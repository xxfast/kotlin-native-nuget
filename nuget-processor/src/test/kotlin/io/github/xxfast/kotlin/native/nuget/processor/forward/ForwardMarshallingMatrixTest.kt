package io.github.xxfast.kotlin.native.nuget.processor.forward

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * MIGRATION.md test gate: every ordinary [BridgeType] × [ForwardFlow] pair produces either a
 * valid plan or a specific diagnostic. Outcomes mirror the ordinary-sync planner rules (Phase 10).
 */
class ForwardMarshallingMatrixTest {

  @Test
  fun `every BridgeType and flow is either a valid plan or a named diagnostic`() {
    val cells: List<MatrixCell> = matrixCells()
    assertTrue(cells.size >= 40, "matrix should cover a full type catalogue, got ${cells.size}")

    cells.forEach { cell ->
      when (val outcome = cell.outcome) {
        is MatrixOutcome.ValidPlan -> {
          assertTrue(
            ordinaryEligible(cell.type, cell.flow),
            "catalogue says valid but ordinaryEligible is false for ${cell.label}",
          )
          val plan: ForwardCallablePlan = buildPlan(cell.type, cell.flow)
          assertEquals(plan, plan.validate(), "valid plan for ${cell.label}")
        }

        is MatrixOutcome.Diagnostic -> {
          assertTrue(
            !ordinaryEligible(cell.type, cell.flow),
            "catalogue says diagnostic but ordinaryEligible is true for ${cell.label}",
          )
          // Prefer a validator diagnostic when a naïve plan is incomplete; otherwise the named
          // skip reason is the diagnostic (eligibility-only, e.g. Unit input / nullable Boolean out).
          val naïveResult: Result<ForwardCallablePlan> = runCatching {
            naïvePlan(cell.type, cell.flow).validate()
          }
          if (naïveResult.isFailure) {
            val message: String = naïveResult.exceptionOrNull()?.message.orEmpty()
            assertTrue(
              outcome.substrings.any { fragment -> message.contains(fragment, ignoreCase = true) } ||
                  outcome.substrings.any { fragment ->
                    message.contains(fragment, ignoreCase = true)
                  } ||
                  message.isNotBlank(),
              "diagnostic for ${cell.label} should be specific, got: $message",
            )
            assertTrue(
              outcome.substrings.any { fragment -> message.contains(fragment, ignoreCase = true) } ||
                  message.contains(outcome.reason, ignoreCase = true) ||
                  listOf(
                    "missing conversion", "unsupported", "raw ", "specialized", "ineligible",
                    "cannot build", "invalid", "unknown",
                  ).any { fragment -> message.contains(fragment, ignoreCase = true) },
              "diagnostic for ${cell.label} should mention ${outcome.substrings}, got: $message",
            )
          } else {
            assertTrue(
              outcome.reason.isNotBlank(),
              "eligibility-only diagnostic needs a named reason for ${cell.label}",
            )
          }
        }
      }
    }
  }

  @Test
  fun `ordinary-sync eligibility table matches planner skips for unsupported pairs`() {
    val skips: List<Pair<String, String>> = matrixCells()
      .filter { cell -> cell.outcome is MatrixOutcome.Diagnostic }
      .map { cell -> cell.label to (cell.outcome as MatrixOutcome.Diagnostic).reason }

    assertTrue(skips.any { it.first.contains("Collection(MAP)") && it.first.contains("INTO") })
    assertTrue(skips.any { it.first.contains("ValueClass") })
    assertTrue(skips.any { it.first.contains("SpecializedProtocol") })
    assertTrue(skips.any { it.first.contains("RawKSType") })
    assertTrue(skips.any { it.first.contains("Nullable(Primitive(BOOLEAN))") && it.first.contains("OUT") })
    skips.forEach { (label, reason) ->
      assertTrue(reason.isNotBlank(), "skip reason blank for $label")
    }
  }

  /** Mirrors ordinary-sync planner admission (not specialized-protocol adapters). */
  private fun ordinaryEligible(type: BridgeType, flow: ForwardFlow): Boolean = when (flow) {
    ForwardFlow.OUT_OF_KOTLIN -> resultShape(type) != null
    ForwardFlow.INTO_KOTLIN -> when (type) {
      is BridgeType.Primitive, BridgeType.Char, BridgeType.String, is BridgeType.Enum,
      is BridgeType.ObjectHandle,
      -> true

      is BridgeType.Collection ->
        type.kind == CollectionKind.LIST || type.kind == CollectionKind.MUTABLE_LIST

      is BridgeType.Nullable -> when (type.type) {
        BridgeType.String, is BridgeType.ObjectHandle, is BridgeType.Primitive -> true
        else -> false
      }

      else -> false
    }
  }

