package io.github.xxfast.kotlin.native.nuget.processor.forward

import com.squareup.kotlinpoet.FileSpec
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirMethod
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirValueClass
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirValueClassConstructor
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirFile
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirNamespace
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirRenderer
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ForwardPhase9ValueClassProjectionTest {
  @Test
  fun `primitive-underlying method with String param emits parameter in Kotlin and C#`() {
    val plan: ForwardCallablePlan = valueClassMethodPlan(
      symbol = "sample.ChartId.matches",
      export = "chartid_matches",
      owner = "sample.ChartId",
      receiver = stringReceiver(),
      parameters = listOf(ForwardPublicParameter("other", BridgeType.String)),
      result = BridgeType.Primitive(PrimitiveKind.BOOLEAN),
      wireResult = ForwardAbiWireType.BOOLEAN,
    )

    val kotlin: String = renderKotlin(plan)
    val method: CirMethod = ForwardCirPlanProjection.valueClassMethod(plan, "Value")
    val csharp: String = renderValueClass(
      methods = listOf(method),
      underlyingType = "string",
      underlyingName = "Value",
      underlyingNativeType = "string",
      underlyingIsReference = false,
    )

    // KotlinPoet escapes the reserved name `value` with backticks.
    assertContains(kotlin, "chartid_matches")
    assertContains(kotlin, "`value`: String")
    assertContains(kotlin, "other: String")
    assertContains(kotlin, "sample.ChartId(value).matches(other)")
    assertFalse(kotlin.contains("errorOut"), "value-class methods keep the no-errorOut ABI")
    assertEquals(listOf("other"), method.parameters.map { it.name })
    assertEquals("bool", method.returnType)
    assertContains(csharp, "public bool Matches(string other)")
    assertContains(csharp, "Native_Matches(string value, string other)")
  }

  @Test
  fun `reference-underlying method with String param emits parameter in Kotlin and C#`() {
    val plan: ForwardCallablePlan = valueClassMethodPlan(
      symbol = "sample.ChartRef.label",
      export = "chartref_label",
      owner = "sample.ChartRef",
      receiver = handleReceiver("sample.Patient"),
      parameters = listOf(ForwardPublicParameter("suffix", BridgeType.String)),
      result = BridgeType.String,
      wireResult = ForwardAbiWireType.POINTER,
    )

    val kotlin: String = renderKotlin(plan)
    val method: CirMethod = ForwardCirPlanProjection.valueClassMethod(plan, "Patient._handle")
    val csharp: String = renderValueClass(
      methods = listOf(method),
      underlyingType = "Patient",
      underlyingName = "Patient",
      underlyingNativeType = "IntPtr",
      underlyingIsReference = true,
    )

    assertContains(kotlin, "handle: COpaquePointer")
    assertContains(kotlin, "suffix: String")
    assertContains(
      kotlin,
      "sample.ChartRef(handle.asStableRef<sample.Patient>().get()).label(suffix)",
    )
    assertEquals(listOf("suffix"), method.parameters.map { it.name })
    assertEquals("string", method.returnType)
    assertEquals("IntPtr", method.nativeReturnType)
    assertContains(csharp, "public string Label(string suffix)")
    assertContains(csharp, "Marshal.PtrToStringUTF8(Native_Label(Patient._handle, suffix))!")
  }

  @Test
  fun `constructor still returns underlying type with CreateChecked pattern`() {
    val plan: ForwardCallablePlan = valueClassConstructorPlan()
    val kotlin: String = renderKotlin(plan)
    val ctor: CirValueClassConstructor = ForwardCirPlanProjection.valueClassConstructor(
      plan = plan,
      nativeSuffix = "",
      underlyingIsString = true,
    )
    val csharp: String = renderValueClass(
      constructors = listOf(ctor),
      underlyingType = "string",
      underlyingName = "Id",
      underlyingNativeType = "string",
      underlyingIsReference = false,
    )

    assertContains(kotlin, "@CName(\"catid_create\")")
    assertContains(kotlin, "errorOut: COpaquePointer?")
    assertContains(kotlin, "sample.CatId(id).id")
    assertEquals("catid_create", ctor.nativeName)
    assertTrue(ctor.hasErrorCheck)
    assertContains(csharp, "CreateChecked(")
    assertContains(csharp, "Marshal.PtrToStringUTF8(CreateChecked(")
    assertContains(csharp, "public CatId(string id)")
  }

  private fun renderKotlin(plan: ForwardCallablePlan): String = FileSpec.builder("sample", "Exports")
    .addForwardKotlinPlanExport(plan)
    .build()
    .toString()

  private fun renderValueClass(
    constructors: List<CirValueClassConstructor> = emptyList(),
    methods: List<CirMethod> = emptyList(),
    underlyingType: String,
    underlyingName: String,
    underlyingNativeType: String,
    underlyingIsReference: Boolean,
  ): String = CirRenderer().render(
    CirFile(
      namespaces = listOf(
        CirNamespace(
          "Sample",
          listOf(
            CirValueClass(
              name = if (underlyingIsReference) "ChartRef" else if (constructors.isNotEmpty()) "CatId" else "ChartId",
              libraryName = "sample",
              nativePrefix = if (underlyingIsReference) "chartref" else if (constructors.isNotEmpty()) "catid" else "chartid",
              underlyingType = underlyingType,
              underlyingName = underlyingName,
              underlyingNativeType = underlyingNativeType,
              underlyingIsReference = underlyingIsReference,
              constructors = constructors,
              properties = emptyList(),
              methods = methods,
            ),
          ),
        ),
      ),
    ),
  )

  private fun valueClassMethodPlan(
    symbol: String,
    export: String,
    owner: String,
    receiver: ForwardAbiParameter,
    parameters: List<ForwardPublicParameter>,
    result: BridgeType,
    wireResult: ForwardAbiWireType,
  ): ForwardCallablePlan {
    val nativeParams: List<ForwardAbiParameter> = listOf(receiver) + parameters.map { parameter ->
      ForwardAbiParameter(
        parameter.name,
        if (parameter.type == BridgeType.String) ForwardAbiWireType.STRING else ForwardAbiWireType.POINTER,
        ForwardAbiDirection.IN,
        ForwardTransfer(
          parameter.name,
          parameter.type,
          ForwardFlow.INTO_KOTLIN,
          ForwardPassing.VALUE,
          ForwardOwnership.BORROWED,
          if (parameter.type == BridgeType.String) ForwardConversion.STRING_TO_UTF8 else ForwardConversion.DIRECT,
        ),
      )
    }
    val call = ForwardNativeCall(export, wireResult, nativeParams)
    val helpers: Set<ForwardHelperRequirement> = buildSet {
      add(ForwardHelperRequirement.STABLE_REF)
      add(ForwardHelperRequirement.VALUE_CLASS)
      if (result == BridgeType.String || parameters.any { it.type == BridgeType.String }) {
        add(ForwardHelperRequirement.UTF8)
      }
    }
    return ForwardCallablePlan(
      invocation = ForwardInvocation(symbol, origin = ForwardCallableOrigin.VALUE_CLASS, target = owner),
      publicSignature = ForwardPublicSignature(
        name = symbol.substringAfterLast('.').replaceFirstChar { it.uppercase() },
        parameters = parameters,
        result = result,
      ),
      evaluation = ForwardEvaluation.EXACTLY_ONCE,
      nativeExports = listOf(call),
      nativeImports = listOf(call),
      result = ForwardResultConvention(
        wireResult,
        ForwardTransfer(
          "result",
          result,
          ForwardFlow.OUT_OF_KOTLIN,
          ForwardPassing.VALUE,
          if (result == BridgeType.String) ForwardOwnership.MATERIALIZED else ForwardOwnership.BORROWED,
          if (result == BridgeType.String) ForwardConversion.UTF8_TO_STRING else ForwardConversion.DIRECT,
        ),
      ),
      errorSlot = null,
      helperRequirements = helpers,
    ).validate()
  }

  private fun valueClassConstructorPlan(): ForwardCallablePlan {
    val error = ForwardAbiParameter(
      "errorOut",
      ForwardAbiWireType.POINTER,
      ForwardAbiDirection.OUT,
      ForwardTransfer(
        "error",
        BridgeType.ObjectHandle("kotlin.Throwable"),
        ForwardFlow.OUT_OF_KOTLIN,
        ForwardPassing.OUT,
        ForwardOwnership.BORROWED,
        ForwardConversion.STABLE_REF_TO_HANDLE,
      ),
    )
    val id = ForwardAbiParameter(
      "id",
      ForwardAbiWireType.STRING,
      ForwardAbiDirection.IN,
      ForwardTransfer(
        "id",
        BridgeType.String,
        ForwardFlow.INTO_KOTLIN,
        ForwardPassing.VALUE,
        ForwardOwnership.BORROWED,
        ForwardConversion.STRING_TO_UTF8,
      ),
    )
    val call = ForwardNativeCall("catid_create", ForwardAbiWireType.POINTER, listOf(id, error))
    return ForwardCallablePlan(
      invocation = ForwardInvocation(
        symbol = "sample.CatId.<init>",
        receiver = "id",
        origin = ForwardCallableOrigin.VALUE_CLASS,
        target = "sample.CatId",
      ),
      publicSignature = ForwardPublicSignature(
        "Create",
        listOf(ForwardPublicParameter("id", BridgeType.String)),
        BridgeType.String,
      ),
      evaluation = ForwardEvaluation.EXACTLY_ONCE,
      nativeExports = listOf(call),
      nativeImports = listOf(call),
      result = ForwardResultConvention(
        ForwardAbiWireType.POINTER,
        ForwardTransfer(
          "result",
          BridgeType.String,
          ForwardFlow.OUT_OF_KOTLIN,
          ForwardPassing.VALUE,
          ForwardOwnership.MATERIALIZED,
          ForwardConversion.UTF8_TO_STRING,
        ),
      ),
      errorSlot = error,
      helperRequirements = setOf(
        ForwardHelperRequirement.STABLE_REF,
        ForwardHelperRequirement.VALUE_CLASS,
        ForwardHelperRequirement.UTF8,
      ),
    ).validate()
  }

  private fun stringReceiver(): ForwardAbiParameter = ForwardAbiParameter(
    "value",
    ForwardAbiWireType.STRING,
    ForwardAbiDirection.IN,
    ForwardTransfer(
      "value",
      BridgeType.String,
      ForwardFlow.INTO_KOTLIN,
      ForwardPassing.VALUE,
      ForwardOwnership.BORROWED,
      ForwardConversion.STRING_TO_UTF8,
    ),
  )

  private fun handleReceiver(owner: String): ForwardAbiParameter = ForwardAbiParameter(
    "handle",
    ForwardAbiWireType.POINTER,
    ForwardAbiDirection.IN,
    ForwardTransfer(
      "handle",
      BridgeType.ObjectHandle(owner),
      ForwardFlow.INTO_KOTLIN,
      ForwardPassing.VALUE,
      ForwardOwnership.BORROWED,
      ForwardConversion.HANDLE_TO_STABLE_REF,
    ),
  )
}
