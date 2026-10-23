package io.github.xxfast.kotlin.native.nuget.processor.exports

import io.github.xxfast.kotlin.native.nuget.processor.forward.kotlinIdentifier
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.TypeSpec
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardBridgeInterfacePlan
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardBridgeSlot
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardBridgeSlotKind
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardBridgeWire
import io.github.xxfast.kotlin.native.nuget.processor.forward.kotlinWire
import io.github.xxfast.kotlin.native.nuget.processor.forward.isGeneric
import io.github.xxfast.kotlin.native.nuget.processor.forward.forwardKotlinDeclaredSpelling
import com.google.devtools.ksp.symbol.KSTypeParameter
import io.github.xxfast.kotlin.native.nuget.processor.forward.managedExceptionLowering
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardAsyncKind
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardAsyncShared
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardAsyncSlots
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardBridgeAsync
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardBridgeAsyncThunk
import io.github.xxfast.kotlin.native.nuget.processor.forward.asyncCallbackHolderConstruction
import io.github.xxfast.kotlin.native.nuget.processor.forward.asyncThunkParameters
import io.github.xxfast.kotlin.native.nuget.processor.forward.currentThunkParameter
import io.github.xxfast.kotlin.native.nuget.processor.forward.endThunkParameter
import io.github.xxfast.kotlin.native.nuget.processor.forward.asyncThunks
import io.github.xxfast.kotlin.native.nuget.processor.forward.flowSource
import io.github.xxfast.kotlin.native.nuget.processor.forward.flowSourceExpression
import io.github.xxfast.kotlin.native.nuget.processor.forward.BridgeType
import io.github.xxfast.kotlin.native.nuget.processor.forward.collectionResultProjection
import io.github.xxfast.kotlin.native.nuget.processor.forward.loweredCollectionExpression
import io.github.xxfast.kotlin.native.nuget.processor.forward.unwrapNullable
import io.github.xxfast.kotlin.native.nuget.processor.forward.ASYNC_CALLBACK_HOLDER
import io.github.xxfast.kotlin.native.nuget.processor.forward.lambdaThunkParameter
import io.github.xxfast.kotlin.native.nuget.processor.forward.loweredCallbackExpression
import io.github.xxfast.kotlin.native.nuget.processor.forward.returnedCallback

/**
 * ADR-084 stage 1: the per-interface bridge factory export (`pet_bridge_create`).
 *
 * One function-pointer + context pair per slot in [ForwardBridgeInterfacePlan.slots] order, then
 * the release pair, then `errorOut`. The export builds an anonymous `object : Pet` forwarding every
 * member to its slot and returns a `StableRef` handle, so every existing interface-typed parameter
 * export (`cat_befriend`, `catextensions_interview`) is reused unchanged: C# converts first, then
 * goes down the ordinary handle path.
 *
 * ADR-084 facet 4: the bridge object owns a `createCleaner` whose argument is the release function
 * pointer paired with its context, and whose block captures nothing. Capturing the bridge (or
 * anything reaching it) in either half would root the object the cleaner exists to observe, so the
 * pair is the whole state: when Kotlin's GC collects the bridge, the cleaner worker invokes the
 * C#-side release, which frees that object's pinned delegate handles.
 *
 * String ownership across a slot: Kotlin mints the `StableRef` for a String *argument* and does not
 * dispose it, because the C# side's `NugetMarshal.FromHandle<string>` disposes it on read (one
 * owner, one dispose). A String *result* is minted by C# (`nuget_wrap_string`) and disposed here.
 */
