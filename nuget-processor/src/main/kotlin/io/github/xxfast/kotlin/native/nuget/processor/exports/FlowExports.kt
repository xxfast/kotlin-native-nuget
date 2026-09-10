package io.github.xxfast.kotlin.native.nuget.processor.exports

import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Visibility
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.TypeName
import io.github.xxfast.kotlin.native.nuget.processor.cir.MUTABLE_STATE_FLOW_TYPES
import io.github.xxfast.kotlin.native.nuget.processor.cir.STATE_FLOW_TYPES
import io.github.xxfast.kotlin.native.nuget.processor.cir.expandAliases
import io.github.xxfast.kotlin.native.nuget.processor.cir.isMutableStateFlowElementObject
import io.github.xxfast.kotlin.native.nuget.processor.cir.isMutableStateFlowElementSupported
import io.github.xxfast.kotlin.native.nuget.processor.forward.BridgeType
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardBridgeTypeClassifier
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardCallablePlanCatalog
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardLegacyParameterShape
import io.github.xxfast.kotlin.native.nuget.processor.forward.collectionResultProjection
import io.github.xxfast.kotlin.native.nuget.processor.forward.isLegacyLowered
import io.github.xxfast.kotlin.native.nuget.processor.forward.isOptInRefused
import io.github.xxfast.kotlin.native.nuget.processor.forward.legacyFlowElementCollection
import io.github.xxfast.kotlin.native.nuget.processor.forward.legacyLoweredName
import io.github.xxfast.kotlin.native.nuget.processor.forward.legacyParameterShapes
import io.github.xxfast.kotlin.native.nuget.processor.forward.legacyPrelude
import io.github.xxfast.kotlin.native.nuget.processor.forward.legacyRefusedFlowElement
import io.github.xxfast.kotlin.native.nuget.processor.forward.legacyRefusedParameter
import io.github.xxfast.kotlin.native.nuget.processor.forward.legacyRefusedReturn
import io.github.xxfast.kotlin.native.nuget.processor.toCName

/**
 * ADR-065/ADR-067/ADR-071's `Flow` and `StateFlow` route, one emitter per member kind.
 *
 * ADR-124 lifted these two emitters out of [addClassExports] so a **sealed arm** can mint the
 * identical exports under its own `${sealed}_${sub}` prefix, which is the same lift ADR-118 made
 * for the suspend route. The callers keep owning membership filtering: which properties and which
 * methods belong to an owner is the caller's rule (all-properties for an arm's properties per
 * ADR-111, declared-only for its methods per ADR-116), and only the per-member emission lives here.
 *
 * @see <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/065-stateflow-mapping.md">ADR-065: StateFlow mapping</a>
 * @see <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/124-flow-route-sealed-arm-owners.md">ADR-124: Flow route on sealed-arm owners</a>
 */

/** The Flow/StateFlow family this route owns, on an already alias-expanded type or not. */
internal fun KSType.isForwardFlowType(): Boolean {
  val qualified: String? = expandAliases().declaration.qualifiedName?.asString()
  return qualified == "kotlinx.coroutines.flow.Flow" || qualified in STATE_FLOW_TYPES
}

/** Whether the member returns a Flow/StateFlow, i.e. belongs to this route rather than the plan. */
internal fun KSFunctionDeclaration.returnsForwardFlow(): Boolean =
  returnType?.resolve()?.isForwardFlowType() == true

/**
 * ADR-124: a sealed arm's flow properties, the **all-properties** rule ADR-111 fixed for the
 * sealed route (`superClass = null`): the generated C# base is abstract and carries no members, so
 * a flow property the Kotlin base declares has to bind on every arm under that arm's own prefix.
 *
 * One selector for three halves (the Kotlin export loop, the two Kotlin gates and the C#
 * translator), because an import with no export behind it is exactly what a second copy of this
 * rule produces.
 */
