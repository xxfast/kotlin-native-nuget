# ADR-096: Function default parameters: ADR-091's omitting overloads on the five function routes

## Status

Accepted

## Context

[ADR-091](091-constructor-default-parameters.md) surfaced Kotlin constructor defaults to C# as
`@JvmOverloads`-style trailing-omitting overloads, and explicitly left the same problem open for
functions (its Consequences: "function/method default parameters ... use the same mechanism but land
in ADR-090's member numbering spaces, a separate ROADMAP item"). That is ROADMAP line 108. Today
`fun greet(name: String, loud: Boolean = false)` is a single two-argument C# static method and every
C# call site must pass `loud`.

The contract this ADR covers, and nothing wider: a C# consumer can omit trailing defaulted arguments
when calling a Kotlin **top-level function, class method, `object` member, companion member, or
extension function**.

The mechanism question is settled by ADR-091 and is not reopened here (`hasDefault` is a bare bit,
so C# optional parameters are impossible; the `@JvmOverloads` trailing rule is Kotlin's own answer;
SKIE fills the same gap for Swift by overload synthesis). What is genuinely open is where a
synthesized entry lands on five routes ADR-091 never touched, and which of those routes' emitters
can even see it. Constraints, read in this repository's source:

- **The Kotlin call site is built from the plan on every origin, not from the declaration.**
  `arguments` joins `plan.publicSignature.parameters` at all three emitter entry points
  (`ForwardKotlinPlanEmitter.kt:55,248,343`) and `invocationExpression` renders
  `<receiver>.$functionName($arguments)` for CLASS / EXTENSION / TOP_LEVEL / OBJECT / COMPANION
  (`ForwardKotlinPlanEmitter.kt:795-823`), taking the call-site name from `plan.invocation.member`.
  So a plan with a *shorter* parameter list emits a shorter positional Kotlin call on every one of
  the five routes, exactly as it does for CONSTRUCTOR. **Verified in source.**
- **An extension receiver is not in the plan's parameter list.** `extensionEntry` passes the
  receiver as `ForwardReceiver.Value(receiverType)` and the parameters separately
  (`ForwardCallablePlanner.kt:1167-1180`), and the C# projection rebuilds the public parameter list
  as `listOf(receiverParam) + plan.publicParameters()`
  (`ForwardCirPlanProjection.kt:401-420`). Truncating trailing parameters therefore cannot touch
  the receiver. **Verified in source.**
- **Three of the five routes are already catalog-driven on both halves.** Class methods, object
  members and companion members are emitted by iterating owner-keyed accessors on both the Kotlin
  and C# halves: `classMethods(owner)` (`ClassExports.kt:252`, `CirClassTranslator.kt:420`),
  `objectMethods(owner)` (`ObjectExports.kt:21`, `CirClassTranslator.kt:1192`),
  `companionMethods(owner)` (`ClassExports.kt:428`, `CirClassTranslator.kt:774`). A planner-
  synthesized entry appears in those lists with **zero emitter changes**. **Verified in source.**
- **The other two routes are per-declaration walks keyed by node identity, and they will crash.**
  Top-level and extension emission calls `callableCatalog.planFor(declaration)` at four sites
  (`NugetProcessor.kt:702`, `ExtensionFunctionExports.kt:19`, `CirTranslator.kt:157`,
  `CirTranslator.kt:344`), and that accessor holds
  `require(planned.size <= 1) { "...a route planned it more than once" }`
  (`ForwardCallablePlanner.kt:322-338`). A synthesized entry shares the declaration node with the
  entry it was synthesized from, so on those two routes the ADR-091 template **fails generation
  outright** until the accessor becomes plural. ADR-095 kept those walks deliberately (the C#
  halves group by `(namespace, file class)` and by receiver simple name), so replacing them with an
  owner-keyed accessor is not available. **Verified in source.**
- **The overload counters are per route and live in two different places.** Class / object /
  companion each build a local `occurrences` map inside their own entry builder
  (`ForwardCallablePlanner.kt:700,890,916`); top-level and extension counters live in `catalog()`
  itself because their scope spans the whole collected list (`ForwardCallablePlanner.kt:376-383`,
  ADR-095). **Verified in source.**
- **Unique extern names come for free.** `ForwardCirPlanProjection.overloadSuffix()` derives `_$n`
  from the plan symbol's tail minus `invocation.member` and applies it to both the DllImport
  `EntryPoint` and the private extern name on the static and extension projections
  (`ForwardCirPlanProjection.kt:194,433,471-480`). The top-level two-call route derives
  `${exportName}_has_value` / `${exportName}_value` from the passed export name
  (`ForwardCallablePlanner.kt:1030-1040`), so a numbered synthesized entry stays unique there too.
  **Verified in source.**
- **The C# collision check already covers every container a synthesized entry can land in.**
  `emitCsharpSignatureCollisions` groups a container's `CirMethod`s by `(name, parameter types)`
  with reference nullability stripped (`CirClassTranslator.kt:1141-1172`) and ADR-095 wired it to
  the class, object, companion-plus-class-statics, top-level file class and `{Receiver}Extensions`
  containers. Synthesized entries join those same member lists, so a collision fails generation
  with `ERROR_CSHARP_SIGNATURE_COLLISION` rather than emitting CS0111. **Verified in source.**
- **`expect`/`actual` erases the bit on the export root, and the index reaches top-level
  functions.** `expectsByName` is built from `resolver.getAllFiles().flatMap { it.declarations }`
  filtered to `isExpect`, keyed by qualified name (`NugetProcessor.kt:266-289`), so a top-level
  `expect fun greet(...)` **is** in the map under `pkg.greet`, as a `KSFunctionDeclaration`.
  **Verified in source.** Two hazards, both verified from the same lines: the map is built with
  `.toMap()`, so two `expect` overloads sharing a qualified name silently collapse to the last one;
  and members of an `expect class` are *not* individually keyed, only the class is.

## Alternatives Considered

### 1. ADR-091's rule on all five routes, synthesized entries appended per counter scope (chosen)

For every planned entry on the five routes, synthesize one extra entry per trailing-defaulted
suffix, appended after all declared entries of the same counter scope so declared exports keep their
numbers. Make `planFor(declaration)` plural for the two per-declaration routes. Pros: identical to
the shipped constructor mechanism, so the Kotlin emitter, the C# projection and the collision check
need no behavioural change; three of five routes need no emitter change at all; unsuffixed and
existing numbered exports render byte-identically. Cons: `planFor(declaration)` becomes
`plansFor(declaration)` at four call sites, and the ADR-074 "planned more than once" invariant has
to be restated in terms of *declared* plans rather than plan count.

### 2. Synthesize into a separate numbering space per route (e.g. an `_opt$k` suffix)

Give synthesized entries their own symbol/export namespace so they never interleave with declared
overload numbers. Pros: numbering of synthesized entries no longer depends on how many declared
namesakes exist. Cons: a second naming scheme on five routes for no consumer-visible benefit (the C#
surface shows no numbers either way), and `ForwardCirPlanProjection.overloadSuffix()` would need a
second derivation rule. Rejected: ADR-034/090/091 all use one continuing `_$n` sequence, and the C
ABI is not a public surface.

### 3. Make the top-level and extension routes catalog-driven instead of making `planFor` plural

Mirror ADR-091's `ClassExports`/`CirClassTranslator` change on the remaining two routes. Rejected:
ADR-095 already looked at this and kept the walks, because the C# halves group by `(namespace, file
class)` and by receiver simple name, and the Kotlin top-level loop has a per-declaration legacy
fallback (`NugetProcessor.kt:702-705`); an owner-keyed accessor cannot reproduce either grouping.
Plural `plansFor` keeps the grouping and is a strictly smaller change.

### 4. Do nothing on the two hard routes (class/object/companion only)

Ship the three catalog-driven routes now, defer top-level and extension. Rejected: `fun greet(name:
String, loud: Boolean = false)` is the exact example on the ROADMAP item, top-level functions are
the most common shape in the samples, and the deferral would leave two of five routes with a
different rule for no reason other than a four-call-site accessor change.

### 5. Add `plansFor(declaration)` alongside the existing singular `planFor(declaration)`

Keep the singular accessor for callers that "know" there is only one plan, and add the plural one
for the two synthesizing routes. Smaller diff on the ADR-095 code that just landed. Rejected
(confirmed with the human, 2026-08-14): a singular accessor whose `require(planned.size <= 1)` is
now *conditionally* true is a trap. It crashes the moment any future route synthesizes a second plan
for a node, with a message ("a route planned it more than once") that names the wrong cause and
points an implementer at a duplicate-planning bug that does not exist. There would also be no live
caller left for it, since all four sites move to the plural form. The singular accessor and its
guard are deleted, not deprecated.

## Decision

Apply ADR-091's rule verbatim to the five function routes.

### The rule

For a planned entry with parameters `p1..pn`, let `d` be the number of *trailing* parameters that
all have a default. For each `k` in `1..d`, synthesize one additional entry taking `p1..p(n-k)`. A
defaulted parameter followed anywhere by a non-defaulted one produces nothing, because the generated
wrapper is a positional Kotlin call and cannot skip a middle argument (ADR-091's rule, unchanged).
The full-signature entry is always emitted.

Two route-specific exclusions, both confirmed with the human (2026-08-14) and to be implemented as
written:

- **A method carrying `Modifier.OVERRIDE` synthesizes nothing.** Kotlin forbids an overriding
  function from specifying default values; the defaults belong to the base declaration
  (https://kotlinlang.org/docs/functions.html#default-arguments, "Overriding methods always use the
  same default parameter values as the base method" - **Inferred from documentation**, not spiked).
  The base class's own synthesized overload is inherited by the generated C# subclass, which
  genuinely extends its base (**Verified**: generated `public class Dog : Animal` keeps every base
  member callable, recorded on the ROADMAP inherited-member item), and Kotlin virtual dispatch
  inside the wrapper still reaches the override. Without this exclusion, a synthesized entry on the
  derived class would either be marked `override` against a base signature that does not exist
  (CS0115) or hide the inherited one (CS0108); the exclusion removes both. Synthesized entries
  therefore also always carry `isOverride = false, isVirtual = false`.
- **The interface route (`interfaceEntries`, `ForwardCallablePlanner.kt:640-663`) synthesizes
  nothing in v1.** Adding a member to a generated C# interface obliges every implementing class to
  carry it, and that route has no overload numbering at all today (`exportName =
  "${prefix}_${name}"`, **Verified in source**). Absent output, not wrong output.

A *class* method that is a defaulted interface member bound onto the implementing class
(`isForwardPlannableMemberOf`, `ForwardClassMembership.kt`) does get synthesis, since it is emitted
as an ordinary class member. The resulting class has an omitting overload the interface does not:
legal C#, and consistent with the interface exclusion above.

### Numbering, per counter scope

Synthesized entries are appended **after every declared entry of the same counter scope**, continuing
that scope's existing counter. Because the declared pass runs first and unchanged, every currently
shipped export name and plan symbol renders byte-identically.

| route         | counter scope                              | where the pass goes                                                        |
|---------------|--------------------------------------------|----------------------------------------------------------------------------|
| class method  | per `(class, simple name)` (`occurrences`)  | `classEntries` becomes `buildList { }`: declared `map`, then synthesized pass reusing the same `occurrences` map |
| `object`      | per `(object, simple name)`                 | same shape in `objectEntries`                                              |
| companion     | per `(companion, simple name)`              | same shape in `companionEntries`                                           |
| top-level     | per `(package, simple name)`, ADR-095       | a second `functions.forEach` in `catalog()` after the first, continuing `topLevelOccurrences` |
| extension     | per `(package, simple name)`, receiver-agnostic, ADR-095 | a second `extensionFunctions.forEach` in `catalog()` after the first, continuing `extensionOccurrences` |

Each synthesized entry reuses its route's existing entry builder with the parameter list
`parameters.dropLast(k)` and `member` set to the bare declared name (which every route already
passes), so `ForwardCirPlanProjection.overloadSuffix()` derives the `_$n` for both the DllImport
`EntryPoint` and the private extern name with no projection change (**Verified in source**, see
Context).

Worked example, one class, showing the interaction with declared overloads that ADR-095 numbers:

```kotlin
class Narrator {
  fun rate(count: Int): String                      // declared #1 -> narrator_rate,   symbol Narrator.rate
  fun rate(mood: String, boost: Int = 1): String    // declared #2 -> narrator_rate_2, symbol Narrator.rate_2
}                                                   // synthesized  -> narrator_rate_3, symbol Narrator.rate_3, params (mood)
```

C# surface: `Rate(int)`, `Rate(string, int)`, `Rate(string)`. One natural overload set, no visible
numbering.

### Defaults source (`expect`/`actual`)

ADR-091's `defaultFlags` gains **exactly one** new consultation, for the **top-level `actual fun`
and nothing else** (confirmed with the human, 2026-08-14). Per-parameter has-a-default is:

1. the parameter's own `hasDefault`; else
2. **only when the entry being planned is a TOP_LEVEL one**, the positionally matching parameter of
   `expectsByName["$package.$name"] as? KSFunctionDeclaration`, consulted **only** when that
   `(package, name)` has exactly one declared namesake in the ADR-095 counter, the resolved expect
   is not an extension (`extensionReceiver == null`), and its parameter count equals the actual's.
   The uniqueness guard is not optional: `expectsByName` is a `.toMap()` keyed by qualified name, so
   two `expect` overloads of one name silently collapse to the last one (**Verified in source**,
   `NugetProcessor.kt:284-289`), and consulting it for an overload set would attribute one
   declaration's defaults to another.

No other route consults `expectsByName` in v1. Class methods, `object` members, extension functions
and companion members read `hasDefault` off the exported (actual) declaration only, which for an
`expect`/`actual` pair always reports `false`; those four therefore get **no** synthesized overloads
on the `expect` path. That is absent output, not wrong output, and it is a deliberate v1 boundary,
not an omission to be "completed" while implementing: an implementing agent must not add a
`expectsByName[owner] as? KSClassDeclaration` member lookup, a companion lookup, or an extension
lookup here. The reasons they are out are recorded in the deferred list below (a class/object member
lookup needs a name-plus-arity match rule across two declarations that no spike has verified; a
companion of an `expect class` is a nested declaration and is not keyed in `expectsByName` at all,
**Verified in source**: the map is built from file-level declarations only; an extension shares the
top-level key space but adds receiver matching on top of the uniqueness guard).

`ForwardCallablePlanner` already takes `expectsByName` (ADR-091,
`ForwardCallablePlanner.kt:358`), so no new wiring.

### The one emitter change: `plansFor(declaration)` replaces `planFor(declaration)`

`ForwardCallablePlanCatalog.planFor(declaration: KSFunctionDeclaration)` currently requires at most
one plan per node (`ForwardCallablePlanner.kt:322-338`; **Verified in source**). Synthesized entries
share their source declaration's node, so:

- Add `synthesized: Boolean = false` to `ForwardCallableCatalogEntry.Planned`. Planner-internal;
  the `ForwardCallablePlan` model, `validate()` and the ABI contract are untouched.
- **Delete** the singular `planFor(declaration)` overload and its `require(planned.size <= 1)`
  guard, and add `plansFor(declaration): List<ForwardCallablePlan>` in planning order (declared plan
  first, then its synthesized entries). Not "add alongside": the singular accessor is a trap once
  any route synthesizes (see Alternative 5), and after this change no caller needs it. Keep
  `require(matches.isNotEmpty())` verbatim, and restate the ADR-074 duplicate-plan invariant as
  `require(planned.count { !it.synthesized } <= 1)`, which preserves exactly what the old check
  caught (a route planning one declaration twice) with a message that still says so.
  The `planFor(symbol: String)` extension (`ForwardKotlinPlanEmitter.kt:477`) is a different
  accessor on a different key and is untouched.
- All four call sites of the singular overload move to `plansFor` and iterate instead of branching
  on a single value; none may keep calling the deleted overload:
  `NugetProcessor.kt:702` (Kotlin top-level; an empty list keeps the legacy
  `addFunctionExports(func)` fallback, a non-empty one exports every plan in order),
  `ExtensionFunctionExports.kt:19` (Kotlin extension), `CirTranslator.kt:157` (top-level C#, the
  enclosing walk is already a `flatMap`), `CirTranslator.kt:344` (extension C#, already a
  `flatMap`).

### Consumer surface, one call site per route

```kotlin
// Kotlin
class Announcer(val prefix: String) {
  fun announce(message: String, loud: Boolean = false): String = ...
  fun tally(count: Int, label: String = "cats", excited: Boolean = false): String = ...
}
object Kennel { fun register(name: String, capacity: Int = 4): String = ... }
class Shelter { companion object { fun of(city: String, capacity: Int = 10): Shelter = ... } }
fun greet(name: String, loud: Boolean = false): String = ...
fun Announcer.repeat(times: Int = 2): String = ...
fun book(name: String, capacity: Int = 3, city: String): String = ...   // middle default
```

```csharp
// C#
var a = new Announcer("hi");
a.Announce("morning");                 // announcer_announce_2      -> loud = false
a.Announce("morning", true);           // announcer_announce        (unchanged)
a.Tally(3);                            // announcer_tally_3         -> "cats", false
a.Tally(3, "kittens");                 // announcer_tally_2         -> false
a.Tally(3, "kittens", true);           // announcer_tally           (unchanged)

