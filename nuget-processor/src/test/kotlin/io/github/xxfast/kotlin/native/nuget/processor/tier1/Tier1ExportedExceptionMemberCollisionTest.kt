package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-241: a member an exported exception class declares whose C# name would hide a
 * `System.Exception` member is a named skip (`SKIPPED_EXCEPTION_MEMBER_COLLISION`), absent from
 * BOTH halves on every route (plan, suspend, Flow, per-call lambda, sealed arm), named once on
 * the class that declares it, with a `<remarks>` paragraph there. A non-colliding member beside it
 * still binds, and an inherited `Throwable` member is not reported.
 *
 * Not covered, and not handled: a colliding member that implements an exported interface member
 * (`interface Sourced { val source: String }`). Skipping it leaves `Exception.Source` implicitly
 * implementing `ISourced.Source` (CS8766 on the nullability, the wrong value either way).
 *
 * Mylo labels his kennel's complaints.
 */
class Tier1ExportedExceptionMemberCollisionTest {

  private val result: Tier1Result by lazy {
    Tier1Harness.run(
      """
      package tier1.kennel

      import kotlinx.coroutines.flow.Flow
      import kotlinx.coroutines.flow.flowOf

      class KennelError(val data: String, val bark: String) : Exception("woof") {
        val source: String = "kennel"
        val helpLink: String get() = "https://kennel"
        fun getBaseException(): String = "base"
        suspend fun targetSite(): String = "bowl"
        val hResult: Flow<Int> get() = flowOf(1)
        fun getObjectData(callback: (Int) -> Unit) = callback(1)
        fun howl(): String = "awoo"
      }

      sealed class Mishap(message: String) : Exception(message) {
        class Spill(val innerException: String) : Mishap("spill")
      }
      """.trimIndent(),
      processorOptions = mapOf("nuget.rootPackage" to "tier1"),
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )
  }

  private val collisions: List<String> = listOf(
    "data", "source", "helpLink", "getBaseException", "targetSite", "hResult", "getObjectData",
  )

  private val code: List<String> by lazy {
    result.generatedCSharp.lines().filterNot { line -> line.trimStart().startsWith("///") }
  }

  @Test
  fun `each colliding member is a named skip on the class that declares it`() {
    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")
    val skips: List<String> = result.kspWarnings
      .filter { warning -> "[nuget:SKIPPED_EXCEPTION_MEMBER_COLLISION]" in warning }
    val named: Set<String> = skips
      .map { warning -> Regex("""Skipping ([\w.]+):""").find(warning)!!.groupValues[1] }
      .toSet()
    assertEquals(
      collisions.map { "tier1.kennel.KennelError.$it" }.toSet() +
        "tier1.kennel.Mishap.Spill.innerException",
      named,
      "skips=$skips",
    )
    assertEquals(named.size, skips.size, "each member is named once; skips=$skips")
    // An inherited `Throwable` member (message, cause, the JVM's stackTrace) is not the author's.
    assertFalse(skips.any { "Throwable" in it || ".message" in it || ".cause" in it }, "skips=$skips")
  }

  @Test
  fun `a colliding member is absent from both halves on every route`() {
    val exports: List<String> = Regex("""@CName\("([^"]+)"\)""").findAll(result.generated)
      .map { match -> match.groupValues[1] }
      .toList()
    for (member in collisions + "innerException") {
      val lower: String = member.lowercase()
      assertFalse(
        exports.any { export -> export.lowercase().endsWith("_$lower") || "_get_$lower" in export.lowercase() || "_${lower}_" in export.lowercase() },
        "a Kotlin export survived for $member: ${exports.filter { lower in it.lowercase() }}",
      )
    }
    val csharpNames: List<String> =
      listOf("Data", "Source", "HelpLink", "GetBaseException", "TargetSite", "HResult", "GetObjectData", "InnerException")
    for (name in csharpNames) {
      assertFalse(
        code.any { line -> Regex("""\bpublic [^(=]*\b$name\b""").containsMatchIn(line) },
        "the C# declares $name: ${code.filter { name in it && "public" in it }}",
      )
    }
    // The non-colliding members beside them still bind.
    assertTrue(code.any { "public string Bark" in it }, "Bark is missing")
    assertTrue(code.any { "public string Howl()" in it }, "Howl is missing")
  }

  @Test
  fun `the skip is named in the class remarks and the C# compiles`() {
    val cs: String = result.generatedCSharp
    assertTrue(
      "/// <para>Not generated from Kotlin `data`: `KennelError` binds as a C# exception type, and " +
        "its member `Data` would hide `System.Exception.Data` (SKIPPED_EXCEPTION_MEMBER_COLLISION).</para>" in cs,
      "expected a remark naming the skipped member; cs=${cs.lines().filter { "///" in it && "data" in it }}",
    )
    Tier1CSharpCompile.assertCompiles(result, "namespace Consumer { public static class Probe { } }")
  }
}
