package io.github.xxfast.nuget.sample.cat

class Box<T>(val value: T)

fun stringBox(): Box<String> = Box("hello")

fun catBox(): Box<Cat> = Box(Cat("Oreo"))

fun intBox(): Box<Int> = Box(42)
