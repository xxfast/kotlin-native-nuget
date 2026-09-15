# ADR-150: KDoc on an exported Kotlin declaration becomes an XML doc comment on its C# declaration

## Status

Accepted

## Context

ROADMAP Phase 4, first line: a C# consumer sees the Kotlin author's KDoc as `///` XML doc comments
on every generated C# declaration that mirrors a documented exported Kotlin declaration. Forward
direction only. The starting design is `docs/backlog/kdoc-to-csharp-xml-docs.md`; this ADR narrows
it to what the spikes below support and prices it.

Today the only doc comment the bridge emits is [ADR-064](064-forward-unsupported-declaration-diagnostics.md)'s
2026-09-10 amendment: `CirClass.remarks` / `CirSealedSubclass.remarks` (`cir/CirModel.kt:81`,
`:173`), rendered by `renderRemarks` in `cir/CirDocRenderer.kt`, the single XML-escaping sink,
called from `cir/CirClassRenderer.kt:216` and `cir/CirSealedRenderer.kt:105` (verified by reading).
Nothing reads `KSDeclaration.docString` anywhere in `nuget-processor` (verified, zero grep hits).

`GeneratedBindingsCheck/GeneratedBindingsCheck.csproj` compiles the packed shim with
`GenerateDocumentationFile`, `TreatWarningsAsErrors` and `NoWarn CS1591` (verified by reading), so
whatever this ADR emits is compiled as a consumer compiles it, and any XML-doc warning is a red
`scripts/verify.sh`.

### Spike 1: what `docString` really holds on KSP 2.3.10 (verified)

A throwaway `SymbolProcessorProvider` run through the Tier 1 harness's `KotlinSymbolProcessing`
entry point, with a common source root (`expect`) and a `Tier1DependencyLibrary` jar on
`libraries`, printed `docString` for every declaration kind the bridge exports. Real output,
`\n` shown literally:

```
KSClassDeclarationImpl spike.Foo origin=KOTLIN doc=<<\n Summary line of Foo.\n\n Second paragraph with `code` and [Bar] link and <angle> & amp.\n\n @property name the name prop\n @constructor builds a Foo\n>>
  KSPropertyDeclarationImpl spike.Foo.name origin=KOTLIN doc=null
  KSFunctionDeclarationAAImpl spike.Foo.doThing origin=KOTLIN doc=<<\n Does a thing.\n @param count how many\n @param label   a label\n @return the result\n @throws IllegalArgumentException when count is negative\n>>
  KSPropertyDeclarationImpl spike.Foo.size origin=KOTLIN doc=<<Prop doc. >>
  KSFunctionDeclarationAAImpl spike.Foo.hidden origin=KOTLIN doc=<<@suppress >>
  KSFunctionDeclarationAAImpl null origin=KOTLIN doc=null                  (declared primary ctor)
  KSFunctionDeclarationAAImpl null origin=KOTLIN doc=<<Secondary ctor doc. >>
KSFunctionDeclarationAAImpl spike.ext origin=KOTLIN doc=<<Ext doc. @receiver the foo >>
KSClassDeclarationImpl spike.Bar origin=KOTLIN doc=<<Iface doc. >>
  KSFunctionDeclarationAAImpl spike.Bar.bar origin=KOTLIN doc=<<Iface member doc. >>
KSClassDeclarationImpl spike.Mood origin=KOTLIN doc=<<Enum doc. >>
  KSFunctionDeclarationAAImpl null origin=SYNTHETIC doc=<<Enum doc. >>
  KSClassDeclarationEnumEntryImpl spike.Mood.HAPPY origin=KOTLIN doc=<<Happy doc. >>
  KSClassDeclarationEnumEntryImpl spike.Mood.SAD origin=KOTLIN doc=null
  KSFunctionDeclarationAAImpl spike.Mood.values origin=SYNTHETIC doc=<<Enum doc. >>
  KSFunctionDeclarationAAImpl spike.Mood.valueOf origin=SYNTHETIC doc=<<Enum doc. >>
  KSPropertyDeclarationImpl spike.Mood.entries origin=SYNTHETIC doc=<<Enum doc. >>
KSClassDeclarationImpl spike.Shape origin=KOTLIN doc=<<Sealed doc. >>
  KSClassDeclarationImpl spike.Shape.Circle origin=KOTLIN doc=<<Arm doc. >>
  KSClassDeclarationImpl spike.Shape.Empty origin=KOTLIN doc=<<Obj arm doc. >>
    KSFunctionDeclarationAAImpl null origin=SYNTHETIC doc=<<Obj arm doc. >>
KSClassDeclarationImpl spike.Meters origin=KOTLIN doc=<<Value class doc. >>
KSClassDeclarationImpl spike.Registry origin=KOTLIN doc=<<Object doc. >>
  KSFunctionDeclarationAAImpl spike.Registry.get origin=KOTLIN doc=<<Obj fn doc. >>
KSClassDeclarationImpl spike.Widget origin=KOTLIN doc=null                 (actual, in Fixture.kt)
  KSFunctionDeclarationAAImpl spike.Widget.spin origin=KOTLIN doc=null
FILE WidgetCommon.kt
KSClassDeclarationImpl spike.Widget origin=KOTLIN doc=<<Expect widget doc. >>
  KSFunctionDeclarationAAImpl spike.Widget.spin origin=KOTLIN doc=<<Expect spin doc. >>
DEP:
KSClassDeclarationImpl dep.doc.DepThing origin=KOTLIN_LIB doc=null        (had /** Dependency class doc. */)
  KSFunctionDeclarationAAImpl dep.doc.DepThing.poke origin=KOTLIN_LIB doc=null
```

