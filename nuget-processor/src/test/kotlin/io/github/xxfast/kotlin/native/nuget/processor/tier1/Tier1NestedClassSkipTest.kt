package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The nested class/object gate, the last member of the family [Tier1UndeclaredEnumSkipTest] and
 * [Tier1NestedInterfaceSkipTest] already cover.
 *
 * Every root bucket in `NugetProcessor` filters `parentDeclaration == null`, so a plain nested
 * `class` or `object` is never declared in C#. Two things were missing:
 *
 * - the declaration itself produced NO diagnostic at all, in any bucket: it simply vanished
 *   (`SKIPPED_NESTED_DECLARATION` now names it once, where it is declared).
 * - a member typed with one skipped as the generic `SKIPPED_UNSUPPORTED_TYPE`/`UNSUPPORTED`, whose
 *   "expose a bridgeable adapter" hint is wrong advice for a type no adapter can make declarable,
 *   and a *nullable* one blamed `NULLABLE` instead, telling the author to un-nullable a type that
 *   can never bind at any position.
 *
 * `label` is the control: a gate that drops the whole owning class is distinguishable from one
 * that drops only the members typed with the nested declaration.
 */
class Tier1NestedClassSkipTest {

  private val source: String = """
    package tier1.nestedclass

    class Owner {
      data class Nested(val x: String?)
      object Single

      companion object {
        fun create(): Owner = Owner()
      }

      fun make(): Nested = Nested("a")
      fun maybe(): Nested? = null
      val nested: Nested = Nested("a")
      fun single(): Single = Single

      val label: String = "owner"
    }

    class Quiet {
      class Unused
    }
  """.trimIndent()

  @Test
  fun `a nested class or object is never spelled in the generated C#`() {
    val result = Tier1Harness.run(source)

    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    listOf("Nested", "Single", "Unused").forEach { nested ->
      assertFalse(
        result.generatedCSharp.contains(nested),
        "expected no declaration of, or dangling reference to, $nested; generatedCSharp=" +
            "${result.generatedCSharp.lines().filter { it.contains(nested) }}",
      )
    }
    listOf(
      "export_owner_make",
      "export_owner_maybe",
      "export_owner_get_nested",
      "export_owner_single",
    ).forEach { export ->
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
  fun `every nested declaration is named once, and the companion is not`() {
    val result = Tier1Harness.run(source)

    val nestedWarnings: List<String> = result.kspWarnings
      .filter { it.contains(ForwardDiagnosticKind.SKIPPED_NESTED_DECLARATION.name) }
    listOf(
      "tier1.nestedclass.Owner.Nested",
      "tier1.nestedclass.Owner.Single",
      "tier1.nestedclass.Quiet.Unused",
    ).forEach { declaration ->
      val warning: String = requireNotNull(
        nestedWarnings.firstOrNull { it.contains(declaration) },
      ) { "expected $declaration to be named once; nestedWarnings=$nestedWarnings" }
      assertTrue(
        warning.contains("move it to the top level"),
        "expected the move-to-top-level fix; got: $warning",
      )
    }
    // A companion object is declared in C# as its owner's statics (ADR-013), so it is emphatically
    // not an undeclared nested declaration.
    assertFalse(
      nestedWarnings.any { it.contains("Companion") },
      "expected no nested-declaration warning for the companion; nestedWarnings=$nestedWarnings",
    )
    assertTrue(
      result.generated.contains("export_owner_create"),
      "expected the companion's own member to still bind; generated=${result.generated}",
    )
  }

  @Test
  fun `members typed with a nested class skip as UNDECLARED_CLASS, naming it`() {
    val result = Tier1Harness.run(source)

    mapOf(
      "Owner.make" to "tier1.nestedclass.Owner.Nested",
      "Owner.maybe" to "tier1.nestedclass.Owner.Nested",
      "Owner.single" to "tier1.nestedclass.Owner.Single",
    ).forEach { (member, nested) ->
      val diagnostic: String = requireNotNull(
        result.kspWarnings.firstOrNull { it.contains("SKIPPED_") && it.contains(member) },
      ) { "expected a skip diagnostic for $member; kspWarnings=${result.kspWarnings}" }
      assertTrue(
        diagnostic.contains("UNDECLARED_CLASS"),
        "expected $member to skip as UNDECLARED_CLASS, not the generic bucket; got: $diagnostic",
      )
      assertTrue(
        diagnostic.contains(nested) && diagnostic.contains("move it to the top level"),
        "expected the $member diagnostic to name $nested and the fix; got: $diagnostic",
      )
    }
    // The nullable return is the position that used to blame NULLABLE: undeclarable outranks
    // position-shaped reasons, exactly as it does for a nested enum or interface.
    val maybe: String = result.kspWarnings.first {
      it.contains("SKIPPED_") && it.contains("Owner.maybe")
    }
    assertFalse(
      maybe.contains("NULLABLE"),
      "expected the nullable return to blame the undeclared type, not nullability; got: $maybe",
    )
  }
}
