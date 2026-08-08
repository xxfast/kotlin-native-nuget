package io.github.xxfast.kotlin.native.nuget.processor.cir

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardBridgeInterfacePlan
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardBridgeSlot
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardBridgeWire
import io.github.xxfast.kotlin.native.nuget.processor.forward.csharpWire
import io.github.xxfast.kotlin.native.nuget.processor.forward.delegateName
import io.github.xxfast.kotlin.native.nuget.processor.forward.delegateParamList

/**
 * ADR-084 stage 1, the C# half of the bridge factory: `NugetBridge.HandleFor` (the fallback
 * `NugetMarshal.HandleOf` reaches when a value carries no `_handle`, i.e. it is a C#-implemented
 * Kotlin interface) plus one `{Iface}BridgeState` per bridgeable interface, holding the pinned
 * delegates and the Kotlin-side bridge handle.
 *
 * Slot order comes from [ForwardBridgeInterfacePlan.slots], the same list the Kotlin export is
 * projected from, so the two flat argument lists cannot drift.
 */
internal fun StringBuilder.renderBridgeHelper(helper: CirBridgeHelper) {
  appendLine("    internal static class NugetBridge")
  appendLine("    {")
  // ADR-084 stage 3: one bridge per *crossing*, not per object. The handle the factory returns is a
  // transfer the call site disposes once the native call is done, which is what leaves Kotlin's own
  // reference as the bridge's only root; caching it would hand out a disposed StableRef next time.
  // C#-side identity does not depend on the reuse: it comes from the token (facet 5).
  appendLine("        internal static IntPtr HandleFor(object impl)")
  appendLine("        {")
  helper.interfaces.forEach { entry ->
    val plan: ForwardBridgeInterfacePlan = entry.plan
    appendLine("            if (impl is ${entry.csQualifiedName} ${plan.simpleName.lowercase()}Impl)")
    appendLine("            {")
    appendLine("                return ${plan.stateClassName}.Create(${plan.simpleName.lowercase()}Impl).KotlinHandle;")
    appendLine("            }")
  }
  appendLine("            throw new NotSupportedException(")
  appendLine("                \$\"{impl.GetType().Name} implements no bridgeable Kotlin interface.\");")
  appendLine("        }")
  appendLine()
  appendLine("        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"nuget_gc_collect\")]")
  appendLine("        private static extern void Native_GcCollect();")
  appendLine()
  // ADR-084 stage 2: the release is cleaner-driven, so it fires on a *later* Kotlin GC cycle, never
  // promptly. Hosts that need to observe it (and the release test) force a round here.
  appendLine("        internal static void GcCollect() => Native_GcCollect();")
  appendLine()
  appendLine("    }")
  appendLine()

  appendLine("    internal abstract class NugetBridgeState")
  appendLine("    {")
  appendLine("        internal IntPtr KotlinHandle;")
  appendLine()
  // The counter is the release path's only observable: the Kotlin cleaner has no return value and
  // runs on its own worker thread, so a test cannot see the callback any other way.
  appendLine("        internal static int ReleasedCount;")
  appendLine()
  appendLine("        private readonly System.Collections.Generic.List<GCHandle> _pins = new();")
  appendLine("        private GCHandle _self;")
  appendLine("        private GCHandle _token;")
  appendLine("        private int _freed;")
  appendLine()
  appendLine("        internal void Pin(params Delegate[] delegates)")
  appendLine("        {")
  appendLine("            foreach (Delegate value in delegates) _pins.Add(GCHandle.Alloc(value));")
  appendLine("        }")
  appendLine()
  // The strong self handle is both the slots' ctx and what keeps this state (and through its
  // delegates, the implementing object) alive for exactly as long as the Kotlin bridge lives.
  appendLine("        internal IntPtr Root()")
  appendLine("        {")
  appendLine("            _self = GCHandle.Alloc(this);")
  appendLine("            return GCHandle.ToIntPtr(_self);")
  appendLine("        }")
  appendLine()
  appendLine()
  // Called from the Kotlin cleaner worker thread, once, when the bridge object is collected.
  // Freeing the release delegate's own pin from inside its invocation is safe: the executing
  // delegate is rooted by the call in progress.
  // ADR-084 facet 5: the identity token is a GCHandle to the implementing object itself, so the
  // shared probe resolves the original instance without knowing any bridge-state type. It also
  // roots that object for exactly as long as the Kotlin bridge lives, the ARC analogue.
  appendLine("        internal IntPtr TokenFor(object impl)")
  appendLine("        {")
  appendLine("            _token = GCHandle.Alloc(impl);")
  appendLine("            return GCHandle.ToIntPtr(_token);")
  appendLine("        }")
  appendLine()
  appendLine("        internal void FreeAll()")
  appendLine("        {")
  appendLine("            if (System.Threading.Interlocked.Exchange(ref _freed, 1) != 0) return;")
  appendLine("            foreach (GCHandle pin in _pins)")
  appendLine("            {")
  appendLine("                if (pin.IsAllocated) pin.Free();")
  appendLine("            }")
  appendLine("            _pins.Clear();")
  appendLine("            if (_self.IsAllocated) _self.Free();")
  appendLine("            if (_token.IsAllocated) _token.Free();")
  appendLine("            System.Threading.Interlocked.Increment(ref ReleasedCount);")
  appendLine("        }")
  appendLine()
  appendLine("    }")
  appendLine()

  helper.interfaces.forEach { entry -> renderBridgeState(helper.libraryName, entry) }
}