What this settles, all **verified**:

1. `docString` strips `/**`, `*/` and each line's ` * ` prefix, and nothing else. A multi-line
   comment starts with `\n` and every line keeps one leading space; a one-line comment keeps one
   trailing space. Tags (`@param`, `@return`, `@throws`, `@property`, `@constructor`, `@receiver`,
   `@suppress`) stay inline. Markdown (`` `code` ``, `[Bar]`) and raw `<angle> & amp` pass through
   unescaped. The processor parses tags itself.
2. Every kind the bridge exports carries its own KDoc: class, interface and interface member,
   method, property, secondary constructor, extension function, top-level function, enum entry,
   sealed arm (class and object), value class, object and object member.
3. **A `SYNTHETIC`-origin member inherits its owner's `docString`.** Enum `values`/`valueOf`/
   `entries` and the implicit constructor of an object, sealed base or enum all report the class's
   comment. A declared primary constructor reports `null`. The bridge must read `docString` only
   off `Origin.KOTLIN` declarations, or every enum's `Entries` helper and every object's handle
   constructor would carry the type's summary.
4. `@property`/`@constructor` text lives on the **class** `docString`; the constructor property
   (`Foo.name`) and the declared primary constructor both report `null`.
5. The `actual` reports `null` everywhere and the `expect` (common source root) carries the text,
   on the class and on its members. Re-confirms ADR-074 point 4.
