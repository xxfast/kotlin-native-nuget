package io.github.xxfast.kotlin.native.nuget.test.issue66

/**
 * Fixture for [#66](https://github.com/xxfast/kotlin-native-nuget/issues/66): a Kotlin *parameter*
 * literally named `error` collides with the generator's own exception slot.
 *
 * Every generated synchronous P/Invoke carries a trailing `out IntPtr error` (ADR-024), and both
 * the `[DllImport]` extern and the public wrapper declare it under that fixed name. A user
 * parameter called `error` therefore renders
 * `Native_Create(string? error, string title, int edition, out IntPtr error)` — CS0100, a duplicate
 * parameter name — and in the wrapper body `IntPtr handle = Native_Create(error, ..., out IntPtr
 * error);` — CS0136, a local that conflicts with the meaning of `error` in the enclosing scope. The
 * failure is at *generation* time: `Interop.cs` does not compile at all, so nothing downstream of
 * it builds either. The Kotlin export side is already safe — its slot is named `errorOut` — so the
 * fix is a C#-render-time rename of the *user* parameter (`error` -> `error_`), not a Kotlin one.
 *
 * Precedent that this is a *parameter* bug and not a *property* one: `cat/Issue38Sample.kt` already
 * ships `data class Loaded(val error: String?, ...)` and it renders fine as
 * `public string? Error => ...`. It survives only because a sealed subclass gets no public
 * constructor and no `Copy` — i.e. no callable ever declares `error` next to the exception slot.
 * The reporter's workaround was renaming the Kotlin property to `failure`; this fixture refuses it,
 * exactly as `issue65/Issue65Sample.kt` refused ADR-077's `ref` -> `referral` rename.
 *
 * Scope is the **ordinary synchronous forward callable plan** only. Each cell is a distinct *render
 * path* for the name `error`, not just another type:
 * - [Issue66StoryState] primary constructor — the declaration sites (extern + wrapper) plus the
 *   bare use site `Native_Create(error, title, edition, out IntPtr error)`, and, because `edition`
 *   carries a trailing default, a synthesized omitting overload `Native_Create_2(error, title)`
 *   that *still carries* `error` and so must be renamed too,
 * - the synthesized [Issue66StoryState] `Copy(error, title, edition)` — the second half of the
 *   `Native_Create` / `Native_Copy` pair the issue quotes,
 * - [Issue66StoryState.describe] — plain scalar (non-nullable `String`) method parameter, the
 *   simplest use site, on an instance callable that also passes `_handle`,
 * - [Issue66StoryState.count] — **collection** parameter, whose render path adds the marshalling
 *   locals (`errorHandle`) and a `NugetMarshal.CreateList(error)` use site inside a try/finally,
 * - [issue66Summarise] — top-level function (so it lands on the static class named after this file,
 *   ADR-007) with an **object-typed** parameter, whose use site is a member access *on* the
 *   parameter (`error._handle`) rather than a bare read.
 *
 * Deviation from the reporter's literal signature, on purpose: the issue shows
 * `data class StoryState(val error: String? = null, /* ... */)`. A leading default with a required
 * parameter after it synthesizes **no** omitting overload (`cat/DefaultsSample.kt`'s `Kennel`
 * documents that rule), so the literal two-parameter shape would leave the overload render path
 * uncrossed. Adding a trailing `edition: Int = 3` keeps `error`'s exact declared shape — first,
 * nullable, defaulted — and buys the `Native_Create_2` path, which still contains `error`. The
 * default is `3` rather than `0` so an overload wired to the wrong Kotlin constructor shows up as a
 * wrong value instead of a plausible one.
 *
 * Deliberately absent: `handle`, `value`, `receiver`, `errorOut`, `valueOut` — the rest of the
 * generator's own local/slot family, tracked separately — and C# keywords as parameter names, which
 * is #65 and is fixed on this branch. Also absent: suspend, `Flow`, lambda and generic shapes; the
 * collision is on the shared ordinary plan and does not need them.
 *
 * The cats run the newsdesk again: Oreo (black, white in the middle) files copy, Mylo (brown and
 * creamy) is the one thing that keeps going wrong.
 */
data class Issue66StoryState(
  val error: String? = null,
  val title: String,
  val edition: Int = 3,
) {
  /**
   * Scalar use site: a non-nullable `String` parameter named `error`, folded into the return so a
   * C# caller can see the argument actually arrived instead of being dropped or defaulted.
   */
  fun describe(error: String): String = "$title: $error"

  /**
   * Collection use site: the wrapper allocates `errorHandle`, calls `NugetMarshal.CreateList` and
   * disposes it in a `finally`. Returns a value derived from the contents so an empty or dropped
   * list is visible from C# as `0`.
   */
  fun count(error: List<String>): Int = error.sumOf { it.length }
}

/**
 * Top-level function with an **object-typed** parameter named `error`, reached from C# as
 * `Issue66Sample.issue66Summarise(...)` (top-level function names are not PascalCased). Its
 * generated use site is a member access on the parameter, `error._handle`.
 */
fun issue66Summarise(error: Issue66StoryState): String =
  "${error.title} #${error.edition} (${error.error ?: "no error"})"