internal fun FileSpec.Builder.addInterfaceBridgeFactoryExport(plan: ForwardBridgeInterfacePlan) {
  if (plan.slots.boxesTicks()) addImport(RUNTIME, "toDotNetTicks")
  val body: String = buildString {
    appendLine("return try {")
    plan.slots.forEach { slot ->
      // ADR-232: a SUSPEND slot's Begin thunk is reinterpreted at its one call, in the override.
      if (slot.async == ForwardBridgeAsync.SUSPEND) return@forEach
      // ADR-161: payloads, the slot's ctx, then the trailing error slot.
      val args: String = (
          slot.parameters.map { it.type.wire.kotlinWire() } + "COpaquePointer" + "COpaquePointer?"
          ).joinToString(", ")
      appendLine(
        "  val ${slot.slotPrefix}Fn = ${slot.slotPrefix}Ptr" +
            ".reinterpret<CFunction<($args) -> ${slot.result.wire.kotlinWire()}>>()"
      )
    }
    // ADR-161: the release thunk shares the one thunk shell, so it carries the slot too, but the
    // cleaner passes `null`: there is nowhere to throw to from a GC worker, so a failure there
    // keeps ADR-102's `Environment.FailFast` backstop. `null` is what selects that branch in the
    // thunk.
    appendLine(
      "  val releaseFn = releasePtr" +
          ".reinterpret<CFunction<(COpaquePointer, COpaquePointer?) -> Unit>>()"
    )
    // ADR-222: a generic interface's bridge is a local generic function, compiled once over the
    // erased bounds and always instantiated at `Nothing`, which satisfies every bound (a
    // self-referencing `T : Comparable<T>` included). Kotlin's use-site cast checks each `T`.
    val typeParameters: List<KSTypeParameter> = plan.declaration.typeParameters
    if (plan.isGeneric) {
      val names: String = typeParameters.joinToString(", ") { it.name.asString() }
      val bounds: List<String> = typeParameters.flatMap { parameter ->
        parameter.bounds.map { it.resolve() }
          .filter { bound ->
            bound.declaration.qualifiedName?.asString() != "kotlin.Any" || !bound.isMarkedNullable
          }
          .map { bound -> "${parameter.name.asString()} : ${bound.forwardKotlinDeclaredSpelling()}" }
      }
      val where: String = if (bounds.isEmpty()) "" else " where ${bounds.joinToString(", ")}"
      appendLine("  fun <$names> bridge(): ${plan.qualifiedName}<$names>$where = object : ${plan.qualifiedName}<$names>, NugetCSharpBridge {")
    } else {
      appendLine("  val bridge = object : ${plan.qualifiedName}, NugetCSharpBridge {")
    }
    // ADR-084 facet 5: the token is a GCHandle to the *implementing C# object*, so the return-
    // position probe resolves the original instance without knowing any bridge-state type.
    appendLine("    override val nugetToken: COpaquePointer = token")
    appendLine("    @Suppress(\"unused\")")
    appendLine("    private val cleaner = createCleaner(releaseFn to releaseCtx) { (fn, ctx) ->")
    appendLine("      fn.invoke(ctx, null)")
    appendLine("    }")
    appendSlotOverrides(plan.slots)
    appendLine("  }")
    if (plan.isGeneric) {
      val nothing: String = typeParameters.joinToString(", ") { "Nothing" }
      appendLine("  NugetHandles.retain(bridge<$nothing>())")
    } else {
      appendLine("  NugetHandles.retain(bridge)")
    }
    appendLine("} catch (e: Throwable) {")
    appendLine("  if (errorOut != null) {")
    appendLine("    errorOut.reinterpret<COpaquePointerVar>().pointed.value = NugetHandles.retain(")
    appendLine("      buildError(e, ::nugetMappedType)")
    appendLine("    )")
    appendLine("  }")
    appendLine("  null")
    append("}")
  }

  val builder: FunSpec.Builder = FunSpec.builder("export_${plan.exportName}")
    .addAnnotation(
      cNameAnnotation(plan.exportName, ownedBy(plan.declaration, "interface bridge factory")),
    )
    .addAnnotation(
      AnnotationSpec.builder(ClassName("kotlin", "OptIn"))
        .addMember("%T::class", ClassName("kotlin.experimental", "ExperimentalNativeApi"))
        .build()
    )

  if (plan.isGeneric || plan.slots.any { slot -> slot.typeParameters.isNotEmpty() }) {
    builder.addAnnotation(
      AnnotationSpec.builder(ClassName("kotlin", "Suppress")).addMember("%S", "UNCHECKED_CAST").build(),
    )
  }
  plan.slots.forEach { slot ->
    builder.addParameter("${slot.slotPrefix}Ptr", cOpaquePointer)
    builder.addParameter("${slot.slotPrefix}Ctx", cOpaquePointer)
    slot.asyncThunkParameters.forEach { thunk -> builder.addParameter(thunk, cOpaquePointer) }
  }
  builder.addParameter("releasePtr", cOpaquePointer)
  builder.addParameter("releaseCtx", cOpaquePointer)
  plan.asyncThunks().forEach { thunk -> builder.addParameter(thunk.parameter, cOpaquePointer) }
  builder.addParameter("token", cOpaquePointer)
  builder.addParameter("errorOut", cOpaquePointer.copy(nullable = true))
  builder.returns(cOpaquePointer.copy(nullable = true))
  builder.addCode(body)

  addFunction(builder.build())
}

