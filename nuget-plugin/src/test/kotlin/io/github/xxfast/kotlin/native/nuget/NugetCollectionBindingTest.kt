package io.github.xxfast.kotlin.native.nuget

import io.github.xxfast.kotlin.native.nuget.rir.RirAssembly
import io.github.xxfast.kotlin.native.nuget.rir.RirClass
import io.github.xxfast.kotlin.native.nuget.rir.RirCollectionKind
import io.github.xxfast.kotlin.native.nuget.rir.RirCollectionType
import io.github.xxfast.kotlin.native.nuget.rir.RirConstructor
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
import io.github.xxfast.kotlin.native.nuget.rir.RirStruct
import io.github.xxfast.kotlin.native.nuget.rir.RirStructComponent
import io.github.xxfast.kotlin.native.nuget.rir.RirStructType
import io.github.xxfast.kotlin.native.nuget.rir.RirTypeRef
import io.github.xxfast.kotlin.native.nuget.rir.RirVoidType
import io.github.xxfast.kotlin.native.nuget.rir.boundHandleTypes
import io.github.xxfast.kotlin.native.nuget.rir.boundInterfaceTypes
import io.github.xxfast.kotlin.native.nuget.rir.bridgeableRegistrables
import io.github.xxfast.kotlin.native.nuget.rir.contractHash
import io.github.xxfast.kotlin.native.nuget.rir.parseReverseIr
import io.github.xxfast.kotlin.native.nuget.rir.slotCount
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * ADR-155: a bound C# member that returns or takes a BCL collection crosses as an eagerly copied
 * READ-ONLY Kotlin collection (`List<T>` / `Set<T>` / `Map<K,V>`, never `MutableList` and friends)
 * over one pointer to a `[count][8-byte slots]` buffer.
 *
 * Both generators are driven off ONE hand-built fixture here, the `NugetAsyncBindingTest` pattern:
 * the failure no single-sided test can see is the two of them disagreeing about how many slots a
 * type occupies, or about which declared C# type the shim casts its container to.
 *
 * Every family below carries one element that needs conversion (`string`) and one that does not
 * (`int`). That the REAL reader emits this RIR for real metadata is asserted separately, against a
 * compiled assembly, in `NugetExtractApiIntegrationTest`.
 */
class NugetCollectionBindingTest {

  private fun list(element: RirTypeRef, definition: String, nullable: Boolean = false) =
    RirCollectionType(
      collection = RirCollectionKind.LIST,
      definition = "System.Collections.Generic.$definition",
      typeArguments = listOf(element),
      nullable = nullable,
    )

  private fun set(element: RirTypeRef, definition: String) = RirCollectionType(
    collection = RirCollectionKind.SET,
    definition = "System.Collections.Generic.$definition",
    typeArguments = listOf(element),
  )

  private fun map(key: RirTypeRef, value: RirTypeRef, definition: String) = RirCollectionType(
    collection = RirCollectionKind.MAP,
    definition = "System.Collections.Generic.$definition",
    typeArguments = listOf(key, value),
  )

  private val int = RirPrimitiveType("int")
  private val tag = RirObjectHandleType("Test.Roster", "Tag")
  private val labelled = RirInterfaceType("Test.Roster", "ILabelled")
  private val mood = RirEnumType("Test.Roster", "Mood")

