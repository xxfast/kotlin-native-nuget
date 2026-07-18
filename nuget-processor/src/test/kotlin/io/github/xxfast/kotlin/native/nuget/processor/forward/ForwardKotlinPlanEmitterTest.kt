package io.github.xxfast.kotlin.native.nuget.processor.forward

import com.squareup.kotlinpoet.FileSpec
import kotlin.test.Test
import kotlin.test.assertContains

class ForwardKotlinPlanEmitterTest {
  @Test
  fun `renders class method from ordered native ABI`() {
    val source: String = render(classMethodPlan())

    assertContains(source, "@CName(\"counter_increment\")")
    assertContains(source, "public fun export_counter_increment(")
    assertContains(source, "handle: COpaquePointer,")
    assertContains(source, "amount: Int,")
    assertContains(source, "errorOut: COpaquePointer?,")
    assertContains(source, "): Int = try {")
    assertContains(source, "handle.asStableRef<sample.Counter>().get().increment(amount)")
    assertContains(source, "errorOut.reinterpret<COpaquePointerVar>().pointed.value")
    assertContains(source, "  0")
  }

  @Test
  fun `renders Unit primitive receiver extension from plan invocation`() {
    val source: String = render(extensionPlan())

    assertContains(source, "@CName(\"int_adjust\")")
    assertContains(source, "public fun export_int_adjust(")
    assertContains(source, "`receiver`: Int,")
    assertContains(source, "amount: Int,")
    assertContains(source, "errorOut: COpaquePointer?,")
    assertContains(source, "receiver.adjust(amount)")
    assertContains(source, "try {")
    assertContains(source, "errorOut.reinterpret<COpaquePointerVar>().pointed.value")
  }

  private fun render(plan: ForwardCallablePlan): String {
    val builder: FileSpec.Builder = FileSpec.builder("sample", "Exports")
    builder.addForwardKotlinPlanExport(plan)
    return builder.build().toString()
  }

  private fun classMethodPlan(): ForwardCallablePlan = directPlan(
    symbol = "sample.Counter.increment",
    exportName = "counter_increment",
    receiver = ForwardAbiParameter(
      name = "handle",
      wireType = ForwardAbiWireType.POINTER,
      direction = ForwardAbiDirection.IN,
      transfer = ForwardTransfer(
        subject = "handle",
        type = BridgeType.ObjectHandle("sample.Counter"),
        flow = ForwardFlow.INTO_KOTLIN,
        passing = ForwardPassing.VALUE,
        ownership = ForwardOwnership.BORROWED,
        conversion = ForwardConversion.HANDLE_TO_STABLE_REF,
      ),
    ),
    result = BridgeType.Primitive(PrimitiveKind.INT),
  )

  private fun extensionPlan(): ForwardCallablePlan = directPlan(
    symbol = "sample.adjust",
    exportName = "int_adjust",
    receiver = ForwardAbiParameter(
      name = "receiver",
      wireType = ForwardAbiWireType.INT32,
      direction = ForwardAbiDirection.IN,
      transfer = directTransfer("receiver", BridgeType.Primitive(PrimitiveKind.INT), ForwardFlow.INTO_KOTLIN),
    ),
    result = BridgeType.Unit,
  )

  private fun directPlan(
    symbol: String,
    exportName: String,
    receiver: ForwardAbiParameter,
    result: BridgeType,
  ): ForwardCallablePlan {
    val error = errorParameter()
    val amount = ForwardAbiParameter(
      name = "amount",
      wireType = ForwardAbiWireType.INT32,
      direction = ForwardAbiDirection.IN,
      transfer = directTransfer("amount", BridgeType.Primitive(PrimitiveKind.INT), ForwardFlow.INTO_KOTLIN),
    )
    val call = ForwardNativeCall(
      exportName = exportName,
      result = if (result == BridgeType.Unit) ForwardAbiWireType.VOID else ForwardAbiWireType.INT32,
      parameters = listOf(receiver, amount, error),
    )
    return ForwardCallablePlan(
      invocation = ForwardInvocation(
        symbol = symbol,
        origin = if (receiver.name == "handle") ForwardCallableOrigin.CLASS else ForwardCallableOrigin.EXTENSION,
      ),
      publicSignature = ForwardPublicSignature(
        name = symbol.substringAfterLast('.'),
        parameters = listOf(ForwardPublicParameter("amount", BridgeType.Primitive(PrimitiveKind.INT))),
        result = result,
      ),
      evaluation = ForwardEvaluation.EXACTLY_ONCE,
      nativeExports = listOf(call),
      nativeImports = listOf(call),
      result = ForwardResultConvention(
        wireType = call.result,
        transfer = directTransfer("result", result, ForwardFlow.OUT_OF_KOTLIN),
      ),
      errorSlot = error,
      helperRequirements = setOf(ForwardHelperRequirement.STABLE_REF),
    ).validate()
  }

  private fun errorParameter(): ForwardAbiParameter = ForwardAbiParameter(
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

  private fun directTransfer(
    subject: String,
    type: BridgeType,
    flow: ForwardFlow,
  ): ForwardTransfer = ForwardTransfer(
    subject = subject,
    type = type,
    flow = flow,
    passing = ForwardPassing.VALUE,
    ownership = ForwardOwnership.BORROWED,
    conversion = ForwardConversion.DIRECT,
  )
}
