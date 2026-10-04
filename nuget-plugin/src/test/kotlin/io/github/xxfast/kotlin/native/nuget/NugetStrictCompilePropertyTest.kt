package io.github.xxfast.kotlin.native.nuget

import org.gradle.api.GradleException
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Real Gradle property resolution, which ProjectBuilder does not supply. No native build runs. */
class NugetStrictCompilePropertyTest {
  @Test
  fun `the CI switch accepts only exact Boolean spellings`() {
    assertTrue(parseStrictCompileCheck("true"))
    assertFalse(parseStrictCompileCheck("false"))
    listOf("", "TRUE", " false ", "1").forEach { value ->
      val failure: GradleException = assertFailsWith { parseStrictCompileCheck(value) }
      assertContains(failure.message.orEmpty(), "nuget.strictCompileCheck")
      assertContains(failure.message.orEmpty(), "true or false")
    }
  }

  private fun fixture(dir: File, explicit: Boolean? = null) {
    File(dir, "settings.gradle").writeText("rootProject.name = 'strict-compile-probe'")
    val setting: String = if (explicit == null) "" else "strictCompileCheck.set($explicit)"
    File(dir, "build.gradle").writeText(
      """
      plugins {
        id 'org.jetbrains.kotlin.multiplatform'
        id 'io.github.xxfast.kotlin.native.nuget'
      }
      nuget {
        publish {
          packageId.set('StrictProbe')
          version.set('1.0.0')
          authors.set('Test')
          description.set('Test')
          $setting
        }
      }
      def strict = tasks.named('nugetCompileInterop').get().strictCompileCheck
      tasks.register('printStrict') {
        inputs.property('strict', strict)
        doLast { current -> println('STRICT=' + current.inputs.properties.strict) }
      }
      """.trimIndent(),
    )
  }

  private fun runner(dir: File, value: String? = null): GradleRunner {
    val args: MutableList<String> =
      mutableListOf("printStrict", "--configuration-cache", "--stacktrace")
    if (value != null) args.add("-Pnuget.strictCompileCheck=$value")
    return GradleRunner.create().withProjectDir(dir).withPluginClasspath().withArguments(args)
  }

  @Test
  fun `CI enables strict checking and configuration cache reuses it`(@TempDir dir: File) {
    fixture(dir)
    assertContains(runner(dir, "true").build().output, "STRICT=true")
    val reused: BuildResult = runner(dir, "true").build()
    assertContains(reused.output, "Reusing configuration cache")
    assertContains(reused.output, "STRICT=true")
  }

  @Test
  fun `the default and explicit CI false stay false`(@TempDir dir: File) {
    fixture(dir)
    assertContains(runner(dir).build().output, "STRICT=false")
    assertContains(runner(dir, "false").build().output, "STRICT=false")
  }

  @Test
  fun `explicit DSL false wins over CI true and unused malformed fallback`(@TempDir dir: File) {
    fixture(dir, false)
    assertContains(runner(dir, "true").build().output, "STRICT=false")
    assertContains(runner(dir, "tru").build().output, "STRICT=false")
  }

  @Test
  fun `explicit DSL true wins over CI false`(@TempDir dir: File) {
    fixture(dir, true)
    assertContains(runner(dir, "false").build().output, "STRICT=true")
  }

  @Test
  fun `a malformed CI switch fails by name`(@TempDir dir: File) {
    fixture(dir)
    val failure: BuildResult = runner(dir, "tru").buildAndFail()
    assertContains(failure.output, "nuget.strictCompileCheck")
    assertContains(failure.output, "true or false")
  }
}
