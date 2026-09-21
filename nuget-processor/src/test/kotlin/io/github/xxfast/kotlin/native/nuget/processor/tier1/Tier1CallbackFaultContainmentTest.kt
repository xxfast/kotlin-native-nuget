package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ADR-161, part A: a BRIDGE-INTERNAL failure while materialising a `Flow<T>` item or a suspend
 * result must fault the stream or the task, not end the host process.
 *
 * Both closures run inside an `[UnmanagedCallersOnly]` thunk whose catch-all is
 * `Environment.FailFast` (ADR-102), and both perform a materialisation the wire cannot guarantee:
 * `NugetMarshal.FromHandle<T>` has no branch for an enum element
 * (`docs/backlog/fromhandle-no-enum-branch.md`), so a `Flow<Mood>` is a reachable trigger today.
 * The consumer symptom was the whole `dotnet test` host aborting with
 * `nuget: unhandled exception in NugetFlowOnNext`.
 *
 * The cells assert the C# text (ADR-060 tier 1), because the failure is the ABSENCE of a `try`
 * around a call, which no signature-level assertion can see. The last cell is the one that matters
 * most: it counts, so a sixth completion-closure site added later without the containment fails
 * here rather than in a consumer's process.
 *
 * Oreo's moods do not materialise. Mylo's host process stays up anyway.
 */
class Tier1CallbackFaultContainmentTest {

  private val fixture: String = """
    package tier1.moods

    import kotlinx.coroutines.flow.Flow
    import kotlinx.coroutines.flow.flowOf

    enum class Mood { HAPPY, GRUMPY }

    class Cat(val name: String)

    class Shelter {
      // The reachable trigger: an enum element FromHandle<T> cannot materialise.
      fun moodStream(): Flow<Mood> = flowOf(Mood.HAPPY)

      // A scalar element, so the containment is not something only the enum route gets.
      fun names(): Flow<String> = flowOf("Oreo")

      // The suspend completion closure, object result: `new Cat(resultPtr)` is the materialisation.
      suspend fun adopt(): Cat = Cat("Oreo")

      // A suspend member also gives the class its drain closure (ADR-025 DisposeAsync).
      suspend fun rehome(): Unit = Unit
    }
  """.trimIndent()

  private fun run(): Tier1Result = Tier1Harness.run(
    fixture,
    fileName = "Shelter.kt",
    processorOptions = mapOf("nuget.rootPackage" to "tier1"),
    libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
  )

  /** The headline cell: a failed item read faults the channel and cancels the Kotlin collector. */
  @Test
  fun `a failed flow item read faults the channel and cancels the collector`() {
    val result = run()

    val missing: List<String> = listOf(
      "                    T value = _read(itemPtr);",
      "                    _channel.Writer.TryComplete(ex);",
      "                    _faulted = true;",
      "                    IntPtr job = Volatile.Read(ref _jobHandle);",
      "                    if (job != IntPtr.Zero) NugetJobNative.Cancel(job);",
    ).filterNot(result.generatedCSharp::contains)

    assertTrue(
      missing.isEmpty(),
      "expected the onNext item read to be contained and to cancel the collector; missing: " +
          "$missing; got: ${csharpLinesFor(result, "_read(itemPtr)")}",
    )
  }

  /**
   * The synchronous-first-emission window: `startCollect` has not returned, so the closure has no
   * job handle to cancel and the constructor has to do it.
   */
  @Test
  fun `a fault during startCollect is cancelled by the constructor`() {
    val result = run()

    assertTrue(
      result.generatedCSharp.contains(
        "if (_faulted && _jobHandle != IntPtr.Zero) NugetJobNative.Cancel(_jobHandle);"
      ),
      "expected the constructor to cancel a flow that faulted before _jobHandle was assigned; " +
          "got: ${csharpLinesFor(result, "_faulted")}",
    )
    assertTrue(
      result.generatedCSharp.contains("private volatile bool _faulted;"),
      "expected the flag the two parties share to be volatile: the onNext closure runs on a Kotlin " +
          "thread and the constructor on the caller's; got: ${csharpLinesFor(result, "_faulted;")}",
    )
  }

  /** `BuildException` is itself a wire read, so the error arm must still complete the channel. */
  @Test
  fun `a failed error materialisation still completes the channel`() {
    val result = run()

    assertTrue(
      result.generatedCSharp.contains(
        "                    _channel.Writer.TryComplete(NugetErrorNative.BuildException(errorPtr));"
      ),
      "expected the onError BuildException call to sit inside the containment; got: " +
          "${csharpLinesFor(result, "BuildException(errorPtr)")}",
    )
  }

  /** The suspend result materialisation faults the awaited task instead of the process. */
  @Test
  fun `a failed suspend result materialisation faults the task`() {
    val result = run()

    val missing: List<String> = listOf(
      "                        t.SetResult(new Cat(resultPtr));",
      "                catch (Exception ex)",
      "                    t.TrySetException(ex);",
    ).filterNot(result.generatedCSharp::contains)

    assertTrue(
      missing.isEmpty(),
      "expected the awaited result extraction to be contained and the task faulted; missing: " +
          "$missing; got: ${csharpLinesFor(result, "t.SetResult")}",
    )
  }

  /**
   * The miss-one guard, and the reason part A extracted one helper instead of editing six copies:
   * every completion closure begins `job.CompleteFromCallback();` and every contained one ends
   * `t.TrySetException(ex);`, so the two counts agreeing is the invariant.
   */
  @Test
  fun `every suspend completion closure is contained`() {
    val result = run()

    val closures: Int = occurrences(result.generatedCSharp, "job.CompleteFromCallback();")
    val contained: Int = occurrences(result.generatedCSharp, "t.TrySetException(ex);")

    assertTrue(closures >= 2, "expected the fixture to reach at least the async and drain closures")
    assertEquals(
      closures,
      contained,
      "expected every completion closure to contain its materialisation; a site rendered without " +
          "the containment fails the host process instead of the awaiter",
    )
  }

  private fun occurrences(text: String, needle: String): Int =
    text.split(needle).size - 1

  private fun csharpLinesFor(result: Tier1Result, needle: String): List<String> =
    result.generatedCSharp.lines().filter { it.contains(needle) }.map(String::trim)
}
