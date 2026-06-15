package io.github.xxfast.kotlin.native.nuget.sample.cat

fun <T> identity(value: T): T = value

fun <T> wrapInBox(value: T): Box<T> = Box(value)
