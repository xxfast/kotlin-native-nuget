package io.github.xxfast.kotlin.native.nuget.processor.forward

import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.TypeName
import io.github.xxfast.kotlin.native.nuget.processor.exports.cNameAnnotation
import io.github.xxfast.kotlin.native.nuget.processor.exports.cOpaquePointer
import io.github.xxfast.kotlin.native.nuget.processor.exports.cOpaquePointerVar
import io.github.xxfast.kotlin.native.nuget.processor.exports.stableRef

/**
 * KotlinPoet projection for planned ordinary callables. Value-class positions use a dedicated
 * path that preserves reconstruction and the shipped no-errorOut member ABI (ADR-014).
 */
internal fun FileSpec.Builder.addForwardKotlinPlanExport(plan: ForwardCallablePlan): FileSpec.Builder {
  if (plan.invocation.origin == ForwardCallableOrigin.VALUE_CLASS) {
    return addForwardValueClassPlanExport(plan)
  }
  if (plan.evaluation == ForwardEvaluation.LEGACY_TWO_CALL) {
    return addLegacyTwoCallKotlinExport(plan)
  }

  plan.validate()
  require(plan.evaluation == ForwardEvaluation.EXACTLY_ONCE) {
    "Forward Kotlin plan emitter only supports exactly-once callables: ${plan.invocation.symbol}"
  }

  val call: ForwardNativeCall = plan.nativeExports.single()
  val receiver: ForwardAbiParameter? = call.parameters.firstOrNull()
    ?.takeIf { parameter -> parameter.name == "handle" || parameter.name == "receiver" }
  val error: ForwardAbiParameter = requireNotNull(plan.errorSlot) {
    "Forward Kotlin plan ${plan.invocation.symbol} is missing its error slot"
  }
  require(call.result == plan.result.wireType) {
    "Forward Kotlin plan ${plan.invocation.symbol} has different native and result wire types"
  }
  require((call.result == ForwardAbiWireType.VOID) == (plan.publicSignature.result == BridgeType.Unit)) {
    "Forward Kotlin plan ${plan.invocation.symbol} has incompatible public and native results"
  }
  require(receiver == null || receiver.direction == ForwardAbiDirection.IN) {
    "Forward Kotlin plan ${plan.invocation.symbol} receiver must be an input"
  }
  require(call.parameters.lastOrNull() == error) {
    "Forward Kotlin plan ${plan.invocation.symbol} must place its error slot last"
  }

  val builder: FunSpec.Builder = FunSpec.builder("export_${call.exportName}")
    .addAnnotation(cNameAnnotation(call.exportName))

  call.parameters.forEachIndexed { index, parameter ->
    builder.addParameter(parameter.name, kotlinType(parameter, index == 0))
  }

  val arguments: String = plan.publicSignature.parameters.joinToString(", ") { parameter ->
    loweredArgument(parameter)
  }
  val invocation: String = invocationExpression(plan, receiver, arguments)

  when (val result: BridgeType = plan.publicSignature.result) {
    BridgeType.Unit -> builder.addCode(errorHandlingUnitBody(invocation, error.name), cOpaquePointerVar, stableRef)
    is BridgeType.Primitive, BridgeType.Char -> {
      builder.returns(kotlinResultType(call.result))
      builder.addCode(
        errorHandlingValueBody(invocation, error.name, defaultResult(result)),
        cOpaquePointerVar,
        stableRef,
      )
    }

    BridgeType.String -> {
      require(call.result == ForwardAbiWireType.POINTER) {
        "Forward Kotlin String result must use POINTER wire type: ${plan.invocation.symbol}"
      }
      builder.returns(kotlinType("String"))
      builder.addCode(
        errorHandlingValueBody(invocation, error.name, "\"\""),
        cOpaquePointerVar,
        stableRef,
      )
    }

    is BridgeType.Enum -> {
      require(call.result == ForwardAbiWireType.INT32) {
        "Forward Kotlin enum result must use INT32 wire type: ${plan.invocation.symbol}"
      }
      builder.returns(kotlinType("Int"))
      builder.addCode(
        errorHandlingValueBody("$invocation.ordinal", error.name, "0"),
        cOpaquePointerVar,
        stableRef,
      )
    }

    is BridgeType.ObjectHandle, is BridgeType.Collection -> {
      builder.returns(cOpaquePointer.copy(nullable = true))
      builder.addCode(handleResultBody(invocation, error.name), stableRef, cOpaquePointerVar, stableRef)
    }

    is BridgeType.Nullable -> addNullableResult(
      builder = builder,
      type = result.type,
      invocation = invocation,
      call = call,
      errorName = error.name,
    )

    is BridgeType.ValueClass ->
      addValueClassOrdinaryResult(builder, result, invocation, call.result, error.name)

    else -> error("Forward Kotlin plan emitter has no Phase 4 result route for $result")
  }

  addFunction(builder.build())
  return this
}

