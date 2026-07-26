package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-040: an interface-typed return/property position must surface in C# as `IFoo`, backed by a
 * generated concrete `Foo` wrapper class, and get `foo_*` interface-dispatch exports on the
 * Kotlin side. Before this feature these positions were **silently dropped** (verified by a
 * throwaway `Tier1Harness` probe recorded in the ADR, then deleted). The first test below
 * reinstates that probe as a permanent regression test, so the defect this feature fixed cannot
 * silently regress.
 */
class Tier1InterfaceReturnTest {

  /**
   * The regression test for the exact defect ADR-040 fixed. Covers `InterfaceExports.kt`,
   * `ForwardCallablePlanner.interfaceEntries`/`ForwardPropertyPlanner.interfaceProperties`, and
   * both plan emitters in one pass: a class-method interface-typed return, an interface property,
   * and the reachability computation that turns them into a backing class + dispatch exports.
   */
  @Test
  fun `interface-typed return is bridged not silently dropped`() {
    val result = Tier1Harness.run(
      """
      package tier1.interfacereturn

      interface Pet {
        val name: String
        fun speak(): String
      }

      class Cat(override val name: String) : Pet {
        override fun speak(): String = "Meow"
        fun findFriend(): Pet = this
      }
      """.trimIndent()
    )

    assertTrue(result.compiledClean, "expected Cat/Pet to compile; got: ${result.compileErrors}")

    val csharp: String = result.generatedCSharp
    assertContains(csharp, "public interface IPet : IDisposable")
    assertContains(csharp, "public sealed class Pet : IPet")
    assertContains(csharp, "public IPet FindFriend()")

    val kotlin: String = result.generated
    assertContains(kotlin, "pet_get_name")
    assertContains(kotlin, "pet_speak")
    assertContains(kotlin, "pet_dispose")
    assertContains(kotlin, "export_cat_findFriend")
  }

  /**
   * ADR-040 sub-decision C (reachability-driven): an interface that only ever appears in an
   * ADR-039 `add*`/`remove*` subscription pair, never in a *return* position, must get no backing
   * class and no `foo_*` dispatch exports at all — the reachability computation deciding
   * *against* generation, not merely a shape it happens not to reach.
   */
  @Test
  fun `interface never in a return position gets no backing class or dispatch exports`() {
    val result = Tier1Harness.run(
      """
      package tier1.interfaceunreachable

      interface Listener {
        fun onEvent(message: String)
      }

      class Source {
        private val listeners: MutableList<Listener> = mutableListOf()
        fun addListener(listener: Listener) { listeners.add(listener) }
        fun removeListener(listener: Listener) { listeners.remove(listener) }
      }
      """.trimIndent()
    )

    // Not a compile-mode assertion: the ADR-039 add*/remove* legacy route this fixture exercises
    // needs `CFunction`/`staticCFunction` cinterop stand-ins `Tier1CinteropStub` does not provide
    // (no existing Tier 1 fixture compiles that shape either). This is the structural mode
    // instead (ADR-060 "Three assertion modes"): read [Tier1Result.generated]/[generatedCSharp]
    // directly.
    val csharp: String = result.generatedCSharp
    assertFalse(
      csharp.contains("class Listener :"),
      "expected no generated backing class for Listener (never in a planned return position); " +
          "generatedCSharp:\n$csharp",
    )
    assertFalse(
      "listener_dispose" in result.generated,
      "expected no listener_* interface-dispatch exports for Listener; generated=${result.generated}",
    )
  }

  /**
   * ADR-040 breaking-changes note: a Kotlin interface's generated backing class name colliding
   * with an existing C# type in the same namespace (a class also named `Pet`) must fail fast
   * (`ERROR_CSHARP_SIGNATURE_COLLISION`), mirroring the existing duplicate-constructor guard —
   * never emit ambiguous C#.
   */
  @Test
  fun `interface backing class name colliding with an existing class fails generation`() {
    val result = Tier1Harness.run(
      """
      package tier1.interfacecollision

      class Pet(val label: String)

      interface Pet2 {
        fun speak(): String
      }
      """.trimIndent()
    )

    // Sanity: this fixture alone (no interface actually named the same simple name as the
    // colliding class) must NOT report a collision — establishes the guard is name-driven, not a
    // blanket failure whenever any interface and any class coexist.
    assertTrue(
      result.kspErrors.none { it.contains(ForwardDiagnosticKind.ERROR_CSHARP_SIGNATURE_COLLISION.name) },
      "expected no collision for Pet2/Pet since their simple names differ; kspErrors=${result.kspErrors}",
    )
  }

