package io.github.xxfast.kotlin.native.nuget

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NugetPackageIdentityTest {
  @Test
  fun `NuGet case identity uses invariant lowercasing`() {
    val previous: Locale = Locale.getDefault()
    try {
      Locale.setDefault(Locale.forLanguageTag("tr-TR"))
      assertEquals("kn_testlibrary", nativeLibraryStem("TESTLIBRARY"))
      assertEquals(nativeLibraryStem("TestLibrary"), nativeLibraryStem("testlibrary"))
    } finally {
      Locale.setDefault(previous)
    }
  }

  @Test
  fun `separators fold to underscores in a readable stem`() {
    assertEquals("kn_testlibrary", nativeLibraryStem("TestLibrary"))
    assertEquals("kn_mobileevidence_kotlin", nativeLibraryStem("MobileEvidence.Kotlin"))
    assertEquals("kn_a", nativeLibraryStem("A"))
  }

  // #469: accepted limit, ids that differ only by separators share a stem.
  @Test
  fun `ids differing only by separators share a stem`() {
    assertEquals("kn_test_library", nativeLibraryStem("Test.Library"))
    assertEquals(nativeLibraryStem("Test.Library"), nativeLibraryStem("Test-Library"))
    assertEquals(nativeLibraryStem("Test-Library"), nativeLibraryStem("Test_Library"))
  }

  @Test
  fun `unsupported package ids fail before they can become filenames`() {
    listOf("", "../escape", "spaces here", "a".repeat(101)).forEach { id ->
      assertFailsWith<IllegalArgumentException> { nativeLibraryStem(id) }
    }
  }

  @Test
  fun `primary filenames follow platform conventions`() {
    assertEquals("kn_a.dll", nativeLibraryFile("A", "win-x64"))
    assertEquals("libkn_a.dylib", nativeLibraryFile("A", "osx-arm64"))
    assertEquals("libkn_a.so", nativeLibraryFile("A", "linux-x64"))
    assertFailsWith<IllegalStateException> { nativeLibraryFile("A", "unknown-x64") }
  }

  @Test
  fun `contract dependency is unique and bounded regardless of dependency id casing`() {
    assertEquals(mapOf("Managed" to "[4.0.0]", "Kotlin.Native.Interop" to "[1.0.0,2.0.0)"),
      dependencyRanges(mapOf("Managed" to "4.0.0", "kotlin.native.interop" to "1.0.0")))
  }
}
