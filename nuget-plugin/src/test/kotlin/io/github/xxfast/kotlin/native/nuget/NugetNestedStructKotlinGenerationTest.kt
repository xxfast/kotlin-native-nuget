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
import kotlin.test.assertFalse

/**
 * ADR-059: nested struct components — the KOTLIN generator side (`NugetGenerateBindingsTask`).
 *
 * Fixture mirrors the already-merged `sample-dependency/Litter.cs` / `Nursery.cs` exactly:
 * `Profile` (Shape A leaf: string/bool/char/enum, the full CONVERTED vocabulary) and `Extent`
 * (Shape B leaf: direct `int`s) nest inside `Litter` (Shape A outer — A-in-A + B-in-A), which
 * nests inside `Nursery` (a DIFFERENT C# namespace: `Sample.Nested` vs `Sample.Structs`).
 *
 * Today (verified by reading the source): `cVarType`, `componentRead`, `argConversion`, `cfnType`
 * and `csAbiType`'s mirror all `error()` on a `RirStructType` reaching them, with the message
 * "nested struct components are not supported in v1 (ADR-056)". Any method whose signature
 * mentions a struct with a struct-typed component therefore throws when its stub/bindings file is
 * generated — that IS this feature's missing-implementation signal for those tests below (an
 * uncaught exception, not a compile error, not a silently-wrong-content assertion mismatch).
 */
class NugetNestedStructKotlinGenerationTest {

  // ------------------------------------------------------------------
  // Fixtures
  // ------------------------------------------------------------------

  private val catMood = RirEnum(
    name = "CatMood",
    entries = listOf(
      RirEnumEntry("Calm", 0), RirEnumEntry("Playful", 1), RirEnumEntry("Sleepy", 2),
    ),
  )
  private val catMoodType = RirEnumType(namespace = "Sample.Enums", name = "CatMood")

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
  private val profileType = RirStructType(namespace = "Sample.Structs", name = "Profile")

  private val extent = RirStruct(
    name = "Extent",
    shape = RirStructShape.INITIALIZER,
    components = listOf(
      RirStructComponent(name = "width", readName = "Width", type = RirPrimitiveType("int")),
      RirStructComponent(name = "height", readName = "Height", type = RirPrimitiveType("int")),
    ),
  )
  private val extentType = RirStructType(namespace = "Sample.Structs", name = "Extent")

  // Litter: Shape A OUTER, nests Profile (Shape A: string/bool/char/enum, CONVERTED) and Extent
  // (Shape B: direct ints) — A-in-A + B-in-A, mixing converted and direct leaves deliberately.
  private val litterType = RirStructType(namespace = "Sample.Structs", name = "Litter")

  // Deliberately member-free (no methods/properties/constructors of its OWN): every test in this
  // file reaches Litter only as a COMPONENT or a top-level parameter/return of another type's
  // method, never through Litter's own bridgeableStructRegistrables — so a member-free Litter
  // carries no extra risk of hitting an unrelated throw path.
  private val litter: RirStruct = RirStruct(
    name = "Litter",
    shape = RirStructShape.CONSTRUCTOR,
    components = listOf(
      RirStructComponent(name = "mother", readName = "Mother", type = profileType),
      RirStructComponent(name = "basket", readName = "Basket", type = extentType),
      RirStructComponent(name = "count", readName = "Count", type = RirPrimitiveType("int")),
      RirStructComponent(name = "mood", readName = "Mood", type = catMoodType),
    ),
  )

  private val namespaceAliases: Map<String, Map<String, String>> = mapOf(
    "SampleDependency" to mapOf(
      "Sample.Enums" to "sample.enums",
      "Sample.Structs" to "sample.structs",
      "Sample.Nested" to "sample.nested",
    ),
  )

