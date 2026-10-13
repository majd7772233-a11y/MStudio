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

package dev.mutwakil.androidide.fragments

import android.content.Intent
import android.os.Environment
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.core.provider.DocumentsContractCompat
import androidx.core.provider.DocumentsContractCompat.buildDocumentUriUsingTree
import androidx.core.provider.DocumentsContractCompat.getTreeDocumentId
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.Fragment
import dev.mutwakil.androidide.common.R
import dev.mutwakil.androidide.resources.R.string
import dev.mutwakil.androidide.utils.flashError
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import java.io.File
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ClickableSpan
import androidx.annotation.StringRes

/**
 * Resolve a Storage Access Framework document ID to a real, app-accessible filesystem path.
 *
 * The external-storage provider uses "primary:relative/path" for emulated shared storage and
 * "<volume-uuid>:relative/path" for removable volumes. Only the primary volume or a currently
 * mounted removable volume reported by Android is accepted; arbitrary volume names are never
 * turned into filesystem paths.
 */
internal fun resolveExternalStorageDocument(documentId: String): File? {
  val separator = documentId.indexOf(':')
  if (separator <= 0 || separator == documentId.lastIndex) return null

  val volumeId = documentId.substring(0, separator)
  val relativePath = documentId.substring(separator + 1)
  if (relativePath.isBlank() || File(relativePath).isAbsolute) return null

  val root =
    if (volumeId.equals("primary", ignoreCase = true)) {
      runCatching { Environment.getExternalStorageDirectory().canonicalFile }.getOrNull()
    } else {
      val mountedVolume =
        runCatching {
          Environment.getStorageVolumes().firstOrNull { volume ->
            volume.isRemovable &&
              volume.uuid?.equals(volumeId, ignoreCase = true) == true &&
              volume.state == Environment.MEDIA_MOUNTED
          }
        }.getOrNull() ?: return null

      // Public removable storage is exposed as /storage/<volume UUID> on Android.
      // The UUID must match an actual mounted removable StorageVolume above.
      runCatching { File("/storage", mountedVolume.uuid!!).canonicalFile }.getOrNull()
    } ?: return null

  if (!root.isDirectory || !root.canRead()) return null
  val selected = runCatching { File(root, relativePath).canonicalFile }.getOrNull() ?: return null
  val rootPath = root.toPath()
  val selectedPath = selected.toPath()
  if (selectedPath != rootPath && !selectedPath.startsWith(rootPath)) return null
  return selected.takeIf { it.isDirectory && it.canRead() }
}

open class BaseFragment @JvmOverloads constructor(contentLayoutId: Int = 0) :
  Fragment(contentLayoutId) {

  private var callback: OnDirectoryPickedCallback? = null
  private val allowedAuthorities =
    setOf(ANDROID_DOCS_AUTHORITY, ANDROIDIDE_DOCS_AUTHORITY)

  protected val viewLifecycleScope = CoroutineScope(Dispatchers.Default + CoroutineName(javaClass.simpleName))

  companion object {
    const val ANDROID_DOCS_AUTHORITY = "com.android.externalstorage.documents"
    const val ANDROIDIDE_DOCS_AUTHORITY = "dev.mutwakil.androidide.documents"
  }

  override fun onDestroyView() {
    super.onDestroyView()
    viewLifecycleScope.cancel("${javaClass.simpleName} is being destroyed")
  }

  private val startForResult =
    registerForActivityResult(StartActivityForResult()) {
      val context = requireContext()
      val uri = it?.data?.data ?: return@registerForActivityResult
      val pickedDir = DocumentFile.fromTreeUri(context, uri)

      if (pickedDir == null) {
        flashError(string.err_invalid_data_by_intent)
        return@registerForActivityResult
      }

      if (!pickedDir.exists() || !pickedDir.isDirectory) {
        flashError(getString(string.msg_picked_isnt_dir))
        return@registerForActivityResult
      }

      val treeDocumentId = runCatching { getTreeDocumentId(uri) }.getOrNull()
      val docUri =
        treeDocumentId?.let { runCatching { buildDocumentUriUsingTree(uri, it) }.getOrNull() }
      val docId = docUri?.let { runCatching { DocumentsContractCompat.getDocumentId(it) }.getOrNull() }
      val authority = docUri?.authority

      if (docId.isNullOrBlank() || authority.isNullOrBlank()) {
        flashError(getString(string.err_invalid_data_by_intent))
        return@registerForActivityResult
      }

      if (!allowedAuthorities.contains(authority)) {
        flashError(getString(string.err_authority_not_allowed, authority))
        return@registerForActivityResult
      }

      val dir =
        if (authority == ANDROIDIDE_DOCS_AUTHORITY) {
          runCatching { File(docId).canonicalFile }.getOrNull()
        } else {
          resolveExternalStorageDocument(docId).also { resolved ->
            if (resolved == null) {
              flashError(getString(string.msg_select_from_primary_storage))
              return@registerForActivityResult
            }
          }
        }

      if (dir == null || !dir.exists() || !dir.isDirectory || !dir.canRead()) {
        flashError(getString(string.err_invalid_data_by_intent))
        return@registerForActivityResult
      }

      if (callback != null) {
        callback!!.onDirectoryPicked(dir)
      }
    }
    
  protected fun appendClickableSpan(
    sb: SpannableStringBuilder,
    @StringRes textRes: Int,
    span: ClickableSpan,
  ) {
    val str = getString(textRes)
    val split = str.split("@@", limit = 3)
    if (split.size != 3) {
      // Not a valid format
      sb.append(str)
      sb.append('\n')
      return
    }
    sb.append(split[0])
    sb.append(split[1], span, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    sb.append(split[2])
    sb.append('\n')
  }

  protected fun pickDirectory(dirCallback: OnDirectoryPickedCallback?) {
    this.callback = dirCallback
    try {
      this.startForResult.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE))
    } catch (e: Exception) {
      requireActivity().flashError(getString(R.string.msg_dir_picker_failed, e.message))
    }
  }

  fun interface OnDirectoryPickedCallback {
    fun onDirectoryPicked(file: File)
  }
}
