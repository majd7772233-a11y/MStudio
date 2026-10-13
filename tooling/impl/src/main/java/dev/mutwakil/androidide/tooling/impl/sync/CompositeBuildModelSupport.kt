package dev.mutwakil.androidide.tooling.impl.sync

import dev.mutwakil.androidide.project.GradleModels
import dev.mutwakil.androidide.tooling.api.messages.AndroidInitializationParams
import dev.mutwakil.androidide.tooling.api.messages.InitializeProjectParams
import org.gradle.tooling.model.gradle.GradleBuild as ToolingGradleBuild

/**
 * Returns each reachable included build once. Composite build references form a graph, not
 * necessarily a tree, so canonical root directories are used to avoid cycles and duplicates.
 */
internal fun collectIncludedBuilds(rootBuild: ToolingGradleBuild): List<ToolingGradleBuild> {
  val visitedRoots = hashSetOf<String>()
  runCatching { rootBuild.rootProject.projectDirectory.canonicalPath }.getOrNull()
    ?.let(visitedRoots::add)

  val result = mutableListOf<ToolingGradleBuild>()

  fun visit(build: ToolingGradleBuild) {
    build.includedBuilds.forEach { includedBuild ->
      val rootPath =
        runCatching { includedBuild.rootProject.projectDirectory.canonicalPath }.getOrNull()
          ?: return@forEach
      if (!visitedRoots.add(rootPath)) return@forEach

      result += includedBuild
      visit(includedBuild)
    }
  }

  visit(rootBuild)
  return result
}

/**
 * Included builds use their own Gradle project-path namespace. Prefixing the included build name
 * prevents a project such as ":core" from colliding with the root build's ":core".
 */
internal fun compositeBuildProjectPath(
  includedBuildName: String,
  projectPath: String,
): String {
  val namespace = includedBuildName.trim().trim(':').ifBlank { "included" }
  val childPath = projectPath.trim().removePrefix(":").trimEnd(':')
  return if (childPath.isBlank()) ":$namespace" else ":$namespace:$childPath"
}

/**
 * Rewrites only this included build's saved variant selections to the local project paths
 * expected by AGP's model. Root-build selections are deliberately excluded because paths such as
 * ":app" may exist in more than one build.
 */
internal fun initializeParamsForIncludedBuild(
  params: InitializeProjectParams,
  includedBuildName: String,
): InitializeProjectParams {
  val namespace = compositeBuildProjectPath(includedBuildName, ":")
  val localSelections =
    params.androidParams.variantSelections.mapNotNull { (path, variant) ->
      when {
        path == namespace -> ":" to variant
        path.startsWith("$namespace:") -> path.removePrefix(namespace) to variant
        else -> null
      }
    }.toMap()

  return InitializeProjectParams(
    directory = params.directory,
    gradleDistribution = params.gradleDistribution,
    androidParams = AndroidInitializationParams(localSelections),
    needsGradleSync = params.needsGradleSync,
    gradleArgs = params.gradleArgs,
    jvmArgs = params.jvmArgs,
  )
}

/**
 * Gives included-build projects unique IDE paths while retaining real filesystem paths and
 * translating project/task selectors into Gradle's composite-build task-path namespace.
 */
internal fun namespaceIncludedBuildProject(
  project: GradleModels.GradleProject,
  includedBuildName: String,
): GradleModels.GradleProject {
  val builder =
    project.toBuilder()
      .setPath(compositeBuildProjectPath(includedBuildName, project.path))
      .clearTask()
      .addAllTask(
        project.taskList.map { task ->
          task.toBuilder()
            .setPath(compositeBuildProjectPath(includedBuildName, task.path))
            .setProjectPath(compositeBuildProjectPath(includedBuildName, task.projectPath))
            .build()
        },
      )

  if (project.hasAndroidProject()) {
    val androidProject = project.androidProject
    if (androidProject.hasVariantDependencies()) {
      val dependencies = androidProject.variantDependencies
      val mappedLibraries =
        dependencies.librariesMap.mapValues { (_, library) ->
          val projectInfo = library.projectInfo
          if (
            library.type == dev.mutwakil.androidide.project.AndroidModels.LibraryType.Project &&
            projectInfo != null &&
            projectInfo.buildId == ":"
          ) {
            library.toBuilder()
              .setProjectInfo(
                projectInfo.toBuilder()
                  .setProjectPath(
                    compositeBuildProjectPath(includedBuildName, projectInfo.projectPath),
                  ).build(),
              ).build()
          } else {
            library
          }
        }
      builder.setAndroidProject(
        androidProject.toBuilder()
          .setVariantDependencies(
            dependencies.toBuilder().clearLibraries().putAllLibraries(mappedLibraries).build(),
          ).build(),
      )
    }
  }

  if (project.hasJavaProject()) {
    val javaProject = project.javaProject
    builder.setJavaProject(
      javaProject.toBuilder()
        .clearDependency()
        .addAllDependency(
          javaProject.dependencyList.map { dependency ->
            if (dependency.hasModule() && dependency.module.projectPath.isNotBlank()) {
              dependency.toBuilder()
                .setModule(
                  dependency.module.toBuilder()
                    .setProjectPath(
                      compositeBuildProjectPath(includedBuildName, dependency.module.projectPath),
                    ).build(),
                ).build()
            } else {
              dependency
            }
          },
        ).build(),
    )
  }

  return builder.build()
}
