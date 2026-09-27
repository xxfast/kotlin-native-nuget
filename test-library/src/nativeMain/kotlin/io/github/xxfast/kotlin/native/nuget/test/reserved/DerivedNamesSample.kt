/**
 * Fixture for the generator names *derived* from a user parameter's name, the half of the
 * reserved-name family `ReservedNamesSample.kt` does not cover. That file is about fixed literals
 * (`handle`, `value`, `errorOut`, ...), where the rule moves the user's parameter. These names are
 * built from a sibling parameter, so a user can only collide with them by spelling a second
 * parameter after the first:
 * - `${name}HasValue`, the presence flag every nullable value-type parameter crosses as, minted on
 *   the plan route for primitives and `Char` (ADR-098), enums (ADR-080), value classes over a
 *   primitive (ADR-079), `Instant` (ADR-076) and `Duration` (ADR-103), and on the legacy
 *   suspend / Flow / StateFlow routes (ADR-122),
 * - the ADR-164 default-argument dispatcher's `${name}IsSet` slot, its `default_${name}` and `mask`
 *   Kotlin body locals, and its C# `${name}Value` body local,
 * - the legacy routes' lowered `${name}Arg` local and their fixed `scopeHandle` / `userData` slots.
 *
 * The rule under test: the **generator's** identifier moves on a collision, never the user's. Every
 * one of these names is private (the extern and the `@CName` export are positional), so the C#
 * caller has to see exactly the names written here, with no trailing underscore.
 *
 * Two kinds of red, and the fixture carries both on purpose:
 * - **loud**: most shapes declare the same name twice in the generated `@CName` export, so the
 *   Kotlin/Native compile fails with `Conflicting declarations`,
 * - **silent**: [Pantry.pour] and [Pantry.ration] compile clean today. The dispatcher's
 *   `val default_limit` and `var mask` locals shadow the user's own `default_limit` and `mask`
 *   parameters, so Kotlin receives `limit`'s value and the dispatch bitmask instead. A fix that
 *   only makes the loud shapes compile, or that moves a mint site without moving every reader of
 *   it, turns a loud row into one of these.
 *
 * Every callable returns all of its arguments joined with `|`, so a dropped, swapped or shadowed
 * argument reads back as the wrong text rather than as a plausible one.
 *
 * Deliberately absent: any workaround rename. The whole point is that a user may name a parameter
 * `limitHasValue`.
 *
 * Oreo (black, white in the middle) rations the kibble; Mylo (brown and creamy) checks whether the
 * bowl has any value at all.
 */
package io.github.xxfast.kotlin.native.nuget.test.reserved

import kotlin.time.Duration
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow

/** How hungry the cat is. Crosses the enum `${name}HasValue` pair (ADR-080). */
enum class Appetite { PECKISH, RAVENOUS }

/**
 * A scoop of kibble, a value class over `Int`, so it crosses the ADR-079 `${name}HasValue` pair.
 */
value class Scoop(val grams: Int)

/**
 * A class the legacy suspend route carries by handle, so it lowers through a `${name}Arg` local.
 */
class Kibble(val brand: String)

/**
 * `${name}HasValue` on the **plan route, top-level**. The synthesized flag for `limit` and the
 * user's `limitHasValue` are both `limitHasValue: Boolean` in the export.
 */
fun measure(limit: Int?, limitHasValue: Boolean): String = "$limit|$limitHasValue"

/**
 * The same collision with the parameters **reversed**, so the fix cannot depend on the user's
 * parameter arriving after the one its name was derived from.
 */
fun measureReversed(limitHasValue: Boolean, limit: Int?): String = "$limitHasValue|$limit"

/** `${name}HasValue` on the ADR-080 **enum** pair. */
fun judge(mood: Appetite?, moodHasValue: Boolean): String = "$mood|$moodHasValue"

/** `${name}HasValue` on the ADR-079 **value class** pair. */
fun weigh(w: Scoop?, wHasValue: Boolean): String = "${w?.grams}|$wHasValue"

/** `${name}HasValue` on the ADR-103 **Duration** pair. Whole seconds, so the text is exact. */
fun wait(d: Duration?, dHasValue: Boolean): String = "${d?.inWholeSeconds}|$dHasValue"

