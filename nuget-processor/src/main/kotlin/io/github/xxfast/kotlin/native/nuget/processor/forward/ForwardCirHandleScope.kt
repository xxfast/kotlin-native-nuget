package io.github.xxfast.kotlin.native.nuget.processor.forward

/**
 * One temporary native handle a C# call site builds before the native call and has to release
 * after it: a collection handle (`NugetMarshal.CreateList(...)`), an ADR-084 interface transfer
 * handle, or an ADR-088 bound-interface GCHandle (which has no cleanup and therefore never needs
 * hoisting).
 *
 * [flat] is the shipped single-statement declare-and-initialize spelling, used when nothing in the
 * body needs disposing. [declarations] and [statement] are the same step split in two so the
 * locals can be declared *before* a `try` and assigned inside it: a `finally` cannot name a local
 * declared in the block it guards.
 */
internal data class ForwardCirHandleStep(
  val flat: String,
  val declarations: List<String> = emptyList(),
  val statement: String = flat,
  /**
   * ADR-210: a statement to run the moment the native call has returned, before its error check.
   * A suspend lambda's key uses it to give up ownership: from then on the Kotlin holder owns it,
   * and the `finally` must no longer unregister it.
   */
  val afterCall: String? = null,
)

/**
 * ROADMAP:130 / ROADMAP:340. Wraps [core] (the native call, its error check, and the result
 * statements, already indented at [indent]) so every temporary handle is released on **every**
 * exit path. The shipped shape put the disposes after the error check's `throw`, so a Kotlin
 * exception leaked a rooted StableRef for the life of the process.
 *
 * When [cleanup] is empty there is nothing to guard and the body keeps its flat shape byte for
 * byte: bodies with no collection or interface handle are unchanged by this.
 */
/** The trailing error slot every plan-route native call passes; it marks the call statement. */
private const val NATIVE_CALL_MARKER: String = "out IntPtr error)"

internal fun forwardCirHandleScope(
  prelude: List<ForwardCirHandleStep>,
  cleanup: List<String>,
  core: String,
  indent: String = "            ",
): String {
  // ROADMAP:29: every renderer that consumes a body writes `{${body}`, so a body always leads with
  // a newline and its opening brace stays alone on its line. The lead used to be optional
  // (`leadingNewline = false`), which is how the property accessors and the custom constructor came
  // to render their first statement on the brace line.
  if (cleanup.isEmpty()) {
    return "\n" + prelude.joinToString("") { step -> "$indent${step.flat}\n" } + core
  }
  val inner = "$indent    "
  return buildString {
    append("\n")
    prelude.flatMap { step -> step.declarations }.forEach { line -> appendLine("$indent$line") }
    appendLine("${indent}try")
    appendLine("$indent{")
    // ADR-164: a step whose whole effect is its hoisted declaration has no statement left.
    prelude.filter { step -> step.statement.isNotEmpty() }
      .forEach { step -> appendLine("$inner${step.statement}") }
    val afterCall: List<String> = prelude.mapNotNull { step -> step.afterCall }
    val coreLines: List<String> = core.trim('\n').lines()
    // Every plan-route native call ends in the error slot and is one whole statement, which is
    // what lets the hand-over land between the call returning and its error check.
    val callLine: Int = coreLines.indexOfFirst { line -> line.contains(NATIVE_CALL_MARKER) }
    val callIsStatement: Boolean = callLine >= 0 && coreLines[callLine].trimEnd().endsWith(";")
    require(afterCall.isEmpty() || callIsStatement) {
      "Forward CIR handle scope found no native call statement to hand ownership over after"
    }
    val indentedCore: String =
      coreLines.flatMapIndexed { index, line ->
        val indented: String = if (line.isBlank()) line else "    $line"
        if (index == callLine) listOf(indented) + afterCall.map { after -> "$inner$after" }
        else listOf(indented)
      }.joinToString("\n")
    appendLine(indentedCore)
    appendLine("$indent}")
    appendLine("${indent}finally")
    appendLine("$indent{")
    cleanup.forEach { line -> appendLine("$inner$line") }
    append("$indent}")
  }
}

/**
 * ADR-147: a `T` value is boxed before the call. `Wrap<T>` reports whether it MINTED the box
 * (a primitive, a char, a string) or contributed a live wrapper's own `_handle`; only a minted
 * box is disposed ([forwardCirTypeParameterCleanup]), which is ADR-099's ownership rule verbatim.
 * Shared by a callable's `T` argument and (ADR-218) a property setter's `T` value, so the ADR-198
 * `Wrap<T?>` rule lives once. Null when [type] is not a type parameter.
 */
internal fun forwardCirTypeParameterBox(
  type: BridgeType,
  name: String,
  local: String,
): ForwardCirHandleStep? {
  val parameter: BridgeType.TypeParameter =
    type.unwrapNullable() as? BridgeType.TypeParameter ?: return null
  // ADR-198: under a `struct` constraint `T?` is `Nullable<T>`, which `Wrap<T>` cannot take; it
  // boxes as itself, `Wrap<T?>` normalising to the underlying enum and answering null with null.
  val wrap: String = if (parameter.valueType && type is BridgeType.Nullable) {
    "NugetMarshal.Wrap<${parameter.name}?>($name, out"
  } else {
    "NugetMarshal.Wrap<${parameter.name}>($name!, out"
  }
  return ForwardCirHandleStep(
    flat = "IntPtr ${local}Box = $wrap bool ${local}Owned);",
    declarations = listOf("IntPtr ${local}Box = IntPtr.Zero;", "bool ${local}Owned = false;"),
    statement = "${local}Box = $wrap ${local}Owned);",
  )
}

/** ADR-099/147: dispose only a box [forwardCirTypeParameterBox] minted, and only once it exists. */
internal fun forwardCirTypeParameterCleanup(
  type: BridgeType,
  name: String,
  local: String,
): String? {
  if (type.unwrapNullable() !is BridgeType.TypeParameter) return null
  // ADR-187: keep a borrowed wrapper alive past the call (only when borrowed: `T` may be a value).
  return "if (${local}Owned && ${local}Box != IntPtr.Zero) { " +
      "NugetMarshal.Dispose(${local}Box); } " +
      "if (!${local}Owned) GC.KeepAlive($name);"
}
