package io.github.xxfast.kotlin.native.nuget.rir

// ADR-049 Alternative 10: a single, shared source of truth for "which static methods on a bound
// RirClass are v1-bridgeable, in what order" — used by BOTH generateKotlinStubs (ADR-048) and
// generateCSharpShims (ADR-049). If this filter/order ever drifted between the two generators, the
// Kotlin registration export's parameter list and the C# [ModuleInitializer]'s pointer arguments
// would silently mismatch, corrupting every call through the misaligned slots at runtime. Do not
// duplicate this logic — always call this function from both generators.
fun bridgeableStaticMethods(cls: RirClass): List<RirMethod> =
  cls.methods.filter { it.isStatic && isV1Bridgeable(it) }

// ADR-048 v1 bridgeable subset: static methods only, void/string/primitive parameter and return
// types (overload sets, ref struct, open generics, dynamic, and DIMs are already filtered out
// before either generator sees them, per ADR-043).
private fun isV1Bridgeable(method: RirMethod): Boolean {
  if (!isV1Type(method.returnType)) return false
  return method.parameters.all { isV1Type(it.type) }
}

private fun isV1Type(type: RirTypeRef): Boolean = when (type) {
  is RirVoidType -> true
  is RirStringType -> true
  is RirPrimitiveType -> type.name in setOf(
    "bool", "byte", "short", "int", "long", "float", "double", "char",
  )
}

// Shared registration export-name derivation (ADR-048's naming contract, which ADR-049's C# side
// must match exactly): "nuget_{ns_snake}_{type_snake}_register". Sharing this function (rather than
// letting each generator re-derive it) closes the same drift risk as bridgeableStaticMethods above.
fun registrationExportName(namespaceName: String, typeName: String): String {
  val nsSnake: String = namespaceName.replace('.', '_').lowercase()
  val typeSnake: String = typeName.toTypeSnake()
  return if (nsSnake.isEmpty()) "nuget_${typeSnake}_register"
    else "nuget_${nsSnake}_${typeSnake}_register"
}

// PascalCase type name → lower_snake_case: insert '_' before each uppercase letter after the
// first, then lowercase. e.g. JsonConvert → json_convert, MathHelper → math_helper
fun String.toTypeSnake(): String = buildString {
  this@toTypeSnake.forEachIndexed { i, c ->
    if (i > 0 && c.isUpperCase()) append('_')
    append(c.lowercaseChar())
  }
}
