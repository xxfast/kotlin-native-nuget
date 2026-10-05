package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The ADR-201 amendment: the positions ADR-201 deferred. Each binds with ADR-201's two encodings
 * (the ADR-107 envelope out of Kotlin, the `"{FullName}: {Message}"` text in) or is a named,
 * reasoned refusal. One cell group per code path.
 *
 * Mochi coughs up a hairball at 3am; Oreo reports it, in bulk.
 */
class Tier1ThrowableRemainingPositionsTest {

  private val options: Map<String, String> = mapOf("nuget.rootPackage" to "tier1")

  /**
   * Group A: a `List`/`MutableList` element and a `Map` value at an input take the parameter
   * encoding per element. C# projects each element to its text before boxing; Kotlin casts the box
   * to `String` and builds a `NugetManagedException`. The same gate admits a `MutableList` setter
   * and a `suspend` list parameter. A narrower declared element still skips named.
   */
  @Test
  fun `a List of Throwable input binds per element`() {
    val result: Tier1Result = Tier1Harness.run(
      """
      package tier1.throwablelistinput

      class MishapLog {
        fun reportAll(mishaps: List<Exception>): Int = mishaps.size
        fun reportSome(mishaps: List<Throwable?>): Int = mishaps.count { it != null }
        fun reportByCat(mishaps: Map<String, Throwable>): Int = mishaps.size
        var pending: MutableList<Throwable> = mutableListOf()
        suspend fun reportLater(mishaps: List<RuntimeException>): Int = mishaps.size
        fun strictAll(mishaps: List<IllegalStateException>): Int = mishaps.size
      }
      """.trimIndent(),
      processorOptions = options,
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )

    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")
    assertTrue(result.kspSucceeded, "expected KSP to succeed; got: ${result.kspErrors}")
    val cs: String = result.generatedCSharp
    assertContains(cs, "public int ReportAll(IReadOnlyList<global::System.Exception> mishaps)")
    assertContains(cs, "public int ReportSome(IReadOnlyList<global::System.Exception?> mishaps)")
    assertContains(
      cs, "public int ReportByCat(IReadOnlyDictionary<string, global::System.Exception> mishaps)",
    )
    assertContains(cs, "IList<global::System.Exception> Pending")
    assertContains(cs, "ReportLaterAsync(IReadOnlyList<global::System.Exception> mishaps")
    assertContains(cs, ".GetType().FullName ?? \"System.Exception\") + \": \" + ")
    assertContains(result.generated, "NugetManagedException(")
    assertFalse(
      Regex("""\bStrictAll\(""").containsMatchIn(cs),
      "a narrower declared element must not bind; generated=$cs",
    )
    assertTrue(
      result.kspWarnings.any {
        it.contains("MishapLog.strictAll") &&
            it.contains("is declared `List<IllegalStateException>`") &&
            it.contains("RuntimeException")
      },
      "expected a THROWABLE skip for strictAll; kspWarnings=${result.kspWarnings}",
    )
    Tier1CSharpCompile.assertCompiles(
      result,
      """
      using System.Collections.Generic;
      public static class Consumer
      {
          public static int Send(Interop.Throwablelistinput.MishapLog log)
          {
              log.Pending = new List<System.Exception> { new System.Exception("fever") };
              return log.ReportAll(new System.Exception[] { new System.Exception("a") })
                  + log.ReportSome(new System.Exception?[] { null });
          }
      }
      """.trimIndent(),
      allowUnsafe = true,
    )
  }

