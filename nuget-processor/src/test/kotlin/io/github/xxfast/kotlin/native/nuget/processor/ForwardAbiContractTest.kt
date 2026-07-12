package io.github.xxfast.kotlin.native.nuget.processor

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ForwardAbiContractTest {
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

  private fun signature(
    name: String,
    vararg parameters: ForwardAbiParameter,
    result: ForwardAbiType = ForwardAbiType.BOOL,
  ): ForwardAbiSignature = ForwardAbiSignature(name, result, parameters.toList())
}
