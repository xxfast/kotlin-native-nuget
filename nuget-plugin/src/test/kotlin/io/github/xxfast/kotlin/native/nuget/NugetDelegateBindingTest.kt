package io.github.xxfast.kotlin.native.nuget

import io.github.xxfast.kotlin.native.nuget.rir.RirClass
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
import io.github.xxfast.kotlin.native.nuget.rir.identity
import io.github.xxfast.kotlin.native.nuget.rir.isNullable
import io.github.xxfast.kotlin.native.nuget.rir.parseReverseIr
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * ADR-158, the contract half: `reverse-ir.json` can now carry a C# delegate as a first-class type
 * ref, and the shared v1 filter refuses one so nothing downstream can half-bind it.
 *
 * This is the seam the binding half flips. The delegate is NOT bound yet: no Kotlin function type
 * is spelled, no one-slot bridge is minted, and the real reader still refuses every delegate-shaped
 * member by name (`skipped_delegate_signature`, asserted against a compiled assembly in
 * `NugetExtractApiIntegrationTest`). What is pinned here is what a half-finished feature gets wrong
 * silently: that the new `kind` parses at all (an unknown discriminator fails the whole build, so a
 * reader emitting one before the plugin knows it is a hard stop, not a silent drop), that a
 * delegate-typed member is refused by the SHARED filter rather than by one generator, and that two
 * delegate shapes never collapse to the same ADR-054 contract hash.
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
      listOf("RunKept"),
      bound,
      "a delegate must be refused in the SHARED filter, so the two generators cannot disagree " +
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
    assertEquals(
      contractHash(intToInt, bridgeableRegistrables(intToInt, emptySet()), emptyMap()),
      contractHash(intToLong, bridgeableRegistrables(intToLong, emptySet()), emptyMap()),
      "while the shapes are refused, neither contributes a slot, so the hash is the empty-list " +
          "hash for both: this is the assertion the binding half INVERTS when the factory slot " +
          "starts folding into the declaring type's registration",
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
      ),
    )

    val bound: List<String> =
      bridgeableRegistrables(workshop, boundHandleTypes = emptySet()).filterIsInstance<io.github.xxfast.kotlin.native.nuget.rir.RirRegistrable.Method>().map { it.method.name }
    assertTrue("Plain" in bound)
    assertFalse("Keep" in bound)
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
}
