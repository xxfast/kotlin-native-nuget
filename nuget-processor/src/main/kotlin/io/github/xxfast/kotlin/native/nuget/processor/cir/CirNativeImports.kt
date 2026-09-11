package io.github.xxfast.kotlin.native.nuget.processor.cir

internal fun CirClass.ordinaryNativeImports(): List<CirDllImport> = buildList {
  if (!isAbstract) {
    constructor?.let { ctor -> add(constructorNativeImport(ctor)) }
    secondaryConstructors.forEach { ctor -> add(constructorNativeImport(ctor)) }
  }

  properties
    .filter { property -> property.hasNativeImport }
    .filterNot { property -> property.usesLegacyNativeImport() }
    .forEach { property -> addAll(propertyNativeImports(property)) }

  methods
    .filter { method -> !method.isAbstract && !method.isAsync && !method.isFlow }
    .forEach { method -> add(methodNativeImport(method)) }

  if (isDataClass) addAll(dataClassNativeImports())
  disposeNativeImport()?.let { nativeImport -> add(nativeImport) }
}

/**
 * ADR-078 amendment (2026-09-11): the arm's ordinary imports, the mirror of
 * [CirClass.ordinaryNativeImports]. Sealed arms render their property, method, suspend and flow
 * externs from real [CirDllImport] nodes (ADR-111/116/118/124), so the contract check reads them
 * structurally instead of scraping them back out of the rendered `Interop.cs`. What stays on the
 * `SEALED_CLASS` legacy route is what still has no node: the discriminator, dispose and the
 * data-class methods.
 */
internal fun CirSealedSubclass.ordinaryNativeImports(libraryName: String): List<CirDllImport> =
  buildList {
    properties
      .filterNot { property -> property.usesLegacyNativeImport() }
      .forEach { property -> addAll(propertyNativeImports(libraryName, nativePrefix, property)) }

    // No `isAbstract`/`isAsync`/`isFlow` filter: an arm's `methods` holds only plain members by
    // construction, and `methodNativeImport` `require`s exactly that.
    methods.forEach { method -> add(methodNativeImport(libraryName, nativePrefix, method)) }

    addAll((asyncMembers + flowMembers).filterIsInstance<CirDllImport>())
  }

/**
 * ADR-078 amendment (2026-09-11): the sealed **base**'s own imports, the same read as
 * [CirSealedSubclass.ordinaryNativeImports] one level up. The base's members are plan-derived
 * nodes too since ADR-111/ADR-116's base carrier, so the contract check reads them structurally.
 */
internal fun CirSealedClass.ordinaryNativeImports(): List<CirDllImport> = buildList {
  properties
    .filterNot { property -> property.usesLegacyNativeImport() }
    .forEach { property -> addAll(propertyNativeImports(libraryName, nativePrefix, property)) }

  methods.forEach { method -> add(methodNativeImport(libraryName, nativePrefix, method)) }
}

internal fun CirClass.constructorNativeImport(ctor: CirConstructor): CirDllImport = CirDllImport(
  libraryName = libraryName,
  entryPoint = "${nativePrefix}_create${ctor.nativeSuffix}",
  returnType = "IntPtr",
  name = "Native_Create${ctor.nativeSuffix}",
  parameters = ctor.nativeParameters
    ?: ctor.parameters.map { parameter -> parameter.copy(nativeType = parameter.type) },
  visibility = CirVisibility.PRIVATE,
  hasSyncErrorOut = true,
)

internal fun CirClass.propertyNativeImports(property: CirProperty): List<CirDllImport> =
  propertyNativeImports(libraryName, nativePrefix, property)

/**
 * ADR-111: the same imports, addressed by the two strings a [CirClass] would have supplied, so an
 * ADR-009 sealed subclass (a [CirSealedSubclass], not a [CirClass]) mints its property externs
 * through this one rule instead of a hand-written `[DllImport]` line that forgot
 * `[return: MarshalAs(UnmanagedType.I1)]`.
 */
internal fun propertyNativeImports(
  libraryName: String,
  nativePrefix: String,
  property: CirProperty,
): List<CirDllImport> = buildList {
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
  // Issue #114: `KotlinAction` carries no angle bracket at arity 0, exactly as
  // `KotlinSuspendAction` does, so matching on `"KotlinFunc<"` alone drops the property's native
  // import and the getter compiles against an extern that was never emitted.
  val isLambda: Boolean = type.startsWith("KotlinFunc<") || type.startsWith("KotlinAction")
  val isSuspendLambda: Boolean = type.startsWith("KotlinSuspendFunc<") ||
      type.startsWith("KotlinSuspendAction")
  return isFlow || isLambda || isSuspendLambda
}

