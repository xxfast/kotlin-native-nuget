package io.github.xxfast.kotlin.native.nuget.processor.forward

import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.FileLocation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSNode
import java.util.Collections

/**
 * ADR-064: the forward direction's named skip diagnostic, mirroring the reverse
 * `RirDiagnostic`/`RirDiagnosticKind` (`RirModel.kt`). Every forward "cannot express this"
 * decision — the plan catalog's genuine drops, the legacy-route reclassifications (cell 23), and
 * the CIR translators' scattered `logger.warn`/`logger.error` calls (variance, unsupported
 * property/function, constructor collision) — builds one of these and routes it through
 * [ForwardDiagnosticSink] rather than calling `KSPLogger` directly.
 *
 * Unlike [io.github.xxfast.kotlin.native.nuget.rir.RirDiagnostic] (reverse works from ECMA-335
 * metadata and has no source symbol), [symbol] carries the originating `KSNode` so the message can
 * point at the author's own Kotlin declaration rather than at generated code.
 */
internal data class ForwardDiagnostic(
  val kind: ForwardDiagnosticKind,
  val symbol: KSNode?,
  val declaration: String,
  val reason: String,
  val hint: String,
  /**
   * ADR-064 amendment (issue #249): the generated C# declaration this skip leaves a hole in, so
   * `CirFile.withSkipRemarks` can name the member on it as a `<remarks>` paragraph. Deliberately
   * carries no default: a producer states either an owner or `null` ("no C# declaration could carry
   * this"), because a defaulted owner is a producer nobody wired, which is the drift ADR-064 has
   * been amended ten times to close.
   *
   * Set by the producer, never derived from [declaration] (a display string spelled differently per
   * producer) and never from `symbol.parentDeclaration` (an inherited member walked through
   * `getAllFunctions()` reports the SUPERTYPE, while the C# hole is on the class being translated).
   */
  val owner: ForwardDiagnosticOwner?,
  /**
   * The Kotlin simple name of the member the hole is about (`weave`, never `Weave`, and never the
   * overload-suffixed catalog symbol). Null when [owner] is null, or when the skip is about the
   * owner itself rather than one of its members.
   */
  val member: String? = null,
  val signature: String = "",
)

/**
 * ADR-064 amendment (issue #249): where a skipped member's `<remarks>` paragraph belongs, in KOTLIN
 * spelling. The C# name is resolved once, in the post-pass, with the same namespace mapping the
 * translator uses: a producer has the Kotlin declaration in hand and nothing else, and the two
 * halves cannot drift while only one of them names C#.
 */
internal sealed interface ForwardDiagnosticOwner {

  /** The Kotlin package of the owning declaration, mapped to a C# namespace by the post-pass. */
  val packageName: String

  /**
   * A class, object, interface, value class, sealed base or sealed arm, as the chain of Kotlin
   * simple names from the outermost declaration inward (`["Gantry", "Rung"]`,
   * `["Scamper", "Dash"]`). Built from the KSP parent chain, so two nested `Entry` types under
   * different owners can never cross-attach.
   */
  data class Type(
    override val packageName: String,
    val path: List<String>,
  ) : ForwardDiagnosticOwner

  /**
   * ADR-007's file-named static class, keyed by the same (package, file stem) pair the translator
   * groups top-level declarations by. The `Kt` suffix is a C# rename the post-pass resolves.
   */
  data class FileClass(
    override val packageName: String,
    val fileStem: String,
  ) : ForwardDiagnosticOwner

  /**
   * ADR-075's partial skip: the member SURVIVES (a property whose setter alone was refused), so the
   * paragraph goes on the generated C# property rather than on its type. Naming it on the type
   * would report a member as absent that a consumer can call.
   */
  data class Property(
    /**
     * The declaration the property is rendered on: a [Type], or a [FileClass] for a top-level
     * property.
     */
    val container: ForwardDiagnosticOwner,
    /** The generated C# property name (`LastTumble`), which is what the post-pass matches on. */
    val publicName: String,
  ) : ForwardDiagnosticOwner {
    override val packageName: String get() = container.packageName
  }
}

/**
 * The owner of a member declared in [this], built from the KSP parent chain. Companion members are
 * folded onto the owning class (ADR-013 renders them as its statics), which is where their C# hole
 * is.
 */
internal fun KSClassDeclaration.forwardDiagnosticOwner(): ForwardDiagnosticOwner.Type {
  val path: MutableList<String> = mutableListOf()
  var current: KSDeclaration? = this
  while (current is KSClassDeclaration) {
    if (!current.isCompanionObject) path.add(0, current.simpleName.asString())
    current = current.parentDeclaration
  }
  return ForwardDiagnosticOwner.Type(packageName.asString(), path)
}

/** The ADR-007 file holder a top-level declaration would have been declared on. */
internal fun KSDeclaration.forwardFileClassOwner(): ForwardDiagnosticOwner.FileClass? {
  val stem: String = containingFile?.fileName?.removeSuffix(".kt") ?: return null
  return ForwardDiagnosticOwner.FileClass(packageName.asString(), stem)
}

internal enum class ForwardDiagnosticSeverity { WARNING, INFO, ERROR }

/** The name prefixes a [ForwardDiagnosticKind] derives its severity and verb from. */
private const val SKIPPED_PREFIX: String = "SKIPPED_"
private const val INFO_PREFIX: String = "INFO_"
private const val ERROR_PREFIX: String = "ERROR_"

/**
 * ADR-064's forward bridgeable-subset boundary, the mirror of `RirDiagnosticKind`. Severity is
 * carried both by the `SKIPPED_/INFO_/ERROR_` name prefix (so a build log reads like the reverse
 * direction) and by [severity] itself (so the sink never string-matches its own enum, matching
 * ADR-057's reverse precedent).
 *
 * The name prefix, not the severity, also decides the [verb] a diagnostic renders with, and the
 * two are checked against each other at class-init time. A `SKIPPED_*` kind is a WARNING that
 * reads "Skipping" and may not override the verb; an `INFO_*` kind reads "Note"; an `ERROR_*` kind
 * reads "Error". Anything else is a WARNING about something that still binds (ADR-109's duplicated
 * dependency type), so it has to say what it is warning about and must pass [declaredVerb]. That
 * is the invariant `ForwardDiagnosticKindTest` pins: before it, a non-skip WARNING that forgot its
 * verb silently rendered as "Skipping", which was a lie.
 */
