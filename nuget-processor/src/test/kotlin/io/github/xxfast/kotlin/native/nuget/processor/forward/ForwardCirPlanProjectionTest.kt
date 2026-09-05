package io.github.xxfast.kotlin.native.nuget.processor.forward

import io.github.xxfast.kotlin.native.nuget.processor.cir.CirClass
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirConstructor
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirDllImport
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirFile
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirMethod
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirNamespace
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirRenderer
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirStaticClass
import io.github.xxfast.kotlin.native.nuget.processor.cir.ordinaryNativeImports
import kotlin.test.Test
import kotlin.test.assertEquals

class ForwardCirPlanProjectionTest {
  @Test
  fun `class method import and wrapper derive from plan`() {
    val plan: ForwardCallablePlan = plan(
      symbol = "sample.Counter.increment",
      exportName = "counter_increment",
      receiver = "handle",
      result = BridgeType.Primitive(PrimitiveKind.INT),
      parameters = listOf("amount" to BridgeType.Primitive(PrimitiveKind.INT)),
    )

    val method: CirMethod = ForwardCirPlanProjection.classMethod(plan, "counter", isOverride = false)
    val import: CirDllImport = CirClass(
      name = "Counter",
      libraryName = "sample",
      nativePrefix = "counter",
      constructor = null,
      properties = emptyList(),
      methods = listOf(method),
    ).ordinaryNativeImports().single { it.name == "Native_Increment" }

    assertEquals("Increment", method.name)
    assertEquals("int", method.returnType)
    assertEquals("increment", method.nativeName)
    assertEquals(listOf("amount"), method.parameters.map { it.name })
    assertEquals("counter_increment", import.entryPoint)
    assertEquals("int", import.returnType)
    assertEquals(listOf("IntPtr", "int"), import.parameters.map { it.nativeType })
    assertEquals(true, import.hasSyncErrorOut)
    val rendered: String = CirRenderer().render(
      CirFile(
        namespaces = listOf(
          CirNamespace(
            "Sample",
            listOf(
              CirClass(
                name = "Counter",
                libraryName = "sample",
                nativePrefix = "counter",
                constructor = null,
                properties = emptyList(),
                methods = listOf(method),
              ),
            ),
          ),
        ),
      ),
    )
    assertEquals(true, rendered.contains("Native_Increment(IntPtr handle, int amount, out IntPtr error)"))
    assertEquals(true, rendered.contains("Native_Increment(_handle, amount, out IntPtr error)"))
  }

  @Test
  fun `primitive extension import and wrapper derive receiver and arguments from plan`() {
    val plan: ForwardCallablePlan = plan(
      symbol = "sample.scale",
      exportName = "int_scale",
      receiver = "receiver",
      result = BridgeType.Unit,
      parameters = listOf("factor" to BridgeType.Primitive(PrimitiveKind.DOUBLE)),
    )

    val members = ForwardCirPlanProjection.extension(plan, "sample")
    val import: CirDllImport = members.filterIsInstance<CirDllImport>().single()
    val method: CirMethod = members.filterIsInstance<CirMethod>().single()

    assertEquals("int_scale", import.entryPoint)
    assertEquals(listOf("int", "double"), import.parameters.map { it.nativeType })
    assertEquals(true, import.hasSyncErrorOut)
    assertEquals("void", method.returnType)
    assertEquals(listOf("receiver", "factor"), method.parameters.map { it.name })
    assertEquals(listOf("int", "double"), method.parameters.map { it.type })
    assertEquals(true, method.isExtension)
    val rendered: String = CirRenderer().render(
      CirFile(
        namespaces = listOf(
          CirNamespace("Sample", listOf(CirStaticClass("IntExtensions", members))),
        ),
      ),
    )
    assertEquals(true, rendered.contains("Native_Scale(int receiver, double factor, out IntPtr error)"))
    assertEquals(true, rendered.contains("Native_Scale(receiver, factor, out IntPtr error)"))
  }

