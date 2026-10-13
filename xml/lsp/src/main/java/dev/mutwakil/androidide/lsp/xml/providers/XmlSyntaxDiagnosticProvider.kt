/*
 * This file is part of AndroidIDE.
 *
 * AndroidIDE is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package dev.mutwakil.androidide.lsp.xml.providers

import dev.mutwakil.androidide.lsp.models.DiagnosticItem
import dev.mutwakil.androidide.lsp.models.DiagnosticResult
import dev.mutwakil.androidide.lsp.models.DiagnosticSeverity.ERROR
import dev.mutwakil.androidide.models.Position
import dev.mutwakil.androidide.models.Range
import java.io.StringReader
import java.nio.file.Path
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import org.xmlpull.v1.XmlPullParserFactory
import org.slf4j.LoggerFactory

/** Reports malformed XML with a navigable source range and clears diagnostics after a valid parse. */
internal class XmlSyntaxDiagnosticProvider {

  fun analyze(file: Path): DiagnosticResult {
    val sourceFile = file.toFile()
    if (!sourceFile.isFile || !sourceFile.canRead()) {
      return DiagnosticResult.NO_UPDATE
    }

    val source =
        try {
          sourceFile.readText(Charsets.UTF_8)
        } catch (error: Exception) {
          LOG.warn("Unable to read XML for diagnostics: {}", file, error)
          return DiagnosticResult.NO_UPDATE
        }

    return try {
      val parser = XmlPullParserFactory.newInstance().newPullParser()
      parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
      parser.setInput(StringReader(source))
      while (parser.next() != XmlPullParser.END_DOCUMENT) {
        // Pull the complete document to catch mismatched tags and malformed attributes.
      }
      DiagnosticResult(file, emptyList())
    } catch (error: XmlPullParserException) {
      val line = (error.lineNumber - 1).coerceAtLeast(0)
      val column = (error.columnNumber - 1).coerceAtLeast(0)
      val offset = offsetAt(source, line, column)
      val endOffset = (offset + 1).coerceAtMost(source.length)
      val endLine = if (endOffset == offset) line else line
      val endColumn = if (endOffset == offset) column else column + 1
      DiagnosticResult(
          file,
          listOf(
              DiagnosticItem(
                  message = error.message ?: "Malformed XML document",
                  code = "xml-parse-error",
                  range =
                      Range(Position(line, column), Position(endLine, endColumn)).apply {
                        start.index = offset
                        end.index = endOffset
                      },
                  source = "xml",
                  severity = ERROR,
              )
          ),
      )
    } catch (error: Exception) {
      LOG.warn("Unexpected XML diagnostic failure for {}", file, error)
      DiagnosticResult(file, emptyList())
    }
  }

  private fun offsetAt(source: String, line: Int, column: Int): Int {
    if (source.isEmpty()) return 0
    var offset = 0
    var currentLine = 0
    while (currentLine < line && offset < source.length) {
      val nextLine = source.indexOf('\n', offset)
      if (nextLine < 0) return source.length
      offset = nextLine + 1
      currentLine++
    }
    return (offset + column).coerceAtMost(source.length)
  }

  companion object {
    private val LOG = LoggerFactory.getLogger(XmlSyntaxDiagnosticProvider::class.java)
  }
}
