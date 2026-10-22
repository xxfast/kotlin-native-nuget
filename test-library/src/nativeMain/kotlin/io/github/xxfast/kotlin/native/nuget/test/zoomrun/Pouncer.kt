package io.github.xxfast.kotlin.native.nuget.test.zoomrun

/**
 * ADR-236: lambda-typed properties on a Kotlin interface. A Kotlin implementation ([laserDot])
 * reaches C# through the backing wrapper; a C# implementation reaches Kotlin through the bridge
 * ([pounce], [cue], [teach]), so both halves of the interface carry delegate properties.
 */
interface Pouncer {
  val onPounce: (Int) -> String
  var cue: ((String) -> String)?
  val onLeap: suspend (Int) -> Int
}
