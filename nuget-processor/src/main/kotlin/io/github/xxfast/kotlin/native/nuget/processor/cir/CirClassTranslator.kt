package io.github.xxfast.kotlin.native.nuget.processor.cir

import com.google.devtools.ksp.getConstructors
import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Variance
import com.google.devtools.ksp.symbol.Visibility
import io.github.xxfast.kotlin.native.nuget.processor.exports.findInterfaceBridgePairs
import io.github.xxfast.kotlin.native.nuget.processor.exports.findStoredCallbackPairs
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardCallablePlan
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardCallablePlanCatalog
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardCirPlanProjection
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardCirPropertyProjection
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnostic
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticSink
import io.github.xxfast.kotlin.native.nuget.processor.forward.planFor
import io.github.xxfast.kotlin.native.nuget.processor.toCName

internal fun translateClass(
  cls: KSClassDeclaration,
  libraryName: String,
  tracker: CollectionHelperTracker,
  exportedTypes: Set<String>,
  logger: KSPLogger,
  callableCatalog: ForwardCallablePlanCatalog,
  context: NugetContext,
): CirClass {
  val name: String = cls.simpleName.asString()
  val prefix: String = name.lowercase()
  val isDataClass: Boolean = cls.modifiers.contains(Modifier.DATA)
  val isAbstract: Boolean = cls.modifiers.contains(Modifier.ABSTRACT)

  val superClass: String? = cls.superTypes
    .map { it.resolve().declaration }
    .filterIsInstance<KSClassDeclaration>()
    .firstOrNull { decl ->
      decl.classKind == ClassKind.CLASS &&
          decl.qualifiedName?.asString() != "kotlin.Any"
    }
    ?.simpleName?.asString()

  val interfaces: List<String> = if (superClass != null) {
    emptyList()
  } else {
    cls.superTypes
      .map { it.resolve().declaration }
      .filterIsInstance<KSClassDeclaration>()
      .filter { it.classKind == ClassKind.INTERFACE }
      .map { "I${it.simpleName.asString()}" }
      .toList()
  }

  val constructor = cls.primaryConstructor
  val cirConstructor: CirConstructor? = if (constructor != null && !isAbstract) {
    val planned = callableCatalog.planFor("${cls.qualifiedName?.asString() ?: name}.<init>")
    if (planned != null) {
      tracker.trackPlan(planned)
      ForwardCirPlanProjection.constructor(planned)
    } else {
      null // ordinary constructor without a plan: no IntPtr fallthrough
    }
  } else null

  // Secondary constructors (ADR-034). Entry points start at _create_2 so they
  // never collide with the primary's _create.
  val secondaryConstructors: List<CirConstructor> = if (isAbstract) emptyList() else cls
    .getConstructors()
    .filter { it != cls.primaryConstructor }
    .filter { it.getVisibility() == Visibility.PUBLIC }
    .toList()
    .mapIndexedNotNull { index, _ ->
      val suffix = "_${index + 2}"
      val planned = callableCatalog.planFor("${cls.qualifiedName?.asString() ?: name}.<init>$suffix")
      if (planned != null) {
        tracker.trackPlan(planned)
        ForwardCirPlanProjection.constructor(planned, suffix)
      } else {
        null
      }
    }

  // C has no overloading and C# cannot declare two constructors with identical
  // parameter types — fail fast rather than emit uncompilable C# (ADR-034).
  val constructorSignatures: List<List<String>> =
    (listOfNotNull(cirConstructor) + secondaryConstructors).map { ctor ->
      ctor.parameters.map { it.type }
    }
  if (constructorSignatures.size != constructorSignatures.toSet().size) {
    ForwardDiagnosticSink.emit(
      listOf(
        ForwardDiagnostic(
          kind = ForwardDiagnosticKind.ERROR_CSHARP_SIGNATURE_COLLISION,
          symbol = cls,
          declaration = "$name.<init>",
          reason = "two or more constructors render identical C# parameter types; C# cannot " +
              "declare two constructors with the same signature (ADR-034)",
          hint = "rename or remove the duplicate constructor, or change one parameter's type " +
              "so the rendered C# signatures differ",
        ),
      ),
      logger,
    )
  }

  val properties: List<CirProperty> = cls.getAllProperties()
    .filter { it.getVisibility() == Visibility.PUBLIC }
    .filter { prop ->
      if (superClass == null) return@filter true
      prop.parentDeclaration == cls
    }
    .mapNotNull { prop ->
      val propName: String = prop.simpleName.asString()
      val planned = callableCatalog.propertyFor("${cls.qualifiedName?.asString() ?: name}.$propName")
      if (planned != null) {
        tracker.trackProperty(planned)
        return@mapNotNull ForwardCirPropertyProjection.classProperty(planned)
      }
      // Named specialized-protocol property adapters only (lambda / suspend-lambda / Flow).
      // Ordinary property types without a plan are skipped — no mapReturnType IntPtr fallthrough.
      val propTypeResolved: KSType = prop.type.resolve().expandAliases()
      val csPropName: String = propName.replaceFirstChar { it.uppercase() }
      val qualifiedTypeName: String? = propTypeResolved.declaration.qualifiedName?.asString()

      val isLambdaType: Boolean = qualifiedTypeName in LAMBDA_TYPES
      val lambdaArity: Int = if (isLambdaType) propTypeResolved.arguments.size - 1 else -1
      if (isLambdaType) tracker.lambdaArities.add(lambdaArity)

      val isSuspendLambdaType: Boolean = qualifiedTypeName in SUSPEND_LAMBDA_TYPES
      val suspendLambdaArity: Int =
        if (isSuspendLambdaType) propTypeResolved.arguments.size - 1 else -1
      if (isSuspendLambdaType) {
        tracker.suspendLambdaArities.add(suspendLambdaArity)
        tracker.needsAsync = true
      }

      // ADR-065: StateFlow (and the read-only MutableStateFlow view) is checked BEFORE FLOW_TYPES
      // -- it is-a Flow, so an isAssignableFrom-style check would make it match the plain-Flow
      // branch and silently lose `.Value`. Detection is on the exact declared qualifiedName.
      val isStateFlowType: Boolean = qualifiedTypeName in STATE_FLOW_TYPES
      val isFlowType: Boolean = !isStateFlowType && qualifiedTypeName in FLOW_TYPES
      val flowElementTypeResolved: KSType? = if (isFlowType || isStateFlowType) {
        propTypeResolved.arguments.firstOrNull()?.type?.resolve()
      } else null
      // ADR-067: nullable element (`StateFlow<T?>`) and nullable member (`StateFlow<T>?`) are only
      // threaded for StateFlow; nullable Flow is out of scope (ADR-065 deferred).
      val isNullableElement: Boolean =
        isStateFlowType && flowElementTypeResolved?.isMarkedNullable == true
      val isNullableMember: Boolean = isStateFlowType && propTypeResolved.isMarkedNullable
      val flowElementType: String? = if (isFlowType || isStateFlowType) {
        // ADR-066: qualified, not by simple name — an admitted dependency-module element type is
        // not guaranteed to share this class's own namespace.
        qualifiedElementCsType(flowElementTypeResolved, context, isNullableElement)
      } else null
      if (isFlowType || isStateFlowType) {
        tracker.needsFlow = true
        tracker.needsAsync = true
      }
      if (isStateFlowType) tracker.needsStateFlow = true

      if (!isLambdaType && !isSuspendLambdaType && !isFlowType && !isStateFlowType) {
        return@mapNotNull null
      }

      val lambdaTypeArgs: List<String> = if (isLambdaType) {
        propTypeResolved.arguments.map { arg ->
          val argType: String = arg.type?.resolve()?.declaration?.simpleName?.asString() ?: "object"
          KOTLIN_TO_CSHARP_PARAM[argType] ?: argType
        }
      } else emptyList()

      val lambdaCsType: String = if (isLambdaType) {
        val typeParams: String = lambdaTypeArgs.joinToString(", ")
        "KotlinFunc<$typeParams>"
      } else ""

      val suspendLambdaTypeArgs: List<String> = if (isSuspendLambdaType) {
        propTypeResolved.arguments.map { arg ->
          val argType: String = arg.type?.resolve()?.declaration?.simpleName?.asString() ?: "object"
          KOTLIN_TO_CSHARP_PARAM[argType] ?: argType
        }
      } else emptyList()

      val suspendLambdaIsUnit: Boolean = isSuspendLambdaType &&
          (suspendLambdaTypeArgs.lastOrNull() == "void" ||
              propTypeResolved.arguments.lastOrNull()?.type?.resolve()?.declaration?.qualifiedName?.asString() == "kotlin.Unit")

      val suspendLambdaCsType: String = if (isSuspendLambdaType) {
        if (suspendLambdaIsUnit) {
          if (suspendLambdaArity == 0) "KotlinSuspendAction"
          else {
            val typeParams: String = suspendLambdaTypeArgs.dropLast(1).joinToString(", ")
            "KotlinSuspendAction<$typeParams>"
          }
        } else {
          val typeParams: String = suspendLambdaTypeArgs.joinToString(", ")
          "KotlinSuspendFunc<$typeParams>"
        }
      } else ""

      val nativeReturnType: String = "IntPtr"
      val type: String = when {
        isLambdaType -> lambdaCsType
        isSuspendLambdaType -> suspendLambdaCsType
        isStateFlowType -> "KotlinStateFlow<$flowElementType>${if (isNullableMember) "?" else ""}"
        isFlowType -> "KotlinFlow<$flowElementType>"
        else -> error("unreachable specialized property branch")
      }

      val getter: String = when {
        isLambdaType -> "new $lambdaCsType(Native_Get_$propName(_handle))"
        isSuspendLambdaType -> "new $suspendLambdaCsType(Native_Get_$propName(_handle))"
        isStateFlowType -> {
          // ADR-065: the collect wiring is byte-for-byte the plain-Flow getter above; the only
          // addition is the second constructor argument, a synchronous `_value` read lambda.
          // ADR-067: a nullable member additionally probes `_has_value` before constructing.
          val collectNativeName = "Native_Get${csPropName}Collect"
          val valueNativeName = "Native_Get${csPropName}Value"
          val hasValueNativeName = "Native_Get${csPropName}HasValue"
          buildString {
            appendLine()
            appendLine("                if (_handle == IntPtr.Zero)")
            appendLine("                    throw new ObjectDisposedException(nameof(${cls.simpleName.asString()}));")
            if (isNullableMember) {
              appendLine("                if (!$hasValueNativeName(_handle))")
              appendLine("                    return null;")
            }
            appendLine("                return new KotlinStateFlow<$flowElementType>((onNext, onComplete, onError, userData) =>")
            appendLine("                    $collectNativeName(_handle, GetOrCreateScope(), onNext, onComplete, onError, userData),")
            appendLine("                    () => $valueNativeName(_handle));")
            append("            ")
          }
        }

        isFlowType -> {
          val collectNativeName = "Native_Get${csPropName}Collect"
          buildString {
            appendLine()
            appendLine("                if (_handle == IntPtr.Zero)")
            appendLine("                    throw new ObjectDisposedException(nameof(${cls.simpleName.asString()}));")
            appendLine("                return new KotlinFlow<$flowElementType>((onNext, onComplete, onError, userData) =>")
            appendLine("                    $collectNativeName(_handle, GetOrCreateScope(), onNext, onComplete, onError, userData));")
            append("            ")
          }
        }

        else -> error("unreachable specialized property getter")
      }

      CirProperty(
        name = csPropName,
        type = type,
        nativeReturnType = nativeReturnType,
        nativeSetterType = nativeReturnType,
        nativeName = propName,
        getter = getter,
        setter = null,
        extraNatives = emptyList(),
        isFlow = isFlowType || isStateFlowType,
        isStateFlow = isStateFlowType,
        flowElementType = flowElementType ?: "",
        hasSyncErrorOut = false,
        isNullableMember = isNullableMember,
      )
    }.toList()

  val allMethods = cls.getAllFunctions().toList()

  val filteredMethods: List<KSFunctionDeclaration> = allMethods
    .filter { it.getVisibility() == Visibility.PUBLIC }
    .filter { method ->
      val methodName: String = method.simpleName.asString()
      val isDataClassMethod: Boolean = isDataClass &&
          (methodName == "copy" || methodName.startsWith("component"))
      val isSkipped: Boolean = methodName in listOf("equals", "hashCode", "toString", "<init>") ||
          isDataClassMethod
      if (isSkipped) return@filter false

      if (superClass != null) {
        method.parentDeclaration == cls
      } else {
        true
      }
    }

  val (allSuspendMethods, regularMethods) = filteredMethods
    .partition { it.modifiers.contains(Modifier.SUSPEND) }

  // ADR-068: a `suspend fun` returning StateFlow<T>/MutableStateFlow<T> is peeled into its own
  // bucket BEFORE the plain-async `asyncMembers` path (below) claims it -- that path would
  // otherwise resolve the return type's simple name "StateFlow" through KOTLIN_TO_CSHARP_PARAM
  // (a miss) and emit an undefined-type `Task<StateFlow>`. `suspend fun` returning plain Flow<T>
  // stays on the (separately deferred) legacy asyncMembers path -- out of scope for this ADR.
  val (suspendStateFlowMethods, suspendMethods) = allSuspendMethods.partition { method ->
    val returnQualified: String? = method.returnType?.resolve()?.expandAliases()
      ?.declaration?.qualifiedName?.asString()
    returnQualified in STATE_FLOW_TYPES
  }

  val (flowMethods, nonFlowMethods) = regularMethods.partition { method ->
    val returnQualified: String? = method.returnType?.resolve()?.expandAliases()
      ?.declaration?.qualifiedName?.asString()
    returnQualified in FLOW_TYPES || returnQualified in STATE_FLOW_TYPES
  }

  if (flowMethods.isNotEmpty()) {
    tracker.needsFlow = true
    tracker.needsAsync = true
  }

  if (suspendStateFlowMethods.isNotEmpty()) {
    tracker.needsFlow = true
    tracker.needsStateFlow = true
    tracker.needsAsync = true
    tracker.needsSuspendStateFlow = true
  }
  if (flowMethods.any { method ->
      method.returnType?.resolve()?.expandAliases()
        ?.declaration?.qualifiedName?.asString() in STATE_FLOW_TYPES
    }
  ) {
    tracker.needsStateFlow = true
  }

  val (lambdaParamMethods, normalMethods) = nonFlowMethods.partition { method ->
    method.parameters.any { param ->
      param.type.resolve().expandAliases().declaration.qualifiedName?.asString() in LAMBDA_TYPES
    }
  }

  // Detect stored-callback pairs; exclude both halves from per-call callback path.
  val storedCallbackPairs: List<Pair<KSFunctionDeclaration, KSFunctionDeclaration>> =
    findStoredCallbackPairs(lambdaParamMethods)
  val storedCallbackExcluded: Set<KSFunctionDeclaration> =
    (storedCallbackPairs.map { it.first } + storedCallbackPairs.map { it.second }).toSet()

  val callbackMembers: List<CirCallbackMethod> = lambdaParamMethods
    .filter { it !in storedCallbackExcluded }
    .mapNotNull { method ->
      translateCallbackMethod(method, libraryName, prefix, exportedTypes, tracker)
    }

  val storedCallbackMembers: List<CirStoredCallbackMethod> = storedCallbackPairs
    .mapNotNull { (addMethod, removeMethod) ->
      translateStoredCallbackMethod(addMethod, removeMethod, libraryName, prefix, exportedTypes, tracker)
    }

  // Detect interface-bridge pairs; exclude both halves from regular method path.
  val interfaceBridgePairs: List<Pair<KSFunctionDeclaration, KSFunctionDeclaration>> =
    findInterfaceBridgePairs(normalMethods)
  val interfaceBridgeExcluded: Set<KSFunctionDeclaration> =
    (interfaceBridgePairs.map { it.first } + interfaceBridgePairs.map { it.second }).toSet()

  val interfaceBridgeMembers: List<CirInterfaceBridgeMethod> = interfaceBridgePairs
    .mapNotNull { (addMethod, removeMethod) ->
      translateInterfaceBridgeMethod(addMethod, removeMethod, libraryName, prefix, name, tracker)
    }

  val methods: List<CirMethod> = normalMethods
    .filter { it !in interfaceBridgeExcluded }
    .mapNotNull { method ->
      val methodName: String = method.simpleName.asString()
      val planned = callableCatalog.planFor(
        "${cls.qualifiedName?.asString() ?: name}.$methodName",
      )
      if (planned != null) {
        tracker.trackPlan(planned)
        return@mapNotNull ForwardCirPlanProjection.classMethod(
          plan = planned,
          nativePrefix = prefix,
          isOverride = superClass != null && method.modifiers.contains(Modifier.OVERRIDE),
        )
      }
      // Abstract declarations still need a C# abstract method for the public surface even though
      // they have no native export / plan (planner skips ABSTRACT).
      val declaredInThisClass: Boolean = method.parentDeclaration == cls
      val hasImplementation: Boolean = declaredInThisClass ||
          method.modifiers.contains(Modifier.OVERRIDE)
      val isMethodAbstract: Boolean = !hasImplementation &&
          (isAbstract || method.modifiers.contains(Modifier.ABSTRACT))
      if (!isMethodAbstract) return@mapNotNull null
      val methodReturnTypeResolved = method.returnType?.resolve()?.expandAliases()
      val methodReturn: String =
        methodReturnTypeResolved?.declaration?.simpleName?.asString() ?: "Unit"
      val isNullableReturn: Boolean = methodReturnTypeResolved?.isMarkedNullable == true
      val returnType: String = when {
        methodReturn == "Unit" -> "void"
        methodReturn == "String" && isNullableReturn -> "string?"
        methodReturn == "String" -> "string"
        methodReturn in KOTLIN_TO_CSHARP_RETURN -> {
          val mapped = KOTLIN_TO_CSHARP_RETURN.getValue(methodReturn)
          if (isNullableReturn && mapped != "void") "$mapped?" else mapped
        }

        isNullableReturn -> "$methodReturn?"
        else -> methodReturn
      }
      val methodParams: List<CirParameter> = method.parameters.map { param ->
        val resolved = param.type.resolve().expandAliases()
        val kotlinType: String = resolved.declaration.simpleName.asString()
        val isEnum: Boolean = (resolved.declaration as? KSClassDeclaration)
          ?.classKind == ClassKind.ENUM_CLASS
        val isNullableString: Boolean = !isEnum && kotlinType == "String" && resolved.isMarkedNullable
        val paramType: String = when {
          isEnum -> kotlinType
          isNullableString -> "string?"
          kotlinType in KOTLIN_TO_CSHARP_PARAM -> KOTLIN_TO_CSHARP_PARAM.getValue(kotlinType)
          else -> kotlinType
        }
        CirParameter(param.name?.asString() ?: "_", paramType)
      }
      CirMethod(
        name = methodName.replaceFirstChar { it.uppercase() },
        returnType = returnType,
        parameters = methodParams,
        body = "",
        isAbstract = true,
        isOverride = superClass != null && method.modifiers.contains(Modifier.OVERRIDE),
        isSyncErrorCheckEnabled = false,
      )
    }

  if (suspendMethods.isNotEmpty()) tracker.needsAsync = true

  val asyncMembers: List<CirMember> = suspendMethods.flatMap { method ->
    val methodName: String = method.simpleName.asString()
    val cname: String = toCName(methodName)
    val csMethodName: String = methodName.replaceFirstChar { it.uppercase() }
    val methodReturn: String = method.returnType?.resolve()?.expandAliases()
      ?.declaration?.simpleName?.asString() ?: "Unit"
    val isUnit: Boolean = methodReturn == "Unit"

    val methodParams: List<CirParameter> = method.parameters.map { param ->
      val resolved: KSType = param.type.resolve().expandAliases()
      val kotlinType: String = resolved.declaration.simpleName.asString()
      CirParameter(param.name?.asString() ?: "_", mapParamType(kotlinType))
    }

    val asyncReturnType: String = if (isUnit) "" else {
      KOTLIN_TO_CSHARP_PARAM[methodReturn] ?: methodReturn
    }

    val nativeParams: List<CirParameter> = listOf(
      CirParameter("handle", "IntPtr"),
      CirParameter("scopeHandle", "IntPtr"),
    ) + methodParams +
        listOf(
          CirParameter("callback", "NugetAsyncCallback"),
          CirParameter("userData", "IntPtr"),
        )

    val nativeImport = CirDllImport(
      libraryName = libraryName,
      entryPoint = "${prefix}_${cname}_async",
      returnType = "IntPtr",
      name = "Native_${csMethodName}Async",
      parameters = nativeParams,
      visibility = CirVisibility.PRIVATE,
    )

    val taskReturnType: String = if (isUnit) "Task" else "Task<$asyncReturnType>"

    val asyncMethod = CirMethod(
      name = "${csMethodName}Async",
      returnType = taskReturnType,
      parameters = methodParams,
      body = "",
      isAsync = true,
      asyncReturnType = asyncReturnType,
    )

    listOf(nativeImport, asyncMethod)
  }

  // ADR-068: `suspend fun` returning StateFlow<T>/MutableStateFlow<T> -- the `_async` export is
  // byte-for-byte [asyncMembers]'s shape above (it already boxes the awaited StateFlow object as
  // a StableRef handle; SuspendFunctionExports.kt needs no change). The rendered method differs:
  // `renderAsyncMethod` recognizes the `KotlinStateFlow<` asyncReturnType prefix and wraps the
  // awaited handle in a handle-owning KotlinStateFlow<T> via the shared
  // `nuget_stateflow_collect`/`nuget_stateflow_value` exports, instead of `new T(resultPtr)`.
  val suspendStateFlowMembers: List<CirMember> = suspendStateFlowMethods.flatMap { method ->
    val methodName: String = method.simpleName.asString()
    val cname: String = toCName(methodName)
    val csMethodName: String = methodName.replaceFirstChar { it.uppercase() }
    val returnType = method.returnType?.resolve()?.expandAliases()
    val flowElementTypeResolved: KSType? = returnType?.arguments?.firstOrNull()?.type?.resolve()
    // v1 scope (ADR-068): nullable element/member is deferred; mirror ADR-065's plain (non-null)
    // shape only.
    val flowCsElementType: String = qualifiedElementCsType(flowElementTypeResolved, context)

    val methodParams: List<CirParameter> = method.parameters.map { param ->
      val resolved: KSType = param.type.resolve().expandAliases()
      val kotlinType: String = resolved.declaration.simpleName.asString()
      CirParameter(param.name?.asString() ?: "_", mapParamType(kotlinType))
    }

    val nativeParams: List<CirParameter> = listOf(
      CirParameter("handle", "IntPtr"),
      CirParameter("scopeHandle", "IntPtr"),
    ) + methodParams +
        listOf(
          CirParameter("callback", "NugetAsyncCallback"),
          CirParameter("userData", "IntPtr"),
        )

    val nativeImport = CirDllImport(
      libraryName = libraryName,
      entryPoint = "${prefix}_${cname}_async",
      returnType = "IntPtr",
      name = "Native_${csMethodName}Async",
      parameters = nativeParams,
      visibility = CirVisibility.PRIVATE,
    )

    val asyncReturnType = "KotlinStateFlow<$flowCsElementType>"

    val asyncMethod = CirMethod(
      name = "${csMethodName}Async",
      returnType = "Task<$asyncReturnType>",
      parameters = methodParams,
      body = "",
      isAsync = true,
      asyncReturnType = asyncReturnType,
    )

    listOf(nativeImport, asyncMethod)
  }

  val flowMembers: List<CirMember> = flowMethods.flatMap { method ->
    val methodName: String = method.simpleName.asString()
    val cname: String = toCName(methodName)
    val csMethodName: String = methodName.replaceFirstChar { it.uppercase() }
    val returnType = method.returnType?.resolve()?.expandAliases()
    val returnQualified: String? = returnType?.declaration?.qualifiedName?.asString()
    val isStateFlowMethod: Boolean = returnQualified in STATE_FLOW_TYPES
    val flowElementTypeResolved: KSType? = returnType?.arguments?.firstOrNull()?.type?.resolve()
    // ADR-067: nullable element/member threading mirrors the sibling property branch above;
    // nullable Flow stays out of scope (ADR-065 deferred).
    val isNullableElement: Boolean =
      isStateFlowMethod && flowElementTypeResolved?.isMarkedNullable == true
    val isNullableMember: Boolean = isStateFlowMethod && returnType?.isMarkedNullable == true
    // ADR-066: qualified, not by simple name — see the sibling property branch above for why.
    val flowCsElementType: String =
      qualifiedElementCsType(flowElementTypeResolved, context, isNullableElement)

    val methodParams: List<CirParameter> = method.parameters.map { param ->
      val resolved: KSType = param.type.resolve().expandAliases()
      val kotlinType: String = resolved.declaration.simpleName.asString()
      CirParameter(param.name?.asString() ?: "_", mapParamType(kotlinType))
    }

    val nativeParams: List<CirParameter> = listOf(
      CirParameter("handle", "IntPtr"),
      CirParameter("scopeHandle", "IntPtr"),
    ) + methodParams + listOf(
      CirParameter("onNext", "IntPtr"),
      CirParameter("onComplete", "IntPtr"),
      CirParameter("onError", "IntPtr"),
      CirParameter("userData", "IntPtr"),
    )

    val nativeImport = CirDllImport(
      libraryName = libraryName,
      entryPoint = "${prefix}_${cname}_collect",
      returnType = "IntPtr",
      name = "Native_${csMethodName}Collect",
      parameters = nativeParams,
      visibility = CirVisibility.PRIVATE,
    )

    val paramNames: String = methodParams.joinToString(", ") { it.name }
    val nativeCallArgs: String = if (paramNames.isEmpty()) {
      "_handle, GetOrCreateScope(), onNext, onComplete, onError, userData"
    } else {
      "_handle, GetOrCreateScope(), $paramNames, onNext, onComplete, onError, userData"
    }

    if (isStateFlowMethod) {
      // ADR-065: sibling synchronous `_value` export -- takes handle + the method's own
      // parameters (no scope, no callbacks, no errorOut; StateFlow.value cannot throw).
      val valueNativeImport = CirDllImport(
        libraryName = libraryName,
        entryPoint = "${prefix}_${cname}_value",
        returnType = "IntPtr",
        name = "Native_${csMethodName}Value",
        parameters = listOf(CirParameter("handle", "IntPtr")) + methodParams,
        visibility = CirVisibility.PRIVATE,
      )

      // ADR-067: nullable member -- sibling `_has_value` presence-probe DllImport, same
      // parameter shape as `_value` (handle + the method's own parameters).
      val hasValueNativeImport: CirDllImport? = if (isNullableMember) {
        CirDllImport(
          libraryName = libraryName,
          entryPoint = "${prefix}_${cname}_has_value",
          returnType = "bool",
          name = "Native_${csMethodName}HasValue",
          parameters = listOf(CirParameter("handle", "IntPtr")) + methodParams,
          visibility = CirVisibility.PRIVATE,
          marshalBooleanReturn = true,
        )
      } else null

      val stateFlowMethod = CirMethod(
        name = csMethodName,
        returnType = "KotlinStateFlow<$flowCsElementType>${if (isNullableMember) "?" else ""}",
        nativeName = "Native_${csMethodName}Collect",
        parameters = methodParams,
        body = nativeCallArgs,
        isFlow = true,
        isStateFlow = true,
        flowElementType = flowCsElementType,
        stateFlowValueNativeName = "Native_${csMethodName}Value",
        isStateFlowNullableMember = isNullableMember,
        stateFlowHasValueNativeName =
          if (isNullableMember) "Native_${csMethodName}HasValue" else "",
      )

      return@flatMap listOfNotNull(
        nativeImport, valueNativeImport, hasValueNativeImport, stateFlowMethod,
      )
    }

    val flowMethod = CirMethod(
      name = csMethodName,
      returnType = "KotlinFlow<$flowCsElementType>",
      nativeName = "Native_${csMethodName}Collect",
      parameters = methodParams,
      body = nativeCallArgs,
      isFlow = true,
      flowElementType = flowCsElementType,
    )

    listOf(nativeImport, flowMethod)
  }

  val companion: KSClassDeclaration? = cls.declarations
    .filterIsInstance<KSClassDeclaration>()
    .firstOrNull { it.isCompanionObject }

  val companionMembers: List<CirMember> = if (companion != null) {
    val companionConsts: List<CirMember> = companion.getAllProperties()
      .filter { it.getVisibility() == Visibility.PUBLIC }
      .filter { it.modifiers.contains(Modifier.CONST) }
      .mapNotNull { translateConstProperty(it) }
      .toList()

    val companionProperties: List<CirMember> = companion.getAllProperties()
      .filter { it.getVisibility() == Visibility.PUBLIC }
      .filter { !it.modifiers.contains(Modifier.CONST) }
      .flatMap { prop ->
        val symbol: String = "${cls.qualifiedName?.asString() ?: name}.Companion.${prop.simpleName.asString()}"
        val planned = callableCatalog.propertyFor(symbol)
        if (planned != null) {
          tracker.trackProperty(planned)
          ForwardCirPropertyProjection.staticProperty(planned, libraryName)
        } else {
          emptyList()
        }
      }
      .toList()

    val companionFunctions: List<CirMember> = companion.getAllFunctions()
      .filter { it.getVisibility() == Visibility.PUBLIC }
      .filter { it.simpleName.asString() !in listOf("equals", "hashCode", "toString", "<init>") }
      .flatMap { func ->
        val symbol: String = "${cls.qualifiedName?.asString() ?: name}.Companion.${func.simpleName.asString()}"
        val planned = callableCatalog.planFor(symbol)
        if (planned != null) {
          tracker.trackPlan(planned)
          ForwardCirPlanProjection.static(planned, libraryName)
        } else {
          emptyList()
        }
      }
      .toList()

    companionConsts + companionProperties + companionFunctions
  } else emptyList()

  // Phase 6: route data-class copy() through the shared plan when it is eligible (same symbol
  // ClassExports.kt checks for the Kotlin half), else keep the legacy hand-rolled route.
  val copyMethod: CirMethod? = if (isDataClass) {
    callableCatalog.planFor("${cls.qualifiedName?.asString() ?: name}.copy")
      ?.let { planned ->
        tracker.trackPlan(planned)
        ForwardCirPlanProjection.classMethod(planned, prefix, isOverride = false)
      }
  } else null

  return CirClass(
    name = name,
    libraryName = libraryName,
    nativePrefix = prefix,
    constructor = cirConstructor,
    secondaryConstructors = secondaryConstructors,
    properties = properties,
    methods = methods,
    copyMethod = copyMethod,
    callbackMethods = callbackMembers,
    storedCallbackMethods = storedCallbackMembers,
    interfaceBridgeMethods = interfaceBridgeMembers,
    interfaces = interfaces,
    superClass = superClass,
    isDataClass = isDataClass,
    isAbstract = isAbstract,
    companionMembers = companionMembers + asyncMembers + suspendStateFlowMembers + flowMembers,
    hasSuspendMethods = cls.getAllFunctions().any { it.modifiers.contains(Modifier.SUSPEND) } ||
        flowMethods.isNotEmpty() ||
        cls.getAllProperties().any { prop ->
          val qualified: String? =
            prop.type.resolve().expandAliases().declaration.qualifiedName?.asString()
          qualified in FLOW_TYPES || qualified in STATE_FLOW_TYPES
        },
  )
}

