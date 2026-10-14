/*
 * MStudio Cloud Build UI.
 * Copyright (C) MStudio contributors.
 * Licensed under the GNU General Public License v3.0 or later.
 */
package dev.mutwakil.androidide.activities

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import dev.mutwakil.androidide.cloudbuild.CloudArtifact
import dev.mutwakil.androidide.cloudbuild.CloudBuildPolicy
import dev.mutwakil.androidide.cloudbuild.CloudRun
import dev.mutwakil.androidide.cloudbuild.GitHubCloudBuildClient
import dev.mutwakil.androidide.cloudbuild.GitHubTokenStore
import dev.mutwakil.androidide.resources.R.string
import dev.mutwakil.androidide.utils.ApkInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CloudBuildActivity : AppCompatActivity() {
    private lateinit var tokenStore: GitHubTokenStore
    private lateinit var tokenInput: EditText
    private lateinit var repositoryInput: EditText
    private lateinit var branchInput: EditText
    private lateinit var taskInput: EditText
    private lateinit var javaInput: EditText
    private lateinit var status: TextView
    private lateinit var preview: TextView
    private lateinit var approveWorkflowButton: MaterialButton
    private lateinit var dispatchButton: MaterialButton
    private lateinit var refreshButton: MaterialButton
    private lateinit var logsButton: MaterialButton
    private lateinit var artifactsButton: MaterialButton
    private lateinit var openRunButton: MaterialButton
    private var api: GitHubCloudBuildClient? = null
    private var defaultBranch: String? = null
    private var workflowInstalled = false
    private var workflowExists = false
    private var busy = false
    private var currentRun: CloudRun? = null
    private var previewText = ""
    private var pendingLogs = ""
    private var availableArtifacts: List<CloudArtifact> = emptyList()
    private var downloadedApks = emptyList<java.io.File>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tokenStore = GitHubTokenStore(this)
        setContentView(buildView())
        status.text = getString(string.cloud_build_status_disconnected)
        updateControls()
    }

    private fun buildView(): android.view.View {
        val scroll = ScrollView(this)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(24))
        }
        scroll.addView(body)
        body.addView(TextView(this).apply {
            text = getString(string.cloud_build_title)
            textSize = 26f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        body.addView(label(getString(string.cloud_build_subtitle)))
        body.addView(label(getString(string.cloud_build_private_warning)))
        body.addView(label(getString(string.cloud_build_token_help)))
        tokenInput = edit(getString(string.cloud_build_token_hint), password = true)
        body.addView(tokenInput)
        body.addView(button(string.cloud_build_connect) { connect() })
        body.addView(button(string.cloud_build_disconnect) {
            tokenStore.clear()
            api = null
            workflowInstalled = false
            currentRun = null
            status.text = getString(string.cloud_build_status_disconnected)
            updateControls()
        })
        body.addView(section("Repository"))
        repositoryInput = edit(getString(string.cloud_build_repository_hint))
        branchInput = edit(getString(string.cloud_build_branch_hint))
        taskInput = edit(getString(string.cloud_build_task_hint))
        javaInput = edit(getString(string.cloud_build_java_hint), number = true)
        body.addView(repositoryInput)
        body.addView(branchInput)
        body.addView(taskInput)
        body.addView(javaInput)
        body.addView(button(string.cloud_build_inspect) { inspect() })
        approveWorkflowButton = button(string.cloud_build_install_workflow) { approveWorkflow() }
        body.addView(approveWorkflowButton)
        body.addView(section(getString(string.cloud_build_preview)))
        preview = TextView(this).apply {
            isTextSelectable = true
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        body.addView(MaterialCardView(this).apply {
            radius = dp(12).toFloat()
            cardElevation = dp(2).toFloat()
            addView(preview)
        })
        dispatchButton = button(string.cloud_build_dispatch) { dispatchBuild() }
        refreshButton = button(string.cloud_build_refresh) { refreshRun() }
        logsButton = button(string.cloud_build_logs) { showLogs() }
        artifactsButton = button(string.cloud_build_artifacts) { chooseArtifact() }
        openRunButton = button(string.cloud_build_open_run) { openRun() }
        body.addView(dispatchButton)
        body.addView(refreshButton)
        body.addView(logsButton)
        body.addView(artifactsButton)
        body.addView(openRunButton)
        status = TextView(this).apply {
            textSize = 14f
            isTextSelectable = true
            setPadding(dp(12), dp(14), dp(12), dp(14))
        }
        body.addView(status)
        return scroll
    }

    private fun connect() {
        val entered = tokenInput.text.toString().trim()
        val token = entered.ifBlank { tokenStore.read().orEmpty() }
        if (token.isBlank()) {
            status.text = getString(string.cloud_build_token_required)
            return
        }
        runAction({
            val candidate = GitHubCloudBuildClient(token)
            val login = candidate.validateToken()
            if (entered.isNotBlank()) tokenStore.save(token)
            api = candidate
            getString(string.cloud_build_connected_as, login)
        }) {
            tokenInput.text?.clear()
        }
    }

    private fun inspect() {
        val client = requireApi() ?: return
        val repo = repositoryInput.text.toString().trim()
        val requestedBranch = branchInput.text.toString().trim()
        val task = taskInput.text.toString().trim()
        val javaVersion = javaInput.text.toString().trim()
        if (!repo.contains('/')) {
            status.text = getString(string.cloud_build_error, "Enter owner/repository.")
            return
        }
        runAction({
            val metadata = client.repository(repo)
            defaultBranch = metadata.defaultBranch
            val sourceBranch = requestedBranch.ifBlank { metadata.defaultBranch }
            if (requestedBranch.isBlank()) withContext(Dispatchers.Main) { branchInput.setText(sourceBranch) }
            val commit = client.branch(repo, sourceBranch)
            val paths = client.inspectTree(repo, commit.sha)
            val detected = CloudBuildPolicy.detectTask(paths)
                ?: throw IllegalStateException(getString(string.cloud_build_no_task))
            val chosenTask = task.ifBlank { detected }
            if (!CloudBuildPolicy.isSafeGradleTask(chosenTask)) {
                throw IllegalArgumentException(getString(string.cloud_build_task_invalid))
            }
            if (javaVersion !in setOf("17", "21")) throw IllegalArgumentException("JDK version must be 17 or 21.")
            withContext(Dispatchers.Main) { if (task.isBlank()) taskInput.setText(detected) }
            workflowExists = client.workflowExists(repo, metadata.defaultBranch)
            workflowInstalled = false
            previewText = buildString {
                append("Repository: ").append(metadata.fullName).append('\n')
                append("Visibility: ").append(if (metadata.isPrivate) "Private" else "Public").append('\n')
                append("Source ref: ").append(sourceBranch).append('\n')
                append("Workflow destination: default branch ").append(metadata.defaultBranch).append('\n')
                append("Gradle task: ").append(chosenTask).append('\n')
                append("JDK: ").append(javaVersion).append("\n\n")
                append(CloudBuildPolicy.workflow(chosenTask, javaVersion))
                if (workflowExists) append("\nWARNING: workflow path already exists; it will not be overwritten.")
            }
            if (workflowExists) getString(string.cloud_build_workflow_exists)
            else getString(string.cloud_build_workflow_ready)
        }) {
            preview.text = previewText
            updateControls()
        }
    }

    private fun approveWorkflow() {
        val client = requireApi() ?: return
        val repo = repositoryInput.text.toString().trim()
        val destination = defaultBranch
        val task = taskInput.text.toString().trim()
        val javaVersion = javaInput.text.toString().trim()
        if (destination.isNullOrBlank() || workflowExists ||
            !CloudBuildPolicy.isSafeGradleTask(task) || javaVersion !in setOf("17", "21")) {
            status.text = getString(string.cloud_build_not_ready)
            return
        }
        AlertDialog.Builder(this)
            .setTitle(getString(string.cloud_build_install_workflow))
            .setMessage(
                "This writes a new workflow file to '$destination'. It runs the selected repository's " +
                    "Gradle scripts on GitHub-hosted infrastructure. The personal token is not passed to the build job. " +
                    "Continue only after reviewing the full preview."
            )
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                runAction({
                    client.createWorkflow(repo, destination, task, javaVersion)
                    workflowInstalled = true
                    getString(string.cloud_build_workflow_added)
                }) { updateControls() }
            }.show()
    }

    private fun dispatchBuild() {
        val client = requireApi() ?: return
        val repo = repositoryInput.text.toString().trim()
        val branch = branchInput.text.toString().trim().ifBlank { defaultBranch.orEmpty() }
        if (!workflowInstalled || branch.isBlank()) {
            status.text = getString(string.cloud_build_not_ready)
            return
        }
        runAction({
            val before = client.latestRunId(repo, branch)
            val run = client.dispatch(repo, branch, before)
            currentRun = run
            getString(string.cloud_build_build_started, run.id.toInt()) + "\n" + run.url
        }) { updateControls() }
    }

    private fun refreshRun() {
        val client = requireApi() ?: return
        val repo = repositoryInput.text.toString().trim()
        val branch = branchInput.text.toString().trim().ifBlank { defaultBranch.orEmpty() }
        runAction({
            val run = currentRun ?: client.findLatestRun(repo, branch)
                ?: throw IllegalStateException(getString(string.cloud_build_no_run))
            currentRun = run
            val jobs = client.jobs(repo, run.id)
            buildString {
                append(getString(string.cloud_build_run_summary, run.id.toInt(), run.status, run.conclusion ?: "in progress"))
                append("\nBranch: ").append(run.branch).append("\nCreated: ").append(run.createdAt)
                jobs.forEach { job ->
                    append("\n\n").append(job.name).append(": ").append(job.status)
                    job.conclusion?.let { append(" (").append(it).append(')') }
                    job.steps.forEach { append("\n  • ").append(it) }
                }
                append("\n\n").append(run.url)
            }
        }) { updateControls() }
    }

    private fun showLogs() {
        val client = requireApi() ?: return
        val run = currentRun ?: return
        val repo = repositoryInput.text.toString().trim()
        runAction({
            pendingLogs = client.downloadRunLogs(repo, run.id)
            "Run logs downloaded."
        }) {
            val text = TextView(this).apply {
                this.text = pendingLogs
                isTextSelectable = true
                typeface = android.graphics.Typeface.MONOSPACE
                textSize = 11f
                setPadding(dp(12), dp(12), dp(12), dp(12))
            }
            AlertDialog.Builder(this).setTitle("Run #${run.id} logs")
                .setView(ScrollView(this).apply { addView(text) })
                .setPositiveButton(android.R.string.ok, null).show()
        }
    }

    private fun chooseArtifact() {
        val client = requireApi() ?: return
        val run = currentRun ?: return
        val repo = repositoryInput.text.toString().trim()
        runAction({
            availableArtifacts = client.artifacts(repo, run.id).filterNot { it.expired }
            if (availableArtifacts.isEmpty()) getString(string.cloud_build_no_artifacts)
            else "Choose an artifact to download."
        }) {
            if (availableArtifacts.isEmpty()) return@runAction
            AlertDialog.Builder(this).setTitle(getString(string.cloud_build_artifact_select))
                .setItems(availableArtifacts.map { "${it.name} — ${it.sizeBytes / 1024} KB" }.toTypedArray()) { _, which ->
                    downloadArtifact(client, repo, run, availableArtifacts[which])
                }.setNegativeButton(android.R.string.cancel, null).show()
        }
    }

    private fun downloadArtifact(client: GitHubCloudBuildClient, repo: String, run: CloudRun, artifact: CloudArtifact) {
        runAction({
            downloadedApks = client.extractApks(client.downloadArtifactZip(repo, artifact.id), run.id, cacheDir)
            "Downloaded ${downloadedApks.size} APK file(s)."
        }) {
            AlertDialog.Builder(this).setTitle(getString(string.cloud_build_apk_select))
                .setItems(downloadedApks.map { it.name }.toTypedArray()) { _, which ->
                    val file = downloadedApks[which]
                    lifecycleScope.launch {
                        val result = withContext(Dispatchers.IO) { ApkInstaller.installApk(this@CloudBuildActivity, file) }
                        status.text = getString(string.cloud_build_downloaded_apk, file.absolutePath) +
                            if (result) "\nInstallation flow opened." else "\nUnable to start installation."
                    }
                }.setNegativeButton(android.R.string.cancel, null).show()
        }
    }

    private fun openRun() {
        currentRun?.url?.takeIf { it.startsWith("https://github.com/") }?.let {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it)))
        }
    }

    private fun requireApi(): GitHubCloudBuildClient? {
        api?.let { return it }
        val token = tokenStore.read()
        if (token.isNullOrBlank()) {
            status.text = getString(string.cloud_build_token_required)
            return null
        }
        return GitHubCloudBuildClient(token).also { api = it }
    }

    private fun runAction(action: suspend () -> String, onSuccess: () -> Unit = {}) {
        if (busy) return
        busy = true
        updateControls()
        status.text = getString(string.cloud_build_running)
        lifecycleScope.launch {
            try {
                val message = withContext(Dispatchers.IO) { action() }
                status.text = message
                onSuccess()
            } catch (error: Exception) {
                status.text = getString(string.cloud_build_error, error.message ?: error.javaClass.simpleName)
            } finally {
                busy = false
                updateControls()
            }
        }
    }

    private fun updateControls() {
        val enabled = !busy
        if (::approveWorkflowButton.isInitialized) approveWorkflowButton.isEnabled = enabled && defaultBranch != null && !workflowExists && !workflowInstalled
        if (::dispatchButton.isInitialized) dispatchButton.isEnabled = enabled && workflowInstalled
        if (::refreshButton.isInitialized) refreshButton.isEnabled = enabled && currentRun != null
        if (::logsButton.isInitialized) logsButton.isEnabled = enabled && currentRun != null
        if (::artifactsButton.isInitialized) artifactsButton.isEnabled = enabled && currentRun != null
        if (::openRunButton.isInitialized) openRunButton.isEnabled = enabled && currentRun?.url?.isNotBlank() == true
    }

    private fun label(textValue: String) = TextView(this).apply {
        text = textValue
        textSize = 14f
        setPadding(0, dp(8), 0, dp(8))
    }

    private fun section(textValue: String) = TextView(this).apply {
        text = textValue
        textSize = 18f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(18), 0, dp(6))
    }

    private fun edit(hintValue: String, password: Boolean = false, number: Boolean = false) =
        EditText(this).apply {
            hint = hintValue
            textSize = 15f
            inputType = when {
                password -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                number -> InputType.TYPE_CLASS_NUMBER
                else -> InputType.TYPE_CLASS_TEXT
            }
            setSingleLine(true)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4); bottomMargin = dp(4) }
        }

    private fun button(title: Int, action: () -> Unit) = MaterialButton(this).apply {
        text = getString(title)
        isAllCaps = false
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4); bottomMargin = dp(4) }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
