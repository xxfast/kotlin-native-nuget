package io.github.xxfast.nuget.sample.cat

interface Pet {
  val name: String
  fun speak(): String
  fun greet(): String = "Hi, I'm $name"
}
