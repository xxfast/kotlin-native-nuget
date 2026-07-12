package io.github.xxfast.kotlin.native.nuget.processor.cir

import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Modifier
import io.github.xxfast.kotlin.native.nuget.processor.toCName
import io.github.xxfast.kotlin.native.nuget.processor.toCSharpName

internal fun translateFunction(
  func: KSFunctionDeclaration,
  libraryName: String,
  rootPackage: String,
  rootNamespace: String,
  tracker: CollectionHelperTracker,
  exportedTypes: Set<String>,
  logger: KSPLogger,
): List<CirMember> {
  val cname: String = toCName(func.simpleName.asString())
  val csName: String = toCSharpName(cname)
  val returnType = func.returnType?.resolve()?.expandAliases()
  val isNullable: Boolean = returnType?.isMarkedNullable == true
  val kotlinReturnType: String = returnType?.declaration?.simpleName?.asString() ?: "Unit"

  // Enums cross the C ABI as their ordinal Int (ADR-006), so the public C# param keeps the enum
  // type while the DllImport takes an int. renderSyncErrorCheckMethod casts at the call site.
  val params: List<CirParameter> = func.parameters.map { param ->
    val resolved: KSType = param.type.resolve().expandAliases()
    val kotlinType: String = resolved.declaration.simpleName.asString()
    val name: String = param.name?.asString() ?: "_"
    val enumDecl: KSClassDeclaration? = (resolved.declaration as? KSClassDeclaration)
      ?.takeIf { it.classKind == ClassKind.ENUM_CLASS }

    if (enumDecl == null) return@map CirParameter(name, mapParamType(kotlinType))

    val enumNamespace: String = mapPackageToNamespace(
      enumDecl.packageName.asString(), rootPackage, rootNamespace,
    )

    CirParameter(name, type = "global::$enumNamespace.$kotlinType", nativeType = "int")
  }

  val hasEnumParams: Boolean = params.any { it.nativeType != it.type }

  // Only the return shapes that render through renderSyncErrorCheckMethod (enum, String,
  // primitive, Unit) cast an enum param down to its ordinal at the native call site. The others
  // hand-build their native call and would silently emit a bridge that does not compile.
  fun enumParamsUnsupported(returnShape: String): List<CirMember> {
    logger.error(
      "Enum parameters are not supported on a top-level function with a $returnShape return " +
          "type ('${func.simpleName.asString()}'). They are supported on top-level functions " +
          "returning an enum, a String, a primitive, or Unit.",
      func,
    )
    return emptyList()
  }

  val entryPoint: String? = if (csName != cname) cname else null

  if (isNullable) {
    if (hasEnumParams) return enumParamsUnsupported("nullable")

    return translateNullableFunction(cname, csName, kotlinReturnType, params, libraryName)
  }

  val returnDecl: KSClassDeclaration? = returnType?.declaration as? KSClassDeclaration

  val qualifiedReturnName: String? = returnDecl?.qualifiedName?.asString()
  val isListReturnType: Boolean = qualifiedReturnName == "kotlin.collections.List"
  val isMutableListReturnType: Boolean = qualifiedReturnName == "kotlin.collections.MutableList"
  val isMapReturnType: Boolean = qualifiedReturnName == "kotlin.collections.Map"
  val isMutableMapReturnType: Boolean = qualifiedReturnName == "kotlin.collections.MutableMap"
  val isSetReturnType: Boolean = qualifiedReturnName == "kotlin.collections.Set"
  val isMutableSetReturnType: Boolean = qualifiedReturnName == "kotlin.collections.MutableSet"
  val isLambdaReturnType: Boolean = qualifiedReturnName in LAMBDA_TYPES

  if (isLambdaReturnType) {
    if (hasEnumParams) return enumParamsUnsupported("lambda")

    val lambdaArity: Int = returnType!!.arguments.size - 1
    tracker.lambdaArities.add(lambdaArity)

    val lambdaTypeArgs: List<String> = returnType.arguments.map { arg ->
      val argType: String = arg.type?.resolve()?.declaration?.simpleName?.asString() ?: "object"
      KOTLIN_TO_CSHARP_PARAM[argType] ?: argType
    }
    val lambdaCsType = "KotlinFunc<${lambdaTypeArgs.joinToString(", ")}>"

    val nativeImport = CirDllImport(
      libraryName = libraryName,
      entryPoint = cname,
      returnType = "IntPtr",
      name = "${csName}_native",
      parameters = params,
      visibility = CirVisibility.PRIVATE,
    )

    val paramNames: String = params.joinToString(", ") { it.name }
    val wrapper = CirMethod(
      name = csName,
      returnType = lambdaCsType,
      parameters = params,
      body = "new $lambdaCsType(${csName}_native($paramNames))",
      isStatic = true,
    )

    return listOf(nativeImport, wrapper)
  }

  if (isListReturnType) {
    if (hasEnumParams) return enumParamsUnsupported("List")

    tracker.needsList = true
    val elementType = returnType?.arguments?.firstOrNull()?.type?.resolve()
    val elementTypeName: String = elementType?.declaration?.simpleName?.asString() ?: "Any"
    val csElementType: String = KOTLIN_TO_CSHARP_PARAM[elementTypeName] ?: elementTypeName

    val nativeImport = CirDllImport(
      libraryName = libraryName,
      entryPoint = cname,
      returnType = "IntPtr",
      name = "${csName}_native",
      parameters = params,
      visibility = CirVisibility.PRIVATE,
    )

    val paramNames: String = params.joinToString(", ") { it.name }
    val body: String = buildString {
      appendLine()
      appendLine("            IntPtr listHandle = ${csName}_native($paramNames);")
      appendLine("            int count = NugetListNative.Count(listHandle);")
      appendLine("            var result = new List<$csElementType>(count);")
      appendLine("            for (int i = 0; i < count; i++)")
      appendLine("            {")
      appendLine("                result.Add(NugetMarshal.FromHandle<$csElementType>(NugetListNative.Get(listHandle, i)));")
      appendLine("            }")
      appendLine("            NugetListNative.Dispose(listHandle);")
      append("            return result.AsReadOnly();")
    }

    val wrapper = CirMethod(
      name = csName,
      returnType = "IReadOnlyList<$csElementType>",
      parameters = params,
      body = body,
      isStatic = true,
    )

    return listOf(nativeImport, wrapper)
  }

  if (isMutableListReturnType) {
    if (hasEnumParams) return enumParamsUnsupported("MutableList")

    tracker.needsList = true
    val elementType = returnType?.arguments?.firstOrNull()?.type?.resolve()
    val elementTypeName: String = elementType?.declaration?.simpleName?.asString() ?: "Any"
    val csElementType: String = KOTLIN_TO_CSHARP_PARAM[elementTypeName] ?: elementTypeName

    val nativeImport = CirDllImport(
      libraryName = libraryName,
      entryPoint = cname,
      returnType = "IntPtr",
      name = "${csName}_native",
      parameters = params,
      visibility = CirVisibility.PRIVATE,
    )

    val paramNames: String = params.joinToString(", ") { it.name }
    val body: String = buildString {
      appendLine()
      appendLine("            IntPtr listHandle = ${csName}_native($paramNames);")
      appendLine("            int count = NugetListNative.Count(listHandle);")
      appendLine("            var result = new List<$csElementType>(count);")
      appendLine("            for (int i = 0; i < count; i++)")
      appendLine("            {")
      appendLine("                result.Add(NugetMarshal.FromHandle<$csElementType>(NugetListNative.Get(listHandle, i)));")
      appendLine("            }")
      appendLine("            NugetListNative.Dispose(listHandle);")
      append("            return result;")
    }

    val wrapper = CirMethod(
      name = csName,
      returnType = "IList<$csElementType>",
      parameters = params,
      body = body,
      isStatic = true,
    )

    return listOf(nativeImport, wrapper)
  }

  if (isMapReturnType) {
    if (hasEnumParams) return enumParamsUnsupported("Map")

    tracker.needsMap = true
    val keyType = returnType?.arguments?.getOrNull(0)?.type?.resolve()
    val keyTypeName: String = keyType?.declaration?.simpleName?.asString() ?: "Any"
    val csKeyType: String = KOTLIN_TO_CSHARP_PARAM[keyTypeName] ?: keyTypeName

    val valueType = returnType?.arguments?.getOrNull(1)?.type?.resolve()
    val valueTypeName: String = valueType?.declaration?.simpleName?.asString() ?: "Any"
    val csValueType: String = KOTLIN_TO_CSHARP_PARAM[valueTypeName] ?: valueTypeName

    val nativeImport = CirDllImport(
      libraryName = libraryName,
      entryPoint = cname,
      returnType = "IntPtr",
      name = "${csName}_native",
      parameters = params,
      visibility = CirVisibility.PRIVATE,
    )

    val paramNames: String = params.joinToString(", ") { it.name }
    val body: String = buildString {
      appendLine()
      appendLine("            IntPtr mapHandle = ${csName}_native($paramNames);")
      appendLine("            int count = NugetMapNative.Count(mapHandle);")
      appendLine("            var result = new Dictionary<$csKeyType, $csValueType>(count);")
      appendLine("            for (int i = 0; i < count; i++)")
      appendLine("            {")
      appendLine("                var key = NugetMarshal.FromHandle<$csKeyType>(NugetMapNative.KeyAt(mapHandle, i));")
      appendLine("                var value = NugetMarshal.FromHandle<$csValueType>(NugetMapNative.ValueAt(mapHandle, i));")
      appendLine("                result[key] = value;")
      appendLine("            }")
      appendLine("            NugetMapNative.Dispose(mapHandle);")
      append("            return result;")
    }

    val wrapper = CirMethod(
      name = csName,
      returnType = "IReadOnlyDictionary<$csKeyType, $csValueType>",
      parameters = params,
      body = body,
      isStatic = true,
    )

    return listOf(nativeImport, wrapper)
  }

  if (isMutableMapReturnType) {
    if (hasEnumParams) return enumParamsUnsupported("MutableMap")

    tracker.needsMap = true
    val keyType = returnType?.arguments?.getOrNull(0)?.type?.resolve()
    val keyTypeName: String = keyType?.declaration?.simpleName?.asString() ?: "Any"
    val csKeyType: String = KOTLIN_TO_CSHARP_PARAM[keyTypeName] ?: keyTypeName

    val valueType = returnType?.arguments?.getOrNull(1)?.type?.resolve()
    val valueTypeName: String = valueType?.declaration?.simpleName?.asString() ?: "Any"
    val csValueType: String = KOTLIN_TO_CSHARP_PARAM[valueTypeName] ?: valueTypeName

    val nativeImport = CirDllImport(
      libraryName = libraryName,
      entryPoint = cname,
      returnType = "IntPtr",
      name = "${csName}_native",
      parameters = params,
      visibility = CirVisibility.PRIVATE,
    )

    val paramNames: String = params.joinToString(", ") { it.name }
    val body: String = buildString {
      appendLine()
      appendLine("            IntPtr mapHandle = ${csName}_native($paramNames);")
      appendLine("            int count = NugetMapNative.Count(mapHandle);")
      appendLine("            var result = new Dictionary<$csKeyType, $csValueType>(count);")
      appendLine("            for (int i = 0; i < count; i++)")
      appendLine("            {")
      appendLine("                var key = NugetMarshal.FromHandle<$csKeyType>(NugetMapNative.KeyAt(mapHandle, i));")
      appendLine("                var value = NugetMarshal.FromHandle<$csValueType>(NugetMapNative.ValueAt(mapHandle, i));")
      appendLine("                result[key] = value;")
      appendLine("            }")
      appendLine("            NugetMapNative.Dispose(mapHandle);")
      append("            return result;")
    }

    val wrapper = CirMethod(
      name = csName,
      returnType = "IDictionary<$csKeyType, $csValueType>",
      parameters = params,
      body = body,
      isStatic = true,
    )

    return listOf(nativeImport, wrapper)
  }

  if (isSetReturnType) {
    if (hasEnumParams) return enumParamsUnsupported("Set")

    tracker.needsSet = true
    val elementType = returnType?.arguments?.firstOrNull()?.type?.resolve()
    val elementTypeName: String = elementType?.declaration?.simpleName?.asString() ?: "Any"
    val csElementType: String = KOTLIN_TO_CSHARP_PARAM[elementTypeName] ?: elementTypeName

    val nativeImport = CirDllImport(
      libraryName = libraryName,
      entryPoint = cname,
      returnType = "IntPtr",
      name = "${csName}_native",
      parameters = params,
      visibility = CirVisibility.PRIVATE,
    )

    val paramNames: String = params.joinToString(", ") { it.name }
    val body: String = buildString {
      appendLine()
      appendLine("            IntPtr setHandle = ${csName}_native($paramNames);")
      appendLine("            int count = NugetSetNative.Count(setHandle);")
      appendLine("            var result = new HashSet<$csElementType>(count);")
      appendLine("            for (int i = 0; i < count; i++)")
      appendLine("            {")
      appendLine("                result.Add(NugetMarshal.FromHandle<$csElementType>(NugetSetNative.ElementAt(setHandle, i)));")
      appendLine("            }")
      appendLine("            NugetSetNative.Dispose(setHandle);")
      append("            return result;")
    }

    val wrapper = CirMethod(
      name = csName,
      returnType = "IReadOnlySet<$csElementType>",
      parameters = params,
      body = body,
      isStatic = true,
    )

    return listOf(nativeImport, wrapper)
  }

  if (isMutableSetReturnType) {
    if (hasEnumParams) return enumParamsUnsupported("MutableSet")

    tracker.needsSet = true
    val elementType = returnType?.arguments?.firstOrNull()?.type?.resolve()
    val elementTypeName: String = elementType?.declaration?.simpleName?.asString() ?: "Any"
    val csElementType: String = KOTLIN_TO_CSHARP_PARAM[elementTypeName] ?: elementTypeName

    val nativeImport = CirDllImport(
      libraryName = libraryName,
      entryPoint = cname,
      returnType = "IntPtr",
      name = "${csName}_native",
      parameters = params,
      visibility = CirVisibility.PRIVATE,
    )

    val paramNames: String = params.joinToString(", ") { it.name }
    val body: String = buildString {
      appendLine()
      appendLine("            IntPtr setHandle = ${csName}_native($paramNames);")
      appendLine("            int count = NugetSetNative.Count(setHandle);")
      appendLine("            var result = new HashSet<$csElementType>(count);")
      appendLine("            for (int i = 0; i < count; i++)")
      appendLine("            {")
      appendLine("                result.Add(NugetMarshal.FromHandle<$csElementType>(NugetSetNative.ElementAt(setHandle, i)));")
      appendLine("            }")
      appendLine("            NugetSetNative.Dispose(setHandle);")
      append("            return result;")
    }

    val wrapper = CirMethod(
      name = csName,
      returnType = "ISet<$csElementType>",
      parameters = params,
      body = body,
      isStatic = true,
    )

    return listOf(nativeImport, wrapper)
  }

  val isGenericReturnType: Boolean = returnDecl?.typeParameters?.isNotEmpty() == true &&
      returnType.arguments.isNotEmpty()

  if (isGenericReturnType) {
    if (hasEnumParams) return enumParamsUnsupported("generic")

    val typeArgs: String = returnType.arguments.joinToString(", ") { arg ->
      val argType: String = arg.type?.resolve()?.declaration?.simpleName?.asString() ?: "object"
      when (argType) {
        "String" -> "string"
        "Int" -> "int"
        else -> argType
      }
    }

    val nativeImport = CirDllImport(
      libraryName = libraryName,
      entryPoint = cname,
      returnType = "IntPtr",
      name = "${csName}_native",
      parameters = params,
      visibility = CirVisibility.PRIVATE,
      hasSyncErrorOut = true,
    )

    val paramNames: String = params.joinToString(", ") { it.name }
    val nativeCallArgs: String =
      if (paramNames.isEmpty()) "out IntPtr error" else "$paramNames, out IntPtr error"
    val body: String = buildString {
      appendLine()
      appendLine("            IntPtr nativeResult = ${csName}_native($nativeCallArgs);")
      appendLine("            if (error != IntPtr.Zero)")
      appendLine("            {")
      appendLine("                throw NugetErrorNative.BuildException(error);")
      appendLine("            }")
      append("            return new $kotlinReturnType<$typeArgs>(nativeResult);")
    }

    val wrapper = CirMethod(
      name = csName,
      returnType = "$kotlinReturnType<$typeArgs>",
      parameters = params,
      body = body,
      isStatic = true,
    )

    return listOf(nativeImport, wrapper)
  }

  val isSealedReturnType: Boolean = returnDecl?.modifiers?.contains(Modifier.SEALED) == true
  val isEnumReturnType: Boolean = returnDecl?.classKind == ClassKind.ENUM_CLASS

  if (isSealedReturnType) {
    if (hasEnumParams) return enumParamsUnsupported("sealed")

    val nativeImport = CirDllImport(
      libraryName = libraryName,
      entryPoint = cname,
      returnType = "IntPtr",
      name = "${csName}_native",
      parameters = params,
      visibility = CirVisibility.PRIVATE,
      hasSyncErrorOut = true,
    )

    val paramNames: String = params.joinToString(", ") { it.name }
    val nativeCallArgs: String =
      if (paramNames.isEmpty()) "out IntPtr error" else "$paramNames, out IntPtr error"
    val body: String = buildString {
      appendLine()
      appendLine("            IntPtr nativeResult = ${csName}_native($nativeCallArgs);")
      appendLine("            if (error != IntPtr.Zero)")
      appendLine("            {")
      appendLine("                throw NugetErrorNative.BuildException(error);")
      appendLine("            }")
      append("            return $kotlinReturnType.FromHandle(nativeResult);")
    }

    val wrapper = CirMethod(
      name = csName,
      returnType = kotlinReturnType,
      parameters = params,
      body = body,
      isStatic = true,
    )

    return listOf(nativeImport, wrapper)
  }

  if (isEnumReturnType) {
    val enumNamespace: String = mapPackageToNamespace(
      returnDecl.packageName.asString(), rootPackage, rootNamespace,
    )
    val enumType: String = "global::$enumNamespace.$kotlinReturnType"
    val nativeImport = CirDllImport(
      libraryName = libraryName,
      entryPoint = cname,
      returnType = "int",
      name = "${csName}_native",
      parameters = params,
      visibility = CirVisibility.PRIVATE,
      hasSyncErrorOut = true,
    )

    // renderSyncErrorCheckMethod builds the native call and the cast back to the enum itself,
    // so it never reads body.
    val wrapper = CirMethod(
      name = csName,
      returnType = enumType,
      nativeReturnType = "int",
      nativeName = "${csName}_native",
      parameters = params,
      body = "",
      isStatic = true,
      isSyncErrorCheckEnabled = true,
    )

    return listOf(nativeImport, wrapper)
  }

  if (kotlinReturnType == "String") {
    val nativeImport = CirDllImport(
      libraryName = libraryName,
      entryPoint = cname,
      returnType = "IntPtr",
      name = "${csName}_native",
      parameters = params,
      visibility = CirVisibility.PRIVATE,
      hasSyncErrorOut = true,
    )

    val paramNames: String = params.joinToString(", ") { it.name }
    val nativeCallArgs: String = if (paramNames.isEmpty()) "out IntPtr error" else "$paramNames, out IntPtr error"
    val wrapper = CirMethod(
      name = csName,
      returnType = "string",
      nativeReturnType = "IntPtr",
      nativeName = "${csName}_native",
      parameters = params,
      body = "Marshal.PtrToStringUTF8(${csName}_native($nativeCallArgs))!",
      isStatic = true,
      isSyncErrorCheckEnabled = true,
    )

    return listOf(nativeImport, wrapper)
  }

  val qualifiedReturnType: String? = returnDecl?.qualifiedName?.asString()
  val isUnknownObjectReturn: Boolean = kotlinReturnType !in KOTLIN_TO_CSHARP_RETURN &&
      qualifiedReturnType != null && qualifiedReturnType !in exportedTypes

  if (isUnknownObjectReturn) {
    logger.warn("Skipping function '${func.simpleName.asString()}': unsupported return type '$qualifiedReturnType'")
    return emptyList()
  }

  val csReturnType: String = mapReturnType(kotlinReturnType)
  val isVoidReturn: Boolean = kotlinReturnType == "Unit"

  val nativeImport = CirDllImport(
    libraryName = libraryName,
    entryPoint = cname,
    returnType = csReturnType,
    name = "${csName}_native",
    parameters = params,
    visibility = CirVisibility.PRIVATE,
    hasSyncErrorOut = true,
  )

  val paramNames: String = params.joinToString(", ") { it.name }
  val nativeCallArgs: String = if (paramNames.isEmpty()) "out IntPtr error" else "$paramNames, out IntPtr error"

  val wrapper = CirMethod(
    name = csName,
    returnType = if (isVoidReturn) "void" else csReturnType,
    nativeName = "${csName}_native",
    parameters = params,
    body = if (isVoidReturn) "${csName}_native($nativeCallArgs)" else "${csName}_native($nativeCallArgs)",
    isStatic = true,
    isSyncErrorCheckEnabled = true,
  )

  return listOf(nativeImport, wrapper)
}

