package io.github.xxfast.kotlin.native.nuget.test.petlist

import io.github.xxfast.kotlin.native.nuget.test.cat.Cat
import io.github.xxfast.kotlin.native.nuget.test.cat.Pet

/**
 * ADR-176 top-level position: `Lineup.FosterLineup()` in C# (ADR-007 file-named static class).
 * Own file so the static class does not collide with the `FosterHome` class name.
 * Mylo always lines up first, because Oreo is still asleep.
 */
fun fosterLineup(): List<Pet> = listOf(Cat("Mylo"), Cat("Oreo"))
