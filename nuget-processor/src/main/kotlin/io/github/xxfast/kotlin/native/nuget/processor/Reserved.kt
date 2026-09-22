package io.github.xxfast.kotlin.native.nuget.processor

val C_RESERVED = setOf(
  "auto", "break", "case", "char", "const", "continue", "default", "do",
  "double", "else", "enum", "extern", "float", "for", "goto", "if",
  "int", "long", "register", "return", "short", "signed", "sizeof",
  "static", "struct", "switch", "typedef", "union", "unsigned", "void",
  "volatile", "while",
)

val CSHARP_RESERVED = setOf(
  "abstract", "as", "base", "bool", "break", "byte", "case", "catch",
  "char", "checked", "class", "const", "continue", "decimal", "default",
  "delegate", "do", "double", "else", "enum", "event", "explicit",
  "extern", "false", "finally", "fixed", "float", "for", "foreach",
  "goto", "if", "implicit", "in", "int", "interface", "internal", "is",
  "lock", "long", "namespace", "new", "null", "object", "operator",
  "out", "override", "params", "private", "protected", "public",
  "readonly", "ref", "return", "sbyte", "sealed", "short", "sizeof",
  "stackalloc", "static", "string", "struct", "switch", "this", "throw",
  "true", "try", "typeof", "uint", "ulong", "unchecked", "unsafe",
  "ushort", "using", "virtual", "void", "volatile", "while",
)

fun toCName(name: String): String {
  if (name in C_RESERVED) return "${name}_"
  return name
}

/** Every run of characters a C symbol segment cannot contain, once the name is lowercased. */
private val NON_C_IDENTIFIER = Regex("[^a-z0-9_]+")

/**
 * ADR-127's reserved leading segment. A library whose sanitised name is this would mint symbols in
 * the `nuget-runtime` ABI's own space, so [sanitizeLibrarySegment]'s caller fails the build.
 */
internal const val RESERVED_LIBRARY_SEGMENT: String = "nuget"

/**
 * ADR-163: the leading segment of every forward C entry point, derived from the `nuget.libraryName`
 * the `DllImport` already names.
 *
 * Lowercased, then every run of characters outside `[a-z0-9_]` collapsed to a single `_`, so
 * `Test-Library.Native` becomes `test_library_native`. A leading digit takes a `_` prefix (a C
 * symbol may not start with one; the alternative, leaving it to the linker, hides the defect in a
 * toolchain error message). An empty or all-punctuation name would leave symbols bare, which is the
 * `signal` hazard this scheme exists to close, so it falls back to `_`.
 */
internal fun sanitizeLibrarySegment(libraryName: String): String {
  val collapsed: String = libraryName.lowercase().replace(NON_C_IDENTIFIER, "_")
  val trimmed: String = collapsed.ifEmpty { "_" }
  return if (trimmed.first().isDigit()) "_$trimmed" else trimmed
}

fun toCSharpName(cname: String): String {
  if (cname.trimEnd('_') in CSHARP_RESERVED) return "@$cname"
  return cname
}

/** Every run of characters a C# identifier cannot contain: `.`, `-`, space, and the rest. */
private val NON_CSHARP_IDENTIFIER = Regex("[^A-Za-z0-9_]+")

/**
 * The single place a *file-derived* name becomes a C# identifier (issue #233).
 *
 * ADR-007 names a file's static holder class after the file stem, and a Kotlin file stem is not a
 * C# identifier: `Hub.mingw.kt` emitted `public static partial class Hub.mingw`, which C# reads as
 * a qualified name, so one dot produced 19 errors that cascaded to the end of `Interop.cs`.
 * Platform-suffixed files (`Foo.ios.kt`, `Foo.mingw.kt`) are the standard KMP layout for `actual`
 * declarations, so this is the normal case, not an exotic one.
 *
 * The rule is structural, never a list of known suffixes: split on every run of characters outside
 * `[A-Za-z0-9_]`, keep the first segment verbatim, uppercase each following segment's first
 * character, join. `Hub.mingw` -> `HubMingw`, `Net.io.core` -> `NetIoCore`, `foo-bar` -> `fooBar`.
 * A leading digit takes a `_` prefix, and the result is passed through [toCSharpName] so a stem
 * that spells a C# keyword becomes a verbatim identifier. An already-legal name is a fixed point,
 * so no existing generated name moves.
 *
 * PascalCase segments rather than `_` separators: `Hub_mingw` is Kotlin's own JVM facade rule, and
 * it was considered, but ADR-110 already made PascalCase the forward spelling of every C# name, so
 * the file-derived one matches its neighbours. Stripping everything after the first dot was also
 * considered and rejected: `Hub.kt` commonly sits beside `Hub.mingw.kt`, and silently merging two
 * files into one class trades a loud parse error for a quiet one.
 */
