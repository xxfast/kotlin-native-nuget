package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-071 amendment (nullable member write): a `MutableStateFlow<T>?` property keeps ADR-067's
 * `_has_value` probe (the C# getter answers null when the member is absent) and gains a settable
 * `.Value` on the present flow. A write that finds the member absent (a getter that flipped to
 * null after the probe) throws `IllegalStateException` through the existing `errorOut` catch, so
 * it surfaces as a `KotlinException`, never a silent no-op.
 *
 * A function returning `MutableStateFlow<T>?` stays on the read-only `_has_value` StateFlow route.
 */
class Tier1MutableStateFlowNullableMemberWriteTest {

  @Test
  fun `a nullable MutableStateFlow member is settable and throws when absent`() {
    val result = Tier1Harness.run(
      """
      package tier1.nullablememberwrite

      import kotlinx.coroutines.flow.MutableStateFlow

      class Diary {
        private var _entry: MutableStateFlow<String>? = null
        val entry: MutableStateFlow<String>? get() = _entry
        fun open(first: String) { _entry = MutableStateFlow(first) }
        fun pages(): MutableStateFlow<String>? = _entry
      }
      """.trimIndent(),
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )

    assertTrue(result.compiledClean, "got: ${result.compileErrors} ${result.kspErrors}")

    val kotlin: String = result.generated
    val csharp: String = result.generatedCSharp

    assertContains(kotlin, "@CName(\"library_tier1_nullablememberwrite__diary_get_entry_has_value\")")
    assertContains(kotlin, "@CName(\"library_tier1_nullablememberwrite__diary_set_entry_value\")")
    assertContains(
      kotlin,
      "(handle.asStableRef<tier1.nullablememberwrite.Diary>().get().entry ?: throw " +
          "IllegalStateException(\"entry is null\")).value = value",
    )

    assertContains(csharp, "public KotlinMutableStateFlow<string>? Entry")
    assertContains(csharp, "if (!Native_GetEntryHasValue(_handle))")
    assertContains(csharp, "return new KotlinMutableStateFlow<string>(")
    assertContains(
      csharp,
      "private static extern void Native_SetEntryValue(NugetKotlinHandle handle, " +
          "[MarshalAs(UnmanagedType.LPUTF8Str)] string value, out IntPtr error);",
    )

    // The function-return twin stays read-only: no held acquire, no flow-keyed setter.
    assertContains(csharp, "public KotlinStateFlow<string>? Pages()")
    assertFalse(
      kotlin.contains("diary_pages_set_value"),
      "expected no setter on a nullable MutableStateFlow function return; generated=$kotlin",
    )
  }
}
