# ADR-167: Interface super-interfaces: `IDerived : IBase`, own members only, unexported supers re-homed

## Status

Accepted

## Context

`interface Derived : Base` flattened: `CirInterface` (`CirModel.kt`) carried no super-interface
list, so `renderInterface` hardcoded `: IDisposable` and `translateInterface` re-derived every
member, own and inherited, onto `IDerived` itself. After [ADR-113](113-interface-declaration-on-the-forward-plan.md)
narrowed that translator to only what the forward plan can express, the inherited members
disappeared outright instead of being present-but-unimplementable, a truncation ROADMAP Phase 4
tracked as understated.

It is not only a truncation. Two shapes ship a non-compiling `Interop.cs` today:

- The [ADR-084](084-csharp-implemented-interfaces.md) bridge factory walks a Kotlin interface's
  **inherited** members (`getAllProperties()`/`getAllFunctions()`, no owner filter) to read a C#
  implementer's state, so a derived interface at a **parameter** position generates a bridge that
  calls `impl.Name`/`impl.Age` off an `IPet` that declares neither: `CS1061`, verified by spike
  against a `Pet : Named, Aged` fixture with no base list.
- A class implementing a generic interface with a concrete type argument (`class KibbleJar :
  Holder<Int>`) renders `: IHolder`, dropping the type argument: `CS0305`.

An unexported super-interface (`ShowCat : Named, Pedigree` with `Pedigree` outside `rootPackage`)
is silently dropped today: no diagnostic, and `Breed`/`Registry` vanish from `IShowCat`, even
though the implementing class already re-homes them (the ADR-101 mirror this ADR extends to
interfaces).

Every analogue that inherits interfaces at all preserves the hierarchy rather than flattening it:
Kotlin/Native's ObjC export chains `@protocol` inheritance, Swift export preserves protocol
inheritance, the JVM's own `extends` list is never flattened, and C# itself is the target
language's own idiom (`IList<T> : ICollection<T>, IEnumerable<T>`).

## Alternatives Considered

1. **Keep flattening, but plan-driven (re-add inherited members onto `IDerived` instead of
   dropping them).** Closes the ADR-113 regression but not the CS1061/CS0305 breaks, and
   `HouseCat` returned as `Pet` still would not be an `INamed`: `Pets.GreetAll(new HouseCat(...))`
   stays a compile error, and `is INamed` is always false. Contradicts every analogue above.
   Rejected.
2. **Base list plus a `new` redeclaration of every inherited member, always.** Compiles, but
   doubles every `IntelliSense` entry for no reason when the signature is identical, and a
   consumer reading `IDerived`'s own declaration would see a member that is not actually theirs to
   implement differently. Kept only for the one case (a diamond) where C# genuinely needs it (see
   Decision).
3. **The ADR-040 backing class delegates to one wrapper instance per kept super**, instead of
   implementing every inherited member directly. C# single inheritance and multiple supers turn
   this into a wrapper forest per handle; the flat backing class (one wrapper per handle) is
   already what ADR-040 is. Rejected.
4. **Emit a marker interface for an unexported super** (an `IPedigree` `ShowCat` implements
   emptily, so `Breed`/`Registry` still have a declaring type name). [ADR-101](101-unexported-supertype-skip.md)
   already rejected marker interfaces for the class route for the same reason: it invents a
   public type with no Kotlin declaration behind it. Rejected; re-homing (Decision) is the same
   answer ADR-101 already gives for a class.

## Decision

`CirInterface` gains a `superInterfaces: List<String>` field (C# spellings, type arguments
included), rendered before `IDisposable`. What determines its contents and `IDerived`'s own member
list is `ForwardInterfaceHierarchy` (`forward/ForwardInterfaceMembership.kt`), one instance per
translated interface, closed over the export set:

1. **The base list is the direct, exported supers only**, filtered by the same `keepsSupertype`
   decision (`SupertypeKind.SUPER_INTERFACE`) the class route already used for a base class, so a
   generic super spells its type argument (`IIntHolder : IHolder<int>`) and an unexported super is
   dropped from the list, `SKIPPED_UNEXPORTED_SUPERTYPE`. C# inherits transitively through the
   kept chain, so `IHouseCat : IPet` alone is enough for `INamed n = Pets.Adopt()` to compile; the
   backing class and every export list only the **direct** derived interface, never the full
   transitive set.
2. **`IDerived` declares its own members only**, decided **lexically** (`iface.declarations`), not
   by KSP's `parentDeclaration`: a substituted generic override (`IntHolder`'s `peek(): Int` over
   `Holder<T>.peek(): T`) is reported by KSP as owned by the subtype even though `IntHolder` never
   wrote it, so an owner-based test wrongly kept it on `IIntHolder` (hiding `IHolder<int>.Peek`,
   `CS0108`).
3. **An identical-signature override is omitted** from the derived interface: `HouseCat`'s
   `override fun greet(): String` restates `Named.greet(): String` with a default body, and
   redeclaring it as `string Greet()` on `IHouseCat` is `CS0108` under the consumer's
   `TreatWarningsAsErrors` build. The member is still planned for the ADR-040 backing class; only
   the interface declaration omits it.
4. **A diamond is redeclared with `new`.** `Moggy : Whiskered, Tailed`, both declaring `whiskers`/
   `blink`/`twitch`: inheriting leaves `moggy.Whiskers` ambiguous (`CS0121`) and the ADR-084 bridge
   reading `impl.Whiskers` off `IMoggy` would not compile either, so `IMoggy : IWhiskered, ITailed`
   redeclares all three with `new`, which is unambiguous C# and the one case Alternative 2's
   always-`new` shape is actually needed.
