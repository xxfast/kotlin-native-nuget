package io.github.xxfast.kotlin.native.nuget.processor.tier1

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Issue #235: no forward route may reach a member the compiler owns, and no diagnostic may name
 * one. A build-log line nobody can act on trains people to ignore the whole channel.
 *
 * Two of the shared predicate's rules are assertable in Tier 1:
 *
 * - `@Deprecated(level = DeprecationLevel.HIDDEN)`, which is plain Kotlin source, so a fixture can
 *   declare it directly on a class member, a companion member, an object member and a property.
 * - the plugin-runtime signature rule, which needs `kotlinx.serialization` on the classpath.
 *   [Tier1DependencyLibrary] supplies the two names the rule reads (`Serializable`, `KSerializer`)
 *   from a genuinely separate compilation unit, and the fixture hand-writes the `serializer()`
 *   member the plugin would otherwise synthesize. The serialization compiler plugin does not run
 *   here, so this pins the *predicate*; that the real plugin produces exactly this shape is
 *   verified against the real klib by `scripts/verify-forward-diagnostics.sh`.
 *
 * The third rule, `Origin.SYNTHETIC`, has no Tier 1 fixture: KSP shows nothing at all for an
 * in-module `@Serializable` type (the plugin lowers in IR, after KSP), and every other synthesized
 * member Kotlin makes in-module is already refused by the language rule ahead of it.
 */
class Tier1CompilerOwnedMemberTest {

  private val serializationStub: File = Tier1DependencyLibrary.compile(
    """
    package kotlinx.serialization

    @Target(AnnotationTarget.CLASS)
    annotation class Serializable

    interface KSerializer<T>
    """.trimIndent(),
    fileName = "SerializationStub.kt",
  )

  @Test
  fun `a HIDDEN-deprecated member is exported by no route and named by no diagnostic`() {
    val result = Tier1Harness.run(
      """
      package tier1.issue235

      class Machine(val serial: Int) {
        fun shown(): Int = serial

        @Deprecated("gone", level = DeprecationLevel.HIDDEN)
        fun hidden(): Int = serial

        @Deprecated("gone", level = DeprecationLevel.HIDDEN)
        val hiddenReading: Int = serial

        companion object {
          fun shownOnCompanion(): Int = 1

          @Deprecated("gone", level = DeprecationLevel.HIDDEN)
          fun hiddenOnCompanion(): Int = 2
        }
      }

      object Registry {
        fun shownOnObject(): Int = 3

        @Deprecated("gone", level = DeprecationLevel.HIDDEN)
        fun hiddenOnObject(): Int = 4
      }
      """.trimIndent(),
      processorOptions = mapOf("nuget.rootPackage" to "tier1.issue235"),
      coroutinesOnCompileClasspath = false,
    )

    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    listOf("export_machine_shown", "export_machine_companion_shown", "export_registry_shown")
      .forEach { expected ->
        assertTrue(
          expected in result.generated,
          "a hidden member must cost its neighbours nothing; expected $expected in:\n${result.generated}",
        )
      }
    assertFalse(
      "hidden" in result.generated.lowercase(),
      "no route may export a HIDDEN-deprecated member; generated:\n${result.generated}",
    )
    assertFalse(
      "Hidden" in result.generatedCSharp,
      "the C# half must not declare one either; generated C#:\n${result.generatedCSharp}",
    )
    assertTrue(
      result.kspWarnings.none { it.contains("hidden", ignoreCase = true) },
      "a member the compiler hid from the source language is the generator's business and " +
          "belongs nowhere near a build log; kspWarnings=${result.kspWarnings}",
    )
  }

  @Test
  fun `a serialization plugin member on a Serializable owner is neither planned nor reported`() {
    val result = Tier1Harness.run(
      """
      package tier1.issue235

      import kotlinx.serialization.KSerializer
      import kotlinx.serialization.Serializable

      @Serializable
      data class Crate(val label: String, val weight: Int) {
        companion object {
          fun serializer(): KSerializer<Crate> = throw IllegalStateException("stand-in")
        }
      }
      """.trimIndent(),
      processorOptions = mapOf("nuget.rootPackage" to "tier1.issue235"),
      libraries = listOf(serializationStub),
      coroutinesOnCompileClasspath = false,
    )

    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    assertTrue(
      "export_crate_get_label" in result.generated,
      "the `@Serializable` type itself still exports in full; generated:\n${result.generated}",
    )
    assertFalse(
      "serializer" in result.generated,
      "no route may reach the plugin's `serializer()`; generated:\n${result.generated}",
    )
    assertFalse(
      "Serializer" in result.generatedCSharp,
      "and the C# half must not carry it either; generated C#:\n${result.generatedCSharp}",
    )
    assertTrue(
      result.kspWarnings.none { it.contains("serializer", ignoreCase = true) },
      "nobody wrote `Crate.Companion.serializer`, so no advice about it can be followed; " +
          "kspWarnings=${result.kspWarnings}",
    )
  }
}
