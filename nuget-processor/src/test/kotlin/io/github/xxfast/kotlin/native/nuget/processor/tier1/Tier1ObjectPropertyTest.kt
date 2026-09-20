package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ROADMAP Phase 4: a Kotlin `object`'s own property reaches C# as a STATIC property on the
 * generated static class, planned by the same `propertyPlan` a companion property uses
 * (`ForwardPropertyPosition.OBJECT`) and projected into both halves off that one plan.
 *
 * Before this, `object Jar { val count: Int }` produced nothing at all: no `@CName` export, no C#
 * member, and no diagnostic either, because the property was never handed to the planner and so
 * was never *dropped*.
 */
class Tier1ObjectPropertyTest {

  @Test
  fun `an object var plans a static property on both halves`() {
    val result = Tier1Harness.run(
      """
      package tier1.objectprop

      object TreatJar {
        var count: Int = 0
      }
      """.trimIndent(),
    )

    assertTrue(
      result.compiledClean,
      "expected the object property exports to compile; got: ${result.compileErrors}",
    )

    val kotlin: String = result.generated
    assertContains(kotlin, "treatjar_get_count")
    assertContains(kotlin, "treatjar_set_count")
    // The access is the Kotlin object read itself -- no handle, no receiver parameter.
    assertContains(kotlin, "tier1.objectprop.TreatJar.count")

    val cs: String = result.generatedCSharp
    assertContains(cs, "public static int Count")
    assertContains(cs, "EntryPoint = \"treatjar_get_count\"")
    assertContains(cs, "EntryPoint = \"treatjar_set_count\"")
  }

  @Test
  fun `an object val renders get-only`() {
    val result = Tier1Harness.run(
      """
      package tier1.objectval

      object TreatJar {
        val label: String = "treats"
      }
      """.trimIndent(),
    )

    assertTrue(result.compiledClean, "expected compile; got: ${result.compileErrors}")
    val cs: String = result.generatedCSharp
    assertContains(cs, "public static string Label")
    assertFalse(cs.contains("treatjar_set_label"), cs)
  }

  @Test
  fun `each object keeps its own const value`() {
    val result = Tier1Harness.run(
      """
      package tier1.objectconst

      object TreatJar {
        /** The pantry's own capacity. */
        const val CAPACITY: Int = 12
      }

      object SpareJar {
        const val CAPACITY: Int = 3
      }

      class Kennel {
        companion object {
          const val ROOM: Int = 7
        }
      }

      class Cattery {
        companion object {
          const val ROOM: Int = 2
        }
      }
      """.trimIndent(),
    )

    val cs: String = result.generatedCSharp
    // The const-literal lookup used to take the FIRST `const val NAME` in the file, so the second
    // declaration of each pair silently rendered the first one's value.
    assertContains(cs, "public const int Capacity = 12;")
    assertContains(cs, "public const int Capacity = 3;")
    assertContains(cs, "public const int Room = 7;")
    assertContains(cs, "public const int Room = 2;")
    // A `const val` is a C# const, never a get export.
    assertFalse(cs.contains("treatjar_get_CAPACITY"), cs)
    // A const-only object's body ends at its last member: no blank line dangling before the brace.
    assertContains(cs.replace("\r\n", "\n"), "public const int Capacity = 3;\n    }")
  }

  @Test
  fun `an object state flow property is a named skip`() {
    val result = Tier1Harness.run(
      """
      package tier1.objectflow

      import kotlinx.coroutines.flow.MutableStateFlow
      import kotlinx.coroutines.flow.StateFlow

      object Hub {
        val level: StateFlow<Int> = MutableStateFlow(0)
        val name: String = "hub"
      }
      """.trimIndent(),
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )

    // No adapter exists for a state flow on a static owner, so it must skip *named* rather than
    // vanish the way it does on the class route (where an adapter re-emits it).
    assertTrue(
      result.kspWarnings.any { it.contains("tier1.objectflow.Hub.level") },
      "expected a named skip for the object's StateFlow property; got: ${result.kspWarnings}",
    )
    assertFalse(result.generatedCSharp.contains("Level"), result.generatedCSharp)
  }

  @Test
  fun `a property and a function claiming one C# name on an object is fatal`() {
    val result = Tier1Harness.run(
      """
      package tier1.objectclash

      object TreatJar {
        val count: Int = 3
        fun count(): Int = 4
      }
      """.trimIndent(),
    )

    val message: String = result.kspErrors.joinToString("\n")
    assertContains(message, "TreatJar.Count")
    assertContains(message, "CS0102")
    // The author has to rename one of two KOTLIN declarations, so the message names both: the C#
    // name alone is not something they can search their own source for.
    assertContains(message, "`val count`")
    assertContains(message, "`fun count()`")
  }

  @Test
  fun `an object flattens an inherited property`() {
    val result = Tier1Harness.run(
      """
      package tier1.objectinherited

      open class Stockroom(val origin: String)

      object TreatJar : Stockroom("kitchen") {
        val label: String = "treats"
      }
      """.trimIndent(),
    )

    assertTrue(result.compiledClean, "expected compile; got: ${result.compileErrors}")
    // A C# static class cannot extend anything, so an inherited property has no other carrier.
    assertContains(result.generatedCSharp, "public static string Origin")
    assertContains(result.generated, "tier1.objectinherited.TreatJar.origin")
  }

