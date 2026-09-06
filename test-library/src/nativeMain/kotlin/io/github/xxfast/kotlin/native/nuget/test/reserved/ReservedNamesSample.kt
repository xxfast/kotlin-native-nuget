/**
 * Fixture for the rest of the generator-owned identifier family that
 * [#66](https://github.com/xxfast/kotlin-native-nuget/issues/66) left out. `error` was one name in
 * a set: the ordinary forward callable plan also owns `handle`, `receiver`, `value`, `errorOut`,
 * `valueOut` on the ABI, and `nativeResult` / `hasValue` as C# wrapper-body locals. A user Kotlin
 * parameter spelled like any of them collides today, and unlike #66 the collision is not always
 * C#-only: three of these names are declared by the Kotlin `@CName` emitter too, so the export
 * itself stops compiling.
 *
 * The seams, read off the shipped generated output rather than guessed:
 * - the instance extern's leading receiver slot is `IntPtr handle`, and the Kotlin export's is
 *   `handle: COpaquePointer`,
 * - the extension-function receiver is `receiver` on both sides
 *   (`Native_Meowify(string receiver, out IntPtr error)` / `export_string_meowify(receiver: String,
 *   errorOut: COpaquePointer?)`),
 * - a value-class receiver is `value` when the underlying is not a reference,
 * - a nullable return on a class member adds `out int valueOut` to the extern and
 *   `valueOut: COpaquePointer?` to the export, read back through the `bool hasValue` local (the
 *   top-level route fans out into two exports instead and uses `__nuget_`-prefixed locals),
 * - every non-void wrapper body opens with `T nativeResult = Native_...(...)`,
 * - the constructor wrapper body opens with `IntPtr handle = Native_Create(...)`.
 *
 * One cell per *mechanism*, not per type, because the failures are not the same compiler error and
 * a fixture trimmed to the cheapest one would go green against a fix that only covers that one:
 * - [Widget] is the **wrapper-body local** collision with no declaration-site duplicate: the
 *   constructor renders `IntPtr handle = Native_Create(handle, out IntPtr error)`, CS0136, and the
 *   synthesized `Copy` adds the declaration-site half on top (`Native_Copy(IntPtr handle, int
 *   handle, ...)`, CS0100). Kotlin is clean here, so this cell isolates the C# render,
 * - [Gadget.describe] is the **both-sides declaration** collision, three names at once: `handle`
 *   duplicates the instance receiver slot in the extern *and* in the Kotlin export, `nativeResult`
 *   rebinds the C# body local (CS0136), and `value` collides with nothing at all on this route and
 *   is here to pin the uniform rule (`value` is renamed on every callable, so the rename cannot be
 *   made conditional on a per-callable collision test that would be wrong for value classes),
 * - `String.tag` is the **extension receiver** collision, the one name that duplicates on both
 *   sides at the same position: `Native_Tag(string receiver, string receiver, out IntPtr error)`
 *   (CS0100) and `export_..._tag(receiver: String, receiver: String, errorOut: ...)` (a Kotlin
 *   duplicate parameter, which is a compile error, not a warning). It is the one cell that does not
 *   live in this file: the merged `StringExtensions` class takes the package of the first-visited
 *   `String` extension, so it sits beside the others in `cat/ReservedExtensions.kt`,
 * - [probe] is the **ADR-055 contract** cell, the only one that fails before anything renders: the
 *   forward ABI check reads a parameter's direction off its *name* (`errorOut` / `valueOut` are
 *   assumed to be the generator's own out-slots), so a user parameter of either name is projected
 *   as `out` on the Kotlin side and `in` on the C# side and the two projections stop agreeing.
 *   Underneath that, the Kotlin export also declares `errorOut` twice,
 * - [Dial.read] is the **`valueOut` slot and `hasValue` local** cell, and it has to be a class
 *   member rather than a top-level function: the top-level nullable route fans out into two
 *   exports with `__nuget_`-prefixed locals and produces neither name (measured, see [Dial]),
 * - [Ratio.scale] is the **`value` receiver slot**: a value class over a non-reference underlying
 *   passes its receiver in a slot named `value`, which is the one place the name really collides
 *   and therefore the reason the rule is uniform rather than conditional,
 * - [Meter] is the **control**. Its `value` is a property, and properties are PascalCased, so
 *   `Value` must stay exactly as it renders today. Its constructor *parameter* is the same word on
 *   the same declaration, and under the uniform rule that one moves. One type proving both halves
 *   is what distinguishes a rename scoped to parameters from a blanket rename of the identifier.
 *
 * Expected after the fix, same chain rule as #66 (`handle` -> `handle_`, `handle_` -> `handle__`,
 * so the shift stays injective without the renamer having to see its siblings): `handle`,
 * `receiver`, `value`, `errorOut`, `valueOut` renamed on **both** sides, and `nativeResult` plus
 * `hasValue` renamed on the C# side only, since neither is a name the Kotlin emitter declares.
 *
 * Deliberately absent: the specialized legacy routes (suspend, `Flow`, lambda, generic). Those are
 * `routes/KeywordRoutesSample.kt`'s territory, and every collision here is on the ordinary plan.
 * Also absent: any Kotlin-side workaround rename, exactly as `issue65/Issue65Sample.kt` and
 * `issue66/Issue66Sample.kt` refused theirs. The whole point is that a user gets to name a
 * parameter `value`.
 *
 * The cats supervise the hardware bench. Oreo (black, white in the middle) tests every handle by
 * batting it off the table; Mylo (brown and creamy) is the reason the meter reads what it reads.
 */