/**
 * ADR-014 (ordinary position, ADR-066's fixture gap): a value class returned by an *ordinary*
 * callable is unboxed to its underlying property before crossing the wire — `Newsroom.code():
 * StoryCode` exports `code().value`, not a StableRef of `StoryCode` itself. Scoped to a `String`
 * underlying, matching [ForwardCallablePlanner]'s planner-side scoping.
 */
private fun addValueClassOrdinaryResult(
  builder: FunSpec.Builder,
  type: BridgeType.ValueClass,
  invocation: String,
  nativeResult: ForwardAbiWireType,
  errorName: String,
) {
  require(type.underlying == BridgeType.String) {
    "Forward Kotlin plan emitter only supports a String-underlying value class at an ordinary " +
        "result position: $type"
  }
  require(nativeResult == ForwardAbiWireType.POINTER) {
    "Forward Kotlin value-class String result must use POINTER wire type: $type"
  }
  builder.returns(kotlinType("String"))
  builder.addCode(
    errorHandlingValueBody("$invocation.${type.underlyingPropertyName}", errorName, "\"\""),
    cOpaquePointerVar,
    stableRef,
  )
}

/**
 * ADR-002 top-level nullable-primitive two-call: `${export}_has_value` + `${export}_value`.
 * Both invoke the same Kotlin function; presence returns BOOLEAN, value unwraps with `!!`.
 */
private fun FileSpec.Builder.addLegacyTwoCallKotlinExport(plan: ForwardCallablePlan): FileSpec.Builder {
  plan.validate()
  require(plan.evaluation == ForwardEvaluation.LEGACY_TWO_CALL) {
    "Legacy two-call emitter received ${plan.evaluation}: ${plan.invocation.symbol}"
  }
  require(plan.invocation.origin == ForwardCallableOrigin.TOP_LEVEL) {
    "Legacy two-call is only planned for top-level functions: ${plan.invocation.symbol}"
  }
  require(plan.nativeExports.size == 2) {
    "Legacy two-call plan ${plan.invocation.symbol} must have two native exports"
  }
  val result: BridgeType.Nullable = plan.publicSignature.result as? BridgeType.Nullable
    ?: error("Legacy two-call plan ${plan.invocation.symbol} requires a nullable result")
  val primitive: BridgeType.Primitive = result.type as? BridgeType.Primitive
    ?: error("Legacy two-call plan ${plan.invocation.symbol} requires a nullable primitive")
  val error: ForwardAbiParameter = requireNotNull(plan.errorSlot) {
    "Legacy two-call plan ${plan.invocation.symbol} is missing its error slot"
  }
  val presence: ForwardNativeCall = plan.nativeExports[0]
  val value: ForwardNativeCall = plan.nativeExports[1]
  require(presence.result == ForwardAbiWireType.BOOLEAN) {
    "Legacy two-call presence export must return BOOLEAN: ${plan.invocation.symbol}"
  }
  require(value.result != ForwardAbiWireType.BOOLEAN && value.result != ForwardAbiWireType.VOID) {
    "Legacy two-call value export must return the primitive wire type: ${plan.invocation.symbol}"
  }

  val arguments: String = plan.publicSignature.parameters.joinToString(", ") { parameter ->
    loweredArgument(parameter)
  }
  val invocation: String = invocationExpression(plan, receiver = null, arguments = arguments)

  fun exportBuilder(call: ForwardNativeCall): FunSpec.Builder {
    val builder: FunSpec.Builder = FunSpec.builder("export_${call.exportName}")
      .addAnnotation(cNameAnnotation(call.exportName))
    call.parameters.forEach { parameter ->
      builder.addParameter(parameter.name, kotlinType(parameter, isReceiver = false))
    }
    require(call.parameters.lastOrNull() == error) {
      "Legacy two-call export ${call.exportName} must place its error slot last"
    }
    return builder
  }

  val presenceBuilder: FunSpec.Builder = exportBuilder(presence).returns(kotlinType("Boolean"))
  presenceBuilder.addCode(
    errorHandlingValueBody("$invocation != null", error.name, "false"),
    cOpaquePointerVar,
    stableRef,
  )
  addFunction(presenceBuilder.build())

  val valueBuilder: FunSpec.Builder = exportBuilder(value).returns(kotlinResultType(value.result))
  valueBuilder.addCode(
    errorHandlingValueBody("$invocation!!", error.name, defaultResult(primitive)),
    cOpaquePointerVar,
    stableRef,
  )
  addFunction(valueBuilder.build())
  return this
}