/**
 * Every slot's `override`, in order. ADR-084 amendment (2026-10-22): a getter whose setter slot
 * follows it is a `var`. Shared by the bridge factory and the ADR-039 subscription route.
 */
internal fun StringBuilder.appendSlotOverrides(slots: List<ForwardBridgeSlot>) {
  val setters: Set<String> = slots
    .filter { slot -> slot.kind == ForwardBridgeSlotKind.SETTER }
    .map { slot -> slot.name }
    .toSet()
  slots.forEach { slot -> appendSlotOverride(slot, mutable = slot.name in setters) }
}

/**
 * One slot's `override`, forwarding to `${slotPrefix}Fn` with `${slotPrefix}Ctx`. Also the ADR-039
 * subscription route's listener-property override, so a listener `val` reads and releases its
 * result exactly as it does here.
 */
private fun StringBuilder.appendSlotOverride(slot: ForwardBridgeSlot, mutable: Boolean) {
  val call: String = invocation(slot)
  when (slot.kind) {
    ForwardBridgeSlotKind.GETTER -> {
      val keyword: String = if (mutable) "var" else "val"
      appendLine("    override $keyword ${slot.name.kotlinIdentifier()}: ${slot.result.kotlin}")
      appendLine("      get() {")
      if (slot.async == ForwardBridgeAsync.FLOW) {
        appendFlowRead(slot, call, "        ")
      } else {
        appendResultMarshalling(slot, call, "        ")
      }
      appendLine("      }")
    }

    // ADR-084 amendment (2026-10-22): rendered directly after its getter, so this accessor
    // attaches to the `override var` above. `value` crosses as a function slot's argument does.
    ForwardBridgeSlotKind.SETTER -> {
      appendLine("      set(value) {")
      appendArgumentRefs(slot, "        ")
      appendResultMarshalling(slot, call, "        ")
      appendLine("      }")
    }

    ForwardBridgeSlotKind.FUNCTION -> {
      val params: String =
        slot.parameters.joinToString(", ") { "${it.name.kotlinIdentifier()}: ${it.type.kotlin}" }
      val modifier: String = if (slot.async == ForwardBridgeAsync.SUSPEND) "suspend " else ""
      // ADR-237: a member's own type parameters are restated; C# sees them erased.
      val typeParameters: String =
        if (slot.typeParameters.isEmpty()) "" else slot.typeParameters.joinToString(", ", "<", "> ")
      appendLine(
        "    override ${modifier}fun $typeParameters${slot.name.kotlinIdentifier()}($params): ${slot.result.kotlin} {",
      )
      when (slot.async) {
        ForwardBridgeAsync.SYNC -> {
          appendArgumentRefs(slot, "      ")
          appendResultMarshalling(slot, call, "      ")
        }

        ForwardBridgeAsync.SUSPEND -> appendSuspendCall(slot, "      ")
        ForwardBridgeAsync.FLOW -> {
          appendArgumentRefs(slot, "      ")
          appendFlowRead(slot, call, "      ")
        }
      }
      appendLine("    }")
    }
  }
}

private const val RUNTIME: String = "io.github.xxfast.kotlin.native.nuget.runtime"

/**
 * ADR-232: a `suspend` member, awaited through the runtime's `awaitForKotlin` exactly as an ADR-210
 * suspend lambda is. The Begin thunk dispatches to the slot's own delegate (`${slotPrefix}Ctx`, a
 * key the bridge state pinned once), so no per-call key is minted; the payload handles are minted
 * inside `begin`, where the Begin thunk's key-miss branch disposes them. The End thunk's result is
 * the ADR-084 slot's own wire, read by [appendResultMarshalling].
 */
