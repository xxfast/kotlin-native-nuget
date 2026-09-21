@file:OptIn(
  ExperimentalForeignApi::class,
  ExperimentalNativeApi::class,
  ExperimentalCoroutinesApi::class,
  NugetRuntimeApi::class,
)

package io.github.xxfast.kotlin.native.nuget.runtime

import kotlin.concurrent.AtomicLong
import kotlin.coroutines.SuspendFunction0
import kotlin.coroutines.SuspendFunction1
import kotlin.coroutines.SuspendFunction2
import kotlin.coroutines.SuspendFunction3
import kotlin.coroutines.cancellation.CancellationException
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.CName
import kotlin.native.runtime.GC
import kotlin.native.runtime.NativeRuntimeApi
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Instant
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.convert
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import platform.posix.memcpy

// ADR-127: this file is the fixed block that `NugetProcessor` used to regenerate into every
// consumer module, moved here verbatim with `internal` dropped to `public`. Every `@CName` name
// and every body is unchanged, which is why `Interop.cs` needed no change at all.

@NugetRuntimeApi
public object NugetHandles {
  public val live: AtomicLong = AtomicLong(0L)

  public fun retain(`value`: Any): COpaquePointer {
    live.incrementAndGet()
    return StableRef.create(value).asCPointer()
  }

  public fun release(handle: COpaquePointer) {
    handle.asStableRef<Any>().dispose()
    live.decrementAndGet()
  }
}

@NugetRuntimeApi
@CName("nuget_live_handles")
public fun export_nuget_live_handles(): Long = NugetHandles.live.value

@NugetRuntimeApi
@CName("nuget_unwrap_string")
public fun export_nuget_unwrap_string(handle: COpaquePointer): String =
  handle.asStableRef<Any>().get() as String

@NugetRuntimeApi
@CName("nuget_unwrap_byte")
public fun export_nuget_unwrap_byte(handle: COpaquePointer): Byte =
  handle.asStableRef<Any>().get() as Byte

@NugetRuntimeApi
@CName("nuget_unwrap_ubyte")
public fun export_nuget_unwrap_ubyte(handle: COpaquePointer): UByte =
  handle.asStableRef<Any>().get() as UByte

@NugetRuntimeApi
@CName("nuget_unwrap_short")
public fun export_nuget_unwrap_short(handle: COpaquePointer): Short =
  handle.asStableRef<Any>().get() as Short

@NugetRuntimeApi
@CName("nuget_unwrap_ushort")
public fun export_nuget_unwrap_ushort(handle: COpaquePointer): UShort =
  handle.asStableRef<Any>().get() as UShort

@NugetRuntimeApi
@CName("nuget_unwrap_int")
public fun export_nuget_unwrap_int(handle: COpaquePointer): Int =
  handle.asStableRef<Any>().get() as Int

@NugetRuntimeApi
@CName("nuget_unwrap_uint")
public fun export_nuget_unwrap_uint(handle: COpaquePointer): UInt =
  handle.asStableRef<Any>().get() as UInt

@NugetRuntimeApi
@CName("nuget_unwrap_long")
public fun export_nuget_unwrap_long(handle: COpaquePointer): Long =
  handle.asStableRef<Any>().get() as Long

@NugetRuntimeApi
@CName("nuget_unwrap_ulong")
public fun export_nuget_unwrap_ulong(handle: COpaquePointer): ULong =
  handle.asStableRef<Any>().get() as ULong

@NugetRuntimeApi
@CName("nuget_unwrap_float")
public fun export_nuget_unwrap_float(handle: COpaquePointer): Float =
  handle.asStableRef<Any>().get() as Float

@NugetRuntimeApi
@CName("nuget_unwrap_double")
public fun export_nuget_unwrap_double(handle: COpaquePointer): Double =
  handle.asStableRef<Any>().get() as Double

@NugetRuntimeApi
@CName("nuget_unwrap_bool")
public fun export_nuget_unwrap_bool(handle: COpaquePointer): Boolean =
  handle.asStableRef<Any>().get() as Boolean

