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
            compareByDescending<File> { qualifierScore(it.name, resourceType, context) }
                .thenByDescending { if (it.path.contains("/src/main/res/")) 1 else 0 }
                .thenBy { it.absolutePath }
        )
  }

  fun findValueResource(
      projectRoot: File,
      resourceType: String,
      resourceName: String,
      context: Context,
  ): String? {
    val parserFactory = XmlPullParserFactory.newInstance()
    val directories = findResourceDirectories(projectRoot, "values", context)
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
}
