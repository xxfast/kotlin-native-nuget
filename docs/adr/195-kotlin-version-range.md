# ADR-195: Kotlin version range: one plugin release supports a floor up to the last tested Kotlin, checked at configuration time

## Status

Accepted

## Context

Target (the contract): "A consumer can use one plugin release with a range of Kotlin versions, from a stated floor (2.4.0) up to the latest tested release, instead of exactly one pinned version. A Kotlin version below the floor fails the build at configuration time with a message naming the reason, and one above the last tested version warns."

This is a Gradle plugin feature, neither forward nor reverse. No generated C# or Kotlin changes.

Today `docs/topics/prerequisites.md:45-51` promises exactly one Kotlin version per plugin release (`2.4.10` for `0.2.0` to `0.8.0`) and says bumping Kotlin without bumping the plugin is unsupported. Nothing in `nuget-plugin/src/main` reads the consumer's Kotlin version.

What actually ties a release to a Kotlin version (all spiked on 2026-10-04, macOS arm64, Gradle 9.1.0, in a throwaway worktree; commands and output in Verification):

- **The plugin forces its own KGP onto the consumer. Verified by execution.** `nuget-plugin/build.gradle.kts:61-62` declares `implementation(kotlin("gradle-plugin-api"))` and `implementation(kotlin("gradle-plugin"))`, so the published module metadata requires `kotlin-gradle-plugin:2.4.10`. A consumer that declares `kotlin("multiplatform")` at `2.3.21` or `2.4.0` next to the plugin gets KGP `2.4.10` with no message at all: `KotlinBasePlugin.pluginVersion` reports `2.4.10` and the build succeeds on the upgraded compiler. The pin in the docs is therefore not a recommendation, it is silently enforced, downward only.
- **The published klibs set a hard floor. Verified by execution.** `nuget-runtime` and `nuget-annotations` are built by the repo's pinned compiler and carry `abi_version=2.4.0`. Once KGP is no longer forced, a `2.3.21` consumer fails at `compileKotlinMacosArm64` with `KLIB resolver: Skipping '...nuget-annotations-macosArm64Main-0.8.0.klib' having incompatible ABI version '2.4.0'. The library was produced by '2.4.10' compiler.` followed by `Could not find "...klib"`. That is a compile-time failure, late, and it names a cache path rather than the cause.
- **Nothing else couples.** Verified by execution: with KGP `compileOnly`, the same consumer on `2.4.0`, `2.4.10` and `2.4.20` resolves its own KGP, runs KSP `2.3.10` (which the plugin ships and applies itself, `build.gradle.kts:63`, `NugetPlugin.kt:90`), compiles, and on `2.4.0` and `2.4.20` links a shared library exporting the runtime's `nuget_*` symbols. Klib backward compatibility (a newer compiler reads an older klib) is the documented guarantee (Kotlin evolution principles, read 2026-10-04).
- `kotlinNativeVersion` (`build.gradle.kts:21`) and the ADR-060 embedded compiler dependencies are test-only (verified by reading); they do not reach a consumer.

Constraints:

- The floor is not a free choice. It is the `major.minor.0` of the compiler that builds the two klibs, because klib ABI version follows the producing compiler's language version (verified on our own manifests; the general rule is inferred from KT-71007).
- `ProjectBuilder` tests apply the real KGP on the test classpath, so they cannot fake a consumer Kotlin version. The decision logic has to be a pure function to be testable.

## Alternatives Considered

### 1. `compileOnly` KGP, a configuration-time range check, floor and last-tested in one place (chosen)

Stop shipping KGP, read the consumer's Kotlin version from `KotlinBasePlugin.pluginVersion`, fail below the floor, warn above the last tested version, and run the smoke consumer in CI at both ends.

Pros: smallest change that makes the range true; the failure moves from a klib resolver message at compile time to one sentence at configuration time; a consumer on a newer Kotlin stops being told they are unsupported. Cons: the plugin now relies on the consumer's KGP being visible to its classloader (see Consequences); the range is only as wide as the klib ABI allows (2.4.0 and up).

### 2. Fail-fast check only, keep `implementation` KGP

Rejected. Verified by execution: with `implementation`, a `2.3.21` consumer's `pluginVersion` already reads `2.4.10`, so a check below the floor can never fire, and a consumer on a newer Kotlin is the only case that reaches it. The check is unreachable without the `compileOnly` change.

### 3. Lower the floor to 2.3 with `-XXLanguage:+ExportKlibToOlderAbiVersion`

