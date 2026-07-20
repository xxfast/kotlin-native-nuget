package io.github.xxfast.kotlin.native.nuget.test.models

/**
 * ADR-066: reachable ONLY transitively, through [TopStory.byline]. No `:test-library` declaration
 * returns a `Byline` on its own — this class proves the closure iterates from an already-admitted
 * type's members rather than doing a single hop from the module's own roots.
 */
class Byline(val name: String)
