package io.github.xxfast.kotlin.native.nuget.processor.forward

import io.github.xxfast.kotlin.native.nuget.processor.ForwardSymbolTable

import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.getConstructors
import com.google.devtools.ksp.isConstructor
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.Origin
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSValueParameter
import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Visibility
import io.github.xxfast.kotlin.native.nuget.processor.ExpectIndex
import io.github.xxfast.kotlin.native.nuget.processor.exports.findInterfaceBridgePairs
import io.github.xxfast.kotlin.native.nuget.processor.exports.findStoredCallbackPairs
import io.github.xxfast.kotlin.native.nuget.processor.exports.forwardArmInterfaceBridgePairs
import io.github.xxfast.kotlin.native.nuget.processor.exports.forwardArmLambdaMethods
import io.github.xxfast.kotlin.native.nuget.processor.exports.forwardArmStoredCallbackPairs
import io.github.xxfast.kotlin.native.nuget.processor.exports.forwardClassLegacyMembers
import io.github.xxfast.kotlin.native.nuget.processor.exports.hasLegacyFlowReturn
import io.github.xxfast.kotlin.native.nuget.processor.exports.hasLegacyGenericReturnRoute
import io.github.xxfast.kotlin.native.nuget.processor.exports.hasLegacyLambdaParameter
import io.github.xxfast.kotlin.native.nuget.processor.exports.hasPlannedCallbackParameter
import io.github.xxfast.kotlin.native.nuget.processor.exports.isCompilerOwnedMember
import io.github.xxfast.kotlin.native.nuget.processor.exports.refusedLegacyLambdaShape
import io.github.xxfast.kotlin.native.nuget.processor.PLAN_OWNED_NAMES
import io.github.xxfast.kotlin.native.nuget.processor.RESULT_FAILED_SLOT
import io.github.xxfast.kotlin.native.nuget.processor.bridgeParameterName
import io.github.xxfast.kotlin.native.nuget.processor.freshName
import io.github.xxfast.kotlin.native.nuget.processor.toCName
import io.github.xxfast.kotlin.native.nuget.processor.asCSymbol
import io.github.xxfast.kotlin.native.nuget.processor.toCSharpName
import io.github.xxfast.kotlin.native.nuget.processor.cir.KOTLIN_EXCEPTION_TYPES
import io.github.xxfast.kotlin.native.nuget.processor.cir.KotlinExceptionMatch
import io.github.xxfast.kotlin.native.nuget.processor.cir.expandAliases
import io.github.xxfast.kotlin.native.nuget.processor.cir.nativePrefix
import io.github.xxfast.kotlin.native.nuget.processor.cir.nestedCsName

/**
 * Why the planner declined to build an ordinary synchronous plan for a callable.
 *
 * [droppedFromCSharp] is the load-bearing distinction: a reason is a *drop* only when no named
 * legacy route re-emits the callable, so the declaration genuinely disappears from the generated
 * C# API (never as an `IntPtr` / `"0"` fallback, just absent). Those reasons are surfaced as a KSP
 * warning. Reasons that defer the callable to a named legacy export builder / CIR translator
 * (`ForwardAbiLegacyRoutes`, ADR-062's legacy-route table) are *not* drops: the callable is still
 * emitted, only not through the plan, so warning on them would be a false alarm.
 */
internal enum class ForwardPlanSkipReason(val droppedFromCSharp: Boolean) {
  // Deferred to a named legacy route (still emitted, just not via the plan): silent.
  ABSTRACT(droppedFromCSharp = false),
  CALLBACK_PROTOCOL(droppedFromCSharp = false),
  FLOW_PROTOCOL(droppedFromCSharp = false),
  GENERIC(droppedFromCSharp = false),
  SUSPEND(droppedFromCSharp = false),
  SUSPEND_CALLBACK_PROTOCOL(droppedFromCSharp = false),

  // No legacy home: the callable is dropped from the C# API and must warn. CHAR/STRING/ENUM/HANDLE/
  // OBJECT are supported ordinary types with no legacy route, so a skip carrying them can only mean
  // a genuine drop; they are defensively classified as drops even though the planner does not
  // currently reach them.
  CHAR(droppedFromCSharp = true),
  COLLECTION(droppedFromCSharp = true),
  ENUM(droppedFromCSharp = true),
  HANDLE(droppedFromCSharp = true),

  // ADR-076: same defensive classification as CHAR/STRING/ENUM/HANDLE/OBJECT above -- Instant is a
  // supported ordinary type with no legacy route, so a skip carrying it can only mean a genuine
  // drop, even though the planner does not currently reach it (shapeOrNull's Instant branch
  // always succeeds).
  INSTANT(droppedFromCSharp = true),

  // ADR-103: defensive in exactly the same way as INSTANT above.
  DURATION(droppedFromCSharp = true),

  /** ADR-106: defensive, exactly as INSTANT/DURATION above -- `shapeOrNull`'s Uuid branch always
   *  succeeds, so a skip carrying a Uuid can only be a genuine drop. */
  UUID(droppedFromCSharp = true),

  /** ADR-151: `kotlin.ByteArray` binds at every ordinary top-level position, so a skip carrying
   *  one is either defensive or the deferred nesting case (`List<ByteArray>`, whose component
   *  helpers have no bytes arm yet). Either way a genuine drop with no legacy route. */
  BYTE_ARRAY(droppedFromCSharp = true),

  /**
   * ADR-083 amendment (boundary nullability part B): a `Map`/`MutableMap` whose KEY is nullable, at
   * ANY position. ADR-083 declined it at an input position already; the result, property-read and
   * nested positions admitted it and rendered `NugetMarshal.ReadMap<string?, int>` against a helper
   * declared `where TKey : notnull`, which is CS8714 and a hard error under the ADR-138 gate's
   * csproj -- a `packNuget` abort, not a consumer-side warning.
   *
   * Its own reason rather than the COLLECTION bucket because the hint slot carries no per-detail
   * text, and COLLECTION's hint ("use components that are primitives, Char, String, ...") would
   * send the author looking at their key's TYPE when the problem is its nullability.
   */
  NULLABLE_MAP_KEY(droppedFromCSharp = true),

  /** ADR-107 / ADR-201: a `Throwable` position that does not bind: an input whose declared type
   *  is narrower than `RuntimeException` (C# can only hand Kotlin a `NugetManagedException`), a
   *  `Set` element or `Map` key, and a bare suspend/Flow result. A genuine drop with no legacy
   *  route -- named here rather than folded into HANDLE, whose hint would point at the
   *  object-handle export set. */
  THROWABLE(droppedFromCSharp = true),
  NULLABLE(droppedFromCSharp = true),

  /** An extension property shadowed by a member property of the same name on its receiver type
   *  (declared or inherited). Kotlin call syntax always resolves `receiver.name` to the member, so
   *  the generated `receiver.name` body would read the member and the C# extension would silently
   *  return the member's value. Only the extension-property route records it. The shadowing
   *  member (`Owner.name`) rides in the detail slot. */
  SHADOWED_BY_MEMBER(droppedFromCSharp = true),

  /** ADR-188: an extension property whose C# name and receiver declaration match a method C#
   *  resolves `receiver.Name` against: an exported extension function's (`val Cat.nameOrStray`
   *  beside `fun Cat?.nameOrStray()`), an enum member function's (rendered beside it in
   *  `{Enum}Extensions`), or a class member function's (an instance method). C# 14 then resolves
   *  no `cat.NameOrStray` access (CS9339, CS1061 or CS0428), so the property is skipped and the
   *  function keeps the name. Only the extension-property route records it; the function, spelled
   *  with its kind (`extension function `nameOrStray``), rides in the detail slot. */
  SHADOWED_BY_EXTENSION_FUNCTION(droppedFromCSharp = true),

  /** ADR-188 amendment: `val Cat.x` beside `val Cat?.x` in one package. Kotlin resolves the pair
   *  by static type; C# cannot declare both (CS0102), both derive one plan symbol and one export,
   *  and neither is a safe survivor, so the pair is refused as a fatal
   *  `ERROR_CSHARP_SIGNATURE_COLLISION`. The two declarations ride in the detail slot. */
  NULLABLE_RECEIVER_TWIN(droppedFromCSharp = true),
  OBJECT(droppedFromCSharp = true),
  STRING(droppedFromCSharp = true),
  UNSUPPORTED(droppedFromCSharp = true),
  VALUE_CLASS(droppedFromCSharp = true),

  // ADR-064: genuine drops with their own named diagnostic kind, not the generic "type
  // combination is not supported" bucket the reasons above still render through.
  /** Cell 23 / BUG-010: a generic + suspend + inline + reified extension returning `Result<T>` —
   *  the combination has no working legacy route, even though suspend and generic each do
   *  individually. */
  UNSUPPORTED_COMBINATION(droppedFromCSharp = true),

  /**
   * ADR-162: the planner's own invariant failed on this callable (a raw `error(...)`, a failed
   * `require(...)`, a null `!!` in classification, shaping or plan validation). Mapped to the fatal
   * [ForwardDiagnosticKind.ERROR_INTERNAL_GENERATOR_FAILURE], not to a `SKIPPED_*` warning: it is a
   * generator bug, and shipping a package quietly missing the declaration is worse than a build
   * that stops.
   *
   * A `Skipped` entry rather than an omitted one, which is the load-bearing part (verified by
   * spike, ADR-162 F2): omitting the entry makes the Kotlin emitter throw `Forward callable
   * catalog has no entry for ...` before the fatal-diagnostic gate, which masks every later
   * failure of the round and takes over Gradle's headline — the exact one-at-a-time behaviour
   * this containment exists to end. [ForwardCallableCatalogEntry.Skipped.detail] carries the
   * exception class and message.
   */
  INTERNAL_FAILURE(droppedFromCSharp = true),

  /** ADR-116: a member function declared on a sealed subclass that the plan declined for a reason
   *  an ordinary class defers to a named legacy route (`suspend`, `Flow`, a generic, a
   *  lambda/stored-callback pair). No legacy route is keyed to a sealed subclass — the ordinary
   *  `classes` list excludes them (ADR-009 / issue #54) — so the member genuinely disappears from
   *  the C# API and has to be named rather than silently deferred. The deferral reason it was
   *  reclassified from rides in [ForwardCallableCatalogEntry.Skipped.detail]. */
  SEALED_SUBCLASS_UNROUTED(droppedFromCSharp = true),

  /** ADR-116 amendment (2026-09-11): the same absence one level up, on a member the sealed
   *  **base** declares (`Job.rest`, an `open suspend fun`). The base carries its ordinary members
   *  now, but no legacy route is keyed to it at all, not even the suspend and flow ones ADR-118
   *  and ADR-124 keyed to the arms, so the member is gone from C# on the base *and* on every arm
   *  that inherits it. Separate from [SEALED_SUBCLASS_UNROUTED] because the remedy differs: the
   *  author can move the member onto each arm, which does have those routes. */
  SEALED_BASE_UNROUTED(droppedFromCSharp = true),

  /** ADR-147: a `suspend`, `Flow` or legacy-callback member of a GENERIC class. Every legacy route
   *  spells the receiver as the bare owner name, which does not compile for `Crate<T>`, so the
   *  deferral is a drop there. The deferral reason it was relabelled from rides in
   *  [ForwardCallableCatalogEntry.Skipped.detail]. */
  GENERIC_OWNER_LEGACY_ROUTE(droppedFromCSharp = true),

  /** ADR-064 amendment (2026-09-13): the ordinary-owner twin of [SEALED_SUBCLASS_UNROUTED]. The
   *  four legacy-route deferrals below ([GENERIC], [FLOW_PROTOCOL], [CALLBACK_PROTOCOL],
   *  [SUSPEND_CALLBACK_PROTOCOL]) are only deferrals at the handful of owner/position combinations
   *  a legacy route is actually keyed to (research H measured six). Everywhere else — a Flow at a
   *  parameter, a lambda at a class-method return, any of them on an object, an interface, an
   *  extension or a constructor — the member vanishes from both halves, so the deferral is a drop
   *  and has to be named. The reason it was reclassified from rides in
   *  [ForwardCallableCatalogEntry.Skipped.detail]. */
  UNROUTED_POSITION(droppedFromCSharp = true),

  /** ADR-064 amendment: a member of a `companion object` whose owner no route renders statics for
   *  (an interface, a sealed base or arm, a value class). ADR-013 folds only an ordinary class's
   *  (and, per ADR-006, an enum's) companion into its owner, so the member is in neither half. The
   *  owner kind rides in [ForwardCallableCatalogEntry.Skipped.detail]. */
  COMPANION_NO_CARRIER(droppedFromCSharp = true),

  /** ADR-160 amendment (issue #111 on the plan): a top-level function returning a lambda one of
   *  whose type arguments C# cannot spell (`() -> Flow<Snapshot>`, `() -> List<Int>`). The
   *  offending argument's Kotlin name rides in [ForwardCallableCatalogEntry.Skipped.detail]. */
  LAMBDA_TYPE_ARGUMENT(droppedFromCSharp = true),

  /** ADR-064/ADR-082: a value-class member whose signature a supertype declares — inherited,
   *  forwarded by interface delegation (e.g. `CharSequence by value`) or explicitly overridden. */
  INHERITED_MEMBER(droppedFromCSharp = true),

  /** ADR-066: a reachable, structurally bridgeable declaration in a dependency module whose
   *  package the reachability closure did not admit (out of scope, not unsupported). Carries its
   *  own diagnostic kind (`SKIPPED_UNEXPORTED_DEPENDENCY_TYPE`) naming the `include(...)` fix,
   *  rather than the generic "declaration is not in the exported object-handle set" message. */
  UNEXPORTED_DEPENDENCY_TYPE(droppedFromCSharp = true),

  /** The same drop, refused for a reason `include(...)` cannot fix: the author's own
   *  `exclude(...)` covers the type, and `PackageScope.covers` tests `exclude` first, so no
   *  include can override it. Split from [UNEXPORTED_DEPENDENCY_TYPE] only for the hint; both
   *  render as the same `SKIPPED_UNEXPORTED_DEPENDENCY_TYPE` kind, so ADR-109's remedy text
   *  ("this member is skipped by design") stays true. */
  EXCLUDED_DEPENDENCY_TYPE(droppedFromCSharp = true),

  /** The same drop for an `expect` declaration read off a dependency klib: its actualization
   *  lives in that module, so no export scope of this module can reach it. */
  EXPECT_DEPENDENCY_TYPE(droppedFromCSharp = true),

  /** The same drop with cross-module admission off entirely (ADR-066 admission rule 4: neither
   *  `rootPackage` nor `include` is set). `include("<dep>")` alone is a trap here: it replaces the
   *  "everything" default and drops the module's own files, so the hint names `rootPackage` and
   *  the module's own packages instead. */
  CROSS_MODULE_DISABLED_DEPENDENCY_TYPE(droppedFromCSharp = true),

  /** ADR-074: an `expect class` actualized by an `actual typealias` whose erased target the
   *  forward direction does not export (a platform-library type, a stdlib type, an out-of-scope
   *  package, or a parameterized target — v1 admits only a redirect to a plain class). Distinct
   *  from [UNEXPORTED_DEPENDENCY_TYPE], whose `include(...)` hint is wrong here: a platform
   *  library can never be brought into scope. */
  ACTUAL_TYPEALIAS_TARGET(droppedFromCSharp = true),

  /** An `enum class` that no route declares as a C# enum: a nested enum ADR-133 does not declare
   *  (under a deferred owner shape such as an enum or generic owner, or dropped by its CS0102
   *  collision gate), or a module-local top-level enum outside the export scope. Distinct from
   *  [UNEXPORTED_DEPENDENCY_TYPE] because `include(...)` cannot make a nested enum declarable.
   *  Before this reason existed the member was emitted with a dangling C# enum reference and no
   *  diagnostic at all, taking the consumer's compile down with CS0426/CS0234. */
  UNDECLARED_ENUM(droppedFromCSharp = true),

  /** Issue #54: an `interface` nested inside a class, which `rootInterfaces` never declares as a
   *  C# `I{Name}` (it filters `parentDeclaration == null`, exactly as `rootEnums` does). The
   *  [UNDECLARED_ENUM] twin, and separate from it only so the hint can name the right declaration
   *  kind. `include(...)` cannot help here either: no export scope makes a nested interface
   *  declarable. */
  UNDECLARED_INTERFACE(droppedFromCSharp = true),

  /** A plain `class` or `object` nested inside another declaration, which no root bucket declares
   *  (every one of them filters `parentDeclaration == null`) and which the reachability closure
   *  refuses to admit from a dependency module for the same reason. The [UNDECLARED_ENUM] /
   *  [UNDECLARED_INTERFACE] twin, separate only so the hint names the right declaration kind.
   *  Excludes the two nested shapes that ARE declared: a sealed subclass (ADR-009, nested under
   *  its base) and a companion object (ADR-013, its owner's statics). */
  UNDECLARED_CLASS(droppedFromCSharp = true),

  /** A `value class` that no route declares as a C# `readonly record struct`: since ADR-134 a
   *  nested one under an admitted owner IS declared, so reaching this reason means the owner walk
   *  deferred it (a generic or `enum class` owner) or its C# name collided. The [UNDECLARED_CLASS]
   *  twin, separate only so the hint names a record struct. Before this reason existed the member
   *  was emitted with a dangling `Owner.Name` struct reference and no diagnostic at all, taking the
   *  consumer's compile down with CS0426/CS0234. */
  UNDECLARED_VALUE_CLASS(droppedFromCSharp = true),

  /** ADR-133: a Kotlin `object` at a parameter or return position. An object is declared in C# as
   *  a STATIC class, and a static type cannot be a parameter or return type at all (CS0722), so
   *  the member is dropped however the object is declared -- top-level or nested. Distinct from
   *  [UNDECLARED_CLASS], whose remedy (move it to the top level) would not help here. */
  OBJECT_POSITION(droppedFromCSharp = true),

  /** ROADMAP Phase 3 (issue #54): a sealed base at a position the plan does not marshal, which
   *  since ADR-105 means an INPUT position only -- a bare parameter, a nullable one, or a
   *  collection component. A real drop, not a legacy-route deferral: the ADR-009 sealed route
   *  emits the hierarchy's own helpers, never a callable that takes one, so a member skipped here
   *  disappears from the C# API. Named rather than folded into `UNSUPPORTED`/`NULLABLE`, for
   *  ADR-088's reason: the type binds perfectly well at a return or property position, so the
   *  message has to blame the position. */
  SEALED_POSITION(droppedFromCSharp = true),

  /** ADR-088: a bound C# interface at a position v1 does not marshal (nullable, property,
   *  collection component, receiver). Named rather than folded into the generic UNSUPPORTED
   *  bucket: the type IS bridgeable, just not here, and the hint differs accordingly. */
  BOUND_INTERFACE_POSITION(droppedFromCSharp = true),

  /** ADR-088: a bound C# interface at a RETURN position that the manifest flags as not
   *  Kotlin-implementable (no `mint{Iface}Bridge`), so a plain Kotlin implementation of it cannot
   *  be lowered to a C#-side bridge. Parameter positions of the same interface stay admissible:
   *  they only need `nuget{Iface}Value`. */
  UNIMPLEMENTABLE_BOUND_INTERFACE(droppedFromCSharp = true),

  /** ADR-115: the member itself carries a `@RequiresOptIn` marker. Not a bridge limitation: the
   *  declaration is fully supported and is out of scope by the author's own signal, the same
   *  *kind* of skip as [EXCLUDED_DEPENDENCY_TYPE]. */
  OPT_IN_MARKER(droppedFromCSharp = true),

  /** ADR-115: the member's *type* carries a marker, so no C# type is declared for it. Its own
   *  reason rather than [UNEXPORTED_DEPENDENCY_TYPE] (whose `include(...)` hint is actively wrong:
   *  no export scope can admit a marked type) or [UNDECLARED_CLASS] (whose hint names nesting).
   *  Both render through the one `SKIPPED_OPT_IN_MARKER` kind. */
  OPT_IN_MARKER_TYPE(droppedFromCSharp = true),

  /** A backticked Kotlin name that is no identifier (`tug hard`, `a+b`) and
   *  carries no `@CSharpName` (ADR-179): neither the C# member nor the C entry point can be
   *  spelled from it, and the generator does not invent a C# name. [detail] is the Kotlin name. */
  NON_IDENTIFIER_NAME(droppedFromCSharp = true),
}

/**
 * ADR-064's 2026-09-09 amendment (issue #131): which side of the callable a skip is about.
 *
 * A reason like [ForwardPlanSkipReason.NULLABLE] genuinely occurs at both positions, so the kind
 * it renders as cannot be a fixed per-reason mapping: a nullable *parameter* reported
 * `SKIPPED_UNSUPPORTED_RETURN` and sent the author reading a return type that was never the
 * problem. Every other reason ignores this and keeps its own named kind.
 *
 * An extension receiver counts as [INPUT], unnamed: it is a parameter with no author-written name.
 */
internal enum class ForwardSkipPosition { INPUT, RETURN }

internal sealed interface ForwardCallableCatalogEntry {
  val symbol: String

  /**
   * ADR-064 for [Skipped], ADR-095 for [Planned]: the originating declaration. On the top-level and
   * extension routes the emitters keep their declaration walks (their C# grouping needs the
   * declaration itself), so a *planned* entry must be findable by node identity — with overload
   * numbering the symbol is no longer derivable from a declaration.
   */
  val node: KSNode?

  data class Planned(
    val plan: ForwardCallablePlan,
    override val node: KSNode? = null,
    /**
     * ADR-164: the defaulted parameters left required because the callable has more than
     * [MAX_OPTIONAL_DEFAULTS] of them, for the `WARNING_DEFAULT_PARAMETER_CAP_EXCEEDED` diagnostic.
     */
    val cappedDefaults: List<String> = emptyList(),
  ) : ForwardCallableCatalogEntry {
    override val symbol: String = plan.invocation.symbol
  }

  data class Skipped(
    override val symbol: String,
    val reason: ForwardPlanSkipReason,
    // ADR-064: the originating declaration, so the diagnostic sink can point KSP/Gradle at the
    // author's own Kotlin source rather than at generated code. Null only where no single KSNode
    // cleanly represents the skip.
    override val node: KSNode? = null,
    // ADR-066: the unexported dependency type's qualified name, when `reason ==
    // UNEXPORTED_DEPENDENCY_TYPE`. Carries enough for the diagnostic sink to build the
    // `include("<package>")` hint without re-deriving it from the generic reason enum.
    val detail: String? = null,
    // Issue #131: the position the skip is about, and (at an input position, when the offending
    // input is a named parameter rather than an extension receiver) its name. Defaulted to the
    // return position so the return-side skip sites, which are the majority, stay untouched.
    val position: ForwardSkipPosition = ForwardSkipPosition.RETURN,
    val parameter: String? = null,
    /**
     * ADR-064 amendment (2026-09-29): the Kotlin spelling of the declared result (`Crate<Int>?`),
     * set only by the planner's return skip when [reason] is NULLABLE, and read only by that
     * reason's sentence and hint. A dedicated slot rather than [detail], which the property and
     * CIR abstract-member routes key their own wording on.
     */
    val returnType: String? = null,
    /**
     * ADR-064 amendment (2026-09-13): the skip is about the *declaration's own* type parameters
     * (`fun <T> f(value: T): T`), not about a type standing at [position]. Set only by the
     * unrouted-position reclassification, and read by `toDiagnosticKind` to pick
     * `SKIPPED_UNSUPPORTED_COMBINATION`: a structural GENERIC and a type-based one at a return
     * carry the same reason and the same position, and the author's remedy differs.
     */
    val structural: Boolean = false,
    /**
     * ADR-064 amendment (issue #249): the generated C# declaration this drop leaves a hole in.
     * Stamped by [ForwardCallablePlanner.catalog]'s per-owner walk rather than per skip site: the
     * walk is the only place that knows the class being planned, which for an inherited member is
     * not what [node]'s parent reports.
     */
    val owner: ForwardDiagnosticOwner? = null,
    /**
     * ADR-164 on the legacy routes: per parameter, whether it has a Kotlin default, for a
     * `suspend` / `Flow` member that a legacy route binds. Null on every other skip.
     */
    val defaultFlags: List<Boolean>? = null,
    /** ADR-164 rule 6 on the legacy routes: the defaulted parameters the cap leaves required. */
    val cappedDefaults: List<String> = emptyList(),
  ) : ForwardCallableCatalogEntry {

    /** The Kotlin simple name of the dropped member, never the overload-suffixed [symbol]. */
    val memberName: String?
      get() = (node as? KSDeclaration)?.simpleName?.asString()
  }
}

/**
 * Issue #249: stamp one owner onto every skipped entry a per-owner walk produced. A `Planned` entry
 * needs none: it has a C# member of its own and nothing to report.
 */
internal fun List<ForwardCallableCatalogEntry>.ownedBy(
  owner: ForwardDiagnosticOwner?,
): List<ForwardCallableCatalogEntry> = map { entry -> entry.ownedBy(owner) }

internal fun ForwardCallableCatalogEntry.ownedBy(
  owner: ForwardDiagnosticOwner?,
): ForwardCallableCatalogEntry =
  if (this is ForwardCallableCatalogEntry.Skipped) copy(owner = owner) else this

/**
 * ADR-064 amendment (2026-09-13): the deferral reasons whose legacy route exists only at *some*
 * owner/position combinations, so a skip carrying one has to be checked against the route's own
 * gate before it is allowed to stay silent. `SUSPEND` and `ABSTRACT` are absent. The suspend
 * route is NOT keyed to every owner the planner reaches (the ADR-064 audit amendment measured an
 * object, a class companion, a value class and an unreachable interface silent), but each owner
 * the route skips names its SUSPEND deferral directly, through `namedSuspend()`, rather than
 * through a routed predicate here. `ABSTRACT` is declared by the class declaration walk, and named
 * on a sealed arm by `sealedSubclassEntries`.
 */
private val UNROUTED_CANDIDATE_REASONS: Set<ForwardPlanSkipReason> = setOf(
  ForwardPlanSkipReason.GENERIC,
  ForwardPlanSkipReason.FLOW_PROTOCOL,
  ForwardPlanSkipReason.CALLBACK_PROTOCOL,
  ForwardPlanSkipReason.SUSPEND_CALLBACK_PROTOCOL,
)

/**
 * ADR-147: the deferrals a non-generic class leaves to a legacy route, all of which a generic owner
 * drops. SUSPEND included: unlike [UNROUTED_CANDIDATE_REASONS], the suspend route is not keyed to a
 * generic owner (`forwardSuspendRouteMethods` returns nothing for one).
 */
private val GENERIC_OWNER_LEGACY_REASONS: Set<ForwardPlanSkipReason> = setOf(
  ForwardPlanSkipReason.SUSPEND,
  ForwardPlanSkipReason.FLOW_PROTOCOL,
  ForwardPlanSkipReason.CALLBACK_PROTOCOL,
)

/**
 * Whether this member overrides a member an EXPORTED interface declares (ADR-174's forwarding
 * case): that interface binds the member or names it on its own declaration. An unexported one
 * does neither (ADR-064 amendment).
 */
private fun KSFunctionDeclaration.overridesExportedInterfaceMember(exported: Set<String>): Boolean {
  val declaring: KSClassDeclaration =
    findOverridee()?.parentDeclaration as? KSClassDeclaration ?: return false
  return declaring.classKind == ClassKind.INTERFACE &&
      declaring.qualifiedName?.asString() in exported
}

/**
 * ADR-064 amendment (2026-09-13), shaped after ADR-116's sealed reclassification: turn a
 * legacy-route deferral no route re-emits into a named [ForwardPlanSkipReason.UNROUTED_POSITION]
 * drop, carrying the reason it came from as the detail.
 *
 * [isRouted] is the *route's own* selection predicate re-run on the declaration, never an
 * (owner, reason, position) tuple: `fun f(): List<Flow<Int>>` on a class reaches here as
 * `FLOW_PROTOCOL` at `RETURN`, exactly like the routed `fun f(): Flow<Int>`, and only the route's
 * gate ("is the *return type* a Flow") tells them apart.
 */
internal fun ForwardCallableCatalogEntry.nameUnroutedPosition(
  isRouted: (ForwardCallableCatalogEntry.Skipped) -> Boolean,
): ForwardCallableCatalogEntry {
  if (this !is ForwardCallableCatalogEntry.Skipped) return this
  if (reason !in UNROUTED_CANDIDATE_REASONS) return this
  if (isRouted(this)) return this
  return ForwardCallableCatalogEntry.Skipped(
    symbol = symbol,
    reason = ForwardPlanSkipReason.UNROUTED_POSITION,
    node = node,
    detail = reason.name,
    position = position,
    parameter = parameter,
    // The structural producers (`method.typeParameters.isNotEmpty()`) are the only way a GENERIC
    // skip can be about the declaration rather than about a type at a position, and they are
    // recognisable from the declaration itself.
    structural = reason == ForwardPlanSkipReason.GENERIC &&
        (node as? KSFunctionDeclaration)?.typeParameters?.isNotEmpty() == true,
  )
}

/** The [ForwardPlanSkipReason.COMPANION_NO_CARRIER] detail that selects the interface sentence. */
internal const val COMPANION_OWNER_INTERFACE: String = "interface"

/** What [carrierlessCompanionDrops] names: the companion's functions and its properties. */
internal class ForwardCarrierlessCompanion(
  val callables: List<ForwardCallableCatalogEntry>,
  val properties: List<ForwardDroppedProperty>,
)

/**
 * ADR-064 amendment: every public member of this owner's `companion object`, named as a
 * [ForwardPlanSkipReason.COMPANION_NO_CARRIER] drop. For an owner no route renders companion
 * statics for: an interface (whose C# twin declares no statics), a sealed base or arm, a value
 * class. One drop per member, owned by this declaration (issue #249), so the remark lands on the
 * C# type the author would have looked for it on.
 *
 * @param ownerKind the owner kind the sentence names ([COMPANION_OWNER_INTERFACE] for an
 *   interface, which reads its own sentence).
 */
internal fun KSClassDeclaration.carrierlessCompanionDrops(
  ownerKind: String,
): ForwardCarrierlessCompanion {
  val owner: String = qualifiedName?.asString()
    ?: return ForwardCarrierlessCompanion(emptyList(), emptyList())
  val companion: KSClassDeclaration = declarations.filterIsInstance<KSClassDeclaration>()
    .firstOrNull { it.isCompanionObject && it.getVisibility() == Visibility.PUBLIC }
    ?: return ForwardCarrierlessCompanion(emptyList(), emptyList())
  val diagnosticOwner: ForwardDiagnosticOwner = forwardDiagnosticOwner()
  val prefix: String = "$owner.${companion.simpleName.asString()}"
  // Numbered like `companionEntries`, so two overloads stay two entries under two symbols.
  val occurrences: MutableMap<String, Int> = mutableMapOf()
  val callables: List<ForwardCallableCatalogEntry> = companion.getAllFunctions()
    .filter { function -> function.getVisibility() == Visibility.PUBLIC }
    .filter { function -> !function.isCompilerOwnedMember(companion) }
    .map { function ->
      val name: String = function.simpleName.asString()
      val occurrence: Int = occurrences.merge(name, 1, Int::plus)!!
      val suffix: String = if (occurrence == 1) "" else "_$occurrence"
      ForwardCallableCatalogEntry.Skipped(
        symbol = "$prefix.$name$suffix",
        reason = ForwardPlanSkipReason.COMPANION_NO_CARRIER,
        node = function,
        detail = ownerKind,
        owner = diagnosticOwner,
      )
    }
    .toList()
  // `const val` included: the constants route is keyed to an ordinary class's companion too.
  val properties: List<ForwardDroppedProperty> = companion.getAllProperties()
    .filter { property -> property.getVisibility() == Visibility.PUBLIC }
    .filter { property -> !property.isCompilerOwnedMember(companion) }
    .map { property ->
      ForwardDroppedProperty(
        symbol = "$prefix.${property.simpleName.asString()}",
        node = property,
        typeDescription = property.type.toString(),
        reason = ForwardPlanSkipReason.COMPANION_NO_CARRIER,
        detail = ownerKind,
        owner = diagnosticOwner,
      )
    }
    .toList()
  return ForwardCarrierlessCompanion(callables, properties)
}

/** The same reclassification over a producer's whole entry list. */
internal fun List<ForwardCallableCatalogEntry>.nameUnroutedPositions(
  isRouted: (ForwardCallableCatalogEntry.Skipped) -> Boolean = { false },
): List<ForwardCallableCatalogEntry> = map { entry -> entry.nameUnroutedPosition(isRouted) }

/**
 * ADR-082's 2026-08-08 amendment: the declared-vs-inherited signal for value-class members.
 *
 * Every member a supertype declares, indexed for signature comparison. A member is inherited when
 * a supertype declares one of the same *kind* with the same simple name, the same arity, and
 * per-position matching parameter types (resolved qualified name plus nullability). A
 * supertype-side type *parameter* matches any argument type, deliberately conservatively: it
 * over-drops (loud diagnostic, non-colliding name as workaround) rather than exporting something
 * that may be a delegation forwarder.
 *
 * The simple-name rule this replaces also dropped author-declared members whose name merely
 * collided with a supertype's (`fun get(key: String)` next to `CharSequence.get(index: Int)`).
 * Properties keep the name-only comparison, and only against supertype *properties*: Kotlin
 * properties cannot overload.
 */
internal class ForwardSupertypeMembers private constructor(
  private val propertyNames: Set<String>,
  private val functions: List<List<String?>>,
) {
  fun declares(property: KSPropertyDeclaration): Boolean =
    property.simpleName.asString() in propertyNames

  /**
   * The wildcard comparison itself lives in `ForwardClassMembership.kt` beside the strict key
   * ([forwardInheritedSignatureKey] / [admits]), shared with `baseClassOverridee`'s base-class
   * fallback, so the two comparisons cannot drift.
   *
   * The wildcard is structural, not top-level, because the key is: once `forwardTypeKey()` recurses
   * into type arguments, a supertype's `holds(items: List<T>)` spells
   * `kotlin.collections.List<T>` while the value class's delegated or overriding
   * `holds(items: List<String>)` spells `kotlin.collections.List<kotlin.String>`, and a
   * top-level-only wildcard would stop matching the two. That member would leak out of
   * `INHERITED_MEMBER` and render a delegation forwarder. Over-matching is the direction ADR-082
   * already chose here.
   *
   * Both keys now carry the extension receiver, which this comparison used to leave out: a
   * supertype's plain `fun f(x: Int)` no longer claims a value class's own `fun String.f(x: Int)`.
   * No shipped fixture has a supertype-declared member extension, so this moves nothing today.
   */
  fun declares(function: KSFunctionDeclaration): Boolean {
    val key: List<String> = function.forwardSignatureKey()
    return functions.any { inherited -> inherited.admits(key) }
  }

  companion object {
    fun of(cls: KSClassDeclaration): ForwardSupertypeMembers {
      val superTypes: List<KSClassDeclaration> = cls.getAllSuperTypes()
        .mapNotNull { superType -> superType.declaration as? KSClassDeclaration }
        .toList()
      return ForwardSupertypeMembers(
        propertyNames = superTypes
          .flatMap { superType ->
            superType.getAllProperties().map { property -> property.simpleName.asString() }
          }
          .toSet(),
        functions = superTypes.flatMap { superType ->
          superType.getAllFunctions().map { function -> function.forwardInheritedSignatureKey() }
        },
      )
    }
  }
}

/**
 * Complete planning result for the first migration slice. Every callable inspected by this
 * planner is either [ForwardCallableCatalogEntry.Planned] or explicitly [Skipped]; no raw KSP
 * type or implicit fallback reaches the emission phase.
 */
