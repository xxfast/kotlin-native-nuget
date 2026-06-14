package io.github.xxfast.nuget.processor.cir

internal val KOTLIN_TO_CSHARP_RETURN = mapOf(
  "String" to "IntPtr",
  "Byte" to "sbyte",
  "UByte" to "byte",
  "Short" to "short",
  "UShort" to "ushort",
  "Int" to "int",
  "UInt" to "uint",
  "Long" to "long",
  "ULong" to "ulong",
  "Float" to "float",
  "Double" to "double",
  "Boolean" to "bool",
  "Unit" to "void",
)

internal val KOTLIN_TO_CSHARP_PARAM = mapOf(
  "String" to "string",
  "Byte" to "sbyte",
  "UByte" to "byte",
  "Short" to "short",
  "UShort" to "ushort",
  "Int" to "int",
  "UInt" to "uint",
  "Long" to "long",
  "ULong" to "ulong",
  "Float" to "float",
  "Double" to "double",
  "Boolean" to "bool",
)

internal val LAMBDA_TYPES = setOf(
  "kotlin.Function0", "kotlin.Function1", "kotlin.Function2", "kotlin.Function3",
)

internal class CollectionHelperTracker {
  var needsList: Boolean = false
  var needsMap: Boolean = false
  var needsSet: Boolean = false
  val lambdaArities: MutableSet<Int> = mutableSetOf()
}

internal fun mapReturnType(kotlinType: String): String =
  KOTLIN_TO_CSHARP_RETURN[kotlinType] ?: "IntPtr"

internal fun mapParamType(kotlinType: String): String =
  KOTLIN_TO_CSHARP_PARAM[kotlinType] ?: "IntPtr"

internal fun mapPackageToNamespace(
  kotlinPackage: String,
  rootPackage: String,
  rootNamespace: String,
): String {
  if (rootPackage.isEmpty()) return rootNamespace

  val relative: String = if (kotlinPackage.startsWith(rootPackage)) {
    kotlinPackage.removePrefix(rootPackage).removePrefix(".")
  } else {
    kotlinPackage
  }

  if (relative.isEmpty()) return rootNamespace

  val suffix: String = relative.split(".")
    .joinToString(".") { segment ->
      segment.replaceFirstChar { it.uppercase() }
    }

  return "$rootNamespace.$suffix"
}