  private val roster = RirClass(
    name = "Roster",
    constructors = listOf(
      RirConstructor(
        parameters = listOf(
          RirParameter("seats", list(int, "IReadOnlyList`1")),
          RirParameter("labels", list(RirStringType(), "IEnumerable`1")),
        ),
        managedSignature = "Roster(IReadOnlyList<Int32>,IEnumerable<String>)",
      ),
    ),
    properties = listOf(
      RirProperty("Names", list(RirStringType(), "IReadOnlyList`1")),
      RirProperty("Ages", list(int, "IList`1"), isReadOnly = false),
    ),
    methods = listOf(
      // A member that binds today, so a missing collection member is visibly a missing MEMBER and
      // not a missing file.
      RirMethod(name = "Describe", returnType = RirStringType()),
      RirMethod(
        name = "Nicknames",
        returnType = list(RirStringType(nullable = true), "IReadOnlyList`1"),
      ),
      RirMethod(
        name = "MaybeNames",
        returnType = list(RirStringType(), "IReadOnlyList`1", nullable = true),
      ),
      RirMethod(name = "Scores", returnType = map(RirStringType(), int, "IReadOnlyDictionary`2")),
      RirMethod(
        name = "Rank",
        returnType = RirVoidType,
        parameters = listOf(RirParameter("scores", map(RirStringType(), int, "IDictionary`2"))),
      ),
      RirMethod(name = "Labels", returnType = set(RirStringType(), "ISet`1")),
      RirMethod(name = "Sizes", returnType = set(int, "IReadOnlySet`1")),
      RirMethod(name = "Tags", returnType = list(tag, "IList`1")),
      RirMethod(name = "Feeders", returnType = list(labelled, "IReadOnlyList`1")),
      RirMethod(name = "Moods", returnType = list(mood, "IReadOnlyList`1")),
      RirMethod(
        name = "Enroll",
        returnType = int,
        parameters = listOf(RirParameter("names", list(RirStringType(), "IEnumerable`1"))),
      ),
      RirMethod(
        name = "Total",
        returnType = int,
        parameters = listOf(RirParameter("ages", list(int, "IList`1"))),
      ),
      // The overload pair that pins the shim cast. Uncast, the first thunk is CS0121 (ambiguous);
      // the second parameter is what keeps the pair from collapsing to one Kotlin signature.
      RirMethod(
        name = "Pick",
        returnType = RirStringType(),
        parameters = listOf(
          RirParameter("xs", list(int, "IList`1")),
          RirParameter("tag", tag),
        ),
        managedSignature = "Pick(IList<Int32>,Tag)",
      ),
      RirMethod(
        name = "Pick",
        returnType = RirStringType(),
        parameters = listOf(
          RirParameter("xs", list(int, "List`1")),
          RirParameter("labelled", labelled),
        ),
        managedSignature = "Pick(List<Int32>,ILabelled)",
      ),
    ),
  )

  private val tagClass = RirClass(
    name = "Tag",
    properties = listOf(RirProperty("Label", RirStringType())),
    interfaces = listOf("Test.Roster.ILabelled"),
  )

  private val labelledInterface = RirInterface(
    name = "ILabelled",
    properties = listOf(RirProperty("Label", RirStringType())),
  )

  private val moodEnum = RirEnum(
    name = "Mood",
    entries = listOf(RirEnumEntry("HAPPY", 0), RirEnumEntry("SLEEPY", 1)),
  )

  private val rir = RirFile(
    assemblies = listOf(
      RirAssembly(
        packageId = "TestDependency",
        assemblyName = "TestDependency",
        namespaces = listOf(
          RirNamespace(
            name = "Test.Roster",
            types = listOf(roster, tagClass, labelledInterface, moodEnum),
          ),
        ),
      ),
    ),
  )

  private fun stub(): String = generateKotlinStubs(rir)
    .single { it.relativePath.endsWith("/Roster.kt") }.content

  private fun bindings(): String = generateKotlinStubs(rir)
    .single { it.relativePath.endsWith("RosterBindings.kt") }.content

  private fun shim(): String = generateCSharpShims(rir, "sample")
    .single { it.relativePath.endsWith("RosterRegistration.cs") }.content

  // ---------------------------------------------------------------- Kotlin stubs

  @Test
  fun `a list return binds as a read-only Kotlin List`() {
    val stub: String = stub()

    // `string` needs a slot conversion, `int` does not; both are List<T> in Kotlin.
    assertContains(stub, "fun nicknames(): List<String?>")
    assertContains(stub, "fun sizes(): Set<Int>")
    assertContains(stub, "fun labels(): Set<String>")
    // IList<Tag> in C#: still a read-only copy here, never MutableList (ADR-155 Alternative 5).
    assertContains(stub, "fun tags(): List<Tag>")
    assertFalse(
      stub.contains("Mutable"),
      "the value is an eager copy, so a mutable Kotlin type would let `add(x)` compile and " +
          "change nothing in C#",
    )
  }

  @Test
  fun `a nullable collection and a nullable element are different question marks`() {
    val stub: String = stub()

    // The collection itself is null (IntPtr.Zero), the elements are not.
    assertContains(stub, "fun maybeNames(): List<String>?")
    // The collection is non-null, an element slot may be 0.
    assertContains(stub, "fun nicknames(): List<String?>")
  }

  @Test
  fun `a map binds as a read-only Kotlin Map at both positions`() {
    val stub: String = stub()

    assertContains(stub, "fun scores(): Map<String, Int>")
    assertContains(stub, "fun rank(scores: Map<String, Int>)")
  }

