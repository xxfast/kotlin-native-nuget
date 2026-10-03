package io.github.xxfast.kotlin.native.nuget.processor.cir

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

  renderDoc(iface.doc, generated = iface.remarks)
  // ADR-174 ruling 5: `IAsyncDisposable` when the interface projects a scope-using member.
  val bases: String = (iface.superInterfaces + "IDisposable" +
      listOfNotNull("IAsyncDisposable".takeIf { iface.isAsyncDisposable })).joinToString(", ")
  appendLine("    public interface ${iface.name}$typeParamStr : $bases")
  appendLine("    {")

  for (prop in iface.properties) {
    renderDoc(prop.doc, "        ", generated = prop.remarks)
    val modifier: String = if (prop.isNew) "new " else ""
    if (prop.hasSetter) {
      appendLine("        $modifier${prop.type} ${prop.identifier} { get; set; }")
    } else {
      appendLine("        $modifier${prop.type} ${prop.identifier} { get; }")
    }
  }

  if (iface.properties.isNotEmpty() && iface.methods.isNotEmpty()) {
    appendLine()
  }

  for (method in iface.methods) {
    renderDoc(method.doc, "        ")
    // ADR-174: a `suspend` member ends in the class route's own `CancellationToken ... = default`,
    // minted the same way (`CirAsyncLocals`) so an implementing class's `Async` method matches.
    val cancellationToken: String = CirAsyncLocals.of(method.parameters).cancellationToken
    val token: String? =
      if (method.isAsync) "CancellationToken $cancellationToken = default" else null
    val paramStr: String =
      (method.parameters.map { it.declaration } + listOfNotNull(token)).joinToString(", ")
    val modifier: String = if (method.isNew) "new " else ""
    appendLine("        $modifier${method.returnType} ${method.identifier}($paramStr);")
  }

  // ADR-134: a type Kotlin declares inside the interface is declared inside the generated
  // `public interface I<Name>` block (C# spec 19.4.1 admits a type_declaration there).
  renderNestedDeclarations(iface.nestedDeclarations)

  appendLine("    }")
  appendLine()
}

internal fun StringBuilder.renderStaticClass(cls: CirStaticClass) {
  renderDoc(generated = cls.remarks)
  appendLine("    public static partial class ${cls.name}")
  appendLine("    {")

  for (member in cls.members) {
    renderMember(member)
  }

  appendLine("    }")
}

/**
 * ADR-147: a `[DllImport]` may not be declared inside a generic type (CS7042), so a generic
 * carrier's externs are hoisted into a sibling non-generic `{Name}Native` static class and each
 * in-class declaration becomes a plain forwarding method with the identical signature. The class
 * body is rendered by the ordinary path first and rewritten afterwards, so a generic class and an
 * ordinary one cannot drift: there is exactly one renderer, and the hoist is a mechanical move.
 */
internal fun StringBuilder.renderClass(cls: CirClass) {
  // ADR-174: an interface's backing wrapper hoists into a named carrier too (`FeedNative`), the
  // same mechanical move, so a generic implementer's explicit interface implementations can reach
  // the interface's imports. One copy of each extern, so the ABI contract still sees it once.
  val carrier: String = cls.nativeCarrier ?: "${cls.name}Native"
  if (cls.typeParameters.isEmpty() && cls.nativeCarrier == null) {
    renderClassDeclaration(cls)
    return
  }
  val body: String = StringBuilder().apply { renderClassDeclaration(cls) }.toString()
  val hoisted: HoistedDllImports = hoistDllImports(body, carrier)
  if (hoisted.imports.isNotEmpty()) {
    appendLine("    internal static class $carrier")
    appendLine("    {")
    hoisted.imports.forEach { import -> append(import) }
    appendLine("    }")
    appendLine()
  }
  append(hoisted.body)
}

/**
 * The externs lifted out of a generic class's body, and the body with forwarders in their place.
 */
private class HoistedDllImports(val body: String, val imports: List<String>)

private val DLL_IMPORT_ATTRIBUTE = Regex("""^\s*\[DllImport\(""")
private val EXTERN_DECLARATION =
  Regex("""^(\s*)(?:private|internal|public) (?:new )?static extern (\S+) (\w+)\((.*)\);$""")

private fun hoistDllImports(body: String, nativeClass: String): HoistedDllImports {
  val lines: List<String> = body.lines()
  val kept: MutableList<String> = mutableListOf()
  val imports: MutableList<String> = mutableListOf()
  var index = 0
  while (index < lines.size) {
    val line: String = lines[index]
    if (!DLL_IMPORT_ATTRIBUTE.containsMatchIn(line)) {
      kept.add(line)
      index++
      continue
    }
    // The block is the attribute(s) plus exactly one extern declaration; everything between is a
    // `[return: MarshalAs(...)]` this renderer emitted itself.
    val block: MutableList<String> = mutableListOf(line)
    var cursor: Int = index + 1
    while (cursor < lines.size && EXTERN_DECLARATION.find(lines[cursor]) == null) {
      block.add(lines[cursor])
      cursor++
    }
    check(cursor < lines.size) { "Unterminated DllImport block while hoisting out of $nativeClass" }
    val declaration = requireNotNull(EXTERN_DECLARATION.find(lines[cursor])) {
      "DllImport block in $nativeClass has no extern declaration"
    }
    val (indent, returnType, name, parameters) = declaration.destructured
    block.add("$indent    internal static extern $returnType $name($parameters);")
    imports.add(block.joinToString("\n", postfix = "\n") { entry -> "    $entry" } + "\n")
    kept.add(
      "${indent}private static $returnType $name($parameters) => " +
          "$nativeClass.$name(${forwardedArguments(parameters)});",
    )
    index = cursor + 1
  }
  return HoistedDllImports(kept.joinToString("\n"), imports)
}

