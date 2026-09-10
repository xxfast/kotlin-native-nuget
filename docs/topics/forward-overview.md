# Publishing Kotlin to C#

The forward direction takes a Kotlin/Native library and generates a C# API for it. You write Kotlin, the plugin ships a `.nupkg` a C# consumer can reference directly, no code generation step on their side.

## Pipeline

At build time:

1. **KSP discovers public declarations.** The KSP processor (`nuget-processor/`) walks every public class, function, and property in the compiled Kotlin/Native source set.
2. **Ordinary sync callables are planned once.** Each ordinary synchronous function, method, constructor, property, companion, object method, extension, and value-class member is classified into a `BridgeType` and validated as a `ForwardCallablePlan` or `ForwardPropertyPlan` ([ADR-062](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/062-forward-callable-plan.md)). Specialized protocols (suspend, `Flow`, lambda/callback, sealed helpers, generic declaration families) stay on named legacy routes; a sealed helper is the one exception at a **property** position, where the property planner now plans it directly instead ([ADR-105](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/105-sealed-property-position.md)): a sealed **return** still rides the legacy route unchanged. See [Interfaces, abstract classes, and sealed classes: Limitations](interfaces-abstract-sealed.md#limitations) for exactly which sealed positions still skip.
3. **Dual projection from the plan.** The same plan projects to CIR for C# ([ADR-004](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/004-cir-intermediate-representation.md)) and to KotlinPoet `@CName` exports. A generation-time ABI contract check ([ADR-055](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/055-forward-abi-contract-check.md)) compares both halves to the plan. The same check also covers the specialized legacy routes ([ADR-078](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/078-forward-abi-legacy-contract-coverage.md)): since their `DllImport` declarations are raw renderer text rather than plan output, they are collected straight from the rendered `Interop.cs` and normalized the same way before being compared against their Kotlin `@CName` exports.
4. **`CirRenderer` emits `Interop.cs`.** The C# source is generated once, at Kotlin build time, and shipped inside the package. There is no consumer-side codegen step, unlike the `ClangSharpPInvokeGenerator`-based approach from earlier phases (see [ADR-001](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/001-csharp-codegen-in-consumer.md)).
5. **KotlinPoet emits `Bridges.kt`.** Kotlin-side `@CName` export wrappers are generated so every bridged declaration has a stable C ABI entry point.
6. **Kotlin/Native compiles and links** shared libraries for each target platform.
7. **`packNuget` packages** the generated C#, the native binaries, and metadata into a `.nupkg`.

```
Gradle Plugin (Kotlin side)                NuGet Package       C# Consumer
┌───────────────────────────────────┐     ┌────────────────┐     ┌──────────────┐
│ Compile Kotlin/Native             │     │ native libs    │     │ Add package  │
│ KSP → plan → CIR → Interop.cs     │────>│ Interop.cs     │────>│ Build        │
│        ↘ KotlinPoet → Bridges.kt  │     └────────────────┘     │ Run          │
│ Link shared libraries             │                            └──────────────┘
│ Package as .nupkg                 │
└───────────────────────────────────┘
```

## Memory model

Kotlin/Native's GC and the .NET GC know nothing about each other. Every object that crosses the bridge needs an explicit ownership story:

- **Primitives** are copied by value. No ownership concern.
- **Strings** cross as UTF-8 `const char*`, copied immediately into a managed `string` via `Marshal.PtrToStringUTF8`. The pointer is never cached.
- **Objects** are pinned Kotlin-side with `StableRef.create(...)`, which returns an opaque `COpaquePointer`. The generated C# wrapper stores that pointer as `_handle` and implements `IDisposable`; disposing releases the `StableRef`. See [ADR-003](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/003-memory-management-across-bridge.md).

Every time an object-typed property or return value crosses the bridge, the generated code creates a **new wrapper** around a **new `StableRef`** rather than caching or reusing an existing one. This mirrors how Kotlin/Native's ObjC and Swift exports behave. Identity is not preserved (`cat.Brother != cat.Brother` even when both point at the same Kotlin object), and disposing one wrapper never cascades to another. See [Classes and objects](classes-and-objects.md) and [ADR-005](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/005-object-return-semantics.md) for the concrete generated shape.

## What ships in the `.nupkg`

Running `packNuget` for `test-library` produces this layout:

```
TestLibrary.1.0.0/
├── contentFiles/cs/any/Interop.cs
└── runtimes/
    ├── osx-arm64/native/
    └── win-x64/native/
```

- **`contentFiles/cs/any/Interop.cs`**: the generated C# source, compiled directly into the consumer's own project (not a separate assembly). This is why the generated code has no external dependency beyond the .NET BCL: it becomes part of the consumer's compilation unit.
- **`runtimes/{rid}/native/`**: the compiled Kotlin/Native shared libraries, one per supported target (`osx-arm64`, `win-x64`, ...). The .NET runtime resolves the correct native asset for the host RID automatically via `[DllImport]`.

No consumer-side build step, SDK, or tool is required beyond referencing the package.

## Package layout and namespace mapping

The Gradle DSL configures the root of the generated namespace tree:

```kotlin
nuget {
  publish {
    packageId = "MyCatLib"
    version = "1.0.0"
    authors = "yourname"
    description = "My Kotlin/Native library"
    rootPackage = "com.example.cats"
  }
}
```

Kotlin sub-packages map relative to `rootPackage`, and the C# namespace root is the package's `packageId`. In `test-library`, `rootPackage = "io.github.xxfast.kotlin.native.nuget.test"`, so:

| Kotlin package | C# namespace |
|---|---|
| `io.github.xxfast.kotlin.native.nuget.test` | `TestLibrary` |
| `io.github.xxfast.kotlin.native.nuget.test.cat` | `TestLibrary.Cat` |
| `io.github.xxfast.kotlin.native.nuget.test.math` | `TestLibrary.Math` |
| `io.github.xxfast.kotlin.native.nuget.test.mime` | `TestLibrary.Mime` |

Every generated declaration lands under its mapped namespace inside the single `Interop.cs` file.

By default every public declaration in the module is bridged, not only those under `rootPackage`.
`publish { include(...); exclude(...) }` narrows that to an explicit package-prefix allowlist, and
when `include` is left empty, `rootPackage` itself becomes the default scope.

The export set is not limited to the module's own files, either: it is a reachability closure that
also walks into types declared in a dependency Gradle module (return types, parameter types,
property types, type arguments of `Flow<T>`/collections, sealed subclasses, primary-constructor
parameters), admitting each discovered type through the same `include`/`exclude`/`rootPackage`
predicate. See [The nuget {} DSL](nuget-dsl.md) for the full predicate and the cross-module closure
rules.

<note>
    <p>
        Naming a top-level exported declaration (an <code>object</code>, a class, a static-class
        file) the same as its own package's last segment produces a C# namespace whose simple name
        matches a type inside it, for example a package <code>com.example.parlour</code> alongside
        an object named <code>Parlour</code>. C# resolves an unqualified reference in that shape
        ambiguously (<code>CS0118</code>, "is a namespace but is used like a type") unless the
        consumer fully qualifies it. Pick a name that doesn't repeat the containing package.
    </p>
</note>

## Diagnostics

Not every Kotlin construct can be expressed as C#. When the generator meets one it cannot bridge,
it names the member and the reason, at the author's own Kotlin source, rather than emitting invalid
Kotlin or a C# API whose signature lies about its contract. Every diagnostic carries a
`ForwardDiagnosticKind` whose name encodes its severity:

- **`SKIPPED_*`**: the member is warned about and omitted entirely from the generated C# API.
  Generation continues. This is the default for a construct the forward direction cannot express
  (an unsupported type, a `Map`/`Set` parameter, an unsupported generic/suspend combination, a
  value-class member a supertype declares, whether inherited, delegated or overridden).
- **`INFO_*`**: the member still binds, under a documented assumption (for example, `out`/`in`
  variance on a class type parameter is dropped, but the member still generates).
- **`ERROR_*`**: generation fails and `CNameExports.kt` (the Kotlin `@CName` export file) is never
  written, so `packNuget` never runs. Cases include two constructors, or two methods on one class,
  that render an identical C# signature
  ([ADR-034](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/034-secondary-constructor-exceptions.md)),
  named `ERROR_CSHARP_SIGNATURE_COLLISION`. This also catches two constructors that differ only in
  *reference*-type nullability (`constructor(from: Patient)` next to `constructor(from: Patient?)`):
  C# does not treat a nullable reference annotation as part of a method's signature, so both would
  otherwise render, correctly but uncompilably, as `Referral(Patient from)` and
  `Referral(Patient? from)` (`CS0111`). Nullable **value** types are unaffected and keep working:
  `constructor(n: Int)` next to `constructor(n: Int?)` render genuinely distinct signatures and are
  not treated as a collision. A second case is a top-level `val` and `fun` that PascalCase to the
  same C# name (`ERROR_CSHARP_NAME_COLLISION`, CS0102); see
  [Top-level declarations](top-level-declarations.md) for that one. This page covers the third:
  `ERROR_C_ENTRY_POINT_COLLISION`, two *different* Kotlin declarations deriving the same underlying C
  entry point; see [Two declarations can't share one C entry point](#entry-point-collision) below.

A `List`/`Map`/`Set` parameter with an unsupported element/key/value type (see
[Collections](collections.md)) is skipped like this, naming the component that failed rather than the
collection kind:

```
[nuget:SKIPPED_UNSUPPORTED_INPUT] Skipping Patient.setMoods: its COLLECTION type combination is not
    supported. the value type Collection? cannot be written into a Kotlin collection; use components
    that are primitives, Char, String, enums, exported class handles, value classes over those, or
    non-null nested collections of the same
    at Fixture.kt:4
```

A property whose declared type the property planner has no getter/setter shape for is skipped the
same way, naming the property's own type. `Cat.unsupported: Sequence<String>` is the fixture:

```
[nuget:SKIPPED_UNSUPPORTED_PROPERTY] Skipping Cat.unsupported: its type generic declaration
    kotlin.sequences.Sequence has no property getter or setter shape. expose a bridgeable property
    (or a getter function) whose type is not generic declaration kotlin.sequences.Sequence, and
    export that instead
    at Cat.kt:46
```

A collection property is skipped the same way when one of its components (the element, or a map
key or value) has no C# spelling. A sealed **class** component no longer falls into this bucket:
since [ADR-105](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/105-sealed-property-position.md)
it binds, materialised through the ADR-009 `FromHandle` discriminator (see
[Sealed types as property types](interfaces-abstract-sealed.md#sealed-types-as-property-types)). A
sealed **interface** component still has no C# spelling to bind against, because only a sealed
*class* gets a `FromHandle` discriminator, so it still skips, naming the offending component:

```
[nuget:SKIPPED_UNSUPPORTED_PROPERTY] Skipping tier1.sealedcollectionproperty.Album.filters: its type
    Collection (element type sealed helper tier1.sealedcollectionproperty.Filter) has no property
    getter or setter shape. expose a bridgeable property (or a getter function) whose type is not
    Collection (element type sealed helper tier1.sealedcollectionproperty.Filter), and export that
    instead
```

`SKIPPED_UNSUPPORTED_PROPERTY` never fires for a property whose type is a lambda, suspend lambda,
`Flow`, or `StateFlow` (nullable or not): those are unplannable by design and still bind through a
named legacy route, so warning would tell a consumer a working property had vanished.

The same kind also fires when an extension property's *receiver* type, not its declared type, is
what the planner can't wire. `String`, a primitive, `ObjectHandle` classes, and a value class over
any of the four underlyings admitted at ordinary positions (`String`, a primitive, an enum, or
`ObjectHandle`) are the supported receivers; anything else warns and the property is dropped
entirely, naming the receiver rather than the property's own (usually fine) type:

```
[nuget:SKIPPED_UNSUPPORTED_PROPERTY] Skipping tier1.skipreceiver.Box.label: its extension receiver
    type generic declaration tier1.skipreceiver.Box is not a supported extension-property receiver.
    declare the property on a class, String, primitive, or value class receiver, or expose a
    top-level getter function instead
    at <file>:<line>
```

The message names the declaration, the reason, an actionable hint, and, when KSP can resolve it,
the file and line of the Kotlin declaration that was skipped, something the reverse direction's
`RirDiagnostic` cannot carry, since it works from compiled metadata rather than source. See each
forward page's own **Limitations** section for which named diagnostic fires where.

A member typed with an enum that is never declared in C#, a nested `enum class`, skips named too,
with the `SKIPPED_UNSUPPORTED_TYPE` kind naming the `UNDECLARED_ENUM` reason instead of being
spelled as a dangling reference; see [Enums: Nested enums skip named](enums.md#nested-enums-skip-named).
A nested `class` or `object` gets the same treatment, naming `UNDECLARED_CLASS` instead (a nested
`interface` was already `UNDECLARED_INTERFACE`, see [Interfaces, abstract and sealed classes: Nested
interfaces skip named](interfaces-abstract-sealed.md#nested-interfaces-skip-named)); see
[Classes and objects: Nested classes and objects](classes-and-objects.md#nested-classes-and-objects).

Three more kinds cover the cross-module export closure ([ADR-066](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/066-forward-export-reachability-closure.md);
see [The nuget {} DSL](nuget-dsl.md) for the closure's own rules). A reachable dependency-module type
outside the effective `include`/`rootPackage` scope is skipped, naming the exact fix, from
`test-library`'s `Newsroom.sponsor(): Advertisement` (`dev.other.core.Advertisement` sits outside
`rootPackage`):

```
[nuget:SKIPPED_UNEXPORTED_DEPENDENCY_TYPE] Skipping Newsroom.sponsor: its type
    `dev.other.core.Advertisement` is declared in a dependency module outside the export scope.
    add include("io.github.xxfast.kotlin.native.nuget.test", "dev.other.core") to
    nuget { publish { } } (an explicit include replaces the rootPackage default, so keep your own
    packages listed), or expose a type from an in-scope package instead
    at Newsroom.kt:66
```

The hint names the whole `include(...)` line, the current scope first, because an explicit `include`
replaces the `rootPackage` default rather than adding to it ([#55](https://github.com/xxfast/kotlin-native-nuget/issues/55)).
A `kotlin.*`/`kotlinx.*` type gets no `include(...)` suggestion at all: a stdlib type wants a
first-class mapping, not an export-scope change, and the hint says so.

`include(...)` is only ever the right fix for a type the closure simply never included. Three other
reasons the closure can refuse a dependency type all fold into the same
`SKIPPED_UNEXPORTED_DEPENDENCY_TYPE` kind but each get their own hint, since `include(...)` would be
wrong advice for any of them. Following [ADR-109](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/109-duplicate-type-hazard.md)'s
own `exclude("<pkg>")` remedy for a duplicated type lands here:

```
[nuget:SKIPPED_UNEXPORTED_DEPENDENCY_TYPE] Skipping Newsroom.latest(): "dep.models" is excluded by
    exclude("dep.models") in nuget { publish { } }, so a callable reaching dep.models.TopStory is
    skipped by design; remove the exclude to export it here (include(...) cannot override an
    exclude)
```

With neither `rootPackage` nor `include` set, so the closure never crosses the module boundary at
all, the hint names the setting that turns cross-module admission on instead:

```
[nuget:SKIPPED_UNEXPORTED_DEPENDENCY_TYPE] Skipping Newsroom.latest(): no rootPackage or include is
    set, so nuget { publish { } } never crosses the module boundary and dep.models.TopStory stays
    out of the export set; set rootPackage(...) or list your own packages alongside "dep.models" in
    include(...) (include(...) on its own replaces the everything-in-this-module default and would
    drop your own files)
```

And a dependency's own `expect` declaration says its actualization lives in that module and cannot
be reached with `include(...)` at all, naming the type instead of suggesting a scope change that
cannot fix it. See [The nuget {} DSL](nuget-dsl.md#cross-module-export-closure) for the full set of
refusal reasons.

When the scope admits none of the module's public declarations, the processor warns once with
`SKIPPED_ALL_DECLARATIONS`, naming the scope and the packages it dropped, instead of returning
silently with no `Interop.cs` in the package:

```
[nuget:SKIPPED_ALL_DECLARATIONS] Skipping DemoNative: the export scope (include("kotlin"),
    rootPackage = "sample") admits none of the module's 3 public declaration(s) in package(s)
    "sample", so no Interop.cs is generated. an explicit include replaces the rootPackage default
    rather than adding to it: list your own package(s) in include(...) as well, or drop include(...)
    to fall back to rootPackage
```

When at least one dependency-module type *is* admitted, the closure also emits one aggregate
`INFO_EXPORTED_FROM_DEPENDENCY` line per KSP run rather than one line per type, naming the whole
admitted set.

### Duplicate-type hazard across two published packages {id="duplicate-type-hazard"}

Two Gradle modules can each publish forward and each independently admit the same dependency-module
type through their own reachability closure. Neither KSP run can see the other's export scope, so
each package would silently declare its own unrelated C# copy of the same Kotlin type
([ADR-109](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/109-duplicate-type-hazard.md)).
The plugin closes that visibility gap: every forward publisher's `include`/`exclude`/`rootPackage`
predicate is lowered into a `nuget.publishedScopes` KSP option (see
[The nuget {} DSL](nuget-dsl.md#cross-module-export-closure) for the wire format), and the processor
matches every admitted dependency type against every *other* publisher's scope, by package, since a
cross-module declaration carries no module identity. A match warns with
`WARNING_DUPLICATED_DEPENDENCY_TYPE`. Nothing is skipped and the generated output does not change,
so the message says "Duplicating", not the severity-keyed "Skipping" every other warning gets:

```
[nuget:WARNING_DUPLICATED_DEPENDENCY_TYPE] Duplicating dep.models.TopStory: the export closure
    admitted it from a dependency module, and the OtherLib NuGet package's export scope
    (rootPackage/include "dep.models") also covers it, so OtherLib declares its own copy (certainly
    if it is one of OtherLib's own types, otherwise whenever OtherLib's API reaches it) and a
    consumer referencing both packages sees two unrelated C# types for one Kotlin type, with no
    conversion between them. Kotlin objects cannot cross between two native libraries, so export it
    from exactly one package: publish a single umbrella module that depends on both, or add
    exclude("dep.models") to nuget { publish { } } here so only OtherLib declares it (callables
    reaching it are then skipped with SKIPPED_UNEXPORTED_DEPENDENCY_TYPE)
```

<note>
<p>This exact line is assembled from the message template plus the fixture values pinned by
<code>Tier1DuplicatedDependencyTypeTest.kt</code> (a real two-publisher run stays silent by design:
the single-publisher <code>test-library</code> real build only ever sees its own entry, dropped at
parse time, so it never fires this warning). A cross-module declaration has no source location, so
the message never carries an <code>at &lt;file&gt;:&lt;line&gt;</code> line.</p>
</note>

The two workable remedies are the two the hint names: publish a single umbrella module that depends
on both instead of two separate publishers, or `exclude("<pkg>")` from one of them so only the other
declares it (the excluded module's own callables reaching that type then skip named with
`SKIPPED_UNEXPORTED_DEPENDENCY_TYPE`). A shared "models" NuGet package both publishers depend on is
**not** a remedy: two published packages are two separate Kotlin/Native runtimes in the consumer's
process, so a handle minted in one is meaningless to the other's exports.

A related but distinct case: the reachability closure never walks a class's supertypes at all, so
an exported class implementing an interface declared outside the export set, the Koin
`KoinComponent` shape, used to render a base-list entry ("`: IKoinComponent`") that nothing ever
generates. `Issue42Api : Issue42Component` is the fixture: `Issue42Component` lives in a separate
Gradle module the export set never admits.

```
[nuget:SKIPPED_UNEXPORTED_SUPERTYPE] Skipping Issue42Api : Issue42Component: supertype
    'dev.other.core.Issue42Component' is not in the export set, so it has no generated C#
    interface; the class is generated without it and its own members still export. an unexported
    supertype carries no members the C# side could call, so nothing is lost; note that
    include("...") does not help here — the export reachability closure never walks supertypes
    at Issue42Api.kt:15
```

Unlike `SKIPPED_UNEXPORTED_DEPENDENCY_TYPE`, the hint does not point at `include(...)`: the closure
has no edge for supertypes, so admitting the dependency package changes nothing. The interface is
dropped from the generated base list; the class's own members, and any defaulted member the
interface declares, still export:

```C#
public class Issue42Api : IDisposable, INugetHandle
{
    /* ... */
    public string ComponentTag()  // Issue42Component's defaulted method, still bound on the class
    {
        /* ... */
    }
}
```

An unexported **base class** dangles the same way, and is covered too: `class Issue42Derived :
UnexportedBase()`, where `UnexportedBase` lives outside the export set, used to render `public
class Issue42Derived : UnexportedBase` and fail CS0246. A base class carries real callable members,
unlike a dropped interface, so the fix is a little different: the base is dropped from the C# base
list entirely (`Issue42Derived` gets no base at all, just `IDisposable, INugetHandle`), and
`UnexportedBase`'s own public members are bound directly on `Issue42Derived`, with no `override`.

```
[nuget:SKIPPED_UNEXPORTED_SUPERTYPE] Skipping Issue42Derived : UnexportedBase: base class
    'dev.other.core.UnexportedBase' is not in the export set, so it has no generated C# class;
    Issue42Derived is generated with no base at all and the base's public members are bound on
    Issue42Derived directly. nothing callable is lost — UnexportedBase's public members export as
    members of Issue42Derived — but C# sees no UnexportedBase type and no inheritance relation, so
    `is`/`as` against it and any other subclass's shared base are gone; to keep the base itself it
    has to enter the export set on its own: include("dev.other.core") admits a base declared in
    this module, but not one from a dependency — the export reachability closure never walks
    supertypes
    at Issue42Derived.kt:16
```

```C#
// before: public class Issue42Derived : UnexportedBase   -> CS0246
public class Issue42Derived : IDisposable, INugetHandle
{
    public Issue42Derived() { /* ... */ }
    public string Label { get; }   // UnexportedBase's own property, bound on the class
    public string Own() { /* ... */ }
    public string Greet(string name) { /* ... */ }  // UnexportedBase's own method, bound on the class
}
```

The hint hedges rather than promising a fix: `include(...)` admits a base declared in the same
module, but not one reached only as a supertype from a dependency, since the ADR-066 reachability
closure never walks supertypes either way.

### Annotation classes skip named {id="annotation-classes-skip-named"}

A public `annotation class` has no route in the forward direction at all: there is no C# projection
of a Kotlin annotation worth generating, so it always vanishes from the generated C#. Until
[ADR-064](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/064-forward-unsupported-declaration-diagnostics.md)'s
2026-09-07 amendment that vanishing was silent; now it skips named with `SKIPPED_ANNOTATION_CLASS`,
once per public top-level annotation class:

```
[nuget:SKIPPED_ANNOTATION_CLASS] Skipping io.github.xxfast.kotlin.native.nuget.test.cat.Tagged:
    annotation classes are not bridged; there is no C# projection of a Kotlin annotation, so nothing
    is generated for it. usages of it on exported declarations are unaffected. Make it internal, or
    exclude(...) its package, if the warning is unwanted
    at Tagged.kt:19
```

Applying the annotation to an exported declaration costs that declaration nothing: the forward
pipeline reads no annotation but `kotlin.native.CName`, so `@Tagged("plaything") data class
Toy(...)` still generates its constructor, properties, `Copy`, `Equals`, `HashCode` and `ToString`
exactly as if the annotation weren't there. An `internal`/`private` annotation class stays silent,
matching every other bucket's visibility gate. An `expect annotation class` fires the same kind
once, on the `actual`: the `isExpect` filter drops the `expect` half one line earlier, so only the
`actual` reaches the bucket, with `symbol` pointing at the actual's own file.

### Opt-in-marked declarations skip named {id="opt-in-marked-declarations-skip-named"}

A declaration carrying its own `@RequiresOptIn`-meta-annotated marker is not part of the
forward-exported surface, at any `RequiresOptIn.Level`
([ADR-115](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/115-opt-in-marker-declarations.md)).
C# has no way to honour a Kotlin opt-in requirement: a Kotlin consumer of a marked declaration must
acknowledge it with `@OptIn` or a compiler flag, while a C# consumer of the generated binding would
see a plain public member with no signal at all. Exporting a marked declaration always erases the
marker's purpose, so it is dropped instead and named with `SKIPPED_OPT_IN_MARKER`, once per dropped
declaration, regardless of whether the marker is `ERROR`- or `WARNING`-level.

A marked class, object, interface, enum, or value class is never declared at all, refused at the
same place a package-scope refusal is, so nothing in the generated `Interop.cs` names it. A marked
member of an exported class, or a marked top-level function or property, skips per-callable
instead, so the owning type still generates with everything else intact. A member whose *type* is a
marked class carries its own reason, blaming the type rather than the member, since no
`include(...)` change can ever bring a marked type into scope.

From `test-library/.../issue113/Issue113Sample.kt`:

```kotlin
@RequiresOptIn(level = RequiresOptIn.Level.ERROR, message = "Litter bookkeeping, not a public API")
annotation class InternalApi

data class Litter(
  val name: String,
  @property:InternalApi val extra: String,
  @LedgerApi val other: String,
) {
  @set:InternalApi
  var viaSetter: String = "unset"
}

@InternalApi
class HouseRules(val motto: String) {
  fun describe(): String = "rules:$motto"
}
```

`HouseRules` never appears in the generated C# at all. `Litter.Extra`, `Litter.Other`, and
`Litter.ViaSetter` (the whole property, not just its setter) are all absent, while `Litter.Name`
generates normally:

```
[nuget:SKIPPED_OPT_IN_MARKER] Skipping io.github.xxfast.kotlin.native.nuget.test.issue113.Litter.extra:
    it is marked with the opt-in marker
    `io.github.xxfast.kotlin.native.nuget.test.issue113.InternalApi`. a C# consumer has no way to
    opt in, so an opt-in-required declaration is not exported; remove the
    `io.github.xxfast.kotlin.native.nuget.test.issue113.InternalApi` annotation from it if it is
    meant to be part of the C# API, or leave it marked if it is library-internal
    at Issue113Sample.kt:116
```

`@set:Marker` is the only accessor position that compiles at all (`@get:` and `@field:` are
frontend errors, `Opt-in requirement marker annotation cannot be used on getter`/`... on field`, so
there is nothing to bridge there in the first place); it is invisible on the property declaration
itself and readable only off the setter, so it drops the whole property rather than exporting it
get-only.

A member whose type is marked blames the type instead, since no `include(...)` change can ever
bring a marked type into scope. `Shelter.rules()` returns `HouseRules`, and the marker sits on
`HouseRules` itself, not on `rules()`:

```
[nuget:SKIPPED_OPT_IN_MARKER] Skipping io.github.xxfast.kotlin.native.nuget.test.issue113.Shelter.rules:
    its type `io.github.xxfast.kotlin.native.nuget.test.issue113.HouseRules` is marked with an
    opt-in marker. no C# type is declared for
    `io.github.xxfast.kotlin.native.nuget.test.issue113.HouseRules` (opt-in marker
    `io.github.xxfast.kotlin.native.nuget.test.issue113.InternalApi`), so every member typed with it
    is skipped rather than emitted as a dangling reference; remove the marker from
    `io.github.xxfast.kotlin.native.nuget.test.issue113.HouseRules`, or expose a type that is not
    opt-in-required instead
```

A marked primary-constructor `val` follows one invariant: the marked declaration never appears in a
C# signature. A trailing marked parameter with a default keeps the shorter constructor overload
that already omits it ([ADR-091](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/091-constructor-default-parameters.md)'s
own omitting-overload machinery); an undefaulted or non-trailing one has no such overload, so the
constructor itself is dropped, `copy` along with it, and the class stays reachable only through a
Kotlin factory, with `WARNING_NO_PUBLIC_CONSTRUCTOR` naming `OPT_IN_MARKER` among the reasons. See
[Classes and objects: No public constructor](classes-and-objects.md#no-public-constructor).

A marker declared one Gradle module away resolves the same way: `Cattery.crossModuleName`, marked
with `CatteryInternalApi` from `:test-models`, is absent from the generated C# exactly like a
module-local marker would be. `@OptIn(InternalApi::class)` on a declaration is a marker *consumer*,
not a marker member, and stays exported; `@SubclassOptInRequired` is not itself
`@RequiresOptIn`-meta-annotated, so it does not match and stays exported either.

### An opt-in-marked parameter *type* takes every arity with it {id="opt-in-marked-parameter-type-every-arity"}

A trailing defaulted parameter whose own *type* is opt-in-marked is a stronger case than a marked
property or default target: it makes every arity of the constructor or function illegal, not only
the declared one, so the trailing-omitting overload above cannot repair it either. Kotlin propagates
the opt-in requirement from a callee's declared parameter types, at every arity, and never from what
a default expression happens to read, so a `data class` whose sole parameter is a marked-typed
default reduces to zero callable arities. Every arity is skipped `OPT_IN_MARKER_TYPE` (still rendered
as `SKIPPED_OPT_IN_MARKER`), and the class ends up factory-only; see [Classes and objects: No public
constructor](classes-and-objects.md#no-public-constructor).

From `test-library/.../issue128/Issue128Sample.kt`, where `Grooming` is an opt-in-marked enum
declared one module away in `:test-models`:

```kotlin
@OptIn(CatteryInternalApi::class)
data class GroomingPlan(
  val name: String = "Oreo",
  val grooming: Grooming = Grooming.DAILY,
)
```

```
[nuget:SKIPPED_OPT_IN_MARKER] Skipping ...GroomingPlan.<init>: its type
    `...Grooming` is marked with an opt-in marker. no C# type is declared for `...Grooming`
    (opt-in marker `...CatteryInternalApi`), so every member typed with it is skipped rather than
    emitted as a dangling reference; remove the marker from `...Grooming`, or expose a type that is
    not opt-in-required instead

[nuget:WARNING_NO_PUBLIC_CONSTRUCTOR] Keeping GroomingPlan: every public constructor is skipped
    (<init>: OPT_IN_MARKER_TYPE, <init>_2: OPT_IN_MARKER_TYPE, <init>_3: OPT_IN_MARKER_TYPE), so the
    generated C# class has only its internal handle constructor and C# cannot construct one.
```

`GroomingPlan` still generates, keeps `Name`, and carries only its internal handle constructor. The
class line also carries the same detail as an XML-escaped `<remarks>` doc comment, so a consumer
sees it as an IDE tooltip
([ADR-064](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/064-forward-unsupported-declaration-diagnostics.md)
2026-09-10 amendment):

```C#
/// <remarks>
/// Cannot be constructed from C#: every Kotlin constructor of GroomingPlan was skipped by the bridge (&lt;init&gt;: OPT_IN_MARKER_TYPE, &lt;init&gt;_2: OPT_IN_MARKER_TYPE, &lt;init&gt;_3: OPT_IN_MARKER_TYPE). Instances come from Kotlin factories that return this type.
/// </remarks>
public class GroomingPlan : IDisposable, INugetHandle
{
    internal IntPtr _handle;

    internal GroomingPlan(IntPtr handle)
    {
        _handle = handle;
    }
```

The same check applies to a function's trailing defaulted parameters, not only constructors: a
top-level `fun schedule(name: String = "Oreo", grooming: Grooming = Grooming.DAILY)` synthesizes no
omitting overload either, and `schedule` is absent from the generated C# at every arity.

### A class with no reachable constructor stays, and says so {id="no-reachable-constructor"}

A class whose every public constructor is skipped, for any reason, still generates a C# type,
`exportedTypes` admits a class by declaration, not by constructor outcome, and a Kotlin factory
returning the class hands C# a usable instance regardless. What used to be silent is the class
carrying only its `internal Foo(IntPtr handle)` constructor, with nothing in the build log
explaining why. Since
[ADR-064](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/064-forward-unsupported-declaration-diagnostics.md)'s
2026-09-07 amendment, this warns once per class with `WARNING_NO_PUBLIC_CONSTRUCTOR`, its verb
"Keeping" rather than the usual "Skipping" since the class itself is not skipped:

```
[nuget:WARNING_NO_PUBLIC_CONSTRUCTOR] Keeping Issue56Failure: every public constructor is skipped
    (<init>: NULLABLE), so the generated C# class has only its internal handle constructor and C#
    cannot construct one. the type is kept because instances can still come from Kotlin factories
    that return it (a top-level function, or a companion factory); expose one, or change the
    constructor parameters to types the bridge can express
    at Issue56Sample.kt:41
```

The warning still only reaches the library author's Gradle log and `NugetDiagnostics.json`; on its
own, the 2026-09-07 amendment left a consumer opening `Issue56Failure` with no explanation anywhere
in the assembly or IntelliSense. ADR-064's 2026-09-10 amendment closes that: the same skipped-
constructor detail the warning names is also emitted as an XML-escaped `/// <remarks>` doc comment
directly above the class line, using consumer-facing wording rather than the diagnostic's
author-facing hint, so the constraint is visible as an IDE tooltip too. See
[Classes and objects: No public constructor](classes-and-objects.md#no-public-constructor) for the
rendered block.

Fires for every skip reason a constructor can go for, including a legacy-route deferral that never
reaches `droppedCallables` on its own (no legacy route re-emits a constructor, so that family was
silent in every channel before this amendment). A sealed type at a parameter position used to be
one such reason, see [A sealed type at a parameter position now binds](#sealed-position-now-binds)
below. Not fired for an abstract class or the interface-return backing wrapper, neither of which is
handle-less by accident. See
[Classes and objects: No public constructor](classes-and-objects.md#no-public-constructor) for the
full `Issue56Failure` shape.

### A sealed type at a parameter position now binds {id="sealed-position-now-binds"}

A sealed type at a bare, nullable, or collection-component parameter position (including a
constructor parameter) used to be dropped completely silently: the `ForwardPlanSkipReason` that
covered it, `SEALED_PROTOCOL`, was a `droppedFromCSharp = false` legacy-route deferral on the
assumption some other route re-emitted it, but no route ever did for a parameter.
[ADR-064](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/064-forward-unsupported-declaration-diagnostics.md)'s
2026-09-07 amendment first renamed the reason `SEALED_POSITION`, flipped `droppedFromCSharp` to
`true`, and added `SKIPPED_SEALED_POSITION` so the skip was at least named; then
[ADR-105](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/105-sealed-property-position.md)'s
2026-09-07 amendment bridged the position itself, sharing the same `sealedAsHandle()` rewrite the
property planner and the sealed-return route use. `SKIPPED_SEALED_POSITION` still fires, but only
for a sealed type with no generated `FromHandle` discriminator at all: a sealed **interface**, or a
sealed class outside the export scope. See
[Interfaces, abstract classes, and sealed classes: A sealed type at a parameter position](interfaces-abstract-sealed.md#a-sealed-type-at-a-parameter-position).

### Two declarations can't share one C entry point {id="entry-point-collision"}

The native ABI is one flat namespace of `@CName`-exported C functions, and the export symbol is
derived from the Kotlin declaration's own (unqualified) name. Two declarations that resolve to the
same symbol used to abort `packNuget` with a raw `IllegalArgumentException` naming only the mangled
symbol (`radio_play_collect`), not which Kotlin declarations were fighting over it.
[ADR-117](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/117-forward-abi-collision-names-owning-declarations.md)
(issue [#106](https://github.com/xxfast/kotlin-native-nuget/issues/106)) replaced that with a named
`ERROR_C_ENTRY_POINT_COLLISION`, naming every owning declaration. Two same-simple-name classes in
different packages is the plainest trigger:

```kotlin
// package tier1.abicollision.a
class Kitten(val name: String)

// package tier1.abicollision.b
class Kitten(val name: String)
```

The shape below is reconstructed from `ForwardDiagnostic.format()` (`[nuget:<kind>] <verb>
<location>: <reason>. <hint><at>`) applied to the actual `ForwardAbiCollision.reason`/`.hint` text
`Tier1EntryPointCollisionTest` asserts against, with the temp-file paths elided:

```
[nuget:ERROR_C_ENTRY_POINT_COLLISION] Error tier1.abicollision.a.Kitten(String): Forward ABI
    duplicate C# import for kitten_create; 2 Kotlin declarations export the same C entry point:
      - tier1.abicollision.a.Kitten(String)
        at .../A.kt:3
      - tier1.abicollision.b.Kitten(String)
        at .../B.kt:3. The C entry point is derived from the unqualified simple name; rename one
    declaration. [kitten_create(in string, out pointer) -> pointer, kitten_create(in string, out pointer) -> pointer]
    at .../A.kt:3
```

(The constructor's C# import carries the ADR-031 error out-parameter alongside the `name` argument,
the same `out IntPtr error` shape [Constructor default parameters](classes-and-objects.md#ctordefaults-generated-c)
shows for `carrier_create`, which is why the bracketed signature pair reads `(in string, out pointer)`
rather than just `(in string)`.) The trailing `at` line echoes the first owner's own location again,
the same location `logger.error` attaches the diagnostic to; it is not a third declaration.

Owner naming has two granularities, depending on which universe the colliding export lives in. Every
plan-routed export (an ordinary constructor, a top-level function, a class method) and the `suspend`
legacy route name the exact declaration, with parameter types and `file:line`, as above. The
remaining legacy routes, a sealed discriminator, a `Flow` collector, and the generated `Dispose`,
name the owning top-level declaration instead (class-granular, since every export those routes
produce derives from that declaration's own prefix). `fun dispose()` on an exported class is this
shape: it collides with the always-generated `IDisposable.Dispose()` export, and the message names
the method plus a `(route-owned export: ...)` marker for the generated side, again reconstructed from
the same test's dispose cell:

```
  - tier1.abicollision.dispose.Closer.dispose()
    at .../Closer.kt:4
  - tier1.abicollision.dispose.Closer (route-owned export: the generated Dispose, a
    suspend/Flow/sealed export, or another legacy route)
    at .../Closer.kt:3
```

The hint is always the same: rename one of the colliding declarations. The prefix scheme itself
(unqualified simple name, no package, no namespace) is unchanged; naming the collision is the interim
remedy, not a fix for it. See the
[open backlog item](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/backlog/two-exported-types-same-simple-name-different.md)
for the structural fix (qualifying the export prefix by package) that would close the collision
itself rather than only naming it.

### A nullable parameter names itself, instead of the return {id="nullable-parameter-names-itself"}

A callable dropped because one of its *parameters* is a nullable type with no wire used to render
as `SKIPPED_UNSUPPORTED_RETURN`, the same kind a nullable **return** with nowhere to put the absence
gets, and the hint said "at this position" without saying which one. An author reading that message
went looking at the return type, which was perfectly exportable, before finding the actual problem
was a parameter. [ADR-064](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/064-forward-unsupported-declaration-diagnostics.md)'s
2026-09-09 amendment (issue [#131](https://github.com/xxfast/kotlin-native-nuget/issues/131)) gives
the skip its own position: an input-position `NULLABLE` now renders `SKIPPED_UNSUPPORTED_INPUT` and
names the parameter, a return-position one keeps `SKIPPED_UNSUPPORTED_RETURN` and its unnamed hint
(there is no `BridgeType`-to-Kotlin-spelling renderer to name a return's type):

```
[nuget:SKIPPED_UNSUPPORTED_INPUT] Skipping io.github.xxfast.kotlin.native.nuget.test.issue131.hubWithEvents: its parameter `events` has a nullable type with no supported wire. the nullable parameter `events` has no wire at an input position; expose a non-nullable wrapper, or a separate has-value/value pair, instead
    at .../issue131/HubSample.kt:47
```

An extension receiver counts as an input position too, unnamed, since it has no author-written
parameter name. A nullable exported class handle at a parameter position was never actually
unsupported, on any route (`null` rides `IntPtr.Zero`); see
[Classes and objects: A nullable class handle parameter](classes-and-objects.md#nullable-handle-parameter)
for the shape that does bind.

### Where these messages appear

A diagnostic computed at generation time is only useful if it reaches the console. The processor
writes every accumulated diagnostic to `NugetDiagnostics.json`, a declared KSP task output
([ADR-100](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/100-forward-diagnostic-delivery.md)).
Being a declared output, not just something printed during the task action, is what makes it survive
an incremental build: it is *restored* on a cache hit and present after an `UP-TO-DATE` run, the two
outcomes a normal, unchanged `packNuget` produces on every run after the first. A
`NugetReportDiagnosticsTask` ahead of `packNuget` reads that file and re-emits every message through
Gradle's own warning logger, so a skip is visible on every `packNuget`, cached or not, not only the
build where KSP happened to run. This is a real `packNuget --console=plain` run against this
repository's own fixture, KSP task `UP-TO-DATE`:

```
> Task :test-library:kspKotlinMacosArm64 UP-TO-DATE

> Task :test-library:nugetReportDiagnostics
[nuget:INFO_EXPORTED_FROM_DEPENDENCY] Note TestLibraryNative: the export closure admitted 6 type(s) from dependency modules: io.github.xxfast.kotlin.native.nuget.test.models.Byline, io.github.xxfast.kotlin.native.nuget.test.models.Purr, io.github.xxfast.kotlin.native.nuget.test.models.StoryCode, io.github.xxfast.kotlin.native.nuget.test.models.StoryUri, io.github.xxfast.kotlin.native.nuget.test.models.TopStory, io.github.xxfast.kotlin.native.nuget.test.models.Whisker. these are generated exactly like module-local types; narrow with exclude(...) if any of them should not be part of the public API
[nuget:SKIPPED_UNEXPORTED_DEPENDENCY_TYPE] Skipping io.github.xxfast.kotlin.native.nuget.test.Newsroom.sponsor: its type `dev.other.core.Advertisement` is declared in a dependency module outside the export scope. add include("io.github.xxfast.kotlin.native.nuget.test", "dev.other.core") to nuget { publish { } } (an explicit include replaces the rootPackage default, so keep your own packages listed), or expose a type from an in-scope package instead
    at /Users/xxfast/Developer/XXFAST/KMP/kotlin-native-nuget/test-library/src/nativeMain/kotlin/io/github/xxfast/kotlin/native/nuget/test/Newsroom.kt:66
[nuget:SKIPPED_INHERITED_MEMBER] Skipping io.github.xxfast.kotlin.native.nuget.test.models.StoryUri.length: it is a value class member that a supertype declares. a value class never exports a member a supertype declares, whether inherited, delegated (`by`) or explicitly overridden (ADR-082); call the supertype's API through the struct's underlying property from C#, or declare a member under a name or signature no supertype declares
[nuget:SKIPPED_INHERITED_MEMBER] Skipping io.github.xxfast.kotlin.native.nuget.test.models.StoryUri.get: it is a value class member that a supertype declares. a value class never exports a member a supertype declares, whether inherited, delegated (`by`) or explicitly overridden (ADR-082); call the supertype's API through the struct's underlying property from C#, or declare a member under a name or signature no supertype declares
[nuget:SKIPPED_INHERITED_MEMBER] Skipping io.github.xxfast.kotlin.native.nuget.test.models.StoryUri.subSequence: it is a value class member that a supertype declares. a value class never exports a member a supertype declares, whether inherited, delegated (`by`) or explicitly overridden (ADR-082); call the supertype's API through the struct's underlying property from C#, or declare a member under a name or signature no supertype declares
[nuget:SKIPPED_UNSUPPORTED_PROPERTY] Skipping io.github.xxfast.kotlin.native.nuget.test.cat.Cat.unsupported: its type generic declaration kotlin.sequences.Sequence has no property getter or setter shape. expose a bridgeable property (or a getter function) whose type is not generic declaration kotlin.sequences.Sequence, and export that instead
    at /Users/xxfast/Developer/XXFAST/KMP/kotlin-native-nuget/test-library/src/nativeMain/kotlin/io/github/xxfast/kotlin/native/nuget/test/cat/Cat.kt:46
```

<note>
<p>Before ADR-100, this exact set of six diagnostics was computed correctly but reached nobody: the
KSP stdout channel never surfaced in the Gradle console (a Worker API stdout-attribution gap), and
even when it did, a normal, unchanged <code>packNuget</code> reports the KSP task
<code>FROM-CACHE</code> then <code>UP-TO-DATE</code> on consecutive runs, so a transport that only
speaks during a task action was silent on every build after the first. Nothing about which
declarations are skipped, or their severity, changed; only delivery did.</p>
</note>

## Limitations

<note>
<p>Another Kotlin compiler plugin on the same target's <code>kotlinCompilerPluginClasspath</code>
can crash <b>link time</b> for a <code>sharedLib</code>, even though ordinary compilation succeeds.
This is not a bug in this plugin; it is the pre-existing Kotlin/Native backend issue
<a href="https://youtrack.jetbrains.com/issue/KT-62984">KT-62984</a>, and it is triggered by any
plugin that generates IR without a klib origin, not by anything this project's KSP processor
emits.</p>
<p>The crash is a <code>NullPointerException</code> in <code>CAdapterCodegen.buildCAdapter</code>
during C-export codegen, which every forward-direction target hits because every forward target
links a <code>sharedLib</code>. Marking the affected declarations <code>internal</code> does not
help, since the crashing IR belongs to the other plugin, not to user code. See
<a href="publish-kotlin-library-as-nuget.md">Publish a Kotlin/Native library as NuGet</a> for the
symptom and the classpath-exclusion workaround.</p>
</note>

### AOT and trimming {id="aot-and-trimming"}

The generated bindings are AOT-safe.

The generics bridge's reflection, the first blocker
([ADR-038](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/038-aot-compilation.md), Deferred),
is fixed: [ADR-094](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/094-reflection-free-generic-dispatch.md)
(Accepted) replaced the type-erased generics bridge's `GetField`/`Activator.CreateInstance`
reflection with a static factory registry. A build against a pre-ADR-094 version of these bindings
(plugin 0.2.0) could still trigger the .NET trimmer's `IL2075` on that `GetField`, mitigated with
`<TrimmerRootAssembly>` on the project compiling `Interop.cs`; on the current generator this should
no longer fire for that reason.

The second blocker, the forward callback surface, is fixed too
([ADR-102](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/102-aot-safe-forward-callbacks.md),
Accepted). Every place C# calls back into Kotlin, a per-call lambda parameter, a stored callback, a
C#-implemented interface bridge, `Flow`/`StateFlow` collection, and `suspend` continuation
resumption, used to hand Kotlin a pointer obtained from `Marshal.GetFunctionPointerForDelegate`,
which needs the runtime to JIT a native-to-managed thunk the first time it is invoked from native
code. A fully AOT-compiled runtime has none, which is what threw `ExecutionEngineException` on a
Mono full-AOT Mac Catalyst build the first time a generated `Flow` was collected. The generated C#
now dispatches every one of those shapes through a static `[UnmanagedCallersOnly]` thunk (one per
delegate shape, `NugetThunks`), keyed off a `GCHandle` ctx pointer every callback ABI already
threaded through unused. Kotlin needs no change:

```C#
internal static unsafe partial class NugetThunks
{
    [UnmanagedCallersOnly(CallConvs = new[] { typeof(global::System.Runtime.CompilerServices.CallConvCdecl) })]
    internal static IntPtr NugetStringStringCallbackThunk(IntPtr a0, IntPtr a1)
    {
        try
        {
            return ((NugetStringStringCallback)GCHandle.FromIntPtr(a1).Target!)(a0, a1);
        }
        catch (Exception ex)
        {
            Environment.FailFast("nuget: unhandled exception in NugetStringStringCallback", ex);
            return default;
        }
    }

    internal static IntPtr NugetStringStringCallbackPtr =>
        (IntPtr)(delegate* unmanaged[Cdecl]<IntPtr, IntPtr, IntPtr>)&NugetStringStringCallbackThunk;
}
```

<note>
<p>An exception escaping a user callback (a per-call lambda, a bridge slot implementation) inside
one of these thunks is process-fatal by design: every thunk body catches and calls
<code>Environment.FailFast</code> rather than letting the exception tear through the native frame
undefined. This matches the de-facto behaviour before this change. A real error-channel ABI
(out-parameters on callback signatures, Kotlin-side rethrow) is future work, see
<a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/ROADMAP.md">ROADMAP.md</a>.</p>
</note>

<note>
<p>CoreCLR NativeAOT (<code>PublishAot=true</code>) was measured working on <code>win-x64</code>
even before this change: ILC pre-generates reverse-P/Invoke marshalling stubs for delegate types it
can root statically, so NativeAOT consumers were never actually broken, only resting on an
undocumented ILC implementation detail rather than the documented
<code>[UnmanagedCallersOnly]</code> contract. This change moves the generated code onto that
contract. <code>AotSmokeTest/</code> publishes and runs all five callback shapes under
<code>PublishAot=true</code> as a permanent CI regression lane: <code>win-x64</code> is verified
locally through the new thunks, <code>osx-arm64</code> is a CI-only lane executing for the first
time in CI.</p>
<p>The Mono full-AOT failure (Catalyst/iOS Release) is what this change actually fixes.
<code>&lt;MtouchInterpreter&gt;-all&lt;/MtouchInterpreter&gt;</code> is expected to no longer be
required on Catalyst/iOS Release builds, but the original Catalyst repro has not yet been re-run
without the flag to confirm the fix end to end on that runtime; keep the flag set until that manual
re-verification lands, tracked in
<a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/ROADMAP.md">ROADMAP.md</a>.</p>
</note>

<seealso>
    <category ref="related">
        <a href="classes-and-objects.md">Classes and objects</a>
    </category>
    <category ref="external">
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/001-csharp-codegen-in-consumer.md">ADR-001: C# codegen in the consumer</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/003-memory-management-across-bridge.md">ADR-003: Memory management across the bridge</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/004-cir-intermediate-representation.md">ADR-004: CIR intermediate representation</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/005-object-return-semantics.md">ADR-005: Object return semantics</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/036-reverse-interop-mechanism.md">ADR-036: Reverse interop mechanism</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/038-aot-compilation.md">ADR-038: NativeAOT compatibility</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/041-kotlin-to-csharp-call-mechanism.md">ADR-041: Kotlin to C# call mechanism</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/055-forward-abi-contract-check.md">ADR-055: Forward ABI contract check</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/062-forward-callable-plan.md">ADR-062: Forward callable plan</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/063-forward-declaration-level-export-scoping.md">ADR-063: Forward declaration-level export scoping</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/064-forward-unsupported-declaration-diagnostics.md">ADR-064: Forward unsupported-declaration diagnostics</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/066-forward-export-reachability-closure.md">ADR-066: Forward export reachability closure</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/078-forward-abi-legacy-contract-coverage.md">ADR-078: Forward ABI legacy contract coverage</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/094-reflection-free-generic-dispatch.md">ADR-094: Reflection-free generic dispatch</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/100-forward-diagnostic-delivery.md">ADR-100: Forward diagnostic delivery</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/101-unexported-supertype-skip.md">ADR-101: Forward, unexported supertype skip</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/102-aot-safe-forward-callbacks.md">ADR-102: AOT-safe forward callbacks</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/109-duplicate-type-hazard.md">ADR-109: Forward, duplicate-type hazard across two published packages</a>
        <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/115-opt-in-marker-declarations.md">ADR-115: Opt-in-marker declarations are out of the exported surface</a>
    </category>
</seealso>
