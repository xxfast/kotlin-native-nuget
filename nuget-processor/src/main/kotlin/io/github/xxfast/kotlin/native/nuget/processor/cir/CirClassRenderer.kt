package io.github.xxfast.kotlin.native.nuget.processor.cir

internal fun StringBuilder.renderGenericClass(cls: CirGenericClass) {
  val typeParams: String = cls.typeParameters.joinToString(", ") { it.name }

  val isConstrained: Boolean = cls.typeParameters.any { it.bounds.isNotEmpty() }

  appendLine("    internal static class ${cls.name}Native")
  appendLine("    {")

  if (isConstrained && cls.hasPublicConstructor) {
    appendLine("        [DllImport(\"${cls.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"${cls.nativePrefix}_create_object\")]")
    appendLine("        internal static extern IntPtr Create_object(IntPtr value, out IntPtr error);")
    appendLine()
  }

  for (prop in cls.properties) {
    appendLine("        [DllImport(\"${cls.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"${cls.nativePrefix}_get_${prop.nativeName}\")]")
    appendLine("        internal static extern ${prop.nativeReturnType} Get_${prop.nativeName}(IntPtr handle);")
    appendLine()
  }

  if (cls.disposable) {
    appendLine("        [DllImport(\"${cls.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"${cls.nativePrefix}_dispose\")]")
    appendLine("        internal static extern void Dispose(IntPtr handle);")
  }

  appendLine("    }")
  appendLine()

  val whereClause: String = cls.typeParameters
    .filter { it.bounds.isNotEmpty() }
    .joinToString(" ") { param ->
      "where ${param.name} : ${param.bounds.joinToString(", ")}"
    }

  val whereStr: String = if (whereClause.isNotEmpty()) " $whereClause" else ""
  appendLine("    public class ${cls.name}<$typeParams> : IDisposable$whereStr")
  appendLine("    {")
  appendLine("        internal IntPtr _handle;")
  appendLine()

  if (cls.hasPublicConstructor) {
    appendLine("        public ${cls.name}(${cls.typeParameters[0].name} value)")
    appendLine("        {")
    if (isConstrained) {
      appendLine("            var field = typeof(${cls.typeParameters[0].name}).GetField(\"_handle\",")
      appendLine("                System.Reflection.BindingFlags.Instance | System.Reflection.BindingFlags.NonPublic | System.Reflection.BindingFlags.Public);")
      appendLine("            IntPtr handle = ${cls.name}Native.Create_object((IntPtr)field!.GetValue(value)!, out IntPtr error);")
      appendLine("            if (error != IntPtr.Zero)")
      appendLine("            {")
      appendLine("                throw NugetErrorNative.BuildException(error);")
      appendLine("            }")
      appendLine("            _handle = handle;")
    } else {
      appendLine("            _handle = NugetMarshal.CreateBox<${cls.typeParameters[0].name}>(value);")
    }
    appendLine("        }")
    appendLine()
  }

  appendLine("        internal ${cls.name}(IntPtr handle)")
  appendLine("        {")
  appendLine("            _handle = handle;")
  appendLine("        }")
  appendLine()

  for (prop in cls.properties) {
    appendLine("        public ${prop.type} ${prop.name} => ${prop.getter};")
    appendLine()
  }

  if (cls.disposable) {
    appendLine("        public void Dispose()")
    appendLine("        {")
    appendLine("            if (_handle != IntPtr.Zero)")
    appendLine("            {")
    appendLine("                ${cls.name}Native.Dispose(_handle);")
    appendLine("                _handle = IntPtr.Zero;")
    appendLine("            }")
    appendLine("        }")
  }

  appendLine("    }")
}

