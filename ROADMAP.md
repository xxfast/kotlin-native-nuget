# Roadmap

Open work only, one line per item. Detail lives behind the links: long investigation writeups in [docs/backlog/](docs/backlog/), shipped work in [FEATURES.md](FEATURES.md) and the ADRs in [docs/adr/](docs/adr/), and the full pre-slim-down roadmap (every completed item's writeup, plus historical notes like the `MVP.md` provenance) in the [archive](docs/roadmap-archive.md), a verbatim snapshot taken 2026-08-31.

Maintenance rules:
- One line per item. If an item needs more than two sentences, put the detail in `docs/backlog/<slug>.md` and link it as `([details](docs/backlog/<slug>.md))`.
- When an item ships, delete its line and its backlog file instead of ticking it; the ADR and [FEATURES.md](FEATURES.md) are the record.
- Keep phase headings even when a phase is complete; mark the phase `Complete.`

## Phase 1: Basic bridging

Complete.

## Phase 2: KSP-driven generation
- [ ] **Duplicate-type hazard across two published packages** ([details](docs/backlog/duplicate-type-hazard-across-two-published-packages.md))
- [ ] **Deferred from ADR-074's v1 scope** ([details](docs/backlog/deferred-from-adr-074-s-v1-scope.md))
- [ ] **The identical hole exists for an unexported base class: `class X : UnexportedBase()` renders a dangling supertype** ([details](docs/backlog/unexported-base-class-dangling-supertype.md))
- [ ] **A value class whose underlying enum/class lives in a different namespace emits a bare member type in its `readonly record struct`** ([details](docs/backlog/value-class-cross-namespace-underlying.md))
- [ ] **A class nested inside another exported class spells a bare simple name, dropping the enclosing scope.** ([details](docs/backlog/nested-object-handle-csharptypenamefor-simplename-only.md))

## Phase 3: Basic type support
- [ ] **Suspected dead branch: `CirClassRenderer.renderClass`'s `implements` `when` has an `else -> " : INugetHandle"` case that looks unreachable.** ([details](docs/backlog/renderclass-implements-dead-branch.md))
- [ ] **A class overriding an interface `val` with its own `var` would emit an invalid C# override (CS0546).** ([details](docs/backlog/class-overriding-interface-val-own-var-would.md))
- [ ] **A file whose only top-level declaration is skipped still emits an empty, pointless `public static partial class X { }` stub into `Interop.cs`.** ([details](docs/backlog/file-whose-only-top-level-declaration-skipped.md))
- [ ] **A class whose only constructor is skipped ships as a dead public type** ([details](docs/backlog/class-whose-only-constructor-skipped-ships-dead.md))
- [ ] **Top-level functions keep Kotlin camelCase while every other position PascalCases.** ([details](docs/backlog/top-level-functions-keep-kotlin-camelcase-while.md))
- [ ] **C# keyword parameter names are never escaped, so a Kotlin parameter literally named `ref`, `out`, `params`, etc. generates invalid C#.** ([details](docs/backlog/c-keyword-parameter-names-never-escaped-kotlin.md))
- [ ] Nullable properties on a data class nested inside a plain class are not covered by the nested-class fix ([details](docs/backlog/plain-class-nested-data-class-nullable.md))
- [ ] A nullable *enum* property on a sealed subclass is not handled by the fix above: the enum branch in `SealedClassExports.kt` (~line 93) ignores `isNullable` and would emit `.ordinal` on an `Enum?` access, generating non-compiling Kotlin, the same bug class as `String?`/`Int?`. Unverified by a fixture. Discovered alongside [#38](https://github.com/xxfast/kotlin-native-nuget/issues/38).
- [ ] A nullable-reference sealed-subclass property getter calls its native export twice: `CirClassTranslator.kt` ~1118 renders `Native_Get_x(_handle, out _) == IntPtr.Zero ? null : new T(Native_Get_x(_handle, out _))`, creating two `StableRef`s and leaking one. Pre-existing, established by source reading only. Discovered alongside [#38](https://github.com/xxfast/kotlin-native-nuget/issues/38).
- [ ] Sealed-path `bool` `DllImport`s lack `[return: MarshalAs(UnmanagedType.I1)]` (only the new `_has_value` import for `Int?` has it), so a non-null `Boolean` sealed-subclass property, or the `_value` import of a `Boolean?` one, would marshal a 4-byte C# bool against Kotlin's 1-byte return. Unverified by a fixture. Discovered alongside [#38](https://github.com/xxfast/kotlin-native-nuget/issues/38).
- [ ] **Only the `List`/`MutableList` sealed-subclass getters read the error slot back** ([details](docs/backlog/sealed-subclass-getter-error-slot.md))
- [ ] **A class method returning a sealed type is silently dropped, with no diagnostic.** A top-level `fun loaded(): State` binds (the #39/#50 fixtures), but the same signature as a method on an exported class generates nothing and warns nothing: the class came out with only its constructor and `Dispose()`. Found while building the [#50](https://github.com/xxfast/kotlin-native-nuget/issues/50) fixture, established by one `packNuget` run, not chased.
- [ ] **Sealed collection components at return and parameter positions are not bridged.** ([details](docs/backlog/sealed-collection-return-parameter-position.md))
- [ ] **`fun x(): List<Shape>`, and a bare sealed-typed parameter (`fun f(shape: Shape)`, a constructor with sealed-typed parameters), vanish from C# with no diagnostic instead of skipping named.** ([details](docs/backlog/sealed-collection-return-silently-drops.md))
- [ ] A sealed *interface* at a property position (bare or as a collection component) still skips named after [ADR-105](docs/adr/105-sealed-property-position.md), because only a sealed *class* gets the ADR-009 `FromHandle` discriminator (`NugetProcessor.kt`\x27s `rootSealedClasses` filters on `classKind == CLASS`), and so does a value class whose underlying is sealed (`isPlannable`\x27s `ValueClass` arm never sees `sealedHandle`). Pinned by `Tier1SealedCollectionPropertyTest`; deferred by ADR-105 alongside issue [#54](https://github.com/xxfast/kotlin-native-nuget/issues/54).
- [ ] A `var shapes: MutableList<Shape>` (or `MutableMap`/`MutableSet`) property with a sealed component plans get-only, named by the ADR-075 read-only diagnostic: the ADR-073 collection write path has never boxed an abstract C# base, so [ADR-105](docs/adr/105-sealed-property-position.md) gated it in `isWrappableComponent` together with the parameter-position slot above. Verified by `Tier1SealedMutableCollectionPropertyTest`; issue [#54](https://github.com/xxfast/kotlin-native-nuget/issues/54).
- [ ] A sealed-subclass property of an unbridgeable stdlib reference type still reports `SKIPPED_UNSUPPORTED_TYPE` with the unactionable `CirClassTranslator.kt:1059-1074` hint "expose a bridgeable wrapper type instead, or add '<type>' to the export set" (observed pre-fix for `kotlin.Throwable`, which ADR-107 carved out of this guard specifically). `SealedClassExports.kt:87-91`'s `isPrimitiveType` whitelist and `CirClassTranslator.kt`'s `isKnownNonReferenceType` don't recognise `kotlin.time.Instant`/`kotlin.time.Duration` either, so the same hint is expected to fire for those too on this route, though neither has a sealed-subclass fixture to confirm it. Discovered alongside [#56](https://github.com/xxfast/kotlin-native-nuget/issues/56) part 2 / [ADR-107](docs/adr/107-throwable-property-mapping.md).

## Phase 4: Rich type support
- [ ] **A null lambda argument cannot cross the callback bridge.** ([details](docs/backlog/null-lambda-argument-cannot-cross-callback-bridge.md))
- [ ] **`ForwardCallableCatalog.plansFor(declaration)` doesn't assert that a synthesized plan has a declared sibling.** ([details](docs/backlog/plansfor-synthesized-sibling-invariant.md))
- [ ] **The interface route (`interfaceEntries`, `ForwardCallablePlanner.kt:~640`) has no overload numbering at all.** ([details](docs/backlog/interface-route-overload-numbering.md))
- [ ] **No absence assertion pins the `override` and interface-route default-parameter exclusions [ADR-096](docs/adr/096-function-default-parameters.md) added.** ([details](docs/backlog/absence-assertion-pins-override-interface-route-default.md))
- [ ] **Explicitly overridden supertype members on a value class (`value class Name(val value: String) : CharSequence { override val length ... }`) are never exported; [#57](https://github.com/xxfast/kotlin-native-nuget/issues/57) closed with a hint fix only, and bridging them means reopening [ADR-082](docs/adr/082-value-class-inherited-members.md).** ([details](docs/backlog/value-class-explicit-override-members-not-exported.md))
- [ ] **Two exported types with the same simple name in different packages collide on their export symbol: a constructor, a top-level function, and a sealed class all hit this.** ([details](docs/backlog/two-exported-types-same-simple-name-different.md))
- [ ] **`translateInterface` emits every public interface member with no bridgeability filter, so an unbridgeable interface member yields CS0535 in the generated C#.** ([details](docs/backlog/translateinterface-no-bridgeability-filter.md))
- [ ] **Legacy `kotlinx.datetime.Instant`.** A distinct qualified name on a distinct dependency from `kotlin.time.Instant` ([ADR-076](docs/adr/076-instant-mapping.md)); would need a one-line classifier alias if a consumer needs it. No current consumer does (NYTimes-KMP is already on `kotlin.time.Instant`).
- [ ] **A `Map<String?, Int>` return renders `Dictionary<string?, int>`, violating `Dictionary`'s `TKey : notnull` constraint.** ([details](docs/backlog/map-string-int-return-renders-dictionary-string.md))
- [ ] **Two Tier 1 tests are pinned to "whatever is currently unsupported" rather than to a stable mechanism, and have now moved four times.** ([details](docs/backlog/two-tier-1-tests-pinned-whatever-currently.md))
- [ ] **Two small coverage gaps left by [ADR-097](docs/adr/097-enum-collection-components.md)'s `List` gate narrowing, named but not chased.** ([details](docs/backlog/two-small-coverage-gaps-left-adr-097.md))
- [ ] **`MutableMap`/`MutableSet` parameters do not write back, matching the pre-existing (and previously undocumented) `MutableList` parameter gap.** ([details](docs/backlog/mutablemap-mutableset-parameters-do-write-back-matching.md))
- [ ] **A returned collection's result handle leaks if materialization throws mid-loop.** ([details](docs/backlog/returned-collection-s-result-handle-leaks-if.md))
- [ ] **The legacy top-level two-call route (`staticLegacyTwoCall`) emits non-compiling C# for a collection parameter** ([details](docs/backlog/legacy-top-level-two-call-route-staticlegacytwocall.md))
- [ ] **`Char?` stays deferred, and ADR-061 bracketed it with `Boolean?` incorrectly.** ([details](docs/backlog/char-stays-deferred-adr-061-bracketed-boolean.md))
- [ ] **A bare `Char?` property is the same latent `packNuget` crash class ADR-080 just closed for bare `Nullable(Enum)`.** ([details](docs/backlog/bare-char-property-same-latent-packnuget-crash.md))
- [ ] **A lone surrogate `Char` does not round-trip under any candidate wire shape.** ([details](docs/backlog/lone-surrogate-char-does-round-trip-under.md))
- [ ] **`NugetReportDiagnosticsTask.report()` is 0/10 in the JVM unit coverage report, deliberately and permanently.** ([details](docs/backlog/nugetreportdiagnosticstask-report-0-10-jvm-unit-coverage.md))
- [ ] **The `SKIPPED_UNSUPPORTED_RETURN` diagnostic names "Boolean" for non-Boolean types.** ([details](docs/backlog/skipped-unsupported-return-diagnostic-names-boolean-non.md))
- [ ] **A nullable parameter, or a declaration with no return position at all, can be mis-reported with the `SKIPPED_UNSUPPORTED_RETURN` kind, meant only for a nullable-Boolean-shaped return.** ([details](docs/backlog/nullable-parameter-mis-reported-as-skipped-unsupported-return.md))
- [ ] **A nullable constructor argument on a generic class crosses wrong, or doesn't compile at all.** ([details](docs/backlog/nullable-constructor-argument-generic-class-crosses-wrong.md))
- [ ] **A `var` property on a generic class exports no setter, so it is silently read-only from C#.** ([details](docs/backlog/var-property-generic-class-exports-setter-silently.md))
- [ ] **A concrete-typed property on a generic class is mis-surfaced as `T`.** ([details](docs/backlog/concrete-typed-property-generic-class-mis-surfaced.md))
- [ ] **The C#-side list gate and the Kotlin-side export gate are two independently computed predicates, never proven equivalent** ([details](docs/backlog/list-gate-predicates-not-proven-equivalent.md))
- [ ] Deferred by [ADR-108](docs/adr/108-result-return-mapping.md): a non-throwing `bool TryRun(out T)` overload beside the throw-on-failure binding, so a C# caller can distinguish an expected `Result.failure` from an unexpected exception; `Result<T>` at a property, parameter, or collection-component position, and `Flow<Result<T>>`, all stay named skips.
- [ ] Deferred by [ADR-106](docs/adr/106-uuid-mapping.md): the binary two-`INT64` `Uuid` wire (spike-verified against real .NET, recorded in the ADR's Alternatives) as a follow-up if the per-crossing hex-dash string allocation and parse ever measurably matters.

## Phase 5: Exception handling
- [ ] **`fun dispose()` crashes KSP with a raw stack trace instead of a diagnostic.** ([details](docs/backlog/fun-dispose-crashes-ksp-raw-stack-trace.md))
- [ ] **Any raw `error(...)` thrown out of a planner or renderer aborts KSP at the first occurrence with no source location, so a large API surface with several such shapes surfaces them one build at a time with nothing to grep for.** The `fun dispose()` line above and [#52](https://github.com/xxfast/kotlin-native-nuget/issues/52)\x27s `No C# property type for SpecializedProtocol(...)` (closed as a located skip by #58, the general class left open) are instances; the fix is a per-declaration catch in `NugetProcessor` that reports the declaration and location and continues, as the `SKIPPED_*` diagnostics do.
- [ ] **Cosmetic: `CirClassRenderer.renderConstructor`'s custom-body path double-indents the first line of a constructor body.** It prepends 12 spaces to a body that already carries its own indentation, visible in generated `Interop.cs`. C# does not care; low priority. Discovered alongside the constructor reference-nullability fix above.
- [ ] **A missing constructor overload can silently resolve to the always-emitted `internal Foo(IntPtr handle)` wrapper constructor instead of failing to compile.** ([details](docs/backlog/missing-constructor-overload-can-silently-resolve-always.md))
- [ ] **The propagated `KotlinException.KotlinStackTrace` carries dozens of host-process frames below the `@CName` entry point, unsymbolizable and actively misleading.** ([details](docs/backlog/kotlinstacktrace-host-process-frames.md))
- [ ] Deferred by [ADR-107](docs/adr/107-throwable-property-mapping.md): `Throwable` at a method-return position or a parameter position, `List<Throwable>`, and a module-local non-exported `Throwable` subclass (e.g. `class MyError : Exception()` not in the export set), which the classifier's klib-origin-only supertype walk leaves classified as `Unsupported` rather than `Throwable`.

## Phase 6: Async support
- [ ] **`suspend fun load(): Result<T>` on a class emits unresolvable C# with no diagnostic.** ([details](docs/backlog/suspend-method-returning-result-emits-unresolvable-csharp.md))
- [ ] Map `SharedFlow<T>` (hot stream with subscribers – may map to `IAsyncEnumerable<T>` with replay or `IObservable<T>`)
- [ ] **`NugetMarshal.FromHandle<T>` has no enum branch, so `StateFlow<SomeEnum>` is unsupported** ([details](docs/backlog/fromhandle-no-enum-branch.md))
- [ ] `CompareAndSet` / `Update` / `Emit` / `TryEmit` / `ReplayCache` / `SubscriptionCount` on `MutableStateFlow<T>`: deferred, additive; mirrors SKIE's wider `SkieSwiftMutableStateFlow<T>` surface (ADR-071 Alternative 4)
- [ ] Nullable element write (`MutableStateFlow<T?>.Value = ...`) and nullable member write, and `suspend fun` returning `MutableStateFlow<T>`: deferred, mirror the read-side nullable/suspend items above
- [ ] Reassigning the whole `MutableStateFlow<T>` member (a `var` member, not just its `.value`): out of scope for ADR-071, which only covers the element write
- [ ] Nullable-member support (`_has_value` probe) is generated symmetrically for both a `StateFlow<T>?` property and a non-suspend function returning `StateFlow<T>?`, but only the property shape has a fixture/test (`CatMoodTracker.maybeMood`/`maybeStreak`, `NullableStateFlowTests.cs`). The function-return shape compiles but is untested; confirm it before relying on it.
- [ ] Top-level `suspend fun` returning `StateFlow<T>` (no parent class scope): deferred, needs a scope decision for the shared `nuget_stateflow_collect` export; v1 covers class methods only
- [ ] Nullable `suspend fun (): StateFlow<T>?` / `StateFlow<T?>`: mirrors ADR-065's deferred nullable items combined with ADR-019's deferred nullable-suspend two-call pattern
- [ ] `StateFlow<T>` as a function parameter (C#→Kotlin) and as a generic type argument (e.g. `Box<StateFlow<String>>`), mirrors the `Flow<T>` items below
- [ ] `INotifyPropertyChanged` adapter over `KotlinStateFlow<T>`: deferred, opt-in convenience for XAML data-binding, not the v1 core
- [ ] Map `suspend fun` returning `Flow<T>` (outer suspend kept as `Task`, composing ADR-019 over ADR-026: `Task<KotlinFlow<T>> XxxAsync()`, following the decision [ADR-068](docs/adr/068-suspend-returning-stateflow.md) established for the StateFlow sibling, not the "treat as non-suspend" phrasing this line previously carried)
- [ ] Map nullable `Flow<T>?` (requires two-call nullable pattern from ADR-002 combined with flow export)
- [ ] Map `Flow<T>` as a function parameter (C#→Kotlin direction; requires Phase 7 bidirectional support)
- [ ] Map `Flow<T>` as a generic type argument (e.g., `Box<Flow<String>>`)
- [ ] Flow backpressure support (bounded `Channel<T>` with explicit resume signaling)
- [ ] Add `@ExperimentalNugetCoroutineApi` opt-in annotation and KSP warning for classes with suspend methods (see [ADR-021](docs/adr/021-structured-concurrency.md))
- [ ] **A materialisation throw inside a `Flow<T>`'s `onNext` callback kills the host process** ([details](docs/backlog/flow-onnext-materialisation-throw-kills-host.md))
- [ ] Re-evaluate the generated `@OptIn` for `CoroutineStart.ATOMIC` (marker moved since kotlinx.coroutines 1.9) ([details](docs/backlog/coroutine-optin-atomic-delicate.md))

## Phase 7: Bidirectional support (C# → Kotlin)
- [ ] Exception propagation from a C# callback into Kotlin (mirror of ADR-024/028/029); ADR-102 sets the v1 fail-fast policy this replaces. [ADR-104](docs/adr/104-reverse-thunk-error-channel.md)'s "Forward-direction convergence" section concludes this should use the same trailing-`errOut`-parameter wire shape and the same `NugetManagedException`, not a new envelope ([details](docs/backlog/csharp-callback-exception-into-kotlin.md))
- [ ] `Flow<T>` / suspend lambda (`suspend (T) -> R`) as a function parameter
- [ ] **The `add*/remove*` subscription route silently mis-handles two member shapes it doesn't restrict for.** ([details](docs/backlog/add-remove-subscription-route-silently-mis-handles.md))
- [ ] **The same route's object/string parameter marshalling disposes the argument `StableRef` up to three times.** ([details](docs/backlog/same-route-s-object-string-parameter-marshalling.md))
- [ ] **A callback invocation racing past `removeListener`/`Dispose()` can now trip `Environment.FailFast`** ([details](docs/backlog/callback-race-past-dispose-failfast.md))

## Phase 8: Ecosystem – consuming NuGet packages from Kotlin

The inverse of everything above, modeled on the Kotlin CocoaPods plugin: resolve a C# NuGet dependency, extract its public API from .NET assembly metadata, generate Kotlin-idiomatic bindings. Kotlin → C# calls need no CLR hosting (the host process is always .NET; the generated C# side registers function-pointer thunks with Kotlin at startup). Research: [synthesis + candidate ADRs](docs/research/nuget-plugin-architecture-synthesis.md).

- [ ] Local path / `.nupkg` file source for a dependency (dev-loop flow, synthesis D6)
- [ ] Extension-level shared feed list (`sources { url("...") }`) – v1 supports per-dependency `source` only
- [ ] Multiple `bind {}` blocks per dependency (distinct namespace groups with different `packageName` values)
- [ ] Tooling UX: detect `dotnet` on PATH (or `local.properties` override) with explicit install guidance; self-heal retry on transient feed failures (mirror of CocoaPods `pod install --repo-update`) – deferred to a follow-up
- [ ] Surface `RirDiagnostic` to the build. ([details](docs/backlog/surface-rirdiagnostic-build.md))

Post-goal expansion of the bridgeable subset is broken out into Phases 9–13 below, mirroring the forward-direction arc (Phase 3 → 7): basic types, rich types, exceptions, async, then bidirectional contracts.

## Phase 9: Reverse basic type support – C# objects in Kotlin

Mirror of Phase 3. Moves the reverse bridge beyond v1 static methods: C# objects become Kotlin classes backed by opaque handles, over the same ADR-041 registration table. All constructs here are already present in `reverse-ir.json` (ADR-046) or are small extraction additions – this phase is primarily stub-gen + shim-gen coverage, relaxing the ADR-043 v1 ceiling construct by construct.

- [ ] **A bound C# class with an `init`-only property generates a Kotlin setter whose C# thunk does not compile.** ([details](docs/backlog/bound-c-class-init-only-property-generates.md))
- [ ] `Nullable<T>` value types (`int?`) are a different, deferred feature (ADR-053 Decision 3): no `NullableAttribute` machinery applies. ([details](docs/backlog/nullable-t-value-types-int-different-deferred.md))
- [ ] Alternate constructors on a Shape B struct: diagnosed, not bound, since the struct's object-initializer primary already reaches every component and an alternate would collide with it. Deferred by [ADR-058](docs/adr/058-csharp-shape-b-structs-in-kotlin.md) Decision 4a; needs a rule for the primary-constructor collision before it can bind
- [ ] Manual (non-auto) settable properties as Shape B components ([details](docs/backlog/manual-non-auto-settable-properties-shape-b.md))
- [ ] Shape A constructor-parameter nullability decoding, a pre-existing ADR-056 gap ([details](docs/backlog/shape-a-ctor-param-nullability-decoding.md))
- [ ] Raising the 22-argument ceiling: pass a pointer to a packed scratch buffer once a member's flattened arity exceeds 22, instead of skipping the member. Sketched, not decided: needs its own call on buffer ownership, layout, and alignment (deferred by [ADR-059](docs/adr/059-nested-struct-components-in-kotlin.md) Decision 5d)
- [ ] `Nullable<T>` components, including a nullable *nested* struct (e.g. `Profile?` inside `Litter`): the existing struct out-pointer + `byte hasValue` format (see the `Nullable<T>` value types item above) extends to it without a new wire format, but it stays its own ROADMAP slice (deferred by [ADR-059](docs/adr/059-nested-struct-components-in-kotlin.md) Scope)
- [ ] Class-typed (handle) components inside a struct: deferred on semantics, not cost. ([details](docs/backlog/class-typed-handle-components-inside-struct-deferred.md))
- [ ] **A bound class returning an interface declared in a different bound namespace generates non-compiling Kotlin.** ([details](docs/backlog/bound-class-returning-interface-declared-different-bound.md))

## Phase 10: Reverse rich type support

Mirror of Phase 4: generics, collections, delegates, and the C#-specific surface sugar that has a direct Kotlin idiom.

- [ ] BCL generic instantiations (`List<int>`, `Dictionary<string,int>`) are a deliberate narrowing, not an oversight ([details](docs/backlog/bcl-generic-instantiations-list-int-dictionary-string.md))
- [ ] Generic structs (`KeyValuePair<K,V>` and similar): deferred by [ADR-056](docs/adr/056-csharp-structs-in-kotlin.md) Scope; open generic structs stay excluded per ADR-043, closed constructed ones land with this item. Unaffected by ADR-072, which is classes only
- [ ] Constraints in the Kotlin surface (`where T : class` → `<T : Any>`): v1 reads `ReferenceTypeConstraint` from metadata but deliberately emits no Kotlin type-parameter constraint (ADR-072 Decision 8); a consumer can write an unbound instantiation's type but can never construct or obtain one
- [ ] Generic interfaces (`IBox<T>`) remain excluded (`skipped_generic_interface`, ADR-070), unaffected by ADR-072's witness shape for classes; needs its own decision on variance and the instantiation-by-interface matrix
- [ ] Map C# collections → Kotlin collections (`IReadOnlyList<T>` → `List<T>`, `IDictionary<K,V>` → `MutableMap<K,V>`, eager copy; mirror of ADR-011)
  - [ ] Structs as collection elements (`List<Point>`): deferred by [ADR-056](docs/adr/056-csharp-structs-in-kotlin.md) Scope
- [ ] Map delegate parameters (`Func<>` / `Action<>` / custom delegates) → Kotlin function types (builds directly on the ADR-036 reverse machinery, direction inverted)
- [ ] Map default parameter values → Kotlin default arguments (constants are in metadata)
- [ ] Map C# extension methods → Kotlin extension functions
- [ ] Map indexers → Kotlin `operator fun get`/`set`
- [ ] Map operator overloads → Kotlin operator functions (where a Kotlin operator exists; skip + warn otherwise per ADR-043 diagnostics)
- [ ] Map `params` arrays → `vararg`
- [ ] `ref` / `out` parameters – decide mapping (multi-value return data class) or document as permanently excluded (needs ADR)

## Phase 11: Reverse exception handling

Mirror of Phase 5. [ADR-104](docs/adr/104-reverse-thunk-error-channel.md) shipped the channel: any
managed call can throw and the exception now reaches Kotlin as a catchable
`NugetManagedException(managedType, message)` instead of a fatal `[UnmanagedCallersOnly]` escape.
The four remaining items below are deliberately deferred (Fork B); each is now purely additive
under ADR-104's design, one or two more runtime accessor slots, never touching thunk arity or the
per-type contract hash again.

- [ ] Map core .NET exceptions to Kotlin analogs (`ArgumentException` → `IllegalArgumentException` etc. – ADR-029's table, reversed). Additive: a Kotlin `when` over `managedType`, per ADR-104 Fork D
- [ ] Propagate the .NET stack trace on the Kotlin exception (mirror of ADR-027). Additive: one more runtime accessor slot plus one field, per ADR-104 Fork B
- [ ] Map `InnerException` → Kotlin `cause` chain (mirror of ADR-028). Additive: a `count`/indexed accessor pair mirroring `nuget_kotlin_error_cause_*`, per ADR-104 Fork B
- [ ] Propagate property accessor and constructor exceptions (mirror of ADR-030/031). The channel already carries these (getter/setter and constructor thunks all gained `errOut`); this line tracks the remaining ADR-029/027/028-equivalent fidelity (typed mapping, stack trace, cause chain) for those call shapes specifically

## Phase 12: Reverse async support

Mirror of Phase 6.

- [ ] Map `Task` / `Task<T>` → `suspend fun` (mirror of ADR-019; completion callback over the ABI, no CLR hosting needed per ADR-041)
- [ ] Wire coroutine cancellation → `CancellationToken` (mirror of ADR-022, direction inverted)
- [ ] Map `IAsyncEnumerable<T>` → `Flow<T>` (mirror of ADR-026)
- [ ] Map C# events → Kotlin (`Flow<T>` or listener + `Cleaner`-scoped subscription; no forward-direction mirror exists – needs ADR, builds on ADR-037 stored-callback machinery)

## Phase 13: Reverse bidirectional – implementing C# contracts in Kotlin

Mirror of Phase 7, composed with its machinery.

- [ ] Pass Kotlin lambdas where a C# API stores the delegate (lifetime beyond the call – mirror of ADR-037)
- [ ] Kotlin subclassing C# **classes** – explicitly deferred, revisit only with a concrete use case (synthesis D5, Swift-export precedent)
- [ ] **Collection-typed slots for a Kotlin-implemented C# interface.** ([details](docs/backlog/collection-typed-slots-kotlin-implemented-c-interface.md))
- [ ] **`Task`-typed members on a Kotlin-implemented C# interface.** Deferred to compose with Phase 12's reverse async work; a `Task`/`Task<T>`-returning interface member is out of the v1 slot vocabulary and named-skipped.
- [ ] **Whether Kotlin frees an ADR-088 bound-interface transfer `GCHandle` when the callee throws is unverified either way.** ([details](docs/backlog/whether-kotlin-frees-adr-088-bound-interface.md))

## Post-migration hardening

Follow-up work from the completed [forward marshalling centralization migration](MIGRATION.md).

- [ ] **A nullable collection method return (`fun x(): List<T>?`) has no planner route and is silently skipped, for every component type, not only value classes.** ([details](docs/backlog/nullable-collection-method-return-fun-x-list.md))
- [ ] **Coverage gaps in the collection-property-setter diagnostic path, named but not chased while building ADR-075** ([details](docs/backlog/coverage-gaps-collection-property-setter-diagnostic-path.md))
- [ ] **A property plan's `helperRequirements` is computed from the property's declared type only, ignoring its receiver's type, and nothing currently checks it.** ([details](docs/backlog/property-plan-s-helperrequirements-computed-from-property.md))
- [ ] **Make list-input eligibility match element marshalling.** ([details](docs/backlog/make-list-input-eligibility-match-element-marshalling.md))

## Tooling & Test Integrity

Fallout from [ADR-053](docs/adr/053-nullable-reference-types-in-kotlin.md) (reverse nullability), the first fixture to exercise the reverse bridge realistically. It flushed out four latent defects, **two of which were phantoms of stale build state**, and the debugging cost was dominated by having no way to observe the bridge and no way to trust the build. These items exist so the next feature does not pay that cost again.

**Rejected: a `scripts/verify.sh --fast` mode.** Measurement killed the premise (verify is 38s clean, 18s warm) and a fast mode would sanction exactly the stale-state phantoms this section exists to prevent. Do not re-add it; full writeup in the [archive](docs/roadmap-archive.md).

- [ ] **`NugetPlugin.kt`'s `packNuget` `afterEvaluate` block computes a local `baseName` that is never read afterwards** ([details](docs/backlog/nugetplugin-kt-s-packnuget-afterevaluate-block-computes.md))
- [ ] **`PackNugetTask.kt`'s `generatedCsDirs` merge silently drops a missing directory with `?: emptyList()`** ([details](docs/backlog/packnugettask-kt-s-generatedcsdirs-merge-packnugettask-kt.md))
- [ ] **An interface with a member outside ADR-084's v1 slot vocabulary silently gets no bridge factory at all** ([details](docs/backlog/interface-var-property-any-other-member-outside.md))
- [ ] **A consumer file containing only generic classes fails generation outright** ([details](docs/backlog/consumer-file-containing-only-generic-classes-fails.md))
- [ ] **Route the composed inner types of the legacy protocols through `BridgeType`.** ([details](docs/backlog/route-composed-inner-types-legacy-protocols-through.md))
- [ ] **A non-exhaustive `BridgeType` `when` silently swallows a new sealed variant into whatever its `else` does; only an exhaustive `when` gets the compiler's help.** ([details](docs/backlog/non-exhaustive-bridgetype-when-silently-swallows-new.md))
- [ ] **Compile the generated Kotlin in the plugin unit tests.** ([details](docs/backlog/compile-generated-kotlin-plugin-unit-tests.md))
- [ ] **`Tier1CinteropStub`'s hand-written `cinterop` stand-in only stubs `CFunction.invoke` for arity 0, 1 and 2.** ([details](docs/backlog/tier1cinteropstub-arity-ceiling.md))
- [ ] **The forward processor has almost no unit-test seam.** ([details](docs/backlog/forward-processor-has-almost-unit-test-seam.md))
- [ ] **Add an end-to-end `scripts/verify-incremental-regeneration.sh` backstop for KSP incremental correctness.** ([details](docs/backlog/add-end-end-scripts-verify-incremental-regeneration.md))
- [ ] **The reverse interop output directory does not clean orphaned files from a prior run** ([details](docs/backlog/reverse-interop-output-directory-does-clean-orphaned.md))
- [ ] **One fatal forward diagnostic still lives outside `ForwardDiagnosticKind`.** ([details](docs/backlog/one-fatal-forward-diagnostic-still-lives-outside.md))
- [ ] **`CirClass.hasInternalHandleConstructor` (`CirModel.kt:57`) is dead configurability.** It defaults to `true` and no production code path ever sets it to anything else; only two test call sites touch it. Not a bug, noticed while reading `CirClassTranslator.kt` for the reference-nullability constructor-collision fix. Worth either wiring a real caller or removing the parameter.
- [ ] **KotlinPoet's `addCode(String)` parses `%` as a format placeholder even on the no-varargs overload.** ([details](docs/backlog/kotlinpoet-s-addcode-string-parses-format-placeholder.md))
- [ ] **Nothing checks that two packaged targets generate the same C# API, and after [ADR-074](docs/adr/074-expect-actual-declarations.md) they can legitimately differ.** ([details](docs/backlog/nothing-checks-two-packaged-targets-generate-same.md))
- [ ] **The stored-bridge survives-a-collection test proves only identity-token resolution, not a real member call through the stored bridge.** ([details](docs/backlog/stored-bridge-survives-collection-test-proves-only.md))
- [ ] **Forward `BuildMapped` (the ADR-029 exception type map) is `private`, so the reverse direction duplicates the same switch** ([details](docs/backlog/forward-buildmapped-adr-029-net-exception-kotlin.md))
- [ ] **`CirTranslator.kt:660` carries a pre-existing "unnecessary safe call on a non-null receiver of type `KSType`" compiler warning.** ([details](docs/backlog/cirtranslator-kt-660-carries-pre-existing-unnecessary.md))
- [ ] **`ForwardAbiContract.csharpType` strips a leading `[MarshalAs(...)]` only on the `out`-prefix branch, not the by-value branch** ([details](docs/backlog/forwardabicontract-s-csharptype-parameter-nativetype-strips.md))
- [ ] **Two coverage gaps left deliberately cold by [ADR-098](docs/adr/098-narrow-primitive-and-char-collection-components.md).** ([details](docs/backlog/two-coverage-gaps-left-deliberately-cold-adr.md))
- [ ] **`Tier1NamedSkipDiagnosticsTest` and `Tier1CompileCellsTest` cells that used `Short`/`Char` as stand-ins for "bridgeable but not wrappable" need a new home.** ([details](docs/backlog/tier1-short-char-standin-cells.md))
- [ ] **Manually re-verify the Mac Catalyst repro without `MtouchInterpreter`** ([details](docs/backlog/catalyst-repro-without-mtouchinterpreter.md))
- [ ] **No Tier 1 test had ever actually compiled a suspend fixture** ([details](docs/backlog/tier1-suspend-fixture-never-compiled.md))
- [ ] **A bound method with an interface-typed parameter and a struct return emits an unresolvable `handleOf(...)`, failing the Kotlin compile.** ([details](docs/backlog/struct-return-interface-parameter-unresolvable-handleof.md))
- [ ] **Four reverse doc pages (`structs.md`, `static-classes-and-methods.md`, `generic-types.md`, `instance-members.md`) show pre-ADR-104 thunk snippets missing the trailing `errOut`.** ([details](docs/backlog/reverse-doc-snippets-missing-adr-104-errout.md))

## Future Improvements

- Support flat/unnested sealed class hierarchies (subclasses as top-level in same namespace)
- KSP incremental processing if build times become a concern on large libraries. Prerequisite: restore a correct per-file dependency set first; the processor deliberately declares `Dependencies.ALL_FILES` to close a dropped-suspend-functions bug (full context in the [archive](docs/roadmap-archive.md))
- Map data classes to C# `record class` if a safe `with`-expression pattern can be found (see [ADR-008](docs/adr/008-data-class-mapping.md))
- Verify Kotlin GC actually frees objects after all StableRefs are disposed (requires Kotlin-side weak references + GC trigger – not feasible in standard unit tests)
- Memory leak detection tooling for bridged objects in CI
- Object identity preservation (caching wrappers) if profiling shows allocation overhead is significant
- Custom type mappers for arbitrary third-party dependency types the plugin will never hardcode (escape hatch). Known stdlib types such as `kotlin.time.Instant` are Phase 4 first-class mappings instead, not this item
- Pure-JVM ECMA-335 metadata reader + NuGet v3 client, dropping the .NET SDK prerequisite from the Kotlin-side build (synthesis D1) – no plugin in this space runs prerequisite-free; a genuine ergonomic edge
- Hand-written C# shim escape hatch for API members outside the auto-bridgeable subset (spm4Kmp's bridge-folder model, synthesis D2)
- Local-feed dev loop: let a C# consumer iterate against a locally built `.nupkg` before publishing (KMMBridge-style dual flow, synthesis D6). The open part is cache-safe re-publishes of a changed `.nupkg`; see the [archive](docs/roadmap-archive.md)
- First-class Gradle publishing workflow for generated NuGet packages: a `publishNuget` task + DSL mirroring Gradle's Maven publishing APIs, so authors never invoke `dotnet nuget push` themselves ([archive](docs/roadmap-archive.md))
- Publish to GitHub Packages as a secondary registry, mirroring KStore. Worth it only as a mirror beside the primary registries, since GitHub Packages needs a token even to read public packages ([archive](docs/roadmap-archive.md))
- Forward un-skip/remap escape hatch: a Xamarin-`Metadata.xml`-style (or `@HiddenFromObjC`-style opt-out) mechanism to force-bind or remap a construct [ADR-064](docs/adr/064-forward-unsupported-declaration-diagnostics.md) would otherwise skip. Deferred by ADR-064's Scope; today the diagnostic's hint text names the hand-written-adapter workaround instead, the same choice ADR-043 made for reverse
- Reverse-bridge integration tests against real published NuGet packages (not just the controlled `TestDependency` fixture) – chosen precisely because they *don't* fit the bridgeable subset cleanly, to prove the ADR-043 skip diagnostics and partial-binding behaviour on messy real-world surfaces (mixed bridgeable/unbridgeable members, legacy nullability, multi-TFM)
- Opt-in compiled-assembly packaging mode: ship the C# shim as a prebuilt `lib/<tfm>/*.dll` instead of the [ADR-050](docs/adr/050-end-to-end-packaging-integration.md) source shim. Flagged by an external integration report (2026-08-20); needs its own ADR, and cuts against the no-.NET-SDK goal ([archive](docs/roadmap-archive.md))
- Map KDoc annotations to C# XML docs for better IDE support (from Kotlin source KDoc or signatures; deferred out of the v0.1.0 MVP)
- Expose Kotlin `Job` as a mapped C# type so cancellation can be tied to the job directly (e.g., `job.Cancel()`) instead of requiring a pre-created `CancellationTokenSource`
- NativeAOT compatibility for the generated bindings ([ADR-038](docs/adr/038-aot-compilation.md)). Reflection-free generic dispatch shipped via [ADR-094](docs/adr/094-reflection-free-generic-dispatch.md); the AOT smoke-test lane and the `[UnmanagedCallersOnly]` callback port are real roadmap items now, leaving only the interop-dialect switch below ([archive](docs/roadmap-archive.md))
- `[DllImport]` → `[LibraryImport]` for the AOT-friendly interop dialect (ADR-038 step 3, still open; narrowed by ADR-094 to the one remaining axis of the originally proposed `CSharpProfile`, since the `genericDispatch` axis no longer needs a profile toggle)
- Swap the forward *reader* from KSP to a K2/IR compiler plugin, **if and when the compiler plugin API stabilizes**. Reader only; the Gradle plugin's orchestration stays regardless. Full cost/benefit writeup in the [archive](docs/roadmap-archive.md)
