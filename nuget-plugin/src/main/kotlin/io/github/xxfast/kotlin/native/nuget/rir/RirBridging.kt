package io.github.xxfast.kotlin.native.nuget.rir

import java.security.MessageDigest

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

// ADR-056: every RirStruct declared anywhere in the RirFile, keyed the same way as
// boundHandleTypes. A RirStructType reference is only ever emitted by the metadata reader for a
// struct that already passed the reader's own Decision 3a validation — exactly mirroring how
// RirEnumType is only ever emitted for a validated enum (see isV1Type below) — so both generators
// need this map to resolve a struct reference's component list at codegen time (abiArgs/
// abiOutArgs/contractHash), not to decide bridgeability.
fun boundStructTypes(file: RirFile): Map<RirTypeKey, RirStruct> =
  file.assemblies.flatMap { assembly ->
    assembly.namespaces.flatMap { namespace ->
      namespace.types
        .filterIsInstance<RirStruct>()
        .map { struct -> RirTypeKey(namespace.name, struct.name) to struct }
    }
  }.toMap()

// ADR-056: one ABI-level argument. `type` is always a scalar — never a RirStructType, because
// abiArgs/abiOutArgs have already expanded any struct into its components. `isOutPointer` marks a
// struct-return out-parameter (see abiOutArgs); an ordinary in-argument is isOutPointer = false.
data class AbiArg(val name: String, val type: RirTypeRef, val isOutPointer: Boolean)

private fun resolveStruct(type: RirTypeRef, structs: Map<RirTypeKey, RirStruct>): RirStruct? =
  (type as? RirStructType)?.let { ref ->
    requireNotNull(structs[RirTypeKey(ref.namespace, ref.name)]) {
      "[nuget] struct ${ref.namespace}.${ref.name} is referenced but not declared in " +
          "reverse-ir.json"
    }
  }

// ADR-056, the anti-drift requirement: expands a parameter list into ABI-level arguments. A
// struct-typed parameter expands into one argument per component (ABI name "{param}_{Component}",
// Component being the struct's readName); every other parameter passes through unchanged. BOTH
// generators (NugetGenerateBindingsTask's Kotlin CFunction types and NugetGenerateShimsTask's C#
// thunk signatures) MUST call this shared function — not re-derive the expansion — or the
// registration slots still line up while the arguments inside a slot silently misalign, which is
// memory corruption with no error.
fun abiArgs(parameters: List<RirParameter>, structs: Map<RirTypeKey, RirStruct>): List<AbiArg> =
  parameters.flatMap { p ->
    val struct: RirStruct? = resolveStruct(p.type, structs)
    if (struct == null) listOf(AbiArg(p.name, p.type, isOutPointer = false))
    else struct.components.map { c ->
      AbiArg("${p.name}_${c.readName}", c.type, isOutPointer = false)
    }
  }

// ADR-056: a struct-typed return expands into one out-pointer ABI argument per component (ABI
// name "out{Component}"), appended after the real parameters. A non-struct return yields no
// out-arguments at all — see abiReturnType, which turns the thunk's own return into void whenever
// this list is non-empty.
fun abiOutArgs(returnType: RirTypeRef, structs: Map<RirTypeKey, RirStruct>): List<AbiArg> {
  val struct: RirStruct? = resolveStruct(returnType, structs)
  return struct?.components.orEmpty().map { c ->
    AbiArg("out${c.readName}", c.type, isOutPointer = true)
  }
}

// ADR-056: the ABI-level return type — RirVoidType when the real return is a struct (its
// components cross via abiOutArgs instead), the real return type unchanged otherwise.
fun abiReturnType(returnType: RirTypeRef, structs: Map<RirTypeKey, RirStruct>): RirTypeRef =
  if (resolveStruct(returnType, structs) != null) RirVoidType else returnType

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

fun bridgeableStructConstructors(
  struct: RirStruct,
  boundHandleTypes: Set<RirTypeKey>,
): List<RirConstructor> {
  val constructors: List<RirConstructor> = struct.constructors
    .filterNot { it.isState }
    .filter { ctor -> ctor.parameters.all { isV1Type(it.type, boundHandleTypes) } }
    .sortedBy { it.identity() }
  bridgeIds(constructors.map { it.identity() })
  return constructors
}

// ADR-056 deferred: Object / record boilerplate and operators never cross the reverse bridge for
// a value type. Filters apply on both the reader (prefer not to emit) and here (defence in depth
// for hand-built RIR fixtures).
private val SKIPPED_STRUCT_METHOD_NAMES: Set<String> = setOf(
  "Equals", "GetHashCode", "ToString", "Deconstruct",
)

