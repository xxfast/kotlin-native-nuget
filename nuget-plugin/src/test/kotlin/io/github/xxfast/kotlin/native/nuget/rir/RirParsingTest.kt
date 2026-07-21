package io.github.xxfast.kotlin.native.nuget.rir

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class RirParsingTest {
  @Test
  fun `reverse-ir json with a single class deserializes to one RirClass in one namespace`() {
    val json = """
      {
        "assemblies": [
          {
            "packageId": "Newtonsoft.Json",
            "assemblyName": "Newtonsoft.Json",
            "namespaces": [
              {
                "name": "Newtonsoft.Json",
                "types": [
                  {
                    "kind": "class",
                    "name": "JsonConvert",
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

    assertEquals(1, file.assemblies.size)
    assertEquals("Newtonsoft.Json", file.assemblies[0].packageId)
    assertEquals(1, file.assemblies[0].namespaces.size)
    val type: RirType = file.assemblies[0].namespaces[0].types[0]
    assertIs<RirClass>(type)
    assertEquals("JsonConvert", type.name)
  }

  @Test
  fun `reverse-ir json with an interface type deserializes to RirInterface`() {
    val json = """
      {
        "assemblies": [
          {
            "packageId": "Acme.Lib",
            "assemblyName": "Acme.Lib",
            "namespaces": [
              {
                "name": "Acme.Lib",
                "types": [
                  {
                    "kind": "interface",
                    "name": "IJsonConvertible",
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

    val type: RirType = file.assemblies[0].namespaces[0].types[0]
    assertIs<RirInterface>(type)
    assertEquals("IJsonConvertible", type.name)
  }

  @Test
  fun `type ref kind void deserializes to RirVoidType`() {
    val json = """
      {
        "assemblies": [
          {
            "packageId": "Acme.Lib",
            "assemblyName": "Acme.Lib",
            "namespaces": [
              {
                "name": "Acme.Lib",
                "types": [
                  {
                    "kind": "class",
                    "name": "Foo",
                    "methods": [
                      {
                        "name": "DoIt",
                        "returnType": { "kind": "void" },
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
    assertIs<RirVoidType>(cls.methods[0].returnType)
  }

  @Test
  fun `type ref kind string deserializes to RirStringType`() {
    val json = """
      {
        "assemblies": [
          {
            "packageId": "Acme.Lib",
            "assemblyName": "Acme.Lib",
            "namespaces": [
              {
                "name": "Acme.Lib",
                "types": [
                  {
                    "kind": "class",
                    "name": "Foo",
                    "methods": [
                      {
                        "name": "GetName",
                        "returnType": { "kind": "string" },
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
    assertIs<RirStringType>(cls.methods[0].returnType)
  }

  @Test
  fun `type ref kind primitive with name int deserializes to RirPrimitiveType with name int`() {
    val json = """
      {
        "assemblies": [
          {
            "packageId": "Acme.Lib",
            "assemblyName": "Acme.Lib",
            "namespaces": [
              {
                "name": "Acme.Lib",
                "types": [
                  {
                    "kind": "class",
                    "name": "Foo",
                    "methods": [
                      {
                        "name": "GetCount",
                        "returnType": { "kind": "primitive", "name": "int" },
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
    assertIs<RirPrimitiveType>(returnType)
    assertEquals("int", returnType.name)
  }

  @Test
  fun `diagnostic with kind skipped_overload_set deserializes correctly`() {
    val json = """
      {
        "assemblies": [
          {
            "packageId": "Newtonsoft.Json",
            "assemblyName": "Newtonsoft.Json",
            "namespaces": [],
            "diagnostics": [
              {
                "kind": "skipped_overload_set",
                "typeName": "JsonConvert",
                "memberName": "SerializeObject",
                "memberSignature": "SerializeObject(object) [+3 overloads]",
                "reason": "overload set — 4 overloads",
                "hint": "Add a C# adapter shim."
              }
            ]
          }
        ]
      }
    """.trimIndent()

    val file: RirFile = parseReverseIr(json)

    val diagnostic: RirDiagnostic = file.assemblies[0].diagnostics[0]
    assertEquals(RirDiagnosticKind.SKIPPED_OVERLOAD_SET, diagnostic.kind)
    assertEquals("JsonConvert", diagnostic.typeName)
    assertEquals("SerializeObject", diagnostic.memberName)
  }

  // ------------------------------------------------------------------
  // ADR-051: RirObjectHandleType (kind=handle) and RirClass.isStatic
  // ------------------------------------------------------------------

  @Test
  fun `type ref kind handle deserializes to RirObjectHandleType with namespace and name`() {
    // Will fail to compile until RirObjectHandleType is added to RirModel.kt
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
                  {
                    "kind": "class",
                    "name": "Template",
                    "methods": [
                      {
                        "name": "Parse",
                        "isStatic": true,
                        "returnType": { "kind": "handle", "namespace": "Test.Text", "name": "Template" },
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
    assertIs<RirObjectHandleType>(returnType)
    assertEquals("Test.Text", returnType.namespace)
    assertEquals("Template", returnType.name)
  }

  @Test
  fun `RirClass with isStatic true in json deserializes with isStatic true`() {
    // Will fail to compile until isStatic is added to RirClass in RirModel.kt
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
                  {
                    "kind": "class",
                    "name": "Template",
                    "isStatic": true,
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
    assertEquals(true, cls.isStatic)
  }

  @Test
  fun `RirClass without isStatic field in json defaults to isStatic false`() {
    // Will fail to compile until isStatic is added to RirClass in RirModel.kt (default false)
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
                  {
                    "kind": "class",
                    "name": "Template",
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
    assertEquals(false, cls.isStatic)
  }

  // ------------------------------------------------------------------
  // Phase 9: enum IR. The metadata reader has already rejected flags, aliases, non-int backing
  // types, and non-contiguous values before emitting this simple ordinal-backed representation.
  // ------------------------------------------------------------------

  @Test
  fun `reverse-ir json with enum and enum type reference deserializes their ordinal contract`() {
    val json = """
      {
        "assemblies": [
          {
            "packageId": "Test.Enums",
            "assemblyName": "Test.Enums",
            "namespaces": [
              {
                "name": "Test.Enums",
                "types": [
                  {
                    "kind": "enum",
                    "name": "Mood",
                    "entries": [
                      { "name": "Happy", "ordinal": 0 },
                      { "name": "Sleepy", "ordinal": 1 },
                      { "name": "Grumpy", "ordinal": 2 }
                    ]
                  },
                  {
                    "kind": "class",
                    "name": "MoodService",
                    "methods": [
                      {
                        "name": "Next",
                        "isStatic": true,
                        "returnType": { "kind": "enum", "namespace": "Test.Enums", "name": "Mood" },
                        "parameters": [
                          { "name": "mood", "type": { "kind": "enum", "namespace": "Test.Enums", "name": "Mood" } }
                        ]
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
    val types: List<RirType> = file.assemblies[0].namespaces[0].types
    val enum: RirEnum = types[0] as RirEnum
    val service: RirClass = types[1] as RirClass
    val returnType: RirTypeRef = service.methods[0].returnType
    val parameterType: RirTypeRef = service.methods[0].parameters[0].type

    assertEquals(listOf("Happy", "Sleepy", "Grumpy"), enum.entries.map { it.name })
    assertEquals(listOf(0, 1, 2), enum.entries.map { it.ordinal })
    assertIs<RirEnumType>(returnType)
    assertIs<RirEnumType>(parameterType)
  }

  // ------------------------------------------------------------------
  // ADR-052: RirClass.constructors (RirConstructor)
  // ------------------------------------------------------------------

  @Test
  fun `RirClass with a single constructor entry deserializes to one RirConstructor with parameters`() {
    // Will fail to compile until RirConstructor / RirClass.constructors is added to RirModel.kt
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
                  {
                    "kind": "class",
                    "name": "Template",
                    "methods": [],
                    "properties": [],
                    "constructors": [
                      {
                        "parameters": [
                          { "name": "source", "type": { "kind": "string" } }
                        ]
                      }
                    ]
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
    assertEquals(1, cls.constructors.size)
    assertEquals(1, cls.constructors[0].parameters.size)
    assertEquals("source", cls.constructors[0].parameters[0].name)
    assertIs<RirStringType>(cls.constructors[0].parameters[0].type)
  }

  @Test
  fun `RirClass without a constructors field in json defaults to an empty constructors list`() {
    // Will fail to compile until RirClass.constructors is added to RirModel.kt (default emptyList)
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
                  {
                    "kind": "class",
                    "name": "Template",
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
    assertEquals(0, cls.constructors.size)
  }

  @Test
  fun `parseReverseIr ignores unknown top-level fields`() {
    val json = """
      {
        "assemblies": [],
        "unknownField": "someValue",
        "anotherUnknown": 123
      }
    """.trimIndent()

    val file: RirFile = parseReverseIr(json)

    assertEquals(0, file.assemblies.size)
  }

  @Test
  fun `method with isStatic true deserializes with isStatic true`() {
    val json = """
      {
        "assemblies": [
          {
            "packageId": "Acme.Lib",
            "assemblyName": "Acme.Lib",
            "namespaces": [
              {
                "name": "Acme.Lib",
                "types": [
                  {
                    "kind": "class",
                    "name": "Foo",
                    "methods": [
                      {
                        "name": "Create",
                        "isStatic": true,
                        "returnType": { "kind": "void" },
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
    assertEquals(true, cls.methods[0].isStatic)
  }

  @Test
  fun `property with isReadOnly false deserializes with isReadOnly false`() {
    val json = """
      {
        "assemblies": [
          {
            "packageId": "Acme.Lib",
            "assemblyName": "Acme.Lib",
            "namespaces": [
              {
                "name": "Acme.Lib",
                "types": [
                  {
                    "kind": "class",
                    "name": "Foo",
                    "methods": [],
                    "properties": [
                      {
                        "name": "Count",
                        "type": { "kind": "primitive", "name": "int" },
                        "isReadOnly": false,
                        "isStatic": false
                      }
                    ]
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
    assertEquals(false, cls.properties[0].isReadOnly)
  }

  // ------------------------------------------------------------------
  // ADR-053: `nullable` on RirStringType / RirObjectHandleType (NullableAttribute decoding).
  // ------------------------------------------------------------------

  @Test
  fun `type ref kind string with no nullable key parses as non-nullable (backward compat)`() {
    val json = """
      {
        "assemblies": [
          {
            "packageId": "Acme.Lib",
            "assemblyName": "Acme.Lib",
            "namespaces": [
              {
                "name": "Acme.Lib",
                "types": [
                  {
                    "kind": "class",
                    "name": "Foo",
                    "methods": [
                      {
                        "name": "GetName",
                        "returnType": { "kind": "string" },
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
    assertIs<RirStringType>(returnType)
    assertEquals(
      false,
      returnType.nullable,
      "an old reverse-ir.json with no 'nullable' key must still parse — additive default false",
    )
  }

  @Test
  fun `type ref kind string with nullable true parses to RirStringType with nullable true`() {
    val json = """
      {
        "assemblies": [
          {
            "packageId": "Test.Nullability",
            "assemblyName": "Test.Nullability",
            "namespaces": [
              {
                "name": "Test.Nullability",
                "types": [
                  {
                    "kind": "class",
                    "name": "NicknameBook",
                    "methods": [
                      {
                        "name": "Find",
                        "isStatic": false,
                        "returnType": { "kind": "string", "nullable": true },
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
    assertIs<RirStringType>(returnType)
    assertEquals(true, returnType.nullable)
  }

  @Test
  fun `type ref kind handle with nullable true parses to RirObjectHandleType with nullable true`() {
    val json = """
      {
        "assemblies": [
          {
            "packageId": "Test.Nullability",
            "assemblyName": "Test.Nullability",
            "namespaces": [
              {
                "name": "Test.Nullability",
                "types": [
                  {
                    "kind": "class",
                    "name": "NicknameBook",
                    "methods": [
                      {
                        "name": "Lookup",
                        "isStatic": false,
                        "returnType": {
                          "kind": "handle",
                          "namespace": "Test.Nullability",
                          "name": "Nickname",
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
    assertIs<RirObjectHandleType>(returnType)
    assertEquals("Test.Nullability", returnType.namespace)
    assertEquals("Nickname", returnType.name)
    assertEquals(true, returnType.nullable)
  }

  @Test
  fun `type ref kind handle with no nullable key parses as non-nullable (backward compat)`() {
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
                  {
                    "kind": "class",
                    "name": "Template",
                    "methods": [
                      {
                        "name": "Parse",
                        "isStatic": true,
                        "returnType": { "kind": "handle", "namespace": "Test.Text", "name": "Template" },
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
    assertIs<RirObjectHandleType>(returnType)
    assertEquals(false, returnType.nullable)
  }

  // ------------------------------------------------------------------
  // ADR-053: RirDiagnosticKind.INFO_OBLIVIOUS_NULLABILITY — assembly-level (memberName empty) and
  // member-level (memberName populated, a `#nullable disable` island) forms both round-trip
  // through the existing RirDiagnostic model (ADR-043), reusing the same JSON shape every other
  // diagnostic kind already uses.
  // ------------------------------------------------------------------

  @Test
  fun `assembly-level diagnostic with kind info_oblivious_nullability deserializes correctly`() {
    val json = """
      {
        "assemblies": [
          {
            "packageId": "MimeMapping",
            "assemblyName": "MimeMapping",
            "namespaces": [],
            "diagnostics": [
              {
                "kind": "info_oblivious_nullability",
                "typeName": "",
                "memberName": "",
                "memberSignature": "",
                "reason": "no NullableAttribute/NullableContextAttribute anywhere in the assembly",
                "hint": "Every reference type in this package binds non-null; a null from C# fails fast."
              }
            ]
          }
        ]
      }
    """.trimIndent()

    val file: RirFile = parseReverseIr(json)

    val diagnostic: RirDiagnostic = file.assemblies[0].diagnostics[0]
    assertEquals(RirDiagnosticKind.INFO_OBLIVIOUS_NULLABILITY, diagnostic.kind)
    assertEquals("", diagnostic.memberName)
  }

  @Test
  fun `member-level diagnostic with kind info_oblivious_nullability names the oblivious member`() {
    val json = """
      {
        "assemblies": [
          {
            "packageId": "Test.Nullability",
            "assemblyName": "Test.Nullability",
            "namespaces": [],
            "diagnostics": [
              {
                "kind": "info_oblivious_nullability",
                "typeName": "LegacyNicknameBook",
                "memberName": "Find",
                "memberSignature": "Find(string): string",
                "reason": "member compiled inside a #nullable disable region",
                "hint": "Find binds non-null; a null from C# fails fast."
              }
            ]
          }
        ]
      }
    """.trimIndent()

    val file: RirFile = parseReverseIr(json)

    val diagnostic: RirDiagnostic = file.assemblies[0].diagnostics[0]
    assertEquals(RirDiagnosticKind.INFO_OBLIVIOUS_NULLABILITY, diagnostic.kind)
    assertEquals("LegacyNicknameBook", diagnostic.typeName)
    assertEquals("Find", diagnostic.memberName)
  }

  // ------------------------------------------------------------------
  // ADR-058: RirStruct (kind=struct namespace type) and RirStructType (kind=struct type ref).
  // ------------------------------------------------------------------

  @Test
  fun `reverse-ir json with a struct type deserializes to RirStruct with components`() {
    val json = """
      {
        "assemblies": [
          {
            "packageId": "Test.Structs",
            "assemblyName": "Test.Structs",
            "namespaces": [
              {
                "name": "Test.Structs",
                "types": [
                  {
                    "kind": "struct",
                    "name": "Point",
                    "components": [
                      { "name": "x", "readName": "X", "type": { "kind": "primitive", "name": "int" } }
                    ]
                  }
                ]
              }
            ]
          }
        ]
      }
    """.trimIndent()

    val file: RirFile = parseReverseIr(json)

    val type: RirType = file.assemblies[0].namespaces[0].types[0]
    assertIs<RirStruct>(type)
    assertEquals("Point", type.name)
    assertEquals(1, type.components.size)
    assertEquals("x", type.components[0].name)
    assertEquals("X", type.components[0].readName)
  }

  @Test
  fun `RirStruct without a shape field defaults to CONSTRUCTOR (backward compat)`() {
    val json = """
      {
        "assemblies": [
          {
            "packageId": "Test.Structs",
            "assemblyName": "Test.Structs",
            "namespaces": [
              {
                "name": "Test.Structs",
                "types": [
                  {
                    "kind": "struct",
                    "name": "Point",
                    "components": [
                      { "name": "x", "readName": "X", "type": { "kind": "primitive", "name": "int" } }
                    ]
                  }
                ]
              }
            ]
          }
        ]
      }
    """.trimIndent()

    val file: RirFile = parseReverseIr(json)

    val type = file.assemblies[0].namespaces[0].types[0] as RirStruct
    assertEquals(
      RirStructShape.CONSTRUCTOR,
      type.shape,
      "an old reverse-ir.json with no 'shape' key must still parse — additive default CONSTRUCTOR",
    )
  }

  @Test
  fun `RirStruct with shape initializer deserializes to INITIALIZER`() {
    val json = """
      {
        "assemblies": [
          {
            "packageId": "Test.Structs",
            "assemblyName": "Test.Structs",
            "namespaces": [
              {
                "name": "Test.Structs",
                "types": [
                  {
                    "kind": "struct",
                    "name": "Point",
                    "shape": "initializer",
                    "components": [
                      { "name": "x", "readName": "X", "type": { "kind": "primitive", "name": "int" } }
                    ]
                  }
                ]
              }
            ]
          }
        ]
      }
    """.trimIndent()

    val file: RirFile = parseReverseIr(json)

    val type = file.assemblies[0].namespaces[0].types[0] as RirStruct
    assertEquals(RirStructShape.INITIALIZER, type.shape)
  }

  @Test
  fun `type ref kind struct deserializes to RirStructType with namespace and name`() {
    val json = """
      {
        "assemblies": [
          {
            "packageId": "Test.Structs",
            "assemblyName": "Test.Structs",
            "namespaces": [
              {
                "name": "Test.Structs",
                "types": [
                  {
                    "kind": "class",
                    "name": "Foo",
                    "methods": [
                      {
                        "name": "Origin",
                        "isStatic": true,
                        "returnType": { "kind": "struct", "namespace": "Test.Structs", "name": "Point" },
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
    assertIs<RirStructType>(returnType)
    assertEquals("Test.Structs", returnType.namespace)
    assertEquals("Point", returnType.name)
  }

  // ------------------------------------------------------------------
  // The `Json { ignoreUnknownKeys = true }` boundary contract: unknown KEYS are ignored, but an
  // unrecognized polymorphic discriminator VALUE is not a key — kotlinx.serialization has no
  // matching subclass to deserialize into and fails the whole parse. A newer metadata reader
  // emitting one type kind this Gradle plugin version does not yet know about fails fast rather
  // than silently dropping that one type.
  // ------------------------------------------------------------------

  @Test
  fun `parseReverseIr fails on an unrecognized type kind`() {
    val json = """
      {
        "assemblies": [
          {
            "packageId": "Test.Future",
            "assemblyName": "Test.Future",
            "namespaces": [
              {
                "name": "Test.Future",
                "types": [
                  {
                    "kind": "future_kind",
                    "name": "FromTheFuture"
                  }
                ]
              }
            ]
          }
        ]
      }
    """.trimIndent()

    // Verified at runtime: the concrete exception is
    // kotlinx.serialization.json.internal.JsonDecodingException, an internal implementation type
    // (package `kotlinx.serialization.json.internal`) not part of kotlinx.serialization's public
    // API. We assert against its public, stable supertype, kotlinx.serialization.SerializationException,
    // which is the actual documented contract for "this JSON could not be decoded".
    assertFailsWith<kotlinx.serialization.SerializationException> {
      parseReverseIr(json)
    }
  }
}
