# ADR-125: A sealed interface's arms may be declared beside it

## Status

Accepted

## Context

GitHub issue #130, filed as a design question rather than a bug:

```kotlin
sealed interface Kind

data class Device(val id: String) : Kind
data object Unknown : Kind

data class Item(val kind: Kind)
```

```
[nuget:SKIPPED_INELIGIBLE_SEALED_INTERFACE] Skipping Kind: sealed interface `Kind` is declared as
`IKind` but cannot be reconstructed in C#: subclass `Device` is declared outside the sealed
interface. make every subclass a nested class or object with no other superclass and no
sub-interfaces
```

[ADR-112](112-sealed-interface-mapping.md) requires every arm to be nested inside the interface.
Arms declared beside the interface, in the same file and package, are the ordinary Kotlin spelling
of a closed hierarchy: Kotlin has permitted same-package, same-module subtypes since 1.5 precisely
so they need not be nested. The reporter's arms are public API on two other platforms, so nesting
them to satisfy a C# generator is a breaking change for consumers who never touch the bindings.

The fallout is not confined to the interface. One refused interface in their tree takes out the
`kind` property on nine implementing types, the constructor **and** `copy` of two unrelated data
classes that merely hold one, and a collection property whose element type is the interface.
Roughly twenty missing members from one predicate.

### The question the issue asks

> Is the nesting requirement essential to the reconstruction, or incidental to how the arms are
> discovered?

**Incidental.** Verified, by reading:

- Arm discovery is `getSealedSubclasses()` on both sealed routes. There is no nested-declaration
  walk anywhere: `CirClassTranslator.translateSealedClass` enumerates
  `cls.getSealedSubclasses()` (`cir/CirClassTranslator.kt:1305`) and reads `parentDeclaration` only
  to compute a rendering flag, `isNested` (`:1310-1311`).
- The renderer already emits sibling arms. `CirSealedRenderer.renderSealedClass` emits the nested
  arms inside the base (`cir/CirSealedRenderer.kt:28`), spells **every** arm bare in the
  `FromHandle` switch in Kotlin declaration order (`:41-44`), and re-emits the non-nested arms at
  namespace level through `outdentToNamespaceLevel()` (`:53-56`, `:204`). That path has shipped for
  sealed *classes* since ADR-009's 2026-09-07 sibling amendment, with the `FlatShape` / `Label` /
  `Loaf` fixture and `IntegrationTests/FlatSealedSubclassTests.cs` behind it.
- Arm ownership already keys on `isSealedSubclass()`, whose sealed-interface half is
  `superTypes.any { it.isEligibleSealedInterface() }` (`forward/ForwardClassMembership.kt:103-105`),
  which is indifferent to where the arm is declared. `rootClasses` and `rootObjects` already filter
  on it (`NugetProcessor.kt:661`, `:691`).
- The refusal is one test, and it runs *inside* the `getSealedSubclasses()` loop, so the arm has
  already been discovered when it is refused (`forward/ForwardClassMembership.kt:47-49`).

ADR-112 says as much itself: "The renderer does have the issue-#54 sibling path
(`CirSealedRenderer.kt:62`), so this is a scope choice, not a mechanism limit; widening is a
one-line predicate change once the nested case is green."

### What nesting was silently buying

It is not literally one line, because a nested declaration implies two properties a top-level one
does not.

**Guard 1, one sealed parent per arm.** A nested class has exactly one enclosing declaration, so it
can be an arm of exactly one hierarchy. A top-level Kotlin class may implement two sealed
interfaces (`class X : A, B`). C# single inheritance cannot express that, and the renderer would
emit `X` twice, once under each base, as two namespace-level `public sealed class X` declarations
(CS0101).

**Guard 2, no top-level enum arm.** `rootEnums` (`NugetProcessor.kt:693-697`) filters on visibility,
`classKind == ENUM_CLASS` and `parentDeclaration == null` and nothing else. It is the only root
bucket without the `!isSealedSubclass()` filter `rootClasses` and `rootObjects` carry. So the moment
a top-level `enum class Pitch : Tone` is admitted as an arm, the generated file declares both
`public enum Pitch` and `public sealed class Pitch : Tone`, and every consumer fails CS0101.
Verified by reading; the fixture keeps the trap live.

### Enums cannot be arms at all

Verified by spike (`dotnet new classlib`, net10.0):

```csharp
public enum Family : IKind { A, B }
public enum Family2 : Kind { A, B }
```

```
error CS1008: Type byte, sbyte, short, ushort, int, uint, long, or ulong expected
```

A C# enum admits only an integral base, so an enum arm has no shape under ADR-112's chosen abstract
class *or* under its rejected interface alternative. This is a language gap, not a generator gap,
and the diagnostic should say so instead of asking for a style change that cannot help.

### The cascade, and why issue option 3 is declined

The issue's third option is to keep the interface ineligible but stop the skip from cascading onto
the classes that hold one. `planOrSkip` refuses the **whole** callable when any input type is
ineligible (`forward/ForwardCallablePlanner.kt:1752-1757`), and a data class's `copy` is planned
from the same primary-constructor parameters, so it follows. Verified by reading.

