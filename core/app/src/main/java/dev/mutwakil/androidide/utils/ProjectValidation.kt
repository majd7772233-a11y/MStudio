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
 * Determines if the directory is either a valid Gradle project itself or a container that includes
 * one or more valid projects. Containers are not recursively treated as projects unless their own
 * Gradle boundary is present.
 */
internal fun isValidProjectOrContainerDirectory(selectedDir: File): Boolean {
    if (!selectedDir.isProjectCandidateDir()) return false
    if (isValidProjectDirectory(selectedDir)) return true

    val subDirs = selectedDir.listFiles()?.filter { it.isProjectCandidateDir() } ?: return false
    return subDirs.any { sub -> isValidProjectDirectory(sub) }
}

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
