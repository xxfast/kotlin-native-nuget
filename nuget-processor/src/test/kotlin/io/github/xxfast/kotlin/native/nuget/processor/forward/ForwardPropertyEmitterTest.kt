package io.github.xxfast.kotlin.native.nuget.processor.forward

import com.squareup.kotlinpoet.FileSpec
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirClass
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirFile
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirNamespace
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirRenderer
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirStaticClass
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

/**
 * Property plan dual projection: KotlinPoet + CIR → C# for ordinary property types and positions.
 * Specialized protocol properties (Flow / StateFlow) stay out of scope.
 */
class ForwardPropertyEmitterTest {

  @Test
  fun `class string property get set unboxes and materializes utf8`() {
    val plan = classProperty(
      name = "label",
      type = BridgeType.String,
      wire = ForwardAbiWireType.POINTER,
      outConversion = ForwardConversion.UTF8_TO_STRING,
      inConversion = ForwardConversion.STRING_TO_UTF8,
      mutable = true,
    )

    val kotlin = renderKotlin(plan)
    assertContains(kotlin, "@CName(\"patient_get_label\")")
    assertContains(kotlin, "@CName(\"patient_set_label\")")
    assertContains(kotlin, "handle.asStableRef<sample.Patient>().get().label")
    assertContains(kotlin, "label = value")

    val csharp = renderClassProperty(plan)
    assertContains(csharp, "public string Label")
    assertContains(csharp, "Marshal.PtrToStringUTF8(nativeResult)!")
    assertContains(csharp, "Native_Set_label(_handle, value, out IntPtr error)")
  }

  @Test
  fun `class enum property get set uses ordinal both ways`() {
    val plan = classProperty(
      name = "mood",
      type = BridgeType.Enum("sample.Mood"),
      wire = ForwardAbiWireType.INT32,
      outConversion = ForwardConversion.ENUM_TO_ORDINAL,
      inConversion = ForwardConversion.ORDINAL_TO_ENUM,
      mutable = true,
    )

    val kotlin = renderKotlin(plan)
    assertContains(kotlin, "mood.ordinal")
    assertContains(kotlin, "mood = sample.Mood.entries[value]")

    val csharp = renderClassProperty(plan)
    assertContains(csharp, "public Mood Mood")
    assertContains(csharp, "return (Mood)nativeResult;")
    assertContains(csharp, "Native_Set_mood(_handle, (int)value, out IntPtr error)")
  }

  @Test
  fun `class object property get set round-trips handles`() {
    val plan = classProperty(
      name = "friend",
      type = BridgeType.ObjectHandle("sample.Friend"),
      wire = ForwardAbiWireType.POINTER,
      outConversion = ForwardConversion.STABLE_REF_TO_HANDLE,
      inConversion = ForwardConversion.HANDLE_TO_STABLE_REF,
      mutable = true,
    )

    val kotlin = renderKotlin(plan)
    assertContains(kotlin, "StableRef.create(")
    assertContains(kotlin, "friend = value.asStableRef<sample.Friend>().get()")

    val csharp = renderClassProperty(plan)
    assertContains(csharp, "public Friend Friend")
    assertContains(csharp, "return new Friend(nativeResult);")
    assertContains(csharp, "Native_Set_friend(_handle, value._handle, out IntPtr error)")
  }

  @Test
  fun `class list property getter materializes through NugetListNative`() {
    val plan = classProperty(
      name = "tags",
      type = BridgeType.Collection(CollectionKind.LIST, element = BridgeType.String),
      wire = ForwardAbiWireType.POINTER,
      outConversion = ForwardConversion.COLLECTION_TO_HANDLE,
      inConversion = null,
      mutable = false,
      helpers = setOf(ForwardHelperRequirement.COLLECTION),
    )

    val kotlin = renderKotlin(plan)
    assertContains(kotlin, "StableRef.create(")
    assertFalse(kotlin.contains("@CName(\"patient_set_tags\")"))

    val csharp = renderClassProperty(plan)
    assertContains(csharp, "IReadOnlyList<string> Tags")
    assertContains(csharp, "NugetListNative.Count(nativeResult)")
    assertContains(csharp, "result.AsReadOnly()")
  }

