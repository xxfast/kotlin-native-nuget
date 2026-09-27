package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Generator names *derived* from a user parameter's name (`${name}HasValue`, `${name}IsSet`,
 * `default_${name}`, `mask`, the C# `${name}Value` local, the legacy `${name}Arg` local and the
 * legacy `scopeHandle` / `userData` slots), meeting a real user parameter of the same spelling.
 *
 * The rule: the generator's identifier moves (`freshName`, one `_` at a time), never the user's,
 * and only on a real collision. Before it, most shapes declared one name twice in the `@CName`
 * export (`Conflicting declarations`), and two compiled clean while misrouting: the dispatcher's
 * `val default_limit` / `var mask` locals shadowed the user's own `default_limit` / `mask`.
 *
 * Every reader must take the minted name, not rebuild it: a reader still spelling `limitHasValue`
 * after the slot moved to `limitHasValue_` resolves to the user's parameter and compiles clean, so
 * the cells pin the *read* sites as well as the declarations.
 *
 * Mirrors `test-library/.../test/reserved/DerivedNamesSample.kt`, whose
 * `IntegrationTests.DerivedNamesTests` prove each argument reaches its own parameter at runtime.
 */
class Tier1DerivedNamesTest {

  private val fixture: String = """
    package tier1.derived

    import kotlin.time.Duration
    import kotlin.time.Instant
    import kotlinx.coroutines.flow.Flow
    import kotlinx.coroutines.flow.MutableStateFlow
    import kotlinx.coroutines.flow.StateFlow
    import kotlinx.coroutines.flow.flow

    enum class Appetite { PECKISH, RAVENOUS }
    @JvmInline value class Scoop(val grams: Int)
    class Kibble(val brand: String)

    fun measure(limit: Int?, limitHasValue: Boolean): String = "" + limit + limitHasValue
    fun measureReversed(limitHasValue: Boolean, limit: Int?): String = "" + limitHasValue + limit
    fun judge(mood: Appetite?, moodHasValue: Boolean): String = "" + mood + moodHasValue
    fun weigh(w: Scoop?, wHasValue: Boolean): String = "" + w?.grams + wHasValue
    fun wait(d: Duration?, dHasValue: Boolean): String = "" + d + dHasValue
    fun letter(c: Char?, cHasValue: Boolean): String = "" + c + cHasValue
    fun stamp(at: Instant?, atHasValue: Boolean): String = "" + at + atHasValue
    fun control(limit: Int? = 3, other: Int = 4): String = "" + limit + other

    class Hopper(val limit: Int?, val limitHasValue: Boolean)

    class Pantry {
      fun fill(limit: Int?, limitHasValue: Boolean): String = "" + limit + limitHasValue
      fun top(limit: Int? = 3, limitHasValue: Boolean = true): String = "" + limit + limitHasValue
      fun serve(limit: Int? = 3, limitIsSet: Boolean): String = "" + limit + limitIsSet
      fun pour(limit: Int? = 3, default_limit: Int?): String = "" + limit + default_limit
      fun ration(limit: Int = 3, mask: Int): String = "" + limit + mask
      fun ladle(limit: Int? = 3, limitValue: Int): String = "" + limit + limitValue
    }

    class Larder {
      suspend fun fill(limit: Int?, limitHasValue: Boolean): String = "" + limit + limitHasValue
      fun snacks(limit: Int?, limitHasValue: Boolean): Flow<String> = flow { emit("" + limit) }
      fun bowl(limit: Int?, limitHasValue: Boolean): StateFlow<String> = MutableStateFlow("" + limit)
      suspend fun dish(limit: Int?, limitHasValue: Boolean): StateFlow<String> =
        MutableStateFlow("" + limit)
      suspend fun count(scopeHandle: Int, userData: Int): String = "" + scopeHandle + userData
    }

    suspend fun portion(limit: Int?, limitHasValue: Boolean): String = "" + limit + limitHasValue
    suspend fun mix(x: Kibble, xArg: Kibble): String = x.brand + xArg.brand
    suspend fun tally(scopeHandle: Int, userData: Int): String = "" + scopeHandle + userData

    class Spout {
      fun trickle(onNext: Int, userData: Int, scopeHandle: Int, obj: Int, scope: Int): Flow<String> =
        flow { emit("" + onNext + userData + scopeHandle + obj + scope) }
      fun level(flow: Int, collectScope: Int): MutableStateFlow<String> =
        MutableStateFlow("" + flow + collectScope)
    }

    fun tick(onTick: () -> Int, onTickPtr: Int, onTickUserData: Int, onTickNative: Int, onTickCtx: Int): String =
      "" + onTick() + onTickPtr + onTickUserData + onTickNative + onTickCtx
    fun label(tags: List<String>, tagsHandle: Int): String = "" + tags + tagsHandle
    suspend fun brew(tcs: Int, callback: Int, callbackHandle: Int, job: Int, jobHandle: Int, reg: Int): String =
      "" + tcs + callback + callbackHandle + job + jobHandle + reg
    suspend fun sift(tags: List<String>, tagsHandle: Int): String = "" + tags + tagsHandle

    class Kettle {
      suspend fun stir(obj: Int, scope: Int, callbackPtr: Int, result: Int, resultRef: Int): String =
        "" + obj + scope + callbackPtr + result + resultRef
      suspend fun pour(cancellationToken: Int): String = "" + cancellationToken
    }

    suspend fun steep(cancellationToken: Int): String = "" + cancellationToken
    fun relay(a0: (Int) -> String): String = a0(4)
    fun echo(ctx: (Int) -> String): String = ctx(5)
  """.trimIndent()

