package io.github.xxfast.kotlin.native.nuget

import io.github.xxfast.kotlin.native.nuget.rir.RirAssembly
import io.github.xxfast.kotlin.native.nuget.rir.RirConstructor
import io.github.xxfast.kotlin.native.nuget.rir.RirEnum
import io.github.xxfast.kotlin.native.nuget.rir.RirEnumEntry
import io.github.xxfast.kotlin.native.nuget.rir.RirEnumType
import io.github.xxfast.kotlin.native.nuget.rir.RirFile
import io.github.xxfast.kotlin.native.nuget.rir.RirMethod
import io.github.xxfast.kotlin.native.nuget.rir.RirNamespace
import io.github.xxfast.kotlin.native.nuget.rir.RirParameter
import io.github.xxfast.kotlin.native.nuget.rir.RirPrimitiveType
import io.github.xxfast.kotlin.native.nuget.rir.RirProperty
import io.github.xxfast.kotlin.native.nuget.rir.RirStringType
import io.github.xxfast.kotlin.native.nuget.rir.RirStruct
import io.github.xxfast.kotlin.native.nuget.rir.RirStructComponent
import io.github.xxfast.kotlin.native.nuget.rir.RirStructType
import io.github.xxfast.kotlin.native.nuget.rir.RirVoidType
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-056 deferred scope: struct methods + computed properties (ADR-014 reconstruct-on-call).
 *
 * Hand-built reverse-IR mirrors `TestDependency/Geometry.cs` Point and
 * `TestDependency/Profile.cs` Profile members.
 *
 * Bind: public non-void instance methods, get-only computed props (not component readNames),
 * static methods → companion. Skip: Equals/GetHashCode/ToString/Deconstruct, operators, setters,
 * void instance methods, component auto-properties. Wire: N component args first, then ordinary
 * args; struct returns via out-pointers. Slot order: alternate ctors → static methods → instance
 * methods → computed property getters. Members force a Bindings file even without alternate ctors.
 */
class NugetStructMemberGenerationTest {

  private val pointType: RirStructType =
    RirStructType(namespace = "Test.Structs", name = "Point")

  private val pointStateCtor: RirConstructor = RirConstructor(
    parameters = listOf(
      RirParameter(name = "x", type = RirPrimitiveType("int")),
      RirParameter(name = "y", type = RirPrimitiveType("int")),
    ),
    isState = true,
  )

  private val pointIntCtor: RirConstructor = RirConstructor(
    parameters = listOf(RirParameter(name = "value", type = RirPrimitiveType("int"))),
  )

  // Mirror Geometry.cs Point members: Magnitude, Offset, Format, Origin.
  private val point: RirStruct = RirStruct(
    name = "Point",
    components = listOf(
      RirStructComponent(name = "x", readName = "X", type = RirPrimitiveType("int")),
      RirStructComponent(name = "y", readName = "Y", type = RirPrimitiveType("int")),
    ),
    constructors = listOf(pointStateCtor, pointIntCtor),
    methods = listOf(
      RirMethod(
        name = "Offset",
        isStatic = false,
        returnType = pointType,
        parameters = listOf(
          RirParameter(name = "dx", type = RirPrimitiveType("int")),
          RirParameter(name = "dy", type = RirPrimitiveType("int")),
        ),
      ),
      RirMethod(
        name = "Format",
        isStatic = false,
        returnType = RirStringType(),
        parameters = emptyList(),
      ),
      RirMethod(
        name = "Origin",
        isStatic = true,
        returnType = pointType,
        parameters = emptyList(),
      ),
      // Skip: void-returning instance methods are out of scope.
      RirMethod(
        name = "Clear",
        isStatic = false,
        returnType = RirVoidType,
        parameters = emptyList(),
      ),
      // Skip: Equals / GetHashCode / ToString / Deconstruct.
      RirMethod(
        name = "Equals",
        isStatic = false,
        returnType = RirPrimitiveType("bool"),
        parameters = listOf(RirParameter(name = "other", type = pointType)),
      ),
      RirMethod(
        name = "ToString",
        isStatic = false,
        returnType = RirStringType(),
        parameters = emptyList(),
      ),
    ),
    properties = listOf(
      // Component auto-properties must not re-bind as computed getters.
      RirProperty(name = "X", type = RirPrimitiveType("int"), isReadOnly = true, isStatic = false),
      RirProperty(name = "Y", type = RirPrimitiveType("int"), isReadOnly = true, isStatic = false),
      // Bind: get-only computed property whose name is not a component readName.
      RirProperty(
        name = "Magnitude",
        type = RirPrimitiveType("int"),
        isReadOnly = true,
        isStatic = false,
      ),
      // Skip: property setters.
      RirProperty(
        name = "Scratch",
        type = RirPrimitiveType("int"),
        isReadOnly = false,
        isStatic = false,
      ),
    ),
  )

