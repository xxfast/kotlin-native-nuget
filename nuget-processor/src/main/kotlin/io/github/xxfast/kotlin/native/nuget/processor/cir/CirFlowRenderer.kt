package io.github.xxfast.kotlin.native.nuget.processor.cir

import io.github.xxfast.kotlin.native.nuget.processor.freshName

/**
 * One flow thunk: unlike every other shape the ctx is not the delegate itself but the shared
 * [NugetFlowCallbacks] state, so the thunk selects [member] out of it.
 */
private fun StringBuilder.appendFlowThunk(name: String, paramList: String, member: String) {
  val types: List<String> = thunkParameterTypes(paramList)
  val parameters: String = types.mapIndexed { index, type -> "$type a$index" }.joinToString(", ")
  val arguments: String = types.indices.joinToString(", ") { index -> "a$index" }
  val ctx: String = "a${types.size - 1}"
  appendThunkBody(
    name,
    parameters,
    "void",
    "((NugetFlowCallbacks)GCHandle.FromIntPtr($ctx).Target!).$member($arguments)",
  )
  appendThunkPointer(name, types, "void")
}

internal fun StringBuilder.renderFlowHelper(helper: CirFlowHelper) {
  appendLine("    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]")
  appendLine("    internal delegate void NugetFlowOnNextCallback(IntPtr itemPtr, byte isCancelled, IntPtr userData);")
  appendLine()
  appendLine("    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]")
  appendLine("    internal delegate void NugetFlowOnCompleteCallback(IntPtr userData);")
  appendLine()
  appendLine("    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]")
  appendLine("    internal delegate void NugetFlowOnErrorCallback(IntPtr errorPtr, IntPtr userData);")
  appendLine()
  appendLine("    internal delegate IntPtr NugetFlowCollectDelegate(IntPtr onNext, IntPtr onComplete, IntPtr onError, IntPtr userData);")
  appendLine()
  // ADR-102 (corrected at the red step): the flow ABI threads ONE shared trailing userData that
  // Kotlin echoes into all three callbacks -- there are no per-callback ctx slots -- so the shared
  // ctx is a GCHandle to this one state object and each thunk pulls its own delegate out of it.
  appendLine("    internal sealed class NugetFlowCallbacks")
  appendLine("    {")
  appendLine("        internal NugetFlowOnNextCallback OnNext = null!;")
  appendLine("        internal NugetFlowOnCompleteCallback OnComplete = null!;")
  appendLine("        internal NugetFlowOnErrorCallback OnError = null!;")
  appendLine()
  appendLine("        private GCHandle _self;")
  appendLine("        private int _parties;")
  appendLine()
  appendLine("        internal IntPtr Root()")
  appendLine("        {")
  appendLine("            _self = GCHandle.Alloc(this);")
  appendLine("            return GCHandle.ToIntPtr(_self);")
  appendLine("        }")
  appendLine()
  // Measured, not theorised: freeing the ctx in DisposeAsync alone crashed the whole C# suite
  // after the last flow test, because `NugetJobNative.Cancel` is asynchronous -- Kotlin delivers
  // the cancellation `onNext(isCancelled: 1)` after DisposeAsync has returned. Under the old
  // GetFunctionPointerForDelegate mechanism the stale call landed on a still-live marshaller stub
  // and was harmless; keyed off a freed GCHandle it is a use-after-free. So the handle is released
  // by whichever of the two parties -- the enumerator's disposal and Kotlin's terminal callback --
  // arrives second, and by neither before.
  appendLine("        internal void Release()")
  appendLine("        {")
  appendLine("            if (Interlocked.Increment(ref _parties) != 2) return;")
  appendLine("            if (_self.IsAllocated) _self.Free();")
  appendLine("        }")
  appendLine("    }")
  appendLine()
  renderThunkClass {
    appendFlowThunk(
      "NugetFlowOnNext", "(IntPtr itemPtr, byte isCancelled, IntPtr userData)", "OnNext",
    )
    appendFlowThunk("NugetFlowOnComplete", "(IntPtr userData)", "OnComplete")
    appendFlowThunk("NugetFlowOnError", "(IntPtr errorPtr, IntPtr userData)", "OnError")
  }
  // ADR-123: `read` is how a *collection* element crosses. These two types are shared by every
  // flow member in the file, so an element that `NugetMarshal.FromHandle<T>` cannot materialise
  // (it has no collection branch) supplies its own per-member materialiser instead. Trailing,
  // optional and on an `internal` constructor, so every shipped member's generated text is
  // byte-identical and no consumer-visible surface moves.
  // `release` is the same per-member slot for the abandoned half: a collection element that was
  // read but never reaches the consumer (queued at `DisposeAsync`, or refused by `TryWrite`
  // after it) holds wrappers nobody else can reach, so the member that knows the element is a
  // collection also says how to release one. Every other element passes nothing, keeping row 16c's
  // wrapper-typed item on the GC (ADR-187).
  appendLine("    public class KotlinFlow<T> : IAsyncEnumerable<T>, IDisposable")
  appendLine("    {")
  appendLine("        private readonly NugetFlowCollectDelegate _startCollect;")
  appendLine("        internal readonly Func<IntPtr, T> _read;")
  appendLine("        private readonly Action<T>? _release;")
  appendLine("        private NugetKotlinHandle _ownedHandle;")
  appendLine()
  appendLine("        internal KotlinFlow(NugetFlowCollectDelegate startCollect, Func<IntPtr, T>? read = null, NugetKotlinHandle? ownedHandle = null, Action<T>? release = null)")
  appendLine("        {")
  appendLine("            _ownedHandle = ownedHandle ?? NugetKotlinHandle.Null;")
  appendLine("            _startCollect = startCollect;")
  appendLine("            _read = read ?? NugetMarshal.FromHandle<T>;")
  appendLine("            _release = release;")
  appendLine("        }")
  appendLine()
  appendLine("        public IAsyncEnumerator<T> GetAsyncEnumerator(CancellationToken cancellationToken = default)")
  appendLine("            => new KotlinFlowEnumerator<T>(_startCollect, cancellationToken, _read, _release);")
  appendLine()
  appendLine("        public void Dispose()")
  appendLine("        {")
  appendLine("            NugetKotlinHandle handle = Interlocked.Exchange(ref _ownedHandle, NugetKotlinHandle.Null);")
  appendLine("            if (!handle.IsInvalid) handle.Dispose();")
  appendLine("        }")
  appendLine("    }")
  appendLine()
  appendLine("    internal class KotlinFlowEnumerator<T> : IAsyncEnumerator<T>")
  appendLine("    {")
  appendLine("        private readonly Channel<T> _channel;")
  appendLine("        private readonly CancellationTokenRegistration _cancelReg;")
  appendLine("        private IntPtr _jobHandle;")
  appendLine("        private NugetFlowCallbacks? _callbacks;")
  appendLine("        private bool _done;")
  // ADR-161: set by the onNext closure when an ITEM failed to materialise, read by the
  // constructor straight after `startCollect` returns. A flow whose first emission is synchronous
  // can fault before `_jobHandle` has been assigned, in which case the closure has no handle to
  // cancel and the constructor does it instead.
  appendLine("        private volatile bool _faulted;")
  // ADR-187: the collect delegate captures the wrapper that started the collection (it reads its
  // `_handle` and its scope), and `KotlinFlow` is dropped as soon as the enumerator exists. Holding
  // the delegate here roots that wrapper, and so its scope, for as long as the enumerator is rooted
  // by its callbacks, so an undisposed wrapper is never finalized mid-collection.
  appendLine("        private readonly NugetFlowCollectDelegate _startCollect;")
  appendLine("        private readonly Func<IntPtr, T> _read;")
  appendLine("        private readonly Action<T>? _release;")
  appendLine()
  appendLine("        public T Current { get; private set; } = default!;")
  appendLine()
  appendLine("        internal KotlinFlowEnumerator(NugetFlowCollectDelegate startCollect, CancellationToken cancellationToken, Func<IntPtr, T>? read = null, Action<T>? release = null)")
  appendLine("        {")
  appendLine("            _startCollect = startCollect;")
  appendLine("            _read = read ?? NugetMarshal.FromHandle<T>;")
  appendLine("            _release = release;")
  appendLine("            _channel = Channel.CreateUnbounded<T>(new UnboundedChannelOptions { SingleReader = true, SingleWriter = true });")
  appendLine()
  appendLine("            var callbacks = new NugetFlowCallbacks();")
  appendLine("            _callbacks = callbacks;")
  appendLine()
  appendLine("            NugetFlowOnNextCallback onNext = (itemPtr, isCancelled, userData) =>")
  appendLine("            {")
  appendLine(
    "                if (isCancelled != 0) { _channel.Writer.TryComplete(); " +
        "callbacks.Release(); return; }"
  )
  // ADR-161: the read is a managed-to-native call plus a materialisation (`new T(handle)`, a
  // collection read, or `FromHandle<T>`, which has no branch for every element kind). It runs
  // inside the NugetFlowOnNext thunk, whose catch-all is `Environment.FailFast` (ADR-102), so a
  // failed item used to end the host process. It now faults the channel -- which `MoveNextAsync`
  // already rethrows out of `await foreach` -- and cancels the Kotlin collector, so the consumer
  // sees the materialisation exception on the stream it was enumerating. The item handle whose
  // read failed is released by the read itself, never here: each read owns its handle on its own
  // failure branch (`Materialize<T>`'s single owner, `ReadList`'s `finally`), so a dispose here
  // would be the second release (LeakTests row 14d).
  // A value `TryWrite` refuses (the channel completed by `DisposeAsync` or by an earlier fault)
  // reaches no consumer, so a collection element is released on the spot (row 16l).
  appendLine("                try")
  appendLine("                {")
  appendLine("                    T value = _read(itemPtr);")
  appendLine("                    if (!_channel.Writer.TryWrite(value)) _release?.Invoke(value);")
  appendLine("                }")
  appendLine("                catch (Exception ex)")
  appendLine("                {")
  appendLine("                    _channel.Writer.TryComplete(ex);")
  appendLine("                    _faulted = true;")
  appendLine("                    IntPtr job = Volatile.Read(ref _jobHandle);")
  appendLine("                    if (job != IntPtr.Zero) NugetJobNative.Cancel(job);")
  appendLine("                }")
  appendLine("            };")
  appendLine()
  appendLine("            NugetFlowOnCompleteCallback onComplete = (userData) =>")
  appendLine("            {")
  appendLine("                _channel.Writer.TryComplete();")
  appendLine("                callbacks.Release();")
  appendLine("            };")
  appendLine()
  appendLine("            NugetFlowOnErrorCallback onError = (errorPtr, userData) =>")
  appendLine("            {")
  // ADR-161: `BuildException` reads the error handle across the wire and can itself fail; the
  // channel must still complete, with whatever failure actually happened, or the collector hangs.
  appendLine("                try")
  appendLine("                {")
  appendLine(
    "                    _channel.Writer.TryComplete(" +
        "NugetErrorNative.BuildException(errorPtr));"
  )
  appendLine("                }")
  appendLine("                catch (Exception ex)")
  appendLine("                {")
  appendLine("                    _channel.Writer.TryComplete(ex);")
  appendLine("                }")
  appendLine("                callbacks.Release();")
  appendLine("            };")
  appendLine()
  appendLine("            callbacks.OnNext = onNext;")
  appendLine("            callbacks.OnComplete = onComplete;")
  appendLine("            callbacks.OnError = onError;")
  appendLine()
  appendLine("            IntPtr job;")
  appendLine("            try")
  appendLine("            {")
  appendLine("                job = startCollect(")
  appendLine("                NugetThunks.NugetFlowOnNextPtr,")
  appendLine("                NugetThunks.NugetFlowOnCompletePtr,")
  appendLine("                NugetThunks.NugetFlowOnErrorPtr,")
  appendLine("                callbacks.Root());")
  appendLine("            }")
  appendLine("            catch")
  appendLine("            {")
  appendLine("                callbacks.Release();")
  appendLine("                callbacks.Release();")
  appendLine("                throw;")
  appendLine("            }")
  // ADR-161: the publication half of the ordering window part A left open. The onNext closure
  // reads this field from the Kotlin emitter's thread with a `Volatile.Read`, so the store has to
  // be a release, not a plain write, or the reader can see `IntPtr.Zero` after the handle exists
  // and skip its cancel. Paired with the local below, which is what this frame cancels through:
  // re-reading the field would be a second race for no gain.
  appendLine("            Interlocked.Exchange(ref _jobHandle, job);")
  appendLine()
  // ADR-161: a synchronous first emission runs onNext before the store above, so the closure's own
  // cancel had no handle to use. Whoever sees the fault with a handle in hand cancels; Cancel is
  // idempotent, so both doing it is harmless.
  appendLine("            if (_faulted && job != IntPtr.Zero) NugetJobNative.Cancel(job);")
  appendLine()
  appendLine("            if (cancellationToken.CanBeCanceled)")
  appendLine("                _cancelReg = cancellationToken.Register(() => NugetJobNative.Cancel(_jobHandle));")
  appendLine("        }")
  appendLine()
  appendLine("        public async ValueTask<bool> MoveNextAsync()")
  appendLine("        {")
  appendLine("            if (_done) return false;")
  appendLine()
  appendLine("            try")
  appendLine("            {")
  appendLine("                if (await _channel.Reader.WaitToReadAsync())")
  appendLine("                {")
  appendLine("                    if (_channel.Reader.TryRead(out T? item))")
  appendLine("                    {")
  appendLine("                        Current = item;")
  appendLine("                        return true;")
  appendLine("                    }")
  appendLine("                }")
  appendLine("            }")
  appendLine("            catch (ChannelClosedException)")
  appendLine("            {")
  appendLine("                _done = true;")
  appendLine("                if (_channel.Reader.Completion.IsFaulted)")
  appendLine("                {")
  appendLine("                    var ex = _channel.Reader.Completion.Exception?.InnerException;")
  appendLine("                    if (ex != null) throw ex;")
  appendLine("                }")
  appendLine("                return false;")
  appendLine("            }")
  appendLine()
  appendLine("            _done = true;")
  appendLine()
  appendLine("            if (_channel.Reader.Completion.IsFaulted)")
  appendLine("            {")
  appendLine("                var ex = _channel.Reader.Completion.Exception?.InnerException;")
  appendLine("                if (ex != null) throw ex;")
  appendLine("            }")
  appendLine()
  appendLine("            return false;")
  appendLine("        }")
  appendLine()
  appendLine("        public ValueTask DisposeAsync()")
  appendLine("        {")
  appendLine("            _cancelReg.Dispose();")
  appendLine("            if (_jobHandle != IntPtr.Zero)")
  appendLine("            {")
  appendLine("                NugetJobNative.Cancel(_jobHandle);")
  appendLine("                NugetJobNative.Dispose(_jobHandle);")
  appendLine("                _jobHandle = IntPtr.Zero;")
  appendLine("            }")
  appendLine(
    "            NugetFlowCallbacks? callbacks = Interlocked.Exchange(ref _callbacks, null);"
  )
  appendLine("            callbacks?.Release();")
  appendLine("            _channel.Writer.TryComplete();")
  // Row 16l: what is still queued was read but never handed out. Completing the writer first
  // splits the two abandoned positions cleanly: a write that landed before it is drained here, a
  // write after it is refused and released by onNext, so no element is released twice.
  appendLine("            if (_release != null)")
  appendLine("                while (_channel.Reader.TryRead(out T? abandoned)) _release(abandoned);")
  appendLine("            GC.KeepAlive(_startCollect);")
  appendLine("            return ValueTask.CompletedTask;")
  appendLine("        }")
  appendLine("    }")
  appendLine()

  if (helper.includesStateFlow) {
    // ADR-065: KotlinStateFlow<T> IS-A KotlinFlow<T> -- it inherits the entire collect/enumerator/
    // cancellation/error machinery above unchanged and adds only a synchronous `Value` read.
    // ADR-068: an optional `ownedHandle` -- set only for the awaited-suspend variant, where this
    // wrapper owns the flow's own StableRef and must dispose it. The ADR-065 property/method
    // variants pass no owned handle (they hold none) and are unchanged; `Dispose()` no-ops for
    // them.
    appendLine("    public class KotlinStateFlow<T> : KotlinFlow<T>")
    appendLine("    {")
    appendLine("        private readonly Func<IntPtr> _readValue;")
    // ADR-187: the owned flow handle is the same `NugetKotlinHandle` the read and write lambdas
    // pass, so a dropped flow is released by the GC and a live one is kept alive by every call.
    appendLine()
    appendLine("        internal KotlinStateFlow(NugetFlowCollectDelegate startCollect, Func<IntPtr> readValue, NugetKotlinHandle? ownedHandle = null, Func<IntPtr, T>? read = null, Action<T>? release = null)")
    appendLine("            : base(startCollect, read, ownedHandle, release)")
    appendLine("        {")
    appendLine("            _readValue = readValue;")
    appendLine("        }")
    appendLine()
    appendLine("        public T Value => _read(_readValue());")
    appendLine()
    appendLine("    }")
    appendLine()
    renderStateFlowObservable()

    if (helper.includesMutableStateFlow) {
      // ADR-071: settable `.Value`. `new`, not `override` -- KotlinStateFlow<T>.Value has no
      // overridable set accessor, so C# rejects `override` with CS0546 (verified by spike, see
      // ADR-071 "Spikes run for this ADR"). The base and derived getters read the same
      // `_readValue` delegate, so a base-typed reference observes a write made through the
      // derived setter.
      appendLine("    public class KotlinMutableStateFlow<T> : KotlinStateFlow<T>")
      appendLine("    {")
      appendLine("        private readonly Action<T> _writeValue;")
      appendLine()
      appendLine("        internal KotlinMutableStateFlow(")
      appendLine("            NugetFlowCollectDelegate startCollect,")
      appendLine("            Func<IntPtr> readValue,")
      appendLine("            Action<T> writeValue,")
      appendLine("            NugetKotlinHandle? ownedHandle = null)")
      appendLine("            : base(startCollect, readValue, ownedHandle)")
      appendLine("        {")
      appendLine("            _writeValue = writeValue;")
      appendLine("        }")
      appendLine()
      appendLine("        public new T Value")
      appendLine("        {")
      appendLine("            get => base.Value;")
      appendLine("            set => _writeValue(value);")
      appendLine("        }")
      appendLine("    }")
      appendLine()
    }
  }
}

