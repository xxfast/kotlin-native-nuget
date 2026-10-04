package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ROADMAP Phase 4: a lambda-typed property was emitted AND named by a
 * `SKIPPED_UNSUPPORTED_PROPERTY` warning saying it had been skipped. ADR-064's contract is one or
 * the other: a member is bound, or it is named as skipped exactly once, never both and never
 * neither.
 *
 * Two causes, both measured on this fixture before the fix:
 *  - the property planner silenced only `lambda`/`suspend lambda` protocol types, but ADR-160 made
 *    an admissible `(Int) -> Unit` classify as a callback, so the class and sealed-arm routes
 *    emitted it while the planner warned (Cat A/B/C/D/F, Pet.Dog O/P/R);
 *  - the planner silenced by the shared CLASS position, which a sealed base and an interface also
 *    plan under though neither has a lambda-property route, so a suspend or non-callback lambda
 *    there was neither emitted nor named (Pet.Dog Q, Groomer U/V, Toy Z).
 *
 * A nullable lambda on an ordinary class (Cat B/C, Salon X) was emitted by a getter that did not
 * compile (`NugetHandles.retain` over a nullable). It never bound, so it now stays unbound, named,
 * with its nullability as the reason. The sealed arm's (Pet.Dog P) compiled but rendered a non-null
 * `KotlinAction<int>` over a possibly-zero handle, so it is refused by the same rule.
 *
 * A lambda declared on a sealed base (Toy Y) binds on every arm, whose getter reads the inherited
 * property off the arm; the base has no route of its own, and naming it skipped there was false.
 * A `var` lambda (Cat D, Pet.Dog R, Toy Yy) binds get-only and names its setter once.
 */
class Tier1LambdaPropertyReportTest {

  private val source: String = """
    package tier1.lambdaprop

    import io.github.xxfast.kotlin.native.nuget.annotations.CSharpName

    class Cat {
      val onPurrA: (Int) -> Unit = {}
      var onPurrB: ((Int) -> Unit)? = null
      val onPurrC: ((Int) -> Unit)? = null
      var onPurrD: (Int) -> Unit = {}
      val onPurrE: suspend (Int) -> Unit = {}
      @CSharpName("Renamed") val onPurrF: (Int) -> Unit = {}
      companion object {
        val onPurrG: (Int) -> Unit = {}
        var onPurrH: ((Int) -> Unit)? = null
      }
    }

    object Kennel {
      val onPurrI: (Int) -> Unit = {}
      var onPurrJ: ((Int) -> Unit)? = null
      val onPurrK: suspend (Int) -> Unit = {}
    }

    val onPurrL: (Int) -> Unit = {}
    var onPurrM: ((Int) -> Unit)? = null
    val onPurrN: suspend (Int) -> Unit = {}

    sealed class Pet {
      class Dog : Pet() {
        val onPurrO: (Int) -> Unit = {}
        var onPurrP: ((Int) -> Unit)? = null
        val onPurrQ: suspend (Int) -> Unit = {}
        var onPurrR: (Int) -> Unit = {}
      }
    }

    interface Groomer {
      val onPurrS: (Int) -> Unit
      var onPurrT: ((Int) -> Unit)?
      val onPurrU: suspend (Int) -> Unit
      val onPurrV: (Char) -> Unit
    }

    fun groomer(): Groomer = TODO()

    class Salon {
      val onPurrW: (Char) -> Unit = {}
      val onPurrX: (suspend (Int) -> Unit)? = null
    }

    sealed class Toy {
      val onPurrY: (Int) -> Unit = {}
      var onPurrYy: (Int) -> Unit = {}
      val onPurrZ: suspend (Int) -> Unit = {}
      class Ball : Toy()
    }

    sealed interface Shape {
      val onPurrSh: (Int) -> Unit
      class Round(val r: Int) : Shape {
        override val onPurrSh: (Int) -> Unit = {}
      }
      enum class Flat : Shape {
        A;
        override val onPurrSh: (Int) -> Unit get() = {}
      }
    }

    class Crate<T>(val t: T) {
      val onPurrGen: (Int) -> Unit = {}
    }
  """.trimIndent()