internal fun KSClassDeclaration.forwardArmFlowProperties(
  classifier: ForwardBridgeTypeClassifier,
): List<KSPropertyDeclaration> = getAllProperties()
  .filter { it.getVisibility() == Visibility.PUBLIC }
  .filter { prop -> prop.type.resolve().expandAliases().isForwardFlowType() }
  // Issue #121: a marked declaration must reach neither artifact, legacy route or not.
  .filter { prop -> !prop.isOptInRefused() }
  // ADR-123: an element this route cannot marshal drops the property on both halves;
  // `warnRefusedLegacyRouteMembers` names it once.
  .filter { prop -> classifier.legacyRefusedFlowElement(prop.type.resolve()) == null }
  .toList()

/**
 * ADR-124: a sealed arm's flow-returning methods, **declared-only** (`parentDeclaration == this`),
 * which is ADR-116's rule for the arm's method surface and ADR-118's for its suspend members. A
 * base `open fun` returning a Flow that no arm overrides therefore belongs to no arm.
 */
internal fun KSClassDeclaration.forwardArmFlowMethods(
  classifier: ForwardBridgeTypeClassifier,
): List<KSFunctionDeclaration> = getAllFunctions()
  .filter { it.getVisibility() == Visibility.PUBLIC }
  .filter { it.parentDeclaration == this }
  .filter { !it.modifiers.contains(Modifier.SUSPEND) }
  .filter { it.returnsForwardFlow() }
  // ADR-114 / ADR-119 / ADR-123: the three refusals the ordinary route applies upstream of its own
  // projection. Both halves must agree, or a C# import arrives with no Kotlin export behind it.
  .filter { method -> classifier.legacyRefusedParameter(method.parameters) == null }
  .filter { method -> classifier.legacyRefusedReturn(method) == null }
  .toList()

/**
 * ADR-071 (2026-09-11): whether this function's return is a `MutableStateFlow<T>` the bridge holds
 * by handle. One predicate for four halves (the Kotlin emitter, the C# translator, and the two
 * gates that emit ADR-068's shared handle-keyed exports on either side), because the held route
 * reads through exports generated once per module: a gate that disagrees with the emitter is an
 * `EntryPointNotFoundException` at the first `.Value`, not a compile error.
 *
 * A `suspend fun` is excluded: ADR-068 already hands its awaited flow back by handle.
 */
internal fun KSFunctionDeclaration.returnsHeldMutableStateFlow(): Boolean {
  if (modifiers.contains(Modifier.SUSPEND)) return false
  val resolved: KSType = returnType?.resolve()?.expandAliases() ?: return false
  if (resolved.declaration.qualifiedName?.asString() !in MUTABLE_STATE_FLOW_TYPES) return false
  // ADR-067's nullable element/member threading is deferred on the settable route, on both halves.
  if (resolved.isMarkedNullable) return false
  val element: KSType? = resolved.arguments.firstOrNull()?.type?.resolve()
  if (element?.isMarkedNullable == true) return false
  return isMutableStateFlowElementSupported(element)
}

/** Whether the arm owns a coroutine scope through this route (a flow property or a flow method). */
internal fun KSClassDeclaration.declaresOrInheritsFlowMember(
  classifier: ForwardBridgeTypeClassifier,
): Boolean = forwardArmFlowProperties(classifier).isNotEmpty() ||
    forwardArmFlowMethods(classifier).isNotEmpty()

/**
 * ADR-065: the `_collect` export, plus StateFlow's synchronous `_value`, ADR-067's `_has_value`
 * probe and ADR-071's `_set_value` write. [prefix] is the owner's export prefix: an ordinary
 * class's simple name lowercased, or (ADR-124) `${sealed}_${sub}` for a sealed arm.
 */
