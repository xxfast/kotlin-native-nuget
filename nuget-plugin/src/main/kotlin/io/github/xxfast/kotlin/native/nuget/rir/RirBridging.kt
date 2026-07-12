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

  // Phase 9 (ROADMAP line 151, instance methods/properties — confirmed "mirror" item, no new ADR:
  // "an instance thunk is a static thunk whose first parameter is the receiver handle," ADR-051
  // Deferred section): one slot per bridgeable instance property. A read-only property emits only
  // a PropertyGetter. A settable property emits both PropertyGetter and PropertySetter.
  //
  // ADR-053 (ROADMAP line 157 unblock): this used to carve out an exception for a handle-typed
  // settable property ("rule 4") — a Kotlin `var`'s getter and setter must share one type, but
  // ADR-051 gave object returns an unconditional `Foo?` and object params an unconditional non-null
  // `Foo`, so no single type existed for such a `var`. Now that a `RirObjectHandleType` carries its
  // own `nullable` flag (a C# property has exactly one `NullableAttribute`), getter and setter
  // agree on one type — `Foo?` or `Foo` — and the exception is gone: a settable handle-typed
  // property always gets a PropertySetter slot, the same as any other settable property.
  data class PropertyGetter(val property: RirProperty) : RirRegistrable
  data class PropertySetter(val property: RirProperty) : RirRegistrable
}

// Phase 9 (ROADMAP line 151): v1-bridgeable instance methods on a bound class — mirrors
// bridgeableStaticMethods, but for `!isStatic` methods.
fun bridgeableInstanceMethods(cls: RirClass, boundHandleTypes: Set<RirTypeKey>): List<RirMethod> =
  cls.methods.filter { !it.isStatic && isV1Bridgeable(it, boundHandleTypes) }

// Phase 9: v1-bridgeable properties on a bound class. Static properties support strings and the
// current primitive subset; handle-typed static properties remain deferred with handle setters.
fun bridgeableProperties(cls: RirClass, boundHandleTypes: Set<RirTypeKey>): List<RirProperty> =
  cls.properties.filter { property ->
    isV1Type(property.type, boundHandleTypes) &&
        (!property.isStatic || property.type !is RirObjectHandleType)
  }

// Phase 9 (ROADMAP line 151, rule 5 — human-approved v1 scope call): the Kotlin member names
// already claimed by the ADR-051 wrapper class itself. An instance member whose Kotlin name
// collides with one of these cannot be rendered as a class member — statics are unaffected
// because they live in the companion object, a separate namespace.
internal val WRAPPER_MEMBER_NAMES: Set<String> = setOf("handle", "close", "cleaner")

// Phase 9 (ROADMAP line 151): PascalCase C# member name → camelCase Kotlin name: lowercase the
// first character only. e.g. SerializeObject → serializeObject, Close → close. Mirrors the
// identically-named private helper duplicated in NugetGenerateBindingsTask.kt/
// NugetGenerateShimsTask.kt; kept here too (rather than exported from either task file) so
// RirBridging.kt — the shared, generator-agnostic layer — never depends on either consumer.
private fun String.toMethodCamelCase(): String = replaceFirstChar { it.lowercaseChar() }

// Phase 9 (ROADMAP line 151, rule 5): ADR-043-style skip diagnostics for instance members whose
// Kotlin name collides with an ADR-051 wrapper member (WRAPPER_MEMBER_NAMES above). Reuses the
// existing RirDiagnostic model — the same one the metadata reader emits for skipped_overload_set
// etc. — rather than inventing a new reporting mechanism (per ADR-043's existing "Diagnostic
// format" contract), even though this particular diagnostic is produced Gradle-plugin-side, not by
// the metadata reader (see RirDiagnosticKind.SKIPPED_MEMBER_NAME_COLLISION).
//
// Deliberately independent of boundHandleTypes/type-bridgeability: a name collision is a Kotlin
// naming problem only, not a type problem — a member is skipped because its Kotlin name shadows
// the wrapper's own member, regardless of whether its C# type would otherwise have been bridgeable.
fun collisionDiagnostics(cls: RirClass): List<RirDiagnostic> {
  val methodDiagnostics: List<RirDiagnostic> = cls.methods
    .filter { !it.isStatic && it.name.toMethodCamelCase() in WRAPPER_MEMBER_NAMES }
    .map { method ->
      val signature: String = "${method.name}(${method.parameters.joinToString(", ") { it.type.describe() }})"
      RirDiagnostic(
        kind = RirDiagnosticKind.SKIPPED_MEMBER_NAME_COLLISION,
        typeName = cls.name,
        memberName = method.name,
        memberSignature = signature,
        reason = "member name collision — Kotlin name '${method.name.toMethodCamelCase()}' would " +
            "shadow the generated wrapper's own '${method.name.toMethodCamelCase()}' member",
        hint = "Rename or remove this member on the bound C# type, or expose it via a " +
            "differently-named C# adapter method.",
      )
    }

  val propertyDiagnostics: List<RirDiagnostic> = cls.properties
    .filter { !it.isStatic && it.name.toMethodCamelCase() in WRAPPER_MEMBER_NAMES }
    .map { property ->
      RirDiagnostic(
        kind = RirDiagnosticKind.SKIPPED_MEMBER_NAME_COLLISION,
        typeName = cls.name,
        memberName = property.name,
        memberSignature = "${property.type.describe()} ${property.name}",
        reason = "member name collision — Kotlin name '${property.name.toMethodCamelCase()}' would " +
            "shadow the generated wrapper's own '${property.name.toMethodCamelCase()}' member",
        hint = "Rename or remove this member on the bound C# type, or expose it via a " +
            "differently-named C# adapter property.",
      )
    }

  return methodDiagnostics + propertyDiagnostics
}

