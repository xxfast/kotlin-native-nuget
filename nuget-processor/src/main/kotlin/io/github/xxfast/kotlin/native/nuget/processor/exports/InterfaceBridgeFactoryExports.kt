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
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardBridgeWire
import io.github.xxfast.kotlin.native.nuget.processor.forward.kotlinWire
import io.github.xxfast.kotlin.native.nuget.processor.forward.managedExceptionLowering

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
  val body: String = buildString {
    appendLine("return try {")
    plan.slots.forEach { slot ->
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
    appendLine("  val bridge = object : ${plan.qualifiedName}, NugetCSharpBridge {")
    // ADR-084 facet 5: the token is a GCHandle to the *implementing C# object*, so the return-
    // position probe resolves the original instance without knowing any bridge-state type.
    appendLine("    override val nugetToken: COpaquePointer = token")
    appendLine("    @Suppress(\"unused\")")
    appendLine("    private val cleaner = createCleaner(releaseFn to releaseCtx) { (fn, ctx) ->")
    appendLine("      fn.invoke(ctx, null)")
    appendLine("    }")
    plan.slots.forEach { slot -> appendSlotOverride(slot) }
    appendLine("  }")
    appendLine("  NugetHandles.retain(bridge)")
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

  plan.slots.forEach { slot ->
    builder.addParameter("${slot.slotPrefix}Ptr", cOpaquePointer)
    builder.addParameter("${slot.slotPrefix}Ctx", cOpaquePointer)
  }
  builder.addParameter("releasePtr", cOpaquePointer)
  builder.addParameter("releaseCtx", cOpaquePointer)
  builder.addParameter("token", cOpaquePointer)
  builder.addParameter("errorOut", cOpaquePointer.copy(nullable = true))
  builder.returns(cOpaquePointer.copy(nullable = true))
  builder.addCode(body)

  addFunction(builder.build())
}

/**
 * One slot's `override`, forwarding to `${slotPrefix}Fn` with `${slotPrefix}Ctx`. Also the ADR-039
 * subscription route's listener-property override, so a listener `val` reads and releases its
 * result exactly as it does here.
 */
internal fun StringBuilder.appendSlotOverride(slot: ForwardBridgeSlot) {
  val call: String = invocation(slot)
  if (slot.isProperty) {
    appendLine("    override val ${slot.name.kotlinIdentifier()}: ${slot.result.kotlin}")
    appendLine("      get() {")
    appendResultMarshalling(slot, call, "        ")
    appendLine("      }")
    return
  }

  val params: String =
    slot.parameters.joinToString(", ") { "${it.name.kotlinIdentifier()}: ${it.type.kotlin}" }
  appendLine("    override fun ${slot.name.kotlinIdentifier()}($params): ${slot.result.kotlin} {")
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
      val line: String = if (parameter.type.nullable) {
        "$reference?.let { NugetHandles.retain(${boxed("it")}) }"
      } else {
        "NugetHandles.retain(${boxed(reference)})"
      }
      appendLine("      val arg${index}Ref = $line")
    }
  }
  appendResultMarshalling(slot, call, "      ")
  appendLine("    }")
}

/** The `fooFn.invoke(...)` text: marshalled arguments in slot order, then the slot's context. */
private fun invocation(slot: ForwardBridgeSlot): String {
  val args: List<String> = slot.parameters.mapIndexed { index, parameter ->
    when (parameter.type.wire) {
      ForwardBridgeWire.OBJECT -> "arg${index}Ref"
      ForwardBridgeWire.BOOLEAN ->
        "if (${parameter.name.kotlinIdentifier()}) 1.toByte() else 0.toByte()"
      ForwardBridgeWire.ENUM -> "${parameter.name.kotlinIdentifier()}.ordinal"
      else -> parameter.name.kotlinIdentifier()
    }
  }
  // ADR-161: the ADR-084 slot's invocation goes through the error channel, so a C# member that
  // throws becomes a `NugetManagedException` at the Kotlin call site of the interface member. The
  // result marshalling below stays outside the helper, so the error is read before any `!!`.
  val invokeArgs: String = (args + "${slot.slotPrefix}Ctx" + "nugetErr").joinToString(", ")
  return "nugetCallbackCall { nugetErr -> ${slot.slotPrefix}Fn.invoke($invokeArgs) }"
}

private fun StringBuilder.appendResultMarshalling(
  slot: ForwardBridgeSlot,
  call: String,
  indent: String,
) {
  when (slot.result.wire) {
    ForwardBridgeWire.UNIT -> appendLine("$indent$call")

    ForwardBridgeWire.OBJECT -> {
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
