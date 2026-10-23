# Decisions (gate, 2026-10-22, read against kn-p4f-a at 6ee56924)

Rule 1 applies: nothing below is deferred. Every gap has a lane. One item is under ESCALATE TO USER
at the end, because it is impossible as specified and only possible with a surface compromise the
user has to pick.

ADR numbers: a lane that amends releases its number; new lanes take 233+ in the order named here.

| Lane | ADR | Verdict |
|---|---|---|
| A1 | amend ADR-216 (release 223) | accept, plus one amendment |
| A2 | ADR-224 | accept |
| A3 (new) | ADR-232 | design below |
| A4 (new) | ADR-233 | design below |
| B1 | ADR-225 | accept |
| B2 | ADR-226 | accept, star result moved to B5 |
| B3 | amend ADR-220 (release 227) | accept |
| B4 | ADR-228 | accept, sealed-interface underlying moved to B5 |
| B5 (new) | ADR-234 | design below |
| B6 (new) | ADR-235 | design below |
| C1 | ADR-229 | accept with siblings folded in, one sibling moved to A3 |
| C2 | ADR-230 | accept, keep the reserved number |
| C3 | ADR-231 | accept |
| C4 (new) | ADR-236 | design below |

## 1. Lane rulings

### A1: nested open generic instantiation, every position fills (amend ADR-216)

Accept the memo: one `BridgeType.openSlots()` collector on the model, one `openSlotFills` renderer
helper, called from every generated C# body that brings a Kotlin value to C# (member result and
parameters, `TryX` twin, getter, setter, callback thunk, flow element, returned lambda, suspend
result, `Result` payload, extension), sealed nested handles collected, abstract generic class gets a
slot through its backing.

Amendment: the fill rule also applies to the generated interface-bridge thunks (`*BridgeState<T>`)
once A4 lets a class handle reach a C#-implemented slot. A1 writes the rule in the ADR as "every
generated C# body"; A4 applies it to the thunks.

Open questions:
1. Amendment, not ADR-223. Same mechanism, no new surface.
2. No owner static-constructor fill. One mechanism.
3. Replace the silent `!hasCustomBody` return with `check(...)`. Let Tier 1 answer.
4. Yes, a nested slot is always typed at the sealed base; the arm constructor spelling is never
   needed. Pin it with the `picked` Tier 1 cell.
5. Interface slots refusing class handles: A4, not A2 (see below).

