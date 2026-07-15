package io.github.xxfast.kotlin.native.nuget

import io.github.xxfast.kotlin.native.nuget.rir.RirClass
import io.github.xxfast.kotlin.native.nuget.rir.RirFile
import io.github.xxfast.kotlin.native.nuget.rir.RirMethod
import io.github.xxfast.kotlin.native.nuget.rir.RirRegistrable
import io.github.xxfast.kotlin.native.nuget.rir.RirStruct
import io.github.xxfast.kotlin.native.nuget.rir.bridgeableRegistrables
import io.github.xxfast.kotlin.native.nuget.rir.contractHash
import io.github.xxfast.kotlin.native.nuget.rir.parseReverseIr
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/** Consumer-side generator contracts for ADR-057. */
class OverloadGenerationTest {
  private val intDescribe =
    "method|static|Test.Overloads.OverloadLab|Describe|(System.Int32)|System.String"
  private val boolDescribe =
    "method|static|Test.Overloads.OverloadLab|Describe|(System.Boolean)|System.String"
  private val stringApply =
    "method|instance|Test.Overloads.OverloadLab|Apply|(System.String)|System.String"
  private val intApply =
    "method|instance|Test.Overloads.OverloadLab|Apply|(System.Int32)|System.String"
  private val intCtor =
    "ctor|instance|Test.Overloads.OverloadLab|.ctor|(System.Int32)|System.Void"
  private val boolCtor =
    "ctor|instance|Test.Overloads.OverloadLab|.ctor|(System.Boolean)|System.Void"

  @Test
  fun `managed signatures survive parsing exactly and are stable under declaration reorder`() {
    val forward: RirClass = overloadClass(overloadRir(reversed = false))
    val reversed: RirClass = overloadClass(overloadRir(reversed = true))

    assertEquals(
      setOf(intDescribe, boolDescribe, stringApply, intApply),
      forward.methods.map { it.managedSignature() }.toSet(),
    )
    assertEquals(
      forward.methods.map { it.managedSignature() }.toSet(),
      reversed.methods.map { it.managedSignature() }.toSet(),
    )
    assertEquals(
      setOf(intCtor, boolCtor),
      forward.constructors.map { it.managedSignature() }.toSet(),
    )
  }

  @Test
  fun `supported overload siblings remain present beside an unsupported sibling diagnostic`() {
    val rir: RirFile = overloadRir(reversed = false, includeUnsupportedDiagnostic = true)
    val cls: RirClass = overloadClass(rir)

    assertEquals(2, cls.methods.count { it.name == "Describe" })
    assertFalse(
      rir.assemblies.single().diagnostics.any { it.kind.name == "SKIPPED_OVERLOAD_SET" },
    )
    assertTrue(
      rir.assemblies.single().diagnostics.any { it.kind.name == "SKIPPED_REF_STRUCT" },
    )
  }

  @Test
  fun `shared registrable order is canonical and independent of reverse ir order`() {
    val first: List<RirRegistrable> = registrables(overloadRir(reversed = false))
    val second: List<RirRegistrable> = registrables(overloadRir(reversed = true))
    val expected: List<String> = listOf(
      boolCtor,
      intCtor,
      boolDescribe,
      intDescribe,
      intApply,
      stringApply,
    )

    assertEquals(expected, first.map { it.managedSignature() })
    assertEquals(expected, second.map { it.managedSignature() })
  }

  @Test
  fun `both generated registration tables use the same canonical overload order`() {
    val rir: RirFile = overloadRir(reversed = true)
    val expectedIds: List<String> = listOf(
      boolCtor,
      intCtor,
      boolDescribe,
      intDescribe,
      intApply,
      stringApply,
    ).map(::bridgeId)
    val bindings: String = generateKotlinStubs(rir)
      .single { it.relativePath.endsWith("OverloadLabBindings.kt") }
      .content
    val shim: String = generateCSharpShims(rir, "sample")
      .single { it.relativePath.endsWith("OverloadLabRegistration.cs") }
      .content

    assertInOrder(bindings, expectedIds)
    assertInOrder(shim, expectedIds)
  }

