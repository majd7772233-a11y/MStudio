/*
 *  This file is part of AndroidCodeStudio.
 *
 *  AndroidCodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidCodeStudio is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package dev.mutwakil.androidide.uidesigner.utils

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileInputStream
import org.slf4j.LoggerFactory
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

/**
 * Helper class to apply background previews in the UI Designer This loads actual image files and
 * XML vectors from Android resource folders
 *
 * @author Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null
 */
class BackgroundPreviewRenderer {

  companion object {
    private val log = LoggerFactory.getLogger(BackgroundPreviewRenderer::class.java)
  }

  /** Apply background preview to a view in the designer */
  fun applyBackgroundPreview(
      view: View,
      backgroundValue: String,
      context: Context,
      layoutFile: File? = null,
  ): Boolean {
    val value = backgroundValue.trim()

    try {
      when {
        value.isEmpty() -> return false
        value == "transparent" -> {
          view.background = ColorDrawable(Color.TRANSPARENT)
          return true
        }
        value.startsWith("#") -> {
          val color = Color.parseColor(value)
          view.background = ColorDrawable(color)
          return true
        }
        value.startsWith("@drawable/") -> {
          return loadDrawableResource(view, value, context, layoutFile)
        }
        value.startsWith("@mipmap/") -> {
          return loadMipmapResource(view, value, context, layoutFile)
        }
        value.startsWith("@color/") -> {
          return loadColorResource(view, value, context, layoutFile)
        }
        value.startsWith("@android:drawable/") -> {
          return loadAndroidDrawable(view, value, context)
        }
        value.startsWith("@android:color/") -> {
          return loadAndroidColor(view, value, context)
        }
        else -> {
          try {
            val color = Color.parseColor(value)
            view.background = ColorDrawable(color)
            return true
          } catch (e: Exception) {
            return false
          }
        }
      }
    } catch (e: Exception) {
      log.debug("Could not preview background '{}': {}", value, e.message)
      return false
    }
  }

  private fun loadDrawableResource(
      view: View,
      resourceName: String,
      context: Context,
      layoutFile: File?,
  ): Boolean {
    val drawableName = resourceName.substringAfter("@drawable/")

    // Project resources take precedence over MStudio resources with the same name.
    if (loadFromResDrawable(view, drawableName, context, layoutFile)) return true

    return try {
      val resourceId =
          context.resources.getIdentifier(drawableName, "drawable", context.packageName)
              .takeIf { it != 0 }
              ?: context.applicationContext.resources.getIdentifier(
                  drawableName,
                  "drawable",
                  context.applicationContext.packageName,
              )
      if (resourceId == 0) return false
      val drawable = ContextCompat.getDrawable(context, resourceId) ?: return false
      view.background = drawable
      true
    } catch (e: Exception) {
      log.debug("Unable to resolve drawable '{}': {}", drawableName, e.message)
      false
    }
  }

  private fun loadMipmapResource(
      view: View,
      resourceName: String,
      context: Context,
      layoutFile: File?,
  ): Boolean {
    val mipmapName = resourceName.substringAfter("@mipmap/")

    if (loadFromResMipmap(view, mipmapName, context, layoutFile)) return true

    return try {
      val resourceId =
          context.resources.getIdentifier(mipmapName, "mipmap", context.packageName)
              .takeIf { it != 0 }
              ?: context.applicationContext.resources.getIdentifier(
                  mipmapName,
                  "mipmap",
                  context.applicationContext.packageName,
              )
      if (resourceId == 0) return false
      val drawable = ContextCompat.getDrawable(context, resourceId) ?: return false
      view.background = drawable
      true
    } catch (e: Exception) {
      log.debug("Unable to resolve mipmap '{}': {}", mipmapName, e.message)
      false
    }
  }

  private fun loadFromResDrawable(
      view: View,
      drawableName: String,
      context: Context,
      layoutFile: File?,
  ): Boolean {
    val projectRoot = findProjectRoot(layoutFile) ?: return false

    val drawableFolders =
        ProjectResourceLocator.findResourceDirectories(
            projectRoot,
            "drawable",
            context,
            ProjectResourceLocator.findModuleRoot(layoutFile),
            ProjectResourceLocator.findSourceSet(layoutFile),
        )

    return loadImageFromFolders(view, drawableName, drawableFolders, context, layoutFile)
  }

