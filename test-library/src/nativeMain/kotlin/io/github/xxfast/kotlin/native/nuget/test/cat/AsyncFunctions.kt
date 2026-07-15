package io.github.xxfast.kotlin.native.nuget.test.cat

import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.seconds

suspend fun fetchGreeting(name: String): String {
  delay(1.seconds)
  return "Hello, $name!"
}

suspend fun saveGreeting(greeting: String) {
  delay(1.seconds)
}