internal data class ForwardCallablePlanCatalog(
  val entries: List<ForwardCallableCatalogEntry>,
  val propertyPlans: List<ForwardPropertyPlan> = emptyList(),
  // ADR-075: the property planner's own diagnostic channel — a mutable collection property
  // planned with `setter = null` because a component failed `isWrappableComponent()`, so the
  // consumer learns the C# property survives read-only rather than silently losing its setter.
  val droppedPropertySetters: List<ForwardDroppedPropertySetter> = emptyList(),
  // The property planner's whole-property channel: a property whose type it cannot plan at all,
  // minus the ones a legacy route still re-emits. Separate from droppedPropertySetters above,
  // which is a partial skip (the getter survives).
  val droppedProperties: List<ForwardDroppedProperty> = emptyList(),
  // The receiver-side counterpart of droppedProperties: an extension property dropped for its
  // receiver type rather than its own type. Same diagnostic kind, different wording.
  val droppedExtensionReceivers: List<ForwardDroppedExtensionReceiver> = emptyList(),
) {
  val plans: List<ForwardCallablePlan> = entries.mapNotNull { entry ->
    (entry as? ForwardCallableCatalogEntry.Planned)?.plan
  }

  fun propertyFor(symbol: String): ForwardPropertyPlan? {
    // ADR-006 amendment: an ENUM_MEMBER plan is keyed `pkg.Mood.x`, which is also the key of a
    // (shadowed) extension `val Mood.x` in the same package. Enum member plans are never looked up
    // by symbol (both halves select them by position through `enumMembersOf`), so they are out of
    // this lookup's universe by position: it cannot return the member's plan to the extension
    // route, nor trip the duplicate invariant below on a legal Kotlin pair. Extension plans are
    // looked up by declaration ([extensionPropertyFor]), so they are out of it too.
    val matches: List<ForwardPropertyPlan> = propertyPlans.filter { plan ->
      plan.symbol == symbol && plan.position != ForwardPropertyPosition.ENUM_MEMBER &&
          plan.position != ForwardPropertyPosition.EXTENSION
    }
    // ADR-074: this invariant must be unreachable once the `allDeclarations` funnel filters
    // `isExpect` (an unfiltered expect/actual pair is what used to trip it). A fresh firing means
    // a *new* source of duplicate qualified names, not this one.
    require(matches.size <= 1) {
      "Forward property catalog has duplicate plans for $symbol; two declarations share one " +
          "qualified name (an unfiltered expect/actual pair is the usual cause)"
    }
    return matches.singleOrNull()
  }

  /**
   * The EXTENSION property plan of [prop], and never a member's. A class member `Leash.x` and an
   * extension `val Leash.x` (or `val Leash?.x`) declared in the member's own package are both keyed
   * `pkg.Leash.x`, so a lookup that did not split them by position would hand the member's plan to
   * the extension route (a shadowed extension, dropped by name, then failed the C# projection's
   * position check) or trip the duplicate invariant on a legal Kotlin pair.
   *
   * ADR-132 amendment (2026-10-04): keyed on the declaration rather than on a symbol string each
   * renderer re-spells, and split by the receiver's nullability. A nullable value-type
   * `val Int?.x` keys `pkg.Int?.x` ([extensionPropertySymbol]); a renderer cannot classify, so it
   * asks for both spellings and the nullability decides. That split is also what stops a DROPPED `val Int?.x`
   * from being handed its planned `val Int.x` twin's plan, which rendered the twin twice (a fatal
   * C# signature collision on a pair that should have built).
   */
  fun extensionPropertyFor(prop: KSPropertyDeclaration): ForwardPropertyPlan? {
    val nullable: Boolean =
      prop.extensionReceiver?.resolve()?.expandAliases()?.isMarkedNullable == true
    val symbols: Set<String> = setOf(
      extensionPropertySymbol(prop, nullableValueReceiver = false),
      extensionPropertySymbol(prop, nullableValueReceiver = true),
    )
    val matches: List<ForwardPropertyPlan> = propertyPlans.filter { plan ->
      val receiver: ForwardPropertyReceiver.Value? = plan.receiver as? ForwardPropertyReceiver.Value
      plan.position == ForwardPropertyPosition.EXTENSION && plan.symbol in symbols &&
          (receiver?.type is BridgeType.Nullable) == nullable
    }
    require(matches.size <= 1) {
      "Forward property catalog has duplicate extension plans for ${symbols.first()}; two " +
          "declarations share one qualified name (an unfiltered expect/actual pair is the usual " +
          "cause)"
    }
    return matches.singleOrNull()
  }

  /** The skipped callables that no legacy route re-emits, so they vanish from the C# API. */
  val droppedCallables: List<ForwardCallableCatalogEntry.Skipped> = entries
    .filterIsInstance<ForwardCallableCatalogEntry.Skipped>()
    .filter { entry -> entry.reason.droppedFromCSharp }

  /**
   * ADR-082: the planned value-class property getters of [owner], in planning order.
   *
   * Both emitters read their member plans off the catalog rather than re-deriving a plan key per
   * `getAllProperties()` / `getAllFunctions()` entry. Symbol-per-declaration re-derivation breaks
   * as soon as two declared members share a simple name (the overload numbering lives in the
   * planner, and a second `getAllFunctions()` entry re-emitted the *first* one's plan).
   * Constructors are excluded: their reference-underlying branch still has a legacy route that
   * needs the declaration itself.
   */
  fun valueClassProperties(owner: String): List<ForwardCallablePlan> = valueClassMembers(owner)
    .filter { plan -> plan.invocation.target?.endsWith("#property") == true }

  /**
   * ADR-171: the box/unbox pair of [owner], in that order, or `null` when the value class has none
   * (its underlying cannot cross, so no erased generic position can carry it either). The planner
   * adds both or neither, so a caller never renders half a pair.
   */
  fun valueClassBoxing(owner: String): Pair<ForwardCallablePlan, ForwardCallablePlan>? {
    val pair: List<ForwardCallablePlan> = plans.filter { plan ->
      plan.invocation.origin == ForwardCallableOrigin.VALUE_CLASS_BOX &&
          plan.invocation.symbol.substringBeforeLast('.') == owner
    }
    if (pair.size != 2) return null
    return pair[0] to pair[1]
  }

  /**
   * ADR-171: every value class with a box/unbox pair, the set a lambda type argument may name. A
   * value class outside it has no erased crossing, so a lambda over it is refused by name at build
   * time rather than bound and left to throw in `Wrap<T>` / `Materialize<T>`.
   */
  val boxedValueClasses: Set<String> by lazy {
    plans
      .filter { plan -> plan.invocation.origin == ForwardCallableOrigin.VALUE_CLASS_BOX }
      .mapTo(mutableSetOf()) { plan -> plan.invocation.symbol.substringBeforeLast('.') }
  }

  /** ADR-082: the planned value-class methods of [owner], in planning order. See above. */
  fun valueClassMethods(owner: String): List<ForwardCallablePlan> = valueClassMembers(owner)
    .filter { plan -> plan.invocation.target?.endsWith("#property") != true }

  /**
   * ADR-090: the planned ordinary-class methods of [owner], in planning order.
   *
   * Same reason as [valueClassMethods]: with overload numbering in the planner, a symbol is no
   * longer derivable from a `getAllFunctions()` entry, so both emitters read the member plans off
   * the catalog instead. The owner filter is *exact*, not a prefix: `interfaceEntries` also emits
   * CLASS-origin plans, keyed by the interface's own qualified name. Constructors are excluded
   * (their own origin aside, `<init>` never belongs to the method surface).
   */
  fun classMethods(owner: String): List<ForwardCallablePlan> = plans.filter { plan ->
    plan.invocation.origin == ForwardCallableOrigin.CLASS &&
        plan.invocation.symbol.substringBeforeLast('.') == owner &&
        !plan.invocation.symbol.substringAfterLast('.').startsWith("<init>")
  }

  /**
   * ADR-091: the planned constructors of [owner], in planning order (primary, then secondaries).
   *
   * Same reason as [classMethods]: both emitters read constructors off the catalog instead of
   * re-deriving a plan key per declaration. Owner-exact matching.
   */
  // ADR-157: a boxed enum arm's constructor answers here too. It is a constructor at every site
  // that reads this query (the Kotlin export loop, the CIR arm projection, the C# renderer); only
  // its Kotlin invocation differs, and that is the emitter's business, not the catalog's.
  fun constructors(owner: String): List<ForwardCallablePlan> = plans.filter { plan ->
    (plan.invocation.origin == ForwardCallableOrigin.CONSTRUCTOR ||
        plan.invocation.origin == ForwardCallableOrigin.ENUM_ARM_BOX) &&
        plan.invocation.symbol.substringBeforeLast('.') == owner
  }

  /**
   * The constructors of [owner] the planner refused, in planning order, whatever the reason.
   *
   * Deliberately not filtered by [ForwardPlanSkipReason.droppedFromCSharp], unlike
   * [droppedCallables]: that flag says a *method* still binds through a legacy route, and no
   * legacy route re-emits a constructor, so a `GENERIC` constructor skip is just as absent from
   * the C# surface as an `UNDECLARED_ENUM` one.
   *
   * Owner-exact, and matched on the `<init>` name so a data class's `copy` (its own origin,
   * planned from the same primary constructor) never counts as one.
   */
  fun skippedConstructors(owner: String): List<ForwardCallableCatalogEntry.Skipped> = entries
    .filterIsInstance<ForwardCallableCatalogEntry.Skipped>()
    .filter { entry ->
      entry.symbol.substringBeforeLast('.') == owner &&
          entry.symbol.substringAfterLast('.').startsWith("<init>")
    }

  /**
   * ADR-095: the planned members of object [owner], in planning order.
   *
   * Same reason as [classMethods]: with per-object overload numbering the symbol of the n-th
   * namesake is `$owner.${name}_$n`, which no `getAllFunctions()` walk can re-derive, so both
   * emitters read the object's members off the catalog. Owner-exact, not prefix.
   */
  fun objectMethods(owner: String): List<ForwardCallablePlan> = plans.filter { plan ->
    plan.invocation.origin == ForwardCallableOrigin.OBJECT &&
        plan.invocation.symbol.substringBeforeLast('.') == owner
  }

  /**
   * ADR-006 amendment: the planned member functions of enum [owner], in planning order. Off the
   * catalog for the reason [objectMethods] is: an overload's `_$n` symbol is underivable.
   */
  fun enumMethods(owner: String): List<ForwardCallablePlan> = plans.filter { plan ->
    plan.invocation.origin == ForwardCallableOrigin.ENUM_MEMBER &&
        plan.invocation.symbol.substringBeforeLast('.') == owner
  }

  /** ADR-094 (write side): the box of enum [owner], or null when none was planned. */
  fun enumBox(owner: String): ForwardCallablePlan? = plans.firstOrNull { plan ->
    plan.invocation.origin == ForwardCallableOrigin.ENUM_BOX &&
        plan.invocation.symbol.substringBeforeLast('.') == owner
  }

  /** ADR-006 amendment: the planned companion `val`/`var`s of enum [owner], in planning order. */
  fun enumCompanionProperties(owner: String): List<ForwardPropertyPlan> =
    propertyPlans.filter { plan ->
      plan.position == ForwardPropertyPosition.COMPANION &&
          plan.symbol.substringBeforeLast('.') == "$owner.Companion"
    }

  /** ADR-095: the planned companion members of class [owner], in planning order. See above. */
  fun companionMethods(owner: String): List<ForwardCallablePlan> = plans.filter { plan ->
    plan.invocation.origin == ForwardCallableOrigin.COMPANION &&
        plan.invocation.symbol.substringBeforeLast('.') == "$owner.Companion"
  }

  /**
   * ADR-095: the plan for [declaration], matched by node identity rather than by symbol.
   *
   * The top-level and extension emitters keep their declaration walks — the C# halves group by
   * (namespace, file class) and by receiver simple name, and the Kotlin top-level loop has a
   * per-declaration legacy fallback — so an owner-keyed accessor cannot replace them. Identity
   * matching is sound because `NugetProcessor` collects `functions` / `extensionFunctions` once and
   * hands the *same list instances* to the planner and to both emitters (verified).
   *
   * Returns null only for a declaration this planner explicitly skipped; a declaration the catalog
   * never saw is a wiring bug and fails loudly rather than silently binding to a namesake's plan.
   */
  fun planFor(declaration: KSFunctionDeclaration): ForwardCallablePlan? {
    val matches: List<ForwardCallableCatalogEntry> = entries
      .filter { entry -> entry.node === declaration }
    require(matches.isNotEmpty()) {
      "Forward callable catalog has no entry for " +
          "${declaration.qualifiedName?.asString() ?: declaration.simpleName.asString()}; the " +
          "emitter is walking a declaration list the planner never saw"
    }
    val planned: List<ForwardCallableCatalogEntry.Planned> = matches
      .filterIsInstance<ForwardCallableCatalogEntry.Planned>()
    // ADR-074: a route planning one declaration twice is a wiring bug.
    require(planned.size <= 1) {
      "Forward callable catalog has ${planned.size} plans for one declaration of " +
          "${declaration.simpleName.asString()}; a route planned it more than once"
    }
    return planned.singleOrNull()?.plan
  }

  /**
   * Issue #97: the ADR-090 overload suffix (`""` or `"_$n"`) of [declaration], read back off its
   * catalog entry by node identity. The Flow/StateFlow legacy route never gets a plan (the planner
   * skips it as `FLOW_PROTOCOL`) but it does consume a number, and its `_collect` / `_value` /
   * `_has_value` / `_set_value` entry points must carry that number or two same-name overloads
   * collide on one C symbol.
   *
   * Lenient where [planFor] is strict: `CirClassTranslator`'s flow walk admits an inherited
   * abstract interface member the planner filters out, and that member has no number to carry.
   */
  fun overloadSuffix(declaration: KSFunctionDeclaration): String {
    val entry: ForwardCallableCatalogEntry = entries
      .firstOrNull { entry -> entry.node === declaration } ?: return ""
    val name: String = declaration.simpleName.asString()
    val tail: String = entry.symbol.substringAfterLast('.')
    return if (tail.startsWith(name)) tail.substring(name.length) else ""
  }

  /**
   * ADR-164 on the legacy routes: per parameter of [declaration], whether it has a Kotlin default,
   * read back off its catalog entry by node identity so the Kotlin export and the C# translator
   * answer from one reading. Lenient like [overloadSuffix]: a member the planner never saw reads
   * its own `hasDefault` bits.
   */
  fun legacyDefaultFlags(declaration: KSFunctionDeclaration): List<Boolean> =
    entries.firstNotNullOfOrNull { entry ->
      (entry as? ForwardCallableCatalogEntry.Skipped)
        ?.takeIf { it.node === declaration }
        ?.defaultFlags
    } ?: declaration.parameters.map { parameter -> parameter.hasDefault }

  /**
   * ADR-164 on the legacy `suspend` routes: the parameter counts of [declaration]'s same-name
   * `suspend` siblings on the same owner. Each renders `XAsync(..., CancellationToken = default)`,
   * so a sibling whose arity a widened call could also stop at makes that call CS0121: the caller
   * of `CountAsync()` cannot tell `count()` from `count(limit = 3)` once `limit` is optional. The
   * C# half keeps the parameter at each such arity required-but-nullable instead. Arity only, not
   * types: a false positive costs one `= null`, a miss costs the consumer's build.
   */
  fun legacySuspendSiblingArities(declaration: KSFunctionDeclaration): Set<Int> {
    // A `Flow` member has no trailing token and no `Async` name: `Ticks()` still prefers the
    // overload that omits nothing.
    if (!declaration.modifiers.contains(Modifier.SUSPEND)) return emptySet()
    val own: ForwardCallableCatalogEntry =
      entries.firstOrNull { entry -> entry.node === declaration } ?: return emptySet()
    val owner: String = own.symbol.substringBeforeLast('.')
    val name: String = declaration.simpleName.asString()
    return entries.asSequence()
      .filterIsInstance<ForwardCallableCatalogEntry.Skipped>()
      .filter { entry -> entry.reason == ForwardPlanSkipReason.SUSPEND }
      .filter { entry ->
        entry.node !== declaration && entry.symbol.substringBeforeLast('.') == owner
      }
      .mapNotNull { entry -> entry.node as? KSFunctionDeclaration }
      .filter { sibling -> sibling.simpleName.asString() == name }
      .map { sibling -> sibling.parameters.size }
      .toSet()
  }

  private fun valueClassMembers(owner: String): List<ForwardCallablePlan> = plans.filter { plan ->
    plan.invocation.origin == ForwardCallableOrigin.VALUE_CLASS &&
        plan.invocation.symbol.substringBeforeLast('.') == owner &&
        !plan.invocation.symbol.substringAfterLast('.').startsWith("<init>")
  }
}

/**
 * Builds the shadow plan for ordinary synchronous class methods and primitive-receiver extension
 * functions. This phase intentionally does not hand its plans to either renderer.
 */
