package io.github.xxfast.kotlin.native.nuget.rir

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * ADR-072 Decision 3: the RIR additions a closed constructed generic needs. `RirClass`
 * gains [typeParameters]/[instantiations], and two new `RirTypeRef` variants
 * (`RirTypeParameterType`/`RirGenericInstanceType`) let a member's signature reference either "the
 * declaring type's own type parameter" or "a closed instantiation of a bound generic definition".
 *
 * None of `RirClass.typeParameters`, `RirClass.instantiations`, `RirInstantiation`,
 * `RirTypeParameterType`, `RirGenericInstanceType` exist in `RirModel.kt` yet (Step 3, tests-only;
 * Step 4 implements the model). Every test below is EXPECTED TO FAIL: either a Kotlin compile
 * error (the fixture references a type/property that does not exist) until Step 4 adds the model,
 * or, for the JSON-only round-trip tests below that only reference EXISTING model members plus
 * unknown JSON keys, a runtime assertion failure/SerializationException, because
 * `ignoreUnknownKeys = true` (RirParsing.kt) silently drops "typeParameters"/"instantiations" and
 * has no registered subtype for an unrecognized "kind" discriminator ("typeparam"/"generic").
 */
class RirGenericParsingTest {

  // ------------------------------------------------------------------
  // RirClass.typeParameters (additive; old reverse-ir.json without it must still parse, the
  // Decision 3 backward-compatibility requirement).
  // ------------------------------------------------------------------

  @Test
  fun `RirClass with typeParameters deserializes the generic parameter names verbatim, in GenericParam order`() {
    val json = """
      {
        "assemblies": [
          {
            "packageId": "Test.Boxes",
            "assemblyName": "Test.Boxes",
            "namespaces": [
              {
                "name": "Test.Boxes",
                "types": [
                  {
                    "kind": "class",
                    "name": "Pairing`2",
                    "typeParameters": ["TKey", "TValue"],
                    "methods": [],
                    "properties": []
                  }
                ]
              }
            ]
          }
        ]
      }
    """.trimIndent()

    val file: RirFile = parseReverseIr(json)

    val cls = file.assemblies[0].namespaces[0].types[0] as RirClass
    assertEquals(
      listOf("TKey", "TValue"),
      cls.typeParameters,
      "Decision 8: type parameter names must reach Kotlin verbatim from GenericParam.Name, in " +
          "declared order",
    )
  }

  @Test
  fun `RirClass without a typeParameters field in json defaults to an empty list (backward compat)`() {
    val json = """
      {
        "assemblies": [
          {
            "packageId": "Test.Text",
            "assemblyName": "Test.Text",
            "namespaces": [
              {
                "name": "Test.Text",
                "types": [
                  { "kind": "class", "name": "Template", "methods": [], "properties": [] }
                ]
              }
            ]
          }
        ]
      }
    """.trimIndent()

    val file: RirFile = parseReverseIr(json)

    val cls = file.assemblies[0].namespaces[0].types[0] as RirClass
    assertEquals(
      emptyList(),
      cls.typeParameters,
      "an old reverse-ir.json with no 'typeParameters' key must still parse: additive default " +
          "emptyList",
    )
  }

  // ------------------------------------------------------------------
  // RirClass.instantiations / RirInstantiation (additive; Decision 2's discovered closed
  // instantiation set).
  // ------------------------------------------------------------------

  @Test
  fun `RirClass with instantiations deserializes one RirInstantiation per discovered closed instantiation`() {
    val json = """
      {
        "assemblies": [
          {
            "packageId": "Test.Boxes",
            "assemblyName": "Test.Boxes",
            "namespaces": [
              {
                "name": "Test.Boxes",
                "types": [
                  {
                    "kind": "class",
                    "name": "Box`1",
                    "typeParameters": ["T"],
                    "instantiations": [
                      { "typeArguments": [ { "kind": "primitive", "name": "int" } ] },
                      { "typeArguments": [ { "kind": "string" } ] },
                      { "typeArguments": [ { "kind": "string", "nullable": true } ] }
                    ],
                    "methods": [],
                    "properties": []
                  }
                ]
              }
            ]
          }
        ]
      }
    """.trimIndent()

    val file: RirFile = parseReverseIr(json)

    val cls = file.assemblies[0].namespaces[0].types[0] as RirClass
    assertEquals(3, cls.instantiations.size)
    assertIs<RirPrimitiveType>(cls.instantiations[0].typeArguments[0])
    assertIs<RirStringType>(cls.instantiations[1].typeArguments[0])
    assertEquals(false, (cls.instantiations[1].typeArguments[0] as RirStringType).nullable)
    assertEquals(true, (cls.instantiations[2].typeArguments[0] as RirStringType).nullable)
  }

