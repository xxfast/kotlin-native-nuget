# Map C# delegate parameters (`Func<>` / `Action<>` / custom delegates) to Kotlin function types

- ROADMAP: line 165 (Phase 10) as of 2026-09-21: "Map delegate parameters (`Func<>` / `Action<>` / custom delegates) → Kotlin function types (builds directly on the ADR-036 reverse machinery, direction inverted)". Adjacent lines read with it: Phase 13 "Pass Kotlin lambdas where a C# API stores the delegate (lifetime beyond the call: mirror of ADR-037)" and Phase 12 "Map C# events → Kotlin".
- Researched: 2026-09-21, two passes. Pass 1 (about 10 of 25 minutes): source reading only. Pass 2 (about 10 of 25 minutes, same day, main at `651cb7d0`, so no `file:line` drifted): the spikes pass 1 skipped were RUN in a dedicated worktree and a temp dir; see `## Spikes run (2026-09-21)`. A claim below is **verified by spike** only where that section quotes the output, **verified by reading** where it says so, and **inferred** otherwise. Prior-art claims about other ecosystems come from documentation recalled, not re-fetched: all **inferred**.
- Restatement: a Kotlin consumer of a bound NuGet package calls a C# method or constructor that declares a delegate parameter and passes an ordinary Kotlin lambda: `workshop.apply(21) { it * 2 }`, `workshop.forEachName { println(it) }`. Reverse direction; C# declares, Kotlin consumes. The C# callee sees a real `Func<int,int>` / `Action<string>` / custom delegate instance, may invoke it synchronously, later, on another thread, or store it; a Kotlin throw inside the lambda surfaces as a catchable exception on both sides instead of killing the host.
- Verdict: **fix**, new ADR needed (drafted below as a section, Proposed, number to be assigned by the main thread; NOT created in `docs/adr/`). The Phase 13 "stored delegate" line is **subsumed** by the recommended lifetime rule (it becomes a test row of this item, not a second feature). The ROADMAP's framing "ADR-036 reverse machinery, direction inverted" is the wrong precedent: the mechanism to reuse is ADR-085/086/087/089 (Kotlin-implemented C# interface), a delegate being a one-slot interface.

## Findings

### 1. LOAD-BEARING latent bug: a custom `delegate` declaration in a bound namespace is classified as an ordinary class today (verified by spike a, 2026-09-21; the generated Kotlin for it was not run)

Dated note, 2026-09-21: spike a confirmed this finding in full and widened it in three ways. The shipped reader, run on a net8.0 classlib declaring `public delegate int Transform(int x);` and `public delegate TOut Transformer<TIn, TOut>(TIn x);`, emitted:
- `{"kind":"class","name":"Transform", "methods":[{"name":"Invoke", ... "managedSignature":"method|instance|Probe.Transform|Invoke|(System.Int32)|System.Int32"}], "constructors":[] }`. The `(object, IntPtr)` constructor is dropped with NO diagnostic (pass 1 said "skipped"; it is silent). `BeginInvoke`/`EndInvoke` each yield `skipped_unbound_type_reference` naming `System.AsyncCallback` / `System.IAsyncResult`, so every custom delegate in a real package already adds two noise diagnostics.
- The generic delegate becomes a GENERIC CLASS: ``{"kind":"class","name":"Transformer`2","typeParameters":["TIn","TOut"],"instantiations":[{"typeArguments":[int,int]}]}`` with `Invoke` over `typeparam` refs, and `ApplyGeneric(int, Transformer<int,int>)` binds its parameter as ``{"kind":"generic","namespace":"Probe","name":"Transformer`2",...}``. The "fake class with a witness bridge" pass 1 predicted is what happens today.
- `ApplyNamed(int, Transform)` binds with `"step":{"kind":"handle","namespace":"Probe","name":"Transform"}`: a handle parameter no Kotlin code can construct (no constructor was extracted).
- NEW: a delegate at a RETURN position (`public Transform MakeDoubler()`) binds today as a `handle` return, and the fake class has an `Invoke` method, so `workshop.makeDoubler().invoke(21)` is, inferred, a working accidental binding. Excluding delegate TypeDefs from class extraction therefore REMOVES a return-position surface that may work today. No fixture or doc depends on it (verified by grep), but the ADR's Consequences must say so, and `skipped_delegate_position` must name the member so the loss is visible.

`NugetMetadataReader/Program.cs:278-287` (the bound-handle-type name collector) excludes interfaces, static classes, value types (`IsValueType`, `:2622-2631`, base `System.ValueType`/`System.Enum`) and ref structs. Nothing anywhere in the reader tests `BaseType == System.MulticastDelegate`: `grep -n -i delegate Program.cs` returns exactly one hit (`:3686`, the function-pointer `delegate*` raw name), and `BaseType` is read only at `:2594-2596` (`IsEnum`) and `:2624-2628` (`IsValueType`). A C# `public delegate int Transform(int x);` compiles to a sealed class extending `System.MulticastDelegate` (ECMA-335 II.14.6, inferred from the spec), so it lands in `_boundHandleTypeNames` and is extracted as an `RirClass`. Inferred consequence: it binds as a handle class with an `invoke(x)` instance method (`Invoke` is a public, non-SpecialName, virtual instance method), a skipped `(object, IntPtr)` constructor and skipped `BeginInvoke`/`EndInvoke` (`IAsyncResult`/`AsyncCallback` unbound). A method parameter of that delegate type then resolves to `RirObjectHandleType` and binds as a handle parameter no Kotlin code can ever construct. No fixture pins this: `grep "delegate \|Func<\|Action<\|Predicate<\|EventHandler\|event "` over `TestDependency/*.cs` and `sample-dependency` returns nothing (verified by grep).

An `IsDelegate` helper (same shape as `IsEnum` at `:2591-2597`) has to run at every type-classification site (`:286`, `:848`, `:1094`, `:3268`, and the main class-extraction loop) and BEFORE the ADR-072 bound-definition branch at `:3465-3498`, or a closed generic custom delegate (`Transformer<int>`) becomes an `RirGenericInstanceType` over a fake class with a witness bridge.

### 2. Where BCL delegates fall today (verified by reading, then by spike a: `Func<int,int>`, `Action<string>` and every nullable variant gave `skipped_unbound_generic_instantiation` with the ADR-155 collection hint; `System.Action` gave `skipped_unbound_type_reference` with reason "type `System.Action` is defined outside the bound assemblies and cannot be an opaque handle in this extraction run")

- Generic (`Func<int,int>`, `Action<string>`, `Predicate<T>`, `Comparison<T>`, `Converter<TIn,TOut>`, `EventHandler<T>`): a TypeSpec, decoded by `GetGenericInstantiation`. It is not async, not a bound definition (`:3469`), not in `CollectionDefinitions` (`:3499-3527`, `:3565-`), so it reaches `:3548-3560`: `skipped_unbound_generic_instantiation`, whose hint tells the user to expose a BCL collection. Misleading for a delegate.
- Non-generic (`System.Action`, `System.EventHandler`, `System.Threading.TimerCallback`): a TypeReference, decoded by `GetTypeFromReference` (`:3353-3403`), which knows `String`, `Task`, `CancellationToken`, `ValueTask` and otherwise emits `skipped_unbound_type_reference` (`:3396-3402`), whose hint says to include `System.Private.CoreLib` in the extraction run. Also misleading.

