package io.github.xxfast.kotlin.native.nuget.processor.exports

import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Modifier
import com.squareup.kotlinpoet.ANY
import com.squareup.kotlinpoet.BOOLEAN
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.TypeName
import io.github.xxfast.kotlin.native.nuget.processor.cir.SharedFlowSurface
import io.github.xxfast.kotlin.native.nuget.processor.cir.expandAliases
import io.github.xxfast.kotlin.native.nuget.processor.cir.sharedFlowSurface
import io.github.xxfast.kotlin.native.nuget.processor.forward.BridgeType
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardBridgeTypeClassifier
import io.github.xxfast.kotlin.native.nuget.processor.forward.collectionResultProjection
import io.github.xxfast.kotlin.native.nuget.processor.forward.legacyFlowElementCollection
import io.github.xxfast.kotlin.native.nuget.processor.forward.legacyFlowElementEnvelope

/**
 * ADR-209: the Kotlin half of `KotlinSharedFlow<T>` (`ReplayCache`) and of
 * `KotlinMutableSharedFlow<T>` (`SubscriptionCount`, `EmitAsync`, `TryEmit`). Every export is
 * generated per module beside the member's `_collect`; no `nuget_*` runtime export is added. Two
 * keyings:
 *
 * - owner-keyed, for a property (`${prefix}_get_<p>_replay_cache`, `..._subscription_count`,
 *   `${prefix}_emit_<p>`, `${prefix}_try_emit_<p>`) and a re-invoked method return's replay cache;
 * - flow-keyed (`${stem}_replay_cache`, ...), for a held method return and an awaited one, which
 *   C# already holds by the flow's own handle.
 *
 * The three synchronous exports carry ADR-030's `errorOut`: a getter-backed member or a re-invoked
 * method can throw on every read, and a throw out of a `@CName` ends the process. `_emit` reports
 * through its completion callback like every suspend export (ADR-128's `launchForCSharp`).
 */

/** ADR-209: whether this function's return is a writable `MutableSharedFlow<T>` C# holds. */
internal fun KSFunctionDeclaration.returnsHeldMutableSharedFlow(): Boolean {
  if (modifiers.contains(Modifier.SUSPEND)) return false
  return sharedFlowSurface(returnType?.resolve()) == SharedFlowSurface.MUTABLE
}

/**
 * ADR-209: one replayed item, projected exactly as [itemBoxExpr] projects an `onNext` item but not
 * boxed: `nuget_list_get` mints each item's box, which the flow's own C# element read consumes.
 */
private fun replayItemExpr(
  elementNullable: Boolean,
  collection: BridgeType.Collection?,
  envelope: Boolean,
): String = when {
  envelope && elementNullable -> "if (value != null) buildError(value, ::nugetMappedType) else null"
  envelope -> "buildError(value, ::nugetMappedType)"
  elementNullable -> "value"
  collection != null -> collectionResultProjection("value", collection)
  else -> "value"
}

/** ADR-209: `NugetHandles.retain(<flow>.replayCache.map { ... })` for the flow type [flowType]. */
internal fun ForwardBridgeTypeClassifier.replayCacheExpression(
  flow: String,
  flowType: KSType,
): String {
  val element: KSType? = flowType.arguments.firstOrNull()?.type?.resolve()?.expandAliases()
  val item: String = replayItemExpr(
    elementNullable = element?.isMarkedNullable == true,
    collection = legacyFlowElementCollection(flowType),
    envelope = legacyFlowElementEnvelope(flowType),
  )
  return "NugetHandles.retain($flow.replayCache.map { value -> $item })"
}

/**
 * ADR-209: `return try { <expression> } catch { errorOut; <fallback> }`, ADR-030's synchronous
 * error slot. [expression] may hold `%T` placeholders for [arguments], which come first.
 */
internal fun FunSpec.Builder.addGuardedReturn(
  expression: String,
  fallback: String,
  vararg arguments: Any,
  errorOut: String = "errorOut",
): FunSpec.Builder = addCode(
  buildString {
    appendLine("return try {")
    appendLine("  $expression")
    appendLine("} catch (e: Throwable) {")
    appendLine("  if ($errorOut != null) {")
    appendLine("    $errorOut.reinterpret<%T>().pointed.value = %T.retain(")
    appendLine("      buildError(e, ::nugetMappedType)")
    appendLine("    )")
    appendLine("  }")
    appendLine("  $fallback")
    append("}")
  },
  *arguments,
  cOpaquePointerVar,
  nugetHandles,
)