  private val pointRir: RirFile = RirFile(
    assemblies = listOf(
      RirAssembly(
        packageId = "TestDependency",
        assemblyName = "TestDependency",
        namespaces = listOf(
          RirNamespace(name = "Test.Structs", types = listOf(point)),
        ),
      ),
    ),
  )

  // Members only (no alternate ctors): must still force Bindings + Registration.
  private val vectorType: RirStructType =
    RirStructType(namespace = "Test.Structs", name = "Vector")
  private val vector: RirStruct = RirStruct(
    name = "Vector",
    components = listOf(
      RirStructComponent(name = "x", readName = "X", type = RirPrimitiveType("int")),
      RirStructComponent(name = "y", readName = "Y", type = RirPrimitiveType("int")),
    ),
    constructors = listOf(
      RirConstructor(
        parameters = listOf(
          RirParameter(name = "x", type = RirPrimitiveType("int")),
          RirParameter(name = "y", type = RirPrimitiveType("int")),
        ),
        isState = true,
      ),
    ),
    methods = listOf(
      RirMethod(
        name = "Negate",
        isStatic = false,
        returnType = vectorType,
        parameters = emptyList(),
      ),
    ),
  )
  private val vectorRir: RirFile = RirFile(
    assemblies = listOf(
      RirAssembly(
        packageId = "TestDependency",
        assemblyName = "TestDependency",
        namespaces = listOf(
          RirNamespace(name = "Test.Structs", types = listOf(vector)),
        ),
      ),
    ),
  )

  private val catMood: RirEnum = RirEnum(
    name = "CatMood",
    entries = listOf(
      RirEnumEntry("Calm", 0),
      RirEnumEntry("Playful", 1),
      RirEnumEntry("Sleepy", 2),
    ),
  )
  private val catMoodType: RirEnumType =
    RirEnumType(namespace = "Test.Enums", name = "CatMood")
  private val profileType: RirStructType =
    RirStructType(namespace = "Test.Structs", name = "Profile")
  private val profile: RirStruct = RirStruct(
    name = "Profile",
    components = listOf(
      RirStructComponent(name = "tag", readName = "Tag", type = RirStringType()),
      RirStructComponent(name = "active", readName = "Active", type = RirPrimitiveType("bool")),
      RirStructComponent(name = "grade", readName = "Grade", type = RirPrimitiveType("char")),
      RirStructComponent(name = "mood", readName = "Mood", type = catMoodType),
    ),
    constructors = listOf(
      RirConstructor(
        parameters = listOf(
          RirParameter(name = "tag", type = RirStringType()),
          RirParameter(name = "active", type = RirPrimitiveType("bool")),
          RirParameter(name = "grade", type = RirPrimitiveType("char")),
          RirParameter(name = "mood", type = catMoodType),
        ),
        isState = true,
      ),
    ),
    methods = listOf(
      RirMethod(
        name = "WithMood",
        isStatic = false,
        returnType = profileType,
        parameters = listOf(RirParameter(name = "mood", type = catMoodType)),
      ),
      RirMethod(
        name = "Resting",
        isStatic = true,
        returnType = profileType,
        parameters = listOf(RirParameter(name = "tag", type = RirStringType())),
      ),
    ),
    properties = listOf(
      RirProperty(name = "Label", type = RirStringType(), isReadOnly = true, isStatic = false),
      RirProperty(
        name = "IsPlayful",
        type = RirPrimitiveType("bool"),
        isReadOnly = true,
        isStatic = false,
      ),
    ),
  )
  private val profileRir: RirFile = RirFile(
    assemblies = listOf(
      RirAssembly(
        packageId = "TestDependency",
        assemblyName = "TestDependency",
        namespaces = listOf(
          RirNamespace(name = "Test.Enums", types = listOf(catMood)),
          RirNamespace(name = "Test.Structs", types = listOf(profile)),
        ),
      ),
    ),
  )
  private val profileNamespaceAliases: Map<String, Map<String, String>> = mapOf(
    "TestDependency" to mapOf(
      "Test.Enums" to "test.enums",
      "Test.Structs" to "test.structs",
    ),
  )

