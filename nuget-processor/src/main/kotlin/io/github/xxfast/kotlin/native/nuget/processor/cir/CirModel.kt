package io.github.xxfast.kotlin.native.nuget.processor.cir

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardBridgeInterfacePlan

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
  // The data-class `copy()` method when it routes through the shared callable plan (Phase 6);
  // null when it is ineligible for planning (e.g. an object-typed constructor parameter) and
  // falls back to the legacy hand-rolled route in `dataClassNativeImports`/`renderDataClassMethods`.
  val copyMethod: CirMethod? = null,
  val callbackMethods: List<CirCallbackMethod> = emptyList(),
  val storedCallbackMethods: List<CirStoredCallbackMethod> = emptyList(),
  val interfaceBridgeMethods: List<CirInterfaceBridgeMethod> = emptyList(),
  val interfaces: List<String> = emptyList(),
  val superClass: String? = null,
  val hasInternalHandleConstructor: Boolean = true,
  val isDataClass: Boolean = false,
  val isAbstract: Boolean = false,
  // ADR-101 amendment (2026-09-10): a non-abstract Kotlin `open class`, i.e. one an exported
  // subclass may extend. Only `Dispose()` reads it, to render `virtual` instead of nothing: a
  // subclass always spells its inherited `Dispose` `override`, which is CS0506 against a
  // non-virtual base. An abstract base already renders `abstract void Dispose();` and a final
  // class has nothing that can override it, so both keep their shipped spelling.
  val isOpen: Boolean = false,
  // ADR-040: true for the generated interface backing wrapper (`sealed class Pet : IPet`) — no
  // public constructor, and the `sealed` modifier communicates that consumers should implement
  // `IPet` rather than subclass this handle wrapper.
  val isSealed: Boolean = false,
  val companionMembers: List<CirMember> = emptyList(),
  val hasSuspendMethods: Boolean = false,
  // ADR-064 amendment (2026-09-10): plain-text prose for a `<remarks>` doc comment on the class,
  // set only when WARNING_NO_PUBLIC_CONSTRUCTOR fires, off the same detail string the diagnostic
  // uses. Text, not markup: `renderRemarks` owns the XML escaping, because the detail names
  // Kotlin constructors as `<init>`.
  val remarks: String? = null,
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
  // Distinguishes constructor entry points / C# native methods. Empty for the
  // primary (catid_create / Native_Create); "_2", "_3", … for secondaries. See
  // ADR-035 (aligns value classes with ADR-034's regular-class scheme).
  val nativeSuffix: String = "",
  // ADR-077: wire-typed DllImport parameters and lowered call arguments when the public and
  // native shapes differ (an enum parameter is `Mood` publicly but `int` on the wire, called as
  // `(int)mood`). Null means the public shape is already the wire shape, the pre-existing routes.
  val nativeParameters: List<CirParameter>? = null,
  val nativeArguments: List<String>? = null,
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
  /**
   * ADR-111 amendment (2026-09-11): the base's **own** declared properties, projected from
   * base-keyed ADR-062 plans exactly as an ordinary [CirClass]'s are. They render `virtual` rather
   * than `abstract` even when Kotlin declares them `abstract`: the export is keyed to the base
   * type, so Kotlin's own dispatch picks the arm's body, and an `abstract` C# member would force
   * every arm to declare an override, which a covariant arm cannot spell (CS1715 then CS0534).
   */
  val properties: List<CirProperty> = emptyList(),
  /**
   * ADR-116 amendment (2026-09-11): the method half of [properties]. A base `open fun` an arm does
   * not override lives here and nowhere else, which is what lets the arm inherit it in C# the way
   * it already inherits it in Kotlin.
   */
  val methods: List<CirMethod> = emptyList(),
) : CirDeclaration

