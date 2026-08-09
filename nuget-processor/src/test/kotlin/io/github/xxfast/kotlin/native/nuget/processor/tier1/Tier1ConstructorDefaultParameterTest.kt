package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-091: constructor default parameters surface as `@JvmOverloads`-style trailing-omitting
 * overloads, synthesized in the planner and numbered into ADR-034's `_$n` sequence.
 *
 * The end-to-end half lives in `cat/DefaultsSample.kt` / `ConstructorDefaultParameterTests.cs`.
 * What is only reachable here is the collision case (its correct outcome is a *failed*
 * generation, so it cannot ship in the fixture library) and the byte-level naming regression.
 */
class Tier1ConstructorDefaultParameterTest {

  /**
   * Structural. Two trailing defaults produce both suffix lengths: the synthesized Kotlin wrapper
   * calls the constructor with *fewer positional arguments* and Kotlin computes the defaults, and
   * the C# half gets numbered private externs behind one natural overload set.
   */
  @Test
  fun `trailing defaults synthesize omitting overloads on both halves`() {
    val result = Tier1Harness.run(
      """
      package tier1.ctordefaults

      class Carrier(val label: String, val size: Int = 3, val padded: Boolean = true) {
        fun describe(): String = "${'$'}label ${'$'}size ${'$'}padded"
      }
      """.trimIndent(),
    )

    assertTrue(
      result.compiledClean,
      "expected the synthesized wrappers to compile; got: ${result.compileErrors}",
    )

    val kotlin: String = result.generated
    assertContains(kotlin, "@CName(\"carrier_create\")")
    assertContains(kotlin, "@CName(\"carrier_create_2\")")
    assertContains(kotlin, "@CName(\"carrier_create_3\")")
    // The load-bearing assertion: the truncated plans call the constructor with fewer positional
    // arguments. Kotlin, not the generator, supplies `size` / `padded`.
    assertContains(kotlin, "Carrier(label, size, padded)")
    assertContains(kotlin, "Carrier(label, size)")
    assertContains(kotlin, "Carrier(label)")

    val cs: String = result.generatedCSharp
    assertContains(cs, "EntryPoint = \"carrier_create\"")
    assertContains(cs, "EntryPoint = \"carrier_create_2\"")
    assertContains(cs, "EntryPoint = \"carrier_create_3\"")
    assertContains(cs, "private static extern IntPtr Native_Create_2(")
    assertContains(cs, "private static extern IntPtr Native_Create_3(")
    // One natural C# overload set: the numbering never reaches the public surface.
    assertContains(cs, "public Carrier(string label, int size, bool padded)")
    assertContains(cs, "public Carrier(string label, int size)")
    assertContains(cs, "public Carrier(string label)")
  }

  /**
   * Structural. A secondary constructor carries its own trailing default, and its synthesized
   * entry continues the sequence *after* every full signature (primary, then secondaries), so the
   * secondary keeps `_2` and only the new overload takes `_3`.
   */
  @Test
  fun `a secondary constructor's trailing default is synthesized after the full signatures`() {
    val result = Tier1Harness.run(
      """
      package tier1.ctordefaultssecondary

      class ScratchPost(val label: String) {
        constructor(label: String, height: Int, sturdy: Boolean = true) :
          this("${'$'}label/${'$'}height/${'$'}sturdy")
      }
      """.trimIndent(),
    )

    assertTrue(result.compiledClean, "expected clean compile; got: ${result.compileErrors}")

    val kotlin: String = result.generated
    assertContains(kotlin, "@CName(\"scratchpost_create\")")
    assertContains(kotlin, "@CName(\"scratchpost_create_2\")")
    assertContains(kotlin, "@CName(\"scratchpost_create_3\")")
    assertFalse(
      kotlin.contains("scratchpost_create_4"),
      "the primary has no defaults, so nothing is synthesized for it; generated=$kotlin",
    )

    val cs: String = result.generatedCSharp
    assertContains(cs, "public ScratchPost(string label, int height)")
  }

