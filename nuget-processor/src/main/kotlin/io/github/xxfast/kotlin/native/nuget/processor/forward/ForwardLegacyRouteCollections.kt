package io.github.xxfast.kotlin.native.nuget.processor.forward

import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.Visibility
import io.github.xxfast.kotlin.native.nuget.processor.exports.isCompilerOwnedMember
import io.github.xxfast.kotlin.native.nuget.processor.freshName
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
  data class Handle(
    val type: BridgeType.ObjectHandle,
    /**
     * Issue #365: a nullable class or sealed handle, still one pointer slot. `null` crosses as
     * `IntPtr.Zero` (`x?._handle ?? IntPtr.Zero` in C#) and Kotlin receives `null`.
     */
    val nullable: Boolean = false,
  ) : ForwardLegacyParameterShape

  /**
   * Issue #299 (ADR-122 amendment): a nullable primitive, `Char` or `String`, crossing on the plan
   * route's own wire. [type] is the non-null inner type. A primitive or `Char` fans out to a
   * `${name}HasValue` BOOLEAN slot followed by the inner value slot (ADR-098 amendment, ADR-164
   * Context); a `String` stays one nullable pointer slot.
   */
  data class NullableScalar(
    val type: BridgeType,
    /**
     * ADR-164 on the legacy routes: a defaulted NON-null parameter crossing on this nullable wire,
     * where null means "unset, let Kotlin run the default". The member still declares the non-null
     * type, so the set arm passes the raw value (`limit`, `x!!`), never `if (h) limit else null`.
     */
    val widened: Boolean = false,
    /**
     * ADR-164 rule 2 on the legacy routes: a defaulted parameter that is ALREADY nullable, public
     * `Optional<T?>` over a leading `${name}IsSet` BOOLEAN slot before its own nullable wire.
     */
    val optional: Boolean = false,
  ) : ForwardLegacyParameterShape {
    /** Whether this parameter takes the has-value slot pair rather than one nullable slot. */
    val fansOut: Boolean
      get() = type !is BridgeType.String
  }

  /**
   * An exported enum, crossing by ordinal: `(int)x` in C#, `Q.entries[x]` in Kotlin, the plan
   * route's own encoding (ADR-080). Its nullable form is a [NullableScalar] over this type.
   */
  data class Enum(val type: BridgeType.Enum) : ForwardLegacyParameterShape

  /**
   * Any other parameter, named so the skip diagnostic can quote it.
   *
   * [refusal] is the classifier's own [BridgeType.Unsupported] when the parameter is an
   * out-of-scope dependency type (ROADMAP Phase 4 line 23), so the skip names ADR-154's
   * `admit(...)` remedy instead of the route's generic "pass a class" wording.
   */
  data class Refused(
    val description: String,
    val refusal: BridgeType.Unsupported? = null,
  ) : ForwardLegacyParameterShape
}

/** The out-of-scope dependency refusal [classified] carries, nullable unwrapped, or null. */
internal fun legacyDependencyRefusal(classified: BridgeType): BridgeType.Unsupported? =
  (classified.unwrapNullable() as? BridgeType.Unsupported)?.takeIf { it.isUnexportedDependency }

/**
 * ADR-171: whether a value class has the `NugetBox`/`NugetUnbox` pair generated for it. The
 * planner builds that pair only for these four underlying kinds, and the suspend route's
 * [ForwardLegacyReturnShape.ValueClass] arm reads through `NugetUnbox`, so both read this one rule.
 */
internal fun BridgeType.ValueClass.hasErasedCrossing(): Boolean =
  underlying == BridgeType.String || underlying is BridgeType.Primitive ||
      underlying is BridgeType.Enum || underlying is BridgeType.ObjectHandle

/**
 * Classifies one legacy-route parameter.
 *
 * ADR-114 classified only *generic* types here and returned [ForwardLegacyParameterShape.Plain]
 * for everything else unread, which meant `mapParamType(simpleName)` handed C# an `IntPtr` for a
 * class, an `object`, a sealed type, an enum, an `Instant`/`Duration`/`Uuid`, a value class and an
 * interface alike (issue #126). ADR-122 classifies the non-generic case too, so the outcomes are
 * marshal it, borrow its handle, cross an enum by ordinal (ADR-164), or refuse it by name. None of
 * them is a public `IntPtr`.
 *
 * A nullable class or sealed handle is a nullable [ForwardLegacyParameterShape.Handle] (issue
 * #365). Nullable collections (`List<T>?`) land in [ForwardLegacyParameterShape.Refused] on
 * purpose: threading nullability through these routes is ADR-067 territory and ADR-114 defers it. A nullable *scalar* is
 * [ForwardLegacyParameterShape.NullableScalar] (issue #299): it used to stay `Plain`, which bound
 * `Int?` as a non-null `int` on both halves, so a C# caller could not pass `null`.
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
    classified is BridgeType.Nullable && classified.type is BridgeType.ObjectHandle ->
      ForwardLegacyParameterShape.Handle(classified.type, nullable = true)
    classified is BridgeType.Enum -> ForwardLegacyParameterShape.Enum(classified)
    classified is BridgeType.Nullable &&
        (classified.type.isLegacyScalar() || classified.type is BridgeType.Enum) ->
      ForwardLegacyParameterShape.NullableScalar(classified.type)

    else -> ForwardLegacyParameterShape.Refused(
      expanded.legacyDescription(),
      legacyDependencyRefusal(classified),
    )
  }

  // Deliberately the un-rewritten classification on the parameter side: a `List<Shape>` of a
  // sealed base stays refused here, as ADR-114/ADR-119 decided (the return side now rewrites),
  // rather than being widened by the rewrite above.
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

/**
 * The Kotlin function types the per-call callback route keys on. Its own copy of the list rather
 * than an import of `LAMBDA_TYPES`, so a member of this file needs nothing from `cir/`.
 */
private val LEGACY_CALLBACK_TYPES: Set<String> = setOf(
  "kotlin.Function0", "kotlin.Function1", "kotlin.Function2", "kotlin.Function3",
)

/** The three outer returns the hand-written per-call route can actually marshal. */
private val LEGACY_CALLBACK_RETURNS: Set<String> = setOf(
  "kotlin.Unit", "kotlin.String", "kotlin.collections.List", "kotlin.collections.MutableList",
)

/**
 * ADR-160 step 4: why the **hand-written** per-call lambda-parameter route cannot carry this
 * member, or null when it can. Read by every selector of that route, on both halves, so a member it
 * cannot carry is a NAMED SKIP instead of the two failure modes it shipped with:
 *
 *  - a non-`Unit`, non-`String`, non-`List` outer return made the C# half declare the extern as
 *    `IntPtr` while the Kotlin half returned the scalar, which the ADR-055 contract turned into
 *    `Forward ABI mismatch for <export>; ... -> pointer, actual ... -> int` and failed the whole
 *    build (ROADMAP line 75's symptom),
 *  - a non-lambda parameter (or a second lambda) was silently dropped by BOTH halves, and the
 *    generated Kotlin then failed to compile with `No value passed for parameter 'x'`.
 *
 * Members whose lambda the ADR-062 plan owns never reach here: the plan carries every outer return
 * its own result matrix carries, mixed parameters included. What is left on this route is the
 * payload shapes the plan's callback lowering declines (`Char`, a sealed base, a value class, a
 * suspend lambda), and those keep working at the returns listed above. A lambda RESULT outside
 * `Unit`/primitive/`String` and a Kotlin-builtin non-scalar payload never reach here: both are
 * refused upstream, before the partition, by `refusedLegacyLambdaShape`.
 */
