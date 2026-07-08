package io.github.xxfast.kotlin.native.nuget.rir

// Identifies a bound C# class by its C# namespace and simple name. Used as a set key to
// determine whether a RirObjectHandleType reference is bridgeable (i.e. the type it refers to is
// a non-static class present in the extraction set). Kept as a plain data class rather than
// Pair<String,String> for readability at call sites.
data class RirTypeKey(val namespace: String, val name: String)

// Derives the set of all non-static RirClass types across the whole RirFile. A
// RirObjectHandleType is v1-bridgeable iff its (namespace, name) pair is in this set. Both
// generators call this function with the same RirFile before delegating to
// bridgeableStaticMethods, so the set is always consistent across the two generator outputs.
fun boundHandleTypes(file: RirFile): Set<RirTypeKey> =
  file.assemblies.flatMap { assembly ->
    assembly.namespaces.flatMap { namespace ->
      namespace.types
        .filterIsInstance<RirClass>()
        .filter { !it.isStatic }
        .map { RirTypeKey(namespace.name, it.name) }
    }
  }.toSet()

// ADR-049 Alternative 10: a single, shared source of truth for "which static methods on a bound
// RirClass are v1-bridgeable, in what order" — used by BOTH generateKotlinStubs (ADR-048) and
// generateCSharpShims (ADR-049). If this filter/order ever drifted between the two generators, the
// Kotlin registration export's parameter list and the C# [ModuleInitializer]'s pointer arguments
// would silently mismatch, corrupting every call through the misaligned slots at runtime. Do not
// duplicate this logic — always call this function from both generators.
//
// ADR-051: signature extended with boundHandleTypes (derived once per RirFile via the
// boundHandleTypes() helper above) so that RirObjectHandleType references can be resolved. Both
// generators derive the set via the shared helper, closing the same drift risk as this function.
fun bridgeableStaticMethods(cls: RirClass, boundHandleTypes: Set<RirTypeKey>): List<RirMethod> =
  cls.methods.filter { it.isStatic && isV1Bridgeable(it, boundHandleTypes) }

// ADR-052: mirrors bridgeableStaticMethods, but for public instance constructors. v1 supports at
// most one public instance constructor per type — the metadata reader emits either zero or one
// RirConstructor per class; multiple public `.ctor`s are grouped and skipped as an overload set
// upstream (skipped_overload_set diagnostic) and never reach either generator.
fun bridgeableConstructors(cls: RirClass, boundHandleTypes: Set<RirTypeKey>): List<RirConstructor> =
  cls.constructors.filter { ctor -> ctor.parameters.all { isV1Type(it.type, boundHandleTypes) } }

// ADR-052: a member that receives exactly one function-pointer slot on the type's
// nuget_{ns}_{type}_register export — either the type's public instance constructor or a
// bridgeable static method. Sealed so both generators derive the registration-order-sensitive
// parts of their output (fn-pointer variables, register parameters/body, C# DllImport parameters,
// C# ModuleInitializer pointer arguments) from ONE shared, ordered list, rather than each
// independently remembering "constructor before methods" — closing the same order-drift hole
// ADR-049 Alternative 10 closed for methods-only registration, now extended to the constructor.
sealed interface RirRegistrable {
  data class Ctor(val ctor: RirConstructor) : RirRegistrable
  data class Method(val method: RirMethod) : RirRegistrable
}

// ADR-052 "shared bridgeable ordering": the constructor pointer (if any) comes FIRST, then
// bridgeable static methods in their existing reverse-ir.json order. Both NugetGenerateBindingsTask
// and NugetGenerateShimsTask must consume this function directly — never re-derive the combined
// order independently, or the two sides' registration slots can silently drift out of alignment.
fun bridgeableRegistrables(cls: RirClass, boundHandleTypes: Set<RirTypeKey>): List<RirRegistrable> =
  bridgeableConstructors(cls, boundHandleTypes).map { RirRegistrable.Ctor(it) } +
    bridgeableStaticMethods(cls, boundHandleTypes).map { RirRegistrable.Method(it) }

// ADR-048 v1 bridgeable subset: static methods only, void/string/primitive/handle parameter and
// return types (overload sets, ref struct, open generics, dynamic, and DIMs are already filtered
// out before either generator sees them, per ADR-043).
private fun isV1Bridgeable(method: RirMethod, boundHandleTypes: Set<RirTypeKey>): Boolean {
  if (!isV1Type(method.returnType, boundHandleTypes)) return false
  return method.parameters.all { isV1Type(it.type, boundHandleTypes) }
}

private fun isV1Type(type: RirTypeRef, boundHandleTypes: Set<RirTypeKey>): Boolean = when (type) {
  is RirVoidType -> true
  is RirStringType -> true
  is RirPrimitiveType -> type.name in setOf(
    "bool", "byte", "short", "int", "long", "float", "double", "char",
  )
  // ADR-051: a handle ref is bridgeable iff the referenced type resolves to a non-static bound
  // class. Types outside the bound set stay unmapped and produce a skipped_unbound_type_reference
  // diagnostic from the reader — never reach here as bridgeable candidates.
  is RirObjectHandleType -> RirTypeKey(type.namespace, type.name) in boundHandleTypes
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
