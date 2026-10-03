package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-006 amendment: an enum member function binds on the ADR-062 forward plan under
 * `ForwardCallableOrigin.ENUM_MEMBER` (Kotlin `Mood.entries[receiver].f()`, C# `F(this Mood mood)`
 * in `MoodExtensions`), a companion function under the COMPANION origin as a plain static of
 * `MoodExtensions`, and a companion `val`/`var` as a static property there. What has no route on an
 * enum (suspend, generic, Flow return, lambda return, `const val`) is named once, never silent.
 */
class Tier1EnumMemberFunctionPlanTest {

  private val source: String =
    """
    package tier1.enumfn

    import kotlinx.coroutines.flow.Flow
    import kotlinx.coroutines.flow.flowOf

    enum class Mood {
      HAPPY, GRUMPY;

      fun isLoudNow(): Boolean = this == GRUMPY
      fun sameAs(mood: Mood): Boolean = this == mood
      fun greet(name: String): String = "hi ${'$'}name"
      fun greet(name: String, times: Int): String = "hi ${'$'}name x${'$'}times"

      suspend fun napAsync(): Int = 1
      fun <T> tagged(value: T): T = value
      fun ticks(): Flow<Int> = flowOf(1)
      fun sound(): () -> String = { "purr" }

      companion object {
        fun fallback(): Mood = HAPPY
        val houseFavourite: Mood = GRUMPY
        var lastSeen: Mood = HAPPY
        const val LIVES: Int = 9
        suspend fun wake(): Mood = HAPPY
      }
    }

    enum class Chatter {
      CHIRP { override fun sound(times: Int): String = "chirp" },
      TRILL { override fun sound(times: Int): String = "trill" };

      abstract fun sound(times: Int): String
    }
    """.trimIndent()

