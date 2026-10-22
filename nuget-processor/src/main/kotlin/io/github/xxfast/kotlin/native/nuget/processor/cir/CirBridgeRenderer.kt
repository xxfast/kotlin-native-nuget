package io.github.xxfast.kotlin.native.nuget.processor.cir

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardBridgeInterfacePlan
import io.github.xxfast.kotlin.native.nuget.processor.forward.bridgeImplVariable
import io.github.xxfast.kotlin.native.nuget.processor.forward.isGeneric
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardBridgeSlot
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardBridgeSlotKind
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardBridgeWire
import io.github.xxfast.kotlin.native.nuget.processor.forward.csharpWire
import io.github.xxfast.kotlin.native.nuget.processor.forward.delegateName
import io.github.xxfast.kotlin.native.nuget.processor.forward.delegateParamList
import io.github.xxfast.kotlin.native.nuget.processor.forward.managedExceptionTextCs
import io.github.xxfast.kotlin.native.nuget.processor.toCSharpName

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
  // ADR-222: a generic interface never reaches `HandleFor`: its call sites name its own entry.
  val plain: List<CirBridgeInterface> = helper.interfaces.filter { entry -> !entry.plan.isGeneric }
  appendLine("        internal static IntPtr HandleFor(object impl, Type declared)")
  appendLine("        {")
  // Interface super-interfaces: the declared parameter type selects the bridge first. A C# class
  // implementing `IHouseCat` is also an `IPet` and an `INamed`, so an `is` test alone picks by
  // generation order; at an `INamed` parameter it must be the `INamed` bridge (and a class
  // implementing two unrelated bridged interfaces must get the one the parameter names). No
  // pattern variable here: the compiler already guarantees `impl` is the declared type.
  plain.forEach { entry ->
    val plan: ForwardBridgeInterfacePlan = entry.plan
    val implCast: String = "(${entry.csQualifiedName})impl"
    appendLine("            if (declared == typeof(${entry.csQualifiedName}))")
    appendLine("            {")
    appendLine("                return ${plan.stateClassName}.Create($implCast).KotlinHandle;")
    appendLine("            }")
  }
  // Fallback for a static type that names no bridgeable interface directly (`object`, a class
  // type): the runtime type, in generation order.
  plain.forEach { entry ->
    val plan: ForwardBridgeInterfacePlan = entry.plan
    // ADR-133: spelled off the state class, not off the simple name -- every arm of this one
    // block declares its pattern variable in the same scope, so two owners' nested `Keeper`s
    // declared `keeperImpl` twice (CS0128). Deriving it from `stateClassName` is also what keeps
    // the two names from drifting; a top-level interface still reads `petImpl`.
    val implVariable: String = plan.bridgeImplVariable()
    appendLine("            if (impl is ${entry.csQualifiedName} $implVariable)")
    appendLine("            {")
    appendLine("                return ${plan.stateClassName}.Create($implVariable).KotlinHandle;")
    appendLine("            }")
  }
  appendLine("            throw new NotSupportedException(")
  // ADR-084: names the declared interface and where the build said why, never only the C# type.
  appendLine("                $\"{impl.GetType().Name} is not a Kotlin-backed object, \" +")
  appendLine("                $\"and {declared.Name} has no bridge for C# implementations; \" +")
  appendLine("                \"the Kotlin build names the members that keep it out \" +")
  appendLine("                \"(SKIPPED_* in NugetDiagnostics.json).\");")
  appendLine("        }")
  helper.interfaces.filter { entry -> entry.plan.isGeneric }
    .forEach { entry -> renderGenericEntry(helper.libraryName, entry) }
  // ADR-186: the `nuget_gc_collect` import lives on `NugetMarshal` (CirMarshalRenderer), which
  // every library renders, not here behind the bridge-plan gate.
  appendLine()
  appendLine("    }")
  appendLine()

  // ADR-224: the erased mint a bridged generic interface implements as a default method, so a
  // C# implementation at an erased position (`Wrap<T>` in a `Box<IKeepsake<X>>`) mints its
  // bridge at the instantiation the static `T` names, under NativeAOT, with no reflection. Public
  // because `IKeepsake<T>` lists it; its one member is internal, so no consumer can call it.
  appendLine(
    "    [global::System.ComponentModel.EditorBrowsable(" +
      "global::System.ComponentModel.EditorBrowsableState.Never)]",
  )
  appendLine("    public interface INugetBridgeable<out TSelf>")
  appendLine("    {")
  appendLine("        internal IntPtr MintBridge();")
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
  // ADR-161 part C: the slot ctxs are table KEYS now, because the bridge delegates share the one
  // thunk shell with the stored-callback and per-call routes. Had they stayed GCHandles every
  // ADR-084 slot call would miss the table and report ObjectDisposedException.
  appendLine("        private readonly System.Collections.Generic.List<IntPtr> _pins = new();")
  appendLine("        private GCHandle _self;")
  appendLine("        private GCHandle _token;")
  appendLine("        private int _freed;")
  appendLine()
  // ADR-102: the pin is also the slot's ctx -- the thunk recovers this exact delegate from it -- so
  // Pin hands the ctx back instead of swallowing it. ADR-161 part C: the table entry IS the strong
  // reference that used to be the GCHandle, so lifetime is unchanged (rooted until `FreeAll`) and
  // `TryRemove` un-roots it exactly as `Free()` did.
  appendLine("        internal IntPtr Pin(Delegate value)")
  appendLine("        {")
  appendLine("            IntPtr key = NugetThunks.RegisterCtx(value);")
  appendLine("            _pins.Add(key);")
  appendLine("            return key;")
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
  appendLine("            foreach (IntPtr pin in _pins)")
  appendLine("            {")
  appendLine("                NugetThunks.UnregisterCtx(pin);")
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
  // ADR-222: a P/Invoke cannot live in a generic class, so a generic state's import sits on
  // `NugetBridge` beside its entry.
  val generic: Boolean = plan.isGeneric
  val state: String = plan.stateClassName + entry.typeArgumentList()
  appendLine("    internal sealed class $state : NugetBridgeState${entry.whereClauses()}")
  appendLine("    {")
  if (!generic) renderBridgeImport(libraryName, plan, "private", "Native_Create")
  val native: String =
    if (generic) "NugetBridge.Native_${plan.stateClassName}Create" else "Native_Create"
  appendLine("        internal static $state Create(${entry.csQualifiedName}${entry.typeArgumentList()} impl)")
  appendLine("        {")
  appendLine("            var state = new $state();")
  // Root() still allocates the strong self handle that keeps this state (and the implementing
  // object it closes over) alive for as long as the Kotlin bridge lives; it is no longer the
  // slots' ctx, because each slot's thunk dispatches through its own delegate handle.
  appendLine("            state.Root();")
  appendLine("            IntPtr token = state.TokenFor(impl);")
  plan.slots.forEach { slot ->
    appendLine("            ${slot.delegateName()} ${slot.slotPrefix} = ${slotLambda(slot)};")
  }
  appendLine("            NugetBridgeVoidCallback release = _ => state.FreeAll();")
  (plan.slots.map { it.slotPrefix } + "release").forEach { pinned ->
    appendLine("            IntPtr ${pinned}Ctx = state.Pin($pinned);")
  }
  val callArgs: List<String> = plan.slots.flatMap { slot ->
    listOf("NugetThunks.${slot.delegateName()}Ptr", "${slot.slotPrefix}Ctx")
  } + listOf(
    "NugetThunks.NugetBridgeVoidCallbackPtr", "releaseCtx", "token", "out IntPtr error",
  )
  appendLine("            state.KotlinHandle = $native(")
  appendLine("                ${callArgs.joinToString(", ")});")
  appendLine("            if (error != IntPtr.Zero) throw NugetErrorNative.BuildException(error);")
  appendLine("            NugetErrorNative.ClearManagedFault();")
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
  return "$parameters => { ${slotBody(slot, "impl")} }"
}

