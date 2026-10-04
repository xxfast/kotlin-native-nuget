package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ADR-179 amendment (2026-10-03): collision keys are unescaped on every route and every extern stem
 * comes from the Kotlin name. Two consequences no cell pinned before:
 *
 * - A keyword-named method beside a same-named property is the CS0102 collision (`@lock()` beside
 *   `@lock { get; }`), detected by the ADR-110 guard on its unescaped key, and the same holds
 *   against a nested type (ADR-133 surface 6), which compares the member's rendered C# name.
 * - An async or `Flow` method whose public C# name is not its Kotlin name (a keyword, a declared
 *   name) still imports the Kotlin `@CName` symbol, under the extern its body calls.
 */
class Tier1KeywordMemberCollisionTest {

  private fun run(body: String): Tier1Result = Tier1Harness.run(
    "package tier1.kwcollide\n\n" +
        "import io.github.xxfast.kotlin.native.nuget.annotations.CSharpName\n" +
        "import kotlinx.coroutines.flow.Flow\n" +
        "import kotlinx.coroutines.flow.MutableStateFlow\n" +
        "import kotlinx.coroutines.flow.StateFlow\n" +
        "import kotlinx.coroutines.flow.flowOf\n\n" + body,
    libraries = listOf(csharpNameLibrary, Tier1Classpath.kotlinxCoroutinesCore),
  )

  private fun Tier1Result.assertError(kind: ForwardDiagnosticKind, vararg fragments: String) {
    assertTrue(
      kspErrors.any { message ->
        message.contains(kind.name) && fragments.all { fragment -> message.contains(fragment) }
      },
      "expected ${kind.name} containing ${fragments.toList()}; kspErrors=$kspErrors",
    )
  }

  private fun Tier1Result.assertCompilesClean() {
    assertTrue(kspSucceeded, "kspErrors=$kspErrors")
    assertTrue(compiledClean, "compileErrors=$compileErrors")
    Tier1CSharpCompile.assertCompiles(this, "", allowUnsafe = true)
  }

  // ---- Claim 1: a keyword method beside a same-named property or nested type ----

  @Test
  fun `a keyword declared method and a keyword declared property of one name collide`() {
    val result: Tier1Result = run(
      """
      class Vault {
        @CSharpName("lock") val held: Int get() = 1
        @CSharpName("lock") fun take(): Int = 2
      }
      """.trimIndent(),
    )
    result.assertError(
      ForwardDiagnosticKind.ERROR_CSHARP_NAME_COLLISION,
      "Vault.lock",
      "`val held` (`@CSharpName(\"lock\")`)",
      "`fun take()` (`@CSharpName(\"lock\")`)",
    )
  }

  @Test
  fun `a keyword Kotlin method and property of one name collide without a declared name`() {
    val result: Tier1Result = run(
      """
      class Vault {
        val lock: Int get() = 1
        fun lock(): Int = 2
      }
      """.trimIndent(),
    )
    result.assertError(
      ForwardDiagnosticKind.ERROR_CSHARP_NAME_COLLISION,
      "Vault.Lock",
      "`val lock`",
      "`fun lock()`",
    )
  }

  @Test
  fun `a keyword declared method beside a property rendering its PascalCase does not collide`() {
    val result: Tier1Result = run(
      """
      class Vault {
        val lock: Int get() = 1
        @CSharpName("lock") fun take(): Int = 2
      }
      """.trimIndent(),
    )
    result.assertCompilesClean()
    assertContains(result.generatedCSharp, "public int Lock")
    assertContains(result.generatedCSharp, "public int @lock()")
  }

  @Test
  fun `a keyword declared suspend method and a keyword property of one name collide`() {
    val result: Tier1Result = run(
      """
      class Vault {
        @CSharpName("lock") val held: Int get() = 1
        @CSharpName("lock") suspend fun take(): Int = 2
      }
      """.trimIndent(),
    )
    result.assertError(
      ForwardDiagnosticKind.ERROR_CSHARP_NAME_COLLISION,
      "Vault.lock",
      "`fun take()` (`@CSharpName(\"lock\")`)",
    )
  }

  @Test
  fun `a keyword declared flow method and a keyword property of one name collide`() {
    val result: Tier1Result = run(
      """
      class Vault {
        @CSharpName("lock") val held: Int get() = 1
        @CSharpName("lock") fun watch(): Flow<Int> = flowOf(1)
      }
      """.trimIndent(),
    )
    result.assertError(
      ForwardDiagnosticKind.ERROR_CSHARP_NAME_COLLISION,
      "Vault.lock",
      "`fun watch()` (`@CSharpName(\"lock\")`)",
    )
  }

  @Test
  fun `a method and a nested type of one rendered name collide`() {
    val result: Tier1Result = run(
      """
      class Vault {
        class Lock(val id: Int)
        fun lock(): Int = 2
      }
      """.trimIndent(),
    )
    result.assertError(
      ForwardDiagnosticKind.ERROR_CSHARP_SIGNATURE_COLLISION,
      "tier1.kwcollide.Vault.Lock",
      "the member `Lock`",
    )
  }