internal fun CirClass.methodNativeImport(method: CirMethod): CirDllImport =
  methodNativeImport(libraryName, nativePrefix, method)

/**
 * ADR-116: the same import, addressed by the two strings a [CirClass] would have supplied, so an
 * ADR-009 sealed subclass (a [CirSealedSubclass], not a [CirClass]) mints its method externs
 * through this one rule. The lift is exactly the one ADR-111 made for [propertyNativeImports].
 */
internal fun methodNativeImport(
  libraryName: String,
  nativePrefix: String,
  method: CirMethod,
): CirDllImport {
  require(!method.isAbstract && !method.isAsync && !method.isFlow) {
    "Only ordinary synchronous concrete methods have normalized native imports"
  }
  val parameters: List<CirParameter> = buildList {
    add(CirParameter("handle", "IntPtr"))
    addAll(method.nativeParameters ?: method.parameters)
    addAll(method.extraNativeParams.map { declaration -> declaration.toRawNativeParameter() })
  }
  return CirDllImport(
    libraryName = libraryName,
    entryPoint = "${nativePrefix}_${method.nativeName}",
    returnType = method.nativeReturnType,
    name = method.resolvedExternName,
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

  val copy: CirMethod? = copyMethod
  if (copy != null) {
    add(methodNativeImport(copy))
  } else {
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
}

internal fun CirClass.disposeNativeImport(): CirDllImport? {
  if (isAbstract) return null
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
  constructors.forEach { ctor -> add(constructorNativeImport(ctor)) }
  properties.forEach { property -> add(propertyNativeImport(property)) }
  methods.forEach { method -> add(methodNativeImport(method)) }
}

internal fun CirValueClass.constructorNativeImport(ctor: CirValueClassConstructor): CirDllImport =
  CirDllImport(
    libraryName = libraryName,
    entryPoint = ctor.nativeName,
    returnType = if (underlyingType == "string") "IntPtr" else underlyingNativeType,
    name = "Native_Create${ctor.nativeSuffix}",
    // ADR-077: the plan-projected wire shape (an enum parameter imports as `int`, its ordinal).
    // Every value-class constructor is plan-routed since the reference-underlying legacy adapter
    // was deleted, so `nativeParameters` is only absent on a hand-built CIR fixture.
    parameters = ctor.nativeParameters
      ?: ctor.parameters.map { parameter -> parameter.copy(nativeType = parameter.type) },
    visibility = CirVisibility.PRIVATE,
    // Unconditional, because `renderValueClass` calls `Native_Create` with `out IntPtr error`
    // unconditionally: every value-class constructor is planned with `includeError = true`.
    hasSyncErrorOut = true,
  )

internal fun CirValueClass.propertyNativeImport(property: CirProperty): CirDllImport = CirDllImport(
  libraryName = libraryName,
  entryPoint = "${nativePrefix}_get_${property.nativeName}",
  returnType = property.nativeReturnType,
  name = "Native_Get${property.name}",
  parameters = listOf(CirParameter("value", underlyingNativeType)),
  visibility = CirVisibility.PRIVATE,
  marshalBooleanReturn = property.nativeReturnType == "bool",
)

internal fun CirValueClass.methodNativeImport(method: CirMethod): CirDllImport {
  val methodParams: List<CirParameter> = method.nativeParameters
    ?: method.parameters.map { parameter -> parameter.copy(nativeType = parameter.type) }
  return CirDllImport(
    libraryName = libraryName,
    entryPoint = "${nativePrefix}_${method.nativeName}",
    returnType = method.nativeReturnType,
    // ADR-082: the numbered native name, not the (shared) public overload name — see
    // `ForwardCirPlanProjection.valueClassMethod`. Identical for unsuffixed members.
    name = "Native_${method.nativeName.replaceFirstChar { it.uppercase() }}",
    parameters = listOf(CirParameter("value", underlyingNativeType)) + methodParams,
    visibility = CirVisibility.PRIVATE,
    hasSyncErrorOut = method.isSyncErrorCheckEnabled,
    marshalBooleanReturn = method.nativeReturnType == "bool",
  )
}

private fun String.toRawNativeParameter(): CirParameter {
  val separator: Int = lastIndexOf(' ')
  require(separator > 0 && separator < lastIndex) {
    "Invalid raw native parameter declaration: '$this'"
  }
  val nativeType: String = substring(0, separator)
  val name: String = substring(separator + 1)
  return CirParameter(name = name, type = nativeType, nativeType = nativeType)
}
