# An author-declared C# name for a single member (`@CSharpName`), so a CS0102 collision resolves without renaming the Kotlin API

- ROADMAP: line 74 as of 2026-10-01: "An author-declared C# name for a single member, in the spirit of `@ObjCName`, so a CS0102 collision (`val payload` beside `fun payload(code: Int): ByteArray?`, fatal since ADR-151) can be resolved without renaming the Kotlin API for every platform (issue #366)."
- Researched: 2026-10-01, about 8 of 20 minutes used
- Restatement: forward direction, the Kotlin author declares. One annotation on one member sets that member's exact C# name. Nothing else changes: not the Kotlin name, not the `@CName` export symbol, not any other platform's name. Not in scope: automatic renaming, skipping one side, downgrading the error.
- Verdict: fix, [ADR-179](../../adr/179-author-declared-csharp-member-name.md) (Proposed)

## Findings

Repo state (all **verified by reading** unless marked):

- `ERROR_CSHARP_NAME_COLLISION` is declared at `nuget-processor/.../forward/ForwardDiagnostic.kt:381`; emitted from `cir/CirMemberNameCollisions.kt:151-189` (`emitMemberNameCollisions`) and `:225-288` (`emitInheritedCollisions`), and from `cir/CirClassTranslator.kt:3741-3790`, `:3916-3945`. The default hint is "rename one of them" (`CirMemberNameCollisions.kt:199`); the "a rename is a silently different API" rationale is at `:32`.
- The collision checker mirrors the suspend `Async` suffix itself at `CirMemberNameCollisions.kt:101`; the generator appends it at `CirFunctionTranslator.kt:792`, `CirClassTranslator.kt:2041`, `:2150` (native stems at `:1955`, `:2104` also carry it but are ABI-side names).
- No chokepoint for a member's C# name. `grep -rc "replaceFirstChar { it.uppercase() }"` over `nuget-processor/src/main`: `CirClassTranslator.kt` 19, `ForwardCallablePlanner.kt` 11, `ForwardCirPlanProjection.kt` 4, `CirTranslator.kt` 4, `CirFunctionTranslator.kt` 3 (`:99`, `:714`, `:844`, each `toCSharpName(name.replaceFirstChar { it.uppercase() })`), `ForwardInterfaceBridgePlanner.kt` 2, `ForwardCirPropertyProjection.kt` 2, `ForwardBridgeTypeClassifier.kt` 2, `CirMemberNameCollisions.kt` 2, and one each in `ForwardPropertyPlanner.kt`, `ForwardDiagnostic.kt`, `ForwardCirCollectionComponents.kt`, `CirTypeMapping.kt`, `CirNativeImports.kt`, `ForwardSymbolTable.kt`. 55 total; not all are member names (type names, file classes, `Create$suffix` helpers at `ForwardCallablePlanner.kt:1081`, native stems). The implementer classifies each while routing.
- `toCSharpName` (keyword escape with `@`) is at `nuget-processor/.../Reserved.kt:55`.
- KSP annotation-by-qualified-name idiom: `forward/ForwardOptInMarkers.kt:36-77`; reading an *argument* off an annotation with three value shapes: `exports/Helpers.kt:288-305` (`isHiddenByDeprecation`, `level == HIDDEN`). Cross-klib annotation read: `CatteryInternalApi` declared in `test-models/src/nativeMain/.../CatteryInternalApi.kt:23`, used at `test-library/.../issue113/Issue113Sample.kt:205`.
- `findOverridee()` is already walked to the root at `ForwardCallablePlanner.kt:1855-1866` and used at `exports/Helpers.kt:346-347`.
- No annotations module exists; `settings.gradle.kts:18-21` includes `nuget-processor`, `nuget-runtime`, `test-models`, `test-library`. `nuget-runtime/build.gradle.kts:11-14` is native-only (macosArm64, macosX64, linuxX64, mingwX64). The plugin wires it as `${target}MainApi` + `export()` at `nuget-plugin/.../NugetPlugin.kt:230-268`, with a `findProject(":nuget-runtime") ?: "io.github.xxfast:nuget-runtime:$PLUGIN_VERSION"` fallback at `:230-231`. ADR-130 alternative 3 rejects a `commonMain` `api` on the native-only runtime ("breaks every consumer with a JVM (or JS) target at configuration time").
- ADR-063:130-141 rejected a per-declaration export annotation for v1, with "a source dependency the export module must take on" as one of four objections. ADR-064:118-126 records `@ObjCName` as the ObjC-export precedent for resolving a name conflict. ADR-113:181-185 rejected renaming one side with ADR-034/082's "silently different API" wording.
- The plugin passes KSP options by reflection at `NugetPlugin.kt:291-315` (`nuget.libraryName`, `nuget.namespace`), which is how a build-script rename map (rejected alternative) would travel.
- `test-library` has **no `commonMain`** (`test-library/src`: `macosArm64Main`, `mingwX64Main`, `nativeMain`); `Issue112Sample.kt` lives in `nativeMain` at `:89` (`fun collarTag(code: Int): Sequence<Int>`, KDoc `:82-87` explains the stand-in) and `:102`. `Tier1Issue112InterfaceProjectionTest.kt:55-67` asserts the method is absent, `:107-111` asserts exactly one skip warning names it.
- Release publishes three modules at `.github/workflows/release.yml:53-55`; a fourth must be added.

