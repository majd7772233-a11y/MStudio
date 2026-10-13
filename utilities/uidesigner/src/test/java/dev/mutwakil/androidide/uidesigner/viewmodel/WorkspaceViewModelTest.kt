/*
 * This file is part of AndroidIDE.
 *
 * AndroidIDE is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package dev.mutwakil.androidide.uidesigner.viewmodel

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WorkspaceViewModelTest {

  @Test
  fun changingTheOpenedFileResetsDirtyState() {
    val viewModel = WorkspaceViewModel()
    viewModel.file = File("first.xml")
    viewModel.markWorkspaceChanged()

    assertTrue(viewModel.hasWorkspaceChanges)

    viewModel.file = File("second.xml")

    assertFalse(viewModel.hasWorkspaceChanges)
  }

  @Test
  fun loadingAnUneditedLayoutDoesNotMarkItDirty() {
    val viewModel = WorkspaceViewModel()
    viewModel.file = File("layout.xml")

    assertFalse(viewModel.hasWorkspaceChanges)
  }
}