internal enum class ForwardDiagnosticKind(
  val severity: ForwardDiagnosticSeverity,
  /** ADR-109: the verb for a kind whose name carries no `SKIPPED_/INFO_/ERROR_` prefix to derive
   *  one from. Null (and required to be null) for every prefixed kind. */
  private val declaredVerb: String? = null,
) {
  /** A classifier `Unsupported` type, or another supported-elsewhere type this position cannot
   *  express (`Char`, an enum, a handle, a value class, ...) at a position with no bridge. */
  SKIPPED_UNSUPPORTED_TYPE(ForwardDiagnosticSeverity.WARNING),

  /** A parameter (or extension receiver) whose type has no input wire: `Map`/`Set` (and mutable
   *  variants), for which no `CreateMap`/`CreateSet` helper exists (ROADMAP line 78), and, since
   *  issue #131, a nullable type with no input wire, which used to render as
   *  [SKIPPED_UNSUPPORTED_RETURN].
   *
   *  ADR-064 amendment (2026-09-20): "no input wire" is not the whole story at the RECEIVER. A
   *  has-value fan-out receiver (`Int?`, `Mood?`, `Instant?`, `Duration?`, a nullable value class
   *  over a primitive/enum underlying) HAS an input wire; it is a two-slot one, and a receiver is
   *  exactly one slot. That drop keeps this kind (the position is still the input one) and carries
   *  its own sentence and hint off [ForwardPlanSkipReason.RECEIVER_FAN_OUT]. */
  SKIPPED_UNSUPPORTED_INPUT(ForwardDiagnosticSeverity.WARNING),

  /** A *return* whose type has no return wire, e.g. a nullable one with nowhere to put the
   *  absence (ROADMAP line 79, ADR-061 deferred width). */
  SKIPPED_UNSUPPORTED_RETURN(ForwardDiagnosticSeverity.WARNING),

  /** A property whose classified type the property planner has no getter/setter shape for, or an
   *  extension property whose *receiver* type has no supported wire shape, so the whole property
   *  is absent from the generated C#. Completes the position naming alongside
   *  [SKIPPED_UNSUPPORTED_INPUT] (a parameter) and [SKIPPED_UNSUPPORTED_RETURN] (a return);
   *  a property used to be the one position that vanished with no diagnostic at all.
   *
   *  A property the legacy routes re-emit (lambda, suspend lambda, Flow, StateFlow) is
   *  unplannable on purpose and binds through `CirClassTranslator`'s adapters, so the *planner*
   *  never raises this for one: a warning there would be a false positive.
   *
   *  Issue #111 added the one case where a legacy route raises it itself: a lambda property with
   *  a type argument C# cannot name (`(CamId) -> Flow<Snapshot>`, an unexported type, a nested
   *  class). That route emits no member at all, so the member really is absent, and staying
   *  silent is what shipped `KotlinFunc<CamId, Flow>` and CS0246 to a consumer. */
  SKIPPED_UNSUPPORTED_PROPERTY(ForwardDiagnosticSeverity.WARNING),

  /** Cell 23 / BUG-010: a generic + `suspend` + `inline` + `reified` extension returning
   *  `Result<T>` — the *combination* has no working legacy route, even though `suspend` and
   *  `generic` each have one individually. */
  SKIPPED_UNSUPPORTED_COMBINATION(ForwardDiagnosticSeverity.WARNING),

  /** A value-class member a supertype declares: inherited, forwarded by interface delegation
   *  (e.g. `CharSequence by value`) or explicitly overridden — ADR-064's product-scope skip,
   *  ratified permanent by ADR-082, not a silently-bridged member. */
  SKIPPED_INHERITED_MEMBER(ForwardDiagnosticSeverity.WARNING),

  /** `out`/`in` variance on a class type parameter is dropped; the member still binds, so this is
   *  a note, not a skip. */
  INFO_DROPPED_VARIANCE(ForwardDiagnosticSeverity.INFO),

  /** ADR-034: two or more constructors render identical C# parameter types. Fatal: silently
   *  dropping one would change the API contract unpredictably. */
  ERROR_CSHARP_SIGNATURE_COLLISION(ForwardDiagnosticSeverity.ERROR),

  /** ADR-117 / issue #106: two or more Kotlin declarations derive the same C entry point (two
   *  `class Kitten` in different packages, a user `fun dispose()` beside the generated `Dispose`,
   *  two `suspend` overloads). Fatal, and the message names every owning declaration: the ABI
   *  contract's duplicate guards used to throw a raw `IllegalArgumentException` naming only the
   *  mangled symbol. Still fails safe — the round returns before `CNameExports.kt` is written. */
  ERROR_C_ENTRY_POINT_COLLISION(ForwardDiagnosticSeverity.ERROR),

  /** ADR-066: a reachable, structurally bridgeable declaration in a dependency module whose
   *  package the reachability closure did not admit — out of scope, not unsupported. Replaces
   *  the misleading `SKIPPED_UNSUPPORTED_TYPE` this case used to fall through to. */
  SKIPPED_UNEXPORTED_DEPENDENCY_TYPE(ForwardDiagnosticSeverity.WARNING),

  /** ADR-154 §6: the same condition under the opt-in `strictDependencyTypes = true`, where the
   *  author has asked to decide every dependency type once, by name. Fatal, so `process()` returns
   *  before `CNameExports.kt` is written and the build stops at the KSP round rather than shipping
   *  a C# surface with a hole in it.
   *
   *  Deliberately NOT keyed on this kind anywhere: the escalation is keyed on
   *  [ForwardPlanSkipReason.UNEXPORTED_DEPENDENCY_TYPE] /
   *  [ForwardPlanSkipReason.CROSS_MODULE_DISABLED_DEPENDENCY_TYPE], because the same refusal at a
   *  PROPERTY position reports under [SKIPPED_UNSUPPORTED_PROPERTY] (ADR-154, verified by spike),
   *  so a kind-keyed escalation would silently miss half the surface. An
   *  `exclude(...)`-caused skip is a different reason constant and stays a warning. */
  ERROR_UNEXPORTED_DEPENDENCY_TYPE(ForwardDiagnosticSeverity.ERROR),

  /** ADR-066: the closure's blast-radius manifest — emitted once per KSP run (not once per
   *  admitted type, which would be noise at scale), aggregating every dependency-module type the
   *  closure admitted into the export set. */
  INFO_EXPORTED_FROM_DEPENDENCY(ForwardDiagnosticSeverity.INFO),

  /** ADR-074: an `expect class` actualized by an `actual typealias` whose target is not in the
   *  forward export set (a platform-library type, a stdlib type, or an out-of-scope package).
   *  Distinct from SKIPPED_UNEXPORTED_DEPENDENCY_TYPE, whose `include(...)` hint is wrong here:
   *  a platform library can never be brought into scope. */
  SKIPPED_ACTUAL_TYPEALIAS_TARGET(ForwardDiagnosticSeverity.WARNING),

  /** ADR-088: a bound C# interface (an ADR-070 stub) at a position v1 does not marshal — nullable,
   *  property, collection component, receiver. Explicitly NOT `SKIPPED_UNSUPPORTED_TYPE`: the type
   *  is fully bridgeable at ordinary parameter/return positions, so the message names the position
   *  and the original C# type rather than blaming the type. */
  SKIPPED_BOUND_TYPE_POSITION(ForwardDiagnosticSeverity.WARNING),

  /** ROADMAP Phase 3 (issue #54): a sealed base at an INPUT position (a bare parameter, a nullable
   *  one, or a collection component), which the plan does not marshal. Explicitly NOT
   *  `SKIPPED_UNSUPPORTED_TYPE`, for ADR-088's reason: since ADR-105 the same type binds fine at a
   *  return or property position, so the message blames the position and names the sealed type. */
  SKIPPED_SEALED_POSITION(ForwardDiagnosticSeverity.WARNING),

  /** ADR-088: a bound C# interface at a RETURN position with no `mint{Iface}Bridge` (ADR-085
   *  inadmissible), so a Kotlin implementation of it cannot be handed back to C#. */
  SKIPPED_UNIMPLEMENTABLE_BOUND_INTERFACE(ForwardDiagnosticSeverity.WARNING),

  /** ADR-101 and its 2026-09-05 amendment: an exported class declares a supertype — an interface
   *  *or* its base class — that is not in the export set, so nothing is ever generated for that
   *  supertype and naming it in the base list would not compile (CS0246). It is dropped from the
   *  generated C# base list and the class still exports, but the two halves differ in what the
   *  drop costs, so they carry different messages:
   *
   *  - interface: nothing callable is lost at all; its *defaulted* members already bind on the
   *    class (`ForwardClassMembership.kt`).
   *  - base class: its public members are re-homed onto the subclass (bound with the subclass's
   *    own export prefix, no `override`), but C# loses the type and the inheritance relation —
   *    no `is`/`as` against the base and no shared base across sibling subclasses.
   *
   *  The `include(...)` advice is measured, not assumed (`Tier1UnexportedSupertypeSkipTest`): the
   *  ADR-066 closure has no `superTypes` edge, so admitting a *dependency* package cannot pull a
   *  supertype-only type in. A supertype declared in this module is a different case — scope
   *  admits same-round source declarations, and the base-class hint picks between the two on
   *  `containingFile`, so it states the one fix that works for that base instead of hedging
   *  across both (ADR-101's 2026-09-11 amendment). The interface hint stays flat: an interface is
   *  not worth exporting for members it does not carry. */
  SKIPPED_UNEXPORTED_SUPERTYPE(ForwardDiagnosticSeverity.WARNING),

  /** Issue #55: the module has public declarations, but the `include`/`exclude`/`rootPackage`
   *  scope (ADR-063) admits none of them, so no `Interop.cs` is generated at all. Without this
   *  the build stays green and the package still packs, with its whole C# surface silently
   *  missing: the trap a bare `include("kotlin")` walks into, since an explicit `include`
   *  replaces the `rootPackage` default rather than adding to it. Emitted once per KSP run,
   *  with no source location (the scope is build configuration, not a declaration). */
  SKIPPED_ALL_DECLARATIONS(ForwardDiagnosticSeverity.WARNING),

  /** ADR-064's 2026-09-07 amendment: a public `annotation class`. Annotations are metadata for
   *  the Kotlin compiler and reflection; there is no C# projection of one worth generating (a
   *  .NET attribute would never be applied to anything, since the Kotlin usages do not cross the
   *  bridge). It had no root bucket at all, so it used to vanish in silence rather than skip with
   *  a name. WARNING, not INFO, and no `verb` override: nothing binds, so it genuinely is
   *  skipped. Usages of the annotation on exported declarations are unaffected -- the forward
   *  pipeline reads no annotation but `kotlin.native.CName`. Top-level declarations only, like
   *  every other root bucket. */
  SKIPPED_ANNOTATION_CLASS(ForwardDiagnosticSeverity.WARNING),

  /** ADR-115: a declaration carrying a `@RequiresOptIn`-meta-annotated marker, or a member whose
   *  type carries one. C# has no way to honour a Kotlin opt-in requirement -- a C# consumer of the
   *  generated binding would see a plain public member with no signal at all -- so a marked
   *  declaration is not exported, at any `RequiresOptIn.Level`. WARNING, like every other
   *  `SKIPPED_*`: the drop is a behaviour change the author needs to see, and the message names
   *  the marker's fully-qualified name so it is findable.
   *
   *  One kind for both `OPT_IN_MARKER` (the declaration itself) and `OPT_IN_MARKER_TYPE` (its
   *  type), exactly as `UNEXPORTED_DEPENDENCY_TYPE` / `EXCLUDED_DEPENDENCY_TYPE` share one: the
   *  member is dropped for the same one reason, and only the hint differs. */
  SKIPPED_OPT_IN_MARKER(ForwardDiagnosticSeverity.WARNING),

  /** A public declaration nested inside an exported class-like declaration (a `class`, `object`,
   *  `interface` or `enum class`) that ADR-133/134 **defers**: `nestedDeclarationDeferral()`
   *  returns a reason for it, because the candidate is `inner`, generic or a nested sealed
   *  hierarchy, or some owner in its chain cannot carry a nested type (an `enum class`, generic,
   *  `inner` or `value class` owner, a companion, or a kind other than class/object/interface).
   *  Every other nested declaration IS declared, as `Owner.Nested`, so this warning fires only for
   *  a deferred shape; the message carries the deferral reason, so the author reads which rule
   *  defers this one rather than a blanket "nested types are not supported". Sealed subclasses
   *  (ADR-009, declared nested under their base) and companion objects (ADR-013, their owner's
   *  statics) are excluded: those ARE declared, by their own routes.
   *
   *  A nested name C# cannot carry for a different reason is a different kind: a name collision
   *  with its owner is `ERROR_CSHARP_SIGNATURE_COLLISION`.
   *
   *  The declaration-level twin of the member-level `UNDECLARED_CLASS`/`UNDECLARED_ENUM`/
   *  `UNDECLARED_INTERFACE` skips, and deliberately both: a deferred nested declaration nothing
   *  references would otherwise produce no output and no diagnostic whatsoever. */
  SKIPPED_NESTED_DECLARATION(ForwardDiagnosticSeverity.WARNING),

  /** ADR-112: a `sealed interface` whose hierarchy the ADR-009 sealed-class route cannot carry:
   *  type parameters, a subclass with a second superclass, or a sub-interface. ADR-125 adds the
   *  two refusals nesting used to buy implicitly (an `enum class` subclass, and a subclass
   *  implementing two sealed interfaces) and drops the one it no longer needs (a subclass declared
   *  beside the interface). An eligible one is declared as an abstract class with a `FromHandle`
   *  discriminator and binds at every position; an ineligible one stays on the interface route as
   *  a bare `I<Name>` that nothing exported can be typed with, so every position it appears at
   *  keeps skipping as [SKIPPED_SEALED_POSITION]. Named once at the declaration, with the
   *  disqualifying reason, because the position skips can only say "no discriminator" and never
   *  why there is none. */
  SKIPPED_INELIGIBLE_SEALED_INTERFACE(ForwardDiagnosticSeverity.WARNING),

  /** ADR-110: a top-level function whose PascalCase C# name is already held by a top-level
   *  property of the same file class (`val name` + `fun name()`, CS0102). camelCase used to keep
   *  the two apart, since Kotlin gives properties and functions separate namespaces and C# does
   *  not. The sibling shape, a function named like its own file class (CS0542), is not an error:
   *  [INFO_FILE_CLASS_RENAMED] renames the class instead.
   *
   *  Fatal rather than a skip, which is where this departs from ADR-110's Decision. Dropping just
   *  the function is not expressible: ADR-062's plan is projected into both halves and ADR-055's
   *  contract requires every planned export to appear in each, so a callable cannot be planned,
   *  exported from Kotlin, and then absent from the C# (`ForwardAbiContract`: "missing C#
   *  projection"). Failing the build names both declarations and asks for the same one-word fix
   *  the skip would have. */
  ERROR_CSHARP_NAME_COLLISION(ForwardDiagnosticSeverity.ERROR),

  /** ADR-110: a top-level function whose PascalCase C# name equals its file class's name
   *  (`fun beam()` in `Beam.kt`), which C# forbids as a member named like its enclosing type
   *  (CS0542). `fun beam()` in `Beam.kt` is ordinary Kotlin and must keep binding, so ADR-007's
   *  own remedy applies -- the same `Kt` suffix it already gives a file class whose name a type
   *  claims -- and the function is emitted unchanged on `BeamKt`.
   *
   *  A note, not a skip: nothing is dropped and no export moves (the static class name is a C#
   *  surface detail). It is reported because the C# call site the author expects, `Beam.Beam()`,
   *  is not the one generated. */
  INFO_FILE_CLASS_RENAMED(ForwardDiagnosticSeverity.INFO),

  /** ADR-109: an admitted dependency-module type whose package another forward publisher in the
   *  same Gradle build also exports. ADR-066 generates it into *this* module's package, as its own
   *  C# class over its own opaque handle, so a consumer referencing both NuGet packages sees two
   *  unrelated C# types for one Kotlin type, with no conversion between them.
   *
   *  Certain when the other publisher is the dependency module itself; a heuristic when it is a
   *  sibling publisher over a shared non-publishing dependency (whether that sibling's API
   *  *reaches* the type is known only to its own KSP run), which is why the message is written to
   *  be true for both.
   *
   *  Nothing is skipped and nothing in the generated output changes — the remedy is structural
   *  (exactly one publisher declares the type) — so the verb is overridden to say so. */
  WARNING_DUPLICATED_DEPENDENCY_TYPE(
    ForwardDiagnosticSeverity.WARNING,
    declaredVerb = "Duplicating",
  ),

  /** ROADMAP Phase 3: every public constructor of an exported class is skipped, so the generated
   *  C# type carries only its `internal Foo(IntPtr handle)` and C# can never construct one.
   *
   *  The type is kept, deliberately: `exportedTypes` and the `ObjectHandle` classifier admit a
   *  class by declaration, not by constructor outcome, and a Kotlin factory returning it (the
   *  shipped `Issue54Drawing` / `sleepingCats()` shape) hands C# a perfectly usable instance.
   *  Nothing changes in the output, so the verb says "Keeping" rather than claiming a skip.
   *
   *  Fires for every skip reason, including the `droppedFromCSharp = false` ones
   *  (`GENERIC`, `FLOW_PROTOCOL`, ...): that flag describes the *method* legacy routes and no
   *  legacy route re-emits a constructor, so a constructor skipped for one of those was silent in
   *  every channel. Not fired for an abstract class (uninstantiable by design) or for the ADR-040
   *  interface backing wrapper (`translateInterfaceBackingClass`, which is never handle-less by
   *  accident). */
  WARNING_NO_PUBLIC_CONSTRUCTOR(
    ForwardDiagnosticSeverity.WARNING,
    declaredVerb = "Keeping",
  ),

  /** ADR-162: a planner, emitter, projection or translator invariant that a legal public Kotlin
   *  declaration reached — a raw `error(...)`, `require(...)`, `check(...)` or `!!` that fired
   *  because some `when` did not learn about a new `BridgeType` variant or route. Before this kind
   *  such a failure aborted the whole KSP round at the first occurrence as one unlocated
   *  `e: [ksp] java.lang.IllegalStateException: ...` line, so an API surface with several such
   *  shapes surfaced them one build at a time, with nothing to grep for.
   *
   *  Fatal, deliberately, and at every site (see ADR-162's Q1): the failure is a generator bug, and
   *  quietly shipping a package with a hole in its C# surface because of one is worse than a build
   *  that stops. What the kind buys is *containment*, not tolerance: every offending declaration of
   *  the round is reported, with its own `file:line`, in one build, and the round still returns
   *  before `CNameExports.kt` is written. The hint names the `exclude(...)` line that unblocks the
   *  author while the bug is fixed upstream.
   *
   *  Deliberately NOT attributed per renderer site: a `CirRenderer` throw has no `KSNode` to point
   *  at without threading nodes through the whole CIR model (ADR-162 Deferred scope), so the
   *  whole-round guard reports one nodeless diagnostic of this kind for those. */
  ERROR_INTERNAL_GENERATOR_FAILURE(ForwardDiagnosticSeverity.ERROR),

  /** ADR-162 (ROADMAP line 236): enum parameters on a top-level function whose return shape is
   *  carried by a legacy route that hand-builds its native call and so never casts an enum
   *  parameter down to its ordinal (a nullable, lambda, `Flow`, collection or handle return).
   *  Emitting the bridge anyway would produce C# that does not compile.
   *
   *  The last fatal forward diagnostic to live outside this enum: it was a bare `logger.error` in
   *  `CirFunctionTranslator` with no `[nuget:KIND]` tag, so it was the one build failure from this
   *  processor a consumer could not grep for by kind. Kept fatal rather than downgraded to a
   *  `SKIPPED_*` (ADR-162 Q5 records the downgrade as a follow-up): the translator already returns
   *  `emptyList()`, so the behaviour is preserved and only the label changes here. */
  ERROR_UNSUPPORTED_ENUM_PARAMETER_ROUTE(ForwardDiagnosticSeverity.ERROR),
  ;

  /** The word [ForwardDiagnostic.format] opens the message with, derived from the name prefix so
   *  that a kind cannot claim to skip something it still generates. */
  val verb: String = declaredVerb ?: when {
    name.startsWith(SKIPPED_PREFIX) -> "Skipping"
    name.startsWith(INFO_PREFIX) -> "Note"
    name.startsWith(ERROR_PREFIX) -> "Error"
    else -> error(
      "$name has no $SKIPPED_PREFIX/$INFO_PREFIX/$ERROR_PREFIX prefix to derive a verb from, so " +
          "it must pass declaredVerb: a WARNING that is not a skip may not render as \"Skipping\""
    )
  }

  init {
    val expected: ForwardDiagnosticSeverity? = when {
      name.startsWith(SKIPPED_PREFIX) -> ForwardDiagnosticSeverity.WARNING
      name.startsWith(INFO_PREFIX) -> ForwardDiagnosticSeverity.INFO
      name.startsWith(ERROR_PREFIX) -> ForwardDiagnosticSeverity.ERROR
      else -> null
    }
    require(expected == null || severity == expected) {
      "$name is prefixed for $expected but declares $severity"
    }
    require(expected == null || declaredVerb == null) {
      "$name derives its verb from its name prefix and may not also declare one"
    }
  }
}