internal fun StringBuilder.renderInterface(iface: CirInterface) {
  val typeParamStr: String = if (iface.typeParameters.isNotEmpty()) {
    val params: String = iface.typeParameters.joinToString(", ") { param ->
      val prefix: String = when (param.variance) {
        CirVariance.COVARIANT -> "out "
        CirVariance.CONTRAVARIANT -> "in "
        CirVariance.INVARIANT -> ""
      }
      "$prefix${param.name}"
    }
    "<$params>"
  } else ""

  appendLine("    public interface ${iface.name}$typeParamStr : IDisposable")
  appendLine("    {")

  for (prop in iface.properties) {
    if (prop.hasSetter) {
      appendLine("        ${prop.type} ${prop.name} { get; set; }")
    } else {
      appendLine("        ${prop.type} ${prop.name} { get; }")
    }
  }

  if (iface.properties.isNotEmpty() && iface.methods.isNotEmpty()) {
    appendLine()
  }

  for (method in iface.methods) {
    val paramStr: String = method.parameters.joinToString(", ") { "${it.type} ${it.name}" }
    appendLine("        ${method.returnType} ${method.name}($paramStr);")
  }

  appendLine("    }")
  appendLine()
}

internal fun StringBuilder.renderStaticClass(cls: CirStaticClass) {
  appendLine("    public static partial class ${cls.name}")
  appendLine("    {")

  for (member in cls.members) {
    renderMember(member)
  }

  appendLine("    }")
}

internal fun StringBuilder.renderClass(cls: CirClass) {
  val abstract: String = if (cls.isAbstract) "abstract " else ""

  val implements: String = when {
    cls.superClass != null -> " : ${cls.superClass}"
    cls.interfaces.isNotEmpty() -> " : ${cls.interfaces.joinToString(", ")}"
    cls.disposable && cls.hasSuspendMethods -> " : IDisposable, IAsyncDisposable"
    cls.disposable -> " : IDisposable"
    else -> ""
  }

  appendLine("    public ${abstract}class ${cls.name}$implements")
  appendLine("    {")

  if (cls.superClass == null) {
    appendLine("        internal IntPtr _handle;")
    if (cls.hasSuspendMethods) {
      appendLine("        internal IntPtr _scopeHandle;")
    }
    appendLine()

    if (cls.hasSuspendMethods) {
      appendLine("        private IntPtr GetOrCreateScope()")
      appendLine("        {")
      appendLine("            IntPtr existing = _scopeHandle;")
      appendLine("            if (existing != IntPtr.Zero) return existing;")
      appendLine("            IntPtr created = NugetScopeNative.Create();")
      appendLine("            IntPtr prior = Interlocked.CompareExchange(ref _scopeHandle, created, IntPtr.Zero);")
      appendLine("            if (prior != IntPtr.Zero)")
      appendLine("            {")
      appendLine("                NugetScopeNative.Dispose(created);")
      appendLine("                return prior;")
      appendLine("            }")
      appendLine("            return created;")
      appendLine("        }")
      appendLine()
    }
  }

  if (cls.constructor != null && !cls.isAbstract) {
    renderClassConstructor(cls, cls.constructor)
  }

  if (!cls.isAbstract) {
    for (secondary in cls.secondaryConstructors) {
      renderClassConstructor(cls, secondary)
    }
  }

  if (cls.hasInternalHandleConstructor) {
    if (cls.superClass != null) {
      appendLine("        internal ${cls.name}(IntPtr handle) : base(handle)")
      appendLine("        {")
      appendLine("        }")
    } else {
      appendLine("        internal ${cls.name}(IntPtr handle)")
      appendLine("        {")
      appendLine("            _handle = handle;")
      appendLine("        }")
    }
    appendLine()
  }

  for (prop in cls.properties) {
    if (prop.isFlow) {
      val collectEntryPoint = "${cls.nativePrefix}_get_${prop.nativeName}_collect"
      appendLine("        [DllImport(\"${cls.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"$collectEntryPoint\")]")
      appendLine("        private static extern IntPtr Native_Get${prop.name}Collect(IntPtr handle, IntPtr scopeHandle, IntPtr onNext, IntPtr onComplete, IntPtr onError, IntPtr userData);")
      appendLine()
      renderProperty(prop)
    } else if (prop.usesLegacyNativeImport()) {
      renderLegacyPropertyNativeImports(cls, prop)
      renderProperty(prop)
    } else {
      cls.propertyNativeImports(prop).forEach { nativeImport -> renderDllImport(nativeImport) }
      renderProperty(prop)
    }
  }

  for (method in cls.methods) {
    if (!method.isAbstract) {
      if (method.isAsync || method.isFlow) {
        renderLegacyMethodNativeImport(cls, method)
      } else {
        renderDllImport(cls.methodNativeImport(method))
      }
    }
    renderMethod(method, cls.name)
  }

  cls.callbackMethods.forEach { cbMethod ->
    renderCallbackMethod(cbMethod)
  }

  cls.storedCallbackMethods.forEach { scMethod ->
    renderStoredCallbackMethod(scMethod)
  }

  cls.interfaceBridgeMethods.forEach { ibMethod ->
    renderInterfaceBridgeMethod(ibMethod)
  }

  for (member in cls.companionMembers) {
    renderMember(member, cls.name)
  }

  if (cls.isDataClass) {
    renderDataClassMethods(cls)
  }

  if (cls.disposable) {
    renderDispose(
      nativeImport = cls.disposeNativeImport(),
      isAbstract = cls.isAbstract,
      hasSuperClass = cls.superClass != null,
      hasSuspendMethods = cls.hasSuspendMethods,
    )
  }

  appendLine("    }")
}

