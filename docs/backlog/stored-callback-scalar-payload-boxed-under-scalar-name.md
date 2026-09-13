# A stored-callback scalar payload is registered under a scalar-shaped delegate name while still boxing it as a handle

**A class that declares a stored `(Int) -> Unit` listener alongside a per-call `(Int) -> Unit`
lambda parameter registers one shared delegate name, `NugetIntVoidCallback`, with two incompatible
parameter lists: `IntPtr arg0Ptr` from the stored route, `int arg0` from the per-call route.**
Whichever route registers second reuses the first's shape; if the per-call route loses the race, its
lambda's parameter type no longer matches the delegate's declared shape, the same CS1661/CS1678
consumer-compile failure [ADR-036](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/036-reverse-interop-mechanism.md)'s
2026-09-13 amendment just fixed for `Boolean` against `Byte`.

`storedArgSuffix` (`CirClassTranslator.kt:2818-2825`, in `translateStoredCallbackMethod`) suffixes a
Kotlin `Int` payload as `Int`, the same fragment the per-call route's `typeSuffix` gives a by-value
`Int`. But the stored route's `delegateParamList` (`CirClassTranslator.kt:2832-2837`) always boxes a
non-enum payload as `IntPtr arg${i}Ptr`, never by value: the suffix names the wire the *other* routes
use for that fragment, not the wire this route actually emits.

It went unnoticed because no fixture declares a stored callback and a per-call lambda parameter of
the same primitive type on one class; `test-library`'s stored-callback fixtures use `Mood` (an enum)
and object payloads, never a bare `Int`/`Double`/`Byte`.

A related, still-latent sibling on the interface-bridge planner: `ForwardBridgeWire.nameFragment()`
(`ForwardInterfaceBridgePlanner.kt:220-223`) spells `BOOLEAN -> "Byte"` under the separate
`NugetBridge...` prefix, with no `BYTE` wire member to collide against yet; it must take `Bool` the
day one is added.

Discovered alongside [ADR-036](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/036-reverse-interop-mechanism.md)'s
2026-09-13 amendment, while fixing the `Boolean`/`Byte` per-call collision. Verified by reading; not
reproduced by a fixture.
