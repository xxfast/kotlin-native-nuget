package io.github.xxfast.kotlin.native.nuget.processor.cir

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
  val typeParameters: List<CirTypeParameter> = emptyList(),
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
  val secondaryConstructors: List<CirConstructor> = emptyList(),
  val properties: List<CirProperty>,
  val methods: List<CirMethod>,
  val callbackMethods: List<CirCallbackMethod> = emptyList(),
  val storedCallbackMethods: List<CirStoredCallbackMethod> = emptyList(),
  val interfaceBridgeMethods: List<CirInterfaceBridgeMethod> = emptyList(),
  val interfaces: List<String> = emptyList(),
  val superClass: String? = null,
  val disposable: Boolean = true,
  val hasInternalHandleConstructor: Boolean = true,
  val isDataClass: Boolean = false,
  val isAbstract: Boolean = false,
  val companionMembers: List<CirMember> = emptyList(),
  val hasSuspendMethods: Boolean = false,
) : CirDeclaration

data class CirValueClass(
  val name: String,
  val libraryName: String,
  val nativePrefix: String,
  val underlyingType: String,
  val underlyingName: String,
  val underlyingNativeType: String,
  val underlyingIsReference: Boolean = false,
  val constructors: List<CirValueClassConstructor> = emptyList(),
  val properties: List<CirProperty>,
  val methods: List<CirMethod>,
) : CirDeclaration

data class CirValueClassConstructor(
  val parameters: List<CirParameter>,
  val nativeName: String,
  val body: String,
  val hasErrorCheck: Boolean = false,
  // Distinguishes constructor entry points / C# native methods. Empty for the
  // primary (catid_create / Native_Create); "_2", "_3", … for secondaries. See
  // ADR-035 (aligns value classes with ADR-034's regular-class scheme).
  val nativeSuffix: String = "",
)

