package io.github.xxfast.kotlin.native.nuget

import io.github.xxfast.kotlin.native.nuget.rir.RirAssembly
import io.github.xxfast.kotlin.native.nuget.rir.RirClass
import io.github.xxfast.kotlin.native.nuget.rir.RirConstructor
import io.github.xxfast.kotlin.native.nuget.rir.RirFile
import io.github.xxfast.kotlin.native.nuget.rir.RirGenericInstanceType
import io.github.xxfast.kotlin.native.nuget.rir.RirInstantiation
import io.github.xxfast.kotlin.native.nuget.rir.RirMethod
import io.github.xxfast.kotlin.native.nuget.rir.RirNamespace
import io.github.xxfast.kotlin.native.nuget.rir.RirParameter
import io.github.xxfast.kotlin.native.nuget.rir.RirPrimitiveType
import io.github.xxfast.kotlin.native.nuget.rir.RirProperty
import io.github.xxfast.kotlin.native.nuget.rir.RirStringType
import io.github.xxfast.kotlin.native.nuget.rir.RirTypeParameterType
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * ADR-072 walking skeleton: `Test.Boxes.Box<T>` mirrors the real fixture (`TestDependency/Boxes.cs`,
 * `Box<int>`/`Box<string>`/`Box<string?>` instantiations) at unit-test scale.
 *
 * EXPECTED TO FAIL as of this commit, for one of three reasons the report back to the caller must
 * distinguish:
 *   - the fixture itself does not compile (`RirClass.typeParameters`/`instantiations`,
 *     `RirInstantiation`, `RirTypeParameterType`, `RirGenericInstanceType` do not exist in
 *     `RirModel.kt` yet);
 *   - once the model exists (Step 4), `generateKotlinStubs`/`generateCSharpShims` do not yet route
 *     a `typeParameters.isNotEmpty()` `RirClass` down any generic-aware path at all, so the
 *     generated output will not match these assertions;
 *   - a routing gap that manifests as a thrown exception (e.g. a `when` over `RirTypeRef` hitting
 *     an unhandled `RirTypeParameterType`/`RirGenericInstanceType` branch) rather than wrong text.
 */
class NugetGenericClassGenerationTest {

  // ------------------------------------------------------------------
  // Fixture: Box<T> with three instantiations (int, string, string?), mirrors the ADR's own
  // Decision 1 code sample and the real TestDependency/Boxes.cs fixture.
  // ------------------------------------------------------------------

  private val boxNamespace = "Test.Boxes"
  private val boxTypeParamT = RirTypeParameterType(index = 0, name = "T")

  private fun boxReturningItself(): RirGenericInstanceType = RirGenericInstanceType(
    namespace = boxNamespace, name = "Box`1", typeArguments = listOf(boxTypeParamT),
  )

  private val box = RirClass(
    name = "Box`1",
    typeParameters = listOf("T"),
    constructors = listOf(
      RirConstructor(parameters = listOf(RirParameter(name = "value", type = boxTypeParamT))),
    ),
    properties = listOf(
      RirProperty(name = "Value", type = boxTypeParamT, isReadOnly = true, isStatic = false),
    ),
    methods = listOf(
      RirMethod(name = "Describe", returnType = RirStringType(), parameters = emptyList()),
      RirMethod(name = "Rewrap", returnType = boxReturningItself(), parameters = emptyList()),
    ),
    instantiations = listOf(
      RirInstantiation(typeArguments = listOf(RirPrimitiveType("int"))),
      RirInstantiation(typeArguments = listOf(RirStringType(nullable = false))),
      RirInstantiation(typeArguments = listOf(RirStringType(nullable = true))),
    ),
  )

  // A definition with zero discovered instantiations: the Box`1-leak regression fixture
  // (Decision 10 / Context item 2).
  private val unused = RirClass(
    name = "Unused`1",
    typeParameters = listOf("T"),
    constructors = listOf(
      RirConstructor(parameters = listOf(RirParameter(name = "value", type = RirTypeParameterType(0, "T")))),
    ),
    properties = listOf(
      RirProperty(
        name = "Value", type = RirTypeParameterType(0, "T"), isReadOnly = true, isStatic = false,
      ),
    ),
    instantiations = emptyList(),
  )

  private val rir = RirFile(
    assemblies = listOf(
      RirAssembly(
        packageId = "TestDependency",
        assemblyName = "TestDependency",
        namespaces = listOf(RirNamespace(name = boxNamespace, types = listOf(box, unused))),
      ),
    ),
  )