  @Test
  fun `a collection parameter is a read-only Kotlin type built inside the existing memScoped`() {
    val stub: String = stub()

    assertContains(stub, "fun enroll(names: List<String>): Int")
    assertContains(stub, "fun total(ages: List<Int>): Int")
    assertContains(
      stub,
      "memScoped",
      message = "the parameter buffer lives in the scope the stub already opens for " +
          ".cstr.ptr arguments",
    )
  }

  @Test
  fun `a handle, interface and enum element each bind as their own Kotlin element type`() {
    val stub: String = stub()

    assertContains(stub, "fun tags(): List<Tag>")
    assertContains(stub, "fun feeders(): List<ILabelled>")
    assertContains(stub, "fun moods(): List<Mood>")
  }

  @Test
  fun `a collection typed property binds at get and set`() {
    val stub: String = stub()

    assertContains(stub, "val names: List<String>")
    assertContains(stub, "var ages: List<Int>")
    assertContains(stub, "set(value)")
  }

  @Test
  fun `a collection typed constructor parameter binds`() {
    val stub: String = stub()

    assertContains(stub, "seats: List<Int>")
    assertContains(stub, "labels: List<String>")
  }

  @Test
  fun `a collection crosses as exactly one pointer per collection`() {
    val bindings: String = bindings()

    // Wire-identical to a handle for arity: one COpaquePointer?, whatever the element count.
    assertContains(
      bindings,
      "internal var nicknamesFn: CPointer<CFunction<(COpaquePointer?, " +
          "CPointer<COpaquePointerVar>) -> COpaquePointer?>>? = null",
    )
    assertContains(
      bindings,
      "internal var enrollFn: CPointer<CFunction<(COpaquePointer?, COpaquePointer?, " +
          "CPointer<COpaquePointerVar>) -> Int>>? = null",
    )
  }

  // ---------------------------------------------------------------- C# shims

  @Test
  fun `a collection return is written into one slot buffer by the shared helper`() {
    val shim: String = shim()

    assertContains(shim, "NugetCollections.Write(")
    assertContains(shim, "NugetCollections.WriteMap(")
    // One pointer out, for the element that converts and the one that does not alike.
    assertContains(
      shim, "private static unsafe IntPtr Nicknames_Thunk(IntPtr selfHandle, IntPtr* errOut)",
    )
    assertContains(
      shim, "private static unsafe IntPtr Sizes_Thunk(IntPtr selfHandle, IntPtr* errOut)",
    )
  }

  @Test
  fun `the shim casts the container it built to the declared C# definition`() {
    val shim: String = shim()

    // Without the cast, Pick(IList<int>, Tag) beside Pick(List<int>, ILabelled) is CS0121 at the
    // call site: the shim does not compile. This is why RirCollectionType carries `definition`.
    assertContains(shim, "(global::System.Collections.Generic.IList<int>)")
    assertContains(shim, "(global::System.Collections.Generic.List<int>)")
    assertContains(shim, "(global::System.Collections.Generic.IEnumerable<string>)")
    assertContains(shim, "(global::System.Collections.Generic.IDictionary<string, int>)")
  }

  @Test
  fun `a collection typed property crosses as one pointer in each direction`() {
    val shim: String = shim()

    assertContains(
      shim, "private static unsafe IntPtr Names_Get_Thunk(IntPtr selfHandle, IntPtr* errOut)",
    )
    assertContains(
      shim, "private static unsafe IntPtr Ages_Get_Thunk(IntPtr selfHandle, IntPtr* errOut)",
    )
    assertContains(shim, "private static unsafe void Ages_Set_Thunk(IntPtr selfHandle, IntPtr ")
  }

  @Test
  fun `the runtime registration carries the one shared collection helper`() {
    val runtime: String = generateCSharpShims(rir, "sample")
      .single { it.relativePath.endsWith("NugetRuntimeRegistration.cs") }.content

    assertContains(runtime, "internal static unsafe class NugetCollections")
    assertContains(runtime, "Marshal.AllocCoTaskMem(")
    assertFalse(
      runtime.contains("nuget_list_create"),
      "the reverse path must not call the FORWARD nuget_list_* exports: those add " +
          "StableRef-boxed KOTLIN objects, which a C# element is not",
    )
  }

  // ---------------------------------------------------------------- contract

  @Test
  fun `both generators agree on the slot count`() {
    // 1 ctor + 14 methods + Names getter + Ages getter/setter. Both sides must say the same
    // number, or ADR-054's contract check fails every consumer at startup.
    assertEquals(
      18,
      bridgeableRegistrables(
        roster, boundHandleTypes(rir), boundInterfaceTypes = boundInterfaceTypes(rir),
      ).slotCount(),
    )
    assertContains(bindings(), "expectedSlots = 18,")
    assertContains(shim(), "nuget_test_roster_roster_register(\n" + " ".repeat(20) + "18,")
  }