  // ------------------------------------------------------------------
  // Kotlin stubs
  // ------------------------------------------------------------------

  @Test
  fun `Point data class exposes computed magnitude via get and keeps components as primary vals`() {
    val files: List<GeneratedFile> = generateKotlinStubs(pointRir)
    val pointFile: String = files.single { it.relativePath.endsWith("/Point.kt") }.content

    assertContains(pointFile, "internal data class Point(")
    assertContains(pointFile, "val x: Int")
    assertContains(pointFile, "val y: Int")
    // Computed property: bridge-backed get(), not a stored primary-ctor val.
    assertContains(pointFile, "val magnitude: Int")
    assertContains(pointFile, "get()")
    assertContains(pointFile, "PointBindings.magnitudeGetterFn")
    // Receiver components expand as leading invoke args (reconstruct-on-call).
    assertContains(pointFile, "fn.invoke(x, y)")
    // Component auto-properties must not reappear as bridge-backed getters.
    assertFalse(pointFile.contains("val x: Int\n  get()"))
    assertFalse(pointFile.contains("PointBindings.xGetterFn"))
    assertFalse(pointFile.contains("PointBindings.yGetterFn"))
  }

  @Test
  fun `Point instance methods expand receiver components then ordinary args`() {
    val files: List<GeneratedFile> = generateKotlinStubs(pointRir)
    val pointFile: String = files.single { it.relativePath.endsWith("/Point.kt") }.content

    assertContains(pointFile, "fun offset(dx: Int, dy: Int): Point = memScoped {")
    assertContains(pointFile, "val outX = alloc<IntVar>()")
    assertContains(pointFile, "val outY = alloc<IntVar>()")
    assertContains(pointFile, "fn.invoke(x, y, dx, dy, outX.ptr, outY.ptr)")
    assertContains(pointFile, "Point(outX.value, outY.value)")
    assertContains(pointFile, "PointBindings.offsetFn")

    assertContains(pointFile, "fun format(): String")
    assertContains(pointFile, "PointBindings.formatFn")
    // Format has no ordinary args: receiver components only.
    assertContains(pointFile, "fn.invoke(x, y)")
  }

  @Test
  fun `Point static Origin lands in companion object without receiver components`() {
    val files: List<GeneratedFile> = generateKotlinStubs(pointRir)
    val pointFile: String = files.single { it.relativePath.endsWith("/Point.kt") }.content

    assertContains(pointFile, "companion object {")
    assertContains(pointFile, "fun origin(): Point = memScoped {")
    assertContains(pointFile, "PointBindings.originFn")
    // Static: no leading x,y. Only out-pointers for the struct return.
    val originBody: String = pointFile.substringAfter("fun origin(): Point = memScoped {")
      .substringBefore("\n  }")
    assertContains(originBody, "fn.invoke(outX.ptr, outY.ptr)")
    assertFalse(
      originBody.contains("fn.invoke(x, y,"),
      "Origin must not prepend receiver components",
    )
  }

  @Test
  fun `Point skips void instance methods Equals ToString and setters`() {
    val files: List<GeneratedFile> = generateKotlinStubs(pointRir)
    val pointFile: String = files.single { it.relativePath.endsWith("/Point.kt") }.content
    val bindings: String = files.single { it.relativePath.endsWith("/PointBindings.kt") }.content

    assertFalse(pointFile.contains("fun clear(") || pointFile.contains("fun clear()"))
    assertFalse(pointFile.contains("fun equals("))
    assertFalse(pointFile.contains("fun toString("))
    assertFalse(pointFile.contains("var scratch"))
    assertFalse(bindings.contains("clearFn"))
    assertFalse(bindings.contains("equalsFn"))
    assertFalse(bindings.contains("toStringFn"))
    assertFalse(bindings.contains("scratch"))
  }