Deferred, out of scope. The flag supports exactly one previous language version and is an unstable `-XX` switch (KT-85359, read 2026-10-04); generated code would also need a conditional `@OptIn(ExperimentalUuidApi::class)` (ADR-106). Not spiked.

### 4. Per-Kotlin-version runtime artifacts selected by the plugin (the SKIE model)

Deferred, out of scope. SKIE needs it because it links compiler internals; this plugin uses three public KGP types and a public KSP API. A publish matrix for a problem the klib rules already solve for 2.4.0 and up.

## Decision

### 1. KGP becomes `compileOnly`

In `nuget-plugin/build.gradle.kts`:

```kotlin
compileOnly(kotlin("gradle-plugin-api"))
compileOnly(kotlin("gradle-plugin"))
testImplementation(kotlin("gradle-plugin"))
implementation("com.google.devtools.ksp:symbol-processing-gradle-plugin:2.3.10") // unchanged
```

Verified by execution: the published module metadata then lists no `kotlin-gradle-plugin` dependency; the plugin applies and the consumer builds on its own KGP with no `NoClassDefFoundError`; three existing `ProjectBuilder` wiring tests pass with the `testImplementation` line.

`testImplementation` alone is not enough (verified by execution): TestKit tests that use `withPluginClasspath()` lost KGP once it became `compileOnly`, and `NugetStrictCompilePropertyTest` failed all 5 rows with `Plugin [id: 'org.jetbrains.kotlin.multiplatform'] was not found`. A `testKitKotlin` configuration (resolvable, carrying `kotlin("gradle-plugin")`) is added to `tasks.pluginUnderTestMetadata`, so the TestKit plugin-under-test classpath holds KGP beside the plugin, the way a consumer that declares both plugins in one `plugins {}` block does. KSP stays `implementation` because the plugin applies it on the consumer's behalf, and KSP's Gradle plugin does not depend on KGP at runtime (verified by reading `symbol-processing-gradle-plugin-2.3.10.module`).

### 2. One source of truth: the root `gradle.properties`

```properties
kotlinFloor=2.4.0
kotlinTested=2.4.20
```

- `nuget-plugin/build.gradle.kts` already loads this file into `rootProperties` (lines 14-18) and already generates `NugetVersion.kt`; `generateVersionConstant` gains `internal const val KOTLIN_FLOOR` and `internal const val KOTLIN_TESTED`, both registered as task inputs.
- `smoke-test/settings.gradle.kts` already loads the same file; it resolves the Kotlin plugin version from `-Psmoke.kotlin=floor|tested|<literal>` (default `tested`) in its `pluginManagement { plugins { } }` block, and `smoke-test/build.gradle.kts` drops its hardcoded `version "2.4.10"`.
- CI names only `floor` and `tested`, never a number.
- The root build has the same override. `settings.gradle.kts` reads `-PkotlinVersion=floor|tested|<literal>` (or `ORG_GRADLE_PROJECT_kotlinVersion`) and applies it with `versionCatalogs.configureEach { if (name == "libs") version("kotlin", ...) }`, so the real tests run this repo on another Kotlin without editing the catalog. Verified by execution: `create("libs") { from(...) }` fails with "you can only call the 'from' method a single time", because Gradle already imports `gradle/libs.versions.toml` as `libs`.

This is not a new hand-duplicated pin of the kind `CLAUDE.md` warns about: both readers take the value from the one file.

**Floor**: the lowest Kotlin whose compiler can read the published klibs. It must equal `major.minor.0` of the repo's own Kotlin pin; a unit test guards it (below). Whoever bumps the repo's Kotlin minor bumps the floor in the same change.

**Last tested**: the highest Kotlin release at which the CI `tested` leg has passed for this plugin release. Bumping it when a new Kotlin ships is a manual one-line change to `kotlinTested` in the root `gradle.properties`; the `tested` CI leg going green on that PR is the evidence. Nothing bumps it automatically. It is independent of the repo's own pin (repo builds on `2.4.10`, tested is `2.4.20`).

### 3. The check

Location: `NugetPlugin.kt`, first statement inside `project.pluginManager.withPlugin(KMP_PLUGIN)` (currently line 89), before `apply(KSP_PLUGIN)`.

Reading the version: `project.plugins.withType(KotlinBasePlugin::class.java).first().pluginVersion`. Verified by execution on 2.3.20, 2.3.21, 2.4.0, 2.4.10 and 2.4.20: it returns the consumer's version and agrees with `getKotlinPluginVersion()`. Verified by `javap`: `KotlinBasePlugin.getPluginVersion()` is in `kotlin-gradle-plugin-api` (present in 2.2.0 and 2.4.10), while `getKotlinPluginVersion` and `kotlinToolingVersion` live in `kotlin-gradle-plugin` (`KotlinPluginWrapperKt`). Use the API one.