/**
 * ADR-206: the opt-in `INotifyPropertyChanged` adapter over `KotlinStateFlow<T>`, emitted beside it
 * under the same `includesStateFlow` gate. Per module, not in the contract library, because only
 * this file can name `KotlinStateFlow<T>` and an adapter needs no cross-package identity (ADR-178).
 * `System.ComponentModel` is spelled `global::` rather than added to the file's usings, so no
 * consumer type name can become ambiguous with one of its many simple names.
 */
private fun StringBuilder.renderStateFlowObservable() {
  val componentModel: String = "global::System.ComponentModel"
  appendLine("    /// <summary>")
  appendLine(
    "    /// Opt-in XAML data-binding view of a <see cref=\"KotlinStateFlow{T}\"/> (ADR-206)."
  )
  appendLine("    /// </summary>")
  appendLine("    public static class KotlinStateFlowExtensions")
  appendLine("    {")
  appendLine("        /// <summary>")
  appendLine(
    "        /// Starts one collection of <paramref name=\"flow\"/> and exposes its latest " +
      "element as an"
  )
  appendLine(
    "        /// <c>INotifyPropertyChanged</c> <c>Value</c>. Dispose the result to stop the " +
      "collection."
  )
  appendLine("        /// </summary>")
  appendLine("        /// <param name=\"flow\">The state flow to observe.</param>")
  appendLine(
    "        /// <param name=\"context\">Where <c>PropertyChanged</c> is raised. Defaults to " +
      "<see cref=\"SynchronizationContext.Current\"/>"
  )
  appendLine(
    "        /// at this call; when that is also null the event is raised inline on the thread " +
      "that delivered the element.</param>"
  )
  appendLine("        public static KotlinStateFlowObservable<T> AsNotifying<T>(")
  appendLine("            this KotlinStateFlow<T> flow, SynchronizationContext? context = null)")
  appendLine(
    "            => new KotlinStateFlowObservable<T>(flow, context ?? " +
      "SynchronizationContext.Current);"
  )
  appendLine("    }")
  appendLine()
  appendLine("    /// <summary>")
  appendLine(
    "    /// An <c>INotifyPropertyChanged</c> adapter over a <see cref=\"KotlinStateFlow{T}\"/>, " +
      "made by <c>AsNotifying()</c> (ADR-206)."
  )
  appendLine(
    "    /// <c>Value</c> is seeded from the flow's current value and then holds the last " +
      "delivered element;"
  )
  appendLine(
    "    /// <c>PropertyChanged(\"Value\")</c> is posted to the captured " +
      "<see cref=\"SynchronizationContext\"/>, or raised inline"
  )
  appendLine("    /// on the delivering thread when there is none.")
  appendLine("    /// </summary>")
  appendLine("    /// <remarks>")
  appendLine(
    "    /// Call <see cref=\"Dispose\"/> when the binding goes away. Until then the running " +
      "collection keeps the Kotlin collect job,"
  )
  appendLine(
    "    /// this adapter and the flow's owner rooted for the life of the process, and it keeps " +
      "delivering; there is no finalizer"
  )
  appendLine(
    "    /// because the running collection itself roots the adapter. A replaced wrapper-typed " +
      "element is not disposed here."
  )
  appendLine(
    "    /// <see cref=\"Completion\"/> completes after <see cref=\"Dispose\"/> and faults if " +
      "the Kotlin collection fails."
  )
  appendLine("    /// </remarks>")
  appendLine(
    "    public sealed class KotlinStateFlowObservable<T> : " +
      "$componentModel.INotifyPropertyChanged, IDisposable"
  )
  appendLine("    {")
  appendLine("        private readonly SynchronizationContext? _context;")
  appendLine(
    "        private readonly CancellationTokenSource _cts = new CancellationTokenSource();"
  )
  appendLine()
  appendLine("        public event $componentModel.PropertyChangedEventHandler? PropertyChanged;")
  appendLine()
  appendLine("        public T Value { get; private set; }")
  appendLine()
  appendLine("        public Task Completion { get; }")
  appendLine()
  appendLine(
    "        internal KotlinStateFlowObservable(KotlinStateFlow<T> flow, " +
      "SynchronizationContext? context)"
  )
  appendLine("        {")
  appendLine("            _context = context;")
  appendLine("            Value = flow.Value;")
  appendLine("            Completion = RunAsync(flow);")
  appendLine("        }")
  appendLine()
  appendLine("        private async Task RunAsync(KotlinStateFlow<T> flow)")
  appendLine("        {")
  appendLine("            try")
  appendLine("            {")
  appendLine(
    "                await foreach (T item in " +
      "flow.WithCancellation(_cts.Token).ConfigureAwait(false))"
  )
  appendLine("                {")
  appendLine("                    SynchronizationContext? context = _context;")
  appendLine("                    if (context == null) Deliver(item);")
  appendLine("                    else context.Post(static state =>")
  appendLine("                    {")
  appendLine(
    "                        (KotlinStateFlowObservable<T> self, T value) = " +
      "((KotlinStateFlowObservable<T>, T))state!;"
  )
  appendLine("                        self.Deliver(value);")
  appendLine("                    }, (this, item));")
  appendLine("                }")
  appendLine("            }")
  appendLine(
    "            catch (OperationCanceledException) when (_cts.IsCancellationRequested) { }"
  )
  appendLine("        }")
  appendLine()
  appendLine("        private void Deliver(T item)")
  appendLine("        {")
  appendLine("            if (_cts.IsCancellationRequested) return;")
  appendLine("            Value = item;")
  appendLine(
    "            PropertyChanged?.Invoke(this, " +
      "new $componentModel.PropertyChangedEventArgs(nameof(Value)));"
  )
  appendLine("        }")
  appendLine()
  appendLine("        public void Dispose()")
  appendLine("        {")
  appendLine("            if (_cts.IsCancellationRequested) return;")
  appendLine("            _cts.Cancel();")
  appendLine("        }")
  appendLine("    }")
  appendLine()
}

