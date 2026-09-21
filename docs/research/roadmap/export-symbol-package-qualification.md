# Export symbols are not package-qualified: same-simple-name types collide, and a bare top-level name can hit a CRT symbol

- ROADMAP: line 47 as of 2026-09-21: "Two exported types with the same simple name in different packages collide on their export symbol: a constructor, a top-level function, a sealed class, and (inferred) an enum property getter all hit this." Folded in: line 48 ("A top-level function named `signal` silently fails at runtime on mingwX64 with `EntryPointNotFoundException`...") and line 76 ("VERIFIED by execution: a top-level function returning a generic type declared in a different Kotlin package than its own renders the type unqualified, `CS0246`...").
- Researched: 2026-09-21, two passes. Pass 1 (about 20 of 20 minutes): source reading and two web fetches only. Pass 2 (same day, about 10 of 25 minutes): the load-bearing spikes were RUN in a scratch temp dir (`konanc` 2.4.10, `-target mingw_x64`) and in a throwaway worktree (three scratch Tier 1 cells, one one-line generator edit, all reverted). Claims now say **verified by spike** with the observed output quoted; see `## Spikes run (2026-09-21)`. Anything still marked **inferred** was not run. No `dotnet` was run in either pass.
- Restatement: a Kotlin author may export `a.Kitten` and `b.Kitten` (or `a.rollCall()` and `b.rollCall()`, or two `LoadState` sealed classes, or two `Mood` enums with an extension property) from one library and the build succeeds, with both reachable from C# under their own namespaces; and a top-level function whose name happens to be a C runtime symbol (`signal`, `abort`, `exit`, `read`) resolves at runtime on every target. Forward; Kotlin declares, C# consumes. The C symbol is a private contract between the generated `CNameExports.kt` and the generated `Interop.cs`; no consumer spells it.
- Verdict: **fix (lines 47 and 48 share one fix; line 76 is a separate small fix).** ADR needed, NOT drafted (this run may write only this memo; last ADR in `docs/adr/README.md` is 157, so the next free number is 158 unless a sibling agent takes it first). Alternatives for the ADR are listed under Recommendation.

## Findings

### 1. How every route composes its `@CName` today: one chokepoint for class-like owners, and three families that bypass it (verified by reading)

No route includes the package. The composition is `<owner chain lowercased, "_"-joined>_<role or member>[_<overload n>]`, or a bare name for top-level callables.

| Route | Where the prefix comes from | Package in symbol? |
|---|---|---|
| Class ctor / method / property / dispose / equals / hashcode / companion | `KSClassDeclaration.nativePrefix()` `cir/CirTypeMapping.kt:235-241` (walks `parentDeclaration` while it is a class, lowercases, joins with `_`); callers `forward/ForwardCallablePlanner.kt:789`, `:1085`, `:1528`, `:1879`, `forward/ForwardPropertyPlanner.kt:320-321`, `:360`, `exports/ClassExports.kt:84`, `cir/CirClassTranslator.kt:586`, `:1907`, `:3060` | no |
| Object members | `obj.nativePrefix()` `ForwardCallablePlanner.kt:1828`, `:1851`; `ForwardPropertyPlanner.kt:396`, `:409-410`; `CirClassTranslator.kt:2302` | no |
| Interface dispatch and bridge factory | `iface.nativePrefix()` `ForwardCallablePlanner.kt:1029`, `ForwardPropertyPlanner.kt:336`, `exports/InterfaceExports.kt:34`, `CirClassTranslator.kt:2921`, `forward/ForwardInterfaceBridgePlanner.kt:124` (`${prefix}_bridge_create`) | no |
| Sealed class and arms (planned) | `sealed.nativePrefix()` + `_${arm.lowercase()}` `ForwardCallablePlanner.kt:694`, `:1234`, `:1359`, `:1500`; `ForwardPropertyPlanner.kt:208`, `:242`; `exports/SealedClassExports.kt:40` | no |
| Sealed class (four processor-level sites) | **raw** `sealed.simpleName.asString().lowercase()`, NOT `nativePrefix()`: `NugetProcessor.kt:2097`, `:2117`, `:2144`, `:2164` (arm prefixes at `:2104`, `:2121`, `:2147`, `:2167`) | no |
| Enum (extension members on an enum) | `enum.nativePrefix()` `exports/EnumExports.kt:25`, `CirClassTranslator.kt:3040`; model default `cir/CirModel.kt:247` (`name.lowercase()`) | no |
| Top-level function (planned) | bare `toCName(name)` + ADR-095 suffix: `ForwardCallablePlanner.kt:1806` | no |
| Top-level function (legacy generic/lambda return) | bare `toCName(name)`: `exports/FunctionExports.kt:89`, `cir/CirFunctionTranslator.kt:76` | no |
| Top-level generic function (`_object`, per-variant) | bare `toCName(name)`: `cir/CirFunctionTranslator.kt:682` (Kotlin half `exports/GenericFunctionExports.kt`, 4 `cNameAnnotation` sites) | no |
| Top-level suspend (`_async`) | bare `toCName(name)`: `exports/SuspendFunctionExports.kt:49`; member suspend `toCName(methodName) + suffix` under `cls.nativePrefix()` `:90`, `:118` | no |
| Top-level property | bare `toCName(name)`: `ForwardPropertyPlanner.kt:418`, `cir/CirTranslator.kt:1258` | no |
| Extension function / property | `${receiverPrefix}_${toCName(name)}$suffix` where `receiverPrefix` is the RECEIVER's `nativePrefix()`, falling back to the receiver's raw lowercased simple name for a non-class receiver: `ForwardCallablePlanner.kt:2195-2196`, `:2263`; `ForwardPropertyPlanner.kt:447-448`, `:475-476`; legacy `cir/CirTranslator.kt:958`, `:1179` | no (neither the receiver's nor the extension's own package) |
| Flow members (`_collect`, `_value`, `_has_value`) | `toCName(methodName) + overloadSuffix` under the owner prefix: `exports/FlowExports.kt:262`; C# strings `cir/CirClassRenderer.kt:298-310` | no |
| Callbacks, collections, scalars, errors, scopes | fixed `nuget_*` names owned by `nuget-runtime` (ADR-127, 66 to 67 names, `docs/adr/127-nuget-runtime-library.md:165-174`) | n/a, reserved prefix |

