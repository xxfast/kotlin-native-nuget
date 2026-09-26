package io.github.xxfast.kotlin.native.nuget.test

const val MAX_LIVES: Int = 9
const val GREETING: String = "Hello, world!"
const val PI_APPROX: Double = 3.14
const val IS_DEBUG: Boolean = false

// ROADMAP line 25: a `const val` value is read out of the source text by a regex today, so every
// declaration below is either a case that emits illegal C# or a silently wrong value, or a case
// the regex already gets right that its replacement must keep. The C# side (`ConstValueTests`)
// asserts the exact value AND the exact C# type of each one, so this one owner walks every branch
// of the literal emitter: every Kotlin type in `KOTLIN_TO_CSHARP_PARAM` has a const here.

// --- every primitive type once ---

/** `Byte` to `sbyte`, negative. How many minutes early Oreo sits by the bowl. */
const val NAP_OFFSET: Byte = -8

/** `UByte` to `byte`. `255u` is a `uint` literal in C#, which does not convert to `byte`. */
const val FULL_BOWL: UByte = 255u

/** `Short` to `short`. The coldest tile Mylo will still nap on, in tenths of a degree. */
const val COLDEST_TILE: Short = -12

/** `UShort` to `ushort`. `65535u` is a `uint` literal in C#, which does not convert to `ushort`. */
const val KIBBLE_STASH: UShort = 65535u

/** `UInt` to `uint`, above `Int.MAX_VALUE`: a reader that unwraps to `Int` goes negative. */
const val TREATS_EVER: UInt = 4_000_000_000u

/** `Long` to `long` from an unsuffixed literal. Mylo's ninety-minute nap in milliseconds. */
const val NAP_MILLIS: Long = 5_400_000

/** `ULong` to `ulong` at `ULong.MAX_VALUE`: a reader that unwraps to `Long` gets `-1`. */
const val STARS_COUNTED: ULong = 18446744073709551615uL

/** `Float` to `float`. How creamy Mylo is, out of one. */
const val MYLO_CREAMINESS: Float = 0.75f

// --- integers ---

/** An infix shift is a compile-time constant: `8`, not the text of the shift. Two cats, 8 legs. */
const val LEG_COUNT: Int = 1 shl 3

/** Hex with underscores: `65535`. */
const val WHISKER_MASK: Int = 0xFF_FF

/** A negative literal. */
const val FLOOR_TEMP: Int = -3

/** `Int.MIN_VALUE` is `-2147483648`; C# has no name `Int`. */
const val FEWEST_NAPS: Int = Int.MIN_VALUE

/** `Long.MIN_VALUE` is `-9223372036854775808`, the literal-negation special case in C#. */
const val OLDEST_NAP: Long = Long.MIN_VALUE

// --- floating point specials ---

/** `Float.NaN`: C# spells it `float.NaN`, itself a `const`. What Oreo weighs after dinner. */
const val MYSTERY_WEIGHT: Float = Float.NaN

/** `Double.NEGATIVE_INFINITY`: C# spells it `double.NegativeInfinity`. */
const val COLDEST_FLOOR: Double = Double.NEGATIVE_INFINITY

// --- chars ---

/** A plain char. */
const val OREO_INITIAL: Char = 'O'

/** A newline escape. */
const val NEWLINE_MEOW: Char = '\n'

/** An escaped single quote. */
const val APOSTROPHE: Char = '\''

/** An escaped backslash. */
const val BACKSLASH: Char = '\\'

// --- strings ---

/** Underscores INSIDE a string are part of the value; today every underscore is stripped. */
const val SNAKE_TOY: String = "snake_case_value"

/** A template over another const: the value is `vsnake_case_value`, not the template text. */
const val TOY_TAG: String = "v$SNAKE_TOY"

/** A closing brace inside a string must not end anything. */
const val BRACED: String = "Oreo } Mylo"

/** A comment marker inside a string is not a comment. */
const val VET_URL: String = "http://vet.example/oreo"

/** An escaped dollar: the value is `cost $5`, and the Kotlin escape is not a C# escape. */
const val TREAT_PRICE: String = "cost \$5"

/** An escaped quote and an escaped backslash, both of which C# must re-escape. */
const val QUOTED_PURR: String = "Mylo said \"mrrp\" \\ twice"

/** Concatenation of two literals is evaluated: `OreoMylo`. */
const val BOTH_CATS: String = "Oreo" + "Mylo"

/** A multi-line raw string is a constant too; its line break is part of the value. */
const val ROLL_CALL: String = """Oreo: black
Mylo: brown"""

/** The value on the line after the equals sign. */
const val WRAPPED_GREETING: String =
  "Mylo, dinner!"