6. A `KOTLIN_LIB` declaration reports `null` even when its source had KDoc. Verified for a JVM jar
   dependency (the Tier 1 stand-in for ADR-066's closure); **inferred** for a real Kotlin/Native
   klib: Kotlin/Native embeds KDoc in a klib only under `-Xexport-kdoc`, and nothing in the KSP
   Analysis API implementation is known to surface it. Treated as "dependency types render
   undocumented" either way.

### Spike 2: which XML-doc warnings are fatal under `GeneratedBindingsCheck`'s property set (verified)

A scratch `net8.0` classlib with the exact csproj property block of `GeneratedBindingsCheck`
(`GenerateDocumentationFile`, `TreatWarningsAsErrors`, `NoWarn CS1591`, `Nullable enable`,
`LangVersion 12.0`). Real `dotnet build` output, trimmed:

```
A.cs(10,40): error CS1573: Parameter 'b' has no matching param tag in the XML comment for 'Foo.Partial(int, int)' (but other parameters do)
A.cs(18,26): error CS1572: XML comment has a param tag for 'zzz', but there is no parameter by that name
A.cs(19,35): error CS1573: Parameter 'a' has no matching param tag in the XML comment for 'Foo.WrongName(int)' (but other parameters do)
A.cs(22,30): error CS1574: XML comment has cref attribute 'NoSuchType' that could not be resolved
A.cs(42,20): error CS1571: XML comment has a duplicate param tag for 'a'
```

and, from the first run before the malformed cases were removed:

```
A.cs(38,65): error CS1570: XML comment has badly formed XML -- 'End tag 'summary' does not match the start tag 'init'.'
A.cs(41,37): error CS1570: XML comment has badly formed XML -- 'Whitespace is not allowed at this location.'   (a bare `&`)
```

What did **not** warn, same run: `<param name="b"></param>` (empty tag) beside a documented `a`;
`<returns>` on a `void` method; `<exception cref="KotlinArgumentException">` and
`<exception cref="global::Lib.KotlinArgumentException">` against a type in the same namespace;
`<seealso cref="Foo"/>`; a `<summary>` on an enum member, on a `readonly record struct`, on a
property; a `///` line with no tags at all. CS1570 is reported first and masks the CS157x family
(the doc-comment pass runs only on an otherwise error-free compilation), which is why the spike ran
twice.

Consequences for the mapping, all load-bearing:

- A partial `<param>` set is fatal (CS1573). Either every parameter of the C# member gets a tag or
  none does.
- A `<param>` naming a parameter the C# member does not have is fatal (CS1572). An omitting
  overload (ADR-091/096) must drop the tags of the parameters it omits; an extension's `@receiver`
  and a `@param` that matches nothing are dropped.
- An unresolvable `cref` is fatal (CS1574). Every `cref` this ADR emits is a spelling the bridge
  itself generates in the same namespace (the `CirErrorRenderer` exception types); nothing else.
- Raw `&`, `<`, `>` are fatal (CS1570). Every text fragment goes through the one escaper.

## Alternatives Considered

### 1. Structured `CirDoc` on the CIR, filled by the planner, rendered by `CirDocRenderer` (chosen)

A `CirDoc(summary, params, returns, throws)` value on every CIR node that mirrors a Kotlin
declaration, produced once per declaration by a small KDoc parser (`forward/ForwardKdoc.kt`),
carried through `ForwardPublicSignature` for planned callables (the planner is the only place that
holds both the `KSFunctionDeclaration` and the omitted-parameter count), and rendered by one new
sibling of `renderRemarks`. Pros: one parser, one escaper, one renderer; the omitting overload and
the `Async` projection get the doc for free off the plan; hand-built CIR fixtures keep their shape
because every new field defaults to `null`. Cons: a field on roughly eleven CIR data classes and a
render call in roughly ten renderer functions.

### 2. Pass the raw `docString` down and render it as one `<summary>` blob

Smallest diff. Cons: `@param`/`@return`/`@throws` lines would print inside `<summary>` as prose;
no `<param>` means IntelliSense never shows parameter docs; and any `@param` naming a Kotlin
parameter is misleading on an omitting overload. Rejected: does not satisfy the restatement's
`<param>`/`<returns>`/`<exception>`.

### 3. Render KDoc-to-XML in the translator (`CirClassTranslator`) with string concatenation

Rejected: ADR-064 made `CirDocRenderer` the single escaping sink for the reason stated there
(`<init>` in a remark was malformed XML); a second sink reintroduces the class of bug
`GeneratedBindingsCheck` exists to catch.

### 4. Full table from the backlog file (remarks, links to `cref`, `@property`, `@constructor`, `@see`)

Deferred, not rejected. Each additional tag needs its own resolution rule (a `cref` that resolves,
verified fatal otherwise) or a lookup the model does not carry yet (`@property` text is on the
class, the property's own `docString` is `null`). None of them changes the mechanism chosen here;
they are additive fields on `CirDoc`.

## Decision

### Consumer-facing shape

```kotlin
/**
 * Books a stay for a cat.
 *
 * Longer explanation, dropped in v1.
 *
 * @param nights how many nights
 * @param suite  which suite, defaults to the cheapest
 * @return the booking reference
 * @throws IllegalArgumentException when nights is not positive
 */
fun book(nights: Int, suite: String = "budget"): String
```

```csharp
/// <summary>Books a stay for a cat.</summary>
/// <param name="nights">how many nights</param>
/// <param name="suite">which suite, defaults to the cheapest</param>
/// <returns>the booking reference</returns>
/// <exception cref="KotlinArgumentException">when nights is not positive</exception>
public string Book(int nights, string suite)

/// <summary>Books a stay for a cat.</summary>
/// <param name="nights">how many nights</param>
/// <returns>the booking reference</returns>
/// <exception cref="KotlinArgumentException">when nights is not positive</exception>
public string Book(int nights)
```

### Tag mapping, v1

| KDoc                                | C# XML doc                                                                                                                     |
|-------------------------------------|--------------------------------------------------------------------------------------------------------------------------------|
| body, first paragraph               | `<summary>`; lines trimmed and joined with a space; `` `x` ``, `[x]` and every other inline markup stay as escaped plain text in v1 |
| `@param name text`                  | `<param name="name">text</param>`, only for a name that is a parameter of the C# member being rendered; else dropped             |
| `@return text`                      | `<returns>text</returns>`, omitted on a `void` member; same text on the `Async` projection                                       |
| `@throws T text` / `@exception T`   | `<exception cref="K">text</exception>`, `K` from the `BuildMapped` table below; unmapped `T` crefs `KotlinException` and prefixes the text with a plain `T: ` (escaped text, no `<c>`) |
| `@suppress` anywhere in the comment | no doc comment on that declaration                                                                                              |
| everything else                     | dropped in v1 (later paragraphs, `@property`, `@constructor`, `@see`, `@sample`, `@since`, `@author`, `@receiver`, `@sample`)  |

**Partial `@param` sets.** When at least one `@param` matches a parameter of the C# member, every
parameter of that member gets a tag; the undocumented ones get `<param name="x"></param>`. Verified
in spike 2 that an empty tag silences CS1573. When no `@param` matches, no `<param>` tag is emitted
at all (also warning-free, since CS1573 only fires "but other parameters do").

**`@param` matching.** By the Kotlin parameter's declared name against
`ForwardPublicParameter.name` (`forward/ForwardMarshallingModel.kt:452-466`), emitted with the
`CirParameter.name` the renderer prints. **Inferred**: `bridgeName()` may respell a C#-reserved
name; the match must happen on the Kotlin side of that rename, before it. An implementing agent
checks `bridgeName()` and, if it renames, matches on the original.

**`@throws` resolution.** `CirErrorRenderer.kt:59-80`'s `BuildMapped` `switch` (verified by
reading) is the table: `kotlin.IllegalArgumentException` → `KotlinArgumentException`;
`kotlin.IllegalStateException`, `kotlin.NoSuchElementException`,
`kotlin.ConcurrentModificationException` → `KotlinInvalidOperationException`;
`kotlin.UnsupportedOperationException` → `KotlinNotSupportedException`;
`kotlin.ClassCastException` → `KotlinInvalidCastException`; `kotlin.ArithmeticException` →
`KotlinArithmeticException`; `kotlin.NumberFormatException` → `KotlinFormatException`; default
`KotlinException`. KDoc spells the type as written by the author (`IllegalArgumentException`,
rarely `kotlin.IllegalArgumentException`), so the match is on the simple name with the `kotlin.`
prefix stripped. The table is lifted into a Kotlin `Map` shared by both callers rather than
duplicated. **Verified** (spike 2) that a simple-name `cref` to a type in the same namespace resolves;
**inferred** that every generated exception type lives in the same C# namespace as every generated
member. If a nested or cross-namespace member can see a different namespace, spell the cref
`global::<libraryNamespace>.KotlinArgumentException`, which spike 2 also verified resolves.

**Merge with ADR-064's `<remarks>`.** Order: `<summary>`, `<remarks>` (ADR-064's text, unchanged),
`<param>`, `<returns>`, `<exception>`. The compiler does not check tag order; this is the order
`docs.microsoft.com` samples use. No author `<remarks>` in v1, so no merge inside `<remarks>`.

