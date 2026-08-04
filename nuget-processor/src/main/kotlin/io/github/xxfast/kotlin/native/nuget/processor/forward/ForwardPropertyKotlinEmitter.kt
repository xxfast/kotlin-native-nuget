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

    // ADR-076: Instant Direct getter writes two OUT components (void return).
    // Rebuild parameters so OUT components sit before errorOut (exportBuilder already
    // appended errorOut for the ordinary Direct path).
    BridgeType.Instant -> {
      addFunction(instantComponentExport(plan, call, access, forceNonNull = false).build())
      return
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

    is BridgeType.ObjectHandle, is BridgeType.Interface, is BridgeType.Collection -> {
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
  val inner: BridgeType = (plan.type as BridgeType.Nullable).type
  // ADR-076: Instant? value export is void + two OUT component writes (not a returned primitive).
  if (inner == BridgeType.Instant) {
    addFunction(
      instantComponentExport(plan, call, plan.accessExpression(), forceNonNull = true).build(),
    )
    return
  }
  val type: BridgeType.Primitive = inner as BridgeType.Primitive
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
  // ADR-076: Instant/Instant? setters take multi-component IN scalars, not a single "value".
  val isInstant: Boolean = plan.type == BridgeType.Instant
  val isNullableInstant: Boolean =
    plan.type is BridgeType.Nullable && plan.type.type == BridgeType.Instant
  if (isInstant || isNullableInstant) {
    call.parameters
      .filter { parameter ->
        parameter.name != "handle" &&
            parameter.name != "receiver" &&
            parameter.name != "errorOut"
      }
      .forEach { parameter ->
        builder.addParameter(parameter.name, kotlinInputType(parameter.transfer.type))
      }
  } else if (assignsNull != true) {
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

/**
 * ADR-076: Instant component-writing export. Parameters follow the plan call order:
 * receiver (if any), OUT components, errorOut. [forceNonNull] unwraps with `!!` for the
 * Instant? two-call value export.
 */
private fun instantComponentExport(
  plan: ForwardPropertyPlan,
  call: ForwardNativeCall,
  access: String,
  forceNonNull: Boolean,
): FunSpec.Builder {
  val builder: FunSpec.Builder = exportBuilder(call, plan.receiver, includeError = false)
  call.parameters
    .filter { parameter ->
      parameter.name != "handle" && parameter.name != "receiver" && parameter.name != "errorOut"
    }
    .forEach { parameter ->
      if (parameter.direction == ForwardAbiDirection.OUT) {
        builder.addParameter(parameter.name, cOpaquePointer.copy(nullable = true))
      } else {
        builder.addParameter(parameter.name, kotlinInputType(parameter.transfer.type))
      }
    }
  builder.addParameter("errorOut", cOpaquePointer.copy(nullable = true))
  val expression: String = if (forceNonNull) "$access!!" else access
  builder.addCode(
    instantWriteBody(expression, "errorOut"),
    ClassName("kotlinx.cinterop", "LongVar"),
    ClassName("kotlinx.cinterop", "IntVar"),
    cOpaquePointerVar,
    stableRef,
  )
  return builder
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
    // ADR-076: Instant? setter reconstructs from has-value + two components.
    BridgeType.Instant ->
      "if (valueHasValue) kotlin.time.Instant.fromEpochSeconds(" +
          "value_epochSeconds, value_nanosecondsOfSecond) else null"

    is BridgeType.ObjectHandle -> "value?.asStableRef<${inner.qualifiedName}>()?.get()"
    is BridgeType.Interface -> "value?.asStableRef<${inner.qualifiedName}>()?.get()"
    // ADR-075 Question D: a nullable collection setter is an ordinary `Direct` route with a
    // nullable `COpaquePointer` value -- `?.` short-circuits before `asStableRef` is ever reached
    // for a null wire value, so the property's static type stays the property's own `List<T>?`.
    is BridgeType.Collection -> loweredCollectionExpression("value", inner, nullable = true)
    else -> error("Forward property emitter has no nullable setter route for $type")
  }

  is BridgeType.Primitive, BridgeType.Char, BridgeType.String -> "value"
  // ADR-076: Instant setter reconstructs from the two fan-out IN components.
  BridgeType.Instant ->
    "kotlin.time.Instant.fromEpochSeconds(value_epochSeconds, value_nanosecondsOfSecond)"

  is BridgeType.Enum -> "${type.qualifiedName}.entries[value]"
  is BridgeType.ObjectHandle -> "value.asStableRef<${type.qualifiedName}>().get()"
  is BridgeType.Interface -> "value.asStableRef<${type.qualifiedName}>().get()"
  is BridgeType.Collection -> loweredCollectionExpression("value", type)
  else -> error("Forward property emitter has no setter route for $type")
}

private fun kotlinInputType(type: BridgeType): TypeName = when (type) {
  is BridgeType.Nullable -> kotlinInputType(type.type).copy(nullable = true)
  is BridgeType.Primitive -> kotlinType(type)
  BridgeType.Char -> kotlinType("Char")
  BridgeType.String -> kotlinType("String")
  is BridgeType.Enum -> kotlinType("Int")
  // ADR-075: only ever reached for an extension property's receiver -- the underlying is what
  // actually crosses the wire (ADR-014).
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

/** ADR-076: write Instant components through OUT pointers (void export). */
private fun instantWriteBody(access: String, error: String): String = buildString {
  appendLine("try {")
  appendLine("  val result = $access")
  appendLine("  if (epochSecondsOut != null) {")
  appendLine("    epochSecondsOut.reinterpret<%T>().pointed.value = result.epochSeconds")
  appendLine("  }")
  appendLine("  if (nanosecondsOfSecondOut != null) {")
  appendLine("    nanosecondsOfSecondOut.reinterpret<%T>().pointed.value = result.nanosecondsOfSecond")
  appendLine("  }")
  appendLine("} catch (e: Throwable) {")
  appendLine("  if ($error != null) {")
  appendLine("    $error.reinterpret<%T>().pointed.value = %T.create(")
  appendLine("      buildError(e)")
  appendLine("    ).asCPointer()")
  appendLine("  }")
  append("}")
}