internal fun legacyRefusedCallbackMember(method: KSFunctionDeclaration): String? {
  val lambdas: List<KSValueParameter> = method.parameters.filter { parameter ->
    parameter.type.resolve().expandAliases().declaration.qualifiedName?.asString() in
        LEGACY_CALLBACK_TYPES
  }
  if (lambdas.size > 1) {
    return "it declares ${lambdas.size} lambda parameters, and this route carries exactly one"
  }
  val other: KSValueParameter? =
    method.parameters.firstOrNull { parameter -> parameter !in lambdas }
  if (other != null) {
    return "it declares the non-lambda parameter `${other.name?.asString() ?: "_"}` beside its " +
        "lambda, which this route drops from both halves"
  }
  val returnType: KSType? = method.returnType?.resolve()?.expandAliases()
  val returnName: String = returnType?.declaration?.qualifiedName?.asString() ?: "kotlin.Unit"
  val nullable: Boolean = method.returnType?.resolve()?.isMarkedNullable == true ||
      returnType?.isMarkedNullable == true
  if (nullable) {
    return "its return type `$returnName?` is nullable, which this route spells non-null on both " +
        "halves"
  }
  if (returnName !in LEGACY_CALLBACK_RETURNS) {
    return "its return type `$returnName` is not one this route can marshal (it carries `Unit`, " +
        "`String` and `List`)"
  }
  return null
}

/**
 * [legacyParameterShape] for every parameter of a member, in declaration order, with ADR-164's
 * widening applied to the defaulted ones ([defaults], positionally, from
 * [ForwardCallablePlanCatalog.legacyDefaultFlags]):
 *
 *  - a defaulted non-null scalar or enum crosses on its nullable wire, null meaning unset
 *    ([ForwardLegacyParameterShape.NullableScalar.widened]);
 *  - a defaulted nullable one gains the `IsSet` slot
 *    ([ForwardLegacyParameterShape.NullableScalar.optional]);
 *  - a defaulted handle or collection stays required, unchanged (these routes have no nullable
 *    encoding for either, ADR-114's deferral).
 *
 * At most [MAX_OPTIONAL_DEFAULTS] widen, the last ones in declaration order (ADR-164 rule 6).
 * Both halves call this one function, so they cannot disagree on a slot.
 */
internal fun ForwardBridgeTypeClassifier.legacyParameterShapes(
  parameters: List<KSValueParameter>,
  defaults: List<Boolean> = emptyList(),
): List<ForwardLegacyParameterShape> {
  val shapes: List<ForwardLegacyParameterShape> =
    parameters.map { legacyParameterShape(it.type.resolve()) }
  val widened: Set<Int> =
    legacyWidenedIndices(shapes, defaults).takeLast(MAX_OPTIONAL_DEFAULTS).toSet()
  return shapes.mapIndexed { index, shape ->
    if (index !in widened) return@mapIndexed shape
    when (shape) {
      ForwardLegacyParameterShape.Plain -> ForwardLegacyParameterShape.NullableScalar(
        classify(parameters[index].type.resolve()), widened = true,
      )
      is ForwardLegacyParameterShape.Enum ->
        ForwardLegacyParameterShape.NullableScalar(shape.type, widened = true)
      is ForwardLegacyParameterShape.NullableScalar -> shape.copy(optional = true)
      else -> shape
    }
  }
}

/**
 * ADR-164 rule 6 on the legacy routes: the defaulted parameters the cap leaves required, by name,
 * for the `WARNING_DEFAULT_PARAMETER_CAP_EXCEEDED` diagnostic.
 */
internal fun ForwardBridgeTypeClassifier.legacyCappedDefaults(
  parameters: List<KSValueParameter>,
  defaults: List<Boolean>,
): List<String> {
  val shapes: List<ForwardLegacyParameterShape> =
    parameters.map { legacyParameterShape(it.type.resolve()) }
  return legacyWidenedIndices(shapes, defaults).dropLast(MAX_OPTIONAL_DEFAULTS)
    .map { index -> parameters[index].name?.asString() ?: "_" }
}

/** The defaulted parameters a nullable encoding exists for, before the cap. */
private fun legacyWidenedIndices(
  shapes: List<ForwardLegacyParameterShape>,
  defaults: List<Boolean>,
): List<Int> = shapes.indices.filter { index ->
  defaults.getOrElse(index) { false } && when (shapes[index]) {
    ForwardLegacyParameterShape.Plain, is ForwardLegacyParameterShape.Enum,
    is ForwardLegacyParameterShape.NullableScalar -> true
    is ForwardLegacyParameterShape.Handle, is ForwardLegacyParameterShape.Marshalled,
    is ForwardLegacyParameterShape.Refused -> false
  }
}

/**
 * ADR-164: the defaulted parameters of this function that must stay REQUIRED because omitting them
 * would leave a real sibling overload's exact parameter list. Kotlin resolves `Foo(name)` to the
 * real `Foo(name)` beside `Foo(name, lives = 9)` (the candidate using no defaults wins), so the
 * dispatcher's "unset" arm could never reach `lives`'s default: it would silently call the
 * sibling. Keeping `lives` required means C# `Foo(name)` binds the sibling too, which is what a
 * Kotlin caller writing `Foo(name)` gets.
 *
 * A sibling of arity `m` shadows index `m` when its parameter types equal this function's first
 * `m`, and every parameter from `m` on is defaulted (a required one there is always passed, so the
 * call never shrinks to the sibling's). [flags] is this function's per-parameter default reading.
 */
internal fun KSFunctionDeclaration.shadowedDefaultIndices(
  flags: List<Boolean>,
  siblings: Sequence<KSFunctionDeclaration>,
): Set<Int> {
  val own: List<String> = parameters.map { parameter -> parameter.type.resolve().overloadKey() }
  return siblings
    .filter { sibling -> sibling !== this && sibling.parameters.size < parameters.size }
    .filter { sibling -> sibling.parameters.none { parameter -> parameter.isVararg } }
    .mapNotNull { sibling ->
      val arity: Int = sibling.parameters.size
      val sameTypes: Boolean = sibling.parameters.indices.all { index ->
        sibling.parameters[index].type.resolve().overloadKey() == own[index]
      }
      val omittable: Boolean =
        (arity until parameters.size).all { index -> flags.getOrElse(index) { false } }
      arity.takeIf { sameTypes && omittable }
    }
    .toSet()
}

/** A structural spelling of a type for overload comparison: qualified, aliases expanded. */
internal fun KSType.overloadKey(): String {
  val expanded: KSType = expandAliases()
  val name: String = expanded.declaration.qualifiedName?.asString()
    ?: expanded.declaration.simpleName.asString()
  val arguments: String =
    if (expanded.arguments.isEmpty()) ""
    else expanded.arguments.joinToString(",", "<", ">") { argument ->
      argument.type?.resolve()?.overloadKey() ?: "*"
    }
  return "$name$arguments${if (expanded.isMarkedNullable) "?" else ""}"
}

/** Whether this parameter is dispatched by the ADR-164 `when (mask)`: set or unset per call. */
internal val ForwardLegacyParameterShape.isLegacyDefaulted: Boolean
  get() = this is ForwardLegacyParameterShape.NullableScalar && (widened || optional)

/** The first refused parameter of a member, as `name: Type`, or null when every one binds. */
internal fun ForwardBridgeTypeClassifier.legacyRefusedParameter(
  parameters: List<KSValueParameter>,
): String? = legacyRefusedParameterShape(parameters)?.let { (name, shape) ->
  "$name: ${shape.description}"
}