private fun StringBuilder.appendSuspendCall(slot: ForwardBridgeSlot, indent: String) {
  val pointer = "COpaquePointer"
  val payloads: List<String> = slot.parameters.map { it.type.wire.kotlinWire() }
  val signature: String =
    (payloads + listOf(pointer, pointer, pointer, "$pointer?")).joinToString(", ")
  val arguments: String = (slotArguments(slot) +
    listOf("nugetCallback", "nugetCtx", "${slot.slotPrefix}Ctx", "nugetErr")).joinToString(", ")
  appendLine("${indent}val nugetTask: $pointer = $RUNTIME.awaitForKotlin(")
  appendLine(
    "$indent  release = { task -> ${ForwardBridgeAsyncThunk.RELEASE_TASK.parameter}" +
      ".reinterpret<CFunction<($pointer) -> Unit>>().invoke(task) },",
  )
  appendLine(
    "$indent  cancel = { source, cancelled -> " +
      "${ForwardBridgeAsyncThunk.RELEASE_CANCELLATION.parameter}" +
      ".reinterpret<CFunction<($pointer, Int) -> Unit>>()" +
      ".invoke(source, if (cancelled) 1 else 0) },",
  )
  appendLine("$indent  begin = { nugetCallback, nugetCtx ->")
  appendArgumentRefs(slot, "$indent    ")
  appendLine(
    "$indent    nugetCallbackCall { nugetErr -> ${slot.slotPrefix}Ptr" +
      ".reinterpret<CFunction<($signature) -> $pointer?>>().invoke($arguments) }",
  )
  appendLine("$indent  },")
  appendLine("$indent)")
  val end: String = "nugetCallbackCall { nugetErr -> ${slot.endThunkParameter}" +
    ".reinterpret<CFunction<($pointer, $pointer?) -> ${slot.result.wire.kotlinWire()}>>()" +
    ".invoke(nugetTask, nugetErr) }"
  // ADR-232: a suspend member returning a Flow completes with the key of its enumeration factory.
  if (slot.element != null) appendFlowRead(slot, end, indent)
  else appendResultMarshalling(slot, end, indent)
}

/**
 * ADR-232: a `Flow` member. The slot call returns the key of the enumeration factory C# registered
 * over the implementation's `KotlinFlow<T>`; a holder takes the key (its cleaner hands it back),
 * and the runtime's `flowForKotlin` reads the enumeration exactly as an ADR-210 `Flow` parameter.
 */
private fun StringBuilder.appendFlowRead(slot: ForwardBridgeSlot, call: String, indent: String) {
  val key = "nugetFlowKey"
  val holder = "nugetFlowHolder"
  val thunk: (ForwardBridgeAsyncThunk) -> String = { it.parameter }
  val slots = ForwardAsyncSlots(
    kind = ForwardAsyncKind.FLOW,
    begin = thunk(ForwardBridgeAsyncThunk.ENUMERATE),
    end = slot.currentThunkParameter,
    key = key,
    shared = mapOf(
      ForwardAsyncShared.RELEASE_TASK to thunk(ForwardBridgeAsyncThunk.RELEASE_TASK),
      ForwardAsyncShared.MOVE_NEXT_BEGIN to thunk(ForwardBridgeAsyncThunk.MOVE_NEXT_BEGIN),
      ForwardAsyncShared.MOVE_NEXT_END to thunk(ForwardBridgeAsyncThunk.MOVE_NEXT_END),
      ForwardAsyncShared.DISPOSE_ENUMERATION to thunk(ForwardBridgeAsyncThunk.DISPOSE_ENUMERATION),
      ForwardAsyncShared.RELEASE_CONTEXT to thunk(ForwardBridgeAsyncThunk.RELEASE_CONTEXT),
    ),
    carried = emptyList(),
    holder = holder,
    csharpKey = key,
  )
  // ADR-233: a nullable flow is the null key.
  if (slot.result.nullable) appendLine("${indent}val $key: COpaquePointer = $call ?: return null")
  else appendLine("${indent}val $key: COpaquePointer = $call!!")
  appendLine("${indent}val $holder = ${asyncCallbackHolderConstruction(slots)}")
  val flow: String = flowSourceExpression(slot.name, slot.flowSource(), slots, holderBuilt = true)
  val cast: String = if (slot.element?.typeParameter == true) " as ${slot.result.kotlin}" else ""
  appendLine("${indent}return ($flow)$cast")
}

/** The `argNRef` handle each [ForwardBridgeWire.OBJECT] argument of [slot] crosses as. */
private fun StringBuilder.appendArgumentRefs(slot: ForwardBridgeSlot, indent: String) {
  slot.parameters.forEachIndexed { index, parameter ->
    if (parameter.type.wire == ForwardBridgeWire.OBJECT) {
      val reference: String = parameter.name.kotlinIdentifier()
      // ADR-201 amendment: a Throwable crosses as its ADR-107 envelope, which C# reads (and
      // disposes) with `BuildException`. A null argument crosses as the null pointer, for a
      // String as for a Throwable (`null as Any` threw before C# was ever called).
      val boxed: (String) -> String = { value ->
        if (parameter.type.throwable != null) "buildError($value, ::nugetMappedType)"
        else "$value as Any"
      }
      // ADR-233: a boxed slot hands C# the value the plan's own result projection would.
      val box: BridgeType? = parameter.type.boxed
      val line: String = when {
        box != null && box is BridgeType.Nullable ->
          "$reference?.let { NugetHandles.retain(${boxOut(box.type, "it")} as Any) }"
        box != null -> "NugetHandles.retain(${boxOut(box, reference)} as Any)"
        parameter.type.nullable -> "$reference?.let { NugetHandles.retain(${boxed("it")}) }"
        else -> "NugetHandles.retain(${boxed(reference)})"
      }
      appendLine("${indent}val arg${index}Ref = $line")
    }
  }
}

