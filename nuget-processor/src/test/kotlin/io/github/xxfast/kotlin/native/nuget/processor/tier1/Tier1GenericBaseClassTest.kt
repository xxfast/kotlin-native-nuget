package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-101 amendment (2026-09-11): a class extending an exported *generic* class renders the base
 * with its type arguments spelled, `public class StringCrate : Crate<string>`.
 *
 * The translator used to spell a base by name alone, so a closed generic base came out as the
 * bare `: Crate`, which is CS0305 at best and CS0118 when the library's namespace shares the
 * base's name. The second half is the base's `Dispose`: a derived class always renders `public
 * override void Dispose()` (ADR-101, 2026-09-10), so the generic wrapper has to say `virtual`
 * for that to compile.
 *
 * Inherited members stay on the base: `Value` is declared by `Crate<T>` and must not be re-bound as
 * a `StringCrate` export, or the derived C# property would hide the base's own.
 */
class Tier1GenericBaseClassTest {

  @Test
  fun `a class extending a generic base spells the base's type arguments`() {
    val result = Tier1Harness.run(
      """
      package tier1.genericbase

      open class Crate<T>(val value: T)

      class StringCrate(label: String) : Crate<String>(label) {
        fun own(): String = "own:${'$'}value"
      }
      """.trimIndent(),
    )

    assertTrue(
      result.compiledClean,
      "expected the generic base exports to compile; got: ${result.compileErrors}",
    )

    val cs: String = result.generatedCSharp
    assertContains(cs, "public class StringCrate : Crate<string>")
    assertFalse(
      cs.contains("public class StringCrate : Crate\n") ||
        cs.contains("public class StringCrate : Crate "),
      "the base must not be spelled without its type arguments; got: $cs",
    )
    assertContains(cs, "public virtual void Dispose()")
    assertFalse(
      cs.contains("public override string Value"),
      "an inherited property must stay on the generic base, not be re-bound as an override; " +
          "got: $cs",
    )
    assertContains(cs, "public override void Dispose()")

    val kotlin: String = result.generated
    assertFalse(
      "export_stringcrate_get_value" in kotlin,
      "an inherited property must stay on the base, not be re-bound on the subclass; got: $kotlin",
    )
    assertContains(kotlin, "@CName(\"stringcrate_own\")")
  }
}
