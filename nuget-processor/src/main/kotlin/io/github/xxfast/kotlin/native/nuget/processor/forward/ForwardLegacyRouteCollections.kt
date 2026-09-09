package io.github.xxfast.kotlin.native.nuget.processor.forward

import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSValueParameter
import com.google.devtools.ksp.symbol.Modifier
import io.github.xxfast.kotlin.native.nuget.processor.cir.FLOW_TYPES
import io.github.xxfast.kotlin.native.nuget.processor.cir.STATE_FLOW_TYPES
import io.github.xxfast.kotlin.native.nuget.processor.cir.expandAliases

/**
 * ADR-114: the one place the Flow/StateFlow and suspend *legacy* routes classify a generic
 * parameter, shared by the Kotlin export builders (`exports/`) and both CIR translators (`cir/`).
 *
 * Those routes spell a parameter by pasting the declaration's own Kotlin type name through
 * `ClassName.bestGuess`, which drops the type arguments, so `fun served(kinds: List<String>):
 * StateFlow<String>` generated `kinds: List` and `packNuget` died at the Kotlin compile of the
 * whole file (issue #109). The two outcomes here are the only two that are safe:
 *
 *  - a supported collection is [Marshalled], crossing as a `COpaquePointer` handle to the same
 *    boxed wire container the ordinary synchronous route uses, and
 *  - every other generic parameter is [Refused] by name, so the member skips with a
 *    `SKIPPED_UNSUPPORTED_INPUT` instead of emitting Kotlin that does not compile.
 *
 * Spelling the parameter with its real Kotlin type instead is deliberately not an option: it
 * compiles, and hands a pinned `kref` struct across an ABI whose C# half declares `IntPtr` (see
 * ADR-114, "The trap: fixing only the spelling").
 */
internal sealed interface ForwardLegacyParameterShape {

  /** A non-generic parameter: the shipped legacy spelling, unchanged. */
  data object Plain : ForwardLegacyParameterShape

  /** A collection the ordinary route's wire container and helpers already cover. */
  data class Marshalled(val type: BridgeType.Collection) : ForwardLegacyParameterShape

  /** Any other generic parameter, named so the skip diagnostic can quote it. */
  data class Refused(val description: String) : ForwardLegacyParameterShape
}

/**
 * Classifies one legacy-route parameter. Only a *generic* type is classified at all: a plain
 * class, primitive, String or enum parameter renders exactly as it does today, so nothing existing
 * changes shape.
 *
 * Nullable collections (`List<T>?`) land in [ForwardLegacyParameterShape.Refused] on purpose:
 * threading nullability through these routes is ADR-067 territory and ADR-114 defers it. Refusing
 * is the safe half of the deferral, since the alternative is the same non-compiling Kotlin.
 */
internal fun ForwardBridgeTypeClassifier.legacyParameterShape(
  type: KSType,
): ForwardLegacyParameterShape {
  val expanded: KSType = type.expandAliases()
  if (expanded.arguments.isEmpty()) return ForwardLegacyParameterShape.Plain

  val collection: BridgeType.Collection? = classify(type) as? BridgeType.Collection
  return if (collection != null && collection.isLegacyMarshallableInput()) {
    ForwardLegacyParameterShape.Marshalled(collection)
  } else {
    ForwardLegacyParameterShape.Refused(expanded.legacyDescription())
  }
}

/** [legacyParameterShape] for every parameter of a member, in declaration order. */
internal fun ForwardBridgeTypeClassifier.legacyParameterShapes(
  parameters: List<KSValueParameter>,
): List<ForwardLegacyParameterShape> =
  parameters.map { legacyParameterShape(it.type.resolve()) }

/** The first refused parameter of a member, as `name: Type`, or null when every one binds. */
internal fun ForwardBridgeTypeClassifier.legacyRefusedParameter(
  parameters: List<KSValueParameter>,
): String? = parameters.firstNotNullOfOrNull { parameter ->
  val shape = legacyParameterShape(parameter.type.resolve())
  if (shape is ForwardLegacyParameterShape.Refused) {
    "${parameter.name?.asString() ?: "_"}: ${shape.description}"
  } else null
}

