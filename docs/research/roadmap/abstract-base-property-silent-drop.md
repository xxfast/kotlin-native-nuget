# An abstract property inherited from an unexported abstract base class drops silently (and, when its type is supported, breaks the subclass with CS0115)

- ROADMAP: line text as of 2026-09-14: "An unplanned abstract property inherited from an unexported abstract **base class** (as opposed to an interface) still drops silently through `inheritedAbstractProperty`'s miss arm, left silent by design since the base class's members are planned nowhere to classify a miss against."
- Researched: 2026-09-14, 12:10 to 12:15 (5 of 15 min), one targeted Tier 1 probe run in a detached worktree (removed)
- Restatement: forward. `abstract class Nester : Roost()` where `Roost` is outside the export root and declares `abstract val x`. `Nester` does not implement `x`. Today `x` reaches C# nowhere and nothing says so. After the fix: a supported `x` renders `public abstract` on `Nester` (so a concrete Kotlin subclass's `override` binds), and an unsupported `x` gets one named `SKIPPED_UNSUPPORTED_PROPERTY` at `Nester.x`. The Kotlin side declares; C# consumes.
- Verdict: fix. No ADR (reserved 151 stays unused): the mechanism is the 2026-09-13 ADR-075 amendment applied to one more owner kind. Document as a further ADR-075 amendment paragraph. The ROADMAP line understates the bug: the supported-type case is a CS0115 in the generated file, not a silent skip.

## Findings

### F1. The class is not skipped; the base is dropped and its members re-home on the subclass (verified by reading)

ADR-101's base-class amendment: `forwardSuperClass` walks `declaredBaseChain()` and answers the nearest EXPORTED base, or null (`nuget-processor/src/main/kotlin/io/github/xxfast/kotlin/native/nuget/processor/forward/ForwardClassMembership.kt:201-204`). An unexported abstract base therefore makes the exported subclass base-less (or skips a hop to a further exported base). `isForwardMemberOf` (`ForwardClassMembership.kt:256-262`) admits the base's members via `superClass == null` or `isFromDroppedBase`. So the item is not moot: the subclass is generated and the base's abstract members reach the translator.

### F2. Exactly what reaches the miss arm today (verified by reading, confirmed by spike)

For `Roost.material` (abstract, parented to the unexported `Roost`) on `Nester`:

1. `ForwardPropertyPlanner.classProperties` (`forward/ForwardPropertyPlanner.kt:182-203`) filters by `isForwardPlannableMemberOf` (`ForwardClassMembership.kt:278-284`): `isDeclaredBy(Nester)` false, and the inherited arm requires `hasImplementation()`, false for an abstract property. Unplanned, so the planner's own `recordDropped` never sees it.
2. `translateClass` (`cir/CirClassTranslator.kt:602-636`): `planned == null`, then `prop.parentDeclaration != cls && prop.isAbstract()` sends it to `inheritedAbstractProperty`.
3. `inheritedAbstractProperty` (`CirClassTranslator.kt:376-407`): `owner = Roost`, key `tier1.hiddenroost.Roost.material`, `interfaceDeclarationCatalog.propertyFor(...)` misses because the catalog is planned over `interfaces` plus `unexportedSupertypeInterfaces`, which is filtered to `ClassKind.INTERFACE` (`NugetProcessor.kt:1228-1236`).
4. `emitInheritedAbstractPropertySkip` (`CirClassTranslator.kt:313-352`): first line `if (owner.classKind != ClassKind.INTERFACE) return null`. Silent, for EVERY type, supported or not.

### F3. The method half already declares the same member (verified by spike)

The abstract-method walk (`CirClassTranslator.kt:840-911`) has no owner-kind guard: `Roost.speak()` renders `public abstract string Speak();` on `Nester`. Properties and methods disagree on the same base.

### F4. Spike output (verified, Tier 1 probe run 2026-09-14 12:12)

Fixture (two files, same module): package `tier1.hiddenroost` with `abstract class Roost { abstract val material: String; abstract var height: Int; abstract val lining: Lining; abstract fun speak(): String; class Lining(val fibre: String = "down") }`; package `tier1.nester` (the export root) with `abstract class Nester : Roost() { fun describe() = "$material@$height" }` and `class Wren : Nester()` overriding all four. Command: `./gradlew :nuget-processor:test --tests '*Tier1ProbeAbstractBasePropertyTest*'` in a detached worktree of `main` at `112475a1`.

kspWarnings (only two):

