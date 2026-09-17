package io.github.xxfast.kotlin.native.nuget.test.cat

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
 * Deliberately absent, because ADR-151 defers them: `List<ByteArray>` and any other collection
 * component, `UByteArray`/`IntArray`/`Array<T>`, `ByteArray` as an extension receiver, and the
 * reverse direction.
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
