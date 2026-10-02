package io.github.xxfast.kotlin.native.nuget.annotations

/**
 * The exact C# name of this member, in place of the generated PascalCase one (ADR-179). Written on
 * the one member whose generated name collides with another's (CS0102); never changes the Kotlin
 * name, the native export symbol, or any other platform's name. Verbatim: no `Async` suffix is
 * appended to a declared name on a `suspend fun`. Overrides inherit it from the root declaration.
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
@MustBeDocumented
public annotation class CSharpName(val name: String)
