package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-066: the two diagnostic kinds the reachability closure adds, `SKIPPED_UNEXPORTED_DEPENDENCY
 * _TYPE` and `INFO_EXPORTED_FROM_DEPENDENCY` — deliberately KSP-build-log-only (the ADR's
 * `include(...)` fix is a message a build log carries, not something visible from compiled C#, so
 * this belongs at the Tier 1 unit level rather than the `:test-library`/`NewsroomReachabilityTests
 * .cs` integration fixture, which already exercises the successful-admission path end to end).
 *
 * [Tier1DependencyLibrary] compiles a genuinely separate `.jar` so the fixture crosses a real
 * compilation-unit boundary (`Origin.KOTLIN_LIB`, `containingFile == null`) the way a Gradle
 * module dependency does — a same-round, multi-package fixture cannot reproduce either signal.
 */
class Tier1ReachabilityClosureTest {

  private val dependencyJar: File = Tier1DependencyLibrary.compile(
    """
    package dep.outside

    class Advert(val sponsor: String)
    """.trimIndent(),
    fileName = "Advert.kt",
  )

  private val fixture: String = """
    package tier1.reachabilityclosure

    import dep.outside.Advert

    class Newsroom {
      fun sponsor(): Advert = Advert("Acme")
    }
  """.trimIndent()

  @Test
  fun `out-of-scope dependency type fires SKIPPED_UNEXPORTED_DEPENDENCY_TYPE naming the include fix`() {
    val result = Tier1Harness.run(
      fixture,
      processorOptions = mapOf("nuget.rootPackage" to "tier1.reachabilityclosure"),
      libraries = listOf(dependencyJar),
    )

    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    assertFalse(
      "export_newsroom_sponsor" in result.generated,
      "expected Newsroom.sponsor to be entirely absent from the generated CNameExports.kt; " +
          "generated:\n${result.generated}",
    )

    val diagnostic: String = requireNotNull(
      result.kspWarnings.firstOrNull { it.contains(ForwardDiagnosticKind.SKIPPED_UNEXPORTED_DEPENDENCY_TYPE.name) },
    ) {
      "expected a SKIPPED_UNEXPORTED_DEPENDENCY_TYPE diagnostic naming Newsroom.sponsor's " +
          "out-of-scope dep.outside.Advert return type; kspWarnings=${result.kspWarnings}"
    }
    // Issue #55: the hint names the whole include line, own package first, because an explicit
    // include replaces the rootPackage default rather than adding to it.
    assertTrue(
      diagnostic.contains("include(\"tier1.reachabilityclosure\", \"dep.outside\")"),
      "expected the diagnostic to name the full include(...) line; got: $diagnostic",
    )
  }

  @Test
  fun `admitted dependency type fires the aggregate INFO_EXPORTED_FROM_DEPENDENCY manifest once`() {
    val result = Tier1Harness.run(
      fixture,
      // Widening the include set to cover both packages is the closure's own escape hatch (the
      // fix the negative test above asserts the hint names verbatim).
      processorOptions = mapOf("nuget.includePackages" to "tier1.reachabilityclosure,dep.outside"),
      libraries = listOf(dependencyJar),
    )

    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    assertTrue(
      "export_newsroom_sponsor" in result.generated,
      "expected Newsroom.sponsor to bind once its dependency type is admitted; " +
          "generated:\n${result.generated}",
    )

    val manifest: List<String> = result.kspWarnings
      .filter { it.contains(ForwardDiagnosticKind.INFO_EXPORTED_FROM_DEPENDENCY.name) }
    assertTrue(
      manifest.size == 1,
      "expected exactly one aggregate INFO_EXPORTED_FROM_DEPENDENCY line, not one per admitted " +
          "type (ADR-066's deliberate deviation from ADR-064's per-member shape); got: $manifest",
    )
    assertTrue(
      manifest.single().contains("dep.outside.Advert"),
      "expected the manifest to name the admitted dep.outside.Advert; got: ${manifest.single()}",
    )
  }

  private val nestedDependencyJar: File = Tier1DependencyLibrary.compile(
    """
    package dep.nested

    class Outer(val tag: String) {
      class Inner(val n: Int)
      object Defaults
    }

    sealed class Shape {
      class Circle(val radius: Int) : Shape()
    }
    """.trimIndent(),
    fileName = "Outer.kt",
  )

