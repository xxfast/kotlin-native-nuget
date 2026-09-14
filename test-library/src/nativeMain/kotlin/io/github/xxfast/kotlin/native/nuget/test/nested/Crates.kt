package io.github.xxfast.kotlin.native.nuget.test.nested

import io.github.xxfast.kotlin.native.nuget.test.cat.Cat
import io.github.xxfast.kotlin.native.nuget.test.cat.Pet

/**
 * Site (c) of the interface-spelling sweep: a generic bound on a **top-level** type declared in
 * another package.
 *
 * `legacyBoundInterfaceCsName` (`CirTypeMapping`) short-circuits a top-level interface bound to a
 * bare `"I$simpleName"`, and the class-bound arm falls through to a bare `simpleName`. Both are
 * spelled into the `TestLibrary.Nested` file, which has no using for `TestLibrary.Cat`, so
 * `where T : IPet` and `where T : Cat` name nothing here. `cat.PetBox<T : Pet>` never caught this
 * because it is declared beside its own bound.
 *
 * [PetCrate] is the interface-bound cell and [CatCrate] the class-bound twin: an implementation
 * that fixes only `legacyBoundInterfaceCsName` leaves the second one red.
 *
 * Constructor and `value` only: the legacy generic class route (ADR-032) bridges those two and
 * nothing else, so a method here would be silently unrouted and the C# cells would assert through
 * a member neither half ever emits. The bound is the whole subject anyway.
 *
 * Oreo travels in the cat-only crate; Mylo will share with any pet at all.
 */
class PetCrate<T : Pet>(val value: T)

/** The class-bound twin of [PetCrate]. */
class CatCrate<T : Cat>(val value: T)
