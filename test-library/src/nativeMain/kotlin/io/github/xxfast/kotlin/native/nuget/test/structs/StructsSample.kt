package io.github.xxfast.kotlin.native.nuget.test.structs

import test.enums.CatMood
import test.structs.Cattery
import test.structs.Cattery2
import test.structs.Geometry
import test.structs.Metrics
import test.structs.Point
import test.structs.Profile

// ADR-056: the full v1 fixture surface — int (Point), long/float/double (Metrics), and
// string/bool/char/bound-enum (Profile, via Cattery2 and Cattery.currentProfile) — and the method
// shapes ADR-056 Scope calls out: struct param + struct return, struct param + non-struct return,
// two struct params in one signature, an INSTANCE method on a bound class taking/returning a
// struct, and a SETTABLE struct-typed property.
//
// Plus ADR-056 deferred "struct methods and computed properties" (ADR-014 reconstruct-on-call):
// Point.{magnitude,offset,format,origin} and Profile.{label,isPlayful,withMood,resting} live on the
// data class / companion, not free-function hosts.
//
//   C# IntegrationTests
//     -> (forward bridge, Interop.cs)  StructsSample.* functions in this file
//       -> Kotlin test-library       StructsSample.kt (this file)
//         -> (reverse bridge, ADR-056) test.structs.{Point,Metrics,Profile,Geometry,Cattery,
//                                       Cattery2}
//           -> real C# TestDependency NuGet Test.Structs.{Point,Metrics,Profile,Geometry,
//                                       Cattery,Cattery2}
//
// The forward-exported functions stay in the existing String/Int/Long/Float/Double/Boolean/enum
// vocabulary (ADR-048/006) rather than exporting a struct itself: mapping a plain multi-field data
// class forward is ADR-014's concern (single-component value classes only) and out of scope here.
// CatMood itself forward-exports directly (mirrors CatMoodSample.kt's advanceMood), so it is used
// as-is rather than smuggled across as an Int ordinal.

fun translatePointDescription(x: Int, y: Int, dx: Int, dy: Int): String {
  val moved: Point = Geometry.translate(Point(x, y), dx, dy)
  return "${moved.x},${moved.y}"
}

// Struct parameter, non-struct (String) return.
fun describePoint(x: Int, y: Int): String = Geometry.describe(Point(x, y))

// TWO struct parameters in one signature.
fun manhattanDistance(x1: Int, y1: Int, x2: Int, y2: Int): Int =
  Geometry.manhattan(Point(x1, y1), Point(x2, y2))

// Decision 2a, made concrete: value equality, copy(), and structural (not reference) equality
// across two INDEPENDENT bridge calls returning "the same" Point. Two Point values built from two
// separate Geometry.translate() invocations with identical inputs must compare equal by content —
// proving the Kotlin data class is copied by value at the boundary, not reference-shared.
fun pointValueEqualityRoundTrip(): Boolean {
  val a = Point(3, 4)
  val b = a.copy()
  val c = a.copy(x = 99)
  val translatedOnce: Point = Geometry.translate(a, 1, 1)
  val translatedAgain: Point = Geometry.translate(a, 1, 1)
  return a == b &&
      a.hashCode() == b.hashCode() &&
      a != c &&
      translatedOnce == translatedAgain &&
      translatedOnce.hashCode() == translatedAgain.hashCode()
}

// Instance method (on a bound ADR-051 handle class) taking and returning a struct whose
// components are long/float/double — the "pass-through" component vocabulary beyond Point's
// plain ints, and the "instance members of bound classes" half of ADR-056's v1 Scope (Geometry's
// methods above are all static). Cattery itself is a real C# class, so — unlike Point/Metrics —
// it needs `close()`; the try/finally here is the deliberate contrast with the value types this
// file otherwise never closes.
fun weighCat(
  name: String,
  heartRateBpm: Long,
  weightKg: Float,
  temperatureC: Double,
  factor: Float,
): String {
  val cattery = Cattery(name)
  try {
    val result: Metrics = cattery.weigh(Metrics(heartRateBpm, weightKg, temperatureC), factor)
    return "${cattery.name}:${result.heartRateBpm},${result.weightKg},${result.temperatureC}"
  } finally {
    cattery.close()
  }
}

// Decision 2a again, this time with a mixed-type (long/float/double) struct returned from an
// INSTANCE method: two separate calls with identical inputs must return equal Metrics values.
fun metricsValueEqualityRoundTrip(
  name: String,
  heartRateBpm: Long,
  weightKg: Float,
  temperatureC: Double,
  factor: Float,
): Boolean {
  val cattery = Cattery(name)
  try {
    val a: Metrics = cattery.weigh(Metrics(heartRateBpm, weightKg, temperatureC), factor)
    val b: Metrics = cattery.weigh(Metrics(heartRateBpm, weightKg, temperatureC), factor)
    return a == b && a.hashCode() == b.hashCode()
  } finally {
    cattery.close()
  }
}

