package io.github.xxfast.kotlin.native.nuget.processor.forward

import io.github.xxfast.kotlin.native.nuget.processor.ForwardSymbolTable

/**
 * ADR-215 "lambda parameter on the plan": the generated per-library export C#'s `NugetMarshal.WrapOwned<T>` calls to turn
 * a borrowed wrapper handle (a live `INugetHandle` the consumer's lambda returned) into a box
 * Kotlin owns. ADR-163's library stem with no package segment: one per library, never a `nuget_*`
 * runtime name, since that ABI is frozen (ADR-201).
 */
internal fun ForwardSymbolTable.retainHandleExport(): String = "${librarySegment}__retain_handle"

/**
 * ADR-215: whether this parameter type hands a `T` from C# INTO Kotlin as an owned box, the one
 * position that needs [retainHandleExport]: a lambda's `T` result (ADR-215, "lambda parameter on the plan"), a `suspend` lambda's
 * `T` result (`EndBoxed()`) and a `Flow<T>` element (`CurrentBoxed()`, both ADR-215, "`T` flowing into Kotlin asynchronously"), and
 * (ADR-231) a lambda's exported class result.
 */
internal fun BridgeType.boxesIntoKotlin(): Boolean = when (this) {
  // ADR-239: every boxed callback result, not only a `T` (ADR-215) or a class (ADR-231).
  is BridgeType.Callback -> result.isBoxedCallbackResult()
  is BridgeType.AsyncCallback -> result.isBoxedCallbackResult()
  is BridgeType.FlowSource -> element.unwrapNullable() is BridgeType.TypeParameter
  BridgeType.Unit,
  is BridgeType.Primitive,
  BridgeType.Char,
  BridgeType.String,
  BridgeType.Instant,
  BridgeType.Duration,
  is BridgeType.Throwable,
  BridgeType.Uuid,
  is BridgeType.Enum,
  is BridgeType.ObjectHandle,
  is BridgeType.Interface,
  is BridgeType.BoundInterface,
  is BridgeType.ValueClass,
  BridgeType.ByteArray,
  is BridgeType.Collection,
  is BridgeType.Nullable,
  is BridgeType.ReturnedLambda,
  is BridgeType.SpecializedProtocol,
  is BridgeType.RawKSType,
  is BridgeType.Unsupported,
  is BridgeType.RawCollection,
  is BridgeType.TypeParameter,
    -> false
}

/**
 * ADR-215 "lambda parameter on the plan": the symbol of the first plan that hands a `T` from C# back to Kotlin
 * ([boxesIntoKotlin]), or null when none does. It is the export's ADR-117 owner. Both halves
 * read this, so the export and its import appear and disappear together; ADR-215's legacy suspend
 * route adds its own sighting (`ForwardBridgeTypeClassifier.boxedAsyncHost`) on both halves.
 */
internal fun ForwardCallablePlanCatalog.objectCallbackResultOwner(): String? =
  plans.firstOrNull { plan ->
    plan.publicSignature.parameters.any { parameter -> parameter.type.boxesIntoKotlin() }
  }?.invocation?.symbol ?: genericBridgeOwner()

/**
 * ADR-222: a generic interface bridge's `T` slot result is a box C# hands Kotlin through
 * `WrapOwned<T>`, so the first plan taking a generic interface owns the retain export too.
 */
private fun ForwardCallablePlanCatalog.genericBridgeOwner(): String? = plans.firstOrNull { plan ->
  plan.nativeExports.flatMap { call -> call.parameters }.any { parameter ->
    (parameter.transfer.type.unwrapNullable() as? BridgeType.Interface)?.bridgeEntry != null
  }
}?.invocation?.symbol

/**
 * ADR-239: the generated per-library export that turns the GCHandle C# mints over a bound C#
 * interface (ADR-088) into a Kotlin handle over the reverse pipeline's value, so the interface
 * crosses an erased position (`NugetMarshal.Wrap<T>`) like any boxed type.
 */
internal fun ForwardSymbolTable.boundBoxExport(type: BridgeType.BoundInterface): String =
  "${librarySegment}__bound_${type.qualifiedName.replace('.', '_')}_box"

/** ADR-239: the reverse of [boundBoxExport], a fresh GCHandle over a Kotlin handle's value. */
internal fun ForwardSymbolTable.boundUnboxExport(type: BridgeType.BoundInterface): String =
  "${librarySegment}__bound_${type.qualifiedName.replace('.', '_')}_unbox"

/** ADR-239: the export that turns a Kotlin handle over a `Throwable` into its ADR-107 envelope. */
internal fun ForwardSymbolTable.throwableEnvelopeExport(): String = "${librarySegment}__throwable_envelope"

/** ADR-239: the export that builds a `NugetManagedException` from C#'s managed-exception text. */
internal fun ForwardSymbolTable.throwableFromTextExport(): String = "${librarySegment}__throwable_from_text"
