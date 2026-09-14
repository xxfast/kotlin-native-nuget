# The abstract method walk hand-spells non-enum types by bare simple name

- ROADMAP: "The abstract **method** walk (`CirClassTranslator.kt`'s `else -> methodReturn` / `else -> kotlinType` arms near `abstractMethods`) still hand-spells a non-enum return or parameter type by its bare simple name, so an inherited abstract `fun` typed with an unexported or nested class dangles `CS0246` in the generated file instead of skipping named." (Phase 4, as of 2026-09-14)
- Researched: 2026-09-14, 6 of 15 minutes
- Restatement: forward. An abstract method (a class's own `abstract fun`, or one inherited from an interface or abstract base and never implemented) typed with a class at a parameter or return position renders fully qualified when the class is declared in C#, or is dropped with one named skip when it is not. Never a bare simple name.
- Verdict: fix. No ADR: the property walk settled the pattern on 2026-09-13 (ADR-075 amendment, classify then spell or refuse); this is the same pattern on the method side. ADR number 150 stays reserved and unused.

## Findings

All **verified by reading** unless marked otherwise. Line numbers are from `main` at `112475a1`.

### Which members reach the walk

- `cir/CirClassTranslator.kt:840-911` is the walk. It takes `normalMethods` minus `interfaceBridgeExcluded`, minus anything in `plannedMemberNames` (`:843-844`), and keeps only `method.isAbstract` (`:852`).
- Two shapes land here and nowhere else: a class's own `abstract fun` (plannable via `isDeclaredBy`, but the planner skips `ABSTRACT`, comment `:838-839`), and an inherited unimplemented `fun` (`isForwardPlannableMemberOf`, `forward/ForwardClassMembership.kt:278-284`, requires `hasImplementation()` for anything not declared by the class). Neither is classified anywhere else, so the walk is the only gate.

### Which positions are hand-spelled

- Return (`:871-886`): `methodReturn = declaration.simpleName` (`:871-872`), then `when`: enum via classifier (`:876`), `Unit`, `String`/`String?`, `KOTLIN_TO_CSHARP_RETURN` (`:880-883`), `isNullableReturn -> "$methodReturn?"` (`:885`), `else -> methodReturn` (`:886`).
- Parameter (`:888-899`): `kotlinType = declaration.simpleName` (`:889`), enum via classifier (`:895`), `string?` (`:896`), `KOTLIN_TO_CSHARP_PARAM` (`:897`), `else -> kotlinType` (`:898`).
- Only the enum position was moved to the classifier (2026-09-05, `classifiedEnum` `:244-248`, `undeclaredEnum` `:254-258`, `emitAbstractMethodEnumSkip` `:270-292`). Every other class-typed position is the bare `simpleName`.
- Extra defect at the parameter arm: nullability is only carried for `String` (`isNullableString`, `:891-892`). A `Foo?` parameter renders `Foo`. Not a compile error (nullable reference annotations warn, CS8610-class), but it drifts from the subclass override the planner spells as `Foo?`.

### What the generated C# is per cell (the `else` arms, bare `simpleName`)

The generated file carries only the `System*` usings (`cir/CirRenderer.kt:9-11` renders `file.usings`; the interface-spelling-sites memo pinned the list to System usings only), so a bare name resolves only inside the declaring class's own namespace.

| Cell | Kotlin | Today | Classifier would give | Label |
|---|---|---|---|---|
| (a) exported class, other package | `abstract fun describe(): other.Other` | `public abstract Other Describe();`: CS0246 unless `Other` happens to share the namespace | `BridgeType.ObjectHandle(csharpType = "global::Ns.Other.Other")` via `csharpTypeNameFor` (`forward/ForwardBridgeTypeClassifier.kt:461-472`, unconditional `global::` since issue #41) | verified by reading; CS code inferred |
| (b) nested declared class (ADR-133 nested, or ADR-009 sealed arm) | `abstract fun part(): Owner.Part` | `public abstract Part Part();`: CS0246, `Part` exists only as `Owner.Part` | `ObjectHandle(csharpType = "global::Ns.Owner.Part")`; `nestedCsName()` (`cir/CirTypeMapping.kt:181`) chains the owner | verified by reading; CS code inferred |
| (c) unexported class (out-of-scope package, klib dependency, or nested under an unexported owner) | `abstract fun lining(): hidden.Nesting.Lining` | `public abstract Lining Lining();`: CS0246, nothing declares it | `BridgeType.Unsupported` with `isUndeclaredClass` (`:275-282`) or `isUnexportedDependency` (`:295-305`); `skipReason()` maps these to `UNDECLARED_CLASS` / `UNEXPORTED_DEPENDENCY_TYPE` (`forward/ForwardCallablePlanner.kt:3740-3770`), both of which `toDiagnosticKind()` maps without `error()` (`forward/ForwardDiagnostic.kt:418-423`, `:447-460`) | verified by reading; ADR-075's fixture pins the `Lining` classification as `UNDECLARED_CLASS` on the property side |
| collection | `abstract fun items(): List<String>` | `public abstract List Items();`: CS0305/CS0246 (ADR-075 already notes this) | `Collection(LIST, String)` spells `IReadOnlyList<string>` (`forward/ForwardCsharpTypes.kt:33-46`) | verified by reading |
| `Instant`/`Duration`/`Uuid` | `abstract fun at(): Instant` | `public abstract Instant At();`: CS0246 | `global::System.DateTimeOffset` etc. (`ForwardCsharpTypes.kt:18-24`) | verified by reading |
| `Char` return | `abstract fun initial(): Char` | `public abstract Char Initial();` (`Char` is not in `KOTLIN_TO_CSHARP_RETURN`, `CirTypeMapping.kt:25-39`); resolves through `using System;` | `char` | inferred that `using System;` is among the usings |
| lambda / Flow / generic / suspend lambda | `abstract fun on(f: (Int) -> Unit)` | `public abstract void On(Function1 f);`: CS0246 | `SpecializedProtocol`, unspellable: `forwardPublicCsharpType()` `error()`s (`ForwardCsharpTypes.kt:49`) and `toDiagnosticKind()` `error()`s on `GENERIC`/`FLOW_PROTOCOL`/`CALLBACK_PROTOCOL` (`ForwardDiagnostic.kt:470-480`) | verified by reading |

### What the property side does (the pattern to copy)

- `inheritedAbstractProperty` (`:377-408`) reads the C# type off the ADR-113 plan; on a miss for an unexported interface owner, `emitInheritedAbstractPropertySkip` (`:313-352`) classifies the type itself (`classifier.classify(...).sealedAsHandle()`, `:326`), takes `skipReason()`/`skipDetail()`, and **hardcodes** the diagnostic kind (`SKIPPED_UNSUPPORTED_PROPERTY`) because `toDiagnosticKind()` `error()`s on legacy-route reasons a bare type can hold (`:340`, comment `:309-311`).
- There is no method-side ADR-113 catalog: `ForwardCallablePlanCatalog` has `propertyFor` (`ForwardCallablePlanner.kt:427`) and `classMethods`, nothing keyed for an interface method declaration. So the method walk cannot read a plan; it has to classify and spell directly, which is what `forwardBaseSpelling` (`:426-460`) already does for base type arguments.
- `sealedAsHandle()` (`ForwardCallablePlanner.kt:3392-3403`) unwraps an eligible sealed base to its `ObjectHandle`, so `abstract fun shape(): Shape` spells `global::Ns.Shape` rather than skipping as `SEALED_POSITION`. A bodiless declaration needs no marshalling, so the spelling is all that matters.

## Recommendation

Route both arms through the classifier, one file. In the walk (`:853-899`):

1. Replace `classifiedEnum` with `classifier.classify(type).sealedAsHandle()` at the return and at every parameter (still after `expandAliases()`).
2. A `BridgeType` is spellable when `forwardPublicCsharpType()` has an arm for it: `Unit`, `Primitive`, `Char`, `String`, `Instant`, `Duration`, `Uuid`, `ObjectHandle`, `Interface`, `Enum`, `ValueClass`, `Collection` (components recursively), `Nullable` of any of those. Write that as an explicit `BridgeType.isPubliclySpellable()` predicate next to `forwardPublicCsharpType()` in `ForwardCsharpTypes.kt` rather than catching its `error()`. `BoundInterface` is deliberately not in the list (ADR-088 defers that position; `skipReason()` already names it `BOUND_INTERFACE_POSITION`).
3. Spellable: `returnType = bridge.forwardPublicCsharpType()`, `paramType` likewise. This deletes the `String`/`KOTLIN_TO_CSHARP_RETURN`/`KOTLIN_TO_CSHARP_PARAM`/`isNullableString` hand maps from the walk (`:877-886`, `:891-898`) and fixes the `Foo?` parameter drift for free (`Nullable` renders its own `?`).
4. Not spellable: generalise `emitAbstractMethodEnumSkip` (`:270-292`) to `emitAbstractMethodSkip`: `reason = bridge.skipReason() ?: UNSUPPORTED`, `detail = bridge.skipDetail()`, and the kind chosen positionally and hardcoded, exactly as the property side does: `SKIPPED_UNSUPPORTED_RETURN` for the return, `SKIPPED_UNSUPPORTED_INPUT` for a parameter, except that the enum and class/dependency reasons keep going through `reason.toDiagnosticKind(position)` so the shipped `UNDECLARED_ENUM` wording (`SKIPPED_UNSUPPORTED_TYPE`) and the `SKIPPED_UNEXPORTED_DEPENDENCY_TYPE` remedy text are unchanged. Use `reason.ownsSentence(detail)` (`ForwardDiagnostic.kt`, ADR-064 amendment) to decide between the reason's sentence and a generic one, the same fork `emitInheritedAbstractPropertySkip` makes (`:330-347`). Drop the member.
5. Delete `classifiedEnum` and `undeclaredEnum` (`:237-258`) once nothing calls them.

Files: `nuget-processor/.../cir/CirClassTranslator.kt` (the walk and the skip helper, net deletion), `forward/ForwardCsharpTypes.kt` (the predicate). Nothing in the renderer, exports, or runtime changes: an abstract C# method has no `DllImport` and no Kotlin export.

Rejected:
- A method-side ADR-113 declaration catalog (plan the interface method, read the plan): correct in principle, but it is a new planner pass over every interface supertype for a route that has no invocation, and the property side only did it because a plan already existed. Direct classification is what `forwardBaseSpelling` does at the same site.
- Keep the hand maps and only add `nestedCsName()` (the ADR-118 move at `:1600-1606`): fixes (b) only, still CS0246 on (a) and (c), and still no named skip.
- Fail the build on an unspellable type (what `forwardBaseSpelling` does): wrong here, a base class must exist but an abstract member is droppable, and dropping named is what every other walk does.

## Files an implementation touches

- `nuget-processor/src/main/kotlin/.../cir/CirClassTranslator.kt` (walk `:840-911`, skip helper `:237-292`)
- `nuget-processor/src/main/kotlin/.../forward/ForwardCsharpTypes.kt` (`isPubliclySpellable()`)
- `nuget-processor/src/test/kotlin/.../tier1/Tier1AbstractMethodTest.kt` (three new cells, below)
- `test-library/src/nativeMain/kotlin/.../garage/Vehicle.kt` (add a cross-package and a nested-typed abstract fun so `GeneratedBindingsCheck` compiles the (a)/(b) spelling for real; Tier 1 does not compile C#, `Tier1StructuralInteropCsTest.kt:8-14`), plus a `hidden/`-package type for (c) in the style of `hidden/Nesting.kt`
- `IntegrationTests/AbstractMethodTests.cs` or the existing abstract-method test file: one assertion per declared cell (`Assert.IsType<Other.Other>(truck.Describe())`), optional but cheap; the load-bearing proof is the compile
- `FEATURES.md`, `ROADMAP.md` line, ADR-075 "Not fixed, noted" paragraph (docs, documenter)
- No `LeakTests` row: no new handle kind, no marshalling path

## Sample test

Tier 1 (`Tier1AbstractMethodTest`, `processorOptions = mapOf("nuget.rootPackage" to "tier1.garage")`):

```kotlin
package tier1.garage.other
class Other(val tag: String = "x")

package tier1.garage
import tier1.garage.other.Other
class Owner { class Part(val n: Int = 1) }
abstract class Vehicle {
  abstract fun describe(): Other          // (a)
  abstract fun part(p: Owner.Part): Owner.Part   // (b)
  abstract fun lining(): tier1.hidden.Nesting.Lining   // (c), package outside rootPackage
}
class Truck : Vehicle() {
  override fun describe(): Other = Other()
  override fun part(p: Owner.Part): Owner.Part = p
  override fun lining(): tier1.hidden.Nesting.Lining = tier1.hidden.Nesting.Lining()
}
```

Assertions: `Regex("""public abstract global::[\w.]*Other\.Other Describe\(\);""")` (the `Tier1NestedTypesTest.kt:516` shape), `Regex("""public abstract global::[\w.]*Owner\.Part Part\(global::[\w.]*Owner\.Part p\);""")`, `assertFalse("Lining Lining(" in csharp)`, and exactly one `kspWarnings` entry containing `SKIPPED_UNSUPPORTED_RETURN` (or the class reason's kind, see open question 2) naming `Vehicle.lining`. `result.compiledClean` for the Kotlin side. The C# compile proof is the test-library fixture under `GeneratedBindingsCheck` (`scripts/verify.sh`).

## Deferred scope

- `BoundInterface` at an abstract position stays a named skip (ADR-088 v1 boundary).
- A `suspend`, Flow-returning or lambda-taking abstract fun never reaches this walk (partitioned off at `:769-782`); those routes' own abstract handling is out of scope here.
- The `abstract fun` on a generic base (`Crate<T>.describe(tag: T)`) is the separate VERIFIED ROADMAP item above this one; `TYPE_PARAMETER` classification here must skip named, not spell `T`.
- The sibling hand-spelling in the plain-async route (`:1600-1606`, `nestedCsName()` fallback, bare cross-namespace) is a different route with a body and marshalling; not touched.

## Open what-questions

1. Resolved, verified by reading: `normalMethods` is what is left after `suspend` (`:769-770`), Flow/StateFlow returns (`:772-776`) and lambda parameters (`:778-782`) are partitioned off, and `filteredMethods` already dropped anything `legacyRefusedParameter`/`legacyRefusedReturn` refused (`:760-764`). So the walk sees no suspend, Flow or lambda-typed abstract fun; the unspellable set that can still reach it is a generic protocol, `RawCollection`, `Throwable`, `BoundInterface`, an ineligible sealed base, an `object` position and the `Unsupported` family.
2. Kind for the (c) cell: `reason.toDiagnosticKind(position)` gives `SKIPPED_UNSUPPORTED_TYPE` for `UNDECLARED_CLASS` and `SKIPPED_UNEXPORTED_DEPENDENCY_TYPE` for the dependency case, which is what the enum skip already emits and what ADR-109's remedy text keys on. Recommendation: keep `toDiagnosticKind(position)` for every reason it accepts and hardcode the positional kind only for the legacy-route reasons it `error()`s on. Decide before writing the Tier 1 assertion.
3. Whether `hidden`-package classification in Tier 1 lands on `UNDECLARED_CLASS` (nested under unexported owner) or `UNSUPPORTED` ("not in the exported object-handle set", `:296-305` with `isUnexportedDependency = false`). ADR-075's property fixture says `UNDECLARED_CLASS` for `Nesting.Lining`; inferred the same here since the classifier is position-agnostic.
