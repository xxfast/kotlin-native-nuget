package io.github.xxfast.kotlin.native.nuget.processor.cir

import io.github.xxfast.kotlin.native.nuget.processor.freshName

/**
 * ADR-209: `KotlinSharedFlow<T>` and `KotlinMutableSharedFlow<T>`, generated per module beside
 * `KotlinStateFlow<T>` (the Flow family is per module, ADR-178/206). Both inherit the whole
 * `KotlinFlow<T>` collect machinery. The templates own the machinery of each added member (the
 * `ReadList`, the `KotlinStateFlow<int>` construction, the `EmitAsync` completion); the member
 * supplies small delegates over its own externs, as `KotlinFlow<T>` takes `startCollect`.
 *
 * Every added delegate is a required constructor parameter placed BEFORE `KotlinFlow<T>`'s
 * optional `read`, `ownedHandle`, `release` tail, in that order, so a construction site that
 * passes `read` positionally (the acquired flow does) lands it on the same slot it always has.
 */
internal fun StringBuilder.renderSharedFlowTemplates(helper: CirFlowHelper) {
  if (!helper.includesSharedFlow) return
  appendLine("    public class KotlinSharedFlow<T> : KotlinFlow<T>")
  appendLine("    {")
  appendLine("        private readonly Func<IntPtr> _readReplayCache;")
  appendLine()
  appendLine("        internal KotlinSharedFlow(NugetFlowCollectDelegate startCollect, Func<IntPtr> readReplayCache, Func<IntPtr, T>? read = null, NugetKotlinHandle? ownedHandle = null, Action<T>? release = null)")
  appendLine("            : base(startCollect, read, ownedHandle, release)")
  appendLine("        {")
  appendLine("            _readReplayCache = readReplayCache;")
  appendLine("        }")
  appendLine()
  // The flow's own element read, so an element collect admits replays exactly as it collects:
  // `nuget_list_get` mints one box per item, the read consumes it, `ReadList` disposes the list.
  appendLine("        /// <summary>A snapshot of Kotlin's <c>replayCache</c>, oldest first.</summary>")
  appendLine("        public IReadOnlyList<T> ReplayCache => NugetMarshal.ReadList(_readReplayCache(), _read);")
  appendLine("    }")
  appendLine()
  if (!helper.includesMutableSharedFlow) return
  appendLine("    public class KotlinMutableSharedFlow<T> : KotlinSharedFlow<T>")
  appendLine("    {")
  appendLine("        private readonly Func<NugetKotlinHandle> _scope;")
  appendLine("        private readonly Func<IntPtr> _subscriptionCount;")
  appendLine("        private readonly Func<T, IntPtr, IntPtr, IntPtr> _startEmit;")
  appendLine("        private readonly Func<T, bool> _tryEmit;")
  appendLine()
  appendLine("        internal KotlinMutableSharedFlow(")
  appendLine("            NugetFlowCollectDelegate startCollect,")
  appendLine("            Func<IntPtr> readReplayCache,")
  appendLine("            Func<NugetKotlinHandle> scope,")
  appendLine("            Func<IntPtr> subscriptionCount,")
  appendLine("            Func<T, IntPtr, IntPtr, IntPtr> startEmit,")
  appendLine("            Func<T, bool> tryEmit,")
  appendLine("            Func<IntPtr, T>? read = null,")
  appendLine("            NugetKotlinHandle? ownedHandle = null,")
  appendLine("            Action<T>? release = null)")
  appendLine("            : base(startCollect, readReplayCache, read, ownedHandle, release)")
  appendLine("        {")
  appendLine("            _scope = scope;")
  appendLine("            _subscriptionCount = subscriptionCount;")
  appendLine("            _startEmit = startEmit;")
  appendLine("            _tryEmit = tryEmit;")
  appendLine("        }")
  appendLine()
  // A new owned `StateFlow<Int>` handle per read, collected on the scope this flow's own collect
  // uses, read through ADR-068's handle-keyed runtime pair. Disposing it frees the handle; an
  // abandoned one is released by the GC (ADR-187).
  appendLine("        /// <summary>")
  appendLine("        /// Kotlin's <c>subscriptionCount</c>: a new <see cref=\"KotlinStateFlow{T}\"/> on every read,")
  appendLine("        /// which the caller owns and disposes.")
  appendLine("        /// </summary>")
  appendLine("        public KotlinStateFlow<int> SubscriptionCount")
  appendLine("        {")
  appendLine("            get")
  appendLine("            {")
  appendLine("                var owned = new NugetKotlinHandle(_subscriptionCount());")
  appendLine("                return new KotlinStateFlow<int>(")
  appendLine("                    (onNext, onComplete, onError, userData) =>")
  appendLine("                        NugetStateFlowNative.Collect(owned, _scope(), onNext, onComplete, onError, userData),")
  appendLine("                    () => NugetStateFlowNative.Value(owned),")
  appendLine("                    owned);")
  appendLine("            }")
  appendLine("        }")
  appendLine()
  // ADR-209: launched on the scope the owner's collect uses, so the owner's `Dispose()` cancels a
  // parked emit and its `DisposeAsync()` drain joins one. The completion is every suspend
  // member's (ADR-019's `NugetJobCell`, ADR-161's containment).
  appendLine("        /// <summary>")
  appendLine("        /// Kotlin's suspending <c>emit</c>: completes once every subscriber has taken")
  appendLine("        /// <paramref name=\"value\"/>, or it is buffered or replayed.")
  appendLine("        /// </summary>")
  appendLine("        public Task EmitAsync(T value, CancellationToken cancellationToken = default)")
  appendLine("        {")
  appendLine("            if (cancellationToken.IsCancellationRequested)")
  appendLine("                return Task.FromCanceled(cancellationToken);")
  appendLine("            var tcs = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);")
  appendLine("            NugetAsyncCallback callback = null!;")
  appendLine("            GCHandle callbackHandle = default;")
  appendLine("            var job = new NugetJobCell();")
  appendAsyncCompletionClosure(
    "TaskCompletionSource<bool>",
    "t.SetResult(true);",
    prelude = listOf("GC.KeepAlive(this);"),
  )
  appendLine("            callbackHandle = GCHandle.Alloc(callback);")
  appendLine("            IntPtr jobHandle;")
  appendLine("            try")
  appendLine("            {")
  appendLine("                jobHandle = _startEmit(value, NugetThunks.NugetAsyncCallbackPtr, GCHandle.ToIntPtr(callbackHandle));")
  appendLine("            }")
  appendLine("            catch")
  appendLine("            {")
  appendLine("                if (callbackHandle.IsAllocated) callbackHandle.Free();")
  appendLine("                throw;")
  appendLine("            }")
  appendLine("            CancellationTokenRegistration reg = cancellationToken.CanBeCanceled")
  appendLine("                ? cancellationToken.Register(() => NugetJobNative.Cancel(jobHandle))")
  appendLine("                : default;")
  appendLine("            job.PublishFromCaller(jobHandle, reg);")
  appendLine("            return tcs.Task;")
  appendLine("        }")
  appendLine()
  appendLine("        /// <summary>")
  appendLine("        /// Kotlin's non-suspending <c>tryEmit</c>: false when the emit would have to suspend.")
  appendLine("        /// </summary>")
  appendLine("        public bool TryEmit(T value) => _tryEmit(value);")
  appendLine("    }")
  appendLine()
}