data class CirSealedSubclass(
  val name: String,
  val nativePrefix: String,
  val properties: List<CirProperty>,
  /**
   * ADR-116: the arm's own declared member functions, projected from the ADR-062 callable plan the
   * way an ordinary [CirClass]'s methods are. Empty for an arm that declares none. A `suspend`
   * member rides [asyncMembers] (ADR-118) and a `Flow`-returning one [flowMembers] (ADR-124); a
   * generic member has no arm route at all and is named by a `SKIPPED_UNSUPPORTED_COMBINATION`
   * diagnostic instead, and so does a callback member the arm route does not cover (an add/remove
   * pair, a `suspend` lambda parameter).
   */
  val methods: List<CirMethod> = emptyList(),
  /**
   * ADR-118: the arm's own declared `suspend` members, projected by the same `suspendMembers`
   * function an ordinary class's `companionMembers` carry -- a [CirDllImport] and an async
   * [CirMethod] per member, which is why they cannot ride [methods].
   */
  val asyncMembers: List<CirMember> = emptyList(),
  /**
   * ADR-124: the arm's Flow/StateFlow-returning **methods**, projected by the same `flowMembers`
   * function an ordinary class's `companionMembers` carry -- a [CirDllImport] set and a
   * `CirMethod(isFlow = true)` per member, which is why they cannot ride [methods] either. The
   * arm's flow *properties* do ride [properties]: a flow property is a [CirProperty] like any
   * other, with `isFlow` set.
   */
  val flowMembers: List<CirMember> = emptyList(),
  /**
   * ADR-116 amendment (2026-09-11): the arm's own declared **per-call lambda-parameter** methods
   * (ADR-036), projected by the same `translateCallbackMethod` an ordinary class's are. A
   * [CirDllImport] plus a `CirCallbackMethod` per member, which is why they cannot ride [methods]
   * either. Deliberately not folded into [hasSuspendMethods]: the callback route runs
   * synchronously and needs no scope of the arm's own.
   */
  val callbackMembers: List<CirMember> = emptyList(),
  /**
   * Whether the arm owns a coroutine scope, which is what gives it its `_scopeHandle`,
   * `GetOrCreateScope()`, `IAsyncDisposable` and `DisposeAsync`. ADR-118 set it for a suspending
   * arm; ADR-124 widened it to a flow-bearing one (a flow method or a flow property), because the
   * collect protocol needs a scope of the arm's own exactly as an `async` body does. The name is
   * kept: [CirClass.hasSuspendMethods] already carries the same widened meaning, and renaming one
   * half would make the two disagree.
   *
   * Deliberately derived from what actually **projected** rather than from a `getAllFunctions()`
   * scan: a base-declared (`open suspend fun`) or ADR-114 refused member would otherwise give the
   * arm a scope no member on it ever uses. One boolean for both routes, so an arm carrying suspend
   * *and* flow members emits exactly one scope field.
   */
  val hasSuspendMethods: Boolean = false,
  val isDataClass: Boolean = false,
  /**
   * Issue #54: whether the subclass is declared *inside* its sealed base in Kotlin. C# follows the
   * Kotlin scope, so a nested one is rendered inside the base's braces (`Shape.Circle`) and a
   * sibling is rendered after them, at namespace level (`Label`). Either way the exports keep the
   * sealed prefix, and either way the sealed route is the only thing that declares the type.
   */
  val isNested: Boolean = true,
  /**
   * ADR-009 amendment (2026-09-11): whether the arm is declared `open` in Kotlin. An open arm
   * renders `public class` instead of `public sealed class`, because a Kotlin subclass of it is
   * collected as an ordinary class with the arm as its base, and `sealed` there is CS0509 on that
   * subclass and CS0549 on every `virtual` member the arm needs. A final arm is unchanged.
   */
  val isOpen: Boolean = false,
)

data class CirObject(
  val name: String,
  val libraryName: String,
  val nativePrefix: String,
  val methods: List<CirMember>,
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
  // ADR-101 amendment (2026-09-11): an `open` generic class can be a base, and a derived class
  // always renders `public override void Dispose()`, so this one renders `virtual` (CS0506).
  val isOpen: Boolean = false,
) : CirDeclaration

