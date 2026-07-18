package io.github.xxfast.kotlin.native.nuget.processor.forward

import io.github.xxfast.kotlin.native.nuget.processor.cir.CirClass
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirDllImport
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirFile
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirMethod
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirNamespace
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirRenderer
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirStaticClass
import io.github.xxfast.kotlin.native.nuget.processor.cir.ordinaryNativeImports
import kotlin.test.Test
import kotlin.test.assertEquals

class ForwardCirPlanProjectionTest {
  @Test
  fun `class method import and wrapper derive from plan`() {
    val plan: ForwardCallablePlan = plan(
      symbol = "sample.Counter.increment",
      exportName = "counter_increment",
      receiver = "handle",
      result = BridgeType.Primitive(PrimitiveKind.INT),
      parameters = listOf("amount" to BridgeType.Primitive(PrimitiveKind.INT)),
    )

    val method: CirMethod = ForwardCirPlanProjection.classMethod(plan, "counter", isOverride = false)
    val import: CirDllImport = CirClass(
      name = "Counter",
      libraryName = "sample",
      nativePrefix = "counter",
      constructor = null,
      properties = emptyList(),
      methods = listOf(method),
    ).ordinaryNativeImports().single { it.name == "Native_Increment" }

    assertEquals("Increment", method.name)
    assertEquals("int", method.returnType)
    assertEquals("increment", method.nativeName)
    assertEquals(listOf("amount"), method.parameters.map { it.name })
    assertEquals("counter_increment", import.entryPoint)
    assertEquals("int", import.returnType)
    assertEquals(listOf("IntPtr", "int"), import.parameters.map { it.nativeType })
    assertEquals(true, import.hasSyncErrorOut)
    val rendered: String = CirRenderer().render(
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
    assertEquals(true, rendered.contains("Native_Increment(IntPtr handle, int amount, out IntPtr error)"))
    assertEquals(true, rendered.contains("Native_Increment(_handle, amount, out IntPtr error)"))
  }

  @Test
  fun `primitive extension import and wrapper derive receiver and arguments from plan`() {
    val plan: ForwardCallablePlan = plan(
      symbol = "sample.scale",
      exportName = "int_scale",
      receiver = "receiver",
      result = BridgeType.Unit,
      parameters = listOf("factor" to BridgeType.Primitive(PrimitiveKind.DOUBLE)),
    )

    val members = ForwardCirPlanProjection.extension(plan, "sample")
    val import: CirDllImport = members.filterIsInstance<CirDllImport>().single()
    val method: CirMethod = members.filterIsInstance<CirMethod>().single()

    assertEquals("int_scale", import.entryPoint)
    assertEquals(listOf("int", "double"), import.parameters.map { it.nativeType })
    assertEquals(true, import.hasSyncErrorOut)
    assertEquals("void", method.returnType)
    assertEquals(listOf("receiver", "factor"), method.parameters.map { it.name })
    assertEquals(listOf("int", "double"), method.parameters.map { it.type })
    assertEquals(true, method.isExtension)
    val rendered: String = CirRenderer().render(
      CirFile(
        namespaces = listOf(
          CirNamespace("Sample", listOf(CirStaticClass("IntExtensions", members))),
        ),
      ),
    )
    assertEquals(true, rendered.contains("Native_Scale(int receiver, double factor, out IntPtr error)"))
    assertEquals(true, rendered.contains("Native_Scale(receiver, factor, out IntPtr error)"))
  }

  private fun plan(
    symbol: String,
    exportName: String,
    receiver: String,
    result: BridgeType,
    parameters: List<Pair<String, BridgeType>>,
  ): ForwardCallablePlan {
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
    val receiverType: BridgeType = if (receiver == "handle") {
      BridgeType.ObjectHandle("sample.Counter")
    } else {
      BridgeType.Primitive(PrimitiveKind.INT)
    }
    val receiverParameter = ForwardAbiParameter(
      receiver,
      if (receiver == "handle") ForwardAbiWireType.POINTER else ForwardAbiWireType.INT32,
      ForwardAbiDirection.IN,
      ForwardTransfer(
        receiver,
        receiverType,
        ForwardFlow.INTO_KOTLIN,
        ForwardPassing.VALUE,
        ForwardOwnership.BORROWED,
        if (receiver == "handle") ForwardConversion.HANDLE_TO_STABLE_REF else ForwardConversion.DIRECT,
      ),
    )
    val values = parameters.map { (name, type) ->
      ForwardAbiParameter(
        name,
        wireType(type),
        ForwardAbiDirection.IN,
        ForwardTransfer(
          name,
          type,
          ForwardFlow.INTO_KOTLIN,
          ForwardPassing.VALUE,
          ForwardOwnership.BORROWED,
          ForwardConversion.DIRECT,
        ),
      )
    }
    val call = ForwardNativeCall(exportName, wireType(result), listOf(receiverParameter) + values + error)
    return ForwardCallablePlan(
      invocation = ForwardInvocation(symbol),
      publicSignature = ForwardPublicSignature(
        exportName.substringAfterLast('_').replaceFirstChar { it.uppercase() },
        parameters.map { (name, type) -> ForwardPublicParameter(name, type) },
        result,
      ),
      evaluation = ForwardEvaluation.EXACTLY_ONCE,
      nativeExports = listOf(call),
      nativeImports = listOf(call),
      result = ForwardResultConvention(
        wireType(result),
        ForwardTransfer(
          "result",
          result,
          ForwardFlow.OUT_OF_KOTLIN,
          ForwardPassing.VALUE,
          ForwardOwnership.BORROWED,
          ForwardConversion.DIRECT,
        ),
      ),
      errorSlot = error,
      helperRequirements = setOf(ForwardHelperRequirement.STABLE_REF),
    ).validate()
  }

  private fun wireType(type: BridgeType): ForwardAbiWireType = when (type) {
    BridgeType.Unit -> ForwardAbiWireType.VOID
    is BridgeType.Primitive -> when (type.kind) {
      PrimitiveKind.INT -> ForwardAbiWireType.INT32
      PrimitiveKind.DOUBLE -> ForwardAbiWireType.FLOAT64
      else -> error("Test only needs Int and Double")
    }

    else -> error("Test only needs direct types")
  }
}
