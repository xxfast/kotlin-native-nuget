package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

/**
 * ROADMAP line 29: a planned property accessor body and a custom constructor body are laid out
 * like every other generated body — the opening brace alone on its line, the first statement on
 * the next one, the statements one level inside the brace. The projection used to pass
 * `leadingNewline = false` to the shared handle scope, so the first statement shared the brace
 * line, and it baked a method-position indent (12 spaces) into bodies that render at property
 * position (16). Whitespace only: no export, no import and no signature moves.
 */
class Tier1PropertyAccessorBodyLayoutTest {

  private val source: String = """
    package tier1.accessorlayout

    class Gauge(var level: Int?) {
      val label: String get() = "gauge"
    }

    class Ledger(val notes: List<String>?) {
      fun size(): Int = notes?.size ?: 0
    }

    val version: String get() = "1.0"
  """.trimIndent()

  @Test
  fun `the fixture compiles`() {
    val result = Tier1Harness.run(source)

    assertTrue(result.compiledClean, "expected a clean compile; got: ${result.compileErrors}")
  }

  /** The ordinary class position: a `Direct` getter, the shape the whole item is named after. */
  @Test
  fun `a class getter body starts on the line after its brace`() {
    val result = Tier1Harness.run(source)

    assertContains(
      result.generatedCSharp,
      """
      |        public string Label
      |        {
      |            get
      |            {
      |                IntPtr nativeResult = Native_Get_label(_handle, out IntPtr error);
      |                if (error != IntPtr.Zero)
      |                {
      |                    throw NugetErrorNative.BuildException(error);
      |                }
      |                return Marshal.PtrToStringUTF8(nativeResult)!;
      |            }
      |        }
      """.trimMargin(),
    )
  }

  /**
   * The `LegacyTwoCall` getter is the path a careless fix breaks: it emitted its own leading
   * newline, so simply letting the scope lead with one puts a blank line after the brace. The `if`
   * line is pinned right under the `bool hasValue` line for exactly that reason.
   */
  @Test
  fun `a nullable primitive getter has no blank line after its brace`() {
    val result = Tier1Harness.run(source)

    assertContains(
      result.generatedCSharp,
      """
      |            get
      |            {
      |                bool hasValue = Native_Get_level(_handle, out IntPtr error);
      |                if (error != IntPtr.Zero)
      |                {
      |                    throw NugetErrorNative.BuildException(error);
      |                }
      |                if (!hasValue) return null;
      |                int value = Native_Get_level_value(_handle, out IntPtr error2);
      """.trimMargin(),
    )
  }

  /** The `NullableDispatch` setter arm already led with a newline; only its depth moves. */
  @Test
  fun `a nullable primitive setter dispatches one level inside its brace`() {
    val result = Tier1Harness.run(source)

    assertContains(
      result.generatedCSharp,
      """
      |            set
      |            {
      |                if (value.HasValue)
      |                {
      |                    Native_Set_level(_handle, value.Value, out IntPtr error);
      """.trimMargin(),
    )
  }

  /**
   * The constructor sibling: the renderer indented a body whose first line already carried its own
   * indent, so the first statement landed at column 24 and every later one at 12.
   */
  @Test
  fun `a custom constructor body renders like a method body`() {
    val result = Tier1Harness.run(source)

    assertContains(
      result.generatedCSharp,
      """
      |        {
      |            IntPtr notesHandle = IntPtr.Zero;
      |            try
      |            {
      |                notesHandle = notes != null ? NugetMarshal.CreateList(notes) : IntPtr.Zero;
      |                IntPtr handle = Native_Create(notesHandle, out IntPtr error);
      """.trimMargin(),
    )
  }

  /** The static (top-level) position shares `property()` with the class position. */
  @Test
  fun `a top-level property getter body starts on the line after its brace`() {
    val result = Tier1Harness.run(source)

    assertContains(
      result.generatedCSharp,
      """
      |            get
      |            {
      |                IntPtr nativeResult = Native_GetVersion(out IntPtr error);
      """.trimMargin(),
    )
  }
}
