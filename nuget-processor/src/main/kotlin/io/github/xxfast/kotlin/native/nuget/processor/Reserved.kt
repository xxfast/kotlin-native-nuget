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

fun toCSharpName(cname: String): String {
  if (cname.trimEnd('_') in CSHARP_RESERVED) return "@$cname"
  return cname
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
 * exception slot (`errorOut`) and ADR-061's nullable-primitive out-slot (`valueOut`).
 *
 * Unlike [CSHARP_ERROR_SLOT] these are declared by the Kotlin `@CName` emitter too, so the rename
 * has to happen once at *plan* time and be seen by both projections. See [bridgeParameterName].
 */
internal val PLAN_OWNED_NAMES: Set<String> =
  setOf("handle", "receiver", "value", "errorOut", "valueOut")

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
