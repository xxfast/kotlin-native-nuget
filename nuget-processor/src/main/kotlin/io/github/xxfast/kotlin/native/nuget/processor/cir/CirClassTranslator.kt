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
  val cirConstructor: CirConstructor? = if (constructor != null) {
    val ctorParams: List<CirParameter> = constructor.parameters.map { param ->
      val resolved: KSType = param.type.resolve().expandAliases()
      val kotlinType: String = resolved.declaration.simpleName.asString()
      CirParameter(param.name?.asString() ?: "_", mapParamType(kotlinType))
    }

    CirConstructor(
      parameters = ctorParams,
      body = "",
      hasErrorCheck = true,
    )
  } else null

  // Secondary constructors (ADR-034). Entry points start at _create_2 so they
  // never collide with the primary's _create.
  val secondaryConstructors: List<CirConstructor> = if (isAbstract) emptyList() else cls
    .getConstructors()
    .filter { it != cls.primaryConstructor }
    .filter { it.getVisibility() == Visibility.PUBLIC }
    .toList()
    .mapIndexed { index, ctor ->
      val params: List<CirParameter> = ctor.parameters.map { param ->
        val resolved: KSType = param.type.resolve().expandAliases()
        val kotlinType: String = resolved.declaration.simpleName.asString()
        CirParameter(param.name?.asString() ?: "_", mapParamType(kotlinType))
      }

      CirConstructor(
        parameters = params,
        body = "",
        hasErrorCheck = true,
        nativeSuffix = "_${index + 2}",
      )
    }

  // C has no overloading and C# cannot declare two constructors with identical
  // parameter types — fail fast rather than emit uncompilable C# (ADR-034).
  val constructorSignatures: List<List<String>> =
    (listOfNotNull(cirConstructor) + secondaryConstructors).map { ctor ->
      ctor.parameters.map { it.type }
    }
  if (constructorSignatures.size != constructorSignatures.toSet().size) {
    logger.error(
      "Class $name has constructors with identical C# signatures; " +
          "rename or remove the duplicate (ADR-034).",
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
      val propTypeResolved: KSType = prop.type.resolve().expandAliases()
      val propType: String = propTypeResolved.declaration.simpleName.asString()
      val isNullable: Boolean = propTypeResolved.isMarkedNullable
      val isMutable: Boolean = prop.isMutable
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

      val isSuspendLambdaType: Boolean = qualifiedTypeName in SUSPEND_LAMBDA_TYPES
      val suspendLambdaArity: Int =
        if (isSuspendLambdaType) propTypeResolved.arguments.size - 1 else -1

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
        extraNatives.add(
          CirExtraNative(
            entryPointSuffix = "get_${propName}_value",
            returnType = csValueType,
            name = "Native_Get_${propName}_value",
            hasSyncErrorOut = true,
          )
        )
        if (isMutable) {
          extraNatives.add(
            CirExtraNative(
              entryPointSuffix = "set_${propName}_null",
              returnType = "void",
              name = "Native_Set_${propName}_null",
              hasSyncErrorOut = true,
            )
          )
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

      val isSettable: Boolean = isMutable && !isLambdaType && !isSuspendLambdaType &&
          !isListType && !isMutableListType && !isMapType && !isMutableMapType &&
          !isSetType && !isMutableSetType

      val setter: String? = if (isSettable) {
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
        isNullableString -> "string?"
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
      val isSkipped: Boolean = methodName in listOf("equals", "hashCode", "toString", "<init>") ||
          isDataClassMethod
      if (isSkipped) return@filter false

      if (superClass != null) {
        method.parentDeclaration == cls
      } else {
        true
      }
    }

  val (suspendMethods, regularMethods) = filteredMethods
    .partition { it.modifiers.contains(Modifier.SUSPEND) }

  val (flowMethods, nonFlowMethods) = regularMethods.partition { method ->
    val returnQualified: String? = method.returnType?.resolve()?.expandAliases()
      ?.declaration?.qualifiedName?.asString()
    returnQualified in FLOW_TYPES
  }

  if (flowMethods.isNotEmpty()) {
    tracker.needsFlow = true
    tracker.needsAsync = true
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
    .map { method ->
      val methodName: String = method.simpleName.asString()
      val methodReturn: String = method.returnType?.resolve()?.expandAliases()
        ?.declaration?.simpleName?.asString() ?: "Unit"

      val csMethodName: String = methodName.replaceFirstChar { it.uppercase() }

      // For enum parameters: public type is the enum name, native type is "int" (ordinal).
      val methodParams: List<CirParameter> = method.parameters.map { param ->
        val resolved: KSType = param.type.resolve().expandAliases()
        val kotlinType: String = resolved.declaration.simpleName.asString()
        val isEnum: Boolean = (resolved.declaration as? KSClassDeclaration)
          ?.classKind == ClassKind.ENUM_CLASS
        CirParameter(
          name = param.name?.asString() ?: "_",
          type = if (isEnum) kotlinType else mapParamType(kotlinType),
          nativeType = if (isEnum) "int" else mapParamType(kotlinType),
        )
      }

      val declaredInThisClass: Boolean = method.parentDeclaration == cls
      val hasImplementation: Boolean = declaredInThisClass ||
          method.modifiers.contains(Modifier.OVERRIDE)
      val isMethodAbstract: Boolean = !hasImplementation &&
          (isAbstract || method.modifiers.contains(Modifier.ABSTRACT))
      val isOverride: Boolean = superClass != null && method.modifiers.contains(Modifier.OVERRIDE)

      // nativeCallArgs is only used for non-sync-error methods; sync-error path builds its own.
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
    val flowCsElementType: String =
      KOTLIN_TO_CSHARP_PARAM[flowElementKotlinType] ?: flowElementKotlinType

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
    secondaryConstructors = secondaryConstructors,
    properties = properties,
    methods = methods,
    callbackMethods = callbackMembers,
    storedCallbackMethods = storedCallbackMembers,
    interfaceBridgeMethods = interfaceBridgeMembers,
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

    if (param.variance != Variance.INVARIANT) {
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
        visibility = CirVisibility.PRIVATE,
        hasSyncErrorOut = true,
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
): CirValueClass {
  val name: String = cls.simpleName.asString()
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

  val secondaryCtorDecls: List<KSFunctionDeclaration> = cls.declarations
    .filterIsInstance<KSFunctionDeclaration>()
    .filter { it.simpleName.asString() == "<init>" }
    .filter { it != cls.primaryConstructor }
    .toList()

  val constructors: List<CirValueClassConstructor> = if (isReferenceUnderlying) {
    // ADR-035: primary `init` validation for reference-underlying value classes is
    // deferred; keep the existing secondary-only export scheme (old numbering).
    secondaryCtorDecls.mapIndexed { index, ctor ->
      val suffix: String = if (index == 0) "" else "_$index"
      val nativeName: String = if (index == 0) "${prefix}_create" else "${prefix}_create_${index}"
      buildConstructor(ctor, suffix, nativeName)
    }
  } else {
    // ADR-035: route the primary through Kotlin so its `init` runs; secondaries
    // renumber to `_create_2+` (aligns with ADR-034).
    buildList {
      add(buildConstructor(cls.primaryConstructor!!, "", "${prefix}_create"))
      secondaryCtorDecls.forEachIndexed { index, ctor ->
        val number: Int = index + 2
        add(buildConstructor(ctor, "_$number", "${prefix}_create_$number"))
      }
    }
  }

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
    .filter {
      it.simpleName.asString() !in listOf(
        "equals", "hashCode", "toString", "<init>",
        "box-impl", "unbox-impl", "constructor-impl",
        "hashCode-impl", "equals-impl", "equals-impl0", "toString-impl",
      )
    }
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
