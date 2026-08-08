package io.github.xxfast.kotlin.native.nuget.processor.forward

import io.github.xxfast.kotlin.native.nuget.processor.cir.CirMethod
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ADR-040: the CIR plan-projection seams a Kotlin interface's return/parameter positions need on
 * top of [BridgeType.ObjectHandle]'s existing shape -- the public type is the projected interface
 * (`IPet`), construction uses the generated backing wrapper class (`Pet`), and a parameter
 * extracts its handle via the shared `NugetMarshal.HandleOf` reflective helper rather than a
 * direct `._handle` field read (sub-decision B; `._handle` does not compile against a static `IPet`
 * parameter type).
 */
class ForwardInterfaceCirProjectionTest {
  private val petType = BridgeType.Interface("sample.Pet", csharpType = "IPet", backingType = "Pet")
  private val error = ForwardAbiParameter(
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

  @Test
  fun `non-null interface return constructs the backing class but returns the interface type`() {
    val plan: ForwardCallablePlan = classMethodPlan(
      symbol = "sample.Cat.closestFriend",
      exportName = "cat_closestFriend",
      result = petType,
      parameters = emptyList(),
    )

    val method: CirMethod = ForwardCirPlanProjection.classMethod(plan, "cat", isOverride = false)

    assertEquals("IPet", method.returnType)
    // ADR-084 facet 5: the wrapper construction is now the fallback of the identity probe.
    assertTrue(
      method.body.contains(
        "return (NugetMarshal.TryResolveCSharp(nativeResult, out IPet csharpOriginal) " +
            "? csharpOriginal : new Pet(nativeResult));",
      ),
    )
  }

  @Test
  fun `nullable interface return null-guards then constructs the backing class`() {
    val plan: ForwardCallablePlan = classMethodPlan(
      symbol = "sample.Cat.maybeFriend",
      exportName = "cat_maybeFriend",
      result = BridgeType.Nullable(petType),
      parameters = emptyList(),
    )

    val method: CirMethod = ForwardCirPlanProjection.classMethod(plan, "cat", isOverride = false)

    assertEquals("IPet?", method.returnType)
    assertTrue(
      method.body.contains(
        "return nativeResult == IntPtr.Zero ? null : " +
            "(NugetMarshal.TryResolveCSharp(nativeResult, out IPet csharpOriginal) " +
            "? csharpOriginal : new Pet(nativeResult));",
      ),
    )
  }

  @Test
  fun `interface-typed parameter is lowered via the shared HandleOf reflective helper`() {
    val plan: ForwardCallablePlan = classMethodPlan(
      symbol = "sample.Cat.befriend",
      exportName = "cat_befriend",
      result = BridgeType.Unit,
      parameters = listOf("pet" to petType),
    )

    val method: CirMethod = ForwardCirPlanProjection.classMethod(plan, "cat", isOverride = false)

    assertEquals("IPet", method.parameters.single { it.name == "pet" }.type)
    // ADR-084 stage 3: extraction moved into the prelude so the minted transfer handle can be
    // disposed once the crossing is done.
    assertTrue(method.body.contains("IntPtr petHandle = NugetMarshal.HandleOf(pet, out bool petOwned);"))
    assertTrue(method.body.contains("if (petOwned) { NugetMarshal.Dispose(petHandle); }"))
  }

  private fun classMethodPlan(
    symbol: String,
    exportName: String,
    result: BridgeType,
    parameters: List<Pair<String, BridgeType>>,
  ): ForwardCallablePlan {
    val receiverType = BridgeType.ObjectHandle("sample.Cat")
    val receiverParameter = ForwardAbiParameter(
      "handle",
      ForwardAbiWireType.POINTER,
      ForwardAbiDirection.IN,
      ForwardTransfer(
        "handle", receiverType, ForwardFlow.INTO_KOTLIN, ForwardPassing.VALUE,
        ForwardOwnership.BORROWED, ForwardConversion.HANDLE_TO_STABLE_REF,
      ),
    )
    val valueParameters = parameters.map { (name, type) ->
      ForwardAbiParameter(
        name,
        ForwardAbiWireType.POINTER,
        ForwardAbiDirection.IN,
        ForwardTransfer(
          name, type, ForwardFlow.INTO_KOTLIN, ForwardPassing.VALUE,
          ForwardOwnership.BORROWED, ForwardConversion.HANDLE_TO_STABLE_REF,
        ),
      )
    }
    val resultTransfer: ForwardTransfer = when (val unwrapped = result) {
      BridgeType.Unit -> ForwardTransfer(
        "result", result, ForwardFlow.OUT_OF_KOTLIN, ForwardPassing.VALUE,
        ForwardOwnership.BORROWED, ForwardConversion.DIRECT,
      )

      is BridgeType.Interface -> ForwardTransfer(
        "result", result, ForwardFlow.OUT_OF_KOTLIN, ForwardPassing.VALUE,
        ForwardOwnership.OWNED_HANDLE, ForwardConversion.STABLE_REF_TO_HANDLE,
      )

      is BridgeType.Nullable -> ForwardTransfer(
        "result", result, ForwardFlow.OUT_OF_KOTLIN, ForwardPassing.VALUE,
        ForwardOwnership.OWNED_HANDLE, ForwardConversion.STABLE_REF_TO_HANDLE,
      )

      else -> error("Test only needs Unit/Interface/Nullable(Interface) results: $unwrapped")
    }
    val resultWireType: ForwardAbiWireType = when (result) {
      BridgeType.Unit -> ForwardAbiWireType.VOID
      else -> ForwardAbiWireType.POINTER
    }
    val call = ForwardNativeCall(
      exportName,
      resultWireType,
      listOf(receiverParameter) + valueParameters + error,
    )
    val cleanup: List<ForwardCleanup> = if (result != BridgeType.Unit) {
      listOf(ForwardCleanup("result", ForwardCleanupKind.DISPOSE_STABLE_REF))
    } else {
      emptyList()
    }
    return ForwardCallablePlan(
      invocation = ForwardInvocation(symbol),
      publicSignature = ForwardPublicSignature(
        symbol.substringAfterLast('.').replaceFirstChar { it.uppercase() },
        parameters.map { (name, type) -> ForwardPublicParameter(name, type) },
        result,
      ),
      evaluation = ForwardEvaluation.EXACTLY_ONCE,
      nativeExports = listOf(call),
      nativeImports = listOf(call),
      result = ForwardResultConvention(resultWireType, resultTransfer),
      errorSlot = error,
      cleanup = cleanup,
      helperRequirements = setOf(ForwardHelperRequirement.STABLE_REF),
    ).validate()
  }
}