  // ------------------------------------------------------------------
  // Decision 1: shape. One Kotlin generic class, over an erased handle, with a per-instantiation
  // witness. EVERY member goes through the witness, including a T-FREE member (describe()).
  // ------------------------------------------------------------------

  @Test
  fun `Box class is a real Kotlin generic class over an erased handle, dispatching every member through a witness`() {
    val files: List<GeneratedFile> = generateKotlinStubs(rir)
    val file: GeneratedFile = files.single { it.relativePath.endsWith("/Box.kt") }

    assertContains(
      file.content,
      "class Box<T>",
      message = "Decision 10: the emitted simple name strips the CLR arity suffix, never `class Box`1`",
    )
    assertFalse(
      file.content.contains("`1"),
      "the Box`1 leak (Context item 2) must be fixed: no backtick-mangled name anywhere in " +
          "generated Kotlin source",
    )
    assertContains(file.content, "val value: T")
    assertContains(
      file.content, "fun describe(): String",
      message = "Decision 1, forced by CS8895: describe() mentions no T at all, but still needs its own " +
          "per-instantiation witness dispatch, not a shared thunk",
    )
    assertContains(file.content, "fun rewrap(): Box<T>")
  }

  @Test
  fun `BoxBridge is a per-definition witness interface generic over T, declaring every member including the T-free one`() {
    val files: List<GeneratedFile> = generateKotlinStubs(rir)
    val file: GeneratedFile = files.single { it.relativePath.endsWith("/BoxBridge.kt") }

    assertContains(file.content, "interface BoxBridge<T>")
    assertContains(file.content, "fun value(")
    assertContains(
      file.content, "fun describe(",
      message = "the T-free member must still be declared on the witness: CS8895 forces every member " +
          "through a per-instantiation thunk, describe() included",
    )
    assertContains(file.content, "fun rewrap(")
  }

  @Test
  fun `one witness object per closed instantiation, BoxOfIntBridge and BoxOfStringBridge both exist and implement BoxBridge with the concrete type argument`() {
    val files: List<GeneratedFile> = generateKotlinStubs(rir)

    val boxOfInt: GeneratedFile = files.single { it.relativePath.endsWith("/BoxOfIntBridge.kt") }
    assertContains(boxOfInt.content, "object BoxOfIntBridge : BoxBridge<Int>")

    val boxOfString: GeneratedFile =
      files.single { it.relativePath.endsWith("/BoxOfStringBridge.kt") }
    assertContains(boxOfString.content, "object BoxOfStringBridge : BoxBridge<String>")
  }

  // ------------------------------------------------------------------
  // Decision 5: construction, and the ambiguity rule (order-independent).
  // ------------------------------------------------------------------

  @Test
  fun `Box of Int gets an unambiguous top-level fake constructor named exactly like the class`() {
    val files: List<GeneratedFile> = generateKotlinStubs(rir)
    val file: GeneratedFile = files.single { it.relativePath.endsWith("/Box.kt") }

    // Decision 5, verified to compile in this project's nativeMain: `Box(42)`, NOT `Box.ofInt(42)`.
    assertContains(file.content, "fun Box(value: Int): Box<Int>")
  }

  @Test
  fun `Box of String and Box of nullable String both lose their fake constructor to the ambiguity rule, regardless of declaration order`() {
    // Box<String> and Box<String?> both erase to a (String) parameter list. Decision 5 requires
    // BOTH to be skipped, and the outcome must not depend on iteration order. Assert both orders of
    // the SAME two instantiations produce the identical (ambiguous, both-skipped) outcome.
    val forwardOrder = box.copy(
      instantiations = listOf(
        RirInstantiation(typeArguments = listOf(RirStringType(nullable = false))),
        RirInstantiation(typeArguments = listOf(RirStringType(nullable = true))),
      ),
    )
    val reverseOrder = box.copy(
      instantiations = listOf(
        RirInstantiation(typeArguments = listOf(RirStringType(nullable = true))),
        RirInstantiation(typeArguments = listOf(RirStringType(nullable = false))),
      ),
    )

    for (cls in listOf(forwardOrder, reverseOrder)) {
      val fileForOrder = RirFile(
        assemblies = listOf(
          RirAssembly(
            packageId = "TestDependency",
            assemblyName = "TestDependency",
            namespaces = listOf(RirNamespace(name = boxNamespace, types = listOf(cls))),
          ),
        ),
      )
      val files: List<GeneratedFile> = generateKotlinStubs(fileForOrder)
      val boxFile: GeneratedFile = files.single { it.relativePath.endsWith("/Box.kt") }

      assertFalse(
        boxFile.content.contains("fun Box(value: String)"),
        "both Box<String> and Box<String?> erase to (String) and must be skipped, in either " +
            "declaration order, got:\n${boxFile.content}",
      )

      val warnings: List<String> = diagnosticWarnings(fileForOrder)
      assertTrue(
        warnings.any { it.contains("ambiguous", ignoreCase = true) },
        "expected a skipped_ambiguous_generic_constructor warning for the (String) collision, " +
            "got $warnings",
      )
    }
  }