```
[nuget:SKIPPED_UNSUPPORTED_PROPERTY] Skipping tier1.nester.Wren.lining: its type `tier1.hiddenroost.Roost.Lining` is a nested class or object never declared in C# (UNDECLARED_CLASS). ...
[nuget:SKIPPED_UNEXPORTED_SUPERTYPE] Skipping Nester : Roost: base class 'tier1.hiddenroost.Roost' is not in the export set, so it has no generated C# class; Nester is generated with no base at all and the base's public members are bound on Nester directly. ...
```

Generated C# (line numbers from the probe's `generatedCSharp`):

```
624:    public abstract class Nester : IDisposable, INugetHandle
638:        public string Describe()
648:        public abstract string Speak();
652:    public class Wren : Nester
674:        public override string Material
692:        public override int Height
714:        public override string Speak()
```

`Nester` declares no `Material`, `Height` or `Lining`. No diagnostic names `Nester.material`, `Nester.height` or `Nester.lining`. `Wren` renders `public override string Material` and `public override int Height` against a base that declares neither.

- **Verified**: the silent drop covers supported types too (`String`, `Int`), not only the unsupported one the ROADMAP names.
- **Inferred** (the Tier 1 harness compiles Kotlin, not C#): `public override string Material` on `Wren : Nester` with no `Material` on `Nester` is CS0115. This is a fixed C# rule, the same one the 2026-09-13 amendment cites for the interface case, so it is safe to build on.
- `Wren.lining` is already named by the planner's own skip (verified): the subclass half of the pair the interface test asserts (`Tier1AbstractUnexportedInterfacePropertyTest.kt:145-155`) is present today.

### F5. Why `Wren` renders `override` (verified by reading)

`overridesBaseClassMember(Nester)` (`ForwardClassMembership.kt:351-377`): `findOverridee()` on `Wren.material` lands on `Roost.material`, whose parent is `ClassKind.CLASS`, so `isOverride = true`. Correct once `Nester` declares the abstract member, wrong (CS0115) while it does not.

### F6. The ADR-075 rationale for leaving it silent no longer holds (verified by reading)

`docs/adr/075-collection-property-getter-setter-independence.md:504-507`: "its members are not planned anywhere, so a miss there says nothing about bridgeability and would invent a reason for an ordinary type". Planning them (the fix below) removes the premise: a miss then means the planner refused the type, exactly as for an unexported interface.

## Recommendation

**Option A (chosen): plan the dropped base chain onto the declaration catalog, the way unexported interfaces already are, and relax the miss arm's owner guard.** Three edits, one new test, one doc amendment.

1. `NugetProcessor.kt:1228-1236`: widen the `unexportedSupertypeInterfaces` filter from `classKind == ClassKind.INTERFACE` to `classKind == INTERFACE || classKind == CLASS` (rename to `unexportedSupertypes`). `getAllSuperTypes()` is already transitive, so `Dinghy : Skiff : Vessel` with only `Skiff` dropped plans `Skiff` too. `kotlin.Any` yields no properties. `interfaceProperties(cls)` filters `parentDeclaration == iface`, so concrete base members get a plan as well; harmless, this catalog reaches only the C# translation, never `generateCNameWrappers` or the ADR-055 contract (per the existing comment at `NugetProcessor.kt:1222-1225`). Keep the third planner's drop channel unmerged, same reason as today.
2. `CirClassTranslator.kt:324`: delete `if (owner.classKind != ClassKind.INTERFACE) return null`. The `qualified in exportedTypes` guard on the next line already keeps an exported owner silent (its own `IFoo`/class declaration names the member). Update the KDoc at `CirClassTranslator.kt:355-375` and the comment at `:626-631`.
3. `inheritedAbstractProperty` needs no code change: `CirProperty(isAbstract = true, setter = if (plan.setter != null) "" else null)` is right for a base-class owner too, because the dropped base has no C# class and the member is a fresh abstract slot on the subclass.

Expected result on the F4 fixture: `Nester` gains `public abstract string Material { get; }` and `public abstract int Height { get; set; }`, `Wren`'s two `override`s bind, and one `SKIPPED_UNSUPPORTED_PROPERTY` names `Nester.lining` with the `UNDECLARED_CLASS` wording beside the existing `Wren.lining` one. Inferred until the red test goes green.

Rejected:

- **B. Dedicated skip reason at the miss arm, no planning.** Names `Nester.lining` but leaves `Nester.material` dropped and `Wren` CS0115. Fixes the ROADMAP sentence, not the bug. Also the "invents a reason for an ordinary type" objection in ADR-075 applies to it and not to A.
- **C. Let `isForwardPlannableMemberOf` admit the inherited abstract member into the ordinary planner.** Mints `nester_get_material` exports and a concrete C# getter, so `Wren.Material` is `override` of a non-virtual (CS0506) and the ADR-055 contract grows. The interface precedent (ADR-075, 2026-09-11) deliberately chose declaration-only, no export.
- **D. Fold the property into the abstract-method walk's hand-mapped spelling.** A second spelling of one plan is what CS0738/CS1715 are made of (the reason the 2026-09-11 amendment reads the catalog instead).

## Files an implementation touches

- `nuget-processor/src/main/kotlin/io/github/xxfast/kotlin/native/nuget/processor/NugetProcessor.kt` (supertype filter, ~1228-1236, and its comment block ~1214-1227)
- `nuget-processor/src/main/kotlin/io/github/xxfast/kotlin/native/nuget/processor/cir/CirClassTranslator.kt` (`emitInheritedAbstractPropertySkip` guard at 324, KDoc 355-375, comment 626-631)
- `nuget-processor/src/test/kotlin/.../tier1/Tier1AbstractUnexportedBasePropertyTest.kt` (new, sibling of `Tier1AbstractUnexportedInterfacePropertyTest`)
- Optional end-to-end cell: `test-models/src/nativeMain/kotlin/dev/other/core/` unexported abstract base plus `test-library/.../` abstract subclass and concrete grandchild, `IntegrationTests/AbstractUnexportedBasePropertyTests.cs` (mirror `AbstractUnexportedInterfacePropertyTests.cs`, including a pure C# subclass with `base(IntPtr.Zero)`). Justified because the Tier 1 harness does not compile C# and the bug IS a C# compile error.
- `docs/adr/075-collection-property-getter-setter-independence.md` (amendment paragraph after the 2026-09-13 one; strike the "deliberately left silent" sentence), `docs/adr/101-unexported-supertype-skip.md` only if it repeats the claim, `ROADMAP.md:40`, `FEATURES.md` row for inherited abstract properties, `docs/topics/` page on inheritance.
- No leak row: no handle, no export, no ABI change.

## Sample test

Tier 1 cell, generator-only (the F4 fixture verbatim), `nuget.rootPackage = tier1.nester`:

```kotlin
@Test
fun `an inherited unimplemented property from an unexported abstract base renders abstract on the class`() {
  val result = Tier1Harness.run(sources, processorOptions = mapOf("nuget.rootPackage" to "tier1.nester"))
  assertTrue(result.compiledClean)
  val csharp = result.generatedCSharp
  assertFalse("class Roost" in csharp)
  assertTrue(result.kspWarnings.any { it.contains("SKIPPED_UNEXPORTED_SUPERTYPE") && it.contains("tier1.hiddenroost.Roost") })
  assertContains(csharp, "public abstract string Material { get; }")
  assertContains(csharp, "public abstract int Height { get; set; }")
  assertContains(csharp, "public override string Material")
  assertContains(csharp, "public override int Height")
  assertFalse("""EntryPoint = "nester_get_material"""" in csharp)
  assertFalse("Lining" in csharp)
}

@Test
fun `an unbridgeable inherited base property is skipped named on the abstract class`() {
  val result = Tier1Harness.run(sources, processorOptions = mapOf("nuget.rootPackage" to "tier1.nester"))
  val missSkips = result.kspWarnings.filter { it.contains("SKIPPED_UNSUPPORTED_PROPERTY") && it.contains("Nester.lining") }
  assertEquals(1, missSkips.size, "kspWarnings=${result.kspWarnings}")
  assertTrue(result.kspWarnings.any { it.contains("SKIPPED_UNSUPPORTED_PROPERTY") && it.contains("Wren.lining") })
}
```

Today: the first test fails on `public abstract string Material { get; }` (verified absent, F4), the second on `missSkips.size == 0` (verified, F4).

## Deferred scope

- Cross-module base (klib / `Tier1DependencyLibrary` jar). ADR-101 verified `getAllProperties()` surfaces a klib base's CONCRETE members; an abstract one is inferred to behave the same. Add a cross-module cell if cheap, otherwise note it as inferred like ADR-075 did for the interface case.
- A dropped intermediate base whose abstract property re-declares one the KEPT exported base also declares abstract (`Dinghy : Skiff : Vessel`, both `Skiff.x` and `Vessel.x` abstract): `inheritedAbstractProperty` renders `abstract` without `override`, which hides `Vessel.X` (CS0108 warning, compiles). Not this item.
- The abstract-method walk's hand-mapped spelling (F3) versus the catalog: out of scope, pre-existing.

## Open what-questions

- What should the ROADMAP line say once shipped? Recommendation: rewrite it as the wider bug (supported abstract base properties vanish, CS0115 on the concrete subclass), since the fix closes both; the documenter deletes it anyway.
- Should the end-to-end xunit cell be part of this lane? Recommendation: yes, one small fixture, because Tier 1 cannot prove the CS0115 is gone. Human decision pending.
