package io.github.xxfast.kotlin.native.nuget.test.cat

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Fixture for [#248](https://github.com/xxfast/kotlin-native-nuget/issues/248), designed in
 * ADR-151 (`docs/adr/151-bytearray-mapping.md`): `kotlin.ByteArray` must surface as `byte[]`
 * (and `ByteArray?` as `byte[]?`) at every ordinary position, over the `Collection` wire (one
 * `StableRef` handle, `MATERIALIZED` ownership, a count plus a `memcpy` instead of a per-element
 * loop, the null pointer for `null`).
 *
 * Today `ByteArray` is a stdlib declaration with `containingFile == null` and no classifier line,
 * so it falls through to `Unsupported(isUnexportedDependency = true)`: the properties drop with
 * `[nuget:SKIPPED_UNSUPPORTED_PROPERTY]` and `<init>`/`copy`/the methods drop with
 * `[nuget:SKIPPED_UNEXPORTED_DEPENDENCY_TYPE]`, which is the issue's second defect (the kind is
 * wrong: nothing third-party is in the signature, so it should read `SKIPPED_UNSUPPORTED_TYPE`).
 *
 * Every position ADR-151 names, once each:
 * - [Payload] constructor - the issue's exact repro, `data class Bar(val code: Int, val data:
 *   ByteArray)`, with the `val` widened to a `var` so the setter row exists too,
 * - [Payload.data] - `var ByteArray` property: getter copies out, setter copies in,
 * - [Payload.checksum] - `val ByteArray?` property, the nullable getter, and the empty guard
 *   (an empty payload has no checksum, so the same fixture carries `null` and `Empty`),
 * - [Payload.slice] - primitive parameters, non-null `ByteArray` return,
 * - [Payload.maybe] - nullable in and nullable out on one callable,
 * - [reverse] - top-level non-null parameter and return, and the copy contract: the caller's
 *   array must be untouched afterwards,
 * - [empty] - top-level non-null return of a zero-length array, the `addressOf(0)` guard's
 *   integration row: it must arrive as an empty `byte[]`, never as `null` and never as a throw,
 * - [maybe] - top-level nullable parameter and return.
 *
 * The collection-component positions ADR-151 deferred are no longer absent: see [burstChunks] and
 * everything below it, plus [CollarLog] and [CollarStream]. Still deliberately absent:
 * `UByteArray`/`IntArray`/`Array<T>`, `ByteArray` as an extension receiver, and the reverse
 * direction.
 *
 * Oreo's collar transmitter sends three bytes, Mylo's sends nothing at all.
 */
data class Payload(val code: Int, var data: ByteArray) {
  /** Nullable `val` property. `null` for a cat whose collar sent no bytes at all. */
  val checksum: ByteArray? get() = if (data.isEmpty()) null else byteArrayOf(data.sum().toByte())

  /** Primitive parameters in, non-null `ByteArray` out. */
  fun slice(from: Int, to: Int): ByteArray = data.copyOfRange(from, to)

  /** Nullable in, nullable out, one callable. */
  fun maybe(input: ByteArray?): ByteArray? = input
}

/**
 * Top-level non-null parameter and return. Also the copy contract: the array C# hands in is
 * copied on the way over, so mutating it after the call cannot reach the Kotlin side.
 */
fun reverse(data: ByteArray): ByteArray = data.reversedArray()

/**
 * Top-level non-null return of a zero-length array. `addressOf(0)` throws on an empty
 * `ByteArray`, so this is the row that fails loudly if the runtime's `isEmpty()` guard is missing.
 */
fun empty(): ByteArray = byteArrayOf()

/** Top-level nullable parameter and return: `null` in, `null` out. */
fun maybe(data: ByteArray?): ByteArray? = data

// ---------------------------------------------------------------------------------------------
// ROADMAP Phase 4: `ByteArray` as a COLLECTION COMPONENT, the position ADR-151 deferred.
//
// A `ByteArray` component crosses as its own `StableRef` handle in the pointer-shaped slot every
// component already uses (the ADR-099 nested-collection arm): C# writes `CreateBytes` per element
// through `Select` and reads `ReadBytes` per element, so `List<ByteArray>` binds as
// `IReadOnlyList<byte[]>`, `MutableList<ByteArray>` as `IList<byte[]>`, `Map<String, ByteArray>`
// as `IReadOnlyDictionary<string, byte[]>` and `MutableMap<String, ByteArray>` as
// `IDictionary<string, byte[]>`. Shipped 2026-09-20 (the ADR-151 amendment). The only members
// below that still drop with `[nuget:SKIPPED_UNSUPPORTED_TYPE]`
// (`ForwardPlanSkipReason.BYTE_ARRAY`) are [uniquePatches] and [byFingerprint], which are DECLINED
// rather than deferred.
//
// Positions, once each: RETURN ([burstChunks], [rewindCollars], [litterGrid], [patchySignals],
// [CollarLog.rewound]), PARAMETER ([spliceBursts], [weighBursts], [countCollarBytes],
// [weighLitterGrid], [missingSignals]), PROPERTY getter and setter ([CollarLog.bursts],
// [CollarLog.chips]), CONSTRUCTOR parameter ([CollarLog]), and the legacy async routes
// ([CollarStream]).
//
// Oreo's collar transmits one burst per zoomie; Mylo's transmits a lot of nothing, at length.
// ---------------------------------------------------------------------------------------------

/**
 * `List<ByteArray>` at a RETURN. Oreo's burst, split into fixed-size chunks, so the result has
 * several elements of different lengths (the last one short) rather than an echo of the input.
 */
fun burstChunks(data: ByteArray, size: Int): List<ByteArray> =
  data.toList().chunked(size).map { it.toByteArray() }

/**
 * `List<ByteArray>` at a PARAMETER. Concatenated, so the C# side observes every element's bytes
 * in order and a dropped or reordered element is visible in the result.
 */
fun spliceBursts(parts: List<ByteArray>): ByteArray =
  parts.fold(byteArrayOf()) { acc, part -> acc + part }

/** `MutableList<ByteArray>` at a PARAMETER: the `IList<byte[]>` spelling of the same wire. */
fun weighBursts(parts: MutableList<ByteArray>): Int = parts.sumOf { it.size }

/**
 * `Map<String, ByteArray>` at a PARAMETER and a RETURN. Each value is reversed, so the result
 * cannot be mistaken for the argument handed straight back.
 */
fun rewindCollars(bursts: Map<String, ByteArray>): Map<String, ByteArray> =
  bursts.mapValues { (_, burst) -> burst.reversedArray() }

/** `MutableMap<String, ByteArray>` at a PARAMETER: the `IDictionary<string, byte[]>` spelling. */
fun countCollarBytes(bursts: MutableMap<String, ByteArray>): Int = bursts.values.sumOf { it.size }

/**
 * Nested, out: `List<List<ByteArray>>`. The recursion is supposed to give this for free once the
 * component gate opens, which is exactly why it gets its own row.
 */
fun litterGrid(): List<List<ByteArray>> = listOf(
  listOf(byteArrayOf(1, 2), byteArrayOf()),
  listOf(byteArrayOf(0x00, 0xFF.toByte(), 0x7F)),
)

/** Nested, in: `List<List<ByteArray>>` at a PARAMETER. */
fun weighLitterGrid(rows: List<List<ByteArray>>): Int = rows.flatten().sumOf { it.size }

/**
 * Nullable component, READ side: `List<ByteArray?>` with a `null` in the middle, an empty array
 * beside it (zero bytes is a value, not an absence) and a high-byte array.
 */
fun patchySignals(): List<ByteArray?> = listOf(
  byteArrayOf(1, 2),
  null,
  byteArrayOf(),
  byteArrayOf(0x00, 0xFF.toByte()),
)

/**
 * Nullable component, WRITE side, and the load-bearing trap of the whole item: a `null` element
 * must ride a null pointer that the fill loop never disposes. Returning the INDICES that arrived
 * null makes this a real round trip - a shim that silently drops nulls, or that shifts the
 * remaining elements up, reports the wrong indices instead of passing by accident.
 */
fun missingSignals(signals: List<ByteArray?>): List<Int> =
  signals.mapIndexedNotNull { index, burst -> if (burst == null) index else null }

/**
 * DECLINED, not deferred: a `Set<ByteArray>`. Arrays compare by identity in both languages and
 * every crossing copies, so no membership test could ever succeed. Must stay a named skip with no
 * C# member at all.
 */
fun uniquePatches(patches: Set<ByteArray>): Int = patches.size

/**
 * DECLINED, not deferred: a `ByteArray` map KEY, for the same identity-versus-copy reason as
 * [uniquePatches]. Must stay a named skip with no C# member at all.
 */
fun byFingerprint(index: Map<ByteArray, String>): Int = index.size

/**
 * The class positions: a `List<ByteArray>` and a `Map<String, ByteArray>` at CONSTRUCTOR
 * parameters, both as `var` properties so the getter and the setter both exist, and a method
 * returning `List<ByteArray>`.
 */
class CollarLog(
  val catName: String,
  var bursts: List<ByteArray>,
  var chips: Map<String, ByteArray>,
) {
  /** Method RETURN of `List<ByteArray>`, reversed per element so it is never an echo. */
  fun rewound(): List<ByteArray> = bursts.map { it.reversedArray() }

  /** Method PARAMETER of `List<ByteArray>` on a class owner, beside the top-level rows. */
  fun append(more: List<ByteArray>): Int {
    bursts = bursts + more
    return bursts.sumOf { it.size }
  }
}

/**
 * The legacy async routes, which share the component gates with the ordinary route.
 *
 * - [bursts] and [ticks] are this item: a `ByteArray` COMPONENT at a `suspend` return and a
 *   `Flow` element.
 * - [snapshot] and [pulses] are a BARE `ByteArray` at those same two async positions, which the
 *   research memo did not check. Both were defects on `main` (a silent CS0246 and a hard processor
 *   crash); both bind now. The C# side still probes them by reflection, which is what let it
 *   report *which* of the two states each one was in.
 *
 * Both suspend members await [delay], so their route has a real suspension point.
 */
class CollarStream {
  /** `suspend fun (): List<ByteArray>`: the legacy suspend route with a bytes component. */
  suspend fun bursts(): List<ByteArray> {
    delay(1)
    return listOf(byteArrayOf(1, 2), byteArrayOf(), byteArrayOf(0x00, 0xFF.toByte()))
  }

  /** `Flow<List<ByteArray>>`: the flow route with a bytes component, over two emissions. */
  val ticks: Flow<List<ByteArray>> = flowOf(
    listOf(byteArrayOf(1)),
    listOf(byteArrayOf(2, 3), byteArrayOf()),
  )

  /**
   * BARE `ByteArray` at a `suspend` return: `Task<byte[]>`.
   *
   * Measured 2026-09-20, BEFORE the fix: this one was not skipped and not crashed, it was a LIE.
   * The route rendered `public Task<ByteArray> SnapshotAsync(...)` into `Interop.cs`, against a
   * `ByteArray` C# type that nothing declares (CS0246), exactly the ADR-123 shape of issue #127.
   * It binds now: the Kotlin half already boxed the result with `NugetHandles.retain`, so the
   * awaited handle IS the one `NugetMarshal.ReadBytes` consumes.
   */
  suspend fun snapshot(): ByteArray {
    delay(1)
    return byteArrayOf(0x00, 0xFF.toByte())
  }

  /**
   * BARE `ByteArray` as a `Flow` element: `KotlinFlow<byte[]>`.
   *
   * Measured 2026-09-20, BEFORE the fix: this one member **crashed** `packNuget` outright, it was
   * not a skip: `e: [ksp] java.lang.IllegalStateException: Kotlin builtin kotlin.ByteArray reached
   * the user-type C# speller; it would render global::TestLibrary.Kotlin..., a namespace nothing
   * declares. Gate the call site on the type's own route first (ADR-123).` It binds now, through
   * ADR-123's own per-member `read:` seam.
   */
  val pulses: Flow<ByteArray> = flowOf(byteArrayOf(1), byteArrayOf(0x7F, 0xFF.toByte()))

  /**
   * The same element, NULLABLE, on a plain `Flow`: `KotlinFlow<byte[]?>`.
   *
   * The crossing of two changes written apart. Before the ADR-065 widening, a plain `Flow<T?>`
   * dropped its `?` on the C# half, so this member would have been declared `KotlinFlow<byte[]>`
   * with an unguarded `NugetMarshal.ReadBytes(h)` -- and the null emission in the middle would have
   * dereferenced `IntPtr.Zero` rather than arriving as null. Mylo's collar goes quiet mid-stream.
   */
  val patchyPulses: Flow<ByteArray?> = flowOf(byteArrayOf(2), null, byteArrayOf(3, 4))
}
