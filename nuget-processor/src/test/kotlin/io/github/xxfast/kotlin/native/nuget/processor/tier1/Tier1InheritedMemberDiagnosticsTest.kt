package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * ROADMAP (below the value-class block): the two coupled inherited-member items, pinned as
 * behaviour rather than argued from source.
 *
 * The first item assumed ordinary classes *drop* inherited members with no diagnostic, the way a
 * value class does, and that the parity fix is to warn. [ordinary base-class members are reachable
 * through C# inheritance, so no member is lost] shows that assumption is wrong for the base-class
 * case: the export is not duplicated onto the subclass, but the C# subclass extends the C# base
 * class, so every inherited member stays callable. A `SKIPPED_INHERITED_MEMBER` there would name a
 * member that works. A value class is a `struct` with no base type, which is why its drop is real.
 *
 * The second item's has-superclass divergence (`CirClassTranslator.superClass` counts only
 * `ClassKind.CLASS` supertypes, `ForwardPropertyPlanner.hasSuperClass` counts any non-`Any`
 * supertype) shows up in [interface-only class binds its defaulted interface members], which is
 * where the genuine drop lives, together with a generated C# type that cannot compile.
 */
class Tier1InheritedMemberDiagnosticsTest {

  /**
   * Characterization, not aspiration. `Dog : Animal` generates `public class Dog : Animal` in C#,
   * so `Speak()`/`Legs`/`Name` are reachable on a `Dog` through ordinary C# inheritance against
   * `Animal`'s own exports (the `StableRef` behind the handle is a `Dog`, which is-a `Animal`).
   * The planner's `parentDeclaration == cls` filter therefore drops a duplicate *export*, not a
   * member of the C# API, so this cell asserts that **no** inherited-member diagnostic fires here.
   */
  @Test
  fun `ordinary base-class members stay reachable through C# inheritance with no diagnostic`() {
    val result = Tier1Harness.run(
      """
      package tier1.inherited

      open class Animal(val name: String) {
        fun speak(): String = "..."
        val legs: Int get() = 4
      }

      class Dog(name: String) : Animal(name) {
        fun fetch(): String = "ball"
      }
      """.trimIndent()
    )

    assertTrue(
      result.compiledClean,
      "expected no broken source for Dog; got: ${result.compileErrors}",
    )
    assertTrue(
      "export_dog_fetch" in result.generated,
      "expected Dog's own declared method to bind; generated=${result.generated}",
    )
    listOf("export_dog_speak", "export_dog_get_legs", "export_dog_get_name").forEach { export ->
      assertTrue(
        export !in result.generated,
        "expected no duplicate $export on the subclass; generated=${result.generated}",
      )
    }
    listOf("export_animal_speak", "export_animal_get_legs", "export_animal_get_name").forEach { export ->
      assertTrue(
        export in result.generated,
        "expected the inherited member to keep its export on the declaring class; " +
            "generated=${result.generated}",
      )
    }
    assertTrue(
      "public class Dog : Animal" in result.generatedCSharp,
      "expected the C# subclass to extend the C# base class, which is what keeps Speak()/Legs/" +
          "Name callable on a Dog; generatedCSharp=${result.generatedCSharp}",
    )
    assertTrue(
      result.kspWarnings.none {
        it.contains(ForwardDiagnosticKind.SKIPPED_INHERITED_MEMBER.name)
      },
      "expected no inherited-member diagnostic: nothing was skipped from the C# API, only the " +
          "duplicate export; kspWarnings=${result.kspWarnings}",
    )
  }

  @Test
  fun `class with no supertypes fires no inherited-member diagnostic`() {
    val result = Tier1Harness.run(
      """
      package tier1.inheritednone

      class Patient(val name: String) {
        fun greet(): String = "hi"
      }
      """.trimIndent()
    )

    assertTrue(
      result.compiledClean,
      "expected no broken source for Patient; got: ${result.compileErrors}",
    )
    assertTrue(
      result.kspWarnings.none {
        it.contains(ForwardDiagnosticKind.SKIPPED_INHERITED_MEMBER.name)
      },
      "expected no inherited-member diagnostic for a class whose only supertype is Any " +
          "(equals/hashCode/toString are excluded by name, not by inheritance); " +
          "kspWarnings=${result.kspWarnings}",
    )
  }

  /**
   * The has-superclass probe, and the one place an ordinary class genuinely loses a member.
   *
   * `Host : Greeter` has no `ClassKind.CLASS` supertype, so `CirClassTranslator.superClass` is
   * null and its member filters keep the inherited members, while `ForwardCallablePlanner
   * .classEntries` (unconditional `parentDeclaration == cls`) and `ForwardPropertyPlanner
   * .classProperties` (`hasSuperClass` counts the interface) plan neither. The two predicates
   * therefore agree on the *class* output only by accident: nothing emits the member, but the C#
   * interface `IGreeter` still declares `Greeting`/`Greet()` and `Host` is still rendered as
   * `public class Host : IGreeter`, which is CS0535 in every consumer. Today: no export, no C#
   * member, and no diagnostic of any kind.
   *
   * The body states the correct behaviour (a defaulted interface member is a real, callable member
   * of `Host`, so it should bind), not the buggy one.
   */
  @Test
  @XFail("ROADMAP: interface-only class drops its defaulted interface members, emitting a C# type that does not implement the interface it declares")
  fun `interface-only class binds its defaulted interface members`() {
    val result = Tier1Harness.run(
      """
      package tier1.inheritediface

      interface Greeter {
        val greeting: String get() = "hello"
        fun greet(): String = greeting
      }

      class Host(val id: Int) : Greeter
      """.trimIndent()
    )

    assertTrue(
      result.compiledClean,
      "expected no broken source for Host; got: ${result.compileErrors}",
    )
    assertTrue(
      "export_host_greet" in result.generated && "export_host_get_greeting" in result.generated,
      "expected Host's defaulted interface members to bind; generated=${result.generated}",
    )
    assertTrue(
      "public class Host : IGreeter" in result.generatedCSharp &&
          "public string Greet()" in result.generatedCSharp,
      "expected the C# class to implement every member of the interface it declares (CS0535 " +
          "otherwise); generatedCSharp=${result.generatedCSharp}",
    )
  }
}
