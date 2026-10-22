package io.github.xxfast.kotlin.native.nuget.test.cat

fun <T> identity(value: T): T = value

fun <T> wrapInBox(value: T): Box<T> = Box(value)

fun <T : Pet> adoptPet(pet: T): T = pet

inline fun <reified T : Pet> groomPet(pet: T): T = pet

// ADR-213: a `Box<T>` parameter carrying the own `T`, bound on the plan with the rest of this file.
// Not `unbox`: `boxshelf.unbox(Box<Int>)` already owns that name.
fun <T> unwrapBox(box: Box<T>): T = box.value