data class CirMarshalHelper(
  val libraryName: String,
  // ADR-073: NugetMarshal.CreateMap/CreateSet call into NugetMapNative/NugetSetNative, which are
  // only emitted (as separate CirMapHelper/CirSetHelper declarations) when the tracker actually
  // saw a Map/Set collection anywhere in the file. Gating these two method bodies on the same
  // flags keeps a library with, say, only List parameters (and no Map/Set at all) compiling --
  // otherwise CreateMap/CreateSet would reference a native class that was never emitted, a
  // CS0103 for every such consumer.
  val includesMap: Boolean = false,
  val includesSet: Boolean = false,
  // ADR-099: same gate for CreateList and ReadList, both of which call into NugetListNative.
  val includesList: Boolean = false,
  // ADR-084: `HandleOf` falls back to `NugetBridge.HandleFor` for a value with no `_handle` (a
  // C#-implemented Kotlin interface). Gated on the same principle as includesMap/includesSet: the
  // fallback only compiles where a CirBridgeHelper was actually emitted.
  val includesBridge: Boolean = false,
  // ADR-094: one entry per concrete handle wrapper in the whole file, rendered as the
  // `NugetMarshal.Factories` dictionary. `Materialize<T>` looks a type up here instead of calling
  // `Activator.CreateInstance` over the internal `T(IntPtr)` constructor, which AOT-only runtimes
  // (Mac Catalyst, iOS) cannot do.
  val factories: List<CirFactoryEntry> = emptyList(),
) : CirDeclaration

// ADR-094: a registry line. [qualifiedTypeName] is the `global::`-free fully qualified C# name
// (`Clinic.ApiResult.Success`); the renderer adds the `global::` prefix, since the registry sits in
// the root namespace and cannot see child-namespace types unqualified.
data class CirFactoryEntry(
  val qualifiedTypeName: String,
  // ADR-094 / issue #40: a sealed BASE is abstract, so `new Base(handle)` will not compile, but it
  // is still materialisable -- its generated `internal static Base FromHandle(IntPtr)` reads the
  // Kotlin-side discriminator and news up the right subclass. Entries flagged here render as a call
  // to that discriminator instead of a constructor, which is what lets `StateFlow<Sealed>.Value`
  // and `await foreach` over a `Flow<Sealed>` hand back the correct arm.
  val viaFromHandle: Boolean = false,
)

// ADR-084: the C#-implemented-interface bridge layer -- `NugetBridge`, `NugetBridgeState`, and one
// `{Iface}BridgeState` per bridgeable interface. Emitted only when at least one interface plans.
internal data class CirBridgeHelper(
  val libraryName: String,
  val interfaces: List<CirBridgeInterface>,
) : CirDeclaration

// The bridge layer is emitted into the root namespace while `IPet` lives in the namespace mapped
// from its Kotlin package, so every reference to the projected interface has to be qualified.
internal data class CirBridgeInterface(
  val csNamespace: String,
  val plan: ForwardBridgeInterfacePlan,
) {
  val csQualifiedName: String = "$csNamespace.${plan.csName}"
}

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

/**
 * ADR-129: the always-emitted runtime helper. Unlike every other helper here it is added OUTSIDE
 * `needsMarshalHelper`, because the `nuget_runtime_version` import must exist for every library,
 * including a scalar-only one that needs no marshalling at all.
 */
data class CirRuntimeHelper(
  val libraryName: String,
) : CirDeclaration

data class CirErrorHelper(
  val libraryName: String,
) : CirDeclaration

data class CirFlowHelper(
  val libraryName: String,
  // ADR-065: also emit the KotlinStateFlow<T> subclass (implies needsFlow -- set by the tracker
  // whenever any StateFlow member is planned).
  val includesStateFlow: Boolean = false,
  // ADR-071: also emit the KotlinMutableStateFlow<T> subclass (implies includesStateFlow -- set by
  // the tracker whenever a publicly-DECLARED MutableStateFlow<T> member/return is planned).
  val includesMutableStateFlow: Boolean = false,
) : CirDeclaration

