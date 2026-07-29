package io.github.xxfast.kotlin.native.nuget.test.boxes

import test.boxes.Box
import test.boxes.Boxes
import test.boxes.Crate
import test.boxes.Pairing
import test.enums.CatMood

// ADR-072: closed constructed generics from C# in Kotlin. C# declares `Box<T>`, `Pairing<TKey,
// TValue>` and `Crate<T>`; Kotlin consumes them as real generic classes, not a monomorphized
// family (Decision 1).
//   C# IntegrationTests
//     -> (forward bridge, Interop.cs)   BoxesSample.*
//       -> Kotlin test-library          BoxesSample.kt
//         -> (reverse bridge, ADR-072)  test.boxes.{Box,Pairing,Crate,Boxes}
//           -> real C# TestDependency   Test.Boxes.{Box,Pairing,Crate,Boxes}
//
// EXPECTED TO FAIL as of this commit: none of `test.boxes.Box`, `Boxes`, `Pairing` or `Crate`
// exist yet. Neither generator routes a `typeParameters.isNotEmpty()` `RirClass` down a generic
// path (Decision 3); today's reader does not even enumerate the instantiations (Decision 2), so
// `reverse-ir.json` carries none of this at all. `test-library`'s Kotlin compile fails outright.

/** Construction: the fake top-level constructor named exactly like the type (Decision 5). */
fun boxOfIntValue(value: Int): Int = Box(value).value

/** A T-FREE member (`Describe`) still needs its own per-instantiation thunk (CS8895). */
fun boxOfIntDescribe(value: Int): String = Box(value).describe()

/**
 * `Box<String>` and `Box<String?>` both lose their fake constructor to
 * `skipped_ambiguous_generic_constructor` (both erase to `(String)`), so `Box<String>` is only
 * reachable via `Boxes.ofText`. `.uppercase()` proves a real Kotlin `String`, not a pointer.
 */
fun boxOfTextUppercased(value: String): String = Boxes.ofText(value).value.uppercase()

/** Decision 7: `Box<String?>` needs the index-aware pre-order `NullableAttribute` decode. */
fun boxOfMaybeTextIsNull(): Boolean = Boxes.ofMaybeText(null).value == null

/** Enum type argument, cross-namespace (`Test.Enums.CatMood`), no cast at the call site. */
fun boxOfMoodIsSleepy(): Boolean = Boxes.ofMood(CatMood.SLEEPY).value == CatMood.SLEEPY

/** Instantiation at a PARAMETER position (`Boxes.Unwrap(Box<int>)`), not just return/property. */
fun unwrapBoxOfInt(value: Int): Int = Boxes.unwrap(Box(value))

/**
 * A member of the generic type returning its own instantiation (`Box<T>.Rewrap(): Box<T>`),
 * the instantiation reached only by Decision 2 phase B substitution.
 */
fun rewrapBoxOfInt(value: Int): Int = Box(value).rewrap().value

/** Arity 2, parameter names (`TKey`/`TValue`) read from metadata verbatim. */
fun tallyPairing(label: String, count: Int): String {
  val tally: Pairing<String, Int> = Boxes.tally(label, count)
  return "${tally.key}/${tally.value}"
}

/** `Crate<T>`'s `ReferenceTypeConstraint` is read but not emitted (Decision 8); construction
 * still goes through the fake constructor since `Crate<String>` is unambiguous. */
fun crateOfTextItem(item: String): String = Crate(item).item

/** ADR-051 lifetime is unchanged and generic-agnostic: `use { }` still frees the handle. */
fun boxLifetimeUse(value: Int): Int = Box(value).use { it.value }

/**
 * Polymorphism over the generic, which is the whole point of not monomorphizing: one function
 * generic over `Box<T>` describing a mixed-instantiation list.
 */
internal fun <T> describeAll(boxes: List<Box<T>>): List<String> = boxes.map { it.describe() }

/** Exercises [describeAll] over two different `Box<Int>` instances. */
fun describeAllBoxOfInt(first: Int, second: Int): String =
  describeAll(listOf(Box(first), Box(second))).joinToString(separator = ",")