internal class ForwardCallablePlanner(
  private val classifier: ForwardBridgeTypeClassifier,
  /**
   * ADR-163: the one forward symbol table. Every export name a plan carries is library- and
   * package-qualified through it, so the plan's two projections cannot disagree and two
   * same-simple-name owners in two packages no longer derive one C entry point.
   */
  private val symbols: ForwardSymbolTable,
  /**
   * ADR-091: the ADR-074 expect index. Only source of parameter defaults for an `expect`/`actual`
   * pair, whose `actual` (the export root) always reports `hasDefault = false`: constructors,
   * top-level functions and extensions, and (ADR-074 amendment, 2026-09-27) members of an
   * `actual` class, interface, object or companion.
   */
  private val expects: ExpectIndex = ExpectIndex(),
) {
  fun catalog(
    classes: List<KSClassDeclaration>,
    functions: List<KSFunctionDeclaration>,
    extensionFunctions: List<KSFunctionDeclaration>,
    objects: List<KSClassDeclaration>,
    properties: List<KSPropertyDeclaration>,
    extensionProperties: List<KSPropertyDeclaration>,
    valueClasses: List<KSClassDeclaration> = emptyList(),
    // ADR-111: sealed bases, whose subclass properties plan alongside the ordinary class ones.
    sealedClasses: List<KSClassDeclaration> = emptyList(),
    // ADR-006 amendment: enums, whose own member properties plan as ENUM_MEMBER.
    enums: List<KSClassDeclaration> = emptyList(),
  ): ForwardCallablePlanCatalog {
    topLevelFunctions = functions
    topLevelExtensions = extensionFunctions
    // ADR-064 amendment: the companions no walk below renders (ADR-013 folds only an ordinary
    // class's companion into the class, ADR-006 an enum's into `{Enum}Extensions`). An enum arm's
    // enum is planned on the enum route, companion included.
    // Keyed by declaration: a nested sealed base is also an arm of its parent, and is named once.
    val carrierlessOwners: Map<KSClassDeclaration, String> = buildMap {
      sealedClasses.forEach { sealed ->
        sealed.getSealedSubclasses()
          .filterNot { sub -> sub.isEnumArm() }
          .forEach { sub -> put(sub, "sealed subclass") }
      }
      sealedClasses.forEach { sealed ->
        val kind: String =
          if (sealed.classKind == ClassKind.INTERFACE) "sealed interface" else "sealed class"
        put(sealed, kind)
      }
      valueClasses.forEach { cls -> put(cls, "value class") }
    }
    val carrierless: List<ForwardCarrierlessCompanion> = carrierlessOwners
      .entries
      .distinctBy { (owner, _) -> owner.qualifiedName?.asString() }
      .map { (owner, kind) -> owner.carrierlessCompanionDrops(kind) }
    val entries: List<ForwardCallableCatalogEntry> = buildList {
      // Issue #249: every walk below stamps the C# owner of what it planned onto its skips, so a
      // dropped member can be named on the declaration that would have declared it. Constructors
      // are deliberately unstamped: ADR-064's WARNING_NO_PUBLIC_CONSTRUCTOR remark already names
      // that hole on the class, and a paragraph per refused `<init>` would say it twice.
      classes.forEach { cls -> addAll(classEntries(cls).ownedBy(cls.forwardDiagnosticOwner())) }
      // ADR-116: the method half of ADR-111. A sealed subclass is deliberately absent from
      // `classes` (ADR-009 / issue #54), so its declared member functions have to be planned from
      // the sealed base, under the same `${sealed}_${sub}` prefix the property getters already use.
      sealedClasses.forEach { sealed ->
        // ADR-116 amendment (2026-09-11): the base's own declared members first, so an arm's
        // projection can ask whether the C# base already carries the signature it is about to
        // spell (`override` when it matches, nothing at all when the arm declares none).
        val base: List<ForwardCallableCatalogEntry> =
          sealedBaseEntries(sealed).ownedBy(sealed.forwardDiagnosticOwner())
        addAll(base)
        sealed.getSealedSubclasses().forEach { sub ->
          // ADR-157: an enum arm has no members of its own on this route. What Kotlin declares on
          // the enum belongs to `{Enum}Extensions` (ADR-006) and is planned there; the arm carries
          // the box constructor (below) and `Value` (the property planner) and nothing else.
          if (sub.isEnumArm()) return@forEach
          addAll(sealedSubclassEntries(sealed, sub).ownedBy(sub.forwardDiagnosticOwner()))
        }
      }
      classes.forEach { cls -> addAll(constructorEntries(cls)) }
      // ADR-148: the constructor half of ADR-111/ADR-116. A sealed subclass of kind `CLASS` is
      // absent from `classes` (ADR-009 / issue #54), so its public constructors were never
      // collected and every arm shipped with only its internal handle constructor. They plan
      // through the very same `constructorEntries` an ordinary class's do, under the arm's own
      // `${sealed}_${arm}` export prefix (the one its properties and methods already use), so the
      // ABI, the overload numbering, the ADR-115 marker gate and the ADR-105 sealed-parameter
      // rewrite are an ordinary class's by construction. `copy` is deliberately off: the sealed
      // route emits a data arm's `copy` nowhere, and an entry no C# member reads would be an
      // export with no import.
      sealedClasses.forEach { sealed ->
        sealed.getSealedSubclasses()
          .filter { sub -> sub.classKind == ClassKind.CLASS }
          // A sealed intermediate arm is never instantiated; Kotlin rejects `Fault()` outright.
          .filter { sub -> Modifier.SEALED !in sub.modifiers }
          .forEach { sub ->
            addAll(
              constructorEntries(
                sub,
                prefix = "${sealed.nativePrefix(symbols)}_${sub.simpleName.asString().lowercase()}",
                copy = false,
              )
            )
          }
        // ADR-157: the boxed enum arm's one constructor, `new PatchArm(Patch.Socks)`, planned
        // through the same `planOrSkip` an ordinary constructor goes through so its enum lowering,
        // its error slot and its ABI contract entry are the ordinary route's. The Kotlin
        // invocation is the identity on the lowered argument (`Patch.entries[value]`).
        sealed.getSealedSubclasses()
          .filter { sub -> sub.isEnumArm() }
          .forEach { sub -> addAll(enumArmBoxEntries(sealed, sub)) }
      }
      // ADR-095: top-level and extension overloads number per (package, name), the extension one
      // deliberately receiver-agnostic because its plan symbol is (`fun Cat.pat()` then
      // `fun Dog.pat()` in one package are one counter). Both counters live here rather than in the
      // per-declaration entry builders, because the scope spans the whole collected list.
      // ROADMAP line 29 (ADR-118 amendment): `functions` carries the top-level `suspend` functions
      // too, in declaration order, so a suspend overload shares this counter with its ordinary
      // namesakes (`ping`, `ping_2_async`) exactly as a class's suspend method does. It lands as
      // a silent SUSPEND skip that the top-level suspend route reads its number from.
      val topLevelOccurrences: MutableMap<String, Int> = mutableMapOf()
      val topLevel: List<ForwardCallableCatalogEntry> = functions.map { function ->
        topLevelEntry(function, overloadSuffix(topLevelOccurrences, function))
          // Issue #249: per declaration, not per walk -- one call to `catalog` covers every file.
          .ownedBy(function.forwardFileClassOwner())
      }
      addAll(topLevel)
      objects.forEach { obj -> addAll(objectEntries(obj).ownedBy(obj.forwardDiagnosticOwner())) }
      // ADR-013 renders a companion's members as the owning class's statics, so the hole is on the
      // class -- which is what `forwardDiagnosticOwner()` returns for a companion.
      classes.forEach { cls -> addAll(companionEntries(cls).ownedBy(cls.forwardDiagnosticOwner())) }
      // ADR-006 amendment: an enum's own functions and its companion's functions, both rendered in
      // `{Enum}Extensions`, so a skip is named on the enum. A suspend companion member is named by
      // `companionEntries` itself, as it is for a class companion.
      enums.forEach { enum ->
        addAll(enumEntries(enum).ownedBy(enum.forwardDiagnosticOwner()))
        addAll(companionEntries(enum).ownedBy(enum.forwardDiagnosticOwner()))
        addAll(enumBoxEntries(enum).ownedBy(enum.forwardDiagnosticOwner()))
      }
      valueClasses.forEach { cls ->
        addAll(valueClassEntries(cls).ownedBy(cls.forwardDiagnosticOwner()))
      }
      // Planned after every member route, so the entry points those routes minted are known: a
      // member and an extension of one name on one receiver, declared in ONE package, derive the
      // same `<lib>_<pkg>__<owner>_<name>` (the member's prefix and the extension's qualifier are
      // then the same package), and only the extension moves to the marked spelling.
      // The hand-written callback routes (a stored-callback or interface-bridge `add`/`remove`
      // pair, a per-call lambda member the plan does not own) mint `<owner>_<name>` too, outside
      // the catalog, so their entry points join through the same selectors their emitters read.
      val taken: Set<String> = filterIsInstance<ForwardCallableCatalogEntry.Planned>()
        .filter { entry -> entry.plan.invocation.origin != ForwardCallableOrigin.EXTENSION }
        .flatMap { entry -> entry.plan.nativeExports.map { call -> call.exportName } }
        .toSet() + legacyCallbackExports(classes, sealedClasses)
      val extensionOccurrences: MutableMap<String, Int> = mutableMapOf()
      val extensions: List<ForwardCallableCatalogEntry> = extensionFunctions.map { function ->
        extensionEntry(
          function,
          overloadSuffix(extensionOccurrences, function, function.extensionOwnerChain()),
          taken,
        )
      }
      addAll(extensions)
      carrierless.forEach { companion -> addAll(companion.callables) }
    }
    val planner = ForwardPropertyPlanner(classifier, symbols, expects)
    // Every entry point the callable catalog minted, the extension functions' included: an
    // extension property accessor that would spell one (a member `fun get_x()` beside a
    // same-package `val Leash?.x`) moves to the `ext` role word instead of colliding with it.
    val callableExports: Set<String> = entries
      .filterIsInstance<ForwardCallableCatalogEntry.Planned>()
      .flatMap { entry -> entry.plan.nativeExports.map { call -> call.exportName } }
      .toSet() + legacyCallbackExports(classes, sealedClasses)
    val propertyPlans: List<ForwardPropertyPlan> = planner.catalog(
      classes, properties, extensionProperties, sealedClasses, objects, enums,
      extensionFunctions = extensionFunctions,
      callableExports = callableExports,
    )
    return ForwardCallablePlanCatalog(
      entries.map { entry -> entry.withLegacyDefaults() },
      propertyPlans, planner.droppedPropertySetters,
      planner.droppedProperties + carrierless.flatMap { companion -> companion.properties },
      planner.droppedExtensionReceivers,
    )
  }

  /**
   * The `<owner>_<name>` entry points the hand-written callback routes export for [classes] and
   * for the arms of [sealedClasses], read off the very selectors `addClassExports` and the
   * sealed-arm loops in `NugetProcessor` emit from, under the same prefixes.
   */
  private fun legacyCallbackExports(
    classes: List<KSClassDeclaration>,
    sealedClasses: List<KSClassDeclaration>,
  ): Set<String> = buildSet {
    classes.forEach { cls ->
      val superClass: KSClassDeclaration? =
        cls.forwardSuperClass(classifier.exportedObjectHandles)
      addAll(
        cls.forwardClassLegacyMembers(classifier, superClass)
          .callbackExportNames(cls.nativePrefix(symbols))
      )
    }
    sealedClasses.forEach { sealed ->
      val sealedPrefix: String = sealed.nativePrefix(symbols)
      sealed.getSealedSubclasses().forEach { arm ->
        val armPrefix = "${sealedPrefix}_${arm.simpleName.asString().lowercase()}"
        val members: List<KSFunctionDeclaration> = arm.forwardArmLambdaMethods(classifier) +
          arm.forwardArmStoredCallbackPairs(classifier).flatMap { pair -> pair.toList() } +
          arm.forwardArmInterfaceBridgePairs(classifier).flatMap { pair -> pair.toList() }
        members.forEach { member -> add("${armPrefix}_${member.simpleName.asString()}") }
      }
    }
  }

  /**
   * ADR-064 amendment: whether the type declaring this member runs a planning pass of its own (an
   * exported class, interface or sealed base), and so names the member's unrouted positions there.
   */
  private fun KSFunctionDeclaration.isDeclaredOnExportedType(): Boolean =
    (parentDeclaration as? KSClassDeclaration)?.qualifiedName?.asString() in
        classifier.exportedObjectHandles

  /**
   * ADR-164 on the legacy routes: a `suspend` or `Flow`-returning member never gets a plan, but it
   * is numbered here, and here is the one place the flag reader lives (the member's override
   * chain plus the ADR-074 expect index, the only way an `actual suspend fun`, top-level or a
   * member of an `actual class`, reports the default its `expect` declares). So the flags ride on
   * the skip entry and both halves read them back through
   * [ForwardCallablePlanCatalog.legacyDefaultFlags].
   */
  private fun ForwardCallableCatalogEntry.withLegacyDefaults(): ForwardCallableCatalogEntry {
    if (this !is ForwardCallableCatalogEntry.Skipped) return this
    if (reason != ForwardPlanSkipReason.SUSPEND && reason != ForwardPlanSkipReason.FLOW_PROTOCOL) {
      return this
    }
    val function: KSFunctionDeclaration = node as? KSFunctionDeclaration ?: return this
    // A top-level function has no overridee, so this is exactly [expectDefaultFlags] for it.
    val flags: List<Boolean> = memberDefaultFlags(function)
    return copy(
      defaultFlags = flags,
      cappedDefaults = classifier.legacyCappedDefaults(function.parameters, flags),
    )
  }

  /**
   * Value-class constructors (ADR-035 primary/secondary numbering), non-underlying public
   * properties, and public methods. Members keep the shipped no-errorOut ABI; only constructors
   * carry an error slot. The receiver is always the *underlying* wire value (primitive/String as
   * a Value named `value`, reference as a Handle named `handle`).
   */
  private fun valueClassEntries(cls: KSClassDeclaration): List<ForwardCallableCatalogEntry> {
    val owner: String = cls.qualifiedName?.asString() ?: return emptyList()
    val prefix: String = cls.nativePrefix(symbols)
    val underlyingParam = cls.primaryConstructor?.parameters?.firstOrNull() ?: return emptyList()
    val underlyingPropName: String = underlyingParam.name?.asString() ?: return emptyList()
    val classifiedUnderlying: BridgeType = classifier.classify(underlyingParam.type.resolve())
    // Sealed (and other handle-shaped specialized) underlyings still cross as StableRef handles
    // on the shipped ABI — same as ObjectHandle. Map them to a Handle receiver so methods like
    // ObservationResult.describe keep working after ordinary legacy deletion.
    val underlyingType: BridgeType = if (
      classifiedUnderlying is BridgeType.SpecializedProtocol &&
      classifiedUnderlying.name.startsWith("sealed helper ")
    ) {
      BridgeType.ObjectHandle(classifiedUnderlying.name.removePrefix("sealed helper "))
    } else {
      classifiedUnderlying
    }
    // The one shared underlying rule (`ForwardValueClassUnderlying`): the Kotlin half emits exactly
    // the constructors planned here, so the two can no longer disagree about a `_create` export.
    val role: ForwardValueClassUnderlying = classifier.valueClassUnderlying(cls)
    if (role == ForwardValueClassUnderlying.REFUSED) return emptyList()
    val isReferenceUnderlying: Boolean = role == ForwardValueClassUnderlying.REFERENCE

    val receiver: ForwardReceiver = if (isReferenceUnderlying) {
      ForwardReceiver.Handle(underlyingType, name = "handle")
    } else {
      ForwardReceiver.Value(underlyingType, name = "value")
    }

    // ADR-066 amendment to ADR-064's SKIPPED_INHERITED_MEMBER filter: `origin != Origin.KOTLIN`
    // is wrong once a value class can live in a dependency module — every member of a KOTLIN_LIB
    // declaration (author-declared or not) reports `origin == KOTLIN_LIB`, so the old rule would
    // drop the entire cross-module value class, not just its delegated members. The only
    // origin-independent signal available, verified against a real klib, is "a supertype declares
    // this member" — this also correctly catches interface delegation (`CharSequence by value`),
    // which forwards members with `parentDeclaration == cls` and is otherwise indistinguishable
    // from a hand-written member. ADR-082's 2026-08-08 amendment narrows the comparison from
    // simple names to signatures; see [ForwardSupertypeMembers]. Computed once per class: cheap
    // relative to walking every member, and `getAllSuperTypes()` is documented as expensive.
    val inherited: ForwardSupertypeMembers = ForwardSupertypeMembers.of(cls)

    return buildList {
      addAll(
        valueClassConstructorEntries(
          cls,
          owner,
          prefix,
          underlyingPropName,
          underlyingType,
          isReferenceUnderlying
        )
      )
      addAll(
        valueClassPropertyEntries(cls, owner, prefix, underlyingPropName, receiver, inherited),
      )
      addAll(valueClassMethodEntries(cls, owner, prefix, receiver, inherited))
      addAll(valueClassBoxingEntries(cls, owner, prefix))
    }
  }

  /**
   * ADR-171: the box/unbox pair that carries a value class through an erased generic position.
   * Kotlin boxes every value class at a generic slot, so the handle has to hold a real boxed `V`
   * (a raw underlying fails the callee's checkcast). Box: `NugetHandles.retain(V(lowered))`, with
   * `init` running inside the error-slot `try`. Unbox: the handle read back as `V`, returned as its
   * underlying through the ordinary value-class result emission.
   *
   * Both or neither, and a refusal is silent: the pair is generated surface no author wrote, so a
   * skip naming it would be noise. A value class whose underlying cannot cross has no erased
   * crossing either, and `Wrap<T>` keeps throwing for it exactly as before.
   */
  private fun valueClassBoxingEntries(
    cls: KSClassDeclaration,
    owner: String,
    prefix: String,
  ): List<ForwardCallableCatalogEntry> {
    if (cls.typeParameters.isNotEmpty()) return emptyList()
    // ADR-105: an exported sealed CLASS underlying is the handle its classification carries, read
    // back through the base's `FromHandle` discriminator, exactly as the ordinary route binds it.
    val type: BridgeType.ValueClass =
      classifier.classify(cls.asStarProjectedType()).sealedAsHandle() as? BridgeType.ValueClass
        ?: return emptyList()
    // The four underlying kinds both value-class wires already implement (ADR-077 sub-item 4).
    if (!type.hasErasedCrossing()) return emptyList()
    val box: ForwardCallableCatalogEntry = planOrSkip(
      symbol = "$owner.<box>",
      publicName = "NugetBox",
      exportName = "${prefix}_box",
      receiver = ForwardReceiver.Static,
      parameters = listOf("unboxed" to type),
      result = BridgeType.TypeParameter("T"),
      origin = ForwardCallableOrigin.VALUE_CLASS_BOX,
      target = owner,
      node = cls,
    )
    val unbox: ForwardCallableCatalogEntry = planOrSkip(
      symbol = "$owner.<unbox>",
      publicName = "NugetUnbox",
      exportName = "${prefix}_unbox",
      receiver = ForwardReceiver.Static,
      parameters = listOf("boxed" to BridgeType.TypeParameter("T", boundQualifiedName = owner)),
      result = type,
      origin = ForwardCallableOrigin.VALUE_CLASS_BOX,
      target = owner,
      node = cls,
    )
    val pair: List<ForwardCallableCatalogEntry> = listOf(box, unbox)
    return if (pair.all { it is ForwardCallableCatalogEntry.Planned }) pair else emptyList()
  }

  private fun valueClassConstructorEntries(
    cls: KSClassDeclaration,
    owner: String,
    prefix: String,
    underlyingPropName: String,
    underlyingType: BridgeType,
    isReferenceUnderlying: Boolean,
  ): List<ForwardCallableCatalogEntry> {
    // Constructor exports return the *underlying* value (ADR-014 unwrapped bridge), not a
    // StableRef of the value class. Reference-underlying primaries are deferred (ADR-035).
    val secondaryConstructors: List<KSFunctionDeclaration> = cls.declarations
      .filterIsInstance<KSFunctionDeclaration>()
      .filter { it.simpleName.asString() == "<init>" }
      .filter { it != cls.primaryConstructor }
      .filter { it.getVisibility() == Visibility.PUBLIC }
      .toList()

    // ADR-035's 2026-09-11 amendment: a secondary is planned on both underlying kinds. Its result
    // is the underlying itself, so a reference underlying returns a fresh handle that C# rebuilds
    // (`: this(new Cat(CreateChecked_2(...)))`) to feed its own positional record constructor.
    // The *primary* stays deferred on a reference underlying: a positional `Wrapper(Cat Cat)`
    // cannot coexist with a hand-written `Wrapper(Cat cat)` (CS0111), and the record header
    // already constructs one, so it is not a skip either.
    val exports: List<Pair<KSFunctionDeclaration, Pair<String, String>>> = buildList {
      val exportedPrimary: KSFunctionDeclaration? = cls.primaryConstructor?.takeIf {
        !isReferenceUnderlying && it.getVisibility() == Visibility.PUBLIC
      }
      if (exportedPrimary != null) {
        add(exportedPrimary to ("${prefix}_create" to ""))
      }
      secondaryConstructors.forEachIndexed { index, ctor ->
        val number: Int = index + 2
        add(ctor to ("${prefix}_create_$number" to "_$number"))
      }
    }

    return exports.map { (ctor, names) ->
      val (export, suffix) = names
      planOrSkip(
        symbol = "$owner.<init>$suffix",
        publicName = "Create$suffix",
        exportName = export,
        receiver = ForwardReceiver.Static,
        parameters = ctor.parameters.map { parameter ->
          parameter.bridgeName() to classifier.classify(parameter.type.resolve())
        },
        result = underlyingType,
        origin = ForwardCallableOrigin.VALUE_CLASS,
        target = owner,
        // Underlying property name used by the Kotlin emitter to unbox: `Owner(args).prop`.
        invocationReceiver = underlyingPropName,
        includeError = true,
        // ADR-150 amendment: a value class's primary constructor carries no `docString` of its
        // own either, so it reads the class comment's `@constructor`/`@param`/`@property` tags
        // exactly as an ordinary class's does. `forParameters` then keeps a `@property` naming a
        // body property out of the parameter list (a `<param>` for a non-parameter is CS1572).
        doc = (
            ctor.forwardKdoc(expects)
              ?: ctor.primaryConstructorKdoc(cls, expects, withSummary = true)
            ).forParameters(ctor.parameters),
      )
    }
  }

  private fun valueClassPropertyEntries(
    cls: KSClassDeclaration,
    owner: String,
    prefix: String,
    underlyingPropName: String,
    receiver: ForwardReceiver,
    inherited: ForwardSupertypeMembers,
  ): List<ForwardCallableCatalogEntry> = cls.getAllProperties()
    .filter { it.getVisibility() == Visibility.PUBLIC }
    .filter { it.simpleName.asString() != underlyingPropName }
    .map { prop ->
      val name: String = prop.simpleName.asString()
      // ADR-064 (ROADMAP line 77), amended by ADR-066: a property whose declaration site is not
      // the value class itself, or that a supertype (including an interface delegate, e.g.
      // `CharSequence by value`'s `length`) also declares by this simple name, is a v1
      // product-scope skip, not a silently-bridged member. See `valueClassEntries` for why the
      // supertype check (not `Origin.KOTLIN`) is the origin-independent signal this needs.
      // Properties compare by name alone (Kotlin properties cannot overload) and only against
      // supertype properties — ADR-082's amendment.
      if (prop.parentDeclaration != cls || inherited.declares(prop)) {
        return@map ForwardCallableCatalogEntry.Skipped(
          "$owner.$name", ForwardPlanSkipReason.INHERITED_MEMBER, node = prop,
        )
      }
      planOrSkip(
        symbol = "$owner.$name",
        publicName = prop.csharpMemberName(),
        exportName = "${prefix}_get_$name",
        receiver = receiver,
        parameters = emptyList(),
        result = classifier.classify(prop.type.resolve()),
        origin = ForwardCallableOrigin.VALUE_CLASS,
        target = owner,
        includeError = false,
        // Property getter: export name contains `_get_`; emitter uses bare member access.
        valueClassProperty = true,
        node = prop,
        doc = prop.forwardKdoc(expects),
      )
    }
    .toList()

  private fun valueClassMethodEntries(
    cls: KSClassDeclaration,
    owner: String,
    prefix: String,
    receiver: ForwardReceiver,
    inherited: ForwardSupertypeMembers,
  ): List<ForwardCallableCatalogEntry> {
    val excluded: Set<String> = setOf(
      "equals", "hashCode", "toString", "<init>",
      "box-impl", "unbox-impl", "constructor-impl",
      "hashCode-impl", "equals-impl", "equals-impl0", "toString-impl",
    )
    // ADR-082 amendment fix B: overload numbering, mirroring the secondary-constructor scheme.
    // Counted over *declared* same-name members in `getAllFunctions()` order, so a skipped
    // (inherited) namesake never consumes a number and the first declared overload keeps the
    // shipped unsuffixed export name.
    val occurrences: MutableMap<String, Int> = mutableMapOf()
    return cls.getAllFunctions()
      .filter { it.getVisibility() == Visibility.PUBLIC }
      .filter { it.simpleName.asString() !in excluded }
      .filter { method -> !method.isCompilerOwnedMember(cls) }
      .map { method ->
        val name: String = method.simpleName.asString()
        // ADR-064 (ROADMAP line 77), amended by ADR-066 and narrowed by ADR-082: the supertype
        // *signature* signal (not `Origin.KOTLIN`, and no longer the simple name) catches both
        // genuine supertype inheritance and interface delegation (`CharSequence by value`'s `get`
        // / `subSequence`), the two constructs `parentDeclaration` alone cannot tell apart
        // cross-module, while letting an unrelated same-name overload (`get(key: String)`)
        // through.
        if (method.parentDeclaration != cls || inherited.declares(method)) {
          return@map ForwardCallableCatalogEntry.Skipped(
            "$owner.$name", ForwardPlanSkipReason.INHERITED_MEMBER, node = method,
          )
        }
        val occurrence: Int = occurrences.merge(name, 1, Int::plus)!!
        val suffix: String = if (occurrence == 1) "" else "_$occurrence"
        val symbol: String = "$owner.$name$suffix"
        val structuralReason: ForwardPlanSkipReason? = when {
          method.modifiers.contains(Modifier.SUSPEND) -> ForwardPlanSkipReason.SUSPEND
          method.typeParameters.isNotEmpty() -> ForwardPlanSkipReason.GENERIC
          else -> null
        }
        if (structuralReason != null) {
          ForwardCallableCatalogEntry.Skipped(symbol, structuralReason, node = method)
        } else {
          planOrSkip(
            symbol = symbol,
            publicName = method.csharpMemberName(),
            exportName = "${prefix}_$name$suffix",
            receiver = receiver,
            parameters = method.parameters.map { parameter ->
              parameter.bridgeName() to classifier.classify(parameter.type.resolve())
            },
            result = method.returnType?.resolve()?.let(classifier::classify) ?: BridgeType.Unit,
            origin = ForwardCallableOrigin.VALUE_CLASS,
            target = owner,
            includeError = false,
            // The symbol carries the overload suffix; the Kotlin call site must not.
            member = name,
            node = method,
            doc = method.forwardKdoc(expects).forParameters(method.parameters),
          )
        }
      }
      // ADR-064 amendment: no legacy route takes a value class (not the suspend, Flow, callback or
      // generic one), so every deferral here is a drop and is named.
      .map { entry -> entry.namedSuspend() }
      .toList()
      .nameUnroutedPositions()
  }

  /**
   * ADR-040: dispatch-export plans for an interface's own declared members (reachability-driven —
   * only called for interfaces the caller already determined appear in a planned return
   * position). The receiver classifies as [BridgeType.ObjectHandle] rather than
   * [BridgeType.Interface] even though the receiver *is* an interface — a receiver is only ever
   * lowered via `asStableRef`, never constructed, so the extra construction spelling
   * [BridgeType.Interface] carries would be unused (sub-decision A.1's Consequences #3).
   *
   * Unlike [classEntries], an interface member with no body still needs an export (the concrete
   * object behind the handle always implements it), so the ABSTRACT structural skip does not
   * apply here.
   */
  fun interfaceEntries(iface: KSClassDeclaration): List<ForwardCallableCatalogEntry> {
    val ifaceName: String = iface.qualifiedName?.asString() ?: return emptyList()
    val prefix: String = iface.nativePrefix(symbols)
    val receiverType: BridgeType = BridgeType.ObjectHandle(ifaceName)
    val methods: List<KSFunctionDeclaration> = iface.getAllFunctions()
      .filter { method -> method.getVisibility() == Visibility.PUBLIC }
      .filter { method -> !method.isCompilerOwnedMember(iface) }
      .toList()
    // Interface super-interfaces: every member, own and inherited, is planned under this
    // interface's prefix, because the ADR-040 backing class implements `IDerived` and through it
    // every `IBase`. `translateInterface` narrows the declaration back to the DECLARED placement.
    val hierarchy = ForwardInterfaceHierarchy(iface, classifier.exportedObjectHandles)
    val placements: List<ForwardInterfaceMemberPlacement> = methods.map(hierarchy::placement)

    // ADR-090 amendment (2026-09-26): the interface route numbers same-name members exactly as
    // `classEntries` does -- a per-interface counter in declaration order, incremented BEFORE the
    // structural skip so a skipped `suspend` namesake still consumes its number. Without it two
    // same-name members shared one symbol and one export name, and a reachable interface aborted
    // generation on a duplicate plan.
    val occurrences: MutableMap<String, Int> = mutableMapOf()
    // ADR-064 audit amendment: the ADR-174 suspend route carries an interface only when it is
    // reachable. A generic interface's suspend member is already named by
    // SKIPPED_GENERIC_INTERFACE_ASYNC_MEMBER, and a sealed interface's arms bind it, so only an
    // unreachable, non-generic, non-sealed interface names its SUSPEND deferral here.
    val namesSuspend: Boolean = !ForwardAsyncInterfaces.carries(iface) &&
        iface.typeParameters.isEmpty() && !iface.isSealedInterface()
    return methods.mapIndexed { index, method ->
      val name: String = method.simpleName.asString()
      val occurrence: Int = occurrences.merge(name, 1, Int::plus)!!
      val suffix: String = if (occurrence == 1) "" else "_$occurrence"
      val symbol: String = "$ifaceName.$name$suffix"
      // A covariant override is planned at the kept super's (substituted) return type: the backing
      // class then implements `IBase`'s member exactly, and the narrower Kotlin value still fits.
      val resultType: KSType? =
        if (placements[index] == ForwardInterfaceMemberPlacement.COVARIANT_OVERRIDE) {
          hierarchy.keptReturnType(method)
        } else {
          method.returnType?.resolve()
        }
      val structuralReason: ForwardPlanSkipReason? = when {
        method.modifiers.contains(Modifier.SUSPEND) -> ForwardPlanSkipReason.SUSPEND
        method.typeParameters.isNotEmpty() -> ForwardPlanSkipReason.GENERIC
        else -> null
      }
      if (structuralReason != null) {
        val skipped = ForwardCallableCatalogEntry.Skipped(symbol, structuralReason, node = method)
        if (namesSuspend) skipped.namedSuspend() else skipped
      } else {
        planOrSkip(
          symbol = symbol,
          publicName = method.csharpMemberName(),
          exportName = "${prefix}_$name$suffix",
          receiver = ForwardReceiver.Handle(receiverType),
          parameters = method.parameters.map { parameter ->
            parameter.bridgeName() to classifier.classify(parameter.type.resolve())
          },
          result = resultType?.let(classifier::classify) ?: BridgeType.Unit,
          origin = ForwardCallableOrigin.CLASS,
          // The symbol carries the overload suffix; the Kotlin call site must not.
          member = name,
          node = method,
          // ADR-164: an interface member widens like any other. ADR-096 excluded interfaces to
          // avoid synthesizing overloads every implementer would owe; one widened signature is
          // what `memberDefaultFlags` already gives every implementer through the root. Read
          // through it here too, so an `actual interface` member widens from its `expect`.
          defaults = declaredDefaults(method.parameters, memberDefaultFlags(method)),
          doc = method.forwardKdoc(expects).forParameters(method.parameters),
        )
      }
    }.nameUnroutedPositions { skipped ->
      // ADR-064 amendment (2026-09-13): the interface DECLARATION is where a default member's
      // unrouted position is named (`classEntries` defers to this). The two exemptions are the
      // measured PART pair: a Flow return and a lambda parameter declared as an interface default
      // are routed. The lambda one is planned here and declared on `IFoo` (ADR-160); the Flow one
      // is declared on `IFoo` and dispatched by its backing wrapper when the interface is
      // reachable (ADR-174), and every implementing class binds it through its own legacy route
      // either way. Warning about a member the consumer can call is the false positive this
      // reclassification is most likely to introduce.
      val method: KSFunctionDeclaration = skipped.node as? KSFunctionDeclaration
        ?: return@nameUnroutedPositions true
      when {
        // As in `classEntries`: the route selects on the return type and owns the member, refused
        // parameters included (those are named once, by `warnRefusedLegacyRouteMembers`).
        method.hasLegacyFlowReturn() -> true
        // ADR-160 step 4: suppressed only when the legacy route really does re-emit the member.
        // A member it refuses by name is named here instead, which is what stops the ADR-055
        // contract failure from being the author's first sign of trouble.
        skipped.reason == ForwardPlanSkipReason.CALLBACK_PROTOCOL ->
          method.hasLegacyLambdaParameter() && legacyRefusedCallbackMember(method) == null

        else -> false
      }
    }.filterIndexed { index, entry ->
      // A member inherited from a kept super that did not plan is that super's drop, named once
      // on its own declaration; re-reporting it under every derived interface is duplicate noise.
      // ADR-113: a member the type-parameter carve-out restores on `IFoo<T>` is not dropped.
      val isInheritedDrop: Boolean = placements[index] == ForwardInterfaceMemberPlacement.INHERITED
      val isCarvedOut: Boolean = methods[index].restoredByTypeParameterCarveOut(iface)
      entry !is ForwardCallableCatalogEntry.Skipped || (!isInheritedDrop && !isCarvedOut)
    }
  }

  private fun classEntries(cls: KSClassDeclaration): List<ForwardCallableCatalogEntry> {
    val className: String = cls.simpleName.asString()
    val prefix: String = cls.nativePrefix(symbols)
    val superClass: KSClassDeclaration? = cls.forwardSuperClass(classifier.exportedObjectHandles)
    val receiverType: BridgeType = BridgeType.ObjectHandle(
      requireNotNull(cls.qualifiedName?.asString()) {
        "Forward class planner cannot create a handle for local ${className}"
      }
    )
    val methods: List<KSFunctionDeclaration> = cls.getAllFunctions()
      .filter { method -> method.getVisibility() == Visibility.PUBLIC }
      // Issue #235: one shared predicate, so no route can reach a member a compiler plugin wrote.
      .filter { method -> !method.isCompilerOwnedMember(cls) }
      // Shared with `CirClassTranslator` and `ForwardPropertyPlanner`: a defaulted interface
      // member the class does not override still binds here, because the C# class declares that
      // interface and must carry the member (`ForwardClassMembership.kt`).
      .filter { method -> method.isForwardPlannableMemberOf(cls, superClass) }
      .toList()
    val interfaceBridgeMethods: Set<KSFunctionDeclaration> = findInterfaceBridgePairs(methods)
      .flatMap { pair -> listOf(pair.first, pair.second) }
      .toSet()
    val storedCallbackMethods: Set<KSFunctionDeclaration> = findStoredCallbackPairs(methods)
      .flatMap { pair -> listOf(pair.first, pair.second) }
      .toSet()

    val owner: String = cls.qualifiedName?.asString() ?: className
    // ADR-147: null for an ordinary class, `io.pkg.Crate<Any?>` for a generic one.
    val ownerType: ForwardGenericOwner? = cls.forwardGenericOwner()
    val hasBacking: Boolean = cls.hasAbstractBacking()
    // ADR-090: overload numbering, the scheme `valueClassMethodEntries` uses (itself ADR-034's
    // secondary-constructor scheme). Counted over the *declared plannable* members in
    // `getAllFunctions()` order — the counter increments before the structural check, so a
    // skipped namesake still consumes its number and numbering stays declaration-order stable.
    val occurrences: MutableMap<String, Int> = mutableMapOf()
    // ADR-096 amendment (2026-09-11): the C# `override` question. It is *not* the Kotlin
    // `override` keyword: what matters to C# is whether a generated base class declares this
    // member. ADR-164: the widened shape comes off the root's default flags either way, so the
    // override and its base render one signature.
    fun isCsharpOverride(method: KSFunctionDeclaration): Boolean =
      method.overridesBaseClassMember(superClass)
    fun entryFor(method: KSFunctionDeclaration): ForwardCallableCatalogEntry {
      val name: String = method.simpleName.asString()
      val occurrence: Int = occurrences.merge(name, 1, Int::plus)!!
      val suffix: String = if (occurrence == 1) "" else "_$occurrence"
      val symbol: String = "$owner.$name$suffix"
      // ADR-090: the C# modifiers, computed here because a planned entry keeps no declaration.
      // ADR-101 amendment (2026-09-11): keyed on a base *class* overridee, not on the Kotlin
      // modifier. `Ledge : Shelf(), Groomable` overrides `Groomable.groom`, which `Shelf` never
      // declares, so C# spells it `virtual`; `public override string Groom()` is CS0115.
      val isOverride: Boolean = isCsharpOverride(method)
      // ADR-101 (2026-09-11): a *declared* `open fun` is virtual too, not just the
      // `override && !final` arm, or a subclass's `override` is CS0506 in C#. Same predicate the
      // property route uses (`CirClassTranslator`), so both halves of a class agree.
      // ADR-101 amendment (2026-09-27): an inherited interface default on an open class too.
      val isVirtual: Boolean = !isOverride && method.isOpenForOverrideOn(cls)
      // An abstract class with a backing wrapper plans its abstract members as call-through
      // exports the wrapper overrides; any other class keeps the declaration-only abstract walk.
      val isAbstract: Boolean = hasBacking && method.isAbstract
      val structuralReason: ForwardPlanSkipReason? = when {
        method.modifiers.contains(Modifier.ABSTRACT) && !hasBacking ->
          ForwardPlanSkipReason.ABSTRACT
        method.modifiers.contains(Modifier.SUSPEND) -> ForwardPlanSkipReason.SUSPEND
        // ADR-197: a routed generic member plans; every other shape keeps the structural skip.
        method.isUnroutedGeneric(interfaceBridgeMethods + storedCallbackMethods) ->
          ForwardPlanSkipReason.GENERIC
        method in interfaceBridgeMethods || method in storedCallbackMethods -> ForwardPlanSkipReason.CALLBACK_PROTOCOL
        else -> null
      }
      fun plan(): ForwardCallableCatalogEntry = planOrSkip(
        symbol = symbol,
        publicName = method.csharpMemberName(),
        exportName = "${prefix}_$name$suffix",
        receiver = ForwardReceiver.Handle(receiverType),
        parameters = method.parameters.map { parameter ->
          parameter.bridgeName() to classifier.classify(parameter.type.resolve())
        },
        result = method.returnType?.resolve()?.let(classifier::classify) ?: BridgeType.Unit,
        origin = ForwardCallableOrigin.CLASS,
        ownerType = ownerType,
        // The symbol carries the overload suffix; the Kotlin call site must not.
        member = name,
        isOverride = isOverride,
        isVirtual = isVirtual,
        node = method,
        defaults = declaredDefaults(method.parameters, memberDefaultFlags(method)),
        doc = method.forwardKdoc(expects).forParameters(method.parameters),
        isAbstract = isAbstract,
      ).withMethodTypeParameters(method)
      // An abstract member of an owner with no wrapper (a generic one) is declared `abstract` in
      // C# with no export, by `CirClassTranslator`'s abstract walk, and every Kotlin subclass's
      // plan overrides it. The declaration must not promise what those plans refuse (a lambda
      // return: CS0534 on the subclass), so the plan is still tried, and its refusal replaces the
      // ABSTRACT skip. The walk then leaves the member off the owner, as every subclass leaves it
      // off, and the refusal names it.
      val walkDeclares: Boolean = structuralReason == ForwardPlanSkipReason.ABSTRACT &&
          !method.hasLegacyFlowReturn() &&
          (!method.hasLegacyLambdaParameter() || method.hasPlannedCallbackParameter(classifier))
      if (walkDeclares) {
        val probe: ForwardCallableCatalogEntry = plan()
        if (probe is ForwardCallableCatalogEntry.Skipped) return probe
      }
      return if (structuralReason != null) {
        ForwardCallableCatalogEntry.Skipped(symbol, structuralReason, node = method)
      } else {
        plan()
      }
    }
    return methods.map { method -> entryFor(method) }.nameUnroutedPositions { skipped ->
      // ADR-064 amendment (2026-09-13): an ordinary class is the owner with the MOST legacy
      // routes, and still only two — the Flow/StateFlow return (ADR-012/065) and the lambda
      // parameter, per-call (ADR-036) or as a stored/interface-bridge add-remove pair. Each is
      // tested through the route's own hoisted gate, so `fun f(): List<Flow<Int>>` (same reason,
      // same position as the routed `fun f(): Flow<Int>`) is named rather than silently dropped.
      val method: KSFunctionDeclaration = skipped.node as? KSFunctionDeclaration
        ?: return@nameUnroutedPositions true
      when {
        // One warning per *declaration*: a defaulted interface member binds here too, and naming
        // it on every implementing class would report the author's one declaration N times. The
        // interface's own planner names it (`interfaceEntries`).
        // ADR-064 amendment: only when that declaration HAS its own pass. An unexported interface
        // or base class gets none, so its member is named on each inheriting class (issue #249: a
        // different owner is a different hole).
        method.parentDeclaration != cls && method.isDeclaredOnExportedType() -> true
        // The Flow route owns the whole member, not just its return: a parameter or element it
        // cannot marshal makes the route refuse the member, and ADR-114/ADR-123 already name that
        // refusal (`warnRefusedLegacyRouteMembers`) with a message that says which type failed.
        // Reporting it here as well would double-report, with the weaker of the two messages
        // first (`TreatBoard.paired`).
        method.hasLegacyFlowReturn() -> true
        skipped.reason == ForwardPlanSkipReason.CALLBACK_PROTOCOL ->
          method in interfaceBridgeMethods || method in storedCallbackMethods ||
              // ADR-160 step 4: as above, only a member the legacy route actually emits is
              // suppressed here; one it refuses by name is reported.
              (method.hasLegacyLambdaParameter() && legacyRefusedCallbackMember(method) == null) ||
              // A refused lambda shape is named by `warnRefusedLegacyRouteMembers` with the type
              // that failed; naming it here too would report one member twice, and the C# remark
              // would keep this generic wording instead.
              method.refusedLegacyLambdaShape() != null

        else -> false
      }
    }.nameGenericOwnerLegacyRoutes(cls, methods)
  }

  /**
   * ADR-147: a generic owner has no legacy route at all, because every one of them spells the
   * receiver as the bare owner name (`asStableRef<Crate>()`), so the deferrals [classEntries]
   * leaves silent ("the legacy route re-emits this") are drops on a generic class. Each is
   * relabelled [ForwardPlanSkipReason.GENERIC_OWNER_LEGACY_ROUTE], with the reason it came from as
   * the detail, unless something else already names it or binds it:
   *  - a member `warnRefusedLegacyRouteMembers` names with its own refusal (a refused lambda shape,
   *    an async member's refused parameter or return, a refused or opt-in-marked pair), which keeps
   *    one member to one warning;
   *  - an override of an interface member, which the interface binds (ADR-174 ruling 4) or names on
   *    its own declaration;
   *  - a member inherited from another declaration, named there.
   */
  private fun List<ForwardCallableCatalogEntry>.nameGenericOwnerLegacyRoutes(
    cls: KSClassDeclaration,
    methods: List<KSFunctionDeclaration>,
  ): List<ForwardCallableCatalogEntry> {
    if (cls.forwardTypeParametersInScope().isEmpty()) return this
    val markers: Set<String> = classifier.exportMarkers
    // The pairs exactly as `warnRefusedLegacyRouteMembers` detects them, so "named there" means
    // what that walk names: a lambda listener is a stored pair, never a subscription one.
    val storedPairs: List<Pair<KSFunctionDeclaration, KSFunctionDeclaration>> =
      findStoredCallbackPairs(
        methods
          .filter { method -> method.hasLegacyLambdaParameter() }
          .filter { method -> method.refusedLegacyLambdaShape() == null },
      )
    val bridgePairs: List<Pair<KSFunctionDeclaration, KSFunctionDeclaration>> =
      findInterfaceBridgePairs(methods.filterNot { method -> method.hasLegacyLambdaParameter() })
    fun Pair<KSFunctionDeclaration, KSFunctionDeclaration>.isMarked(): Boolean =
      toList().any { half -> half.optInMarker(markers) != null }
    val namedPairMembers: Set<KSFunctionDeclaration> = buildSet {
      storedPairs
        .filter { pair -> legacyRefusedStoredCallbackPair(pair.first) != null || pair.isMarked() }
        .forEach { pair -> addAll(pair.toList()) }
      bridgePairs
        .filter { pair ->
          classifier.legacyRefusedInterfaceBridgePair(pair.first) != null || pair.isMarked()
        }
        .forEach { pair -> addAll(pair.toList()) }
    }
    // ADR-064 amendment: "named there" holds only for a declaration with a pass of its own, so an
    // unexported supertype's member, or an override of one, is named here.
    fun KSFunctionDeclaration.isNamedElsewhere(): Boolean =
      (parentDeclaration != cls && isDeclaredOnExportedType()) ||
          refusedLegacyLambdaShape() != null ||
          this in namedPairMembers ||
          overridesExportedInterfaceMember(classifier.exportedObjectHandles) ||
          (isForwardLegacyAsyncRoute() &&
              (classifier.legacyRefusedParameter(parameters) != null ||
                  classifier.legacyRefusedReturn(this) != null))
    return map { entry ->
      if (entry !is ForwardCallableCatalogEntry.Skipped) return@map entry
      if (entry.reason !in GENERIC_OWNER_LEGACY_REASONS) return@map entry
      val method: KSFunctionDeclaration = entry.node as? KSFunctionDeclaration ?: return@map entry
      if (method.isNamedElsewhere()) return@map entry
      entry.copy(
        reason = ForwardPlanSkipReason.GENERIC_OWNER_LEGACY_ROUTE,
        detail = entry.reason.name,
      )
    }
  }

  /**
   * ADR-116 amendment (2026-09-11): the sealed **base**'s own declared member functions, keyed to
   * the base under its own `${sealed}_` export prefix.
   *
   * Three differences from [sealedSubclassEntries], all following from the base being the carrier
   * rather than a leaf:
   * - No [ForwardPlanSkipReason.ABSTRACT] structural skip. An `abstract fun` on the base is
   *   exactly what needs a plan here: the export calls it through the base type
   *   (`handle.asStableRef<Job>().get().describe()`), so Kotlin's own dispatch reaches the arm's
   *   body and the C# member can be concrete.
   * - `isVirtual` therefore covers the abstract case too, so an arm that overrides can spell
   *   `override` without CS0506. `isOverride` stays false: the base overrides nothing.
   * - An unrouted skip is named [ForwardPlanSkipReason.SEALED_BASE_UNROUTED], not the arm's
   *   reason: `Job.rest`, an `open suspend fun` on the base, has produced no diagnostic at all
   *   until now (ADR-118 found the same absence a third time). ADR-175: `Job.rest` itself is
   *   routed now, on the base's own suspend route; a suspend or Flow member keeps its legacy
   *   reason unless the base is generic.
   */
  private fun sealedBaseEntries(sealed: KSClassDeclaration): List<ForwardCallableCatalogEntry> {
    val owner: String = sealed.qualifiedName?.asString() ?: return emptyList()
    val prefix: String = sealed.nativePrefix(symbols)
    val receiverType: BridgeType = BridgeType.ObjectHandle(owner)
    val keptBase: KSClassDeclaration? = sealed.forwardSuperClass(classifier.exportedObjectHandles)
    val methods: List<KSFunctionDeclaration> = sealed.getAllFunctions()
      .filter { method -> method.getVisibility() == Visibility.PUBLIC }
      // `Any`'s members fall out here (issue #235's shared predicate).
      .filter { method -> !method.isCompilerOwnedMember(sealed) }
      // ADR-101 amendment (2026-09-27): declared members plus every inherited one no rendered C#
      // supertype carries -- an unexported base's, and any interface's -- re-homed onto the base,
      // the only C# carrier they have. A kept exported base's own members stay on it. Deliberately
      // `isForwardMemberOf`, not the plannable variant: an inherited abstract member the base does
      // not implement plans here like a declared `abstract fun` does, dispatching through the base.
      .filter { method -> method.isForwardMemberOf(sealed, keptBase) }
      .toList()
    val interfaceBridgeMethods: Set<KSFunctionDeclaration> = findInterfaceBridgePairs(methods)
      .flatMap { pair -> listOf(pair.first, pair.second) }
      .toSet()
    val storedCallbackMethods: Set<KSFunctionDeclaration> = findStoredCallbackPairs(methods)
      .flatMap { pair -> listOf(pair.first, pair.second) }
      .toSet()

    val occurrences: MutableMap<String, Int> = mutableMapOf()
    fun entryFor(method: KSFunctionDeclaration): ForwardCallableCatalogEntry {
      val name: String = method.simpleName.asString()
      val occurrence: Int = occurrences.merge(name, 1, Int::plus)!!
      val suffix: String = if (occurrence == 1) "" else "_$occurrence"
      val symbol: String = "$owner.$name$suffix"
      // An overridable member of the base (`abstract` or `open`) needs a `virtual` C# base member
      // for an arm's `override`. An interface member carries neither modifier when it has no
      // body, and never `open` when it has one, so its owner answers for it.
      val isOverride: Boolean = method.overridesBaseClassMember(keptBase)
      val isOverridable: Boolean = method.modifiers.contains(Modifier.ABSTRACT) ||
          method.isAbstract || method.modifiers.isOpenForOverride()
      val isInterfaceMember: Boolean =
        (method.parentDeclaration as? KSClassDeclaration)?.classKind == ClassKind.INTERFACE
      val isVirtual: Boolean = !isOverride && (isOverridable || isInterfaceMember)
      val structuralReason: ForwardPlanSkipReason? = when {
        method.modifiers.contains(Modifier.SUSPEND) -> ForwardPlanSkipReason.SUSPEND
        // ADR-197: a routed generic member plans; every other shape keeps the structural skip.
        method.isUnroutedGeneric(interfaceBridgeMethods + storedCallbackMethods) ->
          ForwardPlanSkipReason.GENERIC
        method in interfaceBridgeMethods || method in storedCallbackMethods ->
          ForwardPlanSkipReason.CALLBACK_PROTOCOL

        else -> null
      }
      return if (structuralReason != null) {
        ForwardCallableCatalogEntry.Skipped(symbol, structuralReason, node = method)
      } else {
        planOrSkip(
          symbol = symbol,
          publicName = method.csharpMemberName(),
          exportName = "${prefix}_$name$suffix",
          receiver = ForwardReceiver.Handle(receiverType),
          parameters = method.parameters.map { parameter ->
            parameter.bridgeName() to classifier.classify(parameter.type.resolve())
          },
          result = method.returnType?.resolve()?.let(classifier::classify) ?: BridgeType.Unit,
          origin = ForwardCallableOrigin.CLASS,
          // ADR-199: a generic base reads its receiver erased, as ADR-147's class route does.
          ownerType = sealed.forwardGenericOwner(),
          member = name,
          isOverride = isOverride,
          isVirtual = isVirtual,
          node = method,
          // ADR-164: the base is the carrier of the widened signature every overriding arm shares.
          defaults = declaredDefaults(method.parameters, memberDefaultFlags(method)),
          doc = method.forwardKdoc(expects).forParameters(method.parameters),
        ).withMethodTypeParameters(method)
      }
    }

    val entries: List<ForwardCallableCatalogEntry> = methods.map { method -> entryFor(method) }

    // The same posture `sealedSubclassEntries` takes: `droppedFromCSharp = false` means "a named
    // legacy route re-emits it", and no legacy route is keyed to a sealed *base* at all -- not
    // even the suspend and flow ones ADR-118/ADR-124 keyed to the arms. So every skip left here
    // is a real drop and says so.
    // ADR-175: except the suspend and Flow routes, which ARE keyed to the base now (the base's own
    // prefix, receiver `asStableRef<Base>()`). A member those routes admit is emitted by them; one
    // they refuse (ADR-114/119/123) is named with the refusal's own reason by
    // `warnRefusedLegacyRouteMembers`, so neither is relabelled. A generic base still has no route
    // (ADR-147, deferred) and keeps the unrouted name.
    val asyncRouted: Set<ForwardPlanSkipReason> =
      if (sealed.typeParameters.isEmpty()) {
        setOf(ForwardPlanSkipReason.SUSPEND, ForwardPlanSkipReason.FLOW_PROTOCOL)
      } else {
        emptySet()
      }
    return entries.map { entry ->
      if (entry !is ForwardCallableCatalogEntry.Skipped) return@map entry
      if (entry.reason.droppedFromCSharp) return@map entry
      if (entry.reason in asyncRouted) return@map entry
      ForwardCallableCatalogEntry.Skipped(
        entry.symbol,
        ForwardPlanSkipReason.SEALED_BASE_UNROUTED,
        node = entry.node,
        detail = entry.reason.name,
      )
    }
  }

  /**
   * ADR-116: [classEntries] for one arm of an ADR-009 sealed hierarchy, with the four differences
   * a sealed subclass forces.
   *
   * - The export prefix is `${sealed}_${sub}`, the prefix `SealedClassExports` and
   *   `translateSealedClass` already mint for the arm's property getters and `_dispose`, so
   *   `Job.Running.cancel` exports as `job_running_cancel` beside `job_running_get_progress`.
   *   `ForwardCirPlanProjection.classMethod` requires the export to begin with it, so a mismatch
   *   fails the build rather than drifting.
   * - **Membership**: `isForwardPlannableMemberOf(subclass, superClass = sealed)`, the ordinary
   *   class's rule with the sealed type as the base (ADR-101 amendment 2026-09-27). Declared
   *   members bind, and so does an implemented member inherited from an interface the sealed type
   *   does not itself carry: the arm lists that interface (or, unexported, re-homes it), so it
   *   must carry the member or the declaration is CS0535. A sealed base's own member, and `Any`'s,
   *   are carried by the sealed base and fall out. `superClass` is never null here: null admits
   *   every inherited member with a body, `Any`'s included.
   * - `isOverride` is false except for an override of the sealed base's kept exported base class
   *   (ADR-101 amendment 2026-09-27); a sealed base's own member is matched later, by signature,
   *   in `CirClassTranslator.againstSealedBase`. `isVirtual` is true only for a declared `open`
   *   member of an `open` arm (ADR-009 amendment 2026-09-11), since a final arm renders `public
   *   sealed class`, where `virtual` is CS0549.
   * - Every skip an ordinary class would defer to a legacy route becomes a named
   *   [ForwardPlanSkipReason.SEALED_SUBCLASS_UNROUTED] drop, except for the suspend, Flow, per-call
   *   lambda and stored-callback/interface-bridge-pair routes now keyed to the arm too, which stay
   *   deferred like an ordinary class's. Planned entries are untouched.
   */
  private fun sealedSubclassEntries(
    sealed: KSClassDeclaration,
    subclass: KSClassDeclaration,
  ): List<ForwardCallableCatalogEntry> {
    val subName: String = subclass.simpleName.asString()
    val owner: String = subclass.qualifiedName?.asString() ?: return emptyList()
    // ADR-009 amendment (2026-09-11): only an `open` arm renders `public class`, so only an open
    // arm can carry `virtual`. On a final arm the member is effectively final in Kotlin anyway,
    // and `virtual` inside a `public sealed class` is CS0549. An `abstract` arm renders
    // `public abstract class` and is just as extensible.
    val isOpenArm: Boolean = subclass.isForwardExtensible()
    val hasBacking: Boolean = subclass.abstractBackingName() != null
    val prefix: String = "${sealed.nativePrefix(symbols)}_${subName.lowercase()}"
    val receiverType: BridgeType = BridgeType.ObjectHandle(owner)
    val keptBase: KSClassDeclaration? = sealed.forwardSuperClass(classifier.exportedObjectHandles)
    val methods: List<KSFunctionDeclaration> = subclass.getAllFunctions()
      .filter { method -> method.getVisibility() == Visibility.PUBLIC }
      .filter { method -> !method.isCompilerOwnedMember(subclass) }
      // ADR-116, widened by the ADR-101 amendment (2026-09-27): `Any`'s members and a base
      // `open fun` the arm does not override still fall out, so the sealed route's own
      // `_equals`/`_hashcode`/`_tostring` exports stay untouched; an interface member the sealed
      // type does not carry now binds on the arm.
      .filter { method -> method.isForwardPlannableMemberOf(subclass, superClass = sealed) }
      .toList()
    val interfaceBridgeMethods: Set<KSFunctionDeclaration> = findInterfaceBridgePairs(methods)
      .flatMap { pair -> listOf(pair.first, pair.second) }
      .toSet()
    val storedCallbackMethods: Set<KSFunctionDeclaration> = findStoredCallbackPairs(methods)
      .flatMap { pair -> listOf(pair.first, pair.second) }
      .toSet()

    // ADR-090 overload numbering, exactly as `classEntries` counts it: over the declared plannable
    // members in `getAllFunctions()` order, incremented before the structural check so a skipped
    // namesake still consumes its number.
    val occurrences: MutableMap<String, Int> = mutableMapOf()
    fun entryFor(method: KSFunctionDeclaration): ForwardCallableCatalogEntry {
      val name: String = method.simpleName.asString()
      val occurrence: Int = occurrences.merge(name, 1, Int::plus)!!
      val suffix: String = if (occurrence == 1) "" else "_$occurrence"
      val symbol: String = "$owner.$name$suffix"
      // ADR-101 amendment (2026-09-27): an override of the sealed base's kept exported base class
      // is a C# `override` of it, reached through the sealed base's base list (CS0114 otherwise).
      // With no kept base this is false, so every other arm keeps its shipped modifiers.
      val isOverride: Boolean = method.overridesKeptBaseOf(sealed, keptBase)
      val isVirtual: Boolean = !isOverride && isOpenArm && method.isOpenForOverrideOn(subclass)
      // An abstract arm's abstract member is a call-through export its backing wrapper overrides;
      // any other arm (a `sealed` one) still has no route for it.
      val isAbstract: Boolean = hasBacking && method.isAbstract
      val structuralReason: ForwardPlanSkipReason? = when {
        method.modifiers.contains(Modifier.ABSTRACT) && !hasBacking ->
          ForwardPlanSkipReason.ABSTRACT
        method.modifiers.contains(Modifier.SUSPEND) -> ForwardPlanSkipReason.SUSPEND
        // ADR-197: a routed generic member plans on the arm as it does on an ordinary class.
        method.isUnroutedGeneric(interfaceBridgeMethods + storedCallbackMethods) ->
          ForwardPlanSkipReason.GENERIC
        method in interfaceBridgeMethods || method in storedCallbackMethods ->
          ForwardPlanSkipReason.CALLBACK_PROTOCOL

        else -> null
      }
      return if (structuralReason != null) {
        ForwardCallableCatalogEntry.Skipped(symbol, structuralReason, node = method)
      } else {
        planOrSkip(
          symbol = symbol,
          publicName = method.csharpMemberName(),
          exportName = "${prefix}_$name$suffix",
          receiver = ForwardReceiver.Handle(receiverType),
          parameters = method.parameters.map { parameter ->
            parameter.bridgeName() to classifier.classify(parameter.type.resolve())
          },
          result = method.returnType?.resolve()?.let(classifier::classify) ?: BridgeType.Unit,
          origin = ForwardCallableOrigin.CLASS,
          // ADR-199: a generic arm reads its receiver erased, as ADR-147's class route does.
          ownerType = subclass.forwardGenericOwner(),
          // The symbol carries the overload suffix; the Kotlin call site must not.
          member = name,
          isOverride = isOverride,
          isVirtual = isVirtual,
          node = method,
          // ADR-164: the flags come through the override chain, as `classEntries` reads them, so an
          // arm overriding a base member renders the base's widened signature.
          defaults = declaredDefaults(method.parameters, memberDefaultFlags(method)),
          doc = method.forwardKdoc(expects).forParameters(method.parameters),
          isAbstract = isAbstract,
        ).withMethodTypeParameters(method)
      }
    }

    val entries: List<ForwardCallableCatalogEntry> = methods.map { method -> entryFor(method) }

    // ADR-116 Diagnostics: `droppedFromCSharp = false` means "a named legacy route re-emits it",
    // which is only true for an ordinary class. On a sealed arm the member is simply gone, so the
    // silent deferral becomes a named drop carrying the reason it came from. ABSTRACT included
    // (ADR-064 audit amendment): only an `abstract` or `sealed` arm can declare one, and the sealed
    // route declares no abstract member on the arm except through the arm suspend and Flow routes
    // (measured: `abstract suspend fun` and `abstract fun f(): Flow<Int>` are declared), which read
    // the declaration themselves, so those two stay exempt.
    return entries.map { entry ->
      if (entry !is ForwardCallableCatalogEntry.Skipped) return@map entry
      val abstractOnAsyncRoute: Boolean = entry.reason == ForwardPlanSkipReason.ABSTRACT &&
          (entry.node as? KSFunctionDeclaration)?.let { method ->
            method.modifiers.contains(Modifier.SUSPEND) || method.hasLegacyFlowReturn()
          } == true
      val isUnrouted: Boolean =
        !entry.reason.droppedFromCSharp && !abstractOnAsyncRoute &&
            // ADR-118: the legacy suspend route is keyed to sealed arms now, so a SUSPEND skip on
            // an arm means exactly what it means on an ordinary class -- "the plan does not own
            // this one, the named legacy route does" -- and stays silent. The numbered symbol is
            // copied either way, so `overloadSuffix` answers for the arm's overload pair
            // regardless. SUSPEND_CALLBACK_PROTOCOL is deliberately not exempted: no arm route
            // emits it.
            entry.reason != ForwardPlanSkipReason.SUSPEND &&
            // ADR-124: and the same for the legacy Flow/StateFlow route, one issue later.
            entry.reason != ForwardPlanSkipReason.FLOW_PROTOCOL &&
            // ADR-116 amendment (2026-09-11): the per-call lambda-parameter route (ADR-036) is
            // keyed to the arms too now, so its skip is a deferral again. The 2026-09-13 amendment
            // finished the reason off: the stored-callback (ADR-037) and interface-bridge
            // (ADR-039) add/remove **pairs**, which take the identical `CALLBACK_PROTOCOL`
            // constant from the structural check above, are keyed to the arm as well, so the
            // origin split that kept a pair named is gone and the exemption is by reason like the
            // other three. What is left named on an arm is SUSPEND_CALLBACK_PROTOCOL and a GENERIC
            // shape the ADR-197 member route refuses, which no route emits for any owner.
            entry.reason != ForwardPlanSkipReason.CALLBACK_PROTOCOL
      if (!isUnrouted) return@map entry

      ForwardCallableCatalogEntry.Skipped(
        entry.symbol,
        ForwardPlanSkipReason.SEALED_SUBCLASS_UNROUTED,
        node = entry.node,
        detail = entry.reason.name,
      )
    }
  }

  /**
   * ADR-157: the boxed enum arm's constructor. One parameter, the C# enum; one result, an owned
   * handle over the Kotlin entry the ordinal names.
   *
   * The export is `${sealed}_${arm}_create`, the same suffix every other constructor on the sealed
   * route uses, so the shared `constructorNativeImport` rule addresses it without a second naming
   * convention. ADR-157 drafted it as `_box`; that spelling would have bought one hand-written
   * import and nothing else.
   */
  private fun enumArmBoxEntries(
    sealed: KSClassDeclaration,
    arm: KSClassDeclaration,
  ): List<ForwardCallableCatalogEntry> {
    val owner: String = arm.qualifiedName?.asString() ?: return emptyList()
    val prefix = "${sealed.nativePrefix(symbols)}_${arm.simpleName.asString().lowercase()}"
    val type: BridgeType = classifier.classify(arm.asStarProjectedType())
    if (type !is BridgeType.Enum) return emptyList()
    return listOf(
      planOrSkip(
        symbol = "$owner.<init>",
        publicName = "Create",
        exportName = "${prefix}_create",
        receiver = ForwardReceiver.Static,
        // Not `value`: that is a PLAN_OWNED_NAME (the setter slot), and a plan may not hand a
        // user-role parameter one of those.
        parameters = listOf("entry" to type),
        // The handle is a StableRef over the enum ENTRY, so the owner of the result is the enum
        // itself; C# reads it back as the arm through the base's own discriminator.
        result = BridgeType.ObjectHandle(owner),
        origin = ForwardCallableOrigin.ENUM_ARM_BOX,
        target = owner,
        node = arm,
      ),
    )
  }

  private fun constructorEntries(
    cls: KSClassDeclaration,
    // ADR-148: the export prefix, supplied rather than derived, because a sealed arm's is the
    // base's plus its own simple name and a *sibling* arm's `nativePrefix()` does not compose that
    // (`label`, not `flatshape_label`). Everything else about an arm's constructors is an ordinary
    // class's, so the prefix is the only seam.
    prefix: String = cls.nativePrefix(symbols),
    // ADR-148: whether a `data` class also gets its `copy`. False on a sealed arm: the sealed
    // route renders no `Copy` member, so the entry would plan an export nothing imports.
    copy: Boolean = true,
  ): List<ForwardCallableCatalogEntry> {
    if (cls.modifiers.contains(Modifier.ABSTRACT)) return emptyList()
    val owner: String = cls.qualifiedName?.asString() ?: return emptyList()
    val result = BridgeType.ObjectHandle(owner)
    val constructors: List<KSFunctionDeclaration> = cls.getConstructors()
      .filter { it.getVisibility() == Visibility.PUBLIC }
      .toList()
    val primary = cls.primaryConstructor
    val secondaries: List<KSFunctionDeclaration> = constructors.filter { it != primary }
    // ADR-115: a marked primary-constructor `val`. The invariant is that the marked declaration
    // never appears in a C# signature. ADR-164: a marked parameter in the trailing all-defaulted
    // run is dropped from the widened signature and Kotlin evaluates its default; an undefaulted
    // or non-trailing one drops the constructor itself. The class stays reachable through
    // factories, and `copy` follows the constructor for the same reason.
    return buildList {
      if (primary != null) add(constructorEntry(primary, owner, "${prefix}_create", "Create", result, ""))
      secondaries.forEachIndexed { index, constructor ->
        add(
          constructorEntry(
            constructor,
            owner,
            "${prefix}_create_${index + 2}",
            "Create",
            result,
            "_${index + 2}",
          )
        )
      }
      if (copy && cls.modifiers.contains(Modifier.DATA) && primary != null) {
        val receiver = ForwardReceiver.Handle(result)
        val markedCopyParameter: String? = primary.parameters
          .firstNotNullOfOrNull { parameter ->
            parameter.constructorOptInMarker(cls, classifier.exportMarkers)
          }
        if (markedCopyParameter != null) {
          add(
            ForwardCallableCatalogEntry.Skipped(
              "$owner.copy", ForwardPlanSkipReason.OPT_IN_MARKER,
              node = primary, detail = markedCopyParameter,
            )
          )
          return@buildList
        }
        add(
          planOrSkip(
            symbol = "$owner.copy",
            publicName = "Copy",
            exportName = "${prefix}_copy",
            receiver = receiver,
            parameters = primary.parameters.map { parameter ->
              parameter.bridgeName() to classifier.classify(parameter.type.resolve())
            },
            result = result,
            origin = ForwardCallableOrigin.COPY,
            ownerType = cls.forwardGenericOwner(),
            node = primary,
            // ADR-164: every `copy` parameter defaults to the receiver's own value, so every one
            // widens and unset means "keep".
            defaults = declaredDefaults(
              primary.parameters, primary.parameters.map { true }, overloads = false,
            ),
            // ADR-150 amendment: `copy` takes the primary's `<param>` texts but never its
            // `@constructor` summary -- "Fills the bowl" does not describe a copy.
            doc = (
                primary.forwardKdoc(expects)
                  ?: primary.primaryConstructorKdoc(cls, expects, withSummary = false)
                ).forParameters(primary.parameters),
          )
        )
      }
    }
  }

  /**
   * ADR-091: per-parameter "has a default", positionally.
   *
   * KSP exposes exactly one bit ([KSValueParameter.hasDefault]) and never the default expression,
   * which is why the feature is overload synthesis rather than C# optional parameters. For an
   * `expect`/`actual` class the bit is erased on the exported root (ADR-074 exports the `actual`,
   * and Kotlin forbids an `actual` from restating a default), so the expect's primary constructor
   * is consulted positionally through [expects], and only for the actual's own primary
   * constructor, since matching secondaries across the pair needs a signature rule no spike has
   * verified.
   */
  private fun defaultFlags(
    cls: KSClassDeclaration,
    constructor: KSFunctionDeclaration,
    isPrimary: Boolean,
  ): List<Boolean> {
    val expectParameters: List<KSValueParameter> =
      if (!isPrimary) emptyList()
      else expects.classOrNull(cls.qualifiedName?.asString())
        ?.primaryConstructor
        ?.parameters
        .orEmpty()
    return constructor.parameters.mapIndexed { index, parameter ->
      parameter.hasDefault || expectParameters.getOrNull(index)?.hasDefault == true
    }
  }

  /**
   * ADR-096 amendment (2026-09-11): per-parameter "has a default" for a **class member**,
   * positionally, read through the override chain.
   *
   * Kotlin forbids an override from restating a default, but KSP still reports `hasDefault = true`
   * on the override's own parameter (measured on Kotlin 2.4.10 / KSP 2.3.10, 2026-09-19, for a
   * same-module interface, a klib interface and a klib open class alike). The chain walk is
   * therefore a **defensive read**, not the thing that makes the bit appear: dropping it changes
   * no current output. It is kept because the flags matter once ADR-101 drops the base and the
   * subclass has to synthesize for itself, and nothing pins the raw bit as API.
   *
   * The chain is walked to its **root**, not to the nearest `findOverridee()`, so a two-deep chain
   * still answers if an intermediate override ever did lose the bit.
   *
   * The `expect`/`actual` erasure, by contrast, is genuine (Kotlin forbids an `actual` from
   * restating a default, and the exported `actual` reports `hasDefault = false`), so the bit is
   * also read off the `expect` of the method and of its root via [expectDefaultFlags]: the root's
   * is the only way an arm declared on the `actual` side of an `expect sealed class` reaches the
   * default its base's `expect` member declares.
   */
  private fun memberDefaultFlags(method: KSFunctionDeclaration): List<Boolean> {
    val root: KSFunctionDeclaration? = generateSequence(
      method.findOverridee() as? KSFunctionDeclaration,
    ) { overridee -> overridee.findOverridee() as? KSFunctionDeclaration }.lastOrNull()
    val own: List<Boolean> = expectDefaultFlags(method)
    val inherited: List<Boolean> = root?.let(::expectDefaultFlags).orEmpty()
    return own.mapIndexed { index, flag -> flag || inherited.getOrNull(index) == true }
  }

  /**
   * ADR-096 / ADR-074 amendment (2026-09-27): per-parameter "has a default", positionally, for a
   * top-level function or a member alike: the declaration's own bit, or the bit on its `expect`.
   *
   * Kotlin forbids an `actual` from restating a default, so every parameter of an exported
   * `actual` reports `hasDefault = false`. [ExpectIndex.expectFunctionOrNull] resolves the
   * pairing by signature (through the owner's `expect class` for a member) and answers `null`
   * when it is ambiguous, which degrades to "no defaults" rather than attributing one namesake's
   * defaults to another.
   */
  private fun expectDefaultFlags(function: KSFunctionDeclaration): List<Boolean> {
    val expect: KSFunctionDeclaration? = expects.expectFunctionOrNull(function)
    return function.parameters.mapIndexed { index, parameter ->
      parameter.hasDefault || expect?.parameters?.getOrNull(index)?.hasDefault == true
    }
  }

  private fun constructorEntry(
    constructor: KSFunctionDeclaration,
    owner: String,
    export: String,
    publicName: String,
    result: BridgeType.ObjectHandle,
    suffix: String,
  ): ForwardCallableCatalogEntry {
    val cls: KSClassDeclaration? = constructor.parentDeclaration as? KSClassDeclaration
    // ADR-115 gate (b): a parameter whose PROPERTY carries the marker while its TYPE does not is
    // legal to call without (verified: `PropMarked(5)` and `PropMarked()` both compile from a
    // non-opting file). ADR-164 drops it from the widened signature when it is in the trailing
    // all-defaulted run; anywhere else `planOrSkip` skips the constructor as OPT_IN_MARKER.
    val marked: Map<Int, String> = constructor.parameters
      .mapIndexedNotNull { index, parameter ->
        parameter.constructorOptInMarker(cls, classifier.exportMarkers)?.let { index to it }
      }
      .toMap()
    val flags: List<Boolean> =
      if (cls == null) constructor.parameters.map { false }
      else defaultFlags(cls, constructor, isPrimary = constructor == cls.primaryConstructor)
    return planOrSkip(
      symbol = "$owner.<init>$suffix",
      publicName = publicName,
      exportName = export,
      // ADR-141: an `inner class` is constructed through its outer instance (`host.Guest(3)`), so
      // its plan carries a receiver like an extension's -- one borrowed handle slot at index 0,
      // named `outer`. Every other constructor keeps `Static` and renders byte-identically.
      receiver = innerConstructorReceiver(cls),
      parameters = constructor.parameters.map { parameter ->
        parameter.bridgeName() to classifier.classify(parameter.type.resolve())
      },
      result = result,
      origin = ForwardCallableOrigin.CONSTRUCTOR,
      target = owner,
      // ADR-147: `Crate<Any?>(item)`, so the constructed instance is the type the receiver read
      // back and every `T`-typed constructor parameter accepts its decoded box.
      ownerType = cls?.forwardGenericOwner(),
      node = constructor,
      defaults = declaredDefaults(constructor.parameters, flags, marked),
      // ADR-150 amendment: a primary constructor carries no `docString` of its own, so its
      // `<summary>` and `<param>` set come off the class comment's `@constructor`/`@param`/
      // `@property` tags. A secondary constructor documents itself and never reaches the fallback.
      doc = (
          constructor.forwardKdoc(expects)
            ?: constructor.primaryConstructorKdoc(cls, expects, withSummary = true)
          ).forParameters(constructor.parameters),
    )
      // ADR-141: the ADR-034 secondaries come through here too, so each of them carries the
      // receiver by construction -- the outer is not a plan parameter, so no default reaches it.
      // ADR-064 amendment (2026-09-13): no legacy route re-emits a CONSTRUCTOR (measured cell 24:
      // a secondary taking a lambda, a Flow or a generic type beside a bindable primary vanished
      // with no diagnostic, because `WARNING_NO_PUBLIC_CONSTRUCTOR` only fires when *every*
      // constructor is skipped). Each such constructor is now named once, at its input position.
      .nameUnroutedPosition { false }
  }

  /**
   * ADR-095/ADR-090 numbering: the first declared namesake keeps the bare name, the n-th further
   * one is `_$n` (n from 2). The counter increments before any structural check, so a skipped
   * namesake still consumes its number and numbering stays declaration-order stable. The suffix
   * composes *after* `toCName` on the export name: `name_2` is never a C reserved word.
   */
  private fun overloadSuffix(
    occurrences: MutableMap<String, Int>,
    function: KSFunctionDeclaration,
    // ADR-133 amendment: the counter scope beyond the package -- `""` everywhere except an
    // extension, where it is the receiver's enclosing-owner chain ([extensionOwnerChain]).
    // `Coop.Inner.describe` and `Roost.Inner.describe` export under different prefixes
    // (`coop_inner_` / `roost_inner_`), so they are not namesakes and must not share a counter, or
    // the second takes a gratuitous `_2` no collision required. The chain deliberately stops short
    // of the receiver itself, which keeps ADR-095 receiver-agnostic numbering for same-owner
    // receivers exactly as shipped (`Mitten.pat`, `Mitten.pat(style)` and `Tomcat.pat` stay
    // `mitten_pat` / `mitten_pat_2` / `tomcat_pat_3`).
    scope: String = "",
  ): String {
    val key: String =
      "${function.packageName.asString()}.$scope.${function.simpleName.asString()}"
    val occurrence: Int = occurrences.merge(key, 1, Int::plus)!!
    return if (occurrence == 1) "" else "_$occurrence"
  }

  private fun topLevelEntry(
    function: KSFunctionDeclaration,
    suffix: String,
  ): ForwardCallableCatalogEntry = staticEntry(
    function = function,
    symbol = "${function.packageName.asString()}.${function.simpleName.asString()}$suffix",
    // ADR-110: PascalCase, byte-identical to `objectEntries`/`companionEntries`. `toCName` stays
    // on the export name only (it is the C symbol); a PascalCased name is never a C# keyword, so
    // no verbatim-identifier escape is needed either.
    publicName = function.csharpMemberName(),
    // ADR-163: the library and package qualification, which is what makes a top-level
    // `fun signal(dbm: Int)` bind at all and two `rollCall()` in two packages coexist.
    exportName = "${symbols.topLevel(function)}$suffix",
    origin = ForwardCallableOrigin.TOP_LEVEL,
    target = null,
    member = function.simpleName.asString(),
    defaults = expectDefaultFlags(function),
  ).nameUnroutedPosition { skipped ->
    // ADR-064 amendment (2026-09-13): the top-level owner has exactly one legacy route for these
    // reasons — `addFunctionExports` / `translateSpecializedFunction`, keyed on a
    // generic-declaration RETURN. It carries the generic-type return (`Box<Int>`, measured
    // emitting); the lambda return it also used to carry is plan-owned since the ADR-160
    // amendment (`BridgeType.ReturnedLambda`, built in `staticEntry`), so the gate refuses it and a
    // lambda return that does not plan is named here. Since the ADR-064 amendment it refuses a
    // Flow/StateFlow return, which is what makes cell 4 a named
    // skip instead of a consumer-side CS0246. A PARAMETER of any of those types has no route here
    // at all, and an element-carried one (`List<Box<Int>>`) is unmeasured and therefore named.
    //
    // The structural GENERIC deferral never reaches this entry builder: `catalog()` is called with
    // non-generic top-level functions only, so a `fun <T> f(...)` is named from `NugetProcessor`
    // instead (`warnUnroutedGenericFunctions`). A `suspend` one does arrive (ROADMAP line 29) and
    // leaves `staticEntry` as a structural SUSPEND skip, which is not an unrouted candidate.
    skipped.position == ForwardSkipPosition.RETURN && function.hasLegacyGenericReturnRoute()
  }

  private fun objectEntries(obj: KSClassDeclaration): List<ForwardCallableCatalogEntry> {
    val owner: String = obj.qualifiedName?.asString() ?: return emptyList()
    val prefix: String = obj.nativePrefix(symbols)
    val occurrences: MutableMap<String, Int> = mutableMapOf()
    val members: List<KSFunctionDeclaration> = obj.getAllFunctions()
      .filter { it.getVisibility() == Visibility.PUBLIC }
      .filter { member -> !member.isCompilerOwnedMember(obj) }
      // ROADMAP Phase 4: the class route's own membership predicate, with `superClass = null`,
      // because a C# static class cannot extend anything -- an implemented inherited method has no
      // other carrier, exactly like an inherited property
      // (`ForwardPropertyPlanner.objectProperties`). This used to be `parentDeclaration == obj`,
      // which dropped an inherited `fun restock()` with no diagnostic at all. `Any`'s
      // `equals`/`hashCode`/`toString` are already gone above (`isCompilerOwnedMember`), and an
      // abstract member has no implementation for `hasImplementation()` to admit.
      .filter { member -> member.isForwardPlannableMemberOf(obj, superClass = null) }
      .toList()

    fun entryFor(function: KSFunctionDeclaration): ForwardCallableCatalogEntry {
      val name: String = function.simpleName.asString()
      val occurrence: Int = occurrences.merge(name, 1, Int::plus)!!
      val suffix: String = if (occurrence == 1) "" else "_$occurrence"
      return staticEntry(
        function = function,
        symbol = "$owner.$name$suffix",
        publicName = function.csharpMemberName(),
        exportName = "${prefix}_${toCName(name)}$suffix",
        origin = ForwardCallableOrigin.OBJECT,
        target = owner,
        member = name,
        // ADR-074 amendment (2026-09-27): an `actual object` member widens from its `expect`.
        defaults = memberDefaultFlags(function),
      )
    }
    // ADR-064 amendment (2026-09-13): no legacy route is keyed to an object owner at all,
    // measured, cells 3a/13a/18a/22a, so every deferral here is a drop, with no exemption. The
    // audit amendment: SUSPEND included (the suspend route walks classes, never an object).
    return members.map { member -> entryFor(member).namedSuspend() }.nameUnroutedPositions()
  }

  /**
   * ADR-094 (write side): the enum's box, the export `NugetMarshal.Boxers` calls when an enum is
   * written into an erased generic slot. One parameter, the ordinal; one result, an owned handle
   * over the entry it names (`Mood.entries[entry]`), so an out-of-range ordinal (`(Mood)99`)
   * throws inside the error slot.
   *
   * `_box_entry`, not ADR-171's `_box`: an enum member is exported as `<enum>_<name>`, so `_box`
   * would collide with an enum's own `fun box()` and fail a library that built before. Silent when
   * refused, as the value-class pair is: it is generated surface no author wrote.
   */
  private fun enumBoxEntries(enum: KSClassDeclaration): List<ForwardCallableCatalogEntry> {
    val owner: String = enum.qualifiedName?.asString() ?: return emptyList()
    val type: BridgeType = classifier.classify(enum.asStarProjectedType())
    if (type !is BridgeType.Enum) return emptyList()
    val entry: ForwardCallableCatalogEntry = planOrSkip(
      symbol = "$owner.<box>",
      publicName = "NugetBox",
      exportName = "${enum.nativePrefix(symbols)}_box_entry",
      receiver = ForwardReceiver.Static,
      // Not `value`: that is a PLAN_OWNED_NAME (the setter slot).
      parameters = listOf("entry" to type),
      result = BridgeType.TypeParameter("T"),
      origin = ForwardCallableOrigin.ENUM_BOX,
      target = owner,
      node = enum,
    )
    return if (entry is ForwardCallableCatalogEntry.Planned) listOf(entry) else emptyList()
  }

  /**
   * ADR-006 amendment: the functions declared in an `enum class` body, planned on the enum value as
   * receiver (the ordinal ADR-132 already lowers: `Mood.entries[mood]` / `(int)mood`).
   *
   * `declarations`, not `getAllFunctions()`: an inherited `compareTo` is not something the author
   * wrote on the enum. [isCompilerOwnedMember] removes `Any`'s three, and the compiler's
   * synthesized `values()` / `valueOf()` are removed by name. No ABSTRACT skip: an `abstract fun`
   * whose bodies live on the entries is exactly what `Mood.entries[mood].f()` dispatches. No legacy
   * route is keyed to an enum, so every deferral is a named drop, SUSPEND included.
   */
  private fun enumEntries(enum: KSClassDeclaration): List<ForwardCallableCatalogEntry> {
    val owner: String = enum.qualifiedName?.asString() ?: return emptyList()
    val type: BridgeType = classifier.classify(enum.asStarProjectedType())
    if (type !is BridgeType.Enum) return emptyList()
    val prefix: String = enum.nativePrefix(symbols)
    val occurrences: MutableMap<String, Int> = mutableMapOf()
    val members: List<KSFunctionDeclaration> = enum.declarations
      .filterIsInstance<KSFunctionDeclaration>()
      .filter { it.getVisibility() == Visibility.PUBLIC }
      .filter { member -> !member.isCompilerOwnedMember(enum) }
      .filter { member -> member.simpleName.asString() !in ENUM_SYNTHESIZED_FUNCTIONS }
      .toList()

    fun entryFor(function: KSFunctionDeclaration): ForwardCallableCatalogEntry {
      val name: String = function.simpleName.asString()
      val occurrence: Int = occurrences.merge(name, 1, Int::plus)!!
      val suffix: String = if (occurrence == 1) "" else "_$occurrence"
      val symbol = "$owner.$name$suffix"
      val structural: ForwardPlanSkipReason? = when {
        function.modifiers.contains(Modifier.SUSPEND) -> ForwardPlanSkipReason.SUSPEND
        function.typeParameters.isNotEmpty() -> ForwardPlanSkipReason.GENERIC
        else -> null
      }
      if (structural != null) {
        return ForwardCallableCatalogEntry
          .Skipped(symbol, structural, node = function)
          .namedSuspend()
      }
      return planOrSkip(
        symbol = symbol,
        publicName = function.csharpMemberName(),
        exportName = "${prefix}_${toCName(name)}$suffix",
        receiver = ForwardReceiver.Value(type),
        parameters = function.parameters.map { parameter ->
          parameter.bridgeName() to classifier.classify(parameter.type.resolve())
        },
        result = function.returnType?.resolve()?.let(classifier::classify) ?: BridgeType.Unit,
        origin = ForwardCallableOrigin.ENUM_MEMBER,
        target = owner,
        member = name,
        node = function,
        defaults = declaredDefaults(function.parameters, memberDefaultFlags(function)),
        doc = function.forwardKdoc(expects).forParameters(function.parameters),
      )
    }
    return members.map { member -> entryFor(member) }.nameUnroutedPositions()
  }

  /**
   * ADR-006 amendment, widened by the ADR-064 audit amendment: a SUSPEND deferral on an owner no
   * suspend route reads (an enum, an object, a companion, a value class, an unreachable interface)
   * has nothing to defer to, so it becomes the named structural drop `extensionEntry` already uses
   * for a suspend extension.
   */
  private fun ForwardCallableCatalogEntry.namedSuspend(): ForwardCallableCatalogEntry =
    if (this is ForwardCallableCatalogEntry.Skipped && reason == ForwardPlanSkipReason.SUSPEND) {
      ForwardCallableCatalogEntry.Skipped(
        symbol, ForwardPlanSkipReason.UNROUTED_POSITION, node = node,
        detail = ForwardPlanSkipReason.SUSPEND.name, structural = true,
      )
    } else {
      this
    }

  private fun companionEntries(cls: KSClassDeclaration): List<ForwardCallableCatalogEntry> {
    val owner: String = cls.qualifiedName?.asString() ?: return emptyList()
    val companion: KSClassDeclaration = cls.declarations.filterIsInstance<KSClassDeclaration>()
      .firstOrNull { it.isCompanionObject } ?: return emptyList()
    val prefix: String = cls.nativePrefix(symbols)
    val occurrences: MutableMap<String, Int> = mutableMapOf()
    val members: List<KSFunctionDeclaration> = companion.getAllFunctions()
      .filter { it.getVisibility() == Visibility.PUBLIC }
      // Issue #235: `Companion.serializer()` never becomes an entry, so it never becomes a skip.
      .filter { member -> !member.isCompilerOwnedMember(companion) }
      .toList()

    fun entryFor(function: KSFunctionDeclaration): ForwardCallableCatalogEntry {
      val name: String = function.simpleName.asString()
      val occurrence: Int = occurrences.merge(name, 1, Int::plus)!!
      val suffix: String = if (occurrence == 1) "" else "_$occurrence"
      return staticEntry(
        function = function,
        symbol = "$owner.Companion.$name$suffix",
        publicName = function.csharpMemberName(),
        exportName = "${prefix}_companion_${toCName(name)}$suffix",
        origin = ForwardCallableOrigin.COMPANION,
        target = owner,
        member = name,
        // ADR-074 amendment (2026-09-27): an `actual companion object` member widens from its
        // `expect`, resolved through the owner's `expect class`.
        defaults = memberDefaultFlags(function),
      )
    }
    // ADR-064 amendment (2026-09-13): a companion is a static owner like an object, and the
    // legacy routes are keyed to instance members; unmeasured, so a companion member appearing
    // in the diagnostic diff is worth checking against `Interop.cs` before it is believed. The
    // audit amendment: no suspend route reads a companion either (an enum's or a class's), so a
    // SUSPEND deferral here is a named drop too.
    return members.map { member -> entryFor(member).namedSuspend() }.nameUnroutedPositions()
  }

  private fun staticEntry(
    function: KSFunctionDeclaration,
    symbol: String,
    publicName: String,
    exportName: String,
    origin: ForwardCallableOrigin,
    target: String?,
    // ADR-095: the bare declared name for the Kotlin call site, since the symbol may carry `_$n`.
    member: String? = null,
    // ADR-164: per-parameter "has a default", positionally, from the route's own flag reader.
    defaults: List<Boolean>,
  ): ForwardCallableCatalogEntry {
    // ADR-197: an object or companion member's own type parameters route on the plan; a top-level
    // function's stay on the legacy generic-function route.
    val routesGeneric: Boolean = origin == ForwardCallableOrigin.OBJECT ||
        origin == ForwardCallableOrigin.COMPANION
    val structuralReason: ForwardPlanSkipReason? = when {
      function.modifiers.contains(Modifier.SUSPEND) -> ForwardPlanSkipReason.SUSPEND
      function.typeParameters.isNotEmpty() &&
          (!routesGeneric || function.forwardMemberGenericRefusal() != null) ->
        ForwardPlanSkipReason.GENERIC

      else -> null
    }
    if (structuralReason != null) {
      return ForwardCallableCatalogEntry.Skipped(symbol, structuralReason, node = function)
    }
    val returnType: KSType? = function.returnType?.resolve()
    // ADR-160 amendment: a lambda handed OUT is planned at a top-level function's result only (an
    // object or companion member returning one keeps its named CALLBACK_PROTOCOL skip), whatever
    // the function's parameters are. Asked before `classify`, which is position-agnostic and
    // would answer a parameter-position `Callback` or a `lambda` protocol.
    val result: BridgeType = returnType
      ?.let { type ->
        val returnedLambda: BridgeType.ReturnedLambda? =
          if (origin != ForwardCallableOrigin.TOP_LEVEL) null
          else classifier.returnedLambdaOrNull(type)
        returnedLambda ?: classifier.classify(type)
      }
      ?: BridgeType.Unit
    val parameters: List<Pair<String, BridgeType>> = function.parameters
      .map { parameter ->
        parameter.bridgeName() to classifier.classify(parameter.type.resolve())
      }
    val declaredDefaults: ForwardDeclaredDefaults = declaredDefaults(function.parameters, defaults)
    // ADR-170: a top-level nullable scalar (`Int?`, `Char?`, `Instant?`, `Duration?`, an enum, a
    // scalar value class) takes the same ADR-061 single-call `valueOut` route as a member, object
    // or companion function; the ADR-002 two-call pair is gone for functions.
    return planOrSkip(
      symbol = symbol,
      publicName = publicName,
      exportName = exportName,
      receiver = ForwardReceiver.Static,
      parameters = parameters,
      result = result,
      origin = origin,
      target = target,
      member = member,
      node = function,
      defaults = declaredDefaults,
      doc = function.forwardKdoc(expects).forParameters(function.parameters),
    ).withMethodTypeParameters(function)
  }

  /**
   * ADR-197: whether this member declares its own type parameters in a shape the plan does not
   * route ([forwardMemberGenericRefusal]), or as half of an add/remove pair, whose legacy routes
   * are keyed to non-generic members. Checked ahead of the pair's own `CALLBACK_PROTOCOL`, as the
   * structural `GENERIC` skip always was.
   */
  private fun KSFunctionDeclaration.isUnroutedGeneric(
    pairMembers: Set<KSFunctionDeclaration>,
  ): Boolean = typeParameters.isNotEmpty() &&
      (forwardMemberGenericRefusal() != null || this in pairMembers)

  /** ADR-197: stamps [method]'s own type parameters onto a planned entry's public signature. */
  private fun ForwardCallableCatalogEntry.withMethodTypeParameters(
    method: KSFunctionDeclaration,
  ): ForwardCallableCatalogEntry {
    if (this !is ForwardCallableCatalogEntry.Planned) return this
    if (method.typeParameters.isEmpty()) return this
    val signature: ForwardPublicSignature = plan.publicSignature
    return copy(
      plan = plan.copy(
        publicSignature = signature.copy(typeParameters = method.forwardMethodTypeParameters()),
      ),
    )
  }

  /**
   * ADR-133 amendment: the enclosing-owner chain of an extension receiver -- the receiver's own
   * declaring classes, outermost first (`Aviary` for `Aviary.Perch`, `Owner.Middle` for
   * `Owner.Middle.Inner`), and `""` for a top-level receiver, which is what keeps every shipped
   * extension symbol and overload number byte-identical.
   *
   * Deliberately NOT the receiver itself: it scopes the ADR-095 overload counter, which stays
   * receiver-agnostic within one owner by design.
   */
  private fun KSFunctionDeclaration.extensionOwnerChain(): String =
    ((extensionReceiver?.resolve()?.expandAliases()?.declaration as? KSClassDeclaration)
      ?.parentDeclaration as? KSClassDeclaration)?.nestedCsName() ?: ""

  private fun extensionEntry(
    function: KSFunctionDeclaration,
    suffix: String,
    // The entry points every non-extension route minted; see [ForwardSymbolTable.extension].
    taken: Set<String> = emptySet(),
  ): ForwardCallableCatalogEntry {
    // ADR-018: expanded once here, so every spelling taken off this receiver -- the entry-point
    // prefix below, the owner chain, the classified wire type -- comes from the same type the C#
    // half names (`CirTranslator` keys its extension class on `expandAliases()`).
    val receiver: KSType = requireNotNull(function.extensionReceiver) {
      "Forward extension planner received a non-extension function ${function.simpleName.asString()}"
    }.resolve().expandAliases()
    val receiverType: BridgeType = classifier.classify(receiver)
    val functionName: String = function.simpleName.asString()
    // ADR-133 amendment: the whole enclosing chain of the receiver, so an extension on
    // `Aviary.Perch` binds under `aviary_perch_` exactly as that type's own members already do.
    // `nativePrefix()` is byte-identical to the `simpleName.lowercase()` it replaces for a
    // top-level receiver; the elvis covers a receiver whose declaration is not a class (a type
    // parameter). ADR-018: a typealias receiver is expanded above, so `typealias Bird =
    // Aviary.Bird` binds under `aviary_bird_` exactly as the C# `AviaryBirdExtensions` class and
    // the extension *property* route already spell it.
    // ADR-163: the receiver's UNQUALIFIED chain. The package part of an extension's symbol is the
    // EXTENSION's own package, not the receiver's (ADR-095's counter is scoped per package and
    // name), so `symbols.extension` supplies the qualifier from `function` below.
    val receiverPrefix: String = (receiver.declaration as? KSClassDeclaration)
      ?.let { declaration -> ForwardSymbolTable.ownerChain(declaration) }
      ?: receiver.declaration.simpleName.asString().lowercase()
    // ADR-095 keeps an extension symbol receiver-agnostic (the overload counter is per package and
    // name, not per receiver). ADR-133 amendment: the receiver's enclosing-owner chain joins it,
    // because that chain now scopes the counter, so two nested receivers under different owners can
    // both take the unsuffixed name -- and the symbol identifies the dropped callable in
    // `droppedCallables` and in the diagnostic. Empty for a top-level receiver, so every shipped
    // extension symbol is unchanged.
    val ownerChain: String = function.extensionOwnerChain()
    val symbol: String = if (ownerChain.isEmpty()) {
      "${function.packageName.asString()}.$functionName$suffix"
    } else {
      "${function.packageName.asString()}.$ownerChain.$functionName$suffix"
    }

    // ADR-064 cell 23 / BUG-010: a generic + suspend + inline + reified extension returning
    // Result<T> has no legacy route at all — inline+reified erases at the C ABI and suspend
    // needs a concrete continuation type — so it must be recognized as a genuine drop *before*
    // the general SUSPEND/GENERIC structural checks below classify it as a silent legacy-route
    // deferral. That classification is correct for an *ordinary* suspend or generic extension
    // (each has its own working legacy route individually); it is wrong for this specific
    // combination, since nothing re-emits it. Gated narrowly (suspend + inline + a reified type
    // parameter + a `kotlin.Result` return) so ordinary suspend/generic extensions are unaffected.
    if (function.isUnsupportedSuspendGenericResultExtension()) {
      return ForwardCallableCatalogEntry.Skipped(
        symbol, ForwardPlanSkipReason.UNSUPPORTED_COMBINATION, node = function,
      )
    }

    val structuralReason: ForwardPlanSkipReason? = when {
      function.modifiers.contains(Modifier.SUSPEND) -> ForwardPlanSkipReason.SUSPEND
      function.typeParameters.isNotEmpty() -> ForwardPlanSkipReason.GENERIC
      else -> null
    }
    if (structuralReason != null) {
      // The structural half of the same amendment: `fun <T> Depot.tagged(value: T)` has no route
      // either (the generic-function route takes top-level functions, and ADR-197 class-like
      // members, never an extension), so its GENERIC deferral is named here too. SUSPEND is not a
      // candidate on any other owner (the suspend route is keyed to every one of them), but it is
      // here: no route emits a suspend EXTENSION at all (ROADMAP Phase 4 line 23 fold-in, verified
      // silent by the memo's spike), so it is named directly rather than through the candidate
      // set the other owners share.
      if (structuralReason == ForwardPlanSkipReason.SUSPEND) {
        return ForwardCallableCatalogEntry.Skipped(
          symbol, ForwardPlanSkipReason.UNROUTED_POSITION, node = function,
          detail = ForwardPlanSkipReason.SUSPEND.name, structural = true,
        )
      }
      return ForwardCallableCatalogEntry.Skipped(symbol, structuralReason, node = function)
        .nameUnroutedPosition { false }
    }

    // ADR-132 amendment: a receiver whose wire is the ADR-079/080 adjacent `receiverHasValue` +
    // `receiver` PAIR plans like any other; the plan carries it as its public receiver parameter
    // (nullable type plus minted flag slot), see `ForwardPublicSignature.receiver`.
    return planOrSkip(
      symbol = symbol,
      publicName = function.declaredCSharpName()
        ?: toCName(functionName).replaceFirstChar { it.uppercase() },
      exportName = symbols.extension(
        function, receiverPrefix, "${toCName(functionName)}$suffix", taken,
      ),
      // ADR-105 amendment: the receiver gets the same sealed rewrite scope (d) applies to every
      // declared parameter, here rather than in `planOrSkip`, because the extension route is the
      // only one that can hand it a protocol receiver (every other route builds a bare
      // `ObjectHandle` already). An eligible sealed base then plans as an ordinary handle
      // receiver; an ineligible or out-of-scope one carries no `sealedHandle` and still skips.
      receiver = ForwardReceiver.Value(receiverType.sealedAsHandle()),
      parameters = function.parameters.map { parameter ->
        parameter.bridgeName() to classifier.classify(parameter.type.resolve())
      },
      result = function.returnType?.resolve()?.let(classifier::classify) ?: BridgeType.Unit,
      origin = ForwardCallableOrigin.EXTENSION,
      member = functionName,
      node = function,
      // ADR-164: the receiver is a `ForwardReceiver.Value`, not a plan parameter, so no default
      // ever reaches it. ADR-074 amendment (2026-09-27): an `actual` extension's defaults are read
      // off its `expect`, matched with the receiver as part of the signature.
      defaults = declaredDefaults(function.parameters, expectDefaultFlags(function)),
      doc = function.forwardKdoc(expects).forParameters(function.parameters),
      extensionImportAlias =
        forwardExtensionImportAlias(function.packageName.asString(), functionName),
    )
      // ADR-064 amendment (2026-09-13): no legacy route is keyed to an extension for any of these
      // reasons (measured cells 6a/6b/13c/18c/22c; `translateExtensionFunction` has no caller at
      // all, and `genericFunctions` excludes extensions outright), so every deferral here is a
      // drop, with no exemption.
      .nameUnroutedPosition { false }
  }

  /**
   * ADR-064 cell 23 / BUG-010: the one suspend+generic extension shape with no working legacy
   * route (`suspend inline fun <reified T> Receiver.get(...): Result<T>`, NYTimes-KMP BUG-010).
   */
  private fun KSFunctionDeclaration.isUnsupportedSuspendGenericResultExtension(): Boolean {
    if (!modifiers.contains(Modifier.SUSPEND)) return false
    if (!modifiers.contains(Modifier.INLINE)) return false
    if (typeParameters.none { it.isReified }) return false
    val returnDeclaration: String? = returnType?.resolve()?.declaration?.qualifiedName?.asString()
    return returnDeclaration == "kotlin.Result"
  }

  private fun declaredDefaults(
    parameters: List<KSValueParameter>,
    flags: List<Boolean>,
    marked: Map<Int, String> = emptyMap(),
    // False for data-class `copy`: every one of its parameters defaults and it has no user-declared
    // siblings (Kotlin forbids declaring another `copy` beside the generated one), so nothing can
    // shadow it. Its parameters belong to the primary constructor, whose siblings are not `copy`'s.
    overloads: Boolean = true,
  ): ForwardDeclaredDefaults {
    val function: KSFunctionDeclaration? =
      if (overloads) parameters.firstOrNull()?.parent as? KSFunctionDeclaration else null
    val shadowed: Set<Int> =
      function?.shadowedDefaultIndices(flags, function.overloadSiblings()).orEmpty()
    return ForwardDeclaredDefaults(
      flags = flags.mapIndexed { index, flag -> flag && index !in shadowed },
      kotlinNames = parameters.map { parameter -> parameter.name?.asString() ?: "_" },
      marked = marked,
    )
  }

  /**
   * The Kotlin overloads a call to this function also resolves against: the other constructors of
   * its class, the same-name members its class sees (inherited included), or the same-name
   * top-level functions of its package, or for a top-level extension the same-name extensions of
   * its package on the same receiver type (a member extension is not walked: no route plans one).
   * Private ones are invisible to the generated export, so they never shadow.
   */
  private fun KSFunctionDeclaration.overloadSiblings(): Sequence<KSFunctionDeclaration> {
    val owner: KSClassDeclaration? = parentDeclaration as? KSClassDeclaration
    val name: String = simpleName.asString()
    val receiver: String? = extensionReceiver?.resolve()?.overloadKey()
    val siblings: Sequence<KSFunctionDeclaration> = when {
      receiver != null && owner != null -> emptySequence()
      receiver != null -> topLevelExtensions.asSequence().filter { function ->
        function.simpleName.asString() == name &&
            function.packageName.asString() == packageName.asString() &&
            function.extensionReceiver?.resolve()?.overloadKey() == receiver
      }
      owner == null -> topLevelFunctions.asSequence().filter { function ->
        function.simpleName.asString() == name &&
            function.packageName.asString() == packageName.asString()
      }
      isConstructor() -> owner.getConstructors()
      else -> owner.getAllFunctions().filter { function -> function.simpleName.asString() == name }
    }
    return siblings.filter { sibling -> !sibling.modifiers.contains(Modifier.PRIVATE) }
  }

  /** ADR-164: every top-level extension function the catalog walks, for [overloadSiblings]. */
  private var topLevelExtensions: List<KSFunctionDeclaration> = emptyList()

  /** ADR-164: every top-level function the catalog walks, for [overloadSiblings]. */
  private var topLevelFunctions: List<KSFunctionDeclaration> = emptyList()

  /**
   * ADR-164: the one widening pass. Turns the declared parameters of an entry into its public
   * parameters: each defaulted one whose nullable form is routable widens to it (an already
   * nullable one becomes `Optional<T>`), the trailing all-defaulted run becomes omittable, and an
   * unroutable defaulted parameter in that run is dropped so Kotlin evaluates its default.
   *
   * Everything else stays as declared, so a required-position problem still reaches the ordinary
   * skip checks and reports exactly what it did before. Two exceptions keep a defaulted parameter
   * from being dropped: an opt-in-marked TYPE (issue #128: Kotlin propagates the requirement from
   * the callee's declared parameter types, so no call that omits it is legal either), and a
   * parameter left required by the [MAX_OPTIONAL_DEFAULTS] cap.
   */
  private fun widen(
    declared: List<Pair<String, BridgeType>>,
    defaults: ForwardDeclaredDefaults?,
    // ADR-164: whether a per-call lambda can ride the PRESENCE encoding. False where the lambda
    // would be stored past the call (a constructor, `copy`), which ADR-160 forbids.
    presence: Boolean = false,
    // ADR-132 amendment: the value receiver, whose has-value flag is minted in the same pool.
    receiver: Pair<String, BridgeType>? = null,
  ): ForwardWidening = widenDeclared(declared, defaults, presence).withDerivedNames(receiver)

  /**
   * The generator names derived from each public parameter's name, minted here once and carried on
   * the parameter (and the dispatch mask on the widening) so every reader takes the same spelling.
   *
   * A derived name that a user parameter of the same callable already spells moves (`freshName`),
   * never the user's. Two namespaces, because the halves declare these names in different places:
   * - the Kotlin `@CName` export: its ABI slots (every user name, the plan-owned literals, the
   *   ADR-160 callback pair, and the minted `HasValue` / `IsSet` slots) plus the ADR-164
   *   dispatcher's body locals, which must not shadow any of them (a shadowing local compiles
   *   clean and hands the user's parameter the generator's value);
   * - the C# wrapper: its public parameters (the user names) plus the `Optional<T>` value local.
   *
   * Only a name a parameter actually uses is minted, so an unused spelling can never push a used
   * one off its unrenamed form.
   */
  private fun ForwardWidening.withDerivedNames(
    receiver: Pair<String, BridgeType>?,
  ): ForwardWidening {
    val users: Set<String> = parameters.map { parameter -> parameter.name }.toSet()
    val kotlin: MutableSet<String> = (users + PLAN_OWNED_NAMES).toMutableSet()
    fun MutableSet<String>.mint(base: String): String = freshName(base, this).also { add(it) }
    // ADR-132 amendment: the receiver's flag is minted first, so no parameter's derived name can
    // move it, and from the same pool, so a user parameter spelled `receiverHasValue` keeps its
    // name and the flag moves instead. Nothing is reserved library-wide.
    val publicReceiver: ForwardPublicParameter? = receiver?.let { (name, type) ->
      val flag: String = "${name}HasValue"
      ForwardPublicParameter(
        name = name,
        type = type,
        hasValueSlot = if (type.isHasValueFanOutInput()) kotlin.mint(flag) else flag,
      )
    }
    val slotted: List<ForwardPublicParameter> = parameters.map { parameter ->
      val callback: Boolean = parameter.type is BridgeType.Callback
      parameter.copy(
        hasValueSlot = if (parameter.type.isHasValueFanOutInput()) {
          kotlin.mint("${parameter.name}HasValue")
        } else {
          parameter.hasValueSlot
        },
        presenceSlot = if (parameter.hasPresenceSlot) {
          kotlin.mint("${parameter.name}IsSet")
        } else {
          parameter.presenceSlot
        },
        callbackPtrSlot =
          if (callback) kotlin.mint("${parameter.name}Ptr") else parameter.callbackPtrSlot,
        callbackUserDataSlot = if (callback) {
          kotlin.mint("${parameter.name}UserData")
        } else {
          parameter.callbackUserDataSlot
        },
      )
    }
    val csharp: MutableSet<String> = users.toMutableSet()
    val named: List<ForwardPublicParameter> = slotted.map { parameter ->
      val dispatched: Boolean = parameter.default != null &&
          parameter.default.encoding != ForwardDefaultEncoding.PRESENCE
      val optionalLocal: String =
        if (parameter.isOptional) csharp.mint("${parameter.name}Value") else parameter.optionalLocal
      parameter.copy(
        defaultLocal = if (dispatched) {
          kotlin.mint("default_${parameter.name}")
        } else {
          parameter.defaultLocal
        },
        optionalLocal = optionalLocal,
      )
    }.map { parameter ->
      // The C# wrapper reads an `Optional<T>` through its value local, so that is the stem its
      // composite locals were always spelled from.
      val base: String = if (parameter.isOptional) parameter.optionalLocal else parameter.name
      var stem: String = base
      while (CSHARP_LOCAL_SUFFIXES.any { suffix -> "$stem$suffix" in csharp }) stem += "_"
      csharp += CSHARP_LOCAL_SUFFIXES.map { suffix -> "$stem$suffix" }
      parameter.copy(localStem = stem)
    }
    val hasDefault: Boolean = parameters.any { parameter -> parameter.default != null }
    val dispatchMask: String = if (hasDefault) kotlin.mint("mask") else "mask"
    return copy(parameters = named, dispatchMask = dispatchMask, receiver = publicReceiver)
  }

  private fun widenDeclared(
    declared: List<Pair<String, BridgeType>>,
    defaults: ForwardDeclaredDefaults?,
    presence: Boolean,
  ): ForwardWidening {
    val plain = ForwardWidening(
      declared.mapIndexed { index, (name, type) ->
        ForwardPublicParameter(name, type, kotlinName = defaults?.kotlinNames?.getOrNull(index) ?: name)
      },
      marked = defaults?.marked?.values?.firstOrNull(),
    )
    if (defaults == null || defaults.flags.none { it }) return plain
    require(defaults.flags.size == declared.size && defaults.kotlinNames.size == declared.size) {
      "Forward planner default flags do not line up with the declared parameters"
    }
    val roles: MutableList<ForwardDefaultRole> = declared.mapIndexed { index, (_, type) ->
      when {
        !defaults.flags[index] -> ForwardDefaultRole.REQUIRED
        index in defaults.marked -> ForwardDefaultRole.UNROUTABLE
        type.optInMarkerDetail() != null -> ForwardDefaultRole.REQUIRED
        type is BridgeType.Nullable ->
          if (type.inputSkipReason() == null) ForwardDefaultRole.OPTIONAL
          else ForwardDefaultRole.UNROUTABLE

        BridgeType.Nullable(type).inputSkipReason() == null -> ForwardDefaultRole.NULLABLE
        presence && type is BridgeType.Callback && type.inputSkipReason() == null ->
          ForwardDefaultRole.PRESENCE

        else -> ForwardDefaultRole.UNROUTABLE
      }
    }.toMutableList()
    val widened: List<Int> = roles.indices.filter { index -> roles[index].isWidened }
    val capped: List<Int> = widened.dropLast(MAX_OPTIONAL_DEFAULTS)
    capped.forEach { index -> roles[index] = ForwardDefaultRole.REQUIRED }
    val suffix: Int = roles.indexOfLast { role -> role == ForwardDefaultRole.REQUIRED } + 1
    val marked: String? = defaults.marked.entries
      .firstOrNull { (index, _) -> index < suffix }
      ?.value
    val parameters: List<ForwardPublicParameter> = declared.mapIndexedNotNull { index, (name, type) ->
      val kotlinName: String = defaults.kotlinNames[index]
      val omittable: Boolean = index >= suffix
      when (roles[index]) {
        ForwardDefaultRole.REQUIRED -> ForwardPublicParameter(name, type, kotlinName = kotlinName)
        ForwardDefaultRole.UNROUTABLE ->
          if (omittable) null else ForwardPublicParameter(name, type, kotlinName = kotlinName)

        ForwardDefaultRole.NULLABLE -> ForwardPublicParameter(
          name, BridgeType.Nullable(type),
          ForwardParameterDefault(ForwardDefaultEncoding.NULLABLE, omittable), kotlinName,
        )

        ForwardDefaultRole.OPTIONAL -> ForwardPublicParameter(
          name, type, ForwardParameterDefault(ForwardDefaultEncoding.OPTIONAL, omittable), kotlinName,
        )

        ForwardDefaultRole.PRESENCE -> ForwardPublicParameter(
          name, type, ForwardParameterDefault(ForwardDefaultEncoding.PRESENCE, omittable), kotlinName,
        )
      }
    }
    return ForwardWidening(
      parameters = parameters,
      capped = capped.map { index -> defaults.kotlinNames[index] },
      marked = marked,
    )
  }

  /**
   * ADR-164: the native slots of one public parameter: an `Optional<T>` one leads with its BOOLEAN
   * `${name}IsSet` presence slot, then the nullable encoding every other nullable input uses.
   */
  private fun nativeInputParameters(parameter: ForwardPublicParameter): List<ForwardAbiParameter> {
    val encoded: List<ForwardAbiParameter> = nativeInputParameters(
      parameter.name, parameter.type, hasValueSlot = parameter.hasValueSlot,
      callbackSlots = listOf(parameter.callbackPtrSlot, parameter.callbackUserDataSlot),
    )
    if (!parameter.hasPresenceSlot) return encoded
    val presence = ForwardAbiParameter(
      name = parameter.presenceSlot,
      wireType = ForwardAbiWireType.BOOLEAN,
      direction = ForwardAbiDirection.IN,
      transfer = ForwardTransfer(
        parameter.presenceSlot, BridgeType.Primitive(PrimitiveKind.BOOLEAN),
        ForwardFlow.INTO_KOTLIN, ForwardPassing.VALUE, ForwardOwnership.BORROWED,
        ForwardConversion.DIRECT,
      ),
    )
    return listOf(presence) + encoded
  }

  /**
   * ADR-162: every ordinary callable funnels through here, so this is the one place a plan-time
   * generator invariant can be contained per declaration. A caught failure becomes a
   * [ForwardPlanSkipReason.INTERNAL_FAILURE] entry, which the catalog's own drop-reporting path
   * turns into a located fatal [ForwardDiagnosticKind.ERROR_INTERNAL_GENERATOR_FAILURE] — so the
   * planner still needs no logger, exactly as before.
   *
   * `Exception` only, never `Throwable`: an `OutOfMemoryError` is not a fact about one declaration.
   */
  private fun planOrSkip(
    symbol: String,
    publicName: String,
    exportName: String,
    receiver: ForwardReceiver,
    parameters: List<Pair<String, BridgeType>>,
    result: BridgeType,
    origin: ForwardCallableOrigin,
    target: String? = null,
    ownerType: ForwardGenericOwner? = null,
    invocationReceiver: String? = null,
    includeError: Boolean = true,
    valueClassProperty: Boolean = false,
    member: String? = null,
    isOverride: Boolean = false,
    isVirtual: Boolean = false,
    node: KSNode? = null,
    defaults: ForwardDeclaredDefaults? = null,
    doc: ForwardKdoc? = null,
    extensionImportAlias: String? = null,
    isAbstract: Boolean = false,
  ): ForwardCallableCatalogEntry = try {
    planOrSkipUnguarded(
      symbol, publicName, exportName, receiver, parameters, result, origin, target, ownerType,
      invocationReceiver, includeError, valueClassProperty, member, isOverride, isVirtual, node,
      defaults, doc, extensionImportAlias, isAbstract,
    )
  } catch (failure: Exception) {
    ForwardCallableCatalogEntry.Skipped(
      symbol,
      ForwardPlanSkipReason.INTERNAL_FAILURE,
      node = node,
      detail = internalFailureDetail(failure),
    )
  }

  private fun planOrSkipUnguarded(
    symbol: String,
    publicName: String,
    exportName: String,
    receiver: ForwardReceiver,
    parameters: List<Pair<String, BridgeType>>,
    result: BridgeType,
    origin: ForwardCallableOrigin,
    target: String? = null,
    // ADR-147: the applied Kotlin spelling of a generic owner, null for an ordinary class.
    ownerType: ForwardGenericOwner? = null,
    invocationReceiver: String? = null,
    includeError: Boolean = true,
    valueClassProperty: Boolean = false,
    member: String? = null,
    isOverride: Boolean = false,
    isVirtual: Boolean = false,
    node: KSNode? = null,
    // ADR-164: which of [parameters] have a Kotlin default, for the widening pass.
    defaults: ForwardDeclaredDefaults? = null,
    // ADR-150: the author's KDoc, already keyed by the bridge parameter names in [parameters].
    doc: ForwardKdoc? = null,
    // The alias an EXTENSION is imported and called under; see `ForwardInvocation`.
    extensionImportAlias: String? = null,
    isAbstract: Boolean = false,
  ): ForwardCallableCatalogEntry {
    // ADR-115: the author's own signal, checked before any type is looked at -- nothing about the
    // declaration is unsupported, it is simply not part of the exported surface. One check for
    // every route that reaches the plan (class member, object member, companion, extension,
    // top-level, value class), keyed on the declaration the entry already carries.
    val optInMarker: String? = (node as? KSAnnotated)?.optInMarker(classifier.exportMarkers)
    if (optInMarker != null) {
      return ForwardCallableCatalogEntry.Skipped(
        symbol, ForwardPlanSkipReason.OPT_IN_MARKER, node = node, detail = optInMarker,
      )
    }
    // A backticked name that is no identifier has no C# spelling unless the author declares one.
    // Constructors (`<init>`) and the generated box/copy routes name no declared member here.
    val declaredName: String? = (node as? KSDeclaration)?.simpleName?.asString()
      ?.takeIf { name -> name != "<init>" && origin in DECLARED_NAME_ORIGINS }
    if (declaredName != null && !declaredName.isPlainKotlinIdentifier() &&
      (node as KSDeclaration).declaredCSharpName() == null
    ) {
      return ForwardCallableCatalogEntry.Skipped(
        symbol, ForwardPlanSkipReason.NON_IDENTIFIER_NAME, node = node, detail = declaredName,
      )
    }
    // ADR-105 scope (d): the sealed rewrite is applied to every declared PARAMETER here, once,
    // rather than at each catalog site's `classifier.classify(...)` call, so the plan's public
    // signature, its ABI parameters and its input eligibility check all see the same rewritten
    // type. The receiver arrives already rewritten where one can be sealed at all: `extensionEntry`
    // applies the same rewrite to its receiver before calling in, and every other route builds a
    // bare `ObjectHandle` receiver, so this function stays receiver-agnostic.
    // ADR-164: then widened once, so every check below sees the public (possibly nullable) type.
    // A lambda is per-call only (ADR-160), so it widens only where it cannot be stored.
    val widening: ForwardWidening = widen(
      parameters.map { (name, type) -> name to type.sealedAsHandle() },
      defaults,
      presence = origin !in STORED_CALLBACK_ORIGINS,
      receiver = (receiver as? ForwardReceiver.Value)?.let { value -> value.name to value.type },
    )
    if (widening.marked != null) {
      return ForwardCallableCatalogEntry.Skipped(
        symbol, ForwardPlanSkipReason.OPT_IN_MARKER, node = node, detail = widening.marked,
      )
    }
    val publicParameters: List<ForwardPublicParameter> = widening.parameters
    val declared: List<Pair<String, BridgeType>> =
      publicParameters.map { parameter -> parameter.name to parameter.type }
    // Issue #131: name-carrying, so a skip can name the parameter that failed. The receiver rides
    // a null name: it is an input too, just not one the author named.
    val namedInputs: List<Pair<String?, BridgeType>> = buildList {
      when (receiver) {
        is ForwardReceiver.Value -> add(null to receiver.type)
        is ForwardReceiver.Handle -> add(null to receiver.type)
        ForwardReceiver.Static -> Unit
      }
      addAll(declared)
    }
    // ADR-160: a callback is per-call ONLY. The C# prelude allocates the GCHandle before the call
    // and frees it in the `finally`, which is correct exactly when the lambda cannot outlive the
    // crossing. A constructor (or a data-class `copy`, or an enum-arm box, or a value-class member)
    // normally STORES the lambda, and the first later invocation would dispatch the ADR-102 thunk
    // through a freed GCHandle. Those positions keep the `CALLBACK_PROTOCOL` skip, which is what
    // ADR-037's stored-callback route exists for.
    val storedCallback: Pair<String?, BridgeType>? = if (origin in STORED_CALLBACK_ORIGINS) {
      declared.firstOrNull { (_, type) -> type is BridgeType.Callback }
        ?.let { (name, type) -> name to type }
    } else {
      null
    }
    if (storedCallback != null) {
      return ForwardCallableCatalogEntry.Skipped(
        symbol, ForwardPlanSkipReason.CALLBACK_PROTOCOL, node = node,
        position = ForwardSkipPosition.INPUT,
        parameter = storedCallback.first,
      )
    }
    val inputTypes: List<BridgeType> = namedInputs.map { it.second }
    // ADR-201: a `Throwable` RECEIVER (the null-named input) stays deferred even though the same
    // type binds as a parameter: `fun Throwable.describe()` would otherwise become a C# extension
    // method on every `System.Exception`, whose receiver Kotlin sees only as a
    // `NugetManagedException`.
    fun skipReasonOf(name: String?, type: BridgeType): ForwardPlanSkipReason? =
      if (name == null && type.unwrapNullable() is BridgeType.Throwable) {
        ForwardPlanSkipReason.THROWABLE
      } else {
        type.inputSkipReason()
      }
    val ineligible: Pair<String?, BridgeType>? = namedInputs
      .firstOrNull { (name, type) -> skipReasonOf(name, type) != null }
    if (ineligible != null) {
      val ineligibleType: BridgeType = ineligible.second
      return ForwardCallableCatalogEntry.Skipped(
        symbol, requireNotNull(skipReasonOf(ineligible.first, ineligibleType)), node = node,
        detail = ineligibleType.optInMarkerDetail()
          ?: ineligibleType.actualTypeAliasTargetDetail()
          ?: ineligibleType.unexportedDependencyDetail()
          ?: ineligibleType.undeclaredTypeDetail()
          ?: ineligibleType.sealedTypeDetail()
          ?: ineligibleType.collectionComponentDetail()
          ?: ineligibleType.unsupportedTypeDetail()
          // ADR-201: the declared throwable spelling, for THROWABLE's reason line.
          ?: ineligibleType.throwableInputDetail(),
        position = ForwardSkipPosition.INPUT,
        parameter = ineligible.first,
      )
    }

    // ADR-108: `Result<T>` at an ordinary return position is lowered to `T` here, before any shape
    // is taken, and the invocation is flagged so the Kotlin export appends `.getOrThrow()`. The
    // fallback is load-bearing: when `T` has no return shape the plan keeps the ORIGINAL
    // `Result`'s skip reason (VALUE_CLASS), never `T`'s -- a `Result<Shape>` reporting the inner
    // sealed type's own skip would name a position the author did not write, since the legacy
    // re-emit keys on the declared return type.
    // ADR-105 (issue #54): a sealed base at a RESULT position binds as the ObjectHandle the
    // classifier already carries, at EVERY origin -- top-level, class member, object member,
    // companion. Before this, only a top-level sealed return was re-emitted (by the named legacy
    // adapter in exports/FunctionExports.kt), and every member spelling took a skip that no route
    // re-emitted, so the member was dropped with no C# member and no diagnostic. Scope (d) applies
    // the same rewrite to the parameters above.
    val plannedResult: BridgeType = result.sealedAsHandle()
    val unwrappedResult: BridgeType? = plannedResult.kotlinResultPayloadOrNull(origin)
    val effectiveResult: BridgeType =
      if (unwrappedResult != null && unwrappedResult.shapeOrNull() != null) {
        unwrappedResult
      } else {
        plannedResult
      }
    val unwrapsKotlinResult: Boolean = effectiveResult !== plannedResult

    val resultShape: ForwardResultShape? = effectiveResult.shapeOrNull()
    if (resultShape == null) {
      val returnSkipReason: ForwardPlanSkipReason = requireNotNull(plannedResult.skipReason())
      return ForwardCallableCatalogEntry.Skipped(
        symbol, returnSkipReason, node = node,
        detail = (plannedResult as? BridgeType.ReturnedLambda)?.unnameableTypeArgument()
          ?: plannedResult.optInMarkerDetail()
          ?: plannedResult.actualTypeAliasTargetDetail()
          ?: plannedResult.unexportedDependencyDetail()
          ?: plannedResult.undeclaredTypeDetail()
          ?: plannedResult.sealedTypeDetail()
          ?: plannedResult.collectionComponentDetail()
          ?: plannedResult.unsupportedTypeDetail(),
        // ADR-064 amendment (2026-09-13): the default already, stated explicitly because the
        // unrouted-position reclassification reads it — a `fun <T> f(): List<T>` and a
        // `fun f(): Flow<Int>` both have to report RETURN, and an implicit default is not
        // something the next reader of that reclassification can check.
        position = ForwardSkipPosition.RETURN,
        // Nullable-return diagnostic (ADR-064 amendment 2026-09-29): the declared result spelling,
        // for NULLABLE only; `detail` stays as is because other routes key their wording on it.
        returnType = if (returnSkipReason == ForwardPlanSkipReason.NULLABLE) {
          node.declaredResultType()?.kotlinSpelling()
        } else {
          null
        },
      )
    }

    val error: ForwardAbiParameter? = if (includeError) errorParameter() else null
    // The C# `TryX` twin's flag: directly before the error slot, which must stay last.
    val resultFailed: ForwardAbiParameter? =
      if (unwrapsKotlinResult && error != null) resultFailedParameter() else null
    val nativeParameters: List<ForwardAbiParameter> =
      receiverParameter(receiver, widening.receiver) +
        publicParameters.flatMap { parameter -> nativeInputParameters(parameter) } +
        resultShape.extraParameters + listOfNotNull(resultFailed, error)
    val nativeCall = ForwardNativeCall(
      // Identity for every identifier-named callable; a `@CSharpName`d backticked one (`tug hard`)
      // reaches here and its C entry point cannot carry the space.
      exportName = exportName.asCSymbol(),
      result = resultShape.wireType,
      parameters = nativeParameters,
    )
    val helpers: Set<ForwardHelperRequirement> = buildSet {
      add(ForwardHelperRequirement.STABLE_REF)
      if (origin == ForwardCallableOrigin.VALUE_CLASS) add(ForwardHelperRequirement.VALUE_CLASS)
      addAll(resultShape.helperRequirements)
      // ADR-077: a value-class *input* also needs the helper, otherwise the validator's
      // `requiredConversion.helper() in helperRequirements` check rejects its BOX_VALUE_CLASS
      // transfer. The underlying carries its own helper in (UTF-8 / enum ordinal per kind).
      inputTypes
        .mapNotNull { type -> (type.unwrapNullable() as? BridgeType.ValueClass)?.underlying }
        .forEach { underlying ->
          add(ForwardHelperRequirement.VALUE_CLASS)
          if (underlying == BridgeType.String) add(ForwardHelperRequirement.UTF8)
          if (underlying is BridgeType.Enum) add(ForwardHelperRequirement.ENUM_ORDINAL)
        }
      if (inputTypes.any { type -> type.unwrapNullable() == BridgeType.String }) {
        add(ForwardHelperRequirement.UTF8)
      }
      if (inputTypes.any { type -> type.unwrapNullable() is BridgeType.Enum }) {
        add(ForwardHelperRequirement.ENUM_ORDINAL)
      }
      if (inputTypes.any { type -> type.unwrapNullable() is BridgeType.Collection }) {
        add(ForwardHelperRequirement.COLLECTION)
      }
      // ADR-151: the bytes helpers ride the collection row's slot, with their own P/Invoke class.
      // ROADMAP Phase 4: recursive, because a `List<ByteArray>` parameter needs `CreateBytes` per
      // element just as a bare `ByteArray` parameter needs it once.
      if (inputTypes.any { type -> type.containsByteArray() }) {
        add(ForwardHelperRequirement.BYTES)
      }
      if (inputTypes.any { type -> type.unwrapNullable() == BridgeType.Instant }) {
        add(ForwardHelperRequirement.INSTANT)
      }
      // ADR-103: the same input helper requirement for Duration.
      if (inputTypes.any { type -> type.unwrapNullable() == BridgeType.Duration }) {
        add(ForwardHelperRequirement.DURATION)
      }
      // ADR-106: the same pairing for Uuid, whose conversion tags are STRING_TO_UUID/
      // UUID_TO_STRING; no helper function is generated for it (the stdlib's own surface is used).
      if (inputTypes.any { type -> type.unwrapNullable() == BridgeType.Uuid }) {
        add(ForwardHelperRequirement.UUID)
      }
      // ADR-088: the reverse pipeline already generated these helpers into the same compilation;
      // the requirement is recorded only so the validator's conversion/helper pairing check holds.
      if (inputTypes.any { type -> type.unwrapNullable() is BridgeType.BoundInterface }) {
        add(ForwardHelperRequirement.BOUND_INTERFACE)
      }
      // ADR-201: the pairing for a `Throwable` input's STRING_TO_MANAGED_EXCEPTION.
      if (inputTypes.any { type -> type.unwrapNullable() is BridgeType.Throwable }) {
        add(ForwardHelperRequirement.ERROR_TRANSFER)
      }
    }
    val plan = ForwardCallablePlan(
      invocation = ForwardInvocation(
        symbol = symbol,
        receiver = invocationReceiver,
        origin = origin,
        target = if (valueClassProperty) "$target#property" else target,
        member = member,
        unwrapsKotlinResult = unwrapsKotlinResult,
        ownerType = ownerType,
        extensionImportAlias = extensionImportAlias,
      ),
      publicSignature = ForwardPublicSignature(
        name = publicName,
        parameters = publicParameters,
        result = effectiveResult,
        isOverride = isOverride,
        isVirtual = isVirtual,
        isAbstract = isAbstract,
        doc = doc.forPublic(publicParameters),
        dispatchMask = widening.dispatchMask,
        receiver = widening.receiver,
      ),
      evaluation = ForwardEvaluation.EXACTLY_ONCE,
      nativeExports = listOf(nativeCall),
      nativeImports = listOf(nativeCall),
      result = ForwardResultConvention(
        wireType = resultShape.wireType,
        transfer = resultShape.transfer,
      ),
      errorSlot = error,
      cleanup = resultShape.cleanup,
      helperRequirements = helpers,
    ).validate()
    return ForwardCallableCatalogEntry.Planned(plan, node = node, cappedDefaults = widening.capped)
  }

  /**
   * ADR-108: the payload of a `kotlin.Result<T>` result, or null when this type is not a `Result`
   * or the position cannot carry the throw.
   *
   * [ForwardCallableOrigin.VALUE_CLASS] members keep the ADR-014 no-errorOut ABI, so a
   * `getOrThrow()` there would have no slot to write and would abort the process; a constructor
   * cannot return anything but its own type.
   */
  private fun BridgeType.kotlinResultPayloadOrNull(origin: ForwardCallableOrigin): BridgeType? {
    if (this !is BridgeType.ValueClass || qualifiedName != "kotlin.Result") return null
    if (origin == ForwardCallableOrigin.VALUE_CLASS) return null
    if (origin == ForwardCallableOrigin.CONSTRUCTOR) return null
    return typeArguments.singleOrNull()
  }

  private fun errorParameter(): ForwardAbiParameter = ForwardAbiParameter(
    name = "errorOut",
    wireType = ForwardAbiWireType.POINTER,
    direction = ForwardAbiDirection.OUT,
    transfer = ForwardTransfer(
      subject = "error",
      type = BridgeType.ObjectHandle("kotlin.Throwable"),
      flow = ForwardFlow.OUT_OF_KOTLIN,
      passing = ForwardPassing.OUT,
      ownership = ForwardOwnership.BORROWED,
      conversion = ForwardConversion.STABLE_REF_TO_HANDLE,
    ),
    role = ForwardAbiRole.ERROR,
  )

  /**
   * The one-byte `Result` failure flag (ADR-108's Try twin). A POINTER wire, because that is what
   * renders an OUT slot as `COpaquePointer?` in the export; the Boolean transfer is what gives the
   * C# extern its `[MarshalAs(UnmanagedType.I1)]` (ADR-069).
   */
  private fun resultFailedParameter(): ForwardAbiParameter = ForwardAbiParameter(
    name = RESULT_FAILED_SLOT,
    wireType = ForwardAbiWireType.POINTER,
    direction = ForwardAbiDirection.OUT,
    transfer = ForwardTransfer(
      subject = RESULT_FAILED_SLOT,
      type = BridgeType.Primitive(PrimitiveKind.BOOLEAN),
      flow = ForwardFlow.OUT_OF_KOTLIN,
      passing = ForwardPassing.OUT,
      ownership = ForwardOwnership.BORROWED,
      conversion = ForwardConversion.DIRECT,
    ),
    role = ForwardAbiRole.RESULT_FAILED_OUT,
  )

  private fun valueParameter(
    name: String,
    type: BridgeType,
    flow: ForwardFlow,
    role: ForwardAbiRole = ForwardAbiRole.USER,
  ): ForwardAbiParameter = ForwardAbiParameter(
    name = name,
    wireType = type.wireType(),
    direction = ForwardAbiDirection.IN,
    transfer = transfer(name, type, flow),
    role = role,
  )

  /**
   * The native ABI shape for one declared input parameter. Almost every [BridgeType] fans out to
   * exactly one native parameter; a nullable primitive is the sole exception, fanning out to two
   * *adjacent* native parameters (`${name}HasValue` then `name`) in place of the single public
   * parameter, so callers must `flatMap` over the declared parameter list rather than `map`.
   */
  private fun nativeInputParameters(
    name: String,
    type: BridgeType,
    role: ForwardAbiRole = ForwardAbiRole.USER,
    // The planner-minted name of the fan-out's BOOLEAN slot
    // (`ForwardPublicParameter.hasValueSlot`); a value receiver passes its own minted one too
    // (ADR-132 amendment), so the default is only the unrenamed spelling.
    hasValueSlot: String = "${name}HasValue",
    // Likewise `ForwardPublicParameter.callbackPtrSlot` / `callbackUserDataSlot`.
    callbackSlots: List<String> = listOf("${name}Ptr", "${name}UserData"),
  ): List<ForwardAbiParameter> = when (type) {
    is BridgeType.Primitive, BridgeType.Char, BridgeType.String -> listOf(
      valueParameter(name, type, ForwardFlow.INTO_KOTLIN, role),
    )

    // ADR-160: the only input that fans out to two slots of its OWN type, ADR-102's AOT-safe pair
    // -- the `[UnmanagedCallersOnly]` thunk address, then the `GCHandle` ctx of the managed
    // delegate the thunk dispatches to. Both BORROWED with no conversion: the C# wrapper allocates
    // and frees the GCHandle around the call, Kotlin only reinterprets the address for the duration
    // of the invocation and stores nothing (per-call, never a stored ADR-037 subscription).
    is BridgeType.Callback -> callbackSlots.map { slot ->
      ForwardAbiParameter(
        name = slot,
        wireType = ForwardAbiWireType.POINTER,
        direction = ForwardAbiDirection.IN,
        transfer = ForwardTransfer(
          slot, type, ForwardFlow.INTO_KOTLIN, ForwardPassing.VALUE,
          ForwardOwnership.BORROWED, null,
        ),
        role = role,
      )
    }

    // ADR-076: the wire value is a raw INT64 of ticks; the Kotlin export converts it back to an
    // Instant via the TICKS_TO_INSTANT helper before use.
    BridgeType.Instant -> listOf(
      ForwardAbiParameter(
        name = name,
        wireType = ForwardAbiWireType.INT64,
        direction = ForwardAbiDirection.IN,
        transfer = ForwardTransfer(
          name, type, ForwardFlow.INTO_KOTLIN, ForwardPassing.VALUE,
          ForwardOwnership.BORROWED, ForwardConversion.TICKS_TO_INSTANT,
        ),
        role = role,
      )
    )

    // ADR-201: the managed exception's `"{FullName}: {Message}"` text; the Kotlin export splits it
    // at the first ": " (a CLR full name never contains one) into a `NugetManagedException`.
    is BridgeType.Throwable -> listOf(
      ForwardAbiParameter(
        name = name,
        wireType = ForwardAbiWireType.STRING,
        direction = ForwardAbiDirection.IN,
        transfer = ForwardTransfer(
          name, type, ForwardFlow.INTO_KOTLIN, ForwardPassing.VALUE,
          ForwardOwnership.BORROWED, ForwardConversion.STRING_TO_MANAGED_EXCEPTION,
        ),
        role = role,
      )
    )

    // ADR-106: the wire value is the RFC 9562 hex-dash text; the Kotlin export parses it back with
    // `Uuid.parse` before use, the STRING_TO_UUID step.
    BridgeType.Uuid -> listOf(
      ForwardAbiParameter(
        name = name,
        wireType = ForwardAbiWireType.STRING,
        direction = ForwardAbiDirection.IN,
        transfer = ForwardTransfer(
          name, type, ForwardFlow.INTO_KOTLIN, ForwardPassing.VALUE,
          ForwardOwnership.BORROWED, ForwardConversion.STRING_TO_UUID,
        ),
        role = role,
      )
    )

    // ADR-103: identical shape, a raw INT64 of TimeSpan ticks converted back by TICKS_TO_DURATION.
    BridgeType.Duration -> listOf(
      ForwardAbiParameter(
        name = name,
        wireType = ForwardAbiWireType.INT64,
        direction = ForwardAbiDirection.IN,
        transfer = ForwardTransfer(
          name, type, ForwardFlow.INTO_KOTLIN, ForwardPassing.VALUE,
          ForwardOwnership.BORROWED, ForwardConversion.TICKS_TO_DURATION,
        ),
        role = role,
      )
    )

    is BridgeType.Enum -> listOf(
      ForwardAbiParameter(
        name = name,
        wireType = ForwardAbiWireType.INT32,
        direction = ForwardAbiDirection.IN,
        transfer = ForwardTransfer(
          name, type, ForwardFlow.INTO_KOTLIN, ForwardPassing.VALUE,
          ForwardOwnership.BORROWED, ForwardConversion.ORDINAL_TO_ENUM,
        ),
        role = role,
      )
    )

    // ADR-147: a `T` parameter rides the identical boxed StableRef wire a handle does.
    is BridgeType.ObjectHandle, is BridgeType.Interface, is BridgeType.TypeParameter -> listOf(
      ForwardAbiParameter(
        name = name,
        wireType = ForwardAbiWireType.POINTER,
        direction = ForwardAbiDirection.IN,
        transfer = ForwardTransfer(
          name, type, ForwardFlow.INTO_KOTLIN, ForwardPassing.VALUE,
          ForwardOwnership.BORROWED, ForwardConversion.HANDLE_TO_STABLE_REF,
        ),
        role = role,
      )
    )

    // ADR-088: same POINTER/IN wire as the two above, but the pointer is a transfer GCHandle the
    // C# wrapper allocated. Kotlin RECEIVES ownership (`nuget{Iface}Value` either frees it on a
    // token-probe hit or hands it to the wrapper's cleaner), which is why the transfer is not
    // BORROWED: nothing on the C# side frees it after the call.
    is BridgeType.BoundInterface -> listOf(
      ForwardAbiParameter(
        name = name,
        wireType = ForwardAbiWireType.POINTER,
        direction = ForwardAbiDirection.IN,
        transfer = ForwardTransfer(
          name, type, ForwardFlow.INTO_KOTLIN, ForwardPassing.VALUE,
          ForwardOwnership.MATERIALIZED, ForwardConversion.GC_HANDLE_TO_BOUND_VALUE,
        ),
        role = role,
      )
    )

    // ADR-077: one native parameter carrying the underlying's wire value (String/primitive
    // directly, enum as its int ordinal, ObjectHandle as a StableRef pointer). The transfer keeps
    // the *value class* as its type and tags BOX_VALUE_CLASS, which is what
    // `ForwardCallablePlanValidator.requiredConversion` demands; the underlying's own step
    // composes inside the emitted expression rather than stacking a conversion.
    is BridgeType.ValueClass -> listOf(
      ForwardAbiParameter(
        name = name,
        wireType = type.underlying.underlyingWireType(),
        direction = ForwardAbiDirection.IN,
        transfer = ForwardTransfer(
          name, type, ForwardFlow.INTO_KOTLIN, ForwardPassing.VALUE,
          ForwardOwnership.BORROWED, ForwardConversion.BOX_VALUE_CLASS,
        ),
        role = role,
      )
    )

    // ADR-151: the collection row's slot exactly, with the bytes conversion tag; the C# prelude
    // mints the handle with `NugetMarshal.CreateBytes` and the cleanup disposes it.
    BridgeType.ByteArray -> listOf(
      ForwardAbiParameter(
        name = name,
        wireType = ForwardAbiWireType.POINTER,
        direction = ForwardAbiDirection.IN,
        transfer = ForwardTransfer(
          name, type, ForwardFlow.INTO_KOTLIN, ForwardPassing.VALUE,
          ForwardOwnership.BORROWED, ForwardConversion.HANDLE_TO_BYTES,
        ),
        role = role,
      )
    )

    // ADR-073: the POINTER / IN / HANDLE_TO_COLLECTION shape is the same for all six collection
    // kinds; only the C# prelude/cleanup factory and the Kotlin lowering expression are kind-aware.
    is BridgeType.Collection -> listOf(
      ForwardAbiParameter(
        name = name,
        wireType = ForwardAbiWireType.POINTER,
        direction = ForwardAbiDirection.IN,
        transfer = ForwardTransfer(
          name, type, ForwardFlow.INTO_KOTLIN, ForwardPassing.VALUE,
          ForwardOwnership.BORROWED, ForwardConversion.HANDLE_TO_COLLECTION,
        ),
        role = role,
      )
    )

    is BridgeType.Nullable -> when (val inner = type.type) {
      BridgeType.String -> listOf(
        ForwardAbiParameter(
          name = name,
          wireType = ForwardAbiWireType.STRING,
          direction = ForwardAbiDirection.IN,
          transfer = ForwardTransfer(
            name, type, ForwardFlow.INTO_KOTLIN, ForwardPassing.VALUE,
            ForwardOwnership.BORROWED, ForwardConversion.STRING_TO_UTF8,
          ),
          role = role,
        )
      )

      // ADR-201: `Exception?` rides the String wire's null pointer, like `Uuid?`.
      is BridgeType.Throwable -> listOf(
        ForwardAbiParameter(
          name = name,
          wireType = ForwardAbiWireType.STRING,
          direction = ForwardAbiDirection.IN,
          transfer = ForwardTransfer(
            name, type, ForwardFlow.INTO_KOTLIN, ForwardPassing.VALUE,
            ForwardOwnership.BORROWED, ForwardConversion.STRING_TO_MANAGED_EXCEPTION,
          ),
          role = role,
        )
      )

      // ADR-106: `Uuid?` rides the String wire's null pointer, NOT Instant's has-value channel --
      // a single slot whose null means null.
      BridgeType.Uuid -> listOf(
        ForwardAbiParameter(
          name = name,
          wireType = ForwardAbiWireType.STRING,
          direction = ForwardAbiDirection.IN,
          transfer = ForwardTransfer(
            name, type, ForwardFlow.INTO_KOTLIN, ForwardPassing.VALUE,
            ForwardOwnership.BORROWED, ForwardConversion.STRING_TO_UUID,
          ),
          role = role,
        )
      )

      // ADR-147: `T?` rides the null pointer on the same slot, ADR-083's shape.
      is BridgeType.ObjectHandle, is BridgeType.Interface, is BridgeType.TypeParameter -> listOf(
        ForwardAbiParameter(
          name = name,
          wireType = ForwardAbiWireType.POINTER,
          direction = ForwardAbiDirection.IN,
          transfer = ForwardTransfer(
            name, type, ForwardFlow.INTO_KOTLIN, ForwardPassing.VALUE,
            ForwardOwnership.BORROWED, ForwardConversion.HANDLE_TO_STABLE_REF,
          ),
          role = role,
        )
      )

      // ADR-151: `ByteArray?` is the same null-pointer sentinel a nullable collection rides.
      BridgeType.ByteArray -> listOf(
        ForwardAbiParameter(
          name = name,
          wireType = ForwardAbiWireType.POINTER,
          direction = ForwardAbiDirection.IN,
          transfer = ForwardTransfer(
            name, type, ForwardFlow.INTO_KOTLIN, ForwardPassing.VALUE,
            ForwardOwnership.BORROWED, ForwardConversion.HANDLE_TO_BYTES,
          ),
          role = role,
        )
      )

      // ADR-075: the wire value's own nullability (`IntPtr.Zero` for a null collection reference)
      // is completely independent of the collection's element eligibility, already checked by
      // `inputSkipReason()` before this ever runs — same POINTER / HANDLE_TO_COLLECTION shape as
      // the non-null case three cases above, the C# side's `CreateList(...) : IntPtr.Zero`
      // ternary is the only difference (ForwardCirPlanProjection.collectionPrelude).
      is BridgeType.Collection -> listOf(
        ForwardAbiParameter(
          name = name,
          wireType = ForwardAbiWireType.POINTER,
          direction = ForwardAbiDirection.IN,
          transfer = ForwardTransfer(
            name, type, ForwardFlow.INTO_KOTLIN, ForwardPassing.VALUE,
            ForwardOwnership.BORROWED, ForwardConversion.HANDLE_TO_COLLECTION,
          ),
          role = role,
        )
      )

      // ADR-077 sub-items 3/4: one pointer-shaped parameter like the non-null value-class input
      // above (STRING wire for a String underlying, POINTER for an ObjectHandle one); the
      // transfer records the *nullable* type so both emitters lower with null propagation.
      // ADR-079: a Primitive/Enum underlying has no in-band null on its wire, so it fans out to
      // the same adjacent HasValue pair the nullable-primitive and nullable-Instant cases below
      // use, with the value slot carrying the (non-null) value class + BOX_VALUE_CLASS -- exactly
      // how Instant's pair carries Instant + TICKS_TO_INSTANT.
      is BridgeType.ValueClass -> if (inner.underlying.isHasValueFanOutUnderlying()) {
        listOf(
          ForwardAbiParameter(
            name = hasValueSlot,
            wireType = ForwardAbiWireType.BOOLEAN,
            direction = ForwardAbiDirection.IN,
            transfer = ForwardTransfer(
              hasValueSlot, BridgeType.Primitive(PrimitiveKind.BOOLEAN),
              ForwardFlow.INTO_KOTLIN, ForwardPassing.VALUE, ForwardOwnership.BORROWED,
              ForwardConversion.DIRECT,
            ),
          ),
          ForwardAbiParameter(
            name = name,
            wireType = inner.underlying.underlyingWireType(),
            direction = ForwardAbiDirection.IN,
            transfer = ForwardTransfer(
              name, inner, ForwardFlow.INTO_KOTLIN, ForwardPassing.VALUE,
              ForwardOwnership.BORROWED, ForwardConversion.BOX_VALUE_CLASS,
            ),
            role = role,
          ),
        )
      } else {
        listOf(
          ForwardAbiParameter(
            name = name,
            wireType = inner.underlying.underlyingWireType(),
            direction = ForwardAbiDirection.IN,
            transfer = ForwardTransfer(
              name, type, ForwardFlow.INTO_KOTLIN, ForwardPassing.VALUE,
              ForwardOwnership.BORROWED, ForwardConversion.BOX_VALUE_CLASS,
            ),
            role = role,
          )
        )
      }

      // ADR-098 amendment (boundary nullability part C): `Char?` takes the identical adjacent pair,
      // the value slot carrying CHAR16 by value. A by-value `char` slot is exactly what a
      // non-null `Char` parameter already uses, so ADR-098's `[MarshalAs(UnmanagedType.U2)]`
      // covers it and no `out char` (which silently narrows every non-ASCII character) is ever
      // minted.
      is BridgeType.Primitive, BridgeType.Char -> listOf(
        ForwardAbiParameter(
          name = hasValueSlot,
          wireType = ForwardAbiWireType.BOOLEAN,
          direction = ForwardAbiDirection.IN,
          transfer = ForwardTransfer(
            hasValueSlot, BridgeType.Primitive(PrimitiveKind.BOOLEAN), ForwardFlow.INTO_KOTLIN,
            ForwardPassing.VALUE, ForwardOwnership.BORROWED, ForwardConversion.DIRECT,
          ),
        ),
        ForwardAbiParameter(
          name = name,
          wireType = inner.wireType(),
          direction = ForwardAbiDirection.IN,
          transfer = ForwardTransfer(
            name, inner, ForwardFlow.INTO_KOTLIN, ForwardPassing.VALUE,
            ForwardOwnership.BORROWED, ForwardConversion.DIRECT,
          ),
          role = role,
        ),
      )

      // ADR-080: same adjacent-pair shape, the value slot carrying the `int` ordinal with the
      // ORDINAL_TO_ENUM conversion (ADR-079's enum-underlying value class minus the box).
      is BridgeType.Enum -> listOf(
        ForwardAbiParameter(
          name = hasValueSlot,
          wireType = ForwardAbiWireType.BOOLEAN,
          direction = ForwardAbiDirection.IN,
          transfer = ForwardTransfer(
            hasValueSlot, BridgeType.Primitive(PrimitiveKind.BOOLEAN), ForwardFlow.INTO_KOTLIN,
            ForwardPassing.VALUE, ForwardOwnership.BORROWED, ForwardConversion.DIRECT,
          ),
        ),
        ForwardAbiParameter(
          name = name,
          wireType = ForwardAbiWireType.INT32,
          direction = ForwardAbiDirection.IN,
          transfer = ForwardTransfer(
            name, inner, ForwardFlow.INTO_KOTLIN, ForwardPassing.VALUE,
            ForwardOwnership.BORROWED, ForwardConversion.ORDINAL_TO_ENUM,
          ),
          role = role,
        ),
      )

      // ADR-076 §4.1: same adjacent-pair shape as the nullable-primitive case above, except the
      // value slot carries the TICKS_TO_INSTANT conversion (the wire value is still a raw INT64).
      BridgeType.Instant -> listOf(
        ForwardAbiParameter(
          name = hasValueSlot,
          wireType = ForwardAbiWireType.BOOLEAN,
          direction = ForwardAbiDirection.IN,
          transfer = ForwardTransfer(
            hasValueSlot, BridgeType.Primitive(PrimitiveKind.BOOLEAN), ForwardFlow.INTO_KOTLIN,
            ForwardPassing.VALUE, ForwardOwnership.BORROWED, ForwardConversion.DIRECT,
          ),
        ),
        ForwardAbiParameter(
          name = name,
          wireType = ForwardAbiWireType.INT64,
          direction = ForwardAbiDirection.IN,
          transfer = ForwardTransfer(
            name, inner, ForwardFlow.INTO_KOTLIN, ForwardPassing.VALUE,
            ForwardOwnership.BORROWED, ForwardConversion.TICKS_TO_INSTANT,
          ),
          role = role,
        ),
      )

      // ADR-103: identical to the Instant pair above, TICKS_TO_DURATION on the value slot.
      BridgeType.Duration -> listOf(
        ForwardAbiParameter(
          name = hasValueSlot,
          wireType = ForwardAbiWireType.BOOLEAN,
          direction = ForwardAbiDirection.IN,
          transfer = ForwardTransfer(
            hasValueSlot, BridgeType.Primitive(PrimitiveKind.BOOLEAN), ForwardFlow.INTO_KOTLIN,
            ForwardPassing.VALUE, ForwardOwnership.BORROWED, ForwardConversion.DIRECT,
          ),
        ),
        ForwardAbiParameter(
          name = name,
          wireType = ForwardAbiWireType.INT64,
          direction = ForwardAbiDirection.IN,
          transfer = ForwardTransfer(
            name, inner, ForwardFlow.INTO_KOTLIN, ForwardPassing.VALUE,
            ForwardOwnership.BORROWED, ForwardConversion.TICKS_TO_DURATION,
          ),
          role = role,
        ),
      )

      BridgeType.Unit,
      is BridgeType.BoundInterface,
      is BridgeType.Nullable,
      is BridgeType.Callback,
      is BridgeType.ReturnedLambda,
      is BridgeType.SpecializedProtocol,
      is BridgeType.RawKSType,
      is BridgeType.Unsupported,
      is BridgeType.RawCollection,
        -> error("Forward planner cannot build an input parameter for nullable $inner")
    }

    BridgeType.Unit,
    BridgeType.Instant,
    is BridgeType.ReturnedLambda,
    is BridgeType.SpecializedProtocol,
    is BridgeType.RawKSType,
    is BridgeType.Unsupported,
    is BridgeType.RawCollection,
      -> error("Forward planner cannot build an input parameter for $type")
  }

  private fun transfer(subject: String, type: BridgeType, flow: ForwardFlow): ForwardTransfer = ForwardTransfer(
    subject = subject,
    type = type,
    flow = flow,
    passing = ForwardPassing.VALUE,
    ownership = ForwardOwnership.BORROWED,
    conversion = if (type == BridgeType.String && flow == ForwardFlow.INTO_KOTLIN) {
      ForwardConversion.STRING_TO_UTF8
    } else {
      ForwardConversion.DIRECT
    },
  )

  private fun BridgeType.shapeOrNull(): ForwardResultShape? = when (this) {
    BridgeType.Unit, is BridgeType.Primitive, BridgeType.Char -> ForwardResultShape(
      wireType = wireType(),
      transfer = transfer("result", this, ForwardFlow.OUT_OF_KOTLIN),
    )

    // String results cross as a native pointer (Kotlin String / C# IntPtr + PtrToStringUTF8),
    // matching property getters and the shipped value-class method ABI.
    BridgeType.String -> ForwardResultShape(
      wireType = ForwardAbiWireType.POINTER,
      transfer = ForwardTransfer(
        subject = "result",
        type = this,
        flow = ForwardFlow.OUT_OF_KOTLIN,
        passing = ForwardPassing.VALUE,
        ownership = ForwardOwnership.MATERIALIZED,
        conversion = ForwardConversion.UTF8_TO_STRING,
      ),
      helperRequirements = setOf(ForwardHelperRequirement.UTF8),
    )

    // ADR-106: the same pointer-to-text result shape as String, with the Uuid conversion tag.
    BridgeType.Uuid -> ForwardResultShape(
      wireType = ForwardAbiWireType.POINTER,
      transfer = ForwardTransfer(
        subject = "result",
        type = this,
        flow = ForwardFlow.OUT_OF_KOTLIN,
        passing = ForwardPassing.VALUE,
        ownership = ForwardOwnership.MATERIALIZED,
        conversion = ForwardConversion.UUID_TO_STRING,
      ),
      helperRequirements = setOf(ForwardHelperRequirement.UUID),
    )

    is BridgeType.Enum -> ForwardResultShape(
      wireType = ForwardAbiWireType.INT32,
      transfer = ForwardTransfer(
        subject = "result",
        type = this,
        flow = ForwardFlow.OUT_OF_KOTLIN,
        passing = ForwardPassing.VALUE,
        ownership = ForwardOwnership.BORROWED,
        conversion = ForwardConversion.ENUM_TO_ORDINAL,
      ),
      helperRequirements = setOf(ForwardHelperRequirement.ENUM_ORDINAL),
    )

    // ADR-076: same shape as Enum above -- a semantic result with a required conversion, wired
    // as its own primitive-like representation (INT64 ticks).
    BridgeType.Instant -> ForwardResultShape(
      wireType = ForwardAbiWireType.INT64,
      transfer = ForwardTransfer(
        subject = "result",
        type = this,
        flow = ForwardFlow.OUT_OF_KOTLIN,
        passing = ForwardPassing.VALUE,
        ownership = ForwardOwnership.BORROWED,
        conversion = ForwardConversion.INSTANT_TO_TICKS,
      ),
      helperRequirements = setOf(ForwardHelperRequirement.INSTANT),
    )

    // ADR-103: the same shape, INT64 of TimeSpan ticks.
    BridgeType.Duration -> ForwardResultShape(
      wireType = ForwardAbiWireType.INT64,
      transfer = ForwardTransfer(
        subject = "result",
        type = this,
        flow = ForwardFlow.OUT_OF_KOTLIN,
        passing = ForwardPassing.VALUE,
        ownership = ForwardOwnership.BORROWED,
        conversion = ForwardConversion.DURATION_TO_TICKS,
      ),
      helperRequirements = setOf(ForwardHelperRequirement.DURATION),
    )

    // ADR-147: a `T` result is minted by `NugetHandles.retain` like any other handle.
    is BridgeType.ObjectHandle, is BridgeType.Interface, is BridgeType.TypeParameter ->
      handleResultShape(this)
    // ADR-160 amendment: the lambda is minted by `NugetHandles.retain` like any other handle, and
    // has a shape only when C# can spell every one of its type arguments (issue #111).
    is BridgeType.ReturnedLambda ->
      if (unnameableTypeArgument() == null) handleResultShape(this) else null
    // ADR-088: gated on the manifest's Kotlin-implementability flag. Without a
    // `mint{Iface}Bridge`, a plain Kotlin implementation returned here has nothing to become on
    // the C# side, and v1 refuses to emit a route that works for one origin and traps for the
    // other (the skip is named UNIMPLEMENTABLE_BOUND_INTERFACE).
    is BridgeType.BoundInterface -> if (implementable) boundInterfaceResultShape(this) else null
    // ADR-014 gap this feature's fixture flushed out: a value class returned by an *ordinary*
    // (non-value-class-own) callable never had a planner-side result shape, despite the model/
    // validator already carrying BOX_VALUE_CLASS/UNBOX_VALUE_CLASS conversions for exactly this
    // position. Scoped to a String underlying only (what the fixture needs); every other
    // underlying keeps its existing VALUE_CLASS skip rather than risk a wire shape this change
    // was not verified against.
    is BridgeType.ValueClass -> valueClassResultShape(this)
    // ADR-066: an unsupported (or reachable-but-out-of-scope) element/key/value must not reach
    // ForwardCallablePlanValidator as a built Collection shape — that error()s the whole plan
    // rather than skipping just this one callable (the archive(): List<TopStory> crash this
    // feature's fixture flushed out, predating ADR-066 but only reachable once it exists).
    // ADR-151: one materialized handle, read back by `NugetMarshal.ReadBytes`, which disposes it.
    BridgeType.ByteArray -> handleResultShape(this, ForwardHelperRequirement.BYTES)
    // ADR-201: the ADR-107 envelope, minted by the same `NugetHandles.retain`; C# reads it back
    // with `NugetErrorNative.BuildException`, which disposes it.
    is BridgeType.Throwable -> handleResultShape(this, ForwardHelperRequirement.STABLE_REF)

    is BridgeType.Collection -> if (isBridgeableComponent()) {
      handleResultShape(this, ForwardHelperRequirement.COLLECTION)
    } else {
      null
    }

    is BridgeType.Nullable -> nullableResultShape(type)
    is BridgeType.Callback,
    is BridgeType.SpecializedProtocol,
    is BridgeType.RawKSType,
    is BridgeType.Unsupported,
    is BridgeType.RawCollection,
      -> null
  }

  private fun nullableResultShape(type: BridgeType): ForwardResultShape? = when (type) {
    // ADR-106: `Uuid?` returns over the same single pointer slot as `String?`; a null pointer is
    // the null, so no has-value channel.
    BridgeType.Uuid -> ForwardResultShape(
      wireType = ForwardAbiWireType.POINTER,
      transfer = ForwardTransfer(
        subject = "result",
        type = BridgeType.Nullable(type),
        flow = ForwardFlow.OUT_OF_KOTLIN,
        passing = ForwardPassing.VALUE,
        ownership = ForwardOwnership.MATERIALIZED,
        conversion = ForwardConversion.UUID_TO_STRING,
      ),
      helperRequirements = setOf(ForwardHelperRequirement.UUID),
    )

    BridgeType.String -> ForwardResultShape(
      wireType = ForwardAbiWireType.POINTER,
      transfer = ForwardTransfer(
        subject = "result",
        type = BridgeType.Nullable(type),
        flow = ForwardFlow.OUT_OF_KOTLIN,
        passing = ForwardPassing.VALUE,
        ownership = ForwardOwnership.MATERIALIZED,
        conversion = ForwardConversion.UTF8_TO_STRING,
      ),
      helperRequirements = setOf(ForwardHelperRequirement.UTF8),
    )

    // ADR-147: `T?` out is the null pointer, then the handle.
    is BridgeType.ObjectHandle, is BridgeType.Interface, is BridgeType.TypeParameter ->
      handleResultShape(BridgeType.Nullable(type))
    // ADR-061 (2026-09-16 amendment): the nullable ObjectHandle shape above, verbatim. The
    // collection handle is a StableRef on the same POINTER slot, so a null pointer already means
    // Kotlin null and no has-value channel is needed. The component gate is the non-nullable
    // Collection arm's, so an ineligible element/key/value still skips with its own named reason
    // rather than reaching the validator as a built shape (ADR-066).
    // ADR-151: `ByteArray?` out is the null pointer, then the handle.
    BridgeType.ByteArray ->
      handleResultShape(BridgeType.Nullable(type), ForwardHelperRequirement.BYTES)
    // ADR-201: `Throwable?` out is the null pointer, then the envelope.
    is BridgeType.Throwable ->
      handleResultShape(BridgeType.Nullable(type), ForwardHelperRequirement.STABLE_REF)

    is BridgeType.Collection -> if (type.isBridgeableComponent()) {
      handleResultShape(BridgeType.Nullable(type), ForwardHelperRequirement.COLLECTION)
    } else {
      null
    }

    // ADR-077 sub-items 3/4: reuses the corresponding nullable pointer shape verbatim (null rides
    // the null pointer; a value class's underlying is non-nullable by construction, so there is
    // no third state), with only the transfer's type and conversion tag changed. Pointer-shaped
    // underlyings only (String, ObjectHandle); nullable x primitive/enum stays deferred.
    is BridgeType.ValueClass -> when (type.underlying) {
      BridgeType.String -> ForwardResultShape(
        wireType = ForwardAbiWireType.POINTER,
        transfer = ForwardTransfer(
          subject = "result",
          type = BridgeType.Nullable(type),
          flow = ForwardFlow.OUT_OF_KOTLIN,
          passing = ForwardPassing.VALUE,
          ownership = ForwardOwnership.MATERIALIZED,
          conversion = ForwardConversion.UNBOX_VALUE_CLASS,
        ),
        helperRequirements = setOf(
          ForwardHelperRequirement.UTF8,
          ForwardHelperRequirement.VALUE_CLASS,
        ),
      )

      is BridgeType.ObjectHandle -> handleResultShape(BridgeType.Nullable(type)).let { shape ->
        shape.copy(
          transfer = shape.transfer.copy(conversion = ForwardConversion.UNBOX_VALUE_CLASS),
          helperRequirements = shape.helperRequirements + ForwardHelperRequirement.VALUE_CLASS,
        )
      }

      // ADR-079: the ADR-061 single-call shape (BOOLEAN has-value result + `valueOut` OUT
      // pointer), same as the nullable-primitive and nullable-Instant cases below. The outer
      // transfer carries the Nullable value class + UNBOX_VALUE_CLASS; `valueOut` carries the
      // *bare* underlying primitive (INT for an enum ordinal) so it renders as `double`/`int` on
      // the C# side and inherits ADR-069's [MarshalAs(UnmanagedType.I1)] for a Boolean underlying.
      is BridgeType.Primitive, is BridgeType.Enum -> ForwardResultShape(
        wireType = ForwardAbiWireType.BOOLEAN,
        transfer = ForwardTransfer(
          subject = "result",
          type = BridgeType.Nullable(type),
          flow = ForwardFlow.OUT_OF_KOTLIN,
          passing = ForwardPassing.VALUE,
          ownership = ForwardOwnership.BORROWED,
          conversion = ForwardConversion.UNBOX_VALUE_CLASS,
        ),
        extraParameters = listOf(
          ForwardAbiParameter(
            name = "valueOut",
            wireType = ForwardAbiWireType.POINTER,
            direction = ForwardAbiDirection.OUT,
            transfer = ForwardTransfer(
              subject = "valueOut",
              type = type.underlying.valueOutTransferType(),
              flow = ForwardFlow.OUT_OF_KOTLIN,
              passing = ForwardPassing.OUT,
              ownership = ForwardOwnership.BORROWED,
              conversion = ForwardConversion.DIRECT,
            ),
            role = ForwardAbiRole.VALUE_OUT,
          )
        ),
        helperRequirements = buildSet {
          add(ForwardHelperRequirement.VALUE_CLASS)
          if (type.underlying is BridgeType.Enum) add(ForwardHelperRequirement.ENUM_ORDINAL)
        },
      )

      BridgeType.Unit,
      is BridgeType.Primitive,
      BridgeType.Char,
      BridgeType.Instant,
      BridgeType.Duration,
      is BridgeType.Throwable,
      BridgeType.Uuid,
      is BridgeType.Enum,
      is BridgeType.Interface,
      is BridgeType.BoundInterface,
      is BridgeType.ValueClass,
      BridgeType.ByteArray,
      is BridgeType.Collection,
      is BridgeType.Nullable,
      is BridgeType.Callback,
      is BridgeType.ReturnedLambda,
      is BridgeType.SpecializedProtocol,
      is BridgeType.RawKSType,
      is BridgeType.Unsupported,
      is BridgeType.RawCollection,
      is BridgeType.TypeParameter,
        -> null
    }

    is BridgeType.Primitive -> {
      val nullable: BridgeType = BridgeType.Nullable(type)
      ForwardResultShape(
        wireType = ForwardAbiWireType.BOOLEAN,
        transfer = transfer("result", nullable, ForwardFlow.OUT_OF_KOTLIN),
        extraParameters = listOf(
          ForwardAbiParameter(
            name = "valueOut",
            wireType = ForwardAbiWireType.POINTER,
            direction = ForwardAbiDirection.OUT,
            transfer = ForwardTransfer(
              subject = "valueOut",
              type = type,
              flow = ForwardFlow.OUT_OF_KOTLIN,
              passing = ForwardPassing.OUT,
              ownership = ForwardOwnership.BORROWED,
              conversion = ForwardConversion.DIRECT,
            ),
            role = ForwardAbiRole.VALUE_OUT,
          )
        ),
      )
    }

    // ADR-076 §4.2: same BOOLEAN-result + valueOut OUT-pointer shape as the nullable-primitive
    // case above -- the whole nullable story reduces to the already-shipped nullable-primitive
    // INT64 machinery. The outer "result" transfer carries the semantic INSTANT_TO_TICKS
    // conversion; valueOut itself carries the already-converted raw ticks (a plain Long), so its
    // own transfer stays DIRECT and the DllImport/local-variable declarations it drives (which
    // read `transfer.type.csharpType()`) render "long", not "DateTimeOffset".
    BridgeType.Instant -> ForwardResultShape(
      wireType = ForwardAbiWireType.BOOLEAN,
      transfer = ForwardTransfer(
        subject = "result",
        type = BridgeType.Nullable(type),
        flow = ForwardFlow.OUT_OF_KOTLIN,
        passing = ForwardPassing.VALUE,
        ownership = ForwardOwnership.BORROWED,
        conversion = ForwardConversion.INSTANT_TO_TICKS,
      ),
      extraParameters = listOf(
        ForwardAbiParameter(
          name = "valueOut",
          wireType = ForwardAbiWireType.POINTER,
          direction = ForwardAbiDirection.OUT,
          transfer = ForwardTransfer(
            subject = "valueOut",
            type = BridgeType.Primitive(PrimitiveKind.LONG),
            flow = ForwardFlow.OUT_OF_KOTLIN,
            passing = ForwardPassing.OUT,
            ownership = ForwardOwnership.BORROWED,
            conversion = ForwardConversion.DIRECT,
          ),
          role = ForwardAbiRole.VALUE_OUT,
        )
      ),
      helperRequirements = setOf(ForwardHelperRequirement.INSTANT),
    )

    // ADR-103: identical to the Instant case above, DURATION_TO_TICKS on the outer transfer and a
    // plain Long `valueOut` carrying the already-converted TimeSpan ticks.
    BridgeType.Duration -> ForwardResultShape(
      wireType = ForwardAbiWireType.BOOLEAN,
      transfer = ForwardTransfer(
        subject = "result",
        type = BridgeType.Nullable(type),
        flow = ForwardFlow.OUT_OF_KOTLIN,
        passing = ForwardPassing.VALUE,
        ownership = ForwardOwnership.BORROWED,
        conversion = ForwardConversion.DURATION_TO_TICKS,
      ),
      extraParameters = listOf(
        ForwardAbiParameter(
          name = "valueOut",
          wireType = ForwardAbiWireType.POINTER,
          direction = ForwardAbiDirection.OUT,
          transfer = ForwardTransfer(
            subject = "valueOut",
            type = BridgeType.Primitive(PrimitiveKind.LONG),
            flow = ForwardFlow.OUT_OF_KOTLIN,
            passing = ForwardPassing.OUT,
            ownership = ForwardOwnership.BORROWED,
            conversion = ForwardConversion.DIRECT,
          ),
          role = ForwardAbiRole.VALUE_OUT,
        )
      ),
      helperRequirements = setOf(ForwardHelperRequirement.DURATION),
    )

    // ADR-080: a bare nullable enum is ADR-079's value-class-over-enum shape with the box step
    // deleted -- BOOLEAN has-value result plus a `valueOut` carrying the plain `int` ordinal.
    is BridgeType.Enum -> ForwardResultShape(
      wireType = ForwardAbiWireType.BOOLEAN,
      transfer = ForwardTransfer(
        subject = "result",
        type = BridgeType.Nullable(type),
        flow = ForwardFlow.OUT_OF_KOTLIN,
        passing = ForwardPassing.VALUE,
        ownership = ForwardOwnership.BORROWED,
        conversion = ForwardConversion.ENUM_TO_ORDINAL,
      ),
      extraParameters = listOf(
        ForwardAbiParameter(
          name = "valueOut",
          wireType = ForwardAbiWireType.POINTER,
          direction = ForwardAbiDirection.OUT,
          transfer = ForwardTransfer(
            subject = "valueOut",
            type = type.valueOutTransferType(),
            flow = ForwardFlow.OUT_OF_KOTLIN,
            passing = ForwardPassing.OUT,
            ownership = ForwardOwnership.BORROWED,
            conversion = ForwardConversion.DIRECT,
          ),
          role = ForwardAbiRole.VALUE_OUT,
        )
      ),
      helperRequirements = setOf(ForwardHelperRequirement.ENUM_ORDINAL),
    )

    // ADR-098 amendment (boundary nullability part C): `Char?` is the Enum arm above with the
    // ordinal step replaced by `.code`. `valueOut` carries Primitive(USHORT), so it renders as a
    // blittable `out ushort` and Kotlin writes through a `UShortVar` (kotlinx.cinterop has no
    // `CharVar`). Deliberately NOT an `out char`: a BARE `out char` marshals one ANSI byte and
    // silently corrupts non-ASCII characters on both runtimes ('e-acute' to U+FFFD on JIT, the
    // high byte of anything above U+00FF lost on NativeAOT). `[MarshalAs(UnmanagedType.U2)] out
    // char` measures correct on JIT and NativeAOT alike, but would need a second arm in
    // `outParameterMarshalPrefix`. `ushort` is blittable by construction and reuses the Enum
    // `valueOutTransferType()` path unchanged.
    BridgeType.Char -> ForwardResultShape(
      wireType = ForwardAbiWireType.BOOLEAN,
      transfer = ForwardTransfer(
        subject = "result",
        type = BridgeType.Nullable(type),
        flow = ForwardFlow.OUT_OF_KOTLIN,
        passing = ForwardPassing.VALUE,
        ownership = ForwardOwnership.BORROWED,
        conversion = ForwardConversion.DIRECT,
      ),
      extraParameters = listOf(
        ForwardAbiParameter(
          name = "valueOut",
          wireType = ForwardAbiWireType.POINTER,
          direction = ForwardAbiDirection.OUT,
          transfer = ForwardTransfer(
            subject = "valueOut",
            type = BridgeType.Primitive(PrimitiveKind.USHORT),
            flow = ForwardFlow.OUT_OF_KOTLIN,
            passing = ForwardPassing.OUT,
            ownership = ForwardOwnership.BORROWED,
            conversion = ForwardConversion.DIRECT,
          ),
          role = ForwardAbiRole.VALUE_OUT,
        )
      ),
    )

    BridgeType.Unit,
    is BridgeType.BoundInterface,
    is BridgeType.Nullable,
    is BridgeType.Callback,
    is BridgeType.ReturnedLambda,
    is BridgeType.SpecializedProtocol,
    is BridgeType.RawKSType,
    is BridgeType.Unsupported,
    is BridgeType.RawCollection,
      -> null
  }

  /**
   * ADR-088: a bound C# interface returned OUT of Kotlin. Wire-shaped like [handleResultShape] (a
   * POINTER), but deliberately NOT it: the pointer is a GCHandle, not a StableRef, so the
   * ownership and cleanup are the other way round. C# owns the fresh transfer handle and frees it
   * the moment it resolves `.Target`, which is why this is MATERIALIZED with no Kotlin-side
   * cleanup rather than OWNED_HANDLE + DISPOSE_STABLE_REF.
   */
  private fun boundInterfaceResultShape(type: BridgeType.BoundInterface): ForwardResultShape =
    ForwardResultShape(
      wireType = ForwardAbiWireType.POINTER,
      transfer = ForwardTransfer(
        subject = "result",
        type = type,
        flow = ForwardFlow.OUT_OF_KOTLIN,
        passing = ForwardPassing.VALUE,
        ownership = ForwardOwnership.MATERIALIZED,
        conversion = ForwardConversion.BOUND_VALUE_TO_GC_HANDLE,
      ),
      helperRequirements = setOf(ForwardHelperRequirement.BOUND_INTERFACE),
    )

  private fun handleResultShape(
    type: BridgeType,
    helper: ForwardHelperRequirement? = null,
  ): ForwardResultShape = ForwardResultShape(
    wireType = ForwardAbiWireType.POINTER,
    transfer = ForwardTransfer(
      subject = "result",
      type = type,
      flow = ForwardFlow.OUT_OF_KOTLIN,
      passing = ForwardPassing.VALUE,
      ownership = ForwardOwnership.OWNED_HANDLE,
      conversion = when (type.unwrapNullable()) {
        is BridgeType.Collection -> ForwardConversion.COLLECTION_TO_HANDLE
        BridgeType.ByteArray -> ForwardConversion.BYTES_TO_HANDLE
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
        is BridgeType.Nullable,
        is BridgeType.Callback,
        is BridgeType.ReturnedLambda,
        is BridgeType.SpecializedProtocol,
        is BridgeType.RawKSType,
        is BridgeType.Unsupported,
        is BridgeType.RawCollection,
        is BridgeType.TypeParameter,
          -> ForwardConversion.STABLE_REF_TO_HANDLE
      },
    ),
    cleanup = listOf(ForwardCleanup("result", ForwardCleanupKind.DISPOSE_STABLE_REF)),
    helperRequirements = setOfNotNull(helper),
  )

  /**
   * ADR-014 (ordinary position): the value class's underlying wire value crosses the boundary
   * unchanged; the Kotlin export unboxes it (`result.${underlyingPropertyName}`, wired by
   * [ForwardCallableOrigin] alone — the Kotlin emitter reads `type.underlyingPropertyName`
   * directly) and the C# wrapper reconstructs `new StructType(rawValue)`. Reuses the underlying's
   * own shape verbatim except for the outer transfer, which must record `type` (not the
   * underlying) with [ForwardConversion.UNBOX_VALUE_CLASS] — the tag [ForwardCallablePlanValidator
   * .requiredConversion] demands for a `BridgeType.ValueClass` result.
   */
  private fun valueClassResultShape(type: BridgeType.ValueClass): ForwardResultShape? {
    // ADR-077 sub-item 4: the underlying's own shape verbatim (wire, ownership, cleanup: an
    // ObjectHandle underlying keeps OWNED_HANDLE + DISPOSE_STABLE_REF from handleResultShape),
    // with only the transfer re-typed to the value class and re-tagged UNBOX_VALUE_CLASS. The
    // underlying's own step (ordinal, StableRef, UTF-8) composes inside the emitted expressions.
    if (!type.underlying.isOrdinaryValueClassUnderlying()) return null
    val underlyingShape: ForwardResultShape = type.underlying.shapeOrNull() ?: return null
    return underlyingShape.copy(
      transfer = underlyingShape.transfer.copy(
        type = type,
        conversion = ForwardConversion.UNBOX_VALUE_CLASS,
      ),
      helperRequirements = underlyingShape.helperRequirements +
          ForwardHelperRequirement.VALUE_CLASS,
    )
  }

  /**
   * ADR-077 sub-item 4: the underlyings a value class may carry across an ordinary position.
   * Everything else (nested value classes, collections, nullables) keeps the VALUE_CLASS skip.
   */
  private fun BridgeType.isOrdinaryValueClassUnderlying(): Boolean =
    this == BridgeType.String || this is BridgeType.Primitive ||
        this is BridgeType.Enum || this is BridgeType.ObjectHandle

  /**
   * ADR-079: the value-class underlyings whose wire has no in-band null, so a
   * `Nullable(ValueClass)` over them needs the out-of-band has-value channel (an adjacent BOOLEAN
   * parameter at an input position, a BOOLEAN result + `valueOut` at a return one). String and
   * ObjectHandle underlyings ride their own null pointer instead (ADR-077 sub-items 3/4).
   */
  private fun BridgeType.isHasValueFanOutUnderlying(): Boolean =
    this is BridgeType.Primitive || this is BridgeType.Enum

  /**
   * ADR-132: whether [nativeInputParameters] fans this input out into the adjacent
   * `${name}HasValue` + `$name` PAIR (ADR-076/079/080/103) rather than a single slot. Read at the
   * extension-receiver position, where a two-slot input cannot be expressed today.
   */
  private fun BridgeType.isHasValueFanOutInput(): Boolean {
    val inner: BridgeType = (this as? BridgeType.Nullable)?.type ?: return false
    return inner.isHasValueFanOutUnderlying() ||
        // ADR-098 amendment (boundary nullability part C): `Char?` fans out too.
        inner == BridgeType.Char ||
        inner == BridgeType.Instant || inner == BridgeType.Duration ||
        (inner as? BridgeType.ValueClass)?.underlying?.isHasValueFanOutUnderlying() == true
  }

  /**
   * ADR-079: the type an ADR-061 `valueOut` slot carries for a has-value fan-out value class. It is
   * the *bare* underlying primitive (an enum's ordinal is a plain INT), never the value class
   * itself, so the slot renders as `double`/`int` in the DllImport and inherits ADR-069's
   * `[MarshalAs(UnmanagedType.I1)]` for a Boolean underlying. Mirrors Instant's `Primitive(LONG)`
   * valueOut.
   */
  private fun BridgeType.valueOutTransferType(): BridgeType =
    if (this is BridgeType.Enum) BridgeType.Primitive(PrimitiveKind.INT) else this

  private data class ForwardResultShape(
    val wireType: ForwardAbiWireType,
    val transfer: ForwardTransfer,
    val extraParameters: List<ForwardAbiParameter> = emptyList(),
    val cleanup: List<ForwardCleanup> = emptyList(),
    val helperRequirements: Set<ForwardHelperRequirement> = emptySet(),
  )

  private sealed interface ForwardReceiver {
    val type: BridgeType?

    data class Handle(
      override val type: BridgeType,
      val name: String = "handle",
    ) : ForwardReceiver

    data class Value(
      override val type: BridgeType,
      val name: String = "receiver",
    ) : ForwardReceiver

    data object Static : ForwardReceiver {
      override val type: BridgeType? = null
    }
  }

  /**
   * ADR-141: the outer instance an `inner class` constructor is called on, or
   * [ForwardReceiver.Static] for every other class. The outer is the enclosing declaration, which
   * is itself admitted (an inner class under a deferred owner never reaches a plan), and it
   * crosses BORROWED: the inner instance's own Kotlin-side reference is what keeps the outer
   * alive, not the handle.
   */
  private fun innerConstructorReceiver(cls: KSClassDeclaration?): ForwardReceiver {
    if (cls == null || !cls.modifiers.contains(Modifier.INNER)) return ForwardReceiver.Static
    val outerDeclaration: KSClassDeclaration =
      cls.parentDeclaration as? KSClassDeclaration ?: return ForwardReceiver.Static
    val outer: String = outerDeclaration.qualifiedName?.asString() ?: return ForwardReceiver.Static
    // ADR-196: a generic outer (the inner class is flattened onto its holder as `Tin.Latch<T>`) is
    // read back applied (`asStableRef<pkg.Tin<Any?>>`) and typed `Tin<T>` in C#, spelled through
    // `global::` because the bare `Tin` inside the holder is the holder itself. An outer that is
    // itself a captured inner class never gets here: it cannot own one (`NugetProcessor`).
    val applied: String? = outerDeclaration.forwardOwnerTypeName()
    if (applied == null || outerDeclaration.typeParameters.isEmpty()) {
      return ForwardReceiver.Handle(BridgeType.ObjectHandle(outer), name = "outer")
    }
    val arguments: String = outerDeclaration.typeParameters.joinToString(", ") { parameter ->
      outerDeclaration.forwardCsharpTypeParameterName(parameter)
    }
    val namespace: String? = classifier.csharpNamespaceOf(outerDeclaration)
    val name: String = "${outerDeclaration.nestedCsName()}<$arguments>"
    val csharpType: String = if (namespace == null) name else "global::$namespace.$name"
    return ForwardReceiver.Handle(
      BridgeType.ObjectHandle(outer, csharpType = csharpType, kotlinReadType = applied),
      name = "outer",
    )
  }

  private fun receiverParameter(
    receiver: ForwardReceiver,
    // ADR-132 amendment: the planner's public receiver, carrying the minted has-value flag name.
    publicReceiver: ForwardPublicParameter?,
  ): List<ForwardAbiParameter> = when (receiver) {
    is ForwardReceiver.Handle -> listOf(
      ForwardAbiParameter(
        name = receiver.name,
        wireType = ForwardAbiWireType.POINTER,
        direction = ForwardAbiDirection.IN,
        transfer = ForwardTransfer(
          subject = receiver.name,
          type = receiver.type,
          flow = ForwardFlow.INTO_KOTLIN,
          passing = ForwardPassing.VALUE,
          ownership = ForwardOwnership.BORROWED,
          conversion = ForwardConversion.HANDLE_TO_STABLE_REF,
        ),
        role = ForwardAbiRole.RECEIVER,
      )
    )

    is ForwardReceiver.Value -> nativeInputParameters(
      receiver.name, receiver.type, ForwardAbiRole.RECEIVER,
      hasValueSlot = publicReceiver?.hasValueSlot ?: "${receiver.name}HasValue",
    )
    ForwardReceiver.Static -> emptyList()
  }

  private fun BridgeType.inputSkipReason(): ForwardPlanSkipReason? = when (this) {
    // ADR-160: the one position a callback binds at. The classifier already refused every payload
    // or lambda-result shape the two halves cannot lower, so a `Callback` reaching here is
    // emittable by construction.
    is BridgeType.Callback -> null

    // ADR-106: Uuid is admissible at every input position, over the String wire.
    BridgeType.String, BridgeType.Char, BridgeType.Instant, BridgeType.Duration,
    BridgeType.Uuid -> null

    is BridgeType.Enum -> null
    // ADR-040 sub-decision B: an interface-typed parameter is plannable — the C# lowering routes
    // through NugetMarshal.HandleOf (ForwardCirPlanProjection.callArgument), which throws
    // NotSupportedException at runtime for a C#-implemented (non-Kotlin-backed) IFoo.
    // ADR-147: `NugetMarshal.Wrap<T>` boxes whatever `T` was instantiated to.
    is BridgeType.ObjectHandle, is BridgeType.Interface, is BridgeType.TypeParameter -> null
    // ADR-088: admissible at a parameter position regardless of `implementable` — the incoming
    // GCHandle only needs `nuget{Iface}Value`, which every manifest-listed interface has. Only a
    // RETURN of a plain Kotlin implementation needs the mint.
    is BridgeType.BoundInterface -> null
    // ADR-066: an unsupported element must not silently produce a Collection shape that later
    // crashes plan validation — route it through the same skip path as any other unsupported
    // input, preferring the (element ?: key ?: value)'s own reason (e.g.
    // UNEXPORTED_DEPENDENCY_TYPE) when known.
    // ADR-151: admitted at every parameter position; the handle is minted and disposed by C#.
    BridgeType.ByteArray -> null

    // ADR-201: one `"{FullName}: {Message}"` string Kotlin turns into a `NugetManagedException`,
    // which only a declared `Throwable`/`Exception`/`RuntimeException` can hold.
    is BridgeType.Throwable ->
      if (acceptsManagedException) null else ForwardPlanSkipReason.THROWABLE

    is BridgeType.Collection -> collectionInputSkipReason()

    // ADR-077: a value class crosses as its underlying wire value, so an ordinary parameter is
    // plannable exactly when that underlying is (String/primitive/enum/ObjectHandle, sub-item 4).
    is BridgeType.ValueClass ->
      if (underlying.isOrdinaryValueClassUnderlying()) null else ForwardPlanSkipReason.VALUE_CLASS

    // ADR-075: a nullable collection input (e.g. a data class's `notes: List<String>?` primary
    // constructor parameter, mirroring `Visit.notes` as a *property*) shares exactly the same
    // per-kind eligibility as a non-null collection input — the collection reference's own
    // nullability is orthogonal to its components' marshallability, same as the property setter
    // side of this same ADR.
    is BridgeType.Nullable -> when (val inner = type) {
      BridgeType.String, is BridgeType.ObjectHandle, is BridgeType.Primitive,
      // ADR-147: `Wrap<T>` maps a null `T` to `IntPtr.Zero` already.
      is BridgeType.TypeParameter,
      // ADR-133: an interface parameter is already plannable non-null (ADR-040 sub-decision B,
      // `NugetMarshal.HandleOf`), and the nullable C# lowering is the same helper's
      // `HandleOfOrZero` (ForwardCirPlanProjection). Without this a nullable interface
      // PARAMETER skipped while a nullable interface RETURN and PROPERTY both bound.
      is BridgeType.Interface,
      // ADR-106: `Uuid?` rides the null pointer, like `String?`.
      // ADR-098 amendment (boundary nullability part C): `Char?` fans out to the has-value pair
      // with a by-value `char` in the value slot, the same way a nullable primitive does.
      BridgeType.Char,
      BridgeType.Instant, BridgeType.Duration, BridgeType.Uuid -> null

      // ADR-080: a bare nullable enum fans out to the has-value pair with the ordinal in the
      // value slot, exactly like ADR-079's enum-underlying value class minus the box.
      is BridgeType.Enum -> null

      // ADR-151: `byte[]?` rides `IntPtr.Zero`, exactly as a nullable collection does.
      BridgeType.ByteArray -> null

      // ADR-201: a C# null rides the null string pointer.
      is BridgeType.Throwable ->
        if (inner.acceptsManagedException) null else ForwardPlanSkipReason.THROWABLE

      is BridgeType.Collection -> inner.collectionInputSkipReason()
      // ADR-077 sub-items 3/4: null rides the null pointer for the pointer-wired underlyings
      // (String, ObjectHandle). ADR-079: a Primitive/Enum underlying has no in-band null, so it
      // fans out to the has-value pair instead; either way the nullable spelling is plannable
      // exactly when the underlying is (`isOrdinaryValueClassUnderlying`, the non-null rule).
      is BridgeType.ValueClass ->
        if (inner.underlying.isOrdinaryValueClassUnderlying()) null
        else ForwardPlanSkipReason.VALUE_CLASS

      // ROADMAP Phase 3: `Shape?` is not skipped *because* it is nullable -- a bare `Shape` is
      // just as unmarshallable at an input position -- so the NULLABLE bucket's "expose a
      // non-nullable wrapper" hint would send the author after a fix that cannot work, exactly
      // the trap issue #54 fixed for undeclared types. Narrow on purpose: every other nullable
      // protocol (Flow, lambda, generic) keeps the shipped NULLABLE wording, since those are
      // separate deferrals with their own routes.
      // Boundary nullability part A2: a nullable lambda TYPE (`cb: ((Int) -> Unit)?`) is routed by
      // the legacy per-call/stored selector exactly as the non-null spelling is (that selector keys
      // on the expanded declaration's qualified name only), so it BINDS -- while this arm reported
      // it as NULLABLE, a `droppedFromCSharp = true` reason. The member therefore existed in
      // `Interop.cs` AND carried a `SKIPPED_UNSUPPORTED_INPUT` warning, a `NugetDiagnostics.json`
      // row and a "Not generated from Kotlin ..." remark on the very class that declared it. The
      // diagnostic was the wrong half: the nullable spelling takes the same silent legacy-deferral
      // reason the non-null one does, so the tool stops contradicting itself.
      //
      // Narrow to a plain `lambda ` protocol on purpose. Every other nullable protocol (Flow,
      // StateFlow, suspend lambda, generic) genuinely has no route at an input position, so its
      // NULLABLE/UNROUTED wording -- which names the offending parameter, issue #131 -- is the
      // right answer and must not be swapped for a silent deferral.
      is BridgeType.SpecializedProtocol -> when {
        inner.isSealedProtocol() -> ForwardPlanSkipReason.SEALED_POSITION
        inner.name.startsWith("lambda ") -> ForwardPlanSkipReason.CALLBACK_PROTOCOL
        else -> ForwardPlanSkipReason.NULLABLE
      }

      // ADR-160 interaction: a nullable lambda whose payload and result the plan's own callback
      // lowering DOES carry classifies as `Nullable(Callback)`, not as a `lambda ...` protocol, so
      // it needs the identical silent legacy deferral the arm above gives the declined shapes. The
      // plan itself cannot take it -- a `Callback` ABI slot is a function pointer plus user data,
      // with no has-value companion -- and `hasPlannedCallbackParameter` therefore leaves the
      // member on the hand-written route, which binds it and guards the delegate with
      // `ArgumentNullException.ThrowIfNull`. Without this arm the member binds in `Interop.cs` AND
      // reports itself skipped, which is the contradiction part A2 removed.
      is BridgeType.Callback -> ForwardPlanSkipReason.CALLBACK_PROTOCOL

      // ADR-088: `IFeedable?` is on this ADR's deferred list. The null-pointer ride is natural,
      // but it needs its own lowering in four emitter positions; until then the skip names the
      // position rather than falling through to the generic NULLABLE bucket below, whose
      // diagnostic kind is SKIPPED_UNSUPPORTED_RETURN and whose hint talks about Booleans.
      is BridgeType.BoundInterface -> ForwardPlanSkipReason.BOUND_INTERFACE_POSITION

      // Issue #54: the return side's rule, at an input position.
      is BridgeType.Unsupported ->
        if (inner.isUndeclared()) requireNotNull(inner.skipReason())
        else ForwardPlanSkipReason.NULLABLE

      BridgeType.Unit,
      is BridgeType.Nullable,
      is BridgeType.ReturnedLambda,
      is BridgeType.RawKSType,
      is BridgeType.RawCollection,
        -> ForwardPlanSkipReason.NULLABLE
    }

    BridgeType.Unit,
    is BridgeType.Primitive,
    is BridgeType.ReturnedLambda,
    is BridgeType.SpecializedProtocol,
    is BridgeType.RawKSType,
    is BridgeType.Unsupported,
    is BridgeType.RawCollection,
      -> skipReason()
  }

  /**
   * The offending *component* of a `COLLECTION` skip, in the same wording
   * [ForwardPropertyPlanner]'s dropped-setter diagnostic uses ("element type Collection?",
   * "key type String?"). The outer collection kind is never the failure: every kind binds since
   * ADR-073/ADR-097, so only a component can fail. Mirrors [collectionInputSkipReason]'s admitted
   * checks, ADR-083's non-null key rule included, so the named component is the one that actually
   * failed rather than whichever slot is checked last. `null` for any other reason, and for a
   * `RawCollection` (no component survived classification) whose hint stays unnamed.
   */
  private fun BridgeType.collectionComponentDetail(): String? {
    val collection: BridgeType.Collection = unwrapNullable() as? BridgeType.Collection ?: return null
    // ADR-083 amendment (boundary nullability part B): NULLABLE_MAP_KEY gets the same "key type
    // String?" detail COLLECTION gets, so its hint can name the offending slot; every other reason
    // keeps its own unnamed wording.
    val reason: ForwardPlanSkipReason? = collection.collectionInputSkipReason()
    if (reason == ForwardPlanSkipReason.NULLABLE_MAP_KEY) {
      return "key type ${collection.key?.diagnosticTypeName() ?: "unknown"}"
    }
    if (reason != ForwardPlanSkipReason.COLLECTION) return null
    val isMap: Boolean =
      collection.kind == CollectionKind.MAP || collection.kind == CollectionKind.MUTABLE_MAP
    if (!isMap) {
      return "element type ${collection.element?.diagnosticTypeName() ?: "unknown"}"
    }
    val keyOk: Boolean = collection.key?.isWrappableComponent() == true
    val valueOk: Boolean = collection.value?.isWrappableComponent() == true
    val key: String = collection.key?.diagnosticTypeName() ?: "unknown"
    val value: String = collection.value?.diagnosticTypeName() ?: "unknown"
    return when {
      !keyOk && !valueOk -> "key type $key and value type $value"
      !keyOk -> "key type $key"
      else -> "value type $value"
    }
  }

  private fun BridgeType.Collection.collectionInputSkipReason(): ForwardPlanSkipReason? = when {
    // ROADMAP Phase 4: one attribution rule, shared with the result side -- `skipReason()`'s own
    // Collection arm names the component that failed (and the DECLINED bytes slots) rather than
    // the first slot that happens to be populated.
    !isBridgeableComponent() -> skipReason() ?: ForwardPlanSkipReason.UNSUPPORTED

    // ADR-073: map/set inputs are admitted only for components the write side can box
    // (isWrappableComponent). ADR-083: the *key* additionally has to be non-nullable -- a C#
    // Dictionary cannot hold a null key, so a nullable-key map has no idiomatic projection and
    // skips named, even though its value slot would be fine.
    // ADR-083 amendment (boundary nullability part B): the key's nullability is no longer tested
    // here. `declinesNullableMapKey` makes `isBridgeableComponent()` false, so the arm above fires
    // first and attributes the skip to NULLABLE_MAP_KEY at an input position exactly as it does at
    // a return one -- one rule, one wording, every position.
    // ADR-201 amendment: a `Throwable` component declared narrower than `RuntimeException` cannot
    // hold the `NugetManagedException` each element arrives as; named for that, not COLLECTION.
    listOfNotNull(element, key, value).any { component -> component.isNarrowThrowable() } ->
      ForwardPlanSkipReason.THROWABLE

    kind == CollectionKind.MAP || kind == CollectionKind.MUTABLE_MAP -> {
      val keyAdmitted: Boolean = key?.isWrappableComponent() == true
      val valueAdmitted: Boolean = value?.isWrappableComponent() == true
      if (keyAdmitted && valueAdmitted) null else ForwardPlanSkipReason.COLLECTION
    }

    else ->
      if (element?.isWrappableComponent() == true) null else ForwardPlanSkipReason.COLLECTION
  }

  private fun BridgeType.wireType(): ForwardAbiWireType = when (this) {
    BridgeType.Unit -> ForwardAbiWireType.VOID
    // ADR-160: a callback occupies two POINTER slots of its own, built explicitly by
    // `nativeInputParameters`; this is the wire of each of them, and never of a result (a callback
    // has no result shape).
    is BridgeType.Callback -> ForwardAbiWireType.POINTER
    // ADR-160 amendment: the one OWNED handle a returned lambda crosses as.
    is BridgeType.ReturnedLambda -> ForwardAbiWireType.POINTER
    // ADR-107: the error-envelope pointer. Unreachable from a callable plan today (no shape and
    // no input arm admits a Throwable), but it is the wire the property route uses, so naming it
    // here keeps the two planners' answers identical rather than erroring on a live type.
    is BridgeType.Throwable -> ForwardAbiWireType.POINTER
    // ADR-106: the hex-dash text wire, the same STRING slot a String input takes; a Uuid *result*
    // overrides this with the POINTER shape in shapeOrNull, exactly as String does.
    BridgeType.Uuid -> ForwardAbiWireType.STRING
    is BridgeType.Primitive -> when (kind) {
      PrimitiveKind.BOOLEAN -> ForwardAbiWireType.BOOLEAN
      PrimitiveKind.BYTE -> ForwardAbiWireType.INT8
      PrimitiveKind.UBYTE -> ForwardAbiWireType.UINT8
      PrimitiveKind.SHORT -> ForwardAbiWireType.INT16
      PrimitiveKind.USHORT -> ForwardAbiWireType.UINT16
      PrimitiveKind.INT -> ForwardAbiWireType.INT32
      PrimitiveKind.UINT -> ForwardAbiWireType.UINT32
      PrimitiveKind.LONG -> ForwardAbiWireType.INT64
      PrimitiveKind.ULONG -> ForwardAbiWireType.UINT64
      PrimitiveKind.FLOAT -> ForwardAbiWireType.FLOAT32
      PrimitiveKind.DOUBLE -> ForwardAbiWireType.FLOAT64
    }

    BridgeType.String -> ForwardAbiWireType.STRING
    BridgeType.Char -> ForwardAbiWireType.CHAR16
    // ADR-076: like Enum, Instant always needs an explicit conversion tag (INSTANT_TO_TICKS/
    // TICKS_TO_INSTANT) at its own call site rather than this untagged pass-through -- every
    // caller builds its own ForwardTransfer for it instead of reaching this generic helper.
    // ADR-103: same for Duration and its DURATION_TO_TICKS/TICKS_TO_DURATION pair.
    BridgeType.Instant,
    BridgeType.Duration,
    is BridgeType.Nullable,
    is BridgeType.Collection,
      // ADR-151: like Collection, always tagged with its own conversion at its call site.
    BridgeType.ByteArray,
    is BridgeType.RawCollection,
    is BridgeType.Enum,
    is BridgeType.ObjectHandle,
    is BridgeType.Interface,
      // ADR-147: like ObjectHandle, a type parameter always builds its own tagged transfer at its
      // call site (the boxed-handle wire), so it never reaches this untagged pass-through.
    is BridgeType.TypeParameter,
      // ADR-088: like ObjectHandle/Interface, a bound interface always builds its own tagged
      // ForwardTransfer at its call site rather than reaching this untagged pass-through.
    is BridgeType.BoundInterface,
    is BridgeType.ValueClass,
    is BridgeType.SpecializedProtocol,
    is BridgeType.RawKSType,
    is BridgeType.Unsupported,
      -> error("Forward planner requested a wire type for ineligible $this")
  }

  /**
   * ADR-077 sub-item 4: the wire a value-class *underlying* rides. Unlike [wireType], which
   * rejects Enum/ObjectHandle at a declared position (they have their own transfer machinery
   * there), an underlying crosses as its ordinal / StableRef pointer inside a single-slot
   * value-class transfer.
   */
  private fun BridgeType.underlyingWireType(): ForwardAbiWireType = when (this) {
    is BridgeType.Enum -> ForwardAbiWireType.INT32
    is BridgeType.ObjectHandle -> ForwardAbiWireType.POINTER
    BridgeType.Unit,
    is BridgeType.Primitive,
    BridgeType.Char,
    BridgeType.String,
    BridgeType.Instant,
    BridgeType.Duration,
    is BridgeType.Throwable,
    BridgeType.Uuid,
    is BridgeType.Interface,
    is BridgeType.BoundInterface,
    is BridgeType.ValueClass,
    BridgeType.ByteArray,
    is BridgeType.Collection,
    is BridgeType.Nullable,
    is BridgeType.Callback,
    is BridgeType.ReturnedLambda,
    is BridgeType.SpecializedProtocol,
    is BridgeType.RawKSType,
    is BridgeType.Unsupported,
    is BridgeType.RawCollection,
    is BridgeType.TypeParameter,
      -> wireType()
  }
}

