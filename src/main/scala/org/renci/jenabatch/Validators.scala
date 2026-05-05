package org.renci.jenabatch

import org.apache.jena.graph.{Graph, Node, NodeFactory}
import org.apache.jena.graph.compose.Union
import org.apache.jena.query.{ARQ, Query, QueryExecutionFactory, QueryFactory, QuerySolution}
import org.apache.jena.rdf.model.{Model, ModelFactory}
import org.apache.jena.riot.RDFParser
import org.apache.jena.riot.system.{ErrorHandler, ErrorHandlerFactory}
import org.apache.jena.shex.{ShapeMap, Shex, ShexRecord, ShexSchema, ShexStatus, ShexValidator}
import org.apache.jena.sys.JenaSystem
import zio._

import java.nio.file.{Files, Paths}
import java.util.function.Consumer
import scala.collection.immutable.ListMap
import scala.collection.mutable
import scala.jdk.CollectionConverters._
import scala.util.Using
import scala.util.control.NonFatal

/**
  * The shape map a [[LoadedShex]] carries. Static maps are parsed by Jena's
  * grammar at load time; SPARQL maps must be re-resolved per model because
  * the focus-node set depends on the model under validation.
  */
sealed trait LoadedShapeMap

final case class StaticShapeMap(map: ShapeMap) extends LoadedShapeMap

/** A list of SPARQL `(query, shapeRef)` pairs. The query's first projection
  * variable is bound to a focus node; only IRI bindings are forwarded to ShEx
  * (matching ShEx Shape Map semantics — literals/blank-node selectors are
  * possible in principle but not used by any current go-shapes shape map). */
final case class SparqlShapeMap(entries: Vector[SparqlShapeMap.Entry]) extends LoadedShapeMap

object SparqlShapeMap {
  final case class Entry(query: Query, shapeRef: Node)
}

final case class LoadedShex(
  id: String,
  schema: ShexSchema,
  shapeMap: LoadedShapeMap,
  contextGraph: Option[Graph]
)

final case class LoadedQuery(id: String, query: Query)

/**
  * A SPARQL ASK query used to short-circuit per-model processing. If any
  * loaded filter returns true for a given model, the model is reported as
  * "excluded" and no further checks run on it.
  */
final case class LoadedFilter(id: String, query: Query)

object Validators {

  private lazy val jenaInitialized: Unit = {
    JenaSystem.init()
    ARQ.init()
  }

  def initializeJena: Task[Unit] =
    ZIO.attempt(jenaInitialized).unit

  private def jenaBlocking[A](effect: => A): Task[A] =
    initializeJena *> ZIO.attemptBlocking(effect)

  def loadShex(input: ShexInput, contextPaths: List[String] = Nil): Task[LoadedShex] = jenaBlocking {
    val schema = Shex.readSchema(input.schemaPath)
    val mapContents = Files.readString(Paths.get(input.mapPath))
    val shapeMap: LoadedShapeMap =
      if (SparqlShapeMapParser.isSparqlFormat(mapContents)) {
        val entries = SparqlShapeMapParser.parse(mapContents).map { e =>
          val query =
            try QueryFactory.create(e.query)
            catch {
              case NonFatal(t) =>
                throw new IllegalArgumentException(
                  s"ShEx '${input.id}': could not parse SPARQL shape map query '${e.query}': ${t.getMessage}",
                  t
                )
            }
          if (!query.isSelectType)
            throw new IllegalArgumentException(
              s"ShEx '${input.id}': SPARQL shape map query must be a SELECT (got: ${e.query})"
            )
          if (query.getResultVars.isEmpty)
            throw new IllegalArgumentException(
              s"ShEx '${input.id}': SPARQL shape map query has no projection variables (got: ${e.query})"
            )
          SparqlShapeMap.Entry(query, NodeFactory.createURI(e.shapeIri))
        }
        SparqlShapeMap(entries)
      } else {
        StaticShapeMap(Shex.readShapeMap(input.mapPath))
      }
    val contextGraph = loadContextGraph(contextPaths)
    LoadedShex(input.id, schema, shapeMap, contextGraph)
  }

  def loadQuery(input: QueryInput): Task[LoadedQuery] = jenaBlocking {
    LoadedQuery(input.id, QueryFactory.read(input.queryPath))
  }

  /**
    * Load a filter as an ASK query. Rejects non-ASK queries up front so
    * misconfiguration fails at startup rather than producing surprising
    * output mid-stream.
    */
  def loadFilter(input: QueryInput): Task[LoadedFilter] = jenaBlocking {
    val query = QueryFactory.read(input.queryPath)
    if (!query.isAskType) {
      throw new IllegalArgumentException(
        s"Filter '${input.id}' must be a SPARQL ASK query (${input.queryPath})"
      )
    }
    LoadedFilter(input.id, query)
  }

  def loadMetadataQuery(path: String): Task[Query] = jenaBlocking {
    QueryFactory.read(path)
  }

