package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-241: every key the Kotlin `nugetMappedType` `is` chain can return has a C# `MapExported`
 * arm, so nothing whose nearest exported class is a sealed root, an abstract arm or an `object` arm
 * falls through to the rows as a bare `KotlinException`. Each arm builds its node from the one
 * envelope read `BuildException` / `BuildCauses` already did, over the chain built below it, and a
 * C#-constructed instance reads its own Kotlin envelope.
 *
 * Oreo raids the treat cupboard; Mylo left it open.
 */
class Tier1ExportedExceptionArmsTest {

  private val options: Map<String, String> = mapOf("nuget.rootPackage" to "tier1")

  private val store: Tier1Result by lazy {
    Tier1Harness.run(
      """
      package tier1.store

      sealed class StoreException(message: String) : Exception(message) {
        class Unauthorized : StoreException("not signed in")
        data class NotFound(val key: String) : StoreException("no ${'$'}key")
        abstract class Locked(message: String) : StoreException(message)
        object Empty : StoreException("the cupboard is empty")
        data object Closed : StoreException("the cupboard is closed")
      }

      internal class Sneaky : StoreException("Mylo sneaked in")

      private class TokenExpired : StoreException.Locked("expired")

      open class KennelError(message: String) : Exception(message)

      private class Gnawed : KennelError("Oreo gnawed the latch")

      class StaleCache(message: String) : IllegalStateException(message)

      class TreatStore {
        fun fetch(key: String): String = throw StoreException.NotFound(key)
        fun sneak(): Unit = throw Sneaky()
        fun empty(): Unit = throw StoreException.Empty
        fun gnaw(): Unit = throw Gnawed()
        fun collapse(): Unit = throw IllegalStateException("collapsed", StoreException.NotFound("tuna"))
        fun lastMiss(): StoreException = StoreException.NotFound("tuna")
      }
      """.trimIndent(),
      processorOptions = options,
    )
  }

  /** The code lines of the generated C#, so an assertion never matches a `///` remark. */
  private val code: List<String> by lazy {
    store.generatedCSharp.lines().filterNot { line -> line.trimStart().startsWith("///") }
  }

  private fun kotlinKeys(): Set<String> {
    val body: String = store.generated
      .substringAfter("internal fun nugetMappedType(t: Throwable): String? = when {")
      .substringBefore("else -> nugetRowType(t)")
    return Regex("""t is ([\w.]+) -> "([\w.]+)"""").findAll(body)
      .map { match ->
        assertEquals(match.groupValues[1], match.groupValues[2], "an `is` target and its key differ")
        match.groupValues[2]
      }
      .toSet()
  }

  private fun csharpArms(): Map<String, String> = code
    .dropWhile { line -> "private static Exception? MapExported(" !in line }
    .takeWhile { line -> line.trim() != "};" }
    .mapNotNull { line -> Regex("""^\s+"([\w.]+)" => new ([\w.:]+)\(""").find(line) }
    .associate { match -> match.groupValues[1] to match.groupValues[2] }

  @Test
  fun `every key the Kotlin classifier returns has a MapExported arm`() {
    assertTrue(store.compiledClean, "expected a clean compile; got: ${store.compileErrors}")
    val keys: Set<String> = kotlinKeys()
    assertEquals(
      setOf(
        "tier1.store.StoreException",
        "tier1.store.StoreException.Unauthorized",
        "tier1.store.StoreException.NotFound",
        "tier1.store.StoreException.Locked",
        "tier1.store.StoreException.Empty",
        "tier1.store.StoreException.Closed",
        "tier1.store.KennelError",
        "tier1.store.StaleCache",
      ),
      keys,
    )
    assertEquals(keys, csharpArms().keys, "nugetMappedType keys and MapExported arms disagree")
  }

  @Test
  fun `a sealed root and an abstract arm construct their backing, an object arm itself`() {
    val arms: Map<String, String> = csharpArms()
    assertEquals("global::Interop.Store.StoreException.Backing", arms["tier1.store.StoreException"])
    // The root claims `Backing`, so the abstract arm's own wrapper must not hide it (CS0108).
    assertEquals(
      "global::Interop.Store.StoreException.Locked.Backing_",
      arms["tier1.store.StoreException.Locked"],
    )
    assertEquals("global::Interop.Store.StoreException.Empty", arms["tier1.store.StoreException.Empty"])
    assertTrue(code.any { line -> line.trim() == "internal sealed class Backing : StoreException" })
  }

  @Test
  fun `MapExported builds each node from the envelope read already done`() {
    val cs: String = code.joinToString("\n")
    assertContains(
      cs,
      "\"tier1.store.StoreException.NotFound\" => new global::Interop.Store.StoreException.NotFound(" +
        "new NugetKotlinHandle(Native_causeThrowable(errorPtr, index)), " +
        "new NugetErrorSnapshot(kotlinType, msg, stackTrace, inner), out _),",
    )
    assertContains(cs, "Exception? inner = BuildCauses(errorPtr, causeCount);")
    assertContains(cs, "Exception? exported = MapExported(errorPtr, 0, mappedType, kotlinType, msg, stackTrace, inner);")
    // A cause is built over the chain below it, never over its own second envelope.
    assertContains(cs, "inner = MapExported(errorPtr, i, causeMapped, causeType, causeMsg, causeStack, inner) ??")
    assertContains(
      cs,
      "internal StoreException(NugetKotlinHandle handle, NugetErrorSnapshot snapshot, out NugetHandleTag tag)",
    )
    assertContains(
      cs,
      "internal NotFound(NugetKotlinHandle handle, NugetErrorSnapshot snapshot, out NugetHandleTag tag) : " +
        "base(handle, snapshot, out tag)",
    )
    // A value read keeps reading the handle's own envelope.
    assertContains(
      cs,
      "internal StoreException(NugetKotlinHandle handle, out NugetHandleTag tag) : " +
        "this(handle, NugetErrorNative.Snapshot(handle, \"tier1.store.StoreException\"), out tag)",
    )
  }

  @Test
  fun `a C#-constructed exception creates its Kotlin object before the base reads it`() {
    val cs: String = code.joinToString("\n")
    assertContains(cs, "public NotFound(string key) : this(NugetCreateNotFound(key), out _)")
    assertContains(cs, "private static NugetKotlinHandle NugetCreateNotFound(string key)")
    assertContains(cs, "public StaleCache(string message) : this(NugetCreateStaleCache(message), out _)")
    assertContains(cs, "public KennelError(string message) : this(NugetCreateKennelError(message), out _)")
    assertFalse(
      code.any { line -> Regex("""public (NotFound|StaleCache|KennelError|Unauthorized)\(.*\) : (base|this)\(IntPtr\.Zero""").containsMatchIn(line) },
      "an exception constructor still chains through an empty snapshot",
    )
  }

  @Test
  fun `the generated C# compiles and a consumer catches every arm`() {
    Tier1CSharpCompile.assertCompiles(
      store,
      """
      using System;
      using Interop.Store;

      namespace Consumer
      {
          public static class Probe
          {
              public static string Run(TreatStore store)
              {
                  try { store.Empty(); }
                  catch (StoreException.Empty e) { return e.KotlinType; }
                  try { store.Sneak(); }
                  catch (StoreException e) { return e.KotlinType; }
                  try { store.Gnaw(); }
                  catch (KennelError e) { return e.Message; }
                  using var notFound = new StoreException.NotFound("oreo");
                  return notFound.Key + notFound.Message;
              }
          }
      }
      """.trimIndent(),
    )
  }
}
