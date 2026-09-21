package io.github.xxfast.kotlin.native.nuget.processor.forward

import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSNode
import java.util.Collections

/**
 * ADR-162: the per-declaration containment boundary for the generator's own invariants.
 *
 * The forward pipeline holds about 270 raw `error(...)`/`require(...)`/`check(...)` sites, 36 of
 * them `else -> error` arms of a `when` over a sealed model. Every historical crash of that family
 * (issue #52, ADR-080, ADR-081, ADR-097) was an "invariant" that a perfectly legal Kotlin shape
 * reached, because a new `BridgeType` variant or a new route arrived before every `when` learned
 * about it. Classifying them one by one cannot cover the one that fires next, by construction: it
 * is the one nobody has classified. So the boundary is generic, and it is a *reporting* boundary
 * rather than a recovery one.
 *
 * What [guarded] changes, measured (ADR-162 spikes B and C): before it, the first such failure
 * threw out of `process()` and Gradle printed one unlocated
 * `e: [ksp] java.lang.IllegalStateException: ...` line naming one declaration, with every other
 * offending declaration of the same surface invisible until the author fixed that one and built
 * again. After it, each failing declaration is reported as
 * `e: [ksp] <path>:<line>: [nuget:ERROR_INTERNAL_GENERATOR_FAILURE] ...`, the loop keeps going so
 * the rest of the round's failures are reported too, and the existing fatal-diagnostic gate stops
 * the round before `CNameExports.kt` or the ABI contract checks. Nothing inconsistent ships, so a
 * dependent of a failed declaration needs no treatment at all.
 */
internal object ForwardInternalFailures {
  // Synchronized and reset per round for the same reason `ForwardDiagnosticSink.recorded` is: KSP
  // runs the processor on a Worker API thread and two targets' rounds can share one daemon.
  private val reported: MutableSet<Pair<String, String>> =
    Collections.synchronizedSet(mutableSetOf<Pair<String, String>>())

  /** True the first time this (declaration, failure) pair is seen in the round. */
  fun firstReport(declaration: String, detail: String): Boolean =
    reported.add(declaration to detail)

  fun reset() = reported.clear()
}

/**
 * The one-line rendering of a caught failure: the exception class plus its message, which
 * together are what makes the generator bug findable in the source. The class name alone is
 * useless (they are all `IllegalStateException`) and the message alone loses the distinction
 * between a failed `require` and a null `!!`.
 */
internal fun internalFailureDetail(failure: Throwable): String =
  "${failure::class.simpleName ?: failure::class.java.name}: ${failure.message ?: "(no message)"}"

/**
 * The diagnostic a contained failure reports. The hint is the load-bearing half: the author did
 * nothing wrong, so the message says so and names the one line that unblocks their build today
 * (dropping the declaration from the export scope) rather than asking them to change working
 * Kotlin.
 *
 * @param declaration the qualified Kotlin name, which is both what the author reads and what the
 *   `exclude(...)` line has to spell.
 * @param node the originating `KSNode`, so KSP attaches the source location. Null only for the
 *   whole-round guard and the renderer, which have no single declaration in hand.
 */
internal fun internalFailureDiagnostic(
  declaration: String,
  node: KSNode?,
  detail: String,
): ForwardDiagnostic = ForwardDiagnostic(
  kind = ForwardDiagnosticKind.ERROR_INTERNAL_GENERATOR_FAILURE,
  symbol = node,
  declaration = declaration,
  reason = "the generator's own invariant failed while binding it ($detail)",
  hint = "this is a bug in the bridge generator, not a mistake in your Kotlin: add " +
      "exclude(\"$declaration\") to nuget { publish { } } to unblock this build, and report the " +
      "failure with this whole message",
  // ERROR_*: the round returns before anything generated is read, so there is no C# declaration
  // for a `<remarks>` paragraph to land on.
  owner = null,
)

/**
 * Runs [block], containing any `Exception` it throws as one located
 * [ForwardDiagnosticKind.ERROR_INTERNAL_GENERATOR_FAILURE] against [declaration], and returning
 * null so the caller's loop can skip this one declaration and keep reporting the others.
 *
 * Catches `Exception`, never `Throwable`: `OutOfMemoryError`, `StackOverflowError` and KSP's own
 * `Error`s are not per-declaration facts and must still abort the round.
 *
 * Deduped on (declaration, failure detail) for the round, because the same declaration is walked by
 * both projections of ADR-062's plan and would otherwise report the same bug twice.
 */
internal fun <T> guarded(
  declaration: String,
  node: KSNode?,
  logger: KSPLogger,
  block: () -> T,
): T? = try {
  block()
} catch (failure: Exception) {
  val detail: String = internalFailureDetail(failure)
  if (ForwardInternalFailures.firstReport(declaration, detail)) {
    ForwardDiagnosticSink.emit(
      listOf(internalFailureDiagnostic(declaration, node, detail)),
      logger,
    )
  }
  null
}

/** The name a guard reports a declaration under: the qualified one where KSP has it. */
internal fun KSDeclaration.forwardGuardName(): String =
  qualifiedName?.asString() ?: simpleName.asString()
