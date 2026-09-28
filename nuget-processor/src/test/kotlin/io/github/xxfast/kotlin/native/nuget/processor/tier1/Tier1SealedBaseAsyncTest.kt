package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ADR-175: a sealed base (the ADR-112 abstract class for an eligible `sealed interface`, the
 * ADR-009 abstract class for a `sealed class`) projects its own `suspend` / `Flow` / `StateFlow`
 * members and owns the scope, so a C# caller holding the base-typed reference can await them
 * without downcasting to an arm.
 *
 * Today the base declares none of them: `Shape.area`, `Shape.ticks` and `Shape.fallback` are named
 * `SKIPPED_UNSUPPORTED_COMBINATION` (`SEALED_BASE_UNROUTED`), `Shape.level` is dropped in silence,
 * and every arm re-projects its own override under its own prefix with its own scope. The cells
 * below assert the end state: base exports and base C# members present, arm re-projections of base
 * members absent (ADR-159 rule 4), one scope on the base, and no skip line for an admitted member.
 *
 * Every arm kind is in one hierarchy on purpose, because the enum arm is the one a design with an
 * abstract member and arm overrides cannot serve (CS0534 on `CurlArm`): nested `data class` and
 * `data object` arms, a sibling arm declared beside the interface (ADR-125), and an enum arm
 * (ADR-157). [Shape.knead] is the arm-declared member the base does not declare, which must stay on
 * the arm and use the inherited scope. The `sealed class` half is `Job`, whose `rest` is a default
 * body no arm overrides and whose `Running.pause` is an arm-only suspend member.
 *
 * `libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore)` is load-bearing: without `Flow` and
 * `StateFlow` on the KSP path every member here is an unsupported type and each absence assertion
 * passes vacuously.
 *
 * Assertions are on globally unique strings (export names, occurrence counts) rather than on sliced
 * class blocks, because the nested arms live inside the base's braces and a first-closing-brace
 * slice would stop at the first arm. `compiledClean` is the generated **Kotlin** only; the C# is
 * asserted as text and compiled for real by `IntegrationTests/SealedBaseAsyncTests.cs`.
 *
 * Oreo curls into a loaf; Mylo sprawls across the whole sofa.
 */
class Tier1SealedBaseAsyncTest {

  private val fixture: String = """
    package tier1.sealedbaseasync

    import kotlinx.coroutines.awaitCancellation
    import kotlinx.coroutines.flow.Flow
    import kotlinx.coroutines.flow.MutableStateFlow
    import kotlinx.coroutines.flow.StateFlow
    import kotlinx.coroutines.flow.flowOf

    sealed interface Shape {
      suspend fun area(): Int
      fun ticks(): Flow<Int>
      val level: StateFlow<Int>
      suspend fun fallback(): Int = 9
      suspend fun doze(): Int = awaitCancellation()

      data class Loaf(val width: Int) : Shape {
        private val _level: MutableStateFlow<Int> = MutableStateFlow(width)
        override suspend fun area(): Int = width * 3
        override fun ticks(): Flow<Int> = flowOf(width, width + 1)
        override val level: StateFlow<Int> get() = _level
        suspend fun knead(times: Int): Int = width * times + 1
      }

      data object Donut : Shape {
        private val _level: MutableStateFlow<Int> = MutableStateFlow(11)
        override suspend fun area(): Int = 11
        override fun ticks(): Flow<Int> = flowOf(11)
        override val level: StateFlow<Int> get() = _level
      }
    }

    data class Sprawl(val length: Int) : Shape {
      private val _level: MutableStateFlow<Int> = MutableStateFlow(length)
      override suspend fun area(): Int = length * 10
      override fun ticks(): Flow<Int> = flowOf(length, length * 2)
      override val level: StateFlow<Int> get() = _level
    }

    enum class Curl : Shape {
      TIGHT, LOOSE;
      override suspend fun area(): Int = 100 + ordinal
      override fun ticks(): Flow<Int> = flowOf(100 + ordinal)
      override val level: StateFlow<Int> get() = MutableStateFlow(100 + ordinal)
    }

    fun loafShape(width: Int): Shape = Shape.Loaf(width)
    fun curlShape(loose: Boolean): Shape = if (loose) Curl.LOOSE else Curl.TIGHT

    sealed class Job {
      open suspend fun rest(): Int = 0
      data class Running(val progress: Int) : Job() {
        suspend fun pause(): Int = progress
      }
      data class Done(val code: Int) : Job()
    }

    fun anyJob(progress: Int): Job = Job.Running(progress)
  """.trimIndent()