  @Test
  fun `element nullability moves the contract hash`() {
    fun classOf(element: RirTypeRef): RirClass = RirClass(
      name = "Roster",
      methods = listOf(RirMethod(name = "Names", returnType = list(element, "IReadOnlyList`1"))),
    )

    val nonNull: RirClass = classOf(RirStringType())
    val nullable: RirClass = classOf(RirStringType(nullable = true))

    assertNotEquals(
      contractHash(nonNull, bridgeableRegistrables(nonNull, emptySet()), emptyMap()),
      contractHash(nullable, bridgeableRegistrables(nullable, emptySet()), emptyMap()),
      "List<String> and List<String?> read different slots, so they must not hash alike",
    )
  }

  @Test
  fun `a null collection and a null element are different contract signatures`() {
    fun classOf(type: RirTypeRef): RirClass = RirClass(
      name = "Roster",
      methods = listOf(RirMethod(name = "Names", returnType = type)),
    )

    val nullCollection: RirClass =
      classOf(list(RirStringType(), "IReadOnlyList`1", nullable = true))
    val nullElement: RirClass =
      classOf(list(RirStringType(nullable = true), "IReadOnlyList`1"))

    assertNotEquals(
      contractHash(nullCollection, bridgeableRegistrables(nullCollection, emptySet()), emptyMap()),
      contractHash(nullElement, bridgeableRegistrables(nullElement, emptySet()), emptyMap()),
      "ADR-053 failure class: an un-extended isNullable binds every nullable collection non-null",
    )
  }

  // ---------------------------------------------------------------- named skips

  @Test
  fun `an element outside the v1 vocabulary is skipped whole, never half bound`() {
    val point = RirStructType("Test.Roster", "Point")
    val deferred = RirClass(
      name = "Roster",
      methods = listOf(
        RirMethod(name = "Describe", returnType = RirStringType()),
        RirMethod(name = "Corners", returnType = list(point, "IReadOnlyList`1")),
        RirMethod(name = "Nested", returnType = list(list(int, "IList`1"), "IReadOnlyList`1")),
      ),
    )
    val file = RirFile(
      assemblies = listOf(
        RirAssembly(
          packageId = "TestDependency",
          assemblyName = "TestDependency",
          namespaces = listOf(RirNamespace(name = "Test.Roster", types = listOf(deferred))),
        ),
      ),
    )

    val stub: String = generateKotlinStubs(file).single { it.relativePath.endsWith("/Roster.kt") }
      .content
    assertContains(
      stub, "fun describe(): String", message = "the rest of the class must still bind",
    )
    assertFalse(stub.contains("corners"), "a struct element is ADR-056 deferred scope")
    assertFalse(
      stub.contains("nested"), "a nested collection needs a slot holding a buffer pointer",
    )
  }

