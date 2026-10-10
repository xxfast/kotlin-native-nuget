package io.github.xxfast.kotlin.native.nuget.processor.cir

import io.github.xxfast.kotlin.native.nuget.processor.nonNullStringOrThrow
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeAlias
import com.google.devtools.ksp.symbol.KSTypeArgument
import com.google.devtools.ksp.symbol.KSTypeParameter
import com.google.devtools.ksp.symbol.KSValueParameter
import com.google.devtools.ksp.symbol.Variance
import io.github.xxfast.kotlin.native.nuget.processor.forward.BridgeType
import io.github.xxfast.kotlin.native.nuget.processor.forward.CollectionKind
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardBridgeTypeClassifier
import io.github.xxfast.kotlin.native.nuget.processor.forward.forwardKotlinArgumentSpelling
import io.github.xxfast.kotlin.native.nuget.processor.forward.legacyGenericSealedElement
import io.github.xxfast.kotlin.native.nuget.processor.forward.legacyFlowElementInterface
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardCallablePlan
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnostic
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticOwner
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticSink
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardPropertyPlan
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardPropertyReceiver
import io.github.xxfast.kotlin.native.nuget.processor.forward.isEligibleSealedInterface
import io.github.xxfast.kotlin.native.nuget.processor.forward.isSealedInterface
import io.github.xxfast.kotlin.native.nuget.processor.forward.isValueClass
import io.github.xxfast.kotlin.native.nuget.processor.ForwardSymbolTable
import io.github.xxfast.kotlin.native.nuget.processor.isUnderPackage
import io.github.xxfast.kotlin.native.nuget.processor.valueClassUnderlyingOrThrow

/**
 * Expands a `typealias` reference to the type it names (ADR-018).
 *
 * ADR-018 amendment: the alias reference's own `?` is part of the type, so `Name?` (where
 * `typealias Name = String`) expands to `String?`; `KSTypeAlias.type.resolve()` describes only the
 * RHS and cannot see it. A generic alias's type parameters are substituted with the use-site
 * arguments, so `Box<Int>` (where `typealias Box<T> = List<T>`) expands to `List<Int>`, not the
 * alias's own `List<T>`. Chained aliases compose because every level recurses through here.
 */
internal fun KSType.expandAliases(): KSType {
  val decl = declaration
  if (decl !is KSTypeAlias) return this
  val target: KSType = decl.type.resolve().substituteAliasParameters(decl, arguments)
  val expanded: KSType = target.expandAliases()
  return if (isMarkedNullable) expanded.makeNullable() else expanded
}

/**
 * Replaces each of [alias]'s type parameters in this RHS type with the matching [useSite]
 * argument. Only a parameter spelled directly (the whole RHS, or one of its top-level arguments)
 * is substituted; the use-site argument object is reused as-is, so its own `?` and variance carry.
 */
private fun KSType.substituteAliasParameters(
  alias: KSTypeAlias,
  useSite: List<KSTypeArgument>,
): KSType {
  if (useSite.isEmpty()) return this
  val parameters: List<String> = alias.typeParameters.map { it.name.asString() }
  if (parameters.isEmpty() || useSite.size != parameters.size) return this
  fun useSiteFor(type: KSType?): KSTypeArgument? {
    // Matched by name: an alias is top-level, so its RHS can only name its own parameters (KSP2
    // does not hand back the same `KSTypeParameter` instance through `resolve()`).
    val parameter = type?.declaration as? KSTypeParameter ?: return null
    val index: Int = parameters.indexOf(parameter.name.asString())
    return if (index >= 0) useSite[index] else null
  }
  // Defensive: `typealias Id<T> = T` is rejected by the Kotlin compiler, so no compiling module
  // reaches this arm; KSP still sees such a source, and it must not spell a bare `T`.
  useSiteFor(this)?.let { argument ->
    val resolved: KSType = argument.type?.resolve() ?: return this
    return if (isMarkedNullable) resolved.makeNullable() else resolved
  }
  if (arguments.none { useSiteFor(it.type?.resolve()) != null }) return this
  return replace(arguments.map { argument -> useSiteFor(argument.type?.resolve()) ?: argument })
}

internal val KOTLIN_TO_CSHARP_RETURN = mapOf(
  "String" to "IntPtr",
  "Byte" to "sbyte",
  "UByte" to "byte",
  "Short" to "short",
  "UShort" to "ushort",
  "Int" to "int",
  "UInt" to "uint",
  "Long" to "long",
  "ULong" to "ulong",
  "Float" to "float",
  "Double" to "double",
  "Boolean" to "bool",
  "Unit" to "void",
)

internal val KOTLIN_TO_CSHARP_PARAM = mapOf(
  "String" to "string",
  "Char" to "char",
  "Byte" to "sbyte",
  "UByte" to "byte",
  "Short" to "short",
  "UShort" to "ushort",
  "Int" to "int",
  "UInt" to "uint",
  "Long" to "long",
  "ULong" to "ulong",
  "Float" to "float",
  "Double" to "double",
  "Boolean" to "bool",
)

internal val LAMBDA_TYPES = setOf(
  "kotlin.Function0", "kotlin.Function1", "kotlin.Function2", "kotlin.Function3",
)

internal val SUSPEND_LAMBDA_TYPES = setOf(
  "kotlin.coroutines.SuspendFunction0",
  "kotlin.coroutines.SuspendFunction1",
  "kotlin.coroutines.SuspendFunction2",
  "kotlin.coroutines.SuspendFunction3",
)

// ADR-205: SharedFlow<T> (and MutableSharedFlow<T>) is-a Flow whose `collect` replays the
// Kotlin-side replay cache and never completes, so it rides the plain Flow `_collect` route
// unchanged. FLOW_TYPES stays the union so every existing call site picks the shared types up.
// ADR-209: split into READ_ONLY_SHARED_FLOW_TYPES / MUTABLE_SHARED_FLOW_TYPES (the ADR-071
// union pattern), read through [sharedFlowSurface], so a declared MutableSharedFlow<T> can gain
// its write surface beside the `ReplayCache` every shared flow has.
internal val PLAIN_FLOW_TYPES = setOf("kotlinx.coroutines.flow.Flow")
internal val READ_ONLY_SHARED_FLOW_TYPES = setOf("kotlinx.coroutines.flow.SharedFlow")
internal val MUTABLE_SHARED_FLOW_TYPES = setOf("kotlinx.coroutines.flow.MutableSharedFlow")
internal val SHARED_FLOW_TYPES = READ_ONLY_SHARED_FLOW_TYPES + MUTABLE_SHARED_FLOW_TYPES
internal val FLOW_TYPES = PLAIN_FLOW_TYPES + SHARED_FLOW_TYPES

/**
 * ADR-209: what a `Flow`-family member surfaces beyond `KotlinFlow<T>`. Decided once per member by
 * [sharedFlowSurface] and read by every half (the Kotlin exports, the C# spelling and imports, the
 * tracker bits and the named refusal), so an export and its import cannot disagree.
 */
internal enum class SharedFlowSurface {
  /** A plain `Flow<T>`: `KotlinFlow<T>`, nothing added. */
  NONE,

  /**
   * `KotlinSharedFlow<T>`, adding `ReplayCache`: a `SharedFlow<T>`, or a `MutableSharedFlow<T>`
   * whose element has no ADR-071 write arm (that refusal is named).
   */
  READ_ONLY,

  /** `KotlinMutableSharedFlow<T>`, adding `SubscriptionCount`, `EmitAsync` and `TryEmit`. */
  MUTABLE,
}