/**
 * ADR-105 (issue #54): the classifier's `sealed helper` protocol replaced by the
 * [BridgeType.ObjectHandle] it carries ([BridgeType.SpecializedProtocol.sealedHandle],
 * `viaDiscriminator = true`), so a position that can bridge a sealed base rides the existing
 * `ObjectHandle` arms of `isPlannable` / `shapeOrNull` / `wireType` / the Kotlin emitter / the CIR
 * projection rather than gaining a variant of its own.
 *
 * Applied at a *property* type ([ForwardPropertyPlanner]) and, at a callable, to both its *result*
 * and every declared *parameter* ([ForwardCallablePlanner.planOrSkip], ADR-105 scope (d)), and at
 * an extension *receiver*, at both routes that can hand one a sealed base rather than a bare
 * handle: a function's (`ForwardCallablePlanner.extensionEntry`) and a property's
 * (`ForwardPropertyPlanner.extensionProperty`).
 *
 * Recurses through [BridgeType.Nullable], the [BridgeType.Collection] components, and
 * [BridgeType.ValueClass.underlying]: a value class over a sealed type
 * (`value class ObservationResult(val observation: Observation)`) carries the ADR-009 handle in its
 * single member, so the underlying rewrite is all it needs to bind at a property or a callable
 * position. The C# reconstruction composes the two steps
 * (`new ObservationResult(Observation.FromHandle(nativeResult))`).
 *
 * A protocol with a `null` [BridgeType.SpecializedProtocol.sealedHandle] (a sealed interface, an
 * out-of-scope sealed class, or any non-sealed protocol) is returned untouched and skips named
 * exactly as before.
 */
