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
  appendLine("    public interface ${iface.name}$typeParamStr : IDisposable")
  appendLine("    {")

  for (prop in iface.properties) {
    renderDoc(prop.doc, "        ")
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
    renderDoc(method.doc, "        ")
    val paramStr: String = method.parameters.joinToString(", ") { "${it.type} ${it.name}" }
    appendLine("        ${method.returnType} ${method.name}($paramStr);")
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
  if (cls.typeParameters.isEmpty()) {
    renderClassDeclaration(cls)
    return
  }
  val body: String = StringBuilder().apply { renderClassDeclaration(cls) }.toString()
  val hoisted: HoistedDllImports = hoistDllImports(body, "${cls.name}Native")
  if (hoisted.imports.isNotEmpty()) {
    appendLine("    internal static class ${cls.name}Native")
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
  // ADR-159: `IAsyncDisposable` rides on scope OWNERSHIP, not on base-lessness. A derived class that
  // projects the chain's first async member declares the scope and the drain, so it has to advertise
  // them; a class below the owner inherits the interface with the body and must not re-list it.
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
    appendLine("        internal IntPtr _handle;")
    if (cls.ownsScope) renderScopeHandleField()
    appendLine()
    appendLine("        IntPtr INugetHandle.Handle => _handle;")
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
  appendLine("        private static extern IntPtr Native_Get${prop.name}Collect(IntPtr handle, IntPtr scopeHandle, IntPtr onNext, IntPtr onComplete, IntPtr onError, IntPtr userData);")
  appendLine()
  if (prop.isStateFlow) {
    // ADR-065: synchronous `_value` sibling export -- handle only, no scope/callbacks/errorOut.
    val valueEntryPoint = "${nativePrefix}_get_${prop.nativeName}_value"
    appendLine("        [DllImport(\"$libraryName\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"$valueEntryPoint\")]")
    appendLine("        private static extern IntPtr Native_Get${prop.name}Value(IntPtr handle);")
    appendLine()
    if (prop.isNullableMember) {
      // ADR-067: nullable member -- sibling `_has_value` presence-probe export.
      val hasValueEntryPoint = "${nativePrefix}_get_${prop.nativeName}_has_value"
      appendLine("        [DllImport(\"$libraryName\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"$hasValueEntryPoint\")]")
      appendLine("        [return: MarshalAs(UnmanagedType.I1)]")
      appendLine("        private static extern bool Native_Get${prop.name}HasValue(IntPtr handle);")
      appendLine()
    }
    if (prop.isMutableStateFlow) {
      // ADR-071: sibling `_set_value` export -- handle + the element's own wire type + a
      // trailing `out IntPtr error` (the Kotlin setter can throw, MutableStateFlow.value
      // conflates by Any.equals on the previous value).
      val setValueEntryPoint = "${nativePrefix}_set_${prop.nativeName}_value"
      appendLine("        [DllImport(\"$libraryName\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"$setValueEntryPoint\")]")
      // ADR-098: a MutableStateFlow<Char> setter slot is a `char` slot like any other.
      val setValueParam: String = charParameterMarshal(prop.nativeSetterType, "value")
      appendLine("        private static extern void Native_Set${prop.name}Value(IntPtr handle, $setValueParam, out IntPtr error);")
      appendLine()
    }
  }
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
  renderConstructorMember(
    libraryName = cls.libraryName,
    nativePrefix = cls.nativePrefix,
    className = cls.name,
    ctor = ctor,
    hasSuperClass = cls.superClass != null,
    hasSuspendMethods = cls.hasSuspendMethods,
  )
}

/**
 * ADR-148: the extern plus the constructor, addressed by the strings rather than by a [CirClass],
 * so an ADR-009 sealed arm renders its public constructors through the same two lines an ordinary
 * class does. An arm always passes `hasSuperClass = true`: its handle lives on the generated
 * sealed base, which is exactly why `: base(IntPtr.Zero)` then `_handle = handle;` is the shape it
 * needs.
 */
internal fun StringBuilder.renderConstructorMember(
  libraryName: String,
  nativePrefix: String,
  className: String,
  ctor: CirConstructor,
  hasSuperClass: Boolean,
  hasSuspendMethods: Boolean = false,
) {
  renderDllImport(constructorNativeImport(libraryName, nativePrefix, ctor))
  renderConstructor(className, ctor, hasSuperClass, hasSuspendMethods)
}

private fun StringBuilder.renderLegacyMethodNativeImport(cls: CirClass, method: CirMethod) {
  val nativeParamList: MutableList<String> = (listOf("IntPtr handle") +
      method.parameters.map { charParameterMarshal(it.nativeType, it.name) }).toMutableList()
  nativeParamList.addAll(method.extraNativeParams)
  if (method.isSyncErrorCheckEnabled) nativeParamList.add("out IntPtr error")
  val nativeParams: String = nativeParamList.joinToString(", ")
  appendLine("        [DllImport(\"${cls.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"${cls.nativePrefix}_${method.nativeName}\")]")
  charReturnMarshal(method.nativeReturnType)?.let { appendLine(it) }
  appendLine("        private static extern ${method.nativeReturnType} Native_${method.name}($nativeParams);")
  appendLine()
}

internal fun StringBuilder.renderConstructor(
  className: String,
  ctor: CirConstructor,
  hasSuperClass: Boolean = false,
  hasSuspendMethods: Boolean = false,
) {
  renderDoc(ctor.doc, "        ")
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
    // ROADMAP:29: the body already carries its own leading newline and its own indent, exactly like
    // a method body (`renderMethod`), so it is appended to the brace rather than indented again --
    // the second indent put the first statement at column 24 and left the rest at 12.
    appendLine("        {${ctor.body}")
    appendLine("        }")
  }

  appendLine()
}