  private fun loadFromResMipmap(
      view: View,
      mipmapName: String,
      context: Context,
      layoutFile: File?,
  ): Boolean {
    val projectRoot = findProjectRoot(layoutFile) ?: return false

    val mipmapFolders =
        ProjectResourceLocator.findResourceDirectories(
            projectRoot,
            "mipmap",
            context,
            ProjectResourceLocator.findModuleRoot(layoutFile),
            ProjectResourceLocator.findSourceSet(layoutFile),
        )

    return loadImageFromFolders(view, mipmapName, mipmapFolders, context, layoutFile)
  }

  private fun loadImageFromFolders(
      view: View,
      imageName: String,
      folders: List<File>,
      context: Context,
      layoutFile: File?,
  ): Boolean {
    val extensions = listOf(".png", ".jpg", ".jpeg", ".webp", ".gif", ".xml")

    for (resourceDir in folders) {
      if (!resourceDir.isDirectory || !resourceDir.canRead()) continue

      for (ext in extensions) {
        val imageFile = File(resourceDir, "$imageName$ext")
        if (imageFile.exists() && imageFile.canRead()) {
          try {
            // Handle XML drawables (vectors, shapes, etc.)
            if (ext == ".xml") {
              log.debug("Attempting to load XML drawable: {}", imageFile.absolutePath)
              if (loadXmlDrawable(view, imageFile, context, layoutFile)) {
                return true
              }
              continue
            }

            val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath)
            if (bitmap != null) {
              view.background = BitmapDrawable(context.resources, bitmap)
              log.debug("Loaded image: {}", imageFile.absolutePath)
              return true
            }
          } catch (e: Exception) {
            log.debug("Error loading image {}: {}", imageFile.absolutePath, e.message)
          }
        }
      }
    }