data class CirEnum(
  val name: String,
  val libraryName: String,
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

enum class CirVariance { INVARIANT, COVARIANT, CONTRAVARIANT }

data class CirTypeParameter(
  val name: String,
  val bounds: List<String> = emptyList(),
  val variance: CirVariance = CirVariance.INVARIANT,
)

data class CirGenericClass(
  val name: String,
  val typeParameters: List<CirTypeParameter>,
  val libraryName: String,
  val nativePrefix: String,
  val properties: List<CirProperty>,
  val disposable: Boolean = true,
  val hasPublicConstructor: Boolean = true,
) : CirDeclaration

data class CirMarshalHelper(
  val libraryName: String,
) : CirDeclaration

data class CirListHelper(
  val libraryName: String,
) : CirDeclaration

data class CirMapHelper(
  val libraryName: String,
) : CirDeclaration

data class CirSetHelper(
  val libraryName: String,
) : CirDeclaration

data class CirFuncNativeHelper(
  val libraryName: String,
  val arities: Set<Int>,
) : CirDeclaration

data class CirFuncHelper(
  val libraryName: String,
  val arities: Set<Int>,
  val helperNamespace: String,
) : CirDeclaration

data class CirSuspendFuncNativeHelper(
  val libraryName: String,
  val arities: Set<Int>,
) : CirDeclaration

data class CirSuspendFuncHelper(
  val libraryName: String,
  val arities: Set<Int>,
  val helperNamespace: String,
) : CirDeclaration

data class CirAsyncHelper(
  val libraryName: String,
) : CirDeclaration

data class CirScopeHelper(
  val libraryName: String,
) : CirDeclaration

data class CirJobHelper(
  val libraryName: String,
) : CirDeclaration

data class CirErrorHelper(
  val libraryName: String,
) : CirDeclaration

data class CirFlowHelper(
  val libraryName: String,
) : CirDeclaration

data class CirSubscriptionHelper(
  val libraryName: String,
) : CirDeclaration

data class CirCallbackDelegate(
  val name: String,
  val paramList: String,
  val returnType: String,
)

data class CirCallbackDelegateHelper(
  val delegates: List<CirCallbackDelegate>,
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
  val hasSyncErrorOut: Boolean = false,
) : CirMember

// One interface method entry within a CirInterfaceBridgeMethod.
data class CirInterfaceBridgeMethodEntry(
  val methodCsName: String,       // "OnMeow"
  val methodKtName: String,       // "onMeow" (used as variable prefix: onMeowCb, onMeowPtr)
  val delegateName: String,       // "NugetObjectVoidCallback"
  val delegateParamList: String,  // "(IntPtr arg0Ptr, IntPtr _)"
  val callbackBody: String,       // C# inline body: "string arg0 = ...; listener.OnMeow(arg0);"
)

// A class method that is the subscribe half of an interface-bridge pair (add*/subscribe*).
// The paired parameter is a Kotlin interface type, not a lambda.
// Generates an IDisposable factory method backed by two native exports with N function pointers.
data class CirInterfaceBridgeMethod(
  val csMethodName: String,        // "AddListener"
  val csRemoveNativeName: String,  // "Native_RemoveListener"
  val subscribeEntryPoint: String, // "cateventsource_addListener"
  val removeEntryPoint: String,    // "cateventsource_removeListener"
  val libraryName: String,
  val interfaceCsName: String,     // "ICatEventListener"
  val className: String,           // "CatEventSource"
  val entries: List<CirInterfaceBridgeMethodEntry>,
) : CirMember

// A class method that is the subscribe half of a stored-callback pair (add*/subscribe*).
// Generates an IDisposable factory method backed by two native exports.
data class CirStoredCallbackMethod(
  val csMethodName: String,           // "AddMoodListener"
  val csRemoveNativeName: String,     // "Native_RemoveMoodListener"
  val subscribeEntryPoint: String,    // "cat_addMoodListener"
  val removeEntryPoint: String,       // "cat_removeMoodListener"
  val libraryName: String,
  val delegateName: String,           // "NugetIntVoidCallback"
  val delegateParamList: String,      // "(int arg0Ord, IntPtr _)"
  val csParamType: String,            // "Action<Mood>"
  val nativeCallbackBody: String,     // body inside the nativeCallback lambda
) : CirMember

// A class method that accepts a lambda parameter from C# (phase 7 reverse interop).
data class CirCallbackMethod(
  val csMethodName: String,
  val nativeEntryPoint: String,
  val libraryName: String,
  val nativeImportReturnType: String,
  val lambdaParamName: String,
  val delegateName: String,
  val delegateParamList: String,
  val csReturnType: String,
  val csParamType: String,
  val callbackBody: String,
  val wrapperBody: String,
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
  val isExtension: Boolean = false,
  val typeParameters: List<CirTypeParameter> = emptyList(),
  val isAsync: Boolean = false,
  val asyncReturnType: String = "",
  val isSyncErrorCheckEnabled: Boolean = false,
  val isFlow: Boolean = false,
  val flowElementType: String = "",
) : CirMember

data class CirProperty(
  val name: String,
  val type: String,
  val nativeReturnType: String,
  val nativeSetterType: String = nativeReturnType,
  val nativeName: String,
  val getter: String,
  val setter: String? = null,
  val extraNatives: List<CirExtraNative> = emptyList(),
  val isStatic: Boolean = false,
  val isFlow: Boolean = false,
  val flowElementType: String = "",
  val hasSyncErrorOut: Boolean = false,
) : CirMember

data class CirExtraNative(
  val entryPointSuffix: String,
  val returnType: String,
  val name: String,
  val hasValueParam: Boolean = false,
  val hasSyncErrorOut: Boolean = false,
)

data class CirConstructor(
  val parameters: List<CirParameter>,
  val body: String,
  val hasErrorCheck: Boolean = false,
  // Distinguishes secondary constructor entry points / C# native methods from the
  // primary's. Empty for the primary (cat_create / Native_Create); "_2", "_3", …
  // for secondaries (cat_create_2 / Native_Create_2). See ADR-034.
  val nativeSuffix: String = "",
)

data class CirParameter(
  val name: String,
  val type: String,
  // Native (DllImport) type; differs from type when the public C# type needs a cast
  // at the call site (e.g. enum method params: public type "Mood", native type "int").
  val nativeType: String = type,
)

data class CirConst(
  val name: String,
  val type: String,
  val value: String,
) : CirMember

enum class CirVisibility {
  PUBLIC, PRIVATE
}