internal fun String.csharpIdentifier(): String {
  val segments: List<String> = split(NON_CSHARP_IDENTIFIER).filter { it.isNotEmpty() }
  val joined: String = if (segments.isEmpty()) {
    "_"
  } else {
    segments.first() +
      segments.drop(1).joinToString("") { it.replaceFirstChar { char -> char.uppercase() } }
  }
  return toCSharpName(if (joined.first().isDigit()) "_$joined" else joined)
}

/**
 * The C# spelling of a Kotlin *constant-shaped* name: an `enum class` entry and a `const val`
 * (issue #285, dated amendment to ADR-006).
 *
 * ADR-006 wrote one line for entry names, "`SCREAMING_SNAKE_CASE` to `PascalCase`", and the
 * expression that implemented it lowercased every `_` segment whole before uppercasing its first
 * character. That is correct for the shape the ADR considered and wrong for every other one Kotlin
 * admits: a PascalCase entry `SecondValue` reached C# as `Secondvalue`, `camelCase` as `Camelcase`,
 * and `XMLParser_V2` as `XmlparserV2`, so a consumer could not predict the member name from the
 * Kotlin declaration without compiling to read the CS0117 or opening `Interop.cs`.
 *
 * The rule is per SEGMENT, which is the only part that matters and the reason a whole-name gate was
 * rejected: split on `_` and drop empty segments; a segment that contains at least one lowercase
 * letter keeps its internal casing and only gets a capital first character; a segment with no
 * lowercase letter (all caps, digits) lowercases first, exactly as before; join. So `XMLParser_V2`
 * keeps the acronym AND joins across the `_`, while `HAPPY_CAT` still answers `HappyCat` and `AB1C`
 * still answers `Ab1c`. `AB1C` is deliberate, not an oversight: it is indistinguishable from
 * `HAPPY` to `Happy`, so preserving it would reverse ADR-006 and move every published enum.
 *
 * Two guards close shapes Kotlin accepts and C# does not (both were emitted raw before, as CS1001
 * inside `Interop.cs` itself): a converted name that is empty (`_`, `__`) becomes `_`, and one that
 * starts with a digit (`_1ST` to `1st`) takes a `_` prefix.
 *
 * No [toCSharpName] pass and none needed: the result's first character is always an uppercase
 * letter or `_`, and every C# keyword is all lowercase, so a converted name can never spell one.
 *
 * The ABI does not see this name. An entry crosses as its ordinal `int` in every position
 * (ADR-006), and a `const` is a compile-time literal, so this is a C#-surface spelling only.
 */
internal fun String.kotlinConstantToPascalCase(): String {
  val converted: String = split("_")
    .filter { segment -> segment.isNotEmpty() }
    .joinToString("") { segment ->
      val cased: String =
        if (segment.any { character -> character.isLowerCase() }) segment else segment.lowercase()
      cased.replaceFirstChar { character -> character.uppercase() }
    }
  return if (converted.isEmpty() || converted.first().isDigit()) "_$converted" else converted
}

/**
 * The name of the ADR-024 exception slot every synchronous C# import and wrapper body declares as
 * its trailing `out IntPtr error`. Named once here so the rename rule below cannot drift from the
 * ~60 `out IntPtr error` literals in `cir/` that the slot is actually rendered from.
 */
internal const val CSHARP_ERROR_SLOT: String = "error"

/**
 * The identifiers the ordinary forward callable plan puts on the ABI itself, and therefore the
 * names a user parameter may not keep on *either* side of the bridge: the instance receiver slot
 * (`handle`), the extension/value-class receiver slot (`receiver`), the value-class receiver slot
 * of a non-reference underlying and the property setter's argument (`value`), the ADR-024
 * exception slot (`errorOut`), ADR-061's nullable-primitive out-slot (`valueOut`) and ADR-141's
 * inner-class constructor receiver (`outer`).
 *
 * ADR-141 recorded a declared parameter named `outer` as an unguarded CS0100 hazard. It is guarded
 * after all, and by this set rather than by a rule of its own: the plan invariant below is that a
 * generator slot's NAME and its role agree, so the receiver slot can only be called `outer` if
 * `outer` is plan-owned, which shifts a user's own `outer` to `outer_` on every callable exactly
 * as `value` already shifts.
 *
 * Unlike [CSHARP_ERROR_SLOT] these are declared by the Kotlin `@CName` emitter too, so the rename
 * has to happen once at *plan* time and be seen by both projections. See [bridgeParameterName].
 *
 * ADR-062: this set is consulted in exactly two places. [bridgeParameterName] shifts a user
 * parameter off it, and `ForwardAbiParameter`'s `init` requires the name and the slot's
 * `ForwardAbiRole` to agree, so a generator slot left with the default `USER` role fails at
 * construction instead of reading back as user data in a projection.
 */
