package io.github.xxfast.kotlin.native.nuget.processor.forward

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSValueParameter
import com.google.devtools.ksp.symbol.Modifier
import io.github.xxfast.kotlin.native.nuget.processor.cir.FLOW_TYPES
import io.github.xxfast.kotlin.native.nuget.processor.cir.STATE_FLOW_TYPES
import io.github.xxfast.kotlin.native.nuget.processor.cir.expandAliases

/**
 * ADR-114 / ADR-122: the one place the Flow/StateFlow and suspend *legacy* routes classify a
 * parameter, shared by the Kotlin export builders (`exports/`) and both CIR translators (`cir/`).
 * ADR-123 adds the third position, [ForwardLegacyFlowElementShape], for a flow *element*; all
 * three read the same way, so a change to what these routes admit lands in one file.
 *
 * Those routes spell a parameter by pasting the declaration's own Kotlin type name through
 * `ClassName.bestGuess`, which drops the type arguments, so `fun served(kinds: List<String>):
 * StateFlow<String>` generated `kinds: List` and `packNuget` died at the Kotlin compile of the
 * whole file (issue #109). ADR-122 then found the same fall-through at the non-generic position,
 * where it renders a public `IntPtr` nobody can call (issue #126). Four outcomes, and only these
 * four are safe:
 *
 *  - a scalar is [Plain], the shipped spelling;
 *  - a supported collection is [Marshalled], crossing as a `COpaquePointer` handle to the same
 *    boxed wire container the ordinary synchronous route uses;
 *  - a class, `object` or sealed type is a [Handle], the borrowed `_handle` the ordinary plan
 *    route already passes; and
 *  - everything else is [Refused] by name, so the member skips with a
 *    `SKIPPED_UNSUPPORTED_INPUT` instead of emitting Kotlin that does not compile, or C# that
 *    compiles and cannot be called.
 *
 * Spelling the parameter with its real Kotlin type instead is deliberately not an option: it
 * compiles, and hands a pinned `kref` struct across an ABI whose C# half declares `IntPtr` (see
 * ADR-114, "The trap: fixing only the spelling").
 */
internal sealed interface ForwardLegacyParameterShape {

  /**
   * A scalar parameter: the shipped legacy spelling, unchanged.
   *
   * ADR-122 narrowed this from "no type arguments" to "one of the 13 entries `mapParamType` can
   * actually spell". Everything else it used to cover rendered a public `IntPtr` (issue #126).
   */
  data object Plain : ForwardLegacyParameterShape

  /** A collection the ordinary route's wire container and helpers already cover. */
  data class Marshalled(val type: BridgeType.Collection) : ForwardLegacyParameterShape

  /**
   * ADR-122: a class, `object`, sealed base or sealed arm, crossing as the borrowed handle the
   * ordinary plan route already passes (`x._handle` in C#, `asStableRef<T>().get()` in Kotlin).
   */
  data class Handle(val type: BridgeType.ObjectHandle) : ForwardLegacyParameterShape

  /** Any other parameter, named so the skip diagnostic can quote it. */
  data class Refused(val description: String) : ForwardLegacyParameterShape
}

/**
 * Classifies one legacy-route parameter.
 *
 * ADR-114 classified only *generic* types here and returned [ForwardLegacyParameterShape.Plain]
 * for everything else unread, which meant `mapParamType(simpleName)` handed C# an `IntPtr` for a
 * class, an `object`, a sealed type, an enum, an `Instant`/`Duration`/`Uuid`, a value class and an
 * interface alike (issue #126). ADR-122 classifies the non-generic case too, so the three outcomes
 * are marshal it, borrow its handle, or refuse it by name. None of them is a public `IntPtr`.
 *
 * Nullable collections (`List<T>?`) and nullable objects land in
 * [ForwardLegacyParameterShape.Refused] on purpose: threading nullability through these routes is
 * ADR-067 territory and ADR-114 defers it. A nullable *scalar* stays [ForwardLegacyParameterShape
 * .Plain], keeping the non-null spelling both halves already ship for it, rather than dropping a
 * member that binds today.
 */