  @Test
  fun `bridge ids and generated contract remain stable under input reorder`() {
    val forward: RirFile = overloadRir(reversed = false)
    val reversed: RirFile = overloadRir(reversed = true)
    val idPattern = Regex("[0-9a-f]{32}")
    val forwardIds: Set<String> = generateKotlinStubs(forward)
      .flatMap { idPattern.findAll(it.content).map { match -> match.value }.toList() }
      .toSet()
    val reversedIds: Set<String> = generateKotlinStubs(reversed)
      .flatMap { idPattern.findAll(it.content).map { match -> match.value }.toList() }
      .toSet()

    assertEquals(
      setOf(intDescribe, boolDescribe, stringApply, intApply, intCtor, boolCtor).map(::bridgeId)
        .toSet(),
      forwardIds,
    )
    assertEquals(forwardIds, reversedIds)
    assertEquals(contract(forward), contract(reversed))
  }

  @Test
  fun `Kotlin overloads are unsuffixed while internals use bridge ids`() {
    val files: List<GeneratedFile> = generateKotlinStubs(overloadRir(reversed = false))
    val stub: String = files.single { it.relativePath.endsWith("OverloadLab.kt") }.content
    val bindings: String =
      files.single { it.relativePath.endsWith("OverloadLabBindings.kt") }.content

    assertContains(stub, "fun describe(value: Int): String")
    assertContains(stub, "fun describe(value: Boolean): String")
    assertContains(stub, "fun apply(value: String): String")
    assertContains(stub, "fun apply(value: Int): String")
    assertFalse(Regex("fun (describe|apply)__[0-9a-f]{32}").containsMatchIn(stub))

    listOf(intDescribe, boolDescribe, stringApply, intApply).forEach { signature ->
      assertContains(bindings, "__${bridgeId(signature)}Fn")
    }
    listOf(intCtor, boolCtor).forEach { signature ->
      assertContains(bindings, "ctor__${bridgeId(signature)}Fn")
      assertContains(stub, "construct__${bridgeId(signature)}")
    }
  }

  @Test
  fun `C sharp overload thunks have unique ids and retain direct bool and string conversions`() {
    val shim: String = generateCSharpShims(overloadRir(reversed = false), "sample")
      .single { it.relativePath.endsWith("OverloadLabRegistration.cs") }
      .content

    assertContains(shim, "Describe__${bridgeId(intDescribe)}_Thunk(int value)")
    assertContains(shim, "Describe__${bridgeId(boolDescribe)}_Thunk(byte value)")
    assertContains(shim, "OverloadLab.Describe(value)")
    assertContains(shim, "OverloadLab.Describe(value != 0)")
    assertContains(
      shim,
      "Apply__${bridgeId(stringApply)}_Thunk(IntPtr selfHandle, IntPtr valuePtr)",
    )
    assertContains(shim, "Marshal.PtrToStringUTF8(valuePtr)")
    assertContains(shim, "Apply__${bridgeId(intApply)}_Thunk(IntPtr selfHandle, int value)")
  }

  @Test
  fun `multiple class constructors render natural secondary constructors and distinct thunks`() {
    val kotlin: List<GeneratedFile> = generateKotlinStubs(overloadRir(reversed = false))
    val stub: String = kotlin.single { it.relativePath.endsWith("OverloadLab.kt") }.content
    val shim: String = generateCSharpShims(overloadRir(reversed = false), "sample")
      .single { it.relativePath.endsWith("OverloadLabRegistration.cs") }
      .content

    assertContains(stub, "constructor(seed: Int)")
    assertContains(stub, "constructor(enabled: Boolean)")
    assertContains(shim, "Ctor__${bridgeId(intCtor)}_Thunk(int seed)")
    assertContains(shim, "Ctor__${bridgeId(boolCtor)}_Thunk(byte enabled)")
  }

  @Test
  fun `shape A state ctor costs no slots and alternates use slots and out pointers`() {
    val rir: RirFile = structRir()
    val kotlin: List<GeneratedFile> = generateKotlinStubs(rir)
    val point: String = kotlin.single { it.relativePath.endsWith("Point.kt") }.content
    val bindings: String = kotlin.single { it.relativePath.endsWith("PointBindings.kt") }.content
    val shim: String = generateCSharpShims(rir, "sample")
      .single { it.relativePath.endsWith("PointRegistration.cs") }
      .content

    assertContains(point, "data class Point")
    assertContains(point, "val x: Int")
    assertContains(point, "val y: Int")
    assertContains(point, "constructor(value: Int)")
    assertContains(point, "constructor(unit: Boolean)")
    assertContains(point, "constructor(size: Size)")
    assertEquals(3, Regex("ctor__[0-9a-f]{32}Fn").findAll(bindings).map { it.value }.toSet().size)
    assertContains(shim, "int size_Width, int size_Height")
    assertContains(shim, "int* outX, int* outY")
    assertFalse(shim.contains(bridgeId(pointStateCtor)))
  }

