/*
 * This file is part of AndroidIDE.
 *
 * AndroidIDE is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package dev.mutwakil.androidide.uidesigner.utils

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import java.io.File
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

/**
 * Finds project resources across Android modules, source sets and qualifier folders.
 *
 * This is a preview-only filesystem resolver; Gradle/aapt remains authoritative for actual builds.
 */
internal object ProjectResourceLocator {

  private val ignoredDirectories =
      setOf(
          ".git",
          ".gradle",
          ".idea",
          ".kotlin",
          "build",
          "node_modules",
          ".externalNativeBuild",
          ".cxx",
          "out",
          "target",
      )

  fun findResourceDirectories(
      projectRoot: File,
      resourceType: String,
      context: Context,
      preferredModuleRoot: File? = null,
      preferredSourceSet: String? = null,
  ): List<File> {
    if (!projectRoot.isDirectory) return emptyList()

    return projectRoot
        .walkTopDown()
        .maxDepth(MAX_DEPTH)
        .onEnter { directory -> directory == projectRoot || directory.name !in ignoredDirectories }
        .filter { directory ->
          directory.isDirectory &&
              directory.parentFile?.name == "res" &&
              (directory.name == resourceType || directory.name.startsWith("$resourceType-"))
        }
        .toList()
        .distinctBy { it.absolutePath }
        .sortedWith(
            compareByDescending<File> {
              preferredModuleRoot != null &&
                  (it.absolutePath == preferredModuleRoot.absolutePath ||
                      it.absolutePath.startsWith(
                          preferredModuleRoot.absolutePath + File.separator
                      ))
            }
                .thenByDescending { sourceSetScore(it, preferredSourceSet) }
                .thenByDescending { qualifierScore(it.name, resourceType, context) }
                .thenBy { it.absolutePath }
        )
  }

  fun findProjectRoot(layoutFile: File?): File? {
    var current = layoutFile?.absoluteFile?.parentFile ?: return null
    var outermostGradleRoot: File? = null

    repeat(MAX_PROJECT_ROOT_DEPTH) {
      val hasSettings =
          File(current, "settings.gradle").isFile || File(current, "settings.gradle.kts").isFile
      val hasBuild =
          File(current, "build.gradle").isFile || File(current, "build.gradle.kts").isFile

      // A settings file defines the build boundary and takes precedence over a module build file.
      if (hasSettings) return current
      if (hasBuild) outermostGradleRoot = current

      current = current.parentFile ?: return outermostGradleRoot
    }
    return outermostGradleRoot
  }

  /** Finds the source set (for example, main, debug or a flavor) containing the layout. */
  fun findSourceSet(layoutFile: File?, moduleRoot: File? = findModuleRoot(layoutFile)): String? {
    if (layoutFile == null || moduleRoot == null) return null
    val modulePath = moduleRoot.absoluteFile.toPath().normalize()
    val filePath = layoutFile.absoluteFile.toPath().normalize()
    if (!filePath.startsWith(modulePath)) return null
    val relative = modulePath.relativize(filePath).toString().replace(File.separatorChar, '/')
    val segments = relative.split('/')
    val srcIndex = segments.indexOf("src")
    return segments.getOrNull(srcIndex + 1)?.takeIf { srcIndex >= 0 }
  }

  private fun sourceSetScore(directory: File, preferredSourceSet: String?): Int {
    val path = directory.absolutePath.replace(File.separatorChar, '/')
    return when {
      preferredSourceSet == null -> if (path.contains("/src/main/res/")) 1 else 0
      path.contains("/src/$preferredSourceSet/res/") -> 3
      path.contains("/src/main/res/") -> 2
      path.contains("/src/") -> 1
      else -> 0
    }
  }

  /** Returns the nearest Android module root containing the opened layout. */
  fun findModuleRoot(layoutFile: File?): File? {
    var current = layoutFile?.absoluteFile?.parentFile ?: return null
    repeat(MAX_PROJECT_ROOT_DEPTH) {
      if (File(current, "build.gradle").isFile || File(current, "build.gradle.kts").isFile) {
        return current
      }
      current = current.parentFile ?: return null
    }
    return null
  }

  /** Resolves simple project value resources and aliases without pretending to be AAPT. */
  fun resolveValueResource(
      projectRoot: File,
      resourceType: String,
      resourceName: String,
      context: Context,
      preferredModuleRoot: File? = null,
  ): String? =
      resolveValueResource(
          projectRoot,
          resourceType,
          resourceName,
          context,
          preferredModuleRoot,
          mutableSetOf(),
      )

  private fun resolveValueResource(
      projectRoot: File,
      resourceType: String,
      resourceName: String,
      context: Context,
      preferredModuleRoot: File?,
      visited: MutableSet<String>,
  ): String? {
    val key = "$resourceType/$resourceName"
    if (visited.size >= MAX_VALUE_ALIAS_DEPTH || !visited.add(key)) return null
    val value =
        findValueResource(projectRoot, resourceType, resourceName, context, preferredModuleRoot)
            ?: return null
    val alias = Regex("^@($SUPPORTED_VALUE_TYPES)/([A-Za-z_][A-Za-z0-9_.]*)$").matchEntire(value)
    if (alias != null) {
      val targetType = alias.groupValues[1]
      val targetName = alias.groupValues[2]
      return resolveValueResource(
          projectRoot,
          targetType,
          targetName,
          context,
          preferredModuleRoot,
          visited,
      )
    }
    return value.takeIf { !it.startsWith("?") && !it.startsWith("@") }
  }

