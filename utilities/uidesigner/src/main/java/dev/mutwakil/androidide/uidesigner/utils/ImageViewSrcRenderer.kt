/*
 * Enhanced ImageView Src Preview Support for AndroidIDE UI Designer
 * This loads actual drawable resources for ImageView src attributes
 */

package dev.mutwakil.androidide.uidesigner.utils

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.widget.ImageView
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileInputStream
import org.slf4j.LoggerFactory
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

/**
 * Helper class to apply ImageView src previews in the UI Designer This loads actual image files and
 * XML vectors from Android resource folders
 */
class ImageViewSrcRenderer {

  companion object {
    private val log = LoggerFactory.getLogger(ImageViewSrcRenderer::class.java)
  }

  /** Apply src preview to an ImageView in the designer */
  fun applySrcPreview(
      imageView: ImageView,
      srcValue: String,
      context: Context,
      layoutFile: File? = null,
  ): Boolean {
    val value = srcValue.trim()

    try {
      when {
        value.isEmpty() -> return false
        value.startsWith("@drawable/") -> {
          return loadDrawableResource(imageView, value, context, layoutFile)
        }
        value.startsWith("@mipmap/") -> {
          return loadMipmapResource(imageView, value, context, layoutFile)
        }
        value.startsWith("@android:drawable/") -> {
          return loadAndroidDrawable(imageView, value, context)
        }
        else -> {
          // Try to parse as resource name without prefix
          return loadDrawableResource(imageView, "@drawable/$value", context, layoutFile)
        }
      }
    } catch (e: Exception) {
      log.debug("Could not preview src '{}': {}", value, e.message)
      return false
    }
  }