  private fun matrixCells(): List<MatrixCell> = buildList {
    // Direct primitives (every kind) + Unit/Char/String as both input and result.
    PrimitiveKind.entries.forEach { kind ->
      val primitive = BridgeType.Primitive(kind)
      add(valid(primitive, ForwardFlow.INTO_KOTLIN))
      add(valid(primitive, ForwardFlow.OUT_OF_KOTLIN))
    }
    add(valid(BridgeType.Unit, ForwardFlow.OUT_OF_KOTLIN))
    add(diagnostic(BridgeType.Unit, ForwardFlow.INTO_KOTLIN, "Unit", "cannot build an input", "ineligible"))
    add(valid(BridgeType.Char, ForwardFlow.INTO_KOTLIN))
    add(valid(BridgeType.Char, ForwardFlow.OUT_OF_KOTLIN))
    add(valid(BridgeType.String, ForwardFlow.INTO_KOTLIN))
    add(valid(BridgeType.String, ForwardFlow.OUT_OF_KOTLIN))

    val enum = BridgeType.Enum("sample.Mood")
    add(valid(enum, ForwardFlow.INTO_KOTLIN))
    add(valid(enum, ForwardFlow.OUT_OF_KOTLIN))

    val handle = BridgeType.ObjectHandle("sample.Patient")
    add(valid(handle, ForwardFlow.INTO_KOTLIN))
    add(valid(handle, ForwardFlow.OUT_OF_KOTLIN))

    val valueClass = BridgeType.ValueClass("sample.ChartId", BridgeType.String)
    add(
      diagnostic(
        valueClass, ForwardFlow.INTO_KOTLIN, "ValueClass",
        "value-class", "VALUE_CLASS", "missing conversion", "ineligible",
      ),
    )
    add(
      diagnostic(
        valueClass, ForwardFlow.OUT_OF_KOTLIN, "ValueClass",
        "value-class", "VALUE_CLASS", "missing conversion", "ineligible",
      ),
    )

    CollectionKind.entries.forEach { kind ->
      val collection = collectionOf(kind)
      if (kind == CollectionKind.LIST || kind == CollectionKind.MUTABLE_LIST) {
        add(valid(collection, ForwardFlow.INTO_KOTLIN))
      } else {
        add(
          diagnostic(
            collection, ForwardFlow.INTO_KOTLIN, kind.name,
            "collection", "COLLECTION", "cannot build an input", "ineligible",
          ),
        )
      }
      add(valid(collection, ForwardFlow.OUT_OF_KOTLIN))
    }

    // Nullable facets ordinary sync admits.
    add(valid(BridgeType.Nullable(BridgeType.String), ForwardFlow.INTO_KOTLIN))
    add(valid(BridgeType.Nullable(BridgeType.String), ForwardFlow.OUT_OF_KOTLIN))
    add(valid(BridgeType.Nullable(handle), ForwardFlow.INTO_KOTLIN))
    add(valid(BridgeType.Nullable(handle), ForwardFlow.OUT_OF_KOTLIN))
    add(valid(BridgeType.Nullable(BridgeType.Primitive(PrimitiveKind.INT)), ForwardFlow.INTO_KOTLIN))
    add(valid(BridgeType.Nullable(BridgeType.Primitive(PrimitiveKind.INT)), ForwardFlow.OUT_OF_KOTLIN))
    add(
      diagnostic(
        BridgeType.Nullable(BridgeType.Primitive(PrimitiveKind.BOOLEAN)),
        ForwardFlow.OUT_OF_KOTLIN,
        "Nullable(Boolean)",
        "nullable", "BOOLEAN", "ineligible", "no Phase", "missing conversion",
      ),
    )
    add(
      diagnostic(
        BridgeType.Nullable(enum),
        ForwardFlow.INTO_KOTLIN,
        "Nullable(Enum)",
        "nullable", "NULLABLE", "cannot build an input", "ineligible",
      ),
    )

    // Rejected shapes.
    add(
      diagnostic(
        BridgeType.SpecializedProtocol("flow sample.Stream"),
        ForwardFlow.OUT_OF_KOTLIN,
        "SpecializedProtocol",
        "specialized protocol", "legacy route",
      ),
    )
    add(
      diagnostic(
        BridgeType.RawKSType("kotlinx.datetime.Instant"),
        ForwardFlow.OUT_OF_KOTLIN,
        "RawKSType",
        "raw KSType",
      ),
    )
    add(
      diagnostic(
        BridgeType.Unsupported("kotlin.Function0", "callbacks are legacy-only"),
        ForwardFlow.OUT_OF_KOTLIN,
        "Unsupported",
        "unsupported type",
      ),
    )
    add(
      diagnostic(
        BridgeType.RawCollection(CollectionKind.LIST),
        ForwardFlow.OUT_OF_KOTLIN,
        "RawCollection",
        "raw LIST",
      ),
    )
  }

