package io.github.xxfast.kotlin.native.nuget.processor.exports

import io.github.xxfast.kotlin.native.nuget.processor.forward.kotlinIdentifier
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.TypeName
import io.github.xxfast.kotlin.native.nuget.processor.forward.BridgeType
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardLegacyParameterShape
import io.github.xxfast.kotlin.native.nuget.processor.forward.isNullableFlowSource
import io.github.xxfast.kotlin.native.nuget.processor.freshName
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardBridgeTypeClassifier
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardCallablePlanCatalog
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardLegacyNames
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardStaticFlowMember
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardStaticFlowOwner
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardStaticSuspendReceiver
import io.github.xxfast.kotlin.native.nuget.processor.forward.importIfDefaultPackage
import io.github.xxfast.kotlin.native.nuget.processor.forward.kotlinPackageReference
import io.github.xxfast.kotlin.native.nuget.processor.forward.legacyHandleStatement
import io.github.xxfast.kotlin.native.nuget.processor.forward.forwardBoundedRead
import com.squareup.kotlinpoet.MemberName
import io.github.xxfast.kotlin.native.nuget.processor.forward.forwardExtensionImportAlias
import io.github.xxfast.kotlin.native.nuget.processor.forward.legacyArgument
import io.github.xxfast.kotlin.native.nuget.processor.forward.legacyPrelude

/**
 * ADR-230: how a static flow member is reached. [stem] is the planner's export stem; [qualifier]
 * spells the Kotlin call's qualifier (`pkg.Clock.`, `pkg.` for the top level) from the receiver
 * local's name, unused when [receiver] is null.
 */
internal data class ForwardStaticFlowCall(
  val stem: String,
  val qualifier: (String) -> String,
  val receiver: ForwardStaticSuspendReceiver?,
  // ADR-238: the name the call spells in place of the member's own (an extension's import alias).
  val name: String? = null,
)

/**
 * ADR-230: what [FunSpec.staticReceived] rewrites on an instance-shaped flow export: the owner
 * read and the scope read go, the receiver slot (if any) comes in first.
 */
internal data class ForwardStaticFlowSlots(
  val callee: String,
  val ownerRead: String,
  val receiverPrelude: String,
  val scopeRead: String,
  val scopeRoot: String,
  val scopeHandle: String,
  val receiverParameter: List<ParameterSpec>,
)

internal fun ForwardStaticFlowCall.slots(
  qualifiedName: String,
  names: ForwardLegacyNames,
  method: KSFunctionDeclaration,
): ForwardStaticFlowSlots {
  val slot: String = freshName(
    "receiver",
    method.legacyParameterNames().toSet() +
      setOf(names.onNext, names.onComplete, names.onError, names.userData, names.obj, names.scope,
        names.scopeHandle),
  )
  // ADR-238: an extension receiver on the parameter path is parameter zero of its own name set,
  // the member's parameters and the fixed slots reserved so no lowered local meets them.
  val reserved: List<String> = method.legacyParameterNames() + listOf(
    names.onNext, names.onComplete, names.onError, names.userData, names.obj, names.scope,
    names.scopeHandle,
  )
  val receiverNames = ForwardLegacyNames(
    listOf(slot) + reserved,
    listOf(
      (receiver as? ForwardStaticSuspendReceiver.Parameter)?.shape
        ?: ForwardLegacyParameterShape.Plain,
    ) +
      reserved.map { ForwardLegacyParameterShape.Plain },
  )
  val read: String = when (val receiver = receiver) {
    is ForwardStaticSuspendReceiver.Handle, is ForwardStaticSuspendReceiver.Box -> names.obj
    is ForwardStaticSuspendReceiver.Wire -> receiver.shape.view?.kotlinWrap(names.obj) ?: names.obj
    is ForwardStaticSuspendReceiver.Enum -> "${receiver.kotlinType}.entries[$slot]"
    is ForwardStaticSuspendReceiver.Scalar, null -> slot
    is ForwardStaticSuspendReceiver.Parameter ->
      "(${receiverNames.legacyArgument(0, slot.kotlinIdentifier())})"
  }
  val prelude: String = when (val receiver = receiver) {
    is ForwardStaticSuspendReceiver.Handle ->
      legacyHandleStatement(slot, names.obj, receiver.type, receiver.nullable) + "\n"
    // ADR-228: a generic value class's own `T` box, read through its bound checks first.
    is ForwardStaticSuspendReceiver.Box -> {
      val bounds: List<String> = listOfNotNull(receiver.type.boundQualifiedName) +
        receiver.type.additionalBounds
      // ADR-228: a `T` with no erased spelling reads as the trampoline's own variable.
      val variable: String? =
        receiver.type.kotlinName.takeIf { receiver.trampolineBounds.isNotEmpty() }
      "val ${names.obj} = ${forwardBoundedRead(slot, bounds, receiver.nullable, variable)}\n"
    }
    is ForwardStaticSuspendReceiver.Wire -> receiver.wirePrelude(slot, names.obj) + "\n"
    is ForwardStaticSuspendReceiver.Scalar, is ForwardStaticSuspendReceiver.Enum, null -> ""
    is ForwardStaticSuspendReceiver.Parameter -> receiverNames.legacyPrelude(listOf(slot))
  }
  val parameter: List<ParameterSpec> = when (val receiver = receiver) {
    is ForwardStaticSuspendReceiver.Wire ->
      return slotsOf(qualifiedName, names, read, prelude, receiver.wireParameters(slot))
    is ForwardStaticSuspendReceiver.Handle ->
      ParameterSpec.builder(slot, cOpaquePointer.copy(nullable = receiver.nullable)).build()
    is ForwardStaticSuspendReceiver.Scalar ->
      ParameterSpec.builder(slot, ClassName.bestGuess(receiver.kotlinType)).build()
    is ForwardStaticSuspendReceiver.Enum -> ParameterSpec.builder(slot, INT).build()
    is ForwardStaticSuspendReceiver.Box ->
      ParameterSpec.builder(slot, cOpaquePointer.copy(nullable = receiver.nullable)).build()
    is ForwardStaticSuspendReceiver.Parameter -> return slotsOf(
      qualifiedName, names, read, prelude,
      FunSpec.builder("receiver").addLegacySlots(slot, 0, receiver.shape, receiverNames) {
        receiver.type.toBridgeTypeName(nullable = false)
      }.parameters,
    )
    null -> null
  }.let(::listOfNotNull)
  return slotsOf(qualifiedName, names, read, prelude, parameter)
}

