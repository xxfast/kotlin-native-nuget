package io.github.xxfast.kotlin.native.nuget.test.nested

/**
 * The top-level half of the ADR-040 x ADR-019 interface-return seam, kept in its own file because
 * ADR-007 names a file's static class after the file: a top-level function in `Aviary.kt` would
 * want a `static class Aviary` beside `class Aviary` (CS0101) and go red for an unrelated reason.
 *
 * [anyKeeperLater] is the top-level suspend route (`CirFunctionTranslator`) returning a **nested**
 * interface, so it needs the chain *and* the interface spelling together:
 * `Task<global::TestLibrary.Nested.Aviary.IKeeper>`. `cat/Pet.kt`'s `strayPetLater()` is the same
 * route with a top-level interface; between them no owner shape can be right by accident.
 *
 * Oreo will accept any keeper, provided they arrive holding food.
 */
suspend fun anyKeeperLater(): Aviary.Keeper = Aviary("Oreo").currentKeeper()
