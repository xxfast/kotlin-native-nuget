/**
 * The extension half of the `Lantern` fixture. Kotlin call syntax `lantern.glow()` picks the
 * member, so the generated export reaches these through an aliased import instead.
 */
package io.github.xxfast.kotlin.native.nuget.test.lamplight.ext

import io.github.xxfast.kotlin.native.nuget.test.lamplight.Lantern

/** Shadowed by the member `glow()` exactly. */
@Suppress("EXTENSION_SHADOWED_BY_MEMBER")
fun Lantern.glow(): String = "extension"

/** Shadowed by the member `dim(level: Int = 0)` through its default; the compiler does not warn. */
fun Lantern.dim(): String = "extension"

/** Not shadowed: the member `flicker(times: Int)` does not accept a `Long`. */
fun Lantern.flicker(times: Long): String = "extension:$times"
