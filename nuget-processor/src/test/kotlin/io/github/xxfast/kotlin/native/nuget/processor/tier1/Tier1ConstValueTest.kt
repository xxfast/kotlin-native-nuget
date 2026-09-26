package io.github.xxfast.kotlin.native.nuget.processor.tier1

import io.github.xxfast.kotlin.native.nuget.processor.cir.KotlinConstValue
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import java.io.File
import java.lang.reflect.Method
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ROADMAP line 25: a `const val`'s C# value is the compiler's evaluated constant, read through
 * KSP2's internal analysis symbol ([KotlinConstValue]), never a regex over the source text.
 *
 * The regex captured to end of line, so a one-line `object`/companion body, a `;`-separated pair
 * and a trailing `//` comment emitted illegal C#, and underscores inside strings, templates, `\$`,
 * shifts, `Int.MIN_VALUE` and expressions over other consts rendered silently wrong values. A
 * dependency const (no source file) was dropped with no diagnostic.
 */
class Tier1ConstValueTest {

  /**
   * The fail-loud cell for a KSP bump: the reflective route matches KSP2's module-mangled internal
   * getter by name. When a KSP release renames or removes it, every const becomes a
   * `SKIPPED_UNREADABLE_CONST_VALUE`; this cell names the breakage instead.
   */
  @Test
  fun `KSP2 still exposes the analysis symbol accessor the const reader reflects on`() {
    val impl: Class<*> =
      Class.forName("com.google.devtools.ksp.impl.symbol.kotlin.KSPropertyDeclarationImpl")
    val prefix: String = KotlinConstValue.SYMBOL_ACCESSOR_PREFIX
    val accessor: Method? = generateSequence(impl) { it.superclass }
      .flatMap { it.declaredMethods.asSequence() }
      .firstOrNull { it.parameterCount == 0 && it.name.startsWith(prefix) }
    assertNotNull(
      accessor,
      "KSP's KSPropertyDeclarationImpl no longer has a `$prefix*` getter: KotlinConstValue cannot " +
          "read any const value on this KSP version, so every `const val` would be skipped. Find " +
          "the new path to the KaPropertySymbol's initializer.",
    )
  }

  @Test
  fun `one-line bodies, trailing comments and expressions render the evaluated literal`() {
    val result = Tier1Harness.run(
      """
      package tier1.constshapes

      object OneLine { const val NAME = "x" }

      class Host { companion object { const val DEFAULT_NAME = "x" } }

      object Pair2 { const val A: Int = 1; const val B: Int = 2 }

      object Other { const val CAPACITY: Int = 12 }

      object Tricky {
        const val TRAILING: Int = 5 // five
        const val BLOCK: Int = 6 /* six */
        const val REF: Int = TRAILING + 1
        const val CROSS: Int = Other.CAPACITY * 2
        const val SHIFT: Int = 1 shl 3
        const val UNDERSCORE = "snake_case_value"
        const val TEMPLATE = "v${'$'}UNDERSCORE"
        const val DOLLAR = "cost \${'$'}5"
        const val CONCAT = "a" + "b"
        const val BRACE = "a } b"
        const val URL = "http://x"
        const val WRAPPED: String =
          "next line"
      }
      """.trimIndent(),
    )

    val cs: String = result.generatedCSharp
    assertContains(cs, "public const string Name = \"x\";")
    assertContains(cs, "public const string DefaultName = \"x\";")
    assertContains(cs, "public const int A = 1;")
    assertContains(cs, "public const int B = 2;")
    assertContains(cs, "public const int Trailing = 5;")
    assertContains(cs, "public const int Block = 6;")
    assertContains(cs, "public const int Ref = 6;")
    assertContains(cs, "public const int Cross = 24;")
    assertContains(cs, "public const int Shift = 8;")
    assertContains(cs, "public const string Underscore = \"snake_case_value\";")
    assertContains(cs, "public const string Template = \"vsnake_case_value\";")
    assertContains(cs, "public const string Dollar = \"cost $5\";")
    assertContains(cs, "public const string Concat = \"ab\";")
    assertContains(cs, "public const string Brace = \"a } b\";")
    assertContains(cs, "public const string Url = \"http://x\";")
    assertContains(cs, "public const string Wrapped = \"next line\";")
    assertNoConstSkips(result)
  }