// ADR-068: the two shared generic exports keyed on an already-obtained StateFlow<*> handle --
// `Collect`/`Value` on the awaited flow's own StableRef, rather than re-invoking a parent member.
// Emitted once per module (like [CirScopeHelper]/[CirJobHelper]), regardless of how many
// suspend-StateFlow members exist across every class.
data class CirStateFlowHandleHelper(
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
  val marshalBooleanReturn: Boolean = false,
  /**
   * ADR-111/ADR-116 amendment (2026-09-11): `private new static extern`, for a **nested** sealed
   * arm whose extern name collides with one on its base. The arms are declared inside the base
   * (ADR-009 / issue #54), so the base's private externs are accessible to them, and an arm that
   * overrides `sides` mints a `Native_Get_sides` beside the base's own: CS0108, which
   * `GeneratedBindingsCheck` compiles as an error. A sibling arm never sets it (the base's private
   * members are inaccessible there, so nothing is hidden and `new` would be CS0109).
   */
  val isNew: Boolean = false,
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
  /**
   * ADR-090: the private `[DllImport]` extern's C# name, when it is not `Native_$name`.
   *
   * Two overloads share one public name but must not share one extern (CS0111), so a numbered
   * member carries the numbered extern name here. Null keeps the shipped `Native_$name`.
   */
  val externName: String? = null,
  val parameters: List<CirParameter>,
  val body: String,
  val visibility: CirVisibility = CirVisibility.PUBLIC,
  val isStatic: Boolean = false,
  val isAbstract: Boolean = false,
  val isOverride: Boolean = false,
  // ADR-116 amendment (2026-09-11): the method-side twin of [CirProperty.isNew]. A sealed arm
  // whose Kotlin `override` narrows the base's return type cannot spell a C# `override` for every
  // shape (a value-type covariant return is CS0508), so it hides the base member instead. Without
  // the modifier the hide is a CS0108 warning, which `GeneratedBindingsCheck` compiles as an error.
  val isNew: Boolean = false,
  // ADR-040 fixture gap: a class method that implements an interface member (no CLASS supertype,
  // so `isOverride` is false) but whose Kotlin `override` is not `final` is open for further
  // override by a subclass (Kotlin's default: an `override` member stays open unless marked
  // `final`) — Animal.fetch(item) implementing Pet.fetch, further overridden by Cat.fetch, is
  // exactly this shape. C# requires the base declaration to say `virtual` for that subclass
  // `override` to compile (CS0506 otherwise).
  val isVirtual: Boolean = false,
  val isExtension: Boolean = false,
  val typeParameters: List<CirTypeParameter> = emptyList(),
  val isAsync: Boolean = false,
  val asyncReturnType: String = "",
  // ADR-119: the C# expression turning the awaited `resultPtr` into [asyncReturnType], for a
  // suspend member returning a collection (`NugetMarshal.ReadList<T>(resultPtr, ...)`). Null keeps
  // the shipped `new T(resultPtr)` / `FromHandle<T>(resultPtr)` selection in `renderAsyncMethod`.
  val asyncResultRead: String? = null,
  val isSyncErrorCheckEnabled: Boolean = false,
  val isFlow: Boolean = false,
  // ADR-065: true when this is a StateFlow-returning method. Reuses isFlow's whole legacy route
  // (the _collect export + KotlinFlow substrate) and additionally renders a KotlinStateFlow<T>
  // wrapper plus a synchronous `_value` native import (see [stateFlowValueNativeName]).
  val isStateFlow: Boolean = false,
  val flowElementType: String = "",
  // ADR-123: the `read:` argument a collection element passes to KotlinFlow<T>/KotlinStateFlow<T>,
  // the per-member `Func<IntPtr, T>` that materialises one emission through `NugetMarshal.ReadList`
  // and kin. Null leaves the shipped construction untouched, so a non-collection element still
  // reads through the constructors' default `NugetMarshal.FromHandle<T>`.
  val flowElementRead: String? = null,
  // The native method name (e.g. "Native_MoodReportValue") of the sibling `_value` DllImport
  // this StateFlow method's companion-member list also carries. Empty unless [isStateFlow].
  val stateFlowValueNativeName: String = "",
  // ADR-071: true when this is a MutableStateFlow-returning method whose element/member is not
  // nullable (v1 scope) -- renders KotlinMutableStateFlow<T> instead of KotlinStateFlow<T> and
  // additionally carries the sibling `_set_value` DllImport named by
  // [stateFlowSetValueNativeName]. Empty (false) unless [isStateFlow].
  val isMutableStateFlow: Boolean = false,
  // The native method name (e.g. "Native_MoodReportSetValue") of the sibling `_set_value`
  // DllImport. Empty unless [isMutableStateFlow].
  val stateFlowSetValueNativeName: String = "",
  // ADR-071: true when the settable element crosses the write seam as an object handle (v.Handle)
  // rather than by value. Only consulted when [isMutableStateFlow].
  val isMutableStateFlowElementObject: Boolean = false,
  // ADR-067: true when the StateFlow member itself is nullable (`StateFlow<T>?` return). Renders
  // the return type as `KotlinStateFlow<T>?` and gates a `_has_value` presence-probe DllImport /
  // null-check before construction. Empty (false) unless [isStateFlow].
  val isStateFlowNullableMember: Boolean = false,
  // The native method name (e.g. "Native_MoodReportHasValue") of the sibling `_has_value`
  // presence-probe DllImport. Empty unless [isStateFlowNullableMember].
  val stateFlowHasValueNativeName: String = "",
  // Extra raw native (DllImport) parameter declarations spliced in between the method's own
  // parameters and the trailing `out IntPtr error` — e.g. `out int value` for a nullable-
  // primitive return's out-parameter (ADR-061 §5). Empty for every other shape.
  val extraNativeParams: List<String> = emptyList(),
  // When true, `renderMethod` skips `renderSyncErrorCheckMethod`'s auto cast-only cascade (which
  // has no object-wrap / list-materialize / nullable-primitive-out-param branches) and instead
  // renders `body` verbatim via the generic multi-line template, while `isSyncErrorCheckEnabled`
  // still controls the native DllImport's trailing `out IntPtr error` (ADR-061).
  val hasCustomBody: Boolean = false,
  // Overrides the DllImport's native parameter shape when it differs from [parameters] in count
  // or order (e.g. one nullable-primitive public parameter fans out into two adjacent native
  // ones, or a collection parameter's native shape is a single IntPtr list handle rather than its
  // public `IReadOnlyList<T>` type). Null (the default) keeps deriving the DllImport 1:1 from
  // [parameters], the existing behavior for every plain (cast-only) parameter shape. Only
  // consulted for class methods, whose DllImport is derived generically by
  // [CirClass.methodNativeImport]; static/extension methods build their own [CirDllImport]
  // directly and ignore this field.
  val nativeParameters: List<CirParameter>? = null,
) : CirMember

