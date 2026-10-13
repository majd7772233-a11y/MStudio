/*
 * This file is part of AndroidIDE.
 *
 * AndroidIDE is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package dev.mutwakil.androidide.lsp.xml.providers

import java.nio.file.Paths
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XmlResourceReferenceDiagnosticProviderTest {

  @Test
  fun resourceEditsInvalidateTheModuleResourceIndex() {
    val moduleRoot = File("/workspace/app")

    assertTrue(
        XmlResourceReferenceDiagnosticProvider.isSourceResourcePath(
            Paths.get("/workspace/app/src/main/res/values/colors.xml"),
            moduleRoot,
        )
    )
    assertTrue(
        XmlResourceReferenceDiagnosticProvider.isSourceResourcePath(
            Paths.get("/workspace/app/src/debug/res/drawable/icon.xml"),
            moduleRoot,
        )
    )
    assertTrue(
        XmlResourceReferenceDiagnosticProvider.isSourceResourcePath(
            Paths.get("/workspace/app/src/free/res/layout/screen.xml"),
            moduleRoot,
        )
    )
  }

  @Test
  fun ordinaryEditorChangesDoNotInvalidateTheModuleResourceIndex() {
    val moduleRoot = File("/workspace/app")

    assertFalse(
        XmlResourceReferenceDiagnosticProvider.isSourceResourcePath(
            Paths.get("/workspace/app/src/main/java/example/MainActivity.kt"),
            moduleRoot,
        )
    )
    assertFalse(
        XmlResourceReferenceDiagnosticProvider.isSourceResourcePath(
            Paths.get("/workspace/app/src/main/res/layout/screen.xml"),
            File("/workspace/other"),
        )
    )
    assertFalse(
        XmlResourceReferenceDiagnosticProvider.isSourceResourcePath(
            Paths.get("/workspace/app/build/generated/res/values/strings.xml"),
            moduleRoot,
        )
    )
  }
}
