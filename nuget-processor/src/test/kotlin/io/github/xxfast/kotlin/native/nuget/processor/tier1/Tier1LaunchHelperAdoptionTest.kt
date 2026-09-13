package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ADR-128 PR-E: the four legacy emitter templates (`buildSuspendFunctionBody`,
 * `buildSuspendMethodBody`, `buildFlowCollectBody`, `buildFlowMethodCollectBody`) emit a call to
 * the runtime's `launchForCSharp` / `collectForCSharp` instead of repeating the launch shape --
 * `reinterpret`, `launch(start = CoroutineStart.ATOMIC)`, and the success/cancel/error arms -- once
 * per export. Every `@CName` name and the whole C# side are unchanged; this test pins what the
 * generated *body* may and may not contain.
 *
 * What must survive the move, per route:
 * - the scope: a top-level suspend function still launches on its own
 *   `CoroutineScope(Dispatchers.Default)`; a method still launches on the C#-owned scope read from
 *   `scopeHandle` (that is the scope `nuget_scope_cancel` cancels).
 * - the mint: `NugetHandles.retain(...)` stays in the generated text, inside the body lambda, so
 *   the handle-count deltas `LeakTests` pins cannot move.
 * - the Flow source: `obj.method(...)` / `obj.prop` is evaluated INSIDE the body lambda, so a
 *   Flow-returning method that throws still reaches C# as `onError` rather than escaping a
 *   `@CName` export (ADR-128 alternative 3, rejected for exactly this reason).
 */
class Tier1LaunchHelperAdoptionTest {

  private val topLevelSuspendFixture = """
    package tier1.launchhelper

    data class Cat(val name: String, val treats: Int)

    suspend fun adoptCat(name: String): Cat = Cat(name, 3)

    suspend fun cleanLitter() {}
    """.trimIndent()

  private val suspendMethodFixture = """
    package tier1.launchhelper

    class Shelter {
      suspend fun feed(name: String) {}
      suspend fun findCat(name: String): String? = if (name == "Mylo") name else null
    }
    """.trimIndent()

  private val flowFixture = """
    package tier1.launchhelper

    import kotlinx.coroutines.flow.Flow
    import kotlinx.coroutines.flow.flow

    class Radio {
      val purrs: Flow<Int> = flow { emit(1) }
      fun station(name: String): Flow<String> = flow { emit(name) }
    }
    """.trimIndent()

  private fun run(fixture: String): Tier1Result = Tier1Harness.run(
    fixture,
    // `Flow` must resolve on the KSP libraries path or the flow route is never taken at all.
    libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    coroutinesOnCompileClasspath = true,
  )

  @Test
  fun `a top-level suspend function launches through the runtime helper`() {
    val result = run(topLevelSuspendFixture)

    assertTrue(
      result.compiledClean,
      "expected the helper-based suspend export to compile; got: ${result.compileErrors} " +
          "${result.kspErrors}",
    )

    val body: String = exportBody(result, "adoptCat_async")

    assertContains(
      body,
      "launchForCSharp(CoroutineScope(Dispatchers.Default), callbackPtr, userData)",
      message = "expected the ad-hoc scope to be passed to the helper; got: $body",
    )
    assertContains(
      body,
      "NugetHandles.retain(result)",
      message = "expected the result to still be minted in the generated body; got: $body",
    )
    assertNoInlineLaunchShape(body)

    // The top-level `Unit` route: nothing to mint, so the body's last expression is the `null`
    // the helper hands its success arm -- the one arm of `buildSuspendFunctionBody` that no other
    // Tier 1 fixture reaches.
    val unitBody: String = exportBody(result, "cleanLitter_async")
    assertContains(
      unitBody,
      "launchForCSharp(CoroutineScope(Dispatchers.Default), callbackPtr, userData)",
      message = "expected the Unit route to use the helper too; got: $unitBody",
    )
    assertContains(
      unitBody,
      "cleanLitter()\n  null",
      message = "expected the Unit route's body to end in `null`; got: $unitBody",
    )
    assertNoInlineLaunchShape(unitBody)
  }