    return false
  }

  /** Load XML drawable files (vectors, shapes, gradients, etc.) */
  private fun loadXmlDrawable(
      view: View,
      xmlFile: File,
      context: Context,
      layoutFile: File?,
  ): Boolean {
    try {
      // First, try using VectorDrawableCompat for vector drawables
      if (isVectorDrawable(xmlFile)) {
        return loadVectorDrawable(view, xmlFile, context, layoutFile)
      }

      // Try parsing as shape drawable
      if (isShapeDrawable(xmlFile)) {
        return loadShapeDrawable(view, xmlFile, context, layoutFile)
      }

      // Try parsing as layer-list or other drawable types
      return loadGenericXmlDrawable(view, xmlFile, context)
    } catch (e: Exception) {
      log.debug("Error loading XML drawable {}: {}", xmlFile.absolutePath, e.message)
      return false
    }
  }

  /** Check if XML file is a vector drawable */
  private fun isVectorDrawable(xmlFile: File): Boolean {
    try {
      val factory = XmlPullParserFactory.newInstance()
      val parser = factory.newPullParser()
      parser.setInput(FileInputStream(xmlFile), "UTF-8")

      var eventType = parser.eventType
      while (eventType != XmlPullParser.END_DOCUMENT) {
        if (eventType == XmlPullParser.START_TAG) {
          return parser.name == "vector"
        }
        eventType = parser.next()
      }
    } catch (e: Exception) {
      // Ignore parsing errors
    }
    return false
  }

  /** Check if XML file is a shape drawable */
  private fun isShapeDrawable(xmlFile: File): Boolean {
    try {
      val factory = XmlPullParserFactory.newInstance()
      val parser = factory.newPullParser()
      parser.setInput(FileInputStream(xmlFile), "UTF-8")

      var eventType = parser.eventType
      while (eventType != XmlPullParser.END_DOCUMENT) {
        if (eventType == XmlPullParser.START_TAG) {
          return parser.name == "shape"
        }
        eventType = parser.next()
      }
    } catch (e: Exception) {
      // Ignore parsing errors
    }
    return false
  }

  /** Load vector drawable using VectorDrawableCompat */
  private fun loadVectorDrawable(
      view: View,
      xmlFile: File,
      context: Context,
      layoutFile: File?,
  ): Boolean {
    return try {
      // XML vectors are not image streams. Parse their vector/path data using the same renderer
      // used by ImageView src previews, and never report a fake placeholder as a successful render.
      VectorDrawableRenderer().applyVectorPreview(
          view = view,
          vectorValue = xmlFile.nameWithoutExtension,
          context = context,
          layoutFile = layoutFile ?: xmlFile,
          isBackground = true,
      )
    } catch (e: Exception) {
      log.debug("Vector loading failed for {}: {}", xmlFile.name, e.message)
      false
    }
  }

  data class GradientInfo(
      val colors: IntArray,
      val orientation: GradientDrawable.Orientation,
      val cornerRadius: Float = 0f,
      val topLeftRadius: Float = 0f,
      val topRightRadius: Float = 0f,
      val bottomLeftRadius: Float = 0f,
      val bottomRightRadius: Float = 0f,
  )

  private fun extractGradientFromXml(xmlFile: File): GradientInfo? {
    try {
      val content = xmlFile.readText()

      // Extract colors (existing code)
      val colorRegex = """(startColor|endColor|centerColor)="(#[0-9A-Fa-f]{6,8})"""".toRegex()
      val colorMatches = colorRegex.findAll(content).toList()

      // Extract angle (existing code)
      val angleRegex = """angle="(\d+)"""".toRegex()
      val angleMatch = angleRegex.find(content)
      val angle = angleMatch?.groupValues?.get(1)?.toFloatOrNull() ?: 0f

      // Extract corner radius values
      val radiusRegex =
          """(radius|topLeftRadius|topRightRadius|bottomLeftRadius|bottomRightRadius)="(\d+(?:\.\d+)?)(dp|px)?""""
              .toRegex()
      val radiusMatches = radiusRegex.findAll(content).toList()

      var cornerRadius = 0f
      var topLeftRadius = 0f
      var topRightRadius = 0f
      var bottomLeftRadius = 0f
      var bottomRightRadius = 0f

      radiusMatches.forEach { match ->
        val radiusType = match.groupValues[1]
        val radiusValue = match.groupValues[2].toFloatOrNull() ?: 0f

        when (radiusType) {
          "radius" -> cornerRadius = radiusValue
          "topLeftRadius" -> topLeftRadius = radiusValue
          "topRightRadius" -> topRightRadius = radiusValue
          "bottomLeftRadius" -> bottomLeftRadius = radiusValue
          "bottomRightRadius" -> bottomRightRadius = radiusValue
        }
      }

      // Build colors array (existing code)
      if (colorMatches.isNotEmpty()) {
        val colors = mutableListOf<Int>()

        colorMatches
            .find { it.groupValues[1] == "startColor" }
            ?.let { colors.add(Color.parseColor(it.groupValues[2])) }
        colorMatches
            .find { it.groupValues[1] == "centerColor" }
            ?.let { colors.add(Color.parseColor(it.groupValues[2])) }
        colorMatches
            .find { it.groupValues[1] == "endColor" }
            ?.let { colors.add(Color.parseColor(it.groupValues[2])) }

        if (colors.size >= 2) {
          return GradientInfo(
              colors = colors.toIntArray(),
              orientation = getGradientOrientation(angle),
              cornerRadius = cornerRadius,
              topLeftRadius = topLeftRadius,
              topRightRadius = topRightRadius,
              bottomLeftRadius = bottomLeftRadius,
              bottomRightRadius = bottomRightRadius,
          )
        }
      }
    } catch (e: Exception) {
      log.debug("Failed to extract gradient: {}", e.message)
    }
    return null
  }

  /** Load shape drawable by parsing XML */
  private fun loadShapeDrawable(
      view: View,
      xmlFile: File,
      context: Context,
      layoutFile: File?,
  ): Boolean {
    try {
      val drawable = parseShapeDrawable(xmlFile, context, layoutFile)
      if (drawable != null) {
        view.background = drawable
        log.debug("Successfully loaded shape drawable: {}", xmlFile.absolutePath)
        return true
      }
    } catch (e: Exception) {
      log.debug("Failed to load shape drawable {}: {}", xmlFile.absolutePath, e.message)
    }
    return false
  }

  /** Parse shape drawable from XML file */
  private fun parseShapeDrawable(
      xmlFile: File,
      context: Context,
      layoutFile: File?,
  ): GradientDrawable? {
    try {
      val factory = XmlPullParserFactory.newInstance()
      val parser = factory.newPullParser()
      parser.setInput(FileInputStream(xmlFile), "UTF-8")

      val drawable = GradientDrawable()
      var currentTag = ""

      var eventType = parser.eventType
      while (eventType != XmlPullParser.END_DOCUMENT) {
        when (eventType) {
          XmlPullParser.START_TAG -> {
            currentTag = parser.name
            when (currentTag) {
              "shape" -> {
                val shape = parser.getAttributeValue(null, "android:shape")
                when (shape) {
                  "rectangle" -> drawable.shape = GradientDrawable.RECTANGLE
                  "oval" -> drawable.shape = GradientDrawable.OVAL
                  "ring" -> drawable.shape = GradientDrawable.RING
                  "line" -> drawable.shape = GradientDrawable.LINE
                  else -> drawable.shape = GradientDrawable.RECTANGLE
                }
              }
              "solid" -> {
                val color = parser.getAttributeValue(null, "android:color")
                if (color != null) {
                  drawable.setColor(parseColor(color, context, layoutFile))
                }
              }
              "gradient" -> {
                parseGradient(parser, drawable, context, layoutFile)
              }
              "corners" -> {
                val radius = parser.getAttributeValue(null, "android:radius")
                if (radius != null) {
                  val radiusValue = parseDimension(radius, context, layoutFile)
                  drawable.cornerRadius = radiusValue
                }
              }
              "stroke" -> {
                val width = parser.getAttributeValue(null, "android:width")
                val color = parser.getAttributeValue(null, "android:color")
                if (width != null && color != null) {
                  drawable.setStroke(parseDimension(width, context, layoutFile).toInt(), parseColor(color, context, layoutFile))
                }
              }
            }
          }
        }
        eventType = parser.next()
      }

      return drawable
    } catch (e: Exception) {
      log.debug("Error parsing shape drawable: {}", e.message)
      return null
    }
  }

  /** Parse gradient attributes */
  private fun parseGradient(
      parser: XmlPullParser,
      drawable: GradientDrawable,
      context: Context,
      layoutFile: File?,
  ) {
    try {
      val startColor = parser.getAttributeValue(null, "android:startColor")
      val endColor = parser.getAttributeValue(null, "android:endColor")
      val centerColor = parser.getAttributeValue(null, "android:centerColor")
      val angle = parser.getAttributeValue(null, "android:angle")
      val type = parser.getAttributeValue(null, "android:type")

      val colors = mutableListOf<Int>()

      if (startColor != null) {
        colors.add(parseColor(startColor, context, layoutFile))
      }
      if (centerColor != null) {
        colors.add(parseColor(centerColor, context, layoutFile))
      }
      if (endColor != null) {
        colors.add(parseColor(endColor, context, layoutFile))
      }

      if (colors.isNotEmpty()) {
        drawable.colors = colors.toIntArray()
      }

      // Set gradient type
      when (type) {
        "radial" -> drawable.gradientType = GradientDrawable.RADIAL_GRADIENT
        "sweep" -> drawable.gradientType = GradientDrawable.SWEEP_GRADIENT
        else -> drawable.gradientType = GradientDrawable.LINEAR_GRADIENT
      }

      // Set orientation based on angle
      if (angle != null) {
        val angleValue = angle.toFloatOrNull() ?: 0f
        drawable.orientation = getGradientOrientation(angleValue)
      }
    } catch (e: Exception) {
      log.debug("Error parsing gradient: {}", e.message)
    }
  }

  /** Get gradient orientation from angle */
  private fun getGradientOrientation(angle: Float): GradientDrawable.Orientation {
    return when ((angle % 360).toInt()) {
      0 -> GradientDrawable.Orientation.LEFT_RIGHT
      45 -> GradientDrawable.Orientation.BL_TR
      90 -> GradientDrawable.Orientation.BOTTOM_TOP
      135 -> GradientDrawable.Orientation.BR_TL
      180 -> GradientDrawable.Orientation.RIGHT_LEFT
      225 -> GradientDrawable.Orientation.TR_BL
      270 -> GradientDrawable.Orientation.TOP_BOTTOM
      315 -> GradientDrawable.Orientation.TL_BR
      else -> GradientDrawable.Orientation.LEFT_RIGHT
    }
  }

  /** Resolve literal/project colors without silently substituting gray for missing values. */
  private fun parseColor(colorString: String, context: Context, layoutFile: File?): Int {
    return when {
      colorString.startsWith("@color/") -> {
        val projectRoot = ProjectResourceLocator.findProjectRoot(layoutFile)
        val resolved =
            projectRoot?.let {
              ProjectResourceLocator.resolveValueResource(
                  it,
                  "color",
                  colorString.substringAfter("@color/"),
                  context,
                  ProjectResourceLocator.findModuleRoot(layoutFile),
                  ProjectResourceLocator.findSourceSet(layoutFile),
              )
            }
        when {
          resolved != null -> runCatching { Color.parseColor(resolved) }.getOrNull()
          else -> {
            val name = colorString.substringAfter("@color/")
            val id = context.resources.getIdentifier(name, "color", context.packageName)
            if (id != 0) runCatching { ContextCompat.getColor(context, id) }.getOrNull() else null
          }
        } ?: throw IllegalArgumentException("Unresolved color resource: $colorString")
      }
      colorString.startsWith("@android:color/") -> {
        val name = colorString.substringAfter("@android:color/")
        val id = context.resources.getIdentifier(name, "color", "android")
        if (id == 0) throw IllegalArgumentException("Unresolved Android color: $colorString")
        ContextCompat.getColor(context, id)
      }
      else -> Color.parseColor(colorString)
    }
  }

  /** Resolve common Android dimension units and project @dimen aliases for shape previews. */
  private fun parseDimension(
      dimensionString: String,
      context: Context,
      layoutFile: File?,
  ): Float {
    val value =
        if (dimensionString.startsWith("@dimen/")) {
          val root = ProjectResourceLocator.findProjectRoot(layoutFile)
          root?.let {
            ProjectResourceLocator.resolveValueResource(
                it,
                "dimen",
                dimensionString.substringAfter("@dimen/"),
                context,
                ProjectResourceLocator.findModuleRoot(layoutFile),
                ProjectResourceLocator.findSourceSet(layoutFile),
            )
          } ?: throw IllegalArgumentException("Unresolved dimension resource: $dimensionString")
        } else {
          dimensionString
        }
    val number = Regex("^\\s*(-?(?:\\d+(?:\\.\\d*)?|\\.\\d+))\\s*(dp|dip|sp|px|pt|in|mm)?\\s*$")
        .matchEntire(value)
        ?: throw IllegalArgumentException("Unsupported dimension value: $value")
    val amount = number.groupValues[1].toFloat()
    return when (number.groupValues[2]) {
      "dp", "dip" -> amount * context.resources.displayMetrics.density
      "sp" -> amount * context.resources.displayMetrics.scaledDensity
      "pt" -> amount * context.resources.displayMetrics.xdpi / 72f
      "in" -> amount * context.resources.displayMetrics.xdpi
      "mm" -> amount * context.resources.displayMetrics.xdpi / 25.4f
      else -> amount
    }
  }

  /**
   * Unknown drawable XML must not be represented by a fake solid color. Returning false keeps the
   * preview honest and lets the designer surface an unsupported-resource state instead.
   *
   * AAPT-linked resources are required to inflate layer-list, selector, ripple and animated
   * drawables with their real theme and resource references; parsing them as plain XML here would
   * silently produce a preview different from the app.
   */
  private fun loadGenericXmlDrawable(view: View, xmlFile: File, context: Context): Boolean {
    log.debug(
        "Cannot faithfully preview this XML drawable without linked resources: {}",
        xmlFile.absolutePath,
    )
    return false
  }

  /** Find project root based on the layout file path */
  private fun findProjectRoot(layoutFile: File?): File? =
      ProjectResourceLocator.findProjectRoot(layoutFile)

  private fun loadColorResource(
      view: View,
      resourceName: String,
      context: Context,
      layoutFile: File?,
  ): Boolean {
    val colorName = resourceName.substringAfter("@color/")

    // Resolve the opened project's values first, avoiding collisions with the IDE's own resources.
    val projectRoot = ProjectResourceLocator.findProjectRoot(layoutFile)
    val projectColor =
        projectRoot?.let {
          ProjectResourceLocator.resolveValueResource(
              it,
              "color",
              colorName,
              context,
              ProjectResourceLocator.findModuleRoot(layoutFile),
              ProjectResourceLocator.findSourceSet(layoutFile),
          )
        }
    if (projectColor != null) {
      return try {
        view.background = ColorDrawable(Color.parseColor(projectColor))
        true
      } catch (e: Exception) {
        log.debug("Project color '{}' could not be previewed: {}", colorName, e.message)
        false
      }
    }

    return try {
      val resourceId =
          context.resources.getIdentifier(colorName, "color", context.packageName)
              .takeIf { it != 0 }
              ?: context.applicationContext.resources.getIdentifier(
                  colorName,
                  "color",
                  context.applicationContext.packageName,
              )
      if (resourceId == 0) return false
      val color = ContextCompat.getColor(context, resourceId)
      view.background = ColorDrawable(color)
      true
    } catch (e: Exception) {
      log.debug("Unable to resolve color '{}': {}", colorName, e.message)
      false
    }
  }


  private fun loadAndroidDrawable(view: View, resourceName: String, context: Context): Boolean {
    return try {
      val drawableName = resourceName.substringAfter("@android:drawable/")
      val resourceId = context.resources.getIdentifier(drawableName, "drawable", "android")

      if (resourceId != 0) {
        val drawable = ContextCompat.getDrawable(context, resourceId)
        if (drawable != null) {
          view.background = drawable
          return true
        }
      }

      false
    } catch (e: Exception) {
      false
    }
  }

  private fun loadAndroidColor(view: View, resourceName: String, context: Context): Boolean {
    return try {
      val colorName = resourceName.substringAfter("@android:color/")
      val resourceId = context.resources.getIdentifier(colorName, "color", "android")

      if (resourceId != 0) {
        val color = ContextCompat.getColor(context, resourceId)
        view.background = ColorDrawable(color)
        return true
      }

      false
    } catch (e: Exception) {
      false
    }
  }
}