  // ------------------------------------------------------------------
  // Decision 10: names, and the Box`1-leak fix.
  // ------------------------------------------------------------------

  @Test
  fun `internal instantiation-tagged names follow OfXAndY, with Nullable prefix for a nullable reference type argument`() {
    val files: List<GeneratedFile> = generateKotlinStubs(rir)

    assertTrue(files.any { it.relativePath.endsWith("/BoxOfIntBridge.kt") })
    assertTrue(files.any { it.relativePath.endsWith("/BoxOfStringBridge.kt") })
    // Box<string?> is ambiguous with Box<string> per Decision 5 and loses its FAKE CONSTRUCTOR, but
    // it is still a real, separately-tagged instantiation (reachable via Boxes.ofMaybeText), so its
    // witness/bindings/registration must still be tagged BoxOfNullableString, not merged with
    // BoxOfStringBridge.
    assertTrue(
      files.any { it.relativePath.endsWith("/BoxOfNullableStringBridge.kt") },
      "Box<string?> must get its own BoxOfNullableStringBridge witness, distinct from " +
          "BoxOfStringBridge, even though its fake constructor is skipped. Got " +
          files.map { it.relativePath },
    )
  }

  // ------------------------------------------------------------------
  // ADR-053/ADR-070 failure class, third instance (ADR-072): a witness's SUBSTITUTED nullable
  // return must map a null pointer to Kotlin `null`, never guard it with requireNotNull. Regression
  // for the runtime failure `BoxesRoundTripTests.BoxOfMaybeText_Null_StaysGenuinelyNull` hit:
  // `Box<string?>.value` threw "Box.value returned null" instead of returning null.
  // ------------------------------------------------------------------

  @Test
  fun `BoxOfNullableStringBridge value returns null on a null pointer instead of guarding`() {
    val files: List<GeneratedFile> = generateKotlinStubs(rir)
    val file: GeneratedFile =
      files.single { it.relativePath.endsWith("/BoxOfNullableStringBridge.kt") }

    assertContains(
      file.content, "fun value(handle: NugetObjectHandle): String?",
      message = "the witness's declared return type must stay nullable",
    )
    assertFalse(
      file.content.contains("requireNotNull(valueFn) { NugetRegistry.notRegistered(\"Test.Boxes.Box[System.String?]\", \"TestDependency\") }.invoke(handle.require(\"Box\")))"),
      "a nullable-annotated string return must never be wrapped in an outer requireNotNull that " +
          "throws on a legitimate null. Got:\n${file.content}",
    )
  }

  @Test
  fun `BoxOfStringBridge value keeps its non-null guard, unlike the nullable sibling`() {
    val files: List<GeneratedFile> = generateKotlinStubs(rir)
    val file: GeneratedFile = files.single { it.relativePath.endsWith("/BoxOfStringBridge.kt") }

    assertContains(file.content, "fun value(handle: NugetObjectHandle): String")
    assertFalse(
      file.content.contains("String?"),
      "Box<string>'s witness must stay non-null throughout. Got:\n${file.content}",
    )
    assertContains(
      file.content, "\"Box.value returned null\"",
      message = "a NON-nullable string return must keep the fail-fast requireNotNull guard",
    )
  }

  @Test
  fun `a generic definition with zero discovered instantiations emits nothing at all, the Box backtick-1 leak regression`() {
    val files: List<GeneratedFile> = generateKotlinStubs(rir)

    assertFalse(
      files.any { it.relativePath.contains("Unused") },
      "Decision 10: Unused<T> has zero discovered instantiations and must emit NO Kotlin type, " +
          "no witness, no bindings, no registration export at all. Got " +
          files.map { it.relativePath },
    )
    assertFalse(
      files.any { it.content.contains("`1") },
      "the Box`1 leak this feature fixes: no generated Kotlin source may ever contain a literal " +
          "backtick-mangled CLR arity suffix",
    )
  }

