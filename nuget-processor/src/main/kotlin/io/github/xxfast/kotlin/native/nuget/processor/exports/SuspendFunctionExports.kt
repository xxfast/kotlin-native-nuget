package io.github.xxfast.kotlin.native.nuget.processor.exports

import io.github.xxfast.kotlin.native.nuget.processor.forward.legacyFlowElementEnvelope
import io.github.xxfast.kotlin.native.nuget.processor.forward.kotlinIdentifier
import io.github.xxfast.kotlin.native.nuget.processor.ForwardSymbolTable
import io.github.xxfast.kotlin.native.nuget.processor.forward.importIfDefaultPackage
import io.github.xxfast.kotlin.native.nuget.processor.forward.kotlinPackageReference
import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeParameter
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Visibility
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.BOOLEAN
import com.squareup.kotlinpoet.STRING
import com.squareup.kotlinpoet.INT
import io.github.xxfast.kotlin.native.nuget.processor.forward.BridgeType
import io.github.xxfast.kotlin.native.nuget.processor.forward.simpleKotlinName
import io.github.xxfast.kotlin.native.nuget.processor.forward.unwrapNullable
import io.github.xxfast.kotlin.native.nuget.processor.forward.legacyInvocation
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.ParameterSpec
import io.github.xxfast.kotlin.native.nuget.processor.forward.legacyWireLowering
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import io.github.xxfast.kotlin.native.nuget.processor.cir.FLOW_TYPES
import io.github.xxfast.kotlin.native.nuget.processor.cir.READ_ONLY_STATE_FLOW_TYPES
import io.github.xxfast.kotlin.native.nuget.processor.cir.STATE_FLOW_TYPES
import io.github.xxfast.kotlin.native.nuget.processor.cir.MUTABLE_STATE_FLOW_TYPES
import io.github.xxfast.kotlin.native.nuget.processor.forward.legacyFlowElement
import io.github.xxfast.kotlin.native.nuget.processor.forward.legacyFlowElementShape
import io.github.xxfast.kotlin.native.nuget.processor.forward.legacyFlowElementCollection
import io.github.xxfast.kotlin.native.nuget.processor.forward.legacyFlowElementWire
import io.github.xxfast.kotlin.native.nuget.processor.forward.legacyWireBox
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardLegacyFlowElementShape
import io.github.xxfast.kotlin.native.nuget.processor.cir.expandAliases
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardBridgeTypeClassifier
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardExportOwnerTag
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardCallablePlanCatalog
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardLegacyParameterShape
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardLegacyReturnShape
import io.github.xxfast.kotlin.native.nuget.processor.forward.collectionResultProjection
import io.github.xxfast.kotlin.native.nuget.processor.forward.isLegacyLowered
import io.github.xxfast.kotlin.native.nuget.processor.forward.isLegacyNullableSlot
import io.github.xxfast.kotlin.native.nuget.processor.forward.legacyPrelude
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardLegacyNames
import io.github.xxfast.kotlin.native.nuget.processor.forward.legacyKotlinNames
import io.github.xxfast.kotlin.native.nuget.processor.forward.legacyParameterShapes
import io.github.xxfast.kotlin.native.nuget.processor.forward.legacyRefusedParameter
import io.github.xxfast.kotlin.native.nuget.processor.forward.forwardSuperClass
import io.github.xxfast.kotlin.native.nuget.processor.forward.forwardSuspendRouteMethods
import io.github.xxfast.kotlin.native.nuget.processor.forward.legacyRefusedReturn
import io.github.xxfast.kotlin.native.nuget.processor.forward.legacyReturnShape
import io.github.xxfast.kotlin.native.nuget.processor.toCName
import io.github.xxfast.kotlin.native.nuget.processor.freshName
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardStaticSuspendMember
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardStaticSuspendOwner
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardStaticSuspendReceiver
import io.github.xxfast.kotlin.native.nuget.processor.forward.forwardExtensionImportAlias
import io.github.xxfast.kotlin.native.nuget.processor.forward.legacyHandleStatement
import com.squareup.kotlinpoet.MemberName
import io.github.xxfast.kotlin.native.nuget.processor.cir.nativePrefix
import io.github.xxfast.kotlin.native.nuget.processor.forward.forwardMemberGenericRefusal
import io.github.xxfast.kotlin.native.nuget.processor.forward.forwardBoundedRead
import io.github.xxfast.kotlin.native.nuget.processor.forward.forwardMethodTypeParameters
import io.github.xxfast.kotlin.native.nuget.processor.forward.forwardUncheckedCastSuppression
import io.github.xxfast.kotlin.native.nuget.processor.forward.legacyBounds
import io.github.xxfast.kotlin.native.nuget.processor.forward.forwardOwnerTypeName

/**
 * Generates @CName bridge exports for suspend functions using a callback-based async pattern.
 *
 * Each suspend function gets a non-suspend wrapper that:
 * 1. Accepts the original parameters plus a callback pointer and userData pointer
 * 2. Launches a coroutine on Dispatchers.Default
 * 3. Calls the original suspend function
 * 4. Invokes the callback with (resultPtr, null, userData) on success
 *    or (null, errorPtr, userData) on error
 *
 * @see <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/019-suspend-function-mapping.md">ADR-019: Suspend function mapping</a>
 */