  @Test
  fun `the generated cancellation token and the delegate lambda's parameters move off the user's`() {
    assertCsharp("public Task<string> PourAsync(int cancellationToken, CancellationToken cancellationToken_ = default)")
    assertCsharp("public static Task<string> SteepAsync(int cancellationToken, CancellationToken cancellationToken_ = default)")
    assertCsharp("cancellationToken_.CanBeCanceled")
    assertCsharp("t.TrySetCanceled(cancellationToken_);")
    assertCsharp("(int a0_, IntPtr ctx) =>")
    assertCsharp("a0(a0_)")
    assertCsharp("(int a0, IntPtr ctx_) =>")
    assertCsharp("ctx(a0)")
  }

  private val result: Tier1Result by lazy {
    Tier1Harness.run(
      fixture,
      fileName = "Derived.kt",
      processorOptions = mapOf("nuget.rootPackage" to "tier1"),
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )
  }

  @Test
  fun `the run succeeds and the generated Kotlin compiles`() {
    assertEquals(emptyList(), result.kspErrors, "KSP errors (an ADR-055 mismatch lands here)")
    assertTrue(result.compiledClean, "generated Kotlin must compile; got: ${result.compileErrors}")
  }

  @Test
  fun `a plan-route HasValue slot moves off the user's parameter, at every read site`() {
    val measure: String = exportEndingWith("__measure")
    assertTrue(measure.contains("limitHasValue_: Boolean,"), measure)
    assertTrue(measure.contains("measure(if (limitHasValue_) limit else null, limitHasValue)"), measure)

    val reversed: String = exportEndingWith("__measureReversed")
    assertTrue(
      reversed.contains("measureReversed(limitHasValue, if (limitHasValue_) limit else null)"),
      reversed,
    )

    assertTrue(exportEndingWith("__judge").contains("if (moodHasValue_) tier1.derived.Appetite"))
    assertTrue(exportEndingWith("__weigh").contains("if (wHasValue_) tier1.derived.Scoop("))
    assertTrue(exportEndingWith("__wait").contains("if (dHasValue_) durationFromDotNetTicks(d)"))
    assertTrue(exportEndingWith("__letter").contains("if (cHasValue_) c else null"))
    assertTrue(exportEndingWith("__stamp").contains("if (atHasValue_) instantFromDotNetTicks(at)"))
    assertTrue(exportEndingWith("__hopper_create").contains("if (limitHasValue_) limit else null"))
    assertTrue(exportEndingWith("__pantry_fill").contains("if (limitHasValue_) limit else null"))

    // The public surface keeps the author's names exactly.
    assertCsharp("public static string Measure(int? limit, bool limitHasValue)")
    assertCsharp("public string Fill(int? limit, bool limitHasValue)")
    assertCsharp("bool limitHasValue_, int limit, bool limitHasValue, out IntPtr error);")
  }

