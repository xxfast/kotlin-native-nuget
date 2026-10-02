package io.github.xxfast.kotlin.native.nuget.annotations

/**
 * Marks every Kotlin declaration generated from a NuGet package by `bind {}` (ADR-181). The
 * reverse direction (C# to Kotlin) is experimental: these bindings may change in any 1.x release.
 *
 * Opt in per file with `@file:OptIn(ExperimentalNugetBindingApi::class)`, or module-wide with
 * ```
 * kotlin {
 *   compilerOptions {
 *     optIn.add("io.github.xxfast.kotlin.native.nuget.annotations.ExperimentalNugetBindingApi")
 *   }
 * }
 * ```
 */
@RequiresOptIn(
  level = RequiresOptIn.Level.ERROR,
  message = "Bindings generated from a NuGet package by `bind {}` are experimental and may " +
      "change in any 1.x release.",
)
@Retention(AnnotationRetention.BINARY)
@Target(
  AnnotationTarget.CLASS,
  AnnotationTarget.TYPEALIAS,
  AnnotationTarget.FUNCTION,
  AnnotationTarget.PROPERTY,
  AnnotationTarget.CONSTRUCTOR,
)
@MustBeDocumented
public annotation class ExperimentalNugetBindingApi