internal fun FileSpec.Builder.addSuspendFunctionExports(
  func: KSFunctionDeclaration,
  /** ADR-163: the one symbol table. */
  symbols: ForwardSymbolTable,
  // ADR-114: same classification the class-method route below uses. This route compiles today
  // (`addParameters` keeps the type arguments), but it hands C# an IntPtr no caller can produce.
  classifier: ForwardBridgeTypeClassifier,
  // ROADMAP line 29 (ADR-118 amendment): the planner's ADR-095 number for this function, shared
  // with its ordinary top-level namesakes. `translateSuspendFunction` reads the same catalog.
  callableCatalog: ForwardCallablePlanCatalog,
) {
  if (!classifier.admitsStaticSuspend(func)) return
  // ADR-163: library- and package-qualified, and the Kotlin call fully qualified rather than
  // imported by simple name (two same-named suspend functions in two packages both export now).
  // ROADMAP line 29: plus the overload number, so two top-level `suspend` overloads take two C
  // symbols. The Kotlin call below stays bare -- the suffix names the export, not the function.
  val cname: String = symbols.topLevel(func) + callableCatalog.overloadSuffix(func)
  val callee: String =
    kotlinPackageReference(func.packageName.asString()) +
      func.simpleName.asString().kotlinIdentifier()
  // ADR-163: the default package has no qualifier to spell, so the bare call needs this import.
  importIfDefaultPackage(func)
  addStaticSuspendExport(func, cname, { callee }, receiver = null, classifier, callableCatalog)
}

/**
 * ADR-229: a `suspend` member of an owner with no C# instance scope, on the top-level route with a
 * static receiver expression: an `object`'s or a companion's member is called through its owner, an
 * extension's, an enum member's and a value-class member's receiver crosses as the export's
 * leading slot. Same ad-hoc root scope per call as a top-level function.
 */
internal fun FileSpec.Builder.addStaticSuspendExports(
  member: ForwardStaticSuspendMember,
  classifier: ForwardBridgeTypeClassifier,
  callableCatalog: ForwardCallablePlanCatalog,
) {
  val func: KSFunctionDeclaration = member.function
  if (!classifier.admitsStaticSuspend(func)) return
  val name: String = func.simpleName.asString().kotlinIdentifier()
  val owner: ForwardStaticSuspendOwner = member.owner
  val receiver: ForwardStaticSuspendReceiver? = when (owner) {
    is ForwardStaticSuspendOwner.Object -> null
    is ForwardStaticSuspendOwner.Companion -> null
    is ForwardStaticSuspendOwner.Extension -> owner.receiver
    is ForwardStaticSuspendOwner.EnumMember -> owner.receiver
    is ForwardStaticSuspendOwner.ValueClass -> owner.underlying
  }
  val callee: (String) -> String = when (owner) {
    is ForwardStaticSuspendOwner.Object -> {
      importIfDefaultPackage(owner.obj)
      val qualified: String = owner.obj.kotlinQualifiedReference()
      ({ "$qualified.$name" })
    }
    is ForwardStaticSuspendOwner.Companion -> {
      importIfDefaultPackage(owner.owner)
      val qualified: String = owner.owner.kotlinQualifiedReference()
      ({ "$qualified.$name" })
    }
    is ForwardStaticSuspendOwner.Extension -> {
      val alias: String =
        forwardExtensionImportAlias(func.packageName.asString(), func.simpleName.asString())
      addAliasedImport(MemberName(func.packageName.asString(), func.simpleName.asString()), alias)
      ({ read -> "$read.$alias" })
    }
    is ForwardStaticSuspendOwner.EnumMember -> {
      importIfDefaultPackage(owner.enum)
      val qualified: String = owner.enum.kotlinQualifiedReference()
      ({ read -> "$qualified.entries[$read].$name" })
    }
    is ForwardStaticSuspendOwner.ValueClass -> {
      importIfDefaultPackage(owner.valueClass)
      val qualified: String = owner.construction ?: owner.valueClass.kotlinQualifiedReference()
      ({ read -> "$qualified($read).$name" })
    }
  }
  addStaticSuspendExport(func, owner.stem, callee, receiver, classifier, callableCatalog)
}

/**
 * The legacy refusals both halves apply before any export (or import) is emitted: ADR-114's
 * parameter, ADR-119's return and ADR-214's own-`T` rule. A refused member is named once by
 * `warnRefusedLegacyRouteMembers`.
 */
private fun ForwardBridgeTypeClassifier.admitsStaticSuspend(func: KSFunctionDeclaration): Boolean =
  legacyRefusedParameter(func.parameters) == null && legacyRefusedReturn(func) == null &&
    func.forwardMemberGenericRefusal() == null

/** ADR-229: [this] declaration's fully qualified Kotlin spelling, each segment escaped. */
internal fun KSClassDeclaration.kotlinQualifiedReference(): String {
  val packageName: String = packageName.asString()
  val qualified: String = qualifiedName?.asString() ?: simpleName.asString()
  val local: String = if (packageName.isEmpty()) qualified else qualified.removePrefix("$packageName.")
  return kotlinPackageReference(packageName) +
    local.split(".").joinToString(".") { segment -> segment.kotlinIdentifier() }
}

/**
 * The `_async` export of one statically reached `suspend` function. [callee] spells the called
 * function from the receiver local's name (unused when [receiver] is null).
 */
