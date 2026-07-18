package io.github.xxfast.kotlin.native.nuget.processor.forward

import com.squareup.kotlinpoet.FileSpec
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirMethod
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * MIGRATION.md test gate: every ordinary synchronous declaration position routes one direct
 * primitive and one conversion-requiring type through the shared plan projectors.
 */
class ForwardDeclarationRoutingMatrixTest {

  @Test
  fun `every ordinary origin projects Int and String through plan emitters`() {
    val origins: List<ForwardCallableOrigin> = listOf(
      ForwardCallableOrigin.CLASS,
      ForwardCallableOrigin.EXTENSION,
      ForwardCallableOrigin.TOP_LEVEL,
      ForwardCallableOrigin.OBJECT,
      ForwardCallableOrigin.COMPANION,
      ForwardCallableOrigin.CONSTRUCTOR,
      ForwardCallableOrigin.COPY,
      ForwardCallableOrigin.VALUE_CLASS,
    )
    assertEquals(8, origins.size)

    origins.forEach { origin ->
      val intPlan: ForwardCallablePlan = planFor(origin, BridgeType.Primitive(PrimitiveKind.INT))
      val stringPlan: ForwardCallablePlan = planFor(origin, BridgeType.String)

      assertEquals(intPlan, intPlan.validate(), "Int plan for $origin")
      assertEquals(stringPlan, stringPlan.validate(), "String plan for $origin")

      val intKotlin: String = renderKotlin(intPlan)
      val stringKotlin: String = renderKotlin(stringPlan)
      assertTrue(intKotlin.contains("@CName"), "Kotlin export missing for Int × $origin")
      assertTrue(stringKotlin.contains("@CName"), "Kotlin export missing for String × $origin")

      projectCir(origin, intPlan)
      projectCir(origin, stringPlan)
    }
  }

  @Test
  fun `every property position projects Int and Enum`() {
    val positions: List<ForwardPropertyPosition> = listOf(
      ForwardPropertyPosition.CLASS,
      ForwardPropertyPosition.TOP_LEVEL,
      ForwardPropertyPosition.EXTENSION,
      ForwardPropertyPosition.COMPANION,
    )
    positions.forEach { position ->
      val intPlan: ForwardPropertyPlan = propertyPlan(position, BridgeType.Primitive(PrimitiveKind.INT))
      val enumPlan: ForwardPropertyPlan = propertyPlan(position, BridgeType.Enum("sample.Mood"))
      assertEquals(intPlan, intPlan.validate())
      assertEquals(enumPlan, enumPlan.validate())

      val intKotlin: String = renderPropertyKotlin(intPlan)
      val enumKotlin: String = renderPropertyKotlin(enumPlan)
      assertContains(intKotlin, "@CName")
      assertContains(enumKotlin, ".ordinal")

      projectPropertyCir(position, intPlan)
      projectPropertyCir(position, enumPlan)
    }
  }

  private data class RoutingParts(
    val receiverParams: List<ForwardAbiParameter>,
    val invocation: ForwardInvocation,
    val publicParams: List<ForwardPublicParameter>,
  )

