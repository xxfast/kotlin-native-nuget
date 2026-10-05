package io.github.xxfast.kotlin.native.nuget.test.issue463

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf

/**
 * Fixture for the **sealed interface over declared arms** hole (issue #463, ADR-204): a `sealed
 * interface` whose arms also extend another class. ADR-112 eligibility refuses it, because an arm
 * that already has a C# base cannot also extend an abstract `ConnectableDevice`, so the interface
 * classifies as a bare protocol with no discriminator and every member typed with it is dropped
 * with `SKIPPED_SEALED_POSITION` (and the `var` with `SKIPPED_UNSUPPORTED_PROPERTY`). The C# types
 * are all already there: `IConnectableDevice` with its `Address` member, and arm classes that
 * already list it. Only the discriminator and the position binding are missing.
 *
 * After ADR-204 the interface stays `public interface IConnectableDevice`, gains an internal
 * `_handle` and a static `FromHandle` over its declared arms, and binds at every position a sealed
 * class binds at. A returned value is the concrete arm, so it is also its sealed class base.
 *
 * Three hierarchies, one per admission row the human accepted (2026-10-05):
 * - [ConnectableDevice] is the **issue shape**: every arm is an arm of the eligible sealed class
 *   [EvidenceDevice], including one `data object` arm ([SavedDevice]). [LostDevice] is an arm of
 *   [EvidenceDevice] that does **not** implement the interface, so a discriminator built from the
 *   sealed class's arms instead of the interface's is visible.
 * - [Chargeable] is the **plain-superclass shape**: [LaserPointer] extends the plain `open class`
 *   [Trinket], [TreatBall] has no superclass at all.
 * - [RemoteDevice] implements **both** interfaces, so it needs two explicit `_handle` lines on one
 *   arm, and it makes [Chargeable] the **mixed** hierarchy (two plain arms beside one sealed-class
 *   arm) while [ConnectableDevice] stays the pure issue shape.
 *
 * Every position the sealed-class route binds, once each, on [ConnectableDevice]:
 * - [connect] is the **parameter**, answered as the [EvidenceDevice] base so Kotlin's own `when`
 *   reads the arm that crossed back,
 * - [preferred] and [deviceAt] are the **return**, [deviceAt] one arm per index so the
 *   discriminator, not the declared type, decides (including the `data object` arm),
 * - [nearbyOrNull] is the **nullable return** and [describeOrNone] the **nullable parameter**,
 * - [connectable] is the **`List` component**, [connectableSet] the **`Set` component**,
 *   [CollarDock.byAddress] the **`Map` value**,
 * - [CollarDock] takes it at a **constructor parameter** and exposes it at a **`val`**
 *   ([CollarDock.first]) and a **`var`** ([CollarDock.current]),
 * - [scanLater] is the **suspend return** (no suspension point, so the completion can beat the
 *   P/Invoke that started it) and [connectLater] the **suspend parameter**,
 * - [NearbyDevice.pairWith] and [NearbyDevice.fallback] are the **sealed-arm member** taking and
 *   returning it.
 * [chargerAt], [charge] and [chargeables] carry [Chargeable] at return, parameter and `List`, the
 * three that prove its own discriminator over plain arms and the dual arm.
 *
 * - [CollarDock.live] and [CollarDock.scan] are the **`StateFlow`/`Flow` item**, each element
 *   materialised through the interface's own `FromHandle`. On the holder class, because a
 *   top-level `Flow` return is not a bound position at all, sealed or not.
 *
 * Deliberately not here: a callback payload, an inherited sealed-route gap ADR-204 does not
 * widen, and every arm kind ADR-204
 * refuses (bare `object`, `enum class`, abstract, nested, generic, arm-extends-arm), which are
 * Tier 1 cells. No default argument on any handle parameter of a suspend or Flow member (ROADMAP).
 *
 * The cats are wired up. Oreo (black with the white middle) wears the collar tag on the desk; Mylo
 * (brown and creamy) left his tracker in the attic, where it is somehow still charging.
 */
sealed class EvidenceDevice

/**
 * The issue's interface: a capability marker across arms of [EvidenceDevice]. [address] is the
 * interface member C# must be able to call through `IConnectableDevice` without a cast.
 */
sealed interface ConnectableDevice {
  /** Where the device answers. Every arm spells it differently, so the arm is readable from it. */
  val address: String
}

/**
 * The payload arm of both [EvidenceDevice] and [ConnectableDevice]: Oreo's collar tag, [name]d
 * and answering at [address]. Carries the two sealed-arm members.
 */
data class NearbyDevice(val name: String, override val address: String) :
  EvidenceDevice(), ConnectableDevice {
  /**
   * Sealed-arm member **taking** the interface: the other device crosses back as a handle and is
   * read on the Kotlin side, so a raw pointer cannot answer.
   */
  fun pairWith(other: ConnectableDevice): String = "$address<->${other.address}"

  /**
   * Sealed-arm member **returning** the interface, as a *different* arm from the receiver, so the
   * discriminator decides: Mylo's tracker up in the attic.
   */
  fun fallback(): ConnectableDevice = RemoteDevice(host = "attic", battery = 80)
}

/**
 * The **dual** arm: an arm of [EvidenceDevice] implementing both [ConnectableDevice] and
 * [Chargeable]. Mylo's tracker, reachable at [host], with [battery] percent left.
 */
data class RemoteDevice(val host: String, override val battery: Int) :
  EvidenceDevice(), ConnectableDevice, Chargeable {
  override val address: String get() = "remote://$host"
}

/** The `data object` arm of [EvidenceDevice] and [ConnectableDevice]: the remembered pairing. */
data object SavedDevice : EvidenceDevice(), ConnectableDevice {
  override val address: String get() = "saved"
}

