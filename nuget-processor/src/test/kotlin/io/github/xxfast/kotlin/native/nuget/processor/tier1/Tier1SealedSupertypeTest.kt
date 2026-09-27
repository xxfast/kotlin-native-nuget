package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-101 amendment (2026-09-27): a sealed class over a supertype gets the ordinary class's
 * treatment. The sealed base used to render `: IDisposable, INugetHandle` with its own declared
 * members only, so every inherited member was silently lost and no supertype was ever named,
 * exported or not.
 *
 * Unexported supertypes cross a real compilation-unit boundary via [Tier1DependencyLibrary]
 * (`containingFile == null`, the klib shape): they are dropped with `SKIPPED_UNEXPORTED_SUPERTYPE`
 * and their public members re-home onto the sealed base, abstract ones included. Exported
 * supertypes are declared in this module and listed in the sealed base's C# base list.
 */
class Tier1SealedSupertypeTest {

  private val dependencyJar: File = Tier1DependencyLibrary.compile(
    """
    package dep.outside

    open class OutsideBlanket {
      val fabric: String = "fleece"
      var warmth: Int = 3
      fun drape(cat: String): String = "fleece draped over ${'$'}cat"
      open fun shake(): Int = 1
    }

    interface OutsidePurring {
      fun purr(): String = "prrr"
      val rumble: Int get() = 5
      fun knead(paws: Int = 2): Int
      val whiskers: Int
    }
    """.trimIndent(),
    fileName = "OutsideBedding.kt",
  )

  private val fixture: String = """
    package tier1.sealedsuper

    import dep.outside.OutsideBlanket
    import dep.outside.OutsidePurring

    sealed class Swaddle : OutsideBlanket() {
      data class Wriggling(val turns: Int) : Swaddle() {
        override fun shake(): Int = turns
      }
      data class Still(val limbs: Int) : Swaddle()
    }

    sealed class Purrito : OutsidePurring {
      data class Tucked(val paws: Int) : Purrito() {
        override fun knead(paws: Int): Int = paws * this.paws
        override fun purr(): String = "loaf"
        override val whiskers: Int get() = 24
      }
      data class Crouched(val tail: Int) : Purrito() {
        override fun knead(paws: Int): Int = paws + tail
        override val whiskers: Int = 12
      }
    }

    open class Pouffe {
      val stuffing: String = "beans"
      fun plump(times: Int): Int = times * 2
      open fun sink(): Int = 1
    }

    sealed class Ottoman : Pouffe() {
      data class Tall(val shelf: Int) : Ottoman() {
        override fun sink(): Int = shelf
      }
      data class Squat(val step: Int) : Ottoman()
    }

    interface Dreamer {
      fun dream(): String = "chasing the red dot"
      val snores: Int get() = 3
    }

    sealed class Slumber : Dreamer {
      data class Heavy(val hours: Int) : Slumber() {
        override fun dream(): String = "milo"
      }
      data class Fitful(val twitches: Int) : Slumber()
    }
  """.trimIndent()

  private val result: Tier1Result by lazy {
    Tier1Harness.run(
      fixture,
      processorOptions = mapOf("nuget.rootPackage" to "tier1.sealedsuper"),
      libraries = listOf(dependencyJar),
    )
  }

  private fun supertypeWarnings(declaration: String): List<String> = result.kspWarnings
    .filter { it.contains(ForwardDiagnosticKind.SKIPPED_UNEXPORTED_SUPERTYPE.name) }
    .filter { it.contains(declaration) }

  @Test
  fun `the Kotlin half compiles`() {
    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
  }

