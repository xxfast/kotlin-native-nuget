package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-201: `Throwable` beyond the ADR-107 property getter. A sync return, a `List` element or
 * `Map` value, a module-local unexported subclass, and a parameter or setter declared
 * `Throwable`, `Exception` or `RuntimeException` all bind; a narrower declared input type, a
 * `List<Throwable>` input, a `Set` element and a `Map` key skip named.
 *
 * Out of Kotlin the value is the ADR-107 envelope, rebuilt by `NugetErrorNative.BuildException`;
 * into Kotlin it is one `"{FullName}: {Message}"` string Kotlin turns into the ADR-161
 * `NugetManagedException`.
 *
 * Mochi coughs up a hairball at 3am; Oreo reports it.
 */
class Tier1ThrowablePositionsTest {

  private val options: Map<String, String> = mapOf("nuget.rootPackage" to "tier1")

  /**
   * The classifier's supertype walk no longer stops at klib origin, so it must still leave every
   * exported exception class shape (open, abstract, final; Kotlin forbids a generic `Throwable`
   * subclass) on its handle-class binding: the `exportedObjectHandles` guard is what keeps
   * ADR-107 decision 3.
   */
  @Test
  fun `exported exception classes of every shape keep their handle binding`() {
    val result: Tier1Result = Tier1Harness.run(
      """
      package tier1.throwableexported

      open class OpenError(message: String) : Exception(message)
      abstract class AbstractError(message: String) : Exception(message)
      class FinalError(message: String) : IllegalStateException(message)

      class Vet {
        fun open(): OpenError = OpenError("ear mites")
        fun abstractOne(): AbstractError? = null
        fun final(): FinalError = FinalError("hairball")
      }
      """.trimIndent(),
      processorOptions = options,
    )

    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")
    val cs: String = result.generatedCSharp
    assertContains(cs, "OpenError Open()")
    assertContains(cs, "AbstractError? AbstractOne()")
    assertContains(cs, "FinalError Final()")
    assertFalse(
      Regex("""System\.Exception\?? (Open|AbstractOne|Final)\(""").containsMatchIn(cs),
      "an exported exception class must not rebind as System.Exception; generated=$cs",
    )
  }

  /** A sync result: member, top-level, nullable and a stdlib subtype, all through the envelope. */
  @Test
  fun `a sync Throwable result binds as System Exception`() {
    val result: Tier1Result = Tier1Harness.run(
      """
      package tier1.throwablereturn

      class MishapLog {
        fun latest(): Throwable? = null
        fun worst(): Throwable = IllegalArgumentException("Oreo ate the plant")
        fun narrowed(): IllegalStateException = IllegalStateException("bowl is empty")
      }

      fun lastMishap(): Exception? = null
      """.trimIndent(),
      processorOptions = options,
    )

    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")
    assertTrue(result.kspSucceeded, "expected KSP to succeed; got: ${result.kspErrors}")
    val kotlin: String = result.generated
    val cs: String = result.generatedCSharp
    assertContains(
      kotlin,
      "buildError(handle.asStableRef<tier1.throwablereturn.MishapLog>().get().worst(), " +
          "::nugetMappedType)",
    )
    assertContains(kotlin, "?.let { buildError(it, ::nugetMappedType) }")
    assertContains(cs, "public global::System.Exception? Latest()")
    assertContains(cs, "public global::System.Exception Worst()")
    assertContains(cs, "public global::System.Exception Narrowed()")
    assertContains(cs, "public static global::System.Exception? LastMishap()")
    assertContains(cs, "NugetErrorNative.BuildException(nativeResult)")
    Tier1CSharpCompile.assertCompiles(
      result,
      """
      using Interop.Throwablereturn;
      public static class Consumer
      {
          public static string Read(MishapLog log) =>
              log.Worst().Message + (log.Latest()?.Message ?? "") + log.Narrowed().Message;
      }
      """.trimIndent(),
    )
  }

