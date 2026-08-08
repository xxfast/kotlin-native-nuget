package io.github.xxfast.kotlin.native.nuget

import io.github.xxfast.kotlin.native.nuget.rir.RirAssembly
import io.github.xxfast.kotlin.native.nuget.rir.RirClass
import io.github.xxfast.kotlin.native.nuget.rir.RirDiagnostic
import io.github.xxfast.kotlin.native.nuget.rir.RirDiagnosticKind
import io.github.xxfast.kotlin.native.nuget.rir.RirEnum
import io.github.xxfast.kotlin.native.nuget.rir.RirEnumEntry
import io.github.xxfast.kotlin.native.nuget.rir.RirEnumType
import io.github.xxfast.kotlin.native.nuget.rir.RirFile
import io.github.xxfast.kotlin.native.nuget.rir.RirInterface
import io.github.xxfast.kotlin.native.nuget.rir.RirInterfaceType
import io.github.xxfast.kotlin.native.nuget.rir.RirMethod
import io.github.xxfast.kotlin.native.nuget.rir.RirNamespace
import io.github.xxfast.kotlin.native.nuget.rir.RirObjectHandleType
import io.github.xxfast.kotlin.native.nuget.rir.RirParameter
import io.github.xxfast.kotlin.native.nuget.rir.RirPrimitiveType
import io.github.xxfast.kotlin.native.nuget.rir.RirProperty
import io.github.xxfast.kotlin.native.nuget.rir.RirStringType
import io.github.xxfast.kotlin.native.nuget.rir.RirVoidType
import io.github.xxfast.kotlin.native.nuget.rir.boundHandleTypes
import io.github.xxfast.kotlin.native.nuget.rir.boundInterfaceTypes
import io.github.xxfast.kotlin.native.nuget.rir.kotlinBridgeDiagnostics
import io.github.xxfast.kotlin.native.nuget.rir.kotlinBridgePlan
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ADR-085: a Kotlin class implementing a bound C# interface, passed back to C#. Same
 * `Test.Menagerie.IFeedable` fixture ADR-070's tests use (a string return, an int getter, a string
 * parameter, a nullable settable string property) plus a deliberately out-of-vocabulary interface
 * to pin the named skip.
 */
class NugetKotlinBridgeGenerationTest {

  private val iFeedable = RirInterface(
    name = "IFeedable",
    methods = listOf(
      RirMethod(name = "Describe", returnType = RirStringType(nullable = false)),
      RirMethod(
        name = "Feed", returnType = RirVoidType,
        parameters = listOf(RirParameter("food", RirStringType(nullable = false))),
      ),
    ),
    properties = listOf(
      RirProperty(name = "Legs", type = RirPrimitiveType("int"), isReadOnly = true),
      RirProperty(name = "Nickname", type = RirStringType(nullable = true), isReadOnly = false),
    ),
  )

  // A member outside the v1 slot vocabulary: a bound-object-handle parameter.
  private val iAdopter = RirInterface(
    name = "IAdopter",
    methods = listOf(
      RirMethod(
        name = "Adopt", returnType = RirVoidType,
        parameters = listOf(
          RirParameter("pet", RirObjectHandleType(namespace = "Test.Menagerie", name = "Ferret")),
        ),
      ),
    ),
    properties = listOf(
      RirProperty(name = "Name", type = RirStringType(nullable = false), isReadOnly = true),
    ),
  )

  private fun rirOf(vararg types: RirInterface) = RirFile(
    assemblies = listOf(
      RirAssembly(
        packageId = "TestDependency",
        assemblyName = "TestDependency",
        namespaces = listOf(RirNamespace(name = "Test.Menagerie", types = types.toList())),
      ),
    ),
  )

  private val rir = rirOf(iFeedable)

  // A class with an interface-typed PARAMETER and a nullable interface-typed settable PROPERTY:
  // the two reverse call-site positions that can mint a bridge.
  private val iFeedableType = RirInterfaceType(namespace = "Test.Menagerie", name = "IFeedable")

