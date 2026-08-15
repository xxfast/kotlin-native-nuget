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

    BridgeType.Instant -> {
      require(call.result == ForwardAbiWireType.INT64) {
        "Forward Kotlin Instant result must use INT64 wire type: ${plan.invocation.symbol}"
      }
      builder.returns(kotlinResultType(call.result))
      builder.addCode(
        errorHandlingValueBody("$invocation.toDotNetTicks()", error.name, "0L"),
        cOpaquePointerVar,
        stableRef,
      )
    }

    // ADR-088: a GCHandle, not a StableRef — the reverse pipeline's own `nuget{Iface}HandleOut`
    // decides between duplicating a wrapper's handle and minting a bridge, so the forward emitter
    // only names it. Same POINTER result and error-slot shape as the handle branch below.
    is BridgeType.BoundInterface -> {
      require(call.result == ForwardAbiWireType.POINTER) {
        "Forward Kotlin bound-interface result must use POINTER wire type: " +
            plan.invocation.symbol
      }
      builder.returns(cOpaquePointer.copy(nullable = true))
      builder.addCode(
        errorHandlingValueBody(result.handleOutExpression(invocation), error.name, "null"),
        cOpaquePointerVar,
        stableRef,
      )
    }

    is BridgeType.ObjectHandle, is BridgeType.Interface, is BridgeType.Collection -> {
      // ADR-081: a collection with a value-class component boxes a projected copy of itself, so the
      // per-element boxes carry the underlying rather than the value class.
      val boxed: String =
        if (result is BridgeType.Collection) collectionResultProjection(invocation, result)
        else invocation
      builder.returns(cOpaquePointer.copy(nullable = true))
      builder.addCode(handleResultBody(boxed, error.name), stableRef, cOpaquePointerVar, stableRef)
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
  val unboxed = "$invocation.${type.underlyingPropertyName}"
  when (val underlying: BridgeType = type.underlying) {
    BridgeType.String -> {
      require(nativeResult == ForwardAbiWireType.POINTER) {
        "Forward Kotlin value-class String result must use POINTER wire type: $type"
      }
      builder.returns(kotlinType("String"))
      builder.addCode(
        errorHandlingValueBody(unboxed, errorName, "\"\""),
        cOpaquePointerVar,
        stableRef,
      )
    }

    // ADR-077 sub-item 4: the underlying's own result emission with the unboxing composed in.
    is BridgeType.Primitive -> {
      builder.returns(kotlinResultType(nativeResult))
      builder.addCode(
        errorHandlingValueBody(unboxed, errorName, defaultResult(underlying)),
        cOpaquePointerVar,
        stableRef,
      )
    }

    is BridgeType.Enum -> {
      builder.returns(kotlinType("Int"))
      builder.addCode(
        errorHandlingValueBody("$unboxed.ordinal", errorName, "0"),
        cOpaquePointerVar,
        stableRef,
      )
    }

    is BridgeType.ObjectHandle -> {
      builder.returns(cOpaquePointer.copy(nullable = true))
      builder.addCode(handleResultBody(unboxed, errorName), stableRef, cOpaquePointerVar, stableRef)
    }

    else -> error(
      "Forward Kotlin plan emitter has no ordinary value-class result for underlying $underlying",
    )
  }
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
  // ADR-076: a top-level nullable Instant shares this shape too.
  val inner: BridgeType = result.type
  // ADR-079: a Primitive/Enum-underlying value class rides the same two-call shape, unboxing to
  // the underlying on the `_value` call.
  require(
    inner is BridgeType.Primitive || inner == BridgeType.Instant ||
        inner is BridgeType.ValueClass || inner is BridgeType.Enum
  ) {
    "Legacy two-call plan ${plan.invocation.symbol} requires a nullable primitive, Instant, " +
        "enum or value class"
  }
  val error: ForwardAbiParameter = requireNotNull(plan.errorSlot) {
    "Legacy two-call plan ${plan.invocation.symbol} is missing its error slot"
  }
  val presence: ForwardNativeCall = plan.nativeExports[0]
  val value: ForwardNativeCall = plan.nativeExports[1]
  require(presence.result == ForwardAbiWireType.BOOLEAN) {
    "Legacy two-call presence export must return BOOLEAN: ${plan.invocation.symbol}"
  }
  require(value.result != ForwardAbiWireType.VOID) {
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

  val valueExpression: String = when {
    inner == BridgeType.Instant -> "$invocation!!.toDotNetTicks()"
    // ADR-079: unbox to the underlying (the ordinal for an enum underlying) on the `_value` call.
    inner is BridgeType.ValueClass -> "$invocation!!.${inner.underlyingPropertyName}" +
        if (inner.underlying is BridgeType.Enum) ".ordinal" else ""

    // ADR-080: the bare enum's own ordinal, no unbox step in front of it.
    inner is BridgeType.Enum -> "$invocation!!.ordinal"
    else -> "$invocation!!"
  }
  val valueDefault: String = when {
    inner == BridgeType.Instant -> "0L"
    inner is BridgeType.Enum -> "0"
    inner is BridgeType.ValueClass ->
      if (inner.underlying is BridgeType.Enum) "0" else defaultResult(inner.underlying)

    else -> defaultResult(inner)
  }
  val valueBuilder: FunSpec.Builder = exportBuilder(value).returns(kotlinResultType(value.result))
  valueBuilder.addCode(
    errorHandlingValueBody(valueExpression, error.name, valueDefault),
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
  val memberName: String =
    plan.invocation.member ?: plan.invocation.symbol.substringAfterLast('.')
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
      // ADR-081: same projected-copy boxing as the ordinary result route above.
      val boxed: String =
        if (result is BridgeType.Collection) collectionResultProjection(invocation, result)
        else invocation
      builder.returns(cOpaquePointer.copy(nullable = true))
      if (error != null) {
        builder.addCode(
          handleResultBody(boxed, error.name), stableRef, cOpaquePointerVar, stableRef,
        )
      } else {
        builder.addStatement("return %T.create(%L).asCPointer()", stableRef, boxed)
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
  // ADR-074: this invariant must be unreachable once the `allDeclarations` funnel filters
  // `isExpect` (an unfiltered expect/actual pair is what used to trip it). A fresh firing means a
  // *new* source of duplicate qualified names, not this one.
  require(matches.size <= 1) {
    "Forward callable catalog has duplicate plans for $symbol; two declarations share one " +
        "qualified name (an unfiltered expect/actual pair is the usual cause)"
  }
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

/**
 * ADR-081: one component of an incoming collection, cast out of the `Any?` box and, for a value
 * class, re-wrapped. The box never holds the value class itself: the C# call site projected it to
 * the underlying before boxing, so the cast targets the *underlying's* wire type (an int ordinal
 * for an enum underlying) and the value class is reconstructed here, per element, re-running its
 * own `init` exactly as ADR-077's ordinary-position lowering does.
 */
internal fun componentLowering(
  name: String,
  type: BridgeType,
  depth: Int = 0,
): String = when (type) {
  // ADR-099: the component's box holds the *inner container* the C# side built, so the cast target
  // is the wire container (`MutableList<*>`), not the declared Kotlin element type. The star
  // projection is a checked cast with no UNCHECKED_CAST warning; the elements read back as `Any?`
  // either way, and the conversion below is the same one the top level uses.
  is BridgeType.Collection ->
    "($name as ${type.wireContainerType(star = true)})${type.loweringSuffix(".", depth)}"

  // ADR-097: a bare enum arrived as its int ordinal, projected by the C# call site's
  // `Select(x => (int)x)`. Casting to the enum itself here would compile on both sides and
  // ClassCastException at the first call, so this arm and that projection change together.
  is BridgeType.Enum -> "${type.qualifiedName}.entries[$name as kotlin.Int]"

  is BridgeType.ValueClass -> when (val underlying: BridgeType = type.underlying) {
    is BridgeType.Enum ->
      "${type.qualifiedName}(${underlying.qualifiedName}.entries[$name as kotlin.Int])"

    else -> "${type.qualifiedName}($name as ${elementKotlinTypeName(underlying)})"
  }

  // ADR-083: a null component arrived as the null pointer and is already `null` in the `Any?` box,
  // so the cast simply goes nullable (`as T?` accepts null). A value class re-wraps under `?.let`
  // so its own `init` runs for the present elements only.
  is BridgeType.Nullable -> when (val inner: BridgeType = type.type) {
    // ADR-097: `List<Mood?>` is admitted by isWrappableComponent's Nullable branch the moment a
    // bare Enum is wrappable, and its box holds a nullable int ordinal.
    // ADR-099: the `let` parameter is named by depth, so a nested level cannot shadow the `v` of a
    // Map destructuring one level out.
    is BridgeType.Enum ->
      "($name as kotlin.Int?)?.let { ${letParameter(depth)} -> " +
          "${inner.qualifiedName}.entries[${letParameter(depth)}] }"

    is BridgeType.ValueClass -> when (val underlying: BridgeType = inner.underlying) {
      is BridgeType.Enum ->
        "($name as kotlin.Int?)?.let { ${letParameter(depth)} -> " +
            "${inner.qualifiedName}(${underlying.qualifiedName}" +
            ".entries[${letParameter(depth)}]) }"

      else ->
        "($name as ${elementKotlinTypeName(underlying)}?)?.let { ${letParameter(depth)} -> " +
            "${inner.qualifiedName}(${letParameter(depth)}) }"
    }

    else -> "$name as ${elementKotlinTypeName(inner)}?"
  }

  else -> "$name as ${elementKotlinTypeName(type)}"
}

/**
 * ADR-081, read side: the projection appended to a collection-returning invocation before its
 * result is boxed as one `StableRef`. A value-class component is mapped to its underlying (the int
 * ordinal for an enum underlying, the underlying instance for an ObjectHandle underlying), so the
 * generic `nuget_list_get`/`nuget_set_element_at`/`nuget_map_*_at` boxes a value the C# side's
 * `FromHandle<underlying>` can read; C# re-wraps per element. Boxing the value class itself is what
 * bound-and-broke before this ADR. Returns [invocation] unchanged when no component is a value
 * class, so every other collection result keeps its exact previous body.
 */
internal fun collectionResultProjection(
  invocation: String,
  type: BridgeType.Collection,
  nullable: Boolean = false,
  depth: Int = 0,
): String {
  val dot: String = if (nullable) "?." else "."
  val inner: Int = depth + 1
  return when (type.kind) {
    CollectionKind.LIST, CollectionKind.MUTABLE_LIST -> {
      val element: BridgeType = requireNotNull(type.element) {
        "Forward Kotlin collection result has no element type"
      }
      if (!element.componentNeedsProjection()) invocation
      else "$invocation${dot}map { ${lambdaHeader(inner)}" +
          "${componentRaising(elementParameter(inner), element, inner)} }"
    }

    // mapTo(mutableSetOf()) rather than map: `nuget_set_count`/`nuget_set_element_at` cast the box
    // back to `Set<*>`, so the projected copy must still be a Set.
    CollectionKind.SET, CollectionKind.MUTABLE_SET -> {
      val element: BridgeType = requireNotNull(type.element) {
        "Forward Kotlin collection result has no element type"
      }
      if (!element.componentNeedsProjection()) invocation
      else "$invocation${dot}mapTo(mutableSetOf()) { ${lambdaHeader(inner)}" +
          "${componentRaising(elementParameter(inner), element, inner)} }"
    }

    CollectionKind.MAP, CollectionKind.MUTABLE_MAP -> {
      val key: BridgeType = requireNotNull(type.key) { "Forward Kotlin Map result has no key type" }
      val value: BridgeType =
        requireNotNull(type.value) { "Forward Kotlin Map result has no value type" }
      if (!key.componentNeedsProjection() && !value.componentNeedsProjection()) invocation
      else "$invocation${dot}entries${dot}associate { " +
          "(${keyParameter(inner)}, ${valueParameter(inner)}) -> " +
          "${componentRaising(keyParameter(inner), key, inner)} to " +
          "${componentRaising(valueParameter(inner), value, inner)} }"
    }
  }
}

/** The inverse of [componentLowering]: one outgoing component projected to the wire value the
 *  generic box can carry. Identity for everything but a value class (ADR-083: `?.`-lifted when the
 *  component is a nullable value class -- a plain nullable component stays identity, since the box
 *  already holds `Any?` and the nullable read exports carry the null through). */
private fun componentRaising(name: String, type: BridgeType, depth: Int = 0): String {
  val dot: String = if (type is BridgeType.Nullable) "?." else "."
  // ADR-099: a nested collection is boxed as the container object itself, so the only thing to do
  // is raise ITS components, one level down -- the same projection the top level runs.
  if (type is BridgeType.Collection) return collectionResultProjection(name, type, depth = depth)
  // ADR-097: a bare enum leaves as its int ordinal, so the C# side reads `FromHandle<int>` and
  // casts back. Boxing the `Mood` itself is what bound-and-threw before this ADR.
  if (type.componentEnum() != null) return "$name${dot}ordinal"
  val valueClass: BridgeType.ValueClass = type.componentValueClass() ?: return name
  return "$name$dot${valueClass.underlyingPropertyName}" +
      if (valueClass.underlying is BridgeType.Enum) "$dot" + "ordinal" else ""
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
    is BridgeType.ObjectHandle, is BridgeType.Interface -> {
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

    // ADR-077 sub-items 3/4: safe-call unboxing; a Kotlin null ships the null pointer, either as
    // a null String pointer or as a null StableRef pointer for an ObjectHandle underlying.
    // ADR-079: a Primitive/Enum underlying has no null pointer to ride, so it takes the ADR-061
    // BOOLEAN + valueOut shape instead, writing the *unboxed* underlying (the ordinal for an enum)
    // through the underlying's own CVar.
    is BridgeType.ValueClass -> {
      val underlying: BridgeType = type.underlying
      if (underlying is BridgeType.Primitive || underlying is BridgeType.Enum) {
        require(call.result == ForwardAbiWireType.BOOLEAN) {
          "Forward Kotlin nullable value-class result over a primitive/enum underlying " +
              "must use BOOLEAN"
        }
        val valueOut: ForwardAbiParameter = requireNotNull(
          call.parameters.firstOrNull { parameter -> parameter.name == "valueOut" },
        ) { "Forward Kotlin nullable value-class result is missing valueOut" }
        val written: String = "result.${type.underlyingPropertyName}" +
            if (underlying is BridgeType.Enum) ".ordinal" else ""
        val kind: PrimitiveKind =
          if (underlying is BridgeType.Primitive) underlying.kind else PrimitiveKind.INT
        builder.returns(kotlinType("Boolean"))
        builder.addCode(
          nullablePrimitiveResultBody(invocation, valueOut.name, errorName, written),
          cVarType(kind),
          cOpaquePointerVar,
          stableRef,
        )
        return
      }
      require(call.result == ForwardAbiWireType.POINTER) {
        "Forward Kotlin nullable value-class result must use POINTER"
      }
      val unboxed = "$invocation?.${type.underlyingPropertyName}"
      if (underlying is BridgeType.ObjectHandle) {
        builder.returns(cOpaquePointer.copy(nullable = true))
        builder.addCode(
          nullableHandleResultBody(unboxed, errorName),
          stableRef,
          cOpaquePointerVar,
          stableRef,
        )
      } else {
        builder.returns(kotlinType("String").copy(nullable = true))
        builder.addCode(
          errorHandlingValueBody(unboxed, errorName, "null"),
          cOpaquePointerVar,
          stableRef,
        )
      }
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

    // ADR-080: same BOOLEAN + valueOut shape, writing the `int` ordinal into valueOut.
    is BridgeType.Enum -> {
      require(call.result == ForwardAbiWireType.BOOLEAN) {
        "Forward Kotlin nullable enum result must use BOOLEAN"
      }
      val valueOut: ForwardAbiParameter = requireNotNull(
        call.parameters.firstOrNull { parameter -> parameter.name == "valueOut" },
      ) { "Forward Kotlin nullable enum result is missing valueOut" }
      builder.returns(kotlinType("Boolean"))
      builder.addCode(
        nullablePrimitiveResultBody(invocation, valueOut.name, errorName, "result.ordinal"),
        cVarType(PrimitiveKind.INT),
        cOpaquePointerVar,
        stableRef,
      )
    }

    // ADR-076: same BOOLEAN + valueOut shape as the nullable-primitive case above, except the
    // Kotlin Instant result is converted to ticks before it is written into valueOut.
    BridgeType.Instant -> {
      require(call.result == ForwardAbiWireType.BOOLEAN) {
        "Forward Kotlin nullable Instant result must use BOOLEAN"
      }
      val valueOut: ForwardAbiParameter = requireNotNull(
        call.parameters.firstOrNull { parameter -> parameter.name == "valueOut" },
      ) { "Forward Kotlin nullable Instant result is missing valueOut" }
      builder.returns(kotlinType("Boolean"))
      builder.addCode(
        nullableInstantResultBody(invocation, valueOut.name, errorName),
        cVarType(PrimitiveKind.LONG),
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
    PrimitiveKind.BOOLEAN -> "BooleanVar"
  },
)

/** The lowered Kotlin expression for an extension function's receiver: an object handle is
 * un-boxed via `asStableRef`, matching every other object-handle input; a value class is
 * reconstructed from its underlying wire, the same `Owner(value)` re-wrap [loweredArgument]
 * applies at a parameter slot (ADR-077), which also re-runs the value class's own `init`
 * validation; a primitive/String receiver is already the right Kotlin value as-is.
 */
private fun receiverExpression(receiver: ForwardAbiParameter): String =
  when (val type: BridgeType = receiver.transfer.type) {
    is BridgeType.ObjectHandle ->
      "${receiver.name}.asStableRef<${type.qualifiedName}>().get()"

    is BridgeType.ValueClass ->
      "${type.qualifiedName}(${valueClassUnderlyingLowering(receiver.name, type.underlying)})"

    else -> receiver.name
  }

private fun invocationExpression(
  plan: ForwardCallablePlan,
  receiver: ForwardAbiParameter?,
  arguments: String,
): String {
  // ADR-090: an overload's symbol carries the `_2` suffix so catalog keys stay unique; the Kotlin
  // call site must say the declared name. `member` is null for every unnumbered callable.
  val functionName: String =
    plan.invocation.member ?: plan.invocation.symbol.substringAfterLast('.')
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
  is BridgeType.Primitive, BridgeType.Char, is BridgeType.Enum,
  BridgeType.Instant -> kotlinResultType(wireType)

  BridgeType.String -> kotlinType("String")
  is BridgeType.ObjectHandle, is BridgeType.Interface, is BridgeType.Collection -> cOpaquePointer
  // ADR-088: the transfer GCHandle the C# wrapper allocated.
  is BridgeType.BoundInterface -> cOpaquePointer
  // ADR-077 sub-item 1: a value class crosses as its underlying wire value, so the export's
  // parameter is typed as the underlying (String today) and `loweredArgument` re-wraps it.
  is BridgeType.ValueClass -> kotlinInputType(type.underlying, wireType)
  is BridgeType.Nullable -> when (val inner = type.type) {
    BridgeType.String -> kotlinType("String").copy(nullable = true)
    is BridgeType.ObjectHandle, is BridgeType.Interface, is BridgeType.Collection ->
      cOpaquePointer.copy(nullable = true)

    // ADR-077 sub-item 3: the underlying (String today) with the outer nullability re-applied.
    is BridgeType.ValueClass ->
      kotlinInputType(inner.underlying, wireType).copy(nullable = true)

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

/**
 * ADR-088: the reverse pipeline generates `nuget{Iface}Value` / `nuget{Iface}HandleOut` beside the
 * interface stub, in the stub's own Kotlin package. Both are `internal`, which is no obstacle: the
 * reverse bindings and this forward output compile into the same module. Fully qualified rather
 * than imported, matching how the plan emitter already names every other cross-package symbol.
 */
private fun BridgeType.BoundInterface.valueHelper(): String =
  "${qualifiedName.substringBeforeLast('.')}.nuget${qualifiedName.substringAfterLast('.')}Value"

private fun BridgeType.BoundInterface.handleOutExpression(invocation: String): String =
  "${qualifiedName.substringBeforeLast('.')}." +
      "nuget${qualifiedName.substringAfterLast('.')}HandleOut($invocation)"

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

/** [valueExpression] is what gets written into [valueOutName] once `result` is known non-null; it
 *  is `result` itself for a bare primitive and, per ADR-079, the unboxed underlying
 *  (`result.milligrams`, `result.mood.ordinal`) for a value-class result. */
private fun nullablePrimitiveResultBody(
  invocation: String,
  valueOutName: String,
  errorName: String,
  valueExpression: String = "result",
): String = buildString {
  appendLine("return try {")
  appendLine("  val result = $invocation")
  appendLine("  if (result != null && $valueOutName != null) {")
  appendLine("    $valueOutName.reinterpret<%T>().pointed.value = $valueExpression")
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

/** ADR-076: same shape as [nullablePrimitiveResultBody], except the Kotlin `Instant` result is
 *  converted to ticks (via the generated `toDotNetTicks()` helper) before it is written into
 *  [valueOutName] -- the wire payload is a plain `Long`, not the semantic `Instant` itself. */
private fun nullableInstantResultBody(
  invocation: String,
  valueOutName: String,
  errorName: String,
): String = buildString {
  appendLine("return try {")
  appendLine("  val result = $invocation")
  appendLine("  if (result != null && $valueOutName != null) {")
  appendLine("    $valueOutName.reinterpret<%T>().pointed.value = result.toDotNetTicks()")
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
    // ADR-076: the wire value is a raw INT64 of ticks; convert it back to an Instant.
    BridgeType.Instant -> "instantFromDotNetTicks(${parameter.name})"
    is BridgeType.ObjectHandle ->
      "${parameter.name}.asStableRef<${type.qualifiedName}>().get()"

    is BridgeType.Interface ->
      "${parameter.name}.asStableRef<${type.qualifiedName}>().get()"

    // ADR-088: the reverse pipeline's own resolver. It frees the incoming transfer handle and
    // returns the ORIGINAL Kotlin object on a token-probe hit; otherwise it wraps the handle in
    // the ADR-070 `{Iface}Handle`, whose cleaner owns it from here on.
    is BridgeType.BoundInterface -> "${type.valueHelper()}(${parameter.name})"

    is BridgeType.Collection -> loweredCollectionExpression(parameter.name, type)

    // ADR-077: re-wrap the underlying wire value (re-running the value class's own `init`), with
    // the underlying's own lowering composed inside the constructor call (sub-item 4).
    is BridgeType.ValueClass ->
      "${type.qualifiedName}(${valueClassUnderlyingLowering(parameter.name, type.underlying)})"

    is BridgeType.Nullable -> when (val inner: BridgeType = type.type) {
      BridgeType.String -> parameter.name
      is BridgeType.ObjectHandle ->
        "${parameter.name}?.asStableRef<${inner.qualifiedName}>()?.get()"

      is BridgeType.Interface ->
        "${parameter.name}?.asStableRef<${inner.qualifiedName}>()?.get()"

      is BridgeType.Primitive ->
        "if (${parameter.name}HasValue) ${parameter.name} else null"

      // ADR-080: same HasValue guard, with the ordinal lookup the non-null enum branch uses.
      is BridgeType.Enum ->
        "if (${parameter.name}HasValue) ${inner.qualifiedName}.entries[${parameter.name}] else null"

      // ADR-076: same HasValue-guard shape as the nullable Primitive case above, plus the same
      // TICKS_TO_INSTANT conversion the non-nullable Instant branch above uses.
      BridgeType.Instant ->
        "if (${parameter.name}HasValue) instantFromDotNetTicks(${parameter.name}) else null"

      // ADR-075: a nullable collection *parameter* (e.g. a data class's `notes: List<String>?`
      // constructor parameter, mirroring `Visit.notes` as a property) is now planned when its
      // component is eligible (`ForwardCallablePlanner.inputSkipReason()`'s Nullable branch),
      // sharing the same `?.`-guarded lowering the property setter emitter uses.
      is BridgeType.Collection ->
        loweredCollectionExpression(parameter.name, inner, nullable = true)

      // ADR-077 sub-items 3/4: `?.let` re-wraps only a non-null wire value, so a C# null arrives
      // as a genuine Kotlin null rather than a value class wrapping a default.
      // ADR-079: a Primitive/Enum underlying arrives as the adjacent HasValue pair instead, so the
      // guard is the `${name}HasValue` flag (the value slot's dead default is never re-wrapped).
      is BridgeType.ValueClass ->
        if (inner.underlying is BridgeType.Primitive || inner.underlying is BridgeType.Enum) {
          val lowered: String = valueClassUnderlyingLowering(parameter.name, inner.underlying)
          "if (${parameter.name}HasValue) ${inner.qualifiedName}($lowered) else null"
        } else {
          val lowered: String = valueClassUnderlyingLowering("it", inner.underlying)
          "${parameter.name}?.let { ${inner.qualifiedName}($lowered) }"
        }

      else -> error("Forward Kotlin plan emitter has no argument lowering for nullable $inner")
    }

    else -> error("Forward Kotlin plan emitter has no argument lowering for $type")
  }

/**
 * ADR-077 sub-item 4: the wire-to-underlying step composed inside a value-class re-wrap. Shared by
 * [loweredArgument] and [ForwardPropertyKotlinEmitter]'s setter lowering: the wire carries the
 * *underlying's* representation (int ordinal, StableRef pointer), never the value class itself.
 */
internal fun valueClassUnderlyingLowering(name: String, underlying: BridgeType): String =
  when (underlying) {
    is BridgeType.Enum -> "${underlying.qualifiedName}.entries[$name]"
    is BridgeType.ObjectHandle -> "$name.asStableRef<${underlying.qualifiedName}>().get()"
    else -> name
  }

/**
 * The six-kind collection lowering, shared by [loweredArgument] (a callable parameter) and
 * [ForwardPropertyKotlinEmitter]'s `valueExpression()` (a property setter's value, ADR-075) — the
 * two emitters already diverge in shape elsewhere, so this one shared expression builder is kept
 * rather than risking the two collection lowerings drifting apart. [nullable] renders every step
 * of the chain with `?.` instead of `.`, matching the nullable-`ObjectHandle`/`Interface`
 * lowerings one case above: a null `COpaquePointer` never reaches `asStableRef` because `?.`
 * short-circuits.
 */
internal fun loweredCollectionExpression(
  name: String,
  type: BridgeType.Collection,
  nullable: Boolean = false,
): String {
  val dot: String = if (nullable) "?." else "."
  // ADR-099: split into "dereference the handle" and "convert the container", because a nested
  // component needs only the second half (its box already holds the container object).
  return "$name${dot}asStableRef<${type.wireContainerType()}>()${dot}get()" +
      type.loweringSuffix(dot, depth = 0)
}

/** ADR-099: the container type a collection's own handle dereferences to, and the cast target a
 *  nested component uses. [star] picks the star projection the component cast needs. */
internal fun BridgeType.Collection.wireContainerType(star: Boolean = false): String = when (kind) {
  CollectionKind.LIST, CollectionKind.MUTABLE_LIST ->
    if (star) "MutableList<*>" else "MutableList<Any?>"

  CollectionKind.MAP, CollectionKind.MUTABLE_MAP ->
    if (star) "MutableMap<*, *>" else "MutableMap<Any?, Any?>"

  CollectionKind.SET, CollectionKind.MUTABLE_SET ->
    if (star) "MutableSet<*>" else "MutableSet<Any?>"
}

/**
 * The conversion half of [loweredCollectionExpression]: the container's elements cast (and, when a
 * component projects, re-wrapped) into the declared Kotlin type. Shared by the top level and by
 * [componentLowering]'s nested arm, so every depth converts identically. Lambda parameters are
 * named by [depth] because a nested implicit `it` shadows the enclosing lambda's.
 */
private fun BridgeType.Collection.loweringSuffix(dot: String, depth: Int): String {
  val inner: Int = depth + 1
  val element: String = elementParameter(inner)
  return when (kind) {
    CollectionKind.LIST ->
      "${dot}map { ${lambdaHeader(inner)}" +
          "${componentLowering(element, requireNotNull(this.element), inner)} }"

    CollectionKind.MUTABLE_LIST ->
      "${dot}mapTo(mutableListOf()) { ${lambdaHeader(inner)}" +
          "${componentLowering(element, requireNotNull(this.element), inner)} }"

    // ADR-073: copy-in, mirroring the List/MutableList pair above. Neither map kind writes
    // back -- see the ADR's "no write-back" decision.
    CollectionKind.MAP ->
      "${dot}entries${dot}associate { (${keyParameter(inner)}, ${valueParameter(inner)}) -> " +
          "(${componentLowering(keyParameter(inner), requireNotNull(key), inner)}) " +
          "to (${componentLowering(valueParameter(inner), requireNotNull(value), inner)}) }"

    CollectionKind.MUTABLE_MAP ->
      "${dot}entries${dot}associateTo(mutableMapOf()) { " +
          "(${keyParameter(inner)}, ${valueParameter(inner)}) -> " +
          "(${componentLowering(keyParameter(inner), requireNotNull(key), inner)}) " +
          "to (${componentLowering(valueParameter(inner), requireNotNull(value), inner)}) }"

    // ADR-073: SET and MUTABLE_SET deliberately share one lowering -- mapTo(mutableSetOf())
    // yields a MutableSet<T>, which satisfies a Set<T> parameter too, so there is no reason to
    // split them the way the list pair is split.
    CollectionKind.SET, CollectionKind.MUTABLE_SET ->
      "${dot}mapTo(mutableSetOf()) { ${lambdaHeader(inner)}" +
          "${componentLowering(element, requireNotNull(this.element), inner)} }"
  }
}

/** The element lambda's parameter at nesting level [depth]: the shipped implicit `it` at level 1,
 *  an explicit `e2`/`e3` deeper, where an implicit `it` would shadow the enclosing lambda's. */
private fun elementParameter(depth: Int): String = if (depth <= 1) "it" else "e$depth"

/** The `e2 ->` header a nested element lambda needs; empty at level 1, which keeps `it`. */
private fun lambdaHeader(depth: Int): String =
  if (depth <= 1) "" else "${elementParameter(depth)} -> "

private fun keyParameter(depth: Int): String = if (depth <= 1) "k" else "k$depth"

private fun valueParameter(depth: Int): String = if (depth <= 1) "v" else "v$depth"

private fun letParameter(depth: Int): String = if (depth <= 1) "v" else "v$depth"
