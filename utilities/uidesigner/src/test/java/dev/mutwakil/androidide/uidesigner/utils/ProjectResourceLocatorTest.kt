/*
 * This file is part of AndroidIDE.
 *
 * AndroidIDE is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package dev.mutwakil.androidide.uidesigner.utils

import android.content.res.Configuration
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
class ProjectResourceLocatorTest {

  @get:Rule
  val temporaryFolder = TemporaryFolder()

  @Test
  fun resolvesValuesFromTheMatchingConfigurationDirectory() {
    val projectRoot = temporaryFolder.newFolder("values-project")
    File(projectRoot, "app/src/main/res/values/colors.xml").apply {
      parentFile?.mkdirs()
      writeText("<resources><color name=\"accent\">#112233</color></resources>")
    }
    File(projectRoot, "app/src/main/res/values-night/colors.xml").apply {
      parentFile?.mkdirs()
      writeText("<resources><color name=\"accent\">#AABBCC</color></resources>")
    }

    val baseContext = RuntimeEnvironment.getApplication()
    val dayConfiguration =
        Configuration(baseContext.resources.configuration).apply {
          uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
              Configuration.UI_MODE_NIGHT_NO
        }
    val nightConfiguration =
        Configuration(baseContext.resources.configuration).apply {
          uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
              Configuration.UI_MODE_NIGHT_YES
        }

    assertEquals(
        "#112233",
        ProjectResourceLocator.findValueResource(
            projectRoot,
            "color",
            "accent",
            baseContext.createConfigurationContext(dayConfiguration),
        ),
    )
    assertEquals(
        "#AABBCC",
        ProjectResourceLocator.findValueResource(
            projectRoot,
            "color",
            "accent",
            baseContext.createConfigurationContext(nightConfiguration),
        ),
    )
  }

  @Test
  fun discoversResourcesInNonAppModulesAndQualifiedDirectories() {
    val projectRoot = temporaryFolder.newFolder("project")
    File(projectRoot, "feature/profile/src/main/res/drawable").mkdirs()
    File(projectRoot, "feature/profile/src/main/res/drawable-night").mkdirs()
    File(projectRoot, "feature/profile/src/debug/res/drawable-land").mkdirs()

    val baseContext = RuntimeEnvironment.getApplication()
    val configuration =
        Configuration(baseContext.resources.configuration).apply {
          uiMode =
              (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                  Configuration.UI_MODE_NIGHT_YES
        }
    val nightContext = baseContext.createConfigurationContext(configuration)

    val directories =
        ProjectResourceLocator.findResourceDirectories(projectRoot, "drawable", nightContext)

    assertEquals("drawable-night", directories.first().name)
    assertTrue(directories.any { it.name == "drawable-land" })
    assertTrue(directories.any { it.name == "drawable" })
    assertTrue(directories.all { it.isDirectory })
  }
}