That is deliberate and not repairable in the reporter's shape. If `Kind` has no C# type, then
`Item(Kind kind)` has no C# signature: there is nothing for a caller to pass for a required
parameter. The only shape where an ineligible input could be dropped rather than refused is a
**trailing defaulted** parameter, which ADR-091's default-flag mask could omit; `Item(val kind:
Kind)` is not that shape. The honest fix for the cascade is to remove its cause, which is what this
ADR does for the reporter's `data class`-armed hierarchy.

## Alternatives Considered

### 1. Admit top-level class and object arms, refuse enum and multi-sealed-parent arms by name (chosen)

Delete the nesting test; add the two refusals nesting was implicitly providing. The generated C# is
byte-for-byte what a sibling-armed sealed *class* already produces.

Pros: the more common Kotlin spelling binds; no renderer, planner, classifier or export-builder
change; the cascade disappears for those hierarchies; the two new refusals name a real C# constraint
each. Cons: `rootEnums` needs the filter its siblings have, and an author who writes an enum arm
gets a refusal rather than a binding.

### 2. Admit enum arms as boxed wrapper arms (rejected for v1, priced)

`public sealed class PitchArm : Tone { public Pitch Value { get; } }`. Needs a new CIR arm kind, a
new Kotlin export returning the entry ordinal, an unwrap on the parameter side, and a naming rule
against the already-declared `public enum Pitch`. Roughly 6 to 8 files across `cir/`, `exports/` and
the classifier, and it invents a C# shape with no precedent in this repository. That is a feature,
not the removal of an incidental constraint. Deferred, not rejected on principle.

### 3. Opt-in flag on `nuget { publish { } }` (rejected)

The issue offers this as a way for the author to take the risk. With the two guards above there is
no risk left to consent to: what remains unsafe is refused by name. GOALS.md prefers no knob where
the generator can decide.

### 4. Keep the nesting rule, reword the diagnostic only (rejected)

Leaves the ordinary Kotlin spelling of a closed hierarchy unbound for no mechanism reason, and
leaves the twenty-member cascade in place.

### 5. Stop the cascade instead of removing its cause (rejected, issue option 3)

Declined with the reason above: a required parameter of an ineligible type has no C# spelling, so
the constructor cannot be kept. Not implementable except for a trailing defaulted parameter, which
is not the reported shape.

## Decision

Adopt alternative 1. Eligibility for a `sealed interface` becomes:

- no type parameters;
- every `getSealedSubclasses()` entry is a `CLASS` or `OBJECT`, **nested in the interface or
  declared beside it**;
- no entry is an `INTERFACE` (the discriminator is a flat `when`, and a sub-interface has no single
  C# class to construct);
- no entry is an `ENUM_CLASS` (CS1008, verified by spike);
- no entry has another class supertype (a C# arm can only extend the abstract base);
- no entry implements more than one sealed interface (C# single inheritance).

| File | Change |
|---|---|
| `forward/ForwardClassMembership.kt:47-49` | Delete the `parentDeclaration` test. |
| `forward/ForwardClassMembership.kt` (same function) | Add the enum-arm refusal, naming CS1008. |
| `forward/ForwardClassMembership.kt` (same function) | Add the multi-sealed-parent refusal, counting supertypes that answer `isSealedInterface()`. |
| `NugetProcessor.kt:693-697` (`rootEnums`) | Add `.filter { !it.isSealedSubclass() }`, the filter `rootClasses` and `rootObjects` already carry. |
| `NugetProcessor.kt` (`SKIPPED_INELIGIBLE_SEALED_INTERFACE` hint) | Stop saying "make every subclass a nested class or object". |
| `forward/ForwardDiagnostic.kt` (`SEALED_POSITION` hint) | Same wording change, cite ADR-125. |

The multi-parent test reads `isSealedInterface()`, the kind-and-modifier predicate, **not**
`isEligibleSealedInterface()`. Two interfaces sharing an arm would otherwise ask each other for
eligibility forever.

`rootEnums`'s filter is unreachable given the enum-arm refusal, and it stays anyway: it is what
turns a future predicate mistake from CS0101 in every consumer into a missing type, and it makes
all three root buckets read the same.

### Consumer API

```kotlin
sealed interface Transmission

data class Ping(val ms: Int, val label: String) : Transmission
data object Silence : Transmission

data class Packet(val signal: Transmission)
```

```csharp
public abstract class Transmission : IDisposable, INugetHandle
{
    internal static Transmission FromHandle(IntPtr handle) => Native_GetType(handle) switch
    {
        0 => new Ping(handle),
        1 => new Silence(handle),
        _ => throw new InvalidOperationException("Unknown sealed class type")
    };

    public abstract void Dispose();
}

public sealed class Ping : Transmission { public int Ms { get; } public string Label { get; } }
public sealed class Silence : Transmission { }