internal fun StringBuilder.renderProperty(prop: CirProperty) {
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
    appendLine("        public abstract $override${prop.type} ${prop.name} $accessors")
    appendLine()
    return
  }
  val modifier: String = if (prop.isOverride) "override " else if (prop.isVirtual) "virtual " else ""
  val isMultiLineGetter: Boolean = prop.getter.contains('\n')
  val isMultiLineSetter: Boolean = prop.setter?.contains('\n') == true
  if (isMultiLineGetter && prop.setter != null) {
    appendLine("        public ${static}${modifier}${prop.type} ${prop.name}")
    appendLine("        {")
    appendLine("            get")
    appendLine("            {${prop.getter}")
    appendLine("            }")
    appendLine("            set")
    appendLine("            {${prop.setter}")
    appendLine("            }")
    appendLine("        }")
  } else if (isMultiLineGetter) {
    appendLine("        public ${static}${modifier}${prop.type} ${prop.name}")
    appendLine("        {")
    appendLine("            get")
    appendLine("            {${prop.getter}")
    appendLine("            }")
    appendLine("        }")
  } else if (prop.setter == null) {
    appendLine("        public ${static}${modifier}${prop.type} ${prop.name} => ${prop.getter};")
  } else if (isMultiLineSetter) {
    appendLine("        public ${static}${modifier}${prop.type} ${prop.name}")
    appendLine("        {")
    appendLine("            get => ${prop.getter};")
    appendLine("            set")
    appendLine("            {${prop.setter}")
    appendLine("            }")
    appendLine("        }")
  } else {
    appendLine("        public ${static}${modifier}${prop.type} ${prop.name}")
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

/**
 * ADR-098: every `char` slot the generator emits carries an explicit UTF-16 width. Kotlin's
 * `KChar` is `unsigned short`; a bare C# `char` marshals as ONE ANSI byte, silently truncating
 * every non-ASCII character on the way in and losing it to U+FFFD on the way out. Same class of
 * bug and same shape of fix as ADR-069's `[MarshalAs(UnmanagedType.I1)]` on `bool`.
 *
 * Applied by native-type text rather than per projection, so that every renderer that mints an
 * extern slot -- the projected [renderDllImport], the legacy method import, the enum-property
 * import -- goes through the same rule and none can mint an unattributed one. An `out char` slot
 * has no route yet (a nullable `Char` still has no has-value fan-out), so only the by-value shape
 * is matched.
 */
internal fun charParameterMarshal(nativeType: String, name: String): String =
  if (nativeType == "char") "[MarshalAs(UnmanagedType.U2)] $nativeType $name"
  else "$nativeType $name"

/**
 * The `[return: MarshalAs]` line a `char`-returning extern needs, or null. See
 * [charParameterMarshal].
 */
internal fun charReturnMarshal(returnType: String): String? =
  if (returnType == "char") "        [return: MarshalAs(UnmanagedType.U2)]" else null

internal fun StringBuilder.renderDllImport(import: CirDllImport) {
  val visibility: String = if (import.visibility == CirVisibility.PRIVATE) "private" else "public"
  val entryPoint: String = if (import.entryPoint != null) ", EntryPoint = \"${import.entryPoint}\"" else ""
  // The DllImport signature always speaks the native type, which differs from the public C#
  // type when a cast is needed at the call site (e.g. enum params: public "CatMood", native
  // "int"). CirParameter.nativeType defaults to type, so this is a no-op for every other param.
  val paramList: MutableList<String> =
    import.parameters.map { charParameterMarshal(it.nativeType, it.name) }.toMutableList()
  if (import.hasSyncErrorOut) paramList.add("out IntPtr error")
  val paramStr: String = paramList.joinToString(", ")

  appendLine("        [DllImport(\"${import.libraryName}\", CallingConvention = CallingConvention.Cdecl$entryPoint)]")
  if (import.marshalBooleanReturn) appendLine("        [return: MarshalAs(UnmanagedType.I1)]")
  charReturnMarshal(import.returnType)?.let { appendLine(it) }
  val hides: String = if (import.isNew) "new " else ""
  appendLine(
    "        $visibility ${hides}static extern ${import.returnType} ${import.name}($paramStr);",
  )
  appendLine()
}

internal fun StringBuilder.renderMethod(method: CirMethod, className: String = "") {
  // ADR-150: above the async/flow/sync-error dispatch, so all four branches carry the same doc.
  renderDoc(method.doc, "        ")
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
  val override: String = when {
    method.isOverride -> "override "
    method.isVirtual -> "virtual "
    // ADR-116 amendment (2026-09-11): a sealed arm hiding a base member it cannot override.
    method.isNew -> "new "
    else -> ""
  }
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

/**
 * ADR-118: the scope field, shared by the ordinary-class renderer and the sealed-arm renderer. An
 * arm that declares a `suspend fun` owns its own scope (its `Native_Dispose` is per arm, so the
 * sealed base cannot own one), which is why this is lifted rather than inlined twice.
 */
internal fun StringBuilder.renderScopeHandleField() {
  appendLine("        internal IntPtr _scopeHandle;")
}

/**
 * ADR-118: the lazy scope every async body calls, shared by ordinary classes and sealed arms.
 *
 * ADR-159: `internal`, matching `_scopeHandle`. `private` was invisible to a subclass whose own
 * async bodies call it unqualified (CS0122), which is every class below the scope owner.
 */
internal fun StringBuilder.renderGetOrCreateScope() {
  appendLine("        internal IntPtr GetOrCreateScope()")
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
}

internal fun StringBuilder.renderDispose(
  nativeImport: CirDllImport?,
  isAbstract: Boolean = false,
  isOpen: Boolean = false,
  hasSuperClass: Boolean = false,
  hasSuspendMethods: Boolean = false,
  // ADR-159: `hasSuspendMethods` is "a scope exists on this instance" and drives the cleanup block
  // in `Dispose()` at every level; `ownsScope` is "this class declares it" and drives `DisposeAsync`.
  // A sealed arm owns whatever scope it has, so the default keeps `CirSealedRenderer` intact.
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
    appendLine("        public ${abstract}void Dispose();")
    // ADR-159: an abstract scope owner can only DECLARE the drain -- it has no `Native_Dispose`
    // import to call -- so `DisposeAsync` follows `Dispose`'s spelling and each concrete class below
    // renders the body as an `override`. Without this the abstract class advertised
    // `IAsyncDisposable` and implemented nothing (CS0535).
    if (ownsScope) appendLine("        public ${abstract}ValueTask DisposeAsync();")
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
    // ADR-159: the drain is declared once per chain, by the owner, and inherited below (every
    // class's `_dispose` export is `NugetHandles.release`, so the owner's `Native_Dispose` is
    // correct for a derived instance's handle). The one exception is an abstract owner, whose
    // declaration each concrete class overrides with the body.
    if (ownsScope || overridesDisposeAsync) {
      val disposeAsyncModifier: String = if (overridesDisposeAsync) "override " else ""
      appendLine()
      appendLine("        public ${disposeAsyncModifier}ValueTask DisposeAsync()")
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
      appendLine("            NugetAsyncCallback callback = null!;")
      appendLine("            GCHandle callbackHandle = default;")
      // ADR-025 amendment: the drain job is launched `ATOMIC` and an idle scope completes it
      // before `Drain` has returned the handle, the same ADR-019 window `NugetJobCell` closes for
      // the suspend call sites. The callback used to dispose a still-zero local and leak the job.
      appendLine("            var job = new NugetJobCell();")
      appendLine("            callback = (resultPtr, errorPtr, isCancelled, userData) =>")
      appendLine("            {")
      appendLine("                job.CompleteFromCallback();")
      appendLine("                callbackHandle.Free();")
      appendLine("                TaskCompletionSource<bool> t = tcs;")
      appendLine("                NugetScopeNative.Dispose(scopeHandle);")
      appendLine("                Native_Dispose(handle);")
      appendLine("                if (isCancelled != 0)")
      appendLine("                    t.TrySetCanceled();")
      appendLine("                else")
      appendLine("                    t.SetResult(true);")
      appendLine("            };")
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
  appendLine("        private static extern IntPtr Native_${method.csMethodName}(IntPtr handle, IntPtr listenerPtr, IntPtr userData, out IntPtr error);")
  appendLine()
  appendLine("        [DllImport(\"${method.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"${method.removeEntryPoint}\")]")
  appendLine("        private static extern void ${method.csRemoveNativeName}(IntPtr handle, IntPtr subscriptionHandle);")
  appendLine()
  appendLine("        public IDisposable ${method.csMethodName}(${method.csParamType} listener)")
  appendLine("        {")
  appendLine("            ${method.delegateName} nativeCallback = ${method.delegateParamList} => { ${method.nativeCallbackBody} };")
  appendLine("            GCHandle cbHandle = GCHandle.Alloc(nativeCallback);")
  // ADR-102: the AOT-compiled thunk address plus this delegate's own handle as the echoed ctx.
  appendLine(
    "            IntPtr sub = Native_${method.csMethodName}(_handle, " +
        "NugetThunks.${method.delegateName}Ptr, GCHandle.ToIntPtr(cbHandle), out IntPtr error);"
  )
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

  // Native subscribe call. ADR-102: thunk address + this slot's delegate handle as the ctx.
  val nativeCallArgs: String = buildString {
    append("_handle")
    method.entries.forEachIndexed { i, entry ->
      append(", NugetThunks.${entry.delegateName}Ptr, GCHandle.ToIntPtr(h$i)")
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

