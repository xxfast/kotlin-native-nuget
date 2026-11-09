package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

/**
 * ADR-241: an exported Kotlin exception class binds as a C# exception type. The handle class IS
 * the exception: its root derives from the matched mapped `Kotlin*` type, else `KotlinException`,
 * and the per-module `nugetMappedType` classifies exported classes with `is`, deepest first,
 * before the optional and stdlib rows.
 *
 * Oreo raids the treat cupboard; Mylo left it open.
 */
class Tier1ExportedExceptionTypeTest {

  private val options: Map<String, String> = mapOf("nuget.rootPackage" to "tier1")

  private val store: Tier1Result by lazy {
    Tier1Harness.run(
      """
      package tier1.store

      sealed class StoreException(message: String) : Exception(message) {
        class Unauthorized : StoreException("not signed in")
        data class NotFound(val key: String) : StoreException("no ${'$'}key")
        abstract class Locked(message: String) : StoreException(message)
      }

      class StaleCache(message: String) : IllegalStateException(message)

      private class TokenExpired : StoreException.Locked("expired")

      class TreatStore {
        fun fetch(key: String): String = throw StoreException.NotFound(key)
        fun signIn(): Unit = throw TokenExpired()
        fun stale(): Unit = throw StaleCache("stale")
        fun lastMiss(): StoreException = StoreException.NotFound("tuna")
      }
      """.trimIndent(),
      processorOptions = options,
    )
  }

  @Test
  fun `nugetMappedType classifies exported classes deepest first before the rows`() {
    assertTrue(store.compiledClean, "expected a clean compile; got: ${store.compileErrors}")
    val kt: String = store.generated
    val notFound: Int = kt.indexOf("t is tier1.store.StoreException.NotFound ->")
    val locked: Int = kt.indexOf("t is tier1.store.StoreException.Locked ->")
    val root: Int = kt.indexOf("t is tier1.store.StoreException ->")
    val stale: Int = kt.indexOf("t is tier1.store.StaleCache ->")
    assertTrue(notFound >= 0 && locked >= 0 && root >= 0 && stale >= 0, "missing arms; generated=$kt")
    assertTrue(notFound < root && locked < root, "deepest first; generated=$kt")
    assertContains(kt, "internal fun nugetRowType(")
  }

  @Test
  fun `the exception roots derive from the mapped base`() {
    val cs: String = store.generatedCSharp
    assertContains(cs, "class StoreException : global::Kotlin.Native.Interop.KotlinException")
    assertContains(
      cs,
      "class StaleCache : global::Kotlin.Native.Interop.KotlinInvalidOperationException",
    )
  }

  @Test
  fun `the generated C# compiles and a consumer catches the exported types`() {
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
                  try { store.Fetch("oreo"); }
                  catch (StoreException.NotFound e) { return e.Key + e.KotlinType; }
                  catch (StoreException e) { return e.KotlinType; }
                  try { store.Stale(); }
                  catch (InvalidOperationException e) { return e.Message; }
                  using StoreException miss = store.LastMiss();
                  return miss.Message;
              }
          }
      }
      """.trimIndent(),
    )
  }
}
