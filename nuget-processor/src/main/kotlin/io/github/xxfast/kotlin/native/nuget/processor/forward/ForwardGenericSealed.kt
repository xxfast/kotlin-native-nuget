package io.github.xxfast.kotlin.native.nuget.processor.forward

import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeParameter
import com.google.devtools.ksp.symbol.Variance

/** ADR-199: the shared contract's spelling of Kotlin's `Nothing` as a type argument. */
internal const val KOTLIN_NOTHING_CSHARP: String = "global::Kotlin.Native.Interop.KotlinNothing"

/**
 * ADR-199: whether this sealed type is declared on the generic route, its arms on an ADR-196
 * non-generic holder: it has type parameters, or it is an intermediate arm of a sealed type that
 * is. Its C# parameters ([forwardSealedCsParameters]) may still be empty (`Cell.Odd : Cell<int>`).
 */
internal fun KSClassDeclaration.isGenericSealedType(): Boolean {
  if (!isEligibleSealedType()) return false
  if (forwardArmSealedParent()?.isGenericSealedType() == true) return true
  return typeParameters.isNotEmpty()
}

/**
 * ADR-199: an arm of a generic sealed type that is itself sealed (`sealed class Fault<T> :
 * Outcome<T>()`, `sealed class Lapse : Outcome<Nothing>()`). It is declared as a sealed hierarchy
 * of its own, on the parent's holder, with the parent as its base, its parameters by the arm rule.
 */
internal fun KSClassDeclaration.isIntermediateGenericSealedArm(): Boolean =
  isEligibleSealedType() && forwardArmSealedParent()?.isGenericSealedType() == true

/**
 * One type parameter of a C# arm: an own Kotlin parameter it lists, or a phantom ([own] null).
 *
 * @property baseIndex the index of the base's C# parameter this one is passed as.
 * @property source the Kotlin parameter whose bound C# restates for it: [own], or for a phantom
 *   the root parameter it stands in for.
 * @property fixed for a phantom, the Kotlin argument the hierarchy fixed it to (`Nothing` for
 *   `Err : Outcome<Nothing>`), carried down through an intermediate arm to its own arms.
 */
internal data class ForwardSealedArmParameter(
  val csharpName: String,
  val own: KSTypeParameter?,
  val baseIndex: Int,
  val source: KSTypeParameter,
  val fixed: KSType? = null,
)

/** What one base parameter is in an arm's C# base type: an arm parameter, or a closed type. */
internal sealed interface ForwardSealedBaseArgument {
  data class Listed(val parameter: ForwardSealedArmParameter) : ForwardSealedBaseArgument
  data class Closed(val type: KSType) : ForwardSealedBaseArgument
}

/**
 * ADR-199's arm rule, read off the arm's supertype reference to its generic sealed base.
 *
 * @property parameters the C# arm's type parameters, in base order.
 * @property baseArguments one per base C# parameter.
 * @property unrecoverable own Kotlin parameters never listed: every member naming one skips.
 */
internal data class ForwardSealedArmShape(
  val parameters: List<ForwardSealedArmParameter>,
  val baseArguments: List<ForwardSealedBaseArgument>,
  val unrecoverable: Set<String>,
) {
  /** Rule 2 under an invariant parameter: the arm closes the base, so it is cast through object. */
  val isClosed: Boolean get() = baseArguments.any { it is ForwardSealedBaseArgument.Closed }
}

/** The arm's supertype reference to [base], with its type arguments. */
internal fun KSClassDeclaration.sealedSupertype(base: KSClassDeclaration): KSType? {
  val name: String? = base.qualifiedName?.asString()
  return superTypes.map { it.resolve() }
    .firstOrNull { it.declaration.qualifiedName?.asString() == name }
    ?: getAllSuperTypes().firstOrNull { it.declaration.qualifiedName?.asString() == name }
}

