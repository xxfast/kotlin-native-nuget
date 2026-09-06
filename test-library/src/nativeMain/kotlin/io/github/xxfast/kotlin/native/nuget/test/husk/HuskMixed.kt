package io.github.xxfast.kotlin.native.nuget.test.husk

/**
 * The positive control for `HuskOnly.kt`, in the same package and therefore the same
 * `TestLibrary.Husk` namespace.
 *
 * One skipped top-level function of exactly the husk's shape, plus one that survives. The merged
 * member set of this file's static class is therefore non-empty by exactly one member, which is
 * the smallest possible distance from the husk. `HuskMixed` must still be generated, and
 * `HuskMixed.ping()` must still return 1, so "elide a static class whose merged member set is
 * empty" cannot quietly become "elide a static class that skipped something".
 *
 * It is also what keeps `TestLibrary.Husk` alive: a namespace is only droppable when every
 * declaration in it went away, and here one did not.
 *
 * Mylo pings back every time. Oreo, in the file next door, does not.
 */

/**
 * Skipped, same shape as `HuskOnly.scan`: `List<List<String>?>` drops the function with a named
 * `[nuget:SKIPPED_UNSUPPORTED_INPUT]`.
 */
fun sift(litters: List<List<String>?>): Int = litters.size

/** The survivor. Trivial on purpose: nothing about its own marshalling is under test. */
fun ping(): Int = 1