private fun FileSpec.Builder.addStaticSuspendExport(
  func: KSFunctionDeclaration,
  cname: String,
  callee: (String) -> String,
  receiver: ForwardStaticSuspendReceiver?,
  classifier: ForwardBridgeTypeClassifier,
  callableCatalog: ForwardCallablePlanCatalog,
) {
  val returnType = func.returnType?.resolve()?.expandAliases()
  val qualifiedReturn: String = returnType?.declaration?.qualifiedName?.asString() ?: "kotlin.Unit"
  val isUnit: Boolean =
    qualifiedReturn == "kotlin.Unit" && returnType?.declaration !is KSTypeParameter
  // Issue #108: a nullable return cannot go straight into `StableRef.create`, whose T is `Any`.
  val returnShape: ForwardLegacyReturnShape = classifier.legacyReturnShape(returnType)
  // ADR-214: an unbounded `T` result is nullable without a `?`.
  val isNullable: Boolean = returnType?.isMarkedNullable == true || returnShape.isNullableBox()

  // ADR-164: the catalog's default flags (the ADR-074 expect index included) widen the defaulted
  // parameters, exactly as `translateSuspendFunction` reads them for the C# half.
  val paramShapes: List<ForwardLegacyParameterShape> =
    classifier.legacyParameterShapes(func.parameters, callableCatalog.legacyDefaultFlags(func))
  val names: ForwardLegacyNames = legacyKotlinNames(func.parameters, paramShapes)
  // ADR-229: the receiver slot, named apart from every parameter and generated slot.
  val receiverSlot: String = freshName(
    "receiver",
    func.legacyParameterNames().toSet() +
      setOf(names.callback, names.userData, names.obj, names.scope, names.scopeHandle),
  )
  // A handle receiver is read before the launch, as a borrowed handle parameter is (ADR-122).
  val receiverPrelude: String = when (receiver) {
    is ForwardStaticSuspendReceiver.Handle ->
      legacyHandleStatement(receiverSlot, names.obj, receiver.type, receiver.nullable) + "\n"
    // ADR-225: a `T` box is read the same way, through its bound checks, before the launch.
    is ForwardStaticSuspendReceiver.Box -> {
      val bounds: List<String> = listOfNotNull(receiver.type.boundQualifiedName) +
        receiver.type.additionalBounds
      val variable: String? = receiver.type.kotlinName
        .takeIf { receiver.trampolineBounds.isNotEmpty() }
      val read: String = forwardBoundedRead(receiverSlot, bounds, receiver.nullable, variable)
      "val ${names.obj} = $read\n"
    }
    // ADR-240: the receiver's wire read into the local; a value class re-wraps at the call.
    is ForwardStaticSuspendReceiver.Wire -> receiver.wirePrelude(receiverSlot, names.obj) + "\n"
    is ForwardStaticSuspendReceiver.Scalar, is ForwardStaticSuspendReceiver.Enum, null -> ""
  }
  val receiverRead: String = when (receiver) {
    is ForwardStaticSuspendReceiver.Handle, is ForwardStaticSuspendReceiver.Box -> names.obj
    is ForwardStaticSuspendReceiver.Wire -> receiver.shape.view?.kotlinWrap(names.obj) ?: names.obj
    is ForwardStaticSuspendReceiver.Scalar, is ForwardStaticSuspendReceiver.Enum, null ->
      receiverSlot
  }
  // ADR-219: a trampolined `T` (ADR-198) needs the type variable the launch lambda reads, so the
  // whole export, launch included, runs inside the trampoline.
  val declared: ForwardFlowTrampoline? = flowTrampoline(func, owner = null, paramShapes)
  // ADR-225: a value-class receiver's `T` with no erased spelling is re-declared there too.
  val receiverVariable: Pair<String, List<String>>? =
    (receiver as? ForwardStaticSuspendReceiver.Box)
      ?.takeIf { box -> box.trampolineBounds.isNotEmpty() }
      ?.let { box -> box.type.kotlinName to box.trampolineBounds }
  val trampoline: ForwardFlowTrampoline? = when {
    receiverVariable == null -> declared
    declared == null -> ForwardFlowTrampoline(listOf(receiverVariable), emptySet(), null)
    else -> declared.copy(variables = listOf(receiverVariable) + declared.variables)
  }
  val call: String = names.legacyInvocation(
    callee(receiverRead) + func.legacyTypeArguments(),
    func.legacyParameterNames(),
  )
  val paramPrelude: String = receiverPrelude + names.legacyPrelude(func.legacyParameterNames())
  val boxed: String = legacyBoxedResult(returnShape)

  val body: String = buildSuspendFunctionBody(
    call, paramPrelude, isUnit, isNullable, boxed, names,
    installsMappedType = qualifiedReturn in STATE_FLOW_TYPES,
  )

  val builder: FunSpec.Builder = FunSpec.builder("export_${cname}_async")
    // ADR-117: names the function as the owner, so a collision this numbering does not cover
    // (for example across routes) still reports which declarations share the symbol.
    .addAnnotation(cNameAnnotation("${cname}_async", ownedBy(func)))
  when (receiver) {
    is ForwardStaticSuspendReceiver.Handle ->
      builder.addParameter(receiverSlot, cOpaquePointer.copy(nullable = receiver.nullable))
    is ForwardStaticSuspendReceiver.Scalar ->
      builder.addParameter(receiverSlot, ClassName.bestGuess(receiver.kotlinType))
    is ForwardStaticSuspendReceiver.Enum -> builder.addParameter(receiverSlot, INT)
    is ForwardStaticSuspendReceiver.Wire ->
      builder.addParameters(receiver.wireParameters(receiverSlot))
    is ForwardStaticSuspendReceiver.Box ->
      builder.addParameter(receiverSlot, cOpaquePointer.copy(nullable = receiver.nullable))
    null -> Unit
  }
  builder
    .addLegacySuspendParameters(func, paramShapes, names)
    .addParameter(names.callback, cOpaquePointer)
    .addParameter(names.userData, cOpaquePointer)
    .returns(cOpaquePointer)
    .addCode(body)
  if (paramShapes.legacyCastsGenericBound()) {
    builder.addAnnotation(forwardUncheckedCastSuppression)
  }

  addFunction(builder.build().flowTrampolined(trampoline, qualifiedName = ""))
  addAcquiredFlowCollectExport(func, cname, returnType, classifier)
  addFlowKeyedSharedFlowExports(cname, func, returnType, classifier)
  addAwaitedStateFlowCollectionExports(func, cname, returnType, classifier)
  // ADR-230: a settable awaited `MutableStateFlow<T>`, written through the held route's seams as
  // on the class route.
  if (func.awaitsSettableMutableStateFlow()) {
    val element: KSType? = returnType?.arguments?.firstOrNull()?.type?.resolve()?.expandAliases()
    addHeldStateFlowSetValueExport(cname, func, element)
    addHeldStateFlowCompareAndSetExport(cname, func, element)
  }
}

