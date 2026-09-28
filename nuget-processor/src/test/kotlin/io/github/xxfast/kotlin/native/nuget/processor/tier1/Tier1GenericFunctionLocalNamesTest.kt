package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The legacy generic top-level function route (`fun <T>`) hand-builds a C# wrapper body that
 * declares its own locals: `Type width` and `bool present` on the width dispatch, `IntPtr handle`,
 * `out bool owned` and `IntPtr result` on the object arm. A Kotlin parameter with one of those
 * names rebinds inside that body (CS0136). The generator must move **its own** local through
 * `freshName` (collision-only, as the suspend / Flow / callback routes already do) and leave the
 * user's parameter, which is a public named-argument label, untouched.
 *
 * Asserts the C# text and [Tier1Result.kspSucceeded], never [Tier1Result.compiledClean]: that only
 * compiles the generated Kotlin, so it is `true` for every colliding case here. The C# is compiled
 * for real by `IntegrationTests.KeywordRoutesTests`, against the mirror fixtures in
 * `test-library/.../test/routes/KeywordRoutesSample.kt`.
 */
class Tier1GenericFunctionLocalNamesTest {

  private val fixture: String = """
    package tier1.genericlocals

    fun <T> keep(owned: T): T = owned

    fun <T> hold(handle: T): T = handle

    fun <T> pick(result: T): T = result

    fun <T> span(width: T): T = width

    fun <T> seen(present: T): T = present

    class KeywordCollar(handle: Int) {
      val tag: Int = handle
    }
  """.trimIndent()

  private val result: Tier1Result by lazy { Tier1Harness.run(fixture) }

  private val generated: String by lazy { result.generatedCSharp }

  @Test
  fun `the colliding fixture is processed without a KSP error`() {
    assertTrue(result.kspSucceeded, "kspErrors=${result.kspErrors}")
  }

  @Test
  fun `a parameter named owned keeps its label and the object arm moves its owned local`() {
    assertContains(generated, "public static T Keep<T>(T owned)")
    assertContains(generated, "IntPtr handle = NugetMarshal.Wrap<T>(owned!, out bool owned_);")
    assertContains(generated, "if (owned_) NugetMarshal.Dispose(handle);")
    assertFalse(generated.contains("Wrap<T>(owned!, out bool owned)"), "owned local still collides")
  }

  @Test
  fun `a parameter named handle keeps its label and the object arm moves its handle local`() {
    assertContains(generated, "public static T Hold<T>(T handle)")
    assertContains(generated, "IntPtr handle_ = NugetMarshal.Wrap<T>(handle!, out bool owned);")
    assertContains(generated, "Hold_object_native(handle_, out error)")
    assertContains(generated, "if (owned) NugetMarshal.Dispose(handle_);")
  }

  @Test
  fun `a parameter named result keeps its label and the object arm moves its result local`() {
    assertContains(generated, "public static T Pick<T>(T result)")
    assertContains(generated, "IntPtr result_;")
    assertContains(
      generated,
      "result_ = NugetErrorNative.Check(Pick_object_native(handle, out error), error);",
    )
  }

  @Test
  fun `a parameter named width keeps its label and the dispatch moves its width local`() {
    assertContains(generated, "public static T Span<T>(T width)")
    assertContains(generated, "Type width_ = Nullable.GetUnderlyingType(typeof(T)) ?? typeof(T);")
    assertContains(generated, "bool present = width is not null;")
    assertContains(generated, "if (present && width_ == typeof(string))")
    assertContains(generated, "Span_string_native((string)(object)width!, out error)")
  }

  @Test
  fun `a parameter named present keeps its label and the dispatch moves its present local`() {
    assertContains(generated, "public static T Seen<T>(T present)")
    assertContains(generated, "bool present_ = present is not null;")
    assertContains(generated, "if (present_ && width == typeof(int))")
  }

  /**
   * The memo's unchecked adjacent site: the throwing constructor body
   * (`CirClassRenderer.renderConstructor`) declares `IntPtr handle` for the native result. Already
   * safe, and green: a constructor parameter reaches that body through `bridgeParameterName()`
   * (`handle` is in `PLAN_OWNED_NAMES`, because the Kotlin export declares it too), so the user's
   * `handle` is shifted to `handle_` on both halves and never meets the local. Pinned as it is, so
   * a fix to the generic route cannot quietly change it.
   */
  @Test
  fun `a constructor parameter named handle is shifted off the constructor body's handle local`() {
    assertContains(generated, "public KeywordCollar(int handle_)")
    assertContains(generated, "IntPtr handle = Native_Create(handle_, out IntPtr error);")
  }

  /**
   * `errorOut` is not a C# local: it is the Kotlin export's ADR-024 exception slot, and the ADR-055
   * contract check reads direction off the slot name. The raw name used to reach both halves and
   * KSP failed with a forward ABI mismatch; now both halves shift it to `errorOut_` (the plan
   * routes' spelling), while ordinary names such as `value` keep their label. Its own harness run,
   * so a failure here cannot mask the cells above.
   */
  @Test
  fun `a generic parameter named errorOut is processed`() {
    val marked: Tier1Result = Tier1Harness.run(
      """
      package tier1.genericerrorout

      fun <T> mark(errorOut: T): T = errorOut
      """.trimIndent(),
    )
    assertTrue(marked.kspSucceeded, "kspErrors=${marked.kspErrors}")
    assertContains(marked.generatedCSharp, "public static T Mark<T>(T errorOut_)")
  }
}