  @Test
  fun `every primitive type renders its C# literal from the declared type`() {
    val result = Tier1Harness.run(
      """
      package tier1.consttypes

      object Values {
        const val BYTE: Byte = -8
        const val UBYTE: UByte = 255u
        const val SHORT: Short = -12
        const val USHORT: UShort = 65535u
        const val UINT: UInt = 4_000_000_000u
        const val LONG: Long = 5_400_000
        const val ULONG: ULong = 18446744073709551615uL
        const val FLOAT: Float = 0.75f
        const val BIG_FLOAT: Float = 1e10f
        const val DOUBLE: Double = 3.14
        const val TINY: Double = 1e-7
        const val HEX: Int = 0xFF_FF
        const val INT_MIN: Int = Int.MIN_VALUE
        const val LONG_MIN: Long = Long.MIN_VALUE
        const val NAN: Float = Float.NaN
        const val D_NAN: Double = Double.NaN
        const val POS_INF: Float = Float.POSITIVE_INFINITY
        const val NEG_INF: Double = Double.NEGATIVE_INFINITY
        const val TRUE: Boolean = true
      }
      """.trimIndent(),
    )

    val cs: String = result.generatedCSharp
    assertContains(cs, "public const sbyte Byte = -8;")
    assertContains(cs, "public const byte Ubyte = 255;")
    assertContains(cs, "public const short Short = -12;")
    assertContains(cs, "public const ushort Ushort = 65535;")
    // An unwrap to a signed Int would print -294967296.
    assertContains(cs, "public const uint Uint = 4000000000U;")
    assertContains(cs, "public const long Long = 5400000L;")
    // An unwrap to a signed Long would print -1.
    assertContains(cs, "public const ulong Ulong = 18446744073709551615UL;")
    assertContains(cs, "public const float Float = 0.75f;")
    assertContains(cs, "public const float BigFloat = 1.0E10f;")
    assertContains(cs, "public const double Double = 3.14;")
    assertContains(cs, "public const double Tiny = 1.0E-7;")
    assertContains(cs, "public const int Hex = 65535;")
    assertContains(cs, "public const int IntMin = -2147483648;")
    assertContains(cs, "public const long LongMin = -9223372036854775808L;")
    assertContains(cs, "public const float Nan = float.NaN;")
    assertContains(cs, "public const double DNan = double.NaN;")
    assertContains(cs, "public const float PosInf = float.PositiveInfinity;")
    assertContains(cs, "public const double NegInf = double.NegativeInfinity;")
    assertContains(cs, "public const bool True = true;")
    assertNoConstSkips(result)
  }

  @Test
  fun `chars and strings are re-escaped for a regular C# literal`() {
    val result = Tier1Harness.run(
      """
      package tier1.constescapes

      object Text {
        const val PLAIN: Char = 'O'
        const val NEWLINE: Char = '\n'
        const val APOSTROPHE: Char = '\''
        const val BACKSLASH: Char = '\\'
        const val QUOTE_CHAR: Char = '"'
        const val QUOTED = "said \"mrrp\" \\ twice"
        const val CONTROL = "tab\tnul\u0000bell\u0007"
        const val SEPARATOR = "a\u2028b"
        const val EMOJI = "cat \uD83D\uDC31"
        const val SINGLE_RAW = ""${'"'}one"line""${'"'}
      }
      """.trimIndent(),
    )

    val cs: String = result.generatedCSharp
    assertContains(cs, "public const char Plain = 'O';")
    assertContains(cs, "public const char Newline = '\\n';")
    assertContains(cs, "public const char Apostrophe = '\\'';")
    assertContains(cs, "public const char Backslash = '\\\\';")
    assertContains(cs, "public const char QuoteChar = '\"';")
    assertContains(cs, "public const string Quoted = \"said \\\"mrrp\\\" \\\\ twice\";")
    assertContains(cs, "public const string Control = \"tab\\tnul\\0bell\\u0007\";")
    assertContains(cs, "public const string Separator = \"a\\u2028b\";")
    assertContains(cs, "public const string Emoji = \"cat \\uD83D\\uDC31\";")
    assertContains(cs, "public const string SingleRaw = \"one\\\"line\";")
    assertNoConstSkips(result)
  }

