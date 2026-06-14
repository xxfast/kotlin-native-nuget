package io.github.xxfast.nuget.processor.cir

import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Visibility
import io.github.xxfast.nuget.processor.toCName
import io.github.xxfast.nuget.processor.toCSharpName

private val KOTLIN_TO_CSHARP_RETURN = mapOf(
  "String" to "IntPtr",
  "Byte" to "sbyte",
  "UByte" to "byte",
  "Short" to "short",
  "UShort" to "ushort",
  "Int" to "int",
  "UInt" to "uint",
  "Long" to "long",
  "ULong" to "ulong",
  "Float" to "float",
  "Double" to "double",
  "Boolean" to "bool",
  "Unit" to "void",
)

private val KOTLIN_TO_CSHARP_PARAM = mapOf(
  "String" to "string",
  "Byte" to "sbyte",
  "UByte" to "byte",
  "Short" to "short",
  "UShort" to "ushort",
  "Int" to "int",
  "UInt" to "uint",
  "Long" to "long",
  "ULong" to "ulong",
  "Float" to "float",
  "Double" to "double",
  "Boolean" to "bool",
)

class CirTranslator(
  private val libraryName: String,
  private val rootNamespace: String,
  private val rootPackage: String,
  private val className: String,
) {
  private var needsListHelper: Boolean = false
  private var needsMapHelper: Boolean = false
  fun translate(
    functions: List<KSFunctionDeclaration>,
    genericFunctions: List<KSFunctionDeclaration>,
    classes: List<KSClassDeclaration>,
    enums: List<KSClassDeclaration> = emptyList(),
    interfaces: List<KSClassDeclaration> = emptyList(),
    sealedClasses: List<KSClassDeclaration> = emptyList(),
    objects: List<KSClassDeclaration> = emptyList(),
  ): CirFile {
    val (genericClasses, regularClasses) = classes.partition { it.typeParameters.isNotEmpty() }

    val functionsByNamespaceAndFile: Map<Pair<String, String>, List<KSFunctionDeclaration>> = functions
      .groupBy { func ->
        val namespace: String = mapPackageToNamespace(func.packageName.asString())
        val fileName: String = func.containingFile?.fileName?.removeSuffix(".kt") ?: className
        namespace to fileName
      }

    val genericFunctionsByNamespaceAndFile: Map<Pair<String, String>, List<KSFunctionDeclaration>> = genericFunctions
      .groupBy { func ->
        val namespace: String = mapPackageToNamespace(func.packageName.asString())
        val fileName: String = func.containingFile?.fileName?.removeSuffix(".kt") ?: className
        namespace to fileName
      }

    val namespaces: MutableList<CirNamespace> = mutableListOf()
    var needsMarshalHelper: Boolean = false
    needsListHelper = false
    needsMapHelper = false

    for ((key, funcs) in functionsByNamespaceAndFile) {
      val (namespace, fileClassName) = key

      val conflictsWithClass: Boolean = classes.any {
        it.simpleName.asString() == fileClassName &&
          mapPackageToNamespace(it.packageName.asString()) == namespace
      }

      val conflictsWithSealed: Boolean = sealedClasses.any {
        it.simpleName.asString() == fileClassName &&
          mapPackageToNamespace(it.packageName.asString()) == namespace
      }

      val finalClassName: String = if (conflictsWithClass || conflictsWithSealed) "${fileClassName}Kt" else fileClassName

      val members: List<CirMember> = funcs.flatMap { translateFunction(it) }
      val existing = namespaces.find { it.name == namespace }

      if (existing != null) {
        val index: Int = namespaces.indexOf(existing)
        namespaces[index] = existing.copy(declarations = existing.declarations + CirStaticClass(finalClassName, members))
      } else {
        namespaces.add(CirNamespace(namespace, listOf(CirStaticClass(finalClassName, members))))
      }
    }

    for ((key, funcs) in genericFunctionsByNamespaceAndFile) {
      val (namespace, fileClassName) = key

      val conflictsWithClass: Boolean = classes.any {
        it.simpleName.asString() == fileClassName &&
          mapPackageToNamespace(it.packageName.asString()) == namespace
      }

      val conflictsWithSealed: Boolean = sealedClasses.any {
        it.simpleName.asString() == fileClassName &&
          mapPackageToNamespace(it.packageName.asString()) == namespace
      }

      val finalClassName: String = if (conflictsWithClass || conflictsWithSealed) "${fileClassName}Kt" else fileClassName

      val members: List<CirMember> = funcs.flatMap { translateGenericFunction(it) }
      val existing = namespaces.find { it.name == namespace }

      if (existing != null) {
        val existingClass = existing.declarations.find { decl ->
          decl is CirStaticClass && decl.name == finalClassName
        } as? CirStaticClass

        if (existingClass != null) {
          val index: Int = namespaces.indexOf(existing)
          val updatedDecls = existing.declarations.map { decl ->
            if (decl is CirStaticClass && decl.name == finalClassName) {
              decl.copy(members = decl.members + members)
            } else {
              decl
            }
          }
          namespaces[index] = existing.copy(declarations = updatedDecls)
        } else {
          val index: Int = namespaces.indexOf(existing)
          namespaces[index] = existing.copy(declarations = existing.declarations + CirStaticClass(finalClassName, members))
        }
      } else {
        namespaces.add(CirNamespace(namespace, listOf(CirStaticClass(finalClassName, members))))
      }
    }

    for (cls in regularClasses) {
      val namespace: String = mapPackageToNamespace(cls.packageName.asString())
      val cirClass: CirClass = translateClass(cls)
      val existing = namespaces.find { it.name == namespace }

      if (existing != null) {
        val index: Int = namespaces.indexOf(existing)
        namespaces[index] = existing.copy(declarations = existing.declarations + cirClass)
      } else {
        namespaces.add(CirNamespace(namespace, listOf(cirClass)))
      }
    }

    for (cls in genericClasses) {
      val namespace: String = mapPackageToNamespace(cls.packageName.asString())
      val cirGenericClass: CirGenericClass = translateGenericClass(cls)
      val existing = namespaces.find { it.name == namespace }
      needsMarshalHelper = true

      if (existing != null) {
        val index: Int = namespaces.indexOf(existing)
        namespaces[index] = existing.copy(declarations = existing.declarations + cirGenericClass)
      } else {
        namespaces.add(CirNamespace(namespace, listOf(cirGenericClass)))
      }
    }

    for (enum in enums) {
      val namespace: String = mapPackageToNamespace(enum.packageName.asString())
      val cirEnum: CirEnum = translateEnum(enum)
      val existing = namespaces.find { it.name == namespace }

      if (existing != null) {
        val index: Int = namespaces.indexOf(existing)
        namespaces[index] = existing.copy(declarations = existing.declarations + cirEnum)
      } else {
        namespaces.add(CirNamespace(namespace, listOf(cirEnum)))
      }
    }

    for (iface in interfaces) {
      val namespace: String = mapPackageToNamespace(iface.packageName.asString())
      val cirInterface: CirInterface = translateInterface(iface)
      val existing = namespaces.find { it.name == namespace }

      if (existing != null) {
        val index: Int = namespaces.indexOf(existing)
        namespaces[index] = existing.copy(declarations = existing.declarations + cirInterface)
      } else {
        namespaces.add(CirNamespace(namespace, listOf(cirInterface)))
      }
    }

    for (sealed in sealedClasses) {
      val namespace: String = mapPackageToNamespace(sealed.packageName.asString())
      val cirSealedClass: CirSealedClass = translateSealedClass(sealed)
      val existing = namespaces.find { it.name == namespace }

      if (existing != null) {
        val index: Int = namespaces.indexOf(existing)
        namespaces[index] = existing.copy(declarations = existing.declarations + cirSealedClass)
      } else {
        namespaces.add(CirNamespace(namespace, listOf(cirSealedClass)))
      }
    }

    for (obj in objects) {
      val namespace: String = mapPackageToNamespace(obj.packageName.asString())
      val cirObject: CirObject = translateObject(obj)
      val existing = namespaces.find { it.name == namespace }

      if (existing != null) {
        val index: Int = namespaces.indexOf(existing)
        namespaces[index] = existing.copy(declarations = existing.declarations + cirObject)
      } else {
        namespaces.add(CirNamespace(namespace, listOf(cirObject)))
      }
    }

    if (needsListHelper) {
      needsMarshalHelper = true
    }

    if (needsMapHelper) {
      needsMarshalHelper = true
    }

    if (needsMarshalHelper) {
      val firstNamespace: CirNamespace = namespaces.firstOrNull() ?: CirNamespace(rootNamespace, emptyList())
      val idx: Int = namespaces.indexOfFirst { it.name == firstNamespace.name }

      val helpers: MutableList<CirDeclaration> = mutableListOf(CirMarshalHelper(libraryName))
      if (needsListHelper) {
        helpers.add(CirListHelper(libraryName))
      }

      if (needsMapHelper) {
        helpers.add(CirMapHelper(libraryName))
      }

      if (idx >= 0) {
        namespaces[idx] = firstNamespace.copy(
          declarations = helpers + firstNamespace.declarations,
        )
      } else {
        namespaces.add(0, CirNamespace(rootNamespace, helpers))
      }
    }

    val usings: MutableList<String> = mutableListOf("System", "System.Runtime.InteropServices")
    if (needsListHelper || needsMapHelper) {
      usings.add("System.Collections.Generic")
    }

    return CirFile(usings = usings, namespaces = namespaces)
  }

  private fun translateGenericFunction(func: KSFunctionDeclaration): List<CirMember> {
    val funcName: String = func.simpleName.asString()
    val csName: String = toCSharpName(funcName)
    val returnType: KSType? = func.returnType?.resolve()
    val returnDecl: KSClassDeclaration? = returnType?.declaration as? KSClassDeclaration
    val returnTypeName: String = returnType?.declaration?.simpleName?.asString() ?: "Unit"

    val typeParamName: String = func.typeParameters.firstOrNull()?.name?.asString() ?: "T"

    val paramIndex: Int = func.parameters.indexOfFirst { param ->
      param.type.resolve().declaration.simpleName.asString() == typeParamName
    }

    if (paramIndex == -1) return emptyList()

    val param = func.parameters[paramIndex]
    val paramName: String = param.name?.asString() ?: "value"

    val returnsGenericClass: Boolean = returnDecl?.typeParameters?.isNotEmpty() == true

    val result = mutableListOf<CirMember>()

    val primitiveTypes = listOf(
      "string" to "string",
      "int" to "int",
      "long" to "long",
      "float" to "float",
      "double" to "double",
      "bool" to "bool",
    )

    primitiveTypes.forEach { (suffix, csType) ->
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
      )
    )

    return result
  }

  private fun translateFunction(func: KSFunctionDeclaration): List<CirMember> {
    val cname: String = toCName(func.simpleName.asString())
    val csName: String = toCSharpName(cname)
    val returnType: KSType? = func.returnType?.resolve()
    val isNullable: Boolean = returnType?.isMarkedNullable == true
    val kotlinReturnType: String = returnType?.declaration?.simpleName?.asString() ?: "Unit"

    val params: List<CirParameter> = func.parameters.map { param ->
      val kotlinType: String = param.type.resolve().declaration.simpleName.asString()
      CirParameter(param.name?.asString() ?: "_", mapParamType(kotlinType))
    }

    val entryPoint: String? = if (csName != cname) cname else null

    if (isNullable) {
      return translateNullableFunction(cname, csName, kotlinReturnType, params, func)
    }

    val returnDecl: KSClassDeclaration? = returnType?.declaration as? KSClassDeclaration

    val qualifiedReturnName: String? = returnDecl?.qualifiedName?.asString()
    val isListReturnType: Boolean = qualifiedReturnName == "kotlin.collections.List"
    val isMutableListReturnType: Boolean = qualifiedReturnName == "kotlin.collections.MutableList"
    val isMapReturnType: Boolean = qualifiedReturnName == "kotlin.collections.Map"

    if (isListReturnType) {
      needsListHelper = true
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
      needsListHelper = true
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
      needsMapHelper = true
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

    val isGenericReturnType: Boolean = returnDecl?.typeParameters?.isNotEmpty() == true &&
      returnType.arguments.isNotEmpty()

    if (isGenericReturnType) {
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
      )

      val paramNames: String = params.joinToString(", ") { it.name }
      val wrapper = CirMethod(
        name = csName,
        returnType = "$kotlinReturnType<$typeArgs>",
        parameters = params,
        body = "new $kotlinReturnType<$typeArgs>(${csName}_native($paramNames))",
        isStatic = true,
      )

      return listOf(nativeImport, wrapper)
    }

    val isSealedReturnType: Boolean = returnDecl?.modifiers?.contains(Modifier.SEALED) == true

    if (isSealedReturnType) {
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
        returnType = kotlinReturnType,
        parameters = params,
        body = "$kotlinReturnType.FromHandle(${csName}_native($paramNames))",
        isStatic = true,
      )

      return listOf(nativeImport, wrapper)
    }

    if (kotlinReturnType == "String") {
      val nativeImport = CirDllImport(
        libraryName = libraryName,
        entryPoint = entryPoint,
        returnType = "IntPtr",
        name = "${csName}_native",
        parameters = params,
        visibility = CirVisibility.PRIVATE,
      )

      val paramNames: String = params.joinToString(", ") { it.name }
      val wrapper = CirMethod(
        name = csName,
        returnType = "string",
        parameters = params,
        body = "Marshal.PtrToStringUTF8(${csName}_native($paramNames))!",
        isStatic = true,
      )

      return listOf(nativeImport, wrapper)
    }

    return listOf(
      CirDllImport(
        libraryName = libraryName,
        entryPoint = entryPoint,
        returnType = mapReturnType(kotlinReturnType),
        name = csName,
        parameters = params,
      )
    )
  }

  private fun translateNullableFunction(
    cname: String,
    csName: String,
    kotlinReturnType: String,
    params: List<CirParameter>,
    func: KSFunctionDeclaration,
  ): List<CirMember> {
    val csharpReturnType: String = mapReturnType(kotlinReturnType)
    val paramNames: String = params.joinToString(", ") { it.name }

    val hasValueImport = CirDllImport(
      libraryName = libraryName,
      entryPoint = "${cname}_has_value",
      returnType = "bool",
      name = "${csName}_has_value",
      parameters = params,
      visibility = CirVisibility.PRIVATE,
    )

    val valueImport = CirDllImport(
      libraryName = libraryName,
      entryPoint = "${cname}_value",
      returnType = csharpReturnType,
      name = "${csName}_value",
      parameters = params,
      visibility = CirVisibility.PRIVATE,
    )

    val body: String = if (kotlinReturnType == "String") {
      "!${csName}_has_value($paramNames) ? null : Marshal.PtrToStringUTF8(${csName}_value($paramNames))"
    } else {
      "!${csName}_has_value($paramNames) ? null : ${csName}_value($paramNames)"
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

  private fun translateInterface(iface: KSClassDeclaration): CirInterface {
    val name: String = iface.simpleName.asString()
    val interfaceName: String = "I$name"

    val properties: List<CirInterfaceProperty> = iface.getAllProperties()
      .filter { it.getVisibility() == Visibility.PUBLIC }
      .map { prop ->
        val propName: String = prop.simpleName.asString()
        val csPropName: String = propName.replaceFirstChar { it.uppercase() }
        val isMutable: Boolean = prop.isMutable

        CirInterfaceProperty(
          name = csPropName,
          type = mapInterfacePropertyType(prop.type.resolve()),
          hasSetter = isMutable,
        )
      }
      .toList()

    val methods: List<CirInterfaceMethod> = iface.getAllFunctions()
      .filter { it.getVisibility() == Visibility.PUBLIC }
      .filter { it.simpleName.asString() !in listOf("equals", "hashCode", "toString", "<init>") }
      .map { method ->
        val methodName: String = method.simpleName.asString()
        val csMethodName: String = methodName.replaceFirstChar { it.uppercase() }

        val methodParams: List<CirParameter> = method.parameters.map { param ->
          val kotlinType: String = param.type.resolve().declaration.simpleName.asString()
          CirParameter(param.name?.asString() ?: "_", mapParamType(kotlinType))
        }

        val methodReturn: String = method.returnType?.resolve()
          ?.declaration?.simpleName?.asString() ?: "Unit"

        val returnType: String = if (methodReturn == "String") "string"
        else if (methodReturn == "Unit") "void"
        else mapReturnType(methodReturn)

        CirInterfaceMethod(
          name = csMethodName,
          returnType = returnType,
          parameters = methodParams,
        )
      }
      .toList()

    return CirInterface(interfaceName, properties, methods)
  }

  private fun mapInterfacePropertyType(type: KSType): String {
    val typeName: String = type.declaration.simpleName.asString()
    return if (typeName == "String") "string"
    else mapParamType(typeName)
  }

  private fun translateEnum(enum: KSClassDeclaration): CirEnum {
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
        val propTypeResolved: KSType = prop.type.resolve()
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

  private fun translateGenericClass(cls: KSClassDeclaration): CirGenericClass {
    val name: String = cls.simpleName.asString()
    val prefix: String = name.lowercase()
    val typeParams: List<String> = cls.typeParameters.map { it.name.asString() }

    val properties: List<CirProperty> = cls.getAllProperties()
      .filter { it.getVisibility() == Visibility.PUBLIC }
      .map { prop ->
        val propName: String = prop.simpleName.asString()
        val csPropName: String = propName.replaceFirstChar { it.uppercase() }

        CirProperty(
          name = csPropName,
          type = "T",
          nativeReturnType = "IntPtr",
          nativeName = propName,
          getter = "NugetMarshal.FromHandle<T>(${name}Native.Get_$propName(_handle))",
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

  private fun translateClass(cls: KSClassDeclaration): CirClass {
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
        val kotlinType: String = param.type.resolve().declaration.simpleName.asString()
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
      .map { prop ->
        val propName: String = prop.simpleName.asString()
        val propTypeResolved: KSType = prop.type.resolve()
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

        val listElementType: String? = if (isListType || isMutableListType) {
          val elementType = propTypeResolved.arguments.firstOrNull()?.type?.resolve()
          val elementTypeName: String = elementType?.declaration?.simpleName?.asString() ?: "Any"
          KOTLIN_TO_CSHARP_PARAM[elementTypeName] ?: elementTypeName
        } else null

        val mapKeyType: String? = if (isMapType) {
          val keyType = propTypeResolved.arguments.getOrNull(0)?.type?.resolve()
          val keyTypeName: String = keyType?.declaration?.simpleName?.asString() ?: "Any"
          KOTLIN_TO_CSHARP_PARAM[keyTypeName] ?: keyTypeName
        } else null

        val mapValueType: String? = if (isMapType) {
          val valueType = propTypeResolved.arguments.getOrNull(1)?.type?.resolve()
          val valueTypeName: String = valueType?.declaration?.simpleName?.asString() ?: "Any"
          KOTLIN_TO_CSHARP_PARAM[valueTypeName] ?: valueTypeName
        } else null

        if (isListType || isMutableListType) needsListHelper = true
        if (isMapType) needsMapHelper = true

        val isObjectType: Boolean = propType !in KOTLIN_TO_CSHARP_RETURN && !isEnumType && !isListType && !isMutableListType && !isMapType

        val nativeReturnType: String = when {
          (isListType || isMutableListType) -> "IntPtr"
          isMapType -> "IntPtr"
          isEnumType -> "int"
          isObjectType -> "IntPtr"
          else -> mapReturnType(propType)
        }

        val type: String = when {
          isListType -> "IReadOnlyList<$listElementType>"
          isMutableListType -> "IList<$listElementType>"
          isMapType -> "IReadOnlyDictionary<$mapKeyType, $mapValueType>"
          propType == "String" -> "string"
          isEnumType -> propType
          isObjectType && isNullable -> "$propType?"
          isObjectType -> propType
          else -> mapReturnType(propType)
        }

        val getter: String = if (isListType) {
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
        } else if (isMapType) {
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
        } else when {
          propType == "String" -> "Marshal.PtrToStringUTF8(Native_Get_$propName(_handle))!"
          isEnumType -> "($propType)Native_Get_$propName(_handle)"
          isObjectType && isNullable -> "Native_Get_$propName(_handle) == IntPtr.Zero ? null : new $propType(Native_Get_$propName(_handle))"
          isObjectType -> "new $propType(Native_Get_$propName(_handle))"
          else -> "Native_Get_$propName(_handle)"
        }

        val setter: String? = if (isMutable && !isListType && !isMutableListType && !isMapType) {
          when {
            propType == "String" -> "Native_Set_$propName(_handle, value)"
            isEnumType -> "Native_Set_$propName(_handle, (int)value)"
            isObjectType && isNullable -> "Native_Set_$propName(_handle, value?._handle ?? IntPtr.Zero)"
            isObjectType -> "Native_Set_$propName(_handle, value._handle)"
            else -> "Native_Set_$propName(_handle, value)"
          }
        } else null

        CirProperty(
          name = csPropName,
          type = type,
          nativeReturnType = nativeReturnType,
          nativeName = propName,
          getter = getter,
          setter = setter,
        )
      }.toList()

    val allMethods: List<KSFunctionDeclaration> = cls.getAllFunctions().toList()

    val methods: List<CirMethod> = allMethods
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
      .map { method ->
        val methodName: String = method.simpleName.asString()
        val methodReturn: String = method.returnType?.resolve()
          ?.declaration?.simpleName?.asString() ?: "Unit"

        val csMethodName: String = methodName.replaceFirstChar { it.uppercase() }

        val methodParams: List<CirParameter> = method.parameters.map { param ->
          val kotlinType: String = param.type.resolve().declaration.simpleName.asString()
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
        )
      }.toList()

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
    )
  }


  private fun translateSealedClass(cls: KSClassDeclaration): CirSealedClass {
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
            .map { prop ->
              val propName: String = prop.simpleName.asString()
              val propTypeResolved: KSType = prop.type.resolve()
              val propType: String = propTypeResolved.declaration.simpleName.asString()
              val isNullable: Boolean = propTypeResolved.isMarkedNullable
              val csPropName: String = propName.replaceFirstChar { it.uppercase() }

              val isEnumType: Boolean = (propTypeResolved.declaration as? KSClassDeclaration)
                ?.classKind == com.google.devtools.ksp.symbol.ClassKind.ENUM_CLASS

              val qualifiedTypeName: String? = propTypeResolved.declaration.qualifiedName?.asString()
              val isListType: Boolean = qualifiedTypeName == "kotlin.collections.List"
              val isMutableListType: Boolean = qualifiedTypeName == "kotlin.collections.MutableList"
              val isMapType: Boolean = qualifiedTypeName == "kotlin.collections.Map"

              val listElementType: String? = if (isListType || isMutableListType) {
                val elementType = propTypeResolved.arguments.firstOrNull()?.type?.resolve()
                val elementTypeName: String = elementType?.declaration?.simpleName?.asString() ?: "Any"
                KOTLIN_TO_CSHARP_PARAM[elementTypeName] ?: elementTypeName
              } else null

              val mapKeyType: String? = if (isMapType) {
                val keyType = propTypeResolved.arguments.getOrNull(0)?.type?.resolve()
                val keyTypeName: String = keyType?.declaration?.simpleName?.asString() ?: "Any"
                KOTLIN_TO_CSHARP_PARAM[keyTypeName] ?: keyTypeName
              } else null

              val mapValueType: String? = if (isMapType) {
                val valueType = propTypeResolved.arguments.getOrNull(1)?.type?.resolve()
                val valueTypeName: String = valueType?.declaration?.simpleName?.asString() ?: "Any"
                KOTLIN_TO_CSHARP_PARAM[valueTypeName] ?: valueTypeName
              } else null

              if (isListType || isMutableListType) needsListHelper = true
              if (isMapType) needsMapHelper = true

              val isObjectType: Boolean = propType !in KOTLIN_TO_CSHARP_RETURN && !isEnumType && !isListType && !isMutableListType && !isMapType

              val nativeReturnType: String = when {
                (isListType || isMutableListType) -> "IntPtr"
                isMapType -> "IntPtr"
                isEnumType -> "int"
                isObjectType -> "IntPtr"
                else -> mapReturnType(propType)
              }

              val type: String = when {
                isListType -> "IReadOnlyList<$listElementType>"
                isMutableListType -> "IList<$listElementType>"
                isMapType -> "IReadOnlyDictionary<$mapKeyType, $mapValueType>"
                propType == "String" -> "string"
                isEnumType -> propType
                isObjectType && isNullable -> "$propType?"
                isObjectType -> propType
                else -> mapReturnType(propType)
              }

              val getter: String = if (isListType) {
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
              } else if (isMapType) {
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
              } else when {
                propType == "String" -> "Marshal.PtrToStringUTF8(Native_Get_$propName(_handle))!"
                isEnumType -> "($propType)Native_Get_$propName(_handle)"
                isObjectType && isNullable -> "Native_Get_$propName(_handle) == IntPtr.Zero ? null : new $propType(Native_Get_$propName(_handle))"
                isObjectType -> "new $propType(Native_Get_$propName(_handle))"
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

  private fun translateObject(obj: KSClassDeclaration): CirObject {
    val name: String = obj.simpleName.asString()
    val prefix: String = name.lowercase()

    val methods: List<CirDllImport> = obj.getAllFunctions()
      .filter { it.getVisibility() == Visibility.PUBLIC }
      .filter { it.simpleName.asString() !in listOf("equals", "hashCode", "toString", "<init>") }
      .map { method ->
        val methodName: String = method.simpleName.asString()
        val cname: String = toCName(methodName)
        val returnType: KSType? = method.returnType?.resolve()
        val kotlinReturnType: String = returnType?.declaration?.simpleName?.asString() ?: "Unit"

        val params: List<CirParameter> = method.parameters.map { param ->
          val kotlinType: String = param.type.resolve().declaration.simpleName.asString()
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

  private fun mapReturnType(kotlinType: String): String =
    KOTLIN_TO_CSHARP_RETURN[kotlinType] ?: "IntPtr"

  private fun mapParamType(kotlinType: String): String =
    KOTLIN_TO_CSHARP_PARAM[kotlinType] ?: "IntPtr"

  private fun mapPackageToNamespace(kotlinPackage: String): String {
    if (rootPackage.isEmpty()) return rootNamespace

    val relative: String = if (kotlinPackage.startsWith(rootPackage)) {
      kotlinPackage.removePrefix(rootPackage).removePrefix(".")
    } else {
      kotlinPackage
    }

    if (relative.isEmpty()) return rootNamespace

    val suffix: String = relative.split(".")
      .joinToString(".") { segment ->
        segment.replaceFirstChar { it.uppercase() }
      }

    return "$rootNamespace.$suffix"
  }
}
