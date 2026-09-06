package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-007 gives every Kotlin file a C# static class named after the file. When every top-level
 * declaration in that file is skipped, the class is still emitted, empty: `public static partial
 * class HuskOnly { }`. That type is not an API, it is the residue of a skip, and a consumer cannot
 * tell it apart from a class whose members are still to come. Worse, when the husk was the only
 * occupant of its package, the whole `namespace X { }` block survives around nothing.
 *
 * The fix elides a `CirStaticClass` whose merged member set is empty, and then drops a namespace
 * left with no declarations. It runs once, after every contribution loop has merged, because
 * contributions merge across loops by class name: a file with a skipped sync function and a
 * surviving `suspend fun` must keep its class, so no per-loop guard can be correct.
 *
 * Same negative/positive pairing as [Tier1DroppedExtensionEmissionTest], which fixed the extension
 * half of this bug locally with an `isNotEmpty()` guard at its own two emission sites.
 */
class Tier1EmptyStaticClassElisionTest {

  /**
   * Negative half: `List<List<String>?>` is an unsupported input (ADR-099's nullable nested
   * component), so `scan` is skipped and the file has nothing left. Neither the class nor the
   * namespace that held only it may be emitted.
   */
  @Test
  fun `file with every declaration skipped emits no static class and no namespace`() {
    val result = Tier1Harness.run(
      """
      package tier1.husk

      fun scan(litters: List<List<String>?>): Int = litters.size
      """.trimIndent(),
      fileName = "HuskOnly.kt",
    )

    assertTrue(
      result.compiledClean,
      "expected no broken source for the husk fixture; got: ${result.compileErrors}",
    )
    assertFalse(
      result.generatedCSharp.contains("HuskOnly"),
      "expected no empty HuskOnly static class in the generated C#; " +
          "generatedCSharp=${result.generatedCSharp}",
    )
    assertFalse(
      result.generatedCSharp.lines()
        .any { it.trim().startsWith("namespace ") && it.contains("Husk") },
      "expected the husk's namespace block to be dropped once it held no declarations; " +
          "generatedCSharp=${result.generatedCSharp}",
    )
  }

  /**
   * Positive half, one member of distance from the husk: the same skipped shape plus a surviving
   * `ping()`. The class stays, with only the survivor in it, so "elide when empty" cannot degrade
   * into "elide when anything was skipped".
   */
  @Test
  fun `file with one surviving declaration keeps its static class`() {
    val result = Tier1Harness.run(
      """
      package tier1.mixed

      fun sift(litters: List<List<String>?>): Int = litters.size

      fun ping(): Int = 1
      """.trimIndent(),
      fileName = "HuskMixed.kt",
    )

    assertTrue(
      result.compiledClean,
      "expected no broken source for the mixed fixture; got: ${result.compileErrors}",
    )
    assertTrue(
      result.generatedCSharp.contains("class HuskMixed"),
      "expected HuskMixed to survive for its one bound function; " +
          "generatedCSharp=${result.generatedCSharp}",
    )
    assertTrue(
      result.generatedCSharp.contains("ping"),
      "expected the bound function to be a member of HuskMixed; " +
          "generatedCSharp=${result.generatedCSharp}",
    )
    assertFalse(
      result.generatedCSharp.contains("sift", ignoreCase = true),
      "expected the skipped sibling to stay out of HuskMixed; " +
          "generatedCSharp=${result.generatedCSharp}",
    )
  }

  /**
   * The cross-loop case that rules out a per-loop guard: the sync-function loop contributes an
   * empty member list for this file, and the suspend loop then merges the survivor into the same
   * class by name. Guarding either loop on its own contribution would drop the class before the
   * other loop ever ran.
   */
  @Test
  fun `file whose only survivor is a suspend function keeps its static class`() {
    val result = Tier1Harness.run(
      """
      package tier1.suspendmix

      fun sift(litters: List<List<String>?>): Int = litters.size

      suspend fun fetch(): Int = 7
      """.trimIndent(),
      fileName = "HuskSuspend.kt",
    )

    assertTrue(
      result.compiledClean,
      "expected no broken source for the suspend fixture; got: ${result.compileErrors}",
    )
    assertTrue(
      result.generatedCSharp.contains("class HuskSuspend"),
      "expected HuskSuspend to survive for its suspend function, merged in by a later loop; " +
          "generatedCSharp=${result.generatedCSharp}",
    )
  }
}