internal val PLAN_OWNED_NAMES: Set<String> =
  setOf("handle", "receiver", "value", "outer", "errorOut", "valueOut")

/**
 * The identifiers only the C# wrapper *body* declares: the ADR-024 exception slot, the local every
 * non-void wrapper opens with (`T nativeResult = Native_...(...)`) and ADR-061's presence flag
 * (`bool hasValue = ...`). None of them exists in the Kotlin export, so they shift at render time
 * only. See [csharpParameterName].
 */
private val CSHARP_OWNED_NAMES: Set<String> =
  setOf(CSHARP_ERROR_SLOT, "nativeResult", "hasValue")

/**
 * The bridge spelling of a user's Kotlin parameter name, applied once as the name enters a
 * [io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardCallablePlan] so both projections
 * read the shifted name.
 *
 * Issue #66 fixed one name (`error`) at C# render time; the rest of the family in
 * [PLAN_OWNED_NAMES] cannot be fixed there, because the Kotlin export declares them too. A user
 * parameter named `handle` on an instance method is a duplicate parameter in the `@CName` function
 * as well as CS0100 in the extern, and `errorOut` / `valueOut` additionally break the ADR-055
 * contract check, which reads a slot's direction off its name.
 *
 * Same chain rule as [csharpParameterName]: shifting the whole `handle` / `handle_` / `handle__`
 * chain up by one underscore is injective, so two user parameters of one callable can never
 * converge and the renamer never has to see its siblings.
 *
 * `value` is shifted on every callable rather than only where it actually collides, so the rule
 * stays one predictable spelling instead of a per-callable collision test whose answer changes
 * with the receiver kind.
 *
 * Only *parameters* move. A property named `value` renders `Value` (PascalCase), which never meets
 * a generator identifier, and stays put.
 */
internal fun String.bridgeParameterName(): String =
  if (shadows(PLAN_OWNED_NAMES)) "${this}_" else this

/**
 * The C# spelling of a Kotlin parameter name, at both its declaration and every use site. Two
 * render-time rules, kept in one function so they cannot disagree (a name can only ever hit one of
 * them: `error` is not a C# keyword):
 *
 * Issue #65: Kotlin admits C# reserved words as identifiers (`abstract`, `default`, `params`,
 * `ref`, `string`, ...), so a parameter name may be a keyword on the C# side; it is escaped to a
 * verbatim identifier.
 *
 * Issue #66: a parameter named [CSHARP_ERROR_SLOT] would be declared twice in the same signature
 * (CS0100) and, in the wrapper body, would rebind to the inline `out IntPtr error` local that
 * scopes over the whole block (CS0136/CS0841). It takes a `_` suffix instead. So must every name
 * that is already `error` followed by only underscores, or the shifted `error` would land on a
 * sibling: shifting the whole chain up by one is injective (chain members map within the chain,
 * every other name is a fixed point outside it, and nothing maps back onto `error`), so no two
 * parameters of one callable can converge, without the renamer needing to see its siblings. The
 * cost of that locality is that a lone `error_` shifts to `error__` even with no `error` beside it.
 *
 * Both rules apply at *render* time only: the plan and every legacy CIR translator keep the Kotlin
 * name, because the Kotlin `@CName` emitter reads the same names for its own signature and its
 * positional invocation, where `@abstract` is not valid Kotlin and where `error` collides with
 * nothing (the planner names the Kotlin ABI slot `errorOut`).
 *
 * Composite C# locals derived from a parameter (`${name}Handle`, `${name}Ptr`, `${name}Owned`)
 * apply this first and append after, so the `@` stays in *leading* position: `@paramsHandle` is a
 * valid verbatim identifier, `params@Handle` is not.
 *
 * Deliberately not [toCSharpName]: that helper's `trimEnd('_')` exists for C-mangled *function*
 * names, and a parameter name is never C-mangled.
 */
internal fun String.csharpParameterName(): String = when {
  this in CSHARP_RESERVED -> "@$this"
  shadows(CSHARP_OWNED_NAMES) -> "${this}_"
  else -> this
}

/**
 * True when this name is one of [reserved], or one of them followed by nothing but underscores:
 * the chain a shift by one underscore has to cover to stay injective. See [csharpParameterName].
 */
private fun String.shadows(reserved: Set<String>): Boolean = reserved.any { name ->
  startsWith(name) && substring(name.length).all { character -> character == '_' }
}
