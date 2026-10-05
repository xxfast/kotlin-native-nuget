@file:OptIn(ExperimentalForeignApi::class, NugetRuntimeApi::class)

package io.github.xxfast.kotlin.native.nuget.runtime

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.staticCFunction
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking

/**
 * ADR-202: the runtime-owned routes `nuget_suspend_func{0..3}_invoke` and
 * `nuget_stateflow_collect` classify a thrown exception with the module classifier the generated
 * code installed, and with the stdlib rows alone when nothing is installed. Driven through the real
 * exports, the way C# calls them.
 */
class RuntimeMappedTypeTest {

  /** Stands in for `kotlinx.io.IOException`: a row only the module classifier knows. */
  private class LitterBoxJammed(message: String) : Exception(message)

  private val moduleClassifier: (Throwable) -> String? = { t ->
    if (t is LitterBoxJammed) "test.LitterBoxJammed" else nugetStdlibMappedType(t)
  }

  /** A library-authored StateFlow whose `collect` throws, the only way this route can fail. */
  @OptIn(ExperimentalForInheritanceCoroutinesApi::class)
  private class JammedBowlFlow(private val error: Throwable) : StateFlow<String> {
    override val value: String = "jammed"
    override val replayCache: List<String> get() = listOf(value)
    override suspend fun collect(collector: FlowCollector<String>): Nothing = throw error
  }

  @AfterTest
  fun uninstall() {
    nugetModuleMappedType.value = null
  }

  @Test
  fun `every suspend func arity classifies with the installed module classifier`() {
    nugetInstallMappedType(moduleClassifier)
    for (arity in 0..3) {
      val error: NugetError = invokeThrowingSuspendFunc(arity, LitterBoxJammed("Oreo, arity $arity"))
      assertEquals("test.LitterBoxJammed", error.mappedType, "arity $arity")
      assertEquals("Oreo, arity $arity", error.message)
    }
  }

  @Test
  fun `a suspend func with no installed classifier keeps the stdlib rows only`() {
    val module: NugetError = invokeThrowingSuspendFunc(0, LitterBoxJammed("Oreo"))
    assertNull(module.mappedType)
    val stdlib: NugetError = invokeThrowingSuspendFunc(0, IllegalArgumentException("Mylo"))
    assertEquals("kotlin.IllegalArgumentException", stdlib.mappedType)
  }

  @Test
  fun `the installed classifier still answers the stdlib rows through its own fallback`() {
    nugetInstallMappedType(moduleClassifier)
    val error: NugetError = invokeThrowingSuspendFunc(1, NullPointerException())
    assertEquals("kotlin.NullPointerException", error.mappedType)
  }

  @Test
  fun `stateflow collect classifies with the installed module classifier`() {
    nugetInstallMappedType(moduleClassifier)
    val error: NugetError = collectThrowingStateFlow(LitterBoxJammed("Oreo jammed the bowl"))
    assertEquals("test.LitterBoxJammed", error.mappedType)
    assertEquals("Oreo jammed the bowl", error.message)
  }

  @Test
  fun `stateflow collect with no installed classifier keeps the stdlib rows only`() {
    assertNull(collectThrowingStateFlow(LitterBoxJammed("Oreo")).mappedType)
    assertEquals(
      "kotlin.IllegalStateException",
      collectThrowingStateFlow(IllegalStateException("Mylo")).mappedType,
    )
  }