  @Test
  fun `value class parameter passes its underlying property into the string wire`() {
    val chartId = BridgeType.ValueClass("sample.ChartId", BridgeType.String)
    val plan: ForwardCallablePlan = plan(
      symbol = "sample.Counter.retag",
      exportName = "counter_retag",
      receiver = "handle",
      result = BridgeType.Primitive(PrimitiveKind.INT),
      parameters = listOf("id" to chartId),
    )

    val method: CirMethod = ForwardCirPlanProjection.classMethod(plan, "counter", isOverride = false)
    val import: CirDllImport = CirClass(
      name = "Counter",
      libraryName = "sample",
      nativePrefix = "counter",
      constructor = null,
      properties = emptyList(),
      methods = listOf(method),
    ).ordinaryNativeImports().single { it.name == "Native_Retag" }

    // Public surface takes the struct; the native import takes its underlying string.
    assertEquals(listOf("ChartId"), method.parameters.map { it.type })
    assertEquals(listOf("IntPtr", "string"), import.parameters.map { it.nativeType })
    assertEquals(true, method.body?.contains("Native_Retag(_handle, id.Value, out IntPtr error)"))
  }

  /**
   * Issue #65: a Kotlin parameter named after a C# keyword. This cell is the *trivial* input path
   * (an `int`), whose wrapper body is rendered generically from `CirMethod.parameters`, so it is
   * exactly the shape a declaration-only escape would miss: `Native_Describe(_handle, default,
   * out IntPtr error)` compiles (a target-typed `default` literal) and silently marshals 0.
   */
  @Test
  fun `keyword scalar parameter renders verbatim at declaration and call sites`() {
    val plan: ForwardCallablePlan = plan(
      symbol = "sample.Article.describe",
      exportName = "article_describe",
      receiver = "handle",
      result = BridgeType.Primitive(PrimitiveKind.INT),
      parameters = listOf("default" to BridgeType.Primitive(PrimitiveKind.INT)),
    )

    val method: CirMethod =
      ForwardCirPlanProjection.classMethod(plan, "article", isOverride = false)
    val rendered: String = renderClass(method)

    assertEquals(listOf("@default"), method.parameters.map { it.name })
    assertEquals(
      true,
      rendered.contains("Native_Describe(IntPtr handle, int @default, out IntPtr error)"),
      rendered,
    )
    assertEquals(true, rendered.contains("Describe(int @default)"), rendered)
    assertEquals(
      true,
      rendered.contains("Native_Describe(_handle, @default, out IntPtr error)"),
      rendered,
    )
    assertEquals(false, rendered.contains("int default"), rendered)
    assertEquals(false, rendered.contains("(_handle, default,"), rendered)
  }

  /**
   * Issue #65, collection cell: a different render path from a scalar, because the wrapper body
   * allocates a marshalling local and disposes it in a `finally`. The `@` stays in *leading*
   * position on the composite local (`@paramsHandle`), never infix (`params@Handle` is invalid).
   */
  @Test
  fun `keyword collection parameter renders verbatim through its marshalling local`() {
    val plan: ForwardCallablePlan = plan(
      symbol = "sample.Article.tag",
      exportName = "article_tag",
      receiver = "handle",
      result = BridgeType.Primitive(PrimitiveKind.INT),
      parameters = listOf(
        "params" to BridgeType.Collection(CollectionKind.LIST, element = BridgeType.String),
      ),
    )

    val method: CirMethod =
      ForwardCirPlanProjection.classMethod(plan, "article", isOverride = false)
    val rendered: String = renderClass(method)

    assertEquals(listOf("@params"), method.parameters.map { it.name })
    assertEquals(
      true,
      rendered.contains("Native_Tag(IntPtr handle, IntPtr @params, out IntPtr error)"),
      rendered,
    )
    assertEquals(true, rendered.contains("Tag(IReadOnlyList<string> @params)"), rendered)
    assertEquals(true, rendered.contains("IntPtr @paramsHandle = IntPtr.Zero;"), rendered)
    assertEquals(
      true,
      rendered.contains("@paramsHandle = NugetMarshal.CreateList(@params);"),
      rendered,
    )
    assertEquals(
      true,
      rendered.contains("Native_Tag(_handle, @paramsHandle, out IntPtr error)"),
      rendered,
    )
    assertEquals(true, rendered.contains("NugetListNative.Dispose(@paramsHandle)"), rendered)
    assertEquals(false, rendered.contains("CreateList(params)"), rendered)
    assertEquals(false, rendered.contains("params@"), rendered)
  }

