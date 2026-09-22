package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Issue #233: ADR-007 names a file's static holder after the file stem, and `Hub.mingw.kt` is the
 * standard KMP spelling for a platform `actual`. Taken verbatim the stem produced
 * `public static partial class Hub.mingw`, which C# reads as a qualified name (CS1514 and 18 more,
 * cascading to the end of `Interop.cs`). The stem now goes through `String.csharpIdentifier()`
 * once, at the grouping key, so every file-derived holder name is a legal C# identifier.
 */
class Tier1DottedFileClassNameTest {

  /** The issue's repro: one dot, one holder, a name C# can parse. */
  @Test
  fun `a platform-suffixed file names a legal holder class`() {
    val result = Tier1Harness.run(
      sources = mapOf(
        "Hub.mingw.kt" to """
        package tier1.dottedfile.platform

        fun hub(): Int = 7
        """.trimIndent(),
      ),
    )

    assertTrue(result.kspErrors.isEmpty(), "expected no KSP errors; got: ${result.kspErrors}")
    assertTrue(result.compiledClean, "got: ${result.compileErrors}")

    val cs: String = result.generatedCSharp
    assertContains(cs, "public static partial class HubMingw")
    assertContains(cs, "public static int Hub()")
    assertFalse(
      "class Hub.mingw" in cs,
      "a dotted class name does not parse as C#; cs=$cs",
    )
    // C#-only: the native export name never came from the file, so it is untouched.
    assertContains(result.generated, "@CName(\"library_tier1_dottedfile_platform__hub\")")
  }

  /**
   * Requirement 3 of the issue: `Hub.kt` beside `Hub.mingw.kt` must stay two holders. Trading the
   * parse error for a silent merge of two files into one class would be worse.
   */
  @Test
  fun `a dotted file and its undotted neighbour stay distinct holders`() {
    val result = Tier1Harness.run(
      sources = mapOf(
        "Hub.kt" to """
        package tier1.dottedfile.neighbour

        fun hubName(): String = "hub"
        """.trimIndent(),
        "Hub.mingw.kt" to """
        package tier1.dottedfile.neighbour

        fun hub(): Int = 7
        """.trimIndent(),
      ),
    )

    assertTrue(result.kspErrors.isEmpty(), "expected no KSP errors; got: ${result.kspErrors}")
    assertTrue(result.compiledClean, "got: ${result.compileErrors}")

    val cs: String = result.generatedCSharp
    assertContains(cs, "public static partial class Hub\n")
    assertContains(cs, "public static partial class HubMingw")
    assertContains(cs, "public static string HubName()")
    assertContains(cs, "public static int Hub()")
  }

  /**
   * ADR-074 Decision 3 still wins: a genuine `actual` is named after its `expect`'s file, so the
   * sanitiser never sees the per-target stem at all. The common file's own non-`expect` helper
   * lands on the same holder, which is the point of Decision 3.
   */
  @Test
  fun `an actual still takes the expect's file name rather than its own sanitised one`() {
    val result = Tier1Harness.run(
      sources = mapOf(
        "Hub.mingw.kt" to """
        package tier1.dottedfile.expectactual

        actual fun hubTag(): String = "mingw"
        """.trimIndent(),
      ),
      commonSources = mapOf(
        "Hub.kt" to """
        package tier1.dottedfile.expectactual

        expect fun hubTag(): String

        fun hubHelper(): Int = 1
        """.trimIndent(),
      ),
    )

    assertTrue(result.kspErrors.isEmpty(), "expected no KSP errors; got: ${result.kspErrors}")

    val cs: String = result.generatedCSharp
    assertContains(cs, "public static partial class Hub\n")
    assertContains(cs, "public static string HubTag()")
    assertContains(cs, "public static int HubHelper()")
    assertFalse(
      "HubMingw" in cs,
      "ADR-074 Decision 3 names the actual after the expect's file; cs=$cs",
    )
  }

  /**
   * The rule is "produce a legal identifier from any file name", not a list of known platform
   * suffixes: every non-identifier run is a segment boundary, and a leading digit takes a `_`.
   */
  @Test
  fun `several dots join and a leading digit is prefixed`() {
    val result = Tier1Harness.run(
      sources = mapOf(
        "Net.io.core.kt" to """
        package tier1.dottedfile.manysegments

        fun netPing(): Int = 1
        """.trimIndent(),
        "9lives.kt" to """
        package tier1.dottedfile.manysegments

        fun livesLeft(): Int = 9
        """.trimIndent(),
        // Not a dot: any non-identifier run is a boundary, and a trailing one yields no segment.
        "net-tools-.kt" to """
        package tier1.dottedfile.manysegments

        fun netTrace(): Int = 2
        """.trimIndent(),
      ),
    )

    assertTrue(result.kspErrors.isEmpty(), "expected no KSP errors; got: ${result.kspErrors}")
    assertTrue(result.compiledClean, "got: ${result.compileErrors}")

    val cs: String = result.generatedCSharp
    assertContains(cs, "public static partial class NetIoCore")
    assertContains(cs, "public static partial class _9lives")
    assertContains(cs, "public static partial class netTools")
  }

  /**
   * Sanitising makes `HubMingw.kt` and `Hub.mingw.kt` share one holder. That is harmless while
   * their members differ (Kotlin already forbids two identical top-level signatures in a package),
   * and where a member name does repeat across the two files, ADR-110's existing CS0102 guard
   * fires: it is keyed on the file-class key, which is the sanitised one, so it sees both files.
   * No new check was added for the merge.
   */
  @Test
  fun `a member name repeated across two files sharing a holder fails loudly`() {
    val result = Tier1Harness.run(
      sources = mapOf(
        "HubMingw.kt" to """
        package tier1.dottedfile.merged

        val status: String = "x"
        """.trimIndent(),
        "Hub.mingw.kt" to """
        package tier1.dottedfile.merged

        fun status(): String = "y"
        """.trimIndent(),
      ),
    )

    val error: String? = result.kspErrors.firstOrNull {
      it.contains(ForwardDiagnosticKind.ERROR_CSHARP_NAME_COLLISION.name)
    }
    assertTrue(error != null, "expected the named collision failure; kspErrors=${result.kspErrors}")
    assertContains(error, "HubMingw.Status")
    assertContains(error, "CS0102")
  }
}
