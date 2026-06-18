package io.github.xxfast.kotlin.native.nuget.sample.cat

import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.seconds

suspend fun fetchGreeting(name: String): String {
  delay(5.seconds)
  return "Hello, $name!"
}

suspend fun saveGreeting(greeting: String) {
  delay(5.seconds)
}