  private val nestedFixture: String = """
    package tier1.reachabilityclosure.nested

    import dep.nested.Outer
    import dep.nested.Shape

    class Newsroom {
      fun outer(): Outer = Outer("acme")
      fun inner(): Outer.Inner = Outer.Inner(1)
      fun defaults(): Outer.Defaults = Outer.Defaults
      fun shape(): Shape = Shape.Circle(2)
    }
  """.trimIndent()

  private fun nestedResult(): Tier1Result = Tier1Harness.run(
    nestedFixture,
    processorOptions = mapOf(
      "nuget.includePackages" to "tier1.reachabilityclosure.nested,dep.nested",
    ),
    libraries = listOf(nestedDependencyJar),
  )

  /**
   * ADR-133 inverted this cell. A nested dependency declaration used to be refused by every bucket
   * (a namespace-root `class Inner` resolved against nothing, CS0426); it is now declared as the
   * real nested type `Outer.Inner` by the OWNER's walk -- exactly once, since the dependency merge
   * still feeds no nested declaration to a root list (a second one would be CS0101).
   *
   * `Outer.Defaults` keeps a skip, for a different reason that survives the ADR: a Kotlin `object`
   * is a C# *static* class, which cannot appear at a return position (CS0722). So this one cell
   * pins both halves: the nested class binds, the object-typed member still skips named.
   */
  @Test
  fun `a nested dependency class is declared once, nested, and its object sibling still skips`() {
    val result = nestedResult()

    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    listOf("public class Inner", "public static class Defaults").forEach { declaration ->
      assertTrue(
        result.generatedCSharp.contains(declaration),
        "expected `$declaration` to be declared; generatedCSharp=" +
            "${result.generatedCSharp.lines().filter { it.contains("Inner") || it.contains("Defaults") }}",
      )
    }
    listOf("Inner", "Defaults").forEach { nested ->
      assertFalse(
        Regex("""^ {4}public (?:static )?class $nested\b""", RegexOption.MULTILINE)
          .containsMatchIn(result.generatedCSharp),
        "expected no namespace-root twin of $nested (the pre-2026-09-07 flattening); " +
            "generatedCSharp=${result.generatedCSharp.lines().filter { it.contains(nested) }}",
      )
    }
    assertTrue(
      "export_outer_get_tag" in result.generated,
      "expected the admitted OUTER type and its own members to survive; " +
          "generated:\n${result.generated}",
    )
    assertTrue(
      "export_newsroom_inner" in result.generated,
      "expected the member returning the nested dependency class to bind; " +
          "generated:\n${result.generated}",
    )

    val defaults: String = requireNotNull(
      result.kspWarnings.firstOrNull { it.contains("SKIPPED_") && it.contains("Newsroom.defaults") },
    ) { "expected a skip diagnostic for Newsroom.defaults; kspWarnings=${result.kspWarnings}" }
    assertTrue(
      defaults.contains("CS0722"),
      "expected the object-position rule, not the undeclarable-nesting one; got: $defaults",
    )
    // `include(...)` cannot make a static C# type usable at a return position, so it must not be
    // offered -- the same rule the nested route followed before ADR-133.
    assertFalse(
      defaults.contains("include("),
      "expected no include(...) advice for an object-typed position; got: $defaults",
    )
  }

  /** The carve-out: ADR-009 declares a sealed subclass nested, and `nestedCsName` spells it the
   *  same way, so the sealed route's `getSealedSubclasses()` admissions must stay admitted. */
  @Test
  fun `a nested dependency sealed subclass stays admitted`() {
    val result = nestedResult()

    assertTrue(
      result.generatedCSharp.contains("Circle"),
      "expected the dependency sealed subclass to still be declared under its base; " +
          "generatedCSharp=${result.generatedCSharp.lines().filter { it.contains("Shape") }}",
    )
    val manifest: String = result.kspWarnings
      .first { it.contains(ForwardDiagnosticKind.INFO_EXPORTED_FROM_DEPENDENCY.name) }
    assertTrue(
      manifest.contains("dep.nested.Shape.Circle"),
      "expected the sealed subclass to still be admitted by the closure; got: $manifest",
    )
    assertFalse(
      manifest.contains("dep.nested.Outer.Inner") ||
          manifest.contains("dep.nested.Outer.Defaults"),
      "expected the plain nested declarations to be refused admission; got: $manifest",
    )
  }

