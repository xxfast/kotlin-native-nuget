package io.github.xxfast.kotlin.native.nuget.processor.tier1

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

/**
 * Issue #487: a bare interface reached ONLY as a `Flow`/`StateFlow` element (or a bare `suspend`
 * result) is spelled `ISighting` and read through `new Sighting(h, out _)`, so it needs the same
 * backing class, `Factories` key and bridge arm a planned position gives it (ADR-040). Without
 * the walk under test nothing declares `Sighting`, and the generated C# fails `CS0234`.
 *
 * Each cell declares its interface in exactly ONE legacy-route position, so the only thing that
 * can produce the backing class is the reachability walk.
 */
class Tier1FlowInterfaceElementTest {

  private val coroutines: List<File> = listOf(Tier1Classpath.kotlinxCoroutinesCore)

  private fun Tier1Result.assertMaterialises(name: String, where: String) {
    assertTrue(compiledClean, "expected $where to compile; got: $compileErrors")
    val csharp: String = generatedCSharp
    assertContains(
      csharp,
      "public sealed class $name : I$name",
      message = "no backing wrapper for $name at $where:\n$csharp",
    )
    assertContains(
      csharp,
      "[typeof(global::Interop.I$name)] = " +
        "static handle => new global::Interop.$name(handle, out _)",
      message = "no I$name factory key at $where:\n$csharp",
    )
    assertConstructedTypesAreDeclared()
  }

  /**
   * Issue #487 requirement 4, the invariant behind every cell: every `new global::Interop.X.Y(`
   * the generated C# spells names a type declared in that same file. Declarations are read off
   * code lines only (a `///` remark intentionally names skipped types, AGENTS.md).
   */
  private fun Tier1Result.assertConstructedTypesAreDeclared() {
    val code: List<String> = generatedCSharp.lines().filterNot { it.trimStart().startsWith("///") }
    val declared: Set<String> = code
      .flatMap { line -> DECLARATION.findAll(line).map { it.groupValues[1] }.toList() }
      .toSet()
    val constructed: Set<String> = code
      .flatMap { line -> CONSTRUCTION.findAll(line).map { it.groupValues[1] }.toList() }
      .toSet()
    val undeclared: Set<String> = constructed - declared
    assertTrue(
      undeclared.isEmpty(),
      "generated C# constructs types it never declares: $undeclared\n$generatedCSharp",
    )
  }

  @Test
  fun `a module-local interface reached only as a Flow element gets its backing class`() {
    val result = Tier1Harness.run(
      """
      package tier1.flowiface

      import kotlinx.coroutines.flow.Flow
      import kotlinx.coroutines.flow.emptyFlow

      interface Sighting { val name: String? }

      class Spotter {
        fun watch(): Flow<Sighting> = emptyFlow()
      }
      """.trimIndent(),
      libraries = coroutines,
    )
    assertContains(result.generatedCSharp, "KotlinFlow<global::Interop.ISighting> Watch()")
    result.assertMaterialises("Sighting", "Spotter.watch")
  }

  /** The issue's verified shape: the interface and its owner live in a dependency module. */
  @Test
  fun `a dependency interface admitted only as a Flow element gets its backing class`() {
    val dependencyJar: File = Tier1DependencyLibrary.compile(
      """
      package dep.birds

      import kotlinx.coroutines.flow.Flow
      import kotlinx.coroutines.flow.emptyFlow

      interface Sighting { val name: String? }

      class Spotter {
        fun watch(): Flow<Sighting> = emptyFlow()
      }
      """.trimIndent()
    )
    val result = Tier1Harness.run(
      """
      package tier1.flowifacedep

      import dep.birds.Spotter

      fun spotter(): Spotter = Spotter()
      """.trimIndent(),
      processorOptions = mapOf(
        "nuget.namespace" to "Interop",
        "nuget.includePackages" to "tier1.flowifacedep",
        "nuget.admit" to "dep.birds",
      ),
      libraries = listOf(dependencyJar) + coroutines,
    )
    assertContains(result.generatedCSharp, "KotlinFlow<global::Interop.ISighting> Watch()")
    result.assertMaterialises("Sighting", "dep.birds.Spotter.watch")
    Tier1CSharpCompile.assertCompiles(
      result,
      """
      namespace Consumer
      {
          public static class Probe
          {
              public static object Run(global::Interop.Spotter spotter) => spotter.Watch();
          }
      }
      """.trimIndent(),
      allowUnsafe = true,
    )
  }

  @Test
  fun `StateFlow, suspend Flow, nullable element and suspend results all reach the interface`() {
    val result = Tier1Harness.run(
      """
      package tier1.flowifacekinds

      import kotlinx.coroutines.flow.Flow
      import kotlinx.coroutines.flow.MutableStateFlow
      import kotlinx.coroutines.flow.StateFlow
      import kotlinx.coroutines.flow.emptyFlow

      interface Sighting { val name: String? }
      interface Nest { val size: Int }
      interface Egg { val weight: Int }
      interface Feather { val length: Int }
      interface Beak { val width: Int }

      class Spotter {
        val latest: StateFlow<Nest> = MutableStateFlow(object : Nest { override val size: Int = 1 })
        suspend fun later(): Flow<Egg> = emptyFlow()
        fun maybe(): Flow<Feather?> = emptyFlow()
        suspend fun one(): Beak = object : Beak { override val width: Int = 2 }
      }

      suspend fun sightings(): Sighting = object : Sighting { override val name: String? = null }
      """.trimIndent(),
      libraries = coroutines,
    )
    val csharp: String = result.generatedCSharp
    assertContains(csharp, "KotlinStateFlow<global::Interop.INest> Latest")
    assertContains(csharp, "Task<KotlinFlow<global::Interop.IEgg>> LaterAsync(")
    assertContains(csharp, "KotlinFlow<global::Interop.IFeather?> Maybe()")
    assertContains(csharp, "Task<global::Interop.IBeak> OneAsync(")
    assertContains(csharp, "Task<global::Interop.ISighting> SightingsAsync(")
    listOf("Sighting", "Nest", "Egg", "Feather", "Beak").forEach { name ->
      result.assertMaterialises(name, "the kinds cell")
    }
  }

  /** The same walk's second caller: a reachable interface's OWN member naming another interface. */
  @Test
  fun `an interface reached only through a reachable interface's member gets its backing class`() {
    val result = Tier1Harness.run(
      """
      package tier1.flowifacemember

      import kotlinx.coroutines.flow.Flow
      import kotlinx.coroutines.flow.emptyFlow

      interface Brush { val bristles: Int }
      interface Comb { val teeth: Int }

      interface Groomer {
        fun brush(): Brush
        fun combs(): Flow<Comb>
      }

      class Salon {
        fun groomer(): Groomer = object : Groomer {
          override fun brush(): Brush = object : Brush { override val bristles: Int = 3 }
          override fun combs(): Flow<Comb> = emptyFlow()
        }
      }
      """.trimIndent(),
      libraries = coroutines,
    )
    result.assertMaterialises("Groomer", "Salon.groomer")
    result.assertMaterialises("Brush", "Groomer.brush")
    result.assertMaterialises("Comb", "Groomer.combs")
  }

  private companion object {
    val DECLARATION = Regex("""\b(?:class|struct|interface|enum)\s+([A-Za-z_][A-Za-z0-9_]*)""")
    val CONSTRUCTION = Regex(
      """\bnew\s+global::Interop(?:\.[A-Za-z_][A-Za-z0-9_]*)*\.([A-Za-z_][A-Za-z0-9_]*)\s*\(""",
    )
  }
}
