package io.github.xxfast.kotlin.native.nuget.processor.forward

import com.squareup.kotlinpoet.FileSpec
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirClass
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirFile
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirMethod
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirNamespace
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirRenderer
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class ForwardPhase8ProjectionTest {
  @Test
  fun `Char result uses Char return type and null-char catch default`() {
    val plan: ForwardCallablePlan = charResultPlan()
    val kotlin: String = renderKotlin(plan)
    val method: CirMethod = ForwardCirPlanProjection.classMethod(plan, "patient", isOverride = false)

    assertContains(kotlin, "): Char = try {")
    assertContains(kotlin, "'\\u0000'")
    assertEquals("char", method.returnType)
    assertEquals("char", method.nativeReturnType)
  }

  @Test
  fun `Enum result returns ordinal as Int and casts on the C# side`() {
    val plan: ForwardCallablePlan = enumResultPlan()
    val kotlin: String = renderKotlin(plan)
    val method: CirMethod = ForwardCirPlanProjection.classMethod(plan, "patient", isOverride = false)
    val rendered: String = renderClass(method)

    assertContains(kotlin, "): Int = try {")
    assertContains(kotlin, ".mood().ordinal")
    assertEquals("Mood", method.returnType)
    assertEquals("int", method.nativeReturnType)
    assertEquals(true, ForwardHelperRequirement.ENUM_ORDINAL in plan.helperRequirements)
    // Non-custom body relies on CirErrorRenderer's native/public cast when types differ.
    assertEquals(false, method.hasCustomBody)
    assertEquals("Mood", method.returnType)
  }

  @Test
  fun `Map and Set results materialize through NugetMapNative and NugetSetNative`() {
    val mapPlan: ForwardCallablePlan = collectionResultPlan(
      "sample.Patient.scores",
      BridgeType.Collection(
        CollectionKind.MAP,
        key = BridgeType.String,
        value = BridgeType.Primitive(PrimitiveKind.INT),
      ),
    )
    val setPlan: ForwardCallablePlan = collectionResultPlan(
      "sample.Patient.labels",
      BridgeType.Collection(CollectionKind.SET, element = BridgeType.String),
    )

    val mapMethod: CirMethod = ForwardCirPlanProjection.classMethod(mapPlan, "patient", isOverride = false)
    val setMethod: CirMethod = ForwardCirPlanProjection.classMethod(setPlan, "patient", isOverride = false)

    assertEquals("IReadOnlyDictionary<string, int>", mapMethod.returnType)
    assertEquals("IReadOnlySet<string>", setMethod.returnType)
    assertContains(renderClass(mapMethod), "NugetMapNative.Count(mapHandle)")
    assertContains(renderClass(setMethod), "NugetSetNative.Count(setHandle)")
    assertEquals(true, ForwardHelperRequirement.COLLECTION in mapPlan.helperRequirements)
    assertEquals(true, ForwardHelperRequirement.COLLECTION in setPlan.helperRequirements)
  }

  @Test
  fun `Char property getter returns Char with null-char catch default`() {
    val plan: ForwardPropertyPlan = charPropertyPlan()
    val kotlinBuilder: FileSpec.Builder = FileSpec.builder("sample", "Exports")
    kotlinBuilder.addForwardPropertyPlanExports(plan)
    val kotlin: String = kotlinBuilder.build().toString()
    val property = ForwardCirPropertyProjection.classProperty(plan)
    val csharp: String = CirRenderer().render(
      CirFile(
        namespaces = listOf(
          CirNamespace(
            "Sample",
            listOf(
              CirClass(
                name = "Patient",
                libraryName = "sample",
                nativePrefix = "patient",
                constructor = null,
                properties = listOf(property),
                methods = emptyList(),
              ),
            ),
          ),
        ),
      ),
    )

    assertContains(kotlin, "): Char = try {")
    assertContains(kotlin, "'\\u0000'")
    assertEquals("char", property.type)
    assertContains(csharp, "public char Grade")
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
              name = "Patient",
              libraryName = "sample",
              nativePrefix = "patient",
              constructor = null,
              properties = emptyList(),
              methods = listOf(method),
            ),
          ),
        ),
      ),
    ),
  )

  private fun charResultPlan(): ForwardCallablePlan {
    val result = BridgeType.Char
    val error = error()
    val call = ForwardNativeCall("patient_initial", ForwardAbiWireType.CHAR16, listOf(handle()) + error)
    return ForwardCallablePlan(
      invocation = ForwardInvocation("sample.Patient.initial"),
      publicSignature = ForwardPublicSignature("Initial", emptyList(), result),
      evaluation = ForwardEvaluation.EXACTLY_ONCE,
      nativeExports = listOf(call),
      nativeImports = listOf(call),
      result = ForwardResultConvention(
        ForwardAbiWireType.CHAR16,
        ForwardTransfer(
          "result", result, ForwardFlow.OUT_OF_KOTLIN, ForwardPassing.VALUE,
          ForwardOwnership.BORROWED, ForwardConversion.DIRECT,
        ),
      ),
      errorSlot = error,
      helperRequirements = setOf(ForwardHelperRequirement.STABLE_REF),
    ).validate()
  }

  private fun enumResultPlan(): ForwardCallablePlan {
    val result = BridgeType.Enum("sample.Mood")
    val error = error()
    val call = ForwardNativeCall("patient_mood", ForwardAbiWireType.INT32, listOf(handle()) + error)
    return ForwardCallablePlan(
      invocation = ForwardInvocation("sample.Patient.mood"),
      publicSignature = ForwardPublicSignature("Mood", emptyList(), result),
      evaluation = ForwardEvaluation.EXACTLY_ONCE,
      nativeExports = listOf(call),
      nativeImports = listOf(call),
      result = ForwardResultConvention(
        ForwardAbiWireType.INT32,
        ForwardTransfer(
          "result", result, ForwardFlow.OUT_OF_KOTLIN, ForwardPassing.VALUE,
          ForwardOwnership.BORROWED, ForwardConversion.ENUM_TO_ORDINAL,
        ),
      ),
      errorSlot = error,
      helperRequirements = setOf(ForwardHelperRequirement.STABLE_REF, ForwardHelperRequirement.ENUM_ORDINAL),
    ).validate()
  }

  private fun collectionResultPlan(symbol: String, result: BridgeType.Collection): ForwardCallablePlan {
    val error = error()
    val export: String = "patient_" + symbol.substringAfterLast('.')
    val call = ForwardNativeCall(export, ForwardAbiWireType.POINTER, listOf(handle()) + error)
    return ForwardCallablePlan(
      invocation = ForwardInvocation(symbol),
      publicSignature = ForwardPublicSignature(
        symbol.substringAfterLast('.').replaceFirstChar { it.uppercase() },
        emptyList(),
        result,
      ),
      evaluation = ForwardEvaluation.EXACTLY_ONCE,
      nativeExports = listOf(call),
      nativeImports = listOf(call),
      result = ForwardResultConvention(
        ForwardAbiWireType.POINTER,
        ForwardTransfer(
          "result", result, ForwardFlow.OUT_OF_KOTLIN, ForwardPassing.VALUE,
          ForwardOwnership.OWNED_HANDLE, ForwardConversion.COLLECTION_TO_HANDLE,
        ),
      ),
      errorSlot = error,
      cleanup = listOf(ForwardCleanup("result", ForwardCleanupKind.DISPOSE_STABLE_REF)),
      helperRequirements = setOf(ForwardHelperRequirement.STABLE_REF, ForwardHelperRequirement.COLLECTION),
    ).validate()
  }

  private fun charPropertyPlan(): ForwardPropertyPlan {
    val handle = handle()
    val error = error()
    val call = ForwardNativeCall("patient_get_grade", ForwardAbiWireType.CHAR16, listOf(handle, error))
    return ForwardPropertyPlan(
      symbol = "sample.Patient.grade",
      position = ForwardPropertyPosition.CLASS,
      receiver = ForwardPropertyReceiver.Handle("sample.Patient"),
      kotlinName = "grade",
      publicName = "Grade",
      type = BridgeType.Char,
      getter = ForwardPropertyGetter.Direct(call),
    ).validate()
  }

  private fun handle(): ForwardAbiParameter = ForwardAbiParameter(
    "handle",
    ForwardAbiWireType.POINTER,
    ForwardAbiDirection.IN,
    ForwardTransfer(
      "handle", BridgeType.ObjectHandle("sample.Patient"), ForwardFlow.INTO_KOTLIN,
      ForwardPassing.VALUE, ForwardOwnership.BORROWED, ForwardConversion.HANDLE_TO_STABLE_REF,
    ),
  )

  private fun error(): ForwardAbiParameter = ForwardAbiParameter(
    "errorOut",
    ForwardAbiWireType.POINTER,
    ForwardAbiDirection.OUT,
    ForwardTransfer(
      "error", BridgeType.ObjectHandle("kotlin.Throwable"), ForwardFlow.OUT_OF_KOTLIN,
      ForwardPassing.OUT, ForwardOwnership.BORROWED, ForwardConversion.STABLE_REF_TO_HANDLE,
    ),
  )
}