Consequences of that table (verified by reading):
- `nativePrefix()` IS the chokepoint for class-like owners on both halves. Every `CirModel.kt` `nativePrefix` field (`:142`, `:193`, `:247`, `:262`, `:292`, `:389`) is filled from it in `CirClassTranslator.kt` (`:946`, `:1118`, `:1872`, `:1933`, `:2051`, `:2175`, `:2202`, `:2375`, `:2953`, `:3162`), and the C# renderers build their `EntryPoint = "..."` strings from those fields (`cir/CirSealedRenderer.kt:57`, `:162`, `:283`, `:308`, `:311`; `cir/CirClassRenderer.kt:298-310`). So a change inside `nativePrefix()` propagates to both halves for every class-like route.
- Three families bypass it and must be swept in the same change, or the Kotlin and C# halves disagree and `ForwardAbiContract`'s generator-bug `require` fires: (a) the four raw sealed sites in `NugetProcessor.kt`; (b) every bare top-level `toCName(name)` site listed above; (c) the non-class-receiver fallback at `ForwardCallablePlanner.kt:2196` and `ForwardPropertyPlanner.kt:448`.
- **Verified by spike (2026-09-21):** on the PLANNED top-level route the two halves already share one string. A one-line edit of `exportName` at `ForwardCallablePlanner.kt:1806` (prepend the package) changed BOTH `@CName("tier1_ns_a__rollCall")` in `CNameExports.kt` and `EntryPoint = "tier1_ns_a__rollCall"` in `Interop.cs`, with no `ForwardAbiContract` `require` firing. The same edit left a LEGACY-route function bare: `fun make(): Box<Int>` still emitted `@CName("make")` / `EntryPoint = "make"`, which confirms bypass family (b) is real and is not reached from the planner.
- There is one Kotlin-side minter, `cNameAnnotation(value, owner)` `exports/Helpers.kt:157` (ADR-117 amendment), but NO C#-side equivalent: there are about 113 raw `EntryPoint = "` string sites across 12 `cir/*` files (49 of them in `CirMarshalRenderer.kt`, which are the `nuget_*` runtime names). A library-wide prefix therefore cannot be applied "at the minter" on both sides; it has to be part of the prefix/name the two halves already share.

### 2. LOAD-BEARING: qualifying the symbol alone does not fix the top-level shapes; the Kotlin call site is unqualified too (verified by reading, and verified by spike 2026-09-21)

**Verified by spike:** with ONLY the cname qualified (the `:1806` edit above) and two sources `tier1.ns.a.rollCall()` / `tier1.ns.b.rollCall()`, KSP exits `OK`, there is no `ERROR_C_ENTRY_POINT_COLLISION`, and the generated file fails the harness's Kotlin compile:

```
CNameExports.kt:35:3: Overload resolution ambiguity between candidates:
fun rollCall(): String
fun rollCall(): String
CNameExports.kt:47:3: Overload resolution ambiguity between candidates:
fun rollCall(): String
fun rollCall(): String
```

The generated text was `import tier1.ns.a.rollCall` + `import tier1.ns.b.rollCall`, then `@CName("tier1_ns_a__rollCall") public fun export_tier1_ns_a__rollCall(...) = try { rollCall() ...`. So the wrapper names do become unique for free, the imports and the bare call do not, and the emitter change below is REQUIRED. The C# half of the same cell was already fine: `public static partial class A` and `partial class B` each hold their own `RollCall()`.

`forward/ForwardKotlinPlanEmitter.kt:950` renders a top-level invocation as bare `"$functionName($arguments)"` and extension invocations as `receiver.$functionName(...)` (`:949`); the declarations are made visible by simple-name imports: `NugetProcessor.kt:1846`, `exports/FunctionExports.kt:100`, `exports/GenericFunctionExports.kt:46`, `exports/SuspendFunctionExports.kt:52`, `exports/PropertyExports.kt:22`, `exports/ExtensionFunctionExports.kt:23`, `exports/ExtensionPropertyExports.kt:35`. Two same-named, same-signature top-level functions from two packages therefore import to one simple name and the call is ambiguous. This is exactly backlog shape 6's observed "Overload resolution ambiguity in generated `CNameExports.kt`" (`docs/backlog/two-exported-types-same-simple-name-different.md:15`). The generated Kotlin wrapper names are `export_$cname` (`FunctionExports.kt:118`), so they become unique for free once the cname is.

Class routes do not have this problem: the plan emitter spells types by `qualifiedName` (`ForwardKotlinPlanEmitter.kt:515`, `:548-549`, `:1157`). Inferred: the legacy class exports use KotlinPoet `ClassName`s, which KotlinPoet qualifies on a simple-name clash.

Fix shape: a top-level call renders fully qualified (`` `a.b`.rollCall(args) ``, no import); an extension call cannot be qualified in Kotlin, so a clashing extension needs `addAliasedImport` (none exist today, verified by grep) or an always-aliased import derived from the cname.

### 3. Symbol stability and the "ABI contract hash" are not constraints on the forward side (verified by reading)

- `docs/adr/095-static-route-overloads.md:122-123`: "Cross-build stability beyond source order is not required: shim and native library ship from one build, ADR-054." ADR-095's `_n` suffix is already declaration-order dependent.
- The only "contract hash" in the repo is the REVERSE direction's (ADR-054/057/058/059/072, found by grep). The forward `ForwardAbiContract` (`ForwardAbiContract.kt`) is a same-build Kotlin-half versus C#-half consistency check plus the ADR-117 collision detector; nothing persists or compares forward symbols across releases.
- So an always-mangled scheme costs nothing on stability or hash grounds. Its real cost is pinned-string churn in tests and docs (finding 8).

### 4. ADR-117 / ADR-118 / ADR-133 today: the collision is loud, and the hint text hard-codes the current scheme (verified by reading)