  /** Qualified Kotlin symbol (minus the package) to its C# member name and whether it binds. */
  private val cells: Map<String, Pair<String, Boolean>> = mapOf(
    "Cat.onPurrA" to ("OnPurrA" to true),
    "Cat.onPurrB" to ("OnPurrB" to false),
    "Cat.onPurrC" to ("OnPurrC" to false),
    "Cat.onPurrD" to ("OnPurrD" to true),
    "Cat.onPurrE" to ("OnPurrE" to true),
    "Cat.onPurrF" to ("Renamed" to true),
    "Cat.Companion.onPurrG" to ("OnPurrG" to false),
    "Cat.Companion.onPurrH" to ("OnPurrH" to false),
    "Kennel.onPurrI" to ("OnPurrI" to false),
    "Kennel.onPurrJ" to ("OnPurrJ" to false),
    "Kennel.onPurrK" to ("OnPurrK" to false),
    "onPurrL" to ("OnPurrL" to false),
    "onPurrM" to ("OnPurrM" to false),
    "onPurrN" to ("OnPurrN" to false),
    "Pet.Dog.onPurrO" to ("OnPurrO" to true),
    "Pet.Dog.onPurrP" to ("OnPurrP" to false),
    "Pet.Dog.onPurrQ" to ("OnPurrQ" to false),
    "Pet.Dog.onPurrR" to ("OnPurrR" to true),
    "Groomer.onPurrS" to ("OnPurrS" to false),
    "Groomer.onPurrT" to ("OnPurrT" to false),
    "Groomer.onPurrU" to ("OnPurrU" to false),
    "Groomer.onPurrV" to ("OnPurrV" to false),
    "Salon.onPurrW" to ("OnPurrW" to true),
    "Salon.onPurrX" to ("OnPurrX" to false),
    // Declared on the sealed base, bound on every arm (the base has no lambda route).
    "Toy.onPurrY" to ("OnPurrY" to true),
    "Toy.onPurrYy" to ("OnPurrYy" to true),
    "Toy.onPurrZ" to ("OnPurrZ" to false),
    // A sealed interface with a class arm and an enum arm: bound on the class arm, so the base is
    // silent; the enum arm's own override is named on the enum (asserted separately below).
    "Shape.onPurrSh" to ("OnPurrSh" to true),
    "Crate.onPurrGen" to ("OnPurrGen" to false),
  )

  private fun run(): Tier1Result {
    val result: Tier1Result = Tier1Harness.run(source, libraries = listOf(csharpNameLibrary))
    assertTrue(result.kspErrors.isEmpty(), "kspErrors=${result.kspErrors}")
    assertTrue(result.compiledClean, "compileErrors=${result.compileErrors}")
    return result
  }

  /** A C# member declaration, never a `///` remark that names a skipped member. */
  private fun declares(csharp: String, member: String): Boolean = csharp.lines()
    .filterNot { line -> line.trimStart().startsWith("///") }
    .any { line -> Regex("""\bKotlin\w*(<[^>]*>)? $member\b""").containsMatchIn(line) }

  private fun warningsFor(result: Tier1Result, symbol: String): List<String> =
    result.kspWarnings.filter { warning ->
      warning.contains("SKIPPED_UNSUPPORTED_PROPERTY") &&
          warning.contains("Skipping tier1.lambdaprop.$symbol:")
    }

  @Test
  fun `every lambda property is either bound or named once, never both and never neither`() {
    val result: Tier1Result = run()
    val wrong: List<String> = cells.mapNotNull { (symbol, expected) ->
      val (member: String, binds: Boolean) = expected
      val emitted: Boolean = declares(result.generatedCSharp, member)
      val warnings: Int = warningsFor(result, symbol).size
      val actual: String = "emitted=$emitted warnings=$warnings"
      val wanted: String = if (binds) "emitted=true warnings=0" else "emitted=false warnings=1"
      if (actual == wanted) null else "$symbol: wanted $wanted, got $actual"
    }
    assertTrue(wrong.isEmpty(), wrong.joinToString("\n") + "\n${result.kspWarnings}")
    // The enum arm has no lambda route; its override is named once, on the enum.
    assertEquals(1, warningsFor(result, "Shape.Flat.onPurrSh").size, "${result.kspWarnings}")
  }

