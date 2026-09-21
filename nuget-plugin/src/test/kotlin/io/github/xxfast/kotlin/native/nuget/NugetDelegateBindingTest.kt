package io.github.xxfast.kotlin.native.nuget

import io.github.xxfast.kotlin.native.nuget.rir.RirAssembly
import io.github.xxfast.kotlin.native.nuget.rir.RirClass
import io.github.xxfast.kotlin.native.nuget.rir.RirNamespace
import io.github.xxfast.kotlin.native.nuget.rir.RirCollectionKind
import io.github.xxfast.kotlin.native.nuget.rir.RirCollectionType
import io.github.xxfast.kotlin.native.nuget.rir.RirDelegateType
import io.github.xxfast.kotlin.native.nuget.rir.RirConstructor
import io.github.xxfast.kotlin.native.nuget.rir.RirFile
import io.github.xxfast.kotlin.native.nuget.rir.RirInterface
import io.github.xxfast.kotlin.native.nuget.rir.RirStruct
import io.github.xxfast.kotlin.native.nuget.rir.RirStructComponent
import io.github.xxfast.kotlin.native.nuget.rir.RirMethod
import io.github.xxfast.kotlin.native.nuget.rir.RirParameter
import io.github.xxfast.kotlin.native.nuget.rir.RirPrimitiveType
import io.github.xxfast.kotlin.native.nuget.rir.RirStringType
import io.github.xxfast.kotlin.native.nuget.rir.RirVoidType
import io.github.xxfast.kotlin.native.nuget.rir.bridgeableInterfaceRegistrables
import io.github.xxfast.kotlin.native.nuget.rir.bridgeableRegistrables
import io.github.xxfast.kotlin.native.nuget.rir.bridgeableStructConstructors
import io.github.xxfast.kotlin.native.nuget.rir.bridgeableStructRegistrables
import io.github.xxfast.kotlin.native.nuget.rir.contractHash
import io.github.xxfast.kotlin.native.nuget.rir.delegateContractHash
import io.github.xxfast.kotlin.native.nuget.rir.delegatePlans
import io.github.xxfast.kotlin.native.nuget.rir.identity
import io.github.xxfast.kotlin.native.nuget.rir.isNullable
import io.github.xxfast.kotlin.native.nuget.rir.parseReverseIr
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * ADR-158: `reverse-ir.json` carries a C# delegate as a first-class type ref, and a delegate at a
 * PARAMETER position of a method or constructor of an ordinary, non-generic bound class binds as a
 * Kotlin function type. Every other position stays refused and named: a delegate return, a
 * property, a struct or bound-interface member, a generic class's member, a delegate nested in a
 * collection, and a delegate inside a Kotlin-bridge slot.
 *
 * What is pinned here is what a half-finished version of this feature gets wrong SILENTLY: that the
 * new `kind` parses at all (an unknown discriminator fails the whole build, so a reader emitting one
 * against an older plugin is a hard stop, never a silent drop); that the substituted Invoke shape
 * and the type arguments it came from cannot disagree about nullability, because a generator reads
 * `parameters` and not `typeArguments`; that admission is decided in the SHARED filter, so the two
 * generators cannot disagree about a type's registration slot count (asserted for the class, the
 * interface and the struct lists, which is what makes the renderers' `error(...)` arms unreachable
 * rather than merely unlikely); and that two delegate shapes never collapse to one ADR-054 contract
 * hash, because each shape adds a factory slot and the slot COUNT alone cannot tell them apart.
 *
 * That the real reader emits this RIR for real metadata is asserted separately, against a compiled
 * assembly, in `NugetExtractApiIntegrationTest`; that the crossing runs is
 * `IntegrationTests/WorkshopRoundTripTests`.
 */
class NugetDelegateBindingTest {

  private val int = RirPrimitiveType("int")

  private fun func(vararg parameters: RirPrimitiveType, returns: RirPrimitiveType) =
    RirDelegateType(
      definition = "System.Func`${parameters.size + 1}",
      typeArguments = parameters.toList() + returns,
      parameters = parameters.toList(),
      returnType = returns,
    )