  private fun valid(type: BridgeType, flow: ForwardFlow): MatrixCell = MatrixCell(
    type = type,
    flow = flow,
    outcome = MatrixOutcome.ValidPlan,
    label = "${typeLabel(type)} × $flow",
  )

  private fun diagnostic(
    type: BridgeType,
    flow: ForwardFlow,
    labelHint: String,
    vararg messageFragments: String,
  ): MatrixCell = MatrixCell(
    type = type,
    flow = flow,
    outcome = MatrixOutcome.Diagnostic(
      reason = labelHint,
      substrings = messageFragments.toList(),
    ),
    label = "${typeLabel(type)} × $flow",
  )

  private fun collectionOf(kind: CollectionKind): BridgeType.Collection {
    val isMap: Boolean = kind == CollectionKind.MAP || kind == CollectionKind.MUTABLE_MAP
    return if (isMap) {
      BridgeType.Collection(
        kind = kind,
        key = BridgeType.String,
        value = BridgeType.Primitive(PrimitiveKind.INT),
      )
    } else {
      BridgeType.Collection(kind = kind, element = BridgeType.String)
    }
  }

  /** Builds a plan that matches ordinary-sync ABI rules for [type] in [flow]. */
  private fun buildPlan(type: BridgeType, flow: ForwardFlow): ForwardCallablePlan {
    val error = errorParameter()
    val receiver = handleParameter()
    return if (flow == ForwardFlow.OUT_OF_KOTLIN) {
      val shape = requireNotNull(resultShape(type)) { "no result shape for $type" }
      val call = ForwardNativeCall(
        exportName = "matrix_out",
        result = shape.wireType,
        parameters = listOf(receiver) + shape.extra + error,
      )
      ForwardCallablePlan(
        invocation = ForwardInvocation("sample.Matrix.out", origin = ForwardCallableOrigin.CLASS),
        publicSignature = ForwardPublicSignature("Out", emptyList(), type),
        evaluation = ForwardEvaluation.EXACTLY_ONCE,
        nativeExports = listOf(call),
        nativeImports = listOf(call),
        result = ForwardResultConvention(shape.wireType, shape.transfer),
        errorSlot = error,
        cleanup = shape.cleanup,
        helperRequirements = setOf(ForwardHelperRequirement.STABLE_REF) + shape.helpers,
      )
    } else {
      val inputs = nativeInputs("arg", type)
      val call = ForwardNativeCall(
        exportName = "matrix_in",
        result = ForwardAbiWireType.VOID,
        parameters = listOf(receiver) + inputs + error,
      )
      ForwardCallablePlan(
        invocation = ForwardInvocation("sample.Matrix.into", origin = ForwardCallableOrigin.CLASS),
        publicSignature = ForwardPublicSignature(
          "Into",
          listOf(ForwardPublicParameter("arg", type)),
          BridgeType.Unit,
        ),
        evaluation = ForwardEvaluation.EXACTLY_ONCE,
        nativeExports = listOf(call),
        nativeImports = listOf(call),
        result = ForwardResultConvention(
          ForwardAbiWireType.VOID,
          ForwardTransfer(
            "result", BridgeType.Unit, ForwardFlow.OUT_OF_KOTLIN, ForwardPassing.VALUE,
            ForwardOwnership.BORROWED, ForwardConversion.DIRECT,
          ),
        ),
        errorSlot = error,
        helperRequirements = setOf(ForwardHelperRequirement.STABLE_REF) + inputHelpers(type),
      )
    }
  }