Toolchain (**verified by reading** `kotlin-stdlib-2.4.10-common-sources.jar`, the stdlib this repo builds against):

- `kotlin.native.ObjCName` is in `commonMain/kotlin/annotations/NativeAnnotations.kt:62-73`: `@Target(CLASS, PROPERTY, VALUE_PARAMETER, FUNCTION) @Retention(BINARY) @MustBeDocumented @OptionalExpectation @ExperimentalObjCName @SinceKotlin("1.8") public expect annotation class ObjCName(val name: String = "", val swiftName: String = "", val exact: Boolean = false)`. So it does resolve in `commonMain`, but only under `@OptIn(ExperimentalObjCName::class)`: `kotlin.experimental.ExperimentalObjCName` is `@RequiresOptIn` (`commonMain/kotlin/experimental/ExperimentalObjCName.kt`). Its argument shape is `name`, `swiftName`, `exact`.

**Inferred** (not spiked; each fails loudly, not silently, if wrong):

- A `commonMain` declaration's annotation is visible to the native-target KSP run the same way a `nativeMain` one is (KGP compiles `commonMain` into each native compilation). The flipped fixture proves this on the first `verify.sh`; if wrong the collision error keeps firing.
- An `androidTarget()` consumer resolves a KMP library's `jvm` variant through KGP's platform-type compatibility, so `nuget-annotations` needs no Android variant. If wrong, configuration fails with an unresolved-variant error.
- `SOURCE` retention would not reach KSP for an ADR-066 dependency owner read from a klib; `BINARY` chosen.
- `@OptionalExpectation` `ObjCName` on `mingwX64`/`linuxX64` (no `actual`) may resolve to an error type under KSP. Only matters for the rejected alternative.
- The Kotlin compiler's `INCOMPATIBLE_OBJC_NAME_OVERRIDE` makes a disagreeing override an error; cited as posture precedent only.

Prior art consulted, stopping at the first that settled it: Kotlin's own per-platform name annotations (`@JvmName`, `@JsName`, `@ObjCName`) are all annotations on the declaration, verbatim, with an invalid name a compile error. Xamarin `Metadata.xml` and SWIG `%rename` were considered as the build-script alternative and rejected (ADR-179 alternative 3): they exist because those tools do not own the source; a Kotlin author does.

## Recommendation

ADR-179: a new pure-common `nuget-annotations` module with `@CSharpName(val name: String)` (`FUNCTION`, `PROPERTY`, `BINARY`), wired by the plugin to `commonMainApi` next to the runtime wiring, and one helper `KSDeclaration.csharpMemberName()` in a new `forward/ForwardCSharpName.kt` that every member-name site routes through. Semantics: verbatim (no `Async`), validated as a C# identifier (fatal `ERROR_CSHARP_NAME_INVALID`, keywords escaped by `toCSharpName`), inherited by overrides via the root-of-chain walk (a disagreeing override is fatal `ERROR_CSHARP_NAME_OVERRIDE_MISMATCH`), and a declared name that still collides is the same `ERROR_CSHARP_NAME_COLLISION` with the spelling showing the annotation. ABI untouched.

