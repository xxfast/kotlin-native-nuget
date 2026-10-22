package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A value class whose underlying can itself be null, at ordinary positions.
 *
 * Three shipped defects, one cell each:
 *  - `value class TreatCount(val count: Int?)`: its `_create` export took the has-value result wire
 *    in Kotlin while the C# import said a plain `int`, so the generation-time ABI contract check
 *    failed the whole module.
 *  - `List<Nick?>` over `value class Nick(val name: String?)`: as a parameter the Kotlin emitter had
 *    no element cast and crashed the generator; as a result the element projected to its
 *    underlying, so `Nick(null)` and `null` both crossed as the null pointer. The element now
 *    crosses as the value class's own box handle: the null pointer is `null`, a live handle is the
 *    boxed value, so the two stay apart. `List<Sticker<String?>?>` is the same wire.
 *  - A closed `Sticker<String?>` parameter passed `sticker.Value` (a `string?`) to a native import
 *    declared over a non-null `string` (CS8604 under a strict compile).
 *
 * `@JvmInline` is only for the JVM harness (ADR-060's Tier 1 constraint).
 *
 * Oreo (black, white in the middle) leaves gaps in the album. Mylo (brown and creamy) fills them
 * with blank stickers, which are not the same thing as no sticker.
 */
class Tier1NullableUnderlyingValueClassTest {

  private val result: Tier1Result by lazy {
    Tier1Harness.run(
      """
      package tier1.gaps

      import kotlin.jvm.JvmInline

      @JvmInline
      value class Sticker<T>(val value: T)

      @JvmInline
      value class Nick(val name: String?)

      @JvmInline
      value class TreatCount(val count: Int?)

      fun nicknames(): List<Nick?> = listOf(null, Nick(null), Nick("Oreo"))
      fun countNicks(nicks: List<Nick?>): String = "${'$'}{nicks.count { it == null }}"
      fun nickPages(pages: Map<String, Nick?>): Map<String, Nick?> = pages
      fun allNicks(nicks: List<Nick>): List<Nick> = nicks

      fun blankLabel(): Sticker<String?> = Sticker(null)
      fun labelOr(sticker: Sticker<String?>): String = sticker.value ?: "blank"
      fun nickOrAnon(nick: Nick): String = nick.name ?: "anon"
      fun albumPage(): List<Sticker<String?>?> = listOf(null, Sticker(null), Sticker("Oreo"))
      fun fillPage(page: List<Sticker<String?>?>): Int = page.size

      class GapAlbum(val name: String) {
        val roll: List<Nick?> get() = listOf(Nick(null), null)
      }
      """.trimIndent(),
      processorOptions = mapOf("nuget.rootPackage" to "tier1"),
    )
  }

  private val cs: String get() = result.generatedCSharp

  @Test
  fun `the fixture plans, compiles and its generated C# builds strictly`() {
    assertTrue(result.kspSucceeded, "expected KSP to succeed; got: ${result.kspErrors}")
    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")
    assertTrue(
      result.kspWarnings.none { it.contains("tier1.gaps.") },
      "expected nothing to skip; kspWarnings=${result.kspWarnings}",
    )
    Tier1CSharpCompile.assertCompiles(result, "public static class Consumer { }")
  }

  @Test
  fun `a value class over a nullable primitive constructs on the has-value wire`() {
    assertContains(cs, "private static extern bool Native_Create(")
    assertContains(cs, "private static int? CreateChecked(int? count)")
    assertContains(cs, "bool hasValue = Native_Create(")
    assertContains(cs, "out int valueOut, out IntPtr error);")
    assertContains(cs, "return hasValue ? valueOut : null;")
  }

  @Test
  fun `a nullable element over a nullable underlying crosses in as its own box handle`() {
    assertContains(
      result.generated,
      "tier1.gaps.countNicks(nicks.asStableRef<MutableList<Any?>>().get().map " +
          "{ (it as tier1.gaps.Nick?) })",
    )
    assertContains(result.generated, "(v as tier1.gaps.Nick?)")
    assertContains(
      result.generated,
      "map { (it as tier1.gaps.Sticker<*>?)?.let { v -> " +
          "tier1.gaps.Sticker(v.value as kotlin.String?) } }",
    )
    // The C# write side hands the struct to `Wrap<T>`, which mints the box; no projection.
    assertContains(cs, "nicksHandle = NugetMarshal.CreateList(nicks);")
    assertFalse(cs.contains("x?.Name"), "the element must not project to its underlying")
  }

  @Test
  fun `a nullable element over a nullable underlying crosses out as its own box handle`() {
    assertContains(result.generated, "NugetHandles.retain(tier1.gaps.nicknames())")
    assertContains(result.generated, "NugetHandles.retain(tier1.gaps.albumPage())")
    assertContains(
      cs,
      "? (global::Interop.Gaps.Nick?)null : global::Interop.Gaps.Nick.NugetUnbox(elementHandle1)",
    )
    assertContains(
      cs,
      "? (global::Interop.Gaps.Sticker<string?>?)null : " +
          "global::Interop.Gaps.Sticker<string?>.NugetUnbox(elementHandle1)",
    )
  }

  @Test
  fun `a non-null element over a nullable underlying projects to its nullable underlying`() {
    assertContains(result.generated, "tier1.gaps.Nick(it as kotlin.String?)")
  }

  @Test
  fun `a closed nullable underlying crosses on the nullable string wire`() {
    assertContains(cs, "Native_LabelOr([MarshalAs(UnmanagedType.LPUTF8Str)] string? sticker")
    assertContains(cs, "Native_NickOrAnon([MarshalAs(UnmanagedType.LPUTF8Str)] string? nick")
  }
}
