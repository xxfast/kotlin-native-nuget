package io.github.xxfast.kotlin.native.nuget.sample.structs

import sample.enums.CatMood
import sample.structs.Collar
import sample.structs.Collars
import sample.structs.Extent

// ADR-058: the Shape B mirror of StructsSample.kt's Shape A fixture surface — a C# struct with NO
// public constructor (public fields / settable auto-properties) crosses the reverse bridge exactly
// like a Shape A struct, except the C# side reconstructs it with an object initializer instead of a
// constructor call. Extent is field-only (Width, Height, both `int`, direct); Collar is MIXED (a
// public field, a `set` auto-prop, two `init`-only auto-props), spanning the full v1 component
// vocabulary: int (direct) and string/bool/char/enum (converted).
//
//   C# SampleApp.Tests
//     -> (forward bridge, Interop.cs)        CollarSample.* functions in this file
//       -> Kotlin sample-library             CollarSample.kt (this file)
//         -> (reverse bridge, ADR-058)       sample.structs.{Extent,Collar,Collars}
//           -> real C# SampleDependency NuGet Sample.Structs.{Extent,Collar,Collars}
//
// `initialCode: Int` mirrors StructsSample.kt's `gradeCode` workaround: a raw Kotlin `Char`
// parameter cannot cross the FORWARD boundary today (CirTypeMapping.kt's KOTLIN_TO_CSHARP_PARAM
// table has no `Char` entry and silently falls back to IntPtr — a real, separate ADR-006/048 bug,
// unrelated to this feature). Like StructsSample.kt, this file forward-exports plain types, never
// the struct itself: mapping a multi-component data class across the FORWARD boundary is
// ADR-014's concern (single-component value classes only) and out of scope here.
//
// Component order: Collar is girth, colour, belled, initial, mood; Extent is width, height — both
// C# declaration order (ADR-058 Decision 3a).

// Shape B struct as a PARAMETER (object-initializer reconstruction on the C# side), non-struct
// return. Exercises every Collar component: field (Girth), settable auto-props (Colour, Mood),
// init-only auto-props (Belled, Initial).
fun describeCollar(
  girth: Int,
  colour: String,
  belled: Boolean,
  initialCode: Int,
  mood: CatMood,
): String = Collars.describe(Collar(girth, colour, belled, initialCode.toChar(), mood))

// Shape B struct in AND out (out-pointer return of a mixed-vocabulary struct): Collars.loosen
// reconstructs the receiver (object initializer) and returns a new Collar (out-pointers).
fun loosenCollar(
  girth: Int,
  colour: String,
  belled: Boolean,
  initialCode: Int,
  mood: CatMood,
): String {
  val loosened: Collar = Collars.loosen(Collar(girth, colour, belled, initialCode.toChar(), mood))
  return "${loosened.girth},${loosened.colour},${loosened.belled}," +
      "${loosened.initial.code},${loosened.mood}"
}

// TWO Shape B structs of DIFFERENT sub-shapes (Collar: mixed sources; Extent: pure fields) in one
// signature — this is where an abiArgs expansion bug would show up. Collars.pair does its own C#
// string formatting, so this is a plain pass-through.
fun pairCollar(girth: Int, colour: String, width: Int, height: Int): String =
  Collars.pair(Collar(girth, colour, true, 'X', CatMood.CALM), Extent(width, height))

// Extent: a computed property (Area), an instance method returning the struct itself (Grow), and
// a static factory (Unit) -> Kotlin companion object.
fun extentMembers(width: Int, height: Int): String {
  val grown: Extent = Extent(width, height).grow(2)
  return "${Extent(width, height).area}|${grown.width}x${grown.height}|${Extent.unit().area}"
}

// Collar members: two computed properties (Label: string, IsLoud: bool), an instance method
// returning the struct (Resize), and a static factory (Plain) -> Kotlin companion object.
fun collarMembers(girth: Int, colour: String, mood: CatMood): String {
  val c = Collar(girth, colour, true, 'B', mood)
  return "${c.label}|${c.isLoud}|${c.resize(1).girth}|${Collar.plain(colour).label}"
}

// Decision 2a, restated for a struct whose C# original is MUTABLE (settable fields/props): value
// equality and copy() survive the crossing exactly as they do for the immutable Shape A structs.
// There is nothing to close() and no reference identity to leak.
fun collarValueEquality(): Boolean {
  val a = Collar(1, "red", true, 'A', CatMood.PLAYFUL)
  return a == a.copy() && a != a.copy(girth = 2)
}

// The documented defence against the ADR-058 Decision 3 declaration-order hazard: named
// arguments. Compiles (and produces the components in the right slots) only if the generated
// component names are exactly girth, colour, belled, initial, mood — the lower-camel form of the
// C# member names, in C# declaration order.
fun collarNamedArgs(): String {
  val c: Collar =
    Collar(girth = 1, colour = "black", belled = true, initial = 'O', mood = CatMood.CALM)
  return "${c.girth},${c.colour},${c.belled},${c.initial},${c.mood}"
}
