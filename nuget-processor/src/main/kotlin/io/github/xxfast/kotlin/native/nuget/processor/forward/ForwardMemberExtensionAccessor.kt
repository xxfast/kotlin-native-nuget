package io.github.xxfast.kotlin.native.nuget.processor.forward

import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.isAbstract
import com.google.devtools.ksp.symbol.FunctionKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFunction
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSName
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeParameter
import com.google.devtools.ksp.symbol.KSTypeReference
import com.google.devtools.ksp.symbol.KSValueParameter
import com.google.devtools.ksp.symbol.KSVisitor
import com.google.devtools.ksp.symbol.Visibility
import io.github.xxfast.kotlin.native.nuget.processor.cir.expandAliases
import io.github.xxfast.kotlin.native.nuget.processor.exports.isForwardFlowType

/**
 * ADR-238: an extension property declared inside a class (`class Groomhand { val Loafer.nick }`)
 * is a pair of instance methods in C#, Kotlin/JVM's own lowering, since a C# property takes no
 * parameter: `GetNick(Loafer receiver)`, and for a `var` `SetMood(Loafer receiver, int value)`.
 * A `Flow` family one is the flow method `Stream(Loafer receiver)`. Each accessor is read as the
 * member function it lowers to, so the class method and flow routes carry it unchanged; every
 * [KSDeclaration] question (package, KDoc, location, annotations) is the property's own.
 */
internal class ForwardMemberExtensionAccessor(
  val property: KSPropertyDeclaration,
  val setter: Boolean,
) : KSFunctionDeclaration, KSDeclaration by property {
  /** The Kotlin name the call reads or writes, `nick` in `receiver.nick`. */
  val propertyName: String = property.simpleName.asString()

  private val flow: Boolean = property.type.resolve().expandAliases().isForwardFlowType()

  override val simpleName: KSName = accessorName(
    when {
      setter -> "set" + propertyName.replaceFirstChar { it.uppercase() }
      flow -> propertyName
      else -> "get" + propertyName.replaceFirstChar { it.uppercase() }
    },
  )
  override val functionKind: FunctionKind = FunctionKind.MEMBER
  override val isAbstract: Boolean = property.isAbstract()
  override val extensionReceiver: KSTypeReference? = property.extensionReceiver
  override val returnType: KSTypeReference? = if (setter) null else property.type
  override val parameters: List<KSValueParameter> =
    if (setter) listOfNotNull(property.setter?.parameter) else emptyList()
  override val declarations: Sequence<KSDeclaration> = emptySequence()
  override fun findOverridee(): KSDeclaration? = null
  // The property's type as a member of [containing], as the accessor's result (or setter value).
  override fun asMemberOf(containing: KSType): KSFunction {
    val type: KSType = property.asMemberOf(containing)
    val receiver: KSType? = property.extensionReceiver?.resolve()
    val parameters: List<KSTypeParameter> = property.typeParameters
    val setter: Boolean = setter
    return object : KSFunction {
      override val returnType: KSType? = if (setter) null else type
      override val parameterTypes: List<KSType?> = if (setter) listOf(type) else emptyList()
      override val typeParameters: List<KSTypeParameter> = parameters
      override val extensionReceiverType: KSType? = receiver
      override val isError: Boolean = false
    }
  }
  override fun <D, R> accept(visitor: KSVisitor<D, R>, data: D): R = property.accept(visitor, data)
  override fun equals(other: Any?): Boolean =
    other is ForwardMemberExtensionAccessor && other.property == property && other.setter == setter
  override fun hashCode(): Int = property.hashCode() * 31 + setter.hashCode()
}

private fun accessorName(name: String): KSName = object : KSName {
  override fun asString(): String = name
  override fun getQualifier(): String = ""
  override fun getShortName(): String = name
}

/** ADR-238: whether [this] is an extension property declared inside a class-like owner. */
internal fun KSPropertyDeclaration.isMemberExtension(): Boolean =
  extensionReceiver != null && parentDeclaration is KSClassDeclaration

/** ADR-238: [this] class's properties that are not member extensions, which lower to methods. */
internal fun KSClassDeclaration.getAllPlainProperties(): Sequence<KSPropertyDeclaration> =
  getAllProperties().filterNot { property -> property.isMemberExtension() }

/**
 * ADR-238: [this] class's public member extension properties as their accessor functions: a
 * getter (or, for a `Flow` family type, the flow method), then a setter for a `var` whose setter
 * is public. [flow] picks the `Flow` family ones (the class flow route) or the rest (the class
 * method route); null takes both, for an owner whose planner walk routes both kinds.
 */
internal fun KSClassDeclaration.forwardMemberExtensionAccessors(
  flow: Boolean? = null,
): List<ForwardMemberExtensionAccessor> = declarations
  .filterIsInstance<KSPropertyDeclaration>()
  .filter { property -> property.isMemberExtension() }
  .filter { property -> property.getVisibility() == Visibility.PUBLIC }
  .filter { property ->
    flow == null || property.type.resolve().expandAliases().isForwardFlowType() == flow
  }
  .flatMap { property ->
    val isFlow: Boolean = property.type.resolve().expandAliases().isForwardFlowType()
    val settable: Boolean = !isFlow && property.isMutable &&
      property.setter?.let { it.modifiers.none { modifier -> modifier.name == "PRIVATE" } } != false
    listOfNotNull(
      ForwardMemberExtensionAccessor(property, setter = false),
      ForwardMemberExtensionAccessor(property, setter = true).takeIf { settable },
    )
  }
  .toList()
