package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every `...Async` wrapper allocates a `GCHandle` on its completion closure, and only the
 * completion callback frees it. A non-null `String` argument's null guard is evaluated inline in
 * the native call's argument list, AFTER that allocation, so a wrapper that throws there never
 * hands the handle to Kotlin and no completion ever arrives to free it. ADR-210 added the catch
 * that frees it, but only to members that take a suspend lambda; this pins it on every suspend
 * call-site variant `renderAsyncMethod` renders, none of which takes one here.
 *
 * `LeakTests/CollectabilityTests.cs` (`SuspendCompletion_*`) proves the behaviour on the instance
 * and top-level routes. This pins the text on the routes a running test does not reach.
 *
 * Mylo runs the stamp desk; Oreo's parcel is the one that never gets a label.
 */
class Tier1SuspendCompletionHandleFreeTest {

  private val fixture: String = """
    package tier1.stamps

    import kotlinx.coroutines.flow.Flow
    import kotlinx.coroutines.flow.MutableSharedFlow
    import kotlinx.coroutines.flow.MutableStateFlow
    import kotlinx.coroutines.flow.StateFlow
    import kotlinx.coroutines.flow.flowOf

    class StampDesk(val clerk: String) {
      suspend fun stampLater(text: String): String = clerk + " stamped " + text + " later"
      suspend fun watch(text: String): StateFlow<String> = MutableStateFlow(text)
      suspend fun trail(text: String): Flow<String> = flowOf(text)

      // The four suspend-lambda holders (`KotlinSuspendFunc`/`KotlinSuspendAction`, with and
      // without a boxed argument) and `MutableSharedFlow.EmitAsync`.
      val onStamp: suspend () -> String = { clerk }
      val onInk: suspend (Int) -> String = { count -> clerk + count }
      val onDry: suspend () -> Unit = { }
      val onPress: suspend (Int) -> Unit = { }
      val bell: MutableSharedFlow<String> = MutableSharedFlow()
    }

    sealed class Parcel {
      class Boxed(val size: Int) : Parcel() {
        suspend fun label(text: String): String = text + size
      }

      data object Missing : Parcel()
    }

    interface Courier {
      suspend fun deliver(text: String): String
    }

    class Mylo : Courier {
      override suspend fun deliver(text: String): String = text
    }

    suspend fun fetchGreeting(name: String): String = "Hello, " + name + "!"
  """.trimIndent()

  private val allocation = "callbackHandle = GCHandle.Alloc(callback);"
  private val free = "if (callbackHandle.IsAllocated) callbackHandle.Free();"

  /** Each suspend call site: the text from its handle allocation to its `return tcs.Task;`. */
  private fun callSites(csharp: String): List<String> =
    csharp.split(allocation).drop(1).map { tail -> tail.substringBefore("return tcs.Task;") }

  private fun run(): Tier1Result = Tier1Harness.run(
    fixture,
    fileName = "StampSample.kt",
    libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
  )

  /**
   * The invariant on every site at once: C# owns the completion handle from its allocation until
   * the native call returns. Each native call below sits in a `try` whose `catch` frees the handle
   * and rethrows, whether the allocation precedes the `try` or follows the boxes inside it.
   */
  @Test
  fun `every completion handle allocation is freed when its native call throws`() {
    val sites: List<String> = callSites(run().generatedCSharp)

    listOf(
      "StampLaterAsync", "FetchGreetingAsync",
      "NugetSuspendFuncNative.Invoke0(", "NugetSuspendFuncNative.Invoke1(",
      "NugetScopeNative.Drain(", "= _startEmit(",
    ).forEach { call ->
      assertTrue(sites.any { site -> site.contains(call) }, "no call site for `$call` in:\n$sites")
    }
    // Both holders at each arity: `KotlinSuspendFunc` and `KotlinSuspendAction`.
    assertEquals(2, sites.count { site -> site.contains("NugetSuspendFuncNative.Invoke0(") })
    assertEquals(2, sites.count { site -> site.contains("NugetSuspendFuncNative.Invoke1(") })

    sites.forEach { site ->
      val lines: List<String> = site.lines().map { line -> line.trim() }
      val freed: Int = lines.indexOf(free)
      assertTrue(freed >= 0, "a completion handle with no freeing catch:\n$site")
      assertEquals(listOf("}", "catch", "{"), lines.subList(freed - 3, freed), site)
      assertEquals("throw;", lines[freed + 1], site)
      assertEquals(1, lines.count { line -> line == free }, site)
      // The native call, which hands the handle over, is inside the block that catch closes. Its
      // `try` opens after the allocation, or before it where the boxes come first (ADR-171).
      val call: Int = lines.indexOfFirst { line -> line.contains("GCHandle.ToIntPtr(callbackHandle)") }
      assertTrue(call in 0 until freed - 3, site)
      val opened: Int = lines.subList(0, call).count { line -> line == "{" }
      val closed: Int = lines.subList(0, call).count { line -> line == "}" }
      val boxedFirst: Boolean = site.contains("boxedArg0")
      assertEquals(if (boxedFirst) 0 else 1, opened - closed, site)
    }
  }

  @Test
  fun `a suspend call site with a null-guarded argument frees its completion handle`() {
    val result: Tier1Result = run()
    val guarded: List<String> = callSites(result.generatedCSharp)
      .filter { site -> site.contains("?? throw new ArgumentNullException(nameof(") }

    // Instance, StateFlow-returning, Flow-returning, sealed arm, interface (the bridge proxy and
    // the implementing class), top-level.
    val natives: List<String> = guarded.map { site ->
      site.lines().first { line -> line.contains("?? throw new ArgumentNullException") }
        .trim().substringAfter("= ").substringBefore("(")
    }
    listOf("StampLaterAsync", "WatchAsync", "TrailAsync", "LabelAsync", "DeliverAsync", "FetchGreetingAsync")
      .forEach { member ->
        assertTrue(natives.any { native -> native.contains(member) }, "$member missing from $natives")
      }

    guarded.forEach { site ->
      val lines: List<String> = site.lines().map { line -> line.trim() }
      val guard: Int = lines.indexOfFirst { line -> line.contains("?? throw new ArgumentNullException") }
      // The guard is the native call's own argument, inside the `try`.
      assertEquals("try", lines.subList(0, guard).last { line -> line == "try" || line == "}" }, site)
      val freed: Int = lines.indexOf(free)
      assertTrue(freed > guard, site)
      assertEquals(listOf("}", "catch", "{"), lines.subList(freed - 3, freed), site)
      assertEquals("throw;", lines[freed + 1], site)
      // ...and the handle is freed before the wrapper would have published the job.
      val registration: Int = lines.indexOfFirst { line -> line.startsWith("CancellationTokenRegistration ") }
      assertTrue(registration > freed, site)
    }
  }
}
