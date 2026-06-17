package io.github.xxfast.kotlin.native.nuget.processor.cir

import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import io.github.xxfast.kotlin.native.nuget.processor.toCName
import io.github.xxfast.kotlin.native.nuget.processor.toCSharpName

data class NugetContext(
  val libraryName: String,
  val rootNamespace: String,
  val rootPackage: String,
  val className: String,
)

fun translate(
  context: NugetContext,
  logger: KSPLogger,
  functions: List<KSFunctionDeclaration>,
  genericFunctions: List<KSFunctionDeclaration>,
  classes: List<KSClassDeclaration>,
  enums: List<KSClassDeclaration> = emptyList(),
  interfaces: List<KSClassDeclaration> = emptyList(),
  sealedClasses: List<KSClassDeclaration> = emptyList(),
  objects: List<KSClassDeclaration> = emptyList(),
  properties: List<KSPropertyDeclaration> = emptyList(),
  constProperties: List<KSPropertyDeclaration> = emptyList(),
  extensionFunctions: List<KSFunctionDeclaration> = emptyList(),
  extensionProperties: List<KSPropertyDeclaration> = emptyList(),
  valueClasses: List<KSClassDeclaration> = emptyList(),
): CirFile {
  val (genericClasses, regularClasses) = classes.partition { it.typeParameters.isNotEmpty() }

  val exportedTypes: Set<String> = buildSet {
    classes.forEach { add(it.qualifiedName?.asString() ?: "") }
    enums.forEach { add(it.qualifiedName?.asString() ?: "") }
    interfaces.forEach { add(it.qualifiedName?.asString() ?: "") }
    sealedClasses.forEach { cls ->
      add(cls.qualifiedName?.asString() ?: "")
      cls.getSealedSubclasses().forEach { add(it.qualifiedName?.asString() ?: "") }
    }
    objects.forEach { add(it.qualifiedName?.asString() ?: "") }
    remove("")
  }

  fun namespaceOf(pkg: String): String =
    mapPackageToNamespace(pkg, context.rootPackage, context.rootNamespace)

  fun groupByNamespaceAndFile(
    funcs: List<KSFunctionDeclaration>,
  ): Map<Pair<String, String>, List<KSFunctionDeclaration>> =
    funcs.groupBy { func ->
      val namespace: String = namespaceOf(func.packageName.asString())
      val fileName: String = func.containingFile?.fileName?.removeSuffix(".kt") ?: context.className
      namespace to fileName
    }

  fun groupPropertiesByNamespaceAndFile(
    props: List<KSPropertyDeclaration>,
  ): Map<Pair<String, String>, List<KSPropertyDeclaration>> =
    props.groupBy { prop ->
      val namespace: String = namespaceOf(prop.packageName.asString())
      val fileName: String = prop.containingFile?.fileName?.removeSuffix(".kt") ?: context.className
      namespace to fileName
    }

  fun resolveStaticClassName(fileClassName: String, namespace: String): String {
    val conflictsWithClass: Boolean = classes.any {
      it.simpleName.asString() == fileClassName && namespaceOf(it.packageName.asString()) == namespace
    }

    val conflictsWithSealed: Boolean = sealedClasses.any {
      it.simpleName.asString() == fileClassName && namespaceOf(it.packageName.asString()) == namespace
    }

    return if (conflictsWithClass || conflictsWithSealed) "${fileClassName}Kt" else fileClassName
  }

  val namespaces: MutableList<CirNamespace> = mutableListOf()
  var needsMarshalHelper: Boolean = false
  val tracker = CollectionHelperTracker()

  for ((key, funcs) in groupByNamespaceAndFile(functions)) {
    val (namespace, fileClassName) = key
    val finalClassName: String = resolveStaticClassName(fileClassName, namespace)
    val members: List<CirMember> = funcs.flatMap { translateFunction(it, context.libraryName, tracker, exportedTypes, logger) }
    namespaces.addDeclaration(namespace, CirStaticClass(finalClassName, members))
  }

  for ((key, funcs) in groupByNamespaceAndFile(genericFunctions)) {
    val (namespace, fileClassName) = key
    val finalClassName: String = resolveStaticClassName(fileClassName, namespace)
    val members: List<CirMember> = funcs.flatMap { translateGenericFunction(it, context.libraryName) }
    namespaces.mergeStaticClass(namespace, finalClassName, members)
  }

  for ((key, props) in groupPropertiesByNamespaceAndFile(properties)) {
    val (namespace, fileClassName) = key
    val finalClassName: String = resolveStaticClassName(fileClassName, namespace)
    val members: List<CirMember> = props.flatMap { translateProperty(it, context.libraryName) }
    namespaces.mergeStaticClass(namespace, finalClassName, members)
  }

  for ((key, props) in groupPropertiesByNamespaceAndFile(constProperties)) {
    val (namespace, fileClassName) = key
    val finalClassName: String = resolveStaticClassName(fileClassName, namespace)
    val members: List<CirMember> = props.mapNotNull { translateConstProperty(it) }
    namespaces.mergeStaticClass(namespace, finalClassName, members)
  }

  for (cls in regularClasses) {
    namespaces.addDeclaration(namespaceOf(cls.packageName.asString()), translateClass(cls, context.libraryName, tracker, exportedTypes, logger))
  }

  for (cls in genericClasses) {
    namespaces.addDeclaration(namespaceOf(cls.packageName.asString()), translateGenericClass(cls, context.libraryName, logger))
    needsMarshalHelper = true
  }

  for (cls in valueClasses) {
    namespaces.addDeclaration(namespaceOf(cls.packageName.asString()), translateValueClass(cls, context.libraryName))
  }

  for (enum in enums) {
    namespaces.addDeclaration(namespaceOf(enum.packageName.asString()), translateEnum(enum, context.libraryName))
  }

  for (iface in interfaces) {
    namespaces.addDeclaration(namespaceOf(iface.packageName.asString()), translateInterface(iface, context.libraryName, logger))
  }

  for (sealed in sealedClasses) {
    namespaces.addDeclaration(namespaceOf(sealed.packageName.asString()), translateSealedClass(sealed, context.libraryName, tracker, exportedTypes, logger))
  }

  for (obj in objects) {
    namespaces.addDeclaration(namespaceOf(obj.packageName.asString()), translateObject(obj, context.libraryName))
  }

  val extensionsByReceiver: Map<String, List<KSFunctionDeclaration>> =
    extensionFunctions.groupBy { func ->
      func.extensionReceiver!!.resolve().expandAliases().declaration.simpleName.asString()
    }

  for ((receiverName, funcs) in extensionsByReceiver) {
    val className = "${receiverName}Extensions"
    val receiverQualified: String = funcs.first().extensionReceiver!!.resolve().expandAliases()
      .declaration.qualifiedName?.asString() ?: ""

    val receiverDecl = funcs.first().extensionReceiver!!
      .resolve().expandAliases().declaration

    val namespace: String = if (receiverQualified in exportedTypes) {
      namespaceOf(receiverDecl.packageName.asString())
    } else {
      namespaceOf(funcs.first().packageName.asString())
    }

    val members: List<CirMember> = funcs.flatMap { func ->
      translateExtensionFunction(func, receiverName, receiverQualified, context.libraryName, exportedTypes)
    }

    namespaces.addDeclaration(namespace, CirStaticClass(className, members))
  }

  val extensionPropsByReceiver: Map<String, List<KSPropertyDeclaration>> =
    extensionProperties.groupBy { prop ->
      prop.extensionReceiver!!.resolve().expandAliases()
        .declaration.simpleName.asString()
    }

  extensionPropsByReceiver.forEach { (receiverName, props) ->
    val className = "${receiverName}Extensions"
    val receiverQualified: String = props.first().extensionReceiver!!
      .resolve().expandAliases()
      .declaration.qualifiedName?.asString() ?: ""

    val propReceiverDecl = props.first().extensionReceiver!!
      .resolve().expandAliases().declaration

    val namespace: String = if (receiverQualified in exportedTypes) {
      namespaceOf(propReceiverDecl.packageName.asString())
    } else {
      namespaceOf(props.first().packageName.asString())
    }

    val members: List<CirMember> = props.flatMap { prop ->
      translateExtensionProperty(prop, receiverName, receiverQualified, context.libraryName, exportedTypes)
    }

    namespaces.mergeStaticClass(namespace, className, members)
  }

  if (tracker.needsList || tracker.needsMap || tracker.needsSet || tracker.lambdaArities.isNotEmpty()) {
    needsMarshalHelper = true
  }

  if (needsMarshalHelper) {
    val helpers: MutableList<CirDeclaration> = mutableListOf(CirMarshalHelper(context.libraryName))
    if (tracker.needsList) helpers.add(CirListHelper(context.libraryName))
    if (tracker.needsMap) helpers.add(CirMapHelper(context.libraryName))
    if (tracker.needsSet) helpers.add(CirSetHelper(context.libraryName))
    if (tracker.lambdaArities.isNotEmpty()) helpers.add(CirFuncNativeHelper(context.libraryName, tracker.lambdaArities))

    val rootIdx: Int = namespaces.indexOfFirst { it.name == context.rootNamespace }

    if (rootIdx >= 0) {
      val root: CirNamespace = namespaces[rootIdx]
      namespaces[rootIdx] = root.copy(declarations = helpers + root.declarations)
    } else {
      namespaces.add(0, CirNamespace(context.rootNamespace, helpers))
    }

    if (tracker.lambdaArities.isNotEmpty()) {
      val helperNs: String = context.rootNamespace
      val funcHelper = CirFuncHelper(context.libraryName, tracker.lambdaArities, helperNs)
      val rootIdx: Int = namespaces.indexOfFirst { it.name == context.rootNamespace }

      if (rootIdx >= 0) {
        val root: CirNamespace = namespaces[rootIdx]
        namespaces[rootIdx] = root.copy(declarations = listOf(funcHelper) + root.declarations)
      } else {
        namespaces.add(CirNamespace(context.rootNamespace, listOf(funcHelper)))
      }
    }
  }

  val usings: MutableList<String> = mutableListOf("System", "System.Runtime.InteropServices")
  if (tracker.needsList || tracker.needsMap || tracker.needsSet) {
    usings.add("System.Collections.Generic")
  }

  return CirFile(usings = usings, namespaces = namespaces)
}

