/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package dev.mutwakil.androidide.utils

import dev.mutwakil.androidide.app.IDEApplication
import dev.mutwakil.androidide.templates.RecipeExecutor
import dev.mutwakil.androidide.templates.TemplateRecipeResult
import java.io.File
import java.io.InputStream
import java.util.LinkedHashSet

/**
 * [RecipeExecutor] implementation used for creating projects.
 *
 * @author Akash Yadav
 */
class TemplateRecipeExecutor : RecipeExecutor {

  private val application: IDEApplication
    get() = IDEApplication.instance

  private val createdPaths = LinkedHashSet<File>()
  private var projectRoot: File? = null
  private var finished = false

  override fun beginProjectCreation(projectDir: File) {
    checkActive()
    val root = projectDir.canonicalFile
    check(!root.exists()) { "Project directory already exists: '$root'" }
    val parent = root.parentFile
    require(parent != null && parent.isDirectory && parent.canRead() && parent.canWrite()) {
      "Project save location is not a readable, writable directory: '$parent'"
    }
    check(projectRoot == null) { "A project creation transaction is already active" }
    projectRoot = root
  }

  /**
   * Execute a complete recipe as one transaction. A missing result or any thrown failure rolls
   * back only paths created by this executor; a successful result commits the generated files.
   */
  fun <T : TemplateRecipeResult> executeRecipe(
      recipe: RecipeExecutor.() -> T?,
  ): T? {
    checkActive()
    return try {
      val result = recipe(this)
      if (result == null) rollback() else commit()
      result
    } catch (error: Throwable) {
      rollback()
      throw error
    }
  }

  override fun copy(source: File, dest: File) {
    checkActive()
    ensureParentDirectory(dest)
    registerNewFile(dest)
    source.copyTo(dest)
  }

  override fun save(source: String, dest: File) {
    checkActive()
    ensureParentDirectory(dest)
    registerNewFile(dest)
    dest.writeText(source, Charsets.UTF_8)
  }

  override fun openAsset(path: String): InputStream {
    checkActive()
    require(path.isNotBlank()) { "Template asset path must not be blank" }
    return try {
      application.assets.open(path)
    } catch (e: Exception) {
      throw IllegalStateException("Unable to open template asset '$path'", e)
    }
  }

  override fun copyAsset(path: String, dest: File) {
    checkActive()
    ensureParentDirectory(dest)
    registerNewFile(dest)
    openAsset(path).use { input ->
      dest.outputStream().buffered().use { output -> input.copyTo(output) }
    }
  }

  override fun copyAssetsRecursively(path: String, destDir: File) {
    checkActive()
    require(path.isNotBlank()) { "Template asset path must not be blank" }
    copyAssetTree(path.trim('/'), destDir)
  }

  /** Keep newly created files after a successful recipe. */
  fun commit() {
    if (finished) return
    finished = true
    createdPaths.clear()
    projectRoot = null
  }

  /**
   * Remove only paths created during this recipe. Existing files and directories are never deleted.
   * A directory that acquired unrelated content is retained because delete() is non-recursive.
   */
  fun rollback() {
    if (finished) return
    createdPaths.toList().asReversed().forEach { path -> runCatching { path.delete() } }
    createdPaths.clear()

    // Template builders also create source/resource directories directly. Remove only empty
    // directories beneath a destination proven absent at transaction start. Unknown files are
    // never recursively deleted; any directory containing them is retained.
    projectRoot?.takeIf { it.isDirectory }?.walkBottomUp()
        ?.filter { it.isDirectory && it.list()?.isEmpty() == true }
        ?.forEach { directory -> runCatching { directory.delete() } }

    projectRoot = null
    finished = true
  }

  private fun copyAssetTree(assetPath: String, destination: File) {
    val children = try {
      application.assets.list(assetPath).orEmpty()
    } catch (e: Exception) {
      throw IllegalStateException("Unable to list template asset '$assetPath'", e)
    }

    if (children.isNotEmpty()) {
      ensureDirectory(destination)
      children.forEach { child ->
        copyAssetTree("$assetPath/$child", File(destination, child))
      }
      return
    }

    val input = try {
      application.assets.open(assetPath)
    } catch (openError: Exception) {
      // AssetManager reports an empty list for both files and empty directories.
      val listing = runCatching { application.assets.list(assetPath) }.getOrNull()
      if (listing != null && listing.isEmpty()) {
        ensureDirectory(destination)
        return
      }
      throw IllegalStateException("Unable to read template asset '$assetPath'", openError)
    }

    ensureParentDirectory(destination)
    registerNewFile(destination)
    input.use { source ->
      destination.outputStream().buffered().use { output -> source.copyTo(output) }
    }
  }

  private fun ensureParentDirectory(file: File) {
    file.absoluteFile.parentFile?.let(::ensureDirectory)
  }

  private fun ensureDirectory(directory: File) {
    if (directory.isDirectory) return
    check(!directory.exists()) { "Cannot create directory because a file exists at '$directory'" }

    val missing = mutableListOf<File>()
    var current: File? = directory.absoluteFile
    while (current != null && !current.exists()) {
      missing += current
      current = current.parentFile
    }
    check(current == null || current.isDirectory) {
      "Cannot create directory under a non-directory path '$current'"
    }
    check(directory.mkdirs() || directory.isDirectory) {
      "Unable to create directory '$directory'"
    }
    missing.asReversed().forEach { createdPaths.add(it) }
  }

  private fun registerNewFile(file: File) {
    val path = file.canonicalFile
    if (path.exists()) {
      check(path.isFile && path in createdPaths) {
        "Refusing to overwrite an existing template destination: '$path'"
      }
    } else {
      createdPaths.add(path)
    }
  }

  private fun checkActive() {
    check(!finished) { "This template recipe executor has already been completed" }
  }
}