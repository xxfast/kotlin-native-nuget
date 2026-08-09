package io.github.xxfast.kotlin.native.nuget.processor.forward

import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.FileLocation
import com.google.devtools.ksp.symbol.KSNode

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

/**
 * ADR-064's forward bridgeable-subset boundary, the mirror of `RirDiagnosticKind`. Severity is
 * carried both by the `SKIPPED_/INFO_/ERROR_` name prefix (so a build log reads like the reverse
 * direction) and by [severity] itself (so the sink never string-matches its own enum, matching
 * ADR-057's reverse precedent).
 */
internal enum class ForwardDiagnosticKind(val severity: ForwardDiagnosticSeverity) {
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

  /** A value-class member inherited via interface delegation (e.g. `CharSequence by value`) —
   *  ROADMAP line 77's v1 product-scope skip, not a silently-bridged member. */
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
}

/**
 * ADR-064's message-format contract: the rendered line always embeds the kind's [Enum.name] (e.g.
 * `[nuget:SKIPPED_UNSUPPORTED_COMBINATION]`), in the reverse `formatDiagnostic()` house style
 * (`NugetGenerateBindingsTask.kt`'s `w: [nuget:{pkg}] {Skipping|Note}{location}: {reason}.
 * {hint}`), plus the `KSNode` source location reverse cannot carry.
 */
internal fun ForwardDiagnostic.format(): String {
  val verb: String = when (kind.severity) {
    ForwardDiagnosticSeverity.WARNING -> "Skipping"
    ForwardDiagnosticSeverity.INFO -> "Note"
    ForwardDiagnosticSeverity.ERROR -> "Error"
  }
  val location: String = if (signature.isBlank()) declaration else "$declaration($signature)"
  val at: String = (symbol?.location as? FileLocation)
    ?.let { location -> "\n    at ${location.filePath}:${location.lineNumber}" }
    ?: ""
  return "[nuget:${kind.name}] $verb $location: $reason. $hint$at"
}

/**
 * ADR-064 "Where the decision lives": the one sink every forward diagnostic producer routes
 * through. `SKIPPED_*`/`INFO_*` warn and generation continues with the member absent (never an
 * `IntPtr`/`"0"` fallback); `ERROR_*` fails generation. Both severities carry the originating
 * `KSNode` so KSP/Gradle can render the message at the author's own Kotlin source.
 */
internal object ForwardDiagnosticSink {
  fun emit(diagnostics: List<ForwardDiagnostic>, logger: KSPLogger) {
    diagnostics.forEach { diagnostic ->
      val message: String = diagnostic.format()
      when (diagnostic.kind.severity) {
        ForwardDiagnosticSeverity.ERROR -> logger.error(message, diagnostic.symbol)
        ForwardDiagnosticSeverity.WARNING,
        ForwardDiagnosticSeverity.INFO,
          -> logger.warn(message, diagnostic.symbol)
      }
    }
  }
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

  ForwardPlanSkipReason.UNEXPORTED_DEPENDENCY_TYPE ->
    ForwardDiagnosticKind.SKIPPED_UNEXPORTED_DEPENDENCY_TYPE

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
  ForwardPlanSkipReason.OBJECT,
  ForwardPlanSkipReason.STRING,
  ForwardPlanSkipReason.UNSUPPORTED,
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
 *   `"<expect qualified name>-><target rendered name>"`. Ignored by every other reason.
 */
internal fun ForwardPlanSkipReason.diagnosticHint(detail: String? = null): String = when (this) {
  ForwardPlanSkipReason.UNEXPORTED_DEPENDENCY_TYPE -> {
    val dependencyPackage: String = detail
      ?.let { qualifiedName -> qualifiedName.substringBeforeLast('.', qualifiedName) }
      ?: "the dependency's package"
    "add include(\"$dependencyPackage\") to nuget { publish { } }, or expose a type from an " +
        "in-scope package instead"
  }

  ForwardPlanSkipReason.ACTUAL_TYPEALIAS_TARGET -> {
    val parts: List<String>? = detail?.split("->", limit = 2)?.takeIf { it.size == 2 }
    val expectName: String = parts?.get(0) ?: "the expect type"
    val targetName: String = parts?.get(1) ?: "its actual typealias target"
    "the `actual typealias` for `$expectName` resolves to `$targetName`, which the forward " +
        "direction does not export; wrap it in a class you declare and expose that instead"
  }

  ForwardPlanSkipReason.COLLECTION ->
    "expose a wrapper taking a List/MutableList (or individual key/value parameters) instead " +
        "of a Map/Set at this position"

  ForwardPlanSkipReason.NULLABLE ->
    "expose a non-nullable wrapper, or a separate has-value/value pair, instead of a nullable " +
        "Boolean return"

  ForwardPlanSkipReason.UNSUPPORTED_COMBINATION ->
    "expose a non-inline, non-generic wrapper (e.g. a concrete suspend fun returning the " +
        "unwrapped value) and export that instead"

  ForwardPlanSkipReason.INHERITED_MEMBER ->
    "declare the member directly on the value class itself instead of relying on interface " +
        "delegation"

  ForwardPlanSkipReason.BOUND_INTERFACE_POSITION ->
    "ADR-088 v1 marshals a bound C# interface at ordinary, non-nullable function/method/" +
        "constructor parameters and method/function returns only; expose one of those instead of " +
        "a nullable, property or collection-component position"

  ForwardPlanSkipReason.UNIMPLEMENTABLE_BOUND_INTERFACE ->
    "no mint{Interface}Bridge exists for this bound interface (ADR-085 inadmissible), so a " +
        "Kotlin implementation of it cannot be handed back to C#; take it as a parameter " +
        "instead, or return an interface the reverse bindings can bridge"

  else ->
    "expose a bridgeable adapter using only supported parameter/return shapes and export that " +
        "instead"
}