  /**
   * A multi-line raw string's line break is part of its value. Pinned for BOTH working-copy line
   * endings, because the fixture is CRLF on a Windows checkout and LF on CI: the Kotlin front end
   * normalises a CRLF source to `\n` before it evaluates the literal, so both render `\n`.
   */
  @Test
  fun `a multi-line raw string keeps its line break as an escape, for LF and CRLF sources`() {
    listOf("\n", "\r\n").forEach { newline ->
      val source: String = listOf(
        "package tier1.constraw",
        "",
        "object Roll {",
        "  const val CALL: String = \"\"\"Oreo: black",
        "Mylo: brown\"\"\"",
        "}",
        "",
      ).joinToString(newline)
      val result = Tier1Harness.run(source)
      val cs: String = result.generatedCSharp
      assertContains(
        cs,
        "public const string Call = \"Oreo: black\\nMylo: brown\";",
        message = "source line ending ${newline.escaped()}",
      )
      assertNoConstSkips(result)
    }
  }

  /**
   * Finding 10: a const in a separately compiled dependency has no `containingFile`, so the regex
   * route returned null and every caller `mapNotNull`-dropped it silently. The evaluated route
   * reads it off the library symbol. A top-level dependency const has no closure route into the
   * export set; a companion on an admitted dependency class is the reachable shape.
   */
  @Test
  fun `a dependency companion const is read from the library symbol`() {
    val jar: File = Tier1DependencyLibrary.compile(
      """
      package dep.bowls

      class Waterbowl(val label: String) {
        companion object {
          const val LITRES: Int = 2
          const val BRAND: String = "snake_case_bowl"
        }
      }
      """.trimIndent(),
    )
    val result = Tier1Harness.run(
      """
      package tier1.depconst

      import dep.bowls.Waterbowl

      class Kitchen {
        fun bowl(): Waterbowl = Waterbowl("kitchen")
      }
      """.trimIndent(),
      processorOptions = mapOf(
        "nuget.namespace" to "Lib",
        "nuget.includePackages" to "tier1.depconst",
        "nuget.admit" to "dep.bowls.Waterbowl",
      ),
      libraries = listOf(jar),
    )

    val cs: String = result.generatedCSharp
    assertContains(cs, "class Waterbowl")
    assertContains(cs, "public const int Litres = 2;")
    assertContains(cs, "public const string Brand = \"snake_case_bowl\";")
    assertNoConstSkips(result)
  }

  @Test
  fun `the literal renderer refuses a value that is not of the declared type`() {
    assertNull(KotlinConstValue.csharpLiteral(300, "Byte"))
    assertNull(KotlinConstValue.csharpLiteral(-1, "UInt"))
    assertNull(KotlinConstValue.csharpLiteral("x", "Int"))
    assertNull(KotlinConstValue.csharpLiteral(1.5, "Float"))
    assertNull(KotlinConstValue.csharpLiteral(1, "Unit"))
    assertEquals("4000000000U", KotlinConstValue.csharpLiteral(4_000_000_000u, "UInt"))
    assertEquals("18446744073709551615UL", KotlinConstValue.csharpLiteral(ULong.MAX_VALUE, "ULong"))
  }

  @Test
  fun `a declaration with no reachable analysis symbol is unreadable, never guessed`() {
    val read: KotlinConstValue.Read = KotlinConstValue.read(Any())
    val unreadable = read as? KotlinConstValue.Read.Unreadable
    assertNotNull(unreadable, "$read")
    assertContains(unreadable.reason, KotlinConstValue.SYMBOL_ACCESSOR_PREFIX)
  }

  private fun assertNoConstSkips(result: Tier1Result) {
    val skipKind: String = ForwardDiagnosticKind.SKIPPED_UNREADABLE_CONST_VALUE.name
    assertFalse(
      result.kspWarnings.any { it.contains(skipKind) },
      "no const may be skipped; got: ${result.kspWarnings}",
    )
    assertTrue(result.kspErrors.isEmpty(), "${result.kspErrors}")
  }

  private fun String.escaped(): String = replace("\r", "\\r").replace("\n", "\\n")
}