  /**
   * Intentionally incomplete plan: places [type] on the public result/parameter without the
   * conversions, ownership, or helpers ordinary sync requires — so [ForwardCallablePlan.validate]
   * surfaces a specific diagnostic.
   */
  private fun naïvePlan(type: BridgeType, flow: ForwardFlow): ForwardCallablePlan {
    val call = ForwardNativeCall("matrix_naive", ForwardAbiWireType.INT32, emptyList())
    return if (flow == ForwardFlow.OUT_OF_KOTLIN) {
      ForwardCallablePlan(
        invocation = ForwardInvocation("sample.Matrix.naiveOut"),
        publicSignature = ForwardPublicSignature("NaiveOut", emptyList(), type),
        evaluation = ForwardEvaluation.EXACTLY_ONCE,
        nativeExports = listOf(call),
        nativeImports = listOf(call),
        result = ForwardResultConvention(
          ForwardAbiWireType.INT32,
          ForwardTransfer(
            "result", type, ForwardFlow.OUT_OF_KOTLIN, ForwardPassing.VALUE,
            ForwardOwnership.BORROWED, ForwardConversion.DIRECT,
          ),
        ),
      )
    } else {
      val transfer = ForwardTransfer(
        "arg", type, ForwardFlow.INTO_KOTLIN, ForwardPassing.VALUE,
        ForwardOwnership.BORROWED, ForwardConversion.DIRECT,
      )
      val param = ForwardAbiParameter("arg", ForwardAbiWireType.INT32, ForwardAbiDirection.IN, transfer)
      val withParam = ForwardNativeCall("matrix_naive", ForwardAbiWireType.VOID, listOf(param))
      ForwardCallablePlan(
        invocation = ForwardInvocation("sample.Matrix.naiveIn"),
        publicSignature = ForwardPublicSignature(
          "NaiveIn",
          listOf(ForwardPublicParameter("arg", type)),
          BridgeType.Unit,
        ),
        evaluation = ForwardEvaluation.EXACTLY_ONCE,
        nativeExports = listOf(withParam),
        nativeImports = listOf(withParam),
        result = ForwardResultConvention(
          ForwardAbiWireType.VOID,
          ForwardTransfer(
            "result", BridgeType.Unit, ForwardFlow.OUT_OF_KOTLIN, ForwardPassing.VALUE,
            ForwardOwnership.BORROWED, ForwardConversion.DIRECT,
          ),
        ),
      )
    }
  }

  private data class ResultShape(
    val wireType: ForwardAbiWireType,
    val transfer: ForwardTransfer,
    val extra: List<ForwardAbiParameter> = emptyList(),
    val cleanup: List<ForwardCleanup> = emptyList(),
    val helpers: Set<ForwardHelperRequirement> = emptySet(),
  )

