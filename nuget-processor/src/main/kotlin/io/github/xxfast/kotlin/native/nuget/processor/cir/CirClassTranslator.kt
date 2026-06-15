package io.github.xxfast.kotlin.native.nuget.processor.cir

import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSClassDeclaration
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
    .mapNotNull { prop ->
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

      val setter: String? = if (isMutable && !isLambdaType && !isListType && !isMutableListType && !isMapType && !isMutableMapType && !isSetType && !isMutableSetType) {
        when {
          propType == "String" -> "Native_Set_$propName(_handle, value)"
          isEnumType -> "Native_Set_$propName(_handle, (int)value)"
          isReferenceType && isNullable -> "Native_Set_$propName(_handle, value?._handle ?? IntPtr.Zero)"
          isReferenceType -> "Native_Set_$propName(_handle, value._handle)"
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

  val allMethods = cls.getAllFunctions().toList()

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

internal fun translateGenericClass(
  cls: KSClassDeclaration,
  libraryName: String,
): CirGenericClass {
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
      val returnType = method.returnType?.resolve()
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

internal fun translateInterface(
  iface: KSClassDeclaration,
  libraryName: String,
): CirInterface {
  val name: String = iface.simpleName.asString()
  val interfaceName: String = "I$name"

  val properties: List<CirInterfaceProperty> = iface.getAllProperties()
    .filter { it.getVisibility() == Visibility.PUBLIC }
    .map { prop ->
      val propName: String = prop.simpleName.asString()
      val propType: KSType = prop.type.resolve()
      val csPropName: String = propName.replaceFirstChar { it.uppercase() }
      CirInterfaceProperty(csPropName, mapInterfacePropertyType(propType))
    }
    .toList()

  val methods: List<CirInterfaceMethod> = iface.getAllFunctions()
    .filter { it.getVisibility() == Visibility.PUBLIC }
    .filter { it.simpleName.asString() !in listOf("equals", "hashCode", "toString", "<init>") }
    .map { method ->
      val methodName: String = method.simpleName.asString()
      val returnType = method.returnType?.resolve()
      val kotlinReturnType: String = returnType?.declaration?.simpleName?.asString() ?: "Unit"
      val csMethodName: String = methodName.replaceFirstChar { it.uppercase() }

      val csReturnType: String = when (kotlinReturnType) {
        "String" -> "string"
        "Unit" -> "void"
        else -> mapReturnType(kotlinReturnType)
      }

      val params: List<CirParameter> = method.parameters.map { param ->
        val kotlinType: String = param.type.resolve().declaration.simpleName.asString()
        CirParameter(param.name?.asString() ?: "_", mapParamType(kotlinType))
      }

      CirInterfaceMethod(csMethodName, csReturnType, params)
    }
    .toList()

  return CirInterface(interfaceName, properties, methods)
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

private fun mapInterfacePropertyType(type: KSType): String {
  val typeName: String = type.declaration.simpleName.asString()
  return if (typeName == "String") "string"
  else mapParamType(typeName)
}
