# jena-batch

Streamed batch validator for RDF model directories. Loads any number of ShEx schemas and SPARQL queries **once** in a single JVM, then streams each input model through the configured checks, emitting one NDJSON record per model.

## Usage

```sh
jena-batch \
    --input /path/to/models/ \
    --shex gpad=gpad-shapes.shex=gpad-shapes.shapeMap \
    --shex-context gpad=go-hierarchy-closure.ttl \
    --shex other=other-shapes.shex=other-shapes.shapeMap \
    --query disconnected_individuals=sparql/status/disconnected_individuals.rq \
    --query multiply_reified_edges=sparql/status/multiply_reified_edges.rq \
    --filter deleted=sparql/filters/deleted.rq \
    --metadata-query extract-metadata.rq \
    --capture-riot true \
    --parallelism 8 \
    --output results.ndjson
```

### Flags

- `--input <path>` — single `.ttl` file or directory of files (recursive).
- `--output <path>` — NDJSON output. `-` writes to stdout.
- `--shex id=schema=map` — repeatable. Each tagged ShEx pair appears in the output keyed by `id`.
- `--shex-context id=path` — repeatable. Loads auxiliary RDF triples for the matching ShEx `id`; those triples are unioned only while running that ShEx validation and are not visible to other ShEx checks, SPARQL checks, filters, or metadata queries. Multiple context files for the same id are merged.
- `--query id=path` — repeatable. SPARQL SELECT queries; rows emitted verbatim under `sparql.<id>`.
- `--filter id=path` — repeatable. SPARQL **ASK** exclusion queries. If any filter returns true for a model, the model emits one `excluded` line instead of a normal result. ShEx and SPARQL checks are skipped, metadata still runs when configured, and all matching filters are listed in `filter_ids` (see [Output](#output)). Loaded once at startup; non-ASK queries are rejected immediately.
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

`shex` and `sparql` are empty objects when no checks of that kind were configured or when parsing failed. `metadata` is either a SPARQL result object or `null`. `parse_failed: true` means the file did not parse as RDF; checks are skipped and `error` holds the parser's message.

### Excluded lines

When `--filter` queries are configured, a model that matches one or more filters emits one `excluded` line **instead of** a normal result line — its ShEx and SPARQL checks are skipped entirely, and all matching filters are listed in `filter_ids`.

```json
{"kind": "excluded", "path": "models/abc123.ttl", "model_iri": "http://model.geneontology.org/abc123", "duration_ms": 12, "filter_ids": ["deleted"], "metadata": null}
```

Excluded lines are distinguished from regular result lines by the top-level `"kind": "excluded"` field, which a normal result line never carries. A model that produces excluded lines never also produces a result line in the same run; consumers can partition the stream on `kind` without having to reconcile the two.

If multiple filters match the same model, all matching ids appear in `filter_ids`. Filters are evaluated only when the model parsed successfully — a parse failure produces a normal `parse_failed: true` result line so the diagnostics aren't lost. When `--metadata-query` is configured, metadata is still included on excluded records.

## Build

```sh
sbt stage      # produces target/universal/stage/bin/jena-batch
sbt docker:publishLocal
```