private fun StringBuilder.renderLegacyPropertyNativeImports(cls: CirClass, prop: CirProperty) {
  val getterErrorParam: String = if (prop.hasSyncErrorOut) ", out IntPtr error" else ""
  appendLine("        [DllImport(\"${cls.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"${cls.nativePrefix}_get_${prop.nativeName}\")]")
  appendLine("        private static extern ${prop.nativeReturnType} Native_Get_${prop.nativeName}(IntPtr handle$getterErrorParam);")
  appendLine()
}

// Emits the [DllImport] for a constructor's native create entry point plus the
// C# constructor itself. Used for the primary and every secondary (ADR-034).
private fun StringBuilder.renderClassConstructor(cls: CirClass, ctor: CirConstructor) {
  renderDllImport(cls.constructorNativeImport(ctor))
  renderConstructor(cls.name, ctor, cls.superClass != null, cls.hasSuspendMethods)
}

private fun StringBuilder.renderLegacyMethodNativeImport(cls: CirClass, method: CirMethod) {
  val nativeParamList: MutableList<String> = (listOf("IntPtr handle") +
      method.parameters.map { "${it.nativeType} ${it.name}" }).toMutableList()
  nativeParamList.addAll(method.extraNativeParams)
  if (method.isSyncErrorCheckEnabled) nativeParamList.add("out IntPtr error")
  val nativeParams: String = nativeParamList.joinToString(", ")
  appendLine("        [DllImport(\"${cls.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"${cls.nativePrefix}_${method.nativeName}\")]")
  appendLine("        private static extern ${method.nativeReturnType} Native_${method.name}($nativeParams);")
  appendLine()
}

internal fun StringBuilder.renderConstructor(
  className: String,
  ctor: CirConstructor,
  hasSuperClass: Boolean = false,
  hasSuspendMethods: Boolean = false,
) {
  val paramStr: String = ctor.parameters.joinToString(", ") { "${it.type} ${it.name}" }
  val paramNames: String = ctor.parameters.joinToString(", ") { it.name }
  val nativeCallArgs: String = if (paramNames.isEmpty()) "out IntPtr error" else "$paramNames, out IntPtr error"

  if (hasSuperClass) {
    appendLine("        public $className($paramStr) : base(IntPtr.Zero)")
  } else {
    appendLine("        public $className($paramStr)")
  }

  if (ctor.hasErrorCheck) {
    appendLine("        {")
    appendLine("            IntPtr handle = Native_Create${ctor.nativeSuffix}($nativeCallArgs);")
    appendLine("            if (error != IntPtr.Zero)")
    appendLine("            {")
    appendLine("                throw NugetErrorNative.BuildException(error);")
    appendLine("            }")
    appendLine("            _handle = handle;")
    appendLine("        }")
  } else {
    appendLine("        {")
    appendLine("            ${ctor.body}")
    appendLine("        }")
  }

  appendLine()
}