internal fun translateGenericClass(
  cls: KSClassDeclaration,
  libraryName: String,
  logger: KSPLogger,
): CirGenericClass {
  val name: String = cls.simpleName.asString()
  val prefix: String = name.lowercase()
  val typeParams: List<CirTypeParameter> = cls.typeParameters.map { param ->
    val bounds: List<String> = param.bounds.toList().mapNotNull { bound ->
      val resolved = bound.resolve()
      val qualifiedName: String? = resolved.declaration.qualifiedName?.asString()
      val simpleName: String = resolved.declaration.simpleName.asString()
      val isInterface: Boolean = resolved.declaration is KSClassDeclaration &&
          (resolved.declaration as KSClassDeclaration).classKind ==
          ClassKind.INTERFACE

      when {
        qualifiedName == "kotlin.Any" -> null
        isInterface -> "I$simpleName"
        else -> simpleName
      }
    }

    if (param.variance != Variance.INVARIANT) {
      ForwardDiagnosticSink.emit(
        listOf(
          ForwardDiagnostic(
            kind = ForwardDiagnosticKind.INFO_DROPPED_VARIANCE,
            symbol = cls,
            declaration = "${cls.simpleName.asString()}<${param.name.asString()}>",
            reason = "variance '${param.variance}' on this generic class type parameter is " +
                "dropped; C# does not support variance on classes",
            hint = "the member still binds; declare the parameter invariant if the dropped " +
                "variance was load-bearing",
          ),
        ),
        logger,
      )
    }

    CirTypeParameter(param.name.asString(), bounds)
  }

  val properties: List<CirProperty> = cls.getAllProperties()
    .filter { it.getVisibility() == Visibility.PUBLIC }
    .map { prop ->
      val propName: String = prop.simpleName.asString()
      val csPropName: String = propName.replaceFirstChar { it.uppercase() }

      CirProperty(
        name = csPropName,
        type = typeParams.first().name,
        nativeReturnType = "IntPtr",
        nativeName = propName,
        getter = "NugetMarshal.FromHandle<${typeParams.first().name}>(${name}Native.Get_$propName(_handle))",
        setter = null,
      )
    }
    .toList()

  return CirGenericClass(
    name = name,
    typeParameters = typeParams,
    libraryName = libraryName,
    nativePrefix = prefix,
    properties = properties,
    hasPublicConstructor = true,
  )
}