The decision is a pure function, so it is testable without a second KGP:

```kotlin
internal sealed interface KotlinSupport {
  data object Supported : KotlinSupport
  data class BelowFloor(val message: String) : KotlinSupport
  data class AboveTested(val message: String) : KotlinSupport
  data class Unrecognised(val message: String) : KotlinSupport
}

internal fun kotlinSupport(current: String, floor: String, tested: String): KotlinSupport
```

Gate decisions (2026-10-04): floor 2.4.0, tested 2.4.20; anything above tested warns, patch releases included; the comparison is numeric `major.minor.patch`; an unparseable version warns and continues; the CI legs verify behaviour, not only linking.

Comparison: the leading numeric `major.minor.patch` only; any suffix is ignored. So `2.4.0-RC2` counts as `2.4.0` and `2.5.0-dev-3513` counts as `2.5.0`. A string with no leading `major.minor.patch` is `Unrecognised`.

Behaviour, with `<plugin>` being `PLUGIN_VERSION`:

- **Below the floor**: `throw GradleException(message)` during configuration. Exact text for `current = 2.3.21`:

  ```
  [nuget] Kotlin 2.3.21 is not supported. kotlin-native-nuget <plugin> needs Kotlin 2.4.0 or newer: its nuget-runtime and nuget-annotations klibs are built at klib ABI 2.4, which an older Kotlin/Native compiler cannot read. Update the Kotlin Gradle plugin to 2.4.0 or newer.
  ```

- **Above last tested**: `project.logger.warn(message)`, configuration continues. Exact text for `current = 2.5.0`:

  ```
  [nuget] Kotlin 2.5.0 is newer than the last version kotlin-native-nuget <plugin> was tested with (2.4.20). It is expected to work. If it does not, update the plugin.
  ```

- **Unrecognised**: `project.logger.warn`, configuration continues:

  ```
  [nuget] Could not read the Kotlin version 'x'; kotlin-native-nuget <plugin> supports Kotlin 2.4.0 to 2.4.20 and did not check this build.
  ```

- **In range** (floor <= current <= tested, both inclusive): silent.

The warning is logged once per project that applies both plugins. No suppression switch in v1.

### 4. Docs table shape (`docs/topics/prerequisites.md`, Compatibility)

```markdown
| kotlin-native-nuget | Kotlin              | KSP (bundled) | Gradle | JDK | .NET   |
|----------------------|---------------------|---------------|--------|-----|--------|
| `0.9.0`              | `2.4.0` to `2.4.20` | `2.3.10`      | `9.1`  | 17+ | `8.0`+ |
| `0.2.0` to `0.8.0`   | `2.4.10`            | `2.3.10`      | `9.1`  | 17+ | `8.0`+ |
```

The sentence under it changes to: the range is a floor (the build fails below it) and a last tested version (the build warns above it); the plugin applies KSP itself, so a consumer does not choose a KSP version. The old sentence "KSP is pinned to its Kotlin version" is removed: KSP 2.3.0 and later are versioned independently of Kotlin (KSP 2.3.0 release notes, read 2026-10-04), and KSP 2.3.10 ran under KGP 2.4.0, 2.4.10 and 2.4.20 (verified by execution).

### 5. CI matrix

The `consumer` job in `.github/workflows/ci.yml` is a `[floor, tested]` matrix on `macos-latest`, one job per end of the range. It does not stop at a link. Each leg:

1. Publishes the plugin, processor, runtime and annotations to the local repo on the repo's pinned compiler (no override), so the artifacts are the ones a release ships.
2. Resolves by coordinate and links the smoke consumer at X (`verifyProcessorResolvesByCoordinate`, `verifyRuntimeResolvesByCoordinate`, `linkDebugSharedMacosArm64`, `-Psmoke.kotlin=<leg>`).
3. Builds the fixtures as an outside consumer: `./gradlew -p fixture-consumer :test-library:packNuget :test-companion:packNuget -Pconsumer.kotlin=<leg>`, then the real `IntegrationTests` and `LeakTests`. `fixture-consumer/` is a second root over the same `test-library`, `test-companion` and `test-models` directories. In it the processor, runtime and annotations are not projects, so the plugin resolves them by coordinate from the local repo of step 1, and only the fixtures are compiled by X.
4. Fails unless the `test-library` klib manifest `compiler_version` equals X and the published `nuget-runtime` klib's equals the repo's pin, so neither a silently ignored `consumer.kotlin` nor a runtime rebuilt on X can pass.

