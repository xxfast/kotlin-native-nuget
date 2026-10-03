package io.github.xxfast.kotlin.native.nuget

import org.w3c.dom.Document
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

/** ADR-190: what a dependency's `source` string names, once resolved against the project. */
internal sealed interface DependencySource {
  data class Remote(val url: String) : DependencySource
  data class Directory(val dir: File) : DependencySource
  data class Package(val file: File) : DependencySource
}

internal fun classifySource(id: String, value: String): DependencySource {
  require(!value.startsWith("file://", ignoreCase = true)) {
    "[nuget] Dependency '$id' has source '$value'. A file:// URL is not supported; " +
      "use a plain path to the directory or the .nupkg file."
  }

  if (value.contains("://")) return DependencySource.Remote(value)

  // `<RestoreSources>` is `;`-separated, so a path holding one would silently become two feeds.
  require(!value.contains(';')) {
    "[nuget] Dependency '$id' has source '$value'. A local source path cannot contain ';'."
  }

  if (value.endsWith(".nupkg", ignoreCase = true)) return DependencySource.Package(File(value))
  return DependencySource.Directory(File(value))
}

/**
 * Resolves a DSL `source` against the project directory at wiring time, so a task never calls
 * `project.file(...)` at execution time (the configuration cache forbids it).
 */
internal fun resolveSource(value: String, projectDir: File): String {
  if (value.contains("://")) return value
  val file = File(value)
  val resolved: File = if (file.isAbsolute) file else File(projectDir, value)
  return resolved.normalize().absolutePath
}

internal data class NuspecIdentity(val id: String, val version: String)

/** Reads `<id>` and `<version>` from the single root-level `*.nuspec` entry of [nupkg]. */
internal fun readNuspecIdentity(nupkg: File): NuspecIdentity = ZipFile(nupkg).use { zip ->
  val nuspecs: List<ZipEntry> = zip.entries().toList()
    .filter { entry -> !entry.name.contains('/') && entry.name.endsWith(".nuspec", true) }
  require(nuspecs.size == 1) {
    "[nuget] '$nupkg' must hold exactly one root-level .nuspec, found ${nuspecs.map { it.name }}"
  }

  val factory: DocumentBuilderFactory = DocumentBuilderFactory.newInstance()
  factory.isNamespaceAware = true
  val document: Document = zip.getInputStream(nuspecs.single())
    .use { input -> factory.newDocumentBuilder().parse(input) }

  fun element(name: String): String {
    val text: String? = document.getElementsByTagNameNS("*", name).item(0)?.textContent?.trim()
    require(!text.isNullOrEmpty()) { "[nuget] The .nuspec in '$nupkg' has no <$name>" }
    return text
  }

  NuspecIdentity(id = element("id"), version = element("version"))
}

internal fun sha512(file: File): String = MessageDigest.getInstance("SHA-512")
  .digest(file.readBytes())
  .joinToString("") { byte -> "%02x".format(byte) }

/** Every `.nupkg` a directory feed can serve, at any depth (flat or hierarchical layout). */
internal fun nupkgsIn(dir: File): List<File> = dir.walkTopDown()
  .filter { file -> file.isFile && file.name.endsWith(".nupkg", ignoreCase = true) }
  .toList()

internal fun xmlEscape(value: String): String = value
  .replace("&", "&amp;")
  .replace("<", "&lt;")
  .replace(">", "&gt;")
  .replace("\"", "&quot;")
  .replace("'", "&apos;")

/** The `<RestoreSources>` / `<RestorePackagesPath>` lines both restore sites render. */
internal fun restoreLines(feeds: List<String>, packagesPath: String?): String {
  val sources: String = if (feeds.isEmpty()) {
    ""
  } else {
    val joined: String = (listOf(NUGET_ORG_FEED) + feeds).distinct().joinToString(";")
    "\n    <RestoreSources>${xmlEscape(joined)}</RestoreSources>"
  }

  val packages: String = if (packagesPath == null) {
    ""
  } else {
    "\n    <RestorePackagesPath>${xmlEscape(packagesPath)}</RestorePackagesPath>"
  }

  return sources + packages
}

/**
 * The feeds a restore reads, from resolved DSL sources: remote URLs and directories as they are,
 * and the staged feed directory in place of every `.nupkg` file (a file is not a source, NU1301).
 */
internal fun restoreFeeds(sources: Collection<String>, feedDir: File): List<String> = sources
  .map { value ->
    if (value.endsWith(".nupkg", ignoreCase = true) && !value.contains("://")) feedDir.absolutePath
    else value
  }
  .distinct()

internal data class LocalRestorePlan(
  val versions: Map<String, String>,
  val sources: Map<String, String>,
)

