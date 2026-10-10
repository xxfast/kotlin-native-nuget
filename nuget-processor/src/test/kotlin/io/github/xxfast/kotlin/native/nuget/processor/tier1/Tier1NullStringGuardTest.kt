package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * C# can always pass `null!` where a `string` is declared, and a null string pointer in a non-null
 * Kotlin `String` slot is an access violation inside the export. Wherever the generated C# fills
 * a non-null Kotlin `String`, the argument is read through one guarded spelling,
 * `(text ?? throw new ArgumentNullException(nameof(text)))`, so nothing crosses. It is the string
 * twin of the value-class default guard (`valueClassUnderlyingOrThrow`), an expression for the
 * same reason: one spelling serves an argument list, a `Select` lambda, an expression-bodied
 * member and a write lambda.
 *
 * A nullable `String?` slot keeps passing its value straight through. The proof that every
 * guarded shape is legal C# is a real `dotnet build`.
 */
class Tier1NullStringGuardTest {

  private val source: String = """
    package tier1.nullstring

    import kotlinx.coroutines.flow.Flow
    import kotlinx.coroutines.flow.MutableSharedFlow
    import kotlinx.coroutines.flow.MutableStateFlow
    import kotlinx.coroutines.flow.flowOf

    @JvmInline
    value class Tag(val id: String) {
      fun matches(prefix: String): Boolean = id.startsWith(prefix)
    }

    interface Stamper {
      fun press(text: String): String
    }

    class InkStamper(val ink: String) : Stamper {
      override fun press(text: String): String = ink + text
    }

    sealed class Seal {
      class Wax(val colour: String) : Seal() {
        fun press(text: String): String = colour + text
      }
    }

    object Ledger {
      fun entry(text: String): Int = text.length
    }

    class Desk(val owner: String) {
      var label: String = "unlabelled"
      var note: String? = null
      val title: MutableStateFlow<String> = MutableStateFlow("untitled")
      val notices: MutableSharedFlow<String> = MutableSharedFlow(replay = 1)
      fun stamp(text: String): String = text
      fun stampOrBlank(text: String?): String = text ?: ""
      fun stampTwo(first: String, second: String = "x"): String = first + second
      fun stampAll(texts: List<String>): Int = texts.size
      fun stampMaybe(texts: List<String?>): Int = texts.size
      fun stampUnique(texts: Set<String>): Int = texts.size
      fun stampKeyed(byKey: Map<String, String>): Int = byKey.size
      fun stampNested(groups: List<List<String>>): Int = groups.size
      suspend fun stampLater(text: String): String = text
      fun stampStream(text: String): Flow<String> = flowOf(text)
      fun stampWith(make: () -> String): String = make()
      fun stampThrough(stamper: Stamper, text: String): String = stamper.press(text)

      companion object {
        fun measure(text: String): Int = text.length
      }
    }

    fun stampLength(text: String): Int = text.length

    fun String.stamped(): String = "[" + this + "]"

    val String.stampWidth: Int get() = length + 2
    """.trimIndent()

