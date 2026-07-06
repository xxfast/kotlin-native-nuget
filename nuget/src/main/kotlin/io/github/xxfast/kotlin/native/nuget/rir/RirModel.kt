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
) : RirType

@Serializable
@SerialName("interface")
data class RirInterface(
  override val name: String,
  val methods: List<RirMethod> = emptyList(),
  val properties: List<RirProperty> = emptyList(),
) : RirType

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

@Serializable
@OptIn(ExperimentalSerializationApi::class)
@JsonClassDiscriminator("kind")
sealed interface RirTypeRef

@Serializable
@SerialName("void")
data object RirVoidType : RirTypeRef

@Serializable
@SerialName("string")
data object RirStringType : RirTypeRef

// name is one of: "bool", "byte", "short", "int", "long", "float", "double", "char"
@Serializable
@SerialName("primitive")
data class RirPrimitiveType(val name: String) : RirTypeRef

// A reference to a bound C# class that crosses the bridge as an opaque GCHandle pointer (ADR-051).
// Split namespace/name rather than an assembly-qualified string: both generators resolve the
// referenced type to a Kotlin package + class, and parsing an AQN back apart is more error-prone
// than never joining it.
@Serializable
@SerialName("handle")
data class RirObjectHandleType(
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
  @SerialName("skipped_overload_set") SKIPPED_OVERLOAD_SET,
  @SerialName("skipped_ref_struct") SKIPPED_REF_STRUCT,
  @SerialName("skipped_open_generic") SKIPPED_OPEN_GENERIC,
  @SerialName("skipped_dynamic") SKIPPED_DYNAMIC,
  @SerialName("skipped_default_interface_method") SKIPPED_DEFAULT_INTERFACE_METHOD,
  @SerialName("skipped_unbound_type_reference") SKIPPED_UNBOUND_TYPE_REFERENCE,
  @SerialName("info_async_not_yet_mapped") INFO_ASYNC_NOT_YET_MAPPED,
}
