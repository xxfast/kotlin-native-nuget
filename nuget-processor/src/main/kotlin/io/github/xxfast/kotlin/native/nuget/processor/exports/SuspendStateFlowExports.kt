package io.github.xxfast.kotlin.native.nuget.processor.exports

import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec

/**
 * ADR-068: shared generic `@CName` exports keyed on an already-obtained `StateFlow<*>` handle.
 *
 * A `suspend fun` returning `StateFlow<T>` cannot reuse ADR-065's per-member `_collect`/`_value`
 * exports (those re-invoke `obj.member(...)`, which is a suspend call and cannot be made from a
 * non-suspend `@CName` export). Instead, the suspend `_async` export (`SuspendFunctionExports.kt`,
 * unchanged) hands the awaited `StateFlow` object back as a `StableRef` handle, and these two
 * exports operate directly on that handle -- generated once per module, not once per member.
 *
 * @see <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/068-suspend-returning-stateflow.md">ADR-068: suspend fun returning StateFlow</a>
 */
internal fun FileSpec.Builder.addStateFlowHandleExports() {
  addFunction(
    FunSpec.builder("export_nuget_stateflow_collect")
      .addAnnotation(cNameAnnotation("nuget_stateflow_collect"))
      .addParameter("flowHandle", cOpaquePointer)
      .addParameter("scopeHandle", cOpaquePointer)
      .addParameter("onNextPtr", cOpaquePointer)
      .addParameter("onCompletePtr", cOpaquePointer)
      .addParameter("onErrorPtr", cOpaquePointer)
      .addParameter("userData", cOpaquePointer)
      .returns(cOpaquePointer)
      .addCode(buildStateFlowHandleCollectBody())
      .build()
  )

  addFunction(
    FunSpec.builder("export_nuget_stateflow_value")
      .addAnnotation(cNameAnnotation("nuget_stateflow_value"))
      .addParameter("flowHandle", cOpaquePointer)
      .returns(cOpaquePointer)
      .addStatement(
        "return %T.create(flowHandle.asStableRef<$STATE_FLOW_STAR>()" +
            ".get().value as Any).asCPointer()",
        stableRef,
      )
      .build()
  )
}

private const val STATE_FLOW_STAR = "kotlinx.coroutines.flow.StateFlow<*>"

private fun buildStateFlowHandleCollectBody(): String = buildString {
  appendLine("val flow = flowHandle.asStableRef<$STATE_FLOW_STAR>().get()")
  appendLine("val scope = scopeHandle.asStableRef<CoroutineScope>().get()")
  appendLine(
    "val onNext = onNextPtr.reinterpret<CFunction<" +
        "(COpaquePointer?, Byte, COpaquePointer) -> Unit>>()"
  )
  appendLine(
    "val onComplete = onCompletePtr.reinterpret<CFunction<" +
        "(COpaquePointer) -> Unit>>()"
  )
  appendLine(
    "val onError = onErrorPtr.reinterpret<CFunction<" +
        "(COpaquePointer?, COpaquePointer) -> Unit>>()"
  )
  appendLine("val job = scope.launch(start = CoroutineStart.ATOMIC) {")
  appendLine("  try {")
  appendLine("    flow.collect { value ->")
  appendLine("      val itemRef = StableRef.create(value as Any).asCPointer()")
  appendLine("      onNext.invoke(itemRef, 0.toByte(), userData)")
  appendLine("    }")
  appendLine("    onComplete.invoke(userData)")
  appendLine("  } catch (e: CancellationException) {")
  appendLine("    onNext.invoke(null, 1.toByte(), userData)")
  appendLine("    throw e")
  appendLine("  } catch (e: Throwable) {")
  appendLine("    val errRef = StableRef.create(buildError(e)).asCPointer()")
  appendLine("    onError.invoke(errRef, userData)")
  appendLine("  }")
  appendLine("}")
  append("return StableRef.create(job).asCPointer()")
}