/**
 * An arm of [EvidenceDevice] that is **not** a [ConnectableDevice]: the tag Oreo lost under the
 * sofa. C# must not see it as an `IConnectableDevice`, and no interface discriminator may name it.
 */
data class LostDevice(val lastSeen: String) : EvidenceDevice()

/**
 * The plain `open class` [LaserPointer] extends. Ordinary and exported, with one member, so it
 * keeps binding as an ordinary class and is not itself an arm of any sealed interface.
 */
open class Trinket {
  /** Present so [Trinket] is a real exported class rather than an empty marker. */
  fun sparkle(): String = "shiny"
}

/**
 * The plain-superclass interface: arms that are top-level ordinary classes, one with a superclass
 * and one without, plus the dual [RemoteDevice]. [battery] is its interface member.
 */
sealed interface Chargeable {
  /** Charge left, in percent. */
  val battery: Int
}

/** The arm with a plain superclass: the red dot Oreo chases, extending [Trinket]. */
class LaserPointer(override val battery: Int) : Trinket(), Chargeable

/** The arm with no superclass at all: Mylo's treat ball, which lights up when it rolls. */
class TreatBall(override val battery: Int) : Chargeable

/**
 * The holder: [ConnectableDevice] at a **constructor parameter**, a **`val`**, a **`var`**, a
 * **`Map` value** and a **`StateFlow`/`Flow` item**.
 */
class CollarDock(initial: ConnectableDevice) {
  private val docked: MutableStateFlow<ConnectableDevice> = MutableStateFlow(initial)

  /** `val` position: the device the dock was built with. */
  val first: ConnectableDevice = initial

  /** `var` position: the device on the dock right now. Starts as [first]; [live] follows it. */
  var current: ConnectableDevice = initial
    set(value) {
      field = value
      docked.value = value
    }

  /** `Map` value position: every known device keyed by its [ConnectableDevice.address]. */
  fun byAddress(): Map<String, ConnectableDevice> = connectable().associateBy { it.address }

  /** `StateFlow` item position: the device on the dock, as it changes. */
  fun live(): StateFlow<ConnectableDevice> = docked

  /** `Flow` item position: every arm once, in [deviceAt] order. */
  fun scan(): Flow<ConnectableDevice> = flowOf(deviceAt(0), deviceAt(1), deviceAt(2))
}

/**
 * Parameter position. Kotlin's exhaustive `when` over the interface smart-casts each arm to its
 * [EvidenceDevice] base, so the value that comes back is the one Kotlin read.
 */
fun connect(device: ConnectableDevice): EvidenceDevice = when (device) {
  is NearbyDevice -> device
  is RemoteDevice -> device
  SavedDevice -> SavedDevice
}

/** Return position: Oreo's collar tag on the desk, the ADR's consumer sample verbatim. */
fun preferred(): ConnectableDevice = NearbyDevice(name = "desk", address = "aa:bb")

/**
 * Return position, one arm per index so the discriminator decides: `0` Oreo's tag, `1` Mylo's
 * tracker, anything else the remembered pairing (the `data object` arm).
 */
fun deviceAt(index: Int): ConnectableDevice = when (index) {
  0 -> NearbyDevice(name = "oreo", address = "aa:bb")
  1 -> RemoteDevice(host = "attic", battery = 80)
  else -> SavedDevice
}

/** Nullable return position: Oreo's tag when [present], nothing when he has wandered off. */
fun nearbyOrNull(present: Boolean): ConnectableDevice? =
  if (present) NearbyDevice(name = "oreo", address = "aa:bb") else null

/** `List` component position, every arm once, in [deviceAt] order. */
fun connectable(): List<ConnectableDevice> = listOf(deviceAt(0), deviceAt(1), deviceAt(2))

/** `Set` component position, every arm once (the arms are distinct values). */
fun connectableSet(): Set<ConnectableDevice> = connectable().toSet()

/**
 * Nullable **parameter** position: `null` crosses as the zero handle, an arm as its own handle, and
 * Kotlin names which it received.
 */
fun describeOrNone(device: ConnectableDevice?): String = when (device) {
  null -> "none"
  is NearbyDevice -> "nearby ${device.address}"
  is RemoteDevice -> "remote ${device.address}"
  SavedDevice -> "saved"
}

/**
 * Suspend **return** position. No suspension point, so the completion can fire before the
 * P/Invoke that started it returns (the leak harness's tight-loop window).
 */
suspend fun scanLater(index: Int): ConnectableDevice = deviceAt(index)

/** Suspend **parameter** position: the handle crosses back and Kotlin reads its address. */
suspend fun connectLater(device: ConnectableDevice): String = "connected ${device.address}"

/**
 * [Chargeable] return position, one arm per index: `0` the laser (plain superclass), `1` the treat
 * ball (no superclass), anything else Mylo's tracker (the dual sealed-class arm).
 */
fun chargerAt(index: Int): Chargeable = when (index) {
  0 -> LaserPointer(battery = 40)
  1 -> TreatBall(battery = 15)
  else -> RemoteDevice(host = "attic", battery = 80)
}

/**
 * [Chargeable] parameter position. Kotlin's `when` names the arm it received, so the answer reads
 * the Kotlin side of the wire.
 */
fun charge(device: Chargeable): String = when (device) {
  is LaserPointer -> "laser ${device.battery}"
  is TreatBall -> "ball ${device.battery}"
  is RemoteDevice -> "remote ${device.battery}"
}

/** [Chargeable] `List` component position, every arm once, in [chargerAt] order. */
fun chargeables(): List<Chargeable> = listOf(chargerAt(0), chargerAt(1), chargerAt(2))
