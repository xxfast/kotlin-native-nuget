# Issue #613: a subclass nested in its own `open` base redeclares the base's private externs (CS0108)

- ROADMAP: no ROADMAP line; GitHub issue #613 as of 2026-11-09 ("a subclass nested inside its own `open` base class generates C# where `Fruit.Apple` redeclares `private static extern Native_Create/Native_Dispose` that `Fruit` also declares").
- Researched: 2026-11-09, about 6 of a 10 minute budget. Read the code only. No `dotnet` on this host (`which dotnet` printed nothing), so no C# spike ran.
- Restatement: forward (Kotlin -> C#), Kotlin declares. `open class Fruit(val name: String? = null) { class Apple(name: String? = "apple") : Fruit(name) }` should still generate `Fruit.Apple` nested inside `Fruit`. Every per-type extern Apple declares whose name matches an extern on an enclosing base Apple inherits from (and can access) should be spelled `private new static extern`. A top-level sibling subclass keeps today's spelling.
- Verdict: fix. Amend ADR-116 (its 2026-09-11 amendment already holds the sealed-arm rule). No new ADR number.

## Findings

1. **verified (by reading)**: the hypothesis holds. The sealed route already does this. `CirSealedRenderer.kt:209-220` computes `baseExternNames` from `sealed.ordinaryNativeImports()` when `subclass.isNested && !generic`. It applies `CirDllImport.hiding(baseExternNames)` (`CirSealedRenderer.kt:537-538`) at the property site (`:325`) and the method site (`:343`). `renderDllImport` prints `new ` when `import.isNew` is set (`CirClassRenderer.kt:772-775`).
2. **verified (by reading)**: the ordinary open-class route has no hiding at all. `renderClassDeclaration(cls)` (`CirClassRenderer.kt:234-438`) renders externs at these sites, and none of them call `hiding`:
   - ctor: `renderClassConstructor` -> `renderConstructorMember` -> `constructorNativeImport` (`CirClassRenderer.kt:524-550`, `CirNativeImports.kt:69-88`, name `Native_Create${nativeSuffix}`)
   - plain property: `cls.propertyNativeImports(prop)` (`CirClassRenderer.kt:382`)
   - flow property: `renderFlowPropertyNativeImports` builds raw strings (`CirClassRenderer.kt:449-512`: `Native_Get*Collect/Flow/Value/HasValue`, `Native_Set_*`)
   - legacy property: `renderLegacyPropertyNativeImports`, raw string `Native_Get_${nativeName}` (`CirClassRenderer.kt:514-522`)
   - method: `cls.methodNativeImport(method)` (`CirClassRenderer.kt:395`)
   - callback, stored-callback and interface-bridge methods: raw strings (`CirClassRenderer.kt:1065-1070, 1124-1146, 1212-1215`)
   - StateFlow CAS: raw string (`CirClassRenderer.kt:1275-1290`)
   - companion `CirDllImport` members: `renderMember` -> `renderDllImport` (`CirClassRenderer.kt:670-673`)
   - data-class methods: `renderDataClassMethods`
   - dispose: `cls.disposeNativeImport()`, name `Native_Dispose` (`CirClassRenderer.kt:418-426`, `CirNativeImports.kt:241-251`)
3. **verified (by reading)**: the nested class sees nothing about its owner. `renderClassDeclaration` ends with `renderNestedDeclarations(cls.nestedDeclarations)` (`CirClassRenderer.kt:435`). `renderNestedDeclarations(declarations)` (`CirRenderer.kt:251-257`) takes only the list, so the owner's extern names are not available where the nested class renders. The owner `CirClass` is in scope at the call site, though, so the fix is to thread it through.
4. **verified (by reading)**: `CirClass.ordinaryNativeImports()` (`CirNativeImports.kt:3-20`) covers only the structural routes: ctors, non-legacy properties, plain methods, data-class methods and dispose. It leaves out flow, legacy, callback, interface-bridge, CAS and companion externs. The sealed precedent has the same gap (`CirSealedClass.ordinaryNativeImports`, `CirNativeImports.kt:54-64`). If an implementation reuses it as the base name set, it fixes Native_Create, Native_Dispose, plain Native_Get_* and method externs, but not a nested override of a flow or legacy property. The issue forbids exactly that partial fix ("any other Native_*").
5. **verified (by reading)**: an overriding member gets its own extern with the base's name. The extern name comes from the member's Kotlin `nativeName`, not from the owner (`CirNativeImports.kt:98-110` for properties, `:175-` for methods). The translator projects overriding methods onto the subclass with `isOverride = method.overridesBaseClassMember(...)` (`CirClassTranslator.kt:1559`). So `override val name` on `Apple` produces a second `Native_Get_name`. **inferred**: I did not confirm that the subclass's `properties` list contains the override, but the `Native_Get_*` clause in the issue body suggests it does.
6. **verified (by reading)**: the same problem is already solved a third time. A nested backing wrapper hides its owner's externs through `CirBacking.inheritedExternNames = ordinaryNativeImports().map { it.name }` when the owner is non-generic (`CirBackingRenderer.kt:155-159`, applied at `:101, :108, :115, :130`). That makes three copies of one rule.
7. **verified (by reading)**: generic exclusions. A generic class's externs are hoisted into a `${name}Native` carrier (`CirClassRenderer.kt:148-166`, the regex `EXTERN_DECLARATION` at `:176` already accepts `(?:new )?`). Externs hoisted out of a generic base or a generic subclass do not collide inside the class body. So hiding applies only when both the enclosing base and the subclass are non-generic, which mirrors `!generic` at `CirSealedRenderer.kt:214-216`. **inferred**: the case of a non-generic subclass of a generic enclosing base was not traced.
8. **verified (by reading)**: the translator holds the base as a `KSClassDeclaration` (`superClassDeclaration`, `CirClassTranslator.kt:711-713`), and `CirClass.superClass` is only a C# spelling string (`CirModel.kt:232`). So "is my base also my enclosing type" should be decided in the translator by declaration identity. Matching strings at render time is the fragile alternative.
9. **inferred (C# language rules, not spiked; no dotnet on this host)**: CS0108 needs the hidden member to be *accessible* at the hiding declaration. A private member of type X is accessible only inside X's body, nested types included. That gives these cases:
   - (a) Top-level sibling `class Apple : Fruit` does not see `Fruit`'s privates. `new` there is CS0109. The repo already relies on this split (`CirSealedRenderer.kt:209-213`).
   - (b) Multi-level nesting `class A { open class B : A() { class C : B() } }`: C is inside both B and A. It should hide B's names, and A's names too where B does not redeclare them.
   - (c) `class A { class B : A(); class C : B() }`: C is nested in A but not in B. B's privates are inaccessible, so they should not count. A's should count, inherited through B.

   I expect (b) and (c) from Roslyn's hidden-member lookup, which I believe skips inaccessible members and keeps walking up the base chain. **This is unverified and load-bearing for multi-level cases.** If it is wrong, case (c) gives CS0109 (a `new` that hides nothing) or a leftover CS0108. Either fails `TreatWarningsAsErrors`, so the error is loud, not silent. The Tier 1 compile cell below settles it.
10. **verified (by reading)**: `Tier1CSharpCompile` builds generated C# with `<TreatWarningsAsErrors>true</TreatWarningsAsErrors>` (`nuget-processor/src/test/kotlin/io/github/xxfast/kotlin/native/nuget/processor/tier1/Tier1CSharpCompile.kt:47`). It fails rather than skips when dotnet is missing (`:22-23`). The sealed-arm `new` precedent is `Tier1SealedBaseMemberTest` (`tier1/Tier1SealedBaseMemberTest.kt:26`, comment at `:144-146` naming GeneratedBindingsCheck). No test asserts the literal `private new static extern`: `grep "new static extern"` over `nuget-processor/src/test` finds nothing.
11. **verified (by grep)**: no `Fruit` or `Apple` type exists in `test-library/src` or `IntegrationTests/*.cs`. No `orchard` package exists under `test-library/src/nativeMain/kotlin/io/github/xxfast/kotlin/native/nuget/test/` (`orchestra` does).

## Recommendation

Put the hiding rule in one shared place and apply it from the ordinary class route too:

- Translator: give each `CirClass` the extern names it must hide, keyed by declaration identity. For a non-generic class, walk `superClassDeclaration`'s exported chain and keep each ancestor X that is a lexical (transitive) parent of the class and is non-generic. Record those ancestors. Recording a set of names directly would need the bases' CirClasses to be finished first.
- Renderer: `renderNestedDeclarations` takes the enclosing `CirClass` stack (`renderClassDeclaration` passes `cls` plus its own stack). For a nested `CirClass`, take the union of `externNames()` over the stack entries the translator recorded. Thread that set into `renderClassDeclaration` as `hidden: Set<String>`.
- One `CirClass.externNames(): Set<String>` that covers every route in Finding 2, not just `ordinaryNativeImports()`. Structural sites call `.hiding(hidden)`. Raw-string sites (flow, legacy, callbacks, bridge, CAS) take the set and print `new ` per name. Pricing that in: roughly six raw-route functions gain a parameter.
- Optional and in scope only if cheap: switch `CirSealedRenderer`'s `baseExternNames` and `CirClass.backing().inheritedExternNames` to the same `externNames()`, which closes the Finding 4 gap for sealed arms. Strictly this is a separate bug, so ask first (open question 2).

Alternatives rejected:
- `NoWarn CS0108`, un-nesting, skipping the subclass, unconditional `new` (CS0109 on siblings), or fixing only Create/Dispose: all forbidden by the issue.
- A post-render regex pass that inserts `new ` into the nested class's text: it would cover every route in one place, and `hoistDllImports` already parses externs this way. But it patches text instead of the model. Keep it as the fallback if the raw-route parameter threading gets too wide.
- Matching `CirClass.superClass` against the owner's name at render time: string spelling (`global::`, generic args, nested paths) makes this fragile.

## Files an implementation touches

- `nuget-processor/src/main/kotlin/io/github/xxfast/kotlin/native/nuget/processor/cir/CirModel.kt` (new `CirClass` field: hidden-base markers)
- `nuget-processor/src/main/kotlin/io/github/xxfast/kotlin/native/nuget/processor/cir/CirClassTranslator.kt` (compute it near `:711`)
- `nuget-processor/src/main/kotlin/io/github/xxfast/kotlin/native/nuget/processor/cir/CirClassRenderer.kt` (`renderClassDeclaration`, constructor, flow/legacy/callback/bridge/CAS extern renderers, dispose)
- `nuget-processor/src/main/kotlin/io/github/xxfast/kotlin/native/nuget/processor/cir/CirRenderer.kt` (`renderNestedDeclarations`, `renderDeclaration` signature)
- `nuget-processor/src/main/kotlin/io/github/xxfast/kotlin/native/nuget/processor/cir/CirNativeImports.kt` (`CirClass.externNames()`)
- optionally `CirSealedRenderer.kt:215`, `CirBackingRenderer.kt:156` (shared name set)
- new Tier 1 cell `nuget-processor/src/test/kotlin/io/github/xxfast/kotlin/native/nuget/processor/tier1/Tier1NestedOpenSubclassHidingTest.kt`
- fixture `test-library/src/nativeMain/kotlin/io/github/xxfast/kotlin/native/nuget/test/orchard/Orchard.kt` plus an `IntegrationTests/OrchardTests.cs` row. GeneratedBindingsCheck then compiles it with warnings as errors.
- `docs/adr/116-sealed-subclass-methods-on-the-callable-plan.md` amendment; `docs/topics/supported-features.md` row (documenter)

## Sample test

The Tier 1 cell is modelled on `Tier1SealedBaseMemberTest` and uses `Tier1CSharpCompile.assertCompiles`, so a CS0108 or CS0109 fails it:

```kotlin
val result = Tier1Harness.run(
  """
  package tier1.orchard

  open class Fruit(val name: String? = null) {
    open fun ripen(): String = "fruit"
    class Apple(name: String? = "apple") : Fruit(name) {
      override fun ripen(): String = "apple"
    }
    open class Stone(name: String? = "stone") : Fruit(name) {
      class Plum : Stone("plum")          // multi-level: hides Stone's and Fruit's
    }
    class Pear : Stone("pear")            // nested in Fruit, not in Stone (Finding 9c)
  }

  class Quince : Fruit("quince")          // top-level sibling: no `new` (CS0109 otherwise)
  """.trimIndent(),
)
Tier1CSharpCompile.assertCompiles(result, """
  using Interop;
  class Consumer { static string Run() {
    using Fruit.Apple apple = new Fruit.Apple();
    using Fruit.Stone.Plum plum = new Fruit.Stone.Plum();
    using Quince quince = new Quince();
    return apple.Ripen() + plum.Name + quince.Name;
  } }
""".trimIndent())
```

Then add declaration-line assertions with `///` lines excluded (CLAUDE.md): Apple's block contains `private new static extern IntPtr Native_Create(`, `private new static extern void Native_Dispose(` and `private new static extern ... Native_ripen`-shaped lines. `Quince`'s block contains no `new static extern`. Also add a flow-property or legacy-property override row so the raw routes are pinned too.

The xunit fixture row in `IntegrationTests` constructs `Fruit.Apple`, reads `Name == "apple"`, and disposes it.

## Deferred scope

- Hiding non-private (public or internal) base externs on top-level subclasses: none are known to exist on the class route. Out of scope.
- A nested subclass inside a generic enclosing base, and generic nested subclasses: their externs are hoisted to carriers (Finding 7), so no collision is expected. Not traced.
- Nested open subclasses inside sealed arms: different route, not traced.

## Open what-questions

1. Multi-level accessibility (Finding 9b/9c) is inferred and has not been spiked. Recommendation: the implementer writes the Tier 1 cell above first and lets the compiler settle it. If Roslyn turns out not to skip inaccessible members, case (c) needs no `new`.
2. Should the sealed-arm and backing-wrapper sets also widen to `externNames()` (Finding 4)? Recommendation: yes, only if a Tier 1 row shows the gap is real (a nested sealed arm overriding a flow or legacy property). Otherwise file it as a follow-up. Human decision: pending.
3. Structural threading into about six raw-route renderers, or one post-render textual pass? Recommendation: structural, per the repo's direction (ADR-111/116/148 lifts). Human decision: pending.
