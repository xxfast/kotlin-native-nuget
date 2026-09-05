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
import io.github.xxfast.kotlin.native.nuget.processor.cir.LAMBDA_TYPES
import io.github.xxfast.kotlin.native.nuget.processor.cir.MUTABLE_STATE_FLOW_TYPES
import io.github.xxfast.kotlin.native.nuget.processor.cir.STATE_FLOW_TYPES
import io.github.xxfast.kotlin.native.nuget.processor.cir.SUSPEND_LAMBDA_TYPES
import io.github.xxfast.kotlin.native.nuget.processor.cir.expandAliases
import io.github.xxfast.kotlin.native.nuget.processor.cir.isMutableStateFlowElementObject
import io.github.xxfast.kotlin.native.nuget.processor.cir.isMutableStateFlowElementSupported
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardCallablePlan
import io.github.xxfast.kotlin.native.nuget.processor.forward.forwardSuperClass
import io.github.xxfast.kotlin.native.nuget.processor.forward.isForwardMemberOf
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardCallablePlanCatalog
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardPropertyPlan
import io.github.xxfast.kotlin.native.nuget.processor.forward.addForwardKotlinPlanExport
import io.github.xxfast.kotlin.native.nuget.processor.forward.planFor
import io.github.xxfast.kotlin.native.nuget.processor.forward.addForwardPropertyPlanExports
import io.github.xxfast.kotlin.native.nuget.processor.toCName

/**
 * Generates @CName bridge exports for classes: dispose, planned constructors/properties/methods,
 * and named specialized-protocol adapters (Flow, lambda, stored callback, interface bridge).
 * Ordinary synchronous members without a plan are skipped — no IntPtr/defaultValueFor fallthrough.
 *
 * @see <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/003-memory-management-across-bridge.md">ADR-003: Memory management</a>
 * @see <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/005-object-return-semantics.md">ADR-005: Object return semantics</a>
 * @see <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/008-data-class-mapping.md">ADR-008: Data class mapping</a>
 */
