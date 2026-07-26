package io.github.xxfast.kotlin.native.nuget.rir

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

@Serializable
data class RirFile(
  val assemblies: List<RirAssembly>,
)

@Serializable
data class RirAssembly(
  val packageId: String,
  val assemblyName: String,
  val namespaces: List<RirNamespace>,
  val diagnostics: List<RirDiagnostic> = emptyList(),
)

@Serializable
data class RirNamespace(
  val name: String,
  val types: List<RirType>,
)

@Serializable
@OptIn(ExperimentalSerializationApi::class)
@JsonClassDiscriminator("kind")
sealed interface RirType {
  val name: String
}

@Serializable
@SerialName("class")
data class RirClass(
  override val name: String,
  val isAbstract: Boolean = false,
  // ECMA-335: a C# static class is `abstract sealed` in metadata (ADR-051)
  val isStatic: Boolean = false,
  val methods: List<RirMethod> = emptyList(),
  val properties: List<RirProperty> = emptyList(),
  // ADR-052: at most one public instance `.ctor` per type in v1 (additive; old JSON parses with
  // an empty list — a type with no public instance constructor has no RirConstructor entries).
  val constructors: List<RirConstructor> = emptyList(),
  // ADR-070 Decision 5: every interface this class implements, from
  // TypeDefinition.GetInterfaceImplementations() — verified to be the FULL TRANSITIVE closure for
  // a class (a class implementing `ITagged : IFeedable` lists both), not just the directly-
  // declared one. Each entry is "{Namespace}.{Name}"; defaulted so old reverse-ir.json still
  // parses. Filtered to interfaces that are themselves admissible and bound is a generator-side
  // concern (RirBridging.classInterfaceSupertypes), not the reader's.
  val interfaces: List<String> = emptyList(),
) : RirType

// ADR-070: a C#-declared, admissible (Decision 6), bound interface. [methods]/[properties] are
// this interface's OWN declared members only (never members inherited from a base interface —
// verified: `ITagged : IFeedable` reaches the reader with only its own `Tag`), because a Kotlin
// `interface IDerived : IBase` inherits IBase's members through ordinary Kotlin interface
// inheritance and the IDerivedHandle wrapper dispatches them through IBase's own registration
// slots (Decision 5) — nothing needs to be re-declared or re-registered here.
@Serializable
@SerialName("interface")
data class RirInterface(
  override val name: String,
  val methods: List<RirMethod> = emptyList(),
  val properties: List<RirProperty> = emptyList(),
  // ADR-070 Decision 5: base interfaces this interface directly extends (verified: unlike a
  // class, an interface's own InterfaceImpl table lists only its DIRECT bases —
  // `ITagged : IFeedable` lists just IFeedable), "{Namespace}.{Name}" each. Defaulted so old
  // reverse-ir.json still parses.
  val interfaces: List<String> = emptyList(),
) : RirType

// Phase 9: a C# enum admitted by the metadata reader's deliberately narrow reverse mapping.
// Every entry has a validated contiguous Int ordinal, so enum values can cross the C ABI as Int.
@Serializable
@SerialName("enum")
data class RirEnum(
  override val name: String,
  val entries: List<RirEnumEntry>,
) : RirType

@Serializable
data class RirEnumEntry(
  val name: String,
  val ordinal: Int,
)

// ADR-058: how a bridgeable struct's components are reconstructed on the C# side. Shape A (the
// struct has a public instance constructor whose parameters cover its stored state) reconstructs
// with `new T(a, b)`; Shape B (no such constructor — public fields or settable auto-properties)
// reconstructs with an object initializer, `new T { A = a, B = b }`. Defaults to CONSTRUCTOR so
// existing reverse-ir.json without this field (every struct predating ADR-058) still parses as
// Shape A. Mirrors `RirStructShape` in NugetMetadataReader/Program.cs.
@Serializable
enum class RirStructShape {
  @SerialName("constructor")
  CONSTRUCTOR,

  @SerialName("initializer")
  INITIALIZER,
}

// ADR-056: a C# struct that satisfies the Decision 3a "Shape A" rules: exactly one public
// instance constructor, whose parameters (case-insensitively matched to public readable
// properties of the same type) fully cover the struct's stored state. Components remain the wire
// contract for value decomposition; [methods]/[properties] carry ADR-056 deferred scope (struct
// methods + computed properties, ADR-014 reconstruct-on-call mirror). Defaults empty so existing
// reverse-ir.json without those arrays still parse. Mirrors `RirStruct` in
// NugetMetadataReader/Program.cs field-for-field once the reader enumerates members.
// ADR-058: [shape] additionally admits "Shape B" structs — no public constructor; components are
// public settable fields / settable auto-properties instead, in C# declaration (FieldDef) order.
// [constructors] is always empty for RirStructShape.INITIALIZER (Decision 4a: alternate
// constructors on a Shape B struct are deferred, diagnosed rather than bridged).
@Serializable
@SerialName("struct")
data class RirStruct(
  override val name: String,
  val components: List<RirStructComponent> = emptyList(),
  val shape: RirStructShape = RirStructShape.CONSTRUCTOR,
  val constructors: List<RirConstructor> = emptyList(),
  val methods: List<RirMethod> = emptyList(),
  val properties: List<RirProperty> = emptyList(),
) : RirType

