package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.nonNullStringOrThrow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ADR-164 on the legacy `suspend` / `Flow` routes, and the enum parameter those routes used to
 * refuse (`SKIPPED_UNSUPPORTED_INPUT`, ADR-122 Alternative 6).
 *
 * A defaulted parameter widens on both halves: C# gets an optional (`int? portion = null`,
 * `global::Kotlin.Native.Interop.KotlinOptional<int?> treats = default`), and the Kotlin export dispatches `when (mask)` over named
 * calls, so an omitted argument makes Kotlin evaluate its own default. The legacy bodies used to
 * call positionally with every argument, so a C#-only `= null` would have compiled and handed
 * Kotlin a zero filler instead. The runtime half is `IntegrationTests.LegacyRouteDefaultsTests`
 * over `test-library/.../test/suppertime/SuppertimeSample.kt`.
 */
class Tier1LegacyRouteDefaultsTest {

  private val fixture: String = """
    package tier1.legacydefaults

    import kotlinx.coroutines.flow.Flow
    import kotlinx.coroutines.flow.MutableStateFlow
    import kotlinx.coroutines.flow.StateFlow
    import kotlinx.coroutines.flow.flow

    enum class Hunger { PECKISH, STARVING }

    class Placemat(val owner: String)

    class Bell(val bowl: Int) {
      suspend fun feed(cat: String, portion: Int = bowl, note: String = "n", treats: Int? = 5): String =
        cat + portion + note + treats
      fun drip(mask: Int, drops: Int = bowl): Flow<String> = flow { emit("" + mask + drops) }
      suspend fun beg(asked: Hunger, hunger: Hunger = Hunger.PECKISH, fallback: Hunger? = Hunger.STARVING): String =
        "" + asked + hunger + fallback
      suspend fun count(): Int = -1
      suspend fun count(limit: Int = 3): Int = limit
      suspend fun ration(portion: Int = bowl, mask: Int): String = "" + portion + mask
      suspend fun share(mat: Placemat = Placemat("house"), grams: Int = bowl): String =
        mat.owner + grams
      suspend fun settle(cat: String, mat: Placemat? = Placemat("house")): String = cat + mat?.owner
      fun lounge(cat: String, mat: Placemat? = Placemat("house")): Flow<String> =
        flow { emit(cat + mat?.owner) }
      fun snooze(mat: Placemat = Placemat("house")): StateFlow<String> = MutableStateFlow(mat.owner)
    }

    suspend fun tuck(cat: String, mat: Placemat? = Placemat("house")): String = cat + mat?.owner

    suspend fun weighIn(name: String, grams: Int = name.length): String = name + grams
  """.trimIndent()