internal fun FileSpec.Builder.addFlowPropertyExports(
  prop: KSPropertyDeclaration,
  qualifiedName: String,
  prefix: String,
  classifier: ForwardBridgeTypeClassifier,
) {
  val propName: String = prop.simpleName.asString()
  val propTypeResolved: KSType = prop.type.resolve().expandAliases()
  val propType: String = propTypeResolved.declaration.qualifiedName?.asString() ?: "Any"
  // ADR-065: StateFlow (and the read-only MutableStateFlow view) is checked before/alongside
  // plain Flow. The `_collect` export is byte-for-byte the same shape for both (StateFlow's
  // `collect` is inherited from Flow); StateFlow additionally gets a synchronous `_value` export.
  val isStateFlowProperty: Boolean = propType in STATE_FLOW_TYPES
  val isFlowProperty: Boolean = propType == "kotlinx.coroutines.flow.Flow"
  require(isFlowProperty || isStateFlowProperty) {
    "addFlowPropertyExports is the Flow/StateFlow route; got $propType"
  }

  // ADR-123: an element this route cannot marshal drops the property, matching the C# half.
  // `NugetProcessor` names it once as a SKIPPED_UNSUPPORTED_PROPERTY.
  if (classifier.legacyRefusedFlowElement(propTypeResolved) != null) return

  val flowElementType: KSType? = propTypeResolved.arguments.firstOrNull()?.type?.resolve()
  val flowElementQualified: String =
    flowElementType?.declaration?.qualifiedName?.asString() ?: "kotlin.Any"
  // ADR-067: nullable element/member threading is StateFlow-only; nullable Flow stays deferred.
  val elementNullable: Boolean = isStateFlowProperty && flowElementType?.isMarkedNullable == true
  val memberNullable: Boolean = isStateFlowProperty && propTypeResolved.isMarkedNullable
  // ADR-123: a collection element crosses as the ordinary route's boxed wire container, so a
  // component that projects at the seam (a value class to its underlying, an enum to its
  // ordinal) has to leave as that wire value or the C# per-element read decodes the wrong box.
  val flowElementCollection: BridgeType.Collection? =
    classifier.legacyFlowElementCollection(propTypeResolved)

  addFunction(
    FunSpec.builder("export_${prefix}_get_${propName}_collect")
      .addAnnotation(cNameAnnotation("${prefix}_get_${propName}_collect"))
      .addParameter("handle", cOpaquePointer)
      .addParameter("scopeHandle", cOpaquePointer)
      .addParameter("onNextPtr", cOpaquePointer)
      .addParameter("onCompletePtr", cOpaquePointer)
      .addParameter("onErrorPtr", cOpaquePointer)
      .addParameter("userData", cOpaquePointer)
      .returns(cOpaquePointer)
      .addCode(
        buildFlowCollectBody(
          qualifiedName, propName, flowElementQualified, elementNullable, memberNullable,
          flowElementCollection,
        )
      )
      .build()
  )

  if (isStateFlowProperty) {
    // ADR-065: synchronous `_value` export -- boxes `stateFlow.value as Any` into a StableRef,
    // structurally identical to a single onNext emission. No errorOut: StateFlow.value cannot
    // throw (a deliberate narrowing of ADR-030's wrap-all-property-getters policy).
    // ADR-067: a nullable element or nullable member widens the return to `COpaquePointer?` and
    // guards the box with `if (v != null) … else null`.
    addFunction(
      FunSpec.builder("export_${prefix}_get_${propName}_value")
        .addAnnotation(cNameAnnotation("${prefix}_get_${propName}_value"))
        .addParameter("handle", cOpaquePointer)
        .returns(
          if (elementNullable || memberNullable) cOpaquePointer.copy(nullable = true)
          else cOpaquePointer
        )
        .addCode(
          buildStateFlowValuePropertyBody(
            qualifiedName, propName, elementNullable, memberNullable, flowElementCollection,
          ),
        )
        .build()
    )

    if (memberNullable) {
      // ADR-067: nullable member -- presence-probe export backing the C# `_has_value` two-call
      // pattern; the getter returns `null` when this is false, else constructs normally.
      addFunction(
        FunSpec.builder("export_${prefix}_get_${propName}_has_value")
          .addAnnotation(cNameAnnotation("${prefix}_get_${propName}_has_value"))
          .addParameter("handle", cOpaquePointer)
          .returns(Boolean::class)
          .addCode(buildStateFlowHasValuePropertyBody(qualifiedName, propName))
          .build()
      )
    }

    // ADR-071: a genuinely DECLARED MutableStateFlow<T> (not narrowed through .asStateFlow())
    // additionally gains a settable `.Value`, gated on non-nullable element/member (both
    // deferred) and a v1-supported element (primitive/String/object; enum stays deferred).
    val isMutableStateFlowProperty: Boolean = propType in MUTABLE_STATE_FLOW_TYPES &&
        !elementNullable && !memberNullable &&
        isMutableStateFlowElementSupported(flowElementType)
    if (isMutableStateFlowProperty) {
      val (valueParamType: TypeName, assignment: String) =
        mutableStateFlowValueParameter(flowElementType)
      addFunction(
        FunSpec.builder("export_${prefix}_set_${propName}_value")
          .addAnnotation(cNameAnnotation("${prefix}_set_${propName}_value"))
          .addParameter("handle", cOpaquePointer)
          .addParameter("value", valueParamType)
          .addParameter("errorOut", cOpaquePointer.copy(nullable = true))
          .addCode(
            buildStateFlowSetValuePropertyBody(qualifiedName, propName, assignment),
            cOpaquePointerVar, nugetHandles,
          )
          .build()
      )
    }
  }
}

