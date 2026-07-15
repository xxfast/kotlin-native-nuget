package io.github.xxfast.kotlin.native.nuget.test.math

fun add(a: Int, b: Int): Int = a + b

fun multiply(a: Int, b: Int): Int = a * b

fun divide(a: Int, b: Int): Int? = if (b != 0) a / b else null

inline fun square(x: Int): Int = x * x