  @Test
  fun `contract is stable under reorder and changes when an overload is added`() {
    val firstRir: RirFile = overloadRir(reversed = false)
    val reorderedRir: RirFile = overloadRir(reversed = true)
    val addedRir: RirFile = overloadRir(reversed = false, includeExtra = true)

    val first: Long = contract(firstRir)
    assertEquals(first, contract(reorderedRir))
    assertNotEquals(first, contract(addedRir))
  }

  @Test
  fun `contract includes managed signature even when projected Kotlin signature is unchanged`() {
    val left: RirFile = collisionRir(includeRight = false)
    val right: RirFile = collisionRir(includeLeft = false)

    assertNotEquals(contract(left), contract(right))
  }

  @Test
  fun `error diagnostics fail generation while skipped and info diagnostics remain reportable`() {
    val nonErrors: RirFile = overloadRir(reversed = false, includeUnsupportedDiagnostic = true)
    assertTrue(diagnosticWarnings(nonErrors).all { it.startsWith("w: ") })

    val error: IllegalArgumentException = assertFailsWith {
      val rir: RirFile = parseReverseIr(errorDiagnosticJson())
      generateKotlinStubs(rir)
    }
    assertContains(error.message.orEmpty(), "error_kotlin_signature_collision")
    assertContains(error.message.orEmpty(), collisionLeft)
    assertContains(error.message.orEmpty(), collisionRight)
  }

  @Test
  fun `distinct managed signatures collapsing to one Kotlin signature fail before output`() {
    val error: IllegalArgumentException = assertFailsWith {
      generateKotlinStubs(collisionRir())
    }

    assertContains(error.message.orEmpty(), collisionLeft)
    assertContains(error.message.orEmpty(), collisionRight)
    assertContains(error.message.orEmpty(), "fun read(value: String)")
    assertContains(error.message.orEmpty(), "differently named C# adapter")
  }

  @Test
  fun `same simple type names in distinct packages remain distinct overloads`() {
    val files: List<GeneratedFile> = generateKotlinStubs(
      qualifiedTypeRir(),
      namespaceAliases = mapOf(
        "Acme.Api" to mapOf(
          "Acme.Api" to "acme.api",
          "Acme.Left" to "acme.left",
          "Acme.Right" to "acme.right",
        ),
      ),
    )
    val stub: String = files.single { it.relativePath.endsWith("Reader.kt") }.content

    assertContains(stub, "fun read(value: acme.left.Token)")
    assertContains(stub, "fun read(value: acme.right.Token)")
  }

  @Test
  fun `unequal managed signatures with an injected digest collision fail fast`() {
    val owner: Class<*> = Class.forName(
      "io.github.xxfast.kotlin.native.nuget.rir.RirBridgingKt",
    )
    val validator: Method = owner.declaredMethods.singleOrNull { method ->
      method.name == "bridgeIds" && method.parameterCount == 2
    } ?: fail("ADR-057 requires an injectable bridgeIds(signatures, digest) collision seam")
    validator.isAccessible = true
    val digest: (String) -> String = { "0".repeat(32) }

    val thrown: InvocationTargetException = assertFailsWith {
      validator.invoke(null, listOf(intDescribe, boolDescribe), digest)
    }
    val message: String = thrown.cause?.message.orEmpty()
    assertContains(message, intDescribe)
    assertContains(message, boolDescribe)
    assertContains(message, "0".repeat(32))
  }

