package io.github.xxfast.kotlin.native.nuget.test.issue56

/**
 * Fixture for [#56](https://github.com/xxfast/kotlin-native-nuget/issues/56) part 2, designed in
 * ADR-107 (`docs/adr/107-throwable-property-mapping.md`): a property whose declared type is
 * `kotlin.Throwable` / `Throwable?` must read from C# as a **constructed, unthrown**
 * `System.Exception`, carrying the ADR-029 type mapping and the ADR-028 cause chain that the
 * *thrown* position already delivers.
 *
 * `ForwardBridgeTypeClassifier` has no known-stdlib branch for `Throwable`, so it falls through to
 * the `exportedObjectHandles` test, becomes `Unsupported(isUnexportedDependency = true)`, and
 * `ForwardPropertyPlanner.isPlannable` drops every property below with
 * `[nuget:SKIPPED_UNSUPPORTED_PROPERTY]`. None of `Error`, `Fatal`, `LastError` exists on the
 * generated `TestLibrary.Issue56.Issue56Failure` today.
 *
 * The seams, once each:
 * - [Issue56Failure.error] — nullable getter, both halves: [quietMishap] reads `null`,
 *   [dietViolation] reads a mapped `KotlinArgumentException` whose `InnerException` is an
 *   **unmapped** `RuntimeException` (so the ADR-028 chain and the ADR-029 fallback are both
 *   observed, not just the happy mapping),
 * - [Issue56Failure.fatal] — **non-null** getter, which ADR-107 spells as a non-nullable
 *   `global::System.Exception`; carries an `IllegalStateException` so the mapped subtype differs
 *   from `error`'s and a shared-arm bug cannot pass,
 * - [Issue56Failure.lastError] — a `var`, which ADR-107 binds **get-only** (C# cannot mint a typed
 *   Kotlin `Throwable`); the C# cell asserts the setter is absent,
 * - [Issue56LoadState.Failure.error] — the same property on a **sealed subclass**. ADR-107 §Scope records
 *   (verified) that this renders through the legacy ADR-009 path in `SealedClassExports.kt` /
 *   `CirClassTranslator.kt`, which never consults `ForwardPropertyPlanner`, so it is a second,
 *   separate arm. The human decision put it in scope.
 *
 * The **constructor is expected not to bind**: `Issue56Failure(String, Throwable?, Throwable)` has
 * `Throwable` parameters, which ADR-107 leaves out of scope entirely, so `<init>` and `copy` keep
 * their existing input skip and C# must reach these values through the Kotlin factories below.
 * That is by design, not a gap in this fixture.
 *
 * Deliberately absent, because ADR-107 defers them: `Throwable` at a **method return**, as a
 * parameter, or as a collection component (`List<Throwable>`).
 *
 * Oreo raids the treat jar; Mylo just knocks the water bowl over.
 */
data class Issue56Failure(
  val reason: String,
  val error: Throwable?,
  val fatal: Throwable,
) {
  /**
   * A `var Throwable?`. Binds get-only: reading it must work, writing it must not exist in the
   * generated C# surface.
   */
  var lastError: Throwable? = error
}

/**
 * The `null` half of the nullable getter: nothing went wrong that anyone recorded, but the
 * household still has a non-null [Issue56Failure.fatal] to report.
 */
fun quietMishap(): Issue56Failure = Issue56Failure(
  reason = "Mylo knocked the water bowl over",
  error = null,
  fatal = IllegalStateException("the kitchen floor is a lake"),
)

/**
 * The populated half: a mapped `IllegalArgumentException` whose cause is an **unmapped**
 * `RuntimeException`, so C# must see `KotlinArgumentException` -> `InnerException` of the base
 * `KotlinException` type.
 */
fun dietViolation(): Issue56Failure = Issue56Failure(
  reason = "Oreo raided the treat jar",
  error = IllegalArgumentException(
    "Oreo is on a diet!",
    RuntimeException("the treat jar was left open"),
  ),
  fatal = IllegalStateException("the treat jar is empty"),
)

/**
 * The sealed cell. `Loading` is the payload-free arm (Mylo, waiting by the bowl); `Failure`
 * carries the `Throwable?` property that the legacy sealed-subclass renderer must learn to emit.
 */
sealed class Issue56LoadState {
  /** Nothing has gone wrong yet. */
  data object Loading : Issue56LoadState()

  /** The arm under test: a sealed subclass property typed `Throwable?`. */
  data class Failure(val error: Throwable?) : Issue56LoadState()
}

/**
 * Returns the [Issue56LoadState.Failure] arm through the **sealed base**, so C# reaches it via the
 * ADR-009 `Issue56LoadState.FromHandle` discriminator and casts, exactly as `Observation` already does.
 */
fun failedLoad(): Issue56LoadState = Issue56LoadState.Failure(
  IllegalArgumentException("Oreo is on a diet!"),
)

/** The payload-free arm, so the discriminator has something to choose against. */
fun pendingLoad(): Issue56LoadState = Issue56LoadState.Loading