/**
 * ADR-118: the same builder now serves an ordinary class and a sealed subclass. A sealed arm passes
 * its own export [prefix] (`job_running`) and [isArm], because the sealed route -- planner, C#
 * translator and this builder alike -- binds the arm's own surface: what it declares, plus what it
 * inherits from an interface the sealed type does not carry (ADR-101 amendment 2026-09-27).
 */
internal fun FileSpec.Builder.addSuspendClassMethodExports(
  cls: KSClassDeclaration,
  classifier: ForwardBridgeTypeClassifier,
  callableCatalog: ForwardCallablePlanCatalog,
  symbols: ForwardSymbolTable,
  prefix: String = cls.nativePrefix(symbols),
  isArm: Boolean = false,
  exportedTypes: Set<String> = emptySet(),
) {
  // ADR-219: a generic owner reads its receiver erased (`Platter<Any?>`, `Picnic<*>`), the Flow
  // route's spelling (ADR-215); every other owner its bare name.
  val qualifiedName: String = cls.forwardOwnerTypeName() ?: cls.qualifiedName?.asString() ?: return

  // ADR-159: one selector, shared with the C# half (`translateClass`'s `allSuspendMethods`) and
  // with the gate in `NugetProcessor`. It owns the ADR-147 generic-owner refusal, the ADR-114/119
  // parameter and return refusals, ADR-118's arm rule for a sealed arm, the
  // `isForwardMemberOf` membership rule the C# half always applied and the new
  // `override suspend fun` skip. This half used to filter on strictly less and emitted a stray
  // export per inherited suspend member, which `ForwardAbiContract.kotlin` cannot see because it
  // filters Kotlin exports down to the C# import set.
  val suspendMethods: List<KSFunctionDeclaration> = cls.forwardSuspendRouteMethods(
    classifier = classifier,
    superClass = if (isArm) null else cls.forwardSuperClass(exportedTypes),
    isArm = isArm,
  )

  suspendMethods.forEach { method ->
    val methodName: String = method.simpleName.asString()
    // ADR-118: the planner's overload number, so two `suspend` overloads take two C symbols. The
    // Kotlin call site below stays bare -- the suffix names the export, not the method.
    val cname: String = toCName(methodName) + callableCatalog.overloadSuffix(method)
    val returnType = method.returnType?.resolve()?.expandAliases()
    val qualifiedReturn: String = returnType?.declaration?.qualifiedName?.asString() ?: "kotlin.Unit"
    val isUnit: Boolean =
    qualifiedReturn == "kotlin.Unit" && returnType?.declaration !is KSTypeParameter
    // Issue #108: same nullable-return guard as the top-level builder.
    val returnShape: ForwardLegacyReturnShape = classifier.legacyReturnShape(returnType)
    val isNullable: Boolean = returnType?.isMarkedNullable == true || returnShape.isNullableBox()

    val paramShapes: List<ForwardLegacyParameterShape> = classifier.legacyParameterShapes(
      method.parameters, callableCatalog.legacyDefaultFlags(method),
    )
    val names: ForwardLegacyNames = legacyKotlinNames(method.parameters, paramShapes)
    // ADR-219: the Flow route's trampoline (ADR-215): a trampolined own `T`, or a multi-bound
    // owner `T` the star-projected receiver cannot be called with.
    val trampoline: ForwardFlowTrampoline? =
      flowTrampoline(method, owner = cls, paramShapes)
    names.trampolineVariables = trampoline?.ownerVariables.orEmpty()
    val call: String =
      names.legacyInvocation(
        "${names.obj}.${methodName.kotlinIdentifier()}${method.legacyTypeArguments()}",
        method.legacyParameterNames(),
      )
    val paramPrelude: String = names.legacyPrelude(method.legacyParameterNames())
    val boxed: String = legacyBoxedResult(returnShape)

    val body: String = buildSuspendMethodBody(
      qualifiedName, call, paramPrelude, isUnit, isNullable, boxed, names,
      installsMappedType = qualifiedReturn in STATE_FLOW_TYPES,
    )

    val builder: FunSpec.Builder = FunSpec.builder("export_${prefix}_${cname}_async")
      .addAnnotation(cNameAnnotation("${prefix}_${cname}_async", ownedBy(method)))
      .addParameter("handle", cOpaquePointer)
      .addParameter(names.scopeHandle, cOpaquePointer)
    if (paramShapes.legacyCastsGenericBound()) {
      builder.addAnnotation(forwardUncheckedCastSuppression)
    }

    method.parameters.forEachIndexed { index, param ->
      val paramName: String = param.name?.asString() ?: "_"
      // ADR-210: a suspend lambda's flat thunk and key slots, in the planner's order.
      names.asyncSlots[index]?.let { slots ->
        slots.declared.forEach { slot -> builder.addParameter(slot, cOpaquePointer) }
        return@forEachIndexed
      }
      // ADR-114: a collection crosses as a handle to its boxed wire container.
      // ADR-122: so does a class/object/sealed parameter, as the borrowed handle C# already holds.
      // ADR-240: a type riding its ordinary-route wire.
      val wire = paramShapes[index] as? ForwardLegacyParameterShape.Wire
      if (wire != null) {
        builder.addLegacyWireParameter(paramName, wire, names.hasValueSlots[index])
        return@forEachIndexed
      }
      if (paramShapes[index].isLegacyLowered()) {
        val nullable: Boolean = paramShapes[index].isLegacyNullableSlot
        // ADR-164 rule 2: an optional handle's leading `IsSet` slot.
        names.isSetSlots[index]?.let { isSet -> builder.addParameter(isSet, BOOLEAN) }
        builder.addParameter(paramName, cOpaquePointer.copy(nullable = nullable))
        return@forEachIndexed
      }
      val resolved: KSType = param.type.resolve().expandAliases()
      val type: String = resolved.declaration.qualifiedName?.asString()
        ?: resolved.declaration.simpleName.asString()
      builder.addLegacyScalarParameter(
        paramName, paramShapes[index], ClassName.bestGuess(type), names.hasValueSlots[index],
        names.isSetSlots[index],
      )
    }

    builder
      .addParameter(names.callback, cOpaquePointer)
      .addParameter(names.userData, cOpaquePointer)
      .returns(cOpaquePointer)
      .addCode(body)

    addFunction(builder.build().flowTrampolined(trampoline, qualifiedName))
    addAcquiredFlowCollectExport(method, "${prefix}_${cname}", returnType, classifier)
    addFlowKeyedSharedFlowExports("${prefix}_${cname}", method, returnType, classifier)
    addAwaitedStateFlowCollectionExports(method, "${prefix}_${cname}", returnType, classifier)
    // ADR-071 held-route amendment: an awaited `MutableStateFlow<T>` is written through the held
    // route's flow-keyed setter; the `_async` export above already hands the flow back by handle.
    if (method.awaitsSettableMutableStateFlow()) {
      val element: KSType? = returnType?.arguments?.firstOrNull()?.type?.resolve()?.expandAliases()
      addHeldStateFlowSetValueExport("${prefix}_${cname}", method, element)
      addHeldStateFlowCompareAndSetExport("${prefix}_${cname}", method, element)
    }
  }
}

