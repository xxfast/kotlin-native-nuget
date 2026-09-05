package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ADR-082's 2026-08-08 amendment. The ratified inherited-member skip stays (a delegation forwarder
 * and an explicit override never export), but its signal narrows from simple names to signatures,
 * so an author-declared member whose name merely collides with a supertype member is no longer
 * over-dropped. Value-class export names gain the secondary-constructor overload numbering, which
 * is what lets a declared same-name pair coexist at the C symbol level at all.
 *
 * `@JvmInline` is only for the JVM harness (ADR-060's Tier 1 constraint); the exporter branches on
 * `Modifier.VALUE` alone. The delegation half of this behaviour is also guarded end to end by
 * `StoryUri` (cross-module) and `ChartId` (in-module) in `ValueClassDeclaredMemberTests.cs`.
 */
class Tier1ValueClassDeclaredMemberTest {

  /**
   * The over-drop this amendment fixes. `get(key: String)` shares `CharSequence.get(index: Int)`'s
   * kind, name and arity, but not its parameter type, so it is a declared, unrelated overload and
   * must export -- while the delegated `get`/`length` keep skipping under the same rule.
   */
  @Test
  fun `declared overload of an inherited member exports while the inherited signature skips`() {
    val result = Tier1Harness.run(
      """
      package tier1.valueclassdeclared

      @JvmInline
      value class Uri(val value: String) : CharSequence by value {
        fun get(key: String): String = value.substringAfter("${'$'}key=", "")
      }
      """.trimIndent(),
    )

    assertTrue(result.compiledClean, "expected Uri.get to compile; got: ${result.compileErrors}")
    assertContains(result.generated, "@CName(\"uri_get\")")
    assertFalse(
      result.generated.contains("@CName(\"uri_get_length\")"),
      "the delegated CharSequence.length is the inherited signature and must stay skipped; " +
          "generated=${result.generated}",
    )
    assertContains(result.generatedCSharp, "public string Get(string key)")
    assertTrue(
      result.kspWarnings.any { it.contains(ForwardDiagnosticKind.SKIPPED_INHERITED_MEMBER.name) },
      "expected the delegated CharSequence members to still be named as skips; " +
          "kspWarnings=${result.kspWarnings}",
    )
    assertFalse(
      result.kspWarnings.any {
        it.contains(ForwardDiagnosticKind.SKIPPED_INHERITED_MEMBER.name) && it.contains("Uri.get,")
      },
      "the declared get(key) overload exports now, so it must not also be reported as an " +
          "inherited-member skip; kspWarnings=${result.kspWarnings}",
    )
  }

  /**
   * Fix B. Two declared same-name members number their *symbols* and export names the way
   * secondary constructors do (`_2` from the second on), while the C# surface stays one natural
   * overload set. The Kotlin call sites must both say `describe`, not `describe_2`.
   */
  @Test
  fun `two declared overloads get numbered export names and one C-sharp overload set`() {
    val result = Tier1Harness.run(
      """
      package tier1.valueclassoverload

      @JvmInline
      value class ChartId(val value: String) {
        fun describe(): String = "Chart ${'$'}value"
        fun describe(prefix: String): String = "${'$'}prefix ${'$'}value"
      }
      """.trimIndent(),
    )

    assertTrue(result.compiledClean, "expected both overloads to compile; got: ${result.compileErrors}")

    val kotlin: String = result.generated
    assertContains(kotlin, "@CName(\"chartid_describe\")")
    assertContains(kotlin, "@CName(\"chartid_describe_2\")")
    assertContains(kotlin, "tier1.valueclassoverload.ChartId(value).describe()")
    assertContains(kotlin, "tier1.valueclassoverload.ChartId(value).describe(")
    assertFalse(
      kotlin.contains(".describe_2("),
      "the overload suffix belongs to the plan symbol, the C name and the export function name, " +
          "never to the Kotlin call site; generated=$kotlin",
    )

    val cs: String = result.generatedCSharp
    assertContains(cs, "EntryPoint = \"chartid_describe\"")
    assertContains(cs, "EntryPoint = \"chartid_describe_2\"")
    assertContains(cs, "public string Describe()")
    assertContains(cs, "public string Describe(string prefix)")
    assertContains(cs, "Native_Describe_2(Value, prefix)")
  }

  /**
   * The half of the ratified decision the narrowing must not erode: an explicit `override` *is*
   * the inherited signature, so it keeps skipping. Same name, same arity, same parameter type as
   * `CharSequence.get(index: Int)`.
   *
   * Issue #57: the diagnostic's hint used to say "declare the member directly on the value
   * class", which this code already does. The hint must name the rule (a supertype-declared
   * signature never exports) and ADR-082's real escape hatches instead.
   */
  @Test
  fun `explicit override of an inherited member stays skipped`() {
    val result = Tier1Harness.run(
      """
      package tier1.valueclassoverride

      @JvmInline
      value class Password(val value: String) : CharSequence by value {
        override fun get(index: Int): Char = value[index].uppercaseChar()
      }
      """.trimIndent(),
    )

    assertTrue(result.compiledClean, "expected Password to compile; got: ${result.compileErrors}")
    assertFalse(
      result.generated.contains("@CName(\"password_get\")"),
      "an explicit override is the inherited signature and must stay out of the export set; " +
          "generated=${result.generated}",
    )
    val warning: String? = result.kspWarnings.firstOrNull {
      it.contains(ForwardDiagnosticKind.SKIPPED_INHERITED_MEMBER.name) &&
          it.contains("Password.get")
    }
    assertNotNull(
      warning,
      "expected SKIPPED_INHERITED_MEMBER for the overridden member; " +
          "kspWarnings=${result.kspWarnings}",
    )
    assertFalse(
      warning.contains("declare the member directly"),
      "the hint must not recommend declaring a member the code already declares; warning=$warning",
    )
    assertContains(warning, "explicitly overridden")
    assertContains(warning, "underlying property")
    assertContains(warning, "no supertype declares")
  }

  /**
   * The wildcard half of the comparison. A supertype member's parameter that is a *type parameter*
   * (`Comparable<T>.compareTo(other: T)`, resolved off the `Comparable` declaration, so `T` is not
   * substituted) matches any argument type. Here that is also the right answer: `compareTo` is an
   * override, so it is the inherited signature. The amendment records the conservative cost of the
   * same rule (an unrelated same-arity overload in a type-parameter position over-drops).
   */
  @Test
  fun `supertype type parameter position matches any declared parameter type`() {
    val result = Tier1Harness.run(
      """
      package tier1.valueclasstypeparam

      @JvmInline
      value class Code(val value: String) : Comparable<Code> {
        override fun compareTo(other: Code): Int = value.compareTo(other.value)
      }
      """.trimIndent(),
    )

    assertTrue(result.compiledClean, "expected Code to compile; got: ${result.compileErrors}")
    assertFalse(
      result.generated.contains("@CName(\"code_compareTo\")"),
      "Comparable's parameter is the type parameter T, which matches Code conservatively, so " +
          "the override stays out of the export set; generated=${result.generated}",
    )
    assertTrue(
      result.kspWarnings.any { it.contains(ForwardDiagnosticKind.SKIPPED_INHERITED_MEMBER.name) },
      "expected SKIPPED_INHERITED_MEMBER for Code.compareTo; kspWarnings=${result.kspWarnings}",
    )
  }

  /**
   * Numbering makes the C symbols unique, but the C# surface still shares one public name, so the
   * ADR-034 fail-fast the duplicate-constructor check has needed an equivalent here. `tag(String)`
   * and `tag(String?)` are distinct Kotlin overloads that render `Tag(string)` and `Tag(string?)`:
   * identical C# signatures once reference nullability is normalized away (CS0111).
   */
  @Test
  fun `overloads colliding only on reference nullability fire ERROR_CSHARP_SIGNATURE_COLLISION`() {
    val result = Tier1Harness.run(
      """
      package tier1.valueclasscollision

      @JvmInline
      value class ChartId(val value: String) {
        fun tag(note: String): String = "${'$'}value ${'$'}note"
        fun tag(note: String?): String = "${'$'}value ${'$'}{note ?: "none"}"
      }
      """.trimIndent(),
    )

    assertTrue(
      result.kspErrors.any {
        it.contains(ForwardDiagnosticKind.ERROR_CSHARP_SIGNATURE_COLLISION.name) &&
            it.contains("ChartId.Tag")
      },
      "expected the named fatal kind for the two Tag overloads C# cannot tell apart; " +
          "kspErrors=${result.kspErrors}",
    )
  }
}
