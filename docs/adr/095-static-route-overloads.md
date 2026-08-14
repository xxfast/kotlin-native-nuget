# ADR-095: Overloads on the four static export routes: object, companion, top-level, extension

## Status

Accepted

## Context

ADR-090 gave ordinary-class methods an overload scheme and recorded, spike-verified, that the four
other same-name routes still crash `packNuget` with the identical `planFor` duplicate-plans
message: object members (`objectEntries`), companion members (`companionEntries`), top-level
functions (`topLevelEntry`), and extension functions (`extensionEntry`), reproduced as
`...Clinic.open`, `...Clinic.Companion.of`, `...greet`, `...pat`. They were deliberately not
folded into ADR-090 because their numbering scopes and export shapes differ (`toCName` in the
export name, package-level symbol keys).

What the source proves today (all **verified** by reading the named lines on `main`):

- **Symbols and export names carry no disambiguator on any of the four routes.**
  `objectEntries`: symbol `$owner.$name`, export `${prefix}_${toCName(name)}`
  (`ForwardCallablePlanner.kt:801-818`). `companionEntries`: symbol `$owner.Companion.$name`,
  export `${prefix}_companion_${toCName(name)}` (`:821-839`). `topLevelEntry`: symbol
  `$package.$name`, export bare `toCName(name)` (`:792-799`). `extensionEntry`: symbol
  `$package.$name` — **receiver-agnostic** — export
  `${receiverSimpleName.lowercase()}_${toCName(name)}` (`:1032-1075`).
- Because the extension symbol omits the receiver, two same-name extensions on *different*
  receivers in one package (`fun Cat.pat()` + `fun Dog.pat()`) also produce duplicate symbols and
  crash, even though their C exports would not collide. The crash is not limited to same-receiver
  pairs.
- **The Kotlin call-site half is already suffix-ready.** `invocationExpression` reads
  `plan.invocation.member ?: symbol.substringAfterLast('.')` for *every* origin, including
  EXTENSION / TOP_LEVEL / OBJECT / COMPANION (`ForwardKotlinPlanEmitter.kt:800-813`). Only the
  planner needs to pass `member = name`; `staticEntry` and `topLevelNullablePrimitivePlan` do not
  take a `member` parameter today (`ForwardCallablePlanner.kt:842-893, 901-1030`).
- **Every emitter on these routes does a per-declaration `planFor(unsuffixed symbol)` lookup**:
  Kotlin half at `ObjectExports.kt:27`, `ClassExports.kt:431` (companion), `NugetProcessor.kt:701`
  (top-level, with an `addFunctionExports` fallback for unplanned functions),
  `ExtensionFunctionExports.kt:19`; C# half at `CirClassTranslator.kt:805` (companion), `:1175`
  (object), `CirTranslator.kt:156` (top-level, grouped by namespace + file class via
  `groupByNamespaceAndFile`), `:333` (extension, grouped by receiver simple name into
  `{Receiver}Extensions`). With planner-side numbering and no emitter change, the n-th namesake's
  plan (keyed `..._n`) is never found and the unsuffixed lookup silently binds every declaration
  to the *first* overload's plan — a silent-wrong-output shape, worse than today's crash.
- **The private extern names derive from the unsuffixed public name** in
  `ForwardCirPlanProjection.static` (`Native_$identifier` / `Native_Companion_$identifier`,
  `:195-199`), `ForwardCirPlanProjection.extension` (`Native_${plan.publicSignature.name}`,
  `:432`), and `staticLegacyTwoCall` (`${csName}_has_value` / `${csName}_value`, `:281-282`).
  Two overloads sharing a wire shape would declare one extern twice (CS0111), the exact bug
  ADR-090's amendment fixed for class methods. Unlike that case there is no hidden third site
  here: `CirErrorRenderer.renderSyncErrorCheckMethod` uses `method.nativeName` for static
  methods (`CirErrorRenderer.kt:157`), and both `static` and `extension` build their own
  `CirDllImport` and `CirMethod` from one local `nativeName`, so numbering that one local per
  projection fixes declaration, body, and error-check rendering together.
- **No `ERROR_CSHARP_SIGNATURE_COLLISION` check covers any of the four routes.** The only emit
  sites are the duplicate-constructor check (`CirClassTranslator.kt:103`), the ADR-090
  class-method check (`:507` / `:1694` region), and ADR-040's type-name collision
  (`CirTranslator.kt:280`).

## Alternatives Considered

### 1. ADR-090's template per route, with route-appropriate scopes and lookups (chosen)

