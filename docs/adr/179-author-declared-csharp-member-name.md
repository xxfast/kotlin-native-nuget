# ADR-179: `@CSharpName` on a member: an author-declared C# name that resolves a CS0102 collision without renaming the Kotlin API

## Status

Proposed

## Context

Issue [#366](https://github.com/xxfast/kotlin-native-nuget/issues/366). Since
[ADR-151](151-bytearray-mapping.md) made `ByteArray` bridge, this ordinary KMP interface

```kotlin
interface Record {
  val payload: Payload?
  fun payload(code: Int): ByteArray?
}
```

fails the build with `ERROR_CSHARP_NAME_COLLISION` (`ForwardDiagnostic.kt:381`, emitted from
`CirMemberNameCollisions.kt:151-189` and `:225-288`, `CirClassTranslator.kt:3741-3790` and
`:3916-3945`; verified by reading): both members render `Payload`, and C# cannot declare a property
and a method with one name on one type (CS0102). The error is right per
[ADR-110](110-top-level-function-pascal-case.md) and [ADR-113](113-interface-declaration-on-the-forward-plan.md)
("a rename is a silently different API", `CirMemberNameCollisions.kt:32`), and its only hint is
"rename one of them" (`CirMemberNameCollisions.kt:199`). For a KMP library that rename lands on
Android and iOS too, to satisfy a C#-only rule. `Issue112Sample.kt:89` hid the case by returning
`Sequence<Int>` (refused by name) so the colliding method is dropped before the guard runs.

What is wanted: one annotation on one member that sets its C# name. ADR-110/113 reject only a name
the generator picks on its own; a name the author writes is not silent. Not wanted: automatic
renaming or suffixing, skipping one side (ADR-055's both-halves contract), or a warning.

Constraints found in source:

- There is no annotations module. `nuget-runtime` is native-only (`nuget-runtime/build.gradle.kts:11-14`,
  four native targets) and the plugin wires it to `${target}MainApi` plus `export()`
  (`NugetPlugin.kt:230-268`); [ADR-130](130-reverse-error-envelope-on-runtime.md) variant 3 rejected a
  `commonMain` `api` on it because a JVM/JS consumer would fail at configuration. Verified by reading.
- [ADR-063](063-forward-declaration-level-export-scoping.md):130-141 rejected a per-declaration *export* annotation
  for v1, one objection being "a source dependency the export module must take on". That objection was
  about ceremony on every declaration; here the annotation is written once, on the one member that
  collides, so the dependency is the whole feature, not overhead on it.
- ADR-037:274 and ADR-021:371 mention a planned `nuget-annotations` module that was never built.
- The processor already reads annotations by qualified name through the inline idiom
  (`ForwardOptInMarkers.kt:36-77`, `exports/Helpers.kt:288-334`), including an annotation declared one
  klib boundary away (`CatteryInternalApi` in `:test-models`, read for `Issue113Sample.kt:205`), and
  already reads an *argument* off an annotation (`isHiddenByDeprecation`, `Helpers.kt:288-305`, three
  value shapes reduced to a simple name). Verified by reading.
- There is no chokepoint for a member's C# name: `replaceFirstChar { it.uppercase() }` is recomputed at
  55 sites across 15 files (`grep -rc`, verified: `CirClassTranslator.kt` 19, `ForwardCallablePlanner.kt`
  11, `ForwardCirPlanProjection.kt` 4, `CirTranslator.kt` 4, `CirFunctionTranslator.kt` 3, two each in
  `ForwardInterfaceBridgePlanner.kt`, `ForwardCirPropertyProjection.kt`, `ForwardBridgeTypeClassifier.kt`,
  `CirMemberNameCollisions.kt`, one each in `ForwardPropertyPlanner.kt`, `ForwardDiagnostic.kt`,
  `ForwardCirCollectionComponents.kt`, `CirTypeMapping.kt`, `CirNativeImports.kt`, `ForwardSymbolTable.kt`).
  Not all are member names (some build type, file-class or native-stem names). The suspend `Async`
  suffix is appended at `CirFunctionTranslator.kt:792`, `CirClassTranslator.kt:2041`, `:2150`, and
  mirrored by the collision checker at `CirMemberNameCollisions.kt:101`.
- `@CName` export symbols ([ADR-163](163-export-symbol-package-qualification.md)) and ADR-095 overload
  numbering derive from the Kotlin name, so a C#-only rename never touches the native ABI. Verified by
  reading `ForwardSymbolTable.kt` and ADR-095.

## Alternatives Considered

### 1. A new common `nuget-annotations` module with `@CSharpName(name)`, wired by the plugin to `commonMainApi` (chosen)

A pure-`commonMain` KMP module publishing one annotation class, no code. The plugin adds it as
`commonMainApi` beside the existing `${target}MainApi` runtime wiring, so an author writes
`@CSharpName("PayloadBytes")` in `commonMain` with nothing else to add. The processor reads it by
qualified name through the existing idiom.

Pros: the author's declaration is visible where the member is declared, the same shape as
`@JvmName`, `@JsName`, `@ObjCName`; C#-only, no side effect on another platform; the new dependency
is tiny and precedented (kotlinx.serialization's annotations ride in a published KMP artifact).

Cons: a new published artifact (release workflow, version pin, ADR-129-style version drift risk);
it must publish a variant for every target a consumer declares or configuration fails (ADR-130's
objection, which a native-only module could not meet and an all-targets module can).

### 2. Reuse the stdlib's `kotlin.native.ObjCName` (rejected)

No new module, and `@ObjCName` is the precedent ADR-064:123 cites. Verified by reading
`kotlin-stdlib-2.4.10-common-sources.jar` (`commonMain/kotlin/annotations/NativeAnnotations.kt:62-73`):

```kotlin
@Target(CLASS, PROPERTY, VALUE_PARAMETER, FUNCTION)
@Retention(BINARY) @MustBeDocumented @OptionalExpectation @ExperimentalObjCName @SinceKotlin("1.8")
public expect annotation class ObjCName(val name: String = "", val swiftName: String = "", val exact: Boolean = false)
```

Rejected on semantics and on mechanism:

- It renames the member for ObjC and Swift too. #366's author has an iOS target; `fun payload(code)`
  does not collide in ObjC (`payload` vs `payloadCode:`), so the annotation would change the Apple API
  to fix a .NET one, which is exactly the cross-platform rename the issue refuses.
- `@ExperimentalObjCName` is `@RequiresOptIn` (`commonMain/kotlin/experimental/ExperimentalObjCName.kt`,
  verified by reading), so every use needs `@OptIn(ExperimentalObjCName::class)` or a compiler flag;
  the stdlib can withdraw or reshape it.
- `@OptionalExpectation` with no `actual` on `mingwX64`/`linuxX64` (inferred, not spiked): the
  compiler drops the annotation on targets without an actual, and whether KSP's `annotationType.resolve()`
  still yields the qualified name on those targets is unverified. This plugin's primary targets are
  the ones without an actual.

### 3. A rename map in the build script, `nuget { publish { rename("...Record.payload(Int)", "PayloadBytes") } }` (rejected)

The Xamarin `Metadata.xml` / SWIG `%rename` shape, and the shape ADR-063 chose for export filtering
(a package filter in the build script, no source dependency). The plugin already passes string
options to KSP (`NugetPlugin.kt:311-315`, `nuget.libraryName`, `nuget.namespace`).

Rejected: the key must spell a Kotlin overload unambiguously (a string signature grammar to design,
document and parse), it lives a file away from the member it renames, and nothing in the IDE links the
two. Xamarin needs an external file because it does not own the Java source; a Kotlin author owns
theirs. Kotlin's own precedent for a per-platform name is an annotation on the declaration, every time.

### 4. Author adds the dependency by hand, no plugin wiring (rejected variant of 1)

Avoids the all-targets publishing question by making it the author's problem. Rejected: GOALS #1
("just add the plugin"), and the plugin already wires the runtime; one more `api` line in the same
block is the right place.

## Decision

Alternative 1. The three parts:

### The annotation

New module `nuget-annotations` (`settings.gradle.kts` `include`, `mavenPublish` like `nuget-runtime`),
one file in `commonMain`:

```kotlin
package io.github.xxfast.kotlin.native.nuget.annotations

/**
 * The exact C# name of this member, in place of the generated PascalCase one. Written on the one
 * member whose generated name collides with another's (CS0102); never changes the Kotlin name, the
 * native export symbol, or any other platform's name.
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
@MustBeDocumented
public annotation class CSharpName(val name: String)
```

`BINARY`, not `SOURCE`, because a dependency owner admitted through the ADR-066 closure is read from
a klib, and KSP sees only what the klib metadata kept (inferred from the `CatteryInternalApi`
cross-module read, which is `BINARY`-retained; not spiked with a `SOURCE` one). The `jvm` target compiles to JVM 1.8 bytecode (verified: left at the build JDK's default, the
class file was version 65 and the processor's JVM 17 test run failed with
`UnsupportedClassVersionError`, which any JVM 17 consumer would hit too). Targets: the standard
KMP set the Kotlin team publishes libraries for (jvm, js, wasmJs, every `ios*`/`macos*`/`tvos*`/`watchos*`,
`linuxX64`, `linuxArm64`, `mingwX64`, `androidNativeArm64` etc.). Inferred: an `androidTarget()`
consumer resolves the `jvm` variant through KGP's `KotlinPlatformType` compatibility rule, so no AGP
in this module; a ProjectBuilder test with a `jvm()` + `macosArm64()` consumer must prove the wiring
configures (this is the one claim that breaks loudly, not silently, if wrong).

Plugin wiring (`NugetPlugin.kt`, beside `:230-268`): `project.dependencies.add("commonMainApi", annotationsDep)`
where `annotationsDep` is `project.findProject(":nuget-annotations") ?: "io.github.xxfast:nuget-annotations:$PLUGIN_VERSION"`,
the same local-vs-published fallback the runtime uses. No `export()`: nothing in it is a `@CName`.

### The chokepoint

New `forward/ForwardCSharpName.kt`:

```kotlin
internal fun KSDeclaration.declaredCSharpName(): String?   // the validated annotation argument, or null
internal fun KSDeclaration.csharpMemberName(): String      // declared, else PascalCase(simpleName), unescaped
```

Amended during implementation (verified): `csharpMemberName()` returns the name **unescaped**. A
forward plan refuses a C#-escaped public name at plan time (`Forward plan ... public signature name
@event must not be C#-escaped at plan time`), and the renderer escapes; CIR sites that build an
identifier themselves wrap the result in `toCSharpName`. Validation (rules 2 and 3 below) is one
pass over `getSymbolsWithAnnotation` at the start of the round, not inside the helper.

Semantics, each a cell in the Tier 1 test:

1. **Verbatim.** The declared name is the C# name, not PascalCased, not suffixed. A `suspend fun`
   with `@CSharpName("Fetch")` renders `Fetch`, not `FetchAsync`; the author who wants the suffix
   writes it. `@JvmName` and `@ObjCName` are both verbatim, and a name the author spelled out is the
   one place a convention should not be layered back on top. The three `Async` sites and
   `CirMemberNameCollisions.kt:101` therefore branch on `declaredCSharpName() != null`.
2. **Validated, fatal.** `^[A-Za-z_][A-Za-z0-9_]*$` or new `ERROR_CSHARP_NAME_INVALID` (ERROR) naming
   the declaration and the string. A C# keyword passes validation and is escaped by `toCSharpName`
   (`Reserved.kt:55`) like every generated name, so `@CSharpName("event")` renders `@event`.
   `@JvmName` rejects an invalid JVM name at compile time (inferred, Kotlin docs); same posture.
3. **Overrides inherit; a disagreeing override is fatal.** `csharpMemberName()` walks
   `findOverridee()` to the root (the walk `ForwardCallablePlanner.kt:1855-1866` already does for
   another purpose) and takes the root's declared name, so `override fun payload(code)` on the class
   renders `PayloadBytes` and implements `IRecord.PayloadBytes` without repeating the annotation. An
   override that declares a *different* name is `ERROR_CSHARP_NAME_OVERRIDE_MISMATCH` (ERROR), the
   posture of the Kotlin compiler's `INCOMPATIBLE_OBJC_NAME_OVERRIDE` (inferred, not verified against
   the compiler source). A declared name on an override whose root is unannotated renames the C#
   member on the subclass only, which breaks the interface implementation, so that is the same
   mismatch error; the annotation goes on the root declaration.
4. **A declared name that still collides is the same error.** `KotlinSpellings.record` keeps the
   Kotlin spelling and gains an `declared: String?` so the message reads
   `` `fun payload()` (`@CSharpName("Payload")`) `` and the hint no longer says only "rename": "rename
   one of them, or give one a different `@CSharpName`".
5. **Routing.** Every site that renders a *member* name (function, property, companion member,
   interface projection, bridge planner, collision registry) calls `csharpMemberName()`. Sites that
   build a *type*, file-class or native stem name do not (a class name is out of scope, and the native
   stem must keep the Kotlin spelling or the `@CName` and `DllImport` drift apart). The site list by
   file is in the research memo; the implementing agent classifies each of the 55 while routing.

### Scope

v1: `fun` and `val`/`var` members of a class, object, companion, interface and top-level file class,
on the planner and legacy routes alike (both read the same helper). The ABI is untouched: `@CName`
symbols (ADR-163) and ADR-095 numbering stay on the Kotlin name, so an author can add the annotation
in a patch release of their library and only the C# member name changes.

Deferred: `@CSharpName` on a class/object/interface (a type-name collision has its own ADR-007 `Kt`
answer and no issue asks), on a parameter (C# named arguments would be the consumer, nothing collides),
on an enum entry, on a constructor.

## Consequences

- New published artifact `nuget-annotations`: `settings.gradle.kts`, `release.yml:53-55` publication
  list (the memory note "0.7.0 went out without nuget-runtime" is the hazard), `verify.sh --plugin`.
- `NugetPlugin.kt` gains one `commonMainApi` wiring and the plugin's ProjectBuilder test gains a
  mixed jvm+native consumer cell.
- `Issue112Sample.kt` drops its `Sequence<Int>` stand-in for the real `ByteArray?` with the annotation
  on the interface method, and `Tier1Issue112InterfaceProjectionTest.kt:55-67,107-111` flip from
  "the method is absent" to "the method is `CollarTagBytes`". A `commonMain` source set is added to
  `test-library` for that file so the fixture also proves the `commonMain` claim.
- Two new fatal diagnostics on `ForwardDiagnostic.kt`.
- `docs/topics/instance-members.md` and `interfaces-abstract-sealed.md` document the annotation;
  `supported-features.md` gains a row.

## Inferred claims, listed

1. A `commonMain` declaration's annotation is visible to the native-target KSP run through the idiom
   (the repo proves `nativeMain` and cross-klib; `commonMain` is compiled into the same native
   compilation per KGP's source-set model, so this is expected, and the flipped fixture proves it
   on the first `verify.sh`). Fails loudly if wrong: the collision error keeps firing.
2. KGP's `androidJvm` to `jvm` platform-type compatibility lets an Android consumer resolve the
   annotations module without an Android variant. Fails loudly at configuration if wrong.
3. `SOURCE` retention would not survive into a klib for ADR-066 dependency owners; `BINARY` chosen
   without spiking `SOURCE`.
4. `@OptionalExpectation` ObjCName resolution on actual-less targets under KSP (only matters for
   the rejected alternative).
5. The Kotlin compiler's `INCOMPATIBLE_OBJC_NAME_OVERRIDE` posture (precedent for rule 3 only).
