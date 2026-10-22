package io.github.xxfast.kotlin.native.nuget.processor.forward

import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeParameter
import com.google.devtools.ksp.symbol.Modifier
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
  // ADR-229: the static suspend route boxes a member's own `T` on every owner it reaches, an
  // extension, an enum body and a value class included, as the top-level route does.
  val suspends: Boolean = Modifier.SUSPEND in modifiers ||
    // ADR-230: so does the static flow route, through ADR-215's trampoline.
    (extensionReceiver == null && isStaticFlowCandidate())
  // ADR-226: a top-level extension binds its own `T` on the top-level route.
  if (extensionReceiver != null) return suspends || parentDeclaration == null
  if (parentDeclaration == null) return true
  val owner: KSClassDeclaration = parentDeclaration as? KSClassDeclaration ?: return false
  return when (owner.classKind) {
    // ADR-225: a value class too, generic or not, suspending or not; its receiver is the struct.
    ClassKind.CLASS -> true
    ClassKind.OBJECT -> true
    ClassKind.ENUM_CLASS -> suspends
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
    // own type variable instead, which re-declares the bounds. ADR-217: so does an intersection
    // only a generic class witnesses (`crown(basket: Basket<T>) where T : Furry, T : Tricky`).
    if (parameter.hasUnspellableBound() || needsTypeVariable(parameter)) {
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
 *    it (one witnessed only inside a generic class takes the ADR-217 trampoline);
 *  - a `T` nested in a type that is neither a collection, an exported generic class, a lambda nor
 *    a `Flow` (ADR-215) over them (a value class, `kotlin.Array`).
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

  // ADR-220: a generic value class whose underlying is its own type parameter, instantiated at
  // the member's own `T` (or `T?`). ADR-226: under `?` too (a box handle when the underlying can
  // be null, the null pointer otherwise), and as a collection component.
  fun KSType.isOwnInsideGenericValueClass(): Boolean {
    val cls: KSClassDeclaration = declaration as? KSClassDeclaration ?: return false
    if (!cls.isValueClass() || cls.typeParameters.isEmpty()) return false
    val underlying: KSType =
      cls.primaryConstructor?.parameters?.singleOrNull()?.type?.resolve() ?: return false
    val index: Int = cls.typeParameters.indexOfFirst { parameter ->
      underlying.declaration is KSTypeParameter &&
          parameter.name.asString() == underlying.declaration.simpleName.asString()
    }
    if (index < 0) return false
    return arguments.withIndex().all { (position, argument) ->
      val type: KSType = argument.type?.resolve()?.expandAliases() ?: return false
      if (position == index) {
        type.ownParameter()
      } else {
        !type.mentionsOwn()
      }
    }
  }

  // ADR-212: a `List`/`Set`/`Map` (or a mutable kind) whose every component is the own `T`, a
  // type that does not mention it, or such a collection again, carries `T` per element.
  // ADR-216: so does an exported generic class (`Box<T>`, a generic sealed `Outcome<T>`); the
  // classifier's `genericReference` owns every further refusal (a projection, an unexported
  // class, an argument the erased wire cannot read).
  // ADR-215 lane A: so does a non-suspend lambda, whose payloads and result ride the plan's
  // callback on the boxed `Object` wire. Lane C: so does every lambda and `Flow` (each
  // asynchronous route owns its own refusal of a component it cannot carry).
  fun KSType.carriesOwn(): Boolean = ownParameter() ||
      (declaration.qualifiedName?.asString() in OWN_PARAMETER_COLLECTIONS ||
          declaration.isOwnParameterCarrierClass() || isProtocolCarrier()) &&
      arguments.all { argument ->
        val type: KSType? = argument.type?.resolve()?.expandAliases()
        // ADR-228: and a generic value class over the own `T`, boxed through its one pair, inside a
        // class, lambda or `Flow` (a collection component keeps its own rule).
        type != null && (
          !type.mentionsOwn() || type.carriesOwn() ||
            type.isOwnInsideGenericValueClass()
          )
      }

  // ADR-222: a generic interface PARAMETER (`Keepsake<T>`) whose arguments are the own `T` or
  // do not mention it; its bridge reads the erased owner. Never a result (ADR-208's skip).
  fun KSType.isOwnInsideGenericInterface(): Boolean {
    val cls: KSClassDeclaration = declaration as? KSClassDeclaration ?: return false
    if (cls.classKind != ClassKind.INTERFACE || cls.typeParameters.isEmpty()) return false
    return arguments.all { argument ->
      val type: KSType = argument.type?.resolve()?.expandAliases() ?: return false
      type.ownParameter() || !type.mentionsOwn()
    }
  }

  val parameterTypes: List<KSType> =
    parameters.map { parameter -> parameter.type.resolve().expandAliases() }
  val returned: KSType? = returnType?.resolve()?.expandAliases()
  parameterTypes.forEach { type ->
    if (type.mentionsOwn() && !type.carriesOwn() && !type.isOwnInsideGenericValueClass() &&
      !type.isOwnInsideGenericInterface()) {
      return "its type parameter is nested in `$type`"
    }
  }
  if (returned != null && returned.mentionsOwn() && !returned.carriesOwn() &&
    !returned.isOwnInsideGenericValueClass()
  ) {
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
      // ADR-217: one witnessed only inside a generic class runs under the trampoline.
      if (parameterTypes.none { type -> type.mentionsTypeParameter(name) }) {
        return "its multi-bound type parameter `$name` is mentioned by no parameter"
      }
    }
  return null
}

/**
 * ADR-217: the type-variable rule for a member's (or a top-level function's) own `T`: a multi-bound
 * `T` that some parameter mentions but none witnesses runs under ADR-198's trampoline, whose type
 * variable re-declares every bound. A bare `T` witnesses the intersection, and so (ADR-212) does a
 * collection of it, whose elements carry it; a generic class does not, since an invariant
 * `Box<A>` cannot infer `T : A, T : B`. An unspellable bound takes the trampoline regardless.
 */
private fun KSFunctionDeclaration.needsTypeVariable(parameter: KSTypeParameter): Boolean {
  val name: String = parameter.simpleName.asString()
  val types: List<KSType> = parameters.map { it.type.resolve().expandAliases() }
  // ADR-215 lane C: the generated suspend lambda and flow declare their `T` component, and a
  // multi-bound or generic-bound `T` has no closed spelling there, so it takes the type variable.
  val bounds: List<String> = parameter.forwardBoundSpellings()
  val unspelled: Boolean = bounds.size > 1 || bounds.any { bound -> '<' in bound }
  if (unspelled && types.any { type -> type.carriesInsideAsync(name) }) return true
  if (bounds.size <= 1) return false
  return types.any { type -> type.mentionsTypeParameter(name) } &&
      types.none { type -> type.witnessesTypeParameter(name) }
}

/** ADR-215 lane C: whether `T` [name] sits inside a suspend lambda or a `Flow` in this type. */
private fun KSType.carriesInsideAsync(name: String): Boolean {
  val qualifiedName: String? = declaration.qualifiedName?.asString()
  val async: Boolean = isSuspendFunctionType || qualifiedName in SUSPEND_LAMBDA_TYPES ||
      qualifiedName in FLOW_TYPES || qualifiedName in STATE_FLOW_TYPES
  return if (async) mentionsTypeParameter(name) else arguments.any { argument ->
    argument.type?.resolve()?.expandAliases()?.carriesInsideAsync(name) == true
  }
}

private fun KSType.namesTypeParameter(name: String): Boolean =
  (declaration as? KSTypeParameter)?.simpleName?.asString() == name

internal fun KSType.mentionsTypeParameter(name: String): Boolean = namesTypeParameter(name) ||
    arguments.any { argument ->
      argument.type?.resolve()?.expandAliases()?.mentionsTypeParameter(name) == true
    }

private fun KSType.witnessesTypeParameter(name: String): Boolean = namesTypeParameter(name) ||
    declaration.qualifiedName?.asString() in OWN_PARAMETER_COLLECTIONS &&
    arguments.any { argument ->
      argument.type?.resolve()?.expandAliases()?.witnessesTypeParameter(name) == true
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

/**
 * ADR-215: a lambda (lane A, the plan's callback), a suspend lambda or a `Flow` (lane C, the legacy
 * suspend and `Flow` routes), each of which carries a `T` component on its own boxed wire.
 */
private fun KSType.isProtocolCarrier(): Boolean {
  val qualifiedName: String? = declaration.qualifiedName?.asString()
  return isFunctionType || isSuspendFunctionType || qualifiedName in LAMBDA_TYPES ||
      qualifiedName in SUSPEND_LAMBDA_TYPES || qualifiedName in FLOW_TYPES ||
      qualifiedName in STATE_FLOW_TYPES
}