internal fun translateSuspendFunction(
  func: KSFunctionDeclaration,
  libraryName: String,
  tracker: CollectionHelperTracker,
  exportedTypes: Set<String>,
  logger: KSPLogger,
): List<CirMember> {
  val cname: String = toCName(func.simpleName.asString())
  val csName: String = toCSharpName(cname).replaceFirstChar { it.uppercase() }
  val returnType = func.returnType?.resolve()?.expandAliases()
  val kotlinReturnType: String = returnType?.declaration?.simpleName?.asString() ?: "Unit"
  val isUnit: Boolean = kotlinReturnType == "Unit"

  val params: List<CirParameter> = func.parameters.map { param ->
    val kotlinType: String = param.type.resolve().expandAliases().declaration.simpleName.asString()
    CirParameter(param.name?.asString() ?: "_", mapParamType(kotlinType))
  }

  val asyncReturnType: String = if (isUnit) "" else {
    KOTLIN_TO_CSHARP_PARAM[kotlinReturnType] ?: kotlinReturnType
  }

  tracker.needsAsync = true

  val callbackType: String = "NugetAsyncCallback"
  val nativeParams: List<CirParameter> = params +
      listOf(
        CirParameter("callback", callbackType),
        CirParameter("userData", "IntPtr"),
      )

  val nativeImport = CirDllImport(
    libraryName = libraryName,
    entryPoint = "${cname}_async",
    returnType = "IntPtr",
    name = "${csName}Async_native",
    parameters = nativeParams,
    visibility = CirVisibility.PRIVATE,
  )

  val taskReturnType: String = if (isUnit) "Task" else "Task<$asyncReturnType>"

  val asyncMethod = CirMethod(
    name = "${csName}Async",
    returnType = taskReturnType,
    nativeName = "${csName}Async_native",
    parameters = params,
    body = "",
    isStatic = true,
    isAsync = true,
    asyncReturnType = asyncReturnType,
  )

  return listOf(nativeImport, asyncMethod)
}

