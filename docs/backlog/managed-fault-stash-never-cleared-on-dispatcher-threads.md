# A managed-fault stash left on a Kotlin dispatcher thread is never cleared

> Found while landing the `ClearManagedFault()` change on [ADR-161](../adr/161-csharp-callback-exception-into-kotlin.md).

Inferred from the code, not reproduced. A callback thunk that throws stashes its exception in the
`[ThreadStatic]` `NugetErrorNative._lastManagedFault`. Synchronous exports now clear it on return,
but a suspend or `Flow` callback can throw on a Kotlin dispatcher thread, which never runs a C#
export call site. The async completion sites (`CirConcurrencyRenderer.kt` ~206 and
`CirFlowRenderer.kt` ~175) build the exception on another thread, so the stash on the dispatcher
thread stays rooted for the thread's life. Fix direction: clear it where the thunk catches, or
where the stash is consumed on the thread that wrote it.