/**
 * ADR-199: this generic-route sealed type's C# parameters: its own for a root, the arm rule's
 * against its parent for an intermediate arm (`Lapse<T>` for `Lapse : Outcome<Nothing>`).
 */
internal fun KSClassDeclaration.forwardSealedCsParameters(): List<ForwardSealedArmParameter> {
  val parent: KSClassDeclaration? = forwardArmSealedParent()?.takeIf { it.isGenericSealedType() }
  if (parent != null) return forwardSealedArmShape(parent)?.parameters.orEmpty()
  return typeParameters.mapIndexed { index, parameter ->
    val name: String = forwardCsharpTypeParameterName(parameter)
    ForwardSealedArmParameter(name, parameter, index, parameter)
  }
}

/**
 * ADR-199: [arm]'s C# parameter list against its generic-route sealed [base], by the arm rule, read
 * over the base's C# parameters: one the base's Kotlin parameter fills follows rules 1 to 3, and a
 * phantom of the base passes through as a phantom of the arm. Null for a base off that route.
 */
internal fun KSClassDeclaration.forwardSealedArmShape(
  base: KSClassDeclaration,
): ForwardSealedArmShape? {
  if (!base.isGenericSealedType()) return null
  val supertype: KSType = sealedSupertype(base) ?: return null
  val ownNames: Set<String> = typeParameters.map { it.name.asString() }.toSet()
  val listed: MutableList<ForwardSealedArmParameter> = mutableListOf()
  fun phantom(
    of: ForwardSealedArmParameter,
    index: Int,
    fixed: KSType?,
  ): ForwardSealedArmParameter {
    // Named after the base parameter, renamed around any name the arm already uses.
    val taken: Set<String> = ownNames + listed.map { it.csharpName }
    var name: String = of.csharpName
    while (name in taken) name = "T$name"
    return ForwardSealedArmParameter(name, own = null, baseIndex = index, of.source, fixed)
      .also { listed += it }
  }
  val arguments: List<ForwardSealedBaseArgument> =
    base.forwardSealedCsParameters().mapIndexed { index, slot ->
      val baseOwn: KSTypeParameter = slot.own
        ?: return@mapIndexed ForwardSealedBaseArgument.Listed(phantom(slot, index, slot.fixed))
      val position: Int =
        base.typeParameters.indexOfFirst { it.name.asString() == baseOwn.name.asString() }
      val argument: KSType? = supertype.arguments.getOrNull(position)?.type?.resolve()
      val own: KSTypeParameter? = (argument?.declaration as? KSTypeParameter)
        ?.takeIf { named -> typeParameters.any { it.name.asString() == named.name.asString() } }
        ?.takeIf { named -> listed.none { it.own?.name?.asString() == named.name.asString() } }
      if (own != null && argument?.isMarkedNullable == false) {
        val ownParameter: KSTypeParameter =
          typeParameters.first { it.name.asString() == own.name.asString() }
        val entry = ForwardSealedArmParameter(
          forwardCsharpTypeParameterName(ownParameter), ownParameter, index, ownParameter,
        )
        listed += entry
        return@mapIndexed ForwardSealedBaseArgument.Listed(entry)
      }
      val closed: Boolean = argument != null && baseOwn.variance == Variance.INVARIANT &&
          !argument.isNothing() && !argument.mentionsAnyTypeParameter()
      if (closed) return@mapIndexed ForwardSealedBaseArgument.Closed(checkNotNull(argument))
      ForwardSealedBaseArgument.Listed(phantom(slot, index, argument))
    }
  val listedOwn: Set<String> = listed.mapNotNull { it.own?.name?.asString() }.toSet()
  return ForwardSealedArmShape(
    parameters = listed,
    baseArguments = arguments,
    unrecoverable = ownNames - listedOwn,
  )
}

/** `kotlin.Nothing`, the bottom type a fixed variant argument usually is. */
internal fun KSType.isNothing(): Boolean =
  declaration.qualifiedName?.asString() == "kotlin.Nothing"

