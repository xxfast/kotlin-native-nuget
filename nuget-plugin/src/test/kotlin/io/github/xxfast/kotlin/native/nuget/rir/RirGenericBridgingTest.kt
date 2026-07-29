package io.github.xxfast.kotlin.native.nuget.rir

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * ADR-072 Decision 3's table of six permissive `RirTypeRef` dispatch sites: this file pins the
 * two that are directly testable at the RIR-bridging layer (both correctness-critical, per the
 * ADR's own "Carried into Step 4" note) against a bare [RirGenericInstanceType], BEFORE that type
 * exists in `RirModel.kt`. EXPECTED TO FAIL to compile until Step 4 adds
 * `RirGenericInstanceType`/`RirTypeParameterType` to the model. See `RirGenericParsingTest.kt` for
 * the parse-level half of the same seam.
 *
 * `RirBridging.kt:560` `isNullable`'s `else -> false` branch would silently bind a nullable-capable
 * generic instance as non-null (the ADR-053/ADR-070 failure class, third instance).
 *
 * `RirBridging.kt:882` `signaturePart`'s `else -> describe()` branch renders a generic instance
 * WITHOUT its type arguments, so `Box<int>` and `Box<string>` would produce the identical
 * `signaturePart`/`contractHash` input, directly defeating Decision 4 ("the contract hash differs
 * per instantiation"). `signaturePart` itself is private, so this is pinned through the public
 * `contractHash` entry point, exactly like `RirBridgingTest`'s existing struct-signaturePart
 * regression test does for `RirStructType`.
 */
class RirGenericBridgingTest {

  // ------------------------------------------------------------------
  // isNullable (RirBridging.kt:560)
  // ------------------------------------------------------------------

  @Test
  fun `a nullable RirGenericInstanceType reports isNullable true`() {
    val nullableBoxOfInt = RirGenericInstanceType(
      namespace = "Test.Boxes",
      name = "Box`1",
      typeArguments = listOf(RirPrimitiveType("int")),
      nullable = true,
    )

    assertTrue(
      nullableBoxOfInt.isNullable,
      "ADR-072 Decision 3 / ADR-053 failure class, third instance: isNullable's else -> false " +
          "branch must not silently bind a nullable Box<int>? as non-null",
    )
  }

  @Test
  fun `a non-nullable RirGenericInstanceType reports isNullable false`() {
    val boxOfInt = RirGenericInstanceType(
      namespace = "Test.Boxes",
      name = "Box`1",
      typeArguments = listOf(RirPrimitiveType("int")),
      nullable = false,
    )

    assertEquals(false, boxOfInt.isNullable)
  }

  // ------------------------------------------------------------------
  // signaturePart / contractHash (RirBridging.kt:882), Decision 4: "the contract hash differs [...]
  // because the canonical instantiation signature [...] and every substituted signaturePart of every
  // argument and return also differs".
  // ------------------------------------------------------------------

  private fun boxesClassReturning(typeArgument: RirTypeRef): RirClass {
    val peek = RirMethod(
      name = "Peek",
      isStatic = true,
      returnType = RirGenericInstanceType(
        namespace = "Test.Boxes",
        name = "Box`1",
        typeArguments = listOf(typeArgument),
      ),
      parameters = emptyList(),
    )
    return RirClass(name = "Boxes", isStatic = true, methods = listOf(peek))
  }

  @Test
  fun `contractHash differs for a method returning Box of int vs Box of string, signaturePart must recurse into typeArguments`() {
    val boxOfIntCls: RirClass = boxesClassReturning(RirPrimitiveType("int"))
    val boxOfStringCls: RirClass = boxesClassReturning(RirStringType())

    val boxOfIntRegistrables: List<RirRegistrable> = listOf(RirRegistrable.Method(boxOfIntCls.methods[0]))
    val boxOfStringRegistrables: List<RirRegistrable> =
      listOf(RirRegistrable.Method(boxOfStringCls.methods[0]))

    val hashForInt: Long = contractHash("Boxes", boxOfIntRegistrables, emptyMap())
    val hashForString: Long = contractHash("Boxes", boxOfStringRegistrables, emptyMap())

    assertNotEquals(
      hashForInt,
      hashForString,
      "ADR-072 Decision 4, directly defeated by the current else -> describe() branch: a method " +
          "returning Box<int> and the SAME method returning Box<string> must hash differently, " +
          "today signaturePart() ignores typeArguments entirely, so both describe() to the same " +
          "bare \"Test.Boxes.Box`1\" and collide",
    )
  }

  @Test
  fun `contractHash differs for two instantiations named per Decision 4's canonical instantiation signature`() {
    // Decision 4's own worked example:
    //   Test.Boxes.Box`1[System.Int32]   -> hash A
    //   Test.Boxes.Box`1[System.String]  -> hash B
    //   Test.Boxes.Box`1[System.String?] -> hash C
    // Pinned here as three DISTINCT canonical-name hashes over the identical (empty) registrable
    // list, which is exactly what "the hash is computed [...] passing the canonical instantiation
    // signature as the name" requires independently of any member's own signaturePart.
    val hashInt: Long = contractHash("Test.Boxes.Box`1[System.Int32]", emptyList(), emptyMap())
    val hashString: Long = contractHash("Test.Boxes.Box`1[System.String]", emptyList(), emptyMap())
    val hashNullableString: Long =
      contractHash("Test.Boxes.Box`1[System.String?]", emptyList(), emptyMap())

    assertNotEquals(hashInt, hashString)
    assertNotEquals(hashString, hashNullableString)
    assertNotEquals(hashInt, hashNullableString)
  }

  @Test
  fun `contractHash differs for Box of string vs Box of nullable string parameter, nullability of a type argument must be hashed`() {
    fun classTaking(argument: RirTypeRef): RirClass {
      val accept = RirMethod(
        name = "Accept",
        isStatic = true,
        returnType = RirVoidType,
        parameters = listOf(
          RirParameter(
            name = "box",
            type = RirGenericInstanceType(
              namespace = "Test.Boxes", name = "Box`1", typeArguments = listOf(argument),
            ),
          ),
        ),
      )
      return RirClass(name = "Boxes", isStatic = true, methods = listOf(accept))
    }

    val nonNullCls: RirClass = classTaking(RirStringType(nullable = false))
    val nullableCls: RirClass = classTaking(RirStringType(nullable = true))

    val hashNonNull: Long =
      contractHash("Boxes", listOf(RirRegistrable.Method(nonNullCls.methods[0])), emptyMap())
    val hashNullable: Long =
      contractHash("Boxes", listOf(RirRegistrable.Method(nullableCls.methods[0])), emptyMap())

    assertNotEquals(
      hashNonNull,
      hashNullable,
      "Decision 7: a type argument's nullability must be part of the hashed signature, exactly " +
          "like any other reference type's nullability",
    )
  }
}
