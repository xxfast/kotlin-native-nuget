# CI test structure proposal: names, layers and cost

2026-10-04. Baseline: PR #442 (`kotlin-version-range`), which turns the `smoke-test` job into a
`[floor, tested]` Kotlin matrix. Revised after the review at the end of this file.

## Summary

Rename the CI jobs and steps so each one says what it proves, and run the cheap bridge checks
before the leak harness. Keep the jobs parallel and keep today's pull request coverage.

- **Done in the first pass:** job ids, display names, step prefixes and the leak harness moved to
  last in the bridge job. Matching headings in `scripts/verify.sh`. No coverage change.
- **Not done, separate decisions:** any reduction of the Kotlin range legs, the Windows processor
  leg, or a direction split of `IntegrationTests`.
- **No staging:** gating jobs behind each other only saves compute on red runs, and the last 40
  CI runs had none.

## The tests today

Three layers: Kotlin unit tests prove the generators, C# suites prove the packed bridge end to
end, and scripts prove things only a real build can show.

| Layer | Suite | What it proves |
|---|---|---|
| Kotlin unit | `:nuget-processor:test` (285 files) | Forward generator: KSP over Kotlin snippets, asserts the generated C# and `@CName` exports, skips and diagnostics |
| Kotlin unit | `nuget-plugin` tests (67 files) | Reverse generator plus plugin wiring: tasks, DSL, pack, publish, restore, Kotlin and shim generation from C# metadata |
| Kotlin unit | `:nuget-runtime` native tests (6 files) | Runtime helpers that ship in the klib |
| C# end to end | `IntegrationTests` (247 files) | Behaviour of the packed `test-library` from C#, forward and reverse |
| C# end to end | `LeakTests` | Handle counts and GC collectability, in its own process |
| C# end to end | `ContractTests` | The shared `Kotlin.Native.Interop` contract package |
| C# end to end | `MultiPackageTests` | Two independent publishers coexist in one process |
| C# end to end | `SharedExceptionTests` | Exception identity is shared across consumer assemblies |
| C# end to end | `GeneratedBindingsCheck` | Generated bindings compile as a consumer, warnings as errors |
| C# end to end | `AotSmokeTest` | The bridge works under NativeAOT |
| Build level | `verify-forward-diagnostics.sh` | Diagnostics reach the console, including on an incremental build |
| Build level | `verify-runtime-exports.sh` | Every `nuget_*` runtime export is in the linked library |
| Build level | `verify-contract-version-ranges.sh` | Publishers with different contract floors restore together |
| Build level | `smoke-test` | The plugin is consumable by maven coordinate from a separate build |
| Build level | `dogfoodCensus` | Reverse pipeline over nine real NuGet packages, against committed goldens |

Before this change CI ran these as four jobs, all starting at once with no `needs:` between them.

| Job | Legs | Contents |
|---|---|---|
| `unit` | 4: processor and plugin, on macOS and Windows | Kotlin unit tests with coverage |
| `test` | 2: macOS, Windows | Runtime tests, then every C# suite and build-level script, serially |
| `smoke-test` | 1 before #442, 2 with it | By-coordinate consume. With #442, also a repack and `IntegrationTests` and `LeakTests` at Kotlin floor and tested |
| `dogfood` | 1, Ubuntu | Reverse census, path-filtered on pull requests |

## Problem

At a glance, nobody can tell why we have different tests or what each job proves.

- **`unit`, `test` and `smoke-test` say nothing.** `test` holds eleven unrelated checks. `unit`
  hides that one suite is the forward generator and the other is the reverse generator plus plugin
  wiring.
- **`test` vs `smoke-test` is unclear.** `test` resolves the tooling as Gradle projects and proves
  behaviour. `smoke-test` resolves it by maven coordinate and proves the publication. The names
  carry none of that.
- **`smoke-test` does two things after #442.** It checks by-coordinate consumption, then rebuilds
  the repo on another Kotlin and runs the C# suites.
- **The second half tests the wrong shape.** The `kotlinVersion` override rebuilds the processor
  and runtime klib on Kotlin X too. A real consumer gets artifacts built on the pin, linked by X.
  This gap is already written up in
  [kotlin-range-ci-coverage-gaps.md](../backlog/kotlin-range-ci-coverage-gaps.md).

## Names (implemented)

