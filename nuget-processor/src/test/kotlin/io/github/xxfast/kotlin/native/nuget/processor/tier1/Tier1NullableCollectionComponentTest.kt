package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

/**
 * ADR-083: a null collection component rides the null pointer in its own slot, in both directions.
 * One cell per mechanism the ADR names rather than one per fixture member: the component kinds at
 * an input element position (reference, boxed primitive, ObjectHandle, and the value-class
 * composition with ADR-081), both map slots (value admitted, key skipped by name), the property
 * setter (ADR-075's shared predicate), the read side, and the narrowed nullable-element gate that
 * turns the remaining nullable spellings into named skips instead of a KSP crash.
 */
class Tier1NullableCollectionComponentTest {

  @Test
  fun `list input lowers each nullable component kind on both halves`() {
    val result = Tier1Harness.run(
      """
      package tier1.nullablecollectioninput

      class Nurse(val name: String)

      @JvmInline
      value class ChartId(val value: String)

      class ChartBook {
        fun tag(tags: List<String?>): Int = tags.size

        fun logAges(ages: MutableList<Int?>): Int = ages.size

        fun onCall(nurses: List<Nurse?>): Int = nurses.size

        fun file(charts: List<ChartId?>): Int = charts.size

        fun codes(codes: Set<String?>): Int = codes.size
      }
      """.trimIndent(),
    )

    assertTrue(
      result.compiledClean,
      "expected nullable list/set inputs to compile; got: ${result.compileErrors}",
    )

    val kotlin: String = result.generated
    // A plain component is already `null` in the `Any?` box, so the cast simply goes nullable.
    assertContains(kotlin, ".map { it as kotlin.String? }")
    assertContains(kotlin, ".mapTo(mutableListOf()) { it as kotlin.Int? }")
    assertContains(kotlin, ".map { it as tier1.nullablecollectioninput.Nurse? }")
    assertContains(kotlin, ".mapTo(mutableSetOf()) { it as kotlin.String? }")
    // ADR-081's re-wrap composes under `?.let`, so the value class's own `init` runs only for the
    // present elements.
    assertContains(
      kotlin,
      ".map { (it as kotlin.String?)?.let { v -> tier1.nullablecollectioninput.ChartId(v) } }",
    )

    val cs: String = result.generatedCSharp
    // Plain components pass through untouched -- Wrap<T>'s null guard and its nullable-underlying
    // normalization do the work.
    assertContains(cs, "public int Tag(IReadOnlyList<string?> tags)")
    assertContains(cs, "NugetMarshal.CreateList(tags)")
    assertContains(cs, "public int LogAges(IList<int?> ages)")
    assertContains(cs, "NugetMarshal.CreateList(ages)")
    // The value-class projection lifts to `?.`.
    assertContains(cs, "public int File(IReadOnlyList<ChartId?> charts)")
    assertContains(
      cs,
      "NugetMarshal.CreateList(global::System.Linq.Enumerable.Select(charts, x => x?.Value))",
    )
  }

  @Test
  fun `Wrap guards null and dispatches on the underlying of a nullable component`() {
    val result = Tier1Harness.run(
      """
      package tier1.nullablecollectionwrap

      class ChartBook {
        fun tag(tags: List<String?>): Int = tags.size
      }
      """.trimIndent(),
    )

    val cs: String = result.generatedCSharp
    // Without both lines a `List<int?>` would miss every typeof branch (typeof(int?) is not
    // typeof(int)) and a null `string` would reach nuget_wrap_string.
    assertContains(cs, "if (value == null) return IntPtr.Zero;")
    assertContains(cs, "var type = Nullable.GetUnderlyingType(typeof(T)) ?? typeof(T);")
    assertContains(cs, "if (type == typeof(string)) return nuget_wrap_string((string)(object)value!);")
  }

  @Test
  fun `map value slot is admitted and the key slot is not`() {
    val result = Tier1Harness.run(
      """
      package tier1.nullablecollectionmap

      class ChartBook {
        fun scoreBoard(scores: Map<String, Int?>): Int = scores.size

        fun keyedScores(scores: Map<String?, Int>): Int = scores.size
      }
      """.trimIndent(),
    )

    assertTrue(
      result.compiledClean,
      "expected a nullable map value to compile; got: ${result.compileErrors}",
    )

    val kotlin: String = result.generated
    assertContains(
      kotlin,
      "associate { (k, v) -> (k as kotlin.String) to (v as kotlin.Int?) }",
    )
    // A C# Dictionary cannot hold a null key, so the key spelling skips entirely.
    assertTrue(
      "export_chartbook_keyedScores" !in kotlin,
      "expected the nullable-key map input to be skipped; generated=$kotlin",
    )
  }