private fun MutableList<CirNamespace>.addDeclaration(namespace: String, declaration: CirDeclaration) {
  val existing = find { it.name == namespace }
  if (existing != null) {
    val index: Int = indexOf(existing)
    this[index] = existing.copy(declarations = existing.declarations + declaration)
  } else {
    add(CirNamespace(namespace, listOf(declaration)))
  }
}

private fun MutableList<CirNamespace>.mergeStaticClass(namespace: String, className: String, members: List<CirMember>) {
  val existing = find { it.name == namespace }

  if (existing == null) {
    add(CirNamespace(namespace, listOf(CirStaticClass(className, members))))
    return
  }

  val index: Int = indexOf(existing)
  val existingClass = existing.declarations.find { it is CirStaticClass && it.name == className } as? CirStaticClass

  if (existingClass != null) {
    val updatedDecls = existing.declarations.map { decl ->
      if (decl is CirStaticClass && decl.name == className) decl.copy(members = decl.members + members)
      else decl
    }
    this[index] = existing.copy(declarations = updatedDecls)
  } else {
    this[index] = existing.copy(declarations = existing.declarations + CirStaticClass(className, members))
  }
}

internal fun translateExtensionFunction(
  func: KSFunctionDeclaration,
  receiverName: String,
  receiverQualified: String,
  libraryName: String,
  exportedTypes: Set<String>,
): List<CirMember> {
  val funcName: String = func.simpleName.asString()
  val csName: String = toCSharpName(toCName(funcName))
    .replaceFirstChar { it.uppercase() }
  val receiverPrefix: String = receiverName.lowercase()
  val cname: String = "${receiverPrefix}_${toCName(funcName)}"

  val returnType = func.returnType?.resolve()?.expandAliases()
  val kotlinReturnType: String = returnType?.declaration?.simpleName?.asString() ?: "Unit"

  val isExportedReceiver: Boolean = receiverQualified in exportedTypes

  val nativeReceiverType: String = if (isExportedReceiver) "IntPtr" else mapParamType(receiverName)
  val receiverParamName: String = if (isExportedReceiver) "handle" else "receiver"

  val extraParams: List<CirParameter> = func.parameters.map { param ->
    val kotlinType: String = param.type.resolve().expandAliases().declaration.simpleName.asString()
    CirParameter(param.name?.asString() ?: "_", mapParamType(kotlinType))
  }

  val allNativeParams: List<CirParameter> = listOf(
    CirParameter(receiverParamName, nativeReceiverType),
  ) + extraParams

  val nativeReturnType: String = mapReturnType(kotlinReturnType)

  val dllImport = CirDllImport(
    libraryName = libraryName,
    entryPoint = cname,
    returnType = nativeReturnType,
    name = "Native_$csName",
    parameters = allNativeParams,
    visibility = CirVisibility.PRIVATE,
  )

  val csReceiverType: String = if (isExportedReceiver) receiverName else mapParamType(receiverName)
  val csReceiverParamName: String = if (isExportedReceiver) receiverName.lowercase() else "receiver"

  val wrapperParams: List<CirParameter> = listOf(
    CirParameter(csReceiverParamName, csReceiverType),
  ) + extraParams

  val nativeCallReceiver: String = if (isExportedReceiver) "${csReceiverParamName}._handle" else "receiver"
  val nativeCallArgs: String = (listOf(nativeCallReceiver) + extraParams.map { it.name }).joinToString(", ")

  val csReturnType: String
  val body: String

  if (kotlinReturnType == "String") {
    csReturnType = "string"
    body = "Marshal.PtrToStringUTF8(Native_$csName($nativeCallArgs))!"
  } else if (kotlinReturnType == "Unit") {
    csReturnType = "void"
    body = "Native_$csName($nativeCallArgs)"
  } else {
    csReturnType = KOTLIN_TO_CSHARP_PARAM[kotlinReturnType] ?: kotlinReturnType
    body = "Native_$csName($nativeCallArgs)"
  }

  val wrapper = CirMethod(
    name = csName,
    returnType = csReturnType,
    parameters = wrapperParams,
    body = body,
    isStatic = true,
    isExtension = true,
  )

  return listOf(dllImport, wrapper)
}