/** The `fooFn.invoke(...)` text: marshalled arguments in slot order, then the slot's context. */
private fun invocation(slot: ForwardBridgeSlot): String {
  val args: List<String> = slotArguments(slot)
  // ADR-161: the ADR-084 slot's invocation goes through the error channel, so a C# member that
  // throws becomes a `NugetManagedException` at the Kotlin call site of the interface member. The
  // result marshalling below stays outside the helper, so the error is read before any `!!`.
  val invokeArgs: String = (args + "${slot.slotPrefix}Ctx" + "nugetErr").joinToString(", ")
  return "nugetCallbackCall { nugetErr -> ${slot.slotPrefix}Fn.invoke($invokeArgs) }"
}

/** The marshalled payload arguments of [slot], in slot order. */
private fun slotArguments(slot: ForwardBridgeSlot): List<String> =
  slot.parameters.mapIndexed { index, parameter ->
    when (parameter.type.wire) {
      ForwardBridgeWire.OBJECT -> "arg${index}Ref"
      ForwardBridgeWire.BOOLEAN ->
        "if (${parameter.name.kotlinIdentifier()}) 1.toByte() else 0.toByte()"
      ForwardBridgeWire.ENUM -> "${parameter.name.kotlinIdentifier()}.ordinal"
      else -> parameter.name.kotlinIdentifier()
    }
  }

private fun StringBuilder.appendResultMarshalling(
  slot: ForwardBridgeSlot,
  call: String,
  indent: String,
) {
  when (slot.result.wire) {
    ForwardBridgeWire.UNIT -> appendLine("$indent$call")

    ForwardBridgeWire.OBJECT -> {
      // ADR-222: a `T` result is the box `WrapOwned<T>` minted, read and released as lane A does.
      // ADR-232: a hot flow is the flow object's own handle, C# retained, read the same way.
      if (slot.result.typeParameter || slot.result.hotFlow) {
        if (slot.result.nullable) {
          appendLine("${indent}val ref = $call ?: return null as ${slot.result.kotlin}")
        } else {
          appendLine("${indent}val ref = $call!!")
        }
        appendLine("${indent}val value = ref.asStableRef<Any>().get() as ${slot.result.kotlin}")
        appendLine("${indent}NugetHandles.release(ref)")
        appendLine("${indent}return value")
        return
      }
      // ADR-233: a C# lambda is the key of its registered delegate; the holder owns the key.
      val callback: BridgeType.Callback? = slot.returnedCallback
      if (callback != null) {
        val holder = "nugetLambdaHolder"
        appendLine("${indent}val $holder = $ASYNC_CALLBACK_HOLDER(${ForwardBridgeAsyncThunk.RELEASE_CONTEXT.parameter}, $call!!)")
        val lambda: String = loweredCallbackExpression(
          "nugetLambda", callback, slot.lambdaThunkParameter, "$holder.key",
        )
        appendLine("${indent}return $lambda".replace("\n", "\n$indent"))
        return
      }
      // ADR-233: a boxed result is an owned box C# minted, read with the plan's own lowering.
      val box: BridgeType? = slot.result.boxed
      if (box != null) {
        if (box is BridgeType.Nullable) {
          appendLine("${indent}val ref = $call ?: return null${nullCast(slot.result.kotlin)}")
        } else {
          appendLine("${indent}val ref = $call!!")
        }
        appendLine("${indent}val value = ${boxIn(box.unwrapNullable(), "ref", slot.result.kotlin.removeSuffix("?"))}")
        appendLine("${indent}NugetHandles.release(ref)")
        appendLine("${indent}return value")
        return
      }
      // ADR-084: the null String slot is the null pointer; `IntPtr.Zero` never means "empty".
      if (slot.result.nullable) {
        appendLine("${indent}val ref = $call ?: return null")
      } else {
        appendLine("${indent}val ref = $call!!")
      }
      appendLine("${indent}val value = ref.asStableRef<String>().get()")
      appendLine("${indent}NugetHandles.release(ref)")
      // ADR-201 amendment: a Throwable result is the managed-exception text C# boxed.
      val returned: String = if (slot.result.throwable != null) {
        managedExceptionLowering("value", nullable = false)
      } else {
        "value"
      }
      appendLine("${indent}return $returned")
    }

    ForwardBridgeWire.BOOLEAN -> appendLine("${indent}return $call != 0.toByte()")
    ForwardBridgeWire.ENUM -> appendLine("${indent}return ${slot.result.kotlin}.entries[$call]")
    else -> appendLine("${indent}return $call")
  }
}

