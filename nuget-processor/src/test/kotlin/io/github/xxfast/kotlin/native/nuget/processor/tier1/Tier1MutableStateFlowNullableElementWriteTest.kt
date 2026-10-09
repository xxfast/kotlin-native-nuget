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
 * and `Char?` keep the read-only `KotlinStateFlow<T?>` mapping, mirroring ADR-067's read side.
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

    // Boolean? and Char? stay read-only (ADR-067 width deferral), with no setter on either half.
    assertContains(csharp, "public KotlinStateFlow<bool?> Asleep")
    assertContains(csharp, "public KotlinStateFlow<char?> Initial")
    assertFalse(
      kotlin.contains("tracker_set_asleep_value") || kotlin.contains("tracker_set_initial_value"),
      "expected no setter for Boolean?/Char? elements; generated=$kotlin",
    )
    assertFalse(
      csharp.contains("EntryPoint = \"library_tier1_nullablewrite__tracker_set_asleep_value\"") ||
          csharp.contains(
            "EntryPoint = \"library_tier1_nullablewrite__tracker_set_initial_value\"",
          ),
      "expected no setter import for Boolean?/Char? elements; generatedCSharp=$csharp",
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
