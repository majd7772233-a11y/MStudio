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
import android.view.View
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import com.google.android.material.textfield.TextInputLayout
import java.io.File
import org.slf4j.LoggerFactory

/**
 * Applies simple project string resources to the visual preview without resolving the same name
 * against MStudio's own packaged resources first. This intentionally does not emulate AAPT
 * formatting, plurals, styled spans, or theme overlays.
 */
internal object ProjectTextResourcePreview {

  private val log = LoggerFactory.getLogger(ProjectTextResourcePreview::class.java)

  fun apply(
      view: View,
      attributeName: String,
      attributeValue: String,
      context: Context,
      layoutFile: File?,
  ): Boolean {
    val name = attributeName.substringAfter(':').lowercase()
    if (name !in SUPPORTED_ATTRIBUTES || !attributeValue.startsWith("@string/")) return false

    val resourceName = attributeValue.substringAfter("@string/").substringBefore('/')
    if (resourceName.isBlank()) return true

    val projectRoot = ProjectResourceLocator.findProjectRoot(layoutFile)
    val resolved =
        projectRoot?.let {
          ProjectResourceLocator.resolveValueResource(
              it,
              "string",
              resourceName,
              context,
              ProjectResourceLocator.findModuleRoot(layoutFile),
              ProjectResourceLocator.findSourceSet(layoutFile),
          )
        }

    if (resolved == null) {
      log.warn(
          "Cannot resolve project string '{}' for attribute '{}' in layout {}",
          resourceName,
          attributeName,
          layoutFile?.absolutePath ?: "<unknown>",
      )
      // Do not accidentally show a same-named resource bundled with the IDE.
      return projectRoot != null
    }

    return try {
      when (name) {
        "text" -> (view as? TextView)?.text = resolved
        "hint" -> {
          when (view) {
            is TextInputLayout -> view.hint = resolved
            is TextView -> view.hint = resolved
            else -> return false
          }
        }
        "title" -> {
          when (view) {
            is Toolbar -> view.title = resolved
            is TextView -> view.text = resolved
            else -> return false
          }
        }
        "contentdescription" -> view.contentDescription = resolved
      }
      true
    } catch (error: Exception) {
      log.warn("Could not apply project string '{}' to '{}'", resourceName, attributeName, error)
      false
    }
  }

  private val SUPPORTED_ATTRIBUTES =
      setOf("text", "hint", "title", "contentdescription")
}
