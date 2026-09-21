# A per-call lambda-parameter member with a non-`Unit` method return, or a scalar lambda return, does not generate

- ROADMAP: line 75 as of 2026-09-21: "VERIFIED by execution: a class/interface member with a per-call lambda parameter and a non-`Unit` return (`fun f(cb: (Int) -> Unit): Int`) fails `packNuget` with a forward ABI mismatch instead of generating." Folded in: line 71 ("A non-`Boolean` scalar *return* on the per-call lambda-parameter route ... does not compile"). Assessed for folding: line 70 (stored-callback scalar payload) and line 72 (interface-bridge `isPrimitive`).
- Researched: 2026-09-21, two passes. Pass 1 (about 10 of 20 minutes) was source reading only. Pass 2 (about 10 of 25 minutes, same day) ran spikes a to d plus four extra cells (a1, a2, e1, e2) on main `651cb7d0` in a separate worktree; see `## Spikes run (2026-09-21)`. A claim below is **verified by spike** where it quotes observed output, **verified by reading** where it cites source, and **inferred** otherwise. One inference was CONTRADICTED by a spike (which half fails first, finding 4 and the dated note there); the recommendation did not change.
- Restatement: a C# consumer of a Kotlin class (or sealed arm, or interface default re-emitted on a class) calls a member that takes one per-call lambda and gets the member's own result back, `int total = metronome.CountTicks(t => seen.Add(t))`, and can hand Kotlin a lambda that itself returns a scalar, `metronome.SumWeights(t => t * 2)`. Forward; Kotlin declares, C# consumes and supplies the lambda. Two different returns are in play and are kept apart throughout: the METHOD return (outer) and the LAMBDA return (inner).
- Verdict: **fix**, on the existing ADR-036 legacy route, as an ADR-036 amendment (no new ADR drafted). Lines 75 and 71 are one change (same two functions). Line 72 joins through a shared predicate. Line 70 does not join, and its backlog premise is contradicted by the code as it stands and by spike c (finding 7).

## Findings

### 1. The route, end to end (verified by reading)

One Kotlin function and one C# function, selected by the same structural gate on both halves, neither on the ADR-062 plan:

- Gate: `hasLegacyLambdaParameter()` (`exports/ClassExports.kt:50-52`); Kotlin partition `ClassExports.kt:181-199`; C# partition `cir/CirClassTranslator.kt:893-909`; sealed arm `CirClassTranslator.kt:2115` and `NugetProcessor.kt:2152`, selector `exports/LambdaParameterExports.kt:240-253`.
- Kotlin half: `addLambdaParamMethodExport` (`exports/LambdaParameterExports.kt:25-223`).
- C# half: `translateCallbackMethod` (`cir/CirClassTranslator.kt:3179-3387`) produces a `CirCallbackMethod` of pre-rendered strings; `renderCallbackMethod` (`cir/CirClassRenderer.kt:851-872`) prints the `DllImport` as raw text; the delegate and its ADR-102 `[UnmanagedCallersOnly]` thunk come from `cir/CirCallbackRenderer.kt:3-63`.
- Contract: because the extern is raw text, the C# half is scraped by `ForwardAbiContract.csharpLegacy` (`ForwardAbiContract.kt:228-247`), the Kotlin half is read off the `FunSpec` (`:266-269`, `:386-416`), and `assertMatches` compares them (`:183-185`, `expected` = C#, `actual` = Kotlin).

Both functions classify the two returns with independent `when` blocks that never read each other (outer: `CirClassTranslator.kt:3210-3214`, `:3302-3313`, `:3342-3372`, Kotlin `:44-50`, `:149-218`; inner: `CirClassTranslator.kt:3233-3237`, `:3293-3298`, `:3326-3330`, Kotlin `:80-84`, `:122-135`). So the matrix is two axis tables, not N x M cells. **Verified by reading.**

### 2. PRIMARY, the method (outer) return: the C# half is the one that disagrees (verified by reading; the backlog file's "exact line was not established" is now established)

`CirClassTranslator.kt:3372`: `nativeImportReturnType = if (isOuterRetUnit) "void" else "IntPtr"`. The Kotlin half is right for a scalar: `LambdaParameterExports.kt:217` `builder.returns(ClassName.bestGuess(outerRetQualified))` gives `kotlin.Int`, which `kotlinType` maps to `INT` (`ForwardAbiContract.kt:483`), against `IntPtr` to `POINTER` (`:460`). That is exactly the reported "expected ... -> pointer, actual ... -> int".

