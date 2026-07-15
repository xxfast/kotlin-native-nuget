package io.github.xxfast.kotlin.native.nuget.rir

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * ADR-059: nested struct components — a struct-typed component that is itself a struct.
 *
 * [abiArgs]/[abiOutArgs]/[structReceiverAbiArgs] must become a recursive, depth-first pre-order
 * tree walk (Decision 1a/4a). Today (verified by reading the source) they expand exactly one
 * level: a struct-typed component's own `type` is copied straight into the returned [AbiArg]
 * unchanged, even when that `type` is itself a [RirStructType]. Every test below that flattens a
 * struct nested two or three deep therefore currently produces the WRONG [AbiArg] list (too few
 * entries, and at least one entry whose `type` is a [RirStructType] instead of a scalar) — an
 * assertion failure, not a compile error or a thrown exception, because `abiArgs` itself never
 * inspects component `type`s recursively.
 *
 * Fixture mirrors `TestDependency/Litter.cs` / `Nursery.cs` (already merged): `Profile` (Shape
 * A leaf: string/bool/char/enum, the full CONVERTED vocabulary) and `Extent` (Shape B leaf: direct
 * `int`s), nested inside `Litter` (Shape A outer: A-in-A + B-in-A), nested inside `Nursery`
 * (depth 3, a different C# namespace).
 */
class RirNestedStructBridgingTest {

  // ------------------------------------------------------------------
  // Fixtures
  // ------------------------------------------------------------------

  private val catMood = RirEnum(
    name = "CatMood",
    entries = listOf(
      RirEnumEntry("Calm", 0), RirEnumEntry("Playful", 1), RirEnumEntry("Sleepy", 2),
    ),
  )
  private val catMoodType = RirEnumType(namespace = "Test.Enums", name = "CatMood")

  // Profile: Shape A leaf, the full converted vocabulary (string/bool/char/enum).
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

  // Extent: Shape B leaf, direct-only int components.
  private val extent = RirStruct(
    name = "Extent",
    shape = RirStructShape.INITIALIZER,
    components = listOf(
      RirStructComponent(name = "width", readName = "Width", type = RirPrimitiveType("int")),
      RirStructComponent(name = "height", readName = "Height", type = RirPrimitiveType("int")),
    ),
  )
  private val extentType = RirStructType(namespace = "Test.Structs", name = "Extent")

  // Litter: Shape A OUTER — nests a Shape A component (Profile) and a Shape B component (Extent):
  // A-in-A and B-in-A in one type. Flattens to 8 leaves.
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
  private val litterType = RirStructType(namespace = "Test.Structs", name = "Litter")

  // Nursery: DEPTH 3, a different C# namespace from Litter (Test.Nested vs Test.Structs).
  private val nursery = RirStruct(
    name = "Nursery",
    shape = RirStructShape.CONSTRUCTOR,
    components = listOf(
      RirStructComponent(name = "litter", readName = "Litter", type = litterType),
      RirStructComponent(name = "room", readName = "Room", type = RirPrimitiveType("int")),
    ),
  )
  private val nurseryType = RirStructType(namespace = "Test.Nested", name = "Nursery")

  private val structs: Map<RirTypeKey, RirStruct> = mapOf(
    RirTypeKey("Test.Structs", "Profile") to profile,
    RirTypeKey("Test.Structs", "Extent") to extent,
    RirTypeKey("Test.Structs", "Litter") to litter,
    RirTypeKey("Test.Nested", "Nursery") to nursery,
  )

  // The 8 flattened leaves of Litter, DFS pre-order, in ADR-059's own worked example order.
  private val litterLeafReadPath: List<Pair<String, RirTypeRef>> = listOf(
    "Mother_Tag" to RirStringType(),
    "Mother_Active" to RirPrimitiveType("bool"),
    "Mother_Grade" to RirPrimitiveType("char"),
    "Mother_Mood" to catMoodType,
    "Basket_Width" to RirPrimitiveType("int"),
    "Basket_Height" to RirPrimitiveType("int"),
    "Count" to RirPrimitiveType("int"),
    "Mood" to catMoodType,
  )

  // ------------------------------------------------------------------
  // 0. Regression guard (byte-for-byte): depth 1 must render EXACTLY what ADR-056 already emits.
  //    This is the anchor every recursive change must not disturb. Currently PASSES — kept here,
  //    colocated with the new recursive tests, so a future refactor of abiArgs/abiOutArgs cannot
  //    silently regress the depth-1 case while "fixing" depth 2+.
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
  private val pointStructs: Map<RirTypeKey, RirStruct> =
    mapOf(RirTypeKey("Test.Structs", "Point") to point)

  @Test
  fun `REGRESSION GUARD — depth 1 abiArgs and abiOutArgs are byte-for-byte unchanged from ADR-056`() {
    val params = listOf(
      RirParameter(name = "p", type = pointType),
      RirParameter(name = "dx", type = RirPrimitiveType("int")),
    )

    assertEquals(
      listOf(
        AbiArg("p_X", RirPrimitiveType("int"), isOutPointer = false),
        AbiArg("p_Y", RirPrimitiveType("int"), isOutPointer = false),
        AbiArg("dx", RirPrimitiveType("int"), isOutPointer = false),
      ),
      abiArgs(params, pointStructs),
      "the depth-2 recursion must not change the depth-1 (already-shipped) output at all",
    )
    assertEquals(
      listOf(
        AbiArg("outX", RirPrimitiveType("int"), isOutPointer = true),
        AbiArg("outY", RirPrimitiveType("int"), isOutPointer = true),
      ),
      abiOutArgs(pointType, pointStructs),
      "the depth-2 recursion must not change the depth-1 (already-shipped) output at all",
    )
  }

  // ------------------------------------------------------------------
  // 1. abiArgs: DFS pre-order flatten of a struct-typed PARAMETER nested two deep. ABI name is the
  //    `_`-joined readName path, prefixed by the parameter's own name.
  // ------------------------------------------------------------------

  @Test
  fun `abiArgs flattens a struct-typed parameter nested two deep, DFS pre-order, path-joined ABI names`() {
    val params = listOf(RirParameter(name = "l", type = litterType))

    val expected: List<AbiArg> = litterLeafReadPath.map { (path, type) ->
      AbiArg("l_$path", type, isOutPointer = false)
    }

    assertEquals(
      expected,
      abiArgs(params, structs),
      "abiArgs must recurse into a struct-typed component's OWN components (Mother: Profile, " +
          "Basket: Extent) instead of leaving a bare RirStructType in the returned AbiArg — " +
          "today it only expands Litter's own 4 top-level components, two of which (Mother, " +
          "Basket) are themselves un-flattened RirStructType values",
    )
  }

  @Test
  fun `abiArgs keeps a sibling non-struct parameter after the flattened struct parameter`() {
    val params = listOf(
      RirParameter(name = "l", type = litterType),
      RirParameter(name = "by", type = RirPrimitiveType("int")),
    )

    val args: List<AbiArg> = abiArgs(params, structs)

    assertEquals(9, args.size, "8 flattened Litter leaves + 1 ordinary int parameter — got $args")
    assertEquals(AbiArg("by", RirPrimitiveType("int"), isOutPointer = false), args.last())
  }

  // ------------------------------------------------------------------
  // 2. abiOutArgs: a struct RETURN nested two deep expands to one out-pointer per LEAF, DFS
  //    pre-order, appended after all in-arguments (tested at the caller in the generator tests —
  //    here just the out-arg list itself).
  // ------------------------------------------------------------------

  @Test
  fun `abiOutArgs flattens a struct-typed return nested two deep into one out-pointer per leaf`() {
    val expected: List<AbiArg> = litterLeafReadPath.map { (path, type) ->
      AbiArg("out$path", type, isOutPointer = true)
    }

    assertEquals(
      expected,
      abiOutArgs(litterType, structs),
      "abiOutArgs must recurse the same way abiArgs does — today it only emits 4 out-pointers " +
          "(outMother, outBasket, outCount, outMood), two of which name a whole un-flattened " +
          "struct",
    )
  }

  // ------------------------------------------------------------------
  // 3. structReceiverAbiArgs: the receiver of a struct instance member, unprefixed.
  // ------------------------------------------------------------------

  @Test
  fun `structReceiverAbiArgs flattens a nesting struct's own receiver, unprefixed, DFS pre-order`() {
    val expected: List<AbiArg> = litterLeafReadPath.map { (path, type) ->
      AbiArg(path, type, isOutPointer = false)
    }

    assertEquals(
      expected,
      structReceiverAbiArgs(litter, structs),
      "the struct RECEIVER of a Litter instance member (e.g. Grow) must flatten to the same 8 " +
          "leaves as a Litter parameter, just without the leading parameter-name prefix",
    )
  }

  // ------------------------------------------------------------------
  // 4. DEPTH 3: Nursery -> Litter -> Profile/Extent, across a namespace boundary. Proves the
  //    recursion is a real tree walk (arbitrary depth), not a hand-rolled "one extra level".
  // ------------------------------------------------------------------

  @Test
  fun `abiArgs flattens a struct nested THREE deep (Nursery, a different namespace than Litter)`() {
    val params = listOf(RirParameter(name = "n", type = nurseryType))

    val expected: List<AbiArg> = litterLeafReadPath.map { (path, type) ->
      AbiArg("n_Litter_$path", type, isOutPointer = false)
    } + AbiArg("n_Room", RirPrimitiveType("int"), isOutPointer = false)

    assertEquals(
      9,
      expected.size,
      "sanity: Nursery flattens to 9 leaves per ADR-059 (8 from Litter + Room)",
    )
    assertEquals(
      expected,
      abiArgs(params, structs),
      "abiArgs must be a genuine multi-level tree walk: Nursery -> Litter -> {Profile, Extent} " +
          "-> scalars, three levels deep, spanning a C# namespace boundary (Test.Nested vs " +
          "Test.Structs) that abiArgs itself is namespace-agnostic about (it only cares about " +
          "the structs map's keys)",
    )
  }

  // ------------------------------------------------------------------
  // 5. contractHash already recurses arbitrarily deep "for free" (signaturePart's RirStructType
  //    branch calls itself on each component's OWN type, which may itself be a RirStructType) —
  //    ADR-059 says this needs NO code change. Pinned here so a future refactor cannot regress it
  //    silently; this test currently PASSES.
  // ------------------------------------------------------------------

  @Test
  fun `contractHash already expands a struct's components recursively, so a change TWO levels down changes a method that never mentions the inner struct`() {
    val describe = RirMethod(
      name = "Describe",
      isStatic = true,
      returnType = RirStringType(),
      parameters = listOf(RirParameter(name = "l", type = litterType)),
    )
    val cls = RirClass(name = "Litters", isStatic = true, methods = listOf(describe))
    val registrables: List<RirRegistrable> = listOf(RirRegistrable.Method(describe))

    val hashBefore: Long = contractHash(cls, registrables, structs)

    // Add a field to Profile — TWO levels below Litters.Describe's own signature, which never
    // mentions Profile by name at all.
    val widerProfile = profile.copy(
      components = profile.components + RirStructComponent(
        name = "extra", readName = "Extra", type = RirPrimitiveType("int"),
      ),
    )
    val widerStructs: Map<RirTypeKey, RirStruct> =
      structs + (RirTypeKey("Test.Structs", "Profile") to widerProfile)
    val hashAfter: Long = contractHash(cls, registrables, widerStructs)

    assertTrue(
      hashBefore != hashAfter,
      "ADR-059: signaturePart()'s existing RirStructType branch already recurses into a " +
          "component's own type, so a field added to Profile (nested two levels below " +
          "Litters.Describe(Litter): string) must change the contract hash with NO generator " +
          "code change — verify this stays true once abiArgs itself becomes recursive",
    )
  }

  // ------------------------------------------------------------------
  // 6. ABI-name distinctness: `_` is a legal C# identifier character, so the path join is not
  //    injective. Both generators must assert the ABI names they produce for one member are
  //    distinct and fail generation naming both components (ADR-059, "ABI-name collisions must
  //    fail generation"). No such check exists today, so abiArgs silently returns two AbiArgs with
  //    the same `name` — this assertFailsWith currently fails because nothing throws.
  // ------------------------------------------------------------------

  private val tag = RirStruct(
    name = "Tag",
    shape = RirStructShape.CONSTRUCTOR,
    components = listOf(
      RirStructComponent(name = "text", readName = "Text", type = RirStringType()),
      RirStructComponent(name = "weight", readName = "Weight", type = RirPrimitiveType("int")),
    ),
  )
  private val tagType = RirStructType(namespace = "Test.Structs", name = "Tag")

  // public readonly struct Bad(Tag tag, int tag_Text) { ... }  — flattens to p_Tag_Text (from
  // tag.Text) AND p_Tag_Text (from the sibling int component whose OWN readName is "Tag_Text").
  private val bad = RirStruct(
    name = "Bad",
    shape = RirStructShape.CONSTRUCTOR,
    components = listOf(
      RirStructComponent(name = "tag", readName = "Tag", type = tagType),
      RirStructComponent(name = "tagText", readName = "Tag_Text", type = RirPrimitiveType("int")),
    ),
  )
  private val badType = RirStructType(namespace = "Test.Structs", name = "Bad")
  private val badStructs: Map<RirTypeKey, RirStruct> = mapOf(
    RirTypeKey("Test.Structs", "Tag") to tag,
    RirTypeKey("Test.Structs", "Bad") to bad,
  )

  @Test
  fun `abiArgs must fail generation when a path join collides on two distinct components, naming both`() {
    val params = listOf(RirParameter(name = "p", type = badType))

    assertFailsWith<IllegalStateException>(
      "ADR-059: `Bad`'s Tag.Text component and its own Tag_Text component both flatten to the " +
          "ABI name `p_Tag_Text` — abiArgs must assert distinctness and fail generation naming " +
          "both `tag.text` and `tagText`, not silently emit two AbiArgs with the same `name` " +
          "(today's behaviour: no exception at all)",
    ) {
      abiArgs(params, badStructs)
    }
  }
}
