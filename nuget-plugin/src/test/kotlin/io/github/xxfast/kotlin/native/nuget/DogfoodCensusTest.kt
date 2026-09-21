package io.github.xxfast.kotlin.native.nuget

import io.github.xxfast.kotlin.native.nuget.rir.Census
import io.github.xxfast.kotlin.native.nuget.rir.RirFile
import io.github.xxfast.kotlin.native.nuget.rir.census
import io.github.xxfast.kotlin.native.nuget.rir.parseReverseIr
import io.github.xxfast.kotlin.native.nuget.rir.readerFailureCensus
import io.github.xxfast.kotlin.native.nuget.rir.toStableJson
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The reverse dogfooding census (`./gradlew -p nuget-plugin dogfoodCensus`, or
 * `scripts/verify-dogfood.sh`). Runs the WHOLE reverse pipeline over nine pinned published NuGet
 * packages and compares each package's census against its committed golden, exactly.
 *
 * Tagged `dogfood` so the ordinary `test` task (and therefore `verify.sh` and every PR matrix leg)
 * never depends on nuget.org. Unlike the integration tests it FAILS when `dotnet` is absent: a
 * census that silently measures nothing is worse than no census.
 *
 * A golden diff is the entire point. When a bridge change starts binding something new, rerun with
 * `--update` and put the diff in the PR.
 */
@Tag("dogfood")
class DogfoodCensusTest {
  private data class DogfoodPackage(
    val id: String,
    val version: String,
    val includes: List<String>,
  )

  private fun packages(): List<DogfoodPackage> {
    val text: String = requireNotNull(
      javaClass.classLoader.getResourceAsStream("dogfood/packages.json"),
    ) { "dogfood/packages.json missing from the test resources" }.reader().readText()
    return Json.parseToJsonElement(text).jsonArray.map { entry ->
      val obj = entry.jsonObject
      DogfoodPackage(
        id = obj.getValue("id").jsonPrimitive.content,
        version = obj.getValue("version").jsonPrimitive.content,
        includes = obj.getValue("includes").jsonArray.map { it.jsonPrimitive.content },
      )
    }
  }

  @Test
  fun `census matches the committed goldens for every pinned real package`() {
    val dotnet: String = requireNotNull(RealPackageFixture.findDotnet()) {
      "dogfoodCensus needs dotnet on PATH; it measures the real pipeline and must not no-op"
    }
    val goldenDir = File(
      requireNotNull(System.getProperty("dogfood.goldenDir")) {
        "dogfood.goldenDir is not set; run through the dogfoodCensus task, which points it at the " +
            "SOURCE resources dir (a build/ copy would make --update write into the void)"
      },
    )
    val update: Boolean = System.getProperty("dogfood.update") == "true"
    val readerDir: File = RealPackageFixture.unpackReader(javaClass.classLoader)

    val censuses: MutableList<Census> = mutableListOf()
    val drifted: MutableList<String> = mutableListOf()

    // One package at a time, and never an early return: a package whose reader crashes must still
    // leave the other eight rows measured.
    packages().forEach { pkg ->
      val actual: Census = censusFor(dotnet, readerDir, pkg)
      censuses += actual
      val golden = File(goldenDir, "${pkg.id}.${pkg.version}.census.json")
      val rendered: String = actual.toStableJson()
      if (update) {
        golden.writeText(rendered)
        return@forEach
      }
      if (!golden.exists()) {
        drifted += "${pkg.id}: no golden at ${golden.name}; rerun with -Pdogfood.update=true"
        return@forEach
      }
      // Normalized: this developer's checkout is CRLF and CI's is LF, and a line-ending diff is
      // not a census finding.
      val expected: String = golden.readText().replace("\r\n", "\n")
      if (expected != rendered.replace("\r\n", "\n")) {
        drifted += "${pkg.id} ${pkg.version} drifted:\n--- golden\n$expected\n--- actual\n$rendered"
      }
    }

    File(goldenDir, "SUMMARY.md").takeIf { update }?.writeText(summary(censuses))

    if (drifted.isNotEmpty()) {
      fail(
        "the reverse census drifted for ${drifted.size} package(s). If the change is intended, " +
            "rerun `scripts/verify-dogfood.sh --update` and put the golden diff in the PR.\n\n" +
            drifted.joinToString("\n\n"),
      )
    }

    // The harness' own liveness check: a green run that measured nothing is the failure mode a
    // silent `dotnet` skip used to hide.
    assertTrue(censuses.any { it.reader == "ok" }, "no package's reader succeeded at all")
  }

