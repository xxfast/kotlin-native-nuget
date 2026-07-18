package io.github.xxfast.kotlin.native.nuget.processor.forward

import com.squareup.kotlinpoet.FileSpec
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class ForwardPhase6ConstructorProjectionTest {
  @Test
  fun `constructor secondary constructor and data copy retain their native shapes`() {
    val primary = constructorPlan("sample.Counter.<init>", "counter_create", "")
    val secondary = constructorPlan("sample.Counter.<init>_2", "counter_create_2", "_2")
    val copy = copyPlan()

    assertContains(render(primary), "@CName(\"counter_create\")")
    assertContains(render(primary), "StableRef.create(sample.Counter())")
    assertContains(render(secondary), "@CName(\"counter_create_2\")")
    assertContains(render(copy), "handle.asStableRef<sample.Counter>().get().copy()")
    assertEquals("", ForwardCirPlanProjection.constructor(primary).nativeSuffix)
    assertEquals("_2", ForwardCirPlanProjection.constructor(secondary, "_2").nativeSuffix)
  }

  private fun render(plan: ForwardCallablePlan): String = FileSpec.builder("sample", "Exports")
    .addForwardKotlinPlanExport(plan)
    .build()
    .toString()

  private fun constructorPlan(symbol: String, export: String, suffix: String): ForwardCallablePlan = handlePlan(
    symbol = symbol,
    export = export,
    origin = ForwardCallableOrigin.CONSTRUCTOR,
    target = "sample.Counter",
    parameters = emptyList(),
    suffix = suffix,
  )

  private fun copyPlan(): ForwardCallablePlan = handlePlan(
    symbol = "sample.Counter.copy",
    export = "counter_copy",
    origin = ForwardCallableOrigin.COPY,
    target = null,
    parameters = listOf(handle()),
    suffix = "",
  )

  private fun handlePlan(
    symbol: String,
    export: String,
    origin: ForwardCallableOrigin,
    target: String?,
    parameters: List<ForwardAbiParameter>,
    suffix: String,
  ): ForwardCallablePlan {
    val error = error()
    val result = BridgeType.ObjectHandle("sample.Counter")
    val call = ForwardNativeCall(export, ForwardAbiWireType.POINTER, parameters + error)
    return ForwardCallablePlan(
      invocation = ForwardInvocation(symbol, origin = origin, target = target),
      publicSignature = ForwardPublicSignature("Create$suffix", emptyList(), result),
      evaluation = ForwardEvaluation.EXACTLY_ONCE,
      nativeExports = listOf(call),
      nativeImports = listOf(call),
      result = ForwardResultConvention(
        ForwardAbiWireType.POINTER,
        ForwardTransfer("result", result, ForwardFlow.OUT_OF_KOTLIN, ForwardPassing.VALUE,
          ForwardOwnership.OWNED_HANDLE, ForwardConversion.STABLE_REF_TO_HANDLE),
      ),
      errorSlot = error,
      cleanup = listOf(ForwardCleanup("result", ForwardCleanupKind.DISPOSE_STABLE_REF)),
      helperRequirements = setOf(ForwardHelperRequirement.STABLE_REF),
    ).validate()
  }

  private fun handle(): ForwardAbiParameter = ForwardAbiParameter(
    "handle", ForwardAbiWireType.POINTER, ForwardAbiDirection.IN,
    ForwardTransfer("handle", BridgeType.ObjectHandle("sample.Counter"), ForwardFlow.INTO_KOTLIN,
      ForwardPassing.VALUE, ForwardOwnership.BORROWED, ForwardConversion.HANDLE_TO_STABLE_REF),
  )

  private fun error(): ForwardAbiParameter = ForwardAbiParameter(
    "errorOut", ForwardAbiWireType.POINTER, ForwardAbiDirection.OUT,
    ForwardTransfer("error", BridgeType.ObjectHandle("kotlin.Throwable"), ForwardFlow.OUT_OF_KOTLIN,
      ForwardPassing.OUT, ForwardOwnership.BORROWED, ForwardConversion.STABLE_REF_TO_HANDLE),
  )
}
