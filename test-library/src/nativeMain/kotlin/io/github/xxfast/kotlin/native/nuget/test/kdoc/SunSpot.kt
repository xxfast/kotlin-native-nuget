package io.github.xxfast.kotlin.native.nuget.test.kdoc

// ADR-150 fixture: the `expect`/`actual` row, laid out the way the ADR-074 fixture
// (`test/platform`) lays one out: the KDoc lives on the `expect` here in `nativeMain`, both
// `actual`s are bare, and the per-target files are named differently from each other. Per spike 1
// finding 5 the `actual`'s own `docString` is null, so the generated C# carries this text only if
// the expect is consulted.

// The rows below cross the rest of the `expect`/`actual` doc seam, one documented declaration per
// mechanism, so `IntegrationTests/XmlDocTests.cs` can pin each of them separately:
//
//   - a documented PROPERTY of the `expect class` (SunSpot.warmth): the class row above documents a
//     type only, so nothing catches a property route that never consults the expect index
//   - an OVERLOADED pair of documented top-level `expect fun`s with the same parameter COUNT and
//     different parameter types (basking): distinct summaries mean a lookup that collapses the two
//     (by name, or by count alone) lands the wrong text on at least one of them. Taken together the
//     pair pins count + name + type, not type alone.
//   - a documented top-level `expect val` (baskingTag)
//   - a documented top-level function returning a NULLABLE primitive (sunnyNapMinutes),
//     deliberately NOT `expect`: it isolates the two-call nullable-primitive route from the
//     expect index
//   - a documented `expect fun` that is an EXTENSION (SunSpot.stretch)
//   - a documented `expect` declaration that is not a class (object SunLounge)
//
// Every `actual` for these stays BARE of KDoc on purpose: `forwardKdoc` is `docString ?: expect's
// doc`, so a KDoc on an `actual` MASKS the text asserted here (observable today -- the mingw
// `actual fun nuzzle` in `test/platform` puts its own text on the generated C#).

/** Oreo's sunny window perch. */
expect class SunSpot {

  /** How warm the perch is right now. */
  val warmth: String

  fun sunbeam(): String
}

/**
 * Finds where a cat is basking.
 *
 * @param cat which cat
 * @return the sunny spot
 */
expect fun basking(cat: String): String

/**
 * Finds who has basked this long.
 *
 * @param minutes how long
 * @return who was found
 */
expect fun basking(minutes: Int): String

/** The label stitched onto Oreo's favourite perch. */
expect val baskingTag: String

/**
 * How many minutes Mylo napped in the sun, when anyone was counting.
 *
 * @return the minutes, or null when nobody kept count
 */
fun sunnyNapMinutes(): Int? = 14

/**
 * Stretches out across the whole spot.
 *
 * @return how the stretch went
 */
expect fun SunSpot.stretch(): String

/**
 * Stretches out for a while.
 *
 * ADR-096 measurement, not just a doc fixture: the default lives on the `expect` half (Kotlin
 * forbids the `actual` from restating it), and the extension route reads the exported
 * declaration's own `hasDefault` bits only. So exactly ONE C# overload exists, taking `minutes`;
 * there is deliberately no parameterless one, and the `= 5` never reaches a C# caller.
 *
 * @param minutes how long to stretch
 * @return how the stretch went
 */
expect fun SunSpot.stretchFor(minutes: Int = 5): String

/** Where the whole household suns itself. */
expect object SunLounge {
  fun loungers(): Int
}