internal fun translateSealedClass(
  cls: KSClassDeclaration,
  libraryName: String,
  tracker: CollectionHelperTracker,
  exportedTypes: Set<String>,
  logger: KSPLogger,
): CirSealedClass {
  val name: String = cls.simpleName.asString()
  val prefix: String = name.lowercase()

  val subclasses: List<CirSealedSubclass> = cls.getSealedSubclasses()
    .map { subclass ->
      val subName: String = subclass.simpleName.asString()
      val subPrefix: String = "${prefix}_${subName.lowercase()}"
      val isDataClass: Boolean = subclass.modifiers.contains(Modifier.DATA)
      val isDataObject: Boolean = isDataClass && subclass.classKind == ClassKind.OBJECT

      val properties: List<CirProperty> = if (isDataObject) {
        emptyList()
      } else {
        subclass.getAllProperties()
          .filter { it.getVisibility() == Visibility.PUBLIC }
          .mapNotNull { prop ->
            val propName: String = prop.simpleName.asString()
            val propTypeResolved: KSType = prop.type.resolve().expandAliases()
            val propType: String = propTypeResolved.declaration.simpleName.asString()
            val isNullable: Boolean = propTypeResolved.isMarkedNullable
            val csPropName: String = propName.replaceFirstChar { it.uppercase() }

            val isEnumType: Boolean = (propTypeResolved.declaration as? KSClassDeclaration)
              ?.classKind == ClassKind.ENUM_CLASS

            val qualifiedTypeName: String? = propTypeResolved.declaration.qualifiedName?.asString()
            val isListType: Boolean = qualifiedTypeName == "kotlin.collections.List"
            val isMutableListType: Boolean = qualifiedTypeName == "kotlin.collections.MutableList"
            val isMapType: Boolean = qualifiedTypeName == "kotlin.collections.Map"
            val isMutableMapType: Boolean = qualifiedTypeName == "kotlin.collections.MutableMap"
            val isSetType: Boolean = qualifiedTypeName == "kotlin.collections.Set"
            val isMutableSetType: Boolean = qualifiedTypeName == "kotlin.collections.MutableSet"

            val listElementType: String? = if (isListType || isMutableListType) {
              val elementType = propTypeResolved.arguments.firstOrNull()?.type?.resolve()
              val elementTypeName: String = elementType?.declaration?.simpleName?.asString() ?: "Any"
              KOTLIN_TO_CSHARP_PARAM[elementTypeName] ?: elementTypeName
            } else null

            val mapKeyType: String? = if (isMapType || isMutableMapType) {
              val keyType = propTypeResolved.arguments.getOrNull(0)?.type?.resolve()
              val keyTypeName: String = keyType?.declaration?.simpleName?.asString() ?: "Any"
              KOTLIN_TO_CSHARP_PARAM[keyTypeName] ?: keyTypeName
            } else null

            val mapValueType: String? = if (isMapType || isMutableMapType) {
              val valueType = propTypeResolved.arguments.getOrNull(1)?.type?.resolve()
              val valueTypeName: String = valueType?.declaration?.simpleName?.asString() ?: "Any"
              KOTLIN_TO_CSHARP_PARAM[valueTypeName] ?: valueTypeName
            } else null

            val setElementType: String? = if (isSetType || isMutableSetType) {
              val elementType = propTypeResolved.arguments.firstOrNull()?.type?.resolve()
              val elementTypeName: String = elementType?.declaration?.simpleName?.asString() ?: "Any"
              KOTLIN_TO_CSHARP_PARAM[elementTypeName] ?: elementTypeName
            } else null

            if (isListType || isMutableListType) tracker.needsList = true
            if (isMapType || isMutableMapType) tracker.needsMap = true
            if (isSetType || isMutableSetType) tracker.needsSet = true

            val isLambdaType: Boolean = qualifiedTypeName in LAMBDA_TYPES
            val lambdaArity: Int = if (isLambdaType) propTypeResolved.arguments.size - 1 else -1

            if (isLambdaType) tracker.lambdaArities.add(lambdaArity)

            val isReferenceType: Boolean =
              propType !in KOTLIN_TO_CSHARP_RETURN && !isEnumType && !isListType && !isMutableListType && !isMapType && !isMutableMapType && !isSetType && !isMutableSetType && !isLambdaType

            if (isReferenceType && qualifiedTypeName != null && qualifiedTypeName !in exportedTypes) {
              ForwardDiagnosticSink.emit(
                listOf(
                  ForwardDiagnostic(
                    kind = ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_TYPE,
                    symbol = prop,
                    declaration = "${cls.simpleName.asString()}.$subName.$propName",
                    reason = "its type '$qualifiedTypeName' is not in the bridgeable subset " +
                        "(not an exported class/object/enum and not a supported " +
                        "primitive/collection)",
                    hint = "expose a bridgeable wrapper type instead, or add " +
                        "'$qualifiedTypeName' to the export set",
                  ),
                ),
                logger,
              )
              return@mapNotNull null
            }

            val lambdaTypeArgs: List<String> = if (isLambdaType) {
              propTypeResolved.arguments.map { arg ->
                val argType: String = arg.type?.resolve()?.declaration?.simpleName?.asString() ?: "object"
                KOTLIN_TO_CSHARP_PARAM[argType] ?: argType
              }
            } else emptyList()

            val lambdaCsType: String = if (isLambdaType) {
              val typeParams: String = lambdaTypeArgs.joinToString(", ")
              "KotlinFunc<$typeParams>"
            } else ""

            val nativeReturnType: String = when {
              isLambdaType -> "IntPtr"
              (isListType || isMutableListType) -> "IntPtr"
              (isMapType || isMutableMapType) -> "IntPtr"
              (isSetType || isMutableSetType) -> "IntPtr"
              isEnumType -> "int"
              isReferenceType -> "IntPtr"
              else -> mapReturnType(propType)
            }

            val type: String = when {
              isLambdaType -> lambdaCsType
              isListType -> "IReadOnlyList<$listElementType>"
              isMutableListType -> "IList<$listElementType>"
              isMapType -> "IReadOnlyDictionary<$mapKeyType, $mapValueType>"
              isMutableMapType -> "IDictionary<$mapKeyType, $mapValueType>"
              isSetType -> "IReadOnlySet<$setElementType>"
              isMutableSetType -> "ISet<$setElementType>"
              propType == "String" -> "string"
              isEnumType -> propType
              isReferenceType && isNullable -> "$propType?"
              isReferenceType -> propType
              else -> mapReturnType(propType)
            }

            val getter: String = if (isLambdaType) {
              "new $lambdaCsType(Native_Get_$propName(_handle))"
            } else if (isListType) {
              buildString {
                appendLine()
                appendLine("                IntPtr listHandle = Native_Get_$propName(_handle);")
                appendLine("                int count = NugetListNative.Count(listHandle);")
                appendLine("                var result = new List<$listElementType>(count);")
                appendLine("                for (int i = 0; i < count; i++)")
                appendLine("                {")
                appendLine("                    result.Add(NugetMarshal.FromHandle<$listElementType>(NugetListNative.Get(listHandle, i)));")
                appendLine("                }")
                appendLine("                NugetListNative.Dispose(listHandle);")
                append("                return result.AsReadOnly();")
              }
            } else if (isMutableListType) {
              buildString {
                appendLine()
                appendLine("                IntPtr listHandle = Native_Get_$propName(_handle);")
                appendLine("                int count = NugetListNative.Count(listHandle);")
                appendLine("                var result = new List<$listElementType>(count);")
                appendLine("                for (int i = 0; i < count; i++)")
                appendLine("                {")
                appendLine("                    result.Add(NugetMarshal.FromHandle<$listElementType>(NugetListNative.Get(listHandle, i)));")
                appendLine("                }")
                appendLine("                NugetListNative.Dispose(listHandle);")
                append("                return result;")
              }
            } else if (isMapType || isMutableMapType) {
              buildString {
                appendLine()
                appendLine("                IntPtr mapHandle = Native_Get_$propName(_handle);")
                appendLine("                int count = NugetMapNative.Count(mapHandle);")
                appendLine("                var result = new Dictionary<$mapKeyType, $mapValueType>(count);")
                appendLine("                for (int i = 0; i < count; i++)")
                appendLine("                {")
                appendLine("                    var key = NugetMarshal.FromHandle<$mapKeyType>(NugetMapNative.KeyAt(mapHandle, i));")
                appendLine("                    var value = NugetMarshal.FromHandle<$mapValueType>(NugetMapNative.ValueAt(mapHandle, i));")
                appendLine("                    result[key] = value;")
                appendLine("                }")
                appendLine("                NugetMapNative.Dispose(mapHandle);")
                append("                return result;")
              }
            } else if (isSetType || isMutableSetType) {
              buildString {
                appendLine()
                appendLine("                IntPtr setHandle = Native_Get_$propName(_handle);")
                appendLine("                int count = NugetSetNative.Count(setHandle);")
                appendLine("                var result = new HashSet<$setElementType>(count);")
                appendLine("                for (int i = 0; i < count; i++)")
                appendLine("                {")
                appendLine("                    result.Add(NugetMarshal.FromHandle<$setElementType>(NugetSetNative.ElementAt(setHandle, i)));")
                appendLine("                }")
                appendLine("                NugetSetNative.Dispose(setHandle);")
                append("                return result;")
              }
            } else when {
              propType == "String" -> "Marshal.PtrToStringUTF8(Native_Get_$propName(_handle))!"
              isEnumType -> "($propType)Native_Get_$propName(_handle)"
              isReferenceType && isNullable -> "Native_Get_$propName(_handle) == IntPtr.Zero ? null : new $propType(Native_Get_$propName(_handle))"
              isReferenceType -> "new $propType(Native_Get_$propName(_handle))"
              else -> "Native_Get_$propName(_handle)"
            }

            CirProperty(
              name = csPropName,
              type = type,
              nativeReturnType = nativeReturnType,
              nativeName = propName,
              getter = getter,
              setter = null,
            )
          }
          .toList()
      }

      CirSealedSubclass(
        name = subName,
        nativePrefix = subPrefix,
        properties = properties,
        isDataClass = isDataClass,
        isDataObject = isDataObject,
      )
    }
    .toList()

  return CirSealedClass(
    name = name,
    libraryName = libraryName,
    nativePrefix = prefix,
    subclasses = subclasses,
  )
}