/** `${name}HasValue` on the ADR-098 **Char** pair. */
fun letter(c: Char?, cHasValue: Boolean): String = "$c|$cHasValue"

/** `${name}HasValue` on the ADR-076 **Instant** pair. Epoch seconds, so the text is exact. */
fun stamp(at: Instant?, atHasValue: Boolean): String = "${at?.epochSeconds}|$atHasValue"

/**
 * `${name}HasValue` on the **plan route, constructor**. [describe] reads both back so the
 * constructor's own arguments are observable.
 */
class Hopper(val limit: Int?, val limitHasValue: Boolean) {
  /** See [Hopper]. */
  fun describe(): String = "$limit|$limitHasValue"
}

/** The **plan route, member** shapes, including the ADR-164 dispatcher's derived names. */
class Pantry {
  /** `${name}HasValue` on an ordinary instance method. */
  fun fill(limit: Int?, limitHasValue: Boolean): String = "$limit|$limitHasValue"

  /**
   * `${name}HasValue` through the ADR-164 dispatcher, both defaulted. The user's `limitHasValue`
   * gets its own `limitHasValueHasValue` slot today; only the synthesized `limitHasValue` collides.
   */
  fun top(limit: Int? = 3, limitHasValue: Boolean = true): String = "$limit|$limitHasValue"

  /** The dispatcher's `${name}IsSet` presence slot. */
  fun serve(limit: Int? = 3, limitIsSet: Boolean): String = "$limit|$limitIsSet"

  /**
   * **Silent today.** The dispatcher's `val default_limit` local shadows the user's `default_limit`
   * parameter, so it receives `limit`'s value.
   */
  fun pour(limit: Int? = 3, default_limit: Int?): String = "$limit|$default_limit"

  /**
   * **Silent today.** The dispatcher's `var mask` local shadows the user's `mask` parameter, so it
   * receives the dispatch bitmask (`0` or `1`) instead of what C# passed.
   */
  fun ration(limit: Int = 3, mask: Int): String = "$limit|$mask"

  /** The C# wrapper's `${name}Value` local, which unwraps `Optional<int?> limit`. */
  fun ladle(limit: Int? = 3, limitValue: Int): String = "$limit|$limitValue"
}

/** The **legacy routes**' `${name}HasValue` slot (`legacyHasValueSlot`), one cell per route. */
class Larder {
  /** Legacy **suspend member**. */
  suspend fun fill(limit: Int?, limitHasValue: Boolean): String = "$limit|$limitHasValue"

  /** Legacy **Flow member**. Emits once, so the one element carries both arguments. */
  fun snacks(limit: Int?, limitHasValue: Boolean): Flow<String> = flow {
    emit("$limit|$limitHasValue")
  }

  /**
   * Legacy **StateFlow member**, whose `_collect` and `_value` exports both collide. The value is a
   * pure function of the arguments, so the route re-invoking this per `.Value` read is harmless.
   */
  fun bowl(limit: Int?, limitHasValue: Boolean): StateFlow<String> =
    MutableStateFlow("$limit|$limitHasValue")

  /**
   * Legacy **suspend returning StateFlow** (ADR-068). A member rather than top-level: a top-level
   * `suspend fun ...: StateFlow<String>` renders `Task<StateFlow>` in C# today, a type that does
   * not exist, which is a separate bug from this one.
   */
  suspend fun dish(limit: Int?, limitHasValue: Boolean): StateFlow<String> =
    MutableStateFlow("$limit|$limitHasValue")

  /**
   * The legacy suspend member's fixed `scopeHandle` slot (only an instance member threads the
   * class scope) and `userData` slot, both spelled by a user.
   */
  suspend fun count(scopeHandle: Int, userData: Int): String = "$scopeHandle|$userData"
}

/** Legacy **top-level suspend**. */
suspend fun portion(limit: Int?, limitHasValue: Boolean): String = "$limit|$limitHasValue"

/**
 * The legacy route's lowered `${name}Arg` local: `x` lowers to `val xArg = ...asStableRef...`,
 * which shadows the user's own `xArg` handle.
 */
