package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Issue #50 (follow-up to #41): the top-level class renderer qualifies every cross-namespace type
 * reference as `global::Namespace.Name` since #47, but `translateSealedClass` spells a sealed
 * subclass's property, collection component and enum types by *simple* name, so a sealed hierarchy
 * whose payload types live in another exported package fails with CS0246 in every one of those
 * positions. `rootPackage` is set so the `remote` sub-package lands in its own sub-namespace,
 * which is the hop the issue is about.
 */
class Tier1SealedSubclassCrossNamespaceTest {

  private val sources: Map<String, String> = mapOf(
    "Remote.kt" to """
      package tier1.issue50.remote

      data class Assignment(val name: String)
      data class IssPosition(val latitude: Double, val longitude: Double)
      enum class Mood { CALM, HUNGRY }
    """.trimIndent(),
    "UiState.kt" to """
      package tier1.issue50

      import tier1.issue50.remote.Assignment
      import tier1.issue50.remote.IssPosition
      import tier1.issue50.remote.Mood

      sealed class UiState {
        data object Loading : UiState()

        data class Success(
          val result: List<Assignment>,
          val position: IssPosition,
          val maybe: IssPosition?,
          val mood: Mood,
          val unique: Set<Assignment>,
          val byName: Map<String, Assignment>,
        ) : UiState()
      }

      fun loading(): UiState = UiState.Loading
    """.trimIndent(),
  )

  private val remote: String = "global::Interop.Remote"

  private fun run(): Tier1Result = Tier1Harness.run(
    sources,
    processorOptions = mapOf("nuget.rootPackage" to "tier1.issue50"),
  )

  @Test
  fun `a List property on a sealed subclass qualifies its element in every position`() {
    val result = run()

    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")
    assertContains(result.generatedCSharp, "public IReadOnlyList<$remote.Assignment> Result")
    assertContains(result.generatedCSharp, "NugetMarshal.ReadList<$remote.Assignment>(nativeResult,")
    assertContains(
      result.generatedCSharp,
      "static h1 => NugetMarshal.FromHandle<$remote.Assignment>(h1)",
    )
  }

  @Test
  fun `an object property on a sealed subclass qualifies its type and constructor`() {
    val result = run()

    assertContains(
      result.generatedCSharp,
      "public $remote.IssPosition Position",
    )
    assertContains(
      result.generatedCSharp,
      "                return new $remote.IssPosition(nativeResult);",
    )
    // ADR-111 (ROADMAP:27): ONE native call for the nullable reference. The legacy spelling called
    // the export twice, so the null test and the wrapped handle came from two different
    // `StableRef.create`s and one of them leaked.
    assertContains(result.generatedCSharp, "public $remote.IssPosition? Maybe")
    assertContains(
      result.generatedCSharp,
      "                return nativeResult == IntPtr.Zero ? null : new $remote.IssPosition(nativeResult);",
    )
  }

  @Test
  fun `an enum property on a sealed subclass qualifies its type and cast`() {
    val result = run()

    assertContains(result.generatedCSharp, "public $remote.Mood Mood")
    assertContains(
      result.generatedCSharp,
      "                int nativeResult = Native_Get_mood(_handle, out IntPtr error);",
    )
    assertContains(result.generatedCSharp, "                return ($remote.Mood)nativeResult;")
  }

  @Test
  fun `Set and Map properties on a sealed subclass qualify their components`() {
    val result = run()

    assertContains(result.generatedCSharp, "public IReadOnlySet<$remote.Assignment> Unique")
    assertContains(result.generatedCSharp, "NugetMarshal.ReadSet<$remote.Assignment>(nativeResult,")
    assertContains(result.generatedCSharp, "public IReadOnlyDictionary<string, $remote.Assignment> ByName")
    assertContains(result.generatedCSharp, "NugetMarshal.ReadMap<string, $remote.Assignment>(nativeResult,")
  }

  @Test
  fun `no bare cross-namespace reference survives anywhere in the sealed hierarchy`() {
    val result = run()

    // The sealed base's own file is the only place a bare `Assignment`/`IssPosition`/`Mood` could
    // appear; Remote.kt's own declarations are spelled inside their own namespace.
    val sealedBlock: String = result.generatedCSharp
      .substringAfter("public abstract class UiState")
      .substringBefore("namespace Interop.Remote")
    val bareSpellings: List<String> =
      listOf("<Assignment>", "public IssPosition ", "new IssPosition(", "public Mood ", "(Mood)")
    for (bare in bareSpellings) {
      assertFalse(
        sealedBlock.contains(bare),
        "expected no bare `$bare` inside the sealed hierarchy; sealed block=$sealedBlock",
      )
    }
  }
}
