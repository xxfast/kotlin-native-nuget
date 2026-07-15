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
import io.github.xxfast.kotlin.native.nuget.rir.RirStructShape
import io.github.xxfast.kotlin.native.nuget.rir.RirStructType
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-058: C# Shape B structs (no public constructor — public fields or settable auto-properties)
 * in Kotlin.
 *
 * Mirrors ADR-058's Test Design fixture: `Test.Structs.Extent` (field-only, the canonical
 * ROADMAP shape) and `Test.Structs.Collar` (mixed field + `set` auto-prop + `init` auto-prop,
 * spanning the full v1 component vocabulary), plus `Test.Structs.Collars` (static methods
 * taking one or two Shape B structs).
 *
 * ADR-056 Decisions 3a/4a and its "Follow-up design: members" section are reused unchanged: the
 * wire format (components in, out-pointers out), the immutable Kotlin `data class` surface, and
 * the member bind/slot-ordering rules are all shape-agnostic. The only new mechanism this ADR
 * introduces is object-initializer reconstruction (`new T { A = a, B = b }`) in place of
 * constructor reconstruction (`new T(a, b)`), at both C# reconstruction sites.
 *
 * Per ADR-058's instruction on the `c.name.toMethodCamelCase()` (data-class property) vs. raw
 * `it.name` (private carrier delegation) generator seam: these fixtures set every
 * [RirStructComponent.name] to its already-lower-camel form and [RirStructComponent.readName] to
 * the C# member name, exactly as the reader is specified to emit them.
 */
class NugetStructShapeBGenerationTest {

  // ------------------------------------------------------------------
  // Fixtures
  // ------------------------------------------------------------------

  // Extent: field-only Shape B — public int Width; public int Height; — the canonical ROADMAP
  // shape. Members: a computed getter (Area), an instance method returning the struct itself
  // (Grow), and a static factory (Unit) — covers all four "members" categories from point 3.
  private val extentType = RirStructType(namespace = "Test.Structs", name = "Extent")
  private val extent = RirStruct(
    name = "Extent",
    shape = RirStructShape.INITIALIZER,
    components = listOf(
      RirStructComponent(name = "width", readName = "Width", type = RirPrimitiveType("int")),
      RirStructComponent(name = "height", readName = "Height", type = RirPrimitiveType("int")),
    ),
    methods = listOf(
      RirMethod(
        name = "Grow",
        isStatic = false,
        returnType = extentType,
        parameters = listOf(RirParameter(name = "by", type = RirPrimitiveType("int"))),
      ),
      RirMethod(
        name = "Unit",
        isStatic = true,
        returnType = extentType,
        parameters = emptyList(),
      ),
    ),
    properties = listOf(
      RirProperty(
        name = "Area", type = RirPrimitiveType("int"), isReadOnly = true, isStatic = false,
      ),
    ),
  )

  // Collar: MIXED Shape B — a public field (Girth), settable auto-props (Colour: string,
  // Mood: enum), init-only auto-props (Belled: bool, Initial: char). Spans the full v1 component
  // vocabulary in one struct. Members: an instance method returning the struct (Resize, the
  // struct-RECEIVER reconstruction site), a static factory (Plain), and two computed getters.
  private val catMood = RirEnum(
    name = "CatMood",
    entries = listOf(RirEnumEntry("Calm", 0), RirEnumEntry("Playful", 1)),
  )
  private val catMoodType = RirEnumType(namespace = "Test.Enums", name = "CatMood")

