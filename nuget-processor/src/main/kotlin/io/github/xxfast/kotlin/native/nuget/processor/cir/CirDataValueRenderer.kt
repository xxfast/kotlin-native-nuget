package io.github.xxfast.kotlin.native.nuget.processor.cir

/**
 * ADR-242: a scalar data class rendered as a C# value. Set on [CirClass.dataValue] by the class
 * translator for exactly the classes the forward classifier admits as `BridgeType.DataValue`.
 *
 * @param components one per primary-constructor component, in declaration order, zipped by index
 *   with the primary [CirClass.constructor]'s public parameters.
 */
internal data class CirDataValue(val components: List<CirDataValueComponent>)

/**
 * @param propertyName the component's C# property name, the [CirProperty.name] whose getter extern
 *   reads it back off a handle.
 * @param defensiveCopy the public-constructor read of the argument, `{0}` standing for it: a
 *   collection is copied so the value cannot change under the caller, everything else is `{0}`.
 */
internal data class CirDataValueComponent(
  val propertyName: String,
  val defensiveCopy: String = "{0}",
)

private const val MINT_RECEIVER: String = "using NugetKotlinHandle _handle = NugetMint();"

private const val HANDLE_ASSIGNMENT: String = "_handle = new NugetKotlinHandle(handle);"

/**
 * ADR-242: `public sealed class X : IEquatable<X>`, no handle field, no `IDisposable`. The
 * components are get-only properties filled once, by the public constructor from its arguments
 * or by the internal handle constructor from the getter externs (then the handle is released).
 * Every Kotlin member mints a transient instance through `NugetCreate`, the create export the
 * public constructor calls, and releases it before returning: the minted local is named `_handle`,
 * so each planned body, which reads the receiver as `_handle`, renders unchanged inside it.
 */
