package io.github.xxfast.kotlin.native.nuget

import java.io.File
import org.gradle.api.GradleException
import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

  @Test
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
}
