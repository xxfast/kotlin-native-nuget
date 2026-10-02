package io.github.xxfast.kotlin.native.nuget

/**
 * ADR-180: the `nuget {}` DSL marker. A nested block cannot call an outer block's function through
 * an implicit receiver, so `include(...)` inside `repositories { nuget("feed") { } }` is a compile
 * error instead of a silent write to `publish.include`. Public: the compiler applies it in build
 * scripts, the precedent being KGP's `@KotlinGradlePluginDsl`.
 */
@DslMarker
@Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE)
public annotation class NugetDsl