  @Test
  fun `Point Bindings register covers ctors statics methods getters in order`() {
    val files: List<GeneratedFile> = generateKotlinStubs(pointRir)
    val bindings: String = files.single { it.relativePath.endsWith("/PointBindings.kt") }.content

    // One alternate ctor + Origin + Offset + Format + Magnitude = 5 slots (state ctor free).
    assertContains(bindings, "expectedSlots = 5,")
    assertContains(bindings, "ctor")
    assertContains(bindings, "originFn")
    assertContains(bindings, "offsetFn")
    assertContains(bindings, "formatFn")
    assertContains(bindings, "magnitudeGetterFn")

    // Slot order: alternate ctors → static methods → instance methods → computed getters.
    assertInOrder(
      bindings,
      listOf("ctor", "originFn", "formatFn", "offsetFn", "magnitudeGetterFn"),
    )
  }

  @Test
  fun `members alone force Bindings even without alternate constructors`() {
    val files: List<GeneratedFile> = generateKotlinStubs(vectorRir)

    assertTrue(
      files.any { it.relativePath.endsWith("/VectorBindings.kt") },
      "a struct with bridgeable members and no alternate ctors still needs VectorBindings.kt",
    )
    val vectorFile: String = files.single { it.relativePath.endsWith("/Vector.kt") }.content
    assertContains(vectorFile, "fun negate(): Vector = memScoped {")
    assertContains(vectorFile, "fn.invoke(x, y, outX.ptr, outY.ptr)")
    val bindings: String = files.single { it.relativePath.endsWith("/VectorBindings.kt") }.content
    assertContains(bindings, "expectedSlots = 1,")
    assertContains(bindings, "negateFn")
  }

  @Test
  fun `Profile computed properties and methods use full component conversion vocabulary`() {
    val files: List<GeneratedFile> =
      generateKotlinStubs(profileRir, namespaceAliases = profileNamespaceAliases)
    val profileFile: String = files.single { it.relativePath.endsWith("/Profile.kt") }.content

    assertContains(profileFile, "import test.enums.CatMood")
    assertContains(profileFile, "val label: String")
    assertContains(profileFile, "ProfileBindings.labelGetterFn")
    // string return from reconstructed receiver: components first with their conversions.
    assertContains(
      profileFile,
      "fn.invoke(tag.cstr.ptr, active, grade.code.toUShort(), mood.ordinal)",
    )
    assertContains(profileFile, "freeManagedString(")

    assertContains(profileFile, "val isPlayful: Boolean")
    assertContains(profileFile, "ProfileBindings.isPlayfulGetterFn")

    assertContains(profileFile, "fun withMood(mood: CatMood): Profile = memScoped {")
    assertContains(profileFile, "mood.ordinal")
    assertContains(profileFile, "ProfileBindings.withMoodFn")

    assertContains(profileFile, "companion object {")
    assertContains(profileFile, "fun resting(tag: String): Profile = memScoped {")
    assertContains(profileFile, "ProfileBindings.restingFn")
  }

  // ------------------------------------------------------------------
  // C# registration shims
  // ------------------------------------------------------------------

  @Test
  fun `Point C sharp thunks reconstruct the receiver from leading component args`() {
    val files: List<GeneratedFile> = generateCSharpShims(pointRir, nativeLibraryName = "sample")
    val registration: String =
      files.single { it.relativePath == "PointRegistration.cs" }.content

    // Receiver components use readName (X/Y) so they stay distinct from ordinary params and from
    // out-pointers (outX/outY). Reconstruction is `new Point(X, Y)`.
    assertContains(
      registration,
      "private static int Magnitude_Get_Thunk(int X, int Y)",
    )
    assertContains(registration, "new Point(X, Y).Magnitude")

    // Instance method returning struct: components, ordinary args, out-pointers.
    assertContains(
      registration,
      "private static unsafe void Offset_Thunk(int X, int Y, int dx, int dy, int* outX, int* outY)",
    )
    assertContains(registration, "new Point(X, Y).Offset(dx, dy)")
    assertContains(registration, "*outX = result.X;")
    assertContains(registration, "*outY = result.Y;")

    // Instance method returning string.
    assertContains(registration, "private static IntPtr Format_Thunk(int X, int Y)")
    assertContains(registration, "new Point(X, Y).Format()")
    assertContains(registration, "Marshal.StringToCoTaskMemUTF8")

    // Static factory: no receiver components.
    assertContains(
      registration,
      "private static unsafe void Origin_Thunk(int* outX, int* outY)",
    )
    assertContains(registration, "Point.Origin()")
  }