  /**
   * `List<Throwable>` at a getter and a result, a nullable element, and a `Map` value: each
   * element is boxed as its own envelope and read back by `BuildException`, which disposes it.
   */
  @Test
  fun `List and Map value components typed Throwable bind as System Exception`() {
    val result: Tier1Result = Tier1Harness.run(
      """
      package tier1.throwablelist

      class MishapLog(val all: List<Throwable>) {
        fun recent(count: Int): List<Throwable?> = all.takeLast(count)
        fun byCat(): Map<String, Exception> = emptyMap()
      }
      """.trimIndent(),
      processorOptions = options,
    )

    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")
    assertTrue(result.kspSucceeded, "expected KSP to succeed; got: ${result.kspErrors}")
    val cs: String = result.generatedCSharp
    assertContains(cs, "IReadOnlyList<global::System.Exception> All")
    assertContains(cs, "IReadOnlyList<global::System.Exception?> Recent(int count)")
    assertContains(cs, "IReadOnlyDictionary<string, global::System.Exception> ByCat()")
    assertContains(cs, "NugetErrorNative.BuildException(")
    assertContains(result.generated, "buildError(")
    Tier1CSharpCompile.assertCompiles(
      result,
      """
      using Interop.Throwablelist;
      public static class Consumer
      {
          public static int Count(MishapLog log) =>
              log.All.Count + log.Recent(1).Count + log.ByCat().Count;
      }
      """.trimIndent(),
    )
  }

  /**
   * A public class in a package outside `includePackages` is module-local and unexported; typed
   * as a `Throwable` subclass it now binds as the envelope instead of skipping as undeclared.
   */
  @Test
  fun `a module-local unexported Throwable subclass binds as System Exception`() {
    val result: Tier1Result = Tier1Harness.run(
      mapOf(
        "Hairball.kt" to """
          package tier1.unlisted

          class HairballError(message: String) : IllegalStateException(message)
        """.trimIndent(),
        "Mishaps.kt" to """
          package tier1.mishaps

          import tier1.unlisted.HairballError

          class MishapLog {
            fun hairball(): HairballError = HairballError("Mochi, 3am")
            val last: HairballError? get() = null
          }
        """.trimIndent(),
      ),
      processorOptions = options + ("nuget.includePackages" to "tier1.mishaps"),
    )

    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")
    val cs: String = result.generatedCSharp
    assertContains(cs, "public global::System.Exception Hairball()")
    assertContains(cs, "public global::System.Exception? Last")
    assertFalse(
      Regex("""class HairballError\b""").containsMatchIn(cs),
      "the module-local class itself must stay undeclared; generated=$cs",
    )
  }

  /**
   * A parameter declared `Throwable`, `Exception` or `RuntimeException` takes any
   * `System.Exception`; Kotlin receives a `NugetManagedException`. A narrower declared type cannot
   * hold one, so it skips named with a hint saying which declarations bind.
   */
  @Test
  fun `a Throwable parameter binds for the three managed-assignable declared types only`() {
    val result: Tier1Result = Tier1Harness.run(
      """
      package tier1.throwableparameter

      class MishapLog {
        private val seen = mutableListOf<Throwable>()
        fun report(mishap: Throwable) { seen += mishap }
        fun reportOrSkip(mishap: Exception?): Boolean = mishap?.let { seen += it } != null
        fun reportRuntime(mishap: RuntimeException): Int = seen.size
        fun strict(error: IllegalStateException) {}
      }

      fun describe(mishap: Throwable): String = mishap.message ?: ""
      """.trimIndent(),
      processorOptions = options,
    )

    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")
    assertTrue(result.kspSucceeded, "expected KSP to succeed; got: ${result.kspErrors}")
    val kotlin: String = result.generated
    val cs: String = result.generatedCSharp
    assertContains(cs, "public void Report(global::System.Exception mishap)")
    assertContains(cs, "public bool ReportOrSkip(global::System.Exception? mishap)")
    assertContains(cs, "public int ReportRuntime(global::System.Exception mishap)")
    assertContains(cs, "public static string Describe(global::System.Exception mishap)")
    assertContains(kotlin, "NugetManagedException(")
    assertFalse(
      Regex("""\bvoid Strict\(""").containsMatchIn(cs),
      "a narrower declared type must not bind; generated=$cs",
    )
    assertFalse("mishaplog_strict" in kotlin, "expected no strict export; generated=$kotlin")
    assertTrue(
      result.kspWarnings.any { warning ->
        warning.contains("SKIPPED_") && warning.contains("strict") &&
            warning.contains("RuntimeException")
      },
      "expected a named skip for strict whose hint names the bindable declarations; " +
          "kspWarnings=${result.kspWarnings}",
    )
    // The reason line names the declared type, not the `THROWABLE` reason constant.
    val strict: String = result.kspWarnings.single { it.contains("MishapLog.strict") }
    assertContains(
      strict,
      "is declared `IllegalStateException`, which cannot hold the `NugetManagedException` a C# " +
          "exception arrives as",
    )
    assertFalse("THROWABLE type combination" in strict, "expected no reason constant; got: $strict")
    Tier1CSharpCompile.assertCompiles(
      result,
      """
      public static class Consumer
      {
          public static bool Send(Interop.Throwableparameter.MishapLog log)
          {
              log.Report(new System.InvalidOperationException("bowl is empty"));
              return log.ReportOrSkip(null);
          }
      }
      """.trimIndent(),
    )
  }

