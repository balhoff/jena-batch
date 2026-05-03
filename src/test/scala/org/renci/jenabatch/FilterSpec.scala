package org.renci.jenabatch

import org.apache.jena.rdf.model.ModelFactory
import zio._
import zio.test._

import java.nio.file.{Files, Path}

object FilterSpec extends ZIOSpecDefault {

  /** Write `contents` to a fresh temp file with the given extension. */
  private def writeTempFile(suffix: String, contents: String): UIO[Path] =
    ZIO.succeed {
      val path = Files.createTempFile("jena-batch-filter-test-", suffix)
      Files.writeString(path, contents)
      path.toFile.deleteOnExit()
      path
    }

  private val askDeleted =
    """ASK { ?m a <http://www.w3.org/2002/07/owl#Ontology> ;
      |        <http://geneontology.org/lego/modelstate> "delete" . }
      |""".stripMargin

  private val askAlwaysFalse =
    """ASK { ?s <http://example.org/never> ?o . }"""

  private val selectQuery =
    """SELECT ?s WHERE { ?s ?p ?o . } LIMIT 1"""

  private val ttlMarkedDeleted =
    """@prefix owl:  <http://www.w3.org/2002/07/owl#> .
      |@prefix lego: <http://geneontology.org/lego/> .
      |
      |<http://model.geneontology.org/abc> a owl:Ontology ;
      |    lego:modelstate "delete" .
      |""".stripMargin

  private val ttlActive =
    """@prefix owl:  <http://www.w3.org/2002/07/owl#> .
      |@prefix lego: <http://geneontology.org/lego/> .
      |
      |<http://model.geneontology.org/xyz> a owl:Ontology ;
      |    lego:modelstate "production" .
      |""".stripMargin

  override def spec = suite("Filter loading and execution")(
    test("loadFilter accepts an ASK query") {
      for {
        path    <- writeTempFile(".rq", askDeleted)
        loaded  <- Validators.loadFilter(QueryInput("deleted", path.toString))
      } yield assertTrue(loaded.id == "deleted" && loaded.query.isAskType)
    },
    test("loadFilter rejects a non-ASK query") {
      for {
        path    <- writeTempFile(".rq", selectQuery)
        result  <- Validators.loadFilter(QueryInput("bad", path.toString)).exit
      } yield assertTrue(
        result.isFailure &&
          result.causeOption.exists(_.failureOption.exists(_.getMessage.contains("ASK")))
      )
    },
    test("runFilter returns true when the ASK matches the model") {
      for {
        qPath  <- writeTempFile(".rq", askDeleted)
        loaded <- Validators.loadFilter(QueryInput("deleted", qPath.toString))
        model  <- ZIO.succeed {
                    val m = ModelFactory.createDefaultModel()
                    m.read(new java.io.StringReader(ttlMarkedDeleted), null, "TTL")
                    m
                  }
      } yield assertTrue(Validators.runFilter(loaded, model))
    },
    test("runFilter returns false when the ASK doesn't match") {
      for {
        qPath  <- writeTempFile(".rq", askDeleted)
        loaded <- Validators.loadFilter(QueryInput("deleted", qPath.toString))
        model  <- ZIO.succeed {
                    val m = ModelFactory.createDefaultModel()
                    m.read(new java.io.StringReader(ttlActive), null, "TTL")
                    m
                  }
      } yield assertTrue(!Validators.runFilter(loaded, model))
    },
    test("validateOne emits an ExcludedLine when a filter matches") {
      for {
        qPath   <- writeTempFile(".rq", askDeleted)
        ttlPath <- writeTempFile(".ttl", ttlMarkedDeleted)
        loaded  <- Validators.loadFilter(QueryInput("deleted", qPath.toString))
        lines   <- Main.validateOne(
                     ttlPath.toFile,
                     shexes = Nil,
                     queries = Nil,
                     filters = List(loaded),
                     metadataQuery = None,
                     config = JenaBatchConfig(input = ttlPath.toString, output = "-")
                   )
      } yield assertTrue(
        lines.size == 1 &&
          lines.head.isInstanceOf[ExcludedLine] &&
          lines.head.asInstanceOf[ExcludedLine].record.filter_id == "deleted" &&
          lines.head.asInstanceOf[ExcludedLine].record.kind == "excluded" &&
          lines.head.asInstanceOf[ExcludedLine].record.model_iri.contains("http://model.geneontology.org/abc")
      )
    },
    test("validateOne emits a normal ModelLine when no filter matches") {
      for {
        qPath   <- writeTempFile(".rq", askAlwaysFalse)
        ttlPath <- writeTempFile(".ttl", ttlActive)
        loaded  <- Validators.loadFilter(QueryInput("never", qPath.toString))
        lines   <- Main.validateOne(
                     ttlPath.toFile,
                     shexes = Nil,
                     queries = Nil,
                     filters = List(loaded),
                     metadataQuery = None,
                     config = JenaBatchConfig(input = ttlPath.toString, output = "-")
                   )
      } yield assertTrue(
        lines.size == 1 && lines.head.isInstanceOf[ModelLine]
      )
    },
    test("multiple matching filters emit one ExcludedLine each") {
      for {
        qPath1  <- writeTempFile(".rq", askDeleted)
        qPath2  <- writeTempFile(".rq", askDeleted) // same predicate, different id
        ttlPath <- writeTempFile(".ttl", ttlMarkedDeleted)
        f1      <- Validators.loadFilter(QueryInput("first", qPath1.toString))
        f2      <- Validators.loadFilter(QueryInput("second", qPath2.toString))
        lines   <- Main.validateOne(
                     ttlPath.toFile,
                     shexes = Nil,
                     queries = Nil,
                     filters = List(f1, f2),
                     metadataQuery = None,
                     config = JenaBatchConfig(input = ttlPath.toString, output = "-")
                   )
        ids = lines.collect { case ExcludedLine(r) => r.filter_id }
      } yield assertTrue(lines.size == 2 && ids == Vector("first", "second"))
    }
  )

}
