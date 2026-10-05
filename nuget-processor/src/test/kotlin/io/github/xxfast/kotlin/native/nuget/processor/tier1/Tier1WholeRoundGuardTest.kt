package io.github.xxfast.kotlin.native.nuget.processor.tier1

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import io.github.xxfast.kotlin.native.nuget.processor.NugetProcessorProvider
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import java.io.IOException
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ADR-162: the whole-round catch in `NugetProcessor.process`, driven through the real processor.
 *
 * No legal Kotlin shape reaches it, and a shipped test may not plant a throw in the generator. A
 * failing write is neither: it is a failure a real build produces (a full disk, a locked file),
 * and it arrives through the `CodeGenerator` KSP already injects. The `Interop.cs` write is the
 * one that lands outside every per-declaration guard, before the fatal-diagnostic gate.
 */
class Tier1WholeRoundGuardTest {

  /** Fails the `Interop.cs` write only; every other file goes to KSP's own generator. */
  private class FailingInteropWrite(private val real: CodeGenerator) : CodeGenerator by real {
    override fun createNewFile(
      dependencies: Dependencies,
      packageName: String,
      fileName: String,
      extensionName: String,
    ): OutputStream {
      if (fileName == "Interop" && extensionName == "cs") {
        throw IOException("disk full writing $fileName.$extensionName")
      }
      return real.createNewFile(dependencies, packageName, fileName, extensionName)
    }
  }

  private class FailingInteropWriteProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
      NugetProcessorProvider().create(
        SymbolProcessorEnvironment(
          options = environment.options,
          kotlinVersion = environment.kotlinVersion,
          codeGenerator = FailingInteropWrite(environment.codeGenerator),
          logger = environment.logger,
          apiVersion = environment.apiVersion,
          compilerVersion = environment.compilerVersion,
          platforms = environment.platforms,
          kspVersion = environment.kspVersion,
        ),
      )
  }

  @Test
  fun `a failure no declaration owns is reported once under the module label`() {
    // Returning at all is the first assertion: the IOException did not escape `process()`.
    val result: Tier1Result = Tier1Harness.run(
      """
      package tier1.wholeround

      fun purr(): Int = 1
      """.trimIndent(),
      provider = FailingInteropWriteProvider(),
    )

    val internalFailures: List<String> = result.kspErrors.filter { message ->
      ForwardDiagnosticKind.ERROR_INTERNAL_GENERATOR_FAILURE.name in message
    }
    assertEquals(
      1,
      internalFailures.size,
      "the whole-round catch reports the failure exactly once; kspErrors=${result.kspErrors}",
    )
    val failure: String = internalFailures.single()
    // The whole line, pinned: labelled with the module rather than a declaration, carrying the
    // failure's cause, with no source location (nothing owns it) and no `exclude(...)` advice,
    // because there is no declaration the author could exclude to unblock the build.
    assertEquals(
      "[nuget:ERROR_INTERNAL_GENERATOR_FAILURE] " +
          "${ForwardDiagnosticKind.ERROR_INTERNAL_GENERATOR_FAILURE.verb} this Kotlin module: " +
          "the generator's own invariant failed while binding it " +
          "(IOException: disk full writing Interop.cs). " +
          "this is a bug in the bridge generator, not a mistake in your Kotlin, and no single " +
          "declaration owns it, so there is nothing to exclude: report the failure with this " +
          "whole message, which carries its cause",
      failure,
    )
    assertTrue("exclude(" !in failure, "failure=$failure")
    assertEquals(
      listOf(failure),
      result.kspErrors,
      "nothing else is reported: no second copy, no bare KSP exception line",
    )
    assertTrue(
      result.generatedFiles.keys.none { path -> path.endsWith("CNameExports.kt") } &&
          result.generatedFiles.keys.none { path -> path.endsWith("Interop.cs") },
      "the round ends with neither half written; files=${result.generatedFiles.keys}",
    )
    assertEquals("PROCESSING_ERROR", result.kspExitCode, "a logged error fails the KSP run")
  }
}
