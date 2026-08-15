# ADR-100: Forward diagnostic delivery: a tracked KSP output file re-emitted by a Gradle task, because the KSP console channel is invisible and skipped on cached builds

## Status

Accepted

## Context

Every forward "cannot express this" decision already builds a named
[ADR-064](064-forward-unsupported-declaration-diagnostics.md) `ForwardDiagnostic` and routes it
through `ForwardDiagnosticSink.emit(...)`
(`forward/ForwardDiagnostic.kt:129-141`). Fourteen kinds exist, four producers feed the sink
(`NugetProcessor.kt:542-545`), and the whole mitigation story of the breaking change
[ADR-097](097-enum-collection-components.md) just shipped rests on those diagnostics reaching a
human: a member that vanishes from the generated C# is supposed to vanish *loudly*.

**It reaches nobody.** The contract this ADR covers, and nothing wider: a consumer running
`packNuget` on a library containing an unsupported declaration sees a warning naming the declaration
and why it was skipped. Not "the diagnostic is computed". Not "a unit test can observe it".

### What is actually broken (all Verified by execution, three runs, real numbers)

**S1**, `./gradlew :test-library:kspKotlinMacosArm64 --rerun-tasks --console=plain`:

| Measurement | Result |
|---|---|
| Task line | `> Task :test-library:kspKotlinMacosArm64`, no `UP-TO-DATE`/`FROM-CACHE` suffix (it genuinely executed) |
| `[ksp]` lines | **0** |
| `nuget:SKIPPED` lines | **0** |
| `logSpans` mentions | **0** |
| Reverse `[nuget:...]` warnings in the same log | present, e.g. `w: [nuget:MimeMapping] Skipping MimeUtility.TypeMap(TypeMap): indexer ...` |

**S2**, same plus `--info`. The decisive run:

| Measurement | Result |
|---|---|
| `loaded provider` (KSP's **own** stdout line, `KspAATask.kt:773`, not ours) | **0** |
| `Generated bindings for` (our own info line, `NugetProcessor.kt:590`) | **0** |
| `[ksp]` | **0** |
| `nuget:SKIPPED` | **0** |

KSP's own startup line being absent at `--info` is what settles it. Nothing about our processor's
classification, our severity choice, or our message format can explain a missing line we do not
emit. **The entire KSP stdout channel is invisible in this build.**

**S3**, `./gradlew :test-library:packNuget --console=plain`, twice:

| Run | KSP task line | `nuget:SKIPPED` |
|---|---|---|
| 1 | `> Task :test-library:kspKotlinMacosArm64 FROM-CACHE` | **0** |
| 2 | `> Task :test-library:kspKotlinMacosArm64 UP-TO-DATE` | **0** |

So there are **two independent defects**, and the contract needs both fixed:

1. **The KSP transport does not reach the console even when the task runs** (S1, S2).
2. **A normal `packNuget` does not run the task at all** (S3), so any transport that only speaks
   during the task action is silent on every incremental build, which is most builds.

### Why the transport is invisible (Verified by source reading, KSP 2.3.10 + Gradle 9.1.0)

Read from the artifacts this build actually resolves, in `~/.gradle/caches` and
`~/.gradle/wrapper/dists`:

- All platforms, **including native**, run through `KspAATask`; there is no separate native task
  type in KSP2 (`KspSubplugin.kt:202`). Work is submitted with `workerExecutor.noIsolation()`
  (`KspAATask.kt:174`), so the processor runs on a Worker API thread inside the Gradle daemon.
- The processor's `environment.logger` is `KspGradleLogger(logLevel.ordinal)` (`KspAATask.kt:767`,
  `KSPLoader.loadAndRunKSP`), wrapped only by a delegating object that tracks `hasError`
  (`KotlinSymbolProcessing.kt:461-475`). No buffering, no deferral.
- `KspGradleLogger` (`com/google/devtools/ksp/KspGradleLogger.kt:23-62`) holds
  `private val messager = System.out` and prints `w: [ksp] ...` when `loglevel <= 3`. Its constants
  are `LOGGING=0, INFO=1, WARN=3, ERROR=5`.
