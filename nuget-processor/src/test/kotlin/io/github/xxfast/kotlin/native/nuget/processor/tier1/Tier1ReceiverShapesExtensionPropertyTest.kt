package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-132 receiver shapes, one slot to the right: the same lowering at the extension **property**
 * position that `Tier1ReceiverShapesExtensionTest` pins at the extension function position. An
 * INTERFACE receiver takes the ADR-084 stage-3 lifecycle (`HandleOf(receiver, out receiverOwned)`
 * on the C# side, a borrowed `StableRef` read on the Kotlin side, an ADR-135-guarded `Dispose` in
 * the `finally`), and a NULLABLE HANDLE receiver crosses as a null-or-pointer wire. The getter is
 * the new surface here: it used to be a flat body with no scope, so a minted receiver handle had
 * nowhere to be released.
 *
 * The narrow set is deliberate (decided 2026-09-14): only `Interface`, `Nullable(Interface)` and
 * `Nullable(ObjectHandle)` join the receivers this route already admitted. Everything else, the
 * has-value fan-out shapes included, stays a named skip, which the last cell controls.
 */
class Tier1ReceiverShapesExtensionPropertyTest {

  private val source: String = """
    package tier1.receivershapesprop

    interface Pet {
      val name: String
      val legs: Int
      fun speak(): String
    }

    class Cat(val title: String) : Pet {
      override val name: String = title
      override val legs: Int = 4
      override fun speak(): String = "Meow"
    }

    private val tags: MutableMap<String, String> = mutableMapOf()

    val Pet.summary: String get() = "${'$'}name/${'$'}legs/${'$'}{speak()}"

    val Cat?.nameOrStray: String get() = this?.title ?: "stray"

    var Pet.tag: String
      get() = tags[name] ?: "untagged"
      set(value) { tags[name] = value }
  """.trimIndent()

  @Test
  fun `an interface receiver reads a borrowed StableRef and compiles`() {
    val result = Tier1Harness.run(source)

    assertTrue(
      result.compiledClean,
      "expected the interface and nullable handle receivers to compile; got: ${result.compileErrors}",
    )
    // Identical to the extension *function* receiver: the ADR-084 bridge object backing a
    // C#-implemented pet is itself a Kotlin `Pet`, so one read serves both implementations.
    assertContains(
      result.generated,
      "receiver.asStableRef<tier1.receivershapesprop.Pet>().get().summary",
    )
  }

  @Test
  fun `an interface receiver binds as a C# extension on the interface and disposes its transfer handle`() {
    val result = Tier1Harness.run(source)
    val cs: String = result.generatedCSharp

    assertContains(cs, "public static string GetSummary(this global::Interop.IPet receiver)")
    assertContains(cs, "NugetMarshal.HandleOf(receiver, out receiverOwned)")
    // ADR-135's guard: the mint itself can throw, and this `finally` is then reached with the
    // handle still Zero. Without the scope at all, the getter leaked one StableRef per read.
    assertContains(
      cs,
      "if (receiverOwned && receiverHandle != IntPtr.Zero) { NugetMarshal.Dispose(receiverHandle); }",
    )
  }

  @Test
  fun `a nullable handle receiver binds as a C# extension on the nullable wrapper`() {
    val result = Tier1Harness.run(source)

    assertContains(
      result.generatedCSharp,
      "public static string GetNameOrStray(this global::Interop.Cat? receiver)",
    )
    // Parenthesised: `a?.b()?.c().d` binds `.d` to the safe-called result, which is not what the
    // `Cat?` extension declares its receiver to be.
    assertContains(
      result.generated,
      "(receiver?.asStableRef<tier1.receivershapesprop.Cat>()?.get()).nameOrStray",
    )
  }

  /**
   * COMPILE PIN ONLY: no runtime fixture sets a `var` over an interface receiver, so this cell is
   * where the setter half is exercised at all. Both accessors share one receiver slot, so the
   * setter's receiver mint and its value lowering have to live in the same handle scope: the
   * receiver's `Dispose` belongs in the same `finally` the value's would.
   */
  @Test
  fun `a var over an interface receiver exports both accessors through one receiver slot`() {
    val result = Tier1Harness.run(source)
    val cs: String = result.generatedCSharp

    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")
    assertContains(cs, "public static string GetTag(this global::Interop.IPet receiver)")
    assertContains(
      cs,
      "public static void SetTag(this global::Interop.IPet receiver, string value)",
    )
    assertContains(
      result.generated,
      "receiver.asStableRef<tier1.receivershapesprop.Pet>().get().tag = value",
    )
  }

  /**
   * ROADMAP line 22 / ADR-135: an interface that appears **only** as an extension property's
   * receiver still has to reach the bridge factory. That arm of `reachableInterfaceNames` reads
   * the property plan's RECEIVER slot and nothing else in the fixture set exercises it.
   */
  @Test
  fun `a receiver-only interface is reachable through the property plan's RECEIVER slot`() {
    val result = Tier1Harness.run(
      """
      package tier1.propreceiverreach

      interface Sitter {
        fun house(): String
      }

      val Sitter.address: String get() = "at ${'$'}{house()}"
      """.trimIndent(),
    )

    assertContains(result.generated, "@CName(\"sitter_bridge_create\")")
    assertContains(
      result.generatedCSharp,
      "internal sealed class SitterBridgeState : NugetBridgeState",
    )
    // ROADMAP line 28: this fixture used to carry an unrelated `fun frontDoor(): String` purely to
    // open `CirTranslator.needsCoreMarshal`. Without it the module rendered `NugetMarshal.HandleOf`
    // calls and declared none of the helpers behind them.
    assertContains(result.generatedCSharp, "internal static class NugetMarshal")
    assertContains(result.generatedCSharp, "internal static class NugetErrorNative")
  }

  /**
   * The control: a has-value fan-out receiver is unrepresentable on this route (one
   * `valueParameter` mints exactly one slot), so it stays a named `SKIPPED_UNSUPPORTED_PROPERTY`
   * with no export on either side, never a half-rendered one.
   */
  @Test
  fun `a fan-out receiver is a named skip, not an export`() {
    val result = Tier1Harness.run(
      """
      package tier1.propreceiverfanout

      val Int?.orZero: Int get() = this ?: 0
      """.trimIndent(),
    )

    assertTrue(result.kspErrors.isEmpty(), "expected no KSP error; got: ${result.kspErrors}")
    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")
    assertFalse(
      result.generated.contains("orZero"),
      "a fan-out receiver must not render an export at all",
    )
    assertFalse(
      // Word-bounded, accessor prefixes included (this route would render `GetOrZero`): the marshal
      // helper is declared even for a module that exports nothing (ADR-129 amendment), and it
      // declares `HandleOfOrZero`, which a bare `contains("OrZero")` now matches.
      Regex("\\b(Get|Set)?OrZero\\b").containsMatchIn(result.generatedCSharp),
      "a fan-out receiver must not render a C# binding at all",
    )
    assertTrue(
      result.kspWarnings.any { warning -> warning.contains("orZero") },
      "the drop must name itself; got: ${result.kspWarnings}",
    )
  }

}
