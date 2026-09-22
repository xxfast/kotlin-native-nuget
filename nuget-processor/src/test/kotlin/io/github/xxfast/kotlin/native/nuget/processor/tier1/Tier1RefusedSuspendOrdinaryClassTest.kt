package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-159: a class whose **only** async member was refused owns no scope. The ordinary-class
 * mirror of [Tier1SealedArmRefusedSuspendTest], which pinned the same rule for a sealed arm long
 * before the ordinary route derived its flag from anything.
 *
 * The scan this replaces read declarations, not projections, so `NapRegistry` was handed
 * `public class NapRegistry : IDisposable, IAsyncDisposable` with a `_scopeHandle` and a
 * `DisposeAsync` that drains a scope no call ever creates. In a coroutine-free module it was
 * worse than spurious: `tracker.needsAsync` is set only by projected async members, so the file
 * carried no `using System.Threading.Tasks` and the gate reported CS0246 on `ValueTask`, CS0246
 * on `Task` and CS0738 on the unimplementable interface.
 *
 * Deliberately a module with nothing else async, because that is the shape that broke: the
 * refusal has to survive being the only async declaration in the file.
 *
 * Mylo will not be weighed in pairs.
 */
class Tier1RefusedSuspendOrdinaryClassTest {

  private fun run(): Tier1Result = Tier1Harness.run(
    """
    package tier1.refusedordinary

    class NapRegistry {
      // Refused: `Pair` is not a bridgeable parameter, so no `*_pair_async` export exists.
      suspend fun pair(entry: Pair<String, Int>): Int = entry.second

      // The member that survives, and the reason the class is generated at all.
      fun cushions(): Int = 3
    }
    """.trimIndent(),
    fileName = "NapRegistrySample.kt",
    libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
  )

  @Test
  fun `a refused-only class still generates and compiles`() {
    val result = run()

    assertTrue(
      result.compiledClean,
      "expected the refused member to cost the class nothing; got: " +
          "${result.compileErrors} ${result.kspErrors}",
    )
  }

  /** The cell: no scope surface anywhere on the class, on either half. */
  @Test
  fun `a refused-only class is not IAsyncDisposable and declares no scope`() {
    val result = run()
    val cs: String = result.generatedCSharp

    assertContains(cs, "public class NapRegistry : IDisposable, INugetHandle")
    assertFalse(
      cs.contains("IAsyncDisposable"),
      "expected no IAsyncDisposable for a class with no projected async member; got: " +
          cs.lines().filter { it.contains("Disposable") }.map(String::trim),
    )
    listOf("_scopeHandle", "GetOrCreateScope", "DisposeAsync").forEach { absent ->
      assertFalse(
        cs.contains(absent),
        "expected no $absent for a refused-only class; got: " +
            cs.lines().filter { it.contains(absent) }.map(String::trim),
      )
    }
    assertFalse(
      result.generated.contains("napregistry_pair_async"),
      "expected no Kotlin export for the refused member either",
    )
  }

  /**
   * The refusal is still named. Losing the spurious interface must not cost the author the one
   * diagnostic that tells them why their member vanished.
   */
  @Test
  fun `the refused suspend parameter is named once`() {
    val result = run()

    val named: List<String> = result.kspWarnings.filter {
      it.contains("[nuget:${ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_INPUT.name}]") &&
          it.contains("NapRegistry.pair")
    }

    assertEquals(
      1,
      named.size,
      "expected exactly one SKIPPED_UNSUPPORTED_INPUT naming NapRegistry.pair; " +
          "kspWarnings=${result.kspWarnings}",
    )
  }
}