  @Test
  fun `a declared method name taking a nested type's name collides`() {
    val result: Tier1Result = run(
      """
      class Vault {
        class Lock(val id: Int)
        @CSharpName("Lock") fun take(): Int = 2
      }
      """.trimIndent(),
    )
    result.assertError(
      ForwardDiagnosticKind.ERROR_CSHARP_SIGNATURE_COLLISION,
      "tier1.kwcollide.Vault.Lock",
      "the member `Lock`",
      // The hint names the remedy that fits a declared name; PascalCasing is not what collided.
      "or give the member a different `@CSharpName`, so the two C# names differ",
    )
    assertTrue(result.kspErrors.none { "PascalCasing" in it }, "kspErrors=${result.kspErrors}")
  }

  @Test
  fun `a method declared away from a nested type's name does not collide`() {
    val result: Tier1Result = run(
      """
      class Vault {
        class Lock(val id: Int)
        @CSharpName("lock") fun lock(): Int = 2
      }
      """.trimIndent(),
    )
    result.assertCompilesClean()
    assertContains(result.generatedCSharp, "public int @lock()")
    assertContains(result.generatedCSharp, "class Lock")
  }

  @Test
  fun `a suspend method rendering an Async name does not collide with a nested type`() {
    val result: Tier1Result = run(
      """
      class Vault {
        class Lock(val id: Int)
        suspend fun lock(): Int = 2
      }
      """.trimIndent(),
    )
    result.assertCompilesClean()
    assertContains(result.generatedCSharp, "LockAsync(")
    assertContains(result.generatedCSharp, "class Lock")
  }

  // ---- Claim 2: an async or Flow method's extern comes from the export symbol ----

  /**
   * Every `EntryPoint` the C# imports is a Kotlin `@CName`, and every `Native_*` extern a body
   * calls is declared (the C# compile proves the second; this names the first).
   */
  private fun Tier1Result.assertImportsMatchExports() {
    val cnames: Set<String> =
      Regex("@CName\\(\"([^\"]+)\"").findAll(generated).map { it.groupValues[1] }.toSet()
    // The `nuget_*` runtime symbols are exported by the runtime module, not by this one.
    val entryPoints: Set<String> = Regex("EntryPoint = \"(library_tier1_kwcollide__[^\"]+)\"")
      .findAll(generatedCSharp)
      .map { it.groupValues[1] }
      .toSet()
    assertTrue(entryPoints.isNotEmpty(), generatedCSharp)
    assertEquals(emptySet(), entryPoints - cnames, "imports with no export\n$generatedCSharp")
  }

  @Test
  fun `a renamed or keyword suspend method imports its Kotlin symbol`() {
    val result: Tier1Result = run(
      """
      class Vault {
        suspend fun lock(): Int = 1
        @CSharpName("event") suspend fun fetch(): Int = 2
        @CSharpName("Pull") suspend fun load(): Int = 3
        @CSharpName("fixed") suspend fun hold(): StateFlow<Int> = MutableStateFlow(4)
        @CSharpName("Stream") suspend fun open(): Flow<Int> = flowOf(5)
      }
      """.trimIndent(),
    )
    val cs: String = result.generatedCSharp
    assertContains(cs, "Task<int> LockAsync(")
    assertContains(cs, "Task<int> @event(")
    assertContains(cs, "Task<int> Pull(")
    assertContains(cs, "@fixed(")
    assertContains(cs, "Stream(")
    listOf("lock", "fetch", "load", "hold", "open").forEach { kotlin ->
      assertContains(cs, "EntryPoint = \"library_tier1_kwcollide__vault_${kotlin}_async\"")
    }
    assertContains(cs, "extern IntPtr Native_FetchAsync(")
    assertContains(cs, "extern IntPtr Native_LoadAsync(")
    result.assertImportsMatchExports()
    result.assertCompilesClean()
  }

  @Test
  fun `a renamed or keyword flow method imports its Kotlin symbol`() {
    val result: Tier1Result = run(
      """
      class Vault {
        fun lock(): Flow<Int> = flowOf(1)
        @CSharpName("event") fun watch(): Flow<Int> = flowOf(2)
        @CSharpName("Level") fun level(): StateFlow<Int> = MutableStateFlow(3)
        @CSharpName("fixed") fun hold(): MutableStateFlow<Int> = MutableStateFlow(4)
      }
      """.trimIndent(),
    )
    val cs: String = result.generatedCSharp
    assertContains(cs, "KotlinFlow<int> Lock(")
    assertContains(cs, "KotlinFlow<int> @event(")
    assertContains(cs, "KotlinStateFlow<int> Level(")
    assertContains(cs, "KotlinMutableStateFlow<int> @fixed(")
    assertContains(cs, "EntryPoint = \"library_tier1_kwcollide__vault_lock_collect\"")
    assertContains(cs, "EntryPoint = \"library_tier1_kwcollide__vault_watch_collect\"")
    assertContains(cs, "EntryPoint = \"library_tier1_kwcollide__vault_level_value\"")
    assertContains(cs, "EntryPoint = \"library_tier1_kwcollide__vault_hold\"")
    assertContains(cs, "extern IntPtr Native_WatchCollect(")
    assertContains(cs, "extern IntPtr Native_Hold(")
    result.assertImportsMatchExports()
    result.assertCompilesClean()
  }
}