  private val sanctuary = RirClass(
    name = "Sanctuary",
    methods = listOf(
      RirMethod(
        name = "Introduce", returnType = RirStringType(nullable = false),
        parameters = listOf(RirParameter("feedable", iFeedableType)),
      ),
    ),
    properties = listOf(
      RirProperty(
        name = "Featured", type = iFeedableType.copy(nullable = true), isReadOnly = false,
      ),
    ),
  )

  // ADR-085 follow-up fixtures: a SECOND independent bound interface (so one Kotlin class can
  // implement both — the multi-interface dispatch bug) whose enum-typed settable property is
  // declared in ANOTHER namespace, i.e. another generated Kotlin package.
  private val energyMembers = listOf(
    RirEnumEntry("Low", 0), RirEnumEntry("Medium", 1), RirEnumEntry("High", 2),
  )

  private val energyLevel = RirEnumType(namespace = "Test.Wellness", name = "EnergyLevel")

  private val iPerformer = RirInterface(
    name = "IPerformer",
    methods = listOf(RirMethod(name = "Perform", returnType = RirStringType(nullable = false))),
    properties = listOf(
      RirProperty(name = "Energy", type = energyLevel, isReadOnly = false),
    ),
  )

  // The two namespaces must alias to distinct Kotlin packages, as the real TestDependency bind
  // block does — that is what makes the enum reference cross-package at all.
  private val menagerieAliases: Map<String, Map<String, String>> = mapOf(
    "TestDependency" to mapOf(
      "Test.Menagerie" to "test.menagerie",
      "Test.Wellness" to "test.wellness",
    ),
  )

  private val rirWithPerformer = RirFile(
    assemblies = listOf(
      RirAssembly(
        packageId = "TestDependency",
        assemblyName = "TestDependency",
        namespaces = listOf(
          RirNamespace(name = "Test.Menagerie", types = listOf(iFeedable, iPerformer)),
          RirNamespace(
            name = "Test.Wellness",
            types = listOf(RirEnum(name = "EnergyLevel", entries = energyMembers)),
          ),
        ),
      ),
    ),
  )

  // ADR-087 stage 1 is ABI-neutral, so this literal must survive the wrapper. It is derived from
  // IFeedable's member shapes alone (RirBridging.kotlinBridgeContractHash) — nothing about a slot
  // BODY may move it, or every already-shipped C# shim fails the ADR-054 registration check.
  private val contractHashOfIFeedable = 2957489357822963940L

  private val rirWithSanctuary = RirFile(
    assemblies = listOf(
      RirAssembly(
        packageId = "TestDependency",
        assemblyName = "TestDependency",
        namespaces = listOf(
          RirNamespace(name = "Test.Menagerie", types = listOf(iFeedable, sanctuary)),
        ),
      ),
    ),
  )

  // ------------------------------------------------------------------
  // Kotlin side (NugetGenerateBindingsTask)
  // ------------------------------------------------------------------

  @Test
  fun `IFeedable emits one staticCFunction slot per registered member`() {
    val file: GeneratedFile = generateKotlinStubs(rir)
      .single { it.relativePath.endsWith("/IFeedableBindings.kt") }

    assertContains(
      file.content,
      "private fun iFeedableDescribeSlot(ctx: COpaquePointer?): COpaquePointer? = try {\n" +
          "  nugetKotlinString(ctx!!.asStableRef<IFeedable>().get().describe())\n",
    )
    assertContains(
      file.content,
      "private fun iFeedableFeedSlot(ctx: COpaquePointer?, a0: COpaquePointer?): Unit = try {\n" +
          "  ctx!!.asStableRef<IFeedable>().get()" +
          ".feed(requireNotNull(a0).reinterpret<ByteVar>().toKString())\n",
    )
    assertContains(
      file.content,
      "private fun iFeedableLegsGetterSlot(ctx: COpaquePointer?): Int = try {\n" +
          "  ctx!!.asStableRef<IFeedable>().get().legs\n",
    )
    assertContains(file.content, "private fun iFeedableNicknameGetterSlot(")
    assertContains(
      file.content,
      "private fun iFeedableNicknameSetterSlot(ctx: COpaquePointer?, a0: COpaquePointer?) {",
    )
  }

