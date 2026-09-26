# ADR-166: Forward, a `const val`'s C# value is the compiler's evaluated constant

## Status

Accepted, 2026-09-26.

## Context

A `const val`'s C# value used to come from `extractConstValue`
(`nuget-processor/.../cir/CirTranslator.kt`), a regex over the declaring source line
(`const\s+val\s+NAME\s*(?::\s*\S+)?\s*=\s*(.+)`), then `kotlinLiteralToCSharp`, a second pass over
the matched text. Both are gone.

The regex captured to the end of the *line*, not the end of the Kotlin expression, so a one-line
`object`/companion body (`object Jar { const val NAME = "x" } }`) or a `;`-separated pair on one
line captured the rest of the line, including a trailing `}` or the next declaration, as part of
the value: illegal C# (`"x" } };`). A trailing `//` comment landed the generated `;` inside the
comment. Independently of the one-line shape, the text-based route was wrong on ordinary,
one-per-line consts too: it stripped every `_` from a value including a string
(`"snake_case_value"` rendered `"snakecasevalue"`), rendered a string template verbatim
(`"v$UNDERSCORE"` stayed `"v$UNDERSCORE"` instead of the Kotlin value `"vsnake_case_value"`),
carried an unescaped `\$` straight through (`CS1009`), rendered an expression over another const as
its source text (`TRAILING + 1` referenced a C# name that does not exist, `CS0103`), rendered `1
shl 3` and `Int.MIN_VALUE` as source text rather than `8` and `-2147483648`, and a `255u` literal
on a `UByte` property rendered the `UInt`-suffixed source text, `CS0266`. A `const val` in a
separately compiled dependency has no `containingFile`, so the regex route returned `null` and
every caller silently `mapNotNull`-dropped it: no diagnostic, no C# member at all.

Fixing the capture group could not fix all of this: an expression, a template, and a shift are
never going to be right by scanning source text, because the value only exists after the compiler
evaluates the expression.

KSP's public API exposes no constant value; `KSPropertyDeclaration` has nothing named initializer
or constant. KSP2's own implementation class holds the Analysis API symbol the compiler already
evaluated (`KSPropertyDeclarationImpl`'s internal `ktPropertySymbol: KaPropertySymbol`), and KSP
itself reads `ktPropertySymbol.initializer is KaConstantInitializerValue` internally to answer its
own `hasConstantValue`-adjacent questions. That evaluated value is reachable from a processor by
reflection.

## Decision

**Read the compiler's evaluated constant by reflection, and render it from the value and the
declared type; delete the source-text route with no fallback.**

`cir/KotlinConstValue.kt` chains four zero-argument reflective calls off the KSP2 property
declaration: a method matched by the *prefix* `getKtPropertySymbol` (its real name is
module-mangled, e.g. `getKtPropertySymbol$kotlin_analysis_api`) → `getInitializer()` →
`getConstant()` → `getValue()`. Every step is matched by method **name**, never by type: the
Analysis API classes are shaded (`ksp.org.jetbrains...`) and non-public, and in the real Gradle KSP
worker the boxed `kotlin.UInt`/`kotlin.ULong` value may come from a different classloader than the
processor's own, so an `is ULong` check could silently miss a value it should have accepted.
Integers are therefore read through `toString()` (an unsigned box prints its unsigned decimal, e.g.
`ULong.MAX_VALUE` prints `18446744073709551615`, not `-1`) and reparsed against the property's
**declared** Kotlin type, not the runtime box's type: an unsuffixed `const val X: Long = 5` and
`4_000_000_000u` declared as `UInt` both render by their declared type.

The C# literal comes from the value plus the declared type: proper string/char escaping for a
regular (never raw) C# literal, `L`/`U`/`UL` suffixes, no suffix for `Byte`/`Short`/`UByte`/`UShort`
(C# converts an in-range constant), the literal-negation special case for `Int.MIN_VALUE` /
`Long.MIN_VALUE`, and round-trip `float`/`double` formatting including `float.NaN` /
`double.PositiveInfinity` (themselves C# constants, not literals).

**No source-text fallback.** When any reflective step fails (a KSP release renames or removes the
internal accessor, the initializer is not a constant, or the constant carries no value), the const
is skipped with a named, loud diagnostic, `SKIPPED_UNREADABLE_CONST_VALUE`, naming the declaration
and the reason. A source-text fallback was rejected deliberately: it would keep every
silently-wrong case above alive again exactly when nobody is looking, on a future KSP bump. A
`Tier1ConstValueTest` cell asserts the internal accessor is still reachable on the pinned KSP
version, so a KSP bump that removes it fails a Tier 1 test loudly, rather than surfacing as every
`const val` in a consumer's library quietly losing its value.

**Risk, accepted by the human 2026-09-26 (Step 2 gate).** This is a reflective dependency on a
KSP2-internal, module-mangled member with no public contract. The mitigation is the probe cell
above plus the named skip; there is deliberately no attempt to make the reflection resilient beyond
matching by name prefix and reparsing by declared type.

## Alternatives Considered

### 1. A bounded literal lexer instead of the greedy regex

Scan one literal from `=`, respecting string/char literals and comments, stopping at a `;` or a
`}` at brace depth zero instead of end-of-line. This fixes the one-line-body and `;`-pair breakage
and the raw-string/`\$`/underscore-in-string cases, but it is still a lexer over source text: it
can only *skip* (never evaluate) an expression over another const, a shift, or a template, and it
still drops a dependency const with no `containingFile`. Rejected: a narrower patch on a route that
still cannot reach the end state for expressions and templates.

### 2. Make the capture group non-greedy, or stop at `}` / `;` / `//`