@NugetRuntimeApi
@CName("nuget_unwrap_char")
public fun export_nuget_unwrap_char(handle: COpaquePointer): Char =
  handle.asStableRef<Any>().get() as Char

@NugetRuntimeApi
@CName("nuget_dispose")
public fun export_nuget_dispose(handle: COpaquePointer) {
  NugetHandles.release(handle)
}

@NugetRuntimeApi
@CName("nuget_list_count")
public fun export_nuget_list_count(handle: COpaquePointer): Int =
  handle.asStableRef<List<*>>().get().size

@NugetRuntimeApi
@CName("nuget_list_get")
public fun export_nuget_list_get(handle: COpaquePointer, index: Int): COpaquePointer? =
  handle.asStableRef<List<*>>().get()[index]?.let { NugetHandles.retain(it) }

@NugetRuntimeApi
@CName("nuget_list_create")
public fun export_nuget_list_create(): COpaquePointer = NugetHandles.retain(mutableListOf<Any?>())

@NugetRuntimeApi
@CName("nuget_list_add")
public fun export_nuget_list_add(handle: COpaquePointer, element: COpaquePointer?) {
  handle.asStableRef<MutableList<Any?>>().get().add(element?.asStableRef<Any>()?.get())
}

/**
 * ADR-151: mint a Kotlin `ByteArray` from the caller's buffer. The C# side passes a blittable
 * `byte[]` the marshaller pinned for the duration of the call, so the copy has to happen here:
 * the pin ends when this returns. `count == 0` (and a null `src`) is a real, empty value, never
 * a null.
 */
@NugetRuntimeApi
@CName("nuget_bytes_create")
public fun export_nuget_bytes_create(src: CPointer<ByteVar>?, count: Int): COpaquePointer =
  NugetHandles.retain(if (src == null || count == 0) ByteArray(0) else src.readBytes(count))

@NugetRuntimeApi
@CName("nuget_bytes_count")
public fun export_nuget_bytes_count(handle: COpaquePointer): Int =
  handle.asStableRef<ByteArray>().get().size

/**
 * ADR-151: copy the array behind [handle] into the caller's buffer, which C# sized from
 * `nuget_bytes_count`. The `isEmpty()` guard is load-bearing, not tidiness: `addressOf(0)` on an
 * empty `ByteArray` throws `ArrayIndexOutOfBoundsException` (verified with konanc 2.4.10), so
 * without it an empty `byte[]` crossing out of Kotlin fails at the read.
 */
@NugetRuntimeApi
@CName("nuget_bytes_copy")
public fun export_nuget_bytes_copy(handle: COpaquePointer, dest: CPointer<ByteVar>?) {
  val bytes: ByteArray = handle.asStableRef<ByteArray>().get()
  if (bytes.isEmpty() || dest == null) return
  bytes.usePinned { pinned -> memcpy(dest, pinned.addressOf(0), bytes.size.convert()) }
}

@NugetRuntimeApi
@CName("nuget_set_count")
public fun export_nuget_set_count(handle: COpaquePointer): Int =
  handle.asStableRef<Set<*>>().get().size

@NugetRuntimeApi
@CName("nuget_set_element_at")
public fun export_nuget_set_element_at(handle: COpaquePointer, index: Int): COpaquePointer? =
  handle.asStableRef<Set<*>>().get().toList()[index]?.let { NugetHandles.retain(it) }

@NugetRuntimeApi
@CName("nuget_set_create")
public fun export_nuget_set_create(): COpaquePointer = NugetHandles.retain(mutableSetOf<Any?>())

@NugetRuntimeApi
@CName("nuget_set_add")
public fun export_nuget_set_add(handle: COpaquePointer, element: COpaquePointer?) {
  handle.asStableRef<MutableSet<Any?>>().get().add(element?.asStableRef<Any>()?.get())
}

