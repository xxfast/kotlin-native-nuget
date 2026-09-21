package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-098 amendment (boundary nullability part C): a bare `Char?` binds at every ordinary forward
 * position on the ADR-079/080 has-value shapes. `Char` is its own [BridgeType], not a
 * `PrimitiveKind`, which is why every `is BridgeType.Primitive` fan-out arm missed it, and the
 * consequences differed by position: the PROPERTY position threw
 * `IllegalStateException: Forward property direct nullable getter is invalid for ...: Char` out of
 * `KotlinSymbolProcessing.execute` and aborted generation for the whole module, while the three
 * callable positions skipped named with a hint ("a separate has-value/value pair") that described
 * exactly the shape this change builds.
 *
 * The wire is the load-bearing part. A by-value slot is a `char` with ADR-098's
 * `[MarshalAs(UnmanagedType.U2)]`; an OUT slot is a blittable `ushort` that C# casts, never an
 * `out char`. A BARE `out char` marshals one ANSI byte and silently corrupts every non-ASCII
 * character (measured: U+00E9 to U+FFFD, U+732B to U+002B), so the absence of that spelling is
 * asserted rather than assumed.
 */
class Tier1BareNullableCharTest {

  @Test
  fun `bare nullable Char property rides the has-value fan-out on both halves`() {
    val result = Tier1Harness.run(
      """
      package tier1.barenullablechar

      class Tag(var initial: Char?)
      """.trimIndent(),
    )

    assertTrue(
      result.compiledClean,
      "expected a bare nullable Char property to bind; got: ${result.compileErrors}",
    )

    val kotlin: String = result.generated
    // ADR-002's LegacyTwoCall getter: the presence call answers null-ness, the value call ships the
    // character by value.
    assertContains(kotlin, "get().initial != null")
    assertContains(kotlin, "get().initial!!")
    // The NullableDispatch setter pair.
    assertContains(kotlin, "get().initial = value")
    assertContains(kotlin, "get().initial = null")

    val cs: String = result.generatedCSharp
    assertContains(cs, "public char? Initial")
    assertContains(cs, "if (!hasValue) return null;")
    // The by-value slots keep ADR-098's U2, on the getter's `_value` import and the setter alike.
    assertContains(cs, "[return: MarshalAs(UnmanagedType.U2)]")
    assertContains(cs, "[MarshalAs(UnmanagedType.U2)] char value")
    // Never a bare `char` slot: ANSI narrowing would pass every ASCII test and corrupt the rest.
    assertFalse("out char" in cs, "a Char? must never ride an `out char` slot")
  }

  @Test
  fun `bare nullable Char parameter fans out to the adjacent HasValue pair`() {
    val result = Tier1Harness.run(
      """
      package tier1.barenullablecharparam

      class Tag(val observed: Char?) {
        fun describe(initial: Char?): String = initial?.toString() ?: "none"
      }
      """.trimIndent(),
    )

    assertTrue(
      result.compiledClean,
      "expected a bare nullable Char parameter to bind; got: ${result.compileErrors}",
    )

    val kotlin: String = result.generated
    // The HasValue flag decides, so the value slot's dead default is never read.
    assertContains(kotlin, "if (initialHasValue) initial else null")
    assertContains(kotlin, "if (observedHasValue) observed else null")

    val cs: String = result.generatedCSharp
    // The constructor cell: csharp-dev observed `WARNING_NO_PUBLIC_CONSTRUCTOR ... <init>: NULLABLE`
    // here, so a bindable ctor is its own assertion and not a side effect of the method one.
    assertContains(cs, "public Tag(char? observed)")
    assertContains(cs, "char initial")
    assertContains(cs, "initial.HasValue, initial.GetValueOrDefault()")
    assertContains(cs, "observed.HasValue, observed.GetValueOrDefault()")
    assertEquals(
      emptyList(),
      result.kspWarnings.filter { warning -> "SKIPPED" in warning || "NO_PUBLIC_CONSTRUCTOR" in warning },
      "expected no skip and no missing-constructor warning for any Char? position",
    )
  }

  @Test
  fun `bare nullable Char method return uses the BOOLEAN plus ushort valueOut shape`() {
    val result = Tier1Harness.run(
      """
      package tier1.barenullablecharreturn

      class Tag {
        // ADR-061's single-call shape: a BOOLEAN has-value result plus a `ushort` valueOut.
        fun echo(initial: Char?): Char? = initial
      }
      """.trimIndent(),
    )

    assertTrue(
      result.compiledClean,
      "expected a bare nullable Char return to bind; got: ${result.compileErrors}",
    )

    val kotlin: String = result.generated
    // kotlinx.cinterop has no `CharVar`, so the write goes through `UShortVar` over `.code`.
    assertContains(kotlin, "valueOut.reinterpret<UShortVar>().pointed.value = result.code.toUShort()")

    val cs: String = result.generatedCSharp
    assertContains(cs, "out ushort valueOut")
    assertContains(cs, "hasValue ? (char)valueOut : (char?)null;")
    assertFalse("out char" in cs, "the valueOut slot must be a blittable ushort, not an out char")
  }

  @Test
  fun `top-level bare nullable Char return and property keep ADR-002's two-call shape`() {
    val result = Tier1Harness.run(
      """
      package tier1.barenullablechartop

      fun firstLetter(name: String): Char? = name.firstOrNull()

      val mascotInitial: Char? = '한'
      """.trimIndent(),
    )

    assertTrue(
      result.compiledClean,
      "expected top-level Char? positions to bind; got: ${result.compileErrors}",
    )

    val kotlin: String = result.generated
    // The top-level function return takes the LEGACY two-call reroute (`_has_value` + `_value`),
    // which ADR-076, ADR-079 and ADR-080 each had to add their own type to.
    assertContains(kotlin, "_has_value")
    // The top-level PROPERTY crashes through a different caller (`PropertyExports`) than a class
    // property, so it is its own cell.
    assertContains(kotlin, "mascotInitial != null")
    assertContains(kotlin, "mascotInitial!!")

    val cs: String = result.generatedCSharp
    assertContains(cs, "public static char? FirstLetter(")
    assertContains(cs, "public static char? MascotInitial")
    assertFalse("out char" in cs, "the two-call route ships a by-value char, never an out char")
  }
}