/**
 * ADR-209: the surface of the alias-expanded declared flow type [flowType]. A declared
 * `MutableSharedFlow<T>` is mutable exactly when its element would give a `MutableStateFlow<T>` a
 * settable `.Value` ([isMutableStateFlowElementWritable]): `emit`/`tryEmit` take the value that
 * setter takes, so they cross through the same write slot.
 */
internal fun sharedFlowSurface(flowType: KSType?): SharedFlowSurface {
  val resolved: KSType = flowType?.expandAliases() ?: return SharedFlowSurface.NONE
  val qualified: String? = resolved.declaration.qualifiedName?.asString()
  if (qualified in READ_ONLY_SHARED_FLOW_TYPES) return SharedFlowSurface.READ_ONLY
  if (qualified !in MUTABLE_SHARED_FLOW_TYPES) return SharedFlowSurface.NONE
  val element: KSType? = resolved.arguments.firstOrNull()?.type?.resolve()?.expandAliases()
  if (isMutableStateFlowElementWritable(element)) return SharedFlowSurface.MUTABLE
  return SharedFlowSurface.READ_ONLY
}

/**
 * ADR-209: the C# spelling of a flow member's holder for [surface]: `KotlinFlow`,
 * `KotlinSharedFlow` or `KotlinMutableSharedFlow`, without type arguments.
 */
internal fun SharedFlowSurface.csharpHolder(): String = when (this) {
  SharedFlowSurface.NONE -> "KotlinFlow"
  SharedFlowSurface.READ_ONLY -> "KotlinSharedFlow"
  SharedFlowSurface.MUTABLE -> "KotlinMutableSharedFlow"
}

// ADR-065: StateFlow<T> (and, as a read-only view, MutableStateFlow<T>) is a hot,
// always-current-value stream. It is-a Flow, so detection is on the DECLARED type's exact
// qualifiedName and is checked BEFORE FLOW_TYPES everywhere FLOW_TYPES is consulted -- never via
// isAssignableFrom, which would make a StateFlow match the plain-Flow branch and silently lose
// `.Value`. SharedFlow/MutableSharedFlow are FLOW_TYPES members (ADR-205), not StateFlows.
//
// ADR-071: split into MUTABLE_STATE_FLOW_TYPES / READ_ONLY_STATE_FLOW_TYPES so a genuinely
// DECLARED `MutableStateFlow<T>` (not narrowed through `.asStateFlow()`) can additionally gain a
// settable `.Value`. STATE_FLOW_TYPES stays their union so every existing call site (which only
// needs "is this a StateFlow-shaped member at all") is unchanged.
internal val MUTABLE_STATE_FLOW_TYPES = setOf("kotlinx.coroutines.flow.MutableStateFlow")
internal val READ_ONLY_STATE_FLOW_TYPES = setOf("kotlinx.coroutines.flow.StateFlow")
internal val STATE_FLOW_TYPES = READ_ONLY_STATE_FLOW_TYPES + MUTABLE_STATE_FLOW_TYPES

internal class CollectionHelperTracker {
  var needsList: Boolean = false
  var needsMap: Boolean = false
  var needsSet: Boolean = false

  // ADR-151: at least one planned `ByteArray` anywhere in the file, which gates NugetBytesNative
  // and the NugetMarshal.ReadBytes/CreateBytes pair.
  var needsBytes: Boolean = false
  var needsAsync: Boolean = false
  var needsFlow: Boolean = false
  var needsStateFlow: Boolean = false

  // ADR-071: at least one publicly-DECLARED MutableStateFlow<T> member/return needs the settable
  // `KotlinMutableStateFlow<T>` subclass emitted (implies needsStateFlow, which implies needsFlow).
  var needsMutableStateFlow: Boolean = false

  // ADR-209: at least one `KotlinSharedFlow<T>` member (implies needsFlow and, for `ReplayCache`'s
  // `NugetMarshal.ReadList`, needsList).
  var needsSharedFlow: Boolean = false

  // ADR-209: at least one `KotlinMutableSharedFlow<T>` member (implies needsSharedFlow, and, for
  // `SubscriptionCount`, needsStateFlow plus the `NugetStateFlowNative` handle helper).
  var needsMutableSharedFlow: Boolean = false

  /** ADR-209: marks the helper bits a member of [surface] needs. */
  fun trackSharedFlow(surface: SharedFlowSurface) {
    if (surface == SharedFlowSurface.NONE) return
    needsFlow = true
    needsAsync = true
    needsSharedFlow = true
    if (surface == SharedFlowSurface.MUTABLE) needsMutableSharedFlow = true
  }

  // ADR-068: at least one `suspend fun` returns StateFlow<T>/MutableStateFlow<T> -- gates the two
  // shared generic `nuget_stateflow_collect`/`nuget_stateflow_value` handle-keyed exports.
  var needsSuspendStateFlow: Boolean = false
  var needsSubscription: Boolean = false
  val lambdaArities: MutableSet<Int> = mutableSetOf()
  val suspendLambdaArities: MutableSet<Int> = mutableSetOf()
  val callbackDelegates: MutableList<CirCallbackDelegate> = mutableListOf()

  /** Marks List/Map/Set helper needs from a planned [BridgeType] (result, parameter, or property). */
  fun trackCollection(type: BridgeType) {
    val unwrapped: BridgeType = if (type is BridgeType.Nullable) type.type else type
    // ADR-151: a ByteArray is not a Collection, but it rides the collection wire and needs its
    // own native class, so it is tracked on the same walk.
    if (unwrapped == BridgeType.ByteArray) needsBytes = true
    val collection = unwrapped as? BridgeType.Collection ?: return
    when (collection.kind) {
      CollectionKind.LIST, CollectionKind.MUTABLE_LIST -> needsList = true
      CollectionKind.MAP, CollectionKind.MUTABLE_MAP -> needsMap = true
      CollectionKind.SET, CollectionKind.MUTABLE_SET -> needsSet = true
    }
    // ADR-099: a nested component needs ITS kind's native class too -- `Set<List<String>>` is the
    // first declaration to need NugetSetNative and NugetListNative in the same file.
    collection.element?.let { trackCollection(it) }
    collection.key?.let { trackCollection(it) }
    collection.value?.let { trackCollection(it) }
  }

  fun trackPlan(plan: ForwardCallablePlan) {
    trackCollection(plan.publicSignature.result)
    // ADR-160 amendment: a planned lambda return needs its arity's `KotlinFunc`/`KotlinAction`
    // class and `NugetFunctionNative.Invoke{n}` import, which only the tracker declares.
    (plan.publicSignature.result as? BridgeType.ReturnedLambda)?.let { lambda ->
      lambdaArities.add(lambda.arity)
    }
    plan.publicSignature.parameters.forEach { parameter -> trackCollection(parameter.type) }
  }

  fun trackProperty(plan: ForwardPropertyPlan) {
    trackCollection(plan.type)
    // ADR-132 (2026-09-20): an extension property's RECEIVER is a marshalled slot like any other,
    // and since the receiver set admits `Collection` it can be the only collection in a file. The
    // property's declared type alone used to answer here, so `val List<String>.longestName: String`
    // disposed its receiver handle with a `NugetListNative` the file never emitted (CS0103).
    val receiver: ForwardPropertyReceiver = plan.receiver
    if (receiver is ForwardPropertyReceiver.Value) trackCollection(receiver.type)
  }
}

