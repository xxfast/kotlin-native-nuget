package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The top-level generic-return route (`translateFunction`'s `isGenericReturnType` arm) spelled a
 * type argument C# cannot name by its bare Kotlin simple name, and an outer type nothing declares
 * by a namespace that does not exist:
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
 * Issue #111 already gated every lambda-type-argument site; this route was the one left
 * qualify-only. Binding is not an option (a generic carrier reads its `T` through
 * `NugetMarshal.FromHandle<T>`, which has no materialiser for a collection, a constructed generic
 * or a Flow), so each of these is skipped, named, with a `SKIPPED_UNSUPPORTED_RETURN` that says
 * which argument (or which outer type) has no C# spelling.
 *
 * The Kotlin half keeps its `@CName` export, exactly like the lambda-return skip: a C#-only skip
 * leaves an export no import names, which `ForwardAbiContract` tolerates by design.
 *
 * The crate holds Oreo's favourite toy, which nobody may take out.
 */
class Tier1GenericReturnTypeArgumentTest {

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

  /** Every spiked shape, each with the argument (or outer type) its diagnostic must name. */
  private val skipped: Map<String, String> = linkedMapOf(
    "crateOfList" to "kotlin.collections.List",
    "crateOfNullableList" to "kotlin.collections.List",
    "crateOfMap" to "kotlin.collections.Map",
    "crateOfFlow" to "kotlinx.coroutines.flow.Flow",
    "crateOfLambda" to "kotlin.Function1",
    "crateOfAny" to "kotlin.Any",
    "crateOfBytes" to "kotlin.ByteArray",
    "crateOfCrate" to "tier1.catcam.Crate",
    "duoOfIntList" to "kotlin.collections.List",
    "pairOf" to "kotlin.Pair",
    "crateOfListWithParam" to "kotlin.collections.List",
  )

  /**
   * A nullable outer type never reaches the generic-return arm: it took `translateFunction`'s
   * nullable branch, which imported `_has_value`/`_value` entry points the Kotlin half never
   * exports, and the ABI contract failed the whole build as an internal generator error, for a
   * nameable `Crate<Int>?` as much as for `Crate<List<Int>>?`. The route refuses it now, and the
   * planner's NULLABLE return skip is the named diagnostic.
   */
  @Test
  fun `a nullable generic return skips the function, named, instead of failing the build`() {
    val result = run()

    assertTrue(result.kspErrors.isEmpty(), "expected no KSP errors; got: ${result.kspErrors}")
    listOf("maybeCrateOfList", "maybeCrateOfInt").forEach { function ->
      val csName: String = function.replaceFirstChar { it.uppercase() }
      assertFalse(
        result.generatedCSharp.contains("${csName}_"),
        "expected no $csName import on the C# half; got: " +
            "${result.generatedCSharp.lines().filter { it.contains(csName) }}",
      )
      assertTrue(
        skipDiagnostic(result, function).contains("NULLABLE"),
        "expected the NULLABLE return skip for $function; got: ${skipDiagnostic(result, function)}",
      )
    }
  }

  @Test
  fun `a nullable return skip names the refused type, in the sentence and the hint`() {
    val result = run()

    mapOf(
      "maybeCrateOfInt" to "`Crate<Int>?`",
      "maybeCrateOfList" to "`Crate<List<Int>>?`",
    ).forEach { (function, spelled) ->
      val diagnostic: String = skipDiagnostic(result, function)
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
  fun `an unnameable generic-return type argument skips the function, named`() {
    val result = run()

    skipped.forEach { (function, typeArgument) ->
      val csName: String = function.replaceFirstChar { it.uppercase() }
      val lines: List<String> = result.generatedCSharp.lines().filter { it.contains(" $csName(") }
      assertTrue(
        lines.isEmpty(),
        "expected $csName absent from Interop.cs, it has no C# spelling; got: $lines",
      )
      assertSkipDiagnostic(result, function, typeArgument)
    }
  }

  @Test
  fun `the generic-return skip has its own wording, not the lambda one`() {
    val result = run()

    val diagnostic: String = skipDiagnostic(result, "crateOfList")
    assertFalse(
      diagnostic.contains("lambda type argument"),
      "expected the generic-return skip to describe a generic return, not a lambda; got: " +
          diagnostic,
    )
    assertTrue(
      diagnostic.contains("generic return"),
      "expected the generic-return skip to name its route; got: $diagnostic",
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
    assertTrue(
      result.generatedCSharp.contains("public static Crate<T> CrateOf<T>(T item)"),
      "expected a type-parameter return to keep binding; got: " +
          "${result.generatedCSharp.lines().filter { it.contains("CrateOf<") }}",
    )
    listOf("crateOfInt", "crateOfSnapshot", "crateOf").forEach { function ->
      assertTrue(
        result.kspWarnings.none { it.contains("[nuget:SKIPPED_") && it.contains("$function: ") },
        "expected no skip for $function; kspWarnings=${result.kspWarnings}",
      )
    }
  }

  /**
   * `csTypeArgument` reads the argument's own nullability, so a nullable type argument keeps its
   * `?` on this route: `Crate<Int?>` is `Crate<int?>`, never `Crate<int>` where null reads `0`.
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
   * `Crate<T?>` at a type parameter never reaches `csTypeArgument`. With a parameter of its own
   * type parameter it binds on the generic-function route, which spells the return by ADR-147's
   * bare-`T` rule as `Crate<T>`: the `?` written on `T?` is not carried into C#, the instantiation
   * says whether the item holds null (`CrateOfMaybe<int?>` does, `CrateOfMaybe<int>` reads `0`).
   * Without one it is the generic route's existing named skip. Both are pinned as they stand.
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

  /** The Kotlin half keeps its orphan export (like the lambda-return skip) and still compiles. */
  @Test
  fun `the generated Kotlin still compiles`() {
    val result = run()

    assertTrue(
      result.compiledClean,
      "expected clean generated Kotlin; got: ${result.compileErrors}",
    )
    assertTrue(result.kspErrors.isEmpty(), "expected no KSP errors; got: ${result.kspErrors}")
    assertTrue(
      result.generated.contains("\"library_catcam__crateOfList\""),
      "expected the C#-only skip to leave the Kotlin export in place, as the lambda-return skip " +
          "does; got no library_catcam__crateOfList in CNameExports.kt",
    )
  }

  private fun skipDiagnostic(result: Tier1Result, function: String): String = requireNotNull(
    result.kspWarnings.firstOrNull {
      it.contains("[nuget:${ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_RETURN.name}]") &&
          it.contains("$function: ")
    },
  ) {
    "expected a SKIPPED_UNSUPPORTED_RETURN naming $function; kspWarnings=${result.kspWarnings}"
  }

  private fun assertSkipDiagnostic(result: Tier1Result, function: String, typeArgument: String) {
    val diagnostic: String = skipDiagnostic(result, function)
    assertTrue(
      diagnostic.contains("`$typeArgument`"),
      "expected the $function diagnostic to name $typeArgument; got: $diagnostic",
    )
  }
}
