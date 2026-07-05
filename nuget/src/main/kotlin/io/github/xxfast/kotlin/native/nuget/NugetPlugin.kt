package io.github.xxfast.kotlin.native.nuget

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.SharedLibrary
import java.lang.reflect.Method

private const val PLUGIN_VERSION = "0.1.0"

private val KONAN_TO_RID = mapOf(
  "mingw_x64" to "win-x64",
  "macos_arm64" to "osx-arm64",
  "macos_x64" to "osx-x64",
  "linux_x64" to "linux-x64",
  "linux_arm64" to "linux-arm64",
)

class NugetPlugin : Plugin<Project> {
  override fun apply(project: Project) {
    val extension: NugetExtension =
      project.extensions.create("nuget", NugetExtension::class.java)

    project.pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") { _ ->
      project.pluginManager.apply("com.google.devtools.ksp")

      val kotlin: KotlinMultiplatformExtension =
        project.extensions.getByType(KotlinMultiplatformExtension::class.java)

      val processorDep: Any = project.findProject(":nuget-processor")
        ?: "io.github.xxfast:nuget-processor:$PLUGIN_VERSION"

      kotlin.targets.withType(KotlinNativeTarget::class.java).configureEach { target ->
        if (target.konanTarget.name !in KONAN_TO_RID) return@configureEach

        val configName = "ksp${target.name.replaceFirstChar { it.uppercase() }}"
        project.dependencies.add(configName, processorDep)
      }

      project.pluginManager.withPlugin("com.google.devtools.ksp") { _ ->
        project.afterEvaluate { _ ->
          val pub: NugetPublishConfig = requireNotNull(extension.publish) {
            "nuget { publish { ... } } block is required to run packNuget"
          }

          val ksp: Any = project.extensions.getByType(
            Class.forName("com.google.devtools.ksp.gradle.KspExtension")
          )

          val baseName: String? = kotlin.targets
            .filterIsInstance<KotlinNativeTarget>()
            .flatMap { it.binaries.filterIsInstance<SharedLibrary>() }
            .firstOrNull()?.baseName

          val kspClass: Class<*> = ksp.javaClass
          val argMethod: Method = kspClass.getMethod("arg", String::class.java, String::class.java)

          argMethod.invoke(ksp, "nuget.libraryName", baseName ?: "library")
          argMethod.invoke(ksp, "nuget.namespace", pub.packageId ?: "")
          argMethod.invoke(ksp, "nuget.rootPackage", pub.rootPackage ?: "")
          argMethod.invoke(ksp, "nuget.className", "${pub.packageId}Native")
        }
      }

      project.afterEvaluate { _ ->
        val pub: NugetPublishConfig = requireNotNull(extension.publish) {
          "nuget { publish { ... } } block is required to run packNuget"
        }

        val nativeTargets: List<KotlinNativeTarget> =
          kotlin.targets.filterIsInstance<KotlinNativeTarget>()

        val supportedTargets: List<KotlinNativeTarget> =
          nativeTargets.filter { it.konanTarget.name in KONAN_TO_RID }

        if (supportedTargets.isEmpty()) {
          project.logger.warn(
            "w: [nuget] No supported native targets found (expected mingw or macOS). " +
              "Skipping NuGet plugin for project '${project.name}'."
          )
          return@afterEvaluate
        }

        val libDirs: MutableMap<String, String> = mutableMapOf()
        val linkTasks: MutableList<Any> = mutableListOf()
        var baseName: String? = null

        for (target in nativeTargets) {
          val rid: String = KONAN_TO_RID[target.konanTarget.name] ?: continue

          if (target.konanTarget.name.startsWith("mingw")) {
            target.binaries.filterIsInstance<SharedLibrary>().forEach { lib ->
              lib.linkerOpts("-lmsvcrt", "-static-libgcc", "-static-libstdc++")
            }
          }

          val sharedLib: SharedLibrary = target.binaries
            .filterIsInstance<SharedLibrary>()
            .firstOrNull { it.buildType.name == "RELEASE" }
            ?: target.binaries
              .filterIsInstance<SharedLibrary>()
              .firstOrNull()
            ?: continue

          libDirs[rid] = sharedLib.outputDirectory.absolutePath

          if (sharedLib.linkTaskProvider.get().enabled) {
            linkTasks.add(sharedLib.linkTaskProvider)
          }

          if (baseName == null) {
            baseName = sharedLib.baseName
          }
        }

        if (libDirs.isEmpty()) return@afterEvaluate

        // KSP generates Interop.cs at:
        // build/generated/ksp/<target>/<target>Main/resources/Interop.cs
        // Pick the first available target's output
        val firstTarget: String = nativeTargets
          .first { KONAN_TO_RID.containsKey(it.konanTarget.name) }
          .name

        val kspOutputDir: Provider<Directory> = project.layout.buildDirectory
          .dir("generated/ksp/$firstTarget/${firstTarget}Main/resources")

        project.tasks.register("packNuget", PackNugetTask::class.java)
          .configure { task ->
            task.group = "nuget"
            task.description = "Packages the Kotlin/Native shared library as a NuGet package"
            task.packageId.set(pub.packageId)
            task.packageVersion.set(pub.version)
            task.authors.set(pub.authors)
            task.packageDescription.set(pub.description)
            task.nativeLibDirs.set(libDirs)
            task.nativeLibFiles.from(libDirs.values.map { project.fileTree(it) })
            task.generatedCsDir.set(kspOutputDir)
            task.outputDir.set(project.layout.buildDirectory.dir("nuget"))

            linkTasks.forEach { task.dependsOn(it) }

            task.dependsOn("kspKotlin${firstTarget.replaceFirstChar { it.uppercase() }}")
          }
      }
    }

