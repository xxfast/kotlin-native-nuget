package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-111: a sealed subclass's properties are planned by `ForwardPropertyPlanner` and projected by
 * the shared emitter/projection, exactly like an ordinary class's. Closes ROADMAP:26-29 (the
 * nullable-enum `.ordinal` compile error, the double native call on a nullable reference, the
 * missing `[return: MarshalAs(UnmanagedType.I1)]`, the swallowed error slot) and the sealed half of
 * ROADMAP:37 (a type the legacy `isReferenceType` skip list had no arm for).
 */
class Tier1SealedSubclassPropertyPlanTest {

  private val source: String = """
    package tier1.sealedplan

    import kotlin.time.Duration

    enum class Mood { CALM, HUNGRY }

    class Friend(val name: String)

    sealed class Signal {
      abstract val id: String

      val tag: String get() = "signal:${'$'}id"

      data class Ping(
        override val id: String,
        val mood: Mood?,
        val friend: Friend?,
        val flag: Boolean,
        val maybeFlag: Boolean?,
        val age: Duration,
      ) : Signal() {
        var counter: Int = 0
      }

      class Boom : Signal() {
        override val id: String get() = throw IllegalStateException("nope")
      }
    }

    fun signal(): Signal = Signal.Boom()
  """.trimIndent()

  private fun run(): Tier1Result = Tier1Harness.run(source)

  @Test
  fun `the generated Kotlin compiles`() {
    val result = run()

    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")
  }

  /**
   * ROADMAP:26. The legacy enum arm tested `isEnumType` before nullability and spelled
   * `$access.ordinal` unconditionally, so `Mood?` did not compile at all. The plan gives it the
   * ADR-002 has-value pair every nullable non-pointer type takes.
   */
  @Test
  fun `a nullable enum property reads over the has-value pair`() {
    val result = run()

    assertContains(
      result.generated,
      "public fun export_signal_ping_get_mood(handle: COpaquePointer, errorOut: COpaquePointer?): Boolean",
    )
    assertContains(
      result.generated,
      "public fun export_signal_ping_get_mood_value(handle: COpaquePointer, errorOut: COpaquePointer?): Int",
    )
    assertFalse(
      result.generated.contains(".mood.ordinal"),
      "expected no unconditional `.ordinal` on a nullable enum receiver",
    )
    assertContains(result.generatedCSharp, "public global::Interop.Mood? Mood")
    assertContains(result.generatedCSharp, "                return (global::Interop.Mood)value;")
  }

  /**
   * ADR-111 Consequences: the presence export is the planner's bare `${'$'}{prefix}_get_x`, not the
   * legacy `${'$'}{prefix}_get_x_has_value`. Both halves are generated together, so no consumer sees
   * the rename.
   */
  @Test
  fun `the presence export takes the plan's name, not the legacy _has_value one`() {
    val result = run()

    assertFalse(
      result.generated.contains("_has_value"),
      "expected the legacy `_has_value` presence export name to be gone",
    )
    assertFalse(result.generatedCSharp.contains("_has_value"), "same, on the C# half")
  }

  /**
   * ROADMAP:27. The legacy nullable-reference getter called the export twice, so the null test and
   * the wrapped handle came from two different `StableRef.create`s and one leaked.
   */
  @Test
  fun `a nullable reference property makes exactly one native call and checks the error slot`() {
    val result = run()

    assertContains(
      result.generatedCSharp,
      """
      |            public global::Interop.Friend? Friend
      |            {
      |                get
      |                {                IntPtr nativeResult = Native_Get_friend(_handle, out IntPtr error);
      |                if (error != IntPtr.Zero)
      |                {
      |                    throw NugetErrorNative.BuildException(error);
      |                }
      |                return nativeResult == IntPtr.Zero ? null : new global::Interop.Friend(nativeResult);
      """.trimMargin(),
    )
  }

  /**
   * ROADMAP:28. Kotlin returns a 1-byte `Boolean`; an unattributed C# `bool` marshals 4 bytes.
   * Every `bool`-returning import here carries the attribute, including the `_value` half of a
   * `Boolean?` pair, which the legacy renderer attributed on the presence import only.
   */
  @Test
  fun `every bool import carries the I1 return marshal`() {
    val result = run()

    val boolNatives: List<String> =
      listOf("Native_Get_flag", "Native_Get_maybeFlag", "Native_Get_maybeFlag_value")
    boolNatives.forEach { native ->
      assertContains(
        result.generatedCSharp,
        """
        |            [return: MarshalAs(UnmanagedType.I1)]
        |            private static extern bool $native(IntPtr handle, out IntPtr error);
        """.trimMargin(),
      )
    }
  }