/**
 * `(IntPtr arg0, IntPtr _)`: one slot's typed lambda parameters, its arguments then the ctx, for
 * the ADR-039 subscription route's listener-property lambda, so a setter reads `arg0` there too.
 */
internal fun slotLambdaParameters(slot: ForwardBridgeSlot): String {
  val arguments: List<String> = slot.parameters.mapIndexed { index, parameter ->
    "${parameter.type.wire.csharpWire()} arg$index"
  }
  return "(${(arguments + "IntPtr _").joinToString(", ")})"
}

/**
 * The statements of one slot's delegate body, calling the member on [receiver]. Also the ADR-039
 * subscription route's listener-property body (receiver `listener`), so a listener `val` returns
 * its value across exactly as it does on the bridge factory.
 */
internal fun slotBody(slot: ForwardBridgeSlot, receiver: String): String {
  val body: StringBuilder = StringBuilder()
  slot.parameters.forEachIndexed { index, parameter ->
    when (parameter.type.wire) {
      // ADR-201 amendment: the ADR-107 envelope, rebuilt (and disposed) by `BuildException`; a
      // null argument is the null pointer.
      // FromHandle disposes the StableRef the Kotlin side minted; that side deliberately does not.
      ForwardBridgeWire.OBJECT -> if (parameter.type.throwable != null) {
        val read = "NugetErrorNative.BuildException(arg$index)"
        val value: String =
          if (parameter.type.nullable) "arg$index == IntPtr.Zero ? null : $read" else read
        body.append("${parameter.type.csharp} value$index = $value; ")
      } else
        body.append(
          "${parameter.type.csharp} value$index = NugetMarshal.FromHandle<${
            parameter.type.csharp.removeSuffix(
              "?"
            )
          }>(arg$index); "
        )

      ForwardBridgeWire.BOOLEAN -> body.append("bool value$index = arg$index != 0; ")
      ForwardBridgeWire.ENUM -> body.append("${parameter.type.csharp} value$index = (${parameter.type.csharp})arg$index; ")
      else -> body.append("${parameter.type.csharp} value$index = arg$index; ")
    }
  }

  val arguments: String = slot.parameters.indices.joinToString(", ") { "value$it" }
  // ADR-179: the slot keeps the unescaped declared name; the call site escapes it.
  val member: String = toCSharpName(slot.csName)
  // ADR-084 amendment (2026-10-22): a setter slot assigns its one argument, read exactly as a
  // function slot reads it, and returns nothing.
  val access: String = when (slot.kind) {
    ForwardBridgeSlotKind.GETTER -> "$receiver.$member"
    ForwardBridgeSlotKind.SETTER -> "$receiver.$member = value0"
    ForwardBridgeSlotKind.FUNCTION -> "$receiver.$member($arguments)"
  }

  when (slot.result.wire) {
    ForwardBridgeWire.UNIT -> body.append("$access;")
    ForwardBridgeWire.OBJECT -> {
      body.append("${slot.result.csharp} result = $access; ")
      // ADR-222: a `T` result is a box Kotlin owns, minted by lane A's `WrapOwned<T>`.
      if (slot.result.typeParameter) {
        body.append("return NugetMarshal.WrapOwned<${slot.result.csharp.removeSuffix("?")}>(result!);")
        return body.toString()
      }
      // ADR-201 amendment: a Throwable result crosses as its managed-exception text.
      val text: String = if (slot.result.throwable != null) {
        managedExceptionTextCs("result", nullable = false)
      } else {
        "result"
      }
      if (slot.result.nullable) {
        body.append("return result is null ? IntPtr.Zero : NugetMarshal.WrapString($text);")
      } else {
        body.append("return NugetMarshal.WrapString($text);")
      }
    }

    ForwardBridgeWire.BOOLEAN -> body.append("return (byte)($access ? 1 : 0);")
    ForwardBridgeWire.ENUM -> body.append("return (int)$access;")
    else -> body.append("return $access;")
  }

  return body.toString()
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

