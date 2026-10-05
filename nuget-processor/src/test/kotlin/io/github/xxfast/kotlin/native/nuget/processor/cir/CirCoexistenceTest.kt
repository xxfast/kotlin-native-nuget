package io.github.xxfast.kotlin.native.nuget.processor.cir

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CirCoexistenceTest {
  private fun render(): String = CirRenderer().render(
    CirFile(namespaces = listOf(CirNamespace("Publisher.One", listOf(
      CirMarshalHelper("publisher_one"), CirErrorHelper("publisher_one"),
    )))),
  )

  @Test
  fun `handle contracts belong to publisher namespace`() {
    val source = render()
    assertTrue(source.indexOf("namespace Publisher.One") < source.indexOf("internal interface INugetHandle"))
    assertTrue(source.indexOf("namespace Publisher.One") < source.indexOf("internal readonly struct NugetHandleTag"))
  }

  @Test
  fun `shared contract types are referenced without duplicate source definitions`() {
    val source = render()
    assertContains(source, "using Kotlin.Native.Interop;")
    assertFalse(source.contains("public interface IKotlinException"))
    assertFalse(source.contains("public class KotlinException"))
    assertFalse(source.contains("public sealed class KotlinArgumentException"))
    assertFalse(source.contains("public readonly struct Optional<T>"))
    assertFalse(source.contains("public readonly struct KotlinOptional<T>"))
  }

  @Test
  fun `mapped and unmapped errors are built by the shared contract's factory`() {
    // ADR-203: the row switch (fallback included) lives in Kotlin.Native.Interop, so two
    // publishers in one process build every exception through the same contract code.
    val source = render()
    assertContains(
      source,
      "global::Kotlin.Native.Interop.KotlinException.CreateMapped(" +
        "kotlinType, mappedType, msg, stackTrace, inner)",
    )
    assertFalse(source.contains("new global::Kotlin.Native.Interop.Kotlin"), source)
    assertFalse(source.contains("KotlinException.Create("), source)
  }

  @Test
  fun `handle contract root does not depend on namespace encounter order`() {
    val source = CirRenderer().render(CirFile(
      namespaces = listOf(CirNamespace("Publisher.One.Models", emptyList()),
        CirNamespace("Publisher.One", listOf(CirMarshalHelper("one")))),
      rootNamespace = "Publisher.One",
    ))
    assertFalse(source.substringAfter("namespace Publisher.One.Models")
      .substringBefore("namespace Publisher.One\n").contains("INugetHandle"))
    assertContains(source.substringAfter("namespace Publisher.One\n"),
      "internal interface INugetHandle")
  }
}
