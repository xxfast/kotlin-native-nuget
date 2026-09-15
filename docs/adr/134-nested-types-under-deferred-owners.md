# ADR-134: Nested types under ADR-133's deferred owners: sealed base, sealed arm and interface owners, and a nested `value class`; `enum class`, generic and `inner class` owners stay a named skip

## Status

Accepted (2026-09-13)

## Context

[ADR-133](133-nested-types.md) declares a public nested `class`/`object`/`interface`/`enum class` as a
C# nested type under a non-generic, non-`inner` `class` or `object` owner, and keeps
`SKIPPED_NESTED_DECLARATION` (reason naming the shape) for: an `inner class` owner, a generic owner,
an `enum class` owner, an `interface` owner, a sealed base or arm owner, and a nested `value class`
candidate. ROADMAP Phase 4 asks which of these C# can express, and to decide the rest.

Constraints carried from ADR-133: one Kotlin type, exactly one C# declaration (CS0101 otherwise);
process-global C entry points (ADR-117); no released consumer-visible name changes; every deferred
shape stays a *named* skip.

## Alternatives Considered

### 1. Declare where C# can express it, decide a permanent named skip for the rest (chosen)

Sealed base, sealed arm, and `interface` owners declare their children; a nested `value class` is
declared as a nested `readonly record struct`. `enum class`, generic, and `inner class` owners keep
the named skip. Pros: follows Kotlin scope for every shape C# has a block for; no new export shape,
handle kind, or closure edge. Cons: the `I`-prefix rule ADR-133 fixed to the last segment must become
per-interface-segment, and three shapes remain skipped.

### 2. Hoist what C# cannot nest (`SeasonAlmanac`, `BoxLid`) to namespace level