  private fun overloadRir(
    reversed: Boolean,
    includeUnsupportedDiagnostic: Boolean = false,
    includeExtra: Boolean = false,
  ): RirFile {
    val declaredMethods: List<String> = listOf(
      methodJson("Describe", true, "int", intDescribe),
      methodJson("Describe", true, "bool", boolDescribe),
      methodJson("Apply", false, "string", stringApply),
      methodJson("Apply", false, "int", intApply),
    )
    val orderedMethods: List<String> = if (reversed) declaredMethods.reversed() else declaredMethods
    val methods: String = if (includeExtra) {
      (orderedMethods + methodJson("Describe", true, "char", extraDescribe)).joinToString(",")
    } else {
      orderedMethods.joinToString(",")
    }
    val declaredCtors: List<String> = listOf(
      ctorJson("seed", "int", intCtor),
      ctorJson("enabled", "bool", boolCtor),
    )
    val ctors: String = if (reversed) {
      declaredCtors.reversed().joinToString(",")
    } else {
      declaredCtors.joinToString(",")
    }
    val diagnostics: String = if (includeUnsupportedDiagnostic) """
      [{
        "kind":"skipped_ref_struct",
        "typeName":"OverloadLab",
        "memberName":"Describe",
        "memberSignature":"Describe(System.ReadOnlySpan`1<System.Char>)",
        "reason":"ref struct parameters are not bridgeable",
        "hint":"Expose a string adapter."
      }]
    """ else "[]"
    return parseReverseIr(
      fileJson("Test.Overloads", classJson("OverloadLab", methods, ctors), diagnostics)
    )
  }

  private fun methodJson(
    name: String,
    static: Boolean,
    type: String,
    signature: String,
  ): String = """
    {
      "name":"$name",
      "isStatic":$static,
      "managedSignature":"$signature",
      "returnType":{"kind":"string"},
      "parameters":[{"name":"value","type":{"kind":"${kind(type)}"${typeName(type)}}}]
    }
  """.trimIndent()

  private fun ctorJson(name: String, type: String, signature: String): String = """
    {
      "managedSignature":"$signature",
      "parameters":[{"name":"$name","type":{"kind":"${kind(type)}"${typeName(type)}}}]
    }
  """.trimIndent()

  private fun classJson(name: String, methods: String, ctors: String): String = """
    {"kind":"class","name":"$name","methods":[$methods],"constructors":[$ctors]}
  """.trimIndent()

  private fun fileJson(namespace: String, types: String, diagnostics: String = "[]"): String = """
    {
      "assemblies":[{
        "packageId":"Sample.Dependency",
        "assemblyName":"Sample.Dependency",
        "diagnostics":$diagnostics,
        "namespaces":[{"name":"$namespace","types":[$types]}]
      }]
    }
  """.trimIndent()

  private fun kind(type: String): String = if (type == "string") "string" else "primitive"

  private fun typeName(type: String): String = if (type == "string") "" else ",\"name\":\"$type\""

  private fun overloadClass(rir: RirFile): RirClass = rir.assemblies.single().namespaces.single()
    .types.filterIsInstance<RirClass>().single()

  private fun registrables(rir: RirFile): List<RirRegistrable> =
    bridgeableRegistrables(overloadClass(rir), emptySet())

  private fun contract(rir: RirFile): Long {
    val cls: RirClass = overloadClass(rir)
    return contractHash(cls, bridgeableRegistrables(cls, emptySet()), emptyMap())
  }

  private fun Any.managedSignature(): String {
    val getter: Method = javaClass.methods.singleOrNull { method ->
      method.name == "getManagedSignature" && method.parameterCount == 0
    } ?: fail("${javaClass.simpleName} must retain required managedSignature")
    return getter.invoke(this) as String
  }

  private fun RirRegistrable.managedSignature(): String = when (this) {
    is RirRegistrable.Ctor -> ctor.managedSignature()
    is RirRegistrable.Method -> method.managedSignature()
    is RirRegistrable.PropertyGetter -> fail("property not expected in overload fixture")
    is RirRegistrable.PropertySetter -> fail("property not expected in overload fixture")
  }

  private fun bridgeId(signature: String): String = MessageDigest.getInstance("SHA-256")
    .digest(signature.toByteArray(Charsets.UTF_8))
    .take(16)
    .joinToString("") { byte -> "%02x".format(byte) }

  private fun structRir(): RirFile = parseReverseIr(
    """
    {
      "assemblies":[{
        "packageId":"Sample.Dependency",
        "assemblyName":"Sample.Dependency",
        "namespaces":[{"name":"Test.Structs","types":[
          {
            "kind":"struct",
            "name":"Size",
            "components":[
              {"name":"width","readName":"Width","type":{"kind":"primitive","name":"int"}},
              {"name":"height","readName":"Height","type":{"kind":"primitive","name":"int"}}
            ],
            "constructors":[${ctorJson("ignored", "int", sizeStateCtor)}]
          },
          {
            "kind":"struct",
            "name":"Point",
            "components":[
              {"name":"x","readName":"X","type":{"kind":"primitive","name":"int"}},
              {"name":"y","readName":"Y","type":{"kind":"primitive","name":"int"}}
            ],
            "constructors":[
              {
                "managedSignature":"$pointStateCtor",
                "isState":true,
                "parameters":[
                  {"name":"x","type":{"kind":"primitive","name":"int"}},
                  {"name":"y","type":{"kind":"primitive","name":"int"}}
                ]
              },
              ${ctorJson("value", "int", pointIntCtor)},
              ${ctorJson("unit", "bool", pointBoolCtor)},
              {
                "managedSignature":"$pointSizeCtor",
                "parameters":[{"name":"size","type":{
                  "kind":"struct","namespace":"Test.Structs","name":"Size"
                }}]
              }
            ]
          }
        ]}]
      }]
    }
  """.trimIndent()
  )

