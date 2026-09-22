# A dropped late callback invocation with a handle-passed argument leaks that argument's handle

> Discovered while landing [ADR-161](../adr/161-csharp-callback-exception-into-kotlin.md) part C.

A Kotlin invocation of a `void`-returning stored callback or interface bridge slot that lands after
the C# side already disposed the subscription is dropped: the never-reused key table lookup misses,
and the thunk returns without invoking anything. If the dropped invocation's argument was a
handle-passed payload (an exported object, minted before the lookup miss is discovered), the delegate
that would have read and disposed it never runs, so that argument handle leaks. Not measured; no
fixture currently drives a late call with an object-typed payload.