/** [legacyRefusedParameter] with the refused shape itself, for the diagnostic walk. */
internal fun ForwardBridgeTypeClassifier.legacyRefusedParameterShape(
  parameters: List<KSValueParameter>,
): Pair<String, ForwardLegacyParameterShape.Refused>? =
  parameters.firstNotNullOfOrNull { parameter ->
    val shape = legacyParameterShape(parameter.type.resolve())
    if (shape is ForwardLegacyParameterShape.Refused) {
      (parameter.name?.asString() ?: "_") to shape
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

  /**
   * A collection the ordinary route's `List`/`Set`/`Map` return already reads back. [nullable] is
   * a `List<T>?` return: the Kotlin half already sends `null` as a null result pointer (issue
   * #108), so only the C# declared type and a guarded read differ (ADR-119 amendment).
   */
  data class Marshalled(
    val type: BridgeType.Collection,
    val nullable: Boolean = false,
  ) : ForwardLegacyReturnShape

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

  /**
   * ADR-040 at a suspend return: an interface that is in the exported set. The wire is unchanged
   * -- the Kotlin half already pins a `StableRef` on whatever object the body produced -- but the
   * C# half has to DECLARE the position with the projected interface (`Task<Aviary.IKeeper>`)
   * while CONSTRUCTING the ADR-040 backing wrapper (`new Aviary.Keeper(resultPtr)`) to read the
   * handle back. The shipped route spelled both with `nestedCsName()`, which for an interface is
   * the wrapper: the one type ADR-040 says a consumer never sees at a declared position.
   *
   * [nullable] carries the `?` from the declaration, so the completion guards a null result
   * pointer before constructing, exactly as the shipped nullable-object arm does.
   */
  data class Interface(
    val type: BridgeType.Interface,
    val nullable: Boolean,
  ) : ForwardLegacyReturnShape

  /**
   * ROADMAP Phase 4 (ADR-151 amendment): a bare `ByteArray` at a suspend return.
   *
   * It carries no type arguments, so it used to reach [Plain] and the route spelled the awaited
   * result with `nestedCsName()` -- `Task<ByteArray>`, against a C# type nothing declares (CS0246
   * in every consumer, ADR-123 / issue #127's shape one position over). The WIRE was already
   * right: the Kotlin half boxes the result with `NugetHandles.retain(result)`, and for a
   * `ByteArray` that StableRef *is* the handle `NugetMarshal.ReadBytes` expects, the same handle
   * `nuget_bytes_create` mints. So only the C# spelling and the read had to change, and no new
   * export exists for this.
   *
   * [nullable] carries the `?` from the declaration, so the completion guards a null result
   * pointer before reading, exactly as the shipped nullable-object arm does.
   */
  data class Bytes(val nullable: Boolean) : ForwardLegacyReturnShape

  /**
   * ROADMAP Phase 4 line 23: an exported class or object handle (not a sealed base, which is
   * [Discriminated]). It used to be [Plain], spelled with the bare `nestedCsName()`, which names
   * nothing once the type lives in another namespace (an admitted dependency type does, as
   * `TestLibrary.Dev.Other.Bysuspend.Mousetoy` inside `namespace TestLibrary.Errand`). The
   * classifier's [BridgeType.ObjectHandle.csharpType] is `global::`-qualified, the spelling every
   * plan-route position and the Flow route already use.
   */
  data class Handle(
    val handle: BridgeType.ObjectHandle,
    val nullable: Boolean,
  ) : ForwardLegacyReturnShape

  /**
   * ROADMAP Phase 4 line 23: a value class with a `NugetBox`/`NugetUnbox` pair (ADR-171). The
   * Kotlin half already pins the boxed value (`NugetHandles.retain(result)`), which is exactly the
   * handle `NugetUnbox` reads and disposes, so only the C# completion changes: it used to be
   * `new T(resultPtr, out _)`, which a `readonly record struct` has no constructor for (CS1729).
   */
  data class ValueClass(
    val type: BridgeType.ValueClass,
    val nullable: Boolean,
  ) : ForwardLegacyReturnShape

  /**
   * ROADMAP Phase 4 line 23 fold-in: an exported enum. It used to be [Plain], which spelled it
   * bare (`Task<Mood>`, unresolvable from another namespace) and completed it with
   * `new Mood(resultPtr, out _)`, which no C# enum has. It now crosses by ordinal, the encoding
   * every other route uses (ADR-080): the Kotlin half boxes `result.ordinal` as an `Int`, and C#
   * reads it back with `NugetMarshal.FromHandle<int>` (which disposes the box) and casts to the
   * `global::`-qualified enum, so the awaited value is the enum, never a bare `int`.
   */
  data class Enum(
    val type: BridgeType.Enum,
    val nullable: Boolean,
  ) : ForwardLegacyReturnShape

  /**
   * Any other generic return, named so the skip diagnostic can quote it. [refusal] carries the
   * out-of-scope dependency type that caused it, so the skip names ADR-154's `admit(...)`.
   */
  data class Refused(
    val description: String,
    val refusal: BridgeType.Unsupported? = null,
  ) : ForwardLegacyReturnShape
}

/** ROADMAP Phase 4 line 23: the `global::`-qualified C# type a handle or value-class async return
 *  is DECLARED with, `?`-suffixed when nullable. */
internal fun ForwardLegacyReturnShape.Handle.declaredCsharpType(): String =
  if (nullable) "${handle.csharpType}?" else handle.csharpType

internal fun ForwardLegacyReturnShape.ValueClass.declaredCsharpType(): String =
  if (nullable) "${type.csharpType}?" else type.csharpType

internal fun ForwardLegacyReturnShape.Enum.declaredCsharpType(): String =
  if (nullable) "${type.csharpType}?" else type.csharpType

/** The boxed ordinal read back and cast; a null result pointer is `null`, never ordinal 0. */
internal fun ForwardLegacyReturnShape.Enum.legacyEnumRead(handle: String): String {
  val cast: String = "(${type.csharpType})NugetMarshal.FromHandle<int>($handle)"
  return if (nullable) "$handle == IntPtr.Zero ? (${type.csharpType}?)null : $cast" else cast
}

/** ...and the completion's read: the handle constructor, null-guarded when nullable. */
internal fun ForwardLegacyReturnShape.Handle.legacyHandleRead(handle: String): String =
  if (nullable) "$handle == IntPtr.Zero ? null : new ${this.handle.csharpType}($handle, out _)"
  else "new ${this.handle.csharpType}($handle, out _)"

/** `NugetUnbox` reads the box and disposes it; a null result pointer is `null`, never unboxed. */
internal fun ForwardLegacyReturnShape.ValueClass.legacyValueClassRead(handle: String): String =
  if (nullable) {
    "$handle == IntPtr.Zero ? (${type.csharpType}?)null : ${type.csharpType}.NugetUnbox($handle)"
  } else {
    "${type.csharpType}.NugetUnbox($handle)"
  }

/**
 * ROADMAP Phase 4: the C# type a bare-`ByteArray` async position is DECLARED with, and the
 * expression that reads its handle back. Shared by the suspend routes and the flow routes so the
 * four call sites cannot drift.
 */
internal fun legacyBytesCsharpType(nullable: Boolean): String =
  if (nullable) "byte[]?" else "byte[]"

/**
 * `NugetMarshal.ReadBytes(h)`, which copies the Kotlin array out and disposes the handle in its own
 * `finally`. A nullable position is guarded on the wire pointer first: the Kotlin half sends `null`
 * as a null result pointer, and `ReadBytes(IntPtr.Zero)` would call `nuget_bytes_count` on it.
 */
internal fun legacyBytesRead(handle: String, nullable: Boolean): String =
  if (nullable) "$handle == IntPtr.Zero ? null : NugetMarshal.ReadBytes($handle)"
  else "NugetMarshal.ReadBytes($handle)"

/** [legacyBytesRead] in the ADR-123 `read:` slot the flow routes hand their enumerator. */
internal fun legacyBytesElementReadArgument(nullable: Boolean): String =
  "read: static h => ${legacyBytesRead("h", nullable)}"

/**
 * Classifies one suspend member's return. Only a *generic* return is classified at all, so every
 * scalar, string, enum and object return renders exactly as it does today.
 *
 * The admission is the ordinary route's own return-position rule (`isBridgeableComponent`), so
 * the two routes agree on which element types cross. A nullable collection (`List<T>?`) is
 * [ForwardLegacyReturnShape.Marshalled] with `nullable = true`; the parameter side's ADR-114
 * deferral in [legacyParameterShape] is independent (the two positions share no wire).
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

  // ADR-040: decided before the non-generic early return below, for ADR-131's reason one type
  // shape over -- an interface carries no type arguments either, so it was `Plain`, and `Plain`
  // means "spell the return with `nestedCsName()`", which for an interface is the backing wrapper.
  // Gated on the classification rather than on `classKind == INTERFACE`: only an interface in the
  // exported set has a projected `I...` spelling to declare at all, and one outside it keeps the
  // shipped spelling (its member is refused upstream for its own reason).
  val classified: BridgeType = classify(expanded).let {
    if (it is BridgeType.Nullable) it.type else it
  }
  if (classified is BridgeType.Interface) {
    return ForwardLegacyReturnShape.Interface(classified, expanded.isMarkedNullable)
  }

  // ROADMAP Phase 4 / ADR-151: a bare `ByteArray` carries no type arguments, so it reached the
  // `Plain` early return below and the route spelled the awaited result `nestedCsName()` --
  // `public Task<ByteArray> SnapshotAsync(...)` against a C# type nothing declares (measured
  // 2026-09-20). Decided here, ahead of that fall-through.
  if (classified == BridgeType.ByteArray) {
    return ForwardLegacyReturnShape.Bytes(expanded.isMarkedNullable)
  }

  // ROADMAP Phase 4 line 23: `Plain` means "spell it", so a type the classifier refused (an
  // out-of-scope dependency type above all) was spelled as a C# type nothing declares. Refused
  // by name instead, carrying the dependency refusal for the `admit(...)` hint.
  if (classified is BridgeType.Unsupported) {
    return ForwardLegacyReturnShape.Refused(
      expanded.legacyDescription(),
      legacyDependencyRefusal(classified),
    )
  }
  // ...and a value class completes by `NugetUnbox`, never a handle constructor. The
  // `arguments.isEmpty()` guard keeps `kotlin.Result<T>` on the generic refusal below.
  if (classified is BridgeType.ValueClass && expanded.arguments.isEmpty()) {
    return if (classified.hasErasedCrossing()) {
      ForwardLegacyReturnShape.ValueClass(classified, expanded.isMarkedNullable)
    } else {
      ForwardLegacyReturnShape.Refused(expanded.legacyDescription())
    }
  }
  if (classified is BridgeType.ObjectHandle && !classified.viaDiscriminator &&
    expanded.arguments.isEmpty()
  ) {
    return ForwardLegacyReturnShape.Handle(classified, expanded.isMarkedNullable)
  }
  if (classified is BridgeType.Enum) {
    return ForwardLegacyReturnShape.Enum(classified, expanded.isMarkedNullable)
  }

  if (expanded.arguments.isEmpty()) return ForwardLegacyReturnShape.Plain

  // ADR-194: an acquired plain Flow gets a typed per-member collector.
  if (expanded.declaration.qualifiedName?.asString() in FLOW_TYPES) {
    if (expanded.isMarkedNullable) {
      return ForwardLegacyReturnShape.Refused(expanded.legacyDescription())
    }
    val element = expanded.arguments.firstOrNull()?.type?.resolve()?.expandAliases()
    val shape = legacyFlowElementShape(element)
    return if (shape is ForwardLegacyFlowElementShape.Refused) {
      ForwardLegacyReturnShape.Refused(shape.description, shape.refusal)
    } else ForwardLegacyReturnShape.Plain
  }

  // ADR-068 peels a StateFlow return into its own bucket before the plain-async path sees it.
  // ADR-123: that bucket reads every element through the module-wide `nuget_stateflow_value`
  // export, which has no per-member projection seam, so a collection element cannot cross there
  // even though the ADR-065 property and method routes now bind one. Refused, not half-bound.
  if (expanded.declaration.qualifiedName?.asString() in STATE_FLOW_TYPES) {
    val element: KSType? = expanded.arguments.firstOrNull()?.type?.resolve()?.expandAliases()
    // 2026-09-20: a NULLABLE element is refused here for the same reason a collection element is,
    // and more sharply. This bucket reads through the module-wide `nuget_stateflow_value`, which
    // is `NugetHandles.retain(flow.value as Any)` with no null arm and no `try` around it: a null
    // `.Value` would throw *out of a `@CName` export*, which aborts the process rather than
    // faulting a channel. The ADR-065 property/method routes now thread a nullable element
    // (ADR-067's encoding, per-member exports they can widen to `COpaquePointer?`); this one
    // cannot without widening a shared runtime export's return type, so it skips NAMED instead of
    // binding a `KotlinStateFlow<T>` that dies on the first absent value.
    if (element?.isMarkedNullable == true) {
      return ForwardLegacyReturnShape.Refused(expanded.legacyDescription())
    }
    return if (legacyFlowElementShape(element) is ForwardLegacyFlowElementShape.Plain) {
      ForwardLegacyReturnShape.Plain
    } else {
      ForwardLegacyReturnShape.Refused(expanded.legacyDescription())
    }
  }

  // ADR-119 amendment: ADR-105's rewrite first, so a `List<Shape>` of an eligible sealed base
  // carries `ObjectHandle(viaDiscriminator)` elements the admission below accepts, exactly as the
  // plan routes classify it; then the nullable wrapper comes off, carried as `nullable`.
  val rewritten: BridgeType = classify(type).sealedAsHandle()
  val collection: BridgeType.Collection? =
    (if (rewritten is BridgeType.Nullable) rewritten.type else rewritten) as? BridgeType.Collection
  return if (collection != null && collection.isBridgeableComponent()) {
    ForwardLegacyReturnShape.Marshalled(collection, nullable = expanded.isMarkedNullable)
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
internal fun ForwardBridgeTypeClassifier.legacyRefusedReturn(func: KSFunctionDeclaration): String? =
  legacyRefusedReturnShape(func)?.first

/**
 * [legacyRefusedReturn] with the dependency refusal behind it, when there is one, for the
 * diagnostic walk's `admit(...)` wording.
 */
internal fun ForwardBridgeTypeClassifier.legacyRefusedReturnShape(
  func: KSFunctionDeclaration,
): Pair<String, BridgeType.Unsupported?>? {
  val returnType: KSType? = func.returnType?.resolve()
  if (!func.modifiers.contains(Modifier.SUSPEND)) return legacyRefusedFlowElementShape(returnType)
  val shape: ForwardLegacyReturnShape = legacyReturnShape(returnType)
  return if (shape is ForwardLegacyReturnShape.Refused) shape.description to shape.refusal else null
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

  /**
   * ROADMAP Phase 4 (ADR-151 amendment): a bare `ByteArray` element, the return shape's twin.
   *
   * This one did not merely lie, it HARD-CRASHED the processor: `qualifiedElementCsType` runs a
   * Kotlin builtin through the *user-type* namespace mapping, and ADR-123's guard there turns that
   * into an `IllegalStateException` that takes `packNuget` down with it. The wire needed nothing:
   * `FlowExports` already boxes each emission with `NugetHandles.retain(value as Any)`, which for
   * a `ByteArray` is exactly the handle `ReadBytes` consumes. It rides ADR-123's per-member `read:`
   * seam, the same one a collection element uses.
   */
  data class Bytes(val nullable: Boolean) : ForwardLegacyFlowElementShape

  /** Any other generic element, named so the skip diagnostic can quote it. */
  data class Refused(
    val description: String,
    // ROADMAP Phase 4 line 23: the out-of-scope dependency type behind the refusal, if any.
    val refusal: BridgeType.Unsupported? = null,
  ) : ForwardLegacyFlowElementShape
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

  // ROADMAP Phase 4 / ADR-151: a bare `ByteArray` element has no type arguments, so it reached the
  // `Plain` early return and both halves spelled it with `qualifiedElementCsType` -- the user-type
  // speller, which for a Kotlin builtin HARD-CRASHES the processor and therefore `packNuget`
  // ("Kotlin builtin kotlin.ByteArray reached the user-type C# speller", ADR-123). Decided here,
  // ahead of that fall-through.
  val classified: BridgeType = classify(expanded).let {
    if (it is BridgeType.Nullable) it.type else it
  }
  if (classified == BridgeType.ByteArray) {
    return ForwardLegacyFlowElementShape.Bytes(expanded.isMarkedNullable)
  }

  // ROADMAP Phase 4 line 23: an element the classifier refused (an out-of-scope dependency type
  // above all) used to be `Plain`, spelled by `qualifiedElementCsType` as a type nothing declares.
  if (classified is BridgeType.Unsupported) {
    return ForwardLegacyFlowElementShape.Refused(
      expanded.legacyDescription(),
      legacyDependencyRefusal(classified),
    )
  }

  if (expanded.arguments.isEmpty()) return ForwardLegacyFlowElementShape.Plain

  // ADR-119 amendment: the suspend return's ADR-105 rewrite, so `Flow<List<Shape>>` of an eligible
  // sealed base admits its `ObjectHandle(viaDiscriminator)` elements. A nullable collection element
  // is still `Nullable`, not `Collection`, so it stays refused (the `StateFlow<T?>` problem).
  val collection: BridgeType.Collection? =
    classify(type).sealedAsHandle() as? BridgeType.Collection
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
  return expanded.arguments.firstOrNull()?.type?.resolve()?.expandAliases()
}

/** The refused element of a `Flow`/`StateFlow` member, or null when it binds (or is not one). */
internal fun ForwardBridgeTypeClassifier.legacyRefusedFlowElement(type: KSType?): String? =
  legacyRefusedFlowElementShape(type)?.first

/**
 * [legacyRefusedFlowElement] with the out-of-scope dependency type behind the refusal, when there
 * is one, so a Flow method or property can name ADR-154's `admit(...)` remedy (ROADMAP Phase 4
 * line 23).
 */
internal fun ForwardBridgeTypeClassifier.legacyRefusedFlowElementShape(
  type: KSType?,
): Pair<String, BridgeType.Unsupported?>? {
  val element: KSType = legacyFlowElement(type) ?: return null
  val shape: ForwardLegacyFlowElementShape = legacyFlowElementShape(element)
  return if (shape is ForwardLegacyFlowElementShape.Refused) shape.description to shape.refusal
  else null
}

/** The marshalled collection a `Flow`/`StateFlow` member's element is, or null for every other. */
internal fun ForwardBridgeTypeClassifier.legacyFlowElementCollection(
  type: KSType?,
): BridgeType.Collection? {
  val element: KSType = legacyFlowElement(type) ?: return null
  return (legacyFlowElementShape(element) as? ForwardLegacyFlowElementShape.Marshalled)?.type
}

/**
 * ADR-119: the C# expression reading a suspend member's awaited collection handle back into its
 * public type. `NugetMarshal.ReadList`/`ReadSet`/`ReadMap` are the same `nuget_list_*` /
 * `nuget_set_*` / `nuget_map_*` helpers the property route reads through, behind a `finally` that
 * disposes the handle, so the two routes agree at runtime as well as in the signature.
 */
internal fun legacyCollectionRead(handle: String, type: BridgeType.Collection): String =
  componentCollectionRead(handle, type, csharpType = { it.forwardPublicCsharpType() })

/** ADR-119 amendment: the `Task<...>` argument a collection return is DECLARED with. */
internal fun ForwardLegacyReturnShape.Marshalled.declaredCsharpType(): String =
  if (nullable) "${type.forwardPublicCsharpType()}?" else type.forwardPublicCsharpType()

/**
 * ...and its completion read, guarded on the wire pointer: `ReadList(IntPtr.Zero)` would call
 * `nuget_list_count(null)`, so a null result must short-circuit to `null` first.
 */
internal fun ForwardLegacyReturnShape.Marshalled.legacyMarshalledRead(handle: String): String =
  if (nullable) "$handle == IntPtr.Zero ? null : ${legacyCollectionRead(handle, type)}"
  else legacyCollectionRead(handle, type)

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
 * ADR-040: the C# type a suspend member's **interface** return is DECLARED with -- the projected
 * interface, fully qualified and owner-chained by the classifier, never the backing wrapper.
 */
internal fun ForwardLegacyReturnShape.Interface.declaredCsharpType(): String =
  if (nullable) "${type.csharpType}?" else type.csharpType

/**
 * ...and the expression the completion READS the awaited handle back with: ADR-136's resolve-then-
 * wrap, the same expression the synchronous plan return uses, against the completion's own handle
 * local. A handle over a C#-implemented pet resolves to the caller's original object; anything else
 * falls back to the ADR-040 backing wrapper, which is the only type that has a handle constructor
 * (`new IKeeper(ptr)` is CS0144). Both spellings convert implicitly to the declared interface, so
 * they coexist in one statement.
 *
 * A nullable return is guarded on the wire pointer first, for the shipped nullable-object arm's
 * reason: `new Wrapper(IntPtr.Zero)` would hand out a live wrapper over a null handle.
 */
internal fun ForwardLegacyReturnShape.Interface.legacyInterfaceRead(handle: String): String {
  val read: String = interfaceReturnExpression(type.csharpType, type.backingType, handle)
  return if (nullable) "$handle == IntPtr.Zero ? null : $read" else read
}

/**
 * ADR-123: the same read as a named `Func<IntPtr, T>` argument, for the flow routes.
 * `KotlinFlowEnumerator<T>` and `KotlinStateFlow<T>` are shared by every member in the generated
 * file, so a collection element cannot specialise them; it hands them this per-member delegate
 * instead of the default `NugetMarshal.FromHandle<T>`. `h` never collides:
 * `componentCollectionRead` names its own lambdas from nesting level 1 (`h1`) down.
 */
internal fun legacyFlowElementReadArgument(type: BridgeType.Collection): String =
  "read: static h => ${legacyCollectionRead("h", type)}"

/**
 * ADR-040 at a `Flow`/`StateFlow` **element**: the projected interface the element is declared
 * with, or null for every other element (which keeps `qualifiedElementCsType`'s spelling).
 *
 * The classifier is the speller, not `qualifiedElementCsType`: it is the one place that carries
 * BOTH spellings an interface needs (the `I...` projection to declare and the backing wrapper to
 * construct), already `global::`-qualified and owner-chained, and already gated on the exported
 * set -- so an interface no C# declaration exists for falls through unchanged.
 */
internal fun ForwardBridgeTypeClassifier.legacyFlowElementInterface(
  type: KSType?,
): BridgeType.Interface? {
  val expanded: KSType = type?.expandAliases() ?: return null
  val classified: BridgeType = classify(expanded).let {
    if (it is BridgeType.Nullable) it.type else it
  }
  return classified as? BridgeType.Interface
}

/**
 * The element materialiser an interface element needs, in the same `read:` slot ADR-123 added for
 * a collection element. ADR-136: it reads with the same resolve-then-wrap expression as every
 * other interface read, so a stored C#-implemented element is emitted as the caller's own object.
 * Without it the stream is read through `NugetMarshal.FromHandle<T>`, which has no factory for an
 * interface and falls through to its `Activator.CreateInstance` branch: the wrong spelling
 * COMPILES and dies at the first emission.
 */
internal fun legacyInterfaceElementReadArgument(
  type: BridgeType.Interface,
  nullable: Boolean,
): String {
  val read: String = interfaceReturnExpression(type.csharpType, type.backingType, "h")
  return if (nullable) {
    "read: static h => h == IntPtr.Zero ? null : $read"
  } else {
    "read: static h => $read"
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

/**
 * `Pair<String, Int>`, `Map<String?, Int>`, `List<String>?`: the declared type with every alias
 * expanded and every `?` it carries, head and type arguments, so a skip diagnostic names the shape
 * being refused. ADR-122: the `?` matters for a non-generic refusal too, since a nullable object is
 * refused *for* its nullability.
 */
private fun KSType.legacyDescription(): String = kotlinSpelling()

/**
 * The generator names one legacy-route member derives from its parameters, minted once per member
 * by `freshName` so a user parameter spelled like one of them keeps its name and the generator's
 * moves instead (the plan route's `ForwardPublicParameter.hasValueSlot` rule, on a route that has
 * no plan to carry it). Every builder of either half takes its names from here, never rebuilds
 * them: a rebuilt `"${name}HasValue"` would resolve to the user's own `limitHasValue` after a
 * rename and compile clean.
 *
 * Each half builds its own instance from its own spelling of the parameter names (Kotlin from the
 * declaration, C# from `csharpParameterName`), because each declares these names in its own
 * signature. The ABI is positional and ADR-055 compares wire types, so the two can never disagree
 * on the wire.
 *
 * Only a name a parameter actually uses is minted.
 */
internal class ForwardLegacyNames(
  parameters: List<String>,
  /** The member's effective shapes (ADR-164 widening applied), read by the dispatch below. */
  val shapes: List<ForwardLegacyParameterShape>,
  // Which half this instance names: the two spell their callback slots differently (`onNextPtr`
  // in the Kotlin export, `onNext` in the `DllImport` and the collect lambda) and each has locals
  // the other lacks.
  csharp: Boolean = false,
) {
  private val taken: MutableSet<String> = parameters.toMutableSet()

  private fun mint(base: String): String = freshName(base, taken).also { taken += it }

  /**
   * Issue #299: per parameter, the BOOLEAN slot a fanned-out
   * [ForwardLegacyParameterShape.NullableScalar] adds before its value slot, or null.
   * `${name}HasValue` unless taken, the same spelling the plan route gives the same wire.
   */
  val hasValueSlots: List<String?> = parameters.mapIndexed { index, name ->
    val shape: ForwardLegacyParameterShape = shapes[index]
    val fansOut: Boolean = shape is ForwardLegacyParameterShape.NullableScalar && shape.fansOut
    if (fansOut) mint("${name}HasValue") else null
  }

  /**
   * ADR-164 rule 2: per parameter, the leading BOOLEAN slot an `Optional<T?>` parameter adds before
   * its own nullable wire (`${name}IsSet` unless taken), or null.
   */
  val isSetSlots: List<String?> = parameters.mapIndexed { index, name ->
    val shape: ForwardLegacyParameterShape = shapes[index]
    val optional: Boolean = shape is ForwardLegacyParameterShape.NullableScalar && shape.optional
    if (optional) mint("${name}IsSet") else null
  }

  /**
   * Kotlin only: the ADR-164 presence-mask local (`mask` unless taken), or null when no parameter
   * is defaulted. Bound in the prelude, before the coroutine launches, and read by `when (mask)`.
   */
  val mask: String? =
    if (!csharp && shapes.any { shape -> shape.isLegacyDefaulted }) mint("mask") else null

  /**
   * Per parameter, the Kotlin local a lowered shape is bound to (`${name}Arg` unless taken), or
   * null. Named apart from the parameter so the export still names its ABI slot after the
   * declaration.
   */
  val loweredLocals: List<String?> = parameters.mapIndexed { index, name ->
    if (!csharp && shapes[index].isLegacyLowered()) mint("${name}Arg") else null
  }

  /**
   * C# only: per parameter, the wire-handle local a marshalled collection is built into
   * (`${name}Handle` unless taken), or null.
   */
  val handleLocals: List<String?> = parameters.mapIndexed { index, name ->
    val marshalled: Boolean = csharp && shapes[index] is ForwardLegacyParameterShape.Marshalled
    if (marshalled) mint("${name}Handle") else null
  }

  /** The member routes' class-scope slot. */
  val scopeHandle: String = mint("scopeHandle")

  /**
   * The Flow routes' three callback slots: `onNextPtr` / ... in the Kotlin export, `onNext` / ...
   * in the `DllImport` and as the C# collect lambda's parameters. A lambda parameter spelled like a
   * user parameter would shadow it, and the user's argument would silently become the callback.
   */
  val onNext: String = mint(if (csharp) "onNext" else "onNextPtr")
  val onComplete: String = mint(if (csharp) "onComplete" else "onCompletePtr")
  val onError: String = mint(if (csharp) "onError" else "onErrorPtr")

  /** The suspend routes' completion-callback slot: `callbackPtr` in Kotlin, `callback` in C#. */
  val callback: String = mint(if (csharp) "callback" else "callbackPtr")

  /**
   * The suspend and Flow routes' trailing callback-context slot (and C# collect-lambda parameter).
   */
  val userData: String = mint("userData")

  /** Kotlin only: the export body's receiver and scope locals. */
  val obj: String = if (csharp) "obj" else mint("obj")
  val scope: String = if (csharp) "scope" else mint("scope")

  /**
   * Kotlin only: the Flow body's `collectForCSharp { emit -> ... }` lambda parameter, which the
   * member call sits inside: spelled like a user parameter it would shadow the argument.
   */
  val emit: String = if (csharp) "emit" else mint("emit")
}

/** [ForwardLegacyNames] for a member's declared parameters, in the Kotlin export's spelling. */
internal fun legacyKotlinNames(
  parameters: List<KSValueParameter>,
  shapes: List<ForwardLegacyParameterShape>,
): ForwardLegacyNames =
  ForwardLegacyNames(parameters.map { parameter -> parameter.name?.asString() ?: "_" }, shapes)

/**
 * ADR-114 alternative 1: the eager Kotlin copy. Emitted *before* `scope.launch`, so the wire
 * container has been copied out by the time the export returns and the C# side's `finally`-dispose
 * can never be a use-after-free. Reuses the ordinary route's lowering verbatim.
 */
internal fun legacyLoweringStatement(
  parameter: String,
  local: String,
  type: BridgeType.Collection,
): String = "val $local = ${loweredCollectionExpression(parameter, type)}"

/**
 * ADR-122: the eager dereference of a borrowed handle parameter, the same expression the ordinary
 * plan route emits. Emitted in the same prelude as [legacyLoweringStatement] and for a related
 * reason read the other way round: the coroutine captures a strong Kotlin reference, so a C#
 * consumer disposing its wrapper mid-flow cannot invalidate what the coroutine is still reading.
 * Inlined at the call site it would instead be evaluated inside `scope.launch`, after the export
 * has returned, which is exactly the lifetime hazard ADR-114 designed out.
 */
internal fun legacyHandleStatement(
  parameter: String,
  local: String,
  type: BridgeType.ObjectHandle,
  nullable: Boolean = false,
): String = if (nullable) {
  "val $local = $parameter?.asStableRef<${type.qualifiedName}>()?.get()"
} else {
  "val $local = $parameter.asStableRef<${type.qualifiedName}>().get()"
}

/** Whether this lowered parameter's `COpaquePointer` slot is nullable (issue #365). */
internal val ForwardLegacyParameterShape.isLegacyNullableSlot: Boolean
  get() = this is ForwardLegacyParameterShape.Handle && nullable

/**
 * Whether the Kotlin export binds this parameter to an eagerly-evaluated local rather than calling
 * the member with the ABI slot directly. True for both lowered shapes, so the export builders name
 * one rule instead of testing two variants in five places.
 */
internal fun ForwardLegacyParameterShape.isLegacyLowered(): Boolean = when (this) {
  is ForwardLegacyParameterShape.Marshalled, is ForwardLegacyParameterShape.Handle -> true
  ForwardLegacyParameterShape.Plain,
  is ForwardLegacyParameterShape.Enum,
  is ForwardLegacyParameterShape.NullableScalar,
  is ForwardLegacyParameterShape.Refused -> false
}

/**
 * The Kotlin expression the member is called with for parameter [index], spelled [parameter]: a
 * lowered shape reads its eagerly-built local, a fanned-out nullable scalar is rebuilt from its
 * has-value pair (the plan route's `ForwardKotlinPlanEmitter` text), everything else is the
 * parameter itself.
 */
internal fun ForwardLegacyNames.legacyArgument(index: Int, parameter: String): String {
  loweredLocals[index]?.let { local -> return local }
  val shape: ForwardLegacyParameterShape = shapes[index]
  if (shape is ForwardLegacyParameterShape.Enum) return legacyEnumValue(shape.type, parameter)
  val scalar: ForwardLegacyParameterShape.NullableScalar =
    shape as? ForwardLegacyParameterShape.NullableScalar ?: return parameter
  val value: String = (scalar.type as? BridgeType.Enum)
    ?.let { enum -> legacyEnumValue(enum, parameter) } ?: parameter
  val hasValue: String? = hasValueSlots[index]
  return when {
    // ADR-164: a widened non-null parameter is only ever passed in the arms that set it, where
    // the member declares the non-null type: the raw value, not the `Int?` it was carried as.
    scalar.widened && hasValue != null -> value
    scalar.widened -> "$parameter!!"
    hasValue != null -> "if ($hasValue) $value else null"
    else -> parameter
  }
}

/** ADR-080's ordinal lowering, the plan route's text: `Hunger.entries[asked]`. */
private fun legacyEnumValue(type: BridgeType.Enum, parameter: String): String =
  "${type.qualifiedName}.entries[$parameter]"

/**
 * ADR-164: the Kotlin test for whether defaulted parameter [index] was set by the C# caller: the
 * `IsSet` slot of an `Optional`, the has-value slot of a widened value, or the widened `String?`
 * slot being non-null.
 */
private fun ForwardLegacyNames.legacyPresence(index: Int, parameter: String): String =
  isSetSlots[index] ?: hasValueSlots[index] ?: "$parameter != null"

/**
 * The member called with its arguments, as one Kotlin expression. With no defaulted parameter it
 * is the positional `target(a, b)` it always was. With some it is ADR-164's `when (mask)` of named
 * calls (the plan route's [forwardMaskArms]), parenthesized so a caller can hang `.collect`,
 * `.value` or `?.` off it; [mask] is bound by [legacyPrelude], before any coroutine launches.
 */
internal fun ForwardLegacyNames.legacyInvocation(target: String, parameters: List<String>): String {
  val arguments: List<ForwardDispatchArgument> = parameters.mapIndexed { index, parameter ->
    ForwardDispatchArgument(
      label = parameter,
      defaulted = shapes[index].isLegacyDefaulted,
      value = legacyArgument(index, parameter),
    )
  }
  val mask: String = mask ?: return "$target(${arguments.joinToString(", ") { it.value }})"
  val arms: List<String> = forwardMaskArms(arguments) { call -> "$target($call)" }
  return buildString {
    appendLine("(when ($mask) {")
    arms.forEach { arm -> appendLine("  $arm") }
    appendLine("  else -> error(\"unreachable\")")
    append("})")
  }
}

/**
 * Every statement the export body runs before it calls (or launches the call of) the member: the
 * eager ADR-114/ADR-122 lowerings, then ADR-164's presence mask. Evaluated on the caller's thread,
 * so a coroutine only ever captures values.
 */
internal fun ForwardLegacyNames.legacyPrelude(parameters: List<String>): String = buildString {
  parameters.forEachIndexed { index, parameter ->
    shapes[index].legacyPrelude(parameter, loweredLocals[index])?.let { line -> appendLine(line) }
  }
  val mask: String = mask ?: return@buildString
  var bit = 0
  val terms: List<String> = parameters.mapIndexedNotNull { index, parameter ->
    if (!shapes[index].isLegacyDefaulted) return@mapIndexedNotNull null
    "(if (${legacyPresence(index, parameter)}) ${1 shl bit++} else 0)"
  }
  appendLine("val $mask = ${terms.joinToString(" or ")}")
}

/**
 * Issue #299: the C# arguments a fanned-out nullable scalar [name] is passed to the native call as,
 * matching its two slots (the plan route's `ForwardCirPlanProjection` text). An enum's value half
 * is its ordinal (ADR-080).
 */
internal fun legacyNullableScalarArgument(name: String, enum: Boolean = false): String =
  "$name.HasValue, ${if (enum) "(int)" else ""}$name.GetValueOrDefault()"

/**
 * ADR-164 rule 2: the C# arguments an `Optional<T?>` parameter [name] is passed as: its `IsSet`
 * slot, then its nullable wire read off `.Value` (the has-value pair, or the one `string?` slot).
 */
internal fun legacyOptionalArgument(name: String, fansOut: Boolean, enum: Boolean): String =
  if (fansOut) "$name.HasValue, ${legacyNullableScalarArgument("$name.Value", enum)}"
  else "$name.HasValue, $name.Value"

/**
 * The prelude line this parameter contributes, or null when it is passed through as-is. [local] is
 * its [ForwardLegacyNames.loweredLocals] entry, non-null exactly when this shape is lowered.
 */
internal fun ForwardLegacyParameterShape.legacyPrelude(
  parameter: String,
  local: String?,
): String? = when (this) {
  is ForwardLegacyParameterShape.Marshalled ->
    legacyLoweringStatement(parameter, requireNotNull(local), type)
  is ForwardLegacyParameterShape.Handle ->
    legacyHandleStatement(parameter, requireNotNull(local), type, nullable)
  ForwardLegacyParameterShape.Plain,
  is ForwardLegacyParameterShape.Enum,
  is ForwardLegacyParameterShape.NullableScalar,
  is ForwardLegacyParameterShape.Refused -> null
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

/**
 * ADR-090 amendment (2026-09-26): why the ADR-039 `add*`/`remove*` subscription route cannot
 * carry the pair whose add half is [addMethod], or null when it can. That route names one
 * function-pointer slot per listener member by its simple name (`onMeowPtr`, `onMeowFn`), so a
 * listener interface declaring a member name twice generated Kotlin that did not compile
 * (`Conflicting declarations: onMeowFn`). The route is legacy, so it is not numbered: both halves
 * drop the pair and `warnRefusedLegacyRouteMembers` names it.
 */
internal fun ForwardBridgeTypeClassifier.legacyRefusedInterfaceBridgePair(
  addMethod: KSFunctionDeclaration,
): LegacyRefusedInterfaceBridgePair? {
  val listenerType: KSType = addMethod.parameters.firstNotNullOfOrNull { parameter ->
    parameter.type.resolve().expandAliases().takeIf { type ->
      (type.declaration as? KSClassDeclaration)?.classKind == ClassKind.INTERFACE
    }
  } ?: return null
  val listener: KSClassDeclaration = listenerType.declaration as KSClassDeclaration
  undeclaredListener(listenerType)?.let { return it }
  val listenerName: String = listener.simpleName.asString()
  val members: List<KSFunctionDeclaration> = listener.getAllFunctions()
    .filter { method -> method.getVisibility() == Visibility.PUBLIC }
    .filter { method -> !method.isCompilerOwnedMember(listener) }
    .toList()
  val repeated: String? = members
    .groupBy { method -> method.simpleName.asString() }
    .entries
    .firstOrNull { (_, named) -> named.size > 1 }
    ?.key
  if (repeated != null) {
    return LegacyRefusedInterfaceBridgePair(
      kind = ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_INPUT,
      reason = "its listener interface `$listenerName` declares `$repeated` more than once, and " +
          "this subscription route binds one callback slot per member name",
      hint = "give each listener member its own name (`onMeow()` / `onMeowTimes(times)`)",
    )
  }
  return members.firstNotNullOfOrNull { member -> refusedListenerMember(listener, member) }
}

/**
 * ADR-039 amendment (2026-09-28): the refusal for a listener interface with no C# declaration
 * (nested under an owner ADR-133 defers, dropped by its CS0102 collision gate, or outside the
 * export scope), or null when the classifier declares it. The pairing test only reads the
 * declaration's kind, so a nullable listener is unwrapped first, exactly as
 * [legacyFlowElementInterface] does. The kind, sentence and hint come off the same
 * [ForwardPlanSkipReason] the planner gives that type at an ordinary member, so an undeclared
 * listener reads the same as an undeclared interface parameter.
 */
private fun ForwardBridgeTypeClassifier.undeclaredListener(
  listenerType: KSType,
): LegacyRefusedInterfaceBridgePair? {
  val classified: BridgeType = classify(listenerType).let {
    if (it is BridgeType.Nullable) it.type else it
  }
  if (classified !is BridgeType.Unsupported) return null
  val reason: ForwardPlanSkipReason = classified.skipReason() ?: ForwardPlanSkipReason.UNSUPPORTED
  val detail: String? = classified.skipDetail()
  val kind: ForwardDiagnosticKind =
    if (reason.droppedFromCSharp) reason.toDiagnosticKind(ForwardSkipPosition.INPUT)
    else ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_INPUT
  val listenerName: String = listenerType.declaration.qualifiedName?.asString()
    ?: listenerType.declaration.simpleName.asString()
  return LegacyRefusedInterfaceBridgePair(
    kind = kind,
    reason = if (reason.ownsSentence(detail)) reason.diagnosticReason(detail)
    else "its listener interface `$listenerName` has no C# declaration",
    hint = reason.diagnosticHint(detail),
  )
}

/**
 * ADR-039 amendment (2026-09-26): why the subscription route cannot carry one listener member, or
 * null when it can. The route has one `Void` delegate per member and wires each parameter by
 * value, as an ordinal, or over a handle, so a member is carried only when every parameter is in
 * ADR-160's callback payload set and it returns `Unit`. A member inherited from a super-interface
 * is refused too: the generated `IFoo` has no base list, so the C# half would call a member the
 * implementer's interface does not declare (CS1061).
 */
private fun ForwardBridgeTypeClassifier.refusedListenerMember(
  listener: KSClassDeclaration,
  member: KSFunctionDeclaration,
): LegacyRefusedInterfaceBridgePair? {
  val listenerName: String = listener.simpleName.asString()
  val signature: String = "`$listenerName.${member.simpleName.asString()}(" +
      member.parameters.joinToString(", ") { parameter ->
        "${parameter.name?.asString() ?: "_"}: ${parameter.type.resolve().kotlinSpelling()}"
      } + ")`"
  val owner: KSClassDeclaration? = member.parentDeclaration as? KSClassDeclaration
  if (owner != null && owner.qualifiedName?.asString() != listener.qualifiedName?.asString()) {
    return LegacyRefusedInterfaceBridgePair(
      kind = ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_INPUT,
      reason = "its listener member $signature is inherited from `${owner.simpleName.asString()}`" +
          ", and the generated `I$listenerName` declares only `$listenerName`'s own members",
      hint = "redeclare the member on `$listenerName` (`override fun " +
          "${member.simpleName.asString()}(...)`) so `I$listenerName` declares it",
    )
  }
  member.parameters.forEach { parameter ->
    val type: KSType = parameter.type.resolve()
    if (parameter.isVararg || !classify(type).isCallbackPayload()) {
      val spelled: String = (if (parameter.isVararg) "vararg " else "") + type.kotlinSpelling()
      return LegacyRefusedInterfaceBridgePair(
        kind = ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_INPUT,
        reason = "its listener member $signature takes `$spelled`, which an interface callback " +
            "cannot carry (it carries a non-null primitive, String, enum, or exported " +
            "class/interface)",
        hint = "give `$listenerName` members only those parameter types (a collection, a " +
            "nullable, `Char`, `Any` or an array has no crossing on this route), or split the " +
            "member that needs one into a separate listener",
      )
    }
  }
  val returned: KSType? = member.returnType?.resolve()
  if (returned != null && classify(returned) != BridgeType.Unit) {
    return LegacyRefusedInterfaceBridgePair(
      kind = ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_RETURN,
      reason = "its listener member $signature returns `${returned.kotlinSpelling()}`, and this " +
          "subscription route only calls back members that return `Unit`",
      hint = "make `$listenerName`'s members return `Unit`; hand a result back through a method " +
          "on the subscribing class instead",
    )
  }
  return null
}

/**
 * A Kotlin source spelling for a diagnostic: simple names, type arguments and every `?` kept, head
 * and arguments alike. Aliases are expanded at every level (ADR-018 amendment), so `Names?` reads
 * `List<String>?` and `Map<Name?, Int>` reads `Map<String?, Int>`: the shape being refused, not the
 * alias hiding it. A `kotlin.FunctionN` is spelled in arrow syntax (`(Int) -> Unit`).
 */
internal fun KSType.kotlinSpelling(): String {
  val type: KSType = expandAliases()
  val qualifiedName: String? = type.declaration.qualifiedName?.asString()
  if (qualifiedName in LEGACY_CALLBACK_TYPES && type.arguments.isNotEmpty()) {
    val spelled: List<String> = type.arguments.map { argument ->
      argument.type?.resolve()?.kotlinSpelling() ?: "*"
    }
    val lambda: String = "(${spelled.dropLast(1).joinToString(", ")}) -> ${spelled.last()}"
    return if (type.isMarkedNullable) "($lambda)?" else lambda
  }
  val arguments: String =
    if (type.arguments.isEmpty()) ""
    else type.arguments.joinToString(", ", "<", ">") { argument ->
      argument.type?.resolve()?.kotlinSpelling() ?: "*"
    }
  val nullable: String = if (type.isMarkedNullable) "?" else ""
  return "${type.declaration.simpleName.asString()}$arguments$nullable"
}

/**
 * Why an ADR-039 subscription pair is refused: the diagnostic kind (a refused parameter or a
 * refused return), the reason naming the listener member and type, and the author's way out.
 * `warnRefusedLegacyRouteMembers` reports it on both halves of the pair.
 */
internal data class LegacyRefusedInterfaceBridgePair(
  val kind: ForwardDiagnosticKind,
  val reason: String,
  val hint: String,
)

/**
 * The stored-callback (ADR-037) twin of [legacyRefusedInterfaceBridgePair]: why the `add`/`remove`
 * pair whose add half is [addMethod] cannot be carried, or null when it can. The route's bridge
 * lambda is always typed `(...) -> Unit` and its C# half is always an `Action`, so a listener that
 * returns anything else generated Kotlin that did not compile (`actual type is '() -> Unit', but
 * '() -> Int' was expected`).
 *
 * Applied AFTER pair detection, never before the per-call/stored partition: a non-`Unit` result is
 * a working shape on the per-call route (`fun ask(cb: (Int) -> String): String`), so a
 * pre-partition rule would drop members that bind. Pair detection keeps claiming both halves, so a
 * refused pair cannot fall through to the per-call route either.
 */
internal fun legacyRefusedStoredCallbackPair(
  addMethod: KSFunctionDeclaration,
): LegacyRefusedInterfaceBridgePair? {
  val lambda: KSValueParameter = addMethod.parameters.firstOrNull { parameter ->
    parameter.type.resolve().expandAliases().declaration.qualifiedName?.asString() in
        LEGACY_CALLBACK_TYPES
  } ?: return null
  val lambdaType: KSType = lambda.type.resolve().expandAliases()
  val result: KSType = lambdaType.arguments.lastOrNull()?.type?.resolve()?.expandAliases()
    ?: return null
  if (result.declaration.qualifiedName?.asString() == "kotlin.Unit") return null
  return LegacyRefusedInterfaceBridgePair(
    kind = ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_RETURN,
    reason = "its listener `${lambda.name?.asString() ?: "_"}: ${lambdaType.kotlinSpelling()}` " +
        "returns `${result.kotlinSpelling()}`, and a stored listener is called back as an " +
        "`Action`, which returns nothing",
    hint = "make the listener return `Unit`; hand a result back through a method on the " +
        "subscribing class instead",
  )
}

/**
 * ADR-039 amendment (2026-09-26): how one admitted listener parameter crosses the subscription
 * route. Read by both halves (`exports/InterfaceBridgeExports.kt`, `cir/CirClassTranslator.kt`)
 * off one classification, so the wire cannot drift from the gate above.
 */
internal enum class InterfaceBridgeWire { BOOL, BY_VALUE, ORDINAL, HANDLE }

internal fun BridgeType.interfaceBridgeWire(): InterfaceBridgeWire = when (this) {
  is BridgeType.Primitive ->
    if (kind == PrimitiveKind.BOOLEAN) InterfaceBridgeWire.BOOL else InterfaceBridgeWire.BY_VALUE
  is BridgeType.Enum -> InterfaceBridgeWire.ORDINAL
  BridgeType.String, is BridgeType.ObjectHandle, is BridgeType.Interface ->
    InterfaceBridgeWire.HANDLE
  else -> error("ADR-039: the subscription pair gate admitted $this, which has no wire")
}
