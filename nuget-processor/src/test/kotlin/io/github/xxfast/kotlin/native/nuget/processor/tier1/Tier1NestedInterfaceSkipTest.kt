package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-133 flips the nested-interface membership gate: an `interface` nested in an exported class is
 * declared as `Owner.IListener`, with the ADR-040 backing wrapper `Owner.Listener` nested beside it
 * rather than at namespace root.
 *
 * The placement is the whole point, and it is only visible here. `interfaceType` used to spell an
 * interface `global::$namespace.I$simpleName` with no enclosing scope, so the naive flip emits a
 * namespace-root `IListener` that nothing declares (CS0426/CS0246 in the consumer), and prefixing
 * the whole chain gives the equally wrong `IOwner.Listener`. The `I` attaches to the last segment
 * only.
 *
 * Whether a *nullable* interface position binds is orthogonal and ADR-133 does not decide it, so
 * the nullable cells pin only that the three nullable positions agree with each other.
 */
class Tier1NestedInterfaceSkipTest {

  private val source: String = """
    package tier1.nestedinterface

    class Owner {
      interface Listener { fun onEvent(): String }

      var attached: Listener? = null
      fun attach(listener: Listener) {}
      fun detach(listener: Listener?) {}
      fun current(): Listener? = attached

      val label: String = "owner"
    }
  """.trimIndent()

  @Test
  fun `the nested interface is declared inside its owner, with the I on the last segment`() {
    val result = Tier1Harness.run(source)

    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    val csharp: String = result.generatedCSharp
    // Was: assertFalse(csharp.contains("IListener")).
    assertContains(csharp, "public interface IListener")
    assertFalse(
      csharp.contains("IOwner.Listener"),
      "expected the `I` on the last segment only, never `IOwner.Listener`; csharp=" +
          "${csharp.lines().filter { it.contains("Listener") }}",
    )
    assertFalse(
      Regex("""^ {4}public (?:sealed )?(?:class|interface) I?Listener\b""", RegexOption.MULTILINE)
        .containsMatchIn(csharp),
      "expected no namespace-level IListener/Listener (the pre-2026-09-07 flattening); csharp=" +
          "${csharp.lines().filter { it.contains("Listener") }}",
    )
    assertFalse(
      result.kspWarnings.any {
        it.contains(ForwardDiagnosticKind.SKIPPED_NESTED_DECLARATION.name) &&
            it.contains("tier1.nestedinterface.Owner.Listener")
      },
      "expected the nested interface to be declared, not skipped; kspWarnings=${result.kspWarnings}",
    )
  }

  @Test
  fun `the non-null parameter position binds through the chain-prefixed exports`() {
    val result = Tier1Harness.run(source)

    // Was: assertFalse(generated.contains("export_library_tier1_nestedinterface__owner_attach")).
    assertContains(result.generated, "export_library_tier1_nestedinterface__owner_attach")
    assertContains(result.generated, "@CName(\"library_tier1_nestedinterface__owner_attach\")")
    // The interface's own ADR-040 dispatch exports carry the enclosing chain too.
    assertTrue(
      result.generated.contains("owner_listener_"),
      "expected the nested interface's exports to carry the `owner_listener_` chain prefix; " +
          "generated=${result.generated}",
    )
    assertTrue(
      result.generated.contains("export_library_tier1_nestedinterface__owner_get_label"),
      "expected the control member to keep binding; generated=${result.generated}",
    )
  }

  @Test
  fun `the three nullable interface positions agree with each other`() {
    val result = Tier1Harness.run(source)

    // A nullable interface is a capability ADR-133 does not settle; what it must not do is bind one
    // nullable position and drop another, which would be a bug in the nullable path, not nesting.
    val bound: List<Boolean> = listOf(
      "export_library_tier1_nestedinterface__owner_detach",
      "export_library_tier1_nestedinterface__owner_current",
      "export_library_tier1_nestedinterface__owner_get_attached",
    ).map { result.generated.contains(it) }

    assertEquals(
      1,
      bound.distinct().size,
      "expected the nullable parameter, return and property positions to agree; generated=" +
          "${result.generated}",
    )
  }
}
