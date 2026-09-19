package io.github.xxfast.kotlin.native.nuget

import io.github.xxfast.kotlin.native.nuget.rir.RirAssembly
import io.github.xxfast.kotlin.native.nuget.rir.RirAsyncKind
import io.github.xxfast.kotlin.native.nuget.rir.RirClass
import io.github.xxfast.kotlin.native.nuget.rir.RirFile
import io.github.xxfast.kotlin.native.nuget.rir.RirInterface
import io.github.xxfast.kotlin.native.nuget.rir.RirInterfaceType
import io.github.xxfast.kotlin.native.nuget.rir.RirMethod
import io.github.xxfast.kotlin.native.nuget.rir.RirNamespace
import io.github.xxfast.kotlin.native.nuget.rir.RirObjectHandleType
import io.github.xxfast.kotlin.native.nuget.rir.RirParameter
import io.github.xxfast.kotlin.native.nuget.rir.RirPrimitiveType
import io.github.xxfast.kotlin.native.nuget.rir.RirStringType
import io.github.xxfast.kotlin.native.nuget.rir.RirStruct
import io.github.xxfast.kotlin.native.nuget.rir.RirTypeRef
import io.github.xxfast.kotlin.native.nuget.rir.RirVoidType
import io.github.xxfast.kotlin.native.nuget.rir.boundHandleTypes
import io.github.xxfast.kotlin.native.nuget.rir.boundInterfaceTypes
import io.github.xxfast.kotlin.native.nuget.rir.bridgeableRegistrables
import io.github.xxfast.kotlin.native.nuget.rir.contractHash
import io.github.xxfast.kotlin.native.nuget.rir.slotCount
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * ADR-152: a C# `Task` / `Task<T>` member binds as a Kotlin `suspend fun` over a Begin/End thunk
 * pair. Both generators are driven off ONE fixture here, because the failure this guards is the
 * two of them disagreeing about how many slots an async member occupies, which no single-sided
 * test can see.
 *
 * The RIR below is hand-built, the shape the REAL reader emits for `TestDependency/Kennel.cs`
 * (`asyncKind: "task"` with `returnType` holding the AWAITED type). That the reader actually emits
 * it is asserted separately, against a compiled assembly, in
 * `NugetExtractApiIntegrationTest.metadata reader maps Task returns to an async method...`, this
 * file is about what the generators do WITH it.
 */
class NugetAsyncBindingTest {

  private fun asyncMethod(
    name: String,
    returnType: RirTypeRef,
    parameters: List<RirParameter> = emptyList(),
    isStatic: Boolean = false,
  ): RirMethod = RirMethod(
    name = name,
    returnType = returnType,
    parameters = parameters,
    isStatic = isStatic,
    asyncKind = RirAsyncKind.TASK,
  )

  private val kennel = RirClass(
    name = "Kennel",
    methods = listOf(
      asyncMethod("NapAsync", RirVoidType),
      asyncMethod("CountAsync", RirPrimitiveType("int")),
      asyncMethod("NameAsync", RirStringType()),
      asyncMethod("WhisperAsync", RirStringType(nullable = true)),
      asyncMethod(
        "AdoptAsync",
        RirObjectHandleType("Test.Kennel", "Kitten"),
        listOf(RirParameter("name", RirStringType())),
      ),
      asyncMethod("RollCallAsync", RirStringType(), isStatic = true),
      RirMethod(name = "Read", returnType = RirStringType()),
      asyncMethod("ReadAsync", RirStringType()),
      asyncMethod(
        "BoardAsync",
        RirStringType(),
        listOf(RirParameter("guest", RirInterfaceType("Test.Kennel", "IFeedable"))),
      ),
    ),
  )

  private val kitten = RirClass(
    name = "Kitten",
    methods = listOf(RirMethod(name = "Describe", returnType = RirStringType())),
  )

  private val feedable = RirInterface(
    name = "IFeedable",
    methods = listOf(RirMethod(name = "Feed", returnType = RirVoidType)),
  )

