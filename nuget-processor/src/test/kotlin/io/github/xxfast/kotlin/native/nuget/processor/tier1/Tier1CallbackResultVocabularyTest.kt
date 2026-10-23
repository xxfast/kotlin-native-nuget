package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ADR-239: a callback RESULT binds over the vocabulary a method return already crosses: an enum,
 * `Char`, `Instant`, `Duration`, `Uuid`, a `List`/`Set`/`Map`, and a nullable of each, on a per-call
 * lambda parameter and on a stored add/remove pair.
 *
 * The hand-written route's refusal in `ClassExports.refusedLegacyLambdaShape` named every such
 * member with "a callback can return `Unit`, a primitive or a `String`". That sentence was already
 * false before this ADR: ADR-231 binds a class-handle result on the plan (`recruitNow`, Treadmill's
 * `RecruitNow(string, Func<string, Baton>)` is generated), yet the same member was still named
 * skipped in a `<remarks>` paragraph, so one member was both bound and named (ADR-064 says one or
 * the other). Each cell below must bind and be named nowhere, and the sentence must be gone.
 */
class Tier1CallbackResultVocabularyTest {

  private val source: String = """
    package tier1.callbackresult

    import kotlin.time.Duration
    import kotlin.time.Instant
    import kotlin.uuid.Uuid

    class Baton(val holder: String)

    enum class Spirit { CALM, HYPED }

    class Desk(val judge: String) {
      fun recruitNow(make: (String) -> Baton): String = make(judge).holder

      fun spirit(pick: (String) -> Spirit): String = pick(judge).name
      fun maybeSpirit(pick: (String) -> Spirit?): String = pick(judge)?.name ?: "none"
      fun initial(pick: (String) -> Char): String = pick(judge).toString()
      fun maybeInitial(pick: (String) -> Char?): String = pick(judge)?.toString() ?: "none"
      fun stamp(pick: (String) -> Instant): String = pick(judge).toString()
      fun maybeStamp(pick: (String) -> Instant?): String = pick(judge)?.toString() ?: "none"
      fun timing(pick: (String) -> Duration): String = pick(judge).toString()
      fun maybeTiming(pick: (String) -> Duration?): String = pick(judge)?.toString() ?: "none"
      fun ticket(pick: (String) -> Uuid): String = pick(judge).toString()
      fun maybeTicket(pick: (String) -> Uuid?): String = pick(judge)?.toString() ?: "none"
      fun lineup(pick: (String) -> List<String>): String = pick(judge).joinToString()
      fun maybeLineup(pick: (String) -> List<String>?): String = pick(judge)?.joinToString() ?: "none"
      fun numbers(pick: (String) -> Set<Int>): String = pick(judge).joinToString()
      fun maybeNumbers(pick: (String) -> Set<Int>?): String = pick(judge)?.joinToString() ?: "none"
      fun tally(pick: (String) -> Map<String, Int>): String = pick(judge).toString()
      fun maybeTally(pick: (String) -> Map<String, Int>?): String = pick(judge)?.toString() ?: "none"

      private val judges: MutableList<(String) -> Spirit> = mutableListOf()
      fun addJudge(judge: (String) -> Spirit) { judges.add(judge) }
      fun removeJudge(judge: (String) -> Spirit) { judges.remove(judge) }
    }
  """.trimIndent()

  /** Kotlin member to its C# name. */
  private val cells: Map<String, String> = listOf(
    "recruitNow", "spirit", "maybeSpirit", "initial", "maybeInitial", "stamp", "maybeStamp",
    "timing", "maybeTiming", "ticket", "maybeTicket", "lineup", "maybeLineup", "numbers",
    "maybeNumbers", "tally", "maybeTally", "addJudge",
  ).associateWith { member -> member.replaceFirstChar { it.uppercase() } }

  private fun run(): Tier1Result {
    val result: Tier1Result = Tier1Harness.run(source)
    assertTrue(result.kspErrors.isEmpty(), "kspErrors=${result.kspErrors}")
    assertTrue(result.compiledClean, "compileErrors=${result.compileErrors}")
    return result
  }

  /** A C# method declaration, never a `///` remark that names a skipped member. */
  private fun declares(csharp: String, member: String): Boolean =
    Regex("""\bpublic [^=;(]+ $member\(""").containsMatchIn(csharp.withoutDocComments())

  private fun warningsFor(result: Tier1Result, member: String): List<String> =
    result.kspWarnings.filter { warning ->
      warning.contains("Skipping tier1.callbackresult.Desk.$member:") ||
          warning.contains("Skipping Desk.$member:")
    }

  @Test
  fun `every callback result in the vocabulary binds and is named nowhere`() {
    val result: Tier1Result = run()
    val wrong: List<String> = cells.mapNotNull { (kotlin, csharp) ->
      val emitted: Boolean = declares(result.generatedCSharp, csharp)
      val warnings: List<String> = warningsFor(result, kotlin)
      if (emitted && warnings.isEmpty()) null
      else "$kotlin: wanted emitted=true warnings=0, got emitted=$emitted warnings=$warnings"
    }
    assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
  }

  @Test
  fun `the stale callback result sentence is gone from warnings and remarks`() {
    val result: Tier1Result = run()
    val stale = "a callback can return `Unit`, a primitive or a `String`"
    assertEquals(
      emptyList(),
      result.kspWarnings.filter { warning -> warning.contains(stale) },
      result.kspWarnings.joinToString("\n"),
    )
    assertTrue(
      !result.generatedCSharp.contains("a callback can return `Unit`, a primitive or a `String`"),
      "a generated remark still carries the stale sentence",
    )
  }

  /** The class-handle cell on its own: bound by ADR-231 and, until now, also named skipped. */
  @Test
  fun `a class handle callback result is not both bound and named`() {
    val result: Tier1Result = run()
    assertTrue(declares(result.generatedCSharp, "RecruitNow"), result.generatedCSharp)
    assertEquals(emptyList(), warningsFor(result, "recruitNow"), result.kspWarnings.joinToString("\n"))
  }
}
