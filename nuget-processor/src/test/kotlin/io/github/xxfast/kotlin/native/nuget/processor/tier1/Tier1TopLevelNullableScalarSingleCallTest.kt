package io.github.xxfast.kotlin.native.nuget.processor.tier1

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-170: a top-level function returning a nullable scalar takes ADR-061's single-call `valueOut`
 * route, like a member, `object` or companion function, instead of ADR-002's `_has_value` +
 * `_value` pair. The pair never rendered the C# prelude its call arguments named (`itemsHandle`,
 * `fCtx`, ...), so any prelude-bearing parameter produced non-compiling C#, and it ran the Kotlin
 * function twice.
 */
class Tier1TopLevelNullableScalarSingleCallTest {

  private val source: String =
    """
    package tier1.toplevelsinglecall

    import kotlin.time.Duration
    import kotlin.time.Instant

    enum class Mood { CALM, ZOOMIES }

    @JvmInline
    value class Dose(val mg: Double)

    interface Greeter {
      fun greet(): String
    }

    fun plain(): Int? = null

    fun countTags(items: List<String>): Int? = if (items.isEmpty()) null else items.size

    fun maybeTags(items: List<Int>?): Int? = items?.size

    fun mapCount(entries: Map<String, Int>): Long? = entries.size.toLong()

    fun setCount(ids: Set<Int>): Double? = ids.size.toDouble()

    fun byteCount(bytes: ByteArray): Int? = bytes.size

    fun greetLen(g: Greeter): Int? = g.greet().length

    fun viaCallback(f: (Int) -> Int): Int? = f(1)

    fun tagsWithDefault(items: List<String>, extra: Int = 2): Int? = items.size + extra

    fun initial(items: List<String>): Char? = items.firstOrNull()?.firstOrNull()

    fun lastSeen(items: List<String>): Instant? = null

    fun napLength(items: List<String>): Duration? = null

    fun mood(items: List<String>): Mood? = null

    fun dose(items: List<String>): Dose? = null

    fun anyTags(items: List<String>): Boolean? = null
    """.trimIndent()

  private val result: Tier1Result by lazy { Tier1Harness.run(source) }

  @Test
  fun `a top-level Int return emits exactly one export and no two-call pair`() {
    assertTrue(result.compiledClean, "expected clean compile; got: ${result.compileErrors}")
    val kotlin: String = result.generated
    val plainExports: List<String> =
      Regex("""@CName\("library_tier1_toplevelsinglecall__plain[^"]*"\)""")
        .findAll(kotlin).map { match -> match.value }.toList()
    assertEquals(
      listOf("""@CName("library_tier1_toplevelsinglecall__plain")"""),
      plainExports,
    )
    assertFalse("_has_value" in kotlin, "no top-level function may keep the two-call pair")
    // Kotlin runs once per C# call: one invocation, into `result`, never a second `f(...)!!`.
    assertEquals(1, Regex("""tier1\.toplevelsinglecall\.countTags\(""").findAll(kotlin).count())
    assertEquals(1, Regex("""tier1\.toplevelsinglecall\.viaCallback\(""").findAll(kotlin).count())

    val cs: String = result.generatedCSharp
    assertContains(
      cs,
      "private static extern bool Native_Plain(out int valueOut, out IntPtr error);",
    )
    assertFalse("__nuget_hasValue" in cs, "the two-call body is gone")
  }

  @Test
  fun `every prelude-bearing parameter is declared and released around the single call`() {
    val cs: String = result.generatedCSharp
    // Each handle the call names is declared, built inside `try`, and released in `finally`.
    listOf(
      "itemsHandle = NugetMarshal.CreateList(items);" to "NugetListNative.Dispose(itemsHandle);",
      "entriesHandle = NugetMarshal.CreateMap(entries);" to
          "NugetMapNative.Dispose(entriesHandle);",
      "idsHandle = NugetMarshal.CreateSet(ids);" to "NugetSetNative.Dispose(idsHandle);",
      "bytesHandle = NugetMarshal.CreateBytes(bytes);" to "NugetBytesNative.Dispose(bytesHandle);",
      "gHandle = NugetMarshal.HandleOf(g, out gOwned);" to "NugetMarshal.Dispose(gHandle);",
      "fCtx = NugetThunks.RegisterCtx(fNative);" to "NugetThunks.UnregisterCtx(fCtx);",
    ).forEach { (acquire, release) ->
      assertContains(cs, acquire)
      assertContains(cs, release)
    }
    assertContains(
      cs,
      "bool hasValue = Native_CountTags(itemsHandle, out int valueOut, out IntPtr error);",
    )
    assertContains(
      cs,
      "Native_ViaCallback(NugetThunks.NugetIntIntCallbackPtr, fCtx, out int valueOut, " +
          "out IntPtr error);",
    )
    // ADR-164's optional default keeps its HasValue slot ahead of valueOut.
    assertContains(
      cs,
      "private static extern bool Native_TagsWithDefault(IntPtr items, bool extraHasValue, " +
          "int extra, out int valueOut, out IntPtr error);",
    )
  }

