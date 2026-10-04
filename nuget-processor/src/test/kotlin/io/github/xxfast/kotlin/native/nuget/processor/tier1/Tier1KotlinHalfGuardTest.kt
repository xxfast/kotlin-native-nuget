package io.github.xxfast.kotlin.native.nuget.processor.tier1

import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSDeclaration
import io.github.xxfast.kotlin.native.nuget.processor.NugetProcessor
import io.github.xxfast.kotlin.native.nuget.processor.cir.NugetContext
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import io.github.xxfast.kotlin.native.nuget.processor.forward.forwardGuardName
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ADR-162: the failure arm of the Kotlin-half per-declaration guards in `generateCNameWrappers`,
 * driven through the real processor.
 *
 * No legal Kotlin shape reaches that arm today, and a shipped test may not plant a throw in the
 * generator. The processor's defaulted `declarationStep` runs first inside every one of those
 * guards; this test passes a step that fails for each `broken*` declaration, so every guard
 * installation such a declaration passes through has to contain it on its own.
 */
class Tier1KotlinHalfGuardTest {

  /**
   * Fails every visit to a `broken*` declaration with a FRESH message, so the guard's
   * (declaration, detail) dedupe never folds two installations into one report: each visit has
   * to produce its own diagnostic, which is what makes `visits == reports` a per-site check.
   */
  private class PlantedStep {
    val visits: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private var planted: Int = 0

    fun visit(declaration: KSDeclaration) {
      val name: String = declaration.forwardGuardName()
      visits += name
      if (declaration.simpleName.asString().startsWith("broken", ignoreCase = true)) {
        planted += 1
        throw IllegalStateException("planted failure #$planted at $name")
      }
    }
  }

