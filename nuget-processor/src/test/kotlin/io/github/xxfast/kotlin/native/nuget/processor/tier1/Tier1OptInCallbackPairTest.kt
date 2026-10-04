package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ADR-115 / issue #121 on a sealed arm's callback PAIR routes: the stored-callback pair (ADR-037)
 * and the interface-bridge pair (ADR-039).
 *
 * The arm's pair selectors already refused an opt-in-marked half, taking its partner with it, but
 * nothing named either half: the planner suppresses its CALLBACK_PROTOCOL skip for pair members
 * before its own opt-in check runs, so `addRinger` / `removeRinger` vanished without a word. Both
 * halves are named now, as every other pair refusal is, because the unmarked `removeX` is gone too.
 *
 * The sealed base and `object` owners are pinned here too: neither has a pair route, and neither
 * leaks a marked member. [Tier1OptInClassCallbackRouteTest] owns the ordinary class.
 *
 * Biscuit is not for the public. Mylo is.
 */
class Tier1OptInCallbackPairTest {

  private val source: String = """
    package tier1.optin.pair

    @RequiresOptIn(level = RequiresOptIn.Level.WARNING, message = "internal")
    @Retention(AnnotationRetention.BINARY)
    @Target(AnnotationTarget.FUNCTION)
    annotation class Internal

    interface Watcher {
      fun onMeow(m: String)
    }

    sealed class Shelter {
      class Kennel : Shelter() {
        @Internal fun addRinger(l: (Int) -> Unit) {}
        fun removeRinger(l: (Int) -> Unit) {}
        @Internal fun addWatcher(w: Watcher) {}
        fun removeWatcher(w: Watcher) {}
        fun addChime(l: (Int) -> Unit) {}
        fun removeChime(l: (Int) -> Unit) {}
      }
      class Empty : Shelter()
    }

    sealed class Den {
      @Internal fun addHowl(l: (Int) -> Unit) {}
      fun removeHowl(l: (Int) -> Unit) {}
      @Internal fun addSentry(w: Watcher) {}
      fun removeSentry(w: Watcher) {}
      class Burrow : Den()
    }

    object Pound {
      @Internal fun addSiren(l: (Int) -> Unit) {}
      fun removeSiren(l: (Int) -> Unit) {}
      @Internal fun addScout(w: Watcher) {}
      fun removeScout(w: Watcher) {}
      fun addWhistle(l: (Int) -> Unit) {}
      fun removeWhistle(l: (Int) -> Unit) {}
    }
  """.trimIndent()

  private fun run(): Tier1Result = Tier1Harness.run(source)

  @Test
  fun `a marked pair on a sealed arm is named on both halves`() {
    val result: Tier1Result = run()
    result.assertOptInPairRefused("Shelter.Kennel", "addRinger", "removeRinger")
    result.assertOptInPairRefused("Shelter.Kennel", "addWatcher", "removeWatcher")
    assertTrue(result.declaresCSharpMethod("AddChime"), "the unmarked arm pair must still bind")
  }

  /**
   * A sealed base has no callback route at all (ADR-116), so every half is already refused and
   * named, marked or not. Pinned so a future base route cannot ship a marked pair.
   */
  @Test
  fun `a marked pair on a sealed base reaches neither artifact and is named`() {
    val result: Tier1Result = run()
    listOf("addHowl", "removeHowl", "addSentry", "removeSentry").forEach { member ->
      val pascal: String = member.replaceFirstChar { it.uppercase() }
      val named: Regex = Regex("""Skipping ([\w.]+\.)?Den\.$member:""")
      assertTrue(!result.exportsKotlinMember(member), "`Den.$member` must not be exported")
      assertTrue(
        !result.declaresCSharpMethod(pascal),
        "`Den.$member` must not be declared in C#",
      )
      assertTrue(
        result.kspWarnings.any { named.containsMatchIn(it) },
        "`Den.$member` must be named; got ${result.kspWarnings}",
      )
    }
  }

  /**
   * An object has no pair route: each member is planned on its own, so the planner's own opt-in
   * gate already refuses and names the marked half. The unmarked partner binds as the ordinary
   * member it would be without the marker, which is not an opt-in leak.
   */
  @Test
  fun `a marked pair half on an object reaches neither artifact and is named once`() {
    val result: Tier1Result = run()
    listOf("addSiren", "addScout").forEach { member ->
      val pascal: String = member.replaceFirstChar { it.uppercase() }
      assertTrue(!result.exportsKotlinMember(member), "`Pound.$member` must not be exported")
      assertTrue(
        !result.declaresCSharpMethod(pascal),
        "`Pound.$member` must not be declared in C#",
      )
      assertEquals(1, result.optInWarnings("Pound.$member").size, "${result.kspWarnings}")
    }
    // The control that makes the claim above true: an unmarked pair on an object also binds both
    // halves as ordinary members, so the surviving `RemoveSiren` is not the marker's doing.
    listOf("AddWhistle", "RemoveWhistle", "RemoveSiren", "RemoveScout").forEach { member ->
      assertTrue(result.declaresCSharpMethod(member), "`$member` binds as an ordinary member")
    }
  }
}

/** A C# method declaration, not a `<remarks>` mention (those name the Kotlin spelling). */
internal fun Tier1Result.declaresCSharpMethod(member: String): Boolean =
  Regex("""\s$member\(""").containsMatchIn(generatedCSharp)

internal fun Tier1Result.exportsKotlinMember(member: String): Boolean =
  Regex("""_$member\b""").containsMatchIn(generated)

/** The planner names a member by its qualified name, the legacy-route walk by its simple one. */
internal fun Tier1Result.optInWarnings(declaration: String): List<String> = kspWarnings
  .filter { it.contains("[nuget:${ForwardDiagnosticKind.SKIPPED_OPT_IN_MARKER.name}]") }
  .filter { Regex("""Skipping ([\w.]+\.)?${Regex.escape(declaration)}:""").containsMatchIn(it) }

/** Both halves absent from both artifacts and each named exactly once, naming the pair. */
internal fun Tier1Result.assertOptInPairRefused(owner: String, add: String, remove: String) {
  listOf(add, remove).forEach { member ->
    val pascal: String = member.replaceFirstChar { it.uppercase() }
    assertTrue(!exportsKotlinMember(member), "`$owner.$member` must not be exported:\n$generated")
    assertTrue(
      !declaresCSharpMethod(pascal),
      "`$owner.$member` must not be declared in C#:\n$generatedCSharp",
    )
    val named: List<String> = optInWarnings("$owner.$member")
    assertEquals(
      1,
      named.size,
      "`$owner.$member` must be named exactly once as SKIPPED_OPT_IN_MARKER; got $kspWarnings",
    )
    assertTrue(
      "`$add` / `$remove`" in named.single(),
      "the warning must name the pair; got ${named.single()}",
    )
  }
}