Each job is named after the question it answers. Direction (forward, reverse) is used only where a
suite is really one direction.

| Before | Job id | Display name | Question it answers |
|---|---|---|---|
| `unit` / processor | `generators` | `Forward generator (${os})` | Does the processor emit the right C# and exports? |
| `unit` / plugin | `generators` | `Reverse generator and plugin (${os})` | Does the plugin emit the right Kotlin and shims, and wire its tasks? |
| `test` | `bridge` | `Bridge end to end (${os})` | Does the packed bridge behave from C#? |
| `smoke-test` | `consumer` | `Consumer on Kotlin ${kotlin}` | Do the published artifacts work for a real consumer on that Kotlin? |
| `dogfood` | `reverse-census` | `Reverse census over real packages` | How much of real NuGet packages do we bind? |

The two generator suites stay one matrix job (`generators`) with the direction in the display
name. Two job ids would duplicate every step for no gain. Job count is unchanged.

### Stages inside `bridge`

Same steps, prefixed so the log reads as stages. The only reorder is the leak harness, moved to
last.

1. `Runtime:` native helper tests (34s)
2. `Contract:` `ContractTests`, pack, version ranges
3. `Pack:` both publishers (307s), forward diagnostics, runtime exports
4. `Compile:` `GeneratedBindingsCheck`
5. `Behaviour:` `IntegrationTests`, unfiltered, with coverage (42s)
6. `Coexistence:` `MultiPackageTests`, `SharedExceptionTests`
7. `AOT:` publish and run (about 80s)
8. `Leaks:` `LeakTests` (355s)

Before, the leak harness ran ahead of coexistence and AOT, so a failure in either waited about 6
minutes. Coverage collection is unchanged for all three suites that had it.

`scripts/verify.sh` takes the same prefixes on its headings but keeps its order, with AOT last.
On a Homebrew macOS host the AOT link fails without `LIBRARY_PATH` (see `AGENTS.md`), and with AOT
last that host problem cannot stop the leak harness from running.

### Stages inside `consumer`

- `Published artifacts:` publish on the pin, resolve by coordinate, link at Kotlin X.
- `Repo on Kotlin X:` repack `test-library`, prove the compiler version, run `IntegrationTests`
  and `LeakTests`.

The second prefix is deliberately honest. That half does not test a consumer yet. Once
`test-library` is built as an outside consumer of the pinned artifacts, it becomes
`Published artifacts:` too.

`Published artifacts:` is the only place CI resolves the plugin, processor and runtime by maven
coordinate. Any change to when this job runs has to keep that stage on pull requests.

### Where direction does not apply

`ContractTests`, the runtime tests, `LeakTests`, `MultiPackageTests`, `SharedExceptionTests`, AOT
and the consumer job test the shared bridge or the packaging. They keep purpose names.

### Splitting `IntegrationTests` by direction (deferred)

The first pass keeps one unfiltered `Behaviour:` run. A forward and reverse split needs a semantic
classification first, because names and dependency references do not classify the suite:

- `ReverseLambdaTests` exercises callbacks through the forward-generated API (`TestLibrary.Cat`).
- `MimeRoundTripTests` exercises reverse NuGet consumption and never references `TestDependency`.

Before a split replaces the unfiltered run:

1. Define direction by the binding pipeline under test, and decide where mixed classes go.
2. Prove the filtered sets cover every discovered test, with any overlap intentional.
3. Keep coverage collection on both runs.

Folders with namespaces, or xunit traits, are both workable once that exists.

## Measured cost

A CI run with #442 costs 120 to 138 runner-minutes and takes 36 to 38 minutes from creation to
the last job finishing.