/**
 * ADR-064's message-format contract: the rendered line always embeds the kind's [Enum.name] (e.g.
 * `[nuget:SKIPPED_UNSUPPORTED_COMBINATION]`), in the reverse `formatDiagnostic()` house style
 * (`NugetGenerateBindingsTask.kt`'s `w: [nuget:{pkg}] {Skipping|Note}{location}: {reason}.
 * {hint}`), plus the `KSNode` source location reverse cannot carry.
 */
internal fun ForwardDiagnostic.format(): String {
  val location: String = if (signature.isBlank()) declaration else "$declaration($signature)"
  val at: String = (symbol?.location as? FileLocation)
    ?.let { location -> "\n    at ${location.filePath}:${location.lineNumber}" }
    ?: ""
  return "[nuget:${kind.name}] ${kind.verb} $location: $reason. $hint$at"
}

/**
 * ADR-064 "Where the decision lives": the one sink every forward diagnostic producer routes
 * through. `SKIPPED_*`/`INFO_*` warn and generation continues with the member absent (never an
 * `IntPtr`/`"0"` fallback); `ERROR_*` fails generation. Both severities carry the originating
 * `KSNode` so KSP/Gradle can render the message at the author's own Kotlin source.
 */
internal object ForwardDiagnosticSink {
  // ADR-100: every non-fatal diagnostic, in emission order, for `NugetDiagnostics.json`.
  //
  // Corrected 2026-09-21 (ADR-162, verified by spike on `:test-library:kspKotlinMingwX64`, Windows,
  // Gradle 9.1.0, `--console=plain`): these KSPLogger lines DO reach the console, as
  // `e:`/`w: [ksp] <path>:<line>: <message>`, and every failure of a round is printed, not only the
  // first. This comment used to claim they reach no console at all. What ADR-100 measured remains
  // true of the task-level gap it was written for: `packNuget` usually does not run the KSP task
  // (FROM-CACHE, then UP-TO-DATE), so on most builds nothing is emitted here to see and the file is
  // still what a consumer gets. Synchronized because KSP runs the processor on a Worker API thread
  // and two targets' rounds can share one daemon; the processor resets before each round.
  private val recorded: MutableList<ForwardDiagnosticRecord> =
    Collections.synchronizedList(mutableListOf())

  fun emit(diagnostics: List<ForwardDiagnostic>, logger: KSPLogger) {
    diagnostics.forEach { diagnostic ->
      val message: String = diagnostic.format()
      when (diagnostic.kind.severity) {
        // An ERROR_* already fails this KSP round before any output is written, so recording it
        // would produce a file nobody reads (see ADR-100 "Deferred: ERROR_* visibility").
        ForwardDiagnosticSeverity.ERROR -> logger.error(message, diagnostic.symbol)
        ForwardDiagnosticSeverity.WARNING,
        ForwardDiagnosticSeverity.INFO,
          -> {
          logger.warn(message, diagnostic.symbol)
          recorded += ForwardDiagnosticRecord(
            severity = diagnostic.kind.severity,
            kind = diagnostic.kind,
            declaration = diagnostic.declaration,
            message = message,
            // Issue #249: the raw parts, so the generated `<remarks>` can be built from the same
            // recorded list `NugetDiagnostics.json` is written from without ever shipping
            // [message], which embeds the author-facing hint and an absolute source path.
            owner = diagnostic.owner,
            member = diagnostic.member,
            reason = diagnostic.reason,
            // ADR-162 (ROADMAP line 58): the same location [format] already appended as a trailing
            // `at <path>:<line>` line, carried as its own two fields so the Gradle re-emitter can
            // lead its console line with the kotlinc/KSP `<path>:<line>: ` shape an IDE linkifies.
            // Additive, and `format()` is deliberately NOT reordered: KSP's own Gradle logger
            // already prefixes that location to every `logger.warn`/`logger.error` line, so a
            // leading location inside `format()` would print it twice on the KSP path.
            file = (diagnostic.symbol?.location as? FileLocation)?.filePath,
            line = (diagnostic.symbol?.location as? FileLocation)?.lineNumber,
          )
        }
      }
    }
  }