/**
 * Value-class plan projection (Phase 9). Constructors keep errorOut and return the underlying
 * value; properties and methods keep the no-errorOut ABI and reconstruct via
 * `Owner(underlying).member`.
 */
internal fun FileSpec.Builder.addForwardValueClassPlanExport(plan: ForwardCallablePlan): FileSpec.Builder {
  plan.validate()
  require(plan.invocation.origin == ForwardCallableOrigin.VALUE_CLASS) {
    "Value-class Kotlin emitter received ${plan.invocation.origin}"
  }
  require(plan.evaluation == ForwardEvaluation.EXACTLY_ONCE) {
    "Value-class plan must be exactly-once: ${plan.invocation.symbol}"
  }

  val call: ForwardNativeCall = plan.nativeExports.single()
  require(call.result == plan.result.wireType) {
    "Value-class plan ${plan.invocation.symbol} has different native and result wire types"
  }

  val target: String = requireNotNull(plan.invocation.target) {
    "Value-class plan ${plan.invocation.symbol} is missing its owner target"
  }
  val owner: String = target.removeSuffix("#property")
  val isProperty: Boolean =
    target.endsWith("#property") || call.exportName.contains("_get_")
  val isConstructor: Boolean = plan.invocation.symbol.contains(".<init>")
  val error: ForwardAbiParameter? = plan.errorSlot
  if (error != null) {
    require(call.parameters.lastOrNull() == error) {
      "Value-class constructor plan ${plan.invocation.symbol} must place its error slot last"
    }
  }

  val builder: FunSpec.Builder = FunSpec.builder("export_${call.exportName}")
    .addAnnotation(cNameAnnotation(call.exportName))

  call.parameters.forEachIndexed { index, parameter ->
    val isReceiverSlot: Boolean = !isConstructor && index == 0 &&
        parameter.name in setOf("handle", "value", "receiver")
    builder.addParameter(parameter.name, valueClassKotlinType(parameter, isReceiverSlot))
  }

  val arguments: String = plan.publicSignature.parameters.joinToString(", ") { parameter ->
    loweredArgument(parameter)
  }
  val memberName: String = plan.invocation.symbol.substringAfterLast('.')
  val invocation: String = when {
    isConstructor -> {
      val underlyingProp: String = requireNotNull(plan.invocation.receiver) {
        "Value-class constructor plan ${plan.invocation.symbol} is missing underlying property name"
      }
      "$owner($arguments).$underlyingProp"
    }

    isProperty -> {
      val reconstructed: String = valueClassReconstruction(plan, call)
      "$reconstructed.$memberName"
    }

    else -> {
      val reconstructed: String = valueClassReconstruction(plan, call)
      "$reconstructed.$memberName($arguments)"
    }
  }

  when (val result: BridgeType = plan.publicSignature.result) {
    BridgeType.Unit -> {
      if (error != null) {
        builder.addCode(errorHandlingUnitBody(invocation, error.name), cOpaquePointerVar, stableRef)
      } else {
        builder.addStatement("%L", invocation)
      }
    }

    is BridgeType.Primitive, BridgeType.Char -> {
      builder.returns(kotlinResultType(call.result))
      if (error != null) {
        builder.addCode(
          errorHandlingValueBody(invocation, error.name, defaultResult(result)),
          cOpaquePointerVar,
          stableRef,
        )
      } else {
        builder.addStatement("return %L", invocation)
      }
    }

    BridgeType.String -> {
      builder.returns(kotlinType("String"))
      if (error != null) {
        builder.addCode(
          errorHandlingValueBody(invocation, error.name, "\"\""),
          cOpaquePointerVar,
          stableRef,
        )
      } else {
        builder.addStatement("return %L", invocation)
      }
    }

    is BridgeType.Enum -> {
      builder.returns(kotlinType("Int"))
      if (error != null) {
        builder.addCode(
          errorHandlingValueBody("$invocation.ordinal", error.name, "0"),
          cOpaquePointerVar,
          stableRef,
        )
      } else {
        builder.addStatement("return %L.ordinal", invocation)
      }
    }

    is BridgeType.ObjectHandle, is BridgeType.Collection -> {
      builder.returns(cOpaquePointer.copy(nullable = true))
      if (error != null) {
        builder.addCode(handleResultBody(invocation, error.name), stableRef, cOpaquePointerVar, stableRef)
      } else {
        builder.addStatement("return %T.create(%L).asCPointer()", stableRef, invocation)
      }
    }

    is BridgeType.Nullable -> {
      require(error != null) {
        "Value-class nullable results require an error slot: ${plan.invocation.symbol}"
      }
      addNullableResult(
        builder = builder,
        type = result.type,
        invocation = invocation,
        call = call,
        errorName = error.name,
      )
    }

    else -> error("Value-class Kotlin emitter has no result route for $result")
  }

  addFunction(builder.build())
  return this
}