internal fun FileSpec.Builder.addClassExports(
  cls: KSClassDeclaration,
  callableCatalog: ForwardCallablePlanCatalog,
  // ADR-101 amendment: the export set, so this emitter asks the *gated* has-superclass predicate
  // the planners ask. An unexported base is base-less here too, so the base's concrete members
  // are emitted with this class as receiver instead of being left to a C# base that never exists.
  exportedTypes: Set<String>,
) {
  val name: String = cls.simpleName.asString()
  val qualifiedName: String = cls.qualifiedName?.asString() ?: return
  val prefix: String = name.lowercase()
  val isAbstract: Boolean = cls.modifiers.contains(Modifier.ABSTRACT)

  // The shared has-superclass predicate (`ForwardClassMembership.kt`), so this emitter keeps
  // exactly the member set the planner planned: a defaulted interface member the class does not
  // override is bound here too, and the ABI contract check is what would catch any drift.
  val superClass: KSClassDeclaration? = cls.forwardSuperClass(exportedTypes)

  // ADR-091: constructors come off the catalog rather than a `getConstructors()` walk, because the
  // planner also synthesizes trailing-default omitting overloads that no declaration walk can see.
  // Truncated plans need no emitter support: the wrapper's call is built from the plan's
  // parameters, so Kotlin supplies the omitted defaults.
  callableCatalog.constructors(qualifiedName).forEach { plan -> addForwardKotlinPlanExport(plan) }

  addFunction(
    FunSpec.builder("export_${prefix}_dispose")
      .addAnnotation(cNameAnnotation("${prefix}_dispose"))
      .addParameter("handle", cOpaquePointer)
      .addStatement("handle.asStableRef<%L>().dispose()", qualifiedName)
      .build()
  )

  val properties: List<KSPropertyDeclaration> = cls.getAllProperties()
    .filter { it.getVisibility() == Visibility.PUBLIC }
    .filter { prop -> prop.isForwardMemberOf(cls, superClass) }
    .toList()

  properties.forEach { prop ->
    val propName: String = prop.simpleName.asString()
    val planned: ForwardPropertyPlan? = callableCatalog.propertyFor("$qualifiedName.$propName")
    if (planned != null) {
      addForwardPropertyPlanExports(planned)
      return@forEach
    }
    // Named specialized-protocol property adapters (lambda / suspend-lambda / Flow).
    val propTypeResolved: KSType = prop.type.resolve().expandAliases()
    val propType: String = propTypeResolved.declaration.qualifiedName?.asString() ?: "Any"
    val isLambdaProperty: Boolean = propType in LAMBDA_TYPES || propType in SUSPEND_LAMBDA_TYPES
    if (isLambdaProperty) {
      // CIR ships lambda property getters without errorOut (hasSyncErrorOut = false).
      addFunction(
        FunSpec.builder("export_${prefix}_get_$propName")
          .addAnnotation(cNameAnnotation("${prefix}_get_$propName"))
          .addParameter("handle", cOpaquePointer)
          .returns(cOpaquePointer.copy(nullable = true))
          .addStatement(
            "return %T.create(handle.asStableRef<%L>().get().%L).asCPointer()",
            stableRef, qualifiedName, propName,
          )
          .build()
      )
      return@forEach
    }
    // ADR-065: StateFlow (and the read-only MutableStateFlow view) is checked before/alongside
    // plain Flow. The `_collect` export is byte-for-byte the same shape for both (StateFlow's
    // `collect` is inherited from Flow); StateFlow additionally gets a synchronous `_value` export.
    val isStateFlowProperty: Boolean = propType in STATE_FLOW_TYPES
    val isFlowProperty: Boolean = propType == "kotlinx.coroutines.flow.Flow"
    if (!isFlowProperty && !isStateFlowProperty) return@forEach

    val flowElementType: KSType? = propTypeResolved.arguments.firstOrNull()?.type?.resolve()
    val flowElementQualified: String =
      flowElementType?.declaration?.qualifiedName?.asString() ?: "kotlin.Any"
    // ADR-067: nullable element/member threading is StateFlow-only; nullable Flow stays deferred.
    val elementNullable: Boolean = isStateFlowProperty && flowElementType?.isMarkedNullable == true
    val memberNullable: Boolean = isStateFlowProperty && propTypeResolved.isMarkedNullable

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
              qualifiedName, propName, elementNullable, memberNullable,
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
              cOpaquePointerVar, stableRef,
            )
            .build()
        )
      }
    }
  }

  val allRegularMethods: List<KSFunctionDeclaration> = cls.getAllFunctions()
    .filter { it.getVisibility() == Visibility.PUBLIC }
    .filter {
      val methodName: String = it.simpleName.asString()
      val isDataClassMethod: Boolean = cls.modifiers.contains(Modifier.DATA) &&
          (methodName == "copy" || methodName.startsWith("component"))
      methodName !in listOf("equals", "hashCode", "toString", "<init>") && !isDataClassMethod
    }
    .filter { !it.modifiers.contains(Modifier.SUSPEND) }
    .filter { method ->
      method.isForwardMemberOf(cls, superClass) && !method.modifiers.contains(Modifier.ABSTRACT)
    }
    .toList()

  // ADR-065: StateFlow-returning methods route through the same `_collect` shape as plain-Flow
  // methods, plus a sibling synchronous `_value` export (see the flowMethods.forEach loop below).
  val flowMethods: List<KSFunctionDeclaration> = allRegularMethods.filter { method ->
    val returnQualified: String? = method.returnType?.resolve()
      ?.expandAliases()?.declaration?.qualifiedName?.asString()
    returnQualified == "kotlinx.coroutines.flow.Flow" || returnQualified in STATE_FLOW_TYPES
  }

  val allNonFlowMethods: List<KSFunctionDeclaration> = allRegularMethods.filter { method ->
    val returnQualified: String? = method.returnType?.resolve()
      ?.expandAliases()?.declaration?.qualifiedName?.asString()
    returnQualified != "kotlinx.coroutines.flow.Flow" && returnQualified !in STATE_FLOW_TYPES
  }

  val (lambdaParamMethods, methods) = allNonFlowMethods.partition { method ->
    method.parameters.any { param ->
      param.type.resolve().expandAliases().declaration.qualifiedName?.asString() in LAMBDA_TYPES
    }
  }

  val storedCallbackPairs: List<Pair<KSFunctionDeclaration, KSFunctionDeclaration>> =
    findStoredCallbackPairs(lambdaParamMethods)
  val storedCallbackAddMethods: Set<KSFunctionDeclaration> = storedCallbackPairs
    .map { it.first }.toSet()
  val storedCallbackRemoveMethods: Set<KSFunctionDeclaration> = storedCallbackPairs
    .map { it.second }.toSet()

  storedCallbackPairs.forEach { (addMethod, removeMethod) ->
    addStoredCallbackExports(addMethod, removeMethod, qualifiedName, prefix)
  }

  lambdaParamMethods.forEach { method ->
    if (method in storedCallbackAddMethods || method in storedCallbackRemoveMethods) return@forEach
    addLambdaParamMethodExport(method, qualifiedName, prefix)
  }

  val interfaceBridgePairs: List<Pair<KSFunctionDeclaration, KSFunctionDeclaration>> =
    findInterfaceBridgePairs(methods)
  interfaceBridgePairs.forEach { (addMethod, removeMethod) ->
    addInterfaceBridgeExports(addMethod, removeMethod, qualifiedName, prefix)
  }

  // ADR-090: member plans come off the catalog, not from a per-declaration plan lookup. Overload
  // numbering lives in the planner, so `"$qualifiedName.$methodName"` is no longer a plan key for
  // anything past the first same-name declaration (and re-emitted the first one's plan). Flow,
  // lambda-parameter, stored-callback and interface-bridge members never produce a CLASS plan
  // (the planner skips them), so catalog iteration cannot double-emit the routes above.
  callableCatalog.classMethods(qualifiedName).forEach { plan -> addForwardKotlinPlanExport(plan) }

  flowMethods.forEach { method ->
    val methodName: String = method.simpleName.asString()
    val cname: String = toCName(methodName)
    val returnType: KSType? = method.returnType?.resolve()?.expandAliases()
    val returnQualified: String? = returnType?.declaration?.qualifiedName?.asString()
    val isStateFlowMethod: Boolean = returnQualified in STATE_FLOW_TYPES
    val flowElementType: KSType? = returnType?.arguments?.firstOrNull()?.type?.resolve()
    val flowElementQualified: String =
      flowElementType?.declaration?.qualifiedName?.asString() ?: "kotlin.Any"
    // ADR-067: nullable element/member threading is StateFlow-only; nullable Flow stays deferred.
    val elementNullable: Boolean = isStateFlowMethod && flowElementType?.isMarkedNullable == true
    val memberNullable: Boolean = isStateFlowMethod && returnType?.isMarkedNullable == true

    val paramCall: String = method.parameters
      .joinToString(", ") { it.name?.asString() ?: "_" }

    val builder: FunSpec.Builder = FunSpec
      .builder("export_${prefix}_${cname}_collect")
      .addAnnotation(cNameAnnotation("${prefix}_${cname}_collect"))
      .addParameter("handle", cOpaquePointer)
      .addParameter("scopeHandle", cOpaquePointer)

    fun FunSpec.Builder.addFlowParameters() {
      method.parameters.forEach { param ->
        val resolved: KSType = param.type.resolve().expandAliases()
        val type: String = resolved.declaration.qualifiedName?.asString()
          ?: resolved.declaration.simpleName.asString()
        addParameter(param.name?.asString() ?: "_", ClassName.bestGuess(type))
      }
    }

    builder.addFlowParameters()

    builder
      .addParameter("onNextPtr", cOpaquePointer)
      .addParameter("onCompletePtr", cOpaquePointer)
      .addParameter("onErrorPtr", cOpaquePointer)
      .addParameter("userData", cOpaquePointer)
      .returns(cOpaquePointer)
      .addCode(
        buildFlowMethodCollectBody(
          qualifiedName, methodName, paramCall, flowElementQualified,
          elementNullable, memberNullable,
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
            qualifiedName, methodName, paramCall, elementNullable, memberNullable,
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
          .addCode(buildStateFlowHasValueMethodBody(qualifiedName, methodName, paramCall))

        addFunction(hasValueBuilder.build())
      }

      // ADR-071: a genuinely DECLARED MutableStateFlow<T> function return additionally gains a
      // settable `.Value`, gated on non-nullable element/member (both deferred) and a
      // v1-supported element (primitive/String/object; enum stays deferred).
      val isMutableStateFlowMethod: Boolean = returnQualified in MUTABLE_STATE_FLOW_TYPES &&
          !elementNullable && !memberNullable &&
          isMutableStateFlowElementSupported(flowElementType)
      if (isMutableStateFlowMethod) {
        val (valueParamType: TypeName, assignment: String) =
          mutableStateFlowValueParameter(flowElementType)
        val setValueBuilder: FunSpec.Builder = FunSpec
          .builder("export_${prefix}_${cname}_set_value")
          .addAnnotation(cNameAnnotation("${prefix}_${cname}_set_value"))
          .addParameter("handle", cOpaquePointer)

        setValueBuilder.addFlowParameters()

        setValueBuilder
          .addParameter("value", valueParamType)
          .addParameter("errorOut", cOpaquePointer.copy(nullable = true))
          .addCode(
            buildStateFlowSetValueMethodBody(qualifiedName, methodName, paramCall, assignment),
            cOpaquePointerVar, stableRef,
          )

        addFunction(setValueBuilder.build())
      }
    }
  }

  if (cls.modifiers.contains(Modifier.DATA)) {
    addFunction(
      FunSpec.builder("export_${prefix}_equals")
        .addAnnotation(cNameAnnotation("${prefix}_equals"))
        .addParameter("handle", cOpaquePointer)
        .addParameter("other", cOpaquePointer)
        .returns(Boolean::class)
        .addStatement(
          "return handle.asStableRef<%L>().get() == other.asStableRef<%L>().get()",
          qualifiedName, qualifiedName,
        )
        .build()
    )

    addFunction(
      FunSpec.builder("export_${prefix}_hashcode")
        .addAnnotation(cNameAnnotation("${prefix}_hashcode"))
        .addParameter("handle", cOpaquePointer)
        .returns(Int::class)
        .addStatement(
          "return handle.asStableRef<%L>().get().hashCode()",
          qualifiedName,
        )
        .build()
    )

    addFunction(
      FunSpec.builder("export_${prefix}_tostring")
        .addAnnotation(cNameAnnotation("${prefix}_tostring"))
        .addParameter("handle", cOpaquePointer)
        .returns(String::class)
        .addStatement(
          "return handle.asStableRef<%L>().get().toString()",
          qualifiedName,
        )
        .build()
    )

    val planned: ForwardCallablePlan? = callableCatalog.planFor("$qualifiedName.copy")
    if (planned != null) addForwardKotlinPlanExport(planned)
  }
}

