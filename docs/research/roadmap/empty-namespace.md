# An empty `nuget.namespace` breaks the whole generated file

- ROADMAP: line text as of 2026-09-29 (Phase 4, ROADMAP.md:35): "Inferred from a spike, not a consumer build: an empty `nuget.namespace` breaks the whole generated file (`namespace ` with nothing after it, `new global::.WireCage(...)`), since `NugetPlugin.kt:312` passes `pub?.packageId ?: ""` and the processor's `Interop` default (`NugetProcessorProvider.kt:31`) never applies once the option is explicitly set to empty."
- Researched: 2026-09-29, about 5 minutes of a 15 minute budget
- Restatement: forward (Kotlin to C#), plugin and processor. A publisher whose `publish {}` resolves no usable `packageId` (null, empty or blank), and a consume-only project with no `publish {}` at all, get a well-formed `Interop.cs` rooted at the processor's `Interop` default instead of a file with a bare `namespace ` header and `global::.X` references. `packNuget`, which genuinely cannot proceed without a package id, fails with a clear message instead.
- Verdict: fix. No ADR: the chosen rule ("a blank option means absent") is the one every other option in `NugetProcessorProvider.kt` already follows, and there is no second idiomatic C# shape to choose between.

## Findings

1. **The symptom is real, and wider than the ROADMAP line says.** Verified by a Tier 1 spike (a throwaway test file in `nuget-processor/src/test/.../tier1/`, run with `./gradlew.bat :nuget-processor:test --tests "*ZzSpikeEmptyNsTest*" -i`, then deleted; worktree left clean). Sources: `package tier1.cage; class WireCage(val size: Int) { class Latch(val open: Boolean) }; fun makeCage(size: Int): WireCage; enum class Colour { RED }; fun colour(): Colour`, `nuget.rootPackage = "tier1.cage"`. Real output, lines containing `namespace` or `global::`:
   ```
   =====SPIKE empty compiledClean=true
   namespace 
               [typeof(global::.WireCage)] = static handle => new global::.WireCage(handle, out _),
               [typeof(global::.WireCage.Latch)] = static handle => new global::.WireCage.Latch(handle, out _),
   =====SPIKE blank compiledClean=true
   namespace   
               [typeof(global::  .WireCage)] = static handle => new global::  .WireCage(handle, out _),
               [typeof(global::  .WireCage.Latch)] = static handle => new global::  .WireCage.Latch(handle, out _),
               public static global::  .WireCage MakeCage(int size)
                   return new global::  .WireCage(nativeResult, out _);
               public static global::  .Colour Colour()
                   return (global::  .Colour)result;
   =====SPIKE absent compiledClean=true
   namespace Interop
               [typeof(global::Interop.WireCage)] = static handle => new global::Interop.WireCage(handle, out _),
               ...
               public static global::Interop.WireCage MakeCage(int size)
   ```
   Three things this proves (verified): `compiledClean` is `true` in every case, so no existing Tier 1 cell can catch this (Tier 1 compiles Kotlin, not the C#); the empty case is broken in two different ways at once (the handle registry renders `global::.WireCage` while the `MakeCage` return, via `ForwardBridgeTypeClassifier`'s `isEmpty()` bare-name branch, drops the qualifier entirely, which is why it does not appear in the `global::`-filtered empty output); and a **blank** value (`"  "`) is not caught by any of the processor's `isEmpty()` guards, so it renders `global::  .WireCage` everywhere. Any fix must treat blank like empty.

2. **The plugin passes `""` far more often than "an explicitly empty packageId".** Verified by reading. `NugetPublishConfig.kt:6` declares `var packageId: String? = null`, so `NugetPlugin.kt:312` (`pub?.packageId ?: ""`) passes `""` whenever `publish {}` omits `packageId` **and** whenever there is no `publish {}` at all (the consume-only case ADR-050 Alternative 6 keeps configurable, `NugetPlugin.kt:273-278`). The same fallback is at `NugetPlugin.kt:171` (`task.forwardNamespace`, the shims' ADR-087 `errorNamespace`) and `NugetPlugin.kt:314` (`nuget.className`, which becomes `"Native"` for an empty id and `"LibraryNative"` for a null one).

3. **The processor default only covers an absent option.** Verified by reading: `NugetProcessorProvider.kt:31` is `environment.options["nuget.namespace"] ?: "Interop"`. Its sibling options already treat blank as absent: `nuget.boundTypesManifest` (`:19-21`, `takeIf { path -> path.isNotBlank() }`), and every list option filters blank entries (`:39-47`). The namespace read is the odd one out.

4. **Every processor site that branches on an empty root namespace.** Verified by grep (`rootNamespace` under `nuget-processor/src/main`): `ForwardBridgeTypeClassifier.kt:78` (`csharpNamespaceOf` returns null), `:501` (interface bare name), `:546` (nested bare name); `CirTypeMapping.kt:346` (`I$simpleName`), `:395` (nested bare name); `CirClassTranslator.kt:3855`; `mapPackageToNamespace` at `CirTypeMapping.kt:303-327` returns `rootNamespace` verbatim, so `""` becomes the `namespace ` header; `CirTranslator.kt:878-926` inserts the helper/runtime namespaces under `context.rootNamespace`. These `isEmpty()` branches exist for unit tests that build a context without a namespace (`ForwardBridgeTypeClassifier.kt:30`, `val rootNamespace: String = ""`); inferred, from the default value and the "nothing to qualify with" KDoc at `CirTypeMapping.kt:384`. None needs to change if the provider never hands the processor a blank value; they become unreachable from a real build.

5. **The shims must agree with the processor.** Verified by reading `NugetGenerateShimsTask.kt:2626`: `val ex = if (errorNamespace.isEmpty()) "" else "$errorNamespace."`, and the KDoc at `:2622-2625` says unqualified names are chosen deliberately when "no publish {} configured a forward namespace". Inferred consequence: if only the processor is fixed, a `publish {}` without `packageId` gets forward `KotlinException` types in `namespace Interop` while the shims (in `namespace IoGithubXxfast.KotlinNativeNuget`) reference them unqualified, a CS0246 in `nugetCompileInterop`. So `NugetPlugin.kt:171` and `:312` must resolve the namespace with one shared rule.

6. **`packageId` is documented as required.** Verified by reading `docs/topics/nuget-dsl.md:24` (`packageId | String? | yes`). The `Interop` default is not documented on any topic page (verified by grep of `docs/topics`); it lives only in the processor. `PackNugetTask.kt:72` does `packageId.get()`, so a null id fails `packNuget` with Gradle's generic missing-value error, but only after its dependency `nugetCompileInterop` has already compiled the broken `Interop.cs` (inferred from the dependency order at `docs/topics/gradle-tasks.md:14`; the user would see C# compiler errors first). A blank id passes `get()` and would produce a `.nuspec` `<id>` of spaces and a `  .1.0.0.nupkg` file name (inferred from `NugetPlugin.kt:595`).

7. **No plugin test asserts `nuget.namespace`, `nuget.className` or `forwardNamespace`.** Verified by grep of `nuget-plugin/src/test`. `NugetGenerateShimsTaskWiringTest.kt:46` configures `publish { }` with no `packageId`, so a configuration-time `require(packageId)` would break it (verified by reading); failing clearly has to happen at `packNuget` execution, not in `afterEvaluate`.

## Recommendation

Default, do not fail, for code generation; fail clearly only where a package id is truly needed.

1. **Processor** (`NugetProcessorProvider.kt:31`): `environment.options["nuget.namespace"]?.takeIf { it.isNotBlank() } ?: "Interop"`. Blank and absent mean the same thing, matching `:19-21`. This alone fixes the generated `Interop.cs` for every caller, including third-party KSP setups and older plugins.
2. **Plugin**: one private resolver, e.g. `fun NugetPublishConfig?.forwardNamespace(): String = this?.packageId?.takeIf { it.isNotBlank() } ?: "Interop"`, read at `NugetPlugin.kt:171`, `:312`, and used for `:314` (`"${forwardNamespace}Native"`, or keep `"Library"` for the className fallback; see what-questions). The shims and the processor then always agree, so the unqualified branch at `NugetGenerateShimsTask.kt:2626` is only the task's own default, never wired.
3. **packNuget** (`PackNugetTask.kt:72`, execution time): `require(!id.isNullOrBlank()) { "nuget { publish { packageId = ... } } is required to run packNuget" }`, mirroring the existing message shape in `NugetExtensionTest.kt:53`. Better: make `packNuget`'s check fire before `nugetCompileInterop` compiles anything, which only works if it is an input validation on a task that runs first; inferred, not checked, so a plain execution-time `require` in `PackNugetTask` is the minimum.

Why default rather than fail for code generation: a consume-only project (no `publish {}`) is a supported configuration (ADR-050 Alternative 6) whose KSP run still happens, so failing on a missing namespace there would break a configuration the plugin promises to accept. The id matters only to packaging, so that is where the failure belongs.

Alternatives rejected:
- Default only in the plugin: leaves the processor accepting a blank option from any other caller, and the spike shows blank is worse than empty.
- Default only in the processor: the shims keep `""` and diverge from the processor's `Interop` (finding 5).
- Substitute `Interop` in `ForwardBridgeTypeClassifier`'s bare-name branch (the backlog file's second shape): patches one of seven sites, and the `namespace ` header and registry `global::.X` would stay broken.
- Fail in `afterEvaluate` when `publish {}` has no `packageId`: breaks `NugetGenerateShimsTaskWiringTest.kt:46` and in-progress configurations (finding 7).

## Files an implementation touches

- `nuget-processor/src/main/kotlin/io/github/xxfast/kotlin/native/nuget/processor/NugetProcessorProvider.kt` (line 31)
- `nuget-plugin/src/main/kotlin/io/github/xxfast/kotlin/native/nuget/NugetPlugin.kt` (lines 171, 312, 314)
- `nuget-plugin/src/main/kotlin/io/github/xxfast/kotlin/native/nuget/PackNugetTask.kt` (line 72)
- New Tier 1 cell: `nuget-processor/src/test/kotlin/io/github/xxfast/kotlin/native/nuget/processor/tier1/Tier1EmptyNamespaceTest.kt`
- `nuget-plugin/src/test/kotlin/io/github/xxfast/kotlin/native/nuget/NugetPluginKspArgsWiringTest.kt` (new cells)
- Docs: `docs/topics/nuget-dsl.md:24` could state what the generated C# namespace is when `packageId` is absent (optional)
- ROADMAP.md:35 and `docs/backlog/empty-nuget-namespace-breaks-generated-file.md` deleted on close
- No fixture, xunit test or leak row: Tier 1 plus ProjectBuilder cover it.

## Sample test

Tier 1 (fails today, verified by the spike output above):

```kotlin
class Tier1EmptyNamespaceTest {
  private val sources = mapOf("Cage.kt" to """
    package tier1.cage

    class WireCage(val size: Int) { class Latch(val open: Boolean) }
    fun makeCage(size: Int): WireCage = WireCage(size)
  """.trimIndent())

  @Test
  fun `an empty or blank namespace falls back to Interop`() {
    for (blank in listOf("", "  ")) {
      val result = Tier1Harness.run(
        sources,
        processorOptions = mapOf("nuget.rootPackage" to "tier1.cage", "nuget.namespace" to blank),
      )
      assertTrue(result.compiledClean, "got: ${result.compileErrors}")
      val cs: String = result.generatedCSharp
      assertContains(cs, "namespace Interop\n")
      assertContains(cs, "new global::Interop.WireCage(")
      assertContains(cs, "public static global::Interop.WireCage MakeCage(")
      assertFalse(Regex("""global::\s*\.""").containsMatchIn(cs), "bare global:: in: $cs")
      assertFalse(Regex("""(?m)^namespace\s*$""").containsMatchIn(cs), "empty namespace header in: $cs")
    }
  }
}
```

ProjectBuilder (in `NugetPluginKspArgsWiringTest`, same `buildProjectWithSharedLib()`):

```kotlin
@Test
fun `publish without packageId wires the Interop namespace to KSP and the shims`() {
  val project: Project = buildProjectWithSharedLib()
  project.extensions.getByType(NugetExtension::class.java).publish { version = "1.0.0" }
  project.evaluate()

  val args: Map<String, String> = project.extensions.getByType(KspExtension::class.java).arguments
  assertEquals("Interop", args["nuget.namespace"])
  // and, for a blank packageId = "  ", the same; and nugetGenerateShims.forwardNamespace == "Interop"
}
```

A `PackNugetTask` unit cell for the blank-id message, if the existing `PackNugetTask` tests have a harness that runs the action (not checked).

## Deferred scope

- A non-blank but invalid C# namespace (`"My Lib"`, `"1Cat"`, `"Cat-Lib"`) still renders verbatim. `packageId` legally contains `-` (NuGet ids do), which is not a legal C# identifier character; whether that already breaks today is not checked and is a separate item if so.
- The processor's `isEmpty()` branches (finding 4) stay as unit-test affordances; removing them is cleanup, not this fix.

## Open what-questions

1. Trim a non-blank value with surrounding whitespace (`" Cat "`) or pass it verbatim? Recommendation: `isNotBlank` only, no trim, matching `:19-21`; a padded id is a user typo that the C# compiler reports loudly.
2. `nuget.className` for a missing id: keep `"LibraryNative"` (today's null behaviour) or derive `"InteropNative"` from the resolved namespace? Recommendation: keep `"LibraryNative"` for both null and blank, the smallest behaviour change.
3. Should the consume-only (no `publish {}`) shims also move from unqualified to `Interop.`-qualified exception names? Recommendation: yes, via the single resolver, so the processor and shims can never disagree; no fixture exercises consume-only C# today (verified: `smoke-test` and `test-library` both declare `publish {}`), so nothing observable regresses.
4. Document the `Interop` fallback on `nuget-dsl.md`, or keep `packageId` "required" and leave the fallback undocumented? Recommendation: one clause in the `packageId` row.
