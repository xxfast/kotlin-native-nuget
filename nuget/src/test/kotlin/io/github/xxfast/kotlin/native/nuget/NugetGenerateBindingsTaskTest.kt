package io.github.xxfast.kotlin.native.nuget

import io.github.xxfast.kotlin.native.nuget.rir.RirAssembly
import io.github.xxfast.kotlin.native.nuget.rir.RirClass
import io.github.xxfast.kotlin.native.nuget.rir.RirFile
import io.github.xxfast.kotlin.native.nuget.rir.RirMethod
import io.github.xxfast.kotlin.native.nuget.rir.RirNamespace
import io.github.xxfast.kotlin.native.nuget.rir.RirParameter
import io.github.xxfast.kotlin.native.nuget.rir.RirPrimitiveType
import io.github.xxfast.kotlin.native.nuget.rir.RirStringType
import io.github.xxfast.kotlin.native.nuget.rir.RirVoidType
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for the pure generator function that converts a [RirFile] into Kotlin stub source
 * files. Tests assert on the text content and relative paths of the generated [GeneratedFile]
 * list — no Kotlin/Native compilation or dotnet invocation is required.
 *
 * ADR-048: object-per-type stubs, two-file split per type, nativeMain source layout.
 */
class NugetGenerateBindingsTaskTest {

  // ------------------------------------------------------------------
  // Shared fixture: Newtonsoft.Json.JsonConvert with SerializeObject(int): string
  // This is the worked example from ADR-048 §"Full generated artifact example".
  // ------------------------------------------------------------------