public sealed class Packet : IDisposable, INugetHandle
{
    public Packet(Transmission signal) { ... }
    public Transmission Signal { get; }
    public Packet Copy(Transmission signal) { ... }
}
```

There is no `ITransmission`. This is byte-for-byte the ADR-009 sibling output for a sealed class.

### Diagnostics

`SKIPPED_INELIGIBLE_SEALED_INTERFACE` keeps its shape and loses the style rule. The reason now
always names a C# constraint, so the hint states the constraints rather than a spelling:

```
SKIPPED_INELIGIBLE_SEALED_INTERFACE: sealed interface `pkg.Tone` is declared as `ITone` but cannot
be reconstructed in C#: subclass `Pitch` is an enum class, and a C# enum can only extend an
integral type (CS1008), not the abstract class an arm is declared as.
hint: every subclass must be a class or object, declared in the interface or beside it, with no
other superclass, no sub-interface and no second sealed interface; arms may not be enums (ADR-125),
or declare it as a sealed class
```

The `SEALED_POSITION` hint drops "make every subclass a nested class or object" for the same reason.

## Consequences

- A sibling-armed sealed interface binds at every ADR-105 position, and the cascade onto every
  callable that takes one as an input disappears with it. That is the twenty members issue #130
  reports, recovered for the `data class`-armed hierarchy.
- An enum-armed sealed interface stays ineligible, with a reason that names CS1008. The cascade
  onto its holders is unchanged. There is no C# shape short of alternative 2.
- A **nested** enum arm was eligible before this ADR and rendered as an opaque
  `public sealed class Pitch : Tone` with no entries and no `Value`, silently losing the enum
  (no CS0101, since `rootEnums` never declares a nested enum). The new refusal fixes that as a side
  effect. Verified by reading `ForwardClassMembership.kt:41-57` before the change.
- **An arm's extra interfaces are dropped silently.** `class Odd : Kind, CharSequence` stays
  eligible (`declaredSuperClass()` keeps only `CLASS` supertypes) and `renderSealedClass` emits
  `: <base>` only, with no interface list, so the second interface vanishes with no diagnostic.
  Inferred, from reading `sealedSubclassBlock` (`cir/CirSealedRenderer.kt:69`); not spiked. The
  issue explicitly does not ask for it, and it is the same supertype limitation an ordinary class
  has.
- ROADMAP's "an ineligible sealed interface's nested subclass gets two build warnings" item shrinks
  but stays open: the double warning now needs an interface refused for the enum, multi-parent,
  sub-interface, generic or second-superclass reason.
- Still deferred: boxed enum arms, sub-interfaces, generic sealed interfaces, base members with
  bodies rendered on the abstract class, an arm's extra interfaces.
- No new handle kind and no new marshalling path: a sibling arm rides the same `FromHandle` mint a
  nested arm does, so `LeakTests` gains no row. `LiveHandleTests.cs` has no sealed row at all today,
  which is a general gap and belongs on ROADMAP rather than here.

## Prior art (to the depth that changes the decision)

- **ADR-009's 2026-09-07 amendment** did this exact widening for sealed *classes*, for the same
  reason (one Kotlin type, one C# type, wherever it is declared) and with the same renderer. This
  ADR is that amendment applied to the interface half; the sibling path it added is what makes the
  change small.
- **Kotlin**: same-package, same-module subtypes of a sealed interface have been legal since 1.5, so
  the nested spelling is a minority one in practice. Inferred from the Kotlin docs
  ([sealed classes](https://kotlinlang.org/docs/sealed-classes.html)), not spiked.
- **C#**: a closed hierarchy is an abstract class with a non-public constructor and `sealed`
  subclasses matched with `switch` patterns. Whether those subclasses are nested is a style choice
  there too. Inferred; not load-bearing, since ADR-009 already settled the shape.
- Skipped: ObjC/Swift export (neither represents sealedness, so neither has a nesting rule to
  mirror), JVM (no reconstruction seam).

## Claims ledger

**Verified by reading** (file and line named inline): the nesting test is the only thing refusing a
sibling arm; discovery is `getSealedSubclasses()` on both routes; `isNested` is a rendering flag
only; the renderer's sibling path and its bare `FromHandle` spelling; `isSealedSubclass()` being
declaration-position-agnostic; `rootEnums` lacking the `!isSealedSubclass()` filter its two siblings
carry; the whole-callable input refusal in `planOrSkip` and therefore the cascade; a nested enum arm
passing eligibility today.

**Verified by spike**: CS1008 for an enum with a non-integral base (scratch `dotnet` classlib, both
the interface and the class spelling).

**Inferred, nobody has verified**, and what breaks if wrong:

1. Kotlin permits a class to implement two sealed interfaces. If it does not, the multi-parent guard
   is dead code, which is the safe failure direction. The Tier 1 test for it compiles the shape, so
   the answer is recorded there rather than argued here.
2. An arm's extra interfaces are dropped without a diagnostic. If wrong, the renderer emits an
   interface list this ADR did not expect, and the consumer either compiles (harmless) or fails
   loudly with CS0535.
3. `rootEnums`'s new filter is unreachable. If wrong, an enum arm that slips past the refusal is a
   missing type rather than CS0101, which is the point of keeping it.
