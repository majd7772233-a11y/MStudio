package dev.mutwakil.androidide.tooling.impl.sync

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

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
}
