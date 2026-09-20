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
 * **ADR-064 amendment (issue #249) narrows the rule, and these cells now pin the narrowed one.**
 * "A consumer cannot tell it apart from a class whose members are still to come" is precisely what
 * stops being true once the husk SAYS why it is empty, and a file whose every declaration was
 * dropped is the one place a consumer can be told at all. So a holder with remarks survives,
 * carrying nothing but its `<remarks>`; a holder with neither members nor remarks is elided exactly
 * as before, and the last cell here pins that half (which nothing else does any more).
 *
 * Same negative/positive pairing as [Tier1DroppedExtensionEmissionTest], which fixed the extension
 * half of this bug locally with an `isNotEmpty()` guard at its own two emission sites.
 */
class Tier1EmptyStaticClassElisionTest {

  /**
   * `List<List<String>?>` is an unsupported input (ADR-099's nullable nested component), so `scan`
   * is skipped and the file has nothing left to declare. Since issue #249 the holder is kept for
   * its remark alone -- the drop reaches a consumer in `Interop.cs` itself or nowhere -- and it
   * carries no member of any kind.
   */
  @Test
  fun `file with every declaration skipped keeps a holder that carries only the remark`() {
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
    assertTrue(
      result.generatedCSharp.contains("class HuskOnly"),
      "expected the HuskOnly holder to survive to carry its skip remark (issue #249); " +
          "generatedCSharp=${result.generatedCSharp}",
    )
    assertTrue(
      result.generatedCSharp.contains("Not generated from Kotlin `scan`") &&
          result.generatedCSharp.contains("SKIPPED_UNSUPPORTED_INPUT"),
      "expected the holder's remark to name the dropped function and its kind; " +
          "generatedCSharp=${result.generatedCSharp}",
    )
    // The remark is the ONLY thing in it: naming a member as dropped and then emitting it under
    // some other spelling would be the lie ADR-064's honesty rule exists to stop.
    assertFalse(
      result.generatedCSharp.withoutDocComments().contains("scan", ignoreCase = true),
      "expected no member for the dropped function, under any spelling; " +
          "generatedCSharp=${result.generatedCSharp}",
    )
  }

  /**
   * The other half, which nothing else pins any more: a file that declares nothing the bridge
   * could carry AND drops nothing (`internal`/`private` declarations are not part of the public
   * API and are not skips) still renders no holder and no namespace. Without this cell, "keep a
   * holder with remarks" could rot into "keep every holder" with every husk test still green.
   */
  @Test
  fun `file with nothing declared and nothing dropped still emits no static class`() {
    // A second file with a real export, because a module with NO public API generates no
    // `Interop.cs` at all (issue #55's own early return) and would prove nothing about elision.
    val result = Tier1Harness.run(
      mapOf(
        "QuietOnly.kt" to """
        package tier1.quiet

        internal fun tally(): Int = 1

        private val secret: String = "hush"
        """.trimIndent(),
        "LoudOnly.kt" to """
        package tier1.loud

        fun bark(): Int = 1
        """.trimIndent(),
      ),
    )

    assertTrue(
      result.compiledClean,
      "expected no broken source for the quiet fixture; got: ${result.compileErrors}",
    )
    assertFalse(
      result.generatedCSharp.contains("QuietOnly"),
      "expected no QuietOnly static class: nothing was declared and nothing was dropped, so " +
          "there is neither an API nor anything to say; generatedCSharp=${result.generatedCSharp}",
    )
    assertFalse(
      result.generatedCSharp.lines()
        .any { it.trim().startsWith("namespace ") && it.contains("Quiet") },
      "expected the empty namespace block to be dropped with it; " +
          "generatedCSharp=${result.generatedCSharp}",
    )
  }

  /**
   * Positive half, one member of distance from the husk: the same skipped shape plus a surviving
   * `ping()`. The class stays, with only the survivor in it, so "elide when empty" cannot degrade
   * into "elide when anything was skipped". Since issue #249 it also carries a remark naming the
   * skipped sibling, which is what a consumer looking for `Sift` needs to read.
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
    assertTrue(
      result.generatedCSharp.contains("Not generated from Kotlin `sift`"),
      "expected the skipped sibling to be named on the holder it is missing from (issue #249); " +
          "generatedCSharp=${result.generatedCSharp}",
    )
    assertFalse(
      result.generatedCSharp.withoutDocComments().contains("sift", ignoreCase = true),
      "expected the skipped sibling to stay out of HuskMixed's members; " +
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