private fun isSkippedStructMethod(method: RirMethod): Boolean =
  method.name in SKIPPED_STRUCT_METHOD_NAMES || method.name.startsWith("op_")

// ADR-056 deferred (struct methods + computed properties): shared ordered registration list for
// a struct: alternate ctors → static methods → instance methods → get-only computed property
// getters. Both generators MUST consume this function so slot indices never drift. State ctor is
// never a slot. Void-returning instance methods, setters, component auto-properties, and the
// Object/op_* skip list are excluded.
fun bridgeableStructRegistrables(
  struct: RirStruct,
  boundHandleTypes: Set<RirTypeKey>,
): List<RirRegistrable> {
  val constructors: List<RirRegistrable> =
    bridgeableStructConstructors(struct, boundHandleTypes).map { RirRegistrable.Ctor(it) }

  val staticMethods: List<RirRegistrable> = struct.methods
    .filter { it.isStatic }
    .filter { isV1Bridgeable(it, boundHandleTypes) }
    .filterNot { isSkippedStructMethod(it) }
    .sortedBy { it.identity() }
    .map { RirRegistrable.Method(it) }

  val instanceMethods: List<RirRegistrable> = struct.methods
    .filter { !it.isStatic }
    .filter { isV1Bridgeable(it, boundHandleTypes) }
    .filterNot { isSkippedStructMethod(it) }
    // Void instance methods are out of scope for reconstruct-on-call v1 (ADR-056 deferred).
    .filter { it.returnType !is RirVoidType }
    .sortedBy { it.identity() }
    .map { RirRegistrable.Method(it) }

  val componentReadNames: Set<String> =
    struct.components.map { it.readName.lowercase() }.toSet()
  val computedGetters: List<RirRegistrable> = struct.properties
    .filter { it.isReadOnly && !it.isStatic }
    .filter { it.name.lowercase() !in componentReadNames }
    .filter { isV1Type(it.type, boundHandleTypes) }
    .sortedBy { it.name }
    .map { RirRegistrable.PropertyGetter(it) }

  val result: List<RirRegistrable> =
    constructors + staticMethods + instanceMethods + computedGetters
  bridgeIds(result.map { it.identity() })
  return result
}

// ABI in-args for a struct instance member's reconstructed receiver: one scalar per component,
// named by the component's readName (PascalCase property), so C# thunk params stay distinct from
// ordinary method params (e.g. Mood vs mood) and from out-pointers (outX).
fun structReceiverAbiArgs(struct: RirStruct): List<AbiArg> =
  struct.components.map { c -> AbiArg(c.readName, c.type, isOutPointer = false) }

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

fun RirMethod.identity(): String = if (managedSignature.isNotEmpty()) managedSignature else {
  val receiver: String = if (isStatic) "static" else "instance"
  "method|$receiver||$name|(${parameters.joinToString(",") { it.type.describe() }})|" +
      returnType.describe()
}

fun RirConstructor.identity(): String = if (managedSignature.isNotEmpty()) managedSignature else
  "ctor|instance||.ctor|(${parameters.joinToString(",") { it.type.describe() }})|void"

fun RirMethod.bridgeId(): String = bridgeId(identity())

fun RirConstructor.bridgeId(): String = bridgeId(identity())

fun RirMethod.bridgeSuffix(): String =
  if (managedSignature.isEmpty()) "" else "__${bridgeId()}"

fun RirConstructor.bridgeSuffix(): String =
  if (managedSignature.isEmpty()) "" else "__${bridgeId()}"

fun bridgeId(signature: String): String = MessageDigest.getInstance("SHA-256")
  .digest(signature.toByteArray(Charsets.UTF_8))
  .take(16)
  .joinToString("") { byte -> "%02x".format(byte) }

fun bridgeIds(
  signatures: List<String>,
  digest: (String) -> String = ::bridgeId,
): Map<String, String> {
  val ids: Map<String, String> = signatures.associateWith(digest)
  ids.entries.groupBy { it.value }.values.forEach { matches ->
    val distinct: List<String> = matches.map { it.key }.distinct()
    require(distinct.size == 1) {
      "[nuget] bridge digest collision `${matches.first().value}` between " +
          distinct.joinToString(" and ") { "`$it`" }
    }
  }
  return ids
}

