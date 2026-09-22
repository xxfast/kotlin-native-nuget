package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-135: an interface reached only at a *parameter* position (or only as an ADR-132 extension
 * receiver) joins the ADR-084 bridge reachability set, and the transfer-handle cleanup is safe
 * against a throw from the mint itself.
 *
 * The shipped fixtures never caught this because every positioned interface in `test-library` is
 * also returned somewhere, so the return-only walk covered them by accident. These cells use
 * interfaces that are returned by nothing, nested and top-level alike, since nothing in the walk
 * looks at nesting.
 */
class Tier1InterfaceParameterReachabilityTest {

  private val source: String = """
    package tier1.paramreach

    object Boarding {
      interface Clerk {
        fun stamp(): String
      }

      fun fileVia(clerk: Clerk): String = "${'$'}{clerk.stamp()} filed"
    }

    interface Doorman {
      fun buzz(): String
    }

    fun buzzIn(doorman: Doorman): String = "${'$'}{doorman.buzz()}, come in"

    interface Sitter {
      fun house(): String
    }

    fun Sitter.checkIn(): String = "checked in at ${'$'}{house()}"
  """.trimIndent()

  @Test
  fun `a parameter-only interface gets a bridge factory on both halves, nested and top-level`() {
    val result = Tier1Harness.run(source)
    assertTrue(result.compiledClean, "expected the fixture to bind; got: ${result.compileErrors}")

    val kotlin: String = result.generated
    // Nested: the export name carries the enclosing chain exactly as a return-reachable one does.
    assertContains(kotlin, "@CName(\"library_tier1_paramreach__boarding_clerk_bridge_create\")")
    // Top-level: nothing in the reachability walk looks at nesting, so a fix that special-cases
    // the nested path has to fail here.
    assertContains(kotlin, "@CName(\"library_tier1_paramreach__doorman_bridge_create\")")

    val cs: String = result.generatedCSharp
    assertContains(cs, "internal sealed class Tier1ParamreachBoardingClerkBridgeState : NugetBridgeState")
    assertContains(cs, "internal sealed class Tier1ParamreachDoormanBridgeState : NugetBridgeState")
    assertContains(cs, "EntryPoint = \"library_tier1_paramreach__boarding_clerk_bridge_create\"")
    assertContains(cs, "EntryPoint = \"library_tier1_paramreach__doorman_bridge_create\"")
    // The bridge layer exists at all, so the ADR-040 boundary throw is gone.
    assertContains(cs, "return NugetBridge.HandleFor(value);")
    assertFalse(
      cs.contains("passing a C#-implemented interface is not supported yet"),
      "a parameter-only interface is bridgeable, so the boundary exception must be gone",
    )
  }

  @Test
  fun `a receiver-only interface is reachable through the RECEIVER slot`() {
    val result = Tier1Harness.run(source)

    // `Sitter` is never a declared parameter and never a return: it is only an ADR-132 extension
    // receiver, which the planner carries as a RECEIVER-role ABI slot rather than in
    // `publicSignature.parameters`. Walking the public parameters alone leaves this one unbridged.
    assertContains(result.generated, "@CName(\"library_tier1_paramreach__sitter_bridge_create\")")
    assertContains(
      result.generatedCSharp,
      "internal sealed class Tier1ParamreachSitterBridgeState : NugetBridgeState",
    )
  }

  @Test
  fun `the transfer-handle cleanup is guarded against a failed mint`() {
    val result = Tier1Harness.run(source)
    val cs: String = result.generatedCSharp

    // The mint can throw (a C# object implementing no bridgeable interface), and this `finally`
    // is reached with the handle still Zero. `nuget_dispose` is not null-safe, so the guard is
    // what turns a dead host into the managed NotSupportedException.
    assertContains(
      cs,
      "if (clerkOwned && clerkHandle != IntPtr.Zero) { NugetMarshal.Dispose(clerkHandle); }",
    )
    assertContains(
      cs,
      "if (doormanOwned && doormanHandle != IntPtr.Zero) { NugetMarshal.Dispose(doormanHandle); }",
    )
    // The receiver shares the parameter lowering, so it gets the same guard. Its locals are named
    // off the RECEIVER slot (`receiver`), not off the C# parameter name.
    assertContains(
      cs,
      "if (receiverOwned && receiverHandle != IntPtr.Zero) { NugetMarshal.Dispose(receiverHandle); }",
    )
    // And `owned` is only true once a handle was actually minted.
    assertContains(cs, "IntPtr handle = HandleOf(value);")
  }
}