@NugetRuntimeApi
@CName("nuget_map_count")
public fun export_nuget_map_count(handle: COpaquePointer): Int =
  handle.asStableRef<Map<*, *>>().get().size

@NugetRuntimeApi
@CName("nuget_map_key_at")
public fun export_nuget_map_key_at(handle: COpaquePointer, index: Int): COpaquePointer? =
  handle.asStableRef<Map<*, *>>().get().keys.toList()[index]?.let { NugetHandles.retain(it) }

@NugetRuntimeApi
@CName("nuget_map_value_at")
public fun export_nuget_map_value_at(handle: COpaquePointer, index: Int): COpaquePointer? =
  handle.asStableRef<Map<*, *>>().get().values.toList()[index]?.let { NugetHandles.retain(it) }

@NugetRuntimeApi
@CName("nuget_map_create")
public fun export_nuget_map_create(): COpaquePointer =
  NugetHandles.retain(mutableMapOf<Any?, Any?>())

@NugetRuntimeApi
@CName("nuget_map_put")
public fun export_nuget_map_put(
  handle: COpaquePointer,
  key: COpaquePointer?,
  `value`: COpaquePointer?,
) {
  handle.asStableRef<MutableMap<Any?, Any?>>().get()[key?.asStableRef<Any>()?.get()] =
    value?.asStableRef<Any>()?.get()
}

@NugetRuntimeApi
@CName("nuget_wrap_string")
public fun export_nuget_wrap_string(`value`: String): COpaquePointer =
  NugetHandles.retain(value as Any)

@NugetRuntimeApi
@CName("nuget_wrap_byte")
public fun export_nuget_wrap_byte(`value`: Byte): COpaquePointer = NugetHandles.retain(value as Any)

@NugetRuntimeApi
@CName("nuget_wrap_ubyte")
public fun export_nuget_wrap_ubyte(`value`: UByte): COpaquePointer =
  NugetHandles.retain(value as Any)

@NugetRuntimeApi
@CName("nuget_wrap_short")
public fun export_nuget_wrap_short(`value`: Short): COpaquePointer =
  NugetHandles.retain(value as Any)

@NugetRuntimeApi
@CName("nuget_wrap_ushort")
public fun export_nuget_wrap_ushort(`value`: UShort): COpaquePointer =
  NugetHandles.retain(value as Any)

@NugetRuntimeApi
@CName("nuget_wrap_int")
public fun export_nuget_wrap_int(`value`: Int): COpaquePointer = NugetHandles.retain(value as Any)

@NugetRuntimeApi
@CName("nuget_wrap_uint")
public fun export_nuget_wrap_uint(`value`: UInt): COpaquePointer = NugetHandles.retain(value as Any)

@NugetRuntimeApi
@CName("nuget_wrap_long")
public fun export_nuget_wrap_long(`value`: Long): COpaquePointer = NugetHandles.retain(value as Any)

@NugetRuntimeApi
@CName("nuget_wrap_ulong")
public fun export_nuget_wrap_ulong(`value`: ULong): COpaquePointer =
  NugetHandles.retain(value as Any)

@NugetRuntimeApi
@CName("nuget_wrap_float")
public fun export_nuget_wrap_float(`value`: Float): COpaquePointer =
  NugetHandles.retain(value as Any)

@NugetRuntimeApi
@CName("nuget_wrap_double")
public fun export_nuget_wrap_double(`value`: Double): COpaquePointer =
  NugetHandles.retain(value as Any)

@NugetRuntimeApi
@CName("nuget_wrap_bool")
public fun export_nuget_wrap_bool(`value`: Boolean): COpaquePointer =
  NugetHandles.retain(value as Any)

@NugetRuntimeApi
@CName("nuget_wrap_char")
public fun export_nuget_wrap_char(`value`: Char): COpaquePointer = NugetHandles.retain(value as Any)