  /** ADR-107 decision 4 is superseded: a data class with `Throwable` constructor slots binds. */
  @Test
  fun `a data class with Throwable constructor parameters is constructible from C#`() {
    val result: Tier1Result = Tier1Harness.run(
      """
      package tier1.throwabledata

      data class Failure(val reason: String, val error: Throwable?, val fatal: Throwable)
      """.trimIndent(),
      processorOptions = options,
    )

    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")
    val cs: String = result.generatedCSharp
    assertContains(
      cs,
      "public Failure(string reason, global::System.Exception? error_, " +
        "global::System.Exception fatal)",
    )
    Tier1CSharpCompile.assertCompiles(
      result,
      """
      public static class Consumer
      {
          public static Interop.Throwabledata.Failure Make() =>
              new Interop.Throwabledata.Failure("vet", null, new System.Exception("fever"));
      }
      """.trimIndent(),
    )
  }

  /**
   * The `var` setter takes the same `System.Exception` wire when its declared type can hold a
   * `NugetManagedException`; a narrower one stays get-only with the named read-only diagnostic.
   */
  @Test
  fun `a var Throwable setter binds only for the managed-assignable declared types`() {
    val result: Tier1Result = Tier1Harness.run(
      """
      package tier1.throwablesetter

      class Failure(val reason: String) {
        var lastError: Throwable? = null
        var fatal: Exception = RuntimeException("none")
        var strict: IllegalStateException? = null
      }
      """.trimIndent(),
      processorOptions = options,
    )

    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")
    assertTrue(result.kspSucceeded, "expected KSP to succeed; got: ${result.kspErrors}")
    val kotlin: String = result.generated
    assertContains(kotlin, "failure_set_lastError")
    assertContains(kotlin, "failure_set_fatal")
    assertFalse("failure_set_strict" in kotlin, "expected no strict setter; generated=$kotlin")
    assertTrue(
      result.kspWarnings.any {
        it.contains(ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_INPUT.name) &&
            it.contains("strict") && it.contains("read-only")
      },
      "expected the read-only setter diagnostic for strict; kspWarnings=${result.kspWarnings}",
    )
    Tier1CSharpCompile.assertCompiles(
      result,
      """
      public static class Consumer
      {
          public static void Write(Interop.Throwablesetter.Failure failure)
          {
              failure.LastError = new System.ArgumentException("bad paw");
              failure.LastError = null;
              failure.Fatal = new System.Exception("fever");
              System.Exception? read = failure.Strict;
          }
      }
      """.trimIndent(),
    )
  }

