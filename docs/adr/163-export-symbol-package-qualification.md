# ADR-163: Every forward export symbol is qualified by library and package, always

## Status

Accepted, 2026-09-22.

## Context

The native ABI is one flat namespace of `@CName`-exported C functions. Before this ADR, every route
composed a symbol from the declaration's own enclosing chain of simple names and never its package
(ADR-133): `class Kitten` in package `a` and `class Kitten` in package `b` both minted
`kitten_create`. The build failed loudly, `ERROR_C_ENTRY_POINT_COLLISION` (ADR-117), before
`CNameExports.kt` was ever written, for a constructor, a top-level function, a sealed class, a Flow
member and an enum-armed extension property alike (research memo
`docs/research/roadmap/export-symbol-package-qualification.md`, verified by spike:
`tier1.ns.a.Kitten(String)` + `tier1.ns.b.Kitten(String)` produced three collisions, one per symbol).
A library author who wanted `Cattery.House.Kitten` beside `Cattery.Garden.Kitten` — an entirely
ordinary Kotlin package layout — could not ship both.

Separately, a top-level function whose name happened to match an import-library export on mingwX64
resolved at runtime, silently, every time: `fun signal(dbm: Int)` compiled clean, KSP exited `OK`,
and the generated `[DllImport(EntryPoint = "signal")]` threw `EntryPointNotFoundException` at first
call, with no build-time diagnostic anywhere. Verified by spike (`konanc`, `-target mingw_x64`,
`objdump -p` on the resulting DLL): the DLL's export table simply omits `signal`, `abort`, `exit`,
`raise`, `read`, `qsort`, `strtok`, `open`, and every other export of every import library on the
mingw link line, whether or not the Kotlin runtime references it — `ld.lld`'s MinGW auto-exporter
drops any name it can also resolve lazily from an already-linked import library. The match is exact
and case-sensitive; a hand-prefixed `lib_signal` in the same probe binary exported fine. This is not
CRT-only (Win32 names like `Beep`/`MessageBoxA` are dropped the same way) and not a fixed, documentable
list: the dropped set is the union of every import library's exports, thousands of names, and it
changes with the toolchain's own bundled dependencies.

Both defects have the same fix: an export symbol that is never a bare, unqualified name.

## Decision

**Every forward `@CName` is now `<sanitised libraryName>_<declaring package relative to
rootPackage>__<the name the declaration already had>`, always, on every route.**

- `<sanitised libraryName>`: the `nuget.libraryName` a `DllImport` already names, lowercased, every
  run of characters outside `[a-z0-9_]` collapsed to one `_`, a leading digit given a `_` prefix, an
  empty or all-punctuation name falling back to `_` (`sanitizeLibrarySegment`, `Reserved.kt`). It is
  never empty, which is what makes `fun signal()` bind: `<lib>_signal` is not a name any import
  library on the mingw link line exports, so `ld.lld`'s auto-exporter has nothing to match against.
  If the sanitised value is `nuget`, the round fails before anything is planned with a new
  `ERROR_RESERVED_LIBRARY_NAME`: that leading segment is ADR-127's own reserved space for the
  `nuget-runtime` ABI.
- `<package>`: the declaring package relative to `rootPackage` when it is under `rootPackage` (the
  same segment-bounded rule `mapPackageToNamespace` already uses), the whole package otherwise;
  lowercased, `.` replaced by `_`. Omitted together with its own `_` when the relative package is
  empty (a `rootPackage`-level declaration), so a root-package `class Kitten` in library `Test`
  still reads `test_kitten_create`, not `test___kitten_create`.
- `__` (double underscore) separates the package part from the rest, so `a.b` + `C` (`a_b__c`) and
  `a` + `B.C` (`a__b_c`) read differently. This is best-effort, not a JNI-grade escape — a Kotlin
  identifier may itself contain `_` — and ADR-117's collision diagnostic stays as the loud backstop
  for what this scheme cannot tell apart (see Residual collisions below).
- For a class-like owner (class, sealed base or arm, object, enum, interface bridge) the package is
  the **owner's** package. For a top-level or extension callable it is the **callable's own**
  package, matching ADR-095's `(package, name)` overload-counter scope, not the receiver's: two
  extensions of one name on one receiver, declared in two packages, must not converge on one counter
  or one symbol.

### One table, asked everywhere

