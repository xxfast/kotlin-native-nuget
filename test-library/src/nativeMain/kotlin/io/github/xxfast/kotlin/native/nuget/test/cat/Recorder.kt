package io.github.xxfast.kotlin.native.nuget.test.cat

/**
 * Part A1 of the boundary-nullability item: a **null lambda argument** crossing the returned-lambda
 * route (`nuget_funcN_invoke`), plus a **null lambda result** coming back.
 *
 * Top-level on purpose. A class method that RETURNS a lambda is `SKIPPED_UNSUPPORTED_RETURN`
 * ("a lambda binds at a class-method parameter and a top-level function return, but not at this
 * position"), so a `class Recorder { fun signIn(): (String?) -> Unit }` fixture could never bind
 * and would test nothing. The route under test exists only here, at a top-level function return,
 * which is why these three declarations are file-level and the C# holder is the ADR-007 file class
 * `Recorder` (this file declares no type of that name, so it keeps the bare spelling).
 *
 * Two payload families, because they fail at different seams and a fixture trimmed to one of them
 * would go green against half a fix:
 *
 *  - [signIn] is the **reference** payload (`String?`). It needs a conversion at the boundary:
 *    the generated `WrapArg<T>` calls `nuget_wrap_string((string)null)`, and Kotlin's
 *    `export_nuget_wrap_string(value: String)` takes a NON-null `String`, so a null C string
 *    becomes a null reference inside a non-null parameter and the first use throws an uncatchable
 *    `NullPointerException` that takes the host process down (exit code 3, verified at a scratch
 *    mirror of the shipped exports). C# cannot `try/catch` it.
 *  - [describer] is the **value** payload (`Int?`). There is no conversion to get wrong, because
 *    today the null is not even expressible: the C# type argument is rendered `int`, not `int?`,
 *    so `KotlinFunc<int?, string>` does not exist and the test is red at C# compile time before it
 *    is red at runtime. That erasure is the consumer-visible half of the defect: `csTypeArgument`
 *    never reads `isMarkedNullable`, so `(String?) -> Unit` and `(String) -> Unit` are spelled
 *    identically with no diagnostic in between.
 *  - [finder] is the **null RESULT** leg, the mirror direction: `NugetHandles.retain(fn.invoke(p) as
 *    Any)` on a null result is the same uncaught `NullPointerException` inside a `@CName` export
 *    with no error slot.
 *
 * [lastSeen] and [lastSeenWasNull] exist so the assertion is that the KOTLIN lambda observed
 * `null`, not merely that `Invoke` returned. They are functions rather than a top-level `var`
 * because a top-level mutable property is a different route and would drag its own failure mode
 * into this cell; `lastSeenWasNull` separates "observed null" from the "unset" sentinel, which a
 * bare `lastSeen()` returning null cannot.
 *
 * Oreo goes out and comes back unannounced, so the recorder keeps seeing nothing at all. Mylo at
 * least signs in.
 */

private var seen: String? = "unset"

private var seenAtAll: Boolean = false

/** Records whatever the C# caller hands over, `null` included. */
fun signIn(): (String?) -> Unit = {
  seen = it
  seenAtAll = true
}

/** A nullable VALUE payload: `null` is a distinct answer from any `Int`, and says so. */
fun describer(): (Int?) -> String = { if (it == null) "no naps recorded" else "napped $it times" }

/** A nullable RESULT: only Oreo is ever found; everyone else comes back as `null`. */
fun finder(): (String) -> String? = { if (it == "Oreo") it else null }

/** What [signIn]'s lambda last observed, Kotlin-side. */
fun lastSeen(): String? = seen

/** Whether [signIn]'s lambda ran at all, so a `null` reading cannot be the initial state. */
fun signedInAtAll(): Boolean = seenAtAll