| Run | Elapsed minutes | Runner-minutes | Longest job | Last macOS job started after |
|---|---|---|---|---|
| [37186675031](https://github.com/xxfast/kotlin-native-nuget/actions/runs/37186675031) | 36.4 | 137.9 | 25.3 minutes | 11.6 minutes |
| [37178571036](https://github.com/xxfast/kotlin-native-nuget/actions/runs/37178571036) | 37.9 | 120.5 | 25.3 minutes | 14.9 minutes |

Both runs are on `kotlin-version-range` before the rebase, read from the GitHub Actions API on
2026-10-04. Elapsed is `max(jobs.completedAt) - createdAt`. Runner-minutes is the sum of each
job's `completedAt - startedAt`.

The gap between the longest job and the elapsed time is queueing: the macOS jobs do not all start
at once. The cause is overlapping runs. The free plan allows 5 concurrent macOS jobs across the
account, one run with #442 uses all 5, and a second run created seconds later has to wait.

Start delay of the slowest macOS job, from the GitHub Actions API on 2026-10-04:

| Run | Created (UTC) | Another run created within | Slowest macOS start |
|---|---|---|---|
| 37186675031 | 07:43:50 | 25 seconds (37186696282) | 11.6 minutes |
| 37190764052 | 09:00:42 | 2 seconds (37190761712) | 3.8 minutes |
| 37189686701 | 08:40:32 | none, but the two 08:31 runs were still going | 6.7 minutes |
| 37189219653 | 08:31:49 | 3 seconds (37189216664) | 5.1 minutes |
| 37201749825 | 12:20:14 | none | 8 seconds |
| 37199729997 | 11:44:04 | none | 10 seconds |
| 37191918176 | 09:22:00 | none | 8 seconds |

A run on its own starts every job within 10 seconds, and Windows and Ubuntu jobs never waited more
than 18 seconds in any of these. So queueing is a cost of pushing several branches at once (a
stack, or parallel lanes), not of a single pull request. Verified by reading the job timestamps;
that the cap is what holds the jobs is inferred from the pattern.

| Part of the run | Legs | Runner-minutes | Share |
|---|---|---|---|
| Kotlin range legs (`consumer`) | 2, macOS | 42 to 50 | about 36% |
| Bridge | macOS, Windows | 33 to 41 | about 29% |
| Forward generator (processor tests) | macOS, Windows | 31 | about 24% |
| Reverse generator and plugin tests | macOS, Windows | 13 | about 10% |
| Reverse census | Ubuntu | 2 | about 1% |

Inside a range leg (four legs measured across the two runs):

| Stage | Steps | Per leg |
|---|---|---|
| `Published artifacts:` | Publish 173 to 357s, resolve and link 69 to 138s | 4 to 8 minutes |
| `Repo on Kotlin X:` | Pack 390 to 710s, `IntegrationTests` 39 to 61s, `LeakTests` 316 to 321s | 12 to 18 minutes |

Inside the macOS bridge job, two steps dominate: the pack (307s) and `LeakTests` (355s).
`IntegrationTests` takes 42s. Source: run 37199729997 on `main`.

### Failure history

- The last 40 CI runs: 39 green, 1 still running, 0 failed.
- The 12 most recent failed jobs are older than that window. 11 failed in the plugin unit tests,
  back when that step lived in the `test` job. 1 failed in `IntegrationTests`.
- Why they failed was not investigated.

Inferred, not verified: CI is green because `scripts/verify.sh` runs locally before a push.

## Fail-fast staging: considered, not recommended

Staging jobs behind each other saves compute only on red runs, and there have been none in the
last 40.

The option considered:

1. **Stage 0:** a `compile` gate on one Ubuntu runner. Everything else `needs:` it.
2. **Stage 1:** generators and bridge in parallel, as today.
3. **Stage 2:** the Kotlin range legs `needs:` the macOS bridge.

| Case | Compute effect |
|---|---|
| Green run | Adds the gate job. Its duration is unmeasured |
| Compile error | Saves nearly all of the run |
| Red macOS bridge | Saves the range legs, 42 to 50 runner-minutes, about 35% |

The effect on elapsed time is not established. Stage 2 would start the range legs after the macOS
bridge (16 to 24 minutes of execution) instead of beside it, but the macOS jobs already queue, so
scheduling has to be modelled separately from execution before a number is put on it. An earlier
draft claimed an 11 to 27 minute penalty against a 25 minute baseline. That baseline was the
longest job, not the elapsed time, and the claim is withdrawn.

Gating the bridge behind the unit tests was also considered. The processor tests are the slowest
job at 11 to 23 minutes of execution, so that puts the end-to-end signal behind the slowest suite.

**Kept from this option:** the leak harness reorder inside the bridge job.

## Compute savings (not in the first pass)

Each of these reduces what a pull request runs, so each is its own decision. Sizes are read from
the tables above; none is measured as a change.

| Lever | Saves per run | What it costs |
|---|---|---|
| Run `Repo on Kotlin X:` only on `main` or behind a path filter, keeping `Published artifacts:` on every pull request | About 31 to 34 runner-minutes, 25% | A range behaviour break is found later. A path filter needs a dependency audit first: processor, annotations, runtime, plugin, build configuration, fixtures, and the scripts and workflow that run the job |
| Drop `LeakTests` from the range legs | About 10.6 runner-minutes, 8 to 9% | Assumes leaks do not vary by Kotlin patch version. Not shown either way |
| Run the Windows processor leg only on `main` | About 18 runner-minutes, 13 to 15% | A Windows-only generator regression is found after merge |

Not levers:

- **Processor test parallelism.** `nuget-processor/build.gradle.kts` already sets
  `maxParallelForks`, and its comment records that extra forks did not help on the 3 and 4 core
  CI runners. Any speed-up work starts from those measurements.
- **Moving the whole `consumer` job to `main`.** It would also drop the only by-coordinate check
  from pull requests.

### Fewer macOS jobs per run

Since queueing comes from overlapping runs meeting the 5 job macOS cap, every macOS job a run does
not need shortens the wait when branches are pushed together.

- **Forward generator on Ubuntu (done in the second pass).** The processor's tests are JVM only:
  in-process KSP, generated text asserted as strings, no Kotlin/Native link. Their unix leg moves
  from `macos-latest` to `ubuntu-latest`, so a run uses 4 macOS jobs, not 5. The Windows leg and the
  coverage upload stay. Trade-off: the processor suite no longer runs on a macOS host in CI. It
  still runs there locally, and the macOS `bridge` leg runs the real processor end to end.
- **Reverse generator and plugin stays on macOS.** Those tests are host-specific: they compile
  generated Kotlin with the host `kotlinc-native` and resolve host RIDs.

## The outside-consumer fixture

The fix for the wrong-shape half is the backlog gap: build `test-library` as an outside consumer
of the pinned artifacts, so the behaviour suites run against what a consumer gets on Kotlin X.
This is the highest-value follow-up. Its implementation cost and runtime are unknown: how much of
`test-library`'s build assumes it sits in the root build was not read.

## Open decisions

1. `consumer` as one job with two stages (implemented), or two jobs. Seven macOS jobs would
   queue, not fail, since the cap is on concurrency. The cost of a split is scheduling plus
   duplicated setup, publication and artifact transfer, none of it measured.
2. Whether to take any of the compute savings above.
3. Whether to classify `IntegrationTests` by direction.
4. Rename `smoke-test/` to `consumer/`. Not proposed: it touches `verify.sh`, `release.yml`, docs
   and ADRs.

## Unverified

- The workflow passes `actionlint`. It has not run on GitHub yet; the pull request run is the
  first real execution of the renamed jobs and the reordered bridge.
- The reverse share of `IntegrationTests` is unknown. The 247 files were not classified.
- The Ubuntu `compile` gate and the Ubuntu generator legs assume the processor and plugin build
  and pass on Linux. Not run.
- Timings are from 2 to 3 runs each. Cancelled runs are not counted, and `cancel-in-progress` is
  on.
- Purposes of `ContractTests`, `LeakTests` and the runtime tests come from CI step names and
  project notes, not from reading the test bodies.

Verified on 2026-10-04: `main` has no required status checks (branch protection lists none, and
the repository has no rulesets), so renaming jobs breaks no merge gate.

## What the first pass changed

- `.github/workflows/ci.yml`: job ids and display names, step prefixes, the leak harness moved
  after AOT in `bridge`, comments that named the old jobs.
- `scripts/verify.sh`: heading prefixes only. Order unchanged.
- `scripts/verify-dogfood.sh`, `AGENTS.md`, ADR-195 and two backlog notes: references to the old
  job or heading names.

Test project and directory names are unchanged.

## Codex review (2026-10-04)

The first pass is sound: clearer names, moving leak tests after coexistence and AOT, and keeping
the jobs parallel. Preserve current PR coverage while making those changes. The cost analysis
and coverage-reduction options need the following revisions before implementation.

### 1. Correct the wall-time baseline

**Verified from the GitHub Actions API:** the two cited runs took 36.4 and 37.9 minutes from
workflow creation to the last job's completion, including delayed job starts:

| Run | Created (UTC) | Last job completed (UTC) | Elapsed minutes | Runner-minutes |
|---|---|---|---|---|
| [37186675031](https://github.com/xxfast/kotlin-native-nuget/actions/runs/37186675031) | 07:43:50 | 08:20:14 | 36.4 | 137.9 |
| [37178571036](https://github.com/xxfast/kotlin-native-nuget/actions/runs/37178571036) | 04:59:22 | 05:37:15 | 37.9 | 120.5 |

Both runs are from 2026-10-04. Runner-minutes are the sum of each job's `completedAt - startedAt`;
elapsed time is `max(jobs.completedAt) - createdAt`. The runner-minute totals agree with the
proposal, but the approximately 25-minute wall-time baseline does not. The claimed 11–27 minute
staging penalty therefore is not established by these measurements. Keeping jobs parallel is
still reasonable; model scheduling separately from execution before assigning a numerical penalty.

- [x] Correct the baseline and revise the staging comparison, labelling estimates separately
  from observed elapsed times.

### 2. Preserve publication coverage when considering range-job savings

**Verified by reading `.github/workflows/ci.yml` and `smoke-test/build.gradle.kts`:** the range
jobs contain the only CI exercise of resolving the published plugin, processor and runtime by
Maven coordinate. Moving the whole job to `main` also defers publication regressions until after
merge. The trade-off is broader than Kotlin range compatibility alone.

A filter limited to plugin, runtime and version pins is insufficient: processor and annotations
changes, build configuration, and consumer fixtures can affect this path too. An exact filter
needs a dependency audit, including the scripts and workflow that run it.

- [x] Keep a by-coordinate consumer check on relevant PRs if expensive range behaviour stages
  are made conditional; recalculate the savings for that narrower change.
- [ ] Specify and review the actual path filter before proposing it as equivalent coverage.

### 3. Classify integration tests before adding directional filters

**Verified by reading test bodies:** `ReverseLambdaTests` exercises callbacks through the
forward-generated API, while `MimeRoundTripTests` exercises reverse NuGet consumption without
referencing `TestDependency`. Filename prefixes and dependency-name searches cannot reliably
classify the suite. The proposed folder move needs a semantic classification first.

- [ ] Define direction by the binding pipeline under test and decide how mixed classes fit.
- [ ] Before replacing the unfiltered run, prove that the selected test sets cover every
  discovered test, with any overlap intentional. Preserve coverage collection for both runs.
- [x] Use one `Bridge behaviour:` step in the first pass; defer the split until classification
  and filter verification are ready.

### 4. Tighten the processor savings estimate

**Verified by reading `nuget-processor/build.gradle.kts`:** `maxParallelForks` is already set to
`(availableProcessors / 4).coerceIn(1, 4)`. The adjacent comments record prior measurements in
which extra forks did not improve the 3- and 4-core CI runners. Those measurements were not rerun
for this review.

Dropping an 18-minute Windows leg saves approximately 13–15% of a 120–138 runner-minute run.
The quoted 24% is the entire processor suite's share across both operating systems.

- [x] Separate the Windows-leg saving from speculative test optimization. Use the existing
  parallelism measurements as the starting point for any performance investigation.

### 5. Treat the macOS cap as a scheduling constraint

**Verified against [GitHub's documented limits](https://docs.github.com/en/actions/reference/limits#job-concurrency-limits-for-github-hosted-runners):**
five is the maximum number of concurrent macOS jobs on the listed Free plan. It is not a
five-job limit on the workflow definition. Seven macOS jobs do not need to execute simultaneously.
The effect of splitting on elapsed time remains unmeasured; duplicated setup, publication and
artifact transfer also need accounting.

- [x] Reword the split-job decision around scheduling and setup costs. Keeping the combined
  jobs is defensible, but the concurrency cap alone does not settle the decision.

### Recommended sequence

1. Ship names, step prefixes and the leak-test reorder, retaining the unfiltered integration
   suite and current PR coverage.
2. Correct the measurements and evaluate any coverage reduction as a separate decision.
3. Prioritize the outside-consumer fixture to close the correctly identified gap in behaviour
   testing of pinned artifacts linked by Kotlin X. Its implementation cost and runtime remain
   unknown; remove the unsupported claim that it will run at the same cost.

Review scope: source inspection and retrieval of the cited CI timings; no builds or tests were
run, and no workflow changes were made as part of this review.
