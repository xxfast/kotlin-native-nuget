# ADR-091: Constructor default parameters: JvmOverloads-style omitting overloads, planner-synthesized

## Status

Proposed

## Context

A Kotlin constructor default parameter is invisible in C# (ROADMAP line 71): `Cat(name: String,
lives: Int = 9)` generates a single two-argument constructor, so `new Cat("Mouse")` is CS7036 and
every C# call site must pass `lives`. Verified by a real C# compile failure recorded on the
ROADMAP item (found while writing the ADR-071 fixture).

Constraints, in force order:

- **KSP cannot read the default value.** `KSValueParameter` exposes exactly one bit,
  `hasDefault: Boolean`; there is no expression or constant-value API on it. **Verified** against
  the sources of the pinned KSP version (2.3.10, `gradle/libs.versions.toml:3`):
  `com.google.devtools.ksp.symbol.KSValueParameter` declares `name`, `type`, `isVararg`,
  `isNoInline`, `isCrossInline`, `isVal`, `isVar`, `hasDefault` and nothing else (read from
  `symbol-processing-api-2.3.10-sources.jar`). Any design that copies the default into C#
  (`int lives = 9`) is therefore impossible, independent of the fact that a Kotlin default may be
  an arbitrary non-constant expression anyway.
- **Kotlin computes the default when the argument is omitted at the call site.** A generated
  wrapper that calls `Cat(name)` gets `lives = 9` from the Kotlin compiler; this is core language
  behaviour, not an interop feature (Verified trivially; it is how every Kotlin call site works).
- **The export pipeline already supports a plan whose parameter list is shorter than the
  constructor's.** The Kotlin emitter derives the call solely from the plan:
  `arguments` joins `plan.publicSignature.parameters` (`ForwardKotlinPlanEmitter.kt:55,248,343`)
  and the CONSTRUCTOR branch of `invocationExpression` emits `Target($arguments)`
  (`ForwardKotlinPlanEmitter.kt:815`). Nothing consults the `KSFunctionDeclaration`'s own
  parameter list at emission time. **Verified in source.** So a synthesized plan carrying only
  `name` emits `pkg.Cat(name)`, which compiles and uses the default.
- **`expect`/`actual` pairs lose the bit on the export root.** ADR-074 exports the `actual`, and
  Kotlin forbids an `actual` from declaring defaults, so every parameter of the exported
  declaration reports `hasDefault = false`; the bit lives only on the `expect`. **Verified** by
  ADR-074's recorded spike (`expect fun greet(who: String = "...")` reports `hasDefault=true` on
  the expect, `false` on the actual) and named as a prerequisite on ROADMAP line 71. The
  `expectsByName: Map<String, KSDeclaration>` index exists for exactly this class of lookup
  (`NugetProcessor.kt:284`, kept "because the `actual` is structurally complete but
  metadata-poor (no KDoc, no annotations, no parameter defaults)").

Prior art:

- **`@JvmOverloads` (JVM interop)**: the compiler synthesizes one additional overload per
  defaulted parameter, "which has this parameter and all parameters to the right of it in the
  parameter list removed"; for `Circle(centerX, centerY, radius = 1.0)` it generates
  `Circle(int, int, double)` and `Circle(int, int)`
  (https://kotlinlang.org/docs/java-to-kotlin-interop.html#overloads-generation). Inferred from
  documentation. This is Kotlin's own answer to "surface defaults to a language without them
  whose overloads are resolved positionally", and C# overload resolution is the same shape.
- **ObjC export**: default arguments are not surfaced; the documented mapping tables say nothing
  about them and callers pass every argument (inferred from
  https://kotlinlang.org/docs/native-objc-interop.html, which never mentions defaults; the
  Kotlin-Swift interopedia and SKIE both treat this as a known gap, and SKIE's "Default
  Arguments" feature fills it by generating overloads, opt-in because the full combinatorial set
  is exponential: https://skie.touchlab.co/features/default-arguments). So the strongest external
  precedent that *does* solve it (SKIE) also solves it with overload synthesis.

Repo precedent this must compose with:

- ADR-034: secondary constructors export as `${prefix}_create_${n}`, n from 2, declaration order
  (`ForwardCallablePlanner.constructorEntries`, `ForwardCallablePlanner.kt:654-697`; Verified).
- ADR-090: same-name members are numbered in the planner and both emitters read the catalog
  instead of re-deriving plan keys per declaration (`classMethods(owner)` accessor,
  `ForwardCallablePlanner.kt:263`). Constructors still use per-declaration `planFor` loops on
  both halves (`ClassExports.kt:57-73`, `CirClassTranslator.kt:69-96`; Verified), which cannot
  see synthesized entries.
- ADR-034's constructor collision check: all rendered constructors are compared as C# signatures
  (reference nullability stripped, value nullability kept) and any duplicate emits
  `ERROR_CSHARP_SIGNATURE_COLLISION` instead of CS0111 (`CirClassTranslator.kt:104-126`;
  Verified).

## Alternatives Considered

### 1. Synthesized trailing-omitting constructor overloads, numbered in the planner (chosen)

For each exported constructor, synthesize one extra export per omitted trailing-defaulted suffix;
the generated Kotlin wrapper calls the constructor with fewer positional arguments and Kotlin
computes the defaults. The C# surface is a natural constructor overload set, exactly what
`@JvmOverloads` gives Java. Pros: the only design compatible with `hasDefault` being a bare bit;
defaults are evaluated by Kotlin at call time, so non-constant defaults
(`lives: Int = computeLives()`) and default-value changes need no special handling; reuses the
shipped ADR-034 numbering and the shipped plan machinery unchanged on the Kotlin-emitter side.
Cons: d defaults produce d extra exports per constructor (linear, not the 2^n set); a middle
default gets nothing.

### 2. C# optional parameters (`int lives = 9`)

The ideal C# surface in isolation. Rejected as impossible: C# optional-parameter defaults must be
compile-time constants baked into the generated source, and KSP exposes no way to read the
default expression or its value (Verified above, `KSValueParameter` has only `hasDefault`). Even
with a future KSP API, only constant defaults could ever be represented, and the constant would
be duplicated into C# rather than evaluated by Kotlin.

### 3. Full combinatorial overload set (every subset of defaulted parameters)

What SKIE does for Swift. Rejected: exponential surface (SKIE gates it behind opt-in for exactly
this reason), and C# cannot disambiguate two overloads that omit *different* same-typed
parameters, so most of the extra set is uncallable or collision-prone. `@JvmOverloads`' trailing
rule is the established Kotlin answer.

### 4. Do nothing, document "pass every argument"

The ObjC-export status quo. Rejected: it is the current broken state the ROADMAP item exists to
fix, and `new Cat("Mouse")` failing is a first-contact papercut in the plugin's own samples.

## Decision

Adopt the `@JvmOverloads` rule for exported constructors: for a constructor with parameters
`p1..pn`, every maximal trailing run of defaulted parameters produces one omitting overload per
suffix length. Formally: for each k in 1..d, where d is the number of trailing parameters that
all have `hasDefault = true`, synthesize an export taking `p1..p(n-k)`. A defaulted parameter
with a non-defaulted parameter anywhere after it produces no overload (the wrapper is a plain
positional Kotlin call, which cannot skip a middle argument; this is also `@JvmOverloads`'
observable behaviour, inferred from its documented examples). The full-signature constructor is
always emitted, unchanged.

### Defaults source (expect/actual)

`constructorEntries` computes per-parameter "has a default" as:

1. the parameter's own `hasDefault` (ordinary classes; Verified this is where the bit lives), else
2. if `expectsByName[owner]` resolves to a `KSClassDeclaration`, the positionally matching
   parameter of that expect class's **primary constructor**, consulted only when the constructor
   being planned is the actual's primary constructor.

`ForwardCallablePlanner` therefore gains an `expectsByName: Map<String, KSDeclaration>` parameter
(default `emptyMap()`), passed at its single construction site (`NugetProcessor.kt:491`, same
scope as the index at `:284`; Verified both are in scope). Secondary constructors of an
`expect`/`actual` class get no synthesized overloads in v1: matching an actual secondary
constructor to its expect counterpart needs a signature-matching rule across two declarations
whose types resolve in different files, and no rule has been verified; conservatively reporting
"no defaults" produces valid, merely un-improved output. Inferred (not verified): parameter
*count and order* of an expect primary constructor always match the actual's, because Kotlin
requires actualization to match the expect signature; positional lookup relies on this.

### Numbering (extends ADR-034's sequence; assigned once, in the planner)

`constructorEntries` assigns numbers in one pass, in this deterministic order: primary (full),
secondaries (full, declaration order, numbers 2..s+1 as today), then synthesized omitting
overloads: primary's suffixes k = 1..d first, then each secondary's in declaration order.
Synthesized entries continue the same `_$n` sequence:

| entry                              | export name             | plan symbol          | C# surface        |
|------------------------------------|-------------------------|----------------------|-------------------|
| primary, full                      | `${prefix}_create`      | `$owner.<init>`      | `Cat(string, int)` |
| secondary m (m ≥ 2), full          | `${prefix}_create_$m`   | `$owner.<init>_$m`   | overload           |
| synthesized (next free n, n > s+1) | `${prefix}_create_$n`   | `$owner.<init>_$n`   | overload           |

Unsuffixed and secondary entries render byte-identically to today; only new numbers are appended.
Numbering is declaration-order dependent, accepted for the same reason as ADR-034/ADR-090: the C
ABI is not a public surface, and shim + native library always ship from one build (ADR-054
contract hash).

Each synthesized entry goes through the existing `planOrSkip` with `origin = CONSTRUCTOR`,
`target = owner`, and the truncated parameter list. Consequence of routing through `planOrSkip`
independently: a constructor whose *trailing defaulted* parameter has an unsupported type (e.g. a
`Map` input) still gets its omitting overloads planned even though the full signature is skipped,
which softens the "dead public type" ROADMAP item (line 61) for exactly that shape.

### Kotlin half

**No emitter changes for the wrapper body.** The synthesized plan's truncated parameters flow
through the existing `addForwardKotlinPlanExport` path: `arguments` is built from
`plan.publicSignature.parameters` only and the CONSTRUCTOR invocation is `Target($arguments)`
(Verified, `ForwardKotlinPlanEmitter.kt:55,248,343,815`), producing e.g.

```kotlin
@CName("cat_create_2")
fun export_cat_create_2(name: CPointer<ByteVar>, error: ...): COpaquePointer =
  ... StableRef.create(pkg.Cat(name.toKString())) ... // lives omitted; Kotlin supplies 9
