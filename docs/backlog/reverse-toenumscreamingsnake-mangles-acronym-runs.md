# Reverse enum binding mangles an acronym run: C# `AB1C` binds as Kotlin `A_B1_C`

**Symptom (inferred, not run).** Binding a C# enum whose member name contains a run of consecutive
uppercase letters produces an over-split Kotlin name: `AB1C` is predicted to bind as `A_B1_C`, and
`HTTPStatus` as `H_T_T_P_STATUS`, rather than keeping the acronym run together.

**Cause.** `toEnumScreamingSnake` (`nuget-plugin/src/main/kotlin/io/github/xxfast/kotlin/native/nuget/NugetGenerateBindingsTask.kt:1897-1902`)
inserts an underscore before *every* uppercase character, with no acronym-run exception, unlike the
forward-direction `kotlinConstantToPascalCase()` (`nuget-processor/.../processor/Reserved.kt`) that
issue #285's ADR-006 amendment gave the per-segment rule to.

**Coverage gap.** This is the reverse-plugin module (`nuget-plugin`), a separate code path from the
forward casing fix; no fixture in `TestDependency`/`IntegrationTests` binds a C# enum member with an
internal acronym run, so the mangled name has not been observed by a generated-Kotlin snapshot.

**Needs its own decision**, not a mechanical mirror of the forward rule: an acronym-preserving split
needs a rule for where an acronym run ends and the next word starts (`HTTPStatus` -> `HttpStatus` or
`HTTP_STATUS`?), which the forward direction never had to decide because it only ever *joins*
segments, never splits them.

**Discovered by:** the issue #285 research memo (`docs/research/roadmap/enum-entry-casing.md`,
finding 10), by reading only; not run. Filed as its own item per the memo's open question 5
(a separate module, needs its own acronym-run decision, would have blocked the forward-only fix).
