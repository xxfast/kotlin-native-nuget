package io.github.xxfast.kotlin.native.nuget

import java.io.File

class NugetPublishConfig {
  var packageId: String? = null
  var version: String? = null
  var authors: String? = null
  var description: String? = null
  var rootPackage: String? = null

  // ADR-092: derive `<version>-snapshot.<epochMillis>` at execution time, so successive local
  // builds produce distinct package identities instead of hitting NuGet's immutable-version cache.
  var snapshot: Boolean = false

  // ADR-092: where to write the MSBuild props file pinning the minted snapshot version.
  // Default: <rootProject>/build/<packageId>Versions.props. Only consulted when snapshot is true.
  var versionPropsFile: File? = null

  // ADR-093: a directory of native libraries built on another host, laid out exactly like the
  // runtimes/ tree packNuget stages: <dir>/<rid>/native/ holding dll, dylib or so files. Merged
  // into this host's own linked output so one pack produces one multi-RID package.
  var prebuiltRuntimes: File? = null

  private val _include = mutableListOf<String>()
  private val _exclude = mutableListOf<String>()

  // ADR-154: the additive dependency-admission verb. `include(...)` does two jobs (roots for this
  // module's own files, and package-level admission into ADR-066's reachability closure); `admit`
  // does only the second, and only additively — it never picks roots, never replaces the
  // `rootPackage` default (the #55/#60 decision is untouched) and can never resurrect an
  // own-module declaration `include`/`exclude` filtered out.
  //
  // Entries take the exact matcher `exclude` already uses (issue #53): a qualified type name, or a
  // package prefix, both tested with `isUnderPackage`. `exclude` still wins, and an opt-in marker
  // (ADR-115) still refuses.
  private val _admit = mutableListOf<String>()

  // ADR-115 amendment: fully-qualified `@RequiresOptIn` marker names whose declarations keep
  // exporting despite carrying them. The inverse of binary-compatibility-validator's
  // `nonPublicMarkers`: that lists what to hide, this lists what to keep. Entries are trusted
  // unvalidated; a misspelt one simply waives nothing, and the SKIPPED_OPT_IN_MARKER warning next
  // to it prints the correct FQN.
  private val _exportMarkers = mutableListOf<String>()

  val include: List<String> get() = _include.toList()
  val exclude: List<String> get() = _exclude.toList()
  val admit: List<String> get() = _admit.toList()
  val exportMarkers: List<String> get() = _exportMarkers.toList()

  private val _repositories = mutableListOf<NugetRepository>()
  val repositories: List<NugetRepository> get() = _repositories.toList()

  fun repositories(configure: NugetRepositoriesScope.() -> Unit) {
    NugetRepositoriesScope(_repositories).configure()
  }

  // ADR-154 §6: opt-in. Every skip whose reason is a dependency-scope refusal the author can act
  // on (`NOT_INCLUDED`, `CROSS_MODULE_ADMISSION_DISABLED`) becomes an ERROR instead of a warning,
  // so every dependency type in a public signature is either admitted or excluded BY NAME. A skip
  // the author already declared deliberate — `exclude(...)` — stays a warning, which is what makes
  // the pair a once-per-type decision rather than a wall. Default false: ADR-066 section 4's
  // "named diagnostic + skip, never a hard error" is still the shipped behaviour.
  var strictDependencyTypes: Boolean = false

  fun include(vararg packages: String) {
    _include.addAll(packages)
  }

  fun exclude(vararg packages: String) {
    _exclude.addAll(packages)
  }

  /** ADR-154: qualified type names and/or package prefixes, additive, dependency-only. */
  fun admit(vararg types: String) {
    _admit.addAll(types)
  }

  fun exportMarkers(vararg markers: String) {
    _exportMarkers.addAll(markers)
  }
}
