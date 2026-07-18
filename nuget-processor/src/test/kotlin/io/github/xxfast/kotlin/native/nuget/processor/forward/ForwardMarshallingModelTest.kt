package io.github.xxfast.kotlin.native.nuget.processor.forward

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ForwardMarshallingModelTest {
  @Test
  fun `validates a source-neutral string callable plan`() {
    val plan: ForwardCallablePlan = stringPlan()

    assertEquals(plan, plan.validate())
    assertEquals(ForwardEvaluation.EXACTLY_ONCE, plan.evaluation)
    assertEquals(ForwardFlow.OUT_OF_KOTLIN, plan.result.transfer.flow)
    assertEquals(ForwardAbiDirection.IN, plan.nativeExports.single().parameters.single().direction)
  }

  @Test
  fun `rejects raw KSP types`() {
    val error: IllegalStateException = assertFailsWith {
      planWithResult(BridgeType.RawKSType("kotlinx.datetime.Instant")).validate()
    }

    assertTrue(error.message!!.contains("raw KSType"))
  }

  @Test
  fun `rejects unsupported types and raw collections`() {
    val unsupported: IllegalStateException = assertFailsWith {
      planWithResult(BridgeType.Unsupported("kotlin.Function0", "callbacks are legacy-only")).validate()
    }
    val rawCollection: IllegalStateException = assertFailsWith {
      planWithResult(BridgeType.RawCollection(CollectionKind.LIST)).validate()
    }

    assertTrue(unsupported.message!!.contains("unsupported type"))
    assertTrue(rawCollection.message!!.contains("raw LIST collection"))
  }

  @Test
  fun `rejects missing required conversions`() {
    val plan: ForwardCallablePlan = stringPlan().copy(
      nativeExports = listOf(
        stringCall(
          transfer = stringTransfer(conversion = null),
        )
      ),
      nativeImports = listOf(
        stringCall(
          transfer = stringTransfer(conversion = null),
        )
      ),
    )

    val error: IllegalArgumentException = assertFailsWith { plan.validate() }

    assertTrue(error.message!!.contains("missing conversion STRING_TO_UTF8"))
  }

  @Test
  fun `rejects invalid evaluation call counts`() {
    val call: ForwardNativeCall = stringCall()
    val plan: ForwardCallablePlan = stringPlan().copy(
      nativeExports = listOf(call, call.copy(exportName = "sample_echo_second")),
      nativeImports = listOf(call, call.copy(exportName = "sample_echo_second")),
    )

    val error: IllegalArgumentException = assertFailsWith { plan.validate() }

    assertTrue(error.message!!.contains("invalid call count"))
  }

  @Test
  fun `rejects transfers with no ownership`() {
    val missingOwnership: ForwardTransfer = stringTransfer(ownership = null)
    val plan: ForwardCallablePlan = stringPlan().copy(
      nativeExports = listOf(stringCall(missingOwnership)),
      nativeImports = listOf(stringCall(missingOwnership)),
    )

    val error: IllegalArgumentException = assertFailsWith { plan.validate() }

    assertTrue(error.message!!.contains("missing ownership"))
  }

  @Test
  fun `requires cleanup for owned handles`() {
    val handle: BridgeType.ObjectHandle = BridgeType.ObjectHandle("sample.Patient")
    val transfer = ForwardTransfer(
      subject = "patient",
      type = handle,
      flow = ForwardFlow.OUT_OF_KOTLIN,
      passing = ForwardPassing.OUT,
      ownership = ForwardOwnership.OWNED_HANDLE,
      conversion = ForwardConversion.STABLE_REF_TO_HANDLE,
    )
    val call = ForwardNativeCall(
      exportName = "sample_patient",
      result = ForwardAbiWireType.VOID,
      parameters = listOf(
        ForwardAbiParameter("patient", ForwardAbiWireType.POINTER, ForwardAbiDirection.OUT, transfer)
      ),
    )
    val plan = ForwardCallablePlan(
      invocation = ForwardInvocation("sample.patient"),
      publicSignature = ForwardPublicSignature("Patient", emptyList(), handle),
      evaluation = ForwardEvaluation.EXACTLY_ONCE,
      nativeExports = listOf(call),
      nativeImports = listOf(call),
      result = ForwardResultConvention(ForwardAbiWireType.VOID, directUnitResult()),
      helperRequirements = setOf(ForwardHelperRequirement.STABLE_REF),
    )

    val error: IllegalArgumentException = assertFailsWith { plan.validate() }

    assertTrue(error.message!!.contains("missing cleanup"))
  }

  private fun stringPlan(): ForwardCallablePlan = ForwardCallablePlan(
    invocation = ForwardInvocation(symbol = "sample.echo"),
    publicSignature = ForwardPublicSignature(
      name = "Echo",
      parameters = listOf(ForwardPublicParameter("text", BridgeType.String)),
      result = BridgeType.String,
    ),
    evaluation = ForwardEvaluation.EXACTLY_ONCE,
    nativeExports = listOf(stringCall()),
    nativeImports = listOf(stringCall()),
    result = ForwardResultConvention(
      wireType = ForwardAbiWireType.POINTER,
      transfer = ForwardTransfer(
        subject = "result",
        type = BridgeType.String,
        flow = ForwardFlow.OUT_OF_KOTLIN,
        passing = ForwardPassing.VALUE,
        ownership = ForwardOwnership.MATERIALIZED,
        conversion = ForwardConversion.UTF8_TO_STRING,
      ),
    ),
    liftOperations = listOf(
      ForwardValueOperation("result", BridgeType.String, ForwardConversion.UTF8_TO_STRING)
    ),
    lowerOperations = listOf(
      ForwardValueOperation("text", BridgeType.String, ForwardConversion.STRING_TO_UTF8)
    ),
    helperRequirements = setOf(ForwardHelperRequirement.UTF8),
  )

  private fun stringCall(
    transfer: ForwardTransfer = stringTransfer(),
  ): ForwardNativeCall = ForwardNativeCall(
    exportName = "sample_echo",
    result = ForwardAbiWireType.POINTER,
    parameters = listOf(
      ForwardAbiParameter("text", ForwardAbiWireType.POINTER, ForwardAbiDirection.IN, transfer)
    ),
  )

  private fun stringTransfer(
    ownership: ForwardOwnership? = ForwardOwnership.MATERIALIZED,
    conversion: ForwardConversion? = ForwardConversion.STRING_TO_UTF8,
  ): ForwardTransfer = ForwardTransfer(
    subject = "text",
    type = BridgeType.String,
    flow = ForwardFlow.INTO_KOTLIN,
    passing = ForwardPassing.VALUE,
    ownership = ownership,
    conversion = conversion,
  )

  private fun directUnitResult(): ForwardTransfer = ForwardTransfer(
    subject = "result",
    type = BridgeType.Unit,
    flow = ForwardFlow.OUT_OF_KOTLIN,
    passing = ForwardPassing.VALUE,
    ownership = ForwardOwnership.BORROWED,
    conversion = ForwardConversion.DIRECT,
  )

  private fun planWithResult(type: BridgeType): ForwardCallablePlan {
    val call = ForwardNativeCall("sample_value", ForwardAbiWireType.INT32, emptyList())
    return ForwardCallablePlan(
      invocation = ForwardInvocation("sample.value"),
      publicSignature = ForwardPublicSignature("Value", emptyList(), type),
      evaluation = ForwardEvaluation.EXACTLY_ONCE,
      nativeExports = listOf(call),
      nativeImports = listOf(call),
      result = ForwardResultConvention(
        wireType = ForwardAbiWireType.INT32,
        transfer = ForwardTransfer(
          subject = "result",
          type = type,
          flow = ForwardFlow.OUT_OF_KOTLIN,
          passing = ForwardPassing.VALUE,
          ownership = ForwardOwnership.BORROWED,
          conversion = ForwardConversion.DIRECT,
        ),
      ),
    )
  }
}