@NugetRuntimeApi
@CName("nuget_func0_invoke")
public fun export_nuget_func0_invoke(handle: COpaquePointer): COpaquePointer? {
  val fn = handle.asStableRef<Function0<*>>().get()
  // Boundary nullability part A1: a lambda that legitimately returns null ships the null pointer,
  // the ADR-083 in-band null every other pointer-shaped wire already uses. The shipped
  // `retain(result as Any)` threw an uncaught `NullPointerException` inside an export with no error
  // slot, which terminates the host process (exit code 3, measured at a scratch mirror). The C
  // signature is unchanged: Kotlin/Native emits `void*` for both spellings.
  return fn.invoke()?.let(NugetHandles::retain)
}

@NugetRuntimeApi
@CName("nuget_func1_invoke")
public fun export_nuget_func1_invoke(
  handle: COpaquePointer,
  // Boundary nullability part A1: `IntPtr.Zero` IS the argument's null, so the slot is nullable
  // and the deref is safe-called. The shipped non-null spelling did
  // `arg0.asStableRef<Any>().get()` on a zero pointer, an uncaught NPE that killed the host
  // process.
  arg0: COpaquePointer?,
): COpaquePointer? {
  val fn = handle.asStableRef<Function1<Any?, Any?>>().get()
  val param0 = arg0?.asStableRef<Any>()?.get()
  return fn.invoke(param0)?.let(NugetHandles::retain)
}

@NugetRuntimeApi
@CName("nuget_func2_invoke")
public fun export_nuget_func2_invoke(
  handle: COpaquePointer,
  arg0: COpaquePointer?,
  arg1: COpaquePointer?,
): COpaquePointer? {
  val fn = handle.asStableRef<Function2<Any?, Any?, Any?>>().get()
  val param0 = arg0?.asStableRef<Any>()?.get()
  val param1 = arg1?.asStableRef<Any>()?.get()
  return fn.invoke(param0, param1)?.let(NugetHandles::retain)
}

@NugetRuntimeApi
@CName("nuget_func3_invoke")
public fun export_nuget_func3_invoke(
  handle: COpaquePointer,
  arg0: COpaquePointer?,
  arg1: COpaquePointer?,
  arg2: COpaquePointer?,
): COpaquePointer? {
  val fn = handle.asStableRef<Function3<Any?, Any?, Any?, Any?>>().get()
  val param0 = arg0?.asStableRef<Any>()?.get()
  val param1 = arg1?.asStableRef<Any>()?.get()
  val param2 = arg2?.asStableRef<Any>()?.get()
  return fn.invoke(param0, param1, param2)?.let(NugetHandles::retain)
}

@NugetRuntimeApi
@CName("nuget_suspend_func0_invoke")
public fun export_nuget_suspend_func0_invoke(
  handle: COpaquePointer,
  callbackPtr: COpaquePointer,
  userData: COpaquePointer,
): COpaquePointer {
  val fn = handle.asStableRef<SuspendFunction0<*>>().get()
  // ADR-128: the launch shape lives in `launchForCSharp`; this site owns only the call and
  // how its value becomes a handle. The `== Unit` test and the mint order are unchanged.
  return launchForCSharp(CoroutineScope(Dispatchers.Default), callbackPtr, userData) {
    val result = fn.invoke()
    // Boundary nullability part A1: a null result rides the same null pointer `Unit` already does,
    // instead of `retain(null as Any)`, which was an uncaught NPE that killed the host.
    if (result == Unit) null else result?.let(NugetHandles::retain)
  }
}

@NugetRuntimeApi
@CName("nuget_suspend_func1_invoke")
public fun export_nuget_suspend_func1_invoke(
  handle: COpaquePointer,
  arg0: COpaquePointer?,
  callbackPtr: COpaquePointer,
  userData: COpaquePointer,
): COpaquePointer {
  val fn = handle.asStableRef<SuspendFunction1<Any?, Any?>>().get()
  // LOAD-BEARING: this `get()` runs SYNCHRONOUSLY, before `launchForCSharp` below, and that is the
  // only reason the C# caller may dispose the argument box as soon as the native call returns.
  // Moving it inside the launch block is a use-after-free.
  val param0 = arg0?.asStableRef<Any>()?.get()
  // ADR-128: the launch shape lives in `launchForCSharp`; this site owns only the call and
  // how its value becomes a handle. The `== Unit` test and the mint order are unchanged.
  return launchForCSharp(CoroutineScope(Dispatchers.Default), callbackPtr, userData) {
    val result = fn.invoke(param0)
    // Boundary nullability part A1: a null result rides the same null pointer `Unit` already does,
    // instead of `retain(null as Any)`, which was an uncaught NPE that killed the host.
    if (result == Unit) null else result?.let(NugetHandles::retain)
  }
}

