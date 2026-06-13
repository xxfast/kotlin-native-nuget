package io.github.xxfast.nuget.processor

val C_RESERVED = setOf(
  "auto", "break", "case", "char", "const", "continue", "default", "do",
  "double", "else", "enum", "extern", "float", "for", "goto", "if",
  "int", "long", "register", "return", "short", "signed", "sizeof",
  "static", "struct", "switch", "typedef", "union", "unsigned", "void",
  "volatile", "while",
)

val CSHARP_RESERVED = setOf(
  "abstract", "as", "base", "bool", "break", "byte", "case", "catch",
  "char", "checked", "class", "const", "continue", "decimal", "default",
  "delegate", "do", "double", "else", "enum", "event", "explicit",
  "extern", "false", "finally", "fixed", "float", "for", "foreach",
  "goto", "if", "implicit", "in", "int", "interface", "internal", "is",
  "lock", "long", "namespace", "new", "null", "object", "operator",
  "out", "override", "params", "private", "protected", "public",
  "readonly", "ref", "return", "sbyte", "sealed", "short", "sizeof",
  "stackalloc", "static", "string", "struct", "switch", "this", "throw",
  "true", "try", "typeof", "uint", "ulong", "unchecked", "unsafe",
  "ushort", "using", "virtual", "void", "volatile", "while",
)

fun toCName(name: String): String {
  if (name in C_RESERVED) return "${name}_"
  return name
}

fun toCSharpName(cname: String): String {
  if (cname.trimEnd('_') in CSHARP_RESERVED) return "@$cname"
  return cname
}
