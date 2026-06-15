package io.github.xxfast.kotlin.native.nuget.sample.cat

fun Cat.sayName(): String = "My name is ${this.name}"
fun Cat.greetWith(greeting: String): String = "$greeting, ${this.name}!"
