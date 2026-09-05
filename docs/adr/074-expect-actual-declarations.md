# ADR-074: Forward `expect`/`actual` declarations: the `actual` is the export root

## Status
Accepted

## Context

`expect`/`actual` is the most basic construct in Kotlin Multiplatform and the forward direction
does not handle it at all. `grep` for `Modifier.EXPECT` / `isExpect` / `Modifier.ACTUAL` across
`nuget-processor` returns zero hits.

`allDeclarations` (`NugetProcessor.kt:168-172`) is the single funnel for the forward export set,
and it is `resolver.getAllFiles().flatMap { it.declarations }` with only the two ADR-063 package
predicates applied (**Verified**, read in source). For a native compilation KSP hands that funnel
**both** halves of every `expect`/`actual` pair, so each pair is planned twice under one qualified
name and trips a catalog duplicate guard:

- `forward/ForwardKotlinPlanEmitter.kt:383` -> `IllegalArgumentException: Forward callable catalog has duplicate plans for <symbol>`
- `forward/ForwardCallablePlanner.kt:108` -> `IllegalArgumentException: Forward property catalog has duplicate plans for <symbol>`

Both are raw JVM exceptions, not named `ForwardDiagnostic`s (ADR-064), and unlike the
collection-element crash they killed generation for the **entire module**, not one callable.

### The reproduction matrix (Verified by execution, `test-library`, `macosArm64` + `mingwX64`)

| shape | result today |
|---|---|
| `expect class X()` explicit ctor + method | crash on `X.<init>` |
| `expect class X` implicit ctor + method | crash on `X.name` (first method) |
| `expect fun f(): String` | crash on `f` |
| `expect object O { fun name(): String }` | crash on `O.name` |
| `expect val p: String` | crash on `p` (property catalog) |
| `actual typealias` | **no crash**, silently wrong: the `expect` side is emitted as a second, degenerate C# class carrying only the `internal X(IntPtr handle)` constructor, with no members and no public constructor, sitting beside the real aliased type |

This was found while investigating a downstream report (PeopleInSpace `windows/LIMITATIONS.md`)
that blamed the Koin compiler plugin. **Koin is not the cause of this KSP-time crash and needs no
special handling for it** (Verified by execution: the full Koin compiler-plugin setup packs green
through `packNuget` including `GeneratedBindingsCheck`). Koin was the carrier only, because its
idiomatic platform module is written `@Module expect class NativeModule()`. Nothing in this ADR is
Koin-specific.

