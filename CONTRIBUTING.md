# Contributing

This project packages a Kotlin/Native library as a NuGet package with generated C# bindings, and
consumes C# NuGet packages from Kotlin. Read [GOALS.md](GOALS.md) and [ROADMAP.md](ROADMAP.md) before
proposing a change, and [STYLE.md](STYLE.md) before writing code.

## Branches

`<your-initials>/<short-slug>`, for example `ir/mutable-collection-params`. No issue numbers in the
branch name: the slug says what the work is, and the PR body carries the reference. Work is tracked
in [ROADMAP.md](ROADMAP.md) and in GitHub issues, and plenty of changes answer to neither.

## Verify before you open anything

```sh
scripts/verify.sh            # add --plugin when nuget-plugin/ or nuget-runtime/ changed
```

This is the only evidence that counts. It packs the real fixture library through `konanc`, restores the
package into the C# consumer, and runs the integration tests. A green unit-test suite proves the
generator is correct *given* its input; it proves nothing about whether the reader ever produces that
input. Never quote a result you did not run, and never hand-edit generated output or copy files into
`~/.nuget/packages/` to shortcut a rebuild.

## Real-package dogfooding census

`scripts/verify-dogfood.sh` runs the real plugin (restore, the metadata reader, `parseReverseIr`, both
generators) over nine pinned published NuGet packages and compares the result against the committed
goldens in `nuget-plugin/src/test/resources/dogfood/*.census.json`. It is deliberately **not** part of
`scripts/verify.sh`: it reaches nuget.org, and a feed outage must not turn the local gate or a PR red.
The `dogfood` CI job runs it path-filtered on PRs that touch the reader or the reverse pipeline, and on
a weekly schedule; it is not a required check.

```sh
scripts/verify-dogfood.sh            # check the goldens
scripts/verify-dogfood.sh --update   # rewrite the goldens and SUMMARY.md, then review the diff
```

If a change moves how many members bind or which diagnostics fire on a real package, rerun with
`--update` and put the golden diff in the pull request; that diff is the review signal.

## Commits

One commit per pull request. PRs are squash-merged, so **the PR title becomes the commit subject on
`main`**, and the two follow the same rules:

- Verb first, imperative, no trailing period, under 70 characters
- Say what the change does for a consumer, not which files moved
- Backticks on type, module and task names
- Append the ADR when one governs the change: `(ADR-073)`

```
Add support for closed constructed generics in Kotlin
Map Kotlin interfaces at return positions to IFoo (ADR-040)
Bind Map and Set method parameters in generated C# (ADR-073)
```

Release commits are titled `Prepare for release <version>`.

## Pull requests

Open as a **draft**. Promote it yourself once you have read it back.

Keep the body short. A few sentences and a code example, not an essay. The reviewer can read the diff;
the body exists to say what the diff cannot.

Structure, in order:

0. **`Fixes #NNN` on the first line**, then a blank line, when the branch closes a GitHub issue.
   Omit it entirely otherwise; do not hunt for an issue to attach.
1. **Lead with the situation, not the change.** What was true before, and why it was that way. Skip
   "This PR adds...".
2. **A consumer-facing code example**, for anything that changes the generated surface. This is a code
   generator, so what a consumer actually gets is invisible in the diff and is the most useful thing in
   the body. Kotlin declaration, then the C# it generates, or the reverse for a Phase 8+ feature.
3. **Any sharp edge a reviewer would otherwise have to discover.** A documented limitation, a claim
   that is verified by reading rather than by an executed test, a behaviour that looks like a bug and
   is not.
4. **The verify result**: `` `scripts/verify.sh` green: 769 passed, 1 pre-existing skip, 0 failed ``
5. **A `- [x]` checklist** of the commits on the branch, one line per commit subject.

Leave out of the body: section headings, a test plan, a file-by-file scope list, "Please review",
and any generated-by footer. Deferred work and bugs found but not fixed belong in
[ROADMAP.md](ROADMAP.md), where the next contributor will actually find them, not in a PR body that
gets buried on merge.

A release PR (`Prepare for release <version>`) skips all of the above. Its body is the list of commits
on `main` since the previous tag, one `- <subject> (#NNN)` line each, and nothing else: no prose, no
verify line, no release steps (those live in `.github/workflows/release.yml`). The list is the draft of
the GitHub release notes.

```
- Bind suspend collection returns as `Task<IReadOnlyList<T>>` (ADR-119) (#124)
- Stop exporting a marked lambda or Flow property (ADR-115) (#123)
```

## Prose style

Applies to PR bodies, commit messages, ADRs, docs and code comments.

- Short and direct. Contractions are fine. No hedging.
- Plain punctuation. Use a comma, a colon, parentheses or two sentences. **No em-dashes.**
- Backticks liberally on module, type, method and task names.
- No decorative emoji.

## Architecture decisions

A non-trivial mapping decision gets an ADR in [docs/adr](docs/adr), numbered sequentially, opened as
`Proposed` and flipped to `Accepted` once the feature is implemented and verified.

Label every mechanism claim **Verified** (proven by code in this repo or by an executed spike, cited by
`file:line`) or **Inferred** (from documentation). This is not pedantry. ADR-053 asserted an attribute
encoding that only holds when the attribute is compiler-synthesized; an implementing agent followed it
literally and had to debug its way back out. If you are implementing against an ADR and a mechanism
claim does not match reality, the ADR is wrong. Fix the ADR, do not bend the code to match it.

## Documentation

A feature is not done until these are updated:

- [docs/topics](docs/topics): the mapping table, the snippets, and the **Limitations** section, which is
  the one most often left carrying a line that is now false
- [ROADMAP.md](ROADMAP.md): tick the item, link its ADR, and file anything you found but did not fix
- [FEATURES.md](FEATURES.md): the mapping row, with its direction glyph (`→` Kotlin to C#, `←` C# to
  Kotlin, `⇄` both) and ADR link

Every snippet must be lifted from output that actually compiles: the generated `Interop.cs`, the
reverse output under `build/nuget-interop/`, `test-library/`, or `IntegrationTests/`. Do not write one
from memory.

## Agents

Contributors working with coding agents should read [CLAUDE.md](CLAUDE.md), which records the failure
modes this project has already paid for, and [AGENTS.md](AGENTS.md).
