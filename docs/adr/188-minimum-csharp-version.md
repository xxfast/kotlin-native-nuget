# ADR-188: Forward, the generated C# states one minimum language version that matches the default target framework

## Status
Accepted. Decided at the human gate on 2026-10-02; implemented in the same stack.

## Context

The generated C# ships as source in `contentFiles/cs/any/` (`nuget-plugin/.../PackNugetTask.kt:21,105`, verified), so it compiles at the consumer's `LangVersion`. `GOALS.md:8` states ".NET 8+ and C# 12+", enforced by `GeneratedBindingsCheck/GeneratedBindingsCheck.csproj:11-12` and `NugetCompileInteropTask.kt:89-90` (both net8.0, `LangVersion 12.0`, verified). ADR-013 maps Kotlin extension properties to `GetXxx()`/`SetXxx()` and defers C# 14 extension properties "with a major version bump" (`docs/adr/013-extension-property-mapping.md:80`). The parallel item makes the target framework configurable (ADR-184, `ROADMAP.md:16`); default `LangVersion` follows the TFM.

Verified by spike (SDK 10.0.301, scratch dir, full output in `docs/research/roadmap/minimum-csharp-version.md`):

- Today's generated forward code compiles at `LangVersion` 10, 11 and 12; at 9 the only language error is `record structs` (46 sites). Caveat: 6 member bodies referencing an unbound reverse type were not feature-checked; the fixtures were 2026-09-30 build outputs.
- An `extension(Cat cat) { ... }` block fails at the net8.0 default (`CS1022`, `CS1513`) and builds at net8.0 with `LangVersion=14`.
- `GetIsKitten(this Cat)` and the C# 14 `IsKitten` property coexist in one static class as `GetIsKitten(Cat)` and `get_IsKitten(Cat)`: adding the property is additive; raising the floor is what breaks.