/**
 * ADR-209: the launch body of an `_emit` export. The write slot is unwrapped BEFORE the launch,
 * while the C# argument's handle is still pinned by the call; an unwrap that throws (an enum
 * ordinal out of range) is reported through the callback instead of escaping the `@CName`.
 * [flow] is read inside the coroutine, so a throwing getter faults the `Task` too.
 */
private fun emitBody(scope: String, flow: String, assignment: String): String = buildString {
  appendLine("val element = try {")
  appendLine("  $assignment")
  appendLine("} catch (e: Throwable) {")
  appendLine("  return launchForCSharp($scope, callbackPtr, userData, ::nugetMappedType) { throw e }")
  appendLine("}")
  appendLine("return launchForCSharp($scope, callbackPtr, userData, ::nugetMappedType) {")
  appendLine("  $flow.emit(element)")
  appendLine("  null")
  append("}")
}

/**
 * ADR-209: the owner-keyed exports of a `SharedFlow`/`MutableSharedFlow` property beside its
 * `_collect` ([addFlowPropertyExports]). A nullable member found absent throws into the same
 * error slot the ADR-071 setter uses (the C# getter answers null before then).
 */
internal fun FileSpec.Builder.addSharedFlowPropertyExports(
  prop: KSPropertyDeclaration,
  qualifiedName: String,
  prefix: String,
  propName: String,
  propCall: String,
  flowType: KSType,
  classifier: ForwardBridgeTypeClassifier,
) {
  val surface: SharedFlowSurface = sharedFlowSurface(flowType)
  if (surface == SharedFlowSurface.NONE) return
  val holder = "obj.$propCall"
  val flow: String = if (flowType.isMarkedNullable) {
    val name: String = propCall.removeSurrounding("`")
    "($holder ?: throw IllegalStateException(\"$name is null\"))"
  } else {
    holder
  }
  val owner = "val obj = handle.asStableRef<$qualifiedName>().get()\n"
  addFunction(
    FunSpec.builder("export_${prefix}_get_${propName}_replay_cache")
      .addAnnotation(cNameAnnotation("${prefix}_get_${propName}_replay_cache", ownedBy(prop)))
      .addParameter("handle", cOpaquePointer)
      .addParameter("errorOut", cOpaquePointer.copy(nullable = true))
      .returns(cOpaquePointer.copy(nullable = true))
      .addCode(owner)
      .addGuardedReturn(classifier.replayCacheExpression(flow, flowType), "null")
      .build(),
  )
  if (surface != SharedFlowSurface.MUTABLE) return
  val element: KSType? = flowType.arguments.firstOrNull()?.type?.resolve()?.expandAliases()
  val slot: MutableStateFlowWriteSlot = mutableStateFlowWriteSlot(element)
  addFunction(
    FunSpec.builder("export_${prefix}_get_${propName}_subscription_count")
      .addAnnotation(
        cNameAnnotation("${prefix}_get_${propName}_subscription_count", ownedBy(prop)),
      )
      .addParameter("handle", cOpaquePointer)
      .addParameter("errorOut", cOpaquePointer.copy(nullable = true))
      .returns(cOpaquePointer.copy(nullable = true))
      // ADR-202: the count is read through the runtime-owned `nuget_stateflow_*` pair.
      .addCode("$INSTALL_MODULE_MAPPED_TYPE\n")
      .addCode(owner)
      .addGuardedReturn("NugetHandles.retain($flow.subscriptionCount)", "null")
      .build(),
  )
  addFunction(
    FunSpec.builder("export_${prefix}_emit_$propName")
      .addAnnotation(cNameAnnotation("${prefix}_emit_$propName", ownedBy(prop)))
      .addParameter("handle", cOpaquePointer)
      .addParameter("scopeHandle", cOpaquePointer)
      .addMutableStateFlowWriteSlot(slot)
      .addParameter("callbackPtr", cOpaquePointer)
      .addParameter("userData", cOpaquePointer)
      .returns(cOpaquePointer)
      .addCode(owner)
      .addCode("val scope = scopeHandle.asStableRef<CoroutineScope>().get()\n")
      .addCode(emitBody("scope", flow, slot.assignment))
      .build(),
  )
  addFunction(
    FunSpec.builder("export_${prefix}_try_emit_$propName")
      .addAnnotation(cNameAnnotation("${prefix}_try_emit_$propName", ownedBy(prop)))
      .addParameter("handle", cOpaquePointer)
      .addMutableStateFlowWriteSlot(slot)
      .addParameter("errorOut", cOpaquePointer.copy(nullable = true))
      .returns(BOOLEAN)
      .addCode(owner)
      .addGuardedReturn("$flow.tryEmit(${slot.assignment})", "false")
      .build(),
  )
}