internal fun translateObject(
  obj: KSClassDeclaration,
  libraryName: String,
  callableCatalog: ForwardCallablePlanCatalog,
  tracker: CollectionHelperTracker,
): CirObject {
  val name: String = obj.simpleName.asString()
  val prefix: String = name.lowercase()

  // Object methods are static (no receiver handle), so they route through the same
  // shape as top-level functions (CirFunctionTranslator's static template) rather than
  // the class instance-method loop, which hardcodes _handle. See the comment above
  // this function's call site / ADR-060 cells 1 & 25.
  val methods: List<CirMember> = obj.getAllFunctions()
    .filter { it.getVisibility() == Visibility.PUBLIC }
    .filter { it.simpleName.asString() !in listOf("equals", "hashCode", "toString", "<init>") }
    .flatMap { method ->
      val methodName: String = method.simpleName.asString()
      val symbol: String = "${obj.qualifiedName?.asString() ?: name}.$methodName"
      val planned = callableCatalog.planFor(symbol)
      if (planned != null) {
        tracker.trackPlan(planned)
        ForwardCirPlanProjection.static(planned, libraryName)
      } else {
        emptyList()
      }
    }
    .toList()

  return CirObject(
    name = name,
    libraryName = libraryName,
    nativePrefix = prefix,
    methods = methods,
  )
}

