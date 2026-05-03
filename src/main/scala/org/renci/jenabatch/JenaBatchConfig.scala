package org.renci.jenabatch

import caseapp.HelpMessage
import caseapp.core.Error.MalformedValue
import caseapp.core.argparser.{ArgParser, SimpleArgParser}

/**
  * One ShEx schema/shape-map pair, tagged with an id that the consumer uses
  * to attribute the result. Parsed from `id=schemaPath=mapPath`.
  */
final case class ShexInput(id: String, schemaPath: String, mapPath: String)

/**
  * One SPARQL query, tagged with an id. Parsed from `id=queryPath`.
  */
final case class QueryInput(id: String, queryPath: String)

/**
  * CLI configuration. Direct invocation — no subcommand routing, since the
  * tool only does one thing.
  */
final case class JenaBatchConfig(
  @HelpMessage("Path to a single .ttl file or a directory of .ttl files (recursive). Required.")
  input: String,
  @HelpMessage("Where to write NDJSON output (one JSON object per model per line). Use '-' for stdout. Required.")
  output: String,
  @HelpMessage("Repeat to attach a ShEx check. Format: id=schema.shex=map.shapeMap. Each id appears as a key under \"shex\" in the per-model output.")
  shex: List[ShexInput] = Nil,
  @HelpMessage("Repeat to attach a SPARQL SELECT check. Format: id=query.rq. Rows are echoed verbatim into the per-model output under \"sparql.<id>\".")
  query: List[QueryInput] = Nil,
  @HelpMessage("Repeat to attach a SPARQL ASK exclusion filter. Format: id=query.rq. Each filter must be an ASK query; if it returns true for a model, the model is excluded from all checks and emitted as an \"excluded\" record (one per matching filter) instead of a normal result.")
  filter: List[QueryInput] = Nil,
  @HelpMessage("Optional single SPARQL SELECT for per-model metadata extraction. Rows are emitted under \"metadata\".")
  metadataQuery: Option[String] = None,
  @HelpMessage("Capture Apache Jena RIOT parser warnings/errors per model. Default: true.")
  captureRiot: BoolValue = TrueValue,
  @HelpMessage("How many models to process in parallel. Default: 16.")
  parallelism: Int = 16,
  @HelpMessage("If true, abort on the first model that fails to parse as RDF. Default: false (record the failure and continue).")
  failFast: BoolValue = FalseValue
)

sealed trait BoolValue {
  def bool: Boolean
}

case object TrueValue extends BoolValue {
  def bool = true
}

case object FalseValue extends BoolValue {
  def bool = false
}

object JenaBatchConfig {

  implicit val boolParser: ArgParser[BoolValue] = SimpleArgParser.from[BoolValue]("boolean value") { arg =>
    arg.toLowerCase match {
      case "true"  => Right(TrueValue)
      case "false" => Right(FalseValue)
      case "1"     => Right(TrueValue)
      case "0"     => Right(FalseValue)
      case _       => Left(MalformedValue("boolean value", arg))
    }
  }

  implicit val shexInputParser: ArgParser[ShexInput] = SimpleArgParser.from[ShexInput]("shex spec (id=schema=map)") { arg =>
    arg.split('=').toList match {
      case id :: schema :: map :: Nil if id.nonEmpty && schema.nonEmpty && map.nonEmpty =>
        Right(ShexInput(id, schema, map))
      case _                                                                            =>
        Left(MalformedValue("shex spec", arg))
    }
  }

  implicit val queryInputParser: ArgParser[QueryInput] = SimpleArgParser.from[QueryInput]("query spec (id=path)") { arg =>
    val idx = arg.indexOf('=')
    if (idx <= 0 || idx >= arg.length - 1) Left(MalformedValue("query spec", arg))
    else Right(QueryInput(arg.substring(0, idx), arg.substring(idx + 1)))
  }

}
