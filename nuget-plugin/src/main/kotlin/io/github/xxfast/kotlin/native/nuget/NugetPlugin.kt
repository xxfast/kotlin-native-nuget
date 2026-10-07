package io.github.xxfast.kotlin.native.nuget

import io.github.xxfast.kotlin.native.nuget.rir.deriveResolvedVersions
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider
import org.gradle.language.base.plugins.LifecycleBasePlugin
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinBasePlugin
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.SharedLibrary
import java.io.File
import java.lang.reflect.Method
import java.util.concurrent.Callable

// ADR-093: PackNugetTask reads the value set to name the RIDs this plugin version can build when
// it warns about an unknown prebuilt RID.
internal val KONAN_TO_RID = mapOf(
  "mingw_x64" to "win-x64",
  "macos_arm64" to "osx-arm64",
  "macos_x64" to "osx-x64",
  "linux_x64" to "linux-x64",
  "linux_arm64" to "linux-arm64",
)

private const val KMP_PLUGIN: String = "org.jetbrains.kotlin.multiplatform"
private const val KSP_PLUGIN: String = "com.google.devtools.ksp"

// #469: KGP's `baseName` is a plain `String`, so "set explicitly" means "differs from what KGP
// assigned". `sharedLib()` defaults to the project name; `sharedLib("prefix")` to the prefix, and
// names the binary `<prefix><BuildType>Shared` (KGP 2.4.10 `AbstractKotlinNativeBinaryContainer`).
private fun defaultBaseName(project: Project, lib: SharedLibrary): String {
  val type: String = lib.buildType.getName()
  if (lib.name == "${type}Shared") return project.name
  return lib.name.removeSuffix("${type.replaceFirstChar { it.uppercaseChar() }}Shared")
}

internal fun parseStrictCompileCheck(value: String): Boolean {
  if (value == "true") return true
  if (value == "false") return false
  throw GradleException(
    "[nuget] nuget.strictCompileCheck must be true or false, but was '$value'.",
  )
}

// ADR-195: runs before anything else touches KGP, so a consumer below the floor gets one sentence at
// configuration time instead of a klib resolver error at compile time.
private fun checkKotlinVersion(project: Project) {
  val current: String = project.plugins.withType(KotlinBasePlugin::class.java).first().pluginVersion
  val support: KotlinSupport = kotlinSupport(current, floor = KOTLIN_FLOOR, tested = KOTLIN_TESTED)
  when (support) {
    is KotlinSupport.BelowFloor -> throw GradleException(support.message)
    is KotlinSupport.AboveTested -> project.logger.warn(support.message)
    is KotlinSupport.Unrecognised -> project.logger.warn(support.message)
    KotlinSupport.Supported -> Unit
  }
}

// One supported native target as packNuget sees it: the shared library it would pack, and whether
// this host can link it.
private class LocalLibrary(
  val rid: String,
  val target: KotlinNativeTarget,
  val library: SharedLibrary,
  val enabled: Boolean,
)