  @Test
  fun `class map property getter materializes through NugetMapNative`() {
    val plan = classProperty(
      name = "scores",
      type = BridgeType.Collection(
        CollectionKind.MAP,
        key = BridgeType.String,
        value = BridgeType.Primitive(PrimitiveKind.INT),
      ),
      wire = ForwardAbiWireType.POINTER,
      outConversion = ForwardConversion.COLLECTION_TO_HANDLE,
      inConversion = null,
      mutable = false,
      helpers = setOf(ForwardHelperRequirement.COLLECTION),
    )

    val csharp = renderClassProperty(plan)
    assertContains(csharp, "IReadOnlyDictionary<string, int> Scores")
    assertContains(csharp, "NugetMapNative.Count(nativeResult)")
  }

  @Test
  fun `class set property getter materializes through NugetSetNative`() {
    val plan = classProperty(
      name = "labels",
      type = BridgeType.Collection(CollectionKind.SET, element = BridgeType.String),
      wire = ForwardAbiWireType.POINTER,
      outConversion = ForwardConversion.COLLECTION_TO_HANDLE,
      inConversion = null,
      mutable = false,
      helpers = setOf(ForwardHelperRequirement.COLLECTION),
    )

    val csharp = renderClassProperty(plan)
    assertContains(csharp, "IReadOnlySet<string> Labels")
    assertContains(csharp, "NugetSetNative.Count(nativeResult)")
  }

  @Test
  fun `nullable string property getter returns nullable string`() {
    val plan = classProperty(
      name = "nickname",
      type = BridgeType.Nullable(BridgeType.String),
      wire = ForwardAbiWireType.POINTER,
      outConversion = ForwardConversion.UTF8_TO_STRING,
      inConversion = ForwardConversion.STRING_TO_UTF8,
      mutable = true,
    )

    val kotlin = renderKotlin(plan)
    assertContains(kotlin, "): String? = try {")
    assertContains(kotlin, "  null")

    val csharp = renderClassProperty(plan)
    assertContains(csharp, "public string? Nickname")
    assertContains(csharp, "return Marshal.PtrToStringUTF8(nativeResult);")
  }

  @Test
  fun `nullable object property getter returns null for zero handle`() {
    val plan = classProperty(
      name = "buddy",
      type = BridgeType.Nullable(BridgeType.ObjectHandle("sample.Friend")),
      wire = ForwardAbiWireType.POINTER,
      outConversion = ForwardConversion.STABLE_REF_TO_HANDLE,
      inConversion = ForwardConversion.HANDLE_TO_STABLE_REF,
      mutable = true,
    )

    val kotlin = renderKotlin(plan)
    assertContains(kotlin, "if (result == null) null else")
    assertContains(kotlin, "buddy = value?.asStableRef<sample.Friend>()?.get()")

    val csharp = renderClassProperty(plan)
    assertContains(csharp, "public Friend? Buddy")
    assertContains(csharp, "return nativeResult == IntPtr.Zero ? null : new Friend(nativeResult);")
  }

  @Test
  fun `top-level property uses bare name access and static C sharp members`() {
    val plan = staticProperty(
      position = ForwardPropertyPosition.TOP_LEVEL,
      owner = null,
      name = "counter",
      type = BridgeType.Primitive(PrimitiveKind.INT),
      getExport = "get_counter",
      setExport = "set_counter",
    )

    val kotlin = renderKotlin(plan)
    assertContains(kotlin, "@CName(\"get_counter\")")
    assertContains(kotlin, "try {\n  counter")
    assertContains(kotlin, "counter = value")

    val csharp = renderStatic(plan)
    assertContains(csharp, "public static int Counter")
    assertContains(csharp, "Native_GetCounter")
    assertContains(csharp, "Native_SetCounter")
  }

  @Test
  fun `companion property prefixes owner on Kotlin access`() {
    val plan = staticProperty(
      position = ForwardPropertyPosition.COMPANION,
      owner = "sample.Patient",
      name = "defaultAge",
      type = BridgeType.Primitive(PrimitiveKind.INT),
      getExport = "patient_companion_get_defaultAge",
      setExport = "patient_companion_set_defaultAge",
    )

    val kotlin = renderKotlin(plan)
    assertContains(kotlin, "sample.Patient.defaultAge")
    assertContains(kotlin, "sample.Patient.defaultAge = value")
  }

  @Test
  fun `extension property on primitive receiver uses this-style C sharp methods`() {
    val plan = extensionProperty(
      receiver = BridgeType.Primitive(PrimitiveKind.INT),
      name = "doubled",
      type = BridgeType.Primitive(PrimitiveKind.INT),
      getExport = "int_get_doubled",
      setExport = null,
    )

    val kotlin = renderKotlin(plan)
    assertContains(kotlin, "`receiver`: Int")
    assertContains(kotlin, "receiver.doubled")

    val csharp = renderExtension(plan)
    assertContains(csharp, "public static int GetDoubled(this int receiver)")
    assertContains(csharp, "Native_IntGetDoubled(receiver, out IntPtr error)")
  }

