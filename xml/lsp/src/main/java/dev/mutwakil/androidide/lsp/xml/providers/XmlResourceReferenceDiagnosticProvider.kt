/*
 * This file is part of AndroidIDE.
 *
 * AndroidIDE is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package dev.mutwakil.androidide.lsp.xml.providers

import com.android.aaptcompiler.AaptResourceType
import dev.mutwakil.androidide.lsp.models.DiagnosticItem
import dev.mutwakil.androidide.lsp.models.DiagnosticResult
import dev.mutwakil.androidide.lsp.models.DiagnosticSeverity.WARNING
import dev.mutwakil.androidide.models.Position
import dev.mutwakil.androidide.models.Range
import dev.mutwakil.androidide.projects.api.AndroidModule
import dev.mutwakil.androidide.projects.api.Workspace
import dev.mutwakil.androidide.xml.res.IResourceTable
import java.io.File
import java.nio.file.Path
import org.slf4j.LoggerFactory

/**
 * Finds references absent from the opened module's source sets and loaded compile-scope tables.
 * If project resources are not initialized yet, it stays silent instead of marking every reference
 * missing.
 */
internal class XmlResourceReferenceDiagnosticProvider {

  fun analyze(file: Path, workspace: Workspace?): DiagnosticResult {
    val module = workspace?.findModuleForFile(file)?.let { it as? AndroidModule }
        ?: return DiagnosticResult(file, emptyList())

    val tables: Set<IResourceTable> =
        runCatching {
          (module.getSourceResourceTables() + module.getDependencyResourceTables())
              .map { it as IResourceTable }
              .toSet()
        }.getOrElse {
          LOG.debug("Resource tables are not ready for XML diagnostics: {}", file, it)
          emptySet()
        }
    if (tables.isEmpty()) return DiagnosticResult(file, emptyList())

    val source =
        runCatching { file.toFile().readText(Charsets.UTF_8) }.getOrElse {
          return DiagnosticResult.NO_UPDATE
        }
    val diagnostics = mutableListOf<DiagnosticItem>()
    val comments = xmlCommentRanges(source)

    for (match in RESOURCE_REFERENCE.findAll(source)) {
      if (comments.any { match.range.first >= it.first && match.range.first <= it.last }) continue
      val packageName = match.groups["package"]?.value
      val typeName = match.groups["type"]?.value ?: continue
      val resourceName = match.groups["name"]?.value ?: continue
      if (packageName == "android" || typeName == "id" || typeName == "attr") continue
      val type = runCatching { AaptResourceType.valueOf(typeName.uppercase()) }.getOrNull() ?: continue

      if (hasSourceResource(module.delegate.projectDir, typeName, resourceName) ||
          existsInTables(tables, type, resourceName, packageName)
      ) {
        continue
      }

      val start = match.range.first
      val end = match.range.last + 1
      diagnostics.add(
          DiagnosticItem(
              message =
                  "Resource reference @${typeName}/$resourceName was not found in the opened module's source sets or loaded compile-scope resources. Check its spelling, source set, dependency sync, and selected variant.",
              code = "xml-missing-resource",
              range = Range(positionAt(source, start), positionAt(source, end)).apply {
                this.start.index = start
                this.end.index = end
              },
              source = "xml-resources",
              severity = WARNING,
          )
      )
    }

    return DiagnosticResult(file, diagnostics.sortedBy { it.range })
  }

  private fun existsInTables(
      tables: Set<IResourceTable>,
      type: AaptResourceType,
      name: String,
      packageName: String?,
  ): Boolean =
      tables.any { table ->
        table.packages.any { resourcePackage ->
          (packageName == null || resourcePackage.name == packageName) &&
              resourcePackage.findGroup(type)?.findEntry(name) != null
        }
      }

  private fun hasSourceResource(
      moduleRoot: File,
      resourceType: String,
      resourceName: String,
  ): Boolean {
    if (!moduleRoot.isDirectory) return false
    val resourceDirectories =
        moduleRoot
            .walkTopDown()
            .maxDepth(MAX_SCAN_DEPTH)
            .onEnter { it == moduleRoot || it.name !in IGNORED_DIRS }
            .filter {
              it.isDirectory &&
                  it.parentFile?.name == "res" &&
                  (it.name == resourceType || it.name.startsWith("$resourceType-"))
            }
    val extensions = listOf("xml", "png", "webp", "jpg", "jpeg", "gif", "9.png")
    if (resourceDirectories.any { directory ->
      extensions.any { extension -> File(directory, "$resourceName.$extension").isFile }
    }) {
      return true
    }

    if (resourceType !in VALUE_RESOURCE_TYPES) return false
    val valuesDirectories =
        moduleRoot
            .walkTopDown()
            .maxDepth(MAX_SCAN_DEPTH)
            .onEnter { it == moduleRoot || it.name !in IGNORED_DIRS }
            .filter {
              it.isDirectory &&
                  it.parentFile?.name == "res" &&
                  (it.name == "values" || it.name.startsWith("values-"))
            }
    val pattern =
        Regex(
            """<(?:$resourceType|item\b[^>]*\btype\s*=\s*["']$resourceType["'])\b[^>]*\bname\s*=\s*["']$resourceName["']""",
            RegexOption.IGNORE_CASE,
        )
    return valuesDirectories.any { directory ->
      directory.listFiles()?.any { candidate ->
        candidate.isFile && candidate.extension == "xml" &&
            runCatching { pattern.containsMatchIn(candidate.readText()) }.getOrDefault(false)
      } == true
    }
  }

  private fun positionAt(source: String, offset: Int): Position {
    val safeOffset = offset.coerceIn(0, source.length)
    var line = 0
    var lastLineStart = 0
    for (index in 0 until safeOffset) {
      if (source[index] == '\n') {
        line++
        lastLineStart = index + 1
      }
    }
    return Position(line, safeOffset - lastLineStart)
  }

  private fun xmlCommentRanges(source: String): List<IntRange> {
    val ranges = mutableListOf<IntRange>()
    var searchFrom = 0
    while (true) {
      val start = source.indexOf("<!--", searchFrom)
      if (start < 0) break
      val end = source.indexOf("-->", start + 4)
      if (end < 0) {
        ranges.add(start until source.length)
        break
      }
      ranges.add(start until (end + 3))
      searchFrom = end + 3
    }
    return ranges
  }

  companion object {
    private val LOG = LoggerFactory.getLogger(XmlResourceReferenceDiagnosticProvider::class.java)
    private val RESOURCE_REFERENCE =
        Regex("@(?:(?<package>[A-Za-z0-9_.]+):)?(?<type>[A-Za-z][A-Za-z0-9_]*)/(?<name>[A-Za-z0-9_.]+)")
    private val IGNORED_DIRS =
        setOf(".git", ".gradle", ".idea", ".kotlin", "build", "node_modules", ".cxx", "out", "target")
    private val VALUE_RESOURCE_TYPES =
        setOf("string", "color", "dimen", "style", "integer", "bool", "fraction", "array", "plurals")
    private const val MAX_SCAN_DEPTH = 8
  }
}