internal fun translateCompanionProperty(
  prop: KSPropertyDeclaration,
  libraryName: String,
  classPrefix: String,
): List<CirMember> {
  val propName: String = prop.simpleName.asString()
  val propTypeResolved: KSType = prop.type.resolve().expandAliases()
  val propType: String = propTypeResolved.declaration.simpleName.asString()
  val isMutable: Boolean = prop.isMutable
  val csPropName: String = propName.replaceFirstChar { it.uppercase() }

  if (propType !in KOTLIN_TO_CSHARP_RETURN) return emptyList()

  val members: MutableList<CirMember> = mutableListOf()

  val csNativeReturnType: String = mapReturnType(propType)

  members.add(
    CirDllImport(
      libraryName = libraryName,
      entryPoint = "${classPrefix}_companion_get_$propName",
      returnType = csNativeReturnType,
      name = "Native_Companion_Get_$propName",
      parameters = emptyList(),
      visibility = CirVisibility.PRIVATE,
    )
  )

  val csType: String
  val getter: String
  val setter: String?

  if (propType == "String") {
    csType = "string"
    getter = "Marshal.PtrToStringUTF8(Native_Companion_Get_$propName())!"
    setter = if (isMutable) "Native_Companion_Set_$propName(value)" else null

    if (isMutable) {
      members.add(
        CirDllImport(
          libraryName = libraryName,
          entryPoint = "${classPrefix}_companion_set_$propName",
          returnType = "void",
          name = "Native_Companion_Set_$propName",
          parameters = listOf(CirParameter("value", "string")),
          visibility = CirVisibility.PRIVATE,
        )
      )
    }
  } else {
    csType = csNativeReturnType
    getter = "Native_Companion_Get_$propName()"
    setter = if (isMutable) "Native_Companion_Set_$propName(value)" else null

    if (isMutable) {
      members.add(
        CirDllImport(
          libraryName = libraryName,
          entryPoint = "${classPrefix}_companion_set_$propName",
          returnType = "void",
          name = "Native_Companion_Set_$propName",
          parameters = listOf(CirParameter("value", csNativeReturnType)),
          visibility = CirVisibility.PRIVATE,
        )
      )
    }
  }

  members.add(
    CirProperty(
      name = csPropName,
      type = csType,
      nativeReturnType = csNativeReturnType,
      nativeName = propName,
      getter = getter,
      setter = setter,
      isStatic = true,
    )
  )

  return members
}

internal fun translateCompanionFunction(
  func: KSFunctionDeclaration,
  libraryName: String,
  classPrefix: String,
  className: String,
  tracker: CollectionHelperTracker,
): List<CirMember> {
  val methodName: String = func.simpleName.asString()
  val cname: String = toCName(methodName)
  val csMethodName: String = methodName.replaceFirstChar { it.uppercase() }
  val returnType = func.returnType?.resolve()?.expandAliases()
  val kotlinReturnType: String = returnType?.declaration?.simpleName?.asString() ?: "Unit"

  val params: List<CirParameter> = func.parameters.map { param ->
    val kotlinType: String = param.type.resolve().expandAliases().declaration.simpleName.asString()
    CirParameter(param.name?.asString() ?: "_", mapParamType(kotlinType))
  }

  val entryPoint: String = "${classPrefix}_companion_${cname}"
  val nativeName: String = "Native_Companion_$csMethodName"
  val paramNames: String = params.joinToString(", ") { it.name }
  val nativeCallArgs: String = if (paramNames.isEmpty()) {
    "out IntPtr error"
  } else {
    "$paramNames, out IntPtr error"
  }
  val returnQualified: String? = returnType?.declaration?.qualifiedName?.asString()
  val isListReturn: Boolean = returnQualified in setOf(
    "kotlin.collections.List",
    "kotlin.collections.MutableList",
  )
  val isMutableListReturn: Boolean = returnQualified == "kotlin.collections.MutableList"
  val listElementType: String? = if (isListReturn) {
    val elementType: KSType? = returnType?.arguments?.firstOrNull()?.type?.resolve()
    val elementTypeName: String = elementType?.declaration?.simpleName?.asString() ?: "Any"
    KOTLIN_TO_CSHARP_PARAM[elementTypeName] ?: elementTypeName
  } else null
  if (isListReturn) tracker.needsList = true

  fun nativeImport(returnType: String): CirDllImport = CirDllImport(
    libraryName = libraryName,
    entryPoint = entryPoint,
    returnType = returnType,
    name = nativeName,
    parameters = params,
    visibility = CirVisibility.PRIVATE,
    hasSyncErrorOut = true,
  )

  val returnsEnclosingClass: Boolean = kotlinReturnType == className
  val isObjectReturn: Boolean = returnsEnclosingClass ||
      (kotlinReturnType !in KOTLIN_TO_CSHARP_RETURN && kotlinReturnType != "Unit" && !isListReturn)

  if (isListReturn) {
    val returnType: String = if (isMutableListReturn) {
      "IList<$listElementType>"
    } else {
      "IReadOnlyList<$listElementType>"
    }
    val body: String = buildString {
      appendLine()
      appendLine("                IntPtr listHandle = $nativeName($nativeCallArgs);")
      appendLine("                if (error != IntPtr.Zero)")
      appendLine("                {")
      appendLine("                    throw NugetErrorNative.BuildException(error);")
      appendLine("                }")
      appendLine("                int count = NugetListNative.Count(listHandle);")
      appendLine("                var result = new List<$listElementType>(count);")
      appendLine("                for (int i = 0; i < count; i++)")
      appendLine("                {")
      appendLine("                    result.Add(NugetMarshal.FromHandle<$listElementType>(NugetListNative.Get(listHandle, i)));")
      appendLine("                }")
      appendLine("                NugetListNative.Dispose(listHandle);")
      append(if (isMutableListReturn) "                return result;" else "                return result.AsReadOnly();")
    }
    val wrapper = CirMethod(
      name = csMethodName,
      returnType = returnType,
      nativeReturnType = "IntPtr",
      nativeName = nativeName,
      parameters = params,
      body = body,
      isStatic = true,
      isSyncErrorCheckEnabled = true,
      hasCustomBody = true,
    )

    return listOf(nativeImport("IntPtr"), wrapper)
  }

  if (isObjectReturn) {
    val wrapper = CirMethod(
      name = csMethodName,
      returnType = kotlinReturnType,
      nativeReturnType = "IntPtr",
      nativeName = nativeName,
      parameters = params,
      body = buildString {
        appendLine()
        appendLine("                IntPtr nativeResult = $nativeName($nativeCallArgs);")
        appendLine("                if (error != IntPtr.Zero)")
        appendLine("                {")
        appendLine("                    throw NugetErrorNative.BuildException(error);")
        appendLine("                }")
        append("                return new $kotlinReturnType(nativeResult);")
      },
      isStatic = true,
      isSyncErrorCheckEnabled = true,
      hasCustomBody = true,
    )

    return listOf(nativeImport("IntPtr"), wrapper)
  }

  val csReturnType: String = when (kotlinReturnType) {
    "Unit" -> "void"
    "String" -> "string"
    else -> mapReturnType(kotlinReturnType)
  }
  val nativeReturnType: String = if (kotlinReturnType == "String") "IntPtr" else csReturnType
  val wrapper = CirMethod(
    name = csMethodName,
    returnType = csReturnType,
    nativeReturnType = nativeReturnType,
    nativeName = nativeName,
    parameters = params,
    body = "",
    isStatic = true,
    isSyncErrorCheckEnabled = true,
  )

  return listOf(nativeImport(nativeReturnType), wrapper)
}