  /**
   * Issue #65, constructor cell: both the bare use site (`@abstract`) and a member access *on* a
   * keyword-named parameter (`@ref._handle`) — the ADR-077 `reassign(ref: ChartRef)` shape.
   */
  @Test
  fun `keyword constructor parameters render verbatim including member access use sites`() {
    val plan: ForwardCallablePlan = plan(
      symbol = "sample.Article.<init>",
      exportName = "article_create",
      receiver = null,
      result = BridgeType.ObjectHandle("sample.Article"),
      parameters = listOf(
        "abstract" to BridgeType.String,
        "ref" to BridgeType.ObjectHandle("sample.Article"),
      ),
      origin = ForwardCallableOrigin.CONSTRUCTOR,
    )

    val constructor: CirConstructor = ForwardCirPlanProjection.constructor(plan)
    val rendered: String = CirRenderer().render(
      CirFile(
        namespaces = listOf(
          CirNamespace(
            "Sample",
            listOf(
              CirClass(
                name = "Article",
                libraryName = "sample",
                nativePrefix = "article",
                constructor = constructor,
                properties = emptyList(),
                methods = emptyList(),
              ),
            ),
          ),
        ),
      ),
    )

    assertEquals(listOf("@abstract", "@ref"), constructor.parameters.map { it.name })
    assertEquals(true, rendered.contains("Article(string @abstract, Article @ref)"), rendered)
    assertEquals(
      true,
      rendered.contains("string @abstract, IntPtr @ref, out IntPtr error)"),
      rendered,
    )
    assertEquals(
      true,
      rendered.contains("Native_Create(@abstract, @ref._handle, out IntPtr error)"),
      rendered,
    )
    assertEquals(false, rendered.contains("(abstract,"), rendered)
    assertEquals(false, rendered.contains("string abstract"), rendered)
  }

  private fun renderClass(method: CirMethod): String = CirRenderer().render(
    CirFile(
      namespaces = listOf(
        CirNamespace(
          "Sample",
          listOf(
            CirClass(
              name = "Article",
              libraryName = "sample",
              nativePrefix = "article",
              constructor = null,
              properties = emptyList(),
              methods = listOf(method),
            ),
          ),
        ),
      ),
    ),
  )

