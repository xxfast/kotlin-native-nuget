package io.github.xxfast.kotlin.native.nuget.processor.cir

import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardCallablePlanCatalog
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardCirPlanProjection
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardCirPropertyProjection
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardHelperRequirement
import io.github.xxfast.kotlin.native.nuget.processor.forward.planFor
import io.github.xxfast.kotlin.native.nuget.processor.toCName
import io.github.xxfast.kotlin.native.nuget.processor.toCSharpName

private fun syncErrorArguments(parameters: String): String = if (parameters.isEmpty()) {
  "out IntPtr error"
} else {
  "$parameters, out IntPtr error"
}

data class NugetContext(
  val libraryName: String,
  val rootNamespace: String,
  val rootPackage: String,
  val className: String,
)

internal fun translate(
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
  suspendFunctions: List<KSFunctionDeclaration> = emptyList(),
  callableCatalog: ForwardCallablePlanCatalog = ForwardCallablePlanCatalog(emptyList()),
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
    val members: List<CirMember> = funcs.flatMap { function ->
      val symbol: String = "${function.packageName.asString()}.${function.simpleName.asString()}"
      val planned = callableCatalog.planFor(symbol)
      if (planned != null) ForwardCirPlanProjection.static(planned, context.libraryName) else translateFunction(
        function,
        context.libraryName,
        context.rootPackage,
        context.rootNamespace,
        tracker,
        exportedTypes,
        logger,
      )
    }
    namespaces.addDeclaration(namespace, CirStaticClass(finalClassName, members))
  }

  for ((key, funcs) in groupByNamespaceAndFile(genericFunctions)) {
    val (namespace, fileClassName) = key
    val finalClassName: String = resolveStaticClassName(fileClassName, namespace)
    val members: List<CirMember> = funcs.flatMap { translateGenericFunction(it, context.libraryName) }
    namespaces.mergeStaticClass(namespace, finalClassName, members)
  }

  for ((key, funcs) in groupByNamespaceAndFile(suspendFunctions)) {
    val (namespace, fileClassName) = key
    val finalClassName: String = resolveStaticClassName(fileClassName, namespace)
    val members: List<CirMember> = funcs.flatMap { function ->
      translateSuspendFunction(function, context.libraryName, tracker, exportedTypes, logger)
    }
    namespaces.mergeStaticClass(namespace, finalClassName, members)
  }

  for ((key, props) in groupPropertiesByNamespaceAndFile(properties)) {
    val (namespace, fileClassName) = key
    val finalClassName: String = resolveStaticClassName(fileClassName, namespace)
    val members: List<CirMember> = props.flatMap { prop ->
      val plan = callableCatalog.propertyFor("${prop.packageName.asString()}.${prop.simpleName.asString()}")
      if (plan != null) ForwardCirPropertyProjection.staticProperty(plan, context.libraryName)
      else translateProperty(prop, context.libraryName)
    }
    namespaces.mergeStaticClass(namespace, finalClassName, members)
  }

  for ((key, props) in groupPropertiesByNamespaceAndFile(constProperties)) {
    val (namespace, fileClassName) = key
    val finalClassName: String = resolveStaticClassName(fileClassName, namespace)
    val members: List<CirMember> = props.mapNotNull { translateConstProperty(it) }
    namespaces.mergeStaticClass(namespace, finalClassName, members)
  }

  for (cls in regularClasses) {
    namespaces.addDeclaration(
      namespaceOf(cls.packageName.asString()),
      translateClass(cls, context.libraryName, tracker, exportedTypes, logger, callableCatalog)
    )
  }

  for (cls in genericClasses) {
    namespaces.addDeclaration(
      namespaceOf(cls.packageName.asString()),
      translateGenericClass(cls, context.libraryName, logger)
    )
    needsMarshalHelper = true
  }

  for (cls in valueClasses) {
    namespaces.addDeclaration(namespaceOf(cls.packageName.asString()), translateValueClass(cls, context.libraryName))
  }

  for (enum in enums) {
    namespaces.addDeclaration(namespaceOf(enum.packageName.asString()), translateEnum(enum, context.libraryName))
  }

  for (iface in interfaces) {
    namespaces.addDeclaration(
      namespaceOf(iface.packageName.asString()),
      translateInterface(iface, context.libraryName, logger)
    )
  }

  for (sealed in sealedClasses) {
    namespaces.addDeclaration(
      namespaceOf(sealed.packageName.asString()),
      translateSealedClass(sealed, context.libraryName, tracker, exportedTypes, logger)
    )
  }

  for (obj in objects) {
    namespaces.addDeclaration(
      namespaceOf(obj.packageName.asString()),
      translateObject(obj, context.libraryName, callableCatalog),
    )
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
      val planned = callableCatalog.planFor(
        "${func.packageName.asString()}.${func.simpleName.asString()}",
      )
      if (planned != null) {
        if (ForwardHelperRequirement.COLLECTION in planned.helperRequirements) tracker.needsList = true
        return@flatMap ForwardCirPlanProjection.extension(planned, context.libraryName)
      }
      translateExtensionFunction(
        func, receiverName, receiverQualified, context.libraryName, exportedTypes, tracker,
      )
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
      val symbol: String = "${prop.packageName.asString()}.$receiverName.${prop.simpleName.asString()}"
      val plan = callableCatalog.propertyFor(symbol)
      if (plan != null) ForwardCirPropertyProjection.extension(plan, context.libraryName)
      else translateExtensionProperty(prop, receiverName, receiverQualified, context.libraryName, exportedTypes)
    }

    namespaces.mergeStaticClass(namespace, className, members)
  }

  if (tracker.suspendLambdaArities.isNotEmpty()) tracker.needsAsync = true

  if (tracker.needsFlow) tracker.needsAsync = true

  if (tracker.needsList || tracker.needsMap || tracker.needsSet || tracker.lambdaArities.isNotEmpty() || tracker.needsAsync) {
    needsMarshalHelper = true
  }

  if (functions.isNotEmpty() || classes.isNotEmpty() || objects.isNotEmpty() || sealedClasses.isNotEmpty()) {
    needsMarshalHelper = true
  }

  if (needsMarshalHelper) {
    val helpers: MutableList<CirDeclaration> = mutableListOf(CirMarshalHelper(context.libraryName))
    if (tracker.needsList) helpers.add(CirListHelper(context.libraryName))
    if (tracker.needsMap) helpers.add(CirMapHelper(context.libraryName))
    if (tracker.needsSet) helpers.add(CirSetHelper(context.libraryName))
    if (tracker.lambdaArities.isNotEmpty()) helpers.add(CirFuncNativeHelper(context.libraryName, tracker.lambdaArities))
    if (tracker.suspendLambdaArities.isNotEmpty()) helpers.add(
      CirSuspendFuncNativeHelper(
        context.libraryName,
        tracker.suspendLambdaArities
      )
    )
    if (tracker.needsAsync) helpers.add(CirAsyncHelper(context.libraryName))
    if (tracker.needsAsync) helpers.add(CirScopeHelper(context.libraryName))
    if (tracker.needsAsync) helpers.add(CirJobHelper(context.libraryName))
    helpers.add(CirErrorHelper(context.libraryName))
    if (tracker.needsFlow) helpers.add(CirFlowHelper(context.libraryName))
    if (tracker.callbackDelegates.isNotEmpty()) {
      helpers.add(CirCallbackDelegateHelper(tracker.callbackDelegates.distinctBy { it.name }))
    }
    if (tracker.needsSubscription) {
      helpers.add(CirSubscriptionHelper(context.libraryName))
    }

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

    if (tracker.suspendLambdaArities.isNotEmpty()) {
      val helperNs: String = context.rootNamespace
      val suspendFuncHelper = CirSuspendFuncHelper(context.libraryName, tracker.suspendLambdaArities, helperNs)
      val rootIdx: Int = namespaces.indexOfFirst { it.name == context.rootNamespace }

      if (rootIdx >= 0) {
        val root: CirNamespace = namespaces[rootIdx]
        namespaces[rootIdx] = root.copy(declarations = listOf(suspendFuncHelper) + root.declarations)
      } else {
        namespaces.add(CirNamespace(context.rootNamespace, listOf(suspendFuncHelper)))
      }
    }
  }

  val usings: MutableList<String> = mutableListOf("System", "System.Runtime.InteropServices")
  if (tracker.needsList || tracker.needsMap || tracker.needsSet) {
    usings.add("System.Collections.Generic")
  }
  if (tracker.needsAsync) {
    usings.add("System.Runtime.CompilerServices")
    usings.add("System.Threading")
    usings.add("System.Threading.Tasks")
  }
  if (tracker.needsSubscription && "System.Threading" !in usings) {
    usings.add("System.Threading")
  }
  if (tracker.needsFlow) {
    usings.add("System.Threading.Channels")
    if ("System.Collections.Generic" !in usings) usings.add("System.Collections.Generic")
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
  tracker: CollectionHelperTracker,
): List<CirMember> {
  val funcName: String = func.simpleName.asString()
  val csName: String = toCSharpName(toCName(funcName))
    .replaceFirstChar { it.uppercase() }
  val receiverPrefix: String = receiverName.lowercase()
  val cname: String = "${receiverPrefix}_${toCName(funcName)}"

  val returnType = func.returnType?.resolve()?.expandAliases()
  val kotlinReturnType: String = returnType?.declaration?.simpleName?.asString() ?: "Unit"
  val kotlinReturnQualified: String? = returnType?.declaration?.qualifiedName?.asString()
  val isNullableReturn: Boolean = returnType?.isMarkedNullable == true

  val isExportedReceiver: Boolean = receiverQualified in exportedTypes

  val nativeReceiverType: String = if (isExportedReceiver) "IntPtr" else mapParamType(receiverName)
  val receiverParamName: String = if (isExportedReceiver) "handle" else "receiver"

  val extraParams: List<CirParameter> = func.parameters.map { param ->
    val kotlinType: String = param.type.resolve().expandAliases().declaration.simpleName.asString()
    CirParameter(param.name?.asString() ?: "_", mapParamType(kotlinType))
  }

  val csReceiverType: String = if (isExportedReceiver) receiverName else mapParamType(receiverName)
  val csReceiverParamName: String = if (isExportedReceiver) receiverName.lowercase() else "receiver"

  val wrapperParams: List<CirParameter> = listOf(
    CirParameter(csReceiverParamName, csReceiverType, nativeReceiverType),
  ) + extraParams

  val nativeCallReceiver: String = if (isExportedReceiver) "${csReceiverParamName}._handle" else "receiver"
  val nativeCallArgs: String = (listOf(nativeCallReceiver) + extraParams.map { it.name }).joinToString(", ")

  // ADR-061: the same return-marshalling cascade as the class-method position (mirrored 1:1;
  // see ClassExports.kt's `methods.forEach` loop / CirClassTranslator's `translateClass` "methods"
  // block for the shared design rationale). Enum returns are left exactly as before.
  val isEnumReturn: Boolean = (returnType?.declaration as? KSClassDeclaration)
    ?.classKind == ClassKind.ENUM_CLASS
  val isListReturn: Boolean = !isEnumReturn &&
      (kotlinReturnQualified == "kotlin.collections.List" ||
          kotlinReturnQualified == "kotlin.collections.MutableList")
  val isMutableListReturn: Boolean = kotlinReturnQualified == "kotlin.collections.MutableList"
  val listElementType: String? = if (isListReturn) {
    val elementType = returnType?.arguments?.firstOrNull()?.type?.resolve()
    val elementTypeName: String = elementType?.declaration?.simpleName?.asString() ?: "Any"
    KOTLIN_TO_CSHARP_PARAM[elementTypeName] ?: elementTypeName
  } else null
  if (isListReturn) tracker.needsList = true

  // "Char" is deliberately treated as primitive-ish here — see CirClassTranslator.kt's identical
  // exclusion on the mirrored class-method cascade.
  val isPrimitiveReturn: Boolean =
    kotlinReturnType in KOTLIN_TO_CSHARP_RETURN || kotlinReturnType == "Char"
  val isObjectReturn: Boolean = !isEnumReturn && !isPrimitiveReturn && !isListReturn
  val isNullableStringReturn: Boolean =
    isPrimitiveReturn && isNullableReturn && kotlinReturnType == "String"
  val isNullablePrimitiveReturn: Boolean =
    isPrimitiveReturn && isNullableReturn && kotlinReturnType != "String"

  val nativeReturnType: String
  val nativeExtraParams: List<CirParameter>
  val csReturnType: String
  val body: String

  when {
    kotlinReturnType == "Unit" -> {
      nativeReturnType = "void"
      nativeExtraParams = emptyList()
      csReturnType = "void"
      body = checkedExtensionBody(null, "", csName, nativeCallArgs)
    }

    isListReturn -> {
      nativeReturnType = "IntPtr"
      nativeExtraParams = emptyList()
      csReturnType =
        if (isMutableListReturn) "IList<$listElementType>" else "IReadOnlyList<$listElementType>"
      body = buildString {
        appendLine()
        appendLine("            IntPtr listHandle = Native_$csName(${syncErrorArguments(nativeCallArgs)});")
        appendLine("            if (error != IntPtr.Zero)")
        appendLine("            {")
        appendLine("                throw NugetErrorNative.BuildException(error);")
        appendLine("            }")
        appendLine("            int count = NugetListNative.Count(listHandle);")
        appendLine("            var result = new List<$listElementType>(count);")
        appendLine("            for (int i = 0; i < count; i++)")
        appendLine("            {")
        appendLine("                result.Add(NugetMarshal.FromHandle<$listElementType>(NugetListNative.Get(listHandle, i)));")
        appendLine("            }")
        appendLine("            NugetListNative.Dispose(listHandle);")
        append(if (isMutableListReturn) "            return result;" else "            return result.AsReadOnly();")
      }
    }

    isObjectReturn && isNullableReturn -> {
      nativeReturnType = "IntPtr"
      nativeExtraParams = emptyList()
      csReturnType = "$kotlinReturnType?"
      body = buildString {
        appendLine()
        appendLine("            IntPtr nativeResult = Native_$csName(${syncErrorArguments(nativeCallArgs)});")
        appendLine("            if (error != IntPtr.Zero)")
        appendLine("            {")
        appendLine("                throw NugetErrorNative.BuildException(error);")
        appendLine("            }")
        append("            return nativeResult == IntPtr.Zero ? null : new $kotlinReturnType(nativeResult);")
      }
    }

    isObjectReturn -> {
      nativeReturnType = "IntPtr"
      nativeExtraParams = emptyList()
      csReturnType = kotlinReturnType
      body = buildString {
        appendLine()
        appendLine("            IntPtr nativeResult = Native_$csName(${syncErrorArguments(nativeCallArgs)});")
        appendLine("            if (error != IntPtr.Zero)")
        appendLine("            {")
        appendLine("                throw NugetErrorNative.BuildException(error);")
        appendLine("            }")
        append("            return new $kotlinReturnType(nativeResult);")
      }
    }

    isNullableStringReturn -> {
      nativeReturnType = "IntPtr"
      nativeExtraParams = emptyList()
      csReturnType = "string?"
      body = buildString {
        appendLine()
        appendLine("            IntPtr nativeResult = Native_$csName(${syncErrorArguments(nativeCallArgs)});")
        appendLine("            if (error != IntPtr.Zero)")
        appendLine("            {")
        appendLine("                throw NugetErrorNative.BuildException(error);")
        appendLine("            }")
        append("            return Marshal.PtrToStringUTF8(nativeResult);")
      }
    }

    isNullablePrimitiveReturn -> {
      val csValueType: String = mapParamType(kotlinReturnType)
      nativeReturnType = "bool"
      nativeExtraParams = listOf(CirParameter("value", csValueType, "out $csValueType"))
      csReturnType = "$csValueType?"
      body = buildString {
        appendLine()
        appendLine("            bool hasValue = Native_$csName(${syncErrorArguments("$nativeCallArgs, out $csValueType value")});")
        appendLine("            if (error != IntPtr.Zero)")
        appendLine("            {")
        appendLine("                throw NugetErrorNative.BuildException(error);")
        appendLine("            }")
        append("            return hasValue ? value : null;")
      }
    }

    kotlinReturnType == "String" -> {
      nativeReturnType = "IntPtr"
      nativeExtraParams = emptyList()
      csReturnType = "string"
      body =
        checkedExtensionBody("IntPtr", "Marshal.PtrToStringUTF8(result)!", csName, nativeCallArgs)
    }

    else -> {
      nativeReturnType = mapReturnType(kotlinReturnType)
      nativeExtraParams = emptyList()
      csReturnType = KOTLIN_TO_CSHARP_PARAM[kotlinReturnType] ?: kotlinReturnType
      body = checkedExtensionBody(csReturnType, "result", csName, nativeCallArgs)
    }
  }

  val allNativeParams: List<CirParameter> = listOf(
    CirParameter(receiverParamName, nativeReceiverType),
  ) + extraParams + nativeExtraParams

  val dllImport = CirDllImport(
    libraryName = libraryName,
    entryPoint = cname,
    returnType = nativeReturnType,
    name = "Native_$csName",
    parameters = allNativeParams,
    visibility = CirVisibility.PRIVATE,
    hasSyncErrorOut = true,
  )

  val wrapper = CirMethod(
    name = csName,
    returnType = csReturnType,
    nativeReturnType = nativeReturnType,
    nativeName = "Native_$csName",
    parameters = wrapperParams,
    body = body,
    isStatic = true,
    isExtension = true,
  )

  return listOf(dllImport, wrapper)
}