// One component of a bridgeable struct (ADR-056). [name] is the constructor parameter name
// (drives the Kotlin property name); [readName] is the public property used to read the value
// back, which may differ in case from [name] (verified: ctor `x` vs. property `X`).
@Serializable
data class RirStructComponent(
  val name: String,
  val readName: String,
  val type: RirTypeRef,
)

@Serializable
data class RirMethod(
  val name: String,
  val returnType: RirTypeRef,
  val parameters: List<RirParameter> = emptyList(),
  val isStatic: Boolean = false,
  val managedSignature: String = "",
)

@Serializable
data class RirProperty(
  val name: String,
  val type: RirTypeRef,
  val isReadOnly: Boolean = true,
  val isStatic: Boolean = false,
)

@Serializable
data class RirParameter(
  val name: String,
  val type: RirTypeRef,
)

// ADR-052: a C# public instance constructor. Return is implicit (the enclosing RirClass's own
// RirObjectHandleType) — a constructor never returns null, unlike a factory RirMethod, which is
// why this is a distinct node rather than reusing RirMethod (whose returnType is mandatory).
@Serializable
data class RirConstructor(
  val parameters: List<RirParameter> = emptyList(),
  val managedSignature: String = "",
  val isState: Boolean = false,
)

@Serializable
@OptIn(ExperimentalSerializationApi::class)
@JsonClassDiscriminator("kind")
sealed interface RirTypeRef

@Serializable
@SerialName("void")
data object RirVoidType : RirTypeRef

// ADR-053: `nullable` reflects the decoded NullableAttribute payload for this type reference
// (`string?` -> true; oblivious/un-annotated `string` -> false, see RirTypeRef.isNullable).
@Serializable
@SerialName("string")
data class RirStringType(val nullable: Boolean = false) : RirTypeRef

// name is one of: "bool", "byte", "short", "int", "long", "float", "double", "char"
@Serializable
@SerialName("primitive")
data class RirPrimitiveType(val name: String) : RirTypeRef

// A reference to a bound C# class that crosses the bridge as an opaque GCHandle pointer (ADR-051).
// Split namespace/name rather than an assembly-qualified string: both generators resolve the
// referenced type to a Kotlin package + class, and parsing an AQN back apart is more error-prone
// than never joining it.
// ADR-053: `nullable` reflects the decoded NullableAttribute payload for this type reference
// (`Foo?` -> true; oblivious/un-annotated `Foo` -> false, see RirTypeRef.isNullable).
@Serializable
@SerialName("handle")
data class RirObjectHandleType(
  val namespace: String,
  val name: String,
  val nullable: Boolean = false,
) : RirTypeRef

// A reference to a supported C# enum. Like handles, namespace/name keep the JSON contract easy
// to consume; unlike handles, this always crosses the ABI as an ordinal Int.
@Serializable
@SerialName("enum")
data class RirEnumType(
  val namespace: String,
  val name: String,
) : RirTypeRef

// A reference to a bridgeable struct (ADR-056). Unlike RirObjectHandleType, this never crosses
// the bridge as a pointer — both generators expand it into its RirStruct.components at the ABI
// level (arguments in, out-pointers out; see RirBridging.kt's abiArgs/abiOutArgs/abiReturnType).
// Carries no `nullable` flag by design: a nullable value type is System.Nullable<T>, a distinct
// closed generic struct, not an annotation on this type ref.
@Serializable
@SerialName("struct")
data class RirStructType(
  val namespace: String,
  val name: String,
) : RirTypeRef

// ADR-070 Decision 1: a reference to a bound, admissible C# interface. Wire-identical to
// RirObjectHandleType (IntPtr from GCHandle.ToIntPtr(GCHandle.Alloc(obj)), IntPtr.Zero for null)
// — the difference is entirely Kotlin-side: a value arriving at an interface-typed position
// always wraps as the interface's OWN `{Name}Handle` implementation, never the concrete bound
// class (ADR-070 Decision 3), because the wire carries no runtime type tag.
@Serializable
@SerialName("interface")
data class RirInterfaceType(
  val namespace: String,
  val name: String,
  val nullable: Boolean = false,
) : RirTypeRef

@Serializable
data class RirDiagnostic(
  val kind: RirDiagnosticKind,
  val typeName: String,
  val memberName: String,
  val memberSignature: String,
  val reason: String,
  val hint: String,
)

@Serializable
enum class RirDiagnosticKind {
  @SerialName("skipped_overload_set")
  SKIPPED_OVERLOAD_SET,

  @SerialName("skipped_ref_struct")
  SKIPPED_REF_STRUCT,

