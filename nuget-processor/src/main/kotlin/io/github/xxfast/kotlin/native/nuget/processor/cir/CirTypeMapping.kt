package io.github.xxfast.kotlin.native.nuget.processor.cir

import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeAlias

internal fun KSType.expandAliases(): KSType {
  val decl = declaration
  return if (decl is KSTypeAlias) decl.type.resolve().expandAliases()
  else this
}

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

internal val SUSPEND_LAMBDA_TYPES = setOf(
  "kotlin.coroutines.SuspendFunction0",
  "kotlin.coroutines.SuspendFunction1",
  "kotlin.coroutines.SuspendFunction2",
  "kotlin.coroutines.SuspendFunction3",
)

internal val FLOW_TYPES = setOf(
  "kotlinx.coroutines.flow.Flow",
)

internal class CollectionHelperTracker {
  var needsList: Boolean = false
  var needsMap: Boolean = false
  var needsSet: Boolean = false
  var needsAsync: Boolean = false
  var needsFlow: Boolean = false
  var needsSubscription: Boolean = false
  val lambdaArities: MutableSet<Int> = mutableSetOf()
  val suspendLambdaArities: MutableSet<Int> = mutableSetOf()
  val callbackDelegates: MutableList<CirCallbackDelegate> = mutableListOf()
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
