package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-101 amendment (2026-09-13), sibling of [Tier1GenericBaseClassTest]: when a generic base's
 * function mentions the type parameter, KSP hands the subclass a *substituted* copy parented to
 * the subclass. `isDeclaredBy` matches the declared list by simple name only, so that substituted
 * `describe(String)` is mistaken for the subclass's own `describe(Int)` and rendered on it too.
 *
 * The subclass must declare exactly one `Describe`, the `Int` overload it really declares, with no
 * `override` (the generic base projects properties only, so there is nothing to override), and the
 * Kotlin side must mint exactly one `labelledcrate_describe` export rather than a numbered pair.
 */
class Tier1GenericBaseOverloadTest {

  @Test
  fun `a substituted generic-base function is not re-declared on the subclass overloading it`() {
    val result = Tier1Harness.run(
      """
      package tier1.genericbase.overload

      open class Crate<T>(val item: T) {
        fun describe(tag: T): String = "${'$'}tag:${'$'}item"
      }

      class LabelledCrate(item: String) : Crate<String>(item) {
        fun describe(tag: Int): String = "#${'$'}tag:${'$'}item"
      }
      """.trimIndent(),
    )

    assertTrue(
      result.compiledClean,
      "expected the generic-base overload exports to compile; got: ${result.compileErrors}",
    )

    val cs: String = result.generatedCSharp
    // ADR-147: two now. `Crate<T>` carries its own `Describe(T tag)` on the generic carrier, and
    // `LabelledCrate` still declares exactly its own `Describe(int tag)`; the substituted
    // `describe(String)` is inherited through `: Crate<string>`, never re-declared.
    val describes: Int = cs.split("public string Describe(").size - 1
    assertEquals(
      2, describes,
      "the base declares Describe(T) and the subclass Describe(int); got: $cs",
    )
    assertContains(cs, "public string Describe(T tag)")
    assertContains(cs, "public string Describe(int tag)")
    assertFalse(
      "Describe(string" in cs,
      "the substituted String overload is inherited from Crate<string>, never re-declared; " +
          "got: $cs",
    )
    assertFalse(
      "override string Describe" in cs,
      "the declared overload overrides nothing on the generic base; got: $cs",
    )
    assertFalse(
      "labelledcrate_describe_2" in cs,
      "no numbered entry point may be minted for a member the subclass does not declare; got: $cs",
    )

    val kotlin: String = result.generated
    val exports: Int = kotlin.lines()
      .count { "@CName(" in it && "labelledcrate_describe" in it }
    assertEquals(
      1,
      exports,
      "exactly one labelledcrate_describe export must be minted, with no numbered sibling; " +
          "got: $kotlin",
    )
  }

  /**
   * The same bug one level down: the two overloads differ only in a *type argument*, so
   * `forwardTypeKey()` keying the outer declaration alone spells both `kotlin.collections.List`
   * and the substituted `describe(List<String>)` is again mistaken for the declared
   * `describe(List<Int>)`.
   *
   * `@JvmName` is for the JVM harness only (the two overloads share a JVM erasure signature); the
   * keys never read it, so the spelling under test is the shipped one.
   */
  @Test
  fun `a substituted generic-base function differing only in a type argument is not re-declared`() {
    val result = Tier1Harness.run(
      """
      package tier1.genericbase.typeargs

      open class Crate<T>(val item: T) {
        fun describe(tags: List<T>): String = "${'$'}tags:${'$'}item"
      }

      class LabelledCrate(item: String) : Crate<String>(item) {
        @JvmName("describeInts")
        fun describe(tags: List<Int>): String = "#${'$'}tags:${'$'}item"
      }
      """.trimIndent(),
    )

    assertTrue(
      result.compiledClean,
      "expected the type-argument overload exports to compile; got: ${result.compileErrors}",
    )

    val cs: String = result.generatedCSharp
    val describes: Int = cs.split("public string Describe(IReadOnlyList<").size - 1
    assertEquals(
      1, describes,
      "the subclass declares exactly one list-taking Describe; got: $cs",
    )
    assertContains(cs, "public string Describe(IReadOnlyList<int> tags)")
    assertFalse(
      "Describe(IReadOnlyList<string>" in cs,
      "the substituted List<string> overload is inherited from Crate<string>, never re-declared; " +
          "got: $cs",
    )
    assertFalse(
      "labelledcrate_describe_2" in cs,
      "no numbered entry point may be minted for a member the subclass does not declare; got: $cs",
    )

    val kotlin: String = result.generated
    val exports: Int = kotlin.lines()
      .count { "@CName(" in it && "labelledcrate_describe" in it }
    assertEquals(
      1,
      exports,
      "exactly one labelledcrate_describe export must be minted, with no numbered sibling; " +
          "got: $kotlin",
    )
  }

  /**
   * The other half of the same key (memo finding 6, green today and must stay green): ADR-082's
   * value-class supertype wildcard matched `List<T>` against `List<String>` only because both
   * spelled `kotlin.collections.List`. Once the key recurses into arguments, the wildcard has to
   * recurse with it, or this delegated member leaks out of `SKIPPED_INHERITED_MEMBER` and renders
   * a delegation forwarder on the value class.
   */
  @Test
  fun `a value class member whose supertype spells a type-parameter argument stays inherited`() {
    val result = Tier1Harness.run(
      """
      package tier1.valueclass.typeargs

      interface Bag<T> {
        fun holds(items: List<T>): Int
      }

      @JvmInline
      value class Tags(val value: String) : Bag<String> {
        override fun holds(items: List<String>): Int = items.size + value.length
      }
      """.trimIndent(),
    )

    assertTrue(result.compiledClean, "expected Tags to compile; got: ${result.compileErrors}")
    assertFalse(
      result.generated.contains("tags_holds"),
      "the supertype declares holds(List<T>), so the value class member is inherited and must " +
          "never export; generated=${result.generated}",
    )
    assertTrue(
      result.kspWarnings.any {
        it.contains(ForwardDiagnosticKind.SKIPPED_INHERITED_MEMBER.name) && it.contains("holds")
      },
      "expected SKIPPED_INHERITED_MEMBER naming Tags.holds; kspWarnings=${result.kspWarnings}",
    )
  }
}
