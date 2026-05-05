package org.renci.jenabatch

import zio._
import zio.test._

import java.nio.file.{Files, Path}

object ShexContextSpec extends ZIOSpecDefault {

  private def writeTempFile(suffix: String, contents: String): Task[Path] =
    ZIO.attemptBlocking {
      val path = Files.createTempFile("jena-batch-shex-context-test-", suffix)
      Files.writeString(path, contents)
      path.toFile.deleteOnExit()
      path
    }

  private val schema =
    """BASE <http://example.org/shapes/>
      |PREFIX ex: <http://example.org/>
      |
      |<NeedsContext> {
      |  ex:p [ ex:o ]
      |}
      |""".stripMargin

  private val shapeMap =
    """<http://example.org/s>@<http://example.org/shapes/NeedsContext>"""

  private val data =
    """@prefix ex: <http://example.org/> .
      |
      |ex:s ex:other ex:value .
      |""".stripMargin

  private val context =
    """@prefix ex: <http://example.org/> .
      |
      |ex:s ex:p ex:o .
      |""".stripMargin

  private val contextTripleQuery =
    """PREFIX ex: <http://example.org/>
      |SELECT ?o WHERE {
      |  ex:s ex:p ?o .
      |}
      |""".stripMargin

  override def spec = suite("ShEx context graphs")(
    test("context triples are visible only to the configured ShEx check") {
      for {
        schemaPath <- writeTempFile(".shex", schema)
        mapPath    <- writeTempFile(".shapeMap", shapeMap)
        dataPath   <- writeTempFile(".ttl", data)
        contextPath <- writeTempFile(".ttl", context)
        queryPath  <- writeTempFile(".rq", contextTripleQuery)
        withContext <- Validators.loadShex(
                         ShexInput("with_context", schemaPath.toString, mapPath.toString),
                         List(contextPath.toString)
                       )
        withoutContext <- Validators.loadShex(
                            ShexInput("without_context", schemaPath.toString, mapPath.toString)
                          )
        query <- Validators.loadQuery(QueryInput("context_triple", queryPath.toString))
        lines <- Main.validateOne(
                   dataPath.toFile,
                   shexes = List(withContext, withoutContext),
                   queries = List(query),
                   filters = Nil,
                   metadataQuery = None,
                   config = JenaBatchConfig(input = dataPath.toString, output = "-")
                 )
        result = lines.collectFirst { case ModelLine(record) => record }
      } yield assertTrue(
        lines.size == 1 &&
          result.exists(_.shex.get("with_context").exists(_.conformant)) &&
          result.exists(_.shex.get("without_context").exists(r => !r.conformant)) &&
          result.exists(_.sparql.get("context_triple").exists(_.rows.isEmpty))
      )
    },
    test("unknown ShEx context ids fail startup validation") {
      for {
        exit <- Main.validateShexContextIds(
                  shexes = List(ShexInput("known", "schema.shex", "map.shapeMap")),
                  contextIds = Set("unknown")
                ).exit
      } yield assertTrue(
        exit.isFailure &&
          exit.causeOption.exists(_.failureOption.exists(_.getMessage.contains("unknown")))
      )
    }
  )

}
