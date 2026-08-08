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

      // ADR-075: a nullable collection has no element-type restriction on the read side --
      // `nullableHandleBody` already returns Kotlin `null` for a null result before ever building
      // a `StableRef`, the same route a nullable `ObjectHandle`/`Interface` getter takes.
      is BridgeType.ObjectHandle, is BridgeType.Interface, is BridgeType.Collection -> {
        builder.returns(cOpaquePointer.copy(nullable = true))
        builder.addCode(
          nullableHandleBody(access, "errorOut"),
          stableRef,
          cOpaquePointerVar,
          stableRef,
        )
      }

      // ADR-077 sub-items 3/4: safe-call unboxing; a Kotlin null ships the null pointer, over the
      // nullable-String wire or as a null StableRef pointer for an ObjectHandle underlying.
      is BridgeType.ValueClass -> {
        val unboxed = "$access?.${inner.underlyingPropertyName}"
        if (inner.underlying is BridgeType.ObjectHandle) {
          builder.returns(cOpaquePointer.copy(nullable = true))
          builder.addCode(
            nullableHandleBody(unboxed, "errorOut"),
            stableRef,
            cOpaquePointerVar,
            stableRef,
          )
        } else {
          builder.returns(kotlinType("String").copy(nullable = true))
          builder.addCode(valueBody(unboxed, "errorOut", "null"), cOpaquePointerVar, stableRef)
        }
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

    BridgeType.Instant -> {
      builder.returns(kotlinType("Long"))
      builder.addCode(
        valueBody("$access.toDotNetTicks()", "errorOut", "0L"),
        cOpaquePointerVar,
        stableRef,
      )
    }

    is BridgeType.ObjectHandle, is BridgeType.Interface, is BridgeType.Collection -> {
      builder.returns(cOpaquePointer.copy(nullable = true))
      builder.addCode(handleBody(access, "errorOut"), stableRef, cOpaquePointerVar, stableRef)
    }

    // ADR-077 sub-items 2/4: unbox to the underlying property, so the export ships the
    // underlying's wire representation (String, primitive, int ordinal, StableRef pointer); C#
    // reconstructs the record struct.
    is BridgeType.ValueClass -> {
      val unboxed = "$access.${type.underlyingPropertyName}"
      when (val underlying: BridgeType = type.underlying) {
        BridgeType.String -> {
          builder.returns(kotlinType("String"))
          builder.addCode(valueBody(unboxed, "errorOut", "\"\""), cOpaquePointerVar, stableRef)
        }

        is BridgeType.Primitive -> {
          builder.returns(kotlinType(underlying))
          builder.addCode(
            valueBody(unboxed, "errorOut", primitiveDefault(underlying)),
            cOpaquePointerVar,
            stableRef,
          )
        }

        is BridgeType.Enum -> {
          builder.returns(kotlinType("Int"))
          builder.addCode(
            valueBody("$unboxed.ordinal", "errorOut", "0"),
            cOpaquePointerVar,
            stableRef,
          )
        }

        is BridgeType.ObjectHandle -> {
          builder.returns(cOpaquePointer.copy(nullable = true))
          builder.addCode(handleBody(unboxed, "errorOut"), stableRef, cOpaquePointerVar, stableRef)
        }

        else -> error(
          "Forward property emitter has no value-class getter for underlying $underlying",
        )
      }
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
  val inner: BridgeType = (plan.type as BridgeType.Nullable).type
  val builder: FunSpec.Builder = when (inner) {
    is BridgeType.Primitive -> {
      val getterBuilder: FunSpec.Builder = exportBuilder(call, plan.receiver)
        .returns(kotlinType(inner))
      getterBuilder.addCode(
        valueBody("${plan.accessExpression()}!!", "errorOut", primitiveDefault(inner)),
        cOpaquePointerVar,
        stableRef,
      )
      getterBuilder
    }

    // ADR-076: same LegacyTwoCall value shape as the nullable Primitive case above, converted to
    // ticks before it crosses the wire.
    BridgeType.Instant -> {
      val getterBuilder: FunSpec.Builder = exportBuilder(call, plan.receiver)
        .returns(kotlinType("Long"))
      getterBuilder.addCode(
        valueBody("${plan.accessExpression()}!!.toDotNetTicks()", "errorOut", "0L"),
        cOpaquePointerVar,
        stableRef,
      )
      getterBuilder
    }

    // ADR-080: a bare nullable enum rides the same LegacyTwoCall `_value` call as its ordinal.
    is BridgeType.Enum -> exportBuilder(call, plan.receiver)
      .returns(kotlinType("Int"))
      .addCode(
        valueBody("${plan.accessExpression()}!!.ordinal", "errorOut", "0"),
        cOpaquePointerVar,
        stableRef,
      )

    // ADR-079: a Primitive/Enum-underlying value class rides the same LegacyTwoCall `_value` call,
    // unboxed to the underlying (the ordinal for an enum underlying).
    is BridgeType.ValueClass -> {
      val unboxed = "${plan.accessExpression()}!!.${inner.underlyingPropertyName}"
      val getterBuilder: FunSpec.Builder = exportBuilder(call, plan.receiver)
      when (val underlying: BridgeType = inner.underlying) {
        is BridgeType.Primitive -> getterBuilder
          .returns(kotlinType(underlying))
          .addCode(
            valueBody(unboxed, "errorOut", primitiveDefault(underlying)),
            cOpaquePointerVar,
            stableRef,
          )

        is BridgeType.Enum -> getterBuilder
          .returns(kotlinType("Int"))
          .addCode(valueBody("$unboxed.ordinal", "errorOut", "0"), cOpaquePointerVar, stableRef)

        else -> error(
          "Forward property nullable value getter has no value-class underlying route for " +
              "${plan.symbol}: $underlying",
        )
      }
      getterBuilder
    }

    else -> error("Forward property nullable value getter is invalid for ${plan.symbol}: $inner")
  }
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

      // ADR-075: an extension property whose receiver is a value class crosses the bridge as its
      // own underlying primitive/String value (ADR-014), exactly like
      // `ForwardKotlinPlanEmitter.valueClassReconstruction`'s `Owner(value)` for the value class's
      // own declared members -- the receiver must be reconstructed before the property access.
      is BridgeType.ValueClass -> "${type.qualifiedName}(receiver).$kotlinName"

      else -> "receiver.$kotlinName"
    }

    is ForwardPropertyReceiver.Static ->
      receiver.owner?.let { "$it.$kotlinName" } ?: kotlinName
  }