  /** The class route's getter and C# member agree on the rename and on the unrenamed extern. */
  @Test
  fun `a renamed class lambda property binds under its declared name`() {
    val result: Tier1Result = run()
    assertTrue(declares(result.generatedCSharp, "Renamed"), result.generatedCSharp)
    assertEquals(
      1,
      Regex("""@CName\("[a-z0-9_]*_cat_get_onPurrF"\)""").findAll(result.generated).count(),
      result.generated,
    )
  }

  /**
   * A `var` lambda binds get-only, as every other refused setter does (ADR-075, ADR-107): the
   * property survives and its setter is named once. A refused nullable `var` is named once as a
   * whole and never again for its setter.
   */
  @Test
  fun `a var lambda property names its dropped setter once`() {
    val result: Tier1Result = run()
    fun setterWarnings(symbol: String): Int = result.kspWarnings.count { warning ->
      warning.contains("SKIPPED_UNSUPPORTED_INPUT") &&
          warning.contains("tier1.lambdaprop.$symbol:") &&
          warning.contains("its setter is not generated")
    }
    val counts: Map<String, Int> =
      listOf("Cat.onPurrD", "Pet.Dog.onPurrR", "Toy.onPurrYy", "Cat.onPurrB", "Pet.Dog.onPurrP")
        .associateWith(::setterWarnings)
    assertEquals(
      mapOf(
        "Cat.onPurrD" to 1, "Pet.Dog.onPurrR" to 1, "Toy.onPurrYy" to 1,
        "Cat.onPurrB" to 0, "Pet.Dog.onPurrP" to 0,
      ),
      counts,
      result.kspWarnings.joinToString("\n"),
    )
    listOf("OnPurrD", "OnPurrR", "OnPurrYy").forEach { member ->
      assertTrue(
        result.generatedCSharp.lines()
          .filterNot { line -> line.trimStart().startsWith("///") }
          .none { line -> Regex("""\b$member\b""").containsMatchIn(line) && line.contains("set") },
        "expected $member get-only",
      )
    }
  }

  /**
   * Where the non-null function type binds (an ordinary class, a sealed arm), the nullable one is
   * refused for its nullability, and the hint says so instead of "expose a property whose type is
   * not (Int) -> Unit?", which reads as if no lambda property could bind.
   */
  @Test
  fun `a nullable lambda property names its nullability as the reason`() {
    val result: Tier1Result = run()
    listOf("Cat.onPurrB", "Cat.onPurrC", "Salon.onPurrX", "Pet.Dog.onPurrP").forEach { symbol ->
      val warning: String = warningsFor(result, symbol).single()
      assertTrue(warning.contains("nullable function type"), warning)
      assertTrue(!warning.contains("whose type is not"), warning)
    }
    val suspendWarning: String = warningsFor(result, "Salon.onPurrX").single()
    assertTrue(suspendWarning.contains("suspend (Int) -> Unit"), suspendWarning)
  }

  /** A refused nullable lambda reaches neither half, so the Kotlin export cannot outlive it. */
  @Test
  fun `a nullable class or arm lambda property has no export on either half`() {
    val result: Tier1Result = run()
    listOf(
      "cat_get_onPurrB", "cat_get_onPurrC", "salon_get_onPurrX", "pet_dog_get_onPurrP",
    ).forEach { export ->
      assertTrue(export !in result.generated, "unexpected Kotlin export $export")
      assertTrue(
        "Native_Get_${export.substringAfterLast("_get_")}" !in result.generatedCSharp,
        "unexpected DllImport for $export",
      )
    }
  }
}