internal fun BridgeType.sealedAsHandle(): BridgeType = when (this) {
  is BridgeType.SpecializedProtocol -> sealedHandle ?: this
  is BridgeType.Nullable -> BridgeType.Nullable(type.sealedAsHandle())
  is BridgeType.Collection -> copy(
    element = element?.sealedAsHandle(),
    key = key?.sealedAsHandle(),
    value = value?.sealedAsHandle(),
  )

  is BridgeType.ValueClass -> copy(underlying = underlying.sealedAsHandle())

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
  BridgeType.ByteArray,
  is BridgeType.Callback,
  is BridgeType.ReturnedLambda,
  is BridgeType.RawKSType,
  is BridgeType.Unsupported,
  is BridgeType.RawCollection,
  is BridgeType.TypeParameter,
    -> this
}

/**
 * ADR-066: a collection whose element (or map key/value) type is itself unsupported must skip
 * the whole callable through the normal named-diagnostic path, not reach the plan validator —
 * `handleResultShape`/`inputSkipReason` used to build a Collection shape unconditionally, so an
 * unsupported element only surfaced as a hard `IllegalStateException` out of
 * `ForwardCallablePlanValidator.validateType`, crashing the entire `packNuget` rather than
 * skipping the one member. Mirrors [ForwardCallablePlanValidator.validateType]'s error branches
 * exactly, so anything that would `error(...)` there returns `false` here instead.
 *
 * ADR-075: lifted from a `ForwardCallablePlanner` private member to file-level `internal` — the
 * body touches no planner state — so [ForwardPropertyPlanner] can reuse it unchanged for a
 * collection property's setter eligibility.
 */