  /** Starts a fresh round; the object is a singleton in a long-lived Gradle daemon. */
  fun reset() {
    recorded.clear()
    // ADR-162: the containment guards dedupe on (declaration, failure) for the round, since one
    // declaration can fail in both halves; that memory is per round for the same daemon reason.
    ForwardInternalFailures.reset()
  }

  fun recorded(): List<ForwardDiagnosticRecord> = synchronized(recorded) { recorded.toList() }
}

/**
 * ADR-064 producer (1): the planner's [ForwardPlanSkipReason] → the named kind. Only reachable
 * for `droppedFromCSharp = true` reasons; a legacy-route deferral (`droppedFromCSharp = false`)
 * never reaches [ForwardCallablePlanCatalog.droppedCallables] and so never calls this.
 *
 * `COLLECTION` stays a fixed mapping per the ADR Decision table: it only currently arises from an
 * input-position skip (`Map`/`Set` method parameters; a `List`/`MutableList` element accepts them
 * and every other collection *return* already has a working shape).
 *
 * `NULLABLE` was fixed too, on the assumption that it was only asserted at the
 * nullable-Boolean-return site (ADR-061's deferred width). Issue #131 showed it is genuinely
 * ambiguous: a nullable parameter with no wire skipped for the same reason and rendered as
 * `SKIPPED_UNSUPPORTED_RETURN`, pointing the author at a return type that was fine. So it reads
 * [position], exactly as this comment used to prescribe. Every other reason ignores it.
 */
internal fun ForwardPlanSkipReason.toDiagnosticKind(
  position: ForwardSkipPosition = ForwardSkipPosition.RETURN,
  // ADR-064 amendment (2026-09-13): the skip is about the declaration's own type parameters
  // rather than about a type standing at [position]; see `Skipped.structural`.
  structural: Boolean = false,
): ForwardDiagnosticKind = when (this) {
  // ADR-064 amendment (2026-09-13): a legacy-route deferral no route re-emits. The kind is the
  // position's own, exactly as the amendment's decision table says: the author's remedy for a
  // Flow at a parameter ("take the values as a List") is not the remedy for a lambda at a return,
  // and neither is the remedy for `fun <T> f(x: T)` on a class, which is about the *declaration*
  // and so takes the combination kind ADR-116 gave the same absence on a sealed arm.
  ForwardPlanSkipReason.UNROUTED_POSITION -> when {
    structural -> ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_COMBINATION
    position == ForwardSkipPosition.INPUT -> ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_INPUT
    else -> ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_RETURN
  }

  ForwardPlanSkipReason.COLLECTION -> ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_INPUT
  // ADR-132: always an input-position skip by construction — the extension receiver, which the
  // planner treats as input zero — so it is fixed rather than reading [position].
  //
  // ADR-064 amendment (2026-09-20): the kind stays, and a dedicated `SKIPPED_UNSUPPORTED_RECEIVER`
  // was declined. The reason has exactly one remedy, which is this file's own rule for "same kind,
  // own sentence" (the `UNDECLARED_*` family), and a new kind would rename what a consumer reads
  // out of `NugetDiagnostics.json`. The extension-PROPERTY route reports the same reason under
  // `SKIPPED_UNSUPPORTED_PROPERTY` for the same ADR-064 rule: the kind names where the drop
  // happened.
  ForwardPlanSkipReason.RECEIVER_FAN_OUT -> ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_INPUT
  ForwardPlanSkipReason.NULLABLE ->
    if (position == ForwardSkipPosition.INPUT) ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_INPUT
    else ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_RETURN

  // ADR-116: a `suspend`/`Flow`/generic/callback member of a sealed subclass. The kind's
  // documented meaning — the combination has no working legacy route — is literally the case
  // here: no legacy route is keyed to a sealed subclass at all.
  ForwardPlanSkipReason.UNSUPPORTED_COMBINATION,
  ForwardPlanSkipReason.SEALED_SUBCLASS_UNROUTED,
  // ADR-116 amendment (2026-09-11): the base-declared twin, same kind for the same reason.
  ForwardPlanSkipReason.SEALED_BASE_UNROUTED,
    -> ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_COMBINATION

  // ADR-162: the one reason that maps to an ERROR_* kind by construction. It is not a "cannot
  // express this" decision at all; it is the generator failing on something it was meant to handle.
  ForwardPlanSkipReason.INTERNAL_FAILURE ->
    ForwardDiagnosticKind.ERROR_INTERNAL_GENERATOR_FAILURE

  ForwardPlanSkipReason.INHERITED_MEMBER -> ForwardDiagnosticKind.SKIPPED_INHERITED_MEMBER

  // One kind for all four dependency-scope refusals: the member is dropped for the same one
  // reason (its type is not in the export set) and ADR-109's remedy text is keyed off this kind,
  // so only the hint differs.
  ForwardPlanSkipReason.UNEXPORTED_DEPENDENCY_TYPE,
  ForwardPlanSkipReason.EXCLUDED_DEPENDENCY_TYPE,
  ForwardPlanSkipReason.EXPECT_DEPENDENCY_TYPE,
  ForwardPlanSkipReason.CROSS_MODULE_DISABLED_DEPENDENCY_TYPE,
    -> ForwardDiagnosticKind.SKIPPED_UNEXPORTED_DEPENDENCY_TYPE

  ForwardPlanSkipReason.ACTUAL_TYPEALIAS_TARGET ->
    ForwardDiagnosticKind.SKIPPED_ACTUAL_TYPEALIAS_TARGET

  // ADR-115: one kind for the declaration-marked and type-marked halves; only the hint differs.
  ForwardPlanSkipReason.OPT_IN_MARKER,
  ForwardPlanSkipReason.OPT_IN_MARKER_TYPE,
    -> ForwardDiagnosticKind.SKIPPED_OPT_IN_MARKER

  ForwardPlanSkipReason.BOUND_INTERFACE_POSITION ->
    ForwardDiagnosticKind.SKIPPED_BOUND_TYPE_POSITION

  ForwardPlanSkipReason.SEALED_POSITION -> ForwardDiagnosticKind.SKIPPED_SEALED_POSITION

  ForwardPlanSkipReason.UNIMPLEMENTABLE_BOUND_INTERFACE ->
    ForwardDiagnosticKind.SKIPPED_UNIMPLEMENTABLE_BOUND_INTERFACE

  ForwardPlanSkipReason.CHAR,
  ForwardPlanSkipReason.ENUM,
  ForwardPlanSkipReason.HANDLE,
  ForwardPlanSkipReason.INSTANT,
  ForwardPlanSkipReason.DURATION,
    // ADR-107: a genuine drop (v1 binds Throwable only at a property getter), so the same
    // "type combination is not supported" bucket the other ordinary types use.
  ForwardPlanSkipReason.THROWABLE,
    // ADR-106: defensive, like INSTANT/DURATION.
  ForwardPlanSkipReason.UUID,
    // ADR-151: a genuine drop with no legacy route (the deferred `List<ByteArray>` nesting), in
    // the same "type combination is not supported" bucket the other ordinary types use.
  ForwardPlanSkipReason.BYTE_ARRAY,
  ForwardPlanSkipReason.OBJECT,
  ForwardPlanSkipReason.STRING,
  ForwardPlanSkipReason.UNSUPPORTED,
    // An enum outside the exported set: a genuine drop, and deliberately not a new diagnostic
    // kind — the member is unsupported at every position for the same one reason, and only the
    // hint (which names the enum and the move-to-top-level fix) differs.
  ForwardPlanSkipReason.UNDECLARED_ENUM,
    // Issue #54: the nested-interface twin, folded into the same bucket for the same reason -- one
    // undeclarable type, unsupported at every position, distinguished only by its hint.
  ForwardPlanSkipReason.UNDECLARED_INTERFACE,
    // The nested class/object twin of the two above, in the same bucket for the same reason.
  ForwardPlanSkipReason.UNDECLARED_CLASS,
    // ADR-134: and the nested `value class` twin, whose record struct the same owner walk declares.
  ForwardPlanSkipReason.UNDECLARED_VALUE_CLASS,
    // ADR-133: the object-position drop shares the bucket -- one unsupported type at every
    // position, distinguished only by its sentence and hint.
  ForwardPlanSkipReason.OBJECT_POSITION,
  ForwardPlanSkipReason.VALUE_CLASS,
    -> ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_TYPE

  ForwardPlanSkipReason.ABSTRACT,
  ForwardPlanSkipReason.CALLBACK_PROTOCOL,
  ForwardPlanSkipReason.FLOW_PROTOCOL,
  ForwardPlanSkipReason.GENERIC,
  ForwardPlanSkipReason.SUSPEND,
  ForwardPlanSkipReason.SUSPEND_CALLBACK_PROTOCOL,
  ForwardPlanSkipReason.TYPE_PARAMETER,
    -> error(
    "Forward diagnostic translation received a legacy-route deferral ($this); these are " +
        "droppedFromCSharp = false and must never reach warnDroppedForwardCallables",
  )
}

/** `kotlin`, `kotlin.*` and `kotlinx.*`: packages an export scope can never usefully admit.
 *  ADR-151: shared with the classifier, which refuses an unmapped stdlib type as plainly
 *  unsupported rather than as an out-of-scope dependency. */
internal fun String.isStdlibPackage(): Boolean =
  this == "kotlin" || startsWith("kotlin.") || this == "kotlinx" || startsWith("kotlinx.")

/**
 * The fallback sentence every reason that is genuinely about an unsupported type combination
 * keeps. Named so [ownsSentence] can ask "did this reason say something of its own?" without an
 * allowlist that has to be extended every time [diagnosticReason] gains an arm.
 */
/** ADR-151: issue #55/#56's stdlib sentence, shared by the reason that owns it now
 *  ([ForwardPlanSkipReason.UNSUPPORTED]) and the dependency reason it moved off. */
