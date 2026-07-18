package io.github.xxfast.kotlin.native.nuget.processor.forward

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.TypeName
import io.github.xxfast.kotlin.native.nuget.processor.exports.cNameAnnotation
import io.github.xxfast.kotlin.native.nuget.processor.exports.cOpaquePointer
import io.github.xxfast.kotlin.native.nuget.processor.exports.cOpaquePointerVar
import io.github.xxfast.kotlin.native.nuget.processor.exports.stableRef

/** Kotlin projection for the complete planned property path. */
internal fun FileSpec.Builder.addForwardPropertyPlanExports(plan: ForwardPropertyPlan) {
  plan.validate()
  when (val getter: ForwardPropertyGetter = plan.getter) {
    is ForwardPropertyGetter.Direct -> addGetter(plan, getter.call)
    is ForwardPropertyGetter.LegacyTwoCall -> {
      addNullablePresenceGetter(plan, getter.presence)
      addNullableValueGetter(plan, getter.value)
    }
  }
  when (val setter: ForwardPropertySetter? = plan.setter) {
    null -> Unit
    is ForwardPropertySetter.Direct -> addSetter(plan, setter.call, null)
    is ForwardPropertySetter.NullableDispatch -> {
      addSetter(plan, setter.value, false)
      addSetter(plan, setter.nullValue, true)
    }
  }
}

private fun FileSpec.Builder.addGetter(plan: ForwardPropertyPlan, call: ForwardNativeCall) {
  val builder: FunSpec.Builder = exportBuilder(call, plan.receiver)
  val access: String = plan.accessExpression()
  when (val type: BridgeType = plan.type) {
    BridgeType.Unit -> builder.addCode(unitBody(access, "errorOut"), cOpaquePointerVar, stableRef)
    is BridgeType.Primitive -> {
      builder.returns(kotlinType(type))
      builder.addCode(
        valueBody(access, "errorOut", primitiveDefault(type)),
        cOpaquePointerVar,
        stableRef,
      )
    }

    BridgeType.Char -> {
      builder.returns(kotlinType("Char"))
      builder.addCode(valueBody(access, "errorOut", "'\\u0000'"), cOpaquePointerVar, stableRef)
    }

    BridgeType.String -> {
      builder.returns(kotlinType(type))
      builder.addCode(valueBody(access, "errorOut", "\"\""), cOpaquePointerVar, stableRef)
    }

    is BridgeType.Nullable -> when (val inner: BridgeType = type.type) {
      BridgeType.String -> {
        builder.returns(kotlinType(type))
        builder.addCode(valueBody(access, "errorOut", "null"), cOpaquePointerVar, stableRef)
      }

      is BridgeType.ObjectHandle -> {
        builder.returns(cOpaquePointer.copy(nullable = true))
        builder.addCode(
          nullableHandleBody(access, "errorOut"),
          stableRef,
          cOpaquePointerVar,
          stableRef,
        )
      }

      else -> error("Forward property direct nullable getter is invalid for ${plan.symbol}: $inner")
    }

    is BridgeType.Enum -> {
      builder.returns(kotlinType("Int"))
      builder.addCode(
        valueBody("$access.ordinal", "errorOut", "0"),
        cOpaquePointerVar,
        stableRef,
      )
    }

    is BridgeType.ObjectHandle, is BridgeType.Collection -> {
      builder.returns(cOpaquePointer.copy(nullable = true))
      builder.addCode(handleBody(access, "errorOut"), stableRef, cOpaquePointerVar, stableRef)
    }

    else -> error("Forward property emitter has no getter route for $type")
  }
  addFunction(builder.build())
}

private fun FileSpec.Builder.addNullablePresenceGetter(
  plan: ForwardPropertyPlan,
  call: ForwardNativeCall,
) {
  val builder: FunSpec.Builder =
    exportBuilder(call, plan.receiver).returns(kotlinType("Boolean"))
  builder.addCode(
    valueBody("${plan.accessExpression()} != null", "errorOut", "false"),
    cOpaquePointerVar,
    stableRef,
  )
  addFunction(builder.build())
}