  private val result: Tier1Result by lazy {
    Tier1Harness.run(source, libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore))
  }

  private val csharp: String by lazy { result.generatedCSharp }

  /** The guarded read of [name]; [parameter] is what the caller wrote, the `nameof` target. */
  private fun guarded(name: String, parameter: String = name): String =
    "($name ?? throw new ArgumentNullException(nameof($parameter)))"

  private fun assertClean() {
    assertEquals(emptyList(), result.kspErrors, "KSP errors (an ADR-055 mismatch lands here)")
    assertTrue(result.compiledClean, "generated Kotlin must compile; got: ${result.compileErrors}")
  }

  @Test
  fun `a constructor, method, companion, object and top-level parameter refuse null`() {
    assertClean()
    assertContains(csharp, "Native_Create(${guarded("owner")}, out IntPtr error);")
    assertContains(csharp, "Native_Stamp(_handle, ${guarded("text")}, out IntPtr error);")
    assertContains(csharp, "Native_Companion_Measure(${guarded("text")}, out IntPtr error);")
    assertContains(csharp, "Native_Entry(${guarded("text")}, out IntPtr error);")
    assertContains(csharp, "Native_StampLength(${guarded("text")}, out IntPtr error);")
  }

  @Test
  fun `a property setter refuses null and a nullable slot passes it through`() {
    assertClean()
    assertContains(csharp, "Native_Set_label(_handle, ${guarded("value")}, out IntPtr error);")
    assertContains(csharp, "Native_Set_note(_handle, value, out IntPtr error);")
    assertContains(csharp, "Native_StampOrBlank(_handle, text, out IntPtr error);")
  }

  @Test
  fun `an extension receiver, a sealed arm, an interface and a value-class member refuse null`() {
    assertClean()
    assertContains(csharp, "Native_Stamped(${guarded("receiver")}, out IntPtr error);")
    assertContains(csharp, "Native_StringGetStampWidth(${guarded("receiver")}, out IntPtr error);")
    assertContains(csharp, "Native_Create(${guarded("colour")}, out IntPtr error);")
    assertEquals(
      3,
      csharp.lines().count { "Native_Press(_handle, ${guarded("text")}, out IntPtr error)" in it },
      "expected the sealed arm, the implementing class and the interface backing wrapper guarded",
    )
    assertContains(csharp, "${guarded("prefix")}")
    assertContains(csharp, "Native_Create(${guarded("id")}, out IntPtr error);")
  }

  @Test
  fun `a collection component refuses a null element and names the collection`() {
    assertClean()
    assertContains(csharp, "Select(texts, x => ${guarded("x", "texts")})")
    assertContains(
      csharp,
      "new KeyValuePair<string, string>(${guarded("x.Key", "byKey")}, " +
        "${guarded("x.Value", "byKey")})",
    )
    assertContains(csharp, "Select(x, x1 => ${guarded("x1", "groups")})")
    // A nullable element is a legitimate null: `List<String?>` is handed over as it is, with no
    // per-element read. `stampAll` and `stampMaybe` both name their parameter `texts`, so the
    // guarded and the bare prelude each appear exactly once.
    assertEquals(
      1,
      csharp.lines().count { "textsHandle = NugetMarshal.CreateList(texts);" in it },
      "expected the nullable-element list passed bare",
    )
    assertEquals(
      1,
      csharp.lines().count {
        "textsHandle = NugetMarshal.CreateList(" in it && guarded("x", "texts") in it
      },
      "expected the non-null-element list read through the guard",
    )
  }

  @Test
  fun `a suspend and a Flow-returning member parameter refuse null`() {
    assertClean()
    assertTrue(
      csharp.lines().any { "Native_StampLaterAsync(" in it && guarded("text") in it },
      "expected the suspend member's string argument guarded",
    )
    assertTrue(
      csharp.lines().any { "Native_StampStreamCollect(" in it && guarded("text") in it },
      "expected the Flow-returning member's string argument guarded",
    )
  }

  @Test
  fun `the flow element writes share the same guard instead of a statement`() {
    assertClean()
    assertContains(csharp, "Native_SetTitleValue(_handle, ${guarded("v")}, out IntPtr error);")
    assertContains(
      csharp,
      "Native_CompareAndSetTitleValue(_handle, ${guarded("expect")}, ${guarded("update")}, " +
        "out IntPtr error), error);",
    )
    assertTrue(
      csharp.lines().any { "TryEmit" in it && guarded("v") in it } ||
        csharp.lines().any { "Notices" in it && guarded("v") in it },
      "expected the MutableSharedFlow emit to read its string through the guard",
    )
    assertFalse(
      "if (v is null) throw new ArgumentNullException(nameof(v));" in
        csharp.substringAfter("KotlinMutableStateFlow<string> Title")
          .substringBefore("KotlinFlow<string> StampStream"),
      "the string element's statement-form guard is replaced by the shared expression",
    )
  }

  @Test
  fun `a defaulted string parameter keeps its default and guards a passed null`() {
    assertClean()
    // `second: String = "x"` is public `string? second = null`: null means "use the default", so
    // it passes bare while the required `first` beside it is guarded.
    assertContains(csharp, "public string StampTwo(string first, string? second = null)")
    assertContains(
      csharp,
      "Native_StampTwo(_handle, ${guarded("first")}, second, out IntPtr error);",
    )
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
              public static void Run(global::Interop.Desk desk)
              {
                  desk.Label = "a";
                  desk.Note = null;
                  desk.Stamp("a");
                  desk.StampOrBlank(null);
                  desk.StampAll(new[] { "a" });
              }
          }
      }
      """.trimIndent(),
      allowUnsafe = true,
    )
  }
}