// ADR-180: no `afterEvaluate` but ADR-178's. Tasks that exist only when a block is declared are
// registered by the first call to that block (ADR-050 Alternative 6: a consume-only project still
// has no `packNuget`), and everything that depends on a value is a Provider, so a value set after
// the block, or supplied as a Provider, still reaches the task.
public class NugetPlugin : Plugin<Project> {
  override fun apply(project: Project) {
    val extension: NugetExtension =
      project.extensions.create("nuget", NugetExtension::class.java)
    extension.publish.strictCompileCheck.convention(
      project.providers.gradleProperty("nuget.strictCompileCheck")
        .map(::parseStrictCompileCheck).orElse(false),
    )

    // ADR-178: run before either the reverse name provider or forward KSP args read baseName.
    // ADR-180: the one `afterEvaluate` that stays. KGP's `NativeBinary.baseName` is a plain
    // `String` (its lazy form is internal), so the packageId-derived stem can only be written once
    // the build script has finished setting `packageId`.
    project.afterEvaluate {
      if (!extension.publishDeclared) return@afterEvaluate
      val id: String = extension.publish.packageId.orNull?.takeIf { it.isNotBlank() }
        ?: return@afterEvaluate
      val stem: String = nativeLibraryStem(id)
      project.extensions.findByType(KotlinMultiplatformExtension::class.java)
        ?.targets?.filterIsInstance<KotlinNativeTarget>()?.forEach { target ->
          target.binaries.withType(SharedLibrary::class.java).configureEach { lib ->
            val default: String = defaultBaseName(project, lib)
            require(lib.baseName == default || lib.baseName == stem) {
              "[nuget] ${target.name} binary '${lib.name}' sets baseName '${lib.baseName}', but " +
                "the native library name is derived from packageId '$id' as '$stem' (ADR-178). " +
                "Remove the baseName line; the plugin names the library from packageId."
            }
            lib.baseName = stem
          }
        }
    }

    // The consume side does NOT require the KMP plugin: a project can declare
    // `nuget { dependencies { ... } }` without Kotlin Multiplatform applied at all (task
    // registration only; the source-set wiring is a no-op in that case). The first declared
    // dependency registers it; an empty `dependencies {}` registers nothing.
    var consumeRegistered = false
    extension.dependencies.whenObjectAdded {
      if (!consumeRegistered) {
        consumeRegistered = true
        registerConsume(project, extension)
      }
    }

    // The first `bind {}` on any dependency registers the reverse pipeline.
    var reverseRegistered = false
    extension.dependencies.all { dependency ->
      dependency.whenBound {
        if (!reverseRegistered) {
          reverseRegistered = true
          registerReverse(project, extension)
        }
      }
    }

    project.pluginManager.withPlugin(KMP_PLUGIN) { _ ->
      checkKotlinVersion(project)
      project.pluginManager.apply(KSP_PLUGIN)
      project.pluginManager.apply(NugetAnnotationsPlugin::class.java)

      val kotlin: KotlinMultiplatformExtension =
        project.extensions.getByType(KotlinMultiplatformExtension::class.java)

      val processorDep: Any = project.findProject(":nuget-processor")
        ?: "io.github.xxfast:nuget-processor:$PLUGIN_VERSION"

      // ADR-127: the fixed 67-name `nuget_*` ABI ships as a klib instead of being regenerated
      // into every consumer. Resolved exactly as the processor is: the in-repo project when this
      // is the composite build, the published coordinate at this plugin's own version otherwise,
      // so the generator and the runtime cannot skew on the supported path.
      val runtimeDep: Any = project.findProject(":nuget-runtime")
        ?: "io.github.xxfast:nuget-runtime:$PLUGIN_VERSION"

      // ADR-156: a method bound from a C# `IAsyncEnumerable<T>` return names
      // `kotlinx.coroutines.flow.Flow` in a PUBLIC signature of a generated class, and generated
      // classes compile from `nativeMain` (see the srcDir wiring in `registerReverse`) while
      // `nuget-runtime` — and coroutines through its `api` — reaches only `${target}MainApi`.
      // Without this a consumer that does not itself declare coroutines fails with
      // `Unresolved reference: Flow`. ADR-130's objection to putting the RUNTIME here (it
      // publishes no iOS variant) does not apply: kotlinx-coroutines-core publishes every native
      // target. `api`, not `implementation`: the type is in a public signature.
      // `configureEach`, not `findByName`: the default hierarchy has not materialised `nativeMain`
      // yet at the moment the plugin is applied (verified — `findByName` returns null there and
      // the dependency is silently never added, which is the exact failure mode this whole
      // wiring exists to prevent).
      kotlin.sourceSets.configureEach { sourceSet ->
        if (sourceSet.name != "nativeMain") return@configureEach
        project.dependencies.add(
          sourceSet.apiConfigurationName,
          "org.jetbrains.kotlinx:kotlinx-coroutines-core:$COROUTINES_VERSION",
        )
      }

      kotlin.targets.withType(KotlinNativeTarget::class.java).configureEach { target ->
        if (target.konanTarget.name !in KONAN_TO_RID) return@configureEach

        val configName = "ksp${target.name.replaceFirstChar { it.uppercase() }}"
        project.dependencies.add(configName, processorDep)

        // `api`, not `implementation`: verified in ADR-127's spike, `export()` of an
        // `implementation` dependency fails at link time with "Following dependencies exported in
        // the releaseShared binary are not specified as API-dependencies".
        project.dependencies.add("${target.name}MainApi", runtimeDep)

        // Also verified there: without the `export()` the runtime's `@CName` symbols never reach
        // the consumer's shared library, and every `nuget_*` P/Invoke fails with
        // `EntryPointNotFoundException`. `export()` appends, so an author's own entries stand.
        target.binaries.withType(SharedLibrary::class.java).configureEach { lib ->
          lib.export(runtimeDep)

          if (!target.konanTarget.name.startsWith("mingw")) return@configureEach
          // -lole32: the reverse-bound `freeManagedString` actual (ADR-048, mingwMain) calls
          // `platform.windows.CoTaskMemFree`, which is exported from ole32.dll/ole32.lib —
          // needed whenever a bound dependency has a string-returning bridgeable method, which a
          // consume-only project has too. Harmless to link unconditionally for every mingw target.
          lib.linkerOpts("-lmsvcrt", "-static-libgcc", "-static-libstdc++", "-lole32")
        }
      }

      project.pluginManager.withPlugin(KSP_PLUGIN) { _ -> registerKspArgs(project, extension) }
    }

    // `withPlugin` inside the hook: a script that applies KMP imperatively after `nuget {}` still
    // gets packNuget once KMP arrives, and a project without KMP never does.
    extension.whenPublishDeclared {
      project.pluginManager.withPlugin(KMP_PLUGIN) { _ ->
        val kotlin: KotlinMultiplatformExtension =
          project.extensions.getByType(KotlinMultiplatformExtension::class.java)
        registerPublish(project, extension, kotlin)
      }
    }
  }

