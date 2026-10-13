/*
 * This file is part of AndroidIDE.
 *
 * AndroidIDE is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package dev.mutwakil.androidide.uidesigner.utils

import android.widget.TextView
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TemporaryFolder
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ProjectTextResourcePreviewTest {

  @get:Rule
  val temporaryFolder = TemporaryFolder()

  @Test
  fun resolvesProjectTextAndHintWithoutUsingIdeResourceTable() {
    val projectRoot = temporaryFolder.newFolder("project-string-preview")
    File(projectRoot, "settings.gradle").writeText("")
    val layoutFile = File(projectRoot, "app/src/debug/res/layout/screen.xml").apply {
      parentFile?.mkdirs()
      writeText("<TextView />")
    }
    File(projectRoot, "app/src/main/res/values/strings.xml").apply {
      parentFile?.mkdirs()
      writeText(
          """
          <resources>
              <string name="preview_label">Project label</string>
              <string name="preview_hint">Project hint</string>
          </resources>
          """.trimIndent(),
      )
    }
    File(projectRoot, "app/src/debug/res/values/strings.xml").apply {
      parentFile?.mkdirs()
      writeText("<resources><string name=\"preview_label\">Debug label</string></resources>")
    }
    File(projectRoot, "app/build.gradle").writeText("plugins {}")

    val context = RuntimeEnvironment.getApplication()
    val view = TextView(context)

    assertTrue(
        ProjectTextResourcePreview.apply(
            view,
            "android:text",
            "@string/preview_label",
            context,
            layoutFile,
        )
    )
    assertEquals("Debug label", view.text.toString())

    assertTrue(
        ProjectTextResourcePreview.apply(
            view,
            "android:hint",
            "@string/preview_hint",
            context,
            layoutFile,
        )
    )
    assertEquals("Project hint", view.hint.toString())
  }
}
