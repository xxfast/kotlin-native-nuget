package io.github.xxfast.kotlin.native.nuget.processor.cir

import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Visibility
import io.github.xxfast.kotlin.native.nuget.processor.toCName

internal fun translateClass(
  cls: KSClassDeclaration,
  libraryName: String,
  tracker: CollectionHelperTracker,
  exportedTypes: Set<String>,
  logger: KSPLogger,
): CirClass {
  val name: String = cls.simpleName.asString()
  val prefix: String = name.lowercase()
  val isDataClass: Boolean = cls.modifiers.contains(Modifier.DATA)
  val isAbstract: Boolean = cls.modifiers.contains(Modifier.ABSTRACT)

  val superClass: String? = cls.superTypes
    .map { it.resolve().declaration }
    .filterIsInstance<KSClassDeclaration>()
    .firstOrNull { decl ->
      decl.classKind == com.google.devtools.ksp.symbol.ClassKind.CLASS &&
        decl.qualifiedName?.asString() != "kotlin.Any"
    }
    ?.simpleName?.asString()

  val interfaces: List<String> = if (superClass != null) {
    emptyList()
  } else {
    cls.superTypes
      .map { it.resolve().declaration }
      .filterIsInstance<KSClassDeclaration>()
      .filter { it.classKind == com.google.devtools.ksp.symbol.ClassKind.INTERFACE }
      .map { "I${it.simpleName.asString()}" }
      .toList()
  }

  val constructor = cls.primaryConstructor
  val cirConstructor: CirConstructor? = if (constructor != null) {
    val ctorParams: List<CirParameter> = constructor.parameters.map { param ->
      val resolved: KSType = param.type.resolve().expandAliases()
      val kotlinType: String = resolved.declaration.simpleName.asString()
      CirParameter(param.name?.asString() ?: "_", mapParamType(kotlinType))
    }

    CirConstructor(
      parameters = ctorParams,
      body = "_handle = Native_Create(${ctorParams.joinToString(", ") { it.name }});",
    )
  } else null

  val properties: List<CirProperty> = cls.getAllProperties()
    .filter { it.getVisibility() == Visibility.PUBLIC }
    .filter { prop ->
      if (superClass == null) return@filter true
      prop.parentDeclaration == cls
    }
    .mapNotNull { prop ->
      val propName: String = prop.simpleName.asString()
      val propTypeResolved: KSType = prop.type.resolve().expandAliases()
      val propType: String = propTypeResolved.declaration.simpleName.asString()
      val isNullable: Boolean = propTypeResolved.isMarkedNullable
      val isMutable: Boolean = prop.isMutable
      val csPropName: String = propName.replaceFirstChar { it.uppercase() }

      val isEnumType: Boolean = (propTypeResolved.declaration as? KSClassDeclaration)
        ?.classKind == com.google.devtools.ksp.symbol.ClassKind.ENUM_CLASS

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

      val isSuspendLambdaType: Boolean = qualifiedTypeName in SUSPEND_LAMBDA_TYPES
      val suspendLambdaArity: Int = if (isSuspendLambdaType) propTypeResolved.arguments.size - 1 else -1

      if (isSuspendLambdaType) {
        tracker.suspendLambdaArities.add(suspendLambdaArity)
        tracker.needsAsync = true
      }

      val isFlowType: Boolean = qualifiedTypeName in FLOW_TYPES
      val flowElementType: String? = if (isFlowType) {
        val elementType = propTypeResolved.arguments.firstOrNull()?.type?.resolve()
        val elementTypeName: String = elementType?.declaration?.simpleName?.asString() ?: "Any"
        KOTLIN_TO_CSHARP_PARAM[elementTypeName] ?: elementTypeName
      } else null

      if (isFlowType) {
        tracker.needsFlow = true
        tracker.needsAsync = true
      }

      val isReferenceType: Boolean = propType !in KOTLIN_TO_CSHARP_RETURN && !isEnumType &&
        !isListType && !isMutableListType && !isMapType && !isMutableMapType &&
        !isSetType && !isMutableSetType && !isLambdaType && !isSuspendLambdaType &&
        !isFlowType

      if (isReferenceType) {
        if (qualifiedTypeName != null && qualifiedTypeName !in exportedTypes) {
          logger.warn("Skipping property '${cls.simpleName.asString()}.$propName': unsupported type '$qualifiedTypeName'")
          return@mapNotNull null
        }
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

      val isNullablePrimitive: Boolean = isNullable && !isReferenceType && !isEnumType &&
        !isListType && !isMutableListType && !isMapType && !isMutableMapType &&
        !isSetType && !isMutableSetType && !isLambdaType && !isSuspendLambdaType
      val isNullableString: Boolean = isNullablePrimitive && propType == "String"
      val isNullableValueType: Boolean = isNullablePrimitive && propType != "String"

      val nativeReturnType: String = when {
        isLambdaType -> "IntPtr"
        isSuspendLambdaType -> "IntPtr"
        isFlowType -> "IntPtr"
        (isListType || isMutableListType) -> "IntPtr"
        (isMapType || isMutableMapType) -> "IntPtr"
        (isSetType || isMutableSetType) -> "IntPtr"
        isEnumType -> "int"
        isReferenceType -> "IntPtr"
        isNullableString -> "IntPtr"
        isNullableValueType -> "bool"
        else -> mapReturnType(propType)
      }

      val type: String = when {
        isLambdaType -> lambdaCsType
        isSuspendLambdaType -> suspendLambdaCsType
        isFlowType -> "KotlinFlow<$flowElementType>"
        isListType -> "IReadOnlyList<$listElementType>"
        isMutableListType -> "IList<$listElementType>"
        isMapType -> "IReadOnlyDictionary<$mapKeyType, $mapValueType>"
        isMutableMapType -> "IDictionary<$mapKeyType, $mapValueType>"
        isSetType -> "IReadOnlySet<$setElementType>"
        isMutableSetType -> "ISet<$setElementType>"
        isNullableString -> "string?"
        isNullableValueType -> "${mapParamType(propType)}?"
        propType == "String" -> "string"
        isEnumType -> propType
        isReferenceType && isNullable -> "$propType?"
        isReferenceType -> propType
        else -> mapReturnType(propType)
      }

      val extraNatives: MutableList<CirExtraNative> = mutableListOf()

      if (isNullableValueType) {
        val csValueType: String = mapParamType(propType)
        extraNatives.add(CirExtraNative(
          entryPointSuffix = "get_${propName}_value",
          returnType = csValueType,
          name = "Native_Get_${propName}_value",
          hasSyncErrorOut = true,
        ))
        if (isMutable) {
          extraNatives.add(CirExtraNative(
            entryPointSuffix = "set_${propName}_null",
            returnType = "void",
            name = "Native_Set_${propName}_null",
            hasSyncErrorOut = true,
          ))
        }
      }

      val getter: String = if (isLambdaType) {
        "new $lambdaCsType(Native_Get_$propName(_handle))"
      } else if (isSuspendLambdaType) {
        "new $suspendLambdaCsType(Native_Get_$propName(_handle))"
      } else if (isFlowType) {
        val collectNativeName = "Native_Get${csPropName}Collect"
        buildString {
          appendLine()
          appendLine("                if (_handle == IntPtr.Zero)")
          appendLine("                    throw new ObjectDisposedException(nameof(${cls.simpleName.asString()}));")
          appendLine("                return new KotlinFlow<$flowElementType>((onNext, onComplete, onError, userData) =>")
          appendLine("                    $collectNativeName(_handle, GetOrCreateScope(), onNext, onComplete, onError, userData));")
          append("            ")
        }
      } else if (isListType) {
        buildString {
          appendLine()
          appendLine("                IntPtr listHandle = Native_Get_$propName(_handle, out IntPtr error);")
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
          append("                return result.AsReadOnly();")
        }
      } else if (isMutableListType) {
        buildString {
          appendLine()
          appendLine("                IntPtr listHandle = Native_Get_$propName(_handle, out IntPtr error);")
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
          append("                return result;")
        }
      } else if (isMapType || isMutableMapType) {
        buildString {
          appendLine()
          appendLine("                IntPtr mapHandle = Native_Get_$propName(_handle, out IntPtr error);")
          appendLine("                if (error != IntPtr.Zero)")
          appendLine("                {")
          appendLine("                    throw NugetErrorNative.BuildException(error);")
          appendLine("                }")
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
          appendLine("                IntPtr setHandle = Native_Get_$propName(_handle, out IntPtr error);")
          appendLine("                if (error != IntPtr.Zero)")
          appendLine("                {")
          appendLine("                    throw NugetErrorNative.BuildException(error);")
          appendLine("                }")
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
        isNullableString -> buildString {
          appendLine()
          appendLine("                IntPtr nativeResult = Native_Get_$propName(_handle, out IntPtr error);")
          appendLine("                if (error != IntPtr.Zero)")
          appendLine("                {")
          appendLine("                    throw NugetErrorNative.BuildException(error);")
          appendLine("                }")
          append("                return Marshal.PtrToStringUTF8(nativeResult);")
        }
        isNullableValueType -> buildString {
          appendLine()
          appendLine("                bool hasValue = Native_Get_$propName(_handle, out IntPtr error);")
          appendLine("                if (error != IntPtr.Zero)")
          appendLine("                {")
          appendLine("                    throw NugetErrorNative.BuildException(error);")
          appendLine("                }")
          appendLine("                if (!hasValue) return null;")
          appendLine("                ${mapParamType(propType)} value = Native_Get_${propName}_value(_handle, out IntPtr error2);")
          appendLine("                if (error2 != IntPtr.Zero)")
          appendLine("                {")
          appendLine("                    throw NugetErrorNative.BuildException(error2);")
          appendLine("                }")
          append("                return value;")
        }
        propType == "String" -> buildString {
          appendLine()
          appendLine("                IntPtr nativeResult = Native_Get_$propName(_handle, out IntPtr error);")
          appendLine("                if (error != IntPtr.Zero)")
          appendLine("                {")
          appendLine("                    throw NugetErrorNative.BuildException(error);")
          appendLine("                }")
          append("                return Marshal.PtrToStringUTF8(nativeResult)!;")
        }
        isEnumType -> buildString {
          appendLine()
          appendLine("                int nativeResult = Native_Get_$propName(_handle, out IntPtr error);")
          appendLine("                if (error != IntPtr.Zero)")
          appendLine("                {")
          appendLine("                    throw NugetErrorNative.BuildException(error);")
          appendLine("                }")
          append("                return ($propType)nativeResult;")
        }
        isReferenceType && isNullable -> buildString {
          appendLine()
          appendLine("                IntPtr nativeResult = Native_Get_$propName(_handle, out IntPtr error);")
          appendLine("                if (error != IntPtr.Zero)")
          appendLine("                {")
          appendLine("                    throw NugetErrorNative.BuildException(error);")
          appendLine("                }")
          append("                return nativeResult == IntPtr.Zero ? null : new $propType(nativeResult);")
        }
        isReferenceType -> buildString {
          appendLine()
          appendLine("                IntPtr nativeResult = Native_Get_$propName(_handle, out IntPtr error);")
          appendLine("                if (error != IntPtr.Zero)")
          appendLine("                {")
          appendLine("                    throw NugetErrorNative.BuildException(error);")
          appendLine("                }")
          append("                return new $propType(nativeResult);")
        }
        else -> buildString {
          appendLine()
          appendLine("                ${nativeReturnType} result = Native_Get_$propName(_handle, out IntPtr error);")
          appendLine("                if (error != IntPtr.Zero)")
          appendLine("                {")
          appendLine("                    throw NugetErrorNative.BuildException(error);")
          appendLine("                }")
          append("                return result;")
        }
      }

      val hasSyncErrorOut: Boolean = !isLambdaType && !isSuspendLambdaType && !isFlowType

      val setter: String? = if (isMutable && !isLambdaType && !isSuspendLambdaType && !isListType && !isMutableListType && !isMapType && !isMutableMapType && !isSetType && !isMutableSetType) {
        when {
          isNullableString -> buildString {
            appendLine()
            appendLine("                Native_Set_$propName(_handle, value, out IntPtr error);")
            appendLine("                if (error != IntPtr.Zero)")
            appendLine("                {")
            appendLine("                    throw NugetErrorNative.BuildException(error);")
            append("                }")
          }
          isNullableValueType -> buildString {
            appendLine()
            appendLine("                if (value.HasValue)")
            appendLine("                {")
            appendLine("                    Native_Set_$propName(_handle, value.Value, out IntPtr error);")
            appendLine("                    if (error != IntPtr.Zero)")
            appendLine("                    {")
            appendLine("                        throw NugetErrorNative.BuildException(error);")
            appendLine("                    }")
            appendLine("                }")
            appendLine("                else")
            appendLine("                {")
            appendLine("                    Native_Set_${propName}_null(_handle, out IntPtr error);")
            appendLine("                    if (error != IntPtr.Zero)")
            appendLine("                    {")
            appendLine("                        throw NugetErrorNative.BuildException(error);")
            appendLine("                    }")
            append("                }")
          }
          propType == "String" -> buildString {
            appendLine()
            appendLine("                Native_Set_$propName(_handle, value, out IntPtr error);")
            appendLine("                if (error != IntPtr.Zero)")
            appendLine("                {")
            appendLine("                    throw NugetErrorNative.BuildException(error);")
            append("                }")
          }
          isEnumType -> buildString {
            appendLine()
            appendLine("                Native_Set_$propName(_handle, (int)value, out IntPtr error);")
            appendLine("                if (error != IntPtr.Zero)")
            appendLine("                {")
            appendLine("                    throw NugetErrorNative.BuildException(error);")
            append("                }")
          }
          isReferenceType && isNullable -> buildString {
            appendLine()
            appendLine("                Native_Set_$propName(_handle, value?._handle ?? IntPtr.Zero, out IntPtr error);")
            appendLine("                if (error != IntPtr.Zero)")
            appendLine("                {")
            appendLine("                    throw NugetErrorNative.BuildException(error);")
            append("                }")
          }
          isReferenceType -> buildString {
            appendLine()
            appendLine("                Native_Set_$propName(_handle, value._handle, out IntPtr error);")
            appendLine("                if (error != IntPtr.Zero)")
            appendLine("                {")
            appendLine("                    throw NugetErrorNative.BuildException(error);")
            append("                }")
          }
          else -> buildString {
            appendLine()
            appendLine("                Native_Set_$propName(_handle, value, out IntPtr error);")
            appendLine("                if (error != IntPtr.Zero)")
            appendLine("                {")
            appendLine("                    throw NugetErrorNative.BuildException(error);")
            append("                }")
          }
        }
      } else null

      val nativeSetterType: String = when {
        isNullableValueType -> mapReturnType(propType)
        isNullableString -> "string"
        else -> nativeReturnType
      }

      CirProperty(
        name = csPropName,
        type = type,
        nativeReturnType = nativeReturnType,
        nativeSetterType = nativeSetterType,
        nativeName = propName,
        getter = getter,
        setter = setter,
        extraNatives = extraNatives,
        isFlow = isFlowType,
        flowElementType = flowElementType ?: "",
        hasSyncErrorOut = hasSyncErrorOut,
      )
    }.toList()

  val allMethods = cls.getAllFunctions().toList()

  val filteredMethods: List<KSFunctionDeclaration> = allMethods
    .filter { it.getVisibility() == Visibility.PUBLIC }
    .filter { method ->
      val methodName: String = method.simpleName.asString()
      val isDataClassMethod: Boolean = isDataClass &&
        (methodName == "copy" || methodName.startsWith("component"))
      if (methodName in listOf("equals", "hashCode", "toString", "<init>") || isDataClassMethod) return@filter false

      if (superClass != null) {
        method.parentDeclaration == cls
      } else {
        true
      }
    }

  val (suspendMethods, regularMethods) = filteredMethods.partition { it.modifiers.contains(Modifier.SUSPEND) }

  val (flowMethods, normalMethods) = regularMethods.partition { method ->
    val returnQualified: String? = method.returnType?.resolve()?.expandAliases()
      ?.declaration?.qualifiedName?.asString()
    returnQualified in FLOW_TYPES
  }

  if (flowMethods.isNotEmpty()) {
    tracker.needsFlow = true
    tracker.needsAsync = true
  }

  val methods: List<CirMethod> = normalMethods
    .map { method ->
      val methodName: String = method.simpleName.asString()
      val methodReturn: String = method.returnType?.resolve()?.expandAliases()
        ?.declaration?.simpleName?.asString() ?: "Unit"

      val csMethodName: String = methodName.replaceFirstChar { it.uppercase() }

      val methodParams: List<CirParameter> = method.parameters.map { param ->
        val resolved: KSType = param.type.resolve().expandAliases()
        val kotlinType: String = resolved.declaration.simpleName.asString()
        CirParameter(param.name?.asString() ?: "_", mapParamType(kotlinType))
      }

      val declaredInThisClass: Boolean = method.parentDeclaration == cls
      val hasImplementation: Boolean = declaredInThisClass || method.modifiers.contains(Modifier.OVERRIDE)
      val isMethodAbstract: Boolean = !hasImplementation && (isAbstract || method.modifiers.contains(Modifier.ABSTRACT))
      val isOverride: Boolean = superClass != null && method.modifiers.contains(Modifier.OVERRIDE)

      val nativeCallArgs: String = (listOf("_handle") +
        methodParams.map { it.name }).joinToString(", ")

      val body: String = if (methodReturn == "String") {
        "Marshal.PtrToStringUTF8(Native_$csMethodName($nativeCallArgs))!"
      } else {
        "Native_$csMethodName($nativeCallArgs)"
      }

      val returnType: String = if (methodReturn == "String") "string"
      else if (methodReturn == "Unit") "void"
      else mapReturnType(methodReturn)

      CirMethod(
        name = csMethodName,
        returnType = returnType,
        nativeReturnType = mapReturnType(methodReturn),
        nativeName = methodName,
        parameters = methodParams,
        body = body,
        isAbstract = isMethodAbstract,
        isOverride = isOverride,
        isSyncErrorCheckEnabled = !isMethodAbstract,
      )
    }.toList()

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

  val flowMembers: List<CirMember> = flowMethods.flatMap { method ->
    val methodName: String = method.simpleName.asString()
    val cname: String = toCName(methodName)
    val csMethodName: String = methodName.replaceFirstChar { it.uppercase() }
    val returnType = method.returnType?.resolve()?.expandAliases()
    val flowElementKotlinType: String = returnType?.arguments
      ?.firstOrNull()?.type?.resolve()
      ?.declaration?.simpleName?.asString() ?: "Any"
    val flowCsElementType: String = KOTLIN_TO_CSHARP_PARAM[flowElementKotlinType] ?: flowElementKotlinType

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
        translateCompanionProperty(prop, libraryName, prefix)
      }
      .toList()

    val companionFunctions: List<CirMember> = companion.getAllFunctions()
      .filter { it.getVisibility() == Visibility.PUBLIC }
      .filter { it.simpleName.asString() !in listOf("equals", "hashCode", "toString", "<init>") }
      .flatMap { func ->
        translateCompanionFunction(func, libraryName, prefix, name)
      }
      .toList()

    companionConsts + companionProperties + companionFunctions
  } else emptyList()

  return CirClass(
    name = name,
    libraryName = libraryName,
    nativePrefix = prefix,
    constructor = cirConstructor,
    properties = properties,
    methods = methods,
    interfaces = interfaces,
    superClass = superClass,
    isDataClass = isDataClass,
    isAbstract = isAbstract,
    companionMembers = companionMembers + asyncMembers + flowMembers,
    hasSuspendMethods = cls.getAllFunctions().any { it.modifiers.contains(Modifier.SUSPEND) } ||
      flowMethods.isNotEmpty() ||
      cls.getAllProperties().any { prop ->
        prop.type.resolve().expandAliases().declaration.qualifiedName?.asString() in FLOW_TYPES
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

    if (param.variance != com.google.devtools.ksp.symbol.Variance.INVARIANT) {
      logger.warn(
        "Variance '${param.variance}' on class '${cls.simpleName.asString()}' " +
          "type parameter '${param.name.asString()}' will be dropped — " +
          "C# does not support variance on classes"
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
      val isDataObject: Boolean = isDataClass && subclass.classKind == com.google.devtools.ksp.symbol.ClassKind.OBJECT

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
              ?.classKind == com.google.devtools.ksp.symbol.ClassKind.ENUM_CLASS

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

            val isReferenceType: Boolean = propType !in KOTLIN_TO_CSHARP_RETURN && !isEnumType && !isListType && !isMutableListType && !isMapType && !isMutableMapType && !isSetType && !isMutableSetType && !isLambdaType

            if (isReferenceType && qualifiedTypeName != null && qualifiedTypeName !in exportedTypes) {
              logger.warn("Skipping property '${cls.simpleName.asString()}.${subName}.$propName': unsupported type '$qualifiedTypeName'")
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
): CirObject {
  val name: String = obj.simpleName.asString()
  val prefix: String = name.lowercase()

  val methods: List<CirDllImport> = obj.getAllFunctions()
    .filter { it.getVisibility() == Visibility.PUBLIC }
    .filter { it.simpleName.asString() !in listOf("equals", "hashCode", "toString", "<init>") }
    .map { method ->
      val methodName: String = method.simpleName.asString()
      val cname: String = toCName(methodName)
      val returnType = method.returnType?.resolve()?.expandAliases()
      val kotlinReturnType: String = returnType?.declaration?.simpleName?.asString() ?: "Unit"

      val params: List<CirParameter> = method.parameters.map { param ->
        val resolved: KSType = param.type.resolve().expandAliases()
        val kotlinType: String = resolved.declaration.simpleName.asString()
        CirParameter(param.name?.asString() ?: "_", mapParamType(kotlinType))
      }

      val entryPoint: String = "${prefix}_${cname}"

      CirDllImport(
        libraryName = libraryName,
        entryPoint = entryPoint,
        returnType = mapReturnType(kotlinReturnType),
        name = methodName,
        parameters = params,
      )
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

  members.add(CirDllImport(
    libraryName = libraryName,
    entryPoint = "${classPrefix}_companion_get_$propName",
    returnType = csNativeReturnType,
    name = "Native_Companion_Get_$propName",
    parameters = emptyList(),
    visibility = CirVisibility.PRIVATE,
  ))

  val csType: String
  val getter: String
  val setter: String?

  if (propType == "String") {
    csType = "string"
    getter = "Marshal.PtrToStringUTF8(Native_Companion_Get_$propName())!"
    setter = if (isMutable) "Native_Companion_Set_$propName(value)" else null

    if (isMutable) {
      members.add(CirDllImport(
        libraryName = libraryName,
        entryPoint = "${classPrefix}_companion_set_$propName",
        returnType = "void",
        name = "Native_Companion_Set_$propName",
        parameters = listOf(CirParameter("value", "string")),
        visibility = CirVisibility.PRIVATE,
      ))
    }
  } else {
    csType = csNativeReturnType
    getter = "Native_Companion_Get_$propName()"
    setter = if (isMutable) "Native_Companion_Set_$propName(value)" else null

    if (isMutable) {
      members.add(CirDllImport(
        libraryName = libraryName,
        entryPoint = "${classPrefix}_companion_set_$propName",
        returnType = "void",
        name = "Native_Companion_Set_$propName",
        parameters = listOf(CirParameter("value", csNativeReturnType)),
        visibility = CirVisibility.PRIVATE,
      ))
    }
  }

  members.add(CirProperty(
    name = csPropName,
    type = csType,
    nativeReturnType = csNativeReturnType,
    nativeName = propName,
    getter = getter,
    setter = setter,
    isStatic = true,
  ))

  return members
}

internal fun translateCompanionFunction(
  func: KSFunctionDeclaration,
  libraryName: String,
  classPrefix: String,
  className: String,
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

  val returnsEnclosingClass: Boolean = kotlinReturnType == className
  val isObjectReturn: Boolean = returnsEnclosingClass ||
    (kotlinReturnType !in KOTLIN_TO_CSHARP_RETURN && kotlinReturnType != "Unit")

  if (isObjectReturn) {
    val nativeImport = CirDllImport(
      libraryName = libraryName,
      entryPoint = entryPoint,
      returnType = "IntPtr",
      name = "Native_Companion_$csMethodName",
      parameters = params,
      visibility = CirVisibility.PRIVATE,
    )

    val paramNames: String = params.joinToString(", ") { it.name }
    val wrapper = CirMethod(
      name = csMethodName,
      returnType = kotlinReturnType,
      parameters = params,
      body = "new $kotlinReturnType(Native_Companion_$csMethodName($paramNames))",
      isStatic = true,
    )

    return listOf(nativeImport, wrapper)
  }

  if (kotlinReturnType == "String") {
    val nativeImport = CirDllImport(
      libraryName = libraryName,
      entryPoint = entryPoint,
      returnType = "IntPtr",
      name = "Native_Companion_$csMethodName",
      parameters = params,
      visibility = CirVisibility.PRIVATE,
    )

    val paramNames: String = params.joinToString(", ") { it.name }
    val wrapper = CirMethod(
      name = csMethodName,
      returnType = "string",
      parameters = params,
      body = "Marshal.PtrToStringUTF8(Native_Companion_$csMethodName($paramNames))!",
      isStatic = true,
    )

    return listOf(nativeImport, wrapper)
  }

  val csReturnType: String = if (kotlinReturnType == "Unit") "void" else mapReturnType(kotlinReturnType)

  return listOf(
    CirDllImport(
      libraryName = libraryName,
      entryPoint = entryPoint,
      returnType = csReturnType,
      name = "Native_Companion_$csMethodName",
      parameters = params,
      visibility = CirVisibility.PUBLIC,
    )
  )
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
      com.google.devtools.ksp.symbol.Variance.COVARIANT -> CirVariance.COVARIANT
      com.google.devtools.ksp.symbol.Variance.CONTRAVARIANT -> CirVariance.CONTRAVARIANT
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

  return CirEnum(name, entries, properties)
}

internal fun translateValueClass(
  cls: KSClassDeclaration,
  libraryName: String,
): CirValueClass {
  val name: String = cls.simpleName.asString()
  val prefix: String = name.lowercase()

  val underlyingParamName: String = cls.primaryConstructor!!.parameters.first().name!!.asString()
  val underlyingProp: KSPropertyDeclaration = cls.getAllProperties()
    .first { it.simpleName.asString() == underlyingParamName }

  val underlyingResolved: KSType = underlyingProp.type.resolve().expandAliases()
  val underlyingType: String = underlyingResolved.declaration.simpleName.asString()
  val underlyingName: String = underlyingProp.simpleName.asString().replaceFirstChar { it.uppercase() }
  val underlyingNativeType: String = mapParamType(underlyingType)
  val isReferenceUnderlying: Boolean = underlyingType !in KOTLIN_TO_CSHARP_PARAM

  val nativeArg: String = if (isReferenceUnderlying) "${underlyingName}._handle" else underlyingName

  val secondaryConstructors: List<CirValueClassConstructor> = cls.declarations
    .filterIsInstance<KSFunctionDeclaration>()
    .filter { it.simpleName.asString() == "<init>" }
    .filter { it != cls.primaryConstructor }
    .mapIndexed { index, ctor ->
      val params: List<CirParameter> = ctor.parameters.map { param ->
        val resolved: KSType = param.type.resolve().expandAliases()
        val kotlinType: String = resolved.declaration.simpleName.asString()
        CirParameter(param.name?.asString() ?: "_", mapParamType(kotlinType))
      }

      val nativeName: String = if (index == 0) "${prefix}_create" else "${prefix}_create_${index}"

      val indexSuffix: String = if (index > 0) "_$index" else ""
      val paramNames: String = params.joinToString(", ") { it.name }

      val body: String = if (underlyingType == "String") {
        "Marshal.PtrToStringUTF8(Native_Create${indexSuffix}(${paramNames}))!"
      } else {
        "Native_Create${indexSuffix}(${paramNames})"
      }

      CirValueClassConstructor(
        parameters = params,
        nativeName = nativeName,
        body = body,
      )
    }
    .toList()

  val properties: List<CirProperty> = cls.getAllProperties()
    .filter { it.getVisibility() == Visibility.PUBLIC }
    .filter { it.simpleName.asString() != underlyingProp.simpleName.asString() }
    .map { prop ->
      val propName: String = prop.simpleName.asString()
      val propType: String = prop.type.resolve().expandAliases().declaration.simpleName.asString()
      val csPropName: String = propName.replaceFirstChar { it.uppercase() }
      val csReturnType: String = if (propType == "String") "string" else mapReturnType(propType)

      val getter: String = if (propType == "String") {
        "Marshal.PtrToStringUTF8(Native_Get${csPropName}(${nativeArg}))!"
      } else {
        "Native_Get${csPropName}(${nativeArg})"
      }

      CirProperty(
        name = csPropName,
        type = csReturnType,
        nativeReturnType = mapReturnType(propType),
        nativeName = propName,
        getter = getter,
      )
    }
    .toList()

  val methods: List<CirMethod> = cls.getAllFunctions()
    .filter { it.getVisibility() == Visibility.PUBLIC }
    .filter { it.simpleName.asString() !in listOf(
      "equals", "hashCode", "toString", "<init>",
      "box-impl", "unbox-impl", "constructor-impl",
      "hashCode-impl", "equals-impl", "equals-impl0", "toString-impl",
    ) }
    .map { method ->
      val methodName: String = method.simpleName.asString()
      val csMethodName: String = methodName.replaceFirstChar { it.uppercase() }
      val returnType: KSType? = method.returnType?.resolve()?.expandAliases()
      val kotlinReturnType: String = returnType?.declaration?.simpleName?.asString() ?: "Unit"

      val csReturnType: String = when (kotlinReturnType) {
        "String" -> "string"
        "Unit" -> "void"
        else -> mapReturnType(kotlinReturnType)
      }

      val body: String = if (kotlinReturnType == "String") {
        "Marshal.PtrToStringUTF8(Native_${csMethodName}(${nativeArg}))!"
      } else {
        "Native_${csMethodName}(${nativeArg})"
      }

      CirMethod(
        name = csMethodName,
        returnType = csReturnType,
        nativeReturnType = mapReturnType(kotlinReturnType),
        nativeName = methodName,
        parameters = emptyList(),
        body = body,
      )
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
    constructors = secondaryConstructors,
    properties = properties,
    methods = methods,
  )
}

private fun mapInterfacePropertyType(type: KSType): String {
  val typeName: String = type.declaration.simpleName.asString()
  return if (typeName == "String") "string"
  else mapParamType(typeName)
}
