/*
 * This file is part of AndroidIDE.
 *
 * AndroidIDE is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package dev.mutwakil.androidide.lsp.xml.providers

import dev.mutwakil.androidide.lsp.models.DiagnosticSeverity
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class XmlSyntaxDiagnosticProviderTest {

  @get:Rule
  val temporaryFolder = TemporaryFolder()

  @Test
  fun reportsMalformedXmlWithNavigableRange() {
    val file = temporaryFolder.newFile("broken.xml").toPath()
    file.toFile().writeText(
        """
        <LinearLayout>
            <TextView>
        </LinearLayout>
        """.trimIndent()
    )

    val result = XmlSyntaxDiagnosticProvider().analyze(file)

    assertEquals(file, result.file)
    assertEquals(1, result.diagnostics.size)
    val diagnostic = result.diagnostics.single()
    assertEquals(DiagnosticSeverity.ERROR, diagnostic.severity)
    assertEquals("xml-parse-error", diagnostic.code)
    assertTrue(diagnostic.message.isNotBlank())
    assertTrue(diagnostic.range.start.index >= 0)
    assertTrue(diagnostic.range.end.index >= diagnostic.range.start.index)
  }

  @Test
  fun validXmlClearsPreviouslyReportedSyntaxErrors() {
    val file: Path = temporaryFolder.newFile("layout.xml").toPath()
    file.toFile().writeText("<LinearLayout><TextView /></LinearLayout>")

    val result = XmlSyntaxDiagnosticProvider().analyze(file)

    assertEquals(file, result.file)
    assertTrue(result.diagnostics.isEmpty())
  }

  @Test
  fun nonXmlFilesDoNotReceiveXmlDiagnostics() {
    val file = temporaryFolder.newFile("source.java").toPath()
    file.toFile().writeText("class Example {}")

    val result = XmlSyntaxDiagnosticProvider().analyze(file)

    assertFalse(result.diagnostics.any { it.code == "xml-parse-error" })
  }
}
