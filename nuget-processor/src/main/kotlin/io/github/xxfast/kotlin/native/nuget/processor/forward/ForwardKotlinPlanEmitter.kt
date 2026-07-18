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
 * KotlinPoet projection for Phase 3's direct-value callable plans. The planner deliberately
 * selects only Unit and blittable primitive parameters/results, so every parameter is rendered
 * from the ordered native ABI rather than from a declaration-specific KSP rescan.
 */
internal fun FileSpec.Builder.addForwardKotlinPlanExport(plan: ForwardCallablePlan): FileSpec.Builder {
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

  val arguments: String = plan.publicSignature.parameters.joinToString(", ") { parameter -> loweredArgument(parameter) }
  val invocation: String = invocationExpression(plan, receiver, arguments)

  when (val result: BridgeType = plan.publicSignature.result) {
    BridgeType.Unit -> builder.addCode(errorHandlingUnitBody(invocation, error.name), cOpaquePointerVar, stableRef)
    is BridgeType.Primitive -> {
      builder.returns(kotlinResultType(call.result))
      builder.addCode(
        errorHandlingValueBody(invocation, error.name, defaultResult(result)),
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

    else -> error("Forward Kotlin plan emitter has no Phase 4 result route for $result")
  }

  addFunction(builder.build())
  return this
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

/**
 * The Kotlin `@CName` export parameter type for one native ABI parameter, dispatched on its
 * semantic [BridgeType] (not its wire type alone): a wire type like `POINTER` or `STRING` cannot
 * by itself distinguish a plain object handle from a nullable one, or a plain string from a
 * nullable one. A nullable primitive never reaches this dispatch as `Nullable` — the planner
 * already fanned it out into two non-nullable native parameters (`xHasValue`, `x`) before this
 * point, so each sees its own non-nullable [BridgeType.Primitive].
 */
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

/** The lowering expression that turns one native ABI value (or pair, for nullable primitives)
 * back into the Kotlin value the underlying declaration expects, keyed off the *public*
 * [BridgeType] rather than the native ABI shape.
 */
private fun loweredArgument(parameter: ForwardPublicParameter): String = when (val type = parameter.type) {
  is BridgeType.Primitive, BridgeType.Char, BridgeType.String -> parameter.name
  is BridgeType.Enum -> "${type.qualifiedName}.entries[${parameter.name}]"
  is BridgeType.ObjectHandle -> "${parameter.name}.asStableRef<${type.qualifiedName}>().get()"
  is BridgeType.Collection -> when (type.kind) {
    CollectionKind.LIST -> "${parameter.name}.asStableRef<MutableList<Any?>>().get()" +
        ".map { it as ${elementKotlinTypeName(requireNotNull(type.element))} }"

    CollectionKind.MUTABLE_LIST -> "${parameter.name}.asStableRef<MutableList<Any?>>().get()" +
        ".mapTo(mutableListOf()) { it as ${elementKotlinTypeName(requireNotNull(type.element))} }"

    else -> error("Forward Kotlin plan emitter has no argument lowering for collection kind ${type.kind}")
  }

  is BridgeType.Nullable -> when (val inner = type.type) {
    BridgeType.String -> parameter.name
    is BridgeType.ObjectHandle -> "${parameter.name}?.asStableRef<${inner.qualifiedName}>()?.get()"
    is BridgeType.Primitive -> "if (${parameter.name}HasValue) ${parameter.name} else null"
    else -> error("Forward Kotlin plan emitter has no argument lowering for nullable $inner")
  }

  else -> error("Forward Kotlin plan emitter has no argument lowering for $type")
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
private fun receiverExpression(receiver: ForwardAbiParameter): String = when (val type = receiver.transfer.type) {
  is BridgeType.ObjectHandle -> "${receiver.name}.asStableRef<${type.qualifiedName}>().get()"
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
  }
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
