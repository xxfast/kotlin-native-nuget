# A primitive-payload void lambda parameter renders non-compiling C#

**A `(Int) -> Unit` (or any other primitive-payload) lambda parameter renders a thunk body that
unwraps the primitive from a handle using the raw Kotlin type name, which does not compile.**
`CirClassTranslator.kt:~2090-2094`'s lambda-thunk generator maps only `"String" -> "string"` and
falls through to unwrapping every other payload type from a handle (`NugetMarshal.FromHandle<T>`)
the way an object payload is unwrapped; the enum arm is handled correctly, but a primitive arm is
not special-cased at all. For `fun onEvent(ref: (Int) -> Unit)` this emits

```csharp
Int arg0 = NugetMarshal.FromHandle<Int>(arg0Ptr);
```

inside a thunk whose parameter list does not match the delegate it is assigned to
(`internal delegate void NugetIntVoidCallback(int arg0Ord, IntPtr _)`): `Int` is not a valid C#
type name (CS0246, it needed to be `int`), and even fixed to `int` the thunk still reads a handle
pointer where the callback delivers a raw ordinal, not a marshalled object.

It went unnoticed because every existing lambda-parameter fixture (`Cat.forEachToy` and similar)
carries an object payload, never a bare primitive; no fixture forces the primitive branch of this
codepath to run.

Discovered alongside the legacy-route keyword-parameter-name fix
([ADR-024](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/024-sync-exception-propagation.md)'s
2026-09-07 amendment): the `KeywordRoutesSample.kt` fixture originally used `(Int) -> Unit` for its
lambda-parameter cells and hit this defect first, unrelated to the keyword bug under test. The
fixture was switched to an object payload (`KeywordTick`) to keep the keyword-parameter cells green
without also carrying this one. Verified by `packNuget` against the `Int` shape; not yet fixed.