  /**
   * Group B: a `suspend` or `Flow`-returning member's parameter takes the sync route's one nullable
   * `STRING` slot: C# sends the managed-exception text, Kotlin rebuilds a `NugetManagedException`.
   * A narrower declaration stays a named refusal.
   */
  @Test
  fun `a suspend or Flow parameter typed Throwable binds as System Exception`() {
    val result: Tier1Result = Tier1Harness.run(
      """
      package tier1.throwableasyncinput

      import kotlinx.coroutines.flow.Flow
      import kotlinx.coroutines.flow.flowOf

      class MishapLog {
        suspend fun reportLater(mishap: Throwable): String = mishap.message ?: ""
        suspend fun maybeLater(mishap: Exception?): Boolean = mishap != null
        fun watch(mishap: RuntimeException): Flow<Int> = flowOf(1)
        suspend fun strictLater(mishap: IllegalStateException): Int = 1
      }

      suspend fun reportTopLater(mishap: Throwable?): Int = if (mishap == null) 0 else 1
      """.trimIndent(),
      processorOptions = options,
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )

    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")
    assertTrue(result.kspSucceeded, "expected KSP to succeed; got: ${result.kspErrors}")
    val cs: String = result.generatedCSharp
    assertContains(cs, "ReportLaterAsync(global::System.Exception mishap")
    assertContains(cs, "MaybeLaterAsync(global::System.Exception? mishap")
    assertContains(cs, "Watch(global::System.Exception mishap")
    assertContains(cs, "ReportTopLaterAsync(global::System.Exception? mishap")
    assertContains(result.generated, "NugetManagedException(")
    assertFalse(
      Regex("""\bStrictLaterAsync\(""").containsMatchIn(cs),
      "a narrower declared parameter must not bind; generated=$cs",
    )
    assertTrue(
      result.kspWarnings.any { it.contains("SKIPPED_") && it.contains("strictLater") },
      "expected a named skip for strictLater; kspWarnings=${result.kspWarnings}",
    )
    Tier1CSharpCompile.assertCompiles(
      result,
      """
      public static class Consumer
      {
          public static System.Threading.Tasks.Task<string> Send(
              Interop.Throwableasyncinput.MishapLog log) =>
              log.ReportLaterAsync(new System.InvalidOperationException("bowl"));
      }
      """.trimIndent(),
      allowUnsafe = true,
    )
  }

  /**
   * Group D: a lambda payload and an ADR-039 listener parameter cross out as the ADR-107 envelope,
   * read with `BuildException`; a lambda result typed `Throwable`/`Exception`/`RuntimeException`
   * comes back as the managed-exception text over the `String` result box. A narrower lambda
   * result and a nullable payload (the callback routes refuse every nullable payload) stay named
   * refusals, and a stored lambda keeps its own route's named refusal.
   */
  @Test
  fun `a lambda payload and a listener parameter typed Throwable bind`() {
    val result: Tier1Result = Tier1Harness.run(
      """
      package tier1.throwablecallback

      interface MishapListener {
        fun onMishap(mishap: Throwable)
        fun onTwo(cat: String, mishap: Exception)
      }

      class MishapLog {
        private val listeners = mutableListOf<MishapListener>()
        fun addMishapListener(listener: MishapListener) { listeners.add(listener) }
        fun removeMishapListener(listener: MishapListener) { listeners.remove(listener) }

        fun onMishap(handler: (Throwable) -> Unit) = handler(IllegalStateException("vet"))
        fun onTwo(handler: (String, Exception) -> Unit) = handler("Oreo", RuntimeException("x"))
        fun recover(fallback: () -> Exception): String = fallback().message ?: ""
        fun strictRecover(fallback: () -> IllegalStateException): Int = 1
        fun onMaybe(handler: (Throwable?) -> Unit) = handler(null)
      }

      class Watcher(val onMishap: (Throwable) -> Unit)
      """.trimIndent(),
      processorOptions = options,
    )

    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")
    assertTrue(result.kspSucceeded, "expected KSP to succeed; got: ${result.kspErrors}")
    val kotlin: String = result.generated
    val cs: String = result.generatedCSharp
    assertContains(cs, "public void OnMishap(Action<global::System.Exception> handler)")
    assertContains(cs, "public void OnTwo(Action<string, global::System.Exception> handler)")
    assertContains(cs, "public string Recover(Func<global::System.Exception> fallback)")
    assertContains(cs, "NugetErrorNative.BuildException(")
    assertContains(kotlin, "NugetHandles.retain(buildError(")
    assertContains(kotlin, "NugetManagedException(")
    // The listener route: subscribed, and its delegate reads the envelope.
    assertContains(cs, "AddMishapListener(")
    assertContains(cs, "global::System.Exception arg0 = NugetErrorNative.BuildException(arg0Ptr);")
    for (member in listOf("strictRecover", "onMaybe")) {
      assertTrue(
        result.kspWarnings.any { it.contains("SKIPPED_") && it.contains("MishapLog.$member") },
        "expected a named skip for $member; kspWarnings=${result.kspWarnings}",
      )
    }
    for (member in listOf(" StrictRecover(", " OnMaybe(")) {
      assertFalse(
        Regex("""public [^\n]*${Regex.escape(member)}""").containsMatchIn(cs),
        "expected $member to be absent; generated=$cs",
      )
    }
    Tier1CSharpCompile.assertCompiles(
      result,
      """
      public sealed class Listener : Interop.Throwablecallback.IMishapListener
      {
          public void OnMishap(System.Exception mishap) { }
          public void OnTwo(string cat, System.Exception mishap) { }
          public void Dispose() { }
      }
      public static class Consumer
      {
          public static string Use(Interop.Throwablecallback.MishapLog log)
          {
              log.OnMishap(e => { });
              return log.Recover(() => new System.InvalidOperationException("bowl"));
          }
      }
      """.trimIndent(),
      allowUnsafe = true,
    )
  }

