package io.github.xxfast.kotlin.native.nuget.processor.cir

import com.google.devtools.ksp.symbol.KSType
import io.github.xxfast.kotlin.native.nuget.processor.exports.awaitsSettableMutableStateFlow
import io.github.xxfast.kotlin.native.nuget.processor.asCSymbol
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import io.github.xxfast.kotlin.native.nuget.processor.ForwardSymbolTable
import io.github.xxfast.kotlin.native.nuget.processor.freshName
import io.github.xxfast.kotlin.native.nuget.processor.nonNullStringOrThrow
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardStaticSuspendReceiver
import io.github.xxfast.kotlin.native.nuget.processor.forward.forwardPublicCsharpType
import io.github.xxfast.kotlin.native.nuget.processor.forward.csharpAsyncMemberName
import io.github.xxfast.kotlin.native.nuget.processor.forward.BridgeType
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardBridgeTypeClassifier
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardCallablePlanCatalog
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardLegacyNames
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardLegacyReturnShape
import io.github.xxfast.kotlin.native.nuget.processor.forward.legacyBytesCsharpType
import io.github.xxfast.kotlin.native.nuget.processor.forward.legacyBytesRead
import io.github.xxfast.kotlin.native.nuget.processor.forward.legacyEnvelopeCsharpType
import io.github.xxfast.kotlin.native.nuget.processor.forward.legacyEnvelopeRead
import io.github.xxfast.kotlin.native.nuget.processor.forward.legacyMarshalledRead
import io.github.xxfast.kotlin.native.nuget.processor.forward.legacyDiscriminatedRead
import io.github.xxfast.kotlin.native.nuget.processor.forward.legacyRefusedParameter
import io.github.xxfast.kotlin.native.nuget.processor.forward.declaredCsharpType
import io.github.xxfast.kotlin.native.nuget.processor.forward.legacyInterfaceRead
import io.github.xxfast.kotlin.native.nuget.processor.forward.legacyReturnShape
import io.github.xxfast.kotlin.native.nuget.processor.forward.legacyHandleRead
import io.github.xxfast.kotlin.native.nuget.processor.forward.legacyValueClassRead
import io.github.xxfast.kotlin.native.nuget.processor.forward.legacyEnumRead
import io.github.xxfast.kotlin.native.nuget.processor.forward.legacyBoxedRead
import io.github.xxfast.kotlin.native.nuget.processor.forward.forwardMemberGenericRefusal
import io.github.xxfast.kotlin.native.nuget.processor.forward.forwardCsharpMethodTypeParameterName