**Fixture naming.** A `test-library` fixture class name must not collide with any other exported
class in the module: an entry point derives from the *simple* name, not the package, so a second
`Cattery` in a new package is an `ERROR_C_ENTRY_POINT_COLLISION` against `test.issue113.Cattery`.

**`@suppress`.** Opts the declaration out, nothing inherited. Verified the marker arrives as
`@suppress ` in `docString`; Dokka's semantics, one `contains` check.

**Origin gate.** Only an `Origin.KOTLIN` declaration's `docString` is read (spike 1 finding 3).

**`expect`/`actual`.** `ExpectIndex` (`processor/ExpectIndex.kt`) is built from
`resolver.getAllFiles().flatMap { it.declarations }` (`NugetProcessor.kt:639-641`, verified), so
it holds **top-level** declarations only; `classOrNull` and `functionOrNull` cover the `expect`
class and a top-level `expect fun`. A member of an `expect class` is not indexed. New
`ExpectIndex.docOrNull(declaration: KSDeclaration): String?`: for an `actual` class or top-level
function, the paired expect's `docString`; for a member, `classOrNull(parent qualified name)`
then a walk of its `declarations` matched by simple name and the existing `matches` rule (**inferred**
that `matches` compares an `expect` member and its `actual` member equal; it is written for the
top-level case). Resolution: the `actual`'s own `docString` when non-null (always null today),
else the expect's, else none.