suspend fun mix(x: Kibble, xArg: Kibble): String = "${x.brand}|${xArg.brand}"

/**
 * The legacy top-level suspend route's fixed `userData` slot, spelled by a user. `scopeHandle`
 * collides with nothing here (a top-level function has no class scope) and rides along as the
 * control; [Larder.count] is where it collides.
 */
suspend fun tally(scopeHandle: Int, userData: Int): String = "$scopeHandle|$userData"

/**
 * The legacy **Flow** routes' fixed names, each spelled by a user: the Kotlin export's
 * `scopeHandle` / `userData` slots and its `obj` / `scope` / `emit` body names, and the C# collect
 * lambda's `(onNext, onComplete, onError, userData)` parameters, which would otherwise shadow the
 * user's arguments inside the native call.
 */
class Spout {
  /** Legacy **Flow member**. */
  fun trickle(
    onNext: Int, onComplete: Int, onError: Int, userData: Int, scopeHandle: Int, obj: Int,
    scope: Int,
  ): Flow<String> = flow {
    emit("$onNext|$onComplete|$onError|$userData|$scopeHandle|$obj|$scope")
  }

  /** Legacy **StateFlow member** (collect and `.Value` read). */
  fun gauge(onNext: Int, userData: Int): StateFlow<String> = MutableStateFlow("$onNext|$userData")

  /** Legacy **held MutableStateFlow member**, whose C# body declares `flow` and `collectScope`. */
  fun level(flow: Int, collectScope: Int): MutableStateFlow<String> =
    MutableStateFlow("$flow|$collectScope")
}

/**
 * The plan route's ADR-160 callback pair `${name}Ptr` / `${name}UserData` in the Kotlin export and
 * the C# wrapper's `${name}Native` / `${name}Ctx` locals, each spelled by a user.
 */
fun tick(
  onTick: () -> Int, onTickPtr: Int, onTickUserData: Int, onTickNative: Int, onTickCtx: Int,
): String = "${onTick()}|$onTickPtr|$onTickUserData|$onTickNative|$onTickCtx"

/** The plan route's C# `${name}Handle` collection local, spelled by a user. */
fun label(tags: List<String>, tagsHandle: Int): String = "${tags.joinToString(",")}|$tagsHandle"

/**
 * The legacy top-level suspend C# wrapper's method-scope locals (`tcs`, `callback`,
 * `callbackHandle`, `job`, `jobHandle`, `reg`) and its `callback` slot, each spelled by a user.
 */
suspend fun brew(
  tcs: Int, callback: Int, callbackHandle: Int, job: Int, jobHandle: Int, reg: Int,
): String = "$tcs|$callback|$callbackHandle|$job|$jobHandle|$reg"

/** The legacy suspend route's C# `${name}Handle` collection local, spelled by a user. */
suspend fun sift(tags: List<String>, tagsHandle: Int): String =
  "${tags.joinToString(",")}|$tagsHandle"

/**
 * The legacy suspend member's Kotlin body names, each spelled by a user: `obj`, `scope`, and the
 * `callbackPtr` slot. `result` / `resultRef` ride along as the control: they are declared inside
 * the launch lambda, after the call that reads the user's arguments, so they never needed to move.
 */
class Kettle {
  /** See [Kettle]. */
  suspend fun stir(obj: Int, scope: Int, callbackPtr: Int, result: Int, resultRef: Int): String =
    "$obj|$scope|$callbackPtr|$result|$resultRef"

  /**
   * A suspend member whose parameter takes the generated trailing token's name: the user's name
   * wins and the token becomes `cancellationToken_`.
   */
  suspend fun pour(cancellationToken: Int): String = "poured $cancellationToken"
}

/** The same `cancellationToken` collision on the top-level suspend route. */
suspend fun steep(cancellationToken: Int): String = "steeped $cancellationToken"

/**
 * Callback parameters spelled like the C# call-site delegate lambda's own `a0` / `ctx`: the lambda
 * body invokes the user's callback, so its parameters move instead.
 */
fun relay(a0: (Int) -> String): String = a0(4)

/** See [relay]. */
fun echo(ctx: (Int) -> String): String = ctx(5)
