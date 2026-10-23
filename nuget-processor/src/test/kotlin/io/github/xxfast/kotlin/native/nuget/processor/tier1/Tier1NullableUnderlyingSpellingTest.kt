package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

/**
 * Two nullable underlyings:
 * - `value class Opt<T>(val value: T?)` closed at a value type is spelled with the nullable
 *   argument (`Opt<int?>`), because C# reads an unconstrained `T?` as `T` for a value type and
 *   `Opt<int>` would have no null to hold; a reference type stays as is (`Opt<string>`).
 * - a non-generic value class over a nullable collection (`Maybe(val items: List<Int>?)`) binds as
 *   a parameter, a result, under `?` (its box, so `null` and `Maybe(null)` stay apart) and at an
 *   erased position.
 */
class Tier1NullableUnderlyingSpellingTest {

  private val fixture: String = """
    package tier1.nullables

    class Box<T>(val item: T)

    @JvmInline
    value class Opt<T>(val value: T?)

    @JvmInline
    value class Maybe(val items: List<Int>?)

    fun optNone(): Opt<Int> = Opt(null)
    fun optSome(value: Int): Opt<Int> = Opt(value)
    fun optState(opt: Opt<Int>): String = opt.value?.toString() ?: "none"
    fun optNamed(): Opt<String> = Opt(null)
    fun optName(opt: Opt<String>): String = opt.value ?: "none"
    fun <T> optOf(value: T?): Opt<T> = Opt(value)

    fun maybeOf(present: Boolean): Maybe = Maybe(if (present) listOf(1) else null)
    fun maybeSize(maybe: Maybe): Int = maybe.items?.size ?: -1
    fun maybeMaybe(state: Int): Maybe? = if (state == 0) null else Maybe(if (state == 1) null else listOf(state))
    fun maybeState(maybe: Maybe?): String =
      if (maybe == null) "absent" else if (maybe.items == null) "empty" else "full"
    fun boxedMaybe(present: Boolean): Box<Maybe> = Box(Maybe(if (present) listOf(2) else null))
    fun boxedMaybeSize(box: Box<Maybe>): Int = box.item.items?.size ?: -1
  """.trimIndent()

  private val consumer: String = """
    using System.Collections.Generic;
    using Tier1;
    public static class Consumer
    {
        public static void Touch()
        {
            Opt<int?> none = Nullables.OptNone();
            int? missing = none.Value;
            Opt<int?> some = Nullables.OptSome(3);
            string state = Nullables.OptState(new Opt<int?>(null));
            Opt<string> named = Nullables.OptNamed();
            string? name = named.Value;
            string nameOf = Nullables.OptName(new Opt<string>(null));
            Opt<long> open = Nullables.OptOf<long>(4);
            Maybe maybe = Nullables.MaybeOf(false);
            IReadOnlyList<int>? items = maybe.Items;
            int size = Nullables.MaybeSize(new Maybe(null));
            Maybe? maybeMaybe = Nullables.MaybeMaybe(1);
            string maybeState = Nullables.MaybeState(null);
            using Box<Maybe> boxed = Nullables.BoxedMaybe(false);
            int boxedSize = Nullables.BoxedMaybeSize(boxed);
        }
    }
  """.trimIndent()

  private val result: Tier1Result by lazy {
    Tier1Harness.run(
      fixture,
      fileName = "Nullables.kt",
      processorOptions = mapOf("nuget.namespace" to "Tier1"),
    )
  }

  private val code: String by lazy { result.generatedCSharp.withoutDocComments() }

  @Test
  fun `the Kotlin half compiles clean`() {
    assertTrue(result.kspErrors.isEmpty(), "expected no KSP error; got: ${result.kspErrors}")
    assertTrue(
      result.compiledClean,
      "expected the fixture to compile; got: ${result.compileErrors}",
    )
  }

  @Test
  fun `a value type argument under a nullable own T is spelled nullable`() {
    assertContains(code, "Opt<int?> OptNone()")
    assertContains(code, "string OptState(global::Tier1.Opt<int?> opt)")
  }

  @Test
  fun `a reference type argument keeps its spelling`() {
    assertContains(code, "Opt<string> OptNamed()")
    assertContains(code, "string OptName(global::Tier1.Opt<string> opt)")
  }

  @Test
  fun `a nullable collection underlying binds at every position`() {
    assertContains(code, "public readonly record struct Maybe(IReadOnlyList<int>? Items)")
    assertContains(code, "Maybe? MaybeMaybe(int state)")
    assertContains(code, "Box<global::Tier1.Maybe> BoxedMaybe(bool present)")
  }

  @Test
  fun `no member of the fixture skips`() {
    val skipped: List<String> =
      result.kspWarnings.filter { warning -> "[nuget:SKIPPED_" in warning }
    assertTrue(skipped.isEmpty(), "expected every member bound; skipped=$skipped")
  }

  @Test
  fun `a consumer of every position compiles against the generated C#`() {
    val build: Tier1CSharpBuild = Tier1CSharpCompile.compile(result, consumer, allowUnsafe = true)
    assertTrue(build.succeeded, "expected the consumer to compile; log:\n${build.log}")
  }
}
