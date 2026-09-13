package io.github.xxfast.kotlin.native.nuget.processor.exports

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardExportOwnerTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

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
    val annotation = cNameAnnotation("patient_greet", ForwardExportOwnerTag(symbol = "test"))
    assertEquals("CName", annotation.typeName.toString().substringAfterLast('.'))
    assertEquals(1, annotation.members.size)
  }

  /**
   * ADR-117 amendment (Alternative 3): the single `@CName` minter takes a **required** owner and
   * hangs it on the `AnnotationSpec` itself, which is what makes a future legacy export site fail
   * to compile rather than fall back to class-level attribution. Left red on purpose: the sibling
   * call above still passes one argument, for kotlin-dev to update with the main-source change.
   *
   * Also pins the research note's *Inferred* claim that a KotlinPoet tag never renders: Oreo's
   * owner text must not leak into the generated `@CName("oreo_purr")`.
   */
  @Test
  fun `cNameAnnotation carries the owner tag without rendering it`() {
    val owner = ForwardExportOwnerTag(symbol = "cattery.Oreo.purr", role = "generated Dispose")
    val annotation = cNameAnnotation("oreo_purr", owner)

    assertEquals("CName", annotation.typeName.toString().substringAfterLast('.'))
    assertEquals(1, annotation.members.size)
    assertEquals(owner, annotation.tag(ForwardExportOwnerTag::class))
    assertFalse(
      annotation.toString().contains("generated Dispose"),
      "a KotlinPoet tag is builder metadata and must not render; got $annotation",
    )
  }
}
