package dev.mutwakil.androidide.activities

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import dev.mutwakil.androidide.R
import dev.mutwakil.androidide.utils.DecompilerInputPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.java.decompiler.main.decompiler.ConsoleDecompiler
import java.io.File
import java.io.IOException

/** Offline JVM bytecode inspection. Input is parsed as data and is never executed. */
class DecompilerActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var results: TextView
    private var running = false
    private var generatedSources: List<File> = emptyList()
    private var outputRoot: File? = null
    private var exportTarget: File? = null

    private val createExport = registerForActivityResult(ActivityResultContracts.CreateDocument("text/x-java")) { uri ->
        val source = exportTarget
        exportTarget = null
        if (uri != null && source != null) {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    contentResolver.openOutputStream(uri)?.use { output ->
                        source.inputStream().use { it.copyTo(output) }
                    } ?: throw IOException("Cannot open export destination")
                    withContext(Dispatchers.Main) {
                        status.text = getString(R.string.decompiler_export_success, source.name)
                    }
                } catch (error: Exception) {
                    withContext(Dispatchers.Main) {
                        status.text = getString(R.string.decompiler_export_failed, error.message ?: "")
                    }
                }
            }
        }
    }

    private val pickInput = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !running) decompile(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_decompiler)
        title = getString(R.string.decompiler_title)
        status = findViewById(R.id.decompiler_status)
        results = findViewById(R.id.decompiler_results)
        findViewById<Button>(R.id.decompiler_choose).setOnClickListener {
            pickInput.launch(arrayOf("*/*"))
        }
        results.setOnClickListener { showGeneratedSources() }
    }

    private fun decompile(uri: Uri) {
        running = true
        status.text = getString(R.string.decompiler_running)
        results.text = ""
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val name = queryDisplayName(uri) ?: "input.class"
                    if (!DecompilerInputPolicy.isSupportedFileName(name)) {
                        throw IOException(getString(R.string.decompiler_unsupported_type))
                    }
                    val input = File(cacheDir, "decompiler-" + System.nanoTime() + "-" + safeName(name))
                    copyBounded(uri, input)
                    val output = File(filesDir, "decompiled/" + System.currentTimeMillis()).apply {
                        if (!mkdirs() && !isDirectory) throw IOException("Cannot create output directory")
                    }
                    try {
                        ConsoleDecompiler.main(arrayOf(input.absolutePath, output.absolutePath))
                    } finally {
                        input.delete()
                    }
                    val sources = output.walkTopDown()
                        .filter { it.isFile && it.extension.equals("java", ignoreCase = true) }
                        .take(DecompilerInputPolicy.MAX_LISTED_FILES + 1)
                        .toList()
                    if (sources.isEmpty()) {
                        output.deleteRecursively()
                        throw IOException(getString(R.string.decompiler_no_sources))
                    }
                    output to sources
                }
                outputRoot = result.first
                generatedSources = result.second
                val listed = generatedSources.take(DecompilerInputPolicy.MAX_LISTED_FILES)
                status.text = getString(R.string.decompiler_success, listed.size)
                results.text = getString(R.string.decompiler_results_hint, result.first.absolutePath) +
                    "\n\n" + listed.joinToString("\n") { it.relativeTo(result.first).invariantSeparatorsPath } +
                    if (generatedSources.size > listed.size) "\n…" else ""
            } catch (error: Exception) {
                status.text = getString(R.string.decompiler_failed, error.message ?: error.javaClass.simpleName)
            } finally {
                running = false
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return cursor.getString(index)
            }
        }
        return uri.lastPathSegment
    }

    private fun copyBounded(uri: Uri, destination: File) {
        val input = contentResolver.openInputStream(uri) ?: throw IOException("Cannot open selected file")
        input.use { source ->
            destination.outputStream().use { target ->
                val buffer = ByteArray(8192)
                var total = 0L
                while (true) {
                    val count = source.read(buffer)
                    if (count < 0) break
                    total += count
                    if (!DecompilerInputPolicy.isWithinSizeLimit(total)) {
                        destination.delete()
                        throw IOException(getString(R.string.decompiler_input_too_large))
                    }
                    target.write(buffer, 0, count)
                }
            }
        }
    }

    private fun safeName(name: String): String =
        name.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(100)
            .ifBlank { "input.class" }

    private fun showGeneratedSources() {
        if (generatedSources.isEmpty()) return
        val root = outputRoot ?: return
        val visible = generatedSources.take(DecompilerInputPolicy.MAX_LISTED_FILES)
        AlertDialog.Builder(this)
            .setTitle(R.string.decompiler_sources)
            .setItems(visible.map { it.relativeTo(root).invariantSeparatorsPath }.toTypedArray()) { _, index ->
                showSource(visible[index])
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showSource(file: File) {
        val content = try {
            file.readText(Charsets.UTF_8)
        } catch (error: Exception) {
            getString(R.string.decompiler_read_failed, error.message ?: "")
        }
        val text = TextView(this).apply {
            setPadding(24, 16, 24, 16)
            textIsSelectable = true
            text = content
        }
        val scroll = android.widget.ScrollView(this).apply { addView(text) }
        AlertDialog.Builder(this)
            .setTitle(file.name)
            .setView(scroll)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.decompiler_export) { _, _ ->
                exportTarget = file
                createExport.launch(file.name)
            }
            .show()
    }
}
