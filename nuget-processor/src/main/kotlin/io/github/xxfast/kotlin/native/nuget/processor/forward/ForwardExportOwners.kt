package io.github.xxfast.kotlin.native.nuget.processor.forward

import com.google.devtools.ksp.isConstructor
import com.google.devtools.ksp.symbol.FileLocation
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSNode
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec

/**
 * ADR-117: which Kotlin declaration composed a given C entry point, so a duplicate-entry-point
 * failure can name the *owners* (`sample.Radio.play(Player)`) rather than only the mangled symbol.
 *
 * The index is built from the one artifact that holds every export of both universes — the
 * `CNameExports.kt` [FileSpec] the processor already has in hand before the contract check.
 *
 * Every export resolves through its own [ForwardExportOwnerTag] (ADR-117 amendment, 2026-09-13):
 * the tag rides on the `@CName` annotation, minted by the single `cNameAnnotation(value, owner)`
 * helper whose `owner` parameter is required, so a planned callable, a planned property and every
 * legacy route alike name the declaration they came from. An export whose `@CName` bypassed that
 * helper carries no tag and fails [build] by name, rather than being attributed to a declaration
 * that merely sits near it.
 */
internal data class ForwardExportOwnerTag(
  /** A planned callable's `plan.invocation.symbol` or a property plan's `plan.symbol`. */
  val symbol: String? = null,
  /** The declaration itself, where the emitter holds it (every legacy route). */
  val declaration: KSDeclaration? = null,
  /**
   * ADR-117 amendment (2026-09-13): which *generated* member of [declaration] this export is, when
   * the owner is the class rather than a member the user wrote: `generated Dispose`,
   * `sealed discriminator`, `data-class equals`, `generic create variant: string`. A member site
   * (a property getter, a Flow `_collect`, a stored-callback pair) carries none.
   */
  val role: String? = null,
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

    fun build(file: FileSpec, catalog: ForwardCallablePlanCatalog): ForwardExportOwners {
      val byEntryPoint: MutableMap<String, MutableList<ForwardExportOwner>> = mutableMapOf()
      file.members.forEach { member ->
        val function: FunSpec = member as? FunSpec ?: return@forEach
        val entryPoint: String = function.cNameEntryPoint() ?: return@forEach
        byEntryPoint.getOrPut(entryPoint) { mutableListOf() } += function.owner(entryPoint, catalog)
      }
      return ForwardExportOwners(byEntryPoint)
    }

    private fun FunSpec.owner(
      entryPoint: String,
      catalog: ForwardCallablePlanCatalog,
    ): ForwardExportOwner {
      // ADR-117 amendment: the tag rides on the `@CName` annotation, minted by the one
      // `cNameAnnotation(value, owner)` helper whose owner is required, so every export of every
      // route carries its own owner.
      val tag: ForwardExportOwnerTag? = cNameOwnerTag()
      val role: String = tag?.role?.let { label -> " ($label)" }.orEmpty()
      val tagged: KSDeclaration? = tag?.declaration
      if (tagged != null) return ForwardExportOwner(render(tagged) + role, tagged)

      val symbol: String? = tag?.symbol
      if (symbol != null) {
        // A property plan has no catalog node, and an unresolvable symbol must degrade to its own
        // text rather than fail: this is a diagnostic, never a generator invariant.
        val node: KSNode? = catalog.entries.firstOrNull { entry -> entry.symbol == symbol }?.node
        val declaration: KSDeclaration? = node as? KSDeclaration
        return if (declaration != null) {
          ForwardExportOwner(render(declaration) + role, declaration)
        } else {
          ForwardExportOwner(symbol + role, node)
        }
      }

      // No tag at all, or a tag carrying neither declaration nor symbol: an export minted outside
      // `cNameAnnotation(value, owner)`. Fail loudly here rather than let it be attributed to
      // whatever sits near it in the file.
      error(
        "Forward ABI export $entryPoint carries no owner tag; every @CName export must be minted " +
            "through cNameAnnotation(value, owner) with a declaration or a symbol",
      )
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

/** The `@CName` annotation a generated export declares, if it is one. */
private fun FunSpec.cName(): AnnotationSpec? = annotations
  .firstOrNull { annotation -> annotation.typeName.toString() == "kotlin.native.CName" }

/**
 * Sibling of [cNameEntryPoint]: the owner tag `cNameAnnotation(value, owner)` hung on the
 * annotation itself. A `FunSpec` whose `@CName` was not minted by that helper has none — a
 * generator invariant now, enforced by [ForwardExportOwners.build], which fails naming the entry
 * point.
 */
internal fun FunSpec.cNameOwnerTag(): ForwardExportOwnerTag? =
  cName()?.tag(ForwardExportOwnerTag::class)

/** The `@CName` entry point a generated export declares, if it is one. */
internal fun FunSpec.cNameEntryPoint(): String? = cName()
  ?.members
  ?.singleOrNull()
  ?.toString()
  ?.removeSurrounding("\"")
