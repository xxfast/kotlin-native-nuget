package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/**
 * The rest of the generator-owned identifier family issue #66 left out. `error` was one name in a
 * set: the ordinary forward callable plan also owns `handle`, `receiver`, `value`, `errorOut` and
 * `valueOut` on the ABI, plus `nativeResult` / `hasValue` as C# wrapper-body locals. A user Kotlin
 * parameter spelled like any of them collides, and unlike #66 the collision is not C#-only: three
 * of them are declared by the Kotlin `@CName` emitter too, so the export itself stops compiling,
 * and two of them (`errorOut` / `valueOut`) abort KSP outright at the ADR-055 contract check,
 * which reads a slot's direction off its name.
 *
 * One cell per *mechanism*, not per type, because the failures are not the same compiler error and
 * a fixture trimmed to the cheapest one would go green against a fix covering only that one. Every
 * cell asserts the extern, the public declaration **and** the wrapper-body call, since the three
 * are printed from different code and a fix that moved one without the others still would not
 * compile.
 *
 * Mirrors `test-library/.../test/reserved/ReservedNamesSample.kt`, whose
 * `IntegrationTests.ReservedNamesTests` facts prove the same C# actually builds and runs.
 */
class Tier1ReservedParameterNamesTest {

  private val fixture: String = """
    package tier1.reserved

    data class Widget(val handle: Int)

    class Gadget {
      fun describe(handle: Int, value: String, nativeResult: Int): String =
        "${'$'}handle/${'$'}value/${'$'}nativeResult"
    }

    fun String.tag(receiver: String): String = "${'$'}this:${'$'}receiver"

    fun probe(errorOut: Int, valueOut: Int): Int? =
      if (errorOut == 0) null else errorOut + valueOut

    class Dial {
      fun read(valueOut: Int, hasValue: Int): Int? =
        if (hasValue == 0) null else valueOut + hasValue
    }

    @JvmInline
    value class Ratio(val numerator: Int) {
      fun scale(value: Int): Int = numerator * value
    }

    class Meter(val value: String)
  """.trimIndent()

  private val result: Tier1Result by lazy { Tier1Harness.run(fixture) }

  /**
   * The cell that fails earliest and hardest, before a line is rendered: the forward ABI check
   * infers a parameter's direction from its name, so a user `errorOut` / `valueOut` is projected
   * `out` on the Kotlin side against `in` on the C# side and KSP aborts. Asserted first because
   * every other cell in this class is unreachable while it fires.
   */
  @Test
  fun `a parameter named after an out slot does not break the forward abi contract`() {
    assertEquals(emptyList(), result.kspErrors)
    assertEquals(emptyList(), result.compileErrors)
  }

  /**
   * The wrapper-body local collision with no declaration-site duplicate: the constructor body
   * opens `IntPtr handle = Native_Create(...)`, so an argument spelled `handle` rebinds to the
   * not-yet-assigned local (CS0136). Kotlin is clean here, which is what isolates the C# render.
   */
  @Test
  fun `a constructor parameter named after the handle local is renamed`() {
    assertContains(
      result.generatedCSharp,
      "private static extern IntPtr Native_Create(int handle_, out IntPtr error);",
    )
    assertContains(result.generatedCSharp, "public Widget(int handle_)")
    assertContains(
      result.generatedCSharp,
      "IntPtr handle = Native_Create(handle_, out IntPtr error);",
    )
    assertContains(
      result.generated,
      "public fun export_widget_create(handle_: Int, errorOut: COpaquePointer?)",
    )
  }

  /**
   * The synthesized data-class `copy` is the declaration-site half of the same collision: it is an
   * instance callable, so its extern already leads with the `IntPtr handle` receiver slot and a
   * second `handle` beside it is CS0100. On the Kotlin side the export declares `handle` twice.
   */
  @Test
  fun `the data class copy route renames a parameter that shadows the receiver slot`() {
    assertContains(
      result.generatedCSharp,
      "private static extern IntPtr Native_Copy(IntPtr handle, int handle_, out IntPtr error);",
    )
    assertContains(result.generatedCSharp, "public Widget Copy(int handle_)")
    assertContains(
      result.generatedCSharp,
      "IntPtr nativeResult = Native_Copy(_handle, handle_, out IntPtr error);",
    )
    assertContains(result.generated, "  handle: COpaquePointer,\n  handle_: Int,\n  errorOut:")
  }

