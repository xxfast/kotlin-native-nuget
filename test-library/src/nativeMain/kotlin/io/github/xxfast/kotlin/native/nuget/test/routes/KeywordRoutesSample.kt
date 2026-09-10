/**
 * Fixture for the **legacy forward routes** half of
 * [#65](https://github.com/xxfast/kotlin-native-nuget/issues/65) and
 * [#66](https://github.com/xxfast/kotlin-native-nuget/issues/66): a Kotlin parameter named after a
 * C# reserved word, or named literally `error`, still renders as a bare identifier on every
 * forward route that predates the ordinary callable plan.
 *
 * `csharpParameterName()` (`ForwardCirPlanProjection.kt`) is where both fixes live, and it is only
 * reached from the ordinary plan's `ForwardPublicParameter` / `ForwardAbiParameter` render sites.
 * The specialized legacy protocols (ADR-078) print their own `DllImport` declarations and wrapper
 * bodies as raw renderer text off the CIR nodes, so a parameter name copied from Kotlin reaches
 * `Interop.cs` unescaped there: `int ref`, `string params` (CS1001 / CS1041), and, next to the
 * ADR-024 exception slot, a second `error` (CS0100 / CS0136).
 *
 * `issue65/Issue65Sample.kt` and `issue66/Issue66Sample.kt` are the already-green ordinary-plan
 * halves of the same two issues. This file deliberately shares none of their types or names: every
 * cell below is a **distinct legacy render site**, not another type on a route already fixed.
 * Expected after the fix, exactly as on the ordinary plan: `@ref`, `@params`, and `error` renamed
 * to `error_`.
 *
 * One cell per legacy route, because a fixture trimmed to the easiest route would go green against
 * a fix that only covers that one:
 * - [fetch] is the top-level **suspend** route: the generated `Task<int>` wrapper plus its
 *   continuation-carrying import,
 * - [KeywordRoutes.load] is the **class suspend method** route, whose wrapper threads `_handle`
 *   as well as the parameter,
 * - [KeywordRoutes.watch] is the **Flow** route: the parameter is captured into the generated
 *   `KotlinFlow<int>` construction, a render site with no ordinary-plan equivalent at all,
 * - [KeywordRoutes.state] is the **MutableStateFlow** route, and the one cell that needs the #66
 *   rename rather than the #65 escape: the generated flow write lambda declares its own
 *   `out IntPtr error` local, so a user parameter named `error` collides inside a body the
 *   ordinary plan never emits,
 * - [KeywordRoutes.onEvent] and [KeywordRoutes.onFail] are the **lambda parameter** route, where
 *   the name lands on a `System.Action<KeywordTick>` declaration and on the thunk that invokes it.
 *   One cell per issue, since the two rules produce different spellings on the same route. The
 *   payload is an object ([KeywordTick]) so that the cell reads the keyword spelling and nothing
 *   else; the primitive payload has its own fixture in `Metronome`,
 * - [put] is the **generic top-level function** route, whose wrapper is emitted per instantiation,
 * - [KeywordHandler] is the **interface declaration** route: the C# `interface IKeywordHandler`
 *   member is printed by the interface renderer, not by the ordinary plan, so it is bare there,
 * - [KeywordHandlerImpl] is the **control** for that pair. Its `Handle` is a class method, which
 *   the ordinary plan already escapes (`string @params`), and it must stay escaped. It is also how
 *   C# reaches [KeywordHandler] at all,
 * - [make] is the **specialized sealed return** route, where the wrapper marshals the parameter and
 *   then hands the result to the ADR-009 `KeywordShape.FromHandle` discriminator.
 *
 * Dropped after measurement: the **abstract class** route (`abstract class KeywordBase { abstract
 * fun handle(params: String) }` plus a concrete subclass). It does not reach the keyword bug, and
 * it cannot compile for an unrelated reason. An `abstract fun` declared on an abstract Kotlin class
 * that inherits it from no interface is dropped entirely: no `keywordbase_handle` export and no C#
 * declaration on the base, while the subclass still renders `public override string Handle(...)`,
 * which is CS0115 with nothing to override. (`Animal.Speak` renders `public abstract string
 * Speak();` only because `Pet` declares it.) That defect is worth its own issue, and carrying it
 * here would keep `Interop.cs` red after this fix landed. (Fixed by the 2026-09-11 abstract-method
 * walk amendment to ADR-101/ADR-075, see `garage/Vehicle.kt`; this cell was never revived, so the
 * route above stays untouched here.)
 *
 * Every keyword used here (`ref`, `params`) is a legal Kotlin identifier without backticks and a C#
 * reserved word, so no cell needs Kotlin-side quoting to exist.
 *
 * Deliberately absent: the ordinary synchronous plan. It is #65/#66's original scope, it is already
 * fixed, and repeating it here would put a green cell in a red file and blur which route the
 * failure came from.
 *
 * The cats run dispatch. Oreo (black, white in the middle) takes every call; Mylo (brown and
 * creamy) is the reason there is an error slot at all.
 */