  @SerialName("skipped_open_generic")
  SKIPPED_OPEN_GENERIC,

  @SerialName("skipped_dynamic")
  SKIPPED_DYNAMIC,

  @SerialName("skipped_default_interface_method")
  SKIPPED_DEFAULT_INTERFACE_METHOD,

  @SerialName("skipped_unbound_type_reference")
  SKIPPED_UNBOUND_TYPE_REFERENCE,

  // Phase 9 (ROADMAP line 151, instance methods/properties — confirmed mirror of ADR-051/052, no
  // new ADR): unlike every other SKIPPED_* kind, this one is never emitted by the metadata reader
  // — it depends on the ADR-051 wrapper's own Kotlin member names (`handle`, `close`, `cleaner`),
  // which only the Gradle-plugin-side generators know about. Reuses the existing RirDiagnostic
  // model (ADR-043's mechanism) rather than inventing a separate reporting path.
  @SerialName("skipped_member_name_collision")
  SKIPPED_MEMBER_NAME_COLLISION,

  @SerialName("skipped_unsupported_enum")
  SKIPPED_UNSUPPORTED_ENUM,

  // ADR-056: a value-type candidate that failed the Decision 3a "Shape A" bridgeability rules
  // (no/multiple public instance constructors, an unmatched or non-v1 component type, or stored
  // fields not fully covered by the constructor). Never emitted as a class fallback.
  @SerialName("skipped_unsupported_struct")
  SKIPPED_UNSUPPORTED_STRUCT,

  // ADR-059 Decision 5a: a bridged member whose flattened ABI argument count (receiver +
  // parameters + out-pointers, after recursively flattening every nested struct component) would
  // exceed the verified 22-argument Kotlin/Native `CFunction.invoke` ceiling. Emitted
  // Gradle-plugin-side (like SKIPPED_MEMBER_NAME_COLLISION above) from the SHARED
  // bridgeableRegistrables/bridgeableStructRegistrables filter, never by either generator alone —
  // only the shared filter knows the flattened ABI projection, and if only one generator dropped
  // the member the two sides' registration slots would silently misalign.
  @SerialName("skipped_abi_arity_limit")
  SKIPPED_ABI_ARITY_LIMIT,

  @SerialName("error_kotlin_signature_collision")
  ERROR_KOTLIN_SIGNATURE_COLLISION,

  @SerialName("info_async_not_yet_mapped")
  INFO_ASYNC_NOT_YET_MAPPED,

  // ADR-053: an oblivious (un-annotated) reference type binds non-null in Kotlin — this is an
  // informational signal, not a skip, since the member is still bridged. One assembly-level
  // instance (memberName empty) when the whole assembly carries no NullableAttribute/
  // NullableContextAttribute anywhere; one per-member instance (memberName populated) for a
  // `#nullable disable` island inside an otherwise-annotated assembly.
  @SerialName("info_oblivious_nullability")
  INFO_OBLIVIOUS_NULLABILITY,

  // ADR-070 Decision 6: an open generic interface (`IFoo`1`) — the arity-mangled CLR name is not
  // valid Kotlin. Interface-level (unlike skipped_open_generic, which is per-member).
  @SerialName("skipped_generic_interface")
  SKIPPED_GENERIC_INTERFACE,

  // ADR-070 Decision 6: a `static`/`static abstract`/`static virtual` interface member — calling
  // it through the interface itself is CS8926 for the abstract/virtual case.
  @SerialName("skipped_interface_static_member")
  SKIPPED_INTERFACE_STATIC_MEMBER,

  // ADR-070 Decision 6: an indexer (`this[int]`) — reaches the reader as a parameterless property
  // literally named `Item`, which would emit an uncompilable `receiver.Item`. Pre-existing gap,
  // also affects classes.
  @SerialName("skipped_indexer")
  SKIPPED_INDEXER,

  // ADR-070 Decision 6: a `event` member — currently dropped with no diagnostic at all.
  // Pre-existing gap, also affects classes.
  @SerialName("skipped_event")
  SKIPPED_EVENT,

  // ADR-070 Decision 6: an interface with zero admissible members — nothing to generate, must not
  // emit an empty registration export.
  @SerialName("skipped_empty_interface")
  SKIPPED_EMPTY_INTERFACE,

  // ADR-070 Decision 5: a bound class's supertype for a given interface is omitted because at
  // least one interface member has no identically-signed public member on the class (e.g. an
  // explicit interface implementation, which is non-public in metadata).
  @SerialName("skipped_interface_supertype")
  SKIPPED_INTERFACE_SUPERTYPE,

  // ADR-070 Decision 5: a derived interface (`IDerived : IBase`) is bound with only its own
  // declared members because IBase is not itself admissible/bound — IDerived's Kotlin interface
  // omits the `: IBase` supertype and IDerivedHandle cannot dispatch IBase's members.
  @SerialName("info_inherited_interface_members_absent")
  INFO_INHERITED_INTERFACE_MEMBERS_ABSENT,
}