// ADR-068: shared static class holding the two generic exports keyed on an already-obtained
// StateFlow<*> handle -- `Collect`/`Value` operate directly on the awaited flow's own StableRef,
// unlike every ADR-065 per-member `_collect`/`_value` DllImport, which is scoped to one class.
internal fun StringBuilder.renderStateFlowHandleHelper(helper: CirStateFlowHandleHelper) {
  appendLine("    internal static class NugetStateFlowNative")
  appendLine("    {")
  appendLine("        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"nuget_stateflow_collect\")]")
  appendLine("        internal static extern IntPtr Collect(NugetKotlinHandle flowHandle, NugetKotlinHandle scopeHandle, IntPtr onNext, IntPtr onComplete, IntPtr onError, IntPtr userData);")
  appendLine()
  appendLine("        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"nuget_stateflow_value\")]")
  appendLine("        internal static extern IntPtr Value(NugetKotlinHandle flowHandle);")
  appendLine()
  // The null-aware sibling: `IntPtr.Zero` for a null value, read as null by `FromHandle<T?>`.
  appendLine("        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"nuget_stateflow_value_or_null\")]")
  appendLine("        internal static extern IntPtr ValueOrNull(NugetKotlinHandle flowHandle);")
  appendLine("    }")
  appendLine()
}

