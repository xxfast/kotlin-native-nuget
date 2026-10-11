package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A top-level function returning an exported generic class (`fun f(): Crate<Int>`). Until ADR-208
 * it was on a hand-built legacy route that spelled a type argument C# cannot name by its bare
 * Kotlin simple name, and an outer type nothing declares by a namespace that does not exist:
 *
 * ```kotlin
 * fun crateOfList(): Crate<List<Int>> = Crate(listOf(1))
 * fun pairOf(): Pair<Int, Int> = 1 to 2
 * ```
 * ```csharp
 * public static global::Interop.Catcam.Crate<List> CrateOfList()   // CS0246 'List'
 * public static global::Interop.Kotlin.Pair<int, int> PairOf()     // CS0234 'Kotlin'
 * ```
 *
 * ADR-208 put the return on the plan route and deleted the legacy one. A closed instantiation
 * binds, nullable outer and nested instantiation included; an argument the erased wire cannot
 * read (a generic carrier reads its `T` through `NugetMarshal.FromHandle<T>`, which has no
 * materialiser for a collection or a lambda) refuses the use site, named, as
 * `SKIPPED_UNSUPPORTED_TYPE`; a `Flow` argument binds through its own generated collect export
 * (ADR-208 part E); an outer type the module does not export is refused as the
 * unsupported type it is.
 *
 * A skip is absent on BOTH halves now: the legacy route used to leave an orphan `@CName` export.
 *
 * The crate holds Oreo's favourite toy, which nobody may take out.
 */
class Tier1GenericReturnTypeArgumentTest {

  /** A generic reference no route carries, for the NULLABLE-return wording cell. */
  private val unrouted: Tier1GenericCandidate = Tier1UnwrappableWitness.unroutedGeneric

  private val fixture: Map<String, String> = mapOf(
    "Lens.kt" to """
      package tier1.catcam.lens

      class Snapshot(val caption: String)
    """.trimIndent(),
    "Crates.kt" to """
      package tier1.catcam

      import kotlinx.coroutines.flow.Flow
      import kotlinx.coroutines.flow.flowOf
      import tier1.catcam.lens.Snapshot

      class Crate<T>(val item: T)

      class Duo<A, B>(val first: A, val second: B)

      fun crateOfInt(): Crate<Int> = Crate(1)

      fun crateOfSnapshot(): Crate<Snapshot> = Crate(Snapshot("oreo"))

      fun <T> crateOf(item: T): Crate<T> = Crate(item)

      fun crateOfList(): Crate<List<Int>> = Crate(listOf(1))

      fun crateOfNullableList(): Crate<List<Int>?> = Crate(null)

      fun crateOfMap(): Crate<Map<String, Int>> = Crate(mapOf("mylo" to 1))

      fun crateOfFlow(): Crate<Flow<Int>> = Crate(flowOf(1))

      fun crateOfLambda(): Crate<(Int) -> Int> = Crate { it + 1 }

      fun crateOfAny(): Crate<Any> = Crate("toy")

      fun crateOfBytes(): Crate<ByteArray> = Crate(byteArrayOf(1))

      fun crateOfCrate(): Crate<Crate<Int>> = Crate(Crate(1))

      fun duoOfIntList(): Duo<Int, List<Int>> = Duo(1, listOf(2))

      fun pairOf(): Pair<Int, Int> = 1 to 2

      fun crateOfListWithParam(n: Int): Crate<List<Int>> = Crate(listOf(n))

      fun maybeCrateOfList(): Crate<List<Int>>? = null

      fun maybeCrateOfInt(): Crate<Int>? = null

      ${unrouted.declarations}

      fun maybeUnrouted(): ${unrouted.kotlin}? = null

      enum class Mood { CALM, GRUMPY }

      fun crateOfMaybeInt(): Crate<Int?> = Crate(null)

      fun crateOfMaybeName(): Crate<String?> = Crate(null)

      fun crateOfMaybeMood(): Crate<Mood?> = Crate(Mood.GRUMPY)

      fun crateOfMaybeSnapshot(): Crate<Snapshot?> = Crate(null)

      fun <T> crateOfMaybe(item: T): Crate<T?> = Crate(item)

      fun <T> emptyCrateOfMaybe(): Crate<T?> = Crate(null)
    """.trimIndent(),
  )

