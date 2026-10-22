package io.github.xxfast.kotlin.native.nuget.processor.cir

import io.github.xxfast.kotlin.native.nuget.processor.forward.BridgeType
import io.github.xxfast.kotlin.native.nuget.processor.forward.lambdaInvokeMethod
import io.github.xxfast.kotlin.native.nuget.processor.forward.lambdaWrapperCsharpType
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardBridgeInterfacePlan
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardBridgeAsync
import io.github.xxfast.kotlin.native.nuget.processor.forward.asyncBeginName
import io.github.xxfast.kotlin.native.nuget.processor.forward.asyncEndName
import io.github.xxfast.kotlin.native.nuget.processor.forward.asyncThunkParameters
import io.github.xxfast.kotlin.native.nuget.processor.forward.asyncThunks
import io.github.xxfast.kotlin.native.nuget.processor.forward.flowSource
import io.github.xxfast.kotlin.native.nuget.processor.forward.forwardAsyncCurrent
import io.github.xxfast.kotlin.native.nuget.processor.forward.forwardAsyncCurrentName
import io.github.xxfast.kotlin.native.nuget.processor.forward.bridgeImplVariable
import io.github.xxfast.kotlin.native.nuget.processor.forward.isGeneric
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardBridgeSlot
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardBridgeType
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardBridgeSlotKind
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardBridgeWire
import io.github.xxfast.kotlin.native.nuget.processor.forward.csharpWire
import io.github.xxfast.kotlin.native.nuget.processor.forward.delegateName
import io.github.xxfast.kotlin.native.nuget.processor.forward.delegateParamList
import io.github.xxfast.kotlin.native.nuget.processor.forward.managedExceptionTextCs
import io.github.xxfast.kotlin.native.nuget.processor.toCSharpName
import io.github.xxfast.kotlin.native.nuget.processor.forward.CollectionKind
import io.github.xxfast.kotlin.native.nuget.processor.forward.collectionCreateArgument
import io.github.xxfast.kotlin.native.nuget.processor.forward.componentCollectionRead
import io.github.xxfast.kotlin.native.nuget.processor.forward.durationLiftCs
import io.github.xxfast.kotlin.native.nuget.processor.forward.forwardPublicCsharpType
import io.github.xxfast.kotlin.native.nuget.processor.forward.instantLiftCs

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
  helper.hotFlowSegment?.let { segment ->
    renderHotFlowNative(helper.libraryName) { stem -> "${segment}__hotflow_$stem" }
  }
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
    appendLine("            ${slot.bridgeDelegateName()} ${slot.slotPrefix} = ${slotLambda(slot)};")
  }
  appendLine("            NugetBridgeVoidCallback release = _ => state.FreeAll();")
  (plan.slots.map { it.slotPrefix } + "release").forEach { pinned ->
    appendLine("            IntPtr ${pinned}Ctx = state.Pin($pinned);")
  }
  val callArgs: List<String> = plan.slots.flatMap { slot ->
    listOf("NugetThunks.${slot.bridgeDelegateName()}Ptr", "${slot.slotPrefix}Ctx") +
      slot.asyncThunkPointers()
  } + listOf("NugetThunks.NugetBridgeVoidCallbackPtr", "releaseCtx") +
    plan.asyncThunks().map { thunk -> thunk.pointer } +
    listOf("token", "out IntPtr error")
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
  if (slot.async == ForwardBridgeAsync.SUSPEND) {
    val parameters: String =
      (slot.parameters.indices.map { "arg$it" } + "nugetCallback" + "nugetAwaitCtx")
        .joinToString(", ")
    return "($parameters) => { ${suspendSlotBody(slot, "impl")} }"
  }
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
  val body: StringBuilder = StringBuilder(parameterReads(slot))
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
      // ADR-232: a Flow member hands Kotlin the key of an enumeration factory over its result,
      // which Kotlin reads through `flowForKotlin` and whose holder releases the key.
      if (slot.async == ForwardBridgeAsync.FLOW) {
        // ADR-233: a nullable flow member hands back the null key for a null flow.
        val guard: String = if (slot.result.nullable) "result is null ? IntPtr.Zero : " else ""
        body.append(
          "return ${guard}NugetTasks.Register(result, \"${slot.csName}\", " +
            "(Func<IntPtr>)(() => NugetAsyncEnumeration.Enumerate(result)));",
        )
        return body.toString()
      }
      // ADR-232: a hot flow hands back the Kotlin flow object it wraps, or fails at the slot.
      if (slot.result.hotFlow) {
        val guard: String = if (slot.result.nullable) "result is null ? IntPtr.Zero : " else ""
        body.append("return ${guard}NugetHotFlowNative.Retain(result);")
        return body.toString()
      }
      // ADR-233: a boxed result is written as a class member's parameter of its type is.
      val box: BridgeType? = slot.result.boxed
      if (box != null) {
        body.append("return ${boxWrite(box, "result")};")
        return body.toString()
      }
      // ADR-236: the lambda handle the wrapper carries, retained so Kotlin owns its own.
      val lambda: BridgeType.ReturnedLambda? = slot.result.lambda
      if (lambda != null) {
        val owner: String =
          if (lambda.suspend) "NugetManagedSuspendLambda" else "NugetManagedLambda"
        val wrapper: String = lambda.lambdaWrapperCsharpType()
        body.append("return result is null ? IntPtr.Zero : $owner.Result(($wrapper)result);")
        return body.toString()
      }
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