private fun FileSpec.Builder.addNullableValueGetter(
  plan: ForwardPropertyPlan,
  call: ForwardNativeCall,
) {
  val type: BridgeType.Primitive =
    (plan.type as BridgeType.Nullable).type as BridgeType.Primitive
  val builder: FunSpec.Builder = exportBuilder(call, plan.receiver).returns(kotlinType(type))
  builder.addCode(
    valueBody("${plan.accessExpression()}!!", "errorOut", primitiveDefault(type)),
    cOpaquePointerVar,
    stableRef,
  )
  addFunction(builder.build())
}

private fun FileSpec.Builder.addSetter(
  plan: ForwardPropertyPlan,
  call: ForwardNativeCall,
  assignsNull: Boolean?,
) {
  val builder: FunSpec.Builder = exportBuilder(call, plan.receiver, includeError = false)
  if (assignsNull != true) {
    val valueType: BridgeType = requireNotNull(
      call.parameters.firstOrNull { it.name == "value" }?.transfer?.type,
    ) {
      "Forward property setter ${call.exportName} has no value transfer"
    }
    builder.addParameter("value", kotlinInputType(valueType))
  }
  builder.addParameter("errorOut", cOpaquePointer.copy(nullable = true))
  val expression: String = plan.accessExpression()
  val assignment: String = if (assignsNull == true) {
    "$expression = null"
  } else {
    "$expression = ${plan.valueExpression()}"
  }
  builder.addCode(unitBody(assignment, "errorOut"), cOpaquePointerVar, stableRef)
  addFunction(builder.build())
}

private fun exportBuilder(
  call: ForwardNativeCall,
  receiver: ForwardPropertyReceiver,
  includeError: Boolean = true,
): FunSpec.Builder {
  val builder: FunSpec.Builder = FunSpec.builder("export_${call.exportName}")
    .addAnnotation(cNameAnnotation(call.exportName))
  when (receiver) {
    is ForwardPropertyReceiver.Handle -> builder.addParameter("handle", cOpaquePointer)
    is ForwardPropertyReceiver.Value ->
      builder.addParameter("receiver", kotlinInputType(receiver.type))

    is ForwardPropertyReceiver.Static -> Unit
  }
  if (includeError) builder.addParameter("errorOut", cOpaquePointer.copy(nullable = true))
  return builder
}

private fun ForwardPropertyPlan.accessExpression(): String =
  when (val receiver: ForwardPropertyReceiver = receiver) {
    is ForwardPropertyReceiver.Handle ->
      "handle.asStableRef<${receiver.owner}>().get().$kotlinName"

    is ForwardPropertyReceiver.Value -> when (val type: BridgeType = receiver.type) {
      is BridgeType.ObjectHandle ->
        "receiver.asStableRef<${type.qualifiedName}>().get().$kotlinName"

      else -> "receiver.$kotlinName"
    }

    is ForwardPropertyReceiver.Static ->
      receiver.owner?.let { "$it.$kotlinName" } ?: kotlinName
  }

private fun ForwardPropertyPlan.valueExpression(): String = when (val type: BridgeType = type) {
  is BridgeType.Nullable -> when (val inner: BridgeType = type.type) {
    is BridgeType.Primitive, BridgeType.Char, BridgeType.String -> "value"
    is BridgeType.ObjectHandle -> "value?.asStableRef<${inner.qualifiedName}>()?.get()"
    else -> error("Forward property emitter has no nullable setter route for $type")
  }

  is BridgeType.Primitive, BridgeType.Char, BridgeType.String -> "value"
  is BridgeType.Enum -> "${type.qualifiedName}.entries[value]"
  is BridgeType.ObjectHandle -> "value.asStableRef<${type.qualifiedName}>().get()"
  else -> error("Forward property emitter has no setter route for $type")
}

