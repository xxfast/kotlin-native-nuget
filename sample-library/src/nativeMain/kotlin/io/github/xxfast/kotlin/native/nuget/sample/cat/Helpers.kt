package io.github.xxfast.kotlin.native.nuget.sample.cat

fun <T> identity(value: T): T = value

fun <T> wrapInBox(value: T): Box<T> = Box(value)

fun <T : Pet> adoptPet(pet: T): T = pet

inline fun <reified T : Pet> groomPet(pet: T): T = pet