/**
 * ADR-065: the method half of the same route, `${prefix}_${cname}_collect` and its StateFlow
 * siblings, where `cname` carries the planner's overload number (issue #97) so an arm's overload
 * pair numbers exactly as an ordinary class's does.
 */
internal fun FileSpec.Builder.addFlowMethodExports(
  method: KSFunctionDeclaration,
  qualifiedName: String,
  prefix: String,
  classifier: ForwardBridgeTypeClassifier,
  callableCatalog: ForwardCallablePlanCatalog,
) {
  val methodName: String = method.simpleName.asString()
  // Issue #97: carry the planner's overload number, as the C# side does (ADR-090).
  val cname: String = toCName(methodName) + callableCatalog.overloadSuffix(method)
  val returnType: KSType? = method.returnType?.resolve()?.expandAliases()
  val returnQualified: String? = returnType?.declaration?.qualifiedName?.asString()
  val isStateFlowMethod: Boolean = returnQualified in STATE_FLOW_TYPES
  val flowElementType: KSType? = returnType?.arguments?.firstOrNull()?.type?.resolve()
  val flowElementQualified: String =
    flowElementType?.declaration?.qualifiedName?.asString() ?: "kotlin.Any"
  // ADR-067: nullable element/member threading is StateFlow-only; nullable Flow stays deferred.
  val elementNullable: Boolean = isStateFlowMethod && flowElementType?.isMarkedNullable == true
  val memberNullable: Boolean = isStateFlowMethod && returnType?.isMarkedNullable == true
  // ADR-123: the element-side twin of the parameter lowering below -- a collection element
  // leaves per-element projected, exactly as the ordinary route's collection result does.
  val flowElementCollection: BridgeType.Collection? =
    classifier.legacyFlowElementCollection(returnType)

  // ADR-114: a collection parameter is dereferenced and copied out of its wire container
  // eagerly, before `launch`, and the member is called with that local instead of the raw
  // handle. Every other parameter keeps its shipped spelling.
  val paramShapes: List<ForwardLegacyParameterShape> =
    classifier.legacyParameterShapes(method.parameters)

  val paramCall: String = method.parameters
    .mapIndexed { index, param ->
      val paramName: String = param.name?.asString() ?: "_"
      if (paramShapes[index].isLegacyLowered()) legacyLoweredName(paramName) else paramName
    }
    .joinToString(", ")

  val paramPrelude: String = buildString {
    method.parameters.forEachIndexed { index, param ->
      val prelude: String? = paramShapes[index].legacyPrelude(param.name?.asString() ?: "_")
      if (prelude != null) appendLine(prelude)
    }
  }

  fun FunSpec.Builder.addFlowParameters() {
    method.parameters.forEachIndexed { index, param ->
      val paramName: String = param.name?.asString() ?: "_"
      // ADR-114: the wire container is a handle to MutableList<Any?>/MutableSet<Any?>, never the
      // declared collection type, so the ABI slot is a COpaquePointer like every other handle.
      // ADR-122: so is a class/object/sealed parameter, which used to declare its real Kotlin
      // type here and cross as a pinned `kref` struct against C#'s `IntPtr` (issue #126).
      if (paramShapes[index].isLegacyLowered()) {
        addParameter(paramName, cOpaquePointer)
        return@forEachIndexed
      }
      val resolved: KSType = param.type.resolve().expandAliases()
      val type: String = resolved.declaration.qualifiedName?.asString()
        ?: resolved.declaration.simpleName.asString()
      addParameter(paramName, ClassName.bestGuess(type))
    }
  }

  // ADR-071 (2026-09-11): a `MutableStateFlow<T>`-declared function return is HELD. The call
  // happens exactly once, here, and hands its flow back as that flow's own handle; reads then go
  // through ADR-068's module-wide `nuget_stateflow_collect` / `nuget_stateflow_value` and the write
  // through a flow-handle-keyed `_set_value`. The per-member `_collect` / `_value` are not emitted:
  // they re-invoked the function, so a body that builds a fresh flow per call lost every write.
  if (method.returnsHeldMutableStateFlow()) {
    val flowElementHeld: String = flowElementType?.expandAliases()
      ?.declaration?.qualifiedName?.asString() ?: flowElementQualified
    val acquireBuilder: FunSpec.Builder = FunSpec
      .builder("export_${prefix}_$cname")
      .addAnnotation(cNameAnnotation("${prefix}_$cname"))
      .addParameter("handle", cOpaquePointer)

    acquireBuilder.addFlowParameters()

    acquireBuilder
      .returns(cOpaquePointer)
      .addCode(buildStateFlowAcquireMethodBody(qualifiedName, methodName, paramCall, paramPrelude))

    addFunction(acquireBuilder.build())

    val (heldValueParamType: TypeName, heldAssignment: String) =
      mutableStateFlowValueParameter(flowElementType)
    val heldSetValueBuilder: FunSpec.Builder = FunSpec
      .builder("export_${prefix}_${cname}_set_value")
      .addAnnotation(cNameAnnotation("${prefix}_${cname}_set_value"))
      // The owner handle and the method's own parameters are gone: the write is keyed on the flow
      // this call already handed out, which is the whole point of holding it.
      .addParameter("flowHandle", cOpaquePointer)
      .addParameter("value", heldValueParamType)
      .addParameter("errorOut", cOpaquePointer.copy(nullable = true))
      .addCode(
        buildStateFlowHandleSetValueBody(flowElementHeld, heldAssignment),
        cOpaquePointerVar,
        nugetHandles,
      )

    addFunction(heldSetValueBuilder.build())
    return
  }

  val builder: FunSpec.Builder = FunSpec
    .builder("export_${prefix}_${cname}_collect")
    .addAnnotation(cNameAnnotation("${prefix}_${cname}_collect"))
    .addParameter("handle", cOpaquePointer)
    .addParameter("scopeHandle", cOpaquePointer)

  builder.addFlowParameters()

  builder
    .addParameter("onNextPtr", cOpaquePointer)
    .addParameter("onCompletePtr", cOpaquePointer)
    .addParameter("onErrorPtr", cOpaquePointer)
    .addParameter("userData", cOpaquePointer)
    .returns(cOpaquePointer)
    .addCode(
      buildFlowMethodCollectBody(
        qualifiedName, methodName, paramCall, paramPrelude, flowElementQualified,
        elementNullable, memberNullable, flowElementCollection,
      )
    )

  addFunction(builder.build())

  if (isStateFlowMethod) {
    // ADR-065: sibling synchronous `_value` export -- handle + the method's own parameters,
    // no scope/callbacks/errorOut (StateFlow.value cannot throw).
    // ADR-067: a nullable element or nullable member widens the return to `COpaquePointer?` and
    // guards the box with `if (v != null) … else null`.
    val valueBuilder: FunSpec.Builder = FunSpec
      .builder("export_${prefix}_${cname}_value")
      .addAnnotation(cNameAnnotation("${prefix}_${cname}_value"))
      .addParameter("handle", cOpaquePointer)

    valueBuilder.addFlowParameters()

    valueBuilder
      .returns(
        if (elementNullable || memberNullable) cOpaquePointer.copy(nullable = true)
        else cOpaquePointer
      )
      .addCode(
        buildStateFlowValueMethodBody(
          qualifiedName, methodName, paramCall, paramPrelude, elementNullable, memberNullable,
          flowElementCollection,
        )
      )

    addFunction(valueBuilder.build())

    if (memberNullable) {
      // ADR-067: nullable member -- presence-probe export backing the C# `_has_value` two-call
      // pattern; the getter returns `null` when this is false, else constructs normally.
      val hasValueBuilder: FunSpec.Builder = FunSpec
        .builder("export_${prefix}_${cname}_has_value")
        .addAnnotation(cNameAnnotation("${prefix}_${cname}_has_value"))
        .addParameter("handle", cOpaquePointer)

      hasValueBuilder.addFlowParameters()

      hasValueBuilder
        .returns(Boolean::class)
        .addCode(
          buildStateFlowHasValueMethodBody(qualifiedName, methodName, paramCall, paramPrelude)
        )

      addFunction(hasValueBuilder.build())
    }

    // ADR-071 (2026-09-11): the settable half of a function return no longer lands here. A
    // `MutableStateFlow<T>`-declared return is held by handle and returned above, before the
    // `_collect` export is even built; what reaches this point is a read-only `StateFlow<T>`
    // return, which mints nothing per call and keeps its shipped per-member exports.
  }
}