Amended 2026-10-05: step 3 first ran the root build with `ORG_GRADLE_PROJECT_kotlinVersion=<leg>`, which rebuilt the processor and the runtime klib on X as well and so proved "this repo behaves on X", not what a consumer gets. The root `kotlinVersion` override in `settings.gradle.kts` is kept for local use; CI no longer sets it. Step 1 now also publishes the `mingwX64` klibs, because the fixtures pack a `mingwX64` library and a macOS host links it. On a pull request steps 3 and 4 run only when the diff can move them (the path filter in `ci.yml`); a push to `main` always runs them.

No coverage and no NativeAOT run: the `bridge` job owns those on the pinned version. That is 5 macOS jobs per run, exactly the free plan's concurrency cap. `scripts/verify.sh` and `release.yml` keep running the pinned compiler.

## Consequences

- A consumer may declare any Kotlin from 2.4.0 up; the plugin no longer changes which KGP they get. A consumer who was silently upgraded from 2.3.x to 2.4.10 by `0.8.0` now gets the below-floor error instead. That is a behaviour change worth a release note.
- **Verified by execution**: this plugin declared `apply false` in a root project with KGP only in a child fails with `Could not create plugin of type 'NugetPlugin' > Could not generate a decorated class for type NugetPlugin > org/jetbrains/kotlin/gradle/dsl/KotlinMultiplatformExtension`, before `apply()` runs, so the version check cannot explain it. Cause (verified): Gradle's `AbstractClassGenerator.inspectType` reflects every declared method of `NugetPlugin`, and `private fun registerPublish(` plus its local funs and lambdas carry KGP types in their signatures. Workaround, verified: declare both plugins `apply false` in the root project; `prerequisites.md` says so. The fix (about 165 lines out of the plugin class) is split out to the ROADMAP. The `buildSrc` shape was not run.
- **Inferred**: below KGP 2.3.20 the plugin's own classes may fail to load before the check runs (a type the plugin references not existing in a much older KGP). Verified only down to 2.3.20 and 2.3.21, where configuration succeeds and the check would be reached.
- The smoke version read was spiked with `--no-configuration-cache`. As built it uses `providers.gradleProperty("smoke.kotlin")`, and the configuration cache was on in every verification run and resolved it correctly. Verified.
- Raising the repo's Kotlin pin to a new minor raises the floor. The guard test fails until `kotlinFloor` follows.
- Deferred: a floor below 2.4.0 (Alternative 3 or 4); a suppression switch for the warning; a CI leg that asserts the below-floor error end to end.
- Coverage gap: floor and tested legs run macOS only; Windows (`mingwX64`) was not exercised at either. The unrecognised-version branch has no end-to-end run. Tracked in the ROADMAP.
- Not addressed: whether Gradle 9.1 is a real floor. Verified by reading the published module metadata: it carries `org.gradle.jvm.version: 17` and no `org.gradle.plugin.api-version`, so nothing in the metadata rejects an older Gradle. Whether the plugin's code runs on Gradle 8 is not tested.

## Verification

Scratch worktree at `/tmp/kvr-spike` from `kotlin-version-range` (HEAD `7136515f`), removed afterwards. `smoke-test` was parameterised there with `-Psmoke.kotlin` and given a `probeKotlinVersion` task printing `KotlinBasePlugin.pluginVersion`, `getKotlinPluginVersion()` and the KGP jars on the buildscript classpath.

With KGP `compileOnly`, `./gradlew -p smoke-test probeKotlinVersion verifyProcessorResolvesByCoordinate -Psmoke.kotlin=<v> --no-configuration-cache`:

```
2.4.10  PROBE KotlinBasePlugin.pluginVersion=2.4.10 getKotlinPluginVersion=2.4.10   BUILD SUCCESSFUL
2.4.0   PROBE KotlinBasePlugin.pluginVersion=2.4.0 getKotlinPluginVersion=2.4.0     BUILD SUCCESSFUL
        PROBE classpath=[symbol-processing-gradle-plugin-2.3.10.jar, kotlin-gradle-plugin-api-2.4.0-gradle813.jar, kotlin-gradle-plugin-2.4.0-gradle813.jar, ...]
2.4.20  PROBE KotlinBasePlugin.pluginVersion=2.4.20 getKotlinPluginVersion=2.4.20   BUILD SUCCESSFUL
2.3.20  PROBE KotlinBasePlugin.pluginVersion=2.3.20 getKotlinPluginVersion=2.3.20   BUILD SUCCESSFUL
```

