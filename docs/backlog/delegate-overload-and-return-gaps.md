# Delegate feature (ADR-158): four small gaps found but not fixed

1. **Delegate at a return position is a named skip, not a binding.** `Func<int,int> MakeDoubler()`
   is `SKIPPED_DELEGATE_POSITION` today. Before ADR-158, a delegate-typed return bound by accident
   (the mis-classified custom-delegate-as-class kept its `Invoke` method, so `handle.invoke(x)`
   worked); that accidental binding is gone on purpose (see ADR-158 Consequences), and nothing
   replaces it yet. The inverse direction needs a registered `Invoke` thunk per shape and a Kotlin
   function object wrapping a `GCHandle`, the mirror shape of the parameter case this ADR ships.

2. **`delegateOverloadAmbiguityDiagnostics` only looks at methods.** It filters
   `bridgeableRegistrables(...)` to `RirRegistrable.Method` before grouping
   (`NugetGenerateBindingsTask.kt`, the function of that name). A constructor pair differing only by
   delegate shape (the constructor equivalent of `Run(Action)` / `Run(Func<int>)`) binds both
   overloads with no `INFO_DELEGATE_OVERLOAD_AMBIGUITY` note, so a consumer hits the same bare-lambda
   `Overload resolution ambiguity` with no diagnostic pointing at the workaround.

3. **Possible false positive on the overload-ambiguity note.** `f(Func<int,int>)` beside
   `f(Func<int,int>?)` groups as "differs only by delegate shape" the same way `Run(Action)` /
   `Run(Func<int>)` does, but Kotlin's own overload resolution may resolve a bare lambda to the
   non-nullable overload without ambiguity (ordinary Kotlin nullable-vs-non-nullable overload
   resolution, not spiked for this specific pair). Not verified either way; worth a spike before
   trusting or suppressing the note for a nullability-only pair.

4. **Two custom delegates with the same simple name in different C# namespaces, bound into one
   Kotlin package, alias to one `typealias`.** The generated `typealias {Name} = ...` is keyed on
   the delegate's simple name within its target Kotlin package; a second C# delegate of the same
   simple name from a different namespace mapped into the same package silently overwrites the
   first one's typealias declaration rather than raising a name-collision diagnostic (the kind
   already raised for two colliding class names, e.g. `ERROR_CSHARP_NAME_COLLISION`-style checks
   elsewhere in the pipeline).