// ADR-067: `?` on the member access when the whole StateFlow member/return can be null (a
// defensive guard -- the C# side only reaches this after its `_has_value` probe is true, but a
// race should not crash the coroutine); plain `.` (unchanged ADR-065 shape) otherwise.
private fun memberAccessor(receiver: String, memberNullable: Boolean): String =
  if (memberNullable) "$receiver?" else receiver

// ADR-067: the collected/read item expression -- a null-guarded box when the element itself is
// nullable (`StateFlow<T?>`), else the original unguarded `value as Any` box (ADR-065 unchanged).
// ADR-123: a collection element is boxed per-element projected, so a value class leaves as its
// underlying and an enum as its ordinal, exactly as the ordinary route's collection result does.
// The two are exclusive: a nullable collection element is refused before either half sees it.
private fun itemBoxExpr(
  elementNullable: Boolean,
  collection: BridgeType.Collection?,
): String = when {
  elementNullable -> "if (value != null) NugetHandles.retain(value) else null"
  collection != null -> "NugetHandles.retain(${flowValueExpression("value", collection)} as Any)"
  else -> "NugetHandles.retain(value as Any)"
}

/**
 * ADR-123: one flow emission (or one `.Value` read) projected to what the C# per-element read
 * expects. `collectionResultProjection` returns [invocation] unchanged when no component needs
 * projecting, so a `List<String>` element's emitted Kotlin is byte-identical to the shipped one.
 */
