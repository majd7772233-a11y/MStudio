/*
 * MStudio Cloud Build GitHub REST client.
 * Copyright (C) MStudio contributors.
 * Licensed under the GNU General Public License v3.0 or later.
 */
package dev.mutwakil.androidide.cloudbuild

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.zip.ZipInputStream

internal data class CloudRepository(
    val fullName: String,
    val defaultBranch: String,
    val isPrivate: Boolean
)

internal data class CloudBranch(val name: String, val sha: String)
internal data class CloudRun(
    val id: Long,
    val name: String,
    val status: String,
    val conclusion: String?,
    val branch: String,
    val url: String,
    val createdAt: String
)
internal data class CloudJob(
    val name: String,
    val status: String,
    val conclusion: String?,
    val steps: List<String>
)
internal data class CloudArtifact(
    val id: Long,
    val name: String,
    val sizeBytes: Long,
    val expired: Boolean
)

internal class CloudApiException(val statusCode: Int, message: String) : IOException(message)

internal class GitHubCloudBuildClient(private val token: String) {
    private val apiBase = "https://api.github.com"
    private val apiVersion = "2022-11-28"

    fun validateToken(): String {
        val user = requestJson("GET", "/user")
        return user.optString("login").takeIf { it.isNotBlank() }
            ?: throw IOException("GitHub did not return an account name.")
    }

    fun repository(fullName: String): CloudRepository {
        val parts = parseRepository(fullName)
        val json = requestJson("GET", "/repos/${parts.first}/${parts.second}")
        val branch = json.optString("default_branch")
        if (branch.isBlank()) throw IOException("The repository has no default branch.")
        return CloudRepository(json.optString("full_name", fullName), branch, json.optBoolean("private"))
    }

    fun branch(fullName: String, branch: String): CloudBranch {
        val p = parseRepository(fullName)
        val encoded = encodePath(branch)
        val json = requestJson("GET", "/repos/${p.first}/${p.second}/branches/$encoded")
        val sha = json.optJSONObject("commit")?.optString("sha").orEmpty()
        if (sha.isBlank()) throw IOException("GitHub returned no commit for branch '$branch'.")
        return CloudBranch(json.optString("name", branch), sha)
    }

    fun inspectTree(fullName: String, commitSha: String): Set<String> {
        val p = parseRepository(fullName)
        val json = requestJson("GET", "/repos/${p.first}/${p.second}/git/trees/$commitSha?recursive=1")
        if (json.optBoolean("truncated")) {
            throw IOException("This repository tree is too large to inspect safely. No workflow was changed.")
        }
        val tree = json.optJSONArray("tree") ?: JSONArray()
        return buildSet {
            for (i in 0 until tree.length()) {
                val item = tree.optJSONObject(i) ?: continue
                if (item.optString("type") == "blob") add(item.optString("path"))
            }
        }
    }

    fun workflowExists(fullName: String, defaultBranch: String): Boolean {
        val p = parseRepository(fullName)
        val path = encodePath(CloudBuildPolicy.WORKFLOW_PATH)
        val ref = encodeQuery(defaultBranch)
        return try {
            requestJson("GET", "/repos/${p.first}/${p.second}/contents/$path?ref=$ref")
            true
        } catch (e: CloudApiException) {
            if (e.statusCode == 404) false else throw e
        }
    }

    fun createWorkflow(fullName: String, defaultBranch: String, task: String, javaVersion: String) {
        if (workflowExists(fullName, defaultBranch)) {
            throw IOException(
                "The MStudio Cloud Build workflow already exists on the default branch. " +
                    "It was not overwritten; review or reuse it manually."
            )
        }
        val p = parseRepository(fullName)
        val body = JSONObject()
            .put("message", "ci: add approved MStudio cloud build workflow")
            .put("branch", defaultBranch)
            .put("content", android.util.Base64.encodeToString(
                CloudBuildPolicy.workflow(task, javaVersion).toByteArray(StandardCharsets.UTF_8),
                android.util.Base64.NO_WRAP
            ))
        requestJson("PUT", "/repos/${p.first}/${p.second}/contents/${encodePath(CloudBuildPolicy.WORKFLOW_PATH)}", body)
    }

    fun latestRunId(fullName: String, branch: String): Long {
        val p = parseRepository(fullName)
        val json = requestJson(
            "GET",
            "/repos/${p.first}/${p.second}/actions/workflows/${CloudBuildPolicy.WORKFLOW_ID}/runs" +
                "?branch=${encodeQuery(branch)}&event=workflow_dispatch&per_page=1"
        )
        return json.optJSONArray("workflow_runs")?.optJSONObject(0)?.optLong("id") ?: 0L
    }

