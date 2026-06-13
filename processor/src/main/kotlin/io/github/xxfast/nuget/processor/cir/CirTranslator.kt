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
  fun translate(
    functions: List<KSFunctionDeclaration>,
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

    val namespaces: MutableList<CirNamespace> = mutableListOf()
    var needsMarshalHelper: Boolean = false

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

    if (needsMarshalHelper) {
      val firstNamespace: CirNamespace = namespaces.firstOrNull() ?: CirNamespace(rootNamespace, emptyList())
      val idx: Int = namespaces.indexOfFirst { it.name == firstNamespace.name }

      if (idx >= 0) {
        namespaces[idx] = firstNamespace.copy(
          declarations = listOf(CirMarshalHelper(libraryName)) + firstNamespace.declarations,
        )
      } else {
        namespaces.add(0, CirNamespace(rootNamespace, listOf(CirMarshalHelper(libraryName))))
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

    val returnDecl: KSClassDeclaration? = returnType?.declaration as? KSClassDeclaration
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
          getter = "NugetMarshal.FromHandle<T>(Native_Get_$propName(_handle))",
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
