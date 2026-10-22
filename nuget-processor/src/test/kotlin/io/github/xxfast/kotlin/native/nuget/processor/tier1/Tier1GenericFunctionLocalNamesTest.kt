package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Parameters of a top-level `fun <T>` named like a generator local (`owned`, `handle`, `result`,
 * `width`, `present`). Until ADR-213 these bound on the legacy width-variant route, whose
 * hand-built C# body declared those locals and had to move its own through `freshName` (CS0136).
 * That route is deleted; these cells pin that what it was taught is the plan's own behaviour: the
 * user's parameter, a public named-argument label, is untouched (the plan-owned `handle` too, at
 * top level only), and the C# builds.
 *
 * The C# is also compiled by `IntegrationTests.KeywordRoutesTests`, against the mirror fixtures in
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

  private val result: Tier1Result by lazy {
    Tier1Harness.run(
      fixture,
      fileName = "Locals.kt",
      processorOptions = mapOf("nuget.rootPackage" to "tier1"),
    )
  }

  private val generated: String by lazy { result.generatedCSharp }

  @Test
  fun `the colliding fixture is processed without a KSP error`() {
    assertTrue(result.kspSucceeded, "kspErrors=${result.kspErrors}")
  }

  @Test
  fun `every local-named parameter keeps its label`() {
    assertContains(generated, "public static T Keep<T>(T owned)")
    // ADR-213: `handle` is plan-owned (`PLAN_OWNED_NAMES`) and shifts in the Kotlin export and the
    // extern, but a top-level `fun <T>` keeps the public label the width route shipped.
    assertContains(generated, "private static extern IntPtr Native_Hold(IntPtr handle_, out IntPtr error);")
    assertContains(generated, "public static T Hold<T>(T handle)")
    assertContains(generated, "public static T Pick<T>(T result)")
    assertContains(generated, "public static T Span<T>(T width)")
    assertContains(generated, "public static T Seen<T>(T present)")
  }

  @Test
  fun `every local-named function binds as one plan export with no width variants`() {
    listOf("keep", "hold", "pick", "span", "seen").forEach { name ->
      assertContains(generated, "EntryPoint = \"library_genericlocals__$name\"")
      assertFalse(generated.contains("__${name}_"), "$name still has width variants")
    }
  }

  @Test
  fun `the local-named functions compile in C# called by their labels`() {
    Tier1CSharpCompile.assertCompiles(
      result,
      """
      using Interop.Genericlocals;

      namespace Consumer
      {
          public static class Probe
          {
              public static int Run()
              {
                  int owned = Locals.Keep(owned: 1);
                  string handle = Locals.Hold(handle: "oreo");
                  int? result = Locals.Pick<int?>(result: null);
                  long width = Locals.Span(width: 3L);
                  bool present = Locals.Seen(present: true);
                  return owned + handle.Length + (result ?? 0) + (int)width + (present ? 1 : 0);
              }
          }
      }
      """.trimIndent(),
    )
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