  fun findValueResource(
      projectRoot: File,
      resourceType: String,
      resourceName: String,
      context: Context,
      preferredModuleRoot: File? = null,
  ): String? {
    val parserFactory = XmlPullParserFactory.newInstance()
    val directories =
        findResourceDirectories(projectRoot, "values", context, preferredModuleRoot)
    for (directory in directories) {
      val files = directory.listFiles()?.filter { it.isFile && it.extension == "xml" } ?: continue
      for (file in files.sortedBy { it.name }) {
        try {
          file.inputStream().buffered().use { input ->
            val parser = parserFactory.newPullParser()
            parser.setInput(input, "UTF-8")
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
              if (event == XmlPullParser.START_TAG) {
                val itemType = parser.getAttributeValue(null, "type")
                val matchesType = parser.name == resourceType ||
                    (parser.name == "item" && itemType == resourceType)
                if (matchesType && parser.getAttributeValue(null, "name") == resourceName) {
                  val value = runCatching { parser.nextText().trim() }.getOrNull()
                  if (!value.isNullOrBlank()) return value
                }
              }
              event = parser.next()
            }
          }
        } catch (_: Exception) {
          // One malformed/unrelated values file must not hide other matching resource files.
        }
      }
    }
    return null
  }

  private fun qualifierScore(folderName: String, resourceType: String, context: Context): Int {
    if (folderName == resourceType) return 0

    val qualifiers = folderName.removePrefix("$resourceType-").split('-')
    val configuration = context.resources.configuration
    val density = context.resources.displayMetrics.densityDpi
    val isNight =
        configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    var score = 0

    for (qualifier in qualifiers) {
      when (qualifier) {
        "night" -> score += if (isNight) 100 else -100
        "notnight" -> score += if (!isNight) 100 else -100
        "land" ->
            score +=
                if (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 40 else -40
        "port" ->
            score +=
                if (configuration.orientation == Configuration.ORIENTATION_PORTRAIT) 40 else -40
        "square" ->
            score +=
                if (configuration.orientation == Configuration.ORIENTATION_SQUARE) 10 else -10
        "ldpi", "mdpi", "tvdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi" -> {
          val targetDensity =
              when (qualifier) {
                "ldpi" -> 120
                "mdpi" -> 160
                "tvdpi" -> 213
                "hdpi" -> 240
                "xhdpi" -> 320
                "xxhdpi" -> 480
                else -> 640
              }
          score += 30 - kotlin.math.abs(density - targetDensity) / 20
        }
        "anydpi" -> score += 25
        "nodpi" -> score += 20
        else -> {
          when {
            qualifier.startsWith("v") && qualifier.drop(1).toIntOrNull() != null -> {
              val version = qualifier.drop(1).toInt()
              score += if (Build.VERSION.SDK_INT >= version) 10 + version else -100
            }
            qualifier.startsWith("sw") && qualifier.endsWith("dp") -> {
              val width = qualifier.removePrefix("sw").removeSuffix("dp").toIntOrNull()
              if (width != null) {
                score += if (configuration.smallestScreenWidthDp >= width) 10 else -100
              }
            }
            qualifier.startsWith("w") && qualifier.endsWith("dp") -> {
              val width = qualifier.removePrefix("w").removeSuffix("dp").toIntOrNull()
              if (width != null) {
                score += if (configuration.screenWidthDp >= width) 10 else -100
              }
            }
            qualifier.startsWith("h") && qualifier.endsWith("dp") -> {
              val height = qualifier.removePrefix("h").removeSuffix("dp").toIntOrNull()
              if (height != null) {
                score += if (configuration.screenHeightDp >= height) 10 else -100
              }
            }
            qualifier.startsWith("b+") -> {
              val localeParts = qualifier.removePrefix("b+").split('+')
              val language =
                  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    configuration.locales[0]?.language.orEmpty()
                  } else {
                    @Suppress("DEPRECATION") configuration.locale?.language.orEmpty()
                  }
              val region =
                  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    configuration.locales[0]?.country.orEmpty()
                  } else {
                    @Suppress("DEPRECATION") configuration.locale?.country.orEmpty()
                  }
              val languageMatches =
                  localeParts.firstOrNull().equals(language, ignoreCase = true)
              val regionMatches =
                  localeParts.getOrNull(1)?.equals(region, ignoreCase = true) ?: true
              score += if (languageMatches && regionMatches) 80 else -100
            }
            qualifier.length in 2..3 && qualifier.all { it.isLetter() } -> {
              val language =
                  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    configuration.locales[0]?.language.orEmpty()
                  } else {
                    @Suppress("DEPRECATION") configuration.locale?.language.orEmpty()
                  }
              score += if (language.equals(qualifier, ignoreCase = true)) 80 else -100
            }
          }
        }
      }
    }
    return score
  }

  private const val MAX_DEPTH = 8
  private const val MAX_PROJECT_ROOT_DEPTH = 12
  private const val MAX_VALUE_ALIAS_DEPTH = 12
  private const val SUPPORTED_VALUE_TYPES = "string|color|dimen|integer|bool|fraction"
}