  private val result: Tier1Result by lazy {
    Tier1Harness.run(
      fixture,
      fileName = "LegacyDefaults.kt",
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
  fun `a legacy suspend member dispatches its defaults on a mask built before the launch`() {
    val feed: String = exportEndingWith("__bell_feed_async")
    assertTrue(feed.contains("portionHasValue: Boolean,"), feed)
    assertTrue(feed.contains("treatsIsSet: Boolean,\n  treatsHasValue: Boolean,\n  treats: Int,"), feed)
    val mask = "val mask = (if (portionHasValue) 1 else 0) or (if (note != null) 2 else 0) or " +
        "(if (treatsIsSet) 4 else 0)"
    assertTrue(feed.indexOf(mask) in 0 until feed.indexOf("launchForCSharp"), feed)
    assertTrue(feed.contains("0 -> obj.feed(cat)"), feed)
    assertTrue(feed.contains("2 -> obj.feed(cat, note = note!!)"), feed)
    assertTrue(
      feed.contains(
        "7 -> obj.feed(cat, portion = portion, note = note!!, treats = if (treatsHasValue) " +
            "treats else null)"
      ),
      feed,
    )

    assertCsharp(
      "public Task<string> FeedAsync(string cat, int? portion = null, string? note = null, " +
          "global::Kotlin.Native.Interop.KotlinOptional<int?> treats = default, CancellationToken cancellationToken = default)"
    )
    assertCsharp(
      "${nonNullStringOrThrow("cat")}, portion.HasValue, portion.GetValueOrDefault(), note, " +
          "treats.HasValue, " +
          "treats.Value.HasValue, treats.Value.GetValueOrDefault(),"
    )
  }

  @Test
  fun `a top-level suspend function widens and dispatches the same way`() {
    val weighIn: String = exportEndingWith("__weighIn_async")
    assertTrue(weighIn.contains("0 -> tier1.legacydefaults.weighIn(name)"), weighIn)
    assertTrue(weighIn.contains("1 -> tier1.legacydefaults.weighIn(name, grams = grams)"), weighIn)
    assertCsharp(
      "public static Task<string> WeighInAsync(string name, int? grams = null, " +
          "CancellationToken cancellationToken = default)"
    )
  }

  @Test
  fun `a user parameter named mask keeps its name and the dispatcher's moves`() {
    val drip: String = exportEndingWith("__bell_drip_collect")
    assertTrue(drip.contains("val mask_ = (if (dropsHasValue) 1 else 0)"), drip)
    assertTrue(drip.contains("(when (mask_) {"), drip)
    assertTrue(drip.contains("1 -> obj.drip(mask, drops = drops)"), drip)
    assertCsharp("public KotlinFlow<string> Drip(int mask, int? drops = null)")

    val ration: String = exportEndingWith("__bell_ration_async")
    assertTrue(ration.contains("1 -> obj.ration(portion = portion, mask = mask)"), ration)
    // ADR-164 rule 5: a default before a required parameter widens without a C# default.
    assertCsharp("public Task<string> RationAsync(int? portion, int mask, CancellationToken")
  }

  @Test
  fun `an enum parameter binds by ordinal on the legacy route, defaulted or not`() {
    val beg: String = exportEndingWith("__bell_beg_async")
    assertTrue(beg.contains("asked: Int,"), beg)
    assertTrue(beg.contains("fallbackIsSet: Boolean,\n  fallbackHasValue: Boolean,\n  fallback: Int,"), beg)
    assertTrue(beg.contains("0 -> obj.beg(tier1.legacydefaults.Hunger.entries[asked])"), beg)
    assertTrue(
      beg.contains(
        "hunger = tier1.legacydefaults.Hunger.entries[hunger], fallback = if (fallbackHasValue) " +
            "tier1.legacydefaults.Hunger.entries[fallback] else null)"
      ),
      beg,
    )

    assertCsharp("Hunger asked, ")
    assertCsharp("Hunger? hunger = null, global::Kotlin.Native.Interop.KotlinOptional<")
    assertCsharp(
      "(int)asked, hunger.HasValue, (int)hunger.GetValueOrDefault(), fallback.HasValue, " +
          "fallback.Value.HasValue, (int)fallback.Value.GetValueOrDefault(),"
    )
  }

  @Test
  fun `a defaulted handle parameter widens to a nullable handle that Kotlin defaults when null`() {
    val share: String = exportEndingWith("__bell_share_async")
    assertTrue(share.contains("mat: COpaquePointer?,"), share)
    val lowered = "val matArg = mat?.asStableRef<tier1.legacydefaults.Placemat>()?.get()"
    assertTrue(share.indexOf(lowered) in 0 until share.indexOf("launchForCSharp"), share)
    assertTrue(share.contains("val mask = (if (mat != null) 1 else 0) or"), share)
    assertTrue(share.contains("0 -> obj.share()"), share)
    assertTrue(share.contains("1 -> obj.share(mat = matArg!!)"), share)

    assertCsharp(
      "public Task<string> ShareAsync($PLACEMAT? mat = null, int? grams = null, " +
          "CancellationToken cancellationToken = default)"
    )
    assertCsharp("mat?._handle ?? NugetKotlinHandle.Null, grams.HasValue,")

    val snooze: String = exportEndingWith("__bell_snooze_collect")
    assertTrue(snooze.contains("mat: COpaquePointer?,"), snooze)
    assertTrue(snooze.contains("1 -> obj.snooze(mat = matArg!!)"), snooze)
    assertCsharp("public KotlinStateFlow<string> Snooze($PLACEMAT? mat = null)")
  }

  @Test
  fun `an already-nullable defaulted handle parameter is optional behind an IsSet slot`() {
    val settle: String = exportEndingWith("__bell_settle_async")
    assertTrue(settle.contains("matIsSet: Boolean,\n  mat: COpaquePointer?,"), settle)
    assertTrue(settle.contains("val mask = (if (matIsSet) 1 else 0)"), settle)
    assertTrue(settle.contains("0 -> obj.settle(cat)"), settle)
    assertTrue(settle.contains("1 -> obj.settle(cat, mat = matArg)"), settle)

    assertCsharp(
      "public Task<string> SettleAsync(string cat, " +
          "global::Kotlin.Native.Interop.KotlinOptional<$PLACEMAT?> mat = default, " +
          "CancellationToken cancellationToken = default)"
    )
    assertCsharp(
      "${nonNullStringOrThrow("cat")}, mat.HasValue, " +
        "mat.Value?._handle ?? NugetKotlinHandle.Null,",
    )
    // The handle slot after the `IsSet` slot is spelled exactly as a required handle's is.
    assertCsharp(
      "[MarshalAs(UnmanagedType.I1)] bool matIsSet, NugetKotlinHandle mat, IntPtr callback"
    )

    val lounge: String = exportEndingWith("__bell_lounge_collect")
    assertTrue(lounge.contains("matIsSet: Boolean,\n  mat: COpaquePointer?,"), lounge)
    assertTrue(lounge.contains("1 -> obj.lounge(cat, mat = matArg)"), lounge)
    assertCsharp(
      "public KotlinFlow<string> Lounge(string cat, " +
          "global::Kotlin.Native.Interop.KotlinOptional<$PLACEMAT?> mat = default)"
    )

    val tuck: String = exportEndingWith("__tuck_async")
    assertTrue(tuck.contains("matIsSet: Boolean,\n  mat: COpaquePointer?,"), tuck)
    assertTrue(tuck.contains("1 -> tier1.legacydefaults.tuck(cat, mat = matArg)"), tuck)
    assertCsharp(
      "public static Task<string> TuckAsync(string cat, " +
          "global::Kotlin.Native.Interop.KotlinOptional<$PLACEMAT?> mat = default, "
    )
  }

  @Test
  fun `a suspend prefix-overload pair keeps the defaulted parameter required so the call is not CS0121`() {
    assertCsharp("public Task<int> CountAsync(CancellationToken cancellationToken = default)")
    assertCsharp("public Task<int> CountAsync(int? limit, CancellationToken cancellationToken = default)")
  }

  /**
   * ADR-074: an `actual suspend fun` may not restate the default its `expect` declares, so its own
   * `hasDefault` is false. The flags ride the catalog's expect-index read to both legacy halves.
   */
  @Test
  fun `an actual suspend function widens the default its expect declares`() {
    val expect = Tier1Harness.run(
      commonSources = mapOf(
        "Nap.kt" to """
        package tier1.legacydefaultsexpect

        expect suspend fun nap(name: String, minutes: Int = 5): String
        """.trimIndent(),
      ),
      sources = mapOf(
        "NapActual.kt" to """
        package tier1.legacydefaultsexpect

        actual suspend fun nap(name: String, minutes: Int): String = name + minutes
        """.trimIndent(),
      ),
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )

    // The harness compiles common and platform sources as one module, so an expect/actual pair
    // never compiles cleanly here (the plan route's cell has the same shape); KSP still runs.
    assertEquals("OK", expect.kspExitCode, "kspErrors=${expect.kspErrors}")
    assertTrue(expect.generated.contains("0 -> tier1.legacydefaultsexpect.nap(name)"), expect.generated)
    assertTrue(
      expect.generatedCSharp.contains(
        "public static Task<string> NapAsync(string name, int? minutes = null, " +
            "CancellationToken cancellationToken = default)"
      ),
      expect.generatedCSharp,
    )
  }

  /** ADR-164 rule 6 on the legacy routes: the last 8 widen, the rest stay required, named. */
  @Test
  fun `a legacy member past the cap widens its last eight and warns about the rest`() {
    val capped = Tier1Harness.run(
      """
      package tier1.legacydefaultscap

      suspend fun many(a: Int = 1, b: Int = 2, c: Int = 3, d: Int = 4, e: Int = 5, f: Int = 6,
        g: Int = 7, h: Int = 8, i: Int = 9): Int = a + b + c + d + e + f + g + h + i
      """.trimIndent(),
      fileName = "Cap.kt",
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )

    assertTrue(capped.compiledClean, "generated Kotlin must compile; got: ${capped.compileErrors}")
    assertTrue(
      capped.generatedCSharp.contains("ManyAsync(int a, int? b = null, int? c = null"),
      "expected `a` capped required and `b` onwards widened",
    )
    val warning: String? = capped.kspWarnings.firstOrNull {
      it.contains("WARNING_DEFAULT_PARAMETER_CAP_EXCEEDED") && it.contains("many")
    }
    assertTrue(warning != null && warning.contains("`a`"), "kspWarnings=${capped.kspWarnings}")
  }

  private fun exportEndingWith(suffix: String): String {
    val start: Int = result.generated.indexOf("fun export_library_legacydefaults$suffix(")
    assertTrue(start >= 0, "no export ending with $suffix in:\n${result.generated}")
    val end: Int = result.generated.indexOf("\n@CName", start).takeIf { it >= 0 } ?: result.generated.length
    return result.generated.substring(start, end)
  }

  private companion object {
    const val PLACEMAT: String = "global::Interop.Legacydefaults.Placemat"
  }

  private fun assertCsharp(expected: String) {
    assertTrue(result.generatedCSharp.contains(expected), "missing from Interop.cs: $expected")
  }
}
