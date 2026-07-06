package io.github.xxfast.kotlin.native.nuget

import io.github.xxfast.kotlin.native.nuget.rir.RirAssembly
import io.github.xxfast.kotlin.native.nuget.rir.RirClass
import io.github.xxfast.kotlin.native.nuget.rir.RirFile
import io.github.xxfast.kotlin.native.nuget.rir.RirMethod
import io.github.xxfast.kotlin.native.nuget.rir.RirNamespace
import io.github.xxfast.kotlin.native.nuget.rir.RirObjectHandleType
import io.github.xxfast.kotlin.native.nuget.rir.RirParameter
import io.github.xxfast.kotlin.native.nuget.rir.RirPrimitiveType
import io.github.xxfast.kotlin.native.nuget.rir.RirStringType
import io.github.xxfast.kotlin.native.nuget.rir.RirVoidType
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
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

  // ------------------------------------------------------------------
  // ADR-051: C# objects as opaque handles — Kotlin class wrapper generation
  //
  // Canonical fixture: Sample.Text.Template — public non-static class with two static methods:
  //   Parse(source: string): Template  and  Render(template: Template, name: string): string
  //
  // NOTE: this fixture references RirObjectHandleType (not yet in RirModel.kt) and the isStatic
  // field on RirClass (not yet added). Both produce compile errors until the model is extended.
  // That compile error is the expected "red state" for this test section (ADR-051 Step 3).
  // ------------------------------------------------------------------

  // ADR-051 canonical fixture shared across all handle-type binding tests.
  private val templateRir: RirFile = RirFile(
    assemblies = listOf(
      RirAssembly(
        packageId = "Sample.Text",
        assemblyName = "Sample.Text",
        namespaces = listOf(
          RirNamespace(
            name = "Sample.Text",
            types = listOf(
              RirClass(
                name = "Template",
                isStatic = false,
                methods = listOf(
                  RirMethod(
                    name = "Parse",
                    isStatic = true,
                    returnType = RirObjectHandleType(namespace = "Sample.Text", name = "Template"),
                    parameters = listOf(
                      RirParameter(name = "source", type = RirStringType),
                    ),
                  ),
                  RirMethod(
                    name = "Render",
                    isStatic = true,
                    returnType = RirStringType,
                    parameters = listOf(
                      RirParameter(
                        name = "template",
                        type = RirObjectHandleType(namespace = "Sample.Text", name = "Template"),
                      ),
                      RirParameter(name = "name", type = RirStringType),
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

  // Separate fixture for verifying that static C# classes (isStatic=true) still emit `object`.
  private val staticClassRir: RirFile = RirFile(
    assemblies = listOf(
      RirAssembly(
        packageId = "Acme.Lib",
        assemblyName = "Acme.Lib",
        namespaces = listOf(
          RirNamespace(
            name = "Acme.Lib",
            types = listOf(
              RirClass(
                name = "MathHelper",
                isStatic = true,
                methods = listOf(
                  RirMethod(
                    name = "Add",
                    isStatic = true,
                    returnType = RirPrimitiveType("int"),
                    parameters = listOf(
                      RirParameter(name = "a", type = RirPrimitiveType("int")),
                      RirParameter(name = "b", type = RirPrimitiveType("int")),
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
  // 7. Non-static class renders as `class` (not `object`) wrapper
  // ------------------------------------------------------------------

  @Test
  fun `non-static class Template renders as class not object`() {
    val files: List<GeneratedFile> = generateKotlinStubs(templateRir)

    val stub: GeneratedFile = files.single { it.relativePath.endsWith("Template.kt") }
    assertContains(stub.content, "class Template")
    assertFalse(
      stub.content.contains("object Template"),
      "non-static class must render as `class`, not `object`",
    )
  }

  @Test
  fun `Template class has internal constructor taking COpaquePointer`() {
    val files: List<GeneratedFile> = generateKotlinStubs(templateRir)

    val stub: GeneratedFile = files.single { it.relativePath.endsWith("Template.kt") }
    assertContains(stub.content, "internal constructor(handle: COpaquePointer)")
  }

  @Test
  fun `Template class stores NugetObjectHandle as internal field`() {
    val files: List<GeneratedFile> = generateKotlinStubs(templateRir)

    val stub: GeneratedFile = files.single { it.relativePath.endsWith("Template.kt") }
    assertContains(stub.content, "internal val handle: NugetObjectHandle")
  }

  @Test
  fun `Template class implements AutoCloseable with close method`() {
    val files: List<GeneratedFile> = generateKotlinStubs(templateRir)

    val stub: GeneratedFile = files.single { it.relativePath.endsWith("Template.kt") }
    assertContains(stub.content, "AutoCloseable")
    assertContains(stub.content, "override fun close()")
  }

  @Test
  fun `Template class has createCleaner call for automatic GCHandle release`() {
    val files: List<GeneratedFile> = generateKotlinStubs(templateRir)

    val stub: GeneratedFile = files.single { it.relativePath.endsWith("Template.kt") }
    assertContains(stub.content, "createCleaner")
  }

  @Test
  fun `Template static methods are in companion object`() {
    val files: List<GeneratedFile> = generateKotlinStubs(templateRir)

    val stub: GeneratedFile = files.single { it.relativePath.endsWith("Template.kt") }
    assertContains(stub.content, "companion object")
  }

  // ------------------------------------------------------------------
  // 8. parse returns nullable Template (IntPtr.Zero → null)
  // ------------------------------------------------------------------

  @Test
  fun `parse function returns nullable Template`() {
    val files: List<GeneratedFile> = generateKotlinStubs(templateRir)

    val stub: GeneratedFile = files.single { it.relativePath.endsWith("Template.kt") }
    assertContains(
      stub.content,
      "fun parse(source: String): Template?",
      message = "parse must return Template? — IntPtr.Zero maps to null (ADR-051 §Nullability)",
    )
  }

  // ------------------------------------------------------------------
  // 9. render takes Template and calls handle.require
  // ------------------------------------------------------------------

  @Test
  fun `render function takes Template as first parameter`() {
    val files: List<GeneratedFile> = generateKotlinStubs(templateRir)

    val stub: GeneratedFile = files.single { it.relativePath.endsWith("Template.kt") }
    assertContains(stub.content, "fun render(template: Template, name: String): String")
  }

  @Test
  fun `render function calls template handle require to extract the raw pointer`() {
    val files: List<GeneratedFile> = generateKotlinStubs(templateRir)

    val stub: GeneratedFile = files.single { it.relativePath.endsWith("Template.kt") }
    assertContains(
      stub.content,
      "template.handle.require(\"Template\")",
      message = "render must call handle.require(\"Template\") to unwrap the opaque pointer (ADR-051)",
    )
  }

  // ------------------------------------------------------------------
  // 10. NugetRuntime.kt is emitted in the internal package when handle types are present
  // ------------------------------------------------------------------

  @Test
  fun `NugetRuntime kt is emitted in nativeMain internal package when handle types are present`() {
    val files: List<GeneratedFile> = generateKotlinStubs(templateRir)

    val runtime: GeneratedFile? = files.find {
      it.relativePath.endsWith("NugetRuntime.kt") && it.relativePath.startsWith("nativeMain/")
    }
    assertNotNull(
      runtime,
      "NugetRuntime.kt must be generated under nativeMain/ when handle types appear in the IR",
    )
  }

  @Test
  fun `NugetRuntime kt declares CName export nuget_runtime_register`() {
    val files: List<GeneratedFile> = generateKotlinStubs(templateRir)

    val runtime: GeneratedFile = requireNotNull(
      files.find { it.relativePath.endsWith("NugetRuntime.kt") }
    ) { "NugetRuntime.kt must be generated" }

    assertContains(runtime.content, "@CName(\"nuget_runtime_register\")")
  }

  @Test
  fun `NugetRuntime kt declares NugetObjectHandle class`() {
    val files: List<GeneratedFile> = generateKotlinStubs(templateRir)

    val runtime: GeneratedFile = requireNotNull(
      files.find { it.relativePath.endsWith("NugetRuntime.kt") }
    ) { "NugetRuntime.kt must be generated" }

    assertContains(runtime.content, "class NugetObjectHandle")
  }

  @Test
  fun `NugetRuntime kt declares freeGcHandleFn variable`() {
    val files: List<GeneratedFile> = generateKotlinStubs(templateRir)

    val runtime: GeneratedFile = requireNotNull(
      files.find { it.relativePath.endsWith("NugetRuntime.kt") }
    ) { "NugetRuntime.kt must be generated" }

    assertContains(runtime.content, "freeGcHandleFn")
  }

  @Test
  fun `NugetRuntime kt is in the internal package`() {
    val files: List<GeneratedFile> = generateKotlinStubs(templateRir)

    val runtime: GeneratedFile = requireNotNull(
      files.find { it.relativePath.endsWith("NugetRuntime.kt") }
    ) { "NugetRuntime.kt must be generated" }

    assertContains(runtime.content, "package io.github.xxfast.kotlin.native.nuget.internal")
  }

  // Guard: NugetRuntime.kt must NOT be emitted for statics-only IR (uses the existing fixture,
  // compiles today, must stay green before and after implementation).
  @Test
  fun `NugetRuntime kt is not emitted for statics-only IR`() {
    val files: List<GeneratedFile> = generateKotlinStubs(jsonConvertRir)

    assertTrue(
      files.none { it.relativePath.endsWith("NugetRuntime.kt") },
      "NugetRuntime.kt must not be emitted when no handle types are present in the IR",
    )
  }

  // ------------------------------------------------------------------
  // 11. Static C# class (isStatic=true) still renders as `object` (ADR-048 shape unchanged)
  // ------------------------------------------------------------------

  @Test
  fun `static class with isStatic true still renders as object`() {
    val files: List<GeneratedFile> = generateKotlinStubs(staticClassRir)

    val stub: GeneratedFile = files.single { it.relativePath.endsWith("MathHelper.kt") }
    assertContains(stub.content, "object MathHelper")
    assertFalse(
      stub.content.contains("class MathHelper") && !stub.content.contains("object MathHelper"),
      "static class (isStatic=true) must keep the ADR-048 `object` shape",
    )
  }
}