  private val propertyFixture: String = """
    package tier1.reachabilityclosure.property

    import dep.outside.Advert

    class Billboard {
      val sponsor: Advert = Advert("Acme")
    }
  """.trimIndent()

  // ADR-064's 2026-09-11 amendment: the property route carries the same reason a callable does,
  // so an out-of-scope dependency *property* reads the `include(...)` remedy too. The kind stays
  // the position one (`SKIPPED_UNSUPPORTED_PROPERTY`); only the sentence and hint come from the
  // reason.
  @Test
  fun `an out-of-scope dependency property names the include fix too`() {
    val result = Tier1Harness.run(
      propertyFixture,
      processorOptions = mapOf("nuget.rootPackage" to "tier1.reachabilityclosure.property"),
      libraries = listOf(dependencyJar),
    )

    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    assertFalse(
      "export_billboard_get_sponsor" in result.generated,
      "expected Billboard.sponsor to be absent from the generated CNameExports.kt; " +
          "generated:\n${result.generated}",
    )

    val diagnostic: String = requireNotNull(
      result.kspWarnings.firstOrNull {
        it.contains(ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_PROPERTY.name) &&
            it.contains("Billboard.sponsor")
      },
    ) {
      "expected a SKIPPED_UNSUPPORTED_PROPERTY diagnostic for Billboard.sponsor; " +
          "kspWarnings=${result.kspWarnings}"
    }
    assertTrue(
      diagnostic.contains("is declared in a dependency module outside the export scope"),
      "expected the property diagnostic to read as out of scope; got: $diagnostic",
    )
    assertTrue(
      diagnostic.contains("include(\"tier1.reachabilityclosure.property\", \"dep.outside\")"),
      "expected the property diagnostic to name the full include(...) line; got: $diagnostic",
    )
  }

  // --- ADR-066 amendment: the closure's two missing nesting edges ---
  //
  // `dep.edge` is the pure form of both, with nothing the existing `dep.nested` jar can stand in
  // for: `Ledger` is referenced ONLY as `Ledger.Entry` (edge A: the closure must climb the owner
  // chain to admit the owner, because no member returns it), and `Stamp` is referenced ONLY from
  // `Entry.stamp()` (edge B: the closure must descend into a declared nested type's own members,
  // which `walkClassMembers` -- ctor params, properties, own functions, companion -- never does).
  private val edgeDependencyJar: File = Tier1DependencyLibrary.compile(
    """
    package dep.edge

    class Ledger(val tag: String) {
      class Entry(val n: Int) {
        fun stamp(): Stamp = Stamp(n)
      }
    }

    class Stamp(val v: Int)
    """.trimIndent(),
    fileName = "Ledger.kt",
  )

  private val edgeFixture: String = """
    package tier1.reachabilityclosure.edge

    import dep.edge.Ledger

    class Newsroom {
      fun entry(): Ledger.Entry = Ledger.Entry(1)
    }
  """.trimIndent()