internal fun translateInterface(
  iface: KSClassDeclaration,
  libraryName: String,
  logger: KSPLogger,
): CirInterface {
  val name: String = iface.simpleName.asString()
  val interfaceName: String = "I$name"

  val typeParams: List<CirTypeParameter> = iface.typeParameters.map { param ->
    val variance: CirVariance = when (param.variance) {
      Variance.COVARIANT -> CirVariance.COVARIANT
      Variance.CONTRAVARIANT -> CirVariance.CONTRAVARIANT
      else -> CirVariance.INVARIANT
    }
    CirTypeParameter(param.name.asString(), variance = variance)
  }

  val typeParamNames: Set<String> = typeParams.map { it.name }.toSet()

  val properties: List<CirInterfaceProperty> = iface.getAllProperties()
    .filter { it.getVisibility() == Visibility.PUBLIC }
    .map { prop ->
      val propName: String = prop.simpleName.asString()
      val propType: KSType = prop.type.resolve().expandAliases()
      val csPropName: String = propName.replaceFirstChar { it.uppercase() }
      val typeName: String = propType.declaration.simpleName.asString()
      val csType: String =
        if (typeName in typeParamNames) typeName
        else mapInterfacePropertyType(propType)
      CirInterfaceProperty(csPropName, csType)
    }
    .toList()

  val methods: List<CirInterfaceMethod> = iface.getAllFunctions()
    .filter { it.getVisibility() == Visibility.PUBLIC }
    .filter { it.simpleName.asString() !in listOf("equals", "hashCode", "toString", "<init>") }
    .map { method ->
      val methodName: String = method.simpleName.asString()
      val returnType = method.returnType?.resolve()?.expandAliases()
      val kotlinReturnType: String = returnType?.declaration?.simpleName?.asString() ?: "Unit"
      val csMethodName: String = methodName.replaceFirstChar { it.uppercase() }

      val csReturnType: String = when {
        kotlinReturnType == "String" -> "string"
        kotlinReturnType == "Unit" -> "void"
        kotlinReturnType in typeParamNames -> kotlinReturnType
        else -> mapReturnType(kotlinReturnType)
      }

      val params: List<CirParameter> = method.parameters.map { param ->
        val resolved: KSType = param.type.resolve().expandAliases()
        val kotlinType: String = resolved.declaration.simpleName.asString()
        val csType: String =
          if (kotlinType in typeParamNames) kotlinType
          else mapParamType(kotlinType)
        CirParameter(param.name?.asString() ?: "_", csType)
      }

      CirInterfaceMethod(csMethodName, csReturnType, params)
    }
    .toList()

  return CirInterface(interfaceName, typeParams, properties, methods)
}

internal fun translateEnum(
  enum: KSClassDeclaration,
  libraryName: String,
): CirEnum {
  val name: String = enum.simpleName.asString()
  val entries: List<CirEnumEntry> = enum.declarations
    .filterIsInstance<KSClassDeclaration>()
    .mapIndexed { index, entry ->
      val entryName: String = entry.simpleName.asString()
      val csEntryName: String = entryName.split("_")
        .joinToString("") { it.lowercase().replaceFirstChar { c -> c.uppercase() } }
      CirEnumEntry(csEntryName, index)
    }
    .toList()

  val properties: List<CirEnumProperty> = enum.getAllProperties()
    .filter { it.getVisibility() == Visibility.PUBLIC }
    .filter { it.simpleName.asString() !in setOf("name", "ordinal", "declaringJavaClass") }
    .map { prop ->
      val propName: String = prop.simpleName.asString()
      val propTypeResolved: KSType = prop.type.resolve().expandAliases()
      val propType: String = propTypeResolved.declaration.simpleName.asString()
      val csPropName: String = propName.replaceFirstChar { it.uppercase() }

      val nativeReturnType: String = mapReturnType(propType)
      val type: String = if (propType == "String") "string" else mapReturnType(propType)

      CirEnumProperty(
        name = csPropName,
        type = type,
        nativeReturnType = nativeReturnType,
        nativeName = propName,
      )
    }
    .toList()

  return CirEnum(name, libraryName, entries, properties)
}

internal fun translateValueClass(
  cls: KSClassDeclaration,
  libraryName: String,
  callableCatalog: ForwardCallablePlanCatalog = ForwardCallablePlanCatalog(emptyList()),
): CirValueClass {
  val name: String = cls.simpleName.asString()
  val qualifiedName: String = cls.qualifiedName?.asString() ?: name
  val prefix: String = name.lowercase()

  val underlyingParamName: String = cls.primaryConstructor!!.parameters.first().name!!.asString()
  val underlyingProp: KSPropertyDeclaration = cls.getAllProperties()
    .first { it.simpleName.asString() == underlyingParamName }

  val underlyingResolved: KSType = underlyingProp.type.resolve().expandAliases()
  val underlyingType: String = underlyingResolved.declaration.simpleName.asString()
  val underlyingName: String =
    underlyingProp.simpleName.asString().replaceFirstChar { it.uppercase() }
  val underlyingNativeType: String = mapParamType(underlyingType)
  val isReferenceUnderlying: Boolean = underlyingType !in KOTLIN_TO_CSHARP_PARAM

  val nativeArg: String = if (isReferenceUnderlying) "${underlyingName}._handle" else underlyingName

  fun buildConstructor(
    ctor: KSFunctionDeclaration,
    suffix: String,
    nativeName: String,
  ): CirValueClassConstructor {
    val params: List<CirParameter> = ctor.parameters.map { param ->
      val resolved: KSType = param.type.resolve().expandAliases()
      val kotlinType: String = resolved.declaration.simpleName.asString()
      CirParameter(param.name?.asString() ?: "_", mapParamType(kotlinType))
    }

    val paramNames: String = params.joinToString(", ") { it.name }
    val body: String = if (underlyingType == "String") {
      "Marshal.PtrToStringUTF8(CreateChecked${suffix}(${paramNames}))!"
    } else {
      "CreateChecked${suffix}(${paramNames})"
    }

    return CirValueClassConstructor(
      parameters = params,
      nativeName = nativeName,
      body = body,
      hasErrorCheck = true,
      nativeSuffix = suffix,
    )
  }

  fun buildConstructorFromPlan(plan: ForwardCallablePlan, suffix: String): CirValueClassConstructor {
    return ForwardCirPlanProjection.valueClassConstructor(plan, suffix, underlyingType == "String")
  }

  val secondaryCtorDecls: List<KSFunctionDeclaration> = cls.declarations
    .filterIsInstance<KSFunctionDeclaration>()
    .filter { it.simpleName.asString() == "<init>" }
    .filter { it != cls.primaryConstructor }
    .toList()

  val constructors: List<CirValueClassConstructor> = if (isReferenceUnderlying) {
    // ADR-035: primary deferred; secondary-only numbering. Explicit legacy adapter when unplanned.
    secondaryCtorDecls.mapIndexed { index, ctor ->
      val suffix: String = if (index == 0) "" else "_$index"
      val symbolSuffix: String = if (index == 0) "" else "_$index"
      val planned = callableCatalog.planFor("$qualifiedName.<init>$symbolSuffix")
      if (planned != null) {
        buildConstructorFromPlan(planned, suffix)
      } else {
        val nativeName: String = if (index == 0) "${prefix}_create" else "${prefix}_create_${index}"
        buildConstructor(ctor, suffix, nativeName)
      }
    }
  } else {
    // ADR-035: plan-only for primitive-underlying constructors.
    buildList {
      val primaryPlan = callableCatalog.planFor("$qualifiedName.<init>")
      if (primaryPlan != null) add(buildConstructorFromPlan(primaryPlan, ""))
      secondaryCtorDecls.forEachIndexed { index, _ ->
        val number: Int = index + 2
        val planned = callableCatalog.planFor("$qualifiedName.<init>_$number")
        if (planned != null) add(buildConstructorFromPlan(planned, "_$number"))
      }
    }
  }

  val properties: List<CirProperty> = cls.getAllProperties()
    .filter { it.getVisibility() == Visibility.PUBLIC }
    .filter { it.simpleName.asString() != underlyingProp.simpleName.asString() }
    .mapNotNull { prop ->
      val propName: String = prop.simpleName.asString()
      val planned = callableCatalog.planFor("$qualifiedName.$propName")
      if (planned != null) {
        ForwardCirPlanProjection.valueClassProperty(planned, nativeArg)
      } else {
        null
      }
    }
    .toList()

  val methods: List<CirMethod> = cls.getAllFunctions()
    .filter { it.getVisibility() == Visibility.PUBLIC }
    .filter {
      it.simpleName.asString() !in listOf(
        "equals", "hashCode", "toString", "<init>",
        "box-impl", "unbox-impl", "constructor-impl",
        "hashCode-impl", "equals-impl", "equals-impl0", "toString-impl",
      )
    }
    .mapNotNull { method ->
      val methodName: String = method.simpleName.asString()
      val planned = callableCatalog.planFor("$qualifiedName.$methodName")
      if (planned != null) {
        ForwardCirPlanProjection.valueClassMethod(planned, nativeArg)
      } else {
        null
      }
    }
    .toList()

  val csUnderlyingType: String = if (underlyingType == "String") "string"
  else if (isReferenceUnderlying) underlyingType
  else mapParamType(underlyingType)

  return CirValueClass(
    name = name,
    libraryName = libraryName,
    nativePrefix = prefix,
    underlyingType = csUnderlyingType,
    underlyingName = underlyingName,
    underlyingNativeType = underlyingNativeType,
    underlyingIsReference = isReferenceUnderlying,
    constructors = constructors,
    properties = properties,
    methods = methods,
  )
}

