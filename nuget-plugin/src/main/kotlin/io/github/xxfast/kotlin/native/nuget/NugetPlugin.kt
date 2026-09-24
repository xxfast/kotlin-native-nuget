package io.github.xxfast.kotlin.native.nuget

import io.github.xxfast.kotlin.native.nuget.rir.deriveResolvedVersions
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.SharedLibrary
import java.lang.reflect.Method

// ADR-093: PackNugetTask reads the value set to name the RIDs this plugin version can build when
// it warns about an unknown prebuilt RID.
internal val KONAN_TO_RID = mapOf(
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
            // ADR-088: beside the generated Kotlin, not inside it — see the task property.
            task.boundTypesManifestFile.set(interopDir.map { it.file("bound-types.json") })
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
            // ADR-087 stage 2: the same value the forward KSP run uses for `nuget.namespace`, so
            // the reverse shims can throw through the forward error mapping instead of owning a
            // second copy of ADR-029's table.
            task.forwardNamespace.set(project.provider { extension.publish?.packageId ?: "" })
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

      // ADR-127: the fixed 67-name `nuget_*` ABI ships as a klib instead of being regenerated
      // into every consumer. Resolved exactly as the processor is: the in-repo project when this
      // is the composite build, the published coordinate at this plugin's own version otherwise,
      // so the generator and the runtime cannot skew on the supported path.
      val runtimeDep: Any = project.findProject(":nuget-runtime")
        ?: "io.github.xxfast:nuget-runtime:$PLUGIN_VERSION"

      // ADR-156: a method bound from a C# `IAsyncEnumerable<T>` return names
      // `kotlinx.coroutines.flow.Flow` in a PUBLIC signature of a generated class, and generated
      // classes compile from `nativeMain` (see the srcDir wiring above) while `nuget-runtime` —
      // and coroutines through its `api` — reaches only `${target}MainApi`. Without this a
      // consumer that does not itself declare coroutines fails with `Unresolved reference: Flow`.
      // ADR-130's objection to putting the RUNTIME here (it publishes no iOS variant) does not
      // apply: kotlinx-coroutines-core publishes every native target. `api`, not
      // `implementation`: the type is in a public signature.
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
        }
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

          // ADR-063 "Reverse-bound packages are always in scope": the superset of Kotlin
          // packages each bound dependency's reverse-generated stubs can land in, mirroring
          // `kotlinPackage()`'s resolution order (`NugetGenerateBindingsTask.kt:66-73`): the
          // namespace aliases, the `packageName` override, and the sanitised `packageId`
          // fallback. That way an include-based filter can never drop a bound stub the module's
          // own forward code returns.
          val boundPackages: List<String> = extension.dependencies
            .filter { it.bind != null }
            .flatMap { dep ->
              val bind = dep.bind!!
              buildList {
                addAll(bind.aliases.values)
                bind.packageName?.let(::add)
                add(dep.id.lowercase().replace('-', '_'))
              }
            }
            .distinct()

          argMethod.invoke(ksp, "nuget.libraryName", baseName ?: "library")
          argMethod.invoke(ksp, "nuget.namespace", pub?.packageId ?: "")
          argMethod.invoke(ksp, "nuget.rootPackage", pub?.rootPackage ?: "")
          argMethod.invoke(ksp, "nuget.className", "${pub?.packageId ?: "Library"}Native")
          argMethod.invoke(ksp, "nuget.includePackages", pub?.include.orEmpty().joinToString(","))
          argMethod.invoke(ksp, "nuget.excludePackages", pub?.exclude.orEmpty().joinToString(","))
          argMethod.invoke(ksp, "nuget.boundPackages", boundPackages.joinToString(","))
          // ADR-154: the additive dependency-admission entries, on the same comma-joined channel
          // as include/exclude. No Kotlin qualified name or package prefix can contain a comma, so
          // the join is unambiguous. Empty is the shipped default (admission by `include(...)`
          // alone).
          argMethod.invoke(ksp, "nuget.admit", pub?.admit.orEmpty().joinToString(","))
          // ADR-154 §6: opt-in strictness, lowered as a plain boolean string. Absent or "false"
          // keeps ADR-066 section 4's warn-and-skip default.
          argMethod.invoke(
            ksp,
            "nuget.strictDependencyTypes",
            (pub?.strictDependencyTypes ?: false).toString(),
          )
          // ADR-115 amendment: the markers this publisher waives, on the same channel as
          // include/exclude. Empty is the shipped default: every marked declaration keeps skipping.
          argMethod.invoke(
            ksp,
            "nuget.exportMarkers",
            pub?.exportMarkers.orEmpty().joinToString(","),
          )

          // ADR-088: the same channel as `nuget.boundPackages`, carrying what a flat package list
          // cannot — the ORIGINAL C# full name per bound interface, and whether a Kotlin class can
          // implement it. Empty when nothing is bound (the manifest task never ran, so pointing at
          // a path would promise a file that does not exist). Ordering is already guaranteed:
          // `kspKotlin{Target}` dependsOn `nugetGenerateBindings`.
          val boundTypesManifest: String = if (boundPackages.isEmpty()) "" else {
            project.layout.buildDirectory.get().asFile
              .resolve("nuget-interop/bound-types.json").absolutePath
          }
          argMethod.invoke(ksp, "nuget.boundTypesManifest", boundTypesManifest)

          // ADR-109: the ADR-063 export predicate of EVERY forward publisher in this Gradle
          // build, this project included, lowered to packages because the processor can only
          // match an admitted klib type by package (a cross-module declaration carries no
          // module identity: `containingFile == null`, `origin == KOTLIN_LIB`).
          //
          // A Provider, not a String: its body runs when KSP resolves its options, after every
          // project in the build is evaluated, so no cross-project read happens inside
          // `afterEvaluate` — where seeing a not-yet-evaluated sibling publisher would need
          // `project.evaluationDependsOn(other)`, which is circular the moment two publishers
          // each do it to the other (ADR-109 Alternative 2).
          //
          // Self is listed deliberately, and dropped by the processor (its entry's packageId
          // equals its own `nuget.namespace`), so the single-publisher real build still
          // exercises the whole delivery path. A project with no `publish {}` has no export
          // scope of its own and registers nothing at all.
          //
          // The body reads other projects' extensions: legal today, and the first thing that
          // breaks if project isolation is ever enabled (it is not; configuration cache alone
          // permits this).
          if (pub != null) {
            val publishedScopes: Provider<String> = project.provider {
              project.rootProject.allprojects
                .mapNotNull { other ->
                  other.extensions.findByType(NugetExtension::class.java)?.publish
                }
                .map { config ->
                  // Mirrors `effectiveInclude` (`NugetProcessor.kt`): the explicit `include(...)`
                  // list when non-empty, else `[rootPackage]`, else empty — which the processor
                  // treats as "unknown scope" and stays silent about (ADR-109's documented gap).
                  val include: List<String> = config.include
                    .ifEmpty { listOfNotNull(config.rootPackage?.takeIf { it.isNotBlank() }) }
                  listOf(
                    config.packageId.orEmpty(),
                    include.joinToString("|"),
                    config.exclude.joinToString("|"),
                  ).joinToString(":")
                }
                // Sorted for a stable configuration-cache input: the value must not depend on
                // the order Gradle happens to evaluate sibling projects in.
                .sorted()
                .joinToString(";")
            }

            val providerArgMethod: Method =
              kspClass.getMethod("arg", String::class.java, Provider::class.java)
            providerArgMethod.invoke(ksp, "nuget.publishedScopes", publishedScopes)
          }
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

          // ADR-093: a target this host cannot link never enters libDirs, so nativeLibDirs means
          // "RIDs this host will actually produce" and packNuget can be strict about an empty one.
          if (!sharedLib.linkTaskProvider.get().enabled) {
            project.logger.lifecycle(
              "[nuget] Skipping RID '$rid': the link task for target '${target.name}' is disabled " +
                  "on this host. Supply it from another host via " +
                  "nuget { publish { prebuiltRuntimes = ... } } to ship it in this package."
            )
            continue
          }

          libDirs[rid] = sharedLib.outputDirectory.absolutePath
          linkTasks.add(sharedLib.linkTaskProvider)

          if (baseName == null) {
            baseName = sharedLib.baseName
          }
        }

        if (libDirs.isEmpty() && pub.prebuiltRuntimes == null) return@afterEvaluate

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

        // ADR-092: `snapshot = true` replaces the declared version with one minted at execution
        // time, and emits the props file consumers import to reference it.
        val snapshot: SnapshotVersioning? =
          if (pub.snapshot) registerSnapshotVersioning(project, pub) else null

        // ADR-100: the forward direction's diagnostics reach a console only through Gradle's own
        // logger, and only if something speaks on cached builds too. `NugetDiagnostics.json` is a
        // declared KSP output, so it is there even when `kspKotlin{Target}` is FROM-CACHE or
        // UP-TO-DATE; this task is never up-to-date and re-emits it ahead of every packNuget.
        val kspTask: String = "kspKotlin${firstTarget.replaceFirstChar { it.uppercase() }}"
        val reportDiagnostics: TaskProvider<NugetReportDiagnosticsTask> = project.tasks
          .register("nugetReportDiagnostics", NugetReportDiagnosticsTask::class.java) { task ->
            task.group = "nuget"
            task.description = "Reports declarations the forward bridge could not generate"
            task.diagnosticsFiles.from(kspOutputDir)
            task.dependsOn(kspTask)
          }

        // Hoisted above both register calls so packNuget and nugetCompileInterop share one
        // resolved-version provider and one shims dir: the check must compile exactly the files,
        // at exactly the package versions, the pack ships.
        val nugetGenerateShims: TaskProvider<NugetGenerateShimsTask>? =
          if (boundDeps.isEmpty()) null
          else project.tasks.named("nugetGenerateShims", NugetGenerateShimsTask::class.java)

        val resolvedVersions: Provider<Map<String, String>> = if (boundDeps.isEmpty()) {
          project.provider { emptyMap() }
        } else {
          val boundIds: Set<String> = boundDeps.map { it.id }.toSet()
          project.tasks.named("nugetRestore", NugetRestoreTask::class.java)
            .flatMap { restore ->
              restore.assetsFile.map { assetsFile ->
                deriveResolvedVersions(assetsFile.asFile.readText(), boundIds)
              }
            }
        }

        // ADR-138: the generated C# ships as source and is compiled in the consumer's build, so
        // nothing in packNuget can reject a binding that does not compile. This sibling task
        // (the nugetReportDiagnostics precedent) compiles the same files with dotnet first, and
        // skips with a warning when no .NET SDK is installed.
        val compileInterop: TaskProvider<NugetCompileInteropTask> = project.tasks
          .register("nugetCompileInterop", NugetCompileInteropTask::class.java) { task ->
            task.group = "nuget"
            task.description =
              "Compiles the generated C# bindings with dotnet before packNuget stages them"
            task.generatedCsDirs.from(kspOutputDir)
            task.projectDir.set(project.layout.buildDirectory.dir("nuget-compile"))
            task.dotnetSearchPath.set(project.providers.environmentVariable("PATH"))
            task.dependencySources.set(extension.dependencies.mapNotNull { it.source }.distinct())
            task.dependencyVersions.set(resolvedVersions)
            task.dependsOn(kspTask)

            if (nugetGenerateShims != null) {
              task.generatedCsDirs.from(nugetGenerateShims.flatMap { it.csharpOutputDir })
              task.dependsOn(nugetGenerateShims)
            }
          }

        val packNuget: TaskProvider<PackNugetTask> =
          project.tasks.register("packNuget", PackNugetTask::class.java)
        packNuget.configure { task ->
          task.group = "nuget"
          task.description = "Packages the Kotlin/Native shared library as a NuGet package"
          task.packageId.set(pub.packageId)

          if (snapshot == null) {
            task.packageVersion.set(pub.version)
          } else {
            task.packageVersion.set(snapshot.version)
            task.dependsOn(snapshot.versionTask, snapshot.propsTask)
          }

          task.authors.set(pub.authors)
          task.packageDescription.set(pub.description)
          task.nativeLibDirs.set(libDirs)
          task.nativeLibFiles.from(libDirs.values.map { project.fileTree(it) })

          if (pub.prebuiltRuntimes != null) {
            task.prebuiltRuntimesDir.set(pub.prebuiltRuntimes)
          }

          task.generatedCsDirs.from(kspOutputDir)
          task.outputDir.set(project.layout.buildDirectory.dir("nuget"))

          linkTasks.forEach { task.dependsOn(it) }

          task.dependsOn(kspTask)
          task.dependsOn(reportDiagnostics)
          task.dependsOn(compileInterop)
          task.dependencyVersions.set(resolvedVersions)

          if (nugetGenerateShims != null) {
            task.generatedCsDirs.from(nugetGenerateShims.flatMap { it.csharpOutputDir })
            task.dependsOn(nugetGenerateShims)
          }
        }

        registerPublishing(project, pub, packNuget)
      }
    }
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

    val tasks: List<TaskProvider<PublishNugetTask>> = pub.repositories.map { repository ->
      val name: String = repository.name
      val url: String = requireNotNull(repository.url) {
        "nuget { publish { repositories { nuget(\"$name\") { url = ... } } } } " +
          "needs the feed's v3 service index url"
      }

      project.tasks.register(
        "publishNugetTo${name.replaceFirstChar { it.uppercase() }}Repository",
        PublishNugetTask::class.java,
      ) { task ->
        task.group = "publishing"
        task.description = "Pushes the NuGet package built by packNuget to the '$name' repository"
        task.dependsOn(packNuget)
        task.packageFile.set(file)
        task.repositoryName.set(name)
        task.repositoryUrl.set(url)
        task.apiKey.set(
          repository.apiKey ?: project.providers.gradleProperty(repository.apiKeyProperty),
        )
        task.username.set(
          repository.username ?: project.providers.gradleProperty(repository.usernameProperty),
        )
        task.password.set(
          repository.password ?: project.providers.gradleProperty(repository.passwordProperty),
        )
        task.dryRun.convention(false)
        task.skipDuplicate.convention(repository.skipDuplicate)
      }
    }

    project.tasks.register("publishNuget") { task ->
      task.group = "publishing"
      task.description =
        "Pushes the NuGet package built by packNuget to every configured repository"
      task.dependsOn(tasks)
    }
  }
}
