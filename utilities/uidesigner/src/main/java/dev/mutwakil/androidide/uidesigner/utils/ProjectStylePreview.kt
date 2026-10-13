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
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import com.google.android.material.textfield.TextInputLayout
import java.io.File
import org.slf4j.LoggerFactory

/**
 * Applies the subset of project styles that can be previewed faithfully without AAPT linking.
 * Unsupported theme attributes are deliberately skipped rather than replaced with IDE resources.
 */
internal object ProjectStylePreview {

  private val log = LoggerFactory.getLogger(ProjectStylePreview::class.java)

  fun apply(view: View, styleValue: String, context: Context, layoutFile: File?): Boolean {
    if (!styleValue.startsWith("@style/")) return false
    val styleName = styleValue.substringAfter("@style/").substringBefore('/')
    if (styleName.isBlank()) return true

    val projectRoot = ProjectResourceLocator.findProjectRoot(layoutFile)
    if (projectRoot == null) return false
    val items =
        ProjectResourceLocator.resolveStyleItems(
            projectRoot,
            styleName,
            context,
            ProjectResourceLocator.findModuleRoot(layoutFile),
            ProjectResourceLocator.findSourceSet(layoutFile),
        )
    if (items == null) {
      log.warn("Cannot resolve project style '{}' in {}", styleName, layoutFile?.absolutePath)
      return true
    }

    items.forEach { (rawName, value) ->
      val name = rawName.substringAfter(':').lowercase()
      try {
        when (name) {
          "text", "hint", "title", "contentdescription" -> {
            if (!ProjectTextResourcePreview.apply(view, rawName, value, context, layoutFile)) {
              when (name) {
                "text" -> (view as? TextView)?.text = value
                "hint" -> when (view) {
                  is TextInputLayout -> view.hint = value
                  is TextView -> view.hint = value
                }
                "title" -> when (view) {
                  is Toolbar -> view.title = value
                  is TextView -> view.text = value
                }
                "contentdescription" -> view.contentDescription = value
              }
            }
          }
          "textcolor", "texthintcolor" -> {
            val color = resolveColor(value, context, layoutFile) ?: return@forEach
            val textView = view as? TextView ?: return@forEach
            if (name == "textcolor") textView.setTextColor(color) else textView.setHintTextColor(color)
          }
          "background" -> {
            val vectorRenderer = VectorDrawableRenderer()
            val applied =
                vectorRenderer.applyVectorPreview(
                    view, value, context, layoutFile, isBackground = true
                ) || BackgroundPreviewRenderer().applyBackgroundPreview(view, value, context, layoutFile)
            if (!applied && value.startsWith("#")) view.background = ColorDrawable(Color.parseColor(value))
          }
          "textsize", "padding", "paddingleft", "paddingright", "paddingtop", "paddingbottom",
          "paddingstart", "paddingend", "layout_width", "layout_height", "layout_margin",
          "layout_margintop", "layout_marginbottom", "layout_marginstart", "layout_marginend",
          "layout_marginleft", "layout_marginright", "minwidth", "minheight", "elevation" -> {
            if (!ProjectDimensionPreview.apply(view, rawName, value, context, layoutFile)) {
              applyLiteralDimension(view, name, value, context)
            }
          }
          "visibility" -> view.visibility = when (value.lowercase()) {
            "gone" -> View.GONE
            "invisible" -> View.INVISIBLE
            else -> View.VISIBLE
          }
          "enabled" -> view.isEnabled = value.toBooleanStrictOrNull() ?: view.isEnabled
        }
      } catch (error: Exception) {
        log.debug("Could not apply project style item '{}'='{}': {}", rawName, value, error.message)
      }
    }
    return true
  }

  private fun resolveColor(value: String, context: Context, layoutFile: File?): Int? {
    if (value.startsWith("#")) return runCatching { Color.parseColor(value) }.getOrNull()
    if (value.startsWith("@color/")) {
      val root = ProjectResourceLocator.findProjectRoot(layoutFile)
      val resolved =
          root?.let {
            ProjectResourceLocator.resolveValueResource(
                it,
                "color",
                value.substringAfter("@color/"),
                context,
                ProjectResourceLocator.findModuleRoot(layoutFile),
                ProjectResourceLocator.findSourceSet(layoutFile),
            )
          }
      return resolved?.let { runCatching { Color.parseColor(it) }.getOrNull() }
    }
    return null
  }

  private fun applyLiteralDimension(view: View, name: String, value: String, context: Context) {
    val match =
        Regex("^\\s*(-?(?:\\d+(?:\\.\\d*)?|\\.\\d+))\\s*(dp|dip|sp|px|pt|in|mm)\\s*$")
            .matchEntire(value) ?: return
    val amount = match.groupValues[1].toFloatOrNull() ?: return
    val unit = when (match.groupValues[2]) {
      "dp", "dip" -> TypedValue.COMPLEX_UNIT_DIP
      "sp" -> TypedValue.COMPLEX_UNIT_SP
      "px" -> TypedValue.COMPLEX_UNIT_PX
      "pt" -> TypedValue.COMPLEX_UNIT_PT
      "in" -> TypedValue.COMPLEX_UNIT_IN
      "mm" -> TypedValue.COMPLEX_UNIT_MM
      else -> return
    }
    val pixels = TypedValue.applyDimension(unit, amount, context.resources.displayMetrics)
    when (name) {
      "textsize" -> (view as? TextView)?.setTextSize(TypedValue.COMPLEX_UNIT_PX, pixels)
      "padding" -> view.setPadding(pixels.toInt(), pixels.toInt(), pixels.toInt(), pixels.toInt())
      "paddingleft" -> view.setPadding(pixels.toInt(), view.paddingTop, view.paddingRight, view.paddingBottom)
      "paddingright" -> view.setPadding(view.paddingLeft, view.paddingTop, pixels.toInt(), view.paddingBottom)
      "paddingtop" -> view.setPadding(view.paddingLeft, pixels.toInt(), view.paddingRight, view.paddingBottom)
      "paddingbottom" -> view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, pixels.toInt())
      "paddingstart" -> view.setPaddingRelative(pixels.toInt(), view.paddingTop, view.paddingEnd, view.paddingBottom)
      "paddingend" -> view.setPaddingRelative(view.paddingStart, view.paddingTop, pixels.toInt(), view.paddingBottom)
      "layout_width", "layout_height" -> {
        val params = view.layoutParams ?: return
        if (name == "layout_width") params.width = pixels.toInt() else params.height = pixels.toInt()
        view.layoutParams = params
      }
      "minwidth" -> view.minimumWidth = pixels.toInt()
      "minheight" -> view.minimumHeight = pixels.toInt()
      "elevation" -> view.elevation = pixels
      "layout_margin", "layout_margintop", "layout_marginbottom", "layout_marginstart",
      "layout_marginend", "layout_marginleft", "layout_marginright" -> {
        val params = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return
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
  }
}
