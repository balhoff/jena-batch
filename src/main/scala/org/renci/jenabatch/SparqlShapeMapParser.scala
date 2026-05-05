package org.renci.jenabatch

import scala.collection.mutable

/**
  * Parser for the SPARQL-style ShEx shape map format:
  *
  * {{{
  *   SPARQL 'SELECT ?x WHERE { ?x a <…/owl#Ontology> }' @ <…/shapes/GoCamModel>,
  *   SPARQL 'SELECT ?x WHERE { ?x a/<…/subClassOf> <…/GO_0008150> }' @ <…/shapes/BiologicalProcess>
  * }}}
  *
  * Jena 6's [[org.apache.jena.shex.ShapeMap]] only models a node or single triple
  * pattern as the focus selector — `SPARQL '...'` entries from the broader ShEx
  * Shape Map spec aren't accepted by Jena's shape-map grammar. We pre-resolve
  * those queries against the validation graph at runtime and feed Jena an
  * explicit-IRI shape map.
  */
object SparqlShapeMapParser {

  /**
    * Heuristic: a shape map is "SPARQL-format" if its first non-blank, non-comment
    * line starts with `SPARQL` (case-insensitive). Triple-pattern / IRI shape maps
    * always start with `<`, `_:`, or `{`, so the discrimination is unambiguous.
    */
  def isSparqlFormat(content: String): Boolean =
    content.linesIterator
      .map(stripLineComment)
      .map(_.trim)
      .find(_.nonEmpty)
      .exists(_.regionMatches(true, 0, "SPARQL", 0, "SPARQL".length))

  final case class Entry(query: String, shapeIri: String)

  /** Parse the shape map into raw `(query, shapeIri)` pairs. Whitespace and
    * `#…` line comments outside string literals are skipped; commas separate
    * entries (a trailing comma is tolerated). */
  def parse(content: String): Vector[Entry] = {
    val out = mutable.ArrayBuffer.empty[Entry]
    val s   = content
    val n   = s.length
    var i   = 0

    def skipWs(): Unit = {
      while (i < n) {
        val c = s.charAt(i)
        if (c.isWhitespace) i += 1
        else if (c == '#') {
          while (i < n && s.charAt(i) != '\n') i += 1
        } else return
      }
    }

    def expectKeyword(kw: String): Unit = {
      if (i + kw.length > n || !s.regionMatches(true, i, kw, 0, kw.length))
        throw new IllegalArgumentException(
          s"Expected '$kw' at offset $i in shape map: ${snippet(i)}"
        )
      i += kw.length
    }

    def expectChar(c: Char): Unit = {
      if (i >= n || s.charAt(i) != c)
        throw new IllegalArgumentException(
          s"Expected '$c' at offset $i in shape map: ${snippet(i)}"
        )
      i += 1
    }

    def readSingleQuoted(): String = {
      expectChar('\'')
      val start = i
      val buf = new StringBuilder
      while (i < n && s.charAt(i) != '\'') {
        if (s.charAt(i) == '\\' && i + 1 < n) {
          buf.append(s.charAt(i + 1))
          i += 2
        } else {
          buf.append(s.charAt(i))
          i += 1
        }
      }
      if (i >= n)
        throw new IllegalArgumentException(s"Unterminated string starting at offset $start")
      i += 1 // closing quote
      buf.toString
    }

    def readIri(): String = {
      expectChar('<')
      val start = i
      while (i < n && s.charAt(i) != '>') i += 1
      if (i >= n)
        throw new IllegalArgumentException(s"Unterminated IRI starting at offset $start")
      val iri = s.substring(start, i)
      i += 1 // closing >
      iri
    }

    def snippet(at: Int): String = {
      val a = math.max(0, at - 20)
      val b = math.min(n, at + 40)
      "…" + s.substring(a, b).replace('\n', ' ') + "…"
    }

    skipWs()
    while (i < n) {
      expectKeyword("SPARQL")
      skipWs()
      val query = readSingleQuoted()
      skipWs()
      expectChar('@')
      skipWs()
      val iri = readIri()
      out += Entry(query, iri)
      skipWs()
      if (i < n && s.charAt(i) == ',') {
        i += 1
        skipWs()
      } else if (i < n) {
        throw new IllegalArgumentException(
          s"Expected ',' or end of input at offset $i: ${snippet(i)}"
        )
      }
    }

    out.toVector
  }

  private def stripLineComment(line: String): String = {
    // Only strips `#` outside single-quoted strings — the SPARQL queries in
    // entries can legitimately contain `#` (fragment IRIs), so a naive strip
    // would corrupt them. The result of this method is only used for the
    // first-line-sniffing heuristic; the real parser handles comments above.
    var inQuote = false
    var i       = 0
    while (i < line.length) {
      val c = line.charAt(i)
      if (c == '\'' && (i == 0 || line.charAt(i - 1) != '\\')) inQuote = !inQuote
      else if (c == '#' && !inQuote) return line.substring(0, i)
      i += 1
    }
    line
  }

}
