# ADR-149: Supported arities of a partially unsupported signature bind; the unsupported arity stays a named skip

## Status

Accepted

## Context

GitHub issue [#229](https://github.com/xxfast/kotlin-native-nuget/issues/229), ROADMAP Phase 4
line 48, split out of [#131](https://github.com/xxfast/kotlin-native-nuget/issues/131) /
[ADR-064](064-forward-unsupported-declaration-diagnostics.md)'s 2026-09-09 "Not fixed here"
paragraph.

[ADR-091](091-constructor-default-parameters.md) and
[ADR-096](096-function-default-parameters.md) surface a trailing run of Kotlin defaulted
parameters to C# as `@JvmOverloads`-style omitting overloads: one extra export per suffix length.
When the *declared* arity has an unsupported parameter (the standing cell is
`hubWithEvents(settings: Settings = Settings(), events: Flow<Int>? = null)` in
`test-library/.../issue131/HubSample.kt:47`), every shorter overload that never mentions that
parameter is dropped with it. The C# consumer loses `HubWithEvents()` and
`HubWithEvents(Settings)` along with the arity that still carries `events`. The skip diagnostic
is correct for that longest arity and must stay.

The mapping question: does a partially unsupported signature bind at the arities that are fully
bridgeable? The ABI-numbering question: a skipped declared namesake already consumes an occurrence
([ADR-095](095-static-route-overloads.md)); the first surviving synthesized arity may land on
`_2` with the unsuffixed export unused.

This is forward only. Kotlin declares the function or constructor; C# consumes the synthesized
overloads. Admitting `Flow` (or any other unsupported type) at an input is [#131](https://github.com/xxfast/kotlin-native-nuget/issues/131)'s
deeper question and is out of scope. Emitting the unsupported arity with `IntPtr` is the shape
[#126](https://github.com/xxfast/kotlin-native-nuget/issues/126) rejected.

### What the source actually does

The backlog file blamed `synthesized()` and counted six call sites. Both are slightly off.
**Verified by reading this branch.**

`ForwardCallableCatalogEntry.synthesized()`
(`nuget-processor/.../forward/ForwardCallablePlanner.kt:1563-1565`) is a Planned-only copy:
`if (this is Planned) copy(synthesized = true) else this`. It is not what drops the shorter
arities. Each route's `entryFor` / `staticEntry` / `topLevelEntry` already does
`parameters.dropLast(omitted)` and re-runs `planOrSkip` on the truncated list (class
`:1075-1086`, sealed base `:1198-1208`, sealed arm `:1329-1340`, static/object/companion
`:1792-1830`, constructor `:1611-1624`).

The function routes bail *before* that call:

```kotlin
if (declared[index] !is ForwardCallableCatalogEntry.Planned) return@forEachIndexed
```

Seven sites, not six:

| route | file:line |
|---|---|
| top-level | `ForwardCallablePlanner.kt:670` |
| extension | `:688` |
| class method | `:1096` |
| sealed base | `:1219` |
| sealed arm | `:1351` |
| object | `:1719` |
| companion | `:1760` |

Constructor synthesis (`constructorEntries` `:1453-1471`) has **no** Planned gate. Each arity
goes through `constructorEntry` independently. That is why [ADR-115](115-opt-in-marker-declarations.md)
gate (b) already binds the shorter constructor when a trailing defaulted *property-marked*
parameter is omitted (`GroomingLog` / `groominglog_create_2` in
`Tier1OptInMarkedParameterArityTest.kt:150-158`), and why a constructor whose trailing defaulted
parameter has an opt-in-marked *type* skips every arity with its own number
(`GroomingPlan.<init>:`, `.<init>_2:`, `.<init>_3:` in the same test at `:61-95`). ADR-091's
Decision already recorded the merely-unsupported constructor consequence: a trailing defaulted
`Map` input still gets its omitting overloads planned even though the full signature is skipped.

`planOrSkip` (`:2140-2213`) classifies only the truncated `parameters` list as inputs. The dropped
tail is consulted solely by `droppedOptInMarker` (`:2134-2138`), which returns a detail only when
a dropped parameter's **type** is opt-in-marked. A merely-unsupported dropped type (nullable
`Flow`) is the point of the omitting overload and stays off that check. The 2026-09-10 amendments
of ADR-096 and ADR-115 already drew that line.

For `hubWithEvents` minus `events`, the remaining input is `Settings` (`BridgeType.ObjectHandle`,
`inputSkipReason()` returns null at `:3221`) and the result is `Hub` (has a return shape).
`droppedOptInMarker` is null. `planOrSkip` therefore returns Planned. **Verified by reading
`planOrSkip` and `inputSkipReason` (`:3212-3290`, nullable `SpecializedProtocol` that is not
sealed is `NULLABLE` at `:3271-3273`).** No spike: [ADR-096](096-function-default-parameters.md)
already verified that a shorter plan emits a shorter positional Kotlin call
(`ForwardKotlinPlanEmitter.kt:58-64` joins `plan.publicSignature.parameters`;
`invocationExpression` at `:899-919` renders `hubWithEvents($arguments)` / `Target($arguments)`
from that list). Kotlin fills `events = null` at that call site, which is core language
behaviour, the same mechanism ADR-091 uses for `Cat(name)`.

The Kotlin export for a supported shorter arity never mentions `Flow`. There is no claim here
about filling a defaulted `Flow` *across the bridge*.

`plansFor` (`:540-557`) already allows zero declared-Planned entries (`require(planned.count { !it.synthesized } <= 1)`).
A synthesized Planned with no declared Planned sibling is legal today; it just never happens on
the function routes because of the Planned gate. ROADMAP line 49 (`plansFor` sibling invariant)
would make that state a crash, which is incompatible with this mapping. That require stays out.

The interface route (`interfaceEntries` `:948-980`) synthesizes nothing in v1 (ADR-096). It has
no Planned gate to drop. Interface synthesis and interface-route numbering (ROADMAP line 50)
stay out.

## Alternatives Considered

### 1. Judge each synthesized arity on the parameters it still carries; keep the named skip; keep numbering holes (chosen)

Drop the seven Planned gates. Each suffix length already has an `entryFor(..., omitted + 1)` that
truncates and re-runs `planOrSkip`. A fully bridgeable truncated list becomes Planned; an
arity that still carries an unsupported parameter stays `Skipped` with the existing kind and
parameter name. Opt-in-marked *types* still poison every arity through `droppedOptInMarker`
(issue #128, unchanged). Numbering keeps the holes ADR-095 and constructor synthesis already
leave: a skipped declared namesake consumes the unsuffixed occurrence, synthesized continue the
counter.

Pros: the processor change is the seven `return@forEachIndexed` lines; the Kotlin emitter, the C#
projection, collision checks, and `droppedOptInMarker` need no behavioural change; constructors
already are this option; `@JvmOverloads` is the settled analogue (ADR-091/096); when the
unsupported type later binds, the unsuffixed export fills in and the shorter exports keep their
numbers. Cons: the first surviving synthesized arity of a lone namesake lands on `_2`; a function
whose every arity is illegal (opt-in-marked type) starts emitting one skip per arity, matching
constructors, which moves `Tier1OptInMarkedParameterArityTest`'s "schedule skipped exactly once"
pin.

### 2. Keep today's all-or-nothing skip

The declared skip kills synthesis. Rejected: it is the bug. `HubWithEvents()` never touches
`events`.

### 3. Pack the numbers (reuse the skipped declared's unsuffixed export for the first surviving arity)

Give `HubWithEvents(Settings)` the bare `hubwithevents` export instead of `hubwithevents_2`.
Pros: no unused C symbol. Cons: the day `Flow` at an input binds, the unsuffixed export has to
become the full arity and every shorter export shifts, which is an ABI break of the overloads
this feature just shipped. Packing by *not incrementing* a skipped declared would also shift
later declared namesakes of the same name, which is the ADR-095 rule. Constructors already keep
the holes (`GroomingPlan.<init>_2`, `groominglog_create_2`). Rejected.

### 4. Admit nullable `Flow` (or `IntPtr`) at the unsupported arity

Rejected by the issue and by this ADR's scope. #131's mapping question is separate; #126
rejected a placeholder wire. Either would hide this bug behind a different one.

### 5. Special-case only the zero-argument arity, or constructors only

Rejected by the issue. `hub(settings, logger)` is as bridgeable as `hub()`. The case in hand is a
top-level function; constructors already bind.

## Decision

Apply option 1 on every route that already synthesizes omitting overloads: the five ADR-096
function routes, plus the sealed base and sealed arm copies of the same Planned gate. Those two
are not a fifth function route, they are the same defect on ADR-116's members.

### The rule

For a declaration with a trailing run of `d` defaulted parameters, each `k` in `1..d` is judged
on the parameter list `p1..p(n-k)` only:

- If that list is fully bridgeable (and no dropped parameter has an opt-in-marked *type*),
  synthesize the omitting overload.
- If that list still carries an unsupported parameter, skip that arity named, same kind and
  parameter as today.
- The declared arity is unchanged: still Planned when fully bridgeable, still a named skip when
  not.
- A defaulted parameter followed by a required one still synthesizes nothing (positional Kotlin
  call, ADR-091/096, `@JvmOverloads`).
- An opt-in-marked parameter *type* still makes every arity illegal (ADR-096 2026-09-10,
  ADR-115 gate (b) amendment 2026-09-10, `droppedOptInMarker`). A marker on the parameter or
  property while the type is unmarked still repairs at the shorter arity (the `GroomingLog`
  control).

Do not admit new types. Do not emit the unsupported arity. Do not synthesize on
`interfaceEntries`. Do not add a `plansFor` "synthesized requires a declared sibling" require.

### Numbering

Keep the holes. The declared pass still runs first and still increments before the structural
skip (ADR-095, `overloadSuffix` `:1633-1655`; class/object/companion `occurrences.merge` before
`planOrSkip`). Synthesized entries still append after every declared entry of the counter scope
and continue the same counter (ADR-096). Worked example, lone namesake:

```
fun hubWithEvents(settings: Settings = Settings(), events: Flow<Int>? = null)
```

| entry | occurrence | export | C# |
|---|---|---|---|
| declared, skipped | 1 | `hubwithevents` unused | (absent) |
| synthesized, `settings` | 2 | `hubwithevents_2` | `HubWithEvents(Settings)` |
| synthesized, empty | 3 | `hubwithevents_3` | `HubWithEvents()` |

A later declared namesake of the same name already took its number in the declared pass, so it
does not move. Constructors already number this way (`${prefix}_create` skipped, first
synthesized `${prefix}_create_$n` with `n` from `secondaries.size + 2`).

**Inferred (not observed, load-bearing if Flow-at-input later ships):** keeping the holes means
the unsuffixed export is the slot the full arity will occupy, and the shorter exports keep their
numbers. Packing would force those numbers to shift on that day. The C ABI is not a public
surface (ADR-034/090/091), but shim and native still ship from one build (ADR-054), so a packed
scheme that then has to be unpacked is a needless churn inside that pair.

### Diagnostic

The declared skip stays. Today's sentence, **verified** against the fixture build log recorded in
ADR-064:

```
[nuget:SKIPPED_UNSUPPORTED_INPUT] Skipping io.github.xxfast.kotlin.native.nuget.test.issue131.hubWithEvents: its parameter `events` has a nullable type with no supported wire. ...
```

`warnDroppedForwardCallables` (`NugetProcessor.kt:255-274`) sets `declaration = dropped.symbol`,
so the location is the catalog symbol. For the declared arity that is the unsuffixed qualified
name. The reason already names `` `events` `` (`ForwardDiagnostic.kt:650-652`).

v1 keeps that sentence. Tightening it to name the arity (`hubWithEvents(settings, events)`) is a
wording follow-up, not a mapping one. After this change the same warning will sit beside a live
`HubWithEvents()`; that is truthful (the arity that still has `events` is skipped) even if it
reads slightly oddly.

Dropping the Planned gate means a function whose *every* arity is illegal (opt-in-marked type)
starts emitting one skip per numbered symbol, matching `GroomingPlan.<init>` / `.<init>_2` /
`.<init>_3`. Update `Tier1OptInMarkedParameterArityTest`'s `schedule` pin from "exactly once" to
the constructor-shaped per-arity set. A merely-unsupported 2-parameter cell still produces one
skip (only the declared arity fails).

### Consumer surface

Standing fixture, after the change:

```kotlin
fun hubWithEvents(settings: Settings = Settings(), events: Flow<Int>? = null): Hub
```

```csharp
using Hub a = HubSample.HubWithEvents();           // hubwithevents_3, events = null
using var settings = new Settings(3);
using Hub b = HubSample.HubWithEvents(settings);   // hubwithevents_2, events = null
// HubSample.HubWithEvents(settings, events) does not exist
```

Issue #229's 3-arg shape, which the standing fixture does not yet have. Bind the 2-arg arity too,
not only zero and one:

```kotlin
fun hub(settings: Settings = Settings(), logger: Logger? = null, events: Flow<Event>? = null): Hub
```

```csharp
HubSample.Hub();
HubSample.Hub(settings);
HubSample.Hub(settings, logger);   // never touches events
// HubSample.Hub(settings, logger, events) does not exist
```

A constructor with the same trailing unsupported default is the already-correct control
(no Planned gate). Pin it so a later gate cannot regress constructors down to the function bug.

### Processor change

Delete the seven `if (declared[index] !is Planned) return@forEachIndexed` lines. Leave
`entryFor` / `droppedOptInMarker` / `synthesized()` / numbering / `plansFor` alone. The
override/base-planned gates in `classEntries` and `sealedSubclassEntries` stay: those are the
ADR-096/116 "the C# base already carries the overload" exclusions, a different question.

### Kotlin filling the default

**Verified:** the export body is a positional call built from the truncated plan
(`ForwardKotlinPlanEmitter.kt:58-64, 899-919`). **Verified trivially, same as ADR-091:** Kotlin
computes the omitted default at that call site. The default on the standing fixture is `null`.
The C# caller never passes a `Flow`.

## Consequences

- `HubWithEvents()` and `HubWithEvents(Settings)` compile. The arity that still has `events`
  stays absent and still warns `SKIPPED_UNSUPPORTED_INPUT` naming `events`.
- Constructors do not change behaviour. They gain a pin.
- Sealed base and sealed arm members with a trailing unsupported defaulted parameter start
  binding their supported arities, same as class methods. ROADMAP line 45 (sealed base reading
  raw `hasDefault` instead of `memberDefaultFlags`) is a different defect and stays out.
- Interface-route defaults stay absent (ADR-096 v1, ROADMAP line 50).
- Value-class method defaults stay absent (ADR-096 deferred).
- `plansFor` returning only synthesized Planned entries becomes a real state. ROADMAP line 49
  must not add a "declared sibling" require; that require would crash this mapping.
- Opt-in-marked *type* on a function with defaults grows from one skip diagnostic to one per
  arity, aligning functions with constructors. The `schedule` cell of
  `Tier1OptInMarkedParameterArityTest` moves.
- No new handle kind, no new `NugetMarshal` member, no `LeakTests` row. `HubWithEvents()` mints
  the same `Hub` handle `Hub()` already does; the one-arg form borrows a `Settings` handle the
  same way `Hub(settings, ...)` does. Existing issue #131 leak coverage stays.
- FEATURES.md's function-default-parameters row gains one clause: an unsupported parameter costs
  exactly the arities that still carry it.
- The documenter owns `docs/adr/README.md`.

### Inferred claims an implementer may hit

1. *A constructor whose trailing defaulted parameter is a nullable `Flow` already binds the
   shorter arities today.* **Not fixture-proven for `Flow`.** The code path is the same
   `constructorEntry` + `planOrSkip` that `GroomingLog` already exercises for a trailing marked
   property (verified). If the first constructor pin is red, the Planned-gate story is wrong and
   constructors share a different bug; stop and re-read `constructorEntries` before changing
   function routes.
2. *Dropping the Planned gate is enough; no emitter change.* **Verified by reading** that both
   halves iterate catalog plans (`plansFor` / owner-keyed accessors) and that a shorter plan
   already renders. Not re-run through `scripts/verify.sh` (research does not build the repo).
3. *Keeping numbering holes preserves shorter-arity ABI the day the unsupported type later
   binds.* Reasoning, not observed. See Numbering.
4. *C# overload resolution picks `HubWithEvents()` vs `HubWithEvents(Settings)` as a natural
   set.* Standard C#, same surface ADR-096 already ships for `Hub()`. Not spiked.
5. *`KSValueParameter.hasDefault` is `true` on the standing fixture's `events`.* The synthesis
   loop uses that bit; if it were false there would be nothing to drop the gate *for*. The
   fixture is written with `= null`. **Inferred from source text, not from a KSP dump this
   session.** A red first test with no omitting overload is this bit, same as ADR-096's inferred
   claim 1 (now verified on other routes).

Everything labelled **Verified** above was read in this worktree at the line references given, or
is a fixture assertion named above. No repo Gradle build, no scratch spike: the load-bearing
"truncated `planOrSkip` returns Planned" claim is a classification of types the classifier
already admits, and the load-bearing "shorter plan, shorter Kotlin call" claim is already
verified by ADR-096.