/**
 * ADR-071: how a `MutableStateFlow<T>` element crosses the settable-`.Value` write seam, decided
 * once (nullability stripped) and read by every half: the gates, the Kotlin `_set_value` slot and
 * the C# extern plus write lambda. Sealed so every consumer `when`s over it exhaustively, which is
 * the point: a value class used to pass a boolean "is it a class" gate and fall into the handle
 * arm, spelling `v._handle` on a C# record struct. A new arm now fails to compile everywhere it is
 * not handled instead of silently taking a neighbour's wire.
 */
internal sealed interface MutableStateFlowElement {

  /** An element with a settable `.Value`. */
  sealed interface Writable : MutableStateFlowElement

  /** A [KOTLIN_TO_CSHARP_PARAM] primitive, `Char` or `String`: crosses by value. */
  data object Scalar : Writable

  /** An enum (ADR-071 amendment): crosses as its ordinal, as the synchronous enum setter does. */
  data class Enum(val qualifiedName: String) : Writable

  /**
   * An exported class or object: crosses as its handle (`v._handle`). [kotlinType] is the type
   * the Kotlin half reads that handle at, spelled by [forwardKotlinArgumentSpelling]: the
   * qualified name, applied for a generic instantiation (`pkg.Box<kotlin.String>`, ADR-208),
   * since a generic class has no bare spelling (`asStableRef<pkg.Box>()` does not compile).
   */
  data class Handle(val kotlinType: String) : Writable

  /**
   * ADR-071 amendment (value-class element write): a value class whose underlying the synchronous
   * value-class setter carries (ADR-077: a non-null String or primitive other than `Char`, an
   * enum, or an exported class or object). It crosses as that [underlying] (`v.Id`, `(int)v.Mood`,
   * `v.Cat._handle`) and Kotlin re-wraps it, re-running the value class's `init`.
   * [underlyingProperty] is the Kotlin name of the underlying (the C# record struct capitalizes
   * it); [underlyingSimpleName] is the underlying's Kotlin simple name, read by the scalar arm.
   */
  data class ValueClass(
    val qualifiedName: String,
    val simpleName: String,
    val underlyingProperty: String,
    val underlying: Writable,
    val underlyingSimpleName: String,
  ) : Writable {
    /** The C# record struct's underlying property, capitalized as `CirValueClass` declares it. */
    val csProperty: String get() = underlyingProperty.replaceFirstChar { it.uppercase() }
  }

  /**
   * A value class over an underlying no write arm carries ([underlying], spelled for the
   * diagnostic): the member binds read-only and the refusal is named (`SKIPPED_UNSUPPORTED_INPUT`)
   * rather than silent.
   */
  data class RefusedValueClass(val qualifiedName: String, val underlying: String) :
    MutableStateFlowElement

  /**
   * Everything else (collections, `ByteArray`, lambdas, interfaces): the declared
   * `MutableStateFlow<T>` keeps ADR-065/067's read-only `KotlinStateFlow<T>` mapping. A member
   * that survives read-only is named (`SKIPPED_UNSUPPORTED_INPUT`); the kinds left read-only on
   * purpose carry their own reason ([readOnlyMutableStateFlowElement]).
   */
  data object ReadOnly : MutableStateFlowElement
}

/**
 * Classifies a `MutableStateFlow<T>` element for the write seam, ignoring its nullability (the
 * nullable rule lives in [isMutableStateFlowElementWritable]).
 */
internal fun classifyMutableStateFlowElement(elementType: KSType?): MutableStateFlowElement {
  val declaration: KSDeclaration =
    elementType?.expandAliases()?.declaration ?: return MutableStateFlowElement.ReadOnly
  val simpleName: String = declaration.simpleName.asString()
  if (KOTLIN_TO_CSHARP_PARAM.containsKey(simpleName)) return MutableStateFlowElement.Scalar
  // ROADMAP Phase 4: `kotlin.ByteArray` is a `CLASS`, so it would fall into the handle arm below,
  // but it crosses as a *bytes* handle (`nuget_bytes_create`/`ReadBytes`), not as the `v._handle`
  // an exported wrapper carries, and the ADR-071 write seam has no arm that mints one. Its READ
  // side binds (ADR-151 amendment), through the read-only `KotlinStateFlow<byte[]>` mapping.
  if (declaration.qualifiedName?.asString() == "kotlin.ByteArray") {
    return MutableStateFlowElement.ReadOnly
  }
  val classDeclaration: KSClassDeclaration =
    declaration as? KSClassDeclaration ?: return MutableStateFlowElement.ReadOnly
  val qualifiedName: String = classDeclaration.qualifiedName?.asString() ?: simpleName
  val expanded: KSType = elementType.expandAliases()
  return when {
    classDeclaration.classKind == ClassKind.ENUM_CLASS ->
      MutableStateFlowElement.Enum(qualifiedName)

    // Before the CLASS arm: a value class is a `CLASS` too. A generic value class is refused by
    // name there and is never read as a handle.
    classDeclaration.isValueClass() -> valueClassElement(classDeclaration, qualifiedName)

    // ADR-208: a closed generic instantiation (`Box<String>`, a generic sealed `Outcome<Int>`)
    // is an object handle like any other class, read at its applied spelling.
    classDeclaration.classKind == ClassKind.CLASS ||
        classDeclaration.classKind == ClassKind.OBJECT ->
      MutableStateFlowElement.Handle(expanded.makeNotNullable().forwardKotlinArgumentSpelling())

    else -> MutableStateFlowElement.ReadOnly
  }
}

/**
 * ADR-071 amendment (value-class element write): admits exactly the underlyings the synchronous
 * value-class property setter lowers (`ForwardPropertyPlanner.isPlannable`): a non-null String or
 * primitive other than `Char`, an enum, or an exported class or object. A generic value class, a
 * nullable, `Char`, nested value-class, collection or `kotlin.*` class underlying (which the
 * ordinary classifier maps to `Instant`, `Uuid` or `Throwable`, never to an object handle) is
 * refused, naming that underlying.
 */
private fun valueClassElement(
  declaration: KSClassDeclaration,
  qualifiedName: String,
): MutableStateFlowElement {
  val parameter: KSValueParameter? = declaration.primaryConstructor?.parameters?.singleOrNull()
  val type: KSType? = parameter?.type?.resolve()?.expandAliases()
  val underlyingDeclaration: KSDeclaration? = type?.declaration
  val underlyingQualified: String =
    underlyingDeclaration?.qualifiedName?.asString() ?: "an unresolved type"
  val refused = MutableStateFlowElement.RefusedValueClass(
    qualifiedName,
    underlyingQualified + if (type?.isMarkedNullable == true) "?" else "",
  )
  val underlyingProperty: String = parameter?.name?.asString() ?: return refused
  if (type == null || underlyingDeclaration == null) return refused
  if (declaration.typeParameters.isNotEmpty() || type.isMarkedNullable) return refused
  val underlyingSimpleName: String = underlyingDeclaration.simpleName.asString()
  val underlying: MutableStateFlowElement.Writable = when (
    val element: MutableStateFlowElement = classifyMutableStateFlowElement(type)
  ) {
    MutableStateFlowElement.Scalar ->
      if (underlyingSimpleName == "Char") return refused else MutableStateFlowElement.Scalar

    is MutableStateFlowElement.Enum -> element
    is MutableStateFlowElement.Handle -> {
      val packageName: String = underlyingDeclaration.packageName.asString()
      if (packageName == "kotlin" || packageName.startsWith("kotlin.")) return refused
      element
    }

    is MutableStateFlowElement.ValueClass,
    is MutableStateFlowElement.RefusedValueClass,
    MutableStateFlowElement.ReadOnly,
      -> return refused
  }
  return MutableStateFlowElement.ValueClass(
    qualifiedName = qualifiedName,
    simpleName = declaration.simpleName.asString(),
    underlyingProperty = underlyingProperty,
    underlying = underlying,
    underlyingSimpleName = underlyingSimpleName,
  )
}

