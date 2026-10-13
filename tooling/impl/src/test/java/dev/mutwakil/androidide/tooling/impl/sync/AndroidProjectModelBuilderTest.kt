package dev.mutwakil.androidide.tooling.impl.sync

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
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

}