  /**
   * ROADMAP:29. Every non-collection getter used to pass `out _`, so a throwing custom getter
   * returned the wire default (`""` for a String) instead of surfacing the exception.
   */
  @Test
  fun `a throwing String getter surfaces the exception instead of the default`() {
    val result = run()

    assertContains(
      result.generatedCSharp,
      """
      |            public override string Id
      |            {
      |                get
      |                {                IntPtr nativeResult = Native_Get_id(_handle, out IntPtr error);
      |                if (error != IntPtr.Zero)
      |                {
      |                    throw NugetErrorNative.BuildException(error);
      |                }
      |                return Marshal.PtrToStringUTF8(nativeResult)!;
      """.trimMargin(),
    )
  }

  /**
   * The sealed half of ROADMAP:37: `Duration` binds on an ordinary class but the legacy
   * `isReferenceType` dispatch had no arm for it, so it was absent from C# with an unactionable
   * `SKIPPED_UNSUPPORTED_TYPE` hint.
   */
  @Test
  fun `a Duration property on a sealed subclass now binds`() {
    val result = run()

    assertContains(result.generatedCSharp, "public global::System.TimeSpan Age")
    assertContains(
      result.generatedCSharp,
      "private static extern long Native_Get_age(IntPtr handle, out IntPtr error);",
    )
  }

  /** ADR-111 Consequences: legacy always passed `setter = null`. */
  @Test
  fun `a var on a sealed subclass gains a setter`() {
    val result = run()

    assertContains(
      result.generated,
      "public fun export_signal_ping_set_counter(",
    )
    assertContains(
      result.generatedCSharp,
      "private static extern void Native_Set_counter(IntPtr handle, int value, out IntPtr error);",
    )
    assertContains(
      result.generatedCSharp,
      """
      |                set
      |                {                Native_Set_counter(_handle, value, out IntPtr error);
      """.trimMargin(),
    )
  }

  /**
   * ADR-111 amendment (2026-09-11): a property the sealed *base* declares binds on the **base**,
   * not flattened onto every arm. Until item 35 the arms were planned with `superClass = null`, so
   * each one re-bound every implemented base property; the generated C# base carried nothing, and
   * a base-typed consumer could read nothing without pattern-matching first.
   *
   * The flip this test records: `signal_ping_get_tag` / `signal_boom_get_tag` are gone, replaced
   * by the one `signal_get_tag`. An arm still binds what it **declares** itself, which is why
   * `id` survives on both arms (each declares an `override`) beside the base's own.
   */
  @Test
  fun `base-declared properties bind on the base, and arms bind only what they declare`() {
    val result = run()

    // The base carries both of its own declared properties, once.
    assertContains(result.generated, "@CName(\"signal_get_id\")")
    assertContains(result.generated, "@CName(\"signal_get_tag\")")
    assertContains(result.generatedCSharp, "EntryPoint = \"signal_get_tag\"")
    assertContains(result.generatedCSharp, "public virtual string Tag")

    // Each arm declares its own `override val id`, so each keeps its own export for that one.
    listOf("signal_ping", "signal_boom").forEach { prefix ->
      assertContains(result.generated, "@CName(\"${prefix}_get_id\")")
    }

    // `tag` is declared on the base alone and is no longer copied onto the arms.
    assertFalse(
      result.generated.contains("signal_ping_get_tag") ||
          result.generated.contains("signal_boom_get_tag"),
      "a base-declared property binds on the base now, not on every arm: ${result.generated}",
    )
    assertFalse(
      result.generatedCSharp.contains("signal_boom_get_tag"),
      "same, on the C# half: ${result.generatedCSharp}",
    )
  }

  /**
   * ADR-111 Scope: a lambda-typed property has no plan shape, so both halves keep their legacy arm
   * (a raw `[DllImport]` and a `KotlinFunc<...>` getter that still swallows the error slot) until
   * lambda properties migrate for ordinary classes too. Pinned so the residual route stays
   * exercised now that nothing else falls into it.
   */
  @Test
  fun `a lambda property on a sealed subclass stays on the legacy route`() {
    val result = Tier1Harness.run(
      """
      package tier1.sealedlambda

      sealed class Handler {
        class OnTap(val onTap: (Int) -> String) : Handler()
      }

      fun handler(): Handler = Handler.OnTap { it.toString() }
      """.trimIndent()
    )

    assertContains(
      result.generatedCSharp,
      """
      |            [DllImport("library", CallingConvention = CallingConvention.Cdecl, EntryPoint = "handler_ontap_get_onTap")]
      |            private static extern IntPtr Native_Get_onTap(IntPtr handle, out IntPtr error);
      """.trimMargin(),
    )
    assertContains(
      result.generatedCSharp,
      "            public KotlinFunc<int, string> OnTap => new KotlinFunc<int, string>(Native_Get_onTap(_handle, out _));",
    )
    assertContains(result.generated, "@CName(\"handler_ontap_get_onTap\")")
  }
}