  private val collarType = RirStructType(namespace = "Test.Structs", name = "Collar")
  private val collar = RirStruct(
    name = "Collar",
    shape = RirStructShape.INITIALIZER,
    components = listOf(
      RirStructComponent(name = "girth", readName = "Girth", type = RirPrimitiveType("int")),
      RirStructComponent(name = "colour", readName = "Colour", type = RirStringType()),
      RirStructComponent(name = "belled", readName = "Belled", type = RirPrimitiveType("bool")),
      RirStructComponent(name = "initial", readName = "Initial", type = RirPrimitiveType("char")),
      RirStructComponent(name = "mood", readName = "Mood", type = catMoodType),
    ),
    methods = listOf(
      RirMethod(
        name = "Resize",
        isStatic = false,
        returnType = collarType,
        parameters = listOf(RirParameter(name = "by", type = RirPrimitiveType("int"))),
      ),
      RirMethod(
        name = "Plain",
        isStatic = true,
        returnType = collarType,
        parameters = listOf(RirParameter(name = "colour", type = RirStringType())),
      ),
    ),
    properties = listOf(
      RirProperty(
        name = "Label", type = RirStringType(), isReadOnly = true, isStatic = false,
      ),
      RirProperty(
        name = "IsLoud", type = RirPrimitiveType("bool"), isReadOnly = true, isStatic = false,
      ),
    ),
  )

  // Collars: static methods taking one or two Shape B structs — TWO struct params of DIFFERENT
  // shapes-of-Shape-B (Collar is mixed, Extent is field-only) in Pair() is where an abiArgs
  // expansion bug would show up (point 4).
  private val collars = RirClass(
    name = "Collars",
    isAbstract = true,
    isStatic = true,
    methods = listOf(
      RirMethod(
        name = "Describe",
        isStatic = true,
        returnType = RirStringType(),
        parameters = listOf(RirParameter(name = "c", type = collarType)),
      ),
      RirMethod(
        name = "Pair",
        isStatic = true,
        returnType = RirStringType(),
        parameters = listOf(
          RirParameter(name = "a", type = collarType),
          RirParameter(name = "b", type = extentType),
        ),
      ),
    ),
  )

  private val namespaceAliases: Map<String, Map<String, String>> = mapOf(
    "TestDependency" to mapOf(
      "Test.Enums" to "test.enums",
      "Test.Structs" to "test.structs",
    ),
  )

  private val rir = RirFile(
    assemblies = listOf(
      RirAssembly(
        packageId = "TestDependency",
        assemblyName = "TestDependency",
        namespaces = listOf(
          RirNamespace(name = "Test.Enums", types = listOf(catMood)),
          RirNamespace(name = "Test.Structs", types = listOf(extent, collar, collars)),
        ),
      ),
    ),
  )

  // ------------------------------------------------------------------
  // 1. Shape B stub generation: the SAME immutable Kotlin data class a Shape A struct gets.
  //    Components as vals, camelCase names, value equality (free from `data class`), no handle,
  //    no close(). The RIR carries the components; the generator is shape-agnostic on the
  //    Kotlin side (ADR-058: "the Kotlin generator needs no change").
  // ------------------------------------------------------------------

  @Test
  fun `Extent emits an immutable data class with camelCase component vals, no handle, no close`() {
    val files: List<GeneratedFile> = generateKotlinStubs(rir, namespaceAliases = namespaceAliases)
    val extentFile: String = files.single { it.relativePath.endsWith("/Extent.kt") }.content

    assertContains(extentFile, "internal data class Extent(")
    assertContains(extentFile, "val width: Int")
    assertContains(extentFile, "val height: Int")
    assertFalse(
      extentFile.contains("fun close()"),
      "a Shape B struct is copied by value across the bridge — there is nothing to close, " +
          "exactly like a Shape A struct (ADR-056 Decision 2a)",
    )
    assertFalse(extentFile.contains("Cleaner"))
  }

  @Test
  fun `Collar (mixed field + set + init components) emits the same immutable data class shape as Extent`() {
    val files: List<GeneratedFile> = generateKotlinStubs(rir, namespaceAliases = namespaceAliases)
    val collarFile: String = files.single { it.relativePath.endsWith("/Collar.kt") }.content

    assertContains(collarFile, "internal data class Collar(")
    assertContains(collarFile, "val girth: Int")
    assertContains(collarFile, "val colour: String")
    assertContains(collarFile, "val belled: Boolean")
    assertContains(collarFile, "val initial: Char")
    assertContains(collarFile, "val mood: CatMood")
    assertContains(collarFile, "import test.enums.CatMood")
    assertFalse(collarFile.contains("fun close()"))
  }