Minted in exactly one place, `ForwardSymbolTable` (`nuget-processor/.../ForwardSymbolTable.kt`),
built once per round from `NugetContext.libraryName`/`rootPackage` and exposed as
`NugetContext.symbols`, a `by lazy` so its per-package qualifier memoization survives the round. The
planners (`ForwardCallablePlanner`, `ForwardPropertyPlanner`, `ForwardInterfaceBridgePlanner`), the
CIR translators (`CirClassTranslator`, `CirFunctionTranslator`, `CirTranslator`), and the legacy
`exports/` builders (`ClassExports`, `EnumExports`, `FunctionExports`, `GenericFunctionExports`,
`SuspendFunctionExports`, `InterfaceExports`, `SealedClassExports`) all take the same instance rather
than recomputing a prefix locally.

This was necessary, not merely tidy: three families bypassed the pre-existing `nativePrefix()`
chokepoint entirely (the memo's finding 1) — four raw sealed-class prefix sites in
`NugetProcessor.kt` that read the base's lowercased simple name directly, every bare
`toCName(name)` top-level/generic-function site, and the non-class extension-receiver fallback — and
each would have silently disagreed with its own C# twin if it kept computing its own prefix locally.
A shared table with a per-declaration-kind method (`owner`, `topLevel`, `extension`,
`csharpQualifier`) means a route can only ask the table, never invent a qualifier of its own; the
zero-argument `KSClassDeclaration.nativePrefix()` that used to let a route opt out is deleted, so
there is no unqualified path left to bypass into.

### Three things that ride along, because the scheme does not work without them

1. **A top-level call in the generated Kotlin is now spelled fully qualified, not imported by
   simple name.** Verified by spike: with only the C name qualified, two `rollCall()` exports from
   two packages still import by simple name and call bare `rollCall()`, and KSP exits `OK` but the
   generated `CNameExports.kt` fails to compile — `Overload resolution ambiguity between candidates:
   fun rollCall(): String / fun rollCall(): String`. `ForwardKotlinPlanEmitter`'s top-level
   invocation now reads `${kotlinPackageReference(packageName)}$functionName($arguments)`, each
   package segment backtick-escaped if it is a Kotlin hard keyword. The **default package** (no
   `package` line at all) has no qualifier to spell, so it keeps its existing simple-name import
   (`importIfDefaultPackage`) instead — a package-relative expression built by
   `substringBeforeLast('.')` on a qualified name with no `.` in it returns the whole name, which
   would have generated the self-referential `rollCall.rollCall()`.
2. **`{Iface}BridgeState` (ADR-088) takes the same qualification, in C# identifier form.** Every
   interface bridge's state class renders into the single root namespace, so two same-simple-name
   interfaces in two packages (`Aviary.Keeper`, `Registry.Keeper`) both emitted `KeeperBridgeState` —
   CS0101 — the moment their entry points stopped colliding and the build got far enough to try to
   emit both. `ForwardSymbolTable.csharpQualifier` renders the same relative-package segments,
   capitalised and concatenated (empty for a root-package interface, so `PetBridgeState` is
   unchanged), and `ForwardInterfaceBridgePlanner` prepends it to the state class name.
3. **On the legacy top-level routes, the public C# method name and the `_native` extern stem are
   now derived from the declaration's own name, never from the entry point.** Before this fix,
   `CirFunctionTranslator` minted the public C# name from the *cname* (`toCSharpName(cname
   .replaceFirstChar { it.uppercase() })`), which used to be harmless because the cname was the bare
   declaration name; qualifying the cname without changing this would have silently renamed
   `fun make(): Box<Int>` to a public `Test_gen_b__Make()` method and a `Test_gen_b__Make_native`
   extern the moment the symbol gained its package, with no diagnostic anywhere (memo finding 11).
   Both routes (the ordinary top-level function and the generic-function per-variant route) now read
   `csName` from `func.simpleName.asString()` — matching what the class route and the extension
   routes already did — and only the `cname` is qualified.

### Residual collisions, and the diagnostic's rewording

Package qualification does not close every collision, only the cross-package ones. Two shapes still
reach `ERROR_C_ENTRY_POINT_COLLISION`, both **inside one package and owner**:

- A member whose name spells a generated role — the running example is `fun dispose()` against the
  every-handle-class-declares `Dispose()` — although ADR-162 (accepted the same day) moved that
  particular shape earlier, to `ERROR_CSHARP_SIGNATURE_COLLISION` during `translate`, before the ABI
  contract ever runs.
- Two different declarations in one package whose mangled names meet, verified by a class method
  against a top-level function whose Kotlin name is already the mangled symbol
  (`tier1.abicollision.suspend.Radio.play(Player)` beside `tier1.abicollision.suspend.radio_play()`,
  both minting `test_abicollision_suspend__radio_play_async`): `DUPLICATE_CSHARP_IMPORT` fires, naming
  both owners.

`ForwardAbiCollision.hint` (`ForwardAbiContract.kt`) is reworded to say so: "The C entry point is the
library name, the declaration's package relative to `nuget.rootPackage`, and its enclosing chain of
simple names (ADR-163), so two same-named declarations in different packages no longer collide. What
remains is a collision inside ONE package and owner: a member whose name matches a generated role
(`fun dispose()` against the generated `Dispose`), a Kotlin `_` that reads as this scheme's own
separator, or two routes claiming one member; rename one declaration."

`Tier1EntryPointCollisionTest` lost its six cross-package cells (two classes, a Flow property, a Flow
method, a sealed class, a generic class, a generic top-level function); their positive counterparts —
both halves now bind, from their own package — live in the new `Tier1ExportSymbolSchemeTest`. What
remains in `Tier1EntryPointCollisionTest` is exactly the residual class above.

## Line 76: the outer generic return type was unqualified (a separate defect, same file)

`CirFunctionTranslator`'s generic-return arm spelled the *outer* return type by its bare simple name
(`kotlinReturnType`), while issue #111 had already qualified the type *arguments* 15 lines away and
the enum-return arm already spelled `global::$enumNamespace.$kotlinReturnType`. A top-level function
returning a generic type declared in a **different** Kotlin package than its own therefore rendered
`Box<int>` inside a namespace that does not contain `Box` — CS0246 in the consumer — while the
same-package case compiled by accident (both types share one namespace there). Fixed alongside
because it shares the exact file and lines this ADR's `csName` decoupling touches: the outer type
name is now resolved through the same `mapPackageToNamespace` call the enum arm uses and rendered
`global::$returnNamespace.$kotlinReturnType<$typeArgs>` at both the constructor call and the method's
declared return type. Verified by spike (render only, no C# compile — Tier 1 never compiles C#): a
`fun make(): Box<Int>` in package `tier1.gen.b` returning `Box<T>` declared in `tier1.gen.a`, with
`nuget.rootPackage=tier1.gen`, now emits `global::Interop.A.Box<int>` instead of a bare `Box<int>`.

## Alternatives Considered

- **Mangle only on collision, not always.** Keeps today's short symbols in the common case, but a
  forward symbol has no cross-release contract to begin with (ADR-095: "shim and native library ship
  from one build"), and on-collision renaming makes one declaration's symbol depend on whether
  another declaration exists — adding `b.Kitten` later would silently rename `a.Kitten`'s already-
  shipped symbol, the same wart Kotlin's own Objective-C export has for same-name classes. Rejected;
  always-mangle costs nothing a collision-only scheme doesn't already risk, and removes an entire
  class of "why did this rename" surprise.
- **Package-qualify only, with no library segment.** Fixes the cross-package collision but leaves
  `signal` open for a root-package declaration (its relative package is empty, so it would still be
  bare) and leaves `object Nuget { fun dispose() }` free to mint into ADR-127's reserved
  `nuget_*` space. Rejected: the library segment is required for both of those, and once it exists
  the package segment is nearly free.
- **A fixed literal prefix (e.g. `kn_`) instead of the library name.** Fixes `signal` the same way,
  but two generated shared libraries statically linked into one image would still collide on one
  symbol; the library name is already in hand (the `DllImport` names it) and costs nothing extra to
  reuse.
- **A reserved-name diagnostic instead of qualification** (refuse `fun read()` with a named error).
  Re-argued after the mingwX64 spike: the silently-dropped set is *computable* (every export of every
  import library on the mingw link line), so a diagnostic is feasible, either as a small hand-kept
  list (incomplete by construction against thousands of names, and a miss stays a silent runtime
  failure) or as one generated by `nm`-ing the konan import libraries at build time (complete for
  mingw only, a new plugin task tied to the konan bundle layout, nothing for ELF or Darwin). Either
  form only *refuses* a perfectly good `fun read()`; it never lets it bind. Rejected as the end state.
- **Hash suffix** (`kitten_create_3fa9`): short and unique, but unreadable in `nm`, a stack trace, or
  this ADR's own diagnostic, and still bare for `signal` unless prefixed anyway.
- **JNI-style escaping of every `_` in an identifier** (`_1`): fully unambiguous, but every existing
  symbol containing `_` changes shape and the escaped form is harder to read in a debugger; the
  residual-collision diagnostic (above) already covers what best-effort mangling misses.
- **Drop `@CName`, use Kotlin/Native's own `<lib>_symbols()` exported struct.** Immune to every
  hazard above by construction (one export, function pointers nested by package), but replaces every
  `DllImport` with function-pointer table lookups — a wire-format rewrite, not a naming change.

## Consequences

- **Every generated C entry point changes spelling in this one release.** `nuget-processor/src/test`
  had 132 pinned symbol literals across 45 files (a narrower grep count from the memo), all updated
  to the new shape; `IntegrationTests` pins were mechanical churn only. No consumer spells a C entry
  point directly (it is a private contract between the generated `CNameExports.kt` and the generated
  `Interop.cs`), and the shim and native library always ship from one build, so this is invisible to
  an ordinary consumer. It matters only to someone who hand-writes their own `[DllImport]` against a
  generated library's native exports instead of using the shipped `Interop.cs` — an unsupported and
  previously undocumented usage — who would need to re-derive the new symbol.
- A same-simple-name pair in two packages (a class, a top-level function, a sealed class, an enum
  extension property, a Flow member, a generic class or function) now binds, each reaching its own
  generated namespace, instead of failing the whole build.
- A top-level function whose bare name happens to match an import-library export on mingwX64
  (`signal`, `read`, `qsort`, ...) now binds instead of silently vanishing from the DLL's export
  table at runtime. The mechanism generalizes: no generated export is ever a bare user identifier on
  any target, not only mingw.
- `object Nuget { ... }` no longer risks minting into ADR-127's `nuget_*` reserved space, since every
  user symbol now starts with the sanitised library segment; a library literally *named* `nuget`
  fails the build instead, with `ERROR_RESERVED_LIBRARY_NAME`.
- `ERROR_C_ENTRY_POINT_COLLISION` is narrower in scope (a collision inside one package and owner) but
  unchanged in mechanism; its hint text is reworded to match. `ERROR_CSHARP_SIGNATURE_COLLISION`
  (ADR-162, same day) already took the `fun dispose()` shape out of this diagnostic's path entirely.
- No new handle kind, no new marshalling path, and no `LiveHandleTests.cs` row: this ADR changes
  symbol spelling and two identifier-derivation rules, not ownership or lifetime.
- Verified by execution on the packed mingwX64 DLL: `objdump -p` shows `test_signal` and no bare
  `signal`/`read`/`abort`/`exit`; 2682 `test_`-prefixed exports; every `@CName` and `EntryPoint` in the
  fixture is under `test_` or `nuget_`.

## Deferred scope

- Full JNI-grade escaping of `_` inside a Kotlin identifier that happens to read as this scheme's own
  `__` separator. The residual `ERROR_C_ENTRY_POINT_COLLISION` diagnostic is the backstop.
- A named diagnostic for an empty `rootPackage`, under which every package collapses to one C#
  namespace regardless of this ADR's own symbol qualification (a pre-existing `mapPackageToNamespace`
  behaviour, not new here). Left for its own item if it proves worth a diagnostic.
- Reverse-direction symbols (`nuget_runtime_register` and its registration thunks) are fixed names,
  not user-derived, and are out of scope for this ADR.
- Confirming the `ld.lld` `__imp_` lazy-symbol matching rule against the exact LLVM version Kotlin/
  Native 2.4.10 ships, and the ELF/Darwin equivalent of the mingw hazard this ADR closes. Neither
  changes the recommendation (a non-empty leading segment closes the class on every target either
  way); both are recorded as their own ROADMAP items.

## Amendments to other ADRs

- **ADR-095** (`Numbering scopes` table and its `Consequences`): the table's "Pre-existing, out of
  scope" note that two same-name top-level functions in different packages both export bare
  `toCName(name)` and trip `ForwardAbiContract`'s duplicate-export guard, closing with "qualifying
  export prefixes by package is its own item," is superseded by this ADR — that pairing now binds.
- **ADR-117**: the hint text quoted in its own worked example ("The C entry point is derived from the
  unqualified simple name; rename one declaration") is superseded by the reworded hint above.
- **ADR-133**: `ForwardAbiContract.hint`'s wording it records ("derived from the declaration's own
  enclosing chain of simple names, never its package") is no longer true; the entry point is now
  package-qualified, and the hint text is reworded as this ADR describes.
