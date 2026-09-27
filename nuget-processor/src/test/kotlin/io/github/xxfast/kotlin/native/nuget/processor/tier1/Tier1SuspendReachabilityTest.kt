package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ROADMAP Phase 4 line 23 (memo `docs/research/roadmap/top-level-suspend-reachability.md`): a
 * dependency type reachable only through a `suspend` member is either admitted by ADR-066's closure
 * and spelled `global::`-qualified, or the member is refused with a named
 * `SKIPPED_UNEXPORTED_DEPENDENCY_TYPE` carrying ADR-154's `admit(...)` hint. A value class at a
 * suspend return completes through `NugetUnbox`, never a handle constructor it does not have.
 *
 * Every negative assertion checks the BARE spelling too (`Task<Doormat>`, `new Doormat(`): this
 * route used to spell a return by its simple name, which a `global::`-only check never sees.
 */
class Tier1SuspendReachabilityTest {

  private val dependencyJar: File = Tier1DependencyLibrary.compile(
    mapOf(
      "Susp.kt" to """
        package dep.susp

        class Rimguard(val material: String)

        class Knot(val tightness: Int)

        enum class Bedding { FLEECE, WICKER }

        @JvmInline
        value class Eartag(val code: String)
      """.trimIndent(),
      "Never.kt" to """
        package dep.never

        class Doormat(val worn: Boolean)

        class Bristle(val stiff: Boolean)

        class Tassel(val long: Boolean)

        class Fringe(val short: Boolean)
      """.trimIndent(),
    ),
  )

  private val fixture: String = """
    package tier1.suspendreach

    import dep.never.Bristle
    import dep.never.Doormat
    import dep.never.Fringe
    import dep.never.Tassel
    import dep.susp.Eartag
    import dep.susp.Knot
    import dep.susp.Rimguard
    import tier1.suspendreach.moods.Mood
    import kotlinx.coroutines.flow.Flow
    import kotlinx.coroutines.flow.flowOf

    @JvmInline
    value class Nametag(val label: String)

    @JvmInline
    value class Litter(val cats: List<String>)

    suspend fun litterLater(): Litter = Litter(listOf("Oreo", "Mylo"))

    suspend fun moodLater(): Mood = Mood.SLEEPY
    suspend fun maybeMood(): Mood? = null
    suspend fun beddingLater(): dep.susp.Bedding = dep.susp.Bedding.WICKER

    suspend fun rimguardLater(): Rimguard = Rimguard("silicone")
    suspend fun fit(guard: Rimguard): String = guard.material
    suspend fun eartagLater(): Eartag = Eartag("oreo-0001")
    suspend fun nametagLater(): Nametag = Nametag("Oreo")
    suspend fun maybeNametag(): Nametag? = null

    suspend fun doormatLater(): Doormat = Doormat(true)
    suspend fun wipe(mat: Doormat): String = mat.worn.toString()
    suspend fun String.knotted(): Knot = Knot(length)
    suspend fun String.brushed(): Bristle = Bristle(true)

    class Shed(val name: String) {
      suspend fun fetchMat(): Doormat = Doormat(false)
      suspend fun engrave(): Nametag = Nametag(name)
      suspend fun lookUp(): Nametag? = null
      suspend fun mood(): Mood = Mood.PLAYFUL
      fun tassels(): Flow<Tassel> = flowOf(Tassel(true))
      val fringes: Flow<Fringe> get() = flowOf(Fringe(true))
    }

    fun Int.fringe(): Fringe = Fringe(this > 0)
  """.trimIndent()

  private val result: Tier1Result by lazy {
    Tier1Harness.run(
      mapOf(
        "Fixture.kt" to fixture,
        // A module-local enum in a DIFFERENT package from its suspend callers.
        "Moods.kt" to "package tier1.suspendreach.moods\n\nenum class Mood { SLEEPY, PLAYFUL }\n",
      ),
      processorOptions = mapOf(
        "nuget.namespace" to "Lib",
        "nuget.includePackages" to "tier1.suspendreach",
        "nuget.admit" to "dep.susp",
      ),
      libraries = listOf(dependencyJar, Tier1Classpath.kotlinxCoroutinesCore),
    )
  }

  private fun skip(member: String, kind: ForwardDiagnosticKind): String =
    result.kspWarnings.firstOrNull { it.contains(kind.name) && it.contains(member) }
      ?: error("expected a $kind skip for $member; got: ${result.kspWarnings}")

