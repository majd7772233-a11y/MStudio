/*
 * This file is part of AndroidIDE.
 *
 * AndroidIDE is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package dev.mutwakil.androidide.uidesigner.utils

import android.widget.TextView
import dev.mutwakil.androidide.inflater.internal.LayoutFile
import dev.mutwakil.androidide.inflater.internal.NamespaceImpl
import dev.mutwakil.androidide.inflater.internal.ViewImpl
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TemporaryFolder
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ViewToXmlRoundTripTest {

  @get:Rule
  val temporaryFolder = TemporaryFolder()

  @Test
  fun ordinaryViewLayoutCanBeSerialized() {
    val file = layout(
        """
        <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android">
            <TextView android:layout_width="wrap_content" android:layout_height="wrap_content" />
        </LinearLayout>
        """.trimIndent()
    )

    assertNull(ViewToXml.findUnsupportedRoundTripConstruct(file))
  }

  @Test
  fun defaultNamespaceUsesValidXmlDeclarationName() {
    val file = temporaryFolder.newFile("namespace.xml")
    val view =
        ViewImpl(
            LayoutFile(file, "namespace"),
            "TextView",
            TextView(RuntimeEnvironment.getApplication()),
        )
    view.namespaces["urn:default"] = NamespaceImpl("", "urn:default")

    val xml = ViewToXml.generateXml(view)

    assertTrue(xml.contains("xmlns=\"urn:default\""))
    assertFalse(xml.contains("xmlns:="))
  }

  @Test
  fun includeNodesRemainRoundTrippable() {
    val file =
        layout(
            """
            <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android">
                <include layout="@layout/toolbar" android:layout_width="match_parent" android:layout_height="wrap_content" />
            </LinearLayout>
            """.trimIndent()
        )

    assertNull(ViewToXml.findUnsupportedRoundTripConstruct(file))
  }

  @Test
  fun mergeAndOtherXmlOnlyNodesAreRejectedForEditedLayouts() {
    listOf(
          "<merge><TextView /></merge>" to "merge",
        "<LinearLayout><requestFocus /></LinearLayout>" to "requestFocus",
        "<LinearLayout><tag android:id=\"@+id/key\" android:value=\"value\" /></LinearLayout>" to "tag",
        "<LinearLayout><view class=\"example.CustomView\" /></LinearLayout>" to "view",
        "<LinearLayout><fragment android:name=\"example.Screen\" /></LinearLayout>" to "fragment",
    ).forEach { (xml, expectedTag) ->
      assertEquals(expectedTag, ViewToXml.findUnsupportedRoundTripConstruct(layout(xml)))
    }
  }

  private fun layout(contents: String): File {
    return temporaryFolder.newFile("layout-${System.nanoTime()}.xml").apply { writeText(contents) }
  }
}
