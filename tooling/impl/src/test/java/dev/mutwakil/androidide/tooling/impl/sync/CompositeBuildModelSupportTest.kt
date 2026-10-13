package dev.mutwakil.androidide.tooling.impl.sync

import com.google.common.truth.Truth.assertThat
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
        ":" to "includedRootRelease",
        ":app" to "includedAppDebug",
      )
    assertThat(included.androidParams.variantSelections).doesNotContainKey(":appRoot")
    assertThat(params.androidParams.variantSelections[":app"]).isEqualTo("rootDebug")
  }
}