/**
 * ADR-071: why a declared `MutableStateFlow` of [element] (`a List element`) is left read-only,
 * as the refusal's parenthesis says it.
 */
internal class MutableStateFlowReadOnlyElement(val element: String, val why: String) {
  /**
   * The refusal's sentence for a declared [holder] (`MutableStateFlow`, or `MutableSharedFlow`,
   * whose emit rides the same write slot): one wording for both, so they cannot drift.
   */
  fun noWriteArm(holder: String): String = "$element of a $holder has no write arm ($why)"
}

/**
 * ADR-071: the element kinds a declared `MutableStateFlow` leaves read-only on purpose, or null
 * for every other element. [classifyMutableStateFlowElement] answers
 * [MutableStateFlowElement.ReadOnly] for each; this says which, and why, for the diagnostic.
 *
 * A collection and a `ByteArray` cross as a handle to a wire container (`nuget_list_*`,
 * `nuget_bytes_create`) that no write arm builds. An interface element is read through its
 * ADR-040 backing wrapper, and the forward route has no arm that carries a C# implementation
 * into a Kotlin flow.
 */
internal fun readOnlyMutableStateFlowElement(
  elementType: KSType?,
): MutableStateFlowReadOnlyElement? {
  val declaration: KSDeclaration = elementType?.expandAliases()?.declaration ?: return null
  val container = "the write seam does not build its wire container"
  return when (declaration.qualifiedName?.asString()) {
    "kotlin.ByteArray" -> MutableStateFlowReadOnlyElement("a ByteArray element", container)
    "kotlin.collections.List", "kotlin.collections.MutableList" ->
      MutableStateFlowReadOnlyElement("a List element", container)

    "kotlin.collections.Set", "kotlin.collections.MutableSet" ->
      MutableStateFlowReadOnlyElement("a Set element", container)

    "kotlin.collections.Map", "kotlin.collections.MutableMap" ->
      MutableStateFlowReadOnlyElement("a Map element", container)

    else -> {
      val isInterface: Boolean =
        (declaration as? KSClassDeclaration)?.classKind == ClassKind.INTERFACE
      if (!isInterface) return null
      MutableStateFlowReadOnlyElement(
        "an interface element",
        "a C# implementation cannot be written into a Kotlin flow on the forward route",
      )
    }
  }
}

/** The [MutableStateFlowElement.Writable] arm of an element a gate already admitted. */
internal fun writableMutableStateFlowElement(
  elementType: KSType?,
): MutableStateFlowElement.Writable {
  val element: MutableStateFlowElement = classifyMutableStateFlowElement(elementType)
  return requireNotNull(element as? MutableStateFlowElement.Writable) {
    "MutableStateFlow element ${elementType?.declaration?.simpleName?.asString()} reached the " +
        "write seam without a write arm"
  }
}

/**
 * ADR-071: whether a `MutableStateFlow<T>` element gets a settable `.Value`. A non-null element is
 * exactly a [MutableStateFlowElement.Writable] one, and so is a nullable one of every writable
 * kind today. `Boolean?` and `Char?` used to be held back by ADR-067's width deferral; both widths
 * are pinned now (`I1`, ADR-069; `U2`, ADR-098) on the write slots and on the boxed read, so they
 * take the nullable scalars' has-value pair. A nullable enum rides the same has-value slot ahead
 * of its ordinal slot. The per-kind `when` stays as the one place a kind's nullable form can be
 * held back. Read by every gate on both halves, so a setter export and its C# import cannot
 * disagree.
 */
internal fun isMutableStateFlowElementWritable(elementType: KSType?): Boolean {
  val element: MutableStateFlowElement = classifyMutableStateFlowElement(elementType)
  if (element !is MutableStateFlowElement.Writable) return false
  if (elementType?.isMarkedNullable != true) return true
  return when (element) {
    MutableStateFlowElement.Scalar -> true
    is MutableStateFlowElement.Enum -> true
    is MutableStateFlowElement.Handle -> true
    // The ordinary property route plans every nullable value class it plans non-null (ADR-079):
    // an in-band null for a String or handle underlying, the has-value pair otherwise. That rule
    // admits a `Boolean` underlying too, so this does; the read is the ADR-171 factory path.
    is MutableStateFlowElement.ValueClass -> true
  }
}

/**
 * ADR-071: the C# half of the write seam for a (already-[isMutableStateFlowElementWritable])
 * element whose public spelling is [csElementType]. A scalar crosses by value, an enum as its
 * `(int)` ordinal, an object as its handle; a nullable `String?` stays one nullable slot, a
 * nullable object passes a null handle, and a nullable scalar crosses as the legacy route's
 * has-value pair (`valueHasValue, value`).
 */
internal fun mutableStateFlowWrite(elementType: KSType?, csElementType: String): CirStateFlowWrite {
  val nullable: Boolean = elementType?.isMarkedNullable == true
  val simpleName: String? = elementType?.expandAliases()?.declaration?.simpleName?.asString()
  val element: MutableStateFlowElement.Writable = writableMutableStateFlowElement(elementType)
  return when (element) {
    is MutableStateFlowElement.Handle -> if (nullable) {
      CirStateFlowWrite(
        parameters = listOf(CirParameter("value", KOTLIN_HANDLE)),
        arguments = "v?._handle ?? NugetKotlinHandle.Null",
      )
    } else {
      CirStateFlowWrite(
        parameters = listOf(CirParameter("value", KOTLIN_HANDLE)),
        arguments = "v._handle",
        rejectsNull = true,
      )
    }

    // A nullable enum is the nullable scalar's has-value pair over the ordinal slot: the ordinal
    // wire has no null of its own, so `null` crosses as `valueHasValue = false`, never ordinal 0.
    is MutableStateFlowElement.Enum -> if (nullable) {
      CirStateFlowWrite(
        parameters = listOf(
          CirParameter("valueHasValue", "bool"),
          CirParameter("value", "int"),
        ),
        arguments = "v.HasValue, (int)v.GetValueOrDefault()",
      )
    } else {
      CirStateFlowWrite(
        parameters = listOf(CirParameter("value", "int")),
        arguments = "(int)v",
      )
    }

    MutableStateFlowElement.Scalar -> if (nullable && simpleName != "String") {
      CirStateFlowWrite(
        parameters = listOf(
          CirParameter("valueHasValue", "bool"),
          CirParameter("value", csElementType.removeSuffix("?")),
        ),
        arguments = "v.HasValue, v.GetValueOrDefault()",
      )
    } else {
      CirStateFlowWrite(
        parameters = listOf(CirParameter("value", csElementType)),
        // `flow.Value = null!` on a non-null `string` element would hand the export a null string
        // pointer for a non-null Kotlin `String`; its read is the one guard every non-null string
        // crossing shares ([nonNullStringOrThrow]), not a statement of its own.
        arguments = if (!nullable && simpleName == "String") nonNullStringOrThrow("v") else "v",
      )
    }

    is MutableStateFlowElement.ValueClass -> valueClassStateFlowWrite(element, nullable)
  }
}