  /**
   * Positive cell, both packages in scope. `Ledger` has to be admitted on the strength of a
   * reference to `Ledger.Entry` alone, ADR-133's owner walk then declares `Entry` nested under it,
   * and the walk into `Entry`'s own members has to admit the top-level `Stamp`.
   *
   * The manifest assertions are the load-bearing half: the nested type itself must stay OUT of
   * `admitted` (the owner is the admission record and the nested type rides its owner's walk, the
   * rule `a nested dependency sealed subclass stays admitted` above already pins for `dep.nested`),
   * while the owner and the nested type's own dependency must be IN it.
   */
  @Test
  fun `an owner reachable only through its nested type is admitted, and its dependency too`() {
    val result = Tier1Harness.run(
      edgeFixture,
      processorOptions = mapOf(
        "nuget.includePackages" to "tier1.reachabilityclosure.edge,dep.edge",
      ),
      libraries = listOf(edgeDependencyJar),
    )

    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")

    // Edge A: the owner nothing returns is declared, at namespace level (the 4-space indent).
    assertTrue(
      Regex("""^ {4}public class Ledger\b""", RegexOption.MULTILINE)
        .containsMatchIn(result.generatedCSharp),
      "expected the owner `Ledger` to be declared at namespace level even though no member " +
          "returns it; generatedCSharp=" +
          "${result.generatedCSharp.lines().filter { it.contains("Ledger") }}",
    )
    assertTrue(
      result.generatedCSharp.contains("public class Entry"),
      "expected `Entry` to be declared by its owner's walk; generatedCSharp=" +
          "${result.generatedCSharp.lines().filter { it.contains("Entry") }}",
    )
    assertFalse(
      Regex("""^ {4}public class Entry\b""", RegexOption.MULTILINE)
        .containsMatchIn(result.generatedCSharp),
      "expected no namespace-root twin of Entry (the pre-2026-09-07 flattening, CS0426); " +
          "generatedCSharp=${result.generatedCSharp.lines().filter { it.contains("Entry") }}",
    )

    // Edge B: the top-level dependency type reachable only from the nested type's own member.
    assertTrue(
      Regex("""^ {4}public class Stamp\b""", RegexOption.MULTILINE)
        .containsMatchIn(result.generatedCSharp),
      "expected `Stamp`, reachable only from Ledger.Entry.stamp(), to be declared; " +
          "generatedCSharp=${result.generatedCSharp.lines().filter { it.contains("Stamp") }}",
    )

    listOf("export_newsroom_entry", "export_ledger_get_tag", "export_ledger_entry_stamp")
      .forEach { export ->
        assertTrue(
          export in result.generated,
          "expected `$export` to bind; generated:\n${result.generated}",
        )
      }

    val manifest: String = result.kspWarnings
      .first { it.contains(ForwardDiagnosticKind.INFO_EXPORTED_FROM_DEPENDENCY.name) }
    listOf("dep.edge.Ledger", "dep.edge.Stamp").forEach { admitted ->
      assertTrue(
        manifest.contains(admitted),
        "expected the manifest to name the admitted $admitted; got: $manifest",
      )
    }
    assertFalse(
      manifest.contains("dep.edge.Ledger.Entry"),
      "expected the nested type itself to stay out of `admitted` -- the owner is the admission " +
          "record and the nested type rides its owner's walk; got: $manifest",
    )

    val skips: List<String> = result.kspWarnings.filter {
      it.contains("SKIPPED_") &&
          (it.contains("Newsroom.entry") || it.contains("Entry.stamp") || it.contains("dep.edge"))
    }
    assertTrue(
      skips.isEmpty(),
      "expected neither the nested return position nor the nested type's own member to skip; " +
          "got: $skips",
    )
  }

  // ADR-066 edge A, the deferred-owner form: `Season` is an `enum class`, which ADR-133/134 will
  // not let own a nested declaration, and `Almanac` is the only thing anything references. The
  // climb is still what admits `Season`, and admitting `Season` is what puts `Almanac` on the
  // `nestedCandidates` walk that names the skip.
  private val deferredOwnerDependencyJar: File = Tier1DependencyLibrary.compile(
    """
    package dep.deferred

    enum class Season {
      WINTER;

      class Almanac(val n: Int)
    }
    """.trimIndent(),
    fileName = "Season.kt",
  )

  private val deferredOwnerFixture: String = """
    package tier1.reachabilityclosure.deferred

    import dep.deferred.Season

    class Newsroom {
      fun almanac(): Season.Almanac = Season.Almanac(1)
    }
  """.trimIndent()