  @Test
  fun `nullable collection property round-trips through its setter and getter`() {
    val result = Tier1Harness.run(
      """
      package tier1.nullablecollectionproperty

      class ChartBook {
        var visitCounts: List<Int?> = listOf(3, null)
      }
      """.trimIndent(),
    )

    assertTrue(
      result.compiledClean,
      "expected a nullable-component collection property to compile; got: ${result.compileErrors}",
    )

    val kotlin: String = result.generated
    assertContains(
      kotlin,
      "visitCounts = value.asStableRef<MutableList<Any?>>().get().map { it as kotlin.Int? }",
    )

    val cs: String = result.generatedCSharp
    // The getter's read is the two-statement form: the handle cannot be re-evaluated, since each
    // Get mints a fresh StableRef.
    assertContains(cs, "IntPtr elementHandle = NugetListNative.Get(nativeResult, i);")
    assertContains(
      cs,
      "result.Add(elementHandle == IntPtr.Zero ? (int?)null : " +
          "NugetMarshal.FromHandle<int>(elementHandle));",
    )
  }

  @Test
  fun `nullable components read back through the null pointer on the result side`() {
    val result = Tier1Harness.run(
      """
      package tier1.nullablecollectionreturn

      @JvmInline
      value class ChartId(val value: String)

      class ChartBook {
        fun missingTags(): List<String?> = listOf("OREO", null)

        fun spareCodes(): Set<String?> = setOf("OREO", null)

        fun chartAssignments(): Map<String, ChartId?> = mapOf("oreo" to null)
      }
      """.trimIndent(),
    )

    assertTrue(
      result.compiledClean,
      "expected nullable-component returns to compile; got: ${result.compileErrors}",
    )

    val cs: String = result.generatedCSharp
    assertContains(cs, "IntPtr elementHandle = NugetListNative.Get(listHandle, i);")
    assertContains(
      cs,
      "result.Add(elementHandle == IntPtr.Zero ? (string?)null : " +
          "NugetMarshal.FromHandle<string>(elementHandle));",
    )
    assertContains(cs, "IntPtr elementHandle = NugetSetNative.ElementAt(setHandle, i);")
    // The value-class re-wrap sits inside the non-null arm only.
    assertContains(cs, "IntPtr valueHandle = NugetMapNative.ValueAt(mapHandle, i);")
    assertContains(
      cs,
      "var value = valueHandle == IntPtr.Zero ? (ChartId?)null : " +
          "new ChartId(NugetMarshal.FromHandle<string>(valueHandle));",
    )
  }

  /**
   * The enum-underlying value class is the one component kind whose nullable projection is not a
   * plain `?.` on either side: the int-ordinal wire needs an explicit conditional in C# (the
   * `(int)` cast is not lifted) and `entries[...]` needs the `?.let` in Kotlin. No fixture member
   * carries this shape, so the C# half here is asserted by text, not by a C# compile.
   */
  @Test
  fun `nullable value class over an enum underlying rides the int ordinal on both halves`() {
    val result = Tier1Harness.run(
      """
      package tier1.nullablecollectionenumvc

      enum class Mood { CALM, ANXIOUS }

      @JvmInline
      value class Temperament(val mood: Mood)

      class ChartBook {
        fun record(moods: List<Temperament?>): Int = moods.size

        fun onFile(): List<Temperament?> = listOf(Temperament(Mood.CALM), null)
      }
      """.trimIndent(),
    )

    assertTrue(
      result.compiledClean,
      "expected a nullable enum-underlying value-class component to compile; got: " +
          "${result.compileErrors}",
    )

    val kotlin: String = result.generated
    assertContains(
      kotlin,
      ".map { (it as kotlin.Int?)?.let { v -> tier1.nullablecollectionenumvc.Temperament(" +
          "tier1.nullablecollectionenumvc.Mood.entries[v]) } }",
    )
    assertContains(kotlin, "get().onFile().map { it?.mood?.ordinal }")

    val cs: String = result.generatedCSharp
    assertContains(
      cs,
      "NugetMarshal.CreateList(global::System.Linq.Enumerable.Select(moods, " +
          "x => x == null ? (int?)null : (int)x.Value.Mood))",
    )
    assertContains(
      cs,
      "result.Add(elementHandle == IntPtr.Zero ? (Temperament?)null : " +
          "new Temperament((global::Interop.Mood)NugetMarshal.FromHandle<int>(elementHandle)));",
    )
  }

  @Test
  fun `nullable list element outside the wrappable set skips named instead of crashing`() {
    val result = Tier1Harness.run(
      """
      package tier1.nullablecollectionnarrowing

      enum class Mood { CALM, ANXIOUS }

      class ChartBook {
        fun recordMoods(moods: List<Mood?>): Int = moods.size

        fun tallyShorts(counts: List<Short?>): Int = counts.size

        fun initials(letters: List<Char?>): Int = letters.size
      }
      """.trimIndent(),
    )

    assertTrue(
      result.compiledClean,
      "expected the narrowed nullable-element gate to leave compilable source; got: " +
          "${result.compileErrors}",
    )

    val kotlin: String = result.generated
    // All three used to crash the whole KSP run in componentLowering; each is a named skip now.
    assertTrue("export_chartbook_recordMoods" !in kotlin, "generated=$kotlin")
    assertTrue("export_chartbook_tallyShorts" !in kotlin, "generated=$kotlin")
    assertTrue("export_chartbook_initials" !in kotlin, "generated=$kotlin")
  }
}