internal fun translateGenericFunction(
  func: KSFunctionDeclaration,
  libraryName: String,
): List<CirMember> {
  val funcName: String = func.simpleName.asString()
  val csName: String = toCSharpName(funcName)
  val returnType = func.returnType?.resolve()?.expandAliases()
  val returnDecl: KSClassDeclaration? = returnType?.declaration as? KSClassDeclaration
  val returnTypeName: String = returnType?.declaration?.simpleName?.asString() ?: "Unit"

  val typeParamName: String = func.typeParameters.firstOrNull()?.name?.asString() ?: "T"

  val typeParamBounds: List<String> = func.typeParameters.firstOrNull()
    ?.bounds?.toList()?.mapNotNull { bound ->
      val resolved = bound.resolve()
      val qualifiedName: String? =
        resolved.declaration.qualifiedName?.asString()
      val simpleName: String =
        resolved.declaration.simpleName.asString()

      val isInterface: Boolean =
        resolved.declaration is KSClassDeclaration &&
            (resolved.declaration as KSClassDeclaration).classKind ==
            ClassKind.INTERFACE

      when {
        qualifiedName == "kotlin.Any" -> null
        isInterface -> "I$simpleName"
        else -> simpleName
      }
    } ?: emptyList()

  val paramIndex: Int = func.parameters.indexOfFirst { param ->
    param.type.resolve().expandAliases().declaration.simpleName.asString() == typeParamName
  }

  if (paramIndex == -1) return emptyList()

  val param = func.parameters[paramIndex]
  val paramName: String = param.name?.asString() ?: "value"

  val returnsGenericClass: Boolean = returnDecl?.typeParameters?.isNotEmpty() == true

  val result = mutableListOf<CirMember>()

  val isConstrained: Boolean = typeParamBounds.isNotEmpty()

  val primitiveTypes = listOf(
    "string" to "string",
    "int" to "int",
    "long" to "long",
    "float" to "float",
    "double" to "double",
    "bool" to "bool",
  )

  if (!isConstrained) primitiveTypes.forEach { (suffix, csType) ->
    val entryPoint = "${funcName}_$suffix"
    val nativeName = "${csName}_${suffix}_native"

    val nativeReturnType: String = when {
      returnsGenericClass -> "IntPtr"
      csType == "string" -> "IntPtr"
      else -> csType
    }

    val nativeParamType: String = csType

    result.add(
      CirDllImport(
        libraryName = libraryName,
        entryPoint = entryPoint,
        returnType = nativeReturnType,
        name = nativeName,
        parameters = listOf(CirParameter(paramName, nativeParamType)),
        visibility = CirVisibility.PRIVATE,
      )
    )
  }

  val objectEntryPoint = "${funcName}_object"
  val objectNativeName = "${csName}_object_native"

  result.add(
    CirDllImport(
      libraryName = libraryName,
      entryPoint = objectEntryPoint,
      returnType = "IntPtr",
      name = objectNativeName,
      parameters = listOf(CirParameter(paramName, "IntPtr")),
      visibility = CirVisibility.PRIVATE,
    )
  )

  val body: String = buildString {
    appendLine()

    if (!isConstrained) {
      appendLine("      if (typeof(T) == typeof(string))")
      if (returnsGenericClass) {
        appendLine("        return new ${returnTypeName}<T>(${csName}_string_native((string)(object)$paramName!));")
      } else {
        appendLine("        return (T)(object)Marshal.PtrToStringUTF8(${csName}_string_native((string)(object)$paramName!))!;")
      }

      appendLine("      if (typeof(T) == typeof(int))")
      if (returnsGenericClass) {
        appendLine("        return new ${returnTypeName}<T>(${csName}_int_native((int)(object)$paramName!));")
      } else {
        appendLine("        return (T)(object)${csName}_int_native((int)(object)$paramName!);")
      }

      appendLine("      if (typeof(T) == typeof(long))")
      if (returnsGenericClass) {
        appendLine("        return new ${returnTypeName}<T>(${csName}_long_native((long)(object)$paramName!));")
      } else {
        appendLine("        return (T)(object)${csName}_long_native((long)(object)$paramName!);")
      }

      appendLine("      if (typeof(T) == typeof(float))")
      if (returnsGenericClass) {
        appendLine("        return new ${returnTypeName}<T>(${csName}_float_native((float)(object)$paramName!));")
      } else {
        appendLine("        return (T)(object)${csName}_float_native((float)(object)$paramName!);")
      }

      appendLine("      if (typeof(T) == typeof(double))")
      if (returnsGenericClass) {
        appendLine("        return new ${returnTypeName}<T>(${csName}_double_native((double)(object)$paramName!));")
      } else {
        appendLine("        return (T)(object)${csName}_double_native((double)(object)$paramName!);")
      }

      appendLine("      if (typeof(T) == typeof(bool))")
      if (returnsGenericClass) {
        appendLine("        return new ${returnTypeName}<T>(${csName}_bool_native((bool)(object)$paramName!));")
      } else {
        appendLine("        return (T)(object)${csName}_bool_native((bool)(object)$paramName!);")
      }
    }

    if (returnsGenericClass) {
      appendLine("      var field = typeof(T).GetField(\"_handle\",")
      appendLine("        System.Reflection.BindingFlags.Instance | System.Reflection.BindingFlags.NonPublic | System.Reflection.BindingFlags.Public);")
      appendLine("      IntPtr handle = (IntPtr)field!.GetValue($paramName)!;")
      appendLine("      IntPtr result = ${csName}_object_native(handle);")
      appendLine("      return new ${returnTypeName}<T>(result);")
    } else {
      appendLine("      var field = typeof(T).GetField(\"_handle\",")
      appendLine("        System.Reflection.BindingFlags.Instance | System.Reflection.BindingFlags.NonPublic | System.Reflection.BindingFlags.Public);")
      appendLine("      IntPtr handle = (IntPtr)field!.GetValue($paramName)!;")
      appendLine("      IntPtr result = ${csName}_object_native(handle);")
      appendLine("      return (T)Activator.CreateInstance(typeof(T),")
      appendLine("        System.Reflection.BindingFlags.Instance | System.Reflection.BindingFlags.NonPublic | System.Reflection.BindingFlags.Public,")
      appendLine("        null, new object[] { result }, null)!;")
    }
  }

  val methodReturnType: String = if (returnsGenericClass) "$returnTypeName<T>" else "T"

  result.add(
    CirMethod(
      name = csName,
      returnType = methodReturnType,
      parameters = listOf(CirParameter(paramName, "T")),
      body = body.trimEnd(),
      isStatic = true,
      typeParameters = listOf(
        CirTypeParameter(typeParamName, typeParamBounds),
      ),
    )
  )

  return result
}