/**
 * ADR-090: the private `[DllImport]` extern's C# name. The numbered name a plan carried, else the
 * shipped `Native_$name`.
 *
 * The fallback serves hand-built CIR only: every production instance method comes from
 * `ForwardCirPlanProjection.classMethod`, which sets [CirMethod.externName]. A public name is a
 * rendered C# identifier and may be keyword-escaped (`@lock`), an extern identifier may not, so the
 * fallback refuses to derive from one rather than emit `Native_@lock` for the consumer to choke on.
 */
internal val CirMethod.resolvedExternName: String
  get() {
    if (externName != null) return externName
    require(!name.startsWith("@")) {
      "Method $name has no extern name and its public name is C#-escaped; " +
          "an extern identifier cannot be derived from it"
    }
    return "Native_$name"
  }

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
  // ADR-040 fixture gap: the property-side twin of [CirMethod.isOverride]/[CirMethod.isVirtual] —
  // a property never carried either concept before this feature, because no prior fixture had a
  // class property implementing an interface member and then a subclass overriding it again
  // (Cat.Nickname overriding Animal.Nickname's implementation of Pet.nickname).
  val isOverride: Boolean = false,
  val isVirtual: Boolean = false,
  // ADR-111 amendment (2026-09-11): the `new` modifier, for a sealed arm whose Kotlin `override`
  // *narrows* the base's declared type (`Empty.sides: Int` over `NestedShape.sides: Int?`). C#
  // forbids a covariant property override (CS1715), so the arm hides the base member instead of
  // overriding it: two C# members over one Kotlin property, each reading through its own export.
  // Only the sealed route sets it; an ordinary class's covariant override is not a shipped shape.
  val isNew: Boolean = false,
  // ADR-075 amendment (2026-09-10): the property-side twin of [CirMethod.isAbstract]: an
  // `abstract val`/`abstract var` the class declares without implementing. Renders bodiless
  // (`public abstract T Name { get; }`) so a subclass `override` compiles instead of CS0506.
  // Wins over [isVirtual]: `abstract virtual` is CS0503, `abstract override` is legal.
  val isAbstract: Boolean = false,
  // ADR-075 amendment (2026-09-11): false when this property is a declaration only, with no
  // Kotlin export behind it: an abstract property the class *inherits* from an exported interface
  // without implementing, spelled from the ADR-113 declaration catalog. The class-own abstract
  // case keeps its plan and therefore its import pair, so this stays true there. A `DllImport` for
  // an export nobody generated is what the ADR-055 contract check refuses.
  val hasNativeImport: Boolean = true,
  val isFlow: Boolean = false,
  // ADR-065: true when this is a StateFlow (or read-only MutableStateFlow view) property. Reuses
  // isFlow's whole legacy route (the _collect export + KotlinFlow substrate) and additionally
  // renders a KotlinStateFlow<T> wrapper plus a synchronous `_value` native import.
  val isStateFlow: Boolean = false,
  val flowElementType: String = "",
  val hasSyncErrorOut: Boolean = false,
  // ADR-067: true when the StateFlow member itself is nullable (`StateFlow<T>?` property). Renders
  // the property type as `KotlinStateFlow<T>?` and gates a `_has_value` presence-probe DllImport /
  // null-check before construction. False unless [isStateFlow].
  val isNullableMember: Boolean = false,
  // ADR-071: true when this is a MutableStateFlow property whose element/member is not nullable
  // (v1 scope) -- renders KotlinMutableStateFlow<T> instead of KotlinStateFlow<T> and additionally
  // carries the sibling `_set_value` DllImport named by [stateFlowSetValueNativeName].
  val isMutableStateFlow: Boolean = false,
  // The native method name (e.g. "Native_SetTreatCountValue") of the sibling `_set_value`
  // DllImport. Empty unless [isMutableStateFlow].
  val stateFlowSetValueNativeName: String = "",
  // Issue #38: true when this is a sealed-subclass property of a nullable non-String primitive
  // type (`Int?`), which crosses on the ADR-002 two-call pair (`_get_<p>_has_value` +
  // `_get_<p>_value`) instead of a single scalar slot. Read only by [renderSealedClass], which
  // then emits both DllImports in place of the single one. False everywhere else: the top-level
  // property path expresses the same shape through [extraNatives].
  val isNullablePrimitiveTwoCall: Boolean = false,
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
  // Overrides the DllImport's native parameter shape when it differs from [parameters] in count
  // or order (e.g. one nullable-primitive public parameter fans out into two adjacent native
  // ones, or a collection parameter's native shape is a single IntPtr list handle rather than its
  // public `IReadOnlyList<T>` type). Null (the default) keeps deriving the DllImport 1:1 from
  // [parameters], the existing behavior for every plain (cast-only) parameter shape.
  val nativeParameters: List<CirParameter>? = null,
)