internal fun BridgeType.isBridgeableComponent(): Boolean = when (this) {
  BridgeType.Unit, BridgeType.Char, BridgeType.String, is BridgeType.Primitive,
  is BridgeType.Enum, is BridgeType.ObjectHandle,
    -> true

  // ADR-176: an interface is an ordinary collection component (lifting ADR-040's "collections of
  // interfaces" deferral). Each element reads through the ADR-173 token-aware `Materialize<T>`
  // (a C#-implemented element resolves to the caller's object, a Kotlin-backed one to the ADR-040
  // backing wrapper) and writes through `Wrap<T>`'s `NugetBridge.HandleFor` fallback. The backing
  // wrapper and its `Factories` key exist because `NugetProcessor`'s reachability walk visits every
  // collection component.
  is BridgeType.Interface -> true

  // ADR-201: a `Throwable` component is boxed as its own ADR-107 envelope (Kotlin projects each
  // element through `buildError`), and C# rebuilds each with `NugetErrorNative.BuildException`.
  // Read positions only: [isWrappableComponent] keeps refusing it, so a `List<Throwable>` input
  // still skips. The `Set` element and `Map` KEY slots stay refused: see
  // [declinesThrowableComponent].
  is BridgeType.Throwable -> true

  // ADR-160: a callback is a parameter-position type only; a `List<(Int) -> Unit>` has no wire at
  // all, so the member skips named rather than half-binding.
  is BridgeType.Callback -> false

  // ADR-160 amendment: a returned lambda binds at a top-level result only, never nested.
  is BridgeType.ReturnedLambda -> false

  // ADR-106: collection components are deferred (the component would need a `nuget_wrap_*` arm
  // over the text form), so `List<Uuid>` skips named rather than half-binding.
  BridgeType.Uuid -> false

  // ROADMAP Phase 4 (ADR-151 amendment): a `ByteArray` component crosses as its own StableRef
  // handle in the pointer-shaped slot every component already uses -- the ADR-099 nested-collection
  // arm, one handle kind over. C# reads `NugetMarshal.ReadBytes(h)` and writes
  // `NugetMarshal.CreateBytes(x)`; Kotlin casts `it as kotlin.ByteArray` on the way in and boxes
  // the container untouched on the way out. No new runtime export. The `Set` element and the `Map`
  // KEY slots stay refused: see [declinesByteArrayComponent].
  BridgeType.ByteArray -> true

  // ADR-201: a value class over `Throwable` stays deferred. Its per-element projection boxes the
  // underlying itself, never the envelope, so it is refused here rather than read as a handle.
  is BridgeType.ValueClass ->
    underlying !is BridgeType.Throwable && underlying.isBridgeableComponent()
  is BridgeType.Nullable -> type !is BridgeType.Nullable && type != BridgeType.Unit &&
      type.isBridgeableComponent()

  // ADR-083 amendment (boundary nullability part B): a nullable map KEY fails here too, which is
  // what carries the rule to the result, property-read and NESTED positions -- this function
  // recurses, so `List<Map<String?, Int>>` is covered by the same one consult.
  is BridgeType.Collection -> if (
    declinesByteArrayComponent() || declinesThrowableComponent() || declinesNullableMapKey()
  ) {
    false
  } else {
    val isMap: Boolean = kind == CollectionKind.MAP || kind == CollectionKind.MUTABLE_MAP
    if (isMap) {
      key?.isBridgeableComponent() == true && value?.isBridgeableComponent() == true
    } else {
      element?.isBridgeableComponent() == true
    }
  }

  // ADR-076: "Instant as a collection element" is explicitly deferred (its own boxing question,
  // ADR-073/075's isWrappableComponent allow-list territory) -- same route.
  // ADR-088: "bound interfaces as collection components" is on this ADR's own deferred list, for
  // the same reason -- the wrap/box helpers have no route for a GCHandle element.
  // ADR-103: "Duration as a collection element" is deferred for the identical reason.
  // ADR-147 v1: a type parameter binds at a top-level position only. `List<T>` would need a
  // per-element box the write side has no arm for, so it skips named here instead.
  is BridgeType.TypeParameter,
  is BridgeType.BoundInterface, BridgeType.Instant, BridgeType.Duration,
  is BridgeType.RawCollection, is BridgeType.RawKSType, is BridgeType.SpecializedProtocol,
  is BridgeType.Unsupported,
    -> false
}