  private val jsonConvertRir: RirFile = RirFile(
    assemblies = listOf(
      RirAssembly(
        packageId = "Newtonsoft.Json",
        assemblyName = "Newtonsoft.Json",
        namespaces = listOf(
          RirNamespace(
            name = "Newtonsoft.Json",
            types = listOf(
              RirClass(
                name = "JsonConvert",
                methods = listOf(
                  RirMethod(
                    name = "SerializeObject",
                    isStatic = true,
                    returnType = RirStringType,
                    parameters = listOf(
                      RirParameter(name = "value", type = RirPrimitiveType("int")),
                    ),
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    ),
  )

  // ------------------------------------------------------------------
  // 1. Stub file (JsonConvert.kt) content
  // ------------------------------------------------------------------

  @Test
  fun `generated stub file declares object JsonConvert`() {
    val files: List<GeneratedFile> = generateKotlinStubs(jsonConvertRir)

    val stub: GeneratedFile = files.single { it.relativePath.endsWith("JsonConvert.kt") }
    assertContains(stub.content, "object JsonConvert")
  }

  @Test
  fun `generated stub file declares package newtonsoft dot json`() {
    val files: List<GeneratedFile> = generateKotlinStubs(jsonConvertRir)

    val stub: GeneratedFile = files.single { it.relativePath.endsWith("JsonConvert.kt") }
    assertContains(stub.content, "package newtonsoft.json")
  }

  @Test
  fun `generated stub contains fun serializeObject with Int parameter`() {
    val files: List<GeneratedFile> = generateKotlinStubs(jsonConvertRir)

    val stub: GeneratedFile = files.single { it.relativePath.endsWith("JsonConvert.kt") }
    assertContains(stub.content, "fun serializeObject(value: Int)")
  }

  @Test
  fun `generated stub return type for string-returning method is String`() {
    val files: List<GeneratedFile> = generateKotlinStubs(jsonConvertRir)

    val stub: GeneratedFile = files.single { it.relativePath.endsWith("JsonConvert.kt") }
    assertContains(stub.content, "): String")
  }

  // ------------------------------------------------------------------
  // 2. Bindings file (JsonConvertBindings.kt) content
  // ------------------------------------------------------------------

  @Test
  fun `generated bindings file declares CName export nuget_newtonsoft_json_json_convert_register`() {
    val files: List<GeneratedFile> = generateKotlinStubs(jsonConvertRir)

    val bindings: GeneratedFile = files.single { it.relativePath.endsWith("JsonConvertBindings.kt") }
    assertContains(bindings.content, "@CName(\"nuget_newtonsoft_json_json_convert_register\")")
  }

  @Test
  fun `generated bindings file declares serializeObjectFn function pointer variable`() {
    val files: List<GeneratedFile> = generateKotlinStubs(jsonConvertRir)

    val bindings: GeneratedFile = files.single { it.relativePath.endsWith("JsonConvertBindings.kt") }
    assertContains(bindings.content, "serializeObjectFn")
  }

  @Test
  fun `generated bindings file declares package newtonsoft dot json`() {
    val files: List<GeneratedFile> = generateKotlinStubs(jsonConvertRir)

    val bindings: GeneratedFile = files.single { it.relativePath.endsWith("JsonConvertBindings.kt") }
    assertContains(bindings.content, "package newtonsoft.json")
  }

  // ------------------------------------------------------------------
  // 3. File layout: both files land under nativeMain/{package path}
  // ------------------------------------------------------------------

  @Test
  fun `stub file lands under nativeMain newtonsoft json`() {
    val files: List<GeneratedFile> = generateKotlinStubs(jsonConvertRir)

    val stub: GeneratedFile = files.single { it.relativePath.endsWith("JsonConvert.kt") }
    assertTrue(
      stub.relativePath.startsWith("nativeMain/newtonsoft/json/"),
      "stub must be under nativeMain/newtonsoft/json/ but was '${stub.relativePath}'",
    )
  }

  @Test
  fun `bindings file lands under nativeMain newtonsoft json`() {
    val files: List<GeneratedFile> = generateKotlinStubs(jsonConvertRir)

    val bindings: GeneratedFile = files.single { it.relativePath.endsWith("JsonConvertBindings.kt") }
    assertTrue(
      bindings.relativePath.startsWith("nativeMain/newtonsoft/json/"),
      "bindings must be under nativeMain/newtonsoft/json/ but was '${bindings.relativePath}'",
    )
  }

  // ------------------------------------------------------------------
  // 4. Type mapping: void → Unit
  // ------------------------------------------------------------------

  @Test
  fun `void return type maps to Unit in stub`() {
    val rir: RirFile = RirFile(
      assemblies = listOf(
        RirAssembly(
          packageId = "Acme.Lib",
          assemblyName = "Acme.Lib",
          namespaces = listOf(
            RirNamespace(
              name = "Acme.Lib",
              types = listOf(
                RirClass(
                  name = "Foo",
                  methods = listOf(
                    RirMethod(
                      name = "DoIt",
                      isStatic = true,
                      returnType = RirVoidType,
                      parameters = emptyList(),
                    ),
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    )

    val files: List<GeneratedFile> = generateKotlinStubs(rir)

    val stub: GeneratedFile = files.single { it.relativePath.endsWith("Foo.kt") }
    assertContains(stub.content, "fun doIt()")
  }

  // ------------------------------------------------------------------
  // 5. packageNameOverrides: default derivation and override
  // ------------------------------------------------------------------

  @Test
  fun `namespace is derived to lowercase dot-separated package by default`() {
    val files: List<GeneratedFile> = generateKotlinStubs(jsonConvertRir)

    val stub: GeneratedFile = files.single { it.relativePath.endsWith("JsonConvert.kt") }
    assertContains(stub.content, "package newtonsoft.json")
  }

  @Test
  fun `packageNameOverride replaces default package derivation`() {
    val files: List<GeneratedFile> = generateKotlinStubs(
      file = jsonConvertRir,
      packageNameOverrides = mapOf("Newtonsoft.Json" to "json"),
    )

    val stub: GeneratedFile = files.single { it.relativePath.endsWith("JsonConvert.kt") }
    assertContains(stub.content, "package json")
    assertTrue(
      stub.relativePath.startsWith("nativeMain/json/"),
      "With override package 'json' stub must be under nativeMain/json/ but was '${stub.relativePath}'",
    )
  }

  // ------------------------------------------------------------------
  // 6. NugetInterop.kt helper is emitted when any method returns String
  // ------------------------------------------------------------------

  @Test
  fun `NugetInterop expect file is generated when a method has string return type`() {
    val files: List<GeneratedFile> = generateKotlinStubs(jsonConvertRir)

    val interop: GeneratedFile? = files.find {
      it.relativePath.endsWith("NugetInterop.kt") && it.relativePath.startsWith("nativeMain/")
    }
    assertNotNull(interop, "NugetInterop.kt must be generated in nativeMain when string return type is present")
    assertContains(interop.content, "expect fun freeManagedString")
  }
}