private fun valueClassKotlinType(parameter: ForwardAbiParameter, isReceiver: Boolean): TypeName {
  if (isReceiver && parameter.transfer.type is BridgeType.ObjectHandle) return cOpaquePointer
  if (parameter.direction == ForwardAbiDirection.OUT && parameter.wireType == ForwardAbiWireType.POINTER) {
    return cOpaquePointer.copy(nullable = true)
  }
  require(parameter.direction == ForwardAbiDirection.IN) {
    "Value-class plan parameter ${parameter.name} has unsupported direction ${parameter.direction}"
  }
  return kotlinInputType(parameter.transfer.type, parameter.wireType)
}

/** `Owner(value)` or `Owner(handle.asStableRef<Underlying>().get())`. */
private fun valueClassReconstruction(plan: ForwardCallablePlan, call: ForwardNativeCall): String {
  val owner: String = requireNotNull(plan.invocation.target?.removeSuffix("#property")) {
    "Value-class plan ${plan.invocation.symbol} is missing its owner target"
  }
  val receiver: ForwardAbiParameter = call.parameters.firstOrNull()
    ?.takeIf { parameter -> parameter.name in setOf("handle", "value", "receiver") }
    ?: error("Value-class member plan ${plan.invocation.symbol} is missing its receiver parameter")
  return when (val type: BridgeType = receiver.transfer.type) {
    is BridgeType.ObjectHandle ->
      "$owner(${receiver.name}.asStableRef<${type.qualifiedName}>().get())"

    else -> "$owner(${receiver.name})"
  }
}

internal fun ForwardCallablePlanCatalog.planFor(symbol: String): ForwardCallablePlan? {
  val matches: List<ForwardCallablePlan> = plans.filter { plan -> plan.invocation.symbol == symbol }
  require(matches.size <= 1) { "Forward callable catalog has duplicate plans for $symbol" }
  return matches.singleOrNull()
}

private fun kotlinType(parameter: ForwardAbiParameter, isReceiver: Boolean): TypeName {
  if (isReceiver && parameter.transfer.type is BridgeType.ObjectHandle) return cOpaquePointer
  if (parameter.direction == ForwardAbiDirection.OUT && parameter.wireType == ForwardAbiWireType.POINTER) {
    return cOpaquePointer.copy(nullable = true)
  }
  require(parameter.direction == ForwardAbiDirection.IN) {
    "Forward Kotlin plan parameter ${parameter.name} has unsupported direction ${parameter.direction}"
  }
  return kotlinInputType(parameter.transfer.type, parameter.wireType)
}

