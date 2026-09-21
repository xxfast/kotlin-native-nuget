package io.github.xxfast.kotlin.native.nuget.test.issue285

/**
 * Fixture for issue #285: a Kotlin enum entry's internal capitals are lost on the way to C#, so
 * `SecondValue` is only reachable as `Secondvalue`. The conversion lowercases every `_` segment
 * whole and then uppercases its first character, a rule ADR-006 wrote for `SCREAMING_SNAKE_CASE`
 * only. A C# consumer therefore cannot predict the member name from the Kotlin declaration.
 *
 * One entry per row of the research memo's casing table, so every shape the rule can meet crosses
 * once. Oreo (black with the white middle) and Mylo (brown and creamy) supply the flavour; the
 * SPELLING of each entry is the oracle, so the names stay exactly as the table writes them.
 *
 * Rows that MOVE when the fix lands:
 * - [SecondValue] (PascalCase, the issue's own report): `Secondvalue` today, `SecondValue` after,
 * - [ThirdValueHere] (PascalCase, three words): `Thirdvaluehere` today, `ThirdValueHere` after,
 * - [camelCase] (camelCase): `Camelcase` today, `CamelCase` after,
 * - [thirdValue] (camelCase, beside the Pascal [ThirdValueHere] so the two do not collide either
 *   before or after the fix): `Thirdvalue` today, `ThirdValue` after,
 * - [XMLParser_V2] (mixed: an all-caps run, an internal capital and a `_`): `XmlparserV2` today,
 *   `XMLParserV2` after. This is the row that rules out a whole-name gate: only a PER-SEGMENT rule
 *   keeps the acronym and still joins across the `_`.
 *
 * Rows that must NOT move, the negative controls. A fix that simply kept the Kotlin spelling
 * verbatim would satisfy every moving row above and break all of these:
 * - [First] (single Pascal word, already correct),
 * - [AB1C] (all caps with a digit): stays `Ab1c`. Indistinguishable from `HAPPY` to `Happy`, so
 *   preserving it would reverse ADR-006 and move every published enum. Decided, documented.
 * - [HAPPY_CAT] (SCREAMING_SNAKE, the shape ADR-006 was written for): stays `HappyCat`,
 * - [snake_case] (lowercase snake): stays `SnakeCase`,
 * - [HTTP_Status] (all-caps segment plus a Pascal segment): stays `HttpStatus`.
 *
 * Deliberately NOT here, and why:
 * - `_1ST` and `_` / `__`: today they generate `1st = 0` and an empty member name, which is
 *   illegal C# (CS1001) in `Interop.cs` itself, so they would break every consumer test file
 *   rather than this one. The memo assigns them to Tier 1 cells.
 * - Any pair that collides after casing (`FOO` + `Foo`, or `FOO_BAR` + `FooBar` under the new
 *   rule): that is a fatal generation error Tier 1 pins, and it would break `packNuget` here.
 * - An `is`-prefixed Boolean property on the enum: it aborts generation for an unrelated reason
 *   (memo finding 12, `Forward ABI missing Kotlin export for ..._get_is...`).
 *
 * No leak rows accompany this fixture: an enum crosses the C ABI as its ordinal `Int` in both
 * directions (ADR-006), so no `StableRef` handle is created and nothing can be leaked.
 */
enum class Example {
  First,
  SecondValue,
  ThirdValueHere,
  AB1C,
  HAPPY_CAT,
  snake_case,
  camelCase,
  thirdValue,
  XMLParser_V2,
  HTTP_Status,
}

/**
 * The enum at a parameter AND at a return, so the ordinal round-trips both ways across the same
 * call. The rename is a C#-side spelling only: this function must keep answering with the entry it
 * was handed whatever the C# member is called.
 */
fun echoExample(value: Example): Example = value

/**
 * The next entry in declaration order, wrapping at the end. A second ordinal route whose answer
 * differs from its argument, so a C# assertion cannot pass by accident on an identity shim.
 */
fun nextExample(value: Example): Example =
  Example.entries[(value.ordinal + 1) % Example.entries.size]

/**
 * The Kotlin `name` of an entry, which the C# spelling is allowed to differ from (ADR-006 excludes
 * the inherited `name` property, so `ToString()` answers the C# spelling). This lets one C# test
 * pin where the two agree (`SecondValue`) and where they still do not (`HAPPY_CAT` versus
 * `HappyCat`).
 */
fun exampleKotlinName(value: Example): String = value.name

/**
 * The `const val` twin of the same defect (memo finding 4): `translateConstProperty` uses the
 * identical expression, so `MaxRetries` is only reachable as `Maxretries` today. No issue was
 * filed for it; it ships with the enum fix because it is the same helper.
 *
 * Each `const val` is on its own line on purpose: a one-line `object`/companion body makes
 * `extractConstValue` capture to end of line and emit illegal C# (memo finding 12).
 */
object Issue285Limits {
  /** PascalCase: `Maxretries` today, `MaxRetries` after the fix. Oreo's nap retries. */
  const val MaxRetries: Int = 3

  /** SCREAMING_SNAKE control beside it: `MaxNaps` today AND after. Mylo's daily nap budget. */
  const val MAX_NAPS: Int = 7
}
