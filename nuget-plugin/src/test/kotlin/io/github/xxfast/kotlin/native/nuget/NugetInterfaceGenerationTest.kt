package io.github.xxfast.kotlin.native.nuget

import io.github.xxfast.kotlin.native.nuget.rir.RirAssembly
import io.github.xxfast.kotlin.native.nuget.rir.RirClass
import io.github.xxfast.kotlin.native.nuget.rir.RirFile
import io.github.xxfast.kotlin.native.nuget.rir.RirInterface
import io.github.xxfast.kotlin.native.nuget.rir.RirInterfaceType
import io.github.xxfast.kotlin.native.nuget.rir.RirMethod
import io.github.xxfast.kotlin.native.nuget.rir.RirNamespace
import io.github.xxfast.kotlin.native.nuget.rir.RirParameter
import io.github.xxfast.kotlin.native.nuget.rir.RirPrimitiveType
import io.github.xxfast.kotlin.native.nuget.rir.RirProperty
import io.github.xxfast.kotlin.native.nuget.rir.RirStringType
import io.github.xxfast.kotlin.native.nuget.rir.RirVoidType
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-070 walking skeleton: `Test.Menagerie.IFeedable`/`ITagged` (interface inheritance) and
 * `Ferret`/`Sanctuary` (a class that implements IFeedable identically, and a class whose members
 * are interface-typed at RETURN, PARAMETER and PROPERTY positions) — mirrors the real fixture
 * (`TestDependency/Menagerie.cs`) at a scale small enough for a fast unit-test loop.
 */
class NugetInterfaceGenerationTest {

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

  private val iTagged = RirInterface(
    name = "ITagged",
    methods = emptyList(),
    properties = listOf(
      RirProperty(name = "Tag", type = RirStringType(nullable = false), isReadOnly = true),
    ),
    interfaces = listOf("Test.Menagerie.IFeedable"),
  )

  private val iFeedableType = RirInterfaceType(namespace = "Test.Menagerie", name = "IFeedable")
  private val iTaggedType = RirInterfaceType(namespace = "Test.Menagerie", name = "ITagged")

  private val ferret = RirClass(
    name = "Ferret",
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
    interfaces = listOf("Test.Menagerie.IFeedable"),
  )

  private val sanctuary = RirClass(
    name = "Sanctuary",
    methods = listOf(
      RirMethod(name = "Star", returnType = iFeedableType),
      RirMethod(
        name = "Introduce", returnType = RirStringType(nullable = false),
        parameters = listOf(RirParameter("feedable", iFeedableType)),
      ),
      RirMethod(name = "Flagship", returnType = iTaggedType),
    ),
    properties = listOf(
      RirProperty(
        name = "Featured", type = iFeedableType.copy(nullable = true), isReadOnly = false,
      ),
    ),
  )

  private val rir = RirFile(
    assemblies = listOf(
      RirAssembly(
        packageId = "TestDependency",
        assemblyName = "TestDependency",
        namespaces = listOf(
          RirNamespace(
            name = "Test.Menagerie",
            types = listOf(iFeedable, iTagged, ferret, sanctuary),
          ),
        ),
      ),
    ),
  )

  // ------------------------------------------------------------------
  // Kotlin side (NugetGenerateBindingsTask)
  // ------------------------------------------------------------------

  @Test
  fun `IFeedable emits a pure interface with no handle member`() {
    val files: List<GeneratedFile> = generateKotlinStubs(rir)
    val file: GeneratedFile = files.single { it.relativePath.endsWith("/IFeedable.kt") }

    // ADR-088: `public`, so a consumer's own public forward API can name it.
    assertContains(file.content, "\ninterface IFeedable {")
    assertContains(file.content, "fun describe(): String")
    assertContains(file.content, "fun feed(food: String)")
    assertContains(file.content, "val legs: Int")
    assertContains(file.content, "var nickname: String?")
    assertFalse(
      file.content.contains("val handle") || file.content.contains("handle:"),
      "the generated interface must stay pure (Decision 4) — no handle member",
    )
  }

  @Test
  fun `ITagged declares IFeedable as a Kotlin supertype and only its own member`() {
    val files: List<GeneratedFile> = generateKotlinStubs(rir)
    val file: GeneratedFile = files.single { it.relativePath.endsWith("/ITagged.kt") }

    assertContains(file.content, "\ninterface ITagged : IFeedable {")
    assertContains(file.content, "val tag: String")
    assertFalse(file.content.contains("fun describe"), "inherited members are not redeclared")
  }

