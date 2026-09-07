package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-110: a top-level function renders PascalCase in C#, like every other forward position, while
 * its native `@CName` export keeps the Kotlin spelling. The rename introduces two C# name
 * collisions camelCase used to hide, so the skip for each is pinned here alongside the happy path.
 */
class Tier1TopLevelPascalCaseTest {

  @Test
  fun `top-level function renders PascalCase in C# and keeps its native export name`() {
    val result = Tier1Harness.run(
      """
      package tier1.pascal

      fun add(a: Int, b: Int): Int = a + b

      fun lock(): String = "locked"
      """.trimIndent(),
      fileName = "Arithmetic.kt",
    )

    assertTrue(result.compiledClean, "got: ${result.compileErrors}")

    val kotlin: String = result.generated
    assertContains(kotlin, "@CName(\"add\")")
    assertContains(kotlin, "@CName(\"lock\")")

    val cs: String = result.generatedCSharp
    assertContains(cs, "public static partial class Arithmetic")
    assertContains(cs, "public static int Add(int a, int b)")
    assertContains(cs, "EntryPoint = \"add\"")
    // A PascalCased name is never a C# keyword, so `fun lock()` no longer needs the `@` escape.
    assertContains(cs, "public static string Lock()")
    assertFalse(
      "@lock" in cs,
      "expected no verbatim identifier once the name is PascalCase; cs=$cs",
    )
  }

  /**
   * CS0102. Kotlin puts properties and functions in separate namespaces, C# does not: `val name`
   * and `fun name()` in one file both want the member name `Name` on the file's static class.
   * Fatal, not a silent drop of one of them: ADR-055's contract projects a planned callable into
   * both halves, so the function cannot be exported from Kotlin and omitted from the C#.
   */
  @Test
  fun `top-level function colliding with a top-level property fails and names both`() {
    val result = Tier1Harness.run(
      """
      package tier1.pascalcollision

      val name: String = "clinic"

      fun name(): String = "clinic()"
      """.trimIndent(),
      fileName = "Registry.kt",
    )

    val error: String? = result.kspErrors.firstOrNull {
      it.contains(ForwardDiagnosticKind.ERROR_CSHARP_NAME_COLLISION.name)
    }
    assertTrue(error != null, "expected the named collision failure; kspErrors=${result.kspErrors}")
    assertContains(error, "Registry.Name")
    assertContains(error, "property")
    assertContains(error, "CS0102")
    assertContains(error, "rename the Kotlin function 'name'")
  }

  /**
   * CS0542, resolved by renaming the class, not by failing. `fun greeting()` in `Greeting.kt`
   * would be `Greeting.Greeting()`, a member named like its enclosing type, so ADR-007's `Kt`
   * suffix (which it already applies when a *type* claims the file class name) applies here too.
   * `fun beam()` in `Beam.kt` is ordinary Kotlin and has to keep binding.
   */
  @Test
  fun `top-level function claiming its own file class renames the class and notes it`() {
    val result = Tier1Harness.run(
      """
      package tier1.pascalfileclass

      fun greeting(): String = "hi"

      fun other(): String = "ok"
      """.trimIndent(),
      fileName = "Greeting.kt",
    )

    assertTrue(result.compiledClean, "got: ${result.compileErrors}")
    assertTrue(result.kspErrors.isEmpty(), "expected no failure; kspErrors=${result.kspErrors}")

    val cs: String = result.generatedCSharp
    assertContains(cs, "public static partial class GreetingKt")
    assertContains(cs, "public static string Greeting()")
    // The whole file class moves, not just the claiming function.
    assertContains(cs, "public static string Other()")
    // C#-only: the native export keeps the Kotlin spelling.
    assertContains(cs, "EntryPoint = \"greeting\"")
    assertContains(result.generated, "@CName(\"greeting\")")

    val note: String? = result.kspWarnings.firstOrNull {
      it.contains(ForwardDiagnosticKind.INFO_FILE_CLASS_RENAMED.name)
    }
    assertTrue(note != null, "expected the rename note; kspWarnings=${result.kspWarnings}")
    assertContains(note, "GreetingKt")
    assertContains(note, "CS0542")
  }

  /**
   * The legacy routes escape a C# keyword *after* the case change, like the plan route: a
   * sealed-returning `fun lock()` is `Lock()`, and `suspend fun event()` is `EventAsync()`.
   * Escaping first would leave the verbatim `@lock` / `@eventAsync` this ADR exists to remove.
   */
  @Test
  fun `legacy route keyword names PascalCase rather than escaping`() {
    val result = Tier1Harness.run(
      """
      package tier1.pascallegacy

      sealed class Shape {
        data class Dot(val size: Int) : Shape()
      }

      fun lock(): Shape = Shape.Dot(1)

      suspend fun event(): String = "tick"
      """.trimIndent(),
      fileName = "Locks.kt",
    )

    val cs: String = result.generatedCSharp
    assertContains(cs, "Lock()")
    assertContains(cs, "EventAsync")
    assertContains(cs, "EntryPoint = \"lock\"")
    assertFalse("@lock" in cs, "expected no verbatim identifier on the sealed route; cs=$cs")
    assertFalse("@event" in cs, "expected no verbatim identifier on the suspend route; cs=$cs")
  }
}