@NugetRuntimeApi
@CName("nuget_suspend_func2_invoke")
public fun export_nuget_suspend_func2_invoke(
  handle: COpaquePointer,
  arg0: COpaquePointer?,
  arg1: COpaquePointer?,
  callbackPtr: COpaquePointer,
  userData: COpaquePointer,
): COpaquePointer {
  val fn = handle.asStableRef<SuspendFunction2<Any?, Any?, Any?>>().get()
  // LOAD-BEARING, see `nuget_suspend_func1_invoke`: read synchronously, before the launch.
  val param0 = arg0?.asStableRef<Any>()?.get()
  val param1 = arg1?.asStableRef<Any>()?.get()
  // ADR-128: the launch shape lives in `launchForCSharp`; this site owns only the call and
  // how its value becomes a handle. The `== Unit` test and the mint order are unchanged.
  return launchForCSharp(CoroutineScope(Dispatchers.Default), callbackPtr, userData) {
    val result = fn.invoke(param0, param1)
    // Boundary nullability part A1: a null result rides the same null pointer `Unit` already does,
    // instead of `retain(null as Any)`, which was an uncaught NPE that killed the host.
    if (result == Unit) null else result?.let(NugetHandles::retain)
  }
}

@NugetRuntimeApi
@CName("nuget_suspend_func3_invoke")
public fun export_nuget_suspend_func3_invoke(
  handle: COpaquePointer,
  arg0: COpaquePointer?,
  arg1: COpaquePointer?,
  arg2: COpaquePointer?,
  callbackPtr: COpaquePointer,
  userData: COpaquePointer,
): COpaquePointer {
  val fn = handle.asStableRef<SuspendFunction3<Any?, Any?, Any?, Any?>>().get()
  // LOAD-BEARING, see `nuget_suspend_func1_invoke`: read synchronously, before the launch.
  val param0 = arg0?.asStableRef<Any>()?.get()
  val param1 = arg1?.asStableRef<Any>()?.get()
  val param2 = arg2?.asStableRef<Any>()?.get()
  // ADR-128: the launch shape lives in `launchForCSharp`; this site owns only the call and
  // how its value becomes a handle. The `== Unit` test and the mint order are unchanged.
  return launchForCSharp(CoroutineScope(Dispatchers.Default), callbackPtr, userData) {
    val result = fn.invoke(param0, param1, param2)
    // Boundary nullability part A1: a null result rides the same null pointer `Unit` already does,
    // instead of `retain(null as Any)`, which was an uncaught NPE that killed the host.
    if (result == Unit) null else result?.let(NugetHandles::retain)
  }
}

@NugetRuntimeApi
@CName("nuget_scope_create")
public fun export_nuget_scope_create(): COpaquePointer =
  NugetHandles.retain(CoroutineScope(SupervisorJob() + Dispatchers.Default))

@NugetRuntimeApi
@CName("nuget_scope_cancel")
public fun export_nuget_scope_cancel(handle: COpaquePointer?) {
  if (handle == null) {
    return
  }
  handle.asStableRef<CoroutineScope>().get().cancel()
}

@NugetRuntimeApi
@CName("nuget_scope_dispose")
public fun export_nuget_scope_dispose(handle: COpaquePointer?) {
  if (handle == null) {
    return
  }
  NugetHandles.release(handle)
}