  private fun run(): Tier1Result = Tier1Harness.run(
    fixture,
    processorOptions = mapOf("nuget.rootPackage" to "tier1"),
    libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
  )

  /**
   * Every refused use site of an exported outer, each with the type argument its diagnostic must
   * name, as the author wrote it.
   */
  private val refusedArguments: Map<String, String> = linkedMapOf(
    "crateOfList" to "List<Int>",
    "crateOfNullableList" to "List<Int>?",
    "crateOfMap" to "Map<String, Int>",
    "crateOfLambda" to "Function1<Int, Int>",
    "crateOfAny" to "Any",
    "crateOfBytes" to "ByteArray",
    "duoOfIntList" to "List<Int>",
    "crateOfListWithParam" to "List<Int>",
  )

  /**
   * A nullable outer over a bindable argument binds. Over a refused one it is the planner's
   * NULLABLE return skip, as it was before ADR-208, and absent from both halves.
   */
  @Test
  fun `a nullable generic return binds, and skips named when its argument is refused`() {
    val result = run()

    assertTrue(result.kspErrors.isEmpty(), "expected no KSP errors; got: ${result.kspErrors}")
    assertTrue(
      result.generatedCSharp.contains(
        "public static global::Interop.Catcam.Crate<int>? MaybeCrateOfInt()",
      ),
      "expected Crate<Int>? to bind as a nullable wrapper; got: " +
          "${result.generatedCSharp.lines().filter { it.contains("MaybeCrateOfInt") }}",
    )
    assertTrue(
      result.kspWarnings.none { it.contains("[nuget:SKIPPED_") && it.contains("maybeCrateOfInt:") },
      "expected no skip for maybeCrateOfInt; kspWarnings=${result.kspWarnings}",
    )
    assertFalse(
      result.generatedCSharp.contains("MaybeCrateOfList"),
      "expected no MaybeCrateOfList on the C# half; got: " +
          "${result.generatedCSharp.lines().filter { it.contains("MaybeCrateOfList") }}",
    )
    assertTrue(
      skipDiagnostic(result, "maybeCrateOfList", ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_RETURN)
        .contains("NULLABLE"),
      "expected the NULLABLE return skip for maybeCrateOfList",
    )
  }

  /**
   * The NULLABLE return skip names the type it refused, in the sentence and the hint. A nullable
   * generic class over a bindable argument binds since ADR-208, so the probes are one over a
   * refused argument and a generic reference no route carries.
   */
  @Test
  fun `a nullable return skip names the refused type, in the sentence and the hint`() {
    val result = run()

    mapOf(
      "maybeUnrouted" to "`${unrouted.kotlin}?`",
      "maybeCrateOfList" to "`Crate<List<Int>>?`",
    ).forEach { (function, spelled) ->
      val diagnostic: String =
        skipDiagnostic(result, function, ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_RETURN)
      assertTrue(
        diagnostic.contains("its nullable return type $spelled has no supported wire (NULLABLE)"),
        "expected the reason sentence to name $spelled; got: $diagnostic",
      )
      assertTrue(
        diagnostic.contains("the nullable return type $spelled has no wire at a return position"),
        "expected the hint to name $spelled; got: $diagnostic",
      )
      assertFalse(
        diagnostic.contains("a nullable value at this position"),
        "expected the unnamed wording gone; got: $diagnostic",
      )
    }
  }

  @Test
  fun `an unreadable generic-return type argument skips the function, named`() {
    val result = run()

    refusedArguments.forEach { (function, typeArgument) ->
      val csName: String = function.replaceFirstChar { it.uppercase() }
      val lines: List<String> = result.generatedCSharp.lines().filter { it.contains(" $csName(") }
      assertTrue(
        lines.isEmpty(),
        "expected $csName absent from Interop.cs, it has no C# spelling; got: $lines",
      )
      assertRefusedArgument(result, function, typeArgument)
    }
  }