internal fun translateNullableFunction(
  cname: String,
  csName: String,
  kotlinReturnType: String,
  params: List<CirParameter>,
  libraryName: String,
): List<CirMember> {
  val csharpReturnType: String = mapReturnType(kotlinReturnType)
  val paramNames: String = params.joinToString(", ") { it.name }

  // Both crossings throw synchronously (ADR-024), so both DllImports need an error out-param;
  // see the isNullableValueType property getter in CirClassTranslator.kt for the same
  // has_value/value two-call shape this mirrors.
  val hasValueImport = CirDllImport(
    libraryName = libraryName,
    entryPoint = "${cname}_has_value",
    returnType = "bool",
    name = "${csName}_has_value",
    parameters = params,
    visibility = CirVisibility.PRIVATE,
    hasSyncErrorOut = true,
  )

  val valueImport = CirDllImport(
    libraryName = libraryName,
    entryPoint = "${cname}_value",
    returnType = csharpReturnType,
    name = "${csName}_value",
    parameters = params,
    visibility = CirVisibility.PRIVATE,
    hasSyncErrorOut = true,
  )

  // Local names are prefixed to avoid colliding with a Kotlin parameter of the same name
  // (e.g. `fun nullableInt(hasValue: Boolean)` would otherwise shadow a plain `hasValue` local).
  // The has_value/value error outs get their own descriptive suffix (rather than a bare `error`/
  // `error2`) since both are read in the same method body and must stay distinguishable.
  val hasValueCallArgs: String = if (paramNames.isEmpty()) {
    "out IntPtr __nuget_hasValueError"
  } else {
    "$paramNames, out IntPtr __nuget_hasValueError"
  }
  val valueCallArgs: String = if (paramNames.isEmpty()) {
    "out IntPtr __nuget_valueError"
  } else {
    "$paramNames, out IntPtr __nuget_valueError"
  }

  val body: String = buildString {
    appendLine()
    appendLine("            bool __nuget_hasValue = ${csName}_has_value($hasValueCallArgs);")
    appendLine("            if (__nuget_hasValueError != IntPtr.Zero)")
    appendLine("            {")
    appendLine("                throw NugetErrorNative.BuildException(__nuget_hasValueError);")
    appendLine("            }")
    appendLine("            if (!__nuget_hasValue) return null;")
    if (kotlinReturnType == "String") {
      appendLine("            IntPtr __nuget_nativeResult = ${csName}_value($valueCallArgs);")
      appendLine("            if (__nuget_valueError != IntPtr.Zero)")
      appendLine("            {")
      appendLine("                throw NugetErrorNative.BuildException(__nuget_valueError);")
      appendLine("            }")
      append("            return Marshal.PtrToStringUTF8(__nuget_nativeResult);")
    } else {
      appendLine("            $csharpReturnType __nuget_value = ${csName}_value($valueCallArgs);")
      appendLine("            if (__nuget_valueError != IntPtr.Zero)")
      appendLine("            {")
      appendLine("                throw NugetErrorNative.BuildException(__nuget_valueError);")
      appendLine("            }")
      append("            return __nuget_value;")
    }
  }

  val returnTypeStr: String = if (kotlinReturnType == "String") "string?" else "$csharpReturnType?"

  val wrapper = CirMethod(
    name = csName,
    returnType = returnTypeStr,
    parameters = params,
    body = body,
    isStatic = true,
  )

  return listOf(hasValueImport, valueImport, wrapper)
}
