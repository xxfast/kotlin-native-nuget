package io.github.xxfast.kotlin.native.nuget

import io.github.xxfast.kotlin.native.nuget.rir.RirAssembly
import io.github.xxfast.kotlin.native.nuget.rir.RirClass
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
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * ADR-056 walking skeleton: `Sample.Structs.Point` (all-int Shape A struct) and
 * `Sample.Structs.Geometry.Translate(Point, int, int): Point` (a static method with a
 * struct-typed parameter AND a struct-typed return — the case that forces both the parameter
 * decomposition and the out-pointer return paths in one signature).
 *
 * Mirrors the real reverse-ir.json emitted by nuget-metadata-reader for
 * sample-dependency/Geometry.cs — see the walking-skeleton task description.
 */
class NugetStructGenerationTest {

  private val point = RirStruct(
    name = "Point",
    components = listOf(
      RirStructComponent(name = "x", readName = "X", type = RirPrimitiveType("int")),
      RirStructComponent(name = "y", readName = "Y", type = RirPrimitiveType("int")),
    ),
  )

  private val pointType = RirStructType(namespace = "Sample.Structs", name = "Point")

  private val geometry = RirClass(
    name = "Geometry",
    isAbstract = true,
    isStatic = true,
    methods = listOf(
      RirMethod(
        name = "Translate",
        isStatic = true,
        returnType = pointType,
        parameters = listOf(
          RirParameter(name = "p", type = pointType),
          RirParameter(name = "dx", type = RirPrimitiveType("int")),
          RirParameter(name = "dy", type = RirPrimitiveType("int")),
        ),
      ),
    ),
  )

  private val rir = RirFile(
    assemblies = listOf(
      RirAssembly(
        packageId = "SampleDependency",
        assemblyName = "SampleDependency",
        namespaces = listOf(
          RirNamespace(name = "Sample.Structs", types = listOf(point, geometry)),
        ),
      ),
    ),
  )

  // ------------------------------------------------------------------
  // Kotlin side (NugetGenerateBindingsTask)
  // ------------------------------------------------------------------

  @Test
  fun `Point emits an immutable data class with no Bindings file and no registration export`() {
    val files: List<GeneratedFile> = generateKotlinStubs(rir)

    val pointFile: GeneratedFile = files.single { it.relativePath.endsWith("/Point.kt") }
    assertContains(pointFile.content, "internal data class Point(")
    assertContains(pointFile.content, "val x: Int")
    assertContains(pointFile.content, "val y: Int")

    assertFalse(
      files.any { it.relativePath.contains("PointBindings") },
      "a v1 struct must claim zero registration slots — no PointBindings.kt",
    )
  }

  @Test
  fun `Geometry translate call site decomposes the struct parameter and assembles the struct return`() {
    val files: List<GeneratedFile> = generateKotlinStubs(rir)
    val stub: GeneratedFile = files.single { it.relativePath.endsWith("/Geometry.kt") }

    assertContains(stub.content, "fun translate(p: Point, dx: Int, dy: Int): Point = memScoped {")
    assertContains(stub.content, "val outX = alloc<IntVar>()")
    assertContains(stub.content, "val outY = alloc<IntVar>()")
    assertContains(stub.content, "fn.invoke(p.x, p.y, dx, dy, outX.ptr, outY.ptr)")
    assertContains(stub.content, "Point(outX.value, outY.value)")
  }

  @Test
  fun `Geometry Bindings fn-pointer type reflects the expanded ABI arity`() {
    val files: List<GeneratedFile> = generateKotlinStubs(rir)
    val bindings: GeneratedFile = files.single { it.relativePath.endsWith("/GeometryBindings.kt") }

    assertContains(
      bindings.content,
      "internal var translateFn: CPointer<CFunction<(Int, Int, Int, Int, CPointer<IntVar>, " +
          "CPointer<IntVar>) -> Unit>>? = null",
    )
    assertContains(bindings.content, "import kotlinx.cinterop.IntVar")
  }