```

**`ClassExports` goes catalog-driven for constructors.** The two per-declaration loops
(`planFor("$qualifiedName.<init>")` and the indexed secondary loop, `ClassExports.kt:57-73`;
Verified) become one iteration over a new accessor,
`ForwardCallablePlanCatalog.constructors(owner)`: CONSTRUCTOR-origin plans whose
`symbol.substringBeforeLast('.') == owner`, in planning order, mirroring `classMethods(owner)`
(`ForwardCallablePlanner.kt:263`). Owner-exact matching, for ADR-090's reason. This is the
ADR-090 template applied to constructors; occurrence-aware re-derivation in the emitters was
rejected there and is rejected here identically.

### C# half

**`CirClassTranslator` reads the same accessor.** The primary/secondary `planFor` loops
(`CirClassTranslator.kt:69-96`; Verified) become one iteration over `constructors(owner)`. The
projection already parameterizes the extern name via `nativeSuffix`
(`ForwardCirPlanProjection.constructor(plan, nativeSuffix)` builds `Native_Create$nativeSuffix`;
Verified, `ForwardCirPlanProjection.kt:142-180`), so the suffix is derived from the plan symbol's
tail after `<init>` ("" or `_$n`), the same derivation ADR-090's amendment used for
`CirMethod.externName`. C# constructors all share the class name, so the surface is one natural
overload set with no visible numbering.

**The ADR-034 collision check covers synthesized entries for free, and must.** The check compares
*all* rendered constructors' C# parameter-type lists (`CirClassTranslator.kt:104-126`; Verified),
so once synthesized entries join the `CirConstructor` list, `Cat(name: String, lives: Int = 9)`
next to a real `constructor(name: String)` renders two `Cat(string)` signatures and fails
generation with `ERROR_CSHARP_SIGNATURE_COLLISION`, not CS0111. The diagnostic's `hint` is
extended to name the defaulted-parameter cause ("...or remove the default value whose synthesized
overload collides"). Same for two synthesized overloads colliding across constructors (e.g.
`Cat(a: Int, b: Int = 1)` and `constructor(x: Int, s: String = "")` both yielding `Cat(int)`).

### Consumer surface

```kotlin
// Kotlin
class Cat(val name: String, val lives: Int = 9)
class Kennel(val name: String, val capacity: Int = 10, val city: String) // middle default
```

```csharp
// C#
var cat = new Cat("Mouse");          // -> cat_create_2 (omitting overload); cat.Lives == 9
var cat2 = new Cat("Momo", 7);       // -> cat_create (full signature, unchanged)