private fun elementKotlinTypeName(type: BridgeType): String = when (type) {
  BridgeType.String -> "kotlin.String"
  BridgeType.Char -> "kotlin.Char"
  is BridgeType.Primitive -> "kotlin.${type.kind.simpleKotlinName()}"
  is BridgeType.ObjectHandle -> type.qualifiedName
  is BridgeType.Enum -> type.qualifiedName
  else -> error("Forward Kotlin plan emitter has no element type name for $type")
}

private fun PrimitiveKind.simpleKotlinName(): String = when (this) {
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
}

private fun addNullableResult(
  builder: FunSpec.Builder,
  type: BridgeType,
  invocation: String,
  call: ForwardNativeCall,
  errorName: String,
) {
  when (type) {
    is BridgeType.ObjectHandle -> {
      builder.returns(cOpaquePointer.copy(nullable = true))
      builder.addCode(nullableHandleResultBody(invocation, errorName), stableRef, cOpaquePointerVar, stableRef)
    }

    BridgeType.String -> {
      require(call.result == ForwardAbiWireType.POINTER) {
        "Forward Kotlin nullable String result must use POINTER"
      }
      builder.returns(kotlinType("String").copy(nullable = true))
      builder.addCode(errorHandlingValueBody(invocation, errorName, "null"), cOpaquePointerVar, stableRef)
    }

    is BridgeType.Primitive -> {
      require(call.result == ForwardAbiWireType.BOOLEAN) {
        "Forward Kotlin nullable primitive result must use BOOLEAN"
      }
      val valueOut: ForwardAbiParameter = requireNotNull(
        call.parameters.firstOrNull { parameter -> parameter.name == "valueOut" },
      ) { "Forward Kotlin nullable primitive result is missing valueOut" }
      builder.returns(kotlinType("Boolean"))
      builder.addCode(
        nullablePrimitiveResultBody(invocation, valueOut.name, errorName),
        cVarType(type.kind),
        cOpaquePointerVar,
        stableRef,
      )
    }

    else -> error("Forward Kotlin plan emitter has no nullable result route for $type")
  }
}

private fun kotlinResultType(wireType: ForwardAbiWireType): TypeName = when (wireType) {
  ForwardAbiWireType.BOOLEAN -> kotlinType("Boolean")
  ForwardAbiWireType.INT8 -> kotlinType("Byte")
  ForwardAbiWireType.UINT8 -> kotlinType("UByte")
  ForwardAbiWireType.INT16 -> kotlinType("Short")
  ForwardAbiWireType.UINT16 -> kotlinType("UShort")
  ForwardAbiWireType.INT32 -> kotlinType("Int")
  ForwardAbiWireType.UINT32 -> kotlinType("UInt")
  ForwardAbiWireType.INT64 -> kotlinType("Long")
  ForwardAbiWireType.UINT64 -> kotlinType("ULong")
  ForwardAbiWireType.FLOAT32 -> kotlinType("Float")
  ForwardAbiWireType.FLOAT64 -> kotlinType("Double")
  ForwardAbiWireType.STRING -> kotlinType("String")
  ForwardAbiWireType.CHAR16 -> kotlinType("Char")
  ForwardAbiWireType.VOID,
  ForwardAbiWireType.POINTER,
  ForwardAbiWireType.UNKNOWN,
    -> error("Forward Kotlin plan emitter has no direct-value Kotlin type for $wireType")
}

private fun kotlinType(name: String): ClassName = ClassName("kotlin", name)