  /**
   * ROADMAP Phase 4: the method-route twin. Object methods used to be declared-only, so an
   * implemented inherited `fun` vanished with no diagnostic at all; properties flattening while
   * methods did not would have been an inconsistency this item created.
   */
  @Test
  fun `an object flattens an inherited method`() {
    val result = Tier1Harness.run(
      """
      package tier1.objectinheritedfun

      interface Restockable {
        fun restock(): Int = 1
      }

      open class Stockroom(val origin: String) {
        fun size(): Int = origin.length
      }

      object TreatJar : Stockroom("kitchen"), Restockable
      """.trimIndent(),
    )

    assertTrue(result.compiledClean, "expected compile; got: ${result.compileErrors}")
    val cs: String = result.generatedCSharp
    assertContains(cs, "EntryPoint = \"treatjar_size\"")
    // A defaulted interface member is inherited with an implementation too.
    assertContains(cs, "EntryPoint = \"treatjar_restock\"")
    // `Any`'s members are never bound: they carry no C# meaning on a static class.
    assertFalse(cs.contains("treatjar_toString"), cs)
    assertFalse(cs.contains("treatjar_hashCode"), cs)
  }

  /** An INHERITED function collides with a declared property on the same CS0102 grounds. */
  @Test
  fun `an inherited function clashing with a declared property is fatal`() {
    val result = Tier1Harness.run(
      """
      package tier1.objectinheritedclash

      open class Stockroom {
        fun origin(): Int = 1
      }

      object TreatJar : Stockroom() {
        val origin: Int = 2
      }
      """.trimIndent(),
    )

    val message: String = result.kspErrors.joinToString("\n")
    assertContains(message, "TreatJar.Origin")
    assertContains(message, "`val origin`")
    assertContains(message, "`fun origin()`")
  }

  /**
   * ROADMAP Phase 4: an `object` renders as a C# static class, which can neither extend a class nor
   * implement an interface, so every declared supertype is dropped from the declaration. That used
   * to be silent.
   */
  @Test
  fun `an object's dropped supertypes are named`() {
    val result = Tier1Harness.run(
      """
      package tier1.objectsupertypes

      interface Labelled {
        val label: String
      }

      open class Stockroom(val origin: String)

      object TreatJar : Stockroom("kitchen"), Labelled {
        override val label: String = "treats"
      }
      """.trimIndent(),
    )

    val warnings: String = result.kspWarnings.joinToString("\n")
    assertContains(warnings, "TreatJar : Stockroom")
    assertContains(warnings, "cannot extend")
    assertContains(warnings, "TreatJar : Labelled")
    assertContains(warnings, "cannot implement")
    // The members still bind; only the relation is gone.
    assertContains(result.generatedCSharp, "public static string Label")
  }

  @Test
  fun `a nested object's property keeps its owner's export prefix`() {
    val result = Tier1Harness.run(
      """
      package tier1.objectnested

      class Aviary {
        object Defaults {
          val size: Int = 2
        }
      }
      """.trimIndent(),
    )

    assertTrue(result.compiledClean, "expected compile; got: ${result.compileErrors}")
    assertContains(result.generatedCSharp, "EntryPoint = \"aviary_defaults_get_size\"")
  }

  @Test
  fun `a lateinit object property reads through the checked path`() {
    val result = Tier1Harness.run(
      """
      package tier1.objectlateinit

      object TreatJar {
        lateinit var keeper: String
      }
      """.trimIndent(),
    )

    assertTrue(result.compiledClean, "expected compile; got: ${result.compileErrors}")
    val cs: String = result.generatedCSharp
    // The getter must go through the error envelope, so an access before assignment surfaces in C#
    // as a KotlinException instead of crashing the process.
    assertContains(cs, "NugetErrorNative.BuildException(error)")
    assertContains(result.generated, "try {")
  }

  /**
   * Decision 3's probe, kept as a cell: a `StateFlow` property on a COMPANION and at TOP LEVEL is
   * dropped by the planner and has no legacy adapter either, so it must be named too. Both used to
   * ride `recordDropped`'s silence exemption, which was written for the class route's adapters.
   */
  @Test
  fun `companion and top-level state flow properties are named skips too`() {
    val result = Tier1Harness.run(
      """
      package tier1.staticflow

      import kotlinx.coroutines.flow.MutableStateFlow
      import kotlinx.coroutines.flow.StateFlow

      val ambient: StateFlow<Int> = MutableStateFlow(0)

      class Tracker {
        companion object {
          val shared: StateFlow<Int> = MutableStateFlow(0)
        }
      }
      """.trimIndent(),
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )

    assertTrue(
      result.kspWarnings.any { it.contains("tier1.staticflow.ambient") },
      "expected a named skip for the top-level StateFlow property; got: ${result.kspWarnings}",
    )
    assertTrue(
      result.kspWarnings.any { it.contains("Tracker.Companion.shared") },
      "expected a named skip for the companion StateFlow property; got: ${result.kspWarnings}",
    )
    // ...and the silence really was hiding an absence: neither position has a flow adapter, so no
    // C# member is emitted for either of them.
    val cs: String = result.generatedCSharp
    assertFalse(cs.contains("Ambient"), cs)
    assertFalse(cs.contains("Shared"), cs)
  }
}