  private fun manifest(): String =
    result.kspWarnings.firstOrNull {
      it.contains(ForwardDiagnosticKind.INFO_EXPORTED_FROM_DEPENDENCY.name)
    } ?: error("expected an export manifest; got: ${result.kspWarnings}")

  private fun assertSpelledNowhere(type: String) {
    listOf("Task<$type>", "Task<$type?>", "new $type(", "global::Lib.$type").forEach {
      assertFalse(
        it in result.generatedCSharp,
        "`$it` must not be spelled; got:\n${result.generatedCSharp}",
      )
    }
  }

  @Test
  fun `the fixture compiles clean`() {
    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
  }

  @Test
  fun `a type reached only through a top-level suspend member is admitted`() {
    val manifest: String = manifest()
    assertTrue("dep.susp.Rimguard" in manifest, "suspend return; got: $manifest")
    assertTrue("dep.susp.Eartag" in manifest, "suspend value-class return; got: $manifest")
  }

  @Test
  fun `an admitted dependency class at a suspend return is spelled qualified`() {
    val cs: String = result.generatedCSharp
    assertTrue("Task<global::Lib.Rimguard> RimguardLaterAsync(" in cs, cs)
    assertTrue("new global::Lib.Rimguard(resultPtr, out _)" in cs, cs)
    assertFalse("Task<Rimguard>" in cs, "bare spelling; got:\n$cs")
    assertFalse("new Rimguard(" in cs, "bare construction; got:\n$cs")
  }

  @Test
  fun `an admitted dependency class binds as a suspend parameter`() {
    assertTrue(
      "FitAsync(global::Lib.Rimguard guard" in result.generatedCSharp,
      result.generatedCSharp,
    )
  }

  @Test
  fun `a value class at a suspend return completes by NugetUnbox`() {
    val cs: String = result.generatedCSharp
    assertTrue("Task<global::Lib.Eartag> EartagLaterAsync(" in cs, cs)
    assertTrue("t.SetResult(global::Lib.Eartag.NugetUnbox(resultPtr));" in cs, cs)
    assertTrue(Regex("""t\.SetResult\(global::Lib\.[\w.]*Nametag\.NugetUnbox\(resultPtr\)\);""")
      .findAll(cs).count() == 2, "top-level and class-level non-null; got:\n$cs")
    assertTrue(
      Regex("""resultPtr == IntPtr\.Zero \? \(global::Lib\.[\w.]*Nametag\?\)null : """ +
          """global::Lib\.[\w.]*Nametag\.NugetUnbox\(resultPtr\)""").findAll(cs).count() == 2,
      "top-level and class-level nullable, null-guarded; got:\n$cs",
    )
    assertFalse(Regex("""new [\w:.]*(Eartag|Nametag)\(resultPtr""").containsMatchIn(cs), cs)
  }

  @Test
  fun `an out-of-scope dependency at a top-level suspend return is a named admit skip`() {
    val warning: String =
      skip("doormatLater", ForwardDiagnosticKind.SKIPPED_UNEXPORTED_DEPENDENCY_TYPE)
    assertTrue("""admit("dep.never.Doormat")""" in warning, warning)
    assertSpelledNowhere("Doormat")
    assertFalse("doormatLater" in result.generated, "no Kotlin export; got:\n${result.generated}")
  }

  @Test
  fun `an out-of-scope dependency at a class-level suspend return is a named admit skip`() {
    val warning: String = skip("fetchMat", ForwardDiagnosticKind.SKIPPED_UNEXPORTED_DEPENDENCY_TYPE)
    assertTrue("""admit("dep.never.Doormat")""" in warning, warning)
    assertFalse("FetchMatAsync" in result.generatedCSharp, result.generatedCSharp)
    assertFalse("fetchMat" in result.generated, "no Kotlin export; got:\n${result.generated}")
  }

  @Test
  fun `an out-of-scope dependency at a suspend parameter names admit, not the route`() {
    val warning: String = skip("wipe", ForwardDiagnosticKind.SKIPPED_UNEXPORTED_DEPENDENCY_TYPE)
    assertTrue("""admit("dep.never.Doormat")""" in warning, warning)
    assertFalse(
      result.kspWarnings.any {
        it.contains(ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_INPUT.name) && it.contains("wipe")
      },
      "the generic route wording must not also fire; got: ${result.kspWarnings}",
    )
    assertFalse("WipeAsync" in result.generatedCSharp, result.generatedCSharp)
  }

