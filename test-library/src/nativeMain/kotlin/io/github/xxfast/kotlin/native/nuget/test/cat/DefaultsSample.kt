package io.github.xxfast.kotlin.native.nuget.test.cat

// ADR-091 fixture ("Constructor default parameters: JvmOverloads-style omitting overloads").
// Crosses every mechanism the Decision names, one shape per class, rather than the fewest types:
//
//   - [Carrier]     : TWO trailing defaults, so both suffix lengths (k=1 and k=2) are synthesized
//                     and the C# surface must be callable at all three arities.
//   - [Kennel]      : a MIDDLE default (a required parameter sits after it), so the JvmOverloads
//                     rule synthesizes nothing and the C# class must expose exactly ONE public
//                     constructor.
//   - [ScratchPost] : a SECONDARY constructor carrying a trailing default. The secondary's
//                     omitting overload is `(String, Int)`, deliberately distinct from the
//                     primary's `(String)` so this fixture stays generatable; the colliding shape
//                     (`Cat(name, lives = 9)` next to `constructor(name: String)`) is an
//                     ERROR_CSHARP_SIGNATURE_COLLISION case and belongs in the processor's Tier1
//                     table, not here.
//
// The class-with-ONE-trailing-default row is already covered by [Cat] (`lives: Int = 9`), and the
// `expect`/`actual` row (where `hasDefault` lives only on the expect) by
// `platform/PlatformApi.kt`'s `Beacon`. Every default value here is distinct and non-obvious, so
// an overload wired to the wrong Kotlin constructor shows up as a wrong value rather than a
// plausible one.

/**
 * Two trailing defaults: Oreo's travel carrier. Synthesizes `Carrier(string, int)` (k=1) and
 * `Carrier(string)` (k=2) alongside the full `Carrier(string, int, bool)`.
 */
class Carrier(
  val label: String,
  val size: Int = 3,
  val padded: Boolean = true,
) {
  fun describe(): String = "$label size $size ${if (padded) "padded" else "bare"}"
}

/**
 * Middle default: `capacity` has a required parameter after it, so a positional Kotlin call can
 * never skip it and NO omitting overload exists. Exactly one public C# constructor.
 */
class Kennel(
  val name: String,
  val capacity: Int = 10,
  val city: String,
) {
  fun describe(): String = "$name holds $capacity in $city"
}

/**
 * Secondary constructor carrying a trailing default. The primary is `(String)`; the secondary is
 * `(String, Int, Boolean = true)`, so exactly one omitting overload, `(String, Int)`, is
 * synthesized for it and nothing is synthesized for the primary.
 */
class ScratchPost(val label: String) {
  constructor(label: String, height: Int, sturdy: Boolean = true) :
      this("$label/${height}cm/${if (sturdy) "sturdy" else "wobbly"}")

  fun describe(): String = "scratch post $label"
}