  @Test
  fun `suspend methods launch on the scope handle through the runtime helper`() {
    val result = run(suspendMethodFixture)

    assertTrue(
      result.compiledClean,
      "expected the helper-based suspend method exports to compile; got: ${result.compileErrors} " +
          "${result.kspErrors}",
    )

    // The `Unit` route: no result to mint, so the body's last expression is `null`.
    val unitBody: String = exportBody(result, "shelter_feed_async")
    assertContains(
      unitBody,
      "val scope = scopeHandle.asStableRef<CoroutineScope>().get()",
      message = "expected the C#-owned scope to still be read from the handle; got: $unitBody",
    )
    assertContains(
      unitBody,
      "launchForCSharp(scope, callbackPtr, userData)",
      message = "expected the scope handle's scope to be passed to the helper; got: $unitBody",
    )
    assertNoInlineLaunchShape(unitBody)

    // The nullable-result route: the null guard (issue #108) is unchanged and stays in the body.
    val nullableBody: String = exportBody(result, "shelter_findCat_async")
    assertContains(
      nullableBody,
      "launchForCSharp(scope, callbackPtr, userData)",
      message = "expected the method route to use the helper; got: $nullableBody",
    )
    assertContains(
      nullableBody,
      "if (result == null) null else NugetHandles.retain(result)",
      message = "expected the nullable mint to stay in the generated body; got: $nullableBody",
    )
    assertNoInlineLaunchShape(nullableBody)
  }

  @Test
  fun `flow property and method collect through the runtime helper`() {
    val result = run(flowFixture)

    assertTrue(
      result.compiledClean,
      "expected the helper-based flow exports to compile; got: ${result.compileErrors} " +
          "${result.kspErrors}",
    )

    val propertyBody: String = exportBody(result, "radio_get_purrs_collect")
    assertContains(
      propertyBody,
      "collectForCSharp(scope, onNextPtr, onCompletePtr, onErrorPtr, userData)",
      message = "expected the property collect to use the helper; got: $propertyBody",
    )
    assertContains(
      propertyBody,
      "NugetHandles.retain(",
      message = "expected the per-item mint to stay in the generated body; got: $propertyBody",
    )
    assertNoInlineLaunchShape(propertyBody)

    val methodBody: String = exportBody(result, "radio_station_collect")
    assertContains(
      methodBody,
      "collectForCSharp(scope, onNextPtr, onCompletePtr, onErrorPtr, userData)",
      message = "expected the method collect to use the helper; got: $methodBody",
    )
    assertNoInlineLaunchShape(methodBody)

    // ADR-128's load-bearing claim: the Flow source is reached INSIDE the coroutine, so a method
    // that throws on the way to its Flow reaches C# as `onError`. Source order is the proof.
    val helperCall: Int = methodBody.indexOf("collectForCSharp(")
    val flowSource: Int = methodBody.indexOf("obj.station(")
    assertTrue(
      helperCall >= 0 && flowSource > helperCall,
      "expected the Flow source to be evaluated INSIDE the helper's body lambda (after the " +
          "collectForCSharp( token); got: $methodBody",
    )
  }

  /** Neither the suspend nor the Flow body may still spell the launch shape the helper owns. */
  private fun assertNoInlineLaunchShape(body: String) {
    assertEquals(0, body.occurrences("scope.launch("), "inline launch survived; got: $body")
    assertEquals(
      0,
      body.occurrences("CoroutineStart.ATOMIC"),
      "the ATOMIC start belongs to the runtime helper now; got: $body",
    )
    assertEquals(
      0,
      body.occurrences("catch (e: CancellationException)"),
      "the cancel arm belongs to the runtime helper now; got: $body",
    )
  }

  private fun String.occurrences(needle: String): Int = split(needle).size - 1

  /** The generated Kotlin from one `@CName` annotation up to the next one. */
  private fun exportBody(result: Tier1Result, export: String): String {
    val generated: String = result.generated
    val start: Int = generated.indexOf("@CName(\"$export\")")
    require(start >= 0) {
      "no @CName(\"$export\") in the generated Kotlin; exports present: " +
          generated.lines().filter { it.contains("@CName(") }.map(String::trim)
    }
    val rest: String = generated.substring(start + 1)
    val next: Int = rest.indexOf("@CName(\"")
    return if (next >= 0) rest.substring(0, next) else rest
  }
}
