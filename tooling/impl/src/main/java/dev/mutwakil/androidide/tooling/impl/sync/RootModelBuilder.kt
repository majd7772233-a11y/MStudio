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

package dev.mutwakil.androidide.tooling.impl.sync

import com.android.builder.model.v2.ide.SyncIssue as AndroidSyncIssue
import dev.mutwakil.androidide.builder.model.shouldBeIgnored
import dev.mutwakil.androidide.project.GradleBuild
import dev.mutwakil.androidide.project.GradleModels
import dev.mutwakil.androidide.project.ProjectModelInfo
import dev.mutwakil.androidide.project.SyncIssue as ProjectSyncIssue
import dev.mutwakil.androidide.projects.models.projectDir
import dev.mutwakil.androidide.tooling.api.IAndroidProject
import dev.mutwakil.androidide.tooling.api.messages.InitializeProjectParams
import dev.mutwakil.androidide.tooling.api.sync.ProjectSyncHelper
import dev.mutwakil.androidide.tooling.impl.Main
import dev.mutwakil.androidide.tooling.impl.util.configureFrom
import dev.mutwakil.androidide.utils.sha256
import kotlinx.coroutines.runBlocking
import org.gradle.tooling.ConfigurableLauncher
import org.gradle.tooling.model.idea.IdeaProject
import org.gradle.tooling.model.gradle.GradleBuild as ToolingGradleBuild
import org.slf4j.LoggerFactory
import java.io.File
import java.io.Serializable

/** Converts an AGP sync issue to the cached project-model representation. */
internal fun AndroidSyncIssue.toProjectSyncIssue(): ProjectSyncIssue =
  ProjectSyncIssue(
    data = data,
    message = message,
    multilineMessageList = multiLineMessage?.filterNotNull() ?: emptyList(),
    type = type,
    severity =
      when (severity) {
        AndroidSyncIssue.SEVERITY_ERROR -> GradleModels.SyncIssueSeverity.SeverityError
        AndroidSyncIssue.SEVERITY_WARNING -> GradleModels.SyncIssueSeverity.SeverityWarning
        else -> throw IllegalArgumentException("Unknown sync issue severity: $severity")
      },
  )

/**
 * Maps IDEA module names to paths only when the name is unique. IDEA module dependencies expose
 * target module names rather than stable project paths, so duplicate names must not silently map to
 * whichever module happened to appear last.
 */
internal fun uniqueModuleNameToPath(moduleNamePaths: List<Pair<String, String>>): Map<String, String> =
  moduleNamePaths
    .groupBy({ it.first }, { it.second })
    .mapNotNull { (name, paths) ->
      if (paths.size == 1) name to paths.single() else null
    }
    .toMap()

/**
 * Utility class to build the project models.
 *
 * @author Akash Yadav
 */
