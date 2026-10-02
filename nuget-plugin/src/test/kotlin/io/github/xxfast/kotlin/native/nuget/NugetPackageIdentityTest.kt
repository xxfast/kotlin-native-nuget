package io.github.xxfast.kotlin.native.nuget

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class NugetPackageIdentityTest {
  @Test
  fun `NuGet case identity uses invariant lowercasing`() {
    val previous: Locale = Locale.getDefault()
    try {
      Locale.setDefault(Locale.forLanguageTag("tr-TR"))
      assertEquals("kn_746573746c696272617279", nativeLibraryStem("TESTLIBRARY"))
      assertEquals(nativeLibraryStem("TestLibrary"), nativeLibraryStem("testlibrary"))
    } finally {
      Locale.setDefault(previous)
    }
  }

  @Test
  fun `punctuation is preserved in identity instead of collapsed`() {
    assertNotEquals(nativeLibraryStem("Test.Library"), nativeLibraryStem("Test-Library"))
    assertNotEquals(nativeLibraryStem("Test-Library"), nativeLibraryStem("Test_Library"))
    assertEquals("kn_61", nativeLibraryStem("A"))
  }

  @Test
  fun `unsupported package ids fail before they can become filenames`() {
    listOf("", "../escape", "spaces here", "a".repeat(101)).forEach { id ->
      assertFailsWith<IllegalArgumentException> { nativeLibraryStem(id) }
    }
  }

  @Test
  fun `primary filenames follow platform conventions`() {
    assertEquals("kn_61.dll", nativeLibraryFile("A", "win-x64"))
    assertEquals("libkn_61.dylib", nativeLibraryFile("A", "osx-arm64"))
    assertEquals("libkn_61.so", nativeLibraryFile("A", "linux-x64"))
    assertFailsWith<IllegalStateException> { nativeLibraryFile("A", "unknown-x64") }
  }

  @Test
  fun `contract dependency is unique and bounded regardless of dependency id casing`() {
    assertEquals(mapOf("Managed" to "[4.0.0]", "Kotlin.Native.Interop" to "[1.0.0,2.0.0)"),
      dependencyRanges(mapOf("Managed" to "4.0.0", "kotlin.native.interop" to "1.0.0")))
  }
}