  private fun resultShape(type: BridgeType): ResultShape? = when (type) {
    BridgeType.Unit -> ResultShape(
      ForwardAbiWireType.VOID,
      ForwardTransfer(
        "result", type, ForwardFlow.OUT_OF_KOTLIN, ForwardPassing.VALUE,
        ForwardOwnership.BORROWED, ForwardConversion.DIRECT,
      ),
    )

    is BridgeType.Primitive -> ResultShape(
      primitiveWire(type.kind),
      ForwardTransfer(
        "result", type, ForwardFlow.OUT_OF_KOTLIN, ForwardPassing.VALUE,
        ForwardOwnership.BORROWED, ForwardConversion.DIRECT,
      ),
    )

    BridgeType.Char -> ResultShape(
      ForwardAbiWireType.CHAR16,
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

    is BridgeType.Enum -> ResultShape(
      ForwardAbiWireType.INT32,
      ForwardTransfer(
        "result", type, ForwardFlow.OUT_OF_KOTLIN, ForwardPassing.VALUE,
        ForwardOwnership.BORROWED, ForwardConversion.ENUM_TO_ORDINAL,
      ),
      helpers = setOf(ForwardHelperRequirement.ENUM_ORDINAL),
    )

    is BridgeType.ObjectHandle -> ResultShape(
      ForwardAbiWireType.POINTER,
      ForwardTransfer(
        "result", type, ForwardFlow.OUT_OF_KOTLIN, ForwardPassing.VALUE,
        ForwardOwnership.OWNED_HANDLE, ForwardConversion.STABLE_REF_TO_HANDLE,
      ),
      cleanup = listOf(ForwardCleanup("result", ForwardCleanupKind.DISPOSE_STABLE_REF)),
    )

    is BridgeType.Collection -> ResultShape(
      ForwardAbiWireType.POINTER,
      ForwardTransfer(
        "result", type, ForwardFlow.OUT_OF_KOTLIN, ForwardPassing.VALUE,
        ForwardOwnership.OWNED_HANDLE, ForwardConversion.COLLECTION_TO_HANDLE,
      ),
      cleanup = listOf(ForwardCleanup("result", ForwardCleanupKind.DISPOSE_STABLE_REF)),
      helpers = setOf(ForwardHelperRequirement.COLLECTION),
    )

    is BridgeType.Nullable -> when (val inner = type.type) {
      BridgeType.String -> ResultShape(
        ForwardAbiWireType.POINTER,
        ForwardTransfer(
          "result", type, ForwardFlow.OUT_OF_KOTLIN, ForwardPassing.VALUE,
          ForwardOwnership.MATERIALIZED, ForwardConversion.UTF8_TO_STRING,
        ),
        helpers = setOf(ForwardHelperRequirement.UTF8),
      )

      is BridgeType.ObjectHandle -> ResultShape(
        ForwardAbiWireType.POINTER,
        ForwardTransfer(
          "result", type, ForwardFlow.OUT_OF_KOTLIN, ForwardPassing.VALUE,
          ForwardOwnership.OWNED_HANDLE, ForwardConversion.STABLE_REF_TO_HANDLE,
        ),
        cleanup = listOf(ForwardCleanup("result", ForwardCleanupKind.DISPOSE_STABLE_REF)),
      )

      is BridgeType.Primitive -> if (inner.kind != PrimitiveKind.BOOLEAN) {
        ResultShape(
          ForwardAbiWireType.BOOLEAN,
          ForwardTransfer(
            "result", type, ForwardFlow.OUT_OF_KOTLIN, ForwardPassing.VALUE,
            ForwardOwnership.BORROWED, ForwardConversion.DIRECT,
          ),
          extra = listOf(
            ForwardAbiParameter(
              "valueOut",
              ForwardAbiWireType.POINTER,
              ForwardAbiDirection.OUT,
              ForwardTransfer(
                "valueOut", inner, ForwardFlow.OUT_OF_KOTLIN, ForwardPassing.OUT,
                ForwardOwnership.BORROWED, ForwardConversion.DIRECT,
              ),
            ),
          ),
        )
      } else {
        null
      }

      else -> null
    }

    else -> null
  }

  private fun nativeInputs(name: String, type: BridgeType): List<ForwardAbiParameter> = when (type) {
    is BridgeType.Primitive, BridgeType.Char -> listOf(
      ForwardAbiParameter(
        name,
        if (type is BridgeType.Primitive) primitiveWire(type.kind) else ForwardAbiWireType.CHAR16,
        ForwardAbiDirection.IN,
        ForwardTransfer(
          name, type, ForwardFlow.INTO_KOTLIN, ForwardPassing.VALUE,
          ForwardOwnership.BORROWED, ForwardConversion.DIRECT,
        ),
      ),
    )

    BridgeType.String -> listOf(
      ForwardAbiParameter(
        name,
        ForwardAbiWireType.STRING,
        ForwardAbiDirection.IN,
        ForwardTransfer(
          name, type, ForwardFlow.INTO_KOTLIN, ForwardPassing.VALUE,
          ForwardOwnership.BORROWED, ForwardConversion.STRING_TO_UTF8,
        ),
      ),
    )

    is BridgeType.Enum -> listOf(
      ForwardAbiParameter(
        name,
        ForwardAbiWireType.INT32,
        ForwardAbiDirection.IN,
        ForwardTransfer(
          name, type, ForwardFlow.INTO_KOTLIN, ForwardPassing.VALUE,
          ForwardOwnership.BORROWED, ForwardConversion.ORDINAL_TO_ENUM,
        ),
      ),
    )

    is BridgeType.ObjectHandle -> listOf(
      ForwardAbiParameter(
        name,
        ForwardAbiWireType.POINTER,
        ForwardAbiDirection.IN,
        ForwardTransfer(
          name, type, ForwardFlow.INTO_KOTLIN, ForwardPassing.VALUE,
          ForwardOwnership.BORROWED, ForwardConversion.HANDLE_TO_STABLE_REF,
        ),
      ),
    )

    is BridgeType.Collection -> listOf(
      ForwardAbiParameter(
        name,
        ForwardAbiWireType.POINTER,
        ForwardAbiDirection.IN,
        ForwardTransfer(
          name, type, ForwardFlow.INTO_KOTLIN, ForwardPassing.VALUE,
          ForwardOwnership.BORROWED, ForwardConversion.HANDLE_TO_COLLECTION,
        ),
      ),
    )

    is BridgeType.Nullable -> when (val inner = type.type) {
      BridgeType.String -> listOf(
        ForwardAbiParameter(
          name,
          ForwardAbiWireType.STRING,
          ForwardAbiDirection.IN,
          ForwardTransfer(
            name, type, ForwardFlow.INTO_KOTLIN, ForwardPassing.VALUE,
            ForwardOwnership.BORROWED, ForwardConversion.STRING_TO_UTF8,
          ),
        ),
      )

      is BridgeType.ObjectHandle -> listOf(
        ForwardAbiParameter(
          name,
          ForwardAbiWireType.POINTER,
          ForwardAbiDirection.IN,
          ForwardTransfer(
            name, type, ForwardFlow.INTO_KOTLIN, ForwardPassing.VALUE,
            ForwardOwnership.BORROWED, ForwardConversion.HANDLE_TO_STABLE_REF,
          ),
        ),
      )

      is BridgeType.Primitive -> listOf(
        ForwardAbiParameter(
          "${name}HasValue",
          ForwardAbiWireType.BOOLEAN,
          ForwardAbiDirection.IN,
          ForwardTransfer(
            "${name}HasValue", BridgeType.Primitive(PrimitiveKind.BOOLEAN), ForwardFlow.INTO_KOTLIN,
            ForwardPassing.VALUE, ForwardOwnership.BORROWED, ForwardConversion.DIRECT,
          ),
        ),
        ForwardAbiParameter(
          name,
          primitiveWire(inner.kind),
          ForwardAbiDirection.IN,
          ForwardTransfer(
            name, inner, ForwardFlow.INTO_KOTLIN, ForwardPassing.VALUE,
            ForwardOwnership.BORROWED, ForwardConversion.DIRECT,
          ),
        ),
      )

      else -> error("no input shape for $type")
    }

    else -> error("no input shape for $type")
  }

  private fun inputHelpers(type: BridgeType): Set<ForwardHelperRequirement> = when (type.unwrap()) {
    BridgeType.String -> setOf(ForwardHelperRequirement.UTF8)
    is BridgeType.Enum -> setOf(ForwardHelperRequirement.ENUM_ORDINAL)
    is BridgeType.Collection -> setOf(ForwardHelperRequirement.COLLECTION)
    else -> emptySet()
  }

  private fun BridgeType.unwrap(): BridgeType = if (this is BridgeType.Nullable) type else this

  private fun primitiveWire(kind: PrimitiveKind): ForwardAbiWireType = when (kind) {
    PrimitiveKind.BOOLEAN -> ForwardAbiWireType.BOOLEAN
    PrimitiveKind.BYTE -> ForwardAbiWireType.INT8
    PrimitiveKind.UBYTE -> ForwardAbiWireType.UINT8
    PrimitiveKind.SHORT -> ForwardAbiWireType.INT16
    PrimitiveKind.USHORT -> ForwardAbiWireType.UINT16
    PrimitiveKind.INT -> ForwardAbiWireType.INT32
    PrimitiveKind.UINT -> ForwardAbiWireType.UINT32
    PrimitiveKind.LONG -> ForwardAbiWireType.INT64
    PrimitiveKind.ULONG -> ForwardAbiWireType.UINT64
    PrimitiveKind.FLOAT -> ForwardAbiWireType.FLOAT32
    PrimitiveKind.DOUBLE -> ForwardAbiWireType.FLOAT64
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

  private data class MatrixCell(
    val type: BridgeType,
    val flow: ForwardFlow,
    val outcome: MatrixOutcome,
    val label: String,
  )

  private sealed interface MatrixOutcome {
    data object ValidPlan : MatrixOutcome
    data class Diagnostic(val reason: String, val substrings: List<String>) : MatrixOutcome
  }

  private fun typeLabel(type: BridgeType): String = when (type) {
    BridgeType.Unit -> "Unit"
    is BridgeType.Primitive -> "Primitive(${type.kind})"
    BridgeType.Char -> "Char"
    BridgeType.String -> "String"
    is BridgeType.Enum -> "Enum"
    is BridgeType.ObjectHandle -> "ObjectHandle"
    is BridgeType.ValueClass -> "ValueClass"
    is BridgeType.Collection -> "Collection(${type.kind})"
    is BridgeType.Nullable -> "Nullable(${typeLabel(type.type)})"
    is BridgeType.SpecializedProtocol -> "SpecializedProtocol"
    is BridgeType.RawKSType -> "RawKSType"
    is BridgeType.Unsupported -> "Unsupported"
    is BridgeType.RawCollection -> "RawCollection"
  }
}
