package io.github.xxfast.kotlin.native.nuget.processor.forward

import io.github.xxfast.kotlin.native.nuget.processor.ForwardSymbolTable

/**
 * ADR-215 lane A: the generated per-library export C#'s `NugetMarshal.WrapOwned<T>` calls to turn
 * a borrowed wrapper handle (a live `INugetHandle` the consumer's lambda returned) into a box
 * Kotlin owns. ADR-163's library stem with no package segment: one per library, never a `nuget_*`
 * runtime name, since that ABI is frozen (ADR-201).
 */
internal fun ForwardSymbolTable.retainHandleExport(): String = "${librarySegment}__retain_handle"

/**
 * ADR-215 lane A: the symbol of the first plan that hands a `T` from a C# lambda back to Kotlin,
 * the one position that needs [retainHandleExport], or null when none does. It is the export's
 * ADR-117 owner. Both halves read this, so the export and its import appear and disappear
 * together.
 */
internal fun ForwardCallablePlanCatalog.objectCallbackResultOwner(): String? =
  plans.firstOrNull { plan ->
    plan.publicSignature.parameters.any { parameter ->
      val callback: BridgeType.Callback? = parameter.type as? BridgeType.Callback
      callback != null && callback.result.unwrapNullable() is BridgeType.TypeParameter
    }
  }?.invocation?.symbol