// ADR-053: whether a type reference is nullable, per the decoded NullableAttribute payload.
// RirVoidType/RirPrimitiveType/RirEnumType carry no such flag — a nullable value type is a
// distinct closed generic struct (System.Nullable<T>), not an annotation on the value type
// itself, and is out of scope for v1 (deferred to the Phase 9 structs item). Both generators read
// this through a single shared accessor rather than re-deriving the same `when` at each call site.
val RirTypeRef.isNullable: Boolean
  get() = when (this) {
    is RirStringType -> nullable
    is RirObjectHandleType -> nullable
    else -> false
  }

// Short human-readable type description for collisionDiagnostics' memberSignature/reason text
// only (not part of any generated code path) — deliberately independent of the kotlinType()/
// csAbiType() rendering tables owned by the two generator tasks.
private fun RirTypeRef.describe(): String = when (this) {
  is RirVoidType -> "void"
  is RirStringType -> "string"
  is RirPrimitiveType -> name
  is RirObjectHandleType -> "$namespace.$name"
  is RirEnumType -> "$namespace.$name"
}

// ADR-052 "shared bridgeable ordering", extended by Phase 9 line 151: the constructor pointer (if
// any) comes FIRST, then bridgeable static methods, THEN bridgeable instance methods, THEN one
// PropertyGetter/[PropertySetter] pair per bridgeable instance property, followed by the same
// pairs for static properties — each group in its existing reverse-ir.json declaration order,
// groups appended (never interleaved) so already generated ctor/static slot indices never churn.
// Both NugetGenerateBindingsTask and
// NugetGenerateShimsTask must consume this function directly — never re-derive the combined order
// independently, or the two sides' registration slots can silently drift out of alignment.
//
// Rule 5: instance methods/properties whose Kotlin name collides with an ADR-051 wrapper member
// (see collisionDiagnostics) are excluded here — this is the single shared place both generators'
// output is filtered, so a skipped member never gets a registration slot on either side.
fun bridgeableRegistrables(cls: RirClass, boundHandleTypes: Set<RirTypeKey>): List<RirRegistrable> {
  val collidingNames: Set<String> = collisionDiagnostics(cls).map { it.memberName }.toSet()

  val instanceMethods: List<RirMethod> = bridgeableInstanceMethods(cls, boundHandleTypes)
    .filterNot { it.name in collidingNames }

  val properties: List<RirProperty> = bridgeableProperties(cls, boundHandleTypes)
  val instanceProperties: List<RirProperty> = properties
    .filterNot { it.isStatic }
    .filterNot { it.name in collidingNames }
  val staticProperties: List<RirProperty> = properties
    .filter { it.isStatic }

  // ADR-053: "rule 4" (a handle-typed settable property never getting a PropertySetter slot) is
  // deleted — see the PropertyGetter/PropertySetter doc comment above. A settable property (of
  // any type) always gets both a PropertyGetter and a PropertySetter slot.
  fun propertyRegistrables(properties: List<RirProperty>): List<RirRegistrable> = properties.flatMap { property ->
    val getter: RirRegistrable = RirRegistrable.PropertyGetter(property)
    if (property.isReadOnly) listOf(getter)
    else listOf(getter, RirRegistrable.PropertySetter(property))
  }

  return bridgeableConstructors(cls, boundHandleTypes).map { RirRegistrable.Ctor(it) } +
      bridgeableStaticMethods(cls, boundHandleTypes).map { RirRegistrable.Method(it) } +
      instanceMethods.map { RirRegistrable.Method(it) } +
      propertyRegistrables(instanceProperties) +
      propertyRegistrables(staticProperties)
}

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
  // The metadata reader only emits RirEnumType for a validated default-int, contiguous enum.
  is RirEnumType -> true
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