Planner-side occurrence numbering (`_$n` from 2 on symbol and export name, bare `member`
carried on the plan), C#-side extern names derived from the numbered symbol tail, and the
ADR-034 signature-collision check per generated C# container. Emission goes catalog-driven where
the emitters' walk can be replaced by an owner-keyed accessor (object, companion), and
node-identity lookup where the emitter's grouping needs the declaration itself (top-level's
namespace + file-class grouping, extension's receiver grouping, top-level's unplanned-function
fallback). Pros: exact shipped precedent (ADR-034 → ADR-082 → ADR-090); C# surface is one
natural overload set; numbering stays in one place. Cons: two lookup styles instead of one.

### 2. Emitter-side occurrence counters (lockstep re-derivation)

Keep every per-declaration `planFor` loop and maintain a matching counter in each emitter.
Rejected: this is ADR-090's rejected alternative 2, and the drift risk is real here too — the
object C# loop does not apply the planner's `parentDeclaration == obj` filter
(`CirClassTranslator.kt:1170-1183` vs `ForwardCallablePlanner.kt:806`), so an inherited namesake
would desynchronize the counters.

### 3. Receiver-qualified extension symbols (`$package.$Receiver.$name`)

Would let same-name different-receiver extensions keep unsuffixed exports (`cat_pat` /
`dog_pat`). Rejected for v1: it changes the symbol shape consumed at two emitter sites and by
diagnostics, and raises its own questions (nullable receivers, receivers whose simple names
collide across packages, which already share one export prefix today). Package-scoped numbering
solves the crash with the same rule as top-level; an occasionally "unnecessary" `_2` on a C
export is invisible (the C ABI is not a public surface, ADR-054 contract hash).

### 4. Signature mangling / skip-after-first

Rejected for the same reasons as in ADR-082 and ADR-090.

## Decision

All four routes adopt ADR-090's numbering: the first declared overload keeps the bare name, the
n-th further one is suffixed `_$n` (n from 2) on both the symbol and the export name; the C#
public name never carries the number.

### Numbering scopes

| Route | Counter scope | Symbol (n-th) | Export name (n-th) |
|---|---|---|---|
| Object member | per object | `$owner.${name}_$n` | `${prefix}_${toCName(name)}_$n` |
| Companion member | per companion | `$owner.Companion.${name}_$n` | `${prefix}_companion_${toCName(name)}_$n` |
| Top-level function | per (package, name) | `$package.${name}_$n` | `${toCName(name)}_$n` |
| Extension function | per (package, name), receiver-agnostic | `$package.${name}_$n` | `${receiverPrefix}_${toCName(name)}_$n` |

- The suffix composes **after** `toCName`: `"${toCName(name)}_$n"`. `toCName` only appends a
  trailing `_` for C reserved words (verified, `Reserved.kt:25-28`), and `name_2` is never a C
  reserved word, so escaping is preserved. The contrived pair `default` / `default_` can both
  map an occurrence to `default__2`; the `ForwardAbiContract` duplicate-export guard
  (`ForwardAbiContract.kt:79-80`) already fails that loudly and stays as the backstop.
- Counting follows ADR-090's rule: over the declared plannable candidates, in collection order,
  after the route's existing filters; a *structurally* skipped namesake (SUSPEND / GENERIC /
  two-call ineligibility) still consumes its number, because `staticEntry`'s structural check
  runs after the counter increments in the caller — same declaration-order determinism as
  ADR-034 constructors. For object members the filters are visibility +
  `parentDeclaration == obj` + name exclusions (an inherited namesake never consumes a number);
  for companions, visibility + name exclusions; for top-level and extensions, the order is the
  `functions` / `extensionFunctions` lists as NugetProcessor collects and passes them to
  `catalog()` — the same single list both emitters iterate, so planner and emitters agree within
  a build by construction. (Cross-build stability beyond source order is not required: shim and
  native library ship from one build, ADR-054.)
- Extension numbering is receiver-agnostic because the symbol key space is
  (**verified**, `ForwardCallablePlanner.kt:1038`): `fun Cat.pat()` then `fun Dog.pat()` in one
  package number as `pkg.pat` / `pkg.pat_2` and export as `cat_pat` / `dog_pat_2`. The suffix on
  `dog_pat_2` is redundant at the C level but harmless, and it is what keeps one counter, one
  rule.
- `member = name` rides the plan for all four routes: `staticEntry` and
  `topLevelNullablePrimitivePlan` gain a `member` parameter passed through to the
  `ForwardInvocation` (the two-call path also uses `invocationExpression`, verified
  `ForwardKotlinPlanEmitter.kt:251`). No emitter change is needed for the Kotlin call-site name
  (verified, `:800-813`).

