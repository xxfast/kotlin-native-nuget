package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ADR-115 / issue #121 on an ordinary class's hand-written callback METHOD routes: the
 * stored-callback pair (ADR-037), the interface-bridge pair (ADR-039) and the per-call lambda
 * route. #121 gated the property routes only; these three had no marker gate at all, so a marked
 * pair shipped as public C# API with no warning, and a marked per-call member was reported
 * `SKIPPED_OPT_IN_MARKER` and exported anyway.
 *
 * At `RequiresOptIn.Level.ERROR` the same leak was a generated-Kotlin compile failure instead
 * (the export calls the marked member without opting in).
 *
 * A pair is named on both halves by the legacy-route walk; the per-call member is named once, by
 * the planner. Shared helpers live in [Tier1OptInCallbackPairTest]'s file.
 */
class Tier1OptInClassCallbackRouteTest {

  private val warningSource: String = """
    package tier1.optin.yard

    @RequiresOptIn(level = RequiresOptIn.Level.WARNING, message = "internal")
    @Retention(AnnotationRetention.BINARY)
    @Target(AnnotationTarget.FUNCTION)
    annotation class Internal

    interface Watcher {
      fun onMeow(m: String)
    }

    class Yard {
      @Internal fun addBell(l: (Int) -> Unit) {}
      fun removeBell(l: (Int) -> Unit) {}
      @Internal fun addGuard(w: Watcher) {}
      fun removeGuard(w: Watcher) {}
      @Internal fun yardEach(cb: (Char) -> Unit) = cb('a')
      fun addHorn(l: (Int) -> Unit) {}
      fun removeHorn(l: (Int) -> Unit) {}
      fun addPatrol(w: Watcher) {}
      fun removePatrol(w: Watcher) {}
      fun yardWalk(cb: (Char) -> Unit) = cb('b')
      fun addGong(l: (Int) -> Unit) {}
      @Internal fun removeGong(l: (Int) -> Unit) {}
    }
  """.trimIndent()

  private val errorSource: String = """
    package tier1.optin.lodge

    @RequiresOptIn(level = RequiresOptIn.Level.ERROR, message = "internal")
    @Retention(AnnotationRetention.BINARY)
    @Target(AnnotationTarget.FUNCTION)
    annotation class Sealed

    interface Watcher {
      fun onMeow(m: String)
    }

    class Lodge {
      @Sealed fun addBark(l: (Int) -> Unit) {}
      fun removeBark(l: (Int) -> Unit) {}
      @Sealed fun addPost(w: Watcher) {}
      fun removePost(w: Watcher) {}
      @Sealed fun lodgeEach(cb: (Char) -> Unit) = cb('a')
      fun addTail(l: (Int) -> Unit) {}
      fun removeTail(l: (Int) -> Unit) {}
    }
  """.trimIndent()

  @Test
  fun `a marked pair on an ordinary class is not exported and is named on both halves`() {
    val result: Tier1Result = Tier1Harness.run(warningSource)
    result.assertOptInPairRefused("Yard", "addBell", "removeBell")
    result.assertOptInPairRefused("Yard", "addGuard", "removeGuard")
    // A marked REMOVE half takes its unmarked add half with it too.
    result.assertOptInPairRefused("Yard", "addGong", "removeGong")
    assertTrue(
      result.optInWarnings("Yard.addGong").single().contains("`removeGong` is marked"),
      "the warning must name the marked half",
    )
    assertTrue(result.declaresCSharpMethod("AddHorn"), "the unmarked stored pair must still bind")
    assertTrue(
      result.declaresCSharpMethod("AddPatrol"),
      "the unmarked interface pair must still bind",
    )
    assertTrue(result.compiledClean, "generated Kotlin must compile: ${result.compileErrors}")
  }

  @Test
  fun `a marked per-call lambda member on an ordinary class is not exported and named once`() {
    val result: Tier1Result = Tier1Harness.run(warningSource)
    assertTrue(!result.exportsKotlinMember("yardEach"), "yardEach must not be exported")
    assertTrue(!result.declaresCSharpMethod("YardEach"), "YardEach must not be declared in C#")
    assertEquals(1, result.optInWarnings("Yard.yardEach").size, "${result.kspWarnings}")
    assertTrue(
      result.declaresCSharpMethod("YardWalk"),
      "the unmarked per-call member must still bind",
    )
  }

  @Test
  fun `an ERROR-level marked member is skipped, named, and the generated Kotlin compiles`() {
    val result: Tier1Result = Tier1Harness.run(errorSource)
    assertTrue(result.compiledClean, "generated Kotlin must compile: ${result.compileErrors}")
    result.assertOptInPairRefused("Lodge", "addBark", "removeBark")
    result.assertOptInPairRefused("Lodge", "addPost", "removePost")
    assertTrue(!result.exportsKotlinMember("lodgeEach"), "lodgeEach must not be exported")
    assertTrue(!result.declaresCSharpMethod("LodgeEach"), "LodgeEach must not be declared in C#")
    assertEquals(1, result.optInWarnings("Lodge.lodgeEach").size, "${result.kspWarnings}")
    assertTrue(result.declaresCSharpMethod("AddTail"), "the unmarked pair must still bind")
  }
}