  // ADR-058 Decision 3's mitigation: for a Shape B struct, component order is C# FieldDef
  // (declaration) order — NOT public API the way a Shape A constructor's parameter order is. A
  // package author can reorder public fields/auto-properties without it being a source-breaking
  // C# change, and a Kotlin consumer constructing the data class POSITIONALLY has no warning
  // besides this KDoc (the wire itself is protected by Decision 5's contractHash fix). Must name
  // the exact ADR wording so it cannot silently regress to the old shape-agnostic fixed KDoc.
  @Test
  fun `Extent and Collar KDoc names C sharp declaration order and recommends named arguments`() {
    val files: List<GeneratedFile> = generateKotlinStubs(rir, namespaceAliases = namespaceAliases)
    val extentFile: String = files.single { it.relativePath.endsWith("/Extent.kt") }.content
    val collarFile: String = files.single { it.relativePath.endsWith("/Collar.kt") }.content

    listOf(extentFile, collarFile).forEach { content ->
      assertContains(content, "Component order follows the C# declaration order.")
      assertContains(content, "Prefer named arguments.")
      assertContains(
        content,
        "The C# struct's fields/properties are settable, but a Kotlin-side change can never be",
      )
      assertContains(
        content,
        "observable in C# (a copy crossed the boundary), so every component is a `val`.",
      )
      // Must NOT keep the Shape A wording, which has no order hazard and so carries no warning.
      assertFalse(
        content.contains(
          "Mutating this value never affects the C# side (a copy crossed the boundary); use [copy].",
        ),
        "a Shape B struct's KDoc must not keep the Shape A sentence — it must be replaced with " +
            "the declaration-order warning, not merely have it appended",
      )
    }
  }

  // ------------------------------------------------------------------
  // 2. Object-initializer reconstruction: the real Shape B delta. A Shape A struct reconstructs
  //    with `new T(c1, c2)`; a Shape B struct MUST reconstruct with `new T { A = c1, B = c2 }`,
  //    at BOTH reconstruction sites: an ordinary struct-typed parameter (paramBinding) and the
  //    struct receiver of a struct member (structReceiverReconstruction, ADR-056's members
  //    follow-up).
  // ------------------------------------------------------------------

  @Test
  fun `Describe_Thunk reconstructs the Shape B struct PARAMETER with an object initializer, not new T of components`() {
    val files: List<GeneratedFile> = generateCSharpShims(rir, nativeLibraryName = "sample")
    val registration: String =
      files.single { it.relativePath == "CollarsRegistration.cs" }.content

    assertContains(
      registration,
      "private static IntPtr Describe_Thunk(int c_Girth, IntPtr c_ColourPtr, byte c_Belled, " +
          "ushort c_Initial, int c_Mood)",
    )
    assertContains(
      registration,
      "Collars.Describe(new Collar { Girth = c_Girth, Colour = " +
          "Marshal.PtrToStringUTF8(c_ColourPtr)!, Belled = c_Belled != 0, " +
          "Initial = (char)c_Initial, Mood = (CatMood)c_Mood })",
    )
    assertFalse(
      registration.contains("new Collar(c_Girth"),
      "a Shape B struct must never reconstruct via the Shape A constructor-call syntax",
    )
  }

  @Test
  fun `Resize_Thunk reconstructs the Shape B struct RECEIVER with an object initializer`() {
    val files: List<GeneratedFile> = generateCSharpShims(rir, nativeLibraryName = "sample")
    val registration: String =
      files.single { it.relativePath == "CollarRegistration.cs" }.content

    assertContains(
      registration,
      "private static unsafe void Resize_Thunk(int Girth, IntPtr ColourPtr, byte Belled, " +
          "ushort Initial, int Mood, int by, int* outGirth, IntPtr* outColour, byte* outBelled, " +
          "ushort* outInitial, int* outMood)",
    )
    assertContains(
      registration,
      "Collar result = new Collar { Girth = Girth, Colour = Marshal.PtrToStringUTF8(ColourPtr)!, " +
          "Belled = Belled != 0, Initial = (char)Initial, Mood = (CatMood)Mood }.Resize(by);",
    )
    assertContains(registration, "*outGirth = result.Girth;")
    assertContains(registration, "*outColour = Marshal.StringToCoTaskMemUTF8(result.Colour);")
    assertContains(registration, "*outBelled = result.Belled ? (byte)1 : (byte)0;")
    assertContains(registration, "*outInitial = (ushort)result.Initial;")
    assertContains(registration, "*outMood = (int)result.Mood;")
    assertFalse(
      registration.contains("new Collar(Girth"),
      "the struct RECEIVER of a struct member must also use the object-initializer, not the " +
          "Shape A constructor-call syntax",
    )
  }