/**
 * ADR-190: checks every local source and stages each `.nupkg` file into [feedDir] as
 * `<id>.<version>.nupkg` (a folder feed finds a package only by that name). [feedDir] is synced: it
 * holds exactly the staged files afterwards.
 */
internal fun stageLocalSources(
  versions: Map<String, String>,
  sources: Map<String, String>,
  feedDir: File,
): LocalRestorePlan {
  if (feedDir.exists()) check(feedDir.deleteRecursively()) { "[nuget] Could not clear $feedDir" }
  feedDir.mkdirs()

  val resolvedVersions: MutableMap<String, String> = versions.toMutableMap()
  val feeds: Map<String, String> = sources.mapValues { (id, value) ->
    when (val source: DependencySource = classifySource(id, value)) {
      is DependencySource.Remote -> source.url

      is DependencySource.Directory -> {
        require(source.dir.isDirectory) {
          "[nuget] Dependency '$id' has source '${source.dir.absolutePath}', " +
            "which is not an existing directory."
        }
        source.dir.absolutePath
      }

      is DependencySource.Package -> {
        val file: File = source.file
        require(file.isFile) {
          "[nuget] Dependency '$id' has source '${file.absolutePath}', which does not exist."
        }

        val identity: NuspecIdentity = readNuspecIdentity(file)
        require(identity.id.equals(id, ignoreCase = true)) {
          "[nuget] Dependency '$id' has source '${file.absolutePath}', " +
            "but that package's id is '${identity.id}'."
        }

        val declared: String? = versions[id]
        require(declared == null || declared == identity.version) {
          "[nuget] Dependency '$id' declares version $declared, but its source " +
            "'${file.absolutePath}' is version ${identity.version}. Drop the version or match it."
        }

        file.copyTo(File(feedDir, "${identity.id}.${identity.version}.nupkg"), overwrite = true)
        resolvedVersions[id] = identity.version
        feedDir.absolutePath
      }
    }
  }

  return LocalRestorePlan(resolvedVersions, feeds)
}

/** ADR-190: each declared `source`, local paths resolved against [projectDir]. */
internal fun Iterable<NugetDependency>.resolvedSources(projectDir: File): Map<String, String> =
  filter { dependency -> dependency.source.isPresent }
    .associate { dependency -> dependency.id to resolveSource(dependency.source.get(), projectDir) }

/** The local (non-URL) entries of [sources]. */
internal fun localOnly(sources: Map<String, String>): Map<String, String> =
  sources.filterValues { value -> !value.contains("://") }

/**
 * ADR-190: NuGet treats an extracted `id + version` as immutable, so a same-version rebuild is
 * never re-read. Deletes the extracted folder of every id a local feed serves (read from each
 * `.nuspec`, not the file name), which makes the next restore extract the current bytes.
 */
internal fun evictLocalPackages(feeds: Collection<File>, packagesDir: File) {
  val ids: Set<String> = feeds
    .filter { feed -> feed.isDirectory }
    .flatMap { feed -> nupkgsIn(feed) }
    .map { nupkg -> readNuspecIdentity(nupkg).id.lowercase() }
    .toSet()

  packagesDir.listFiles().orEmpty()
    .filter { folder -> folder.name.lowercase() in ids }
    .forEach { folder ->
      check(folder.deleteRecursively() && !folder.exists()) {
        "[nuget] Could not evict the extracted package '$folder' before restore; a process may " +
          "still hold a file in it. Close it and re-run, or the old assembly would be bound."
      }
    }
}

/**
 * ADR-190: after restore, the extracted `.nupkg` of every dependency with a local source must be
 * byte-identical to one in that source. Anything else (an eviction miss, another feed serving the
 * same id and version) would bind an assembly the author did not build, so it fails.
 */
internal fun verifyLocalPackages(localSources: Map<String, String>, folders: Map<String, File>) {
  localSources.forEach { (id, value) ->
    val folder: File = checkNotNull(folders[id]) {
      "[nuget] Dependency '$id' has local source '$value', but restore resolved no package for it."
    }

    val extracted: File = folder.listFiles().orEmpty()
      .singleOrNull { file -> file.name.endsWith(".nupkg", ignoreCase = true) }
      ?: error("[nuget] Dependency '$id': expected exactly one .nupkg in '$folder' after restore.")

    val source = File(value)
    val candidates: List<File> = if (source.isDirectory) nupkgsIn(source) else listOf(source)
    val hash: String = sha512(extracted)
    check(candidates.any { candidate -> sha512(candidate) == hash }) {
      "[nuget] Dependency '$id' restored '${extracted.absolutePath}', which matches no .nupkg " +
        "in its source '$value'. Another feed served the same id and version, or a stale " +
        "copy survived eviction. Make sure the source holds the package; for a stale copy, " +
        "run `./gradlew clean` and build again."
    }
  }
}