  private val rir = RirFile(
    assemblies = listOf(
      RirAssembly(
        packageId = "TestDependency",
        assemblyName = "TestDependency",
        namespaces = listOf(
          RirNamespace(name = "Test.Kennel", types = listOf(kennel, kitten, feedable)),
        ),
      ),
    ),
  )

  private fun stub(): String = generateKotlinStubs(rir)
    .single { it.relativePath.endsWith("/Kennel.kt") }.content

  private fun bindings(): String = generateKotlinStubs(rir)
    .single { it.relativePath.endsWith("KennelBindings.kt") }.content

  private fun shim(): String = generateCSharpShims(rir, "sample")
    .single { it.relativePath.endsWith("KennelRegistration.cs") }.content

  // ---------------------------------------------------------------- Kotlin stubs

  @Test
  fun `an async member renders a suspend fun over the begin end pair`() {
    val stub: String = stub()

    assertContains(stub, "suspend fun count(): Int")
    assertContains(stub, "val begin = requireNotNull(KennelBindings.countAsyncBeginFn)")
    assertContains(stub, "val end = requireNotNull(KennelBindings.countAsyncEndFn)")
    assertContains(stub, "val task: COpaquePointer = nugetAwaitTask { callback, ctx ->")
    // The Begin call carries the receiver, the in-args, then the callback and its ctx; the End
    // call carries the task handle and nothing else.
    assertContains(
      stub,
      "nugetCall { err -> begin.invoke(handle.require(\"Kennel\"), callback, ctx, err) }",
    )
    assertContains(stub, "return nugetCall { err -> end.invoke(task, err) }")
  }

  // ADR-130's seam, and the reason it is asserted HERE rather than in the fixture build:
  // `test-library` declares coroutines on nativeMain itself, so a stub naming
  // suspendCancellableCoroutine directly would compile there and fail for a real consumer.
  @Test
  fun `a generated suspend stub names no kotlinx coroutines symbol`() {
    generateKotlinStubs(rir).filter { it.relativePath.startsWith("nativeMain/") }.forEach { file ->
      assertFalse(
        file.content.contains("kotlinx.coroutines"),
        "${file.relativePath} names kotlinx.coroutines; nativeMain cannot see the runtime's " +
            "coroutines api (ADR-130)",
      )
    }
  }

  @Test
  fun `the await seam is an expect in nativeMain with an actual per target`() {
    val files: List<GeneratedFile> = generateKotlinStubs(rir)
    val expect: String =
      files.single {
        it.relativePath.startsWith("nativeMain/") && it.relativePath.endsWith("NugetRuntime.kt")
      }.content
    assertContains(expect, "internal expect suspend fun nugetAwaitTask(")

    listOf("posixMain", "mingwMain").forEach { target ->
      val actual: String =
        files.single {
          it.relativePath.startsWith("$target/") &&
              it.relativePath.endsWith("NugetKotlinErrors.kt")
        }.content
      assertContains(actual, "internal actual suspend fun nugetAwaitTask(")
      assertContains(actual, "awaitForKotlin(")
      // The cancel-then-complete arm: the task GCHandle's only other owner is the End thunk.
      assertContains(actual, "requireNotNull(freeGcHandleFn)")
    }
  }

  @Test
  fun `every awaited return shape keeps its synchronous return half`() {
    val stub: String = stub()

    assertContains(stub, "suspend fun nap()")
    assertContains(stub, "suspend fun name(): String")
    // Nullable reference result: the `?: return null` half, not the error() half.
    assertContains(stub, "suspend fun whisper(): String?")
    assertContains(stub, "suspend fun adopt(name: String): Kitten")
    assertContains(stub, "freeManagedString(resultPtr)")
  }

  @Test
  fun `a static async member has no receiver in its begin call`() {
    val stub: String = stub()

    assertContains(stub, "suspend fun rollCall(): String")
    assertContains(stub, "nugetCall { err -> begin.invoke(callback, ctx, err) }")
  }

