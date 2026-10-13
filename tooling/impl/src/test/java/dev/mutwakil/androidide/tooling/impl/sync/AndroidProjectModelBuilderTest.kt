package dev.mutwakil.androidide.tooling.impl.sync

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import com.android.builder.model.v2.ide.SyncIssue as AndroidSyncIssue
import com.android.builder.model.v2.ide.ArtifactDependencies as AgpArtifactDependencies
import com.android.builder.model.v2.ide.GraphItem as AgpGraphItem
import com.android.builder.model.v2.ide.UnresolvedDependency as AgpUnresolvedDependency
import dev.mutwakil.androidide.tooling.impl.serial.asProtoModel
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.nio.file.Files

@RunWith(JUnit4::class)
class AndroidProjectModelBuilderTest {

  @Test
  fun preservesAnExplicitValidVariant() {
    assertThat(
      resolveConfiguredAndroidVariant(
        requestedVariant = "freeMinApiDebug",
        variantNames = listOf("freeMinApiDebug", "paidRelease"),
        projectPath = ":mobile",
      ),
    ).isEqualTo("freeMinApiDebug")
  }

  @Test
  fun rejectsAnExplicitVariantThatNoLongerExists() {
    val error = runCatching {
      resolveConfiguredAndroidVariant(
        requestedVariant = "debug",
        variantNames = listOf("freeDebug", "paidDebug"),
        projectPath = ":mobile",
      )
    }.exceptionOrNull()

    assertThat(error).isInstanceOf(ModelBuilderException::class.java)
    assertThat(error).hasMessageThat().contains("requested selection was not replaced")
    assertThat(error).hasMessageThat().contains("freeDebug, paidDebug")
  }

  @Test
  fun usesFirstAvailableVariantOnlyWhenNoSelectionWasProvided() {
    assertThat(
      resolveConfiguredAndroidVariant(
        requestedVariant = null,
        variantNames = listOf("freeDebug", "paidDebug"),
        projectPath = ":mobile",
      ),
    ).isEqualTo("freeDebug")
  }

  @Test
  fun reportsAnActionableErrorWhenAgpReportsNoVariants() {
    val error = runCatching {
      resolveConfiguredAndroidVariant(
        requestedVariant = null,
        variantNames = emptyList(),
        projectPath = ":mobile",
      )
    }.exceptionOrNull()

    assertThat(error).isInstanceOf(ModelBuilderException::class.java)
    assertThat(error).hasMessageThat().contains("No build variants were reported")
    assertThat(error).hasMessageThat().contains(":mobile")
  }
  @Test
  fun findsNestedClassesJarInsideTheExactFlavoredVariantDirectory() {
    val buildDirectory = Files.createTempDirectory("mstudio-classes-jar-").toFile()
    try {
      val selectedJar = File(
        buildDirectory,
        "intermediates/compile_library_classes_jar/freeMinApiDebug/output/classes.jar",
      )
      selectedJar.parentFile.mkdirs()
      selectedJar.writeBytes(byteArrayOf(1))
      val otherVariantJar = File(
        buildDirectory,
        "intermediates/compile_library_classes_jar/paidDebug/classes.jar",
      )
      otherVariantJar.parentFile.mkdirs()
      otherVariantJar.writeBytes(byteArrayOf(2))

      assertThat(findClassesJarForVariant(buildDirectory, "freeMinApiDebug"))
        .isEqualTo(selectedJar)
    } finally {
      buildDirectory.deleteRecursively()
    }
  }

  @Test
  fun doesNotBorrowAnotherVariantsJarWhenSelectedVariantHasNotBeenBuilt() {
    val buildDirectory = Files.createTempDirectory("mstudio-classes-jar-").toFile()
    try {
      val otherVariantJar = File(
        buildDirectory,
        "intermediates/compile_library_classes_jar/paidDebug/classes.jar",
      )
      otherVariantJar.parentFile.mkdirs()
      otherVariantJar.writeBytes(byteArrayOf(2))

      assertThat(findClassesJarForVariant(buildDirectory, "freeDebug"))
        .isEqualTo(
          File(
            buildDirectory,
            "intermediates/compile_library_classes_jar/freeDebug/classes.jar",
          ),
        )
    } finally {
      buildDirectory.deleteRecursively()
    }
  }

  @Test
  fun mapsOnlyUniqueModuleNamesToProjectPaths() {
    assertThat(
      uniqueModuleNameToPath(
        listOf(
          "app" to ":app",
          "core" to ":core",
          "core" to ":tools:core",
        ),
      ),
    ).containsExactly("app", ":app")
  }

  @Test
  fun preservesActionableSyncIssueDetailsInTheProjectModel() {
    val issue = mockk<AndroidSyncIssue>()
    every { issue.data } returns "dependency"
    every { issue.message } returns "Could not resolve dependency"
    every { issue.multiLineMessage } returns listOf("Check the repository URL", null)
    every { issue.type } returns 77
    every { issue.severity } returns AndroidSyncIssue.SEVERITY_ERROR

    val mapped = issue.toProjectSyncIssue()

    assertThat(mapped.message).isEqualTo("Could not resolve dependency")
    assertThat(mapped.data).isEqualTo("dependency")
    assertThat(mapped.multilineMessageList).containsExactly("Check the repository URL")
    assertThat(mapped.type).isEqualTo(77)
    assertThat(mapped.severity).isEqualTo(
      dev.mutwakil.androidide.project.GradleModels.SyncIssueSeverity.SeverityError,
    )
  }

  @Test
  fun preservesResolvedAndUnresolvedCompileDependencies() {
    val graphItem = mockk<AgpGraphItem>()
    every { graphItem.key } returns "external-lib"
    every { graphItem.requestedCoordinates } returns "org.example:library:1.2.3"
    every { graphItem.dependencies } returns emptyList()

    val unresolved = mockk<AgpUnresolvedDependency>()
    every { unresolved.name } returns "org.example:missing:1.0"
    every { unresolved.cause } returns "Artifact not found"

    val artifact = mockk<AgpArtifactDependencies>()
    every { artifact.compileDependencies } returns listOf(graphItem)
    every { artifact.unresolvedDependencies } returns listOf(unresolved)

    val mapped = artifact.asProtoModel()

    assertThat(mapped.compileDependencyList).hasSize(1)
    assertThat(mapped.compileDependencyList.single().key).isEqualTo("external-lib")
    assertThat(mapped.compileDependencyList.single().requestedCoordinates)
      .isEqualTo("org.example:library:1.2.3")
    assertThat(mapped.unresolvedDependencyList).hasSize(1)
    assertThat(mapped.unresolvedDependencyList.single().name).isEqualTo("org.example:missing:1.0")
    assertThat(mapped.unresolvedDependencyList.single().cause).isEqualTo("Artifact not found")
  }

}
