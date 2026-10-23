package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * ADR-239: a `suspend` lambda parameter carries the plain callback's payload set (`Char`, a
 * nullable, a collection, `ByteArray`, `Duration`, a value class) on the boxed `Object` wire, and a
 * nullable `suspend` lambda (`(suspend (Int) -> String)?`) binds with its C# `null` crossing as a
 * zero key, the way a `Flow<T>?` source does. Every cell binds on both halves and is named nowhere.
 */
class Tier1SuspendLambdaVocabularyTest {

  private val source: String = """
    package tier1.suspendvocab

    import kotlin.time.Duration

    class Tag(val name: String)

    @JvmInline
    value class Paw(val size: Int)

    class Chef {
      suspend fun maybe(prepare: (suspend (Int) -> String)?): String = prepare?.invoke(1) ?: "none"
      fun maybeNow(prepare: (suspend (Int) -> String)?): Int = if (prepare == null) 0 else 1
      suspend fun initial(pick: suspend (Char) -> String): String = pick('a')
      suspend fun names(pick: suspend (List<String>) -> Int): Int = pick(listOf("a"))
      suspend fun maybeCount(pick: suspend (Int?) -> String): String = pick(null)
      suspend fun bytes(pick: suspend (ByteArray) -> Int): Int = pick(byteArrayOf(1))
      suspend fun elapsed(pick: suspend (Duration) -> Long): Long = pick(Duration.ZERO)
      suspend fun paw(pick: suspend (Paw) -> Int): Int = pick(Paw(2))
    }

    fun relay(events: (suspend () -> Int)? = null, tag: Tag): Int = if (events == null) 0 else 1
  """.trimIndent()

  @Test
  fun `every suspend lambda payload and a nullable suspend lambda bind and are named nowhere`() {
    val result: Tier1Result =
      Tier1Harness.run(source, libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore))
    assertTrue(result.kspErrors.isEmpty(), "kspErrors=${result.kspErrors}")
    assertTrue(result.compiledClean, "compileErrors=${result.compileErrors}")
    val cs: String = result.generatedCSharp
    listOf(
      "MaybeAsync(Func<int, global::System.Threading.CancellationToken, " +
          "global::System.Threading.Tasks.Task<string>>? prepare",
      "MaybeNow(Func<int, global::System.Threading.CancellationToken, " +
          "global::System.Threading.Tasks.Task<string>>? prepare",
      "InitialAsync(Func<char, ", "NamesAsync(Func<IReadOnlyList<string>, ",
      "MaybeCountAsync(Func<int?, ", "BytesAsync(Func<byte[], ",
      "ElapsedAsync(Func<global::System.TimeSpan, ", "PawAsync(Func<", "Relay(",
    ).forEach { member ->
      val declared: List<String> = cs.lines().filter { line ->
        "Async(" in line || "Relay(" in line || "MaybeNow(" in line
      }
      assertTrue(member in cs, "expected `$member` declared; cs lines: $declared")
    }
    assertTrue(
      result.kspWarnings.none { warning -> "suspendvocab." in warning },
      "a member that binds must not be reported; got: ${result.kspWarnings}",
    )
  }
}
