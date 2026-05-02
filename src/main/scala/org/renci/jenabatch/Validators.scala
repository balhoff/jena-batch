package org.renci.jenabatch

import org.apache.jena.graph.Graph
import org.apache.jena.query.{Query, QueryExecutionFactory, QueryFactory, QuerySolution}
import org.apache.jena.rdf.model.{Model, ModelFactory}
import org.apache.jena.riot.RDFParser
import org.apache.jena.riot.system.{ErrorHandler, ErrorHandlerFactory}
import org.apache.jena.shex.{ShapeMap, Shex, ShexRecord, ShexSchema, ShexStatus, ShexValidator}
import zio._

import java.util.function.Consumer
import scala.collection.immutable.ListMap
import scala.collection.mutable
import scala.jdk.CollectionConverters._
import scala.util.Using

final case class LoadedShex(id: String, schema: ShexSchema, shapeMap: ShapeMap)

final case class LoadedQuery(id: String, query: Query)

object Validators {

  // Pull a `Code: <digits>/<NAME>` token out of a riot diagnostic message.
  // Riot doesn't expose the structured warning code through the ErrorHandler
  // API (only severity/message/line/col), so we squint at the message text.
  private val CodePattern = """Code:\s*\d+/(\w+)""".r

  def loadShex(input: ShexInput): Task[LoadedShex] = ZIO.attemptBlocking {
    val schema = Shex.readSchema(input.schemaPath)
    val shapeMap = Shex.readShapeMap(input.mapPath)
    LoadedShex(input.id, schema, shapeMap)
  }

  def loadQuery(input: QueryInput): Task[LoadedQuery] = ZIO.attemptBlocking {
    LoadedQuery(input.id, QueryFactory.read(input.queryPath))
  }

  def loadMetadataQuery(path: String): Task[Query] = ZIO.attemptBlocking {
    QueryFactory.read(path)
  }

  /**
    * Parse a single TTL/RDF file and capture any riot diagnostics raised
    * during the parse. Returns the parsed [[Model]] paired with the
    * accumulated diagnostics; on a fatal parse failure, returns the
    * partial-or-empty model alongside whatever diagnostics arrived first.
    */
  def parseWithDiagnostics(path: String): (Model, Vector[RiotDiagnostic], Option[String]) = {
    val model = ModelFactory.createDefaultModel()
    val diagnostics = mutable.ArrayBuffer.empty[RiotDiagnostic]

    val handler = new ErrorHandler {
      override def warning(message: String, line: Long, col: Long): Unit =
        diagnostics += RiotDiagnostic("WARN", line, col, extractCode(message), message)

      override def error(message: String, line: Long, col: Long): Unit =
        diagnostics += RiotDiagnostic("ERROR", line, col, extractCode(message), message)

      override def fatal(message: String, line: Long, col: Long): Unit = {
        diagnostics += RiotDiagnostic("FATAL", line, col, extractCode(message), message)
        // Defer to Jena's default fatal handler to actually abort the parse.
        ErrorHandlerFactory.errorHandlerStrict.fatal(message, line, col)
      }
    }

    val failure: Option[String] =
      try {
        RDFParser.create().source(path).errorHandler(handler).parse(model)
        None
      } catch {
        case t: Throwable => Some(t.getMessage)
      }
    (model, diagnostics.toVector, failure)
  }

  def runShex(loaded: LoadedShex, graph: Graph): ShexResult = {
    val report = ShexValidator.get().validate(graph, loaded.schema, loaded.shapeMap)
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

  def runSparql(query: Query, model: Model): SparqlResult = {
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

  private def extractCode(message: String): Option[String] =
    CodePattern.findFirstMatchIn(message).map(_.group(1))

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
