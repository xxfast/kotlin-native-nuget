package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.nonNullStringOrThrow
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A C# `record struct` always has a `default`, and over a String or an exported class its
 * underlying is then null: a value Kotlin could never have built. Wherever such a struct is
 * unwrapped for a non-null Kotlin slot, the generated C# refuses it in the unwrap itself
 * (`(tag.Id ?? throw new ArgumentException(...))`), so nothing reaches Kotlin. One helper spells
 * the guard for every route: constructor and method parameters, top-level functions, property
 * setters, extension receivers, the struct's own members, the ADR-171 box, collection components
 * (on the ordinary and the legacy suspend / Flow routes alike) and the ADR-071 flow write.
 *
 * The nullable `V?` spelling keeps null as null and refuses only a present default. A primitive
 * or enum underlying has a legitimate default, and a nullable underlying (`String?`) a legitimate
 * null, so neither is guarded.
 */
class Tier1ValueClassDefaultGuardTest {

  private val source: String = """
    package tier1.vcdefault

    import kotlinx.coroutines.flow.Flow
    import kotlinx.coroutines.flow.MutableStateFlow
    import kotlinx.coroutines.flow.emptyFlow

    class Cat(val name: String)

    @JvmInline
    value class Tag(val id: String) {
      val length: Int get() = id.length
      fun shout(): String = id.uppercase()
    }

    @JvmInline
    value class Collar(val cat: Cat) {
      fun label(): String = cat.name
    }

    @JvmInline
    value class Naps(val count: Int)

    @JvmInline
    value class MaybeId(val value: String?) {
      fun orAnonymous(): String = value ?: "anonymous"
    }

    fun Tag.padded(width: Int): String = id.padEnd(width)

    fun topLevel(tag: Tag, collar: Collar): String = tag.id + collar.cat.name

    class Tracker(tag: Tag) {
      var tag: Tag = tag
      var collar: Collar = Collar(Cat("x"))
      var spareTag: Tag? = null
      var spareCollar: Collar? = null
      var naps: Naps = Naps(1)
      var plain: String = "a"
      val text: MutableStateFlow<String> = MutableStateFlow("a")
      val chip: MutableStateFlow<Tag> = MutableStateFlow(tag)
      fun retag(tag: Tag, collar: Collar, spare: Tag?, spareCollar: Collar?) {}
      fun retagAll(tags: List<Tag>, collars: List<Collar>, spares: List<Tag?>) {}
      fun retagNamed(byName: Map<String, Tag>) {}
      suspend fun retagLater(tags: List<Tag>): Int = tags.size
      fun watch(tags: List<Tag>): Flow<Int> = emptyFlow()
    }

    data class Passport(val tag: Tag, val spare: Tag?, val spareCollar: Collar?)
    """.trimIndent()