/**
 * ADR-209: the constructor arguments a shared flow takes after `startCollect`, one entry each,
 * without separators, at [indent]. [receiver] is the first argument of every extern (`_handle` for
 * an owner-keyed member, the owned flow handle for a held or awaited one); [scope] is the scope
 * expression the member's own collect passes, re-evaluated on each use.
 */
internal fun sharedFlowArguments(
  shared: CirSharedFlow,
  receiver: String,
  scope: String,
  indent: String,
  // Every name in scope where the arguments are rendered: the lambdas' own names move off them,
  // so none shadows a user parameter or an enclosing closure's.
  taken: Set<String> = emptySet(),
): List<String> {
  val names: MutableSet<String> = taken.toMutableSet()
  fun mint(base: String): String = freshName(base, names).also { names += it }
  val error: String = mint("error")
  val replay: String = "$indent() => NugetErrorNative.Check(${shared.replayCache}($receiver, " +
      "out IntPtr $error), $error)"
  val mutable: CirMutableSharedFlow = shared.mutable ?: return listOf(replay)
  val v: String = mint("v")
  val callback: String = mint("callback")
  val userData: String = mint("userData")
  val write: CirStateFlowWrite = mutable.write.relabelled("value", v)
  // The ADR-071 guards, in the setter's order: a null object first, then the value-class
  // `default(V)` check, whose `v` [relabelled] already moved onto this lambda's parameter.
  val guard: String = listOfNotNull(
    "if ($v is null) throw new ArgumentNullException(nameof($v));".takeIf { write.rejectsNull },
    write.guard,
  ).joinToString("") { statement -> "$statement " }
  return listOf(
    replay,
    "$indent() => $scope",
    "$indent() => NugetErrorNative.Check(${mutable.subscriptionCount}($receiver, " +
        "out IntPtr $error), $error)",
    "$indent($v, $callback, $userData) => { ${guard}return ${mutable.emit}($receiver, $scope, " +
        "${write.arguments}, $callback, $userData); }",
    "$indent$v => { ${guard}return NugetErrorNative.Check(${mutable.tryEmit}($receiver, " +
        "${write.arguments}, out IntPtr $error), $error); }",
  )
}

