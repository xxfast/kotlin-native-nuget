package io.github.xxfast.kotlin.native.nuget.processor.forward

import com.squareup.kotlinpoet.FileSpec
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirFile
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirNamespace
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirRenderer
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirStaticClass
import kotlin.test.Test
import kotlin.test.assertContains

class ForwardPhase6StaticCallableTest {
  @Test
  fun `top-level factory and object method share the handle result plan`() {
    val topLevel = factoryPlan(
      symbol = "sample.createCounter",
      export = "createCounter",
      origin = ForwardCallableOrigin.TOP_LEVEL,
      target = null,
    )
    val objectMethod = factoryPlan(
      symbol = "sample.Catalog.createCounter",
      export = "catalog_createCounter",
      origin = ForwardCallableOrigin.OBJECT,
      target = "sample.Catalog",
    )

    assertContains(renderKotlin(topLevel), "StableRef.create(createCounter())")
    assertContains(renderKotlin(objectMethod), "StableRef.create(sample.Catalog.createCounter())")
    assertContains(renderCsharp(topLevel), "return new Counter(nativeResult);")
    assertContains(renderCsharp(objectMethod), "EntryPoint = \"catalog_createCounter\"")
  }

  private fun renderKotlin(plan: ForwardCallablePlan): String {
    val builder: FileSpec.Builder = FileSpec.builder("sample", "Exports")
    builder.addForwardKotlinPlanExport(plan)
    return builder.build().toString()
  }

  private fun renderCsharp(plan: ForwardCallablePlan): String = CirRenderer().render(
    CirFile(
      namespaces = listOf(
        CirNamespace("Sample", listOf(CirStaticClass("Factories", ForwardCirPlanProjection.static(plan, "sample")))),
      ),
    ),
  )

  private fun factoryPlan(
    symbol: String,
    export: String,
    origin: ForwardCallableOrigin,
    target: String?,
  ): ForwardCallablePlan {
    val result = BridgeType.ObjectHandle("sample.Counter")
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
    val call = ForwardNativeCall(export, ForwardAbiWireType.POINTER, listOf(error))
    return ForwardCallablePlan(
      invocation = ForwardInvocation(symbol, origin = origin, target = target),
      publicSignature = ForwardPublicSignature("CreateCounter", emptyList(), result),
      evaluation = ForwardEvaluation.EXACTLY_ONCE,
      nativeExports = listOf(call),
      nativeImports = listOf(call),
      result = ForwardResultConvention(
        ForwardAbiWireType.POINTER,
        ForwardTransfer(
          subject = "result",
          type = result,
          flow = ForwardFlow.OUT_OF_KOTLIN,
          passing = ForwardPassing.VALUE,
          ownership = ForwardOwnership.OWNED_HANDLE,
          conversion = ForwardConversion.STABLE_REF_TO_HANDLE,
        ),
      ),
      errorSlot = error,
      cleanup = listOf(ForwardCleanup("result", ForwardCleanupKind.DISPOSE_STABLE_REF)),
      helperRequirements = setOf(ForwardHelperRequirement.STABLE_REF),
    ).validate()
  }
}
