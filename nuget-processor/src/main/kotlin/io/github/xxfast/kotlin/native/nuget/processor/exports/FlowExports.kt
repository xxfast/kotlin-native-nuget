package io.github.xxfast.kotlin.native.nuget.processor.exports

import io.github.xxfast.kotlin.native.nuget.processor.forward.legacyFlowElementEnvelope
import io.github.xxfast.kotlin.native.nuget.processor.forward.kotlinIdentifier
import io.github.xxfast.kotlin.native.nuget.processor.asCSymbol
import io.github.xxfast.kotlin.native.nuget.processor.freshName
import io.github.xxfast.kotlin.native.nuget.processor.cir.SharedFlowSurface
import io.github.xxfast.kotlin.native.nuget.processor.cir.sharedFlowSurface
import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Visibility
import com.squareup.kotlinpoet.BOOLEAN
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.TypeName
import io.github.xxfast.kotlin.native.nuget.processor.cir.MUTABLE_STATE_FLOW_TYPES
import io.github.xxfast.kotlin.native.nuget.processor.cir.FLOW_TYPES
import io.github.xxfast.kotlin.native.nuget.processor.cir.STATE_FLOW_TYPES
import io.github.xxfast.kotlin.native.nuget.processor.cir.expandAliases
import io.github.xxfast.kotlin.native.nuget.processor.cir.MutableStateFlowElement
import io.github.xxfast.kotlin.native.nuget.processor.cir.writableMutableStateFlowElement
import io.github.xxfast.kotlin.native.nuget.processor.cir.isMutableStateFlowElementWritable
import io.github.xxfast.kotlin.native.nuget.processor.forward.BridgeType
import io.github.xxfast.kotlin.native.nuget.processor.forward.forwardKotlinArgumentSpelling
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardBridgeTypeClassifier
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardCallablePlanCatalog
import io.github.xxfast.kotlin.native.nuget.processor.forward.forwardArmMemberProjectedByBase
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardLegacyParameterShape
import io.github.xxfast.kotlin.native.nuget.processor.forward.collectionResultProjection
import io.github.xxfast.kotlin.native.nuget.processor.forward.isForwardArmMember
import io.github.xxfast.kotlin.native.nuget.processor.forward.isLegacyLowered
import io.github.xxfast.kotlin.native.nuget.processor.forward.isLegacyNullableSlot
import io.github.xxfast.kotlin.native.nuget.processor.forward.isOptInRefused
import io.github.xxfast.kotlin.native.nuget.processor.forward.legacyFlowElementCollection
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardLegacyNames
import io.github.xxfast.kotlin.native.nuget.processor.forward.legacyInvocation
import io.github.xxfast.kotlin.native.nuget.processor.forward.legacyKotlinNames
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
 * ADR-111, the arm's own surface for its methods per ADR-116), and only the per-member emission
 * lives here.
 *
 * @see <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/065-stateflow-mapping.md">ADR-065: StateFlow mapping</a>
 * @see <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/124-flow-route-sealed-arm-owners.md">ADR-124: Flow route on sealed-arm owners</a>
 */

/** The Flow/StateFlow family this route owns, on an already alias-expanded type or not. */
internal fun KSType.isForwardFlowType(): Boolean {
  val qualified: String? = expandAliases().declaration.qualifiedName?.asString()
  return qualified in FLOW_TYPES || qualified in STATE_FLOW_TYPES
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
  .filter { prop -> !prop.isOptInRefused(classifier.exportMarkers) }
  // ADR-123: an element this route cannot marshal drops the property on both halves;
  // `warnRefusedLegacyRouteMembers` names it once.
  .filter { prop -> classifier.legacyRefusedFlowElement(prop.type.resolve()) == null }
  // ADR-175: a flow property the sealed base projects is the base's (ADR-159 rule 4).
  .filter { prop -> !forwardArmMemberProjectedByBase(prop, classifier) }
  .toList()

/**
 * ADR-124: a sealed arm's flow-returning methods, on the arm's own surface ([isForwardArmMember]),
 * which is ADR-116's rule for the arm's method surface and ADR-118's for its suspend members:
 * declared, plus inherited from an interface the sealed type does not carry (ADR-101 amendment
 * 2026-09-27). A base `open fun` returning a Flow that no arm overrides still belongs to no arm.
 */
internal fun KSClassDeclaration.forwardArmFlowMethods(
  classifier: ForwardBridgeTypeClassifier,
): List<KSFunctionDeclaration> = getAllFunctions()
  .filter { it.getVisibility() == Visibility.PUBLIC }
  .filter { isForwardArmMember(it) }
  .filter { !it.modifiers.contains(Modifier.SUSPEND) }
  .filter { it.returnsForwardFlow() }
  // Issue #230: the synthetic-member filter every other member route applies. A `Flow`-typed
  // data class parameter's `componentN` is a duplicate of the property, on both halves.
  .filter { method -> !method.isCompilerOwnedMember(this) }
  // ADR-114 / ADR-119 / ADR-123: the three refusals the ordinary route applies upstream of its own
  // projection. Both halves must agree, or a C# import arrives with no Kotlin export behind it.
  .filter { method -> classifier.legacyRefusedParameter(method.parameters) == null }
  .filter { method -> classifier.legacyRefusedReturn(method) == null }
  // ADR-175: an override of a Flow member the sealed base projects stays on the base.
  .filter { method -> !forwardArmMemberProjectedByBase(method, classifier) }
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
  // A nullable member stays on the read-only `_has_value` route: the held acquire has no null arm.
  if (resolved.isMarkedNullable) return false
  // ADR-071 amendment: a nullable element is held and settable (bar `Boolean?`/`Char?`).
  val element: KSType? = resolved.arguments.firstOrNull()?.type?.resolve()?.expandAliases()
  return isMutableStateFlowElementWritable(element)
}