internal fun StringBuilder.renderFlowMethod(method: CirMethod, className: String) {
  if (method.isStateFlow) {
    renderStateFlowMethod(method, className)
    return
  }

  val paramStr: String = method.parameters.joinToString(", ") { it.declaration }
  val nativeName: String = method.nativeName

  appendLine(
    "        ${method.memberHead("public ")}KotlinFlow<${method.flowElementType}> " +
        "${method.explicitName}($paramStr)",
  )
  appendLine("        {")
  appendLine("            if (_handle.IsInvalid)")
  appendLine("                throw new ObjectDisposedException(nameof($className));")
  appendLine("            return new KotlinFlow<${method.flowElementType}>((${method.flowCallbackNames.joinToString(", ")}) =>")
  // ADR-114: the collect delegate runs per subscription, so the wire container is built inside it
  // and disposed the moment the native call returns. Kotlin has already copied it out.
  // ADR-123: a collection element adds its own materialiser after the delegate; every other
  // element passes nothing and keeps the shipped single-argument construction.
  val read: String? = method.flowElementRead
  appendScopedNativeCall(
    method, "                ", "$nativeName(${method.body})", if (read == null) ");" else ",",
  )
  if (read != null) appendLine("                $read);")
  appendLine("        }")
  appendLine()
}

