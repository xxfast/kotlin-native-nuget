package io.github.xxfast.kotlin.native.nuget.processor.cir

import io.github.xxfast.kotlin.native.nuget.processor.asCSymbol
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import io.github.xxfast.kotlin.native.nuget.processor.ForwardSymbolTable
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
  val cname: String = symbols.topLevel(func) + suffix
  // ADR-110: the case change alone, so `suspend fun lock()` renders `LockAsync`;
  // the renderer escapes.
  // ADR-163: from the declaration name, not from [cname].
  val csName: String = func.simpleName.asString().asCSymbol().replaceFirstChar { it.uppercase() }
  // The public name stays `${csName}Async`: the overloads are one natural C# overload set.
  val nativeName: String = "${csName}${suffix}Async_native"
  val kotlinReturnType: String = returnType?.declaration?.simpleName?.asString() ?: "Unit"
  val isUnit: Boolean = kotlinReturnType == "Unit"

  val params: List<CirParameter> = legacyRouteParameters(
    func.parameters, classifier, tracker,
    callableCatalog.legacyDefaultFlags(func),
    callableCatalog.legacySuspendSiblingArities(func),
  )

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
  val asyncReturnType: String = when {
    isUnit -> ""
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
  val nativeParams: List<CirParameter> = params.nativeImportParameters() +
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
    isStatic = true,
    isAsync = true,
    openSlotFills = classifier.openSlotFills(func),
    asyncReturnType = asyncReturnType,
    typeParameters = func.cirMethodTypeParameters(context, logger),
    flowElementRead = stateFlowElement?.read,
    flowElementNullable = stateFlowElement?.elementNullable == true,
    acquiredFlowNullable = acquiredFlowNullable,
    acquiredFlowCollectNativeName =
      if (stateFlowElement?.acquiredFlow == true) "${nativeName}_collect" else null,
    isAwaitedStateFlow = stateFlowElement != null && !stateFlowElement.acquiredFlow,
    sharedFlow = shared,
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
  return listOf(nativeImport, asyncMethod) + listOfNotNull(collector) + awaitedPair +
    shared?.let { flowKeyedSharedFlowImports(libraryName, cname, "${nativeName}_", it) }.orEmpty()
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