package io.github.xxfast.kotlin.native.nuget.test.routes

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow

/**
 * Top-level **suspend** route. Surfaces on the ADR-007 static class `KeywordRoutesSample` as a
 * `Task<int>`-returning wrapper whose declared parameter is copied straight from Kotlin.
 */
suspend fun fetch(ref: Int): Int = ref + 1

/**
 * Top-level **generic function** route. The wrapper is emitted per instantiation, so the keyword
 * name is repeated once per specialization rather than once per declaration.
 */
fun <T> put(ref: T): T = ref

/**
 * **Specialized sealed return** route: the wrapper marshals the keyword-named parameter and then
 * routes the result through the ADR-009 `KeywordShape.FromHandle` discriminator. Folds the argument
 * into the payload so a C# caller can see the value actually arrived.
 */
fun make(ref: Int): KeywordShape = Round(ref)

/** The sealed base [make] returns. */
sealed class KeywordShape

/** Oreo, curled up. The arm [make] produces, carrying the argument it was handed. */
data class Round(val r: Int) : KeywordShape()

/**
 * The payload the lambda-parameter cells carry. An object rather than an `Int` so those cells fail
 * only for the keyword reason under test: see [KeywordRoutes.onEvent].
 */
class KeywordTick(val n: Int)

/**
 * Carries the class-method legacy routes: suspend, Flow, MutableStateFlow and lambda parameters.
 */
class KeywordRoutes {
  /**
   * **Class suspend method** route. Its wrapper threads `_handle` alongside the keyword-named
   * parameter, so it is a second declaration site from [fetch]'s, not a repeat of it.
   */
  suspend fun load(params: String): String = params

  /**
   * **Flow** route. The keyword-named parameter is captured into the generated `KotlinFlow<int>`
   * construction site, which the ordinary plan never emits. Emits `1..3` offset by the argument's
   * length, so a dropped or defaulted argument is visible as the wrong sequence rather than an
   * empty one.
   */
  fun watch(params: String): Flow<Int> = flow {
    (1..3).forEach { emit(it + params.length) }
  }

  /** Backs [state]. See there for why the flow cannot be built on the fly. */
  private val states = mutableMapOf<Int, MutableStateFlow<Int>>()

  /**
   * **MutableStateFlow** route, and the #66 half of this file. The cell exists for the `error` to
   * `error_` rename: the generated flow write lambda declares its own `out IntPtr error` local, so
   * a user parameter named `error` rebinds inside a body the ordinary plan never emits (CS0136).
   * Seeds the flow with the argument so a C# caller can read it straight back out of `.Value`.
   *
   * Field-backed per key, because the ADR-071 route re-invokes this getter on every `.Value` read
   * and every `.Value` write rather than holding the flow it was first handed. Returning a fresh
   * `MutableStateFlow` per call would send each write into a throwaway, and the next read would
   * come back as the seed.
   */
  fun state(error: Int): MutableStateFlow<Int> = states.getOrPut(error) { MutableStateFlow(error) }

  /**
   * **Lambda parameter** route, #65 spelling. The name lands on a `System.Action<KeywordTick>`
   * declaration and on the generated thunk that invokes it. Invokes with `7` so the callback firing
   * at all is distinguishable from it firing with a default.
   */
  fun onEvent(ref: (KeywordTick) -> Unit) {
    ref(KeywordTick(7))
  }

  /**
   * The same **lambda parameter** route, #66 spelling: the callback itself is named `error`, next
   * to the exception slot the wrapper already declares. `9` rather than `7` so a test cannot pass
   * by reading [onEvent]'s value.
   */
  fun onFail(error: (KeywordTick) -> Unit) {
    error(KeywordTick(9))
  }
}

/**
 * **Interface declaration** route. The C# `interface IKeywordHandler` member is printed by the
 * interface renderer rather than by the ordinary plan, so the name arrives bare there
 * (`string Handle(string params);`) even though [KeywordHandlerImpl]'s class method beside it is
 * already escaped.
 */
interface KeywordHandler {
  fun handle(params: String): String
}

/**
 * The **control** for the interface route, and the only way C# reaches [KeywordHandler]. Its
 * `handle` is an ordinary class method, so it is already escaped as `string @params` today and must
 * stay that way: a fix that only moves the escape around is distinguishable from one that adds it
 * to the interface declaration.
 */
class KeywordHandlerImpl : KeywordHandler {
  override fun handle(params: String): String = params.uppercase()
}