internal fun StringBuilder.renderDataValueClass(
  cls: CirClass,
  value: CirDataValue,
  externs: CirExternScope,
  enclosing: List<CirEnclosingExterns>,
) {
  val ctor: CirConstructor = checkNotNull(cls.constructor) {
    "ADR-242: data value ${cls.name} has no planned primary constructor"
  }
  check(ctor.parameters.size == value.components.size) {
    "ADR-242: data value ${cls.name} has ${value.components.size} components but its " +
        "constructor takes ${ctor.parameters.size} parameters"
  }
  val components: Map<String, CirProperty> = cls.properties
    .filter { prop -> prop.name in value.components.map { it.propertyName } }
    .associateBy { it.name }
  value.components.forEach { component ->
    checkNotNull(components[component.propertyName]) {
      "ADR-242: data value ${cls.name} has no getter for component ${component.propertyName}"
    }
  }
  val name: String = cls.name

  renderDoc(cls.doc, generated = cls.remarks)
  val bases: String = (listOf("IEquatable<$name>") + cls.interfaces + "INugetGenericValue")
    .distinct().joinToString(", ")
  appendLine("    public sealed class $name : $bases")
  appendLine("    {")

  // The create export, behind the public constructor, the mint, and the erased-position box.
  renderDllImport(constructorNativeImport(cls.libraryName, cls.nativePrefix, ctor), externs)
  val declarations: String = ctor.parameters.joinToString(", ") { it.declaration }
  val names: String = ctor.parameters.joinToString(", ") { it.name }
  val componentReads: String =
    value.components.joinToString(", ") { components.getValue(it.propertyName).identifierOf() }
  renderDoc(ctor.doc, "        ")
  appendLine("        public $name($declarations)")
  appendLine("        {")
  // Kotlin's constructor and `init` run here; the instance is released at once.
  appendLine("            new NugetKotlinHandle(NugetCreate${ctor.nativeSuffix}($names)).Dispose();")
  value.components.zip(ctor.parameters).forEach { (component, parameter) ->
    val target: String = components.getValue(component.propertyName).identifierOf()
    appendLine("            $target = ${component.defensiveCopy.replace("{0}", parameter.name)};")
  }
  appendLine("        }")
  appendLine()
  renderCreate(ctor, declarations)

  // A secondary constructor reads its components back from the instance Kotlin built.
  cls.secondaryConstructors.forEach { secondary ->
    renderDllImport(constructorNativeImport(cls.libraryName, cls.nativePrefix, secondary), externs)
    val secondaryNames: String = secondary.parameters.joinToString(", ") { it.name }
    renderDoc(secondary.doc, "        ")
    appendLine(
      "        public $name(${secondary.parameters.joinToString(", ") { it.declaration }}) : " +
          "this(new NugetKotlinHandle(NugetCreate${secondary.nativeSuffix}($secondaryNames)), out _)",
    )
    appendLine("        {")
    appendLine("        }")
    appendLine()
    renderCreate(secondary, secondary.parameters.joinToString(", ") { it.declaration })
  }

  // Reading a handle Kotlin handed out: copy every component, then release it.
  appendLine(
    "        internal $name(IntPtr handle, out NugetHandleTag tag) : " +
        "this(new NugetKotlinHandle(handle), out tag)",
  )
  appendLine("        {")
  appendLine("        }")
  appendLine()
  appendLine("        internal $name(NugetKotlinHandle handle, out NugetHandleTag tag)")
  appendLine("        {")
  appendLine("            tag = default;")
  appendLine("            using (handle)")
  appendLine("            {")
  value.components.forEach { component ->
    val prop: CirProperty = components.getValue(component.propertyName)
    appendLine("                ${prop.identifierOf()} = NugetRead${prop.name}(handle);")
  }
  appendLine("            }")
  appendLine("        }")
  appendLine()

  // A fresh Kotlin instance over these components, owned by the caller.
  appendLine(
    "        internal NugetKotlinHandle NugetMint() => " +
        "new NugetKotlinHandle(NugetCreate${ctor.nativeSuffix}($componentReads));",
  )
  appendLine()
  // An erased position (`Wrap<T>`, `object`) takes the owned-mint branch, never `INugetHandle`.
  appendLine("        IntPtr INugetGenericValue.NugetBox() => NugetCreate${ctor.nativeSuffix}($componentReads);")
  appendLine()

  // Components: plain get-only properties, each with its getter extern behind the handle read.
  value.components.forEach { component ->
    val prop: CirProperty = components.getValue(component.propertyName)
    cls.propertyNativeImports(prop).forEach { nativeImport -> renderDllImport(nativeImport, externs) }
    renderDoc(prop.doc, "        ", generated = prop.remarks)
    appendLine("        public ${prop.type} ${prop.explicitName} { get; }")
    appendLine()
    appendLine("        private static ${prop.type} NugetRead${prop.name}(NugetKotlinHandle _handle)")
    val read: String =
      if (prop.getter.contains('\n')) prop.getter else "\n            return ${prop.getter};"
    appendLine("        {$read")
    appendLine("        }")
    appendLine()
  }

  // Computed members: Kotlin's code on every call, over a transient instance.
  cls.properties.filter { it.name !in components }.forEach { prop ->
    cls.propertyNativeImports(prop).forEach { nativeImport -> renderDllImport(nativeImport, externs) }
    renderProperty(if (prop.isStatic) prop else prop.withMintedReceiver())
  }
  cls.methods.forEach { method ->
    if (!method.isAbstract) renderDllImport(cls.methodNativeImport(method), externs)
    append(buildString { renderMethod(method, name) }.let { if (method.isStatic) it else it.withMintedReceiverBody() })
  }
  cls.callbackMethods.forEach { member ->
    append(buildString { renderMember(member, name, externs) }.withMintedReceiverBody())
  }
  cls.storedCallbackMethods.forEach { member ->
    append(buildString { renderMember(member, name, externs) }.withMintedReceiverBody())
  }
  cls.interfaceBridgeMethods.forEach { member ->
    append(buildString { renderMember(member, name, externs) }.withMintedReceiverBody())
  }
  cls.companionMembers.forEach { member ->
    val rendered: String = buildString { renderMember(member, name, externs) }
    val isInstance: Boolean = when (member) {
      is CirMethod -> !member.isStatic
      is CirProperty -> !member.isStatic
      else -> false
    }
    append(if (isInstance) rendered.withMintedReceiverBody() else rendered)
  }

  renderDataValueEquality(cls, externs)

  renderNestedDeclarations(
    cls.nestedDeclarations,
    enclosing + CirEnclosingExterns(cls.kotlinName, externs.externs),
  )
  appendLine("    }")
}

