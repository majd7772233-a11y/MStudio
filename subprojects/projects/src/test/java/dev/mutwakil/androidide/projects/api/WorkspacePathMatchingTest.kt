package dev.mutwakil.androidide.projects.api

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.nio.file.Paths

@RunWith(JUnit4::class)
class WorkspacePathMatchingTest {

  @Test
  fun acceptsAFileInsideItsModuleDirectory() {
    assertThat(
      isPathWithinDirectory(
        Paths.get("/workspace/app/src/Main.java"),
        Paths.get("/workspace/app"),
      ),
    ).isTrue()
  }

  @Test
  fun rejectsSiblingDirectoriesWithMatchingStringPrefixes() {
    assertThat(
      isPathWithinDirectory(
        Paths.get("/workspace/app-old/src/Main.java"),
        Paths.get("/workspace/app"),
      ),
    ).isFalse()
  }

  @Test
  fun acceptsTheModuleDirectoryItself() {
    assertThat(
      isPathWithinDirectory(
        Paths.get("/workspace/app"),
        Paths.get("/workspace/app"),
      ),
    ).isTrue()
  }
}