    project.afterEvaluate { _ ->
      val deps: List<NugetDependency> = extension.dependencies
      if (deps.isEmpty()) return@afterEvaluate

      val interopDir: Provider<Directory> = project.layout.buildDirectory.dir("nuget-interop")

      val kotlin: KotlinMultiplatformExtension? =
        project.extensions.findByType(KotlinMultiplatformExtension::class.java)

      val rids: List<String> = kotlin
        ?.targets
        ?.filterIsInstance<KotlinNativeTarget>()
        ?.filter { it.konanTarget.name in KONAN_TO_RID }
        ?.mapNotNull { KONAN_TO_RID[it.konanTarget.name] }
        ?: emptyList()

      val nugetGen: TaskProvider<NugetGenTask> =
        project.tasks.register("nugetGen", NugetGenTask::class.java) { task ->
          task.group = "nuget"
          task.description =
            "Generates the synthetic interop.csproj for NuGet dependency resolution"
          val versions: Map<String, String> = deps
            .filter { it.version != null }
            .associate { it.id to it.version!! }

          val sources: Map<String, String> = deps
            .filter { it.source != null }
            .associate { it.id to it.source!! }

          task.dependencyIds.set(deps.map { it.id })
          task.dependencyVersions.set(versions)
          task.dependencySources.set(sources)
          task.targetFramework.set("net8.0")
          task.runtimeIdentifiers.set(rids)
          task.csprojFile.set(interopDir.map { it.file("interop.csproj") })
        }

      val nugetRestore: TaskProvider<NugetRestoreTask> =
        project.tasks.register("nugetRestore", NugetRestoreTask::class.java) { task ->
          task.group = "nuget"
          task.description = "Runs dotnet restore to download declared NuGet packages"
          task.csprojFile.set(nugetGen.flatMap { it.csprojFile })
          task.assetsFile.set(interopDir.map { it.file("obj/project.assets.json") })
        }

      val nugetImport: TaskProvider<*> = project.tasks.register("nugetImport") { task ->
        task.group = "nuget"
        task.description = "IDE-sync umbrella task: resolve NuGet dependencies"
        task.dependsOn(nugetRestore)
      }

      val bound: List<NugetDependency> = deps.filter { it.bind != null }

      if (bound.isNotEmpty()) {
        val nugetExtractApi: TaskProvider<NugetExtractApiTask> =
          project.tasks.register("nugetExtractApi", NugetExtractApiTask::class.java) { task ->
            task.group = "nuget"
            task.description =
              "Extracts the public API surface of bound NuGet packages into reverse-ir.json"
            task.assetsFile.set(nugetRestore.flatMap { it.assetsFile })
            task.boundPackageIds.set(bound.map { it.id })
            task.packageNameOverrides.set(
              bound
                .filter { it.bind!!.packageName != null }
                .associate { it.id to it.bind!!.packageName!! }
            )
            task.namespaceIncludes.set(bound.associate { it.id to it.bind!!.include })
            task.namespaceExcludes.set(bound.associate { it.id to it.bind!!.exclude })
            task.namespaceAliases.set(bound.associate { it.id to it.bind!!.aliases })
            task.reverseIrFile.set(interopDir.map { it.file("reverse-ir.json") })
          }

        nugetImport.configure { task -> task.dependsOn(nugetExtractApi) }

        val nugetGenerateBindings: TaskProvider<NugetGenerateBindingsTask> =
          project.tasks.register(
            "nugetGenerateBindings",
            NugetGenerateBindingsTask::class.java,
          ) { task ->
            task.group = "nuget"
            task.description =
              "Generates Kotlin stubs and the C# registration contract from reverse-ir.json"
            task.reverseIrFile.set(nugetExtractApi.flatMap { it.reverseIrFile })
            task.packageNameOverrides.set(
              bound
                .filter { it.bind!!.packageName != null }
                .associate { it.id to it.bind!!.packageName!! }
            )
            task.namespaceAliases.set(bound.associate { it.id to it.bind!!.aliases })
            task.kotlinOutputDir.set(interopDir.map { it.dir("kotlin") })
          }

        nugetImport.configure { task -> task.dependsOn(nugetGenerateBindings) }

        if (kotlin != null) {
          kotlin.sourceSets.findByName("nativeMain")?.kotlin?.srcDir(
            nugetGenerateBindings.flatMap { task ->
              task.kotlinOutputDir.map { it.dir("nativeMain") }
            }
          )

          for (target in kotlin.targets.filterIsInstance<KotlinNativeTarget>()) {
            val rid: String = KONAN_TO_RID[target.konanTarget.name] ?: continue
            val subdir: String = if (rid.startsWith("win-")) "mingwMain" else "posixMain"
            kotlin.sourceSets.findByName("${target.name}Main")?.kotlin?.srcDir(
              nugetGenerateBindings.flatMap { task ->
                task.kotlinOutputDir.map { it.dir(subdir) }
              }
            )
          }
        }
      }
    }
  }
}