/**
 * ADR-071 amendment (value-class element write): the C# half, the synchronous value-class
 * setter's unwrap (`ForwardCirPropertyProjection`, ADR-077) over the lambda's `v`. The extern
 * slot is the underlying's own wire; a nullable element rides that underlying's nullable spelling
 * (an in-band null for a String or handle, the has-value pair for a primitive or enum ordinal).
 * A struct is never null, so nothing rejects null; a reference underlying is instead unwrapped
 * through [valueClassUnderlyingOrThrow], the one guard every value-class crossing shares, so
 * `default(V)` never reaches a non-null Kotlin slot (or, nullable, silently clears the flow).
 */
private fun valueClassStateFlowWrite(
  element: MutableStateFlowElement.ValueClass,
  nullable: Boolean,
): CirStateFlowWrite {
  val prop: String = element.csProperty
  // The reference underlying of the PRESENT struct: `v` itself, or `v.Value` behind `v.HasValue`.
  val present: String = valueClassUnderlyingOrThrow(
    struct = if (nullable) "v.Value" else "v",
    property = prop,
    structName = element.simpleName,
    parameter = "v",
  )
  val hasValuePair: (String, String) -> CirStateFlowWrite = { wire: String, unwrapped: String ->
    CirStateFlowWrite(
      parameters = listOf(CirParameter("valueHasValue", "bool"), CirParameter("value", wire)),
      arguments = "v.HasValue, $unwrapped",
    )
  }
  return when (element.underlying) {
    is MutableStateFlowElement.Handle -> CirStateFlowWrite(
      parameters = listOf(CirParameter("value", KOTLIN_HANDLE)),
      arguments = if (nullable) {
        "v.HasValue ? $present._handle : NugetKotlinHandle.Null"
      } else {
        "$present._handle"
      },
    )

    is MutableStateFlowElement.Enum -> if (nullable) {
      hasValuePair("int", "(int)v.GetValueOrDefault().$prop")
    } else {
      CirStateFlowWrite(listOf(CirParameter("value", "int")), "(int)v.$prop")
    }

    MutableStateFlowElement.Scalar -> {
      val wire: String = KOTLIN_TO_CSHARP_PARAM.getValue(element.underlyingSimpleName)
      when {
        wire == "string" -> CirStateFlowWrite(
          parameters = listOf(CirParameter("value", if (nullable) "string?" else "string")),
          arguments = if (nullable) "v.HasValue ? $present : null" else present,
        )

        nullable -> hasValuePair(wire, "v.GetValueOrDefault().$prop")
        else -> CirStateFlowWrite(listOf(CirParameter("value", wire)), "v.$prop")
      }
    }

    is MutableStateFlowElement.ValueClass ->
      error("value class ${element.qualifiedName} over a value class has no write arm")
  }
}

internal fun mapReturnType(kotlinType: String): String =
  KOTLIN_TO_CSHARP_RETURN[kotlinType] ?: "IntPtr"

internal fun mapParamType(kotlinType: String): String =
  KOTLIN_TO_CSHARP_PARAM[kotlinType] ?: "IntPtr"

/**
 * The C# spelling of a declaration's *name within its namespace*, enclosing scope included:
 * `Circle` for a top-level class, `Shape.Circle` for a subclass declared inside its sealed base.
 *
 * ADR-009 declares a sealed subclass as a **nested** C# class (`CirSealedRenderer` emits
 * `public sealed class Circle : Shape` *inside* `public abstract class Shape`), so the
 * simple-name-only spelling `global::Namespace.Circle` names a type that does not exist and fails
 * the whole `Interop.cs` with CS0234/CS0246. Every member **type position** — property, method
 * return and its `new T(handle)` construction, parameter — must therefore walk the enclosing
 * declarations and join their simple names outermost-first.
 *
 * The walk stops at the first non-class parent, so a file-level declaration is unchanged and a
 * class local to a function contributes only its own name (it is never exported anyway).
 */
internal fun KSClassDeclaration.nestedCsName(): String {
  val chain: List<KSClassDeclaration> =
    generateSequence<KSDeclaration>(this) { it.parentDeclaration }
      .takeWhile { it is KSClassDeclaration }
      .filterIsInstance<KSClassDeclaration>()
      .toList()
      .asReversed()
  // ADR-134 (reversing ADR-133's last-segment-only rule): an *enclosing* `interface` segment is
  // spelled `I<Name>` -- that is the C# type the child is declared inside (`ICage.Bar`), and the
  // ADR-040 backing wrapper `Cage` declares nothing. An ADR-112 ELIGIBLE sealed interface is
  // exempt: it renders as `public abstract class Beam`, so `IBeam` exists nowhere (issue #54).
  // The LAST segment keeps its bare name here; `nestedInterfaceCsName()` adds the `I` to it when
  // the interface itself is the type being named.
  return chain
    .mapIndexed { index, declaration ->
      val simpleName: String = declaration.simpleName.asString()
      val isEnclosingInterface: Boolean = index < chain.lastIndex &&
          declaration.classKind == ClassKind.INTERFACE &&
          !declaration.isEligibleSealedInterface()
      if (isEnclosingInterface) "I$simpleName" else simpleName
    }
    .joinToString(".")
}

/**
 * ADR-133: the C entry-point prefix of a declaration -- the whole enclosing chain, each simple name
 * lowercased, `_`-joined (`owner_nested`, `owner_middle_inner`).
 *
 * ADR-163: that chain is no longer the whole prefix. It now sits behind the library and package
 * qualification [ForwardSymbolTable] owns, so two `Kitten` classes in two packages no longer derive
 * one symbol. The zero-argument form was DELETED rather than kept as a default: every caller has to
 * pass the table, so no route can mint an unqualified prefix by omission.
 */
internal fun KSClassDeclaration.nativePrefix(symbols: ForwardSymbolTable): String =
  symbols.owner(this)

/**
 * ADR-133: the ADR-040 C# interface name of a declaration, with the `I` on the LAST segment only
 * (`Owner.IListener`). A naive `I` + [nestedCsName] gives the nonexistent `IOwner.Listener`.
 */
internal fun KSClassDeclaration.nestedInterfaceCsName(): String {
  val nested: String = nestedCsName()
  val cut: Int = nested.lastIndexOf('.')
  return if (cut < 0) {
    "I$nested"
  } else {
    nested.substring(0, cut + 1) + "I" + nested.substring(cut + 1)
  }
}

internal fun mapPackageToNamespace(
  kotlinPackage: String,
  rootPackage: String,
  rootNamespace: String,
): String {
  if (rootPackage.isEmpty()) return rootNamespace

  // ADR-066 §5 amendment (2026-09-13): "under root" is a SEGMENT-bounded test, and it is
  // literally the same [isUnderPackage] predicate admission already applies, so the two cannot
  // drift. An unbounded `startsWith(rootPackage)` made `com.examples.x` under root `com.example`
  // strip a literal prefix and render `Clinic.S.X`, a namespace built from half a package
  // segment, which no admission decision agrees with. A package that is not under root keeps its
  // whole name.
  val relative: String =
    if (isUnderPackage(kotlinPackage, rootPackage)) {
      kotlinPackage.removePrefix(rootPackage).removePrefix(".")
    } else {
      kotlinPackage
    }

  if (relative.isEmpty()) return rootNamespace

  val suffix: String = relative.split(".")
    .joinToString(".") { segment ->
      segment.replaceFirstChar { it.uppercase() }
    }

  return "$rootNamespace.$suffix"
}

