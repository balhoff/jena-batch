package org.renci.jenabatch

import zio._
import zio.test._

import java.nio.file.{Files, Path}

object SparqlShapeMapSpec extends ZIOSpecDefault {

  private def writeTempFile(suffix: String, contents: String): Task[Path] =
    ZIO.attemptBlocking {
      val path = Files.createTempFile("jena-batch-sparql-shape-map-test-", suffix)
      Files.writeString(path, contents)
      path.toFile.deleteOnExit()
      path
    }

  // ShEx schema mirroring how go-cam-shapes.shex is set up: a shape with a
  // hard requirement (`ex:occurs_in` with at least one IRI value) so that
  // models lacking the predicate fail validation.
  private val schema =
    """BASE <http://example.org/shapes/>
      |PREFIX ex: <http://example.org/>
      |
      |<BiologicalProcess> {
      |  ex:occurs_in IRI +
      |}
      |""".stripMargin

  // SPARQL-format shape map: pick out instances of any subclass of ex:BP. Uses
  // the very same `a/<rdfs:subClassOf>` idiom go-cam-shapes.shapeMap uses, so
  // matching focus nodes only emerge once the closure is unioned in.
  private val sparqlShapeMap =
    """SPARQL 'SELECT ?x WHERE { ?x a/<http://www.w3.org/2000/01/rdf-schema#subClassOf> <http://example.org/BiologicalProcess> }' @ <http://example.org/shapes/BiologicalProcess>"""

  // The model declares a process instance and gives it `occurs_in`, which
  // means it should be conformant once the closure is supplied (and unmatched
  // — never selected as a focus — when the closure is missing).
  private val data =
    """@prefix ex: <http://example.org/> .
      |
      |ex:p1 a ex:Apoptosis .
      |ex:p1 ex:occurs_in ex:cell .
      |""".stripMargin

  // Closure: ex:Apoptosis rdfs:subClassOf ex:BiologicalProcess. Without this
  // the SPARQL focus query yields no rows so the shape map is empty — the
  // ShEx validator has nothing to check, which the API treats as conformant.
  private val closure =
    """@prefix ex:   <http://example.org/> .
      |@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
      |
      |ex:Apoptosis rdfs:subClassOf ex:BiologicalProcess .
      |""".stripMargin

  override def spec = suite("SPARQL-format ShEx shape maps")(
    test("isSparqlFormat sniffs leading SPARQL keyword past comments / blanks") {
      assertTrue(
        SparqlShapeMapParser.isSparqlFormat(sparqlShapeMap),
        SparqlShapeMapParser.isSparqlFormat("# comment\n\n  SPARQL 'X' @ <Y>"),
        !SparqlShapeMapParser.isSparqlFormat("<http://example.org/s>@<http://example.org/Shape>"),
        !SparqlShapeMapParser.isSparqlFormat("{ FOCUS <p> <o> }@<Shape>"),
        !SparqlShapeMapParser.isSparqlFormat("")
      )
    },
    test("parses multi-entry SPARQL shape map with trailing comma + comment") {
      val text =
        """# leading comment
          |SPARQL 'SELECT ?x WHERE { ?x a <urn:A> }' @ <urn:Shape1>,
          |SPARQL 'SELECT ?x WHERE { ?x a <urn:B> }' @ <urn:Shape2>,
          |""".stripMargin
      val entries = SparqlShapeMapParser.parse(text)
      assertTrue(
        entries.size == 2,
        entries(0).shapeIri == "urn:Shape1",
        entries(1).shapeIri == "urn:Shape2",
        entries(0).query.contains("urn:A")
      )
    },
    test("validation runs SPARQL shape-map queries against model ∪ context") {
      for {
        schemaPath  <- writeTempFile(".shex", schema)
        mapPath     <- writeTempFile(".shapeMap", sparqlShapeMap)
        dataPath    <- writeTempFile(".ttl", data)
        closurePath <- writeTempFile(".ttl", closure)
        withClosure <- Validators.loadShex(
                         ShexInput("with_closure", schemaPath.toString, mapPath.toString),
                         List(closurePath.toString)
                       )
        withoutClosure <- Validators.loadShex(
                            ShexInput("without_closure", schemaPath.toString, mapPath.toString)
                          )
        lines <- Main.validateOne(
                   dataPath.toFile,
                   shexes = List(withClosure, withoutClosure),
                   queries = Nil,
                   filters = Nil,
                   metadataQuery = None,
                   config = JenaBatchConfig(input = dataPath.toString, output = "-")
                 )
        result = lines.collectFirst { case ModelLine(record) => record }
      } yield assertTrue(
        // The SPARQL shape map must have been recognised on both loads.
        withClosure.shapeMap.isInstanceOf[SparqlShapeMap],
        withoutClosure.shapeMap.isInstanceOf[SparqlShapeMap],
        // With closure: focus selected, model has required `ex:occurs_in`, conforms.
        result.exists(_.shex.get("with_closure").exists(_.conformant)),
        // Without closure: no focus selected, ShEx vacuously conforms.
        result.exists(_.shex.get("without_closure").exists(_.conformant))
      )
    },
    test("SPARQL shape map flags a model that fails the ShEx constraint") {
      val brokenData =
        """@prefix ex: <http://example.org/> .
          |
          |ex:p1 a ex:Apoptosis .
          |""".stripMargin // no occurs_in — should fail
      for {
        schemaPath  <- writeTempFile(".shex", schema)
        mapPath     <- writeTempFile(".shapeMap", sparqlShapeMap)
        dataPath    <- writeTempFile(".ttl", brokenData)
        closurePath <- writeTempFile(".ttl", closure)
        loaded <- Validators.loadShex(
                    ShexInput("with_closure", schemaPath.toString, mapPath.toString),
                    List(closurePath.toString)
                  )
        lines <- Main.validateOne(
                   dataPath.toFile,
                   shexes = List(loaded),
                   queries = Nil,
                   filters = Nil,
                   metadataQuery = None,
                   config = JenaBatchConfig(input = dataPath.toString, output = "-")
                 )
        result = lines.collectFirst { case ModelLine(record) => record }
      } yield assertTrue(
        result.exists(r => !r.shex("with_closure").conformant),
        result.exists(_.shex("with_closure").non_conformant_nodes.exists(_.node == "http://example.org/p1"))
      )
    }
  )

}
