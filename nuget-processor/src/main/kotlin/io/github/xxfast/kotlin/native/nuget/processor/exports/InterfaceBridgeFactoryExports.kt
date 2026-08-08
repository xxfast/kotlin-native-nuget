package io.github.xxfast.kotlin.native.nuget.processor.exports

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
      val args: String = (slot.parameters.map { it.type.wire.kotlinWire() } + "COpaquePointer")
        .joinToString(", ")
      appendLine(
        "  val ${slot.slotPrefix}Fn = ${slot.slotPrefix}Ptr" +
            ".reinterpret<CFunction<($args) -> ${slot.result.wire.kotlinWire()}>>()"
      )
    }
    appendLine("  val releaseFn = releasePtr.reinterpret<CFunction<(COpaquePointer) -> Unit>>()")
    appendLine("  val bridge = object : ${plan.qualifiedName}, NugetCSharpBridge {")
    // ADR-084 facet 5: the token is a GCHandle to the *implementing C# object*, so the return-
    // position probe resolves the original instance without knowing any bridge-state type.
    appendLine("    override val nugetToken: COpaquePointer = token")
    appendLine("    @Suppress(\"unused\")")
    appendLine("    private val cleaner = createCleaner(releaseFn to releaseCtx) { (fn, ctx) ->")
    appendLine("      fn.invoke(ctx)")
    appendLine("    }")
    plan.slots.forEach { slot -> appendSlotOverride(slot) }
    appendLine("  }")
    appendLine("  StableRef.create(bridge).asCPointer()")
    appendLine("} catch (e: Throwable) {")
    appendLine("  if (errorOut != null) {")
    appendLine("    errorOut.reinterpret<COpaquePointerVar>().pointed.value = StableRef.create(")
    appendLine("      buildError(e)")
    appendLine("    ).asCPointer()")
    appendLine("  }")
    appendLine("  null")
    append("}")
  }

  val builder: FunSpec.Builder = FunSpec.builder("export_${plan.exportName}")
    .addAnnotation(cNameAnnotation(plan.exportName))
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
 * ADR-084 facet 5: the marker every generated bridge object implements, plus the shared probe the
 * C# return position asks before wrapping a handle. A Kotlin-backed object answers null (it does
 * not implement the marker), so the ordinary wrapper construction stands; a bridge answers the
 * GCHandle of the C# object behind it, and C# hands that original instance back instead of
 * double-bridging it.
 *
 * Both are emitted unconditionally: the C# probe lives in `NugetMarshal`, which every module with
 * an interface return has, including modules whose interfaces did not plan a bridge factory.
 */
internal fun FileSpec.Builder.addCSharpBridgeMarker() {
  addType(
    TypeSpec.interfaceBuilder("NugetCSharpBridge")
      .addModifiers(KModifier.INTERNAL)
      .addProperty("nugetToken", cOpaquePointer)
      .build()
  )
  addFunction(
    FunSpec.builder("export_nuget_csharp_token")
      .addAnnotation(cNameAnnotation("nuget_csharp_token"))
      .addParameter("handle", cOpaquePointer)
      .returns(cOpaquePointer.copy(nullable = true))
      .addStatement(
        "return (handle.asStableRef<Any>().get() as? NugetCSharpBridge)?.nugetToken",
      )
      .build()
  )
}

/**
 * ADR-084 stage 2: the support export that forces a Kotlin GC round, so a host (and the release
 * test) can observe the cleaner-driven release deterministically instead of waiting for a natural
 * collection. Emitted alongside the factories, never on its own.
 */
internal fun FileSpec.Builder.addGcCollectExport() {
  addFunction(
    FunSpec.builder("export_nuget_gc_collect")
      .addAnnotation(cNameAnnotation("nuget_gc_collect"))
      .addAnnotation(
        AnnotationSpec.builder(ClassName("kotlin", "OptIn"))
          .addMember("%T::class", ClassName("kotlin.native.runtime", "NativeRuntimeApi"))
          .build()
      )
      .addStatement("%T.collect()", ClassName("kotlin.native.runtime", "GC"))
      .build()
  )
}

private fun StringBuilder.appendSlotOverride(slot: ForwardBridgeSlot) {
  val call: String = invocation(slot)
  if (slot.isProperty) {
    appendLine("    override val ${slot.name}: ${slot.result.kotlin}")
    appendLine("      get() {")
    appendResultMarshalling(slot, call, "        ")
    appendLine("      }")
    return
  }

  val params: String = slot.parameters.joinToString(", ") { "${it.name}: ${it.type.kotlin}" }
  appendLine("    override fun ${slot.name}($params): ${slot.result.kotlin} {")
  slot.parameters.forEachIndexed { index, parameter ->
    if (parameter.type.wire == ForwardBridgeWire.OBJECT) {
      appendLine("      val arg${index}Ref = StableRef.create(${parameter.name} as Any).asCPointer()")
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
      ForwardBridgeWire.BOOLEAN -> "if (${parameter.name}) 1.toByte() else 0.toByte()"
      ForwardBridgeWire.ENUM -> "${parameter.name}.ordinal"
      else -> parameter.name
    }
  }
  return "${slot.slotPrefix}Fn.invoke(${(args + "${slot.slotPrefix}Ctx").joinToString(", ")})"
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
      appendLine("${indent}ref.asStableRef<Any>().dispose()")
      appendLine("${indent}return value")
    }

    ForwardBridgeWire.BOOLEAN -> appendLine("${indent}return $call != 0.toByte()")
    ForwardBridgeWire.ENUM -> appendLine("${indent}return ${slot.result.kotlin}.entries[$call]")
    else -> appendLine("${indent}return $call")
  }
}
