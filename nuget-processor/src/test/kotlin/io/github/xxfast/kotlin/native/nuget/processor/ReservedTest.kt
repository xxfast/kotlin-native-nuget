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
}
