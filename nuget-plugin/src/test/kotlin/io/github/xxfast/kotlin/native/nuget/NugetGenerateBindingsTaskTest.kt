package io.github.xxfast.kotlin.native.nuget

import io.github.xxfast.kotlin.native.nuget.rir.RirAssembly
import io.github.xxfast.kotlin.native.nuget.rir.RirClass
import io.github.xxfast.kotlin.native.nuget.rir.RirConstructor
import io.github.xxfast.kotlin.native.nuget.rir.RirEnum
import io.github.xxfast.kotlin.native.nuget.rir.RirEnumEntry
import io.github.xxfast.kotlin.native.nuget.rir.RirEnumType
import io.github.xxfast.kotlin.native.nuget.rir.RirFile
import io.github.xxfast.kotlin.native.nuget.rir.RirMethod
import io.github.xxfast.kotlin.native.nuget.rir.RirNamespace
import io.github.xxfast.kotlin.native.nuget.rir.RirObjectHandleType
import io.github.xxfast.kotlin.native.nuget.rir.RirParameter
import io.github.xxfast.kotlin.native.nuget.rir.RirPrimitiveType
import io.github.xxfast.kotlin.native.nuget.rir.RirProperty
import io.github.xxfast.kotlin.native.nuget.rir.RirStringType
import io.github.xxfast.kotlin.native.nuget.rir.RirVoidType
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
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
                properties = listOf(
                  RirProperty(
                    name = "DefaultValue",
                    type = RirPrimitiveType("int"),
                    isReadOnly = false,
                    isStatic = true,
                  ),
                  RirProperty(
                    name = "Version",
                    type = RirStringType,
                    isReadOnly = true,
                    isStatic = true,
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

  @Test
  fun `static class properties render as bridge-backed val and var on its object`() {
    val files: List<GeneratedFile> = generateKotlinStubs(staticClassRir)
    val stub: GeneratedFile = files.single { it.relativePath.endsWith("MathHelper.kt") }

    assertContains(stub.content, "var defaultValue: Int")
    assertContains(stub.content, "val version: String")
    assertContains(stub.content, "get()")
    assertContains(stub.content, "set(value)")
  }

  // ------------------------------------------------------------------
  // ADR-052: C# instance constructors in Kotlin — `new Foo(...)` maps to a Kotlin secondary
  // `constructor`, backed by a file-private `construct(...)` helper.
  //
  // Canonical fixture: Sample.Text.Template — public non-static class with:
  //   - one public instance constructor: Template(string source)
  //   - the existing ADR-051 statics: Parse(source: string): Template, Render(template, name): string
  // so both the constructor path and the pre-existing static-handle path are exercised together,
  // and constructor-pointer-before-method-pointer ordering (ADR-052 "shared bridgeable ordering")
  // is observable.
  //
  // NOTE: this fixture references RirClass.constructors / RirConstructor, which do not yet drive
  // any generation logic in NugetGenerateBindingsTask.kt (ADR-052 Step 3 "red" state) — the
  // fixture itself compiles (RirModel.kt was extended additively), but the assertions below fail
  // because the generator does not yet emit the secondary constructor, the `construct(...)`
  // helper, the `ctorFn` pointer variable, or the leading `ctorPtr` register parameter.
  // ------------------------------------------------------------------

  private val templateWithCtorRir: RirFile = RirFile(
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
                constructors = listOf(
                  RirConstructor(
                    parameters = listOf(
                      RirParameter(name = "source", type = RirStringType),
                    ),
                  ),
                ),
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

  // ------------------------------------------------------------------
  // 12. {TypeName}.kt: secondary constructor delegating through the `construct(...)` helper
  // ------------------------------------------------------------------

  @Test
  fun `Template kt declares secondary constructor delegating to construct helper`() {
    val files: List<GeneratedFile> = generateKotlinStubs(templateWithCtorRir)

    val stub: GeneratedFile = files.single { it.relativePath.endsWith("Template.kt") }
    assertContains(
      stub.content,
      "constructor(source: String) : this(construct(source))",
      message = "ADR-052: a public C# instance constructor must map to a Kotlin secondary " +
        "constructor delegating through the file-private construct(...) helper",
    )
  }

  @Test
  fun `Template kt declares file-private construct helper returning COpaquePointer`() {
    val files: List<GeneratedFile> = generateKotlinStubs(templateWithCtorRir)

    val stub: GeneratedFile = files.single { it.relativePath.endsWith("Template.kt") }
    assertContains(
      stub.content,
      "private fun construct(source: String): COpaquePointer",
      message = "ADR-052: the construct(...) helper must be file-private and return the raw, " +
        "non-null handle",
    )
  }

  @Test
  fun `construct helper requireNotNulls the returned handle as a non-null constructor contract`() {
    val files: List<GeneratedFile> = generateKotlinStubs(templateWithCtorRir)

    val stub: GeneratedFile = files.single { it.relativePath.endsWith("Template.kt") }
    // The construct(...) helper body must requireNotNull the handle returned from ctorFn — a
    // constructor never returns null (ADR-052), unlike the nullable Foo? factory return path.
    val constructBody: String = stub.content.substringAfter(
      delimiter = "private fun construct(source: String): COpaquePointer",
      missingDelimiterValue = "",
    )
    assertContains(
      constructBody,
      "requireNotNull",
      message = "ADR-052: construct(...) must requireNotNull the ctor thunk's returned handle " +
        "(a null handle from a C# constructor is a bridge-invariant violation, not a legitimate value)",
    )
  }

  // ------------------------------------------------------------------
  // 13. {TypeName}Bindings.kt: ctorFn pointer var + leading ctorPtr register parameter
  // ------------------------------------------------------------------

  @Test
  fun `TemplateBindings kt declares ctorFn function pointer variable`() {
    val files: List<GeneratedFile> = generateKotlinStubs(templateWithCtorRir)

    val bindings: GeneratedFile = files.single { it.relativePath.endsWith("TemplateBindings.kt") }
    assertContains(
      bindings.content,
      "ctorFn",
      message = "ADR-052: TemplateBindings.kt must declare a ctorFn function pointer variable",
    )
  }

  @Test
  fun `TemplateBindings kt register export takes ctorPtr as its first parameter`() {
    val files: List<GeneratedFile> = generateKotlinStubs(templateWithCtorRir)

    val bindings: GeneratedFile = files.single { it.relativePath.endsWith("TemplateBindings.kt") }
    val registerSignature: String = bindings.content
      .substringAfter("fun nuget_sample_text_template_register(")
      .substringBefore(") {")

    val ctorIndex: Int = registerSignature.indexOf("ctorPtr")
    val parseIndex: Int = registerSignature.indexOf("parsePtr")
    val renderIndex: Int = registerSignature.indexOf("renderPtr")

    assertTrue(
      ctorIndex >= 0 && parseIndex >= 0 && renderIndex >= 0,
      "ctorPtr, parsePtr, and renderPtr must all be declared in the register signature, got: " +
        "'$registerSignature'",
    )
    assertTrue(
      ctorIndex < parseIndex && ctorIndex < renderIndex,
      "ADR-052 shared bridgeable ordering: ctorPtr must come first, before the static-method " +
        "pointers — got signature '$registerSignature'",
    )
  }

  @Test
  fun `TemplateBindings kt register body assigns ctorFn from ctorPtr`() {
    val files: List<GeneratedFile> = generateKotlinStubs(templateWithCtorRir)

    val bindings: GeneratedFile = files.single { it.relativePath.endsWith("TemplateBindings.kt") }
    assertContains(
      bindings.content,
      "ctorFn = ctorPtr.reinterpret()",
      message = "ADR-052: the register body must assign ctorFn from the reinterpreted ctorPtr",
    )
  }

  // ------------------------------------------------------------------
  // Phase 9 (ROADMAP line 151): C# instance methods and instance properties in Kotlin —
  // confirmed "mirror" item, no new ADR. "An instance thunk is a static thunk whose first
  // parameter is the receiver handle" (ADR-051 Deferred section). Reuses the ADR-051 handle +
  // wrapper machinery unchanged.
  //
  // Canonical fixture: Sample.Text.Template — public non-static class with:
  //   - a public instance constructor Template(string source)                (ADR-052, unchanged)
  //   - static Parse(source: string): Template                               (ADR-051, unchanged)
  //   - instance method Rename(newName: string): void
  //   - instance method Clone(): Template                                    (handle-typed return)
  //   - instance property Name: string { get; }                              (read-only)
  //   - instance property Length: int { get; set; }                         (settable, primitive)
  //   - instance property Parent: Template { get; set; }                    (settable, handle-typed
  //     — rule 4: must render as `val parent: Template?`, no setter, even though isReadOnly=false)
  //
  // NOTE: RirRegistrable.PropertyGetter/PropertySetter and bridgeableProperties() exist only as
  // stubs in RirBridging.kt at this step (Phase 9 line 151 Step 3 "red" state) — the generator
  // does not yet render any instance method as a member function or any instance property at all,
  // so the assertions in this section are expected to fail until the follow-up round.
  // ------------------------------------------------------------------

  private val templateInstanceRir: RirFile = RirFile(
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
                constructors = listOf(
                  RirConstructor(
                    parameters = listOf(RirParameter(name = "source", type = RirStringType)),
                  ),
                ),
                methods = listOf(
                  RirMethod(
                    name = "Parse",
                    isStatic = true,
                    returnType = RirObjectHandleType(namespace = "Sample.Text", name = "Template"),
                    parameters = listOf(RirParameter(name = "source", type = RirStringType)),
                  ),
                  RirMethod(
                    name = "Rename",
                    isStatic = false,
                    returnType = RirVoidType,
                    parameters = listOf(RirParameter(name = "newName", type = RirStringType)),
                  ),
                  RirMethod(
                    name = "Clone",
                    isStatic = false,
                    returnType = RirObjectHandleType(namespace = "Sample.Text", name = "Template"),
                    parameters = emptyList(),
                  ),
                ),
                properties = listOf(
                  RirProperty(name = "Name", type = RirStringType, isReadOnly = true, isStatic = false),
                  RirProperty(
                    name = "Length",
                    type = RirPrimitiveType("int"),
                    isReadOnly = false,
                    isStatic = false,
                  ),
                  RirProperty(
                    name = "Parent",
                    type = RirObjectHandleType(namespace = "Sample.Text", name = "Template"),
                    isReadOnly = false,
                    isStatic = false,
                  ),
                  RirProperty(
                    name = "DefaultName",
                    type = RirStringType,
                    isReadOnly = false,
                    isStatic = true,
                  ),
                  RirProperty(
                    name = "RenderCount",
                    type = RirPrimitiveType("int"),
                    isReadOnly = true,
                    isStatic = true,
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
  // 14. Instance method renders as a member function outside the companion object, passing
  //     handle.require("Template") as the receiver — the static method stays in companion.
  // ------------------------------------------------------------------

  @Test
  fun `instance method Rename renders as a member function outside the companion object`() {
    val files: List<GeneratedFile> = generateKotlinStubs(templateInstanceRir)
    val stub: GeneratedFile = files.single { it.relativePath.endsWith("Template.kt") }

    assertContains(stub.content, "fun rename(newName: String)")

    val companionStart: Int = stub.content.indexOf("companion object")
    val renameIndex: Int = stub.content.indexOf("fun rename(")
    assertTrue(
      companionStart < 0 || renameIndex < companionStart,
      "instance method rename must be a class member, declared before/outside companion object " +
        "— got companionStart=$companionStart, renameIndex=$renameIndex",
    )
  }

  @Test
  fun `instance method Rename passes handle require as the first invoke argument (the receiver)`() {
    val files: List<GeneratedFile> = generateKotlinStubs(templateInstanceRir)
    val stub: GeneratedFile = files.single { it.relativePath.endsWith("Template.kt") }

    assertContains(
      stub.content,
      "handle.require(\"Template\")",
      message = "Phase 9 line 151: an instance method call must prepend the receiver via " +
        "handle.require(...) as the first fn.invoke(...) argument (the ADR-051 insight: an " +
        "instance thunk is a static thunk whose first parameter is the receiver handle)",
    )
  }

  @Test
  fun `static method Parse still renders inside companion object`() {
    val files: List<GeneratedFile> = generateKotlinStubs(templateInstanceRir)
    val stub: GeneratedFile = files.single { it.relativePath.endsWith("Template.kt") }

    val companionBody: String = stub.content.substringAfter("companion object {")
    assertContains(companionBody, "fun parse(source: String): Template?")
  }

  // ------------------------------------------------------------------
  // 14b. TemplateBindings.kt: the CFunction *type* declaration for an instance-method fn-pointer
  // variable must carry the same receiver arity as the Template.kt call site — otherwise
  // fn.invoke(receiver, ...args) at the call site has more arguments than the CFunction type
  // declares, and the generated Kotlin fails to compile ("Too many arguments for ... invoke").
  // Regression test for exactly that arity-drift bug between the two ADR-048 split files.
  // ------------------------------------------------------------------

  @Test
  fun `TemplateBindings kt fn pointer type for a zero-arg instance method has receiver-only CFunction arity`() {
    val files: List<GeneratedFile> = generateKotlinStubs(templateInstanceRir)
    val bindings: GeneratedFile = files.single { it.relativePath.endsWith("TemplateBindings.kt") }

    assertContains(
      bindings.content,
      "internal var cloneFn: CPointer<CFunction<(COpaquePointer?) -> COpaquePointer?>>? = null",
      message = "a zero-C#-parameter instance method's CFunction type must still declare one " +
        "COpaquePointer? parameter for the receiver — Clone() call site passes " +
        "handle.require(\"Template\") as its only fn.invoke(...) argument",
    )
  }

  @Test
  fun `TemplateBindings kt fn pointer type for a one-arg instance method has receiver plus param CFunction arity`() {
    val files: List<GeneratedFile> = generateKotlinStubs(templateInstanceRir)
    val bindings: GeneratedFile = files.single { it.relativePath.endsWith("TemplateBindings.kt") }

    assertContains(
      bindings.content,
      "internal var renameFn: CPointer<CFunction<(COpaquePointer?, COpaquePointer?) -> Unit>>? = null",
      message = "a one-C#-parameter instance method's CFunction type must declare two " +
        "COpaquePointer? parameters (receiver, then newName) — Rename(newName) call site passes " +
        "handle.require(\"Template\") followed by newName.cstr.ptr to fn.invoke(...)",
    )
  }

  @Test
  fun `TemplateBindings kt fn pointer type for a one-arg static method has no receiver slot`() {
    val files: List<GeneratedFile> = generateKotlinStubs(templateInstanceRir)
    val bindings: GeneratedFile = files.single { it.relativePath.endsWith("TemplateBindings.kt") }

    assertContains(
      bindings.content,
      "internal var parseFn: CPointer<CFunction<(COpaquePointer?) -> COpaquePointer?>>? = null",
      message = "a static method's CFunction type must NOT gain a receiver slot — Parse(source) " +
        "has exactly one C# parameter and no receiver, regression guard against over-applying " +
        "the instance-method receiver fix",
    )
  }

  // ------------------------------------------------------------------
  // 15. Read-only instance property -> val x: T get() = ...
  //     Settable primitive instance property -> var x: T with get()/set(value)
  // ------------------------------------------------------------------

  @Test
  fun `read-only instance property Name renders as val with an explicit get`() {
    val files: List<GeneratedFile> = generateKotlinStubs(templateInstanceRir)
    val stub: GeneratedFile = files.single { it.relativePath.endsWith("Template.kt") }

    assertContains(
      stub.content,
      "val name: String",
      message = "a read-only bridge-backed property cannot be a stored val — it must declare an " +
        "explicit get()",
    )
    assertContains(stub.content, "get()")
  }

  @Test
  fun `settable primitive instance property Length renders as var with get and set`() {
    val files: List<GeneratedFile> = generateKotlinStubs(templateInstanceRir)
    val stub: GeneratedFile = files.single { it.relativePath.endsWith("Template.kt") }

    assertContains(stub.content, "var length: Int")
    assertContains(stub.content, "set(value)")
  }

  // ------------------------------------------------------------------
  // 16. Rule 4 (human-approved v1 scope call): a handle-typed settable property renders as
  //     read-only `val x: Foo?`, never `var`, even though the RIR says isReadOnly=false.
  // ------------------------------------------------------------------

  @Test
  fun `handle-typed instance property Parent renders as read-only val Template nullable even though settable in RIR`() {
    val files: List<GeneratedFile> = generateKotlinStubs(templateInstanceRir)
    val stub: GeneratedFile = files.single { it.relativePath.endsWith("Template.kt") }

    assertContains(
      stub.content,
      "val parent: Template?",
      message = "rule 4: a handle-typed settable property must render as val, not var, in v1 " +
        "(a Kotlin var's getter/setter must share one type, but object returns are Foo? and " +
        "object params are non-null Foo)",
    )
    assertFalse(
      stub.content.contains("var parent"),
      "handle-typed property Parent must never render as var, even though RIR isReadOnly=false",
    )
  }

  // ------------------------------------------------------------------
  // 16b. Static C# properties share the property ABI without a receiver. They live in the
  // companion object for ordinary C# classes, while static C# classes use their existing object
  // shape (covered above). Their registration slots append after all instance property slots.
  // ------------------------------------------------------------------

  @Test
  fun `static properties DefaultName and RenderCount render inside Template companion object`() {
    val files: List<GeneratedFile> = generateKotlinStubs(templateInstanceRir)
    val stub: GeneratedFile = files.single { it.relativePath.endsWith("Template.kt") }
    val companionBody: String = stub.content.substringAfter("companion object {")

    assertContains(companionBody, "var defaultName: String")
    assertContains(companionBody, "val renderCount: Int")
    assertContains(companionBody, "set(value)")
  }

  @Test
  fun `static property function pointers have no receiver parameter`() {
    val files: List<GeneratedFile> = generateKotlinStubs(templateInstanceRir)
    val bindings: GeneratedFile = files.single { it.relativePath.endsWith("TemplateBindings.kt") }

    assertContains(
      bindings.content,
      "internal var defaultNameGetterFn: CPointer<CFunction<() -> COpaquePointer?>>? = null",
    )
    assertContains(
      bindings.content,
      "internal var defaultNameSetterFn: CPointer<CFunction<(COpaquePointer?) -> Unit>>? = null",
    )
    assertContains(
      bindings.content,
      "internal var renderCountGetterFn: CPointer<CFunction<() -> Int>>? = null",
    )
  }

  // ------------------------------------------------------------------
  // 17. Instance method returning a handle type returns Foo? (same nullability rule as ADR-051
  //     static factory returns — IntPtr.Zero maps to null).
  // ------------------------------------------------------------------

  @Test
  fun `instance method Clone returning a handle type returns nullable Template`() {
    val files: List<GeneratedFile> = generateKotlinStubs(templateInstanceRir)
    val stub: GeneratedFile = files.single { it.relativePath.endsWith("Template.kt") }

    assertContains(stub.content, "fun clone(): Template?")
  }

  // ------------------------------------------------------------------
  // 18. Rule 5 (human-approved v1 scope call): an instance member whose Kotlin name collides
  //     with the ADR-051 wrapper's own members (handle, close, cleaner) is skipped — statics are
  //     unaffected because they live in the companion object, a separate namespace.
  // ------------------------------------------------------------------

  private val collisionRir: RirFile = RirFile(
    assemblies = listOf(
      RirAssembly(
        packageId = "Sample.Text",
        assemblyName = "Sample.Text",
        namespaces = listOf(
          RirNamespace(
            name = "Sample.Text",
            types = listOf(
              RirClass(
                name = "Widget",
                isStatic = false,
                constructors = listOf(RirConstructor(parameters = emptyList())),
                methods = listOf(
                  // control: non-colliding, must survive the collision filter untouched.
                  RirMethod(name = "Reset", isStatic = false, returnType = RirVoidType, parameters = emptyList()),
                  // colliding: shadows the ADR-051 wrapper's own close().
                  RirMethod(name = "Close", isStatic = false, returnType = RirVoidType, parameters = emptyList()),
                  // safe: a static Close overload lives in the companion object.
                  RirMethod(
                    name = "Close",
                    isStatic = true,
                    returnType = RirVoidType,
                    parameters = listOf(RirParameter(name = "reason", type = RirStringType)),
                  ),
                ),
                properties = listOf(
                  // control: non-colliding, must survive the collision filter untouched.
                  RirProperty(name = "Label", type = RirStringType, isReadOnly = true, isStatic = false),
                  // colliding: shadows the ADR-051 wrapper's own `internal val handle` field.
                  RirProperty(name = "Handle", type = RirStringType, isReadOnly = true, isStatic = false),
                ),
              ),
            ),
          ),
        ),
      ),
    ),
  )

  @Test
  fun `colliding instance method Close is skipped while the non-colliding instance method Reset is kept`() {
    val files: List<GeneratedFile> = generateKotlinStubs(collisionRir)
    val stub: GeneratedFile = files.single { it.relativePath.endsWith("Widget.kt") }

    assertContains(
      stub.content,
      "fun reset()",
      message = "non-colliding instance method Reset must still be generated",
    )

    val closeDeclarations: Int = Regex("fun close\\(").findAll(stub.content).count()
    assertEquals(
      2,
      closeDeclarations,
      "expected exactly 2 `fun close(` declarations — the ADR-051 override close() and the " +
        "static Close(string) in companion object — the colliding instance Close() must be " +
        "skipped, got $closeDeclarations",
    )
  }

  @Test
  fun `colliding instance property Handle is skipped while the non-colliding property Label is kept`() {
    val files: List<GeneratedFile> = generateKotlinStubs(collisionRir)
    val stub: GeneratedFile = files.single { it.relativePath.endsWith("Widget.kt") }

    assertContains(
      stub.content,
      "val label: String",
      message = "non-colliding instance property Label must still be generated",
    )
    assertFalse(
      stub.content.contains("val handle: String"),
      "the colliding instance property Handle must not shadow the wrapper's own " +
        "internal val handle: NugetObjectHandle",
    )
  }

  @Test
  fun `static method named Close is not skipped and renders inside companion object`() {
    val files: List<GeneratedFile> = generateKotlinStubs(collisionRir)
    val stub: GeneratedFile = files.single { it.relativePath.endsWith("Widget.kt") }

    val companionBody: String = stub.content.substringAfter("companion object {")
    assertContains(companionBody, "fun close(reason: String)")
  }

  // ------------------------------------------------------------------
  // Phase 9: C# enums → standalone Kotlin enum classes (ordinal ABI).
  //
  // The metadata reader admits only default-int, unique contiguous enums, so the RIR records
  // the already-validated ordinal for each entry. Enums themselves need no registration table:
  // only the consuming C# class contributes method/property thunk slots.
  // ------------------------------------------------------------------

  private val moodRir: RirFile = RirFile(
    assemblies = listOf(
      RirAssembly(
        packageId = "Sample.Enums",
        assemblyName = "Sample.Enums",
        namespaces = listOf(
          RirNamespace(
            name = "Sample.Enums",
            types = listOf(
              RirEnum(
                name = "Mood",
                entries = listOf(
                  RirEnumEntry(name = "Happy", ordinal = 0),
                  RirEnumEntry(name = "Sleepy", ordinal = 1),
                  RirEnumEntry(name = "Grumpy", ordinal = 2),
                ),
              ),
              RirClass(
                name = "MoodService",
                isStatic = true,
                methods = listOf(
                  RirMethod(
                    name = "Next",
                    isStatic = true,
                    returnType = RirEnumType(namespace = "Sample.Enums", name = "Mood"),
                    parameters = listOf(
                      RirParameter(
                        name = "mood",
                        type = RirEnumType(namespace = "Sample.Enums", name = "Mood"),
                      ),
                    ),
                  ),
                ),
                properties = listOf(
                  RirProperty(
                    name = "DefaultMood",
                    type = RirEnumType(namespace = "Sample.Enums", name = "Mood"),
                    isReadOnly = false,
                    isStatic = true,
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    ),
  )

  @Test
  fun `C sharp enum renders as a standalone Kotlin enum class with screaming snake entries`() {
    val files: List<GeneratedFile> = generateKotlinStubs(moodRir)
    val enum: GeneratedFile = files.single { it.relativePath.endsWith("Mood.kt") }

    assertContains(enum.content, "enum class Mood")
    assertContains(enum.content, "HAPPY")
    assertContains(enum.content, "SLEEPY")
    assertContains(enum.content, "GRUMPY")
    assertTrue(
      files.none { it.relativePath.endsWith("MoodBindings.kt") },
      "an enum has no registration table or bindings file; it crosses the ABI as an ordinal Int",
    )
  }

  @Test
  fun `enum method signatures use Mood and marshal values through ordinal`() {
    val files: List<GeneratedFile> = generateKotlinStubs(moodRir)
    val stub: GeneratedFile = files.single { it.relativePath.endsWith("MoodService.kt") }
    val bindings: GeneratedFile = files.single { it.relativePath.endsWith("MoodServiceBindings.kt") }

    assertContains(stub.content, "fun next(mood: Mood): Mood")
    assertContains(stub.content, "fn.invoke(mood.ordinal)")
    assertContains(stub.content, "nugetEnumEntry(Mood.entries, ")
    assertContains(
      bindings.content,
      "internal var nextFn: CPointer<CFunction<(Int) -> Int>>? = null",
    )
  }

  @Test
  fun `enum property maps to Mood var and converts its getter and setter through ordinal`() {
    val files: List<GeneratedFile> = generateKotlinStubs(moodRir)
    val stub: GeneratedFile = files.single { it.relativePath.endsWith("MoodService.kt") }
    val bindings: GeneratedFile = files.single { it.relativePath.endsWith("MoodServiceBindings.kt") }

    assertContains(stub.content, "var defaultMood: Mood")
    assertContains(stub.content, "nugetEnumEntry(Mood.entries, ")
    assertContains(stub.content, "fn.invoke(value.ordinal)")
    assertContains(
      bindings.content,
      "internal var defaultMoodGetterFn: CPointer<CFunction<() -> Int>>? = null",
    )
    assertContains(
      bindings.content,
      "internal var defaultMoodSetterFn: CPointer<CFunction<(Int) -> Unit>>? = null",
    )
  }

  // ------------------------------------------------------------------
  // 15. Enum ordinals coming back from C# are bounds-checked (a C# enum is not a closed set:
  // `(Mood)99` is a legal C# value, so `Mood.entries[99]` must not be indexed blind)
  // ------------------------------------------------------------------

  @Test
  fun `enum returning method bounds checks the ordinal through the shared helper`() {
    val files: List<GeneratedFile> = generateKotlinStubs(moodRir)
    val stub: GeneratedFile = files.single { it.relativePath.endsWith("MoodService.kt") }

    assertContains(
      stub.content,
      "return nugetEnumEntry(Mood.entries, fn.invoke(mood.ordinal), \"Mood\")",
    )
    assertContains(
      stub.content,
      "import io.github.xxfast.kotlin.native.nuget.internal.nugetEnumEntry",
    )
  }

  @Test
  fun `NugetEnums kt is emitted in the internal package and fails fast on an unknown ordinal`() {
    val files: List<GeneratedFile> = generateKotlinStubs(moodRir)

    val enums: GeneratedFile = requireNotNull(
      files.find { it.relativePath.endsWith("NugetEnums.kt") && it.relativePath.startsWith("nativeMain/") }
    ) { "NugetEnums.kt must be generated under nativeMain/ when an enum crosses back from C#" }

    assertContains(enums.content, "package io.github.xxfast.kotlin.native.nuget.internal")
    assertContains(enums.content, "internal fun <T : Enum<T>> nugetEnumEntry(")
    assertContains(enums.content, "check(ordinal in entries.indices)")
    assertContains(enums.content, "has no entry for ordinal")
  }

  @Test
  fun `NugetEnums kt is not emitted when no enum is returned from C#`() {
    val files: List<GeneratedFile> = generateKotlinStubs(jsonConvertRir)

    assertTrue(
      files.none { it.relativePath.endsWith("NugetEnums.kt") },
      "NugetEnums.kt must not be emitted when no enum type is present in the IR",
    )
  }

  // ------------------------------------------------------------------
  // 16. Cross-namespace enum references: Mood is declared in Sample.Enums but consumed by
  // Sample.Text.MoodService. When the two namespaces are aliased to different Kotlin packages the
  // stub must import the enum, or the generated code does not compile.
  // ------------------------------------------------------------------

  private val crossNamespaceMoodRir: RirFile = RirFile(
    assemblies = listOf(
      RirAssembly(
        packageId = "Sample",
        assemblyName = "Sample",
        namespaces = listOf(
          RirNamespace(
            name = "Sample.Enums",
            types = listOf(
              RirEnum(
                name = "Mood",
                entries = listOf(
                  RirEnumEntry(name = "Happy", ordinal = 0),
                  RirEnumEntry(name = "Grumpy", ordinal = 1),
                ),
              ),
            ),
          ),
          RirNamespace(
            name = "Sample.Text",
            types = listOf(
              RirClass(
                name = "MoodService",
                isStatic = true,
                methods = listOf(
                  RirMethod(
                    name = "Next",
                    isStatic = true,
                    returnType = RirEnumType(namespace = "Sample.Enums", name = "Mood"),
                    parameters = listOf(
                      RirParameter(
                        name = "mood",
                        type = RirEnumType(namespace = "Sample.Enums", name = "Mood"),
                      ),
                    ),
                  ),
                ),
                properties = listOf(
                  RirProperty(
                    name = "DefaultMood",
                    type = RirEnumType(namespace = "Sample.Enums", name = "Mood"),
                    isStatic = true,
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    ),
  )

  private val moodNamespaceAliases: Map<String, Map<String, String>> = mapOf(
    "Sample" to mapOf(
      "Sample.Enums" to "sample.enums",
      "Sample.Text" to "sample.text",
    ),
  )

  @Test
  fun `stub imports an enum declared in a different kotlin package`() {
    val files: List<GeneratedFile> = generateKotlinStubs(
      file = crossNamespaceMoodRir,
      namespaceAliases = moodNamespaceAliases,
    )

    val enum: GeneratedFile = files.single { it.relativePath.endsWith("/Mood.kt") }
    val stub: GeneratedFile = files.single { it.relativePath.endsWith("MoodService.kt") }

    assertContains(enum.content, "package sample.enums")
    assertContains(stub.content, "package sample.text")
    assertContains(stub.content, "import sample.enums.Mood")
  }

  @Test
  fun `stub does not import an enum generated into its own kotlin package`() {
    val files: List<GeneratedFile> = generateKotlinStubs(crossNamespaceMoodRir)

    val stub: GeneratedFile = files.single { it.relativePath.endsWith("MoodService.kt") }

    assertContains(stub.content, "package sample")
    assertFalse(
      stub.content.contains("import sample.Mood"),
      "Mood lands in the same Kotlin package as MoodService without aliases, so it needs no import",
    )
  }
}
