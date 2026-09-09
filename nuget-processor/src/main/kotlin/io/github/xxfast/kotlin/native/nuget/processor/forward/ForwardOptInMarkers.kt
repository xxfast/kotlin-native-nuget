package io.github.xxfast.kotlin.native.nuget.processor.forward

import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSPropertySetter
import com.google.devtools.ksp.symbol.KSValueParameter

/**
 * ADR-115: a declaration carrying a `@RequiresOptIn`-meta-annotated marker is not part of the
 * forward-exported C# surface, at any `RequiresOptIn.Level`.
 *
 * C# has no way to honour a Kotlin opt-in requirement: a Kotlin consumer is forced to acknowledge
 * a marker, a C# consumer of the generated binding sees a plain public member with no signal at
 * all. So exporting a marked declaration always erases the marker's purpose, and the generated
 * `CNameExports.kt` does not even compile against an `ERROR`-level one.
 *
 * This is the forward direction's only annotation read (the pipeline reads no others), so it lives
 * in one place rather than inline at each of the five sites that need it.
 */
private const val REQUIRES_OPT_IN: String = "kotlin.RequiresOptIn"

/**
 * The two hops ADR-115 Findings 4 and 7 verified: resolve the annotation's own declaration and ask
 * whether *it* carries `kotlin.RequiresOptIn`.
 *
 * `kotlin.OptIn` answers null here, so a declaration that opts *in* to someone else's marker stays
 * exported (Finding 7). `kotlin.SubclassOptInRequired` also answers null (Finding 6) and that is
 * deliberate, not an oversight: its semantic is "you may use this, you may not subclass it", and
 * the forward direction never generates a C# subclass of an exported Kotlin class, so such a type
 * is still safe to export. Do not "fix" it.
 */
private fun KSAnnotation.optInMarkerName(): String? {
  val declaration: KSDeclaration = annotationType.resolve().declaration
  val marker: Boolean = declaration.annotations.any { meta ->
    meta.annotationType.resolve().declaration.qualifiedName?.asString() == REQUIRES_OPT_IN
  }
  if (!marker) return null
  return declaration.qualifiedName?.asString() ?: declaration.simpleName.asString()
}

/**
 * The fully-qualified name of the first opt-in marker on this declaration, or null when it carries
 * none.
 *
 * Two positions are read, and only two, because only two are writable Kotlin (ADR-115's amendment,
 * verified by compiling each shape against this repo's own toolchain):
 *
 * - the declaration itself, which covers `@Marker class`/`fun`/`val`, `@property:Marker` on a
 *   constructor `val` (Finding 1), and the default-target form on a constructor `val` of a marker
 *   whose `@Target` excludes `VALUE_PARAMETER` (Finding 2);
 * - a property's **setter**, for `@set:Marker` on a `var`. That marker is invisible on the
 *   declaration, so missing it is a silent leak.
 *
 * `@get:Marker` and `@field:Marker` are not read because neither compiles
 * (`Opt-in requirement marker annotation cannot be used on getter`/`... on field`), so ADR-115's
 * getter row describes a shape that cannot exist.
 *
 * `@set:Marker` skips the whole property rather than exporting it get-only: an accessor-level
 * partial projection does not exist in the forward plan.
 */
internal fun KSAnnotated.optInMarker(): String? {
  val own: String? = annotations.firstNotNullOfOrNull { annotation -> annotation.optInMarkerName() }
  if (own != null) return own
  val setter: KSPropertySetter = (this as? KSPropertyDeclaration)?.setter ?: return null
  return setter.annotations.firstNotNullOfOrNull { annotation -> annotation.optInMarkerName() }
}

/**
 * Issue #121: whether a legacy (non-plan) route must refuse to emit this property.
 *
 * `ForwardCallablePlanCatalog.propertyFor` answers null for two opposite reasons: the planner has
 * no shape for the type, which is exactly what the legacy lambda and Flow arms exist to handle,
 * and the planner *refused* the declaration, which nothing may emit. A legacy arm that reads only
 * "no plan" takes the second case as an invitation, so an opt-in marked lambda property was
 * reported `SKIPPED_OPT_IN_MARKER` and exported anyway, on both sides of the bridge.
 *
 * Every plan-driven route already gets this from the planner. Only the arms that run *after* a
 * null plan need to ask, which is why this is a predicate rather than another filter on the
 * property list: the property must still reach the planner to be diagnosed.
 *
 * The requirement is absence, not compilability. C# has no equivalent of a Kotlin opt-in marker,
 * so a member that reaches `Interop.cs` is unconditionally public API in the shipped package with
 * no way to re-hide it downstream.
 */
internal fun KSPropertyDeclaration.isOptInRefused(): Boolean = optInMarker() != null

/**
 * ADR-115: the marker on a constructor parameter, read from the parameter itself *and* from the
 * property a `val`/`var` parameter declares.
 *
 * Both positions are needed and neither subsumes the other: a default-target marker lands on the
 * parameter (and on the synthesized `copy`/`<init>` parameters), while `@property:Marker` lands
 * only on the property. The invariant either way is that the marked declaration never appears in a
 * C# signature, so the constructor cannot keep the slot: the generated Kotlin call is positional.
 */
internal fun KSValueParameter.constructorOptInMarker(owner: KSClassDeclaration?): String? {
  val own: String? = optInMarker()
  if (own != null) return own
  if (!isVal && !isVar) return null
  val propertyName: String = name?.asString() ?: return null
  return owner
    ?.getAllProperties()
    ?.firstOrNull { property -> property.simpleName.asString() == propertyName }
    ?.optInMarker()
}
