package io.github.xxfast.kotlin.native.nuget.processor

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeArgument

/**
 * ADR-074 / ADR-091 / ADR-096: the index of every `expect` declaration in the compilation, keyed by
 * qualified name.
 *
 * The `actual` is the export root (ADR-074) but is metadata-poor: normally no KDoc (one written on
 * the `actual` *is* reported, and wins, but authors write it on the `expect`), no annotations, and
 * no parameter defaults, because Kotlin forbids an `actual` from restating one. `findExpects()` is
 * Verified empty on KSP 2.3.10, so the qualified name is the only available link back to the
 * `expect` half.
 *
 * A qualified name is not unique for functions: overloaded top-level `expect fun`s share one. The
 * index therefore holds *every* declaration under a name and resolves a function by signature
 * ([functionOrNull]), so one overload's defaults can never be attributed to another. Zero or
 * multiple matches resolve to `null`, which is the "no defaults, own file name" fallback.
 */
internal class ExpectIndex(declarations: List<KSDeclaration> = emptyList()) {

  private val byName: Map<String, List<KSDeclaration>> = declarations
    .filter { it.isExpect }
    .mapNotNull { declaration ->
      declaration.qualifiedName?.asString()?.let { name -> name to declaration }
    }
    .groupBy({ it.first }, { it.second })

  /**
   * ADR-091: the `expect class` under [qualifiedName], the only source of the primary constructor's
   * parameter defaults. A qualified name names at most one class, so no signature rule applies.
   */
  fun classOrNull(qualifiedName: String?): KSClassDeclaration? =
    byName[qualifiedName].orEmpty().filterIsInstance<KSClassDeclaration>().singleOrNull()

  /**
   * ADR-096: the single `expect fun` that [actual] actualizes, matched on the extension receiver,
   * then parameter count, then positional parameter names, then positional parameter types.
   *
   * The receiver is part of the comparison rather than a reason to bail out: an extension used to
   * resolve to `null` here, which cost a documented `expect fun Foo.bar()` its KDoc. Comparing the
   * rendered receiver (absent matches only absent) keeps the property that motivated the original
   * exclusion -- a non-extension never matches an extension of the same qualified name, and an
   * extension never matches one on a different receiver.
   *
   * Ambiguity resolves to `null` rather than to a guess, because a wrong match hands one overload's
   * defaults to another and emits an omitting overload whose Kotlin call site does not compile.
   */
  fun functionOrNull(actual: KSFunctionDeclaration): KSFunctionDeclaration? {
    val candidates: List<KSFunctionDeclaration> = byName[actual.qualifiedName?.asString()]
      .orEmpty()
      .filterIsInstance<KSFunctionDeclaration>()
      .filter { it.matches(actual) }
    return candidates.singleOrNull()
  }

  /**
   * ADR-074 Decision 3: the file the `expect` half of [declaration] was declared in, so a top-level
   * `actual` takes its C# static class name from the shared `expect` file instead of its own
   * per-target one. Functions resolve by signature, so two `expect` overloads declared in different
   * files each name their own file.
   */
  fun fileNameOrNull(declaration: KSDeclaration): String? {
    if (!declaration.isActual) return null
    val expect: KSDeclaration? =
      if (declaration is KSFunctionDeclaration) functionOrNull(declaration)
      else byName[declaration.qualifiedName?.asString()].orEmpty().firstOrNull()
    return expect?.containingFile?.fileName?.removeSuffix(".kt")
  }

  /**
   * ADR-150: the KDoc of the `expect` half of [declaration], consulted because the author normally
   * writes the doc on the `expect` and leaves the `actual` bare.
   *
   * Not because an `actual` *cannot* report one: measured 2026-09-20 on mingwX64, an `actual` that
   * carries its own KDoc reports it, and the caller's `docString ?: docOrNull(this)` then prefers
   * the `actual`'s text. Both arms are live; ADR-150's "always null today" reading is stale.
   *
   * The index holds top-level declarations only, so a *member* of an `expect class` is found by
   * walking the indexed class's own declarations: by signature for a function, by simple name for
   * anything else.
   */
  fun docOrNull(declaration: KSDeclaration): String? {
    val parent: KSClassDeclaration? = declaration.parentDeclaration as? KSClassDeclaration
    // An enum ENTRY of an `actual enum class` is not itself marked `actual` (measured: KSP reports
    // `isActual == false` on it), so gating on the declaration alone lost every entry's KDoc. A
    // member of an `actual` owner is resolved through that owner below, where the match is by
    // signature for a function and by simple name otherwise, so a platform-only member simply
    // finds nothing.
    val declarationOrOwnerIsActual: Boolean = declaration.isActual || parent?.isActual == true
    if (!declarationOrOwnerIsActual) return null
    if (parent == null) {
      val expect: KSDeclaration? =
        if (declaration is KSFunctionDeclaration) functionOrNull(declaration)
        else byName[declaration.qualifiedName?.asString()].orEmpty().firstOrNull()
      return expect?.docString
    }
    val expectClass: KSClassDeclaration = classOrNull(parent.qualifiedName?.asString())
      ?: return null
    val name: String = declaration.simpleName.asString()
    val members: List<KSDeclaration> =
      expectClass.declarations.filter { it.simpleName.asString() == name }.toList()
    val member: KSDeclaration? =
      if (declaration is KSFunctionDeclaration) {
        members.filterIsInstance<KSFunctionDeclaration>().singleOrNull { it.matches(declaration) }
      } else {
        members.singleOrNull()
      }
    return member?.docString
  }

  private fun KSFunctionDeclaration.matches(actual: KSFunctionDeclaration): Boolean {
    if (receiverRendering() != actual.receiverRendering()) return false
    if (parameters.size != actual.parameters.size) return false
    return parameters.zip(actual.parameters).all { (expected, declared) ->
      expected.name?.asString() == declared.name?.asString() &&
          expected.type.resolve().render() == declared.type.resolve().render()
    }
  }

  /**
   * The extension receiver as part of a signature, or `null` for a non-extension. Rendered the same
   * way every other position is, so an alias receiver compares identically on both halves.
   */
  private fun KSFunctionDeclaration.receiverRendering(): String? =
    extensionReceiver?.resolve()?.render()

  /**
   * A structural name for a type, deliberately not [KSType.toString], which is not specified to be
   * stable or fully qualified. Aliases are left unexpanded: an `expect`/`actual` pair is written
   * against the same declarations, so both halves render an alias identically.
   */
  private fun KSType.render(): String {
    val name: String = declaration.qualifiedName?.asString()
      ?: declaration.simpleName.asString()
    val arguments: String =
      if (this.arguments.isEmpty()) ""
      else this.arguments.joinToString(",", "<", ">") { it.render() }
    return "$name$arguments${if (isMarkedNullable) "?" else ""}"
  }

  private fun KSTypeArgument.render(): String = type?.resolve()?.render() ?: "*"
}
