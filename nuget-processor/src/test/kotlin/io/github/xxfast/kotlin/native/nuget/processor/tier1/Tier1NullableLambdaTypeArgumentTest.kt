package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Boundary nullability part A1: the returned-lambda route (`nuget_funcN_invoke`), where Kotlin
 * hands C# a lambda and C# calls `Invoke`.
 *
 * Two halves, and fixing either alone leaves the feature unusable. The SPELLING: `csTypeArgument`
 * never read `isMarkedNullable`, so `(String?) -> Unit` and `(String) -> Unit` rendered identically
 * as `KotlinAction<string>`, and `(Int?) -> Unit` as `KotlinFunc<int, ...>` where null is not
 * expressible at all, with no diagnostic in between. The CROSSING: the private `WrapArg<T>` copy
 * passed a null string straight into `export_nuget_wrap_string(value: String)` and threw
 * `NotSupportedException` for `int?`, while the invoke exports took non-null `COpaquePointer` and
 * did `fn.invoke(...) as Any` on the way back -- each an uncaught Kotlin NPE inside an export with
 * no error slot, i.e. a host-process death, not a catchable exception.
 *
 * The route binds at a TOP-LEVEL function return only (a class method returning a lambda is
 * `SKIPPED_UNSUPPORTED_RETURN`), so the fixture is top-level.
 */
class Tier1NullableLambdaTypeArgumentTest {

  @Test
  fun `a lambda type argument carries its own nullability into the C# spelling`() {
    val result = Tier1Harness.run(
      """
      package tier1.nullablelambdaspelling

      fun recorder(): (String?) -> Unit = { }

      fun describer(): (Int?) -> String = { if (it == null) "none" else "n=${'$'}it" }

      fun finder(): (String) -> String? = { if (it == "Oreo") it else null }

      fun plain(): (String) -> Unit = { }
      """.trimIndent(),
    )

    assertTrue(
      result.compiledClean,
      "expected the lambda-returning functions to bind; got: ${result.compileErrors}",
    )

    val cs: String = result.generatedCSharp
    assertTrue("KotlinAction<string?> Recorder()" in cs, "a nullable reference payload; cs=$cs")
    assertTrue("KotlinFunc<int?, string> Describer()" in cs, "a nullable VALUE payload; cs=$cs")
    assertTrue("KotlinFunc<string, string?> Finder()" in cs, "a nullable RESULT; cs=$cs")
    // The control: a non-null lambda keeps the exact shipped spelling, so this is a nullability
    // read and not a blanket `?`.
    assertTrue("KotlinAction<string> Plain()" in cs, "a non-null payload is unchanged; cs=$cs")
  }

  @Test
  fun `Invoke boxes through NugetMarshal Wrap and disposes what it owns`() {
    val result = Tier1Harness.run(
      """
      package tier1.nullablelambdawrap

      fun describer(): (Int?) -> String = { "n=${'$'}it" }
      """.trimIndent(),
    )

    val cs: String = result.generatedCSharp
    // The private copy is gone, on both the sync and the suspend helper.
    assertFalse("WrapArg<" in cs, "the private WrapArg<T> copy must not be emitted any more")
    assertTrue(
      "Wrap<T1>(arg0, out bool owned0);" in cs,
      "expected the argument boxed through NugetMarshal.Wrap with its ownership report; cs=$cs",
    )
    // The leak half: one StableRef per argument per call was minted and never released by anyone.
    assertTrue(
      "if (owned0) " in cs && "Dispose(boxedArg0);" in cs,
      "expected the owned box disposed after the call; cs=$cs",
    )
    // Ordering is load-bearing: the export dereferences the box synchronously inside Invoke.
    assertTrue(
      cs.indexOf("Wrap<T1>(arg0, out bool owned0);") < cs.indexOf("if (owned0) "),
      "the box must be disposed AFTER the native call, never before",
    )
  }

  /**
   * The SUSPEND twin (`nuget_suspend_funcN_invoke`), which has its own copy of every one of the
   * above: its own `WrapArg<T>`, its own spelling path, its own invoke exports. A fix applied to
   * one copy and not the other leaves this route broken, so it is its own cell rather than an
   * assumption.
   *
   * The suspend lambda route binds at a PROPERTY (`val onFeed: suspend (String) -> String`), not
   * at a function return, which is why this fixture differs in shape from the two above.
   */
  @Test
  fun `the suspend lambda twin carries nullability and boxes through Wrap too`() {
    val result = Tier1Harness.run(
      """
      package tier1.nullablesuspendlambda

      class Feeder(val catName: String) {
        val onFeed: suspend (String?) -> String = { food -> "${'$'}catName ate ${'$'}food" }

        val onNote: suspend (Int) -> String? = { grams -> if (grams > 0) "${'$'}grams g" else null }

        val onPlain: suspend (String) -> String = { food -> food }
      }
      """.trimIndent(),
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )

    assertTrue(
      result.compiledClean,
      "expected the suspend lambda properties to bind; got: ${result.compileErrors}",
    )

    val cs: String = result.generatedCSharp
    assertTrue("KotlinSuspendFunc<string?, string> OnFeed" in cs, "nullable payload; cs=$cs")
    assertTrue("KotlinSuspendFunc<int, string?> OnNote" in cs, "nullable result; cs=$cs")
    // The control, unchanged.
    assertTrue("KotlinSuspendFunc<string, string> OnPlain" in cs, "non-null is unchanged; cs=$cs")
    assertFalse("WrapArg<" in cs, "the suspend helper's own WrapArg<T> copy must be gone too")
    assertTrue(
      "Wrap<T1>(arg0, out bool owned0);" in cs,
      "expected InvokeAsync to box through NugetMarshal.Wrap; cs=$cs",
    )
    // The disposal is safe ONLY because the suspend export reads every box synchronously, before
    // `launchForCSharp`; the generated order is what pins that.
    assertTrue(
      cs.indexOf("Wrap<T1>(arg0, out bool owned0);") < cs.indexOf("if (owned0) "),
      "the box must be disposed after the native call returns, never inside the continuation",
    )
  }
}