Breaks legal, already-working values that contain those characters (`"a } b"`, `"http://x"`
containing `//`) and fixes nothing in the expression/template/escaping class of bug. Rejected.

### 3. Keep the regex as a fallback behind the evaluated route

Keeps every silently-wrong case reachable again the moment the reflective route stops working on a
KSP bump, which is exactly the failure mode a loud named skip is meant to surface instead. Rejected.

### 4. Emit a runtime getter instead of a C# `const`

Loses `const` semantics a `const val` is documented to carry: a `const` field can be used as a
switch label or an attribute argument, a plain property cannot. Rejected.

### 5. Read `compileTimeValue` from klib metadata in the Gradle plugin

Correct in principle and does not depend on a KSP internal, but the klib does not exist yet at KSP
time (KSP runs before compilation), so this would need a second pass in a different subsystem after
compilation. Rejected as disproportionate unless the KSP2 reflective route proves unstable across
releases in practice.

## Consequences

- Kotlin's own hex/underscore/binary literal presentation is not preserved: `const val MASK: Int =
  0xFF_FF` renders `public const int Mask = 65535;`, not `0xFF_FF`. This is the evaluated decimal
  value, which is ordinary and idiomatic for a C# `const`, not a defect.
- An expression over another const, a sibling const in the same or a different owner, or an infix
  shift renders as the **evaluated literal**, never as the C# spelling of the source expression:
  `const val REF: Int = TRAILING + 1` renders `public const int Ref = 6;`. A C# consumer sees the
  same value Kotlin itself inlines at every call site; there is no `Ref` that stays in sync with a
  changed `Trailing` at the C# layer, exactly as there is none at the Kotlin layer once compiled.
- A raw multi-line string's line break renders as the `\n` escape in a regular C# literal
  regardless of whether the source file's own line ending is LF or CRLF, because the Kotlin front
  end normalizes a raw string's line endings before evaluating it.
- A `const val` declared in a companion object of an admitted dependency class now binds instead of
  being silently dropped, proven against a real JVM dependency jar; the same route is inferred, not
  yet proven, for a Kotlin/Native klib dependency, since KSP evaluates a `KOTLIN_LIB`-origin
  constant through the identical `KaConstantInitializerValue` branch.
- `extractConstValue`, `declarationLine`, `offsetOfLine`, and `kotlinLiteralToCSharp` are deleted
  with no replacement of their text-scanning behaviour; every value now flows through
  `KotlinConstValue`.
