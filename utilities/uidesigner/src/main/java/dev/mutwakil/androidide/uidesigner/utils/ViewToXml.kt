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

package dev.mutwakil.androidide.uidesigner.utils

import android.content.Context
import android.util.Xml
import com.blankj.utilcode.util.ThreadUtils
import dev.mutwakil.androidide.inflater.IView
import dev.mutwakil.androidide.inflater.IViewGroup
import dev.mutwakil.androidide.inflater.internal.ViewGroupImpl
import dev.mutwakil.androidide.inflater.internal.ViewImpl
import dev.mutwakil.androidide.lsp.xml.utils.XMLBuilder
import dev.mutwakil.androidide.tasks.executeAsyncProvideError
import dev.mutwakil.androidide.uidesigner.R
import dev.mutwakil.androidide.utils.DialogUtils
import java.util.concurrent.CompletableFuture
import java.io.File
import java.util.concurrent.CompletionException
import org.slf4j.LoggerFactory
import org.xmlpull.v1.XmlPullParser

/**
 * Generates XML code for [IView].
 *
 * @author Akash Yadav
 */
object ViewToXml {

  private val log = LoggerFactory.getLogger(ViewToXml::class.java)

  @JvmStatic
  @JvmOverloads
  fun generateXml(
      context: Context,
      workspace: ViewGroupImpl,
      onGenerated: (String) -> Unit = {},
      onFailure: (result: String?, error: Throwable?) -> Unit,
  ) {
    context.apply {
      val future: CompletableFuture<String?> =
          executeAsyncProvideError({
            if (workspace.childCount == 0) {
              throw CompletionException(
                  IllegalStateException("No views have been added to workspace")
              )
            }

            if (workspace.childCount > 1) {
              throw CompletionException(
                  IllegalStateException("Invalid view hierarchy. More than one root views found.")
              )
            }

            return@executeAsyncProvideError generateXml(workspace[0] as ViewImpl)
          }) { _, _ ->
          }

      val progress =
          DialogUtils.newProgressDialog(
                  this,
                  getString(R.string.title_generating_xml),
                  getString(R.string.please_wait),
                  false,
              ) { _, _ ->
                future.cancel(true)
              }
              .show()

      future.whenComplete { result, error ->
        ThreadUtils.runOnUiThread {
          progress.dismiss()

          if (result.isNullOrBlank() || error != null) {
            log.error("Unable to generate XML code", error)
            onFailure(result, error)
            return@runOnUiThread
          }

          onGenerated(result)
        }
      }
    }
  }

  /**
   * Returns the first XML construct that this visual view model cannot represent losslessly.
   *
   * These constructs must be left untouched until the designer can preserve their source nodes;
   * serializing the inflated view tree would otherwise silently remove their original semantics.
   */
  @JvmStatic
  fun findUnsupportedRoundTripConstruct(file: File): String? {
    val parser = Xml.newPullParser()
    parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
    file.inputStream().buffered().use { input ->
      parser.setInput(input, null)
      var event = parser.eventType
      while (event != XmlPullParser.END_DOCUMENT) {
        if (event == XmlPullParser.START_TAG) {
          val tag = parser.name
          val unsupported =
              tag.equals("merge", ignoreCase = true) ||
                  tag.equals("requestFocus", ignoreCase = true) ||
                  tag.equals("tag", ignoreCase = true) ||
                  tag.equals("fragment", ignoreCase = true) ||
                  (tag.equals("view", ignoreCase = true) &&
                      !parser.getAttributeValue(null, "class").isNullOrBlank())
          if (unsupported) return tag
        }
        event = parser.next()
      }
    }
    return null
  }

  @JvmStatic
  fun generateXml(view: ViewImpl): String {
    val builder = XMLBuilder("", System.lineSeparator())
    builder.apply {
      appendXmlProlog()
      linefeed()
      appendView(view)
    }
    return builder.toString()
  }

  private fun XMLBuilder.appendView(view: ViewImpl, indent: Int = 1) {
    startElement(view.tag, false)

    for (namespace in view.namespaceDecls) {
      linefeed()
      indent(indent)
      val declarationName =
          namespace.prefix.takeIf { it.isNotBlank() }?.let { "xmlns:$it" } ?: "xmlns"
      addSingleAttribute(declarationName, namespace.uri, true)
    }

    for (attr in view.attributes) {
      val namespace = attr.namespace
      if (namespace != null && namespace.uri.isNotBlank() && namespace.prefix.isBlank()) {
        throw IllegalStateException(
            "Cannot safely serialize namespaced attribute '${attr.name}' without a namespace prefix"
        )
      }

      linefeed()
      indent(indent)

      val ns = namespace?.prefix?.takeIf { it.isNotBlank() }?.let { "${it}:" } ?: ""
      addSingleAttribute("${ns}${attr.name}", attr.value, true)
    }

    if (view is IViewGroup) {

      closeStartElement()

      for (i in 0 until view.childCount) {
        linefeed()
        linefeed()
        indent(indent)
        appendView(view[i] as ViewImpl, indent + 1)
      }

      linefeed()
      linefeed()
      indent(indent - 1)
      endElement(view.tag)
    } else {
      selfCloseElement()
    }
  }

  private fun XMLBuilder.appendXmlProlog() {
    startPrologOrPI("xml")
    addSingleAttribute("version", "1.0", true)
    addSingleAttribute("encoding", "utf-8", true)
    endPrologOrPI()
  }
}