internal fun StringBuilder.renderProperty(prop: CirProperty) {
  val static: String = if (prop.isStatic) "static " else ""
  val isMultiLineGetter: Boolean = prop.getter.contains('\n')
  val isMultiLineSetter: Boolean = prop.setter?.contains('\n') == true
  if (isMultiLineGetter && prop.setter != null) {
    appendLine("        public ${static}${prop.type} ${prop.name}")
    appendLine("        {")
    appendLine("            get")
    appendLine("            {${prop.getter}")
    appendLine("            }")
    appendLine("            set")
    appendLine("            {${prop.setter}")
    appendLine("            }")
    appendLine("        }")
  } else if (isMultiLineGetter) {
    appendLine("        public ${static}${prop.type} ${prop.name}")
    appendLine("        {")
    appendLine("            get")
    appendLine("            {${prop.getter}")
    appendLine("            }")
    appendLine("        }")
  } else if (prop.setter == null) {
    appendLine("        public ${static}${prop.type} ${prop.name} => ${prop.getter};")
  } else if (isMultiLineSetter) {
    appendLine("        public ${static}${prop.type} ${prop.name}")
    appendLine("        {")
    appendLine("            get => ${prop.getter};")
    appendLine("            set")
    appendLine("            {${prop.setter}")
    appendLine("            }")
    appendLine("        }")
  } else {
    appendLine("        public ${static}${prop.type} ${prop.name}")
    appendLine("        {")
    appendLine("            get => ${prop.getter};")
    appendLine("            set => ${prop.setter};")
    appendLine("        }")
  }
  appendLine()
}

internal fun StringBuilder.renderMember(member: CirMember, className: String = "") {
  when (member) {
    is CirDllImport -> renderDllImport(member)
    is CirMethod -> renderMethod(member, className)
    is CirProperty -> renderProperty(member)
    is CirConst -> renderConst(member)
    is CirCallbackMethod -> renderCallbackMethod(member)
    is CirStoredCallbackMethod -> renderStoredCallbackMethod(member)
    is CirInterfaceBridgeMethod -> renderInterfaceBridgeMethod(member)
  }
}

internal fun StringBuilder.renderConst(const: CirConst) {
  appendLine("        public const ${const.type} ${const.name} = ${const.value};")
  appendLine()
}

internal fun StringBuilder.renderDllImport(import: CirDllImport) {
  val visibility: String = if (import.visibility == CirVisibility.PRIVATE) "private" else "public"
  val entryPoint: String = if (import.entryPoint != null) ", EntryPoint = \"${import.entryPoint}\"" else ""
  // The DllImport signature always speaks the native type, which differs from the public C#
  // type when a cast is needed at the call site (e.g. enum params: public "CatMood", native
  // "int"). CirParameter.nativeType defaults to type, so this is a no-op for every other param.
  val paramList: MutableList<String> =
    import.parameters.map { "${it.nativeType} ${it.name}" }.toMutableList()
  if (import.hasSyncErrorOut) paramList.add("out IntPtr error")
  val paramStr: String = paramList.joinToString(", ")

  appendLine("        [DllImport(\"${import.libraryName}\", CallingConvention = CallingConvention.Cdecl$entryPoint)]")
  if (import.marshalBooleanReturn) appendLine("        [return: MarshalAs(UnmanagedType.I1)]")
  appendLine("        $visibility static extern ${import.returnType} ${import.name}($paramStr);")
  appendLine()
}

