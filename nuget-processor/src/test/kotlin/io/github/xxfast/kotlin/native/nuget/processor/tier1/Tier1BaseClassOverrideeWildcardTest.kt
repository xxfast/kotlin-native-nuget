package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The other side of [Tier1KeptBaseInterfaceListTest]'s overload cell: `baseClassOverridee` matches
 * a base-class function by *signature*, and a base-side type parameter position admits any
 * subclass type (ADR-082's wildcard).
 *
 * Both cells here are green today and must stay green under the signature key. They are the
 * executable form of the hazard the fix had to avoid: a strict `forwardSignatureKey()` in the
 * fallback would key the base's unsubstituted `f(x: T)` as `["f", "T"]` against the subclass's
 * `["f", "kotlin.String"]`, find no match, and flip a real override to `virtual`, which is CS0506
 * against a base member that is `virtual`, and CS0114 when it is not.
 *
 * Since ADR-147 a generic class carries its own methods on the C# generic carrier, so `Base<T>`
 * really does spell an `F` for the subclass to override; before ADR-147 these cells pinned
 * generator text against a carrier that had no `F` at all.
 */
class Tier1BaseClassOverrideeWildcardTest {

  /** Cell B: a bare type-parameter position, `Base<T>.f(x: T)` over `Sub.f(x: String)`. */
  @Test
  fun `an override of a generic base's type-parameter position stays an override`() {
    val result = Tier1Harness.run(
      """
      package tier1.wildcard.bare

      open class Base<T>(val item: T) {
        open fun f(x: T): String = "base:${'$'}x"
      }

      class Sub(item: String) : Base<String>(item) {
        override fun f(x: String): String = "sub:${'$'}x"
      }
      """.trimIndent(),
      processorOptions = mapOf("nuget.rootPackage" to "tier1.wildcard.bare"),
    )

    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    assertContains(result.generatedCSharp, "public override string F(string x)")
    assertFalse(
      "public virtual string F(string x)" in result.generatedCSharp,
      "`Sub` really does override `Base<T>.f`, and the base carries the slot; generated C#:" +
          "\n${result.generatedCSharp}",
    )
  }

  /**
   * Cell B2: the type parameter one level down, `Base<T>.f(x: List<T>)` over
   * `Sub.f(x: List<String>)`. This is the shape `mentionsTypeParameter()` exists for: the keys now
   * recurse into type arguments, so `kotlin.collections.List<T>` and
   * `kotlin.collections.List<kotlin.String>` differ unless the wildcard is structural too.
   */
  @Test
  fun `an override of a generic base's type-parameter argument stays an override`() {
    val result = Tier1Harness.run(
      """
      package tier1.wildcard.argument

      open class Base<T>(val item: T) {
        open fun f(xs: List<T>): String = "base:${'$'}{xs.size}"
      }

      class Sub(item: String) : Base<String>(item) {
        override fun f(xs: List<String>): String = "sub:${'$'}{xs.size}"
      }
      """.trimIndent(),
      processorOptions = mapOf("nuget.rootPackage" to "tier1.wildcard.argument"),
    )

    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    assertContains(result.generatedCSharp, "public override string F(IReadOnlyList<string> xs)")
    assertFalse(
      "public virtual string F(IReadOnlyList<string> xs)" in result.generatedCSharp,
      "a base-side `List<T>` admits the subclass's `List<String>`; generated C#:" +
          "\n${result.generatedCSharp}",
    )
  }
}