Kennel.Register("Paws");               // kennel_register_2         -> capacity = 4
Shelter.Of("Colombo");                 // shelter_companion_of_2    -> capacity = 10
Announcers.Greet("Momo");              // greet_2                   -> loud = false
a.Repeat();                            // announcer_repeat_2        -> times = 2

Announcers.Book("Paws", 12, "Colombo");// full signature only: `capacity` is followed by a
                                       // required parameter, so no omitting overload exists
```

(Export names above are illustrative of the scheme, not of any particular fixture's final numbers:
each `_$n` is whatever that route's counter has reached.)

## Consequences

- `Greet("hi")` compiles; every existing full-signature call site is unchanged, and every currently
  shipped export name and plan symbol renders byte-identically, because synthesized entries are
  appended after the declared pass in each counter scope.
- `d` trailing defaults add `d` native exports per function (linear). Middle defaults add nothing,
  matching `@JvmOverloads` and ADR-091.
- Class, `object` and companion routes need **no** emitter change on either half. Top-level and
  extension need one accessor change (`plansFor`) at four call sites; without it those two routes
  fail generation with the ADR-074 "a route planned it more than once" message rather than
  producing anything wrong.
- A synthesized overload colliding with a declared one (`fun describe(p: String)` next to
  `fun describe(p: String, excited: Boolean = false)`) fails generation with
  `ERROR_CSHARP_SIGNATURE_COLLISION` in the owning container, not CS0111. The diagnostic `hint`
  gains ADR-091's defaulted-parameter clause ("...or remove the default value whose synthesized
  overload collides").
- `override` methods, interface-route members and value-class methods get no synthesized overloads,
  and an `expect`/`actual` pair gets them only on the top-level-function route: absent output, never
  wrong output.
- The singular `planFor(declaration)` accessor is gone. Any future route that starts synthesizing
  entries onto an existing declaration node inherits the plural accessor and cannot re-trip the
  ADR-074 message with the wrong cause attached.
- **Deferred, recorded for the roadmap** (nothing here is implemented by this ADR; tick the ROADMAP
  item without losing them):
  - **`expect`/`actual` defaults on the four non-top-level routes.** A class method or `object`
    member would need `expectsByName[owner] as? KSClassDeclaration` plus a name-and-arity match rule
    across two declarations, which no spike has verified; an extension shares the top-level key
    space but adds receiver matching on top of the uniqueness guard; a companion member of an
    `expect class` is not reachable at all, because `expectsByName` is built from file-level
    declarations only (**Verified in source**, `NugetProcessor.kt:266-289`).
  - **Interface-route defaults** (`interfaceEntries`), **and that route's total lack of overload
    numbering**: `exportName = "${prefix}_${method.simpleName}"` with no occurrence counter and no
    `_$n` suffix (`ForwardCallablePlanner.kt:640-663`, **Verified in source**), so two same-name
    interface methods presumably reproduce the duplicate-symbol crash ADR-090/ADR-095 fixed on the
    other routes. Not spiked here; adjacent, deliberately out of scope.
  - **Value-class method defaults**: `valueClassMethodEntries` is a separate numbering space
    (ADR-035/ADR-082), as is the value-class *constructor* case ADR-091 already deferred.
  - **Partial `Copy(...)` overloads** (ADR-091's reasoning is unchanged: a copy's defaults are the
    receiver's current values, not the constructor's).
  - **Secondary-constructor defaults on `expect` classes** (ADR-091, still open).
- **Inferred claims an implementer may hit, in the order they would bite:**
  1. *`KSValueParameter.hasDefault` reports `true` at a class method, `object` member, companion
     member and extension declaration.* **Not verified for these four.** It is Verified for a
     primary constructor (ADR-091) and for a top-level `expect fun` (ADR-074's spike), and no
     mechanism is known by which KSP would report a source-declared default for those but not for
     a class member; no spike was run for this ADR because a wrong answer produces **absent** output
     (no synthesized overload at all, loudly visible as a missing C# overload in the fixture's first
     test), never wrong output. If the very first fixture test on a route shows no omitting
     overload, check this bit before anything else.
  2. *Kotlin forbids an `override` from restating defaults*, from the language documentation
     (functions.html#default-arguments), not from a compile. The exclusion is conservative either
     way: if the bit turned out to be `true` on an override, the exclusion only costs an overload.
  3. *A top-level `expect fun`'s parameter count and order match its `actual`'s*, guaranteed by the
     language's actualization rules but not spiked (ADR-091 carries the same claim for classes); the
     one positional lookup this ADR keeps, and its parameter-count guard, rely on it.
- Everything labelled **Verified** above was read in this repository's source at the line references
  given. No build was run for this ADR: the feature is unimplemented, so a `packNuget` run could
  only have re-confirmed current behaviour, and the two claims a build would settle (the
  `planFor` duplicate-plan crash, and unique extern naming for numbered entries) are already pinned
  to `require(planned.size <= 1)` and `overloadSuffix()` in source.