private fun stdlibTypeHint(detail: String?): String =
  "${detail ?: "it"} is a Kotlin stdlib type with no first-class C# mapping yet; expose a " +
      "bridgeable type instead (include(...) is not the fix: an explicit include replaces " +
      "the export scope rather than mapping the type)"

/**
 * ADR-154 §6: under the opt-in `strictDependencyTypes = true`, a dependency-scope skip the author
 * can act on becomes an error; everything else keeps the kind it already had.
 *
 * The two escalated reasons are exactly the two refusals `admit(...)` (or `rootPackage`) repairs:
 * `NOT_INCLUDED` and `CROSS_MODULE_ADMISSION_DISABLED`. `EXCLUDED_DEPENDENCY_TYPE` is left alone on
 * purpose — the author already declared that omission deliberate, and escalating it would make
 * strict mode unsatisfiable for any dependency the build genuinely amputates.
 * `EXPECT_DEPENDENCY_TYPE` is left alone because no scope entry of this module can repair it at
 * all.
 *
 * Applied to the KIND rather than inside [toDiagnosticKind] so the property route (whose kind is
 * positional, `SKIPPED_UNSUPPORTED_PROPERTY`, and never derived from the reason) escalates through
 * the same one rule.
 */
internal fun ForwardDiagnosticKind.escalatedForStrictDependencyTypes(
  reason: ForwardPlanSkipReason?,
  strict: Boolean,
): ForwardDiagnosticKind = if (strict && reason in STRICT_DEPENDENCY_TYPE_REASONS) {
  ForwardDiagnosticKind.ERROR_UNEXPORTED_DEPENDENCY_TYPE
} else {
  this
}

private val STRICT_DEPENDENCY_TYPE_REASONS: Set<ForwardPlanSkipReason> = setOf(
  ForwardPlanSkipReason.UNEXPORTED_DEPENDENCY_TYPE,
  ForwardPlanSkipReason.CROSS_MODULE_DISABLED_DEPENDENCY_TYPE,
)

internal fun ForwardPlanSkipReason.genericSentence(): String =
  "its $name type combination is not supported"

/**
 * ADR-064's 2026-09-11 amendment: true when [diagnosticReason] says something better than
 * [genericSentence] for this reason and detail. The property route reads it to decide between the
 * reason's sentence and hint (scope, nesting, sealed, opt-in) and its own shipped "no property
 * getter or setter shape" pair, which stays for a reason that has nothing of its own to say (a
 * legacy-route deferral like [ForwardPlanSkipReason.GENERIC], whose generic sentence would name a
 * reason constant the author cannot act on).
 *
 * Detail-sensitive on purpose: [ForwardPlanSkipReason.EXCLUDED_DEPENDENCY_TYPE] and the
 * `UNDECLARED_*` arms read [detail], so the answer for the same reason can differ with it. The
 * `parameter` slot [diagnosticReason] takes is deliberately absent: only
 * [ForwardPlanSkipReason.NULLABLE] reads it, and only at an input position, which a property drop
 * never is.
 */
internal fun ForwardPlanSkipReason.ownsSentence(detail: String?): Boolean =
  diagnosticReason(detail) != genericSentence()

/**
 * ADR-064's 2026-09-10 amendment: the per-reason sentence, kept beside the hint it reads with.
 *
 * The six named arms are the drops that are not about an unsupported type combination, so the
 * generic sentence would be wrong on both counts: the author's own `exclude(...)`, their own
 * opt-in marker (on the declaration, and on a member's type), a position no route carries at all
 * (a reference-underlying value class constructor per ADR-035, a specialized member of a sealed
 * subclass per ADR-116), and a nullable *parameter*, where the generic sentence reads as being
 * about the whole callable and sent the author to the return type (issue #131). Every other drop
 * keeps the generic sentence, which names the reason constant.
 *
 * A legacy-route deferral needs no guard here: [toDiagnosticKind] is evaluated first in the same
 * [ForwardDiagnostic] construction and already fails on one.
 *
 * @param detail the same slot [diagnosticHint] documents; read here by the excluded and opt-in
 *   arms, which name the offending type, and by [ForwardPlanSkipReason.SEALED_SUBCLASS_UNROUTED],
 *   where it carries the member kind ("suspend", "Flow", ...).
 * @param parameter the same slot [diagnosticHint] documents; read here only by
 *   [ForwardPlanSkipReason.NULLABLE], and only when the offending input is a named parameter.
 */
internal fun ForwardPlanSkipReason.diagnosticReason(
  detail: String? = null,
  parameter: String? = null,
): String {
  val generic: String = genericSentence()
  return when (this) {
    // ADR-109's remedy, followed: out of scope by the author's own instruction, not unsupported,
    // which is what the hint below ("skipped by design") and this kind's KDoc already say. The
    // package and the remedy stay in the hint; this sentence only names the type.
    ForwardPlanSkipReason.EXCLUDED_DEPENDENCY_TYPE ->
      "its type `${detail ?: "in an excluded package"}` is excluded from the export scope by " +
          "your own exclude(...)"

    // ADR-162: the generator's fault, said plainly, with the exception class and message so the bug
    // is findable. Never the generic sentence, which would read as a claim about the author's types.
    ForwardPlanSkipReason.INTERNAL_FAILURE ->
      "the generator's own invariant failed while planning it " +
          "(${detail ?: "no failure detail was captured"})"

    // ADR-115: the author's own signal, named as such.
    ForwardPlanSkipReason.OPT_IN_MARKER ->
      "it is marked with the opt-in marker `${detail ?: "an opt-in marker"}`"

    ForwardPlanSkipReason.OPT_IN_MARKER_TYPE ->
      "its type `${detail?.substringBefore("->") ?: "its type"}` is marked with an opt-in marker"

    // ADR-116: nothing about this member's types is unsupported; the owner kind has no route.
    // ADR-064 amendment (2026-09-13): names the position, because the type itself IS bridgeable
    // somewhere else and the generic sentence ("its FLOW_PROTOCOL type combination is not
    // supported") would send the author looking for a type problem that does not exist.
    ForwardPlanSkipReason.UNROUTED_POSITION -> when (detail) {
      ForwardPlanSkipReason.FLOW_PROTOCOL.name ->
        "a Flow/StateFlow binds at a class-method return and a property, but not at this position"

      // ADR-160: a per-call lambda PARAMETER now binds off the ADR-062 plan at every ordinary
      // position (class method, sealed arm, object member, top-level function, extension), so the
      // shipped sentence ("not at this position") became false for it. What is left unrouted here
      // is a lambda *return* anywhere but a top-level function, and a lambda whose payload or own
      // return neither the plan nor the hand-written route carries.
      ForwardPlanSkipReason.CALLBACK_PROTOCOL.name ->
        "a lambda parameter binds at an ordinary position and a lambda return only at a " +
            "top-level function, so either this position or this lambda's own payload/return " +
            "has no route"

      ForwardPlanSkipReason.SUSPEND_CALLBACK_PROTOCOL.name ->
        "a `suspend` lambda is not bridged at any position"

      ForwardPlanSkipReason.GENERIC.name ->
        "a generic type binds at a top-level function return, and a generic function at a " +
            "top-level function with a parameter of its own type parameter, but not at this " +
            "position"

      else -> "no bridge route carries it at this position"
    }

    ForwardPlanSkipReason.SEALED_SUBCLASS_UNROUTED ->
      "it is a ${detail ?: "specialized"} member of a sealed subclass, which has no route yet " +
          "(ADR-116)"

    // ADR-116 amendment (2026-09-11): the same sentence for a member the sealed *base* declares.
    // Naming the owner kind matters here: the remedy below is to move it onto the arms.
    ForwardPlanSkipReason.SEALED_BASE_UNROUTED ->
      "it is a ${detail ?: "specialized"} member of a sealed base class, which has no route yet " +
          "(ADR-116)"

    // ADR-064's 2026-09-11 amendment: scope, position and nesting drops. None of these is about
    // an unsupported type combination, and each contradicted the hint printed beside it. The
    // reason constant is kept only in the three UNDECLARED_* sentences below, whose kind is the
    // shared SKIPPED_UNSUPPORTED_TYPE and so does not name the reason in the prefix.
    //
    // ADR-066: out of the export scope, not unsupported. The `include(...)` line stays in the hint.
    ForwardPlanSkipReason.UNEXPORTED_DEPENDENCY_TYPE ->
      "its type ${detail?.let { "`$it` " } ?: ""}is declared in a dependency module outside the " +
          "export scope"

    ForwardPlanSkipReason.EXPECT_DEPENDENCY_TYPE ->
      "its type ${detail?.let { "`$it` " } ?: ""}is an `expect` declaration in a dependency " +
          "module, which no export scope of this module can reach"

    // ADR-066 admission rule 4: neither rootPackage nor include is set.
    ForwardPlanSkipReason.CROSS_MODULE_DISABLED_DEPENDENCY_TYPE ->
      "its type ${detail?.let { "`$it` " } ?: ""}is declared in a dependency module and " +
          "cross-module export is off"

    // ADR-074: `detail` is `"<expect>-><target>"`, split exactly as the hint below splits it.
    ForwardPlanSkipReason.ACTUAL_TYPEALIAS_TARGET -> {
      val parts: List<String>? = detail?.split("->", limit = 2)?.takeIf { it.size == 2 }
      "its type `${parts?.get(0) ?: "the expect type"}` is an `actual typealias` to " +
          "`${parts?.get(1) ?: "an unexported target"}`, which is not exported"
    }

    // The four that keep the reason constant: they share one diagnostic kind, so the prefix does
    // not distinguish them and the sentence has to.
    ForwardPlanSkipReason.UNDECLARED_ENUM ->
      "its enum type `${detail ?: "the enum"}` is never declared as a C# enum ($name)"

    ForwardPlanSkipReason.UNDECLARED_INTERFACE ->
      "its interface type `${detail ?: "the interface"}` is nested and no C# nested interface is " +
          "declared for it ($name)"

    ForwardPlanSkipReason.UNDECLARED_CLASS ->
      "its type `${detail ?: "the class"}` is nested and no C# nested type is declared for it " +
          "($name)"

    ForwardPlanSkipReason.UNDECLARED_VALUE_CLASS ->
      "its value class type `${detail ?: "the value class"}` is nested and no C# nested record " +
          "struct is declared for it ($name)"

    // ADR-133: the object-at-a-member-position drop. Owns its sentence so the author reads the C#
    // rule (a static type has no parameter or return position) rather than the generic combination.
    ForwardPlanSkipReason.OBJECT_POSITION ->
      "its type `${detail ?: "the object"}` is a Kotlin `object`, declared in C# as a static " +
          "class, which cannot appear at a parameter or return position (CS0722)"

    // ADR-082: nothing about the types failed; a supertype declares this signature.
    ForwardPlanSkipReason.INHERITED_MEMBER ->
      "it is a value class member that a supertype declares"

    // ADR-112 left one shape here: a sealed type with no generated discriminator, which is what
    // the hint below says too.
    ForwardPlanSkipReason.SEALED_POSITION ->
      "its sealed type ${detail?.let { "`$it` " } ?: ""}has no generated C# discriminator"

    // ADR-088: both are about the position, not the type. A bound C# interface is bridgeable,
    // just not here (nullable, property, collection component, receiver), and an
    // unimplementable one has no mint bridge to return through.
    ForwardPlanSkipReason.BOUND_INTERFACE_POSITION ->
      "a bound C# interface is not marshalled at this position"

    ForwardPlanSkipReason.UNIMPLEMENTABLE_BOUND_INTERFACE ->
      "it returns a bound C# interface that Kotlin cannot implement"

    // The type is what failed, so the sentence names it. Without this arm the author read
    // "its UNSUPPORTED type combination is not supported", which names a reason constant and no
    // type at all, while the same drop on a PROPERTY named the type. Both routes read this one
    // sentence now ([ownsSentence] claims the property route as soon as a detail is there); a type
    // whose refusal is about the position rather than the type carries no detail and keeps the
    // generic sentence.
    ForwardPlanSkipReason.UNSUPPORTED ->
      if (detail != null) "its type `$detail` is not supported" else generic

    // ADR-064 amendment (2026-09-20) / ADR-132: the receiver SHAPE is what failed, not the type.
    // `Int?` is bridgeable everywhere else, so the generic sentence ("its RECEIVER_FAN_OUT type
    // combination is not supported") named a reason constant, never named the receiver, and read
    // as a claim about the type. Keeps the `(RECEIVER_FAN_OUT)` tag for the same reason the
    // `UNDECLARED_*` sentences do: the kind is shared with other producers and the docs tell
    // readers to search for that string. Both routes read this one sentence (the extension
    // PROPERTY planner records the same reason), so they cannot drift.
    ForwardPlanSkipReason.RECEIVER_FAN_OUT ->
      "its extension receiver ${detail?.let { "`$it`" } ?: "type"} crosses the bridge as a " +
          "has-value flag plus a value (two slots), and an extension receiver can carry only " +
          "one ($name)"

    // Issue #131: guarded on the name being there, so a return-position nullable keeps the
    // shipped generic sentence.
    ForwardPlanSkipReason.NULLABLE ->
      if (parameter != null) "its parameter `$parameter` has a nullable type with no supported wire"
      else generic

    else -> generic
  }
}