  private fun collisionRir(
    includeLeft: Boolean = true,
    includeRight: Boolean = true,
  ): RirFile = parseReverseIr(
    fileJson(
      "Sample.Collisions",
      classJson(
        "Reader",
        listOf(
          methodJson("Read", true, "string", collisionLeft).takeIf { includeLeft },
          methodJson("Read", true, "string", collisionRight).takeIf { includeRight },
        ).filterNotNull().joinToString(","),
        "",
      ),
    )
  )

  private fun errorDiagnosticJson(): String = fileJson(
    namespace = "Sample.Collisions",
    types = classJson("Reader", "", ""),
    diagnostics = """
      [{
        "kind":"error_kotlin_signature_collision",
        "typeName":"Reader",
        "memberName":"Read",
        "memberSignature":"fun read(value: String)",
        "reason":"$collisionLeft collides with $collisionRight",
        "hint":"Expose a differently named C# adapter."
      }]
    """.trimIndent(),
  )

  private fun qualifiedTypeRir(): RirFile = parseReverseIr(
    """
    {
      "assemblies":[{
        "packageId":"Acme.Api",
        "assemblyName":"Acme.Api",
        "namespaces":[
          {"name":"Acme.Left","types":[{"kind":"class","name":"Token"}]},
          {"name":"Acme.Right","types":[{"kind":"class","name":"Token"}]},
          {"name":"Acme.Api","types":[{
            "kind":"class",
            "name":"Reader",
            "methods":[
              {
                "name":"Read","isStatic":true,
                "managedSignature":"$qualifiedLeftRead",
                "returnType":{"kind":"void"},
                "parameters":[{"name":"value","type":{
                  "kind":"handle","namespace":"Acme.Left","name":"Token"
                }}]
              },
              {
                "name":"Read","isStatic":true,
                "managedSignature":"$qualifiedRightRead",
                "returnType":{"kind":"void"},
                "parameters":[{"name":"value","type":{
                  "kind":"handle","namespace":"Acme.Right","name":"Token"
                }}]
              }
            ]
          }]}
        ]
      }]
    }
  """.trimIndent()
  )

  private val extraDescribe =
    "method|static|Test.Overloads.OverloadLab|Describe|(System.Char)|System.String"
  private val collisionLeft =
    "method|static|Sample.Collisions.Reader|Read|(Sample.Left.Text)|System.String"
  private val collisionRight =
    "method|static|Sample.Collisions.Reader|Read|(Sample.Right.Text)|System.String"
  private val sizeStateCtor =
    "ctor|instance|Test.Structs.Size|.ctor|(System.Int32,System.Int32)|System.Void"
  private val pointStateCtor =
    "ctor|instance|Test.Structs.Point|.ctor|(System.Int32,System.Int32)|System.Void"
  private val pointIntCtor =
    "ctor|instance|Test.Structs.Point|.ctor|(System.Int32)|System.Void"
  private val pointBoolCtor =
    "ctor|instance|Test.Structs.Point|.ctor|(System.Boolean)|System.Void"
  private val pointSizeCtor =
    "ctor|instance|Test.Structs.Point|.ctor|(Test.Structs.Size)|System.Void"
  private val qualifiedLeftRead =
    "method|static|Acme.Api.Reader|Read|(Acme.Left.Token)|System.Void"
  private val qualifiedRightRead =
    "method|static|Acme.Api.Reader|Read|(Acme.Right.Token)|System.Void"

  private fun assertInOrder(content: String, values: List<String>) {
    val positions: List<Int> = values.map { value -> content.indexOf(value) }
    assertTrue(positions.all { it >= 0 }, "missing ordered bridge ids in generated content")
    assertEquals(positions.sorted(), positions, "bridge ids must follow canonical slot order")
  }
}
