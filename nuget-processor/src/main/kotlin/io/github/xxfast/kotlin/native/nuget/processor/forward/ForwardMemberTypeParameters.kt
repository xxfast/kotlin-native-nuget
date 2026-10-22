package io.github.xxfast.kotlin.native.nuget.processor.forward

import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeParameter
import io.github.xxfast.kotlin.native.nuget.processor.cir.FLOW_TYPES
import io.github.xxfast.kotlin.native.nuget.processor.cir.LAMBDA_TYPES
import io.github.xxfast.kotlin.native.nuget.processor.cir.STATE_FLOW_TYPES
import io.github.xxfast.kotlin.native.nuget.processor.cir.SUSPEND_LAMBDA_TYPES
import io.github.xxfast.kotlin.native.nuget.processor.cir.expandAliases

/**
 * ADR-197: whether this function's own type parameters classify as [BridgeType.TypeParameter]: a
 * member (not an extension) of a class, a sealed class, an object or a companion. An interface,
 * an enum and a value class keep the named refusal. ADR-211 / ADR-213: a top-level function is an
 * owner too; the width-variant route it used to share is gone.
 */
internal fun KSFunctionDeclaration.isForwardGenericMemberOwner(): Boolean {
  if (extensionReceiver != null) return false
  if (parentDeclaration == null) return true
  val owner: KSClassDeclaration = parentDeclaration as? KSClassDeclaration ?: return false
  return when (owner.classKind) {
    ClassKind.CLASS -> !owner.isValueClass()
    ClassKind.OBJECT -> true
    else -> false
  }
}

/**
 * ADR-197: the C# spelling of one of this member's own type parameters. Its own name, unless it
 * is the member's own C# name or its owner's (CS0694), or one of the owner's type parameters as C#
 * spells them; then prefixed with `T` until it clashes with nothing, the rule
 * [forwardCsharpTypeParameterName] applies to a class's parameters.
 */
internal fun KSFunctionDeclaration.forwardCsharpMethodTypeParameterName(
  parameter: KSTypeParameter,
): String {
  val name: String = parameter.simpleName.asString()
  val owner: KSClassDeclaration? = parentDeclaration as? KSClassDeclaration
  val taken: Set<String> = buildSet {
    add(csharpMemberName())
    owner?.let { cls ->
      add(cls.simpleName.asString())
      // ADR-196: an inner class of a generic owner declares the parameters it captures too.
      cls.forwardTypeParametersInScope().forEach { (declaring, ownerParameter) ->
        add(declaring.forwardCsharpTypeParameterName(ownerParameter))
      }
    }
  }
  if (name !in taken) return name
  val siblings: Set<String> = typeParameters.map { it.simpleName.asString() }.toSet()
  var candidate = "T$name"
  while (candidate in taken || candidate in siblings) candidate = "T$candidate"
  return candidate
}

/**
 * ADR-197: this member's own type parameters as the plan carries them, without their C#
 * constraints (filled after planning, see [ForwardMethodTypeParameter.constraints]).
 */
internal fun KSFunctionDeclaration.forwardMethodTypeParameters(): List<ForwardMethodTypeParameter> =
  typeParameters.map { parameter ->
    val bounds: List<String> = parameter.forwardBoundSpellings()
    val nullable: String = if (parameter.hasNullableBound()) "?" else ""
    val kotlinName: String = parameter.simpleName.asString()
    // ADR-198: no erased argument is within an unspellable bound; the call names the trampoline's
    // own type variable instead, which re-declares the bounds.
    if (parameter.hasUnspellableBound()) {
      return@map ForwardMethodTypeParameter(
        name = forwardCsharpMethodTypeParameterName(parameter),
        kotlinName = kotlinName,
        kotlinTypeArgument = kotlinName,
        trampolineBounds =
          parameter.bounds
            .map { bound -> bound.resolve().forwardKotlinDeclaredSpelling() }
            .toList(),
      )
    }
    ForwardMethodTypeParameter(
      name = forwardCsharpMethodTypeParameterName(parameter),
      kotlinName = kotlinName,
      kotlinTypeArgument = if (bounds.size > 1) "_" else (bounds.firstOrNull() ?: "Any") + nullable,
    )
  }