internal fun translateExtensionProperty(
  prop: KSPropertyDeclaration,
  receiverName: String,
  receiverQualified: String,
  libraryName: String,
  exportedTypes: Set<String>,
): List<CirMember> {
  val propName: String = prop.simpleName.asString()
  val csName: String = "Get${propName.replaceFirstChar { it.uppercase() }}"
  val receiverPrefix: String = receiverName.lowercase()
  val cname: String = "${receiverPrefix}_get_${toCName(propName)}"

  val propTypeResolved: KSType = prop.type.resolve().expandAliases()
  val kotlinReturnType: String = propTypeResolved.declaration.simpleName.asString()

  val isExportedReceiver: Boolean = receiverQualified in exportedTypes

  val nativeReceiverType: String = if (isExportedReceiver) "IntPtr" else mapParamType(receiverName)
  val receiverParamName: String = if (isExportedReceiver) "handle" else "receiver"

  val allNativeParams: List<CirParameter> = listOf(
    CirParameter(receiverParamName, nativeReceiverType),
  )

  val nativeReturnType: String = mapReturnType(kotlinReturnType)

  val dllImport = CirDllImport(
    libraryName = libraryName,
    entryPoint = cname,
    returnType = nativeReturnType,
    name = "Native_$csName",
    parameters = allNativeParams,
    visibility = CirVisibility.PRIVATE,
  )

  val csReceiverType: String = if (isExportedReceiver) receiverName else mapParamType(receiverName)
  val csReceiverParamName: String = if (isExportedReceiver) receiverName.lowercase() else "receiver"

  val wrapperParams: List<CirParameter> = listOf(
    CirParameter(csReceiverParamName, csReceiverType),
  )

  val nativeCallReceiver: String = if (isExportedReceiver) "${csReceiverParamName}._handle" else "receiver"

  val csReturnType: String
  val body: String

  if (kotlinReturnType == "String") {
    csReturnType = "string"
    body = "Marshal.PtrToStringUTF8(Native_$csName($nativeCallReceiver))!"
  } else {
    csReturnType = KOTLIN_TO_CSHARP_PARAM[kotlinReturnType] ?: kotlinReturnType
    body = "Native_$csName($nativeCallReceiver)"
  }

  val wrapper = CirMethod(
    name = csName,
    returnType = csReturnType,
    parameters = wrapperParams,
    body = body,
    isStatic = true,
    isExtension = true,
  )

  return listOf(dllImport, wrapper)
}

