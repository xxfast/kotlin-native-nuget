package io.github.xxfast.kotlin.native.nuget.test.lineage

// ROADMAP Phase 4, "`interface Derived : Base` flattens", the diamond. `Moggy : Whiskered, Tailed`
// inherits `whiskers` and `blink` from BOTH supers (Kotlin merges them into one member without an
// override) and must override `twitch`, which both default. On the C# side the base list alone
// leaves `moggy.Twitch()` ambiguous (CS0121), and the ADR-084 bridge reads `impl.Twitch()` off
// `IMoggy`, so `IMoggy` redeclares all three with `new`. The consumer build compiles this file's
// bindings with warnings as errors, which is what proves the `new` is right (a bare redeclaration
// is CS0108). `Cushion.settle` puts `Moggy` at a PARAMETER position so the bridge is generated.

/** One of two unrelated supers declaring the same members. */
interface Whiskered {
  /** Declared here and on [Tailed]: Kotlin merges them on [Moggy] with no override. */
  val whiskers: Int

  /** Same merge, as a function. */
  fun blink(): Int

  /** Defaulted here and on [Tailed], so [Moggy] must override it. */
  fun twitch(): String = "Whiskers twitch"
}

/** The second super, declaring the same three members. */
interface Tailed {
  /** See [Whiskered.whiskers]. */
  val whiskers: Int

  /** See [Whiskered.blink]. */
  fun blink(): Int

  /** See [Whiskered.twitch]. */
  fun twitch(): String = "Tail swishes"
}

/** The diamond: `IMoggy : IWhiskered, ITailed` redeclares the shared members with `new`. */
interface Moggy : Whiskered, Tailed {
  /** The override Kotlin forces. */
  override fun twitch(): String = "Oreo twitches his whiskers and swishes his tail"

  /** Own member. */
  fun nap(): String
}

/** Oreo as a moggy: returned, so the ADR-040 backing wrapper implements every member once. */
fun adoptMoggy(): Moggy = object : Moggy {
  override val whiskers: Int = 24
  override fun blink(): Int = 2
  override fun nap(): String = "Oreo naps on the white bit"
}

/** Takes the diamond at a parameter position, so the ADR-084 bridge reads every member off it. */
class Cushion {
  /** Every shared member, read through the derived interface. */
  fun settle(moggy: Moggy): String =
    "${moggy.whiskers} whiskers, ${moggy.blink()} blinks, ${moggy.twitch()}, ${moggy.nap()}"
}