  /** `Pair` is not declared in C#: refused as the unmapped stdlib type it is, named. */
  @Test
  fun `a generic return whose outer type is not exported skips the function, named`() {
    val result = run()

    val lines: List<String> = result.generatedCSharp.lines().filter { it.contains(" PairOf(") }
    assertTrue(lines.isEmpty(), "expected PairOf absent from Interop.cs; got: $lines")
    val diagnostic: String =
      skipDiagnostic(result, "pairOf", ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_TYPE)
    assertTrue(
      diagnostic.contains("kotlin.Pair"),
      "expected the pairOf diagnostic to name kotlin.Pair; got: $diagnostic",
    )
  }

  @Test
  fun `the refused-argument skip has its own wording, not the lambda one`() {
    val result = run()

    val diagnostic: String =
      skipDiagnostic(result, "crateOfList", ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_TYPE)
    assertFalse(
      diagnostic.contains("lambda type argument"),
      "expected the skip to describe a generic class, not a lambda; got: $diagnostic",
    )
    assertFalse(
      diagnostic.contains("sealed type"),
      "expected the skip not to call a plain class a sealed type; got: $diagnostic",
    )
    assertTrue(
      diagnostic.contains("its generic class `tier1.catcam.Crate` has no C# spelling here"),
      "expected the skip to name the generic class; got: $diagnostic",
    )
  }

  @Test
  fun `a nameable generic return still binds`() {
    val result = run()

    assertTrue(
      result.generatedCSharp.contains(
        "public static global::Interop.Catcam.Crate<int> CrateOfInt()",
      ),
      "expected Crate<Int> to keep binding; got: " +
          "${result.generatedCSharp.lines().filter { it.contains("CrateOfInt") }}",
    )
    assertTrue(
      result.generatedCSharp.contains(
        "public static global::Interop.Catcam.Crate<global::Interop.Catcam.Lens.Snapshot> " +
            "CrateOfSnapshot()",
      ),
      "expected Crate<Snapshot> to keep binding, qualified; got: " +
          "${result.generatedCSharp.lines().filter { it.contains("CrateOfSnapshot") }}",
    )
    // ADR-208 part E: a Flow argument binds; `crate.Item` is a `KotlinFlow<int>` materialised
    // through the instantiation's own generated collect export.
    assertTrue(
      result.generatedCSharp.contains(
        "public static global::Interop.Catcam.Crate<global::Interop.KotlinFlow<int>> CrateOfFlow()",
      ),
      "expected Crate<Flow<Int>> to bind; got: " +
          "${result.generatedCSharp.lines().filter { it.contains("CrateOfFlow") }}",
    )
    assertTrue(
      result.generated.contains("\"library_flowarg_flow_kotlin_Int_collect\""),
      "expected the Flow<Int> argument's collect export in CNameExports.kt",
    )
    // ADR-208: a nested instantiation binds too; its inner wrapper has its own factory.
    assertTrue(
      result.generatedCSharp.contains(
        "public static global::Interop.Catcam.Crate<global::Interop.Catcam.Crate<int>> " +
            "CrateOfCrate()",
      ),
      "expected Crate<Crate<Int>> to bind; got: " +
          "${result.generatedCSharp.lines().filter { it.contains("CrateOfCrate") }}",
    )
    assertTrue(
      result.generatedCSharp.contains("public static Crate<T> CrateOf<T>(T item)"),
      "expected a type-parameter return to keep binding; got: " +
          "${result.generatedCSharp.lines().filter { it.contains("CrateOf<") }}",
    )
    val bound: List<String> =
      listOf("crateOfInt", "crateOfSnapshot", "crateOfCrate", "crateOfFlow", "crateOf")
    bound.forEach { function ->
      assertTrue(
        result.kspWarnings.none { it.contains("[nuget:SKIPPED_") && it.contains("$function: ") },
        "expected no skip for $function; kspWarnings=${result.kspWarnings}",
      )
    }
  }