  @Test
  fun `every nullable scalar inner type rides the valueOut slot`() {
    // `Long?` / `Instant?` / `Duration?` write through `LongVar`, which the Tier 1 stub now
    // carries.
    assertTrue(result.compiledClean, "expected clean compile; got: ${result.compileErrors}")
    assertContains(
      result.generated,
      "valueOut.reinterpret<LongVar>().pointed.value = result.toDotNetTicks()",
    )
    val cs: String = result.generatedCSharp
    assertContains(
      cs,
      "private static extern bool Native_MapCount(IntPtr entries, out long valueOut, " +
          "out IntPtr error);",
    )
    assertContains(cs, "return hasValue ? (char)valueOut : (char?)null;")
    assertContains(
      cs,
      "return hasValue ? new global::System.DateTimeOffset(valueOut, " +
          "global::System.TimeSpan.Zero) : (global::System.DateTimeOffset?)null;",
    )
    assertContains(
      cs,
      "return hasValue ? new global::System.TimeSpan(valueOut) : (global::System.TimeSpan?)null;",
    )
    assertContains(
      cs,
      "return hasValue ? (global::Interop.Mood)valueOut : (global::Interop.Mood?)null;",
    )
    assertContains(
      cs,
      "return hasValue ? new global::Interop.Dose(valueOut) : (global::Interop.Dose?)null;",
    )
    assertContains(cs, "[MarshalAs(UnmanagedType.I1)] out bool valueOut")
  }

  /** ADR-095's overload numbering and ADR-150's doc comment come with the route. */
  @Test
  fun `overloaded and documented top-level nullable scalars keep their suffix and doc`() {
    val overloads: Tier1Result = Tier1Harness.run(
      """
      package tier1.toplevelsinglecalloverload

      /** Looks up a cat's age by name. */
      fun age(name: String): Int? = name.length

      /** Looks up a cat's age by tag number. */
      fun age(tag: Int): Int? = tag
      """.trimIndent(),
    )
    assertTrue(overloads.compiledClean, "expected clean compile; got: ${overloads.compileErrors}")
    val cs: String = overloads.generatedCSharp
    assertContains(cs, "public static int? Age(string name)")
    assertContains(cs, "public static int? Age(int tag)")
    assertContains(cs, "/// <summary>Looks up a cat's age by name.</summary>")
    assertContains(cs, "/// <summary>Looks up a cat's age by tag number.</summary>")
    val natives: Set<String> = Regex("""private static extern bool (Native_Age\w*)\(""")
      .findAll(cs).map { match -> match.groupValues[1] }.toSet()
    assertEquals(setOf("Native_Age", "Native_Age_2"), natives)
    assertFalse("_has_value" in overloads.generated)
  }

  /**
   * A bound C# interface parameter takes the same prelude chain. Only the C# half is pinned: the
   * admitted Kotlin half calls the reverse pipeline's `nugetIFeedable*` helpers, which a Tier 1
   * fixture does not have (see [Tier1BoundInterfacePositionTest]).
   */
  @Test
  fun `a bound interface parameter declares its handle around the single call`() {
    val manifest: File = Files.createTempFile("nuget-bound-types-", ".json").toFile()
    manifest.deleteOnExit()
    manifest.writeText(
      """
      {
        "interfaces": [
          { "kotlinName": "bound.menagerie.IFeedable", "csharpName": "Test.Menagerie.IFeedable", "implementable": true }
        ]
      }
      """.trimIndent()
    )
    val bound: Tier1Result = Tier1Harness.run(
      sources = mapOf(
        "Bound.kt" to """
          package bound.menagerie

          interface IFeedable {
            fun describe(): String
          }
        """.trimIndent(),
        "Fixture.kt" to """
          package tier1.toplevelsinglecallbound

          import bound.menagerie.IFeedable

          fun portions(feedable: IFeedable): Int? = feedable.describe().length
        """.trimIndent(),
      ),
      processorOptions = mapOf("nuget.boundTypesManifest" to manifest.absolutePath),
    )
    val cs: String = bound.generatedCSharp
    val body: String = cs.substringAfter("public static int? Portions(")
      .substringBefore("\n        }\n")
    // The same GCHandle prelude the member route renders (ADR-088), declared before the call.
    assertContains(body, "IntPtr feedableHandle = GCHandle.ToIntPtr(GCHandle.Alloc(feedable));")
    assertContains(
      body,
      "bool hasValue = Native_Portions(feedableHandle, out int valueOut, out IntPtr error);",
    )
    assertFalse("_has_value" in cs)
  }
}