private fun flowValueExpression(invocation: String, collection: BridgeType.Collection?): String =
  if (collection == null) invocation else collectionResultProjection(invocation, collection)

private fun buildFlowCollectBody(
  qualifiedName: String,
  propName: String,
  flowElementQualified: String,
  elementNullable: Boolean = false,
  memberNullable: Boolean = false,
  elementCollection: BridgeType.Collection?,
): String = buildString {
  appendLine("val obj = handle.asStableRef<$qualifiedName>().get()")
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
  appendLine("    obj.${memberAccessor(propName, memberNullable)}.collect { value ->")
  appendLine("      val itemRef = ${itemBoxExpr(elementNullable, elementCollection)}")
  appendLine("      onNext.invoke(itemRef, 0.toByte(), userData)")
  appendLine("    }")
  appendLine("    onComplete.invoke(userData)")
  appendLine("  } catch (e: CancellationException) {")
  appendLine("    onNext.invoke(null, 1.toByte(), userData)")
  appendLine("    throw e")
  appendLine("  } catch (e: Throwable) {")
  appendLine("    val errRef = NugetHandles.retain(buildError(e))")
  appendLine("    onError.invoke(errRef, userData)")
  appendLine("  }")
  appendLine("}")
  append("return NugetHandles.retain(job)")
}

