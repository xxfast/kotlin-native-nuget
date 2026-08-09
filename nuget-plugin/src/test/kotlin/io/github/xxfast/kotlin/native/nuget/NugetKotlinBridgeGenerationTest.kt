package io.github.xxfast.kotlin.native.nuget

import io.github.xxfast.kotlin.native.nuget.rir.RirAssembly
import io.github.xxfast.kotlin.native.nuget.rir.RirClass
import io.github.xxfast.kotlin.native.nuget.rir.RirDiagnostic
import io.github.xxfast.kotlin.native.nuget.rir.RirDiagnosticKind
import io.github.xxfast.kotlin.native.nuget.rir.RirEnum
import io.github.xxfast.kotlin.native.nuget.rir.RirEnumEntry
import io.github.xxfast.kotlin.native.nuget.rir.RirEnumType
import io.github.xxfast.kotlin.native.nuget.rir.RirFile
import io.github.xxfast.kotlin.native.nuget.rir.RirGenericInstanceType
import io.github.xxfast.kotlin.native.nuget.rir.RirInterface
import io.github.xxfast.kotlin.native.nuget.rir.RirInterfaceType
import io.github.xxfast.kotlin.native.nuget.rir.RirMethod
import io.github.xxfast.kotlin.native.nuget.rir.RirNamespace
import io.github.xxfast.kotlin.native.nuget.rir.RirObjectHandleType
import io.github.xxfast.kotlin.native.nuget.rir.RirParameter
import io.github.xxfast.kotlin.native.nuget.rir.RirPrimitiveType
import io.github.xxfast.kotlin.native.nuget.rir.RirProperty
import io.github.xxfast.kotlin.native.nuget.rir.RirRegistrable
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
import kotlin.test.assertNotEquals
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
  // IFeedable's member shapes plus its flattened factory signature
  // (RirBridging.kotlinBridgeContractHash) — nothing about a slot BODY may move it, or every
  // already-shipped C# shim fails the ADR-054 registration check.
  // Moved twice, deliberately, in this wave: once when the flattened factory signature entered
  // the hash, once when ADR-087 stage 2's errOut param moved every slot onto the uniform
  // kotlin_bridge_v2 tag. Both are coordinated regenerations of both halves.
  private val contractHashOfIFeedable = -7783318298045765409L

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
      "private fun iFeedableDescribeSlot(ctx: COpaquePointer?, errOut: CPointer<COpaquePointerVar>?): " +
          "COpaquePointer? = try {\n" +
          "  nugetKotlinString(ctx!!.asStableRef<IFeedable>().get().describe())\n",
    )
    assertContains(
      file.content,
      "private fun iFeedableFeedSlot(ctx: COpaquePointer?, a0: COpaquePointer?, errOut: CPointer<COpaquePointerVar>?): " +
          "Unit = try {\n" +
          "  ctx!!.asStableRef<IFeedable>().get()" +
          ".feed(requireNotNull(a0).reinterpret<ByteVar>().toKString())\n",
    )
    assertContains(
      file.content,
      "private fun iFeedableLegsGetterSlot(ctx: COpaquePointer?, errOut: CPointer<COpaquePointerVar>?): Int = try {\n" +
          "  ctx!!.asStableRef<IFeedable>().get().legs\n",
    )
    assertContains(file.content, "private fun iFeedableNicknameGetterSlot(")
    assertContains(
      file.content,
      "private fun iFeedableNicknameSetterSlot(ctx: COpaquePointer?, a0: COpaquePointer?, " +
          "errOut: CPointer<COpaquePointerVar>?) {",
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
  fun `every slot writes the forward error envelope through its trailing out-param`() {
    val file: GeneratedFile = generateKotlinStubs(rir)
      .single { it.relativePath.endsWith("/IFeedableBindings.kt") }

    // One catch per slot, and it never rethrows: the throw becomes a catchable .NET exception in
    // the C# bridge member instead of terminating the host (stage 1's posture).
    assertEquals(5, file.content.split("} catch (t: Throwable) {").size - 1)
    assertFalse(file.content.contains("throw t"))
    assertFalse(file.content.contains("the process will now terminate"))
    assertContains(file.content, "errOut?.pointed?.value = nugetKotlinError(t)")
    assertContains(
      file.content,
      "import io.github.xxfast.kotlin.native.nuget.internal.nugetKotlinError",
    )
    // The setter's block body wraps too (its body is a statement, not an expression).
    assertContains(
      file.content,
      "private fun iFeedableNicknameSetterSlot(ctx: COpaquePointer?, a0: COpaquePointer?, " +
          "errOut: CPointer<COpaquePointerVar>?) {\n" +
          "  try {\n" +
          "    ctx!!.asStableRef<IFeedable>().get().nickname = " +
          "a0?.reinterpret<ByteVar>()?.toKString()\n",
    )
  }

  @Test
  fun `the dummy return after an envelope write is pinned per wire type`() {
    // ADR-087 ledger: dummy-return safety was Inferred for everything but Int/pointer. C# never
    // reads these (it checks errOut first), so the contract is only that each type-checks and
    // allocates nothing — a string slot in particular returns a null pointer, which the C# side
    // must not hand to nuget_kotlin_string_free.
    val wide = RirInterface(
      name = "IWide",
      methods = listOf(
        RirMethod(name = "S", returnType = RirStringType(nullable = false)),
        RirMethod(name = "B", returnType = RirPrimitiveType("bool")),
        RirMethod(name = "By", returnType = RirPrimitiveType("byte")),
        RirMethod(name = "Sh", returnType = RirPrimitiveType("short")),
        RirMethod(name = "I", returnType = RirPrimitiveType("int")),
        RirMethod(name = "L", returnType = RirPrimitiveType("long")),
        RirMethod(name = "F", returnType = RirPrimitiveType("float")),
        RirMethod(name = "D", returnType = RirPrimitiveType("double")),
        RirMethod(name = "C", returnType = RirPrimitiveType("char")),
        RirMethod(name = "V", returnType = RirVoidType),
      ),
    )
    val content: String = generateKotlinStubs(rirOf(wide))
      .single { it.relativePath.endsWith("/IWideBindings.kt") }
      .content

    val dummyOf: (String) -> String = { slot ->
      content.substringAfter("private fun iWide${slot}Slot(")
        .substringAfter("errOut?.pointed?.value = nugetKotlinError(t)\n")
        .substringBefore("}")
        .trim()
    }
    assertEquals("null", dummyOf("S"))
    assertEquals("false", dummyOf("B"))
    assertEquals("0u", dummyOf("By"))
    assertEquals("0", dummyOf("Sh"))
    assertEquals("0", dummyOf("I"))
    assertEquals("0L", dummyOf("L"))
    assertEquals("0.0f", dummyOf("F"))
    assertEquals("0.0", dummyOf("D"))
    assertEquals("0u", dummyOf("C"))
    // A Unit slot returns nothing at all: the catch ends with the envelope write.
    assertContains(
      content,
      "private fun iWideVSlot(ctx: COpaquePointer?, errOut: CPointer<COpaquePointerVar>?): " +
          "Unit = try {\n" +
          "  ctx!!.asStableRef<IWide>().get().v()\n" +
          "} catch (t: Throwable) {\n" +
          "  errOut?.pointed?.value = nugetKotlinError(t)\n" +
          "}",
    )
  }

  @Test
  fun `the C# bridge member checks the error slot before touching the result`() {
    val file: GeneratedFile =
      generateCSharpShims(rir, "TestLibraryNative", errorNamespace = "TestLibrary")
        .single { it.relativePath == "IFeedableRegistration.cs" }

    // The envelope READ is reverse-owned (the reverse Kotlin cannot see the forward NugetError
    // across the source-set boundary), but the THROWN types are the forward public ones, so a
    // consumer catches ONE exception family in both directions.
    assertContains(
      file.content,
      "                IntPtr err = IntPtr.Zero;\n" +
          "                IntPtr resultPtr = _describe(_ctx.DangerousGetHandle(), &err);\n" +
          "                if (err != IntPtr.Zero) throw NugetKotlinErrors.Build(err);",
    )
    // A void slot checks after the call; a getter checks before converting its scalar.
    assertContains(
      file.content,
      "                    _feed(_ctx.DangerousGetHandle(), foodPtr, &err);\n" +
          "                    if (err != IntPtr.Zero) throw NugetKotlinErrors.Build(err);",
    )
    assertContains(
      file.content,
      "                    int result = _legsGetter(_ctx.DangerousGetHandle(), &err);\n" +
          "                    if (err != IntPtr.Zero) throw NugetKotlinErrors.Build(err);\n" +
          "                    return result;",
    )
    // Every slot's function-pointer type carries the trailing IntPtr* before its return type.
    assertContains(
      file.content,
      "private readonly delegate* unmanaged[Cdecl]<IntPtr, IntPtr*, IntPtr> _describe;",
    )
    assertContains(
      file.content,
      "private readonly delegate* unmanaged[Cdecl]<IntPtr, IntPtr, IntPtr*, void> _feed;",
    )
    assertContains(
      file.content,
      "private readonly delegate* unmanaged[Cdecl]<IntPtr, IntPtr*, int> _legsGetter;",
    )
  }

  @Test
  fun `the envelope moves every contract hash onto one uniform v2 tag`() {
    // The errOut param changes every slot's ARITY, which slotCount cannot see, so the tag bump is
    // what makes a pre-stage-2 shim fail the ADR-054 check loudly. Both generators must agree.
    val kotlinHash: String = generateKotlinStubs(rir)
      .single { it.relativePath.endsWith("/IFeedableBindings.kt") }
      .content.substringAfter("expectedHash = ").substringBefore("L,")
    val csharpHash: String = generateCSharpShims(rir, "TestLibraryNative")
      .single { it.relativePath == "IFeedableRegistration.cs" }
      .content.substringAfter("                    7,\n                    ").substringBefore("L,")

    assertEquals(kotlinHash, csharpHash)
    // ...and it is NOT the pre-envelope value the round-1 flattening test pinned.
    assertNotEquals("-2696048506129608359", kotlinHash)
    assertEquals("$contractHashOfIFeedable", kotlinHash)
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
      file.content, "private readonly delegate* unmanaged[Cdecl]<IntPtr, IntPtr*, IntPtr> _describe;",
    )
    assertContains(
      file.content,
      "private readonly delegate* unmanaged[Cdecl]<IntPtr, IntPtr, IntPtr*, void> _feed;",
    )
    assertContains(
      file.content, "private readonly delegate* unmanaged[Cdecl]<IntPtr, IntPtr*, int> _legsGetter;",
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
    assertContains(file.content, "_nicknameSetter(_ctx.DangerousGetHandle(), valuePtr, &err);")
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

  // ------------------------------------------------------------------
  // Derived-interface flattening (Phase 13 Wave 2, item 1)
  // ------------------------------------------------------------------

  private val iTagged = RirInterface(
    name = "ITagged",
    properties = listOf(
      RirProperty(name = "Tag", type = RirStringType(nullable = false), isReadOnly = true),
    ),
    interfaces = listOf("Test.Menagerie.IFeedable"),
  )

  private val rirWithTagged: RirFile = rirOf(iFeedable, iTagged)

  private fun slotNames(iface: RirInterface, file: RirFile): List<String> =
    requireNotNull(kotlinBridgePlan(iface, boundHandleTypes(file), boundInterfaceTypes(file)))
      .slots
      .map { slot ->
        when (slot) {
          is RirRegistrable.Method -> slot.method.name
          is RirRegistrable.PropertyGetter -> "${slot.property.name}#get"
          is RirRegistrable.PropertySetter -> "${slot.property.name}#set"
          is RirRegistrable.Ctor -> error("no ctor")
        }
      }

  @Test
  fun `a derived interface flattens its base's slots ahead of its own`() {
    assertEquals(
      listOf("Describe", "Feed", "Legs#get", "Nickname#get", "Nickname#set", "Tag#get"),
      slotNames(iTagged, rirWithTagged),
    )
    // The base's own plan is unchanged by the derived interface existing.
    assertEquals(
      listOf("Describe", "Feed", "Legs#get", "Nickname#get", "Nickname#set"),
      slotNames(iFeedable, rirWithTagged),
    )
  }

  @Test
  fun `a diamond visits the shared base once, depth-first in declared order`() {
    // IDiamond : ILeft, IRight — and both derive from IFeedable. IFeedable's slots must appear
    // exactly once, before either branch's own.
    val iLeft = RirInterface(
      name = "ILeft",
      methods = listOf(RirMethod(name = "Left", returnType = RirVoidType)),
      interfaces = listOf("Test.Menagerie.IFeedable"),
    )
    val iRight = RirInterface(
      name = "IRight",
      methods = listOf(RirMethod(name = "Right", returnType = RirVoidType)),
      interfaces = listOf("Test.Menagerie.IFeedable"),
    )
    val iDiamond = RirInterface(
      name = "IDiamond",
      methods = listOf(RirMethod(name = "Both", returnType = RirVoidType)),
      interfaces = listOf("Test.Menagerie.ILeft", "Test.Menagerie.IRight"),
    )
    val file: RirFile = rirOf(iFeedable, iLeft, iRight, iDiamond)

    assertEquals(
      listOf(
        "Describe", "Feed", "Legs#get", "Nickname#get", "Nickname#set",
        "Left", "Right", "Both",
      ),
      slotNames(iDiamond, file),
    )
  }

  @Test
  fun `a base member outside the vocabulary blocks the derived interface's bridge too`() {
    val iTaggedAdopter = iTagged.copy(interfaces = listOf("Test.Menagerie.IAdopter"))
    val file: RirFile = rirOf(iAdopter, iTaggedAdopter)
    val diagnostics: List<RirDiagnostic> = kotlinBridgeDiagnostics(
      iTaggedAdopter, boundHandleTypes(file), boundInterfaceTypes(file),
    )

    assertEquals(RirDiagnosticKind.SKIPPED_KOTLIN_BRIDGE, diagnostics.single().kind)
    assertEquals("ITagged", diagnostics.single().typeName)
    assertEquals("IAdopter.Adopt", diagnostics.single().memberSignature)
    assertContains(diagnostics.single().reason, "inherited from IAdopter")
    assertNull(kotlinBridgePlan(iTaggedAdopter, boundHandleTypes(file), boundInterfaceTypes(file)))
  }

  @Test
  fun `the derived contract hash moves when the BASE interface gains a member`() {
    val bindingsOf: (RirFile) -> String = { file ->
      generateKotlinStubs(file).single { it.relativePath.endsWith("/ITaggedBindings.kt") }.content
    }
    val before: String = bindingsOf(rirWithTagged)
    val grownBase: RirInterface = iFeedable.copy(
      methods = iFeedable.methods + RirMethod(name = "Groom", returnType = RirVoidType),
    )
    val after: String = bindingsOf(rirOf(grownBase, iTagged))

    val hashOf: (String) -> String = { it.substringAfter("expectedHash = ").substringBefore(",") }
    assertNotEquals(hashOf(before), hashOf(after))
    // ITagged's OWN registration slots did not move — only the flattened factory did, which is
    // exactly the drift a memberHash-only contract cannot see.
    assertContains(before, "expectedSlots = 3,")
    assertContains(after, "expectedSlots = 3,")
  }

  @Test
  fun `the Kotlin factory hands base slots then own slots to C#`() {
    val file: GeneratedFile = generateKotlinStubs(rirWithTagged)
      .single { it.relativePath.endsWith("/ITaggedBindings.kt") }

    assertContains(file.content, "private fun iTaggedDescribeSlot(ctx: COpaquePointer?, errOut:")
    assertContains(file.content, "private fun iTaggedTagGetterSlot(ctx: COpaquePointer?, errOut:")
    assertContains(
      file.content,
      "  fn.invoke(\n" +
          "    staticCFunction(::iTaggedDescribeSlot),\n" +
          "    staticCFunction(::iTaggedFeedSlot),\n" +
          "    staticCFunction(::iTaggedLegsGetterSlot),\n" +
          "    staticCFunction(::iTaggedNicknameGetterSlot),\n" +
          "    staticCFunction(::iTaggedNicknameSetterSlot),\n" +
          "    staticCFunction(::iTaggedTagGetterSlot),\n" +
          "    ctx,\n",
    )
    // An inherited String member needs the slot bodies' marshalling imports even though none of
    // ITagged's OWN registration slots is a String parameter.
    assertContains(file.content, "import kotlinx.cinterop.toKString")
    // The dispatcher arm keys on the derived interface's own qualified name.
    val dispatch: GeneratedFile = generateKotlinStubs(rirWithTagged)
      .single { it.relativePath.endsWith("/NugetKotlinBridges.kt") }
    assertContains(
      dispatch.content,
      "interfaceName == \"Test.Menagerie.ITagged\" && value is testdependency.ITagged -> " +
          "testdependency.mintITaggedBridge(value)",
    )
  }

  @Test
  fun `the C# bridge derives from the derived interface and implements the inherited members`() {
    val file: GeneratedFile = generateCSharpShims(rirWithTagged, "TestLibraryNative")
      .single { it.relativePath == "ITaggedRegistration.cs" }

    assertContains(file.content, "private sealed unsafe class ITaggedBridge : ITagged")
    assertContains(file.content, "public string Describe()")
    assertContains(file.content, "public string Tag")
    assertContains(
      file.content,
      "private static unsafe IntPtr CreateITaggedBridge(IntPtr describe, IntPtr feed, " +
          "IntPtr legsGetter, IntPtr nicknameGetter, IntPtr nicknameSetter, IntPtr tagGetter, " +
          "IntPtr ctx) =>",
    )
    assertContains(
      file.content,
      "(IntPtr)(delegate* unmanaged[Cdecl]<IntPtr, IntPtr, IntPtr, IntPtr, IntPtr, IntPtr, " +
          "IntPtr, IntPtr>)(&CreateITaggedBridge)",
    )
    // ITagged registers ONE own member slot plus the two bridge slots.
    assertContains(file.content, "                    3,")
  }

  // ------------------------------------------------------------------
  // Object- and interface-typed slots (ADR-086, Phase 13 Wave 2 item 2)
  // ------------------------------------------------------------------

  private val ferretType = RirObjectHandleType(namespace = "Test.Menagerie", name = "Ferret")

  private val ferret = RirClass(
    name = "Ferret",
    methods = listOf(RirMethod(name = "Brush", returnType = RirVoidType)),
  )

  private val iKeeper = RirInterface(
    name = "IKeeper",
    methods = listOf(
      RirMethod(
        name = "Groom", returnType = ferretType,
        parameters = listOf(RirParameter("pet", ferretType)),
      ),
      RirMethod(
        name = "Pair", returnType = iFeedableType,
        parameters = listOf(RirParameter("other", iFeedableType)),
      ),
    ),
    properties = listOf(
      RirProperty(name = "Favorite", type = ferretType.copy(nullable = true), isReadOnly = false),
    ),
  )

  private val rirWithKeeper: RirFile = RirFile(
    assemblies = listOf(
      RirAssembly(
        packageId = "TestDependency",
        assemblyName = "TestDependency",
        namespaces = listOf(
          RirNamespace(name = "Test.Menagerie", types = listOf(iFeedable, ferret, iKeeper)),
        ),
      ),
    ),
  )

  @Test
  fun `bound-object and bound-interface members plan a bridge with a dup thunk`() {
    val plan = requireNotNull(
      kotlinBridgePlan(
        iKeeper, boundHandleTypes(rirWithKeeper), boundInterfaceTypes(rirWithKeeper),
      ),
    )

    assertEquals(4, plan.slots.size)
    assertTrue(plan.needsDupHandle, "Groom/Pair/Favorite all hand a handle back OUT")
    assertEquals(
      emptyList(),
      kotlinBridgeDiagnostics(
        iKeeper, boundHandleTypes(rirWithKeeper), boundInterfaceTypes(rirWithKeeper),
      ),
    )
  }

  @Test
  fun `an UNBOUND object type keeps the named skip`() {
    val unbound = RirInterface(
      name = "IStray",
      methods = listOf(
        RirMethod(
          name = "Adopt", returnType = RirVoidType,
          parameters = listOf(
            RirParameter("pet", RirObjectHandleType("Test.Elsewhere", "Alpaca")),
          ),
        ),
      ),
    )
    val file: RirFile = rirOf(unbound)

    // ADR-070 admissibility drops an unbound handle parameter BEFORE it ever becomes a
    // registrable, so the named skip comes from that layer; the widened slot vocabulary is
    // deliberately still bound-only underneath it (a planner that admitted an unbound type would
    // emit a slot body naming a wrapper class this build never generates).
    val diagnostics: List<RirDiagnostic> =
      kotlinBridgeDiagnostics(unbound, boundHandleTypes(file), boundInterfaceTypes(file))
    assertContains(diagnostics.single().reason, "not bridgeable in the C#->Kotlin direction")
    assertNull(kotlinBridgePlan(unbound, boundHandleTypes(file), boundInterfaceTypes(file)))
  }

  @Test
  fun `a collection-typed member STILL skips, with the widened hint`() {
    val iShelter = RirInterface(
      name = "IShelter",
      methods = listOf(
        RirMethod(
          name = "Census",
          returnType = RirGenericInstanceType(
            namespace = "System.Collections.Generic",
            name = "List`1",
            typeArguments = listOf(RirPrimitiveType("int")),
          ),
        ),
      ),
    )
    val file: RirFile = rirOf(iShelter)

    val diagnostic: RirDiagnostic =
      kotlinBridgeDiagnostics(iShelter, boundHandleTypes(file), boundInterfaceTypes(file)).single()
    assertEquals("Census", diagnostic.memberName)
    assertContains(diagnostic.reason, "not bridgeable in the C#->Kotlin direction")
    assertContains(diagnostic.hint, "Collections, structs, generic instantiations and Task")
    assertNull(kotlinBridgePlan(iShelter, boundHandleTypes(file), boundInterfaceTypes(file)))
  }

  @Test
  fun `the Kotlin slot bodies wrap inbound handles and dup outbound ones`() {
    val file: GeneratedFile = generateKotlinStubs(rirWithKeeper)
      .single { it.relativePath.endsWith("/IKeeperBindings.kt") }

    // Bound-object parameter: transfer to Kotlin, wrapped by the ordinary cleaner-owned wrapper.
    // Bound-object return: dup'd into a fresh transfer handle through this interface's thunk.
    assertContains(
      file.content,
      "  nugetHandleOut(ctx!!.asStableRef<IKeeper>().get().groom(Ferret(requireNotNull(a0))), " +
          "\"Test.Menagerie.Ferret\", IKeeperBindings.dupHandleFn)",
    )
    // Bound-interface parameter: the shipped token probe returns the ORIGINAL Kotlin object.
    assertContains(
      file.content,
      "  nugetHandleOut(ctx!!.asStableRef<IKeeper>().get()" +
          ".pair(nugetIFeedableValue(requireNotNull(a0))), " +
          "\"Test.Menagerie.IFeedable\", IKeeperBindings.dupHandleFn)",
    )
    // Nullable bound-object property, both directions.
    assertContains(
      file.content,
      "  nugetHandleOut(ctx!!.asStableRef<IKeeper>().get().favorite, " +
          "\"Test.Menagerie.Ferret\", IKeeperBindings.dupHandleFn)",
    )
    assertContains(
      file.content,
      "    ctx!!.asStableRef<IKeeper>().get().favorite = a0?.let { Ferret(it) }",
    )
    assertContains(file.content, "import io.github.xxfast.kotlin.native.nuget.internal.nugetHandleOut")
    // The dup thunk is a registered slot like any other, and the register export takes it LAST.
    assertContains(
      file.content,
      "  internal var dupHandleFn:\n" +
          "    CPointer<CFunction<(COpaquePointer?) -> COpaquePointer?>>? = null",
    )
    assertContains(file.content, "  dupHandlePtr: COpaquePointer?,")
    assertContains(
      file.content,
      "  IKeeperBindings.dupHandleFn = requireNotNull(dupHandlePtr).reinterpret()",
    )
    // 4 member slots + createBridge + bridgeToken + dupHandle.
    assertContains(file.content, "expectedSlots = 7,")
  }

  @Test
  fun `the C# bridge allocates argument handles and frees resolved return handles`() {
    val file: GeneratedFile = generateCSharpShims(rirWithKeeper, "TestLibraryNative")
      .single { it.relativePath == "IKeeperRegistration.cs" }

    assertContains(
      file.content,
      "                IntPtr resultPtr = _groom(_ctx.DangerousGetHandle(), " +
          "GCHandle.ToIntPtr(GCHandle.Alloc(pet)), &err);",
    )
    assertContains(
      file.content,
      "                GCHandle resultHandle = GCHandle.FromIntPtr(resultPtr);\n" +
          "                try\n" +
          "                {\n" +
          "                    return (Ferret)resultHandle.Target!;\n" +
          "                }\n" +
          "                finally\n" +
          "                {\n" +
          "                    resultHandle.Free();\n" +
          "                }",
    )
    // Nullable getter: IntPtr.Zero rides null, before any GCHandle is resolved.
    assertContains(file.content, "                    if (resultPtr == IntPtr.Zero) return null;")
    // Nullable setter argument.
    assertContains(
      file.content,
      "_favoriteSetter(_ctx.DangerousGetHandle(), " +
          "value is null ? IntPtr.Zero : GCHandle.ToIntPtr(GCHandle.Alloc(value)), &err);",
    )
    // The dup thunk itself, in the shape the scratchpad spike proved.
    assertContains(
      file.content,
      "        private static IntPtr IKeeperDupHandle(IntPtr handle) =>\n" +
          "            handle == IntPtr.Zero\n" +
          "                ? IntPtr.Zero\n" +
          "                : GCHandle.ToIntPtr(GCHandle.Alloc(GCHandle.FromIntPtr(handle).Target));",
    )
    assertContains(
      file.content,
      "(IntPtr)(delegate* unmanaged[Cdecl]<IntPtr, IntPtr>)(&IKeeperDupHandle)",
    )
    assertContains(
      file.content,
      "IntPtr createBridgePtr, IntPtr bridgeTokenPtr, IntPtr dupHandlePtr);",
    )
    assertContains(file.content, "                    7,")
  }

  @Test
  fun `an interface with no handle-backed OUT position registers no dup thunk`() {
    // IFeedable is all strings/ints: ADR-085's two extra slots, v1 tag, no dup.
    val plan = requireNotNull(kotlinBridgePlan(iFeedable, boundHandleTypes(rir), boundInterfaceTypes(rir)))
    assertFalse(plan.needsDupHandle)
    val file: GeneratedFile = generateKotlinStubs(rir)
      .single { it.relativePath.endsWith("/IFeedableBindings.kt") }
    assertFalse(file.content.contains("dupHandleFn"))
  }

  @Test
  fun `the runtime shim owns the envelope reader and throws the forward exception types`() {
    val file: GeneratedFile =
      generateCSharpShims(rir, "TestLibraryNative", errorNamespace = "TestLibrary")
        .single { it.relativePath == "NugetRuntimeRegistration.cs" }

    assertContains(file.content, "internal static class NugetKotlinErrors")
    assertContains(file.content, "EntryPoint = \"nuget_kotlin_error_type\")]")
    assertContains(file.content, "EntryPoint = \"nuget_kotlin_error_cause_count\")]")
    assertContains(file.content, "EntryPoint = \"nuget_kotlin_error_free\")]")
    // ADR-029's map, qualified onto the FORWARD public hierarchy: one catch for both directions.
    assertContains(
      file.content,
      "\"kotlin.IllegalStateException\" => new " +
          "TestLibrary.KotlinInvalidOperationException(kotlinType, message, stackTrace, inner),",
    )
    assertContains(
      file.content,
      "_ => new TestLibrary.KotlinException(kotlinType, message, stackTrace, inner)",
    )
  }
}