**Verified by spike** (cells a and a2, Tier 1): `fun countTicks(listener: (Int) -> Unit): Int` makes the processor throw `java.lang.IllegalArgumentException: Forward ABI mismatch for metronome_countTicks; expected metronome_countTicks(in pointer, in pointer, in pointer, out pointer) -> pointer, actual metronome_countTicks(in pointer, in pointer, in pointer, out pointer) -> int` at `ForwardAbiContract.assertMatches(ForwardAbiContract.kt:183)` from `NugetProcessor.process(NugetProcessor.kt:1610)`. It is an uncaught `require`, not a KSP diagnostic: `Tier1Harness.run` itself throws, `CNameExports.kt` is never written (`cNameExports.writeTo` is after the check, `NugetProcessor.kt:1630`), so nothing downstream of the contract is observable for a scalar outer return on today's main. A red Tier 1 cell for this axis must therefore expect the harness to throw (or just assert `compiledClean` and let the exception be the red).

Even with the contract silenced the C# would not compile: `:3312` spells the public return as the Kotlin simple name (`public Int CountTicks(...)`) and `:3366` emits `return new Int(nativeHandle);`. Still **inferred** (CS0246): `Interop.cs` is written before the contract check but the Tier 1 harness discards it when the processor throws, so the text was not captured. The same two lines were observed on the enum cell (`public Mood MoodAfter(...)`, `return new Mood(nativeHandle);`, finding 3), which is the same code path. Not load-bearing: step 1 rewrites both lines.

Why `String` and `List` work today: `ForwardAbiContract` treats a `String` result as `POINTER` on both sides (`:467-473`, `:492-498`), and the `List` arm retains and returns `COpaquePointer?` (`LambdaParameterExports.kt:166-183`, `:215`).

### 3. Method-return axis, today (verified by reading unless marked)

| Outer return | Contract | Result |
|---|---|---|
| `Unit` | VOID / VOID | works (`Cat.forEachToy`, `Metronome.onTick`) |
| `String` | POINTER / POINTER | works (`Cat.describeWith`) |
| `List<T>`, `MutableList<T>` | POINTER / POINTER | works for `List<String>` (`Cat.nicknamesMatching`); element spelled by simple name (`:3306-3309`), so an object element in another namespace is **inferred** broken, out of scope here |
| `Int`, `Long`, `Short`, `Byte`, `Float`, `Double`, unsigned | POINTER vs scalar | **fails the build** (this item) |
| `Boolean` | POINTER vs BOOL | **fails the build** |
| `Char` | POINTER vs SHORT | fails the build |
| enum | POINTER / POINTER, passes | **Verified by spike** (cell e1), broken on BOTH halves. Kotlin: `Return type mismatch: expected 'Mood', actual 'Mood?'.` (the `try` yields `null` from the catch arm). C#: `public Mood MoodAfter(Action<int> listener)` with `return new Mood(nativeHandle);`, scratch classlib gives `error CS1729: 'Mood' does not contain a constructor that takes 1 arguments`. |
| exported class | POINTER / POINTER, passes | the Kotlin `else` arm (`LambdaParameterExports.kt:184-201`) returns the bare Kotlin object from a `@CName` function with no `NugetHandles.retain`, unlike the `List` arm at `:172`; it is the only `returns(ClassName.bestGuess(<user type>))` in `exports/` (grep, one hit, `:217`). **Verified by reading**: its catch arm yields `defaultValueFor(...)` = `null` for a non-`kotlin.` type (`exports/Helpers.kt:109`) against a non-null `Toy` result. **Verified by spike** (cell b, `fun firstToy(where: (Toy) -> Boolean): Toy`): the processor exits OK with no warning, the contract passes, and the generated Kotlin does NOT compile: `CNameExports.kt:88:10: Return type mismatch: expected 'Toy', actual 'Toy?'.` The emitted export is `public fun export_cat_firstToy(...): Toy { ... return try { ...firstToy { ... } } catch (e: Throwable) { ...; null } }` with no `NugetHandles.retain` on the result. So it is a build failure, not a silent bad pointer: no consumer holds a non-`StableRef` pointer today. The C# half on its own DOES compile (scratch classlib, `Build succeeded`) and reads `private static extern IntPtr Native_FirstToy(...)`, `public Toy FirstToy(Func<Toy, bool> where)`, `return new Toy(nativeHandle);`, so only the Kotlin compile stands between this cell and a bad pointer; a fix that only makes the catch arm type-check (for example by throwing) would create the silent bug. Seam limit: the Tier 1 compile is `K2JVMCompiler`, not Kotlin/Native, so whether konan would additionally reject a bare object `@CName` return stays **inferred**; moot while the file does not compile. Step 4 of the recommendation removes the cell. |
| nullable anything | `?` is never read (`qualifiedName` only) | **Verified by spike** for `String?` (cell e2, `fun maybeLabel(listener: (Int) -> Unit): String?`): Kotlin `Return type mismatch: expected 'String', actual 'String?'.` (export declared `: String`, catch arm `""`), C# `public string MaybeLabel(...)` with `return Marshal.PtrToStringUTF8(nativeResult)!;`. Other nullable cells **inferred** wrong the same way. |
| `Set`, `Map`, other generic | POINTER / POINTER, passes | `new Set(nativeHandle)`: **inferred** compile failure |

