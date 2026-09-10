package io.github.xxfast.kotlin.native.nuget.processor.forward

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ForwardAbiRoleTest {
  @Test
  fun `rejects a generator slot left with the default user role`() {
    val error: IllegalArgumentException = assertFailsWith {
      parameter(name = "handle", role = ForwardAbiRole.USER)
    }

    assertTrue(error.message!!.contains("PLAN_OWNED_NAMES"))
  }

  @Test
  fun `rejects a user name claiming a generator role`() {
    val error: IllegalArgumentException = assertFailsWith {
      parameter(name = "amount", role = ForwardAbiRole.ERROR)
    }

    assertTrue(error.message!!.contains("PLAN_OWNED_NAMES"))
  }

  @Test
  fun `accepts a shifted user name beside its generator slot`() {
    val shifted: ForwardAbiParameter = parameter(name = "handle_", role = ForwardAbiRole.USER)

    assertEquals(ForwardAbiRole.USER, shifted.role)
  }

  @Test
  fun `rejects an error slot that is not last`() {
    val plan: ForwardCallablePlan = planOf(
      parameter("errorOut", ForwardAbiRole.ERROR, ForwardAbiDirection.OUT),
      parameter("amount", ForwardAbiRole.USER),
    )

    val error: IllegalArgumentException = assertFailsWith { plan.validate() }

    assertTrue(error.message!!.contains("error slot"))
  }

  @Test
  fun `rejects a receiver that is not first`() {
    val plan: ForwardCallablePlan = planOf(
      parameter("amount", ForwardAbiRole.USER),
      parameter("handle", ForwardAbiRole.RECEIVER),
    )

    val error: IllegalArgumentException = assertFailsWith { plan.validate() }

    assertTrue(error.message!!.contains("receiver"))
  }

  @Test
  fun `accepts a receiver first and an error slot last`() {
    val plan: ForwardCallablePlan = planOf(
      parameter("handle", ForwardAbiRole.RECEIVER),
      parameter("amount", ForwardAbiRole.USER),
      parameter("errorOut", ForwardAbiRole.ERROR, ForwardAbiDirection.OUT),
    )

    assertEquals(plan, plan.validate())
  }

  private fun parameter(
    name: String,
    role: ForwardAbiRole,
    direction: ForwardAbiDirection = ForwardAbiDirection.IN,
  ): ForwardAbiParameter = ForwardAbiParameter(
    name = name,
    wireType = ForwardAbiWireType.POINTER,
    direction = direction,
    transfer = ForwardTransfer(
      subject = name,
      type = BridgeType.ObjectHandle("sample.Account"),
      flow = if (direction == ForwardAbiDirection.IN) ForwardFlow.INTO_KOTLIN
      else ForwardFlow.OUT_OF_KOTLIN,
      passing = if (direction == ForwardAbiDirection.IN) ForwardPassing.VALUE
      else ForwardPassing.OUT,
      ownership = ForwardOwnership.BORROWED,
      conversion = if (direction == ForwardAbiDirection.IN) {
        ForwardConversion.HANDLE_TO_STABLE_REF
      } else {
        ForwardConversion.STABLE_REF_TO_HANDLE
      },
    ),
    role = role,
  )

  private fun planOf(vararg parameters: ForwardAbiParameter): ForwardCallablePlan {
    val call = ForwardNativeCall(
      exportName = "sample_account_deposit",
      result = ForwardAbiWireType.VOID,
      parameters = parameters.toList(),
    )
    return ForwardCallablePlan(
      invocation = ForwardInvocation("sample.Account.deposit"),
      publicSignature = ForwardPublicSignature("Deposit", emptyList(), BridgeType.Unit),
      evaluation = ForwardEvaluation.EXACTLY_ONCE,
      nativeExports = listOf(call),
      nativeImports = listOf(call),
      result = ForwardResultConvention(
        wireType = ForwardAbiWireType.VOID,
        transfer = ForwardTransfer(
          subject = "result",
          type = BridgeType.Unit,
          flow = ForwardFlow.OUT_OF_KOTLIN,
          passing = ForwardPassing.VALUE,
          ownership = ForwardOwnership.BORROWED,
          conversion = ForwardConversion.DIRECT,
        ),
      ),
      helperRequirements = setOf(ForwardHelperRequirement.STABLE_REF),
    )
  }
}
