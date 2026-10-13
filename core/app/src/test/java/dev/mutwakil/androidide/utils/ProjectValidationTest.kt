/*
 * This file is part of AndroidIDE.
 *
 * AndroidIDE is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package dev.mutwakil.androidide.utils

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ProjectValidationTest {
    private lateinit var tempRoot: File

    @Before
    fun setUp() {
        tempRoot = Files.createTempDirectory("mstudio-project-validation-").toFile()
    }

    @After
    fun tearDown() {
        tempRoot.deleteRecursively()
    }

    @Test
    fun acceptsRootGradleProjectWithoutAppModule() {
        val project = directory("root-java-project")
        File(project, "build.gradle").writeText("plugins { id 'java' }")

        assertThat(isValidProjectDirectory(project)).isTrue()
    }

    @Test
    fun acceptsKotlinDslAndCustomApplicationModuleNames() {
        val project = directory("custom-module")
        File(project, "settings.gradle.kts").writeText("include(\":mobile\")")
        File(directory("custom-module/mobile"), "build.gradle.kts")
            .writeText("plugins { id(\"com.android.application\") }")

        assertThat(isValidProjectDirectory(project)).isTrue()
    }

    @Test
    fun acceptsLibraryOnlyMultiModuleProject() {
        val project = directory("libraries-only")
        File(project, "settings.gradle").writeText("include ':core'")
        File(directory("libraries-only/core"), "build.gradle")
            .writeText("plugins { id 'com.android.library' }")

        assertThat(isValidProjectDirectory(project)).isTrue()
    }

    @Test
    fun rejectsEmptyAndIncompleteDirectories() {
        val empty = directory("empty")
        val incomplete = directory("incomplete")
        File(incomplete, "settings.gradle.kts").writeText("include(\":app\")")
        File(directory("incomplete/app"), "src").mkdirs()

        assertThat(isValidProjectDirectory(empty)).isFalse()
        assertThat(isValidProjectDirectory(incomplete)).isFalse()
    }

    @Test
    fun rejectsBuildScriptDirectoryInsteadOfRegularFile() {
        val project = directory("invalid-script")
        File(project, "settings.gradle").writeText("include ':app'")
        File(project, "build.gradle").mkdirs()

        assertThat(isValidProjectDirectory(project)).isFalse()
    }

    @Test
    fun findsNestedProjectsWithoutTreatingContainersAsProjectRoots() {
        val container = directory("workspace")
        val nestedProject = directory("workspace/group with spaces/مشروع")
        File(nestedProject, "settings.gradle.kts").writeText("rootProject.name = \"مشروع\"")
        File(nestedProject, "build.gradle.kts").writeText("plugins { id(\"java\") }")

        assertThat(isValidProjectOrContainerDirectory(container)).isTrue()
        assertThat(findValidProjectsInContainer(container)).containsExactly(nestedProject)
        assertThat(isValidProjectDirectory(container)).isFalse()
    }

    @Test
    fun doesNotDiscoverProjectsInsideHiddenOrGeneratedDirectories() {
        val container = directory("workspace")
        val hiddenProject = directory("workspace/.hidden")
        File(hiddenProject, "build.gradle").writeText("plugins { id 'java' }")
        val generatedProject = directory("workspace/build/generated")
        File(generatedProject, "build.gradle").writeText("plugins { id 'java' }")

        assertThat(findValidProjectsInContainer(container)).isEmpty()
    }

    @Test
    fun preservesSpecialPluginProjectDetection() {
        val project = directory("plugin")
        File(directory("plugin/libs"), "plugin-api.jar").writeBytes(byteArrayOf(1))
        File(project, "build.gradle.kts").writeText("")

        assertThat(isPluginProject(project)).isTrue()
        assertThat(isValidProjectDirectory(project)).isTrue()
    }

    private fun directory(relativePath: String): File =
        File(tempRoot, relativePath).apply { mkdirs() }
}