  @Test
  fun `IFeedable emits a mint entry point that hands the slots and a StableRef ctx to C#`() {
    val file: GeneratedFile = generateKotlinStubs(rir)
      .single { it.relativePath.endsWith("/IFeedableBindings.kt") }

    assertContains(
      file.content, "internal fun mintIFeedableBridge(impl: IFeedable): COpaquePointer",
    )
    assertContains(file.content, "val ctx: COpaquePointer = StableRef.create(impl).asCPointer()")
    // Slot order is the registration order, and ctx is always last.
    assertContains(
      file.content,
      """
        |    fn.invoke(
        |    staticCFunction(::iFeedableDescribeSlot),
        |    staticCFunction(::iFeedableFeedSlot),
        |    staticCFunction(::iFeedableLegsGetterSlot),
        |    staticCFunction(::iFeedableNicknameGetterSlot),
        |    staticCFunction(::iFeedableNicknameSetterSlot),
        |    ctx,
        |    ),
      """.trimMargin(),
    )
  }

  @Test
  fun `nugetHandle mints a bridge instead of erroring, through the generated dispatcher`() {
    val files: List<GeneratedFile> = generateKotlinStubs(rir)
    val runtime: GeneratedFile = files.single { it.relativePath.endsWith("/NugetRuntime.kt") }
    val dispatcher: GeneratedFile =
      files.single { it.relativePath.endsWith("/NugetKotlinBridges.kt") }

    assertContains(
      runtime.content, "?: nugetMintBridge(this, interfaceName)?.let { NugetObjectHandle(it) }",
    )
    assertContains(
      dispatcher.content,
      "interfaceName == \"Test.Menagerie.IFeedable\" && value is testdependency.IFeedable -> " +
          "testdependency.mintIFeedableBridge(value)",
    )
    // The error(...) survives for a value implementing no bridgeable bound interface.
    assertContains(runtime.content, "is a Kotlin implementation of ")
  }

  @Test
  fun `the shared Kotlin exports for release and string free are emitted once`() {
    val runtime: GeneratedFile = generateKotlinStubs(rir)
      .single { it.relativePath.endsWith("/NugetRuntime.kt") }

    assertContains(runtime.content, "@CName(\"nuget_kotlin_release\")")
    assertContains(runtime.content, "ctx.asStableRef<Any>().dispose()")
    assertContains(runtime.content, "@CName(\"nuget_kotlin_string_free\")")
    assertContains(runtime.content, "nativeHeap.free(ptr)")
  }

  @Test
  fun `an interface-typed return resolves a minted bridge back to the original Kotlin object`() {
    val file: GeneratedFile = generateKotlinStubs(rir)
      .single { it.relativePath.endsWith("/IFeedableBindings.kt") }

    assertContains(file.content, "internal fun nugetIFeedableValue(ptr: COpaquePointer): IFeedable")
    assertContains(file.content, "IFeedableBindings.bridgeTokenFn?.invoke(ptr)")
    assertContains(file.content, "return token.asStableRef<Any>().get() as IFeedable")
    assertContains(file.content, "return IFeedableHandle(ptr)")
  }