  @Test
  fun `Geometry registers exactly one slot — the struct costs nothing extra`() {
    val files: List<GeneratedFile> = generateKotlinStubs(rir)
    val bindings: GeneratedFile = files.single { it.relativePath.endsWith("/GeometryBindings.kt") }
    assertContains(bindings.content, "expectedSlots = 1,")
  }

  // ------------------------------------------------------------------
  // C# side (NugetGenerateShimsTask)
  // ------------------------------------------------------------------

  @Test
  fun `Point has no C# registration shim`() {
    val files: List<GeneratedFile> = generateCSharpShims(rir, nativeLibraryName = "sample")
    assertFalse(files.any { it.relativePath.contains("PointRegistration") })
  }

  @Test
  fun `Translate_Thunk expands the struct parameter into components and the struct return into out-pointers`() {
    val files: List<GeneratedFile> = generateCSharpShims(rir, nativeLibraryName = "sample")
    val registration: GeneratedFile = files.single { it.relativePath == "GeometryRegistration.cs" }

    assertContains(
      registration.content,
      "private static unsafe void Translate_Thunk(int p_X, int p_Y, int dx, int dy, int* outX, " +
          "int* outY)",
    )
    assertContains(
      registration.content,
      "Point result = Geometry.Translate(new Point(p_X, p_Y), dx, dy);",
    )
    assertContains(registration.content, "*outX = result.X;")
    assertContains(registration.content, "*outY = result.Y;")
  }

  @Test
  fun `Geometry ModuleInitializer delegate type matches the expanded thunk signature`() {
    val files: List<GeneratedFile> = generateCSharpShims(rir, nativeLibraryName = "sample")
    val registration: GeneratedFile = files.single { it.relativePath == "GeometryRegistration.cs" }

    assertContains(
      registration.content,
      "delegate* unmanaged[Cdecl]<int, int, int, int, int*, int*, void>",
    )
  }

  @Test
  fun `Kotlin and C# generators agree on the register contract hash for Geometry`() {
    val kotlinFiles: List<GeneratedFile> = generateKotlinStubs(rir)
    val csharpFiles: List<GeneratedFile> = generateCSharpShims(rir, nativeLibraryName = "sample")

    val kotlinBindings: String =
      kotlinFiles.single { it.relativePath.endsWith("/GeometryBindings.kt") }.content
    val csharpRegistration: String =
      csharpFiles.single { it.relativePath == "GeometryRegistration.cs" }.content

    val kotlinHash: String = Regex("expectedHash = (-?\\d+)L").find(kotlinBindings)!!.groupValues[1]
    val csharpHash: String = Regex("(-?\\d+)L,\\n\\s+\\(IntPtr\\)\\(delegate\\*")
      .find(csharpRegistration)!!.groupValues[1]

    assertEquals(kotlinHash, csharpHash)
  }

  // ------------------------------------------------------------------
  // Component conversion: a struct whose components are string/bool/char/enum — the types that
  // need a REAL per-type conversion (not a raw pass-through), in parameter position, return
  // position, and as a property type. `CatMood` lives in a different namespace than `Profile`
  // (mirrors this repo's own bind{} config, where Sample.Structs and Sample.Enums are aliased to
  // different Kotlin packages) so these fixtures also cover the cross-package enum import.
  // ------------------------------------------------------------------

  private val catMood = RirEnum(
    name = "CatMood",
    entries = listOf(RirEnumEntry("Calm", 0), RirEnumEntry("Playful", 1)),
  )
  private val catMoodType = RirEnumType(namespace = "Sample.Enums", name = "CatMood")

  private val profile = RirStruct(
    name = "Profile",
    components = listOf(
      RirStructComponent(name = "tag", readName = "Tag", type = RirStringType()),
      RirStructComponent(name = "score", readName = "Score", type = RirPrimitiveType("int")),
      RirStructComponent(name = "active", readName = "Active", type = RirPrimitiveType("bool")),
      RirStructComponent(name = "grade", readName = "Grade", type = RirPrimitiveType("char")),
      RirStructComponent(name = "mood", readName = "Mood", type = catMoodType),
    ),
  )
  private val profileType = RirStructType(namespace = "Sample.Structs", name = "Profile")