private fun kotlinInputType(type: BridgeType): TypeName = when (type) {
  is BridgeType.Nullable -> kotlinInputType(type.type).copy(nullable = true)
  is BridgeType.Primitive -> kotlinType(type)
  BridgeType.Char -> kotlinType("Char")
  BridgeType.String -> kotlinType("String")
  is BridgeType.Enum -> kotlinType("Int")
  is BridgeType.ObjectHandle, is BridgeType.Collection ->
    cOpaquePointer.copy(nullable = type is BridgeType.Nullable)

  else -> error("Forward property emitter has no input type for $type")
}

private fun kotlinType(type: BridgeType): TypeName = when (type) {
  is BridgeType.Nullable -> kotlinType(type.type).copy(nullable = true)
  is BridgeType.Primitive -> kotlinType(
    when (type.kind) {
      PrimitiveKind.BOOLEAN -> "Boolean"
      PrimitiveKind.BYTE -> "Byte"
      PrimitiveKind.UBYTE -> "UByte"
      PrimitiveKind.SHORT -> "Short"
      PrimitiveKind.USHORT -> "UShort"
      PrimitiveKind.INT -> "Int"
      PrimitiveKind.UINT -> "UInt"
      PrimitiveKind.LONG -> "Long"
      PrimitiveKind.ULONG -> "ULong"
      PrimitiveKind.FLOAT -> "Float"
      PrimitiveKind.DOUBLE -> "Double"
    },
  )

  BridgeType.Char -> kotlinType("Char")
  BridgeType.String -> kotlinType("String")
  else -> error("Forward property emitter has no Kotlin result type for $type")
}

private fun kotlinType(name: String): ClassName = ClassName("kotlin", name)

private fun primitiveDefault(type: BridgeType.Primitive): String = when (type.kind) {
  PrimitiveKind.BOOLEAN -> "false"
  PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT, PrimitiveKind.LONG -> "0"
  PrimitiveKind.UBYTE -> "0.toUByte()"
  PrimitiveKind.USHORT -> "0.toUShort()"
  PrimitiveKind.UINT -> "0u"
  PrimitiveKind.ULONG -> "0uL"
  PrimitiveKind.FLOAT -> "0.0f"
  PrimitiveKind.DOUBLE -> "0.0"
}

private fun unitBody(invocation: String, error: String): String = buildString {
  appendLine("try {")
  appendLine("  $invocation")
  appendLine("} catch (e: Throwable) {")
  appendLine("  if ($error != null) {")
  appendLine("    $error.reinterpret<%T>().pointed.value = %T.create(")
  appendLine("      buildError(e)")
  appendLine("    ).asCPointer()")
  appendLine("  }")
  append("}")
}

private fun valueBody(invocation: String, error: String, fallback: String): String = buildString {
  appendLine("return try {")
  appendLine("  $invocation")
  appendLine("} catch (e: Throwable) {")
  appendLine("  if ($error != null) {")
  appendLine("    $error.reinterpret<%T>().pointed.value = %T.create(")
  appendLine("      buildError(e)")
  appendLine("    ).asCPointer()")
  appendLine("  }")
  appendLine("  $fallback")
  append("}")
}

private fun handleBody(invocation: String, error: String): String = buildString {
  appendLine("return try {")
  appendLine("  %T.create($invocation).asCPointer()")
  appendLine("} catch (e: Throwable) {")
  appendLine("  if ($error != null) {")
  appendLine("    $error.reinterpret<%T>().pointed.value = %T.create(")
  appendLine("      buildError(e)")
  appendLine("    ).asCPointer()")
  appendLine("  }")
  appendLine("  null")
  append("}")
}

private fun nullableHandleBody(invocation: String, error: String): String = buildString {
  appendLine("return try {")
  appendLine("  val result = $invocation")
  appendLine("  if (result == null) null else %T.create(result).asCPointer()")
  appendLine("} catch (e: Throwable) {")
  appendLine("  if ($error != null) {")
  appendLine("    $error.reinterpret<%T>().pointed.value = %T.create(")
  appendLine("      buildError(e)")
  appendLine("    ).asCPointer()")
  appendLine("  }")
  appendLine("  null")
  append("}")
}
