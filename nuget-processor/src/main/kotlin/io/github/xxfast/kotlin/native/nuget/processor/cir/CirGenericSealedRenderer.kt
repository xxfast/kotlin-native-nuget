package io.github.xxfast.kotlin.native.nuget.processor.cir

/**
 * ADR-199: a generic sealed hierarchy. The base `Outcome<T>` renders as ADR-009's abstract class
 * with its type parameters, and everything ADR-009 nests inside it (the arms, the declarations
 * Kotlin nests in the base, an intermediate sealed arm's own hierarchy) sits on the ADR-196
 * non-generic holder `Outcome` instead, so `Outcome.Ok<T>` spells Kotlin's `Outcome.Ok<Int>`.
 *
 * No extern may sit inside a generic type, nested or not (CS7042), so each generic block is
 * rendered by the ordinary path and its externs hoisted afterwards, the move `renderClass` makes
 * for a generic class: the base's into `OutcomeNative`, an arm's into `OkNative` on the holder.
 */
internal fun StringBuilder.renderGenericSealedClass(sealed: CirSealedClass) {
  appendLine("    public static class ${sealed.name}")
  appendLine("    {")
  val holder: String = buildString {
    sealed.subclasses.filter { it.isNested && !it.isIntermediate }.forEach { arm ->
      renderGenericArm(sealed, arm)
    }
    // An intermediate that closes an invariant parameter (`Cell.Odd : Cell<int>`) is not generic,
    // so it takes ADR-009's own route, its arms nested in it.
    sealed.intermediates.forEach { intermediate ->
      append(buildString { renderSealedClass(intermediate) }.indentNestedBody())
      appendLine()
    }
  }
  append(holder)
  if (sealed.nestedDeclarations.isNotEmpty()) renderNestedDeclarations(sealed.nestedDeclarations)
  appendLine("    }")

  val hoisted: HoistedDllImports =
    hoistDllImports(buildString { renderSealedBase(sealed) }, "${sealed.name}Native")
  renderNativeCarrier("${sealed.name}Native", hoisted.imports)
  append(hoisted.body.trimEnd()).appendLine()

  sealed.subclasses.filter { !it.isNested && !it.isIntermediate }.forEach { arm ->
    appendLine()
    append(buildString { renderGenericArm(sealed, arm) }.outdentToNamespaceLevel())
  }
}

/**
 * One arm, at the depth of a type nested in the holder. A generic arm's externs move onto an
 * `{Arm}Native` beside it, and an abstract one's generic wrapper onto a static `{Arm}` holder
 * beside it (C7); a closed arm (`Cell.IntCell : Cell<int>`) is not generic and keeps them.
 */
private fun StringBuilder.renderGenericArm(sealed: CirSealedClass, arm: CirSealedSubclass) {
  val block: String = sealedSubclassBlock(sealed, arm)
  if (arm.typeParameters.isEmpty()) {
    append(block)
    return
  }
  val hoisted: HoistedDllImports = hoistDllImports(block, "${arm.name}Native")
  append(hoisted.body.trimEnd()).appendLine()
  appendLine()
  val carrier: String = buildString { renderNativeCarrier("${arm.name}Native", hoisted.imports) }
  append(carrier.indentNestedBody())
  val backing: CirBacking? = arm.backing(sealed)?.takeIf { it.isHeld }
  if (backing == null && arm.nestedDeclarations.isEmpty()) return
  appendLine("        public static class ${arm.name}")
  appendLine("        {")
  if (backing != null) {
    val held: HoistedDllImports = hoistDllImports(backingClassBlock(backing), arm.name)
    held.imports.forEach { import -> append(import.indentNestedBody()) }
    appendLine()
    append(held.body.trimEnd().indentNestedBody()).appendLine()
  }
  if (arm.nestedDeclarations.isNotEmpty()) {
    append(buildString { renderNestedDeclarations(arm.nestedDeclarations) }.indentNestedBody())
    appendLine()
  }
  appendLine("        }")
  appendLine()
}

/** An `internal static class` holding hoisted externs, or nothing when there are none. */
private fun StringBuilder.renderNativeCarrier(name: String, imports: List<String>) {
  if (imports.isEmpty()) return
  appendLine("    internal static class $name")
  appendLine("    {")
  imports.forEach { import -> append(import) }
  appendLine("    }")
  appendLine()
}
