package io.github.xxfast.kotlin.native.nuget.processor.forward

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import io.github.xxfast.kotlin.native.nuget.processor.cir.LAMBDA_TYPES
import io.github.xxfast.kotlin.native.nuget.processor.cir.SUSPEND_LAMBDA_TYPES
import io.github.xxfast.kotlin.native.nuget.processor.cir.expandAliases

/**
 * Which hand-written lambda-property getter route, if any, an owner's properties reach. The
 * property planner reads it to decide whether an unplannable lambda property is a named skip, and
 * every emitter of that route reads the same answer through [carriesLegacyLambdaProperty], so the
 * report and the emission cannot disagree (ADR-064: a member is bound or named, never both and
 * never neither).
 *
 * The planner's own position cannot answer this: an ordinary class, a sealed base, a sealed arm
 * and an interface all plan as [ForwardPropertyPosition.CLASS], and they differ here.
 */
internal enum class ForwardLambdaPropertyCarrier {
  /** No lambda-property route: an interface, a companion, an object, top level, an enum, and a
   *  generic class (ADR-147: the route spells `asStableRef<Owner>()`). */
  NONE,

  /** `ClassExports` + `CirClassTranslator`'s class loop: a plain or suspend lambda, non-null. */
  CLASS,

  /** `SealedClassExports` + `translateSealedClass`'s arm loop: a plain lambda, non-null. */
  SEALED_ARM,

  /**
   * A sealed base's own lambda property. The base renders none, but the arm loop walks every
   * property an arm has, inherited ones included, so each arm binds it as [SEALED_ARM] does,
   * reading the inherited property off the arm. Emitted by no route on the base itself.
   *
   * An enum arm (ADR-157) runs no arm loop, but its own override is an enum member property,
   * which the enum's planner names on the enum, so every arm still either binds it or names it.
   */
  SEALED_BASE,
}

/** The [ForwardLambdaPropertyCarrier] of an ordinary (non-sealed) exported class. */
internal fun KSClassDeclaration.classLambdaPropertyCarrier(): ForwardLambdaPropertyCarrier =
  if (typeParameters.isEmpty()) {
    ForwardLambdaPropertyCarrier.CLASS
  } else {
    ForwardLambdaPropertyCarrier.NONE
  }

/**
 * True when [carrier]'s legacy lambda-property route binds [this] property.
 *
 * A nullable lambda is refused on every carrier ([refusesNullableLambdaProperty] names it): the
 * class getter hands the nullable value straight to `NugetHandles.retain(Any)`, which does not
 * compile, and the arm getter wrapped a null in a non-null `KotlinAction<T>` over a zero handle.
 */
internal fun KSPropertyDeclaration.carriesLegacyLambdaProperty(
  carrier: ForwardLambdaPropertyCarrier,
): Boolean {
  val declared: KSType = type.resolve()
  val expanded: KSType = declared.expandAliases()
  if (declared.isMarkedNullable || expanded.isMarkedNullable) return false
  return carrier.carries(expanded.declaration.qualifiedName?.asString())
}

/**
 * The non-null Kotlin spelling of [this] property's function type (`(Int) -> Unit`,
 * `suspend (Int) -> Unit`) when [carrier] would bind it but for its nullability, or null when the
 * property is not that shape. The diagnostic names the nullability, rather than the whole type,
 * as what has no route.
 */
internal fun KSPropertyDeclaration.refusesNullableLambdaProperty(
  carrier: ForwardLambdaPropertyCarrier,
): String? {
  val declared: KSType = type.resolve()
  val expanded: KSType = declared.expandAliases()
  if (!declared.isMarkedNullable && !expanded.isMarkedNullable) return null
  val qualifiedName: String? = expanded.declaration.qualifiedName?.asString()
  if (!carrier.carries(qualifiedName)) return null
  val spelled: List<String> = expanded.arguments.map { argument ->
    argument.type?.resolve()?.kotlinSpelling() ?: "*"
  }
  val prefix: String = if (qualifiedName in SUSPEND_LAMBDA_TYPES) "suspend " else ""
  return "$prefix(${spelled.dropLast(1).joinToString(", ")}) -> ${spelled.last()}"
}

private fun ForwardLambdaPropertyCarrier.carries(qualifiedName: String?): Boolean = when (this) {
  ForwardLambdaPropertyCarrier.NONE -> false
  ForwardLambdaPropertyCarrier.CLASS ->
    qualifiedName in LAMBDA_TYPES || qualifiedName in SUSPEND_LAMBDA_TYPES

  ForwardLambdaPropertyCarrier.SEALED_ARM, ForwardLambdaPropertyCarrier.SEALED_BASE ->
    qualifiedName in LAMBDA_TYPES
}
