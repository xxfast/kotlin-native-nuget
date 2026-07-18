package io.github.xxfast.kotlin.native.nuget.processor.forward

import com.squareup.kotlinpoet.FileSpec
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirClass
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirFile
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirNamespace
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirRenderer
import kotlin.test.Test
import kotlin.test.assertContains

class ForwardPhase5PropertyProjectionTest {
  @Test
  fun `nullable primitive property keeps its legacy two-call getter and nullable setter dispatch`() {
    val plan = nullableIntPropertyPlan()
    val kotlinBuilder: FileSpec.Builder = FileSpec.builder("sample", "Exports")
    kotlinBuilder.addForwardPropertyPlanExports(plan)
    val kotlin = kotlinBuilder.build().toString()
    val csharp = CirRenderer().render(
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
                properties = listOf(ForwardCirPropertyProjection.classProperty(plan)),
                methods = emptyList(),
              ),
            ),
          ),
        ),
      ),
    )

    assertContains(kotlin, "@CName(\"counter_get_score\")")
    assertContains(kotlin, "@CName(\"counter_get_score_value\")")
    assertContains(kotlin, "`value`: Int,")
    assertContains(kotlin, "@CName(\"counter_set_score_null\")")
    assertContains(csharp, "bool hasValue = Native_Get_score(_handle, out IntPtr error);")
    assertContains(csharp, "int value = Native_Get_score_value(_handle, out IntPtr error2);")
    assertContains(csharp, "if (value.HasValue)")
    assertContains(csharp, "Native_Set_score_null(_handle, out IntPtr error);")
  }

  private fun nullableIntPropertyPlan(): ForwardPropertyPlan {
    val type = BridgeType.Nullable(BridgeType.Primitive(PrimitiveKind.INT))
    val handle = ForwardAbiParameter(
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
    )
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
    val value = ForwardAbiParameter(
      name = "value",
      wireType = ForwardAbiWireType.INT32,
      direction = ForwardAbiDirection.IN,
      transfer = ForwardTransfer(
        subject = "value",
        type = BridgeType.Primitive(PrimitiveKind.INT),
        flow = ForwardFlow.INTO_KOTLIN,
        passing = ForwardPassing.VALUE,
        ownership = ForwardOwnership.BORROWED,
        conversion = ForwardConversion.DIRECT,
      ),
    )
    fun call(name: String, result: ForwardAbiWireType, parameters: List<ForwardAbiParameter>): ForwardNativeCall =
      ForwardNativeCall(name, result, parameters + error)

    return ForwardPropertyPlan(
      symbol = "sample.Counter.score",
      position = ForwardPropertyPosition.CLASS,
      receiver = ForwardPropertyReceiver.Handle("sample.Counter"),
      kotlinName = "score",
      publicName = "Score",
      type = type,
      getter = ForwardPropertyGetter.LegacyTwoCall(
        presence = call("counter_get_score", ForwardAbiWireType.BOOLEAN, listOf(handle)),
        value = call("counter_get_score_value", ForwardAbiWireType.INT32, listOf(handle)),
      ),
      setter = ForwardPropertySetter.NullableDispatch(
        value = call("counter_set_score", ForwardAbiWireType.VOID, listOf(handle, value)),
        nullValue = call("counter_set_score_null", ForwardAbiWireType.VOID, listOf(handle)),
      ),
    ).validate()
  }
}
