package dev.mutwakil.androidide.lsp.java.providers

import com.google.common.truth.Truth.assertThat
import dev.mutwakil.androidide.lsp.models.DiagnosticResult
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.nio.file.Paths
import java.time.Instant

@RunWith(JUnit4::class)
class PerFileDiagnosticCacheTest {

  @Test
  fun diagnosticsAreNeverReusedAcrossDifferentFiles() {
    val cache = PerFileDiagnosticCache()
    val firstFile = Paths.get("/workspace/app/src/First.java")
    val secondFile = Paths.get("/workspace/app/src/Second.java")
    val analyzedAt = Instant.parse("2026-10-13T12:00:00Z")
    val firstResult = DiagnosticResult(firstFile, emptyList())

    cache.store(firstFile, firstResult, analyzedAt)

    assertThat(
      cache.getFresh(firstFile, Instant.parse("2026-10-13T11:59:00Z")),
    ).isEqualTo(firstResult)
    assertThat(
      cache.getFresh(secondFile, Instant.parse("2026-10-13T11:59:00Z")),
    ).isNull()
    assertThat(cache.getOrNoUpdate(secondFile)).isEqualTo(DiagnosticResult.NO_UPDATE)
  }

  @Test
  fun clearingAFileRemovesItsResultAndFreshnessTimestamp() {
    val cache = PerFileDiagnosticCache()
    val file = Paths.get("/workspace/app/src/Main.java")
    val analyzedAt = Instant.parse("2026-10-13T12:00:00Z")
    cache.store(file, DiagnosticResult(file, emptyList()), analyzedAt)

    cache.clear(file)

    assertThat(cache.getFresh(file, Instant.parse("2026-10-13T11:59:00Z"))).isNull()
    assertThat(cache.getOrNoUpdate(file)).isEqualTo(DiagnosticResult.NO_UPDATE)
  }
}
