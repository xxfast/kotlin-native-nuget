package io.github.xxfast.kotlin.native.nuget.processor.forward

import com.squareup.kotlinpoet.FileSpec
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirClass
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirDllImport
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirFile
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirMethod
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirNamespace
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirRenderer
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirStaticClass
import io.github.xxfast.kotlin.native.nuget.processor.cir.ordinaryNativeImports
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class ForwardPhase4ProjectionTest {
  @Test
  fun `object List and nullable String results use their planned carriers`() {
    val objectPlan: ForwardCallablePlan = pointerPlan("sample.Counter.friend", BridgeType.ObjectHandle("sample.Friend"))
    val listPlan: ForwardCallablePlan = pointerPlan(
      "sample.Counter.scores",
      BridgeType.Collection(CollectionKind.LIST, element = BridgeType.Primitive(PrimitiveKind.INT)),
    )
    val stringPlan: ForwardCallablePlan = nullableStringPlan()

    assertContains(renderKotlin(objectPlan), "StableRef.create(handle.asStableRef<sample.Counter>().get().friend())")
    assertContains(renderKotlin(listPlan), "StableRef.create(handle.asStableRef<sample.Counter>().get().scores())")
    assertContains(renderKotlin(stringPlan), "): String? = try {")

    val objectMethod = ForwardCirPlanProjection.classMethod(objectPlan, "counter", isOverride = false)
    val listMethod = ForwardCirPlanProjection.classMethod(listPlan, "counter", isOverride = false)
    assertEquals("Friend", objectMethod.returnType)
    assertEquals("IReadOnlyList<int>", listMethod.returnType)
    assertEquals(true, ForwardHelperRequirement.COLLECTION in listPlan.helperRequirements)
    assertContains(renderClass(listMethod), "NugetListNative.Count(listHandle)")
  }

  @Test
  fun `nullable numeric extension preserves Boolean valueOut ABI`() {
    val plan: ForwardCallablePlan = nullableIntPlan(receiver = "receiver")
    val kotlin: String = renderKotlin(plan)
    val members = ForwardCirPlanProjection.extension(plan, "sample")
    val import: CirDllImport = members.filterIsInstance<CirDllImport>().single()
    val method: CirMethod = members.filterIsInstance<CirMethod>().single()
    val rendered: String = CirRenderer().render(
      CirFile(namespaces = listOf(CirNamespace("Sample", listOf(CirStaticClass("IntExtensions", members))))),
    )

    assertContains(kotlin, "valueOut: COpaquePointer?")
    assertContains(kotlin, "valueOut.reinterpret<IntVar>().pointed.value = result")
    assertContains(kotlin, "result != null")
    assertEquals("bool", import.returnType)
    assertEquals(listOf("int", "out int"), import.parameters.map { it.nativeType })
    assertEquals("int?", method.returnType)
    assertContains(rendered, "Native_Maybe(receiver, out int valueOut, out IntPtr error)")
  }

  private fun renderKotlin(plan: ForwardCallablePlan): String {
    val builder: FileSpec.Builder = FileSpec.builder("sample", "Exports")
    builder.addForwardKotlinPlanExport(plan)
    return builder.build().toString()
  }

  private fun renderClass(method: CirMethod): String = CirRenderer().render(
    CirFile(
      namespaces = listOf(
        CirNamespace(
          "Sample",
          listOf(
            CirClass(
              name = "Counter",
              libraryName = "sample",
              nativePrefix = "counter",
              constructor = null,
              properties = emptyList(),
              methods = listOf(method),
            ),
          ),
        ),
      ),
    ),
  )

  private fun pointerPlan(symbol: String, result: BridgeType): ForwardCallablePlan {
    val error = error()
    val call = ForwardNativeCall(
      symbol.substringAfterLast('.').let { "counter_$it" },
      ForwardAbiWireType.POINTER,
      listOf(handle()) + error,
    )
    val conversion = if (result is BridgeType.Collection) {
      ForwardConversion.COLLECTION_TO_HANDLE
    } else {
      ForwardConversion.STABLE_REF_TO_HANDLE
    }
    return ForwardCallablePlan(
      invocation = ForwardInvocation(symbol),
      publicSignature = ForwardPublicSignature(symbol.substringAfterLast('.').replaceFirstChar { it.uppercase() }, emptyList(), result),
      evaluation = ForwardEvaluation.EXACTLY_ONCE,
      nativeExports = listOf(call),
      nativeImports = listOf(call),
      result = ForwardResultConvention(
        ForwardAbiWireType.POINTER,
        ForwardTransfer("result", result, ForwardFlow.OUT_OF_KOTLIN, ForwardPassing.VALUE, ForwardOwnership.OWNED_HANDLE, conversion),
      ),
      errorSlot = error,
      cleanup = listOf(ForwardCleanup("result", ForwardCleanupKind.DISPOSE_STABLE_REF)),
      helperRequirements = setOf(ForwardHelperRequirement.STABLE_REF) +
          if (result is BridgeType.Collection) setOf(ForwardHelperRequirement.COLLECTION) else emptySet(),
    ).validate()
  }

  private fun nullableStringPlan(): ForwardCallablePlan {
    val result: BridgeType = BridgeType.Nullable(BridgeType.String)
    val error = error()
    val call = ForwardNativeCall("counter_label", ForwardAbiWireType.POINTER, listOf(handle()) + error)
    return ForwardCallablePlan(
      invocation = ForwardInvocation("sample.Counter.label"),
      publicSignature = ForwardPublicSignature("Label", emptyList(), result),
      evaluation = ForwardEvaluation.EXACTLY_ONCE,
      nativeExports = listOf(call),
      nativeImports = listOf(call),
      result = ForwardResultConvention(
        ForwardAbiWireType.POINTER,
        ForwardTransfer("result", result, ForwardFlow.OUT_OF_KOTLIN, ForwardPassing.VALUE, ForwardOwnership.MATERIALIZED, ForwardConversion.UTF8_TO_STRING),
      ),
      errorSlot = error,
      helperRequirements = setOf(ForwardHelperRequirement.STABLE_REF, ForwardHelperRequirement.UTF8),
    ).validate()
  }

  private fun nullableIntPlan(receiver: String): ForwardCallablePlan {
    val value: BridgeType = BridgeType.Primitive(PrimitiveKind.INT)
    val result: BridgeType = BridgeType.Nullable(value)
    val error = error()
    val receiverParameter = if (receiver == "handle") handle() else ForwardAbiParameter(
      "receiver",
      ForwardAbiWireType.INT32,
      ForwardAbiDirection.IN,
      direct("receiver", value, ForwardFlow.INTO_KOTLIN),
    )
    val valueOut = ForwardAbiParameter(
      "valueOut",
      ForwardAbiWireType.POINTER,
      ForwardAbiDirection.OUT,
      ForwardTransfer("valueOut", value, ForwardFlow.OUT_OF_KOTLIN, ForwardPassing.OUT, ForwardOwnership.BORROWED, ForwardConversion.DIRECT),
    )
    val call = ForwardNativeCall("int_maybe", ForwardAbiWireType.BOOLEAN, listOf(receiverParameter, valueOut, error))
    return ForwardCallablePlan(
      invocation = ForwardInvocation(if (receiver == "handle") "sample.Counter.maybe" else "sample.maybe"),
      publicSignature = ForwardPublicSignature("Maybe", emptyList(), result),
      evaluation = ForwardEvaluation.EXACTLY_ONCE,
      nativeExports = listOf(call),
      nativeImports = listOf(call),
      result = ForwardResultConvention(ForwardAbiWireType.BOOLEAN, direct("result", result, ForwardFlow.OUT_OF_KOTLIN)),
      errorSlot = error,
      helperRequirements = setOf(ForwardHelperRequirement.STABLE_REF),
    ).validate()
  }

  private fun handle(): ForwardAbiParameter = ForwardAbiParameter(
    "handle",
    ForwardAbiWireType.POINTER,
    ForwardAbiDirection.IN,
    ForwardTransfer("handle", BridgeType.ObjectHandle("sample.Counter"), ForwardFlow.INTO_KOTLIN, ForwardPassing.VALUE, ForwardOwnership.BORROWED, ForwardConversion.HANDLE_TO_STABLE_REF),
  )

  private fun error(): ForwardAbiParameter = ForwardAbiParameter(
    "errorOut",
    ForwardAbiWireType.POINTER,
    ForwardAbiDirection.OUT,
    ForwardTransfer("error", BridgeType.ObjectHandle("kotlin.Throwable"), ForwardFlow.OUT_OF_KOTLIN, ForwardPassing.OUT, ForwardOwnership.BORROWED, ForwardConversion.STABLE_REF_TO_HANDLE),
  )

  private fun direct(subject: String, type: BridgeType, flow: ForwardFlow): ForwardTransfer = ForwardTransfer(
    subject,
    type,
    flow,
    ForwardPassing.VALUE,
    ForwardOwnership.BORROWED,
    ForwardConversion.DIRECT,
  )
}