@NugetRuntimeApi
@CName("nuget_scope_drain")
public fun export_nuget_scope_drain(
  scopeHandle: COpaquePointer,
  callbackPtr: COpaquePointer,
  userData: COpaquePointer,
): COpaquePointer {
  val scope = scopeHandle.asStableRef<CoroutineScope>().get()
  val callback = callbackPtr.reinterpret<CFunction<
        (COpaquePointer?, COpaquePointer?, Byte, COpaquePointer) -> Unit>>()
  val drainJob = scope.launch(start = CoroutineStart.ATOMIC) {
    val self = coroutineContext[Job]
    scope.coroutineContext[Job]
      ?.children
      ?.filter { it != self }
      ?.forEach { it.join() }
    callback.invoke(null, null, 0.toByte(), userData)
  }
  return NugetHandles.retain(drainJob)
}

@NugetRuntimeApi
@CName("nuget_job_cancel")
public fun export_nuget_job_cancel(handle: COpaquePointer?) {
  if (handle == null) {
    return
  }
  handle.asStableRef<Job>().get().cancel()
}

@NugetRuntimeApi
@CName("nuget_job_dispose")
public fun export_nuget_job_dispose(handle: COpaquePointer?) {
  if (handle == null) {
    return
  }
  NugetHandles.release(handle)
}

@NugetRuntimeApi
public data class NugetError(
  public val type: String,
  public val message: String,
  public val stackTrace: String,
  public val cause: NugetError? = null,
)

@NugetRuntimeApi
public fun buildError(e: Throwable): NugetError {
  val seen = mutableSetOf<Throwable>()
  fun build(t: Throwable): NugetError? {
    if (!seen.add(t)) return null
    return NugetError(
      type = t::class.qualifiedName ?: t::class.simpleName ?: "UnknownException",
      message = t.message ?: "Kotlin error",
      stackTrace = t.stackTraceToString(),
      cause = t.cause?.let(::build),
    )
  }
  return build(e)!!
}

private tailrec fun NugetError.at(index: Int): NugetError =
  if (index == 0) this else cause!!.at(index - 1)

/**
 * ADR-129: the 67th export. Returns the version of this `nuget-runtime` klib, so a process can say
 * which runtime its `.dylib`/`.dll` actually carries instead of that being an `nm`-plus-cache-walk
 * question. Wire shape is the one every string-returning export above already uses: Kotlin/Native
 * hands out a `const char*` valid for the immediate call and C# copies it with
 * `Marshal.PtrToStringUTF8` (ADR-003).
 */
@NugetRuntimeApi
@CName("nuget_runtime_version")
public fun export_nuget_runtime_version(): String = NUGET_RUNTIME_VERSION

/**
 * ADR-161: the exception a Kotlin caller sees when the C# callback it invoked threw.
 *
 * Public, and thrown by [nugetCallbackCall], so a library author can `catch` it at the Kotlin call
 * site of the lambda. The message carries the managed type first so a `catch (e: Exception)` that
 * only prints `e.message` still names what actually failed on the other side.
 */
public class NugetManagedException(
  public val managedType: String,
  public val managedMessage: String,
) : RuntimeException("$managedType: $managedMessage")

/**
 * ADR-161: the Kotlin-owned holder a C# thunk pushes through [export_nuget_managed_error_create]
 * and [nugetCallbackCall] reads back. Deliberately a raw [StableRef], not `NugetHandles.retain`:
 * the holder lives for the length of one callback return and never reaches a consumer, so counting
 * it would move every `nuget_live_handles` baseline for a thing no test can observe.
 */
internal class NugetManagedError(
  val type: String,
  val message: String,
  val kind: Int,
)

/**
 * ADR-161: the 71st export, and the only one part B adds. A generated `[UnmanagedCallersOnly]`
 * thunk calls this from inside its `catch` and stores the returned pointer in the trailing
 * `IntPtr* errOut` slot the Kotlin side supplied.
 *
 * [kind] is `1` for a managed cancellation (`OperationCanceledException`), `0` otherwise, mirroring
 * ADR-153's reverse-direction mapping so a cancelled C# callback cancels the Kotlin coroutine that
 * invoked it rather than failing it.
 */
