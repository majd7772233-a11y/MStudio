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
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

/**
 * Finds references absent from the opened module's source sets and loaded compile-scope tables.
 * If project resources are not initialized yet, it stays silent instead of marking every reference
 * missing.
 */
internal class XmlResourceReferenceDiagnosticProvider {

  private val sourceResourceCache = ConcurrentHashMap<String, Set<String>>()

  fun clearCache() {
    sourceResourceCache.clear()
  }

  fun invalidate(file: Path, workspace: Workspace?) {
    val module = workspace?.findModuleForFile(file)?.let { it as? AndroidModule } ?: return
    val moduleRoot = module.delegate.projectDir
    // Editing a layout does not change the module's resource-name index. Only edits beneath an
    // Android source-set res directory invalidate it; this avoids a full module tree walk on each
    // keystroke while still refreshing diagnostics after resources are added, renamed or removed.
    if (isSourceResourcePath(file, moduleRoot)) {
      sourceResourceCache.remove(moduleRoot.absolutePath)
    }
  }

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
    val locallyDeclaredIds = declaredIds(source, comments)
    val moduleRoot = module.delegate.projectDir
    val sourceResources =
        sourceResourceCache.computeIfAbsent(moduleRoot.absolutePath) {
          collectSourceResources(moduleRoot)
        }

    for (match in RESOURCE_REFERENCE.findAll(source)) {
      if (comments.any { match.range.first >= it.first && match.range.first <= it.last }) continue
      val packageName = match.groups["package"]?.value
      val typeName = match.groups["type"]?.value ?: continue
      val resourceName = match.groups["name"]?.value ?: continue
      if (packageName == "android") continue
      val type = runCatching { AaptResourceType.valueOf(typeName.uppercase()) }.getOrNull() ?: continue
      if (typeName == "id" && resourceName in locallyDeclaredIds) continue

      val isModulePackage = packageName == null || packageName == module.namespace
      if ((isModulePackage && "$typeName/$resourceName" in sourceResources) ||
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

  /** Scans the module's source resources once per XML analysis, avoiding a tree walk per ref. */
  private fun collectSourceResources(moduleRoot: File): Set<String> {
    if (!moduleRoot.isDirectory) return emptySet()
    val resources = mutableSetOf<String>()
    val resourceDirectories =
        moduleRoot
            .walkTopDown()
            .maxDepth(MAX_SCAN_DEPTH)
            .onEnter { it == moduleRoot || it.name !in IGNORED_DIRS }
            .filter { directory ->
              directory.isDirectory &&
                  directory.parentFile?.name == "res" &&
                  (directory.name == "values" || directory.name.startsWith("values-") ||
                      directory.name.matches(Regex("[A-Za-z][A-Za-z0-9_]*(?:-[A-Za-z0-9_+.-]+)?")))
            }
    val parserFactory = runCatching { XmlPullParserFactory.newInstance() }.getOrNull()

    for (directory in resourceDirectories) {
      val directoryType = directory.name.substringBefore('-')
      val files = directory.listFiles()?.filter { it.isFile } ?: continue
      if (directoryType == "values") {
        if (parserFactory == null) continue
        for (file in files.filter { it.extension == "xml" }) {
          runCatching {
            file.inputStream().buffered().use { input ->
              val parser = parserFactory.newPullParser()
              parser.setInput(input, "UTF-8")
              var event = parser.eventType
              while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                  val type =
                      if (parser.name == "item") parser.getAttributeValue(null, "type")
                      else parser.name
                  val name = parser.getAttributeValue(null, "name")
                  if (!type.isNullOrBlank() && !name.isNullOrBlank()) {
                    resources.add("$type/$name")
                  }
                }
                event = parser.next()
              }
            }
          }
        }
      } else {
        for (file in files) {
          val filename = when {
            file.name.endsWith(".9.png") -> file.name.removeSuffix(".9.png")
            else -> file.nameWithoutExtension
          }
          if (file.extension in RESOURCE_FILE_EXTENSIONS) {
            resources.add("$directoryType/$filename")
            // IDs are usually declared inside layout XML rather than values XML. Index those
            // declarations too, so a valid @id reference in another layout is not flagged.
            if (file.extension == "xml") {
              runCatching {
                val xml = file.readText(Charsets.UTF_8)
                val comments = xmlCommentRanges(xml)
                resources.addAll(declaredIds(xml, comments).map { "id/$it" })
              }
            }
          }
        }
      }
    }
    return resources
  }

  private fun declaredIds(source: String, comments: List<IntRange>): Set<String> =
      DECLARED_ID_REFERENCE.findAll(source)
          .filter { match ->
            comments.none { match.range.first >= it.first && match.range.first <= it.last }
          }
          .map { it.groupValues[1] }
          .toSet()

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
    internal fun isSourceResourcePath(file: Path, moduleRoot: File): Boolean {
      val root = moduleRoot.toPath().toAbsolutePath().normalize()
      val candidate = file.toAbsolutePath().normalize()
      if (!candidate.startsWith(root)) return false
      val relative = root.relativize(candidate)
      val segments = (0 until relative.nameCount).map { relative.getName(it).toString() }
      return segments.indices.any { index ->
        segments[index] == "res" && index >= 2 && segments[index - 2] == "src"
      }
    }

    private val LOG = LoggerFactory.getLogger(XmlResourceReferenceDiagnosticProvider::class.java)
    private val RESOURCE_REFERENCE =
        Regex("@(?:(?<package>[A-Za-z0-9_.]+):)?(?<type>[A-Za-z][A-Za-z0-9_]*)/(?<name>[A-Za-z0-9_.]+)")
    private val DECLARED_ID_REFERENCE = Regex("@\\+id/([A-Za-z0-9_.]+)")
    private val IGNORED_DIRS =
        setOf(".git", ".gradle", ".idea", ".kotlin", "build", "node_modules", ".cxx", "out", "target")
    private val RESOURCE_FILE_EXTENSIONS =
        setOf("xml", "png", "webp", "jpg", "jpeg", "gif", "bmp", "avif")
    private const val MAX_SCAN_DEPTH = 8
  }
}