  /**
   * A nullable type argument keeps its `?`: `Crate<Int?>` is `Crate<int?>`, never `Crate<int>`
   * where null reads `0`.
   */
  @Test
  fun `a nullable generic-return type argument keeps its question mark`() {
    val result = run()
    val cs: String = result.generatedCSharp

    mapOf(
      "CrateOfMaybeInt" to "global::Interop.Catcam.Crate<int?>",
      "CrateOfMaybeName" to "global::Interop.Catcam.Crate<string?>",
      "CrateOfMaybeMood" to "global::Interop.Catcam.Crate<global::Interop.Catcam.Mood?>",
      "CrateOfMaybeSnapshot" to
        "global::Interop.Catcam.Crate<global::Interop.Catcam.Lens.Snapshot?>",
    ).forEach { (function, spelled) ->
      assertTrue(
        cs.contains("public static $spelled $function()"),
        "expected $function to return $spelled; got: " +
          "${cs.lines().filter { it.contains(" $function(") }}",
      )
    }
    listOf(
      "crateOfMaybeInt",
      "crateOfMaybeName",
      "crateOfMaybeMood",
      "crateOfMaybeSnapshot",
      "crateOfMaybe",
    ).forEach { function ->
      assertTrue(
        result.kspWarnings.none { it.contains("[nuget:SKIPPED_") && it.contains("$function: ") },
        "expected no skip for $function; kspWarnings=${result.kspWarnings}",
      )
    }
  }

  /**
   * `Crate<T?>` at a function's own type parameter is the generic-function route's (ADR-197). With
   * a parameter of its own type parameter it binds, spelling the return by ADR-147's bare-`T` rule
   * as `Crate<T>`: the `?` written on `T?` is not carried into C#, the instantiation says whether
   * the item holds null (`CrateOfMaybe<int?>` does, `CrateOfMaybe<int>` reads `0`). Without one it
   * is that route's existing named skip. Both are pinned as they stand.
   */
  @Test
  fun `a nullable type-parameter argument keeps the generic-function route's spelling`() {
    val result = run()
    val cs: String = result.generatedCSharp

    assertTrue(
      cs.contains("public static Crate<T> CrateOfMaybe<T>(T item)"),
      "expected the generic-function route's Crate<T>; got: " +
        "${cs.lines().filter { it.contains("CrateOfMaybe<") }}",
    )
    assertTrue(
      cs.lines().none { it.trimStart().startsWith("public static") && " EmptyCrateOfMaybe" in it },
      "expected no EmptyCrateOfMaybe declaration; got: " +
        "${cs.lines().filter { it.contains("EmptyCrateOfMaybe") }}",
    )
    assertTrue(
      result.kspWarnings.any {
        it.contains("[nuget:${ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_RETURN.name}]") &&
          it.contains("emptyCrateOfMaybe: ")
      },
      "expected a SKIPPED_UNSUPPORTED_RETURN naming emptyCrateOfMaybe; " +
        "kspWarnings=${result.kspWarnings}",
    )
  }

  /** A skip is absent on both halves: no orphan export is left for a refused function. */
  @Test
  fun `the generated Kotlin still compiles and a skipped function exports nothing`() {
    val result = run()

    assertTrue(
      result.compiledClean,
      "expected clean generated Kotlin; got: ${result.compileErrors}",
    )
    assertTrue(result.kspErrors.isEmpty(), "expected no KSP errors; got: ${result.kspErrors}")
    listOf("crateOfList", "pairOf").forEach { function ->
      assertFalse(
        result.generated.contains("\"library_catcam__$function\""),
        "expected no orphan export for the skipped $function in CNameExports.kt",
      )
    }
    assertTrue(
      result.generated.contains("\"library_catcam__crateOfInt\""),
      "expected the bound crateOfInt to keep its export",
    )
  }

  private fun skipDiagnostic(
    result: Tier1Result,
    function: String,
    kind: ForwardDiagnosticKind,
  ): String = requireNotNull(
    result.kspWarnings.firstOrNull {
      it.contains("[nuget:${kind.name}]") && it.contains("$function: ")
    },
  ) {
    "expected a ${kind.name} naming $function; kspWarnings=${result.kspWarnings}"
  }

  private fun assertRefusedArgument(result: Tier1Result, function: String, typeArgument: String) {
    val diagnostic: String =
      skipDiagnostic(result, function, ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_TYPE)
    assertTrue(
      diagnostic.contains("cannot read its type argument `$typeArgument`"),
      "expected the $function diagnostic to name $typeArgument; got: $diagnostic",
    )
  }
}
