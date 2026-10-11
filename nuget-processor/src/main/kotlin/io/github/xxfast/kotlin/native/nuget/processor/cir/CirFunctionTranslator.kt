package io.github.xxfast.kotlin.native.nuget.processor.cir

import io.github.xxfast.kotlin.native.nuget.processor.asCSymbol
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import io.github.xxfast.kotlin.native.nuget.processor.ForwardSymbolTable
import io.github.xxfast.kotlin.native.nuget.processor.abiSlotParameterName
import io.github.xxfast.kotlin.native.nuget.processor.csharpParameterName
import io.github.xxfast.kotlin.native.nuget.processor.forward.csharpAsyncMemberName
import io.github.xxfast.kotlin.native.nuget.processor.forward.csharpMemberName
import io.github.xxfast.kotlin.native.nuget.processor.freshName
import io.github.xxfast.kotlin.native.nuget.processor.exports.legacyGenericHasNonTrivialBound
import io.github.xxfast.kotlin.native.nuget.processor.exports.legacyGenericRouteParameterIndex
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

  val asyncMethod = CirMethod(
    name = func.csharpAsyncMemberName(),
    returnType = taskReturnType,
    nativeName = nativeName,
    parameters = params,
    body = "",
    isStatic = true,
    isAsync = true,
    asyncReturnType = asyncReturnType,
    flowElementRead = stateFlowElement?.read,
    flowElementNullable = stateFlowElement?.elementNullable == true,
    acquiredFlowNullable = acquiredFlowNullable,
    acquiredFlowCollectNativeName =
      if (asyncReturnType.startsWith("KotlinFlow<")) "${nativeName}_collect" else null,
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
  return listOf(nativeImport, asyncMethod) + listOfNotNull(collector) + awaitedPair
}