/**
 * ADR-066 amendment: the package an `include(...)` hint has to name, from a qualified name that
 * may be a NESTED one. `dep.edge.Ledger.Entry` is in package `dep.edge`, not `dep.edge.Ledger`,
 * and the closure admits an owner reached only through its nested type since that amendment, so
 * this name now reaches the hint for real rather than only in theory. A bare
 * `substringBeforeLast('.')` produced an `include("dep.edge.Ledger")` line matching no package at
 * all — a remedy that silently changes nothing.
 *
 * Convention-based on purpose: by the time a hint is built, the only thing in hand is the rendered
 * qualified name ([ForwardCallableCatalogEntry.Skipped] carries no declaration and no package
 * slot), so the type segments are identified the one way a string allows — Kotlin type names are
 * capitalised and package segments are not. The `substringBeforeLast` fallback keeps a lowercase
 * type name (`dep.edge.entry`) on the shipped behaviour, and every non-nested capitalised name
 * resolves identically to the old expression.
 */
private fun String.dependencyPackageName(): String {
  val segments: List<String> = split('.')
  val packageSegments: List<String> =
    segments.dropLastWhile { segment -> segment.firstOrNull()?.isUpperCase() == true }
  return if (packageSegments.isEmpty() || packageSegments.size == segments.size) {
    substringBeforeLast('.', this)
  } else {
    packageSegments.joinToString(".")
  }
}

/**
 * ADR-154: the name an `admit(...)` entry has to carry for this refused type — the package
 * segments plus the OUTERMOST type segment, so a nested refusal names its owner.
 *
 * Load-bearing, not cosmetic. `admit("dep.edge.Ledger.Entry")` does **not** repair a nested
 * refusal: the closure admits `Entry`, then climbs to its owner (`ForwardReachabilityClosure`'s
 * ADR-066 amendment, edge A), asks the same matcher about `dep.edge.Ledger` — for which the
 * longer entry is not a prefix, so `isUnderPackage` is false both ways — refuses the owner
 * `NOT_INCLUDED`, and propagates that refusal straight back onto `Entry`. The author pastes the
 * line and reads the identical warning. `admit("dep.edge.Ledger")` admits the owner and, through
 * the same `startsWith("dep.edge.Ledger.")` clause, the nested type with it, which is exactly
 * ADR-154 §3's "lets ADR-133's owner walk declare its nested types".
 *
 * `exclude(...)` is the opposite and keeps the full name: the closure tests `isExcluded` on the
 * declaration itself, before the owner climb, so a nested exclude entry does bite.
 *
 * Same capitalised-segment convention as [dependencyPackageName] and for the same reason: by the
 * time a hint is built, a rendered qualified name is all there is.
 */
private fun String.dependencyAdmitName(): String {
  val segments: List<String> = split('.')
  val firstTypeIndex: Int =
    segments.indexOfFirst { segment -> segment.firstOrNull()?.isUpperCase() == true }
  return if (firstTypeIndex < 0) this else segments.take(firstTypeIndex + 1).joinToString(".")
}

/**
 * ADR-064: an actionable per-reason hint, kept alongside the mapping above it documents.
 *
 * @param detail ADR-066: the unexported dependency type's qualified name
 *   ([ForwardCallableCatalogEntry.Skipped.detail]), used only by [ForwardPlanSkipReason
 *   .UNEXPORTED_DEPENDENCY_TYPE] to name the exact `include(...)` fix. ADR-074: for
 *   [ForwardPlanSkipReason.ACTUAL_TYPEALIAS_TARGET], the same slot instead carries
 *   `"<expect qualified name>-><target rendered name>"`. For [ForwardPlanSkipReason.COLLECTION] it
 *   carries the offending component ("element type Collection?", "key type String?"). For
 *   [ForwardPlanSkipReason.UNDECLARED_ENUM] and
 *   [ForwardPlanSkipReason.UNDECLARED_INTERFACE] and [ForwardPlanSkipReason.UNDECLARED_VALUE_CLASS]
 *   it carries the undeclared type's qualified name,
 *   including when the enum is a collection component (the only extractor that descends into one).
 *   ADR-064 amendment (2026-09-20): for [ForwardPlanSkipReason.RECEIVER_FAN_OUT] it carries the
 *   RENDERED extension receiver type (`Int?`, `Mood?`, `Dosage?`), which the hint also reads the
 *   non-null spelling off (`removeSuffix("?")`); an extension symbol does not name its receiver,
 *   so without it the message cannot say which declaration position failed.
 *   Ignored by every other reason.
 * ADR-154 removed the `scope` parameter (ADR-063's `include(...)` packages): the un-admitted hint
 * is now the additive `admit("<qualified type>")`, which by construction does not need the author's
 * whole export scope echoed back at it. Nothing else ever read it.
 * @param parameter issue #131: the offending parameter's name, when the skip is at an input
 *   position and the input is a named parameter rather than an extension receiver. Read only by
 *   [ForwardPlanSkipReason.NULLABLE], whose shipped sentence could not say which position failed.
 */