  private fun loadDrawableResource(
      imageView: ImageView,
      resourceName: String,
      context: Context,
      layoutFile: File?,
  ): Boolean {
    val drawableName = resourceName.substringAfter("@drawable/")

    // Resolve the opened project's own resource before consulting MStudio's installed resources;
    // identical resource names across the two apps must not render the wrong image.
    if (loadFromResDrawable(imageView, drawableName, context, layoutFile)) return true

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
      imageView.setImageDrawable(drawable)
      true
    } catch (e: Exception) {
      log.debug("Unable to resolve drawable resource '{}': {}", drawableName, e.message)
      false
    }
  }

  private fun loadMipmapResource(
      imageView: ImageView,
      resourceName: String,
      context: Context,
      layoutFile: File?,
  ): Boolean {
    val mipmapName = resourceName.substringAfter("@mipmap/")

    // Prefer the opened project's mipmap, including qualifier-specific and non-app modules.
    if (loadFromResMipmap(imageView, mipmapName, context, layoutFile)) return true

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
      imageView.setImageDrawable(drawable)
      true
    } catch (e: Exception) {
      log.debug("Unable to resolve mipmap resource '{}': {}", mipmapName, e.message)
      false
    }
  }

  private fun loadFromResDrawable(
      imageView: ImageView,
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

    return loadImageFromFolders(imageView, drawableName, drawableFolders, context, layoutFile)
  }

  private fun loadFromResMipmap(
      imageView: ImageView,
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

    return loadImageFromFolders(imageView, mipmapName, mipmapFolders, context, layoutFile)
  }

  private fun loadImageFromFolders(
      imageView: ImageView,
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
              log.debug("Attempting to load XML drawable for ImageView: {}", imageFile.absolutePath)
              if (loadXmlDrawable(imageView, imageFile, context, layoutFile)) {
                return true
              }
              continue
            }

            val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath)
            if (bitmap != null) {
              imageView.setImageBitmap(bitmap)
              log.debug("Loaded image for ImageView: {}", imageFile.absolutePath)
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

  /** Load XML drawable files (vectors, shapes, gradients, etc.) for ImageView */
  private fun loadXmlDrawable(
      imageView: ImageView,
      xmlFile: File,
      context: Context,
      layoutFile: File?,
  ): Boolean {
    try {
      // First, try using VectorDrawableCompat for vector drawables
      if (isVectorDrawable(xmlFile)) {
        return loadVectorDrawable(imageView, xmlFile, context, layoutFile)
      }

      // Try parsing as shape drawable
      if (isShapeDrawable(xmlFile)) {
        return loadShapeDrawable(imageView, xmlFile, context)
      }

      // Try parsing as layer-list or other drawable types
      return loadGenericXmlDrawable(imageView, xmlFile, context)
    } catch (e: Exception) {
      log.debug("Error loading XML drawable for ImageView {}: {}", xmlFile.absolutePath, e.message)
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

  /** Load vector drawable for ImageView */
  private fun loadVectorDrawable(
      imageView: ImageView,
      xmlFile: File,
      context: Context,
      layoutFile: File?,
  ): Boolean {
    // VectorDrawable XML is not a bitmap stream. Use the same project-aware renderer as
    // background previews so path data, viewport dimensions and tint are actually interpreted.
    return try {
      VectorDrawableRenderer().applyVectorPreview(
          view = imageView,
          vectorValue = xmlFile.nameWithoutExtension,
          context = context,
          layoutFile = layoutFile ?: xmlFile,
          isBackground = false,
      )
    } catch (e: Exception) {
      log.debug("Vector loading failed for ImageView {}: {}", xmlFile.name, e.message)
      false
    }
  }

  /** Load shape drawable for ImageView by parsing XML */
  private fun loadShapeDrawable(imageView: ImageView, xmlFile: File, context: Context): Boolean {
    try {
      // For ImageView, we can reuse the BackgroundPreviewRenderer's shape parsing logic
      // but apply it to setImageDrawable instead of background
      val backgroundRenderer = BackgroundPreviewRenderer()

      // Create a temporary view to parse the shape, then extract the drawable
      val tempView = android.view.View(context)
      val xmlContent = xmlFile.readText()

      // Try to extract basic shape properties for a simple shape drawable
      val drawable = parseSimpleShapeForImageView(xmlFile)
      if (drawable != null) {
        imageView.setImageDrawable(drawable)
        log.debug("Successfully loaded shape drawable for ImageView: {}", xmlFile.absolutePath)
        return true
      }
    } catch (e: Exception) {
      log.debug(
          "Failed to load shape drawable for ImageView {}: {}",
          xmlFile.absolutePath,
          e.message,
      )
    }
    return false
  }

  /** Parse a simple shape drawable specifically for ImageView */
  private fun parseSimpleShapeForImageView(xmlFile: File): Drawable? {
    try {
      val content = xmlFile.readText()

      // Create a simple colored shape as preview
      val colorRegex = """android:color="(#[0-9A-Fa-f]{6,8})"""".toRegex()
      val colorMatch = colorRegex.find(content)

      if (colorMatch != null) {
        val color = Color.parseColor(colorMatch.groupValues[1])
        return ColorDrawable(color)
      }

      // No reliable color was resolved; report unsupported rather than inventing one.
      return null
    } catch (e: Exception) {
      log.debug("Error parsing simple shape: {}", e.message)
      return null
    }
  }

  /**
   * Do not claim success by substituting an unrelated orange rectangle for an unsupported drawable.
   * These XML resources need the project's linked AAPT resources and theme to render faithfully.
   */
  private fun loadGenericXmlDrawable(
      imageView: ImageView,
      xmlFile: File,
      context: Context,
  ): Boolean {
    log.debug(
        "Cannot faithfully preview this XML drawable for ImageView without linked resources: {}",
        xmlFile.absolutePath,
    )
    return false
  }

  /**
   * Find project root based on the layout file path (reused from BackgroundPreviewRenderer logic)
   */
  private fun findProjectRoot(layoutFile: File?): File? =
      ProjectResourceLocator.findProjectRoot(layoutFile)

  private fun loadAndroidDrawable(
      imageView: ImageView,
      resourceName: String,
      context: Context,
  ): Boolean {
    return try {
      val drawableName = resourceName.substringAfter("@android:drawable/")
      val resourceId = context.resources.getIdentifier(drawableName, "drawable", "android")

      if (resourceId != 0) {
        val drawable = ContextCompat.getDrawable(context, resourceId)
        if (drawable != null) {
          imageView.setImageDrawable(drawable)
          return true
        }
      }

      false
    } catch (e: Exception) {
      false
    }
  }
}

/** Enhanced ImageView src completion */
object ImageViewSrcCompletions {

  val COMMON_IMAGE_SOURCES =
      listOf(
          // Common Android drawables
          "@android:drawable/ic_menu_add",
          "@android:drawable/ic_menu_delete",
          "@android:drawable/ic_menu_edit",
          "@android:drawable/ic_menu_info_details",
          "@android:drawable/ic_menu_search",
          "@android:drawable/ic_menu_share",
          "@android:drawable/ic_dialog_alert",
          "@android:drawable/ic_dialog_info",
          "@android:drawable/star_on",
          "@android:drawable/star_off",
          "@android:drawable/btn_star",

          // Resource references
          "@drawable/",
          "@mipmap/",
      )

  fun getSrcSuggestions(currentText: String): List<String> {
    return when {
      currentText.startsWith("@drawable/") -> listOf("@drawable/")
      currentText.startsWith("@mipmap/") -> listOf("@mipmap/")
      currentText.startsWith("@android:drawable/") ->
          COMMON_IMAGE_SOURCES.filter {
            it.startsWith("@android:drawable/") &&
                it.contains(currentText.substringAfter("@android:drawable/"), ignoreCase = true)
          }
      else -> COMMON_IMAGE_SOURCES.filter { it.contains(currentText, ignoreCase = true) }.take(10)
    }
  }
}
