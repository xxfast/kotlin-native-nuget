package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

/**
 * ADR-081: a value-class component of a collection crosses as its *underlying*, projected per
 * element on both sides. One cell per mechanism the ADR names rather than one per fixture member:
 * the four underlyings at an input element position, both slots of a map independently, the
 * property setter (ADR-075's shared predicate), and the read side (method return + property
 * getter), which bound-and-broke before this ADR by boxing the value class itself.
 */
class Tier1ValueClassCollectionComponentTest {

  @Test
  fun `list input lowers each underlying on both halves`() {
    val result = Tier1Harness.run(
      """
      package tier1.vccollectioninput

      enum class Mood { CALM, ANXIOUS }

      class Patient(val name: String)

      @JvmInline
      value class ChartId(val value: String)

      @JvmInline
      value class Dosage(val milligrams: Double)

      @JvmInline
      value class Temperament(val mood: Mood)

      @JvmInline
      value class ChartRef(val patient: Patient)

      class ChartBook {
        fun file(charts: List<ChartId>): String = charts.joinToString(",") { it.value }

        fun sumDosages(doses: MutableList<Dosage>): Double = doses.sumOf { it.milligrams }

        fun namesOf(refs: List<ChartRef>): String = refs.joinToString(",") { it.patient.name }

        fun recordMoods(moods: Set<Temperament>): Int = moods.size
      }
      """.trimIndent(),
    )

    assertTrue(result.compiledClean, "expected value-class list inputs to compile; got: ${result.compileErrors}")

    val kotlin: String = result.generated
    // String and primitive underlyings cast to the underlying's wire type and re-wrap.
    assertContains(kotlin, ".map { tier1.vccollectioninput.ChartId(it as kotlin.String) }")
    assertContains(
      kotlin,
      ".mapTo(mutableListOf()) { tier1.vccollectioninput.Dosage(it as kotlin.Double) }",
    )
    // ObjectHandle underlying: the generic box already holds the underlying instance.
    assertContains(kotlin, ".map { tier1.vccollectioninput.ChartRef(it as tier1.vccollectioninput.Patient) }")
    // Enum underlying rides the int ordinal.
    assertContains(
      kotlin,
      ".mapTo(mutableSetOf()) { tier1.vccollectioninput.Temperament(" +
          "tier1.vccollectioninput.Mood.entries[it as kotlin.Int]) }",
    )

    val cs: String = result.generatedCSharp
    assertContains(cs, "NugetMarshal.CreateList(global::System.Linq.Enumerable.Select(charts, x => x.Value))")
    assertContains(cs, "NugetMarshal.CreateList(global::System.Linq.Enumerable.Select(doses, x => x.Milligrams))")
    assertContains(cs, "NugetMarshal.CreateList(global::System.Linq.Enumerable.Select(refs, x => x.Patient))")
    // The enum underlying is pre-cast to int so `Wrap<T>`'s existing int branch fires.
    assertContains(cs, "NugetMarshal.CreateSet(global::System.Linq.Enumerable.Select(moods, x => (int)x.Mood))")
  }

  @Test
  fun `map input projects the key and value slots independently`() {
    val result = Tier1Harness.run(
      """
      package tier1.vccollectionmap

      @JvmInline
      value class ChartId(val value: String)

      class ChartBook {
        fun sectioned(sections: Map<String, ChartId>): Int = sections.size

        fun tallyByChart(counts: Map<ChartId, Int>): Int = counts.values.sum()
      }
      """.trimIndent(),
    )

    assertTrue(result.compiledClean, "expected value-class map components to compile; got: ${result.compileErrors}")

    val kotlin: String = result.generated
    assertContains(
      kotlin,
      "associate { (k, v) -> (k as kotlin.String) to (tier1.vccollectionmap.ChartId(v as kotlin.String)) }",
    )
    assertContains(
      kotlin,
      "associate { (k, v) -> (tier1.vccollectionmap.ChartId(k as kotlin.String)) to (v as kotlin.Int) }",
    )

    val cs: String = result.generatedCSharp
    assertContains(
      cs,
      "NugetMarshal.CreateMap(global::System.Linq.Enumerable.Select(sections, " +
          "x => new KeyValuePair<string, string>(x.Key, x.Value.Value)))",
    )
    assertContains(
      cs,
      "NugetMarshal.CreateMap(global::System.Linq.Enumerable.Select(counts, " +
          "x => new KeyValuePair<string, int>(x.Key.Value, x.Value)))",
    )
  }

  @Test
  fun `collection property setter and getter project every element`() {
    val result = Tier1Harness.run(
      """
      package tier1.vccollectionproperty

      @JvmInline
      value class ChartId(val value: String)

      class ChartBook {
        var pendingCharts: List<ChartId> = listOf(ChartId("CH-1"))
      }
      """.trimIndent(),
    )

    assertTrue(result.compiledClean, "expected a value-class collection property to compile; got: ${result.compileErrors}")

    val kotlin: String = result.generated
    // Setter: re-wrap per element. Getter: box a copy projected to the underlying.
    assertContains(
      kotlin,
      "pendingCharts = value.asStableRef<MutableList<Any?>>().get()" +
          ".map { tier1.vccollectionproperty.ChartId(it as kotlin.String) }",
    )
    assertContains(kotlin, "get().pendingCharts.map { it.value }")

    val cs: String = result.generatedCSharp
    assertContains(
      cs,
      "NugetMarshal.CreateList(global::System.Linq.Enumerable.Select(value, x => x.Value))",
    )
    assertContains(
      cs,
      "result.Add(new ChartId(NugetMarshal.FromHandle<string>(NugetListNative.Get(nativeResult, i))));",
    )
  }

  @Test
  fun `collection return re-wraps per element on the read side`() {
    val result = Tier1Harness.run(
      """
      package tier1.vccollectionreturn

      enum class Mood { CALM, ANXIOUS }

      @JvmInline
      value class ChartId(val value: String)

      @JvmInline
      value class Temperament(val mood: Mood)

      class ChartBook {
        fun issuedCharts(): List<ChartId> = listOf(ChartId("CH-1"), ChartId("CH-2"))

        fun moodsOnFile(): Set<Temperament> = setOf(Temperament(Mood.ANXIOUS))

        fun sectionsOnFile(): Map<String, ChartId> = mapOf("north" to ChartId("CH-1"))
      }
      """.trimIndent(),
    )

    assertTrue(result.compiledClean, "expected value-class collection returns to compile; got: ${result.compileErrors}")

    val kotlin: String = result.generated
    // The projected copy is what gets boxed; a Set stays a Set so `nuget_set_*` can read it.
    assertContains(kotlin, "get().issuedCharts().map { it.value }")
    assertContains(kotlin, "get().moodsOnFile().mapTo(mutableSetOf()) { it.mood.ordinal }")
    // Map result: only the slot that is a value class is projected.
    assertContains(kotlin, "get().sectionsOnFile().entries.associate { (k, v) -> k to v.value }")

    val cs: String = result.generatedCSharp
    assertContains(
      cs,
      "result.Add(new ChartId(NugetMarshal.FromHandle<string>(NugetListNative.Get(listHandle, i))));",
    )
    assertContains(
      cs,
      "result.Add(new Temperament((global::Interop.Mood)NugetMarshal.FromHandle<int>(" +
          "NugetSetNative.ElementAt(setHandle, i))));",
    )
    assertContains(
      cs,
      "var value = new ChartId(NugetMarshal.FromHandle<string>(NugetMapNative.ValueAt(mapHandle, i)));",
    )
  }
}