  private val result: Tier1Result by lazy {
    Tier1Harness.run(source, libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore))
  }

  private val prefix: String = "library_tier1_enumfn__"

  private fun warningsFor(symbol: String): List<String> =
    result.kspWarnings.filter { it.contains("tier1.enumfn.$symbol") }

  @Test
  fun `generates and compiles`() {
    assertEquals("OK", result.kspExitCode, "kspErrors=${result.kspErrors}")
    assertTrue(result.kspErrors.isEmpty(), "got: ${result.kspErrors}")
    assertTrue(result.compiledClean, "got: ${result.compileErrors}")
  }

  @Test
  fun `a member function is called on the entry and carries the error slot`() {
    val kotlin: String = result.generated
    assertContains(kotlin, "@CName(\"${prefix}mood_isLoudNow\")")
    assertContains(kotlin, "Mood.entries[receiver].isLoudNow()")
    assertContains(kotlin, "@CName(\"${prefix}mood_greet_2\")")
    val cs: String = result.generatedCSharp
    assertContains(cs, "EntryPoint = \"${prefix}mood_isLoudNow\"")
    assertContains(cs, "public static bool IsLoudNow(this global::Interop.Mood mood)")
    assertContains(cs, "public static string Greet(this global::Interop.Mood mood, string name)")
    assertContains(
      cs, "public static string Greet(this global::Interop.Mood mood, string name, int times)",
    )
    val lines: List<String> = cs.lines().map { it.trim() }
    val extern: Int = lines.indexOfFirst { line ->
      line.startsWith("private static extern bool Native_IsLoudNow(") &&
        line.contains("out IntPtr error")
    }
    assertTrue(extern > 0, "no IsLoudNow extern with an error slot in:\n$cs")
    assertEquals("[return: MarshalAs(UnmanagedType.I1)]", lines[extern - 1])
  }

  // A declared parameter already spelled like the enum keeps the `mood` name; the receiver falls
  // back to the plan-owned `receiver` rather than rendering a duplicate C# parameter (CS0100).
  @Test
  fun `a declared parameter named after the enum keeps its name`() {
    assertContains(
      result.generatedCSharp,
      "public static bool SameAs(this global::Interop.Mood receiver, global::Interop.Mood mood)",
    )
  }

  @Test
  fun `an abstract member dispatches through the entry`() {
    assertContains(result.generated, "Chatter.entries[receiver].sound(")
    assertContains(
      result.generatedCSharp,
      "public static string Sound(this global::Interop.Chatter chatter, int times)",
    )
    assertContains(result.generatedCSharp, "public static partial class ChatterExtensions")
  }

  @Test
  fun `companion functions and properties are statics on the extensions class`() {
    val kotlin: String = result.generated
    assertContains(kotlin, "@CName(\"${prefix}mood_companion_fallback\")")
    assertContains(kotlin, "tier1.enumfn.Mood.fallback()")
    val cs: String = result.generatedCSharp
    assertContains(cs, "public static global::Interop.Mood Fallback()")
    assertContains(cs, "Native_Companion_Fallback")
    assertContains(cs, "public static global::Interop.Mood HouseFavourite")
    assertContains(cs, "public static global::Interop.Mood LastSeen")
    assertTrue(warningsFor("Mood.Companion.houseFavourite").isEmpty(), "${result.kspWarnings}")
    assertTrue(warningsFor("Mood.Companion.lastSeen").isEmpty(), "${result.kspWarnings}")
  }

  @Test
  fun `a const val in an enum companion stays a named skip`() {
    val skips: List<String> = warningsFor("Mood.Companion.LIVES")
    assertEquals(1, skips.size, "kspWarnings=${result.kspWarnings}")
    assertContains(skips.single(), ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_PROPERTY.name)
    assertFalse(result.generatedCSharp.contains("Lives"))
  }

  @Test
  fun `every enum member with no route is named exactly once`() {
    listOf("Mood.napAsync", "Mood.tagged", "Mood.ticks", "Mood.sound", "Mood.Companion.wake")
      .forEach { symbol ->
        val skips: List<String> = warningsFor(symbol)
        assertEquals(1, skips.size, "$symbol kspWarnings=${result.kspWarnings}")
      }
    assertContains(
      warningsFor("Mood.napAsync").single(),
      "a companion object, a value class, an enum,",
    )
    val cs: String = result.generatedCSharp
    listOf("NapAsync", "Tagged", "Ticks", "Wake")
      .forEach { name -> assertFalse(cs.contains(" $name("), "$name must not render") }
  }

  @Test
  fun `no member function is left unnamed or double-reported`() {
    // The retired `SKIPPED_ENUM_MEMBER_FUNCTION` producer is gone; nothing bindable warns.
    listOf("Mood.isLoudNow", "Mood.greet", "Mood.Companion.fallback").forEach { symbol ->
      assertTrue(warningsFor(symbol).isEmpty(), "$symbol kspWarnings=${result.kspWarnings}")
    }
    assertTrue(warningsFor("Chatter").isEmpty(), "${result.kspWarnings}")
  }

  private val collisionSource: String =
    """
    package tier1.enumfncollision

    enum class Mood {
      HAPPY, GRUMPY;

      val description: String get() = name
      fun description(): String = name
    }
    """.trimIndent()

  private val collisionResult: Tier1Result by lazy { Tier1Harness.run(collisionSource) }

  @Test
  fun `a member function beside a same-named member property is a named collision`() {
    val errors: List<String> = collisionResult.kspErrors
      .filter { it.contains(ForwardDiagnosticKind.ERROR_CSHARP_SIGNATURE_COLLISION.name) }
    assertEquals(1, errors.size, "kspErrors=${collisionResult.kspErrors}")
    assertContains(errors.single(), "`val description`")
    assertContains(errors.single(), "`fun description`")
    assertContains(errors.single(), "MoodExtensions")
  }

  private val companionCollisionSource: String =
    """
    package tier1.enumfncompanioncollision

    enum class Mood {
      HAPPY, GRUMPY;

      fun describe(): String = name

      companion object {
        fun describe(mood: Mood): String = mood.name
      }
    }
    """.trimIndent()

  private val companionCollisionResult: Tier1Result by lazy {
    Tier1Harness.run(companionCollisionSource)
  }

  @Test
  fun `a member function beside a same-signature companion function is a named collision`() {
    val errors: List<String> = companionCollisionResult.kspErrors
      .filter { it.contains(ForwardDiagnosticKind.ERROR_CSHARP_SIGNATURE_COLLISION.name) }
    assertEquals(1, errors.size, "kspErrors=${companionCollisionResult.kspErrors}")
    assertContains(errors.single(), "companion `fun describe`")
  }

  private fun collisionErrors(source: String): Pair<Tier1Result, List<String>> {
    val run: Tier1Result = Tier1Harness.run(source)
    return run to run.kspErrors
      .filter { it.contains(ForwardDiagnosticKind.ERROR_CSHARP_SIGNATURE_COLLISION.name) }
  }

  // A companion `val fallback` renders the static property `Fallback` and a member `fun fallback()`
  // the method `Fallback(this Mood)`: one C# name, a property and a method, CS0102.
  @Test
  fun `a companion property beside a same-named member function is a named collision`() {
    val (run, errors) = collisionErrors(
      """
      package tier1.enumfnpropcollision

      enum class Mood {
        HAPPY, GRUMPY;

        fun fallback(): Mood = HAPPY

        companion object {
          val fallback: Mood = GRUMPY
        }
      }
      """.trimIndent()
    )
    assertEquals(1, errors.size, "kspErrors=${run.kspErrors}")
    assertContains(errors.single(), "companion `val fallback`")
    assertContains(errors.single(), "`fun fallback`")
    assertContains(errors.single(), "MoodExtensions")
  }

  // A companion `val` beside a member property of the same name: the static property `Nickname`
  // and the method `Nickname(this Mood)`.
  @Test
  fun `a companion property beside a same-named member property is a named collision`() {
    val (run, errors) = collisionErrors(
      """
      package tier1.enumproppropcollision

      enum class Mood {
        HAPPY, GRUMPY;

        val nickname: String get() = name

        companion object {
          val nickname: String = "cat"
        }
      }
      """.trimIndent()
    )
    assertEquals(1, errors.size, "kspErrors=${run.kspErrors}")
    assertContains(errors.single(), "companion `val nickname`")
    assertContains(errors.single(), "`val nickname`")
  }

  // A member property `val greet` (`Greet(this Mood)`) beside a companion `fun greet()` (`Greet()`)
  // is two methods with different parameter lists: legal C# overloads, so no error.
  @Test
  fun `a member property beside a same-named companion function is a legal overload`() {
    val (run, errors) = collisionErrors(
      """
      package tier1.enumpropfncompanion

      enum class Mood {
        HAPPY, GRUMPY;

        val greet: String get() = name

        companion object {
          fun greet(): String = "hi"
        }
      }
      """.trimIndent()
    )
    assertTrue(errors.isEmpty(), "kspErrors=${run.kspErrors}")
    assertEquals("OK", run.kspExitCode, "kspErrors=${run.kspErrors}")
    assertContains(
      run.generatedCSharp,
      "public static string Greet(this global::Interop.Mood mood)",
    )
    assertContains(run.generatedCSharp, "public static string Greet()")
  }
}
