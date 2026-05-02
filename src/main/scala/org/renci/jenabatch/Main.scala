package org.renci.jenabatch

import caseapp._
import org.apache.commons.io.FileUtils
import org.apache.jena.rdf.model.Model
import org.apache.jena.vocabulary.{OWL2, RDF}
import org.renci.jenabatch.JenaBatchConfig._ // brings ArgParser implicits into scope
import zio._
import zio.json._
import zio.stream._

import java.io.{File, FileOutputStream, OutputStream}
import java.lang.{System => JSystem}
import java.lang.System.currentTimeMillis
import java.nio.charset.StandardCharsets
import scala.collection.immutable.ListMap
import scala.jdk.CollectionConverters._

/**
  * Streamed batch validator: parses each input model with Jena RIOT, captures
  * any parser diagnostics, then runs all configured ShEx schemas, SPARQL
  * queries and the (optional) metadata query against it. Emits one JSON
  * record per model as NDJSON. Single JVM, single load of every schema/query
  * — designed to amortize JVM and library setup over an entire corpus.
  */
object Main extends ZCaseApp[JenaBatchConfig] {

  override def run(config: JenaBatchConfig, arg: RemainingArgs): URIO[Scope, ExitCode] = {
    val program = for {
      _              <- ZIO.succeed(scribe.info(s"Starting jena-batch on ${config.input} (parallelism=${config.parallelism})"))
      loadedShexes   <- ZIO.foreach(config.shex)(Validators.loadShex)
      _              <- ZIO.succeed(scribe.info(s"Loaded ${loadedShexes.size} ShEx schema(s)"))
      loadedQueries  <- ZIO.foreach(config.query)(Validators.loadQuery)
      _              <- ZIO.succeed(scribe.info(s"Loaded ${loadedQueries.size} SPARQL query(ies)"))
      metadataQueryO <- ZIO.foreach(config.metadataQuery)(Validators.loadMetadataQuery)
      sink           <- openSink(config.output)
      start          <- ZIO.succeed(currentTimeMillis())
      stream          = streamModels(config.input, config.parallelism)
                          .mapZIOParUnordered(config.parallelism)(file =>
                            validateOne(file, loadedShexes, loadedQueries, metadataQueryO, config)
                          )
      _              <- stream.foreach(result => writeLine(sink, result.toJson))
      stop           <- ZIO.succeed(currentTimeMillis())
      _              <- ZIO.succeed(scribe.info(s"Done in ${(stop - start) / 1000.0}s"))
    } yield ()
    program.tapError(e => ZIO.succeed(e.printStackTrace())).exitCode
  }

  // -- per-model work -------------------------------------------------------

  def validateOne(
    file: File,
    shexes: List[LoadedShex],
    queries: List[LoadedQuery],
    metadataQuery: Option[org.apache.jena.query.Query],
    config: JenaBatchConfig
  ): Task[ModelResult] = ZIO.attemptBlocking {
    val path = file.getPath
    val start = currentTimeMillis()

    val (model, diagnostics, parseFailure) = Validators.parseWithDiagnostics(path)
    val capturedDiagnostics = if (config.captureRiot.bool) diagnostics else Vector.empty

    if (parseFailure.isDefined && config.failFast.bool) {
      throw new RuntimeException(s"Parse failure on $path: ${parseFailure.get}")
    }

    val modelIRI = if (parseFailure.isEmpty) findModelIRI(model) else None

    val shexResults: ListMap[String, ShexResult] =
      if (parseFailure.isDefined) ListMap.empty
      else
        shexes.foldLeft(ListMap.empty[String, ShexResult]) { (acc, loaded) =>
          acc + (loaded.id -> Validators.runShex(loaded, model.getGraph))
        }

    val sparqlResults: ListMap[String, SparqlResult] =
      if (parseFailure.isDefined) ListMap.empty
      else
        queries.foldLeft(ListMap.empty[String, SparqlResult]) { (acc, loaded) =>
          acc + (loaded.id -> Validators.runSparql(loaded.query, model))
        }

    val metadataResult: Option[SparqlResult] =
      if (parseFailure.isDefined) None
      else metadataQuery.map(q => Validators.runSparql(q, model))

    ModelResult(
      path = path,
      model_iri = modelIRI,
      duration_ms = currentTimeMillis() - start,
      parse_failed = parseFailure.isDefined,
      riot_diagnostics = capturedDiagnostics,
      shex = shexResults,
      sparql = sparqlResults,
      metadata = metadataResult,
      error = parseFailure
    )
  }

  // -- streaming infrastructure --------------------------------------------

  def streamModels(path: String, parallelism: Int): Stream[Throwable, File] = {
    val fileOrDirectory = new File(path)
    val filesZ = for {
      isDirectory <- ZIO.attempt(fileOrDirectory.isDirectory)
      inputFiles <- if (isDirectory)
        ZIO.attemptBlocking(FileUtils.listFiles(fileOrDirectory, null, true).asScala.to(List))
      else ZIO.succeed(List(fileOrDirectory))
    } yield inputFiles
    ZStream.fromIterableZIO(filesZ).flatMapPar(parallelism)(file => ZStream.succeed(file))
  }

  def openSink(path: String): ZIO[Scope, Throwable, OutputStream] = {
    if (path == "-") ZIO.succeed(JSystem.out)
    else
      ZIO.acquireRelease(
        ZIO.attempt(new FileOutputStream(new File(path)))
      )(stream => ZIO.succeed(stream.close()))
  }

  def writeLine(sink: OutputStream, line: String): Task[Unit] = ZIO.attemptBlocking {
    sink.write((line + "\n").getBytes(StandardCharsets.UTF_8))
  }

  def findModelIRI(model: Model): Option[String] =
    model.listSubjectsWithProperty(RDF.`type`, OWL2.Ontology).asScala
      .find(_.isURIResource)
      .map(_.getURI)

}
