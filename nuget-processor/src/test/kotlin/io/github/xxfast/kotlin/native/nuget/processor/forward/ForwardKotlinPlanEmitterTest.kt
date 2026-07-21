package io.github.xxfast.kotlin.native.nuget.processor.forward

import com.squareup.kotlinpoet.FileSpec
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class ForwardKotlinPlanEmitterTest {
  @Test
  fun `renders class method from ordered native ABI`() {
    val source: String = render(classMethodPlan())

    assertContains(source, "@CName(\"counter_increment\")")
    assertContains(source, "public fun export_counter_increment(")
    assertContains(source, "handle: COpaquePointer,")
    assertContains(source, "amount: Int,")
    assertContains(source, "errorOut: COpaquePointer?,")
    assertContains(source, "): Int = try {")
    assertContains(source, "handle.asStableRef<sample.Counter>().get().increment(amount)")
    assertContains(source, "errorOut.reinterpret<COpaquePointerVar>().pointed.value")
    assertContains(source, "  0")
  }

  @Test
  fun `renders Unit primitive receiver extension from plan invocation`() {
    val source: String = render(extensionPlan())

    assertContains(source, "@CName(\"int_adjust\")")
    assertContains(source, "public fun export_int_adjust(")
    assertContains(source, "`receiver`: Int,")
    assertContains(source, "amount: Int,")
    assertContains(source, "errorOut: COpaquePointer?,")
    assertContains(source, "receiver.adjust(amount)")
    assertContains(source, "try {")
    assertContains(source, "errorOut.reinterpret<COpaquePointerVar>().pointed.value")
  }

  @Test
  fun `enum parameter lowers through entries indexing`() {
    val source = render(
      methodWithParams(
        symbol = "sample.Vet.diagnose",
        export = "vet_diagnose",
        result = BridgeType.Unit,
        params = listOf("mood" to BridgeType.Enum("sample.Mood")),
      ),
    )
    assertContains(source, "sample.Mood.entries[mood]")
    assertContains(source, "handle.asStableRef<sample.Vet>().get().diagnose(sample.Mood.entries[mood])")
  }

  @Test
  fun `list parameter lowers through StableRef map cast`() {
    val list = BridgeType.Collection(CollectionKind.LIST, element = BridgeType.String)
    val source = render(
      methodWithParams(
        symbol = "sample.Patient.replaceTags",
        export = "patient_replaceTags",
        result = BridgeType.Unit,
        params = listOf("tags" to list),
      ),
    )
    assertContains(source, "tags.asStableRef<MutableList<Any?>>().get()")
    assertContains(source, ".map { it as kotlin.String }")
  }

  @Test
  fun `mutable list parameter lowers through mapTo`() {
    val list = BridgeType.Collection(CollectionKind.MUTABLE_LIST, element = BridgeType.Primitive(PrimitiveKind.INT))
    val source = render(
      methodWithParams(
        symbol = "sample.Patient.replaceScores",
        export = "patient_replaceScores",
        result = BridgeType.Unit,
        params = listOf("scores" to list),
      ),
    )
    assertContains(source, ".mapTo(mutableListOf()) { it as kotlin.Int }")
  }

  @Test
  fun `nullable object parameter uses safe asStableRef`() {
    val source = render(
      methodWithParams(
        symbol = "sample.Patient.befriend",
        export = "patient_befriend",
        result = BridgeType.Unit,
        params = listOf(
          "friend" to BridgeType.Nullable(BridgeType.ObjectHandle("sample.Friend")),
        ),
      ),
    )
    assertContains(source, "friend?.asStableRef<sample.Friend>()?.get()")
  }

  @Test
  fun `nullable primitive parameter fans out to HasValue ABI pair`() {
    // Planner emits two adjacent native params (ageHasValue, age) for one public nullable age.
    val error = errorParameter()
    val handle = ForwardAbiParameter(
      "handle",
      ForwardAbiWireType.POINTER,
      ForwardAbiDirection.IN,
      ForwardTransfer(
        "handle", BridgeType.ObjectHandle("sample.Patient"), ForwardFlow.INTO_KOTLIN,
        ForwardPassing.VALUE, ForwardOwnership.BORROWED, ForwardConversion.HANDLE_TO_STABLE_REF,
      ),
    )
    val hasValue = ForwardAbiParameter(
      "ageHasValue",
      ForwardAbiWireType.BOOLEAN,
      ForwardAbiDirection.IN,
      directTransfer("ageHasValue", BridgeType.Primitive(PrimitiveKind.BOOLEAN), ForwardFlow.INTO_KOTLIN),
    )
    val age = ForwardAbiParameter(
      "age",
      ForwardAbiWireType.INT32,
      ForwardAbiDirection.IN,
      directTransfer("age", BridgeType.Primitive(PrimitiveKind.INT), ForwardFlow.INTO_KOTLIN),
    )
    val call = ForwardNativeCall(
      "patient_setOptionalAge",
      ForwardAbiWireType.VOID,
      listOf(handle, hasValue, age, error),
    )
    val plan = ForwardCallablePlan(
      invocation = ForwardInvocation("sample.Patient.setOptionalAge"),
      publicSignature = ForwardPublicSignature(
        "SetOptionalAge",
        listOf(
          ForwardPublicParameter(
            "age",
            BridgeType.Nullable(BridgeType.Primitive(PrimitiveKind.INT)),
          ),
        ),
        BridgeType.Unit,
      ),
      evaluation = ForwardEvaluation.EXACTLY_ONCE,
      nativeExports = listOf(call),
      nativeImports = listOf(call),
      result = ForwardResultConvention(
        ForwardAbiWireType.VOID,
        directTransfer("result", BridgeType.Unit, ForwardFlow.OUT_OF_KOTLIN),
      ),
      errorSlot = error,
      helperRequirements = setOf(ForwardHelperRequirement.STABLE_REF),
    ).validate()

    val source = render(plan)
    assertContains(source, "ageHasValue: Boolean")
    assertContains(source, "age: Int")
    assertContains(source, "if (ageHasValue) age else null")
  }

  @Test
  fun `constructor origin invokes target type`() {
    val source = render(
      constructorPlan(
        symbol = "sample.Patient.<init>",
        export = "patient_create",
        target = "sample.Patient",
        params = listOf("name" to BridgeType.String),
      ),
    )
    assertContains(source, "StableRef.create(sample.Patient(name))")
  }

  @Test
  fun `copy origin invokes data class copy`() {
    val source = render(
      methodWithParams(
        symbol = "sample.Point.copy",
        export = "point_copy",
        result = BridgeType.ObjectHandle("sample.Point"),
        params = listOf(
          "x" to BridgeType.Primitive(PrimitiveKind.INT),
          "y" to BridgeType.Primitive(PrimitiveKind.INT),
        ),
        origin = ForwardCallableOrigin.COPY,
      ),
    )
    assertContains(source, "handle.asStableRef<sample.Point>().get().copy(x, y)")
  }

  @Test
  fun `companion origin uses target qualifier`() {
    val source = render(
      staticPlan(
        symbol = "sample.Patient.create",
        export = "patient_companion_create",
        origin = ForwardCallableOrigin.COMPANION,
        target = "sample.Patient",
        result = BridgeType.ObjectHandle("sample.Patient"),
      ),
    )
    assertContains(source, "StableRef.create(sample.Patient.create())")
  }

  @Test
  fun `ordinary value class result unboxes underlying property`() {
    val valueClass = BridgeType.ValueClass(
      qualifiedName = "sample.StoryCode",
      underlying = BridgeType.String,
      underlyingPropertyName = "value",
    )
    val error = errorParameter()
    val handle = ForwardAbiParameter(
      "handle",
      ForwardAbiWireType.POINTER,
      ForwardAbiDirection.IN,
      ForwardTransfer(
        "handle", BridgeType.ObjectHandle("sample.Newsroom"), ForwardFlow.INTO_KOTLIN,
        ForwardPassing.VALUE, ForwardOwnership.BORROWED, ForwardConversion.HANDLE_TO_STABLE_REF,
      ),
    )
    val call = ForwardNativeCall("newsroom_code", ForwardAbiWireType.POINTER, listOf(handle, error))
    val plan = ForwardCallablePlan(
      invocation = ForwardInvocation("sample.Newsroom.code", origin = ForwardCallableOrigin.CLASS),
      publicSignature = ForwardPublicSignature("Code", emptyList(), valueClass),
      evaluation = ForwardEvaluation.EXACTLY_ONCE,
      nativeExports = listOf(call),
      nativeImports = listOf(call),
      result = ForwardResultConvention(
        ForwardAbiWireType.POINTER,
        ForwardTransfer(
          "result", valueClass, ForwardFlow.OUT_OF_KOTLIN, ForwardPassing.VALUE,
          ForwardOwnership.MATERIALIZED, ForwardConversion.UNBOX_VALUE_CLASS,
        ),
      ),
      errorSlot = error,
      helperRequirements = setOf(
        ForwardHelperRequirement.STABLE_REF,
        ForwardHelperRequirement.UTF8,
        ForwardHelperRequirement.VALUE_CLASS,
      ),
    ).validate()

    val source = render(plan)
    assertContains(source, "): String = try {")
    assertContains(source, ".code().value")
  }

  @Test
  fun `value class constructor returns underlying with errorOut`() {
    val error = errorParameter()
    val value = ForwardAbiParameter(
      "value",
      ForwardAbiWireType.INT32,
      ForwardAbiDirection.IN,
      directTransfer("value", BridgeType.Primitive(PrimitiveKind.INT), ForwardFlow.INTO_KOTLIN),
    )
    val call = ForwardNativeCall("catid_create", ForwardAbiWireType.INT32, listOf(value, error))
    val plan = ForwardCallablePlan(
      invocation = ForwardInvocation(
        "sample.CatId.<init>",
        origin = ForwardCallableOrigin.VALUE_CLASS,
        target = "sample.CatId",
        receiver = "value",
      ),
      publicSignature = ForwardPublicSignature(
        "CatId",
        listOf(ForwardPublicParameter("value", BridgeType.Primitive(PrimitiveKind.INT))),
        BridgeType.Primitive(PrimitiveKind.INT),
      ),
      evaluation = ForwardEvaluation.EXACTLY_ONCE,
      nativeExports = listOf(call),
      nativeImports = listOf(call),
      result = ForwardResultConvention(
        ForwardAbiWireType.INT32,
        directTransfer("result", BridgeType.Primitive(PrimitiveKind.INT), ForwardFlow.OUT_OF_KOTLIN),
      ),
      errorSlot = error,
      helperRequirements = setOf(
        ForwardHelperRequirement.VALUE_CLASS,
        ForwardHelperRequirement.STABLE_REF,
      ),
    ).validate()

    val source = render(plan)
    assertContains(source, "sample.CatId(value).value")
    assertContains(source, "errorOut")
  }

  @Test
  fun `value class property without errorOut returns underlying member`() {
    val value = ForwardAbiParameter(
      "value",
      ForwardAbiWireType.INT32,
      ForwardAbiDirection.IN,
      directTransfer("value", BridgeType.Primitive(PrimitiveKind.INT), ForwardFlow.INTO_KOTLIN),
    )
    val call = ForwardNativeCall("catid_get_value", ForwardAbiWireType.INT32, listOf(value))
    val plan = ForwardCallablePlan(
      invocation = ForwardInvocation(
        "sample.CatId.value",
        origin = ForwardCallableOrigin.VALUE_CLASS,
        target = "sample.CatId#property",
      ),
      publicSignature = ForwardPublicSignature(
        "Value",
        emptyList(),
        BridgeType.Primitive(PrimitiveKind.INT),
      ),
      evaluation = ForwardEvaluation.EXACTLY_ONCE,
      nativeExports = listOf(call),
      nativeImports = listOf(call),
      result = ForwardResultConvention(
        ForwardAbiWireType.INT32,
        directTransfer("result", BridgeType.Primitive(PrimitiveKind.INT), ForwardFlow.OUT_OF_KOTLIN),
      ),
      errorSlot = null,
      helperRequirements = setOf(ForwardHelperRequirement.VALUE_CLASS),
    ).validate()

    val source = render(plan)
    assertContains(source, "sample.CatId(value).value")
    assertFalse(source.contains("errorOut"))
  }

  @Test
  fun `value class Unit method without errorOut is a statement`() {
    val value = ForwardAbiParameter(
      "value",
      ForwardAbiWireType.INT32,
      ForwardAbiDirection.IN,
      directTransfer("value", BridgeType.Primitive(PrimitiveKind.INT), ForwardFlow.INTO_KOTLIN),
    )
    val call = ForwardNativeCall("catid_touch", ForwardAbiWireType.VOID, listOf(value))
    val plan = ForwardCallablePlan(
      invocation = ForwardInvocation(
        "sample.CatId.touch",
        origin = ForwardCallableOrigin.VALUE_CLASS,
        target = "sample.CatId",
      ),
      publicSignature = ForwardPublicSignature("Touch", emptyList(), BridgeType.Unit),
      evaluation = ForwardEvaluation.EXACTLY_ONCE,
      nativeExports = listOf(call),
      nativeImports = listOf(call),
      result = ForwardResultConvention(
        ForwardAbiWireType.VOID,
        directTransfer("result", BridgeType.Unit, ForwardFlow.OUT_OF_KOTLIN),
      ),
      errorSlot = null,
      helperRequirements = setOf(ForwardHelperRequirement.VALUE_CLASS),
    ).validate()

    val source = render(plan)
    assertContains(source, "sample.CatId(value).touch()")
    assertFalse(source.contains("errorOut"))
    assertFalse(source.contains("return try"))
  }

  @Test
  fun `object handle extension receiver unboxes before call`() {
    val receiver = ForwardAbiParameter(
      "receiver",
      ForwardAbiWireType.POINTER,
      ForwardAbiDirection.IN,
      ForwardTransfer(
        "receiver", BridgeType.ObjectHandle("sample.Patient"), ForwardFlow.INTO_KOTLIN,
        ForwardPassing.VALUE, ForwardOwnership.BORROWED, ForwardConversion.HANDLE_TO_STABLE_REF,
      ),
    )
    val error = errorParameter()
    val call = ForwardNativeCall(
      "patient_shout",
      ForwardAbiWireType.VOID,
      listOf(receiver, error),
    )
    val plan = ForwardCallablePlan(
      invocation = ForwardInvocation("sample.shout", origin = ForwardCallableOrigin.EXTENSION),
      publicSignature = ForwardPublicSignature("Shout", emptyList(), BridgeType.Unit),
      evaluation = ForwardEvaluation.EXACTLY_ONCE,
      nativeExports = listOf(call),
      nativeImports = listOf(call),
      result = ForwardResultConvention(
        ForwardAbiWireType.VOID,
        directTransfer("result", BridgeType.Unit, ForwardFlow.OUT_OF_KOTLIN),
      ),
      errorSlot = error,
      helperRequirements = setOf(ForwardHelperRequirement.STABLE_REF),
    ).validate()

    val source = render(plan)
    assertContains(source, "receiver.asStableRef<sample.Patient>().get().shout()")
  }

  private fun render(plan: ForwardCallablePlan): String {
    val builder: FileSpec.Builder = FileSpec.builder("sample", "Exports")
    builder.addForwardKotlinPlanExport(plan)
    return builder.build().toString()
  }

  private fun classMethodPlan(): ForwardCallablePlan = directPlan(
    symbol = "sample.Counter.increment",
    exportName = "counter_increment",
    receiver = ForwardAbiParameter(
      name = "handle",
      wireType = ForwardAbiWireType.POINTER,
      direction = ForwardAbiDirection.IN,
      transfer = ForwardTransfer(
        subject = "handle",
        type = BridgeType.ObjectHandle("sample.Counter"),
        flow = ForwardFlow.INTO_KOTLIN,
        passing = ForwardPassing.VALUE,
        ownership = ForwardOwnership.BORROWED,
        conversion = ForwardConversion.HANDLE_TO_STABLE_REF,
      ),
    ),
    result = BridgeType.Primitive(PrimitiveKind.INT),
  )

  private fun extensionPlan(): ForwardCallablePlan = directPlan(
    symbol = "sample.adjust",
    exportName = "int_adjust",
    receiver = ForwardAbiParameter(
      name = "receiver",
      wireType = ForwardAbiWireType.INT32,
      direction = ForwardAbiDirection.IN,
      transfer = directTransfer("receiver", BridgeType.Primitive(PrimitiveKind.INT), ForwardFlow.INTO_KOTLIN),
    ),
    result = BridgeType.Unit,
  )

  private fun methodWithParams(
    symbol: String,
    export: String,
    result: BridgeType,
    params: List<Pair<String, BridgeType>>,
    origin: ForwardCallableOrigin = ForwardCallableOrigin.CLASS,
  ): ForwardCallablePlan {
    val error = errorParameter()
    val owner = symbol.substringBeforeLast('.')
    val handle = ForwardAbiParameter(
      "handle",
      ForwardAbiWireType.POINTER,
      ForwardAbiDirection.IN,
      ForwardTransfer(
        "handle", BridgeType.ObjectHandle(owner), ForwardFlow.INTO_KOTLIN,
        ForwardPassing.VALUE, ForwardOwnership.BORROWED, ForwardConversion.HANDLE_TO_STABLE_REF,
      ),
    )
    val abiParams = params.map { (name, type) -> publicParamToAbi(name, type) }
    val wire = when (result) {
      BridgeType.Unit -> ForwardAbiWireType.VOID
      is BridgeType.ObjectHandle -> ForwardAbiWireType.POINTER
      is BridgeType.Primitive -> ForwardAbiWireType.INT32
      else -> error("test helper needs wire for $result")
    }
    val call = ForwardNativeCall(export, wire, listOf(handle) + abiParams + error)
    val transfer = when (result) {
      BridgeType.Unit -> directTransfer("result", BridgeType.Unit, ForwardFlow.OUT_OF_KOTLIN)
      is BridgeType.ObjectHandle -> ForwardTransfer(
        "result", result, ForwardFlow.OUT_OF_KOTLIN, ForwardPassing.VALUE,
        ForwardOwnership.OWNED_HANDLE, ForwardConversion.STABLE_REF_TO_HANDLE,
      )
      else -> directTransfer("result", result, ForwardFlow.OUT_OF_KOTLIN)
    }
    return ForwardCallablePlan(
      invocation = ForwardInvocation(symbol, origin = origin),
      publicSignature = ForwardPublicSignature(
        symbol.substringAfterLast('.').replaceFirstChar { it.uppercase() },
        params.map { (n, t) -> ForwardPublicParameter(n, t) },
        result,
      ),
      evaluation = ForwardEvaluation.EXACTLY_ONCE,
      nativeExports = listOf(call),
      nativeImports = listOf(call),
      result = ForwardResultConvention(wire, transfer),
      errorSlot = error,
      cleanup = if (result is BridgeType.ObjectHandle) {
        listOf(ForwardCleanup("result", ForwardCleanupKind.DISPOSE_STABLE_REF))
      } else {
        emptyList()
      },
      helperRequirements = buildSet {
        add(ForwardHelperRequirement.STABLE_REF)
        if (params.any { it.second is BridgeType.Collection }) add(ForwardHelperRequirement.COLLECTION)
        if (params.any { it.second is BridgeType.Enum }) add(ForwardHelperRequirement.ENUM_ORDINAL)
      },
    ).validate()
  }

  private fun constructorPlan(
    symbol: String,
    export: String,
    target: String,
    params: List<Pair<String, BridgeType>>,
  ): ForwardCallablePlan {
    val error = errorParameter()
    val abiParams = params.map { (name, type) -> publicParamToAbi(name, type) }
    val result = BridgeType.ObjectHandle(target)
    val call = ForwardNativeCall(export, ForwardAbiWireType.POINTER, abiParams + error)
    return ForwardCallablePlan(
      invocation = ForwardInvocation(symbol, origin = ForwardCallableOrigin.CONSTRUCTOR, target = target),
      publicSignature = ForwardPublicSignature(
        target.substringAfterLast('.'),
        params.map { (n, t) -> ForwardPublicParameter(n, t) },
        result,
      ),
      evaluation = ForwardEvaluation.EXACTLY_ONCE,
      nativeExports = listOf(call),
      nativeImports = listOf(call),
      result = ForwardResultConvention(
        ForwardAbiWireType.POINTER,
        ForwardTransfer(
          "result", result, ForwardFlow.OUT_OF_KOTLIN, ForwardPassing.VALUE,
          ForwardOwnership.OWNED_HANDLE, ForwardConversion.STABLE_REF_TO_HANDLE,
        ),
      ),
      errorSlot = error,
      cleanup = listOf(ForwardCleanup("result", ForwardCleanupKind.DISPOSE_STABLE_REF)),
      helperRequirements = setOf(ForwardHelperRequirement.STABLE_REF, ForwardHelperRequirement.UTF8),
    ).validate()
  }

  private fun staticPlan(
    symbol: String,
    export: String,
    origin: ForwardCallableOrigin,
    target: String,
    result: BridgeType.ObjectHandle,
  ): ForwardCallablePlan {
    val error = errorParameter()
    val call = ForwardNativeCall(export, ForwardAbiWireType.POINTER, listOf(error))
    return ForwardCallablePlan(
      invocation = ForwardInvocation(symbol, origin = origin, target = target),
      publicSignature = ForwardPublicSignature("Create", emptyList(), result),
      evaluation = ForwardEvaluation.EXACTLY_ONCE,
      nativeExports = listOf(call),
      nativeImports = listOf(call),
      result = ForwardResultConvention(
        ForwardAbiWireType.POINTER,
        ForwardTransfer(
          "result", result, ForwardFlow.OUT_OF_KOTLIN, ForwardPassing.VALUE,
          ForwardOwnership.OWNED_HANDLE, ForwardConversion.STABLE_REF_TO_HANDLE,
        ),
      ),
      errorSlot = error,
      cleanup = listOf(ForwardCleanup("result", ForwardCleanupKind.DISPOSE_STABLE_REF)),
      helperRequirements = setOf(ForwardHelperRequirement.STABLE_REF),
    ).validate()
  }

  private fun publicParamToAbi(name: String, type: BridgeType): ForwardAbiParameter {
    val wire = when (type) {
      is BridgeType.Primitive -> ForwardAbiWireType.INT32
      is BridgeType.Enum -> ForwardAbiWireType.INT32
      BridgeType.String -> ForwardAbiWireType.STRING
      is BridgeType.ObjectHandle, is BridgeType.Collection -> ForwardAbiWireType.POINTER
      is BridgeType.Nullable -> when (type.type) {
        is BridgeType.Primitive -> ForwardAbiWireType.INT32
        is BridgeType.ObjectHandle -> ForwardAbiWireType.POINTER
        BridgeType.String -> ForwardAbiWireType.STRING
        else -> error("no wire for $type")
      }
      else -> error("no wire for $type")
    }
    val conversion = when (val unwrapped = if (type is BridgeType.Nullable) type.type else type) {
      is BridgeType.Enum -> ForwardConversion.ORDINAL_TO_ENUM
      is BridgeType.ObjectHandle -> ForwardConversion.HANDLE_TO_STABLE_REF
      is BridgeType.Collection -> ForwardConversion.HANDLE_TO_COLLECTION
      BridgeType.String -> ForwardConversion.STRING_TO_UTF8
      else -> ForwardConversion.DIRECT
    }
    return ForwardAbiParameter(
      name,
      wire,
      ForwardAbiDirection.IN,
      ForwardTransfer(
        name, type, ForwardFlow.INTO_KOTLIN, ForwardPassing.VALUE,
        ForwardOwnership.BORROWED, conversion,
      ),
    )
  }

  private fun directPlan(
    symbol: String,
    exportName: String,
    receiver: ForwardAbiParameter,
    result: BridgeType,
  ): ForwardCallablePlan {
    val error = errorParameter()
    val amount = ForwardAbiParameter(
      name = "amount",
      wireType = ForwardAbiWireType.INT32,
      direction = ForwardAbiDirection.IN,
      transfer = directTransfer("amount", BridgeType.Primitive(PrimitiveKind.INT), ForwardFlow.INTO_KOTLIN),
    )
    val call = ForwardNativeCall(
      exportName = exportName,
      result = if (result == BridgeType.Unit) ForwardAbiWireType.VOID else ForwardAbiWireType.INT32,
      parameters = listOf(receiver, amount, error),
    )
    return ForwardCallablePlan(
      invocation = ForwardInvocation(
        symbol = symbol,
        origin = if (receiver.name == "handle") ForwardCallableOrigin.CLASS else ForwardCallableOrigin.EXTENSION,
      ),
      publicSignature = ForwardPublicSignature(
        name = symbol.substringAfterLast('.'),
        parameters = listOf(ForwardPublicParameter("amount", BridgeType.Primitive(PrimitiveKind.INT))),
        result = result,
      ),
      evaluation = ForwardEvaluation.EXACTLY_ONCE,
      nativeExports = listOf(call),
      nativeImports = listOf(call),
      result = ForwardResultConvention(
        wireType = call.result,
        transfer = directTransfer("result", result, ForwardFlow.OUT_OF_KOTLIN),
      ),
      errorSlot = error,
      helperRequirements = setOf(ForwardHelperRequirement.STABLE_REF),
    ).validate()
  }

  private fun errorParameter(): ForwardAbiParameter = ForwardAbiParameter(
    name = "errorOut",
    wireType = ForwardAbiWireType.POINTER,
    direction = ForwardAbiDirection.OUT,
    transfer = ForwardTransfer(
      subject = "error",
      type = BridgeType.ObjectHandle("kotlin.Throwable"),
      flow = ForwardFlow.OUT_OF_KOTLIN,
      passing = ForwardPassing.OUT,
      ownership = ForwardOwnership.BORROWED,
      conversion = ForwardConversion.STABLE_REF_TO_HANDLE,
    ),
  )

  private fun directTransfer(
    subject: String,
    type: BridgeType,
    flow: ForwardFlow,
  ): ForwardTransfer = ForwardTransfer(
    subject = subject,
    type = type,
    flow = flow,
    passing = ForwardPassing.VALUE,
    ownership = ForwardOwnership.BORROWED,
    conversion = ForwardConversion.DIRECT,
  )
}