internal fun ForwardBridgeTypeClassifier.legacyParameterShape(
  type: KSType,
): ForwardLegacyParameterShape {
  val expanded: KSType = type.expandAliases()
  // ADR-105's rewrite, applied here for the same reason the planner applies it at a parameter
  // position: a sealed base crosses as the handle its arms do, and Kotlin discriminates. The
  // `viaDiscriminator` flag only matters for C# reconstruction, which a parameter never does.
  val classified: BridgeType = classify(type).sealedAsHandle()

  if (expanded.arguments.isEmpty()) return when {
    classified.isLegacyScalar() -> ForwardLegacyParameterShape.Plain
    classified is BridgeType.ObjectHandle -> ForwardLegacyParameterShape.Handle(classified)
    classified is BridgeType.Nullable && classified.type.isLegacyScalar() ->
      ForwardLegacyParameterShape.Plain

    else -> ForwardLegacyParameterShape.Refused(expanded.legacyDescription())
  }

  // Deliberately the un-rewritten classification: a `List<Shape>` of a sealed base stays refused,
  // as ADR-114/ADR-119 decided, rather than being widened by the rewrite above.
  val collection: BridgeType.Collection? = classify(type) as? BridgeType.Collection
  return if (collection != null && collection.isLegacyMarshallableInput()) {
    ForwardLegacyParameterShape.Marshalled(collection)
  } else {
    ForwardLegacyParameterShape.Refused(expanded.legacyDescription())
  }
}

/**
 * The 13 entries `mapParamType` can spell (`cir/CirTypeMapping.kt`). This is the whole of what
 * [ForwardLegacyParameterShape.Plain] may cover: anything outside it rendered `IntPtr`, which is
 * issue #126.
 */
private fun BridgeType.isLegacyScalar(): Boolean =
  this is BridgeType.Primitive || this is BridgeType.Char || this is BridgeType.String

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

  /**
   * ADR-131: an ADR-009 sealed **base** (a sealed class, or an ADR-112 eligible sealed interface)
   * at a suspend return. The wire is unchanged -- the Kotlin half already pins a `StableRef` on
   * the *concrete arm* the body produced -- but the C# completion has to read it back through the
   * generated `internal static Base FromHandle(IntPtr)` discriminator, because ADR-009 renders the
   * base as `public abstract class` and `new Base(resultPtr)` is CS0144.
   *
   * [nullable] carries the `?` from the declaration, so the completion can guard a null result
   * pointer before discriminating, the same guard the shipped nullable-object arm applies.
   */
  data class Discriminated(
    val handle: BridgeType.ObjectHandle,
    val nullable: Boolean,
  ) : ForwardLegacyReturnShape

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

  // ADR-131: the sealed case is decided *before* the non-generic early return below, because a
  // sealed base carries no type arguments and so was `Plain` -- i.e. `new Job(resultPtr)` against
  // an abstract class. The gate is the declaration's `sealed` modifier, not the classification:
  // an ordinary class return is an `ObjectHandle` too, and must keep its `new T(resultPtr)`.
  if ((expanded.declaration as? KSClassDeclaration)?.modifiers?.contains(Modifier.SEALED) == true) {
    // ADR-105's rewrite, the same one `legacyParameterShape` applies: an *eligible* sealed type
    // carries the `ObjectHandle(viaDiscriminator = true)` its arms cross as. Anything else (an
    // ineligible sealed interface, an out-of-scope sealed class) has a null `sealedHandle`, and is
    // refused by name rather than rendered as C# that cannot compile.
    val unwrapped: BridgeType = classify(type).sealedAsHandle().let {
      if (it is BridgeType.Nullable) it.type else it
    }
    return if (unwrapped is BridgeType.ObjectHandle && unwrapped.viaDiscriminator) {
      ForwardLegacyReturnShape.Discriminated(unwrapped, expanded.isMarkedNullable)
    } else {
      ForwardLegacyReturnShape.Refused(expanded.legacyDescription())
    }
  }

  if (expanded.arguments.isEmpty()) return ForwardLegacyReturnShape.Plain

  // ADR-068 peels a StateFlow return into its own bucket before the plain-async path sees it.
  // ADR-123: that bucket reads every element through the module-wide `nuget_stateflow_value`
  // export, which has no per-member projection seam, so a collection element cannot cross there
  // even though the ADR-065 property and method routes now bind one. Refused, not half-bound.
  if (expanded.declaration.qualifiedName?.asString() in STATE_FLOW_TYPES) {
    val element: KSType? = expanded.arguments.firstOrNull()?.type?.resolve()
    return if (legacyFlowElementShape(element) is ForwardLegacyFlowElementShape.Plain) {
      ForwardLegacyReturnShape.Plain
    } else {
      ForwardLegacyReturnShape.Refused(expanded.legacyDescription())
    }
  }

  val collection: BridgeType.Collection? = classify(type) as? BridgeType.Collection
  return if (collection != null && collection.isBridgeableComponent()) {
    ForwardLegacyReturnShape.Marshalled(collection)
  } else {
    ForwardLegacyReturnShape.Refused(expanded.legacyDescription())
  }
}