  /**
   * The deferred positions stay named skips: a `List<Throwable>` input, a `Set` element and a
   * `Map` key (each crossing builds a fresh `System.Exception`, which compares by reference), and
   * an extension receiver (it would extend every `System.Exception` in C#).
   */
  @Test
  fun `Throwable input collections, Set elements, Map keys and receivers skip named`() {
    val result: Tier1Result = Tier1Harness.run(
      """
      package tier1.throwableskips

      class MishapLog {
        fun reportAll(errors: List<Throwable>) {}
        fun distinct(): Set<Throwable> = emptySet()
        fun byError(): Map<Throwable, String> = emptyMap()
        val unique: Set<Exception> get() = emptySet()
      }

      fun Throwable.describe(): String = message ?: ""
      """.trimIndent(),
      processorOptions = options,
    )

    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")
    assertTrue(result.kspSucceeded, "expected KSP to succeed; got: ${result.kspErrors}")
    val cs: String = result.generatedCSharp
    for (member in listOf("ReportAll(", "Distinct(", "ByError(", " Unique", "Describe(")) {
      assertFalse(
        Regex("""public [^\n]*\b${Regex.escape(member)}""").containsMatchIn(cs),
        "expected $member to be absent; generated=$cs",
      )
    }
    for (member in listOf("reportAll", "distinct", "byError", "unique", "describe")) {
      assertTrue(
        result.kspWarnings.any { it.contains("SKIPPED_") && it.contains(member) },
        "expected a named skip for $member; kspWarnings=${result.kspWarnings}",
      )
    }
    assertTrue(
      result.kspWarnings.any {
        it.contains("describe") && it.contains("its extension receiver is declared `Throwable`")
      },
      "expected the receiver skip to name the declared type; kspWarnings=${result.kspWarnings}",
    )
  }

  /**
   * An exported abstract class whose abstract members take or return `Throwable`, in both owner
   * shapes: with a backing wrapper (returned by `Kennel.sink()`) and without one (`Bowl`, reached
   * only through its concrete subclass, so the `refusedAbstract` walk is what decides). Either
   * way C# declares exactly what can be overridden, or the generated C# fails with CS0534; the
   * narrower `IllegalStateException` parameter is a named skip on both sides.
   */
  @Test
  fun `abstract members typed Throwable declare only what the wrapper overrides`() {
    val result: Tier1Result = Tier1Harness.run(
      """
      package tier1.throwableabstract

      abstract class Sink {
        abstract fun cause(): Throwable?
        abstract fun accept(mishap: Throwable)
        abstract fun strict(error: IllegalStateException)
      }

      class ConsoleSink : Sink() {
        override fun cause(): Throwable? = null
        override fun accept(mishap: Throwable) {}
        override fun strict(error: IllegalStateException) {}
      }

      abstract class Bowl {
        abstract fun spill(): Exception
        abstract fun strict(error: IllegalStateException)
      }

      class WaterBowl : Bowl() {
        override fun spill(): Exception = RuntimeException("wet floor")
        override fun strict(error: IllegalStateException) {}
      }

      class Kennel {
        fun sink(): Sink = ConsoleSink()
      }
      """.trimIndent(),
      processorOptions = options,
    )

    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")
    val cs: String = result.generatedCSharp
    assertContains(cs, "public abstract global::System.Exception? Cause()")
    assertContains(cs, "public abstract void Accept(global::System.Exception mishap)")
    assertContains(cs, "public abstract global::System.Exception Spill()")
    assertFalse(
      Regex("""void Strict\(""").containsMatchIn(cs),
      "the narrower parameter must stay off C#; generated=$cs",
    )
    Tier1CSharpCompile.assertCompiles(result, "public static class Consumer { }")
  }

  /**
   * ADR-115 keeps winning: an opt-in-marked module-local exception is never declared, and the
   * marker gate is what names it, so it still skips rather than binding as `System.Exception`.
   */
  @Test
  fun `an opt-in marked module-local exception still skips under the marker`() {
    val result: Tier1Result = Tier1Harness.run(
      mapOf(
        "Hairball.kt" to """
          package tier1.unlisted

          @RequiresOptIn
          annotation class Experimental

          @Experimental
          class HairballError(message: String) : IllegalStateException(message)
        """.trimIndent(),
        "Mishaps.kt" to """
          package tier1.mishaps

          import tier1.unlisted.Experimental
          import tier1.unlisted.HairballError

          class MishapLog {
            @OptIn(Experimental::class)
            fun hairball(): HairballError = HairballError("Mochi, 3am")
          }
        """.trimIndent(),
      ),
      processorOptions = options + ("nuget.includePackages" to "tier1.mishaps"),
    )

    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")
    assertFalse(
      Regex("""\bHairball\(""").containsMatchIn(result.generatedCSharp),
      "a marked exception must not bind; generated=${result.generatedCSharp}",
    )
    assertTrue(
      result.kspWarnings.any {
        it.contains(ForwardDiagnosticKind.SKIPPED_OPT_IN_MARKER.name) && it.contains("hairball")
      },
      "expected the opt-in marker skip for hairball; kspWarnings=${result.kspWarnings}",
    )
  }