/**
 * ADR-209: the re-invoked method return's `ReplayCache` delegate. It re-runs the Kotlin function
 * with the method's own arguments on every read, as `KotlinStateFlow.Value` does (ADR-065), so a
 * collection argument's wire handle is built and disposed per read (ADR-114).
 */
internal fun reinvokedReplayArgument(
  method: CirMethod,
  replayCache: String,
  indent: String,
): String {
  val names: MutableSet<String> = method.parameters.localScopeNames()
  val error: String = freshName("error", names)
  val paramNames: String = method.parameters.joinToString(", ") { it.nativeArgument }
  val args: String = if (paramNames.isEmpty()) "_handle" else "_handle, $paramNames"
  if (!method.parameters.hasCollectionHandles()) {
    return "$indent() => NugetErrorNative.Check($replayCache($args, out IntPtr $error), $error)"
  }
  val result: String = freshName("replay", names)
  val call = "$result = $replayCache($args, out $error)"
  val scoped: List<String> =
    requireNotNull(method.parameters.collectionScopedCall("$indent    ", call, returns = false))
  return buildString {
    appendLine("$indent() =>")
    appendLine("$indent{")
    appendLine("$indent    IntPtr $result;")
    appendLine("$indent    IntPtr $error;")
    scoped.forEach { line -> appendLine(line) }
    append("$indent    return NugetErrorNative.Check($result, $error);\n$indent}")
  }
}

/**
 * ADR-209: the C# externs of a flow keyed on its own handle (a held method return or an awaited
 * `suspend` return), named off [nativeStem] and entered at `${stem}_...`. A `_collect` keyed on
 * the same handle is the caller's: the awaited route already has one (ADR-194).
 */
internal fun flowKeyedSharedFlowImports(
  libraryName: String,
  stem: String,
  nativeStem: String,
  shared: CirSharedFlow,
): List<CirDllImport> {
  val flowHandle = CirParameter("flowHandle", KOTLIN_HANDLE)
  val replay = CirDllImport(
    libraryName = libraryName,
    entryPoint = "${stem}_replay_cache",
    returnType = "IntPtr",
    name = "${nativeStem}ReplayCache",
    parameters = listOf(flowHandle),
    visibility = CirVisibility.PRIVATE,
    hasSyncErrorOut = true,
  )
  val mutable: CirMutableSharedFlow = shared.mutable ?: return listOf(replay)
  return listOf(replay) + sharedFlowWriteImports(
    libraryName,
    receiver = flowHandle,
    subscriptionCount = "${stem}_subscription_count" to "${nativeStem}SubscriptionCount",
    emit = "${stem}_emit" to "${nativeStem}Emit",
    tryEmit = "${stem}_try_emit" to "${nativeStem}TryEmit",
    write = mutable.write,
  )
}