/**
 * ADR-114: [call] rendered either as the shipped single expression or, when a parameter carries a
 * collection wire handle, as a block that builds every handle, calls, and disposes in a `finally`.
 * [terminator] closes whatever construct the call sits inside (`);`, `,`).
 */
private fun StringBuilder.appendScopedNativeCall(
  method: CirMethod,
  indent: String,
  call: String,
  terminator: String,
) {
  val scoped: List<String>? = method.parameters.collectionScopedCall(indent, call)
  if (scoped == null) {
    appendLine("$indent$call$terminator")
    return
  }
  scoped.forEachIndexed { index, line ->
    appendLine(if (index == scoped.lastIndex) "$line$terminator" else line)
  }
}

// ADR-065: StateFlow<T> as a non-suspend function return. Identical to renderFlowMethod's
// `_collect` wiring, plus a synchronous `_value` read passed as the second KotlinStateFlow<T>
// constructor argument (the method's own parameters, re-read on each `.Value` access).
// ADR-067: a nullable member (`StateFlow<T>?` return) additionally probes `_has_value` before
// constructing and returns `null` when absent.
// ADR-071: a genuinely DECLARED MutableStateFlow<T> return additionally passes a third
// `Action<T>` write lambda, backed by the sibling `_set_value` export, and constructs
// KotlinMutableStateFlow<T> instead of KotlinStateFlow<T>.
internal fun StringBuilder.renderStateFlowMethod(method: CirMethod, className: String) {
  if (method.isMutableStateFlow) {
    renderHeldStateFlowMethod(method, className)
    return
  }

  val paramStr: String = method.parameters.joinToString(", ") { it.declaration }
  // ADR-114: the native call passes the wire handle, not the public collection.
  val paramNames: String = method.parameters.joinToString(", ") { it.nativeArgument }
  val nativeName: String = method.nativeName
  val valueNativeName: String = method.stateFlowValueNativeName
  val valueCallArgs: String = if (paramNames.isEmpty()) "_handle" else "_handle, $paramNames"
  val nullableSuffix: String = if (method.isStateFlowNullableMember) "?" else ""

  appendLine(
    "        ${method.memberHead("public ")}" +
        "KotlinStateFlow<${method.flowElementType}>$nullableSuffix " +
        "${method.explicitName}($paramStr)",
  )
  appendLine("        {")
  appendLine("            if (_handle.IsInvalid)")
  appendLine("                throw new ObjectDisposedException(nameof($className));")
  if (method.isStateFlowNullableMember) {
    val hasValueNativeName: String = method.stateFlowHasValueNativeName
    val probe: String = "$hasValueNativeName($valueCallArgs)"
    // ADR-114: the presence probe is a synchronous call like any other, so it gets its own
    // call-scoped handle rather than sharing one with the collect delegate below.
    val scoped: List<String>? = method.parameters
      .collectionScopedCall("            ", "hasValue = $probe", returns = false)
    if (scoped == null) {
      appendLine("            if (!$probe)")
      appendLine("                return null;")
    } else {
      appendLine("            bool hasValue;")
      scoped.forEach { appendLine(it) }
      appendLine("            if (!hasValue)")
      appendLine("                return null;")
    }
  }
  appendLine("            return new KotlinStateFlow<${method.flowElementType}>((${method.flowCallbackNames.joinToString(", ")}) =>")
  appendScopedNativeCall(method, "                ", "$nativeName(${method.body})", ",")
  // ADR-123: `read:` is named, so it skips the optional `ownedHandle` slot only the ADR-068
  // awaited-suspend variant fills. A non-collection element passes nothing at all.
  val read: String? = method.flowElementRead
  appendValueLambda(method, valueNativeName, valueCallArgs, if (read == null) ");" else ",")
  if (read != null) appendLine("                $read);")
  appendLine("        }")
  appendLine()
}