  @Test
  fun `RirClass without an instantiations field in json defaults to an empty list, the Box backtick-1 leak regression seam`() {
    // Decision 10: a generic definition with ZERO discovered instantiations must emit nothing at
    // all. The reader-side contract for that is simply "instantiations is empty". This pins the
    // parse-level half of that contract (additive default), independent of what either generator
    // then does with it (Step 4).
    val json = """
      {
        "assemblies": [
          {
            "packageId": "Test.Boxes",
            "assemblyName": "Test.Boxes",
            "namespaces": [
              {
                "name": "Test.Boxes",
                "types": [
                  {
                    "kind": "class",
                    "name": "Unused`1",
                    "typeParameters": ["T"],
                    "methods": [],
                    "properties": []
                  }
                ]
              }
            ]
          }
        ]
      }
    """.trimIndent()

    val file: RirFile = parseReverseIr(json)

    val cls = file.assemblies[0].namespaces[0].types[0] as RirClass
    assertEquals(emptyList(), cls.instantiations)
  }

  // ------------------------------------------------------------------
  // RirTypeParameterType (kind="typeparam"): a member type that IS a type parameter of the
  // declaring type.
  // ------------------------------------------------------------------

  @Test
  fun `type ref kind typeparam deserializes to RirTypeParameterType with index and name`() {
    val json = """
      {
        "assemblies": [
          {
            "packageId": "Test.Boxes",
            "assemblyName": "Test.Boxes",
            "namespaces": [
              {
                "name": "Test.Boxes",
                "types": [
                  {
                    "kind": "class",
                    "name": "Box`1",
                    "typeParameters": ["T"],
                    "properties": [
                      {
                        "name": "Value",
                        "type": { "kind": "typeparam", "index": 0, "name": "T" },
                        "isReadOnly": true,
                        "isStatic": false
                      }
                    ],
                    "methods": []
                  }
                ]
              }
            ]
          }
        ]
      }
    """.trimIndent()

    val file: RirFile = parseReverseIr(json)

    val cls = file.assemblies[0].namespaces[0].types[0] as RirClass
    val type: RirTypeRef = cls.properties[0].type
    assertIs<RirTypeParameterType>(type)
    assertEquals(0, type.index)
    assertEquals("T", type.name)
  }

  // ------------------------------------------------------------------
  // RirGenericInstanceType (kind="generic"): a member type that is a closed instantiation of a
  // bound generic definition.
  // ------------------------------------------------------------------

  @Test
  fun `type ref kind generic deserializes to RirGenericInstanceType with namespace, name, typeArguments and nullable default false`() {
    val json = """
      {
        "assemblies": [
          {
            "packageId": "Test.Boxes",
            "assemblyName": "Test.Boxes",
            "namespaces": [
              {
                "name": "Test.Boxes",
                "types": [
                  {
                    "kind": "class",
                    "name": "Box`1",
                    "typeParameters": ["T"],
                    "methods": [
                      {
                        "name": "Rewrap",
                        "isStatic": false,
                        "returnType": {
                          "kind": "generic",
                          "namespace": "Test.Boxes",
                          "name": "Box`1",
                          "typeArguments": [ { "kind": "typeparam", "index": 0, "name": "T" } ]
                        },
                        "parameters": []
                      }
                    ],
                    "properties": []
                  }
                ]
              }
            ]
          }
        ]
      }
    """.trimIndent()

    val file: RirFile = parseReverseIr(json)

    val cls = file.assemblies[0].namespaces[0].types[0] as RirClass
    val returnType: RirTypeRef = cls.methods[0].returnType
    assertIs<RirGenericInstanceType>(returnType)
    assertEquals("Test.Boxes", returnType.namespace)
    assertEquals("Box`1", returnType.name, "Decision 10: RIR identity keeps the CLR name verbatim")
    assertEquals(1, returnType.typeArguments.size)
    assertEquals(false, returnType.nullable)
  }