  /**
   * ADR-091's expect/actual clause. Kotlin forbids an `actual` from restating a default, so the
   * bit exists only on the `expect` header; without the `expectsByName` lookup the planner would
   * silently and "correctly" conclude the class has no defaults.
   */
  @Test
  fun `an expect class primary-constructor default is read off the expect`() {
    val result = Tier1Harness.run(
      commonSources = mapOf(
        "Beacon.kt" to """
        package tier1.ctordefaultsexpect

        expect class Beacon(name: String, interval: Int = 5) {
          fun describe(): String
        }
        """.trimIndent(),
      ),
      sources = mapOf(
        "BeaconActual.kt" to """
        package tier1.ctordefaultsexpect

        actual class Beacon actual constructor(
          private val name: String,
          private val interval: Int,
        ) {
          actual fun describe(): String = "${'$'}name every ${'$'}interval"
        }
        """.trimIndent(),
      ),
    )

    assertEquals("OK", result.kspExitCode, "kspErrors=${result.kspErrors}")
    val kotlin: String = result.generated
    assertContains(kotlin, "@CName(\"beacon_create\")")
    assertContains(kotlin, "@CName(\"beacon_create_2\")")
    assertContains(kotlin, "Beacon(name, interval)")
    assertContains(kotlin, "Beacon(name)")
    assertContains(result.generatedCSharp, "public Beacon(string name)")
  }

  /**
   * Diagnostic. A synthesized overload that collides with a real constructor must fail generation
   * with the ADR-034 kind (extended hint) rather than emit CS0111 C#.
   */
  @Test
  fun `a synthesized overload colliding with a real constructor fires ERROR_CSHARP_SIGNATURE_COLLISION`() {
    val result = Tier1Harness.run(
      """
      package tier1.ctordefaultscollision

      class Foo(val name: String, val lives: Int = 9) {
        constructor(name: String) : this(name, 1)
      }
      """.trimIndent(),
    )

    assertTrue(
      result.kspErrors.any {
        it.contains(ForwardDiagnosticKind.ERROR_CSHARP_SIGNATURE_COLLISION.name)
      },
      "expected the synthesized-overload collision to fail generation; kspErrors=${result.kspErrors}",
    )
    assertTrue(
      result.kspErrors.any { it.contains("remove the default value whose synthesized") },
      "expected the hint to name the defaulted-parameter cause; kspErrors=${result.kspErrors}",
    )
  }

  /**
   * Regression. A middle default (a required parameter sits after it) cannot be omitted by a
   * positional Kotlin call, so nothing is synthesized: exactly one public C# constructor.
   */
  @Test
  fun `a middle default synthesizes nothing`() {
    val result = Tier1Harness.run(
      """
      package tier1.ctordefaultsmiddle

      class Kennel(val name: String, val capacity: Int = 10, val city: String)
      """.trimIndent(),
    )

    assertTrue(result.compiledClean, "expected clean compile; got: ${result.compileErrors}")
    val kotlin: String = result.generated
    assertContains(kotlin, "@CName(\"kennel_create\")")
    assertFalse(
      kotlin.contains("kennel_create_2"),
      "a middle default gets no omitting overload (the JvmOverloads rule); generated=$kotlin",
    )
    assertEquals(
      1,
      Regex("""public Kennel\(""").findAll(result.generatedCSharp).count(),
      "expected exactly one public constructor; generatedCSharp=${result.generatedCSharp}",
    )
  }

  /**
   * Regression. A class with no defaults must render byte-identically to the shipped naming: no
   * spurious `_2` export, and the private extern still `Native_Create`.
   */
  @Test
  fun `a constructor without defaults keeps its unsuffixed naming`() {
    val result = Tier1Harness.run(
      """
      package tier1.ctornodefaults

      class Plain(val name: String, val count: Int)
      """.trimIndent(),
    )

    assertTrue(result.compiledClean, "expected clean compile; got: ${result.compileErrors}")
    val kotlin: String = result.generated
    assertContains(kotlin, "@CName(\"plain_create\")")
    assertFalse(kotlin.contains("plain_create_2"), "no numbering without defaults; generated=$kotlin")

    val cs: String = result.generatedCSharp
    assertContains(cs, "private static extern IntPtr Native_Create(")
    assertFalse(cs.contains("Native_Create_2"), "no numbering without defaults; generatedCSharp=$cs")
  }
}
