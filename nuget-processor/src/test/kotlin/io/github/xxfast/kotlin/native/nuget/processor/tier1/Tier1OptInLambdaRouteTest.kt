package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Issue #121. ADR-115's opt-in gate lives in the planners, and the lambda-property route does not
 * go through them. `ClassExports.kt:88` and its sealed twin ask the catalog for a plan and, when
 * there is none, fall through to a legacy arm that emits unconditionally:
 *
 * ```kotlin
 * val planned: ForwardPropertyPlan? = callableCatalog.propertyFor("$qualifiedName.$propName")
 * if (planned != null) { addForwardPropertyPlanExports(planned); return@forEach }
 * // legacy arm, emits a lambda or Flow getter with no gate of its own
 * ```
 *
 * `planned == null` conflates two opposite answers: "the planner has no shape for this type", which
 * is what the legacy arm exists for, and "the planner refused this declaration", which nothing may
 * emit. So a marked lambda property is reported `SKIPPED_OPT_IN_MARKER` and then exported anyway,
 * on both sides.
 *
 * That also explains why the gate looks correct everywhere else: for a marked *non-lambda* property
 * the fall-through reaches no arm and emits nothing, so it is excluded by accident rather than by
 * the gate.
 *
 * The requirement is absence, not compilability (issue #121, "Please do not fix it these two
 * ways"). C# has no equivalent of a Kotlin opt-in marker, so a member that reaches `Interop.cs` is
 * unconditionally public API in the shipped package with no way to re-hide it. Opting the generated
 * Kotlin in would make the build green and still ship the member.
 *
 * [Tier1OptInMarkerSkipTest] owns the detection side; this file owns the emitters.
 *
 * Oreo refuses to be public API. Mylo has no opinion.
 */
class Tier1OptInLambdaRouteTest {

  private val source: String = """
    package tier1.optin.lambda

    import kotlinx.coroutines.flow.Flow
    import kotlinx.coroutines.flow.flowOf

    @RequiresOptIn(level = RequiresOptIn.Level.ERROR, message = "internal")
    @Retention(AnnotationRetention.BINARY)
    @Target(
      AnnotationTarget.CLASS,
      AnnotationTarget.FUNCTION,
      AnnotationTarget.PROPERTY,
      AnnotationTarget.CONSTRUCTOR,
    )
    annotation class InternalMarker

    class Feed(val items: Int) {
      @property:InternalMarker val onRefresh: suspend () -> Unit = {}
      @property:InternalMarker val onRetry: () -> Unit = {}
      @property:InternalMarker val onTicks: Flow<Int> = flowOf(1)

      val onVisible: () -> Unit = {}

      suspend fun refresh() = onRefresh()
      fun retry() = onRetry()
    }

    sealed class Panel {
      data class Live(val label: String) : Panel() {
        @property:InternalMarker val onClose: () -> Unit = {}
        val onOpen: () -> Unit = {}
      }
    }
  """.trimIndent()

  private fun run(): Tier1Result = Tier1Harness.run(
    source,
    libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
  )

  /** Criterion 1: no `@CName` thunk for a marked property in the generated Kotlin. */
  @Test
  fun `a marked lambda property has no CName getter in the generated Kotlin`() {
    val result = run()
    val kotlin: String = result.generated

    listOf("feed_get_onRefresh", "feed_get_onRetry", "panel_live_get_onClose").forEach { export ->
      assertTrue(
        export !in kotlin,
        "a marked property must not reach the generated Kotlin, found `$export`",
      )
    }
  }

  /** The Flow arm of the same fall-through, which the issue does not name but shares the route. */
  @Test
  fun `a marked Flow property has no CName export in the generated Kotlin`() {
    val result = run()

    assertTrue(
      "onTicks" !in result.generated,
      "a marked Flow property goes down the same legacy fall-through and must be gated too",
    )
  }

  /** Criterion 2: no C# member for a marked property in the generated bindings. */
  @Test
  fun `a marked lambda property has no C# member`() {
    val result = run()
    val cs: String = result.generatedCSharp

    listOf("OnRefresh", "OnRetry", "OnTicks", "OnClose").forEach { member ->
      assertTrue(
        member !in cs,
        "a marked property must not reach Interop.cs, found `$member`. C# has no opt-in " +
            "marker, so anything emitted here is unconditionally public in the package",
      )
    }
  }

  /**
   * Criterion 4, the control. The unmarked functions that delegate to the marked properties are
   * the intended surface and must keep binding, so the gate cannot simply take the class with it.
   */
  @Test
  fun `the unmarked delegating members still bind`() {
    val result = run()
    val cs: String = result.generatedCSharp

    assertTrue("RefreshAsync" in cs, "the unmarked suspend delegate must survive; got:\n$cs")
    assertTrue("Retry" in cs, "the unmarked delegate must survive")
    assertTrue("OnVisible" in cs, "an unmarked lambda property on the same class must survive")
    assertTrue("OnOpen" in cs, "an unmarked lambda property on the sealed arm must survive")
  }

  /**
   * The invariant issue #121 asks for, and the one that would have caught this before release: a
   * declaration named in any `SKIPPED_*` diagnostic must be absent from both artifacts. Asserted
   * here over this fixture rather than globally, which is a broader change than one bug fix.
   */
  @Test
  fun `nothing named in a SKIPPED diagnostic appears in either artifact`() {
    val result = run()

    val skipped: List<String> = result.kspWarnings
      .filter { it.contains(ForwardDiagnosticKind.SKIPPED_OPT_IN_MARKER.name) }
      .mapNotNull { warning ->
        Regex("""tier1\.optin\.lambda\.[\w.]+""").find(warning)?.value
      }
      .map { it.substringAfterLast('.') }
      .distinct()

    assertTrue(skipped.isNotEmpty(), "expected the marked properties to be reported at all")

    skipped.forEach { member ->
      val pascal: String = member.replaceFirstChar { it.uppercase() }
      assertTrue(
        member !in result.generated,
        "`$member` is reported skipped yet present in the generated Kotlin",
      )
      assertTrue(
        pascal !in result.generatedCSharp,
        "`$member` is reported skipped yet present as `$pascal` in the generated C#",
      )
    }
  }
}
