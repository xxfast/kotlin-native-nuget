package io.github.xxfast.kotlin.native.nuget.processor.forward

import com.squareup.kotlinpoet.FileSpec
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirFile
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirNamespace
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirRenderer
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirStaticClass
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/**
 * Phase 10: top-level nullable primitives stay on ADR-002 two-call via
 * [ForwardEvaluation.LEGACY_TWO_CALL], not ADR-061 single-call valueOut.
 */
class ForwardPhase10LegacyTwoCallTest {
  @Test
  fun `top-level nullable primitive plan emits has_value and value exports`() {
    val plan = legacyTwoCallPlan(
      symbol = "sample.nullableInt",
      export = "nullableInt",
      publicName = "nullableInt",
      primitive = BridgeType.Primitive(PrimitiveKind.INT),
      parameters = listOf(ForwardPublicParameter("hasValue", BridgeType.Primitive(PrimitiveKind.BOOLEAN))),
    )

    val kotlin = renderKotlin(plan)
    assertContains(kotlin, "@CName(\"nullableInt_has_value\")")
    assertContains(kotlin, "@CName(\"nullableInt_value\")")
    assertContains(kotlin, "nullableInt(hasValue) != null")
    assertContains(kotlin, "nullableInt(hasValue)!!")

    val csharp = renderCsharp(plan)
    assertContains(csharp, "EntryPoint = \"nullableInt_has_value\"")
    assertContains(csharp, "EntryPoint = \"nullableInt_value\"")
    assertContains(csharp, "public static int? nullableInt(bool hasValue)")
    assertContains(csharp, "if (!__nuget_hasValue) return null;")
  }

  @Test
  fun `legacy two-call plan validates with two native exports`() {
    val plan = legacyTwoCallPlan(
      symbol = "sample.nullableIntProbe",
      export = "nullableIntProbe",
      publicName = "nullableIntProbe",
      primitive = BridgeType.Primitive(PrimitiveKind.INT),
      parameters = emptyList(),
    )
    assertEquals(ForwardEvaluation.LEGACY_TWO_CALL, plan.evaluation)
    assertEquals(2, plan.nativeExports.size)
    assertEquals("nullableIntProbe_has_value", plan.nativeExports[0].exportName)
    assertEquals("nullableIntProbe_value", plan.nativeExports[1].exportName)
  }

  private fun renderKotlin(plan: ForwardCallablePlan): String {
    val builder: FileSpec.Builder = FileSpec.builder("sample", "Exports")
    builder.addForwardKotlinPlanExport(plan)
    return builder.build().toString()
  }

  private fun renderCsharp(plan: ForwardCallablePlan): String = CirRenderer().render(
    CirFile(
      namespaces = listOf(
        CirNamespace("Sample", listOf(CirStaticClass("Mappings", ForwardCirPlanProjection.static(plan, "sample")))),
      ),
    ),
  )

  private fun legacyTwoCallPlan(
    symbol: String,
    export: String,
    publicName: String,
    primitive: BridgeType.Primitive,
    parameters: List<ForwardPublicParameter>,
  ): ForwardCallablePlan {
    val result = BridgeType.Nullable(primitive)
    val error = ForwardAbiParameter(
      name = "errorOut",
      wireType = ForwardAbiWireType.POINTER,
      direction = ForwardAbiDirection.OUT,
      transfer = ForwardTransfer(
        subject = "error",
        type = BridgeType.ObjectHandle("kotlin.Throwable"),
        flow = ForwardFlow.OUT_OF_KOTLIN,
        passing = ForwardPassing.OUT,
        ownership = ForwardOwnership.BORROWED,
        conversion = ForwardConversion.STABLE_REF_TO_HANDLE,
      ),
    )
    val inputs: List<ForwardAbiParameter> = parameters.map { parameter ->
      ForwardAbiParameter(
        name = parameter.name,
        wireType = ForwardAbiWireType.BOOLEAN,
        direction = ForwardAbiDirection.IN,
        transfer = ForwardTransfer(
          parameter.name, parameter.type, ForwardFlow.INTO_KOTLIN, ForwardPassing.VALUE,
          ForwardOwnership.BORROWED, ForwardConversion.DIRECT,
        ),
      )
    }
    val presence = ForwardNativeCall("${export}_has_value", ForwardAbiWireType.BOOLEAN, inputs + error)
    val value = ForwardNativeCall("${export}_value", ForwardAbiWireType.INT32, inputs + error)
    return ForwardCallablePlan(
      invocation = ForwardInvocation(symbol, origin = ForwardCallableOrigin.TOP_LEVEL),
      publicSignature = ForwardPublicSignature(publicName, parameters, result),
      evaluation = ForwardEvaluation.LEGACY_TWO_CALL,
      nativeExports = listOf(presence, value),
      nativeImports = listOf(presence, value),
      result = ForwardResultConvention(
        ForwardAbiWireType.BOOLEAN,
        ForwardTransfer(
          subject = "result",
          type = result,
          flow = ForwardFlow.OUT_OF_KOTLIN,
          passing = ForwardPassing.VALUE,
          ownership = ForwardOwnership.BORROWED,
          conversion = ForwardConversion.DIRECT,
        ),
      ),
      errorSlot = error,
      helperRequirements = setOf(ForwardHelperRequirement.STABLE_REF),
    ).validate()
  }
}