  /** The actual colliding shape: an interface `Pet` and a class also named `Pet`, same namespace. */
  @Test
  fun `interface backing class Pet colliding with class Pet fails generation`() {
    // A Kotlin source file cannot itself declare two top-level "Pet"s in one package (a genuine
    // `Conflicting declarations` compile error, not this ADR's collision at all), so the class and
    // the interface live in different Kotlin packages here. No `nuget.rootPackage` processor
    // option is supplied, so every package collapses to the same default namespace
    // (`mapPackageToNamespace` returns `rootNamespace` unconditionally when `rootPackage` is
    // blank) — a real cross-package collision without needing namespace-mapping options.
    val result = Tier1Harness.run(
      mapOf(
        "PetClass.kt" to """
        package tier1.interfacecollision2.kennel

        class Pet(val label: String)
        """.trimIndent(),
        "PetInterface.kt" to """
        package tier1.interfacecollision2.shelter

        interface Pet {
          fun speak(): String
        }
        """.trimIndent(),
        "Vet.kt" to """
        package tier1.interfacecollision2.shelter

        class Vet {
          fun favorite(): Pet = object : Pet {
            override fun speak(): String = "Meow"
          }
        }
        """.trimIndent(),
      )
    )

    assertTrue(
      result.kspErrors.any { it.contains(ForwardDiagnosticKind.ERROR_CSHARP_SIGNATURE_COLLISION.name) },
      "expected the interface Pet's generated backing class to collide with the existing " +
          "class Pet in the same namespace; kspErrors=${result.kspErrors}",
    )
    assertTrue(
      result.kspErrors.any { it.contains("Pet") },
      "expected the collision diagnostic to name Pet; kspErrors=${result.kspErrors}",
    )
  }

  /**
   * `CirTranslator.resolveStaticClassName`'s interface-backing-class branch: a top-level
   * function's file-derived static class name (`Pet.kt` -> `Pet`) collides with the *interface's*
   * generated backing class, not with an ordinary class/sealed-class — must rename to `PetKt`
   * exactly like the pre-existing class/sealed-class collision cases.
   */
  @Test
  fun `top-level function file name colliding with an interface backing class is renamed to Kt suffix`() {
    val result = Tier1Harness.run(
      mapOf(
        "Pet.kt" to """
        package tier1.staticnameclash

        interface Pet {
          fun speak(): String
        }

        fun greet(): String = "hello"
        """.trimIndent(),
        "Vet.kt" to """
        package tier1.staticnameclash

        class Vet {
          fun adopt(): Pet = object : Pet {
            override fun speak(): String = "Meow"
          }
        }
        """.trimIndent(),
      )
    )

    assertTrue(result.compiledClean, "expected the fixture to compile; got: ${result.compileErrors}")

    val csharp: String = result.generatedCSharp
    assertContains(
      csharp,
      "public static partial class PetKt",
      message = "expected greet()'s static wrapper class to be renamed PetKt to avoid colliding " +
          "with the interface Pet's generated backing class; generatedCSharp:\n$csharp",
    )
    assertContains(csharp, "public sealed class Pet : IPet")
  }

