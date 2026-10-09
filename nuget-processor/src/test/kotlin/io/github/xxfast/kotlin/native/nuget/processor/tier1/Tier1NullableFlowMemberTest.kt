package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-067's nullable member, widened to plain `Flow`: a class member whose whole `Flow<T>` can be
 * absent binds as `KotlinFlow<T>?` (property and method) and `Task<KotlinFlow<T>?>` (suspend, class
 * and top-level), null when the Kotlin member is null.
 *
 * Before this, the property and method routes admitted a `Flow<T>?` member and then emitted
 * `obj.diet.collect { ... }` on a nullable receiver: generated Kotlin that did not compile, with
 * no diagnostic. The suspend route refused it by name.
 *
 * Oreo has a special diet; Mylo does not, and a feeder that has served no meals has no moods yet.
 */
class Tier1NullableFlowMemberTest {

  private val result by lazy {
    Tier1Harness.run(
      """
      package tier1.feeder

      import kotlinx.coroutines.flow.Flow
      import kotlinx.coroutines.flow.StateFlow
      import kotlinx.coroutines.flow.flowOf

      enum class Mood { HUNGRY, NAPPING }

      class Kibble(val brand: String)

      // The interface carrier and the sealed arm delegate to the same two builders.
      interface Pantry {
        val stash: Flow<Int>?
        fun stashFor(meals: Int): Flow<Int>?
      }

      class Cupboard : Pantry {
        override val stash: Flow<Int>? = null
        override fun stashFor(meals: Int): Flow<Int>? = null
      }

      fun pantry(): Pantry = Cupboard()

      sealed class Meal {
        class Dinner : Meal() {
          val courses: Flow<String>? = null
          fun coursesFor(guests: Int): Flow<String>? = null
        }
      }

      class Feeder(val catName: String) {
        val specialDiet: Flow<String>? get() = if (catName == "Mylo") null else flowOf("salmon")
        val bowls: Flow<Kibble>? get() = null
        val portions: Flow<Int> = flowOf(1)

        fun moodsAfter(meals: Int): Flow<Mood>? = if (meals == 0) null else flowOf(Mood.HUNGRY)
        fun kibbleFor(meals: Int): Flow<Kibble>? = null
        // The StateFlow twin (ADR-067) binds on the same route and warned "Skipping" too.
        fun lastMeal(meals: Int): StateFlow<String>? = null

        suspend fun maybePortions(count: Int): Flow<Int>? = if (count == 0) null else flowOf(count)
        suspend fun maybeKibble(): Flow<Kibble>? = null
      }

      suspend fun maybeTreats(count: Int): Flow<String>? = if (count == 0) null else flowOf("tuna")
      """.trimIndent(),
      fileName = "Feeder.kt",
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )
  }

  @Test
  fun `a nullable Flow member generates Kotlin that compiles`() {
    assertTrue(
      result.compiledClean,
      "expected clean generated Kotlin; got: ${result.compileErrors} ${result.kspErrors}",
    )
    // ADR-055: the contract check accepts the new `_has_value` export and DllImport pairs.
    assertTrue(result.kspErrors.isEmpty(), "expected no KSP error; got: ${result.kspErrors}")
    assertTrue(
      result.kspWarnings.none { warning ->
        listOf(
          "Feeder.specialDiet", "Feeder.bowls", "Feeder.moodsAfter", "Feeder.kibbleFor",
          "Feeder.lastMeal", "Feeder.maybePortions", "Feeder.maybeKibble", "feeder.maybeTreats",
          "Pantry.stash", "Pantry.stashFor", "Dinner.courses", "Dinner.coursesFor",
        ).any { member -> "Skipping tier1.$member" in warning }
      },
      "expected no skip for any nullable Flow member; kspWarnings=${result.kspWarnings}",
    )
  }

  @Test
  fun `the property and method declare a nullable KotlinFlow and probe presence first`() {
    val csharp: String = result.generatedCSharp
    listOf(
      "KotlinFlow<string>? SpecialDiet",
      "KotlinFlow<global::Interop.Kibble>? Bowls",
      "KotlinFlow<global::Interop.Mood>? MoodsAfter(int meals)",
      "KotlinFlow<global::Interop.Kibble>? KibbleFor(int meals)",
    ).forEach { expected ->
      assertContains(
        csharp,
        expected,
        message = "expected a nullable KotlinFlow member; csharp=" +
            "${csharp.lines().filter { it.contains("KotlinFlow<") }}",
      )
    }
    // The non-null twin keeps the shipped non-null spelling.
    assertContains(csharp, "KotlinFlow<int> Portions")
    assertFalse("KotlinFlow<int>? Portions" in csharp, csharp)

    val kotlin: String = result.generated
    listOf(
      "_get_specialDiet_has_value", "_get_bowls_has_value",
      "_moodsAfter_has_value", "_kibbleFor_has_value",
    ).forEach { probe ->
      assertContains(kotlin, probe, message = "expected the $probe export")
      assertContains(csharp, probe, message = "expected the $probe DllImport")
    }
    assertFalse("_get_portions_has_value" in kotlin, kotlin)
    val getter: String = csharp.substringAfter("KotlinFlow<string>? SpecialDiet")
      .substringBefore("Native_GetSpecialDietCollect(")
    assertContains(getter, "if (!Native_GetSpecialDietHasValue(_handle))")
    assertContains(getter, "return null;")
    val method: String = csharp.substringAfter("KotlinFlow<global::Interop.Mood>? MoodsAfter(")
      .substringBefore("Native_MoodsAfterCollect(")
    assertContains(method, "if (!Native_MoodsAfterHasValue(_handle, meals))")

    // The interface carrier and the sealed arm inherit the same two builders.
    listOf(
      "KotlinFlow<int>? Stash", "KotlinFlow<int>? StashFor(int meals)",
      "KotlinFlow<string>? Courses", "KotlinFlow<string>? CoursesFor(int guests)",
    ).forEach { expected -> assertContains(csharp, expected) }
  }

  @Test
  fun `the suspend position awaits a nullable KotlinFlow guarded on the wire pointer`() {
    val csharp: String = result.generatedCSharp
    assertContains(csharp, "public Task<KotlinFlow<int>?> MaybePortionsAsync(")
    assertContains(csharp, "public Task<KotlinFlow<global::Interop.Kibble>?> MaybeKibbleAsync(")
    assertContains(csharp, "public static Task<KotlinFlow<string>?> MaybeTreatsAsync(")
    assertContains(csharp, "TaskCompletionSource<KotlinFlow<int>?>")
    // The `?` is a separate flag: it never reaches the construction site.
    assertFalse(Regex("new KotlinFlow<[^\\n]*>\\?\\(").containsMatchIn(csharp), csharp)
    assertEquals(
      3,
      Regex(Regex.escape("if (resultPtr == IntPtr.Zero)")).findAll(csharp).count(),
      "expected each nullable suspend Flow guarded on the wire; csharp=$csharp",
    )
  }
}
