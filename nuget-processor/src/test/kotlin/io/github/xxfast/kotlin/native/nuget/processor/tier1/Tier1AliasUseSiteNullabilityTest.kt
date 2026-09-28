package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-018 amendment: a typealias's use-site `?` is part of the type, and a generic alias's type
 * parameters are substituted with the use-site arguments.
 *
 * `expandAliases()` used to return the alias's RHS as written, so `Name?` read as `String` to every
 * reader that took nullability off the expanded type. The plan route already ORed the unexpanded
 * `?` back in; the suspend and StateFlow legacy routes did not, and produced Kotlin that failed to
 * compile (`String?` retained as `Any`, an unsafe call on a nullable `StateFlow` receiver). A
 * `suspend fun ...(): Names?` split the two halves of the ABI outright: the C# half planned an
 * export the Kotlin half refused (`ERROR_INTERNAL_GENERATOR_FAILURE`).
 */
class Tier1AliasUseSiteNullabilityTest {

  private val source: String = """
    package tier1.aliasnull

    import kotlinx.coroutines.flow.Flow
    import kotlinx.coroutines.flow.MutableStateFlow
    import kotlinx.coroutines.flow.StateFlow
    import kotlinx.coroutines.flow.flowOf

    typealias Name = String
    typealias MaybeName = String?
    typealias Names = List<String>
    typealias Age = Int
    typealias MaybeState = StateFlow<Int>
    typealias Box<T> = List<T>

    suspend fun sGreet(n: Name?): Name? = n
    suspend fun sAge(a: Age?): Age? = a
    suspend fun sMaybe(n: MaybeName): MaybeName = n
    suspend fun sMapPlain(m: Map<String?, Int>): Int = 1
    suspend fun sMapAlias(m: Map<Name?, Int>): Int = 1
    suspend fun sRetNames(): Names? = null
    suspend fun sBoxed(): Box<Int> = listOf(1)

    fun boxed(b: Box<Int>): Box<Int> = b
    fun boxedNames(b: Box<String>): Box<String> = b

    class Dog {
      suspend fun sName(): Name? = null
      suspend fun sAge(): Age? = null
      val maybeState: MaybeState? = null
      val state: MaybeState = MutableStateFlow(1)
      val elemState: StateFlow<MaybeName> = MutableStateFlow(null)
      fun elemFlow(): Flow<MaybeName> = flowOf(null)
    }
  """.trimIndent()