/**
 * ROADMAP Phase 4: whether a `ByteArray` appears anywhere in this type, nested components included.
 * ADR-151 tested only the top level (`unwrapNullable() == ByteArray`), which was right while a
 * component could not be one; now `List<ByteArray>` needs the same `ForwardHelperRequirement.BYTES`
 * on the plan as a bare `ByteArray` does. (The C# helper EMISSION is tracker-derived and already
 * recursive, `CollectionHelperTracker.trackCollection`; this is the plan's own honest spelling of
 * what it converts.)
 */
internal fun BridgeType.containsByteArray(): Boolean {
  val type: BridgeType = unwrapNullable()
  return when (type) {
    BridgeType.ByteArray -> true
    is BridgeType.Collection ->
      listOfNotNull(type.element, type.key, type.value).any { it.containsByteArray() }

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
    is BridgeType.Nullable,
    is BridgeType.Callback,
    is BridgeType.ReturnedLambda,
    is BridgeType.SpecializedProtocol,
    is BridgeType.RawKSType,
    is BridgeType.Unsupported,
    is BridgeType.RawCollection,
    is BridgeType.TypeParameter,
      -> false
  }
}

/**
 * ROADMAP Phase 4, the ADR-151 amendment's DECLINED list (not a deferral): the two component slots
 * a `ByteArray` must never occupy, whichever gate is asking.
 *
 * A `Set` element and a `Map` KEY are both equality slots, and an array compares by IDENTITY in
 * Kotlin and in C# alike. Every crossing of this bridge copies, so the `byte[]` a C# caller holds
 * is never the `ByteArray` instance the Kotlin container hashed: `set.Contains(bytes)` and
 * `map[bytes]` would compile, run, and silently never match. Binding them would be a trap dressed
 * as a feature, so they skip named with the hint that says why and what to use instead.
 *
 * The `List`/`MutableList` element and the `Map`/`MutableMap` VALUE slots carry no equality
 * contract, so they bind.
 */
internal fun BridgeType.Collection.declinesByteArrayComponent(): Boolean = when (kind) {
  CollectionKind.MAP, CollectionKind.MUTABLE_MAP ->
    key?.unwrapNullable() == BridgeType.ByteArray

  CollectionKind.SET, CollectionKind.MUTABLE_SET ->
    element?.unwrapNullable() == BridgeType.ByteArray

  CollectionKind.LIST, CollectionKind.MUTABLE_LIST -> false
}

/**
 * ADR-201: the two component slots a `Throwable` must never occupy, the same equality slots
 * [declinesByteArrayComponent] refuses. Each crossing builds a fresh envelope and a fresh C#
 * exception, and `System.Exception` compares by reference, so a C# `IReadOnlySet<Exception>` or
 * dictionary key could never be looked up. A `List` element and a `Map` VALUE bind.
 */
internal fun BridgeType.Collection.declinesThrowableComponent(): Boolean = when (kind) {
  CollectionKind.MAP, CollectionKind.MUTABLE_MAP -> key?.unwrapNullable() is BridgeType.Throwable
  CollectionKind.SET, CollectionKind.MUTABLE_SET ->
    element?.unwrapNullable() is BridgeType.Throwable

  CollectionKind.LIST, CollectionKind.MUTABLE_LIST -> false
}

/** ADR-201: a `Throwable` declared narrower than `RuntimeException`, nullable or not. */
internal fun BridgeType.isNarrowThrowable(): Boolean =
  (unwrapNullable() as? BridgeType.Throwable)?.acceptsManagedException == false

/**
 * ADR-201: the declared spelling a THROWABLE input skip names: the throwable's simple name for a
 * bare one (`IllegalStateException`), the collection with its narrow component
 * (`List<IllegalStateException>`) for a list or map input. Null when neither applies.
 */
internal fun BridgeType.throwableInputDetail(): String? {
  fun spell(component: BridgeType): String {
    val throwable: BridgeType.Throwable =
      component.unwrapNullable() as? BridgeType.Throwable ?: return component.diagnosticTypeName()
    val suffix: String = if (component is BridgeType.Nullable) "?" else ""
    return throwable.kotlinType.substringAfterLast('.') + suffix
  }
  val bare: BridgeType = unwrapNullable()
  if (bare is BridgeType.Throwable) return spell(bare)
  val collection: BridgeType.Collection = bare as? BridgeType.Collection ?: return null
  val components: List<BridgeType> =
    listOfNotNull(collection.key, collection.value, collection.element)
  if (components.none { component -> component.isNarrowThrowable() }) return null
  val kind: String = when (collection.kind) {
    CollectionKind.LIST -> "List"
    CollectionKind.MUTABLE_LIST -> "MutableList"
    CollectionKind.MAP -> "Map"
    CollectionKind.MUTABLE_MAP -> "MutableMap"
    CollectionKind.SET -> "Set"
    CollectionKind.MUTABLE_SET -> "MutableSet"
  }
  return components.joinToString(", ", "$kind<", ">") { component -> spell(component) }
}

/**
 * ADR-083 amendment (boundary nullability part B): whether this collection is a map whose KEY is
 * nullable, which is declined at EVERY position rather than only at an input one.
 *
 * ADR-083 refused a nullable key at the input positions and deliberately left the result-position
 * gates untouched. Measured consequence: `fun perchScores(): Map<String?, Int>` rendered
 * `IReadOnlyDictionary<string?, int>` over `NugetMarshal.ReadMap<string?, int>`, and that helper is
 * `where TKey : notnull`, so the generated file raised CS8714 -- an ERROR under the generated
 * bindings csproj (`<Nullable>enable</Nullable>` plus `<TreatWarningsAsErrors>true`), at the member
 * return, the property read, a nested component, a top-level function, a `suspend fun` and a
 * `Flow` element alike. So the shape never compiled anywhere, which is why declining it removes
 * nothing that worked.
 *
 * Declining rather than binding is also the idiomatic answer: `Dictionary`, `ImmutableDictionary`
 * and `FrozenDictionary` all throw on a null key, as do Java's `Map.of` and Swift's ObjC bridge. A
 * Kotlin `Map<String?, V>` is the outlier.
 */
/**
 * The "key type String?" detail for the innermost map [declinesNullableMapKey] refuses, searched
 * recursively so a nested `List<Map<String?, Int>>` names the key rather than the list. `null`
 * when nothing here is a nullable-key map, which is what keeps the shared `skipDetail()` chain
 * intact for every other reason.
 */
internal fun BridgeType.nullableMapKeyDetail(): String? {
  val collection: BridgeType.Collection =
    (if (this is BridgeType.Nullable) type else this) as? BridgeType.Collection ?: return null
  if (collection.declinesNullableMapKey()) {
    return "key type ${collection.key?.diagnosticTypeName() ?: "unknown"}"
  }
  return listOfNotNull(collection.element, collection.key, collection.value)
    .firstNotNullOfOrNull { component -> component.nullableMapKeyDetail() }
}

internal fun BridgeType.Collection.declinesNullableMapKey(): Boolean = when (kind) {
  CollectionKind.MAP, CollectionKind.MUTABLE_MAP -> key is BridgeType.Nullable
  CollectionKind.SET, CollectionKind.MUTABLE_SET,
  CollectionKind.LIST, CollectionKind.MUTABLE_LIST,
    -> false
}

/**
 * ADR-073: the component types the C# write side can actually box, for an input-position
 * `Map`/`Set` (and their mutable variants): the six `nuget_wrap_*` primitives plus an object
 * handle (via `CreateMap`/`CreateSet`'s reflective `_handle` fallback), plus (ADR-081) a value
 * class over any of those underlyings, projected to the underlying per element, plus (ADR-097) a
 * bare `Enum`, which rides that same per-element projection as its int ordinal, plus (ADR-098) the
 * six narrow primitive kinds and `Char`, each with a `nuget_wrap_*` export of its own. Still
 * narrower than [isBridgeableComponent], which also admits nested `Collection` and `Unit` (neither
 * of which the write side can box), because those overshoots would otherwise either crash
 * `packNuget` (nested `Collection`, no `elementKotlinTypeName` branch) or throw at runtime
 * (`NotSupportedException`, no matching `nuget_wrap_*`).
 *
 * ADR-097: this is now the gate for *every* input position, `List` included. ADR-075 already
 * reused it for a collection *property setter*.
 *
 * ADR-075: lifted from a `ForwardCallablePlanner` private member to file-level `internal` — the
 * body touches no planner state.
 */
