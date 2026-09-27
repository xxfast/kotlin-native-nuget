package io.github.xxfast.kotlin.native.nuget.test.errand

/**
 * ROADMAP Phase 4 line 23, value-class arm: a **module-local** value class returned from a
 * `suspend` member, top-level ([fetchNametag], [findNametag]) and class-level
 * ([ErrandRunner.collectNametag], [ErrandRunner.lookUpNametag]).
 *
 * Declared in C# as a `readonly record struct` with only `Nametag(string label)`, so a suspend
 * completion spelled `new Nametag(resultPtr, out _)` (the handle-class shape) does not compile
 * (CS1729). The wire is already a boxed value class in a StableRef, which `Nametag.NugetUnbox`
 * reads and disposes.
 *
 * Oreo's tag is engraved. Mylo's is scribbled in biro.
 */
value class Nametag(val label: String)