  // ------------------------------------------------------------------
  // 1. Kotlin surface stays NESTED: `data class Litter(val mother: Profile, val basket: Extent,
  //    ...)`, not the flattened `data class Litter(val motherTag: String, ...)` ADR-059 explicitly
  //    rejects (Decision 2b). This does not touch the ABI-expansion machinery at all (it is a
  //    direct rendering of RirStructComponent.type), so it is a "surface mechanic" pin rather than
  //    a currently-broken assertion — kept here so a future refactor cannot silently flatten the
  //    Kotlin-facing type while fixing the ABI.
  // ------------------------------------------------------------------

  @Test
  fun `Litter kt declares a NESTED data class — val mother Profile, val basket Extent — not a flattened one`() {
    val rir = RirFile(
      assemblies = listOf(
        RirAssembly(
          packageId = "SampleDependency",
          assemblyName = "SampleDependency",
          namespaces = listOf(
            RirNamespace(name = "Sample.Enums", types = listOf(catMood)),
            RirNamespace(name = "Sample.Structs", types = listOf(profile, extent, litter)),
          ),
        ),
      ),
    )

    val files: List<GeneratedFile> = generateKotlinStubs(rir, namespaceAliases = namespaceAliases)
    val litterFile: String = files.single { it.relativePath.endsWith("/Litter.kt") }.content

    assertContains(litterFile, "internal data class Litter(")
    assertContains(litterFile, "val mother: Profile")
    assertContains(litterFile, "val basket: Extent")
    assertContains(litterFile, "val count: Int")
    assertContains(litterFile, "val mood: CatMood")
    assertFalse(
      litterFile.contains("val motherTag"),
      "the Kotlin surface must stay NESTED (a Profile component), never the flattened wire shape " +
          "(ADR-059 Decision 2b explicitly rejects `motherTag: String, motherActive: Boolean, " +
          "...`)",
    )
  }

  // ------------------------------------------------------------------
  // 2. Cross-package import: `Nursery` (package sample.nested) declares `val litter: Litter`,
  //    whose type lives in `sample.structs`. `structFileContent` renders a component's type with a
  //    bare, unimported `kotlinType(c.type)` today (verified by reading the source) — this
  //    assertion currently FAILS (missing import), with no exception, because Nursery here has NO
  //    bridgeable members (avoids the separate "nested struct not supported" throw entirely, so
  //    this test isolates exactly the import-rendering bug ADR-059 flags).
  // ------------------------------------------------------------------

  @Test
  fun `Nursery kt imports sample structs Litter — the component type lives in a different Kotlin package`() {
    val nursery = RirStruct(
      name = "Nursery",
      shape = RirStructShape.CONSTRUCTOR,
      components = listOf(
        RirStructComponent(name = "litter", readName = "Litter", type = litterType),
        RirStructComponent(name = "room", readName = "Room", type = RirPrimitiveType("int")),
      ),
    )
    val rir = RirFile(
      assemblies = listOf(
        RirAssembly(
          packageId = "SampleDependency",
          assemblyName = "SampleDependency",
          namespaces = listOf(
            RirNamespace(name = "Sample.Enums", types = listOf(catMood)),
            RirNamespace(name = "Sample.Structs", types = listOf(profile, extent, litter)),
            RirNamespace(name = "Sample.Nested", types = listOf(nursery)),
          ),
        ),
      ),
    )

    val files: List<GeneratedFile> = generateKotlinStubs(rir, namespaceAliases = namespaceAliases)
    val nurseryFile: String = files.single { it.relativePath.endsWith("/Nursery.kt") }.content

    assertContains(nurseryFile, "internal data class Nursery(")
    assertContains(nurseryFile, "val litter: Litter")
    assertContains(
      nurseryFile,
      "import sample.structs.Litter",
      message = "ADR-059: structFileContent renders a struct-typed component's Kotlin type with " +
          "kotlinType(c.type) — a bare, unimported simple name — instead of " +
          "declKotlinType(c.type, qualifiedTypeNames), which every OTHER reference site already " +
          "uses. This is the only fixture shape (Nursery in sample.nested, Litter in " +
          "sample.structs) that can even observe the bug.",
    )
  }

