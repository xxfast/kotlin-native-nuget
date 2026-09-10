package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The nested-interface membership gate, the sibling of [Tier1UndeclaredEnumSkipTest]'s shape (a).
 *
 * `rootInterfaces` (`NugetProcessor.kt`) filters `parentDeclaration == null`, exactly as
 * `rootEnums` does for enums, so an `interface` nested inside an exported class is never declared
 * in C#. The classifier's `interfaceType` membership check already skips every member typed with
 * it, which is correct and stays. What was wrong was the *diagnostic*: the skip landed in the
 * generic "UNSUPPORTED type combination" bucket without ever naming the interface, and the nullable
 * return position blamed `NULLABLE` instead, so the author was told to write a non-nullable wrapper
 * for a type that can never bind at any position.
 *
 * Every classifier-fed position the nested interface occupies here (parameter, nullable parameter,
 * nullable return, property) must skip named, with the top-level fix in the hint where the route
 * carries one.
 * `label` rides along as the control: a gate that drops the whole owning class is distinguishable
 * from one that drops only the interface-typed members.
 */
class Tier1NestedInterfaceSkipTest {

  private val source: String = """
    package tier1.nestedinterface

    class Owner {
      interface Listener { fun onEvent(): String }

      var listener: Listener? = null
      fun attach(listener: Listener) {}
      fun detach(listener: Listener?) {}
      fun current(): Listener? = listener

      val label: String = "owner"
    }
  """.trimIndent()

  @Test
  fun `a nested interface is never spelled in the generated C#`() {
    val result = Tier1Harness.run(source)

    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    assertFalse(
      result.generatedCSharp.contains("IListener"),
      "expected no dangling reference to the projected nested interface; generatedCSharp=" +
          "${result.generatedCSharp.lines().filter { it.contains("Listener") }}",
    )
    assertFalse(
      result.generatedCSharp.contains("Owner.Listener"),
      "expected no dangling reference to the nested interface; generatedCSharp=" +
          "${result.generatedCSharp.lines().filter { it.contains("Listener") }}",
    )
    listOf(
      "export_owner_attach",
      "export_owner_detach",
      "export_owner_current",
      "export_owner_get_listener",
    )
      .forEach { export ->
        assertFalse(
          result.generated.contains(export),
          "expected $export to be absent from the generated exports; generated=${result.generated}",
        )
      }
    assertTrue(
      result.generated.contains("export_owner_get_label"),
      "expected the control member to survive the gate; generated=${result.generated}",
    )
  }

  @Test
  fun `the parameter and return positions skip named with the top-level hint`() {
    val result = Tier1Harness.run(source)

    listOf("Owner.attach", "Owner.detach", "Owner.current").forEach { member ->
      val diagnostic: String = requireNotNull(
        result.kspWarnings.firstOrNull { it.contains("SKIPPED_") && it.contains(member) },
      ) { "expected a skip diagnostic for $member; kspWarnings=${result.kspWarnings}" }
      assertTrue(
        diagnostic.contains("tier1.nestedinterface.Owner.Listener"),
        "expected the $member diagnostic to name the undeclared nested interface; got: $diagnostic",
      )
      assertTrue(
        diagnostic.contains("nested interface") && diagnostic.contains("move it to the top level"),
        "expected the $member diagnostic to name the move-to-top-level fix; got: $diagnostic",
      )
    }
  }

  @Test
  fun `the nested-interface property skips named too`() {
    val result = Tier1Harness.run(source)

    assertTrue(
      result.kspWarnings.any {
        it.contains("SKIPPED_UNSUPPORTED_PROPERTY") &&
            it.contains("Owner.listener") &&
            it.contains("tier1.nestedinterface.Owner.Listener") &&
            // ADR-064's 2026-09-11 amendment: same reason, same hint, at a property position too.
            it.contains("move it to the top level")
      },
      "expected the property route to skip naming the undeclared nested interface and its " +
          "move-to-top-level fix; kspWarnings=${result.kspWarnings}",
    )
  }
}
