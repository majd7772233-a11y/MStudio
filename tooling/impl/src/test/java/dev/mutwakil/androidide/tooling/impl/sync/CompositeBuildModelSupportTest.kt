package dev.mutwakil.androidide.tooling.impl.sync

import com.google.common.truth.Truth.assertThat
import dev.mutwakil.androidide.project.AndroidModels
import dev.mutwakil.androidide.project.GradleModels
import dev.mutwakil.androidide.project.JavaModels
import dev.mutwakil.androidide.tooling.api.messages.AndroidInitializationParams
import dev.mutwakil.androidide.tooling.api.messages.InitializeProjectParams
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class CompositeBuildModelSupportTest {

  @Test
  fun includedBuildProjectPathsDoNotCollideWithRootBuildPaths() {
    assertThat(compositeBuildProjectPath("shared", ":"))
      .isEqualTo(":shared")
    assertThat(compositeBuildProjectPath("shared", ":core:utils"))
      .isEqualTo(":shared:core:utils")
  }

  @Test
  fun namespacesIncludedBuildTasksAndJavaModuleDependencies() {
    val dependency =
      JavaModels.JavaDependency.newBuilder()
        .setModule(
          JavaModels.JavaModuleDependency.newBuilder()
            .setModuleName("lib")
            .setProjectPath(":lib")
            .build(),
        ).build()
    val project =
      GradleModels.GradleProject.newBuilder()
        .setName("core")
        .setPath(":core")
        .setProjectDirPath("/workspace/shared/core")
        .setBuildDirPath("/workspace/shared/core/build")
        .setBuildScriptPath("/workspace/shared/core/build.gradle.kts")
        .addTask(
          GradleModels.GradleTask.newBuilder()
            .setName("compileJava")
            .setPath(":core:compileJava")
            .setProjectPath(":core")
            .build(),
        )
        .setJavaProject(JavaModels.JavaProject.newBuilder().addDependency(dependency).build())
        .build()

    val mapped = namespaceIncludedBuildProject(project, "shared")

    assertThat(mapped.path).isEqualTo(":shared:core")
    assertThat(mapped.taskList.single().path).isEqualTo(":shared:core:compileJava")
    assertThat(mapped.taskList.single().projectPath).isEqualTo(":shared:core")
    assertThat(mapped.javaProject.dependencyList.single().module.projectPath)
      .isEqualTo(":shared:lib")
  }

  @Test
  fun mapsRootAndroidProjectDependenciesToIncludedBuildPaths() {
    val library =
      AndroidModels.Library.newBuilder()
        .setKey("included-library")
        .setType(AndroidModels.LibraryType.Project)
        .setProjectInfo(
          AndroidModels.ProjectInfo.newBuilder()
            .setBuildId(":shared")
            .setProjectPath(":lib")
            .build(),
        ).build()
    val androidProject =
      AndroidModels.AndroidProject.newBuilder()
        .setVariantDependencies(
          AndroidModels.VariantDependencies.newBuilder()
            .putLibraries("included-library", library)
            .build(),
        ).build()
    val project =
      GradleModels.GradleProject.newBuilder()
        .setName("app")
        .setPath(":app")
        .setAndroidProject(androidProject)
        .build()

    val mapped = namespaceRootBuildProjectDependencies(project, mapOf(":shared" to "shared"))

    assertThat(
      mapped.androidProject.variantDependencies.librariesMap["included-library"]!!
        .projectInfo.projectPath,
    ).isEqualTo(":shared:lib")
  }

  @Test
  fun includedBuildVariantSelectionsAreMappedWithoutRootBuildCollisions() {
    val params =
      InitializeProjectParams(
        directory = "/projects/root",
        androidParams =
          AndroidInitializationParams(
            mapOf(
              ":app" to "rootDebug",
              ":shared" to "includedRootRelease",
              ":shared:app" to "includedAppDebug",
            ),
          ),
      )

    val included = initializeParamsForIncludedBuild(params, "shared")

    assertThat(included.androidParams.variantSelections)
      .containsExactly(
        ":", "includedRootRelease",
        ":app", "includedAppDebug",
      )
    assertThat(params.androidParams.variantSelections[":app"]).isEqualTo("rootDebug")
  }
}
