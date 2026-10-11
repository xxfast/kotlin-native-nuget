package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-071 amendment (enum element write): a `MutableStateFlow<SomeEnum>` member binds a settable
 * `KotlinMutableStateFlow<SomeEnum>` whose write crosses as the enum's ordinal, exactly as the
 * synchronous enum setter does: `(int)v` in C#, a `value: Int` slot and `Mood.entries[value]` in
 * Kotlin. Covers the class property, the held method return, the sealed arm property and the
 * awaited `suspend fun` holder, which all share the one write classifier.
 *
 * A nullable enum element (`MutableStateFlow<Mood?>`) is settable too, on the property and the
 * held return: it is the composition of two wires that already existed, the nullable scalar's
 * has-value pair and this ordinal (`v.HasValue, (int)v.GetValueOrDefault()` in C#,
 * `if (valueHasValue) Mood.entries[value] else null` in Kotlin). The awaited route keeps every
 * nullable element read-only, this one included. A value-class element
 * with no boxed form (here over `String?`) is skipped BY NAME (it used to pass the gate as an
 * ordinary class and take the object-handle arm, spelling `v._handle` on a C# record struct, and
 * later bound a read-only holder nothing could read). The writable value-class shapes live in
 * `Tier1MutableStateFlowValueClassElementTest`.
 * The end-to-end half lives in `CatMoodTracker` / `MutableStateFlowEnumElementTests.cs`.
 */
class Tier1MutableStateFlowEnumElementTest {

  private val source: String = """
    package tier1.enumwrite

    import kotlinx.coroutines.flow.MutableStateFlow
    import kotlinx.coroutines.flow.StateFlow

    enum class Mood { HAPPY, SLEEPY, GRUMPY }

    @JvmInline
    value class Tag(val id: String?)

    class Tracker {
      val outlook: MutableStateFlow<Mood> = MutableStateFlow(Mood.SLEEPY)
      val maybeOutlook: MutableStateFlow<Mood?> = MutableStateFlow(null)
      val tag: MutableStateFlow<Tag> = MutableStateFlow(Tag("oreo-1"))
      fun outlookDial(): MutableStateFlow<Mood> = outlook
      fun maybeDial(): MutableStateFlow<Mood?> = maybeOutlook
      fun tagDial(): MutableStateFlow<Tag> = tag
      suspend fun awaitOutlook(): MutableStateFlow<Mood> = outlook
      suspend fun awaitMaybeOutlook(): StateFlow<Mood?> = maybeOutlook
      suspend fun awaitMaybeDial(): MutableStateFlow<Mood?> = maybeOutlook
    }

    sealed class Pet {
      class Cat : Pet() {
        val temper: MutableStateFlow<Mood> = MutableStateFlow(Mood.GRUMPY)
      }
    }
    """.trimIndent()

  @Test
  fun `an enum element MutableStateFlow property writes the ordinal`() {
    val result = Tier1Harness.run(source, libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore))

    assertTrue(
      result.compiledClean,
      "expected the enum-element write to compile; got: ${result.compileErrors} " +
          "${result.kspErrors}",
    )

    val kotlin: String = result.generated
    val csharp: String = result.generatedCSharp

