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
import kotlin.test.assertFailsWith
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
      it.packageId.set("TestLibrary")
      it.version.set("1.0.0")
      it.authors.set("Test Author")
      it.description.set("Test description")
      it.prebuiltRuntimes.set(root)
    }

    project.evaluate()

    val packNuget = project.tasks.getByName("packNuget") as PackNugetTask
    assertEquals(root.absolutePath, packNuget.prebuiltRuntimesDir.get().asFile.absolutePath)
  }

  @Test
  fun `prebuiltRuntimesDir is absent when prebuiltRuntimes is not set`() {
    val project: Project = buildProject()

    project.extensions.getByType(NugetExtension::class.java).publish {
      it.packageId.set("TestLibrary")
      it.version.set("1.0.0")
      it.authors.set("Test Author")
      it.description.set("Test description")
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
      it.packageId.set("TestLibrary")
      it.version.set("1.0.0")
      it.authors.set("Test Author")
      it.description.set("Test description")
      it.prebuiltRuntimes.set(prebuiltTree())
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
      it.packageId.set("TestLibrary")
      it.version.set("1.0.0")
      it.authors.set("Test Author")
      it.description.set("Test description")
      it.prebuiltRuntimes.set(prebuiltTree())
    }

    project.evaluate()

    val packNuget = project.tasks.findByName("packNuget")
    assertNotNull(packNuget, "a pack-only host must still get a packNuget task")
    assertTrue((packNuget as PackNugetTask).nativeLibDirs.get().isEmpty())
  }

  @Test
  fun `packNuget fails at execution when every local link is disabled and nothing is prebuilt`() {
    val project: Project = buildProject()
    project.disableLinkTasks("mingwX64")
    project.disableLinkTasks("macosArm64")

    project.extensions.getByType(NugetExtension::class.java).publish {
      it.packageId.set("TestLibrary")
      it.version.set("1.0.0")
      it.authors.set("Test Author")
      it.description.set("Test description")
    }

    project.evaluate()

    // ADR-180 gate decision 2: registered on every `publish {}` project, failing when it runs
    // instead of being silently absent.
    val packNuget = project.tasks.getByName("packNuget") as PackNugetTask
    assertEquals(setOf("win-x64", "osx-arm64"), packNuget.skippedRids.get().keys)
    val error = assertFailsWith<IllegalStateException> { packNuget.pack() }
    assertTrue(
      error.message.orEmpty().contains("No native library to pack"),
      "with no local RID and no prebuilt input there is nothing to pack: ${error.message}",
    )
  }
  @Test fun `compile and pack baseline excludes disabled first target`() {
    val project = buildProject()
    project.disableLinkTasks("mingwX64")
    val kotlin = project.extensions.getByType(KotlinMultiplatformExtension::class.java)
    (kotlin.targets.getByName("macosArm64") as KotlinNativeTarget).binaries.filterIsInstance<SharedLibrary>()
      .forEach { it.linkTaskProvider.configure { link -> link.enabled = true } }
    project.extensions.getByType(NugetExtension::class.java).publish { it.packageId.set("TestLibrary") }
    project.evaluate()
    val pack = project.tasks.getByName("packNuget") as PackNugetTask
    val compile = project.tasks.getByName("nugetCompileInterop") as NugetCompileInteropTask
    assertEquals(setOf("osx-arm64"), pack.localContractDirs.get().keys)
    assertTrue(pack.generatedCsDirs.files.single().path.contains("macosArm64"))
    assertEquals(pack.generatedCsDirs.files, compile.generatedCsDirs.files)
    val names = pack.taskDependencies.getDependencies(pack).map { it.name }
    assertTrue("kspKotlinMacosArm64" in names, names.toString())
    assertFalse("kspKotlinMingwX64" in names, names.toString())
  }
  @Test fun `all packaged local targets depend on their KSP and track contracts`() {
    val project = buildProject()
    val kotlin = project.extensions.getByType(KotlinMultiplatformExtension::class.java)
    kotlin.targets.filterIsInstance<KotlinNativeTarget>().forEach { target ->
      target.binaries.filterIsInstance<SharedLibrary>().forEach { it.linkTaskProvider.configure { link -> link.enabled = true } }
    }
    project.extensions.getByType(NugetExtension::class.java).publish { it.packageId.set("TestLibrary") }
    project.evaluate()
    val pack = project.tasks.getByName("packNuget") as PackNugetTask
    assertEquals(setOf("osx-arm64", "win-x64"), pack.localContractDirs.get().keys)
    val names = pack.taskDependencies.getDependencies(pack).map { it.name }
    assertTrue("kspKotlinMacosArm64" in names && "kspKotlinMingwX64" in names, names.toString())
    pack.localContractDirs.get().values.forEach { path -> writeProducerContract(File(path)) }
    assertTrue(pack.contractFiles.files.count { it.name == "ForwardAbi.json" } == 2)
  }
  @Test fun `prebuilt only baseline is stable and never requires disabled KSP`() {
    val project = buildProject()
    project.disableLinkTasks("mingwX64")
    project.disableLinkTasks("macosArm64")
    val root = prebuiltTree()
    File(root, "aaa-new-rid").mkdirs()
    project.extensions.getByType(NugetExtension::class.java).publish {
      it.packageId.set("TestLibrary")
      it.prebuiltRuntimes.set(root)
    }
    project.evaluate()
    val pack = project.tasks.getByName("packNuget") as PackNugetTask
    val compile = project.tasks.getByName("nugetCompileInterop") as NugetCompileInteropTask
    assertEquals(setOf(File(root, "aaa-new-rid")), pack.generatedCsDirs.files)
    assertEquals(pack.generatedCsDirs.files, compile.generatedCsDirs.files)
    assertTrue(pack.taskDependencies.getDependencies(pack).none { it.name.startsWith("kspKotlin") })
  }

}
