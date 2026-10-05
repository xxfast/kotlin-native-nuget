package io.github.xxfast.kotlin.native.nuget

import io.github.xxfast.kotlin.native.nuget.rir.RirAssembly
import io.github.xxfast.kotlin.native.nuget.rir.RirClass
import io.github.xxfast.kotlin.native.nuget.rir.RirFile
import io.github.xxfast.kotlin.native.nuget.rir.RirInstantiation
import io.github.xxfast.kotlin.native.nuget.rir.RirInterface
import io.github.xxfast.kotlin.native.nuget.rir.RirMethod
import io.github.xxfast.kotlin.native.nuget.rir.RirNamespace
import io.github.xxfast.kotlin.native.nuget.rir.RirObjectHandleType
import io.github.xxfast.kotlin.native.nuget.rir.RirParameter
import io.github.xxfast.kotlin.native.nuget.rir.RirPrimitiveType
import io.github.xxfast.kotlin.native.nuget.rir.RirStruct
import org.gradle.api.Project
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.testfixtures.ProjectBuilder
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.SharedLibrary
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class NugetCoexistenceTest {
  private val value = RirPrimitiveType("int")
  private val member = RirMethod(name = "Read", isStatic = true, returnType = value)
  private val ir = RirFile(assemblies = listOf(RirAssembly(
    packageId = "Dependency", assemblyName = "Dependency", namespaces = listOf(RirNamespace(
      name = "Dependency.Models", types = listOf(
        RirClass(name = "Reader", methods = listOf(member)),
        RirClass(name = "Box", typeParameters = listOf("T"),
          instantiations = listOf(RirInstantiation(listOf(value))), methods = listOf(member)),
        RirInterface(name = "IReader", methods = listOf(member.copy(isStatic = false))),
        RirStruct(name = "Point", methods = listOf(member)),
      ),
    )),
  )))

  @Test
  fun `relocated class thunks qualify own and referenced equal simple names`() {
    val types = ir.copy(assemblies = listOf(RirAssembly(
      packageId = "Dependency", assemblyName = "Dependency", namespaces = listOf(RirNamespace(
        name = "Dependency.Models", types = listOf(RirClass(name = "Reader", methods = listOf(
          member.copy(isStatic = false, parameters = listOf(
            RirParameter("other", RirObjectHandleType("Dependency.Other", "Reader")),
          )),
        ))),
      ), RirNamespace(name = "Dependency.Other", types = listOf(RirClass(name = "Reader")))),
    )))
    val generated = generateCSharpShims(types, "native_one", "Publisher.One")
      .single { it.relativePath == "ReaderRegistration.cs" }.content
    assertContains(generated, "global::Dependency.Models.Reader receiver = (global::Dependency.Models.Reader)")
    assertContains(generated, "(global::Dependency.Other.Reader)GCHandle.FromIntPtr(otherHandle)")
  }

  @Test
  fun `all reverse branches and helpers are publisher scoped`() {
    val generated = generateCSharpShims(ir, "native_one", "Publisher.One")
    generated.forEach { file ->
      val expected = if (file.relativePath == "NugetTrace.cs" ||
        file.relativePath == "NugetRuntimeRegistration.cs") "Publisher.One.NugetReverse"
      else "Publisher.One.NugetReverse.Dependency.Models"
      assertContains(file.content, "namespace $expected", message = file.relativePath)
      assertFalse(file.content.contains("IoGithubXxfast.KotlinNativeNuget"), file.relativePath)
    }
    assertEquals(6, generated.size, "class, generic witness, interface, struct, runtime, trace")
  }

  @Test
  fun `different publishers can bind identical dependencies without declaring identical namespaces`() {
    val first = generateCSharpShims(ir, "native_one", "Publisher.One")
    val second = generateCSharpShims(ir, "native_two", "Publisher.Two")
    first.zip(second).forEach { (a, b) ->
      assertFalse(a.content.substringAfter("namespace ").substringBefore('\n') ==
        b.content.substringAfter("namespace ").substringBefore('\n'), a.relativePath)
    }
  }

  @Test
  fun `hermetic compile references compatible contract major and retains exact managed dependencies`() {
    val csproj = generateCheckCsproj(emptyList(), mapOf("Dependency" to "4.0.0"), listOf("local-feed"))
    assertContains(csproj, "<PackageReference Include=\"Kotlin.Native.Interop\" Version=\"[1.0.0,2.0.0)\" />")
    assertContains(csproj, "<PackageReference Include=\"Dependency\" Version=\"[4.0.0]\" />")
    assertContains(csproj, "local-feed")
  }

  private fun pack(nativeFiles: List<String> = emptyList(), prebuilt: Boolean = false): Pair<PackNugetTask, File> {
    val task = ProjectBuilder.builder().build().tasks.create("packNuget", PackNugetTask::class.java)
    val output = Files.createTempDirectory("coexist-pack").toFile()
    task.packageId.set("TestLibrary")
    task.packageVersion.set("1.0.0")
    task.authors.set("Test")
    task.packageDescription.set("Test")
    task.dependencyVersions.set(emptyMap())
    task.nativeLibDirs.set(emptyMap())
    task.outputDir.set(output)
    if (nativeFiles.isNotEmpty()) {
      val root = Files.createTempDirectory("coexist-native").toFile()
      val native = if (prebuilt) File(root, "win-x64/native") else root
      native.mkdirs()
      nativeFiles.forEach { File(native, it).writeText("binary") }
      val contract = if (prebuilt) native.parentFile else root
      writeProducerContract(contract)
      if (!prebuilt) task.localContractDirs.set(mapOf("win-x64" to contract.path))
      if (prebuilt) task.prebuiltRuntimesDir.set(root)
      else task.nativeLibDirs.set(mapOf("win-x64" to native.path))
    }
    return task to output
  }

  @Test
  fun `every generated package declares compatible shared contract dependency`() {
    val (task, output) = pack()
    task.pack()
    assertContains(File(output, "TestLibrary.1.0.0/TestLibrary.nuspec").readText(),
      "<dependency id=\"Kotlin.Native.Interop\" version=\"[1.0.0,2.0.0)\" />")
  }

  @Test
  fun `prebuilt mismatched primary native identity fails with migration diagnostic`() {
    val (task, _) = pack(listOf("shared.dll"), prebuilt = true)
    val error = assertFailsWith<IllegalArgumentException> { task.pack() }
    assertContains(error.message.orEmpty(), "kn_testlibrary.dll")
    assertContains(error.message.orEmpty(), "win-x64")
  }

  @Test
  fun `prebuilt auxiliary native files fail instead of colliding in consumers`() {
    val (task, _) = pack(listOf("kn_testlibrary.dll", "auxiliary.dll"), prebuilt = true)
    val error = assertFailsWith<IllegalArgumentException> { task.pack() }
    assertContains(error.message.orEmpty(), "auxiliary.dll")
  }

  // #469: link output keeps a library left behind under an old baseName; only the expected one ships.
  @Test
  fun `local output ships only the expected library beside a leftover`() {
    val (task, output) = pack(listOf("kn_testlibrary.dll", "shared.dll"))
    task.pack()
    val native = File(output, "TestLibrary.1.0.0/runtimes/win-x64/native")
    assertEquals(listOf("kn_testlibrary.dll"), native.list()?.toList())
  }

  @Test
  fun `local output without the expected library names the leftover`() {
    val (task, _) = pack(listOf("shared.dll"))
    val error = assertFailsWith<IllegalArgumentException> { task.pack() }
    val message: String = error.message.orEmpty()
    assertContains(message, "kn_testlibrary.dll")
    assertContains(message, "win-x64")
    assertContains(message, "shared.dll")
    assertContains(message, "ADR-178")
  }

  private fun publishing(id: String, configure: SharedLibrary.() -> Unit = {}): Pair<Project, KotlinNativeTarget> {
    val project = ProjectBuilder.builder().withName("fixture").build()
    project.plugins.apply("org.jetbrains.kotlin.multiplatform")
    project.plugins.apply("io.github.xxfast.kotlin.native.nuget")
    val kotlin = project.extensions.getByType(KotlinMultiplatformExtension::class.java)
    val target = kotlin.mingwX64 { binaries.sharedLib { configure() } }
    project.tasks.withType(NugetCompileInteropTask::class.java).configureEach { task ->
      task.dependencySources.add("local-contract-feed")
    }
    project.extensions.getByType(NugetExtension::class.java).publish {
      it.packageId.set(id)
      it.version.set("1.0.0")
      it.authors.set("Test")
      it.description.set("Test")
    }
    return project to target
  }

  @Test
  fun `publish derives native stem before native name providers resolve it`() {
    val (project, target) = publishing("Test.Library-2")
    (project as ProjectInternal).evaluate()
    target.binaries.filterIsInstance<SharedLibrary>().forEach { lib ->
      assertEquals("kn_test_library_2", lib.baseName)
    }
    assertContains(project.tasks.named("nugetCompileInterop", NugetCompileInteropTask::class.java)
      .get().dependencySources.get(), "local-contract-feed")
  }

  @Test
  fun `an explicit baseName equal to the derived stem is accepted`() {
    val (project, target) = publishing("Test.Library-2") { baseName = "kn_test_library_2" }
    (project as ProjectInternal).evaluate()
    assertEquals("kn_test_library_2", target.binaries.filterIsInstance<SharedLibrary>().first().baseName)
  }

  // #469: KGP's default is the project name for an unprefixed sharedLib and the prefix otherwise.
  @Test
  fun `a prefixed sharedLib keeps no explicit baseName`() {
    val project = ProjectBuilder.builder().withName("fixture").build()
    project.plugins.apply("org.jetbrains.kotlin.multiplatform")
    project.plugins.apply("io.github.xxfast.kotlin.native.nuget")
    val kotlin = project.extensions.getByType(KotlinMultiplatformExtension::class.java)
    val target = kotlin.mingwX64 { binaries.sharedLib("extra") }
    assertEquals("extra", target.binaries.filterIsInstance<SharedLibrary>().first().baseName)
    project.extensions.getByType(NugetExtension::class.java).publish { it.packageId.set("Test.Library-2") }
    (project as ProjectInternal).evaluate()
    assertEquals("kn_test_library_2", target.binaries.filterIsInstance<SharedLibrary>().first().baseName)
  }

  @Test
  fun `an explicit baseName that disagrees with the derived stem fails the build`() {
    val (project, _) = publishing("Test.Library-2") { baseName = "shared" }
    val error = assertFails { (project as ProjectInternal).evaluate() }
    val message: String = generateSequence(error) { it.cause }.mapNotNull { it.message }
      .first { it.startsWith("[nuget]") }
    assertContains(message, "mingwX64")
    assertContains(message, "'shared'")
    assertContains(message, "'kn_test_library_2'")
    assertContains(message, "packageId")
  }
}