private fun buildSuspendFunctionBody(
  // The whole call (`legacyInvocation`): positional, or ADR-164's `when (mask)` of named calls.
  call: String,
  paramPrelude: String,
  isUnit: Boolean,
  isNullable: Boolean,
  boxed: String,
  names: ForwardLegacyNames,
  installsMappedType: Boolean,
): String = buildString {
  appendMappedTypeInstall(installsMappedType)
  // ADR-128: the launch shape -- `reinterpret`, `launch(start = CoroutineStart.ATOMIC)` and the
  // three callback arms -- belongs to the runtime's `launchForCSharp`. This route still owns its
  // ad-hoc scope (the top-level route has no C#-owned scope to launch on), the call, and the mint:
  // the body's last expression is the handle the helper hands the success arm, or `null`.
  append(paramPrelude)
  val resultRefCode: String = resultRefExpression(isNullable, boxed)
  appendLine(
    "return launchForCSharp(CoroutineScope(Dispatchers.Default), ${names.callback}, " +
        "${names.userData}, ::nugetMappedType) {"
  )
  if (isUnit) {
    appendLine("  $call")
    appendLine("  null")
  } else {
    appendLine("  val result = $call")
    appendLine("  val resultRef = $resultRefCode")
    appendLine("  resultRef")
  }
  append("}")
}

private fun buildSuspendMethodBody(
  qualifiedName: String,
  // The whole call on `names.obj` (`legacyInvocation`).
  call: String,
  paramPrelude: String,
  isUnit: Boolean,
  isNullable: Boolean,
  boxed: String,
  names: ForwardLegacyNames,
  installsMappedType: Boolean,
): String = buildString {
  appendMappedTypeInstall(installsMappedType)
  appendLine("val ${names.obj} = handle.asStableRef<$qualifiedName>().get()")
  // ADR-128: same helper as the top-level route, launched on the C#-owned scope this route has
  // always used (the one `nuget_scope_cancel` cancels). ADR-114/ADR-122's prelude locals stay
  // *before* the helper call, so they are still evaluated on the caller's thread, before launch.
  appendLine("val ${names.scope} = ${names.scopeHandle}.asStableRef<CoroutineScope>().get()")
  append(paramPrelude)
  val resultRefCode: String = resultRefExpression(isNullable, boxed)
  appendLine("return launchForCSharp(${names.scope}, ${names.callback}, ${names.userData}, ::nugetMappedType) {")
  // `result` / `resultRef` need no minting: they are declared inside the launch lambda and their
  // initializers read the user's argument before the local is in scope.
  if (isUnit) {
    appendLine("  $call")
    appendLine("  null")
  } else {
    appendLine("  val result = $call")
    appendLine("  val resultRef = $resultRefCode")
    appendLine("  resultRef")
  }
  append("}")
}

