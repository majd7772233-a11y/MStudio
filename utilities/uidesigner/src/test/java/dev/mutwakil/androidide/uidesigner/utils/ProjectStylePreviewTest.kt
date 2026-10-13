/*
 * This file is part of AndroidIDE.
 *
 * AndroidIDE is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package dev.mutwakil.androidide.uidesigner.utils

import android.util.TypedValue
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
class ProjectStylePreviewTest {

  @get:Rule
  val temporaryFolder = TemporaryFolder()

  @Test
  fun appliesProjectStyleItemsAndExplicitParentInheritance() {
    val projectRoot = temporaryFolder.newFolder("project-styles")
    File(projectRoot, "settings.gradle").writeText("")
    File(projectRoot, "app/build.gradle").apply {
      parentFile?.mkdirs()
      writeText("plugins {}")
    }
    val layoutFile = File(projectRoot, "app/src/main/res/layout/screen.xml").apply {
      parentFile?.mkdirs()
      writeText("<TextView />")
    }
    File(projectRoot, "app/src/main/res/values/styles.xml").apply {
      parentFile?.mkdirs()
      writeText(
          """
          <resources>
              <style name="BasePreviewStyle">
                  <item name="android:text">Base label</item>
                  <item name="android:textSize">18sp</item>
              </style>
              <style name="ChildPreviewStyle" parent="@style/BasePreviewStyle">
                  <item name="android:text">Child label</item>
              </style>
          </resources>
          """.trimIndent(),
      )
    }

    val context = RuntimeEnvironment.getApplication()
    val view = TextView(context)

    assertTrue(
        ProjectStylePreview.apply(
            view,
            "@style/ChildPreviewStyle",
            context,
            layoutFile,
        )
    )
    assertEquals("Child label", view.text.toString())
    assertEquals(
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            18f,
            context.resources.displayMetrics,
        ),
        view.textSize,
        0.01f,
    )
  }
}
