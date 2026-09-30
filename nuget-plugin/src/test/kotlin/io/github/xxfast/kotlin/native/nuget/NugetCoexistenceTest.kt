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
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.testfixtures.ProjectBuilder
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.SharedLibrary
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
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
  fun `local and prebuilt mismatched primary native identity fails with migration diagnostic`() {
    listOf(false, true).forEach { prebuilt ->
      val (task, _) = pack(listOf("shared.dll"), prebuilt)
      val error = assertFailsWith<IllegalArgumentException> { task.pack() }
      assertContains(error.message.orEmpty(), "kn_746573746c696272617279.dll")
      assertContains(error.message.orEmpty(), "win-x64")
    }
  }

  @Test
  fun `local and prebuilt auxiliary native files fail instead of colliding in consumers`() {
    listOf(false, true).forEach { prebuilt ->
      val (task, _) = pack(listOf("kn_746573746c696272617279.dll", "auxiliary.dll"), prebuilt)
      val error = assertFailsWith<IllegalArgumentException> { task.pack() }
      assertContains(error.message.orEmpty(), "auxiliary.dll")
    }
  }

  @Test
  fun `publish derives native stem before native name providers resolve it`() {
    val project = ProjectBuilder.builder().build()
    project.plugins.apply("org.jetbrains.kotlin.multiplatform")
    project.plugins.apply("io.github.xxfast.kotlin.native.nuget")
    val kotlin = project.extensions.getByType(KotlinMultiplatformExtension::class.java)
    val target = kotlin.mingwX64 { binaries.sharedLib { baseName = "shared" } }
    project.tasks.withType(NugetCompileInteropTask::class.java).configureEach { task ->
      task.dependencySources.add("local-contract-feed")
    }
    project.extensions.getByType(NugetExtension::class.java).publish {
      packageId = "Test.Library-2"; version = "1.0.0"; authors = "Test"; description = "Test"
    }
    (project as ProjectInternal).evaluate()
    assertEquals("kn_746573742e6c6962726172792d32", target.binaries.filterIsInstance<SharedLibrary>().first().baseName)
    assertContains(project.tasks.named("nugetCompileInterop", NugetCompileInteropTask::class.java)
      .get().dependencySources.get(), "local-contract-feed")
  }
}
