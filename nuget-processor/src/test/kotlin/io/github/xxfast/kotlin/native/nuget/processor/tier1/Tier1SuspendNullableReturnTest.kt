package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

/**
 * Issue #108: a `suspend fun` returning a nullable type broke both halves of the bridge.
 *
 * Kotlin half: `buildSuspendFunctionBody` / `buildSuspendMethodBody` branched only on `isUnit`,
 * so a nullable result reached `StableRef.create(result)`, whose `T` is bound to `Any`. The
 * generated file did not compile, which the harness's compile step reproduces (its `StableRef`
 * stand-in carries the same `T : Any` bound).
 *
 * C# half: `asyncReturnType` was built from `simpleName`, which drops the `?`, so a null result
 * arrived as `new Cat(IntPtr.Zero)` (a live wrapper over a null handle) or as a `0` from
 * `FromHandle<int>`. The behavioural half of that is asserted end to end by
 * `IntegrationTests/SuspendNullableReturnTests.cs`; this test pins the generated shapes.
 */
class Tier1SuspendNullableReturnTest {

  private val fixture = """
    package tier1.suspendnullable

    class Cat(val name: String)

    suspend fun findShelterCat(catName: String): Cat? = if (catName == "Mylo") Cat(catName) else null

    suspend fun countTreatsLeft(catName: String): Int? = if (catName == "Oreo") 7 else null

    suspend fun fetchGreeting(name: String): String = "Hello, ${'$'}name!"

    class CatService {
      suspend fun findAdoptedCat(catName: String): Cat? = if (catName == "Mylo") Cat(catName) else null

      suspend fun countWhiskers(catName: String): Int? = if (catName == "Oreo") 24 else null
    }
    """.trimIndent()

  @Test
  fun `nullable suspend returns compile and guard the StableRef on both builders`() {
    val result = Tier1Harness.run(fixture)

    assertTrue(
      result.compiledClean,
      "expected the generated suspend exports to compile; got: ${result.compileErrors}",
    )

    // Top-level: buildSuspendFunctionBody.
    assertContains(
      result.generated,
      "val resultRef = if (result == null) null else StableRef.create(result).asCPointer()",
      message = "expected a null-guarded resultRef; generated=${result.generated}",
    )
    // Class method: buildSuspendMethodBody. Both builders emit the same guard, so a single
    // occurrence would mean only one of them was fixed.
    assertTrue(
      result.generated.split("if (result == null) null else StableRef.create(result)").size - 1 >= 4,
      "expected all four nullable suspend exports to be guarded; generated=${result.generated}",
    )
    // A non-nullable return keeps the unguarded shape.
    assertContains(
      result.generated,
      "val resultRef = StableRef.create(result).asCPointer()",
      message = "expected the non-nullable return to stay unguarded; generated=${result.generated}",
    )
  }

  @Test
  fun `nullable suspend returns carry nullability into the C# Task type`() {
    val csharp: String = Tier1Harness.run(fixture).generatedCSharp

    assertContains(csharp, "Task<Cat?> FindShelterCatAsync")
    assertContains(csharp, "Task<int?> CountTreatsLeftAsync")
    assertContains(csharp, "Task<Cat?> FindAdoptedCatAsync")
    assertContains(csharp, "Task<int?> CountWhiskersAsync")
    // A non-nullable return is unchanged.
    assertContains(csharp, "Task<string> FetchGreetingAsync")

    // The object case has no `FromHandle` null guard to lean on, so the null is tested on the
    // wire pointer; the primitive case rides ADR-067's `Nullable.GetUnderlyingType` branch.
    assertContains(csharp, "t.SetResult(resultPtr == IntPtr.Zero ? null : new Cat(resultPtr));")
    assertContains(csharp, "t.SetResult(NugetMarshal.FromHandle<int?>(resultPtr));")
  }
}