**Dependency declarations.** Render undocumented. Verified for a jar, inferred for a klib (spike 1
finding 6). Stated as a limit in the topic page.

### Model and plumbing

- `cir/CirModel.kt`: new `data class CirDoc(val summary: String?, val params: List<CirDocParam>,
  val returns: String?, val throws: List<CirDocThrows>)` with `CirDocParam(name, text)` and
  `CirDocThrows(cref, text)`. New `val doc: CirDoc? = null` on `CirClass`, `CirSealedSubclass`,
  `CirSealedClass`, `CirObject`, `CirValueClass`, `CirEnum`, `CirEnumEntry`, `CirInterface`,
  `CirInterfaceMethod`, `CirInterfaceProperty`, `CirMethod`, `CirProperty` and `CirConstructor`.
  Text, never markup. All thirteen are wired: every generated C# declaration that mirrors a
  documented exported Kotlin declaration carries its doc.
- `forward/ForwardKdoc.kt` (new): `parseKdoc(docString: String): ForwardKdoc?` returning
  `summary`, `params: Map<String, String>`, `returns`, `throws: List<Pair<String, String>>`,
  `suppressed`. Pure function, unit-tested against spike 1's real shapes (leading `\n`, per-line
  leading space, trailing space, tags inline, multi-line tag continuation joined with a space).
- `forward/ForwardMarshallingModel.kt`: `ForwardPublicSignature.doc: ForwardKdoc? = null` (:452).
  Set in `ForwardCallablePlanner.kt`'s `planOrSkip` callers (`entryFor` at :1044 and the top-level,
  extension, constructor, sealed and value-class equivalents) from the declaration's KDoc, with the
  omitted trailing parameters' `@param` entries removed for a synthesized overload (:1104-1105
  passes `omitted`).
- `forward/ForwardCirPlanProjection.kt` (`classMethod` :108, `constructor` :148/:179, `static`
  :188, `valueClassMethod` :78, `valueClassProperty` :57) and
  `forward/ForwardCirPropertyProjection.kt` (:68-101): project `plan.publicSignature.doc` to
  `CirDoc`, resolving `@throws` through the shared table, filtering `@param` to
  `plan.publicSignature.parameters`. `<returns>` dropped when `result` is `Unit`.
- `cir/CirClassTranslator.kt`: class-level docs (`translateClass` :1022 already sets `remarks`;
  sealed arm :2021), enum and entry docs, object and value-class docs, interface member docs, and
  the legacy (non-plan) property route, each off the declaration's `docString` with the origin gate
  and the `ExpectIndex` fallback.
- `cir/CirDocRenderer.kt`: `renderDoc(doc: CirDoc?, indent)` beside `renderRemarks`, printing the
  five tags in the order above through the existing `xmlEscaped()`. No `<c>` rewrite in v1: a
  backtick span or `[link]` stays escaped plain text, per the narrower human decision recorded
  below.
- Render call sites: `CirClassRenderer.kt` `renderClass` (beside `renderRemarks`), `renderMethod`
  (first line, before the async/flow/sync-error dispatch so all four branches share it),
  `renderConstructor`, `renderProperty` (above the abstract early return), `renderInterface` and
  each of its properties and methods; `renderStaticClass`'s members reach `renderMethod`, which is
  what documents a top-level function, an extension and an object member. `CirEnumRenderer.kt` on
  the enum and per entry; `CirObjectRenderer.kt` on the object, the value-class struct and each of
  its properties and methods; `CirSealedRenderer.kt` on the base and on each arm.