With KGP `compileOnly`, `... probeKotlinVersion compileKotlinMacosArm64 -Psmoke.kotlin=<v>`: `kspKotlinMacosArm64` and `compileKotlinMacosArm64` ran and `BUILD SUCCESSFUL` on 2.4.10, 2.4.0 and 2.4.20. On 2.3.21:

```
> Task :compileKotlinMacosArm64 FAILED
w: KLIB resolver: Skipping '.../nuget-annotations-macosArm64Main-0.8.0.klib' having incompatible ABI version '2.4.0'. The library was produced by '2.4.10' compiler.
e: KLIB resolver: Could not find ".../nuget-annotations-macosArm64Main-0.8.0.klib" in [...]
```

With KGP `compileOnly`, `... linkDebugSharedMacosArm64 -Psmoke.kotlin=<v>` then `nm -gU <dylib> | grep -c " _nuget_"`: `BUILD SUCCESSFUL` and `73` on both 2.4.0 and 2.4.20, `_nuget_runtime_version` present in both.

With today's `implementation` KGP (unmodified build script, republished), `-Psmoke.kotlin=2.3.21`:

```
PROBE KotlinBasePlugin.pluginVersion=2.4.10 getKotlinPluginVersion=2.4.10
PROBE classpath=[symbol-processing-gradle-plugin-2.3.10.jar, kotlin-gradle-plugin-api-2.4.10-gradle813.jar, kotlin-gradle-plugin-2.4.10-gradle813.jar, ...]
> Task :compileKotlinMacosArm64
BUILD SUCCESSFUL
```

and the same `pluginVersion=2.4.10` for `-Psmoke.kotlin=2.4.0`.

`./gradlew -p nuget-plugin test --tests "*NugetPluginRuntimeExportWiringTest" --tests "*NugetPluginKspArgsWiringTest" --tests "*NugetPluginComposabilityTest"` with the `compileOnly` plus `testImplementation` edit: `BUILD SUCCESSFUL`.

Caveats on the spike: the klib in the 2.3.21 failure resolved from the Gradle module cache rather than the scratch repo, so it may be the published `0.8.0` artifact rather than the one built in the worktree; both are built by 2.4.10 and the message says so. One intermediate run hit `OutOfMemoryError: Metaspace` in a daemon that had loaded five KGP versions; the rerun on a fresh daemon is the result quoted. Only `macosArm64` was exercised.

### Proposed tests (`nuget-plugin/src/test/kotlin`, written in Step 3)

`NugetKotlinVersionTest`:

- `kotlinSupport_atFloor_isSupported`
- `kotlinSupport_atTested_isSupported`
- `kotlinSupport_betweenFloorAndTested_isSupported`
- `kotlinSupport_belowFloor_failsNamingTheKlibAbi` (asserts the exact message above)
- `kotlinSupport_aboveTested_warnsNamingTheTestedVersion` (asserts the exact message above)
- `kotlinSupport_preReleaseOfFloor_isSupported` (`2.4.0-RC2`)
- `kotlinSupport_devBuildAboveTested_warns` (`2.5.0-dev-3513`)
- `kotlinSupport_unparseableVersion_isUnrecognised`
- `kotlinFloor_matchesTheMinorThisBuildCompilesWith` (`KOTLIN_FLOOR` equals the `major.minor.0` of `KotlinBasePlugin.pluginVersion` of the KGP on the test classpath. Not `KotlinVersion.CURRENT`: verified by execution, in the Gradle test worker it reads `2.2.0`, Gradle's embedded stdlib)
- `kotlinTested_isNotBelowFloor`
- `apply_withKmpAtTheBuildsOwnKotlin_configuresWithoutError` (`ProjectBuilder`, real KGP on the test classpath)
- `apply_withoutKmp_doesNotCheckKotlin` (`ProjectBuilder`, consume-only project)

### Implementation verification (2026-10-04, commit `569c285d` plus this change, macosArm64)

Verified by execution: `scripts/verify.sh --plugin` green at the pinned 2.4.10, at the floor 2.4.0 and at tested 2.4.20. `IntegrationTests` 3036/3036 and `LeakTests` 164/164 at each; plugin tests 822/822 at the pinned version; `test-library` klib manifest `compiler_version` 2.4.10, 2.4.0 and 2.4.20 respectively. The smoke consumer links at floor and tested with 73 `nuget_` exports; 2.3.21 fails at configuration time; 2.5.0-Beta1 warns and builds. Not exercised: Windows (`mingwX64`) at floor or tested.