internal fun translateGenericFunction(
  func: KSFunctionDeclaration,
  libraryName: String,
  // ADR-133: as on the generic class route -- a bound on a nested interface needs the namespace.
  context: NugetContext,
  logger: KSPLogger,
): List<CirMember> {
  val funcName: String = func.simpleName.asString()
  // ADR-110: PascalCase, unescaped (the renderer escapes); every DllImport on this route pins its
  // own explicit entry point.
  val csName: String = func.csharpMemberName()
  val returnType = func.returnType?.resolve()?.expandAliases()
  val returnDecl: KSClassDeclaration? = returnType?.declaration as? KSClassDeclaration
  val returnTypeName: String = returnType?.declaration?.simpleName?.asString() ?: "Unit"

  val typeParamName: String = func.typeParameters.firstOrNull()?.name?.asString() ?: "T"

  val typeParamBounds: List<String> = func.typeParameters.firstOrNull()
    ?.bounds?.toList()?.mapNotNull { bound ->
      // The generic-class route's speller, so the two cannot disagree on a bound.
      cirBoundConstraint(bound.resolve(), context, logger, func, "$funcName<$typeParamName>")
    }
    // `notnull` must come first in a C# constraint list and adds nothing next to a class bound.
    // ADR-198: `struct` must come first too (CS0449).
    ?.let { bounds -> if (bounds.size > 1) bounds - NOTNULL_CONSTRAINT else bounds }
    ?.sortedByDescending { constraint -> constraint == ENUM_CONSTRAINT }
    ?: emptyList()

  // ADR-064 amendment (2026-09-13): the shared gate, so this half, the Kotlin half and the
  // diagnostic that now names the refusal cannot disagree about which generic functions bind.
  val paramIndex: Int = func.legacyGenericRouteParameterIndex()

  if (paramIndex == -1) return emptyList()

  val param = func.parameters[paramIndex]
  // Printed into the CirParameter of every instantiation import *and* into the hand-built body
  // below, which declares its own `IntPtr error` local; escaping once here keeps the two in step.
  // The ABI slot shift (`errorOut` -> `errorOut_`) runs first, as on the Kotlin half
  // (`GenericFunctionExports`), so the ADR-055 contract check sees one name on both.
  val paramName: String =
    (param.name?.asString() ?: "value").abiSlotParameterName().csharpParameterName()

  // Every local the hand-built body declares is minted off the user's parameter (collision-only,
  // `freshName`), so the public label never moves and non-colliding output is unchanged.
  val taken: MutableSet<String> = mutableSetOf(paramName.removePrefix("@"))
  fun local(base: String): String = freshName(base, taken).also { name -> taken += name }
  val errorLocal: String = local("error")
  val widthLocal: String = local("width")
  val presentLocal: String = local("present")
  val handleLocal: String = local("handle")
  val ownedLocal: String = local("owned")
  val resultLocal: String = local("result")

  val returnsGenericClass: Boolean = returnDecl?.typeParameters?.isNotEmpty() == true

  val result = mutableListOf<CirMember>()

  // `notnull` narrows nothing the primitive widths care about (`int`, `string` both satisfy it),
  // so the width dispatch survives `T : Any`; any other bound replaces it. The Kotlin half's own
  // gate, so a builtin bound dropped to `notnull` here does not import widths never exported.
  val isConstrained: Boolean = func.legacyGenericHasNonTrivialBound()

  val primitiveTypes = listOf(
    "string" to "string",
    "int" to "int",
    "long" to "long",
    "float" to "float",
    "double" to "double",
    "bool" to "bool",
  )

  if (!isConstrained) primitiveTypes.forEach { (suffix, csType) ->
    val entryPoint = "${context.symbols.topLevel(func)}_$suffix"
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
        hasSyncErrorOut = true,
      )
    )
  }

  val objectEntryPoint = "${context.symbols.topLevel(func)}_object"
  val objectNativeName = "${csName}_object_native"

  result.add(
    CirDllImport(
      libraryName = libraryName,
      entryPoint = objectEntryPoint,
      returnType = "IntPtr",
      name = objectNativeName,
      parameters = listOf(CirParameter(paramName, "IntPtr")),
      visibility = CirVisibility.PRIVATE,
      hasSyncErrorOut = true,
    )
  )

  val body: String = buildString {
    appendLine()
    appendLine("      IntPtr $errorLocal;")

    if (!isConstrained) {
      // ADR-147 amendment, applied to this route: `T = int?` is `Nullable<int>`, which never
      // equals `typeof(int)`, so dispatch on the underlying type. A null argument has no width to
      // cross on (the string width's Kotlin parameter is a non-null `String`) and takes the object
      // variant's null pointer instead.
      appendLine("      Type $widthLocal = Nullable.GetUnderlyingType(typeof($typeParamName)) ?? typeof($typeParamName);")
      appendLine("      bool $presentLocal = $paramName is not null;")
      appendLine("      if ($presentLocal && $widthLocal == typeof(string))")
      if (returnsGenericClass) {
        appendLine("        return new ${returnTypeName}<$typeParamName>(NugetErrorNative.Check(${csName}_string_native((string)(object)$paramName!, out $errorLocal), $errorLocal), out _);")
      } else {
        appendLine("        return ($typeParamName)(object)Marshal.PtrToStringUTF8(NugetErrorNative.Check(${csName}_string_native((string)(object)$paramName!, out $errorLocal), $errorLocal))!;")
      }

      appendLine("      if ($presentLocal && $widthLocal == typeof(int))")
      if (returnsGenericClass) {
        appendLine("        return new ${returnTypeName}<$typeParamName>(NugetErrorNative.Check(${csName}_int_native((int)(object)$paramName!, out $errorLocal), $errorLocal), out _);")
      } else {
        appendLine("        return ($typeParamName)(object)NugetErrorNative.Check(${csName}_int_native((int)(object)$paramName!, out $errorLocal), $errorLocal);")
      }

      appendLine("      if ($presentLocal && $widthLocal == typeof(long))")
      if (returnsGenericClass) {
        appendLine("        return new ${returnTypeName}<$typeParamName>(NugetErrorNative.Check(${csName}_long_native((long)(object)$paramName!, out $errorLocal), $errorLocal), out _);")
      } else {
        appendLine("        return ($typeParamName)(object)NugetErrorNative.Check(${csName}_long_native((long)(object)$paramName!, out $errorLocal), $errorLocal);")
      }

      appendLine("      if ($presentLocal && $widthLocal == typeof(float))")
      if (returnsGenericClass) {
        appendLine("        return new ${returnTypeName}<$typeParamName>(NugetErrorNative.Check(${csName}_float_native((float)(object)$paramName!, out $errorLocal), $errorLocal), out _);")
      } else {
        appendLine("        return ($typeParamName)(object)NugetErrorNative.Check(${csName}_float_native((float)(object)$paramName!, out $errorLocal), $errorLocal);")
      }

      appendLine("      if ($presentLocal && $widthLocal == typeof(double))")
      if (returnsGenericClass) {
        appendLine("        return new ${returnTypeName}<$typeParamName>(NugetErrorNative.Check(${csName}_double_native((double)(object)$paramName!, out $errorLocal), $errorLocal), out _);")
      } else {
        appendLine("        return ($typeParamName)(object)NugetErrorNative.Check(${csName}_double_native((double)(object)$paramName!, out $errorLocal), $errorLocal);")
      }

      appendLine("      if ($presentLocal && $widthLocal == typeof(bool))")
      if (returnsGenericClass) {
        appendLine("        return new ${returnTypeName}<$typeParamName>(NugetErrorNative.Check(${csName}_bool_native((bool)(object)$paramName!, out $errorLocal), $errorLocal), out _);")
      } else {
        appendLine("        return ($typeParamName)(object)NugetErrorNative.Check(${csName}_bool_native((bool)(object)$paramName!, out $errorLocal), $errorLocal);")
      }
    }

    // ADR-094: the object argument answers INugetHandle (a miss is an InvalidCastException where it
    // used to be a NullReferenceException), and a wrapper result comes back out of the generated
    // factory registry rather than Activator.CreateInstance (a builtin box is unwrapped first, see
    // the return below).
    // ADR-147 amendment: a null argument crosses as the null pointer (ADR-083), and a null result
    // is the `default` of whatever `T` was instantiated to, never a factory lookup.
    // ADR-173: the object argument goes through the ONE erased write function, `Wrap<T>`, the
    // lambda and generic-class routes already use: null is the null pointer, a value class boxes
    // (ADR-171 `Boxers`), a Kotlin-backed wrapper yields its own handle, and a C#-implemented
    // interface mints a bridge transfer handle, disposed on `owned` once the native call returns.
    // `!`: `Wrap<T>` takes a non-null `T` under a `T?` parameter's flow analysis (CS8604), and
    // answers a null with the null pointer itself (ADR-083).
    appendLine(
      "      IntPtr $handleLocal = NugetMarshal.Wrap<$typeParamName>($paramName!, out bool $ownedLocal);",
    )
    appendLine("      IntPtr $resultLocal;")
    appendLine("      try")
    appendLine("      {")
    appendLine(
      "        $resultLocal = NugetErrorNative.Check(${csName}_object_native($handleLocal, out $errorLocal), $errorLocal);",
    )
    appendLine("      }")
    appendLine("      finally")
    appendLine("      {")
    // ADR-187: a borrowed handle is a live wrapper's raw `Handle`; keep the wrapper alive past the call.
    appendLine("        if ($ownedLocal) NugetMarshal.Dispose($handleLocal);")
    appendLine("        else GC.KeepAlive($paramName);")
    appendLine("      }")
    if (returnsGenericClass) {
      appendLine("      return new ${returnTypeName}<$typeParamName>($resultLocal, out _);")
    } else {
      // ADR-015 amendment: the generic class route's reader. `Wrap<T>` above boxes a builtin `T`
      // (`Weigh<int>`, and every builtin an unconstrained function has no width variant for, such
      // as `Identity<short>`), and the object variant hands that box back. `FromHandle<T>` unwraps
      // and disposes it before falling to the factory registry; `Materialize<T>` knew only the
      // registry and threw NotSupportedException. The null pointer is `default`, as before.
      appendLine("      return NugetMarshal.FromHandle<$typeParamName>($resultLocal);")
    }
  }

  val methodReturnType: String =
    if (returnsGenericClass) "$returnTypeName<$typeParamName>" else typeParamName

  result.add(
    CirMethod(
      name = csName,
      returnType = methodReturnType,
      parameters = listOf(CirParameter(paramName, typeParamName)),
      body = body.trimEnd(),
      isStatic = true,
      typeParameters = listOf(
        CirTypeParameter(typeParamName, typeParamBounds),
      ),
    )
  )

  return result
}
