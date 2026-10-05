package io.github.xxfast.kotlin.native.nuget.test.models

import io.github.xxfast.kotlin.native.nuget.annotations.CSharpName

/**
 * Issue #464: a property and a function sharing the Kotlin name `city`, both of which would render
 * `City` in C#, declared in this dependency module where the main plugin is not applied. The
 * annotations-only plugin is what puts [CSharpName] on this module's classpath, so the function can
 * take a different C# name here instead of being renamed in Kotlin.
 *
 * Oreo files every story from the sunniest windowsill in the house.
 */
interface Dateline {
  /** Keeps the generated name `City`. */
  val city: String

  /** Same Kotlin name as [city]; renders as `CityFor`. */
  @CSharpName("CityFor")
  fun city(edition: Int): String
}