/**
 * ADR-071 held-route amendment (cross-noted on ADR-068): whether a class `suspend fun`'s awaited
 * `MutableStateFlow<T>` is settable. Its `_async` export already hands the flow back by handle, so
 * the write is the held route's flow-keyed `_set_value` sibling. One predicate for the Kotlin
 * export and the C# translator. A nullable member or element stays read-only: the write side of
 * `suspend fun (): MutableStateFlow<T?>` is deferred.
 */
internal fun KSFunctionDeclaration.awaitsSettableMutableStateFlow(): Boolean {
  if (!modifiers.contains(Modifier.SUSPEND)) return false
  val resolved: KSType = returnType?.resolve()?.expandAliases() ?: return false
  if (resolved.declaration.qualifiedName?.asString() !in MUTABLE_STATE_FLOW_TYPES) return false
  if (resolved.isMarkedNullable) return false
  val element: KSType? = resolved.arguments.firstOrNull()?.type?.resolve()?.expandAliases()
  if (element?.isMarkedNullable == true) return false
  return isMutableStateFlowElementWritable(element)
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
  val propName: String = prop.simpleName.asString().asCSymbol()
  // The Kotlin spelling of the same name, backticked where it needs it (`in`, `tug hard`).
  val propCall: String = prop.simpleName.asString().kotlinIdentifier()
  val propTypeResolved: KSType = prop.type.resolve().expandAliases()
  val propType: String = propTypeResolved.declaration.qualifiedName?.asString() ?: "Any"
  // ADR-065: StateFlow (and the read-only MutableStateFlow view) is checked before/alongside
  // plain Flow. The `_collect` export is byte-for-byte the same shape for both (StateFlow's
  // `collect` is inherited from Flow); StateFlow additionally gets a synchronous `_value` export.
  val isStateFlowProperty: Boolean = propType in STATE_FLOW_TYPES
  val isFlowProperty: Boolean = propType in FLOW_TYPES
  require(isFlowProperty || isStateFlowProperty) {
    "addFlowPropertyExports is the Flow/StateFlow route; got $propType"
  }

  // ADR-123: an element this route cannot marshal drops the property, matching the C# half.
  // `NugetProcessor` names it once as a SKIPPED_UNSUPPORTED_PROPERTY.
  if (classifier.legacyRefusedFlowElement(propTypeResolved) != null) return

  val flowElementType: KSType? =
    propTypeResolved.arguments.firstOrNull()?.type?.resolve()?.expandAliases()
  val flowElementQualified: String =
    flowElementType?.declaration?.qualifiedName?.asString() ?: "kotlin.Any"
  // ADR-067, widened 2026-09-20: a nullable ELEMENT is threaded on both flow shapes, the C# half's
  // matching change. A plain `Flow<T?>` used to box `value as Any` here, which throws on the first
  // null emission and reaches the consumer as `onError` -- a stream that dies instead of yielding
  // null. Null now crosses as a null item pointer, the encoding `StateFlow<T?>` already used.
  // A nullable MEMBER (`StateFlow<T>?` and, since 2026-10-09, `Flow<T>?`) is the `_has_value`
  // probe pair.
  val elementNullable: Boolean = flowElementType?.isMarkedNullable == true
  val memberNullable: Boolean = propTypeResolved.isMarkedNullable
  // ADR-123: a collection element crosses as the ordinary route's boxed wire container, so a
  // component that projects at the seam (a value class to its underlying, an enum to its
  // ordinal) has to leave as that wire value or the C# per-element read decodes the wrong box.
  val flowElementCollection: BridgeType.Collection? =
    classifier.legacyFlowElementCollection(propTypeResolved)
  // ADR-201 amendment: a Throwable element leaves as its ADR-107 envelope.
  val flowElementEnvelope: Boolean = classifier.legacyFlowElementEnvelope(propTypeResolved)

  addFunction(
    FunSpec.builder("export_${prefix}_get_${propName}_collect")
      .addAnnotation(cNameAnnotation("${prefix}_get_${propName}_collect", ownedBy(prop)))
      .addParameter("handle", cOpaquePointer)
      .addParameter("scopeHandle", cOpaquePointer)
      .addParameter("onNextPtr", cOpaquePointer)
      .addParameter("onCompletePtr", cOpaquePointer)
      .addParameter("onErrorPtr", cOpaquePointer)
      .addParameter("userData", cOpaquePointer)
      .returns(cOpaquePointer)
      .addCode(
        buildFlowCollectBody(
          qualifiedName, propCall, flowElementQualified, elementNullable, memberNullable,
          flowElementCollection, flowElementEnvelope,
        )
      )
      .build()
  )

  // ADR-209: a SharedFlow's `ReplayCache`, and a writable MutableSharedFlow's write surface.
  addSharedFlowPropertyExports(
    prop, qualifiedName, prefix, propName, propCall, propTypeResolved, classifier,
  )

  if (isStateFlowProperty) {
    // ADR-065: synchronous `_value` export -- boxes `stateFlow.value as Any` into a StableRef,
    // structurally identical to a single onNext emission. No errorOut: StateFlow.value cannot
    // throw (a deliberate narrowing of ADR-030's wrap-all-property-getters policy).
    // ADR-067: a nullable element or nullable member widens the return to `COpaquePointer?` and
    // guards the box with `if (v != null) … else null`.
    addFunction(
      FunSpec.builder("export_${prefix}_get_${propName}_value")
        .addAnnotation(cNameAnnotation("${prefix}_get_${propName}_value", ownedBy(prop)))
        .addParameter("handle", cOpaquePointer)
        .returns(
          if (elementNullable || memberNullable) cOpaquePointer.copy(nullable = true)
          else cOpaquePointer
        )
        .addCode(
          buildStateFlowValuePropertyBody(
            qualifiedName, propCall, elementNullable, memberNullable, flowElementCollection,
            flowElementEnvelope,
          ),
        )
        .build()
    )

    // ADR-071: a genuinely DECLARED MutableStateFlow<T> (not narrowed through .asStateFlow())
    // additionally gains a settable `.Value`, gated on a writable element (primitive/String/
    // object, a non-null enum as its ordinal; a nullable one bar `Boolean?`/`Char?`/enum). A
    // nullable member is settable too: the write throws when it finds the member absent.
    val isMutableStateFlowProperty: Boolean = propType in MUTABLE_STATE_FLOW_TYPES &&
        isMutableStateFlowElementWritable(flowElementType)
    if (isMutableStateFlowProperty) {
      val slot: MutableStateFlowWriteSlot = mutableStateFlowWriteSlot(flowElementType)
      addFunction(
        FunSpec.builder("export_${prefix}_set_${propName}_value")
          .addAnnotation(cNameAnnotation("${prefix}_set_${propName}_value", ownedBy(prop)))
          .addParameter("handle", cOpaquePointer)
          .addMutableStateFlowWriteSlot(slot)
          .addParameter("errorOut", cOpaquePointer.copy(nullable = true))
          .addCode(
            buildStateFlowSetValuePropertyBody(
              qualifiedName, propCall, slot.assignment, memberNullable,
            ),
            cOpaquePointerVar, nugetHandles,
          )
          .build()
      )
      addStateFlowCompareAndSetPropertyExport(
        prop, qualifiedName, prefix, propName, propCall, flowElementType, memberNullable,
      )
    }
  }

  if (memberNullable) {
    // ADR-067: nullable member -- presence-probe export backing the C# `_has_value` two-call
    // pattern; the getter returns `null` when this is false, else constructs normally. Since
    // 2026-10-09 (ADR-026 amendment) a plain `Flow<T>?` member takes the same probe.
    addFunction(
      FunSpec.builder("export_${prefix}_get_${propName}_has_value")
        .addAnnotation(cNameAnnotation("${prefix}_get_${propName}_has_value", ownedBy(prop)))
        .addParameter("handle", cOpaquePointer)
        .returns(Boolean::class)
        .addCode(buildFlowHasValuePropertyBody(qualifiedName, propCall))
        .build()
    )
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
  val flowElementType: KSType? =
    returnType?.arguments?.firstOrNull()?.type?.resolve()?.expandAliases()
  val flowElementQualified: String =
    flowElementType?.declaration?.qualifiedName?.asString() ?: "kotlin.Any"
  // ADR-067 (widened 2026-09-20): nullable ELEMENT threading on both flow shapes, mirroring the
  // property half above, and (2026-10-09) the nullable MEMBER on both shapes too.
  val elementNullable: Boolean = flowElementType?.isMarkedNullable == true
  val memberNullable: Boolean = returnType?.isMarkedNullable == true
  // ADR-123: the element-side twin of the parameter lowering below -- a collection element
  // leaves per-element projected, exactly as the ordinary route's collection result does.
  val flowElementCollection: BridgeType.Collection? =
    classifier.legacyFlowElementCollection(returnType)
  // ADR-201 amendment: a Throwable element leaves as its ADR-107 envelope.
  val flowElementEnvelope: Boolean = classifier.legacyFlowElementEnvelope(returnType)

  // ADR-114: a collection parameter is dereferenced and copied out of its wire container
  // eagerly, before `launch`, and the member is called with that local instead of the raw
  // handle. Every other parameter keeps its shipped spelling.
  // ADR-164: defaulted parameters widen and dispatch through `when (mask)`, as on the suspend
  // route.
  val paramShapes: List<ForwardLegacyParameterShape> = classifier.legacyParameterShapes(
    method.parameters, callableCatalog.legacyDefaultFlags(method),
  )
  val names: ForwardLegacyNames = legacyKotlinNames(method.parameters, paramShapes)

  // The whole call on `names.obj`, positional or dispatched; every body below reads it as is.
  val call: String =
    names.legacyInvocation(
      "${names.obj}.${methodName.kotlinIdentifier()}", method.legacyParameterNames(),
    )
  val paramPrelude: String = names.legacyPrelude(method.legacyParameterNames())

  fun FunSpec.Builder.addFlowParameters() {
    method.parameters.forEachIndexed { index, param ->
      val paramName: String = param.name?.asString() ?: "_"
      // ADR-114: the wire container is a handle to MutableList<Any?>/MutableSet<Any?>, never the
      // declared collection type, so the ABI slot is a COpaquePointer like every other handle.
      // ADR-122: so is a class/object/sealed parameter, which used to declare its real Kotlin
      // type here and cross as a pinned `kref` struct against C#'s `IntPtr` (issue #126).
      if (paramShapes[index].isLegacyLowered()) {
        val nullable: Boolean = paramShapes[index].isLegacyNullableSlot
        // ADR-164 rule 2: an optional handle's leading `IsSet` slot.
        names.isSetSlots[index]?.let { isSet -> addParameter(isSet, BOOLEAN) }
        addParameter(paramName, cOpaquePointer.copy(nullable = nullable))
        return@forEachIndexed
      }
      val resolved: KSType = param.type.resolve().expandAliases()
      val type: String = resolved.declaration.qualifiedName?.asString()
        ?: resolved.declaration.simpleName.asString()
      addLegacyScalarParameter(
        paramName, paramShapes[index], ClassName.bestGuess(type), names.hasValueSlots[index],
        names.isSetSlots[index],
      )
    }
  }

  // ADR-209: a writable `MutableSharedFlow<T>` return is HELD for ADR-071's reason: one acquire,
  // then the acquired flow's own `_collect` and the flow-keyed shared seams.
  if (method.returnsHeldMutableSharedFlow()) {
    val acquireBuilder: FunSpec.Builder = FunSpec
      .builder("export_${prefix}_$cname")
      .addAnnotation(cNameAnnotation("${prefix}_$cname", ownedBy(method)))
      .addParameter("handle", cOpaquePointer)
    acquireBuilder.addFlowParameters()
    acquireBuilder
      .returns(cOpaquePointer.copy(nullable = memberNullable))
      .addCode(
        buildSharedFlowAcquireMethodBody(
          qualifiedName, call, paramPrelude, names.obj, memberNullable,
        ),
      )
    addFunction(acquireBuilder.build())
    addAcquiredFlowCollectExport(method, "${prefix}_$cname", returnType, classifier)
    addFlowKeyedSharedFlowExports("${prefix}_$cname", method, returnType, classifier)
    return
  }

  // ADR-071 (2026-09-11): a `MutableStateFlow<T>`-declared function return is HELD. The call
  // happens exactly once, here, and hands its flow back as that flow's own handle; reads then go
  // through ADR-068's module-wide `nuget_stateflow_collect` / `nuget_stateflow_value` and the write
  // through a flow-handle-keyed `_set_value`. The per-member `_collect` / `_value` are not emitted:
  // they re-invoked the function, so a body that builds a fresh flow per call lost every write.
  if (method.returnsHeldMutableStateFlow()) {
    val acquireBuilder: FunSpec.Builder = FunSpec
      .builder("export_${prefix}_$cname")
      .addAnnotation(cNameAnnotation("${prefix}_$cname", ownedBy(method)))
      .addParameter("handle", cOpaquePointer)

    acquireBuilder.addFlowParameters()

    acquireBuilder
      .returns(cOpaquePointer)
      .addCode(
        buildStateFlowAcquireMethodBody(qualifiedName, call, paramPrelude, names.obj)
      )

    addFunction(acquireBuilder.build())
    addHeldStateFlowSetValueExport("${prefix}_${cname}", method, flowElementType)
    addHeldStateFlowCompareAndSetExport("${prefix}_${cname}", method, flowElementType)
    return
  }

  val builder: FunSpec.Builder = FunSpec
    .builder("export_${prefix}_${cname}_collect")
    .addAnnotation(cNameAnnotation("${prefix}_${cname}_collect", ownedBy(method)))
    .addParameter("handle", cOpaquePointer)
    .addParameter(names.scopeHandle, cOpaquePointer)

  builder.addFlowParameters()

  builder
    .addParameter(names.onNext, cOpaquePointer)
    .addParameter(names.onComplete, cOpaquePointer)
    .addParameter(names.onError, cOpaquePointer)
    .addParameter(names.userData, cOpaquePointer)
    .returns(cOpaquePointer)
    .addCode(
      buildFlowMethodCollectBody(
        qualifiedName, call, paramPrelude, flowElementQualified,
        elementNullable, memberNullable, flowElementCollection, names, flowElementEnvelope,
      )
    )

  addFunction(builder.build())

  // ADR-209: a re-invoked SharedFlow return's `ReplayCache`, keyed like the collect: the owner and
  // the method's own arguments, the method re-run per read.
  if (sharedFlowSurface(returnType) != SharedFlowSurface.NONE) {
    // The error slot moves off a user parameter spelled `errorOut`, as the fixed slots do.
    val errorOut: String = freshName("errorOut", method.legacyParameterNames().toMutableSet())
    val replayBuilder: FunSpec.Builder = FunSpec
      .builder("export_${prefix}_${cname}_replay_cache")
      .addAnnotation(cNameAnnotation("${prefix}_${cname}_replay_cache", ownedBy(method)))
      .addParameter("handle", cOpaquePointer)
    replayBuilder.addFlowParameters()
    val flow: String = if (memberNullable) {
      "($call ?: throw IllegalStateException(\"${methodName} returned null\"))"
    } else {
      call
    }
    replayBuilder
      .addParameter(errorOut, cOpaquePointer.copy(nullable = true))
      .returns(cOpaquePointer.copy(nullable = true))
      .addCode("val ${names.obj} = handle.asStableRef<$qualifiedName>().get()\n")
      .addCode(paramPrelude)
      .addGuardedReturn(
        classifier.replayCacheExpression(flow, requireNotNull(returnType)), "null",
        errorOut = errorOut,
      )
    addFunction(replayBuilder.build())
  }

  if (isStateFlowMethod) {
    // ADR-065: sibling synchronous `_value` export -- handle + the method's own parameters,
    // no scope/callbacks/errorOut (StateFlow.value cannot throw).
    // ADR-067: a nullable element or nullable member widens the return to `COpaquePointer?` and
    // guards the box with `if (v != null) … else null`.
    val valueBuilder: FunSpec.Builder = FunSpec
      .builder("export_${prefix}_${cname}_value")
      .addAnnotation(cNameAnnotation("${prefix}_${cname}_value", ownedBy(method)))
      .addParameter("handle", cOpaquePointer)

    valueBuilder.addFlowParameters()

    valueBuilder
      .returns(
        if (elementNullable || memberNullable) cOpaquePointer.copy(nullable = true)
        else cOpaquePointer
      )
      .addCode(
        buildStateFlowValueMethodBody(
          qualifiedName, call, paramPrelude, elementNullable, memberNullable,
          flowElementCollection, names.obj, flowElementEnvelope,
        )
      )

    addFunction(valueBuilder.build())

    // ADR-071 (2026-09-11): the settable half of a function return no longer lands here. A
    // `MutableStateFlow<T>`-declared return is held by handle and returned above, before the
    // `_collect` export is even built; what reaches this point is a read-only `StateFlow<T>`
    // return, which mints nothing per call and keeps its shipped per-member exports.
  }

  if (memberNullable) {
    // ADR-067: nullable member -- presence-probe export backing the C# `_has_value` two-call
    // pattern; the getter returns `null` when this is false, else constructs normally. Since
    // 2026-10-09 (ADR-026 amendment) a plain `Flow<T>?` return takes the same probe.
    val hasValueBuilder: FunSpec.Builder = FunSpec
      .builder("export_${prefix}_${cname}_has_value")
      .addAnnotation(cNameAnnotation("${prefix}_${cname}_has_value", ownedBy(method)))
      .addParameter("handle", cOpaquePointer)

    hasValueBuilder.addFlowParameters()

    hasValueBuilder
      .returns(Boolean::class)
      .addCode(
        buildFlowHasValueMethodBody(qualifiedName, call, paramPrelude, names.obj)
      )

    addFunction(hasValueBuilder.build())
  }
}

// ADR-067: `?` on the member access when the whole Flow or StateFlow member/return can be null (a
// defensive guard -- the C# side only reaches this after its `_has_value` probe is true, but a
// race should not crash the coroutine); plain `.` (unchanged ADR-065 shape) otherwise.
private fun memberAccessor(receiver: String, memberNullable: Boolean): String =
  if (memberNullable) "$receiver?" else receiver

// ADR-067: the collected/read item expression -- a null-guarded box when the element itself is
// nullable (`StateFlow<T?>` and, since 2026-09-20, `Flow<T?>`), else the original unguarded
// `value as Any` box (ADR-065 unchanged).
// ADR-123: a collection element is boxed per-element projected, so a value class leaves as its
// underlying and an enum as its ordinal, exactly as the ordinary route's collection result does.
// The two are exclusive: a nullable collection element is refused before either half sees it.
internal fun itemBoxExpr(
  elementNullable: Boolean,
  collection: BridgeType.Collection?,
  // ADR-201 amendment: a Throwable element leaves as its ADR-107 envelope.
  envelope: Boolean = false,
): String = when {
  envelope && elementNullable ->
    "if (value != null) NugetHandles.retain(buildError(value, ::nugetMappedType)) else null"
  envelope -> "NugetHandles.retain(buildError(value, ::nugetMappedType))"
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
  envelope: Boolean = false,
): String = buildString {
  appendLine("val obj = handle.asStableRef<$qualifiedName>().get()")
  // ADR-128: the runtime's `collectForCSharp` owns the three reinterpreted callbacks, the ATOMIC
  // launch and the complete/cancel/error arms. This route keeps the Flow source and the per-item
  // mint -- and the source is read *inside* the body lambda, so a property getter that throws
  // still reaches C# as `onError` instead of escaping the `@CName` export.
  appendLine("val scope = scopeHandle.asStableRef<CoroutineScope>().get()")
  appendLine(
    "return collectForCSharp(scope, onNextPtr, onCompletePtr, onErrorPtr, userData, ::nugetMappedType) { emit ->"
  )
  appendLine("  obj.${memberAccessor(propName, memberNullable)}.collect { value ->")
  appendLine("    val itemRef = ${itemBoxExpr(elementNullable, elementCollection, envelope)}")
  appendLine("    emit(itemRef)")
  appendLine("  }")
  append("}")
}

private fun buildFlowMethodCollectBody(
  qualifiedName: String,
  // The member call on the receiver local (`legacyInvocation`), positional or dispatched.
  call: String,
  // ADR-114: the eager collection copy, emitted before `launch` so the C# side's finally-dispose
  // of the wire handle can never race the coroutine reading it.
  paramPrelude: String,
  flowElementQualified: String,
  elementNullable: Boolean = false,
  memberNullable: Boolean = false,
  elementCollection: BridgeType.Collection?,
  // Every fixed slot and local below is minted apart from the member's own parameter names.
  names: ForwardLegacyNames,
  envelope: Boolean = false,
): String = buildString {
  val obj: String = names.obj
  val scope: String = names.scope
  val emit: String = names.emit
  appendLine("val $obj = handle.asStableRef<$qualifiedName>().get()")
  // ADR-128: as the property route above, with ADR-114's eager parameter copy still emitted
  // *before* the helper call. The member call itself stays inside the body lambda: a Flow-returning
  // method that throws on the way to its Flow reaches C# as `onError`, which is the shipped
  // behaviour and the reason the helper takes a body rather than a `Flow` argument.
  appendLine("val $scope = ${names.scopeHandle}.asStableRef<CoroutineScope>().get()")
  append(paramPrelude)
  appendLine(
    "return collectForCSharp($scope, ${names.onNext}, ${names.onComplete}, ${names.onError}, " +
        "${names.userData}, ::nugetMappedType) { $emit ->"
  )
  appendLine("  ${memberAccessor(call, memberNullable)}.collect { value ->")
  appendLine("    val itemRef = ${itemBoxExpr(elementNullable, elementCollection, envelope)}")
  appendLine("    $emit(itemRef)")
  appendLine("  }")
  append("}")
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
  envelope: Boolean = false,
): String = buildString {
  appendLine("val obj = handle.asStableRef<$qualifiedName>().get()")
  if (!elementNullable && !memberNullable) {
    val read: String = flowValueExpression("obj.$propName.value", elementCollection)
    append("return NugetHandles.retain(${stateFlowBox(read, envelope)})")
  } else {
    appendLine("val v = obj.${memberAccessor(propName, memberNullable)}.value")
    append("return if (v != null) NugetHandles.retain(${stateFlowNullableBox(envelope)}) else null")
  }
}

private fun buildStateFlowValueMethodBody(
  qualifiedName: String,
  call: String,
  paramPrelude: String,
  elementNullable: Boolean = false,
  memberNullable: Boolean = false,
  elementCollection: BridgeType.Collection?,
  obj: String,
  envelope: Boolean = false,
): String = buildString {
  appendLine("val $obj = handle.asStableRef<$qualifiedName>().get()")
  append(paramPrelude)
  if (!elementNullable && !memberNullable) {
    val read: String = flowValueExpression("$call.value", elementCollection)
    append("return NugetHandles.retain(${stateFlowBox(read, envelope)})")
  } else {
    appendLine("val v = ${memberAccessor(call, memberNullable)}.value")
    append("return if (v != null) NugetHandles.retain(${stateFlowNullableBox(envelope)}) else null")
  }
}

// ADR-201 amendment: what a `_value` read boxes: the ADR-107 envelope for a Throwable element,
// else the value itself (the shipped `as Any` spelling).
private fun stateFlowBox(read: String, envelope: Boolean): String =
  if (envelope) "buildError($read, ::nugetMappedType)" else "$read as Any"

private fun stateFlowNullableBox(envelope: Boolean): String =
  if (envelope) "buildError(v, ::nugetMappedType)" else "v"

// ADR-067: nullable-member presence probe -- backs the C# `_has_value` two-call pattern. A pure,
// idempotent read of a `val`/getter-backed Flow or StateFlow reference; safe to call before
// subscribing.
private fun buildFlowHasValuePropertyBody(
  qualifiedName: String,
  propName: String,
): String = buildString {
  appendLine("val obj = handle.asStableRef<$qualifiedName>().get()")
  append("return obj.$propName != null")
}

private fun buildFlowHasValueMethodBody(
  qualifiedName: String,
  call: String,
  paramPrelude: String,
  obj: String,
): String = buildString {
  appendLine("val $obj = handle.asStableRef<$qualifiedName>().get()")
  append(paramPrelude)
  append("return $call != null")
}

/**
 * ADR-071: the settable `.Value` write seam's Kotlin parameters (in order, after the receiver
 * handle) and the assignment expression that unwraps them into the element.
 */
internal data class MutableStateFlowWriteSlot(
  val parameters: List<Pair<String, TypeName>>,
  val assignment: String,
)

/**
 * ADR-071: the Kotlin half of the write seam for a (already-[isMutableStateFlowElementWritable])
 * MutableStateFlow<T> element, one arm per [MutableStateFlowElement.Writable]. Primitive/`Char`/
 * `String` cross by value (no conversion, or the one conversion `String` already needs); an enum
 * crosses as its ordinal and is read back with `entries[value]`, byte-for-byte the synchronous
 * enum setter's shape (`ForwardPropertyKotlinEmitter`); an ordinary class/object element crosses
 * as a `COpaquePointer` and is unwrapped via `asStableRef`, the same shape as
 * `ForwardPropertyKotlinEmitter.valueExpression`'s `ObjectHandle` branch. A value class crosses as
 * its underlying and is re-wrapped ([valueClassWriteSlot]).
 *
 * Nullable element write: a `String?` is one nullable slot, an object a nullable pointer unwrapped
 * null-safely, and a scalar the has-value pair `addLegacyScalarParameter` gives every sibling
 * legacy-route slot (`valueHasValue, value`), so a null is never a zero. A nullable enum never
 * reaches here: the gate keeps it read-only.
 */
internal fun mutableStateFlowWriteSlot(elementType: KSType?): MutableStateFlowWriteSlot {
  val declaration = elementType?.expandAliases()?.declaration
  val simpleName: String = declaration?.simpleName?.asString() ?: "Any"
  val nullable: Boolean = elementType?.isMarkedNullable == true
  val element: MutableStateFlowElement.Writable = writableMutableStateFlowElement(elementType)
  return when (element) {
    is MutableStateFlowElement.Handle -> {
      val unwrap: String = if (nullable) {
        "value?.asStableRef<${element.kotlinType}>()?.get()"
      } else {
        "value.asStableRef<${element.kotlinType}>().get()"
      }
      MutableStateFlowWriteSlot(listOf("value" to cOpaquePointer.copy(nullable = nullable)), unwrap)
    }

    // A nullable enum is the nullable scalar's has-value pair over the ordinal slot, so a null is
    // never ordinal 0; an out-of-range ordinal still throws out of `entries[value]`.
    is MutableStateFlowElement.Enum -> if (nullable) {
      MutableStateFlowWriteSlot(
        listOf("valueHasValue" to BOOLEAN, "value" to INT),
        "if (valueHasValue) ${element.qualifiedName}.entries[value] else null",
      )
    } else {
      MutableStateFlowWriteSlot(listOf("value" to INT), "${element.qualifiedName}.entries[value]")
    }

    MutableStateFlowElement.Scalar -> if (nullable && simpleName != "String") {
      MutableStateFlowWriteSlot(
        listOf("valueHasValue" to BOOLEAN, "value" to ClassName("kotlin", simpleName)),
        "if (valueHasValue) value else null",
      )
    } else {
      MutableStateFlowWriteSlot(
        listOf("value" to ClassName("kotlin", simpleName).copy(nullable = nullable)),
        "value",
      )
    }

    is MutableStateFlowElement.ValueClass -> valueClassWriteSlot(element, nullable)
  }
}

/**
 * ADR-071 amendment (value-class element write): the Kotlin half, the synchronous value-class
 * setter's re-wrap (`ForwardPropertyKotlinEmitter`, ADR-077): the slot is the underlying's own
 * wire and the assignment rebuilds the value class around it, so its `init` re-runs. A nullable
 * element is null exactly when the underlying slot says so (a null pointer, or the has-value
 * pair's flag).
 */
private fun valueClassWriteSlot(
  element: MutableStateFlowElement.ValueClass,
  nullable: Boolean,
): MutableStateFlowWriteSlot {
  val wrap: (String) -> String = { underlying: String -> "${element.qualifiedName}($underlying)" }
  val hasValuePair: (TypeName, String) -> MutableStateFlowWriteSlot = { wire, unwrapped ->
    MutableStateFlowWriteSlot(
      listOf("valueHasValue" to BOOLEAN, "value" to wire),
      "if (valueHasValue) ${wrap(unwrapped)} else null",
    )
  }
  return when (val underlying: MutableStateFlowElement.Writable = element.underlying) {
    is MutableStateFlowElement.Handle -> if (nullable) {
      MutableStateFlowWriteSlot(
        listOf("value" to cOpaquePointer.copy(nullable = true)),
        "value?.asStableRef<${underlying.kotlinType}>()?.get()?.let { ${wrap("it")} }",
      )
    } else {
      MutableStateFlowWriteSlot(
        listOf("value" to cOpaquePointer),
        wrap("value.asStableRef<${underlying.kotlinType}>().get()"),
      )
    }

    is MutableStateFlowElement.Enum -> if (nullable) {
      hasValuePair(INT, "${underlying.qualifiedName}.entries[value]")
    } else {
      MutableStateFlowWriteSlot(
        listOf("value" to INT),
        wrap("${underlying.qualifiedName}.entries[value]"),
      )
    }

    MutableStateFlowElement.Scalar -> {
      val wire = ClassName("kotlin", element.underlyingSimpleName)
      when {
        element.underlyingSimpleName == "String" -> MutableStateFlowWriteSlot(
          listOf("value" to wire.copy(nullable = nullable)),
          if (nullable) "value?.let { ${wrap("it")} }" else wrap("value"),
        )

        nullable -> hasValuePair(wire, "value")
        else -> MutableStateFlowWriteSlot(listOf("value" to wire), wrap("value"))
      }
    }

    is MutableStateFlowElement.ValueClass ->
      error("value class ${element.qualifiedName} over a value class has no write arm")
  }
}

internal fun FunSpec.Builder.addMutableStateFlowWriteSlot(
  slot: MutableStateFlowWriteSlot,
): FunSpec.Builder = apply {
  slot.parameters.forEach { (name: String, type: TypeName) -> addParameter(name, type) }
}

/**
 * The element of a held `MutableStateFlow<T>` as the flow-keyed write exports read the flow at
 * (`MutableStateFlow<kotlin.Int?>`, `MutableStateFlow<pkg.Box<kotlin.String>>`): the one
 * spelling the write slot's handle arm uses ([MutableStateFlowElement.Handle.kotlinType]), with
 * the element's own nullability.
 */
private fun heldStateFlowElementSpelling(elementType: KSType?): String =
  elementType?.expandAliases()?.forwardKotlinArgumentSpelling() ?: "kotlin.Any"

/**
 * ADR-071 (2026-09-11): the held route's flow-handle-keyed `${stem}_set_value(flowHandle, value,
 * errorOut)` export. Shared by the held function-return route and the suspend route that awaits
 * a `MutableStateFlow<T>`, both of which hand C# the flow's own handle.
 */
internal fun FileSpec.Builder.addHeldStateFlowSetValueExport(
  stem: String,
  owner: KSFunctionDeclaration,
  elementType: KSType?,
) {
  val elementQualified: String = heldStateFlowElementSpelling(elementType)
  val slot: MutableStateFlowWriteSlot = mutableStateFlowWriteSlot(elementType)
  addFunction(
    FunSpec.builder("export_${stem}_set_value")
      .addAnnotation(cNameAnnotation("${stem}_set_value", ownedBy(owner)))
      // The owner handle and the method's own parameters are gone: the write is keyed on the flow
      // the call already handed out, which is the whole point of holding it.
      .addParameter("flowHandle", cOpaquePointer)
      .addMutableStateFlowWriteSlot(slot)
      .addParameter("errorOut", cOpaquePointer.copy(nullable = true))
      .addCode(
        buildStateFlowHandleSetValueBody(elementQualified, slot.assignment),
        cOpaquePointerVar,
        nugetHandles,
      )
      .build()
  )
}

// ADR-071: the `_set_value` export body -- writes `value` (already unwrapped by [assignment]) into
// the underlying MutableStateFlow's `.value`. MutableStateFlow.value's setter conflates by
// `Any.equals` on the PREVIOUS value (kotlinx.coroutines StateFlow.kt), so a throwing `equals`
// (or a throwing object `equals`/handle dereference) propagates out and is wrapped via `errorOut`,
// the same ADR-030 shape every ordinary `var` property setter already carries.
// Nullable member write: a member found absent throws into the same catch, so it surfaces in C#
// as a `KotlinException` instead of a silent no-op (the C# getter answers null before then).
private fun buildStateFlowSetValuePropertyBody(
  qualifiedName: String,
  propName: String,
  assignment: String,
  memberNullable: Boolean,
): String = buildString {
  val member: String = "handle.asStableRef<$qualifiedName>().get().$propName"
  appendLine("try {")
  if (memberNullable) {
    val name: String = propName.removeSurrounding("`")
    appendLine("  ($member ?: throw IllegalStateException(\"$name is null\")).value = $assignment")
  } else {
    appendLine("  $member.value = $assignment")
  }
  appendLine("} catch (e: Throwable) {")
  appendLine("  if (errorOut != null) {")
  appendLine("    errorOut.reinterpret<%T>().pointed.value = %T.retain(")
  appendLine("      buildError(e, ::nugetMappedType)")
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
  call: String,
  paramPrelude: String,
  obj: String,
): String = buildString {
  // ADR-202: the held flow is read through the runtime-owned `nuget_stateflow_collect`.
  appendLine(INSTALL_MODULE_MAPPED_TYPE)
  appendLine("val $obj = handle.asStableRef<$qualifiedName>().get()")
  append(paramPrelude)
  append("return NugetHandles.retain($call as Any)")
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
  appendLine("      buildError(e, ::nugetMappedType)")
  appendLine("    )")
  appendLine("  }")
  append("}")
}

/**
 * ADR-071 Alternative 4: [this] write slot re-labelled from `value` to [stem], so a
 * `compareAndSet(expect, update)` export crosses each of its two parameters through the setter's
 * own slot (has-value pair, nullable pointer and all) without restating its marshalling.
 */
private fun MutableStateFlowWriteSlot.relabelled(stem: String): MutableStateFlowWriteSlot {
  val name = Regex("""(?<![.\w])value(HasValue)?\b""")
  return MutableStateFlowWriteSlot(
    parameters = parameters.map { (param: String, type: TypeName) ->
      name.replace(param) { "$stem${it.groupValues[1]}" } to type
    },
    assignment = name.replace(assignment) { "$stem${it.groupValues[1]}" },
  )
}

/** ADR-071 Alternative 4: the `expect` slot, the `update` slot, in export order. */
private fun mutableStateFlowCompareAndSetSlots(
  elementType: KSType?,
): Pair<MutableStateFlowWriteSlot, MutableStateFlowWriteSlot> {
  val slot: MutableStateFlowWriteSlot = mutableStateFlowWriteSlot(elementType)
  return slot.relabelled("expect") to slot.relabelled("update")
}

/**
 * ADR-071 Alternative 4: the property route's owner-keyed
 * `${prefix}_compare_and_set_${propName}_value(handle, expect, update, errorOut): Boolean`, the
 * `_set_value` sibling with two write slots. `compareAndSet` conflates by the previous value's
 * `equals` exactly like the setter, so a throw lands in `errorOut` and returns `false`, which the
 * C# side never reads as a missed swap (`NugetErrorNative.Check` throws first).
 */
private fun FileSpec.Builder.addStateFlowCompareAndSetPropertyExport(
  prop: KSPropertyDeclaration,
  qualifiedName: String,
  prefix: String,
  propName: String,
  propCall: String,
  elementType: KSType?,
  memberNullable: Boolean,
) {
  val (expect: MutableStateFlowWriteSlot, update: MutableStateFlowWriteSlot) =
    mutableStateFlowCompareAndSetSlots(elementType)
  val holder: String = "handle.asStableRef<$qualifiedName>().get().$propCall"
  val receiver: String = if (memberNullable) {
    val name: String = propCall.removeSurrounding("`")
    "($holder ?: throw IllegalStateException(\"$name is null\"))"
  } else {
    holder
  }
  addFunction(
    FunSpec.builder("export_${prefix}_compare_and_set_${propName}_value")
      .addAnnotation(
        cNameAnnotation("${prefix}_compare_and_set_${propName}_value", ownedBy(prop)),
      )
      .addParameter("handle", cOpaquePointer)
      .addMutableStateFlowWriteSlot(expect)
      .addMutableStateFlowWriteSlot(update)
      .addParameter("errorOut", cOpaquePointer.copy(nullable = true))
      .returns(BOOLEAN)
      .addCode(
        buildStateFlowCompareAndSetBody(
          "$receiver.compareAndSet(${expect.assignment}, ${update.assignment})",
        ),
        cOpaquePointerVar,
        nugetHandles,
      )
      .build()
  )
}

/**
 * ADR-071 Alternative 4: the held route's flow-keyed `${stem}_compare_and_set(flowHandle, expect,
 * update, errorOut): Boolean`, beside [addHeldStateFlowSetValueExport] on every route that calls
 * it (the held function return and the awaited suspend return). [stem] already carries the
 * overload suffix, so two overloads never share one C symbol.
 */
internal fun FileSpec.Builder.addHeldStateFlowCompareAndSetExport(
  stem: String,
  owner: KSFunctionDeclaration,
  elementType: KSType?,
) {
  val elementQualified: String = heldStateFlowElementSpelling(elementType)
  val (expect: MutableStateFlowWriteSlot, update: MutableStateFlowWriteSlot) =
    mutableStateFlowCompareAndSetSlots(elementType)
  val flow: String =
    "flowHandle.asStableRef<kotlinx.coroutines.flow.MutableStateFlow<$elementQualified>>().get()"
  addFunction(
    FunSpec.builder("export_${stem}_compare_and_set")
      .addAnnotation(cNameAnnotation("${stem}_compare_and_set", ownedBy(owner)))
      .addParameter("flowHandle", cOpaquePointer)
      .addMutableStateFlowWriteSlot(expect)
      .addMutableStateFlowWriteSlot(update)
      .addParameter("errorOut", cOpaquePointer.copy(nullable = true))
      .returns(BOOLEAN)
      .addCode(
        buildStateFlowCompareAndSetBody(
          "$flow.compareAndSet(${expect.assignment}, ${update.assignment})",
        ),
        cOpaquePointerVar,
        nugetHandles,
      )
      .build()
  )
}

// ADR-071 Alternative 4: `return try { <cas> } catch { errorOut; false }`, the `_has_value`-style
// Boolean shape with the setter's ADR-030 error slot.
private fun buildStateFlowCompareAndSetBody(compareAndSet: String): String = buildString {
  appendLine("return try {")
  appendLine("  $compareAndSet")
  appendLine("} catch (e: Throwable) {")
  appendLine("  if (errorOut != null) {")
  appendLine("    errorOut.reinterpret<%T>().pointed.value = %T.retain(")
  appendLine("      buildError(e, ::nugetMappedType)")
  appendLine("    )")
  appendLine("  }")
  appendLine("  false")
  append("}")
}