- `loglevel` is `LogLevel.entries.first { project.logger.isEnabled(it) }.ordinal`, captured at
  configuration time and stored as an `@Input` (`KspAATask.kt:359-361`, `:660-662`).
- Gradle `LogLevel` ordinals are `DEBUG=0, INFO=1, LIFECYCLE=2, WARN=3, QUIET=4, ERROR=5`
  (**Verified**, `javap` on `gradle-logging-api-9.1.0.jar` from this repo's own wrapper dist), so at
  the default console level `loglevel = 2` and `2 <= 3` holds.

On paper the warning prints. It does not. The mechanism gap is `System.out.println` from a Worker
API thread: [gradle/gradle#21395](https://github.com/gradle/gradle/issues/21395) (open, unresolved)
documents that output produced from async/worker threads inside a task is not attributed to the
task's logging context, and Gradle's own answer in that thread is "use the task logger API, not
`println`". **Inferred** (the issue reports mis-attribution; S2 shows total absence in this build, so
the exact Gradle-side disposal path is unconfirmed). The precise mechanism does not change the
decision: what S1/S2 establish by execution is that no message we hand to `KSPLogger` arrives, and
that is enough.

### Why the reverse direction works, and what that tells us

`NugetGenerateBindingsTask.kt:5855` does `diagnosticWarnings(rir).forEach { logger.warn(it) }` on
**Gradle's own `Task.logger`**, from the task thread, at Gradle's WARN level (**Verified**, repo
code, and confirmed present in S1's log). Same build, same console, two completely different
transports, and only one of them is the one Gradle tells plugin authors to use. The forward
direction should stop inventing a second channel.

### The trap this got past, named

`Tier1EnumCollectionComponentTest.kt:212-260` asserts a `SKIPPED_UNSUPPORTED_INPUT` warning naming
`logSpans` and is green. It proves the **producer**. It cannot prove the **transport**, because
`Tier1Harness.kt:155` injects its own `RecordingKSPLogger` straight into `KotlinSymbolProcessing`,
which is exactly the layer that differs in production (and the harness is JVM-mode,
`KSPJvmConfig`, while production is `KSPNativeConfig`). This is CLAUDE.md's "every layer's unit tests
are green, but the real pipeline is broken end to end" row, one level up: a green sink test says the
sink emits *given* a logger, and says nothing about whether anyone can read that logger's output.

## Alternatives Considered

### 1. A tracked KSP output file, re-emitted by a cheap Gradle task (chosen)

The processor writes its diagnostics to a machine-readable file through
`CodeGenerator.createNewFile(...)`, which makes the file a declared KSP output. A small
`packNuget`-side task reads that file and re-emits every line through Gradle's `Task.logger.warn`,
in the existing `[nuget:...]` house style.

- Pro: survives **defect 2**. A declared task output is restored from the build cache and is present
  on an `UP-TO-DATE` run; a task *action* is not. The reporting task is never up-to-date and costs
  one small file read, so the warning appears on every `packNuget` regardless of KSP's execution
  outcome.
- Pro: survives **defect 1**, by not using the broken channel at all.
- Pro: one channel for both directions. A consumer sees forward skips and reverse skips in the same
  place, same prefix, same severity.
- Pro: the file is also the natural input for anything later (an opt-in `failOnSkippedDeclarations`,
  a report file, an IDE integration) without re-opening this decision.
- Con: one extra generated file and one extra task. The rendering must stay single-sourced or the
  two sides drift (addressed in the Decision: the file carries the already-rendered message).

### 2. Keep the KSP logger and fix the level/format (eliminated by evidence)

Adjust severity, message shape, or ask consumers for `--info`.

**This is not a weaker option, it is a dead one.** S2 shows KSP's *own* `loaded provider(s)` line is
absent at `--info`. There is no level at which our message appears, because the channel itself does
not arrive. No formatting or severity change can fix a stream nobody reads. It also does nothing
about defect 2.

### 3. Escalate `SKIPPED_*` to `ERROR_*`

Rejected. `test-library` deliberately contains skipped declarations (`MoodLedger.logSpans`,
`EnumComponentCollectionsSample.kt:100`, is an ADR-097 fixture asserted *absent* from the generated
C#), and `scripts/verify.sh` must stay green. A blanket error breaks the repository's own corpus on
the first run. `ERROR_CSHARP_SIGNATURE_COLLISION` stays the only fatal kind. If loudness is wanted
later, the knob is an opt-in `nuget { publish { failOnSkippedDeclarations = true } }` reading the
same file, not a default flip; out of scope here.

### 4. Print to `System.err` instead of `System.out` from the processor

Not evidence-backed (nothing was run that shows stderr survives where stdout does not), it keeps our
diagnostics on a channel KSP owns and could change, and it dies on defect 2 anyway.

### 5. Make `kspKotlin{Target}` never up-to-date

Fixes defect 2 by brute force at the cost of re-running the whole KSP compilation on every build,
and does not fix defect 1 at all, so the extra work would buy nothing.

### 6. Have `packNuget` re-derive the skips itself

Impossible without re-running KSP: the planner's skip decisions need `KSNode`/`KSType` symbols that
exist only inside a KSP round. The plugin can read what the processor recorded; it cannot recompute
it.

## Decision

**Alternative 1.** Delivery only: no change to which declarations are reported, no change to any
severity, no change to any `ForwardDiagnosticKind`, no ABI surface change.

### 1. The processor writes `NugetDiagnostics.json`

`ForwardDiagnosticSink.emit(...)` keeps calling `KSPLogger` exactly as it does today (harmless, and
it is what the Tier 1 harness observes), and additionally accumulates every emitted diagnostic. At
the end of `process()`, alongside `cNameExports.writeTo(...)`, the processor writes the accumulated
list through:

```kotlin
codeGenerator.createNewFile(
  dependencies = Dependencies.ALL_FILES,
  packageName = "",
  fileName = "NugetDiagnostics",
  extensionName = "json",
)
```

**Verified** (KSP 2.3.10 source, `com/google/devtools/ksp/common/impl/CodeGeneratorImpl.kt:139-146`):
`extensionToDirectory` special-cases only `class`, `java` and `kt`; **every other extension,
including `json`, lands in `resourcesDir`.** That is the same directory `Interop.cs` already lands
in, i.e. `build/generated/ksp/<target>/<target>Main/resources/`, which is precisely the path
`NugetPlugin.kt:365-366` already points `generatedCsDirs` at. Files created this way are KSP task
outputs, which is the property the whole decision rests on: **the file is restored on a `FROM-CACHE`
run and present on an `UP-TO-DATE` run, when no task action executes.**

The file is written **unconditionally**, including as an empty array, so "no file" unambiguously
means "KSP never ran for this target" rather than "no skips", and so the reporting side never has to
guess.

### 2. The format

A JSON array, one object per diagnostic, in emission order:

```json
[
  {
    "severity": "WARNING",
    "kind": "SKIPPED_UNSUPPORTED_INPUT",
    "declaration": "io.github.xxfast.kotlin.native.nuget.test.clinic.MoodLedger.logSpans",
    "message": "[nuget:SKIPPED_UNSUPPORTED_INPUT] Skipping io.github.xxfast.kotlin.native.nuget.test.clinic.MoodLedger.logSpans: its COLLECTION type combination is not supported. expose a wrapper taking a List/MutableList (or individual key/value parameters) instead of a Map/Set at this position\n    at /Users/.../EnumComponentCollectionsSample.kt:100"
  }
]
```

`message` is **the exact string `ForwardDiagnostic.format()` already produces**
(`forward/ForwardDiagnostic.kt:110-121`), source location suffix included. The reporting task prints
it verbatim and renders nothing itself. That is deliberate: two renderers for one message is a drift
bug waiting to happen, and the ADR-064 format contract stays in one place. `severity`, `kind` and
`declaration` are carried as separate fields for future consumers (an opt-in fail-on-skip, tooling),
not because the re-emitter needs them beyond picking `warn`.

Only `WARNING` and `INFO` diagnostics reach the file. An `ERROR_*` already fails the KSP round
(`ForwardDiagnosticTrackingLogger`, `NugetProcessor.kt:562` returns before writing outputs), so an
error's message is not this file's job. See Consequences for what that leaves open.

Serialization and parsing follow the existing `bound-types.json` precedent
(`forward/ForwardBoundTypes.kt:30-58`, `NugetGenerateBindingsTask.kt:240`): hand-rolled, fail-fast
with a named message, no new dependency on either side.

### 3. The Gradle side re-emits

`nuget-plugin` registers one task per publishing project, wired ahead of `packNuget`:

```kotlin
@DisableCachingByDefault(because = "reporting only; must speak on every build, including cached ones")
abstract class NugetReportDiagnosticsTask : DefaultTask() {
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val diagnosticsFiles: ConfigurableFileCollection

  init { outputs.upToDateWhen { false } }

  @TaskAction
  fun report() { /* parse each file, logger.warn(message) per entry */ }
}
```

- `diagnosticsFiles` points at the same `kspOutputDir` `generatedCsDirs` already uses
  (`NugetPlugin.kt:365-366`), filtered to `NugetDiagnostics.json`.
- `dependsOn("kspKotlin${firstTarget}")`, and `packNuget.dependsOn(report)`, matching the existing
  `packNuget` wiring at `NugetPlugin.kt:408`.
- A missing file is a silent no-op (a project with `bind {}` and no `publish {}` never runs KSP).
- Messages go through `Task.logger.warn`, the same call the reverse direction makes at
  `NugetGenerateBindingsTask.kt:5855`, so both directions land at Gradle's WARN level with the same
  `[nuget:...]` prefix.

**Inferred** (source reading, not run): that this task's `logger.warn` output appears where the
reverse task's does. It is the identical Gradle API called from a task action on the task thread, and
S1 shows the reverse task's warnings arriving in the same console, so the risk is low; but nobody has
run *this* task. The verification script in the next section is what promotes it.

## Consequences

- A consumer running `packNuget` sees each skipped declaration named, on every build, cached or not.
  ADR-097's breaking change gets the mitigation it was documented as having.
- Files touched: `nuget-processor` (accumulate in the sink, one writer at the end of `process()`),
  `nuget-plugin` (one task class, one registration, one `dependsOn`). No generated C#, no generated
  Kotlin, no native export, no contract hash, no ABI change. Nothing a consumer compiles against
  moves.
- One new generated file ships in the KSP resources directory. It is **not** packaged: `packNuget`
  picks `Interop.cs` out of that directory by name, so a stray `.json` beside it is inert. Worth a
  glance during implementation to confirm nothing globs the directory wholesale.
- Every `SKIPPED_*` stays a `WARNING`. `scripts/verify.sh` stays green with `logSpans` skipped and
  now also *noisy*, which is the point.
- The `KSPLogger` calls stay. They are free, they are what the Tier 1 tests observe, and they will
  start working the day the Gradle/KSP worker-output gap closes upstream.

### How this is tested, and why the obvious test is not enough

- **The existing Tier 1 assertion proves the producer, not the transport.** Keep
  `Tier1EnumCollectionComponentTest`'s `kspWarnings` assertion; relabel what it claims. It observes a
  `RecordingKSPLogger` the harness injected (`Tier1Harness.kt:155`), which is the one component
  production replaces. **This was the trap.** A test that asserts a sink emits can never fail for the
  bug this ADR fixes.
- **The load-bearing test is a real build, run twice.** Add `scripts/verify-forward-diagnostics.sh`
  (or a block in `scripts/verify.sh`) that:
  1. runs `./gradlew :test-library:packNuget --console=plain`, capturing stdout and stderr, and
     asserts the output contains `[nuget:SKIPPED_` and the name of a known-skipped declaration;
  2. **runs the identical command a second time, with no `--rerun-tasks` and no clean, and asserts
     the same two things again.** This is the assertion that pins the contract. S3 shows run 2 is
     exactly where the current behaviour dies (`kspKotlinMacosArm64 UP-TO-DATE`, zero warnings), and
     it is the only assertion that distinguishes "appears once when KSP happens to run" from "a
     consumer sees it".
- **Pick the named declaration at implementation time, and know it is a moving target.** Every
  capability-gap skip is something the roadmap is actively closing:
  [ADR-098](098-narrow-primitive-and-char-collection-components.md) (Proposed) flips
  `MoodLedger.logSpans(List<Short>)`, today's obvious cell, into a *working* member, and
  [ADR-099](099-nested-collection-components.md) (Proposed) does the same for
  `List<List<String>>`, the obvious fallback. Naming either would produce a test that silently stops
  testing anything the moment that ADR lands. So:
  - assert on the generic `[nuget:SKIPPED_` marker, which cannot go stale while any skip exists, and
  - pick the named declaration by reading the `NugetDiagnostics.json` the first implementation run
    actually produces (which this ADR makes trivial to inspect), preferring a **product-scope** skip
    (`SKIPPED_INHERITED_MEMBER`, `SKIPPED_UNEXPORTED_DEPENDENCY_TYPE`, `SKIPPED_BOUND_TYPE_POSITION`)
    over a capability-gap one, since those are deliberate v1 boundaries rather than open roadmap
    items;
  - if the corpus has no such cell, adding one fixture declaration is the minimum test cost of this
    ADR, and it should be a scope skip, not a type-capability skip.

### Deferred, with reasons

- **`ERROR_*` visibility.** S2 implies an `ERROR_CSHARP_SIGNATURE_COLLISION` message is invisible for
  exactly the same reason a warning is, leaving only `KSP failed with exit code: PROCESSING_ERROR`.
  Evidence conflicts: ADR-097's own spike log quotes a real
  `e: [ksp] java.lang.IllegalStateException: ...` line, so errors may take a different path (a
  thrown exception through Gradle's failure reporting rather than the worker's stdout). **Unverified
  either way, and not fixed here.** The file cannot carry it, since an error aborts before outputs
  are written. If an implementing agent hits an unexplained "KSP failed with exit code" and no
  message, this is why; a follow-up would have to fail the build from the Gradle side after reading a
  recorded error, which is a different design.
- **Multi-target reporting.** Only `firstTarget`'s diagnostics are re-emitted, matching how
  `packNuget` already picks one target's `Interop.cs` (`NugetPlugin.kt:365-366`). A second RID's KSP
  run could in principle skip a different declaration (`expect`/`actual` platform APIs). Named, not
  chased; ADR-093's multi-RID work is the place for it.
- **Widening what is reported.** Out of scope by construction. ADR-064's kinds already cover the
  corpus; this ADR moves existing messages onto a channel that works and changes nothing about which
  messages exist.
- **An opt-in `failOnSkippedDeclarations`.** The file makes it a small follow-up. Not shipped here,
  because the immediate contract is "sees a warning", not "fails the build".

### ROADMAP disposition

`ROADMAP.md:349` ("Surface ordinary planner skips as generation-time diagnostics") and this ADR are
the **same item**, not two. Its first half (produce a diagnostic for every non-specialized skip,
exempt the named legacy routes) shipped with ADR-064; `ForwardPlanSkipReason.droppedFromCSharp` is
exactly its "keep named specialized protocols explicitly exempt" clause. Its second half, *"add a
real KSP fixture proving an unsupported declaration fails loudly instead of disappearing"*, was never
done, and is precisely the missing test that let two defects ship unnoticed.

So: **keep `:349` open, rewrite its body, do not tick it, and do not open a second item.** The
rewritten body should say that the producers and their Tier 1 coverage exist (ADR-064), that the
remaining gap is delivery to a console a consumer actually reads plus the twice-run end-to-end
fixture, and that ADR-100 owns it.
