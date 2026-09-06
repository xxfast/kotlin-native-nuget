package io.github.xxfast.kotlin.native.nuget.test.chaff

/**
 * The namespace half of the empty-static-class elision, which `husk/` cannot reach.
 *
 * `TestLibrary.Husk` survives because `HuskMixed` still lives in it. This package is the case
 * where nothing survives: `ChaffOnly.kt` is the only file in `chaff`, and its only declaration is
 * skipped. So the fix has to elide the static class *and* then notice the namespace it left has
 * no declarations left and drop it too. Without this package the namespace rule would never be
 * exercised, and a fix that only elided the class would still go green.
 *
 * Today `Interop.cs` carries `namespace TestLibrary.Chaff { public static partial class ChaffOnly
 * { } }`. After the fix neither the type nor the namespace may appear.
 *
 * Chaff is what is left after the winnowing. Neither cat is interested.
 */

/** Skipped for the same reason as the husk, and with the same named diagnostic. */
fun winnow(litters: List<List<String>?>): Int = litters.size
