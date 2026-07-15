package io.github.xxfast.kotlin.native.nuget.test.cat

interface Readable<out T> {
    fun read(): T
}

interface Writable<in T> {
    fun write(value: T)
}