@NugetRuntimeApi
@CName("nuget_managed_error_create")
public fun export_nuget_managed_error_create(
  type: String,
  message: String,
  kind: Int,
): COpaquePointer = StableRef.create(NugetManagedError(type, message, kind)).asCPointer()

/**
 * ADR-161: the Kotlin half of the forward callback error channel. Every generated invocation of a
 * user-code callback goes through here.
 *
 * The slot is allocated and zeroed by the caller (this function), which is ADR-104's structural
 * rule: the error is checked **before** the return value is touched, so a `!!` or an `asStableRef`
 * on the callback's result can never fire ahead of the managed exception that explains why the
 * result is absent. The holder is disposed here, by the side that owns it.
 */
@NugetRuntimeApi
public fun <R> nugetCallbackCall(block: (COpaquePointer) -> R): R = memScoped {
  val slot: COpaquePointerVar = alloc()
  slot.value = null
  val result: R = block(slot.ptr)
  val raw: COpaquePointer? = slot.value
  if (raw == null) return@memScoped result
  val ref: StableRef<NugetManagedError> = raw.asStableRef()
  val error: NugetManagedError = ref.get()
  ref.dispose()
  if (error.kind == 1) {
    throw CancellationException(
      "${error.type}: ${error.message}",
      NugetManagedException(error.type, error.message),
    )
  }
  throw NugetManagedException(error.type, error.message)
}

@NugetRuntimeApi
@CName("nuget_error_type")
public fun export_nuget_error_type(handle: COpaquePointer): String =
  handle.asStableRef<NugetError>().get().type

@NugetRuntimeApi
@CName("nuget_error_message")
public fun export_nuget_error_message(handle: COpaquePointer): String =
  handle.asStableRef<NugetError>().get().message

@NugetRuntimeApi
@CName("nuget_error_stacktrace")
public fun export_nuget_error_stacktrace(handle: COpaquePointer): String =
  handle.asStableRef<NugetError>().get().stackTrace

@NugetRuntimeApi
@CName("nuget_error_cause_count")
public fun export_nuget_error_cause_count(handle: COpaquePointer): Int {
  var e: NugetError? = handle.asStableRef<NugetError>().get()
  var n = 0
  while (e != null) {
    n++; e = e.cause
  }
  return n
}

@NugetRuntimeApi
@CName("nuget_error_cause_type")
public fun export_nuget_error_cause_type(handle: COpaquePointer, index: Int): String =
  handle.asStableRef<NugetError>().get().at(index).type

@NugetRuntimeApi
@CName("nuget_error_cause_message")
public fun export_nuget_error_cause_message(handle: COpaquePointer, index: Int): String =
  handle.asStableRef<NugetError>().get().at(index).message

@NugetRuntimeApi
@CName("nuget_error_cause_stacktrace")
public fun export_nuget_error_cause_stacktrace(handle: COpaquePointer, index: Int): String =
  handle.asStableRef<NugetError>().get().at(index).stackTrace

private const val TICKS_UNIX_EPOCH: Long = 621_355_968_000_000_000L

private const val EPOCH_SECONDS_MIN: Long = -62_135_596_800L

private const val EPOCH_SECONDS_MAX: Long = 253_402_300_799L

@NugetRuntimeApi
public fun Instant.toDotNetTicks(): Long {
  require(epochSeconds in EPOCH_SECONDS_MIN..EPOCH_SECONDS_MAX) {
    "Instant $this is outside System.DateTimeOffset's range " +
        "(0001-01-01T00:00:00Z..9999-12-31T23:59:59.9999999Z)"
  }
  return TICKS_UNIX_EPOCH + epochSeconds * 10_000_000L + nanosecondsOfSecond / 100
}