Inferred (vendor page, fetched 2026-10-02): .NET 8 and .NET 9 reach end of support on 2026-11-10; .NET 10 (default C# 14) runs to 2028-11-14.

## Alternatives Considered

### 1. Floor equals the default TFM's default `LangVersion`: net10.0 and C# 14 (chosen)

With `net10.0` default: floor C# 14, extension properties become C# 14 extension properties in 0.9.0 and `GetXxx`/`SetXxx` are removed. With `net8.0` default: floor C# 12 for all of 1.x, extension members are a 2.0 item. Either way one number, enforced by the two compile checks.

### 2. Lock C# 12 regardless of the default TFM

Safe for every net8.0 consumer, but locks 1.x to the language of a framework that is out of support five weeks from now, and pushes ADR-013's and ADR-006's C# 14 mappings to 2.0.

### 3. Require C# 14 on a net8.0 default via a `build/<id>.props` that sets `LangVersion`

Rejected: changes the consumer's whole project language, and a `LangVersion` above the TFM default is unsupported by Microsoft (inferred, docs).

### 4. Raise the floor in a 1.x minor when needed

Rejected: under source shipping it is a compile break for every consumer below the new floor.

### 5. Advertise the de facto floor (C# 10)

Rejected: nothing gains, and every new emission would need re-checking against 10.

## Decision

Decided at the gate (2026-10-02):

- The default TFM is `net10.0` (ADR-184 owns the mechanics) and the minimum C# version is C# 14, its default `LangVersion`. It is stated in `GOALS.md` and the 1.0 stability policy, and enforced by `GeneratedBindingsCheck` and `nugetCompileInterop` (both move to net10.0, `LangVersion 14`).
- Kotlin extension properties map to C# 14 `extension` blocks now, in the 0.9.0 breaking window, superseding ADR-013's property shape.
- `GetXxx()`/`SetXxx()` are removed outright, with no `[Obsolete]` release.
- ADR-006's static enum members are not in scope.
- The floor only rises in a major release.
- An extension property that shares its C# name with an extension function on the same receiver declaration (`val Cat?.nameOrStray` beside `fun Cat?.nameOrStray()`) is skipped, and the function keeps the name. Both declare in one static class, but C# 14 makes every `cat.NameOrStray` access ambiguous (CS9339). The skip is named `SHADOWED_BY_EXTENSION_FUNCTION`, reported under `SKIPPED_UNSUPPORTED_PROPERTY` as `SHADOWED_BY_MEMBER` is. Its hint names `@CSharpName` (ADR-179), which keeps both; the generator never invents the second name (ADR-110). The match is on the receiver declaration and on the C# name, so a `@CSharpName` on either side separates them. Nullability is ignored for a reference-type receiver only; for a value-type receiver `fun Int.label()` beside `val Int?.label` does not clash, since C# sees `int` and `int?` as two receivers (ADR-132 amendment 2026-10-04).

Shape:

```csharp
public static class CatExtensions
{
    extension(Cat cat)
    {
        public bool IsKitten => ...;
        public string Label { get => ...; set => ...; }
    }
}
// consumer: if (cat.IsKitten) cat.Label = "small";
```

## Consequences

- ADR-013 is superseded for the property shape. ADR-006's static enum members (`Mood.Fallback()`) become expressible on the C# 14 floor but are out of scope here. `GeneratedBindingsCheck`, `NugetCompileInteropTask` and its test move to the new TFM and `LangVersion`. A consumer on net8.0 must move to net10.0 (or raise its own `LangVersion`, unsupported); the generator keeps no C# 12 fallback mapping.
- Breaking for consumers: any call to a generated `GetXxx()`/`SetXxx()` extension method becomes a property access; any consumer compiling below C# 14 (net8.0/net9.0 default) fails to compile the package's source.
- The documented disambiguated form is the lowered static accessor: `CatExtensions.get_IsKitten(cat)` and `CatExtensions.set_Label(cat, value)` (`Extensions.get_X(receiver)` / `set_X(receiver, value)`), which a consumer calls when two imported namespaces both declare `X` for the same receiver. `IntegrationTests/ExtensionNamespaceTests.cs` pins it.
- An extension property and an extension function of one C# name on one receiver no longer both reach C#: the property is the one skipped (`SHADOWED_BY_EXTENSION_FUNCTION`). Under ADR-013 they coexisted as `GetX()` and `X()`.
- The Kotlin exports and the `DllImport` set are unchanged (`{receiver}_get_{name}` / `_set_{name}`, `Native_*GetX`): only the public C# member changes, so the ADR-055 contract check needs nothing new.

## Amendment 2026-10-03: the property/function clash is per C# extension class

The `SHADOWED_BY_EXTENSION_FUNCTION` skip is keyed on `(extension namespace, receiver declaration, C# name)`,
not on `(receiver declaration, C# name)`. The namespace comes from the rule the translator uses to place an
extension (`extensionNamespace`, shared with the planner): the receiver's package when the receiver is
exported, the declaring package otherwise. This corrects the Decision bullet above, which reads as if the
match ignored packages.

- Exported receiver (`Cat`): extensions from every package merge into one `CatExtensions`, so the skip
  stays, across packages too.
- Unexported receiver (`String`): each package gets its own `StringExtensions`, so `val String.tag` in
  one package and `fun String.tag()` in another both bind. A consumer file that imports both namespaces
  gets CS9339 on the property member syntax only and uses `get_Tag`, the escape documented above.
- Same package: still skipped, named once.

Evidence. Verified: `Tier1ExtensionPropertyFunctionClashTest` has six cells (the unexported two-package
cell failed before and passes after, the two controls are unchanged); `:nuget-processor:test` passed
(1553); `scripts/verify.sh` is green, where one xunit file imports each namespace and calls the property
or the function. The research spike with SDK 10 showed that importing both namespaces gives CS9339 on
`"x".Tag` only. No LeakTests row, since `String` crosses without a handle.

## Amendment 2026-10-03: member functions beside an extension property, and the nullable-receiver twin

Two legal Kotlin pairs were unverified. Both are now handled.

- Member function beside an extension property. The `SHADOWED_BY_EXTENSION_FUNCTION` skip also fires
  when the receiver's enum, class, interface or value class declares a member function with the same
  C# name, at any arity (the suspend `Async` spelling counts on a class). The key is C#'s resolution,
  so a nullable receiver is no exemption, unlike `SHADOWED_BY_MEMBER`. An enum member function renders
  `Name(this Enum ...)` in the same `{Enum}Extensions` class, giving CS9339 with no parameters and
  CS1061 with any; an instance method makes `cat.Name` a method group (CS0428). The property is
  skipped, the function keeps the name, and the warning names the kind (for example "the enum member function `Coat.grooming`" or
  "the member function `Cat.grooming`"). A suspend or generic enum function is a named
  drop on its own route and never renders, so it is no clash. Behaviour change: the zero-parameter enum
  case was a fatal `ERROR_CSHARP_SIGNATURE_COLLISION` from the translator and is now this warning,
  uniform with the extension-function rule. The `NugetDiagnostics.json` detail for the code now carries
  the kind ("extension function `tag`") instead of the bare name.
- `val Cat.x` beside `val Cat?.x`. Kotlin compiles the pair; C# rejects it at declaration (CS0102).
  This holds for a reference-type receiver only: a value-type pair (`val Int.x` beside `val Int?.x`)
  binds both (ADR-132 amendment 2026-10-04), and the sentences below describe the reference case.
  The plan symbol and the C entry point derive from the receiver declaration with nullability
  dropped, so both planned as one symbol and generation died with `ERROR_INTERNAL_GENERATOR_FAILURE`
  blaming expect/actual, reported twice. It is now a fatal `ERROR_CSHARP_SIGNATURE_COLLISION`
  (skip reason `NULLABLE_RECEIVER_TWIN`) naming both declarations, hint "rename one of them in
  Kotlin". `@CSharpName` cannot separate them. Neither twin is a safe survivor: dropping either
  changes the value one receiver type reads. Letting `@CSharpName` separate them would mean carrying
  nullability in the symbol and export, and is not done.

Known imprecision: twins are grouped by plan symbol, so two same-named receivers from two packages,
both extended in a third package, would get the twin message. That case hit the internal failure
before, and their C entry points collide either way.

Evidence. Verified: `Tier1EnumMemberExtensionNameClashTest` (two cells, zero and one parameter) and
`Tier1ExtensionPropertyFunctionClashTest` (three cells: class member function, value-class member
function, nullable twin) were each red before and green after; `:nuget-processor:test` passed (1558).
The C# compiler behaviour (CS9339, CS1061, CS0428, CS0102) was verified by the research spike with
SDK 10, not by a compile in this change. Processor-only; the native pipeline was not run for this
item. No LeakTests row.
