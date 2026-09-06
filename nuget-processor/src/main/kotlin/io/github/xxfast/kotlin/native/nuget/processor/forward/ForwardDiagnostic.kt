package io.github.xxfast.kotlin.native.nuget.processor.forward

import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.FileLocation
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
  val signature: String = "",
)

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

  /** `Map`/`Set` (and mutable variants) as a method *parameter* — no `CreateMap`/`CreateSet`
   *  helper exists (ROADMAP line 78). */
  SKIPPED_UNSUPPORTED_INPUT(ForwardDiagnosticSeverity.WARNING),

  /** A nullable `Boolean` method *return* — no single-call ABI shape for it (ROADMAP line 79,
   *  ADR-061 deferred width). */
  SKIPPED_UNSUPPORTED_RETURN(ForwardDiagnosticSeverity.WARNING),

  /** A property whose classified type the property planner has no getter/setter shape for, or an
   *  extension property whose *receiver* type has no supported wire shape, so the whole property
   *  is absent from the generated C#. Completes the position naming alongside
   *  [SKIPPED_UNSUPPORTED_INPUT] (a parameter) and [SKIPPED_UNSUPPORTED_RETURN] (a return);
   *  a property used to be the one position that vanished with no diagnostic at all.
   *
   *  Never fires for a property the legacy routes still re-emit (lambda, suspend lambda, Flow,
   *  StateFlow): those are unplannable on purpose and bind through `CirClassTranslator`'s
   *  adapters, so a warning would be a false positive. */
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

  /** ADR-066: a reachable, structurally bridgeable declaration in a dependency module whose
   *  package the reachability closure did not admit — out of scope, not unsupported. Replaces
   *  the misleading `SKIPPED_UNSUPPORTED_TYPE` this case used to fall through to. */
  SKIPPED_UNEXPORTED_DEPENDENCY_TYPE(ForwardDiagnosticSeverity.WARNING),

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
   *  admits same-round source declarations — and only the base-class hint mentions it, since only
   *  a base class is worth exporting for its members. */
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

  /** A public declaration nested inside an exported class-like declaration: a `class`, `object`,
   *  `interface` or `enum class`. Every root bucket in `NugetProcessor` filters
   *  `parentDeclaration == null` and the ADR-066 closure refuses to admit a nested dependency
   *  declaration, so nothing is ever generated for one; it used to vanish in total silence, with
   *  only the members typed with it saying anything at all. This names the declaration itself,
   *  once, where it is declared. Sealed subclasses (ADR-009, declared nested under their base) and
   *  companion objects (ADR-013, their owner's statics) are excluded: those ARE declared.
   *
   *  The declaration-level twin of the member-level `UNDECLARED_CLASS`/`UNDECLARED_ENUM`/
   *  `UNDECLARED_INTERFACE` skips, and deliberately both: a nested declaration nothing references
   *  would otherwise produce no output and no diagnostic whatsoever. */
  SKIPPED_NESTED_DECLARATION(ForwardDiagnosticSeverity.WARNING),

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
   *  (`SEALED_PROTOCOL`, `GENERIC`, ...): that flag describes the *method* legacy routes and no
   *  legacy route re-emits a constructor, so a constructor skipped for one of those was silent in
   *  every channel. Not fired for an abstract class (uninstantiable by design) or for the ADR-040
   *  interface backing wrapper (`translateInterfaceBackingClass`, which is never handle-less by
   *  accident). */
  WARNING_NO_PUBLIC_CONSTRUCTOR(
    ForwardDiagnosticSeverity.WARNING,
    declaredVerb = "Keeping",
  ),
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
  // ADR-100: every non-fatal diagnostic, in emission order, for `NugetDiagnostics.json`. The
  // KSPLogger calls stay (free, observed by the Tier 1 harness, and they start working the day the
  // Gradle/KSP worker-stdout gap closes upstream), but they reach no console today, so the file is
  // what a consumer actually gets. Synchronized because KSP runs the processor on a Worker API
  // thread and two targets' rounds can share one daemon; the processor resets before each round.
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
          )
        }
      }
    }
  }

  /** Starts a fresh round; the object is a singleton in a long-lived Gradle daemon. */
  fun reset() = recorded.clear()

  fun recorded(): List<ForwardDiagnosticRecord> = synchronized(recorded) { recorded.toList() }
}

/**
 * ADR-064 producer (1): the planner's [ForwardPlanSkipReason] → the named kind. Only reachable
 * for `droppedFromCSharp = true` reasons; a legacy-route deferral (`droppedFromCSharp = false`)
 * never reaches [ForwardCallablePlanCatalog.droppedCallables] and so never calls this.
 *
 * `COLLECTION` and `NULLABLE` are fixed mappings per the ADR Decision table, not a general
 * input/return disambiguation: `COLLECTION` only currently arises from an input-position skip
 * (`Map`/`Set` method parameters — a `List`/`MutableList` element accepts them and every other
 * collection *return* already has a working shape), and `NULLABLE` is asserted at the
 * nullable-Boolean-return site (ADR-061's deferred width). A future reason that is genuinely
 * ambiguous between input and return position would need the planner to carry that distinction
 * explicitly rather than relying on this table.
 */