  /**
   * Group C: a bare `suspend` result, a `Flow` element and a `StateFlow` element on a property or
   * method, plus an acquired `Flow` from a `suspend` member. Each per-member export boxes the
   * ADR-107 envelope, and C# reads it with `BuildException`; a null is the null pointer. A
   * `MutableStateFlow` member keeps refusing (the runtime pair and the ADR-071 write seam have no
   * envelope arm), named with that reason.
   */
  @Test
  fun `a bare suspend result and Flow or StateFlow element typed Throwable bind`() {
    val result: Tier1Result = Tier1Harness.run(
      """
      package tier1.throwableasyncresult

      import kotlinx.coroutines.flow.Flow
      import kotlinx.coroutines.flow.MutableStateFlow
      import kotlinx.coroutines.flow.StateFlow
      import kotlinx.coroutines.flow.flowOf

      class MishapLog {
        private val seen = MutableStateFlow<Throwable?>(null)
        suspend fun worstLater(): Throwable = IllegalArgumentException("Oreo ate the plant")
        suspend fun latestLater(): Exception? = null
        fun each(): Flow<Throwable> = flowOf(IllegalStateException("bowl"))
        fun maybeEach(): Flow<Throwable?> = flowOf(null)
        val current: StateFlow<Throwable?> get() = seen
        fun currentFor(cat: String): StateFlow<Throwable?> = seen
        suspend fun streamLater(): Flow<Exception> = flowOf(RuntimeException("x"))
        val settable: MutableStateFlow<Throwable?> = MutableStateFlow(null)
      }

      suspend fun worstTopLater(): Throwable? = null
      """.trimIndent(),
      processorOptions = options,
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )

    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")
    assertTrue(result.kspSucceeded, "expected KSP to succeed; got: ${result.kspErrors}")
    val kotlin: String = result.generated
    val cs: String = result.generatedCSharp
    assertContains(cs, "Task<global::System.Exception> WorstLaterAsync(")
    assertContains(cs, "Task<global::System.Exception?> LatestLaterAsync(")
    assertContains(cs, "Task<global::System.Exception?> WorstTopLaterAsync(")
    assertContains(cs, "KotlinFlow<global::System.Exception> Each(")
    assertContains(cs, "KotlinFlow<global::System.Exception?> MaybeEach(")
    assertContains(cs, "KotlinStateFlow<global::System.Exception?> Current")
    assertContains(cs, "KotlinStateFlow<global::System.Exception?> CurrentFor(")
    assertContains(cs, "KotlinFlow<global::System.Exception>> StreamLaterAsync(")
    assertContains(cs, "read: static h => NugetErrorNative.BuildException(h)")
    assertContains(
      cs, "resultPtr == IntPtr.Zero ? null : NugetErrorNative.BuildException(resultPtr)",
    )
    assertContains(kotlin, "NugetHandles.retain(buildError(value, ::nugetMappedType))")
    assertContains(kotlin, "buildError(result, ::nugetMappedType)")
    assertFalse(
      Regex("""public [^\n]* Settable\r?$""", RegexOption.MULTILINE).containsMatchIn(cs),
      "a MutableStateFlow<Throwable?> property must not bind; generated=$cs",
    )
    assertTrue(
      result.kspWarnings.any {
        it.contains("MishapLog.settable") && it.contains("runtime's shared StateFlow exports")
      },
      "expected the runtime-pair refusal for settable; kspWarnings=${result.kspWarnings}",
    )
    Tier1CSharpCompile.assertCompiles(
      result, "public static class Consumer { }", allowUnsafe = true,
    )
  }

