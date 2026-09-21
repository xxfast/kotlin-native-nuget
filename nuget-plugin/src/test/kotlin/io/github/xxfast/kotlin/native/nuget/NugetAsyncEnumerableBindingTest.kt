package io.github.xxfast.kotlin.native.nuget

import io.github.xxfast.kotlin.native.nuget.rir.RirAssembly
import io.github.xxfast.kotlin.native.nuget.rir.RirAsyncKind
import io.github.xxfast.kotlin.native.nuget.rir.RirClass
import io.github.xxfast.kotlin.native.nuget.rir.RirFile
import io.github.xxfast.kotlin.native.nuget.rir.RirInterface
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
import io.github.xxfast.kotlin.native.nuget.rir.parseReverseIr
import io.github.xxfast.kotlin.native.nuget.rir.slotCount
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * ADR-155: a C# method returning `IAsyncEnumerable<T>` binds as a Kotlin **plain** `fun` returning
 * `Flow<T>`, pulled over an `Enumerate` / `Current` slot pair per method plus three shared runtime
 * slots (`MoveNextBegin`, `MoveNextEnd`, `DisposeEnumeration`).
 *
 * Both generators are driven off ONE fixture, for the same reason [NugetAsyncBindingTest] is: the
 * failure this guards is the two of them disagreeing about how many slots the member occupies,
 * which no single-sided test can see.
 *
 * The RIR below is hand-built, the shape the reader is specified to emit for the `IAsyncEnumerable`
 * members of `TestDependency/Kennel.cs`: `asyncKind: "async_enumerable"` with `returnType` holding
 * the ELEMENT type. That the reader actually emits it is asserted separately against a compiled
 * assembly in `NugetExtractApiIntegrationTest`; this file is about what the generators do WITH it.
 */
class NugetAsyncEnumerableBindingTest {

  private fun streamMethod(
    name: String,
    element: RirTypeRef,
    parameters: List<RirParameter> = emptyList(),
    isStatic: Boolean = false,
    cancellationToken: Int? = null,
  ): RirMethod = RirMethod(
    name = name,
    returnType = element,
    parameters = parameters,
    isStatic = isStatic,
    asyncKind = RirAsyncKind.ASYNC_ENUMERABLE,
    cancellationToken = cancellationToken,
  )

  private val kennel = RirClass(
    name = "Kennel",
    methods = listOf(
      // converting element: a managed string, freed by the sync return half
      streamMethod(
        "BarksAsync", RirStringType(), listOf(RirParameter("count", RirPrimitiveType("int"))),
      ),
      // converting element: a bound class handle
      streamMethod("LitterAsync", RirObjectHandleType("Test.Kennel", "Cat"), cancellationToken = 0),
      // non-converting element, and a static
      streamMethod("TicksAsync", RirPrimitiveType("int"), isStatic = true),
      // nullable element
      streamMethod("WhispersAsync", RirStringType(nullable = true)),
    ),
  )

  private val cat = RirClass(
    name = "Cat",
    methods = listOf(RirMethod(name = "Describe", returnType = RirStringType())),
  )

