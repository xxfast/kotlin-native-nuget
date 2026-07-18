package io.github.xxfast.kotlin.native.nuget.processor.cir

internal fun CirClass.ordinaryNativeImports(): List<CirDllImport> = buildList {
  if (!isAbstract) {
    constructor?.let { ctor -> add(constructorNativeImport(ctor)) }
    secondaryConstructors.forEach { ctor -> add(constructorNativeImport(ctor)) }
  }

  properties
    .filterNot { property -> property.usesLegacyNativeImport() }
    .forEach { property -> addAll(propertyNativeImports(property)) }

  methods
    .filter { method -> !method.isAbstract && !method.isAsync && !method.isFlow }
    .forEach { method -> add(methodNativeImport(method)) }

  if (isDataClass) addAll(dataClassNativeImports())
  disposeNativeImport()?.let { nativeImport -> add(nativeImport) }
}

internal fun CirClass.constructorNativeImport(ctor: CirConstructor): CirDllImport = CirDllImport(
  libraryName = libraryName,
  entryPoint = "${nativePrefix}_create${ctor.nativeSuffix}",
  returnType = "IntPtr",
  name = "Native_Create${ctor.nativeSuffix}",
  parameters = ctor.parameters.map { parameter -> parameter.copy(nativeType = parameter.type) },
  visibility = CirVisibility.PRIVATE,
  hasSyncErrorOut = true,
)

internal fun CirClass.propertyNativeImports(property: CirProperty): List<CirDllImport> = buildList {
  require(!property.usesLegacyNativeImport()) {
    "Specialized properties use a named legacy native-import route"
  }
  add(
    CirDllImport(
      libraryName = libraryName,
      entryPoint = "${nativePrefix}_get_${property.nativeName}",
      returnType = property.nativeReturnType,
      name = "Native_Get_${property.nativeName}",
      parameters = listOf(CirParameter("handle", "IntPtr")),
      visibility = CirVisibility.PRIVATE,
      hasSyncErrorOut = property.hasSyncErrorOut,
      marshalBooleanReturn = property.nativeReturnType == "bool",
    )
  )

  if (property.setter != null) {
    add(
      CirDllImport(
        libraryName = libraryName,
        entryPoint = "${nativePrefix}_set_${property.nativeName}",
        returnType = "void",
        name = "Native_Set_${property.nativeName}",
        parameters = listOf(
          CirParameter("handle", "IntPtr"),
          CirParameter("value", property.nativeSetterType),
        ),
        visibility = CirVisibility.PRIVATE,
        hasSyncErrorOut = property.hasSyncErrorOut,
      )
    )
  }

  property.extraNatives.forEach { extra ->
    val parameters: List<CirParameter> = buildList {
      add(CirParameter("handle", "IntPtr"))
      if (extra.hasValueParam) add(CirParameter("value", extra.returnType))
    }
    add(
      CirDllImport(
        libraryName = libraryName,
        entryPoint = "${nativePrefix}_${extra.entryPointSuffix}",
        returnType = if (extra.hasValueParam) "void" else extra.returnType,
        name = extra.name,
        parameters = parameters,
        visibility = CirVisibility.PRIVATE,
        hasSyncErrorOut = extra.hasSyncErrorOut,
        marshalBooleanReturn = !extra.hasValueParam && extra.returnType == "bool",
      )
    )
  }
}

internal fun CirProperty.usesLegacyNativeImport(): Boolean {
  val isLambda: Boolean = type.startsWith("KotlinFunc<")
  val isSuspendLambda: Boolean = type.startsWith("KotlinSuspendFunc<") ||
      type.startsWith("KotlinSuspendAction")
  return isFlow || isLambda || isSuspendLambda
}

internal fun CirClass.methodNativeImport(method: CirMethod): CirDllImport {
  require(!method.isAbstract && !method.isAsync && !method.isFlow) {
    "Only ordinary synchronous concrete methods have normalized native imports"
  }
  val parameters: List<CirParameter> = buildList {
    add(CirParameter("handle", "IntPtr"))
    addAll(method.parameters)
    addAll(method.extraNativeParams.map { declaration -> declaration.toRawNativeParameter() })
  }
  return CirDllImport(
    libraryName = libraryName,
    entryPoint = "${nativePrefix}_${method.nativeName}",
    returnType = method.nativeReturnType,
    name = "Native_${method.name}",
    parameters = parameters,
    visibility = CirVisibility.PRIVATE,
    hasSyncErrorOut = method.isSyncErrorCheckEnabled,
  )
}

