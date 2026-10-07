package io.github.xxfast.kotlin.native.nuget.processor.tier1

import com.google.devtools.ksp.KspExperimental
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSFile
import io.github.xxfast.kotlin.native.nuget.processor.NugetProcessorProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The forward generator's output must not depend on the order KSP enumerates source files. On
 * ext4 `readdir` order is hash-based, so a Linux pack enumerated a RID's per-platform `actual`
 * files in a different order from a Windows or macOS pack and the cross-RID package contract
 * check failed on `Interop.cs` token order.
 *
 * The harness writes every fixture into one source root and KSP lists that directory itself, so
 * reordering the fixture map changes nothing on a sorted file system. The order is reversed at
 * the [Resolver] seam instead: the same files, handed to the real processor in both orders.
 */
class Tier1DeterministicFileOrderTest {

  @OptIn(KspExperimental::class)
  private class ReversingResolver(private val delegate: Resolver) : Resolver by delegate {
    override fun getAllFiles(): Sequence<KSFile> =
      delegate.getAllFiles().toList().asReversed().asSequence()

    override fun getNewFiles(): Sequence<KSFile> =
      delegate.getNewFiles().toList().asReversed().asSequence()

    override fun getSymbolsWithAnnotation(
      annotationName: String,
      inDepth: Boolean,
    ): Sequence<KSAnnotated> =
      delegate.getSymbolsWithAnnotation(annotationName, inDepth).toList().asReversed().asSequence()
  }

  private class ReversingProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
      val real: SymbolProcessor = NugetProcessorProvider().create(environment)
      return object : SymbolProcessor by real {
        override fun process(resolver: Resolver): List<KSAnnotated> =
          real.process(ReversingResolver(resolver))
      }
    }
  }

  private val sources: Map<String, String> = mapOf(
    "Alpha.kt" to """
      package tier1.fileorder

      import io.github.xxfast.kotlin.native.nuget.annotations.CSharpName

      fun alpha(): Int = 1

      val alphaLabel: String = "alpha"

      class Alpha(val name: String) {
        @CSharpName("Describe")
        fun describe(): String = name
      }

      sealed class Shape {
        object Dot : Shape()
      }

      enum class AlphaMood { CALM, CURIOUS }
    """.trimIndent(),
    "Beta.kt" to """
      package tier1.fileorder

      import io.github.xxfast.kotlin.native.nuget.annotations.CSharpName

      fun beta(): Int = 2

      val betaLabel: String = "beta"

      class Beta(val count: Int) {
        @CSharpName("Tally")
        fun tally(): Int = count
      }

      class Square(val side: Int) : Shape()

      enum class BetaMood { SLEEPY, HUNGRY }
    """.trimIndent(),
  )

  private fun generate(provider: SymbolProcessorProvider): Map<String, String> {
    val result: Tier1Result = Tier1Harness.run(
      sources = sources,
      libraries = listOf(csharpNameLibrary),
      provider = provider,
    )
    assertTrue(result.kspErrors.isEmpty(), "kspErrors=${result.kspErrors}")
    assertTrue(result.compiledClean, "compileErrors=${result.compileErrors}")
    // `NugetDiagnostics.json` records absolute source paths, and each run gets its own temp dir.
    return result.generatedFiles.mapValues { (_, text) ->
      text.replace(Regex("nuget-tier1-[0-9]+"), "nuget-tier1-WORKDIR")
    }
  }

  @Test
  fun `reversing the file order leaves every generated file byte-identical`() {
    val enumerated: Map<String, String> = generate(NugetProcessorProvider())
    val reversed: Map<String, String> = generate(ReversingProvider())

    assertTrue(enumerated.keys.any { it.endsWith("Interop.cs") }, "keys=${enumerated.keys}")
    assertTrue(enumerated.keys.any { it.endsWith("CNameExports.kt") }, "keys=${enumerated.keys}")
    assertEquals(enumerated.keys, reversed.keys)
    enumerated.forEach { (path, text) ->
      assertEquals(text, reversed.getValue(path), "$path differs when the file order is reversed")
    }
  }
}
