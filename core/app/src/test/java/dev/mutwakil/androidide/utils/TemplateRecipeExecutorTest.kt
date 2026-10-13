/*
 * This file is part of AndroidIDE.
 *
 * AndroidIDE is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package dev.mutwakil.androidide.utils

import dev.mutwakil.androidide.templates.TemplateRecipeResult
import java.io.File
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TemplateRecipeExecutorTest {

  @get:Rule
  val temporaryFolder = TemporaryFolder()

  @Test
  fun failedRecipeRemovesOnlyItsCreatedFilesAndEmptyDirectories() {
    val root = File(temporaryFolder.root, "NewProject")
    val executor = TemplateRecipeExecutor()

    assertThrows(IllegalStateException::class.java) {
      executor.executeRecipe {
        beginProjectCreation(root)
        // Mirrors template builders that create directories directly rather than through save().
        File(root, "app/src/main/java").mkdirs()
        save("partial source", File(root, "app/src/main/java/MainActivity.java"))
        throw IllegalStateException("simulated recipe failure")
      }
    }

    assertFalse(root.exists())
  }

  @Test
  fun successfulRecipeCommitsGeneratedFiles() {
    val root = File(temporaryFolder.root, "NewProject")
    val executor = TemplateRecipeExecutor()

    val result = executor.executeRecipe {
      beginProjectCreation(root)
      root.mkdirs()
      save("settings", File(root, "settings.gradle.kts"))
      object : TemplateRecipeResult {}
    }

    assertTrue(result != null)
    assertEquals("settings", File(root, "settings.gradle.kts").readText())
    assertTrue(root.isDirectory)
  }

  @Test
  fun refusesToStartInsideAnExistingProjectDirectory() {
    val root = File(temporaryFolder.root, "ExistingProject").apply { mkdirs() }
    val marker = File(root, "keep.txt").apply { writeText("keep") }
    val executor = TemplateRecipeExecutor()

    assertThrows(IllegalStateException::class.java) {
      executor.beginProjectCreation(root)
    }

    assertEquals("keep", marker.readText())
  }

  @Test
  fun refusesToOverwriteAnExistingFile() {
    val existing = File(temporaryFolder.root, "keep.txt").apply { writeText("original") }
    val executor = TemplateRecipeExecutor()

    assertThrows(IllegalStateException::class.java) {
      executor.save("replacement", existing)
    }

    assertEquals("original", existing.readText())
  }

  @Test
  fun failedRecipeCannotWriteOutsideItsProjectRoot() {
    val root = File(temporaryFolder.root, "NewProject")
    val outside = File(temporaryFolder.root, "keep.txt").apply { writeText("original") }
    val executor = TemplateRecipeExecutor()

    assertThrows(IllegalStateException::class.java) {
      executor.executeRecipe {
        beginProjectCreation(root)
        root.mkdirs()
        save("replacement", outside)
        object : TemplateRecipeResult {}
      }
    }

    assertEquals("original", outside.readText())
    assertFalse(root.exists())
  }

  @Test
  fun nullRecipeResultRollsBackPartialOutput() {
    val root = File(temporaryFolder.root, "NewProject")
    val executor = TemplateRecipeExecutor()

    val result = executor.executeRecipe<TemplateRecipeResult> {
      beginProjectCreation(root)
      root.mkdirs()
      save("partial", File(root, "partial.txt"))
      null
    }

    assertTrue(result == null)
    assertFalse(root.exists())
  }

  @Test
  fun cancellationBetweenWritesStopsRecipeAndRollsBackGeneratedOutput() {
    val root = File(temporaryFolder.root, "CancelledProject")
    val executor = TemplateRecipeExecutor()

    assertThrows(CancellationException::class.java) {
      executor.executeRecipe {
        beginProjectCreation(root)
        root.mkdirs()
        save("partial", File(root, "partial.txt"))
        executor.cancel()
        save("must not be written", File(root, "second.txt"))
        object : TemplateRecipeResult {}
      }
    }

    assertFalse(root.exists())
  }

  @Test
  fun cancellationImmediatelyBeforeCommitRollsBackGeneratedOutput() {
    val root = File(temporaryFolder.root, "CancelledBeforeCommit")
    val executor = TemplateRecipeExecutor()

    assertThrows(CancellationException::class.java) {
      executor.executeRecipe {
        beginProjectCreation(root)
        root.mkdirs()
        save("partial", File(root, "partial.txt"))
        executor.cancel()
        object : TemplateRecipeResult {}
      }
    }

    assertFalse(root.exists())
  }

  @Test
  fun rollbackRetainsUntrackedContentAddedDuringFailure() {
    val root = File(temporaryFolder.root, "NewProject")
    val executor = TemplateRecipeExecutor()

    executor.beginProjectCreation(root)
    root.mkdirs()
    val generated = File(root, "generated.txt")
    executor.save("owned", generated)
    val unrelated = File(root, "unrelated.txt").apply { writeText("keep me") }

    executor.rollback()

    assertFalse(generated.exists())
    assertTrue(unrelated.isFile)
    assertEquals("keep me", unrelated.readText())
    assertTrue(root.isDirectory)
  }
}