  // ------------------------------------------------------------------
  // 2b. Cross-package import, STUB FILE (not the struct's own data-class file): `Nurseries`
  //    (package sample.nested, an object-shape stub — all its methods are static) has a method
  //    returning `Nursery`, whose reassembly (structComponentReads) emits bare `Nursery(...)`,
  //    `Litter(...)`, `Profile(...)`, `Extent(...)` constructor calls — every struct in the
  //    RETURN's component tree, not just the outermost one. `stubFileContent` gathers
  //    `structEnumTypes` recursively (so an enum reached through a nested struct already imports
  //    correctly) but never gathered the STRUCT types the same way, so `Litter`/`Profile`/`Extent`
  //    rendered unresolved in the generated `Nurseries.kt` even though `structFileContent`'s own
  //    fix (test 2 above) already covered `Nursery.kt` itself.
  // ------------------------------------------------------------------

  @Test
  fun `Nurseries kt (object-shape STUB, not the struct file) imports every struct its return reassembles, at every nesting depth`() {
    val nursery = RirStruct(
      name = "Nursery",
      shape = RirStructShape.CONSTRUCTOR,
      components = listOf(
        RirStructComponent(name = "litter", readName = "Litter", type = litterType),
        RirStructComponent(name = "room", readName = "Room", type = RirPrimitiveType("int")),
      ),
    )
    val nurseryType = RirStructType(namespace = "Sample.Nested", name = "Nursery")
    val nurseries = RirClass(
      name = "Nurseries",
      isAbstract = true,
      isStatic = true,
      methods = listOf(
        RirMethod(
          name = "Rehome",
          isStatic = true,
          returnType = nurseryType,
          parameters = listOf(RirParameter(name = "n", type = nurseryType)),
        ),
      ),
    )
    val rir = RirFile(
      assemblies = listOf(
        RirAssembly(
          packageId = "SampleDependency",
          assemblyName = "SampleDependency",
          namespaces = listOf(
            RirNamespace(name = "Sample.Enums", types = listOf(catMood)),
            RirNamespace(name = "Sample.Structs", types = listOf(profile, extent, litter)),
            RirNamespace(name = "Sample.Nested", types = listOf(nursery, nurseries)),
          ),
        ),
      ),
    )

    val files: List<GeneratedFile> =
      generateKotlinStubs(rir, namespaceAliases = namespaceAliases)
    val nurseriesFile: String = files.single { it.relativePath.endsWith("/Nurseries.kt") }.content

    assertContains(nurseriesFile, "fun rehome(n: Nursery): Nursery = memScoped {")
    assertContains(
      nurseriesFile,
      "import sample.structs.Litter",
      message = "ADR-059: stubFileContent (the object-shape STUB file, distinct from " +
          "structFileContent's own Nursery.kt) reassembles a returned Nursery with a nested " +
          "Litter(...) constructor call and never imported it — structEnumTypes was gathered " +
          "recursively but there was no struct equivalent",
    )
    assertContains(nurseriesFile, "import sample.structs.Profile")
    assertContains(nurseriesFile, "import sample.structs.Extent")
  }