/**
 * A legacy-route member's refused return as the author spelled it, or null when it binds.
 *
 * ADR-123 widens this from `suspend`-only to every legacy async route, so a `Flow`-returning
 * method whose element cannot cross is filtered by the same single call both halves already make
 * for a suspend member.
 */
internal fun ForwardBridgeTypeClassifier.legacyRefusedReturn(func: KSFunctionDeclaration): String? {
  val returnType: KSType? = func.returnType?.resolve()
  if (!func.modifiers.contains(Modifier.SUSPEND)) return legacyRefusedFlowElement(returnType)
  val shape: ForwardLegacyReturnShape = legacyReturnShape(returnType)
  return if (shape is ForwardLegacyReturnShape.Refused) shape.description else null
}

/**
 * ADR-123: the element-side twin of [ForwardLegacyReturnShape], for the `Flow`/`StateFlow` element
 * position on a property and a method return.
 *
 * Both halves spelled that element with `qualifiedElementCsType`, which runs a Kotlin builtin
 * through the *user-type* namespace mapping and never reads its type arguments, so
 * `StateFlow<Set<NodeId>>` rendered `KotlinStateFlow<global::Demo.Kotlin.Collections.Set>`: a
 * namespace nothing declares (issue #127). The runtime half was broken independently, since
 * `NugetMarshal.FromHandle<T>` has no collection branch and the Kotlin emission boxed the value
 * unprojected. Three outcomes, the same three ADR-114 and ADR-119 use one position over.
 */
internal sealed interface ForwardLegacyFlowElementShape {

  /** An element with no type arguments: the shipped spelling, byte for byte. */
  data object Plain : ForwardLegacyFlowElementShape

  /** A collection the ordinary route's wire container and helpers already cover. */
  data class Marshalled(val type: BridgeType.Collection) : ForwardLegacyFlowElementShape

  /** Any other generic element, named so the skip diagnostic can quote it. */
  data class Refused(val description: String) : ForwardLegacyFlowElementShape
}

/**
 * Classifies one `Flow`/`StateFlow` element. Only a *generic* element is classified at all, so
 * every scalar, string, enum, object and sealed element renders exactly as it does today,
 * including ADR-067's nullable scalar element (`StateFlow<String?>`, no type arguments of its own).
 *
 * The admission is the ordinary route's own return-position rule (`isBridgeableComponent`), so an
 * element type crosses here exactly when it crosses on the property route. A nullable collection
 * element is [ForwardLegacyFlowElementShape.Refused], keeping ADR-114's and ADR-119's rule.
 */
internal fun ForwardBridgeTypeClassifier.legacyFlowElementShape(
  type: KSType?,
): ForwardLegacyFlowElementShape {
  val expanded: KSType = type?.expandAliases() ?: return ForwardLegacyFlowElementShape.Plain
  if (expanded.arguments.isEmpty()) return ForwardLegacyFlowElementShape.Plain

  val collection: BridgeType.Collection? = classify(type) as? BridgeType.Collection
  return if (collection != null && collection.isBridgeableComponent()) {
    ForwardLegacyFlowElementShape.Marshalled(collection)
  } else {
    ForwardLegacyFlowElementShape.Refused(expanded.legacyDescription())
  }
}

/**
 * ADR-123: the element of a `Flow`/`StateFlow` type, or null when [type] is neither. The one place
 * the element is peeled off, so the two halves cannot disagree about which argument it is.
 */
internal fun legacyFlowElement(type: KSType?): KSType? {
  val expanded: KSType = type?.expandAliases() ?: return null
  val qualified: String? = expanded.declaration.qualifiedName?.asString()
  if (qualified !in FLOW_TYPES && qualified !in STATE_FLOW_TYPES) return null
  return expanded.arguments.firstOrNull()?.type?.resolve()
}

/** The refused element of a `Flow`/`StateFlow` member, or null when it binds (or is not one). */
internal fun ForwardBridgeTypeClassifier.legacyRefusedFlowElement(type: KSType?): String? {
  val element: KSType = legacyFlowElement(type) ?: return null
  val shape: ForwardLegacyFlowElementShape = legacyFlowElementShape(element)
  return if (shape is ForwardLegacyFlowElementShape.Refused) shape.description else null
}

/** The marshalled collection a `Flow`/`StateFlow` member's element is, or null for every other. */
internal fun ForwardBridgeTypeClassifier.legacyFlowElementCollection(
  type: KSType?,
): BridgeType.Collection? {
  val element: KSType = legacyFlowElement(type) ?: return null
  return (legacyFlowElementShape(element) as? ForwardLegacyFlowElementShape.Marshalled)?.type
}