/** `out IntPtr error, [MarshalAs(UnmanagedType.U2)] char c` -> `out error, c`. */
private fun forwardedArguments(parameters: String): String {
  if (parameters.isBlank()) return ""
  return parameters.split(',').joinToString(", ") { parameter ->
    val trimmed: String = parameter.trim()
    val name: String = trimmed.substringAfterLast(' ')
    val modifier: String = when {
      trimmed.startsWith("out ") -> "out "
      trimmed.startsWith("ref ") -> "ref "
      else -> ""
    }
    "$modifier$name"
  }
}

private fun StringBuilder.renderClassDeclaration(cls: CirClass) {
  val abstract: String = if (cls.isAbstract) "abstract " else ""
  val sealedModifier: String = if (cls.isSealed) "sealed " else ""

  // ADR-094: a class declares `_handle` (and therefore implements INugetHandle) exactly when it has
  // no superclass; a derived class inherits both the field and the explicit implementation.
  // The disposables ride *beside* the exported interfaces rather than instead of them (ADR-094
  // amendment 2026-09-10): `renderDispose` emits `Dispose()` unconditionally and `DisposeAsync()`
  // whenever the class owns a scope, so the base list has to advertise what the body implements or
  // the class cannot be held as an `IAsyncDisposable`. Same spelling `CirSealedRenderer` gives a
  // suspending arm (ADR-118).
  // ADR-101 amendment (2026-09-11): a derived class lists its own interfaces beside the base. The
  // disposables stay off that list: the base declares `_handle`, implements `INugetHandle` and
  // carries `IDisposable`, and a derived class inherits all three.
  // ADR-159: `IAsyncDisposable` rides on scope OWNERSHIP, not on base-lessness. A derived class
  // that projects the chain's first async member declares the scope and the drain, so it has to
  // advertise them; a class below the owner inherits the interface with the body and must not
  // re-list it.
  val asyncDisposable: List<String> = listOfNotNull("IAsyncDisposable".takeIf { cls.ownsScope })
  val implements: String = if (cls.superClass != null) {
    " : " + (listOf(cls.superClass) + cls.interfaces + asyncDisposable).joinToString(", ")
  } else {
    val disposables: List<String> = listOf("IDisposable") + asyncDisposable
    " : " + (cls.interfaces + disposables + "INugetHandle").distinct().joinToString(", ")
  }

  // ADR-147: the generic carrier. `Crate<T>` plus one `where T : Bound` per bounded parameter,
  // after the base list, which is where C# wants it.
  val typeParameters: String =
    if (cls.typeParameters.isEmpty()) ""
    else cls.typeParameters.joinToString(", ", prefix = "<", postfix = ">") { it.name }
  val constraints: String = cls.typeParameters
    .filter { it.bounds.isNotEmpty() }
    .joinToString(" ") { param -> "where ${param.name} : ${param.bounds.joinToString(", ")}" }
  val whereClause: String = if (constraints.isEmpty()) "" else " $constraints"

  // ADR-150 amendment: one `<remarks>` for both halves -- the author's KDoc paragraphs, then
  // ADR-064's generated prose as the last `<para>`.
  renderDoc(cls.doc, generated = cls.remarks)
  appendLine(
    "    public $sealedModifier${abstract}class ${cls.name}$typeParameters$implements$whereClause",
  )
  appendLine("    {")

  if (cls.superClass == null) {
    appendLine("        internal NugetKotlinHandle _handle = NugetKotlinHandle.Null;")
    if (cls.ownsScope) renderScopeHandleField()
    appendLine()
    appendLine("        IntPtr INugetHandle.Handle => _handle.DangerousGetHandle();")
    appendLine()

    if (cls.ownsScope) {
      renderGetOrCreateScope()
      appendLine()
    }
  } else if (cls.ownsScope) {
    // ADR-159: a derived owner. `_handle` and `INugetHandle` stay on the base (ADR-094), the scope
    // does not: it belongs to the level that projects the first async member.
    renderScopeHandleField()
    appendLine()
    renderGetOrCreateScope()
    appendLine()
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
    appendLine(
      "        internal ${cls.name}(IntPtr handle, out NugetHandleTag tag) : " +
          "this(new NugetKotlinHandle(handle), out tag)",
    )
    appendLine("        {")
    appendLine("        }")
    appendLine()
    if (cls.superClass != null) {
      appendLine(
        "        internal ${cls.name}(NugetKotlinHandle handle, out NugetHandleTag tag) : " +
            "base(handle, out tag)"
      )
      appendLine("        {")
      appendLine("        }")
    } else {
      appendLine("        internal ${cls.name}(NugetKotlinHandle handle, out NugetHandleTag tag)")
      appendLine("        {")
      appendLine("            tag = default;")
      appendLine("            _handle = handle;")
      appendLine("        }")
    }
    appendLine()
  }

  for (prop in cls.properties) {
    if (!prop.hasNativeImport) {
      // ADR-075 amendment (2026-09-11): a declaration-only abstract property (inherited from an
      // exported interface, never implemented here) has no export to import. Rendered, not
      // imported: the mirror of the abstract-method arm below.
      renderProperty(prop)
    } else if (prop.isFlow) {
      renderFlowPropertyNativeImports(cls.libraryName, cls.nativePrefix, prop)
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

  renderDispose(
    nativeImport = cls.disposeNativeImport(),
    isAbstract = cls.isAbstract,
    isOpen = cls.isOpen,
    hasSuperClass = cls.superClass != null,
    hasSuspendMethods = cls.hasSuspendMethods,
    ownsScope = cls.ownsScope,
    overridesDisposeAsync = cls.overridesDisposeAsync,
  )

  // ADR-133: nested declarations render last, inside this block, re-indented one level.
  renderNestedDeclarations(cls.nestedDeclarations)

  appendLine("    }")
}

/**
 * ADR-065/ADR-067/ADR-071's flow-property externs: `_collect`, plus StateFlow's `_value`, the
 * nullable member's `_has_value` probe and a declared `MutableStateFlow`'s `_set_value` write.
 *
 * ADR-124 lifted them out of [renderClass], addressed by the two strings a [CirClass] would have
 * supplied, so a sealed subclass mints the identical block for a flow property on an arm. Exactly
 * the lift ADR-111 made for `propertyNativeImports` and ADR-116 for `methodNativeImport`; baked at
 * the ordinary-class depth, so the sealed caller re-indents the whole block.
 */
internal fun StringBuilder.renderFlowPropertyNativeImports(
  libraryName: String,
  nativePrefix: String,
  prop: CirProperty,
) {
  require(prop.isFlow) { "Only a Flow/StateFlow property has collect and value native imports" }
  val collectEntryPoint = "${nativePrefix}_get_${prop.nativeName}_collect"
  appendLine("        [DllImport(\"$libraryName\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"$collectEntryPoint\")]")
  appendLine("        private static extern IntPtr Native_Get${prop.nativeStem}Collect(NugetKotlinHandle handle, NugetKotlinHandle scopeHandle, IntPtr onNext, IntPtr onComplete, IntPtr onError, IntPtr userData);")
  appendLine()
  if (prop.isStateFlow) {
    // ADR-065: synchronous `_value` sibling export -- handle only, no scope/callbacks/errorOut.
    val valueEntryPoint = "${nativePrefix}_get_${prop.nativeName}_value"
    appendLine("        [DllImport(\"$libraryName\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"$valueEntryPoint\")]")
    appendLine("        private static extern IntPtr Native_Get${prop.nativeStem}Value(NugetKotlinHandle handle);")
    appendLine()
    if (prop.isNullableMember) {
      // ADR-067: nullable member -- sibling `_has_value` presence-probe export.
      val hasValueEntryPoint = "${nativePrefix}_get_${prop.nativeName}_has_value"
      appendLine("        [DllImport(\"$libraryName\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"$hasValueEntryPoint\")]")
      appendLine("        [return: MarshalAs(UnmanagedType.I1)]")
      appendLine("        private static extern bool Native_Get${prop.nativeStem}HasValue(NugetKotlinHandle handle);")
      appendLine()
    }
    if (prop.isMutableStateFlow) {
      // ADR-071: sibling `_set_value` export -- handle + the element's own wire type + a
      // trailing `out IntPtr error` (the Kotlin setter can throw, MutableStateFlow.value
      // conflates by Any.equals on the previous value).
      val setValueEntryPoint = "${nativePrefix}_set_${prop.nativeName}_value"
      appendLine("        [DllImport(\"$libraryName\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"$setValueEntryPoint\")]")
      // ADR-098: a MutableStateFlow<Char> setter slot is a `char` slot like any other.
      val setValueParam: String = narrowParameterMarshal(prop.nativeSetterType, "value")
      appendLine("        private static extern void Native_Set${prop.nativeStem}Value(NugetKotlinHandle handle, $setValueParam, out IntPtr error);")
      appendLine()
    }
  }
}

private fun StringBuilder.renderLegacyPropertyNativeImports(cls: CirClass, prop: CirProperty) {
  val getterErrorParam: String = if (prop.hasSyncErrorOut) ", out IntPtr error" else ""
  appendLine("        [DllImport(\"${cls.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"${cls.nativePrefix}_get_${prop.nativeName}\")]")
  narrowReturnMarshal(prop.nativeReturnType)?.let { appendLine(it) }
  appendLine("        private static extern ${prop.nativeReturnType} Native_Get_${prop.nativeName}(NugetKotlinHandle handle$getterErrorParam);")
  appendLine()
}

// Emits the [DllImport] for a constructor's native create entry point plus the
// C# constructor itself. Used for the primary and every secondary (ADR-034).
private fun StringBuilder.renderClassConstructor(cls: CirClass, ctor: CirConstructor) {
  renderConstructorMember(
    libraryName = cls.libraryName,
    nativePrefix = cls.nativePrefix,
    className = cls.name,
    ctor = ctor,
    hasSuperClass = cls.superClass != null,
  )
}

/**
 * ADR-148: the extern plus the constructor, addressed by the strings rather than by a [CirClass],
 * so an ADR-009 sealed arm renders its public constructors through the same two lines an ordinary
 * class does. An arm always passes `hasSuperClass = true`: its handle lives on the generated
 * sealed base, which is exactly why `: base(IntPtr.Zero, out _)` then `_handle = handle;` is the
 * shape it needs.
 */
internal fun StringBuilder.renderConstructorMember(
  libraryName: String,
  nativePrefix: String,
  className: String,
  ctor: CirConstructor,
  hasSuperClass: Boolean,
) {
  renderDllImport(constructorNativeImport(libraryName, nativePrefix, ctor))
  renderConstructor(className, ctor, hasSuperClass)
}

private fun StringBuilder.renderLegacyMethodNativeImport(cls: CirClass, method: CirMethod) {
  val nativeParamList: MutableList<String> = (listOf("NugetKotlinHandle handle") +
      method.parameters.map { narrowParameterMarshal(it.nativeType, it.name) }).toMutableList()
  nativeParamList.addAll(method.extraNativeParams)
  if (method.isSyncErrorCheckEnabled) nativeParamList.add("out IntPtr error")
  val nativeParams: String = nativeParamList.joinToString(", ")
  appendLine("        [DllImport(\"${cls.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"${cls.nativePrefix}_${method.nativeName}\")]")
  narrowReturnMarshal(method.nativeReturnType)?.let { appendLine(it) }
  appendLine("        private static extern ${method.nativeReturnType} Native_${method.name}($nativeParams);")
  appendLine()
}

internal fun StringBuilder.renderConstructor(
  className: String,
  ctor: CirConstructor,
  hasSuperClass: Boolean = false,
) {
  renderDoc(ctor.doc, "        ")
  val paramStr: String = ctor.parameters.joinToString(", ") { it.declaration }
  val paramNames: String = ctor.parameters.joinToString(", ") { it.name }
  val nativeCallArgs: String = if (paramNames.isEmpty()) "out IntPtr error" else "$paramNames, out IntPtr error"

  if (hasSuperClass) {
    appendLine("        public $className($paramStr) : base(IntPtr.Zero, out _)")
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
    appendLine("            _handle = new NugetKotlinHandle(handle);")
    appendLine("        }")
  } else {
    // ROADMAP:29: the body already carries its own leading newline and its own indent, exactly like
    // a method body (`renderMethod`), so it is appended to the brace rather than indented again --
    // the second indent put the first statement at column 24 and left the rest at 12.
    appendLine("        {${ctor.body}")
    appendLine("        }")
  }

  appendLine()
}

internal fun StringBuilder.renderProperty(prop: CirProperty) {
  // ROADMAP line 28, case D: the public property overrides a get-only base property, so it renders
  // get-only (CS0546 otherwise), and the setter the interface declares is implemented explicitly
  // beside it. The `Native_Set_` import still comes off `setter`, so it is emitted either way.
  if (prop.explicitSetterInterfaces.isNotEmpty()) {
    renderProperty(prop.copy(setter = null, explicitSetterInterfaces = emptyList()))
    val setter: String = checkNotNull(prop.setter) {
      "Property ${prop.name} has explicit interface setters but no setter body"
    }
    prop.explicitSetterInterfaces.forEach { iface ->
      appendLine("        ${prop.type} $iface.${prop.identifier}")
      appendLine("        {")
      appendLine("            get => ${prop.identifier};")
      if (setter.contains('\n')) {
        appendLine("            set")
        appendLine("            {$setter")
        appendLine("            }")
      } else {
        appendLine("            set => $setter;")
      }
      appendLine("        }")
      appendLine()
    }
    return
  }
  // ADR-150: above the abstract early return, so both spellings carry the doc. ADR-075's
  // getter/setter pair is one C# property, so it gets one `<summary>`.
  renderDoc(prop.doc, "        ", generated = prop.remarks)
  val static: String = if (prop.isStatic) "static " else ""
  // ADR-075 amendment (2026-09-10): an abstract property is declaration-only, so it takes none of
  // the body-shaped arms below. `isVirtual` is deliberately ignored: `abstract virtual` is CS0503,
  // while `abstract override` (an abstract re-declaration of a base member) is legal C#.
  if (prop.isAbstract) {
    val override: String = if (prop.isOverride) "override " else ""
    val accessors: String = if (prop.setter != null) "{ get; set; }" else "{ get; }"
    appendLine("        public abstract $override${prop.type} ${prop.identifier} $accessors")
    appendLine()
    return
  }
  val modifier: String = if (prop.isOverride) "override " else if (prop.isVirtual) "virtual " else ""
  val isMultiLineGetter: Boolean = prop.getter.contains('\n')
  val isMultiLineSetter: Boolean = prop.setter?.contains('\n') == true
  val head: String = prop.memberHead("public $static$modifier")
  if (isMultiLineGetter && prop.setter != null) {
    appendLine("        $head${prop.type} ${prop.explicitName}")
    appendLine("        {")
    appendLine("            get")
    appendLine("            {${prop.getter}")
    appendLine("            }")
    appendLine("            set")
    appendLine("            {${prop.setter}")
    appendLine("            }")
    appendLine("        }")
  } else if (isMultiLineGetter) {
    appendLine("        $head${prop.type} ${prop.explicitName}")
    appendLine("        {")
    appendLine("            get")
    appendLine("            {${prop.getter}")
    appendLine("            }")
    appendLine("        }")
  } else if (prop.setter == null) {
    appendLine("        $head${prop.type} ${prop.explicitName} => ${prop.getter};")
  } else if (isMultiLineSetter) {
    appendLine("        $head${prop.type} ${prop.explicitName}")
    appendLine("        {")
    appendLine("            get => ${prop.getter};")
    appendLine("            set")
    appendLine("            {${prop.setter}")
    appendLine("            }")
    appendLine("        }")
  } else {
    appendLine("        $head${prop.type} ${prop.explicitName}")
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
    is CirExtensionProperty -> renderExtensionProperty(member)
  }
}

/**
 * ADR-188: one C# 14 `extension(Receiver receiver)` block per Kotlin extension property. The bodies
 * arrive at the extension-method depth (brace at column 8), and an accessor brace here sits at
 * column 16, so each body moves in two levels.
 */
internal fun StringBuilder.renderExtensionProperty(prop: CirExtensionProperty) {
  appendLine("        extension(${prop.receiverType} receiver)")
  appendLine("        {")
  renderDoc(prop.doc, "            ")
  appendLine("            public ${prop.type} ${prop.identifier}")
  appendLine("            {")
  appendLine("                get")
  appendLine("                {${prop.getter.indentNestedBody().indentNestedBody()}")
  appendLine("                }")
  if (prop.setter != null) {
    appendLine("                set")
    appendLine("                {${prop.setter.indentNestedBody().indentNestedBody()}")
    appendLine("                }")
  }
  appendLine("            }")
  appendLine("        }")
  appendLine()
}

internal fun StringBuilder.renderConst(const: CirConst) {
  appendLine("        public const ${const.type} ${const.name} = ${const.value};")
  appendLine()
}

/**
 * The attributed spelling for a narrow `char` or `bool` parameter slot ([nativeType] `"char"`,
 * `"bool"`, `"out bool"`, or `"ref bool"`), or the bare `nativeType name` for everything else.
 *
 * ADR-098: every `char` slot the generator emits carries an explicit UTF-16 width. Kotlin's
 * `KChar` is `unsigned short`; a bare C# `char` marshals as ONE ANSI byte, silently truncating
 * every non-ASCII character on the way in and losing it to U+FFFD on the way out. Same class of
 * bug and same shape of fix as ADR-069's `[MarshalAs(UnmanagedType.I1)]` on `bool`.
 *
 * Applied by native-type text rather than per projection, so that every renderer that mints an
 * extern slot -- the projected [renderDllImport], the legacy method import, the enum-property
 * import -- goes through the same rule and none can mint an unattributed one. Only the by-value
 * `char` shape is matched, and that is now exhaustive: since the ADR-098 amendment gave `Char?`
 * its has-value fan-out, an OUT slot for a character is declared `out ushort` and cast on the C#
 * side (blittable by construction), so no `out char` slot exists anywhere to attribute. A BARE
 * `out char` was measured to narrow every non-ASCII character to one ANSI byte, which is the
 * regression this whole rule exists to prevent.
 *
 * ADR-055 amendment (2026-09-27): the same rule carries ADR-069's `bool`. A bare `bool` slot
 * marshals as the 4-byte Win32 `BOOL` against Kotlin's 1-byte C `bool`, so a by-value `bool` and
 * an `out bool` both gain `[MarshalAs(UnmanagedType.I1)]` here. A `nativeType` that already carries
 * its own attribute (a leading `[`) is left alone, so no slot is attributed twice.
 */
internal fun narrowParameterMarshal(nativeType: String, name: String): String = when (nativeType) {
  "char" -> "[MarshalAs(UnmanagedType.U2)] $nativeType $name"
  "bool", "out bool", "ref bool" -> "[MarshalAs(UnmanagedType.I1)] $nativeType $name"
  else -> "$nativeType $name"
}

/**
 * The `[return: MarshalAs]` line a `char`- or `bool`-returning extern needs, or null. See
 * [narrowParameterMarshal].
 */
internal fun narrowReturnMarshal(returnType: String): String? = when (returnType) {
  "char" -> "        [return: MarshalAs(UnmanagedType.U2)]"
  "bool" -> "        [return: MarshalAs(UnmanagedType.I1)]"
  else -> null
}

internal fun StringBuilder.renderDllImport(import: CirDllImport) {
  val visibility: String = if (import.visibility == CirVisibility.PRIVATE) "private" else "public"
  val entryPoint: String = if (import.entryPoint != null) ", EntryPoint = \"${import.entryPoint}\"" else ""
  // The DllImport signature always speaks the native type, which differs from the public C#
  // type when a cast is needed at the call site (e.g. enum params: public "CatMood", native
  // "int"). CirParameter.nativeType defaults to type, so this is a no-op for every other param.
  val paramList: MutableList<String> =
    import.parameters.map { narrowParameterMarshal(it.nativeType, it.name) }.toMutableList()
  if (import.hasSyncErrorOut) paramList.add("out IntPtr error")
  val paramStr: String = paramList.joinToString(", ")

  appendLine("        [DllImport(\"${import.libraryName}\", CallingConvention = CallingConvention.Cdecl$entryPoint)]")
  narrowReturnMarshal(import.returnType)?.let { appendLine(it) }
  val hides: String = if (import.isNew) "new " else ""
  appendLine(
    "        $visibility ${hides}static extern ${import.returnType} ${import.name}($paramStr);",
  )
  appendLine()
}

internal fun StringBuilder.renderMethod(method: CirMethod, className: String = "") {
  // ADR-150: above the async/flow/sync-error dispatch, so all four branches carry the same doc.
  renderDoc(method.doc, "        ")
  // ADR-174: `nameof(Crate)` does not bind inside `Crate<T>` (CS0305); an explicit implementation
  // names its interface instead, which always does.
  val disposedName: String = method.explicitInterface ?: className
  if (method.isAsync) {
    renderAsyncMethod(method, disposedName)
    return
  }

  if (method.isFlow) {
    renderFlowMethod(method, disposedName)
    return
  }

  if (method.isSyncErrorCheckEnabled && !method.hasCustomBody) {
    renderSyncErrorCheckMethod(method, className)
    return
  }

  val visibility: String = if (method.visibility == CirVisibility.PRIVATE) "private" else "public"
  val static: String = if (method.isStatic) "static " else ""
  val override: String = when {
    method.isOverride -> "override "
    method.isVirtual -> "virtual "
    // ADR-116 amendment (2026-09-11): a sealed arm hiding a base member it cannot override.
    method.isNew -> "new "
    else -> ""
  }
  val abstract: String = if (method.isAbstract) "abstract " else ""
  val paramStr: String = method.parameters.mapIndexed { index, param ->
    if (method.isExtension && index == 0) "this ${param.declaration}"
    else param.declaration
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
    appendLine("        $visibility ${abstract}${method.returnType} ${method.identifier}$genericDecl($paramStr)$whereStr;")
  } else {
    val isMultiLine: Boolean = method.body.contains('\n')

    if (isMultiLine) {
      if (method.returnType == "void") {
        appendLine("        $visibility ${static}${override}void ${method.identifier}$genericDecl($paramStr)$whereStr")
      } else {
        appendLine("        $visibility $static$override${method.returnType} ${method.identifier}$genericDecl($paramStr)$whereStr")
      }
      appendLine("        {${method.body}")
      appendLine("        }")
    } else {
      if (method.returnType == "void") {
        appendLine("        $visibility $static$override void ${method.identifier}$genericDecl($paramStr)$whereStr")
        appendLine("            => ${method.body};")
      } else {
        appendLine("        $visibility $static$override${method.returnType} ${method.identifier}$genericDecl($paramStr)$whereStr")
        appendLine("            => ${method.body};")
      }
    }
  }

  appendLine()
}

internal fun StringBuilder.renderDataClassMethods(cls: CirClass) {
  cls.dataClassNativeImports().forEach { nativeImport -> renderDllImport(nativeImport) }

  if (cls.copyMethod != null) renderMethod(cls.copyMethod, cls.name)

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

/**
 * ADR-118: the scope field, shared by the ordinary-class renderer and the sealed-arm renderer. An
 * arm that declares a `suspend fun` owns its own scope (its `Native_Dispose` is per arm, so the
 * sealed base cannot own one), which is why this is lifted rather than inlined twice.
 */
internal fun StringBuilder.renderScopeHandleField() {
  appendLine("        internal NugetScopeHandle? _scopeHandle;")
}

/**
 * ADR-118: the lazy scope every async body calls, shared by ordinary classes and sealed arms.
 *
 * ADR-159: `internal`, matching `_scopeHandle`. `private` was invisible to a subclass whose own
 * async bodies call it unqualified (CS0122), which is every class below the scope owner.
 *
 * ADR-187: the scope is an owned [NugetScopeHandle], so the import slot it is passed to keeps it
 * alive for the call, and an undisposed wrapper's scope is cancelled and released by the GC.
 */
internal fun StringBuilder.renderGetOrCreateScope() {
  appendLine("        internal NugetScopeHandle GetOrCreateScope()")
  appendLine("        {")
  appendLine("            NugetScopeHandle? existing = _scopeHandle;")
  appendLine("            if (existing != null) return existing;")
  appendLine("            var created = new NugetScopeHandle(NugetScopeNative.Create());")
  appendLine("            NugetScopeHandle? prior = Interlocked.CompareExchange(ref _scopeHandle, created, null);")
  appendLine("            if (prior != null)")
  appendLine("            {")
  appendLine("                created.DisposeWithoutCancel();")
  appendLine("                return prior;")
  appendLine("            }")
  appendLine("            return created;")
  appendLine("        }")
}

internal fun StringBuilder.renderDispose(
  nativeImport: CirDllImport?,
  isAbstract: Boolean = false,
  isOpen: Boolean = false,
  hasSuperClass: Boolean = false,
  hasSuspendMethods: Boolean = false,
  // ADR-159: `hasSuspendMethods` is "a scope exists on this instance" and drives the cleanup
  // block in `Dispose()` at every level; `ownsScope` is "this class declares it" and drives
  // `DisposeAsync`. A sealed arm owns whatever scope it has, so the default keeps
  // `CirSealedRenderer` intact.
  ownsScope: Boolean = hasSuspendMethods,
  overridesDisposeAsync: Boolean = false,
) {
  val abstract: String = if (isAbstract) "abstract " else ""
  // ADR-101 amendment (2026-09-10): a derived class always spells its Dispose `override`, so a
  // base has to be overridable or the subclass is CS0506. An abstract base already is (it renders
  // `abstract void Dispose();`); a concrete `open class` needs `virtual` said out loud. A final
  // class keeps the shipped bare `public void Dispose()`.
  val override: String = if (hasSuperClass) "override " else if (isOpen) "virtual " else ""

  if (isAbstract) {
    // An abstract class below an exported base re-abstracts the inherited slot; a bare `abstract`
    // hides it, and the concrete subclass then cannot implement both (CS0533/CS0114/CS0534).
    val abstractOverride: String = if (hasSuperClass) "override " else ""
    appendLine("        public ${abstract}${abstractOverride}void Dispose();")
    // ADR-159: an abstract scope owner can only DECLARE the drain -- it has no `Native_Dispose`
    // import to call -- so `DisposeAsync` follows `Dispose`'s spelling and each concrete class
    // below renders the body as an `override`. Without this the abstract class advertised
    // `IAsyncDisposable` and implemented nothing (CS0535).
    if (ownsScope) appendLine("        public ${abstract}ValueTask DisposeAsync();")
  } else {
    renderDllImport(requireNotNull(nativeImport) { "Concrete disposable classes require a native import" })
    // ADR-187: the owned handle is swapped for the zero sentinel, so a second `Dispose()` and every
    // disposed check read zero exactly as the raw field did, and the handle object is released once.
    // `Native_Dispose` stays imported for the ABI contract; the shared release is `nuget_dispose`.
    appendLine("        public ${override}void Dispose()")
    appendLine("        {")
    appendLine("            $TAKE_HANDLE")
    appendLine("            if (handle.IsInvalid) return;")
    if (hasSuspendMethods) {
      appendLine("            Interlocked.Exchange(ref _scopeHandle, null)?.Dispose();")
    }
    appendLine("            handle.Dispose();")
    appendLine("        }")
    // ADR-159: the drain is declared once per chain, by the owner, and inherited below (every
    // class's `_dispose` export is `NugetHandles.release`, so the owner's `Native_Dispose` is
    // correct for a derived instance's handle). The one exception is an abstract owner, whose
    // declaration each concrete class overrides with the body.
    if (ownsScope || overridesDisposeAsync) {
      val disposeAsyncModifier: String = if (overridesDisposeAsync) "override " else ""
      appendLine()
      appendLine("        public ${disposeAsyncModifier}ValueTask DisposeAsync()")
      appendLine("        {")
      appendLine("            $TAKE_HANDLE")
      appendLine("            if (handle.IsInvalid) return ValueTask.CompletedTask;")
      appendLine("            NugetScopeHandle? scopeHandle = Interlocked.Exchange(ref _scopeHandle, null);")
      appendLine("            if (scopeHandle == null)")
      appendLine("            {")
      appendLine("                handle.Dispose();")
      appendLine("                return ValueTask.CompletedTask;")
      appendLine("            }")
      appendLine("            return new ValueTask(DrainAndDisposeAsync(handle, scopeHandle));")
      appendLine("        }")
      appendLine()
      // ADR-187: the wrapper is already reading as disposed; the closure holds the two handle
      // objects it took, so neither can be finalized before the drain completes, and it releases
      // them through the same objects, so nothing is released twice.
      appendLine("        private Task DrainAndDisposeAsync(NugetKotlinHandle handle, NugetScopeHandle scopeHandle)")
      appendLine("        {")
      appendLine("            var tcs = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);")
      appendLine("            NugetAsyncCallback callback = null!;")
      appendLine("            GCHandle callbackHandle = default;")
      // ADR-025 amendment: the drain job is launched `ATOMIC` and an idle scope completes it
      // before `Drain` has returned the handle, the same ADR-019 window `NugetJobCell` closes for
      // the suspend call sites. The callback used to dispose a still-zero local and leak the job.
      appendLine("            var job = new NugetJobCell();")
      // ADR-161: the same containment as every other completion closure. The two disposals are
      // inside it (a failing dispose must fault the returned ValueTask, not the process) and the
      // drain callback has no error arm to contain.
      appendAsyncCompletionClosure(
        "TaskCompletionSource<bool>",
        "t.SetResult(true);",
        cancellationArgument = "",
        prelude = listOf("scopeHandle.DisposeWithoutCancel();", "handle.Dispose();"),
        includesErrorBranch = false,
      )
      appendLine("            callbackHandle = GCHandle.Alloc(callback);")
      appendLine(
        "            IntPtr drainJobHandle = NugetScopeNative.Drain(scopeHandle, " +
            "NugetThunks.NugetAsyncCallbackPtr, GCHandle.ToIntPtr(callbackHandle));"
      )
      appendLine("            job.PublishFromCaller(drainJobHandle, default);")
      appendLine("            return tcs.Task;")
      appendLine("        }")
    }
  }
}

private fun StringBuilder.renderStoredCallbackMethod(method: CirStoredCallbackMethod) {
  appendLine("        [DllImport(\"${method.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"${method.subscribeEntryPoint}\")]")
  appendLine("        private static extern IntPtr ${method.csAddNativeName}(NugetKotlinHandle handle, IntPtr listenerPtr, IntPtr userData, out IntPtr error);")
  appendLine()
  appendLine("        [DllImport(\"${method.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"${method.removeEntryPoint}\")]")
  appendLine("        private static extern void ${method.csRemoveNativeName}(IntPtr handle, IntPtr subscriptionHandle);")
  appendLine()
  appendLine("        public IDisposable ${method.identifier}(${method.csParamType} listener)")
  appendLine("        {")
  // Unconditional, whatever the Kotlin nullability: the pair never forwards the C# argument (the
  // export builds its own non-null bridge), so a null here would subscribe a live listener that
  // throws on the first emission. Argument validation precedes receiver state, and both precede
  // `RegisterCtx`, so a rejected call mints no ctx key and no Kotlin handle.
  appendLine("            ArgumentNullException.ThrowIfNull(listener);")
  appendLine(
    "            if (_handle.IsInvalid) " +
        "throw new ObjectDisposedException(nameof(${method.className}));"
  )
  appendLine("            ${method.delegateName} nativeCallback = ${method.delegateParamList} => { ${method.nativeCallbackBody} };")
  // ADR-161 part C: the ctx is a never-reused table key, not a GCHandle. A Kotlin emission that
  // lands after `Dispose()` removed the key is a lookup miss the thunk drops, where a freed
  // GCHandle would have resolved to whatever the next allocation put in its slot.
  appendLine("            IntPtr cbKey = NugetThunks.RegisterCtx(nativeCallback);")
  // ADR-102: the AOT-compiled thunk address plus this delegate's own key as the echoed ctx.
  appendLine(
    "            IntPtr sub = ${method.csAddNativeName}(_handle, " +
        "NugetThunks.${method.delegateName}Ptr, cbKey, out IntPtr error);"
  )
  // The un-registration on the failure path is also the fix for a pre-existing leak: the old code
  // never freed `cbHandle` when subscribe reported an error, so the delegate stayed rooted forever.
  appendLine(
    "            if (error != IntPtr.Zero) { NugetThunks.UnregisterCtx(cbKey); " +
        "throw NugetErrorNative.BuildException(error); }"
  )
  // ADR-187 (gate, 2026-10-02): the token is NOT finalizer-released. A discarded subscription keeps
  // delivering; only an explicit `Dispose()` unregisters. The remove export ignores the receiver,
  // which is passed raw so a subscription disposed after its owner still unregisters.
  appendLine(
    "            return new NugetSubscription(() => { " +
        "${method.csRemoveNativeName}(_handle.DangerousGetHandle(), sub); NugetThunks.UnregisterCtx(cbKey); });"
  )
  appendLine("        }")
  appendLine()
}

private fun StringBuilder.renderInterfaceBridgeMethod(method: CirInterfaceBridgeMethod) {
  // DllImport for subscribe: handle + per-method (fnPtr, ctx) pairs + error
  val nativeAddParams: String = buildString {
    append("NugetKotlinHandle handle")
    method.entries.forEach { entry ->
      append(", IntPtr ${entry.methodKtName}Ptr, IntPtr ${entry.methodKtName}Ctx")
    }
    append(", out IntPtr error")
  }
  appendLine(
    "        [DllImport(\"${method.libraryName}\", CallingConvention = CallingConvention.Cdecl, " +
        "EntryPoint = \"${method.subscribeEntryPoint}\")]"
  )
  appendLine("        private static extern IntPtr ${method.csAddNativeName}($nativeAddParams);")
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
  appendLine("        public IDisposable ${method.identifier}(${method.interfaceCsName} listener)")
  appendLine("        {")
  // Same guard as the lambda pair, ahead of the disposed check: the argument is named first.
  appendLine("            ArgumentNullException.ThrowIfNull(listener);")
  appendLine(
    "            if (_handle.IsInvalid) throw new ObjectDisposedException(nameof(${method.className}));"
  )

  // Delegate assignments
  method.entries.forEach { entry ->
    appendLine(
      "            ${entry.delegateName} ${entry.methodKtName}Cb = " +
          "${entry.delegateParamList} => { ${entry.callbackBody} };"
    )
  }

  // ADR-161 part C: one never-reused table key per listener method, in place of one GCHandle each.
  method.entries.forEachIndexed { i, entry ->
    appendLine("            IntPtr k$i = NugetThunks.RegisterCtx(${entry.methodKtName}Cb);")
  }

  // Native subscribe call. ADR-102: thunk address + this slot's delegate key as the ctx.
  val nativeCallArgs: String = buildString {
    append("_handle")
    method.entries.forEachIndexed { i, _ ->
      append(", NugetThunks.${method.entries[i].delegateName}Ptr, k$i")
    }
  }
  appendLine("            IntPtr sub = ${method.csAddNativeName}($nativeCallArgs, out IntPtr error);")

  // Error check with key removal
  val freeHandles: String =
    method.entries.indices.joinToString(" ") { "NugetThunks.UnregisterCtx(k$it);" }
  appendLine("            if (error != IntPtr.Zero) { $freeHandles throw NugetErrorNative.BuildException(error); }")

  // Return NugetSubscription
  appendLine(
    "            return new NugetSubscription(() => { ${method.csRemoveNativeName}(_handle.DangerousGetHandle(), sub); $freeHandles });"
  )
  appendLine("        }")
  appendLine()
}

private fun StringBuilder.renderCallbackMethod(method: CirCallbackMethod) {
  appendLine("        [DllImport(\"${method.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"${method.nativeEntryPoint}\")]")
  narrowReturnMarshal(method.nativeImportReturnType)?.let { appendLine(it) }
  appendLine("        private static extern ${method.nativeImportReturnType} ${method.csNativeName}(NugetKotlinHandle handle, IntPtr ${method.lambdaParamName}Ptr, IntPtr userData, out IntPtr error);")
  appendLine()
  appendLine("        public ${method.csReturnType} ${method.identifier}(${method.csParamType} ${method.lambdaParamName})")
  appendLine("        {")
  // Boundary nullability part A2, now unconditional: ahead of `RegisterCtx`, which accepts null
  // happily and defers the failure to the managed callback body, where the null delegate is
  // invoked and the resulting `NullReferenceException` crosses back as a Kotlin error that never
  // names the argument. Kotlin nullability does not matter: C# has no "no listener" spelling here.
  appendLine("            ArgumentNullException.ThrowIfNull(${method.lambdaParamName});")
  appendLine("            ${method.delegateName} nativeCallback = ${method.delegateParamList} =>")
  appendLine("            {")
  appendLine(method.callbackBody)
  appendLine("            };")
  // ADR-161 part C: a never-reused key, so a lambda that escaped the call (a Kotlin author bug)
  // is a lookup miss reported as ObjectDisposedException, not a read of a reused GCHandle slot.
  appendLine("            IntPtr cbKey = NugetThunks.RegisterCtx(nativeCallback);")
  appendLine("            try")
  appendLine("            {")
  appendLine(method.wrapperBody)
  appendLine("            }")
  appendLine("            finally")
  appendLine("            {")
  appendLine("                NugetThunks.UnregisterCtx(cbKey);")
  appendLine("            }")
  appendLine("        }")
  appendLine()
}


/**
 * ADR-187: takes a wrapper's owned handle out of its field, leaving the zero sentinel behind. Every
 * release path starts here, so a handle object is released exactly once.
 */
internal const val TAKE_HANDLE: String =
  "NugetKotlinHandle handle = Interlocked.Exchange(ref _handle, NugetKotlinHandle.Null);"
