# A `MutableStateFlow` returned from a non-field-backed function silently loses writes

**A `fun state(): MutableStateFlow<T>` (or any `MutableStateFlow`-returning function that is not a
stored property) that constructs a fresh flow on each call loses every write from C#, silently.**
The [ADR-071](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/071-mutable-stateflow-mapping.md)
route does not hold onto the flow it was first handed: it re-invokes the Kotlin getter on every
`.Value` read and every `.Value` write. The generated write path,
`keywordroutes_state_set_value`, does `obj.state(error).value = value`, calling `state(error)`
again to obtain a target and writing into whatever it returns.

For a property (`val state: MutableStateFlow<T> = MutableStateFlow(...)`) this is invisible,
because the getter always returns the same backing instance. For a plain function with no backing
field, e.g. `fun state() = MutableStateFlow(0)`, each call constructs a brand new flow, so a C#
write lands in a throwaway object and the next read comes back at the original seed, not the
written value.

It went unnoticed because every shipped `MutableStateFlow` fixture backs the flow with a stored
property; nothing exercised the function-returning shape until one was needed.

Discovered alongside the legacy-route keyword-parameter-name fix
([ADR-024](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/024-sync-exception-propagation.md)'s
2026-09-07 amendment): the `KeywordRoutesSample.kt` fixture needed a `MutableStateFlow`-returning
class method to exercise the `error` → `error_` rename inside the write lambda, and a naive
`fun state(error: Int) = MutableStateFlow(error)` hit this defect first, unrelated to the keyword
bug under test. The fixture works around it by memoising the flow per key
(`states.getOrPut(error) { MutableStateFlow(error) }`) so the same instance is returned on every
call, keeping the keyword-parameter cell green without also carrying this one.

Fix shape, not yet decided: either skip named (diagnostic, not silent) a `MutableStateFlow`
-returning member that is not a stored property, since there is no way to make repeated
construction observably correct without caching state the generator does not own; or document the
"must be field-backed" contract explicitly if skipping is too disruptive. Verified by `packNuget`
against the unmemoised shape and a failing xunit assertion on the round trip; not yet fixed.