### Emission: two lookup styles, by what the emitter needs

- **Object and companion members go catalog-driven**, mirroring ADR-090's `classMethods(owner)`:
  new accessors `objectMethods(owner)` (origin OBJECT, owner-exact on
  `symbol.substringBeforeLast('.')`) and `companionMethods(owner)` (origin COMPANION, owner-exact
  on the `$owner.Companion` prefix segment). Consumed by `ObjectExports` and `ClassExports`'s
  companion loop on the Kotlin half, and by `CirClassTranslator`'s object (`:1175`) and companion
  (`:805`) loops on the C# half. Owner-exact matching, not prefix matching, for the same reason
  ADR-090 required it.
- **Top-level and extension functions keep their declaration walks with a node-identity lookup.**
  The C# emitters' grouping needs the declaration (namespace + file class for top-level,
  receiver simple name for extensions), and the Kotlin top-level loop needs its per-declaration
  `addFunctionExports` fallback for unplanned functions, so an owner-keyed accessor cannot
  replace either walk. Instead `ForwardCallableCatalogEntry.Planned` gains the `node: KSNode?`
  its `Skipped` sibling already has (verified, `ForwardCallablePlanner.kt:99-119`), and a new
  `planFor(declaration: KSFunctionDeclaration)` matches by reference identity. This is sound
  because — unlike the class-member case ADR-090 declined to node-match, where each side
  re-derives members through separate `getAllFunctions()` calls — both emitters here iterate the
  *same list instances* NugetProcessor collected and passed to `catalog()`. **Inferred, must be
  confirmed at implementation time:** that the `functions` / `extensionFunctions` lists reaching
  `CirTranslator` are the same objects passed to the planner (the flow through
  `NugetProcessor.kt` strongly suggests one collection point, but the full plumbing was not
  traced line by line). The lookup must `require` a hit-or-known-skip rather than silently
  falling through, so if the identity assumption is ever wrong the build fails loudly instead of
  binding namesakes to the wrong plan.
- **Extern names derive from the numbered symbol tail**, exactly ADR-090's amendment, applied at
  the three verified sites: `ForwardCirPlanProjection.static` (`Native_$tail` /
  `Native_Companion_$tail`), `ForwardCirPlanProjection.extension` (`Native_$tail`), and
  `staticLegacyTwoCall` (`${tail}_has_value` / `${tail}_value`). Each site builds its
  `CirDllImport` and `CirMethod` from one local name, and `renderSyncErrorCheckMethod` reads
  `method.nativeName` for static methods (verified, `CirErrorRenderer.kt:157`), so there is no
  ADR-090-style third site; unsuffixed members render byte-identically to today.
- **The ADR-034 `ERROR_CSHARP_SIGNATURE_COLLISION` check extends per generated C# container**
  (C# cannot overload on reference nullability alone): the object's generated class, the owning
  class's statics for companions, the (namespace, file class) static class for top-level, and
  the `{Receiver}Extensions` class for extensions — with the receiver type included as the
  first parameter of the comparison key, since C# extension overloads are distinguished by it.
  Companion statics should be checked *together with* the owning class's planned instance
  methods: C# rejects two members differing only in static-ness (**inferred** from the C#
  language rule that a type cannot declare two methods with the same name and parameter types
  regardless of `static`; not spiked).

### Consumer surface

```kotlin
// Kotlin (test-library fixture shapes)
object Clinic {
  fun open(): String = "9-5"
  fun open(day: String): String = "$day: 9-5"
}
class Cat(val name: String) {
  companion object {
    fun of(name: String): Cat = Cat(name)
    fun of(name: String, lives: Int): Cat = Cat(name)
  }
}
fun greet(name: String): String = "Hello, $name"
fun greet(name: String, excited: Boolean): String = /* ... */
fun Cat.pat(): String = "purr"
fun Cat.pat(times: Int): String = "purr".repeat(times)
```

```csharp
// C#: natural overload sets, no visible numbering
Clinic.Open();                 // -> clinic_open
Clinic.Open("Monday");         // -> clinic_open_2
Cat.Of("Momo");                // -> cat_companion_of
Cat.Of("Momo", 9);             // -> cat_companion_of_2
ClinicSample.greet("Momo");          // -> greet   (top-level names stay camelCase; see below)
ClinicSample.greet("Momo", true);    // -> greet_2
cat.Pat();                     // -> cat_pat  ({Receiver}Extensions static, existing shape)
cat.Pat(3);                    // -> cat_pat_2
```

