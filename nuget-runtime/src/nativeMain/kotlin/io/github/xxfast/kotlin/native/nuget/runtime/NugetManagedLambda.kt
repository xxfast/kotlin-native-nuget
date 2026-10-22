@file:OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class, NugetRuntimeApi::class)

package io.github.xxfast.kotlin.native.nuget.runtime

import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.CName
import kotlin.native.ref.Cleaner
import kotlin.native.ref.createCleaner
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set

// ADR-236: a C# delegate set into a Kotlin lambda-typed property. The generated `KotlinFunc<...>`
// (and its siblings) convert implicitly from the matching BCL delegate by minting, through the
// exports below, a Kotlin lambda over the delegate. The lambda is erased: every argument crosses
// as a retained handle the C# thunk reads with `NugetMarshal.FromHandle<T>`, and the result as an
// owned handle C# minted with `Wrap<T>` (or retained through `nuget_handle_retain`). The delegate
// itself lives behind a C# `GCHandle` key the lambda's cleaner frees once Kotlin drops the
// lambda, so replacing or clearing the property is what lets the delegate be collected.

private typealias NugetManagedRelease = CPointer<CFunction<(COpaquePointer?) -> Unit>>

/** The C# key and the thunk that frees it. Captures nothing else, so the cleaner cannot pin the
 *  lambda it is attached to. */
private class NugetManagedKey(val key: COpaquePointer, val release: NugetManagedRelease) {
  fun free() {
    release(key)
  }
}

/** Reads and releases a result handle the C# thunk minted for Kotlin. */
private fun COpaquePointer?.takeManagedResult(): Any? {
  val handle: COpaquePointer = this ?: return null
  val value: Any = handle.asStableRef<Any>().get()
  NugetHandles.release(handle)
  return value
}

/**
 * One C# delegate seen from Kotlin. [call] marshals the erased arguments into retained handles
 * (C# owns them from the thunk on) and reads the result back. A `Unit` lambda answers `Unit`, the
 * value every Kotlin `() -> Unit` call site expects, whatever the thunk returned.
 */
private class NugetManagedFunction(
  private val invoke: CPointer<
    CFunction<(COpaquePointer?, COpaquePointer?, COpaquePointer?) -> COpaquePointer?>
  >,
  key: NugetManagedKey,
  private val unit: Boolean,
) {
  private val key: COpaquePointer = key.key

  @Suppress("unused")
  private val cleaner: Cleaner = createCleaner(key) { it.free() }

  fun call(arguments: Array<Any?>): Any? = memScoped {
    val slots: CPointer<COpaquePointerVar> = allocArray(arguments.size.coerceAtLeast(1))
    arguments.forEachIndexed { index, argument ->
      slots[index] = argument?.let(NugetHandles::retain)
    }
    val result: COpaquePointer? = nugetCallbackCall { error -> invoke(key, slots, error) }
    val value: Any? = result.takeManagedResult()
    if (unit) Unit else value
  }
}

/**
 * The suspend twin of [NugetManagedFunction]: [begin] reads the arguments synchronously and starts
 * the delegate's `Task`, [end] unwraps the completed task (the ADR-152 `awaitForKotlin` shape).
 */
private class NugetManagedSuspendFunction(
  private val begin: CPointer<
    CFunction<
      (
        COpaquePointer?, COpaquePointer?, COpaquePointer?, COpaquePointer?, COpaquePointer?
      ) -> COpaquePointer?
    >
  >,
  private val end: CPointer<CFunction<(COpaquePointer?, COpaquePointer?) -> COpaquePointer?>>,
  private val releaseTask: NugetManagedRelease,
  private val cancel: CPointer<CFunction<(COpaquePointer?, Int) -> Unit>>,
  key: NugetManagedKey,
  private val unit: Boolean,
) {
  private val key: COpaquePointer = key.key

  @Suppress("unused")
  private val cleaner: Cleaner = createCleaner(key) { it.free() }

  suspend fun call(arguments: Array<Any?>): Any? {
    val task: COpaquePointer = awaitForKotlin(
      release = { handle -> releaseTask(handle) },
      cancel = { source, cancelled -> cancel(source, if (cancelled) 1 else 0) },
    ) { callback, ctx ->
      memScoped {
        val slots: CPointer<COpaquePointerVar> = allocArray(arguments.size.coerceAtLeast(1))
        arguments.forEachIndexed { index, argument ->
          slots[index] = argument?.let(NugetHandles::retain)
        }
        nugetCallbackCall { error -> begin(key, slots, callback, ctx, error) }
      }
    }
    val result: COpaquePointer? = nugetCallbackCall { error -> end(task, error) }
    val value: Any? = result.takeManagedResult()
    return if (unit) Unit else value
  }
}