  @Test
  fun `an unexported base class re-homes every public member onto the sealed base`() {
    listOf(
      "export_library_swaddle_get_fabric",
      "export_library_swaddle_get_warmth",
      "export_library_swaddle_set_warmth",
      "export_library_swaddle_drape",
      "export_library_swaddle_shake",
    ).forEach { export ->
      assertTrue(export in result.generated, "missing $export; generated:\n${result.generated}")
    }
    val cs: String = result.generatedCSharp
    assertTrue("public abstract class Swaddle : IDisposable, INugetHandle" in cs, cs)
    assertTrue(
      "public virtual string Drape(string cat)" in cs || "public string Drape(string cat)" in cs,
      cs,
    )
    assertTrue(
      "public virtual int Shake()" in cs,
      "the open member must be virtual on the base; $cs",
    )
    assertTrue(
      "public override int Shake()" in cs,
      "the overriding arm must override the re-homed base slot, not hide it; $cs",
    )
    assertFalse("OutsideBlanket" in cs, cs)
  }

  @Test
  fun `an unexported base class on a sealed base is named once`() {
    val warnings: List<String> = supertypeWarnings("Swaddle : ")
    assertEquals(1, warnings.size, "kspWarnings=${result.kspWarnings}")
    val warning: String = warnings.single()
    assertTrue("dep.outside.OutsideBlanket" in warning, warning)
    assertTrue("base class" in warning, warning)
    assertTrue("public members are bound on Swaddle directly" in warning, warning)
  }

  @Test
  fun `an unexported interface re-homes defaulted and abstract members onto the sealed base`() {
    listOf(
      "export_library_purrito_purr",
      "export_library_purrito_knead",
      "export_library_purrito_get_rumble",
      "export_library_purrito_get_whiskers",
    ).forEach { export ->
      assertTrue(export in result.generated, "missing $export; generated:\n${result.generated}")
    }
    val cs: String = result.generatedCSharp
    assertTrue("public abstract class Purrito : IDisposable, INugetHandle" in cs, cs)
    assertTrue("public virtual string Purr()" in cs, cs)
    assertTrue("public virtual int Knead(" in cs, cs)
    assertTrue("public override string Purr()" in cs, cs)
    assertFalse("IOutsidePurring" in cs, cs)
  }

  @Test
  fun `an unexported interface warning no longer claims nothing is lost for the wrong reason`() {
    val warnings: List<String> = supertypeWarnings("Purrito : ")
    assertEquals(1, warnings.size, "kspWarnings=${result.kspWarnings}")
    val warning: String = warnings.single()
    assertTrue("dep.outside.OutsidePurring" in warning, warning)
    assertTrue("public members are bound on Purrito directly" in warning, warning)
    assertTrue("`is`/`as` against it is gone" in warning, warning)
    assertFalse("carries no members the C# side could call" in warning, warning)
  }

  @Test
  fun `an exported base class is the sealed base's C# base and keeps its own members`() {
    val cs: String = result.generatedCSharp
    assertTrue("public abstract class Ottoman : Pouffe" in cs, cs)
    assertTrue(
      "internal Ottoman(IntPtr handle, out NugetHandleTag tag) : base(handle, out tag)" in cs,
      cs,
    )
    assertTrue("public abstract override void Dispose();" in cs, cs)
    assertFalse(
      "export_library_ottoman_get_stuffing" in result.generated ||
          "export_library_ottoman_plump" in result.generated,
      "the kept base carries its own members; re-binding them hides Pouffe's (CS0108); " +
          "generated:\n${result.generated}",
    )
    assertTrue(
      "public override int Sink()" in cs,
      "the arm's override of Pouffe.sink must override it through the sealed base (CS0114); $cs",
    )
    assertTrue(supertypeWarnings("Ottoman : ").isEmpty(), "kspWarnings=${result.kspWarnings}")
  }

  @Test
  fun `an exported interface is listed and its members bound on the sealed base`() {
    val cs: String = result.generatedCSharp
    assertTrue("public abstract class Slumber : IDreamer, IDisposable, INugetHandle" in cs, cs)
    assertTrue("export_library_slumber_dream" in result.generated, result.generated)
    assertTrue("export_library_slumber_get_snores" in result.generated, result.generated)
    assertTrue("public virtual string Dream()" in cs, cs)
    assertTrue("public override string Dream()" in cs, cs)
    assertTrue(supertypeWarnings("Slumber : ").isEmpty(), "kspWarnings=${result.kspWarnings}")
  }
}
