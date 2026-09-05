# A forward projection matches an ABI parameter by its literal name, not by position

**A user-declared Kotlin parameter that happens to be named `handle`, `value`, `receiver`,
`errorOut`, or `valueOut` is silently misrouted rather than escaped or renamed**, because the forward
projection layer identifies the generator's own synthetic ABI parameters by string-matching their
literal name rather than by position or a marker type: `ForwardCirPlanProjection.kt:95`,
`ForwardCirPropertyProjection.kt:139`, and `ForwardKotlinPlanEmitter.kt:359` / `:487` all look up
`"handle"` / `"value"` / `"receiver"` / `"errorOut"` / `"valueOut"` by name. A user constructor
parameter literally named `handle` collides outright with the generated `IntPtr handle` local in the
constructor wrapper; a user parameter named one of the other four is at risk of being read as the
generator's own slot and dropped from the `[DllImport]` rather than treated as ordinary user data.
Unlike issue [#65](https://github.com/xxfast/kotlin-native-nuget/issues/65)'s keyword-escaping gap,
`@`-escaping would not fix this family at all: `handle`/`value`/`receiver`/`errorOut`/`valueOut` are
not C# keywords, so nothing before this class of collision would ever flag them. Source-reading only,
not verified by a fixture; the #65 fixture deliberately excluded these five names to avoid conflating
the two defect classes. `error` was the member of this family tracked here and has since been fixed
as issue [#66](https://github.com/xxfast/kotlin-native-nuget/issues/66) (the C#-render-time
`error` -> `error_` rename, [ADR-024](https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/024-sync-exception-propagation.md)'s
2026-09-05 amendment); it is closed and not part of the remaining scope below. Found alongside issue
[#65](https://github.com/xxfast/kotlin-native-nuget/issues/65).

## Findings from #66's investigation (2026-09-05)

Fixing `error` required reading the constructor and method render paths closely enough to surface
four related findings in the remaining `handle`/`value`/`receiver`/`valueOut` members of this
family. None of these were fixed; they are split out here per this project's workflow (a fix
narrowly scoped to `error` should not silently absorb the rest of the family). Found alongside
issue [#66](https://github.com/xxfast/kotlin-native-nuget/issues/66).

- **(a) Verified by reading; pins the original entry's claim to a line and error code.** The
  original paragraph above already named a constructor parameter literally called `handle` as a
  collision; reading `ForwardCirPlanProjection.kt:169` while fixing #66 confirms the mechanism is
  identical to #66's own: the constructor body is emitted as
  `IntPtr handle = Native_Create$nativeSuffix($callArgs);` with the constructor's public parameters
  already in scope, so a `handle`-named parameter produces
  `IntPtr handle = Native_Create(handle, ...)`, the same CS0136 "local conflicts with the meaning of
  an in-scope identifier" class `error` produced.
- **(b) Verified by reading; a name not on the original list.** The method-body local `nativeResult`
  collides the same way and was not one of the five names this file originally tracked. Every
  non-`Unit` method wrapper declares a `nativeResult` local (`IntPtr nativeResult = $nativeName($arguments);`
  at `ForwardCirPlanProjection.kt:1139`, and the same local under a typed declaration at `:1123`,
  `:1206`, `:1338`), again with the method's own parameters in scope. A Kotlin method parameter
  named `nativeResult` hits the same CS0136 class as `handle` and `error`, just on a method body
  rather than a constructor body.
- **(c) Grep only, not read in full; unverified.** A Kotlin method parameter named `handle` (the
  instance receiver's own rendered name) or an extension parameter named `receiver` duplicates the
  extern's own leading generator-supplied parameter, and, per this backlog file's original entry,
  is also at risk of being misrouted by the `ForwardCirPropertyProjection.kt:139` /
  `ForwardKotlinPlanEmitter.kt:359`/`:487` name lookups on the *Kotlin* `@CName` side, not just the
  C# side. No ABI-name uniqueness or collision check was found in `ForwardCallablePlanner.kt` or
  `ForwardMarshallingModel.kt` by grepping for `unique`/`collision`/`distinct`; neither file was
  read in full, so an existing guard elsewhere cannot be ruled out.
- **(d) Verified by reading; not a bug, noted for completeness.** `ForwardCallablePlanner.kt:457-458`
  states directly: "Members keep the shipped no-errorOut ABI; only constructors carry an error
  slot." So a value-class *member* (property or method, not the constructor) has no `errorOut`/`error`
  slot on its generated signature at all, and an `error`-named parameter there simply shifts to
  `error_` under the same #66 rule with nothing beneath it to collide with. (The value-class
  *constructor* is unaffected by this note and does carry the slot, per the same comment.) This is
  deliberate, not a gap: it is called out so nobody re-opens it as a fifth #66-shaped cell.
