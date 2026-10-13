/*
 * This file is part of AndroidIDE.
 *
 * AndroidIDE is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package dev.mutwakil.androidide.uidesigner.utils

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TemporaryFolder
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class BackgroundPreviewRendererTest {

  @get:Rule
  val temporaryFolder = TemporaryFolder()

  @Test
  fun shapeDrawableResolvesProjectColorReferences() {
    val projectRoot = temporaryFolder.newFolder("shape-project")
    File(projectRoot, "settings.gradle").writeText("")
    File(projectRoot, "app/src/main/res/layout/screen.xml").apply {
      parentFile?.mkdirs()
      writeText("<FrameLayout />")
    }
    File(projectRoot, "app/src/main/res/values/colors.xml").apply {
      parentFile?.mkdirs()
      writeText("<resources><color name=\"shape_accent\">#345678</color></resources>")
    }
    File(projectRoot, "app/src/main/res/drawable/shape_preview.xml").apply {
      parentFile?.mkdirs()
      writeText(
          """
          <shape xmlns:android="http://schemas.android.com/apk/res/android">
              <solid android:color="@color/shape_accent" />
              <corners android:radius="8dp" />
          </shape>
          """.trimIndent(),
      )
    }

    val context = RuntimeEnvironment.getApplication()
    val preview = View(context)
    val layoutFile = File(projectRoot, "app/src/main/res/layout/screen.xml")

    assertTrue(
        BackgroundPreviewRenderer().applyBackgroundPreview(
            preview,
            "@drawable/shape_preview",
            context,
            layoutFile,
        )
    )
    assertEquals(Color.parseColor("#345678"), (preview.background as ColorDrawable).color)
  }

  @Test
  fun unsupportedXmlDrawableIsNotReportedAsSuccessfulPlaceholder() {
    val projectRoot = temporaryFolder.newFolder("unsupported-drawable-project")
    File(projectRoot, "settings.gradle").writeText("")
    val layoutFile = File(projectRoot, "app/src/main/res/layout/screen.xml").apply {
      parentFile?.mkdirs()
      writeText("<FrameLayout />")
    }
    File(projectRoot, "app/src/main/res/drawable/unsupported_preview.xml").apply {
      parentFile?.mkdirs()
      writeText(
          """
          <selector xmlns:android="http://schemas.android.com/apk/res/android">
              <item android:state_pressed="true" android:drawable="@android:drawable/ic_menu_add" />
          </selector>
          """.trimIndent(),
      )
    }

    val preview = View(RuntimeEnvironment.getApplication())

    assertFalse(
        BackgroundPreviewRenderer().applyBackgroundPreview(
            preview,
            "@drawable/unsupported_preview",
            RuntimeEnvironment.getApplication(),
            layoutFile,
        )
    )
  }

  @Test
  fun projectColorResourceIsResolvedFromTheOpenedProject() {
    val projectRoot = temporaryFolder.newFolder("project")
    File(projectRoot, "settings.gradle").writeText("")
    File(projectRoot, "app/src/main/res/layout/screen.xml").apply {
      parentFile?.mkdirs()
      writeText("<FrameLayout />")
    }
    File(projectRoot, "app/src/main/res/values/colors.xml").apply {
      parentFile?.mkdirs()
      writeText("<resources><color name=\"designer_preview_accent\">#123456</color></resources>")
    }

    val context = RuntimeEnvironment.getApplication()
    val preview = View(context)
    val layoutFile = File(projectRoot, "app/src/main/res/layout/screen.xml")

    assertTrue(
        BackgroundPreviewRenderer().applyBackgroundPreview(
            preview,
            "@color/designer_preview_accent",
            context,
            layoutFile,
        )
    )
    assertEquals(Color.parseColor("#123456"), (preview.background as ColorDrawable).color)
  }
}
