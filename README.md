# jena-batch

Streamed batch validator for RDF model directories. Loads any number of ShEx schemas and SPARQL queries **once** in a single JVM, then streams each input model through every configured check, emitting one JSON-line per model.

## Usage

```sh
jena-batch \
    --input /path/to/models/ \
    --shex gpad=gpad-shapes.shex=gpad-shapes.shapeMap \
    --shex other=other-shapes.shex=other-shapes.shapeMap \
    --query disconnected_individuals=sparql/status/disconnected_individuals.rq \
    --query multiply_reified_edges=sparql/status/multiply_reified_edges.rq \
    --metadata-query extract-metadata.rq \
    --capture-riot true \
    --parallelism 8 \
    --output results.ndjson
```

### Flags

- `--input <path>` — single `.ttl` file or directory of files (recursive).
- `--output <path>` — NDJSON output. `-` writes to stdout.
- `--shex id=schema=map` — repeatable. Each tagged ShEx pair appears in the output keyed by `id`.
- `--query id=path` — repeatable. SPARQL SELECT queries; rows emitted verbatim under `sparql.<id>`.
- `--metadata-query <path>` — single SPARQL SELECT for per-model metadata extraction; rows under `metadata`.
- `--capture-riot true|false` — capture Apache Jena RIOT parser warnings/errors (default: true).
- `--parallelism <int>` — concurrent models in flight (default: 16).
- `--fail-fast true|false` — abort on the first parse failure rather than recording it and continuing (default: false).

JVM heap is bumped via `-J-Xmx<size>` (sbt-native-packager passthrough); 8 GB is the launcher default. ShEx + SPARQL is much lighter than ontology reasoning, so the default is usually fine.

## Output

One JSON object per model per line. Schema:

```json
{
  "path": "models/abc123.ttl",
  "model_iri": "http://model.geneontology.org/abc123",
  "duration_ms": 234,
  "parse_failed": false,
  "riot_diagnostics": [
    {"severity": "WARN", "line": 215, "col": 51,
     "code": "PORT_SHOULD_NOT_BE_EMPTY",
     "message": "Not advised IRI: <http://http://...>"}
  ],
  "shex": {
    "gpad": {
      "conformant": false,
      "non_conformant_nodes": [
        {"node": "http://...", "shape": "http://...",
         "reason": "missing required occurs_in"}
      ]
    }
  },
  "sparql": {
    "disconnected_individuals": {
      "vars": ["individual", "type"],
      "rows": [
        {"individual": "http://...", "type": "GO:0003674"}
      ]
    }
  },
  "metadata": {
    "vars": ["title", "date"],
    "rows": [{"title": "Apoptotic process", "date": "2026-04-30"}]
  },
  "error": null
}
```

Fields are present-but-empty when no checks of that kind were configured. `parse_failed: true` means the file did not parse as RDF; `shex` / `sparql` / `metadata` will be empty in that case and `error` will hold the parser's message.

## Build

```sh
sbt stage      # produces target/universal/stage/bin/jena-batch
sbt docker:publishLocal
```