internal fun KSType.mentionsAnyTypeParameter(): Boolean {
  if (declaration is KSTypeParameter) return true
  return arguments.any { argument -> argument.type?.resolve()?.mentionsAnyTypeParameter() == true }
}

/**
 * ADR-199: the own type parameter of a generic sealed arm that its C# declaration cannot recover
 * (`U` in `Both<T, U> : Outcome<T>`), or null when [parameter] is listed.
 */
internal fun KSTypeParameter.isUnrecoverableArmParameter(): Boolean {
  val arm: KSClassDeclaration = parentDeclaration as? KSClassDeclaration ?: return false
  val base: KSClassDeclaration = arm.forwardArmSealedParent() ?: return false
  if (!base.isGenericSealedType()) return false
  val shape: ForwardSealedArmShape = arm.forwardSealedArmShape(base) ?: return false
  return name.asString() in shape.unrecoverable
}

/**
 * ADR-199: whether `NugetMarshal.FromHandle<T>` can read this type argument of a generic sealed
 * reference, ADR-147's rule for `T`: a primitive, `Char`, `String`, an exported class, object,
 * interface or enum, a boxed value class, a type parameter in scope, another generic sealed
 * instantiation, or any of those nullable.
 */
internal fun BridgeType.isErasedSealedArgument(): Boolean = when (this) {
  is BridgeType.Primitive, BridgeType.Char, BridgeType.String, is BridgeType.Enum,
  is BridgeType.ObjectHandle, is BridgeType.Interface, is BridgeType.TypeParameter,
    -> true

  is BridgeType.ValueClass -> typeArguments.isEmpty() && hasErasedCrossing()
  is BridgeType.Nullable -> type.isErasedSealedArgument()
  BridgeType.Unit,
  BridgeType.Instant,
  BridgeType.Duration,
  is BridgeType.Throwable,
  BridgeType.Uuid,
  is BridgeType.BoundInterface,
  BridgeType.ByteArray,
  is BridgeType.Collection,
  is BridgeType.Callback,
  is BridgeType.ReturnedLambda,
  is BridgeType.SpecializedProtocol,
  is BridgeType.RawKSType,
  is BridgeType.Unsupported,
  is BridgeType.RawCollection,
    -> false
}

/**
 * ADR-199: a type argument as the Kotlin half reads a generic sealed handle at (`kotlin.Int`,
 * `pkg.Outcome<kotlin.Int>`). A type parameter in scope erases to its bound, as ADR-147 erases the
 * generic owner that declares it.
 */
internal fun KSType.forwardKotlinArgumentSpelling(): String {
  val parameter: KSTypeParameter? = declaration as? KSTypeParameter
  if (parameter != null) {
    val bound: String = parameter.forwardBoundSpellings().firstOrNull() ?: "Any"
    val nullable: Boolean = isMarkedNullable || parameter.hasNullableBound()
    return bound + if (nullable) "?" else ""
  }
  return forwardKotlinBoundSpelling() + if (isMarkedNullable) "?" else ""
}

/**
 * ADR-199: this class star-projected (`pkg.Outcome<*>`, `pkg.Duel.Flip<*, *>`), the spelling a
 * read that needs no type argument takes. The bare qualified name for a non-generic class.
 */
internal fun KSClassDeclaration.forwardStarSpelling(): String {
  val name: String = qualifiedName?.asString() ?: simpleName.asString()
  if (typeParameters.isEmpty()) return name
  return "$name<${typeParameters.joinToString(", ") { "*" }}>"
}

/** ADR-199: every intermediate sealed arm below this generic sealed type, depth first. */
internal fun KSClassDeclaration.intermediateGenericSealedArms(): List<KSClassDeclaration> {
  if (!isGenericSealedType()) return emptyList()
  return getSealedSubclasses()
    .filter { arm -> arm.isIntermediateGenericSealedArm() }
    .flatMap { arm -> listOf(arm) + arm.intermediateGenericSealedArms() }
    .toList()
}