private fun checkedExtensionBody(
  resultType: String?,
  returnValue: String,
  csName: String,
  arguments: String,
): String = buildString {
  appendLine()
  val call: String = "Native_$csName(${syncErrorArguments(arguments)})"
  if (resultType == null) appendLine("            $call;")
  else appendLine("            $resultType result = $call;")
  appendLine("            if (error != IntPtr.Zero)")
  appendLine("            {")
  appendLine("                throw NugetErrorNative.BuildException(error);")
  appendLine("            }")
  if (resultType != null) append("            return $returnValue;")
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
    hasSyncErrorOut = true,
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
    body = buildString {
      appendLine()
      appendLine("            IntPtr nativeResult = Native_$csName($nativeCallReceiver, out IntPtr error);")
      appendLine("            if (error != IntPtr.Zero)")
      appendLine("            {")
      appendLine("                throw NugetErrorNative.BuildException(error);")
      appendLine("            }")
      append("            return Marshal.PtrToStringUTF8(nativeResult)!;")
    }
  } else {
    csReturnType = KOTLIN_TO_CSHARP_PARAM[kotlinReturnType] ?: kotlinReturnType
    body = buildString {
      appendLine()
      appendLine("            $csReturnType result = Native_$csName($nativeCallReceiver, out IntPtr error);")
      appendLine("            if (error != IntPtr.Zero)")
      appendLine("            {")
      appendLine("                throw NugetErrorNative.BuildException(error);")
      appendLine("            }")
      append("            return result;")
    }
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

    members.add(
      CirDllImport(
        libraryName = libraryName,
        entryPoint = "get_$cname",
        returnType = "bool",
        name = "Native_Get_$propName",
        parameters = emptyList(),
        visibility = CirVisibility.PRIVATE,
        hasSyncErrorOut = true,
      )
    )

    members.add(
      CirDllImport(
        libraryName = libraryName,
        entryPoint = "get_${cname}_value",
        returnType = csValueType,
        name = "Native_Get_${propName}_value",
        parameters = emptyList(),
        visibility = CirVisibility.PRIVATE,
        hasSyncErrorOut = true,
      )
    )

    if (isMutable) {
      members.add(
        CirDllImport(
          libraryName = libraryName,
          entryPoint = "set_$cname",
          returnType = "void",
          name = "Native_Set_$propName",
          parameters = listOf(CirParameter("value", csValueType)),
          visibility = CirVisibility.PRIVATE,
          hasSyncErrorOut = true,
        )
      )

      members.add(
        CirDllImport(
          libraryName = libraryName,
          entryPoint = "set_${cname}_null",
          returnType = "void",
          name = "Native_Set_${propName}_null",
          parameters = emptyList(),
          visibility = CirVisibility.PRIVATE,
          hasSyncErrorOut = true,
        )
      )
    }

    val getter: String = buildString {
      appendLine()
      appendLine("                bool hasValue = Native_Get_$propName(out IntPtr error);")
      appendLine("                if (error != IntPtr.Zero)")
      appendLine("                {")
      appendLine("                    throw NugetErrorNative.BuildException(error);")
      appendLine("                }")
      appendLine("                if (!hasValue) return null;")
      appendLine("                $csValueType value = Native_Get_${propName}_value(out IntPtr error2);")
      appendLine("                if (error2 != IntPtr.Zero)")
      appendLine("                {")
      appendLine("                    throw NugetErrorNative.BuildException(error2);")
      appendLine("                }")
      append("                return value;")
    }

    val setter: String? = if (isMutable) buildString {
      appendLine()
      appendLine("                if (value.HasValue)")
      appendLine("                {")
      appendLine("                    Native_Set_$propName(value.Value, out IntPtr error);")
      appendLine("                    if (error != IntPtr.Zero)")
      appendLine("                    {")
      appendLine("                        throw NugetErrorNative.BuildException(error);")
      appendLine("                    }")
      appendLine("                }")
      appendLine("                else")
      appendLine("                {")
      appendLine("                    Native_Set_${propName}_null(out IntPtr error);")
      appendLine("                    if (error != IntPtr.Zero)")
      appendLine("                    {")
      appendLine("                        throw NugetErrorNative.BuildException(error);")
      appendLine("                    }")
      append("                }")
    } else null

    members.add(
      CirProperty(
        name = csPropName,
        type = "$csValueType?",
        nativeReturnType = csValueType,
        nativeName = propName,
        getter = getter,
        setter = setter,
        isStatic = true,
        hasSyncErrorOut = true,
      )
    )

    return members
  }

  val csNativeReturnType: String = mapReturnType(propType)

  members.add(
    CirDllImport(
      libraryName = libraryName,
      entryPoint = "get_$cname",
      returnType = csNativeReturnType,
      name = "Native_Get_$propName",
      parameters = emptyList(),
      visibility = CirVisibility.PRIVATE,
      hasSyncErrorOut = true,
    )
  )

  val csType: String
  val getter: String
  val setter: String?

  if (propType == "String" && isNullable) {
    csType = "string?"
    getter = buildString {
      appendLine()
      appendLine("                IntPtr nativeResult = Native_Get_$propName(out IntPtr error);")
      appendLine("                if (error != IntPtr.Zero)")
      appendLine("                {")
      appendLine("                    throw NugetErrorNative.BuildException(error);")
      appendLine("                }")
      append("                return Marshal.PtrToStringUTF8(nativeResult);")
    }
    setter = if (isMutable) buildString {
      appendLine()
      appendLine("                Native_Set_$propName(value, out IntPtr error);")
      appendLine("                if (error != IntPtr.Zero)")
      appendLine("                {")
      appendLine("                    throw NugetErrorNative.BuildException(error);")
      append("                }")
    } else null

    if (isMutable) {
      members.add(
        CirDllImport(
          libraryName = libraryName,
          entryPoint = "set_$cname",
          returnType = "void",
          name = "Native_Set_$propName",
          parameters = listOf(CirParameter("value", "string?")),
          visibility = CirVisibility.PRIVATE,
          hasSyncErrorOut = true,
        )
      )
    }
  } else if (propType == "String") {
    csType = "string"
    getter = buildString {
      appendLine()
      appendLine("                IntPtr nativeResult = Native_Get_$propName(out IntPtr error);")
      appendLine("                if (error != IntPtr.Zero)")
      appendLine("                {")
      appendLine("                    throw NugetErrorNative.BuildException(error);")
      appendLine("                }")
      append("                return Marshal.PtrToStringUTF8(nativeResult)!;")
    }
    setter = if (isMutable) buildString {
      appendLine()
      appendLine("                Native_Set_$propName(value, out IntPtr error);")
      appendLine("                if (error != IntPtr.Zero)")
      appendLine("                {")
      appendLine("                    throw NugetErrorNative.BuildException(error);")
      append("                }")
    } else null

    if (isMutable) {
      members.add(
        CirDllImport(
          libraryName = libraryName,
          entryPoint = "set_$cname",
          returnType = "void",
          name = "Native_Set_$propName",
          parameters = listOf(CirParameter("value", "string")),
          visibility = CirVisibility.PRIVATE,
          hasSyncErrorOut = true,
        )
      )
    }
  } else {
    csType = csNativeReturnType
    getter = buildString {
      appendLine()
      appendLine("                $csNativeReturnType result = Native_Get_$propName(out IntPtr error);")
      appendLine("                if (error != IntPtr.Zero)")
      appendLine("                {")
      appendLine("                    throw NugetErrorNative.BuildException(error);")
      appendLine("                }")
      append("                return result;")
    }
    setter = if (isMutable) buildString {
      appendLine()
      appendLine("                Native_Set_$propName(value, out IntPtr error);")
      appendLine("                if (error != IntPtr.Zero)")
      appendLine("                {")
      appendLine("                    throw NugetErrorNative.BuildException(error);")
      append("                }")
    } else null

    if (isMutable) {
      members.add(
        CirDllImport(
          libraryName = libraryName,
          entryPoint = "set_$cname",
          returnType = "void",
          name = "Native_Set_$propName",
          parameters = listOf(CirParameter("value", csNativeReturnType)),
          visibility = CirVisibility.PRIVATE,
          hasSyncErrorOut = true,
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
      hasSyncErrorOut = true,
    )
  )

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
