package dev.mutwakil.androidide.projects

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.nio.file.Files

class ProjectSwitchStateTest {

  @Test
  fun treatsEquivalentCanonicalPathsAsTheSameProject() {
    val root = Files.createTempDirectory("mstudio-project-switch-").toFile()
    try {
      assertThat(projectPathsDiffer(root.absolutePath, root.resolve(".").path)).isFalse()
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun detectsDifferentProjectsAndMissingCurrentState() {
    val first = Files.createTempDirectory("mstudio-project-a-").toFile()
    val second = Files.createTempDirectory("mstudio-project-b-").toFile()
    try {
      assertThat(projectPathsDiffer(first.absolutePath, second.absolutePath)).isTrue()
      assertThat(projectPathsDiffer(null, second.absolutePath)).isFalse()
      assertThat(projectPathsDiffer("", second.absolutePath)).isFalse()
    } finally {
      first.deleteRecursively()
      second.deleteRecursively()
    }
  }
}
