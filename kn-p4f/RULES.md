# Rules for every agent in this run (read before you start)

1. **NO DEFERRALS.** The user's direct instruction. No gap you find may become a ROADMAP line, a `docs/backlog/` file, a "Deferred"/"Declined"/"Out of scope" ADR entry, a named skip left in place, or a docs Limitations line. If you find a gap, it gets designed and built in this run. If you believe something is truly impossible, say so in your report with evidence; the main thread escalates to the user. You do not decide to defer.
2. Never run `./gradlew --stop`. Never kill a Gradle daemon you did not start. Other sessions share the machine.
3. Work only in the worktree path you were given. Never touch `/Users/xxfast/Developer/XXFAST/KMP/kotlin-native-nuget` (main checkout) or any `kn-p7-*` worktree.
4. Export `NUGET_PACKAGES=$HOME/.nuget/kn-p4f-<w>` (w = a, b or c, from your worktree name) for every Gradle/dotnet command.
5. Verify with `bash /Users/xxfast/Developer/XXFAST/KMP/kn-p4f/verify-lane.sh <worktree>`, which runs `scripts/verify.sh` plus the CI lint (`supported-features.md`), an em-dash check and a no-new-ROADMAP-line check. Run it with a 1 h timeout. Do not leave a build running when you report.
6. No em-dashes (U+2014) anywhere: code, comments, docs, ADRs.
7. Do not commit, rebase, push or create branches. The main thread commits.
8. CLAUDE.md binds you: no hand-copying into `~/.nuget/packages`, no hand-patched generated files, instrument before hypothesizing.
9. No process words in repo files (no "lane", "DECISIONS", "fable", "the main thread", "this run", "after the rebase"). Write ADRs and comments as if for a reader who never saw this session.
