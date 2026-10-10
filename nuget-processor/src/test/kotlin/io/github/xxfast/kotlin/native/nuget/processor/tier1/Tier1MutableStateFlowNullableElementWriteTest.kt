package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-071 amendment (nullable element write): a `MutableStateFlow<T?>` member gets a settable
 * `.Value` that accepts null, on the property route and the held function-return route alike. A
 * `String?` crosses as one nullable string slot, an object element as a nullable handle
 * (`IntPtr.Zero` for null), and a nullable scalar as the legacy route's has-value pair. `Boolean?`
 * and `Char?` are that pair too, now that both widths are pinned (ADR-067 had deferred them).
 *
 * The end-to-end half lives in `CatMoodTracker` / `MutableStateFlowTests.cs`.
 */
class Tier1MutableStateFlowNullableElementWriteTest {

  private val source: String = """
    package tier1.nullablewrite

    import kotlinx.coroutines.flow.MutableStateFlow

    class Toy(val name: String)

    class Tracker {
      val tag: MutableStateFlow<String?> = MutableStateFlow(null)
      val naps: MutableStateFlow<Int?> = MutableStateFlow(null)
      val toy: MutableStateFlow<Toy?> = MutableStateFlow(null)
      val asleep: MutableStateFlow<Boolean?> = MutableStateFlow(null)
      val initial: MutableStateFlow<Char?> = MutableStateFlow(null)
      fun napJar(): MutableStateFlow<Int?> = naps
      fun asleepSwitch(): MutableStateFlow<Boolean?> = asleep
      fun toyBox(): MutableStateFlow<Toy?> = toy
    }
    """.trimIndent()

  @Test
  fun `a nullable element MutableStateFlow property is settable with null`() {
    val result = Tier1Harness.run(source, libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore))

    assertTrue(
      result.compiledClean,
      "expected the nullable-element write to compile; got: ${result.compileErrors} " +
          "${result.kspErrors}",
    )

    val kotlin: String = result.generated
    val csharp: String = result.generatedCSharp

    // String?: one nullable string slot, assigned as is.
    assertContains(kotlin, "@CName(\"library_tier1_nullablewrite__tracker_set_tag_value\")")
    assertContains(kotlin, "`value`: String?,")
    assertContains(csharp, "public KotlinMutableStateFlow<string?> Tag")
    assertContains(
      csharp,
      "private static extern void Native_SetTagValue(NugetKotlinHandle handle, " +
          "[MarshalAs(UnmanagedType.LPUTF8Str)] string? value, out IntPtr error);",
    )

    // Int?: the has-value pair, one export, never a zero standing in for null.
    assertContains(kotlin, "valueHasValue: Boolean,")
    assertContains(kotlin, ".naps.value = if (valueHasValue) value else null")
    assertContains(csharp, "public KotlinMutableStateFlow<int?> Naps")
    assertContains(
      csharp,
      "private static extern void Native_SetNapsValue(NugetKotlinHandle handle, " +
          "[MarshalAs(UnmanagedType.I1)] bool valueHasValue, int value, out IntPtr error);",
    )
    assertContains(
      csharp,
      "Native_SetNapsValue(_handle, v.HasValue, v.GetValueOrDefault(), out IntPtr error);",
    )