  @Test
  fun `an out-of-scope dependency Flow element is a named admit skip`() {
    val warning: String = skip("tassels", ForwardDiagnosticKind.SKIPPED_UNEXPORTED_DEPENDENCY_TYPE)
    assertTrue("""admit("dep.never.Tassel")""" in warning, warning)
    assertSpelledNowhere("Tassel")
    assertFalse("Tassel>" in result.generatedCSharp, result.generatedCSharp)
  }

  @Test
  fun `a top-level suspend extension is named, never silently dropped`() {
    val warning: String = result.kspWarnings.firstOrNull { it.contains("knotted") }
      ?: error("expected knotted to be named; got: ${result.kspWarnings}")
    assertTrue("suspend" in warning, warning)
  }

  /** A value class with no `NugetBox`/`NugetUnbox` pair (ADR-171 builds one only for a scalar,
   *  String, enum or handle underlying) is refused by name, never completed through a `NugetUnbox`
   *  that was never generated (CS0117). */
  @Test
  fun `a value class with no unbox pair at a suspend return is refused by name`() {
    skip("litterLater", ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_RETURN)
    assertFalse("LitterLaterAsync" in result.generatedCSharp, result.generatedCSharp)
    assertFalse("Litter.NugetUnbox" in result.generatedCSharp, result.generatedCSharp)
    assertFalse("litterLater" in result.generated, "no Kotlin export; got: ${result.generated}")
  }

  /**
   * ROADMAP Phase 4 line 23 fold-in: an enum at a suspend return (module-local in another package,
   * top-level and class-level, nullable; and a dependency enum reached only through a top-level
   * suspend return). It was spelled bare (`Task<Mood>`) and completed with `new Mood(resultPtr,
   * out _)`, which no C# enum has. It now crosses by ordinal and is cast back to the qualified
   * enum, so the awaited value is the enum, never an `int`.
   */
  @Test
  fun `an enum at a suspend return crosses by ordinal and is cast back qualified`() {
    val cs: String = result.generatedCSharp
    assertTrue("dep.susp.Bedding" in manifest(), "dependency enum admitted; got: ${manifest()}")
    val mood = """global::Lib\.[\w.]*Mood"""
    listOf("MoodLaterAsync", "MoodAsync").forEach { name ->
      assertTrue(Regex("""Task<$mood> $name\(""").containsMatchIn(cs), "$name; got:\n$cs")
    }
    assertTrue(Regex("""Task<$mood\?> MaybeMoodAsync\(""").containsMatchIn(cs), cs)
    assertTrue(
      Regex("""t\.SetResult\(\($mood\)NugetMarshal\.FromHandle<int>\(resultPtr\)\);""")
        .findAll(cs).count() == 2,
      "top-level and class-level non-null, cast to the enum; got:\n$cs",
    )
    assertTrue(
      Regex("""t\.SetResult\(resultPtr == IntPtr\.Zero \? \($mood\?\)null : """ +
          """\($mood\)NugetMarshal\.FromHandle<int>\(resultPtr\)\);""").containsMatchIn(cs),
      "nullable, null-guarded; got:\n$cs",
    )
    assertTrue(
      "t.SetResult((global::Lib.Bedding)NugetMarshal.FromHandle<int>(resultPtr));" in cs,
      "dependency enum; got:\n$cs",
    )
    assertFalse(Regex("""new [\w:.]*(Mood|Bedding)\(""").containsMatchIn(cs), cs)
    assertFalse("Task<Mood>" in cs || "Task<Bedding>" in cs, "bare spelling; got:\n$cs")
    assertTrue(
      Regex("""NugetHandles\.retain\(result\.ordinal\)""").findAll(result.generated).count() >= 4,
      "every enum suspend return boxes its ordinal; got:\n${result.generated}",
    )
  }

  /** The Flow PROPERTY twin of the Flow-method cell: `admit(...)`, not the generic wording. */
  @Test
  fun `an out-of-scope dependency Flow property element is a named admit skip`() {
    val warning: String = skip("fringes", ForwardDiagnosticKind.SKIPPED_UNEXPORTED_DEPENDENCY_TYPE)
    assertTrue("""admit("dep.never.Fringe")""" in warning, warning)
    assertFalse(
      result.kspWarnings.any {
        it.contains(ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_PROPERTY.name) &&
            it.contains("fringes")
      },
      "the generic property wording must not also fire; got: ${result.kspWarnings}",
    )
    assertFalse("Fringes" in result.generatedCSharp, result.generatedCSharp)
  }
}