  @Test
  fun `a namespace declaring both Box and Box backtick-1 fails generation with error_generic_arity_name_collision`() {
    val nonGenericBox = RirClass(name = "Box", constructors = emptyList())
    val collidingFile = RirFile(
      assemblies = listOf(
        RirAssembly(
          packageId = "TestDependency",
          assemblyName = "TestDependency",
          namespaces = listOf(
            RirNamespace(name = boxNamespace, types = listOf(box, nonGenericBox)),
          ),
        ),
      ),
    )

    var threw = false
    try {
      generateKotlinStubs(collidingFile)
    } catch (e: Exception) {
      threw = true
    }
    assertTrue(
      threw,
      "Decision 10: `Box` and `Box`1` in the same namespace must fail generation with " +
          "error_generic_arity_name_collision (ADR-057's error_kotlin_signature_collision " +
          "precedent), not silently pick one",
    )
  }

  // ------------------------------------------------------------------
  // Decision 8: variance, constraints, arity. Type parameter names verbatim, no constraints
  // emitted even for a `where T : class` definition.
  // ------------------------------------------------------------------

  @Test
  fun `Pairing arity 2 type parameter names TKey and TValue reach Kotlin verbatim`() {
    val pairing = RirClass(
      name = "Pairing`2",
      typeParameters = listOf("TKey", "TValue"),
      constructors = listOf(
        RirConstructor(
          parameters = listOf(
            RirParameter(name = "key", type = RirTypeParameterType(0, "TKey")),
            RirParameter(name = "value", type = RirTypeParameterType(1, "TValue")),
          ),
        ),
      ),
      properties = listOf(
        RirProperty(
          name = "Key", type = RirTypeParameterType(0, "TKey"), isReadOnly = true, isStatic = false,
        ),
        RirProperty(
          name = "Value", type = RirTypeParameterType(1, "TValue"), isReadOnly = true,
          isStatic = false,
        ),
      ),
      instantiations = listOf(
        RirInstantiation(typeArguments = listOf(RirStringType(), RirPrimitiveType("int"))),
      ),
    )
    val file = RirFile(
      assemblies = listOf(
        RirAssembly(
          packageId = "TestDependency", assemblyName = "TestDependency",
          namespaces = listOf(RirNamespace(name = boxNamespace, types = listOf(pairing))),
        ),
      ),
    )

    val files: List<GeneratedFile> = generateKotlinStubs(file)
    val pairingFile: GeneratedFile = files.single { it.relativePath.endsWith("/Pairing.kt") }

    assertContains(pairingFile.content, "class Pairing<TKey, TValue>")
  }

  @Test
  fun `Crate constrained by where T colon class emits no Kotlin type-parameter constraint at all`() {
    val crate = RirClass(
      name = "Crate`1",
      typeParameters = listOf("T"),
      constructors = listOf(
        RirConstructor(
          parameters = listOf(RirParameter(name = "item", type = RirTypeParameterType(0, "T"))),
        ),
      ),
      properties = listOf(
        RirProperty(
          name = "Item", type = RirTypeParameterType(0, "T"), isReadOnly = true, isStatic = false,
        ),
      ),
      instantiations = listOf(RirInstantiation(typeArguments = listOf(RirStringType()))),
    )
    val file = RirFile(
      assemblies = listOf(
        RirAssembly(
          packageId = "TestDependency", assemblyName = "TestDependency",
          namespaces = listOf(RirNamespace(name = boxNamespace, types = listOf(crate))),
        ),
      ),
    )

    val files: List<GeneratedFile> = generateKotlinStubs(file)
    val crateFile: GeneratedFile = files.single { it.relativePath.endsWith("/Crate.kt") }

    assertContains(crateFile.content, "class Crate<T>")
    assertFalse(
      crateFile.content.contains("T : Any") || crateFile.content.contains("where T"),
      "Decision 8: v1 emits NO Kotlin type-parameter constraints, even though the metadata " +
          "carries ReferenceTypeConstraint. Got:\n${crateFile.content}",
    )
  }

  // ------------------------------------------------------------------
  // C# side (NugetGenerateShimsTask): Decision 4, one registration export/class PER
  // INSTANTIATION, not per definition; same slot count, differing contract hash.
  // ------------------------------------------------------------------

