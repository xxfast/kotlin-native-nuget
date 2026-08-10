package io.github.xxfast.kotlin.native.nuget

import org.gradle.api.Project
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.testfixtures.ProjectBuilder
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.SharedLibrary
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ADR-093: `publish { prebuiltRuntimes = dir }` must reach `packNuget.prebuiltRuntimesDir`, and a
 * target whose link task is disabled on this host must be excluded from `nativeLibDirs` at
 * configuration time rather than silently skipped at execution time.
 */
class NugetPluginPrebuiltRuntimesWiringTest {
  private fun buildProject(): Project {
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
    kotlin.macosArm64 {
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

  private fun Project.disableLinkTasks(targetName: String) {
    val kotlin: KotlinMultiplatformExtension =
      extensions.getByType(KotlinMultiplatformExtension::class.java)
    val target: KotlinNativeTarget = kotlin.targets.getByName(targetName) as KotlinNativeTarget
    target.binaries
      .filterIsInstance<SharedLibrary>()
      .forEach { lib -> lib.linkTaskProvider.configure { it.enabled = false } }
  }

  private fun prebuiltTree(): File {
    val root: File = Files.createTempDirectory("prebuilt").toFile()
    val native = File(root, "win-x64/native")
    native.mkdirs()
    File(native, "test.dll").writeText("fake native binary")
    return root
  }

  @Test
  fun `prebuiltRuntimes reaches packNuget prebuiltRuntimesDir`() {
    val project: Project = buildProject()
    val root: File = prebuiltTree()

    project.extensions.getByType(NugetExtension::class.java).publish {
      packageId = "TestLibrary"
      version = "1.0.0"
      authors = "Test Author"
      description = "Test description"
      prebuiltRuntimes = root
    }

    project.evaluate()

    val packNuget = project.tasks.getByName("packNuget") as PackNugetTask
    assertEquals(root.absolutePath, packNuget.prebuiltRuntimesDir.get().asFile.absolutePath)
  }

  @Test
  fun `prebuiltRuntimesDir is absent when prebuiltRuntimes is not set`() {
    val project: Project = buildProject()

    project.extensions.getByType(NugetExtension::class.java).publish {
      packageId = "TestLibrary"
      version = "1.0.0"
      authors = "Test Author"
      description = "Test description"
    }

    project.evaluate()

    val packNuget = project.tasks.getByName("packNuget") as PackNugetTask
    assertFalse(
      packNuget.prebuiltRuntimesDir.isPresent,
      "an unset prebuiltRuntimes must leave the optional input absent",
    )
  }

  @Test
  fun `a target whose link task is disabled is excluded from nativeLibDirs`() {
    val project: Project = buildProject()
    project.disableLinkTasks("mingwX64")

    project.extensions.getByType(NugetExtension::class.java).publish {
      packageId = "TestLibrary"
      version = "1.0.0"
      authors = "Test Author"
      description = "Test description"
      prebuiltRuntimes = prebuiltTree()
    }

    project.evaluate()

    val packNuget = project.tasks.getByName("packNuget") as PackNugetTask
    val rids: Set<String> = packNuget.nativeLibDirs.get().keys

    assertFalse(
      rids.contains("win-x64"),
      "a disabled link task must not contribute a RID this host cannot produce, was $rids",
    )
  }

  @Test
  fun `packNuget is still registered when every local link is disabled but prebuilt is set`() {
    val project: Project = buildProject()
    project.disableLinkTasks("mingwX64")
    project.disableLinkTasks("macosArm64")

    project.extensions.getByType(NugetExtension::class.java).publish {
      packageId = "TestLibrary"
      version = "1.0.0"
      authors = "Test Author"
      description = "Test description"
      prebuiltRuntimes = prebuiltTree()
    }

    project.evaluate()

    val packNuget = project.tasks.findByName("packNuget")
    assertNotNull(packNuget, "a pack-only host must still get a packNuget task")
    assertTrue((packNuget as PackNugetTask).nativeLibDirs.get().isEmpty())
  }

  @Test
  fun `packNuget is not registered when every local link is disabled and nothing is prebuilt`() {
    val project: Project = buildProject()
    project.disableLinkTasks("mingwX64")
    project.disableLinkTasks("macosArm64")

    project.extensions.getByType(NugetExtension::class.java).publish {
      packageId = "TestLibrary"
      version = "1.0.0"
      authors = "Test Author"
      description = "Test description"
    }

    project.evaluate()

    assertFalse(
      project.tasks.names.contains("packNuget"),
      "with no local RID and no prebuilt input there is nothing to pack",
    )
  }
}