  private fun invokeThrowingSuspendFunc(arity: Int, thrown: Throwable): NugetError {
    val live: Long = NugetHandles.live.value
    val fn: Any = when (arity) {
      0 -> suspendOf0(thrown)
      1 -> suspendOf1(thrown)
      2 -> suspendOf2(thrown)
      else -> suspendOf3(thrown)
    }
    val fnHandle: COpaquePointer = NugetHandles.retain(fn)
    val holder = LaunchResultHolder()
    val holderRef: StableRef<LaunchResultHolder> = StableRef.create(holder)
    val userData: COpaquePointer = holderRef.asCPointer()
    val jobHandle: COpaquePointer = when (arity) {
      0 -> export_nuget_suspend_func0_invoke(fnHandle, mappedResultCallback, userData)
      1 -> export_nuget_suspend_func1_invoke(fnHandle, null, mappedResultCallback, userData)
      2 -> export_nuget_suspend_func2_invoke(fnHandle, null, null, mappedResultCallback, userData)
      else ->
        export_nuget_suspend_func3_invoke(fnHandle, null, null, null, mappedResultCallback, userData)
    }
    runBlocking { jobHandle.asStableRef<Job>().get().join() }

    assertEquals(1, holder.calls)
    assertNull(holder.result)
    val errorHandle: COpaquePointer = assertNotNull(holder.error)
    val error: NugetError = errorHandle.asStableRef<NugetError>().get()
    NugetHandles.release(errorHandle)
    NugetHandles.release(jobHandle)
    NugetHandles.release(fnHandle)
    holderRef.dispose()
    assertEquals(live, NugetHandles.live.value)
    return error
  }

  private fun collectThrowingStateFlow(thrown: Throwable): NugetError {
    val live: Long = NugetHandles.live.value
    val flowHandle: COpaquePointer = NugetHandles.retain(JammedBowlFlow(thrown))
    val holder = CollectHolder()
    val holderRef: StableRef<CollectHolder> = StableRef.create(holder)
    val jobHandle: COpaquePointer = export_nuget_stateflow_collect(
      flowHandle = flowHandle,
      scopeHandle = null,
      onNextPtr = mappedOnNextCallback,
      onCompletePtr = mappedOnCompleteCallback,
      onErrorPtr = mappedOnErrorCallback,
      userData = holderRef.asCPointer(),
    )
    runBlocking { jobHandle.asStableRef<Job>().get().join() }

    assertEquals(0, holder.items.size)
    assertEquals(0, holder.completes)
    val errorHandle: COpaquePointer = assertNotNull(holder.error)
    val error: NugetError = errorHandle.asStableRef<NugetError>().get()
    NugetHandles.release(errorHandle)
    NugetHandles.release(jobHandle)
    NugetHandles.release(flowHandle)
    holderRef.dispose()
    assertEquals(live, NugetHandles.live.value)
    return error
  }
}

private fun suspendOf0(thrown: Throwable): suspend () -> Any? = { throw thrown }

private fun suspendOf1(thrown: Throwable): suspend (Any?) -> Any? = { _ -> throw thrown }

private fun suspendOf2(thrown: Throwable): suspend (Any?, Any?) -> Any? = { _, _ -> throw thrown }

private fun suspendOf3(thrown: Throwable): suspend (Any?, Any?, Any?) -> Any? =
  { _, _, _ -> throw thrown }

private val mappedResultCallback =
  staticCFunction<
      COpaquePointer?, COpaquePointer?, Byte, COpaquePointer?, Unit,
      > { result, error, cancelled, userData ->
    val holder: LaunchResultHolder = userData!!.asStableRef<LaunchResultHolder>().get()
    holder.calls++
    holder.result = result
    holder.error = error
    holder.cancelled = cancelled
  }

private val mappedOnNextCallback =
  staticCFunction<COpaquePointer?, Byte, COpaquePointer?, Unit> { item, cancelled, userData ->
    val holder: CollectHolder = userData!!.asStableRef<CollectHolder>().get()
    holder.items += item
    holder.lastCancelled = cancelled
  }

private val mappedOnCompleteCallback = staticCFunction<COpaquePointer?, Unit> { userData ->
  userData!!.asStableRef<CollectHolder>().get().completes++
}

private val mappedOnErrorCallback =
  staticCFunction<COpaquePointer?, COpaquePointer?, Unit> { error, userData ->
    userData!!.asStableRef<CollectHolder>().get().error = error
  }