private fun buildFlowMethodCollectBody(
  qualifiedName: String,
  methodName: String,
  paramCall: String,
  // ADR-114: the eager collection copy, emitted before `launch` so the C# side's finally-dispose
  // of the wire handle can never race the coroutine reading it.
  paramPrelude: String,
  flowElementQualified: String,
  elementNullable: Boolean = false,
  memberNullable: Boolean = false,
  elementCollection: BridgeType.Collection?,
): String = buildString {
  appendLine("val obj = handle.asStableRef<$qualifiedName>().get()")
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
  append(paramPrelude)
  appendLine("val job = scope.launch(start = CoroutineStart.ATOMIC) {")
  appendLine("  try {")
  appendLine(
    "    obj.${memberAccessor("$methodName($paramCall)", memberNullable)}.collect { value ->",
  )
  appendLine("      val itemRef = ${itemBoxExpr(elementNullable, elementCollection)}")
  appendLine("      onNext.invoke(itemRef, 0.toByte(), userData)")
  appendLine("    }")
  appendLine("    onComplete.invoke(userData)")
  appendLine("  } catch (e: CancellationException) {")
  appendLine("    onNext.invoke(null, 1.toByte(), userData)")
  appendLine("    throw e")
  appendLine("  } catch (e: Throwable) {")
  appendLine("    val errRef = NugetHandles.retain(buildError(e))")
  appendLine("    onError.invoke(errRef, userData)")
  appendLine("  }")
  appendLine("}")
  append("return NugetHandles.retain(job)")
}

// ADR-065: the `_value` export body -- boxes `stateFlow.value as Any` into a StableRef, byte-for-
// byte the same shape as a single onNext emission above. No errorOut: StateFlow.value cannot throw.
// ADR-067: a nullable element or nullable member instead reads the value into a local and only
// boxes it when non-null, returning `null` (a widened `COpaquePointer?`) otherwise.
private fun buildStateFlowValuePropertyBody(
  qualifiedName: String,
  propName: String,
  elementNullable: Boolean = false,
  memberNullable: Boolean = false,
  elementCollection: BridgeType.Collection?,
): String = buildString {
  appendLine("val obj = handle.asStableRef<$qualifiedName>().get()")
  if (!elementNullable && !memberNullable) {
    val read: String = flowValueExpression("obj.$propName.value", elementCollection)
    append("return NugetHandles.retain($read as Any)")
  } else {
    appendLine("val v = obj.${memberAccessor(propName, memberNullable)}.value")
    append("return if (v != null) NugetHandles.retain(v) else null")
  }
}

private fun buildStateFlowValueMethodBody(
  qualifiedName: String,
  methodName: String,
  paramCall: String,
  paramPrelude: String,
  elementNullable: Boolean = false,
  memberNullable: Boolean = false,
  elementCollection: BridgeType.Collection?,
): String = buildString {
  appendLine("val obj = handle.asStableRef<$qualifiedName>().get()")
  append(paramPrelude)
  if (!elementNullable && !memberNullable) {
    val read: String =
      flowValueExpression("obj.$methodName($paramCall).value", elementCollection)
    append("return NugetHandles.retain($read as Any)")
  } else {
    appendLine("val v = obj.${memberAccessor("$methodName($paramCall)", memberNullable)}.value")
    append("return if (v != null) NugetHandles.retain(v) else null")
  }
}

// ADR-067: nullable-member presence probe -- backs the C# `_has_value` two-call pattern. A pure,
// idempotent read of a `val`/getter-backed StateFlow reference; safe to call before subscribing.
private fun buildStateFlowHasValuePropertyBody(
  qualifiedName: String,
  propName: String,
): String = buildString {
  appendLine("val obj = handle.asStableRef<$qualifiedName>().get()")
  append("return obj.$propName != null")
}

private fun buildStateFlowHasValueMethodBody(
  qualifiedName: String,
  methodName: String,
  paramCall: String,
  paramPrelude: String,
): String = buildString {
  appendLine("val obj = handle.asStableRef<$qualifiedName>().get()")
  append(paramPrelude)
  append("return obj.$methodName($paramCall) != null")
}