// Struct PARAMETER with the string/bool/char/enum component vocabulary, on a STATIC method —
// Cattery2.Announce(Profile): string. `grade` crosses the FORWARD boundary as an Int code point,
// not a raw Kotlin Char: CirTypeMapping.kt's KOTLIN_TO_CSHARP_PARAM table has no entry for "Char"
// and silently falls back to IntPtr — an 8-byte pointer marshal for what is actually a 2-byte
// Kotlin Char at the C ABI. That is a real, separate forward-direction (ADR-006/048) bug, unrelated
// to ADR-056; the REVERSE struct's own Char component (Profile.grade) is unaffected. Converting
// here keeps this file's assertions about the reverse struct, not about the unrelated forward Char
// gap.
fun announceProfile(tag: String, active: Boolean, gradeCode: Int, mood: CatMood): String =
  Cattery2.announce(Profile(tag, active, gradeCode.toChar(), mood))

// Struct PARAMETER and RETURN with the same vocabulary — Cattery2.Promote(Profile): Profile.
// Returns a formatted string (not the struct itself — forward-exporting a plain multi-field data
// class is ADR-014's concern, single-component value classes only, and out of scope here) so the
// C# test can assert every component round-tripped, including the enum value Promote deliberately
// changes (-> PLAYFUL). `gradeCode`: see announceProfile's note on the forward Char gap.
fun promoteProfile(tag: String, active: Boolean, gradeCode: Int, mood: CatMood): String {
  val promoted: Profile = Cattery2.promote(Profile(tag, active, gradeCode.toChar(), mood))
  return "${promoted.tag},${promoted.active},${promoted.grade.code},${promoted.mood}"
}

// Decision 2a with the full (string/bool/char/enum) component vocabulary: two independently
// constructed Profile values with identical components must compare equal by value, and a changed
// component must compare unequal.
fun profileValueEqualityRoundTrip(): Boolean {
  val a = Profile("Oreo", true, 'A', CatMood.PLAYFUL)
  val b = a.copy()
  val c = a.copy(active = false)
  return a == b && a.hashCode() == b.hashCode() && a != c
}

// A SETTABLE struct-typed PROPERTY on a bound class (Cattery.currentProfile). Reads the
// constructor default (out-pointer reconstruction through the getter), writes a new value
// (decomposed parameters through the setter), then reads it back to prove the write actually took.
fun catteryCurrentProfileRoundTrip(
  name: String,
  tag: String,
  active: Boolean,
  gradeCode: Int,
  mood: CatMood,
): String {
  val cattery = Cattery(name)
  try {
    val before: Profile = cattery.currentProfile
    cattery.currentProfile = Profile(tag, active, gradeCode.toChar(), mood)
    val after: Profile = cattery.currentProfile
    return "${before.tag},${before.active},${before.grade.code},${before.mood}" +
        "|${after.tag},${after.active},${after.grade.code},${after.mood}"
  } finally {
    cattery.close()
  }
}

// --- ADR-056 deferred: struct methods + computed properties (ADR-014 reconstruct-on-call) ---
// Members live on the generated data class (and companion), not free functions. Wire form: N
// component args first (C# thunk rebuilds `new Point(x,y)`), then ordinary args; struct returns
// stay void + out-pointers. Sample-library will not compile against these until kotlin-dev lands
// the reader/generator work. That is the intended TDD failure mode.

// Computed property on Point that is NOT a component (components are x/y).
fun pointMagnitude(x: Int, y: Int): Int = Point(x, y).magnitude

// Instance method returning a struct (out-pointers), then Format for a forward-friendly string.
fun offsetPoint(x: Int, y: Int, dx: Int, dy: Int): String = Point(x, y).offset(dx, dy).format()

// Static method on Point → companion object, then instance Format.
fun pointOriginFormat(): String = Point.origin().format()

// Computed string property on Profile (string conversion, non-component).
fun profileLabel(tag: String, active: Boolean, gradeCode: Int, mood: CatMood): String =
  Profile(tag, active, gradeCode.toChar(), mood).label

// Instance method returning Profile (full component vocabulary on reconstruct + out-pointers),
// then Label so the forward surface stays plain types. Appends isPlayful to force the bool
// computed-property seam on the reconstructed result.
fun profileWithMood(
  tag: String,
  active: Boolean,
  gradeCode: Int,
  mood: CatMood,
  newMood: CatMood,
): String {
  val updated: Profile = Profile(tag, active, gradeCode.toChar(), mood).withMood(newMood)
  return "${updated.label}|${updated.isPlayful}"
}

// Static factory on Profile → companion object, then Label.
fun profileRestingLabel(tag: String): String = Profile.resting(tag).label