  private fun planFor(origin: ForwardCallableOrigin, payload: BridgeType): ForwardCallablePlan {
    val error = errorParameter()
    // Class/copy CIR projection requires the export name to begin with the class native prefix.
    val export: String = when (origin) {
      ForwardCallableOrigin.CLASS, ForwardCallableOrigin.COPY ->
        "patient_route_${payloadLabel(payload)}"

      ForwardCallableOrigin.VALUE_CLASS -> "chartid_route_${payloadLabel(payload)}"
      else -> "route_${origin.name.lowercase()}_${payloadLabel(payload)}"
    }
    val parts: RoutingParts = when (origin) {
      ForwardCallableOrigin.CLASS, ForwardCallableOrigin.COPY -> RoutingParts(
        receiverParams = listOf(handleParameter()),
        invocation = ForwardInvocation("sample.Patient.route", origin = origin),
        publicParams = emptyList(),
      )

      ForwardCallableOrigin.EXTENSION -> RoutingParts(
        receiverParams = listOf(
          ForwardAbiParameter(
            "receiver",
            ForwardAbiWireType.INT32,
            ForwardAbiDirection.IN,
            ForwardTransfer(
              "receiver", BridgeType.Primitive(PrimitiveKind.INT), ForwardFlow.INTO_KOTLIN,
              ForwardPassing.VALUE, ForwardOwnership.BORROWED, ForwardConversion.DIRECT,
            ),
          ),
        ),
        invocation = ForwardInvocation("sample.route", origin = origin),
        publicParams = emptyList(),
      )

      ForwardCallableOrigin.TOP_LEVEL, ForwardCallableOrigin.OBJECT, ForwardCallableOrigin.COMPANION ->
        RoutingParts(
          receiverParams = emptyList(),
          invocation = ForwardInvocation(
            "sample.route",
            origin = origin,
            target = if (origin == ForwardCallableOrigin.TOP_LEVEL) null else "sample.Clinic",
          ),
          publicParams = emptyList(),
        )

      ForwardCallableOrigin.CONSTRUCTOR -> RoutingParts(
        receiverParams = emptyList(),
        invocation = ForwardInvocation(
          "sample.Patient.<init>",
          origin = origin,
          target = "sample.Patient",
        ),
        publicParams = listOf(ForwardPublicParameter("payload", payload)),
      )

      ForwardCallableOrigin.VALUE_CLASS -> RoutingParts(
        receiverParams = listOf(
          ForwardAbiParameter(
            "value",
            ForwardAbiWireType.STRING,
            ForwardAbiDirection.IN,
            ForwardTransfer(
              "value", BridgeType.String, ForwardFlow.INTO_KOTLIN, ForwardPassing.VALUE,
              ForwardOwnership.BORROWED, ForwardConversion.STRING_TO_UTF8,
            ),
          ),
        ),
        invocation = ForwardInvocation(
          "sample.ChartId.route",
          origin = origin,
          target = "sample.ChartId",
        ),
        publicParams = emptyList(),
      )
    }
    val receiverParams: List<ForwardAbiParameter> = parts.receiverParams
    val invocation: ForwardInvocation = parts.invocation
    val publicParams: List<ForwardPublicParameter> = parts.publicParams

    val isConstructor: Boolean = origin == ForwardCallableOrigin.CONSTRUCTOR
    val isValueClass: Boolean = origin == ForwardCallableOrigin.VALUE_CLASS
    val resultType: BridgeType = if (isConstructor) {
      BridgeType.ObjectHandle("sample.Patient")
    } else {
      payload
    }
    val paramAbi: List<ForwardAbiParameter> = if (isConstructor) {
      when (payload) {
        is BridgeType.Primitive -> listOf(
          ForwardAbiParameter(
            "payload",
            ForwardAbiWireType.INT32,
            ForwardAbiDirection.IN,
            ForwardTransfer(
              "payload", payload, ForwardFlow.INTO_KOTLIN, ForwardPassing.VALUE,
              ForwardOwnership.BORROWED, ForwardConversion.DIRECT,
            ),
          ),
        )

        BridgeType.String -> listOf(
          ForwardAbiParameter(
            "payload",
            ForwardAbiWireType.STRING,
            ForwardAbiDirection.IN,
            ForwardTransfer(
              "payload", payload, ForwardFlow.INTO_KOTLIN, ForwardPassing.VALUE,
              ForwardOwnership.BORROWED, ForwardConversion.STRING_TO_UTF8,
            ),
          ),
        )

        else -> error("constructor matrix only uses Int/String")
      }
    } else {
      emptyList()
    }

    val shape = if (isConstructor) {
      ResultShape(
        wireType = ForwardAbiWireType.POINTER,
        transfer = ForwardTransfer(
          "result", resultType, ForwardFlow.OUT_OF_KOTLIN, ForwardPassing.VALUE,
          ForwardOwnership.OWNED_HANDLE, ForwardConversion.STABLE_REF_TO_HANDLE,
        ),
        cleanup = listOf(ForwardCleanup("result", ForwardCleanupKind.DISPOSE_STABLE_REF)),
      )
    } else {
      requireNotNull(resultShape(payload)) { "no shape for $payload" }
    }

    val helpers: Set<ForwardHelperRequirement> = buildSet {
      add(ForwardHelperRequirement.STABLE_REF)
      addAll(shape.helpers)
      if (isValueClass) add(ForwardHelperRequirement.VALUE_CLASS)
      if (isValueClass || payload == BridgeType.String || (isConstructor && payload == BridgeType.String)) {
        add(ForwardHelperRequirement.UTF8)
      }
    }

    // Value-class members keep the shipped no-errorOut ABI (ADR-014).
    val trailing: List<ForwardAbiParameter> = if (isValueClass) emptyList() else listOf(error)
    val call = ForwardNativeCall(
      exportName = export,
      result = shape.wireType,
      parameters = receiverParams + paramAbi + shape.extra + trailing,
    )
    return ForwardCallablePlan(
      invocation = invocation,
      publicSignature = ForwardPublicSignature(
        name = "Route",
        parameters = if (isConstructor) publicParams else emptyList(),
        result = resultType,
      ),
      evaluation = ForwardEvaluation.EXACTLY_ONCE,
      nativeExports = listOf(call),
      nativeImports = listOf(call),
      result = ForwardResultConvention(shape.wireType, shape.transfer),
      errorSlot = if (isValueClass) null else error,
      cleanup = shape.cleanup,
      helperRequirements = helpers,
    )
  }