fun RirRegistrable.identity(): String = when (this) {
  is RirRegistrable.Ctor -> ctor.identity()
  is RirRegistrable.Method -> method.identity()
  is RirRegistrable.PropertyGetter ->
    "property|instance|get|${property.name}|${property.type.describe()}"

  is RirRegistrable.PropertySetter ->
    "property|instance|set|${property.name}|${property.type.describe()}"
}

fun RirRegistrable.bridgeId(): String = bridgeId(identity())

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
      val paramTypes: String = method.parameters.joinToString(", ") { it.type.describe() }
      val signature: String = "${method.name}($paramTypes)"
      RirDiagnostic(
        kind = RirDiagnosticKind.SKIPPED_MEMBER_NAME_COLLISION,
        typeName = cls.name,
        memberName = method.name,
        memberSignature = signature,
        reason = "member name collision — Kotlin name " +
            "'${method.name.toMethodCamelCase()}' would shadow the generated wrapper's own " +
            "'${method.name.toMethodCamelCase()}' member",
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
        reason = "member name collision — Kotlin name " +
            "'${property.name.toMethodCamelCase()}' would shadow the generated wrapper's own " +
            "'${property.name.toMethodCamelCase()}' member",
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
  is RirStructType -> "$namespace.$name"
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
  fun propertyRegistrables(properties: List<RirProperty>): List<RirRegistrable> =
    properties.flatMap { property ->
      val getter: RirRegistrable = RirRegistrable.PropertyGetter(property)
      if (property.isReadOnly) listOf(getter)
      else listOf(getter, RirRegistrable.PropertySetter(property))
    }

  val hasManagedIdentity: Boolean = (cls.constructors.map { it.managedSignature } +
      cls.methods.map { it.managedSignature }).any { it.isNotEmpty() }

  fun <T> canonical(values: List<T>, identity: (T) -> String): List<T> =
    if (hasManagedIdentity) values.sortedBy(identity) else values

  val constructors: List<RirRegistrable> = canonical(
    bridgeableConstructors(cls, boundHandleTypes), RirConstructor::identity,
  )
    .map { RirRegistrable.Ctor(it) }
  val staticMethods: List<RirRegistrable> = canonical(
    bridgeableStaticMethods(cls, boundHandleTypes), RirMethod::identity,
  )
    .map { RirRegistrable.Method(it) }
  val sortedInstanceMethods: List<RirRegistrable> = canonical(
    instanceMethods, RirMethod::identity,
  )
    .map { RirRegistrable.Method(it) }
  val sortedInstanceProperties: List<RirRegistrable> = propertyRegistrables(
    if (hasManagedIdentity) instanceProperties.sortedBy { it.name } else instanceProperties,
  )
  val sortedStaticProperties: List<RirRegistrable> = propertyRegistrables(
    if (hasManagedIdentity) staticProperties.sortedBy { it.name } else staticProperties,
  )
  val result: List<RirRegistrable> = constructors + staticMethods + sortedInstanceMethods +
      sortedInstanceProperties + sortedStaticProperties
  bridgeIds(result.map { it.identity() })
  return result
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
  // ADR-056: like RirEnumType above, the metadata reader only emits RirStructType for a struct
  // that already passed its own Decision 3a validation (component types included) — the struct's
  // own component types are checked there, not here.
  is RirStructType -> true
}

// ADR-054: a pure, deterministic 64-bit hash (FNV-1a) over the ordered registrable signature list
// of one RirClass — the "contractHash" leading parameter both generators bake into a type's
// nuget_{ns}_{type}_register export/[ModuleInitializer] call. Both generators read the same
// reverse-ir.json and call this exact function, so within one build the two sides can never
// disagree; across two different builds (a stale C# shim vs. a rebuilt native library) the hash
// differs whenever the ordered (memberKind, memberName, returnType, paramTypes, nullability) list
// differs, which is precisely the drift ADR-054 exists to detect at runtime.
// ADR-056: `structs` lets signaturePart expand a RirStructType's components — load-bearing (see
// below), not just plumbing.
fun contractHash(
  cls: RirClass,
  registrables: List<RirRegistrable>,
  structs: Map<RirTypeKey, RirStruct>,
): Long {
  val signature: String = cls.name + "|" +
      registrables.joinToString("|") { it.contractSignature(structs) }
  return fnv1a64(signature)
}

fun structConstructorContractHash(
  namespace: String,
  struct: RirStruct,
  constructors: List<RirConstructor>,
  structs: Map<RirTypeKey, RirStruct>,
): Long = structContractHash(
  namespace,
  struct,
  constructors.map { RirRegistrable.Ctor(it) },
  structs,
)

