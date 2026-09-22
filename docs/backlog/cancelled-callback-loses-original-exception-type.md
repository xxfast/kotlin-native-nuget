# A cancelled C# callback re-crosses to an uncaught C# caller typed as Kotlin's `CancellationException`

> Discovered while landing [ADR-161](../adr/161-csharp-callback-exception-into-kotlin.md) part B.

When a C# callback throws `OperationCanceledException`, `nugetCallbackCall` throws Kotlin's
`CancellationException` (carrying the original `NugetManagedException` as `cause`), which cancels
the invoking coroutine as intended. If nothing catches it and it escapes a forward `@CName` export,
it re-crosses to C# through the ordinary ADR-024 channel as `KotlinException` with `KotlinType ==
"kotlin.coroutines.cancellation.CancellationException"`, not the original .NET cancellation type
(`OperationCanceledException`, `TaskCanceledException`, or a user subclass). A C# caller pattern
matching on the original type on the far side of that round trip will not match. Fixing this needs
the cause chain (already carried) to be consulted specifically for this case at the export's error
build site, or a dedicated `kind` on the outer envelope, neither of which exists today.