  /**
   * The legacy suspend and Flow routes: a `List<Throwable>` result rides the shared collection
   * projection both halves already use; a bare `Throwable` result, `Flow`/`StateFlow` element or
   * suspend parameter has no legacy-route arm and skips named. The bare suspend result used to
   * render `Task<Throwable?>` over a C# type nothing declares, and the bare `Flow` element crashed
   * the processor in the user-type speller.
   */
  @Test
  fun `suspend and Flow Throwable positions bind as lists and skip bare`() {
    val result: Tier1Result = Tier1Harness.run(
      """
      package tier1.throwableasync

      import kotlinx.coroutines.flow.Flow
      import kotlinx.coroutines.flow.MutableStateFlow
      import kotlinx.coroutines.flow.StateFlow
      import kotlinx.coroutines.flow.flowOf

      class MishapLog {
        suspend fun all(): List<Throwable> = listOf(IllegalStateException("bowl"))
        fun stream(): Flow<List<Throwable>> = flowOf(emptyList())
        suspend fun latest(): Throwable? = null
        fun each(): Flow<Throwable> = flowOf()
        val state: StateFlow<Throwable?> = MutableStateFlow(null)
        suspend fun report(mishap: Throwable): Int = 1
      }
      """.trimIndent(),
      processorOptions = options,
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )

    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")
    assertTrue(result.kspSucceeded, "expected KSP to succeed; got: ${result.kspErrors}")
    val cs: String = result.generatedCSharp
    assertContains(cs, "Task<IReadOnlyList<global::System.Exception>> AllAsync(")
    assertContains(cs, "IReadOnlyList<global::System.Exception>")
    for (member in listOf("LatestAsync(", "Each(", " State", "ReportAsync(")) {
      assertFalse(
        Regex("""public [^\n]*\b${Regex.escape(member)}""").containsMatchIn(cs),
        "expected $member to be absent; generated=$cs",
      )
    }
    for (member in listOf("latest", "each", "state", "report")) {
      assertTrue(
        result.kspWarnings.any { it.contains("SKIPPED_") && it.contains(member) },
        "expected a named skip for $member; kspWarnings=${result.kspWarnings}",
      )
    }
    Tier1CSharpCompile.assertCompiles(
      result, "public static class Consumer { }", allowUnsafe = true,
    )
  }

