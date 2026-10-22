package io.github.xxfast.kotlin.native.nuget.processor.exports

import com.google.devtools.ksp.symbol.KSDeclaration
import com.squareup.kotlinpoet.BOOLEAN
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.UNIT
import io.github.xxfast.kotlin.native.nuget.processor.ForwardSymbolTable
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardExportOwnerTag

/**
 * ADR-232: the handle-keyed exports a C#-side hot flow reads and writes through, so a
 * `KotlinMutableStateFlow<T>.Create` or `KotlinMutableSharedFlow<T>.Create` made in C# is a real
 * Kotlin `MutableStateFlow` / `MutableSharedFlow` a C#-implemented interface's hot-flow slot hands
 * back. Values cross as ADR-214 boxes (`NugetMarshal.Wrap<T>` in, `NugetHandles.retain` out), so
 * one set serves every element type.
 */
internal object HotFlowHandleExports {
  fun name(symbols: ForwardSymbolTable, stem: String): String =
    "${symbols.librarySegment}__hotflow_$stem"
}

private const val RUNTIME: String = "io.github.xxfast.kotlin.native.nuget.runtime"
private const val FLOWS: String = "kotlinx.coroutines.flow"

internal fun FileSpec.Builder.addHotFlowHandleExports(
  symbols: ForwardSymbolTable,
  owner: KSDeclaration,
) {
  val tag = ForwardExportOwnerTag(declaration = owner, role = "hot flow handle")
  val pointer: TypeName = cOpaquePointer
  val nullable: TypeName = cOpaquePointer.copy(nullable = true)
  fun export(
    stem: String,
    parameters: List<Pair<String, TypeName>>,
    returns: TypeName,
    body: String,
  ) {
    val name: String = HotFlowHandleExports.name(symbols, stem)
    val builder: FunSpec.Builder = FunSpec.builder("export_$name")
      .addAnnotation(cNameAnnotation(name, tag))
      .returns(returns)
      .addCode(body)
    parameters.forEach { (parameter, type) -> builder.addParameter(parameter, type) }
    addFunction(builder.build())
  }
  val unbox = "?.asStableRef<Any>()?.get()"
  export(
    "state_create", listOf("initial" to nullable), pointer,
    "return NugetHandles.retain($FLOWS.MutableStateFlow<Any?>(initial$unbox))\n",
  )
  export(
    "state_value", listOf("handle" to pointer), nullable,
    "return handle.asStableRef<$FLOWS.StateFlow<Any?>>().get().value?.let { NugetHandles.retain(it) }\n",
  )
  export(
    "state_set", listOf("handle" to pointer, "value" to nullable), UNIT,
    "handle.asStableRef<$FLOWS.MutableStateFlow<Any?>>().get().value = value$unbox\n",
  )
  export(
    "state_compare_and_set", listOf("handle" to pointer, "expect" to nullable, "update" to nullable), BOOLEAN,
    "return handle.asStableRef<$FLOWS.MutableStateFlow<Any?>>().get()" +
      ".compareAndSet(expect$unbox, update$unbox)\n",
  )
  // An unbounded buffer, so `tryEmit` never drops for a slow collector and `emit` never suspends.
  export(
    "shared_create", listOf("replay" to INT), pointer,
    "return NugetHandles.retain($FLOWS.MutableSharedFlow<Any?>(replay, Int.MAX_VALUE - replay))\n",
  )
  export(
    "shared_replay_cache", listOf("handle" to pointer), pointer,
    "return NugetHandles.retain(handle.asStableRef<$FLOWS.SharedFlow<Any?>>().get().replayCache.toList())\n",
  )
  export(
    "shared_subscription_count", listOf("handle" to pointer), pointer,
    "return NugetHandles.retain(handle.asStableRef<$FLOWS.MutableSharedFlow<Any?>>().get().subscriptionCount)\n",
  )
  export(
    "shared_try_emit", listOf("handle" to pointer, "value" to nullable), BOOLEAN,
    "return handle.asStableRef<$FLOWS.MutableSharedFlow<Any?>>().get().tryEmit(value$unbox)\n",
  )
  export(
    "shared_emit",
    listOf(
      "handle" to pointer, "scopeHandle" to pointer, "value" to nullable, "callbackPtr" to pointer,
      "userData" to pointer,
    ),
    pointer,
    "val flow = handle.asStableRef<$FLOWS.MutableSharedFlow<Any?>>().get()\n" +
      "val element: Any? = value$unbox\n" +
      "val scope = scopeHandle.asStableRef<kotlinx.coroutines.CoroutineScope>().get()\n" +
      "return $RUNTIME.launchForCSharp(scope, callbackPtr, userData, ::nugetMappedType) {\n" +
      "  flow.emit(element)\n" +
      "  null\n" +
      "}\n",
  )
  // Any hot flow, StateFlow or SharedFlow, collected on the C# enumerator's scope.
  export(
    "collect",
    listOf(
      "handle" to pointer, "scopeHandle" to pointer, "onNextPtr" to pointer,
      "onCompletePtr" to pointer, "onErrorPtr" to pointer, "userData" to pointer,
    ),
    pointer,
    "val flow = handle.asStableRef<$FLOWS.Flow<Any?>>().get()\n" +
      "val scope = scopeHandle.asStableRef<kotlinx.coroutines.CoroutineScope>().get()\n" +
      "return $RUNTIME.collectForCSharp(scope, onNextPtr, onCompletePtr, onErrorPtr, userData, " +
      "::nugetMappedType) { emit ->\n" +
      "  flow.collect { value -> emit(value?.let { NugetHandles.retain(it) }) }\n" +
      "}\n",
  )
  // The slot's handle back: a new owned handle to the same flow object, so the C# wrapper may be
  // collected the moment the slot returns.
  export(
    "retain", listOf("handle" to pointer), pointer,
    "return NugetHandles.retain(handle.asStableRef<Any>().get())\n",
  )
}