    fun dispatch(fullName: String, branch: String, previousRunId: Long): CloudRun {
        val p = parseRepository(fullName)
        requestJson(
            "POST",
            "/repos/${p.first}/${p.second}/actions/workflows/${CloudBuildPolicy.WORKFLOW_ID}/dispatches",
            JSONObject().put("ref", branch)
        )
        repeat(10) {
            Thread.sleep(1500)
            val json = requestJson(
                "GET",
                "/repos/${p.first}/${p.second}/actions/workflows/${CloudBuildPolicy.WORKFLOW_ID}/runs" +
                    "?branch=${encodeQuery(branch)}&event=workflow_dispatch&per_page=10"
            )
            val runs = json.optJSONArray("workflow_runs") ?: JSONArray()
            for (i in 0 until runs.length()) {
                val item = runs.optJSONObject(i) ?: continue
                val id = item.optLong("id")
                if (id > previousRunId && item.optString("head_branch") == branch) return parseRun(item)
            }
        }
        throw IOException(
            "GitHub accepted the dispatch but did not expose a matching run yet. " +
                "Use Refresh to check again; the build was not dispatched twice."
        )
    }

    fun findLatestRun(fullName: String, branch: String): CloudRun? {
        val p = parseRepository(fullName)
        val json = requestJson(
            "GET",
            "/repos/${p.first}/${p.second}/actions/workflows/${CloudBuildPolicy.WORKFLOW_ID}/runs" +
                "?branch=${encodeQuery(branch)}&event=workflow_dispatch&per_page=1"
        )
        return json.optJSONArray("workflow_runs")?.optJSONObject(0)?.let(::parseRun)
    }

    fun jobs(fullName: String, runId: Long): List<CloudJob> {
        val p = parseRepository(fullName)
        val json = requestJson("GET", "/repos/${p.first}/${p.second}/actions/runs/$runId/jobs?per_page=100")
        val array = json.optJSONArray("jobs") ?: JSONArray()
        return buildList {
            for (i in 0 until array.length()) {
                val job = array.optJSONObject(i) ?: continue
                val stepsJson = job.optJSONArray("steps") ?: JSONArray()
                val steps = buildList {
                    for (j in 0 until stepsJson.length()) {
                        val step = stepsJson.optJSONObject(j) ?: continue
                        add("${step.optString("name")}: ${step.optString("status")}" +
                            step.optString("conclusion").takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty())
                    }
                }
                add(CloudJob(
                    job.optString("name"),
                    job.optString("status"),
                    job.optString("conclusion").takeIf { it.isNotBlank() },
                    steps
                ))
            }
        }
    }