  // ADR-155: a struct member decomposes into flattened out-pointers and a bound-interface member
  // has its own hand-written dispatch body, so neither rides the shared conversion path this wire
  // was built into. Both are refused UPSTREAM, by name, which is what keeps the fail-fast arms
  // still standing in those routes unreachable rather than a crash waiting for a fixture.
  @Test
  fun `a collection on a struct or interface member is a named skip, never a half built member`() {
    val deferred = RirFile(
      assemblies = listOf(
        RirAssembly(
          packageId = "TestDependency",
          assemblyName = "TestDependency",
          namespaces = listOf(
            RirNamespace(
              name = "Test.Roster",
              types = listOf(
                RirStruct(
                  name = "Paw",
                  components = listOf(RirStructComponent("size", "Size", int)),
                  methods = listOf(
                    RirMethod(name = "Prints", returnType = list(RirStringType(), "IList`1")),
                  ),
                ),
                RirInterface(
                  name = "ILabelled",
                  methods = listOf(
                    RirMethod(name = "Aliases", returnType = list(RirStringType(), "IList`1")),
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    )

    val warnings: List<String> = diagnosticWarnings(deferred)
    listOf("Paw.Prints", "ILabelled.Aliases").forEach { member ->
      assertTrue(
        warnings.any { it.contains(member) && it.contains("ADR-155 deferred scope") },
        "`$member` must be skipped by name, got: $warnings",
      )
    }
    generateKotlinStubs(deferred).forEach {
      assertFalse(it.content.contains("prints"), "${it.relativePath} bound a deferred position")
      assertFalse(it.content.contains("aliases"), "${it.relativePath} bound a deferred position")
    }
  }

  @Test
  fun `the reader's collection vocabulary parses from reverse-ir json`() {
    val file: RirFile = parseReverseIr(
      """
      {
        "assemblies":[{
          "packageId":"TestDependency",
          "assemblyName":"TestDependency",
          "diagnostics":[
            {
              "kind":"skipped_collection_element",
              "typeName":"Roster","memberName":"Corners",
              "memberSignature":"Corners()",
              "reason":"struct elements are deferred","hint":"Expose a list of primitives."
            },
            {
              "kind":"skipped_array",
              "typeName":"Roster","memberName":"Ages",
              "memberSignature":"Ages()",
              "reason":"arrays are deferred","hint":"Expose IReadOnlyList<T>."
            }
          ],
          "namespaces":[{"name":"Test.Roster","types":[
            {"kind":"class","name":"Roster","methods":[{
              "name":"Names",
              "returnType":{
                "kind":"collection","collection":"list",
                "definition":"System.Collections.Generic.IReadOnlyList`1",
                "typeArguments":[{"kind":"string","nullable":true}]
              },
              "parameters":[]
            }]}
          ]}]
        }]
      }
      """.trimIndent(),
    )

    val names: RirMethod = file.assemblies.single().namespaces.single().types
      .filterIsInstance<RirClass>().single().methods.single()
    val returned = names.returnType as RirCollectionType

    assertEquals(RirCollectionKind.LIST, returned.collection)
    assertEquals("System.Collections.Generic.IReadOnlyList`1", returned.definition)
    assertFalse(returned.nullable, "the collection itself is not annotated nullable here")
    assertTrue(returned.typeArguments.single().let { it is RirStringType && it.nullable })

    // Both named skips reach the consumer's build log rather than vanishing.
    val warnings: List<String> = diagnosticWarnings(file)
    assertTrue(
      warnings.any { it.contains("Roster.Corners") && it.contains("Skipping") }, "$warnings",
    )
    assertTrue(
      warnings.any { it.contains("Roster.Ages") && it.contains("Skipping") }, "$warnings",
    )
  }

  // Q8, decided 2026-09-21: every list-like C# definition collapsing to one Kotlin `List<T>` makes
  // `AddRange(IEnumerable<string>)` beside `AddRange(List<string>)` reachable, and that used to be
  // a hard generation failure (validateKotlinSignatures' require(false)). ALL colliding members
  // are dropped, never all-but-one (ADR-072 Decision 5's rule), each named.
  @Test
  fun `C sharp overloads that collapse to one Kotlin signature are all skipped by name`() {
    val collapsing = RirClass(
      name = "Roster",
      methods = listOf(
        RirMethod(name = "Describe", returnType = RirStringType()),
        RirMethod(
          name = "AddRange",
          returnType = RirVoidType,
          parameters = listOf(RirParameter("items", list(RirStringType(), "IEnumerable`1"))),
          managedSignature = "AddRange(IEnumerable<String>)",
        ),
        RirMethod(
          name = "AddRange",
          returnType = RirVoidType,
          parameters = listOf(RirParameter("items", list(RirStringType(), "List`1"))),
          managedSignature = "AddRange(List<String>)",
        ),
      ),
    )
    val file = RirFile(
      assemblies = listOf(
        RirAssembly(
          packageId = "TestDependency",
          assemblyName = "TestDependency",
          namespaces = listOf(RirNamespace(name = "Test.Roster", types = listOf(collapsing))),
        ),
      ),
    )

    val stub: String = generateKotlinStubs(file).single { it.relativePath.endsWith("/Roster.kt") }
      .content
    assertContains(
      stub, "fun describe(): String", message = "the rest of the class must still bind",
    )
    assertFalse(stub.contains("addRange"), "both halves of the collapsing set are dropped")
    assertFalse(
      generateCSharpShims(file, "sample")
        .single { it.relativePath.endsWith("RosterRegistration.cs") }.content
        .contains("AddRange"),
      "a member the Kotlin side dropped must not keep a registration slot on the C# side",
    )

    val named: List<String> = diagnosticWarnings(file).filter { it.contains("AddRange") }
    assertEquals(2, named.size, "one diagnostic per dropped member, not one per set: $named")
    named.forEach {
      assertContains(it, "Skipping")
      assertContains(it, "fun addRange(items: List<String>)")
    }
  }
}