object RootModelBuilder :
  AbstractModelBuilder<RootProjectModelBuilderParams, File>(), Serializable {
  private val serialVersionUID = 1L
  private val log = LoggerFactory.getLogger(RootModelBuilder::class.java)

  private const val PROJECT_SYNC_LOCK_TIMEOUT_MS = 10 * 1000L

  override fun build(
    initializeParams: InitializeProjectParams,
    param: RootProjectModelBuilderParams,
  ): File {
    val (projectConnection, cancellationToken, projectCacheFile, syncMetaFile) = param

    // do not reference the 'initializationParams' field in the
    val executor =
      projectConnection.action { controller ->
        val ideaProject = controller.getModelAndLog(IdeaProject::class.java)

        val ideaModules = ideaProject.modules
        val moduleNameGroups = ideaModules.groupBy { it.name }
        val modulePaths =
          uniqueModuleNameToPath(ideaModules.map { it.name to it.gradleProject.path })
        moduleNameGroups.filterValues { it.size > 1 }.keys.forEach { duplicateName ->
          log.warn(
            "IDEA module name '{}' is ambiguous. Dependencies targeting this name will not be assigned a guessed project path.",
            duplicateName,
          )
        }
        val rootModule =
          ideaModules.find { it.gradleProject.parent == null }
            ?: throw ModelBuilderException(
              "Unable to find root project",
            )

        val rootProjectVersions = getAndroidVersions(rootModule, controller)

        val syncIssues = hashSetOf<AndroidSyncIssue>()
        val syncIssueReporter =
          ISyncIssueReporter { syncIssue ->
            if (syncIssue.shouldBeIgnored()) {
              // this SyncIssue should not be shown to the user
              return@ISyncIssueReporter
            }
            syncIssues.add(syncIssue)
          }

        val rootGradleProject =
          GradleProjectModelBuilder
            .build(initializeParams, rootModule.gradleProject)
            .toBuilder()

        if (rootProjectVersions != null) {
          // Root project is an Android project
          checkAgpVersion(rootProjectVersions, syncIssueReporter)
          val androidProject =
            AndroidProjectModelBuilder
              .build(
                initializeParams,
                AndroidProjectModelBuilderParams(
                  controller,
                  rootModule,
                  rootProjectVersions,
                  syncIssueReporter,
                ),
              )

          rootGradleProject.setAndroidProject(androidProject)
        }

        val projects =
          ideaModules.map { ideaModule ->
            val gradleProject =
              GradleProjectModelBuilder
                .build(initializeParams, ideaModule.gradleProject)
                .toBuilder()

            val versions = getAndroidVersions(ideaModule, controller)
            if (versions != null) {
              checkAgpVersion(versions, syncIssueReporter)
              val androidProject =
                AndroidProjectModelBuilder
                  .build(
                    initializeParams,
                    AndroidProjectModelBuilderParams(
                      controller,
                      ideaModule,
                      versions,
                      syncIssueReporter,
                    ),
                  )

              gradleProject.setAndroidProject(androidProject)
            } else {
              val javaProject =
                JavaProjectModelBuilder
                  .build(
                    initializeParams,
                    JavaProjectModelBuilderParams(ideaProject, ideaModule, modulePaths),
                  )

              gradleProject.setJavaProject(javaProject)
            }

            gradleProject.build()
          }.toMutableList()

        // Included builds are separate Gradle builds, not children in the root project's
        // IdeaProject. Query Gradle's Tooling API build model and import every reachable included
        // build as a separately namespaced set of IDE modules.
        val toolingBuild = controller.getModel(ToolingGradleBuild::class.java)
        val includedBuilds = collectIncludedBuilds(toolingBuild)
        val includedBuildNamesById =
          includedBuilds.flatMap { includedBuild ->
            val directory = includedBuild.rootProject.projectDirectory
            val name = directory.name
            listOf(
              ":$name" to name,
              name to name,
              (runCatching { directory.canonicalPath }.getOrNull() ?: directory.absolutePath) to name,
            )
          }.toMap()

        if (rootGradleProject.build().hasAndroidProject()) {
          rootGradleProject.setAndroidProject(
            namespaceRootBuildProjectDependencies(
              rootGradleProject.build(),
              includedBuildNamesById,
            ).androidProject,
          )
        }
        projects.indices.forEach { index ->
          projects[index] =
            namespaceRootBuildProjectDependencies(projects[index], includedBuildNamesById)
        }

        includedBuilds.forEach { includedBuild ->
          val includedIdeaProject =
            controller.getModel(includedBuild.rootProject, IdeaProject::class.java)
          val includedModules = includedIdeaProject.modules
          // Gradle's composite task path uses the included-build name, which defaults to
          // the included directory name and may differ from settings.gradle's rootProject.name.
          val buildName = includedBuild.rootProject.projectDirectory.name
          val includedInitializeParams = initializeParamsForIncludedBuild(initializeParams, buildName)
          val includedModulePaths =
            uniqueModuleNameToPath(
              includedModules.map { module -> module.name to module.gradleProject.path },
            )

          includedModules.forEach { includedModule ->
            val includedGradleProject =
              GradleProjectModelBuilder
                .build(initializeParams, includedModule.gradleProject)
                .toBuilder()

            val versions = getAndroidVersions(includedModule, controller)
            if (versions != null) {
              checkAgpVersion(versions, syncIssueReporter)
              val androidProject =
                AndroidProjectModelBuilder
                  .build(
                    includedInitializeParams,
                    AndroidProjectModelBuilderParams(
                      controller,
                      includedModule,
                      versions,
                      syncIssueReporter,
                    ),
                  )
              includedGradleProject.setAndroidProject(androidProject)
            } else {
              val javaProject =
                JavaProjectModelBuilder
                  .build(
                    initializeParams,
                    JavaProjectModelBuilderParams(
                      includedIdeaProject,
                      includedModule,
                      includedModulePaths,
                    ),
                  )
              includedGradleProject.setJavaProject(javaProject)
            }

            projects += namespaceIncludedBuildProject(includedGradleProject.build(), buildName)
          }
        }

        val gradleBuild =
          GradleBuild(
            rootProject = rootGradleProject.build(),
            subProjectList = projects,
            syncIssueList = syncIssues.map { it.toProjectSyncIssue() },
          )

        // IF the IDE were running fully in a JVM environment, we would have
        // used protobuf-java instead of protobuf-javalite. Messages generated
        // by protobuf-java are java.io.Serializable, can cross the BuildExecutor
        // boundary, and we could have returned the GradleBuild model here.
        // But since we're using protobuf-javalite, the models are not serializable
        // and hence cannot cross the BuildExecutor boundary. As a result,
        // we write the model cache file here in the build executor itself.

        val projectDir = gradleBuild.rootProject.projectDir

        val success =
          ProjectSyncHelper.tryUseSyncLock(projectDir, PROJECT_SYNC_LOCK_TIMEOUT_MS) {
            ProjectSyncHelper.writeGradleBuildSync(
              gradleBuild = gradleBuild,
              targetFile = projectCacheFile,
            )

            runBlocking {
              syncMetaFile.outputStream().buffered().use { out ->
                ProjectSyncHelper
                  .createSyncMeta(
                    projectDir = projectDir,
                    includeChecksum = true,
                    projectModelInfo =
                      ProjectModelInfo(
                        projectCacheFile.absolutePath,
                        projectCacheFile.sha256(),
                      ),
                  ).writeTo(out)
                out.flush()
              }
            }
          }

        if (!success) {
          throw ModelBuilderException("Failed to acquire sync lock. Unable to write cache.")
        }

        return@action projectCacheFile
      }

    executor.configureFrom(initializeParams)
    applyAndroidModelBuilderProps(executor)

    if (cancellationToken != null) {
      executor.withCancellationToken(cancellationToken)
    }

    val logger = LoggerFactory.getLogger("RootModelBuilder")
    logger.warn("Starting build. See build output for more details...")

    if (Main.client != null) {
      Main.client.logOutput("Starting build...")
    }

    val cacheFile = executor.run()
    logger.debug("Build action executed")

    return cacheFile
  }

  private fun applyAndroidModelBuilderProps(launcher: ConfigurableLauncher<*>) {
    launcher.addProperty(IAndroidProject.PROPERTY_BUILD_MODEL_ONLY, true)
    launcher.addProperty(IAndroidProject.PROPERTY_INVOKED_FROM_IDE, true)
  }

  private fun ConfigurableLauncher<*>.addProperty(
    property: String,
    value: Any,
  ) {
    addArguments(String.format("-P%s=%s", property, value))
  }
}