/** The bridge factory's `[DllImport]`, under [name] with [visibility]. */
private fun StringBuilder.renderBridgeImport(
  libraryName: String,
  plan: ForwardBridgeInterfacePlan,
  visibility: String,
  name: String,
) {
  val nativeParams: List<String> = plan.slots.flatMap { slot ->
    listOf("IntPtr ${slot.slotPrefix}Ptr", "IntPtr ${slot.slotPrefix}Ctx")
  } + listOf("IntPtr releasePtr", "IntPtr releaseCtx", "IntPtr token", "out IntPtr error")
  appendLine("        [DllImport(\"$libraryName\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"${plan.exportName}\")]")
  appendLine("        $visibility static extern IntPtr $name(${nativeParams.joinToString(", ")});")
  appendLine()
}

/**
 * ADR-222: a generic interface's named entry (`KeepsakeHandleOf<T>`), inferring `T` from the call
 * site's declared `IKeepsake<T>`, plus its factory import. A Kotlin-backed value hands over its own
 * handle; a C# implementation mints a one-crossing transfer handle through `KeepsakeBridgeState<T>`.
 */
private fun StringBuilder.renderGenericEntry(libraryName: String, entry: CirBridgeInterface) {
  val plan: ForwardBridgeInterfacePlan = entry.plan
  val types: String = entry.typeArgumentList()
  val entryName: String = plan.stateClassName.removeSuffix("BridgeState") + "HandleOf"
  appendLine()
  renderBridgeImport(libraryName, plan, "internal", "Native_${plan.stateClassName}Create")
  appendLine("        internal static IntPtr $entryName$types(${entry.csQualifiedName}$types value, out bool owned)${entry.whereClauses()}")
  appendLine("        {")
  appendLine("            if (value is INugetHandle wrapper)")
  appendLine("            {")
  appendLine("                owned = false;")
  appendLine("                return wrapper.Handle;")
  appendLine("            }")
  // ADR-135: `owned` only after the mint, so a throwing mint never disposes IntPtr.Zero.
  appendLine("            IntPtr handle = ${plan.stateClassName}$types.Create(value).KotlinHandle;")
  appendLine("            owned = true;")
  appendLine("            return handle;")
  appendLine("        }")
}

/** `<T, U>` for a generic bridge, empty otherwise. */
private fun CirBridgeInterface.typeArgumentList(): String =
  if (typeParameters.isEmpty()) "" else typeParameters.joinToString(", ", "<", ">") { it.name }

/** Each constrained type parameter as ` where T : ...`, the interface's own constraints. */
private fun CirBridgeInterface.whereClauses(): String = typeParameters
  .filter { parameter -> parameter.bounds.isNotEmpty() }
  .joinToString("") { parameter ->
    " where ${parameter.name} : ${parameter.bounds.joinToString(", ")}"
  }