  // The Async-suffix rule, both halves: `Read` owns the stripped name, so `ReadAsync` keeps its
  // suffix. Stripping unconditionally here is a Kotlin signature collision (no overload on
  // `suspend`), which is why the rule is conditional rather than absolute.
  @Test
  fun `the Async suffix is kept beside a synchronous sibling`() {
    val stub: String = stub()

    assertContains(stub, "fun read(): String")
    assertContains(stub, "suspend fun readAsync(): String")
    assertFalse(
      stub.contains("suspend fun read(): String"),
      "stripping `Async` beside a sync `Read` collides: Kotlin cannot overload on `suspend`",
    )
  }

  // ADR-085: an interface-typed argument still mints its bridge inside a transfer scope, on the
  // BEGIN call (the only crossing that passes it).
  @Test
  fun `an interface typed argument crosses on the begin call inside a transfer scope`() {
    val stub: String = stub()

    assertContains(stub, "suspend fun board(guest: IFeedable): String")
    assertContains(
      stub,
      "nugetTransferScope { begin.invoke(handle.require(\"Kennel\"), " +
          "handleOf(guest, \"Test.Kennel.IFeedable\"), callback, ctx, err) }",
    )
  }

  @Test
  fun `each async member registers two adjacent pointers in slot order`() {
    val bindings: String = bindings()

    assertContains(bindings, "countAsyncBeginPtr: COpaquePointer?,")
    assertContains(bindings, "countAsyncEndPtr: COpaquePointer?,")
    assertTrue(
      bindings.indexOf("countAsyncBeginPtr") < bindings.indexOf("countAsyncEndPtr"),
      "Begin must precede End in the registration parameter list",
    )
    // The Begin slot's own CFunction type: receiver, in-args, callback, ctx, error slot, Unit.
    assertContains(
      bindings,
      "internal var adoptAsyncBeginFn: CPointer<CFunction<(COpaquePointer?, COpaquePointer?, " +
          "COpaquePointer?, COpaquePointer?, CPointer<COpaquePointerVar>) -> Unit>>? = null",
    )
    assertContains(
      bindings,
      "internal var adoptAsyncEndFn: CPointer<CFunction<(COpaquePointer?, " +
          "CPointer<COpaquePointerVar>) -> COpaquePointer?>>? = null",
    )
  }

  @Test
  fun `the registered slot count counts both halves of every async member`() {
    // 9 members, 8 of them async: 8 * 2 + 1, and both generators must say the same number or
    // ADR-054's contract check fails every consumer at startup.
    assertEquals(
      17,
      bridgeableRegistrables(
        kennel, boundHandleTypes(rir), boundInterfaceTypes = boundInterfaceTypes(rir),
      ).slotCount(),
    )
    assertContains(bindings(), "expectedSlots = 17,")
    assertContains(shim(), "nuget_test_kennel_kennel_register(\n" + " ".repeat(20) + "17,")
  }

  // ---------------------------------------------------------------- C# shims

  @Test
  fun `an async member emits a Begin thunk that attaches once and an End thunk that unwraps`() {
    val shim: String = shim()

    assertContains(
      shim,
      "private static unsafe void CountAsyncBegin_Thunk(IntPtr selfHandle, IntPtr callback, " +
          "IntPtr ctx, IntPtr* errOut)",
    )
    assertContains(shim, "Task task = receiver.CountAsync();")
    assertContains(shim, "NugetTasks.Attach(task, callback, ctx);")
    assertContains(
      shim,
      "private static unsafe int CountAsyncEnd_Thunk(IntPtr taskHandle, IntPtr* errOut)",
    )
    assertContains(shim, "((Task<int>)handle.Target!).GetAwaiter().GetResult()")
    assertContains(shim, "handle.Free();")
    assertFalse(
      shim.contains(".Result"),
      "End must use GetAwaiter().GetResult(): `.Result` surfaces an AggregateException instead " +
          "of the original managed exception",
    )
  }

  @Test
  fun `a non generic Task End thunk awaits the bare Task and returns void`() {
    val shim: String = shim()

    assertContains(
      shim,
      "private static unsafe void NapAsyncEnd_Thunk(IntPtr taskHandle, IntPtr* errOut)",
    )
    assertContains(shim, "((Task)handle.Target!).GetAwaiter().GetResult();")
  }

