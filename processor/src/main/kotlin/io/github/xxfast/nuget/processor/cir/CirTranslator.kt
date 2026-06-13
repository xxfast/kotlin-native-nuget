package io.github.xxfast.nuget.processor.cir

import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSType
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
  fun translate(
    functions: List<KSFunctionDeclaration>,
    classes: List<KSClassDeclaration>,
    enums: List<KSClassDeclaration> = emptyList(),
  ): CirFile {
    val functionsByNamespaceAndFile: Map<Pair<String, String>, List<KSFunctionDeclaration>> = functions
      .groupBy { func ->
        val namespace: String = mapPackageToNamespace(func.packageName.asString())
        val fileName: String = func.containingFile?.fileName?.removeSuffix(".kt") ?: className
        namespace to fileName
      }

    val namespaces: MutableList<CirNamespace> = mutableListOf()

    for ((key, funcs) in functionsByNamespaceAndFile) {
      val (namespace, fileClassName) = key

      val conflictsWithClass: Boolean = classes.any {
        it.simpleName.asString() == fileClassName &&
          mapPackageToNamespace(it.packageName.asString()) == namespace
      }
      val finalClassName: String = if (conflictsWithClass) "${fileClassName}Kt" else fileClassName

      val members: List<CirMember> = funcs.flatMap { translateFunction(it) }
      val existing = namespaces.find { it.name == namespace }

      if (existing != null) {
        val index: Int = namespaces.indexOf(existing)
        namespaces[index] = existing.copy(declarations = existing.declarations + CirStaticClass(finalClassName, members))
      } else {
        namespaces.add(CirNamespace(namespace, listOf(CirStaticClass(finalClassName, members))))
      }
    }

    for (cls in classes) {
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

    return CirFile(namespaces = namespaces)
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

  private fun translateClass(cls: KSClassDeclaration): CirClass {
    val name: String = cls.simpleName.asString()
    val prefix: String = name.lowercase()

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
      .map { prop ->
        val propName: String = prop.simpleName.asString()
        val propTypeResolved: KSType = prop.type.resolve()
        val propType: String = propTypeResolved.declaration.simpleName.asString()
        val isNullable: Boolean = propTypeResolved.isMarkedNullable
        val isMutable: Boolean = prop.isMutable
        val csPropName: String = propName.replaceFirstChar { it.uppercase() }

        val isEnumType: Boolean = (propTypeResolved.declaration as? KSClassDeclaration)
          ?.classKind == com.google.devtools.ksp.symbol.ClassKind.ENUM_CLASS

        val isObjectType: Boolean = propType !in KOTLIN_TO_CSHARP_RETURN && !isEnumType

        val nativeReturnType: String = when {
          isEnumType -> "int"
          isObjectType -> "IntPtr"
          else -> mapReturnType(propType)
        }

        val type: String = when {
          propType == "String" -> "string"
          isEnumType -> propType
          isObjectType && isNullable -> "$propType?"
          isObjectType -> propType
          else -> mapReturnType(propType)
        }

        val getter: String = when {
          propType == "String" -> "Marshal.PtrToStringUTF8(Native_Get_$propName(_handle))!"
          isEnumType -> "($propType)Native_Get_$propName(_handle)"
          isObjectType && isNullable -> "Native_Get_$propName(_handle) == IntPtr.Zero ? null : new $propType(Native_Get_$propName(_handle))"
          isObjectType -> "new $propType(Native_Get_$propName(_handle))"
          else -> "Native_Get_$propName(_handle)"
        }

        val setter: String? = if (isMutable) {
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

    val methods: List<CirMethod> = cls.getAllFunctions()
      .filter { it.getVisibility() == Visibility.PUBLIC }
      .filter { it.simpleName.asString() !in listOf("equals", "hashCode", "toString", "<init>") }
      .map { method ->
        val methodName: String = method.simpleName.asString()
        val methodReturn: String = method.returnType?.resolve()
          ?.declaration?.simpleName?.asString() ?: "Unit"

        val csMethodName: String = methodName.replaceFirstChar { it.uppercase() }

        val methodParams: List<CirParameter> = method.parameters.map { param ->
          val kotlinType: String = param.type.resolve().declaration.simpleName.asString()
          CirParameter(param.name?.asString() ?: "_", mapParamType(kotlinType))
        }

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
        )
      }.toList()

    return CirClass(
      name = name,
      libraryName = libraryName,
      nativePrefix = prefix,
      constructor = cirConstructor,
      properties = properties,
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