/**
 * ADR-119: the return-side twin of [ForwardLegacyParameterShape], for the suspend route only (the
 * Flow routes own their return). The suspend route spelled a return by its simple name on both
 * halves, so `suspend fun fetch(): List<Member>` pinned the raw `List` in a `StableRef` and
 * rendered `Task<List>`, which is CS0305 in the consumer's build (issue #122). The same two
 * outcomes apply: a supported collection is [Marshalled] as a handle to the same boxed wire
 * container the ordinary route's `List` return uses, and every other generic return is [Refused]
 * by name, so the member skips with a `SKIPPED_UNSUPPORTED_RETURN` instead of emitting C# that
 * cannot compile.
 */
internal sealed interface ForwardLegacyReturnShape {

  /** A non-generic return, or a `StateFlow` (ADR-068 owns that one): the shipped spelling. */
  data object Plain : ForwardLegacyReturnShape

  /** A collection the ordinary route's `List`/`Set`/`Map` return already reads back. */
  data class Marshalled(val type: BridgeType.Collection) : ForwardLegacyReturnShape

  /** Any other generic return, named so the skip diagnostic can quote it. */
  data class Refused(val description: String) : ForwardLegacyReturnShape
}

/**
 * Classifies one suspend member's return. Only a *generic* return is classified at all, so every
 * scalar, string, enum and object return renders exactly as it does today.
 *
 * The admission is the ordinary route's own return-position rule (`isBridgeableComponent`), so
 * the two routes agree on which element types cross. A nullable collection (`List<T>?`) is
 * [ForwardLegacyReturnShape.Refused], mirroring [legacyParameterShape]'s ADR-114 deferral.
 */
internal fun ForwardBridgeTypeClassifier.legacyReturnShape(
  type: KSType?,
): ForwardLegacyReturnShape {
  val expanded: KSType = type?.expandAliases() ?: return ForwardLegacyReturnShape.Plain
  if (expanded.arguments.isEmpty()) return ForwardLegacyReturnShape.Plain
  // ADR-068 peels a StateFlow return into its own bucket before the plain-async path sees it.
  if (expanded.declaration.qualifiedName?.asString() in STATE_FLOW_TYPES) {
    return ForwardLegacyReturnShape.Plain
  }

  val collection: BridgeType.Collection? = classify(type) as? BridgeType.Collection
  return if (collection != null && collection.isBridgeableComponent()) {
    ForwardLegacyReturnShape.Marshalled(collection)
  } else {
    ForwardLegacyReturnShape.Refused(expanded.legacyDescription())
  }
}

/** A suspend member's refused return as the author spelled it, or null when it binds. */
internal fun ForwardBridgeTypeClassifier.legacyRefusedReturn(func: KSFunctionDeclaration): String? {
  if (!func.modifiers.contains(Modifier.SUSPEND)) return null
  val shape: ForwardLegacyReturnShape = legacyReturnShape(func.returnType?.resolve())
  return if (shape is ForwardLegacyReturnShape.Refused) shape.description else null
}

/**
 * Every collection kind a member's parameters need native helper exports for, nested components
 * included. `Set<List<String>>` calls `nuget_list_create` one level down, so reading only the
 * outer kind would leave those exports unemitted and the failure would be an
 * `EntryPointNotFoundException` at first call rather than a build error (ADR-114 answer 4).
 */
internal fun ForwardBridgeTypeClassifier.legacyCollectionKinds(
  parameters: List<KSValueParameter>,
): Sequence<CollectionKind> = sequence {
  parameters.forEach { parameter ->
    val shape = legacyParameterShape(parameter.type.resolve())
    if (shape is ForwardLegacyParameterShape.Marshalled) yieldAll(shape.type.nestedKinds())
  }
}

/** ADR-119: [legacyCollectionKinds] for a suspend member's return, `nuget_list_get` and kin. */
internal fun ForwardBridgeTypeClassifier.legacyReturnCollectionKinds(
  func: KSFunctionDeclaration,
): Sequence<CollectionKind> {
  if (!func.modifiers.contains(Modifier.SUSPEND)) return emptySequence()
  val shape: ForwardLegacyReturnShape = legacyReturnShape(func.returnType?.resolve())
  return if (shape is ForwardLegacyReturnShape.Marshalled) shape.type.nestedKinds()
  else emptySequence()
}