/**
 * ADR-123: [legacyCollectionKinds] for a `Flow`/`StateFlow` element. No declaration scan finds
 * these on its own: the `needs*Support` walks read a property's *type*, and that type is
 * `StateFlow`, not `Set`, so without this the generated C# calls `nuget_set_count` against a
 * native library that never exported it.
 */
internal fun ForwardBridgeTypeClassifier.legacyFlowElementCollectionKinds(
  type: KSType?,
): Sequence<CollectionKind> =
  legacyFlowElementCollection(type)?.nestedKinds() ?: emptySequence()

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

/**
 * ADR-131: the C# expression reading a suspend member's awaited **sealed base** handle back into
 * its public type. The Kotlin half pins a `StableRef` on the concrete arm the body produced, so
 * the generated `internal static Base FromHandle(IntPtr)` discriminator (ADR-009, the same one
 * the ordinary plan route reads a sealed return through) resolves the arm and takes ownership of
 * that handle. `new Base(resultPtr)`, the shipped spelling, is CS0144 against an abstract class.
 *
 * [csharpType] is passed in already spelled by the caller's own `nestedCsName()`, the one the
 * route spells `Task<...>` with, so the declared type and the read can never drift apart.
 *
 * A nullable base is guarded on the wire pointer first: the Kotlin half sends `null` as a null
 * result pointer, and `FromHandle` would otherwise discriminate on `IntPtr.Zero`.
 */
internal fun legacyDiscriminatedRead(
  handle: String,
  csharpType: String,
  nullable: Boolean,
): String =
  if (nullable) "$handle == IntPtr.Zero ? null : $csharpType.FromHandle($handle)"
  else "$csharpType.FromHandle($handle)"

/**
 * ADR-123: the same read as a named `Func<IntPtr, T>` argument, for the flow routes.
 * `KotlinFlowEnumerator<T>` and `KotlinStateFlow<T>` are shared by every member in the generated
 * file, so a collection element cannot specialise them; it hands them this per-member delegate
 * instead of the default `NugetMarshal.FromHandle<T>`. `h` never collides:
 * `componentCollectionRead` names its own lambdas from nesting level 1 (`h1`) down.
 */
internal fun legacyFlowElementReadArgument(type: BridgeType.Collection): String =
  "read: static h => ${legacyCollectionRead("h", type)}"

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
  // ADR-122: the `?` matters for a non-generic refusal too, since a nullable object is refused
  // *for* its nullability and the author would otherwise read the message as "no objects here".
  val nullable: String = if (isMarkedNullable) "?" else ""
  if (arguments.isEmpty()) return "$base$nullable"
  val rendered: String = arguments.joinToString(", ") { argument ->
    argument.type?.resolve()?.declaration?.simpleName?.asString() ?: "*"
  }
  return "$base<$rendered>$nullable"
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

/**
 * ADR-122: the eager dereference of a borrowed handle parameter, the same expression the ordinary
 * plan route emits. Emitted in the same prelude as [legacyLoweringStatement] and for a related
 * reason read the other way round: the coroutine captures a strong Kotlin reference, so a C#
 * consumer disposing its wrapper mid-flow cannot invalidate what the coroutine is still reading.
 * Inlined at the call site it would instead be evaluated inside `scope.launch`, after the export
 * has returned, which is exactly the lifetime hazard ADR-114 designed out.
 */
internal fun legacyHandleStatement(parameter: String, type: BridgeType.ObjectHandle): String =
  "val ${legacyLoweredName(parameter)} = $parameter.asStableRef<${type.qualifiedName}>().get()"

/**
 * Whether the Kotlin export binds this parameter to an eagerly-evaluated local rather than calling
 * the member with the ABI slot directly. True for both lowered shapes, so the export builders name
 * one rule instead of testing two variants in five places.
 */
internal fun ForwardLegacyParameterShape.isLegacyLowered(): Boolean = when (this) {
  is ForwardLegacyParameterShape.Marshalled, is ForwardLegacyParameterShape.Handle -> true
  ForwardLegacyParameterShape.Plain, is ForwardLegacyParameterShape.Refused -> false
}

/** The prelude line this parameter contributes, or null when it is passed through as-is. */
internal fun ForwardLegacyParameterShape.legacyPrelude(parameter: String): String? = when (this) {
  is ForwardLegacyParameterShape.Marshalled -> legacyLoweringStatement(parameter, type)
  is ForwardLegacyParameterShape.Handle -> legacyHandleStatement(parameter, type)
  ForwardLegacyParameterShape.Plain, is ForwardLegacyParameterShape.Refused -> null
}

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
