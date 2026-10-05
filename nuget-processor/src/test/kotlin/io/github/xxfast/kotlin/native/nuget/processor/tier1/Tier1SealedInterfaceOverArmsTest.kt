package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-204 (issue #463): a sealed interface ADR-112 refuses, whose every arm is already declared by
 * its own route as an instantiable handle class listing `I<Name>`, keeps `I<Name>` and gains an
 * internal `_handle`, a `get_type` extern and a `FromHandle` switching to each arm's class. No
 * backing wrapper, no bridge plan: the type never classifies as an interface.
 *
 * One admitted cell per admitted row of the ADR's table (sealed-class arm with a `data object`,
 * plain top-level arm with and without a superclass, an arm implementing two such interfaces) and
 * one refused cell per refused row, each asserting its own named reason.
 */
class Tier1SealedInterfaceOverArmsTest {

  private val admitted: String = """
    package tier1.overarms

    import kotlinx.coroutines.flow.Flow
    import kotlinx.coroutines.flow.MutableStateFlow
    import kotlinx.coroutines.flow.StateFlow
    import kotlinx.coroutines.flow.flowOf

    sealed class Evidence

    sealed interface Connectable {
      val address: String
    }

    data class Nearby(override val address: String) : Evidence(), Connectable

    data class Remote(val host: String, override val battery: Int) :
      Evidence(), Connectable, Chargeable {
      override val address: String get() = host
    }

    data object Saved : Evidence(), Connectable {
      override val address: String get() = "saved"
    }

    data class Lost(val at: String) : Evidence()

    open class Trinket

    sealed interface Chargeable {
      val battery: Int
    }

    class Laser(override val battery: Int) : Trinket(), Chargeable

    class Ball(override val battery: Int) : Chargeable

    class Dock(initial: Connectable) {
      val first: Connectable = initial
      private val state = MutableStateFlow(initial)
      var current: Connectable = initial
        set(value) {
          field = value
          state.value = value
        }
      fun byAddress(): Map<String, Connectable> = mapOf(first.address to first)
      fun live(): StateFlow<Connectable> = state
      fun scan(): Flow<Connectable> = flowOf(first)
    }

    fun connect(device: Connectable): Evidence = when (device) {
      is Nearby -> device
      is Remote -> device
      Saved -> Saved
    }

    fun preferred(): Connectable = Nearby("aa")

    fun nearbyOrNull(present: Boolean): Connectable? = if (present) Nearby("aa") else null

    fun all(): List<Connectable> = listOf(Nearby("aa"), Saved)

    fun allSet(): Set<Connectable> = setOf(Nearby("aa"), Saved)

    fun describeOrNone(device: Connectable?): String = device?.address ?: "none"

    suspend fun scanLater(index: Int): Connectable = Saved

    suspend fun connectLater(device: Connectable): String = device.address

    suspend fun maybeLater(present: Boolean): Connectable? = if (present) Saved else null

    fun charger(): Chargeable = Ball(1)

    fun charge(device: Chargeable): Int = device.battery
  """.trimIndent()

  /** Code lines only: a `///` doc line may legitimately name anything. */
  private fun String.codeLines(): List<String> =
    lines().filterNot { it.trimStart().startsWith("///") }

  @Test
  fun `an admitted sealed interface keeps I-Name and gains a discriminator`() {
    val result = Tier1Harness.run(
      admitted,
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )

    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    val csharp: String = result.generatedCSharp
    listOf(
      "public interface IConnectable : IDisposable",
      "internal NugetKotlinHandle _handle { get; }",
      "EntryPoint = \"library_tier1_overarms__connectable_get_type\")]",
      "internal static IConnectable FromHandle(IntPtr handle)",
      "internal static IConnectable FromHandle(NugetKotlinHandle handle)",
      "0 => new global::Interop.Nearby(handle, out _),",
      "1 => new global::Interop.Remote(handle, out _),",
      "2 => new global::Interop.Saved(handle, out _),",
      "public interface IChargeable : IDisposable",
    ).forEach { line -> assertContains(csharp, line) }
    assertTrue(
      result.generated.contains("export_library_tier1_overarms__connectable_get_type") &&
          result.generated.contains("export_library_tier1_overarms__chargeable_get_type"),
      "expected both discriminator exports; generated=${result.generated}",
    )
    val hasLostBranch: Boolean = csharp.codeLines().any { line ->
      val constructsLost: Boolean = line.contains("new global::Interop.Lost(handle")
      val isSwitchBranch: Boolean = line.contains("=>") && line.trimStart().startsWith("3")
      constructsLost && isSwitchBranch
    }
    assertFalse(hasLostBranch, "expected no discriminator branch for the non-arm `Lost`")
  }