  private fun censusFor(dotnet: String, readerDir: File, pkg: DogfoodPackage): Census {
    val dllPaths: Map<String, List<String>> =
      RealPackageFixture.restore(dotnet, pkg.id, pkg.version)
    val outcome: RealPackageFixture.ReaderOutcome = RealPackageFixture.readerOutcome(
      dotnet = dotnet,
      readerProjectDir = readerDir,
      id = pkg.id,
      version = pkg.version,
      dllPaths = dllPaths,
      includes = pkg.includes,
    )
    if (outcome.exitCode != 0) {
      return readerFailureCensus(
        packageName = pkg.id,
        version = pkg.version,
        asset = outcome.asset,
        includes = pkg.includes,
        readerError = RealPackageFixture.sanitizeReaderError(
          outcome.stderr, pkg.id, pkg.version,
        ),
      )
    }

    val rir: RirFile = parseReverseIr(outcome.stdout)
    // "Generation" here means exactly what it checked: both generators ran without throwing. It
    // does NOT mean the output compiles, which is a later layer of this harness.
    val generation: Result<Unit> = runCatching {
      generateKotlinStubs(rir)
      generateCSharpShims(rir, nativeLibraryName = "dogfood")
    }
    return census(
      rir = rir,
      packageName = pkg.id,
      version = pkg.version,
      asset = outcome.asset,
      includes = pkg.includes,
      generation = if (generation.isSuccess) "ok" else "failed",
      generationError = generation.exceptionOrNull()?.message?.lineSequence()?.first()?.trim(),
    )
  }

  private fun summary(censuses: List<Census>): String {
    val rows: String = censuses.joinToString("\n") { c ->
      val bound: Int = c.members.values.sumOf { it.bound }
      val surface: Int = c.publicSurface?.members ?: 0
      val share: String =
        if (surface == 0) "n/a" else "${(bound * 100.0 / surface).let { "%.1f".format(it) }} percent"
      "| ${c.packageName} ${c.version} | `${c.asset}` | ${c.reader} | ${c.generation} | " +
          "$bound | $surface | $share | ${c.collapsedOverloads.sets} | " +
          "${c.collapsedOverloads.bridgeableMethods} |"
    }
    val sets: Int = censuses.sumOf { it.collapsedOverloads.sets }
    val dropped: Int = censuses.sumOf { it.collapsedOverloads.membersDropped }
    val bridgeable: Int = censuses.sumOf { it.collapsedOverloads.bridgeableMethods }
    val kinds: Map<String, Int> = censuses
      .flatMap { it.diagnostics.entries }
      .groupingBy { it.key }
      .fold(0) { total, entry -> total + entry.value }
      .toSortedMap()
    val unbound: List<Pair<String, Int>> = censuses
      .flatMap { it.unboundTypeReferences.entries }
      .groupingBy { it.key }
      .fold(0) { total, entry -> total + entry.value }
      .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
      .take(20)
      .map { it.key to it.value }

    return buildString {
      appendLine("# Reverse census over real published NuGet packages")
      appendLine()
      appendLine("Generated by `./gradlew -p nuget-plugin dogfoodCensus -Pdogfood.update=true`")
      appendLine("(`scripts/verify-dogfood.sh --update`). Do not hand-edit.")
      appendLine()
      appendLine("What the ratio counts: `bound` is the members the plugin's own `bridgeable*`")
      appendLine("filters keep, and `public` is an independent count the metadata reader takes off")
      appendLine("the assembly BEFORE its top-level-only type filter and before any bridgeability")
      appendLine("decision. So the denominator includes members this project drops with no")
      appendLine("diagnostic at all: the members of nested public types, the members of a struct")
      appendLine("that fails ADR-056, and every operator. Those are real gaps and the ratio is")
      appendLine("meant to be held against them.")
      appendLine()
      appendLine("What the ratio does NOT count: protected and internal members (out of scope),")
      appendLine("inherited members (the RIR carries no base type, so a derived wrapper never")
      appendLine("gets them and they are in neither half), and anything about CORRECTNESS.")
      appendLine("`generation: ok` means both generators returned without throwing. It does not")
      appendLine("mean the generated Kotlin or C# compiles; nothing here compiles anything.")
      appendLine()
      appendLine(
        "| Package | Asset | Reader | Generation | Bound | Public members | Share | " +
            "Collapsed sets | Bridgeable methods |",
      )
      appendLine("|---|---|---|---|---|---|---|---|---|")
      appendLine(rows)
      appendLine()
      appendLine("## Collapsed overload sets (ADR-155)")
      appendLine()
      appendLine(
        "Measured across the whole run: **$sets** collapsed set(s), **$dropped** member(s) " +
            "dropped, out of **$bridgeable** bridgeable methods.",
      )
      appendLine()
      appendLine("This is the frequency measurement the ROADMAP line asks for: how often a C#")
      appendLine("overload pair really does collapse onto one Kotlin collection signature in")
      appendLine("published packages. A set only reaches the check if BOTH siblings are already")
      appendLine("bridgeable, so array and `Nullable<T>` siblings never get there.")
      appendLine()
      appendLine("## Diagnostics by kind, whole run")
      appendLine()
      kinds.forEach { (kind, count) -> appendLine("- `$kind`: $count") }
      appendLine()
      appendLine("## Most demanded unmapped type references")
      appendLine()
      appendLine("Parsed out of each diagnostic's prose, so approximate. This ranks which BCL type")
      appendLine("to map next by real demand rather than by guess.")
      appendLine()
      unbound.forEach { (name, count) -> appendLine("- `$name`: $count") }
    }
  }
}
