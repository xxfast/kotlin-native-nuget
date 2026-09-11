package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-101 amendment (2026-09-11): a class with **both** an exported base class and its own
 * interface keeps both halves of its base list.
 *
 * `class Ledge : Shelf(), Groomable` used to render `public class Ledge : Shelf` alone: keeping a
 * base emptied the interface list outright, and with it went every inherited member, including
 * `Groomable.brushes()`'s default body. The visible half was the missing `: IGroomable`, but the
 * shape did not compile either way: `override` was read off the Kotlin modifier rather than off an
 * overridee, so `Ledge.groom()` (which overrides an *interface* member `Shelf` knows nothing
 * about) rendered `public override string Groom()`, CS0115.
 *
 * The three cells below are the fast red for the three coupled edits: the base list, the
 * inherited-default binding, and `virtual` vs `override`. `IntegrationTests`'
 * `InterfaceBesideBaseTests` is the consumer proof; Tier 1 never compiles C#.
 */
class Tier1KeptBaseInterfaceListTest {

  private val fixture: String = """
    package tier1.keptbase

    open class Shelf {
      fun height(): Int = 3
    }

    interface Groomable {
      fun groom(): String
      fun brushes(): Int = 1
    }

    class Ledge : Shelf(), Groomable {
      override fun groom(): String = "Mylo: groomed"
    }
  """.trimIndent()

  private fun generate(): Tier1Result = Tier1Harness.run(
    fixture,
    processorOptions = mapOf("nuget.rootPackage" to "tier1.keptbase"),
  )

  @Test
  fun `a kept base class keeps the interface beside it in the base list`() {
    val result = generate()

    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    assertTrue(
      "public class Ledge : Shelf, IGroomable" in result.generatedCSharp,
      "a kept base must not empty the interface list; a C# consumer holding a `Ledge` as an " +
          "`IGroomable` is CS0266 without it; generated C#:\n${result.generatedCSharp}",
    )
    assertFalse(
      "public class Ledge : Shelf, IDisposable" in result.generatedCSharp,
      "the base carries `IDisposable`/`INugetHandle`; repeating them on the derived class is " +
          "the ADR-094 duplicate; generated C#:\n${result.generatedCSharp}",
    )
  }

  @Test
  fun `an interface default the class never overrides binds on the class itself`() {
    val result = generate()

    assertTrue(
      "export_ledge_brushes" in result.generated,
      "`: IGroomable` without a `Brushes` body is CS0535, and the default body is reachable by " +
          "ordinary dispatch on the instance behind the handle; generated:\n${result.generated}",
    )
    assertTrue(
      "public int Brushes()" in result.generatedCSharp,
      "generated C#:\n${result.generatedCSharp}",
    )
    assertFalse(
      "export_ledge_height" in result.generated,
      "a base *class* member still stays on the base: re-binding it on the subclass hides the " +
          "base one (CS0108); generated:\n${result.generated}",
    )
  }

  @Test
  fun `implementing an interface member beside a base renders virtual, never override`() {
    val result = generate()

    assertTrue(
      "public virtual string Groom()" in result.generatedCSharp,
      "`Shelf` declares no `Groom`, so the slot starts on `Ledge`; generated C#:" +
          "\n${result.generatedCSharp}",
    )
    assertFalse(
      "override string Groom()" in result.generatedCSharp,
      "`override` in C# has to mean \"overrides a base *class* member\", not \"the Kotlin " +
          "modifier said override\": against a `Shelf` with no `Groom` it is CS0115; " +
          "generated C#:\n${result.generatedCSharp}",
    )
  }
}
