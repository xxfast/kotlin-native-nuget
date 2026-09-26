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

/**
 * ADR-132 (2026-09-20): `partial`, and load-bearing. An enum that has properties of its own already
 * owns a `{Enum}Extensions` class here, while *any* extension declared over that enum -- function
 * or property -- merges into a static class of the same name in the same namespace
 * (`CirTranslator`'s extension loops, rendered `public static partial class` by
 * `CirClassRenderer`). Two declarations of one class with only one of them `partial` is CS0260,
 * which fails the whole `Interop.cs` compile. Latent since ADR-132 shipped the enum receiver on
 * the extension-FUNCTION route: nothing in the fixture set declared an extension over an enum
 * until `Mood.rallyCry()` / `Mood.emoji` did.
 */
internal fun StringBuilder.renderEnumExtensions(enum: CirEnum) {
  appendLine("    public static partial class ${enum.csName.replace(".", "")}Extensions")
  appendLine("    {")

  for (prop in enum.properties) {
    // The Kotlin property name verbatim (`mood_get_isSleepy`), as `EnumExports` spells the export
    // and as every other property route does. Lowercasing it here aborted generation for any
    // camelCase enum property (`Forward ABI missing Kotlin export`).
    val entryPoint: String = "${enum.nativePrefix}_get_${prop.nativeName}"
    // ADR-133: the C# parameter name of the extension's `this` receiver, with the enclosing
    // scope's dots stripped (`ownerkind`, not `owner.kind`, which is not a legal identifier).
    val receiverParam: String = enum.csName.lowercase().replace(".", "")

    appendLine("        [DllImport(\"${enum.libraryName}\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"$entryPoint\")]")
    // ADR-098: an enum's `Char` property getter is an extern slot like any other.
    charReturnMarshal(prop.nativeReturnType)?.let { appendLine(it) }
    // Kotlin/Native `Boolean` is a 1-byte C `bool`; without I1 .NET reads a 4-byte Win32 `BOOL`.
    if (prop.nativeReturnType == "bool") appendLine("        [return: MarshalAs(UnmanagedType.I1)]")
    appendLine("        private static extern ${prop.nativeReturnType} Native_Get${prop.name}(int ordinal);")
    appendLine()

    val body: String = if (prop.type == "string") {
      "Marshal.PtrToStringUTF8(Native_Get${prop.name}((int)$receiverParam))!"
    } else if (prop.isEnum) {
      // The wire is the ordinal (ADR-006); the extern is `int` and the public spelling is the enum.
      "(${prop.type})Native_Get${prop.name}((int)$receiverParam)"
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

