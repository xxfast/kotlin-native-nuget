package io.github.xxfast.kotlin.native.nuget.processor.cir

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
  appendLine("    public class KotlinFlow<T> : IAsyncEnumerable<T>")
  appendLine("    {")
  appendLine("        private readonly NugetFlowCollectDelegate _startCollect;")
  appendLine()
  appendLine("        internal KotlinFlow(NugetFlowCollectDelegate startCollect)")
  appendLine("        {")
  appendLine("            _startCollect = startCollect;")
  appendLine("        }")
  appendLine()
  appendLine("        public IAsyncEnumerator<T> GetAsyncEnumerator(CancellationToken cancellationToken = default)")
  appendLine("            => new KotlinFlowEnumerator<T>(_startCollect, cancellationToken);")
  appendLine("    }")
  appendLine()
  appendLine("    internal class KotlinFlowEnumerator<T> : IAsyncEnumerator<T>")
  appendLine("    {")
  appendLine("        private readonly Channel<T> _channel;")
  appendLine("        private readonly CancellationTokenRegistration _cancelReg;")
  appendLine("        private IntPtr _jobHandle;")
  appendLine("        private NugetFlowCallbacks? _callbacks;")
  appendLine("        private bool _done;")
  appendLine()
  appendLine("        public T Current { get; private set; } = default!;")
  appendLine()
  appendLine("        internal KotlinFlowEnumerator(NugetFlowCollectDelegate startCollect, CancellationToken cancellationToken)")
  appendLine("        {")
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
  appendLine("                T value = NugetMarshal.FromHandle<T>(itemPtr);")
  appendLine("                _channel.Writer.TryWrite(value);")
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
  appendLine("                _channel.Writer.TryComplete(NugetErrorNative.BuildException(errorPtr));")
  appendLine("                callbacks.Release();")
  appendLine("            };")
  appendLine()
  appendLine("            callbacks.OnNext = onNext;")
  appendLine("            callbacks.OnComplete = onComplete;")
  appendLine("            callbacks.OnError = onError;")
  appendLine()
  appendLine("            _jobHandle = startCollect(")
  appendLine("                NugetThunks.NugetFlowOnNextPtr,")
  appendLine("                NugetThunks.NugetFlowOnCompletePtr,")
  appendLine("                NugetThunks.NugetFlowOnErrorPtr,")
  appendLine("                callbacks.Root());")
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
    appendLine("    public class KotlinStateFlow<T> : KotlinFlow<T>, IDisposable")
    appendLine("    {")
    appendLine("        private readonly Func<IntPtr> _readValue;")
    appendLine("        private IntPtr _ownedHandle;")
    appendLine()
    appendLine("        internal KotlinStateFlow(NugetFlowCollectDelegate startCollect, Func<IntPtr> readValue, IntPtr ownedHandle = default)")
    appendLine("            : base(startCollect)")
    appendLine("        {")
    appendLine("            _readValue = readValue;")
    appendLine("            _ownedHandle = ownedHandle;")
    appendLine("        }")
    appendLine()
    appendLine("        public T Value => NugetMarshal.FromHandle<T>(_readValue());")
    appendLine()
    appendLine("        public void Dispose()")
    appendLine("        {")
    appendLine("            IntPtr handle = Interlocked.Exchange(ref _ownedHandle, IntPtr.Zero);")
    appendLine("            if (handle != IntPtr.Zero)")
    appendLine("            {")
    appendLine("                NugetMarshal.Dispose(handle);")
    appendLine("            }")
    appendLine("        }")
    appendLine("    }")
    appendLine()

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
      appendLine("            IntPtr ownedHandle = default)")
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

// ADR-068: shared static class holding the two generic exports keyed on an already-obtained
// StateFlow<*> handle -- `Collect`/`Value` operate directly on the awaited flow's own StableRef,
// unlike every ADR-065 per-member `_collect`/`_value` DllImport, which is scoped to one class.
internal fun StringBuilder.renderStateFlowHandleHelper(helper: CirStateFlowHandleHelper) {
  appendLine("    internal static class NugetStateFlowNative")
  appendLine("    {")
  appendLine("        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"nuget_stateflow_collect\")]")
  appendLine("        internal static extern IntPtr Collect(IntPtr flowHandle, IntPtr scopeHandle, IntPtr onNext, IntPtr onComplete, IntPtr onError, IntPtr userData);")
  appendLine()
  appendLine("        [DllImport(\"${helper.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"nuget_stateflow_value\")]")
  appendLine("        internal static extern IntPtr Value(IntPtr flowHandle);")
  appendLine("    }")
  appendLine()
}

internal fun StringBuilder.renderFlowMethod(method: CirMethod, className: String) {
  if (method.isStateFlow) {
    renderStateFlowMethod(method, className)
    return
  }

  val paramStr: String = method.parameters.joinToString(", ") { "${it.type} ${it.name}" }
  val nativeName: String = method.nativeName

  appendLine("        public KotlinFlow<${method.flowElementType}> ${method.name}($paramStr)")
  appendLine("        {")
  appendLine("            if (_handle == IntPtr.Zero)")
  appendLine("                throw new ObjectDisposedException(nameof($className));")
  appendLine("            return new KotlinFlow<${method.flowElementType}>((onNext, onComplete, onError, userData) =>")
  appendLine("                $nativeName(${method.body}));")
  appendLine("        }")
  appendLine()
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
  val paramStr: String = method.parameters.joinToString(", ") { "${it.type} ${it.name}" }
  val paramNames: String = method.parameters.joinToString(", ") { it.name }
  val nativeName: String = method.nativeName
  val valueNativeName: String = method.stateFlowValueNativeName
  val valueCallArgs: String = if (paramNames.isEmpty()) "_handle" else "_handle, $paramNames"
  val nullableSuffix: String = if (method.isStateFlowNullableMember) "?" else ""
  val ctorName: String =
    if (method.isMutableStateFlow) "KotlinMutableStateFlow" else "KotlinStateFlow"

  appendLine("        public $ctorName<${method.flowElementType}>$nullableSuffix ${method.name}($paramStr)")
  appendLine("        {")
  appendLine("            if (_handle == IntPtr.Zero)")
  appendLine("                throw new ObjectDisposedException(nameof($className));")
  if (method.isStateFlowNullableMember) {
    val hasValueNativeName: String = method.stateFlowHasValueNativeName
    appendLine("            if (!$hasValueNativeName($valueCallArgs))")
    appendLine("                return null;")
  }
  appendLine("            return new $ctorName<${method.flowElementType}>((onNext, onComplete, onError, userData) =>")
  appendLine("                $nativeName(${method.body}),")
  if (method.isMutableStateFlow) {
    val setValueNativeName: String = method.stateFlowSetValueNativeName
    val writeReceiver: String = if (method.isMutableStateFlowElementObject) "v._handle" else "v"
    appendLine("                () => $valueNativeName($valueCallArgs),")
    appendLine("                v =>")
    appendLine("                {")
    if (method.isMutableStateFlowElementObject) {
      appendLine("                    if (v is null) throw new ArgumentNullException(nameof(v));")
    }
    appendLine("                    $setValueNativeName($valueCallArgs, $writeReceiver, out IntPtr error);")
    appendLine("                    if (error != IntPtr.Zero) throw NugetErrorNative.BuildException(error);")
    appendLine("                });")
  } else {
    appendLine("                () => $valueNativeName($valueCallArgs));")
  }
  appendLine("        }")
  appendLine()
}

