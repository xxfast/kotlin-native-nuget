package io.github.xxfast.kotlin.native.nuget.processor.cir

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression coverage for the nullable-returning top-level function ABI (ADR-002 two-call
 * pattern combined with ADR-024 synchronous exception propagation).
 *
 * Both native crossings (`_has_value` and `_value`) must declare a synchronous error out-param,
 * otherwise the generated DllImport signature has one fewer parameter than the native function
 * actually writes into, and the very first thrown exception corrupts memory across the ABI.
 *
 * @see <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/002-nullable-two-call-pattern.md">ADR-002</a>
 * @see <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/024-synchronous-exception-propagation.md">ADR-024</a>
 */
class CirFunctionTranslatorNullableTest {

  private fun render(members: List<CirMember>): String = CirRenderer().render(
    CirFile(
      namespaces = listOf(
        CirNamespace(
          name = "SampleLibrary",
          declarations = listOf(CirStaticClass(name = "Mappings", members = members)),
        ),
      ),
    ),
  )

  @Test
  fun `nullable Int function - both native imports carry a sync error out-param`() {
    val members: List<CirMember> = translateNullableFunction(
      cname = "nullableInt",
      csName = "NullableInt",
      kotlinReturnType = "Int",
      params = listOf(CirParameter("hasValue", "bool")),
      libraryName = "SampleLibrary",
    )

    val hasValueImport: CirDllImport = members.filterIsInstance<CirDllImport>()
      .single { it.name == "NullableInt_has_value" }
    val valueImport: CirDllImport = members.filterIsInstance<CirDllImport>()
      .single { it.name == "NullableInt_value" }

    assertTrue(
      hasValueImport.hasSyncErrorOut,
      "NullableInt_has_value must declare a sync error out-param",
    )
    assertTrue(valueImport.hasSyncErrorOut, "NullableInt_value must declare a sync error out-param")
  }

  @Test
  fun `nullable Int function - rendered DllImports declare out IntPtr error`() {
    val members: List<CirMember> = translateNullableFunction(
      cname = "nullableInt",
      csName = "NullableInt",
      kotlinReturnType = "Int",
      params = listOf(CirParameter("hasValue", "bool")),
      libraryName = "SampleLibrary",
    )

    val rendered: String = render(members)

    assertTrue(
      rendered.contains("extern bool NullableInt_has_value(bool hasValue, out IntPtr error);"),
      "expected has_value DllImport to declare `out IntPtr error`, got:\n$rendered",
    )
    assertTrue(
      rendered.contains("extern int NullableInt_value(bool hasValue, out IntPtr error);"),
      "expected value DllImport to declare `out IntPtr error`, got:\n$rendered",
    )
  }

  @Test
  fun `nullable Int function - wrapper body checks both error outs and returns null when absent`() {
    val members: List<CirMember> = translateNullableFunction(
      cname = "nullableInt",
      csName = "NullableInt",
      kotlinReturnType = "Int",
      params = listOf(CirParameter("hasValue", "bool")),
      libraryName = "SampleLibrary",
    )

    val wrapper: CirMethod = members.filterIsInstance<CirMethod>().single()
    assertEquals("int?", wrapper.returnType)

    val body: String = wrapper.body
    assertTrue(body.contains("NullableInt_has_value(hasValue, out IntPtr __nuget_hasValueError)"))
    assertTrue(body.contains("NullableInt_value(hasValue, out IntPtr __nuget_valueError)"))
    // Two crossings, two synchronous error checks.
    assertEquals(2, Regex("NugetErrorNative\\.BuildException").findAll(body).count())
    assertTrue(body.contains("if (!__nuget_hasValue) return null;"))
    // Regression: the local names must not collide with a Kotlin parameter named `hasValue`.
    assertTrue(body.contains("bool __nuget_hasValue ="))
  }

  @Test
  fun `nullable String function - both native imports carry a sync error out-param`() {
    val members: List<CirMember> = translateNullableFunction(
      cname = "nullableString",
      csName = "NullableString",
      kotlinReturnType = "String",
      params = listOf(CirParameter("hasValue", "bool")),
      libraryName = "SampleLibrary",
    )

    val hasValueImport: CirDllImport = members.filterIsInstance<CirDllImport>()
      .single { it.name == "NullableString_has_value" }
    val valueImport: CirDllImport = members.filterIsInstance<CirDllImport>()
      .single { it.name == "NullableString_value" }

    assertTrue(
      hasValueImport.hasSyncErrorOut,
      "NullableString_has_value must declare a sync error out-param",
    )
    assertTrue(
      valueImport.hasSyncErrorOut,
      "NullableString_value must declare a sync error out-param",
    )
  }

  @Test
  fun `nullable String function - wrapper body checks both error outs and marshals the string`() {
    val members: List<CirMember> = translateNullableFunction(
      cname = "nullableString",
      csName = "NullableString",
      kotlinReturnType = "String",
      params = listOf(CirParameter("hasValue", "bool")),
      libraryName = "SampleLibrary",
    )

    val wrapper: CirMethod = members.filterIsInstance<CirMethod>().single()
    assertEquals("string?", wrapper.returnType)

    val body: String = wrapper.body
    assertTrue(
      body.contains("NullableString_has_value(hasValue, out IntPtr __nuget_hasValueError)"),
    )
    assertTrue(body.contains("NullableString_value(hasValue, out IntPtr __nuget_valueError)"))
    assertEquals(2, Regex("NugetErrorNative\\.BuildException").findAll(body).count())
    assertTrue(body.contains("Marshal.PtrToStringUTF8(__nuget_nativeResult)"))
  }

  @Test
  fun `nullable function with no params - error out-param is the sole native argument`() {
    val members: List<CirMember> = translateNullableFunction(
      cname = "maybeAnswer",
      csName = "MaybeAnswer",
      kotlinReturnType = "Int",
      params = emptyList(),
      libraryName = "SampleLibrary",
    )

    val rendered: String = render(members)

    assertTrue(rendered.contains("extern bool MaybeAnswer_has_value(out IntPtr error);"))
    assertTrue(rendered.contains("extern int MaybeAnswer_value(out IntPtr error);"))
  }
}