That verification is scoped to this KSP-time duplicate-plan crash only. A separate, still-live
issue was found later on plugin 0.3.0 with Kotlin 2.4.10: having the Koin compiler plugin on a
`sharedLib` target's `kotlinCompilerPluginClasspath` crashes *link time* C-export codegen with
`NullPointerException at KlibModuleOriginKt.getKlibModuleOrigin` / `CAdapterCodegen.buildCAdapter`,
regardless of the annotated declaration's visibility. That is the pre-existing Kotlin/Native
backend bug [KT-62984](https://youtrack.jetbrains.com/issue/KT-62984), unrelated to the
`expect`/`actual` handling this ADR decides; see
[Publish a Kotlin/Native library as NuGet](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/topics/publish-kotlin-library-as-nuget.md)
for the symptom and workaround.

### What KSP actually shows (Verified by spike, this session)

Every claim in this section was produced by a scratch two-target KMP project
(`macosArm64` + `mingwX64`, Kotlin 2.4.10, KSP 2.3.10, the exact versions in
`gradle/libs.versions.toml`) with a probe `SymbolProcessor` on `kspMacosArm64` that dumped the
model. Spike sources and raw output are transient (scratch dir); the findings are reproduced
below verbatim.

1. **`getAllFiles()` returns every source set in the compilation.** For `kspKotlinMacosArm64`:

   ```
   === getAllFiles() ===
   FILE .../lib/src/macosArm64Main/kotlin/Actuals.kt
   FILE .../lib/src/nativeMain/kotlin/Expects.kt
   FILE .../lib/src/commonMain/kotlin/Common.kt
   ```

   So the duplicate is structural, not a bug in KSP: the header and the body are two files in one
   compilation, and both are ours. It holds for an `expect` in `commonMain` and for one in an
   intermediate `nativeMain` alike.

2. **`Modifier.EXPECT` / `Modifier.ACTUAL` and `isExpect` / `isActual` are reliable**, on
   top-level declarations and on class members individually:
   `CLASS(CLASS) spike.Explicit ... modifiers=[EXPECT] isExpect=true isActual=false` against
   `modifiers=[ACTUAL] isExpect=false isActual=true`, and inside the actual class
   `MEMBER-FUN greet isActual=true modifiers=[ACTUAL]`.

3. **`findActuals()` and `findExpects()` return empty sequences for everything.** Every
   declaration in the spike, both directions, top-level and member:
   `findActuals=Success([]) findExpects=Success([])`. KSP2's Analysis API implementation does not
   populate them (corroborated by community reports of `findActuals` being unimplemented in
   KSP2). **They are not a usable dedupe key on KSP 2.3.10.** A modifier filter is.

4. **The `expect` carries metadata the `actual` does not.** KDoc and annotations declared on the
   `expect` are absent from the `actual`:
   `expect`: `docString=KDoc declared only on the native expect class... annotations=[Marker]`;
   `actual`: `docString=null annotations=[]`.

5. **Default parameter values live on the `expect` only.** `expect fun greet(who: String = "...")`
   reports `params=[who:hasDefault=true]`, the matching `actual` reports `hasDefault=false`. This
   is not a KSP artefact: Kotlin forbids an `actual` from declaring defaults (Inferred, from the
   documented rule "an actual function cannot have default argument values; declare them in the
   expected function instead").

6. **An `expect class` with no explicit constructor has no constructor at all**:
   `primaryCtor=null ctors=0`. Its `actual` reports one `origin=SYNTHETIC` no-arg constructor.
   Same for `expect object` (`ctors=0`). This is exactly why the matrix's implicit-ctor row
   crashes on the first *method* rather than on `<init>`, and it is the mechanism behind the
   `actual typealias` row's "no public constructor" degenerate class.

7. **A type reference to an `expect`/`actual` *class* pair resolves to the `actual`.** From a
   function declared in the shared source set *and* from one declared in the target source set:
   `returnType=spike.Explicit declIsExpect=false declIsActual=true declFile=Actuals.kt`.

8. **A type reference to an `expect class` actualized by an `actual typealias` resolves to the
   `expect` class declaration**, from both source sets:
   `returnType=spike.Aliased declKind=KSClassDeclarationImpl declIsExpect=true declFile=Expects.kt`.
   `KSType.declaration` is a `KSClassDeclaration`, **not** a `KSTypeAlias`, so `expandAliases()`
   (ADR-018) cannot see through it and never will. The link to the real type exists only on the
   separate top-level `KSTypeAlias spike.Aliased modifiers=[ACTUAL]`, whose `findActualType()`
   does resolve correctly (`findActualType=spike.Real`). This asymmetry between rows 7 and 8 is
   the whole reason `actual typealias` needs its own rule.

9. **A JVM KSP2 run configured with `commonSourceRoots` reproduces the same two-declaration
   view**, `EXIT=OK`, no frontend error, `getAllFiles()` listing both the common and the platform
   file. `KSPConfig` already exposes `commonSourceRoots` (`getCommonSourceRoots()`), and KSP2
   merges it into the module's source roots. So the ADR-060 Tier 1 harness can host these cells
   with a one-parameter change; the red test does not need a native toolchain.

### Two repository constraints this decision has to respect

- **The classifier keys the export set by qualified name, not declaration identity**
  (`ForwardBridgeTypeContext.exportedObjectHandles: Set<String>`, and
  `ForwardReachabilityResult.admitted: Map<String, KSClassDeclaration>`; **Verified**, read in
  source). So whichever half of a pair survives the funnel, name-based lookups keep matching.
  Only the *content* of the surviving declaration differs.
- **`packNuget` packages exactly one target's `Interop.cs` while shipping every target's binary**
  (`NugetPlugin.kt:331-339`, "Pick the first available target's output"; **Verified**, read in
  source). The C# API a consumer gets is therefore generated from one target's KSP run, and every
  other platform's `.dylib`/`.dll` has to satisfy it. `ForwardAbiContract` (ADR-055) is a
  per-run check, so it cannot see a cross-target divergence at all.

### How every other Kotlin export backend answers this

All four are **Verified in upstream source** (fetched from `JetBrains/kotlin` master this
session), not inferred from docs. All four do the same thing: they drop the `expect`.

- **Kotlin/Native's own C export** (`CAdapterGenerator.kt`, the generator behind the
  `libfoo_api.h` of a `-produce dynamic` library, i.e. the closest possible analogue to this
  project) filters at three sites: `isExportedFunction` (`descriptor.isExpect -> false`),
  `isExportedClass` (with the comment `// Do not export expect classes.`), and
  `visitPropertyDescriptor` (`if (descriptor.isExpect) return true`, skipping the accessors).
  Functions, classes, properties: exactly the three shapes in our matrix that crash.
- **ObjC export, both implementations.** K1 `ObjCExportMapper.shouldBeExposed`
  (`descriptor.isExpect -> false`) and `shouldBeVisible` (`!descriptor.isExpect`); Analysis API
  `isVisibleInObjC` for callables *and* classes (`if (symbol.isExpect) return false`).
- **Wasm/JS klib export** (`WasmExportUtils.kt`): `!declaration.isExpect` on both the
  `@WasmExport` and `@JsExport` paths, with the comment *"expect declarations are eliminated
  during wasmLowerings - export declarations not generated"* and a reference to KT-86267,
  *"K/Wasm: prohibit placing JsExport/WasmExport on expect declarations"*.
- **JS export** rejected `@JsExport` on an `expect` outright with *"Declaration of such kind
  (expect) cannot be exported to JavaScript"* (KT-64951, fixed in 2.2.0; Inferred, from the
  YouTrack issue). Its reporter's case is precisely our `actual typealias` row:
  `actual typealias File = org.w3c.files.File`, where the fix has the exportability check see
  through the `expect` to the actual side.
- **Java/JVM**: Inferred from the Kotlin docs, *"the compiler merges the expected and actual
  declarations... generates one declaration with its actual implementation for each platform"*,
  so Java only ever sees the actual. No `@Jvm*` annotation exists for the expect side.
- **Swift export**: Inferred. `docs/swift-export/architecture.md` says it consumes a klib through
  the Analysis API, so it sees a platform module's view; a code search for an `isExpect` filter
  under `native/swift` found none. Not confirmed either way.

The central mechanism claim in one line: **every one of those backends runs after actualization,
on IR / descriptors / a klib, so it observes one declaration per pair and the `isExpect` filter is
belt-and-braces. KSP is the odd one out because it is a per-compilation, per-source-file view, so
it observes two** (Verified, spike finding 1). That is not a defect to route around; it is the
reason we must choose a side explicitly.

## Alternatives Considered

### 1. The `actual` is the export root; filter `Modifier.EXPECT` at the funnel (chosen)

Drop every `isExpect` declaration in `allDeclarations`, before anything downstream sees it.

**Pros:**
- It is what four Kotlin export backends do, including Kotlin/Native's own C export, at exactly
  the same three declaration kinds.
- The `actual` is what the generated `@CName` wrapper compiles against and the only side with a
  body; after the filter, an `actual class` is indistinguishable from an ordinary class for every
  downstream stage (`Modifier.ACTUAL` is additive and no `contains(...)` check anywhere reacts to
  it).
- It is the only side that has a constructor when the class declares none (spike finding 6), so
  it is the only side that can produce a usable C# class for the matrix's implicit-ctor row.
- One filter at one choke point covers all six matrix rows plus `expect interface` / `enum` /
  `value class` / annotation class, because the filter is declaration-kind agnostic.

**Cons:**
- KDoc, annotations and default parameter values declared on the `expect` are invisible
  (spike findings 4 and 5). None is consumed today; the open Phase 4 default-parameter item will
  have to consult the expect side deliberately (see Consequences).
- The exported API is whatever *that* target's `actual` declares. An `actual` may legally declare
  public members the `expect` never mentioned, so two targets can disagree, and only one target's
  `Interop.cs` is packaged.
- The C# static class name for a top-level `actual fun`/`val` comes from the *actual's* file name
  (`CirTranslator.kt:81`/`:90` read `containingFile.fileName`; **Verified** in source), which is
  typically per-target (`PlatformMacos.kt` vs `PlatformMingw.kt`). Decision 3 fixes this.