  private fun registerConsume(project: Project, extension: NugetExtension) {
    val interopDir: Provider<Directory> = project.layout.buildDirectory.dir("nuget-interop")
    val dependencies = extension.dependencies
    val projectDir: File = project.layout.projectDirectory.asFile
    val sources: Provider<Map<String, String>> =
      project.provider { dependencies.resolvedSources(projectDir) }
    val localSources: Provider<Map<String, String>> = sources.map(::localOnly)
    val shared: Provider<List<String>> =
      project.provider { resolvedShared(extension.sources.get(), projectDir) }
    val sharedDirs: Provider<List<File>> = shared.map(::sharedDirectories)
    // ADR-190 / ADR-191: restore moves under build/ only when a local feed is declared.
    val packagesDir: Provider<Directory> = project.provider {
      val noLocalFeed: Boolean = localSources.get().isEmpty() && sharedDirs.get().isEmpty()
      if (noLocalFeed) null
      else interopDir.get().dir("packages")
    }

    val nugetGenerateRestoreProject: TaskProvider<NugetGenerateRestoreProjectTask> =
      project.tasks.register(
        NugetTaskNames.GENERATE_RESTORE_PROJECT,
        NugetGenerateRestoreProjectTask::class.java,
      ) { task ->
        task.group = "nuget"
        task.description = "Generates the synthetic interop.csproj for NuGet dependency resolution"
        task.dependencyIds.set(project.provider { dependencies.map { it.id } })
        task.dependencyVersions.set(
          project.provider {
            dependencies
              .filter { it.version.isPresent }
              .associate { it.id to it.version.get() }
          }
        )
        task.dependencySources.set(sources)
        task.sharedSources.set(shared)
        task.localSources.from(sharedDirs)
        task.localSources.from(localSources.map { local -> local.values.map(::File) })
        task.packagesDir.set(packagesDir)
        task.feedDir.set(interopDir.map { it.dir("feed") })
        task.targetFramework.set(extension.validatedTargetFramework)
        task.runtimeIdentifiers.set(
          project.provider {
            project.extensions.findByType(KotlinMultiplatformExtension::class.java)
              ?.targets
              ?.filterIsInstance<KotlinNativeTarget>()
              ?.mapNotNull { KONAN_TO_RID[it.konanTarget.name] }
              .orEmpty()
          }
        )
        task.csprojFile.set(interopDir.map { it.file("interop.csproj") })
      }

    val nugetRestore: TaskProvider<NugetRestoreTask> =
      project.tasks.register(NugetTaskNames.RESTORE, NugetRestoreTask::class.java) { task ->
        task.group = "nuget"
        task.description = "Runs dotnet restore to download declared NuGet packages"
        task.csprojFile.set(nugetGenerateRestoreProject.flatMap { it.csprojFile })
        task.targetFramework.set(extension.validatedTargetFramework)
        task.assetsFile.set(interopDir.map { it.file("obj/project.assets.json") })
        task.localFeeds.from(nugetGenerateRestoreProject.flatMap { it.feedDir })
        task.localFeeds.from(
          localSources.map { local ->
            local.values.filterNot { it.endsWith(".nupkg", ignoreCase = true) }.map(::File)
          }
        )
        task.localFeeds.from(sharedDirs)
        task.localSources.set(localSources)
        task.packagesDir.set(packagesDir)
        wireDotnet(project, task.dotnet, task.dotnetSource)
      }

    project.tasks.register(NugetTaskNames.IMPORT) { task ->
      task.group = "nuget"
      task.description = "IDE-sync umbrella task: resolve NuGet dependencies"
      task.dependsOn(nugetRestore)
    }
  }