Two insertion points, mirroring the two ADR-152 needed (`:3367-3372` documents that non-generic `Task` was misfiled exactly this way). Recommended: a `DelegateDefinitions` table keyed by CLR full name only (ADR-155's "match on namespace + name, never on resolution scope" reasoning at `:3493-3498` applies unchanged: `Func`/`Action` live in `System.Runtime` facade vs `System.Private.CoreLib`).

### 3. The mechanism to reuse is ADR-085's bridge, with N = 1 slot (verified by reading)

- Shared planner: `rir/RirBridging.kt:1352-1373` `KotlinBridgePlan(iface, slots, needsDupHandle)`; `:1497-1512` `kotlinBridgePlan`; both generator tasks project the same plan so slot order cannot drift (`:1348-1351`).
- C# half: `NugetGenerateShimsTask.kt:3193-3262` emits `private sealed unsafe class {I}Bridge : {I}, INugetKotlinBridge` holding `KotlinRefHandle _ctx` plus one `delegate* unmanaged[Cdecl]<IntPtr, args..., IntPtr*, ret>` per slot (`:3276-3292`), a `[UnmanagedCallersOnly] Create{I}Bridge(slots..., ctx)` factory returning `GCHandle.ToIntPtr(GCHandle.Alloc(new Bridge(...)))` as a TRANSFER handle (`:3245-3251`), and a `{I}KotlinToken` probe (`:3253-3258`).
- Lifetime: `KotlinRefHandle : SafeHandle` (`NugetGenerateShimsTask.kt:3002-3013`) whose `ReleaseHandle` P/Invokes `nuget_kotlin_release` (`:2984-2985`; Kotlin export at `NugetGenerateBindingsTask.kt:5465-5466`). ADR-085 spiked this by execution on 2026-08-08 (`docs/adr/085-...md:31-66`): a Kotlin `staticCFunction` with a `StableRef` ctx is callable from the main thread, a pool thread, 4 concurrent raw threads and the finalizer thread; the finalizer-driven release fires after `GC.Collect()` + `WaitForPendingFinalizers()`. Verified by that ADR's spike, not re-run here.
- Kotlin half: `NugetGenerateBindingsTask.kt:6415-6458` `kotlinBridgeBlock` (one `staticCFunction(::slot)` per slot, `StableRef.create(impl)` ctx, ADR-089 resolve-or-mint through a weak `NugetBridgeTable` keyed on `identityHashCode`), `:6463-6509` slot functions, arity-generic (`mapIndexed` over parameters).
- Error channel: `:6511-6555` every slot has a trailing `errOut` and `kotlinSlotEnvelope` catches `Throwable`, writes `nugetKotlinError(t)` and returns a dummy (ADR-087 stage 2); the C# bridge member checks it and throws `KotlinException`.
- Reentrancy (Kotlin calls C#, C# synchronously calls the Kotlin slot, same thread, same crossing) is already the shipped shape: `TestDependency/Menagerie.cs:177-178` `Introduce(IFeedable feedable) => $"introduced {feedable.Describe()} ..."`, exercised with a Kotlin `Goat` by `IntegrationTests/MenagerieRoundTripTests.cs`. Verified by reading; this is exactly what `Apply(int, Func<int,int>)` needs.
- Stored and dropped lifetimes are already pinned for the interface case: `MenagerieRoundTripTests.cs:~355-385` (dropped bridge releases within 5 s, counted through `kotlinBridgeReleaseCount`, `test-library/.../menagerie/MenagerieSample.kt:170-175` over `nugetKotlinReleaseCount`) and `:~366-384` `LiveKotlinGoatBridge_SurvivesACollection_AndStillResolves` (stored bridge survives 3 forced collections). Verified by reading.

What a delegate adds over an interface: (a) there is no `RirInterface` and no interface-owned registration export to hang the factory on (`Func<int,int>` is a BCL type); (b) the factory must return a DELEGATE instance, `new global::System.Func<int, int>(holder.Invoke)`, not the holder; (c) the identity token probe becomes `Target is Delegate d && d.Target is INugetKotlinBridge`. **Verified by spike c1 (2026-09-21, .NET half only)**: a delegate strongly roots its `Target`. A holder owning a `SafeHandle` plus a `delegate* unmanaged[Cdecl]<IntPtr, int, int, int, int, IntPtr*, int>`, wrapped as `new Func<int,int,int,int,int>(holder.Invoke)` and handed over through a transfer `GCHandle` that is then freed, was NOT released across 3 x (`GC.Collect()` + `WaitForPendingFinalizers()`) while a static field kept the delegate; invoked correctly from a pool thread (`pool invoke = 1010`); stayed unreleased after `Delegate.Combine` replaced the stored instance with a multicast one; was released once the field was nulled (`released=1`, on a thread that was neither main nor the pool thread); and a never-stored (per-call) delegate was released on the next settle after its transfer handle was freed (`released=2`). `d.Target is IBridge` printed `True` and `d.Method.Name` printed `Invoke`, so the token probe shape works. Still inferred: closed-delegate creation over an instance method is a static `ldftn` + `newobj`, no reflection and no `Marshal.GetFunctionPointerForDelegate`, so it is AOT and trimmer safe on the ADR-094/102 argument.

### 4. Per-call vs stored is NOT distinguishable from metadata (inferred from ECMA-335 and C# language docs; no counter-evidence found)

C# `scoped` applies to `ref`/`ref struct` values only; there is no escape annotation for a delegate parameter, and nothing in ECMA-335 parameter metadata records whether the callee stores its argument. `List<T>.ForEach(Action<T>)` (per-call) and `Timer(TimerCallback)` (stored) have the same parameter shape. Forward ADR-036/037 could split the two because the Kotlin AUTHOR owns the declaration and the processor sees `add*/remove*` pairs; the reverse direction reads a third party's DLL. Consequence: v1 must use ONE lifetime model that is safe for the stored case. ADR-085's model already is: the .NET GC owns the Kotlin `StableRef` through the `SafeHandle`. The price is that a per-call lambda's `StableRef` is released GC-timed, not at return. That is the same posture ADR-085 accepted ("GC-timed, never prompt", `docs/adr/085-...md:60-64`).

### 5. Slot vocabulary and arity as shipped (verified by reading)

`isKotlinBridgeSlotType` (`RirBridging.kt:1431-1449`): `void` (return only), primitives, bound enums, `string`/`string?`, bound object handles, bound interfaces. Out: structs, generic instances, type parameters, collections (`:1444-1448`, the allocator-inversion Phase 13 item). `KOTLIN_BRIDGE_MAX_ARITY = 2` (`:1452`) is a policy mirror of ADR-084 (`docs/adr/085-...md:291`), not a mechanism limit: both emitters are arity-generic (finding 3). The hard limit is `ABI_ARITY_CEILING = 22` (`RirBridging.kt:700`, ADR-059 Constraint 3, verified there by spike): slot = ctx + N + errOut, so N <= 20, above `Func`'s 16. The factory is 1 slot + ctx = 2 arguments. The ceiling is not reachable by any BCL delegate. **Verified by spike c2 (2026-09-21)**: on Kotlin 2.4.10 mingwX64, `staticCFunction(::slot4)` over `(COpaquePointer?, Int, Int, Int, Int, CPointer<COpaquePointerVar>?) -> Int` (ctx + 4 + errOut, six C parameters) compiles, and invoking the pointer with a `StableRef` ctx to a CAPTURING `(Int, Int, Int, Int) -> Int` lambda returned the right value (`SPIKE arity4 slot = 20`). So lifting the arity constant to 4 for delegate slots is a policy change only.

`Task`-returning slots are out (`ROADMAP.md` Phase 13 "`Task`-typed members on a Kotlin-implemented C# interface"); the reverse completion machinery is exactly-once C# to Kotlin (`NugetAwait.kt:23-47` per ADR-156), and a Kotlin `suspend` lambda producing a C# `Task` needs a `TaskCompletionSource` completed from a Kotlin callback, which no reverse code does today. So `Func<Task>` and `Func<CancellationToken, Task<T>>` share a design with that Phase 13 line, not with this item.

### 6. Exception path end to end (verified by reading the two ADRs; composition inferred)

Kotlin lambda throws, slot envelope writes `NugetError` (ADR-087), the C# holder's `Invoke` throws `KotlinException`, which propagates through the C# method, is caught by the outer reverse thunk (ADR-104) and reaches the Kotlin caller as `NugetManagedException(managedType = "...KotlinException", message)`. The original `Throwable` identity and type are lost; this is the round-trip fidelity loss both ADRs already deferred (`docs/adr/104-...md:463-465`, `:534`; `docs/adr/087-...md:316-318`). If C# swallows or invokes the delegate on another thread, the Kotlin caller sees nothing, which is correct.

Dated note, 2026-09-21 (spike e, settled by reading, CONTRADICTS pass 1): the holder does not always throw `KotlinException`. `NugetKotlinErrors.Build` maps the Kotlin type through the ADR-029 table duplicated at `NugetGenerateShimsTask.kt:3085-3097` (`kotlin.IllegalStateException` to `KotlinInvalidOperationException`, `kotlin.IllegalArgumentException` to `KotlinArgumentException`, and so on; only an unmapped type falls through to `KotlinException`). `managedType` is `target?.GetType().FullName` (`ManagedErrorType_Thunk`, `NugetGenerateShimsTask.kt:2651-2657`), and the exception types live in the forward bindings' namespace (`errorNamespace`, `:93-99`, `:2558`), which is `TestLibrary` for the fixture (`IntegrationTests/MenagerieRoundTripTests.cs:292` spells it fully qualified, `TestLibrary.KotlinInvalidOperationException("no vacancy")`; `IntegrationTests/ConstructorExceptionPropagationTests.cs:1` `using TestLibrary;`). So Kotlin `error("boom")` inside the lambda surfaces as `managedType == "TestLibrary.KotlinInvalidOperationException"`, and that string does NOT end with `"KotlinException"`: pass 1's sample assertion would have failed. Verified by reading (the string is a deterministic `FullName`); not executed. The shipped four-hop precedent, `MenagerieRoundTripTests.cs:290-326` (`KotlinNoVacancy_DescribeThrows_ReachesCSharpAsACatchableException`), asserts the message only, so no test pins the type string today.

### 7. RIR has no place for a delegate (verified by reading)

`rir/RirModel.kt` `RirTypeRef` variants: void, string, primitive, handle, enum, struct, interface, typeparam, generic, collection.  Adding `RirDelegateType` CHANGES the ADR-046 `reverse-ir.json` contract (unlike ADR-059, which deliberately did not). Verified by reading: `rir/RirParsing.kt:7` parses with `Json { ignoreUnknownKeys = true }`, which tolerates unknown KEYS but not an unknown `kind` discriminator on the sealed `RirTypeRef`, so a stale plugin meeting `"kind":"delegate"` fails loudly, not silently. No schema version field exists in `RirModel.kt` or `Program.cs` (verified by grep for `schemaVersion`). Inferred: the reader is always co-built with the plugin (it lives in this repo as `NugetMetadataReader/` and is run by `NugetExtractApiTask`), so a version skew is not a supported configuration; the implementer should confirm what ADR-046 says about adding a variant (not read in full). `RirDiagnosticKind` has `skipped_event` but nothing delegate-shaped. C# twin records and `[JsonDerivedType]` list at `Program.cs:4040-4049`. Events are dropped with `skipped_event` at `Program.cs:1732-1754`, whose hint already promises "once callback parameters are supported".

### 7a. `NullableAttribute` layout for delegate types (verified by spike b, 2026-09-21) and the two silent traps it exposes

Decoded from a net8.0 `<Nullable>enable</Nullable>` assembly with `System.Reflection.Metadata` (blob = prolog `01-00`, then a `byte[]` with a 4-byte count, or a single byte):

| Parameter | Attribute found | Payload bytes |
|---|---|---|
| `Action<string?> a` | `NullableAttribute` on the param | `[1, 2]` (blob `01-00-02-00-00-00-01-02-00-00`) |
| `Func<string?, string>? b` | `NullableAttribute` on the param | `[2, 2, 1]` (blob `01-00-03-00-00-00-02-02-01-00-00`) |
| `Func<int, string?> c` | `NullableAttribute` on the param | `[1, 2]`: the `int` argument consumes no byte |
| `Func<int, int> d`, `Func<string, string> e` | none on param or method | type-level `NullableContextAttribute(1)` applies |
| `Func<string?, string?>? f` | none on the param; METHOD-level `NullableContextAttribute(2)` | every node is 2 |
| `Func<int, int>? g` | none on the param; METHOD-level `NullableContextAttribute(2)` | the delegate node is 2 |

Every attribute constructor was `ctor=MemberReference` with the type's resolution scope an `AssemblyReference` (the ADR-053 trap is real for delegates too). So: one byte per reference-type node, pre-order, delegate node first, value-type arguments consume none, and the uniform case collapses into a context attribute with NO per-parameter attribute at all (rows f and g), which is how a nullable delegate parameter usually arrives.

The shipped decoder already implements exactly this rule (verified by reading): `CountAnnotatableNodes` `Program.cs:2756-2770`, `ApplyPreOrder` `:2832-2884`, the member to method to type to oblivious chain with single-byte broadcast `:2905-2932`, the `MemberReference` constructor branch `:2658-2660` and `:3007`, and a LOUD `skipped_generic_type_argument` when the byte count disagrees with the node count (`:2793-2806`). So no new decoding is needed, but two ways of wiring `RirDelegateType` in are SILENT:

1. If `RirDelegateType` is not added to `IsAnnotatable` (`:2744-2746`), `CountAnnotatableNodes` and `ApplyPreOrder`, the node count is 0 and `:2789` returns the type unchanged with no diagnostic: every `Action<string?>` binds as `(String) -> Unit` and every `Action?` as non-null.
2. The count and the walk must run over `typeArguments` (that is what Roslyn annotates), never over the substituted `parameters`/`returnType`. If the reader derives `parameters`/`returnType` from the type arguments BEFORE nullability is applied, the applied `typeArguments` say `string?` while `parameters` still say `string`, and the generator (which reads `parameters`) silently emits the non-null type. Derive the Invoke shape AFTER `ApplyPreOrder`, or have the new `ApplyPreOrder` arm rebuild it.

For a NON-generic custom delegate (`Transform`) the parameter node count is 1 (the delegate itself); the nullability of its Invoke parameters comes from the `Invoke` MethodDef's own attributes, resolved once with the delegate TypeDef as the type tier. For a closed generic custom delegate (``Transformer`2<string?, int>``) the type-argument bytes are on the USING parameter and the Invoke signature is `!0`/`!1`, so substitution must carry the applied argument. Inferred (not spiked): a custom generic delegate whose Invoke mentions a reference type directly (`delegate T Pick<T>(string? hint)`) mixes both sources.

### 7b. Overloads that differ only by delegate shape are ambiguous for every bare lambda (verified by spike d, 2026-09-21, Kotlin 2.4.10, K2)

`fun spikeRun(a: () -> Unit)` beside `fun spikeRun(f: () -> Int)`, and `fun spikeMap(a: (Int) -> Unit)` beside `fun spikeMap(f: (Int) -> Int)`, DECLARE fine on Kotlin/Native (no JVM signature clash), but all four bare-lambda calls failed to compile: `spikeRun { 1 }`, `spikeRun { }`, `spikeMap { it * 2 }`, `spikeMap { println(it) }` each gave `Overload resolution ambiguity between candidates`, and the one-parameter pair also gave `Unresolved reference 'it'`. Pass 1 guessed "may be ambiguous"; it is ambiguous always, in both directions, including the `{ }` form. What does resolve (run, output quoted): a typed value (`val i: () -> Int = { 1 }; spikeRun(i)` printed `Int overload`; the `() -> Unit` twin printed `Unit overload`) and an anonymous function (`spikeMap(fun(x: Int): Int = x * 2)` printed `Int overload`). This is the `Task.Run(Action)` / `Task.Run(Func<T>)` shape, common in .NET, so it is now what-question 6, not a how-question.

### 8. Prior art (all inferred, from documentation recalled this session, links for the next reader)

- Kotlin consuming Java: SAM interfaces accept lambdas by SAM conversion, but `java.util.function.*` stay nominal types, not `(T) -> R` (https://kotlinlang.org/docs/java-interop.html#sam-conversions). Kotlin/Native ObjC import is the closer precedent: an ObjC block type imports as a Kotlin function type and the runtime retains the Kotlin lambda for as long as ObjC retains the block, with no per-call/stored split (https://kotlinlang.org/docs/native-objc-interop.html#callbacks-and-blocks). That is the same rule recommended here: the foreign runtime's memory manager owns the lambda.
- Swift export maps Kotlin function types to Swift closures with the same retain rule (https://kotlinlang.org/docs/native-swift-export.html).
- JNA `Callback`: the JAVA caller must keep the callback object reachable or the native side crashes (https://java-native-access.github.io/jna/5.13.0/javadoc/com/sun/jna/Callback.html). The well-known footgun; the reason not to make the Kotlin caller own the lifetime.
- Xamarin.Android binding: a Java SAM/listener parameter gets a generated `I*Implementor` peer holding the C# delegate, kept alive by the Java side through a GREF; `Java.Lang.Runnable(Action)` is the hand-written form (https://learn.microsoft.com/en-us/previous-versions/xamarin/android/platform/binding-java-library/). Mirror image of the holder class here.
- CsWinRT: a projected delegate passed to WinRT is wrapped in a ref-counted CCW; the native side's `Release` frees it. Same ownership direction.
- Python.NET: a Python callable converts to a .NET delegate whose target holds the `PyObject`; released when the delegate is collected (https://pythonnet.github.io/pythonnet/python.html#delegates-and-events).

Every analogue that lets the callee store the callback gives ownership to the CALLEE's runtime. None distinguishes per-call from stored. Analogues skipped: Dukat/Kotlin-JS (same GC on both sides, nothing to learn about lifetime), CocoaPods/SPM plugin studies (build-time resolution, not type mapping).

## Recommendation

One ADR, size M/L. End state:

1. **Reader**: `MetadataHelpers.IsDelegate`; delegate TypeDefs never become `RirClass`. New `RirTypeRef` variant, first-class (not folded into `RirGenericInstanceType`):
   ```kotlin
   @Serializable @SerialName("delegate")
   data class RirDelegateType(
     val definition: String,               // CLR full name: "System.Func`2", "Test.Workshop.Transform"
     val typeArguments: List<RirTypeRef>,  // for spelling the closed C# type; empty when non-generic
     val parameters: List<RirTypeRef>,     // the Invoke signature AFTER type-argument substitution AND after ADR-053 nullability was applied to typeArguments (finding 7a trap 2)
     val returnType: RirTypeRef,
     val nullable: Boolean = false,
   ) : RirTypeRef
   ```
   Sources: (a) the `DelegateDefinitions` name table for `System.Action`, ``System.Action`1..16``, ``System.Func`1..17``, ``System.Predicate`1``, ``System.Comparison`1``, ``System.Converter`2``, whose Invoke shape is derived from the type arguments by rule; (b) a custom delegate DECLARED IN A BOUND ASSEMBLY, whose Invoke `MethodDefinition` signature is decoded with the closed instantiation's generic context. A custom delegate declared in an unbound external assembly keeps its current diagnostic (the reader has the TypeReference only, no Invoke signature; inferred that the reader does not resolve external TypeDefs).
   Dated note, 2026-09-21 (from spikes a and b): (i) `IsDelegate` must also suppress the two `BeginInvoke`/`EndInvoke` `skipped_unbound_type_reference` diagnostics and the silent constructor drop that the class route produces today, by never entering member extraction for a delegate TypeDef; (ii) `RirDelegateType` joins `IsAnnotatable`, `CountAnnotatableNodes` (`1 + typeArguments.Sum(...)`) and `ApplyPreOrder` in the same commit that introduces it, with a reader test per row of the finding 7a table, because omitting it is silent; (iii) the delegate-as-return surface that binds by accident today disappears and becomes `skipped_delegate_position`.2. **Kotlin surface**: a plain function type. `Func<int,int>` is `(Int) -> Int`, `Action<string?>` is `(String?) -> Unit`, `Predicate<Cat>` is `(Cat) -> Boolean`, `Comparison<string>` is `(String, String) -> Int`, a nullable delegate parameter (`Action? onDone`) is `(() -> Unit)?` with null crossing as `IntPtr.Zero`. A custom delegate additionally emits `typealias Transform = (Int) -> Int` in its namespace package so the C# name survives in IntelliJ completion; not a `fun interface` (a SAM would force `Transform { ... }` at every call site and makes `Func`-typed and custom-typed parameters feel different for no gain; ObjC-import precedent).
3. **Wire**: the delegate parameter crosses the ORDINARY method/constructor thunk as one `IntPtr` transfer `GCHandle` (same wire as an ADR-070 interface parameter, so thunk arity rules and `cfnType`/`csAbiType` gain one arm each mapping to the handle wire). The thunk casts `(global::System.Func<int, int>)GCHandle.FromIntPtr(h).Target!`. The only NEW emission is, per distinct delegate shape, a C# holder class (`KotlinRefHandle _ctx` + ONE `delegate* unmanaged[Cdecl]<IntPtr, args..., IntPtr*, ret>` + an `Invoke` member that is `bridgeMethodMember` verbatim) and a `[UnmanagedCallersOnly]` factory `(IntPtr invoke, IntPtr ctx) -> IntPtr` returning a GCHandle on `new TDelegate(holder.Invoke)`; on the Kotlin side one `staticCFunction` slot and a `mint...Delegate(f)` doing ADR-089 resolve-or-mint. The Kotlin call site frees the transfer handle after the call through the already-registered `freeGcHandleFn`, exactly like ADR-085.
4. **Registration home**: the factory slot rides the registration of the DECLARING bound type that uses the shape (one factory per distinct shape per declaring type, deduped by a shape signature), moving only that type's ADR-054 slot count and contract hash, the way `kotlinBridgeContractHash(memberHash, plan)` (`RirBridging.kt:1578-`) already folds an interface's factory slots in. Rejected: slots on the shared `<runtime>` registration (unbounded shape set, churns `NUGET_RUNTIME_CONTRACT_HASH` for every consumer). Alternative kept open as how-question 3: one `nuget_{pkg}_delegates_register` export per bound assembly.
5. **Lifetime rule (one rule, both Phase 10 and Phase 13)**: the C# delegate owns the Kotlin lambda. The `StableRef` on the lambda is released by `KotlinRefHandle.ReleaseHandle` when the .NET GC collects the holder, which happens when C# drops the delegate. The Kotlin caller owns nothing and frees nothing beyond the transfer handle; there is no `close()`, no `Cleaner`, no subscription object. A Kotlin-side `Cleaner` is structurally wrong here for the reason ADR-085 lifetime sub-decision (b) records: it builds a cross-runtime strong cycle.
6. **v1 scope**: delegate at a PARAMETER position of a method or constructor of a bound non-generic class, explicitly including a static method on a static class (the baseline ADR-041/049 route and the cheapest first commit) as well as static and instance methods on an ordinary class; shapes from the name table plus bound custom delegates (closed generic custom delegates included); Invoke parameter and return types inside the shipped slot vocabulary (finding 5); Invoke arity up to 4 (lift `KOTLIN_BRIDGE_MAX_ARITY` for delegate slots only, emitters are already arity-generic; the six-parameter `staticCFunction` slot is verified by spike c2); nullable delegate; nullable reference type arguments (layout verified by spike b). Added 2026-09-21 after spike d: an overload set whose members differ ONLY by delegate shape (`Run(Action)` / `Run(Func<int>)`) binds, every member, and the reader or planner attaches a named info diagnostic (working name `info_delegate_overload_ambiguity`) telling the consumer that a bare lambda will not resolve and to pass a typed function value or an anonymous function (`run(fun(): Int = 1)`), both verified to resolve. Dropping the set ADR-057 style is the rejected alternative: it would delete `Task.Run`-shaped APIs that remain perfectly callable. Human decision pending (what-question 6).

Priced: reader 1 file (about 6 sites), plugin main 4 files, plugin tests 3 to 4 files, fixtures 3 files, integration + leak tests 2 files, docs 4 files. About 17 files.

Alternatives rejected:
- Per-call borrow (free the `StableRef` when the C# method returns, the literal ADR-036 inversion): use-after-free the first time a callee stores the delegate, undetectable from metadata (finding 4). JNA's footgun.
- Kotlin-owned handle (`NugetCallback : AutoCloseable` the caller must keep and close): pushes a lifetime the consumer cannot know onto the consumer; not Kotlin-idiomatic; no analogue does it except JNA.
- Opt-in DSL list of "storing" methods: the user cannot know either, and the safe model costs only GC-timed release.
- `fun interface` per custom delegate: see point 2.
- Function pointer passed straight to C# with `Marshal.GetDelegateForFunctionPointer`: no ctx, so no closures; not AOT-safe (ADR-102 removed exactly this family forward).
- Pin and close with a better diagnostic only: leaves the finding 1 mis-binding in place and blocks events.

## Files an implementation touches

Reader: `NugetMetadataReader/Program.cs` (`MetadataHelpers.IsDelegate`; classification sites `:286`, `:848`, `:1094`, `:3268` plus the class-extraction loop; `GetTypeFromReference` `:3353` non-generic arm; `GetGenericInstantiation` arm before `:3469` for bound custom generic delegates and before `:3548` for the name table; `RirDelegateType` record + `[JsonDerivedType]` at `:4040-4049`; the two new diagnostic kinds; position checks in `TryMapMethod`/`TryMapConstructor`/`TryDecodePropertyType` near `:2011`, `:2216`, `:2304`, `:2405`; ADR-053 walker, all three in one commit: `IsAnnotatable` `:2744-2746`, `CountAnnotatableNodes` `:2756-2770`, `ApplyPreOrder` `:2832-2884`; a new info diagnostic kind for delegate-shape-only overload pairs).
Plugin main: `nuget-plugin/src/main/kotlin/io/github/xxfast/kotlin/native/nuget/rir/RirModel.kt` (variant + kinds), `rir/RirBridging.kt` (`isV1Type` `:1061`, `isKotlinBridgeSlotType` `:1431` refuses a delegate INSIDE a slot, a `KotlinDelegatePlan` beside `KotlinBridgePlan` `:1352`, contract-hash fold `:1578`, arity constant `:1452`, diagnostics), `NugetGenerateBindingsTask.kt` (Kotlin type spelling, typealias emission, call-site lowering + transfer free, mint block reusing `kotlinBridgeSlotFunction` `:6463` / `kotlinSlotEnvelope` `:6543` / `NugetBridgeTable` `:5257`), `NugetGenerateShimsTask.kt` (holder + factory beside `kotlinBridgeCsharp` `:3193`, thunk parameter cast, slot list in `buildRegistration` `:1410-1514`). Possibly `NugetReportDiagnosticsTask.kt` if kinds are enumerated there (not opened).
Plugin tests: new `NugetDelegateBindingTest.kt`, `NugetExtractApiIntegrationTest.kt` (named-skip assertions), `NugetGenerateShimsTaskTest.kt` / `NugetKotlinBridgeGenerationTest.kt` (text pins), `rir/` serialization test.
Fixtures: new `TestDependency/Workshop.cs`; new `test-library/src/nativeMain/kotlin/io/github/xxfast/kotlin/native/nuget/test/workshop/WorkshopSample.kt`; `test-library/build.gradle.kts` only if namespaces are listed per bind (not confirmed).
Tests: new `IntegrationTests/WorkshopRoundTripTests.cs`; `LeakTests/LiveHandleTests.cs` rows.
Docs: new `docs/topics/reverse-delegates.md` (or a section of `reverse-overview.md`), `FEATURES.md` row, `ROADMAP.md` (delete the Phase 10 line and the Phase 13 stored-delegate line together; reword the Phase 12 event line's "builds on ADR-037" to this ADR), ADR index (documenter).

## Sample test

C# fixture (`TestDependency/Workshop.cs`, namespace `Test.Workshop`):

```csharp
public delegate int Transform(int value);

public sealed class Workshop
{
    private Func<int, int>? _kept;
    public static int Twice(int seed, Func<int, int> step) => step(step(seed)); // static route, first commit
    public int Apply(int seed, Func<int, int> step) => step(step(seed));
    public void ForEachName(Action<string> visit) { visit("Oreo"); visit("Mochi"); }
    public int ApplyNamed(int seed, Transform step) => step(seed);
    public bool AnyLong(Predicate<string> test) => test("Oreo") || test("Marshmallow");
    public string DescribeOrDefault(Func<string?>? label) => label?.Invoke() ?? "none";
    public void Keep(Func<int, int> step) => _kept = step;      // STORED
    public int RunKept(int seed) => _kept!(seed);
    public Task<int> RunKeptOnPoolAsync(int seed) => Task.Run(() => _kept!(seed)); // other thread
    public void Forget() => _kept = null;
    public Task<int> LaterAsync(Func<Task<int>> work) => work(); // named skip, asserted by the plugin test
}
```

Kotlin caller (`WorkshopSample.kt`, forward-exported so xunit drives it):

```kotlin
fun workshopDoubleTwice(seed: Int): Int = Workshop().apply(seed) { it * 2 }
fun workshopNames(): String = buildList { Workshop().forEachName { add(it) } }.joinToString(",")
fun workshopThrowing(): String = try {
  Workshop().apply(1) { error("boom from Kotlin") }; "no throw"
} catch (e: NugetManagedException) { "${e.managedType}|${e.message}" }
private val held = Workshop()
fun workshopKeep(factor: Int) { held.keep { it * factor } }   // capturing lambda, then unreachable from Kotlin
fun workshopRunKept(seed: Int): Int = held.runKept(seed)
suspend fun workshopRunKeptOnPool(seed: Int): Int = held.runKeptOnPoolAsync(seed)
fun workshopForget() = held.forget()
```

xunit (`IntegrationTests/WorkshopRoundTripTests.cs`, `KennelRoundTripTests` style):

```csharp
[Fact] public void Func_IsInvokedSynchronously_Reentrant() => Assert.Equal(84, WorkshopSample.WorkshopDoubleTwice(21));
[Fact] public void Action_String_ArrivesInOrder() => Assert.Equal("Oreo,Mochi", WorkshopSample.WorkshopNames());
[Fact] public void KotlinThrowInsideLambda_IsCatchableOnTheKotlinSide()
{
    string[] parts = WorkshopSample.WorkshopThrowing().Split('|');
    // Corrected 2026-09-21 (spike e): error(...) throws kotlin.IllegalStateException, which the ADR-029
    // table in NugetKotlinErrors.Map turns into KotlinInvalidOperationException; managedType is its
    // GetType().FullName in the forward namespace. The old EndsWith("KotlinException") would have FAILED.
    Assert.Equal("TestLibrary.KotlinInvalidOperationException", parts[0]);
    Assert.Equal("boom from Kotlin", parts[1]);
}
[Fact] public async Task StoredLambda_SurvivesCollections_AndRunsOnAPoolThread()
{
    WorkshopSample.WorkshopKeep(3);
    for (int i = 0; i < 3; i++) { GC.Collect(); GC.WaitForPendingFinalizers(); NugetBridge.GcCollect(); }
    Assert.Equal(21, WorkshopSample.WorkshopRunKept(7));
    Assert.Equal(21, await WorkshopSample.WorkshopRunKeptOnPoolAsync(7));
    int before = MenagerieSample.KotlinBridgeReleaseCount();
    WorkshopSample.WorkshopForget();
    Assert.True(KotlinReleaseFiredWithin(before, TimeSpan.FromSeconds(5)));   // helper as in MenagerieRoundTripTests
}
```

Fixture additions after the spikes (2026-09-21): `public static int Sum4(Func<int,int,int,int,int> f) => f(1, 2, 3, 4);` (arity 4), `public string Shout(Action<string?> sink)` plus `DescribeOrDefault` above for the finding 7a rows (one per-parameter `NullableAttribute` row, one method-context-only row such as `public void Maybe(Func<int,int>? f)`), and the pair `public static string Run(Action a)` / `public static string Run(Func<int> f)` whose Kotlin caller uses `Workshop.run(fun(): Int = 1)`. A reader-level test (`NugetExtractApiIntegrationTest`, which already compiles a C# fixture and runs the real reader at `:42-80`) asserts per row that the extracted `parameters` carry the nullability, not just `typeArguments`.

Plugin test (`NugetDelegateBindingTest`): RIR JSON for `Apply` carries `{"kind":"delegate","definition":"System.Func`2",...}`; `Transform` is NOT an `RirClass`; `LaterAsync` yields `skipped_delegate_signature`; generated Kotlin contains `fun apply(seed: Int, step: (Int) -> Int): Int` and `typealias Transform = (Int) -> Int`.

Leak rows (`LeakTests/LiveHandleTests.cs`): (1) N per-call crossings with a CAPTURING lambda minted per iteration return `LiveHandles` to baseline after settle (each crossing mints one `StableRef`, released GC-timed; a non-capturing lambda is a singleton and would be reused by ADR-089, proving nothing); (2) keep, forget, settle returns to baseline; (3) a throwing lambda returns to baseline (the `NugetError` envelope `StableRef` and the ADR-104 exception `GCHandle`). Caveat already recorded at `LiveHandleTests.cs:778-781`: the harness counts Kotlin `StableRef`s only, so a leaked transfer `GCHandle` on the C# side stays green; whether the throw path frees the transfer handle is the same unverified question as the ROADMAP Phase 13 ADR-088 line.

## Deferred scope

- Delegate at a RETURN, PROPERTY or FIELD position (`Func<int,int> Step { get; set; }`, `Func<int,int> MakeAdder(int)`): the inverse direction (a C# delegate handed to Kotlin needs a registered `Invoke` thunk per shape and a Kotlin function object wrapping a `GCHandle`). Named `skipped_delegate_position`. A set-only view of a get/set property is not offered (half a property is not Kotlin-idiomatic).
- Async delegates: `Func<Task>`, `Func<Task<T>>`, `Func<CancellationToken, Task<T>>`, `Func<ValueTask>`. Named `skipped_delegate_signature`. Same design as Phase 13 "`Task`-typed members on a Kotlin-implemented C# interface" (finding 5): fold into that line, do not add a new one. This is the largest real-world gap (very common in modern .NET APIs) and should be the next item after this one.
- Struct, collection, `Nullable<T>`, `object`, type-parameter, bound-generic-instance and nested-delegate (`Func<Func<int>>`) Invoke positions; `ref`/`out`/`in` delegate parameters (`delegate bool TryParse(string s, out int v)`), and `ref` returns: `skipped_delegate_signature`, each blocked on the same item that blocks it for interface slots (ADR-155 collection slots, ADR-056 struct flattening in a slot, the `ref`/`out` ROADMAP line).
- Delegate parameter on a struct method, a bound-interface member (C#-implemented call side is cheap but the Kotlin-implemented slot side is the inverse direction, and ADR-085 is all-or-nothing per interface, so it would turn the interface's bridge off with `skipped_kotlin_bridge`), or a generic class member (ADR-072 witness thunks): `skipped_delegate_position`.
- `EventHandler` / `EventHandler<T>` and any delegate with an `object` parameter: left to the events ADR.
- Custom delegates declared in an unbound external assembly.
- Open generic methods taking `Func<T, R>` (`Select<T,R>`): already `skipped_open_generic`, unchanged.
- Exception identity round-trip (the original Kotlin `Throwable` rethrown in Kotlin): ADR-104 Fork B posture, unchanged.
- Multicast or C#-side equality semantics beyond ADR-089 reuse.

### What this design leaves room for (events, not designed here)

- `RirDelegateType` is a first-class type ref, so a future `RirEvent(name, handlerType: RirDelegateType, isStatic)` needs no new type machinery.
- The mint path is resolve-or-mint by Kotlin lambda identity (ADR-089 table holding the DELEGATE weakly). `remove_X` must receive a delegate `Equals` to the one passed to `add_X`; .NET delegate equality is (Target, Method) (inferred), so either the reused instance or a fresh delegate over the reused holder satisfies it. The events ADR should therefore key on the holder, and this item must not mint a fresh holder per crossing while the old one is alive.
- The lifetime rule already covers a subscribed handler: the event's invocation list keeps the delegate alive; unsubscribe drops it; no Kotlin-side handle is needed for correctness, only for the ability to unsubscribe (a `Flow` or a returned subscription is the event ADR's call).
- `EventHandler<T>`'s `object? sender` needs an `object` admission or an elision rule; that belongs to the events ADR. Until then `skipped_event` stays, with its hint (`Program.cs:1750-1753`) now true.

## Open what-questions

1. **Is "stored" in v1, folding the Phase 13 line into this item?** Recommendation: yes. The safe lifetime model is the only one metadata permits (finding 4), it is the shipped ADR-085 model, and the stored case costs one fixture method and one test row. Delete both ROADMAP lines together. Human decision: pending.
2. **Function type or SAM for a custom delegate?** Recommendation: function type plus a `typealias` carrying the C# name. Human decision: pending.
3. **Async delegates (`Func<Task>`, `Func<CancellationToken, Task<T>>`) in or out of v1?** Recommendation: out, named skip, folded into the existing Phase 13 `Task`-typed-slot line and picked up next, since it needs a Kotlin-to-C# completion mechanism nothing has today. Human decision: pending.
4. **Arity ceiling for delegate slots: 2 (shared constant) or 4?** Recommendation: 4 for delegate slots only, leaving interface slots at 2; the six-parameter slot compiles and runs (spike c2, verified 2026-09-21), so nothing technical stands in the way; `Func<T1..T4,R>` covers the large majority of real signatures and the emitters are arity-generic. Human decision: pending.
5. **Should the misclassified custom-delegate-as-class (finding 1) be fixed as part of this item or first as its own bug?** Recommendation: same item; the fix IS the first commit of this item (delegates stop being classes and become a named skip), so it can land alone if the rest slips. Spike a (2026-09-21) adds one fact the human should weigh: landing the fix alone REMOVES the accidental delegate-as-return handle binding (`makeDoubler().invoke(21)`) with nothing replacing it until the return position ships. Human decision: pending.
6. **Overloads differing only by delegate shape (`Run(Action)` / `Run(Func<int>)`): bind both, or drop the set?** Verified by spike d: a bare lambda NEVER resolves against such a pair in Kotlin 2.4.10; a typed function value or an anonymous function does. Recommendation: bind both and attach a named info diagnostic with the workaround, because dropping deletes `Task.Run`-shaped APIs that stay callable. Human decision: pending.

How-questions (for the implementer, recorded so they are not re-discovered): registration home per declaring type vs one per-assembly delegates export (recommendation: per declaring type, smallest change to ADR-054 accounting; caveat: the same shape used by two bound classes then mints two factories, two holder classes and two reuse tables, so one lambda passed to both classes becomes two C# delegate instances, which is harmless in v1 but means the per-assembly alternative buys delegate identity ACROSS types, which the events ADR may want); shape key spelling for generated names (recommend a sanitized CLR name such as `FuncInt32Int32`, collision-checked like ADR-072 instantiation names); overload sets that differ only by delegate shape moved to what-question 6 after spike d; the remaining how-part is that the ADR-057 collapse check (`skipped_overload_set`, `NugetGenerateBindingsTask.kt:6669`, `RirBridging.kt:250`) must treat two function types with different return types as DISTINCT Kotlin signatures and still give the two thunks distinct C entry names (not read in depth; inferred that the existing suffix scheme keys on the managed signature, which already differs).

## Spikes run (2026-09-21)

All run in the dedicated worktree `kn-w3` (detached at `651cb7d0`) or a `mktemp -d` scratch dir; both restored or deleted afterwards. Toolchain: .NET SDK 10.0.301 building `net8.0`, Kotlin 2.4.10, mingwX64.

| Spike | Seam | Command | Result |
|---|---|---|---|
| a | Shipped reader on a real assembly | scratch `net8.0` classlib (`Transform`, ``Transformer`2``, `Workshop` with `Func`/`Action`/`System.Action`/custom parameters and a `Transform` return), then `dotnet run --project NugetMetadataReader -- --package Probe <probe.dll>` | Finding 1 CONFIRMED and widened: `Transform` is `{"kind":"class"}` with `Invoke`, `constructors: []`, two noise diagnostics; ``Transformer`2`` is a generic class with an `[int,int]` instantiation; custom-delegate parameter is a `handle`; custom-delegate RETURN binds as a `handle` (new). Finding 2 CONFIRMED: `Func`/`Action<T>` give `skipped_unbound_generic_instantiation` with the collection hint, `System.Action` gives `skipped_unbound_type_reference`. |
| b | Roslyn metadata, decoded with `System.Reflection.Metadata` | scratch console app printing `attr.Constructor.Kind`, the attribute type's resolution scope and `GetBlobBytes(attr.Value)` for each parameter, method and type of the same dll | Layout CONFIRMED (table in finding 7a): `[1,2]`, `[2,2,1]`, `[1,2]`; ctor is `MemberReference` via `AssemblyReference`. NEW: uniform cases carry no per-parameter attribute, only a method-level `NullableContextAttribute(2)`. The shipped decoder already handles all of it; the two silent wiring traps are recorded in 7a. |
| c1 | .NET GC semantics of delegate over holder | scratch `net8.0` console app, Release, holder + `SafeHandle` + six-parameter `delegate* unmanaged[Cdecl]`, factory and thunk in `NoInlining` methods, transfer `GCHandle` freed | Output: `target is IBridge: True; method=Invoke` / `after transfer free + 3 GCs, delegate stored: released=0` / `pool invoke = 1010` / `after Delegate.Combine + GCs: released=0` / `after drop + GCs: released=1 on thread=1` (main was 2, pool was 4) / `per-call (never stored) after transfer free + GCs: released=2`. Delegate-roots-holder and delegate-owned lifetime CONFIRMED. |
| c2 | Kotlin/Native six-parameter `staticCFunction` | scratch test in `nuget-runtime/src/nativeTest`, `./gradlew :nuget-runtime:mingwX64Test --tests "*ZzDelegateSpikeTest*"` | `SPIKE arity4 slot = 20`, `BUILD SUCCESSFUL`. Arity lift CONFIRMED as policy only. |
| c3 | Kotlin slot invoked off-thread, stored bridge survives, dropped bridge releases | NOT re-run: already proven by shipped fixtures, read not executed this session: `TestDependency/Kennel.cs:157-162` `BoardAsync(IFeedable)` calls the Kotlin slot after `await Task.Delay` (`KennelRoundTripTests.cs:138`), `MenagerieRoundTripTests` dropped/stored rows (finding 3), ADR-085 spike 2026-08-08 | Verified by reading plus the prior spike. |
| d | Kotlin 2.4.10 overload resolution | same scratch test file, first compile | All four bare-lambda calls: `Overload resolution ambiguity between candidates` (plus `Unresolved reference 'it'`). Typed value and anonymous function resolve: `Int overload`, `Unit overload`, `Int overload`. Pass 1's "may be ambiguous" UPGRADED to "always ambiguous"; became what-question 6. |
| e | `managedType` string | reading only: `NugetGenerateShimsTask.kt:2651-2657`, `:3085-3097`, `:93-99`, `MenagerieRoundTripTests.cs:292` | Pass 1's `EndsWith("KotlinException")` CONTRADICTED: `error(...)` surfaces as `TestLibrary.KotlinInvalidOperationException`. Sample test corrected. Not executed. |

Not run: the "what Kotlin does the generator emit for the mis-extracted `Transform` class and does it compile" half of item a. The reader output settles finding 1, and the generated code is about to be deleted by the fix, so a full `packNuget` for it was not worth the budget.

## Spike first (post-implementation checks only; everything that could be checked before implementation has been)

1. The COMPOSED path end to end (Kotlin `staticCFunction` slot behind a real `Func<int,int,int,int,int>` created by the generated factory, invoked from a pool thread, holder dropped, `nuget_kotlin_release` counted): each half is verified separately (c1, c2, c3) but the composition exists only once the generator emits it. `WorkshopRoundTripTests.StoredLambda_SurvivesCollections_AndRunsOnAPoolThread` plus a `Sum4` row IS this check; write it first.
2. The exact `managedType` string at runtime: `Assert.Equal("TestLibrary.KotlinInvalidOperationException", ...)` in the sample test is the check. If it fails, read the actual value before touching the generator (the string is `GetType().FullName`, so a mismatch means the namespace wiring, not the channel).
3. Inferred, not spiked: a custom generic delegate whose `Invoke` mixes type parameters and directly annotated reference types (`delegate T Pick<T>(string? hint)`) takes its nullability from two places (finding 7a). Add it to the reader test before admitting it, or name-skip it in v1.
4. Inferred, not spiked: AOT/trimmer safety of `new TDelegate(holder.Invoke)`; `AotSmokeTest/` is the seam once a delegate fixture exists.

## Proposed ADR draft (number to be assigned by the main thread; do not file until then)

# ADR-NNN: Reverse, C# delegate parameters as Kotlin function types: one-slot Kotlin bridge, delegate-owned lifetime

## Status
Proposed

## Context
A bound C# method or constructor that takes `Func<>`, `Action<>`, `Predicate<T>`, `Comparison<T>`, `Converter<,>` or a package-declared `delegate` is skipped today with a misleading diagnostic (`skipped_unbound_generic_instantiation` or `skipped_unbound_type_reference`), and a package-declared delegate TYPE is mis-extracted as an ordinary class because the reader never tests for a `System.MulticastDelegate` base (verified by spike, 2026-09-21: the shipped reader emits `{"kind":"class","name":"Transform"}` with an `Invoke` method and no constructor, a generic delegate becomes a generic class with instantiations, and a delegate-typed return binds as a handle; `Program.cs:278-287`, `:2591-2631`). ECMA-335 carries no information about whether a callee stores a delegate argument (inferred from the spec; no counter-example known), so per-call and stored delegates cannot be told apart at generation time. ADR-085/086/087/089 already carry a Kotlin object into C# behind a ctx `StableRef` and Kotlin-minted function-pointer slots, with a `SafeHandle`-driven release verified by execution from arbitrary .NET threads including the finalizer thread (ADR-085 spike, 2026-08-08). A delegate is that mechanism with exactly one slot.

## Alternatives Considered
### 1. One-slot Kotlin bridge per delegate shape, delegate-owned lifetime (chosen)
Holder class + factory returning `new TDelegate(holder.Invoke)`; Kotlin function type on the surface; the .NET GC releases the Kotlin lambda. Pros: one lifetime rule correct for per-call and stored; reuses shipped, spiked machinery and the ADR-087/104 error channels; AOT-safe (inferred). Cons: per-call lambdas are released GC-timed; one factory slot per shape per declaring type moves that type's contract hash.
### 2. Per-call borrow, the literal ADR-036 inversion
Release the `StableRef` when the C# call returns. Pros: prompt release. Cons: use-after-free whenever the callee stores, and storing is undetectable. Rejected.
### 3. Kotlin-owned `AutoCloseable` callback handle
Pros: deterministic. Cons: the consumer cannot know the required lifetime either; not idiomatic; JNA's known footgun. Rejected.
### 4. `fun interface` per delegate instead of function types
Pros: nominal names, Java-interop precedent. Cons: `Func`/`Action` have no useful nominal name; splits the surface in two; ObjC-import and Swift-export precedent is function types. Rejected; a `typealias` keeps the custom name.
### 5. Raw function pointer + `Marshal.GetDelegateForFunctionPointer`
No ctx so no closures, not AOT-safe (ADR-102). Rejected.

## Decision
1. Reader: `IsDelegate` excludes delegate TypeDefs from class extraction; a `DelegateDefinitions` table matched by CLR full name only (inferred to be necessary for the same facade-assembly reason ADR-155 verified for collections) plus bound custom delegates decoded from their `Invoke` signature produce `RirDelegateType(definition, typeArguments, parameters, returnType, nullable)`. Nullability (verified by spike, 2026-09-21): Roslyn writes one `NullableAttribute` byte per reference-type node in pre-order with the delegate node first (`Action<string?>` is `[1,2]`, `Func<string?,string>?` is `[2,2,1]`, `Func<int,string?>` is `[1,2]`), through a `MemberReference` constructor on net8.0, and writes NO per-parameter attribute when every node agrees (a method-level `NullableContextAttribute` carries it). The shipped ADR-053/072 decoder handles all of that; `RirDelegateType` must join `IsAnnotatable`, `CountAnnotatableNodes` and `ApplyPreOrder` over its `typeArguments`, and `parameters`/`returnType` must be derived after that pass. Either omission is silent: a nullable type binds as non-null with no diagnostic.
2. Surface: `(P...) -> R`; `void` is `Unit`; nullable delegate is a nullable function type; custom delegates add a `typealias`.
3. Wire: the delegate crosses existing method/constructor thunks as one transfer `GCHandle` `IntPtr`; per shape, a C# holder (`KotlinRefHandle` + one `delegate* unmanaged[Cdecl]<IntPtr, args..., IntPtr*, ret>`) and a `[UnmanagedCallersOnly]` factory `(invoke, ctx) -> GCHandle(new TDelegate(holder.Invoke))`, registered as an extra slot on the declaring type's ADR-054 registration and folded into its contract hash; Kotlin mints through an ADR-089 weak reuse table keyed on lambda identity. Verified by spike (2026-09-21, .NET half): a delegate strongly roots its `Target`; the holder's `SafeHandle` was not released while the delegate was stored (3 forced collections, and after `Delegate.Combine`), and was released after the delegate was dropped, on a non-caller thread. Verified by spike (Kotlin half): a six-parameter `staticCFunction` slot (ctx + 4 + errOut) compiles and runs on Kotlin 2.4.10 mingwX64. The two halves composed through generated code are NOT verified until the feature's own round-trip test runs.
4. Lifetime rule: the C# delegate owns the Kotlin lambda; release is `KotlinRefHandle.ReleaseHandle` on .NET collection; Kotlin frees only the transfer handle. Verified for the interface bridge (ADR-085 spike and `MenagerieRoundTripTests`) and for the delegate indirection on the .NET side (spike above); a never-stored delegate was released on the first settle after its transfer handle was freed.
5. Errors: ADR-087 envelope inside the slot, the ADR-029 MAPPED exception out of `Invoke` (`KotlinInvalidOperationException` for `kotlin.IllegalStateException`, base `KotlinException` only for unmapped types; verified by reading `NugetKotlinErrors.Map`), ADR-104 at the outer thunk, so the Kotlin caller sees `managedType = "<forward namespace>.KotlinInvalidOperationException"`. No new channel.
6. Threading: the lambda may run on any .NET thread, concurrently, and after the Kotlin caller returned; the consumer's lambda must be thread-safe if the C# API is. Verified for slots by the ADR-085 spike.
7. Diagnostics: `skipped_delegate_signature` (Invoke shape outside the slot vocabulary: async, struct, collection, `object`, `ref`/`out`/`in`, nested delegate, arity above the ceiling) and `skipped_delegate_position` (return, property, struct method, bound-interface member, generic class member).
8. Scope: parameters of methods and constructors of bound non-generic classes; Invoke arity up to 4.
9. Overloads differing only by delegate shape bind, every member, with a named info diagnostic: verified by spike (Kotlin 2.4.10), a bare lambda is always `Overload resolution ambiguity` against such a pair, while a typed function value or an anonymous function resolves. Alternative considered: drop the set (ADR-057 style); rejected because the members stay callable.

## Consequences
The Phase 13 stored-delegate line closes with this ADR. Events become expressible (`RirDelegateType` plus reuse-by-identity give `add_`/`remove_` what they need) but stay `skipped_event`. Async delegates remain the largest gap and join the Phase 13 `Task`-typed-slot design. Each declaring type that uses a delegate shape gains registration slots, so its contract hash moves (stale-shim detection per ADR-054 fires as designed). Per-call lambdas hold their captures until the next .NET collection: document it. A package-declared delegate that today mis-binds as a class disappears from the generated surface and reappears as a typealias: a behaviour change no fixture or doc depends on (verified by grep). That includes a delegate-typed RETURN, which binds today by accident as a handle with an `invoke` method (verified by spike at the reader; runtime behaviour inferred) and becomes `skipped_delegate_position` until the return position ships.