  @Test
  fun `a delegate type ref parses from reverse-ir json`() {
    val file: RirFile = parseReverseIr(
      """
      {
        "assemblies":[{
          "packageId":"TestDependency",
          "assemblyName":"TestDependency",
          "diagnostics":[],
          "namespaces":[{"name":"Test.Workshop","types":[
            {"kind":"class","name":"Workshop","methods":[{
              "name":"Shout",
              "returnType":{"kind":"void"},
              "parameters":[{"name":"sink","type":{
                "kind":"delegate",
                "definition":"System.Action`1",
                "typeArguments":[{"kind":"string","nullable":true}],
                "parameters":[{"kind":"string","nullable":true}],
                "returnType":{"kind":"void"},
                "nullable":true
              }}]
            }]}
          ]}]
        }]
      }
      """.trimIndent(),
    )

    val shout: RirMethod = file.assemblies.single().namespaces.single().types
      .filterIsInstance<RirClass>().single().methods.single()
    val sink = shout.parameters.single().type as RirDelegateType

    assertEquals("System.Action`1", sink.definition)
    assertEquals(RirVoidType, sink.returnType)
    // The two annotations are independent: `Action<string?>? sink` is a NULLABLE function type
    // whose own argument is also nullable, and the reader writes one NullableAttribute byte per
    // node with the delegate node first.
    assertTrue(sink.nullable, "the delegate reference's own annotation")
    assertTrue(sink.isNullable, "the shared accessor both generators read must agree")
    assertTrue(
      sink.parameters.single().let { it is RirStringType && it.nullable },
      "the Invoke parameter carries its own annotation, and a generator reads THIS list, never " +
          "typeArguments: deriving it before nullability is applied binds `Action<string?>` as " +
          "`(String) -> Unit` with no diagnostic anywhere",
    )
    assertEquals(
      sink.typeArguments.single().isNullable,
      sink.parameters.single().isNullable,
      "the substituted Invoke shape and the type arguments it came from cannot disagree",
    )
  }

  @Test
  fun `a delegate-typed member is refused by the shared v1 filter`() {
    val workshop = RirClass(
      name = "Workshop",
      methods = listOf(
        // Binds: the sibling with no delegate anywhere in its signature.
        RirMethod(
          name = "RunKept",
          returnType = int,
          parameters = listOf(RirParameter("seed", int)),
          managedSignature = "method|instance|Test.Workshop.Workshop|RunKept|(System.Int32)|System.Int32",
        ),
        // Refused at the parameter position.
        RirMethod(
          name = "Apply",
          returnType = int,
          parameters = listOf(
            RirParameter("seed", int),
            RirParameter("step", func(int, returns = int)),
          ),
          managedSignature =
            "method|instance|Test.Workshop.Workshop|Apply|(System.Int32,System.Func`2)|System.Int32",
        ),
        // Refused at the return position too (the position that used to bind by accident as a
        // handle, before the reader stopped extracting a delegate TypeDef as a class).
        RirMethod(
          name = "MakeDoubler",
          returnType = func(int, returns = int),
          managedSignature =
            "method|instance|Test.Workshop.Workshop|MakeDoubler|()|Test.Workshop.Transform",
        ),
      ),
    )

    val bound: List<String> =
      bridgeableRegistrables(workshop, boundHandleTypes = emptySet()).filterIsInstance<io.github.xxfast.kotlin.native.nuget.rir.RirRegistrable.Method>().map { it.method.name }
    assertEquals(
      listOf("Apply", "RunKept"),
      bound.sorted(),
      "a delegate PARAMETER of an ordinary class binds; a delegate RETURN (MakeDoubler) does not, " +
          "and the decision is made in the SHARED filter so the two generators cannot disagree " +
          "about how many registration slots the type has",
    )
  }

