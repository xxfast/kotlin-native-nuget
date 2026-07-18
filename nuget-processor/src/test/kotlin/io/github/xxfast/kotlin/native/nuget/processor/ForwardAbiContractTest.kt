package io.github.xxfast.kotlin.native.nuget.processor

import io.github.xxfast.kotlin.native.nuget.processor.cir.CirClass
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirConstructor
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirDllImport
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirExtraNative
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirFile
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirMethod
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirNamespace
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirObject
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirParameter
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirProperty
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirStaticClass
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirValueClass
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirValueClassConstructor
import io.github.xxfast.kotlin.native.nuget.processor.forward.BridgeType
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardAbiDirection as PlannedAbiDirection
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardAbiParameter as PlannedAbiParameter
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardAbiWireType
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardCallableCatalogEntry
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardCallablePlan
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardCallablePlanCatalog
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardConversion
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardEvaluation
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardFlow
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardHelperRequirement
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardInvocation
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardOwnership
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardPassing
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardPublicSignature
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardResultConvention
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardTransfer
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardNativeCall
import io.github.xxfast.kotlin.native.nuget.processor.forward.PrimitiveKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ForwardAbiContractTest {
  @Test
  fun `snapshots every ordinary native import projection`() {
    val companionImport = CirDllImport(
      libraryName = "sample",
      entryPoint = "class_companion",
      returnType = "int",
      name = "Native_Companion",
      parameters = listOf(CirParameter("value", "int")),
    )
    val file = CirFile(
      namespaces = listOf(
        CirNamespace(
          name = "Sample",
          declarations = listOf(
            CirStaticClass(
              name = "Functions",
              members = listOf(
                CirDllImport(
                  "sample",
                  "static_echo",
                  "string",
                  "Native_Echo",
                  listOf(CirParameter("value", "string")),
                )
              ),
            ),
            CirObject(
              name = "Singleton",
              libraryName = "sample",
              nativePrefix = "singleton",
              methods = listOf(
                CirDllImport(
                  "sample",
                  "singleton_ping",
                  "bool",
                  "Native_Ping",
                  listOf(CirParameter("value", "int")),
                )
              ),
            ),
            CirClass(
              name = "SampleClass",
              libraryName = "sample",
              nativePrefix = "sample",
              constructor = CirConstructor(emptyList(), ""),
              secondaryConstructors = listOf(
                CirConstructor(listOf(CirParameter("value", "int")), "", nativeSuffix = "_2")
              ),
              properties = listOf(
                CirProperty(
                  name = "Count",
                  type = "int",
                  nativeReturnType = "int",
                  nativeName = "count",
                  getter = "0",
                  setter = "value",
                  extraNatives = listOf(
                    CirExtraNative(
                      entryPointSuffix = "get_count_has_value",
                      returnType = "bool",
                      name = "Native_Get_Count_HasValue",
                      hasSyncErrorOut = true,
                    )
                  ),
                  hasSyncErrorOut = true,
                )
              ),
              methods = listOf(
                CirMethod(
                  name = "TryRead",
                  returnType = "bool",
                  nativeName = "try_read",
                  parameters = listOf(CirParameter("key", "string")),
                  body = "false",
                  isSyncErrorCheckEnabled = true,
                  extraNativeParams = listOf("out int value"),
                )
              ),
              isDataClass = true,
              companionMembers = listOf(companionImport),
            ),
            CirValueClass(
              name = "SampleValue",
              libraryName = "sample",
              nativePrefix = "value",
              underlyingType = "int",
              underlyingName = "Value",
              underlyingNativeType = "int",
              constructors = listOf(
                CirValueClassConstructor(
                  parameters = listOf(CirParameter("value", "int")),
                  nativeName = "value_create",
                  body = "value",
                )
              ),
              properties = listOf(
                CirProperty("Doubled", "int", "int", nativeName = "doubled", getter = "0")
              ),
              methods = listOf(
                CirMethod("IsPositive", "bool", nativeName = "is_positive", parameters = emptyList(), body = "false")
              ),
            ),
          ),
        )
      ),
    )

    assertEquals(
      """
      class_companion(in int) -> int
      sample_copy(in pointer, out pointer) -> pointer
      sample_create(out pointer) -> pointer
      sample_create_2(in int, out pointer) -> pointer
      sample_dispose(in pointer) -> void
      sample_equals(in pointer, in pointer) -> bool
      sample_get_count(in pointer, out pointer) -> int
      sample_get_count_has_value(in pointer, out pointer) -> bool
      sample_hashcode(in pointer) -> int
      sample_set_count(in pointer, in int, out pointer) -> void
      sample_tostring(in pointer) -> pointer
      sample_try_read(in pointer, in string, out pointer, out pointer) -> bool
      singleton_ping(in int) -> bool
      static_echo(in string) -> pointer
      value_create(in int, out pointer) -> int
      value_get_doubled(in int) -> int
      value_is_positive(in int) -> bool
      """.trimIndent(),
      ForwardAbiContract.csharp(file).canonicalText(),
    )
  }

  @Test
  fun `reports missing error out parameter`() {
    val error: IllegalArgumentException = assertFailsWith {
      ForwardAbiContract.assertMatches(
        csharp = listOf(
          signature(
            "nullable_has_value",
            ForwardAbiParameter(ForwardAbiType.POINTER, ForwardAbiDirection.OUT)
          )
        ),
        kotlin = listOf(signature("nullable_has_value")),
      )
    }

    assertTrue(error.message!!.contains("nullable_has_value"))
    assertTrue(error.message!!.contains("expected"))
    assertTrue(error.message!!.contains("actual"))
  }

  @Test
  fun `reports reordered parameters`() {
    val error: IllegalArgumentException = assertFailsWith {
      ForwardAbiContract.assertMatches(
        csharp = listOf(
          signature(
            "combine",
            ForwardAbiParameter(ForwardAbiType.INT),
            ForwardAbiParameter(ForwardAbiType.STRING)
          )
        ),
        kotlin = listOf(
          signature(
            "combine",
            ForwardAbiParameter(ForwardAbiType.STRING),
            ForwardAbiParameter(ForwardAbiType.INT)
          )
        ),
      )
    }

    assertTrue(error.message!!.contains("combine"))
  }

  @Test
  fun `reports wrong wire type`() {
    val error: IllegalArgumentException = assertFailsWith {
      ForwardAbiContract.assertMatches(
        csharp = listOf(signature("count", result = ForwardAbiType.LONG)),
        kotlin = listOf(signature("count", result = ForwardAbiType.INT)),
      )
    }

    assertTrue(error.message!!.contains("count"))
    assertTrue(error.message!!.contains("long"))
    assertTrue(error.message!!.contains("int"))
  }

  @Test
  fun `checks a shadow plan against both legacy ABI projections`() {
    val catalog = ForwardCallablePlanCatalog(
      listOf(ForwardCallableCatalogEntry.Planned(shadowPlan()))
    )
    val expected = signature(
      "counter_increment",
      ForwardAbiParameter(ForwardAbiType.POINTER),
      ForwardAbiParameter(ForwardAbiType.INT),
      ForwardAbiParameter(ForwardAbiType.POINTER, ForwardAbiDirection.OUT),
      result = ForwardAbiType.INT,
    )

    ForwardAbiContract.assertMatchesPlan(catalog, listOf(expected), listOf(expected))
  }

  @Test
  fun `reports which legacy projection drifts from a shadow plan`() {
    val error: IllegalArgumentException = assertFailsWith {
      ForwardAbiContract.assertMatchesPlan(
        ForwardCallablePlanCatalog(listOf(ForwardCallableCatalogEntry.Planned(shadowPlan()))),
        csharp = listOf(signature("counter_increment", result = ForwardAbiType.LONG)),
        kotlin = listOf(signature("counter_increment", result = ForwardAbiType.INT)),
      )
    }

    assertTrue(error.message!!.contains("counter_increment"))
    assertTrue(error.message!!.contains("C#"))
  }

  private fun signature(
    name: String,
    vararg parameters: ForwardAbiParameter,
    result: ForwardAbiType = ForwardAbiType.BOOL,
  ): ForwardAbiSignature = ForwardAbiSignature(name, result, parameters.toList())

  private fun shadowPlan(): ForwardCallablePlan {
    val int: BridgeType.Primitive = BridgeType.Primitive(PrimitiveKind.INT)
    val handle: BridgeType.ObjectHandle = BridgeType.ObjectHandle("sample.Counter")
    val handleTransfer = ForwardTransfer(
      "handle", handle, ForwardFlow.INTO_KOTLIN, ForwardPassing.VALUE,
      ForwardOwnership.BORROWED, ForwardConversion.HANDLE_TO_STABLE_REF,
    )
    val incrementTransfer = ForwardTransfer(
      "increment", int, ForwardFlow.INTO_KOTLIN, ForwardPassing.VALUE,
      ForwardOwnership.BORROWED, ForwardConversion.DIRECT,
    )
    val errorTransfer = ForwardTransfer(
      "error", BridgeType.ObjectHandle("kotlin.Throwable"), ForwardFlow.OUT_OF_KOTLIN,
      ForwardPassing.OUT, ForwardOwnership.BORROWED, ForwardConversion.STABLE_REF_TO_HANDLE,
    )
    val error = PlannedAbiParameter(
      "errorOut", ForwardAbiWireType.POINTER, PlannedAbiDirection.OUT, errorTransfer,
    )
    val call = ForwardNativeCall(
      "counter_increment",
      ForwardAbiWireType.INT32,
      listOf(
        PlannedAbiParameter("handle", ForwardAbiWireType.POINTER, PlannedAbiDirection.IN, handleTransfer),
        PlannedAbiParameter("increment", ForwardAbiWireType.INT32, PlannedAbiDirection.IN, incrementTransfer),
        error,
      ),
    )
    return ForwardCallablePlan(
      invocation = ForwardInvocation("sample.Counter.increment"),
      publicSignature = ForwardPublicSignature("Increment", emptyList(), int),
      evaluation = ForwardEvaluation.EXACTLY_ONCE,
      nativeExports = listOf(call),
      nativeImports = listOf(call),
      result = ForwardResultConvention(
        ForwardAbiWireType.INT32,
        ForwardTransfer(
          "result", int, ForwardFlow.OUT_OF_KOTLIN, ForwardPassing.VALUE,
          ForwardOwnership.BORROWED, ForwardConversion.DIRECT,
        ),
      ),
      errorSlot = error,
      helperRequirements = setOf(ForwardHelperRequirement.STABLE_REF),
    ).validate()
  }
}
