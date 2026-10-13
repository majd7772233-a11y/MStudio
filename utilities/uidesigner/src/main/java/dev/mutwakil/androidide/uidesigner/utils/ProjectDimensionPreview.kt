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
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.io.File
import org.slf4j.LoggerFactory

/** Applies the common dimension-backed view properties from project @dimen resources. */
internal object ProjectDimensionPreview {

  private val log = LoggerFactory.getLogger(ProjectDimensionPreview::class.java)

  fun apply(
      view: View,
      attributeName: String,
      attributeValue: String,
      context: Context,
      layoutFile: File?,
  ): Boolean {
    if (!attributeValue.startsWith("@dimen/")) return false
    val name = attributeName.substringAfter(':').lowercase()
    if (name !in SUPPORTED_ATTRIBUTES) return false

    val resourceName = attributeValue.substringAfter("@dimen/").substringBefore('/')
    if (resourceName.isBlank()) return true

    val projectRoot = ProjectResourceLocator.findProjectRoot(layoutFile)
    val resolved =
        projectRoot?.let {
          ProjectResourceLocator.resolveValueResource(
              it,
              "dimen",
              resourceName,
              context,
              ProjectResourceLocator.findModuleRoot(layoutFile),
              ProjectResourceLocator.findSourceSet(layoutFile),
          )
        }

    if (resolved == null) {
      if (projectRoot != null) {
        log.warn("Cannot resolve project dimension '{}' in {}", resourceName, layoutFile?.path)
        return true
      }
      return false
    }

    val pixels = parsePixels(resolved, context) ?: run {
      log.warn("Unsupported project dimension '{}' value '{}'", resourceName, resolved)
      return true
    }

    return try {
      when (name) {
        "textsize" -> {
          val textView = view as? TextView ?: return false
          textView.setTextSize(TypedValue.COMPLEX_UNIT_PX, pixels)
        }
        "layout_width", "layout_height" -> {
          val params = view.layoutParams ?: return false
          val dimension = pixels.toInt()
          if (name == "layout_width") params.width = dimension else params.height = dimension
          view.layoutParams = params
        }
        "minwidth" -> view.minimumWidth = pixels.toInt()
        "minheight" -> view.minimumHeight = pixels.toInt()
        "elevation" -> view.elevation = pixels
        "padding" -> view.setPadding(pixels.toInt(), pixels.toInt(), pixels.toInt(), pixels.toInt())
        "paddingleft" -> view.setPadding(pixels.toInt(), view.paddingTop, view.paddingRight, view.paddingBottom)
        "paddingright" -> view.setPadding(view.paddingLeft, view.paddingTop, pixels.toInt(), view.paddingBottom)
        "paddingtop" -> view.setPadding(view.paddingLeft, pixels.toInt(), view.paddingRight, view.paddingBottom)
        "paddingbottom" -> view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, pixels.toInt())
        "paddingstart" ->
            view.setPaddingRelative(pixels.toInt(), view.paddingTop, view.paddingEnd, view.paddingBottom)
        "paddingend" ->
            view.setPaddingRelative(view.paddingStart, view.paddingTop, pixels.toInt(), view.paddingBottom)
        "layout_margin", "layout_margintop", "layout_marginbottom", "layout_marginstart",
        "layout_marginend", "layout_marginleft", "layout_marginright" -> {
          val params = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return false
          when (name) {
            "layout_margin" -> params.setMargins(pixels.toInt(), pixels.toInt(), pixels.toInt(), pixels.toInt())
            "layout_margintop" -> params.topMargin = pixels.toInt()
            "layout_marginbottom" -> params.bottomMargin = pixels.toInt()
            "layout_marginstart" -> params.marginStart = pixels.toInt()
            "layout_marginend" -> params.marginEnd = pixels.toInt()
            "layout_marginleft" -> params.leftMargin = pixels.toInt()
            "layout_marginright" -> params.rightMargin = pixels.toInt()
          }
          view.layoutParams = params
        }
      }
      true
    } catch (error: Exception) {
      log.warn("Could not apply project dimension '{}' to '{}'", resourceName, attributeName, error)
      false
    }
  }

  private fun parsePixels(value: String, context: Context): Float? {
    val match =
        Regex("^\\s*(-?(?:\\d+(?:\\.\\d*)?|\\.\\d+))\\s*(dp|dip|sp|px|pt|in|mm)\\s*$")
            .matchEntire(value) ?: return null
    val amount = match.groupValues[1].toFloatOrNull() ?: return null
    val unit =
        when (match.groupValues[2]) {
          "dp", "dip" -> TypedValue.COMPLEX_UNIT_DIP
          "sp" -> TypedValue.COMPLEX_UNIT_SP
          "px" -> TypedValue.COMPLEX_UNIT_PX
          "pt" -> TypedValue.COMPLEX_UNIT_PT
          "in" -> TypedValue.COMPLEX_UNIT_IN
          "mm" -> TypedValue.COMPLEX_UNIT_MM
          else -> return null
        }
    return TypedValue.applyDimension(unit, amount, context.resources.displayMetrics)
  }

  private val SUPPORTED_ATTRIBUTES =
      setOf(
          "textsize", "layout_width", "layout_height", "minwidth", "minheight", "elevation",
          "padding", "paddingleft", "paddingright", "paddingtop", "paddingbottom",
          "paddingstart", "paddingend", "layout_margin", "layout_margintop",
          "layout_marginbottom", "layout_marginstart", "layout_marginend",
          "layout_marginleft", "layout_marginright",
      )
}
