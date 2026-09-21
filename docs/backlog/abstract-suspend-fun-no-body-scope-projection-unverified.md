# An `abstract suspend fun` with no body: unverified whether the base projects a scope

An abstract class declaring `abstract suspend fun foo(): T` with no body has no Kotlin function to
export (`SuspendFunctionExports.kt` needs a body to lower into a coroutine launch), so it is unclear
whether `forwardSuspendRouteMethods` counts it as "projected" for [ADR-159](../adr/159-async-member-on-kotlin-subclass.md)'s scope-ownership question. If it does, the abstract base would be named the
scope owner while exporting nothing to use the scope, and each concrete override would need to
re-project as the real scope user, a shape ADR-159's "skip the override" rule (rule 4) does not
cover, because there the overridden member sits on a *kept* base that already projects a real
export. If it does not, the abstract base correctly defers ownership to whichever concrete override
is reached first in the chain, which may or may not already fall out of the existing walk.

Not investigated in the time available (deferred by the ADR-159 memo's "Deferred scope"). No
`test-library` fixture declares an abstract `suspend fun` with no body; `Brusher.groom` in the
ADR-159 fixture has a body on the abstract class. Needs a spike before it can be classified as a
bug or confirmed as already correct.