  @Test
  fun `the register export gains the two bridge slots and a distinct contract hash`() {
    val planned: GeneratedFile = generateKotlinStubs(rir)
      .single { it.relativePath.endsWith("/IFeedableBindings.kt") }

    assertContains(planned.content, "createBridgePtr: COpaquePointer?,")
    assertContains(planned.content, "bridgeTokenPtr: COpaquePointer?,")
    assertContains(
      planned.content,
      "IFeedableBindings.createBridgeFn = requireNotNull(createBridgePtr).reinterpret()",
    )
    assertContains(planned.content, "expectedSlots = 7,")

    val hashOf: (String) -> String = { content ->
      content.substringAfter("expectedHash = ").substringBefore("L,")
    }
    val unplanned: GeneratedFile = generateKotlinStubs(rirOf(iAdopter))
      .single { it.relativePath.endsWith("/IAdopterBindings.kt") }
    assertTrue(hashOf(planned.content) != hashOf(unplanned.content))
  }

  @Test
  fun `a call site that can mint frees the transfer handle after the native call`() {
    val runtime: GeneratedFile = generateKotlinStubs(rirWithSanctuary)
      .single { it.relativePath.endsWith("/NugetRuntime.kt") }

    // The scope owns ONLY minted handles; a generated wrapper's own handle belongs to its Cleaner.
    assertContains(runtime.content, "internal class NugetTransferScope {")
    assertContains(runtime.content, "val owned: Boolean = value !is NugetHandleOwner")
    assertContains(runtime.content, "if (owned) minted.add(handle)")
    assertContains(runtime.content, "minted.forEach { it.free() }")
    assertContains(
      runtime.content,
      "internal inline fun <R> nugetTransferScope(block: NugetTransferScope.() -> R): R {",
    )
    assertContains(runtime.content, "scope.releaseMinted()")
  }

  @Test
  fun `an interface-typed parameter and setter wrap their invoke in the transfer scope`() {
    val file: GeneratedFile = generateKotlinStubs(rirWithSanctuary)
      .single { it.relativePath.endsWith("/Sanctuary.kt") }

    // Method parameter position, with a string return (so both scopes nest, transfer outermost).
    assertContains(
      file.content,
      "val resultPtr = nugetTransferScope { fn.invoke(handle.require(\"Sanctuary\"), " +
          "handleOf(feedable, \"Test.Menagerie.IFeedable\")) }",
    )
    // Property-setter position, nullable: the same scope, through handleOfOrNull.
    assertContains(
      file.content,
      "nugetTransferScope { fn.invoke(handle.require(\"Sanctuary\"), " +
          "handleOfOrNull(value, \"Test.Menagerie.IFeedable\")) }",
    )
    assertContains(
      file.content, "import io.github.xxfast.kotlin.native.nuget.internal.nugetTransferScope",
    )
  }

  @Test
  fun `the release export counts releases so a GC-timed release is observable`() {
    val runtime: GeneratedFile = generateKotlinStubs(rir)
      .single { it.relativePath.endsWith("/NugetRuntime.kt") }

    assertContains(runtime.content, "internal fun nugetKotlinReleaseCount(): Int")
    assertContains(
      runtime.content, "if (kotlinReleaseCount.compareAndSet(current, current + 1)) break",
    )
  }

  // ------------------------------------------------------------------
  // Cross-package enum imports (the three interface files)
  // ------------------------------------------------------------------

  @Test
  fun `an interface's cross-package enum member is imported in all three generated files`() {
    val files: List<GeneratedFile> =
      generateKotlinStubs(rirWithPerformer, namespaceAliases = menagerieAliases)
    val expected = "import test.wellness.EnergyLevel"

    val iface: GeneratedFile = files.single { it.relativePath.endsWith("/IPerformer.kt") }
    val handle: GeneratedFile = files.single { it.relativePath.endsWith("/IPerformerHandle.kt") }
    val bindings: GeneratedFile =
      files.single { it.relativePath.endsWith("/IPerformerBindings.kt") }

    // The pure stub declares `var energy: EnergyLevel`...
    assertContains(iface.content, expected)
    assertContains(iface.content, "var energy: EnergyLevel")
    // ...the handle wrapper implements it...
    assertContains(handle.content, expected)
    // ...and the slot body names the enum for the INBOUND (setter) crossing.
    assertContains(bindings.content, expected)
    assertContains(bindings.content, "nugetEnumEntry(EnergyLevel.entries, a0, \"EnergyLevel\")")
  }