/**
 * ADR-133: the C# spelling of an interface used as a generic **type-parameter bound**
 * (`class Box<T : Pet>` -> `where T : IPet`), on the legacy generic class and function routes.
 *
 * A NESTED interface carries its owner chain and is qualified exactly as the classifier qualifies
 * an interface at a member position: bare `IKeeper` names nothing at namespace scope (CS0246).
 *
 * Amended 2026-09-14: a TOP-LEVEL bound is qualified too. The bare `I$simpleName` it used to keep
 * only resolves when the bound's own package maps to the same namespace as the file the bound is
 * printed into, which is issue #41's disproved assumption: `nested.PetCrate<T : cat.Pet>` emitted
 * `where T : IPet` into `TestLibrary.Nested`, whose only usings are `System` ones (`CirRenderer`),
 * and `Interop.cs` failed CS0246. Qualifying unconditionally is the one rule every other render
 * site follows; a bound position is a type reference, so `global::` is legal there.
 */
internal fun KSClassDeclaration.legacyBoundInterfaceCsName(context: NugetContext): String {
  val simpleName: String = simpleName.asString()
  if (context.rootNamespace.isEmpty()) return "I$simpleName"
  val namespace: String = mapPackageToNamespace(
    packageName.asString(), context.rootPackage, context.rootNamespace,
  )
  return "global::$namespace.${nestedInterfaceCsName()}"
}

/**
 * The class-bound twin of [legacyBoundInterfaceCsName] (`class CatCrate<T : Cat>` -> `where T :
 * global::TestLibrary.Cat.Cat`). Same defect, same rule: the bare simple name the generic routes
 * used to print resolves only inside the bound's own namespace, so a cross-package or nested
 * bound named nothing (CS0246, or CS0118 when a sibling namespace shares the simple name). A
 * builtin bound never reaches it: [cirBoundConstraint] drops it first.
 */
internal fun legacyBoundClassCsName(type: KSType, context: NugetContext): String =
  qualifiedElementCsType(type, context)

/**
 * The C# `where` constraint for one Kotlin type-parameter bound, shared by the generic class
 * (`cirTypeParameters`) and generic function (`translateGenericFunction`) routes; null means no
 * constraint.
 *
 * A Kotlin builtin bound other than `Any` (`Comparable<T>`, `Number`, `CharSequence`) has no C#
 * spelling: `Number` is CS0246, `Kotlin.IComparable` under the root CS0234 (ADR-123), and
 * `IComparable<T>` would lock out every generated wrapper, none of which implements it. It is
 * dropped, keeping `notnull` when the bound is non-null, and reported as INFO_DROPPED_BOUND on
 * [symbol] under [declaration]. The check runs before the interface/class split so an interface
 * builtin (`Comparable`) and a class builtin (`Number`) take the same arm. ADR-198: a non-null
 * `Enum<T>` is the exception, spelled [ENUM_CONSTRAINT].
 */
internal fun cirBoundConstraint(
  bound: KSType,
  context: NugetContext,
  logger: KSPLogger,
  symbol: KSNode,
  declaration: String,
  // The C# spelling of a type parameter a bound's arguments name (`IRival<T>`): the class route
  // may have renamed one around a member (`A` -> `TA`); the function route keeps every name.
  typeParameterName: (String) -> String = { name -> name },
): String? {
  val qualifiedName: String? = bound.declaration.qualifiedName?.asString()
  // ADR-147 amendment: a `T : Pet?` carries null on the Kotlin half, so its C# constraint says so
  // too; a bare `where T : Pet` would make `Kennel<Pet?>` a CS8631 in a nullable context.
  val nullable: String = if (bound.isMarkedNullable) "?" else ""
  val notNull: String? = if (bound.isMarkedNullable) null else NOTNULL_CONSTRAINT
  // ADR-147 amendment: `T : Any` is C#'s `where T : notnull`; the implicit `Any?` of an
  // unconstrained parameter is no constraint at all.
  if (qualifiedName == "kotlin.Any") return notNull

  fun dropped(reason: String): String? {
    ForwardDiagnosticSink.emit(
      listOf(
        ForwardDiagnostic(
          kind = ForwardDiagnosticKind.INFO_DROPPED_BOUND,
          symbol = symbol,
          declaration = declaration,
          reason = reason,
          hint = "the declaration still binds; a C# caller can pass a type argument Kotlin would " +
              "reject, which fails at the call",
          // Nothing generated loses a member: the declaration binds with a looser constraint.
          owner = null,
        ),
      ),
      logger,
    )
    return notNull
  }

  // An alias to a builtin is the builtin; the interface arm below keeps reading the unexpanded
  // declaration it always read.
  val expanded: KSDeclaration = bound.expandAliases().declaration
  val classDeclaration: KSClassDeclaration? = bound.declaration as? KSClassDeclaration
  if (expanded.packageName.asString().isKotlinBuiltinPackage()) {
    // ADR-198: the one builtin C# can say. A generated enum is a C# `enum`, so `struct, Enum`
    // admits exactly the enums and rejects `int`, `string`, wrappers and `Mood?`. A nullable
    // `Enum<T>?` keeps the drop: no C# constraint is both a value type and nullable.
    if (expanded.qualifiedName?.asString() == "kotlin.Enum" && !bound.isMarkedNullable) {
      return ENUM_CONSTRAINT
    }
    return dropped(
      "bound '${expanded.qualifiedName?.asString()}' on this type parameter is a Kotlin " +
          "builtin with no C# equivalent and is dropped from the where clause",
    )
  }

  // A generic bound keeps its type arguments (`IRival<T>`); the bare name is CS0305. One C# cannot
  // spell (a use-site projection, a builtin collection) drops the whole bound, named.
  val arguments: List<String> = bound.arguments.map { argument ->
    argument.boundArgumentCsName(context, typeParameterName)
      ?: return dropped(
        "bound '${bound.forwardDiagnosticSpelling()}' on this type parameter has a type " +
            "argument with no C# spelling and is dropped from the where clause",
      )
  }
  val applied: String = if (arguments.isEmpty()) "" else "<${arguments.joinToString(", ")}>"

  // ADR-133, amended 2026-09-14: every bound carries its owner chain and its namespace, nested or
  // not. A bare bound only resolves in the bound's own namespace.
  return when {
    classDeclaration != null && classDeclaration.classKind == ClassKind.INTERFACE ->
      classDeclaration.legacyBoundInterfaceCsName(context) + applied + nullable
    else -> legacyBoundClassCsName(bound, context) + applied + nullable
  }
}

/**
 * One type argument of a generic bound, as C# spells it inside the `where` clause, or null when
 * C# has no spelling for it: a star or use-site projection (C# has no use-site variance), or a
 * builtin other than a scalar keyword.
 */
private fun KSTypeArgument.boundArgumentCsName(
  context: NugetContext,
  typeParameterName: (String) -> String,
): String? {
  if (variance != Variance.INVARIANT) return null
  val type: KSType = type?.resolve() ?: return null
  val nullable: String = if (type.isMarkedNullable) "?" else ""
  val declaration: KSDeclaration = type.expandAliases().declaration
  if (declaration is KSTypeParameter) {
    return typeParameterName(declaration.name.asString()) + nullable
  }
  val classDeclaration: KSClassDeclaration = declaration as? KSClassDeclaration ?: return null
  if (classDeclaration.packageName.asString().isKotlinBuiltinPackage()) {
    if (type.arguments.isNotEmpty()) return null
    return KOTLIN_TO_CSHARP_PARAM[classDeclaration.simpleName.asString()]?.plus(nullable)
  }
  val arguments: List<String> = type.arguments.map { argument ->
    argument.boundArgumentCsName(context, typeParameterName) ?: return null
  }
  val applied: String = if (arguments.isEmpty()) "" else "<${arguments.joinToString(", ")}>"
  val name: String = if (classDeclaration.classKind == ClassKind.INTERFACE) {
    classDeclaration.legacyBoundInterfaceCsName(context)
  } else {
    legacyBoundClassCsName(type.makeNotNullable(), context)
  }
  return name + applied + nullable
}

