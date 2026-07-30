package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

/**
 * ROADMAP.md line 288: `NugetProcessor.kt`'s `hasNothingToProcess` / aggregating `Dependencies`
 * construction treats 13 declaration buckets as the complete set of "things this module
 * exports", but never includes `suspendFunctions`. A source file whose only public declarations
 * are top-level `suspend fun`s therefore:
 *
 * 1. Never counts toward `hasNothingToProcess` (`:312`), so a module containing ONLY such a file
 *    generates nothing at all, silently (defect 1, [suspend-only module generates output at all]).
 * 2. Is never named in the `sources` array feeding the aggregating `Dependencies` for either
 *    generated output (`:319-331`), so on an incremental round triggered by editing some other,
 *    unrelated file, KSP has no record that the suspend-only file's declarations belong to
 *    either output and its exports silently disappear from BOTH halves (defects 2 and 3).
 *
 * The fixture deliberately keeps two files: one with ONLY top-level `suspend fun`s (declares in
 * zero buckets, the file that gets dropped) and one with an ordinary `class` (a declared bucket,
 * the file whose edit triggers the incremental round without itself exercising the bug). Putting
 * the suspend fun in the same file as a class would mask the bug, since the file would then
 * appear in the `classes` bucket's `containingFile` list regardless of the missing
 * `suspendFunctions` bucket.
 */
class Tier1SuspendOnlyFileTest {

  private val asyncOnlyFile = """
    package tier1.suspendbucket

    import kotlinx.coroutines.delay
    import kotlin.time.Duration.Companion.seconds

    suspend fun fetchGreeting(name: String): String {
      delay(1.seconds)
      return "Hello, ${'$'}name!"
    }
    """.trimIndent()

  private val vetFileV1 = """
    package tier1.suspendbucket

    class Vet(val name: String)
    """.trimIndent()

  private val vetFileV2 = """
    package tier1.suspendbucket

    class Vet(val name: String) {
      fun greet(): String = "Hi, ${'$'}name"
    }
    """.trimIndent()

  /**
   * Defect 2/3, C# half. Pass 1 generates `Interop.cs` with both `Vet` and the suspend-only
   * file's `FetchGreetingAsync` wrapper. Pass 2 edits ONLY `Vet.kt` and re-runs incrementally;
   * because `AsyncOnly.kt` was never declared as a source of the aggregating `Interop.cs`
   * output, the suspend export should vanish from pass 2's regeneration even though nothing
   * about the suspend function itself changed.
   */
  @Test
  fun `incremental edit of an unrelated file drops the suspend-only file's C# export`() {
    val pass1 = Tier1Harness.run(mapOf("AsyncOnly.kt" to asyncOnlyFile, "Vet.kt" to vetFileV1))
    assertContains(
      pass1.generatedCSharp,
      "FetchGreetingAsync",
      message = "sanity: pass 1 (full, non-incremental) must generate the suspend export before " +
          "the incremental regression can be observed; got: ${pass1.generatedCSharp}",
    )

    val pass2 = Tier1Harness.runIncremental(
      initial = mapOf("AsyncOnly.kt" to asyncOnlyFile, "Vet.kt" to vetFileV1),
      editedFile = "Vet.kt",
      editedContent = vetFileV2,
    )

    assertContains(
      pass2.generatedCSharp,
      "FetchGreetingAsync",
      message = "expected the suspend-only file's export to survive an incremental round " +
          "triggered by editing a DIFFERENT file, but it was dropped from Interop.cs. " +
          "generated files: ${pass2.generatedFiles.keys}",
    )
  }

  /**
   * Defect 2/3, Kotlin half (same two-pass round as the C# assertion above — same defect,
   * different generated output). `CNameExports.kt` is written with
   * `Dependencies(aggregating = true)` and no originating files at all (`:395`), so it is even
   * less anchored to the suspend-only file than `Interop.cs` is.
   */
  @Test
  fun `incremental edit of an unrelated file drops the suspend-only file's CName export`() {
    val pass1 = Tier1Harness.run(mapOf("AsyncOnly.kt" to asyncOnlyFile, "Vet.kt" to vetFileV1))
    assertContains(
      pass1.generated,
      "fetchGreeting_async",
      message = "sanity: pass 1 (full, non-incremental) must generate the suspend @CName export " +
          "before the incremental regression can be observed; got: ${pass1.generated}",
    )

    val pass2 = Tier1Harness.runIncremental(
      initial = mapOf("AsyncOnly.kt" to asyncOnlyFile, "Vet.kt" to vetFileV1),
      editedFile = "Vet.kt",
      editedContent = vetFileV2,
    )

    assertContains(
      pass2.generated,
      "fetchGreeting_async",
      message = "expected the suspend-only file's @CName export to survive an incremental round " +
          "triggered by editing a DIFFERENT file, but it was dropped from CNameExports.kt. " +
          "generated files: ${pass2.generatedFiles.keys}",
    )
  }

  /**
   * Defect 1. A module whose ONLY public declaration anywhere is a top-level `suspend fun` hits
   * every one of `hasNothingToProcess`'s 13 checked buckets as empty (`suspendFunctions` isn't
   * among them) and bails at `:317`, generating nothing — even on a clean, non-incremental,
   * single-shot build, with no diagnostic explaining why.
   */
  @Test
  fun `a module whose only declaration is a top-level suspend fun still generates output`() {
    val result = Tier1Harness.run(mapOf("AsyncOnly.kt" to asyncOnlyFile))

    assertTrue(
      result.generatedFiles.keys.any { it.endsWith("Interop.cs") },
      "expected Interop.cs to be generated for a suspend-only module; got no generated files " +
          "at all: ${result.generatedFiles.keys}. kspErrors=${result.kspErrors}",
    )
    assertTrue(
      result.generatedFiles.keys.any { it.endsWith("CNameExports.kt") },
      "expected CNameExports.kt to be generated for a suspend-only module; got no generated " +
          "files at all: ${result.generatedFiles.keys}. kspErrors=${result.kspErrors}",
    )
    assertContains(
      result.generatedFiles.entries.first { it.key.endsWith("Interop.cs") }.value,
      "FetchGreetingAsync",
      message = "expected the suspend function's async wrapper in the generated Interop.cs",
    )
    assertContains(
      result.generatedFiles.entries.first { it.key.endsWith("CNameExports.kt") }.value,
      "fetchGreeting_async",
      message = "expected the suspend function's @CName export in the generated CNameExports.kt",
    )
  }
}
