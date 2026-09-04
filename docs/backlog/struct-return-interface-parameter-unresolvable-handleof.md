# A bound method with an interface-typed parameter and a struct return emits an unresolvable `handleOf(...)`

A bound C# member that both takes an interface-typed parameter (`IFeedable`, say) and returns a
struct fails the Kotlin compile of the generated bindings, with `handleOf` unresolved.

`NugetGenerateBindingsTask.kt`'s `buildStubMethod`, the `is RirStructType` return branch
(`NugetGenerateBindingsTask.kt:3821-3843`), calls `wrapInvoke(fullInvokeArgs, hasStringArg = false,
hasInterfaceArg = false)` unconditionally, hard-coding `hasInterfaceArg = false` regardless of
whether the member actually has an interface-typed parameter. Every other return-shape branch
instead threads `hasInterfaceParam` through from the member's own parameter list, so `wrapInvoke`
wraps the call in `nugetTransferScope { ... }` when needed
(`NugetGenerateBindingsTask.kt:3498-3507`). `handleOf`/`handleOfOrNull`
(`NugetGenerateBindingsTask.kt:4627-4638`) are members of `NugetTransferScope`, so a call to either
one outside a `nugetTransferScope { }` block has no receiver, an unresolved reference the Kotlin
compiler rejects.

Fails loudly, not silently: the generated Kotlin fails to compile, it does not run with wrong
behaviour. Unreachable with today's fixtures, since no fixture member combines an interface-typed
parameter with a struct return, which is why nobody has hit it. Verified by source reading only,
not executed against a real fixture member.

Discovered alongside [ADR-104](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/104-reverse-thunk-error-channel.md):
the implementer preserved this pre-existing gap deliberately (passing `hasInterfaceArg = false`
to keep the struct-return branch's existing behaviour) rather than opportunistically fixing an
unrelated bug while adding the error out-parameter.
