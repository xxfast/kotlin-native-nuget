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
import io.github.xxfast.kotlin.native.nuget.rir.RirStringType
import io.github.xxfast.kotlin.native.nuget.rir.RirStruct
import io.github.xxfast.kotlin.native.nuget.rir.RirStructComponent
import io.github.xxfast.kotlin.native.nuget.rir.RirStructShape
import io.github.xxfast.kotlin.native.nuget.rir.RirStructType
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/**
 * ADR-059: nested struct components — the C# generator side (`NugetGenerateShimsTask`).
 *
 * Fixture mirrors `TestDependency/Litter.cs` / `Nursery.cs`: `Profile` (Shape A leaf) and
 * `Extent` (Shape B leaf) nest inside `Litter` (Shape A OUTER — A-in-A + B-in-A). `Point` (Shape
 * A leaf) and `Collar` (Shape B leaf, mixed field/set/init) nest inside `Nest` (Shape B OUTER —
 * A-in-B + B-in-B), covering all four shape combinations across the two fixtures (Decision 3a).
 *
 * Today `csAbiType`/`csReturnConversion`/`paramConversion`/`thunkParamName`'s mirror all `error()`
 * on a `RirStructType` reaching them — any thunk whose signature mentions a struct with a
 * struct-typed component throws when generated. That thrown exception IS this feature's
 * missing-implementation signal below (not a compile error, not a silent wrong-content mismatch).
 */
class NugetNestedStructShimGenerationTest {

  // ------------------------------------------------------------------
  // Fixtures: A-in-A + B-in-A (Litter)
  // ------------------------------------------------------------------

  private val catMood = RirEnum(
    name = "CatMood",
    entries = listOf(
      RirEnumEntry("Calm", 0), RirEnumEntry("Playful", 1), RirEnumEntry("Sleepy", 2),
    ),
  )
  private val catMoodType = RirEnumType(namespace = "Test.Enums", name = "CatMood")

  private val profile = RirStruct(
    name = "Profile",
    shape = RirStructShape.CONSTRUCTOR,
    components = listOf(
      RirStructComponent(name = "tag", readName = "Tag", type = RirStringType()),
      RirStructComponent(name = "active", readName = "Active", type = RirPrimitiveType("bool")),
      RirStructComponent(name = "grade", readName = "Grade", type = RirPrimitiveType("char")),
      RirStructComponent(name = "mood", readName = "Mood", type = catMoodType),
    ),
  )
  private val profileType = RirStructType(namespace = "Test.Structs", name = "Profile")

  private val extent = RirStruct(
    name = "Extent",
    shape = RirStructShape.INITIALIZER,
    components = listOf(
      RirStructComponent(name = "width", readName = "Width", type = RirPrimitiveType("int")),
      RirStructComponent(name = "height", readName = "Height", type = RirPrimitiveType("int")),
    ),
  )
  private val extentType = RirStructType(namespace = "Test.Structs", name = "Extent")

  private val litterType = RirStructType(namespace = "Test.Structs", name = "Litter")
  private val litter = RirStruct(
    name = "Litter",
    shape = RirStructShape.CONSTRUCTOR,
    components = listOf(
      RirStructComponent(name = "mother", readName = "Mother", type = profileType),
      RirStructComponent(name = "basket", readName = "Basket", type = extentType),
      RirStructComponent(name = "count", readName = "Count", type = RirPrimitiveType("int")),
      RirStructComponent(name = "mood", readName = "Mood", type = catMoodType),
    ),
  )

  private val litters = RirClass(
    name = "Litters",
    isAbstract = true,
    isStatic = true,
    methods = listOf(
      RirMethod(
        name = "Describe",
        isStatic = true,
        returnType = RirStringType(),
        parameters = listOf(RirParameter(name = "l", type = litterType)),
      ),
      RirMethod(
        name = "Grow",
        isStatic = true,
        returnType = litterType,
        parameters = listOf(
          RirParameter(name = "l", type = litterType),
          RirParameter(name = "by", type = RirPrimitiveType("int")),
        ),
      ),
    ),
  )

  private val littersRir = RirFile(
    assemblies = listOf(
      RirAssembly(
        packageId = "TestDependency",
        assemblyName = "TestDependency",
        namespaces = listOf(
          RirNamespace(name = "Test.Enums", types = listOf(catMood)),
          RirNamespace(name = "Test.Structs", types = listOf(profile, extent, litter, litters)),
        ),
      ),
    ),
  )

  // ------------------------------------------------------------------
  // 0. Regression guard (byte-for-byte): depth 1 must render EXACTLY what ADR-056 already emits.
  // ------------------------------------------------------------------