  @Test
  fun `the dispatcher's IsSet slot, default local and mask move off the user's parameters`() {
    val top: String = exportEndingWith("__pantry_top")
    assertTrue(top.contains("val default_limit = if (limitHasValue_) limit else null"), top)
    assertTrue(top.contains("limitHasValueHasValue: Boolean,"), top)

    val serve: String = exportEndingWith("__pantry_serve")
    assertTrue(serve.contains("limitIsSet_: Boolean,"), serve)
    assertTrue(serve.contains("if (limitIsSet_) mask = mask or 1"), serve)
    assertTrue(serve.contains("limitIsSet = limitIsSet)"), serve)

    val pour: String = exportEndingWith("__pantry_pour")
    assertTrue(pour.contains("val default_limit_ = if (limitHasValue) limit else null"), pour)
    assertTrue(
      pour.contains(
        "pour(limit = default_limit_, default_limit = if (default_limitHasValue) default_limit else null)"
      ),
      pour,
    )

    val ration: String = exportEndingWith("__pantry_ration")
    assertTrue(ration.contains("var mask_ = 0"), ration)
    assertTrue(ration.contains("when (mask_) {"), ration)
    assertTrue(ration.contains("ration(limit = default_limit!!, mask = mask)"), ration)
  }

  @Test
  fun `the C# Optional value local moves off the user's parameter`() {
    assertCsharp("public string Ladle(Optional<int?> limit, int limitValue)")
    assertCsharp("var limitValue_ = limit.Value;")
    assertCsharp("limitValue_.HasValue, limitValue_.GetValueOrDefault(), limitValue, out IntPtr error);")
  }

  @Test
  fun `a legacy-route HasValue slot moves off the user's parameter on every route`() {
    listOf(
      "__larder_fill_async", "__portion_async", "__larder_dish_async",
      "__larder_snacks_collect", "__larder_bowl_collect", "__larder_bowl_value",
    ).forEach { suffix ->
      val export: String = exportEndingWith(suffix)
      assertTrue(export.contains("limitHasValue_: Boolean,"), export)
      assertTrue(export.contains("(if (limitHasValue_) limit else null, limitHasValue)"), export)
    }
    assertCsharp("public Task<string> FillAsync(int? limit, bool limitHasValue, CancellationToken")
    assertCsharp("IntPtr scopeHandle, bool limitHasValue_, int limit, bool limitHasValue, IntPtr callback")
  }

  @Test
  fun `a legacy lowered local and the fixed suspend slots move off the user's parameters`() {
    val mix: String = exportEndingWith("__mix_async")
    assertTrue(mix.contains("val xArg_ = x.asStableRef<tier1.derived.Kibble>().get()"), mix)
    assertTrue(mix.contains("tier1.derived.mix(xArg_, xArgArg)"), mix)

    val count: String = exportEndingWith("__larder_count_async")
    assertTrue(count.contains("scopeHandle_: COpaquePointer,"), count)
    assertTrue(count.contains("userData_: COpaquePointer,"), count)
    assertTrue(count.contains("val scope = scopeHandle_.asStableRef<CoroutineScope>().get()"), count)
    assertTrue(count.contains("launchForCSharp(scope, callbackPtr, userData_)"), count)
    assertTrue(count.contains("obj.count(scopeHandle, userData)"), count)

    val tally: String = exportEndingWith("__tally_async")
    assertTrue(tally.contains("callbackPtr, userData_)"), tally)
    assertTrue(tally.contains("tier1.derived.tally(scopeHandle, userData)"), tally)

    assertCsharp("Native_CountAsync(IntPtr handle, IntPtr scopeHandle_, int scopeHandle, int userData, IntPtr callback, IntPtr userData_);")
    assertCsharp("TallyAsync_native(int scopeHandle, int userData, IntPtr callback, IntPtr userData_);")
  }