/**
 * `void* nuget_func_from_managed(int arity, void* invoke, void* release, void* key, int unit)`:
 * a retained Kotlin `FunctionN` (arity 0 to 3) over the C# delegate behind [key]. The returned
 * handle is owned by the C# `KotlinFunc` the conversion builds.
 */
@NugetRuntimeApi
@CName("nuget_func_from_managed")
public fun export_nuget_func_from_managed(
  arity: Int,
  invoke: COpaquePointer,
  release: COpaquePointer,
  key: COpaquePointer,
  unit: Int,
): COpaquePointer {
  val managedKey = NugetManagedKey(key, release.reinterpret())
  val target = NugetManagedFunction(invoke.reinterpret(), managedKey, unit != 0)
  val lambda: Any = when (arity) {
    0 -> {
      val body: () -> Any? = { target.call(emptyArray()) }
      body
    }
    1 -> {
      val body: (Any?) -> Any? = { a -> target.call(arrayOf(a)) }
      body
    }
    2 -> {
      val body: (Any?, Any?) -> Any? = { a, b -> target.call(arrayOf(a, b)) }
      body
    }
    3 -> {
      val body: (Any?, Any?, Any?) -> Any? = { a, b, c -> target.call(arrayOf(a, b, c)) }
      body
    }
    else -> throw IllegalArgumentException("[nuget] no managed lambda of arity $arity")
  }
  return NugetHandles.retain(lambda)
}

/**
 * `void* nuget_suspend_func_from_managed(int arity, void* begin, void* end, void* releaseTask,
 * void* cancel, void* release, void* key, int unit)`: the suspend twin of
 * [export_nuget_func_from_managed], a retained `SuspendFunctionN` over a C#
 * `Func<..., CancellationToken, Task<...>>`.
 */
@NugetRuntimeApi
@CName("nuget_suspend_func_from_managed")
public fun export_nuget_suspend_func_from_managed(
  arity: Int,
  begin: COpaquePointer,
  end: COpaquePointer,
  releaseTask: COpaquePointer,
  cancel: COpaquePointer,
  release: COpaquePointer,
  key: COpaquePointer,
  unit: Int,
): COpaquePointer {
  val target = NugetManagedSuspendFunction(
    begin.reinterpret(), end.reinterpret(), releaseTask.reinterpret(), cancel.reinterpret(),
    NugetManagedKey(key, release.reinterpret()), unit != 0,
  )
  val lambda: Any = when (arity) {
    0 -> {
      val body: suspend () -> Any? = { target.call(emptyArray()) }
      body
    }
    1 -> {
      val body: suspend (Any?) -> Any? = { a -> target.call(arrayOf(a)) }
      body
    }
    2 -> {
      val body: suspend (Any?, Any?) -> Any? = { a, b -> target.call(arrayOf(a, b)) }
      body
    }
    3 -> {
      val body: suspend (Any?, Any?, Any?) -> Any? = { a, b, c -> target.call(arrayOf(a, b, c)) }
      body
    }
    else -> throw IllegalArgumentException("[nuget] no managed suspend lambda of arity $arity")
  }
  return NugetHandles.retain(lambda)
}

/**
 * `void* nuget_handle_retain(void* handle)`: a fresh retained handle over the object [handle]
 * holds, for a C# thunk handing Kotlin a value whose handle a live wrapper still owns.
 */
@NugetRuntimeApi
@CName("nuget_handle_retain")
public fun export_nuget_handle_retain(handle: COpaquePointer): COpaquePointer =
  NugetHandles.retain(handle.asStableRef<Any>().get())