### 2. The `expect` is the export root; filter `Modifier.ACTUAL`

**Pros:**
- Target-independent by construction: the packaged `Interop.cs` would describe the common
  contract, which is exactly the API surface the author promised on every platform.
- Keeps KDoc, annotations and default parameter values.

**Cons:**
- **It cannot express the implicit-constructor row at all**: `expect class X { fun name() }` has
  `ctors=0` (Verified), so the generated C# class would have no public constructor. That is
  literally the degenerate class the `actual typealias` row already produces, promoted from a bug
  to a design.
- The `actual typealias` row has no `expect`-side body to bind to and no actual class declaration
  either, so it would still need a rule of its own.
- Members an `actual` adds beyond the `expect` would silently vanish, which is a bigger surprise
  than the divergence in alternative 1 (there the extra member at least appears on the target it
  was written for).
- No Kotlin backend does this.

### 3. Merge the pair into one synthetic declaration (actual body + expect metadata)

Dedupe by qualified name, take the `actual` as the structural source and overlay the `expect`'s
KDoc, annotations and parameter defaults.

**Pros:**
- Strictly the most information-preserving; it is what the compiler itself conceptually does.

**Cons:**
- Nothing downstream consumes KDoc or annotations today (`grep docString` in `nuget-processor`:
  zero hits; **Verified**), so the entire merge machinery would buy exactly one future feature
  (default parameters) that is not scheduled yet.
- A merged view is not a `KSDeclaration`, so it would need a wrapper type threaded through the
  planner, the classifier and both emitters, or a parallel index. Large blast radius for zero
  present-day output change.
- Deferred, not rejected: Decision 1 keeps the expect index that a later merge would need.

### 4. Dedupe through `findActuals()` / `findExpects()`

The first-class KSP API for exactly this (`KSExpectActual`, implemented by `KSDeclaration`).

**Cons:**
- **Verified non-functional on this toolchain**: both return empty sequences for every
  declaration in the spike, in both directions, for top-level declarations and for members. A
  filter built on them would silently keep both halves and the crash would stay.
- Rejected on evidence, not on taste. If a future KSP fixes it, it becomes a nicer *linking*
  mechanism (pairing an actual with its expect for Decision 3's naming and for default-parameter
  recovery), but never a *filtering* one: the modifier is enough and is always present.

### 5. Skip every `expect`/`actual` pair with a named diagnostic

Detect the duplicate and drop both halves with `SKIPPED_UNSUPPORTED_COMBINATION`.

**Pros:**
- Smallest possible change; converts a module-wide crash into a per-declaration warning.

**Cons:**
- Refuses to bridge the most common construct in KMP. Any consumer with one `expect class` in
  scope would lose it plus everything reachable through it, silently by design.
- ADR-064's policy is skip-with-a-name for things we *cannot* express. We can express this one.

### 2a-2d. What `actual typealias` means on the C# surface (sub-decision)

- **2a. Erase to the alias target, and redirect the `expect` name to it (chosen).** The target
  type is exported under its own name; a member typed with the `expect` name binds to the target's
  C# type. Consistent with ADR-018 (aliases are erased, transparent expansion), with Kotlin's own
  C export and ObjC export (which see the actualized type), and with the JS export fix in
  KT-64951. Cost: the redirect must be name-based, because the resolved declaration is the
  `expect` class and never the alias (spike finding 8).
- **2b. Export the target's members under the `expect` name.** Preserves the author's public API
  name, which is genuinely the name they wrote. Rejected: if the target is also exported directly
  (very common, it is an ordinary class in the same module), the consumer gets two unrelated C#
  types for one Kotlin type with no conversion between them, which is precisely the ADR-066
  duplicate-type hazard, self-inflicted this time.
- **2c. Export both, with a conversion.** All of 2b's cost plus a conversion nobody asked for.
- **2d. Skip the whole shape with a diagnostic.** Honest and cheap, but it drops a working type
  for no reason when the target is an ordinary module-local class. Kept as the *fallback* for
  when the target is genuinely not exportable, which is Decision 2's diagnostic path.

## Decision

### Decision 1: the `actual` is the export root

At the `allDeclarations` funnel (`NugetProcessor.kt:168-172`), drop every declaration whose
`modifiers` contain `Modifier.EXPECT` (equivalently `isExpect`; both **Verified** present and
consistent). The filter is declaration-kind agnostic and therefore covers classes, objects,
functions, properties, interfaces, enums, value classes and annotation classes in one line.

```kotlin
val allDeclarations: List<KSDeclaration> = resolver.getAllFiles()
  .flatMap { it.declarations }
  .filter { it.packageName.asString() != "io.github.xxfast.kotlin.native.nuget.generated" }
  // ADR-074: the `actual` is the export root. For a native compilation `getAllFiles()` returns
  // the `expect` header and the `actual` body as two files of one compilation (Verified), so
  // without this every pair is planned twice under one symbol. Same rule, same three declaration
  // kinds, as Kotlin/Native's own C export (`CAdapterGenerator`) and ObjC export.
  .filter { !it.isExpect }
  .filter { isPackageExported(it.packageName.asString()) }
  .toList()
```

Apply the same guard to declarations admitted by the ADR-066 reachability closure
(`ForwardReachabilityClosure.visitDeclaration`): a cross-module klib declaration reporting
`isExpect` must not be admitted. Whether a klib can even present one is **Inferred** (not spiked:
the spike had no cross-module `expect`/`actual`); the guard costs one condition and removes the
question.

Filtering an `expect` emits **no diagnostic**. It is normal and correct, it is what every Kotlin
backend does silently, and one warning per `expect` declaration would be pure noise in a KMP
module.

Alongside the filter, build a **by-name index of the expects** in the same pass:

```kotlin
// ADR-074: kept because the `actual` is structurally complete but metadata-poor. `findExpects()`
// is Verified empty on KSP 2.3.10, so the qualified name is the only available link.
val expectsByName: Map<String, KSDeclaration> = <expect declarations>.associateBy { it.qualifiedName!!.asString() }
```

Two decisions below consume it, and the deferred default-parameter work needs it.

### Decision 2: `actual typealias` erases to the alias target, with a name redirect

