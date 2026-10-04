package io.github.xxfast.kotlin.native.nuget.test.nameplate.engraved

/** ADR-188 amendment: the extension function half of the `nameplate/worn` pair. */
fun String.nameplate(): String = "engraved:$this"
