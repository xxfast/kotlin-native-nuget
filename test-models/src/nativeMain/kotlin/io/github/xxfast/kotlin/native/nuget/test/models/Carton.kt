package io.github.xxfast.kotlin.native.nuget.test.models

import kotlinx.serialization.Serializable

/**
 * Issue #223: the kotlinx.serialization compiler plugin synthesizes a nested `$serializer` object
 * on every `@Serializable` declaration. `$` is not a legal C# identifier character, so the moment
 * the ADR-134 nested-declaration walk declares one, `Interop.cs` stops parsing (CS1056 and five
 * friends, six diagnostics per site). Nothing in this file declares that object, which is the
 * point: the walk has to refuse a compiler-synthesized declaration without being told its name.
 *
 * [Carton] is the `data class` arm and [CartonTag] the `value class` arm, because the real report
 * had both. Both are plain otherwise, so a failure here means exactly one thing. Oreo rates a
 * carton by how badly it fits her.
 */
@Serializable
data class Carton(val label: String, val weight: Int)

/** The `value class` arm of the same cell: a synthetic `$serializer` under an unwrapped owner. */
@Serializable
value class CartonTag(val value: String)
