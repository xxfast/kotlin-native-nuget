package io.github.xxfast.kotlin.native.nuget.test.pantry

/**
 * ADR-147 amendment: a type parameter bounded by `Any` is non-null, so its C# spelling carries
 * `where T : notnull`. The unbounded siblings (`Box`, `Slot`, `Crate`) stay nullable and accept a
 * `null` argument; this one keeps the non-null crossing.
 *
 * Oreo's treat tin. There is always something in it, or he would not sit next to it.
 */
class Tin<T : Any>(val value: T)

/**
 * Property names that PascalCase onto the class's own type parameter names: `a` renders `A`, the
 * same identifier as the type parameter `A`, which C# refuses (CS0102). The property names are the
 * consumer-facing API, so it is the type parameters' C# spelling that has to give way.
 *
 * Oreo and Mylo, sharing one windowsill: a duo of whatever they happen to be that day.
 */
class Duo<A, B>(val a: A, val b: B)