  @Test
  fun `type ref kind generic with nullable true parses to RirGenericInstanceType with nullable true`() {
    // Decision 7 / the ADR-053 failure class, third instance: a Box<T> reference itself CAN be
    // annotated nullable ("Box<int>? NullableIntBox()" in the ADR's verified NullableAttribute
    // table) independently of its type argument's own nullability.
    val json = """
      {
        "assemblies": [
          {
            "packageId": "Test.Boxes",
            "assemblyName": "Test.Boxes",
            "namespaces": [
              {
                "name": "Test.Boxes",
                "types": [
                  {
                    "kind": "class",
                    "name": "Boxes",
                    "isStatic": true,
                    "methods": [
                      {
                        "name": "MaybeBox",
                        "isStatic": true,
                        "returnType": {
                          "kind": "generic",
                          "namespace": "Test.Boxes",
                          "name": "Box`1",
                          "typeArguments": [ { "kind": "primitive", "name": "int" } ],
                          "nullable": true
                        },
                        "parameters": []
                      }
                    ],
                    "properties": []
                  }
                ]
              }
            ]
          }
        ]
      }
    """.trimIndent()

    val file: RirFile = parseReverseIr(json)

    val cls = file.assemblies[0].namespaces[0].types[0] as RirClass
    val returnType: RirTypeRef = cls.methods[0].returnType
    assertIs<RirGenericInstanceType>(returnType)
    assertEquals(true, returnType.nullable)
  }

  @Test
  fun `RirGenericInstanceType supports arity 2 typeArguments in declaration order`() {
    val json = """
      {
        "assemblies": [
          {
            "packageId": "Test.Boxes",
            "assemblyName": "Test.Boxes",
            "namespaces": [
              {
                "name": "Test.Boxes",
                "types": [
                  {
                    "kind": "class",
                    "name": "Boxes",
                    "isStatic": true,
                    "methods": [
                      {
                        "name": "Tally",
                        "isStatic": true,
                        "returnType": {
                          "kind": "generic",
                          "namespace": "Test.Boxes",
                          "name": "Pairing`2",
                          "typeArguments": [
                            { "kind": "string" },
                            { "kind": "primitive", "name": "int" }
                          ]
                        },
                        "parameters": []
                      }
                    ],
                    "properties": []
                  }
                ]
              }
            ]
          }
        ]
      }
    """.trimIndent()

    val file: RirFile = parseReverseIr(json)

    val cls = file.assemblies[0].namespaces[0].types[0] as RirClass
    val returnType = cls.methods[0].returnType as RirGenericInstanceType
    assertIs<RirStringType>(returnType.typeArguments[0])
    assertIs<RirPrimitiveType>(returnType.typeArguments[1])
  }

  // ------------------------------------------------------------------
  // Fails-closed-by-construction sanity, mirroring RirParsingTest's existing
  // "parseReverseIr fails on an unrecognized type kind" test. Pre-Step-4 (RirTypeParameterType not
  // yet registered), "typeparam" was itself an unrecognized discriminator and this test asserted a
  // SerializationException. Now that Step 4 has registered it (see the "type ref kind typeparam
  // deserializes..." test above), that premise no longer holds: parsing must SUCCEED, which is
  // the whole point of Decision 3 lifting ADR-043's exclusion for a resolvable type parameter. Kept
  // as a still-unrecognized-discriminator regression pin, now over a kind that remains genuinely
  // unregistered ("typeparam-nonsense"), rather than asserting a parse failure that Step 4 itself
  // fixed.
  // ------------------------------------------------------------------

  @Test
  fun `parseReverseIr succeeds on kind typeparam now that RirTypeParameterType is registered`() {
    val json = """
      {
        "assemblies": [
          {
            "packageId": "Test.Boxes",
            "assemblyName": "Test.Boxes",
            "namespaces": [
              {
                "name": "Test.Boxes",
                "types": [
                  {
                    "kind": "class",
                    "name": "Box`1",
                    "properties": [
                      {
                        "name": "Value",
                        "type": { "kind": "typeparam", "index": 0, "name": "T" },
                        "isReadOnly": true,
                        "isStatic": false
                      }
                    ],
                    "methods": []
                  }
                ]
              }
            ]
          }
        ]
      }
    """.trimIndent()

    val file: RirFile = parseReverseIr(json)
    assertIs<RirTypeParameterType>(
      (file.assemblies[0].namespaces[0].types[0] as RirClass).properties[0].type,
    )
  }

  @Test
  fun `parseReverseIr still fails on a genuinely unrecognized kind`() {
    val json = """
      {
        "assemblies": [
          {
            "packageId": "Test.Boxes",
            "assemblyName": "Test.Boxes",
            "namespaces": [
              {
                "name": "Test.Boxes",
                "types": [
                  {
                    "kind": "class",
                    "name": "Box`1",
                    "properties": [
                      {
                        "name": "Value",
                        "type": { "kind": "typeparam-nonsense", "index": 0, "name": "T" },
                        "isReadOnly": true,
                        "isStatic": false
                      }
                    ],
                    "methods": []
                  }
                ]
              }
            ]
          }
        ]
      }
    """.trimIndent()

    assertFailsWith<kotlinx.serialization.SerializationException> {
      parseReverseIr(json)
    }
  }
}