  @Test
  fun `two delegate shapes are different contract signatures`() {
    fun workshop(step: RirDelegateType) = RirClass(
      name = "Workshop",
      methods = listOf(
        RirMethod(
          name = "Apply",
          returnType = RirVoidType,
          parameters = listOf(RirParameter("step", step)),
          managedSignature = "method|instance|Test.Workshop.Workshop|Apply|(System.Func)|System.Void",
        ),
      ),
    )

    val intToInt = workshop(func(int, returns = int))
    val intToLong = workshop(func(int, returns = RirPrimitiveType("long")))

    // Through `identity()`, the one public door onto the shared `describe()` fold: with no
    // managedSignature it spells the member from its types, which is exactly what contractHash
    // folds and what the binding half will hang its per-shape holder class and factory slot off.
    // A delegate arm that ignored the Invoke shape (spelling the CLR name alone) would make these
    // two identical, which is the ADR-072 Decision 3 bug one direction over.
    assertNotEquals(
      derivedIdentity(intToInt),
      derivedIdentity(intToLong),
      "`Func<int,int>` and `Func<int,long>` need different C# holders, so they must never describe " +
          "or hash alike",
    )
    assertTrue(
      derivedIdentity(intToInt).contains("int") && derivedIdentity(intToInt).contains("Func"),
      "the description carries both the CLR definition and the Invoke shape: " +
          derivedIdentity(intToInt),
    )
    // ADR-054: each shape adds a factory slot to the DECLARING type's registration, so the type's
    // contract hash must move with the shape. A shim built against `Func<int,int>` registering into
    // a native library that now expects `Func<int,long>` has to fail loudly at startup, and the
    // slot count alone cannot see the difference: both are one slot.
    fun hashOf(cls: RirClass): Long {
      val registrables = bridgeableRegistrables(cls, emptySet())
      return delegateContractHash(
        contractHash(cls, registrables, emptyMap()), delegatePlans(registrables, cls.name),
      )
    }
    assertNotEquals(hashOf(intToInt), hashOf(intToLong))
    assertEquals(
      1,
      delegatePlans(bridgeableRegistrables(intToInt, emptySet()), "Workshop").size,
      "one factory slot per distinct shape, whatever the number of members using it",
    )
  }

  // `describe()` is private to the bridging file; `identity()` is its public door, and falls back to
  // describing the member from its types when the RIR carries no managedSignature.
  private fun derivedIdentity(cls: RirClass): String =
    cls.methods.single().copy(managedSignature = "").identity()

  @Test
  fun `a delegate is not a collection element and not a bridge slot`() {
    // Both refusals are fail-closed arms in the shared file rather than in a generator, so they are
    // asserted through the same public filter: a member taking `List<Func<int,int>>` and a member
    // taking a bare delegate are both absent for the same reason.
    val workshop = RirClass(
      name = "Workshop",
      methods = listOf(
        RirMethod(
          name = "Plain",
          returnType = RirVoidType,
          managedSignature = "method|instance|Test.Workshop.Workshop|Plain|()|System.Void",
        ),
        RirMethod(
          name = "Keep",
          returnType = RirVoidType,
          parameters = listOf(RirParameter("step", func(int, returns = int))),
          managedSignature =
            "method|instance|Test.Workshop.Workshop|Keep|(System.Func`2)|System.Void",
        ),
        RirMethod(
          name = "KeepAll",
          returnType = RirVoidType,
          parameters = listOf(
            RirParameter(
              "steps",
              RirCollectionType(
                collection = RirCollectionKind.LIST,
                definition = "System.Collections.Generic.IReadOnlyList`1",
                typeArguments = listOf(func(int, returns = int)),
              ),
            ),
          ),
          managedSignature =
            "method|instance|Test.Workshop.Workshop|KeepAll|(IReadOnlyList`1)|System.Void",
        ),
      ),
    )

    val bound: List<String> =
      bridgeableRegistrables(workshop, boundHandleTypes = emptySet()).filterIsInstance<io.github.xxfast.kotlin.native.nuget.rir.RirRegistrable.Method>().map { it.method.name }
    assertTrue("Plain" in bound)
    assertTrue("Keep" in bound, "a bare delegate parameter binds")
    assertFalse(
      "KeepAll" in bound,
      "a delegate INSIDE a collection does not: one buffer slot cannot carry a minted bridge plus " +
          "its lifetime, the same refusal a collection-typed interface slot gets",
    )
  }