5. **A covariant override is a named skip.** `override fun greet(): Cat` narrowing a kept super's
   `fun greet(): Pet` cannot be redeclared on `IDerived` without `new` plus an explicit interface
   implementation on every implementer (a distinct, deferred subsystem); v1 leaves it off
   `IDerived` entirely, `SKIPPED_UNSUPPORTED_COMBINATION`, and C# still reaches it through the
   super, at the super's type. The ADR-040 backing class implements it at that same (super's)
   type, so the member itself is never lost, only its narrower spelling on the derived interface.
6. **An unexported super's members are re-homed onto the derived interface**, the ADR-101 mirror:
   `ShowCat : Named, Pedigree` with `Pedigree` unexported declares `Breed`/`Registry` on
   `IShowCat` itself (lexically "declared here" per point 2, since re-homing widens
   `isDeclaredHere` to the interface plus every re-homed super), so a C# implementer of
   `IShowCat` can satisfy the ADR-084 bridge that reads them, with no `IPedigree` ever declared
   (Alternative 4, rejected).
7. **The ADR-040 backing class, its interface export list, and the ADR-084 bridge factory's slot
   walk all plan every member the interface has, own and inherited**, unchanged from before this
   ADR (`callableCatalog` already walked `getAllFunctions()`/`getAllProperties()` with no owner
   filter); only the **declaration** (`translateInterface`) narrows to own members. This is why a
   `HouseCat` returned from Kotlin answers `Name`/`Age`/`Greet()` through its backing wrapper, and
   why a C#-implemented `IHouseCat` must still implement every inherited member for the bridge to
   read.
8. **A declared member whose C# name equals an *inherited* member's name** (a method named like an
   inherited property, or the reverse) is `ERROR_CSHARP_NAME_COLLISION`, the same CS0108 hiding
   guard the base-list-adjacent members already need, extended across the base list
   (`emitInheritedInterfaceNameCollisions`).
9. **Bridge selection reads the parameter's declared interface, not runtime order.**
   `NugetMarshal.HandleOf<T>` now passes `typeof(T)` to `NugetBridge.HandleFor(object impl, Type
   declared)`, so a C# `MyloHouseCat : IHouseCat` passed at an `IPet` parameter picks `IPet`'s
   bridge rather than risking a match against an unrelated interface `MyloHouseCat` also happens
   to implement (or against `INamed`'s bridge specifically, now that `IPet` genuinely is an
   `INamed`). The runtime `is`-based fallback stays for an `object`-typed value with no static
   interface type to read. See the amendment on [ADR-084](084-csharp-implemented-interfaces.md).

The class route's own base list (`KibbleJar : IHolder<int>`) reuses the same super-interface
spelling helper, fixing its pre-existing `CS0305` as a side effect: it was the same "render a
super-interface reference" code the class route already had, just missing type arguments.

## Consequences

**What changes:** `CirModel.kt` (`CirInterface.superInterfaces`), `CirClassRenderer.kt`
(`renderInterface`'s base list), `CirClassTranslator.kt` (`translateInterface`'s super list, own-
member narrowing, override omission, re-homing, diagnostics; the class route's interface list
gains type arguments), `CirTranslator.kt` (threads `exportedTypes` into `translateInterface`),
`ForwardCallablePlanner.kt`/`ForwardPropertyPlanner.kt` (interface entries already plan inherited
members; unchanged in shape, read by the new hierarchy for placement), `exports/InterfaceExports.kt`,
`exports/Helpers.kt` (the new `ERROR_CSHARP_NAME_COLLISION` cross-base-list guard), a new
`forward/ForwardInterfaceMembership.kt` (`ForwardInterfaceHierarchy`, `ForwardInterfaceMemberPlacement`).

**What breaks:** none for an existing consumer. `test-library` had no interface extending another
before this fixture, so no shipped `IFoo` loses a member: every member a derived interface used to
flatten onto itself either genuinely belongs there (own) or now reaches C# through the correct
base instead.

**Fixed alongside, pre-existing:**

- KSP2 marks a member merged from two unrelated supers as `SYNTHETIC`, and the existing
  `isCompilerOwnedMember` filter dropped it from `getAllFunctions()`, so a bridge factory for
  `object : Moggy` silently had no slot for `blink()`/`whiskers` and failed to compile.
- A false-positive `SKIPPED_UNSUPPORTED_TYPE ... T` warning fired for a member ADR-113's
  type-parameter carve-out (`typeParameterMethods`/`typeParameterProperties`) already restores
  (`Holder.peek`): the warning read the plan gap, not the carve-out's own restoration.

**Deferred, known and untested:** a diamond whose override narrows the return covariantly (a
diamond *and* a covariant override at once) takes the covariant-skip path rather than a combined
`new`-plus-narrower-type path; not fixtured. The interface-bridge route's own inherited-overload
crash (a same-name pair reached only through inheritance) is [ADR-090](090-ordinary-class-method-overloads.md)'s
overload-numbering fix, landed first and reused here unchanged, not a fresh decision of this ADR.
A generic super-interface with an unrepresentable type argument (`Box<List<Int>>`) is expected to
drop from the base list with a named skip, the same fallback other unspellable-type sites use;
not fixtured here.

Fixture: `test-library/.../test/lineage/Lineage.kt` (the base-list, own-member, generic-super,
unexported-super and both-direction cells) and `.../test/lineage/Moggy.kt` (the diamond);
unexported super `.../hidden/Pedigree.kt`. Tests: `Tier1InterfaceSuperInterfacesTest.kt`;
`IntegrationTests/InterfaceSuperInterfaceTests.cs`; `LeakTests/LiveHandleTests.cs` rows 6n/6o.
