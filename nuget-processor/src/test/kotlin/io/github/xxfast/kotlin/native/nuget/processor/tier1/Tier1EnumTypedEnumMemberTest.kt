package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A pre-existing hole on the ADR-006 enum-member route, flushed out by the ADR-157 fixture and
 * fixed separately from it: an enum property typed as **another enum**
 * (`enum class Swirl(val patch: Patch)`) fell out of `mapReturnType`'s table, so the extension was
 * emitted as `public static IntPtr Patch(this Swirl swirl)` over an export returning the Kotlin
 * enum object itself. A consumer got a raw object pointer where the ADR-006 contract says the
 * ordinal, exactly as `Volume.loudness: Int` already gets an `int`.
 *
 * No fixture had an enum-typed enum member before, which is why nothing caught it. ADR-157's
 * finding 7 claimed the route needed nothing; that claim was wrong and the ADR now says so.
 */
class Tier1EnumTypedEnumMemberTest {

  @Test
  fun `an enum property typed as another enum lowers to the ordinal on both halves`() {
    val result = Tier1Harness.run(
      """
      package tier1.enumtypedenummember

      enum class Patch { BIB, SOCKS }

      enum class Swirl(val patch: Patch) {
        COCOA(Patch.BIB),
        CREAM(Patch.SOCKS),
      }
      """.trimIndent(),
    )

    assertTrue(result.compiledClean, "expected an enum-typed enum member to bind; got: ${result.compileErrors}")

    val kotlin: String = result.generated
    // ADR-006 amendment: planned, so the ordinal read sits inside the contained `try`.
    assertContains(kotlin, "tier1.enumtypedenummember.Swirl.entries[receiver].patch.ordinal")

    val cs: String = result.generatedCSharp
    assertContains(
      cs,
      "private static extern int Native_SwirlGetPatch(int receiver, out IntPtr error);",
    )
    assertContains(
      cs,
      "public static global::Interop.Patch Patch(this global::Interop.Swirl swirl)",
    )
    assertContains(cs, "Native_SwirlGetPatch((int)swirl, out IntPtr error)")
    // The defect: the return slot used to fall through to IntPtr on both halves.
    assertFalse(
      cs.contains("IntPtr Patch(this"),
      "the enum-typed member must not fall through to the IntPtr default",
    )
  }
}