  // The renderer arms say "a delegate never reaches here", and that claim is only true if EVERY
  // admission path runs through the shared filter. The class path is asserted above; these are the
  // other two owners of a registration list. If either admitted the member, the generator would
  // fail with an error(...) and a stack trace instead of dropping it.
  @Test
  fun `the interface and struct registration lists refuse a delegate too`() {
    val step = RirParameter("step", func(int, returns = int))

    val iface = RirInterface(
      name = "IWorkshop",
      methods = listOf(
        RirMethod(
          name = "Apply",
          returnType = int,
          parameters = listOf(step),
          managedSignature = "method|instance|Test.Workshop.IWorkshop|Apply|(System.Func`2)|System.Int32",
        ),
      ),
    )
    assertTrue(
      bridgeableInterfaceRegistrables(iface, boundHandleTypes = emptySet()).isEmpty(),
      "ADR-070's member filter shares isV1Bridgeable, so it refuses a delegate with no extra arm",
    )

    val struct = RirStruct(
      name = "Bench",
      components = listOf(RirStructComponent("Seats", "Seats", int)),
      constructors = listOf(
        RirConstructor(parameters = listOf(RirParameter("seats", int)), managedSignature = "Bench(Int32)"),
        RirConstructor(parameters = listOf(step), managedSignature = "Bench(Func`2)"),
      ),
      methods = listOf(
        RirMethod(
          name = "Apply",
          returnType = int,
          parameters = listOf(step),
          isStatic = true,
          managedSignature = "method|static|Test.Workshop.Bench|Apply|(System.Func`2)|System.Int32",
        ),
      ),
    )
    assertTrue(
      bridgeableStructRegistrables(struct, boundHandleTypes = emptySet())
        .none { it is io.github.xxfast.kotlin.native.nuget.rir.RirRegistrable.Method },
      "ADR-056's struct member filter shares isV1Type, so it refuses a delegate too",
    )
    assertTrue(
      bridgeableStructConstructors(struct, boundHandleTypes = emptySet()).size == 1,
      "only the delegate-free constructor survives",
    )
  }

  // ADR-158 step 4: a PACKAGE-DECLARED delegate. The wire, the slot and the lifetime are the BCL
  // case's, so what is pinned here is the only thing that differs: the Kotlin parameter is a bare
  // function type with a `typealias` beside it carrying the C# name, and the C# factory constructs
  // the DECLARED delegate type. A factory that built a `Func<int,int>` instead would still compile
  // and still invoke, and `Workshop.ApplyNamed(int, Transform)` would then fail to bind to it: the
  // defect is a C# overload-resolution error at shim-compile time, not a marshalling one.
  @Test
  fun `a package-declared delegate binds as a function type behind a typealias`() {
    val transform = RirDelegateType(
      definition = "Test.Workshop.Transform",
      typeArguments = emptyList(),
      parameters = listOf(int),
      returnType = int,
    )
    val workshop = RirClass(
      name = "Workshop",
      methods = listOf(
        RirMethod(
          name = "ApplyNamed",
          returnType = int,
          parameters = listOf(RirParameter("seed", int), RirParameter("step", transform)),
          isStatic = true,
          managedSignature =
            "method|static|Test.Workshop.Workshop|ApplyNamed|(System.Int32,Test.Workshop.Transform)|System.Int32",
        ),
      ),
    )
    val rir = RirFile(
      assemblies = listOf(
        RirAssembly(
          packageId = "TestDependency",
          assemblyName = "TestDependency",
          namespaces = listOf(RirNamespace(name = "Test.Workshop", types = listOf(workshop))),
        ),
      ),
    )

    val files: List<GeneratedFile> = generateKotlinStubs(rir)
    val stub: String = files.single { it.relativePath.endsWith("/Workshop.kt") }.content
    assertContains(stub, "fun applyNamed(seed: Int, step: (Int) -> Int): Int")

    // The alias lives in the delegate's OWN package, in a file named so it can never collide with a
    // bound C# type's generated file, and is emitted even though no RIR type declares the delegate
    // (a delegate TypeDef is not extracted as a type at all).
    val aliases: GeneratedFile =
      files.single { it.relativePath.endsWith("NugetDelegates.kt") }
    assertEquals("nativeMain/testdependency/NugetDelegates.kt", aliases.relativePath)
    assertContains(aliases.content, "package testdependency")
    assertContains(aliases.content, "typealias Transform = (Int) -> Int")

    val shim: String = generateCSharpShims(rir, "sample")
      .single { it.relativePath.endsWith("WorkshopRegistration.cs") }.content
    assertContains(
      shim,
      "new global::Test.Workshop.Transform(",
      message = "the factory must construct the DECLARED delegate type: a Func<int,int> would not bind to " +
          "`ApplyNamed(int, Transform)`",
    )
    assertContains(shim, "CreateTransformInt32Int32Delegate")
  }
}
