# What it would take to say the generated bridging has no memory leaks

> Added 2026-09-09 after ADR-120 landed and its first Windows run flaked +1 on a neighbouring test.

"Zero leaks" for arbitrary consumer programs is not a testable claim. The reachable claim is stronger than it sounds: the generator has a finite set of routes, every crossing goes through one, so "every route, every exit path, both directions, every shipped target, measured for handles, objects and heap, no drift" is exhaustive over what the generator can emit. Composition adding nothing on top is a code-review argument the ADRs partly already make, not a test.

The rungs, cheapest first. Each is its own ROADMAP line; this file is the map.

1. **Every forward route has a happy-path row in `LiveHandleTests`.** Eleven families today. The checklist is the FEATURES.md mapping table, so completeness is checkable, not guessed: sealed classes, generics, value classes, structs, enums, nullable variants, `Result`, `Uuid`, map and set returns, nested collections, properties, extensions, overload sets. One `[Fact]` each, about a third of a second each.
2. **Every generated exit path is exercised, and coverage proves every emitted `Dispose` was hit.** ADR-073's leak was confirmed by a `Dispose` line at zero hits beside a hot `throw`; make that systematic. Fault injection through the mutable `NugetMarshal.Factories` covers materialization; the Kotlin side needs a fixture that throws on demand at each route (callback body, interface bridge member, suspend body, Flow emission).
3. **The assembly-level `LiveHandles` sweep** (already a ROADMAP line): every existing test becomes a leak probe. Report first, assert once the suite is handle-clean.
4. **Objects, not only handles** (ADR-121, in progress): a zero handle count with the object still reachable is a leak the counter cannot see. Extend per family once the first fixture ships.
5. **Reverse direction** (already a ROADMAP line): the plugin's ten mint sites through a counter, then rungs 1 to 4 again for C# → Kotlin.
6. **Non-handle memory: a soak test.** One crossing 100k times; Kotlin heap from the native runtime's GC statistics, `GC.GetTotalMemory(true)` on .NET, and process RSS, before and after. The only rung that measures memory rather than bookkeeping: string buffers, pinned arrays, growth in internal tables. Not a CI gate, a committed baseline like the micro-benchmark's.
7. **Concurrency.** Crossings from many threads while `GC.Collect()` races `Dispose`. ADR-089 found and closed one such race by reasoning; a stress test finds the ones nobody reasoned about.
8. **Every shipped target runs the full suite.** Windows and macOS do. The AOT publish is a different runtime and only gets a smoke test today.

Leverage: rungs 1 and 2 are mechanical because the generator knows its own routes. A later version could emit the harness rows itself, one per route it generates, so a new mapping cannot ship without its leak test. Same move ADR-055 made for the ABI contract.
