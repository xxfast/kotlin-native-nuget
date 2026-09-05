package io.github.xxfast.kotlin.native.nuget.processor.forward

/**
 * ADR-063's export predicate as a value: the `include`/`exclude` pair, with the exact matching
 * rules `NugetProcessor.isExported` applies — `exclude` wins, by package prefix *or* by qualified
 * declaration name (issue #53: a sealed base takes its subclasses with it), an empty `include`
 * means "everything", and a non-empty one is a package-prefix test.
 *
 * Extracted (rather than copied) because ADR-109 needs the same predicate for a *second*
 * publisher's scope, arriving as text over a KSP option instead of as this module's own options.
 * The one thing that must NOT be shared is the empty-include reading; see [PublishedScope].
 */
data class PackageScope(
  val include: List<String>,
  val exclude: List<String>,
) {
  fun covers(packageName: String, qualifiedName: String?): Boolean {
    fun matches(prefix: String): Boolean =
      packageName == prefix || packageName.startsWith("$prefix.")

    fun matchesDeclaration(prefix: String): Boolean =
      qualifiedName != null && (qualifiedName == prefix || qualifiedName.startsWith("$prefix."))

    if (exclude.any { matches(it) || matchesDeclaration(it) }) return false
    if (include.isEmpty()) return true
    return include.any(::matches)
  }
}

/**
 * ADR-109: another forward publisher in this same Gradle build, as the processor sees it — a
 * NuGet package id plus the [PackageScope] its own `publish { rootPackage / include / exclude }`
 * lowers to. Delivered by the plugin as the `nuget.publishedScopes` option, because neither
 * module's KSP run can see the other's.
 *
 * A cross-module declaration carries no module identity (`containingFile == null`,
 * `origin == KOTLIN_LIB`, ADR-066), so *by package* is the only match available.
 */
data class PublishedScope(
  val packageId: String,
  val scope: PackageScope,
) {
  /**
   * Deliberately stricter than [PackageScope.covers] in one place: an empty `include` means "all
   * of that publisher's own files", which cannot be lowered to a package list at all. Reading it
   * as "everything" here — the way `isExported` reads it for *this* module's own scope — would
   * warn about every admitted type against a publisher we know nothing about. ADR-109 Decision 2
   * makes that silence a documented gap instead.
   */
  fun covers(packageName: String, qualifiedName: String?): Boolean =
    scope.include.isNotEmpty() && scope.covers(packageName, qualifiedName)
}

/**
 * ADR-109 Decision 2's wire format: entries `;`-separated, fields `:`-separated, lists
 * `|`-separated, `<packageId>:<include1|include2>:<exclude1|exclude2>`.
 *
 * Two entries are dropped rather than carried: this module's own (its `packageId` is
 * [selfPackageId], i.e. `nuget.namespace` — the plugin lists self deliberately so the
 * single-publisher real build exercises the whole delivery path), and one with no `packageId` at
 * all, which no hint could name.
 *
 * A wrong field count fails loudly: package names are `[A-Za-z0-9_.]` and NuGet ids
 * `[A-Za-z0-9._-]`, so neither can contain a delimiter, and a split that does not yield three
 * fields means the two halves of this contract disagree — guessing which field is missing would
 * silently warn about the wrong packages.
 */
internal fun parsePublishedScopes(
  encoded: String?,
  selfPackageId: String,
): List<PublishedScope> = encoded
  .orEmpty()
  .split(";")
  .filter { it.isNotBlank() }
  .map { entry ->
    val fields: List<String> = entry.split(":")
    require(fields.size == 3) {
      "Malformed nuget.publishedScopes entry \"$entry\": expected 3 ':'-separated fields " +
          "(<packageId>:<include1|include2>:<exclude1|exclude2>), got ${fields.size}"
    }
    PublishedScope(
      packageId = fields[0],
      scope = PackageScope(
        include = fields[1].split("|").filter { it.isNotBlank() },
        exclude = fields[2].split("|").filter { it.isNotBlank() },
      ),
    )
  }
  .filter { it.packageId.isNotBlank() && it.packageId != selfPackageId }