### 4. FOLD-IN line 71, the lambda (inner) return: BOTH halves are wrong, the ROADMAP names only one (verified by reading)

- C#: `CirClassTranslator.kt:3233-3237` gives every non-`Unit`, non-`Boolean` return an `IntPtr` delegate, and `:3296-3297` wraps it with `NugetMarshal.WrapString(<expr>)` in both the `String` arm and the `else` arm. For `(Int) -> Int` that is `WrapString(int)`. (The ROADMAP's `:2624` and `:2687-2688` have drifted to `:3236` and `:3296-3297`.)
- Kotlin: `LambdaParameterExports.kt:80-84` spells the `CFunction` return `COpaquePointer?` and `:128-134` reads it with `resultRef.asStableRef<String>().get()`, so the wrapper lambda yields a `String` where the member wants an `Int`.
- **Verified by spike** (cell a1, `fun weighAll(weigh: (Int) -> Int)`, `Unit` outer so the contract passes and the inner axis is isolated). Kotlin: `val weighFn = weighPtr.reinterpret<CFunction<(Int, COpaquePointer) -> COpaquePointer?>>()`, body `val cbResult = resultRef.asStableRef<String>().get()`, compile error `CNameExports.kt:63:7: Return type mismatch: expected 'Int', actual 'String'.` C#: `internal delegate IntPtr NugetIntIntCallback(int arg0, IntPtr userData);`, `public void WeighAll(Func<int, int> weigh)`, `return NugetMarshal.WrapString(weigh(arg0));`, scratch classlib `error CS1503: Argument 1: cannot convert from 'int' to 'string'`. Processor exit OK, no warning, no diagnostic.
- Dated note, 2026-09-21: pass 1 inferred for spike a that "generated Kotlin does not compile, before any C#". **CONTRADICTED** for the exact shape `fun sumWeights(weigh: (Int) -> Int): Int`: the processor's own ABI contract throws first (`Forward ABI mismatch for metronome_sumWeights; expected ... -> pointer, actual ... -> int`), on the OUTER axis, and no Kotlin is ever written. The inner-axis Kotlin failure is real but is only reachable when the outer return passes the contract (`Unit`, `String`, `List`). Consequence for the implementation order: fix the outer axis first or the inner-axis red test cannot go red for the right reason; use a `Unit`-outer cell (as a1 did) to pin line 71 independently. The recommendation is unchanged.
- Target is already written down: ADR-036 `docs/adr/036-reverse-interop-mechanism.md:402` ("Primitive: passed by value", both directions) and `:418` (`NugetIntIntCallback // (int a, IntPtr userData) -> int   for (Int) -> Int`). `typeSuffix` (`:3217-3224`) already yields `Int` for an `Int` return, so the by-value fix makes the existing name true to its wire; no rename, and ADR-036's 2026-09-13 injectivity rule (`:575-598`) is satisfied rather than strained.
- The ADR-102 thunk needs nothing: `appendThunkBody` returns `default` after `FailFast` for any non-void type (`CirCallbackRenderer.kt:94-96`) and the pointer cast is built from the type strings (`:103-112`). **Inferred**: `sbyte`/`short`/`int`/`long`/`float`/`double` and unsigned are blittable and legal `[UnmanagedCallersOnly]` returns; `bool` and `char` are not, which is why `Boolean` already rides `byte` and why `Char` stays out.

| Lambda return | Today |
|---|---|
| `Unit`, `Boolean`, `String` | works (`forEachToy`, `nicknamesMatching`, `describeWith`, `greetUsing`, `combineNicknames`) |
| numeric scalar | broken on both halves (line 71) |
| `Char` | broken, no crossing convention on any callback route (ADR-036 `:500-501`) |
| exported object, enum | broken on both halves the same way (`asStableRef<String>`, `WrapString(Toy)`). A real fix has an ownership problem: Kotlin releases the returned box (`LambdaParameterExports.kt:132`), which for a C#-owned wrapper's handle is **inferred** a double free. |

### 5. Top-level functions with the same shape do NOT work, so there is nothing to converge on (verified by reading)

