package org.renci.jenabatch

import org.apache.jena.graph.Graph
import org.apache.jena.graph.compose.Union
import org.apache.jena.query.{ARQ, Query, QueryExecutionFactory, QueryFactory, QuerySolution}
import org.apache.jena.rdf.model.{Model, ModelFactory}
import org.apache.jena.riot.RDFParser
import org.apache.jena.riot.system.{ErrorHandler, ErrorHandlerFactory}
import org.apache.jena.shex.{ShapeMap, Shex, ShexRecord, ShexSchema, ShexStatus, ShexValidator}
import org.apache.jena.sys.JenaSystem
import zio._

import java.util.function.Consumer
import scala.collection.immutable.ListMap
import scala.collection.mutable
import scala.jdk.CollectionConverters._
import scala.util.Using
import scala.util.control.NonFatal

final case class LoadedShex(id: String, schema: ShexSchema, shapeMap: ShapeMap, contextGraph: Option[Graph])

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
    val shapeMap = Shex.readShapeMap(input.mapPath)
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
    val report = ShexValidator.get().validate(validationGraph, loaded.schema, loaded.shapeMap)
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