  private val rir = RirFile(
    assemblies = listOf(
      RirAssembly(
        packageId = "TestDependency",
        assemblyName = "TestDependency",
        namespaces = listOf(RirNamespace(name = "Test.Kennel", types = listOf(kennel, cat))),
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

  // ---------------------------------------------------------------- RIR contract

  @Test
  fun `the reader's async_enumerable kind round trips through the parser`() {
    val json = """
      {
        "assemblies": [{
          "packageId": "TestDependency",
          "assemblyName": "TestDependency",
          "namespaces": [{
            "name": "Test.Kennel",
            "types": [{
              "kind": "class",
              "name": "Kennel",
              "methods": [{
                "name": "BarksAsync",
                "asyncKind": "async_enumerable",
                "returnType": { "kind": "string" },
                "parameters": [{ "name": "count", "type": { "kind": "primitive", "name": "int" } }]
              }]
            }]
          }]
        }]
      }
    """.trimIndent()

    val parsed: RirFile = parseReverseIr(json)
    val method: RirMethod =
      (parsed.assemblies.single().namespaces.single().types.single() as RirClass).methods.single()

    assertEquals(RirAsyncKind.ASYNC_ENUMERABLE, method.asyncKind)
  }

  // ---------------------------------------------------------------- Kotlin stubs

  @Test
  fun `an async enumerable member renders a plain fun returning a Flow`() {
    val stub: String = stub()

    assertContains(stub, "fun barks(count: Int): Flow<String>")
    assertFalse(
      stub.contains("suspend fun barks"),
      "ADR-155: the method is a plain `fun`; nothing runs until `collect`, got:\n$stub",
    )
    assertContains(stub, "import kotlinx.coroutines.flow.Flow")
  }

  // The element vocabulary is ADR-152's Task<T> vocabulary: `Current` IS the ordinary sync return
  // half, so a converting element still frees its managed string and a handle element still wraps.
  @Test
  fun `every element shape keeps the synchronous return half`() {
    val stub: String = stub()

    assertContains(stub, "fun litter(): Flow<Cat>")
    assertContains(stub, "fun whispers(): Flow<String?>")
    assertContains(stub, "freeManagedString(")
  }

  @Test
  fun `a non converting element needs no marshalling`() {
    assertContains(stub(), "fun ticks(): Flow<Int>")
  }

  @Test
  fun `a static async enumerable member lands on the companion with no receiver`() {
    val stub: String = stub()
    val companion: String = stub.substringAfter("companion object")

    assertContains(companion, "fun ticks(): Flow<Int>")
  }

  // ADR-153, unchanged for the new kind: the single trailing CancellationToken is elided from the
  // Kotlin signature; the bridge owns one token per collect.
  @Test
  fun `a trailing cancellation token is elided from the Kotlin signature`() {
    val stub: String = stub()

    assertContains(stub, "fun litter(): Flow<Cat>")
    assertFalse(
      stub.contains("fun litter(cancellationToken"),
      "ADR-153: the elided token must not reach the Kotlin signature, got:\n$stub",
    )
  }

  // The flow body is the runtime's shape (ADR-128 "runtime owns the shape"): the stub names the
  // seam and the two per-method slots, never a hand-rolled `flow { }`.
  @Test
  fun `the flow body goes through the runtime seam and both per method slots`() {
    val stub: String = stub()

    assertContains(stub, "nugetFlow")
    assertContains(stub, "barksAsyncEnumerateFn")
    assertContains(stub, "barksAsyncCurrentFn")
  }

  @Test
  fun `the flow seam is an expect in nativeMain with an actual per target`() {
    val files: List<GeneratedFile> = generateKotlinStubs(rir)
    val expect: String = files.single {
      it.relativePath.startsWith("nativeMain/") && it.relativePath.endsWith("NugetRuntime.kt")
    }.content
    assertContains(expect, "nugetFlow")

    listOf("posixMain", "mingwMain").forEach { target ->
      val actual: String = files.single {
        it.relativePath.startsWith("$target/") && it.relativePath.endsWith("NugetKotlinErrors.kt")
      }.content
      assertContains(actual, "flowForKotlin(")
    }
  }

  // ---------------------------------------------------------------- registration slots

  @Test
  fun `each async enumerable member registers two adjacent pointers in slot order`() {
    val bindings: String = bindings()

    assertContains(bindings, "barksAsyncEnumeratePtr: COpaquePointer?,")
    assertContains(bindings, "barksAsyncCurrentPtr: COpaquePointer?,")
    assertTrue(
      bindings.indexOf("barksAsyncEnumeratePtr") < bindings.indexOf("barksAsyncCurrentPtr"),
      "Enumerate must precede Current in the registration parameter list",
    )
  }

  @Test
  fun `the registered slot count counts both halves of every async enumerable member`() {
    // 4 members, all async-enumerable: 4 * 2, and both generators must say the same number or
    // ADR-054's contract check fails every consumer at startup.
    assertEquals(
      8,
      bridgeableRegistrables(
        kennel, boundHandleTypes(rir), boundInterfaceTypes = boundInterfaceTypes(rir),
      ).slotCount(),
    )
    assertContains(bindings(), "expectedSlots = 8,")
    assertContains(shim(), "nuget_test_kennel_kennel_register(\n" + " ".repeat(20) + "8,")
  }

  // ---------------------------------------------------------------- C# shims

  @Test
  fun `the Enumerate thunk calls the method and returns one enumeration handle`() {
    val shim: String = shim()

    assertContains(
      shim,
      "private static unsafe IntPtr BarksAsyncEnumerate_Thunk(IntPtr selfHandle, int count, " +
          "IntPtr* errOut)",
    )
    assertContains(shim, "CancellationTokenSource cts = new();")
    assertContains(shim, ".GetAsyncEnumerator(cts.Token)")
    assertContains(shim, "new NugetAsyncEnumeration<string>(")
  }

  @Test
  fun `the Current thunk is the sync return half over the enumeration handle`() {
    val shim: String = shim()

    assertContains(
      shim,
      "private static unsafe IntPtr BarksAsyncCurrent_Thunk(IntPtr enumeration, IntPtr* errOut)",
    )
    assertContains(shim, ".Enumerator.Current")
  }

  // ADR-153: the bridge's own token goes back at exactly the index the elided parameter sat at.
  @Test
  fun `an elided token is passed back at its own index in the Enumerate call`() {
    assertContains(shim(), "receiver.LitterAsync(cts.Token)")
  }

  @Test
  fun `a static member's Enumerate thunk has no selfHandle`() {
    val shim: String = shim()

    assertContains(shim, "private static unsafe IntPtr TicksAsyncEnumerate_Thunk(IntPtr* errOut)")
    assertContains(shim, "Kennel.TicksAsync()")
  }

  @Test
  fun `the module initializer passes Enumerate then Current per member in order`() {
    val shim: String = shim()
    val enumerate: Int = shim.indexOf("(&BarksAsyncEnumerate_Thunk)")
    val current: Int = shim.indexOf("(&BarksAsyncCurrent_Thunk)")

    assertTrue(
      enumerate > 0 && current > enumerate,
      "Enumerate then Current, adjacent, in the ModuleInitializer",
    )
  }

  // ---------------------------------------------------------------- the three runtime slots

  @Test
  fun `the runtime registration gains the three enumeration thunks`() {
    val runtime: String = runtimeShim()

    assertContains(runtime, "internal abstract class NugetAsyncEnumeration")
    assertContains(
      runtime,
      "MoveNextBegin_Thunk(IntPtr enumeration, IntPtr callback, IntPtr ctx, IntPtr* errOut)",
    )
    assertContains(runtime, "MoveNextEnd_Thunk(IntPtr taskHandle, IntPtr* errOut)")
    assertContains(runtime, "DisposeEnumeration_Thunk(IntPtr enumeration, int cancelled)")
  }

  // ADR-155's load-bearing guard: an exception escaping an `async void` dispose sequence
  // terminates the .NET host, which is exactly the host abort the feature forbids.
  @Test
  fun `the queued dispose sequence is a Task Run with an outer catch never async void`() {
    val runtime: String = runtimeShim()
    // The THUNK, not its ModuleInitializer reference: slicing at the first mention would leave
    // ADR-153's ReleaseCancellation_Thunk (a legitimate work-item user) inside the window.
    val dispose: String =
      runtime.substringAfter("private static void DisposeEnumeration_Thunk")

    assertContains(dispose, "Task.Run(async () =>")
    assertContains(dispose, "catch (Exception)")
    assertFalse(
      dispose.contains("UnsafeQueueUserWorkItem"),
      "an `async` lambda on a work item is an `async void` shape: an escaping exception aborts " +
          "the host, got:\n$dispose",
    )
    // Cancel, await the pending step, THEN dispose: DisposeAsync during a pending MoveNextAsync
    // throws NotSupportedException and leaks the running iterator (ADR-155 spike row a).
    assertTrue(
      dispose.indexOf("Cts.Cancel()") < dispose.indexOf("await n.Pending") &&
          dispose.indexOf("await n.Pending") < dispose.indexOf("await n.Dispose()"),
      "the dispose sequence must be cancel, await pending, dispose, got:\n$dispose",
    )
  }

  @Test
  fun `the runtime registration grows from seven slots to ten`() {
    val runtime: String = runtimeShim()

    assertContains(
      runtime,
      "IntPtr moveNextBeginPtr, IntPtr moveNextEndPtr, IntPtr disposeEnumerationPtr",
    )
    assertTrue(
      Regex("nuget_runtime_register\\(\\s*10,\\s*-?\\d+L,").containsMatchIn(runtime),
      "ADR-155: the three enumeration slots take nuget_runtime_register from 7 to 10, " +
          "got:\n$runtime",
    )
  }

  @Test
  fun `the Kotlin runtime export accepts the three new pointers and expects ten slots`() {
    val runtime: String = generateKotlinStubs(rir)
      .single { it.relativePath.endsWith("NugetRuntime.kt") }.content

    assertContains(runtime, "expectedSlots = 10,")
    assertContains(runtime, "moveNextBeginPtr: COpaquePointer?,")
    assertContains(runtime, "moveNextEndPtr: COpaquePointer?,")
    assertContains(runtime, "disposeEnumerationPtr: COpaquePointer?,")
  }

  // ---------------------------------------------------------------- contract + deferred scope

  @Test
  fun `an async enumerable member hashes differently from the same member as a Task`() {
    val task = RirClass(
      name = "Kennel",
      methods = listOf(
        RirMethod(
          name = "BarksAsync",
          returnType = RirStringType(),
          asyncKind = RirAsyncKind.TASK,
        ),
      ),
    )
    val stream = RirClass(
      name = "Kennel",
      methods = listOf(streamMethod("BarksAsync", RirStringType())),
    )

    assertNotEquals(
      contractHash(task, bridgeableRegistrables(task, emptySet()), emptyMap()),
      contractHash(stream, bridgeableRegistrables(stream, emptySet()), emptyMap()),
      "an `asyncenum:` prefix must make `Task<T> Foo()` and `IAsyncEnumerable<T> Foo()` hash " +
          "differently: same name, same parameters, same element type, completely different wire",
    )
  }

  // ROADMAP:194, unchanged for the new kind: asyncDeferredDiagnostics keys on `asyncKind != null`,
  // so these stay named skips. Green today by construction; they are the guard for the rewrite of
  // the 22 `asyncKind != null` sites, not a red row.
  @Test
  fun `an async enumerable on a deferred owner is a named skip never a half built pair`() {
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
                  methods = listOf(streamMethod("FlexesAsync", RirPrimitiveType("int"))),
                ),
                RirInterface(
                  name = "IFeedable",
                  methods = listOf(streamMethod("MealsAsync", RirStringType())),
                ),
                RirClass(
                  name = "Crate",
                  typeParameters = listOf("T"),
                  methods = listOf(streamMethod("ItemsAsync", RirStringType())),
                ),
              ),
            ),
          ),
        ),
      ),
    )

    val warnings: List<String> = diagnosticWarnings(deferred)
    listOf("FlexesAsync", "MealsAsync", "ItemsAsync").forEach { name ->
      assertTrue(
        warnings.any { it.contains(name) && it.contains("deferred scope") },
        "an async-enumerable member on a deferred owner must be skipped by name, got: $warnings",
      )
    }
    generateKotlinStubs(deferred).forEach { file ->
      listOf("flexes", "meals", "items").forEach { member ->
        assertFalse(
          file.content.contains(member),
          "${file.relativePath} bound a deferred shape",
        )
      }
    }
  }

  // A void element is not an `IAsyncEnumerable<T>` at all; if one ever reaches the generator it
  // must not produce a half-built pair.
  @Test
  fun `an async enumerable with no element type binds nothing`() {
    val bad = RirClass(
      name = "Kennel",
      methods = listOf(streamMethod("NothingAsync", RirVoidType)),
    )

    assertEquals(0, bridgeableRegistrables(bad, emptySet()).slotCount())
  }
}
