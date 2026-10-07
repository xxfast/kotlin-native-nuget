package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ADR-161 residual: a C# callback that throws `OperationCanceledException` reaches Kotlin as
 * `CancellationException` (cause `NugetManagedException`). When that escapes a forward export
 * uncaught, the shared `BuildException` now consults the cause chain the Kotlin side carried and
 * hands the C# caller the original .NET cancellation exception (the stashed instance when it is
 * still there, otherwise one rebuilt from the carried type name) instead of a
 * `KotlinOperationCanceledException`. No envelope `kind` and no `nuget_*` export is added.
 *
 * Oreo cancels bath time. Mylo gets told exactly why.
 */
class Tier1CancelledCallbackOriginalTest {

  private val fixture: String = """
    package tier1.cancel

    class Bath {
      fun describeWith(format: (String) -> String): String = format("Oreo")
    }
  """.trimIndent()

  private fun run(): Tier1Result = Tier1Harness.run(
    fixture,
    fileName = "Bath.kt",
    processorOptions = mapOf("nuget.rootPackage" to "tier1"),
  )

  private val cs: String by lazy { run().generatedCSharp }

  private val buildException: String by lazy {
    cs.substringAfter("internal static Exception BuildException(").substringBefore("\n        }")
  }

  @Test
  fun `the cancellation check reads the carried cause chain before the error is disposed`() {
    assertContains(
      cs,
      "private const string CancellationType =\n" +
        "            \"kotlin.coroutines.cancellation.CancellationException\";",
    )
    val detect: Int = buildException.indexOf(
      "bool cancelledCallback = causeCount >= 2 && kotlinType == CancellationType",
    )
    val cause: Int = buildException.indexOf("&& CauseType(errorPtr, 1) == ManagedExceptionType")
    val message: Int = buildException.indexOf("&& CauseMessage(errorPtr, 1) == msg;")
    val dispose: Int = buildException.indexOf("NugetMarshal.Dispose(errorPtr);")
    assertTrue(detect in 0 until cause, "detection reads cause 1; body=$buildException")
    assertTrue(message in cause until dispose, "every read precedes the dispose; body=$buildException")
  }

  @Test
  fun `a cancelled callback takes the cancellation path and everything else the managed path`() {
    assertContains(
      buildException,
      "Exception? original = cancelledCallback\n" +
        "                ? TakeOriginalCancellation(msg)\n" +
        "                : TakeOriginalManagedFault(kotlinType, msg);",
    )
    // The ordinary mapping stays the fallback for a Kotlin-originated cancellation.
    assertContains(buildException, "KotlinException.CreateMapped(kotlinType, mappedType, msg")
  }

  @Test
  fun `the cancellation path prefers a stashed cancellation and rebuilds only the framework types`() {
    val take: String = cs.substringAfter("private static Exception? TakeOriginalCancellation(")
      .substringBefore("\n        }")
    val stash: Int = take.indexOf(
      "if (fault != null) return fault is OperationCanceledException ? fault : null;",
    )
    val rebuild: Int = take.indexOf("new System.Threading.Tasks.TaskCanceledException(managedMessage)")
    assertTrue(stash in 0 until rebuild, "the instance is tried first; body=$take")
    assertContains(take, "\"System.OperationCanceledException\" => new OperationCanceledException(managedMessage),")
    // A type the bridge cannot name keeps the ordinary mapping rather than an invented type.
    assertContains(take, "_ => null,")
    assertEquals(1, Regex("private static Exception\\? TakeOriginalCancellation\\(").findAll(cs).count())
  }

  @Test
  fun `the cancellation path compiles against the shared contract`() {
    Tier1CSharpCompile.assertCompiles(
      run(),
      allowUnsafe = true,
      consumerSource = """
      namespace Consumer
      {
          public static class Probe
          {
              public static void Touch() { }
          }
      }
      """.trimIndent(),
    )
  }
}
