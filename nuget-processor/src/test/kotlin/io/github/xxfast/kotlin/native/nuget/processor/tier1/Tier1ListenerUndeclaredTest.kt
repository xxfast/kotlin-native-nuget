package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-039 amendment (2026-09-28): an `add*`/`remove*` subscription pair whose listener interface
 * has no C# declaration is refused on both halves and named, instead of emitting C#
 * `AddWatcher(IWatcher listener)` against an `IWatcher` nothing declares (CS0246 in the consumer's
 * build). The refusal reads like the same undeclared type at an ordinary member on the plan.
 */
class Tier1ListenerUndeclaredTest {

  private fun Tier1Result.assertPairSkipped(listener: String, marker: String) {
    assertTrue(kspSucceeded, "expected no KSP error; got $kspExitCode $kspErrors")
    assertTrue(compiledClean, "expected the fixture to bind; got: $compileErrors")
    assertFalse(generatedCSharp.contains("IWatcher"), "no C# may name the undeclared `IWatcher`")
    assertFalse(generatedCSharp.contains("AddWatcher("), "no C# member over an undeclared listener")
    assertFalse(generated.contains("object : $listener"), "no Kotlin export either")
    listOf("addWatcher", "removeWatcher").forEach { member ->
      assertTrue(
        kspWarnings.any { it.contains("Almanac.$member") && it.contains(listener) && it.contains(marker) },
        "expected a named skip for $member naming $listener with $marker; got $kspWarnings",
      )
    }
  }

  @Test
  fun `a listener nested under an enum owner skips the pair by name on both halves`() {
    Tier1Harness.run(
      """
      package tier1.listenerenum

      enum class Season {
        SPRING;
        interface Watcher { fun onTick() }
      }

      class Almanac {
        fun addWatcher(watcher: Season.Watcher) = Unit
        fun removeWatcher(watcher: Season.Watcher) = Unit
      }
      """.trimIndent(),
    ).assertPairSkipped("tier1.listenerenum.Season.Watcher", "[nuget:SKIPPED_UNSUPPORTED_TYPE]")
  }

  @Test
  fun `a nullable listener nested under a generic owner skips the pair by name on both halves`() {
    Tier1Harness.run(
      """
      package tier1.listenernullable

      class Box<T> {
        interface Watcher { fun onTick() }
      }

      class Almanac {
        fun addWatcher(watcher: Box.Watcher?) = Unit
        fun removeWatcher(watcher: Box.Watcher?) = Unit
      }
      """.trimIndent(),
    ).assertPairSkipped("tier1.listenernullable.Box.Watcher", "[nuget:SKIPPED_UNSUPPORTED_TYPE]")
  }

  @Test
  fun `a listener outside the export scope skips the pair as the plan skips that type`() {
    // A same-module interface outside `nuget.rootPackage` is not a dependency type, so it takes no
    // `include(...)` hint; what matters is that the pair reads exactly like `take`, an ordinary
    // member the plan skips for the same undeclared type.
    val result = Tier1Harness.run(
      mapOf(
        "Almanac.kt" to """
          package tier1.scoped

          class Almanac {
            fun addWatcher(watcher: tier1.outside.Watcher) = Unit
            fun removeWatcher(watcher: tier1.outside.Watcher) = Unit
            fun take(watcher: tier1.outside.Watcher) = Unit
          }
        """.trimIndent(),
        "Watcher.kt" to """
          package tier1.outside

          interface Watcher { fun onTick() }
        """.trimIndent(),
      ),
      processorOptions = mapOf("nuget.rootPackage" to "tier1.scoped"),
    )
    result.assertPairSkipped("tier1.outside.Watcher", "[nuget:SKIPPED_UNSUPPORTED_TYPE]")

    val plan: String = result.kspWarnings.singleOrNull { it.contains("Almanac.take: ") }
      ?: error("expected the plan to skip `take`; got ${result.kspWarnings}")
    val pair: String = result.kspWarnings.single { it.contains("Skipping Almanac.addWatcher:") }
    assertEquals(
      plan.substringAfter("Almanac.take: ").substringBefore("\n"),
      pair.substringAfter("subscription pair is not bound: ").substringBefore("\n"),
    )
  }

  @Test
  fun `a declared nested listener still binds the pair`() {
    val result = Tier1Harness.run(
      """
      package tier1.listenerdeclared

      class Aviary {
        interface Watcher { fun onTick() }
      }

      class Almanac {
        fun addWatcher(watcher: Aviary.Watcher?) = Unit
        fun removeWatcher(watcher: Aviary.Watcher?) = Unit
      }
      """.trimIndent(),
    )
    assertTrue(result.kspSucceeded, "expected no KSP error; got ${result.kspExitCode} ${result.kspErrors}")
    assertTrue(result.compiledClean, "expected the fixture to bind; got: ${result.compileErrors}")
    assertTrue(result.generatedCSharp.contains("AddWatcher("), "a declared listener keeps its pair")
    assertTrue(result.generatedCSharp.contains("Aviary.IWatcher"), "spelled through its owner")
  }
}