internal fun StringBuilder.renderMethod(method: CirMethod, className: String = "") {
  if (method.isAsync) {
    renderAsyncMethod(method, className)
    return
  }

  if (method.isFlow) {
    renderFlowMethod(method, className)
    return
  }

  if (method.isSyncErrorCheckEnabled && !method.hasCustomBody) {
    renderSyncErrorCheckMethod(method, className)
    return
  }

  val visibility: String = if (method.visibility == CirVisibility.PRIVATE) "private" else "public"
  val static: String = if (method.isStatic) "static " else ""
  val override: String = if (method.isOverride) "override " else ""
  val abstract: String = if (method.isAbstract) "abstract " else ""
  val paramStr: String = method.parameters.mapIndexed { index, param ->
    if (method.isExtension && index == 0) "this ${param.type} ${param.name}"
    else "${param.type} ${param.name}"
  }.joinToString(", ")

  // A standalone `T` type-parameter *token* — never a substring match. The old
  // `.contains("T")` matched the letter T anywhere, including inside an ordinary type name like
  // `Toy` (ADR-061 surfaced this: an extension function on `Toy` was rendered as a bogus
  // `ToyExtensions.FindOwner<T>(Toy)`, an uninferable generic method neither side intended).
  val genericTypeToken: Regex = Regex("(?<![A-Za-z0-9_])T(?![A-Za-z0-9_])")
  val hasGenericType: Boolean = method.typeParameters.isNotEmpty() ||
      genericTypeToken.containsMatchIn(method.returnType) ||
      method.parameters.any { genericTypeToken.containsMatchIn(it.type) }

  val genericDecl: String = if (hasGenericType && method.isStatic) {
    val names: String = if (method.typeParameters.isNotEmpty()) {
      method.typeParameters.joinToString(", ") { it.name }
    } else "T"
    "<$names>"
  } else ""

  val whereClause: String = method.typeParameters
    .filter { it.bounds.isNotEmpty() }
    .joinToString(" ") { param ->
      "where ${param.name} : ${param.bounds.joinToString(", ")}"
    }

  val whereStr: String =
    if (whereClause.isNotEmpty()) " $whereClause" else ""

  if (method.isAbstract) {
    appendLine("        $visibility ${abstract}${method.returnType} ${method.name}$genericDecl($paramStr)$whereStr;")
  } else {
    val isMultiLine: Boolean = method.body.contains('\n')

    if (isMultiLine) {
      if (method.returnType == "void") {
        appendLine("        $visibility ${static}${override}void ${method.name}$genericDecl($paramStr)$whereStr")
      } else {
        appendLine("        $visibility $static$override${method.returnType} ${method.name}$genericDecl($paramStr)$whereStr")
      }
      appendLine("        {${method.body}")
      appendLine("        }")
    } else {
      if (method.returnType == "void") {
        appendLine("        $visibility $static$override void ${method.name}$genericDecl($paramStr)$whereStr")
        appendLine("            => ${method.body};")
      } else {
        appendLine("        $visibility $static$override${method.returnType} ${method.name}$genericDecl($paramStr)$whereStr")
        appendLine("            => ${method.body};")
      }
    }
  }

  appendLine()
}

internal fun StringBuilder.renderDataClassMethods(cls: CirClass) {
  cls.dataClassNativeImports().forEach { nativeImport -> renderDllImport(nativeImport) }

  if (cls.copyMethod != null) {
    renderMethod(cls.copyMethod, cls.name)
  } else if (cls.constructor != null) {
    val copyParams: String = cls.constructor.parameters.joinToString(", ") { "${it.type} ${it.name}" }
    val copyParamNames: String = cls.constructor.parameters.joinToString(", ") { it.name }
    val copyNativeArgs: String = if (copyParamNames.isEmpty()) {
      "_handle, out IntPtr error"
    } else {
      "_handle, $copyParamNames, out IntPtr error"
    }
    appendLine("        public ${cls.name} Copy($copyParams)")
    appendLine("        {")
    appendLine("            IntPtr handle = Native_Copy($copyNativeArgs);")
    appendLine("            if (error != IntPtr.Zero)")
    appendLine("            {")
    appendLine("                throw NugetErrorNative.BuildException(error);")
    appendLine("            }")
    appendLine("            return new ${cls.name}(handle);")
    appendLine("        }")
    appendLine()
  }

  appendLine("        public override bool Equals(object? obj)")
  appendLine("        {")
  appendLine("            if (obj is ${cls.name} other) return Native_Equals(_handle, other._handle);")
  appendLine("            return false;")
  appendLine("        }")
  appendLine()
  appendLine("        public override int GetHashCode() => Native_HashCode(_handle);")
  appendLine()
  appendLine("        public override string ToString() => Marshal.PtrToStringUTF8(Native_ToString(_handle))!;")
  appendLine()
}