/**
 * ADR-197: why this member's own type parameters do not route, or null when they do (and always
 * null for a member that declares none). The route carries a `T` where ADR-147's boxed wire
 * already does: a bare `T` or `T?` parameter or return, inside a `List`, `Set` or `Map` (ADR-212),
 * inside an exported generic class (ADR-216), or inside a `Result` return's payload. The
 * refusal is the shipped structural `GENERIC` skip, named on its owner.
 *
 * Refused, each a shape the route cannot spell:
 *  - an extension member, and a member of an owner [isForwardGenericMemberOwner] declines;
 *  - a `T` that shadows an owner's (C# reads that as CS0693, which a warnings-as-errors build
 *    fails on);
 *  - a `reified` `T`, which the boxed call would silently instantiate at its erased bound;
 *  - a bound that is itself a type parameter, which no erased argument satisfies (a bound with
 *    no closed spelling, `Enum<T>`, `Node<T>`, routes through ADR-198's trampoline instead);
 *  - a multi-bound `T` no parameter mentions, since no type argument spells it and nothing infers
 *    it, and (ADR-216, until ADR-217) one witnessed only inside a generic class;
 *  - a `T` nested in a type that is neither a collection, an exported generic class nor a
 *    non-suspend lambda (ADR-215) over them (a value class, `kotlin.Array`), and a suspend lambda
 *    or `Flow` anywhere in the signature, whose own routes are keyed to non-generic members.
 */
internal fun KSFunctionDeclaration.forwardMemberGenericRefusal(): String? {
  if (typeParameters.isEmpty()) return null
  if (!isForwardGenericMemberOwner()) {
    val owner: KSClassDeclaration? = parentDeclaration as? KSClassDeclaration
    return when {
      extensionReceiver != null -> "it is an extension function"
      owner == null -> "it is not a member of a class"
      owner.classKind == ClassKind.INTERFACE -> "it is declared on an interface"
      owner.classKind == ClassKind.ENUM_CLASS -> "it is declared on an enum class"
      owner.isValueClass() -> "it is declared on a value class"
      else -> "its owner has no generic-method route"
    }
  }
  // ADR-196: the owner's own parameters and those an `inner` owner captures from a generic outer;
  // a nested (non-inner) class sees none of its outer's, in Kotlin or on its C# holder.
  // ADR-211: a top-level function has no owner, so none in scope.
  val ownerNames: Set<String> = (parentDeclaration as? KSClassDeclaration)
    ?.forwardTypeParametersInScope()
    .orEmpty()
    .map { (_, parameter) -> parameter.simpleName.asString() }
    .toSet()
  val own: Set<String> = typeParameters.map { it.simpleName.asString() }.toSet()
  typeParameters.forEach { parameter ->
    val name: String = parameter.simpleName.asString()
    if (name in ownerNames) return "its type parameter `$name` shadows its owner's"
    // ADR-213: a top-level `reified T` reifies at its bound (`Any?` when unbounded), the explicit
    // type argument the plan passes; a member one has never bound and keeps the refusal.
    if (parameter.isReified && parentDeclaration != null) {
      return "its type parameter `$name` is reified"
    }
    parameter.bounds.forEach { bound ->
      val resolved: KSType = bound.resolve()
      if (resolved.declaration is KSTypeParameter) {
        return "its type parameter `$name` is bounded by another type parameter"
      }
      // ADR-198: an `Enum<T>` (or any other self-referencing) bound routes through the trampoline.
    }
  }

  fun KSType.ownParameter(): Boolean =
    (declaration as? KSTypeParameter)?.simpleName?.asString() in own

  fun KSType.mentionsOwn(): Boolean = ownParameter() ||
      arguments.any { argument -> argument.type?.resolve()?.expandAliases()?.mentionsOwn() == true }

  // ADR-212: a `List`/`Set`/`Map` (or a mutable kind) whose every component is the own `T`, a
  // type that does not mention it, or such a collection again, carries `T` per element.
  // ADR-216: so does an exported generic class (`Box<T>`, a generic sealed `Outcome<T>`); the
  // classifier's `genericReference` owns every further refusal (a projection, an unexported
  // class, an argument the erased wire cannot read).
  // ADR-215 lane A: so does a non-suspend lambda, whose payloads and result ride the plan's
  // callback on the boxed `Object` wire.
  fun KSType.carriesOwn(): Boolean = ownParameter() ||
      (declaration.qualifiedName?.asString() in OWN_PARAMETER_COLLECTIONS ||
          declaration.isOwnParameterCarrierClass() || isPlainLambda()) &&
      arguments.all { argument ->
        val type: KSType? = argument.type?.resolve()?.expandAliases()
        type != null && (!type.mentionsOwn() || type.carriesOwn())
      }

  // ADR-215 lane A: a non-suspend lambda is no longer a protocol here; its components are still
  // walked, so a `Flow` or a suspend lambda inside one keeps the refusal.
  fun KSType.carriesProtocol(): Boolean {
    val qualifiedName: String? = declaration.qualifiedName?.asString()
    return isSuspendFunctionType || qualifiedName in SUSPEND_LAMBDA_TYPES ||
        qualifiedName in FLOW_TYPES || qualifiedName in STATE_FLOW_TYPES ||
        arguments.any { argument ->
          argument.type?.resolve()?.expandAliases()?.carriesProtocol() == true
        }
  }

  val parameterTypes: List<KSType> =
    parameters.map { parameter -> parameter.type.resolve().expandAliases() }
  val returned: KSType? = returnType?.resolve()?.expandAliases()
  if ((parameterTypes + listOfNotNull(returned)).any { type -> type.carriesProtocol() }) {
    return "its signature carries a suspend lambda or a Flow"
  }
  parameterTypes.forEach { type ->
    if (type.mentionsOwn() && !type.carriesOwn()) {
      return "its type parameter is nested in `$type`"
    }
  }
  if (returned != null && returned.mentionsOwn() && !returned.carriesOwn()) {
    val resultPayload: KSType? = returned
      .takeIf { type -> type.declaration.qualifiedName?.asString() == "kotlin.Result" }
      ?.arguments?.singleOrNull()?.type?.resolve()?.expandAliases()
    // ADR-216: any payload the gate admits elsewhere, `List<T>` and `Box<T>` included.
    if (resultPayload?.carriesOwn() != true) return "its type parameter is nested in `$returned`"
  }
  typeParameters
    .filter { parameter -> parameter.forwardBoundSpellings().size > 1 }
    .forEach { parameter ->
      val name: String = parameter.simpleName.asString()
      fun KSType.isNamed(): Boolean =
        (declaration as? KSTypeParameter)?.simpleName?.asString() == name
      fun KSType.mentions(): Boolean = isNamed() ||
          arguments.any { argument ->
            argument.type?.resolve()?.expandAliases()?.mentions() == true
          }
      // ADR-212: a collection of `T` witnesses it too; its elements carry the intersection.
      // ADR-216: a generic class does not: an invariant `Box<A>` cannot infer `T : A, T : B`.
      fun KSType.witnesses(): Boolean = isNamed() ||
          declaration.qualifiedName?.asString() in OWN_PARAMETER_COLLECTIONS &&
          arguments.any { argument ->
            argument.type?.resolve()?.expandAliases()?.witnesses() == true
          }
      if (parameterTypes.any { type -> type.witnesses() }) return@forEach
      val nesting: KSType = parameterTypes.firstOrNull { type -> type.mentions() }
        ?: return "its multi-bound type parameter `$name` is mentioned by no parameter"
      return "its multi-bound type parameter `$name` is witnessed only inside `$nesting`, " +
          "which ADR-217 lifts"
    }
  return null
}