  private val result: Tier1Result by lazy {
    Tier1Harness.run(
      fixture,
      fileName = "ShapeSample.kt",
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )
  }

  private fun String.occurrences(needle: String): Int =
    windowed(needle.length).count { it == needle }

  /** The base list of `public abstract class <name> :`, in whatever order the renderer emits it. */
  private fun String.baseListOf(name: String): String =
    lines().firstOrNull { it.trim().startsWith("public abstract class $name :") }.orEmpty()

  @Test
  fun `the fixture generates without a processor error`() {
    assertTrue(
      result.kspSucceeded,
      "expected KSP to succeed; got: ${result.kspExitCode} ${result.kspErrors}",
    )
    assertTrue(
      result.compiledClean,
      "expected the generated Kotlin to compile; got: ${result.compileErrors}",
    )
  }

  /**
   * The Kotlin half: the base exports each async member under its **own** prefix, receiver
   * `asStableRef<Shape>()`, so dispatch reaches the arm override, the default body and the enum
   * entry alike. No arm re-exports an override of a base-projected member.
   */
  @Test
  fun `the sealed base exports its async members and the arms do not re-export them`() {
    val generated: String = result.generated

    val missing: List<String> = listOf(
      "library_tier1_sealedbaseasync__shape_area_async",
      "library_tier1_sealedbaseasync__shape_fallback_async",
      "library_tier1_sealedbaseasync__shape_doze_async",
      "library_tier1_sealedbaseasync__shape_ticks_collect",
      "library_tier1_sealedbaseasync__shape_get_level_collect",
      "library_tier1_sealedbaseasync__shape_get_level_value",
      "library_tier1_sealedbaseasync__job_rest_async",
      // Arm-declared members the base does not declare stay on the arm (ADR-118 unchanged).
      "library_tier1_sealedbaseasync__shape_loaf_knead_async",
      "library_tier1_sealedbaseasync__job_running_pause_async",
    ).filterNot { generated.contains("@CName(\"$it\")") }

    assertTrue(missing.isEmpty(), "expected the base-prefixed async exports; missing: $missing")

    val leaked: List<String> = listOf(
      "shape_loaf_area_async",
      "shape_loaf_ticks_collect",
      "shape_loaf_get_level_collect",
      "shape_donut_area_async",
      "shape_donut_get_level_value",
      "sprawl_area_async",
      "shape_sprawl_area_async",
      "sprawl_get_level_value",
      "shape_sprawl_get_level_value",
      "job_running_rest_async",
      "job_done_rest_async",
    ).filter { generated.contains("__$it\")") }

    assertTrue(leaked.isEmpty(), "expected no arm re-export of a base member; got: $leaked")
  }