new Kennel("Paws", 12, "Colombo");   // full signature only:
                                     // `capacity` has a required parameter after it,
                                     // so no omitting overload exists (JvmOverloads rule)
```

### Data-class `copy()`

Untouched. The COPY-origin plan passes every primary-constructor parameter and its Kotlin
defaults are "the receiver's current values", not the constructor defaults, so trailing-omitting
overloads would model the wrong thing; a partial copy is the 2^n named-argument case rejected as
Alternative 3. The full-argument `Copy(...)` stays as shipped.

## Consequences

- `new Cat("Mouse")` compiles and yields `Lives == 9`; every existing full-signature call site is
  unchanged, and unsuffixed/secondary exports render byte-identically to today.
- d trailing defaults add d native exports per constructor (linear). Middle defaults add nothing
  and this is documented behaviour, matching `@JvmOverloads`.
- Constructor emission becomes catalog-driven on both halves (ADR-090's template), retiring two
  per-declaration `planFor` loops; the `planFor` duplicate-plans invariant keeps its ADR-074
  meaning.
- A synthesized overload colliding with a real constructor (or another synthesized one) fails
  generation with the named ADR-034 diagnostic instead of emitting CS0111.
- `expect` class primary-constructor defaults surface via `expectsByName`; without the lookup the
  feature would silently and "correctly" conclude no expect class has defaults (the ROADMAP-named
  trap). Expect-class *secondary* constructors get no overloads in v1 (recorded above).
- Out of scope, recorded for the roadmap: function/method default parameters (top-level, class
  methods, object/companion members, extensions) use the same mechanism but land in ADR-090's
  member numbering spaces, a separate ROADMAP item; value-class constructor defaults
  (`valueClassConstructorEntries` is a separate numbering space, ADR-035); `vararg` parameters
  (report `hasDefault = false`; unchanged); partial `Copy(...)` overloads.
- Inferred claims an implementer may hit: the `@JvmOverloads` middle-default behaviour is taken
  from documented examples, not a decompiled class file (it does not bind this design, which is
  constrained by positional Kotlin calls regardless); expect/actual primary-constructor
  positional parameter matching is guaranteed by the language's actualization rules but was not
  spiked. Everything labelled Verified above was read in this repository's source or the pinned
  KSP 2.3.10 sources.
