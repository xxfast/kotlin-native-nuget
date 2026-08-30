package io.github.xxfast.kotlin.native.nuget.test.issue41

/**
 * Issue [#41](https://github.com/xxfast/kotlin-native-nuget/issues/41) fixture, sub-package half.
 *
 * This declaration exists purely to live in a *different* C# namespace from the type that
 * references it. `issue41` sits under `rootPackage`, so it maps to the sub-namespace
 * `TestLibrary.Issue41`, exactly as a second `include(...)` package would (ADR-063: an empty
 * `include` defaults to `rootPackage`, so every sub-package under it is exported and mapped
 * relative to it). The referencing type, `Issue41Bundle`, sits in the root package and therefore
 * lands in the bare `TestLibrary` namespace -- the cross-namespace hop the issue is about.
 *
 * Non-null scalars only: the point of the fixture is *where the name is written* in the generated
 * C#, not how any component marshals. Oreo weighs more than he should; Mylo insists he does not.
 */
data class Issue41Thing(
  val name: String,
  val weight: Int,
)
