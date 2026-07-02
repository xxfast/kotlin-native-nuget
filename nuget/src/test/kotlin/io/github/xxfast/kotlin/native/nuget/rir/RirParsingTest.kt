package io.github.xxfast.kotlin.native.nuget.rir

import kotlin.test.Test
import kotlin.test.assertEquals
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
}
