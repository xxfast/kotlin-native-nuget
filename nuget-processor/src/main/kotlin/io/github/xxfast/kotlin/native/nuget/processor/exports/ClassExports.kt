package io.github.xxfast.kotlin.native.nuget.processor.exports

import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Visibility
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import io.github.xxfast.kotlin.native.nuget.processor.cir.LAMBDA_TYPES
import io.github.xxfast.kotlin.native.nuget.processor.cir.STATE_FLOW_TYPES
import io.github.xxfast.kotlin.native.nuget.processor.cir.SUSPEND_LAMBDA_TYPES
import io.github.xxfast.kotlin.native.nuget.processor.cir.expandAliases
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardBridgeTypeClassifier
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardCallablePlan
import io.github.xxfast.kotlin.native.nuget.processor.forward.legacyRefusedParameter
import io.github.xxfast.kotlin.native.nuget.processor.forward.legacyRefusedReturn
import io.github.xxfast.kotlin.native.nuget.processor.forward.forwardSuperClass
import io.github.xxfast.kotlin.native.nuget.processor.forward.isForwardMemberOf
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardCallablePlanCatalog
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardPropertyPlan
import io.github.xxfast.kotlin.native.nuget.processor.forward.addForwardKotlinPlanExport
import io.github.xxfast.kotlin.native.nuget.processor.forward.planFor
import io.github.xxfast.kotlin.native.nuget.processor.forward.addForwardPropertyPlanExports
import io.github.xxfast.kotlin.native.nuget.processor.forward.isOptInRefused

/**
 * ADR-064 amendment (2026-09-13): the legacy class Flow/StateFlow route's own selection gate,
 * hoisted so the route below and the planner's unrouted-position reclassification cannot drift.
 * Deliberately *only* the return-type test: the ADR-114/ADR-123 refusal filters that follow it in
 * [addClassExports] have their own named diagnostic (`warnRefusedLegacyRouteMembers`), so folding
 * them in here would double-report the same member.
 */
internal fun KSFunctionDeclaration.hasLegacyFlowReturn(): Boolean {
  val returnQualified: String? = returnType?.resolve()
    ?.expandAliases()?.declaration?.qualifiedName?.asString()
  return returnQualified == "kotlinx.coroutines.flow.Flow" || returnQualified in STATE_FLOW_TYPES
}

/**
 * ADR-064 amendment (2026-09-13): the legacy per-call lambda-parameter route's own gate (the
 * `allNonFlowMethods.partition` below), hoisted for the same reason as [hasLegacyFlowReturn]. A
 * lambda carried by a collection *element* (`List<(Int) -> Unit>`) is not a lambda parameter and
 * this says so.
 */
internal fun KSFunctionDeclaration.hasLegacyLambdaParameter(): Boolean = parameters.any { param ->
  param.type.resolve().expandAliases().declaration.qualifiedName?.asString() in LAMBDA_TYPES
}

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
  // ADR-114: the legacy flow route classifies its own parameters, so a collection crosses as a
  // handle and any other generic parameter is refused by name instead of emitting `kinds: List`.
  classifier: ForwardBridgeTypeClassifier,
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
      .addAnnotation(cNameAnnotation("${prefix}_dispose", ownedBy(cls, "generated Dispose")))
      .addParameter("handle", cOpaquePointer)
      .addStatement("%T.release(handle)", nugetHandles)
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
    // Issue #121: the planner declined, but a decline is not always an invitation. A marked
    // declaration must reach neither artifact, so the legacy arms below never run for one.
    if (prop.isOptInRefused(classifier.exportMarkers)) return@forEach
    // Named specialized-protocol property adapters (lambda / suspend-lambda / Flow).
    val propTypeResolved: KSType = prop.type.resolve().expandAliases()
    val propType: String = propTypeResolved.declaration.qualifiedName?.asString() ?: "Any"
    val isLambdaProperty: Boolean = propType in LAMBDA_TYPES || propType in SUSPEND_LAMBDA_TYPES
    if (isLambdaProperty) {
      // CIR ships lambda property getters without errorOut (hasSyncErrorOut = false).
      addFunction(
        FunSpec.builder("export_${prefix}_get_$propName")
          .addAnnotation(cNameAnnotation("${prefix}_get_$propName", ownedBy(prop)))
          .addParameter("handle", cOpaquePointer)
          .returns(cOpaquePointer.copy(nullable = true))
          .addStatement(
            "return %T.retain(handle.asStableRef<%L>().get().%L)",
            nugetHandles, qualifiedName, propName,
          )
          .build()
      )
      return@forEach
    }
    // ADR-124: the flow property route, one emitter shared with the sealed-arm loop in
    // `NugetProcessor` (`FlowExports.kt`). Membership stays the caller's rule, as it was.
    if (!propTypeResolved.isForwardFlowType()) return@forEach
    addFlowPropertyExports(prop, qualifiedName, prefix, classifier)
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
  val flowMethods: List<KSFunctionDeclaration> = allRegularMethods
    .filter { method -> method.hasLegacyFlowReturn() }
    // ADR-114: a generic parameter this route cannot marshal skips the member entirely rather
    // than emitting non-compiling Kotlin. `NugetProcessor` names it in a SKIPPED_UNSUPPORTED_INPUT.
    // ADR-123: likewise an element this route cannot marshal, named SKIPPED_UNSUPPORTED_RETURN.
    .filter { method -> classifier.legacyRefusedParameter(method.parameters) == null }
    .filter { method -> classifier.legacyRefusedReturn(method) == null }

  val allNonFlowMethods: List<KSFunctionDeclaration> = allRegularMethods
    .filterNot { method -> method.hasLegacyFlowReturn() }

  val (lambdaParamMethods, methods) = allNonFlowMethods.partition { method ->
    method.hasLegacyLambdaParameter()
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
    addFlowMethodExports(method, qualifiedName, prefix, classifier, callableCatalog)
  }

  if (cls.modifiers.contains(Modifier.DATA)) {
    addFunction(
      FunSpec.builder("export_${prefix}_equals")
        .addAnnotation(cNameAnnotation("${prefix}_equals", ownedBy(cls, "data-class equals")))
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
        .addAnnotation(cNameAnnotation("${prefix}_hashcode", ownedBy(cls, "data-class hashCode")))
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
        .addAnnotation(cNameAnnotation("${prefix}_tostring", ownedBy(cls, "data-class toString")))
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