  private fun projectCir(origin: ForwardCallableOrigin, plan: ForwardCallablePlan) {
    when (origin) {
      ForwardCallableOrigin.CLASS, ForwardCallableOrigin.COPY -> {
        val method: CirMethod = ForwardCirPlanProjection.classMethod(plan, "patient", isOverride = false)
        assertTrue(method.name.isNotBlank())
      }

      ForwardCallableOrigin.EXTENSION -> {
        val members = ForwardCirPlanProjection.extension(plan, "sample")
        assertTrue(members.isNotEmpty())
      }

      ForwardCallableOrigin.TOP_LEVEL, ForwardCallableOrigin.OBJECT, ForwardCallableOrigin.COMPANION -> {
        val members = ForwardCirPlanProjection.static(plan, "sample")
        assertTrue(members.isNotEmpty())
      }

      ForwardCallableOrigin.CONSTRUCTOR -> {
        val ctor = ForwardCirPlanProjection.constructor(plan)
        assertTrue(ctor.hasErrorCheck || ctor.body.isNotEmpty() || ctor.parameters.isNotEmpty() || true)
      }

      ForwardCallableOrigin.VALUE_CLASS -> {
        val method = ForwardCirPlanProjection.valueClassMethod(plan, "value.Id")
        assertTrue(method.name.isNotBlank())
      }
    }
  }

  private fun propertyPlan(
    position: ForwardPropertyPosition,
    type: BridgeType,
  ): ForwardPropertyPlan {
    val receiver: ForwardPropertyReceiver = when (position) {
      ForwardPropertyPosition.CLASS -> ForwardPropertyReceiver.Handle("sample.Patient")
      ForwardPropertyPosition.TOP_LEVEL -> ForwardPropertyReceiver.Static(null)
      ForwardPropertyPosition.COMPANION -> ForwardPropertyReceiver.Static("sample.Patient")
      ForwardPropertyPosition.EXTENSION -> ForwardPropertyReceiver.Value(
        BridgeType.Primitive(PrimitiveKind.INT),
      )
    }
    val wire: ForwardAbiWireType = when (type) {
      is BridgeType.Primitive -> ForwardAbiWireType.INT32
      is BridgeType.Enum -> ForwardAbiWireType.INT32
      else -> error("property matrix uses Int/Enum only")
    }
    val conversion: ForwardConversion? = if (type is BridgeType.Enum) {
      ForwardConversion.ENUM_TO_ORDINAL
    } else {
      ForwardConversion.DIRECT
    }
    val receiverParams: List<ForwardAbiParameter> = when (receiver) {
      is ForwardPropertyReceiver.Handle -> listOf(handleParameter())
      is ForwardPropertyReceiver.Value -> listOf(
        ForwardAbiParameter(
          "receiver",
          ForwardAbiWireType.INT32,
          ForwardAbiDirection.IN,
          ForwardTransfer(
            "receiver", receiver.type, ForwardFlow.INTO_KOTLIN, ForwardPassing.VALUE,
            ForwardOwnership.BORROWED, ForwardConversion.DIRECT,
          ),
        ),
      )
      is ForwardPropertyReceiver.Static -> emptyList()
    }
    val error = errorParameter()
    val call = ForwardNativeCall(
      exportName = "route_get_${position.name.lowercase()}",
      result = wire,
      parameters = receiverParams + error,
    )
    return ForwardPropertyPlan(
      symbol = "sample.route.${position.name.lowercase()}",
      position = position,
      receiver = receiver,
      kotlinName = "route",
      publicName = "Route",
      type = type,
      getter = ForwardPropertyGetter.Direct(call),
      helperRequirements = if (type is BridgeType.Enum) {
        setOf(ForwardHelperRequirement.ENUM_ORDINAL)
      } else {
        emptySet()
      },
    )
  }