internal fun StringBuilder.renderDispose(
  nativeImport: CirDllImport?,
  isAbstract: Boolean = false,
  hasSuperClass: Boolean = false,
  hasSuspendMethods: Boolean = false,
) {
  val abstract: String = if (isAbstract) "abstract " else ""
  val override: String = if (hasSuperClass) "override " else ""

  if (isAbstract) {
    appendLine("        public ${abstract}void Dispose();")
  } else {
    renderDllImport(requireNotNull(nativeImport) { "Concrete disposable classes require a native import" })
    appendLine("        public ${override}void Dispose()")
    appendLine("        {")
    appendLine("            IntPtr handle = Interlocked.Exchange(ref _handle, IntPtr.Zero);")
    appendLine("            if (handle == IntPtr.Zero) return;")
    if (hasSuspendMethods) {
      appendLine("            IntPtr scopeHandle = Interlocked.Exchange(ref _scopeHandle, IntPtr.Zero);")
      appendLine("            if (scopeHandle != IntPtr.Zero)")
      appendLine("            {")
      appendLine("                NugetScopeNative.Cancel(scopeHandle);")
      appendLine("                NugetScopeNative.Dispose(scopeHandle);")
      appendLine("            }")
    }
    appendLine("            Native_Dispose(handle);")
    appendLine("        }")
    if (hasSuspendMethods) {
      appendLine()
      appendLine("        public ValueTask DisposeAsync()")
      appendLine("        {")
      appendLine("            IntPtr handle = Interlocked.Exchange(ref _handle, IntPtr.Zero);")
      appendLine("            if (handle == IntPtr.Zero) return ValueTask.CompletedTask;")
      appendLine("            IntPtr scopeHandle = Interlocked.Exchange(ref _scopeHandle, IntPtr.Zero);")
      appendLine("            if (scopeHandle == IntPtr.Zero)")
      appendLine("            {")
      appendLine("                Native_Dispose(handle);")
      appendLine("                return ValueTask.CompletedTask;")
      appendLine("            }")
      appendLine("            return new ValueTask(DrainAndDisposeAsync(handle, scopeHandle));")
      appendLine("        }")
      appendLine()
      appendLine("        private Task DrainAndDisposeAsync(IntPtr handle, IntPtr scopeHandle)")
      appendLine("        {")
      appendLine("            var tcs = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);")
      appendLine("            GCHandle tcsHandle = GCHandle.Alloc(tcs);")
      appendLine("            NugetAsyncCallback callback = null!;")
      appendLine("            GCHandle callbackHandle = default;")
      appendLine("            IntPtr drainJobHandle = IntPtr.Zero;")
      appendLine("            callback = (resultPtr, errorPtr, isCancelled, userData) =>")
      appendLine("            {")
      appendLine("                NugetJobNative.Dispose(drainJobHandle);")
      appendLine("                callbackHandle.Free();")
      appendLine("                var t = (TaskCompletionSource<bool>)GCHandle.FromIntPtr(userData).Target!;")
      appendLine("                GCHandle.FromIntPtr(userData).Free();")
      appendLine("                NugetScopeNative.Dispose(scopeHandle);")
      appendLine("                Native_Dispose(handle);")
      appendLine("                if (isCancelled != 0)")
      appendLine("                    t.TrySetCanceled();")
      appendLine("                else")
      appendLine("                    t.SetResult(true);")
      appendLine("            };")
      appendLine("            callbackHandle = GCHandle.Alloc(callback);")
      appendLine("            drainJobHandle = NugetScopeNative.Drain(scopeHandle, callback, GCHandle.ToIntPtr(tcsHandle));")
      appendLine("            return tcs.Task;")
      appendLine("        }")
    }
  }
}

private fun StringBuilder.renderStoredCallbackMethod(method: CirStoredCallbackMethod) {
  appendLine("        [DllImport(\"${method.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"${method.subscribeEntryPoint}\")]")
  appendLine("        private static extern IntPtr Native_${method.csMethodName}(IntPtr handle, IntPtr listenerPtr, IntPtr userData, out IntPtr error);")
  appendLine()
  appendLine("        [DllImport(\"${method.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"${method.removeEntryPoint}\")]")
  appendLine("        private static extern void ${method.csRemoveNativeName}(IntPtr handle, IntPtr subscriptionHandle);")
  appendLine()
  appendLine("        public IDisposable ${method.csMethodName}(${method.csParamType} listener)")
  appendLine("        {")
  appendLine("            ${method.delegateName} nativeCallback = ${method.delegateParamList} => { ${method.nativeCallbackBody} };")
  appendLine("            GCHandle cbHandle = GCHandle.Alloc(nativeCallback);")
  appendLine("            IntPtr fnPtr = Marshal.GetFunctionPointerForDelegate(nativeCallback);")
  appendLine("            IntPtr sub = Native_${method.csMethodName}(_handle, fnPtr, IntPtr.Zero, out IntPtr error);")
  appendLine("            if (error != IntPtr.Zero) throw NugetErrorNative.BuildException(error);")
  appendLine("            return new NugetSubscription(() => { ${method.csRemoveNativeName}(_handle, sub); cbHandle.Free(); });")
  appendLine("        }")
  appendLine()
}

