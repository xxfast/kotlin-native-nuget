package io.github.xxfast.kotlin.native.nuget.processor.cir

internal fun StringBuilder.renderEnum(enum: CirEnum) {
  appendLine("    public enum ${enum.name}")
  appendLine("    {")

  for (entry in enum.entries) {
    appendLine("        ${entry.name} = ${entry.ordinal},")
  }

  appendLine("    }")

  if (enum.properties.isNotEmpty()) {
    appendLine()
    renderEnumExtensions(enum)
  }
}

internal fun StringBuilder.renderEnumExtensions(enum: CirEnum) {
  appendLine("    public static class ${enum.name}Extensions")
  appendLine("    {")

  for (prop in enum.properties) {
    val enumLowercase: String = enum.name.lowercase()
    val propLowercase: String = prop.nativeName.lowercase()
    val entryPoint: String = "${enumLowercase}_get_$propLowercase"

    appendLine("        [DllImport(\"sample\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"$entryPoint\")]")
    appendLine("        private static extern ${prop.nativeReturnType} Native_Get${prop.name}(int ordinal);")
    appendLine()

    val body: String = if (prop.type == "string") {
      "Marshal.PtrToStringUTF8(Native_Get${prop.name}((int)${enum.name.lowercase()}))!"
    } else {
      "Native_Get${prop.name}((int)${enum.name.lowercase()})"
    }

    appendLine("        public static ${prop.type} ${prop.name}(this ${enum.name} ${enum.name.lowercase()})")
    appendLine("            => $body;")
    appendLine()
  }

  appendLine("    }")
}

