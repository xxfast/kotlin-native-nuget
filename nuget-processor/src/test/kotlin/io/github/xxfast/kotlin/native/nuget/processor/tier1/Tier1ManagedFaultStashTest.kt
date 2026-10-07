package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

/**
 * ADR-161 part B residual: the `[ThreadStatic] _lastManagedFault` stash a throwing callback thunk
 * fills must not outlive the crossing that set it. The error arm consumes it as before; the
 * success arm of every synchronous crossing now clears it through the one
 * `NugetErrorNative.ClearManagedFault()` helper, so a callback throw that Kotlin caught does not
 * keep its exception rooted on the thread.
 *
 * The cells assert the C# text (ADR-060 tier 1): the defect is a MISSING statement after each
 * error arm, which no signature check can see. The structural cell walks every error arm the
 * fixture's routes emit, so a route whose template forgets the clear fails here.
 *
 * Oreo throws the remote. Mylo makes sure nobody keeps holding it.
 */
class Tier1ManagedFaultStashTest {

  private val fixture: String = """
    package tier1.stash

    enum class Mood { HAPPY, GRUMPY }

    data class Toy(val name: String, val squeaks: Int)

    value class Treats(val count: Int)

    sealed class Visit {
      data class Vet(val reason: String) : Visit()
      data object Groomer : Visit()
    }

    interface Greeter {
      fun greet(name: String): String
    }

    object Registry {
      var count: Int = 0
      fun register(name: String): Int = ++count
    }

    class Cat(val name: String) {
      var mood: Mood = Mood.HAPPY
      var nickname: String? = null
      var age: Int? = null
      val toys: List<String> = listOf("ball")
      val scores: Map<String, Int> = mapOf("Oreo" to 1)
      fun describe(): String = name
      fun describeWith(format: (String) -> String): String = format(name)
      fun greetVia(greeter: Greeter): String = greeter.greet(name)
      fun weigh(): Treats = Treats(3)
      fun favourite(): Toy = Toy("ball", 2)
      fun lastVisit(): Visit = Visit.Groomer
      fun tryFeed(): Result<Int> = Result.success(1)
      fun pet() {}

      companion object {
        fun adopt(name: String): Cat = Cat(name)
      }
    }

    fun loudest(names: List<String>): String = names.first()
    fun counts(): Map<String, Int> = mapOf("Mylo" to 2)
    fun tags(): Set<String> = setOf("tabby")
    fun ping(): Int = 1
    fun nap() {}
    fun Cat.purr(): String = name
    fun Cat.friends(): List<String> = listOf(name)
    fun Cat.middleName(): String? = null
    fun Cat.lives(): Int? = 9
  """.trimIndent()

  private val cs: String by lazy {
    Tier1Harness.run(
      fixture,
      fileName = "Cat.kt",
      processorOptions = mapOf("nuget.rootPackage" to "tier1"),
    ).generatedCSharp
  }

  @Test
  fun `the error helper declares the one clear and Check calls it on success`() {
    assertContains(cs, "internal static void ClearManagedFault() => _lastManagedFault = null;")
    val check: String = cs.substringAfter("internal static T Check<T>(").substringBefore("\n        }")
    val thrown: Int = check.indexOf("if (error != IntPtr.Zero) throw BuildException(error, caller);")
    val cleared: Int = check.indexOf("ClearManagedFault();")
    val returned: Int = check.indexOf("return result;")
    assertTrue(thrown in 0 until cleared, "clear must follow the error arm; check=$check")
    assertTrue(cleared < returned, "clear must precede the return; check=$check")
  }

  @Test
  fun `the error arm still consumes the stash exactly as before`() {
    val take: String = cs.substringAfter("private static Exception? TakeOriginalManagedFault(")
      .substringBefore("\n        }")
    assertContains(take, "if (expected != message) return null;")
    assertContains(take, "_lastManagedFault = null;")
    assertContains(cs, "Exception? original = TakeOriginalManagedFault(kotlinType, msg);")
  }

  @Test
  fun `every synchronous error arm is followed by the success-path clear`() {
    val lines: List<String> = cs.lines()
    val arms: List<Int> = lines.indices.filter { index ->
      Regex("""^\s*if \(error\d* != IntPtr\.Zero\)""").containsMatchIn(lines[index])
    }
    assertTrue(arms.size > 10, "the fixture should exercise many routes; found ${arms.size}")

    val missing: List<String> = arms.mapNotNull { index ->
      val arm: String = lines[index]
      val indent: Int = arm.indexOfFirst { char -> !char.isWhitespace() }
      val last: Int = if (lines[index + 1].trim() == "{") {
        (index + 2 until lines.size).first { candidate ->
          lines[candidate].trim() == "}" &&
            lines[candidate].indexOfFirst { char -> !char.isWhitespace() } == indent
        }
      } else {
        index
      }
      val next: String = lines[last + 1].trim()
      if (next.endsWith("ClearManagedFault();")) null
      else "line ${index + 1}: $arm -> $next"
    }
    assertTrue(missing.isEmpty(), "error arms without the clear:\n${missing.joinToString("\n")}")
  }

  @Test
  fun `the clearing helper compiles against the shared contract`() {
    Tier1CSharpCompile.assertCompiles(
      Tier1Harness.run(
        fixture,
        fileName = "Cat.kt",
        processorOptions = mapOf("nuget.rootPackage" to "tier1"),
      ),
      allowUnsafe = true,
      consumerSource = """
      namespace Consumer
      {
          public static class Probe
          {
              public static void Touch() { }
          }
      }
      """.trimIndent(),
    )
  }
}
