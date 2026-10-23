package io.github.xxfast.kotlin.native.nuget.processor.forward

import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeParameter
import com.google.devtools.ksp.symbol.Visibility

/**
 * ADR-237: the one box/unbox pair a generic value class crosses an `object` position on. Kotlin
 * boxes every instantiation of `Bow<X>` as one runtime class, so one pair serves them all: the box
 * export re-wraps an `object` box of the underlying (running `init`), and the unbox export hands
 * the underlying back as an `object` box. The C# struct reaches the box through
 * `INugetGenericValue`, and `NugetMarshal.KotlinTypes` reads one back as `Bow<object?>`.
 *
 * Only an underlying that is the class's own type parameter crosses this way, since an `object`
 * box carries exactly that type erased. Every other parameter of the class is phantom, so the box
 * constructs it at `Nothing`, which satisfies any bound.
 *
 * @param underlyingProperty the public underlying property the unbox reads.
 * @param construction the fully applied type the box constructs (`Bow<Any?>`).
 * @param star the star projection the unbox reads the handle as (`Bow<*>`).
 * @param cast the type the box casts the unboxed `Any?` to, or null when it is `Any?` itself.
 * @param csharpUnderlying the C# spelling of the underlying inside the struct (`T`, `T?`).
 */
internal data class ForwardGenericValueBox(
  val underlyingProperty: String,
  val construction: String,
  val star: String,
  val cast: String?,
  val csharpUnderlying: String,
)

/**
 * ADR-237: [ForwardGenericValueBox] for this generic value class, or null when its underlying
 * cannot ride an `object` box: a non-public underlying (no unbox can read it), an underlying that
 * is not the class's own type parameter, or a bound that itself mentions a type parameter (no
 * closed spelling to construct at). Such a value still crosses an `object` position, opaquely, as
 * the `KotlinObject` that hands the same Kotlin value back.
 */
internal fun KSClassDeclaration.genericValueBox(): ForwardGenericValueBox? {
  if (typeParameters.isEmpty()) return null
  val qualified: String = qualifiedName?.asString() ?: return null
  val parameterName: String = primaryConstructor?.parameters?.firstOrNull()?.name?.asString()
    ?: return null
  val property: KSPropertyDeclaration = getAllProperties()
    .firstOrNull { it.simpleName.asString() == parameterName } ?: return null
  if (property.getVisibility() != Visibility.PUBLIC) return null
  val underlying: KSType = property.type.resolve()
  val own: KSTypeParameter = underlying.declaration as? KSTypeParameter ?: return null
  val ownName: String = own.name.asString()
  if (typeParameters.none { it.name.asString() == ownName }) return null
  val bounds: List<KSType> = own.bounds.map { it.resolve() }.toList()
  if (bounds.size > 1 || bounds.any { it.arguments.isNotEmpty() }) return null
  val bound: KSType? = bounds.firstOrNull()
  val boundName: String = bound?.declaration?.qualifiedName?.asString() ?: "kotlin.Any"
  val argument: String = boundName + if (bound == null || bound.isMarkedNullable) "?" else ""
  val construction: String =
    typeParameters.joinToString(", ", "$qualified<", ">") { parameter ->
      if (parameter.name.asString() == ownName) argument else "Nothing"
    }
  val castTarget: String =
    if (underlying.isMarkedNullable && !argument.endsWith("?")) "$argument?" else argument
  return ForwardGenericValueBox(
    underlyingProperty = parameterName,
    construction = construction,
    star = typeParameters.joinToString(", ", "$qualified<", ">") { "*" },
    cast = castTarget.takeIf { it != "kotlin.Any?" },
    csharpUnderlying = ownName + if (underlying.isMarkedNullable) "?" else "",
  )
}