data class CirParameter(
  val name: String,
  val type: String,
  // Native (DllImport) type; differs from type when the public C# type needs a cast
  // at the call site (e.g. enum method params: public type "Mood", native type "int").
  val nativeType: String = type,
  // Whether [type]'s underlying (non-nullable) C# spelling is a reference type. Only
  // constructor parameters currently rely on this (ADR-034's duplicate-constructor check must
  // strip a reference type's trailing "?" before comparing signatures, since C# nullable
  // reference annotations are not part of a method signature — unlike nullable *value* types,
  // where e.g. `int` and `int?` really are distinct overloads). Defaults to true so call sites
  // that never populate it keep today's "compare the raw rendered string" behavior.
  val isReferenceType: Boolean = true,
  // ADR-114: the C# expression building this parameter's native wire handle, for a collection
  // parameter on a legacy Flow/StateFlow or suspend route (`NugetMarshal.CreateList(kinds)`). The
  // renderer emits it immediately before the native call and disposes it in a `finally`; the
  // call passes `<name>Handle` instead of `<name>`. Null for every other parameter shape.
  val collectionCreate: String? = null,
  // ADR-122: the C# expression this parameter is passed to the native call as, when it is not just
  // the parameter name (`observation._handle` for a handle parameter on a legacy Flow/suspend
  // route). Deliberately separate from [collectionCreate]: that one also triggers the
  // create-then-`finally`-dispose block, which a *borrowed* handle must never get, since the
  // wrapper the caller holds owns it. Null for every other parameter shape.
  val nativeArgumentExpression: String? = null,
)

data class CirConst(
  val name: String,
  val type: String,
  val value: String,
) : CirMember

enum class CirVisibility {
  PUBLIC, PRIVATE
}