private fun StringBuilder.renderInterfaceBridgeMethod(method: CirInterfaceBridgeMethod) {
  // DllImport for subscribe: handle + per-method (fnPtr, ctx) pairs + error
  val nativeAddParams: String = buildString {
    append("IntPtr handle")
    method.entries.forEach { entry ->
      append(", IntPtr ${entry.methodKtName}Ptr, IntPtr ${entry.methodKtName}Ctx")
    }
    append(", out IntPtr error")
  }
  appendLine(
    "        [DllImport(\"${method.libraryName}\", CallingConvention = CallingConvention.Cdecl, " +
        "EntryPoint = \"${method.subscribeEntryPoint}\")]"
  )
  appendLine("        private static extern IntPtr Native_${method.csMethodName}($nativeAddParams);")
  appendLine()

  // DllImport for unsubscribe
  appendLine(
    "        [DllImport(\"${method.libraryName}\", CallingConvention = CallingConvention.Cdecl, " +
        "EntryPoint = \"${method.removeEntryPoint}\")]"
  )
  appendLine(
    "        private static extern void ${method.csRemoveNativeName}(IntPtr handle, IntPtr subscriptionHandle);"
  )
  appendLine()

  // Public IDisposable method
  appendLine("        public IDisposable ${method.csMethodName}(${method.interfaceCsName} listener)")
  appendLine("        {")
  appendLine(
    "            if (_handle == IntPtr.Zero) throw new ObjectDisposedException(nameof(${method.className}));"
  )

  // Delegate assignments
  method.entries.forEach { entry ->
    appendLine(
      "            ${entry.delegateName} ${entry.methodKtName}Cb = " +
          "${entry.delegateParamList} => { ${entry.callbackBody} };"
    )
  }

  // GCHandle allocations
  method.entries.forEachIndexed { i, entry ->
    appendLine("            GCHandle h$i = GCHandle.Alloc(${entry.methodKtName}Cb);")
  }

  // Native subscribe call
  val nativeCallArgs: String = buildString {
    append("_handle")
    method.entries.forEach { entry ->
      append(", Marshal.GetFunctionPointerForDelegate(${entry.methodKtName}Cb), IntPtr.Zero")
    }
  }
  appendLine("            IntPtr sub = Native_${method.csMethodName}($nativeCallArgs, out IntPtr error);")

  // Error check with handle freeing
  val freeHandles: String = method.entries.indices.joinToString(" ") { "h$it.Free();" }
  appendLine("            if (error != IntPtr.Zero) { $freeHandles throw NugetErrorNative.BuildException(error); }")

  // Return NugetSubscription
  appendLine(
    "            return new NugetSubscription(() => { ${method.csRemoveNativeName}(_handle, sub); $freeHandles });"
  )
  appendLine("        }")
  appendLine()
}

private fun StringBuilder.renderCallbackMethod(method: CirCallbackMethod) {
  appendLine("        [DllImport(\"${method.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"${method.nativeEntryPoint}\")]")
  appendLine("        private static extern ${method.nativeImportReturnType} Native_${method.csMethodName}(IntPtr handle, IntPtr ${method.lambdaParamName}Ptr, IntPtr userData, out IntPtr error);")
  appendLine()
  appendLine("        public ${method.csReturnType} ${method.csMethodName}(${method.csParamType} ${method.lambdaParamName})")
  appendLine("        {")
  appendLine("            ${method.delegateName} nativeCallback = ${method.delegateParamList} =>")
  appendLine("            {")
  appendLine(method.callbackBody)
  appendLine("            };")
  appendLine("            GCHandle cbHandle = GCHandle.Alloc(nativeCallback);")
  appendLine("            IntPtr fnPtr = Marshal.GetFunctionPointerForDelegate(nativeCallback);")
  appendLine("            try")
  appendLine("            {")
  appendLine(method.wrapperBody)
  appendLine("            }")
  appendLine("            finally")
  appendLine("            {")
  appendLine("                cbHandle.Free();")
  appendLine("            }")
  appendLine("        }")
  appendLine()
}