  private fun plan(
    symbol: String,
    exportName: String,
    receiver: String?,
    result: BridgeType,
    parameters: List<Pair<String, BridgeType>>,
    origin: ForwardCallableOrigin = ForwardCallableOrigin.CLASS,
  ): ForwardCallablePlan {
    val error = ForwardAbiParameter(
      "errorOut",
      ForwardAbiWireType.POINTER,
      ForwardAbiDirection.OUT,
      ForwardTransfer(
        "error",
        BridgeType.ObjectHandle("kotlin.Throwable"),
        ForwardFlow.OUT_OF_KOTLIN,
        ForwardPassing.OUT,
        ForwardOwnership.BORROWED,
        ForwardConversion.STABLE_REF_TO_HANDLE,
      ),
    )
    val receiverParameter: ForwardAbiParameter? = receiver?.let { name ->
      val receiverType: BridgeType = if (name == "handle") {
        BridgeType.ObjectHandle("sample.Counter")
      } else {
        BridgeType.Primitive(PrimitiveKind.INT)
      }
      val receiverConversion: ForwardConversion = if (name == "handle") {
        ForwardConversion.HANDLE_TO_STABLE_REF
      } else {
        ForwardConversion.DIRECT
      }
      ForwardAbiParameter(
        name,
        if (name == "handle") ForwardAbiWireType.POINTER else ForwardAbiWireType.INT32,
        ForwardAbiDirection.IN,
        ForwardTransfer(
          name,
          receiverType,
          ForwardFlow.INTO_KOTLIN,
          ForwardPassing.VALUE,
          ForwardOwnership.BORROWED,
          receiverConversion,
        ),
      )
    }
    val values = parameters.map { (name, type) ->
      ForwardAbiParameter(
        name,
        wireType(type),
        ForwardAbiDirection.IN,
        ForwardTransfer(
          name,
          type,
          ForwardFlow.INTO_KOTLIN,
          ForwardPassing.VALUE,
          ForwardOwnership.BORROWED,
          conversion(type, ForwardFlow.INTO_KOTLIN),
        ),
      )
    }
    val call = ForwardNativeCall(
      exportName,
      wireType(result),
      listOfNotNull(receiverParameter) + values + error,
    )
    return ForwardCallablePlan(
      invocation = ForwardInvocation(symbol, origin = origin),
      publicSignature = ForwardPublicSignature(
        exportName.substringAfterLast('_').replaceFirstChar { it.uppercase() },
        parameters.map { (name, type) -> ForwardPublicParameter(name, type) },
        result,
      ),
      evaluation = ForwardEvaluation.EXACTLY_ONCE,
      nativeExports = listOf(call),
      nativeImports = listOf(call),
      result = ForwardResultConvention(
        wireType(result),
        ForwardTransfer(
          "result",
          result,
          ForwardFlow.OUT_OF_KOTLIN,
          ForwardPassing.VALUE,
          ForwardOwnership.BORROWED,
          conversion(result, ForwardFlow.OUT_OF_KOTLIN),
        ),
      ),
      errorSlot = error,
      helperRequirements = buildSet {
        add(ForwardHelperRequirement.STABLE_REF)
        (parameters.map { it.second } + result).forEach { type -> addAll(helpers(type)) }
      },
    ).validate()
  }

  /** The conversion [ForwardCallablePlanValidator] requires for [type] in [flow]. */
  private fun conversion(type: BridgeType, flow: ForwardFlow): ForwardConversion? =
    when (type) {
      BridgeType.String ->
        if (flow == ForwardFlow.INTO_KOTLIN) ForwardConversion.STRING_TO_UTF8
        else ForwardConversion.UTF8_TO_STRING

      is BridgeType.ObjectHandle ->
        if (flow == ForwardFlow.INTO_KOTLIN) ForwardConversion.HANDLE_TO_STABLE_REF
        else ForwardConversion.STABLE_REF_TO_HANDLE

      is BridgeType.Collection ->
        if (flow == ForwardFlow.INTO_KOTLIN) ForwardConversion.HANDLE_TO_COLLECTION
        else ForwardConversion.COLLECTION_TO_HANDLE

      is BridgeType.ValueClass ->
        if (flow == ForwardFlow.INTO_KOTLIN) ForwardConversion.BOX_VALUE_CLASS
        else ForwardConversion.UNBOX_VALUE_CLASS

      else -> ForwardConversion.DIRECT
    }

  /** The helper requirements the conversions above oblige the plan to declare. */
  private fun helpers(type: BridgeType): Set<ForwardHelperRequirement> = when (type) {
    BridgeType.String -> setOf(ForwardHelperRequirement.UTF8)
    is BridgeType.Collection -> setOf(ForwardHelperRequirement.COLLECTION)
    is BridgeType.ValueClass ->
      setOf(ForwardHelperRequirement.VALUE_CLASS, ForwardHelperRequirement.UTF8)

    else -> emptySet()
  }

  private fun wireType(type: BridgeType): ForwardAbiWireType = when (type) {
    BridgeType.Unit -> ForwardAbiWireType.VOID
    is BridgeType.Primitive -> when (type.kind) {
      PrimitiveKind.INT -> ForwardAbiWireType.INT32
      PrimitiveKind.DOUBLE -> ForwardAbiWireType.FLOAT64
      else -> error("Test only needs Int and Double")
    }

    BridgeType.String -> ForwardAbiWireType.STRING
    is BridgeType.ObjectHandle, is BridgeType.Collection -> ForwardAbiWireType.POINTER
    is BridgeType.ValueClass -> wireType(type.underlying)
    else -> error("Test only needs direct types")
  }
}