  private val point = RirStruct(
    name = "Point",
    shape = RirStructShape.CONSTRUCTOR,
    components = listOf(
      RirStructComponent(name = "x", readName = "X", type = RirPrimitiveType("int")),
      RirStructComponent(name = "y", readName = "Y", type = RirPrimitiveType("int")),
    ),
  )
  private val pointType = RirStructType(namespace = "Test.Structs", name = "Point")
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
        ),
      ),
    ),
  )
  private val geometryRir = RirFile(
    assemblies = listOf(
      RirAssembly(
        packageId = "TestDependency",
        assemblyName = "TestDependency",
        namespaces = listOf(RirNamespace(name = "Test.Structs", types = listOf(point, geometry))),
      ),
    ),
  )

  @Test
  fun `REGRESSION GUARD — Translate_Thunk is byte-for-byte unchanged from ADR-056`() {
    val files: List<GeneratedFile> = generateCSharpShims(geometryRir, nativeLibraryName = "sample")
    val registration: String =
      files.single { it.relativePath == "GeometryRegistration.cs" }.content

    assertContains(
      registration,
      "private static unsafe void Translate_Thunk(int p_X, int p_Y, int dx, int* outX, int* outY)",
      message = "the depth-2 recursion must not change the depth-1 (already-shipped) thunk shape",
    )
    assertContains(registration, "Point result = Geometry.Translate(new Point(p_X, p_Y), dx);")
  }

  // ------------------------------------------------------------------
  // 1. A-in-A + B-in-A: Litter's receiver-less PARAMETER reconstruction is a nested `new Litter(
  //    new Profile(...), new Extent { ... }, count, mood)` — the outer's Shape A ctor call wraps
  //    an inner Shape A ctor call (Profile) AND an inner Shape B object initializer (Extent).
  // ------------------------------------------------------------------

  @Test
  fun `Describe_Thunk reconstructs a nested Litter parameter — outer ctor wraps inner ctor (Profile) and inner initializer (Extent)`() {
    val files: List<GeneratedFile> = generateCSharpShims(littersRir, nativeLibraryName = "sample")
    val registration: String =
      files.single { it.relativePath == "LittersRegistration.cs" }.content

    assertContains(
      registration,
      "private static IntPtr Describe_Thunk(IntPtr l_Mother_TagPtr, byte l_Mother_Active, " +
          "ushort l_Mother_Grade, int l_Mother_Mood, int l_Basket_Width, int l_Basket_Height, " +
          "int l_Count, int l_Mood)",
      message = "the thunk signature must be the 8 flattened LEAVES, DFS pre-order, path-named " +
          "l_Mother_Tag.. l_Basket_Height.. l_Count.. l_Mood — not 4 arguments naming whole " +
          "un-flattened structs",
    )
    assertContains(
      registration,
      "Litters.Describe(new Litter(new Profile(Marshal.PtrToStringUTF8(l_Mother_TagPtr)!, " +
          "l_Mother_Active != 0, (char)l_Mother_Grade, (CatMood)l_Mother_Mood), " +
          "new Extent { Width = l_Basket_Width, Height = l_Basket_Height }, l_Count, " +
          "(CatMood)l_Mood))",
      message = "reconstruction must be RECURSIVE: the outer Litter ctor call's first two " +
          "arguments are themselves reconstruction expressions (an inner ctor call for the Shape " +
          "A Profile component, an inner object initializer for the Shape B Extent component)",
    )
  }

  @Test
  fun `Grow_Thunk writes every leaf of a nested Litter return through its own out-pointer, DFS pre-order`() {
    val files: List<GeneratedFile> = generateCSharpShims(littersRir, nativeLibraryName = "sample")
    val registration: String =
      files.single { it.relativePath == "LittersRegistration.cs" }.content

    assertContains(
      registration,
      "private static unsafe void Grow_Thunk(IntPtr l_Mother_TagPtr, byte l_Mother_Active, " +
          "ushort l_Mother_Grade, int l_Mother_Mood, int l_Basket_Width, int l_Basket_Height, " +
          "int l_Count, int l_Mood, int by, IntPtr* outMother_Tag, byte* outMother_Active, " +
          "ushort* outMother_Grade, int* outMother_Mood, int* outBasket_Width, " +
          "int* outBasket_Height, int* outCount, int* outMood)",
      message = "8 in-leaves, `by`, then 8 out-pointers (one per LEAF, DFS pre-order) appended " +
          "after every in-argument — the struct return contributes no whole-struct out-pointer",
    )
    assertContains(
      registration,
      "*outMother_Tag = Marshal.StringToCoTaskMemUTF8(result.Mother.Tag);",
    )
    assertContains(registration, "*outMother_Active = result.Mother.Active ? (byte)1 : (byte)0;")
    assertContains(registration, "*outMother_Grade = (ushort)result.Mother.Grade;")
    assertContains(registration, "*outMother_Mood = (int)result.Mother.Mood;")
    assertContains(registration, "*outBasket_Width = result.Basket.Width;")
    assertContains(registration, "*outBasket_Height = result.Basket.Height;")
    assertContains(registration, "*outCount = result.Count;")
    assertContains(registration, "*outMood = (int)result.Mood;")
  }

  // ------------------------------------------------------------------
  // 2. A-in-B + B-in-B: `Nest` (Shape B OUTER) nests `Point` (Shape A) via a settable auto-prop
  //    source and `Collar` (Shape B, mixed field/set/init) via a public-field source. The outer
  //    object initializer's `Centre = ...`/`Collar = ...` assignments are themselves nested
  //    reconstruction expressions.
  // ------------------------------------------------------------------

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
  )
  private val collarType = RirStructType(namespace = "Test.Structs", name = "Collar")

  private val nestPoint = RirStruct(
    name = "Point",
    shape = RirStructShape.CONSTRUCTOR,
    components = listOf(
      RirStructComponent(name = "x", readName = "X", type = RirPrimitiveType("int")),
      RirStructComponent(name = "y", readName = "Y", type = RirPrimitiveType("int")),
    ),
  )
  private val nestPointType = RirStructType(namespace = "Test.Structs", name = "Point")

  private val nestType = RirStructType(namespace = "Test.Structs", name = "Nest")
  private val nest = RirStruct(
    name = "Nest",
    shape = RirStructShape.INITIALIZER,
    components = listOf(
      RirStructComponent(name = "collar", readName = "Collar", type = collarType),
      RirStructComponent(name = "centre", readName = "Centre", type = nestPointType),
      RirStructComponent(name = "bounds", readName = "Bounds", type = extentType),
      RirStructComponent(name = "lined", readName = "Lined", type = RirPrimitiveType("bool")),
    ),
  )

  private val nestFns = RirClass(
    name = "NestFns",
    isAbstract = true,
    isStatic = true,
    methods = listOf(
      RirMethod(
        name = "Describe",
        isStatic = true,
        returnType = RirStringType(),
        parameters = listOf(RirParameter(name = "n", type = nestType)),
      ),
    ),
  )

  private val nestRir = RirFile(
    assemblies = listOf(
      RirAssembly(
        packageId = "TestDependency",
        assemblyName = "TestDependency",
        namespaces = listOf(
          RirNamespace(name = "Test.Enums", types = listOf(catMood)),
          RirNamespace(
            name = "Test.Structs",
            types = listOf(collar, nestPoint, extent, nest, nestFns),
          ),
        ),
      ),
    ),
  )

  @Test
  fun `Describe_Thunk reconstructs a Shape B outer Nest — object initializer assignments ARE nested reconstruction expressions`() {
    val files: List<GeneratedFile> = generateCSharpShims(nestRir, nativeLibraryName = "sample")
    val registration: String =
      files.single { it.relativePath == "NestFnsRegistration.cs" }.content

    assertContains(
      registration,
      "NestFns.Describe(new Nest { Collar = new Collar { Girth = n_Collar_Girth, Colour = " +
          "Marshal.PtrToStringUTF8(n_Collar_ColourPtr)!, Belled = n_Collar_Belled != 0, " +
          "Initial = (char)n_Collar_Initial, Mood = (CatMood)n_Collar_Mood }, " +
          "Centre = new Point(n_Centre_X, n_Centre_Y), " +
          "Bounds = new Extent { Width = n_Bounds_Width, Height = n_Bounds_Height }, " +
          "Lined = n_Lined != 0 })",
      message = "B-in-B (Collar: nested object initializer) and A-in-B (Point: nested " +
          "constructor call) must BOTH appear as the VALUE of the outer Nest object " +
          "initializer's own assignments — the outer's Shape B decides PLACEMENT (`Collar = " +
          "...`), the inner's own shape decides how that expression is BUILT",
    )
  }

  // ------------------------------------------------------------------
  // 3. Cross-namespace `using`: a class in Test.Nested referencing a struct declared in
  //    Test.Structs needs `using Test.Structs;` for its unqualified `new Point(...)` to
  //    resolve. Deliberately FLAT (Point has no struct-typed component of its own) so this test
  //    isolates the "using" bug from the (still unimplemented) recursive-flattening throw path —
  //    ADR-059 calls this out as a PRE-EXISTING gap that nesting merely makes routine.
  // ------------------------------------------------------------------

  private val nurseries = RirClass(
    name = "Nurseries",
    isAbstract = true,
    isStatic = true,
    methods = listOf(
      RirMethod(
        name = "Trace",
        isStatic = true,
        returnType = RirStringType(),
        parameters = listOf(RirParameter(name = "p", type = pointType)),
      ),
    ),
  )
  private val crossNamespaceRir = RirFile(
    assemblies = listOf(
      RirAssembly(
        packageId = "TestDependency",
        assemblyName = "TestDependency",
        namespaces = listOf(
          RirNamespace(name = "Test.Structs", types = listOf(point)),
          RirNamespace(name = "Test.Nested", types = listOf(nurseries)),
        ),
      ),
    ),
  )

  @Test
  fun `Nurseries cs uses Sample Structs — a class referencing a struct declared in a DIFFERENT C sharp namespace`() {
    val files: List<GeneratedFile> =
      generateCSharpShims(crossNamespaceRir, nativeLibraryName = "sample")
    val registration: String =
      files.single { it.relativePath == "NurseriesRegistration.cs" }.content

    assertContains(registration, "namespace Test.Nested")
    assertContains(registration, "new Point(p_X, p_Y)")
    assertContains(
      registration,
      "using Test.Structs;",
      message = "ADR-059: referencedEnumTypes only collects RirEnumType — there is no struct " +
          "equivalent, so a struct declared in a different C# namespace than the class " +
          "referencing it gets no `using`, and the generated `new Point(...)` does not resolve. " +
          "This is a PRE-EXISTING gap (no nesting required to observe it) that nesting makes " +
          "routine, per ADR-059's own framing.",
    )
  }

  // ------------------------------------------------------------------
  // 4. Arity ceiling (Decision 5a): a member whose flattened ABI arity exceeds 22 must be SKIPPED
  //    by BOTH generators — if only one generator dropped it, the two sides' registration slots
  //    would silently misalign (memory corruption with no error). `Merge(Litter, Litter): Litter`
  //    is 8 + 8 in + 8 out = 24 arguments, over the ceiling; `Grow(Litter, int): Litter` (17) and
  //    `Describe(Litter): string` (8) must both still bind.
  //
  // NOTE: the shared filter this decision requires lives in RirBridging.kt's
  // bridgeableRegistrables/bridgeableStructRegistrables (per ADR-059 Decision 5a) and does not
  // exist yet, so this fixture currently throws on the "nested struct not supported" guard before
  // an arity-specific check would even run — which is still the correct "missing feature" signal
  // for this step. Once nesting itself is implemented, this is the test that then proves the
  // arity ceiling is enforced identically by both generators.
  // ------------------------------------------------------------------

  private val littersWithMerge = litters.copy(
    methods = litters.methods + RirMethod(
      name = "Merge",
      isStatic = true,
      returnType = litterType,
      parameters = listOf(
        RirParameter(name = "a", type = litterType),
        RirParameter(name = "b", type = litterType),
      ),
    ),
  )
  private val mergeRir = littersRir.copy(
    assemblies = listOf(
      littersRir.assemblies.single().copy(
        namespaces = listOf(
          RirNamespace(name = "Test.Enums", types = listOf(catMood)),
          RirNamespace(
            name = "Test.Structs",
            types = listOf(profile, extent, litter, littersWithMerge),
          ),
        ),
      ),
    ),
  )

  @Test
  fun `both generators skip Merge (24 flattened args, over the 22-arg ceiling) but still bind Describe and Grow`() {
    val kotlinFiles: List<GeneratedFile> =
      generateKotlinStubs(
        mergeRir,
        namespaceAliases = mapOf(
          "TestDependency" to mapOf(
            "Test.Enums" to "test.enums",
            "Test.Structs" to "test.structs",
          ),
        ),
      )
    val csharpFiles: List<GeneratedFile> =
      generateCSharpShims(mergeRir, nativeLibraryName = "sample")

    val littersKt: String = kotlinFiles.single { it.relativePath.endsWith("/Litters.kt") }.content
    val littersRegistration: String =
      csharpFiles.single { it.relativePath == "LittersRegistration.cs" }.content

    assertEquals(
      false,
      littersKt.contains("fun merge("),
      "Merge's flattened arity is 24 (8 + 8 in, 8 out), over the 22-argument CFunction.invoke " +
          "ceiling verified in ADR-059 Constraint 3 — it must be SKIPPED, not generate Kotlin " +
          "that fails to compile",
    )
    assertEquals(
      false,
      littersRegistration.contains("Merge_Thunk"),
      "the C# generator must independently arrive at the SAME skip — if only one side dropped " +
          "Merge, the two generators' registration slot counts would silently misalign",
    )
    assertContains(littersKt, "fun describe(l: Litter): String {")
    assertContains(littersKt, "fun grow(l: Litter, by: Int): Litter = memScoped {")
    assertContains(littersRegistration, "Describe_Thunk")
    assertContains(littersRegistration, "Grow_Thunk")
  }
}
