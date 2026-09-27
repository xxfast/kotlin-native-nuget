package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-039 amendment (2026-09-26): the `add*`/`remove*` subscription route carries a listener
 * member's parameters only over ADR-160's callback payload set (a primitive by value, a `String`,
 * an enum ordinal, an exported object or interface handle) and only a `Unit` return, because it
 * has nothing but `Void` delegates. Its hand-written `isPrimitive` test (`startsWith("kotlin.") &&
 * != "String"`) admitted `List`, `Map`, `Pair`, `Any`, arrays and nullables and wired them as
 * `int`, and a non-`Unit` member rendered `override fun count() {`, so every one of those shapes
 * broke the build on one half or both with an error pointing at generated code.
 *
 * The whole pair is refused (the Kotlin half builds `object : Watcher { ... }`, which must
 * override every member, and the C# implementer must implement every `IWatcher` member), named on
 * both `add` and `remove` with the listener member and the type.
 */
class Tier1InterfaceBridgePayloadTest {

  private fun source(pkg: String, listener: String, extra: String = ""): String =
    """
    package tier1.$pkg

    $extra

    interface Watcher {
      $listener
    }

    class Kennel {
      private val watchers = mutableListOf<Watcher>()
      fun addWatcher(w: Watcher) { watchers.add(w) }
      fun removeWatcher(w: Watcher) { watchers.remove(w) }
      fun size(): Int = watchers.size
    }
    """.trimIndent()

  private fun assertRefused(
    pkg: String,
    listener: String,
    member: String,
    type: String,
    kind: ForwardDiagnosticKind = ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_INPUT,
  ): Tier1Result {
    val result = Tier1Harness.run(source(pkg, listener))
    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")
    assertFalse(
      result.generated.contains("_kennel_addWatcher") ||
          result.generated.contains("_kennel_removeWatcher"),
      "no Kotlin subscription export; generated=${result.generated}",
    )
    assertFalse(
      result.generatedCSharp.contains("AddWatcher"),
      "no C# subscription method; cs=${result.generatedCSharp}",
    )
    // The rest of the class is untouched.
    assertContains(result.generated, "_kennel_size")
    listOf("addWatcher", "removeWatcher").forEach { pairMember ->
      assertTrue(
        result.kspWarnings.any { warning ->
          warning.contains(kind.name) &&
              warning.contains("Kennel.$pairMember") &&
              warning.contains("`addWatcher` / `removeWatcher`") &&
              warning.contains("Watcher") &&
              warning.contains(member) &&
              warning.contains(type)
        },
        "expected $pairMember to be named with $member / $type; kspWarnings=${result.kspWarnings}",
      )
    }
    return result
  }

  @Test
  fun `a List parameter refuses the pair by name`() {
    val result = assertRefused("bplist", "fun onBatch(items: List<Int>)", "onBatch", "List<Int>")
    assertFalse(result.generatedCSharp.contains("NugetListVoidCallback"))
  }

  @Test
  fun `a Map parameter refuses the pair by name`() {
    assertRefused("bpmap", "fun onMap(m: Map<String, Int>)", "onMap", "Map<String, Int>")
  }

  @Test
  fun `a Pair parameter refuses the pair by name`() {
    assertRefused("bppair", "fun onPair(p: Pair<Int, Int>)", "onPair", "Pair<Int, Int>")
  }

  @Test
  fun `an Any parameter refuses the pair by name`() {
    val result = assertRefused("bpany", "fun onThing(thing: Any)", "onThing", "Any")
    assertFalse(result.generatedCSharp.contains("NugetAnyVoidCallback"))
  }

  @Test
  fun `an IntArray parameter refuses the pair by name`() {
    assertRefused("bparr", "fun onArr(a: IntArray)", "onArr", "IntArray")
  }

  @Test
  fun `a nullable Int parameter refuses the pair by name`() {
    assertRefused("bpnint", "fun onNullInt(n: Int?)", "onNullInt", "Int?")
  }

  @Test
  fun `a nullable String parameter refuses the pair by name`() {
    assertRefused("bpnstr", "fun onNullStr(s: String?)", "onNullStr", "String?")
  }

  /** ADR-160's payload set has no `Char` arm (`BridgeType.Char` is not a `Primitive`). */
  @Test
  fun `a Char parameter refuses the pair by name`() {
    assertRefused("bpchar", "fun onChar(c: Char)", "onChar", "Char")
  }

  @Test
  fun `a non-Unit return refuses the pair by name`() {
    assertRefused(
      "bpret",
      "fun count(): Int",
      "count",
      "Int",
      kind = ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_RETURN,
    )
  }

  /**
   * The ADR-113 declaration-entry criterion: `IWatcher` declares only `Watcher`'s own members (no
   * base list), so a slot wired for an inherited `onBase()` called `listener.OnBase()` on an
   * interface with no such member (CS1061). Measured before the gate: the pair emitted.
   */
  @Test
  fun `an inherited listener member refuses the pair by name`() {
    val result = Tier1Harness.run(
      """
      package tier1.bpinherit

      interface Base { fun onBase() }
      interface Watcher : Base { fun onMeow(message: String) }

      class Kennel {
        private val watchers = mutableListOf<Watcher>()
        fun addWatcher(w: Watcher) { watchers.add(w) }
        fun removeWatcher(w: Watcher) { watchers.remove(w) }
      }
      """.trimIndent(),
    )
    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")
    assertFalse(result.generatedCSharp.contains("AddWatcher"), "cs=${result.generatedCSharp}")
    assertTrue(
      result.kspWarnings.any { warning ->
        warning.contains("Kennel.addWatcher") && warning.contains("onBase") &&
            warning.contains("inherited from `Base`")
      },
      "kspWarnings=${result.kspWarnings}",
    )
  }

  /**
   * A sealed arm's pair (`forwardArmInterfaceBridgePairs`) takes the same refusal, and the arm
   * walk in `warnRefusedLegacyRouteMembers` names both halves under the arm's owner.
   */
  @Test
  fun `a sealed arm's pair is refused and named on both halves`() {
    val result = Tier1Harness.run(
      """
      package tier1.bparm

      interface Watcher { fun onBatch(items: List<Int>) }

      sealed class Shelter {
        class Kennel : Shelter() {
          private val watchers = mutableListOf<Watcher>()
          fun addWatcher(w: Watcher) { watchers.add(w) }
          fun removeWatcher(w: Watcher) { watchers.remove(w) }
        }
        class Empty : Shelter()
      }
      """.trimIndent(),
    )
    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")
    assertFalse(
      result.generated.contains("_addWatcher") || result.generated.contains("_removeWatcher"),
      "no Kotlin subscription export on the arm; generated=${result.generated}",
    )
    assertFalse(
      result.generatedCSharp.contains("AddWatcher("),
      "no C# subscription method on the arm; cs=${result.generatedCSharp}",
    )
    listOf("addWatcher", "removeWatcher").forEach { pairMember ->
      assertTrue(
        result.kspWarnings.any { warning ->
          warning.contains(ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_INPUT.name) &&
              warning.contains("Shelter.Kennel.$pairMember") &&
              warning.contains("`addWatcher` / `removeWatcher`") &&
              warning.contains("onBatch") &&
              warning.contains("List<Int>")
        },
        "expected $pairMember to be named; kspWarnings=${result.kspWarnings}",
      )
    }
  }

  /** One refused member refuses the pair even beside members the route carries. */
  @Test
  fun `one refused member among carried ones refuses the whole pair`() {
    assertRefused(
      "bpmixed",
      "fun onMeow(message: String)\n  fun onBatch(items: List<Int>)",
      "onBatch",
      "List<Int>",
    )
  }

  /**
   * Controls: every member shape in the admitted set still binds on both halves, with the wire
   * each kind had before (the delegate names are shared with the stored-callback route).
   */
  @Test
  fun `admitted payloads still bind on both halves`() {
    val result = Tier1Harness.run(
      source(
        "bpok",
        """
        fun onUInt(u: UInt)
          fun onLong(l: Long)
          fun onFlag(b: Boolean)
          fun onMessage(message: String)
          fun onMood(mood: Mood)
          fun onBone(bone: Bone)
          fun onPurr()
        """.trimIndent(),
        extra = """
        enum class Mood { HAPPY, SAD }
        class Bone(val size: Int)
        """.trimIndent(),
      ),
    )
    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")
    val kotlin: String = result.generated
    assertContains(kotlin, "_kennel_addWatcher")
    assertContains(kotlin, "_kennel_removeWatcher")
    assertContains(kotlin, "CFunction<(UInt, COpaquePointer, COpaquePointer?) -> Unit>")
    assertContains(kotlin, "CFunction<(Long, COpaquePointer, COpaquePointer?) -> Unit>")
    assertContains(kotlin, "CFunction<(Byte, COpaquePointer, COpaquePointer?) -> Unit>")
    assertContains(kotlin, "CFunction<(Int, COpaquePointer, COpaquePointer?) -> Unit>")
    assertContains(kotlin, "override fun onMood(mood: tier1.bpok.Mood)")
    assertContains(kotlin, "override fun onBone(bone: tier1.bpok.Bone)")
    val cs: String = result.generatedCSharp
    assertContains(cs, "AddWatcher")
    assertContains(cs, "NugetUIntVoidCallback(uint arg0, IntPtr _)")
    assertContains(cs, "NugetLongVoidCallback(long arg0, IntPtr _)")
    assertContains(cs, "NugetBoolVoidCallback(byte arg0, IntPtr _)")
    assertContains(cs, "NugetIntVoidCallback(int arg0Ord, IntPtr _)")
    assertContains(cs, "NugetObjectVoidCallback(IntPtr arg0Ptr, IntPtr _)")
    assertFalse(
      result.kspWarnings.any { it.contains("Kennel.addWatcher") },
      "an admitted pair is not named; kspWarnings=${result.kspWarnings}",
    )
  }

  /**
   * Folded (ADR-113): an interface whose only collection use is on its own members still gets the
   * `System.Collections.Generic` using, which nothing else in the file asks for. Tier 1 does not
   * compile C# (ADR-060), so this is structural: without the using, `IReadOnlyList<int>` fails
   * CS0246 under a no-implicit-usings consumer.
   */
  @Test
  fun `an interface-only collection member still pulls in the collections using`() {
    val result = Tier1Harness.run(
      """
      package tier1.bpusing

      interface Watcher {
        fun onBatch(items: List<Int>)
      }
      """.trimIndent(),
    )
    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")
    val cs: String = result.generatedCSharp
    assertContains(cs, "IReadOnlyList<int>")
    assertContains(cs, "using System.Collections.Generic;")
  }
}
