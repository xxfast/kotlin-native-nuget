# ADR-090: Ordinary-class method overloads: mirror ADR-082's numbering, catalog-driven emission

## Status

Accepted

## Context

Ordinary classes have no overload scheme (ROADMAP line 103, recorded as a latent bug in
ADR-082's Consequences). `ForwardCallablePlanner.classEntries` names every method export
`"${prefix}_$name"` and keys every plan by `"$owner.$name"`
(`ForwardCallablePlanner.kt:594,608`), so two same-name methods on one class produce duplicate
symbols and duplicate export names.

**Verified by spike (2026-08-10, temporary Tier 1 test through the real processor, since
reverted):** a class with `fun describe(): String` and `fun describe(prefix: String): String`
crashes the KSP run with

```
java.lang.IllegalArgumentException: Forward callable catalog has duplicate plans for
tier1.overloadspike.Cat.describe; two declarations share one qualified name (an unfiltered
expect/actual pair is the usual cause)
```

thrown from `planFor`'s `require(matches.size <= 1)` (`ForwardKotlinPlanEmitter.kt:482`), reached
from `ClassExports.kt:268`. The `ForwardAbiContract` duplicate-export guard
(`ForwardAbiContract.kt:79-80`) is a second line of defence behind it; the `planFor` guard trips
first.

**Also verified by the same spike, out of scope here:** the crash shape is shared by every other
same-name route. Object members (`objectEntries`), companion members (`companionEntries`),
top-level functions (`topLevelEntry`), and extension functions (`extensionEntry`) each crash with
the same message (`...Clinic.open`, `...Clinic.Companion.of`, `...greet`, `...pat`). None of them
is fixed by this ADR: their numbering would be scoped differently (per-object, per-companion, and
per-package rather than per-class, with `toCName` in the export shape), so they are separate
roadmap items, not free riders on `classEntries`.

ADR-082's 2026-08-08 amendment shipped the ready-made template for value classes
(`valueClassMethodEntries`, `ForwardCallablePlanner.kt:451-514`): secondary-constructor-style
numbering (ADR-034/035 precedent, `${prefix}_create_${index + 2}`), catalog-driven member
emission, and the ADR-034 `ERROR_CSHARP_SIGNATURE_COLLISION` check. This ADR maps that template
onto ordinary classes.

## Alternatives Considered

### 1. Mirror ADR-082 verbatim: planner-side numbering + catalog-driven emission (chosen)

First declared overload keeps the bare name, the n-th further one is numbered `_$n` (n from 2),
and both emitters read the class's member plans off the catalog instead of re-deriving a
per-declaration plan key. Pros: exact precedent (ADR-082 amendment, itself mirroring ADR-034
constructor numbering); the C# surface stays one natural overload set; the numbering lives in
exactly one place. Cons: the C# translator needs per-plan `isOverride`/`isVirtual` flags that
today come from the `KSFunctionDeclaration` walk, so those must be computed at planning time and
carried on the plan.

### 2. Occurrence-aware re-derivation in the emitters

Keep the per-declaration `planFor("$owner.$name")` loops in `ClassExports.kt` and
`CirClassTranslator.kt`, each maintaining its own same-name occurrence counter. Rejected: this is
the three-site lockstep ADR-082's amendment explicitly abandoned ("the two halves must not
drift", `CirClassTranslator.kt:1633-1635`), and for ordinary classes the drift risk is worse than
it was for value classes: the emitters' method lists are filtered differently from the planner's
(`ClassExports` partitions out Flow-returning, lambda-parameter, and interface-bridge methods
before its plan loop, verified at `ClassExports.kt:234-270`), so a skipped namesake would consume
a number in the planner but be invisible to a naive emitter-side counter.

### 3. Signature mangling (parameter types in the export name)

JNI-style `cat_describe_string`. Rejected for the same reasons ADR-082 rejected it for value
classes: unreadable exports, an open-ended type-name encoding, and no in-repo precedent. The
numbering scheme is already shipped and tested for constructors and value-class methods.

### 4. Skip every overload after the first, with a named diagnostic

Rejected: overloads are natural, idiomatic C#; dropping them silently degrades the surface where
a working scheme already exists one origin over.

## Decision

Ordinary-class methods adopt ADR-082's overload numbering verbatim, with catalog-driven emission
on both halves.

### Naming scheme (mirrors `valueClassMethodEntries`, verified source)

In `classEntries`, count same-name occurrences over the *declared plannable* members, in
`getAllFunctions()` order, after the existing filters (visibility, the `equals`/`hashCode`/
`toString`/`<init>`/data-class-`copy`/`componentN` exclusions, and
`isForwardPlannableMemberOf`), the same way `valueClassMethodEntries` counts after its
inherited-member skip:

| occurrence | export name (`@CName` / DllImport EntryPoint) | plan symbol         | C# public name |
|------------|-----------------------------------------------|---------------------|----------------|
| 1st        | `${prefix}_$name`                             | `$owner.$name`      | `Name`         |
| n-th (n≥2) | `${prefix}_${name}_$n`                        | `$owner.${name}_$n` | `Name`         |

- An inherited namesake never consumes a number (it never reaches the counter; the
  `isForwardPlannableMemberOf` filter runs first). A *structurally skipped* declared namesake
  (SUSPEND / GENERIC / CALLBACK_PROTOCOL) does consume a number, exactly as in
  `valueClassMethodEntries` where the counter increments before the structural check
  (verified, `ForwardCallablePlanner.kt:484-493`). Numbering is therefore deterministic per
  declaration order, not per surviving-plan order, matching ADR-034's constructor behaviour.
- `planOrSkip` receives `member = name`, so `ForwardInvocation.member` carries the bare declared
  name (the slot ADR-082 added; verified it exists and defaults to null,
  `ForwardCallablePlanner.kt:996`).

### Kotlin half

- **`invocationExpression`'s CLASS branch must consume `invocation.member`.** Verified today it
  derives the call-site name from the symbol tail
  (`functionName = plan.invocation.symbol.substringAfterLast('.')`,
  `ForwardKotlinPlanEmitter.kt:795-805`), which for a suffixed symbol would emit a call to a
  nonexistent Kotlin method `describe_2`. It becomes
  `plan.invocation.member ?: symbol.substringAfterLast('.')`, the exact line the value-class
  emitter already uses (`ForwardKotlinPlanEmitter.kt:346-347`). The COPY branch is unaffected
  (data-class `copy` is name-filtered out of `classEntries`).
- **`ClassExports`'s plan loop goes catalog-driven.** The per-declaration
  `planFor("$qualifiedName.$methodName")` loop (`ClassExports.kt:265-270`) becomes an iteration
  over a new catalog accessor, `ForwardCallablePlanCatalog.classMethods(owner)`: the
  CLASS-origin plans whose `symbol.substringBeforeLast('.') == owner`, excluding `<init>`, in
  planning order, mirroring `valueClassMethods(owner)`
  (`ForwardCallablePlanner.kt:247-258`). Flow / lambda / interface-bridge members never produce
  CLASS-origin plans (they are planner-side Skipped, CALLBACK_PROTOCOL among others, verified
  `ForwardCallablePlanner.kt:595-600`), so catalog iteration cannot double-emit them.
  Note the accessor's filter must be owner-exact: `interfaceEntries` also emits CLASS-origin
  plans, keyed by the interface's own qualified name (verified,
  `ForwardCallablePlanner.kt:528-560`), so prefix matching would be wrong; exact
  owner-segment equality is required.