internal fun translateSuspendFunction(
  func: KSFunctionDeclaration,
  libraryName: String,
  /** ADR-163: the one symbol table, so this half agrees with `SuspendFunctionExports`. */
  symbols: ForwardSymbolTable,
  tracker: CollectionHelperTracker,
  exportedTypes: Set<String>,
  logger: KSPLogger,
  // ADR-114: the top-level suspend route compiles today (its parameters keep their type
  // arguments), but hands C# an IntPtr no caller can produce. Same classification as every other
  // legacy route.
  classifier: ForwardBridgeTypeClassifier,
  // ROADMAP line 29 (ADR-118 amendment): the planner's ADR-095 number, the same one
  // `addSuspendFunctionExports` reads.
  callableCatalog: ForwardCallablePlanCatalog,
  /** ADR-068 (2026-09-27 amendment): the namespace a `StateFlow<T>` element is spelled in. */
  context: NugetContext,
  /** ADR-229: a static owner's export stem, minted by the planner; null for a top-level function. */
  stem: String? = null,
  /** ADR-229: an extension's or an enum member's receiver, the C# `this` parameter. */
  receiver: ForwardStaticSuspendReceiver? = null,
  /**
   * ADR-229: a value-class member's receiver, the struct's own underlying. Not a public parameter:
   * the member renders as an instance method that passes this argument first.
   */
  structReceiver: CirParameter? = null,
): List<CirMember> {
  if (classifier.legacyRefusedParameter(func.parameters) != null) return emptyList()
  // ADR-214: the Kotlin half's own admission, ADR-197/211's rules for a function's own `T`.
  if (func.forwardMemberGenericRefusal() != null) return emptyList()
  val returnType = func.returnType?.resolve()?.expandAliases()
  // ADR-119: a generic return that is not a marshallable collection skips on both halves.
  val returnShape: ForwardLegacyReturnShape = classifier.legacyReturnShape(returnType)
  if (returnShape is ForwardLegacyReturnShape.Refused) return emptyList()
  val collectionReturn: BridgeType.Collection? =
    (returnShape as? ForwardLegacyReturnShape.Marshalled)?.type
  if (collectionReturn != null) tracker.trackCollection(collectionReturn)
  // ROADMAP Phase 4: the class route's bytes arm, for a top-level `suspend fun f(): ByteArray`.
  if (returnShape is ForwardLegacyReturnShape.Bytes) tracker.needsBytes = true

  // ROADMAP line 29: the overload number goes on the entry point, the extern's name AND the
  // wrapper's call site. On a same-wire pair (both externs take identical native parameters)
  // numbering the entry point alone is CS0111, and numbering the extern but not the call site
  // silently binds the second body to the first overload's extern.
  val suffix: String = callableCatalog.overloadSuffix(func)
  // ADR-163: library- and package-qualified; `_async` is appended by the entry points below.
  val cname: String = stem ?: (symbols.topLevel(func) + suffix)
  // ADR-110: the case change alone, so `suspend fun lock()` renders `LockAsync`;
  // the renderer escapes.
  // ADR-163: from the declaration name, not from [cname].
  val csName: String = func.simpleName.asString().asCSymbol().replaceFirstChar { it.uppercase() }
  // The public name stays `${csName}Async`: the overloads are one natural C# overload set.
  val nativeName: String = "${csName}${suffix}Async_native"
  val kotlinReturnType: String = returnType?.declaration?.simpleName?.asString() ?: "Unit"
  val isUnit: Boolean = kotlinReturnType == "Unit"

  val declared: List<CirParameter> = legacyRouteParameters(
    func.parameters, classifier, tracker,
    callableCatalog.legacyDefaultFlags(func),
    callableCatalog.legacySuspendSiblingArities(func),
  )
  // ADR-229: the receiver leads, named apart from the declared parameters.
  val receiverParameter: CirParameter? = receiver?.cirReceiverParameter(
    freshName("receiver", declared.localScopeNames()),
  )
  val params: List<CirParameter> = listOfNotNull(receiverParameter) + declared

  // ADR-068 (2026-09-27 amendment): the class route's StateFlow bucket, on a top-level function.
  // The Kotlin half is the plain top-level export (the awaited flow is already minted as a handle);
  // only the C# spelling and the completion differ, and those come from the class route's helper.
  val stateFlowElement: SuspendStateFlowElement? =
    if (
      returnType?.declaration?.qualifiedName?.asString() in STATE_FLOW_TYPES ||
      returnType?.declaration?.qualifiedName?.asString() in FLOW_TYPES
    ) {
      suspendStateFlowElement(returnType, classifier, context, tracker)
    } else {
      null
    }
  if (stateFlowElement != null) {
    tracker.needsFlow = true
    if (returnType?.declaration?.qualifiedName?.asString() in STATE_FLOW_TYPES) {
      tracker.needsStateFlow = true
      tracker.needsSuspendStateFlow = true
    }
  }

  // Issue #108: a nullable Kotlin return has to reach C# as `Task<T?>`, otherwise a null result
  // is read back as a `0` primitive or as a live wrapper over `IntPtr.Zero`.
  // ADR-230: the class route's settable awaited `MutableStateFlow<T>` (ADR-071 held-route
  // amendment), written through the flow-keyed `_set_value` the static route now exports too.
  val settable: Boolean = func.awaitsSettableMutableStateFlow() &&
    stateFlowElement != null && !stateFlowElement.acquiredFlow
  val write: CirStateFlowWrite? = if (settable) {
    tracker.needsMutableStateFlow = true
    val elementType: KSType? =
      returnType?.arguments?.firstOrNull()?.type?.resolve()?.expandAliases()
    mutableStateFlowWrite(elementType, requireNotNull(stateFlowElement).elementType)
  } else {
    null
  }
  val asyncReturnType: String = when {
    isUnit -> ""
    write != null -> "KotlinMutableStateFlow<${requireNotNull(stateFlowElement).elementType}>"
    stateFlowElement != null -> stateFlowElement.asyncReturnType
    returnShape is ForwardLegacyReturnShape.Marshalled -> returnShape.declaredCsharpType()
    // ROADMAP Phase 4: `Task<byte[]>`, the class route's own line.
    returnShape is ForwardLegacyReturnShape.Bytes -> legacyBytesCsharpType(returnShape.nullable)
    returnShape is ForwardLegacyReturnShape.Envelope ->
      legacyEnvelopeCsharpType(returnShape.nullable)
    // ADR-040: the class route's own line -- the projected interface, not the backing wrapper
    // `nestedCsName()` would spell below. `strayPetLater()` is `Task<IPet>`, ADR-040's own example.
    returnShape is ForwardLegacyReturnShape.Interface -> returnShape.declaredCsharpType()
    // ADR-204: `I<Name>`, which `nestedCsName()` below cannot spell.
    returnShape is ForwardLegacyReturnShape.Discriminated && returnShape.isInterface ->
      returnShape.declaredCsharpType()
    // ROADMAP Phase 4 line 23: `global::`-qualified, so a dependency type in another namespace
    // resolves; a value class is the record struct `NugetUnbox` returns.
    returnShape is ForwardLegacyReturnShape.Handle -> returnShape.declaredCsharpType()
    returnShape is ForwardLegacyReturnShape.ValueClass -> returnShape.declaredCsharpType()
    returnShape is ForwardLegacyReturnShape.Enum -> returnShape.declaredCsharpType()
    returnShape is ForwardLegacyReturnShape.Boxed -> returnShape.declaredCsharpType()
    else -> {
      // ADR-118: same speller as the class route -- a nested sealed arm return carries its
      // enclosing base, a top-level type stays bare.
      val csharp: String = KOTLIN_TO_CSHARP_PARAM[kotlinReturnType]
        ?: (returnType?.declaration as? KSClassDeclaration)?.nestedCsName()
        ?: kotlinReturnType
      if (returnType?.isMarkedNullable == true) "$csharp?" else csharp
    }
  }

  tracker.needsAsync = true

  // ADR-102: raw thunk address (NugetThunks.NugetAsyncCallbackPtr), not a marshalled delegate.
  val callbackType: String = "IntPtr"
  // The trailing slot moves off a user parameter spelled `userData`, as the Kotlin export's does.
  val slotNames: ForwardLegacyNames =
    legacyCsharpNames(func.parameters, classifier, callableCatalog.legacyDefaultFlags(func))
  val nativeParams: List<CirParameter> =
    listOfNotNull(structReceiver).nativeImportParameters() + params.nativeImportParameters() +
      listOf(
        CirParameter(slotNames.callback, callbackType),
        CirParameter(slotNames.userData, "IntPtr"),
      )

  val nativeImport = CirDllImport(
    libraryName = libraryName,
    entryPoint = "${cname}_async",
    returnType = "IntPtr",
    name = nativeName,
    parameters = nativeParams,
    visibility = CirVisibility.PRIVATE,
  )

  // ADR-026 amendment (2026-10-09): a nullable acquired `Flow<T>?` awaits to `KotlinFlow<T>?`; the
  // `?` stays off [asyncReturnType], which the completion reuses inside `new ...(`.
  val acquiredFlowNullable: Boolean = stateFlowElement?.memberNullable == true
  val taskReturnType: String = when {
    isUnit -> "Task"
    acquiredFlowNullable -> "Task<$asyncReturnType?>"
    else -> "Task<$asyncReturnType>"
  }

  // ADR-209: the class route's awaited shared-flow seams, under this route's extern spelling.
  val shared: CirSharedFlow? =
    stateFlowElement?.let { awaitedSharedFlow(it, returnType, "${nativeName}_") }

  val asyncMethod = CirMethod(
    name = func.csharpAsyncMemberName(),
    returnType = taskReturnType,
    nativeName = nativeName,
    parameters = params,
    body = "",
    isStatic = structReceiver == null,
    isExtension = receiverParameter != null,
    asyncReceiver = structReceiver,
    isAsync = true,
    asyncReturnType = asyncReturnType,
    typeParameters = func.cirMethodTypeParameters(context, logger),
    flowElementRead = stateFlowElement?.read,
    flowElementNullable = stateFlowElement?.elementNullable == true,
    acquiredFlowNullable = acquiredFlowNullable,
    acquiredFlowCollectNativeName =
      if (stateFlowElement?.acquiredFlow == true) "${nativeName}_collect" else null,
    isAwaitedStateFlow = stateFlowElement != null && !stateFlowElement.acquiredFlow,
    sharedFlow = shared,
    stateFlowWrite = write,
    stateFlowSetValueNativeName = if (write != null) "${nativeName}_SetValue" else "",
    stateFlowCompareAndSetNativeName = if (write != null) "${nativeName}_CompareAndSet" else "",
    awaitedStateFlowCollectNativeName =
      if (stateFlowElement?.collection != null) "${nativeName}_collect" else null,
    awaitedStateFlowValueNativeName =
      if (stateFlowElement?.collection != null) "${nativeName}_value" else null,
    // ADR-119 / ADR-131: the top-level route's own copy of the class route's decision, exhaustive
    // for the same reason -- the two routes have to answer a new return shape identically.
    asyncResultRead = when (returnShape) {
      is ForwardLegacyReturnShape.Marshalled -> returnShape.legacyMarshalledRead("resultPtr")

      is ForwardLegacyReturnShape.Discriminated -> legacyDiscriminatedRead(
        handle = "resultPtr",
        csharpType = asyncReturnType.removeSuffix("?"),
        nullable = returnShape.nullable,
      )

      // ADR-040: the backing wrapper, for the class route's reason -- the declared type is now
      // the interface, and only the wrapper has a handle constructor.
      is ForwardLegacyReturnShape.Interface -> returnShape.legacyInterfaceRead("resultPtr")

      // ROADMAP Phase 4: `NugetMarshal.ReadBytes(resultPtr)`, the class route's own line.
      is ForwardLegacyReturnShape.Bytes -> legacyBytesRead("resultPtr", returnShape.nullable)
      // ADR-201 amendment: the awaited envelope, rebuilt and disposed by `BuildException`.
      is ForwardLegacyReturnShape.Envelope -> legacyEnvelopeRead("resultPtr", returnShape.nullable)

      // ROADMAP Phase 4 line 23: the qualified handle constructor, and `NugetUnbox` for a value
      // class.
      is ForwardLegacyReturnShape.Handle -> returnShape.legacyHandleRead("resultPtr")

      is ForwardLegacyReturnShape.ValueClass -> returnShape.legacyValueClassRead("resultPtr")

      // The boxed ordinal, cast back to the enum (never handed back as an `int`).
      is ForwardLegacyReturnShape.Enum -> returnShape.legacyEnumRead("resultPtr")

      // ADR-214: the plan route's read of a boxed `T`.
      is ForwardLegacyReturnShape.Boxed -> returnShape.legacyBoxedRead("resultPtr")

      ForwardLegacyReturnShape.Plain, is ForwardLegacyReturnShape.Refused -> null
    },
  )

  val collector: CirDllImport? = asyncMethod.acquiredFlowCollectNativeName?.let { name ->
    acquiredFlowCollectImport(libraryName, cname, name)
  }
  // ADR-068, collection element: the per-member pair the awaited holder reads through.
  val awaitedPair: List<CirDllImport> = if (stateFlowElement?.collection != null) {
    awaitedStateFlowCollectionImports(
      libraryName, cname, "${nativeName}_collect", "${nativeName}_value",
    )
  } else {
    emptyList()
  }
  val writeImports: List<CirDllImport> = write?.let {
    listOf(
      CirDllImport(
        libraryName = libraryName,
        entryPoint = "${cname}_set_value",
        returnType = "void",
        name = "${nativeName}_SetValue",
        parameters = listOf(CirParameter("flowHandle", KOTLIN_HANDLE)) + it.parameters,
        visibility = CirVisibility.PRIVATE,
        hasSyncErrorOut = true,
      ),
      heldStateFlowCompareAndSetImport(libraryName, cname, "${nativeName}_CompareAndSet", it),
    )
  }.orEmpty()
  return listOf(nativeImport, asyncMethod) + listOfNotNull(collector) + awaitedPair + writeImports +
    shared?.let { flowKeyedSharedFlowImports(libraryName, cname, "${nativeName}_", it) }.orEmpty()
}

