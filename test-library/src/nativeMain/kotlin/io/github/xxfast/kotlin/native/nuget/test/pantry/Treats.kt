package io.github.xxfast.kotlin.native.nuget.test.pantry

/**
 * ADR-147 amendment on the legacy generic-function route: `T : Any` renders `where T : notnull`,
 * and the per-width dispatch still serves `int` and `string`, which both satisfy it.
 *
 * Whatever Oreo is given, he hands back unchanged and expects another.
 */
fun <T : Any> handBack(treat: T): T = treat
