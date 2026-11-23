package io.github.xxfast.kotlin.native.nuget.test.adoption

/**
 * ADR-243 top-level position: a package function returning a `List` of handles. Lives in its own
 * file so the generated static class (`AdoptionDrive`) does not collide with [AdoptionCentre].
 *
 * Oreo and Mylo turned up on the doorstep again.
 */
fun streetCats(): List<Kitten> = listOf(Kitten("Mylo"), Kitten("Oreo"))