/**
 * ADR-202: an awaited `StateFlow` is read through the runtime-owned `nuget_stateflow_collect`, so
 * the export installs the module classifier before it launches, on the caller's thread. Every
 * other result (a plain `Flow` included, which collects through its own generated `_collect`)
 * never reaches a runtime-owned route.
 */
private fun StringBuilder.appendMappedTypeInstall(installsMappedType: Boolean) {
  if (installsMappedType) appendLine(INSTALL_MODULE_MAPPED_TYPE)
}

/**
 * Issue #108: `StableRef.create` takes `T : Any`, so a nullable suspend result has to be tested
 * before it is pinned. Null travels as a null result pointer, which is the same wire shape the
 * property route already uses, and the C# side reads it back as `null` rather than as a wrapper
 * over `IntPtr.Zero`.
 */
private fun resultRefExpression(isNullable: Boolean, boxed: String): String =
  if (isNullable) "if (result == null) null else NugetHandles.retain($boxed)"
  else "NugetHandles.retain($boxed)"

/**
 * ADR-119: what the suspend export pins for the C# side to read. A collection result is projected
 * per element exactly as the ordinary route's `List` return is (`collectionResultProjection`: a
 * value class or enum component leaves as its wire value, everything else is boxed as-is), so the
 * `nuget_list_get` / `nuget_set_element_at` / `nuget_map_*_at` helpers see the same container
 * shape on both routes. Any other result stays the bare `result` the shipped route pins.
 */
private fun legacyBoxedResult(shape: ForwardLegacyReturnShape): String = when (shape) {
  is ForwardLegacyReturnShape.Marshalled -> collectionResultProjection("result", shape.type)
  // ROADMAP Phase 4 line 23 fold-in: an enum crosses by ordinal (ADR-080), which the C# half
  // reads with `FromHandle<int>` and casts. Inside `resultRefExpression`'s null test, so a
  // nullable enum is smart-cast by then.
  is ForwardLegacyReturnShape.Enum -> "result.ordinal"
  // ADR-201 amendment: the ADR-107 envelope, which the C# completion reads with `BuildException`.
  is ForwardLegacyReturnShape.Envelope -> "buildError(result, ::nugetMappedType)"
  // ADR-214: a boxed `T` is pinned as it is, which is what `FromHandle<T>` reads.
  is ForwardLegacyReturnShape.Boxed -> "result"
  // ADR-240: the innermost underlying on its ordinary-route result wire.
  is ForwardLegacyReturnShape.Wire -> shape.legacyWireBox("result")
  else -> "result"
}

/** ADR-214: a boxed `T` result whose null crosses as the null pointer. */
private fun ForwardLegacyReturnShape.isNullableBox(): Boolean =
  this is ForwardLegacyReturnShape.Boxed && nullable

/**
 * ADR-214: the member's own type arguments at the call (`<Any?>`), the plan route's
 * [ForwardMethodTypeParameter.kotlinTypeArgument]s, so a `T` only the return mentions resolves.
 */
internal fun KSFunctionDeclaration.legacyTypeArguments(): String {
  val arguments: List<String> = forwardMethodTypeParameters().map { it.kotlinTypeArgument }
  return if (arguments.isEmpty()) "" else arguments.joinToString(", ", "<", ">")
}

/** ADR-214: whether a boxed `T`'s checked cast names a generic bound (`Comparable<Any?>`). */
private fun List<ForwardLegacyParameterShape>.legacyCastsGenericBound(): Boolean =
  filterIsInstance<ForwardLegacyParameterShape.Boxed>()
    .any { shape -> shape.type.legacyBounds().any { bound -> '<' in bound } }

/**
 * The member's parameter names as the export declares its slots, the spelling
 * `legacyInvocation` / `legacyPrelude` read them under. The ADR-114 eager copy those emit before
 * `launch` is why the C# side may dispose a wire handle the moment this export returns.
 */
internal fun KSFunctionDeclaration.legacyParameterNames(): List<String> =
  parameters.map { parameter -> parameter.name?.asString() ?: "_" }

/**
 * [addParameters] with ADR-114's collection arm: the top-level suspend route spelled a collection
 * with its real Kotlin type (`toBridgeTypeName` keeps the type arguments), which compiles and then
 * hands C# an `IntPtr` no caller can produce. A handle is the shape both halves agree on.
 */
private fun FunSpec.Builder.addLegacySuspendParameters(
  func: KSFunctionDeclaration,
  shapes: List<ForwardLegacyParameterShape>,
  names: ForwardLegacyNames,
): FunSpec.Builder {
  func.parameters.forEachIndexed { index, param ->
    val name: String = param.name?.asString() ?: "_"
    // ADR-210: a suspend lambda's flat thunk and key slots, in the planner's order.
    names.asyncSlots[index]?.let { slots ->
      slots.declared.forEach { slot -> addParameter(slot, cOpaquePointer) }
      return@forEachIndexed
    }
    // ADR-240: a type riding its ordinary-route wire.
    val wire = shapes[index] as? ForwardLegacyParameterShape.Wire
    if (wire != null) {
      addLegacyWireParameter(name, wire, names.hasValueSlots[index])
      return@forEachIndexed
    }
    if (shapes[index].isLegacyLowered()) {
      // ADR-164 rule 2: an optional handle's leading `IsSet` slot.
      names.isSetSlots[index]?.let { isSet -> addParameter(isSet, BOOLEAN) }
      addParameter(name, cOpaquePointer.copy(nullable = shapes[index].isLegacyNullableSlot))
      return@forEachIndexed
    }
    addLegacyScalarParameter(
      name,
      shapes[index],
      param.type.resolve().expandAliases().toBridgeTypeName(nullable = false),
      names.hasValueSlots[index],
      names.isSetSlots[index],
    )
  }
  return this
}

