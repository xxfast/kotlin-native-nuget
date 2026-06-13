package io.github.xxfast.nuget.processor.cir

data class CirFile(
  val usings: List<String> = listOf("System", "System.Runtime.InteropServices"),
  val namespaces: List<CirNamespace>,
)

data class CirNamespace(
  val name: String,
  val declarations: List<CirDeclaration>,
)

sealed interface CirDeclaration

data class CirStaticClass(
  val name: String,
  val members: List<CirMember>,
) : CirDeclaration

data class CirInterface(
  val name: String,
  val properties: List<CirInterfaceProperty>,
  val methods: List<CirInterfaceMethod>,
) : CirDeclaration

data class CirInterfaceProperty(
  val name: String,
  val type: String,
  val hasSetter: Boolean = false,
)

data class CirInterfaceMethod(
  val name: String,
  val returnType: String,
  val parameters: List<CirParameter>,
)

data class CirClass(
  val name: String,
  val libraryName: String,
  val nativePrefix: String,
  val constructor: CirConstructor?,
  val properties: List<CirProperty>,
  val methods: List<CirMethod>,
  val interfaces: List<String> = emptyList(),
  val superClass: String? = null,
  val disposable: Boolean = true,
  val hasInternalHandleConstructor: Boolean = true,
  val isDataClass: Boolean = false,
  val isAbstract: Boolean = false,
) : CirDeclaration

data class CirEnum(
  val name: String,
  val entries: List<CirEnumEntry>,
  val properties: List<CirEnumProperty> = emptyList(),
) : CirDeclaration

data class CirSealedClass(
  val name: String,
  val libraryName: String,
  val nativePrefix: String,
  val subclasses: List<CirSealedSubclass>,
) : CirDeclaration

data class CirSealedSubclass(
  val name: String,
  val nativePrefix: String,
  val properties: List<CirProperty>,
  val isDataClass: Boolean = false,
  val isDataObject: Boolean = false,
)

data class CirObject(
  val name: String,
  val libraryName: String,
  val nativePrefix: String,
  val methods: List<CirDllImport>,
) : CirDeclaration

data class CirGenericClass(
  val name: String,
  val typeParameters: List<String>,
  val libraryName: String,
  val nativePrefix: String,
  val properties: List<CirProperty>,
  val disposable: Boolean = true,
) : CirDeclaration

data class CirMarshalHelper(
  val libraryName: String,
) : CirDeclaration

data class CirEnumEntry(
  val name: String,
  val ordinal: Int,
)

data class CirEnumProperty(
  val name: String,
  val type: String,
  val nativeReturnType: String,
  val nativeName: String,
)

sealed interface CirMember

data class CirDllImport(
  val libraryName: String,
  val entryPoint: String?,
  val returnType: String,
  val name: String,
  val parameters: List<CirParameter>,
  val visibility: CirVisibility = CirVisibility.PUBLIC,
) : CirMember

data class CirMethod(
  val name: String,
  val returnType: String,
  val nativeReturnType: String = returnType,
  val nativeName: String = "Native_$name",
  val parameters: List<CirParameter>,
  val body: String,
  val visibility: CirVisibility = CirVisibility.PUBLIC,
  val isStatic: Boolean = false,
  val isAbstract: Boolean = false,
  val isOverride: Boolean = false,
) : CirMember

data class CirProperty(
  val name: String,
  val type: String,
  val nativeReturnType: String,
  val nativeName: String,
  val getter: String,
  val setter: String? = null,
) : CirMember

data class CirConstructor(
  val parameters: List<CirParameter>,
  val body: String,
)

data class CirParameter(
  val name: String,
  val type: String,
)

enum class CirVisibility {
  PUBLIC, PRIVATE
}