internal fun BridgeType.isWrappableComponent(): Boolean = when (this) {
  BridgeType.String -> true
  // ADR-098: every PrimitiveKind is wrappable now that the six narrow kinds have a
  // `nuget_wrap_*` export each. Kept as an explicit set rather than `true` so a future kind has
  // to be admitted deliberately, with its export minted alongside.
  is BridgeType.Primitive -> kind in setOf(
    PrimitiveKind.BYTE, PrimitiveKind.UBYTE, PrimitiveKind.SHORT, PrimitiveKind.USHORT,
    PrimitiveKind.INT, PrimitiveKind.UINT, PrimitiveKind.LONG, PrimitiveKind.ULONG,
    PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE, PrimitiveKind.BOOLEAN,
  )

  // ADR-098 part B: `Char` is its own BridgeType, not a PrimitiveKind, so it needs its own arm.
  // It crosses as the UTF-16 code unit Kotlin already emits (`KChar` = `unsigned short`), with
  // the C# side pinned to that width by `[MarshalAs(UnmanagedType.U2)]`.
  BridgeType.Char -> true

  // ROADMAP Phase 4 (ADR-151 amendment): the write side mints one `nuget_bytes_create` handle per
  // element through the `Select` projection, so `Wrap<T>` is only ever instantiated at `T = IntPtr`
  // (or `IntPtr?`) -- a branch it already has. `nuget_list_add`/`nuget_map_put` then store the
  // DEREFERENCED object, which is the real `ByteArray`, and the fill loop disposes the box it owns.
  BridgeType.ByteArray -> true

  // ADR-105 scope (d): every handle boxes through `CreateList`/`CreateMap`/`CreateSet`, which end
  // in `Wrap<T>`'s `if (value is INugetHandle wrapper)` arm -- a runtime type test, so an abstract
  // C# base satisfies it exactly as a concrete wrapper does. A *discriminated* handle (an ADR-009
  // sealed base) was gated here while only the read side was open; opening the write side admits a
  // `List<Shape>` parameter and, through the same shared gate, gives a
  // `var shapes: MutableList<Shape>` property its setter back.
  is BridgeType.ObjectHandle -> true

  // ADR-097: a *bare* enum component rides the same int-ordinal wire ADR-081 minted for a value
  // class over an enum, projected per element at the C# call site (`(int)x`) and re-wrapped as
  // `Mood.entries[it as Int]` on the Kotlin side, so `Wrap<T>` is only ever instantiated at
  // `T = int`. One branch here admits it at every position the predicate guards: `List`/`Set`/`Map`
  // callable inputs and collection property setters.
  is BridgeType.Enum -> true

  // ADR-176: an interface component boxes through `Wrap<T>`: a Kotlin-backed `IPet` wrapper is an
  // `INugetHandle` (its own handle, not owned); any other C# implementation falls to ADR-173's
  // `HandleOf` -> `NugetBridge.HandleFor` arm (an owned transfer handle the fill loop disposes).
  // `nuget_list_add` stores the dereferenced Kotlin bridge object, so the Kotlin `it as Pet` cast
  // holds for both.
  is BridgeType.Interface -> true

  // ADR-201 amendment: a `Throwable` component takes the parameter encoding per element: C# boxes
  // its `"{FullName}: {Message}"` text, Kotlin casts the box to `String` and builds a
  // `NugetManagedException`, so only a declaration that can hold one is wrappable.
  is BridgeType.Throwable -> acceptsManagedException

  // ADR-081: a value-class component crosses as its *underlying*, projected per element at the C#
  // call site (`x.Value`, `(int)x.Mood`, `x.Patient`) before `Wrap<T>` is ever instantiated, so the
  // write side only ever boxes a type it already handles.
  is BridgeType.ValueClass ->
    underlying is BridgeType.Enum || underlying.isWrappableComponent()

  // ADR-099: a nested collection crosses as the inner collection's own native handle, built by the
  // same CreateList/CreateSet/CreateMap the outer one uses and read back through the matching
  // Read* helper. Recursive, so depth 3 is the same code as depth 1. The map-key rule mirrors the
  // top-level one: a C# Dictionary cannot hold a null key.
  // ADR-083 amendment (boundary nullability part B): the inline "key is not Nullable" test that
  // used to sit in the map arm below now lives in [declinesNullableMapKey], so the write side and
  // every read side consult one predicate instead of four copies of the rule.
  is BridgeType.Collection -> if (
    declinesByteArrayComponent() || declinesThrowableComponent() || declinesNullableMapKey()
  ) {
    false
  } else {
    val isMap: Boolean = kind == CollectionKind.MAP || kind == CollectionKind.MUTABLE_MAP
    if (isMap) {
      key?.isWrappableComponent() == true && value?.isWrappableComponent() == true
    } else {
      element?.isWrappableComponent() == true
    }
  }

  // ADR-083: a component slot is already pointer-shaped (every element crosses as a boxed
  // StableRef handle), so the null pointer is an in-band null for every wrappable component kind
  // -- including Int?, which at an *ordinary* position needs the ADR-079 has-value pair. Nesting
  // is excluded, matching isBridgeableComponent's own no-nested-nullable rule.
  //
  // ADR-099: the `Collection` exclusion is deliberate and load-bearing. This branch delegates to
  // its inner type, so `List<List<String>?>` would be admitted *automatically* the instant a
  // Collection became wrappable, and it would bind with a write projection that has no null arm --
  // the exact trap ADR-097 hit with `List<Mood?>`. A nullable *leaf* under nesting
  // (`List<List<String?>>`) is admitted and rides ADR-083's arms under the recursion.
  is BridgeType.Nullable -> type !is BridgeType.Nullable && type !is BridgeType.Collection &&
      type.isWrappableComponent()

  BridgeType.Unit,
  BridgeType.Instant,
  BridgeType.Duration,
  BridgeType.Uuid,
  is BridgeType.BoundInterface,
  is BridgeType.Callback,
  is BridgeType.ReturnedLambda,
  is BridgeType.SpecializedProtocol,
  is BridgeType.RawKSType,
  is BridgeType.Unsupported,
  is BridgeType.RawCollection,
  is BridgeType.TypeParameter,
    -> false
}

/**
 * The name this declared parameter carries on the plan, and therefore in both projections. See
 * [bridgeParameterName] for why the shift happens here rather than at either render site, and
 * `PLAN_OWNED_NAMES` for the set. `_` for a parameter KSP cannot name at all.
 *
 * Applied only where a *user's* parameter enters the plan. The generator's own slots
 * (`receiverParameter`, `errorParameter`, the ADR-061 out-slot) pass their literal names straight
 * through, which is exactly what makes the shift injective against them.
 */
/**
 * ADR-150: the KDoc this declaration exports, or null.
 *
 * Only an `Origin.KOTLIN` declaration's `docString` is read: a SYNTHETIC member (an enum's
 * `values`/`valueOf`/`entries`, an object's implicit constructor) reports its *owner's* comment on
 * KSP 2.3.10 (verified), so reading it would put the type's summary on every helper it generates.
 * An `actual` carries no KDoc at all, so the paired `expect` is consulted through [ExpectIndex].
 */
internal fun KSDeclaration.forwardKdoc(expects: ExpectIndex): ForwardKdoc? {
  if (origin != Origin.KOTLIN) return null
  val doc: ForwardKdoc = (
    parseKdoc(docString ?: expects.docOrNull(this))
    ?: (this as? KSPropertyDeclaration)?.propertyTagKdoc(expects)
    ?: return null
  ).scopedAt(this, expects)
  if (doc.throws.isEmpty()) return doc
  return doc.copy(
    throws = doc.throws.map { entry ->
      entry.copy(resolvedCsharpType = resolveThrownType(entry.type, expects)?.mappedCsharpType())
    },
  )
}

/**
 * A bare `[Name]` resolves like an identifier at the declaration it documents: its own class
 * scope outward, then its package. Each target that names a type that way is stamped with that
 * type's dotted path from the package, so `CirFile.resolveDocLinks()` crefs that type or nothing,
 * and never a same-named type in another package. KSP exposes no file imports, so a type reached
 * only through an import stays unscoped, and keeps the file-wide unique-name fallback.
 */
private fun ForwardKdoc.scopedAt(declaration: KSDeclaration, expects: ExpectIndex): ForwardKdoc {
  val paths: Map<String, String> = linkTargets()
    .mapNotNull { target -> declaration.linkPath(target, expects)?.let { path -> target to path } }
    .toMap()
  return if (paths.isEmpty()) this else copy(linkPaths = linkPaths + paths)
}

private fun KSDeclaration.linkPath(target: String, expects: ExpectIndex): String? {
  val head: String = target.substringBefore('.')
  var scope: KSDeclaration? = this as? KSClassDeclaration ?: parentDeclaration
  while (scope != null) {
    val declaresHead: Boolean = scope is KSClassDeclaration &&
      scope.declarations.any { it is KSClassDeclaration && it.simpleName.asString() == head }
    if (declaresHead) return (scope.typePath() + target).joinToString(".")
    scope = scope.parentDeclaration
  }
  val pkg: String = packageName.asString()
  val qualified: String = if (pkg.isEmpty()) head else "$pkg.$head"
  return target.takeIf { expects.classByName(qualified) != null }
}

/** The simple names from the outermost enclosing class down to this one. */
private fun KSDeclaration.typePath(): List<String> =
  generateSequence(this) { it.parentDeclaration }
    .map { it.simpleName.asString() }
    .toList()
    .reversed()

/**
 * ADR-177: the class an ADR-150 `@throws T` names. KSP exposes no file imports, so the written
 * name is tried as a qualified name, then in this declaration's package, then in the packages an
 * exception is conventionally imported from. `null` when none resolves.
 */
private fun KSDeclaration.resolveThrownType(
  written: String,
  expects: ExpectIndex,
): KSClassDeclaration? {
  val candidates: List<String> = if ('.' in written) {
    listOf(written)
  } else {
    listOf(
      "${packageName.asString()}.$written",
      "kotlin.$written",
      "kotlin.coroutines.cancellation.$written",
      "kotlinx.io.$written",
    )
  }
  return candidates.firstNotNullOfOrNull { name -> expects.classByName(name) }
}

private val JVM_STDLIB_PACKAGE: Regex = Regex("""^java\.(lang|util)\.""")

/**
 * ADR-177: the first mapping row this class IS-A, in the table's most-specific-first order, the
 * same rule the Kotlin side applies at runtime. A NAME row matches only the exact class.
 */
private fun KSClassDeclaration.mappedCsharpType(): String {
  val self: String? = qualifiedName?.asString()
  // On a JVM compilation (Tier 1) the stdlib rows are typealiases of `java.lang`/`java.util`
  // classes, so those spell their Kotlin name; on Kotlin/Native they already are `kotlin.*`.
  val lineage: Set<String?> = getAllSuperTypes()
    .map { it.declaration.qualifiedName?.asString()?.replace(JVM_STDLIB_PACKAGE, "kotlin.") }
    .toSet() + self
  return KOTLIN_EXCEPTION_TYPES.firstOrNull { row ->
    when (row.match) {
      KotlinExceptionMatch.IS -> row.kotlinType in lineage
      KotlinExceptionMatch.NAME -> row.kotlinType == self
    }
  }?.csharpType ?: "KotlinException"
}

/**
 * ADR-150 amendment: a constructor property (`class Bowl(val flavour: String)`) reports
 * `docString == null` through KSP (spike 2, 2026-09-20, confirming ADR-150's spike 1 finding 4),
 * and its text lives on the CLASS comment as `@property flavour`. This is the one place that is
 * read, so all four property call sites inherit it.
 *
 * Own KDoc wins: an inline `/** own */ val name` DOES report a `docString` (spike 2), so the
 * caller reaching here has already established the property documents nothing itself.
 *
 * `@property` only, never the class-level `@param`: a `@param` documents the *constructor
 * parameter*, and putting it on the property would document a member the author never described.
 */
private fun KSPropertyDeclaration.propertyTagKdoc(expects: ExpectIndex): ForwardKdoc? {
  val owner: KSClassDeclaration = parentDeclaration as? KSClassDeclaration ?: return null
  val text: String = owner.classLevelKdoc(expects)?.properties?.get(simpleName.asString())
    ?: return null
  return ForwardKdoc(summary = text)
}

/**
 * ADR-150 amendment: the class's own comment, parsed for the tags that document a *different*
 * declaration (`@property`, `@constructor`, and the class-level `@param` of the primary
 * constructor). Never the constructor's own `docString`: an implicit primary is SYNTHETIC and
 * reports the whole class comment (ADR-150 spike 1 finding 3).
 */
private fun KSClassDeclaration.classLevelKdoc(expects: ExpectIndex): ForwardKdoc? {
  if (origin != Origin.KOTLIN) return null
  return parseKdoc(docString ?: expects.docOrNull(this))?.scopedAt(this, expects)
}

/**
 * ADR-150 amendment: the doc of a primary constructor, which carries none of its own (verified:
 * a declared primary reports `docString == null`, an implicit one is SYNTHETIC). `@constructor` is
 * its summary and the class-level `@param` tags are its parameters, with `@property` as the
 * fallback text for a same-named constructor parameter -- most authors document a `val` parameter
 * once, with `@property`, and without the fallback the C# constructor stays undocumented.
 *
 * `forParameters` then drops every name that is not a parameter of the rendered overload, which is
 * what keeps a CS1572 (and a `@property` for a body property) off the generated file.
 */
private fun KSFunctionDeclaration.primaryConstructorKdoc(
  cls: KSClassDeclaration?,
  expects: ExpectIndex,
  withSummary: Boolean,
): ForwardKdoc? {
  if (cls == null || this != cls.primaryConstructor) return null
  val classDoc: ForwardKdoc = cls.classLevelKdoc(expects) ?: return null
  val params: Map<String, String> =
    classDoc.params + classDoc.properties.filterKeys { it !in classDoc.params }
  val summary: String? = classDoc.constructor.takeIf { withSummary }
  if (summary == null && params.isEmpty()) return null
  return ForwardKdoc(summary = summary, params = params, linkPaths = classDoc.linkPaths)
}

/**
 * ADR-150: re-keys `@param` entries from the Kotlin parameter names the author wrote to the bridge
 * names the plan carries (`bridgeName()` respells a C#-owned name). A `@param` naming nothing in
 * [parameters] is dropped, which is what keeps a CS1572 off the generated file.
 */
private fun ForwardKdoc?.forParameters(parameters: List<KSValueParameter>): ForwardKdoc? {
  val doc: ForwardKdoc = this ?: return null
  return doc.copy(
    params = parameters
      .mapNotNull { parameter ->
        doc.params[parameter.name?.asString()]?.let { text -> parameter.bridgeName() to text }
      }
      .toMap(),
  )
}

/**
 * ADR-164: only the `@param` entries of parameters the widened signature still declares. A trailing
 * unroutable default dropped from it would otherwise document a parameter C# does not have
 * (CS1572).
 */
private fun ForwardKdoc?.forPublic(parameters: List<ForwardPublicParameter>): ForwardKdoc? {
  val doc: ForwardKdoc = this ?: return null
  val names: Set<String> = parameters.map { parameter -> parameter.name }.toSet()
  return doc.copy(params = doc.params.filterKeys { name -> name in names })
}

private fun KSValueParameter.bridgeName(): String =
  (name?.asString() ?: "_").bridgeParameterName()

/**
 * ADR-064's 2026-09-11 amendment: the skip-reason classification and its detail extractors sit at
 * file level so the *property* planner can carry the same reason a callable does. They read
 * nothing but the [BridgeType] they are called on, so lifting them out of
 * [ForwardCallablePlanner] costs nothing (the same move ADR-075 made for [isBridgeableComponent]).
 */
internal fun BridgeType.unwrapNullable(): BridgeType = if (this is BridgeType.Nullable) type else this

/** ADR-066: the qualified name to feed the `SKIPPED_UNEXPORTED_DEPENDENCY_TYPE` hint, when this
 *  (possibly nullable-wrapped) type is the direct reason a callable was dropped because it is a
 *  reachable-but-out-of-scope dependency type. `null` for every other skip reason. */
internal fun BridgeType.unexportedDependencyDetail(): String? {
  // ADR-154 §5: descends one collection level, exactly as [undeclaredTypeDetail] and
  // [sealedTypeDetail] do. Without it, `fun levels(): List<LogLevel>` carried NO detail and the
  // hint printed its literal `"the dependency's package"` fallback — a remedy naming no package
  // and no type (research spike 1b). The element is the type that was refused; the `List` never
  // was. The `dependency's package` fallback below is now unreachable from a collection position.
  val unwrapped: BridgeType = unwrapNullable()
  val candidate: BridgeType = when (unwrapped) {
    is BridgeType.Collection ->
      (unwrapped.element ?: unwrapped.key ?: unwrapped.value)?.unwrapNullable() ?: unwrapped

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
    is BridgeType.Nullable,
    is BridgeType.Callback,
    is BridgeType.ReturnedLambda,
    is BridgeType.SpecializedProtocol,
    is BridgeType.RawKSType,
    is BridgeType.Unsupported,
    is BridgeType.RawCollection,
    is BridgeType.TypeParameter,
      -> unwrapped
  }
  return (candidate as? BridgeType.Unsupported)
    ?.takeIf { unsupported -> unsupported.isUnexportedDependency }
    ?.rendered
}

/** ADR-074: the `expect` name and its erased-to target, when this (possibly nullable-wrapped)
 *  type is the direct reason a callable was dropped because its `actual typealias` target is
 *  not exportable. Encoded as `"<expect qualified name>-><target rendered name>"` so
 *  [ForwardDiagnosticKind.SKIPPED_ACTUAL_TYPEALIAS_TARGET]'s hint can name both without a
 *  second detail slot on [ForwardCallableCatalogEntry.Skipped]. `null` for every other reason. */
internal fun BridgeType.actualTypeAliasTargetDetail(): String? =
  (unwrapNullable() as? BridgeType.Unsupported)
    ?.takeIf { unsupported -> unsupported.isActualTypeAliasTarget }
    ?.let { unsupported -> "${unsupported.actualTypeAliasExpectName}->${unsupported.rendered}" }

/** True for the two "declared nowhere, at any position" flags, whose skip reason outranks the
 *  position-shaped ones ([ForwardPlanSkipReason.NULLABLE]) when both could apply. */
internal fun BridgeType.isUndeclared(): Boolean {
  val unsupported: BridgeType.Unsupported = this as? BridgeType.Unsupported ?: return false
  return unsupported.isUndeclaredEnum || unsupported.isUndeclaredInterface ||
      unsupported.isUndeclaredClass || unsupported.isUndeclaredValueClass
}

/** The undeclared type's qualified name, when this (possibly nullable-wrapped, possibly
 *  collection-wrapped) type is the direct reason a callable was dropped by
 *  [ForwardPlanSkipReason.UNDECLARED_ENUM] or [ForwardPlanSkipReason.UNDECLARED_INTERFACE].
 *  `null` for every other skip reason.
 *
 *  Descends one collection level, unlike its two siblings above: a `List<Outer.Mode>` parameter
 *  attributes to its *element's* reason (`collectionInputSkipReason`), and
 *  `collectionComponentDetail()` deliberately declines any reason but `COLLECTION`, so without
 *  this the hint for the element case would name no type at all. The siblings' equivalent gap
 *  (`List<UnexportedDep>`) is left exactly as it was, changing it would reword a shipped
 *  hint. */
/** ADR-115: `"<marked type>-><marker qualified name>"`, so the one diagnostic can name both
 *  the type the author wrote and the marker that removed it, without a second detail slot. */
internal fun BridgeType.optInMarkerDetail(): String? {
  val unwrapped: BridgeType = unwrapNullable()
  val candidate: BridgeType = when (unwrapped) {
    is BridgeType.Collection ->
      (unwrapped.element ?: unwrapped.key ?: unwrapped.value)?.unwrapNullable() ?: unwrapped

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
    is BridgeType.Nullable,
    is BridgeType.Callback,
    is BridgeType.ReturnedLambda,
    is BridgeType.SpecializedProtocol,
    is BridgeType.RawKSType,
    is BridgeType.Unsupported,
    is BridgeType.RawCollection,
    is BridgeType.TypeParameter,
      -> unwrapped
  }
  val unsupported: BridgeType.Unsupported = candidate as? BridgeType.Unsupported ?: return null
  val marker: String = unsupported.optInMarker ?: return null
  return "${unsupported.rendered}->$marker"
}

internal fun BridgeType.undeclaredTypeDetail(): String? {
  val unwrapped: BridgeType = unwrapNullable()
  val candidate: BridgeType = when (unwrapped) {
    is BridgeType.Collection ->
      (unwrapped.element ?: unwrapped.key ?: unwrapped.value)?.unwrapNullable() ?: unwrapped

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
    is BridgeType.Nullable,
    is BridgeType.Callback,
    is BridgeType.ReturnedLambda,
    is BridgeType.SpecializedProtocol,
    is BridgeType.RawKSType,
    is BridgeType.Unsupported,
    is BridgeType.RawCollection,
    is BridgeType.TypeParameter,
      -> unwrapped
  }
  return (candidate as? BridgeType.Unsupported)
    ?.takeIf { unsupported ->
      unsupported.isUndeclaredEnum || unsupported.isUndeclaredInterface ||
          unsupported.isUndeclaredClass || unsupported.isUndeclaredValueClass ||
          unsupported.isObjectPosition
    }
    ?.rendered
}

/** True for the ADR-009 sealed-hierarchy protocol, whichever position it turned up at. The
 *  classifier mints exactly one protocol name for it, so the prefix is the whole test. */
private fun BridgeType.isSealedProtocol(): Boolean =
  this is BridgeType.SpecializedProtocol && name.startsWith(SEALED_HELPER_PREFIX)

/** The sealed base's qualified name, when this (possibly nullable-wrapped, possibly
 *  collection-wrapped) type is the direct reason a callable took a
 *  [ForwardPlanSkipReason.SEALED_POSITION] skip. Descends one collection level for the same
 *  reason [undeclaredTypeDetail] does: a `List<Shape>` parameter attributes to its element's
 *  reason, so without this its hint would name no type at all. `null` for every other reason. */
internal fun BridgeType.sealedTypeDetail(): String? {
  val unwrapped: BridgeType = unwrapNullable()
  val candidate: BridgeType = when (unwrapped) {
    // The component that IS the sealed type: `Map<String, Sealed>` used to pick the `String` key
    // first, so the hint read "sealed type `the sealed type`" with nothing to name (issue #463).
    is BridgeType.Collection ->
      listOfNotNull(unwrapped.element, unwrapped.key, unwrapped.value)
        .map { component -> component.unwrapNullable() }
        .firstOrNull { component -> component.isSealedProtocol() }
        ?: unwrapped

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
    is BridgeType.Nullable,
    is BridgeType.Callback,
    is BridgeType.ReturnedLambda,
    is BridgeType.SpecializedProtocol,
    is BridgeType.RawKSType,
    is BridgeType.Unsupported,
    is BridgeType.RawCollection,
    is BridgeType.TypeParameter,
      -> unwrapped
  }
  val protocol: BridgeType.SpecializedProtocol = (candidate as? BridgeType.SpecializedProtocol)
    ?.takeIf { protocol -> protocol.name.startsWith(SEALED_HELPER_PREFIX) }
    ?: return null
  val name: String = protocol.name.removePrefix(SEALED_HELPER_PREFIX)
  // ADR-199: a generic sealed reference refused at its use site carries the reason after the name.
  return protocol.sealedRefusal?.let { why -> "$name$GENERIC_SEALED_REFUSAL_SEPARATOR$why" } ?: name
}

/** ADR-199: splits a SEALED_POSITION detail into the sealed type and its use-site refusal. */
internal const val GENERIC_SEALED_REFUSAL_SEPARATOR: String = ": "


internal fun BridgeType.skipReason(): ForwardPlanSkipReason? = when (this) {
  BridgeType.Unit, is BridgeType.Primitive -> null
  // ADR-160: only reached from a position a callback cannot bind at -- a RESULT (a Kotlin function
  // handed OUT is a different mechanism, `ReturnedLambda`, planned at a top-level function only) or
  // a collection component.
  // A parameter-position callback plans a shape and never asks.
  is BridgeType.Callback -> ForwardPlanSkipReason.CALLBACK_PROTOCOL
  // ADR-160 amendment: a returned lambda has a shape exactly when every type argument has a C#
  // spelling, so reaching here means one does not (or it was nested somewhere it cannot bind).
  is BridgeType.ReturnedLambda -> ForwardPlanSkipReason.LAMBDA_TYPE_ARGUMENT
  BridgeType.Char -> ForwardPlanSkipReason.CHAR
  BridgeType.String -> ForwardPlanSkipReason.STRING
  // ADR-076: defensive only -- shapeOrNull's Instant branch always succeeds, same as CHAR/
  // STRING above.
  BridgeType.Instant -> ForwardPlanSkipReason.INSTANT
  // ADR-103: defensive only, in the same way.
  BridgeType.Duration -> ForwardPlanSkipReason.DURATION
  // ADR-201: defensive at a result (shapeOrNull always has a Throwable branch); reached for real
  // from a narrower declared input type and from a nested position that does not bind.
  is BridgeType.Throwable -> ForwardPlanSkipReason.THROWABLE
  // ADR-106: defensive only, like Instant/Duration -- Uuid always has a return shape.
  BridgeType.Uuid -> ForwardPlanSkipReason.UUID
  // ADR-151: reached for real from the deferred nesting case (`List<ByteArray>`); defensive at
  // every top-level position, where a ByteArray always has a shape.
  BridgeType.ByteArray -> ForwardPlanSkipReason.BYTE_ARRAY
  // ADR-088: same deferred nullable position as the input side, named the same way instead of
  // reaching the generic NULLABLE bucket.
  is BridgeType.Nullable -> when {
    type is BridgeType.BoundInterface -> ForwardPlanSkipReason.BOUND_INTERFACE_POSITION
    // Issue #54: `Listener?` is not skipped *because* it is nullable -- a non-nullable
    // `Listener` is just as undeclarable -- so the NULLABLE bucket's "expose a non-nullable
    // wrapper" hint would send the author after a fix that cannot work. An undeclared inner
    // type wins over the position. Narrow on purpose: every other nullable Unsupported keeps
    // the shipped NULLABLE wording.
    type.isUndeclared() -> requireNotNull(type.skipReason())
    // ADR-061 (2026-09-16 amendment): `List<T>?` now has a return route, so a nullable collection
    // whose component is ineligible was refused for the *component's* reason, not for being
    // nullable. Attribute it there, the same way the non-nullable Collection arm below does; the
    // NULLABLE bucket's "expose a non-nullable wrapper" hint would send the author after a fix
    // that cannot work. A bridgeable-component nullable collection keeps NULLABLE, which is still
    // the honest answer at the input position it can only be skipped from.
    type is BridgeType.Collection && !type.isBridgeableComponent() ->
      requireNotNull(type.skipReason())
    else -> ForwardPlanSkipReason.NULLABLE
  }
  // ADR-066: a bridgeable-shaped Collection (List/MutableList result, Map/Set) that still
  // reaches here failed for its own reason (nothing else calls skipReason() on a bridgeable
  // Collection); an unsupported element/key/value attributes to that component's own reason
  // (e.g. UNEXPORTED_DEPENDENCY_TYPE) instead of the generic COLLECTION bucket, which
  // `toDiagnosticKind()` reserves for the genuinely input-position case.
  is BridgeType.Collection -> when {
    // ROADMAP Phase 4: the DECLINED bytes slots. Their component passes `isBridgeableComponent()`
    // on its own (a `ByteArray` binds as a `List` element), so the failing-component search below
    // would find nothing and fall back to whichever slot is first. Named here instead, so the
    // author reads "BYTE_ARRAY" and gets the identity-versus-copy hint.
    declinesByteArrayComponent() -> ForwardPlanSkipReason.BYTE_ARRAY
    // ADR-201: the same declined equality slots for a `Throwable`, named for the same reason.
    declinesThrowableComponent() -> ForwardPlanSkipReason.THROWABLE
    // ADR-083 amendment (boundary nullability part B): named here for the same reason BYTE_ARRAY
    // is. `Nullable(String)` is a perfectly good component on its own, so the failing-component
    // search below would find nothing and fall back to whichever slot is first, reporting NULLABLE
    // with a hint about non-nullable wrappers instead of naming the KEY.
    declinesNullableMapKey() -> ForwardPlanSkipReason.NULLABLE_MAP_KEY
    isBridgeableComponent() -> ForwardPlanSkipReason.COLLECTION
    // ROADMAP Phase 4: the component that actually FAILED, not whichever slot is listed first.
    // `Map<String, Sequence<Int>>` used to report `STRING` -- naming the one component that was
    // fine -- and sent the author after a fix that cannot work (measured 2026-09-20 on
    // `Map<String, ByteArray>`). The old `element ?: key ?: value` order stays as the fallback, for
    // a collection whose components were all dropped at classification.
    else -> listOfNotNull(element, key, value)
      .firstOrNull { component -> !component.isBridgeableComponent() }
      ?.skipReason()
      ?: (element ?: key ?: value)?.skipReason()
      ?: ForwardPlanSkipReason.UNSUPPORTED
  }

  // ADR-147 v1: only reached from a position a `T` cannot bind at (nested in a collection, a
  // lambda or a value class); a top-level `T` plans a shape and never asks. UNSUPPORTED, a named
  // drop: no legacy route carries a type parameter at such a position.
  is BridgeType.TypeParameter -> ForwardPlanSkipReason.UNSUPPORTED

  is BridgeType.RawCollection -> ForwardPlanSkipReason.COLLECTION
  is BridgeType.Enum -> ForwardPlanSkipReason.ENUM
  is BridgeType.ObjectHandle -> ForwardPlanSkipReason.HANDLE
  // Never actually reached by an ordinary interface result (shapeOrNull's Interface branch
  // always succeeds); only reachable defensively via a Collection-of-Interface element skip.
  is BridgeType.Interface -> ForwardPlanSkipReason.HANDLE
  // ADR-088: shapeOrNull's BoundInterface branch succeeds only for a manifest-flagged
  // Kotlin-implementable interface, so reaching here at a return position means exactly the
  // "no mint{Iface}Bridge" case. A collection element reaches here too, and takes the position
  // skip instead (collections of bound interfaces are deferred).
  is BridgeType.BoundInterface ->
    if (implementable) ForwardPlanSkipReason.BOUND_INTERFACE_POSITION
    else ForwardPlanSkipReason.UNIMPLEMENTABLE_BOUND_INTERFACE

  is BridgeType.ValueClass -> ForwardPlanSkipReason.VALUE_CLASS
  is BridgeType.SpecializedProtocol -> when {
    // ADR-065: StateFlow shares the plain-Flow legacy route (both are named legacy exports in
    // exports/ClassExports.kt + cir/CirFlowRenderer.kt); it is a distinct SpecializedProtocol
    // name only so the classifier never confuses it with plain Flow (ADR-065 detection order).
    name.startsWith("state flow ") -> ForwardPlanSkipReason.FLOW_PROTOCOL
    name.startsWith("flow ") -> ForwardPlanSkipReason.FLOW_PROTOCOL
    name.startsWith("suspend lambda ") -> ForwardPlanSkipReason.SUSPEND_CALLBACK_PROTOCOL
    name.startsWith("lambda ") || name.startsWith("interface bridge ") -> ForwardPlanSkipReason.CALLBACK_PROTOCOL
    name.startsWith(SEALED_HELPER_PREFIX) -> ForwardPlanSkipReason.SEALED_POSITION
    name.startsWith("generic declaration ") -> ForwardPlanSkipReason.GENERIC
    else -> error("Forward planner has no explicit legacy route for specialized protocol $name")
  }

  is BridgeType.RawKSType -> error("Forward planner received raw KSP type $rendered")
  // ADR-074: checked ahead of isUnexportedDependency -- an actual-typealias-target redirect can
  // land on either an out-of-scope module-local type or a cross-module one, and both must carry
  // this ADR's own diagnostic, not the generic UNEXPORTED_DEPENDENCY_TYPE include(...) hint.
  is BridgeType.Unsupported -> when {
    // ADR-115: checked first -- a marked type is refused for a reason no scope change and no
    // move-to-top-level can repair, so it must not pick up any of the hints below.
    optInMarker != null -> ForwardPlanSkipReason.OPT_IN_MARKER_TYPE
    isActualTypeAliasTarget -> ForwardPlanSkipReason.ACTUAL_TYPEALIAS_TARGET
    // The classifier sets exactly one of these two on an enum, and never both: a nested enum
    // (whichever module it lives in) is undeclarable rather than out of scope, so it must not
    // pick up the `include(...)` hint.
    isUndeclaredEnum -> ForwardPlanSkipReason.UNDECLARED_ENUM
    // Issue #54: the same "undeclarable, not out of scope" rule for a nested interface.
    isUndeclaredInterface -> ForwardPlanSkipReason.UNDECLARED_INTERFACE
    // ...and for a nested class or object.
    isUndeclaredClass -> ForwardPlanSkipReason.UNDECLARED_CLASS
    // ...and for a nested value class, whose record struct is declared by the same owner walk.
    isUndeclaredValueClass -> ForwardPlanSkipReason.UNDECLARED_VALUE_CLASS
    // ADR-133: an `object` is declared (as a C# static class) but unusable at a member position.
    isObjectPosition -> ForwardPlanSkipReason.OBJECT_POSITION
    // The closure records WHY it refused a dependency declaration; each refusal wants a
    // different remedy, and only NOT_INCLUDED (or an unrecorded refusal, e.g. a module-local
    // type the closure never saw) wants the `include(...)` one.
    isUnexportedDependency -> when (unexportedDependencyRefusal) {
      ForwardAdmissionRefusal.EXCLUDED_BY_CONFIG ->
        ForwardPlanSkipReason.EXCLUDED_DEPENDENCY_TYPE

      ForwardAdmissionRefusal.EXPECT_IN_DEPENDENCY ->
        ForwardPlanSkipReason.EXPECT_DEPENDENCY_TYPE

      ForwardAdmissionRefusal.CROSS_MODULE_ADMISSION_DISABLED ->
        ForwardPlanSkipReason.CROSS_MODULE_DISABLED_DEPENDENCY_TYPE

      // Defensive: the classifier tests nestedness ahead of the dependency route, so a nested
      // refusal should never reach here. If one ever does, it must not be told to widen scope.
      ForwardAdmissionRefusal.NESTED_DECLARATION -> ForwardPlanSkipReason.UNDECLARED_CLASS

      ForwardAdmissionRefusal.NOT_INCLUDED, null ->
        ForwardPlanSkipReason.UNEXPORTED_DEPENDENCY_TYPE
    }

    else -> ForwardPlanSkipReason.UNSUPPORTED
  }
}

/**
 * The detail slot for whichever of the extractors above applies, in the order the three callable
 * skip sites already chain them: the author's own opt-in marker first, then the `actual typealias`
 * redirect, then the dependency-scope name, then the undeclared type, then the sealed base.
 *
 * `collectionComponentDetail()` is deliberately *not* in the chain: it is keyed to
 * [ForwardPlanSkipReason.COLLECTION], and the property route has its own component wording
 * ("Collection (element type ...)") for that case.
 */
internal fun BridgeType.skipDetail(): String? = optInMarkerDetail()
  // ADR-083 amendment (boundary nullability part B): ahead of the generic arms so the property
  // route's NULLABLE_MAP_KEY sentence names the key slot, matching the callable route's.
  ?: nullableMapKeyDetail()
  ?: actualTypeAliasTargetDetail()
  ?: unexportedDependencyDetail()
  ?: undeclaredTypeDetail()
  ?: sealedTypeDetail()
  ?: unsupportedTypeDetail()

/** The rendered name of whatever the classifier refused, so [ForwardPlanSkipReason.UNSUPPORTED]'s
 *  sentence can name the type the author wrote instead of the reason constant (and, ADR-151, so
 *  its hint can recognise an unmapped `kotlin.*`/`kotlinx.*` type). Last in the chain: every
 *  flagged refusal above it (opt-in marker, typealias target, scope, nesting) is more specific and
 *  keeps its own wording. `null` for every type that is not [BridgeType.Unsupported], which is
 *  every type whose refusal is about a position rather than the type itself. */
internal fun BridgeType.unsupportedTypeDetail(): String? =
  (unwrapNullable() as? BridgeType.Unsupported)?.rendered

/**
 * ADR-164: the most defaulted parameters one callable widens. The Kotlin dispatch has one arm per
 * subset, so 8 is 256 arms (about 36 KB of dylib, measured); beyond it only the last 8 widen.
 */
internal const val MAX_OPTIONAL_DEFAULTS: Int = 8

/**
 * The suffixes the C# plan projection spells a parameter's wrapper locals with, off
 * [ForwardPublicParameter.localStem]. None is a suffix of another, so two stems' locals can only
 * meet if the stems do.
 */
internal val CSHARP_LOCAL_SUFFIXES: List<String> = listOf("Handle", "Owned", "Box", "Ctx", "Native")

/** ADR-164: the defaulted-parameter facts of one entry, aligned with its declared parameters. */
internal data class ForwardDeclaredDefaults(
  val flags: List<Boolean>,
  val kotlinNames: List<String>,
  /** ADR-115: index to marker detail of a constructor parameter whose PROPERTY is opt-in marked. */
  val marked: Map<Int, String> = emptyMap(),
)

/** ADR-164: the outcome of the widening pass. [marked] set means the entry is an OPT_IN_MARKER skip. */
internal data class ForwardWidening(
  val parameters: List<ForwardPublicParameter>,
  val capped: List<String> = emptyList(),
  val marked: String? = null,
  /** The ADR-164 dispatcher's bitmask local, minted beside [parameters]' derived names. */
  val dispatchMask: String = "mask",
  /** ADR-132 amendment: the value receiver, its has-value flag minted beside [parameters]'. */
  val receiver: ForwardPublicParameter? = null,
)

private enum class ForwardDefaultRole(val isWidened: Boolean) {
  REQUIRED(false),
  UNROUTABLE(false),
  NULLABLE(true),
  OPTIONAL(true),
  PRESENCE(true),
}

/**
 * ADR-160: the origins whose callable normally STORES a lambda argument past the call, so a
 * per-call GCHandle would be freed under it. ADR-164 reads the same set to keep a defaulted lambda
 * there dropped rather than widened.
 */
/** The origins whose C# member and C entry point are spelled from the declaration's own name. */
private val DECLARED_NAME_ORIGINS: Set<ForwardCallableOrigin> = setOf(
  ForwardCallableOrigin.CLASS,
  ForwardCallableOrigin.EXTENSION,
  ForwardCallableOrigin.TOP_LEVEL,
  ForwardCallableOrigin.OBJECT,
  ForwardCallableOrigin.COMPANION,
  ForwardCallableOrigin.ENUM_MEMBER,
  ForwardCallableOrigin.VALUE_CLASS,
)

private val STORED_CALLBACK_ORIGINS: Set<ForwardCallableOrigin> = setOf(
  ForwardCallableOrigin.CONSTRUCTOR,
  ForwardCallableOrigin.COPY,
  ForwardCallableOrigin.ENUM_ARM_BOX,
  ForwardCallableOrigin.VALUE_CLASS,
  ForwardCallableOrigin.VALUE_CLASS_BOX,
)

/** The declared result type of a planned function or property, for a return-skip diagnostic. */
private fun KSNode?.declaredResultType(): KSType? = when (this) {
  is KSFunctionDeclaration -> returnType?.resolve()
  is KSPropertyDeclaration -> type.resolve()
  else -> null
}

/** ADR-006 amendment: the functions the compiler writes on every `enum class`, none authored. */
internal val ENUM_SYNTHESIZED_FUNCTIONS: Set<String> = setOf("values", "valueOf")
