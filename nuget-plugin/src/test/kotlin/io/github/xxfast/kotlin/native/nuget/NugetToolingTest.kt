package io.github.xxfast.kotlin.native.nuget

import java.io.File
import org.gradle.api.GradleException
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration

class NugetToolingTest {
  @Test
  fun `findExecutable finds an executable placed in a temp dir`(@TempDir dir: File) {
    val exe = File(dir, "dotnet")
    exe.writeText("#!/bin/sh\n")
    exe.setExecutable(true)

    val found: String? = findExecutable("dotnet", dir.absolutePath)

    assertEquals(exe.absolutePath, found)
  }

  @Test
  fun `findExecutable returns null when the name is absent from the search path`(@TempDir dir: File) {
    val found: String? = findExecutable("dotnet", dir.absolutePath)

    assertNull(found)
  }

  @Test
  fun `findExecutable returns null when searchPath is null`() {
    val found: String? = findExecutable("dotnet", null)

    assertNull(found)
  }

  // setExecutable(false) is a no-op on a file system with no execute permission, so on Windows the
  // file stays executable and findExecutable returns it. The skip is the platform, not a bug here.
  @Test
  @DisabledOnOs(OS.WINDOWS)
  fun `findExecutable skips a non-executable file of the right name`(@TempDir dir: File) {
    val file = File(dir, "dotnet")
    file.writeText("not executable")
    file.setExecutable(false)

    val found: String? = findExecutable("dotnet", dir.absolutePath)

    assertNull(found)
  }

  @Test
  fun `requireDotnet throws GradleException when not found`(@TempDir dir: File) {
    val exception: GradleException = assertFailsWith<GradleException> {
      requireDotnet("restore NuGet packages", dir.absolutePath)
    }

    assertTrue(exception.message?.contains("dot.net/download") == true)
    assertTrue(exception.message?.contains("restore NuGet packages") == true)
  }

  @Test
  fun `requireDotnet returns the path when found`(@TempDir dir: File) {
    val exe = File(dir, "dotnet")
    exe.writeText("#!/bin/sh\n")
    exe.setExecutable(true)

    val found: String = requireDotnet("restore NuGet packages", dir.absolutePath)

    assertEquals(exe.absolutePath, found)
  }

  private fun fakeDotnet(dir: File): File {
    val exe = File(dir, "dotnet")
    exe.writeText("#!/bin/sh\n")
    exe.setExecutable(true)
    return exe
  }

  @Test
  fun `a configured dotnet wins over the one on PATH`(@TempDir dir: File) {
    val configured: File = fakeDotnet(File(dir, "configured").also { it.mkdirs() })
    val onPath: File = File(dir, "path").also { it.mkdirs() }
    fakeDotnet(onPath)

    val found: String? =
      resolveDotnet(configured.absolutePath, "local.properties", onPath.absolutePath)

    assertEquals(configured.absolutePath, found)
  }

  @Test
  fun `a configured dotnet that does not exist fails naming the key, value and source`(
    @TempDir dir: File,
  ) {
    fakeDotnet(dir)
    val missing: String = File(dir, "nope/dotnet").absolutePath

    val failure: GradleException = assertFailsWith<GradleException> {
      resolveDotnet(missing, "local.properties (/repo/local.properties)", dir.absolutePath)
    }

    val message: String = failure.message.orEmpty()
    assertContains(message, "nuget.dotnet")
    assertContains(message, missing)
    assertContains(message, "local.properties (/repo/local.properties)")
  }

  @Test
  @DisabledOnOs(OS.WINDOWS)
  fun `a configured dotnet that is not executable fails instead of falling back to PATH`(
    @TempDir dir: File,
  ) {
    val onPath: File = File(dir, "path").also { it.mkdirs() }
    fakeDotnet(onPath)
    val file = File(dir, "dotnet")
    file.writeText("not executable")
    file.setExecutable(false)

    val failure: GradleException = assertFailsWith<GradleException> {
      resolveDotnet(file.absolutePath, "the Gradle property", onPath.absolutePath)
    }

    assertContains(failure.message.orEmpty(), "the Gradle property")
  }

  @Test
  fun `a relative configured dotnet fails`(@TempDir dir: File) {
    val failure: GradleException = assertFailsWith<GradleException> {
      resolveDotnet("bin/dotnet", "local.properties", dir.absolutePath)
    }

    assertContains(failure.message.orEmpty(), "absolute")
  }