/** ADR-209: [flowKeyedSharedFlowImports]' extern names, without a carrier. */
internal fun flowKeyedSharedFlow(nativeStem: String, write: CirStateFlowWrite?): CirSharedFlow =
  CirSharedFlow(
    replayCache = "${nativeStem}ReplayCache",
    mutable = write?.let {
      CirMutableSharedFlow(
        subscriptionCount = "${nativeStem}SubscriptionCount",
        emit = "${nativeStem}Emit",
        tryEmit = "${nativeStem}TryEmit",
        write = it,
      )
    },
  )

/**
 * ADR-209: the write half's three externs, entry point to extern name: `_subscription_count`
 * (`IntPtr` plus error), `_emit` (the scope, the write slot, the completion callback; reports
 * through the callback) and `_try_emit` (`bool` plus error).
 */
private fun sharedFlowWriteImports(
  libraryName: String,
  receiver: CirParameter,
  subscriptionCount: Pair<String, String>,
  emit: Pair<String, String>,
  tryEmit: Pair<String, String>,
  write: CirStateFlowWrite,
): List<CirDllImport> = listOf(
  CirDllImport(
    libraryName = libraryName,
    entryPoint = subscriptionCount.first,
    returnType = "IntPtr",
    name = subscriptionCount.second,
    parameters = listOf(receiver),
    visibility = CirVisibility.PRIVATE,
    hasSyncErrorOut = true,
  ),
  CirDllImport(
    libraryName = libraryName,
    entryPoint = emit.first,
    returnType = "IntPtr",
    name = emit.second,
    parameters = listOf(receiver, CirParameter("scopeHandle", KOTLIN_HANDLE)) +
        write.parameters +
        listOf(CirParameter("callback", "IntPtr"), CirParameter("userData", "IntPtr")),
    visibility = CirVisibility.PRIVATE,
  ),
  CirDllImport(
    libraryName = libraryName,
    entryPoint = tryEmit.first,
    returnType = "bool",
    name = tryEmit.second,
    parameters = listOf(receiver) + write.parameters,
    visibility = CirVisibility.PRIVATE,
    hasSyncErrorOut = true,
  ),
)

/**
 * ADR-209: an owner-keyed shared flow property's externs, beside its `_collect` in
 * [renderFlowPropertyNativeImports]: `_get_<p>_replay_cache`, and for a mutable one
 * `_get_<p>_subscription_count`, `_emit_<p>` and `_try_emit_<p>`.
 */
internal fun StringBuilder.renderSharedFlowPropertyNativeImports(
  libraryName: String,
  nativePrefix: String,
  prop: CirProperty,
) {
  val shared: CirSharedFlow = prop.sharedFlow ?: return
  val handle = CirParameter("handle", KOTLIN_HANDLE)
  renderDllImport(
    CirDllImport(
      libraryName = libraryName,
      entryPoint = "${nativePrefix}_get_${prop.nativeName}_replay_cache",
      returnType = "IntPtr",
      name = "Native_Get${prop.nativeStem}ReplayCache",
      parameters = listOf(handle),
      visibility = CirVisibility.PRIVATE,
      hasSyncErrorOut = true,
    ),
  )
  val mutable: CirMutableSharedFlow = shared.mutable ?: return
  sharedFlowWriteImports(
    libraryName,
    receiver = handle,
    subscriptionCount = "${nativePrefix}_get_${prop.nativeName}_subscription_count" to
        "Native_Get${prop.nativeStem}SubscriptionCount",
    emit = "${nativePrefix}_emit_${prop.nativeName}" to "Native_Emit${prop.nativeStem}",
    tryEmit = "${nativePrefix}_try_emit_${prop.nativeName}" to "Native_TryEmit${prop.nativeStem}",
    write = mutable.write,
  ).forEach { import -> renderDllImport(import) }
}

/** ADR-209: [renderSharedFlowPropertyNativeImports]' extern names, prefixed by [carrier]. */
internal fun propertySharedFlow(
  nativeStem: String,
  carrier: String,
  write: CirStateFlowWrite?,
): CirSharedFlow = CirSharedFlow(
  replayCache = "${carrier}Native_Get${nativeStem}ReplayCache",
  mutable = write?.let {
    CirMutableSharedFlow(
      subscriptionCount = "${carrier}Native_Get${nativeStem}SubscriptionCount",
      emit = "${carrier}Native_Emit$nativeStem",
      tryEmit = "${carrier}Native_TryEmit$nativeStem",
      write = it,
    )
  },
)