private fun ForwardPropertyPlan.valueExpression(): String = when (val type: BridgeType = type) {
  is BridgeType.Nullable -> when (val inner: BridgeType = type.type) {
    is BridgeType.Primitive, BridgeType.Char, BridgeType.String -> "value"
    is BridgeType.ObjectHandle -> "value?.asStableRef<${inner.qualifiedName}>()?.get()"
    is BridgeType.Interface -> "value?.asStableRef<${inner.qualifiedName}>()?.get()"
    // ADR-076: the wire value is a raw INT64 of ticks; convert it back to an Instant.
    BridgeType.Instant -> "instantFromDotNetTicks(value)"
    // ADR-080: the NullableDispatch `set` export carries the bare ordinal (`set_null` is the
    // other export), so the entry lookup is unconditional.
    is BridgeType.Enum -> "${inner.qualifiedName}.entries[value]"
    // ADR-075 Question D: a nullable collection setter is an ordinary `Direct` route with a
    // nullable `COpaquePointer` value -- `?.` short-circuits before `asStableRef` is ever reached
    // for a null wire value, so the property's static type stays the property's own `List<T>?`.
    is BridgeType.Collection -> loweredCollectionExpression("value", inner, nullable = true)
    // ADR-077 sub-items 3/4: `?.let` re-wraps only a non-null wire value, matching the callable
    // parameter lowering in ForwardKotlinPlanEmitter.
    // ADR-079: a Primitive/Enum underlying takes the NullableDispatch route instead, whose `set`
    // export receives the bare underlying wire (non-null by construction; `set_null` is the other
    // export), so the value is re-wrapped unconditionally.
    is BridgeType.ValueClass ->
      if (inner.underlying is BridgeType.Primitive || inner.underlying is BridgeType.Enum) {
        "${inner.qualifiedName}(${valueClassUnderlyingLowering("value", inner.underlying)})"
      } else {
        val lowered: String = valueClassUnderlyingLowering("it", inner.underlying)
        "value?.let { ${inner.qualifiedName}($lowered) }"
      }

    else -> error("Forward property emitter has no nullable setter route for $type")
  }

  is BridgeType.Primitive, BridgeType.Char, BridgeType.String -> "value"
  is BridgeType.Enum -> "${type.qualifiedName}.entries[value]"
  BridgeType.Instant -> "instantFromDotNetTicks(value)"
  is BridgeType.ObjectHandle -> "value.asStableRef<${type.qualifiedName}>().get()"
  is BridgeType.Interface -> "value.asStableRef<${type.qualifiedName}>().get()"
  is BridgeType.Collection -> loweredCollectionExpression("value", type)
  // ADR-077 sub-items 2/4: re-wrap the raw underlying wire value, re-running the value class's
  // own `init` validation, exactly like the callable parameter lowering in
  // ForwardKotlinPlanEmitter.
  is BridgeType.ValueClass ->
    "${type.qualifiedName}(${valueClassUnderlyingLowering("value", type.underlying)})"

  else -> error("Forward property emitter has no setter route for $type")
}

private fun kotlinInputType(type: BridgeType): TypeName = when (type) {
  is BridgeType.Nullable -> kotlinInputType(type.type).copy(nullable = true)
  is BridgeType.Primitive -> kotlinType(type)
  BridgeType.Char -> kotlinType("Char")
  BridgeType.String -> kotlinType("String")
  is BridgeType.Enum -> kotlinType("Int")
  BridgeType.Instant -> kotlinType("Long")
  // ADR-014: the underlying is what actually crosses the wire, both for an extension property's
  // value-class receiver (ADR-075) and for an ordinary value-class property's setter value
  // (ADR-077 sub-item 2).
  is BridgeType.ValueClass -> kotlinInputType(type.underlying)
  is BridgeType.ObjectHandle, is BridgeType.Interface, is BridgeType.Collection ->
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