`ForwardAbiCollision.hint` (`ForwardAbiContract.kt:87-91`) says "The C entry point is derived from the declaration's own enclosing chain of simple names, never its package (ADR-133), so two same-named declarations in different packages collide; rename one declaration." `Tier1EntryPointCollisionTest.kt` pins eight cells, six of them cross-package shapes (`:26` classes, `:159` Flow property, `:214` Flow method, `:265` sealed, `:341` generic class, `:393` generic top-level function). After the fix those six stop being collisions and must flip to positive "both bind" cells. Two cells are a DIFFERENT class that package qualification does not touch: `:58` (`fun dispose()` versus the generated `Dispose`, `cat_dispose`) and `:102` (a suspend method versus a top-level suspend function). ADR-117's diagnostic stays as the backstop for those and for the residual ambiguity in finding 7.

**Verified by spike (2026-09-21), the primary collision as it fails today** (`tier1.ns.a.Kitten(val name: String)` + `tier1.ns.b.Kitten(val name: String)`): KSP exits `PROCESSING_ERROR`, no `CNameExports.kt` is written, and THREE collisions are reported, one per symbol (`kitten_create`, `kitten_dispose`, `kitten_get_name`). The first, verbatim apart from the temp path:

```
[nuget:ERROR_C_ENTRY_POINT_COLLISION] Error tier1.ns.a.Kitten(String): Forward ABI duplicate C# import for kitten_create; 2 Kotlin declarations export the same C entry point:
  - tier1.ns.a.Kitten(String)
    at .../src/A.kt:3
  - tier1.ns.b.Kitten(String)
    at .../src/B.kt:3. The C entry point is derived from the declaration's own enclosing chain of simple names, never its package (ADR-133), so two same-named declarations in different packages collide; rename one declaration. [kitten_create(in string, out pointer) -> pointer, kitten_create(in string, out pointer) -> pointer]
```

So today's failure is a loud, named build error, never a link error or a wrong binding.

### 5. The `signal` failure: mechanism now known (verified by spike 2026-09-21; the pass 1 account was partly WRONG, see the dated note)