private fun StringBuilder.renderBridgeState(libraryName: String, entry: CirBridgeInterface) {
  val plan: ForwardBridgeInterfacePlan = entry.plan
  appendLine("    internal sealed class ${plan.stateClassName} : NugetBridgeState")
  appendLine("    {")
  val nativeParams: List<String> = plan.slots.flatMap { slot ->
    listOf("IntPtr ${slot.slotPrefix}Ptr", "IntPtr ${slot.slotPrefix}Ctx")
  } + listOf("IntPtr releasePtr", "IntPtr releaseCtx", "IntPtr token", "out IntPtr error")
  appendLine("        [DllImport(\"$libraryName\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"${plan.exportName}\")]")
  appendLine("        private static extern IntPtr Native_Create(${nativeParams.joinToString(", ")});")
  appendLine()
  appendLine("        internal static ${plan.stateClassName} Create(${entry.csQualifiedName} impl)")
  appendLine("        {")
  appendLine("            var state = new ${plan.stateClassName}();")
  appendLine("            IntPtr ctx = state.Root();")
  appendLine("            IntPtr token = state.TokenFor(impl);")
  plan.slots.forEach { slot ->
    appendLine("            ${slot.delegateName()} ${slot.slotPrefix} = ${slotLambda(slot)};")
  }
  appendLine("            NugetBridgeVoidCallback release = _ => state.FreeAll();")
  val pinned: String = (plan.slots.map { it.slotPrefix } + "release").joinToString(", ")
  appendLine("            state.Pin($pinned);")
  val callArgs: List<String> = plan.slots.flatMap { slot ->
    listOf("Marshal.GetFunctionPointerForDelegate(${slot.slotPrefix})", "ctx")
  } + listOf("Marshal.GetFunctionPointerForDelegate(release)", "ctx", "token", "out IntPtr error")
  appendLine("            state.KotlinHandle = Native_Create(")
  appendLine("                ${callArgs.joinToString(", ")});")
  appendLine("            if (error != IntPtr.Zero) throw NugetErrorNative.BuildException(error);")
  appendLine("            return state;")
  appendLine("        }")
  appendLine("    }")
  appendLine()
}

/** The C# statement-lambda body assigned to one slot's delegate variable. */
private fun slotLambda(slot: ForwardBridgeSlot): String {
  val parameters: String = if (slot.parameters.isEmpty()) {
    "_"
  } else {
    "(${(slot.parameters.indices.map { "arg$it" } + "_").joinToString(", ")})"
  }

  val body: StringBuilder = StringBuilder()
  slot.parameters.forEachIndexed { index, parameter ->
    when (parameter.type.wire) {
      // FromHandle disposes the StableRef the Kotlin side minted; that side deliberately does not.
      ForwardBridgeWire.OBJECT ->
        body.append("${parameter.type.csharp} value$index = NugetMarshal.FromHandle<${parameter.type.csharp.removeSuffix("?")}>(arg$index); ")

      ForwardBridgeWire.BOOLEAN -> body.append("bool value$index = arg$index != 0; ")
      ForwardBridgeWire.ENUM -> body.append("${parameter.type.csharp} value$index = (${parameter.type.csharp})arg$index; ")
      else -> body.append("${parameter.type.csharp} value$index = arg$index; ")
    }
  }

  val arguments: String = slot.parameters.indices.joinToString(", ") { "value$it" }
  val access: String = if (slot.isProperty) "impl.${slot.csName}" else "impl.${slot.csName}($arguments)"

  when (slot.result.wire) {
    ForwardBridgeWire.UNIT -> body.append("$access;")
    ForwardBridgeWire.OBJECT -> {
      body.append("${slot.result.csharp} result = $access; ")
      if (slot.result.nullable) {
        body.append("return result is null ? IntPtr.Zero : NugetMarshal.WrapString(result);")
      } else {
        body.append("return NugetMarshal.WrapString(result);")
      }
    }

    ForwardBridgeWire.BOOLEAN -> body.append("return (byte)($access ? 1 : 0);")
    ForwardBridgeWire.ENUM -> body.append("return (int)$access;")
    else -> body.append("return $access;")
  }

  return "$parameters => { $body }"
}

/**
 * The delegate declarations the bridge states need, contributed to the shared
 * [CirCallbackDelegateHelper] so every callback type is declared in exactly one place.
 */
internal fun bridgeDelegates(interfaces: List<CirBridgeInterface>): List<CirCallbackDelegate> {
  val slotDelegates: List<CirCallbackDelegate> = interfaces.flatMap { entry -> entry.plan.slots }
    .map { slot ->
      CirCallbackDelegate(
        name = slot.delegateName(),
        paramList = slot.delegateParamList(),
        returnType = slot.result.wire.csharpWire(),
      )
    }
  val release = CirCallbackDelegate("NugetBridgeVoidCallback", "(IntPtr ctx)", "void")
  return (slotDelegates + release).distinctBy { delegate -> delegate.name }
}