  @Test
  fun `nothing configured falls back to PATH`(@TempDir dir: File) {
    val exe: File = fakeDotnet(dir)

    assertEquals(exe.absolutePath, resolveDotnet(null, null, dir.absolutePath))
  }

  @Test
  fun `requireDotnet uses the configured dotnet when PATH has none`(@TempDir dir: File) {
    val configured: File = fakeDotnet(File(dir, "configured").also { it.mkdirs() })
    val empty: File = File(dir, "empty").also { it.mkdirs() }

    val found: String =
      requireDotnet("restore NuGet packages", empty.absolutePath, configured.absolutePath, "x")

    assertEquals(configured.absolutePath, found)
  }

  // Real `dotnet restore` output (stdout; stderr is empty), captured 2026-10-03 against an
  // unreachable feed and a missing local source.
  private val unreachable: String = """
      Determining projects to restore...
    /tmp/r.csproj : error NU1301: Unable to load the service index for source https://127.0.0.1:9/v3/index.json.
    /tmp/r.csproj : error NU1301:   Connection refused (127.0.0.1:9)
      Failed to restore /tmp/r.csproj (in 5.82 sec).
  """.trimIndent()

  private val missingLocalSource: String = """
      Determining projects to restore...
    /tmp/r.csproj : error NU1301: The local source '/tmp/nope-dir' doesn't exist.
      Failed to restore /tmp/r.csproj (in 62 ms).
  """.trimIndent()

  @Test
  fun `an unreachable feed is a transient NU1301`() {
    assertEquals("NU1301", transientFeedCode(unreachable))
  }

  @Test
  fun `a missing local source is not transient`() {
    assertNull(transientFeedCode(missingLocalSource))
  }

  @Test
  fun `package and compatibility errors are not transient`() {
    assertNull(transientFeedCode("r.csproj : error NU1101: Unable to find package Acme."))
    assertNull(transientFeedCode("r.csproj : error NU1102: Unable to find package Acme (>= 9.0.1)"))
    assertNull(transientFeedCode("r.csproj : error NU1202: Package Acme 1.0.0 is not compatible"))
  }

  @Test
  fun `the retry policy retries a transient failure until the last attempt`() {
    assertEquals("NU1301", restoreRetryCode(1, unreachable, attempt = 1))
    assertEquals("NU1301", restoreRetryCode(1, unreachable, attempt = 2))
    assertNull(restoreRetryCode(1, unreachable, attempt = 3))
    assertNull(restoreRetryCode(0, unreachable, attempt = 1))
    assertNull(restoreRetryCode(1, "error NU1101: Unable to find package Acme.", attempt = 1))
  }

  @Test
  fun `a transient failure is retried and each retry is warned`() {
    val outcomes: ArrayDeque<ProcessOutcome> = ArrayDeque(
      listOf(
        ProcessOutcome(1, unreachable, ""),
        ProcessOutcome(1, unreachable, ""),
        ProcessOutcome(0, "", ""),
      )
    )
    val warnings: MutableList<String> = mutableListOf()
    val sleeps: MutableList<Duration> = mutableListOf()

    val result: ProcessOutcome =
      retryTransientFeedFailures(warn = { warnings += it }, sleep = { sleeps += it }) {
        outcomes.removeFirst()
      }

    assertEquals(0, result.exitCode)
    assertEquals(2, warnings.size)
    assertContains(warnings[0], "attempt 1 of 3")
    assertContains(warnings[1], "attempt 2 of 3")
    assertContains(warnings[0], "NU1301")
    assertEquals(listOf(RESTORE_BACKOFF, RESTORE_BACKOFF), sleeps)
  }

  @Test
  fun `a persistent transient failure stops after three attempts`() {
    var calls = 0

    val result: ProcessOutcome = retryTransientFeedFailures(warn = { }, sleep = { }) {
      calls++
      ProcessOutcome(1, unreachable, "")
    }

    assertEquals(1, result.exitCode)
    assertEquals(3, calls)
  }

  @Test
  fun `a non transient failure is not retried`() {
    var calls = 0

    val result: ProcessOutcome = retryTransientFeedFailures(warn = { }, sleep = { }) {
      calls++
      ProcessOutcome(1, "r.csproj : error NU1101: Unable to find package Acme.", "")
    }

    assertEquals(1, result.exitCode)
    assertEquals(1, calls)
  }
}
