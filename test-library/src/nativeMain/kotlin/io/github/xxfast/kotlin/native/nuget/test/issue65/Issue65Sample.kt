package io.github.xxfast.kotlin.native.nuget.test.issue65

/**
 * Fixture for [#65](https://github.com/xxfast/kotlin-native-nuget/issues/65): a Kotlin *parameter*
 * whose name is a C# reserved keyword is emitted verbatim into `Interop.cs`.
 *
 * `csharpIdentifier()` (`ForwardCallablePlanner.kt:1463`) `@`-escapes a C# keyword, but it is only
 * ever applied to the *method* name (`ForwardCallablePlanner.kt:549`); every `parameter.name` use
 * site in `ForwardCallablePlanner.kt`/`ForwardCirPlanProjection.kt` passes the raw Kotlin
 * identifier straight through. So a parameter named `abstract` renders as `string abstract` on both
 * the public wrapper and the `[DllImport]` extern, and the generated file does not compile at all
 * (CS1001/CS1041) — the failure is at generation time, not at runtime. Precedent: ADR-077's
 * `Patient.reassign` was declared `fun reassign(ref: ChartRef)`, hit exactly this, and was renamed
 * to `referral` to dodge it. That rename is the reporter's workaround; this fixture refuses it.
 *
 * Scope is the **ordinary synchronous forward callable plan** only. Each cell is a distinct *render
 * path* for a parameter name, not just another type:
 * - [Issue65Article] primary constructor `abstract` — constructor declaration site (wrapper +
 *   extern) plus the bare use site `Native_Create(abstract, title, ...)`,
 * - [Issue65Article.describe] `default` — plain scalar method parameter. This is the cell that
 *   catches a *half* fix: `Native_Describe(_handle, default, out ...)` is **valid C#** (`default`
 *   is the default-value literal, `0` for `Int`), so escaping only the declaration site compiles
 *   and silently passes `0`. The return folds the argument into the string so the C# test can see
 *   the difference between `7` and `0`,
 * - [Issue65Article.tag] `params` — collection parameter, whose render path adds the marshalling
 *   locals (`paramsHandle`) and the `NugetMarshal.CreateList(params)` use site,
 * - [issue65Greet] `string` — top-level function, which surfaces on the static class named after
 *   this file (ADR-007),
 * - [issue65Byline] `ref` — object-typed parameter, whose use site is a member access *on* the
 *   parameter (`ref._handle`). This is literally the ADR-077 shape that was renamed away.
 *
 * Every name here is a C# keyword and a legal Kotlin identifier without backticks. Deliberately
 * absent: `handle`, `value`, `receiver`, `error`, `errorOut`, `valueOut` — those collide with the
 * generator's own locals, which is the separately tracked #66, and would muddy this signal. Also
 * absent: suspend, `Flow`, lambda, sealed and generic parameter shapes; the bug is on the shared
 * ordinary plan and does not need them.
 *
 * The cats supply the copy desk: Oreo files the piece, Mylo writes the abstract and sleeps on it.
 */
data class Issue65Article(val abstract: String, val title: String) {
  /**
   * Plain scalar keyword parameter. `default` must reach the native side intact: the returned
   * string embeds it, so a C# caller passing `7` and receiving `... x0` proves the generator
   * emitted the C# `default` *literal* at the call site instead of the escaped parameter.
   */
  fun describe(default: Int): String = "$title x$default"

  /**
   * Keyword-named **collection** parameter: a different render path from a scalar, because the
   * generated wrapper allocates `paramsHandle`, calls `NugetMarshal.CreateList(params)` and
   * disposes it in a `finally`. Returns a value derived from the contents so an empty or dropped
   * list is visible from C#.
   */
  fun tag(params: List<String>): Int = params.sumOf { it.length }
}

/**
 * Top-level function with a keyword-named parameter, reached from C# as
 * `Issue65Sample.issue65Greet(...)` (top-level function names are not PascalCased).
 */
fun issue65Greet(string: String): String = "Meow, $string"

/**
 * Top-level function with an **object-typed** keyword parameter — the ADR-077
 * `reassign(ref: ...)` shape. Its generated use site is a member access on the parameter
 * (`ref._handle`), which no other cell here exercises.
 */
fun issue65Byline(ref: Issue65Article): String = "${ref.title} by Oreo"
