package io.github.xxfast.kotlin.native.nuget.processor

import kotlin.test.Test
import kotlin.test.assertEquals

class ReservedTest {
  @Test
  fun `toCName suffixes a C reserved word`() {
    assertEquals("int_", toCName("int"))
    assertEquals("void_", toCName("void"))
  }

  @Test
  fun `toCName leaves a C-sharp-only reserved word untouched`() {
    // "class" is C#-reserved but not C-reserved; the asymmetry between the two sets is the point.
    assertEquals("class", toCName("class"))
  }

  @Test
  fun `toCName leaves an ordinary identifier untouched`() {
    assertEquals("myFunction", toCName("myFunction"))
  }

  @Test
  fun `toCName leaves an empty string untouched`() {
    assertEquals("", toCName(""))
  }

  @Test
  fun `toCSharpName prefixes a C-sharp reserved word with at`() {
    // trimEnd('_') on "class_" yields "class", which is C#-reserved; the untrimmed input is prefixed.
    assertEquals("@class_", toCSharpName("class_"))
  }

  @Test
  fun `toCSharpName leaves an ordinary identifier untouched`() {
    assertEquals("myMethod", toCSharpName("myMethod"))
  }

  @Test
  fun `toCSharpName leaves a C-only reserved word untouched`() {
    // "auto" is C-reserved but not C#-reserved.
    assertEquals("auto_", toCSharpName("auto_"))
  }

  @Test
  fun `toCSharpName round trips a word reserved on both sides through toCName`() {
    assertEquals("@int_", toCSharpName(toCName("int")))
  }

  @Test
  fun `toCSharpName prefixes a word reserved on both sides without trailing underscore`() {
    assertEquals("@struct", toCSharpName("struct"))
  }

  /**
   * The render-time parameter-name helper: a keyword escape, the error-slot shift, and the
   * identity for everything else, including the generator-minted ABI slot names, which the
   * hand-written body text references raw and so must never move. Shared by the ordinary plan
   * projection and every legacy CIR translator, so it lives beside [toCSharpName] rather than in
   * either one.
   */
  @Test
  fun `csharpParameterName escapes keywords and shifts the error slot chain only`() {
    assertEquals("error_", "error".csharpParameterName())
    assertEquals("error__", "error_".csharpParameterName())
    assertEquals("error___", "error__".csharpParameterName())
    assertEquals("errorOut", "errorOut".csharpParameterName())
    assertEquals("errors", "errors".csharpParameterName())
    assertEquals("myError", "myError".csharpParameterName())
    assertEquals("handle", "handle".csharpParameterName())
    assertEquals("receiver", "receiver".csharpParameterName())
    assertEquals("@abstract", "abstract".csharpParameterName())
    assertEquals("@ref", "ref".csharpParameterName())
    assertEquals("@params", "params".csharpParameterName())
  }

  /**
   * Issue #285, the casing table of the research memo, row for row: the shared spelling of an enum
   * entry and a `const val`. The MOVED rows are the fix; the UNCHANGED rows are the load-bearing
   * negative controls, since a rule that simply kept the Kotlin spelling verbatim would satisfy
   * every moved row and emit `AB1C` and `HAPPY_CAT` for the rest.
   */
  @Test
  fun `kotlinConstantToPascalCase keeps a segment's internal capitals`() {
    // Moved: PascalCase and camelCase kept their internal capitals nowhere before.
    assertEquals("SecondValue", "SecondValue".kotlinConstantToPascalCase())
    assertEquals("ThirdValueHere", "ThirdValueHere".kotlinConstantToPascalCase())
    assertEquals("CamelCase", "camelCase".kotlinConstantToPascalCase())
    assertEquals("ThirdValue", "thirdValue".kotlinConstantToPascalCase())
    // The mixed row that rules out a whole-name gate: an acronym, an internal capital and a `_`.
    assertEquals("XMLParserV2", "XMLParser_V2".kotlinConstantToPascalCase())
    assertEquals("MaxRetries", "MaxRetries".kotlinConstantToPascalCase())

    // Unchanged: a single Pascal word, all caps with a digit, SCREAMING_SNAKE, snake_case, and an
    // all-caps segment beside a Pascal one.
    assertEquals("First", "First".kotlinConstantToPascalCase())
    assertEquals("Ab1c", "AB1C".kotlinConstantToPascalCase())
    assertEquals("Ab1c", "Ab1c".kotlinConstantToPascalCase())
    assertEquals("Happy", "HAPPY".kotlinConstantToPascalCase())
    assertEquals("HappyCat", "HAPPY_CAT".kotlinConstantToPascalCase())
    assertEquals("ScreamingSnake", "SCREAMING_SNAKE".kotlinConstantToPascalCase())
    assertEquals("SnakeCase", "snake_case".kotlinConstantToPascalCase())
    assertEquals("HttpStatus", "HTTP_Status".kotlinConstantToPascalCase())
    assertEquals("MaxNaps", "MAX_NAPS".kotlinConstantToPascalCase())
    assertEquals("PiApprox", "PI_APPROX".kotlinConstantToPascalCase())
  }

