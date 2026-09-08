package io.github.xxfast.kotlin.native.nuget.processor.forward

import com.google.devtools.ksp.isConstructor
import com.google.devtools.ksp.symbol.FileLocation
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSNode
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec

/**
 * ADR-117: which Kotlin declaration composed a given C entry point, so a duplicate-entry-point
 * failure can name the *owners* (`sample.Radio.play(Player)`) rather than only the mangled symbol.
 *
 * The index is built from the one artifact that holds every export of both universes — the
 * `CNameExports.kt` [FileSpec] the processor already has in hand before the contract check — at
 * two granularities:
 *
 * - **fine**: an export whose `FunSpec` carries a [ForwardExportOwnerTag] (every planned callable,
 *   every planned property, and the two suspend legacy sites) resolves to its own declaration;
 * - **coarse**: any other export is attributed by [ForwardExportOwnerRange], the `members` range
 *   the per-declaration loop in `generateCNameWrappers` added it in, so a route-owned export (the
 *   generated `Dispose`, a sealed discriminator, a `_collect`) names its owning top-level
 *   declaration.
 *
 * A `FunSpec` in neither bucket is a generator helper (`nuget_dispose`, the scope/job and lambda
 * helpers) and says so.
 */
internal data class ForwardExportOwnerTag(
  /** A planned callable's `plan.invocation.symbol` or a property plan's `plan.symbol`. */
  val symbol: String? = null,
  /** The declaration itself, where the emitter holds it (the suspend legacy route). */
  val declaration: KSDeclaration? = null,
)

/** One owning Kotlin declaration of a C entry point, with the source location when there is one. */
internal data class ForwardExportOwner(val text: String, val node: KSNode? = null) {
  fun render(): String {
    val at: String = (node?.location as? FileLocation)
      ?.let { location -> "\n    at ${location.filePath}:${location.lineNumber}" }
      .orEmpty()
    return "$text$at"
  }
}

/**
 * The `[from, until)` half-open range of `FileSpec.Builder.members` indices a single top-level
 * declaration's export builders added, recorded by `generateCNameWrappers`.
 */
internal data class ForwardExportOwnerRange(
  val from: Int,
  val until: Int,
  val declaration: KSDeclaration,
)

internal class ForwardExportOwners(
  private val byEntryPoint: Map<String, List<ForwardExportOwner>>,
) {
  /**
   * The owners of [entryPoint], or the explicit generator-helper owner when the index knows of no
   * Kotlin declaration behind it (an empty index included: the caller still gets a legible name
   * rather than a silently ownerless message).
   */
  fun owners(entryPoint: String): List<ForwardExportOwner> =
    byEntryPoint[entryPoint].orEmpty().ifEmpty { listOf(GENERATED_HELPER) }

  companion object {
    val EMPTY: ForwardExportOwners = ForwardExportOwners(emptyMap())

    val GENERATED_HELPER: ForwardExportOwner =
      ForwardExportOwner("generated helper (no Kotlin declaration)")

    private const val ROUTE_OWNED: String =
      " (route-owned export: the generated Dispose, a suspend/Flow/sealed export, or another " +
          "legacy route)"

    fun build(
      file: FileSpec,
      ranges: List<ForwardExportOwnerRange>,
      catalog: ForwardCallablePlanCatalog,
    ): ForwardExportOwners {
      val byEntryPoint: MutableMap<String, MutableList<ForwardExportOwner>> = mutableMapOf()
      // `FileSpec.members` preserves the builder's own `members` order (KotlinPoet copies the list
      // verbatim), so a builder-side index range addresses the same element here.
      file.members.forEachIndexed { index, member ->
        val function: FunSpec = member as? FunSpec ?: return@forEachIndexed
        val entryPoint: String = function.cNameEntryPoint() ?: return@forEachIndexed
        byEntryPoint.getOrPut(entryPoint) { mutableListOf() } +=
          function.owner(index, ranges, catalog)
      }
      return ForwardExportOwners(byEntryPoint)
    }

    private fun FunSpec.owner(
      index: Int,
      ranges: List<ForwardExportOwnerRange>,
      catalog: ForwardCallablePlanCatalog,
    ): ForwardExportOwner {
      // Tag wins over range: a planned member of a class sits inside that class's coarse range.
      val tag: ForwardExportOwnerTag? = tag(ForwardExportOwnerTag::class)
      if (tag != null) {
        val tagged: KSDeclaration? = tag.declaration
        if (tagged != null) return ForwardExportOwner(render(tagged), tagged)
        val symbol: String? = tag.symbol
        if (symbol != null) {
          // A property plan has no catalog node, and an unresolvable symbol must degrade to its
          // own text rather than fail: this is a diagnostic, never a generator invariant.
          val node: KSNode? = catalog.entries.firstOrNull { entry -> entry.symbol == symbol }?.node
          val declaration: KSDeclaration? = node as? KSDeclaration
          return if (declaration != null) ForwardExportOwner(render(declaration), declaration)
          else ForwardExportOwner(symbol, node)
        }
      }

      val range: ForwardExportOwnerRange? = ranges.firstOrNull { range ->
        index >= range.from && index < range.until
      }
      if (range != null) {
        val declaration: KSDeclaration = range.declaration
        return ForwardExportOwner(qualified(declaration) + ROUTE_OWNED, declaration)
      }

      return GENERATED_HELPER
    }

    /**
     * `pkg.Owner.member(ParamType, ...)`, and `pkg.Owner(ParamType, ...)` for a constructor — via
     * `parentDeclaration`, never via the constructor's own `qualifiedName`, whose spelling KSP
     * does not contract.
     */
    private fun render(declaration: KSDeclaration): String {
      if (declaration !is KSFunctionDeclaration) return qualified(declaration)

      val owner: String = if (declaration.isConstructor()) {
        declaration.parentDeclaration?.let { parent -> qualified(parent) }.orEmpty()
      } else {
        qualified(declaration)
      }
      val parameters: String = declaration.parameters.joinToString(", ") { parameter ->
        parameter.type.resolve().declaration.simpleName.asString()
      }
      return "$owner($parameters)"
    }

    private fun qualified(declaration: KSDeclaration): String =
      declaration.qualifiedName?.asString() ?: declaration.simpleName.asString()
  }
}

/** The `@CName` entry point a generated export declares, if it is one. */
internal fun FunSpec.cNameEntryPoint(): String? = annotations
  .firstOrNull { annotation -> annotation.typeName.toString() == "kotlin.native.CName" }
  ?.members
  ?.singleOrNull()
  ?.toString()
  ?.removeSurrounding("\"")

/**
 * ADR-117: `generateCNameWrappers`' result — the exports file plus the coarse per-declaration
 * `members` ranges the owner index needs. Bundled rather than threaded through a mutable
 * out-parameter so the index cannot be built from ranges that belong to a different `FileSpec`.
 */
internal data class ForwardCNameExports(
  val file: FileSpec,
  val ranges: List<ForwardExportOwnerRange>,
)