internal fun ForwardPlanSkipReason.diagnosticHint(
  detail: String? = null,
  parameter: String? = null,
  /** ROADMAP line 37: the author's own `exclude(...)` entries, so
   *  [ForwardPlanSkipReason.EXCLUDED_DEPENDENCY_TYPE] can quote the entry that matched instead of
   *  a package derived from the type name. Empty keeps the derived-package wording. */
  excludeEntries: List<String> = emptyList(),
): String = when (this) {
  // ADR-162: the author did nothing wrong, so the hint says so and names the one line that unblocks
  // their build while the bug is fixed upstream. The declaration name is not in hand here (the hint
  // takes only the detail slots), so the `exclude(...)` line is spelled generically; the
  // [guarded]-reported half of the same kind, which does have the name, spells it out.
  ForwardPlanSkipReason.INTERNAL_FAILURE ->
    "this is a bug in the bridge generator, not a mistake in your Kotlin: add an exclude(...) " +
        "entry for this declaration to nuget { publish { } } to unblock this build, and report the " +
        "failure with this whole message"

  // ADR-151: an unmapped stdlib type no longer reaches here at all (the classifier refuses it as
  // plainly unsupported), so this arm is about a real dependency module. The stdlib sentence moved
  // to [ForwardPlanSkipReason.UNSUPPORTED] below; the guard stays because `refusedDependencyTypes`
  // can still name a stdlib type the closure saw.
  ForwardPlanSkipReason.UNEXPORTED_DEPENDENCY_TYPE -> {
    val dependencyPackage: String = detail?.dependencyPackageName()
      ?: "the dependency's package"
    if (dependencyPackage.isStdlibPackage()) {
      stdlibTypeHint(detail)
    } else if (detail == null) {
      // ADR-154: no type name in hand (the classifier flagged the position, not a named type), so
      // neither verb can be spelled with an argument. Never the old literal placeholder, which
      // rendered as `include("...", "the dependency's package")` — a line matching no package at
      // all (research spike 1b, the `List<LogLevel>` case, now fixed at its source in
      // `unexportedDependencyDetail`).
      "admit the dependency type by qualified name with admit(\"<qualified type>\") in " +
          "nuget { publish { } }, or expose a type from an in-scope package instead"
    } else {
      // ADR-154 §5: the hint is the ADDITIVE verb. `include(...)` is deliberately absent: an
      // explicit include replaces the `rootPackage` default (#55/#60), so the old hint had to
      // spell the author's whole export scope back at them and one mistyped entry emptied it.
      // `admit` adds one entry, admits that one declaration (never its package, ADR-154 §3), and
      // cannot change which of the module's OWN files are exported.
      // The admit argument is the OUTERMOST type ([dependencyAdmitName]); the exclude argument
      // stays the full name, because exclude is tested on the declaration itself.
      "add admit(\"${detail.dependencyAdmitName()}\") to nuget { publish { } } to export it " +
          "(admit is additive and dependency-only; it takes a package prefix such as " +
          "\"$dependencyPackage\" too), or exclude(\"$detail\") to record the omission as " +
          "deliberate"
    }
  }

  // ADR-151: an unmapped `kotlin.*`/`kotlinx.*` type is unsupported, not out of scope. Issue
  // #55/#56's sentence, moved here with it: `include("kotlin")` was the old hint, and following
  // it replaced the export scope with one nothing in the module lives under. A stdlib type wants
  // a first-class mapping (ADR-076 `Instant`, ADR-103 `Duration`, ADR-151 `ByteArray`).
  ForwardPlanSkipReason.UNSUPPORTED ->
    if (detail != null && detail.isStdlibPackage()) stdlibTypeHint(detail) else genericSkipHint

  // Following ADR-109's `exclude("<pkg>")` remedy lands every callable reaching the excluded type
  // here. `include(...)` is not the fix: `PackageScope.covers` tests `exclude` first, so an
  // include can never override one.
  //
  // [dependencyPackageName] rather than a bare `substringBeforeLast('.')`, matching the two hints
  // beside it: a package-level `exclude("dep.models")` propagates onto the nested
  // `dep.models.Broadcast.AdBand` through its owner, and the old spelling quoted
  // `exclude("dep.models.Broadcast")`, an entry the author never wrote. The shape this spelling
  // gets wrong is the reverse one: a TYPE-level `exclude("dep.models.Broadcast")` (issue #53),
  // where the owner really was the entry and the hint now points a segment too high. The hint
  // cannot tell them apart, because [ForwardCallableCatalogEntry.Skipped] carries only the
  // rendered type name and not the exclude entry that matched it; carrying that entry through
  // `detail` is the real fix.
  ForwardPlanSkipReason.EXCLUDED_DEPENDENCY_TYPE -> {
    // ROADMAP line 37 / issue #53: the entry the author actually wrote, matched with the same
    // `isUnderPackage` rule (and the same first-match order) the closure refused the type on. The
    // shipped hint derived a PACKAGE from the type name, so a type-level `exclude("dep.Broadcast")`
    // was quoted back as `exclude("dep")` and a nested `dep.Broadcast.AdBand` as
    // `exclude("dep.Broadcast")` — in both cases an entry that appears nowhere in the build file.
    // Falls back to the derived package only when no entry matched (a refusal recorded by some
    // other route), which is the shipped wording.
    val matched: String? = detail
      ?.let { type -> excludeEntries.matchesDeclaration(type.dependencyPackageName(), type) }
    val excluded: String = matched ?: detail?.dependencyPackageName() ?: "its package"
    val subject: String = when {
      matched == null -> "\"$excluded\""
      matched == detail -> "`$matched`, excluded by name,"
      else -> "\"$matched\", the package it is in,"
    }
    "$subject is excluded by exclude(\"$excluded\") in nuget { publish { } }, so a callable " +
        "reaching ${detail ?: "it"} is skipped by design; remove that exclude entry to export it " +
        "here (neither include(...) nor admit(...) can override an exclude)"
  }

  ForwardPlanSkipReason.EXPECT_DEPENDENCY_TYPE ->
    "${detail ?: "it"} is an `expect` declaration in a dependency module; its actualization " +
        "lives in that module and cannot be brought into scope with include(...); expose a " +
        "type you declare instead"

  ForwardPlanSkipReason.CROSS_MODULE_DISABLED_DEPENDENCY_TYPE ->
    // ADR-154 §2: `admit(...)` now opens admission rule 4's gate on its own, so the remedy is one
    // additive entry rather than an `include(...)` line that also replaces the
    // everything-in-this-module default and silently drops the author's own files. The old
    // `"the dependency's package"` fallback is gone with it: with no type name in hand, the line
    // names the verb and nothing it cannot spell.
    "no rootPackage, include or admit entry is set, so nuget { publish { } } never crosses the " +
        "module boundary and ${detail ?: "the type"} stays out of the export set; add " +
        "admit(${detail?.let { "\"${it.dependencyAdmitName()}\"" } ?: "\"<qualified type>\""}) " +
        "to nuget { publish { } } (additive and dependency-only), or set rootPackage"

  ForwardPlanSkipReason.ACTUAL_TYPEALIAS_TARGET -> {
    val parts: List<String>? = detail?.split("->", limit = 2)?.takeIf { it.size == 2 }
    val expectName: String = parts?.get(0) ?: "the expect type"
    val targetName: String = parts?.get(1) ?: "its actual typealias target"
    "the `actual typealias` for `$expectName` resolves to `$targetName`, which the forward " +
        "direction does not export; wrap it in a class you declare and expose that instead"
  }

  // The outer collection kind is never what failed here: ADR-073 admitted `Map`/`Set` inputs and
  // ADR-097 collapsed `List` into the same rule, so a `COLLECTION` skip always means one
  // *component* (element, key or value) has no wire. The pre-ADR-097 text told the author to
  // "use a List instead of a Map/Set", which is unactionable advice for a `List` parameter that
  // already is one, and names the outer container rather than the offending component. `detail`
  // carries that component in the property setter diagnostic's wording; without it (a raw
  // collection, or a result-position collection) the sentence stays true, just unnamed.
  ForwardPlanSkipReason.COLLECTION -> {
    val component: String = detail ?: "component type"
    "the $component cannot be written into a Kotlin collection; use components that are " +
        "primitives, Char, String, enums, exported class handles, value classes over those, or " +
        "non-null nested collections of the same"
  }

  // Deliberately does not name a type: this reason fires at both an input and a return position,
  // for any nullable spelling with no wire, and the slot carries no detail. It used to say
  // "a nullable Boolean return", which was wrong for every type that is not a Boolean, and every
  // nullable Boolean return binds since ADR-069.
  //
  // Issue #131: it can name the offending *parameter* though, which is what the reader needs to
  // find the type in their own source. Without a name (a return, or an extension receiver) the
  // shipped sentence is unchanged.
  ForwardPlanSkipReason.NULLABLE ->
    if (parameter != null) {
      "the nullable parameter `$parameter` has no wire at an input position; expose a " +
          "non-nullable wrapper, or a separate has-value/value pair, instead"
    } else {
      "expose a non-nullable wrapper, or a separate has-value/value pair, instead of a nullable " +
          "value at this position"
    }

  ForwardPlanSkipReason.UNSUPPORTED_COMBINATION ->
    "expose a non-inline, non-generic wrapper (e.g. a concrete suspend fun returning the " +
        "unwrapped value) and export that instead"

  // ADR-116: `else` below would send the author after unsupported parameter/return shapes, which
  // is wrong here — the shapes are fine, the *route* is missing for this owner kind.
  // ADR-116 amendment (2026-09-11): "non-generic" was the whole remedy while GENERIC was the only
  // kind left under this reason. It is not: an add/remove callback pair and a suspend lambda
  // parameter reach it too, and neither is generic, so telling that author to drop a type
  // parameter names nothing they wrote.
  // ADR-064 amendment (2026-09-13): the remedy is always "move it to a position that does bind",
  // and the positions that bind differ per member kind, so each detail names its own.
  ForwardPlanSkipReason.UNROUTED_POSITION -> when (detail) {
    ForwardPlanSkipReason.FLOW_PROTOCOL.name ->
      "return the Flow from a method on an ordinary class (or expose it as a property); a Flow " +
          "cannot be passed in, and no object, interface, extension or constructor route carries " +
          "one"

    ForwardPlanSkipReason.CALLBACK_PROTOCOL.name ->
      "take the lambda as a parameter of an ordinary class method (a per-call one, or an " +
          "add/remove pair), or return it from a top-level function"

    ForwardPlanSkipReason.SUSPEND_CALLBACK_PROTOCOL.name ->
      "take a plain (non-suspend) lambda parameter on an ordinary class method instead"

    ForwardPlanSkipReason.GENERIC.name ->
      "expose a non-generic wrapper (`fun f(value: Int)` beside `fun <T> f(value: T)`), or move " +
          "the declaration to a top-level function with a parameter of its own type parameter"

    else -> "expose an equivalent member at a position the bridge carries"
  }

  ForwardPlanSkipReason.SEALED_SUBCLASS_UNROUTED ->
    "move the member onto an ordinary class (which still has the legacy route this member kind " +
        "needs), or expose an equivalent member on the sealed subclass in a shape the arm's " +
        "routes do carry (a plain, per-call, non-generic one)"

  // ADR-116 amendment (2026-09-11): a sealed base has one remedy an arm does not -- the arms
  // themselves, which do carry the suspend and flow routes (ADR-118/ADR-124).
  ForwardPlanSkipReason.SEALED_BASE_UNROUTED ->
    "declare the member on each arm of the sealed class instead (the arms carry the suspend and " +
        "Flow routes the base does not), or move it onto an ordinary class"

  // Issue #57: the old hint ("declare the member directly on the value class") was already true
  // of an explicit `override`, which skips by the same rule (ADR-082: an override *is* the
  // inherited signature). The escape hatch ADR-082 actually names is a non-colliding signature.
  ForwardPlanSkipReason.INHERITED_MEMBER ->
    "a value class never exports a member a supertype declares, whether inherited, delegated " +
        "(`by`) or explicitly overridden (ADR-082); call the supertype's API through the " +
        "struct's underlying property from C#, or declare a member under a name or signature " +
        "no supertype declares"

  ForwardPlanSkipReason.BOUND_INTERFACE_POSITION ->
    "ADR-088 v1 marshals a bound C# interface at ordinary, non-nullable function/method/" +
        "constructor parameters and method/function returns only; expose one of those instead of " +
        "a nullable, property or collection-component position"

  // Names the sealed type, because the reason line cannot. ADR-105 scope (d) closed the position
  // half of this reason and ADR-112 narrowed it again: what is left is a sealed type with no
  // generated ADR-009 discriminator (an INELIGIBLE sealed interface, or a sealed type outside the
  // export scope), which C# has no way to reconstruct. `SKIPPED_INELIGIBLE_SEALED_INTERFACE` says
  // why an ineligible one has none.
  ForwardPlanSkipReason.SEALED_POSITION -> {
    val sealedName: String = detail ?: "the sealed type"
    "sealed type `$sealedName` has no generated discriminator, so C# cannot reconstruct it: only " +
        "an eligible sealed type inside the export scope gets one (ADR-009, ADR-112), and that " +
        "binds at every position (ADR-105); export it from an included package, make every " +
        "subclass a class or object (in the sealed type or beside it) with no other superclass " +
        "and no second sealed interface (ADR-125), or accept a concrete subclass"
  }

  // Names the enum, because the reason line cannot: `warnDroppedForwardCallables` builds it from
  // the reason's own name. Worded to stay true for both shapes the flag covers — a nested enum in
  // either module, and a module-local top-level enum outside the export scope — since `detail`
  // carries only the qualified name and cannot tell them apart.
  ForwardPlanSkipReason.UNDECLARED_ENUM -> {
    val enumName: String = detail ?: "the enum"
    "enum `$enumName` is not in the export set, so it is never declared as a C# enum and every " +
        "member typed with it is skipped rather than emitted as a dangling reference; if it is " +
        "nested, the SKIPPED_NESTED_DECLARATION warning on the declaration itself names which " +
        "shape rule defers it (an `enum class`, generic or `inner` owner, for instance), so " +
        "move it to the top level of its file, or, if it already is top level, bring its package " +
        "into the export scope"
  }

  // Names the interface, for the reason above, and says nested explicitly: unlike the enum flag
  // this one covers exactly one shape, so the hint does not have to hedge about export scope.
  // Mirrors the UNDECLARED_CLASS hint below, for the same ADR-133/134 reason: a nested interface
  // under a supported owner IS declared, so reaching this hint means this particular one is
  // deferred, and the declaration's own warning is where the specific shape rule is named.
  ForwardPlanSkipReason.UNDECLARED_INTERFACE -> {
    val interfaceName: String = detail ?: "the interface"
    "interface `$interfaceName` is nested inside another declaration and no C# nested interface " +
        "is generated for it, so every member typed with it is skipped rather than emitted as a " +
        "dangling reference; the SKIPPED_NESTED_DECLARATION warning on the declaration itself " +
        "names which shape rule defers it (a generic, `inner` or sealed nested type, or an owner " +
        "that cannot carry one), or move it to the top level of its file"
  }

  // Names the class or object, for the reason above. Since ADR-133 a nested class or object IS
  // declared as a C# nested type when its shape and its whole owner chain allow it, so this hint
  // no longer says nesting is fatal: reaching it means this particular one is deferred (the
  // `SKIPPED_NESTED_DECLARATION` warning at the declaration carries the specific reason), and the
  // ADR-066 amendment routes the remaining scope cases to the `include(...)` hint instead of here.
  ForwardPlanSkipReason.UNDECLARED_CLASS -> {
    val className: String = detail ?: "the class"
    "`$className` is nested inside another declaration and no C# nested type is generated " +
        "for it, so every member typed with it is skipped rather than emitted as a dangling " +
        "reference; the SKIPPED_NESTED_DECLARATION warning on the declaration itself names which " +
        "shape rule defers it (a generic, `inner`, `value` or sealed nested type, or an owner " +
        "that cannot carry one), or move it to the top level of its file"
  }

  // Names the value class, and says record struct rather than nested type: a value class is the one
  // nested kind that is not a handle at all, so "no C# nested type is generated for it" would read
  // as though a class were missing. Same ADR-134 shape rule as the hint above it, and the same two
  // remedies, because the same owner walk declares both.
  ForwardPlanSkipReason.UNDECLARED_VALUE_CLASS -> {
    val valueClassName: String = detail ?: "the value class"
    "value class `$valueClassName` is nested inside another declaration and no C# `readonly " +
        "record struct` is generated for it, so every member typed with it is skipped rather " +
        "than emitted as a dangling reference; the SKIPPED_NESTED_DECLARATION warning on the " +
        "declaration itself names which shape rule defers it (a generic or `enum class` owner), " +
        "or move it to the top level of its file"
  }

  // ADR-133: names the object and the C# rule. Deliberately not the UNDECLARED_CLASS hint: moving
  // the object to the top level changes nothing, because a Kotlin `object` renders as a C# STATIC
  // class wherever it is declared, and a static type is illegal at a parameter or return position.
  ForwardPlanSkipReason.OBJECT_POSITION -> {
    val objectName: String = detail ?: "the object"
    "`$objectName` is a Kotlin `object`, which is declared in C# as a static class (its members " +
        "are callable as `$objectName.Member()`), and C# forbids a static type at a parameter or " +
        "return position (CS0722), so every member typed with it is skipped rather than emitted " +
        "as uncompilable C#; return a regular class, or call the object`s members directly"
  }

  // ADR-115: no `include(...)`, no move-to-top-level and no scope change can repair either of
  // these, so the hint names the only two things that can: remove the marker, or stop exposing the
  // declaration publicly.
  ForwardPlanSkipReason.OPT_IN_MARKER ->
    "a C# consumer has no way to opt in, so an opt-in-required declaration is not exported; " +
        "remove the ${detail?.let { "`$it`" } ?: "marker"} annotation from it if it is meant to " +
        "be part of the C# API, or leave it marked if it is library-internal"

  // The reason line above already blames the type and says it is marked; this only needs to add
  // the marker's name and the consequence, not restate the reason.
  ForwardPlanSkipReason.OPT_IN_MARKER_TYPE -> {
    val parts: List<String>? = detail?.split("->", limit = 2)?.takeIf { it.size == 2 }
    val type: String = parts?.get(0) ?: "its type"
    val marker: String = parts?.get(1) ?: "an opt-in marker"
    "no C# type is declared for `$type` (opt-in marker `$marker`), so every member typed with " +
        "it is skipped rather than emitted as a dangling reference; remove the marker from " +
        "`$type`, or expose a type that is not opt-in-required instead"
  }

  ForwardPlanSkipReason.UNIMPLEMENTABLE_BOUND_INTERFACE ->
    "no mint{Interface}Bridge exists for this bound interface (ADR-085 inadmissible), so a " +
        "Kotlin implementation of it cannot be handed back to C#; take it as a parameter " +
        "instead, or return an interface the reverse bindings can bridge"

  // ADR-064 amendment (2026-09-20) / ADR-132: the two remedies that exist, parameter FIRST. Every
  // fan-out shape is by definition one that DOES bind at an ordinary parameter (that position is
  // what fans it into the ADR-079/080 has-value + value pair), so moving it off the receiver keeps
  // what the declaration means; the non-null receiver is second because it changes the meaning.
  // The non-null clause is truthful on both routes: the callable route binds every non-null twin
  // (ADR-132), and the property route binds `Enum`/`Instant`/`Duration` since its 2026-09-20
  // receiver-parity amendment.
  ForwardPlanSkipReason.RECEIVER_FAN_OUT -> {
    val receiver: String = detail?.let { "`$it`" } ?: "the receiver type"
    val nonNull: String = detail?.removeSuffix("?")?.let { "`$it`" } ?: "its non-null type"
    "$receiver binds as an ordinary parameter, so declare a top-level function that takes it as " +
        "a parameter instead of as the receiver; or declare the extension on the non-null " +
        "receiver $nonNull"
  }

  // ROADMAP Phase 4 (ADR-151 amendment): since a `ByteArray` binds as a `List` element and as a
  // `Map` VALUE, the only shapes that still reach this reason are the two DECLINED equality slots,
  // so the hint says WHY rather than sending the author to write an adapter that would behave
  // exactly as badly.
  ForwardPlanSkipReason.BYTE_ARRAY ->
    "a `ByteArray` cannot be a `Set` element or a `Map` key: arrays compare by identity in " +
        "Kotlin and in C# alike, and every crossing of this bridge copies, so the `byte[]` a " +
        "caller holds is never the array the Kotlin container hashed and no membership test or " +
        "lookup could ever succeed. use a `List<ByteArray>` if order (not uniqueness) is what " +
        "you need, or key the map by a `String` or a value class over one, such as a hex or " +
        "Base64 digest. a `ByteArray` binds everywhere else: at a `List`/`MutableList` element, " +
        "a `Map`/`MutableMap` VALUE, and at any ordinary parameter, return or property"

  else -> genericSkipHint
}

