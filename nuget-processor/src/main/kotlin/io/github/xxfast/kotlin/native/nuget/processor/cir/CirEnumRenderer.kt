package io.github.xxfast.kotlin.native.nuget.processor.cir

/**
 * ADR-133: [nested] renders the enum body only. A nested enum is re-indented into its owner's
 * block by the caller, while its extension class stays at namespace level (CS1109 forbids
 * extension methods in a nested class) and is emitted by `renderNamespace`'s post-pass.
 */
internal fun StringBuilder.renderEnum(enum: CirEnum, nested: Boolean = false) {
  renderDoc(enum.doc)
  appendLine("    public enum ${enum.name}")
  appendLine("    {")

  for (entry in enum.entries) {
    renderDoc(entry.doc, "        ")
    appendLine("        ${entry.name} = ${entry.ordinal},")
  }

  appendLine("    }")

  if (enum.properties.isNotEmpty() && !nested) {
    appendLine()
    renderEnumExtensions(enum)
  }
}

internal fun StringBuilder.renderEnumExtensions(enum: CirEnum) {
  appendLine("    public static class ${enum.csName.replace(".", "")}Extensions")
  appendLine("    {")

  for (prop in enum.properties) {
    val enumLowercase: String = enum.nativePrefix
    val propLowercase: String = prop.nativeName.lowercase()
    val entryPoint: String = "${enumLowercase}_get_$propLowercase"
    // ADR-133: the C# parameter name of the extension's `this` receiver, with the enclosing
    // scope's dots stripped (`ownerkind`, not `owner.kind`, which is not a legal identifier).
    val receiverParam: String = enum.csName.lowercase().replace(".", "")

    appendLine("        [DllImport(\"${enum.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"$entryPoint\")]")
    // ADR-098: an enum's `Char` property getter is an extern slot like any other.
    charReturnMarshal(prop.nativeReturnType)?.let { appendLine(it) }
    appendLine("        private static extern ${prop.nativeReturnType} Native_Get${prop.name}(int ordinal);")
    appendLine()

    val body: String = if (prop.type == "string") {
      "Marshal.PtrToStringUTF8(Native_Get${prop.name}((int)$receiverParam))!"
    } else {
      "Native_Get${prop.name}((int)$receiverParam)"
    }

    appendLine(
      "        public static ${prop.type} ${prop.name}(this ${enum.csName} $receiverParam)",
    )
    appendLine("            => $body;")
    appendLine()
  }

  appendLine("    }")
}