  /**
   * The other owners of an ordinary sync callable: an interface, an object, a companion, a sealed
   * arm, an enum, a generic class, a class extension, a `Result<Throwable>` and a value class's own
   * members. The value class has no error slot (ADR-014), so its `Throwable?` result is a named
   * skip while the non-null result, getter and parameter bind.
   */
  @Test
  fun `every ordinary owner binds Throwable results and inputs`() {
    val result: Tier1Result = Tier1Harness.run(
      """
      package tier1.throwableowners

      interface Sink {
        fun cause(): Throwable?
        fun accept(mishap: Exception)
      }

      class ConsoleSink : Sink {
        override fun cause(): Throwable? = null
        override fun accept(mishap: Exception) {}
      }

      object Registry {
        fun latest(): Throwable? = null
        var current: Exception? = null
      }

      class Kennel {
        companion object {
          fun broken(): Exception = RuntimeException("the gate is open")
        }
        fun sink(): Sink = ConsoleSink()
        fun attempt(): Result<Throwable> = Result.success(IllegalStateException("bowl"))
      }

      sealed class Outcome {
        data class Failed(val mishap: Throwable) : Outcome() {
          fun retry(with: Exception): Outcome = Failed(with)
        }
        data object Fine : Outcome()
      }

      @JvmInline
      value class Tag(val name: String) {
        fun asError(): Throwable = IllegalStateException(name)
        fun maybe(): Throwable? = null
        val err: Exception get() = IllegalStateException(name)
      }

      enum class Mood {
        GRUMPY;
        fun why(): Throwable = IllegalStateException("no treats")
      }

      class Box<T>(val item: T) {
        fun reject(mishap: Throwable): T = item
      }

      fun Kennel.blame(mishap: Exception): String = mishap.message ?: ""
      fun outcomes(): List<Outcome> = emptyList()
      fun tag(): Tag = Tag("Oreo")
      fun box(): Box<String> = Box("Mylo")
      """.trimIndent(),
      processorOptions = options,
    )

    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")
    assertTrue(result.kspSucceeded, "expected KSP to succeed; got: ${result.kspErrors}")
    val cs: String = result.generatedCSharp
    assertContains(cs, "global::System.Exception? Cause()")
    assertContains(cs, "void Accept(global::System.Exception mishap)")
    assertContains(cs, "public static global::System.Exception? Latest()")
    assertContains(cs, "public static global::System.Exception Broken()")
    assertContains(cs, "public global::System.Exception Attempt()")
    assertContains(cs, "Retry(global::System.Exception with)")
    assertContains(
      cs, "public global::System.Exception AsError() => NugetErrorNative.BuildException(",
    )
    assertContains(cs, "public global::System.Exception Err => NugetErrorNative.BuildException(")
    assertContains(cs, "Why(this global::Interop.Throwableowners.Mood mood)")
    assertContains(cs, "public T Reject(global::System.Exception mishap)")
    assertContains(
      cs,
      "Blame(this global::Interop.Throwableowners.Kennel receiver, " +
          "global::System.Exception mishap)",
    )
    assertFalse(
      Regex("""\bMaybe\(""").containsMatchIn(cs), "expected Tag.maybe absent; generated=$cs",
    )
    assertTrue(
      result.kspWarnings.any { it.contains("SKIPPED_") && it.contains("Tag.maybe") },
      "expected a named skip for Tag.maybe; kspWarnings=${result.kspWarnings}",
    )
    Tier1CSharpCompile.assertCompiles(result, "public static class Consumer { }")
  }

  /**
   * A value class OVER `Throwable` stays deferred: its underlying is not an ordinary value-class
   * underlying, so every position typed with it skips named rather than reaching an emitter arm
   * that has no `Throwable` underlying.
   */
  @Test
  fun `a value class over Throwable skips named at every position`() {
    val result: Tier1Result = Tier1Harness.run(
      """
      package tier1.throwablewrapped

      @JvmInline
      value class Wrapped(val cause: Throwable)

      class Vet {
        fun wrapped(): Wrapped = Wrapped(IllegalStateException("fever"))
        fun maybe(): Wrapped? = null
        fun take(wrapped: Wrapped): Int = 1
        val last: Wrapped? get() = null
        fun all(): List<Wrapped> = emptyList()
      }
      """.trimIndent(),
      processorOptions = options,
    )

    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")
    assertTrue(result.kspSucceeded, "expected KSP to succeed; got: ${result.kspErrors}")
    val cs: String = result.generatedCSharp
    val absent: List<String> = listOf(
      """ Wrapped\(\)""", """ Maybe\(\)""", """ Take\(""", """ Last\r?$""", """ All\(\)""",
    )
    for (member in absent) {
      assertFalse(
        Regex("""public [^\n]*$member""", RegexOption.MULTILINE).containsMatchIn(cs),
        "expected $member to be absent; generated=$cs",
      )
    }
    for (member in listOf("wrapped", "maybe", "take", "last", "all")) {
      assertTrue(
        result.kspWarnings.any { it.contains("SKIPPED_") && it.contains("Vet.$member") },
        "expected a named skip for $member; kspWarnings=${result.kspWarnings}",
      )
    }
    Tier1CSharpCompile.assertCompiles(result, "public static class Consumer { }")
  }
}