  private val roster = RirClass(
    name = "Roster",
    isAbstract = true,
    isStatic = true,
    methods = listOf(
      RirMethod(
        name = "Describe",
        isStatic = true,
        returnType = RirStringType(),
        parameters = listOf(RirParameter(name = "p", type = profileType)),
      ),
      RirMethod(
        name = "Default",
        isStatic = true,
        returnType = profileType,
        parameters = emptyList(),
      ),
    ),
    properties = listOf(
      RirProperty(name = "Current", type = profileType, isReadOnly = false, isStatic = true),
    ),
  )

  private val profileRir = RirFile(
    assemblies = listOf(
      RirAssembly(
        packageId = "SampleDependency",
        assemblyName = "SampleDependency",
        namespaces = listOf(
          RirNamespace(name = "Sample.Enums", types = listOf(catMood)),
          RirNamespace(name = "Sample.Structs", types = listOf(profile, roster)),
        ),
      ),
    ),
  )

  private val profileNamespaceAliases: Map<String, Map<String, String>> = mapOf(
    "SampleDependency" to mapOf(
      "Sample.Enums" to "sample.enums",
      "Sample.Structs" to "sample.structs",
    ),
  )

  @Test
  fun `Profile imports the cross-package enum its own component references`() {
    val files: List<GeneratedFile> =
      generateKotlinStubs(profileRir, namespaceAliases = profileNamespaceAliases)
    val profileFile: GeneratedFile = files.single { it.relativePath.endsWith("/Profile.kt") }

    assertContains(profileFile.content, "import sample.enums.CatMood")
    assertContains(profileFile.content, "val tag: String")
    assertContains(profileFile.content, "val score: Int")
    assertContains(profileFile.content, "val active: Boolean")
    assertContains(profileFile.content, "val grade: Char")
    assertContains(profileFile.content, "val mood: CatMood")
  }

  @Test
  fun `describe decomposes each struct-parameter component through its own conversion`() {
    val files: List<GeneratedFile> =
      generateKotlinStubs(profileRir, namespaceAliases = profileNamespaceAliases)
    val roster: GeneratedFile = files.single { it.relativePath.endsWith("/Roster.kt") }

    // string -> .cstr.ptr, bool -> untouched, char -> .code.toUShort(), enum -> .ordinal — the
    // SAME argConversion(...) a top-level parameter of each type would use.
    assertContains(
      roster.content,
      "fn.invoke(p.tag.cstr.ptr, p.score, p.active, p.grade.code.toUShort(), p.mood.ordinal)",
    )
  }

  @Test
  fun `default reconstructs each struct-return component through its own conversion`() {
    val files: List<GeneratedFile> =
      generateKotlinStubs(profileRir, namespaceAliases = profileNamespaceAliases)
    val roster: GeneratedFile = files.single { it.relativePath.endsWith("/Roster.kt") }

    assertContains(roster.content, "fun default(): Profile = memScoped {")
    assertContains(roster.content, "val outTag = alloc<COpaquePointerVar>()")
    assertContains(roster.content, "val outActive = alloc<UByteVar>()")
    assertContains(roster.content, "val outGrade = alloc<UShortVar>()")
    assertContains(roster.content, "val outMood = alloc<IntVar>()")
    // string: reinterpret+toKString+free, not a raw `.value` (which would be a COpaquePointer?,
    // not a String).
    assertContains(roster.content, "reinterpret<ByteVar>().toKString()")
    assertContains(roster.content, "freeManagedString(")
    // bool: UByte -> Boolean, not a raw `.value` (which would be UByte, not Boolean).
    assertContains(roster.content, "outActive.value.toInt() != 0")
    // char: UShort -> Char, not a raw `.value` (which would be UShort, not Char).
    assertContains(roster.content, "outGrade.value.toInt().toChar()")
    // enum: bounds-checked through the shared nugetEnumEntry helper, not a raw ordinal.
    assertContains(roster.content, "nugetEnumEntry(CatMood.entries, outMood.value, \"CatMood\")")
  }