  @Test
  fun `no backing wrapper and exactly one Factories key per interface`() {
    val result = Tier1Harness.run(
      admitted,
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )
    val code: List<String> = result.generatedCSharp.codeLines()

    listOf("Connectable", "Chargeable").forEach { name ->
      assertTrue(
        code.none { Regex("""\bclass $name\b""").containsMatchIn(it) },
        "expected no backing wrapper `class $name`; " +
            "lines=${code.filter { it.contains("class $name") }}",
      )
      assertEquals(
        1,
        code.count { it.contains("[typeof(global::Interop.I$name)]") },
        "expected exactly one Factories key for I$name; lines=" +
            "${code.filter { it.contains("typeof(global::Interop.I$name)") }}",
      )
    }
    assertContains(
      result.generatedCSharp,
      "[typeof(global::Interop.IConnectable)] = static handle => " +
          "global::Interop.IConnectable.FromHandle(handle)",
    )
  }

  @Test
  fun `every arm implements the internal handle explicitly, the dual arm twice`() {
    val result = Tier1Harness.run(
      admitted,
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )
    val csharp: String = result.generatedCSharp

    fun armBlock(name: String): String {
      val start: Int = csharp.indexOf("public sealed class $name ")
      check(start >= 0) { "no arm $name in ${csharp.lines().filter { it.contains(name) }}" }
      val end: Int = csharp.indexOf("\n    public ", start + 1).takeIf { it > 0 } ?: csharp.length
      return csharp.substring(start, end)
    }
    listOf("Nearby", "Saved", "Remote").forEach { arm ->
      assertContains(armBlock(arm), "NugetKotlinHandle IConnectable._handle => _handle;")
    }
    assertContains(armBlock("Remote"), "NugetKotlinHandle IChargeable._handle => _handle;")
    listOf("Laser", "Ball").forEach { arm ->
      val start: Int = csharp.indexOf("public class $arm ").takeIf { it >= 0 }
        ?: csharp.indexOf("public sealed class $arm ")
      check(start >= 0) { "no class $arm" }
      val block: String = csharp.substring(start, csharp.indexOf("\n    }", start))
      assertContains(block, "NugetKotlinHandle IChargeable._handle => _handle;")
    }
    assertFalse(
      armBlock("Lost").contains("._handle =>"),
      "expected the non-arm `Lost` to carry no explicit handle line",
    )
  }

  @Test
  fun `every position binds and nothing in the fixture is skipped`() {
    val result = Tier1Harness.run(
      admitted,
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )
    val csharp: String = result.generatedCSharp

    listOf(
      "public static global::Interop.Evidence Connect(global::Interop.IConnectable device)",
      "public static global::Interop.IConnectable Preferred()",
      "public static global::Interop.IConnectable? NearbyOrNull(bool present)",
      "public static IReadOnlyList<global::Interop.IConnectable> All()",
      "public static IReadOnlySet<global::Interop.IConnectable> AllSet()",
      "public static string DescribeOrNone(global::Interop.IConnectable? device)",
      "Native_DescribeOrNone(device?._handle ?? NugetKotlinHandle.Null, out IntPtr error)",
      "public global::Interop.IConnectable First",
      "public global::Interop.IConnectable Current",
      "public IReadOnlyDictionary<string, global::Interop.IConnectable> ByAddress()",
      "KotlinStateFlow<global::Interop.IConnectable> Live()",
      "KotlinFlow<global::Interop.IConnectable> Scan()",
      "Task<global::Interop.IConnectable> ScanLaterAsync(",
      "Task<string> ConnectLaterAsync(global::Interop.IConnectable device",
      "Task<global::Interop.IConnectable?> MaybeLaterAsync(",
      "public static global::Interop.IChargeable Charger()",
      "public static int Charge(global::Interop.IChargeable device)",
    ).forEach { member -> assertContains(csharp, member) }
    val skipped: List<String> = result.kspWarnings
      .filter { it.contains("SKIPPED_") || it.contains("WARNING_") }
      .filter { it.contains("tier1.overarms") }
    assertTrue(skipped.isEmpty(), "expected no skip in the fixture; got: $skipped")
  }

  @Test
  fun `the shipped Mixed control stays refused with the nested reason`() {
    val result = Tier1Harness.run(
      """
      package tier1.overarms.mixed

      open class Rhythm

      sealed interface Mixed {
        class Odd : Rhythm(), Mixed
      }

      fun mixed(): Mixed = Mixed.Odd()
      """.trimIndent(),
    )
    val diagnostic: String = reasonFor(result, "tier1.overarms.mixed.Mixed")
    assertContains(diagnostic, "subclass `Odd` extends another class")
    assertContains(diagnostic, "subclass `Odd` is nested in `Mixed` with no sealed class base")
    assertTrue(
      result.kspWarnings.any {
        it.contains(ForwardDiagnosticKind.SKIPPED_SEALED_POSITION.name) &&
            it.contains("tier1.overarms.mixed.Mixed")
      },
      "expected the position to keep skipping; kspWarnings=${result.kspWarnings}",
    )
    assertFalse(result.generatedCSharp.contains("_handle { get; }"))
  }

