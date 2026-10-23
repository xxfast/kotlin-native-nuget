package io.github.xxfast.kotlin.native.nuget.processor.exports

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

/**
 * ADR-230: how a static flow member is reached. [stem] is the planner's export stem; [qualifier]
 * spells the Kotlin call's qualifier (`pkg.Clock.`, `pkg.` for the top level) from the receiver
 * local's name, unused when [receiver] is null.
 */
internal data class ForwardStaticFlowCall(
  val stem: String,
  val qualifier: (String) -> String,
  val receiver: ForwardStaticSuspendReceiver?,
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
  val receiverParameter: ParameterSpec?,
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
  val read: String = when (val receiver = receiver) {
    is ForwardStaticSuspendReceiver.Handle -> names.obj
    is ForwardStaticSuspendReceiver.Enum -> "${receiver.kotlinType}.entries[$slot]"
    is ForwardStaticSuspendReceiver.Scalar, null -> slot
  }
  val prelude: String = when (val receiver = receiver) {
    is ForwardStaticSuspendReceiver.Handle ->
      legacyHandleStatement(slot, names.obj, receiver.type, receiver.nullable) + "\n"
    is ForwardStaticSuspendReceiver.Scalar, is ForwardStaticSuspendReceiver.Enum, null -> ""
  }
  val parameter: ParameterSpec? = when (val receiver = receiver) {
    is ForwardStaticSuspendReceiver.Handle ->
      ParameterSpec.builder(slot, cOpaquePointer.copy(nullable = receiver.nullable)).build()
    is ForwardStaticSuspendReceiver.Scalar ->
      ParameterSpec.builder(slot, ClassName.bestGuess(receiver.kotlinType)).build()
    is ForwardStaticSuspendReceiver.Enum -> ParameterSpec.builder(slot, INT).build()
    null -> null
  }
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
  slots.receiverParameter?.let { parameter -> builder.parameters.add(0, parameter) }
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
    is ForwardStaticFlowOwner.ValueClass -> {
      importIfDefaultPackage(owner.valueClass)
      val qualified: String = owner.valueClass.kotlinQualifiedReference()
      ForwardStaticFlowCall(owner.stem, { read -> "$qualified($read)." }, owner.underlying)
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
