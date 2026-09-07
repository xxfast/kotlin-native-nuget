package io.github.xxfast.kotlin.native.nuget.test.cat

/**
 * A public `annotation class`, here purely to pin that one skips **named**.
 *
 * `ClassKind.ANNOTATION_CLASS` has no route in the forward direction, so this declaration passes
 * the export gate and is then matched by no root bucket: it vanishes from the generated C# with no
 * diagnostic at all. That silence is the bug the ADR-064 amendment closes with
 * `SKIPPED_ANNOTATION_CLASS`; there is no C# projection of a Kotlin annotation worth generating,
 * so the *absence* is correct and only the missing warning is not.
 *
 * The usage on [Toy] is inert: the forward pipeline reads no annotation but `kotlin.native.CName`,
 * so `@Tagged("plaything")` costs `Toy` nothing. It keeps its constructor, `name`, `color`, `copy`,
 * `equals`, `hashCode` and `toString` exactly as before, which `AnnotationClassTests` asserts
 * alongside the absence.
 *
 * Oreo's tag says "menace"; Mylo's says "loaf". Neither tag crosses the bridge.
 */
annotation class Tagged(val tag: String)