internal fun FileSpec.Builder.addCompanionExports(
  cls: KSClassDeclaration,
  callableCatalog: ForwardCallablePlanCatalog,
) {
  val qualifiedName: String = cls.qualifiedName?.asString() ?: return

  val companion: KSClassDeclaration = cls.declarations
    .filterIsInstance<KSClassDeclaration>()
    .firstOrNull { it.isCompanionObject } ?: return

  // ADR-095: companion members come off the catalog — per-companion overload numbering makes the
  // symbol underivable from a `getAllFunctions()` entry (see `addObjectExports`).
  callableCatalog.companionMethods(qualifiedName).forEach { plan ->
    addForwardKotlinPlanExport(plan)
  }

  companion.getAllProperties()
    .filter { it.getVisibility() == Visibility.PUBLIC }
    .filter { !it.modifiers.contains(Modifier.CONST) }
    .forEach { prop ->
      val planned: ForwardPropertyPlan? =
        callableCatalog.propertyFor("$qualifiedName.Companion.${prop.simpleName.asString()}")
      if (planned != null) addForwardPropertyPlanExports(planned)
    }
}

// ADR-067: `?` on the member access when the whole StateFlow member/return can be null (a
// defensive guard -- the C# side only reaches this after its `_has_value` probe is true, but a
// race should not crash the coroutine); plain `.` (unchanged ADR-065 shape) otherwise.
private fun memberAccessor(receiver: String, memberNullable: Boolean): String =
  if (memberNullable) "$receiver?" else receiver