/** The static create call: the planned constructor body, returning the raw handle it made. */
private fun StringBuilder.renderCreate(ctor: CirConstructor, declarations: String) {
  appendLine("        private static IntPtr NugetCreate${ctor.nativeSuffix}($declarations)")
  if (ctor.hasErrorCheck) {
    val names: String = ctor.parameters.joinToString(", ") { it.nativeArgument }
    val args: String = if (names.isEmpty()) "out IntPtr error" else "$names, out IntPtr error"
    appendLine("        {")
    appendLine("            IntPtr handle = Native_Create${ctor.nativeSuffix}($args);")
    appendLine("            if (error != IntPtr.Zero)")
    appendLine("            {")
    appendLine("                throw NugetErrorNative.BuildException(error);")
    appendLine("            }")
    appendLine("            NugetErrorNative.ClearManagedFault();")
    appendLine("            return handle;")
    appendLine("        }")
  } else {
    check(HANDLE_ASSIGNMENT in ctor.body) {
      "ADR-242: a data value constructor body no longer ends in `$HANDLE_ASSIGNMENT`"
    }
    appendLine("        {${ctor.body.replace(HANDLE_ASSIGNMENT, "return handle;")}")
    appendLine("        }")
  }
  appendLine()
}

private fun StringBuilder.renderDataValueEquality(cls: CirClass, externs: CirExternScope) {
  val name: String = cls.name
  cls.dataClassNativeImports().forEach { nativeImport -> renderDllImport(nativeImport, externs) }
  if (cls.copyMethod != null) {
    append(buildString { renderMethod(cls.copyMethod, name) }.withMintedReceiverBody())
  }
  appendLine("        public bool Equals($name? other)")
  appendLine("        {")
  appendLine("            if (other is null) return false;")
  appendLine("            $MINT_RECEIVER")
  appendLine("            using NugetKotlinHandle otherHandle = other.NugetMint();")
  appendLine("            return Native_Equals(_handle, otherHandle);")
  appendLine("        }")
  appendLine()
  appendLine("        public override bool Equals(object? obj) => obj is $name other && Equals(other);")
  appendLine()
  appendLine("        public override int GetHashCode()")
  appendLine("        {")
  appendLine("            $MINT_RECEIVER")
  appendLine("            return Native_HashCode(_handle);")
  appendLine("        }")
  appendLine()
  appendLine("        public override string ToString()")
  appendLine("        {")
  appendLine("            $MINT_RECEIVER")
  appendLine("            return Marshal.PtrToStringUTF8(Native_ToString(_handle))!;")
  appendLine("        }")
  appendLine()
  appendLine(
    "        public static bool operator ==($name? left, $name? right) => " +
        "left is null ? right is null : left.Equals(right);",
  )
  appendLine()
  appendLine("        public static bool operator !=($name? left, $name? right) => !(left == right);")
}

private fun CirProperty.identifierOf(): String = explicitName


private fun CirProperty.withMintedReceiver(): CirProperty {
  val body: String = if (getter.contains('\n')) {
    "\n            $MINT_RECEIVER$getter"
  } else {
    "\n            $MINT_RECEIVER\n            return $getter;"
  }
  return copy(getter = body)
}

private val EXPRESSION_MEMBER = Regex("""^(        (?!///)\S.*?\)) => (.*);$""")

/**
 * A rendered instance member with the transient receiver minted at the top of its body. A block
 * body gets the mint after its opening brace; an expression body becomes a block.
 */
private fun String.withMintedReceiverBody(): String {
  if ("_handle" !in this) return this
  val lines: List<String> = lines()
  val out: MutableList<String> = mutableListOf()
  var minted = false
  for (line in lines) {
    if (minted) {
      out += line
      continue
    }
    val expression: MatchResult? = EXPRESSION_MEMBER.find(line)
    if (expression != null && "_handle" in expression.groupValues[2]) {
      val (head, body) = expression.destructured
      out += head
      out += "        {"
      out += "            $MINT_RECEIVER"
      out += if (head.contains(" void ")) "            $body;" else "            return $body;"
      out += "        }"
      minted = true
      continue
    }
    out += line
    if (line == "        {") {
      out += "            $MINT_RECEIVER"
      minted = true
    }
  }
  check(minted) { "ADR-242: no body to mint a receiver into:\n$this" }
  return out.joinToString("\n")
}
