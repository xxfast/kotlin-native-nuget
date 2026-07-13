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
) : RirType

@Serializable
@SerialName("interface")
data class RirInterface(
  override val name: String,
  val methods: List<RirMethod> = emptyList(),
  val properties: List<RirProperty> = emptyList(),
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

// ADR-056: a C# struct that satisfies the Decision 3a "Shape A" rules — exactly one public
// instance constructor, whose parameters (case-insensitively matched to public readable
// properties of the same type) fully cover the struct's stored state. Struct members (methods,
// computed properties) are deliberately not enumerated in v1: `components` is the complete wire
// contract. Mirrors `RirStruct` in nuget-metadata-reader/Program.cs field-for-field.
@Serializable
@SerialName("struct")
data class RirStruct(
  override val name: String,
  val components: List<RirStructComponent> = emptyList(),
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

  @SerialName("info_async_not_yet_mapped")
  INFO_ASYNC_NOT_YET_MAPPED,

  // ADR-053: an oblivious (un-annotated) reference type binds non-null in Kotlin — this is an
  // informational signal, not a skip, since the member is still bridged. One assembly-level
  // instance (memberName empty) when the whole assembly carries no NullableAttribute/
  // NullableContextAttribute anywhere; one per-member instance (memberName populated) for a
  // `#nullable disable` island inside an otherwise-annotated assembly.
  @SerialName("info_oblivious_nullability")
  INFO_OBLIVIOUS_NULLABILITY,
}
