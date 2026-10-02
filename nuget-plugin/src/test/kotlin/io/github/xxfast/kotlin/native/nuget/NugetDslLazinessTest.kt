package io.github.xxfast.kotlin.native.nuget

import com.google.devtools.ksp.gradle.KspExtension
import org.gradle.api.Project
import org.gradle.api.Task
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ADR-180: the DSL registers on declaration and wires values as Providers, so nothing waits for
 * `afterEvaluate` (except ADR-178's `baseName` stamp) and a value set after a block still reaches
 * the task.
 */
class NugetDslLazinessTest {
  private fun project(): Project {
    val project: Project = ProjectBuilder.builder().build()
    project.plugins.apply("org.jetbrains.kotlin.multiplatform")
    project.plugins.apply("io.github.xxfast.kotlin.native.nuget")
    project.extensions.getByType(KotlinMultiplatformExtension::class.java)
      .mingwX64 { target -> target.binaries { sharedLib { baseName = "test" } } }
    return project
  }

  private fun Project.nuget(): NugetExtension = extensions.getByType(NugetExtension::class.java)

  private fun Task.dependencyNames(): Set<String> =
    taskDependencies.getDependencies(this).map { it.name }.toSet()

  @Test
  fun `a second publish block merges and packNuget exists without evaluate`() {
    val project: Project = project()
    val nuget: NugetExtension = project.nuget()

    nuget.publish { pub -> pub.packageId.set("MyLib"); pub.include("a.b") }
    nuget.publish { pub -> pub.version.set("1.0.0"); pub.include("a.c") }

    // No evaluate(): registration no longer waits for afterEvaluate.
    assertNotNull(project.tasks.findByName("packNuget"))
    assertEquals(listOf("a.b", "a.c"), nuget.publish.include.get())
    assertEquals("MyLib", nuget.publish.packageId.get())

    // Values set AFTER wiring still reach the task: the provider chain, not a snapshot.
    nuget.publish { pub -> pub.packageId.set("Renamed") }
    val pack = project.tasks.getByName("packNuget") as PackNugetTask
    assertEquals("Renamed", pack.packageId.get())
    assertEquals("1.0.0", pack.packageVersion.get())

    val ksp: KspExtension = project.extensions.getByType(KspExtension::class.java)
    assertEquals("a.b,a.c", ksp.arguments["nuget.includePackages"])
    assertEquals("Renamed", ksp.arguments["nuget.namespace"])
  }

  @Test
  fun `a provider feeds packageId`() {
    val project: Project = project()
    val id = project.objects.property(String::class.java)

    project.nuget().publish { pub -> pub.packageId.set(id) }
    id.set("FromProvider")

    val pack = project.tasks.getByName("packNuget") as PackNugetTask
    assertEquals("FromProvider", pack.packageId.get())
  }

  @Test
  fun `a consume-only project still gets no packNuget`() {
    val project: Project = project()
    project.nuget().dependencies { deps -> deps.dependency("Some.Package", "1.0.0") }

    assertNotNull(project.tasks.findByName("nugetRestore"))
    (project as ProjectInternal).evaluate()
    assertNull(project.tasks.findByName("packNuget"))
  }

  @Test
  fun `an empty dependencies block registers nothing`() {
    val project: Project = project()
    project.nuget().dependencies { }

    assertNull(project.tasks.findByName("nugetGen"))
  }

  @Test
  fun `a second dependency call with the same id yields one PackageReference`() {
    val project: Project = project()
    project.nuget().dependencies { deps ->
      deps.dependency("Acme", "1.0.0")
      deps.dependency("Acme") { dep -> dep.source.set("https://feed") }
    }

    val gen = project.tasks.getByName("nugetGen") as NugetGenTask
    assertEquals(listOf("Acme"), gen.dependencyIds.get())
    assertEquals(mapOf("Acme" to "1.0.0"), gen.dependencyVersions.get())
    assertEquals(mapOf("Acme" to "https://feed"), gen.dependencySources.get())
  }

  @Test
  fun `the first bind registers the reverse tasks without evaluate`() {
    val project: Project = project()
    project.nuget().dependencies { deps ->
      deps.dependency("Acme", "1.0.0") { dep -> dep.bind { bind -> bind.include("Acme.Core") } }
    }

    val extract = project.tasks.getByName("nugetExtractApi") as NugetExtractApiTask
    assertEquals(listOf("Acme"), extract.boundPackageIds.get())
    assertEquals(mapOf("Acme" to listOf("Acme.Core")), extract.namespaceIncludes.get())
    assertNotNull(project.tasks.findByName("nugetGenerateShims"))
  }

  // The silent-wrong-output trap: `nativeMain` does not exist while the build script runs, so the
  // srcDir must be wired through `matching {}.configureEach {}`.
  @Test
  fun `bind wires the generated Kotlin into nativeMain`() {
    val project: Project = project()
    project.nuget().dependencies { deps ->
      deps.dependency("Acme", "1.0.0") { dep -> dep.bind { } }
    }

    (project as ProjectInternal).evaluate()

    val kotlin = project.extensions.getByType(KotlinMultiplatformExtension::class.java)
    val dirs: Set<File> = kotlin.sourceSets.getByName("nativeMain").kotlin.srcDirs
    assertTrue(
      dirs.any { it.invariantSeparatorsPath.endsWith("nuget-interop/kotlin/nativeMain") },
      "nativeMain must carry the generated reverse stubs, was $dirs",
    )
    val mingw: Set<File> = kotlin.sourceSets.getByName("mingwX64Main").kotlin.srcDirs
    assertTrue(
      mingw.any { it.invariantSeparatorsPath.endsWith("nuget-interop/kotlin/mingwMain") },
      "mingwX64Main must carry the mingw stubs, was $mingw",
    )
  }

  @Test
  fun `bind and publish in either order merge shims into packNuget`() {
    val project: Project = project()
    project.nuget().dependencies { deps ->
      deps.dependency("Acme", "1.0.0") { dep -> dep.bind { } }
    }
    project.nuget().publish { pub -> pub.packageId.set("MyLib"); pub.version.set("1.0.0") }

    val pack: Task = project.tasks.getByName("packNuget")
    assertTrue("nugetGenerateShims" in pack.dependencyNames())
  }

  @Test
  fun `a repository declared in a second publish block gets its task`() {
    val project: Project = project()
    project.nuget().publish { pub -> pub.packageId.set("MyLib") }
    project.nuget().publish { pub ->
      pub.repositories { repos -> repos.nuget("feed") { repo -> repo.url.set("https://f") } }
    }

    val task = project.tasks.getByName("publishNugetToFeedRepository") as PublishNugetTask
    assertEquals("https://f", task.repositoryUrl.get())
    assertTrue("publishNugetToFeedRepository" in
      project.tasks.getByName("publishNuget").dependencyNames())
  }

  @Test
  fun `a repository without url fails when its task runs, not at configuration`() {
    val project: Project = project()
    project.nuget().publish { pub ->
      pub.packageId.set("MyLib")
      pub.repositories { repos -> repos.nuget("feed") { } }
    }
    (project as ProjectInternal).evaluate()

    val task = project.tasks.getByName("publishNugetToFeedRepository") as PublishNugetTask
    val error = assertFailsWith<IllegalArgumentException> { task.publish() }
    assertTrue(error.message.orEmpty().contains("v3 service index url"), "${error.message}")
  }

  @Test
  fun `versionPropsFile defaults from packageId and accepts an override`() {
    val project: Project = project()
    val nuget: NugetExtension = project.nuget()
    nuget.publish { pub -> pub.packageId.set("MyLib") }

    assertEquals("MyLibVersions.props", nuget.publish.versionPropsFile.get().asFile.name)

    val override: File = File(Files.createTempDirectory("props").toFile(), "Custom.props")
    nuget.publish { pub -> pub.versionPropsFile.set(override) }
    val props =
      project.tasks.getByName("nugetSnapshotVersionProps") as NugetSnapshotVersionPropsTask
    assertEquals(override.absolutePath, props.outputFile.get().asFile.absolutePath)
  }

  @Test
  fun `a publish project with no supported target gets a packNuget that fails at execution`() {
    val project: Project = ProjectBuilder.builder().build()
    project.plugins.apply("org.jetbrains.kotlin.multiplatform")
    project.plugins.apply("io.github.xxfast.kotlin.native.nuget")
    project.extensions.getByType(KotlinMultiplatformExtension::class.java).jvm()
    project.nuget().publish { pub ->
      pub.packageId.set("MyLib"); pub.version.set("1.0.0")
      pub.authors.set("A"); pub.description.set("D")
    }
    (project as ProjectInternal).evaluate()

    val pack = project.tasks.getByName("packNuget") as PackNugetTask
    assertFalse(pack.hasSupportedTargets.get())
    val error = assertFailsWith<IllegalStateException> { pack.pack() }
    assertTrue(error.message.orEmpty().contains("No supported native targets"), "${error.message}")
  }

  // Pre-existing bug split out of ADR-180: the reverse `freeManagedString` (mingwMain) calls
  // `CoTaskMemFree` from ole32, but `-lole32` used to be added only on `publish {}` projects.
  @Test
  fun `a consume-only mingw shared library links ole32`() {
    val project: Project = project()
    project.nuget().dependencies { deps ->
      deps.dependency("Acme", "1.0.0") { dep -> dep.bind { } }
    }
    (project as ProjectInternal).evaluate()

    val kotlin = project.extensions.getByType(KotlinMultiplatformExtension::class.java)
    val library: SharedLibrary = (kotlin.targets.getByName("mingwX64") as KotlinNativeTarget)
      .binaries.filterIsInstance<SharedLibrary>().first()
    assertTrue("-lole32" in library.linkerOpts, "was ${library.linkerOpts}")
  }

  @Test
  fun `a publishing mingw shared library links ole32 exactly once`() {
    val project: Project = project()
    project.nuget().publish { pub -> pub.packageId.set("MyLib") }
    (project as ProjectInternal).evaluate()

    val kotlin = project.extensions.getByType(KotlinMultiplatformExtension::class.java)
    val library: SharedLibrary = (kotlin.targets.getByName("mingwX64") as KotlinNativeTarget)
      .binaries.filterIsInstance<SharedLibrary>().first()
    assertEquals(1, library.linkerOpts.count { it == "-lole32" }, "was ${library.linkerOpts}")
  }

  @Test
  fun `the ADR-178 baseName stamp still reaches the KSP libraryName`() {
    val project: Project = project()
    project.nuget().publish { pub -> pub.packageId.set("MyLib") }
    (project as ProjectInternal).evaluate()

    val kotlin = project.extensions.getByType(KotlinMultiplatformExtension::class.java)
    val baseName: String = (kotlin.targets.getByName("mingwX64") as KotlinNativeTarget)
      .binaries.filterIsInstance<SharedLibrary>().first().baseName
    val ksp: KspExtension = project.extensions.getByType(KspExtension::class.java)
    assertEquals(baseName, ksp.arguments["nuget.libraryName"])
    assertEquals(nativeLibraryStem("MyLib"), baseName)
  }
}
