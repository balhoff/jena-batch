# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

- `sbt compile` — type-check.
- `sbt test` — run tests (zio-test framework).
- `sbt "Docker / stage"` — generate `target/docker/stage/{Dockerfile, opt/...}` ready for `docker build`. The CI workflow uses this; locally it's also handy for inspecting what the published image will look like.
- `sbt stage` — produce a runnable distribution at `target/universal/stage/bin/jena-batch`. Use this for local end-to-end runs.
- `sbt docker:publishLocal` — build the image and load it into the local Docker daemon. The image lands as `jena-batch:0.1.0` since `dockerUsername` is intentionally not set; use `docker tag` to rename if needed.
- Run a single test class: `sbt "testOnly org.renci.jenabatch.ConfigSpec"`.

## Architecture

### Why this exists

Per-model JVM cold-start dominates corpus-scale RDF validation. The standard Apache Jena CLIs (`shex`, `arq`, `riot`) each spin up a fresh JVM per invocation; for 50K+ models with several checks each that's tens of hours of pure JVM startup, doing barely any actual work. jena-batch loads the JVM, all schemas, and all parsed queries **once**, then streams models through them — turning days of shell loops into minutes of real work.

This is deliberately a sibling project to [`materializer`](https://github.com/balhoff/materializer): same author, same streaming shape (zio-streams + ZIO), same packaging (sbt-native-packager → Docker), complementary purpose. Where materializer answers "what can be inferred?", jena-batch answers "does it conform to these shapes / how does it answer these queries?"

### The streaming pipeline

`Main.run` is a single ZIO program:

1. **Up-front** (one-shot per process): load every `--shex` schema/shapeMap, parse every `--query` and the `--metadata-query`, open the output sink.
2. **Per-model**, in parallel via `mapZIOParUnordered(parallelism)`:
   - `Validators.parseWithDiagnostics(path)` parses with a `RDFParser` whose `ErrorHandler` accumulates RIOT warnings/errors into a `Vector[RiotDiagnostic]`. The handler defers fatals to `ErrorHandlerFactory.errorHandlerStrict` so a fatal still aborts the parse and we record a `parse_failed: true` model.
   - For each loaded ShEx: `ShexValidator.get().validate(graph, schema, shapeMap)`, walking the result via `forEachReport` (Jena 6's API doesn't expose iteration directly).
   - For each loaded SPARQL query: `QueryExecutionFactory.create(query, model).execSelect()`, projecting bindings into a `ListMap[String, String]` keyed by the SELECT vars in declared order.
   - Optional metadata query: same as SPARQL, output goes under `metadata`.
3. **Output**: each `ModelResult` is JSON-encoded via zio-json and written as a single NDJSON line. The downstream `.foreach` consumes the stream sequentially after the parallel work fan-in, so `OutputStream.write` does not need its own lock.

`--fail-fast` controls behaviour on parse failure: false (default) records the failure and continues; true aborts the whole stream.

### CLI: tagged-input args, no subcommand

`JenaBatchConfig` uses case-app, but unlike materializer there's no command routing — the tool only does one thing. `--shex` and `--query` are repeatable with **id-tagged** values: `--shex gpad=schema.shex=map.shapeMap` and `--query disconnected_individuals=path.rq`. The id is the key under which results appear in the per-model JSON, so consumers don't need to round-trip via filename heuristics. The `ShexInput` / `QueryInput` parsers live in `JenaBatchConfig`'s companion object; `Main` imports them via `import org.renci.jenabatch.JenaBatchConfig._` because Scala 2's macro-driven case-app derivation doesn't always find companion-object implicits during `Parser[T]` materialisation.

### Output contract

The `ModelResult` schema in `Results.scala` is what downstream consumers (e.g. the GO-CAM model-status dashboard's `update-status.mjs`) read. It is intentionally **not** opinionated about pass/fail semantics — it reports raw observations: how many SPARQL rows came back, whether the ShEx report conformed, what RIOT diagnostics were raised. Mapping those into a check-status taxonomy (`pass`/`fail`/`unknown`/etc.) is the consumer's job.

### Java 21 is required

Apache Jena 6 dropped support for Java 17 and below. The `dockerBaseImage` is `eclipse-temurin:21` and the GitHub workflow uses `java-version: '21'`. Don't downgrade either.

### Why scribe-slf4j2 and not scribe-slf4j

Apache Jena 6 brings in `slf4j-api` 2.x. The older `scribe-slf4j` artifact ships a 1.7.x-shaped binding that the modern slf4j-api refuses to load (it warns "Class path contains SLF4J bindings targeting slf4j-api versions 1.7.x or earlier" and falls back to NOP). Use `scribe-slf4j2`. Don't downgrade.

### Releasing

`.github/workflows/publish-docker-image.yml` fires on **release: published** (not tag push) and uses `sbt "Docker / stage"` to generate the Dockerfile + context, then `docker/build-push-action` for the actual multi-arch build (linux/amd64 + linux/arm64) and push to `ghcr.io/<owner>/jena-batch`. The `dockerUsername` setting in `build.sbt` is intentionally absent — the GitHub workflow tags via `metadata-action`, so SBT's notion of an image name doesn't matter for published images.