/** A bound as the author wrote it, for a diagnostic: simple names, arguments kept. */
private fun KSType.forwardDiagnosticSpelling(): String {
  val name: String = declaration.simpleName.asString()
  if (arguments.isEmpty()) return name
  val spelled: String = arguments.joinToString(", ") { argument ->
    argument.type?.resolve()?.forwardDiagnosticSpelling() ?: "*"
  }
  return "$name<$spelled>"
}

/**
 * ADR-066: the `Flow<T>`/`StateFlow<T>` element-type route mapped its element by *simple* name
 * (`declaration.simpleName.asString()`), so `Flow<TopStory>` emitted the unqualified
 * `KotlinFlow<TopStory>`, a type that only resolves inside `Interop.cs` when the element's
 * namespace happens to coincide with the enclosing class's own namespace, which an admitted
 * dependency-module type is never guaranteed to do. Mirrors [ForwardBridgeTypeClassifier]'s enum
 * branch: a known scalar keeps its C# primitive spelling, and everything else is qualified.
 *
 * Qualification is unconditional whenever a root namespace exists: a reference already in the
 * enclosing namespace renders `global::Namespace.Name` too, rather than staying bare. That is
 * what shipped, and it is what a caller must expect; the earlier "a same-namespace reference
 * stays bare" wording described a same-namespace shortcut this function has never had. Only an
 * empty [NugetContext.rootNamespace] (nothing to qualify with) yields a bare name.
 */
internal fun qualifiedElementCsType(type: KSType?, context: NugetContext): String {
  val declaration: KSDeclaration = type?.expandAliases()?.declaration ?: return "Any"
  val simpleName: String = declaration.simpleName.asString()
  KOTLIN_TO_CSHARP_PARAM[simpleName]?.let { return it }
  val classDeclaration: KSClassDeclaration = declaration as? KSClassDeclaration ?: return simpleName
  // Enclosing scope included ([nestedCsName]): this route spells a *sealed subclass* property's
  // type (`CirClassTranslator`) and an ADR-067 flow element, either of which can be a class nested
  // in its sealed base and so declared as a nested C# class.
  // ADR-204: an ineligible sealed interface is declared only as `I<Name>` (the eligible one is the
  // ADR-112 abstract class, spelled by its bare name). Spelling the bare name named a type nothing
  // declares (CS0234). The member is still refused upstream unless the interface has a
  // discriminator (`legacyFlowElementShape`).
  val nestedName: String = if (
    classDeclaration.isSealedInterface() && !classDeclaration.isEligibleSealedInterface()
  ) {
    classDeclaration.nestedInterfaceCsName()
  } else {
    classDeclaration.nestedCsName()
  }
  if (context.rootNamespace.isEmpty()) return nestedName
  val kotlinPackage: String = classDeclaration.packageName.asString()
  // ADR-123: this is the *user-type* speller. A Kotlin builtin reaching it is a defect at every
  // call site, because [mapPackageToNamespace] has no notion of a builtin package: it capitalises
  // `kotlin.collections` into `Kotlin.Collections` and hangs it off the root namespace, naming a
  // namespace nothing declares (issue #127, CS0234), and it never reads the type's arguments
  // either. Callers gate first: a collection element goes through `forwardPublicCsharpType()`,
  // and anything else builtin is refused by name.
  check(!kotlinPackage.isKotlinBuiltinPackage()) {
    "Kotlin builtin $kotlinPackage.$nestedName reached the user-type C# speller; it would " +
        "render global::${context.rootNamespace}.Kotlin..., a namespace nothing declares. " +
        "Gate the call site on the type's own route first (ADR-123)."
  }
  val namespace: String = mapPackageToNamespace(
    kotlinPackage, context.rootPackage, context.rootNamespace,
  )
  return "global::$namespace.$nestedName"
}

/**
 * `kotlin`, `kotlinx` and everything under them: the packages [mapPackageToNamespace] must never
 * see, since a root namespace is only ever a *user* package's prefix.
 */
internal fun String.isKotlinBuiltinPackage(): Boolean =
  this == "kotlin" || this == "kotlinx" ||
      startsWith("kotlin.") || startsWith("kotlinx.")

/**
 * ADR-067: threads a nullable *element* (`StateFlow<T?>`) through to the C# type argument. Both
 * value types (`int?` → real `Nullable<int>`) and reference types (`string?`/`Cat?` — a compile-
 * time-only annotation) accept the trailing `?`; C# does not reject it on either kind.
 */
internal fun qualifiedElementCsType(
  type: KSType?,
  context: NugetContext,
  nullable: Boolean,
): String {
  val base: String = qualifiedElementCsType(type, context)
  return if (nullable) "$base?" else base
}

/**
 * Issue #111: how one type argument of a `KotlinFunc<...>` / `KotlinSuspendFunc<...>` / generic
 * return is spelled in C#, or why it cannot be spelled at all.
 *
 * The legacy lambda routes spelled every argument
 * `arg.type?.resolve()?.declaration?.simpleName?.asString() ?: "object"`, which drops both the
 * argument's namespace and its own type arguments, so `(CamId) -> Flow<Snapshot>` rendered
 * `KotlinFunc<CamId, Flow>`: two separate CS0246s in one line.
 *
 * Qualifying alone does not fix it. `Flow<Snapshot>` spelled `global::Ns.Kotlinx.Coroutines.Flow
 * .Flow` names a type nothing declares, which is the same CS0246 in a longer coat. So there are
 * two outcomes, never one: an argument C# can genuinely name is [Named] and fully qualified, and
 * one it cannot is [Unnameable] and the whole member is skipped by its caller with a diagnostic
 * naming the offending argument.
 */
internal sealed interface CsTypeArgument {
  /**
   * The C# spelling: a primitive (`int`), a type parameter in scope (`T`), or `global::Ns.Name`.
   */
  data class Named(val csType: String) : CsTypeArgument

  /** The qualified name of the argument C# has no spelling for, for the caller's diagnostic. */
  data class Unnameable(val typeArgument: String) : CsTypeArgument
}

/**
 * Issue #111: the one place a lambda's (or a generic return's) type argument is turned into C#.
 *
 * Admits, in order:
 *  - a known scalar, by its C# primitive name (`Int` -> `int`), and `Unit` as `void` (the suspend
 *    routes narrow a `void` result to `KotlinSuspendAction`);
 *  - a *type parameter* (`class Crate<T>`), kept bare: `T` is in scope at the declaration, not a
 *    type in a namespace, so `global::Ns.T` would be nonsense;
 *  - a class, object or enum that is both **exported and declared**, fully qualified.
 *
 * Everything else is [CsTypeArgument.Unnameable]: a type carrying its own type arguments
 * (`Flow<T>`, `List<T>`, a nested lambda, a generic class), and any type outside [exportedTypes]
 * (an unexported dependency type, a nested class, a stdlib type). The export set is the test on
 * purpose rather than "is it a class": [ForwardReachabilityClosure] does not walk lambda type
 * arguments (`LAMBDA_TYPES` is an intrinsic terminal, not a carrier), so a class reachable only
 * through one is never admitted, and qualifying it would emit a `global::` reference to a type
 * nothing declares.
 *
 * A *declared* enum is admitted here: since ADR-094's 2026-09-29 amendment every exported enum
 * registers a `NugetMarshal.Factories` entry, so `FromHandle` reads it by ordinal.
 */
