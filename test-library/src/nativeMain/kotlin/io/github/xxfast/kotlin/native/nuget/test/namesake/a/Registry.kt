package io.github.xxfast.kotlin.native.nuget.test.namesake.a

/**
 * Namesake fixture, half A: the top-level function shape (backlog shapes 1 and 6). Two `rollCall()`
 * functions in two packages mint one bare `rollCall` symbol today, and per the memo's spike 2a a
 * cname-only fix leaves the generated `CNameExports.kt` with an overload-resolution ambiguity, so
 * the Kotlin call site has to be qualified too.
 *
 * File name is `Registry.kt` rather than `RollCall.kt` so the ADR-007 file class (`Registry`) does
 * not share a name with its own member and the ADR-110 rename is not in play.
 */
fun rollCall(): String = "a: Oreo present"