  @Test
  fun `one registration export and one registration class per instantiation, named by the instantiation tag`() {
    val files: List<GeneratedFile> = generateCSharpShims(rir, nativeLibraryName = "sample")

    val boxOfInt: GeneratedFile =
      files.single { it.relativePath == "BoxOfIntRegistration.cs" }
    assertContains(boxOfInt.content, "internal static class BoxOfIntRegistration")
    assertContains(boxOfInt.content, "nuget_test_boxes_box_of_int_register")
    assertContains(
      boxOfInt.content, "(Box<int>)",
      message = "Decision 4/CS8895: the thunk must cast the receiver to the CONCRETE closed instantiation",
    )

    val boxOfString: GeneratedFile =
      files.single { it.relativePath == "BoxOfStringRegistration.cs" }
    assertContains(boxOfString.content, "internal static class BoxOfStringRegistration")
    assertContains(boxOfString.content, "nuget_test_boxes_box_of_string_register")
    assertContains(boxOfString.content, "(Box<string>)")

    assertNotEquals(
      boxOfInt.relativePath, boxOfString.relativePath,
      "one registration export per closed instantiation, never a shared one per definition",
    )
  }

  @Test
  fun `Box of Int and Box of String have the same slot count but different contract hashes`() {
    val files: List<GeneratedFile> = generateCSharpShims(rir, nativeLibraryName = "sample")

    val boxOfInt: String =
      files.single { it.relativePath == "BoxOfIntRegistration.cs" }.content
    val boxOfString: String =
      files.single { it.relativePath == "BoxOfStringRegistration.cs" }.content

    val slotCountRegex = Regex("""nuget_test_boxes_box_of_\w+_register\((\d+),""")
    val slotCountInt: String = slotCountRegex.find(boxOfInt)!!.groupValues[1]
    val slotCountString: String = slotCountRegex.find(boxOfString)!!.groupValues[1]
    assertEquals(
      slotCountInt, slotCountString,
      "Decision 4: substitution never reorders or changes arity, every instantiation of one " +
          "definition has the SAME slot count",
    )

    val hashRegex = Regex("""_register\(\d+,\s*(-?\d+)L""")
    val hashInt: String = hashRegex.find(boxOfInt)!!.groupValues[1]
    val hashString: String = hashRegex.find(boxOfString)!!.groupValues[1]
    assertNotEquals(
      hashInt, hashString,
      "Decision 4, directly pinning the else -> describe() bug: same slot count must not mean " +
          "same contract hash. The canonical instantiation signature name AND every substituted " +
          "signaturePart must differ per instantiation",
    )
  }

  @Test
  fun `Unused with zero instantiations emits no C# registration class at all`() {
    val files: List<GeneratedFile> = generateCSharpShims(rir, nativeLibraryName = "sample")

    assertFalse(files.any { it.relativePath.contains("Unused") })
  }

  // ------------------------------------------------------------------
  // ADR-072 gap: an ORDINARY (non-generic) class member that returns, takes, or exposes a closed
  // Box<T> instantiation. The RirBridging.isV1Type RirGenericInstanceType branch used to fail
  // closed (`false`) for exactly this shape, dropping the member with no diagnostic at all. This
  // is the seam Decision 5's fake-constructor ambiguity rule leans on: Box<String> and
  // Box<String?> both lose their fake constructor, so `Boxes.ofText`/`Boxes.ofMaybeText` are the
  // ONLY route to a `Box<String>` value at all.
  // ------------------------------------------------------------------

  private val boxesContainer = RirClass(
    name = "Boxes",
    isStatic = true,
    methods = listOf(
      // RETURN position: Box<String> is unreachable via a fake constructor (Decision 5), so this
      // is the only route to it, forces type-argument marshalling to actually exist.
      RirMethod(
        name = "OfText",
        isStatic = true,
        returnType = RirGenericInstanceType(
          namespace = boxNamespace, name = "Box`1",
          typeArguments = listOf(RirStringType(nullable = false)),
        ),
        parameters = listOf(RirParameter(name = "value", type = RirStringType(nullable = false))),
      ),
      // PARAMETER position.
      RirMethod(
        name = "Unwrap",
        isStatic = true,
        returnType = RirPrimitiveType("int"),
        parameters = listOf(
          RirParameter(
            name = "box",
            type = RirGenericInstanceType(
              namespace = boxNamespace, name = "Box`1",
              typeArguments = listOf(RirPrimitiveType("int")),
            ),
          ),
        ),
      ),
    ),
    properties = listOf(
      // PROPERTY position.
      RirProperty(
        name = "Sample",
        type = RirGenericInstanceType(
          namespace = boxNamespace, name = "Box`1", typeArguments = listOf(RirPrimitiveType("int")),
        ),
        isReadOnly = true,
        isStatic = true,
      ),
    ),
  )

