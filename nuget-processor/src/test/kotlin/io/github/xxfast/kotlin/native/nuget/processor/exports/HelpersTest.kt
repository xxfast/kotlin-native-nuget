package io.github.xxfast.kotlin.native.nuget.processor.exports

import kotlin.test.Test
import kotlin.test.assertEquals

/** Pure table helpers used by ordinary and remaining legacy export routes. */
class HelpersTest {
  @Test
  fun `cVarTypeFor maps numeric primitives`() {
    assertEquals("ByteVar", cVarTypeFor("kotlin.Byte").simpleName)
    assertEquals("UByteVar", cVarTypeFor("kotlin.UByte").simpleName)
    assertEquals("ShortVar", cVarTypeFor("kotlin.Short").simpleName)
    assertEquals("UShortVar", cVarTypeFor("kotlin.UShort").simpleName)
    assertEquals("IntVar", cVarTypeFor("kotlin.Int").simpleName)
    assertEquals("UIntVar", cVarTypeFor("kotlin.UInt").simpleName)
    assertEquals("LongVar", cVarTypeFor("kotlin.Long").simpleName)
    assertEquals("ULongVar", cVarTypeFor("kotlin.ULong").simpleName)
    assertEquals("FloatVar", cVarTypeFor("kotlin.Float").simpleName)
    assertEquals("DoubleVar", cVarTypeFor("kotlin.Double").simpleName)
  }

  @Test
  fun `cVarTypeFor falls back to IntVar for unknown types`() {
    assertEquals("IntVar", cVarTypeFor("kotlin.Boolean").simpleName)
    assertEquals("IntVar", cVarTypeFor("sample.Patient").simpleName)
  }

  @Test
  fun `defaultValueFor covers primitives Unit and handles`() {
    assertEquals("false", defaultValueFor("kotlin.Boolean"))
    assertEquals("\"\"", defaultValueFor("kotlin.String"))
    assertEquals("0.0f", defaultValueFor("kotlin.Float"))
    assertEquals("0.0", defaultValueFor("kotlin.Double"))
    assertEquals("0.toUByte()", defaultValueFor("kotlin.UByte"))
    assertEquals("0.toUShort()", defaultValueFor("kotlin.UShort"))
    assertEquals("0u", defaultValueFor("kotlin.UInt"))
    assertEquals("0uL", defaultValueFor("kotlin.ULong"))
    assertEquals("", defaultValueFor("kotlin.Unit"))
    assertEquals("0", defaultValueFor("kotlin.Int"))
    assertEquals("null", defaultValueFor("sample.Patient"))
  }

  @Test
  fun `cNameAnnotation wraps the entry point`() {
    val annotation = cNameAnnotation("patient_greet")
    assertEquals("CName", annotation.typeName.toString().substringAfterLast('.'))
    assertEquals(1, annotation.members.size)
  }
}