/**
 * ADR-071: classifies a (already-[isMutableStateFlowElementSupported]) MutableStateFlow<T>
 * element for the settable `.Value` write seam -- the exported setter's Kotlin parameter type and
 * the assignment expression that unwraps it. Primitive/`Char`/`String` cross by value (no
 * conversion, or the one conversion `String` already needs); an ordinary class/object element
 * crosses as a `COpaquePointer` and is unwrapped via `asStableRef`, byte-for-byte the same shape
 * as `ForwardPropertyKotlinEmitter.valueExpression`'s `ObjectHandle` branch.
 */
private fun mutableStateFlowValueParameter(elementType: KSType?): Pair<TypeName, String> {
  val declaration = elementType?.expandAliases()?.declaration
  val simpleName: String = declaration?.simpleName?.asString() ?: "Any"
  return if (isMutableStateFlowElementObject(elementType)) {
    val qualifiedElementName: String = (declaration as KSClassDeclaration)
      .qualifiedName?.asString() ?: simpleName
    cOpaquePointer to "value.asStableRef<$qualifiedElementName>().get()"
  } else {
    ClassName("kotlin", simpleName) to "value"
  }
}

// ADR-071: the `_set_value` export body -- writes `value` (already unwrapped by [assignment]) into
// the underlying MutableStateFlow's `.value`. MutableStateFlow.value's setter conflates by
// `Any.equals` on the PREVIOUS value (kotlinx.coroutines StateFlow.kt), so a throwing `equals`
// (or a throwing object `equals`/handle dereference) propagates out and is wrapped via `errorOut`,
// the same ADR-030 shape every ordinary `var` property setter already carries.
private fun buildStateFlowSetValuePropertyBody(
  qualifiedName: String,
  propName: String,
  assignment: String,
): String = buildString {
  appendLine("try {")
  appendLine("  handle.asStableRef<$qualifiedName>().get().$propName.value = $assignment")
  appendLine("} catch (e: Throwable) {")
  appendLine("  if (errorOut != null) {")
  appendLine("    errorOut.reinterpret<%T>().pointed.value = %T.retain(")
  appendLine("      buildError(e)")
  appendLine("    )")
  appendLine("  }")
  append("}")
}

/**
 * ADR-071 (2026-09-11): the held route's acquire body. The function is invoked exactly once per
 * C# call and its flow is handed back as that flow's own handle, minted through the generated
 * `NugetHandles` table (never a bare `StableRef.create`, which would leave ADR-120's live-handle
 * accounting blind to it). The C# wrapper owns that handle and frees it in `Dispose()`.
 */
private fun buildStateFlowAcquireMethodBody(
  qualifiedName: String,
  methodName: String,
  paramCall: String,
  paramPrelude: String,
): String = buildString {
  appendLine("val obj = handle.asStableRef<$qualifiedName>().get()")
  append(paramPrelude)
  append("return NugetHandles.retain(obj.$methodName($paramCall) as Any)")
}

/**
 * ADR-071 (2026-09-11): the held route's write body. Keyed on the flow handle the acquire export
 * handed out, so the write lands in the flow the caller holds however the function body behaves;
 * the shipped shape re-invoked the function here and lost the write when the body built a fresh
 * flow. `MutableStateFlow.value`'s setter conflates by `Any.equals` on the PREVIOUS value
 * (kotlinx.coroutines StateFlow.kt), so a throwing `equals` still propagates through `errorOut`,
 * the same ADR-030 shape the property half carries.
 */
private fun buildStateFlowHandleSetValueBody(
  flowElementQualified: String,
  assignment: String,
): String = buildString {
  appendLine("try {")
  appendLine(
    "  flowHandle.asStableRef<kotlinx.coroutines.flow.MutableStateFlow<" +
        "$flowElementQualified>>().get().value = $assignment"
  )
  appendLine("} catch (e: Throwable) {")
  appendLine("  if (errorOut != null) {")
  appendLine("    errorOut.reinterpret<%T>().pointed.value = %T.retain(")
  appendLine("      buildError(e)")
  appendLine("    )")
  appendLine("  }")
  append("}")
}