  @Test
  fun `IFeedableHandle implements NugetHandleOwner and dispatches through IFeedableBindings`() {
    val files: List<GeneratedFile> = generateKotlinStubs(rir)
    val file: GeneratedFile = files.single { it.relativePath.endsWith("/IFeedableHandle.kt") }

    assertContains(
      file.content,
      "internal class IFeedableHandle internal constructor(handle: COpaquePointer) : " +
          "IFeedable, NugetHandleOwner, AutoCloseable {",
    )
    assertContains(
      file.content, "override val handle: NugetObjectHandle = NugetObjectHandle(handle)",
    )
    assertContains(file.content, "override fun describe(): String {")
    assertContains(file.content, "IFeedableBindings.describeFn")
    assertContains(file.content, "override var nickname: String?")
  }

  @Test
  fun `ITaggedHandle dispatches its own member through ITaggedBindings and inherited members through IFeedableBindings`() {
    val files: List<GeneratedFile> = generateKotlinStubs(rir)
    val file: GeneratedFile = files.single { it.relativePath.endsWith("/ITaggedHandle.kt") }

    assertContains(file.content, "ITaggedBindings.tagGetterFn")
    assertContains(file.content, "IFeedableBindings.describeFn")
    assertContains(file.content, "IFeedableBindings.legsGetterFn")
  }

  @Test
  fun `IFeedable register export has no constructor slot and expects 5 member slots`() {
    val files: List<GeneratedFile> = generateKotlinStubs(rir)
    val file: GeneratedFile = files.single { it.relativePath.endsWith("/IFeedableBindings.kt") }

    // describe, feed, legsGetter, nicknameGetter, nicknameSetter = 5 member slots, plus ADR-085's
    // two bridge slots (createBridge, bridgeToken) because IFeedable is Kotlin-implementable, plus
    // ADR-088's dup thunk, which every plannable interface now registers (a forward return
    // position can hand any of them back).
    assertContains(file.content, "expectedSlots = 8,")
    assertFalse(file.content.contains("ctor"))
  }

  @Test
  fun `Ferret declares the IFeedable supertype with override members`() {
    val files: List<GeneratedFile> = generateKotlinStubs(rir)
    val file: GeneratedFile = files.single { it.relativePath.endsWith("/Ferret.kt") }

    assertContains(
      file.content,
      "internal class Ferret internal constructor(handle: COpaquePointer) : " +
          "IFeedable, NugetHandleOwner, AutoCloseable {",
    )
    assertContains(file.content, "override fun describe(): String {")
    assertContains(file.content, "override var nickname: String?")
  }

  @Test
  fun `Sanctuary star wraps the interface-typed return in IFeedableHandle, never Ferret`() {
    val files: List<GeneratedFile> = generateKotlinStubs(rir)
    val file: GeneratedFile = files.single { it.relativePath.endsWith("/Sanctuary.kt") }

    assertContains(file.content, "fun star(): IFeedable {")
    assertContains(file.content, "nugetIFeedableValue(requireNotNull(ptr)")
  }

  @Test
  fun `Sanctuary introduce lowers an interface-typed parameter through the transfer scope`() {
    val files: List<GeneratedFile> = generateKotlinStubs(rir)
    val file: GeneratedFile = files.single { it.relativePath.endsWith("/Sanctuary.kt") }

    assertContains(file.content, "fun introduce(feedable: IFeedable): String {")
    // ADR-085: the scope's handleOf(...) unwraps a generated wrapper exactly as nugetHandle() did,
    // and additionally owns (and frees) a handle minted for a Kotlin implementation.
    // The name is the QUALIFIED C# one: it keys nugetMintBridge's target dispatch.
    assertContains(file.content, "handleOf(feedable, \"Test.Menagerie.IFeedable\")")
  }

  @Test
  fun `Sanctuary featured is a nullable interface-typed var wrapped in IFeedableHandle`() {
    val files: List<GeneratedFile> = generateKotlinStubs(rir)
    val file: GeneratedFile = files.single { it.relativePath.endsWith("/Sanctuary.kt") }

    assertContains(file.content, "var featured: IFeedable?")
    assertContains(file.content, "ptr?.let { nugetIFeedableValue(it) }")
  }

  @Test
  fun `Sanctuary flagship returns ITagged wrapped in ITaggedHandle`() {
    val files: List<GeneratedFile> = generateKotlinStubs(rir)
    val file: GeneratedFile = files.single { it.relativePath.endsWith("/Sanctuary.kt") }

    assertContains(file.content, "fun flagship(): ITagged {")
    assertContains(file.content, "nugetITaggedValue(requireNotNull(ptr)")
  }