private fun mapInterfacePropertyType(type: KSType): String {
  val typeName: String = type.declaration.simpleName.asString()
  return if (typeName == "String") "string"
  else mapParamType(typeName)
}

private fun translateCallbackMethod(
  method: KSFunctionDeclaration,
  libraryName: String,
  classPrefix: String,
  exportedTypes: Set<String>,
  tracker: CollectionHelperTracker,
): CirCallbackMethod? {
  val methodName: String = method.simpleName.asString()
  val csMethodName: String = methodName.replaceFirstChar { it.uppercase() }
  val nativeEntryPoint: String = "${classPrefix}_$methodName"

  val lambdaParam = method.parameters.firstOrNull { param ->
    param.type.resolve().expandAliases().declaration.qualifiedName?.asString() in LAMBDA_TYPES
  } ?: return null

  val lambdaParamName: String = lambdaParam.name?.asString() ?: "callback"
  val lambdaType: KSType = lambdaParam.type.resolve().expandAliases()
  val lambdaArity: Int = lambdaType.arguments.size - 1

  val lambdaArgTypes: List<KSType> = lambdaType.arguments.dropLast(1)
    .mapNotNull { it.type?.resolve()?.expandAliases() }
  val lambdaRetType: KSType? = lambdaType.arguments.lastOrNull()?.type?.resolve()?.expandAliases()
  val lambdaRetKotlin: String = lambdaRetType?.declaration?.simpleName?.asString() ?: "Unit"
  val lambdaRetQualified: String =
    lambdaRetType?.declaration?.qualifiedName?.asString() ?: "kotlin.Unit"

  val outerRetType: KSType? = method.returnType?.resolve()?.expandAliases()
  val outerRetKotlin: String = outerRetType?.declaration?.simpleName?.asString() ?: "Unit"
  val outerRetQualified: String =
    outerRetType?.declaration?.qualifiedName?.asString() ?: "kotlin.Unit"

  val isOuterRetUnit: Boolean = outerRetQualified == "kotlin.Unit"
  val isOuterRetString: Boolean = outerRetQualified == "kotlin.String"
  val isOuterRetList: Boolean = outerRetQualified in setOf(
    "kotlin.collections.List", "kotlin.collections.MutableList",
  )

  // Delegate name: Nuget{Arg1}...{Return}Callback
  fun typeSuffix(kotlinType: String, qualified: String?): String = when {
    kotlinType == "Boolean" -> "Byte"
    kotlinType == "String" -> "String"
    kotlinType == "Unit" -> "Void"
    qualified != null && qualified in exportedTypes -> "Object"
    kotlinType in KOTLIN_TO_CSHARP_RETURN -> kotlinType
    else -> "Object"
  }

  val argSuffixes: List<String> = lambdaArgTypes.map { t ->
    typeSuffix(t.declaration.simpleName.asString(), t.declaration.qualifiedName?.asString())
  }
  val retSuffix: String = typeSuffix(lambdaRetKotlin, lambdaRetQualified)
  val delegateName: String = "Nuget${argSuffixes.joinToString("")}${retSuffix}Callback"

  // Delegate native return type and param list
  val delegateReturnType: String = when {
    lambdaRetKotlin == "Unit" -> "void"
    lambdaRetKotlin == "Boolean" -> "byte"
    else -> "IntPtr"
  }

  val delegateParamList: String = if (lambdaArity == 0) {
    "(IntPtr userData)"
  } else {
    val argParams: String = lambdaArgTypes
      .mapIndexed { i, _ -> "IntPtr arg${i}Ptr" }
      .joinToString(", ")
    "($argParams, IntPtr userData)"
  }

  val delegate = CirCallbackDelegate(delegateName, delegateParamList, delegateReturnType)
  if (tracker.callbackDelegates.none { it.name == delegateName }) {
    tracker.callbackDelegates.add(delegate)
  }

  // C# callback body (inside the nativeCallback lambda)
  val callbackBody: String = buildString {
    lambdaArgTypes.forEachIndexed { i, argType ->
      val argKotlin: String = argType.declaration.simpleName.asString()
      val csArgType: String = when (argKotlin) {
        "String" -> "string"
        else -> argKotlin
      }
      appendLine("            $csArgType arg$i = NugetMarshal.FromHandle<$csArgType>(arg${i}Ptr);")
    }
    val argCallNames: String = lambdaArgTypes.indices.joinToString(", ") { "arg$it" }
    val callExpr: String =
      if (lambdaArity == 0) "$lambdaParamName()" else "$lambdaParamName($argCallNames)"
    when {
      lambdaRetKotlin == "Unit" -> append("            $callExpr;")
      lambdaRetKotlin == "Boolean" -> append("            return $callExpr ? (byte)1 : (byte)0;")
      lambdaRetKotlin == "String" -> append("            return NugetMarshal.WrapString($callExpr);")
      else -> append("            return NugetMarshal.WrapString($callExpr);")
    }
  }

  // C# public method return type
  val csReturnType: String = when {
    isOuterRetUnit -> "void"
    isOuterRetString -> "string"
    isOuterRetList -> {
      val elemType = outerRetType?.arguments?.firstOrNull()?.type?.resolve()?.expandAliases()
      val elemKotlin: String = elemType?.declaration?.simpleName?.asString() ?: "string"
      val elemCs: String = KOTLIN_TO_CSHARP_PARAM[elemKotlin] ?: elemKotlin
      "IReadOnlyList<$elemCs>"
    }

    else -> outerRetKotlin
  }

  // C# param type (Func<> or Action<>)
  val csParamType: String = buildString {
    val isVoidReturn: Boolean = lambdaRetKotlin == "Unit"
    val argCsTypes: List<String> = lambdaArgTypes.map { t ->
      val k: String = t.declaration.simpleName.asString()
      KOTLIN_TO_CSHARP_PARAM[k] ?: k
    }
    if (isVoidReturn) {
      if (lambdaArity == 0) append("Action")
      else append("Action<${argCsTypes.joinToString(", ")}>")
    } else {
      val retCs: String = when {
        lambdaRetKotlin == "Boolean" -> "bool"
        lambdaRetKotlin == "String" -> "string"
        else -> KOTLIN_TO_CSHARP_PARAM[lambdaRetKotlin] ?: lambdaRetKotlin
      }
      val allTypes: List<String> = argCsTypes + retCs
      if (lambdaArity == 0) append("Func<$retCs>")
      else append("Func<${allTypes.joinToString(", ")}>")
    }
  }

  // C# wrapper body (inside try{})
  val nativeCall: String = "Native_$csMethodName(_handle, fnPtr, IntPtr.Zero, out IntPtr error)"
  val wrapperBody: String = buildString {
    when {
      isOuterRetUnit -> appendLine("            $nativeCall;")
      isOuterRetString -> appendLine("            IntPtr nativeResult = $nativeCall;")
      isOuterRetList -> appendLine("            IntPtr listHandle = $nativeCall;")
      else -> appendLine("            IntPtr nativeHandle = $nativeCall;")
    }
    appendLine("            if (error != IntPtr.Zero) throw NugetErrorNative.BuildException(error);")
    when {
      isOuterRetString -> append("            return Marshal.PtrToStringUTF8(nativeResult)!;")
      isOuterRetList -> {
        val elemType = outerRetType?.arguments?.firstOrNull()?.type?.resolve()?.expandAliases()
        val elemKotlin: String = elemType?.declaration?.simpleName?.asString() ?: "string"
        val elemCs: String = KOTLIN_TO_CSHARP_PARAM[elemKotlin] ?: elemKotlin
        appendLine("            int count = NugetListNative.Count(listHandle);")
        appendLine("            var result = new List<$elemCs>(count);")
        appendLine("            for (int i = 0; i < count; i++)")
        appendLine("            {")
        appendLine("                result.Add(NugetMarshal.FromHandle<$elemCs>(NugetListNative.Get(listHandle, i)));")
        appendLine("            }")
        appendLine("            NugetListNative.Dispose(listHandle);")
        append("            return result.AsReadOnly();")
      }

      !isOuterRetUnit -> append("            return new $outerRetKotlin(nativeHandle);")
    }
  }

  if (isOuterRetList) tracker.needsList = true

  val nativeImportReturnType: String = if (isOuterRetUnit) "void" else "IntPtr"

  return CirCallbackMethod(
    csMethodName = csMethodName,
    nativeEntryPoint = nativeEntryPoint,
    libraryName = libraryName,
    nativeImportReturnType = nativeImportReturnType,
    lambdaParamName = lambdaParamName,
    delegateName = delegateName,
    delegateParamList = delegateParamList,
    csReturnType = csReturnType,
    csParamType = csParamType,
    callbackBody = callbackBody,
    wrapperBody = wrapperBody,
  )
}

/**
 * Translates an `add{X}` / `remove{X}` method pair into a [CirStoredCallbackMethod].
 * The add method becomes a public `IDisposable Add{X}(Action<T> listener)` on the C# side;
 * the remove method is consumed internally by the generated NugetSubscription dispose action.
 *
 * Enum lambda arg types are passed as `int` (ordinal) at the C boundary rather than StableRef handles.
 *
 * @see <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/037-stored-callbacks.md">ADR-037: Stored callbacks</a>
 */