    fun artifacts(fullName: String, runId: Long): List<CloudArtifact> {
        val p = parseRepository(fullName)
        val json = requestJson("GET", "/repos/${p.first}/${p.second}/actions/runs/$runId/artifacts?per_page=100")
        val array = json.optJSONArray("artifacts") ?: JSONArray()
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                add(CloudArtifact(
                    item.optLong("id"),
                    item.optString("name"),
                    item.optLong("size_in_bytes"),
                    item.optBoolean("expired")
                ))
            }
        }
    }

    fun downloadArtifactZip(fullName: String, artifactId: Long): ByteArray {
        val p = parseRepository(fullName)
        return getRedirectedBytes(
            "$apiBase/repos/${p.first}/${p.second}/actions/artifacts/$artifactId/zip",
            authenticated = true,
            maxBytes = 700L * 1024 * 1024
        )
    }

    fun downloadRunLogs(fullName: String, runId: Long): String {
        val p = parseRepository(fullName)
        val bytes = getRedirectedBytes(
            "$apiBase/repos/${p.first}/${p.second}/actions/runs/$runId/logs",
            authenticated = true,
            maxBytes = 20L * 1024 * 1024
        )
        val output = StringBuilder()
        ZipInputStream(bytes.inputStream()).use { zip ->
            var entryCount = 0
            while (true) {
                val entry = zip.nextEntry ?: break
                if (++entryCount > 300) throw IOException("The run log archive contains too many files.")
                if (!entry.isDirectory) {
                    val name = entry.name.replace('\\', '/')
                    if (name.startsWith("/") || name.split('/').any { it == ".." }) {
                        throw IOException("Unsafe path found in run logs.")
                    }
                    val buffer = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val count = zip.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > 2 * 1024 * 1024) break
                        if (output.length() < 120_000) {
                            output.append(String(buffer, 0, count, StandardCharsets.UTF_8))
                        }
                    }
                }
                zip.closeEntry()
                if (output.length() >= 120_000) break
            }
        }
        return output.toString().takeLast(30_000).ifBlank { "No readable text was returned in the log archive." }
    }

    fun extractApks(artifactZip: ByteArray, runId: Long, cacheDir: java.io.File): List<java.io.File> {
        val destination = java.io.File(cacheDir, "cloud-build/$runId")
        if (!destination.exists() && !destination.mkdirs()) throw IOException("Cannot create APK cache directory.")
        val result = mutableListOf<java.io.File>()
        var total = 0L
        var entries = 0
        ZipInputStream(artifactZip.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (++entries > 1000) throw IOException("The artifact contains too many files.")
                val normalized = entry.name.replace('\\', '/')
                if (normalized.startsWith("/") || normalized.split('/').any { it == ".." }) {
                    throw IOException("Unsafe path found in downloaded artifact.")
                }
                if (!entry.isDirectory && normalized.endsWith(".apk", ignoreCase = true)) {
                    val name = normalized.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_")
                    if (name.isBlank()) throw IOException("Invalid APK filename.")
                    val target = java.io.File(destination, name)
                    target.outputStream().use { out ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > 600L * 1024 * 1024) throw IOException("Extracted APK data exceeds the safety limit.")
                            out.write(buffer, 0, count)
                        }
                    }
                    if (target.length() == 0L) {
                        target.delete()
                        throw IOException("GitHub returned an empty APK.")
                    }
                    result.add(target)
                }
                zip.closeEntry()
            }
        }
        if (result.isEmpty()) throw IOException("This run's artifact contains no APK file.")
        return result
    }

    private fun requestJson(method: String, path: String, body: JSONObject? = null): JSONObject {
        val connection = open("$apiBase$path", authenticated = true, method = method, body = body)
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
        connection.disconnect()
        if (code !in 200..299) {
            val message = runCatching { JSONObject(text).optString("message") }.getOrNull().orEmpty()
            throw CloudApiException(code, "GitHub API returned HTTP $code: ${message.ifBlank { "request failed" }}")
        }
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    }

    private fun getRedirectedBytes(url: String, authenticated: Boolean, maxBytes: Long): ByteArray {
        var current = URL(url)
        var attachToken = authenticated
        repeat(4) {
            val connection = open(current.toString(), attachToken, "GET", null)
            val code = connection.responseCode
            if (code in 300..399) {
                val location = connection.getHeaderField("Location")
                    ?: throw IOException("GitHub returned a redirect without a download URL.")
                connection.disconnect()
                current = URL(current, location)
                attachToken = current.host.equals("api.github.com", ignoreCase = true)
                return@repeat
            }
            if (code !in 200..299) {
                val text = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                connection.disconnect()
                throw CloudApiException(code, "Download failed with HTTP $code: ${text.take(300)}")
            }
            val output = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > maxBytes) throw IOException("Downloaded data exceeds the safety limit.")
                    output.write(buffer, 0, count)
                }
            }
            connection.disconnect()
            return output.toByteArray()
        }
        throw IOException("Too many redirects while downloading from GitHub.")
    }

    private fun open(url: String, authenticated: Boolean, method: String, body: JSONObject?): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 20_000
        connection.readTimeout = 45_000
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("X-GitHub-Api-Version", apiVersion)
        connection.setRequestProperty("User-Agent", "MStudio-Cloud-Build")
        if (authenticated) connection.setRequestProperty("Authorization", "Bearer $token")
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.outputStream.use { it.write(body.toString().toByteArray(StandardCharsets.UTF_8)) }
        }
        return connection
    }

    private fun parseRun(item: JSONObject) = CloudRun(
        item.optLong("id"),
        item.optString("name", "MStudio Cloud Build"),
        item.optString("status", "unknown"),
        item.optString("conclusion").takeIf { it.isNotBlank() },
        item.optString("head_branch"),
        item.optString("html_url"),
        item.optString("created_at")
    )

    private fun parseRepository(fullName: String): Pair<String, String> {
        val parts = fullName.trim().split('/')
        if (parts.size != 2 || parts.any { !it.matches(Regex("[A-Za-z0-9_.-]+")) }) {
            throw IOException("Enter the repository as owner/repository.")
        }
        return parts[0] to parts[1]
    }

    private fun encodePath(value: String): String =
        value.split('/').joinToString("/") { encodeQuery(it) }

    private fun encodeQuery(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