Unchecked-cast `T` conjuring edge (finding 8): not a gap. A result-only own `T` is already admitted
(`Groomers.kt:33`, `fun <T> nothing(): T?`, C# names the argument), so the C# member is generic over
`T` and the member-entry fill names `Basket<T>` with `T` bound at the call site. The value reaching
C# is typed by a generated signature, and every generated signature fills. A1 adds the fixture
`fun <T> conjure(): Basket<Basket<T>> = Basket(Basket(null as T))` and an xunit row reading
`Groomer.Conjure<string>().Item.Item` (null) to pin it.

### A2: generic interface at every position (ADR-224)

Accept the memo's end state: `classify` returns `genericInterfaceOrNull` for every position,
reachability no longer filters `bridgeEntry != null`, dispatch exports over the erased receiver
(`asStableRef<Keepsake<Any?>>()`, ADR-198 trampoline for a self bound), generic backing wrapper
with hoisted externs, interface `Factories` key plus `fillingOpenSlots` arm plus implementer static
constructor line, `INugetBridgeable<TSelf>` DIM mint in `Wrap<T>`/`HandleOf<T>`, and step 4 lifting
`SKIPPED_GENERIC_INTERFACE_ASYNC_MEMBER` for the Kotlin-backed side.

Open questions:
1. Backing wrapper name: reuse the non-generic collision check (`CirTranslator.kt:235-240`). No new rule.
2. Return of an interface whose bridge is refused: bind every output position; name only the
   input and erased-input positions with the bridge's refusal. After A3 and A4 the only refusal
   left is the member-level `<R>` (ESCALATE TO USER), so this branch is small.
3. C#-implemented suspend member: A3. It is not a ROADMAP line (Phase 7 reads "Complete"); it is
   an ADR-084 "Deferred" entry (`084:409`), which rule 1 counts as a deferral.
4. Marker visibility in the base list: accept. Only AOT-safe universal mint found, spike-verified.

### A3 (new): a C#-implemented interface with `suspend` and `Flow` members (ADR-232)

Gap: `ForwardInterfaceBridgePlanner.kt:405-406, 425-426` refuse a suspend or Flow member for every
bridgeable interface, generic or not; ADR-084:409 lists `suspend` members as deferred.

Design: a suspend slot is an ADR-210 suspend-lambda slot bound to the implementation's method.
- C# interface member stays what ADR-174 renders for the Kotlin-backed side: `Task<R> FooAsync(args,
  CancellationToken cancellationToken = default)`, `KotlinFlow<T> Watch(args)`. No surface change
  for shipped bindings; an existing C# implementer just gains a member to implement.
- Kotlin bridge object: `override suspend fun foo(args): R` calls the runtime's `awaitForKotlin`
  seam (`NugetAwait.kt`) with the slot's thunk; the thunk calls `impl.FooAsync(args, token)` and
  completes through the ADR-210 End thunk for `R`. Cancellation of the Kotlin coroutine cancels
  the token (ADR-210's shape). Result types: the ADR-084 slot vocabulary (own `T` boxed,
  `Throwable`, enum, `Unit`, `String`, five primitives); where ADR-210 has no End thunk for one of
  them, A3 adds the thunk, it does not refuse.
- Flow member: the bridge's `override fun watch(): Flow<T>` reads the slot's handle result. A
  `KotlinFlow<T>` the implementer got from Kotlin passes its own handle back. For a C#-made flow,
  A3 adds `public static KotlinFlow<T> KotlinFlow<T>.From(IAsyncEnumerable<T>)`, which wraps the
  enumerable with the ADR-210 `flowForKotlin` pull seam (`NugetFlow.kt`); the bridge receives the
  Kotlin `Flow<T>` the seam produces. `StateFlow`/`SharedFlow` members on a C#-implemented
  interface take the same handle route for a Kotlin-made flow and are named by their own sentence
  for a C#-made one, exactly ADR-210's existing rule for those parameters (that rule is a shipped
  ADR-210 decision, not a new deferral; A3 documents the composition).
- Lifetime: the slot's delegate is the bridge object's own method, so no per-call key; the
  ADR-084 cleaner that owns the bridge owns it.
- The unreachable-interface suspend member (C1 finding 9, `ForwardCallablePlanner.kt:1519-1543`)
  lands here: an unreachable interface has no Kotlin-backed instance that can reach C#
  (`interfaceEntries` KDoc), so the only consumer of its suspend member is a C# implementation.
  A3 declares the member on `I<Name>` whether or not the interface is reachable, and bridges it.
  C1 does not touch this site.

Depends on A2 (generic interface classification and the lifted async-member skip).

### A4 (new): interface bridge slot vocabulary widens to every classifier-admitted type (ADR-233)

Gap: `ForwardInterfaceBridgePlanner.bridgeType` (`:498-575`) admits the own `T`, `Throwable`,
enums, `Unit`, `String` and five primitives. Exported class handles (A1 finding 9), collections
(ADR-084:409 "object-typed and collection-typed slot"), value classes, nullable primitives and
enums, `Char`, `Instant`, `Duration`, `Uuid`, `ByteArray`, generic class and generic interface
references, and lambdas are all refused.

Design: the slot wire is the plan's wire. `bridgeType` asks `ForwardBridgeTypeClassifier.classify`
and lowers the result with the same `nativeInputParameters` / result-shape machinery a class member
plan uses (one slot per native parameter, has-value fan-out pairs included), so an interface slot
and a class member typed alike cross alike. C# thunk side: the thunk reads with the ADR-062
projection the member plan already renders (`Materialize`, `FromHandle`, `Wrap`, value-class
unwrap) and the A1 fill rule runs at thunk entry. Kotlin side: the bridge object's `override`
marshals with `ForwardKotlinPlanEmitter`'s lowering for the same `BridgeType`. A lambda-typed slot
rides the ADR-160 callback wire (plain) or the ADR-210 wire (suspend). Ownership: a class handle
returned from C# is `WrapOwned` through the ADR-222 retain export, a passed one is borrowed, the
rule every plan position already has.

Alternative rejected: hand-adding one `when` arm per type to `bridgeType`. That is how the
vocabulary got stuck at nine entries; the planner must stop having its own type table.

Depends on A2 (one classifier entry for interfaces). After A4 the only remaining bridge refusal is
the member-level `<R>` (ESCALATE TO USER).

### B1: generic value class members and secondary constructors (ADR-225)

Accept: value-receiver route with the substituted underlying at the class's own `T`, `Nothing`
spelling for a phantom, secondary constructors as `_create_N` exports returning the underlying,
hoisted externs on the struct, member own type parameters admitted on any value class.

Open questions:
1. No C# `where` on the struct. Keep ADR-220's header; bounds check in Kotlin.
2. `Value`/`Deconstruct` collisions: ADR-117 diagnostics apply; pin in Tier 1, no new renaming.
3. Secondary constructor whose parameters do not mention `T`: spell `pkg.Sticker<Any?>(args)`
   (erased bound otherwise). The author's unchecked cast, not the bridge's.

### B2: own `T` in a generic value class under `?` and in collections (ADR-226)

Accept the box-handle rule for a nullable outer over a nullable-admitting underlying, the
collection component arm, the nullable-underlying-at-non-null-outer admission (`Nick`,
`MaybeCount`, closed `Sticker<String?>`), the lossy `List<Nick?>` wire replaced, and the
star-projected parameter as a synthesized method type parameter.

Open questions:
1. Star-projected result: B5 (below), one rule for both positions.
2. Box handle, not fan-out, at the top level. One mechanism; the allocation is the same every
   erased position already pays.

Depends on B4 (box pair) and B3 (bound gate).

### B3: own `T` in a generic value class whatever its bound (amend ADR-220)

Accept: delete `hasSpellableBound()`, trampoline through ADR-217 where needed. Amendment, release
227. Lands first in worktree b.

### B4: generic value class at every erased position (ADR-228)

Accept: one box/unbox pair per generic value class, `INugetGenericValue` dispatch in `Wrap<T>`,
`NugetFactory<Sticker<T>>` and `<Sticker<T>?>` registration from the static constructor, `NugetBox`
and every erased-position call site, `Nick` (nullable underlying) folded in.

Open questions:
1. Registration at every erased call site: yes. Spike finding 8 shows an unregistered closed type
   misses; two `??=` per call is the price.
2. `Box<object>` holding a `Sticker`: unchanged `NotSupportedException`; document.
3. Ineligible sealed-interface underlying: B5, and the memo's claim that it is "not on this code
   path" is wrong (section 4).

### B5 (new): value class underlying widenings (ADR-234)

Three gaps, one lane, because all three touch `valueClassUnderlyingRole`, `hasErasedCrossing` and
`genericValueClass()` together.

1. **Star-projected or `out`-projected generic value class at a result** (`fun any(): Sticker<*>`).
   Decision: the same synthesized method type parameter B2 uses at a parameter, caller-supplied at
   a result: `public static Sticker<TSticker> Any<TSticker>()`, read `FromHandle<TSticker>`.
   Reason: `Sticker<object?>` cannot be read (`Materialize<object>` has no boxed-primitive branch,
   `kotlin.Any` has no `BridgeType`, ADR-208 refuses `Box<Any>`), so it would need a new `Any`
   wire for one edge. A wrong `TSticker` fails exactly as a wrong `as T` in any own-`T` result
   does today (`fun <T> nothing(): T?` ships that hazard); the ADR says so in one sentence.
2. **Value class over an ineligible sealed interface at erased positions.** ADR-204 classifies it
   as `SpecializedProtocol(SEALED_HELPER_PREFIX)` with an `I<Name>` `sealedHandle`
   (`ForwardBridgeTypeClassifier.kt:406-420`) and `valueClassUnderlyingRole` makes it REFERENCE;
   the only block is `hasErasedCrossing()` admitting `ObjectHandle` alone. Decision: admit the
   sealed-helper protocol (eligible sealed base included, same arm), unbox through
   `I<Name>.FromHandle(handle)` / the base's `FromHandle`, the ADR-171 pair otherwise unchanged.
   Also admit the sealed-helper underlying in `isOrdinaryValueClassUnderlying()` if the Tier 1 cell
   shows a non-erased position skipping (ADR-105 says it crosses; the predicate does not list it).
3. **Value class over `Char` and `Char?`.** `Char` has a full wire (`nuget_wrap_char`,
   `nuget_unwrap_char`, `char` at every ordinary and component position). Decision:
   `valueClassUnderlyingRole` VALUE for `Char` and `Nullable(Char)`, `isOrdinaryValueClassUnderlying`
   admits `Char`, `Char?` fans out as ADR-098 part C already does for a bare `Char?`,
   `hasErasedCrossing` admits it, `Sticker<Char>` stops being `UNDECLARED_VALUE_CLASS`.

Depends on B4 (erased pair) and B2 (star parameter rule).

### B6 (new, gate-found): the remaining refused value class underlyings (ADR-235)

Found by the gate in `value-classes.md:32-38` and `ForwardValueClassUnderlying.kt:70-96`, not by a
memo: `Instant`, `Duration`, `Uuid`, `ByteArray`, a collection, another value class and a plain
interface underlying are refused by name although each has a shipped wire at every ordinary
position. Rule 1 does not let me leave a refusal I read. Decision, one rule: the struct's `Value`
is the underlying's existing C# spelling (`DateTimeOffset`, `TimeSpan`, `Guid`, `byte[]`,
`IReadOnlyList<T>`, the inner struct, `I<Name>`) and the underlying crosses on the wire it uses as
a parameter today; `init` runs in Kotlin on the re-wrap. A nested value class unwraps recursively
to the innermost underlying in C# and re-wraps outward in Kotlin. Erased positions ride the
ADR-171 pair with that wire. Last in worktree b so the earlier lanes are not held by it.

### C1: `suspend` on static owners and the sibling owners (ADR-229)

Accept: generalise the top-level suspend route over a static receiver expression on both halves,
ad-hoc root scope per call, `namedSuspend()` dropped in `objectEntries` and `companionEntries`, a
`forwardStaticSuspendRouteMethods` selector shared by emitter, translator and diagnostics.
Siblings folded in with the receiver as a leading parameter of the `_async` export: suspend
extension (receiver handle, C# `this` extension), enum member (ordinal, `MoodExtensions`),
value-class member (the value class's wire slot, instance method on the struct). The unreachable
interface member is A3's (above), C1 leaves `ForwardCallablePlanner.kt:1519-1543` alone.

Open questions:
1. Yes, siblings ride ADR-229. One planner change, one mechanism; this is what makes it an ADR.
2. Companion vs member `XAsync` collision: reuse `CirMemberNameCollisions`' sync rule; the lane
   verifies it sees async members and extends it if not. No name-skip.
3. Owner-qualified companion call (`Den.open()`), as the plan route spells it; a named companion
   must use the owner form.

### C2: `Flow` on static owners, functions and properties (ADR-230)

Accept alternative 1 (ad-hoc root scope per collection, no owned scope), every flow kind, held
`MutableStateFlow`/`MutableSharedFlow` through the flow-keyed seams with a null scope, static flow
properties (finding 7) in scope, generic form through ADR-215's trampoline with `owner = null`.
Keep the reserved number: three owners, two member kinds and five flow kinds is a mapping decision
a reader should find under its own ADR, and it saves a renumber.

Open questions:
1. Alternative 1. Parity with the shipped awaited static flow (`CirConcurrencyRenderer.kt:456-461`).
2. Held `val`: a fresh holder per read, as the instance held property route does.
3. Facade class: the same file-facade class as top-level functions (ADR-007).

### C3: plain lambda on a `suspend` or `Flow` member (ADR-231)

Accept alternative 1: Kotlin `try/finally` through the shared `RELEASE_CONTEXT` thunk, key handed
over by `collectionScopedCall`, Flow sibling (finding 8) in scope.

Open questions:
1. Alternative 1. One mechanism for both routes.
2. Stored plain lambda: key-miss behaviour of finding 4 is enough; matches ADR-160. Document on the
   topic page.
3. C3 lands after C1 in worktree c, so C3 owns the Tier 1 cell for an `object`'s suspend member
   with a plain lambda. Lambda properties on static owners are C4 (below).

### C4 (new): lambda-typed properties on every owner the class route does not reach (ADR-236)

Probe answered from source: `ForwardLambdaPropertyCarrier.NONE` names an interface, a companion,
an object, top level, an enum and a generic class as having no lambda-property route
(`ForwardLegacyLambdaProperty.kt:21-24`, `classLambdaPropertyCarrier()` at `:43-48`), so a
`val onTick: (Int) -> Unit` on any of them is a named skip today. Confirmed gap.

Design: the class route's getter (`ClassExports.kt:513-530`, `NugetHandles.retain(<receiver>.prop)`)
and `CirClassTranslator`'s lambda adapter generalise over a receiver expression, the C1/C2 move:
static spelling for object, companion and top level; `Mood.entries[ordinal]` for an enum member
(C# extension on `MoodExtensions`); the erased owner `asStableRef<Owner<Any?>>()` for a generic
class (ADR-147's reason goes away the same way ADR-215 removed it for flows); the dispatch export
over the interface handle for a Kotlin-backed interface, read by the backing wrapper. Get-only as
today; a `var`'s setter stays the ADR-075/107 named partial skip (that is a shipped rule, not a new
deferral). Suspend lambda properties ride ADR-202's `nuget_suspend_func*` on every owner. A new
carrier value per owner, so the planner's report and the emitters cannot disagree. The
C#-implemented side of an interface lambda property is A4's slot vocabulary.

## 2. Gap assignment

| Gap | Lane | Decision |
|---|---|---|
| Interface slot refuses every exported class handle (A1 finding 9) | A4 | slot wire = plan wire, whole classifier vocabulary |
| Unchecked-cast `T` conjuring with no `T` input (A1 finding 8) | A1 | not a factory gap; fixture and row pin it |
| C#-implemented interface with a `suspend`/`Flow` member (A2 Q3, ADR-084:409) | A3 | ADR-210 slot bound to `impl.FooAsync`, `KotlinFlow<T>.From` |
| Member-level `<R>` on a bridged interface (ADR-222) | ESCALATE | see below |
| Star-projected `Sticker<*>` result (B2 Q1) | B5 | caller-supplied synthesized type parameter |
| Value class over an ineligible sealed interface at erased positions (B4 Q3) | B5 | `hasErasedCrossing` admits the sealed-helper protocol |
| Value class over `Char` (`value-classes.md:33`, ADR-220:127) | B5 | `Char` and `Char?` are VALUE underlyings |
| Top-level `V?` wire (B2 Q2) | B2 | box handle, not fan-out |
| `Instant`/`Duration`/`Uuid`/`ByteArray`/collection/nested value class/plain interface underlyings (gate-found) | B6 | underlying's own spelling and wire |
| Suspend extension, enum member, value-class member (C1 finding 9) | C1 | receiver as leading `_async` parameter |
| Suspend member of an unreachable interface (C1 finding 9) | A3 | declare on `I<Name>` regardless of reachability, bridge it |
| Static `Flow`/`StateFlow`/`SharedFlow` properties (C2 finding 7) | C2 | static receiver, no scope slot |
| Plain lambda on a `Flow`-returning member (C3 finding 8) | C3 | same `finally` release around `collectForCSharp` |
| Lambda properties on static owners, enum, generic class, interface (C3 probe) | C4 | class getter route over a receiver expression |

## 3. Lane order and dependencies

Worktree a: A1, A2, A3, A4.
- A1 independent. A2 independent of A1 (A2's `openSlotHandles` Interface arm lands on A1's
  collector, so rebase A2 onto A1's model change). A3 and A4 depend on A2. A4 applies A1's fill rule
  to bridge thunks, so A4 after A1 and A2 (A3 before A4 only because A3 is smaller and A4 then
  covers A3's new slot kinds in one place).

Worktree b: B3, B1, B4, B2, B5, B6.
- B3 first (shared `isOwnInsideGenericValueClass` gate). B1 creates `StickerNative` and the struct
  member rendering B4 extends. B4 before B2 (box pair). B5 after B2 and B4. B6 last, isolated.

Worktree c: C1, C2, C3, C4.
- C1 and C2 share the static-receiver generalisation of the legacy routes; C1 first, C2 reuses its
  receiver spelling. C3 after both (its Tier 1 cells combine with static owners). C4 last, reuses
  the same receiver expression helper.

Cross-worktree: A3 and C1 both touch `ForwardCallablePlanner.kt` near `interfaceEntries`; C1 now
leaves `:1519-1543` alone, so no overlap. A4 (interface slots) and C4 (interface lambda property,
Kotlin-backed side) touch different halves of the interface; assemble a before c.

## 4. Memo claims I think are wrong, with evidence

1. **A1 finding 8, the "conjured `T`" residue.** Not a factory gap. A result-only own `T` is
   admitted (`test-library/.../membergeneric/Groomers.kt:33`, `fun <T> nothing(): T?`, "the Kotlin
   call names its type argument"), so the generated C# member is generic and the member-entry fill
   names every open slot in its signature with `T` bound by the caller. There is no path by which a
   Kotlin value reaches C# outside a generated signature.
2. **B4 Q3, "the ineligible sealed interface underlying is not on this code path".** It is.
   `valueClassUnderlyingRole()` (`ForwardValueClassUnderlying.kt:70-73`) makes a
   `SpecializedProtocol` with `SEALED_HELPER_PREFIX` a REFERENCE underlying, and ADR-204
   (`ForwardBridgeTypeClassifier.kt:406-420`) gives an ineligible sealed interface over declared arms
   that protocol with an `I<Name>` `sealedHandle` and its own `FromHandle`. The blocker is
   `hasErasedCrossing()` admitting only `ObjectHandle`, the same predicate B4 edits. The sealed
   interface does have a C# crossing.
3. **C1 finding 9, "unreachable interface member" as a C1 sibling.** `interfaceEntries` is "only
   called for interfaces the caller already determined appear in a planned return"
   (`ForwardCallablePlanner.kt:1487-1489` KDoc), so an unreachable interface has no Kotlin-backed
   instance in C# and nothing for a static-receiver suspend export to dispatch to. The gap is on
   the C#-implemented side (A3).
4. **A2 Q3, "the main thread decides whether it is already a ROADMAP item".** It is not. ROADMAP
   Phase 7 reads "Complete"; the only record is ADR-084:409 "Deferred: ... `suspend` members". An
   ADR-only deferral is still a deferral under rule 1.
5. **A1 finding 10 / A2's reuse of `isErasedTypeArgument` refusing a generic value class
   argument.** True today, but B4 flips it (`is BridgeType.ValueClass -> hasErasedCrossing()`), so
   after assembly `Basket<Sticker<T>>` is a shape A1's collector must not stop at. A1's `openSlots`
   `ValueClass` arm must recurse into a generic value class's own `openArguments` rather than
   return empty; A1 writes it that way now (no cost before B4 lands, correct after).
6. **C2 memo, amendment vs ADR.** Not wrong, overruled: the reserved number stays (section 1).

## ESCALATE TO USER

**A member-level type parameter on a C#-implemented interface (`interface Keepsake<T> { fun <R>
map(f: (T) -> R): R }`), ADR-222:218 "a genuine impossibility".**

Impossible as specified: a C# generic method needs `R` at the call site, Kotlin erases `R`, and the
only runtime instantiation (`MethodInfo.MakeGenericMethod`) is reflection that has no code for a
value-type `R` under NativeAOT (ADR-222 Alternative 3 measured this for `MakeGenericType`).

Possible only with a surface compromise: declare the member on the C# interface erased, `object?
Map(Func<T, object?> f)`, and call it at `R = object`. That needs an `Any` wire the bridge does not
have: `kotlin.Any` reaches no `BridgeType` (it falls through `classify` to the exported-handle
check), ADR-208 refuses `Box<Any>`, and `Materialize<object>` has no boxed-primitive branch
(`CirMarshalRenderer.kt:201-231`); the runtime has no type-tag export to build one on
(`nuget_unwrap_*` are per type). Building that wire (a `nuget_box_kind` export plus an `object`
read that dispatches on it, and a Kotlin-name keyed factory table for handles) is a new ADR-sized
item that also changes what the C# interface looks like against the Kotlin one.

The user has to choose: (a) build the `Any` wire and erase member-level type parameters on bridged
interfaces to `object` (new lane A5, ADR-237), or (b) record it as impossible in ADR-222 as it
already is, which is not a deferral because no design satisfies the spec. I cannot pick (b) under
rule 1 and cannot pick (a) without the user accepting the erased surface.

### A6 (new): an extension binds on every receiver, sync and `suspend`, generic or not (ADR-238)

Accept the five-part design: Part 1 (sync generic extension function on the plan route, receiver
checked by the own-`T` gate like a parameter), Part 2 (bare-`T` home), Part 3 (generic extension
property with `extension<T>(...)`), Part 4 (suspend receiver is a leading `ForwardLegacyParameterShape`,
`legacyParameterShape` gains `Interface`, `BoundInterface`, `ValueClass`, `Converting`, `Bytes`),
Part 5 (remaining sync property receivers). Runs in worktree d, before B6 at assembly.

Open questions:
1. **Bare-`T` home.** Yes: bounded `T` on `{FirstBound}Extensions` in the bound's namespace,
   unbounded (`Any`/`Any?`) on `ObjectExtensions` in the declaring package's namespace. An
   intersection bound homes on the first declared bound (the one ADR-198 spells first). One helper
   owns the key for all five sites. An unbounded extension appearing on every C# type is what the
   author declared; Kotlin/JVM does the same.
2. **Widen suspend parameters too.** Yes. One lowering per wire for receiver and parameter (the
   ADR-159 lesson). The lane pins the current parameter refusals with one Tier 1 cell first, then
   flips it; if a kind is already green as a parameter the cell just pins it.
3. **Member extensions.** Check first with a Tier 1 probe (`class Groomer { fun Cat.brush() }`):
   if the class walk drops it silently or plans it with the receiver lost, that is a bug and A6
   fixes it in the same lane; it binds as an instance method on the owner with the extension
   receiver as the first parameter, `groomer.Brush(cat)`, Kotlin/JVM's own lowering (C# cannot
   declare an extension inside a non-static class). If the probe shows a named skip, same design,
   same lane. Suspend member extensions take the ADR-229 class suspend route with the receiver as a
   leading parameter, the C1 sibling shape. Its own section in ADR-238.
4. **Cell 23, `Result<T>` on the suspend route.** Not carried today:
   `ForwardLegacyRouteCollections.kt:973` keeps `kotlin.Result<T>` on the generic refusal. Rule 1:
   A6 builds it. Decision: a `suspend fun ...: Result<T>` binds as `Task<T>`; Kotlin calls
   `getOrThrow()` inside the coroutine before the completion callback, so a failure faults the
   `Task` with the ADR-032 mapped exception. No `TryXAsync` twin: a `Task` already is the try
   shape, and ADR-195's `out`-pair cannot be spelled on an awaitable. One return-shape arm, so it
   applies to every suspend owner (class, static, extension), not only extensions; the ADR says so.
   Cell 23 then loses its special case and `reified T` reifies at its bound (ADR-213).
5. **Projection receivers.** Include. `fun Bindle<*>.isPacked()` and `fun List<*>.describe()` get a
   synthesized method type parameter (`IsPacked<T>(this Bindle<T> receiver)`,
   `Describe<T>(this IReadOnlyList<T> receiver)`), Kotlin reads `Bindle<*>` / `List<Any?>`, the
   element on ADR-212's boxed wire. Same rule as B2/B5's star-projected value class, receiver
   position only; the classifier's projection refusal stays everywhere else.
6. **`ByteArray` function receiver doc.** `ForwardCallablePlanner.kt:4792` admits `ByteArray` at
   every input with no receiver carve-out, so `extensions.md:85` is most likely stale already. The
   Tier 1 pin decides; either way the row reads "yes / yes" after A6 and the docs lane rewrites the
   receiver table as a single sentence ("every receiver the bridge can pass as a parameter").

Amendments to the memo: none to the mechanism. Finding 16 (Flow return on a suspend extension) is
C2's static collect route; A6 rebases onto C2 at assembly and adds one Tier 1 cell for
`suspend fun Cat.watch(): Flow<Int>` then, owned by whichever lands second (C2 is in worktree c,
so A6 adds it).

Dependencies: A6 is built on ADR-229 (already in kn-p4f-d). Assembly order: after c (C1, C2), before
B6. Touches `ForwardLegacyRouteCollections.kt`, which C3 also edits (`legacyParameterShape(parameter)`
callback arm); different functions, rebase A6 last among the legacy-route lanes.
