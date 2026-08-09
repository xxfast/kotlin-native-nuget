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
internal fun forwardCirHandleScope(
  prelude: List<ForwardCirHandleStep>,
  cleanup: List<String>,
  core: String,
  indent: String = "            ",
  leadingNewline: Boolean = true,
): String {
  val lead: String = if (leadingNewline) "\n" else ""
  if (cleanup.isEmpty()) {
    return lead + prelude.joinToString("") { step -> "$indent${step.flat}\n" } + core
  }
  val inner = "$indent    "
  return buildString {
    append(lead)
    prelude.flatMap { step -> step.declarations }.forEach { line -> appendLine("$indent$line") }
    appendLine("${indent}try")
    appendLine("$indent{")
    prelude.forEach { step -> appendLine("$inner${step.statement}") }
    val indentedCore: String =
      core.trim('\n').lines().joinToString("\n") { line ->
        if (line.isBlank()) line else "    $line"
      }
    appendLine(indentedCore)
    appendLine("$indent}")
    appendLine("${indent}finally")
    appendLine("$indent{")
    cleanup.forEach { line -> appendLine("$inner$line") }
    append("$indent}")
  }
}
