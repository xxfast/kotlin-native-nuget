# Methods declared on a generic class bind on the C# generic carrier

- ROADMAP: "**VERIFIED by execution:** a function declared on a generic base (`Crate<T>.describe(tag: T)`) has no C# carrier, no Kotlin export and no diagnostic; `translateGenericClass` (`CirClassTranslator.kt` ~:1020-1097) and `GenericClassExports.kt` project properties only. Pinned by `GenericBaseOverloadTests.GenericBase_CarriesNoDescribeOfItsOwn`, which must be flipped deliberately when this is closed." (Phase 4 line 36, as of 2026-09-14)
- Researched: 2026-09-14, 8 of 25 minutes, verified by reading (no spike)
- Restatement: forward. A method declared on a generic class (`class Crate<T> { fun describe(tag: T): String }`) binds on the C# generic carrier (`Crate<T>.Describe(T tag)`) through the existing boxed-handle wire, or is refused with a named skip; today it is silently absent.
- Verdict: fix. ADR-147 (`docs/adr/147-generic-class-methods.md`, Proposed).

## Findings

- Properties only: `translateGenericClass` (`cir/CirClassTranslator.kt:1028-1097`) walks `getAllProperties()` only; `CirGenericClass` (`cir/CirModel.kt:243-254`) has no `methods` field; `exports/GenericClassExports.kt` does the same walk. Generic classes never reach the ADR-062 planner (`NugetProcessor.kt:1097-1099` splits `classes` / `genericClasses`), so no diagnostic fires. The only `emit` in the function is `INFO_DROPPED_VARIANCE`.
- How `T` crosses today: one opaque handle, never per-width. Getter `item?.let { NugetHandles.retain(it) }` (`NugetRuntime.kt:51-54`); C# `FromHandle<T>` dispatches on `typeof(T)` to `nuget_unwrap_<width>` + dispose, else `Materialize<T>` (ADR-094 registry). The input half already ships for collection components: `NugetMarshal.Wrap<T>(value, out owned)` boxes through `nuget_wrap_<width>` or hands over a wrapper's `Handle`, with ADR-099's `finally { if (owned) Dispose }`. `create_object` already passes `value.asStableRef<Any>().get()` into a `T`-typed ctor parameter. Both halves exist on different routes; a method pairs them with no new wire, runtime export or `NugetMarshal` member.
- The generic top-level function route (`exports/GenericFunctionExports.kt`, `cir/CirFunctionTranslator.kt:740-910`) emits 13 typed-variant exports per function, single `T` position only. Rejected for methods (quadratic in `T` positions).
- ADR-072 is the reverse generics ADR; the forward generic route is ADR-010/015/016/094.
- Two pins flip, not one: `GenericBase_CarriesNoDescribeOfItsOwn` and `LabelledCrate_DeclaresExactlyOneDescribeTakingInt` (uses `GetMethods()` without `DeclaredOnly`, so it also sees the inherited `Describe(string)`; inferred from the reflection contract). `Tier1GenericBaseOverloadTest.kt` flips too.
- Related open backlog items, untouched: concrete-typed property mis-surfaced as `T`, `var` setter missing, nullable ctor arg, `FromHandle` no enum branch.

## Recommendation

`Crate<T>.Describe(T tag)`, `Crate<T>.Pick(T other)` as instance methods on the existing carrier; `LabelledCrate` inherits `Describe(string)` beside its own `Describe(int)`. Body: `Wrap<T>` in, `NugetErrorNative.Check` + `PtrToStringUTF8` / `FromHandle<T>` out, `finally` dispose when owned. Kotlin: one `@CName("crate_describe")` export, receiver `handle.asStableRef<Crate<Any?>>().get()`, argument `tag.asStableRef<Any>().get()`, ADR-032 error-out. Overloads use ADR-090 numbering (`crate_describe_2`).

v1 admits: declared, non-suspend, no own type parameters; positions are class type parameters (`T`, `T?`, any count), `String`/`String?`, primitive widths, `Unit` return; bounded `T` via the `boundQualified` shape. Refused named: own `<R>`, class/object/sealed/enum/value-class/collection/lambda/Flow/suspend positions, generic subclasses. Alternative 3 (move generic classes onto the ADR-062 plan with a `BridgeType.TypeParameter`) is the follow-up when non-`T` class positions are wanted.

## Files touched

Four generator files (`cir/CirClassTranslator.kt`, `cir/CirModel.kt`, `exports/GenericClassExports.kt`, the C# generic renderer) + `test-library` `Crate` fixture + `IntegrationTests/GenericBaseOverloadTests.cs` (two pins flip) + `Tier1GenericBaseOverloadTest.kt` + a `LeakTests` row (the route mints a boxed-argument handle and a result handle). Full price table in the ADR.

## Sample test

```csharp
[Fact]
public void GenericCrate_Describe_TakesTheClassTypeParameter()
{
    using var crate = new Crate<string>("kibble");
    Assert.Equal("kibble tagged fresh", crate.Describe("fresh"));
}
```

## Deferred scope

Own type parameters, non-`T` class positions (Alternative 3), generic subclasses.

## Open what-questions

- Alternative 3 now or as a follow-up? Recommendation: follow-up; the narrow closure ships the missing carrier without moving the route.
- Inferred, loud-failing if wrong: `asStableRef<Crate<Any?>>().get()` compiles without `UNCHECKED_CAST` and dispatches under erasure (fallback: star projection + suppressed cast); `GetMethods()` includes inherited public methods; the ABI contract check stays green with both sides added in one change.
