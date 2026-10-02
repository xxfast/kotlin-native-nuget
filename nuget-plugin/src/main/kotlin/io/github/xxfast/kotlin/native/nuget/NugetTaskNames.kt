package io.github.xxfast.kotlin.native.nuget

/**
 * Every task name the plugin registers, in one place (ADR-182 section 2). Tasks that produce or
 * publish the package are verb-first, as Kotlin's Gradle plugin names them (`packNuget`,
 * `publishNuget`, `publishNugetTo<Name>Repository`); steps that drive the .NET tool or generate
 * inputs are `nuget`-prefixed (`nugetRestore`, `nugetGenerateRestoreProject`, `nugetSnapshotVersion`).
 * Registrations, lookups and messages read these; no other file spells a task name as a string.
 */
internal object NugetTaskNames {
  const val GENERATE_RESTORE_PROJECT: String = "nugetGenerateRestoreProject"
  const val RESTORE: String = "nugetRestore"
  const val IMPORT: String = "nugetImport"
  const val EXTRACT_API: String = "nugetExtractApi"
  const val GENERATE_BINDINGS: String = "nugetGenerateBindings"
  const val GENERATE_SHIMS: String = "nugetGenerateShims"
  const val REPORT_DIAGNOSTICS: String = "nugetReportDiagnostics"
  const val COMPILE_INTEROP: String = "nugetCompileInterop"
  const val PACK: String = "packNuget"
  const val PUBLISH: String = "publishNuget"
  const val SNAPSHOT_VERSION: String = "nugetSnapshotVersion"
  const val SNAPSHOT_VERSION_PROPS: String = "nugetSnapshotVersionProps"

  /**
   * The ADR-165 push task for one named repository, `maven-publish`'s `To<Name>Repository`
   * tail.
   */
  fun publishTo(repository: String): String =
    "publishNugetTo${repository.replaceFirstChar { it.uppercase() }}Repository"
}