  // ------------------------------------------------------------------
  // 3. Stub body: flatten on the way OUT (one path expression per leaf, each through the SAME
  //    argConversion(...) a top-level parameter of that leaf's type would use — mixing CONVERTED
  //    leaves (Mother: string/bool/char/enum) with DIRECT leaves (Basket: int, Count: int) in one
  //    call), and reassemble on the way IN (a nested constructor expression, shape-agnostic:
  //    Profile is Shape A -> `Profile(...)`, Extent is Shape B -> but the Kotlin data class ctor
  //    is ALWAYS positional regardless, so this is simply `Extent(...)` too).
  // ------------------------------------------------------------------

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
        packageId = "SampleDependency",
        assemblyName = "SampleDependency",
        namespaces = listOf(
          RirNamespace(name = "Sample.Enums", types = listOf(catMood)),
          RirNamespace(
            name = "Sample.Structs",
            types = listOf(profile, extent, litter, litters),
          ),
        ),
      ),
    ),
  )

  @Test
  fun `describe decomposes every leaf of a nested struct parameter, mixing converted and direct component conversions`() {
    val files: List<GeneratedFile> =
      generateKotlinStubs(littersRir, namespaceAliases = namespaceAliases)
    val littersFile: String = files.single { it.relativePath.endsWith("/Litters.kt") }.content

    assertContains(littersFile, "fun describe(l: Litter): String {")
    assertContains(
      littersFile,
      "fn.invoke(l.mother.tag.cstr.ptr, l.mother.active, l.mother.grade.code.toUShort(), " +
          "l.mother.mood.ordinal, l.basket.width, l.basket.height, l.count, l.mood.ordinal)",
      message = "ADR-059: each leaf must be reached through a PATH expression (l.mother.tag, not " +
          "l.tag) and go through the SAME conversion its own type already requires — string -> " +
          ".cstr.ptr, bool -> untouched, char -> .code.toUShort(), enum -> .ordinal, int -> " +
          "untouched. Mixing Mother's converted vocabulary with Basket/Count's direct ints in " +
          "one call is deliberate: a generator that open-codes conversion at the wrong nesting " +
          "level, or converts a leaf it should leave alone (or vice versa), fails this exact " +
          "string.",
    )
  }

  @Test
  fun `grow reassembles the flattened out-pointer list back into a NESTED Litter, shape-agnostically`() {
    val files: List<GeneratedFile> =
      generateKotlinStubs(littersRir, namespaceAliases = namespaceAliases)
    val littersFile: String = files.single { it.relativePath.endsWith("/Litters.kt") }.content

    assertContains(littersFile, "fun grow(l: Litter, by: Int): Litter = memScoped {")
    // One alloc per LEAF, DFS pre-order — 8 leaves, not 4 (today's one-level allocs would be
    // `alloc<COpaquePointerVar>()` sized for a whole-struct out-pointer, which does not exist on
    // the wire at all).
    assertContains(littersFile, "val outMother_Tag = alloc<COpaquePointerVar>()")
    assertContains(littersFile, "val outMother_Active = alloc<UByteVar>()")
    assertContains(littersFile, "val outMother_Grade = alloc<UShortVar>()")
    assertContains(littersFile, "val outMother_Mood = alloc<IntVar>()")
    assertContains(littersFile, "val outBasket_Width = alloc<IntVar>()")
    assertContains(littersFile, "val outBasket_Height = alloc<IntVar>()")
    assertContains(littersFile, "val outCount = alloc<IntVar>()")
    assertContains(littersFile, "val outMood = alloc<IntVar>()")
    assertContains(
      littersFile,
      "fn.invoke(l.mother.tag.cstr.ptr, l.mother.active, l.mother.grade.code.toUShort(), " +
          "l.mother.mood.ordinal, l.basket.width, l.basket.height, l.count, l.mood.ordinal, by, " +
          "outMother_Tag.ptr, outMother_Active.ptr, outMother_Grade.ptr, outMother_Mood.ptr, " +
          "outBasket_Width.ptr, outBasket_Height.ptr, outCount.ptr, outMood.ptr)",
    )
    // Reassembly: the data class primary constructor is ALWAYS positional (Decision 2a/3a's
    // "shape-agnostic" property) — Profile(...) here is exactly the same call shape whether
    // Profile itself is Shape A or Shape B, and likewise for Extent(...).
    assertContains(
      littersFile,
      "Litter(Profile(outMother_TagResult, outMother_Active.value.toInt() != 0, " +
          "outMother_Grade.value.toInt().toChar(), " +
          "nugetEnumEntry(CatMood.entries, outMother_Mood.value, \"CatMood\")), " +
          "Extent(outBasket_Width.value, outBasket_Height.value), outCount.value, " +
          "nugetEnumEntry(CatMood.entries, outMood.value, \"CatMood\"))",
      message = "reassembly must be a NESTED constructor expression (Litter(Profile(...), " +
          "Extent(...), ...)), not a flat 8-argument Litter(...) call — the Kotlin data class " +
          "constructor is positional regardless of which C# shape each nested struct used " +
          "(ADR-059's 'shape-agnostic reassembly' property)",
    )
  }
}