### C# half

- **`CirClassTranslator`'s method loop reads the same accessor.** The per-declaration
  `planFor` at `CirClassTranslator.kt:430` becomes iteration over `classMethods(owner)` for the
  planned members; the abstract-member fallback (`CirClassTranslator.kt:442-449`), which has no
  plan by design, stays on the existing declaration walk.
- **`isOverride`/`isVirtual` move to planning time.** Today they are computed per declaration at
  the emitter (`method.modifiers.contains(Modifier.OVERRIDE)`,
  `isOpenInterfaceImplementation(superClass)`, `CirClassTranslator.kt:438-439`). Planned entries
  do not retain the `KSNode` (verified: `ForwardCallableCatalogEntry.Planned` holds only the
  plan, `ForwardCallablePlanner.kt:100-104`), and matching plans back to declarations by node
  identity across separate `getAllFunctions()` calls is unverified for KSP2, so the flags are
  computed in `classEntries` (which already resolves `cls.forwardSuperClass()`, verified line
  567) and carried on the plan (inferred as the cleanest carrier: two new optional fields on
  `ForwardInvocation`, defaulting to false, ignored by every non-CLASS origin; an implementer
  finding a better seam on the plan model may use it, the requirement is only that the flags
  ride the plan).
- **The private extern *name* must carry the number, not just the EntryPoint.** Verified today
  both extern-name sites derive from the *public* name: `methodNativeImport` builds
  `name = "Native_${method.name}"` (`CirNativeImports.kt:106`) and `classMethod`'s body
  projection calls `resultProjection(nativeName = "Native_${plan.publicSignature.name}", ...)`
  (`ForwardCirPlanProjection.kt:361-362`). Two overloads can share a wire shape (an `Int` and an
  enum parameter both cross as `int`), and one extern name declared twice is CS0111; ADR-082 hit
  and documented exactly this (`ForwardCirPlanProjection.kt:79-84`). Both sites therefore derive
  from the numbered symbol tail (`Native_Describe` / `Native_Describe_2`), which leaves
  unsuffixed members rendering byte-identically to today. The EntryPoint itself already follows
  `CirMethod.nativeName` (verified: `entryPoint = "${nativePrefix}_${method.nativeName}"`,
  `CirNativeImports.kt:104`), which comes from the plan's export name, so it is numbered for
  free.
