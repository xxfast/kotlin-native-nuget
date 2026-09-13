package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-116's 2026-09-13 amendment, the sibling of [Tier1SealedArmLambdaTest]: an arm's
 * `override fun` of a base member the planner **structurally declined** owes its own ADR-096
 * omitting overloads.
 *
 * Two independent root causes hold the overload back today, and this fixture is shaped so that
 * fixing only one keeps it red:
 * 1. `sealedSubclassEntries` returns early whenever `method.findOverridee()?.parentDeclaration` is
 *    the sealed base. That gate keys on the Kotlin declaration graph, not on whether
 *    `sealedBaseEntries` actually produced a `Planned` entry — and here it did not, because
 *    `tag` is behind a `@RequiresOptIn` marker and ADR-115 drops it.
 * 2. The same pass counts trailing defaults with raw `hasDefault` off the **override's** own
 *    parameters, which Kotlin forbids from restating a default, so every flag reads `false`.
 *    `classEntries` already uses `memberDefaultFlags(...)` for exactly this reason.
 *
 * The marker is `WARNING` level on purpose: ADR-115 is level-independent, so the base is skipped
 * either way, and a `WARNING` marker cannot turn an opt-in propagation inside the generated
 * `CNameExports.kt` into a compile error that would mask the missing overload. `compiledClean` is
 * therefore still a meaningful assertion here.
 *
 * `Idle` is the control: it inherits the declined member without overriding it, so it must gain
 * neither arity. Oreo tags his hallway sprints; Mylo, loafed on the mat, tags nothing.
 */
class Tier1SealedArmOmittingOverloadDeclinedBaseTest {

  private val fixture: String = """
    package tier1.armdeclinedbase

    @RequiresOptIn(level = RequiresOptIn.Level.WARNING, message = "still settling")
    @Target(AnnotationTarget.CLASS, AnnotationTarget.PROPERTY, AnnotationTarget.FUNCTION)
    annotation class Unstable

    sealed class Job {
      // Declined by ADR-115 (marker), and the only carrier of the default. The base can therefore
      // bind neither `Tag(string, string)` nor the omitting `Tag(string)`.
      @Unstable
      open fun tag(prefix: String, suffix: String = "!"): String = prefix + suffix

      data class Running(val progress: Int) : Job() {
        // Consumes the marker rather than propagating it, so the arm's member is bindable while
        // the base's is not. `suffix` cannot restate the default; the overridee carries it.
        @OptIn(Unstable::class)
        override fun tag(prefix: String, suffix: String): String =
          "${'$'}prefix${'$'}progress${'$'}suffix"
      }

      // The control arm: inherits the declined member, overrides nothing, gains nothing.
      data object Idle : Job()
    }

    class JobFactory {
      fun running(progress: Int): Job.Running = Job.Running(progress)
    }
  """.trimIndent()

  private fun run(): Tier1Result = Tier1Harness.run(fixture, fileName = "JobSample.kt")

  /**
   * The Kotlin half. The arm exports both arities off its own prefix, the numbered symbol is the
   * synthesized short one, and nothing at all is exported for the declined base member.
   */
  @Test
  fun `an arm's override of a declined base member exports both arities under the arm's prefix`() {
    val result = run()

    assertTrue(
      result.compiledClean,
      "expected the arm's exports to compile; got: ${result.compileErrors} ${result.kspErrors}",
    )

    val kotlin: String = result.generated
    assertContains(kotlin, "@CName(\"job_running_tag\")")
    assertContains(kotlin, "@CName(\"job_running_tag_2\")")
    // The synthesized arity calls Kotlin with one positional argument, letting the overridee's
    // default supply the rest. A truncation that passed both would read as the wrong value.
    assertContains(kotlin, "tag(prefix)")

    val leaked: List<String> = listOf("job_tag", "job_tag_2", "job_idle_tag")
      .filter { entryPoint -> kotlin.contains("@CName(\"$entryPoint\")") }

    assertTrue(
      leaked.isEmpty(),
      "a member behind an opt-in marker must not export on the base or on a non-overriding arm; " +
          "got: $leaked",
    )
  }

  /**
   * The C# half, and the one that pins the issue's symptom: `Tag(string prefix)` has to be declared
   * **inside the arm's own class body**, because a base-typed call cannot exist at all here.
   */
  @Test
  fun `the arm declares both Tag arities and the declining base declares none`() {
    val result = run()

    val running: String = armBody(result.generatedCSharp, "Running")

    assertTrue(
      running.contains("public string Tag(string prefix)"),
      "expected the synthesized omitting overload on the arm; got: ${linesFor(result, "Tag(")}",
    )
    assertTrue(
      running.contains("public string Tag(string prefix, string suffix)"),
      "expected the declared arity on the arm; got: ${linesFor(result, "Tag(")}",
    )
    assertTrue(
      running.contains("EntryPoint = \"job_running_tag\""),
      "expected the arm-prefixed extern; got: ${linesFor(result, "job_running_tag")}",
    )

    assertFalse(
      baseBody(result.generatedCSharp).contains("Tag("),
      "the declining base must declare no Tag: ${baseBody(result.generatedCSharp)}",
    )
    assertFalse(
      armBody(result.generatedCSharp, "Idle").contains("Tag("),
      "a non-overriding arm must declare no Tag: ${linesFor(result, "Tag(")}",
    )
  }

  /** The generated C# between one arm's class header and the next arm's, base members excluded. */
  private fun armBody(csharp: String, arm: String): String {
    val header: String = "public sealed class $arm"
    val start: Int = csharp.indexOf(header)
    check(start >= 0) { "no `$header` in the generated C#" }
    val next: Int = csharp.indexOf("public sealed class ", start + header.length)
    return if (next < 0) csharp.substring(start) else csharp.substring(start, next)
  }

  /** The generated C# for the sealed base itself, up to the first arm's class header. */
  private fun baseBody(csharp: String): String {
    val start: Int = csharp.indexOf("class Job")
    check(start >= 0) { "no `class Job` in the generated C#" }
    val next: Int = csharp.indexOf("public sealed class ", start)
    return if (next < 0) csharp.substring(start) else csharp.substring(start, next)
  }

  private fun linesFor(result: Tier1Result, needle: String): List<String> =
    result.generatedCSharp.lines().filter { line -> line.contains(needle) }
}