1. Collect the actual typealiases from the same funnel input, before the `isExpect` filter drops
   the paired expect class:

   ```kotlin
   // Verified: the `actual typealias` reaches KSP as a top-level KSTypeAlias with
   // Modifier.ACTUAL, and `findActualType()` resolves it to the target KSClassDeclaration.
   val actualTypeAliases: Map<String, KSClassDeclaration> = allFiles
     .flatMap { it.declarations }
     .filterIsInstance<KSTypeAlias>()
     .filter { it.isActual }
     .mapNotNull { alias -> alias.qualifiedName?.asString()?.let { it to alias.findActualType() } }
     .toMap()
   ```

2. Thread that map into `ForwardBridgeTypeContext` and apply it in
   `ForwardBridgeTypeClassifier.classifyNonNullable`, at the point where the resolved
   `KSClassDeclaration` and its `qualifiedName` are in hand and **before** the
   `exportedObjectHandles` lookup: if the declaration `isExpect` and the map has its qualified
   name, substitute the target declaration and its qualified name, and classify that instead. Do
   the same in `ForwardReachabilityClosure.visitType`.

   This redirect is required, not cosmetic: a type reference to such a name resolves to the
   `expect` class from *every* source set, including the one that declares the alias (Verified,
   spike finding 8), and `expandAliases()` structurally cannot help because the resolved
   declaration is a class, not an alias.

3. The C# type is the **target's** (`SystemClock`), never the `expect`'s (`Clock`). The alias name
   disappears from the C# surface exactly as an ordinary `typealias` does under ADR-018.

4. If the redirect target is not in the export set (a platform library type such as
   `platform.Foundation.NSObject`, a stdlib type, a type in an excluded package, or a kind the
   forward direction cannot express), the member routes through the normal ADR-064 skip path with
   a new diagnostic kind:

   ```kotlin
   /** ADR-074: an `expect class` actualized by an `actual typealias` whose target is not in the
    *  forward export set (a platform-library type, a stdlib type, or an out-of-scope package).
    *  Distinct from SKIPPED_UNEXPORTED_DEPENDENCY_TYPE, whose `include(...)` hint is wrong here:
    *  a platform library can never be brought into scope. */
   SKIPPED_ACTUAL_TYPEALIAS_TARGET(ForwardDiagnosticSeverity.WARNING),
   ```

   with the hint: *"the `actual typealias` for `<expect name>` resolves to `<target>`, which the
   forward direction does not export; wrap it in a class you declare and expose that instead"*.

### Decision 3: a top-level `actual fun`/`val` takes its C# static class name from the `expect`'s file

`CirTranslator.kt:81`/`:90` derive the ADR-007 per-file static class name from
`containingFile.fileName` (**Verified**, read in source). For a declaration carrying
`Modifier.ACTUAL`, look the qualified name up in `expectsByName` and use the **expect's** file
name when a match exists; fall back to the actual's file name when it does not.

Without this, `expect fun platformName()` in `nativeMain/Platform.kt` with actuals in
`macosArm64Main/PlatformMacos.kt` and `mingwX64Main/PlatformMingw.kt` produces
`public static class PlatformMacos` in one target's `Interop.cs` and `PlatformMingw` in the
other's, while `packNuget` packages exactly one of them (**Verified** in source) and ships both
binaries. The consumer then gets a class named after somebody else's platform, and on the other
platform the P/Invoke entry points still resolve (the symbol name does not embed the file), so the
divergence is invisible until someone reads the API.

Kotlin requires an `expect` and its `actual` to live in the same module, so within one compilation
the lookup always hits; the fallback is defensive only (**Inferred** from the language rule, not
spiked).

### Decision 4: the failure mode for everything still unsupported

- The two duplicate guards stay as invariants but must be unreachable through this path. Upgrade
  both messages to name the likely cause, since a `require` that fires again means a *new* source
  of duplicate qualified names: `"Forward callable catalog has duplicate plans for $symbol; two
  declarations share one qualified name (an unfiltered expect/actual pair is the usual cause)"`.
- `SKIPPED_ACTUAL_TYPEALIAS_TARGET` is the one new diagnostic kind (Decision 2 point 4).
- No other new kinds. Everything else about an `actual` declaration is an ordinary declaration and
  reaches the existing ADR-064 paths unchanged.

### The consumer-side C# API, per matrix row

Given the fixture package `io.github.xxfast.kotlin.native.nuget.test.platform`:

| Kotlin (`nativeMain` expect + `{target}Main` actual) | generated `Interop.cs` |
|---|---|
| `expect class Device(name: String) { fun describe(): String; val id: String }` | `public class Device : IDisposable { public Device(string name); internal Device(IntPtr handle); public string Describe(); public string Id { get; } public void Dispose(); }` |
| `expect class Sensor { fun reading(): Int }` (implicit ctor) | `public class Sensor : IDisposable { public Sensor(); internal Sensor(IntPtr handle); public int Reading(); public void Dispose(); }` |
| `expect fun platformName(): String` (in `Platform.kt`) | `public static class Platform { public static string platformName(); }` (Decision 3: `Platform`, from the *expect's* file, not `PlatformMacos`) |
| `expect object PlatformRegistry { fun count(): Int }` | `public static class PlatformRegistry { public static int Count(); }` |
| `expect val platformTag: String` (in `Platform.kt`) | `public static class Platform { public static string PlatformTag { get; } }` |
| `expect class Clock { fun label(): String }` + `actual typealias Clock = SystemClock` | **no `Clock` type at all**; `public class SystemClock : IDisposable { ... public string Label(); }`, and a member declared `fun defaultClock(): Clock` binds as `public static SystemClock defaultClock()` |
| `expect class Failure` + `actual typealias Failure = kotlin.IllegalStateException` | no type, and every member mentioning it is skipped with `[nuget:SKIPPED_ACTUAL_TYPEALIAS_TARGET]` |

Every generated shape here is byte-for-byte what the equivalent non-`expect` declaration produces
today. That is the point of the design: after the filter, nothing downstream knows `expect`/`actual`
exists.

**Casing, corrected while landing this ADR.** The table above originally PascalCased the top-level
*function* rows (`PlatformName()`, `DefaultClock()`). That contradicted the paragraph directly above
it, and it was wrong: this repository does **not** PascalCase a top-level free function.
`topLevelEntry` (`ForwardCallablePlanner.kt:531`) sets `publicName = toCName(...).csharpIdentifier()`
and neither helper changes case (`toCName` only suffixes C reserved words, `Reserved.kt:25`;
`csharpIdentifier()` only `@`-escapes C# keywords, `ForwardCallablePlanner.kt:1348`), whereas
`objectEntries` (`:549`) and `companionEntries` (`:570`) both apply
`replaceFirstChar { it.uppercase() }`. **Verified** against committed, compiling call sites:
`Arithmetic.add(3, 4)` and `ClinicSample.patientNameLength(patient)` (camelCase, multi-word, so not
snake-cased either) against `CatRegistry.Clear()` (an `object`, PascalCase).
`IntegrationTests/MethodParameterMarshallingTests.cs:141` documents the rule in a comment. Top-level
*properties* go the other way and the table's `PlatformTag` row was always right: `val catBreed`
binds as `Properties.CatBreed` (**Verified**, `TopLevelPropertyTests.cs:10`).

So the forward direction PascalCases every position except the top-level function, which keeps its
Kotlin camelCase. That asymmetry is pre-existing, deliberate enough to be commented in a test, and
**out of scope here**: this ADR's entire claim is that an `actual` generates exactly what the same
non-`expect` declaration would, so it must inherit the convention rather than quietly fix it for one
declaration kind. ROADMAP.md's own closed item on `object` methods called the equivalent
inconsistency "off-idiom" and fixed it, which makes the surviving top-level-function case a
reasonable follow-up, tracked separately.

## Consequences

### What changes

- Six crashing/silently-wrong shapes become ordinary, correctly bridged declarations. The blast
  radius shrinks from "the whole module fails to generate" to nothing.
- `expect interface`, `expect enum class`, `expect value class` and `expect annotation class` are
  fixed by the same one-line filter without being exercised. Supported by construction, untested;
  each is worth a Tier 1 cell when someone needs it.
- The forward export set gains one *behavioural* asymmetry worth writing down: the C# API is now
  the API of **one target's `actual` declarations**.

### The cross-target divergence hazard (accepted for v1, needs its own item)

`packNuget` packages one target's `Interop.cs` (**Verified** in source) and ships every target's
binary. An `actual` may legally declare public members its `expect` never mentioned, so two
targets can produce different C# APIs, and only one is shipped. Consequences:

- A member present in target A's actual but not target B's generates a `[DllImport]` that
  resolves to a missing entry point on B: a runtime `EntryPointNotFoundException`, not a build
  error.
- `ForwardAbiContract` (ADR-055) cannot catch it. It compares the C# and Kotlin generated in the
  *same* run, and each target's run is internally consistent.
- A single KSP run cannot diagnose it either: the other target's source set is not in this
  compilation (**Verified**, spike finding 1, `getAllFiles()` lists only `commonMain`,
  `nativeMain` and `macosArm64Main`).

Decision 3 removes the most likely accidental instance (per-target file names). The general case
needs a plugin-level check: run KSP for every packaged target and diff the ADR-055 forward ABI
contract manifests, failing when they disagree. Recommended as a follow-up ROADMAP item under
Tooling & Test Integrity; explicitly **not** in this feature's scope.

Which target is "first" for packaging is `kotlin.targets` iteration order. For `test-library` the
build directory contains generated resources for `macosArm64` only, so `macosArm64` is first there
(**Inferred** from build-directory state, which is not evidence about source; the source claim
that *exactly one* target is packaged is Verified).

### What is deferred

Closed by the 2026-09-05 amendment below; each bullet now points at its outcome instead of leaving
the shape open.

- **Default parameter values from the `expect`.** Shipped, not deferred: [ADR-091](091-constructor-default-parameters.md)
  (constructors) and [ADR-096](096-function-default-parameters.md) (functions) both read
  `expectsByName`. See the amendment's item 5.
- **KDoc and annotations from the `expect`.** Rejected, no re-open trigger fired yet. See the
  amendment's item 4.
- **Merging the pair (alternative 3).** Rejected: the one motivating consumer (default parameters)
  shipped without a merge. See the amendment's item 5.
- **Cross-module `expect`/`actual`** (an admitted ADR-066 klib declaration). Rejected as
  unreachable; the guard stays defensive. See the amendment's item 3.
- **`expect sealed class`.** Exercised, both for actual-side subclasses (`test-library` +
  Tier 1) and, at the KSP2 level only, for a common-side subclass. See the amendment's item 1.
- **`actual typealias` to a generic or parameterized target.** Rejected as a mapping (the target
  shapes Kotlin itself accepts are narrower than the ADR's own example, which is not a compiling
  Kotlin program); the existing `SKIPPED_ACTUAL_TYPEALIAS_TARGET` skip is now pinned by a Tier 1
  cell. See the amendment's item 2.
- **`expect interface`, `expect enum class`, `expect value class`.** Exercised, both levels.
  **`expect annotation class`.** Not applicable, no forward route exists for it. See the
  amendment's item 6.

### Fixture design

Per this repository's rule, the fixture crosses **every** mechanism rather than the fewest types.

**`test-library`** gains one package, `...nuget.test.platform`, with three files. The two target
files must declare an **identical public surface** and differ only in returned values: that is
both the point of the fixture (it proves the `actual` body is what runs) and a live demonstration
of the divergence constraint above.

`src/nativeMain/kotlin/.../platform/Platform.kt` (the expect side, plus the alias target):

```kotlin
expect class Device(name: String) {   // explicit ctor + method + property
  fun describe(): String
  val id: String
}

expect class Sensor {                 // implicit ctor (the `ctors=0` row)
  fun reading(): Int
}

expect fun platformName(): String     // top-level fun; differs per target
expect val platformTag: String        // top-level val; differs per target

expect object PlatformRegistry {      // expect object
  fun count(): Int
}

expect class Clock {                  // actualized by a typealias
  fun label(): String
}

class SystemClock {                   // the alias target: module-local and exported
  fun label(): String = "system-clock"
}

fun labelOf(clock: Clock): String = clock.label()   // redirect at a parameter position,
                                                    // referenced from shared code
```

`src/macosArm64Main/kotlin/.../platform/PlatformMacos.kt` and
`src/mingwX64Main/kotlin/.../platform/PlatformMingw.kt` (deliberately **differently named files**,
so Decision 3 is under test rather than accidentally satisfied):

```kotlin
actual class Device actual constructor(private val name: String) {
  actual fun describe(): String = "$name on macos"      // "on mingw" in the other
  actual val id: String = "macos-device"                // "mingw-device"
}

actual class Sensor { actual fun reading(): Int = 42 }  // 24
actual fun platformName(): String = "macos"             // "mingw"
actual val platformTag: String = "osx-arm64"            // "win-x64"
actual object PlatformRegistry { actual fun count(): Int = 1 }
actual typealias Clock = SystemClock

fun defaultClock(): Clock = SystemClock()               // redirect at a return position,
                                                        // referenced from target code
```

**`IntegrationTests`** asserts the *body* ran, not just that the symbol exists: `PlatformName()`
returns the value for the running RID, selected in the test with
`RuntimeInformation.IsOSPlatform(OSPlatform.OSX)`. Same for `PlatformTag`, `Sensor.Reading()` and
`Device.Describe()`. Plus: `new Device("x").Describe()`, `new Sensor().Reading()` (proving the
implicit-ctor row produces a *usable* public constructor), `PlatformRegistry.Count()`, and
`DefaultClock().Label()` returning `"system-clock"` through a C# variable typed `SystemClock`
(proving the alias redirect, since the test would not compile if the type were `Clock`).

**Tier 1** carries the cells that must not live in `test-library`:

- The harness gains a `commonSourceRoots` parameter on `KSPJvmConfig.Builder`
  (**Verified** to work: a JVM KSP2 run so configured processed an `expect`/`actual` fixture with
  `EXIT=OK` and reported both files from `getAllFiles()`).
- One structural cell per matrix row asserting that the generated `Interop.cs` contains exactly
  one declaration for each name, with the actual's members.
- One structural cell for Decision 3: the generated `Interop.cs` contains `class Platform` and
  does **not** contain `PlatformMacos`/`PlatformMingw`.
- One diagnostic cell for `SKIPPED_ACTUAL_TYPEALIAS_TARGET`, using
  `actual typealias Failure = kotlin.IllegalStateException`. It is a permanent Tier 1 cell:
  a `SKIPPED_*` warning must not sit in `test-library`'s build log forever.
- One regression cell per crash row, asserting `kspExitCode = OK` and empty `kspErrors`. These
  are the ones that are red today.

## Amendment (2026-09-05): closing the v1 deferred list

Judgement: an **amendment**, not a new ADR. Every item below closes an entry in this ADR's own
"What is deferred" list; none introduces a mechanism this ADR does not already describe (the
`isExpect` filter, the `expectsByName` index, the `actualTypeAliasTargets` redirect, and the
closure guard). Status stays Accepted.

Every shape the "What is deferred" section left open now has a terminal state: **exercised** by a
fixture cell (`test-library` Kotlin + `IntegrationTests` xunit + Tier 1), **fixed** if exercising it
revealed a defect, or **rejected** with a reason. Nothing stays deferred. Predicted processor
source change for the whole list: none landed; each exercised cell names the one predicate that
would make it red and the fix if it is.

Mechanism claims are labelled **Verified** (read in this repository's source, or in upstream Kotlin
source fetched on 2026-09-05) or **Inferred**.

A structural fact about the Tier 1 harness applies to every cell below: `Tier1Harness.compileGenerated`
runs a plain single-target `K2JVMCompiler.exec()` with no `-Xcommon-sources`/multiplatform wiring, so
it cannot model the real common/actual module split. Every cell in this amendment therefore asserts
on `result.generated` / `result.generatedCSharp` / `result.kspWarnings`, never on `compiledClean`; an
`expect`/`actual` fixture legitimately fails that JVM single-target compile step, exactly as the
existing pre-amendment cells already do.

### The structural fact all six rest on (Verified)

`NugetProcessor.kt:283-296`: the `isExpect` filter produces `candidateDeclarations`, and
`allDeclarations` is derived from it; every root bucket (`allFunctions`, `allProperties`,
`rootClasses`, `rootValueClasses`, `rootSealedClasses`, `rootObjects`, `rootEnums`,
`rootInterfaces`, `NugetProcessor.kt:357-433`) is derived from `allDeclarations`. So the filter
precedes every route, and an `actual` reaches exactly the route the same non-`expect` declaration
would, selected on `classKind`, `Modifier.SEALED`, `Modifier.VALUE`/`INLINE`, top-levelness and
visibility only. `isActual`/`Modifier.ACTUAL` is read at exactly two sites (`CirTranslator.kt:96`,
`NugetProcessor.kt:353`), neither of which changes a route.

### 1. `expect sealed class`: exercised

Kotlin permits subclasses of an `expect sealed class` on the actual side and in any module on the
dependency path between the expect and the actual, including the expect's own (Inferred, from the
[sealed-interface-freedom KEEP](https://github.com/Kotlin/KEEP/blob/master/proposals/sealed-interface-freedom.md)).

After the filter the `actual sealed class` (`modifiers = [ACTUAL, SEALED]`) enters
`rootSealedClasses` (`NugetProcessor.kt:411-416`, **Verified**) and every subclass enumeration in
the sealed route is `getSealedSubclasses()` on that actual declaration (`SealedClassExports.kt:30`,
`CirClassTranslator.kt:976`, `CirTranslator.kt:80,:278`, `ForwardReachabilityClosure.kt:87,:186`,
`NugetProcessor.kt:535,:983,:1061,:1087,:1122`; **Verified**). The one predicate this ADR never
proved is that `getSealedSubclasses()` on an *actual* returns the same inheritors it returns for an
ordinary sealed class (Inferred: KSP2 resolves inheritors through the Analysis API over the
module's sources, which does not distinguish the two). Its failure is loud, not silent:
`SealedClassExports.kt:37-45` renders one `is` branch per subclass inside an exhaustive `when`
expression, so a missing or empty inheritor list is a Kotlin compile error in the generated
`CNameExports.kt`, caught by Tier 1 and by the native build.

Fixture (the `platform` package): `expect sealed class Signal` + `expect fun collarSignal(dbm: Int):
Signal` in `PlatformResiduals.kt`; on both targets `actual sealed class Signal { data class
Strong(val dbm: Int) : Signal(); data object Lost : Signal() }` and `actual fun collarSignal(dbm: Int):
Signal = if (dbm < 0) Signal.Lost else Signal.Strong(dbm + 42)` (`+ 24` on mingw). xunit
(`ExpectActualResidualsTests.cs`): `typeof(Signal).IsAbstract`, `typeof(Signal.Strong).IsSealed`,
`collarSignal(10)` discriminates as `Strong` with the running actual's boost (42 macOS / 24 mingw)
through `Dbm`, and `collarSignal(-1)` discriminates as `Lost`. Tier 1
(`Tier1ExpectActualDeclarationsTest.kt`): one cell for actual-side subclasses (hierarchy rendered
once, `signal_get_type` names both `Strong` and `Lost` in its exhaustive `when`), green.

A second cell declares a **common-side** subclass instead, next to the expect (`class
CommonPing(val strength: Int) : Signal()` in `commonSources`), to pin the KEEP's "any module on the
dependency path" claim rather than only the actual-side placement the native fixture uses. That
cell must declare the constructor on both halves, `expect sealed class Signal()` / `actual sealed
class Signal actual constructor()`: an `expect class` with no declared constructor is not
constructible from common code (spike finding 6's `ctors=0`), and without it the frontend would
reject `: Signal()` for a reason unrelated to what the cell pins. It went green: the discriminator
names both the common-side `CommonPing` and the actual-side `Lost`. This cell is **KSP2-level
only**, not a `test-library` fixture: the Tier 1 harness's single-target JVM compile does not model
the real common/actual module split, and the native `test-library` fixture has only actual-side
subclasses, so this sub-shape's "exercised" claim is scoped to what KSP2 itself resolves, not to a
native end-to-end build.

### 2. `actual typealias` to a generic target: the mapping is rejected, the skip is pinned

The example this ADR deferred, `actual typealias Bag = List<String>`, is not a Kotlin program.
`FirActualTypeAliasChecker.checkTypeAliasWithComplexSubstitution` reports
`ACTUAL_TYPE_ALIAS_WITH_COMPLEX_SUBSTITUTION` whenever `typeParameters.size !=
expandedType.typeArguments.size` or an argument is anything other than the alias's own type
parameter in the same position, and `FirErrors.kt:855` declares that diagnostic with severity
`ERROR` (**Verified**, upstream source fetched). `actual typealias Bag<T> = List<T>` is rejected
too: `checkTypeAliasToClassWithDeclarationSiteVariance` (same file, `:115-121`, **Verified**)
reports `ACTUAL_TYPE_ALIAS_TO_CLASS_WITH_DECLARATION_SITE_VARIANCE` whenever any type parameter
of the expanded class is non-invariant, and `kotlin.collections.List` is `List<out E>`; an
`expect class` actualized onto an interface is additionally an incompatible class kind (Inferred).
So a read-only stdlib collection can never be the target of an `actual typealias` for an
`expect class`. The compilable generic forms are narrower: an invariant target of matching kind,
such as a module-local `class Crate<T>` behind `expect class Bag<T>` (or, Inferred,
`MutableList<T>` behind an `expect interface Bag<T>`).

For those forms the redirect already refuses deterministically:
`ForwardBridgeTypeClassifier.classifyActualTypeAliasTarget` returns `Unsupported(..., "actual
typealias target is not a plain, non-generic class", isActualTypeAliasTarget = true)` on
`target.typeParameters.isNotEmpty()` (`:216-227`), which routes to
`SKIPPED_ACTUAL_TYPEALIAS_TARGET`. The redirect is the first branch of `classifyNonNullable` after
the `KSTypeParameter`/`KSClassDeclaration` guards (`:76-80`), and neither the planner nor the
classifier has an `arguments.isNotEmpty()` branch that could intercept a parameterized return
type ahead of it (**Verified**, grep). A top-level `fun bagOf(): Bag<String>` is an ordinary
function (`genericFunctions` keys on the *function's* type parameters, **Verified**).

A real mapping is **rejected** for v1 and beyond, until a user asks: the redirect substitutes a
`KSClassDeclaration`, not a `KSType`, so it carries no argument map from the alias's parameters to
the target's; for a stdlib target the erased type (`List<T>`) is a carrier, not an exportable
declaration, so there is no C# type to name at a `Bag<String>` position without that rewrite; for a
module-local generic target the rewrite plus the generic route's constraints make it a feature, not
a residual. One permanent Tier 1 cell pins the skip
(`Tier1ExpectActualDeclarationsTest.kt`'s `` `actual typealias to a generic target is skipped with
its own diagnostic` ``), using a module-local invariant target so no frontend rule unrelated to the
pin can fire: `expect class Bag<T>` + `expect fun bagOf(): Bag<String>` in `commonSources`; `class
Crate<T>(val item: T)` + `actual typealias Bag<T> = Crate<T>` + `actual fun bagOf(): Bag<String> =
Crate("a")` in `sources`; asserts `SKIPPED_ACTUAL_TYPEALIAS_TARGET` naming `Bag` and `Crate`, no
`class Bag` in the C#, `kspExitCode OK`. The cell asserts kind and names, not the reason text. A
`SKIPPED_*` warning must not sit in `test-library`'s build log, so no `test-library` cell.

### 3. Cross-module (klib) `expect`/`actual`: rejected as unreachable; guard kept

Roots come from `getAllFiles()` (module-local, Verified spike finding 1), and Kotlin requires an
expect and its actual to share a module (Inferred, language rule already cited by Decision 3). So a
dependency klib can only hold pairs actualized when it was compiled, and the closure guard
(`ForwardReachabilityClosure.kt:159-162`, `if (classDeclaration.isExpect) return`, before
`admitted[...]`; **Verified**) can never fire on a root.

Whether a platform klib's metadata still carries the expect half is **Inferred, not spiked** and
stays so: both possibilities are non-silent. If the klib carries only actuals, the guard is a no-op.
If it carries an expect and a consumer reference resolves to it (only plausible for an
alias-actualized pair, by analogy with finding 8), the reference misses the module-local
`actualTypeAliasTargets` map (`NugetProcessor.kt:350-355`), hits the guard, stays unadmitted and
surfaces as `SKIPPED_UNEXPORTED_DEPENDENCY_TYPE`, the same named skip
`Tier1ReachabilityClosureTest.kt:42-57` exercises. Its `include(...)` hint would be wrong for that
case; that is a message defect, tracked separately, not wrong generated output.

Tier 1 cannot settle the question: `Tier1DependencyLibrary.compile` builds a JVM jar with
`K2JVMCompiler` (**Verified**, `:24-45`), and an expect produces no JVM class file, so the JVM
analog proves nothing about klib metadata. The re-open condition is a user report of a dependency
expect surfacing; the spike then is a two-module Gradle native build (dependency klib with `expect
class` + `actual typealias`, consumer referencing it).

### 4. KDoc and annotations declared on the `expect`: rejected

Nothing reads `docString` (**Verified**, zero hits). The only annotation consumer in the forward
pipeline is `hasCNameAnnotation()` (`NugetProcessor.kt:210-213`, applied at `:362`/`:369`,
**Verified**), which runs on the post-filter list, i.e. the actual. That is the right side:
Kotlin/Native's `CAdapterGenerator` skips expects (Verified upstream, Context above), so
`@CName` only means anything on the actual it compiles, and `@CName` cannot appear in `commonMain`
at all. Rejected; the re-open trigger is the feature that first consumes KDoc or annotations (XML
doc comments on the generated C#, an opt-out annotation), which must read `expectsByName`, exactly
as the default-parameter note below already required and ADR-091/096 already do.

### 5. Merging the pair into one synthetic declaration (Alternative 3): rejected

Alternative 3 was deferred because it "would buy exactly one future feature (default parameters)".
That feature has since shipped **without a merge**: `ForwardCallablePlanner.kt:886-897` (ADR-091,
constructor defaults) and `:935` (ADR-096, function defaults) consult `expectsByName` positionally,
exercised by `Beacon(name, interval = 5)` and `beaconLabel(prefix, level = 7)` in `PlatformApi.kt`
with RID-selected xunit (**Verified**). The by-name index is therefore proven sufficient for the
consumer that motivated the merge; a merged view would be a refactor with zero output change and a
blast radius across the planner, the classifier and both emitters. Rejected. `expectsByName` stays
the mechanism for any future expect-side metadata consumer.

### 6. `expect interface`, `enum class`, `value class`: exercised; `annotation class`: not applicable

Kotlin's compatibility checker compares `value`/`inline` between the halves (`ClassModifiers`) and
requires every expect enum entry on the actual (`EnumEntries`) (**Verified**, upstream
`ExpectActualCompatibility.kt`); that no other checker forbids `expect value class` is Inferred.

Predictions, all **Verified** from source: the actual interface enters `rootInterfaces`
(`:430-433`) and its members are read from itself (`CirClassTranslator.kt:1659,:1672`); the actual
enum enters `rootEnums` (`:424-427`) and its entries come from its own `declarations`
(`CirClassTranslator.kt:1701`); the actual value class enters `rootValueClasses` (`:404-408`) via
`isValueClass()` = `Modifier.VALUE || INLINE` (`ForwardBridgeTypeClassifier.kt:435-436`) and, being
a value class, must declare its primary constructor, so spike finding 6's `ctors=0` hazard does not
arise.

Fixture (the `platform` package; both target files declare the identical public surface):
`expect interface Transponder { fun ping(): String }` + `expect fun transponder(): Transponder` (an
`internal` per-target implementing class, `MacosTransponder`/`MingwTransponder`, so the packaged
`Interop.cs` is identical across targets and the return position triggers ADR-040's backing class,
generated as `public interface ITransponder : IDisposable` + `public sealed class Transponder :
ITransponder, INugetHandle`); `expect enum class Band { LOW, HIGH }` + `expect fun band(): Band`
(`LOW` on macos, `HIGH` on mingw; generated `public enum Band { Low = 0, High = 1 }`); `expect value
class Frequency(val hertz: Int)` + `expect fun frequency(): Frequency` (`2400` on macos, `5800` on
mingw; generated `public readonly record struct Frequency`). Not named `Beacon`/`Reporter`/`Tier`/
`Meters`: `PlatformApi.kt` in this same package already declares an ADR-091 `expect class Beacon`,
and the other names were placeholders from an earlier draft of this amendment, corrected to what
shipped. xunit (`ExpectActualResidualsTests.cs`) asserts the running RID's value through each
(`Transponder_Ping_ReturnsRunningActualsAnswer`, `Band_ReturnsRunningActualsEntry`,
`Frequency_Hertz_ReturnsRunningActualsValue`), plus `Band_EntriesAreOrdinalBacked` pinning
`(int)Band.Low == 0` / `(int)Band.High == 1`. Tier 1: one cell per kind asserting the type renders
exactly once with the actual's members/entries. The Tier 1 harness is a JVM K2 run, so its `actual
value class` cell carries `@JvmInline` exactly as `Tier1Cell15Test.kt:25-26` does (**Verified**);
`isValueClass()` keys on `Modifier.VALUE`, which the annotated form still carries, and the native
`test-library` fixture needs no annotation. Enum entries are `LOW`/`HIGH` in Kotlin and
`Band.Low`/`Band.High` in C#, the same rendering `Mood.kt` `HAPPY` -> `Mood.Happy` already
exercises (**Verified**).

`expect annotation class` is **not applicable**: the forward direction has no route for
`ClassKind.ANNOTATION_CLASS` (zero hits in `nuget-processor/src/main`, **Verified**; `rootClasses`
requires `classKind == CLASS`), so an ordinary `annotation class` is already outside the export
set, with no diagnostic. An `expect` one is filtered one line earlier and is otherwise identical.
No fixture: an absence assertion on an un-routed kind pins nothing.

### Consequences of the amendment

- "What is deferred" above is now historical; the live list is: the default-parameter and
  KDoc/annotation notes (as re-open conditions), the cross-target divergence hazard (its own item),
  and the two default-parameter limitations in `docs/topics/expect-actual.md`.
- Adjacent, out of scope, recorded for tracking on `ROADMAP.md`, not fixed here: a top-level
  function named `signal` silently failed to resolve at runtime on mingwX64 (renamed to
  `collarSignal` in the fixture to sidestep it, root cause not fully confirmed); the `include(...)`
  hint on a hypothetical dependency alias-actualized expect that `include` cannot fix (item 3);
  `expectsByName`'s `.toMap()` collapsing overloaded top-level `expect fun`s
  (`NugetProcessor.kt:338-343`, which ADR-096's default lookup would need to disambiguate); no
  diagnostic for a public `annotation class` (item 6).