/**
 * ADR-233: a non-null Kotlin value of [type] as the object its box holds, exactly what a class
 * member's result of that type boxes: a collection with its components projected (ADR-081), an
 * `Instant` or `Duration` as its .NET ticks, a `Uuid` as its text, everything else as itself.
 */
private fun boxOut(type: BridgeType, value: String): String = when (type) {
  BridgeType.Instant, BridgeType.Duration -> "$value.toDotNetTicks()"
  BridgeType.Uuid -> "$value.toString()"
  is BridgeType.Collection -> collectionResultProjection(value, type)
  is BridgeType.Nullable -> "$value?.let { ${boxOut(type.type, "it")} }"
  is BridgeType.Primitive,
  BridgeType.Char,
  BridgeType.String,
  is BridgeType.Enum,
  is BridgeType.ObjectHandle,
  is BridgeType.Interface,
  is BridgeType.BoundInterface,
  is BridgeType.ValueClass,
  BridgeType.ByteArray,
  is BridgeType.TypeParameter,
  BridgeType.Unit,
  is BridgeType.Throwable,
  is BridgeType.Callback,
  is BridgeType.AsyncCallback,
  is BridgeType.FlowSource,
  is BridgeType.ReturnedLambda,
  is BridgeType.SpecializedProtocol,
  is BridgeType.RawKSType,
  is BridgeType.Unsupported,
  is BridgeType.RawCollection,
    -> value
}

/**
 * ADR-233: the non-null Kotlin value of [type] the box at [ref] holds, read exactly as a class
 * member's parameter of that type reads it; [kotlin] is the declared spelling.
 */
private fun boxIn(type: BridgeType, ref: String, kotlin: String): String {
  val raw = "$ref.asStableRef<Any>().get()"
  return when (type) {
    BridgeType.Instant -> "$RUNTIME.instantFromDotNetTicks($raw as Long)"
    BridgeType.Duration -> "$RUNTIME.durationFromDotNetTicks($raw as Long)"
    BridgeType.Uuid -> "kotlin.uuid.Uuid.parse($raw as String)"
    is BridgeType.Collection -> "(${loweredCollectionExpression(ref, type)}) as $kotlin"
    is BridgeType.Primitive,
    BridgeType.Char,
    BridgeType.String,
    is BridgeType.Enum,
    is BridgeType.ObjectHandle,
    is BridgeType.Interface,
    is BridgeType.BoundInterface,
    is BridgeType.ValueClass,
    BridgeType.ByteArray,
    is BridgeType.Nullable,
    is BridgeType.TypeParameter,
    BridgeType.Unit,
    is BridgeType.Throwable,
    is BridgeType.Callback,
    is BridgeType.AsyncCallback,
    is BridgeType.FlowSource,
    is BridgeType.ReturnedLambda,
    is BridgeType.SpecializedProtocol,
    is BridgeType.RawKSType,
    is BridgeType.Unsupported,
    is BridgeType.RawCollection,
      -> "$raw as $kotlin"
  }
}

/** ADR-233: whether a slot of [slots] boxes an `Instant` or `Duration`, which need an import. */
internal fun List<ForwardBridgeSlot>.boxesTicks(): Boolean =
  flatMap { slot -> slot.parameters.map { it.type } + slot.result }
    .mapNotNull { type -> type.boxed }
    .any { box -> box.mentionsTicks() }

private fun BridgeType.mentionsTicks(): Boolean =
  this == BridgeType.Instant || this == BridgeType.Duration ||
    (this is BridgeType.Nullable && type.mentionsTicks()) ||
    (this is BridgeType.Collection && listOfNotNull(element, key, value).any { it.mentionsTicks() })

/**
 * ADR-237: ` as R` when a null result must be cast to a member's own type parameter, which `null`
 * is not assignable to; empty for a nullable type, which takes `null` as is.
 */
private fun nullCast(kotlin: String): String = if (kotlin.endsWith("?")) "" else " as $kotlin"