/**
 * The ABI slots of one legacy-route parameter that is passed rather than lowered, [type] being its
 * non-null Kotlin spelling. Issue #299: a nullable primitive or `Char` takes the plan route's
 * has-value pair (`limitHasValue: Boolean, limit: Int`), a nullable `String` one `String?` slot.
 * [hasValue] is the member's [ForwardLegacyNames.hasValueSlots] entry for this parameter. Shared by
 * every legacy parameter builder so they cannot drift apart.
 */
internal fun FunSpec.Builder.addLegacyScalarParameter(
  name: String,
  shape: ForwardLegacyParameterShape,
  type: TypeName,
  hasValue: String?,
  // ADR-164 rule 2: the `Optional`'s leading presence slot, [ForwardLegacyNames.isSetSlots].
  isSet: String? = null,
): FunSpec.Builder {
  if (isSet != null) addParameter(isSet, BOOLEAN)
  if (hasValue != null) addParameter(hasValue, BOOLEAN)
  // ADR-201 amendment: a Throwable parameter crosses as its managed-exception text.
  if (shape is ForwardLegacyParameterShape.ManagedException) {
    return addParameter(name, STRING.copy(nullable = shape.nullable))
  }
  val nullable: Boolean = shape is ForwardLegacyParameterShape.NullableScalar && !shape.fansOut
  // An enum crosses as its ordinal (ADR-080), nullable or not.
  val enum: Boolean = shape is ForwardLegacyParameterShape.Enum ||
      (shape is ForwardLegacyParameterShape.NullableScalar && shape.type is BridgeType.Enum)
  return addParameter(name, (if (enum) INT else type).copy(nullable = nullable))
}

internal fun FileSpec.Builder.addAcquiredFlowCollectExport(
  method: KSDeclaration,
  prefix: String,
  returnType: KSType?,
  classifier: ForwardBridgeTypeClassifier,
) {
  if (returnType?.declaration?.qualifiedName?.asString() !in FLOW_TYPES) return
  val element: KSType = requireNotNull(returnType)
    .arguments.firstOrNull()?.type?.resolve()?.expandAliases()
    ?: error("Acquired Flow must declare an element type")
  val type = ClassName("kotlinx.coroutines.flow", "Flow").parameterizedBy(
    element.toBridgeTypeName(),
  )
  val collection: BridgeType.Collection? = classifier.legacyFlowElementCollection(returnType)
  val boxed: String = itemBoxExpr(
    element.isMarkedNullable, collection, classifier.legacyFlowElementEnvelope(returnType),
    classifier.legacyFlowElementWire(returnType),
  )
  addFunction(handleKeyedFlowCollectExport(prefix, ownedBy(method), type, boxed))
}

/**
 * The `${stem}_collect` export of a flow C# holds by its own handle: an acquired `Flow` (ADR-194),
 * an awaited `StateFlow` of a collection (ADR-068) and a flow type argument (ADR-208 part E).
 * [flowType] is the type the handle is read at and [boxed] the per-element projection
 * ([itemBoxExpr]). A null scope launches on an ad-hoc root scope, the top-level routes' shape.
 */
internal fun handleKeyedFlowCollectExport(
  stem: String,
  owner: ForwardExportOwnerTag,
  flowType: TypeName,
  boxed: String,
): FunSpec = FunSpec.builder("export_${stem}_collect")
  .addAnnotation(cNameAnnotation("${stem}_collect", owner))
  .addParameter("flowHandle", cOpaquePointer)
  .addParameter("scopeHandle", cOpaquePointer.copy(nullable = true))
  .addParameter("onNextPtr", cOpaquePointer)
  .addParameter("onCompletePtr", cOpaquePointer)
  .addParameter("onErrorPtr", cOpaquePointer)
  .addParameter("userData", cOpaquePointer)
  .returns(cOpaquePointer)
  .addCode("val flow = flowHandle.asStableRef<%T>().get()\n", flowType)
  .addCode(
    "val scope = scopeHandle?.asStableRef<CoroutineScope>()?.get() " +
      "?: CoroutineScope(Dispatchers.Default)\n",
  )
  .addCode(
    "return collectForCSharp(scope, onNextPtr, onCompletePtr, onErrorPtr, " +
      "userData, ::nugetMappedType) { emit ->\n" +
      "  flow.collect { value -> emit($boxed) }\n}\n",
  )
  .build()

/**
 * The `${stem}_value` sibling for a held `StateFlow`: its current value through the same [boxed]
 * projection. [nullable] is a nullable element, whose null value is a null handle.
 */
internal fun handleKeyedStateFlowValueExport(
  stem: String,
  owner: ForwardExportOwnerTag,
  flowType: TypeName,
  boxed: String,
  nullable: Boolean = false,
): FunSpec = FunSpec.builder("export_${stem}_value")
  .addAnnotation(cNameAnnotation("${stem}_value", owner))
  .addParameter("flowHandle", cOpaquePointer)
  .returns(cOpaquePointer.copy(nullable = nullable))
  .addCode("val value = flowHandle.asStableRef<%T>().get().value\n", flowType)
  .addCode("return $boxed\n")
  .build()