  /**
    * Parse a single TTL/RDF file and capture any riot diagnostics raised
    * during the parse. Returns the parsed [[Model]] paired with the
    * accumulated diagnostics; on a fatal parse failure, returns the
    * partial-or-empty model alongside whatever diagnostics arrived first.
    */
  def parseWithDiagnostics(path: String): Task[(Model, Vector[RiotDiagnostic], Option[String])] =
    jenaBlocking {
      val model = ModelFactory.createDefaultModel()
      val diagnostics = mutable.ArrayBuffer.empty[RiotDiagnostic]

      val handler = new ErrorHandler {
        override def warning(message: String, line: Long, col: Long): Unit =
          diagnostics += RiotDiagnostic("WARN", line, col, message)

        override def error(message: String, line: Long, col: Long): Unit =
          diagnostics += RiotDiagnostic("ERROR", line, col, message)

        override def fatal(message: String, line: Long, col: Long): Unit = {
          diagnostics += RiotDiagnostic("FATAL", line, col, message)
          // Defer to Jena's default fatal handler to actually abort the parse.
          ErrorHandlerFactory.errorHandlerStrict.fatal(message, line, col)
        }
      }

      // checking(true) + strict(true) match `riot --validate`: catches lexical
      // form / language-tag / IRI well-formedness issues that lenient mode lets
      // through. Diagnostics route through the same ErrorHandler regardless.
      val failure: Option[String] =
        try {
          RDFParser.create()
            .source(path)
            .checking(true)
            .strict(true)
            .errorHandler(handler)
            .parse(model)
          None
        } catch {
          case NonFatal(t) => Some(Option(t.getMessage).getOrElse(t.toString))
        }
      (model, diagnostics.toVector, failure)
    }

  def runShex(loaded: LoadedShex, graph: Graph): Task[ShexResult] = jenaBlocking {
    val validationGraph = loaded.contextGraph.map(context => new Union(graph, context)).getOrElse(graph)
    val effectiveMap = loaded.shapeMap match {
      case StaticShapeMap(map)      => map
      case SparqlShapeMap(entries)  => buildSparqlShapeMap(entries, validationGraph)
    }
    val report = ShexValidator.get().validate(validationGraph, loaded.schema, effectiveMap)
    val nonConformant = mutable.ArrayBuffer.empty[NonConformantNode]
    report.forEachReport(new Consumer[ShexRecord] {
      override def accept(entry: ShexRecord): Unit =
        if (entry.status == ShexStatus.nonconformant) {
          nonConformant += NonConformantNode(
            node = stringifyNode(entry.node),
            shape = Option(entry.shapeExprLabel).map(stringifyNode).getOrElse(""),
            reason = Option(entry.reason)
          )
        }
    })
    ShexResult(conformant = report.conforms(), non_conformant_nodes = nonConformant.toVector)
  }

  def runFilter(loaded: LoadedFilter, model: Model): Task[Boolean] =
    jenaBlocking {
      Using.resource(QueryExecutionFactory.create(loaded.query, model))(_.execAsk())
    }

  def runSparql(query: Query, model: Model): Task[SparqlResult] = jenaBlocking {
    val vars = query.getResultVars.asScala.toVector
    val rows = mutable.ArrayBuffer.empty[ListMap[String, String]]
    Using.resource(QueryExecutionFactory.create(query, model)) { exec =>
      val rs = exec.execSelect()
      while (rs.hasNext) {
        rows += extractRow(rs.next(), vars)
      }
    }
    SparqlResult(vars = vars, rows = rows.toVector)
  }

  // -- helpers --------------------------------------------------------------

  /** Run each SPARQL shape-map entry against the (model ∪ context) graph and
    * fold the IRI bindings into an explicit-IRI Jena `ShapeMap`. The first
    * projection variable supplies the focus node; non-IRI bindings are
    * silently skipped because Jena's ShEx records expect node-shape pairs and
    * non-IRI focuses aren't meaningful for any of the go-shapes shape maps in
    * use today. */
  private def buildSparqlShapeMap(entries: Vector[SparqlShapeMap.Entry], graph: Graph): ShapeMap = {
    val model = ModelFactory.createModelForGraph(graph)
    val builder = ShapeMap.newBuilder()
    entries.foreach { entry =>
      val varName = entry.query.getResultVars.asScala.head
      Using.resource(QueryExecutionFactory.create(entry.query, model)) { exec =>
        val rs = exec.execSelect()
        while (rs.hasNext) {
          val rdfNode = rs.next().get(varName)
          if (rdfNode != null && rdfNode.isURIResource) {
            builder.add(rdfNode.asNode, entry.shapeRef)
          }
        }
      }
    }
    builder.build()
  }

  private def loadContextGraph(paths: List[String]): Option[Graph] =
    if (paths.isEmpty) None
    else {
      val model = ModelFactory.createDefaultModel()
      paths.foreach { path =>
        RDFParser.create()
          .source(path)
          .checking(true)
          .strict(true)
          .parse(model)
      }
      Some(model.getGraph)
    }

  private def stringifyNode(node: org.apache.jena.graph.Node): String =
    if (node == null) ""
    else if (node.isURI) node.getURI
    else if (node.isBlank) "_:" + node.getBlankNodeLabel
    else if (node.isLiteral) node.getLiteralLexicalForm
    else node.toString

  private def extractRow(soln: QuerySolution, vars: Vector[String]): ListMap[String, String] = {
    val builder = ListMap.newBuilder[String, String]
    vars.foreach { v =>
      val node = soln.get(v)
      if (node != null) {
        val str = if (node.isURIResource) node.asResource.getURI
                  else if (node.isAnon) "_:" + node.asResource.getId.getLabelString
                  else if (node.isLiteral) node.asLiteral.getLexicalForm
                  else node.toString
        builder += v -> str
      }
    }
    builder.result()
  }

}