`ForwardDiagnostic.kt:731-733`: "a lambda binds at a class-method parameter and a top-level function return, but not at this position". `test-library/.../unrouted/UnroutedPositionsSample.kt:114-118` (`callbackParamOnTopLevel(cb): Int`) is a cell its own comment PREDICTS as NONE, as are the object (`:72`) and extension (`:136`) forms; the prediction is confirmed by the diagnostic text above and by the route having no top-level, object or extension emitter (every selector in finding 1 is class or arm keyed). The observed matrix (`research/H-observed-matrix.md`, cited by the backlog file) was not opened. The ordinary-class planner names the per-call route as one of only two legacy routes a class has (`forward/ForwardCallablePlanner.kt:1188-1213`). The only thing with a complete return matrix is the ADR-062 plan itself, which has no callback parameter kind: `CALLBACK_PROTOCOL` is a skip reason (`ForwardCallablePlanner.kt:44`, `:4211`), and ADR-062 lists "Lambda / stored-callback / interface-bridge methods" under "What remains on named legacy routes" (`docs/adr/062-forward-callable-plan.md:62-70`).

### 6. Untracked sibling on the same two functions: non-lambda parameters are ignored (verified by reading; failure mode inferred)

The Kotlin export declares exactly `handle, <cb>Ptr, <cb>UserData, errorOut` (`LambdaParameterExports.kt:205-211`) and calls `.$methodName { ... }` with only the trailing lambda (`:152`, `:168`, `:187`); the C# extern and public method carry only the lambda (`CirClassRenderer.kt:853-855`). Only `firstOrNull` lambda is read (`:32`, `CirClassTranslator.kt:3190`). **Verified by spike** (cell d, `fun f(scale: Int, cb: (Int) -> Unit)`): processor exit OK, no warning, contract passes (both halves drop `scale`), generated Kotlin calls `.f { it0 -> ... }` and fails with `CNameExports.kt:59:55: No value passed for parameter 'scale'.`; C# is `private static extern void Native_F(IntPtr handle, IntPtr cbPtr, IntPtr userData, out IntPtr error);` and `public void F(Action<int> cb)`, `scale` gone from both. A two-lambda member is still **inferred** to fail the same way (not spiked). No ROADMAP or backlog line covers it. No gate refuses it: `legacyRefusedParameter` is applied only to async routes (`CirClassTranslator.kt:870-874`).

### 7. Line 70, stored-callback scalar payload: the backlog's premise does not match the code (verified by reading AND by spike c)

