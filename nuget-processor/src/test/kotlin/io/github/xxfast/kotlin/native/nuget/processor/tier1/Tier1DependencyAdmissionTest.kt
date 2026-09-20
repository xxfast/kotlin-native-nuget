package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-154: `admit(...)`, the additive dependency-admission verb, taking a qualified type name or a
 * package prefix (the same matcher `exclude` uses, issue #53). `include(...)` is untouched: no cell
 * here lists a dependency package in `nuget.includePackages`, so every admitted type crossed the
 * module boundary through `nuget.admit` alone.
 *
 * The jar is a genuinely separate compilation unit ([Tier1DependencyLibrary]), which is what makes
 * the declarations report `containingFile == null` and reach ADR-066's closure at all. Three
 * packages, because the point of per-type admission is that one entry does NOT drag its siblings:
 * `dep.bytype` (one type admitted by name, two not), `dep.byprefix` (admitted by prefix, no type
 * named anywhere) and `dep.never` (reachable, admitted by nothing).
 */
class Tier1DependencyAdmissionTest {

  private val dependencyJar: File = Tier1DependencyLibrary.compile(
    mapOf(
      "Bytype.kt" to """
        package dep.bytype

        enum class Bedding { FLEECE, WICKER }

        class Waterbowl(val label: String) {
          fun millilitres(): Int = 250
          val rim: Rimguard get() = Rimguard("silicone")
          fun fitGuard(guard: Rimguard): String = guard.material
        }

        class Rimguard(val material: String)

        @JvmInline
        value class Eartag(val code: String)
      """.trimIndent(),
      "Byprefix.kt" to """
        package dep.byprefix

        class Tuft(val colour: String)
      """.trimIndent(),
      "Never.kt" to """
        package dep.never

        class Doormat(val worn: Boolean)
      """.trimIndent(),
    ),
  )

  private val fixture: String = """
    package tier1.admission

    import dep.byprefix.Tuft
    import dep.bytype.Bedding
    import dep.bytype.Eartag
    import dep.bytype.Rimguard
    import dep.bytype.Waterbowl

    class Storeroom {
      fun bedding(): Bedding = Bedding.FLEECE
      fun bowl(): Waterbowl = Waterbowl("kitchen")
      fun beddings(): List<Bedding> = listOf(Bedding.FLEECE)
      fun tuft(): Tuft = Tuft("brown")
      fun rimguard(): Rimguard = Rimguard("silicone")
      fun eartag(): Eartag = Eartag("oreo-0001")
      fun doormat(): dep.never.Doormat = dep.never.Doormat(true)
    }
  """.trimIndent()

  private fun run(options: Map<String, String>): Tier1Result = Tier1Harness.run(
    fixture,
    processorOptions = mapOf("nuget.namespace" to "Lib") + options,
    libraries = listOf(dependencyJar),
  )

  /** The shipped shape: the module's own package included, dependency packages never. */
  private val admittedOptions: Map<String, String> = mapOf(
    "nuget.includePackages" to "tier1.admission",
    "nuget.admit" to "dep.bytype.Bedding,dep.bytype.Waterbowl,dep.byprefix",
  )

  private fun skip(result: Tier1Result, member: String, kind: ForwardDiagnosticKind): String =
    result.kspWarnings.firstOrNull { it.contains(kind.name) && it.contains(member) }
      ?: error("expected a $kind skip for $member; got: ${result.kspWarnings}")

  private fun manifest(result: Tier1Result): String =
    result.kspWarnings
      .plus(result.kspErrors)
      .firstOrNull { it.contains(ForwardDiagnosticKind.INFO_EXPORTED_FROM_DEPENDENCY.name) }
      ?: error("expected an export manifest; got: ${result.kspWarnings}")

  @Test
  fun `a dependency type admitted by qualified name is exported without its package`() {
    val result = run(admittedOptions)

    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    val manifest: String = manifest(result)
    assertTrue("dep.bytype.Waterbowl" in manifest, "expected the admitted class; got: $manifest")
    assertTrue("dep.bytype.Bedding" in manifest, "expected the admitted enum; got: $manifest")
    // ADR-154 section 3: admitting a type is not admitting its package.
    assertFalse("dep.bytype.Rimguard" in manifest, "admit must not walk the package; $manifest")
    assertFalse("dep.bytype.Eartag" in manifest, "admit must not walk the package; $manifest")
    assertFalse("dep.never" in manifest, "an unadmitted package must stay out; $manifest")
  }

  @Test
  fun `an admit entry naming a package prefix admits every type under it`() {
    val manifest: String = manifest(run(admittedOptions))

    assertTrue(
      "dep.byprefix.Tuft" in manifest,
      "the prefix entry names no type at all, so the matcher's package half is what admits it; " +
          "got: $manifest",
    )
  }

  /**
   * ADR-154 section 2, the load-bearing guard. With no `rootPackage` and no `include`,
   * `PackageScope.covers` answers `true` for every non-excluded package, so an unguarded
   * `isExportedAndUnmarked || admitMatches` predicate would admit every reachable klib declaration
   * the moment `admit` opened admission rule 4's gate: the whole compile classpath, silently
   * (ADR-066 Alternative 3). The manifest must list the admitted type and nothing else.
   */
  @Test
  fun `admit alone does not walk the classpath`() {
    val result = run(mapOf("nuget.admit" to "dep.bytype.Waterbowl"))

    val manifest: String = manifest(result)
    assertTrue("dep.bytype.Waterbowl" in manifest, "expected the admitted type; got: $manifest")
    assertFalse("dep.bytype.Bedding" in manifest, "sibling admitted by nothing; got: $manifest")
    assertFalse("dep.bytype.Rimguard" in manifest, "sibling admitted by nothing; got: $manifest")
    assertFalse("dep.byprefix" in manifest, "package admitted by nothing; got: $manifest")
    assertFalse("dep.never" in manifest, "package admitted by nothing; got: $manifest")
    assertFalse("kotlin." in manifest, "the stdlib is not an admission target; got: $manifest")
  }

  /**
   * ADR-154 section 3 again, from the member side: the admitted class keeps binding and only the
   * members mentioning the un-admitted sibling are dropped — at the method position under the
   * dependency kind, at the property position under the positional property kind (which is why
   * nothing may count dependency-scope skips by KIND).
   */
  @Test
  fun `a member of an admitted type typed with an unadmitted type skips named`() {
    val result = run(admittedOptions)

    val method: String =
      skip(result, "fitGuard", ForwardDiagnosticKind.SKIPPED_UNEXPORTED_DEPENDENCY_TYPE)
    assertTrue("dep.bytype.Rimguard" in method, "expected the refused type named; got: $method")

    val property: String =
      skip(result, "rim", ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_PROPERTY)
    assertTrue(
      "dep.bytype.Rimguard" in property,
      "the property position must carry the same dependency hint; got: $property",
    )

    // The survivor beside them: "the members skip" is not "the type failed to bind".
    assertTrue("millilitres" in result.generatedCSharp, "expected the kept member")
  }

  /**
   * ROADMAP line 38, folded into ADR-154 section 4. Before this gate a top-level dependency value
   * class was SPELLED (`global::Lib.Dep.Bytype.Eartag`) with no declaration and no diagnostic, so
   * the consumer's C# failed CS0246 with nothing in the log pointing at it.
   *
   * The klib half of this (a klib value class reports `Modifier.INLINE`, not `VALUE`) cannot be
   * reproduced in a JVM jar; `scripts/verify.sh` proves it against `:test-models`.
   */
  @Test
  fun `an unadmitted top-level dependency value class is a named skip, not a spelling`() {
    val result = run(admittedOptions)

    val warning: String =
      skip(result, "eartag", ForwardDiagnosticKind.SKIPPED_UNEXPORTED_DEPENDENCY_TYPE)
    assertTrue("dep.bytype.Eartag" in warning, "expected the value class named; got: $warning")
    // The ONE permitted mention is the #276 `<remarks>` paragraph naming the dropped member. What
    // must never appear is a TYPE REFERENCE: `global::...Eartag` at a return/parameter position or
    // a `new global::...Eartag(...)` construction, against a struct nothing declares (CS0246).
    assertFalse(
      "global::Lib.Dep.Bytype.Eartag" in result.generatedCSharp,
      "an undeclared value class must not be spelled; got:\n${result.generatedCSharp}",
    )
    assertFalse(
      "Eartag" in result.generated,
      "nor bridged from the Kotlin side; got:\n${result.generated}",
    )
  }

  /**
   * ADR-108's carve-out, beside the gate above: `kotlin.Result` is a top-level value class in no
   * export set, recorded refused `NOT_INCLUDED` by the closure exactly like `Eartag`, and a gate
   * keyed on "was it refused" alone would amputate the whole Result route.
   */
  @Test
  fun `kotlin Result still binds as its payload beside the value-class gate`() {
    val result = Tier1Harness.run(
      """
      package tier1.admission

      class Treats {
        fun feed(name: String): Result<String> = Result.success("${'$'}name got a treat")
      }
      """.trimIndent(),
      processorOptions = mapOf(
        "nuget.namespace" to "Lib",
        "nuget.includePackages" to "tier1.admission",
        "nuget.admit" to "dep.bytype.Waterbowl",
      ),
      libraries = listOf(dependencyJar),
    )

    assertTrue(result.compiledClean, "expected no broken source; got: ${result.compileErrors}")
    assertTrue(
      "treats_feed" in result.generated,
      "ADR-108's Result return must still bind; got:\n${result.generated}",
    )
  }

  /** ADR-154 section 1: `exclude` still wins, tested ahead of admission in the closure. */
  @Test
  fun `exclude beats admit for the same type`() {
    val result = run(
      admittedOptions + mapOf("nuget.excludePackages" to "dep.bytype.Waterbowl"),
    )

    val manifest: String = manifest(result)
    assertFalse("dep.bytype.Waterbowl" in manifest, "exclude wins; got: $manifest")
    assertTrue("dep.bytype.Bedding" in manifest, "the other admit entry is unaffected; $manifest")

    val warning: String =
      skip(result, "bowl", ForwardDiagnosticKind.SKIPPED_UNEXPORTED_DEPENDENCY_TYPE)
    assertTrue(
      """exclude("dep.bytype.Waterbowl")""" in warning,
      "ROADMAP line 37: the hint must quote the entry the author WROTE, which here is a " +
          "type-level exclude, not the package it derives from; got: $warning",
    )
    assertFalse(
      """exclude("dep.bytype")""" in warning,
      "the derived package is not an entry anyone wrote; got: $warning",
    )
  }

  /**
   * ADR-154 section 5: the hint is the additive verb, and never the `include(...)` replacement line
   * (#60's shape, which cannot apply to an additive verb).
   */
  @Test
  fun `the unadmitted hint names admit, never include`() {
    val result = run(admittedOptions)
    val warning: String =
      skip(result, "rimguard", ForwardDiagnosticKind.SKIPPED_UNEXPORTED_DEPENDENCY_TYPE)

    assertTrue(
      """add admit("dep.bytype.Rimguard")""" in warning,
      "expected the additive line the author would paste; got: $warning",
    )
    assertTrue(
      """exclude("dep.bytype.Rimguard")""" in warning,
      "expected the deliberate-omission spelling beside it; got: $warning",
    )
    assertFalse("include(" in warning, "the #55 trap must not be reachable; got: $warning")
  }

  /**
   * The `List<T>` hint bug (research spike 1b): a collection-element refusal passed no detail at
   * all, so the hint printed its literal `"the dependency's package"` fallback — a remedy naming
   * no package and no type. Fixed at the source, in `unexportedDependencyDetail`, which now
   * descends one collection level like its two siblings.
   */
  @Test
  fun `a collection element names the element type, not the placeholder`() {
    val result = run(mapOf("nuget.includePackages" to "tier1.admission"))
    val warning: String =
      skip(result, "beddings", ForwardDiagnosticKind.SKIPPED_UNEXPORTED_DEPENDENCY_TYPE)

    assertFalse(
      "the dependency's package" in warning,
      "the literal placeholder must never reach an author; got: $warning",
    )
    assertTrue(
      """admit("dep.bytype.Bedding")""" in warning,
      "expected the ELEMENT type named; got: $warning",
    )
  }

  /**
   * ADR-154 section 6, the opt-in. Keyed on the SKIP REASON, so both the dependency kind and the
   * positional property kind escalate; `EXCLUDED_BY_CONFIG` is a different reason constant and
   * stays a warning, which is what makes "admitted or excluded by name" a decision rather than a
   * wall.
   */
  @Test
  fun `strict mode turns an unadmitted dependency type into an error`() {
    val result = run(
      admittedOptions + mapOf(
        "nuget.strictDependencyTypes" to "true",
        "nuget.excludePackages" to "dep.never",
      ),
    )

    val errors: List<String> = result.kspErrors.filter {
      it.contains(ForwardDiagnosticKind.ERROR_UNEXPORTED_DEPENDENCY_TYPE.name)
    }
    assertTrue(
      errors.any { "rimguard" in it },
      "expected the method position escalated; got: ${result.kspErrors}",
    )
    assertTrue(
      errors.any { "rim" in it },
      "expected the PROPERTY position escalated through the same reason-keyed rule; " +
          "got: ${result.kspErrors}",
    )

    // The author's own exclude stays a warning under strict: they already decided.
    assertTrue(
      result.kspWarnings.any { "doormat" in it && "exclude" in it },
      "an excluded type must not be escalated; got: ${result.kspWarnings}",
    )
    assertFalse(
      result.kspErrors.any { "doormat" in it },
      "an excluded type must not be escalated; got: ${result.kspErrors}",
    )
  }

  @Test
  fun `strict mode is off by default`() {
    val result = run(admittedOptions)

    assertEquals(
      emptyList(),
      result.kspErrors.filter {
        it.contains(ForwardDiagnosticKind.ERROR_UNEXPORTED_DEPENDENCY_TYPE.name)
      },
      "ADR-066 section 4's warn-and-skip is still the shipped default",
    )
  }
}