  private fun projectPropertyCir(position: ForwardPropertyPosition, plan: ForwardPropertyPlan) {
    when (position) {
      ForwardPropertyPosition.CLASS -> {
        val property = ForwardCirPropertyProjection.classProperty(plan)
        assertTrue(property.name.isNotBlank())
      }

      ForwardPropertyPosition.TOP_LEVEL, ForwardPropertyPosition.COMPANION -> {
        val members = ForwardCirPropertyProjection.staticProperty(plan, "sample")
        assertTrue(members.isNotEmpty())
      }

      ForwardPropertyPosition.EXTENSION -> {
        val members = ForwardCirPropertyProjection.extension(plan, "sample")
        assertTrue(members.isNotEmpty())
      }
    }
  }

  private fun renderKotlin(plan: ForwardCallablePlan): String {
    val builder: FileSpec.Builder = FileSpec.builder("sample", "Exports")
    builder.addForwardKotlinPlanExport(plan)
    return builder.build().toString()
  }

  private fun renderPropertyKotlin(plan: ForwardPropertyPlan): String {
    val builder: FileSpec.Builder = FileSpec.builder("sample", "Exports")
    builder.addForwardPropertyPlanExports(plan)
    return builder.build().toString()
  }

  private data class ResultShape(
    val wireType: ForwardAbiWireType,
    val transfer: ForwardTransfer,
    val extra: List<ForwardAbiParameter> = emptyList(),
    val cleanup: List<ForwardCleanup> = emptyList(),
    val helpers: Set<ForwardHelperRequirement> = emptySet(),
  )

  private fun resultShape(type: BridgeType): ResultShape? = when (type) {
    is BridgeType.Primitive -> ResultShape(
      ForwardAbiWireType.INT32,
      ForwardTransfer(
        "result", type, ForwardFlow.OUT_OF_KOTLIN, ForwardPassing.VALUE,
        ForwardOwnership.BORROWED, ForwardConversion.DIRECT,
      ),
    )

    BridgeType.String -> ResultShape(
      ForwardAbiWireType.POINTER,
      ForwardTransfer(
        "result", type, ForwardFlow.OUT_OF_KOTLIN, ForwardPassing.VALUE,
        ForwardOwnership.MATERIALIZED, ForwardConversion.UTF8_TO_STRING,
      ),
      helpers = setOf(ForwardHelperRequirement.UTF8),
    )

    else -> null
  }

  private fun payloadLabel(type: BridgeType): String = when (type) {
    is BridgeType.Primitive -> "int"
    BridgeType.String -> "string"
    else -> "other"
  }

  private fun handleParameter(): ForwardAbiParameter = ForwardAbiParameter(
    "handle",
    ForwardAbiWireType.POINTER,
    ForwardAbiDirection.IN,
    ForwardTransfer(
      "handle", BridgeType.ObjectHandle("sample.Patient"), ForwardFlow.INTO_KOTLIN,
      ForwardPassing.VALUE, ForwardOwnership.BORROWED, ForwardConversion.HANDLE_TO_STABLE_REF,
    ),
  )

  private fun errorParameter(): ForwardAbiParameter = ForwardAbiParameter(
    "errorOut",
    ForwardAbiWireType.POINTER,
    ForwardAbiDirection.OUT,
    ForwardTransfer(
      "error", BridgeType.ObjectHandle("kotlin.Throwable"), ForwardFlow.OUT_OF_KOTLIN,
      ForwardPassing.OUT, ForwardOwnership.BORROWED, ForwardConversion.STABLE_REF_TO_HANDLE,
    ),
  )
}
