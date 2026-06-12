package io.github.xxfast.nuget.sample.math

fun add(a: Int, b: Int): Int = a + b

fun multiply(a: Int, b: Int): Int = a * b

fun divide(a: Int, b: Int): Int? = if (b != 0) a / b else null