Rejected: reuse `kotlin.native.ObjCName` (renames the Apple API too, experimental opt-in, no `actual` on this plugin's main targets); a build-script rename map (overload-signature string grammar, far from the declaration); author-added dependency without plugin wiring (GOALS #1).

## Files an implementation touches

- New module: `nuget-annotations/build.gradle.kts`, `nuget-annotations/src/commonMain/kotlin/io/github/xxfast/kotlin/native/nuget/annotations/CSharpName.kt`; `settings.gradle.kts`; `.github/workflows/release.yml:53-55`; `scripts/verify.sh` (`--plugin` publication list); `gradle.properties` / version generation if the module needs an ADR-129-style pin (it carries no version constant, so probably not).
- Plugin: `nuget-plugin/.../NugetPlugin.kt` (new `commonMainApi` wiring beside `:230-268`); the plugin's ProjectBuilder test (a `jvm()` + `macosArm64()` consumer configures).
- Processor: new `forward/ForwardCSharpName.kt`; `forward/ForwardDiagnostic.kt` (two new ERROR kinds); member-name sites in `ForwardCallablePlanner.kt`, `ForwardPropertyPlanner.kt`, `ForwardInterfaceBridgePlanner.kt`, `ForwardCirPropertyProjection.kt`, `ForwardCirPlanProjection.kt`, `ForwardCirCollectionComponents.kt`, `ForwardBridgeTypeClassifier.kt`, `cir/CirClassTranslator.kt`, `cir/CirTranslator.kt`, `cir/CirFunctionTranslator.kt`, `cir/CirMemberNameCollisions.kt` (`KotlinSpelling` gains the declared name; `:101` and `:199` change); the three `Async` sites.
- Fixture: `test-library/build.gradle.kts` (add `commonMain`), move `Issue112Sample.kt` to `src/commonMain` and flip `Sequence<Int>` to `ByteArray?` with `@CSharpName("CollarTagBytes")` on the interface method; `Tier1Issue112InterfaceProjectionTest.kt:55-67`, `:107-111`.
- Tests: a new `Tier1CSharpNameTest.kt` (cells: verbatim on suspend, invalid name fatal, keyword escaped, override inherits, override mismatch fatal, declared name collides fatal with annotation in the message, top-level function, companion member, property); `IntegrationTests/Issue112Tests.cs` (or wherever issue112 lives) gains the call.
- Docs: `docs/topics/instance-members.md`, `docs/topics/interfaces-abstract-sealed.md`, `docs/topics/supported-features.md`, `docs/topics/nuget-dsl.md` (what the plugin now wires), `ROADMAP.md:74`, `docs/backlog/author-declared-csharp-member-name.md` (delete), this memo (delete).

## Sample test

Kotlin fixture (`test-library/src/commonMain/.../issue112/Issue112Sample.kt`, the shape after the flip):

```kotlin
import io.github.xxfast.kotlin.native.nuget.annotations.CSharpName

interface Advertisement {
  val identifier: String
  val collarTag: CollarTag?
  val codes: Collection<String>

  /** The issue's literal collision: same Kotlin name as [collarTag], now bridgeable (ADR-151). */
  @CSharpName("CollarTagBytes")
  fun collarTag(code: Int): ByteArray?

  fun describe(prefix: String): String
}

class BleAdvertisement(
  override val identifier: String,
  override val collarTag: CollarTag?,
) : Advertisement {
  override val codes: Collection<String> get() = listOf(identifier)
  // No annotation here: the override inherits `CollarTagBytes` from the interface.
  override fun collarTag(code: Int): ByteArray? = if (code < 0) null else byteArrayOf(code.toByte())
  override fun describe(prefix: String): String = "$prefix$identifier"
}
```

xunit:

```csharp
[Fact]
public void CollarTagBytes_RendersUnderTheDeclaredName_BesideTheProperty()
{
    using var ad = new BleAdvertisement("beacon-1", null);
    IAdvertisement iface = ad;

    Assert.Null(iface.CollarTag);
    Assert.Equal(new byte[] { 7 }, iface.CollarTagBytes(7));
    Assert.Null(ad.CollarTagBytes(-1));
}
```

Tier 1 cells (generator-only): the generated interface contains `byte[]? CollarTagBytes(int code)` and `CollarTag { get; }`; no `ERROR_CSHARP_NAME_COLLISION`; `@CSharpName("collar tag")` is `ERROR_CSHARP_NAME_INVALID`; `@CSharpName("event")` renders `@event`; `@CSharpName("Fetch") suspend fun` renders `Task<..> Fetch(`; an override declaring `@CSharpName("Other")` is `ERROR_CSHARP_NAME_OVERRIDE_MISMATCH`; `@CSharpName("CollarTag")` on the method is `ERROR_CSHARP_NAME_COLLISION` whose message contains `@CSharpName("CollarTag")`.

## Deferred scope

`@CSharpName` on a class, object, interface, enum entry, constructor or parameter. A reverse-direction mirror (a C# `[KotlinName]`) has no asker.

## Open what-questions

1. Should the suspend `Async` suffix still be appended to a declared name? Recommendation: no, verbatim, like `@JvmName`/`@ObjCName`; the author who wants it writes it. Human decision (2026-10-01): verbatim, no suffix.
2. Should the annotations module be wired as `commonMainApi` or `commonMainImplementation`? Recommendation: `api`, so a downstream KMP consumer compiling against the klib never sees an unresolved annotation class; the artifact is one class. Human decision (2026-10-01): `api`, per the recommendation.
3. Must the override repeat the annotation, or inherit it? Recommendation: inherit, error on disagreement; repeating with the same name is allowed. Human decision (2026-10-01): inherit; same-name repeat allowed, different name fatal.
4. Which KMP target set does `nuget-annotations` publish? Recommendation: the full standard set (jvm, js, wasmJs, all Apple, linux x64/arm64, mingwX64, androidNative*), no AGP; spike the `androidTarget()` consumer in the ProjectBuilder test before release. Human decision (2026-10-01): new `nuget-annotations` module, standard target set per the recommendation; the ObjCName reuse is rejected.