  /**
   * Permanent refusal: a `StateFlow<Throwable>` read through the runtime-owned pair
   * (`nuget_stateflow_value` / `nuget_stateflow_collect`), which boxes the value itself and has no
   * per-member seam. A held `MutableStateFlow` return and an awaited `StateFlow` both read there,
   * so an envelope read of that box would fail inside an export and abort the process. They must
   * keep refusing by name whatever the per-member routes bind.
   */
  @Test
  fun `a runtime-pair StateFlow of Throwable stays a named refusal`() {
    val result: Tier1Result = Tier1Harness.run(
      """
      package tier1.throwableruntimepair

      import kotlinx.coroutines.flow.MutableStateFlow
      import kotlinx.coroutines.flow.StateFlow

      class MishapLog {
        private val seen = MutableStateFlow<Throwable>(IllegalStateException("bowl"))
        fun held(): MutableStateFlow<Throwable> = seen
        suspend fun watch(): StateFlow<Throwable> = seen
        fun heldMaybe(): MutableStateFlow<Throwable?> = MutableStateFlow(null)
        suspend fun watchMaybe(): StateFlow<Exception?> = MutableStateFlow(null)
      }
      """.trimIndent(),
      processorOptions = options,
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )

    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")
    assertTrue(result.kspSucceeded, "expected KSP to succeed; got: ${result.kspErrors}")
    val cs: String = result.generatedCSharp
    for (member in listOf(" Held(", " WatchAsync(", " HeldMaybe(", " WatchMaybeAsync(")) {
      assertFalse(
        Regex("""public [^\n]*${Regex.escape(member)}""").containsMatchIn(cs),
        "expected $member to be absent; generated=$cs",
      )
    }
    for (member in listOf("held", "watch", "heldMaybe", "watchMaybe")) {
      assertTrue(
        result.kspWarnings.any { it.contains("SKIPPED_") && it.contains("MishapLog.$member") &&
            it.contains("runtime's shared StateFlow exports") },
        "expected a named skip for $member; kspWarnings=${result.kspWarnings}",
      )
    }
  }

  /**
   * Group E: a C#-implemented interface slot (ADR-084). A parameter is the envelope over the slot's
   * `OBJECT` wire, read with `BuildException`; a result is the text over the same wire, boxed with
   * `WrapString` and rebuilt as a `NugetManagedException`. A result declared narrower than
   * `RuntimeException` plans no factory, and that refusal is named rather than silent.
   */
  @Test
  fun `a C#-implemented interface slot binds Throwable both ways`() {
    val result: Tier1Result = Tier1Harness.run(
      """
      package tier1.throwablebridge

      interface MishapSink {
        fun accept(mishap: Throwable)
        fun maybe(mishap: Exception?): Boolean
        fun last(): Exception?
        fun worst(): Throwable
        val latest: RuntimeException?
        fun note(text: String?)
      }

      interface StrictSink {
        fun last(): IllegalStateException?
      }

      class Drain {
        fun drainTo(sink: MishapSink): String? {
          sink.accept(IllegalStateException("Mochi, 3am"))
          sink.note(null)
          return sink.last()?.message
        }
        fun strict(sink: StrictSink): Int = 1
      }
      """.trimIndent(),
      processorOptions = options,
    )

    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")
    assertTrue(result.kspSucceeded, "expected KSP to succeed; got: ${result.kspErrors}")
    val kotlin: String = result.generated
    val cs: String = result.generatedCSharp
    assertContains(kotlin, "mishapsink_bridge_create")
    assertContains(kotlin, "NugetHandles.retain(buildError(mishap, ::nugetMappedType))")
    assertContains(kotlin, "mishap?.let { NugetHandles.retain(buildError(it, ::nugetMappedType)) }")
    assertContains(kotlin, "NugetManagedException(")
    assertContains(cs, "= NugetErrorNative.BuildException(arg0);")
    assertContains(cs, "arg0 == IntPtr.Zero ? null : NugetErrorNative.BuildException(arg0);")
    assertContains(cs, "NugetMarshal.WrapString((result.GetType().FullName")
    assertFalse("strictsink_bridge_create" in kotlin, "expected no StrictSink factory")
    assertTrue(
      result.kspWarnings.any {
        it.contains("StrictSink.last") && it.contains("IllegalStateException") &&
            it.contains("RuntimeException")
      },
      "expected a named refusal for StrictSink.last; kspWarnings=${result.kspWarnings}",
    )
    Tier1CSharpCompile.assertCompiles(
      result,
      """
      using Interop.Throwablebridge;
      public sealed class Sink : IMishapSink
      {
          public System.Exception? Seen;
          public void Accept(System.Exception mishap) => Seen = mishap;
          public bool Maybe(System.Exception? mishap) => mishap is not null;
          public System.Exception? Last() => new System.TimeoutException("late");
          public System.Exception Worst() => new System.Exception("fever");
          public System.Exception? Latest => null;
          public void Note(string? text) { }
          public void Dispose() { }
      }
      """.trimIndent(),
      allowUnsafe = true,
    )
  }
}