private fun cVarType(kind: PrimitiveKind): ClassName = ClassName(
  "kotlinx.cinterop",
  when (kind) {
    PrimitiveKind.BYTE -> "ByteVar"
    PrimitiveKind.UBYTE -> "UByteVar"
    PrimitiveKind.SHORT -> "ShortVar"
    PrimitiveKind.USHORT -> "UShortVar"
    PrimitiveKind.INT -> "IntVar"
    PrimitiveKind.UINT -> "UIntVar"
    PrimitiveKind.LONG -> "LongVar"
    PrimitiveKind.ULONG -> "ULongVar"
    PrimitiveKind.FLOAT -> "FloatVar"
    PrimitiveKind.DOUBLE -> "DoubleVar"
    PrimitiveKind.BOOLEAN -> error("Boolean nullable return is not a Phase 4 route")
  },
)

/** The lowered Kotlin expression for an extension function's receiver: an object handle is
 * un-boxed via `asStableRef`, matching every other object-handle input; a primitive/String
 * receiver is already the right Kotlin value as-is.
 */
private fun receiverExpression(receiver: ForwardAbiParameter): String =
  when (val type: BridgeType = receiver.transfer.type) {
    is BridgeType.ObjectHandle ->
      "${receiver.name}.asStableRef<${type.qualifiedName}>().get()"

    else -> receiver.name
  }

private fun invocationExpression(
  plan: ForwardCallablePlan,
  receiver: ForwardAbiParameter?,
  arguments: String,
): String {
  val functionName: String = plan.invocation.symbol.substringAfterLast('.')
  return when (plan.invocation.origin) {
    ForwardCallableOrigin.CLASS -> {
      val owner: String = plan.invocation.symbol.substringBeforeLast('.')
      "handle.asStableRef<$owner>().get().$functionName($arguments)"
    }

    ForwardCallableOrigin.EXTENSION -> "${receiverExpression(requireNotNull(receiver))}.$functionName($arguments)"
    ForwardCallableOrigin.TOP_LEVEL -> "$functionName($arguments)"
    ForwardCallableOrigin.OBJECT, ForwardCallableOrigin.COMPANION ->
      "${requireNotNull(plan.invocation.target)}.$functionName($arguments)"

    ForwardCallableOrigin.CONSTRUCTOR -> "${requireNotNull(plan.invocation.target)}($arguments)"
    ForwardCallableOrigin.COPY -> {
      val owner: String = plan.invocation.symbol.substringBeforeLast('.')
      "handle.asStableRef<$owner>().get().copy($arguments)"
    }

    ForwardCallableOrigin.VALUE_CLASS ->
      error("VALUE_CLASS plans use addForwardValueClassPlanExport, not invocationExpression")
  }
}

private fun kotlinInputType(type: BridgeType, wireType: ForwardAbiWireType): TypeName = when (type) {
  is BridgeType.Primitive, BridgeType.Char, is BridgeType.Enum -> kotlinResultType(wireType)
  BridgeType.String -> kotlinType("String")
  is BridgeType.ObjectHandle, is BridgeType.Collection -> cOpaquePointer
  is BridgeType.Nullable -> when (val inner = type.type) {
    BridgeType.String -> kotlinType("String").copy(nullable = true)
    is BridgeType.ObjectHandle -> cOpaquePointer.copy(nullable = true)
    else -> error("Forward Kotlin plan emitter has no input type for nullable $inner")
  }

  else -> error("Forward Kotlin plan emitter has no input type for $type")
}

private fun errorHandlingUnitBody(invocation: String, errorName: String): String = buildString {
  appendLine("try {")
  appendLine("  $invocation")
  appendLine("} catch (e: Throwable) {")
  appendLine("  if ($errorName != null) {")
  appendLine("    $errorName.reinterpret<%T>().pointed.value = %T.create(")
  appendLine("      buildError(e)")
  appendLine("    ).asCPointer()")
  appendLine("  }")
  append("}")
}

private fun errorHandlingValueBody(
  invocation: String,
  errorName: String,
  default: String,
): String = buildString {
  appendLine("return try {")
  appendLine("  $invocation")
  appendLine("} catch (e: Throwable) {")
  appendLine("  if ($errorName != null) {")
  appendLine("    $errorName.reinterpret<%T>().pointed.value = %T.create(")
  appendLine("      buildError(e)")
  appendLine("    ).asCPointer()")
  appendLine("  }")
  appendLine("  $default")
  append("}")
}

