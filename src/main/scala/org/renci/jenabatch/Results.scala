package org.renci.jenabatch

import zio.json._

import scala.collection.immutable.ListMap

/**
  * Output schema. One [[ModelResult]] per input model is emitted as a JSON
  * line on stdout / the output file. Fields are intentionally optional —
  * absence means "not configured" (e.g. no `--shex` flags supplied means
  * the `shex` field is empty), not "this model is unhealthy."
  *
  * The consumer (run-checks.mjs in the dashboard pipeline, or whatever
  * downstream tool) maps these into its own check-status taxonomy.
  */
final case class ModelResult(
  path: String,
  model_iri: Option[String],
  duration_ms: Long,
  parse_failed: Boolean,
  riot_diagnostics: Vector[RiotDiagnostic],
  shex: ListMap[String, ShexResult],
  sparql: ListMap[String, SparqlResult],
  metadata: Option[SparqlResult],
  error: Option[String]
)

final case class RiotDiagnostic(
  severity: String,
  line: Long,
  col: Long,
  code: Option[String],
  message: String
)

final case class ShexResult(
  conformant: Boolean,
  non_conformant_nodes: Vector[NonConformantNode]
)

final case class NonConformantNode(
  node: String,
  shape: String,
  reason: Option[String]
)

final case class SparqlResult(
  vars: Vector[String],
  rows: Vector[ListMap[String, String]]
)

/**
  * Emitted in place of a [[ModelResult]] when one or more `--filter` ASK
  * queries return true for the model. One record per matching filter, so
  * `filter_id` always identifies a single filter. The `kind` field is the
  * discriminator that consumers check to distinguish excluded lines from
  * regular [[ModelResult]] lines in the NDJSON stream — its value is
  * always the literal string "excluded".
  *
  * A model that produces excluded records produces no [[ModelResult]] for
  * the same run: exclusion short-circuits all checks (ShEx, SPARQL,
  * metadata) so consumers don't have to reconcile both kinds for the same
  * input.
  */
final case class ExcludedRecord(
  kind: String,
  path: String,
  model_iri: Option[String],
  duration_ms: Long,
  filter_id: String
)

/**
  * Sum-type for everything written to the NDJSON output. Each variant has
  * its own JSON shape, so a downstream reader either dispatches on the
  * presence of a top-level `"kind":"excluded"` field, or schema-discriminates
  * via `parse_failed` / `riot_diagnostics` which only [[ModelResult]] has.
  */
sealed trait OutputLine {
  def toJsonLine: String
}

final case class ModelLine(result: ModelResult) extends OutputLine {
  def toJsonLine: String = result.toJson
}

final case class ExcludedLine(record: ExcludedRecord) extends OutputLine {
  def toJsonLine: String = record.toJson
}

object ModelResult {

  // zio-json doesn't ship a built-in ListMap codec, so we round-trip via
  // a Vector of pairs to preserve insertion order.
  implicit def listMapEncoder[V: JsonEncoder]: JsonEncoder[ListMap[String, V]] =
    JsonEncoder.map[String, V].contramap(_.toMap)

  implicit val riotDiagnosticEncoder: JsonEncoder[RiotDiagnostic] = DeriveJsonEncoder.gen[RiotDiagnostic]
  implicit val nonConformantNodeEncoder: JsonEncoder[NonConformantNode] = DeriveJsonEncoder.gen[NonConformantNode]
  implicit val shexResultEncoder: JsonEncoder[ShexResult] = DeriveJsonEncoder.gen[ShexResult]
  implicit val sparqlResultEncoder: JsonEncoder[SparqlResult] = DeriveJsonEncoder.gen[SparqlResult]
  implicit val modelResultEncoder: JsonEncoder[ModelResult] = DeriveJsonEncoder.gen[ModelResult]

}

object ExcludedRecord {
  implicit val excludedRecordEncoder: JsonEncoder[ExcludedRecord] = DeriveJsonEncoder.gen[ExcludedRecord]
}