  /** Every refused row of the ADR's table, one hierarchy each, so each reason is named. */
  private val refused: String = """
    package tier1.overarms.refused

    import kotlinx.coroutines.flow.Flow
    import kotlinx.coroutines.flow.flowOf
    import kotlinx.coroutines.flow.MutableStateFlow
    import kotlinx.coroutines.flow.StateFlow

    open class Base

    sealed interface ViaObject
    class ObjA : Base(), ViaObject
    object ObjB : ViaObject

    sealed interface ViaEnum
    class EnumA : Base(), ViaEnum
    enum class EnumB : ViaEnum { X }

    sealed interface ViaSub
    class SubA : Base(), ViaSub
    interface SubB : ViaSub

    sealed interface ViaGeneric
    class GenA : Base(), ViaGeneric
    class GenB<T>(val t: T) : ViaGeneric

    sealed interface ViaAbstract
    class AbsA : Base(), ViaAbstract
    abstract class AbsB : ViaAbstract

    sealed interface ViaIntermediate
    class MidA : Base(), ViaIntermediate
    sealed class MidB : ViaIntermediate

    sealed interface ViaChain
    open class ChainA : Base(), ViaChain
    class ChainB : ChainA(), ViaChain

    sealed class Gen<T>
    sealed interface ViaGenericSealed
    class GsA : Gen<Int>(), ViaGenericSealed
    class GsB : Base(), ViaGenericSealed

    sealed interface ViaGenericIface<T>
    class GiA : Base(), ViaGenericIface<Int>

    internal class Hidden : Base(), ViaInternal
    sealed interface ViaInternal
    class InA : Base(), ViaInternal

    class Holder {
      fun obj(): ViaObject = ObjA()
      fun chain(): ViaChain = ChainB()
      val live: StateFlow<ViaChain> = MutableStateFlow(ChainB())
      fun chains(): Flow<ViaChain> = flowOf(ChainB())
      fun byName(): Map<String, ViaChain> = mapOf("b" to ChainB())
    }
  """.trimIndent()

  @Test
  fun `every refused arm kind is named with its own reason`() {
    val result = Tier1Harness.run(
      refused,
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )

    mapOf(
      "ViaObject" to "subclass `ObjB` is an object, which C# declares as a static class",
      "ViaEnum" to "subclass `EnumB` is an enum class",
      "ViaSub" to "subclass `SubB` is an interface",
      "ViaGeneric" to "subclass `GenB` is generic",
      "ViaAbstract" to "subclass `AbsB` is abstract",
      "ViaIntermediate" to "subclass `MidB` is itself a sealed class",
      "ViaChain" to "subclass `ChainB` extends `ChainA`, another subclass of the same interface",
      "ViaGenericSealed" to "subclass `GsA` is an arm of the generic sealed class `Gen`",
      "ViaGenericIface" to "the interface is generic",
      "ViaInternal" to "subclass `Hidden` is not exported",
    ).forEach { (name, reason) ->
      assertContains(reasonFor(result, "tier1.overarms.refused.$name"), reason)
    }
    assertFalse(
      result.generatedCSharp.codeLines().any { it.contains("_handle { get; }") },
      "expected no refused interface to gain a discriminator",
    )
  }

  /**
   * A refused sealed interface at a `Flow` item skips at the member like every other position,
   * rather than spelling an undeclared `KotlinFlow<...ViaChain>` (CS0234 in every consumer).
   */
  @Test
  fun `a refused sealed interface skips at the Flow item instead of spelling an undeclared name`() {
    val result = Tier1Harness.run(
      refused,
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )

    assertFalse(
      result.generatedCSharp.contains("ViaChain>"),
      "expected no Flow spelled over the refused interface; lines=" +
          "${result.generatedCSharp.lines().filter { it.contains("ViaChain") }}",
    )
    assertTrue(
      listOf("Holder.chains", "Holder.live").all { member ->
        result.kspWarnings.any { it.contains("SKIPPED_") && it.contains(member) }
      },
      "expected the Flow member to skip named; kspWarnings=${result.kspWarnings}",
    )
  }

  /**
   * A `Map<String, Sealed>` value skip names the sealed type. The detail walk used to take the
   * `String` key first and the hint read "sealed type `the sealed type`".
   */
  @Test
  fun `a refused sealed Map value names the sealed type in its hint`() {
    val result = Tier1Harness.run(
      refused,
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )

    val skip: String = requireNotNull(
      result.kspWarnings.firstOrNull {
        it.contains(ForwardDiagnosticKind.SKIPPED_SEALED_POSITION.name) &&
            it.contains("Holder.byName")
      },
    ) { "expected the Map member to skip; kspWarnings=${result.kspWarnings}" }
    assertContains(skip, "sealed type `tier1.overarms.refused.ViaChain`")
    assertFalse(skip.contains("`the sealed type`"), "expected no unfilled placeholder; got: $skip")
  }

  private fun reasonFor(result: Tier1Result, name: String): String = requireNotNull(
    result.kspWarnings.firstOrNull {
      it.contains(ForwardDiagnosticKind.SKIPPED_INELIGIBLE_SEALED_INTERFACE.name) &&
          it.contains("`$name`")
    },
  ) { "expected an ineligibility warning for $name; kspWarnings=${result.kspWarnings}" }
}