private fun translateStoredCallbackMethod(
  addMethod: KSFunctionDeclaration,
  removeMethod: KSFunctionDeclaration,
  libraryName: String,
  classPrefix: String,
  exportedTypes: Set<String>,
  tracker: CollectionHelperTracker,
): CirStoredCallbackMethod? {
  val addMethodName: String = addMethod.simpleName.asString()
  val removeMethodName: String = removeMethod.simpleName.asString()
  val csMethodName: String = addMethodName.replaceFirstChar { it.uppercase() }
  val csRemoveNativeName: String = "Native_${removeMethodName.replaceFirstChar { it.uppercase() }}"

  val lambdaParam = addMethod.parameters.firstOrNull { param ->
    param.type.resolve().expandAliases().declaration.qualifiedName?.asString() in LAMBDA_TYPES
  } ?: return null

  val lambdaType: KSType = lambdaParam.type.resolve().expandAliases()
  val lambdaArgTypes: List<KSType> = lambdaType.arguments.dropLast(1)
    .mapNotNull { it.type?.resolve()?.expandAliases() }
  val lambdaArity: Int = lambdaArgTypes.size

  // For enum args: ordinal Int. For reference types: IntPtr.
  val isEnumArgs: List<Boolean> = lambdaArgTypes.map { argType ->
    (argType.declaration as? KSClassDeclaration)?.classKind == ClassKind.ENUM_CLASS
  }

  // Delegate naming for stored callbacks: enum ordinal -> "Int" suffix, object -> "Object" suffix.
  fun storedArgSuffix(argType: KSType, isEnum: Boolean): String = when {
    isEnum -> "Int"
    argType.declaration.simpleName.asString() == "Boolean" -> "Byte"
    argType.declaration.simpleName.asString() == "String" -> "Object"
    argType.declaration.qualifiedName?.asString() in KOTLIN_TO_CSHARP_RETURN ->
      argType.declaration.simpleName.asString()

    else -> "Object"
  }

  val argSuffixes: List<String> = lambdaArgTypes.mapIndexed { i, t -> storedArgSuffix(t, isEnumArgs[i]) }
  val delegateName: String = "Nuget${argSuffixes.joinToString("")}VoidCallback"

  // Delegate parameter list: enum -> int arg0Ord, object -> IntPtr arg0Ptr, arity-0 -> just userData
  val delegateParamList: String = if (lambdaArity == 0) {
    "(IntPtr _)"
  } else {
    val argParams: String = lambdaArgTypes.mapIndexed { i, argType ->
      if (isEnumArgs[i]) "int arg${i}Ord" else "IntPtr arg${i}Ptr"
    }.joinToString(", ")
    "($argParams, IntPtr _)"
  }

  val delegate = CirCallbackDelegate(delegateName, delegateParamList, "void")
  if (tracker.callbackDelegates.none { it.name == delegateName }) {
    tracker.callbackDelegates.add(delegate)
  }
  tracker.needsSubscription = true

  // C# param type for the public method: Action or Action<T1, T2, ...>
  val csParamType: String = buildString {
    val argCsTypes: List<String> = lambdaArgTypes.mapIndexed { i, t ->
      if (isEnumArgs[i]) t.declaration.simpleName.asString()
      else KOTLIN_TO_CSHARP_PARAM[t.declaration.simpleName.asString()] ?: t.declaration.simpleName.asString()
    }
    if (lambdaArity == 0) append("Action")
    else append("Action<${argCsTypes.joinToString(", ")}>")
  }

  // nativeCallback body (inside the lambda assigned to the delegate variable)
  val nativeCallbackBody: String = buildString {
    lambdaArgTypes.forEachIndexed { i, argType ->
      val simpleName: String = argType.declaration.simpleName.asString()
      val csType: String = if (isEnumArgs[i]) simpleName
      else KOTLIN_TO_CSHARP_PARAM[simpleName] ?: simpleName
      if (isEnumArgs[i]) append("$csType arg$i = ($csType)arg${i}Ord; ")
      else append("$csType arg$i = NugetMarshal.FromHandle<$csType>(arg${i}Ptr); NugetMarshal.Dispose(arg${i}Ptr); ")
    }
    val callArgs: String = if (lambdaArity == 0) "" else
      lambdaArgTypes.indices.joinToString(", ") { "arg$it" }
    append("listener($callArgs);")
  }

  return CirStoredCallbackMethod(
    csMethodName = csMethodName,
    csRemoveNativeName = csRemoveNativeName,
    subscribeEntryPoint = "${classPrefix}_$addMethodName",
    removeEntryPoint = "${classPrefix}_$removeMethodName",
    libraryName = libraryName,
    delegateName = delegateName,
    delegateParamList = delegateParamList,
    csParamType = csParamType,
    nativeCallbackBody = nativeCallbackBody,
  )
}

/**
 * Translates an `add{X}` / `remove{X}` pair (where the parameter is a Kotlin interface type) into
 * a [CirInterfaceBridgeMethod]. The add method becomes a public `IDisposable Add{X}(IFace listener)`
 * on the C# side; the remove method is consumed internally by the generated NugetSubscription.
 *
 * @see <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/039-interface-bridging.md">ADR-039: Interface bridging</a>
 */
private fun translateInterfaceBridgeMethod(
  addMethod: KSFunctionDeclaration,
  removeMethod: KSFunctionDeclaration,
  libraryName: String,
  classPrefix: String,
  className: String,
  tracker: CollectionHelperTracker,
): CirInterfaceBridgeMethod? {
  val addMethodName: String = addMethod.simpleName.asString()
  val removeMethodName: String = removeMethod.simpleName.asString()
  val csMethodName: String = addMethodName.replaceFirstChar { it.uppercase() }
  val csRemoveNativeName: String = "Native_${removeMethodName.replaceFirstChar { it.uppercase() }}"

  val ifaceParam = addMethod.parameters.firstOrNull { param ->
    (param.type.resolve().expandAliases().declaration as? KSClassDeclaration)
      ?.classKind == ClassKind.INTERFACE
  } ?: return null

  val ifaceDecl = ifaceParam.type.resolve().expandAliases().declaration as? KSClassDeclaration ?: return null
  val ifaceName: String = ifaceDecl.simpleName.asString()
  val interfaceCsName: String = "I$ifaceName"

  val ifaceMethods: List<KSFunctionDeclaration> = ifaceDecl.getAllFunctions()
    .filter { it.getVisibility() == Visibility.PUBLIC }
    .filter { it.simpleName.asString() !in listOf("equals", "hashCode", "toString", "<init>") }
    .toList()

  tracker.needsSubscription = true

  val entries: List<CirInterfaceBridgeMethodEntry> = ifaceMethods.map { method ->
    val mName: String = method.simpleName.asString()
    val mCsName: String = mName.replaceFirstChar { it.uppercase() }
    val params = method.parameters.toList()
    val arity: Int = params.size

    // Delegate suffix naming follows stored-callback convention
    val argSuffixes: List<String> = params.map { param ->
      val pType = param.type.resolve().expandAliases()
      val pSimple: String = pType.declaration.simpleName.asString()
      val pQualified: String = pType.declaration.qualifiedName?.asString() ?: ""
      val isEnum: Boolean = (pType.declaration as? KSClassDeclaration)?.classKind == ClassKind.ENUM_CLASS
      val isPrimitive: Boolean = pQualified.startsWith("kotlin.") && pSimple != "String"
      when {
        isEnum -> "Int"
        pSimple == "Boolean" -> "Byte"
        isPrimitive -> pSimple
        else -> "Object"
      }
    }
    val delegateName: String = "Nuget${argSuffixes.joinToString("")}VoidCallback"

    // Delegate param list: enum -> int arg${i}Ord, reference/string -> IntPtr arg${i}Ptr, arity-0 -> IntPtr _
    val delegateParamList: String = if (arity == 0) {
      "(IntPtr _)"
    } else {
      val argParams: String = params.mapIndexed { i, param ->
        val pType = param.type.resolve().expandAliases()
        val pSimple: String = pType.declaration.simpleName.asString()
        val pQualified: String = pType.declaration.qualifiedName?.asString() ?: ""
        val isEnum: Boolean = (pType.declaration as? KSClassDeclaration)?.classKind == ClassKind.ENUM_CLASS
        val isPrimitive: Boolean = pQualified.startsWith("kotlin.") && pSimple != "String"
        when {
          isEnum -> "int arg${i}Ord"
          pSimple == "Boolean" -> "byte arg${i}"
          isPrimitive -> "${KOTLIN_TO_CSHARP_PARAM[pSimple] ?: "int"} arg${i}"
          else -> "IntPtr arg${i}Ptr"
        }
      }.joinToString(", ")
      "($argParams, IntPtr _)"
    }

    val delegate = CirCallbackDelegate(delegateName, delegateParamList, "void")
    if (tracker.callbackDelegates.none { it.name == delegateName }) {
      tracker.callbackDelegates.add(delegate)
    }

    // C# callback body: unmarshal args and call listener.Method(args)
    val callbackBody: String = buildString {
      params.forEachIndexed { i, param ->
        val pType = param.type.resolve().expandAliases()
        val pSimple: String = pType.declaration.simpleName.asString()
        val pQualified: String = pType.declaration.qualifiedName?.asString() ?: ""
        val isEnum: Boolean = (pType.declaration as? KSClassDeclaration)?.classKind == ClassKind.ENUM_CLASS
        val isPrimitive: Boolean = pQualified.startsWith("kotlin.") && pSimple != "String"
        val csType: String = when {
          isEnum -> pSimple
          pSimple == "Boolean" -> "bool"
          isPrimitive -> KOTLIN_TO_CSHARP_PARAM[pSimple] ?: pSimple
          pSimple == "String" -> "string"
          else -> pSimple
        }
        when {
          isEnum -> append("$csType arg$i = ($csType)arg${i}Ord; ")
          pSimple == "Boolean" -> append("$csType arg$i = arg${i} != 0; ")
          isPrimitive -> { /* arg is already the right type, no unmarshal needed */
          }

          else -> append("$csType arg$i = NugetMarshal.FromHandle<$csType>(arg${i}Ptr); NugetMarshal.Dispose(arg${i}Ptr); ")
        }
      }
      val callArgs: String = params.indices.joinToString(", ") { "arg$it" }
      append("listener.$mCsName($callArgs);")
    }

    CirInterfaceBridgeMethodEntry(
      methodCsName = mCsName,
      methodKtName = mName,
      delegateName = delegateName,
      delegateParamList = delegateParamList,
      callbackBody = callbackBody,
    )
  }

  return CirInterfaceBridgeMethod(
    csMethodName = csMethodName,
    csRemoveNativeName = csRemoveNativeName,
    subscribeEntryPoint = "${classPrefix}_$addMethodName",
    removeEntryPoint = "${classPrefix}_$removeMethodName",
    libraryName = libraryName,
    interfaceCsName = interfaceCsName,
    className = className,
    entries = entries,
  )
}