  @Test
  fun `NugetRuntime carries the NugetHandleOwner marker and nugetHandle extension`() {
    val files: List<GeneratedFile> = generateKotlinStubs(rir)
    val file: GeneratedFile = files.single { it.relativePath.endsWith("/NugetRuntime.kt") }

    assertContains(file.content, "internal interface NugetHandleOwner {")
    assertContains(
      file.content, "internal fun Any.nugetHandle(interfaceName: String): NugetObjectHandle",
    )
  }

  // ------------------------------------------------------------------
  // C# side (NugetGenerateShimsTask)
  // ------------------------------------------------------------------

  @Test
  fun `IFeedableRegistration thunks cast the receiver to the interface, never a concrete class`() {
    val files: List<GeneratedFile> = generateCSharpShims(rir, nativeLibraryName = "sample")
    val registration: GeneratedFile = files.single { it.relativePath == "IFeedableRegistration.cs" }

    assertContains(
      registration.content,
      "IFeedable receiver = (IFeedable)GCHandle.FromIntPtr(selfHandle).Target!;",
    )
    assertContains(registration.content, "internal static class IFeedableRegistration")
  }

  @Test
  fun `ITaggedRegistration has exactly one slot for its own Tag member`() {
    val files: List<GeneratedFile> = generateCSharpShims(rir, nativeLibraryName = "sample")
    val registration: GeneratedFile = files.single { it.relativePath == "ITaggedRegistration.cs" }

    assertContains(registration.content, "Tag_Get_Thunk")
    assertFalse(
      registration.content.contains("Describe_Thunk"),
      "ITagged's own export never re-registers IFeedable's members",
    )
    // Scoped to the REGISTER export deliberately: since ADR-085 Wave 2 this same file also carries
    // the ITaggedBridge class, which DOES implement IFeedable's Describe (flattened slots), so a
    // whole-file "no Describe anywhere" assertion no longer says what it means to say.
    assertContains(
      registration.content,
      "private static extern void nuget_test_menagerie_i_tagged_register(int slotCount, " +
          "long contractHash, IntPtr tagGetterPtr, IntPtr createBridgePtr, " +
          "IntPtr bridgeTokenPtr, IntPtr dupHandlePtr);",
    )
  }

  @Test
  fun `SanctuaryRegistration passes an interface-typed parameter and return as plain IntPtr`() {
    val files: List<GeneratedFile> = generateCSharpShims(rir, nativeLibraryName = "sample")
    val registration: GeneratedFile = files.single { it.relativePath == "SanctuaryRegistration.cs" }

    assertContains(
      registration.content,
      "receiver.Introduce((IFeedable)GCHandle.FromIntPtr(feedableHandle).Target!);",
    )
    assertContains(registration.content, "IFeedable? result = receiver.Star();")
  }

  @Test
  fun `Kotlin and C# generators agree on the register contract hash for IFeedable`() {
    val kotlinFiles: List<GeneratedFile> = generateKotlinStubs(rir)
    val csharpFiles: List<GeneratedFile> = generateCSharpShims(rir, nativeLibraryName = "sample")

    val kotlinBindings: String =
      kotlinFiles.single { it.relativePath.endsWith("/IFeedableBindings.kt") }.content
    val csharpRegistration: String =
      csharpFiles.single { it.relativePath == "IFeedableRegistration.cs" }.content

    val kotlinHash: String = Regex("expectedHash = (-?\\d+)L").find(kotlinBindings)!!.groupValues[1]
    val csharpHash: String = Regex("(-?\\d+)L,\\n\\s+\\(IntPtr\\)\\(delegate\\*")
      .find(csharpRegistration)!!.groupValues[1]

    assertEquals(kotlinHash, csharpHash)
  }

  @Test
  fun `Interfaces are counted in expectedRegistrations for the N of M message`() {
    val files: List<GeneratedFile> = generateKotlinStubs(rir)
    val registry: GeneratedFile = files.single { it.relativePath.endsWith("/NugetRegistry.kt") }

    assertContains(registry.content, "\"Test.Menagerie.IFeedable\"")
    assertContains(registry.content, "\"Test.Menagerie.ITagged\"")
  }

  @Test
  fun `no ABI mismatch between Kotlin cfnType and C# csAbiType for interface members`() {
    val kotlinFiles: List<GeneratedFile> = generateKotlinStubs(rir)
    val bindings: String =
      kotlinFiles.single { it.relativePath.endsWith("/IFeedableBindings.kt") }.content
    // A string-returning, no-arg instance method — receiver is COpaquePointer? on the Kotlin
    // side.
    assertTrue(
      bindings.contains(
        "internal var describeFn: CPointer<CFunction<(COpaquePointer?) -> COpaquePointer?>>? " +
            "= null",
      ),
    )
  }
}
