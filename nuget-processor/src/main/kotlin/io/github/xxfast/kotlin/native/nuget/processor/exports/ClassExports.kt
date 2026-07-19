package io.github.xxfast.kotlin.native.nuget.processor.exports

import com.google.devtools.ksp.getConstructors
import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Visibility
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import io.github.xxfast.kotlin.native.nuget.processor.cir.LAMBDA_TYPES
import io.github.xxfast.kotlin.native.nuget.processor.cir.STATE_FLOW_TYPES
import io.github.xxfast.kotlin.native.nuget.processor.cir.SUSPEND_LAMBDA_TYPES
import io.github.xxfast.kotlin.native.nuget.processor.cir.expandAliases
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardCallablePlan
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
) {
  val name: String = cls.simpleName.asString()
  val qualifiedName: String = cls.qualifiedName?.asString() ?: return
  val prefix: String = name.lowercase()
  val isAbstract: Boolean = cls.modifiers.contains(Modifier.ABSTRACT)

  val hasSuperClass: Boolean = cls.superTypes
    .map { it.resolve().declaration }
    .filterIsInstance<KSClassDeclaration>()
    .any { it.classKind == ClassKind.CLASS && it.qualifiedName?.asString() != "kotlin.Any" }

  val constructor: KSFunctionDeclaration? = cls.primaryConstructor

  if (constructor != null && !isAbstract) {
    val planned: ForwardCallablePlan? = callableCatalog.planFor("$qualifiedName.<init>")
    if (planned != null) addForwardKotlinPlanExport(planned)
  }

  if (!isAbstract) {
    val secondaryConstructors: List<KSFunctionDeclaration> = cls.getConstructors()
      .filter { it != cls.primaryConstructor }
      .filter { it.getVisibility() == Visibility.PUBLIC }
      .toList()

    secondaryConstructors.forEachIndexed { index, _ ->
      val planned: ForwardCallablePlan? =
        callableCatalog.planFor("$qualifiedName.<init>_${index + 2}")
      if (planned != null) addForwardKotlinPlanExport(planned)
    }
  }

  addFunction(
    FunSpec.builder("export_${prefix}_dispose")
      .addAnnotation(cNameAnnotation("${prefix}_dispose"))
      .addParameter("handle", cOpaquePointer)
      .addStatement("handle.asStableRef<%L>().dispose()", qualifiedName)
      .build()
  )

  val properties: List<KSPropertyDeclaration> = cls.getAllProperties()
    .filter { it.getVisibility() == Visibility.PUBLIC }
    .filter { prop ->
      if (!hasSuperClass) return@filter true
      prop.parentDeclaration == cls
    }
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
        .addCode(buildFlowCollectBody(qualifiedName, propName, flowElementQualified))
        .build()
    )

    if (isStateFlowProperty) {
      // ADR-065: synchronous `_value` export -- boxes `stateFlow.value as Any` into a StableRef,
      // structurally identical to a single onNext emission. No errorOut: StateFlow.value cannot
      // throw (a deliberate narrowing of ADR-030's wrap-all-property-getters policy).
      addFunction(
        FunSpec.builder("export_${prefix}_get_${propName}_value")
          .addAnnotation(cNameAnnotation("${prefix}_get_${propName}_value"))
          .addParameter("handle", cOpaquePointer)
          .returns(cOpaquePointer)
          .addCode(buildStateFlowValuePropertyBody(qualifiedName, propName))
          .build()
      )
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
      if (hasSuperClass) {
        method.parentDeclaration == cls && !method.modifiers.contains(Modifier.ABSTRACT)
      } else {
        val declaredInThisClass: Boolean = method.parentDeclaration == cls
        declaredInThisClass && !method.modifiers.contains(Modifier.ABSTRACT)
      }
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
  val interfaceBridgeExcluded: Set<KSFunctionDeclaration> =
    (interfaceBridgePairs.map { it.first } + interfaceBridgePairs.map { it.second }).toSet()

  interfaceBridgePairs.forEach { (addMethod, removeMethod) ->
    addInterfaceBridgeExports(addMethod, removeMethod, qualifiedName, prefix)
  }

  methods.forEach { method ->
    if (method in interfaceBridgeExcluded) return@forEach
    val methodName: String = method.simpleName.asString()
    val planned: ForwardCallablePlan? = callableCatalog.planFor("$qualifiedName.$methodName")
    if (planned != null) addForwardKotlinPlanExport(planned)
  }

  flowMethods.forEach { method ->
    val methodName: String = method.simpleName.asString()
    val cname: String = toCName(methodName)
    val returnType: KSType? = method.returnType?.resolve()?.expandAliases()
    val returnQualified: String? = returnType?.declaration?.qualifiedName?.asString()
    val isStateFlowMethod: Boolean = returnQualified in STATE_FLOW_TYPES
    val flowElementType: KSType? = returnType?.arguments?.firstOrNull()?.type?.resolve()
    val flowElementQualified: String =
      flowElementType?.declaration?.qualifiedName?.asString() ?: "kotlin.Any"

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
        )
      )

    addFunction(builder.build())

    if (isStateFlowMethod) {
      // ADR-065: sibling synchronous `_value` export -- handle + the method's own parameters,
      // no scope/callbacks/errorOut (StateFlow.value cannot throw).
      val valueBuilder: FunSpec.Builder = FunSpec
        .builder("export_${prefix}_${cname}_value")
        .addAnnotation(cNameAnnotation("${prefix}_${cname}_value"))
        .addParameter("handle", cOpaquePointer)

      valueBuilder.addFlowParameters()

      valueBuilder
        .returns(cOpaquePointer)
        .addCode(buildStateFlowValueMethodBody(qualifiedName, methodName, paramCall))

      addFunction(valueBuilder.build())
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

  companion.getAllFunctions()
    .filter { it.getVisibility() == Visibility.PUBLIC }
    .filter { it.simpleName.asString() !in listOf("equals", "hashCode", "toString", "<init>") }
    .forEach { method ->
      val planned: ForwardCallablePlan? =
        callableCatalog.planFor("$qualifiedName.Companion.${method.simpleName.asString()}")
      if (planned != null) addForwardKotlinPlanExport(planned)
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

private fun buildFlowCollectBody(
  qualifiedName: String,
  propName: String,
  flowElementQualified: String,
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
  appendLine("    obj.$propName.collect { value ->")
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

private fun buildFlowMethodCollectBody(
  qualifiedName: String,
  methodName: String,
  paramCall: String,
  flowElementQualified: String,
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
  appendLine("    obj.$methodName($paramCall).collect { value ->")
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

// ADR-065: the `_value` export body -- boxes `stateFlow.value as Any` into a StableRef, byte-for-
// byte the same shape as a single onNext emission above. No errorOut: StateFlow.value cannot throw.
private fun buildStateFlowValuePropertyBody(
  qualifiedName: String,
  propName: String,
): String = buildString {
  appendLine("val obj = handle.asStableRef<$qualifiedName>().get()")
  append("return StableRef.create(obj.$propName.value as Any).asCPointer()")
}

private fun buildStateFlowValueMethodBody(
  qualifiedName: String,
  methodName: String,
  paramCall: String,
): String = buildString {
  appendLine("val obj = handle.asStableRef<$qualifiedName>().get()")
  append("return StableRef.create(obj.$methodName($paramCall).value as Any).asCPointer()")
}