    assertContains(kotlin, "@CName(\"library_tier1_enumwrite__tracker_set_outlook_value\")")
    assertContains(kotlin, ".outlook.value = tier1.enumwrite.Mood.entries[value]")
    assertContains(csharp, Regex("""public KotlinMutableStateFlow<[\w.:]*Mood> Outlook\b"""))
    assertContains(
      csharp,
      "private static extern void Native_SetOutlookValue(NugetKotlinHandle handle, int value, " +
          "out IntPtr error);",
    )
    assertContains(csharp, "Native_SetOutlookValue(_handle, (int)v, out IntPtr error);")
  }

  @Test
  fun `an enum element MutableStateFlow method return is held and writes the ordinal`() {
    val result = Tier1Harness.run(source, libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore))

    assertTrue(result.compiledClean, "got: ${result.compileErrors} ${result.kspErrors}")

    val kotlin: String = result.generated
    val csharp: String = result.generatedCSharp

    assertContains(kotlin, "@CName(\"library_tier1_enumwrite__tracker_outlookDial_set_value\")")
    assertContains(
      kotlin,
      "flowHandle.asStableRef<kotlinx.coroutines.flow.MutableStateFlow<tier1.enumwrite.Mood>>()" +
          ".get().value = tier1.enumwrite.Mood.entries[value]",
    )
    assertContains(csharp, Regex("""public KotlinMutableStateFlow<[\w.:]*Mood> OutlookDial\(\)"""))
    assertContains(
      csharp,
      "private static extern void Native_OutlookDialSetValue(NugetKotlinHandle flowHandle, " +
          "int value, out IntPtr error);",
    )
    assertContains(csharp, "Native_OutlookDialSetValue(owned, (int)v, out IntPtr error);")
  }

  @Test
  fun `an enum element MutableStateFlow on a sealed arm and a suspend return is settable`() {
    val result = Tier1Harness.run(source, libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore))

    assertTrue(result.compiledClean, "got: ${result.compileErrors} ${result.kspErrors}")

    val kotlin: String = result.generated
    val csharp: String = result.generatedCSharp

    // Sealed arm property.
    assertContains(kotlin, ".temper.value = tier1.enumwrite.Mood.entries[value]")
    assertContains(csharp, Regex("""public KotlinMutableStateFlow<[\w.:]*Mood> Temper\b"""))

    // The awaited suspend holder shares the held route's flow-keyed setter.
    assertContains(kotlin, "@CName(\"library_tier1_enumwrite__tracker_awaitOutlook_set_value\")")
    assertContains(csharp, Regex("""Task<KotlinMutableStateFlow<[\w.:]*Mood>> AwaitOutlook"""))
    assertContains(csharp, "(NugetKotlinHandle flowHandle, int value, out IntPtr error)")
    assertFalse(
      csharp.contains("ArgumentNullException(nameof(v))"),
      "expected no null guard on an enum element write; generatedCSharp=$csharp",
    )
  }

  /**
   * The property route: the setter and both `compareAndSet` slots cross as the has-value pair
   * over the ordinal slot, so a null is never ordinal 0.
   */
  @Test
  fun `a nullable enum element property writes a has-value pair over the ordinal`() {
    val result = Tier1Harness.run(source, libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore))

    assertTrue(result.compiledClean, "got: ${result.compileErrors} ${result.kspErrors}")

    val kotlin: String = result.generated
    val csharp: String = result.generatedCSharp

    assertContains(
      csharp,
      Regex("""public KotlinMutableStateFlow<[\w.:]*Mood\?> MaybeOutlook\b"""),
    )
    assertContains(kotlin, "@CName(\"library_tier1_enumwrite__tracker_set_maybeOutlook_value\")")
    assertContains(
      kotlin,
      ".maybeOutlook.value = if (valueHasValue) tier1.enumwrite.Mood.entries[value] else null",
    )
    assertContains(
      kotlin,
      ".maybeOutlook.compareAndSet(" +
          "if (expectHasValue) tier1.enumwrite.Mood.entries[expect] else null, " +
          "if (updateHasValue) tier1.enumwrite.Mood.entries[update] else null)",
    )
    assertContains(
      csharp,
      "private static extern void Native_SetMaybeOutlookValue(NugetKotlinHandle handle, " +
          "[MarshalAs(UnmanagedType.I1)] bool valueHasValue, int value, out IntPtr error);",
    )
    assertContains(
      csharp,
      "Native_SetMaybeOutlookValue(_handle, v.HasValue, (int)v.GetValueOrDefault(), " +
          "out IntPtr error);",
    )
    assertContains(
      csharp,
      "expect.HasValue, (int)expect.GetValueOrDefault(), " +
          "update.HasValue, (int)update.GetValueOrDefault(), out IntPtr error)",
    )
    assertTrue(
      result.kspWarnings.none { it.contains("[nuget:") && it.contains("maybeOutlook") },
      "expected no diagnostic for maybeOutlook; kspWarnings=${result.kspWarnings}",
    )
  }

  /** The held return shares the property's slot, keyed on the flow handle. */
  @Test
  fun `a nullable enum element held return writes the same pair`() {
    val result = Tier1Harness.run(source, libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore))

    assertTrue(result.compiledClean, "got: ${result.compileErrors} ${result.kspErrors}")

    val kotlin: String = result.generated
    val csharp: String = result.generatedCSharp

    assertContains(
      csharp,
      Regex("""public KotlinMutableStateFlow<[\w.:]*Mood\?> MaybeDial\(\)"""),
    )
    assertContains(kotlin, "@CName(\"library_tier1_enumwrite__tracker_maybeDial_set_value\")")
    assertContains(
      kotlin,
      "flowHandle.asStableRef<kotlinx.coroutines.flow.MutableStateFlow<tier1.enumwrite.Mood?>>()" +
          ".get().value = if (valueHasValue) tier1.enumwrite.Mood.entries[value] else null",
    )
    assertContains(
      csharp,
      "Native_MaybeDialSetValue(owned, v.HasValue, (int)v.GetValueOrDefault(), out IntPtr error);",
    )
    // A null current value has no ordinal: the held holder reads through the null-aware export.
    val heldBody: String =
      csharp.substringAfter("MaybeDial()").substringBefore("\n        }\n")
    assertContains(heldBody, "() => NugetStateFlowNative.ValueOrNull(")
  }

  /** The awaited route keeps a nullable element read-only for every kind (ADR-071). */
  @Test
  fun `an awaited nullable enum element stays read-only`() {
    val result = Tier1Harness.run(source, libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore))

    val csharp: String = result.generatedCSharp
    assertContains(csharp, Regex("""Task<KotlinStateFlow<[\w.:]*Mood\?>> AwaitMaybeDial"""))
    assertFalse(
      "tracker_awaitMaybeDial_set_value" in result.generated,
      "expected no Kotlin setter for an awaited nullable element; generated=${result.generated}",
    )
  }

  @Test
  fun `the consumer writes null and an entry through a nullable enum element`() {
    val result = Tier1Harness.run(source, libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore))

    Tier1CSharpCompile.assertCompiles(
      result,
      """
      using Interop;

      namespace Consumer
      {
          public static class Probe
          {
              public static bool Run(Tracker tracker)
              {
                  KotlinMutableStateFlow<Mood?> maybe = tracker.MaybeOutlook;
                  maybe.Value = Mood.Grumpy;
                  maybe.Value = null;
                  bool swapped = maybe.CompareAndSet(null, Mood.Happy);
                  maybe.Update(mood => mood == null ? Mood.Sleepy : null);
                  Mood? after = maybe.UpdateAndGet(_ => Mood.Happy);
                  using KotlinMutableStateFlow<Mood?> dial = tracker.MaybeDial();
                  dial.Value = after;
                  return swapped && dial.Value == Mood.Happy;
              }
          }
      }
      """.trimIndent(),
      allowUnsafe = true,
    )
  }

  @Test
  fun `a suspend StateFlow of a nullable enum reads through the null-aware export`() {
    val result = Tier1Harness.run(source, libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore))

    assertTrue(result.compiledClean, "got: ${result.compileErrors} ${result.kspErrors}")

    val csharp: String = result.generatedCSharp
    assertContains(csharp, Regex("""Task<KotlinStateFlow<[\w.:]*Mood\?>> AwaitMaybeOutlook"""))
    val awaitBody: String = csharp.substringAfter("Task<KotlinStateFlow<")
      .substringAfter("AwaitMaybeOutlook")
      .substringBefore("\n        }\n")
    assertContains(awaitBody, "() => NugetStateFlowNative.ValueOrNull(flowHandle),")
  }

  @Test
  fun `a value class element over a nullable underlying is skipped by name`() {
    val result = Tier1Harness.run(source, libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore))

    assertTrue(result.compiledClean, "got: ${result.compileErrors} ${result.kspErrors}")

    val kotlin: String = result.generated
    val csharp: String = result.generatedCSharp

    // `Tag` wraps a `String?`: a record struct with no box/unbox pair, so nothing could read a
    // bare element back and the flow route drops the member instead of binding it read-only.
    assertFalse(
      Regex("""Flow<[\w.:]*Tag\??>""").containsMatchIn(csharp),
      "expected no holder of a value class element with no boxed form; generatedCSharp=$csharp",
    )
    assertFalse(
      "tracker_set_tag_value" in kotlin || "tracker_tagDial_set_value" in kotlin,
      "expected no Kotlin setter for a value class element; generated=$kotlin",
    )
    assertTrue(
      result.kspWarnings.any {
        it.contains(ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_PROPERTY.name) &&
            it.contains("Tracker.tag:") && it.contains("a value class is a Flow or StateFlow")
      },
      "expected a named skip of Tracker.tag; kspWarnings=${result.kspWarnings}",
    )
    assertTrue(
      result.kspWarnings.any {
        it.contains(ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_RETURN.name) &&
            it.contains("Tracker.tagDial") && it.contains("a value class is a Flow or StateFlow")
      },
      "expected a named skip of Tracker.tagDial; kspWarnings=${result.kspWarnings}",
    )
  }
}