internal fun translateProperty(
  prop: KSPropertyDeclaration,
  libraryName: String,
): List<CirMember> {
  val propName: String = prop.simpleName.asString()
  val cname: String = io.github.xxfast.kotlin.native.nuget.processor.toCName(propName)
  val propTypeResolved: KSType = prop.type.resolve().expandAliases()
  val propType: String = propTypeResolved.declaration.simpleName.asString()
  val isNullable: Boolean = propTypeResolved.isMarkedNullable
  val isMutable: Boolean = prop.isMutable
  val csPropName: String = propName.replaceFirstChar { it.uppercase() }

  if (propType !in KOTLIN_TO_CSHARP_RETURN) return emptyList()

  val isNullablePrimitive: Boolean = isNullable && propType != "String"

  val members: MutableList<CirMember> = mutableListOf()

  if (isNullablePrimitive) {
    val csValueType: String = mapParamType(propType)

    members.add(CirDllImport(
      libraryName = libraryName,
      entryPoint = "get_$cname",
      returnType = "bool",
      name = "Native_Get_$propName",
      parameters = emptyList(),
      visibility = CirVisibility.PRIVATE,
    ))

    members.add(CirDllImport(
      libraryName = libraryName,
      entryPoint = "get_${cname}_value",
      returnType = csValueType,
      name = "Native_Get_${propName}_value",
      parameters = emptyList(),
      visibility = CirVisibility.PRIVATE,
    ))

    if (isMutable) {
      members.add(CirDllImport(
        libraryName = libraryName,
        entryPoint = "set_$cname",
        returnType = "void",
        name = "Native_Set_$propName",
        parameters = listOf(CirParameter("value", csValueType)),
        visibility = CirVisibility.PRIVATE,
      ))

      members.add(CirDllImport(
        libraryName = libraryName,
        entryPoint = "set_${cname}_null",
        returnType = "void",
        name = "Native_Set_${propName}_null",
        parameters = emptyList(),
        visibility = CirVisibility.PRIVATE,
      ))
    }

    val getter = "!Native_Get_$propName() ? null : Native_Get_${propName}_value()"
    val setter: String? = if (isMutable) buildString {
      appendLine()
      appendLine("                if (value.HasValue) Native_Set_$propName(value.Value);")
      append("                else Native_Set_${propName}_null();")
    } else null

    members.add(CirProperty(
      name = csPropName,
      type = "$csValueType?",
      nativeReturnType = csValueType,
      nativeName = propName,
      getter = getter,
      setter = setter,
      isStatic = true,
    ))

    return members
  }

  val csNativeReturnType: String = mapReturnType(propType)

  members.add(CirDllImport(
    libraryName = libraryName,
    entryPoint = "get_$cname",
    returnType = csNativeReturnType,
    name = "Native_Get_$propName",
    parameters = emptyList(),
    visibility = CirVisibility.PRIVATE,
  ))

  val csType: String
  val getter: String
  val setter: String?

  if (propType == "String" && isNullable) {
    csType = "string?"
    getter = "Marshal.PtrToStringUTF8(Native_Get_$propName())"
    setter = if (isMutable) "Native_Set_$propName(value)" else null

    if (isMutable) {
      members.add(CirDllImport(
        libraryName = libraryName,
        entryPoint = "set_$cname",
        returnType = "void",
        name = "Native_Set_$propName",
        parameters = listOf(CirParameter("value", "string?")),
        visibility = CirVisibility.PRIVATE,
      ))
    }
  } else if (propType == "String") {
    csType = "string"
    getter = "Marshal.PtrToStringUTF8(Native_Get_$propName())!"
    setter = if (isMutable) "Native_Set_$propName(value)" else null

    if (isMutable) {
      members.add(CirDllImport(
        libraryName = libraryName,
        entryPoint = "set_$cname",
        returnType = "void",
        name = "Native_Set_$propName",
        parameters = listOf(CirParameter("value", "string")),
        visibility = CirVisibility.PRIVATE,
      ))
    }
  } else {
    csType = csNativeReturnType
    getter = "Native_Get_$propName()"
    setter = if (isMutable) "Native_Set_$propName(value)" else null

    if (isMutable) {
      members.add(CirDllImport(
        libraryName = libraryName,
        entryPoint = "set_$cname",
        returnType = "void",
        name = "Native_Set_$propName",
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

internal fun translateConstProperty(
  prop: KSPropertyDeclaration,
): CirConst? {
  val propName: String = prop.simpleName.asString()
  val propTypeResolved: KSType = prop.type.resolve().expandAliases()
  val propType: String = propTypeResolved.declaration.simpleName.asString()
  val csPropName: String = propName.split("_")
    .joinToString("") { segment ->
      segment.lowercase().replaceFirstChar { it.uppercase() }
    }
  val csType: String = KOTLIN_TO_CSHARP_PARAM[propType] ?: return null
  val value: String = extractConstValue(prop) ?: return null
  val csValue: String = kotlinLiteralToCSharp(value, propType)

  return CirConst(name = csPropName, type = csType, value = csValue)
}

private fun extractConstValue(prop: KSPropertyDeclaration): String? {
  val filePath: String = prop.containingFile?.filePath ?: return null
  val propName: String = prop.simpleName.asString()
  val sourceText: String = java.io.File(filePath).readText()
  val pattern = Regex("""const\s+val\s+${Regex.escape(propName)}\s*(?::\s*\S+)?\s*=\s*(.+)""")
  val match: MatchResult = pattern.find(sourceText) ?: return null
  return match.groupValues[1].trim()
}

private fun kotlinLiteralToCSharp(value: String, kotlinType: String): String {
  val cleaned: String = value.replace("_", "")
  return when (kotlinType) {
    "String" -> cleaned
    "Float" -> if (cleaned.endsWith("f", ignoreCase = true)) cleaned else "${cleaned}f"
    "Long" -> if (cleaned.endsWith("L")) cleaned else "${cleaned}L"
    "UInt" -> if (cleaned.endsWith("u", ignoreCase = true)) cleaned else "${cleaned}U"
    "ULong" -> if (cleaned.endsWith("uL", ignoreCase = true) || cleaned.endsWith("UL")) cleaned else "${cleaned}UL"
    else -> cleaned
  }
}