  // Registered by the first `bind {}`. A dependency always exists by then (`bind` is a member of
  // one), so the consume tasks are already registered.
  private fun registerReverse(project: Project, extension: NugetExtension) {
    val interopDir: Provider<Directory> = project.layout.buildDirectory.dir("nuget-interop")
    val bound: Provider<List<NugetDependency>> =
      project.provider { extension.dependencies.filter { it.bound } }

    val packageNameOverrides: Provider<Map<String, String>> = bound.map { deps ->
      deps.filter { it.bind.packageName.isPresent }.associate { it.id to it.bind.packageName.get() }
    }

    val aliases: Provider<Map<String, Map<String, String>>> =
      bound.map { deps -> deps.associate { it.id to it.bind.aliases.get() } }

    val nugetRestore: TaskProvider<NugetRestoreTask> =
      project.tasks.named(NugetTaskNames.RESTORE, NugetRestoreTask::class.java)

    val nugetImport: TaskProvider<*> = project.tasks.named(NugetTaskNames.IMPORT)

    val nugetExtractApi: TaskProvider<NugetExtractApiTask> =
      project.tasks.register(NugetTaskNames.EXTRACT_API, NugetExtractApiTask::class.java) { task ->
        task.group = "nuget"
        task.description =
          "Extracts the public API surface of bound NuGet packages into reverse-ir.json"
        task.assetsFile.set(nugetRestore.flatMap { it.assetsFile })
        task.targetFramework.set(extension.validatedTargetFramework)
        task.boundPackageIds.set(bound.map { deps -> deps.map { it.id } })
        task.packageNameOverrides.set(packageNameOverrides)
        task.namespaceIncludes.set(
          bound.map { deps -> deps.associate { it.id to it.bind.includeNamespaces.get() } }
        )
        task.namespaceExcludes.set(
          bound.map { deps -> deps.associate { it.id to it.bind.excludeNamespaces.get() } }
        )
        task.namespaceAliases.set(aliases)
        task.reverseIrFile.set(interopDir.map { it.file("reverse-ir.json") })
        wireDotnet(project, task.dotnet, task.dotnetSource)
      }

    nugetImport.configure { task -> task.dependsOn(nugetExtractApi) }

    val nugetGenerateBindings: TaskProvider<NugetGenerateBindingsTask> =
      project.tasks.register(
        NugetTaskNames.GENERATE_BINDINGS,
        NugetGenerateBindingsTask::class.java,
      ) { task ->
        task.group = "nuget"
        task.description =
          "Generates Kotlin stubs and the C# registration contract from reverse-ir.json"
        task.reverseIrFile.set(nugetExtractApi.flatMap { it.reverseIrFile })
        task.packageNameOverrides.set(packageNameOverrides)
        task.namespaceAliases.set(aliases)
        task.kotlinOutputDir.set(interopDir.map { it.dir("kotlin") })
        // ADR-088: beside the generated Kotlin, not inside it — see the task property.
        task.boundTypesManifestFile.set(interopDir.map { it.file("bound-types.json") })
        task.diagnosticsFile.set(interopDir.map { it.file("NugetDiagnostics.json") })
      }

    nugetImport.configure { task -> task.dependsOn(nugetGenerateBindings) }

    // Lazily resolved: the fail-fast only fires when nativeLibraryName is actually queried (i.e.
    // when nugetGenerateShims itself runs or is inspected), not for every project that declares
    // `bind {}` — a project with no `binaries { sharedLib {} }` configured yet should still be
    // able to configure/evaluate successfully otherwise.
    val nativeLibraryName: Provider<String> = project.provider {
      requireNotNull(
        project.extensions.findByType(KotlinMultiplatformExtension::class.java)
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
        NugetTaskNames.GENERATE_SHIMS,
        NugetGenerateShimsTask::class.java,
      ) { task ->
        task.group = "nuget"
        task.description = "Generates C#-side [UnmanagedCallersOnly] thunks and startup " +
            "registration shims from reverse-ir.json"
        task.reverseIrFile.set(nugetExtractApi.flatMap { it.reverseIrFile })
        task.nativeLibraryName.set(nativeLibraryName)
        // ADR-087 stage 2: the same value the forward KSP run uses for `nuget.namespace`, so
        // the reverse shims can throw through the forward error mapping instead of owning a
        // second copy of ADR-029's table.
        task.forwardNamespace.set(extension.publish.forwardNamespace())
        task.csharpOutputDir.set(interopDir.map { it.dir("csharp") })
      }

    nugetImport.configure { task -> task.dependsOn(nugetGenerateShims) }

    // ADR-050 Alternative 6: a project that ALSO publishes merges the reverse shims into
    // contentFiles/cs/<tfm>/ and pins each bound package at its exact resolved version in the
    // .nuspec. `withType().configureEach` covers whichever of `publish {}` and `bind {}` came
    // first; the publish side sets `dependencyVersions` only as a convention.
    val boundIds: Provider<Set<String>> = bound.map { deps -> deps.map { it.id }.toSet() }
    // `flatMap` over the restore task, not a `zip`: the configuration cache must keep this tied to
    // the task's output and resolve it at execution time (a `zip` was read at store time, before
    // `dotnet restore` had written the file).
    val resolvedVersions: Provider<Map<String, String>> = nugetRestore.flatMap { restore ->
      restore.assetsFile.map { assetsFile ->
        deriveResolvedVersions(assetsFile.asFile.readText(), boundIds.get())
      }
    }

    project.tasks.withType(PackNugetTask::class.java).configureEach { task ->
      task.dependencyVersions.set(resolvedVersions)
      task.generatedCsDirs.from(nugetGenerateShims.flatMap { it.csharpOutputDir })
      task.dependsOn(nugetGenerateShims)
    }

    project.tasks.withType(NugetCompileInteropTask::class.java).configureEach { task ->
      task.dependencyVersions.set(resolvedVersions)
      task.generatedCsDirs.from(nugetGenerateShims.flatMap { it.csharpOutputDir })
      task.dependsOn(nugetGenerateShims)
    }

    project.pluginManager.withPlugin(KMP_PLUGIN) { _ ->
      val kotlin: KotlinMultiplatformExtension =
        project.extensions.getByType(KotlinMultiplatformExtension::class.java)

      // Wired via a Provider computed independently from `interopDir` (NOT chained through
      // `nugetGenerateBindings.kotlinOutputDir`) even though both resolve to the identical path.
      // KSP's Gradle plugin (`KspAATask`) eagerly resolves the compilation's source directories
      // while computing its OWN task's dependencies, i.e. before `nugetGenerateBindings` has run.
      // A Provider chained through a task's own `@OutputDirectory` property trips Gradle's
      // "querying the mapped value of task '...' before task '...' has completed is not
      // supported" safeguard when read this way; a plain Provider with no producer-task metadata
      // does not. Because this sidesteps Gradle's automatic task-dependency inference, the
      // `kspKotlin{Target}` dependency is added explicitly below.
      val kotlinOutputDirLiteral: Provider<Directory> = interopDir.map { it.dir("kotlin") }

      // `matching {}.configureEach {}`, never `findByName`: the default hierarchy has not
      // materialised `nativeMain` while the build script is still running, and a `findByName`
      // there silently drops the srcDir.
      kotlin.sourceSets.matching { it.name == "nativeMain" }.configureEach { sourceSet ->
        sourceSet.kotlin.srcDir(kotlinOutputDirLiteral.map { it.dir("nativeMain") })
      }

      kotlin.targets.withType(KotlinNativeTarget::class.java).configureEach { target ->
        val rid: String = KONAN_TO_RID[target.konanTarget.name] ?: return@configureEach
        val subdir: String = if (rid.startsWith("win-")) "mingwMain" else "posixMain"
        val sourceSetName = "${target.name}Main"
        kotlin.sourceSets.matching { it.name == sourceSetName }.configureEach { sourceSet ->
          sourceSet.kotlin.srcDir(kotlinOutputDirLiteral.map { it.dir(subdir) })
        }

        // `tasks.matching` (not `tasks.named`, which would throw if KSP hasn't registered that
        // task for this target) so this stays a no-op when absent.
        val kspTaskName = "kspKotlin${target.name.replaceFirstChar { it.uppercase() }}"
        project.tasks.matching { it.name == kspTaskName }.configureEach { task ->
          task.dependsOn(nugetGenerateBindings)
        }
      }
    }
  }

  // ADR-050 Alternative 6: registered whether or not `publish {}` is declared. Without it there is
  // nothing meaningful to derive these from, so they fall back to empty/placeholder values
  // (harmless: nobody consumes this forward output without a `publish {}`/`packNuget`). Every
  // value is a Provider (`KspExtension.arg(String, Provider<String>)`, which `put`s into its
  // `apOptions` MapProperty), so values set after this runs still arrive.
  private fun registerKspArgs(project: Project, extension: NugetExtension) {
    val pub: NugetPublishConfig = extension.publish
    val ksp: Any = project.extensions.getByType(
      Class.forName("com.google.devtools.ksp.gradle.KspExtension")
    )
    val argMethod: Method = ksp.javaClass.getMethod("arg", String::class.java, Provider::class.java)
    fun arg(key: String, value: Provider<String>) {
      argMethod.invoke(ksp, key, value)
    }

    // Reads ADR-178's stamp: the provider resolves after the afterEvaluate that writes it.
    arg(
      "nuget.libraryName",
      project.provider {
        project.extensions.findByType(KotlinMultiplatformExtension::class.java)
          ?.targets?.filterIsInstance<KotlinNativeTarget>()
          ?.flatMap { it.binaries.filterIsInstance<SharedLibrary>() }
          ?.firstOrNull()?.baseName
          ?: "library"
      },
    )

    // ADR-063 "Reverse-bound packages are always in scope": the superset of Kotlin packages each
    // bound dependency's reverse-generated stubs can land in, mirroring `kotlinPackage()`'s
    // resolution order (`NugetGenerateBindingsTask.kt:66-73`): the namespace aliases, the
    // `packageName` override, and the sanitised `packageId` fallback. That way an include-based
    // filter can never drop a bound stub the module's own forward code returns.
    val boundPackages: Provider<List<String>> = project.provider {
      extension.dependencies
        .filter { it.bound }
        .flatMap { dep ->
          buildList {
            addAll(dep.bind.aliases.get().values)
            dep.bind.packageName.orNull?.let(::add)
            add(dep.id.lowercase().replace('-', '_'))
          }
        }
        .distinct()
    }

    val classNameStem: Provider<String> =
      pub.packageId.map { id -> id.ifBlank { "Library" } }.orElse("Library")

    arg("nuget.namespace", pub.forwardNamespace())
    arg("nuget.rootPackage", pub.rootPackage.orElse(""))
    arg("nuget.className", classNameStem.map { stem -> "${stem}Native" })
    arg("nuget.includePackages", pub.include.map { it.joinToString(",") })
    arg("nuget.excludePackages", pub.exclude.map { it.joinToString(",") })
    arg("nuget.boundPackages", boundPackages.map { it.joinToString(",") })
    // ADR-154: the additive dependency-admission entries, on the same comma-joined channel as
    // include/exclude. No Kotlin qualified name or package prefix can contain a comma, so the join
    // is unambiguous. Empty is the shipped default (admission by `include(...)` alone).
    arg("nuget.admit", pub.admit.map { it.joinToString(",") })
    // ADR-154 §6: opt-in strictness, lowered as a plain boolean string. Absent or "false" keeps
    // ADR-066 section 4's warn-and-skip default.
    arg("nuget.strictDependencyTypes", pub.strictDependencyTypes.map { it.toString() })
    // ADR-115 amendment: the markers this publisher waives, on the same channel as
    // include/exclude. Empty is the shipped default: every marked declaration keeps skipping.
    arg("nuget.exportMarkers", pub.exportMarkers.map { it.joinToString(",") })

    // ADR-088: the same channel as `nuget.boundPackages`, carrying what a flat package list
    // cannot — the ORIGINAL C# full name per bound interface, and whether a Kotlin class can
    // implement it. Empty when nothing is bound (the manifest task never ran, so pointing at a
    // path would promise a file that does not exist). Ordering is already guaranteed:
    // `kspKotlin{Target}` dependsOn `nugetGenerateBindings`.
    val manifest: Provider<String> = project.layout.buildDirectory
      .file("nuget-interop/bound-types.json")
      .map { it.asFile.absolutePath }
    arg(
      "nuget.boundTypesManifest",
      boundPackages.zip(manifest) { packages, path -> if (packages.isEmpty()) "" else path },
    )

    // ADR-109: registered by `publish {}`; a project with no `publish {}` has no export scope of
    // its own and registers nothing at all.
    extension.whenPublishDeclared { arg("nuget.publishedScopes", publishedScopes(project)) }
  }

  // ADR-109: the ADR-063 export predicate of EVERY forward publisher in this Gradle build, this
  // project included, lowered to packages because the processor can only match an admitted klib
  // type by package (a cross-module declaration carries no module identity:
  // `containingFile == null`, `origin == KOTLIN_LIB`).
  //
  // The Provider defers the cross-project walk to option resolution. The real two-publisher
  // fixture verifies delivery of a scope configured after this reader is evaluated
  // (scripts/verify-forward-diagnostics.sh). It does not prove that every Provider invocation
  // waits for all projects to finish evaluation. Eager reciprocal evaluationDependsOn calls would
  // be circular (ADR-109 Alternative 2).
  //
  // Self is listed deliberately, and dropped by the processor (its entry's packageId equals its
  // own `nuget.namespace`), so the single-publisher real build still exercises the whole delivery
  // path.
  //
  // The body reads other projects' extensions: legal today, and the first thing that breaks if
  // project isolation is ever enabled (it is not; configuration cache alone permits this).
  private fun publishedScopes(project: Project): Provider<String> = project.provider {
    project.rootProject.allprojects
      .mapNotNull { other -> other.extensions.findByType(NugetExtension::class.java) }
      .filter { it.publishDeclared }
      .map { it.publish }
      .map { config ->
        // Mirrors `effectiveInclude` (`NugetProcessor.kt`): the explicit `include(...)` list
        // when non-empty, else `[rootPackage]`, else empty — which the processor treats as
        // "unknown scope" and stays silent about (ADR-109's documented gap).
        val include: List<String> = config.include.get()
          .ifEmpty { listOfNotNull(config.rootPackage.orNull?.takeIf { it.isNotBlank() }) }
        // ADR-154: the publisher's `admit(...)` entries (packages or qualified type names) ride
        // an optional fourth field, so a type it admits by name is visible to every other
        // publisher's duplicate check. Written only when non-empty, so an admission-free
        // encoding is byte-identical to the three-field one.
        val admit: List<String> = config.admit.get()
        listOfNotNull(
          config.packageId.orNull.orEmpty(),
          include.joinToString("|"),
          config.exclude.get().joinToString("|"),
          admit.takeIf { it.isNotEmpty() }?.joinToString("|"),
        ).joinToString(":")
      }
      // Sorted for a stable configuration-cache input: the value must not depend on the order
      // Gradle happens to evaluate sibling projects in.
      .sorted()
      .joinToString(";")
  }

  // Registered by the first `publish {}` once KMP is applied. ADR-180: a project with no supported
  // target, or with nothing this host can link and no prebuilt runtimes, still gets packNuget; the
  // task fails when it runs instead of being silently absent.
  private fun registerPublish(
    project: Project,
    extension: NugetExtension,
    kotlin: KotlinMultiplatformExtension,
  ) {
    val pub: NugetPublishConfig = extension.publish

    fun supportedTargets(): List<KotlinNativeTarget> = kotlin.targets
      .filterIsInstance<KotlinNativeTarget>()
      .filter { it.konanTarget.name in KONAN_TO_RID }

    // Realises the link tasks, so only ever called from a Provider or Callable (graph time).
    fun localLibraries(): List<LocalLibrary> = supportedTargets().mapNotNull { target ->
      val libraries: List<SharedLibrary> = target.binaries.filterIsInstance<SharedLibrary>()
      val library: SharedLibrary = libraries.firstOrNull { it.buildType.name == "RELEASE" }
        ?: libraries.firstOrNull()
        ?: return@mapNotNull null
      // ADR-093: a target this host cannot link never enters nativeLibDirs, so it means "RIDs
      // this host will actually produce" and packNuget can be strict about an empty one.
      LocalLibrary(
        rid = KONAN_TO_RID.getValue(target.konanTarget.name),
        target = target,
        library = library,
        enabled = library.linkTaskProvider.get().enabled,
      )
    }

    val libDirs: Provider<Map<String, String>> = project.provider {
      localLibraries()
        .filter { it.enabled }
        .associate { it.rid to it.library.outputDirectory.absolutePath }
    }

    val contractDirs: Provider<Map<String, String>> = project.provider {
      localLibraries().filter { it.enabled }.sortedBy { it.rid }.associate {
        it.rid to project.layout.buildDirectory.dir(
          "generated/ksp/${it.target.name}/${it.target.name}Main/resources"
        ).get().asFile.absolutePath
      }
    }
    val kspOutputDir: Provider<Directory> = project.layout.dir(project.provider {
      val local = contractDirs.get().values.firstOrNull()
      if (local != null) project.file(local)
      else pub.prebuiltRuntimes.orNull?.asFile?.listFiles()?.filter { it.isDirectory }
        ?.sortedBy { it.name }?.firstOrNull() ?: project.file("build/generated/ksp/none")
    })
    val kspTask: Callable<List<String>> = Callable {
      localLibraries().filter { it.enabled }.sortedBy { it.rid }.mapNotNull {
        val name = "kspKotlin${it.target.name.replaceFirstChar { char -> char.uppercase() }}"
        if (name in project.tasks.names) name else null
      }
    }

    // ADR-092: `snapshot = true` replaces the declared version with one minted at execution time,
    // and emits the props file consumers import to reference it.
    val snapshot: SnapshotVersioning = registerSnapshotVersioning(project, pub)

    // ADR-100: the forward direction's diagnostics reach a console only through Gradle's own
    // logger, and only if something speaks on cached builds too. `NugetDiagnostics.json` is a
    // declared KSP output, so it is there even when `kspKotlin{Target}` is FROM-CACHE or
    // UP-TO-DATE; this task is never up-to-date and re-emits it ahead of every packNuget.
    val reportDiagnostics: TaskProvider<NugetReportDiagnosticsTask> = project.tasks
      .register(NugetTaskNames.REPORT_DIAGNOSTICS, NugetReportDiagnosticsTask::class.java) { task ->
        task.group = "nuget"
        task.description = "Reports declarations the forward bridge could not generate"
        task.diagnosticsFiles.from(kspOutputDir)
        task.dependsOn(kspTask)
      }

    // ADR-138: the generated C# ships as source and is compiled in the consumer's build, so
    // nothing in packNuget can reject a binding that does not compile. This sibling task (the
    // nugetReportDiagnostics precedent) compiles the same files with dotnet first, and skips with
    // a warning when no .NET SDK is installed. `registerReverse` adds the shims and the resolved
    // versions when a dependency is bound, so the check compiles exactly what the pack ships.
    val compileInterop: TaskProvider<NugetCompileInteropTask> = project.tasks
      .register(NugetTaskNames.COMPILE_INTEROP, NugetCompileInteropTask::class.java) { task ->
        task.group = "nuget"
        task.description =
          "Compiles the generated C# bindings with dotnet before ${NugetTaskNames.PACK} stages them"
        task.generatedCsDirs.from(kspOutputDir)
        task.projectDir.set(project.layout.buildDirectory.dir("nuget-compile"))
        task.targetFramework.set(extension.validatedTargetFramework)
        task.dotnetSearchPath.set(project.providers.environmentVariable("PATH"))
        // ADR-190 / ADR-191: the resolved feeds (shared first) and packages folder nugetRestore
        // uses, read from the DSL.
        val projectDir: File = project.layout.projectDirectory.asFile
        val interopDir: Provider<Directory> = project.layout.buildDirectory.dir("nuget-interop")
        val shared: Provider<List<String>> =
          project.provider { resolvedShared(extension.sources.get(), projectDir) }
        wireDotnet(project, task.dotnet, task.dotnetSource)
        task.strictCompileCheck.set(pub.strictCompileCheck)
        task.dependencySources.addAll(
          project.provider {
            val feedDir: File = interopDir.get().dir("feed").asFile
            val perDependency: Collection<String> =
              extension.dependencies.resolvedSources(projectDir).values
            restoreFeeds(shared.get() + perDependency, feedDir)
          }
        )
        task.packagesDir.set(
          project.provider {
            val local: Map<String, String> =
              localOnly(extension.dependencies.resolvedSources(projectDir))
            val noLocalFeed: Boolean = local.isEmpty() && sharedDirectories(shared.get()).isEmpty()
            if (noLocalFeed) null else interopDir.get().dir("packages")
          }
        )
        task.dependencyVersions.convention(emptyMap())
        task.dependsOn(kspTask)
      }

    // `check` compiles the bindings too, so a broken binding surfaces before `packNuget`.
    // `withType`, not `tasks.matching`: lazy, any plugin order, no-op without lifecycle-base.
    project.plugins.withType(LifecycleBasePlugin::class.java) {
      project.tasks.named(LifecycleBasePlugin.CHECK_TASK_NAME)
        .configure { it.dependsOn(compileInterop) }
    }

    val packNuget: TaskProvider<PackNugetTask> =
      project.tasks.register(NugetTaskNames.PACK, PackNugetTask::class.java) { task ->
        task.group = "nuget"
        task.description = "Packages the Kotlin/Native shared library as a NuGet package"
        task.packageId.set(pub.packageId)
        task.packageVersion.set(
          pub.snapshot.flatMap { enabled -> if (enabled) snapshot.version else pub.version }
        )
        task.dependsOn(
          Callable {
            if (pub.snapshot.get()) listOf(snapshot.versionTask, snapshot.propsTask)
            else emptyList()
          }
        )

        task.authors.set(pub.authors)
        task.packageDescription.set(pub.description)
        task.hasSupportedTargets.set(project.provider { supportedTargets().isNotEmpty() })
        task.skippedRids.set(
          project.provider {
            localLibraries().filter { !it.enabled }.associate { it.rid to it.target.name }
          }
        )
        task.nativeLibDirs.set(libDirs)
        task.localContractDirs.set(contractDirs)
        task.contractFiles.from(contractDirs.map { dirs -> dirs.values.map { project.fileTree(it) } })
        task.nativeLibFiles.from(libDirs.map { dirs -> dirs.values.map { project.fileTree(it) } })
        task.dependsOn(
          Callable { localLibraries().filter { it.enabled }.map { it.library.linkTaskProvider } }
        )
        task.prebuiltRuntimesDir.set(pub.prebuiltRuntimes)

        task.generatedCsDirs.from(kspOutputDir)
        task.outputDir.set(project.layout.buildDirectory.dir("nuget"))
        task.targetFramework.set(extension.validatedTargetFramework)

        task.dependsOn(kspTask)
        task.dependsOn(reportDiagnostics)
        task.dependsOn(compileInterop)
        task.dependencyVersions.convention(emptyMap())
      }

    registerPublishing(project, pub, packNuget)
  }

  // ADR-165: one push task per named repository plus the `publishNuget` aggregate, which exists
  // even with no repositories so `publishNuget` is always a valid task name on a packing project.
  private fun registerPublishing(
    project: Project,
    pub: NugetPublishConfig,
    packNuget: TaskProvider<PackNugetTask>,
  ) {
    val file: Provider<RegularFile> = packNuget.flatMap { task ->
      val name: Provider<String> =
        task.packageId.zip(task.packageVersion) { id, version -> "$id.$version.nupkg" }
      task.outputDir.file(name)
    }

    // ADR-180: `all {}`, so a repository declared after `publish {}` first ran (a second block)
    // still gets its task. A missing `url` fails in the task's action.
    pub.repositories.all { repository ->
      val name: String = repository.name
      project.tasks.register(
        NugetTaskNames.publishTo(name),
        PublishNugetTask::class.java,
      ) { task ->
        task.group = "publishing"
        task.description = "Pushes the NuGet package built by ${NugetTaskNames.PACK} to the '$name' repository"
        task.dependsOn(packNuget)
        task.packageFile.set(file)
        task.repositoryName.set(name)
        task.repositoryUrl.set(repository.url)
        task.apiKey.set(
          repository.apiKey.orElse(project.providers.gradleProperty(repository.apiKeyProperty)),
        )
        task.username.set(
          repository.username.orElse(project.providers.gradleProperty(repository.usernameProperty)),
        )
        task.password.set(
          repository.password.orElse(project.providers.gradleProperty(repository.passwordProperty)),
        )
        task.dryRun.convention(false)
        task.skipDuplicate.convention(repository.skipDuplicate)
      }
    }

    project.tasks.register(NugetTaskNames.PUBLISH) { task ->
      task.group = "publishing"
      task.description =
        "Pushes the NuGet package built by ${NugetTaskNames.PACK} to every configured repository"
      task.dependsOn(project.tasks.withType(PublishNugetTask::class.java))
    }
  }
}
