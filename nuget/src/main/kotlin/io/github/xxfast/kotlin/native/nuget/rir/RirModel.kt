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

// Future (post-v1, unblocked by "Map C# objects as opaque handles in Kotlin"):
// @Serializable
// @SerialName("handle")
// data class RirObjectHandleType(val assemblyQualifiedName: String) : RirTypeRef

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
  @SerialName("info_async_not_yet_mapped") INFO_ASYNC_NOT_YET_MAPPED,
}