private fun handleResultBody(invocation: String, errorName: String): String = buildString {
  appendLine("return try {")
  appendLine("  %T.create($invocation).asCPointer()")
  appendLine("} catch (e: Throwable) {")
  appendLine("  if ($errorName != null) {")
  appendLine("    $errorName.reinterpret<%T>().pointed.value = %T.create(")
  appendLine("      buildError(e)")
  appendLine("    ).asCPointer()")
  appendLine("  }")
  appendLine("  null")
  append("}")
}

private fun nullableHandleResultBody(invocation: String, errorName: String): String = buildString {
  appendLine("return try {")
  appendLine("  val result = $invocation")
  appendLine("  if (result == null) null else %T.create(result).asCPointer()")
  appendLine("} catch (e: Throwable) {")
  appendLine("  if ($errorName != null) {")
  appendLine("    $errorName.reinterpret<%T>().pointed.value = %T.create(")
  appendLine("      buildError(e)")
  appendLine("    ).asCPointer()")
  appendLine("  }")
  appendLine("  null")
  append("}")
}

private fun nullablePrimitiveResultBody(
  invocation: String,
  valueOutName: String,
  errorName: String,
): String = buildString {
  appendLine("return try {")
  appendLine("  val result = $invocation")
  appendLine("  if (result != null && $valueOutName != null) {")
  appendLine("    $valueOutName.reinterpret<%T>().pointed.value = result")
  appendLine("  }")
  appendLine("  result != null")
  appendLine("} catch (e: Throwable) {")
  appendLine("  if ($errorName != null) {")
  appendLine("    $errorName.reinterpret<%T>().pointed.value = %T.create(")
  appendLine("      buildError(e)")
  appendLine("    ).asCPointer()")
  appendLine("  }")
  appendLine("  false")
  append("}")
}

private fun defaultResult(type: BridgeType): String = when (type) {
  BridgeType.Char -> "'\\u0000'"
  is BridgeType.Primitive -> when (type.kind) {
    PrimitiveKind.BOOLEAN -> "false"
    PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT, PrimitiveKind.LONG -> "0"
    PrimitiveKind.UBYTE -> "0.toUByte()"
    PrimitiveKind.USHORT -> "0.toUShort()"
    PrimitiveKind.UINT -> "0u"
    PrimitiveKind.ULONG -> "0uL"
    PrimitiveKind.FLOAT -> "0.0f"
    PrimitiveKind.DOUBLE -> "0.0"
  }

  else -> error("Forward Kotlin plan emitter has no direct-value default for $type")
}

/** The lowering expression that turns one native ABI value back into the Kotlin argument. */
private fun loweredArgument(parameter: ForwardPublicParameter): String =
  when (val type: BridgeType = parameter.type) {
    is BridgeType.Primitive, BridgeType.Char, BridgeType.String -> parameter.name
    is BridgeType.Enum -> "${type.qualifiedName}.entries[${parameter.name}]"
    is BridgeType.ObjectHandle ->
      "${parameter.name}.asStableRef<${type.qualifiedName}>().get()"

    is BridgeType.Collection -> when (type.kind) {
      CollectionKind.LIST ->
        "${parameter.name}.asStableRef<MutableList<Any?>>().get()" +
            ".map { it as ${elementKotlinTypeName(requireNotNull(type.element))} }"

      CollectionKind.MUTABLE_LIST ->
        "${parameter.name}.asStableRef<MutableList<Any?>>().get()" +
            ".mapTo(mutableListOf()) { it as ${elementKotlinTypeName(requireNotNull(type.element))} }"

      else -> error(
        "Forward Kotlin plan emitter has no argument lowering for collection kind ${type.kind}",
      )
    }

    is BridgeType.Nullable -> when (val inner: BridgeType = type.type) {
      BridgeType.String -> parameter.name
      is BridgeType.ObjectHandle ->
        "${parameter.name}?.asStableRef<${inner.qualifiedName}>()?.get()"

      is BridgeType.Primitive ->
        "if (${parameter.name}HasValue) ${parameter.name} else null"

      else -> error("Forward Kotlin plan emitter has no argument lowering for nullable $inner")
    }

    else -> error("Forward Kotlin plan emitter has no argument lowering for $type")
  }
