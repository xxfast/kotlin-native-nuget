package io.github.xxfast.kotlin.native.nuget.test.cat

/**
 * Fixture for [#56](https://github.com/xxfast/kotlin-native-nuget/issues/56) part 1, designed in
 * ADR-108 (`docs/adr/108-result-return-mapping.md`) with the human decision **throw-on-failure
 * only** (no `TryRun` overload).
 *
 * `kotlin.Result` classifies as `BridgeType.ValueClass("kotlin.Result", underlying =
 * Nullable(Unsupported))`, and `valueClassResultShape` admits only String/Primitive/Enum/
 * ObjectHandle underlyings, so today both members below are dropped with
 * `[nuget:SKIPPED_UNSUPPORTED_TYPE] ... its VALUE_CLASS type combination is not supported` and
 * neither `Run` nor `Feed` exists on the generated `TestLibrary.Cat.Service`.
 *
 * The feature must lower `Result<T>` to `T` at the return position and append `.getOrThrow()`
 * inside the export's existing `try`, so a `Result.failure(e)` is indistinguishable at the
 * boundary from `throw e` and arrives in C# as the ADR-029-mapped exception.
 *
 * The two seams this crosses, once each:
 * - [Service.run] — `Result<Unit>`, the issue's exact repro: the payload has no wire at all, so
 *   the export body is `errorHandlingUnitBody` and the C# signature must be `void Run()`, not
 *   `Unit Run()`. This is the cell that proves the rewrite happens *before* the
 *   `(call.result == VOID) == (publicSignature.result == Unit)` emitter guard.
 * - [Service.feed] — `Result<String>`, a payload with a real (pointer) wire and both outcomes:
 *   success returns the string through the ordinary String result shape, failure rides the
 *   errorOut slot that already ships.
 *
 * Deliberately absent, because ADR-108 defers them: `Result` at property or parameter position,
 * `Result<T>` where `T` has no return shape, value-class-own members, `suspend fun`, and any
 * `Try`-style pair projection.
 *
 * Mylo eats anything put in front of him. Oreo is on a diet and has opinions about that.
 */
class Service {
  /** `Result<Unit>` -> `void Run()`. Always succeeds: the success half of the Unit payload. */
  fun run(): Result<Unit> = Result.success(Unit)

  /**
   * `Result<String>` -> `string Feed(string)`. Succeeds for Mylo; for Oreo returns
   * `Result.failure(IllegalArgumentException(...))`, which must surface as
   * `KotlinArgumentException : ArgumentException` with
   * `KotlinType == "kotlin.IllegalArgumentException"` -- the same object a `throw` would produce.
   */
  fun feed(catName: String): Result<String> =
    if (catName == "Oreo") Result.failure(IllegalArgumentException("Oreo is on a diet!"))
    else Result.success("$catName got a treat")
}

/** The issue's factory. `Service` has a no-arg constructor too; both reach the same members. */
fun service(): Service = Service()