  @Test
  fun `extension property on object receiver unboxes handle`() {
    val plan = extensionProperty(
      receiver = BridgeType.ObjectHandle("sample.Patient"),
      name = "alias",
      type = BridgeType.String,
      getExport = "patient_get_alias",
      setExport = "patient_set_alias",
    )

    val kotlin = renderKotlin(plan)
    assertContains(kotlin, "receiver.asStableRef<sample.Patient>().get().alias")
    assertContains(kotlin, "alias = value")

    val csharp = renderExtension(plan)
    assertContains(csharp, "public static string GetAlias(this Patient receiver)")
    assertContains(csharp, "Native_PatientGetAlias(receiver._handle, out IntPtr error)")
    assertContains(csharp, "public static void SetAlias(this Patient receiver, string value)")
  }

  // -- builders ---------------------------------------------------------------

  private fun classProperty(
    name: String,
    type: BridgeType,
    wire: ForwardAbiWireType,
    outConversion: ForwardConversion?,
    inConversion: ForwardConversion?,
    mutable: Boolean,
    helpers: Set<ForwardHelperRequirement> = emptySet(),
  ): ForwardPropertyPlan {
    val handle = handleParam()
    val error = errorParam()
    val getCall = ForwardNativeCall(
      "patient_get_$name",
      wire,
      listOf(handle, error),
    )
    val setter: ForwardPropertySetter? = if (!mutable) null else {
      val value = ForwardAbiParameter(
        "value",
        if (type.unwrap() == BridgeType.String) ForwardAbiWireType.STRING else wire,
        ForwardAbiDirection.IN,
        ForwardTransfer(
          "value", type, ForwardFlow.INTO_KOTLIN, ForwardPassing.VALUE,
          ForwardOwnership.BORROWED, inConversion,
        ),
      )
      ForwardPropertySetter.Direct(
        ForwardNativeCall("patient_set_$name", ForwardAbiWireType.VOID, listOf(handle, value, error)),
      )
    }
    return ForwardPropertyPlan(
      symbol = "sample.Patient.$name",
      position = ForwardPropertyPosition.CLASS,
      receiver = ForwardPropertyReceiver.Handle("sample.Patient"),
      kotlinName = name,
      publicName = name.replaceFirstChar { it.uppercase() },
      type = type,
      getter = ForwardPropertyGetter.Direct(getCall),
      setter = setter,
      helperRequirements = helpers,
    ).validate()
  }

  private fun staticProperty(
    position: ForwardPropertyPosition,
    owner: String?,
    name: String,
    type: BridgeType,
    getExport: String,
    setExport: String,
  ): ForwardPropertyPlan {
    val error = errorParam()
    val getCall = ForwardNativeCall(getExport, ForwardAbiWireType.INT32, listOf(error))
    val value = ForwardAbiParameter(
      "value",
      ForwardAbiWireType.INT32,
      ForwardAbiDirection.IN,
      ForwardTransfer(
        "value", type, ForwardFlow.INTO_KOTLIN, ForwardPassing.VALUE,
        ForwardOwnership.BORROWED, ForwardConversion.DIRECT,
      ),
    )
    return ForwardPropertyPlan(
      symbol = "sample.$name",
      position = position,
      receiver = ForwardPropertyReceiver.Static(owner),
      kotlinName = name,
      publicName = name.replaceFirstChar { it.uppercase() },
      type = type,
      getter = ForwardPropertyGetter.Direct(getCall),
      setter = ForwardPropertySetter.Direct(
        ForwardNativeCall(setExport, ForwardAbiWireType.VOID, listOf(value, error)),
      ),
    ).validate()
  }

