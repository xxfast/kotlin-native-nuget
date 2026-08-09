package io.github.xxfast.kotlin.native.nuget

import com.google.devtools.ksp.gradle.KspExtension
import org.gradle.api.Project
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.testfixtures.ProjectBuilder
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ADR-063: `publish { include(...); exclude(...) }` must be wired through to the KSP processor as
 * `nuget.includePackages` / `nuget.excludePackages` args (comma-joined), alongside the existing
 * `nuget.rootPackage` arg (`NugetPlugin.kt:245-248`).
 *
 * Mirrors the `buildProjectWithSharedLib` helper from [NugetPluginComposabilityTest]: the KMP
 * plugin must be applied with a `binaries { sharedLib {} }` target configured to reach the
 * `publish {}`/KSP-arg-wiring `afterEvaluate` block.
 */
class NugetPluginKspArgsWiringTest {
  private fun buildProjectWithSharedLib(): Project {
    val project: Project = ProjectBuilder.builder().build()
    project.plugins.apply("org.jetbrains.kotlin.multiplatform")
    project.plugins.apply("io.github.xxfast.kotlin.native.nuget")

    val kotlin: KotlinMultiplatformExtension =
      project.extensions.getByType(KotlinMultiplatformExtension::class.java)
    kotlin.mingwX64 {
      binaries {
        sharedLib {
          baseName = "test"
        }
      }
    }

    return project
  }

  private fun Project.evaluate() {
    (this as ProjectInternal).evaluate()
  }

  @Test
  fun `publish include and exclude are wired as comma-joined KSP args`() {
    val project: Project = buildProjectWithSharedLib()

    project.extensions.getByType(NugetExtension::class.java).publish {
      packageId = "TestLibrary"
      version = "1.0.0"
      authors = "Test Author"
      description = "Test description"
      rootPackage = "com.contoso.api"
      include("com.contoso.api")
      include("com.contoso.extra")
      exclude("com.contoso.api.internal")
    }

    project.evaluate()

    val ksp: KspExtension = project.extensions.getByType(KspExtension::class.java)
    val args: Map<String, String> = ksp.arguments

    assertEquals("com.contoso.api", args["nuget.rootPackage"])
    assertEquals("com.contoso.api,com.contoso.extra", args["nuget.includePackages"])
    assertEquals("com.contoso.api.internal", args["nuget.excludePackages"])
  }

  @Test
  fun `publish without include or exclude wires empty KSP args`() {
    val project: Project = buildProjectWithSharedLib()

    project.extensions.getByType(NugetExtension::class.java).publish {
      packageId = "TestLibrary"
      version = "1.0.0"
      authors = "Test Author"
      description = "Test description"
    }

    project.evaluate()

    val ksp: KspExtension = project.extensions.getByType(KspExtension::class.java)
    val args: Map<String, String> = ksp.arguments

    assertEquals("", args["nuget.includePackages"])
    assertEquals("", args["nuget.excludePackages"])
  }

  /**
   * ADR-063 "Reverse-bound packages are always in scope": `nuget.boundPackages` is the superset
   * of packages a bound dependency's reverse-generated stubs can land in: the namespace aliases,
   * the `packageName` override, and the sanitised `packageId` fallback, deduped.
   */
  @Test
  fun `bound dependency packages are wired as a deduped comma-joined KSP arg`() {
    val project: Project = buildProjectWithSharedLib()

    project.extensions.getByType(NugetExtension::class.java).publish {
      packageId = "TestLibrary"
      version = "1.0.0"
      authors = "Test Author"
      description = "Test description"
    }

    project.extensions.getByType(NugetExtension::class.java).dependencies {
      dependency("Acme") {
        version = "1.0.0"
        bind {
          packageName = "acme"
          alias("Acme.Core", kotlinPackage = "acme.core")
        }
      }
    }

    project.evaluate()

    val ksp: KspExtension = project.extensions.getByType(KspExtension::class.java)
    val args: Map<String, String> = ksp.arguments
    val boundPackages: Set<String> = args.getValue("nuget.boundPackages").split(",").toSet()

    assertEquals(setOf("acme.core", "acme"), boundPackages)
  }

  /**
   * ADR-088: the manifest path rides the same channel as `nuget.boundPackages`. Absolute, because
   * KSP resolves nothing relative to the project dir, and pointed at `nugetGenerateBindings`'
   * own output file.
   */
  @Test
  fun `the bound-types manifest path is wired as a KSP arg when a dependency is bound`() {
    val project: Project = buildProjectWithSharedLib()

    project.extensions.getByType(NugetExtension::class.java).publish {
      packageId = "TestLibrary"
      version = "1.0.0"
      authors = "Test Author"
      description = "Test description"
    }

    project.extensions.getByType(NugetExtension::class.java).dependencies {
      dependency("Acme") {
        version = "1.0.0"
        bind { packageName = "acme" }
      }
    }

    project.evaluate()

    val ksp: KspExtension = project.extensions.getByType(KspExtension::class.java)
    val manifest: String = ksp.arguments.getValue("nuget.boundTypesManifest")

    assertTrue(manifest.endsWith("build/nuget-interop/bound-types.json"), manifest)
    assertTrue(File(manifest).isAbsolute, manifest)
  }

  /** No `bind {}` means no manifest task ran, so the option must stay empty rather than promise a
   *  file that will never exist. */
  @Test
  fun `the bound-types manifest arg is empty with no bound dependency`() {
    val project: Project = buildProjectWithSharedLib()

    project.extensions.getByType(NugetExtension::class.java).publish {
      packageId = "TestLibrary"
      version = "1.0.0"
      authors = "Test Author"
      description = "Test description"
    }

    project.evaluate()

    val ksp: KspExtension = project.extensions.getByType(KspExtension::class.java)
    assertEquals("", ksp.arguments["nuget.boundTypesManifest"])
  }
}