  /**
   * Three names on one ordinary instance method. `handle` duplicates the receiver slot on both
   * sides, `nativeResult` rebinds the C# body local the `string` return declares (CS0136), and
   * `value` collides with nothing on this route and still moves, which is what pins the rule as
   * uniform rather than a per-callable collision test.
   *
   * `nativeResult` is the C#-only half: it shifts in the extern and the public signature, and the
   * Kotlin export keeps the user's own spelling, because the Kotlin emitter declares no such name.
   */
  @Test
  fun `an instance method renames the handle receiver, the value slot and the C# result local`() {
    assertContains(
      result.generatedCSharp,
      "private static extern IntPtr Native_Describe(IntPtr handle, int handle_, " +
          "[MarshalAs(UnmanagedType.LPUTF8Str)] string value_, int nativeResult_, " +
          "out IntPtr error);",
    )
    assertContains(
      result.generatedCSharp,
      "public string Describe(int handle_, string value_, int nativeResult_)",
    )
    assertContains(
      result.generatedCSharp,
      "IntPtr nativeResult = Native_Describe(_handle, handle_, value_, nativeResult_, " +
          "out IntPtr error);",
    )
    assertContains(
      result.generated,
      "  handle: COpaquePointer,\n  handle_: Int,\n  value_: String,\n  nativeResult: Int,\n" +
          "  errorOut: COpaquePointer?,\n",
    )
  }

  /**
   * The extension receiver, the one name that duplicates at the same position on both sides:
   * `Native_Tag(string receiver, string receiver, ...)` is CS0100 and the Kotlin export declares
   * `receiver` twice, which is a compile error rather than a warning.
   */
  @Test
  fun `an extension function renames a parameter that shadows the receiver slot`() {
    assertContains(
      result.generatedCSharp,
      "private static extern IntPtr Native_Tag([MarshalAs(UnmanagedType.LPUTF8Str)] " +
          "string receiver, [MarshalAs(UnmanagedType.LPUTF8Str)] string receiver_, " +
          "out IntPtr error);",
    )
    assertContains(
      result.generatedCSharp,
      "public static string Tag(this string receiver, string receiver_)",
    )
    assertContains(result.generatedCSharp, "Native_Tag(receiver, receiver_, out IntPtr error);")
    assertContains(result.generated, "  `receiver`: String,\n  receiver_: String,\n  errorOut:")
    assertContains(result.generated, "receiver.tag(receiver_)")
  }

  /**
   * The `valueOut` slot and the `hasValue` local, which only the class-member nullable route
   * produces: the top-level route fans out into two exports with `__nuget_`-prefixed locals, so it
   * has neither name to collide with. ADR-061's shape keeps one export, so the extern carries the
   * user's two `in` slots *and* the generator's `out int valueOut`, read back through
   * `bool hasValue`.
   */
  @Test
  fun `a nullable class member renames the value out slot and the has value local`() {
    assertContains(
      result.generatedCSharp,
      "private static extern bool Native_Read(IntPtr handle, int valueOut_, int hasValue_, " +
          "out int valueOut, out IntPtr error);",
    )
    assertContains(result.generatedCSharp, "public int? Read(int valueOut_, int hasValue_)")
    assertContains(
      result.generatedCSharp,
      "bool hasValue = Native_Read(_handle, valueOut_, hasValue_, out int valueOut, " +
          "out IntPtr error);",
    )
    assertContains(result.generatedCSharp, "return hasValue ? valueOut : null;")
    assertContains(
      result.generated,
      "  handle: COpaquePointer,\n  valueOut_: Int,\n  hasValue: Int,\n" +
          "  valueOut: COpaquePointer?,\n  errorOut: COpaquePointer?,\n",
    )
  }