/**
 * ADR-216: an exported generic class a member's own type parameter may sit inside: a class (a
 * sealed one too) with type parameters, not a value class (which has no C# type when generic) and
 * not in `kotlin` (`kotlin.Array` keeps its `GENERIC` refusal; `Result` has its own exemption).
 */
private fun KSDeclaration.isOwnParameterCarrierClass(): Boolean {
  val cls: KSClassDeclaration = this as? KSClassDeclaration ?: return false
  if (cls.typeParameters.isEmpty() || cls.classKind != ClassKind.CLASS || cls.isValueClass()) {
    return false
  }
  val pkg: String = cls.packageName.asString()
  return pkg != "kotlin" && !pkg.startsWith("kotlin.")
}

/** ADR-212: the collection kinds whose components may be a member's own type parameter. */
private val OWN_PARAMETER_COLLECTIONS: Set<String> = setOf(
  "kotlin.collections.List", "kotlin.collections.MutableList",
  "kotlin.collections.Set", "kotlin.collections.MutableSet",
  "kotlin.collections.Map", "kotlin.collections.MutableMap",
)

/**
 * ADR-198: a `reified` type parameter whose bound has no closed spelling (`inline fun <reified T :
 * Enum<T>>`). The trampoline's `T` is not reified and neither is `Nothing`, so nothing the export
 * can name is a legal reified argument; the language has no way to call it here.
 */
internal fun KSFunctionDeclaration.isReifiedWithUnspellableBound(): Boolean =
  typeParameters.any { parameter -> parameter.isReified && parameter.isTrampolined() }

/** ADR-215 lane A: a non-suspend `kotlin.FunctionN`, the shape the plan's callback carries. */
private fun KSType.isPlainLambda(): Boolean =
  !isSuspendFunctionType &&
      (isFunctionType || declaration.qualifiedName?.asString() in LAMBDA_TYPES)