  /** The real processor with the provider's no-option context and the planted step. */
  private class PlantedStepProvider(private val step: PlantedStep) : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
      NugetProcessor(
        codeGenerator = environment.codeGenerator,
        logger = environment.logger,
        context = NugetContext(
          libraryName = "library",
          rootNamespace = "Interop",
          rootPackage = "",
          className = "NativeBindings",
        ),
        declarationStep = step::visit,
      )
  }

  // One failing and one healthy declaration per loop of `generateCNameWrappers`, the failing one
  // declared first, so the healthy one is only reached if the loop survived the failure.
  private val fixture: String = """
    package tier1.guard

    fun brokenFetch(): Int = 1
    fun healthyFetch(): Int = 2

    fun <T> brokenEcho(value: T): T = value
    fun <T> healthyEcho(value: T): T = value

    class BrokenCrate(val size: Int) {
      fun open(): Int = size
      companion object {
        fun empty(): Int = 0
      }
    }
    class HealthyCrate(val size: Int) {
      fun open(): Int = size
      companion object {
        fun empty(): Int = 0
      }
    }

    enum class BrokenMood { CALM, CROSS }
    enum class HealthyMood { CALM, CROSS }

    sealed class BrokenShape {
      class Dot(val r: Int) : BrokenShape()
    }
    sealed class HealthyShape {
      class Dot(val r: Int) : HealthyShape()
    }
    sealed class Tile {
      class BrokenTile(val n: Int) : Tile()
      class HealthyTile(val n: Int) : Tile()
    }

    object BrokenRegistry {
      fun count(): Int = 1
    }
    object HealthyRegistry {
      fun count(): Int = 1
    }

    @JvmInline
    value class BrokenId(val raw: Int)
    @JvmInline
    value class HealthyId(val raw: Int)

    interface BrokenSource {
      fun read(): Int
    }
    interface HealthySource {
      fun read(): Int
    }
    fun openBrokenSource(): BrokenSource = object : BrokenSource {
      override fun read(): Int = 1
    }
    fun openHealthySource(): HealthySource = object : HealthySource {
      override fun read(): Int = 1
    }

    val brokenLevel: Int = 1
    val healthyLevel: Int = 2

    fun Int.brokenTwice(): Int = this * 2
    fun Int.healthyTwice(): Int = this * 2

    val Int.brokenHalf: Int get() = this / 2
    val Int.healthyHalf: Int get() = this / 2

    suspend fun brokenWait(): Int = 1
    suspend fun healthyWait(): Int = 1
  """.trimIndent()

  private class GuardedPair(val broken: String, val healthy: String, val guards: Int)

  /**
   * Failing declaration, its healthy partner in the same loops, and how many guard installations
   * in `generateCNameWrappers` walk that kind: a class goes through the class, companion and
   * suspend-class loops, a sealed base through the sealed and sealed-async loops, an arm through
   * the four arm loops, an interface through the export loop and the bridge-plan guard. A loop
   * added or removed there changes a count, which is when this test should be re-read.
   */
  private val pairs: List<GuardedPair> = listOf(
    GuardedPair("brokenFetch", "healthyFetch", guards = 1),
    GuardedPair("brokenEcho", "healthyEcho", guards = 1),
    GuardedPair("BrokenCrate", "HealthyCrate", guards = 3),
    GuardedPair("BrokenMood", "HealthyMood", guards = 1),
    GuardedPair("BrokenShape", "HealthyShape", guards = 2),
    GuardedPair("Tile.BrokenTile", "Tile.HealthyTile", guards = 4),
    GuardedPair("BrokenRegistry", "HealthyRegistry", guards = 1),
    GuardedPair("BrokenId", "HealthyId", guards = 1),
    GuardedPair("BrokenSource", "HealthySource", guards = 2),
    GuardedPair("brokenLevel", "healthyLevel", guards = 1),
    GuardedPair("brokenTwice", "healthyTwice", guards = 1),
    GuardedPair("brokenHalf", "healthyHalf", guards = 1),
    GuardedPair("brokenWait", "healthyWait", guards = 1),
  ).map { pair ->
    GuardedPair("tier1.guard.${pair.broken}", "tier1.guard.${pair.healthy}", pair.guards)
  }

  /** The fixture is legal on its own: without the planted step it generates and compiles clean. */
  @Test
  fun `the fixture generates cleanly with the default step`() {
    val result: Tier1Result = Tier1Harness.run(fixture)

    assertEquals(emptyList(), result.kspErrors, "kspErrors=${result.kspErrors}")
    assertTrue(
      result.generatedFiles.keys.any { path -> path.endsWith("CNameExports.kt") },
      "files=${result.generatedFiles.keys}",
    )
    assertEquals(emptyList(), result.compileErrors, "compileErrors=${result.compileErrors}")
  }

  @Test
  fun `every Kotlin-half guard contains its own declaration and the loop keeps going`() {
    val step = PlantedStep()
    val result: Tier1Result = Tier1Harness.run(fixture, provider = PlantedStepProvider(step))
    val visits: List<String> = step.visits.toList()

    assertTrue(
      result.kspErrors.none { message -> "this Kotlin module" in message },
      "nothing escaped a per-declaration guard to the whole-round catch; " +
          "kspErrors=${result.kspErrors}",
    )
    assertTrue(
      result.kspErrors.all { message ->
        ForwardDiagnosticKind.ERROR_INTERNAL_GENERATOR_FAILURE.name in message &&
            "planted failure #" in message
      },
      "the planted failures are the only errors; kspErrors=${result.kspErrors}",
    )
    assertEquals(
      visits.count { name -> name.substringAfterLast('.').startsWith("broken", true) },
      result.kspErrors.size,
      "every planted throw is reported exactly once; visits=$visits kspErrors=${result.kspErrors}",
    )

    pairs.forEach { pair ->
      val broken: String = pair.broken
      val healthy: String = pair.healthy
      val brokenVisits: Int = visits.count { name -> name == broken }
      val reports: List<String> = result.kspErrors.filter { message -> "at $broken)" in message }
      assertEquals(
        pair.guards,
        brokenVisits,
        "$broken passes through ${pair.guards} guard installation(s); visits=$visits",
      )
      assertEquals(
        brokenVisits,
        reports.size,
        "each guard $broken passes through reports it once; reports=$reports",
      )
      reports.forEach { report ->
        assertTrue(
          " $broken: " in report && "\n    at " in report && "Fixture.kt:" in report,
          "named and located at the declaration's own source; report=$report",
        )
      }

      assertTrue(
        visits.indexOf(healthy) > visits.indexOf(broken),
        "$healthy is reached after $broken failed, so the loop kept going; visits=$visits",
      )
      assertTrue(
        result.kspErrors.none { message -> healthy in message },
        "$healthy finished inside its own guard; kspErrors=${result.kspErrors}",
      )
    }

    assertTrue(
      result.generatedFiles.keys.none { path -> path.endsWith("CNameExports.kt") },
      "the fatal gate stops the round before the Kotlin half is written; " +
          "files=${result.generatedFiles.keys}",
    )
    // The C# half is written ahead of that gate and its translators never call the step, so
    // `Interop.cs` still lands in a round the Kotlin half failed. The KSP run fails regardless.
    assertTrue(
      result.generatedFiles.keys.any { path -> path.endsWith("Interop.cs") },
      "files=${result.generatedFiles.keys}",
    )
    assertEquals("PROCESSING_ERROR", result.kspExitCode, "a contained failure is still fatal")
  }
}
