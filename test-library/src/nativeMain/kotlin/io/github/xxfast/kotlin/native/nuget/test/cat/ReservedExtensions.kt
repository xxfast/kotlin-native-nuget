package io.github.xxfast.kotlin.native.nuget.test.cat

/**
 * Extension-receiver collision. The generated extension method already names its `this` parameter
 * `receiver` on the C# side and its first export parameter `receiver` on the Kotlin side, so a user
 * parameter of the same name is a duplicate at the same position on both. Joins the two so the
 * order they arrive in is observable.
 *
 * The rest of this fixture family lives in `reserved/ReservedNamesSample.kt`; this cell sits beside
 * the other `String` extensions instead, because the merged `StringExtensions` class takes the
 * package of the first-visited `String` extension, so a cell in its own package would drag the
 * whole class (and every other `String` extension with it) out of the namespace its tests use.
 */
fun String.tag(receiver: String): String = "$this:$receiver"