internal fun ForwardPlanSkipReason.toDiagnosticKind(): ForwardDiagnosticKind = when (this) {
  ForwardPlanSkipReason.COLLECTION -> ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_INPUT
  ForwardPlanSkipReason.NULLABLE -> ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_RETURN
  ForwardPlanSkipReason.UNSUPPORTED_COMBINATION ->
    ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_COMBINATION

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

  ForwardPlanSkipReason.BOUND_INTERFACE_POSITION ->
    ForwardDiagnosticKind.SKIPPED_BOUND_TYPE_POSITION

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
  ForwardPlanSkipReason.VALUE_CLASS,
    -> ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_TYPE

  ForwardPlanSkipReason.ABSTRACT,
  ForwardPlanSkipReason.CALLBACK_PROTOCOL,
  ForwardPlanSkipReason.FLOW_PROTOCOL,
  ForwardPlanSkipReason.GENERIC,
  ForwardPlanSkipReason.SEALED_PROTOCOL,
  ForwardPlanSkipReason.SUSPEND,
  ForwardPlanSkipReason.SUSPEND_CALLBACK_PROTOCOL,
  ForwardPlanSkipReason.TYPE_PARAMETER,
    -> error(
    "Forward diagnostic translation received a legacy-route deferral ($this); these are " +
        "droppedFromCSharp = false and must never reach warnDroppedForwardCallables",
  )
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
 *   [ForwardPlanSkipReason.UNDECLARED_INTERFACE] it carries the undeclared type's qualified name,
 *   including when the enum is a collection component (the only extractor that descends into one).
 *   Ignored by every other reason.
 */
/** `kotlin`, `kotlin.*` and `kotlinx.*`: packages an export scope can never usefully admit. */
private fun String.isStdlibPackage(): Boolean =
  this == "kotlin" || startsWith("kotlin.") || this == "kotlinx" || startsWith("kotlinx.")

internal fun ForwardPlanSkipReason.diagnosticHint(
  detail: String? = null,
  scope: List<String> = emptyList(),
): String = when (this) {
  ForwardPlanSkipReason.UNEXPORTED_DEPENDENCY_TYPE -> {
    val dependencyPackage: String = detail
      ?.let { qualifiedName -> qualifiedName.substringBeforeLast('.', qualifiedName) }
      ?: "the dependency's package"
    if (dependencyPackage.isStdlibPackage()) {
      // Issue #55/#56: `include("kotlin")` was the hint here, and following it replaced the
      // export scope with one nothing in the module lives under. A stdlib type wants a first-class
      // mapping (ADR-076 `Instant`, ADR-103 `Duration`), not an export-scope change.
      "${detail ?: "it"} is a Kotlin stdlib type with no first-class C# mapping yet; expose a " +
          "bridgeable type instead (include(...) is not the fix: an explicit include replaces " +
          "the export scope rather than mapping the type)"
    } else {
      // Issue #55: name the whole include line, not just the missing package. ADR-063's explicit
      // `include` replaces the `rootPackage` default, so a hint naming only the new package
      // walks the author into an empty export set.
      val packages: String = (scope + dependencyPackage).distinct().joinToString { "\"$it\"" }
      "add include($packages) to nuget { publish { } } (an explicit include replaces the " +
          "rootPackage default, so keep your own packages listed), or expose a type from an " +
          "in-scope package instead"
    }
  }

  // Following ADR-109's `exclude("<pkg>")` remedy lands every callable reaching the excluded type
  // here. `include(...)` is not the fix: `PackageScope.covers` tests `exclude` first, so an
  // include can never override one.
  ForwardPlanSkipReason.EXCLUDED_DEPENDENCY_TYPE -> {
    val excluded: String = detail
      ?.let { qualifiedName -> qualifiedName.substringBeforeLast('.', qualifiedName) }
      ?: "its package"
    "\"$excluded\" is excluded by exclude(\"$excluded\") in nuget { publish { } }, so a callable " +
        "reaching ${detail ?: "it"} is skipped by design; remove the exclude to export it here " +
        "(include(...) cannot override an exclude)"
  }

  ForwardPlanSkipReason.EXPECT_DEPENDENCY_TYPE ->
    "${detail ?: "it"} is an `expect` declaration in a dependency module; its actualization " +
        "lives in that module and cannot be brought into scope with include(...); expose a " +
        "type you declare instead"

  ForwardPlanSkipReason.CROSS_MODULE_DISABLED_DEPENDENCY_TYPE -> {
    val dependencyPackage: String = detail
      ?.let { qualifiedName -> qualifiedName.substringBeforeLast('.', qualifiedName) }
      ?: "the dependency's package"
    "no rootPackage or include is set, so nuget { publish { } } never crosses the module " +
        "boundary and ${detail ?: "the type"} stays out of the export set; set rootPackage(...) " +
        "or list your own packages alongside \"$dependencyPackage\" in include(...) " +
        "(include(...) on its own replaces the everything-in-this-module default and would " +
        "drop your own files)"
  }

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
  ForwardPlanSkipReason.NULLABLE ->
    "expose a non-nullable wrapper, or a separate has-value/value pair, instead of a nullable " +
        "value at this position"

  ForwardPlanSkipReason.UNSUPPORTED_COMBINATION ->
    "expose a non-inline, non-generic wrapper (e.g. a concrete suspend fun returning the " +
        "unwrapped value) and export that instead"

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

  // Names the enum, because the reason line cannot: `warnDroppedForwardCallables` builds it from
  // the reason's own name. Worded to stay true for both shapes the flag covers — a nested enum in
  // either module, and a module-local top-level enum outside the export scope — since `detail`
  // carries only the qualified name and cannot tell them apart.
  ForwardPlanSkipReason.UNDECLARED_ENUM -> {
    val enumName: String = detail ?: "the enum"
    "enum `$enumName` is not in the export set, so it is never declared as a C# enum and every " +
        "member typed with it is skipped rather than emitted as a dangling reference; a nested " +
        "enum class is never declared (only top-level enums are), so move it to the top level of " +
        "its file — or, if it already is top level, bring its package into the export scope"
  }

  // Names the interface, for the reason above, and says nested explicitly: unlike the enum flag
  // this one covers exactly one shape, so the hint does not have to hedge about export scope.
  ForwardPlanSkipReason.UNDECLARED_INTERFACE -> {
    val interfaceName: String = detail ?: "the interface"
    "interface `$interfaceName` is nested inside a class, and a nested interface is never " +
        "declared as a C# interface (only top-level ones are), so every member typed with it is " +
        "skipped rather than emitted as a dangling reference; move it to the top level of its file"
  }

  // Names the class or object, for the reason above. Covers exactly one shape (a nested
  // declaration in either module), so like the interface hint it does not hedge about scope.
  ForwardPlanSkipReason.UNDECLARED_CLASS -> {
    val className: String = detail ?: "the class"
    "`$className` is nested inside another declaration, and a nested class or object is never " +
        "declared in C# (only top-level ones are, plus sealed subclasses and companion objects), " +
        "so every member typed with it is skipped rather than emitted as a dangling reference; " +
        "move it to the top level of its file"
  }

  ForwardPlanSkipReason.UNIMPLEMENTABLE_BOUND_INTERFACE ->
    "no mint{Interface}Bridge exists for this bound interface (ADR-085 inadmissible), so a " +
        "Kotlin implementation of it cannot be handed back to C#; take it as a parameter " +
        "instead, or return an interface the reverse bindings can bridge"

  else ->
    "expose a bridgeable adapter using only supported parameter/return shapes and export that " +
        "instead"
}

/**
 * A short, human-readable name for a diagnostic message; never used to drive marshalling.
 *
 * Lifted from a `ForwardPropertyPlanner` private member to file-level `internal` (the body touches
 * no planner state) so the callable planner can name a skipped collection's offending component in
 * exactly the wording the property setter diagnostic already uses.
 */
internal fun BridgeType.diagnosticTypeName(): String = when (this) {
  BridgeType.Unit -> "Unit"
  BridgeType.Char -> "Char"
  BridgeType.String -> "String"
  BridgeType.Instant -> "Instant"
  BridgeType.Duration -> "Duration"
  BridgeType.Throwable -> "Throwable"
  BridgeType.Uuid -> "Uuid"
  is BridgeType.Primitive -> kind.name.lowercase().replaceFirstChar { it.uppercase() }
  is BridgeType.Enum -> qualifiedName.substringAfterLast('.')
  is BridgeType.ObjectHandle -> qualifiedName.substringAfterLast('.')
  is BridgeType.Interface -> qualifiedName.substringAfterLast('.')
  is BridgeType.BoundInterface -> qualifiedName.substringAfterLast('.')
  is BridgeType.ValueClass -> qualifiedName.substringAfterLast('.')
  is BridgeType.Collection -> "Collection"
  is BridgeType.Nullable -> "${type.diagnosticTypeName()}?"
  is BridgeType.SpecializedProtocol -> name
  is BridgeType.RawCollection -> "Collection"
  is BridgeType.RawKSType -> rendered
  is BridgeType.Unsupported -> rendered
}