  @Test
  fun `a same-package enum member needs no import`() {
    val samePkg: RirFile = RirFile(
      assemblies = listOf(
        RirAssembly(
          packageId = "TestDependency",
          assemblyName = "TestDependency",
          namespaces = listOf(
            RirNamespace(
              name = "Test.Menagerie",
              types = listOf(
                iPerformer.copy(
                  properties = listOf(
                    RirProperty(
                      name = "Energy",
                      type = energyLevel.copy(namespace = "Test.Menagerie"),
                      isReadOnly = false,
                    ),
                  ),
                ),
                RirEnum(name = "EnergyLevel", entries = energyMembers),
              ),
            ),
          ),
        ),
      ),
    )
    val bindings: GeneratedFile = generateKotlinStubs(samePkg)
      .single { it.relativePath.endsWith("/IPerformerBindings.kt") }

    assertFalse(bindings.content.contains("import testdependency.EnergyLevel"))
    assertFalse(bindings.content.contains("import test.wellness.EnergyLevel"))
    assertContains(bindings.content, "nugetEnumEntry(EnergyLevel.entries, a0, \"EnergyLevel\")")
  }

  // ------------------------------------------------------------------
  // Target-keyed bridge dispatch
  // ------------------------------------------------------------------

  @Test
  fun `nugetMintBridge dispatches on the target interface, not the first matching arm`() {
    val dispatcher: GeneratedFile =
      generateKotlinStubs(rirWithPerformer, namespaceAliases = menagerieAliases)
        .single { it.relativePath.endsWith("/NugetKotlinBridges.kt") }

    assertContains(
      dispatcher.content,
      "internal fun nugetMintBridge(value: Any, interfaceName: String): COpaquePointer?",
    )
    // Both arms are present and BOTH are gated on the crossing position's qualified C# name, so a
    // Kotlin class implementing both mints the bridge the position asked for.
    assertContains(
      dispatcher.content,
      "interfaceName == \"Test.Menagerie.IFeedable\" && value is test.menagerie.IFeedable -> " +
          "test.menagerie.mintIFeedableBridge(value)",
    )
    assertContains(
      dispatcher.content,
      "interfaceName == \"Test.Menagerie.IPerformer\" && value is test.menagerie.IPerformer -> " +
          "test.menagerie.mintIPerformerBridge(value)",
    )
  }

  @Test
  fun `the transfer scope keeps the readable simple name in its messages`() {
    val runtime: GeneratedFile = generateKotlinStubs(rirWithSanctuary)
      .single { it.relativePath.endsWith("/NugetRuntime.kt") }

    assertContains(runtime.content, "return handle.require(interfaceName.substringAfterLast('.'))")
    assertContains(runtime.content, "\${interfaceName.substringAfterLast('.')}")
    assertContains(runtime.content, "is a Kotlin implementation of ")
  }

  // ------------------------------------------------------------------
  // ADR-087 stage 1: named per-slot fast-fail
  // ------------------------------------------------------------------

  @Test
  fun `every slot body is wrapped in a fast-fail that names the C# member and rethrows`() {
    val file: GeneratedFile = generateKotlinStubs(rir)
      .single { it.relativePath.endsWith("/IFeedableBindings.kt") }

    // Method, getter and setter alike, named by their C# names (the audience is the C# consumer).
    listOf("IFeedable.Describe", "IFeedable.Feed", "IFeedable.Legs", "IFeedable.Nickname")
      .forEach { member ->
        assertContains(
          file.content,
          "    \"[nuget] Kotlin implementation of `$member` threw \" +\n" +
              "      \"\${t::class.qualifiedName ?: \"an exception\"}: \${t.message}. \" +\n" +
              "      \"A Kotlin-implemented C# interface member must not throw " +
              "(ADR-087 stage 1); \" +\n" +
              "      \"the process will now terminate.\"",
        )
      }
    // Termination semantics are unchanged: the catch rethrows.
    assertContains(file.content, "} catch (t: Throwable) {")
    assertContains(file.content, "  throw t\n}")
    // The setter's block body wraps too (its body is a statement, not an expression).
    assertContains(
      file.content,
      "private fun iFeedableNicknameSetterSlot(ctx: COpaquePointer?, a0: COpaquePointer?) {\n" +
          "  try {\n" +
          "    ctx!!.asStableRef<IFeedable>().get().nickname = " +
          "a0?.reinterpret<ByteVar>()?.toKString()\n",
    )
  }