Spike: a scratch Kotlin/Native file of explicit `@CName` functions, built with `konanc` 2.4.10 (`-produce dynamic -target mingw_x64`, the repo's Kotlin version), then `objdump -p` (export name table) and `nm` on the DLL. No generator involved, so this isolates the toolchain.

- **Verified by spike: `signal` is defined in the DLL and ABSENT from the export table.** It is not "present but shadowed at P/Invoke resolution". `nm probe.dll`: `T abort`, `T exit`, `T lib_signal`, `T raise`, `T read`, `T sibling`, `T signal`. Export name table (172 names in total), filtered to the probe's names:

  ```
  [  43] +base[  44]  002b last_signal
  [  44] +base[  45]  002c lib_signal
  [  46] +base[  47]  002e probe_symbols
  [ 169] +base[ 170]  00a9 sibling
  ```

  `signal`, `abort`, `exit`, `raise`, `read` are all missing; the hand-prefixed `lib_signal` (same body, same file) is exported. This reproduces the backlog file's observation on a second, generator-free build.
- **Verified by spike: it is not CRT-only.** A second probe: `qsort`, `strtok`, `open` (msvcrt), `Beep` (kernel32), `MessageBoxA` (user32) and `BCryptGenRandom` (bcrypt) were ALL silently dropped from the export table; `kitten_create`, `lib_qsort` and lowercase `sleep` were exported (why `sleep` survives was not checked; inferred: no import library on the link line exports that exact name). The match is exact-name and case-sensitive.
- **Verified by spike: there is a second, LOUD outcome class.** `@CName("atexit")`, `"main"`, `"DllMain"` and `"Sleep"` fail the link instead: `ld.lld: error: duplicate symbol: atexit >>> defined at ...crtdll.c:214 ...dllcrt2.o >>> defined at ...probe.dll.o`, and `duplicate symbol: Sleep ... defined at libkernel32.a(duirs01409.o)`. These are names defined in always-linked CRT objects or imports the Kotlin runtime itself pulls in. So "no build-time diagnostic" is true for `signal` but not universal: a clashing name either fails the link loudly or vanishes from the exports silently, depending on whether anything in the image already resolved it.
- **Verified by spike: the linker is `ld.lld` in MinGW mode with auto-export, and no `.def` is an INPUT.** `-Xverbose-phases=Linker` shows `clang++ ... probe.dll.o -shared -lbcrypt -lwinpthread -fuse-ld=...llvm-21-x86_64-windows-essentials-150\bin\ld.lld.exe`: no `.def`, no `--export-all-symbols`, no `--exclude-symbols`. Kotlin/Native does WRITE a `probe.def` next to the DLL, and that file lists `signal`, `abort`, `exit`, `raise`, `read`; it is an output for consumers, and it is wrong about the DLL it describes. Auto-export is confirmed by `pthread_cond_signal` and other runtime symbols appearing in the table.
- **Inferred from lld source, consistent with every observation above, not proven:** lld `COFF/MinGW.cpp`, `AutoExporter::shouldExport` (fetched from `llvm-project` `main`; konan ships llvm-21, not diffed): "If a corresponding __imp_ symbol exists and is defined, don't export it." followed by `if (symtab.find(("__imp_" + sym->getName()).str())) return false;`. The code checks existence only, and the final DLL has NO `__imp_signal` (`nm | grep __imp_` shows `__imp__exit`, not `__imp_signal`), so the match is inferred to be against the LAZY archive symbol every import library on the link line contributes (`libmsvcrt.a`, `libkernel32.a`, `libuser32.a`, `libadvapi32.a`, `libshell32.a`, `libbcrypt.a`, ...), whether or not anything uses it. That reading predicts exactly the probe result (unused `qsort`, `Beep`, `MessageBoxA` dropped; `sleep` kept). The binutils-`ld` manual cited in pass 1 is NOT the relevant document: the linker is lld.
- **Dated note, 2026-09-21, what pass 1 got wrong.** Pass 1 said the excluded set is "a few symbols known to belong to the system's runtime", "undocumented", and guessed at a CRT thunk. Observed instead: the silently dropped set is the union of EVERY export of every import library on the mingw link line, thousands of names, Win32 as well as CRT, and it is computable (`nm` the import libraries of the konan dependency bundle `msys2-mingw-w64-x86_64-2`). It is still toolchain-owned: it changes when Kotlin/Native bumps that bundle or its default `-l` set.
- **Practical hit set (verified by reading `Reserved.kt:25-28`: `toCName` preserves case):** a camelCase Kotlin top-level function collides with the LOWERCASE names, which are the CRT/POSIX ones. OBSERVED silently dropped in spike 1: `signal`, `read`, `open`, `exit`, `abort`, `raise`, `qsort`, `strtok`. INFERRED, not observed (same import library, same rule expected): `write`, `close`, `remove`, `rename`, `time`, `clock`, `rand`, `system`, `getenv`, `printf`, `puts`, `log`, `sin`, `pow`, `round`, `floor`, `malloc`, `free`. Some of the inferred ones may instead land in the loud duplicate-symbol class if a statically linked `libmingwex.a` or CRT object defines them. An interim reserved list must be built from observed drops, not from this sentence. Win32 PascalCase names only collide with a PascalCase Kotlin function. Owner-prefixed symbols (`cat_read`) are safe today by the same mechanism that makes a library prefix safe.
- **Is the prefix a fix by mechanism or by luck? By mechanism (verified for the hand-prefixed name, inferred for the rule).** A symbol is dropped or rejected only when its EXACT name is an import-library export or a CRT-object definition. `<lib>_<anything>` is in neither set unless a system DLL exports that exact string. Still pending: the end-to-end xunit proof after the generator change (spike b, narrowed).
- **Inferred from spike 1's output, not proven: the user's definition INTERPOSES the runtime's own calls on mingwX64 too.** `nm` shows exactly one `T abort` and one `T exit`, there is no `__imp_abort` or `__imp_exit` in the image, and the link raised no duplicate-symbol error, although the Kotlin runtime references both. The simplest reading is that the user's `abort` satisfied those references, so the runtime's `abort()` now runs Kotlin user code. If so a bare `exit` or `abort` is worse than a missing entry point, which strengthens always-prefix over a diagnostic that an author can suppress or a list can miss.
- **Inferred, unverified on any target:** on ELF, a default-visibility `signal`/`read`/`abort` exported from the Kotlin `.so` can also interpose the Kotlin runtime's own calls to libc inside that library (no `-Bsymbolic`), which is worse than a missing entry point. On Darwin the two-level namespace plausibly makes the bare name work. Neither was run (Windows box).
- `Reserved.kt:3-9` is a C KEYWORD list (`toCName` appends `_`), not a libc/CRT list (verified by reading). Note `toCName("signal") == "signal"`.
- Decisive point for the design, RE-ARGUED: pass 1 argued "a list can never be complete because the set is undocumented". That argument is withdrawn. The honest comparison is: a reserved-name diagnostic is FEASIBLE but must either be a hand-kept list (incomplete by construction against thousands of names, and a miss is still a silent runtime failure on one OS) or be generated by `nm`-ing the konan import libraries at build time (complete for mingw, a new plugin task, a new dependency on the konan bundle layout, and nothing for ELF interposition). Both only REFUSE the name, so the author must rename a perfectly good `fun read()`. A scheme in which no generated symbol is a bare user identifier removes the class on every target, costs nothing extra once line 47 is changing every symbol anyway, and lets `fun signal()` bind. Always-prefix still wins; the reason is "the set is huge, toolchain-owned and per-target, and a diagnostic only refuses", not "the set is unknowable".
- Package qualification alone does NOT fix `signal`: a function in the root package has an empty relative package and stays bare. This is why the end state needs a fixed leading segment as well as the package.

### 6. ADR-127 and ADR-109 (verified by reading)

- ADR-127 reserves `nuget_*` for the runtime's fixed ABI (`127-nuget-runtime-library.md:165-174`). There is NO guard against a user symbol landing in that space (grep for `startsWith("nuget_` in `nuget-processor/src/main`: none). Today `object Nuget { fun dispose() }` would mint `nuget_dispose`. Inferred, not reproduced: that collides with the runtime export at link time or binds the wrong function.
- ADR-109 (duplicate-type hazard across two published packages) is about two LIBRARIES each exporting the same dependency type. `[DllImport("lib")]` resolves per library handle, so identical symbols in two dynamic libraries do not clash for P/Invoke (inferred from .NET's documented probing; not run). They WOULD clash under static linking (`__Internal`, iOS/Catalyst style), which the repo does not appear to use for forward bindings (inferred; a grep for `__Internal` errored out and was not retried). A library-derived leading segment makes the symbols unique even then, a fixed literal does not.

### 7. Prior art (inferred: from documentation and my own knowledge, two pages fetched, no tool was run)

- **JNI**: always fully package-mangled, `Java_<pkg>_<Class>_<method>`, `.` to `_`, a literal `_` escaped as `_1`, overloads append `__<signature>`. Unambiguous by construction, readable, long. The escape is what buys unambiguity.
- **UniFFI**: always namespaced by crate, `uniffi_<crate>_fn_func_<name>`, `..._fn_method_<type>_<name>`, `..._fn_constructor_<type>_<name>`, plus `uniffi_<crate>_checksum_*` symbols. Fixed tool prefix + library name + role + owner + member. Closest analogue to this project. (The one page fetched, `internals/foreign_calls.html`, did not state the pattern; this is from memory of generated scaffolding, inferred.)
- **cbindgen**: no symbol mangling at all; the author writes `#[no_mangle]` names and owns collisions; only TYPE names can take a configured `prefix`. It is the "do nothing" pole.
- **Kotlin/Native C export (no `@CName`)**: exports ONE symbol, `<libname>_symbols()`, returning a struct of function pointers nested by package (`kotlin.root.<pkg>.<fn>`); real functions are internal. Package-qualified by structure, library-prefixed by name, immune to libc clashes. `@CName` opts out of that protection, which is the hole this item sits in.
- **Kotlin ObjC export**: framework prefix on every class (`SharedKitten`), top-level functions on a `<File>Kt` class, and a same-name clash resolved by appending `_` to the later one (`Kitten_`). That is mangle-on-collision, and it is a known pain point: names are order-dependent and adding a declaration renames another.
- **Kotlin Swift export**: packages become nested namespaces (enums), so every declaration is always package-qualified; flattening is an opt-in convenience layer.
- Pattern: every tool that GENERATES both sides of the boundary (JNI headers, UniFFI, Kotlin's own struct export, Swift export) always qualifies. Only the tools where a human writes or reads the foreign name (cbindgen, ObjC export) keep bare names, and ObjC export's on-collision renaming is the cautionary tale.

### 8. Cost of changing every symbol: pinned strings (verified by grep counts, 2026-09-21)

- `nuget-processor/src/test`: 58 files mention `EntryPoint = ` or `@CName(`; a narrower pattern (`"<owner>_(create|dispose|get_|set_)...`) finds 132 pinned symbol literals in 45 files. These are text pins and all move.
- `IntegrationTests/*.cs`: five files mention `EntryPoint` (`MethodOverloadTests.cs`, `SealedSubclassMethodTests.cs`, `StaticRouteOverloadTests.cs`, `SuspendMethodOverloadTests.cs`, `ValueClassDeclaredMemberTests.cs`); inferred to be reflection or comment pins of suffix numbering, not opened.
- Docs that spell the scheme: `docs/topics/architecture.md:53`, `:70` (the `toy_` walkthrough), `docs/topics/forward-overview.md:860-889` (the collision section, quotes `kitten_create`), `docs/topics/extensions.md:239`, ADR-133 `:263`, ADR-117 message format, ADR-095 table `:103-106`.

### 9. Things that go live the moment symbols stop colliding (verified by reading the backlog, mechanisms inferred)

- `<Name>BridgeState` (`ForwardInterfaceBridgePlanner.kt:130`, `"${iface.nestedCsName().replace(".", "")}BridgeState"`, verified by reading) carries the owner chain but no package: two `Keeper` interfaces give CS0101 in the shared root-namespace helper (backlog shape 5). Must take the same qualifier in the same change.
- The merged extension class groups by `(extensionNamespace(receiver, func), receiver.nestedCsName())` (`CirTranslator.kt:593-602`, verified by reading). The backlog's ADR-126 note says `a.Kitten` and `b.Kitten` merge into one `KittenExtensions`; whether the namespace half of today's key already separates them depends on `extensionNamespace`, which was NOT opened in this run. Inferred: an extension declared in ONE package on both `a.Kitten` and `b.Kitten` still merges (same namespace, same receiver key) and would emit two same-named `this Kitten` overloads that differ only by namespace-qualified type, which compiles, so this may be benign. Pin it with a Tier 1 cell rather than trusting either reading.
- With an EMPTY `rootPackage`, `mapPackageToNamespace` returns `rootNamespace` for every package (`CirTypeMapping.kt:262`), so `a.Kitten` and `b.Kitten` land in one C# namespace: CS0101 in the consumer, where today the symbol collision fails the Kotlin build first. Needs a named diagnostic (or stays refused) in that configuration.

### 10. Line 76 (cross-namespace generic return, CS0246) is a different defect (verified by reading; render verified by spike 2026-09-21)

`cir/CirFunctionTranslator.kt:84` sets `kotlinReturnType` to the return declaration's SIMPLE name; the generic-return arm uses it raw at `:532` (`new $kotlinReturnType<$typeArgs>(nativeResult)`) and `:537` (`returnType = "$kotlinReturnType<$typeArgs>"`). The type ARGUMENTS were already qualified by issue #111 (`:509-510`, `csTypeArgumentNames`), and the enum arm 15 lines below already spells `global::$enumNamespace.$kotlinReturnType` (`:552`). So only the outer type name is unqualified. It is C# type spelling on the ADR-064 legacy route; it shares no code with the symbol scheme. It does share a worktree-level file (`CirFunctionTranslator.kt`, whose `:76` and `:682` cname lines this item edits), so it can ride in the same PR as a separate commit.

**Verified by spike (2026-09-21), the render itself.** Tier 1 cell: `A.kt` = `package tier1.gen.a` / `class Box<T>(val v: T)`; `B.kt` = `package tier1.gen.b` / `fun make(): Box<Int> = Box(1)`; options `nuget.rootPackage=tier1.gen`. KSP `OK`, the Kotlin half compiles clean, and `Interop.cs` has:

```
namespace Interop.B
    public static partial class B
        [DllImport("library", CallingConvention = CallingConvention.Cdecl, EntryPoint = "make")]
        private static extern IntPtr Make_native(out IntPtr error);
        public static Box<int> Make()
            return new Box<int>(nativeResult);
namespace Interop.A
    public class Box<T> : IDisposable, INugetHandle
```

`Box<int>` is spelled unqualified at both `:532` and `:537` inside `Interop.B`, while `Box<T>` lives in `Interop.A`: that is the CS0246. The C# compile itself was NOT run in this pass (Tier 1 never compiles C#, ADR-060); the CS0246 rests on the backlog line's own "verified by execution" plus this text. Two side observations from the same cell: the function DID take the ADR-064 legacy route (extern is `Make_native`, the legacy naming, not the planned `Native_Make`), and with NO `rootPackage` both types land in one `namespace Interop`, so the defect is invisible in a default-options Tier 1 cell. A regression cell must set `nuget.rootPackage`.

### 11. On the legacy top-level routes the public C# name is derived FROM the cname (verified by reading 2026-09-21; found while checking spike d)

`cir/CirFunctionTranslator.kt:76` mints `cname = toCName(func.simpleName)` and `:81` mints `csName = toCSharpName(cname.replaceFirstChar { it.uppercase() })`; `:682` and `:684` repeat the pair for the generic-function route. `csName` is the public method name (`CirMethod(name = csName`) and the stem of the extern (`"${csName}_native"`, `:198`, `:515`). An implementer who qualifies `:76` or `:682` and changes nothing else gets a public C# method `Testlib_gen_b__make()` and an extern `Testlib_gen_b__make_native`, with no diagnostic and no failing Tier 1 pin unless a cell asserts the public name. Fix shape: derive `csName` from `func.simpleName` (as `:771-774` and `cir/CirTranslator.kt:954-955` already do), and only then qualify `cname`. The other bypass sites were grepped and are clean on this point: `CirTranslator.kt:955` (extension) and `:1258` (top-level property) take the C# name from the declaration name, and the Kotlin-half files (`FunctionExports.kt:89-90`, `SuspendFunctionExports.kt:49-50`, `GenericFunctionExports.kt:35`) keep a separate `funcName` for the call and import. Side note, verified by reading: `GenericFunctionExports.kt:74` and `:122` build the cname from raw `funcName`, not `toCName(funcName)`, while the C# half at `CirFunctionTranslator.kt:682` uses `toCName`; the two agree only while the name is not a C keyword. The symbol table in open question 7 removes both hazards.

## Recommendation

**End state: every generated `@CName` is `<lib>_<package>__<owner chain>_<member>[_<n>]`, always, on every route.** Concretely:
- `<lib>`: the library's `libraryName`, lowercased and sanitised to `[a-z0-9_]` (the same value the `DllImport` already names). Never empty, so no symbol is ever a bare user identifier, which fixes `signal` and every other CRT/libc name on every target without a list. Verified by spike 1 for the mingwX64 mechanism: a hand-prefixed `lib_signal` and `lib_qsort` are exported where `signal` and `qsort` are dropped, because the drop is an exact-name match against import-library exports (finding 5). If the sanitised value is `nuget`, fail with a named diagnostic (ADR-127's space).
- `<package>`: the declaring Kotlin package RELATIVE to `rootPackage` when under it (the `isUnderPackage` rule `mapPackageToNamespace` already uses), else the whole package; lowercased, `.` to `_`; omitted with its separator when empty. For a class-like owner it is the owner's package; for a top-level or extension callable it is the CALLABLE's own package (not the receiver's), matching ADR-095's `(package, name)` counter scope.
- `__` (double underscore) between the package part and the owner chain, so `a.b` + `C` (`a_b__c`) and `a` + `B.C` (`a__b_c`) differ. Kotlin identifiers may themselves contain `_`, so this is best-effort, not a JNI-grade escape; ADR-117's collision diagnostic stays as the loud backstop, with its hint rewritten.
- Implemented by (1) giving `nativePrefix()` a context-aware form that prepends `<lib>_<package>__`, (2) one new helper for top-level/extension callables, (3) sweeping the three bypass families in finding 1, (4) qualifying top-level Kotlin calls and aliasing extension imports (finding 2), (5) qualifying `BridgeState` and the extension-class grouping key (finding 9).

Priced: about 14 generator files, 1 message/hint file, 2 fixture files, 2 new xunit tests, 1 Tier 1 file flipped plus roughly 45 Tier 1/unit files of mechanical pin churn, 5 docs. Size L, almost all of it mechanical; the design risk is concentrated in findings 2 and 9. No runtime (`nuget-runtime`) or plugin change expected (inferred).

Alternatives rejected (the ADR's alternatives section):
- **Mangle only on collision**: keeps today's short names, but adding `b.Kitten` renames `a.Kitten`'s symbols (ObjC export's known wart), needs a whole-library pre-pass before any planner runs, and does nothing for `signal` (there is no in-library collision to detect).
- **Package-qualify only, no library segment**: fixes line 47, leaves line 48 open for root-package functions, and leaves `object Nuget` able to mint `nuget_*`.
- **Fixed literal prefix (`kn_`) instead of the library name**: fixes `signal`, but two generated libraries statically linked into one image still clash; the library name is already in hand and costs the same.
- **Diagnostic against a reserved-name list** (re-argued 2026-09-21 after spike 1): feasible, because the silently dropped set on mingwX64 is computable (every export of every import library on the link line, finding 5). A hand-kept list in `Reserved.kt` is incomplete by construction against thousands of names and a miss stays a silent runtime failure; a list generated by `nm`-ing the konan import libraries is complete for mingw only, adds a plugin task tied to the konan bundle layout, and says nothing about ELF or Darwin. Either form only REFUSES `fun read()`; it never binds it. Rejected as the end state; a SHORT hand-kept lowercase CRT list (finding 5's practical hit set) is still worth shipping as an interim ERROR if the scheme is not scheduled soon, since it turns the known silent cases loud for about ten lines of code.
- **Hash suffix (`kitten_create_3fa9`)**: short and unique, unreadable in `nm`, stack traces and the ADR-117 diagnostic, and still bare for `signal` unless prefixed anyway.
- **JNI-style `_1` escaping for full unambiguity**: correct but every existing name containing `_` changes shape and readability drops; the collision diagnostic already covers the residue.
- **Stop using `@CName`, use Kotlin's `<lib>_symbols()` struct**: immune by construction, but replaces `DllImport` with function-pointer table lookups on every route; a rewrite, not a naming change.

## Files an implementation touches

Generator (`nuget-processor/src/main/kotlin/io/github/xxfast/kotlin/native/nuget/processor/`):
- `cir/CirTypeMapping.kt` (`nativePrefix()` `:235`, new top-level symbol helper beside it)
- `Reserved.kt` (library-segment sanitiser; optional interim CRT list)
- `NugetProcessor.kt` (`:2097-2167` raw sealed prefixes; `:1846` import)
- `forward/ForwardCallablePlanner.kt` (`:1806`, `:2195-2196`, `:2263`, and every `nativePrefix()` caller if the signature gains a context parameter)
- `forward/ForwardPropertyPlanner.kt` (`:418`, `:447-448`, `:475-476`)
- `forward/ForwardKotlinPlanEmitter.kt` (`:949-950` qualified call / aliased import)
- `forward/ForwardInterfaceBridgePlanner.kt` (`:124`, `BridgeState` name ~`:130`)
- `exports/FunctionExports.kt` (`:89`, `:100`), `exports/GenericFunctionExports.kt` (`:46`), `exports/SuspendFunctionExports.kt` (`:49`, `:52`), `exports/PropertyExports.kt` (`:22`), `exports/ExtensionFunctionExports.kt` (`:23`), `exports/ExtensionPropertyExports.kt` (`:35`)
- `cir/CirFunctionTranslator.kt` (`:76`, `:682`; FIRST decouple `csName` at `:81` and `:684` from `cname`, finding 11; plus line 76's fix at `:532`, `:537`)
- `cir/CirTranslator.kt` (`:958`, `:1179`, `:1258`, `extensionsByReceiver` key)
- `cir/CirModel.kt` (`:247` enum default `name.lowercase()` must not survive as a fallback)
- `ForwardAbiContract.kt` (`:87-91` hint text)
Fixtures: `test-library/src/nativeMain/kotlin/io/github/xxfast/kotlin/native/nuget/test/` new `namesake/a/Kitten.kt`, `namesake/b/Kitten.kt` (class, sealed, enum + extension property, top-level function each), a root-or-any-package `fun signal(dbm: Int)`; un-rename is optional for `Mitten`/`Tomcat`, `Issue56LoadState`, `rosterRoll`, `collarSignal`. For line 76: restore research H's `UnroutedTopLevelGeneric.kt` (it was moved to `.kt.disabled`; not located in this run).
Tests: new `IntegrationTests/NamesakeTests.cs`; `nuget-processor/src/test/.../tier1/Tier1EntryPointCollisionTest.kt` (six cells flip to positive); `ReservedTest.kt`; about 45 Tier 1/unit files of pinned-symbol churn (finding 8); `IntegrationTests/{MethodOverload,SealedSubclassMethod,StaticRouteOverload,SuspendMethodOverload,ValueClassDeclaredMember}Tests.cs` if they pin symbols. No leak rows: no handle lifetime changes (inferred).
Docs: new ADR (158 or next free); `docs/topics/architecture.md:53-70`; `docs/topics/forward-overview.md:860-889`; `docs/topics/extensions.md:239`; amendments to ADR-095 (`:103-106`, `:264-272`), ADR-117 (hint), ADR-133 (`:263`); delete the three backlog files and ROADMAP lines 47, 48, 76 on close.

## Sample test

Fixture Kotlin:

```kotlin
// test/namesake/a/Kitten.kt
package io.github.xxfast.kotlin.native.nuget.test.namesake.a
class Kitten(val name: String) { fun greet(): String = "a:$name" }
fun rollCall(): String = "a"

// test/namesake/b/Kitten.kt
package io.github.xxfast.kotlin.native.nuget.test.namesake.b
class Kitten(val name: String) { fun greet(): String = "b:$name" }
fun rollCall(): String = "b"

// test/namesake/Signal.kt
package io.github.xxfast.kotlin.native.nuget.test.namesake
private var last: Int = 0
fun signal(dbm: Int) { last = dbm }
fun lastSignal(): Int = last
```

xunit (file-class names follow ADR-007 and are inferred here):

```csharp
[Fact]
public void TwoKittens_InDifferentPackages_BothBind()
{
    using var a = new TestLibrary.Namesake.A.Kitten("Oreo");
    using var b = new TestLibrary.Namesake.B.Kitten("Oreo");
    Assert.Equal("a:Oreo", a.Greet());
    Assert.Equal("b:Oreo", b.Greet());
}

[Fact]
public void TwoTopLevelFunctions_InDifferentPackages_CallTheirOwnDeclaration()
{
    Assert.Equal("a", TestLibrary.Namesake.A.KittenKt.RollCall());
    Assert.Equal("b", TestLibrary.Namesake.B.KittenKt.RollCall());
}

[Fact]
public void TopLevelFunctionNamedSignal_Resolves()   // today: EntryPointNotFoundException on mingwX64
{
    TestLibrary.Namesake.SignalKt.Signal(-42);
    Assert.Equal(-42, TestLibrary.Namesake.SignalKt.LastSignal());
}
```

Tier 1 cell (replaces `two classes with one simple name in different packages name both constructors`): two sources `pkg.a.Kitten`, `pkg.b.Kitten`; assert zero `ERROR_C_ENTRY_POINT_COLLISION`, `Interop.cs` contains both `EntryPoint = "<lib>_a__kitten_create"` and `EntryPoint = "<lib>_b__kitten_create"`, and `CNameExports.kt` contains both `@CName` strings. A second cell: root-package `fun signal(dbm: Int)` emits `EntryPoint = "<lib>_signal"` and never `EntryPoint = "signal"`. Spike note (2026-09-21): a third cell is REQUIRED by spike 2a, two `rollCall()` in two packages asserting `result.compiledClean`, because a cname-only change passes KSP and fails only at the Kotlin compile. For the mingwX64 proof, extend the fixture beyond `signal` with one more silently dropped name (`read` or `open`); do NOT use `atexit`, `main`, `DllMain` or `Sleep`, which fail the link today and would break `packNuget` if the fix regressed. Line 76's cell must set `nuget.rootPackage` (finding 10).

## Deferred scope

- Member-versus-generated-role collisions inside ONE owner (`fun dispose()` versus `cat_dispose`, a method literally named `get_name`, a user function literally named `foo_2` versus ADR-095's suffix): untouched by package qualification; ADR-117 keeps failing them loudly. The `fun dispose` backlog item stays its own item.
- Full JNI-grade escaping of `_` in identifiers.
- Reverse-direction symbols (`nuget_runtime_register` and the registration thunks): fixed names, not user-derived; out of scope.
- Empty-`rootPackage` configuration: namesakes land in one C# namespace (finding 9). Recommend a named diagnostic in this item if it is cheap, else a new backlog line.
- The mingw rule is now known (finding 5, spike 1). Still deferred: confirming the lld `__imp_` lazy-symbol reading against llvm-21, and the ELF interposition behaviour (spike c). The PR should still carry one `objdump -p` check on mingwX64 to prove the fix end to end.
- The `.def` file Kotlin/Native writes next to the DLL lists symbols the DLL does not export (verified by spike 1). Nothing in this repo consumes it (inferred, not grepped); worth a line in the ADR so nobody trusts it as an export oracle. An upstream Kotlin/Native issue is out of scope.
- Un-renaming the workaround fixtures (`Mitten`, `Issue56LoadState`, `rosterRoll`, `collarSignal`).

## Open what-questions

1. WHAT is the leading segment: the library name, a fixed literal, or nothing (package only)? Recommendation: the sanitised `libraryName`. It is the only option that fixes `signal`, keeps clear of `nuget_*`, and survives static linking of two generated libraries. Human decision: pending.
2. Package spelled relative to `rootPackage` or in full? Recommendation: relative (mirrors `mapPackageToNamespace`, keeps `testlib_cat__cat_create` rather than `testlib_io_github_xxfast_..._cat__cat_create`); a package outside root keeps its whole name. Human decision: pending.
3. Always mangle, or only on collision? Recommendation: always. Forward symbols have no cross-release contract (ADR-095 `:122`), and on-collision renaming makes one declaration's symbol depend on another's existence. Human decision: pending.
4. Is changing EVERY forward symbol in one release acceptable? Inferred yes: no consumer spells a symbol, shim and native library ship from one build. It would matter only to someone hand-writing `DllImport`s against a generated library; say so in the release notes. Human decision: pending.
5. Do lines 47 and 48 close together, with line 76 as a separate commit in the same PR? Recommendation: yes; 47 and 48 are one scheme, 76 is a five-line legacy-route spelling fix that touches the same file. If the scheme is NOT scheduled soon, land line 76 alone now and add the interim CRT-name diagnostic for line 48.
6. What should happen when the sanitised library name is `nuget`, or a user declares `object Nuget`? Recommendation: the first is a named ERROR; the second stops mattering once every user symbol starts with `<lib>_`.
7. How (not what, for the implementer): a context parameter on `nativePrefix()` versus a precomputed per-declaration symbol table handed to both planners. Recommendation: the table, built once in `NugetProcessor`, so the three bypass families cannot drift again and the ADR-117 owner index can share it.

## Spikes run (2026-09-21)

| # | Claim spiked | Seam | Result |
|---|---|---|---|
| 1 | A bare `@CName("signal")` is lost on mingwX64, and a prefixed name survives by mechanism | Scratch temp dir, no repo code: `konanc.bat sig.kt -produce dynamic -target mingw_x64 -o probe` (Kotlin/Native 2.4.10), then `objdump -p probe.dll` (name table) and `nm probe.dll`; link line from `-Xverbose-phases=Linker` | **Verified.** `nm`: `T signal`. Export table: `signal`, `abort`, `exit`, `raise`, `read` absent; `lib_signal`, `last_signal`, `sibling` present. Second probe: `qsort`, `strtok`, `open`, `Beep`, `MessageBoxA`, `BCryptGenRandom` absent; `lib_qsort`, `kitten_create`, `sleep` present. `atexit`, `main`, `DllMain`, `Sleep`: `ld.lld: error: duplicate symbol`. Linker is `ld.lld`, auto-export, no `.def` input. **Contradicts pass 1** on the mechanism (not binutils, not "a few CRT symbols", not unknowable): finding 5 rewritten, the diagnostic alternative re-argued. The lld `__imp_` rule itself stays inferred from source |
| 2a | Qualifying only the cname leaves two same-named top-level functions ambiguous in `CNameExports.kt` | Throwaway worktree: `ForwardCallablePlanner.kt:1806` edited to prepend the package; scratch Tier 1 cell, two `rollCall()` in `tier1.ns.a` / `tier1.ns.b`; `./gradlew :nuget-processor:test --tests '*ScratchSpikeTest*'` | **Verified.** KSP `OK`, then `CNameExports.kt:35:3: Overload resolution ambiguity between candidates: fun rollCall(): String / fun rollCall(): String` (twice). Finding 2 stands; the emitter change is required |
| 2b | The primary collision today (two `Kitten` classes, two packages) | Same run, unedited class route | **Verified.** `PROCESSING_ERROR`, three `ERROR_C_ENTRY_POINT_COLLISION` (`kitten_create`, `kitten_dispose`, `kitten_get_name`), no `CNameExports.kt`. Text quoted in finding 4 |
| d | No C# extern NAME derives from the cname | Same `rollCall` cell, reading `Interop.cs`; then a read-only grep of the bypass sites | **Verified for the PLANNED top-level route; CONTRADICTED by reading for the legacy top-level routes.** Planned: with cname `tier1_ns_a__rollCall` the extern stayed `private static extern IntPtr Native_RollCall(out IntPtr error);` and the public method `RollCall()`; the cname appears only inside `EntryPoint = "..."`. Legacy: `cir/CirFunctionTranslator.kt:81` and `:684` are `val csName = toCSharpName(cname.replaceFirstChar { it.uppercase() })`, so the PUBLIC C# method name and the `${csName}_native` extern both derive FROM the cname. The spike's `Make_native` proves nothing here, because the `:1806` edit never reached that route (its cname stayed `make`). See finding 11. NOT checked: class, sealed, Flow, member-suspend routes (inferred safe: they name externs from member names) |
| 4 | Line 76: the outer generic return type renders unqualified | Scratch Tier 1 cell with `nuget.rootPackage` set | **Verified** (render only, no C# compile). Quoted in finding 10 |

All scratch files were deleted and the worktree restored (`git checkout -- .`, `git clean -fd`); nothing from the spikes is in any checkout.

## Spike first (for `kotlin-dev`, still NOT run)

b. (narrowed) After the generator change, on mingwX64: `objdump -p test.dll | grep signal` lists `<lib>_..._signal`, and the xunit `Signal` test passes. The hand-prefixed half is already proven (spike 1); what is left is the end-to-end proof through the real generator and P/Invoke.
c. `nm -D` on a linuxX64 build of a library exporting bare `read` or `abort` TODAY, to learn whether ELF interposition is a live hazard worth a release note. Not runnable on this Windows box. Nobody has verified ELF or Darwin behaviour; if bare names misbehave there in some other way, always-prefix still covers it, a mingw-only diagnostic would not.
e. (new) The lld rule behind the silent drop (`symtab.find("__imp_" + name)` matching lazy archive symbols) is inferred from `llvm-project` `main`, not from the llvm-21 konan ships, and was not proven with `-Wl,--verbose` or a map file. It does not change the recommendation; it matters only to someone who picks the generated-list diagnostic instead.
f. (new) Spike d covered two routes only. When the scheme lands, one Tier 1 assertion that the package-qualified cname string occurs in `Interop.cs` ONLY inside `EntryPoint = "` closes it for every route.
g. (new) Extension imports: finding 2's `addAliasedImport` fix shape for a clashing EXTENSION was not spiked; only the top-level function shape was.