  private fun extensionProperty(
    receiver: BridgeType,
    name: String,
    type: BridgeType,
    getExport: String,
    setExport: String?,
  ): ForwardPropertyPlan {
    val error = errorParam()
    val receiverWire = when (receiver) {
      is BridgeType.ObjectHandle -> ForwardAbiWireType.POINTER
      is BridgeType.Primitive -> ForwardAbiWireType.INT32
      BridgeType.String -> ForwardAbiWireType.STRING
      else -> error("unsupported extension receiver $receiver")
    }
    val receiverParam = ForwardAbiParameter(
      "receiver",
      receiverWire,
      ForwardAbiDirection.IN,
      ForwardTransfer(
        "receiver", receiver, ForwardFlow.INTO_KOTLIN, ForwardPassing.VALUE,
        ForwardOwnership.BORROWED,
        if (receiver is BridgeType.ObjectHandle) ForwardConversion.HANDLE_TO_STABLE_REF
        else ForwardConversion.DIRECT,
      ),
    )
    val resultWire = when (type) {
      BridgeType.String -> ForwardAbiWireType.POINTER
      is BridgeType.Primitive -> ForwardAbiWireType.INT32
      else -> error("unsupported extension result $type")
    }
    val getCall = ForwardNativeCall(getExport, resultWire, listOf(receiverParam, error))
    val setter: ForwardPropertySetter? = setExport?.let { export ->
      val valueWire = if (type == BridgeType.String) ForwardAbiWireType.STRING else resultWire
      val value = ForwardAbiParameter(
        "value",
        valueWire,
        ForwardAbiDirection.IN,
        ForwardTransfer(
          "value", type, ForwardFlow.INTO_KOTLIN, ForwardPassing.VALUE,
          ForwardOwnership.BORROWED,
          if (type == BridgeType.String) ForwardConversion.STRING_TO_UTF8 else ForwardConversion.DIRECT,
        ),
      )
      ForwardPropertySetter.Direct(
        ForwardNativeCall(export, ForwardAbiWireType.VOID, listOf(receiverParam, value, error)),
      )
    }
    return ForwardPropertyPlan(
      symbol = "sample.ext.$name",
      position = ForwardPropertyPosition.EXTENSION,
      receiver = ForwardPropertyReceiver.Value(receiver),
      kotlinName = name,
      publicName = name.replaceFirstChar { it.uppercase() },
      type = type,
      getter = ForwardPropertyGetter.Direct(getCall),
      setter = setter,
    ).validate()
  }

  private fun renderKotlin(plan: ForwardPropertyPlan): String {
    val builder = FileSpec.builder("sample", "Exports")
    builder.addForwardPropertyPlanExports(plan)
    return builder.build().toString()
  }

  private fun renderClassProperty(plan: ForwardPropertyPlan): String =
    CirRenderer().render(
      CirFile(
        namespaces = listOf(
          CirNamespace(
            "Sample",
            listOf(
              CirClass(
                name = "Patient",
                libraryName = "sample",
                nativePrefix = "patient",
                constructor = null,
                properties = listOf(ForwardCirPropertyProjection.classProperty(plan)),
                methods = emptyList(),
                disposable = false,
                hasInternalHandleConstructor = true,
              ),
            ),
          ),
        ),
      ),
    )

  private fun renderStatic(plan: ForwardPropertyPlan): String =
    CirRenderer().render(
      CirFile(
        namespaces = listOf(
          CirNamespace(
            "Sample",
            listOf(CirStaticClass("Globals", ForwardCirPropertyProjection.staticProperty(plan, "sample"))),
          ),
        ),
      ),
    )

  private fun renderExtension(plan: ForwardPropertyPlan): String =
    CirRenderer().render(
      CirFile(
        namespaces = listOf(
          CirNamespace(
            "Sample",
            listOf(CirStaticClass("Extensions", ForwardCirPropertyProjection.extension(plan, "sample"))),
          ),
        ),
      ),
    )

  private fun handleParam(): ForwardAbiParameter = ForwardAbiParameter(
    "handle",
    ForwardAbiWireType.POINTER,
    ForwardAbiDirection.IN,
    ForwardTransfer(
      "handle", BridgeType.ObjectHandle("sample.Patient"), ForwardFlow.INTO_KOTLIN,
      ForwardPassing.VALUE, ForwardOwnership.BORROWED, ForwardConversion.HANDLE_TO_STABLE_REF,
    ),
  )

  private fun errorParam(): ForwardAbiParameter = ForwardAbiParameter(
    "errorOut",
    ForwardAbiWireType.POINTER,
    ForwardAbiDirection.OUT,
    ForwardTransfer(
      "error", BridgeType.ObjectHandle("kotlin.Throwable"), ForwardFlow.OUT_OF_KOTLIN,
      ForwardPassing.OUT, ForwardOwnership.BORROWED, ForwardConversion.STABLE_REF_TO_HANDLE,
    ),
  )

  private fun BridgeType.unwrap(): BridgeType =
    if (this is BridgeType.Nullable) type else this
}
