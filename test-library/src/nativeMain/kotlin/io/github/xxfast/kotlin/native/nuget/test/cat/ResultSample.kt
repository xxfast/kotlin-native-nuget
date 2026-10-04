package io.github.xxfast.kotlin.native.nuget.test.cat

/**
 * Fixture for [#56](https://github.com/xxfast/kotlin-native-nuget/issues/56) part 1, designed in
 * ADR-108 (`docs/adr/108-result-return-mapping.md`): `Result<T>` at an ordinary return position
 * binds as a throwing `T X()`, and beside it a non-throwing
 * `bool TryX(..., out T value, out Exception? failure)` twin over the same export.
 *
 * The throwing member lowers `Result<T>` to `T` and appends `.getOrThrow()` inside the export's
 * existing `try`, so a `Result.failure(e)` arrives in C# as the ADR-029-mapped exception, exactly
 * as `throw e` would. The Try twin tells those two apart: the export writes a failure flag from the
 * `Result` before unwrapping it, so `TryX` returns `false` (with the same mapped exception) for a
 * modelled failure and still throws for an exception the Kotlin body threw.
 *
 * The seams this crosses, once each:
 * - [Service.run] / [Service.scold] -- `Result<Unit>`: `void Run()`, and a Try with no `value`.
 * - [Service.feed] -- `Result<String>`, a payload that needs a conversion (a UTF-8 pointer).
 * - [Service.weigh] -- `Result<Int>`, a payload that needs none, and the one member whose body
 *   THROWS (for Ghost) instead of returning a failure: the Try must rethrow it, never report it as
 *   `false`.
 * - [Service.lastVetVisitYear] -- `Result<Int?>`: a successful `null` is not a failure.
 * - [Service.adopt] -- `Result<Cat>`, a handle payload.
 *
 * Deliberately absent, because ADR-108 defers them: `Result` at property or parameter position,
 * `Result<T>` where `T` has no return shape, value-class-own members and `suspend fun`.
 *
 * Mylo eats anything put in front of him. Oreo is on a diet and has opinions about that. Ghost is
 * not a cat anyone has ever weighed.
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

  /**
   * `Result<Int>` -> `int Weigh(string)`. Oreo refuses the scale (a modelled failure); Ghost is not
   * a cat at all, and the body throws rather than returning a failure.
   */
  fun weigh(catName: String): Result<Int> = when (catName) {
    "Ghost" -> throw IllegalStateException("No such cat: Ghost")
    "Oreo" -> Result.failure(IllegalArgumentException("Oreo will not get on the scale"))
    else -> Result.success(4)
  }

  /** `Result<Int?>`: nobody has taken Mylo to the vet yet (a successful null); Oreo hid. */
  fun lastVetVisitYear(catName: String): Result<Int?> =
    if (catName == "Oreo") Result.failure(IllegalStateException("Oreo hid under the bed"))
    else Result.success(null)

  /** `Result<Cat>`: a handle payload the caller owns. Oreo is not up for adoption. */
  fun adopt(catName: String): Result<Cat> =
    if (catName == "Oreo") Result.failure(IllegalArgumentException("Oreo stays home"))
    else Result.success(Cat(catName))

  /** `Result<Unit>` with a failure half: Oreo does not care. */
  fun scold(catName: String): Result<Unit> =
    if (catName == "Oreo") Result.failure(IllegalArgumentException("Oreo does not care"))
    else Result.success(Unit)
}

/** The issue's factory. `Service` has a no-arg constructor too; both reach the same members. */
fun service(): Service = Service()
