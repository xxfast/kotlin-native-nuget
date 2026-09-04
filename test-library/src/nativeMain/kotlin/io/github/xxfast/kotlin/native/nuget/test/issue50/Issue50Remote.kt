package io.github.xxfast.kotlin.native.nuget.test.issue50

/**
 * Issue [#50](https://github.com/xxfast/kotlin-native-nuget/issues/50) fixture, sub-package half.
 *
 * Both payload types exist purely to live in a *different* C# namespace (`TestLibrary.Issue50`)
 * from the sealed hierarchy that carries them, exactly as [#41](https://github.com/xxfast/kotlin-native-nuget/issues/41)'s
 * `Issue41Thing` does for a top-level class. Non-null scalars only: the point is where the name is
 * written in the generated C#, not how any component marshals.
 */
data class Issue50Assignment(
  val name: String,
  val craft: String,
)

/** The reference-payload half: a single object property, not a collection. */
data class Issue50Position(
  val latitude: Double,
  val longitude: Double,
)