  /**
   * The two shapes Kotlin accepts and C# does not, which used to be emitted raw as CS1001 inside
   * `Interop.cs` itself: a converted name that starts with a digit, and one that is empty.
   */
  @Test
  fun `kotlinConstantToPascalCase guards a digit-led or empty converted name`() {
    assertEquals("_1st", "_1ST".kotlinConstantToPascalCase())
    assertEquals("_1st", "1ST".kotlinConstantToPascalCase())
    assertEquals("_", "_".kotlinConstantToPascalCase())
    assertEquals("_", "__".kotlinConstantToPascalCase())
    assertEquals("_", "".kotlinConstantToPascalCase())
  }

  /**
   * A C# keyword is unreachable by construction, which is why the helper takes no [toCSharpName]
   * pass: the first character is always an uppercase letter or `_`, and every C# keyword is all
   * lowercase.
   */
  @Test
  fun `kotlinConstantToPascalCase can never spell a C-sharp keyword`() {
    assertEquals("Class", "class".kotlinConstantToPascalCase())
    assertEquals("Default", "default".kotlinConstantToPascalCase())
    assertEquals("Event", "event".kotlinConstantToPascalCase())
  }

  /**
   * The precondition of the ERROR_CSHARP_NAME_COLLISION guard, pinned at the helper: the pair that
   * already collided before the fix, and the one the per-segment rule newly makes collide.
   */
  @Test
  fun `kotlinConstantToPascalCase collapses two names onto one`() {
    assertEquals("Foo".kotlinConstantToPascalCase(), "FOO".kotlinConstantToPascalCase())
    assertEquals("FooBar".kotlinConstantToPascalCase(), "FOO_BAR".kotlinConstantToPascalCase())
    assertEquals("_".kotlinConstantToPascalCase(), "__".kotlinConstantToPascalCase())
  }

  /**
   * ADR-163: the leading segment of every forward C entry point. It is derived from a name a human
   * chose for a NuGet package or a Kotlin/Native binary, so it may carry case, dots and dashes that
   * no C symbol may.
   */
  @Test
  fun `sanitizeLibrarySegment lowercases and collapses everything a C symbol cannot carry`() {
    assertEquals("testlibrary", sanitizeLibrarySegment("TestLibrary"))
    assertEquals("test_library_native", sanitizeLibrarySegment("Test-Library.Native"))
    // A RUN of illegal characters collapses to one `_`, so the separator count stays predictable.
    assertEquals("a_b", sanitizeLibrarySegment("a -. b"))
    assertEquals("kn_demo", sanitizeLibrarySegment("kn_demo"))
  }

  @Test
  fun `sanitizeLibrarySegment keeps a leading digit out of symbol position`() {
    // A C identifier may not begin with a digit, and leaving it to the linker hides the defect in a
    // toolchain message.
    assertEquals("_2cats", sanitizeLibrarySegment("2cats"))
    assertEquals("_9lives", sanitizeLibrarySegment("9Lives"))
  }

  @Test
  fun `sanitizeLibrarySegment never returns an empty segment`() {
    // An empty leading segment would leave a top-level symbol bare, which is precisely the `signal`
    // hazard ADR-163 exists to close.
    assertEquals("_", sanitizeLibrarySegment(""))
    assertEquals("_", sanitizeLibrarySegment("..."))
  }

  @Test
  fun `sanitizeLibrarySegment can land on the reserved runtime segment`() {
    // The sanitiser does NOT refuse it: the caller does, with a named diagnostic, because only the
    // caller can point at the option that set it (ADR-127 reserves `nuget_*` for the runtime ABI).
    assertEquals(RESERVED_LIBRARY_SEGMENT, sanitizeLibrarySegment("NuGet"))
    assertEquals(RESERVED_LIBRARY_SEGMENT, sanitizeLibrarySegment("nuget"))
  }
}