@NugetRuntimeApi
public fun instantFromDotNetTicks(ticks: Long): Instant {
  require(ticks in 0L..3_155_378_975_999_999_999L) {
    "Tick value $ticks is not a valid System.DateTimeOffset"
  }
  val sinceEpoch: Long = ticks - TICKS_UNIX_EPOCH
  return Instant.fromEpochSeconds(
    epochSeconds = sinceEpoch / 10_000_000L,
    nanosecondAdjustment = ((sinceEpoch % 10_000_000L) * 100L).toInt(),
  )
}

private const val TIMESPAN_MAX_SECONDS: Long = 922_337_203_685L

private const val TIMESPAN_MAX_FRAC_TICKS: Long = 4_775_807L

private const val TIMESPAN_MIN_FRAC_TICKS: Long = -4_775_808L

@NugetRuntimeApi
public fun Duration.toDotNetTicks(): Long {
  require(isFinite()) {
    "Duration $this is infinite and cannot be represented as a System.TimeSpan"
  }
  return toComponents { seconds, nanoseconds ->
    require(seconds in -TIMESPAN_MAX_SECONDS..TIMESPAN_MAX_SECONDS) {
      "Duration $this is outside System.TimeSpan's range (about 10675199 days either side of zero)"
    }
    val frac: Long = nanoseconds / 100L
    require(!(seconds == TIMESPAN_MAX_SECONDS && frac > TIMESPAN_MAX_FRAC_TICKS)) {
      "Duration $this is outside System.TimeSpan's range (about 10675199 days either side of zero)"
    }
    require(!(seconds == -TIMESPAN_MAX_SECONDS && frac < TIMESPAN_MIN_FRAC_TICKS)) {
      "Duration $this is outside System.TimeSpan's range (about 10675199 days either side of zero)"
    }
    seconds * 10_000_000L + frac
  }
}

@NugetRuntimeApi
public fun durationFromDotNetTicks(ticks: Long): Duration =
  if (ticks in -92_233_720_368_547_758L..92_233_720_368_547_758L) (ticks * 100L).nanoseconds
  else (ticks / 10_000L).milliseconds

/**
 * ADR-102: the identity marker a generated C#-implemented bridge class carries, so a handle that
 * came from C# in the first place comes back as the original object rather than as a second
 * wrapper over its own bridge.
 */
@NugetRuntimeApi
public interface NugetCSharpBridge {
  public val nugetToken: COpaquePointer
}

@NugetRuntimeApi
@CName("nuget_csharp_token")
public fun export_nuget_csharp_token(handle: COpaquePointer): COpaquePointer? =
  (handle.asStableRef<Any>().get() as? NugetCSharpBridge)?.nugetToken

@NugetRuntimeApi
@CName("nuget_gc_collect")
@OptIn(NativeRuntimeApi::class)
public fun export_nuget_gc_collect() {
  GC.collect()
}

@NugetRuntimeApi
@CName("nuget_stateflow_collect")
public fun export_nuget_stateflow_collect(
  flowHandle: COpaquePointer,
  scopeHandle: COpaquePointer,
  onNextPtr: COpaquePointer,
  onCompletePtr: COpaquePointer,
  onErrorPtr: COpaquePointer,
  userData: COpaquePointer,
): COpaquePointer {
  val flow = flowHandle.asStableRef<StateFlow<*>>().get()
  val scope = scopeHandle.asStableRef<CoroutineScope>().get()
  // ADR-128: `collectForCSharp` owns the trio of callbacks and the launch; the flow handle is
  // still dereferenced before the launch, as today, and only `.collect` moves into the body.
  return collectForCSharp(scope, onNextPtr, onCompletePtr, onErrorPtr, userData) { emit ->
    flow.collect { value -> emit(NugetHandles.retain(value as Any)) }
  }
}

@NugetRuntimeApi
@CName("nuget_stateflow_value")
public fun export_nuget_stateflow_value(flowHandle: COpaquePointer): COpaquePointer =
  NugetHandles.retain(flowHandle.asStableRef<StateFlow<*>>().get().value as Any)