/** Enhanced background completion */
object BackgroundCompletions {

  val COMMON_BACKGROUNDS =
      listOf(
          // Solid colors
          "#FFFFFF",
          "#000000",
          "#808080",
          "#FF0000",
          "#00FF00",
          "#0000FF",
          "#FFFF00",
          "#FF00FF",
          "#00FFFF",
          "#FFC0CB",
          "#FFA500",
          "#800080",

          // Transparent
          "transparent",
          "@android:color/transparent",

          // Common Android backgrounds
          "@android:drawable/btn_default",
          "@android:drawable/list_selector_background",
          "@android:color/white",
          "@android:color/black",
          "@android:color/darker_gray",

          // Resource references
          "@drawable/",
          "@color/",
          "@mipmap/",
      )

  fun getBackgroundSuggestions(currentText: String): List<String> {
    return when {
      currentText.startsWith("#") -> getColorSuggestions(currentText)
      currentText.startsWith("@drawable/") -> listOf("@drawable/")
      currentText.startsWith("@color/") -> listOf("@color/")
      currentText.startsWith("@mipmap/") -> listOf("@mipmap/")
      else -> COMMON_BACKGROUNDS.filter { it.contains(currentText, ignoreCase = true) }.take(10)
    }
  }

  private fun getColorSuggestions(current: String): List<String> {
    return when (current.length) {
      1 -> listOf("#FFFFFF", "#000000", "#FF0000", "#00FF00", "#0000FF")
      2,
      3,
      4,
      5,
      6 -> {
        val suggestions = mutableListOf<String>()
        if (current.length < 7) {
          suggestions.add(current + "0".repeat(7 - current.length))
          suggestions.add(current + "F".repeat(7 - current.length))
        }
        suggestions.add(current)
        suggestions
      }
      else -> listOf(current)
    }
  }
}