Pros: every public Kotlin type gets *some* C# type. Cons: invents a name no Kotlin scope has, needs a
per-owner-kind fork in `nestedCsName()` at every type-position site, and a namespace-level collision
rule against a genuine top-level `SeasonAlmanac`/`BoxLid` (the ADR-117 lesson, in C# instead of C).
The enum-extension hoist (`AviaryKindExtensions`) is not a precedent: that class has no Kotlin
counterpart, so nobody looks it up by Kotlin name. Rejected for both enum and generic owners.

### 3. Support `inner class` with an outer-instance constructor parameter

`new Host.Guest(host, 3)` / `host_guest_create(outerHandle, visits)`, the JVM `host.new Guest(3)`
shape. Pros: complete. Cons: a new CONSTRUCTOR-plan shape (receiver-first wire parameter, Kotlin body
`outer.asStableRef<Host>().get().Guest(visits)`), its own leak row (the inner instance holds
`this@Host`), not a nesting question. Deferred to its own ADR; the skip reason already names it.

### 4. Nest an interface owner's children under the ADR-040 wrapper (`Cage.Bar`)

Pros: no `nestedCsName()` change. Cons: the wrapper exists only when ADR-040's reachability rule
triggers, so `Cage.Bar` dangles for every interface without a return position. Rejected.

## Decision

**Owner kinds admitted.** A sealed base (`sealed class` or an ADR-112 *eligible* `sealed interface`,
both rendered `public abstract class`), a sealed arm (nested or ADR-125 sibling), and a non-generic
`interface`, including an ADR-112 *ineligible* `sealed interface` (still rendered `public interface`,
since ineligibility is a supertype-shape question, orthogonal to nesting). A nested `value class`
candidate is declared under any admitted owner.

`unsupportedNestedOwnerReason()` (`NugetProcessor.kt`) drops its `INTERFACE` arm and its
`sealed base or sealed arm` arm entirely, and widens the "only a `class` or `object` owner" arm to
also admit `INTERFACE`. `unsupportedNestedCandidateReason()` drops its `isValueClass()` arm. The
`typeParameters.isNotEmpty()` owner arm is untouched by either change: it is what keeps a variant
type parameter's scope free of nested types (C# spec §19.4.9), and `CirInterface` does carry
`typeParameters`, so a generic interface owner still defers correctly through the same arm a generic
class owner always used.

**Owner kinds that stay a named skip, permanently:** an `enum class` owner (**Verified**, C# spec
§20.2/§20.4: `enum_body : '{' enum_member_declarations? '}'`, an enum body holds only named
constants), a generic owner (**Verified**, §15.3.9.7: "Every type declaration contained within a
generic class declaration is implicitly a generic type declaration ... the containing constructed
type, including its type arguments, shall be named", so `Box<T>.Lid` would be one C# type per `T`
where Kotlin has one), an `inner class` owner or candidate (C# has no inner classes; Alternative 3
records the design), a `value class` owner (no nested-type slot), a nested sealed hierarchy
*candidate* (a sealed type declared inside some other admitted owner, as opposed to the sealed
owner's own children: "its arms would have to nest twice"), and a companion (ADR-013, unchanged).
Reasons are otherwise unchanged from ADR-133.

**Spelling (the one ADR-133 reversal).** `nestedCsName()` prefixes `I` on **every enclosing interface
segment** of the chain, not the last only: `interface Cage { class Bar }` is `global::NS.ICage.Bar`.
The rule is split across two sites that together spell every segment: `nestedCsName()` prefixes each
*enclosing* interface segment (`index < chain.lastIndex && classKind == INTERFACE`), and
`nestedInterfaceCsName()` still prefixes the *last* segment when the interface itself is the type
being named (unchanged from ADR-133). An **eligible** sealed interface segment is exempt from the
enclosing-segment rule (`!declaration.isEligibleSealedInterface()`): it renders as `public abstract
class Beam`, so `IBeam` exists nowhere in the assembly (the issue #54 rule), and `Beam.Lens` is
spelled with no `I` anywhere in its chain.

**C# capability, cited.**
- Interface nested types: **Verified** (spec §19.4.1) `interface_member_declaration` includes
  `type_declaration`.
- Nested struct in a class/interface: **Verified** (§15.3.9.1) "A type declared within a class,
  struct, or interface is called a nested type."
- That a nested class carrying `[DllImport] private static extern` members compiles inside a
  `public interface` block under `TreatWarningsAsErrors`: **Verified**. The shipped
  `ICage`/`Bar` fixture cell compiles clean under `GeneratedBindingsCheck`, so this is no longer an
  inference the ADR-133 draft carried forward unresolved.
- Language floor: no new one. The generated code already targets `LangVersion 12.0`
  (`GeneratedBindingsCheck`); nested types in interfaces are a C# 8 feature (**Inferred** from the
  C# 8 default-interface-members feature, not independently re-tested on 7.3).

**Gate order (silent-loss hazard).** `unsupportedNestedOwnerReason()` tests `classKind == INTERFACE`
before it would test sealed, and an **eligible** sealed interface is collected into `rootSealedClasses`
and rendered by the sealed route, not the interface route. The relaxed interface arm must not also
claim an eligible sealed interface as an interface owner: if it did, the interface's children would be
partitioned into a `CirInterface` slot the sealed renderer never reads and vanish with no diagnostic
at all, not even CS0101. This did not need a code change beyond the classification `nestedCsName()`
already had to make (`isEligibleSealedInterface()`); it is a hazard for the *next* change to this
area, not a defect shipped here, and it is now pinned by a Tier 1 cell (see Fixture and tests) rather
than left as a documentation-only warning.

Separately, and pre-existing since ADR-112 rather than introduced here: an **ineligible** sealed
interface still renders `public interface ITone`, and, since it is now an ordinary admitted interface
owner, a plain (non-arm) nested type declared inside it is now *declared* rather than
`SKIPPED_NESTED_DECLARATION`. An ineligible interface's own **arms** are unaffected: they keep the
single hierarchy-level `SKIPPED_INELIGIBLE_SEALED_INTERFACE` warning ADR-112 gives them, with no
second, separate nested-declaration warning, since the parent's warning already explains the whole
hierarchy.

### Bridge mechanism

Identical to ADR-133: a nested class under a sealed base/arm/interface owner mints a `StableRef` at
`${owner.nativePrefix()}_${name}_create` (`purr_detail_create`, `purr_on_trace_create`,
`cage_bar_create`, `beam_lens_create`), disposes at `_dispose`, and its members ride the ADR-062 plan
keyed by qualified name. `SealedClassExports.kt` and `translateInterface`
(`CirClassTranslator.kt`) already composed `nativePrefix()` for the base/arm and interface routes
respectively, so the mechanism needed no change for those owners; only the *declaration* half
(collection, translation, rendering) was missing. A nested value class crosses as its underlying
(ADR-014/077), declared as `Owner.Tag` (`readonly record struct`), exported at `owner_tag_*`
(`hamper_weight_create`, `hamper_weight_get_kilos`, `hamper_weight_isHeavy`): **Verified** by reading
the generated `Interop.cs` and `CNameExports.kt` directly, correctly indented inside `Hamper`'s block
and composing the full chain, not the bare simple name. No new handle kind, no new
`LeakTests/LiveHandleTests.cs` row.

### Code seams

- `NugetProcessor.kt`: `unsupportedNestedOwnerReason()`/`unsupportedNestedCandidateReason()` narrowed
  as described above; this is the entire admission change. `nestedCandidates` already flat-mapped over
  `declaredClasses + valueClasses + sealedClasses + declaredObjects + declaredInterfaces +
  declaredEnums` before this ADR (ADR-133 walked sealed and interface owners for candidates too, even
  though their own owner-gate skipped them); only the owner gate needed to move. `valueClasses` is
  renamed to `declaredValueClasses` to free the name for the merged list below. `allClasses` now excludes a nested `value class` from
  the ordinary CLASS bucket (`nestedDeclared.filter { classKind == CLASS && !isValueClass() }`), since
  a value class is `ClassKind.CLASS` in KSP too and would otherwise also render a handle class beside
  its own record struct. `valueClasses` becomes one merged list,
  `declaredValueClasses + nestedDeclared.filter { isValueClass() }`, so the KotlinPoet exporter, the
  plan catalog, and the CIR translator cannot disagree about which value classes exist.
- `CirModel.kt`: `nestedDeclarations: List<CirDeclaration> = emptyList()` added to `CirInterface`,
  `CirSealedClass`, and `CirSealedSubclass` (`CirClass`/`CirObject` already had it from ADR-133).
- `CirClassTranslator.kt` `translateSealedClass`: gains a **required** `nestedOf: (KSClassDeclaration)
  -> List<CirDeclaration>` parameter, supplied by `CirTranslator` as `::translateNestedOf`, called once
  for the base (`nestedOf(cls)`) and once per arm (`nestedOf(subclass)`). Required rather than defaulted
  deliberately: a default would let a future caller forget to pass it and silently declare no nested
  types for that caller, the one failure mode of this feature that emits neither a duplicate
  declaration nor a diagnostic.
- `CirClassRenderer.kt` `renderInterface`: calls `renderNestedDeclarations(iface.nestedDeclarations)`
  before the interface's closing brace (C# spec §19.4.1 admits a `type_declaration` there).
  `CirSealedRenderer.kt`'s base and arm blocks call the equivalent for their own
  `nestedDeclarations`.
- `CirRenderer.kt`: the ADR-133 `nestedEnumsOf(declaration)` (which matched only `CirClass`/`CirObject`)
  is rebuilt on top of a new `nestedDeclarationsOf(declaration)` that also descends into
  `CirInterface`/`CirSealedClass`/`CirSealedSubclass`, so a nested enum inside any of the newly
  admitted owners still reaches the namespace-level `OuterKindExtensions` hoist instead of being lost.
- `CirTypeMapping.kt` `nestedCsName()`: per-segment `I`, as described in Spelling above.
- `nestedOwnerScopeCollision()` (the CS0542/CS0102 guard) applies unchanged to the newly admitted
  owners; no fixture cell exercises it under a sealed or value-class owner (see Consequences).

## Fixture and tests

`test-library/.../nested/Deferred.kt`: `interface Cage { class Bar }` with `WireCage : Cage` as the
constructible implementation an interface itself cannot provide; `sealed class Purr` nesting `Detail`
beside its arms `On`/`Off`, with `On` itself nesting `Trace`; `sealed interface Beam` (eligible, ADR-112)
nesting `Lens`, with arms `Lit`/`Dark`; `class Hamper` nesting `value class Weight`. Top-level
`purringPurr`/`sleepingPurr`/`litBeam` reach the `data object` arm and the eligible-sealed-interface
arm from a return position, since ADR-009 gives every arm only an `internal` handle constructor.
Every accessor is named to dodge the owner-scope collision rule (`barAt`, `detailOf`, `traceOf`,
`weightOf`, never `bar`/`detail`/`trace`/`weight`), so a fixture bug can't masquerade as this
feature's own gate.

`IntegrationTests/NestedDeferredOwnersTests.cs`: one declaration-presence assertion and one round-trip
per owner kind, plus the interface's `I`-on-every-segment spelling assertion and a check that no
`IBeam` exists anywhere in the assembly. `Tier1NestedTypesTest.kt`: an `admittedSource` fixture
(`Cage`/`Signal`/`Pulse`/`Crate`) pins presence, export-prefix chaining, and "no namespace-level twin,
no skip warning" for all four owner kinds in one processor run; `deferredSource` narrows to exactly
`Box.Lid` (generic), `Season.Almanac` (enum class), and `Host.Guest` (inner class) staying named
skips, `Cage.Bar` removed from that list since it is no longer one. `Tier1SealedInterfaceTest.kt`
gains a cell flipping `Tone.Helper` (a plain nested class under an **ineligible** sealed interface
`Tone`) from a named skip to a declared `public class Helper` inside `public interface ITone`.

## Consequences

- Additive: `Purr.Detail`, `Purr.On.Trace`, `ICage.Bar`, `Beam.Lens`, `Hamper.Weight` (and, for an
  ineligible sealed interface, any plain nested helper it declares) now appear; no released top-level
  name or entry point changes (a top-level chain has one element, byte-identical per ADR-133).
- ADR-133's "last segment only" interface rule is superseded by "every enclosing segment, `I` on the
  last only when the interface itself is the referenced type." No released spelling changes:
  **Verified** by the gate, not by a fixture predating this ADR: `unsupportedNestedOwnerReason()`
  skipped every interface owner before this ADR, so no previously-declared type had an interface as a
  non-last chain segment to reclassify.
- `SKIPPED_NESTED_DECLARATION` survives for exactly: an `enum class` owner, a generic owner, an
  `inner class` owner or candidate, a `value class` owner, and a nested sealed hierarchy candidate.
  ROADMAP's Phase 4 line for the deferred-owner set closes; the `inner class` design (Alternative 3)
  becomes its own Phase 4 item.
- Not closed here, and not claimed to be: the CS0542/CS0102 owner-scope collision arms under a sealed
  or value-class owner have no fixture (this ADR's own `Deferred.kt` deliberately dodges every
  collision, the same way ADR-133's did); that ROADMAP line is unchanged by this ADR.
- A gap this ADR's own fixture surfaced, not fixed: `ForwardBridgeTypeClassifier`'s `valueClass()`
  branch has no membership/nested gate the way `interfaceType()` does, so a nested `value class`
  declared under a **still-deferred** owner (e.g. a hypothetical `Box<T>.Lid` were it a value class)
  would spell an undeclared struct name at a member position with no diagnostic at all, rather than
  the named `UNDECLARED_CLASS`/`UNDECLARED_ENUM`/`UNDECLARED_INTERFACE` skip every other undeclared
  nested kind gets. Recorded as a Phase 4 ROADMAP item.
- A pre-existing hazard, verified while building this fixture rather than introduced by it: every
  sealed arm ADR-009 has ever generated carries only `internal Arm(IntPtr handle)`, no public
  constructor. In .NET 7+, `IntPtr` is `nint`, and `int` converts to `nint` implicitly, so
  `new Purr.On(9)` compiles clean, binding to the internal handle constructor with `9` as a
  fabricated stable-ref address, and access-violates on the first call through it. This is the same
  root cause the existing ROADMAP backlog item for a missing-constructor-overload silent bind
  describes, now verified for a shape (no public constructor at all) that backlog entry had called
  benign; folded into that entry rather than duplicated (see ROADMAP).
- Cost: 8 source files touched (`NugetProcessor.kt`, `CirModel.kt`, `CirClassTranslator.kt`,
  `CirTranslator.kt`, `CirRenderer.kt`, `CirClassRenderer.kt`, `CirSealedRenderer.kt`,
  `CirTypeMapping.kt`), one new fixture file, one xunit file, two Tier 1 test files, this ADR,
  three `docs/topics` pages, `FEATURES.md` rows. Roughly half of ADR-133's footprint, as expected: the
  spelling and prefixing mechanisms already existed, only the owner gate and the declaration/render
  plumbing needed widening.

## Verified claims

- Every symbol cited above (`ICage.Bar`, `WireCage`, `Purr.Detail`, `Purr.On.Trace`, `Beam.Lens`,
  `Hamper.Weight`, and their `@CName` exports: `cage_bar_create`, `purr_detail_create`,
  `purr_on_trace_create`, `beam_lens_create`, `hamper_weight_create`, `hamper_weight_get_kilos`,
  `hamper_weight_isHeavy`) exists in the generated `Interop.cs` for the shipped fixture, read directly
  rather than inferred from the design.
- A nested class carrying `[DllImport]` externs compiling inside a `public interface` block under
  `TreatWarningsAsErrors` (Inferred in the original draft): now Verified, since the fixture's own
  `GeneratedBindingsCheck` run compiles `ICage`/`Bar` clean.
- The value class's re-indentation (`indentNestedBody()` applied to `renderValueClass` output) and its
  `@CName` chain composition (`ValueClassExports.kt` spelling `hamper_weight_*`, not the bare
  `weight_*`): both Verified by reading the generated `Interop.cs`/`CNameExports.kt` directly rather
  than inferred; both render correctly.
- The gate-order hazard for an eligible sealed interface: Verified, and now pinned by a Tier 1 cell
  (`an eligible sealed interface owns its nested type as the abstract class, never losing it`); a
  regression here has no diagnostic of its own, so that Tier 1 cell going red is the only signal.
- Verify: green, 1801 / 0 / 0, 35 (IntegrationTests/LeakTests side); processor 834.

## Inferred claims (not independently re-spiked for this reconciliation)

1. C# 8 is the version that admitted nested types inside interfaces (irrelevant to the shipped
   language floor either way, since the generated code already targets a much later `LangVersion`).
2. The ADR-125 sibling-arm export-prefix composition (`"${base.nativePrefix()}_${sub.simpleName
   .lowercase()}"`, unchanged by this ADR) still agrees with a nested-declaration child's own prefix
   for a **sibling**-declared arm that itself owns a nested type; no fixture cell exercises a nested
   declaration under a sibling arm specifically (only the nested-arm cell, `Purr.On.Trace`, shipped).

## Amendment (2026-09-15): a compiler-synthesized nested declaration is never walked

Issue #223. This ADR's walk reaches every public nested declaration under an admitted owner. Some
of those are not written by anyone. kotlinx.serialization's compiler plugin synthesizes a nested
`$serializer` object on every `@Serializable` declaration, and the walk declared it as
`public static class $serializer` inside the owner. `$` is not a legal C# identifier character, so
`Interop.cs` stopped parsing: six diagnostics per site, 144 errors across 24 owners in the report.

Two rules follow.

1. The walk filters compiler-synthesized declarations before it declares one or descends into it.
   The filter sits in `nestedClassDeclarations()` rather than at the candidate funnel, because the
   walk also feeds the ADR-066 reachability closure, which must not admit dependency types on a
   synthetic declaration's behalf.
2. No identifier containing `$` is rendered, from any route. `CirRenderer.render` checks the
   rendered file and fails the build naming the identifier. `$` is legal C# only inside an
   interpolated string, so string literals are stripped before the scan.

The signal is the name, not `KSClassDeclaration.origin`. Verified against the fixture: KSP reports
`Carton.$serializer` as `Origin.KOTLIN_LIB`, the same origin as the `@Serializable` class that owns
it, so origin cannot separate the two across a klib boundary. A `$` in a simple name can only have
been synthesized, since Kotlin source cannot declare one.

Not sanitized to `_serializer`, and not special-cased by name. The object is empty and nobody asked
for it; the correct behaviour is to not walk it, silently. It is not a consumer API gap, so it gets
no `SKIPPED_*` diagnostic either.

`:test-models` carries the fixture: `@Serializable data class Carton` and
`@Serializable value class CartonTag`, both reached from `Newsroom`, so the data-class and
value-class owner arms of the original report are both covered one klib boundary away.