internal fun csTypeArgument(
  type: KSType?,
  exportedTypes: Set<String>,
  context: NugetContext,
  classifier: ForwardBridgeTypeClassifier,
): CsTypeArgument {
  val resolved: KSType = type?.expandAliases()
    ?: return CsTypeArgument.Unnameable("an unresolved type argument")
  val declaration: KSDeclaration = resolved.declaration
  val simpleName: String = declaration.simpleName.asString()
  val qualifiedName: String = declaration.qualifiedName?.asString() ?: simpleName

  // Boundary nullability part A1: the type argument's own nullability, read the two-sided way
  // `ForwardBridgeTypeClassifier.classify` reads it (since the ADR-018 amendment `expandAliases()`
  // carries a `Name?` use site's `?` itself; the unexpanded OR is kept as belt-and-braces).
  // Without this, `(String?) -> Unit` and `(String) -> Unit` were spelled identically as
  // `KotlinAction<string>` with no diagnostic in between, and `(Int?) -> Unit` came out
  // `KotlinAction<int>`, where null is not expressible at all: a consumer could not write the call.
  // `void` is excluded because `void?` is not a C# type (a Unit-returning lambda drops the argument
  // entirely, issue #114).
  val suffix: String = if (type.isMarkedNullable || resolved.isMarkedNullable) "?" else ""

  KOTLIN_TO_CSHARP_PARAM[simpleName]?.let { return CsTypeArgument.Named("$it$suffix") }
  if (qualifiedName == "kotlin.Unit") return CsTypeArgument.Named("void")
  if (declaration is KSTypeParameter) return CsTypeArgument.Named("$simpleName$suffix")

  if (declaration !is KSClassDeclaration) return CsTypeArgument.Unnameable(qualifiedName)
  // ADR-199: a closed generic sealed instantiation is read through its `Factories` entry.
  if (resolved.arguments.isNotEmpty()) {
    return classifier.legacyGenericSealedElement(resolved, nullable = suffix.isNotEmpty())
      ?.let { spelled -> CsTypeArgument.Named(spelled) }
      ?: CsTypeArgument.Unnameable(qualifiedName)
  }
  if (qualifiedName !in exportedTypes) return CsTypeArgument.Unnameable(qualifiedName)

  // ADR-173: an exported interface is spelled as the projected interface (`IPet`), never its
  // ADR-040 backing wrapper (`Pet`), which a consumer's own implementation can never be. The same
  // classifier the Flow element route spells with, so there is one interface spelling; it returns
  // only `BridgeType.Interface`, so sealed and `fun` interfaces keep their own projections.
  classifier.legacyFlowElementInterface(resolved)?.let { iface ->
    return CsTypeArgument.Named("${iface.csharpType}$suffix")
  }

  return CsTypeArgument.Named("${qualifiedElementCsType(resolved, context)}$suffix")
}

/**
 * Issue #111: [csTypeArgument] over a whole argument list, so a caller can decide once between
 * "spell the member" and "skip it named". The first unnameable argument wins: a member with two
 * of them is skipped for the first, and the author fixes them one at a time either way.
 */
internal fun csTypeArguments(
  arguments: List<KSTypeArgument>,
  exportedTypes: Set<String>,
  context: NugetContext,
  classifier: ForwardBridgeTypeClassifier,
): CsTypeArgument.Unnameable? = arguments
  .asSequence()
  .map { argument -> csTypeArgument(argument.type?.resolve(), exportedTypes, context, classifier) }
  .filterIsInstance<CsTypeArgument.Unnameable>()
  .firstOrNull()

/**
 * Issue #111: the C# spellings, valid only when [csTypeArguments] found nothing unnameable. Every
 * caller gates on [csTypeArguments] first and skips the member named, so an unnameable argument
 * here is a caller that forgot the gate: failing loudly beats rendering a bare simple name for the
 * consumer's compiler to reject (CS0246).
 */
internal fun csTypeArgumentNames(
  arguments: List<KSTypeArgument>,
  exportedTypes: Set<String>,
  context: NugetContext,
  classifier: ForwardBridgeTypeClassifier,
): List<String> = arguments.map { argument ->
  val resolved: KSType? = argument.type?.resolve()
  when (val spelling = csTypeArgument(resolved, exportedTypes, context, classifier)) {
    is CsTypeArgument.Named -> spelling.csType
    is CsTypeArgument.Unnameable -> error(
      "Forward CIR spelled the type argument '${spelling.typeArgument}', which has no C# " +
          "spelling, without gating on csTypeArguments first (issue #111)",
    )
  }
}

/**
 * Issue #114: the C# spelling of a lambda whose type arguments are already resolved, narrowing a
 * Unit return to `KotlinAction` instead of spelling it `void`.
 *
 * `void` is a legal C# return type and never a legal type argument, so `() -> Unit` rendered
 * `KotlinFunc<void>` is `CS1547: Keyword 'void' cannot be used in this context`, twice per
 * property (once for the declared type, once for the `new`). The suspend arm has always narrowed
 * this way; this is its plain-lambda twin.
 *
 * The Unit return is *dropped* rather than replaced, so the arity is carried by the parameters
 * alone: `() -> Unit` is the non-generic `KotlinAction` and `(Int) -> Unit` is
 * `KotlinAction<int>`, the same shape as `KotlinSuspendAction` and the shape `renderFuncHelper`
 * declares.
 *
 * [typeArgs] is a lambda's full argument list, return type last, as [csTypeArgumentNames] spells
 * it. Only `kotlin.Unit` maps to `"void"` there, so the string test is exact.
 *
 * Shared rather than inlined because the three routes that spell a lambda (ordinary class
 * property, sealed subclass property, top-level function return) were three hand-written copies
 * of one expression, and issue #111 had already been fixed in each of them separately.
 */
internal fun csLambdaType(typeArgs: List<String>): String {
  if (typeArgs.lastOrNull() != "void") return "KotlinFunc<${typeArgs.joinToString(", ")}>"
  val parameters: List<String> = typeArgs.dropLast(1)
  if (parameters.isEmpty()) return "KotlinAction"
  return "KotlinAction<${parameters.joinToString(", ")}>"
}

/**
 * Issue #111: the one diagnostic every lambda route raises for a type argument with no C#
 * spelling, so a property arm and a return arm say the same thing under different kinds.
 *
 * It names the offending argument rather than the member's whole type: `(CamId) -> Flow<Snapshot>`
 * is skipped because of `Flow`, and an author told only "this property was skipped" has three
 * candidates to guess between.
 */
internal fun lambdaTypeArgumentDiagnostic(
  kind: ForwardDiagnosticKind,
  symbol: KSNode?,
  declaration: String,
  typeArgument: String,
  // Issue #249: this route emits no member at all, so the member really is absent from the
  // generated C# and its owner has a hole worth naming.
  owner: ForwardDiagnosticOwner?,
  member: String?,
): ForwardDiagnostic = ForwardDiagnostic(
  kind = kind,
  symbol = symbol,
  declaration = declaration,
  owner = owner,
  member = member,
  reason = "its lambda type argument `$typeArgument` has no C# spelling: a lambda argument must " +
      "be a primitive, String, or an exported class, object or enum that is declared in C#, and " +
      "a type carrying its own type arguments (Flow<T>, a collection, another lambda, a generic " +
      "class) has no spelling on this route at all",
  hint = "expose a lambda over bridgeable types instead: replace `$typeArgument` with a " +
      "primitive, a String, or a top-level exported class in the export scope (a Flow or a " +
      "generic type argument needs its own bridgeable wrapper type)",
)
