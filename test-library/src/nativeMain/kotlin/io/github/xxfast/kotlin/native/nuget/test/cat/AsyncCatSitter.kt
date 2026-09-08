package io.github.xxfast.kotlin.native.nuget.test.cat

import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay

/**
 * ADR-118 / ROADMAP line 54: the **same-arity** `suspend` overload pair, the cell that pins the
 * overload suffix onto `CirMethod.nativeName` and not only onto the `[DllImport]` EntryPoint.
 *
 * [AsyncCatService.fetchCat]'s pair differs in arity, so C# overload resolution would pick the
 * right private extern for the wrong reason even if only the EntryPoint were numbered. This pair
 * does not: both parameters cross the wire as a single `IntPtr` handle to a boxed wire container
 * (ADR-114's collection lowering), so both `Native_FeedAsync` calls have the identical argument
 * types. If the `_2` suffix reaches `CirDllImport.entryPoint` but misses `CirMethod.nativeName`,
 * the second body calls the first extern, **compiles**, runs, and answers the first overload's
 * string — a silent wrong answer, which is why the tests assert the values and not the presence.
 *
 * The two public C# signatures still differ (`IReadOnlyList<int>` vs `IReadOnlySet<string>`), so
 * the pair is a legal C# overload set. A pair of *object*-typed parameters (`feed(cat: Cat)` /
 * `feed(treat: Treat)`) would not be: the legacy suspend route maps every non-collection,
 * non-primitive parameter through `mapParamType`, whose fallback is `IntPtr`, so both overloads
 * would render `Task<string> FeedAsync(IntPtr, CancellationToken)` and collide with CS0111 inside
 * the generated file. Object parameters on the suspend route are a separate gap, not this one.
 *
 * Oreo eats by the numbers; Mylo will only accept a treat that has been called by name.
 */
class AsyncCatSitter(private val sitter: String) {
  /** First overload: portions, by count. */
  suspend fun feed(portions: List<Int>): String {
    delay(1.milliseconds)
    return "$sitter served ${portions.sum()} portions to Oreo"
  }

  /**
   * Second overload, same arity and the same `IntPtr` wire slot: `asynccatsitter_feed_2_async` /
   * `Native_Feed_2Async`. Its answer shares no substring shape with the first, so a mis-numbered
   * extern is unmistakable.
   */
  suspend fun feed(names: Set<String>): String {
    delay(1.milliseconds)
    return "$sitter called ${names.sorted().joinToString("+")} to the bowl"
  }
}