  @Test
  fun `a static async member's Begin thunk has no selfHandle`() {
    val shim: String = shim()

    assertContains(
      shim,
      "private static unsafe void RollCallAsyncBegin_Thunk(IntPtr callback, IntPtr ctx, " +
          "IntPtr* errOut)",
    )
    assertContains(shim, "Task task = Kennel.RollCallAsync();")
  }

  @Test
  fun `the module initializer passes both pointers per async member in order`() {
    val shim: String = shim()
    val begin: Int = shim.indexOf("(&CountAsyncBegin_Thunk)")
    val end: Int = shim.indexOf("(&CountAsyncEnd_Thunk)")

    assertTrue(begin > 0 && end > begin, "Begin then End, adjacent, in the ModuleInitializer")
    assertContains(
      shim,
      "(IntPtr)(delegate* unmanaged[Cdecl]<IntPtr, IntPtr, IntPtr, IntPtr*, void>)" +
          "(&CountAsyncBegin_Thunk)",
    )
    assertContains(
      shim,
      "(IntPtr)(delegate* unmanaged[Cdecl]<IntPtr, IntPtr*, int>)(&CountAsyncEnd_Thunk)",
    )
    assertContains(shim, "    using System.Threading.Tasks;")
  }

  @Test
  fun `the runtime registration carries the one shared Attach helper`() {
    val runtime: String = generateCSharpShims(rir, "sample")
      .single { it.relativePath.endsWith("NugetRuntimeRegistration.cs") }.content

    assertContains(runtime, "internal static unsafe class NugetTasks")
    assertContains(runtime, "internal static void Attach(Task task, IntPtr callback, IntPtr ctx)")
    assertContains(runtime, "TaskContinuationOptions.None,")
    assertContains(runtime, "TaskScheduler.Default);")
    assertFalse(
      runtime.contains("ExecuteSynchronously"),
      "the callback must never run Kotlin inline on the thread that completed the task",
    )
  }

  // ---------------------------------------------------------------- contract + deferred scope

  @Test
  fun `turning a synchronous member async drifts the contract hash`() {
    val sync = RirClass(
      name = "Kennel",
      methods = listOf(RirMethod(name = "CountAsync", returnType = RirPrimitiveType("int"))),
    )
    val async = RirClass(
      name = "Kennel",
      methods = listOf(asyncMethod("CountAsync", RirPrimitiveType("int"))),
    )

    assertNotEquals(
      contractHash(sync, bridgeableRegistrables(sync, emptySet()), emptyMap()),
      contractHash(async, bridgeableRegistrables(async, emptySet()), emptyMap()),
      "an `async:` prefix must make `T Foo()` and `Task<T> Foo()` hash differently",
    )
  }

  @Test
  fun `async on a deferred owner is a named skip, never a half built pair`() {
    val deferred = RirFile(
      assemblies = listOf(
        RirAssembly(
          packageId = "TestDependency",
          assemblyName = "TestDependency",
          namespaces = listOf(
            RirNamespace(
              name = "Test.Kennel",
              types = listOf(
                RirStruct(
                  name = "Paw",
                  methods = listOf(asyncMethod("FlexAsync", RirPrimitiveType("int"))),
                ),
                RirInterface(
                  name = "IFeedable",
                  methods = listOf(asyncMethod("FeedAsync", RirVoidType)),
                ),
              ),
            ),
          ),
        ),
      ),
    )

    val warnings: List<String> = diagnosticWarnings(deferred)
    assertTrue(
      warnings.any { it.contains("FlexAsync") && it.contains("ADR-152 deferred scope") },
      "an async struct method must be skipped by name, got: $warnings",
    )
    assertTrue(
      warnings.any { it.contains("FeedAsync") && it.contains("ADR-152 deferred scope") },
      "an async interface member must be skipped by name, got: $warnings",
    )
    generateKotlinStubs(deferred).forEach {
      assertFalse(it.content.contains("flexAsync"), "${it.relativePath} bound a deferred shape")
      assertFalse(it.content.contains("feedAsync"), "${it.relativePath} bound a deferred shape")
    }
  }
}
