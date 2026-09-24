package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-164 (superseding ADR-091's omitting overloads): a constructor's defaulted parameters widen to
 * their nullable C# form on ONE constructor, and the Kotlin wrapper dispatches on which were set.
 *
 * The end-to-end half lives in `cat/DefaultsSample.kt` / `ConstructorDefaultParameterTests.cs` and
 * `issue297/Issue297Sample.kt` / `Issue297Tests.cs`. What is only reachable here is the byte-level
 * naming, and the expect/actual lookup.
 */
class Tier1ConstructorDefaultParameterTest {

  /**
   * Structural. Two trailing defaults widen on the one export: the Kotlin wrapper names only the
   * arguments that were set and Kotlin computes the rest, and the C# constructor is optional from
   * the first default on.
   */
  @Test
  fun `trailing defaults widen one constructor on both halves`() {
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
      "expected the dispatching wrapper to compile; got: ${result.compileErrors}",
    )

    val kotlin: String = result.generated
    assertContains(kotlin, "@CName(\"library_tier1_ctordefaults__carrier_create\")")
    assertFalse(kotlin.contains("carrier_create_2"), "no synthesized export; generated=$kotlin")
    // The load-bearing assertion: each arm names only what was set, so Kotlin supplies the rest.
    assertContains(kotlin, "0 -> tier1.ctordefaults.Carrier(label)")
    assertContains(kotlin, "2 -> tier1.ctordefaults.Carrier(label, padded = default_padded!!)")
    assertContains(
      kotlin, "3 -> tier1.ctordefaults.Carrier(label, size = default_size!!, padded = default_padded!!)",
    )

    val cs: String = result.generatedCSharp
    assertContains(cs, "EntryPoint = \"library_tier1_ctordefaults__carrier_create\"")
    assertFalse(cs.contains("Native_Create_2"), "no synthesized extern; generatedCSharp=$cs")
    assertContains(cs, "public Carrier(string label, int? size = null, bool? padded = null)")
  }

  /**
   * Structural. A secondary constructor's default widens that secondary in place, so the ADR-034
   * `_2` it always had is still the only number.
   */
  @Test
  fun `a secondary constructor's trailing default widens in place`() {
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
    assertContains(kotlin, "@CName(\"library_tier1_ctordefaultssecondary__scratchpost_create\")")
    assertContains(kotlin, "@CName(\"library_tier1_ctordefaultssecondary__scratchpost_create_2\")")
    assertFalse(
      kotlin.contains("scratchpost_create_3"),
      "no synthesized entry continues the sequence; generated=$kotlin",
    )

    val cs: String = result.generatedCSharp
    assertContains(cs, "public ScratchPost(string label)")
    assertContains(cs, "public ScratchPost(string label, int height, bool? sturdy = null)")
  }

  /**
   * ADR-091's expect/actual clause, kept by ADR-164. Kotlin forbids an `actual` from restating a
   * default, so the bit exists only on the `expect` header; without the `ExpectIndex` lookup the
   * planner would silently and "correctly" conclude the class has no defaults.
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
    assertContains(kotlin, "@CName(\"library_tier1_ctordefaultsexpect__beacon_create\")")
    assertContains(kotlin, "0 -> tier1.ctordefaultsexpect.Beacon(name)")
    assertContains(kotlin, "1 -> tier1.ctordefaultsexpect.Beacon(name, interval = default_interval!!)")
    assertContains(result.generatedCSharp, "public Beacon(string name, int? interval = null)")
  }

  /**
   * ADR-164: the widened `Foo(string, int?)` is a different C# signature from a real `Foo(string)`,
   * so the collision ADR-091 had to fail generation for is gone (C# prefers the candidate with no
   * omitted optional, so `new Foo("x")` is not ambiguous either).
   */
  @Test
  fun `a widened constructor beside a real shorter one does not collide`() {
    val result = Tier1Harness.run(
      """
      package tier1.ctordefaultscollision

      class Foo(val name: String, val lives: Int = 9) {
        constructor(name: String) : this(name, 1)
      }
      """.trimIndent(),
    )

    assertFalse(
      result.kspErrors.any {
        it.contains(ForwardDiagnosticKind.ERROR_CSHARP_SIGNATURE_COLLISION.name)
      },
      "expected no collision; kspErrors=${result.kspErrors}",
    )
    val cs: String = result.generatedCSharp
    assertContains(cs, "public Foo(string name, int? lives = null)")
    assertContains(cs, "public Foo(string name)")
  }

  /**
   * ADR-164: a middle default (a required parameter sits after it) is required-but-nullable. The
   * C# caller passes `null` positionally and Kotlin still evaluates the default.
   */
  @Test
  fun `a middle default is required but nullable`() {
    val result = Tier1Harness.run(
      """
      package tier1.ctordefaultsmiddle

      class Kennel(val name: String, val capacity: Int = 10, val city: String)
      """.trimIndent(),
    )

    assertTrue(result.compiledClean, "expected clean compile; got: ${result.compileErrors}")
    val kotlin: String = result.generated
    assertContains(kotlin, "@CName(\"library_tier1_ctordefaultsmiddle__kennel_create\")")
    assertFalse(kotlin.contains("kennel_create_2"), "no synthesized export; generated=$kotlin")
    assertContains(kotlin, "0 -> tier1.ctordefaultsmiddle.Kennel(name, city = city)")
    assertEquals(
      1,
      Regex("""public Kennel\(""").findAll(result.generatedCSharp).count(),
      "expected exactly one public constructor; generatedCSharp=${result.generatedCSharp}",
    )
    assertContains(result.generatedCSharp, "public Kennel(string name, int? capacity, string city)")
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
    assertContains(kotlin, "@CName(\"library_tier1_ctornodefaults__plain_create\")")
    assertFalse(kotlin.contains("plain_create_2"), "no numbering without defaults; generated=$kotlin")

    val cs: String = result.generatedCSharp
    assertContains(cs, "private static extern IntPtr Native_Create(")
    assertFalse(cs.contains("Native_Create_2"), "no numbering without defaults; generatedCSharp=$cs")
  }
}