  @Test
  fun `Point ModuleInitializer registers member slots after alternate ctors`() {
    val files: List<GeneratedFile> = generateCSharpShims(pointRir, nativeLibraryName = "sample")
    val registration: String =
      files.single { it.relativePath == "PointRegistration.cs" }.content

    assertContains(registration, "nuget_test_structs_point_register(")
    // 5 slots: int alternate ctor + Origin + Format + Offset + Magnitude.
    assertContains(registration, "5,")
    assertInOrder(
      registration,
      listOf(
        "Ctor__",
        "Origin_Thunk",
        "Format_Thunk",
        "Offset_Thunk",
        "Magnitude_Get_Thunk",
      ),
    )
    // Skipped members never appear.
    assertFalse(registration.contains("Clear_Thunk"))
    assertFalse(registration.contains("Equals_Thunk"))
    assertFalse(registration.contains("ToString_Thunk"))
    assertFalse(registration.contains("Scratch_"))
    assertFalse(registration.contains("X_Get_Thunk"))
  }

  @Test
  fun `Vector members alone force a C sharp registration shim`() {
    val files: List<GeneratedFile> = generateCSharpShims(vectorRir, nativeLibraryName = "sample")
    assertTrue(
      files.any { it.relativePath == "VectorRegistration.cs" },
      "members without alternate ctors still emit VectorRegistration.cs",
    )
    val registration: String =
      files.single { it.relativePath == "VectorRegistration.cs" }.content
    assertContains(registration, "Negate_Thunk(int X, int Y, int* outX, int* outY)")
    assertContains(registration, "new Vector(X, Y).Negate()")
  }

  @Test
  fun `Profile C sharp thunks convert string bool char enum components on reconstruct`() {
    val files: List<GeneratedFile> = generateCSharpShims(profileRir, nativeLibraryName = "sample")
    assertTrue(
      files.any { it.relativePath == "ProfileRegistration.cs" },
      "Profile members force ProfileRegistration.cs even without alternate ctors",
    )
    val registration: String =
      files.single { it.relativePath == "ProfileRegistration.cs" }.content

    // Receiver components use readName (Tag/Active/Grade/Mood). Case-sensitive C# then keeps the
    // WithMood parameter as lowercase `mood` without colliding with component `Mood`.
    assertContains(registration, "using Test.Enums;")
    assertContains(
      registration,
      "private static IntPtr Label_Get_Thunk(IntPtr TagPtr, byte Active, ushort Grade, int Mood)",
    )
    assertContains(
      registration,
      "new Profile(Marshal.PtrToStringUTF8(TagPtr)!, Active != 0, " +
          "(char)Grade, (CatMood)Mood).Label",
    )

    assertContains(
      registration,
      "private static byte IsPlayful_Get_Thunk(IntPtr TagPtr, byte Active, ushort Grade, int Mood)",
    )
    assertContains(registration, ".IsPlayful")

    assertContains(
      registration,
      "WithMood_Thunk(IntPtr TagPtr, byte Active, ushort Grade, int Mood, int mood,",
    )
    assertContains(registration, ".WithMood((CatMood)mood)")
    assertContains(registration, "*outTag = Marshal.StringToCoTaskMemUTF8(result.Tag);")
    assertContains(registration, "*outMood = (int)result.Mood;")

    assertContains(registration, "Resting_Thunk(IntPtr tagPtr")
    assertContains(registration, "Profile.Resting(")
  }

  @Test
  fun `Kotlin and C sharp agree on Point member registration slot count`() {
    val kotlinFiles: List<GeneratedFile> = generateKotlinStubs(pointRir)
    val csharpFiles: List<GeneratedFile> =
      generateCSharpShims(pointRir, nativeLibraryName = "sample")

    val bindings: String =
      kotlinFiles.single { it.relativePath.endsWith("/PointBindings.kt") }.content
    val registration: String =
      csharpFiles.single { it.relativePath == "PointRegistration.cs" }.content

    val kotlinSlots: String =
      Regex("expectedSlots = (\\d+),").find(bindings)!!.groupValues[1]
    // ModuleInitializer first int arg to the register export is slotCount.
    assertContains(registration, "\n                $kotlinSlots,")
    assertEquals("5", kotlinSlots)
  }

  private fun assertInOrder(content: String, values: List<String>) {
    val positions: List<Int> = values.map { value -> content.indexOf(value) }
    assertTrue(
      positions.all { it >= 0 },
      "missing ordered tokens in generated content: " +
          values.zip(positions).filter { it.second < 0 }.map { it.first },
    )
    assertEquals(positions.sorted(), positions, "tokens must follow canonical slot order: $values")
  }
}