internal fun CirClass.dataClassNativeImports(): List<CirDllImport> = buildList {
  require(isDataClass) { "Data-class native imports require a data-class CIR node" }
  add(
    CirDllImport(
      libraryName,
      "${nativePrefix}_equals",
      "bool",
      "Native_Equals",
      listOf(CirParameter("handle", "IntPtr"), CirParameter("other", "IntPtr")),
      CirVisibility.PRIVATE,
    )
  )
  add(
    CirDllImport(
      libraryName,
      "${nativePrefix}_hashcode",
      "int",
      "Native_HashCode",
      listOf(CirParameter("handle", "IntPtr")),
      CirVisibility.PRIVATE,
    )
  )
  add(
    CirDllImport(
      libraryName,
      "${nativePrefix}_tostring",
      "IntPtr",
      "Native_ToString",
      listOf(CirParameter("handle", "IntPtr")),
      CirVisibility.PRIVATE,
    )
  )

  constructor?.let { ctor ->
    add(
      CirDllImport(
        libraryName = libraryName,
        entryPoint = "${nativePrefix}_copy",
        returnType = "IntPtr",
        name = "Native_Copy",
        parameters = listOf(CirParameter("handle", "IntPtr")) +
            ctor.parameters.map { parameter -> parameter.copy(nativeType = parameter.type) },
        visibility = CirVisibility.PRIVATE,
        hasSyncErrorOut = true,
      )
    )
  }
}

internal fun CirClass.disposeNativeImport(): CirDllImport? {
  if (!disposable || isAbstract) return null
  return CirDllImport(
    libraryName = libraryName,
    entryPoint = "${nativePrefix}_dispose",
    returnType = "void",
    name = "Native_Dispose",
    parameters = listOf(CirParameter("handle", "IntPtr")),
    visibility = CirVisibility.PRIVATE,
  )
}

internal fun CirValueClass.ordinaryNativeImports(): List<CirDllImport> = buildList {
  constructors.forEachIndexed { index, ctor -> add(constructorNativeImport(index, ctor)) }
  properties.forEach { property -> add(propertyNativeImport(property)) }
  methods.forEach { method -> add(methodNativeImport(method)) }
}

internal fun CirValueClass.constructorNativeImport(
  index: Int,
  ctor: CirValueClassConstructor,
): CirDllImport {
  val suffix: String = if (underlyingIsReference && index > 0) "_$index" else ctor.nativeSuffix
  return CirDllImport(
    libraryName = libraryName,
    entryPoint = ctor.nativeName,
    returnType = if (underlyingType == "string") "IntPtr" else underlyingNativeType,
    name = "Native_Create$suffix",
    parameters = ctor.parameters.map { parameter -> parameter.copy(nativeType = parameter.type) },
    visibility = CirVisibility.PRIVATE,
    hasSyncErrorOut = !underlyingIsReference || ctor.hasErrorCheck,
  )
}

internal fun CirValueClass.propertyNativeImport(property: CirProperty): CirDllImport = CirDllImport(
  libraryName = libraryName,
  entryPoint = "${nativePrefix}_get_${property.nativeName}",
  returnType = property.nativeReturnType,
  name = "Native_Get${property.name}",
  parameters = listOf(CirParameter("value", underlyingNativeType)),
  visibility = CirVisibility.PRIVATE,
)

internal fun CirValueClass.methodNativeImport(method: CirMethod): CirDllImport = CirDllImport(
  libraryName = libraryName,
  entryPoint = "${nativePrefix}_${method.nativeName}",
  returnType = method.nativeReturnType,
  name = "Native_${method.name}",
  parameters = listOf(CirParameter("value", underlyingNativeType)),
  visibility = CirVisibility.PRIVATE,
  marshalBooleanReturn = method.nativeReturnType == "bool",
)

private fun String.toRawNativeParameter(): CirParameter {
  val separator: Int = lastIndexOf(' ')
  require(separator > 0 && separator < lastIndex) {
    "Invalid raw native parameter declaration: '$this'"
  }
  val nativeType: String = substring(0, separator)
  val name: String = substring(separator + 1)
  return CirParameter(name = name, type = nativeType, nativeType = nativeType)
}