/** Each payload `argN` of [slot] read into its `valueN`, as statements. */
private fun parameterReads(slot: ForwardBridgeSlot): String {
  val body: StringBuilder = StringBuilder()
  slot.parameters.forEachIndexed { index, parameter ->
    when (parameter.type.wire) {
      // ADR-201 amendment: the ADR-107 envelope, rebuilt (and disposed) by `BuildException`; a
      // null argument is the null pointer.
      // FromHandle disposes the StableRef the Kotlin side minted; that side deliberately does not.
      // ADR-236: a lambda argument is a handle Kotlin retained, which the wrapper owns from here.
      ForwardBridgeWire.OBJECT -> if (parameter.type.lambda != null) {
        val lambda: BridgeType.ReturnedLambda = parameter.type.lambda
        val wrapper: String = lambda.lambdaWrapperCsharpType()
        val read = "new $wrapper(arg$index).${lambda.lambdaInvokeMethod()}"
        val value: String =
          if (parameter.type.nullable) "arg$index == IntPtr.Zero ? null : $read" else read
        body.append("${parameter.type.csharp} value$index = $value; ")
      } else if (parameter.type.boxed != null) {
        // ADR-233: read as a class member's result of the same type is, releasing the box.
        val box: BridgeType = requireNotNull(parameter.type.boxed)
        body.append("${parameter.type.csharp} value$index = ${boxRead(box, "arg$index")}; ")
      } else if (parameter.type.throwable != null) {
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
      ForwardBridgeWire.ENUM -> body.append(
        "${parameter.type.csharp} value$index = (${parameter.type.csharp})arg$index; ",
      )

      ForwardBridgeWire.UNIT,
      ForwardBridgeWire.INT,
      ForwardBridgeWire.LONG,
      ForwardBridgeWire.FLOAT,
      ForwardBridgeWire.DOUBLE ->
        body.append("${parameter.type.csharp} value$index = arg$index; ")
    }
  }

  return body.toString()
}

/**
 * The delegate declarations the bridge states need, contributed to the shared
 * [CirCallbackDelegateHelper] so every callback type is declared in exactly one place.
 */
internal fun bridgeDelegates(interfaces: List<CirBridgeInterface>): List<CirCallbackDelegate> {
  val slotDelegates: List<CirCallbackDelegate> = interfaces.flatMap { entry -> entry.plan.slots }
    // ADR-232: a SUSPEND slot's Begin delegate is declared by the ADR-210 async helper.
    .filter { slot -> slot.async != ForwardBridgeAsync.SUSPEND }
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
    listOfNotNull(
      "IntPtr ${slot.slotPrefix}Ptr",
      "IntPtr ${slot.slotPrefix}Ctx",
    ) + slot.asyncThunkParameters.map { thunk -> "IntPtr $thunk" }
  } + listOf("IntPtr releasePtr", "IntPtr releaseCtx") +
    plan.asyncThunks().map { thunk -> "IntPtr ${thunk.parameter}" } +
    listOf("IntPtr token", "out IntPtr error")
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

/**
 * ADR-232: a SUSPEND slot's Begin delegate body. It reads the payloads as the synchronous slot
 * does, then starts the implementation's `Task` through ADR-210's `NugetTasks.Begin`, which mints
 * the `CancellationTokenSource` Kotlin's cancellation reaches and attaches the completion. A `T`
 * result starts through `BeginBoxed`, so the End thunk can box it without naming `T`.
 */
private fun suspendSlotBody(slot: ForwardBridgeSlot, receiver: String): String {
  val arguments: String =
    (slot.parameters.indices.map { "value$it" } + "nugetToken").joinToString(", ")
  val element: ForwardBridgeType? = slot.element
  val begin: String = when {
    element?.typeParameter == true ->
      "NugetTasks.BeginFlow<${slot.result.csharp.removeSuffix("?")}, ${element.csharp.removeSuffix("?")}>"
    slot.result.typeParameter -> "NugetTasks.BeginBoxed<${slot.result.csharp.removeSuffix("?")}>"
    else -> "NugetTasks.Begin"
  }
  // ADR-233 amendment: the flow's enumeration factory is registered under the member's name.
  val named: String = if (element?.typeParameter == true) "\"${slot.csName}\", " else ""
  return parameterReads(slot) +
    "return $begin(nugetToken => $receiver.${toCSharpName(slot.csName)}($arguments), " +
    "${named}nugetCallback, nugetAwaitCtx);"
}

/** The delegate type a slot's pinned delegate is declared as. */
private fun ForwardBridgeSlot.bridgeDelegateName(): String =
  if (async == ForwardBridgeAsync.SUSPEND) asyncBeginName() else delegateName()

/**
 * ADR-232: the thunk pointers an async slot crosses after its pair, in `asyncThunkParameters`
 * order.
 */
private fun ForwardBridgeSlot.asyncThunkPointers(): List<String> = listOfNotNull(
  "NugetThunks.${asyncEndName()}Ptr".takeIf { async == ForwardBridgeAsync.SUSPEND },
  element?.let { "NugetThunks.${flowSource().forwardAsyncCurrentName()}Ptr" },
)

/** ADR-232: every Begin thunk the bridged interfaces' SUSPEND slots dispatch through. */
internal fun bridgeAsyncBegins(plans: List<ForwardBridgeInterfacePlan>): List<CirAsyncBegin> =
  plans.flatMap { plan -> plan.slots }
    .filter { slot -> slot.async == ForwardBridgeAsync.SUSPEND }
    .map { slot ->
      CirAsyncBegin(slot.asyncBeginName(), slot.parameters.map { it.type.wire.csharpWire() })
    }

/**
 * ADR-232: every End thunk the SUSPEND slots read through. Each applies the synchronous slot's
 * result conversion to the completed task's result, after the read is certain (ADR-210
 * alternative 8), and frees the task handle in `NugetTasks.End`'s `finally`.
 */
internal fun bridgeAsyncEnds(plans: List<ForwardBridgeInterfacePlan>): List<CirAsyncEnd> =
  plans.flatMap { plan -> plan.slots }
    .filter { slot -> slot.async == ForwardBridgeAsync.SUSPEND }
    .map { slot ->
      val type = slot.result
      val name: String = slot.asyncEndName()
      val exception = "global::System.Exception"
      when {
        // ADR-233 amendment: a Flow of the interface's own `T` was started through `BeginFlow`.
        slot.element?.typeParameter == true -> CirAsyncEnd(name, "IntPtr", "NugetTasks.EndBoxed(a0)")
        // ADR-232: a suspend member returning a Flow: the awaited flow's enumeration factory key.
        slot.element != null -> {
          val flow: String = slot.result.csharp
          CirAsyncEnd(
            name,
            "IntPtr",
            "NugetTasks.End<$flow>(a0) is $flow nugetValue ? NugetTasks.Register(nugetValue, " +
              "\"${slot.csName}\", (Func<IntPtr>)(() => NugetAsyncEnumeration.Enumerate(nugetValue))) : " +
              "IntPtr.Zero",
          )
        }

        type.typeParameter -> CirAsyncEnd(name, "IntPtr", "NugetTasks.EndBoxed(a0)")
        // ADR-233: an awaited hot flow hands back the Kotlin flow object it wraps.
        type.hotFlow -> CirAsyncEnd(
          name,
          "IntPtr",
          "NugetTasks.End<${type.csharp}>(a0) is var nugetValue && nugetValue is not null ? " +
            "NugetHotFlowNative.Retain(nugetValue) : IntPtr.Zero",
        )
        // ADR-233: the completed result written as the synchronous boxed slot writes it.
        type.boxed != null -> CirAsyncEnd(
          name,
          "IntPtr",
          "NugetTasks.End<${type.csharp}>(a0) is var nugetValue ? " +
            "${boxWrite(requireNotNull(type.boxed), "nugetValue")} : IntPtr.Zero",
        )
        type.throwable != null -> CirAsyncEnd(
          name,
          "IntPtr",
          "NugetTasks.End<$exception?>(a0) is $exception nugetValue ? " +
            "NugetMarshal.WrapString(${managedExceptionTextCs("nugetValue", nullable = false)}) : " +
            "IntPtr.Zero",
        )

        type.wire == ForwardBridgeWire.OBJECT && type.nullable -> CirAsyncEnd(
          name,
          "IntPtr",
          "NugetTasks.End<string?>(a0) is string nugetValue ? " +
            "NugetMarshal.WrapString(nugetValue) : IntPtr.Zero",
        )

        type.wire == ForwardBridgeWire.OBJECT ->
          CirAsyncEnd(name, "IntPtr", "NugetMarshal.WrapString(NugetTasks.End<string>(a0))")

        type.wire == ForwardBridgeWire.UNIT -> CirAsyncEnd(name, "void", "NugetTasks.End(a0)")
        type.wire == ForwardBridgeWire.BOOLEAN ->
          CirAsyncEnd(name, "byte", "NugetTasks.End<bool>(a0) ? (byte)1 : (byte)0")

        type.wire == ForwardBridgeWire.ENUM ->
          CirAsyncEnd(name, "int", "(int)NugetTasks.End<${type.csharp}>(a0)")

        else -> CirAsyncEnd(name, type.csharp, "NugetTasks.End<${type.csharp}>(a0)")
      }
    }

/** ADR-232: every Current thunk the FLOW slots read their elements through. */
internal fun bridgeAsyncCurrents(plans: List<ForwardBridgeInterfacePlan>): List<CirAsyncEnd> =
  plans.flatMap { plan -> plan.slots }
    .filter { slot -> slot.element != null }
    .map { slot -> slot.flowSource().forwardAsyncCurrent() }

/** ADR-232: a SUSPEND slot's `T` result or a FLOW slot's `T` element crosses as an owned box. */
internal fun bridgeAsyncBoxes(plans: List<ForwardBridgeInterfacePlan>): Boolean =
  plans.flatMap { plan -> plan.slots }.any { slot ->
    when (slot.async) {
      ForwardBridgeAsync.SYNC -> false
      ForwardBridgeAsync.SUSPEND -> (slot.result.typeParameter && slot.element == null) ||
        slot.element?.let { it.typeParameter || it.boxed != null || it.star } == true
      ForwardBridgeAsync.FLOW ->
        slot.element?.let { it.typeParameter || it.boxed != null || it.star } == true
    }
  }

/** ADR-232: whether any bridged interface has a hot-flow slot. */
internal fun List<ForwardBridgeInterfacePlan>.bridgesHotFlows(): Boolean =
  any { plan -> plan.slots.any { slot -> slot.result.hotFlow } }

/**
 * ADR-232: the C# half of `HotFlowHandleExports`. `Retain` is the hot-flow slot's whole body: a
 * wrapper that owns a Kotlin flow hands a new handle to it back; one that does not (a member
 * wrapper that re-reads its owner) fails at the slot with an `ArgumentException`, never silently.
 * `CreateState` and `CreateShared` build the mutable wrappers over a real Kotlin flow, on the
 * same delegates every other wrapper is built from.
 */
internal fun StringBuilder.renderHotFlowNative(libraryName: String, symbols: (String) -> String) {
  fun import(stem: String, returns: String, parameters: String) {
    appendLine("        [DllImport(\"$libraryName\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"${symbols(stem)}\")]")
    if (returns == "bool") appendLine("        [return: MarshalAs(UnmanagedType.I1)]")
    appendLine("        private static extern $returns Native_${stem.split('_').joinToString("") { it.replaceFirstChar(Char::uppercase) }}($parameters);")
    appendLine()
  }
  appendLine("    internal static class NugetHotFlowNative")
  appendLine("    {")
  import("state_create", "IntPtr", "IntPtr initial")
  import("state_value", "IntPtr", "NugetKotlinHandle handle")
  import("state_set", "void", "NugetKotlinHandle handle, IntPtr value")
  import("state_compare_and_set", "bool", "NugetKotlinHandle handle, IntPtr expect, IntPtr update")
  import("shared_create", "IntPtr", "int replay")
  import("shared_replay_cache", "IntPtr", "NugetKotlinHandle handle")
  import("shared_subscription_count", "IntPtr", "NugetKotlinHandle handle")
  import("shared_try_emit", "bool", "NugetKotlinHandle handle, IntPtr value")
  import("shared_emit", "IntPtr", "NugetKotlinHandle handle, NugetKotlinHandle scopeHandle, IntPtr value, IntPtr callback, IntPtr userData")
  import("collect", "IntPtr", "NugetKotlinHandle handle, NugetKotlinHandle scopeHandle, IntPtr onNext, IntPtr onComplete, IntPtr onError, IntPtr userData")
  import("retain", "IntPtr", "IntPtr handle")
  appendLine(
    """
        internal static IntPtr Retain<T>(KotlinFlow<T> flow)
        {
            ArgumentNullException.ThrowIfNull(flow);
            IntPtr handle = flow.KotlinHandle;
            if (handle == IntPtr.Zero && flow.MintHandle != null)
            {
                IntPtr minted = flow.MintHandle();
                GC.KeepAlive(flow);
                return minted;
            }
            if (handle == IntPtr.Zero)
            {
                throw new ArgumentException(
                    $"{flow.GetType().Name} wraps no Kotlin flow of its own, so it cannot be handed to Kotlin. " +
                    "Return a KotlinMutableStateFlow<T>.Create or KotlinMutableSharedFlow<T>.Create flow, " +
                    "or one Kotlin returned that owns its flow.",
                    nameof(flow));
            }
            IntPtr retained = Native_Retain(handle);
            GC.KeepAlive(flow);
            return retained;
        }

        private static void WithBox<T>(T value, Action<IntPtr> use)
        {
            IntPtr box = NugetMarshal.Wrap<T>(value!, out bool owned);
            try { use(box); }
            finally { if (owned) NugetMarshal.Dispose(box); else GC.KeepAlive(value); }
        }

        private static TResult WithBox<T, TResult>(T value, Func<IntPtr, TResult> use)
        {
            IntPtr box = NugetMarshal.Wrap<T>(value!, out bool owned);
            try { return use(box); }
            finally { if (owned) NugetMarshal.Dispose(box); else GC.KeepAlive(value); }
        }

        internal static KotlinMutableStateFlow<T> CreateState<T>(T initial)
        {
            var handle = new NugetKotlinHandle(WithBox(initial, box => Native_StateCreate(box)));
            var scope = new NugetScopeHandle(NugetScopeNative.Create());
            return new KotlinMutableStateFlow<T>(
                (onNext, onComplete, onError, userData) => Native_Collect(handle, scope, onNext, onComplete, onError, userData),
                () => Native_StateValue(handle),
                value => WithBox(value, box => Native_StateSet(handle, box)),
                (expect, update) => WithBox(expect, e => WithBox(update, u => Native_StateCompareAndSet(handle, e, u))),
                handle,
                ownedScope: scope);
        }

        internal static KotlinMutableSharedFlow<T> CreateShared<T>(int replay)
        {
            if (replay < 0) throw new ArgumentOutOfRangeException(nameof(replay), replay, "replay must not be negative");
            var handle = new NugetKotlinHandle(Native_SharedCreate(replay));
            var scope = new NugetScopeHandle(NugetScopeNative.Create());
            return new KotlinMutableSharedFlow<T>(
                (onNext, onComplete, onError, userData) => Native_Collect(handle, scope, onNext, onComplete, onError, userData),
                () => Native_SharedReplayCache(handle),
                () => scope,
                () => Native_SharedSubscriptionCount(handle),
                (value, callback, userData) => WithBox(value, box => Native_SharedEmit(handle, scope, box, callback, userData)),
                value => WithBox(value, box => Native_SharedTryEmit(handle, box)),
                ownedHandle: handle,
                ownedScope: scope);
        }
    }
""".trimEnd(),
  )
  appendLine()
}

/**
 * ADR-233: the C# value of [type] a Kotlin box at [handle] holds, read (and released) exactly as a
 * class member's result of that type is read.
 */
internal fun boxRead(type: BridgeType, handle: String): String {
  val csharp: String = type.forwardPublicCsharpType()
  return when (type) {
    is BridgeType.Nullable -> if (type.type.boxReadsItself()) {
      "NugetMarshal.FromHandle<$csharp>($handle)"
    } else {
      "$handle == IntPtr.Zero ? default($csharp) : ${boxRead(type.type, handle)}"
    }
    BridgeType.Instant -> instantLiftCs("NugetMarshal.FromHandle<long>($handle)")
    BridgeType.Duration -> durationLiftCs("NugetMarshal.FromHandle<long>($handle)")
    BridgeType.Uuid -> "global::System.Guid.Parse(NugetMarshal.FromHandle<string>($handle))"
    BridgeType.ByteArray -> "NugetMarshal.ReadBytes($handle)"
    is BridgeType.Collection ->
      componentCollectionRead(handle, type, { component -> component.forwardPublicCsharpType() })
    is BridgeType.Primitive,
    BridgeType.Char,
    BridgeType.String,
    is BridgeType.Enum,
    is BridgeType.ObjectHandle,
    is BridgeType.Interface,
    is BridgeType.BoundInterface,
    is BridgeType.ValueClass,
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
      -> "NugetMarshal.FromHandle<$csharp>($handle)"
  }
}

/**
 * ADR-233: [value] of [type] as the owned box Kotlin reads, written exactly as a class member's
 * parameter of that type is written; a borrowed wrapper handle is retained (`WrapOwned`).
 */
internal fun boxWrite(type: BridgeType, value: String): String {
  val csharp: String = type.forwardPublicCsharpType()
  return when (type) {
    is BridgeType.Nullable -> if (type.type.boxReadsItself()) {
      "NugetMarshal.WrapOwned<$csharp>($value)"
    } else {
      val present: String = if (type.type.isCsharpValueType()) "$value.Value" else "$value!"
      "$value is null ? IntPtr.Zero : ${boxWrite(type.type, present)}"
    }
    BridgeType.Instant -> "NugetMarshal.Wrap<long>($value.UtcTicks, out bool _)"
    BridgeType.Duration -> "NugetMarshal.Wrap<long>($value.Ticks, out bool _)"
    BridgeType.Uuid -> "NugetMarshal.WrapString($value.ToString())"
    BridgeType.ByteArray -> "NugetMarshal.CreateBytes($value)"
    is BridgeType.Collection -> {
      val factory: String = when (type.kind) {
        CollectionKind.LIST, CollectionKind.MUTABLE_LIST -> "CreateList"
        CollectionKind.MAP, CollectionKind.MUTABLE_MAP -> "CreateMap"
        CollectionKind.SET, CollectionKind.MUTABLE_SET -> "CreateSet"
      }
      val argument: String = collectionCreateArgument(
        value, type, csharpType = { component -> component.forwardPublicCsharpType() },
      )
      "NugetMarshal.$factory($argument)"
    }
    is BridgeType.Primitive,
    BridgeType.Char,
    BridgeType.String,
    is BridgeType.Enum,
    is BridgeType.ObjectHandle,
    is BridgeType.Interface,
    is BridgeType.BoundInterface,
    is BridgeType.ValueClass,
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
      -> "NugetMarshal.WrapOwned<$csharp>($value)"
  }
}

/** ADR-233: whether `FromHandle` / `WrapOwned` carry [this] (and its null) with no projection. */
private fun BridgeType.boxReadsItself(): Boolean =
  this != BridgeType.Instant && this != BridgeType.Duration && this != BridgeType.Uuid &&
    this != BridgeType.ByteArray && this !is BridgeType.Collection

/** ADR-233: a C# struct, whose nullable form is `Nullable<T>` and is read through `.Value`. */
private fun BridgeType.isCsharpValueType(): Boolean =
  this == BridgeType.Instant || this == BridgeType.Duration || this == BridgeType.Uuid

/** ADR-233: whether a bridged interface's slot writes its result through `WrapOwned`. */
internal fun bridgeWrapsOwned(plans: List<ForwardBridgeInterfacePlan>): Boolean =
  plans.flatMap { plan -> plan.slots }.any { slot -> slot.result.boxed != null }

/** ADR-233: every boxed slot type, so the marshal helper declares the collection helpers. */
internal fun bridgeBoxedTypes(plans: List<ForwardBridgeInterfacePlan>): List<BridgeType> =
  plans.flatMap { plan -> plan.slots }
    .flatMap { slot -> slot.parameters.map { it.type } + slot.result }
    .mapNotNull { type -> type.boxed }

