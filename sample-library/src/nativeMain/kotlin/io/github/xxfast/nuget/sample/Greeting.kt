package io.github.xxfast.nuget.sample

import kotlin.experimental.ExperimentalNativeApi

@OptIn(ExperimentalNativeApi::class)
@CName("greeting")
fun greeting(): String {
  return "Hello from Kotlin/Native!"
}