  @Test
  fun `the legacy Flow slots, body names and C# collect-lambda parameters move off the user's`() {
    val trickle: String = exportEndingWith("__spout_trickle_collect")
    assertTrue(trickle.contains("scopeHandle_: COpaquePointer,"), trickle)
    assertTrue(trickle.contains("userData_: COpaquePointer,"), trickle)
    assertTrue(trickle.contains("val obj_ = handle.asStableRef"), trickle)
    assertTrue(trickle.contains("val scope_ = scopeHandle_.asStableRef"), trickle)
    assertTrue(trickle.contains("obj_.trickle(onNext, userData, scopeHandle, obj, scope)"), trickle)

    assertCsharp("return new KotlinFlow<string>((onNext_, onComplete, onError, userData_) =>")
    assertCsharp(
      "Native_TrickleCollect(_handle, GetOrCreateScope(), onNext, userData, scopeHandle, obj, scope, " +
          "onNext_, onComplete, onError, userData_)"
    )
    assertCsharp("IntPtr flow_ = Native_Level(_handle, flow, collectScope);")
    assertCsharp("IntPtr collectScope_ = GetOrCreateScope();")
  }

  @Test
  fun `the plan-route callback slots and C# wrapper locals move off the user's`() {
    val tick: String = exportEndingWith("__tick")
    assertTrue(tick.contains("onTickPtr_: COpaquePointer"), tick)
    assertTrue(tick.contains("onTickUserData_: COpaquePointer"), tick)
    assertTrue(tick.contains("onTickPtr_.reinterpret<"), tick)
    assertCsharp("IntPtr onTick_Ctx = IntPtr.Zero;")
    assertCsharp("IntPtr tags_Handle")
  }

  @Test
  fun `the legacy suspend wrapper and body locals move off the user's`() {
    assertCsharp("var tcs_ = new TaskCompletionSource<string>")
    assertCsharp("IntPtr jobHandle_ = BrewAsync_native(tcs, callback, callbackHandle, job, jobHandle, reg,")
    assertCsharp("IntPtr tagsHandle_ = ")
    val stir: String = exportEndingWith("__kettle_stir_async")
    assertTrue(stir.contains("callbackPtr_: COpaquePointer,"), stir)
    assertTrue(stir.contains("val obj_ = handle.asStableRef"), stir)
    assertTrue(stir.contains("launchForCSharp(scope_, callbackPtr_, userData)"), stir)
    assertTrue(stir.contains("obj_.stir(obj, scope, callbackPtr, result, resultRef)"), stir)
  }

  /** Tier 1 never compiles C#, so a duplicate extern parameter (CS0100) is checked structurally. */
  @Test
  fun `no extern declares one parameter name twice`() {
    val duplicated: List<String> = result.generatedCSharp.lines()
      .filter { line -> line.contains("static extern") }
      .filter { line ->
        val names: List<String> = line.substringAfter('(').substringBeforeLast(')')
          .split(',')
          .map { parameter -> parameter.trim().substringAfterLast(' ') }
          .filter { name -> name.isNotEmpty() }
        names.size != names.toSet().size
      }
    assertEquals(emptyList(), duplicated)
  }

  /** Collision-only: a callable with nothing to collide with keeps every unrenamed spelling. */
  @Test
  fun `a non-colliding callable keeps the unrenamed spellings`() {
    val control: String = exportEndingWith("__control")
    assertTrue(control.contains("limitHasValue: Boolean,"), control)
    assertTrue(control.contains("val default_limit = if (limitHasValue) limit else null"), control)
    assertTrue(control.contains("var mask = 0"), control)
    assertFalse(control.contains("_ ="), control)
  }

  private fun exportEndingWith(suffix: String): String {
    val start: Int = result.generated.indexOf("fun export_library_derived$suffix(")
    assertTrue(start >= 0, "no export ending with $suffix in:\n${result.generated}")
    val end: Int = result.generated.indexOf("\n@CName", start).takeIf { it >= 0 } ?: result.generated.length
    return result.generated.substring(start, end)
  }

  private fun assertCsharp(expected: String) {
    assertTrue(result.generatedCSharp.contains(expected), "missing from Interop.cs: $expected")
  }
}