  private val rirWithOrdinaryGenericMembers = RirFile(
    assemblies = listOf(
      RirAssembly(
        packageId = "TestDependency",
        assemblyName = "TestDependency",
        namespaces = listOf(
          RirNamespace(name = boxNamespace, types = listOf(box, unused, boxesContainer)),
        ),
      ),
    ),
  )

  @Test
  fun `an ordinary class member returning Box of string constructs the wrapper with the BoxOfStringBridge witness`() {
    val files: List<GeneratedFile> = generateKotlinStubs(rirWithOrdinaryGenericMembers)
    val boxesFile: GeneratedFile = files.single { it.relativePath.endsWith("/Boxes.kt") }

    assertContains(
      boxesFile.content, "fun ofText(value: String): Box<String>",
      message = "an ordinary member returning a bridgeable closed instantiation must no longer be " +
          "dropped by the shared v1-bridgeable filter. Got:\n${boxesFile.content}",
    )
    assertContains(
      boxesFile.content, "BoxOfStringBridge)",
      message = "Decision 1: the wrapper must be constructed with THIS position's own " +
          "per-instantiation witness, chosen entirely at generation time",
    )
  }

  @Test
  fun `an ordinary class member taking Box of int as a parameter unwraps it via handle require, no witness needed`() {
    val files: List<GeneratedFile> = generateKotlinStubs(rirWithOrdinaryGenericMembers)
    val boxesFile: GeneratedFile = files.single { it.relativePath.endsWith("/Boxes.kt") }

    assertContains(boxesFile.content, "fun unwrap(box: Box<Int>): Int")
    assertContains(
      boxesFile.content, "box.handle.require(\"Box\")",
      message = "a generic-instantiation PARAMETER is wire-identical to a handle parameter, " +
          "unwrap via .handle.require(...), never re-dispatched through a witness",
    )
  }

  @Test
  fun `a static property typed as a Box of int instantiation picks the BoxOfIntBridge witness`() {
    val files: List<GeneratedFile> = generateKotlinStubs(rirWithOrdinaryGenericMembers)
    val boxesFile: GeneratedFile = files.single { it.relativePath.endsWith("/Boxes.kt") }

    assertContains(boxesFile.content, "val sample: Box<Int>")
    assertContains(boxesFile.content, "BoxOfIntBridge)")
  }

  @Test
  fun `the C# shim thunk names the closed instantiation concretely, both as a parameter cast and a return type`() {
    val files: List<GeneratedFile> =
      generateCSharpShims(rirWithOrdinaryGenericMembers, nativeLibraryName = "sample")
    val boxesRegistration: GeneratedFile = files.single { it.relativePath == "BoxesRegistration.cs" }

    assertContains(
      boxesRegistration.content, "(Box<int>)",
      message = "a Box<int> PARAMETER unwraps via a cast to the CONCRETE closed instantiation, " +
          "never the open definition",
    )
    assertContains(
      boxesRegistration.content, "Box<string>? result",
      message = "a Box<string> RETURN names the concrete closed instantiation",
    )
  }

  @Test
  fun `an instantiation never discovered by the reader is skipped, not admitted, even though its definition is bound`() {
    val undiscovered = RirMethod(
      name = "NeverSeen",
      isStatic = true,
      returnType = RirGenericInstanceType(
        namespace = boxNamespace, name = "Box`1", typeArguments = listOf(RirPrimitiveType("long")),
      ),
      parameters = emptyList(),
    )
    val boxesWithUndiscovered = boxesContainer.copy(methods = boxesContainer.methods + undiscovered)
    val file = RirFile(
      assemblies = listOf(
        RirAssembly(
          packageId = "TestDependency", assemblyName = "TestDependency",
          namespaces = listOf(
            RirNamespace(name = boxNamespace, types = listOf(box, boxesWithUndiscovered)),
          ),
        ),
      ),
    )

    val files: List<GeneratedFile> = generateKotlinStubs(file)
    val boxesFile: GeneratedFile = files.single { it.relativePath.endsWith("/Boxes.kt") }

    assertFalse(
      boxesFile.content.contains("neverSeen"),
      "Box<long> was never discovered by the reader's Decision 2 pass (box.instantiations has no " +
          "long entry). It must be SKIPPED, not admitted, even though Box`1 itself is bound",
    )
  }
}