// ADR-067: the collected/read item expression -- a null-guarded box when the element itself is
// nullable (`StateFlow<T?>`), else the original unguarded `value as Any` box (ADR-065 unchanged).
private fun itemBoxExpr(elementNullable: Boolean): String =
  if (elementNullable) "if (value != null) StableRef.create(value).asCPointer() else null"
  else "StableRef.create(value as Any).asCPointer()"

private fun buildFlowCollectBody(
  qualifiedName: String,
  propName: String,
  flowElementQualified: String,
  elementNullable: Boolean = false,
  memberNullable: Boolean = false,
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
  appendLine("      val itemRef = ${itemBoxExpr(elementNullable)}")
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

private fun buildFlowMethodCollectBody(
  qualifiedName: String,
  methodName: String,
  paramCall: String,
  flowElementQualified: String,
  elementNullable: Boolean = false,
  memberNullable: Boolean = false,
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
  appendLine(
    "    obj.${memberAccessor("$methodName($paramCall)", memberNullable)}.collect { value ->",
  )
  appendLine("      val itemRef = ${itemBoxExpr(elementNullable)}")
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

// ADR-065: the `_value` export body -- boxes `stateFlow.value as Any` into a StableRef, byte-for-
// byte the same shape as a single onNext emission above. No errorOut: StateFlow.value cannot throw.
// ADR-067: a nullable element or nullable member instead reads the value into a local and only
// boxes it when non-null, returning `null` (a widened `COpaquePointer?`) otherwise.
private fun buildStateFlowValuePropertyBody(
  qualifiedName: String,
  propName: String,
  elementNullable: Boolean = false,
  memberNullable: Boolean = false,
): String = buildString {
  appendLine("val obj = handle.asStableRef<$qualifiedName>().get()")
  if (!elementNullable && !memberNullable) {
    append("return StableRef.create(obj.$propName.value as Any).asCPointer()")
  } else {
    appendLine("val v = obj.${memberAccessor(propName, memberNullable)}.value")
    append("return if (v != null) StableRef.create(v).asCPointer() else null")
  }
}

private fun buildStateFlowValueMethodBody(
  qualifiedName: String,
  methodName: String,
  paramCall: String,
  elementNullable: Boolean = false,
  memberNullable: Boolean = false,
): String = buildString {
  appendLine("val obj = handle.asStableRef<$qualifiedName>().get()")
  if (!elementNullable && !memberNullable) {
    append("return StableRef.create(obj.$methodName($paramCall).value as Any).asCPointer()")
  } else {
    appendLine("val v = obj.${memberAccessor("$methodName($paramCall)", memberNullable)}.value")
    append("return if (v != null) StableRef.create(v).asCPointer() else null")
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
): String = buildString {
  appendLine("val obj = handle.asStableRef<$qualifiedName>().get()")
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
  appendLine("    errorOut.reinterpret<%T>().pointed.value = %T.create(")
  appendLine("      buildError(e)")
  appendLine("    ).asCPointer()")
  appendLine("  }")
  append("}")
}

private fun buildStateFlowSetValueMethodBody(
  qualifiedName: String,
  methodName: String,
  paramCall: String,
  assignment: String,
): String = buildString {
  appendLine("try {")
  appendLine(
    "  handle.asStableRef<$qualifiedName>().get().$methodName($paramCall).value = " +
        "$assignment"
  )
  appendLine("} catch (e: Throwable) {")
  appendLine("  if (errorOut != null) {")
  appendLine("    errorOut.reinterpret<%T>().pointed.value = %T.create(")
  appendLine("      buildError(e)")
  appendLine("    ).asCPointer()")
  appendLine("  }")
  append("}")
}