    // Toy?: a nullable handle, unwrapped null-safely; the C# write passes a null handle for null.
    assertContains(kotlin, ".toy.value = value?.asStableRef<tier1.nullablewrite.Toy>()?.get()")
    assertContains(csharp, Regex("""public KotlinMutableStateFlow<[\w.:]*Toy\?> Toy\b"""))
    assertContains(
      csharp,
      "Native_SetToyValue(_handle, v?._handle ?? NugetKotlinHandle.Null, out IntPtr error);",
    )

  }

  /**
   * `Boolean?` and `Char?` were held back by ADR-067's width deferral. Both widths are pinned now
   * (`I1` for `bool`, ADR-069; `U2` for `char`, ADR-098), on the has-value slot, the value slot and
   * the boxed read alike, so they are the same pair as every other nullable scalar.
   */
  @Test
  fun `a nullable Boolean or Char element is the same has-value pair, at a pinned width`() {
    val result = Tier1Harness.run(source, libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore))

    assertTrue(result.compiledClean, "got: ${result.compileErrors} ${result.kspErrors}")

    val kotlin: String = result.generated
    val csharp: String = result.generatedCSharp

    assertContains(csharp, "public KotlinMutableStateFlow<bool?> Asleep")
    assertContains(csharp, "public KotlinMutableStateFlow<char?> Initial")
    assertContains(kotlin, ".asleep.value = if (valueHasValue) value else null")
    assertContains(kotlin, ".initial.value = if (valueHasValue) value else null")
    assertContains(
      kotlin,
      Regex("""tracker_set_asleep_value\([^)]*valueHasValue: Boolean,\s*`?value`?: Boolean,"""),
    )
    assertContains(
      kotlin,
      Regex("""tracker_set_initial_value\([^)]*valueHasValue: Boolean,\s*`?value`?: Char,"""),
    )
    assertContains(
      csharp,
      "private static extern void Native_SetAsleepValue(NugetKotlinHandle handle, " +
          "[MarshalAs(UnmanagedType.I1)] bool valueHasValue, " +
          "[MarshalAs(UnmanagedType.I1)] bool value, out IntPtr error);",
    )
    assertContains(
      csharp,
      "private static extern void Native_SetInitialValue(NugetKotlinHandle handle, " +
          "[MarshalAs(UnmanagedType.I1)] bool valueHasValue, " +
          "[MarshalAs(UnmanagedType.U2)] char value, out IntPtr error);",
    )
    assertContains(
      csharp,
      "Native_SetAsleepValue(_handle, v.HasValue, v.GetValueOrDefault(), out IntPtr error);",
    )
    // The held twin shares the slot and reads a null current value through the null-aware export.
    assertContains(csharp, "public KotlinMutableStateFlow<bool?> AsleepSwitch()")
    assertContains(
      csharp,
      "Native_AsleepSwitchSetValue(owned, v.HasValue, v.GetValueOrDefault(), out IntPtr error);",
    )
    assertTrue(
      result.kspWarnings.none { it.contains("[nuget:") && it.contains("Tracker.") },
      "expected no diagnostic for a settable nullable element; kspWarnings=${result.kspWarnings}",
    )
  }

  @Test
  fun `the consumer writes null, false and a char through nullable Boolean and Char elements`() {
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
                  KotlinMutableStateFlow<bool?> asleep = tracker.Asleep;
                  asleep.Value = false;
                  asleep.Value = null;
                  bool swapped = asleep.CompareAndSet(null, true);
                  asleep.Update(value => value == true ? null : false);

                  KotlinMutableStateFlow<char?> initial = tracker.Initial;
                  initial.Value = 'O';
                  initial.Value = null;
                  char? after = initial.UpdateAndGet(_ => 'M');

                  using KotlinMutableStateFlow<bool?> held = tracker.AsleepSwitch();
                  held.Value = true;
                  return swapped && after == 'M' && held.Value == true;
              }
          }
      }
      """.trimIndent(),
      allowUnsafe = true,
    )
  }

  @Test
  fun `a nullable element MutableStateFlow function return is held and settable with null`() {
    val result = Tier1Harness.run(source, libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore))

    assertTrue(result.compiledClean, "got: ${result.compileErrors} ${result.kspErrors}")

    val kotlin: String = result.generated
    val csharp: String = result.generatedCSharp

    // The held route's flow-keyed setter, typed on the nullable element.
    assertContains(kotlin, "@CName(\"library_tier1_nullablewrite__tracker_napJar_set_value\")")
    assertContains(
      kotlin,
      "flowHandle.asStableRef<kotlinx.coroutines.flow.MutableStateFlow<kotlin.Int?>>().get()" +
          ".value = if (valueHasValue) value else null",
    )
    assertContains(
      kotlin,
      "flowHandle.asStableRef<kotlinx.coroutines.flow.MutableStateFlow<tier1.nullablewrite.Toy?>>()" +
          ".get().value = value?.asStableRef<tier1.nullablewrite.Toy>()?.get()",
    )
    assertContains(csharp, "public KotlinMutableStateFlow<int?> NapJar()")
    assertContains(
      csharp,
      "private static extern void Native_NapJarSetValue(NugetKotlinHandle flowHandle, " +
          "[MarshalAs(UnmanagedType.I1)] bool valueHasValue, int value, out IntPtr error);",
    )
    // A null current value reads through the runtime's null-aware export; `nuget_stateflow_value`
    // has no null arm and would throw out of the `@CName`.
    assertContains(csharp, "() => NugetStateFlowNative.ValueOrNull(owned),")
    assertContains(
      csharp,
      "Native_NapJarSetValue(owned, v.HasValue, v.GetValueOrDefault(), out IntPtr error);",
    )
    assertContains(
      csharp,
      "Native_ToyBoxSetValue(owned, v?._handle ?? NugetKotlinHandle.Null, out IntPtr error);",
    )
    assertFalse(
      csharp.contains("ArgumentNullException(nameof(v))"),
      "expected no null guard on a nullable object element write; generatedCSharp=$csharp",
    )
  }
}