  private val result: Tier1Result by lazy {
    Tier1Harness.run(source, libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore))
  }

  private val csharp: String by lazy { result.generatedCSharp }

  /** The guarded unwrap of [struct]'s [property]; [parameter] is the `nameof` target, if any. */
  private fun guarded(struct: String, property: String, type: String, parameter: String?): String {
    val name: String = parameter?.let { ", nameof($it)" }.orEmpty()
    return "($struct.$property ?? throw new ArgumentException(" +
      "\"default($type) carries no $property; construct a $type instead\"$name))"
  }

  private fun tag(struct: String, parameter: String? = struct): String =
    guarded(struct, "Id", "Tag", parameter)

  private fun collar(struct: String, parameter: String? = struct): String =
    guarded(struct, "Cat", "Collar", parameter)

  private fun assertClean() {
    assertEquals(emptyList(), result.kspErrors, "KSP errors (an ADR-055 mismatch lands here)")
    assertTrue(result.compiledClean, "generated Kotlin must compile; got: ${result.compileErrors}")
  }

  @Test
  fun `a method, constructor and top-level parameter refuse a default`() {
    assertClean()
    assertContains(
      csharp,
      "Native_Retag(_handle, ${tag("tag")}, ${collar("collar")}._handle, " +
        "spare.HasValue ? ${tag("spare.Value", "spare")} : null, " +
        "spareCollar.HasValue ? ${collar("spareCollar.Value", "spareCollar")}._handle " +
        ": NugetKotlinHandle.Null, out IntPtr error);",
    )
    assertContains(csharp, "Native_Create(${tag("tag")}, out IntPtr error);")
    assertContains(
      csharp,
      "Native_TopLevel(${tag("tag")}, ${collar("collar")}._handle, out IntPtr error);",
    )
  }

  @Test
  fun `a property setter refuses a default, and the nullable setter only a present one`() {
    assertClean()
    assertContains(csharp, "Native_Set_tag(_handle, ${tag("value")}, out IntPtr error);")
    assertContains(
      csharp,
      "Native_Set_collar(_handle, ${collar("value")}._handle, out IntPtr error);",
    )
    assertContains(
      csharp,
      "Native_Set_spareTag(_handle, value.HasValue ? ${tag("value.Value", "value")} : null, " +
        "out IntPtr error);",
    )
    assertContains(
      csharp,
      "Native_Set_spareCollar(_handle, value.HasValue ? " +
        "${collar("value.Value", "value")}._handle : NugetKotlinHandle.Null, out IntPtr error);",
    )
  }

  @Test
  fun `a collection component refuses a default on the ordinary and the legacy routes`() {
    assertClean()
    val element: String = tag("x", parameter = null)
    assertContains(csharp, "Select(tags, x => $element));")
    assertContains(csharp, "Select(collars, x => ${collar("x", parameter = null)}));")
    assertContains(
      csharp,
      "Select(spares, x => x.HasValue ? ${tag("x.Value", parameter = null)} : null));",
    )
    assertContains(
      csharp,
      "new KeyValuePair<string, string>(${nonNullStringOrThrow("x.Key", "byName")}, " +
        "${tag("x.Value", parameter = null)})",
    )
    // The suspend and the Flow-returning member build the same list; all three sites are guarded.
    assertEquals(
      3,
      csharp.lines().count { line -> "Select(tags, x => $element)" in line },
      "expected RetagAll, RetagLaterAsync and Watch guarded:\n" +
        csharp.lines().filter { "Select(tags" in it }.joinToString("\n"),
    )
  }

  @Test
  fun `the struct's own members, an extension receiver and the box refuse a default`() {
    assertClean()
    assertContains(csharp, "Native_GetLength(${tag("this", parameter = null)})")
    assertContains(csharp, "Native_Shout(${tag("this", parameter = null)})")
    assertContains(csharp, "Native_Label(${collar("this", parameter = null)}._handle)")
    assertContains(csharp, "Native_Padded(${tag("receiver")}, width, out IntPtr error);")
    assertContains(csharp, "Native_NugetBox(${tag("unboxed")}, out IntPtr error);")
  }

  /**
   * ADR-164: an optional `copy` parameter is read once into a `spareValue` local, and that local
   * is what the wrapper unwraps. The exception still names the PUBLIC parameter, the one the
   * caller wrote.
   */
  @Test
  fun `an optional copy parameter refuses a present default and names the public parameter`() {
    assertClean()
    val copy: String =
      csharp.substringAfter("public Passport Copy(").substringBefore("\n        }")
    assertContains(
      copy,
      "spare.HasValue, spareValue.HasValue ? ${tag("spareValue.Value", "spare")} : null, ",
    )
    assertContains(
      copy,
      "spareCollar.HasValue, spareCollarValue.HasValue ? " +
        "${collar("spareCollarValue.Value", "spareCollar")}._handle : NugetKotlinHandle.Null, ",
    )
    assertFalse(
      Regex("""nameof\(\w+Value\)""").containsMatchIn(csharp),
      "a guard names a public parameter, never the unwrapped local; got:\n$copy",
    )
  }

  @Test
  fun `the MutableStateFlow write shares the same guard`() {
    assertClean()
    assertContains(csharp, "Native_SetChipValue(_handle, ${tag("v")}, out IntPtr error);")
    assertContains(
      csharp,
      "Native_CompareAndSetChipValue(_handle, ${tag("expect")}, ${tag("update")}, " +
        "out IntPtr error), error);",
    )
  }

  /**
   * The same null, without a value class around it: `flow.Value = null!` on a
   * `KotlinMutableStateFlow<string>` hands the export a null string pointer for a non-null Kotlin
   * `String`. The write lambda rejects it with the `ArgumentNullException` a non-null object
   * element already gets, on the setter and on both compare-and-set slots.
   */
  @Test
  fun `a non-null String MutableStateFlow element rejects null before it crosses`() {
    assertClean()
    // The read is the one guard every non-null string crossing shares, not a statement.
    assertContains(
      csharp,
      "Native_SetTextValue(_handle, ${nonNullStringOrThrow("v")}, out IntPtr error);",
    )
    assertContains(
      csharp,
      "Native_CompareAndSetTextValue(_handle, ${nonNullStringOrThrow("expect")}, " +
        "${nonNullStringOrThrow("update")}, out IntPtr error), error);",
    )
  }

  @Test
  fun `a primitive underlying and a nullable underlying are not guarded`() {
    assertClean()
    assertContains(csharp, "Native_Set_naps(_handle, value.Count, out IntPtr error);")
    assertContains(csharp, "Native_OrAnonymous(Value)")
    assertFalse("default(Naps)" in csharp, "a primitive underlying's default is a real value")
    assertFalse("default(MaybeId)" in csharp, "a nullable underlying's null is a real value")
  }

  @Test
  fun `every guarded shape compiles in C#`() {
    assertClean()
    Tier1CSharpCompile.assertCompiles(
      result,
      """
      namespace Consumer
      {
          public static class Probe
          {
              public static void Run(global::Interop.Tracker tracker)
              {
                  tracker.Tag = default;
                  tracker.SpareTag = null;
                  tracker.Retag(default, default, null, null);
                  tracker.RetagAll(
                      new global::Interop.Tag[0], new global::Interop.Collar[0],
                      new global::Interop.Tag?[0]);
              }
          }
      }
      """.trimIndent(),
      allowUnsafe = true,
    )
  }
}