/**
 * ADR-068, collection element: an awaited read-only `StateFlow<List<T>>` (or `Set`/`Map`) reads
 * through its own `_value` / `_collect` pair keyed on the awaited flow handle, the seam ADR-202
 * gave the acquired `Flow` ([addAcquiredFlowCollectExport]). The runtime's shared
 * `nuget_stateflow_value` / `nuget_stateflow_collect` box the value unprojected, so a value-class
 * or enum component would reach C# as the wrong thing; this pair boxes it through the same
 * per-element projection the property and method routes use ([itemBoxExpr]). Only a collection
 * element gets the pair: every other element keeps the shared runtime pair, so no member that
 * bound before gains an export.
 */
private fun FileSpec.Builder.addAwaitedStateFlowCollectionExports(
  method: KSFunctionDeclaration,
  prefix: String,
  returnType: KSType?,
  classifier: ForwardBridgeTypeClassifier,
) {
  if (returnType?.declaration?.qualifiedName?.asString() !in STATE_FLOW_TYPES) return
  val collection: BridgeType.Collection? = classifier.legacyFlowElementCollection(returnType)
  // ADR-240: a wire element takes the same pair, boxed on its result wire; and an awaited
  // `MutableStateFlow` of a `ByteArray`, whose box is the bytes handle itself.
  val wire: ForwardLegacyFlowElementShape.Wire? = classifier.legacyFlowElementWire(returnType)
  val bytes: Boolean = classifier.legacyFlowElementShape(legacyFlowElement(returnType)) is
    ForwardLegacyFlowElementShape.Bytes &&
    returnType?.declaration?.qualifiedName?.asString() in MUTABLE_STATE_FLOW_TYPES
  if (collection == null && wire == null && !bytes) return
  val element: KSType = requireNotNull(returnType)
    .arguments.firstOrNull()?.type?.resolve()?.expandAliases()
    ?: error("Awaited StateFlow must declare an element type")
  val type: TypeName = ClassName("kotlinx.coroutines.flow", "StateFlow").parameterizedBy(
    element.toBridgeTypeName(),
  )
  val nullable: Boolean = wire != null && element.isMarkedNullable
  val boxed: String = itemBoxExpr(elementNullable = nullable, collection = collection, wire = wire)

  addFunction(handleKeyedStateFlowValueExport(prefix, ownedBy(method), type, boxed, nullable))
  addFunction(handleKeyedFlowCollectExport(prefix, ownedBy(method), type, boxed))
}

/**
 * ADR-240: the ABI slots of a [ForwardLegacyParameterShape.Wire] parameter, the ordinary route's
 * own for its planned wire: a has-value `BOOLEAN` before a nullable value type, then ticks as
 * `Long`, hex-dash text as `String`, a bytes, collection, class or interface handle as a pointer,
 * or the scalar itself.
 */
internal fun FunSpec.Builder.addLegacyWireParameter(
  name: String,
  shape: ForwardLegacyParameterShape.Wire,
  hasValue: String?,
): FunSpec.Builder {
  val planned: BridgeType = shape.planned
  val nullable: Boolean = planned is BridgeType.Nullable
  if (hasValue != null) addParameter(hasValue, BOOLEAN)
  val type: TypeName = when (val inner: BridgeType = planned.unwrapNullable()) {
    is BridgeType.Primitive -> ClassName("kotlin", inner.kind.simpleKotlinName())
    BridgeType.Instant, BridgeType.Duration -> ClassName("kotlin", "Long")
    is BridgeType.Enum -> INT
    BridgeType.String, BridgeType.Uuid -> STRING.copy(nullable = nullable)
    BridgeType.ByteArray, is BridgeType.Interface, is BridgeType.Collection,
    is BridgeType.ObjectHandle -> cOpaquePointer.copy(nullable = nullable)
    BridgeType.Unit,
    BridgeType.Char,
    is BridgeType.Throwable,
    is BridgeType.BoundInterface,
    is BridgeType.ValueClass,
    is BridgeType.Nullable,
    is BridgeType.Callback,
    is BridgeType.AsyncCallback,
    is BridgeType.FlowSource,
    is BridgeType.ReturnedLambda,
    is BridgeType.SpecializedProtocol,
    is BridgeType.RawKSType,
    is BridgeType.Unsupported,
    is BridgeType.RawCollection,
    is BridgeType.TypeParameter,
      -> error("ADR-240: no legacy wire slot for $planned")
  }
  return addParameter(name, type)
}

/** ADR-240: the has-value slot a fanned-out wire receiver [slot] leads with, or null. */
internal fun ForwardStaticSuspendReceiver.Wire.hasValueSlot(slot: String): String? =
  if (shape.fansOut) "${slot}HasValue" else null

/** ADR-240: the receiver's eager read into [local], before any coroutine launches. */
internal fun ForwardStaticSuspendReceiver.Wire.wirePrelude(slot: String, local: String): String =
  "val $local = ${legacyWireLowering(slot, shape.planned, hasValueSlot(slot))}"

/** ADR-240: the receiver's ABI slots, in order. */
internal fun ForwardStaticSuspendReceiver.Wire.wireParameters(slot: String): List<ParameterSpec> =
  FunSpec.builder("receiver").addLegacyWireParameter(slot, shape, hasValueSlot(slot)).build()
    .parameters
