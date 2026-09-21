package io.github.xxfast.kotlin.native.nuget.test.unrouted

import io.github.xxfast.kotlin.native.nuget.test.cat.Box

/**
 * ROADMAP line 76 fixture, restored: research H's row 20 RETURN cell, which was moved to
 * `.kt.disabled` before that item's green verify precisely because the generated C# does not
 * compile. The `.kt.disabled` file is not present anywhere in this worktree, so this is the minimal
 * shape rebuilt from the backlog file
 * `docs/backlog/top-level-generic-return-cross-namespace-renders-unqualified.md`.
 *
 * A top-level function returning a generic class declared in a DIFFERENT Kotlin package than its
 * own: `Box<T>` lives in `test.cat` (`TestLibrary.Cat`), this function lives in `test.unrouted`
 * (`TestLibrary.Unrouted`). `CirFunctionTranslator.kt` spells the outer type by its simple name on
 * the legacy generic-return route, so the generated C# says `Box<int>` with no qualification and no
 * `using`: `CS0246` in the consumer's build. The type ARGUMENTS were already qualified by #111, and
 * the same-package case (`cat.wrapInBox`) compiles today, so only the outer name is at fault.
 *
 * Oreo gets in every box that arrives, whichever room it was addressed to.
 */
fun genericReturnOnTopLevel(): Box<Int> = Box(1)