  private val result by lazy {
    Tier1Harness.run(source, libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore))
  }

  @Test
  fun `the generated Kotlin compiles and the generator does not fail`() {
    assertTrue(result.kspErrors.isEmpty(), "got: ${result.kspErrors}")
    assertTrue(result.compiledClean, "got: ${result.compileErrors}\n${result.generated}")
  }

  @Test
  fun `a nullable alias crosses the suspend route as nullable`() {
    val csharp: String = result.generatedCSharp
    assertContains(csharp, "Task<string?> SGreetAsync(string? n")
    assertContains(csharp, "Task<int?> SAgeAsync(int? a")
    assertContains(csharp, "Task<string?> SMaybeAsync(string? n")
    assertContains(csharp, "Task<string?> SNameAsync(")
    assertContains(csharp, "Task<int?> SAgeAsync(")
  }

  @Test
  fun `a nullable alias to a StateFlow member binds as a nullable flow`() {
    val csharp: String = result.generatedCSharp
    assertContains(csharp, "KotlinStateFlow<int>? MaybeState")
    assertContains(csharp, "KotlinStateFlow<int> State")
    // An alias TO a nullable type in element position reads nullable too.
    assertContains(csharp, "KotlinStateFlow<string?> ElemState")
    assertContains(csharp, "KotlinFlow<string?> ElemFlow(")
  }

  @Test
  fun `a generic alias is substituted with its use-site arguments`() {
    val csharp: String = result.generatedCSharp
    assertContains(csharp, "IReadOnlyList<int> Boxed(IReadOnlyList<int> b)")
    assertContains(csharp, "IReadOnlyList<string> BoxedNames(IReadOnlyList<string> b)")
    assertContains(csharp, "Task<IReadOnlyList<int>> SBoxedAsync(")
    assertFalse(
      result.kspWarnings.any { "boxed" in it && "UNSUPPORTED" in it },
      "Box<Int> must not be skipped; got: ${result.kspWarnings}",
    )
  }

  @Test
  fun `a nullable collection alias return is refused like its written-out type`() {
    assertFalse(result.generatedCSharp.contains("SRetNamesAsync"), "Names? must stay absent")
    assertFalse(result.generated.contains("sRetNames_async"), "no half-emitted export")
    assertTrue(
      result.kspWarnings.any { "sRetNames" in it && "List<String>?" in it },
      "the refusal names the expanded, nullable type; got: ${result.kspWarnings}",
    )
  }

  @Test
  fun `a refusal message spells every type argument's own nullability`() {
    val plain: String? = result.kspWarnings.firstOrNull { "sMapPlain" in it }
    val aliased: String? = result.kspWarnings.firstOrNull { "sMapAlias" in it }
    assertTrue(plain != null && "m: Map<String?, Int>" in plain, "got: $plain")
    assertTrue(aliased != null && "m: Map<String?, Int>" in aliased, "got: $aliased")
  }

  /**
   * Pins for the legacy collection-return reads (`CirFunctionTranslator` List/MutableList/Set/
   * MutableSet arms, `translateCompanionFunction`, `translateExtensionFunction`), which read the
   * element's `simpleName` without expanding. Spiked 2026-09-28: every one of these shapes is
   * already planned on the ADR-062 route, so the element spells `string`, never the alias `Name`
   * (a CS0246 break). If a shape ever falls back to those reads, this cell names it.
   */
  private val collectionSource: String = """
    package tier1.aliascollections

    typealias Name = String
    typealias P<T> = List<Map<String, T>>

    fun listNames(): List<Name> = listOf("a")
    fun mutableListNames(): MutableList<Name> = mutableListOf("a")
    fun setNames(): Set<Name> = setOf("a")
    fun mutableSetNames(): MutableSet<Name> = mutableSetOf("a")
    class Kennel(val n: Int) {
      companion object {
        fun companionNames(): List<Name> = listOf("a")
      }
    }
    fun Kennel.extNames(): List<Name> = listOf("a")

    fun nested(p: P<Int>): P<Int> = p
  """.trimIndent()

  private val collectionResult by lazy { Tier1Harness.run(collectionSource) }

  @Test
  fun `an alias collection element spells the expanded type on every collection-return route`() {
    val csharp: String = collectionResult.generatedCSharp
    assertTrue(collectionResult.compiledClean, "got: ${collectionResult.compileErrors}")
    assertContains(csharp, "IReadOnlyList<string> ListNames()")
    assertContains(csharp, "IList<string> MutableListNames()")
    assertContains(csharp, "IReadOnlySet<string> SetNames()")
    assertContains(csharp, "ISet<string> MutableSetNames()")
    assertContains(csharp, "IReadOnlyList<string> CompanionNames()")
    assertContains(csharp, "IReadOnlyList<string> ExtNames(")
    assertFalse(csharp.contains("<Name>"), "the alias name must not reach C#")
  }

  /**
   * A generic alias whose parameter is used NESTED (`List<Map<String, T>>`) is not substituted:
   * `expandAliases()` only swaps a parameter spelled as the whole RHS or a top-level argument
   * (building a fresh nested `KSTypeArgument` needs the `Resolver`). It stays the pre-existing
   * named skip on both halves, so a half-substituted `T` can never be spelled into C#.
   */
  @Test
  fun `a nested generic alias parameter stays a named skip on both halves`() {
    assertFalse(collectionResult.generated.contains("__nested"), "no Kotlin export")
    assertFalse(collectionResult.generatedCSharp.contains("__nested\""), "no C# import")
    assertFalse(collectionResult.generatedCSharp.contains(" Nested("), "no C# wrapper")
    assertTrue(
      collectionResult.kspWarnings.any {
        "tier1.aliascollections.nested" in it && "SKIPPED_UNSUPPORTED_TYPE" in it
      },
      "got: ${collectionResult.kspWarnings}",
    )
  }

  /**
   * `typealias Id<T> = T` is not valid Kotlin (an alias must expand to a class type), so the
   * fixture itself does not compile and no real module can reach `expandAliases()`'s bare-parameter
   * arm. KSP still processes it; pinned that the arm spells the use-site type, never a bare `T`.
   */
  @Test
  fun `a bare-parameter alias spells the use-site type, never T`() {
    val result = Tier1Harness.run(
      """
      package tier1.aliasid

      typealias Id<T> = T

      fun ident(i: Id<Int>): Id<Int> = i
      fun identNullable(i: Id<Int>?): Id<Int>? = i
      fun identName(i: Id<String>): Id<String> = i
      """.trimIndent()
    )
    val csharp: String = result.generatedCSharp
    assertTrue(result.kspErrors.isEmpty(), "got: ${result.kspErrors}")
    assertContains(csharp, "public static int Ident(int i)")
    assertContains(csharp, "public static int? IdentNullable(int? i)")
    assertContains(csharp, "public static string IdentName(string i)")
    assertFalse(csharp.contains("(T i)"), "a bare T must not reach C#")
  }
}
