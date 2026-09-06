package io.github.xxfast.kotlin.native.nuget.test.husk

/**
 * The husk: a file whose *every* top-level declaration is skipped, so the file-named static class
 * ADR-007 gives it has an empty merged member set.
 *
 * Today the generator still emits `public static partial class HuskOnly { }` into `Interop.cs`.
 * A type with no members is not an API, it is a scar left by a skip, and a consumer reading
 * IntelliSense cannot tell it apart from a class whose members are still to come. The fix elides
 * a static class whose merged member set is empty. Once that lands, no `HuskOnly` may exist
 * anywhere on the C# surface.
 *
 * The `husk` package is new on purpose. Putting this file in an existing package would let an
 * unrelated fixture hold the static class up and the assertion would prove nothing. Its only
 * company is `HuskMixed.kt`, the positive control: one skipped function of the same shape plus
 * one surviving `ping()`, so the same run has to elide one static class and keep the other. That
 * pairing is what stops the fix from being "drop every static class", and it is also why
 * `TestLibrary.Husk` itself survives here. The namespace-drop half of the fix is pinned one
 * package over, by `chaff/ChaffOnly.kt`, where nothing at all survives.
 *
 * Oreo scans the empty treat bag every morning. There is nothing in it. There has never been
 * anything in it. He keeps scanning.
 */

/**
 * The only declaration in the file, and it is skipped by a *named* diagnostic:
 * `List<List<String>?>` is ADR-099's nullable nested component, which cannot be written into a
 * Kotlin collection, so the parameter position drops the whole function with
 * `[nuget:SKIPPED_UNSUPPORTED_INPUT] ... its COLLECTION type combination is not supported`. Named
 * matters here: the fixture's premise is "every declaration in this file is skipped", and a
 * diagnostic is what makes that observable rather than asserted. No export symbol, no C# member,
 * and therefore no reason for `HuskOnly` to exist at all.
 */
fun scan(litters: List<List<String>?>): Int = litters.size