/**
 * ADR-071 (2026-09-11): a `MutableStateFlow<T>`-declared function return, HELD. The acquire call
 * runs once, in the method body, and its result IS the flow's own handle; the wrapper then keys
 * every seam on that one handle -- ADR-068's shared `nuget_stateflow_collect` /
 * `nuget_stateflow_value` for the reads, the flow-keyed `_set_value` for the write, and
 * `ownedHandle` so `Dispose()` frees it. The shipped shape passed three lambdas that each
 * re-invoked the Kotlin function, so a write landed in one throwaway flow and the next read built
 * another.
 */
private fun StringBuilder.renderHeldStateFlowMethod(method: CirMethod, className: String) {
  val paramStr: String = method.parameters.joinToString(", ") { it.declaration }
  val element: String = method.flowElementType

  appendLine(
    "        ${method.memberHead("public ")}KotlinMutableStateFlow<$element> " +
        "${method.explicitName}($paramStr)",
  )
  appendLine("        {")
  appendLine("            if (_handle.IsInvalid)")
  appendLine("                throw new ObjectDisposedException(nameof($className));")
  // ADR-114: a collection argument's wire handle lives only for the acquire call, which is the
  // one call that reads it; the flow the call returns owns nothing of it.
  val acquire: String = "${method.nativeName}(${method.body})"
  // The body's own locals share the method scope with the user's parameters, so they move off a
  // parameter spelled like either; only this renderer declares or reads them.
  val taken: MutableSet<String> = method.parameters.localScopeNames()
  val flow: String = freshName("flow", taken).also { taken += it }
  val owned: String = freshName("owned", taken).also { taken += it }
  val collectScope: String = freshName("collectScope", taken)
  val scoped: List<String>? =
    method.parameters.collectionScopedCall("            ", "$flow = $acquire", returns = false)
  if (scoped == null) {
    appendLine("            IntPtr $flow = $acquire;")
  } else {
    appendLine("            IntPtr $flow = IntPtr.Zero;")
    scoped.forEach { appendLine(it) }
  }
  // ADR-187: the lambdas capture the owned flow handle and the parent's scope handle OBJECTS, not
  // raw pointers, so neither can be finalized while the flow is reachable, and each call keeps the
  // one it passes alive. A parent disposed first leaves a closed scope, which the collect call
  // rejects (`ObjectDisposedException`) instead of handing Kotlin a released pointer.
  appendLine("            var $owned = new NugetKotlinHandle($flow);")
  appendLine("            NugetScopeHandle $collectScope = GetOrCreateScope();")
  appendLine("            return new KotlinMutableStateFlow<$element>(")
  appendLine("                (onNext, onComplete, onError, userData) =>")
  appendLine("                    NugetStateFlowNative.Collect($owned, $collectScope, onNext, onComplete, onError, userData),")
  // A nullable element reads through the null-aware sibling: `nuget_stateflow_value` has no null
  // arm and would throw out of the `@CName` on a null current value.
  val valueRead: String = if (method.flowElementNullable) "ValueOrNull" else "Value"
  appendLine("                () => NugetStateFlowNative.$valueRead($owned),")
  appendLine("                v =>")
  appendLine("                {")
  val write: CirStateFlowWrite = requireNotNull(method.stateFlowWrite)
  if (write.rejectsNull) {
    appendLine("                    if (v is null) throw new ArgumentNullException(nameof(v));")
  }
  appendLine("                    ${method.stateFlowSetValueNativeName}($owned, ${write.arguments}, out IntPtr error);")
  appendLine("                    if (error != IntPtr.Zero) throw NugetErrorNative.BuildException(error);")
  appendLine("                    NugetErrorNative.ClearManagedFault();")
  appendLine("                },")
  appendLine("                $owned);")
  appendLine("        }")
  appendLine()
}

/**
 * ADR-065's `.Value` read lambda, with ADR-114's per-read wire handle. The lambda is re-invoked on
 * every `.Value` access, so a collection parameter is re-marshalled per read; the handle is still
 * call-scoped, which is what keeps the ownership model uniform across all four exports.
 */
private fun StringBuilder.appendValueLambda(
  method: CirMethod,
  valueNativeName: String,
  valueCallArgs: String,
  terminator: String,
) {
  val call: String = "$valueNativeName($valueCallArgs)"
  if (!method.parameters.hasCollectionHandles()) {
    appendLine("                () => $call$terminator")
    return
  }
  appendLine("                () =>")
  appendScopedNativeCall(method, "                ", call, terminator)
}