private fun ForwardStaticFlowCall.slotsOf(
  qualifiedName: String,
  names: ForwardLegacyNames,
  read: String,
  prelude: String,
  parameter: List<ParameterSpec>,
): ForwardStaticFlowSlots {
  return ForwardStaticFlowSlots(
    callee = qualifier(read),
    ownerRead = "val ${names.obj} = handle.asStableRef<$qualifiedName>().get()\n",
    receiverPrelude = prelude,
    scopeRead = "val ${names.scope} = ${names.scopeHandle}.asStableRef<CoroutineScope>().get()",
    scopeRoot = "val ${names.scope} = CoroutineScope(Dispatchers.Default)",
    scopeHandle = names.scopeHandle,
    receiverParameter = parameter,
  )
}

/**
 * ADR-230: [this] instance-shaped flow export over a static receiver: no `handle`, no
 * `scopeHandle` (each collection launches on an ad-hoc root scope, the ADR-068 amendment's shape),
 * and the receiver slot first when the owner has one. Identity for an instance member.
 */
internal fun FunSpec.staticReceived(slots: ForwardStaticFlowSlots?): FunSpec {
  if (slots == null) return this
  val text: String = body.toString()
    .replace(slots.ownerRead, slots.receiverPrelude)
    .replace(slots.scopeRead, slots.scopeRoot)
  val builder: FunSpec.Builder = toBuilder().clearBody().addCode(CodeBlock.of("%L", text))
  builder.parameters.removeAll { parameter ->
    parameter.name == "handle" || parameter.name == slots.scopeHandle
  }
  builder.parameters.addAll(0, slots.receiverParameter)
  return builder.build()
}

/**
 * ADR-230: the Kotlin half of one static flow member: the instance Flow route's exports
 * ([addFlowMethodExports]) over the member's static receiver.
 */
internal fun FileSpec.Builder.addStaticFlowExports(
  member: ForwardStaticFlowMember,
  classifier: ForwardBridgeTypeClassifier,
  callableCatalog: ForwardCallablePlanCatalog,
) {
  val function: KSFunctionDeclaration = member.function
  val call: ForwardStaticFlowCall = when (val owner = member.owner) {
    is ForwardStaticFlowOwner.TopLevel -> {
      importIfDefaultPackage(function)
      val qualifier: String = kotlinPackageReference(function.packageName.asString())
      ForwardStaticFlowCall(owner.stem, { qualifier }, receiver = null)
    }
    is ForwardStaticFlowOwner.Object -> {
      importIfDefaultPackage(owner.obj)
      val qualified: String = owner.obj.kotlinQualifiedReference()
      ForwardStaticFlowCall(owner.stem, { "$qualified." }, receiver = null)
    }
    is ForwardStaticFlowOwner.Companion -> {
      importIfDefaultPackage(owner.owner)
      val qualified: String = owner.owner.kotlinQualifiedReference()
      ForwardStaticFlowCall(owner.stem, { "$qualified." }, receiver = null)
    }
    is ForwardStaticFlowOwner.EnumMember -> {
      importIfDefaultPackage(owner.enum)
      ForwardStaticFlowCall(owner.stem, { read -> "$read." }, owner.receiver)
    }
    // ADR-238: an extension is called on its receiver's read through its import alias.
    is ForwardStaticFlowOwner.Extension -> {
      val alias: String =
        forwardExtensionImportAlias(function.packageName.asString(), function.simpleName.asString())
      val member = MemberName(function.packageName.asString(), function.simpleName.asString())
      addAliasedImport(member, alias)
      ForwardStaticFlowCall(owner.stem, { read -> "$read." }, owner.receiver, name = alias)
    }
    is ForwardStaticFlowOwner.ValueClass -> {
      importIfDefaultPackage(owner.valueClass)
      val qualified: String = owner.valueClass.kotlinQualifiedReference()
      ForwardStaticFlowCall(
        owner.stem, { read -> "${owner.construction ?: qualified}($read)." }, owner.underlying,
      )
    }
  }
  addFlowMethodExports(
    function, qualifiedName = "", prefix = "", classifier, callableCatalog, owner = null,
    static = call,
  )
}

/**
 * ADR-210 amendment: the Kotlin type of one slot of the asynchronous parameter at [index]. Every
 * slot is a pointer; the key of a `Flow<T>?` is nullable, since a C# `null` passes a zero key.
 */
internal fun ForwardLegacyNames.asyncSlotType(index: Int, slot: String): TypeName {
  val type: BridgeType? = (shapes[index] as? ForwardLegacyParameterShape.Async)?.type
  val key: Boolean = asyncSlots[index]?.key == slot
  return if (key && type?.isNullableFlowSource() == true) cOpaquePointer.copy(nullable = true)
  else cOpaquePointer
}