  /**
   * The climb at `ForwardReachabilityClosure.kt:285` is unconditional on purpose, and this is the
   * cell that pins why: gating it on "is the owner declarable" would look like a no-op (the nested
   * type is deferred either way) while actually deleting the only carrier of the nested type's
   * named skip. Admitting `Season` costs nothing false -- it is a real, usable C# enum -- and it
   * is what makes `Almanac` reachable as a `nestedCandidate`, so the author gets
   * `SKIPPED_NESTED_DECLARATION` naming the type and the reason instead of silence.
   */
  @Test
  fun `a deferred owner reached only through its nested type is still admitted, so the skip stays named`() {
    val result = Tier1Harness.run(
      deferredOwnerFixture,
      processorOptions = mapOf(
        "nuget.includePackages" to "tier1.reachabilityclosure.deferred,dep.deferred",
      ),
      libraries = listOf(deferredOwnerDependencyJar),
    )

    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")

    // The owner nothing returns is admitted, and it is a real usable C# type on its own.
    assertTrue(
      Regex("""^ {4}public enum Season\b""", RegexOption.MULTILINE)
        .containsMatchIn(result.generatedCSharp),
      "expected the deferred owner `Season` to still be declared as an enum; generatedCSharp=" +
          "${result.generatedCSharp.lines().filter { it.contains("Season") }}",
    )
    assertFalse(
      result.generatedCSharp.withoutDocComments().contains("Almanac"),
      "expected the deferred nested type to be absent from the generated C#; generatedCSharp=" +
          "${result.generatedCSharp.lines().filter { it.contains("Almanac") }}",
    )
    assertFalse(
      "export_newsroom_almanac" in result.generated,
      "expected the member returning the deferred nested type to bind nothing; generated:\n" +
          result.generated,
    )

    // The whole point of the climb: the skip is named, not silent.
    val skip: String = requireNotNull(
      result.kspWarnings.firstOrNull {
        it.contains(ForwardDiagnosticKind.SKIPPED_NESTED_DECLARATION.name) &&
            it.contains("dep.deferred.Season.Almanac")
      },
    ) {
      "expected a SKIPPED_NESTED_DECLARATION naming dep.deferred.Season.Almanac, which only the " +
          "owner's admission can carry; kspWarnings=${result.kspWarnings}"
    }
    assertTrue(
      skip.contains("enum class"),
      "expected the skip to name the enum-owner reason; got: $skip",
    )

    val manifest: String = result.kspWarnings
      .first { it.contains(ForwardDiagnosticKind.INFO_EXPORTED_FROM_DEPENDENCY.name) }
    assertTrue(
      manifest.contains("dep.deferred.Season"),
      "expected the manifest to name the admitted owner; got: $manifest",
    )
    assertFalse(
      manifest.contains("dep.deferred.Season.Almanac"),
      "expected the deferred nested type to stay out of `admitted`; got: $manifest",
    )
  }

  /**
   * Negative cell, same shape with `dep.edge` OUT of scope. The remedy has to stay the SCOPE one:
   * the nested gate must not shadow the refusal the closure already recorded, because
   * `include("tier1.reachabilityclosure.edge", "dep.edge")` is what actually fixes this build,
   * while `UNDECLARED_CLASS`'s "move it to the top level of its file" is both stale since ADR-133
   * and useless for a type the author does not own.
   */
  @Test
  fun `the same shape out of scope keeps the scope refusal, not the nested wording`() {
    val result = Tier1Harness.run(
      edgeFixture,
      processorOptions = mapOf("nuget.rootPackage" to "tier1.reachabilityclosure.edge"),
      libraries = listOf(edgeDependencyJar),
    )

    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    assertFalse(
      "export_newsroom_entry" in result.generated,
      "expected Newsroom.entry to be absent while its dependency package is out of scope; " +
          "generated:\n${result.generated}",
    )

    val diagnostic: String = requireNotNull(
      result.kspWarnings.firstOrNull {
        it.contains(ForwardDiagnosticKind.SKIPPED_UNEXPORTED_DEPENDENCY_TYPE.name) &&
            it.contains("Newsroom.entry")
      },
    ) {
      "expected the out-of-scope refusal to survive the nested gate for Newsroom.entry; " +
          "kspWarnings=${result.kspWarnings}"
    }
    assertTrue(
      diagnostic.contains("include(\"tier1.reachabilityclosure.edge\", \"dep.edge\")"),
      "expected the scope remedy naming the full include(...) line; got: $diagnostic",
    )
    assertFalse(
      result.kspWarnings.any {
        it.contains("Newsroom.entry") && it.contains("is nested inside another declaration")
      },
      "expected the UNDECLARED_CLASS nesting wording (and its `move it to the top level of its " +
          "file` remedy) NOT to be offered for a type in an out-of-scope dependency; " +
          "kspWarnings=${result.kspWarnings}",
    )
  }
}