  @Test
  fun `the fast-fail wrapper is ABI-neutral - slot count and contract hash do not move`() {
    val bindings: GeneratedFile = generateKotlinStubs(rir)
      .single { it.relativePath.endsWith("/IFeedableBindings.kt") }

    // Pinned literals: the wrapper is a body-only change, so a stale shim stays compatible. If
    // either of these moves, the ADR-054 registration check will reject every shipped consumer.
    assertContains(bindings.content, "expectedSlots = 7,")
    assertContains(bindings.content, "expectedHash = ${contractHashOfIFeedable}L,")
  }

  // ------------------------------------------------------------------
  // C# side (NugetGenerateShimsTask)
  // ------------------------------------------------------------------

  @Test
  fun `IFeedable emits a bridge class implementing the interface over Kotlin function pointers`() {
    val file: GeneratedFile = generateCSharpShims(rir, "TestLibraryNative")
      .single { it.relativePath == "IFeedableRegistration.cs" }

    assertContains(
      file.content,
      "private sealed unsafe class IFeedableBridge : IFeedable, INugetKotlinBridge",
    )
    assertContains(file.content, "private readonly KotlinRefHandle _ctx;")
    assertContains(
      file.content, "private readonly delegate* unmanaged[Cdecl]<IntPtr, IntPtr> _describe;",
    )
    assertContains(
      file.content,
      "private readonly delegate* unmanaged[Cdecl]<IntPtr, IntPtr, void> _feed;",
    )
    assertContains(
      file.content, "private readonly delegate* unmanaged[Cdecl]<IntPtr, int> _legsGetter;",
    )
    assertContains(file.content, "public IntPtr NugetToken => _ctx.DangerousGetHandle();")
    // String return: read Kotlin's UTF-8 buffer, then hand it straight back to Kotlin to free.
    assertContains(file.content, "return Marshal.PtrToStringUTF8(resultPtr)!;")
    assertContains(
      file.content,
      "if (resultPtr != IntPtr.Zero) NugetKotlinNative.nuget_kotlin_string_free(resultPtr);",
    )
    // String parameter: C# allocates and frees its own buffer around the synchronous call.
    assertContains(file.content, "IntPtr foodPtr = Marshal.StringToCoTaskMemUTF8(food);")
    assertContains(file.content, "Marshal.FreeCoTaskMem(foodPtr);")
    // A settable property renders as ONE property with both accessors.
    assertContains(file.content, "public string? Nickname")
    assertContains(file.content, "_nicknameSetter(_ctx.DangerousGetHandle(), valuePtr);")
  }

  @Test
  fun `the factory and identity-token thunks ride the existing registration slot table`() {
    val file: GeneratedFile = generateCSharpShims(rir, "TestLibraryNative")
      .single { it.relativePath == "IFeedableRegistration.cs" }

    assertContains(file.content, "IntPtr createBridgePtr, IntPtr bridgeTokenPtr")
    assertContains(
      file.content,
      "private static unsafe IntPtr CreateIFeedableBridge(IntPtr describe, IntPtr feed, " +
          "IntPtr legsGetter, IntPtr nicknameGetter, IntPtr nicknameSetter, IntPtr ctx) =>",
    )
    assertContains(
      file.content,
      "(IntPtr)(delegate* unmanaged[Cdecl]<IntPtr, IntPtr, IntPtr, IntPtr, IntPtr, IntPtr, " +
          "IntPtr>)(&CreateIFeedableBridge)",
    )
    assertContains(
      file.content,
      "(IntPtr)(delegate* unmanaged[Cdecl]<IntPtr, IntPtr>)(&IFeedableKotlinToken)",
    )
    assertContains(
      file.content,
      "GCHandle.FromIntPtr(handle).Target is INugetKotlinBridge bridge",
    )
    assertContains(file.content, "7,")
  }