The file's static class is `ClinicSample`, not `ClinicSampleKt`: `resolveStaticClassName` only
appends `Kt` when the file class name collides with a class / sealed class / interface backing
class of the same name in that namespace (verified, `CirTranslator.kt:123-145`; the sketch said
`ClinicSampleKt` and was wrong).

Top-level public names keep today's casing (`toCName(name).csharpIdentifier()`, verified
`ForwardCallablePlanner.kt:795`); the ROADMAP's separate camelCase item is deliberately not
touched here — numbering must compose with whatever that item later decides, which it does,
since the public name never carries the number.

### Amendment (implementation, 2026-08-14)

Everything above landed as designed. Four corrections, all verified against the real pipeline:

- **The extern name keeps its shipped base and gains only the number.** The ADR said the extern
  derives "from the numbered symbol tail". Taken literally that also changes *unnumbered* members:
  the tail is the Kotlin name (`describe`), while the shipped extern is built from the C# public
  name (`Native_Describe`), and for top-level from the camelCase one (`Native_bookGrooming`). The
  implementation instead appends the suffix the symbol tail carries
  (`ForwardCirPlanProjection.overloadSuffix()`, tail minus `invocation.member`), so
  `Native_Describe` / `Native_Describe_2`, `Native_Companion_Of_3`, `waitTime_2_has_value`, and
  every unsuffixed member renders byte-identically to before. Same three sites as named.
- **The list-identity assumption the ADR flagged as inferred is verified.** `NugetProcessor`
  collects `functions` and `extensionFunctions` once (`NugetProcessor.kt:310-321`) and passes those
  same instances to `forwardPlanner.catalog(...)`, `generateCNameWrappers(...)` and `translate(...)`,
  so `planFor(declaration)` matches by reference. It `require`s a hit (planned or skipped) instead
  of returning null on a miss, so a future second collection point fails loudly.
- **"C# rejects two members differing only in static-ness" is verified, not inferred.** A scratch
  net8.0 build of `public string Tag(string)` next to `public static string Tag(string)` on one
  class fails with `CS0111: Type 'Groomer' already defines a member called 'Tag' with the same
  parameter types`. Companion statics are therefore checked together with the owning class's
  planned instance methods, as the ADR proposed.
- **Going catalog-driven removed a latent double-emit on the object route.** The C# object loop
  looked up `$owner.$name` per `getAllFunctions()` entry *without* the planner's
  `parentDeclaration == obj` filter, so an inherited namesake of a declared member found the
  declared member's plan and projected it a second time. Catalog iteration cannot express that.

The ADR-034 collision check is now one shared helper, `emitCsharpSignatureCollisions`
(`CirClassTranslator.kt`), called for the class (instance + companion), the object, the
(namespace, file class) top-level static class, and each `{Receiver}Extensions`. The value-class
check keeps its own copy (out of scope here).

## Consequences

- Same-name overloads on all four routes generate instead of crashing `packNuget`; each surfaces
  as one natural C# overload set.
- The different-receiver same-name extension crash (never listed on the roadmap by name) is
  fixed by the same numbering, at the cost of an occasionally redundant `_n` on a C export.
- `planFor(symbol)`'s duplicate-plans invariant stays meaningful (ADR-074): a fresh firing again
  indicates a genuinely new duplicate-symbol source.
- Reference-nullability-only namesake pairs fail generation with
  `ERROR_CSHARP_SIGNATURE_COLLISION` instead of emitting CS0111/CS0663.
- Numbering is declaration/collection-order dependent, accepted as in ADR-034/090 (one-build
  shim+native pairing, ADR-054).
- Pre-existing, out of scope, unchanged by this ADR: two same-name top-level functions in
  *different* packages both export bare `toCName(name)` and trip the `ForwardAbiContract`
  duplicate-export guard; two extension receivers with the same lowercase simple name in
  different packages share an export prefix; `{Receiver}Extensions` groups by receiver *simple*
  name. Each fails loudly at generation today and keeps doing so. The same family bit this ADR's
  own fixture (**verified** while implementing): two exported *classes* with the same simple name
  in different packages both export `${simpleName.lowercase()}_create`
  (`ForwardCallablePlanner.constructorEntries`), so a second `Kitten` failed the build with
  `Forward ABI duplicate C# import for kitten_create`. The fixture receiver was renamed `Mitten`;
  qualifying export prefixes by package is its own item.
- Deferred with their existing routes: namesakes that are suspend, generic, Flow-returning or
  otherwise non-planned keep their current handling; the counter numbers around them
  deterministically.
- The default-parameter roadmap item (`@JvmOverloads`-style omitting overloads for these routes)
  gains the numbering space it needs: synthesized entries would continue each scope's `_$n`
  sequence, as ADR-091 did for constructors.
