package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

/**
 * ADR-210 amendment: a `Flow<T>?` parameter is an `IAsyncEnumerable<T>?`. A C# `null` registers
 * nothing and passes a zero key, which Kotlin reads as a `null` flow.
 */
class Tier1NullableFlowParameterTest {

  @Test
  fun `a nullable Flow parameter binds on the plan and legacy routes`() {
    val result: Tier1Result = Tier1Harness.run(
      """
      package tier1.nullableflowparam

      import kotlinx.coroutines.flow.Flow
      import kotlinx.coroutines.flow.toList

      fun hasFlap(flap: Flow<String>?): Boolean = flap != null

      suspend fun count(visitors: Flow<Int>?): Int = visitors?.toList()?.size ?: -1

      class Porch {
        suspend fun climb(steps: Flow<Int>?): Int = steps?.toList()?.size ?: 0
      }
      """.trimIndent(),
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )

    assertTrue(result.compiledClean, "got: ${result.compileErrors} ${result.kspErrors}")
    assertTrue(
      result.kspWarnings.none { "nullableflowparam" in it || "Porch.climb" in it || "count" in it },
      "kspWarnings=${result.kspWarnings}",
    )
    val cs: String = result.generatedCSharp
    assertContains(cs, "HasFlap(global::System.Collections.Generic.IAsyncEnumerable<string>? flap)")
    assertContains(cs, "CountAsync(global::System.Collections.Generic.IAsyncEnumerable<int>? visitors")
    assertContains(cs, "ClimbAsync(global::System.Collections.Generic.IAsyncEnumerable<int>? steps")
    assertContains(cs, "flap is null ? IntPtr.Zero : NugetTasks.Register(flap")
    assertContains(result.generated, "flapKey: COpaquePointer?")
    assertContains(result.generated, "if (visitorsKey == null) null else")
  }
}