`storedArgSuffix` (`CirClassTranslator.kt:3427-3435`, drifted from the backlog's `:2818-2825`) tests the QUALIFIED name against `KOTLIN_TO_CSHARP_RETURN`, which is keyed by SIMPLE name (`cir/CirTypeMapping.kt:28-42`). `kotlin.Int` never matches, so a stored `(Int) -> Unit` is suffixed `Object`, registers `NugetObjectVoidCallback(IntPtr arg0Ptr, IntPtr _)` (`:3441-3448`), and is read with `FromHandle<int>` (int branch exists, `cir/CirMarshalRenderer.kt:247`). Name and wire agree. ADR-036's 2026-09-11 amendment says the same (`036:515-519`: "a primitive falls through to the `Object` suffix"), and `tier1/Tier1PrimitiveLambdaParameterTest.kt:42-43` records the stored route's only `Int`-suffixed payload as the enum ordinal (`NugetIntVoidCallback(int arg0Ord, IntPtr _)`). The `NugetIntVoidCallback` collision the backlog file describes is therefore not reachable today; what is real is the boxing cost (one `StableRef` per invocation, `exports/StoredCallbackExports.kt:148-156`, `:176-180`) and a trap: "fixing" `:3431` to the simple name without moving the wire would create the collision.

**Verified by spike** (cell c: `onTick`, `addTickListener`, `removeTickListener`, all `(Int) -> Unit`, one class, no enum). `Interop.cs` declares exactly two callback delegates, each once: `internal delegate void NugetIntVoidCallback(int arg0, IntPtr userData);` (per-call `onTick`) and `internal delegate void NugetObjectVoidCallback(IntPtr arg0Ptr, IntPtr _);` (stored route). The stored C# lambda is `NugetObjectVoidCallback nativeCallback = (IntPtr arg0Ptr, IntPtr _) => { int arg0 = NugetMarshal.FromHandle<int>(arg0Ptr); listener(arg0); };`; the stored Kotlin half is `reinterpret<CFunction<(COpaquePointer?, COpaquePointer) -> Unit>>()` with `val arg0Ref = NugetHandles.retain(arg0)`; the per-call half is `CFunction<(Int, COpaquePointer) -> Unit>`. Processor exit OK, generated Kotlin compiles clean, and the `Interop.cs` compiles in a scratch net8.0 classlib (`Build succeeded`). So the ROADMAP line 70 collision claim is wrong as written: there is no `NugetIntVoidCallback` shape conflict on today's main. Runtime behaviour of the boxed payload was not executed (no Kotlin/Native link); name, wire and both compiles agree, so it stays **inferred** working.

### 8. Line 72, interface-bridge `isPrimitive` (verified by reading)

Six sites, drifted from `:2934`/`:2957`: `CirClassTranslator.kt:3556`, `:3575-3579`, `:3598-3611`; Kotlin twin `exports/InterfaceBridgeExports.kt:65`, `:103`, `:121`. All spell `pQualified.startsWith("kotlin.") && pSimple != "String"`. The correct predicate already exists on the per-call route: `byValueArgs` (`CirClassTranslator.kt:3242-3247`, twin `LambdaParameterExports.kt:57-62`): `startsWith("kotlin.") && simple in KOTLIN_TO_CSHARP_PARAM && simple != "String" && simple != "Char"`.

## Recommendation

Implement ADR-036's own marshalling table at the two return positions where it was never implemented, on both halves, and gate everything the route still cannot carry by name. Size S/M, 9 processor files.

1. Method return, C# half only (Kotlin is already right for scalars, including the catch arm: `defaultValueFor` gives `false`, `0`, `0.0`, `0u` and friends, `exports/Helpers.kt:99-110`, verified by reading): `nativeImportReturnType`, `csReturnType` and the wrapper body take the C# primitive from `KOTLIN_TO_CSHARP_RETURN`; `Boolean` renders `bool` with `[return: MarshalAs(UnmanagedType.I1)]` above the extern (ADR-069 spelling, precedent `CirClassRenderer.kt:312`, `:525`; the scraper skips `[` lines, `ForwardAbiContract.kt:239-241`). Needs one flag on `CirCallbackMethod` (`cir/CirModel.kt:646`).
2. Lambda return, both halves: a by-value scalar returns itself. C# delegate return is the primitive and the body is `return <call>;`; Kotlin `CFunction` return is the Kotlin type and the body returns `fn.invoke(...)` directly, no retain, no release.
3. Hoist the by-value test into `cir/CirTypeMapping.kt` as one `isByValueCallbackScalar(KSType)`; the per-call route (args and returns) and the six interface-bridge sites (line 72) call it. A `List`/`Any` bridged parameter then falls to the handle arm; whether that arm compiles for `List` is **inferred no**, so line 72 closes as "no longer wires as `int`" and any refusal it needs is decided in the same gate as step 4 if cheap, else left on its ROADMAP line reworded. Behaviour change to name: the shared predicate excludes `Char`, which today's bridge `isPrimitive` admits and wires as `char` by value; it moves to the handle arm. No `test-library` fixture has a bridged interface method with a `Char` parameter (grep `: Char`, one hit, an ordinary class method at `clinic/ClinicSample.kt:231`).
4. One hoisted refusal, `legacyRefusedCallbackMember(method): String?` in `forward/ForwardLegacyRouteCollections.kt`, read by every selector in finding 1 plus `isArmCallbackRoutable` (`LambdaParameterExports.kt:285-288`), so a member this route cannot carry is a named skip on BOTH halves instead of a contract failure or non-compiling output: outer return that is nullable, `Char`, enum, exported object, `Set`/`Map`/other generic; lambda return that is `Char`, enum or object; any non-lambda parameter or second lambda (finding 6). ADR-123 is the precedent for refusing a legacy-route return on both halves.

Why this is the recommendation and not the plan migration: the consumer-visible surface and the C ABI this produces (delegate shapes, by-value scalars, `Func<int,int>`) are exactly what a future plan-owned callback parameter would have to emit, so the fixtures, Tier 1 pins and ADR-036 amendment survive a migration unchanged; only the two hand-written functions get replaced. Same end state, smaller step.

Alternatives rejected:
- Move the per-call route onto the ADR-062 plan now (a callback parameter kind in the planner, `ForwardMarshallingModel`, `ForwardKotlinPlanEmitter`, `ForwardCirPlanProjection`; deletes both hand-written halves; buys every return kind, mixed parameters, nullability, overloads and the top-level/object/extension positions by construction): the true end state, but size L, a new ADR, and it reopens ADR-064's measured position matrix. Queue as its own item (what-question 1).
- C#-only patch of `:3372`: passes the contract for scalars and leaves line 71, the enum/object/nullable cells and finding 6 generating broken output.
- Refuse every non-`Unit`/`String`/`List` outer return: closes the build failure but does not deliver the restatement.
- Fold line 70 in: different Kotlin file, different delegate parameter list, premise wrong; see Deferred scope.

## Files an implementation touches

Processor (`nuget-processor/src/main/kotlin/io/github/xxfast/kotlin/native/nuget/processor/`):
- `cir/CirClassTranslator.kt` (`translateCallbackMethod`; six `isPrimitive` sites in the bridge translator)
- `exports/LambdaParameterExports.kt` (lambda-return arm, `isArmCallbackRoutable`)
- `exports/InterfaceBridgeExports.kt` (three `isPrimitive` sites)
- `cir/CirTypeMapping.kt` (shared predicate)
- `cir/CirModel.kt`, `cir/CirClassRenderer.kt` (`CirCallbackMethod` Boolean-return flag, attribute line)
- `forward/ForwardLegacyRouteCollections.kt` (refusal), `exports/ClassExports.kt` and `NugetProcessor.kt` (wire the refusal into both selectors and the named-skip warning, next to `warnRefusedLegacyRouteMembers`)
- No change expected in `ForwardAbiContract.kt`, `CirCallbackRenderer.kt`, runtime, or ABI names (**inferred**).

Fixtures: `test-library/src/nativeMain/kotlin/io/github/xxfast/kotlin/native/nuget/test/metronome/Metronome.kt`. Do NOT edit `unrouted/UnroutedPositionsSample.kt`: `Depot.callbackParamOnClass` is a measured matrix cell deliberately set to `Unit`.
Tests: `IntegrationTests/PrimitiveLambdaPayloadTests.cs`; `nuget-processor/src/test/.../tier1/Tier1PrimitiveLambdaParameterTest.kt` (or a sibling `Tier1CallbackReturnTest.kt`) for the extern, delegate, `CFunction` text and one refused cell; a Tier 1 cell for a bridged `List` parameter (line 72).
Leak rows: none. A by-value scalar mints no handle in either direction (same argument ADR-036 `:619-624` used). A row is needed only if what-question 2 folds the object outer return in.
Docs: ADR-036 amendment (2026-09-xx), `docs/topics/lambdas-and-callbacks.md`, `FEATURES.md` row, ROADMAP lines 71, 72, 75 deleted and line 70 reworded, `docs/backlog/callback-parameter-class-method-non-unit-return-abi-mismatch.md` deleted, this memo deleted.

## Sample test

Fixture (`Metronome.kt`), one declaration per axis so a failure names its axis:

```kotlin
/** Method-return axis: the member's own scalar result survives a per-call lambda parameter. */
fun countTicks(listener: (Int) -> Unit): Int {
  repeat(beats) { listener(it + 1) }
  return beats
}

/** Method-return axis, the one scalar that is not its own wire. */
fun anyTickAbove(limit: (Int) -> Boolean): Boolean = (1..beats).any(limit)

/** Lambda-return axis: the C# lambda hands a scalar back by value. */
fun sumWeights(weigh: (Int) -> Int): Int = (1..beats).sumOf { weigh(it) }

/** Lambda-return axis, signed narrow and floating wires. */
fun loudest(velocityOf: (Int) -> Byte): Byte = (1..beats).maxOf { velocityOf(it) }
fun totalTempo(tempoOf: (Int) -> Double): Double = (1..beats).sumOf { tempoOf(it) }
```

```csharp
[Fact]
public void Metronome_CountTicks_ReturnsTheMembersOwnScalar()
{
    using var metronome = new Metronome(4);
    var ticks = new List<int>();
    int total = metronome.CountTicks(tick => ticks.Add(tick));
    Assert.Equal(4, total);
    Assert.Equal(new List<int> { 1, 2, 3, 4 }, ticks);
}

[Fact]
public void Metronome_SumWeights_LambdaReturnsIntByValue()
{
    using var metronome = new Metronome(4);
    Assert.Equal(20, metronome.SumWeights(tick => tick * 2));
}

[Fact]
public void Metronome_Loudest_LambdaReturnsSignedByte()
{
    using var metronome = new Metronome(4);
    // -100 read through an unsigned wire would arrive as 156 and win the max.
    sbyte loudest = metronome.Loudest(tick => (sbyte)(tick == 1 ? -100 : tick));
    Assert.Equal((sbyte)4, loudest);
}

[Fact]
public void Metronome_TotalTempo_LambdaReturnsDouble()
{
    using var metronome = new Metronome(4);
    Assert.Equal(5.0, metronome.TotalTempo(tick => tick * 0.5));
}
```

Note from the 2026-09-21 spikes: on today's main the scalar-outer cells make `Tier1Harness.run` throw (uncaught contract `require`), so their red state is an exception, not a `compileErrors` entry; keep one `Unit`-outer cell (`fun weighAll(weigh: (Int) -> Int)`) so the lambda-return axis has a red that names its own cause (`expected 'Int', actual 'String'`). The fixture and xunit tests above are unchanged by the spikes.

Tier 1 text pins: `private static extern int Native_CountTicks(`, `internal delegate int NugetIntIntCallback(int arg0, IntPtr userData);` exactly once, `CFunction<(Int, COpaquePointer) -> Int>`, no `WrapString(` and no `asStableRef<String>` on the scalar members, and a refused cell (`fun pick(cb: (Int) -> Unit): Mood?`) absent from both halves with a named warning.

## Deferred scope

- Line 70 (stored-callback scalar payload): not this change. Spike c confirmed finding 7 (2026-09-21), so reword the ROADMAP line and backlog file to "boxes a scalar payload under the `Object` name; by-value is an optimisation", drop the collision claim (finding 7). When done it reuses the step 3 predicate, touches `exports/StoredCallbackExports.kt` and `translateStoredCallbackMethod`, moves `Boolean` to `Bool` per ADR-036 `:597-598`, and must land suffix and wire together. Natural second PR of a stack.
- Line 81 (`ForwardAbiLegacyRoutes` sealed branch misses arm callback routes): test-only consumer, unrelated.
- Line 77 (interface default invisible through the interface type): unrelated to the return; the class re-emission is fixed here as a side effect (**inferred**, same function).
- Nullable outer returns, enum and exported-object outer returns, `Char` on either axis, object or enum lambda returns, mixed parameters, two lambdas: refused by name in this change, bound later by the plan migration rather than by growing the legacy route.
- Arity is unchanged (0 to 3 via `LAMBDA_TYPES`, `CirTypeMapping.kt:60-62`).

## Open what-questions

1. Is the end state "per-call callbacks on the ADR-062 plan", and should it be queued now? Recommendation: yes, as its own ROADMAP item with a new ADR (next free number is 158), after this fix; it is the only way to bind mixed parameters and the object, top-level and extension positions without hand-writing a second return matrix. Human decision: pending.
2. Should the exported-object and enum METHOD returns (`fun firstToy(where: (Toy) -> Boolean): Toy`) be bound here instead of refused? They are plausible real shapes and cheap (retain plus `new global::Ns.Toy(handle)`, ordinal for enum), but the object cell needs a LeakTests row and nullable (`Toy?`, the `firstOrNull` shape) arrives with it. Recommendation: refuse by name here, bind on the plan (question 1); fold in only if the human wants `Toy`/`Toy?` before the migration. Human decision: pending.
3. Finding 6 (non-lambda parameters ignored) has no ROADMAP line. Recommendation: refuse by name inside this change (same gate, no new line), since it is a build failure on the same two functions. Human decision: pending.
4. Line 70: accept the rewording (no collision today) or have `kotlin-dev` confirm with a Tier 1 cell first? Recommendation: reword. Spike c ran on 2026-09-21 and confirmed there is no collision (finding 7), so no further confirmation is needed; pinning cell c as a permanent Tier 1 test is optional and cheap. Human decision: pending.
5. New ADR or amendment? Recommendation: ADR-036 amendment, same shape as its 2026-09-11 and 2026-09-13 amendments; there is one idiomatic answer (`Func<int,int>`, by value) and the table already says it. Human decision: pending.

## Spikes run (2026-09-21)

All on main `651cb7d0`, in a separate worktree, nothing committed. Seam T1 = a scratch Tier 1 test calling `Tier1Harness.run(source)` via `./gradlew :nuget-processor:test --tests '*Tier1SpikeCallbackReturnTest*'`: it runs the real processor (KSP2), captures `Interop.cs` and `CNameExports.kt`, and compiles the generated Kotlin with `K2JVMCompiler` against the cinterop stubs. Seam CS = the captured `Interop.cs` built alone in a scratch `net8.0` classlib (`Nullable` on, `AllowUnsafeBlocks` on, `ImplicitUsings` enabled). This only APPROXIMATES the ADR-138 `nugetCompileInterop` gate: the real csproj (`nuget-plugin/.../NugetCompileInteropTask.kt:87-97`, verified by reading) also sets `LangVersion 12.0`, `TreatWarningsAsErrors` and `GenerateDocumentationFile`, and does not set `ImplicitUsings`. The error codes quoted are the errors left with implicit usings ON; see the incidental observation below for what appeared with them OFF. No Kotlin/Native link and no runtime execution was done, so nothing here verifies run-time behaviour or konan-only checks.

| Spike | Shape | Seam | Result |
|---|---|---|---|
| a | `fun sumWeights(weigh: (Int) -> Int): Int` | T1 | Processor throws `IllegalArgumentException: Forward ABI mismatch for metronome_sumWeights; expected ...(in pointer, in pointer, in pointer, out pointer) -> pointer, actual ... -> int` at `ForwardAbiContract.kt:183`. The contract (outer axis) fails first; no Kotlin is written. CONTRADICTS pass 1's "Kotlin compile fails first". |
| a1 (added) | `fun weighAll(weigh: (Int) -> Int)` | T1 + CS | Isolates the lambda return. Kotlin: `Return type mismatch: expected 'Int', actual 'String'.` C#: `error CS1503: Argument 1: cannot convert from 'int' to 'string'` on `NugetMarshal.WrapString(weigh(arg0))`. Both halves broken, as finding 4 says. |
| a2 (added) | `fun countTicks(listener: (Int) -> Unit): Int` | T1 | The ROADMAP line 75 shape: same thrown contract mismatch, `-> pointer` vs `-> int`. |
| b | `fun firstToy(where: (Toy) -> Boolean): Toy` | T1 + CS | Contract passes, no warning. Kotlin does NOT compile: `Return type mismatch: expected 'Toy', actual 'Toy?'.` Export returns the bare object, no `retain`. C# compiles alone and does `return new Toy(nativeHandle);`. Build failure today, not a silent bad pointer. |
| c | `onTick` + `addTickListener`/`removeTickListener`, all `(Int) -> Unit` | T1 + CS | Exactly one `internal delegate void NugetIntVoidCallback(int arg0, IntPtr userData);` and one `internal delegate void NugetObjectVoidCallback(IntPtr arg0Ptr, IntPtr _);`. Stored route boxes and reads `FromHandle<int>`. Kotlin compiles clean, C# `Build succeeded`. ROADMAP line 70's collision claim is wrong. |
| d | `fun f(scale: Int, cb: (Int) -> Unit)` | T1 | Contract passes, no warning. Kotlin: `No value passed for parameter 'scale'.` C# `public void F(Action<int> cb)`, `scale` dropped on both halves. |
| e1 (added) | `fun moodAfter(listener: (Int) -> Unit): Mood` (enum) | T1 + CS | Kotlin: `expected 'Mood', actual 'Mood?'`. C#: `error CS1729: 'Mood' does not contain a constructor that takes 1 arguments`. |
| e2 (added) | `fun maybeLabel(listener: (Int) -> Unit): String?` | T1 | Kotlin: `expected 'String', actual 'String?'`. C# declares non-null `string` and applies `!`. |

Every refused cell in recommendation step 4 that was spiked (object, enum, nullable outer return, non-lambda parameter) is today a silent processor success (exit OK, zero warnings) followed by a compile failure in generated code, which is the case for the named refusal.

Still unspiked, all non-load-bearing for the recommendation: the C# text for a scalar outer return (`public Int ...`, harness discards `Interop.cs` on throw); `Boolean` and `Char` outer returns (same contract path as `Int`, inferred); `Set`/`Map` outer return; two-lambda member; object or enum LAMBDA return and its double-free concern (refused in step 4); the bridged `List` parameter of line 72; konan's verdict on a bare object `@CName` return; any run-time behaviour.

Incidental observation, out of scope, not on the ROADMAP (grep `Interlocked`, no hit): with `ImplicitUsings` disabled, cells a1, b and e1 (a module whose only classes have per-call lambda members, no async, no stored callback) ALSO failed with `error CS0103: The name 'Interlocked' does not exist in the current context` on the generated `Dispose()` (`IntPtr handle = Interlocked.Exchange(ref _handle, IntPtr.Zero);`), while cell c (stored callback, so `tracker.needsSubscription`) built clean. **Verified by reading**: `using System.Threading` is added only under `needsAsync` or `needsSubscription` (`cir/CirTranslator.kt:812-819`). **Inferred**: a module with neither fails the real `nugetCompileInterop` gate, since that csproj does not enable implicit usings; not reproduced through the real gate, and whether a plain class without any lambda member hits it was not checked. Worth a what-question or an issue of its own; it does not affect this item.

## Spike first (post-implementation only)

e. After the change: the `bool` extern with `[return: MarshalAs(UnmanagedType.I1)]` still scrapes as `BOOL` in `csharpLegacy`, and the `delegate* unmanaged[Cdecl]<int, IntPtr, int>` thunks pass the AOT leg (ADR-102). **Inferred** until then.