/** The hint every reason with nothing more specific to say keeps. */
private const val genericSkipHint: String =
  "expose a bridgeable adapter using only supported parameter/return shapes and export that " +
      "instead"

/**
 * A short, human-readable name for a diagnostic message; never used to drive marshalling.
 *
 * Lifted from a `ForwardPropertyPlanner` private member to file-level `internal` (the body touches
 * no planner state) so the callable planner can name a skipped collection's offending component in
 * exactly the wording the property setter diagnostic already uses.
 */
internal fun BridgeType.diagnosticTypeName(): String = when (this) {
  // ADR-160: spelled as the Kotlin function type the author wrote, so a refused nesting reads
  // `(Int) -> Unit` rather than a model constant.
  is BridgeType.Callback -> parameters.joinToString(
    prefix = "(",
    postfix = ") -> ${result.diagnosticTypeName()}",
  ) { parameter -> parameter.diagnosticTypeName() }

  BridgeType.Unit -> "Unit"
  BridgeType.Char -> "Char"
  BridgeType.String -> "String"
  BridgeType.Instant -> "Instant"
  BridgeType.Duration -> "Duration"
  BridgeType.Throwable -> "Throwable"
  BridgeType.Uuid -> "Uuid"
  BridgeType.ByteArray -> "ByteArray"
  is BridgeType.Primitive -> kind.name.lowercase().replaceFirstChar { it.uppercase() }
  is BridgeType.Enum -> qualifiedName.substringAfterLast('.')
  is BridgeType.ObjectHandle -> qualifiedName.substringAfterLast('.')
  is BridgeType.Interface -> qualifiedName.substringAfterLast('.')
  is BridgeType.BoundInterface -> qualifiedName.substringAfterLast('.')
  is BridgeType.ValueClass -> qualifiedName.substringAfterLast('.')
  is BridgeType.Collection -> "Collection"
  is BridgeType.Nullable -> "${type.diagnosticTypeName()}?"
  is BridgeType.SpecializedProtocol -> name
  // ADR-147: the parameter's own name, which is how the author spelled it.
  is BridgeType.TypeParameter -> name
  is BridgeType.RawCollection -> "Collection"
  is BridgeType.RawKSType -> rendered
  is BridgeType.Unsupported -> rendered
}
