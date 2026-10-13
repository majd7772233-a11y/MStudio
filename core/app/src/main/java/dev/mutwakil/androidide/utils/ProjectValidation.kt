package dev.mutwakil.androidide.utils

import java.io.File
import kotlin.collections.filter
import kotlin.collections.orEmpty

/** Checks if the file is a readable, visible directory. */
internal fun File.isProjectCandidateDir(): Boolean =
    isDirectory && canRead() && !name.startsWith(".") && !isHidden

/** Scans the given root directory for valid Android project subdirectories. */
internal fun findValidProjects(projectsRoot: File): List<File> {
    if (!projectsRoot.isProjectCandidateDir()) return emptyList()

    val subdirs = projectsRoot.listFiles()
        ?.filter { it.isProjectCandidateDir() }
        .orEmpty()
    if (subdirs.isEmpty()) return emptyList()

    return subdirs.filter { dir -> isValidProjectDirectory(dir) }
}

/**
 * Determines whether a selected directory is a Gradle project MStudio can hand to Gradle for
 * authoritative model/sync validation.
 *
 * Do not require an `app/` directory: valid projects may use another application-module name,
 * contain only Android/Java libraries, or use the root project as the only module. The settings
 * file is the project boundary for multi-module projects; a root build script also identifies a
 * single-module Gradle project. This is deliberately structural validation, not a replacement for
 * Gradle sync or a parser for arbitrary build scripts.
 */
fun isValidProjectDirectory(selectedDir: File): Boolean {
    if (!selectedDir.isProjectCandidateDir()) return false
    if (isPluginProject(selectedDir)) return true

    val hasRootBuildScript = selectedDir.hasGradleBuildScript()
    val hasSettings = selectedDir.hasGradleSettingsFile()

    if (hasRootBuildScript) return true
    if (!hasSettings) return false

    // A settings file without any module build script is commonly an incomplete checkout.
    // Inspect visible, readable descendants but skip generated/VCS directories.
    return containsModuleBuildScript(selectedDir, maxDepth = 4)
}

/**
 * Finds project roots inside a selected directory. A directory that is itself a project is treated
 * as one root and is not traversed, so nested source folders cannot be mistaken for separate projects.
 * Parent containers are searched to a bounded depth; hidden, generated, VCS and dependency folders
 * are skipped, and canonical paths prevent symlink loops or duplicate roots.
 */
internal fun findValidProjectsInContainer(
    selectedDir: File,
    maxDepth: Int = 4,
): List<File> {
    if (!selectedDir.isProjectCandidateDir() || maxDepth < 0) return emptyList()
    if (isValidProjectDirectory(selectedDir)) return listOf(selectedDir)

    val found = linkedMapOf<String, File>()
    val visited = hashSetOf<String>()
    val ignoredNames = setOf("build", ".gradle", ".git", "node_modules", ".idea", ".kotlin")

    fun visit(directory: File, depth: Int) {
        if (depth > maxDepth || !directory.isProjectCandidateDir()) return
        val canonicalPath = runCatching { directory.canonicalPath }.getOrNull() ?: return
        if (!visited.add(canonicalPath)) return

        val children = directory.listFiles()
            ?.filter { child ->
                child.isProjectCandidateDir() && child.name !in ignoredNames
            }
            ?.sortedBy { it.name }
            .orEmpty()

        for (child in children) {
            if (isValidProjectDirectory(child)) {
                val path = runCatching { child.canonicalPath }.getOrNull() ?: continue
                found.putIfAbsent(path, child)
            } else {
                visit(child, depth + 1)
            }
        }
    }

    visit(selectedDir, 0)
    return found.values.toList()
}

/**
 * Determines whether the selected directory is a valid Gradle project or a parent container that
 * contains at least one project within the bounded search depth.
 */
internal fun isValidProjectOrContainerDirectory(selectedDir: File): Boolean =
    findValidProjectsInContainer(selectedDir).isNotEmpty()

/** Checks whether the directory contains a specific plugin project structure. */
internal fun isPluginProject(dir: File): Boolean {
    val pluginApiJar = File(dir, "libs/plugin-api.jar")
    val buildGradle = File(dir, "build.gradle.kts")
    return pluginApiJar.isFile && buildGradle.isFile
}

private fun File.hasGradleBuildScript(): Boolean =
    listOf("build.gradle", "build.gradle.kts").any { File(this, it).isFile }

private fun File.hasGradleSettingsFile(): Boolean =
    listOf("settings.gradle", "settings.gradle.kts").any { File(this, it).isFile }

private fun containsModuleBuildScript(root: File, maxDepth: Int): Boolean {
    fun scan(directory: File, depth: Int): Boolean {
        if (depth > maxDepth || !directory.isDirectory || !directory.canRead()) return false
        if (directory.hasGradleBuildScript()) return true

        val children = directory.listFiles()
            ?.filter { child ->
                child.isDirectory && child.canRead() &&
                    !child.name.startsWith(".") &&
                    child.name !in setOf("build", ".gradle", ".git", "node_modules")
            }
            .orEmpty()

        return children.any { child -> scan(child, depth + 1) }
    }

    // The root itself was checked by the caller; start at child depth 1.
    return root.listFiles()
        ?.filter { child ->
            child.isDirectory && child.canRead() &&
                !child.name.startsWith(".") &&
                child.name !in setOf("build", ".gradle", ".git", "node_modules")
        }
        .orEmpty()
        .any { child -> scan(child, 1) }
}