/**
 * ADR-119: the C# expression reading a suspend member's awaited collection handle back into its
 * public type. `NugetMarshal.ReadList`/`ReadSet`/`ReadMap` are the same `nuget_list_*` /
 * `nuget_set_*` / `nuget_map_*` helpers the property route reads through, behind a `finally` that
 * disposes the handle, so the two routes agree at runtime as well as in the signature.
 */
internal fun legacyCollectionRead(handle: String, type: BridgeType.Collection): String =
  componentCollectionRead(handle, type, csharpType = { it.forwardPublicCsharpType() })

private fun BridgeType.Collection.nestedKinds(): Sequence<CollectionKind> = sequence {
  yield(kind)
  listOfNotNull(element, key, value).forEach { component ->
    val unwrapped: BridgeType =
      if (component is BridgeType.Nullable) component.type else component
    if (unwrapped is BridgeType.Collection) yieldAll(unwrapped.nestedKinds())
  }
}

/**
 * The same admission the ordinary synchronous route applies to a collection *input*: every
 * component has to be one the C# write side can box (`isWrappableComponent`), with ADR-083's
 * non-null map key rule. Deliberately re-stated rather than reaching into the planner, which
 * builds a whole `ForwardCallablePlan` these routes never carry.
 */
private fun BridgeType.Collection.isLegacyMarshallableInput(): Boolean = when {
  !isBridgeableComponent() -> false

  kind == CollectionKind.MAP || kind == CollectionKind.MUTABLE_MAP ->
    key?.let { it !is BridgeType.Nullable && it.isWrappableComponent() } == true &&
        value?.isWrappableComponent() == true

  else -> element?.isWrappableComponent() == true
}

/** `Pair<String, Int>`: the author's own spelling, so a skip diagnostic names what to change. */
private fun KSType.legacyDescription(): String {
  val base: String = declaration.simpleName.asString()
  if (arguments.isEmpty()) return base
  val rendered: String = arguments.joinToString(", ") { argument ->
    argument.type?.resolve()?.declaration?.simpleName?.asString() ?: "*"
  }
  return "$base<$rendered>${if (isMarkedNullable) "?" else ""}"
}

/**
 * The Kotlin local a legacy export binds a lowered collection argument to. Named apart from the
 * parameter so the export still names its ABI slot after the declaration.
 */
internal fun legacyLoweredName(parameter: String): String = "${parameter}Arg"

/**
 * ADR-114 alternative 1: the eager Kotlin copy. Emitted *before* `scope.launch`, so the wire
 * container has been copied out by the time the export returns and the C# side's `finally`-dispose
 * can never be a use-after-free. Reuses the ordinary route's lowering verbatim.
 */
internal fun legacyLoweringStatement(parameter: String, type: BridgeType.Collection): String =
  "val ${legacyLoweredName(parameter)} = ${loweredCollectionExpression(parameter, type)}"

/** The C# expression that builds [name]'s native wire handle, with per-element projection. */
internal fun legacyCollectionCreate(name: String, type: BridgeType.Collection): String {
  val factory: String = when (type.kind) {
    CollectionKind.LIST, CollectionKind.MUTABLE_LIST -> "CreateList"
    CollectionKind.MAP, CollectionKind.MUTABLE_MAP -> "CreateMap"
    CollectionKind.SET, CollectionKind.MUTABLE_SET -> "CreateSet"
  }
  val source: String = collectionCreateArgument(name, type) { it.forwardPublicCsharpType() }
  return "NugetMarshal.$factory($source)"
}

/**
 * Whether a member takes one of the two legacy async routes ADR-114 covers: a `suspend` member
 * (the `_async` export) or a `Flow`/`StateFlow`-returning one (`_collect`, plus the synchronous
 * `_value` / `_has_value` / `_set_value` siblings). Neither carries a `ForwardCallablePlan`.
 */
internal fun KSFunctionDeclaration.isForwardLegacyAsyncRoute(): Boolean {
  if (modifiers.contains(Modifier.SUSPEND)) return true
  val returnQualified: String? = returnType?.resolve()
    ?.expandAliases()?.declaration?.qualifiedName?.asString()
  return returnQualified in FLOW_TYPES || returnQualified in STATE_FLOW_TYPES
}
