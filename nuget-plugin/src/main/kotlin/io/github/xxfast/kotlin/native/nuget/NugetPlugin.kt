package io.github.xxfast.kotlin.native.nuget

import io.github.xxfast.kotlin.native.nuget.rir.deriveResolvedVersions
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.SharedLibrary
import java.lang.reflect.Method

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

    // ADR-050 Alternative 6: the consume-side (`dependencies { bind {} }`) afterEvaluate block is
    // registered FIRST — before the KMP-gated publish/packNuget block below — so that, by
    // registration order, nugetRestore/nugetGenerateShims already exist as TaskProviders by the
    // time packNuget is configured. This works regardless of whether the KMP plugin is applied
    // before or after this plugin: Gradle's afterEvaluate callbacks fire in registration order,
    // and `withPlugin` below either fires synchronously now (if KMP is already applied) or later
    // when KMP is applied — either way, strictly after this statement has already registered its
    // own afterEvaluate callback.
    //
    // This block intentionally does NOT require the KMP plugin: a project can declare
    // `nuget { dependencies { ... } }` without Kotlin Multiplatform applied at all (task
    // registration only; kotlinOutputDir/kotlin source-set wiring below simply no-ops in that
    // case).
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

        // Lazily resolved (not a plain `val`/requireNotNull computed eagerly here): deferring
        // via project.provider {} means the fail-fast only fires when nativeLibraryName is
        // actually queried (i.e. when nugetGenerateShims itself runs or is inspected), not for
        // every project that declares `bind {}` — a project with no `binaries { sharedLib {} }`
        // configured yet should still be able to configure/evaluate successfully otherwise.
        val nativeLibraryName: Provider<String> = project.provider {
          requireNotNull(
            kotlin
              ?.targets
              ?.filterIsInstance<KotlinNativeTarget>()
              ?.flatMap { it.binaries.filterIsInstance<SharedLibrary>() }
              ?.firstOrNull()?.baseName
          ) {
            "[nuget] No Kotlin/Native shared library binary configured. " +
              "nuget { dependencies { bind { ... } } } requires a " +
              "`binaries { sharedLib { ... } }` target to host the registered C# thunks."
          }
        }

        val nugetGenerateShims: TaskProvider<NugetGenerateShimsTask> =
          project.tasks.register(
            "nugetGenerateShims",
            NugetGenerateShimsTask::class.java,
          ) { task ->
            task.group = "nuget"
            task.description = "Generates C#-side [UnmanagedCallersOnly] thunks and startup " +
              "registration shims from reverse-ir.json"
            task.reverseIrFile.set(nugetExtractApi.flatMap { it.reverseIrFile })
            task.nativeLibraryName.set(nativeLibraryName)
            task.csharpOutputDir.set(interopDir.map { it.dir("csharp") })
          }

        nugetImport.configure { task -> task.dependsOn(nugetGenerateShims) }

        if (kotlin != null) {
          // Wired via a Provider computed independently from `interopDir` (NOT chained through
          // `nugetGenerateBindings.kotlinOutputDir`, e.g.
          // `nugetGenerateBindings.flatMap { it.kotlinOutputDir... }`) even though both resolve
          // to the identical path. KSP's Gradle plugin (`KspAATask`)
          // eagerly resolves the compilation's source directories — including calling
          // `SourceDirectorySet.srcDirTrees`/`getFiles()` — while computing its OWN task's
          // dependencies, i.e. before `nugetGenerateBindings` has run. A Provider chained through
          // a task's own `@OutputDirectory` property trips Gradle's "querying the mapped value of
          // task '...' before task '...' has completed is not supported" safeguard when read this
          // way; a plain Provider with no associated producer-task metadata does not. Because this
          // sidesteps Gradle's automatic task-dependency inference (which relies on that same
          // producer-task metadata), the `kspKotlin{Target}` dependency is instead added
          // explicitly below.
          val kotlinOutputDirLiteral: Provider<Directory> = interopDir.map { it.dir("kotlin") }

          kotlin.sourceSets.findByName("nativeMain")?.kotlin?.srcDir(
            kotlinOutputDirLiteral.map { it.dir("nativeMain") }
          )

          for (target in kotlin.targets.filterIsInstance<KotlinNativeTarget>()) {
            val rid: String = KONAN_TO_RID[target.konanTarget.name] ?: continue
            val subdir: String = if (rid.startsWith("win-")) "mingwMain" else "posixMain"
            kotlin.sourceSets.findByName("${target.name}Main")?.kotlin?.srcDir(
              kotlinOutputDirLiteral.map { it.dir(subdir) }
            )

            // The KSP Gradle plugin names its per-target task `kspKotlin{Target}` (matches the
            // existing `packNuget` wiring's `task.dependsOn("kspKotlin$firstTarget")` below).
            // Match by name via `tasks.matching` (not `tasks.named`, which would throw if KSP
            // hasn't registered that task for this target) so this stays a no-op when absent.
            val kspTaskName = "kspKotlin${target.name.replaceFirstChar { it.uppercase() }}"
            project.tasks.matching { it.name == kspTaskName }.configureEach { task ->
              task.dependsOn(nugetGenerateBindings)
            }
          }
        }
      }
    }

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
          // ADR-050 Alternative 6: no longer requireNotNull — a project that declares only
          // `dependencies { bind {} }` (no `publish {}`) must configure successfully. When
          // publish is absent there is nothing meaningful to derive these KSP args from; fall
          // back to empty/placeholder values (harmless: nobody consumes this forward-generation
          // output without a `publish {}`/`packNuget` in the first place).
          val pub: NugetPublishConfig? = extension.publish

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
          argMethod.invoke(ksp, "nuget.namespace", pub?.packageId ?: "")
          argMethod.invoke(ksp, "nuget.rootPackage", pub?.rootPackage ?: "")
          argMethod.invoke(ksp, "nuget.className", "${pub?.packageId ?: "Library"}Native")
        }
      }

      project.afterEvaluate { _ ->
        // ADR-050 Alternative 6: early-return (not requireNotNull) — a project with no
        // `publish {}` block simply does not get a `packNuget` task; it may still fully configure
        // a consume-only (`dependencies { bind {} }`) setup via the block registered above.
        val pub: NugetPublishConfig = extension.publish ?: return@afterEvaluate

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
              // -lole32: the reverse-bound `freeManagedString` actual (ADR-048, mingwMain) calls
              // `platform.windows.CoTaskMemFree`, which is exported from ole32.dll/ole32.lib —
              // needed whenever a bound dependency has a string-returning bridgeable method.
              // Harmless to link unconditionally for every mingw target.
              lib.linkerOpts("-lmsvcrt", "-static-libgcc", "-static-libstdc++", "-lole32")
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

        // ADR-050 Alternative 6: when this project ALSO declares `dependencies { bind {} }`
        // (registered by the afterEvaluate block above, which — by registration order — has
        // already run), merge the reverse-direction shim output into contentFiles/cs/any/ and
        // pin the bound package(s) at their exact resolved version in the .nuspec
        // <dependencies> block. Looked up by task name (rather than a shared TaskProvider
        // variable) because the two afterEvaluate blocks are independent closures.
        val boundDeps: List<NugetDependency> = extension.dependencies.filter { it.bind != null }

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
            task.generatedCsDirs.from(kspOutputDir)
            task.outputDir.set(project.layout.buildDirectory.dir("nuget"))

            linkTasks.forEach { task.dependsOn(it) }

            task.dependsOn("kspKotlin${firstTarget.replaceFirstChar { it.uppercase() }}")

            if (boundDeps.isNotEmpty()) {
              val nugetGenerateShims: TaskProvider<NugetGenerateShimsTask> =
                project.tasks.named("nugetGenerateShims", NugetGenerateShimsTask::class.java)
              val nugetRestore: TaskProvider<NugetRestoreTask> =
                project.tasks.named("nugetRestore", NugetRestoreTask::class.java)

              val boundIds: Set<String> = boundDeps.map { it.id }.toSet()

              task.generatedCsDirs.from(nugetGenerateShims.flatMap { it.csharpOutputDir })
              task.dependencyVersions.set(
                nugetRestore.flatMap { restore ->
                  restore.assetsFile.map { assetsFile ->
                    deriveResolvedVersions(assetsFile.asFile.readText(), boundIds)
                  }
                }
              )
              task.dependsOn(nugetGenerateShims)
            } else {
              task.dependencyVersions.set(emptyMap())
            }
          }
      }
    }
  }
}
