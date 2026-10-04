package io.github.xxfast.kotlin.native.nuget.test.nameplate.worn

/**
 * ADR-188 amendment. An extension property on an unexported receiver (`String`), named like the
 * extension function in `nameplate/engraved`. `String` is not exported, so each package's
 * `StringExtensions` lands in its own namespace (ADR-126) and both members bind.
 */
val String.nameplate: String get() = "worn:$this"