**Where the doc comes from, per family.** Planned callables (class methods, sealed-arm and
sealed-base methods, constructors incl. secondary, top-level functions, extensions, object and
companion members, value-class members, interface members, `copy`) carry it on
`ForwardPublicSignature.doc`, set at every `planOrSkip` call site from the declaration's KDoc with
the omitted `@param`s already dropped. Properties carry it on `ForwardPropertyPlan.doc`; ADR-075's
getter/setter pair is one C# property and gets one block. Types (class, object, value class,
interface, enum, enum entry, sealed base, sealed arm) read it in `CirClassTranslator` with the
origin gate. The `Async` projection (ADR-023) reads the suspend declaration's own KDoc, and its
`<param>` set includes the generator's trailing `cancellationToken`, which is a C# parameter like
any other and is a fatal CS1573 without a tag once a declared parameter has one.

### Verification

- `ForwardKdocTest` (unit): the parser against spike 1's real `docString` shapes.
- `CirDocRendererTest` (unit): escaping, empty `<param>`, null doc renders nothing.
- `Tier1KdocXmlDocTest` (structural): a fixture with a documented class, a method with
  `@param`/`@return`/`@throws` and a defaulted trailing parameter (asserting the omitting overload
  drops that `<param>`), a `@suppress`ed member (asserting no `///`), an enum (asserting the
  `Entries` helper and handle constructor carry nothing), and an `expect` with KDoc over a bare
  `actual` (asserting the C# carries the expect's text). Assertions on the rendered `///` lines.
- `scripts/verify.sh`: `GeneratedBindingsCheck` compiles the fixture's real KDoc; one new step
  asserts `GeneratedBindingsCheck/obj/<Configuration>/net8.0/GeneratedBindingsCheck.xml` contains
  an expected `<member name="M:...">` entry. Verified (spike 2) that the documentation file lands
  at `obj/Debug/net8.0/<AssemblyName>.xml`.
- No `LeakTests` row: this route mints no handle and changes no ABI.

## Consequences

- Every documented exported declaration gains a `///` block in `Interop.cs`, in every family the
  bridge generates. No ABI, export, contract-hash or handle change.
- A Kotlin `object`'s property has no C# surface at all today (`CirObject` carries methods only),
  so there is no declaration for its KDoc to attach to. Pre-existing gap, named here because the
  Tier 1 object cell asserts the absence rather than leaving it unexplained.
- `test-library` gains KDoc on a handful of members the integration tests already touch.
- A future `<remarks>` from later paragraphs, `<see cref>` from `[links]` to exported types,
  `@property` → property summary and `@constructor` → primary constructor summary are additive
  `CirDoc` fields plus a resolution rule each; none reopens this mechanism.
- Dependency (klib) declarations stay undocumented; the topic page says so.
- **Every** KDoc in an exported module now reaches the consumer, including prose written for the
  repository's own readers. Two guards see it, and both were adjusted rather than worked around:
  Issue #223's `$`-in-an-identifier check now skips `//` lines (a `$` in a comment is legal C# and
  a KDoc quoting a Kotlin template is prose, not an identifier), and one `test-library` note about
  the synthesized `$serializer` became a `//` comment, because
  `scripts/verify-forward-diagnostics.sh` asserts the word never appears in a generated
  `Interop.cs`.
- Reverse (NuGet `lib/<tfm>/*.xml` → KDoc) is its own item, unchanged.

### Inferred claims, listed

1. A real Kotlin/Native klib dependency reports `docString == null` through KSP (verified only for
   a JVM jar). If wrong, dependency types would gain docs, which is harmless.
2. `ExpectIndex`'s `matches` compares an `expect` class member and its `actual` member as equal.
   If wrong, member docs on multiplatform classes silently go missing; the Tier 1 cell above pins
   it.
3. `bridgeName()` does not rename a Kotlin parameter in a way that breaks `@param` matching. If
   wrong, that parameter's tag is silently dropped (never a CS1572, since the match is by C# name at
   render time).
4. Every generated exception type is visible by simple name from every generated member. If wrong,
   CS1574 fails `GeneratedBindingsCheck` loudly, not silently; the `global::` spelling is the fix.
