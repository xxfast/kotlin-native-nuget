@file:OptIn(ExperimentalForeignApi::class)

package io.github.xxfast.kotlin.native.nuget.runtime

import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.LongVar
import kotlinx.cinterop.NativePlacement
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set

// ADR-155: the reverse collection crossing, owned once by the runtime (the ADR-128 rule: the
// runtime owns the shape, the generated stub owns the call). A C# collection crosses as ONE
// pointer to a flat buffer of 8-byte slots: `[count][slot 1]...[slot n]` for a list or a set,
// `[count][k1][v1]...[kn][vn]` for a map, where each slot holds the element in the scalar wire
// form the reverse bridge already uses for that type. `null` (IntPtr.Zero) is a null collection.
//
// Only the BUFFER shape lives here. What a slot MEANS is per-element and per-declaration, so the
// generated stub owns it: a string slot is a CoTaskMem pointer it reads and frees, a handle slot
// is a GCHandle it wraps, an enum slot is an ordinal it bounds-checks. That split is what keeps
// this file fixed while the element vocabulary grows.
//
// Neither function registers anything: they are plain Kotlin, not thunk slots, so
// NUGET_RUNTIME_CONTRACT_HASH does not move and an existing consumer's startup check is unchanged.

/**
 * ADR-155: reads the slot buffer a C# thunk returned, and frees it with [release].
 *
 * Returns `null` for a null collection ([buffer] is `IntPtr.Zero`), which is distinct from an
 * empty collection (a buffer whose count is 0). The caller decodes each slot itself; a slot that
 * owns memory of its own (a string pointer, a `GCHandle`) is still the caller's to release.
 *
 * [release] is passed in rather than referenced directly because freeing C#-allocated memory is
 * per-target (`free` on Unix, `CoTaskMemFree` on Windows) and the generated stub already owns
 * that `expect`/`actual` pair: the runtime must not grow a second copy of it.
 */
@NugetRuntimeApi
public fun readSlotsForKotlin(
  buffer: COpaquePointer?,
  release: (buffer: COpaquePointer) -> Unit,
): LongArray? {
  if (buffer == null) return null
  val slots: CPointer<LongVar> = buffer.reinterpret()
  val count: Long = slots[0]
  require(count >= 0 && count <= Int.MAX_VALUE.toLong()) {
    "[nuget] a C# collection buffer declared $count slots, which is not a readable count. The " +
        "C# shim and this runtime disagree about the buffer layout."
  }
  try {
    return LongArray(count.toInt()) { slots[it + 1] }
  } finally {
    release(buffer)
  }
}

/**
 * ADR-155: writes [slots] as a collection buffer in this placement, for a collection-typed
 * parameter.
 *
 * Nothing is freed here and nothing needs to be: the buffer lives in the `memScoped` block the
 * generated stub already opens for its `.cstr.ptr` arguments, exactly like a string argument, and
 * the thunk copies the slots into a managed container before the C# member runs.
 */
@NugetRuntimeApi
public fun NativePlacement.writeSlotsForKotlin(slots: List<Long>): COpaquePointer {
  val buffer: CPointer<LongVar> = allocArray(slots.size + 1)
  buffer[0] = slots.size.toLong()
  slots.forEachIndexed { index, slot -> buffer[index + 1] = slot }
  return buffer
}