/**
 * ADR-229: the C# `this` parameter of a static suspend member's receiver, on the slot the Kotlin
 * export declares for it (`addStaticSuspendExport`): a borrowed handle, a scalar, or an ordinal.
 */
internal fun ForwardStaticSuspendReceiver.cirReceiverParameter(name: String): CirParameter =
  when (this) {
    is ForwardStaticSuspendReceiver.Handle -> CirParameter(
      name,
      type = if (nullable) BridgeType.Nullable(type).forwardPublicCsharpType()
      else type.forwardPublicCsharpType(),
      nativeType = KOTLIN_HANDLE,
      nativeArgumentExpression =
        if (nullable) "$name?._handle ?? NugetKotlinHandle.Null" else "$name._handle",
    )

    is ForwardStaticSuspendReceiver.Scalar -> {
      val csharp: String = mapParamType(kotlinType.substringAfterLast('.'))
      CirParameter(
        name,
        csharp,
        isReferenceType = csharp == "string",
        nativeArgumentExpression = if (csharp == "string") nonNullStringOrThrow(name) else null,
      )
    }

    is ForwardStaticSuspendReceiver.Enum -> CirParameter(
      name,
      type = type.csharpType,
      nativeType = "int",
      isReferenceType = false,
      nativeArgumentExpression = "(int)$name",
    )
  }

/**
 * ADR-214: a legacy-route member's own type parameters with their C# constraints, the planned
 * route's spelling (`withMethodTypeParameterConstraints`), for a member the plan never carries.
 */
internal fun KSFunctionDeclaration.cirMethodTypeParameters(
  context: NugetContext,
  logger: KSPLogger,
): List<CirTypeParameter> {
  val owner: String = parentDeclaration?.simpleName?.asString()?.let { "$it." }.orEmpty()
  return typeParameters.map { typeParameter ->
    CirTypeParameter(
      forwardCsharpMethodTypeParameterName(typeParameter),
      typeParameter.cirConstraints(
        context, logger, this,
        "$owner${simpleName.asString()}<${typeParameter.name.asString()}>",
      ),
    )
  }
}