  /**
   * The `value` receiver slot, and the cell the uniform `value` rule exists for: a value class
   * over a non-reference underlying passes its receiver in a slot the planner names `value`, so a
   * member parameter of the same name is CS0100 at that position. ADR-014 means a value-class
   * member carries no exception slot, so this extern has no trailing `out IntPtr error` at all.
   */
  @Test
  fun `a value class member renames a parameter that shadows the underlying receiver slot`() {
    assertContains(
      result.generatedCSharp,
      "private static extern int Native_Scale(int value, int value_);",
    )
    assertContains(
      result.generatedCSharp,
      "public int Scale(int value_) => Native_Scale(Numerator, value_);",
    )
    assertContains(
      result.generated,
      "public fun export_ratio_scale(`value`: Int, value_: Int): Int = " +
          "tier1.reserved.Ratio(value).scale(value_)",
    )
  }

  /**
   * The control. `value` on a property renders `Value` (properties are PascalCased, so they never
   * meet a generator identifier) and must not move; the same word on the constructor parameter
   * does. A fix that renamed the identifier everywhere instead of only at parameter positions
   * fails here.
   */
  @Test
  fun `a property keeps its name while the constructor parameter of the same word moves`() {
    assertContains(result.generatedCSharp, "public string Value")
    assertContains(
      result.generatedCSharp,
      "private static extern IntPtr Native_Create([MarshalAs(UnmanagedType.LPUTF8Str)] " +
          "string value_, out IntPtr error);",
    )
    assertContains(result.generatedCSharp, "public Meter(string value_)")
    assertContains(
      result.generatedCSharp,
      "IntPtr handle = Native_Create(value_, out IntPtr error);",
    )
    assertContains(
      result.generated,
      "public fun export_meter_create(value_: String, errorOut: COpaquePointer?)",
    )
  }

  /**
   * The top-level nullable route, which fans out into `probe_has_value` / `probe_value`. Its own
   * locals are `__nuget_`-prefixed so nothing collides in the body, but both exports still declare
   * the user's `errorOut` beside the generator's, on both sides.
   */
  @Test
  fun `the top level two call route renames both out slot names`() {
    assertContains(
      result.generatedCSharp,
      "private static extern bool Probe_has_value(int errorOut_, int valueOut_, " +
          "out IntPtr error);",
    )
    assertContains(
      result.generatedCSharp,
      "private static extern int Probe_value(int errorOut_, int valueOut_, " +
          "out IntPtr error);",
    )
    assertContains(result.generatedCSharp, "public static int? Probe(int errorOut_, int valueOut_)")
    assertContains(
      result.generatedCSharp,
      "Probe_has_value(errorOut_, valueOut_, out IntPtr __nuget_hasValueError);",
    )
    assertContains(
      result.generatedCSharp,
      "Probe_value(errorOut_, valueOut_, out IntPtr __nuget_valueError);",
    )
    assertContains(result.generated, "probe(errorOut_, valueOut_) != null")
    assertContains(
      result.generated,
      "public fun export_probe_has_value(\n  errorOut_: Int,\n  valueOut_: Int,\n" +
          "  errorOut: COpaquePointer?,\n)",
    )
  }

  /**
   * The complement of the positive cells: no bare spelling survives at a parameter position
   * anywhere. Each entry is written so the renamed form is not a substring of it, and so the
   * generator's own slots (`IntPtr handle`, `out int valueOut`, `this string receiver`) are not
   * false positives.
   */
  @Test
  fun `no render site leaves a generator owned parameter name bare`() {
    val offenders: List<String> = (result.generatedCSharp.lines() + result.generated.lines())
      .map { line -> line.trim() }
      .filter { line -> BARE.any { bare -> line.contains(bare) } }

    assertEquals(emptyList(), offenders, "bare parameter names: ${offenders.joinToString("\n")}")
  }

  private companion object {
    val BARE: List<String> = listOf(
      "IntPtr handle, int handle,", "int handle_, int handle,",
      "string receiver, [MarshalAs(UnmanagedType.LPUTF8Str)] string receiver,",
      "int value, int value)", "int nativeResult,", "int valueOut, int hasValue,",
      "int errorOut, int valueOut,",
      "handle_: Int,\n  handle: Int", "receiver_: String,\n  receiver: String",
    )
  }
}
