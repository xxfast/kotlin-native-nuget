package io.github.xxfast.kotlin.native.nuget

import io.github.xxfast.kotlin.native.nuget.rir.NUGET_RUNTIME_CONTRACT_HASH
import io.github.xxfast.kotlin.native.nuget.rir.RirAssembly
import io.github.xxfast.kotlin.native.nuget.rir.RirAsyncKind
import io.github.xxfast.kotlin.native.nuget.rir.RirClass
import io.github.xxfast.kotlin.native.nuget.rir.RirFile
import io.github.xxfast.kotlin.native.nuget.rir.RirMethod
import io.github.xxfast.kotlin.native.nuget.rir.RirNamespace
import io.github.xxfast.kotlin.native.nuget.rir.RirParameter
import io.github.xxfast.kotlin.native.nuget.rir.RirPrimitiveType
import io.github.xxfast.kotlin.native.nuget.rir.RirStringType
import io.github.xxfast.kotlin.native.nuget.rir.RirTypeRef
import io.github.xxfast.kotlin.native.nuget.rir.bridgeableRegistrables
import io.github.xxfast.kotlin.native.nuget.rir.contractHash
import io.github.xxfast.kotlin.native.nuget.rir.fnv1a64
import io.github.xxfast.kotlin.native.nuget.rir.slotCount
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * ADR-153: an async C# method taking exactly one `CancellationToken` binds with the token elided,
 * and the bridge supplies a `CancellationTokenSource` it owns, so cancelling the Kotlin coroutine
 * actually tells the C# work to stop.
 *
 * Both generators are driven off ONE fixture, for the ADR-152 reason: the failure this guards is
 * the two halves disagreeing about the Begin thunk's return type or the runtime's slot count,
 * which no single-sided test can see. `CountAsync` is the no-token control in every row: a member
 * without a token keeps ADR-152's `void` Begin shape exactly.
 *
 * That the REAL reader produces `cancellationToken` at all is asserted separately against a
 * compiled assembly in `NugetExtractApiIntegrationTest` (CLAUDE.md's hand-built-fixture warning);
 * this file is about what the generators do WITH it.
 */
class NugetCancellationTokenBindingTest {

  private fun asyncMethod(
    name: String,
    returnType: RirTypeRef,
    parameters: List<RirParameter> = emptyList(),
    cancellationToken: Int? = null,
  ): RirMethod = RirMethod(
    name = name,
    returnType = returnType,
    parameters = parameters,
    asyncKind = RirAsyncKind.TASK,
    cancellationToken = cancellationToken,
  )

  // C#: Task<int> CountAsync();
  //     Task<int> StayAsync(string name, CancellationToken ct);
  //     Task<int> FetchAsync(CancellationToken ct, int count);
  private val kennel = RirClass(
    name = "Kennel",
    methods = listOf(
      asyncMethod("CountAsync", RirPrimitiveType("int")),
      asyncMethod(
        "StayAsync",
        RirPrimitiveType("int"),
        listOf(RirParameter("name", RirStringType())),
        cancellationToken = 1,
      ),
      asyncMethod(
        "FetchAsync",
        RirPrimitiveType("int"),
        listOf(RirParameter("count", RirPrimitiveType("int"))),
        cancellationToken = 0,
      ),
    ),
  )

  private val rir = RirFile(
    assemblies = listOf(
      RirAssembly(
        packageId = "TestDependency",
        assemblyName = "TestDependency",
        namespaces = listOf(RirNamespace(name = "Test.Kennel", types = listOf(kennel))),
      ),
    ),
  )

  private fun stub(): String = generateKotlinStubs(rir)
    .single { it.relativePath.endsWith("/Kennel.kt") }.content

  private fun bindings(): String = generateKotlinStubs(rir)
    .single { it.relativePath.endsWith("KennelBindings.kt") }.content

  private fun shim(): String = generateCSharpShims(rir, "sample")
    .single { it.relativePath.endsWith("KennelRegistration.cs") }.content

  private fun runtimeShim(): String = generateCSharpShims(rir, "sample")
    .single { it.relativePath.endsWith("NugetRuntimeRegistration.cs") }.content

  // The one file that holds the generated error plumbing, found by content rather than by name so
  // a rename of the emitted file does not silently turn this into a vacuous pass.
  private fun errorSupport(): String = generateKotlinStubs(rir)
    .single { it.content.contains("internal fun nugetThrowManagedError(") }.content

  // The shared runtime register export lives in its own generated file, not in the per-class
  // bindings; found by content for the same reason as errorSupport().
  private fun runtimeBindings(): String = generateKotlinStubs(rir)
    .single { it.content.contains("@CName(\"nuget_runtime_register\")") }.content

  // ---------------------------------------------------------------- Kotlin stubs

  @Test
  fun `an elided token leaves a suspend fun with the remaining parameters only`() {
    val stub: String = stub()

    assertContains(stub, "suspend fun stay(name: String): Int")
    assertContains(stub, "suspend fun fetch(count: Int): Int")
    assertFalse(
      stub.contains("CancellationToken"),
      "the token is the bridge's, never the caller's: it must not appear in the stub",
    )
  }

  @Test
  fun `a token method's begin slot returns the cancellation source handle`() {
    val bindings: String = bindings()

    // Receiver, the one remaining in-arg, callback, ctx, error slot, and now the CTS GCHandle.
    assertContains(
      bindings,
      "internal var stayAsyncBeginFn: CPointer<CFunction<(COpaquePointer?, COpaquePointer?, " +
          "COpaquePointer?, COpaquePointer?, CPointer<COpaquePointerVar>) -> COpaquePointer?>>? " +
          "= null",
    )
    // The control: no token, no handle, ADR-152's shape untouched.
    assertContains(
      bindings,
      "internal var countAsyncBeginFn: CPointer<CFunction<(COpaquePointer?, COpaquePointer?, " +
          "COpaquePointer?, CPointer<COpaquePointerVar>) -> Unit>>? = null",
    )
  }

  @Test
  fun `the await actual releases the cancellation source through the runtime slot`() {
    listOf("posixMain", "mingwMain").forEach { target ->
      val actual: String = generateKotlinStubs(rir).single {
        it.relativePath.startsWith("$target/") && it.relativePath.endsWith("NugetKotlinErrors.kt")
      }.content

      assertContains(actual, "requireNotNull(releaseCancellationFn)")
      assertContains(actual, "if (cancelled) 1 else 0")
    }
  }

  // The mapping site, ADR-153's second half: ALWAYS, at the single throw site, so a synchronous
  // thunk that throws an OperationCanceledException surfaces the same way.
  @Test
  fun `a cancellation kind error throws CancellationException with the managed cause`() {
    val support: String = errorSupport()

    assertContains(support, "managedErrorKindFn")
    assertContains(
      support,
      "throw kotlin.coroutines.cancellation.CancellationException(message, managed)",
    )
    // ADR-130 as amended by ADR-155: `Flow` is the ONE kotlinx.coroutines name nativeMain may
    // carry (the plugin puts kotlinx-coroutines-core there for it). The cancellation type must
    // still be the stdlib one — on Kotlin/Native it IS the kotlinx type, via a typealias, so
    // naming kotlinx here would be a gratuitous dependency on a source set that had none.
    assertFalse(
      support.lineSequence().any {
        it.contains("kotlinx.coroutines") && !it.contains("kotlinx.coroutines.flow.Flow")
      },
      "nativeMain may name only kotlinx.coroutines.flow.Flow (ADR-130/155); the cancellation " +
          "type is the stdlib one",
    )
  }

  @Test
  fun `the runtime register export carries ten slots and both new thunks`() {
    val bindings: String = runtimeBindings()

    // ADR-155 took the shared runtime from ADR-153's 7 slots to 10.
    assertContains(bindings, "expectedSlots = 10,")
    assertContains(bindings, "releaseCancellationPtr: COpaquePointer?,")
    assertContains(bindings, "managedErrorKindPtr: COpaquePointer?,")
    assertContains(bindings, "internal var releaseCancellationFn:")
    assertContains(bindings, "internal var managedErrorKindFn:")
  }

  // ---------------------------------------------------------------- C# shims

  @Test
  fun `a token method's Begin thunk mints the source after Attach and returns its handle`() {
    val shim: String = shim()

    assertContains(shim, "private static unsafe IntPtr StayAsyncBegin_Thunk(")
    assertContains(shim, "CancellationTokenSource cts = new CancellationTokenSource();")
    assertContains(shim, "NugetTasks.Attach(task, callback, ctx);")
    assertContains(shim, "return GCHandle.ToIntPtr(GCHandle.Alloc(cts));")
    assertTrue(
      shim.indexOf("NugetTasks.Attach(task, callback, ctx);") <
          shim.indexOf("GCHandle.Alloc(cts)"),
      "the handle is minted LAST: nothing above it can leak it on a throw",
    )
    assertContains(shim, "    using System.Threading;")
  }

  @Test
  fun `the token goes in at its recorded C# index`() {
    val shim: String = shim()

    // StayAsync(string name, CancellationToken ct): index 1, so the token is last.
    assertContains(shim, ", cts.Token);")
    // FetchAsync(CancellationToken ct, int count): index 0, so the token comes FIRST. An index
    // the shim ignores would still compile for StayAsync and silently swap these two.
    assertContains(shim, "receiver.FetchAsync(cts.Token, ")
  }

  @Test
  fun `the Begin error path returns zero instead of a handle`() {
    val shim: String = shim()

    // `default` IS IntPtr.Zero for an IntPtr return, and it is the one catch path every
    // error-channel thunk in the file shares (ADR-104), so the Begin thunk hands back no handle.
    assertContains(shim, "return default;")
  }

  @Test
  fun `the module initializer declares the Begin pointer with its new return type`() {
    val shim: String = shim()

    // selfHandle, name, callback, ctx, errOut, -> IntPtr
    assertContains(
      shim,
      "(IntPtr)(delegate* unmanaged[Cdecl]<IntPtr, IntPtr, IntPtr, IntPtr, IntPtr*, IntPtr>)" +
          "(&StayAsyncBegin_Thunk)",
    )
    // The control keeps `void`, which is the whole point of doing this per method.
    assertContains(
      shim,
      "(IntPtr)(delegate* unmanaged[Cdecl]<IntPtr, IntPtr, IntPtr, IntPtr*, void>)" +
          "(&CountAsyncBegin_Thunk)",
    )
  }

  @Test
  fun `the release cancellation thunk queues Cancel and never disposes on that arm`() {
    val runtime: String = runtimeShim()

    assertContains(
      runtime,
      "private static void ReleaseCancellation_Thunk(IntPtr handle, int cancel)",
    )
    assertContains(runtime, "cts.Dispose();")
    assertContains(runtime, "ThreadPool.UnsafeQueueUserWorkItem(")
    assertContains(runtime, "preferLocal: false")
    assertTrue(
      runtime.indexOf("UnsafeQueueUserWorkItem(") < runtime.indexOf(".Cancel()"),
      "Cancel() runs user registrations inline and can throw; it must never run on the kotlinx " +
          "cancellation handler's thread",
    )
    assertTrue(
      runtime.indexOf("cts.Dispose();") < runtime.indexOf("UnsafeQueueUserWorkItem("),
      "the cancelled arm must not dispose: a Dispose racing the queued Cancel throws " +
          "ObjectDisposedException",
    )
  }

  @Test
  fun `the managed error kind thunk tests the exception type rather than its name`() {
    val runtime: String = runtimeShim()

    assertContains(runtime, "private static int ManagedErrorKind_Thunk(IntPtr err)")
    assertContains(runtime, "is OperationCanceledException ? 1 : 0")
    assertFalse(
      runtime.contains("\"System.Threading.Tasks.TaskCanceledException\""),
      "a name match misses a user subclass of OperationCanceledException (ADR-153 spike)",
    )
  }

  @Test
  fun `both generators register ten runtime slots`() {
    val runtime: String = runtimeShim()

    assertContains(runtime, "nuget_runtime_register(10 slots)")
    assertContains(runtime, "IntPtr releaseCancellationPtr, IntPtr managedErrorKindPtr")
    assertContains(runtime, "(&ReleaseCancellation_Thunk)")
    assertContains(runtime, "(&ManagedErrorKind_Thunk)")
    assertContains(runtimeBindings(), "expectedSlots = 10,")
  }

  // ---------------------------------------------------------------- contract

  @Test
  fun `adding a token to an existing async method drifts the contract hash`() {
    val without = RirClass(
      name = "Kennel",
      methods = listOf(asyncMethod("StayAsync", RirPrimitiveType("int"))),
    )
    val with = RirClass(
      name = "Kennel",
      methods = listOf(asyncMethod("StayAsync", RirPrimitiveType("int"), cancellationToken = 0)),
    )

    assertNotEquals(
      contractHash(without, bridgeableRegistrables(without, emptySet()), emptyMap()),
      contractHash(with, bridgeableRegistrables(with, emptySet()), emptyMap()),
      "the Begin thunk's return type changed; a stale shim must fail loudly at startup",
    )
    // Same slot count either way: the token costs no registration slot.
    assertEquals(
      bridgeableRegistrables(without, emptySet()).slotCount(),
      bridgeableRegistrables(with, emptySet()).slotCount(),
    )
  }

  @Test
  fun `moving the token's index drifts the contract hash`() {
    val first = RirClass(
      name = "Kennel",
      methods = listOf(
        asyncMethod(
          "FetchAsync",
          RirPrimitiveType("int"),
          listOf(RirParameter("count", RirPrimitiveType("int"))),
          cancellationToken = 0,
        ),
      ),
    )
    val second = RirClass(
      name = "Kennel",
      methods = listOf(
        asyncMethod(
          "FetchAsync",
          RirPrimitiveType("int"),
          listOf(RirParameter("count", RirPrimitiveType("int"))),
          cancellationToken = 1,
        ),
      ),
    )

    assertNotEquals(
      contractHash(first, bridgeableRegistrables(first, emptySet()), emptyMap()),
      contractHash(second, bridgeableRegistrables(second, emptySet()), emptyMap()),
      "the shim inserts cts.Token AT the index; an index-blind hash cannot see the two overloads " +
          "swap places",
    )
  }

  @Test
  fun `the runtime contract hash moved off its five slot value`() {
    assertNotEquals(
      fnv1a64(
        "runtime:freeGcHandle(handle:COpaquePointer):Unit;" +
            "weakenGcHandle(handle:COpaquePointer):COpaquePointer;" +
            "resolveGcHandle(handle:COpaquePointer):COpaquePointer;" +
            "managedErrorType(err:COpaquePointer):COpaquePointer;" +
            "managedErrorMessage(err:COpaquePointer):COpaquePointer"
      ),
      NUGET_RUNTIME_CONTRACT_HASH,
      "7 slots is a different runtime contract from ADR-104's 5; the hash is what tells a stale " +
          "shim so (ADR-054)",
    )
  }
}