  /**
   * The C# half, as text. Each base member is declared exactly once in the assembly (on the base),
   * so an arm that still re-projects it shows up as a second declaration, which in the consumer is
   * CS0108 at best.
   */
  @Test
  fun `the sealed base declares each async member once and owns the scope`() {
    val cs: String = result.generatedCSharp

    assertTrue(
      cs.baseListOf("Shape").contains("IAsyncDisposable"),
      "expected the base to be the scope owner; got: " +
          cs.lines().filter { it.contains("class Shape") || it.contains("class Job") },
    )
    assertTrue(
      cs.baseListOf("Job").contains("IAsyncDisposable"),
      "expected the sealed-class base to be the scope owner too",
    )

    val once: Map<String, Int> = listOf(
      "public Task<int> AreaAsync(CancellationToken cancellationToken = default)",
      "public Task<int> FallbackAsync(CancellationToken cancellationToken = default)",
      "public Task<int> DozeAsync(CancellationToken cancellationToken = default)",
      "public KotlinFlow<int> Ticks()",
      "public KotlinStateFlow<int> Level",
      "public Task<int> RestAsync(CancellationToken cancellationToken = default)",
      "public Task<int> KneadAsync(int times, CancellationToken cancellationToken = default)",
      "public Task<int> PauseAsync(CancellationToken cancellationToken = default)",
    ).associateWith { cs.occurrences(it) }.filterValues { it != 1 }

    assertTrue(once.isEmpty(), "expected each async member declared exactly once; got: $once")

    // One scope owner per hierarchy: the base. Arms keep no `_scopeHandle` of their own.
    assertEquals(
      2,
      cs.occurrences("internal IntPtr _scopeHandle;"),
      "expected exactly two scope fields (Shape and Job); got: " +
          cs.lines().filter { it.contains("_scopeHandle;") || it.contains("_scopeHandle =") },
    )
    assertTrue(
      cs.contains("public abstract ValueTask DisposeAsync()"),
      "expected the abstract owner to declare DisposeAsync abstract (ADR-159)",
    )
    assertTrue(
      cs.contains("EntryPoint = \"library_tier1_sealedbaseasync__shape_area_async\""),
      "expected the base AreaAsync to import the base export",
    )
  }

  /**
   * The diagnostic half: an admitted async member on a sealed base is no longer named
   * `SEALED_BASE_UNROUTED`, and the `StateFlow` property is not dropped in silence (bound, so it
   * appears in no skip line at all).
   */
  @Test
  fun `no admitted base async member is named as unrouted or dropped`() {
    val named: List<String> = result.kspWarnings.filter { warning ->
      listOf("Shape.area", "Shape.ticks", "Shape.level", "Shape.fallback", "Shape.doze", "Job.rest")
        .any { member -> warning.contains(member) }
    }

    assertTrue(named.isEmpty(), "expected no skip for an admitted base async member; got: $named")
    assertTrue(
      result.kspWarnings.none { it.contains("sealed base class, which has no route yet") },
      "expected SEALED_BASE_UNROUTED to stop firing; got: ${result.kspWarnings}",
    )
  }

  /**
   * ADR-175 inferred claim: `forwardScopeOwner`'s walk from an ordinary class below an `open` arm
   * reaches the sealed base, so the one scope stays on the base and the subclass only overrides
   * the abstract owner's `DisposeAsync` (as the arm does), never re-declaring the scope.
   */
  @Test
  fun `a class below an open arm inherits the sealed base's scope`() {
    val nested: Tier1Result = Tier1Harness.run(
      """
      package tier1.sealedbaseopenarm

      sealed class Job {
        open suspend fun rest(): Int = 0
        open class Running(val progress: Int) : Job()
      }

      class Sub(progress: Int) : Job.Running(progress)

      fun anyJob(progress: Int): Job = Sub(progress)
      """.trimIndent(),
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )
    assertTrue(nested.compiledClean, "got: ${nested.compileErrors} ${nested.kspErrors}")
    val cs: String = nested.generatedCSharp
    assertEquals(1, cs.occurrences("internal IntPtr _scopeHandle;"), cs)
    assertTrue(cs.baseListOf("Job").contains("IAsyncDisposable"), cs)
    assertTrue(
      cs.lines().none { it.contains("class Sub :") && it.contains("IAsyncDisposable") },
      "expected Sub to inherit IAsyncDisposable, not re-list it; got: $cs",
    )
    assertEquals(2, cs.occurrences("public override ValueTask DisposeAsync()"), cs)
  }
}