  @Test
  fun `Area_Get_Thunk (computed getter) also reconstructs the Shape B receiver with an object initializer`() {
    val files: List<GeneratedFile> = generateCSharpShims(rir, nativeLibraryName = "sample")
    val registration: String =
      files.single { it.relativePath == "ExtentRegistration.cs" }.content

    assertContains(registration, "private static int Area_Get_Thunk(int Width, int Height)")
    assertContains(registration, "new Extent { Width = Width, Height = Height }.Area")
    assertFalse(registration.contains("new Extent(Width"))
  }

  @Test
  fun `Grow_Thunk (instance method returning the struct) reconstructs its receiver with an object initializer`() {
    val files: List<GeneratedFile> = generateCSharpShims(rir, nativeLibraryName = "sample")
    val registration: String =
      files.single { it.relativePath == "ExtentRegistration.cs" }.content

    assertContains(
      registration,
      "private static unsafe void Grow_Thunk(int Width, int Height, int by, int* outWidth, " +
          "int* outHeight)",
    )
    assertContains(
      registration,
      "Extent result = new Extent { Width = Width, Height = Height }.Grow(by);",
    )
    assertContains(registration, "*outWidth = result.Width;")
    assertContains(registration, "*outHeight = result.Height;")
  }

  // ------------------------------------------------------------------
  // 3. Members on a Shape B struct compose from the shipped ADR-056 members follow-up: instance
  //    methods, computed getters, statics, struct returns via out-pointers, and the struct's own
  //    nuget_{ns}_{type}_register export with the same slot ordering (alternate ctors [none for
  //    Shape B, Decision 4a] -> statics -> instance methods -> computed getters).
  // ------------------------------------------------------------------

  @Test
  fun `Extent Kotlin stub exposes Area as a computed val, Grow as an instance method, Unit as a companion factory`() {
    val files: List<GeneratedFile> = generateKotlinStubs(rir, namespaceAliases = namespaceAliases)
    val extentFile: String = files.single { it.relativePath.endsWith("/Extent.kt") }.content

    assertContains(extentFile, "val area: Int")
    assertContains(extentFile, "get()")
    assertContains(extentFile, "ExtentBindings.areaGetterFn")

    assertContains(extentFile, "fun grow(by: Int): Extent = memScoped {")
    assertContains(extentFile, "fn.invoke(width, height, by, outWidth.ptr, outHeight.ptr)")
    assertContains(extentFile, "Extent(outWidth.value, outHeight.value)")
    assertContains(extentFile, "ExtentBindings.growFn")

    assertContains(extentFile, "companion object {")
    assertContains(extentFile, "fun unit(): Extent = memScoped {")
    assertContains(extentFile, "ExtentBindings.unitFn")
  }

  @Test
  fun `Extent register export covers static, instance method, and computed getter slots, in canonical order`() {
    val files: List<GeneratedFile> = generateKotlinStubs(rir, namespaceAliases = namespaceAliases)
    val bindings: String = files.single { it.relativePath.endsWith("/ExtentBindings.kt") }.content

    // Unit (static) + Grow (instance) + Area (computed getter) = 3 slots. Shape B contributes NO
    // alternate-constructor slot (Decision 4a: the reader emits no RirConstructor for Shape B).
    assertContains(bindings, "expectedSlots = 3,")
    assertInOrder(bindings, listOf("unitFn", "growFn", "areaGetterFn"))
  }

