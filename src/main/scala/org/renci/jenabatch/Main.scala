package org.renci.jenabatch

import caseapp._
import org.apache.jena.rdf.model.Model
import org.apache.jena.vocabulary.{OWL2, RDF}
import org.renci.jenabatch.JenaBatchConfig._ // brings ArgParser implicits into scope
import zio._
import zio.json._
import zio.stream._

import java.io.{File, FileOutputStream, OutputStream}
import java.lang.{System => JSystem}
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.TimeUnit
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
      _              <- ZIO.logInfo(s"Starting jena-batch on ${config.input} (parallelism=${config.parallelism})")
      _              <- Validators.initializeJena
      shexContexts    = config.shexContext.groupMap(_.id)(_.graphPath)
      _              <- validateShexContextIds(config.shex, shexContexts.keySet)
      loadedShexes   <- ZIO.foreach(config.shex)(input =>
                          Validators.loadShex(input, shexContexts.getOrElse(input.id, Nil))
                        )
      _              <- ZIO.logInfo(s"Loaded ${loadedShexes.size} ShEx schema(s) with ${config.shexContext.size} context graph(s)")
      loadedQueries  <- ZIO.foreach(config.query)(Validators.loadQuery)
      _              <- ZIO.logInfo(s"Loaded ${loadedQueries.size} SPARQL query(ies)")
      loadedFilters  <- ZIO.foreach(config.filter)(Validators.loadFilter)
      _              <- ZIO.logInfo(s"Loaded ${loadedFilters.size} filter(s)")
      metadataQueryO <- ZIO.foreach(config.metadataQuery)(Validators.loadMetadataQuery)
      sink           <- openSink(config.output)
      start          <- Clock.currentTime(TimeUnit.MILLISECONDS)
      stream          = streamModels(config.input)
                          .mapZIOParUnordered(config.parallelism)(file =>
                            validateOne(file, loadedShexes, loadedQueries, loadedFilters, metadataQueryO, config)
                          )
      _              <- stream.foreach(lines => ZIO.foreachDiscard(lines)(line => writeLine(sink, line.toJsonLine)))
      stop           <- Clock.currentTime(TimeUnit.MILLISECONDS)
      _              <- ZIO.logInfo(s"Done in ${(stop - start) / 1000.0}s")
    } yield ()
    program.tapErrorCause(cause => ZIO.logErrorCause("jena-batch failed", cause)).exitCode
  }

  // -- per-model work -------------------------------------------------------

  def validateOne(
    file: File,
    shexes: List[LoadedShex],
    queries: List[LoadedQuery],
    filters: List[LoadedFilter],
    metadataQuery: Option[org.apache.jena.query.Query],
    config: JenaBatchConfig
  ): Task[Vector[OutputLine]] = {
    val path = file.getPath

    def matchingFilters(model: Model): Task[Vector[String]] =
      ZIO.foreach(filters) { filter =>
        Validators.runFilter(filter, model).map(filter.id -> _)
      }.map(_.collect { case (id, true) => id }.toVector)

    def runShexes(model: Model): Task[ListMap[String, ShexResult]] =
      ZIO.foreach(shexes) { loaded =>
        Validators.runShex(loaded, model.getGraph).map(loaded.id -> _)
      }.map(results => ListMap.from(results))

    def runQueries(model: Model): Task[ListMap[String, SparqlResult]] =
      ZIO.foreach(queries) { loaded =>
        Validators.runSparql(loaded.query, model).map(loaded.id -> _)
      }.map(results => ListMap.from(results))

    for {
      start <- Clock.currentTime(TimeUnit.MILLISECONDS)
      parsed <- Validators.parseWithDiagnostics(path)
      (model, diagnostics, parseFailure) = parsed
      capturedDiagnostics = if (config.captureRiot.bool) diagnostics else Vector.empty
      _ <- parseFailure match {
             case Some(message) if config.failFast.bool =>
               ZIO.fail(new RuntimeException(s"Parse failure on $path: $message"))
             case _                                     =>
               ZIO.unit
           }
      modelIRI = if (parseFailure.isEmpty) findModelIRI(model) else None
      // Filters short-circuit everything else, but only when the parse
      // succeeded — a parse failure is itself diagnostic information the
      // consumer should see, and we can't query a model we couldn't read.
      matchedFilterIds <- if (parseFailure.isDefined) ZIO.succeed(Vector.empty[String])
                          else matchingFilters(model)
      lines <- if (matchedFilterIds.nonEmpty) {
                 // Run metadata extraction even on excluded models — downstream
                 // consumers (dashboards, audit tools) want title/contributor/taxon
                 // for filtered-but-still-known models. ShEx and SPARQL checks
                 // remain short-circuited.
                 for {
                   metadataResult <- ZIO.foreach(metadataQuery)(q => Validators.runSparql(q, model))
                   stop           <- Clock.currentTime(TimeUnit.MILLISECONDS)
                 } yield Vector[OutputLine](ExcludedLine(ExcludedRecord(
                   kind = "excluded",
                   path = path,
                   model_iri = modelIRI,
                   duration_ms = stop - start,
                   filter_ids = matchedFilterIds,
                   metadata = metadataResult
                 )))
               } else {
                 for {
                   shexResults   <- if (parseFailure.isDefined) ZIO.succeed(ListMap.empty[String, ShexResult])
                                    else runShexes(model)
                   sparqlResults <- if (parseFailure.isDefined) ZIO.succeed(ListMap.empty[String, SparqlResult])
                                    else runQueries(model)
                   metadataResult <- if (parseFailure.isDefined) ZIO.none
                                     else ZIO.foreach(metadataQuery)(q => Validators.runSparql(q, model))
                   stop          <- Clock.currentTime(TimeUnit.MILLISECONDS)
                 } yield Vector[OutputLine](ModelLine(ModelResult(
                   path = path,
                   model_iri = modelIRI,
                   duration_ms = stop - start,
                   parse_failed = parseFailure.isDefined,
                   riot_diagnostics = capturedDiagnostics,
                   shex = shexResults,
                   sparql = sparqlResults,
                   metadata = metadataResult,
                   error = parseFailure
                 )))
               }
    } yield lines
  }

  // -- streaming infrastructure --------------------------------------------

  def validateShexContextIds(shexes: List[ShexInput], contextIds: Iterable[String]): Task[Unit] = {
    val shexIds = shexes.iterator.map(_.id).toSet
    val unknown = contextIds.toSet.diff(shexIds).toVector.sorted
    if (unknown.isEmpty) ZIO.unit
    else {
      ZIO.fail(new IllegalArgumentException(
        s"--shex-context id(s) do not match any --shex id: ${unknown.mkString(", ")}"
      ))
    }
  }

  def streamModels(path: String): Stream[Throwable, File] = {
    val fileOrDirectory = new File(path)
    ZStream.unwrap {
      ZIO.attempt(fileOrDirectory.isDirectory).map { isDirectory =>
        if (isDirectory) {
          ZStream.unwrapScoped {
            ZIO.acquireRelease(
              ZIO.attemptBlocking(Files.walk(fileOrDirectory.toPath))
            )(paths =>
              ZIO.attemptBlocking(paths.close()).catchAll { e =>
                ZIO.logWarning(s"Failed to close input file stream: ${e.getMessage}")
              }
            ).map { paths =>
              ZStream.unfoldZIO(paths.iterator()) { iterator =>
                ZIO.attemptBlocking {
                  if (iterator.hasNext) Some((iterator.next(), iterator))
                  else None
                }
              }.filterZIO(path => ZIO.attemptBlocking(Files.isRegularFile(path)))
                .map(_.toFile)
            }
          }
        } else {
          ZStream.succeed(fileOrDirectory)
        }
      }
    }
  }

  def openSink(path: String): ZIO[Scope, Throwable, OutputStream] = {
    if (path == "-") ZIO.succeed(JSystem.out)
    else
      ZIO.acquireRelease(
        ZIO.attemptBlocking(new FileOutputStream(new File(path)))
      )(stream =>
        ZIO.attemptBlocking(stream.close()).catchAll { e =>
          ZIO.logWarning(s"Failed to close output stream: ${e.getMessage}")
        }
      )
  }

  def writeLine(sink: OutputStream, line: String): Task[Unit] = ZIO.attemptBlocking {
    sink.write((line + "\n").getBytes(StandardCharsets.UTF_8))
  }

  def findModelIRI(model: Model): Option[String] =
    model.listSubjectsWithProperty(RDF.`type`, OWL2.Ontology).asScala
      .find(_.isURIResource)
      .map(_.getURI)

}