  /**
   * ADR-040 Scope: "collections of interfaces" stays deferred v1 scope. `List<Pet>` as a method
   * *return* is routed through the ordinary `BridgeType.Collection` element-plannability check
   * (`isBridgeableComponent()`), which explicitly excludes `BridgeType.Interface` elements, so
   * `skipReason()` attributes the drop to the element's own reason (`BridgeType.Interface ->
   * ForwardPlanSkipReason.HANDLE`, verified through this harness) — `SKIPPED_UNSUPPORTED_TYPE`,
   * not the generic collection bucket — rather than silently emitting an untested shape.
   */
  @Test
  fun `List of interface return fires a named skip diagnostic and is omitted`() {
    val result = Tier1Harness.run(
      """
      package tier1.interfacecollectionskip

      interface Pet {
        fun speak(): String
      }

      class Shelter {
        fun pets(): List<Pet> = listOf(object : Pet {
          override fun speak(): String = "Meow"
        })
      }
      """.trimIndent()
    )

    assertTrue(result.compiledClean, "expected no broken source for Shelter.pets; got: ${result.compileErrors}")
    assertFalse(
      "export_shelter_pets" in result.generated,
      "expected Shelter.pets to be entirely absent from the generated CNameExports.kt; " +
          "generated=${result.generated}",
    )
    assertTrue(
      result.kspWarnings.any { it.contains(ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_TYPE.name) },
      "expected a named skip diagnostic for Shelter.pets's List<Pet> return; " +
          "kspWarnings=${result.kspWarnings}",
    )
  }

  /**
   * `CirClassTranslator.isOpenInterfaceImplementation`: a class implementing an interface member
   * (no CLASS supertype) is open by Kotlin default unless `final`, so C# needs `virtual` on the
   * base declaration for a further subclass `override` to compile (`CS0506` otherwise). Covers
   * both the property and method cases in one fixture, mirroring `Animal`/`Cat`'s real shape in
   * `test-library`.
   */
  @Test
  fun `class implementing an interface member further overridden by a subclass renders virtual then override`() {
    val result = Tier1Harness.run(
      """
      package tier1.openinterfaceimpl

      interface Pet {
        val nickname: String?
        fun fetch(item: String): String
      }

      abstract class Animal(val name: String) : Pet {
        override val nickname: String? = null
        override fun fetch(item: String): String = "${'$'}name sniffs the ${'$'}item"
      }

      class Cat(name: String) : Animal(name) {
        override val nickname: String? = "${'$'}{name}y"
        override fun fetch(item: String): String = "${'$'}name fetches the ${'$'}item"
      }
      """.trimIndent()
    )

    assertTrue(result.compiledClean, "expected Animal/Cat to compile; got: ${result.compileErrors}")

    val csharp: String = result.generatedCSharp
    assertContains(
      csharp,
      "public virtual string? Nickname",
      message = "expected Animal's interface-implementing Nickname property to render virtual " +
          "so Cat's override compiles; generatedCSharp:\n$csharp",
    )
    assertContains(
      csharp,
      "public virtual string Fetch(string item)",
      message = "expected Animal's interface-implementing Fetch method to render virtual so " +
          "Cat's override compiles; generatedCSharp:\n$csharp",
    )
    assertContains(csharp, "public override string? Nickname")
    assertContains(csharp, "public override string Fetch(string item)")
  }

  /**
   * `mapInterfacePropertyType` threading [com.google.devtools.ksp.symbol.KSType.isMarkedNullable]:
   * the plain `IFoo` interface declaration (distinct from the concrete backing class, which goes
   * through the ordinary planned-property projection) must render a nullable interface property
   * as `string?`, not the non-nullable `string` it rendered before this ADR (`CS8766` against a
   * correctly-nullable implementer's getter otherwise).
   */
  @Test
  fun `nullable interface property renders nullable in the IFoo interface declaration`() {
    val result = Tier1Harness.run(
      """
      package tier1.interfacenullableprop

      interface Pet {
        val nickname: String?
      }

      class Cat(override val nickname: String?) : Pet
      """.trimIndent()
    )

    assertTrue(result.compiledClean, "expected Pet/Cat to compile; got: ${result.compileErrors}")
    assertContains(
      result.generatedCSharp,
      "string? Nickname { get; }",
      message = "expected IPet's Nickname to render nullable in the interface declaration; " +
          "generatedCSharp:\n${result.generatedCSharp}",
    )
  }
}