/**
 * ADR-209: the exports of a shared flow C# holds by its own handle (a held method return, or the
 * value of a `suspend` member), entered at `${stem}_...`. The held route's `_collect` is the
 * acquired flow's ([addAcquiredFlowCollectExport]); the awaited route already has it.
 *
 * `_emit` launches on the scope C# passes, the one the flow's own collect uses. A top-level
 * `suspend` owner has none and passes null; its collect falls back to an ad-hoc scope, and so does
 * this.
 */
internal fun FileSpec.Builder.addFlowKeyedSharedFlowExports(
  stem: String,
  owner: KSDeclaration,
  returnType: KSType?,
  classifier: ForwardBridgeTypeClassifier,
) {
  val flowType: KSType = returnType?.expandAliases() ?: return
  val surface: SharedFlowSurface = sharedFlowSurface(flowType)
  if (surface == SharedFlowSurface.NONE) return
  val element: KSType? = flowType.arguments.firstOrNull()?.type?.resolve()?.expandAliases()
  val elementType: TypeName = element?.toBridgeTypeName() ?: ANY_NULLABLE
  val shared = "flowHandle.asStableRef<kotlinx.coroutines.flow.SharedFlow<%T>>().get()"
  addFunction(
    FunSpec.builder("export_${stem}_replay_cache")
      .addAnnotation(cNameAnnotation("${stem}_replay_cache", ownedBy(owner)))
      .addParameter("flowHandle", cOpaquePointer)
      .addParameter("errorOut", cOpaquePointer.copy(nullable = true))
      .returns(cOpaquePointer.copy(nullable = true))
      .addGuardedReturn(classifier.replayCacheExpression(shared, flowType), "null", elementType)
      .build(),
  )
  if (surface != SharedFlowSurface.MUTABLE) return
  val mutable = "flowHandle.asStableRef<kotlinx.coroutines.flow.MutableSharedFlow<%T>>().get()"
  val slot: MutableStateFlowWriteSlot = mutableStateFlowWriteSlot(element)
  addFunction(
    FunSpec.builder("export_${stem}_subscription_count")
      .addAnnotation(cNameAnnotation("${stem}_subscription_count", ownedBy(owner)))
      .addParameter("flowHandle", cOpaquePointer)
      .addParameter("errorOut", cOpaquePointer.copy(nullable = true))
      .returns(cOpaquePointer.copy(nullable = true))
      .addCode("$INSTALL_MODULE_MAPPED_TYPE\n")
      .addGuardedReturn("NugetHandles.retain($mutable.subscriptionCount)", "null", elementType)
      .build(),
  )
  addFunction(
    FunSpec.builder("export_${stem}_emit")
      .addAnnotation(cNameAnnotation("${stem}_emit", ownedBy(owner)))
      .addParameter("flowHandle", cOpaquePointer)
      .addParameter("scopeHandle", cOpaquePointer.copy(nullable = true))
      .addMutableStateFlowWriteSlot(slot)
      .addParameter("callbackPtr", cOpaquePointer)
      .addParameter("userData", cOpaquePointer)
      .returns(cOpaquePointer)
      .addCode("val flow = $mutable\n", elementType)
      .addCode(
        "val scope = scopeHandle?.asStableRef<CoroutineScope>()?.get() " +
          "?: CoroutineScope(Dispatchers.Default)\n",
      )
      .addCode(emitBody("scope", "flow", slot.assignment))
      .build(),
  )
  addFunction(
    FunSpec.builder("export_${stem}_try_emit")
      .addAnnotation(cNameAnnotation("${stem}_try_emit", ownedBy(owner)))
      .addParameter("flowHandle", cOpaquePointer)
      .addMutableStateFlowWriteSlot(slot)
      .addParameter("errorOut", cOpaquePointer.copy(nullable = true))
      .returns(BOOLEAN)
      .addGuardedReturn("$mutable.tryEmit(${slot.assignment})", "false", elementType)
      .build(),
  )
}

/**
 * ADR-209: a held `MutableSharedFlow<T>` method return's acquire body: the function runs once per
 * C# call and its flow is handed back as that flow's own handle (a null flow as a null pointer),
 * minted through `NugetHandles` (ADR-120). The C# wrapper owns the handle.
 */
internal fun buildSharedFlowAcquireMethodBody(
  qualifiedName: String,
  call: String,
  paramPrelude: String,
  obj: String,
  memberNullable: Boolean,
): String = buildString {
  appendLine("val $obj = handle.asStableRef<$qualifiedName>().get()")
  append(paramPrelude)
  if (memberNullable) {
    append("return $call?.let { flow -> NugetHandles.retain(flow) }")
  } else {
    append("return NugetHandles.retain($call)")
  }
}

private val ANY_NULLABLE: TypeName = ANY.copy(nullable = true)