package io.github.xxfast.kotlin.native.nuget.test.reserved

/**
 * Wrapper-body local collision, C# only. The primary constructor parameter is named `handle`, and
 * the generated constructor body declares `IntPtr handle = Native_Create(handle, out IntPtr error)`
 * in the same scope (CS0136). The synthesized `Copy` is the second half: it is an instance
 * callable, so its extern already leads with the `IntPtr handle` receiver slot and a second
 * `handle` beside it is CS0100.
 *
 * `Int` rather than `String` so the cell cannot pass by accident through a string marshalling path,
 * and so the round-tripped value is exactly what was handed in.
 */
data class Widget(val handle: Int)

/** Three names on one ordinary instance method. See the file KDoc for why they share a cell. */
class Gadget {
  /**
   * `handle` duplicates the receiver slot on both sides, `nativeResult` rebinds the C# body local
   * the `String` return declares, and `value` collides with nothing here and is carried to pin the
   * uniform rule. The return folds all three together so a dropped, reordered or defaulted argument
   * is visible from C# as the wrong text rather than as a plausible one.
   */
  fun describe(handle: Int, value: String, nativeResult: Int): String =
    "$handle/$value/$nativeResult"
}

/**
 * ADR-055 contract collision, on the ordinary top-level route (so it lands on the ADR-007 static
 * class named after this file). This is the cell that fails earliest and hardest: the forward ABI
 * check infers a parameter's direction from its *name*, treating `errorOut` and `valueOut` as the
 * generator's own out-slots, so the Kotlin projection of this signature reads `out int, out int`
 * against the C# projection's `in int, in int` and KSP aborts before a single line is rendered.
 * Under that, the Kotlin export declares `errorOut` twice, once for the user and once for the
 * exception slot.
 *
 * The nullable `Int?` return is kept because it is what the top-level route fans out
 * (`probe_has_value` / `probe_value`), which is how the same collision reaches two exports rather
 * than one. The `valueOut` *slot* itself lives on the class route; see [Dial].
 *
 * Returns null for `errorOut == 0` so the absent path is crossed as well as the value one, and the
 * sum otherwise so both arguments have to survive to produce the right answer.
 */
fun probe(errorOut: Int, valueOut: Int): Int? = if (errorOut == 0) null else errorOut + valueOut

/**
 * The `valueOut` slot and the `hasValue` local, which only the **class-member** nullable route
 * produces. Measured, not assumed: a top-level function returning `Int?` fans out into two
 * exports (`probe_has_value` / `probe_value`) whose C# wrapper locals are already
 * `__nuget_`-prefixed, so it has no `valueOut` slot and no bare `hasValue` to collide with. A
 * class member keeps the single-export ADR-061 shape instead: `Native_Read(IntPtr handle,
 * int valueOut, int hasValue, out int valueOut, out IntPtr error)` (CS0100), read back through
 * `bool hasValue = ...` (CS0136), against a Kotlin export that declares `valueOut` twice.
 *
 * Returns null when `hasValue` is `0` so the absent path is crossed too, and the sum otherwise so
 * both arguments have to survive to produce the right answer.
 */
class Dial {
  /** See [Dial]. Named `read` so the C# member is `Read`, well clear of the generated helpers. */
  fun read(valueOut: Int, hasValue: Int): Int? = if (hasValue == 0) null else valueOut + hasValue
}

/**
 * The `value` **receiver slot**, and the cell the uniform `value` rule exists for. A value class
 * over a non-reference underlying passes its receiver in a slot the planner names `value`
 * (`Native_IsValid(string value)` on `CatId` today), so a member parameter of the same name is a
 * duplicate at that position: `Native_Scale(int value, int value)`, CS0100. [Gadget.describe] and
 * [Meter] are the other half of the rule, where `value` collides with nothing and still moves.
 *
 * The underlying property is `numerator`, not `value`, so this cell tests the slot rather than the
 * property: the slot is named `value` whatever the underlying is called. ADR-014 means a value
 * class member carries no exception slot, so this is also the one cell whose extern has no trailing
 * `out IntPtr error` at all.
 */
value class Ratio(val numerator: Int) {
  /** See [Ratio]. Multiplies so a swapped or dropped argument reads as the wrong number. */
  fun scale(value: Int): Int = numerator * value
}

/**
 * The control. `value` on a property renders `Value` (properties are PascalCased, so they never
 * meet the generator's lowercase identifiers) and must not move; the same word on the constructor
 * parameter is a parameter and does move under the uniform rule. Both are asserted, so a fix that
 * renames the identifier everywhere instead of only at parameter positions fails here.
 */
class Meter(val value: String)