  @Test
  fun `Current property getter and setter both go through the shared per-component conversions`() {
    val files: List<GeneratedFile> =
      generateKotlinStubs(profileRir, namespaceAliases = profileNamespaceAliases)
    val roster: GeneratedFile = files.single { it.relativePath.endsWith("/Roster.kt") }

    assertContains(roster.content, "var current: Profile")
    // Getter: same out-pointer reconstruction shape as `default()`.
    assertContains(roster.content, "nugetEnumEntry(CatMood.entries, outMood.value, \"CatMood\")")
    // Setter: same component decomposition shape as `describe(...)`, applied to `value`.
    assertContains(
      roster.content,
      "fn.invoke(value.tag.cstr.ptr, value.score, value.active, value.grade.code.toUShort(), " +
          "value.mood.ordinal)",
    )
  }

  @Test
  fun `Profile registers zero slots even though Roster has a settable struct property`() {
    val files: List<GeneratedFile> =
      generateKotlinStubs(profileRir, namespaceAliases = profileNamespaceAliases)
    assertFalse(files.any { it.relativePath.contains("ProfileBindings") })
  }

  @Test
  fun `Describe_Thunk reconstructs the struct parameter through the exact per-component conversions`() {
    val files: List<GeneratedFile> = generateCSharpShims(profileRir, nativeLibraryName = "sample")
    val registration: GeneratedFile = files.single { it.relativePath == "RosterRegistration.cs" }

    // A struct-component enum from a different C# namespace needs its own `using`.
    assertContains(registration.content, "using Sample.Enums;")
    // A string RETURN crosses as IntPtr (ADR-049); the string PARAMETER component's declared
    // name gets the existing Ptr suffix (thunkParamName), matching p_TagPtr's use below.
    assertContains(
      registration.content,
      "private static IntPtr Describe_Thunk(IntPtr p_TagPtr, int p_Score, byte p_Active, " +
          "ushort p_Grade, int p_Mood)",
    )
    assertContains(
      registration.content,
      "new Profile(Marshal.PtrToStringUTF8(p_TagPtr)!, p_Score, p_Active != 0, (char)p_Grade, " +
          "(CatMood)p_Mood)",
    )
  }

  @Test
  fun `Default_Thunk writes each struct-return component through its own conversion`() {
    val files: List<GeneratedFile> = generateCSharpShims(profileRir, nativeLibraryName = "sample")
    val registration: GeneratedFile = files.single { it.relativePath == "RosterRegistration.cs" }

    assertContains(
      registration.content,
      "private static unsafe void Default_Thunk(IntPtr* outTag, int* outScore, byte* outActive, " +
          "ushort* outGrade, int* outMood)",
    )
    assertContains(registration.content, "*outTag = Marshal.StringToCoTaskMemUTF8(result.Tag);")
    assertContains(registration.content, "*outScore = result.Score;")
    assertContains(registration.content, "*outActive = result.Active ? (byte)1 : (byte)0;")
    assertContains(registration.content, "*outGrade = (ushort)result.Grade;")
    assertContains(registration.content, "*outMood = (int)result.Mood;")
  }

  @Test
  fun `Current_Get_Thunk and Current_Set_Thunk expand through the same component conversions`() {
    val files: List<GeneratedFile> = generateCSharpShims(profileRir, nativeLibraryName = "sample")
    val registration: GeneratedFile = files.single { it.relativePath == "RosterRegistration.cs" }

    assertContains(
      registration.content,
      "private static unsafe void Current_Get_Thunk(IntPtr* outTag, int* outScore, " +
          "byte* outActive, ushort* outGrade, int* outMood)",
    )
    assertContains(registration.content, "*outActive = result.Active ? (byte)1 : (byte)0;")
    assertContains(
      registration.content,
      "private static void Current_Set_Thunk(IntPtr value_TagPtr, int value_Score, " +
          "byte value_Active, ushort value_Grade, int value_Mood)",
    )
    assertContains(
      registration.content,
      "Roster.Current = new Profile(Marshal.PtrToStringUTF8(value_TagPtr)!, value_Score, " +
          "value_Active != 0, (char)value_Grade, (CatMood)value_Mood);",
    )
  }
}
