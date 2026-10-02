package io.github.xxfast.kotlin.native.nuget

import org.gradle.api.Action
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import javax.inject.Inject

@NugetDsl
public abstract class NugetPublishConfig @Inject constructor(objects: ObjectFactory) {
  public abstract val packageId: Property<String>
  public abstract val version: Property<String>
  public abstract val authors: Property<String>
  public abstract val description: Property<String>
  public abstract val rootPackage: Property<String>

  // ADR-092: derive `<version>-snapshot.<epochMillis>` at execution time, so successive local
  // builds produce distinct package identities instead of hitting NuGet's immutable-version cache.
  public abstract val snapshot: Property<Boolean>

  // ADR-092: where to write the MSBuild props file pinning the minted snapshot version.
  // Default: <rootProject>/build/<packageId>Versions.props (the plugin sets the convention).
  // Only consulted when snapshot is true.
  public abstract val versionPropsFile: RegularFileProperty

  // ADR-093: a directory of native libraries built on another host, laid out exactly like the
  // runtimes/ tree packNuget stages: <dir>/<rid>/native/ holding dll, dylib or so files. Merged
  // into this host's own linked output so one pack produces one multi-RID package.
  public abstract val prebuiltRuntimes: DirectoryProperty

  // ADR-154 §6: opt-in. Every skip whose reason is a dependency-scope refusal the author can act
  // on (`NOT_INCLUDED`, `CROSS_MODULE_ADMISSION_DISABLED`) becomes an ERROR instead of a warning,
  // so every dependency type in a public signature is either admitted or excluded BY NAME. A skip
  // the author already declared deliberate — `exclude(...)` — stays a warning, which is what
  // makes the pair a once-per-type decision rather than a wall. Default false: ADR-066
  // section 4's "named diagnostic + skip, never a hard error" is still the shipped
  // behaviour.
  public abstract val strictDependencyTypes: Property<Boolean>

  public abstract val include: ListProperty<String>
  public abstract val exclude: ListProperty<String>

  // ADR-154: the additive dependency-admission verb. `include(...)` does two jobs (roots for this
  // module's own files, and package-level admission into ADR-066's reachability closure); `admit`
  // does only the second, and only additively — it never picks roots, never replaces the
  // `rootPackage` default (the #55/#60 decision is untouched) and can never resurrect an
  // own-module declaration `include`/`exclude` filtered out.
  //
  // Entries take the exact matcher `exclude` already uses (issue #53): a qualified type name, or a
  // package prefix, both tested with `isUnderPackage`. `exclude` still wins, and an opt-in marker
  // (ADR-115) still refuses.
  public abstract val admit: ListProperty<String>

  // ADR-115 amendment: fully-qualified `@RequiresOptIn` marker names whose declarations keep
  // exporting despite carrying them. The inverse of binary-compatibility-validator's
  // `nonPublicMarkers`: that lists what to hide, this lists what to keep. Entries are trusted
  // unvalidated; a misspelt one simply waives nothing, and the SKIPPED_OPT_IN_MARKER warning next
  // to it prints the correct FQN.
  public abstract val exportMarkers: ListProperty<String>

  // Keyed by repository name. Iterates in name order, not declaration order.
  public val repositories: NamedDomainObjectContainer<NugetRepository> =
    objects.domainObjectContainer(NugetRepository::class.java)

  init {
    snapshot.convention(false)
    strictDependencyTypes.convention(false)
  }

  // An explicit function, not only the container: without it `repositories { }` inside
  // `publish {}` resolves to `Project.repositories` (verified in ADR-180's spike).
  public fun repositories(action: Action<in NugetRepositoriesScope>) {
    action.execute(NugetRepositoriesScope(repositories))
  }

  public fun include(vararg packages: String) {
    include.addAll(*packages)
  }

  public fun exclude(vararg packages: String) {
    exclude.addAll(*packages)
  }

  /** ADR-154: qualified type names and/or package prefixes, additive, dependency-only. */
  public fun admit(vararg types: String) {
    admit.addAll(*types)
  }

  public fun exportMarkers(vararg markers: String) {
    exportMarkers.addAll(*markers)
  }
}

/**
 * The forward C# root namespace: `packageId`, or the processor's `Interop` default when there is
 * no `publish {}` or its `packageId` is unset or blank. One rule for both the KSP `nuget.namespace`
 * option and the reverse shims' ADR-087 error namespace, so the two halves always agree.
 */
internal fun NugetPublishConfig.forwardNamespace(): Provider<String> =
  packageId.map { id -> id.ifBlank { "Interop" } }.orElse("Interop")