  @Test
  fun `Collar register export covers static, instance method, and two computed getters, in canonical order`() {
    val files: List<GeneratedFile> = generateKotlinStubs(rir, namespaceAliases = namespaceAliases)
    val bindings: String = files.single { it.relativePath.endsWith("/CollarBindings.kt") }.content

    // Plain (static) + Resize (instance) + IsLoud, Label (computed getters, alphabetical) = 4.
    assertContains(bindings, "expectedSlots = 4,")
    assertInOrder(bindings, listOf("plainFn", "resizeFn", "isLoudGetterFn", "labelGetterFn"))
  }

  @Test
  fun `Extent and Collar C sharp registrations expose the same register export and slot order as the Kotlin bindings`() {
    val csharpFiles: List<GeneratedFile> = generateCSharpShims(rir, nativeLibraryName = "sample")

    val extentRegistration: String =
      csharpFiles.single { it.relativePath == "ExtentRegistration.cs" }.content
    assertContains(extentRegistration, "nuget_test_structs_extent_register(")
    assertInOrder(extentRegistration, listOf("Unit_Thunk", "Grow_Thunk", "Area_Get_Thunk"))

    val collarRegistration: String =
      csharpFiles.single { it.relativePath == "CollarRegistration.cs" }.content
    assertContains(collarRegistration, "nuget_test_structs_collar_register(")
    assertInOrder(
      collarRegistration,
      listOf("Plain_Thunk", "Resize_Thunk", "IsLoud_Get_Thunk", "Label_Get_Thunk"),
    )
  }

  @Test
  fun `Kotlin and C sharp agree on Extent's register contract hash`() {
    val kotlinFiles: List<GeneratedFile> =
      generateKotlinStubs(rir, namespaceAliases = namespaceAliases)
    val csharpFiles: List<GeneratedFile> = generateCSharpShims(rir, nativeLibraryName = "sample")

    val bindings: String =
      kotlinFiles.single { it.relativePath.endsWith("/ExtentBindings.kt") }.content
    val registration: String =
      csharpFiles.single { it.relativePath == "ExtentRegistration.cs" }.content

    val kotlinHash: String = Regex("expectedHash = (-?\\d+)L").find(bindings)!!.groupValues[1]
    val csharpHash: String = Regex("(-?\\d+)L,\\n\\s+\\(IntPtr\\)\\(delegate\\*")
      .find(registration)!!.groupValues[1]

    assertEquals(kotlinHash, csharpHash)
  }

  // ------------------------------------------------------------------
  // 4. A signature with TWO struct parameters — one Shape B field-only (Extent), one Shape B
  //    mixed (Collar) — pins the abiArgs expansion across two DIFFERENT structs in one signature.
  // ------------------------------------------------------------------

  @Test
  fun `Pair_Thunk expands BOTH struct parameters through abiArgs and reconstructs each with its own object initializer`() {
    val files: List<GeneratedFile> = generateCSharpShims(rir, nativeLibraryName = "sample")
    val registration: String =
      files.single { it.relativePath == "CollarsRegistration.cs" }.content

    assertContains(
      registration,
      "private static IntPtr Pair_Thunk(int a_Girth, IntPtr a_ColourPtr, byte a_Belled, " +
          "ushort a_Initial, int a_Mood, int b_Width, int b_Height)",
    )
    assertContains(
      registration,
      "Collars.Pair(new Collar { Girth = a_Girth, Colour = Marshal.PtrToStringUTF8(a_ColourPtr)!, " +
          "Belled = a_Belled != 0, Initial = (char)a_Initial, Mood = (CatMood)a_Mood }, " +
          "new Extent { Width = b_Width, Height = b_Height })",
    )
  }

  @Test
  fun `pairCollar Kotlin call site decomposes both struct parameters through their own component conversions`() {
    val files: List<GeneratedFile> = generateKotlinStubs(rir, namespaceAliases = namespaceAliases)
    val collarsFile: String = files.single { it.relativePath.endsWith("/Collars.kt") }.content

    assertContains(
      collarsFile,
      "fn.invoke(a.girth, a.colour.cstr.ptr, a.belled, a.initial.code.toUShort(), a.mood.ordinal, " +
          "b.width, b.height)",
    )
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
