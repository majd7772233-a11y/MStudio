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
import android.view.ViewGroup
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
class ProjectDimensionPreviewTest {

  @get:Rule
  val temporaryFolder = TemporaryFolder()

  @Test
  fun resolvesProjectTextSizeAndPaddingDimensions() {
    val projectRoot = temporaryFolder.newFolder("project-dimensions")
    File(projectRoot, "settings.gradle").writeText("")
    val layoutFile = File(projectRoot, "app/src/main/res/layout/screen.xml").apply {
      parentFile?.mkdirs()
      writeText("<TextView />")
    }
    File(projectRoot, "app/build.gradle").writeText("plugins {}")
    File(projectRoot, "app/src/main/res/values/dimens.xml").apply {
      parentFile?.mkdirs()
      writeText(
          """
          <resources>
              <dimen name="preview_text_size">20sp</dimen>
              <dimen name="preview_padding">8dp</dimen>
          </resources>
          """.trimIndent(),
      )
    }

    val context = RuntimeEnvironment.getApplication()
    val view = TextView(context).apply {
      layoutParams = ViewGroup.LayoutParams(100, 100)
    }

    assertTrue(
        ProjectDimensionPreview.apply(
            view,
            "android:textSize",
            "@dimen/preview_text_size",
            context,
            layoutFile,
        )
    )
    assertEquals(
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            20f,
            context.resources.displayMetrics,
        ),
        view.textSize,
        0.01f,
    )

    assertTrue(
        ProjectDimensionPreview.apply(
            view,
            "android:padding",
            "@dimen/preview_padding",
            context,
            layoutFile,
        )
    )
    val expectedPadding =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            8f,
            context.resources.displayMetrics,
        ).toInt()
    assertEquals(expectedPadding, view.paddingLeft)
    assertEquals(expectedPadding, view.paddingTop)
    assertEquals(expectedPadding, view.paddingRight)
    assertEquals(expectedPadding, view.paddingBottom)
  }
}