  @Test
  fun `the shared runtime shim carries the marker interface and the releasing SafeHandle`() {
    val file: GeneratedFile = generateCSharpShims(rir, "TestLibraryNative")
      .single { it.relativePath == "NugetRuntimeRegistration.cs" }

    assertContains(file.content, "internal interface INugetKotlinBridge")
    assertContains(file.content, "internal sealed class KotlinRefHandle : SafeHandle")
    assertContains(file.content, "NugetKotlinNative.nuget_kotlin_release(handle);")
    assertContains(file.content, "EntryPoint = \"nuget_kotlin_string_free\")]")
  }

  // ------------------------------------------------------------------
  // Detection rule
  // ------------------------------------------------------------------

  @Test
  fun `an out-of-vocabulary member yields skipped_kotlin_bridge and no bridge at all`() {
    val file: RirFile = rirOf(iAdopter)
    val diagnostics: List<RirDiagnostic> = kotlinBridgeDiagnostics(
      iAdopter, boundHandleTypes(file), boundInterfaceTypes(file),
    )

    assertEquals(1, diagnostics.size)
    assertEquals(RirDiagnosticKind.SKIPPED_KOTLIN_BRIDGE, diagnostics.single().kind)
    assertEquals("Adopt", diagnostics.single().memberName)
    assertNull(kotlinBridgePlan(iAdopter, boundHandleTypes(file), boundInterfaceTypes(file)))

    val bindings: GeneratedFile = generateKotlinStubs(file)
      .single { it.relativePath.endsWith("/IAdopterBindings.kt") }
    assertFalse(bindings.content.contains("mintIAdopterBridge"))
    assertFalse(bindings.content.contains("createBridgeFn"))
    // The plain wrapper construction stays, so an interface-typed return still works.
    assertContains(
      bindings.content,
      "internal fun nugetIAdopterValue(ptr: COpaquePointer): IAdopter =\n  IAdopterHandle(ptr)",
    )

    val shim: GeneratedFile = generateCSharpShims(file, "TestLibraryNative")
      .single { it.relativePath == "IAdopterRegistration.cs" }
    assertFalse(shim.content.contains("CreateIAdopterBridge"))
    assertFalse(shim.content.contains("IAdopterBridge"))
  }

  @Test
  fun `interface inheritance is deferred with a named skip, not a broken bridge`() {
    val iTagged = RirInterface(
      name = "ITagged",
      properties = listOf(
        RirProperty(name = "Tag", type = RirStringType(nullable = false), isReadOnly = true),
      ),
      interfaces = listOf("Test.Menagerie.IFeedable"),
    )
    val file: RirFile = rirOf(iFeedable, iTagged)
    val diagnostics: List<RirDiagnostic> = kotlinBridgeDiagnostics(
      iTagged, boundHandleTypes(file), boundInterfaceTypes(file),
    )

    assertEquals(RirDiagnosticKind.SKIPPED_KOTLIN_BRIDGE, diagnostics.single().kind)
    assertContains(diagnostics.single().reason, "interface inheritance is deferred")
    assertNull(kotlinBridgePlan(iTagged, boundHandleTypes(file), boundInterfaceTypes(file)))
    // ...while the base interface itself is still bridgeable.
    assertTrue(
      kotlinBridgePlan(iFeedable, boundHandleTypes(file), boundInterfaceTypes(file)) != null,
    )
  }
}