- **ADR-034 signature-collision check extends to ordinary-class methods.** C# cannot overload on
  reference nullability alone. The value-class check (`CirClassTranslator.kt:1642-1671`: group
  planned methods by public name + parameter types with reference nullability stripped and value
  nullability kept, emit `ERROR_CSHARP_SIGNATURE_COLLISION` for any group larger than one) is
  replicated over the class's planned methods, beside the existing duplicate-constructor check
  (`CirClassTranslator.kt:98-126`). Verified that no such check exists for ordinary-class
  methods today (the only ERROR_CSHARP_SIGNATURE_COLLISION emit sites are the two above plus
  ADR-040's type-name collision in `CirTranslator.kt:280`).

### Consumer surface

```kotlin
// Kotlin
class Cat(val name: String) {
  fun describe(): String = "a cat"
  fun describe(prefix: String): String = "$prefix cat"
  fun describe(prefix: String, excited: Boolean): String = /* ... */
}
```

```csharp
// C#: one natural overload set, no visible numbering
var cat = new Cat("Momo");
cat.Describe();               // -> cat_describe
cat.Describe("fluffy");       // -> cat_describe_2
cat.Describe("fluffy", true); // -> cat_describe_3
```

### Amendment (implementation, 2026-08-10)

Three corrections found while implementing, all verified against the real pipeline:

- **There is a *third* extern-name site, and it is the decisive one.** For a sync-error-checked
  instance method, `CirErrorRenderer.renderSyncErrorCheckMethod` (`CirErrorRenderer.kt:154`)
  rebuilds the C# body from scratch and hardcodes `"Native_${method.name}"`, discarding the name
  the plan's `resultProjection` produced. With only the two sites this ADR named, the numbered
  externs are *declared* correctly and every overload body still calls `Native_Describe`
  (verified: `GeneratedBindingsCheck` failed with `CS1501: No overload for method
  'Native_Describe' takes 3 arguments`). Rather than derive the name a third time, the numbered
  name is carried on the CIR node: `CirMethod.externName: String? = null`, set by
  `ForwardCirPlanProjection.classMethod`, read by `CirClass.methodNativeImport` and by
  `renderSyncErrorCheckMethod`. Null keeps the shipped `Native_$name`, so unsuffixed members
  render byte-identically.
- **The flags ride `ForwardPublicSignature`, not `ForwardInvocation`** (the ADR permitted a better
  seam): `override` / `virtual` describe the rendered C# member, not how Kotlin is called, and
  `ForwardPropertyPlan`'s C# projection already takes them as rendering inputs.
- **Emission order inside a class changes.** `CirClassTranslator` now renders all planned methods
  (catalog order) followed by the abstract-member fallbacks (declaration order), instead of one
  interleaved declaration walk. Cosmetic in the generated C#; a class whose members are all
  planned or all abstract is unaffected.

## Consequences

- Two (or more) same-name methods on an exported ordinary class generate instead of crashing
  `packNuget`; the C# surface is a standard overload set.
- `planFor`'s duplicate-plans invariant stays as-is and stays meaningful: with numbering in the
  planner, a fresh firing again indicates a genuinely new source of duplicate qualified names
  (its ADR-074 purpose), not an overload.
- Overload numbering is declaration-order dependent, like ADR-034 constructor numbering:
  reordering same-name Kotlin declarations renumbers native exports. The C ABI is not a public
  surface (shim and native library always ship from one build, guarded by ADR-054's contract
  hash), so this is accepted.
- A same-name pair whose C# signatures collide (reference-nullability-only difference) fails
  generation with `ERROR_CSHARP_SIGNATURE_COLLISION` instead of emitting CS0111.
- Out of scope, recorded for the roadmap (each verified crashing with the same `planFor`
  message by this ADR's spike): object members, companion members, top-level functions, and
  extension functions still have no overload scheme. Same recommended template, separate items,
  because their numbering scope and export shapes (`toCName`, package-level keys) differ from
  `classEntries`.
- Deferred with their existing routes: overloads where one namesake is Flow-returning,
  lambda-taking, or an interface-bridge pair member keep those members' current non-planned
  handling; the planner-side occurrence counter still numbers around them deterministically.