// ADR-056 deferred: contract hash over the full shared struct registration list (alternate
// ctors + methods + computed getters). Same component prefix as the ctor-only form so a
// members-free struct keeps a stable hash with the previous shape.
fun structContractHash(
  namespace: String,
  struct: RirStruct,
  registrables: List<RirRegistrable>,
  structs: Map<RirTypeKey, RirStruct>,
): Long {
  val components: String = struct.components.joinToString(",") {
    "${it.name}:${it.type.signaturePart(structs)}"
  }
  val signature: String = "$namespace.${struct.name}|{$components}|" +
      registrables.joinToString("|") { it.contractSignature(structs) }
  return fnv1a64(signature)
}

// One "column" of contractHash's input: member kind, name, parameter types (with nullability
// suffix), and — for a method or constructor — return type. A same-arity change to any of these
// (e.g. a parameter's nullability flips) changes the resulting hash, which is the whole point:
// same-arity drift is exactly what a plain slotCount comparison cannot see.
private fun RirRegistrable.contractSignature(structs: Map<RirTypeKey, RirStruct>): String =
  when (this) {
    is RirRegistrable.Ctor -> ctor.identity() + ":ctor(" +
        ctor.parameters.joinToString(",") { it.type.signaturePart(structs) } + ")"

    is RirRegistrable.Method -> method.identity() + ":method:${method.name}(" +
        method.parameters.joinToString(",") { it.type.signaturePart(structs) } +
        "):" + method.returnType.signaturePart(structs)

    is RirRegistrable.PropertyGetter ->
      "get:${property.name}:${property.type.signaturePart(structs)}"

    is RirRegistrable.PropertySetter ->
      "set:${property.name}:${property.type.signaturePart(structs)}"
  }

// describe() plus the ADR-053 nullability flag — two type refs that only differ by nullability
// must not hash identically, or a nullability-only drift between two generations would be invisible
// to the contract check.
//
// ADR-056, load-bearing: a RirStructType expands to "namespace.name{comp1,comp2,...}" instead of
// describe()'s bare "namespace.name". If the C# struct gains a field, every thunk taking it
// changes ARITY while the method signatures look unchanged — slotCount cannot see this drift
// either (the number of methods is unchanged) — so the hash must be sensitive to the struct's own
// component list, recursively (a component that is itself a struct would expand again).
//
// ADR-058 Decision 5: each component expands as "name:type", matching structContractHash's own
// expansion of a struct's OWN components — not type only. A struct reference used to hash only
// component TYPES, so reordering two same-typed components (e.g. Width/Height, both `int`) was
// invisible here even though it changes every affected thunk's true ABI slot assignment. Shape B
// makes such a reorder source-compatible in C# (an ordinary field reorder), where for Shape A it
// was already a breaking constructor-parameter reorder — promoting a latent hole into a plausible
// one. See RirBridgingTest's Decision 5 regression test.
private fun RirTypeRef.signaturePart(structs: Map<RirTypeKey, RirStruct>): String = when (this) {
  is RirStructType -> {
    val struct = requireNotNull(structs[RirTypeKey(namespace, name)]) {
      "[nuget] contractHash: struct $namespace.$name referenced but not declared in reverse-ir.json"
    }
    val componentParts: String =
      struct.components.joinToString(",") { "${it.name}:${it.type.signaturePart(structs)}" }
    "$namespace.$name{$componentParts}"
  }

  else -> describe() + if (isNullable) "?" else ""
}

// FNV-1a 64-bit over the UTF-8 bytes of [s]. Deterministic across JVM versions/platforms (unlike
// relying on String.hashCode() consistency), which both Gradle tasks need since they run this
// function independently in the same build.
private fun fnv1a64(s: String): Long {
  var hash = -3750763034362895579L // FNV-1a 64-bit offset basis (14695981039346656037 as Long)
  s.toByteArray(Charsets.UTF_8).forEach { byte ->
    hash = hash xor (byte.toLong() and 0xffL)
    hash *= 1099511628211L // FNV-1a 64-bit prime
  }
  return hash
}

// ADR-054: nuget_runtime_register has no RirClass/registrable list to hash (the shared GCHandle-
// free thunk is a fixed, single-slot contract, not derived from reverse-ir.json), so both
// generators bake this same literal constant rather than each independently re-deriving a "fake"
// one-element registrable list. Computed via the same fnv1a64 so it is not a magic number.
val NUGET_RUNTIME_CONTRACT_HASH: Long = fnv1a64("runtime:freeGcHandle(handle:COpaquePointer):Unit")

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
