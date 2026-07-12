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
 * Unit tests for the pure generator function that converts a [RirFile] into C# registration-shim
 * source files. Tests assert on the text content and relative paths of the generated
 * [GeneratedFile] list — no `dotnet`/Roslyn compilation is required.
 *
 * ADR-049: `[UnmanagedCallersOnly]` thunks + `[ModuleInitializer]` registration, one
 * `{TypeName}Registration.cs` per bound `RirClass`, corrected inverse type-mapping table
 * (`IntPtr` for strings, `byte`/`ushort` narrowing for `bool`/`char`), no `try`/`catch` in thunk
 * bodies ("let it crash" — see ADR-049 "Exception behaviour").
 */
class NugetGenerateShimsTaskTest {

  // ------------------------------------------------------------------
  // Shared fixture: Newtonsoft.Json.JsonConvert with SerializeObject(int): string
  // This is the "Full worked example" from ADR-049.
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

  private fun jsonConvertShim(): GeneratedFile {
    val files: List<GeneratedFile> = generateCSharpShims(jsonConvertRir, "sample")
    return files.single { it.relativePath.endsWith("JsonConvertRegistration.cs") }
  }

  // ------------------------------------------------------------------
  // 1. File layout: one file at csharp/JsonConvertRegistration.cs (ADR-049 output path convention)
  // ------------------------------------------------------------------

  @Test
  fun `emits exactly one file for JsonConvert named JsonConvertRegistration cs`() {
    val files: List<GeneratedFile> = generateCSharpShims(jsonConvertRir, "sample")

    assertEquals(
      1,
      files.size,
      "expected exactly one generated file, got: ${files.map { it.relativePath }}",
    )
    assertTrue(
      files.single().relativePath.endsWith("JsonConvertRegistration.cs"),
      "generated file must be named JsonConvertRegistration.cs " +
        "but was '${files.single().relativePath}'",
    )
  }

  // ------------------------------------------------------------------
  // 2. Namespace + class declaration: same namespace as the original C# type, verbatim
  // ------------------------------------------------------------------

  @Test
  fun `generated shim declares namespace Newtonsoft dot Json verbatim`() {
    val shim: GeneratedFile = jsonConvertShim()
    assertContains(shim.content, "namespace Newtonsoft.Json")
  }

  @Test
  fun `generated shim declares nullable enable directive`() {
    val shim: GeneratedFile = jsonConvertShim()
    assertContains(shim.content, "#nullable enable")
  }

  @Test
  fun `generated shim declares internal static class JsonConvertRegistration`() {
    val shim: GeneratedFile = jsonConvertShim()
    assertContains(shim.content, "internal static class JsonConvertRegistration")
  }

  // ------------------------------------------------------------------
  // 3. [DllImport] for the registration export
  // ------------------------------------------------------------------

  @Test
  fun `generated shim DllImport targets the native library name argument`() {
    val shim: GeneratedFile = jsonConvertShim()
    assertContains(shim.content, "[DllImport(\"sample\"")
  }

  @Test
  fun `generated shim DllImport EntryPoint matches ADR-048 export name`() {
    val shim: GeneratedFile = jsonConvertShim()
    assertContains(
      shim.content,
      "EntryPoint = \"nuget_newtonsoft_json_json_convert_register\"",
    )
  }

  @Test
  fun `generated shim EntryPoint matches what the Kotlin generator produces for the same input`() {
    val kotlinFiles: List<GeneratedFile> = generateKotlinStubs(jsonConvertRir)
    val bindings: GeneratedFile =
      kotlinFiles.single { it.relativePath.endsWith("JsonConvertBindings.kt") }
    val exportName: String = Regex("@CName\\(\"(.+?)\"\\)")
      .find(bindings.content)!!
      .groupValues[1]

    val shim: GeneratedFile = jsonConvertShim()
    assertContains(
      shim.content,
      "EntryPoint = \"$exportName\"",
      message = "C# [DllImport] EntryPoint must match the Kotlin @CName export exactly, " +
        "to avoid ADR-049 Alternative 10's parameter-order/export-name drift",
    )
  }

  // ------------------------------------------------------------------
  // 4. [ModuleInitializer] registration call shape, in reverse-ir method order
  // ------------------------------------------------------------------

  @Test
  fun `generated shim declares ModuleInitializer Initialize method`() {
    val shim: GeneratedFile = jsonConvertShim()
    assertContains(shim.content, "[ModuleInitializer]")
    assertContains(shim.content, "internal static unsafe void Initialize()")
  }

  @Test
  fun `generated shim passes thunk address via delegate unmanaged Cdecl function pointer syntax`() {
    val shim: GeneratedFile = jsonConvertShim()
    assertContains(
      shim.content,
      "(IntPtr)(delegate* unmanaged[Cdecl]<int, IntPtr>)(&SerializeObject_Thunk)",
    )
  }

  // ------------------------------------------------------------------
  // 5. [UnmanagedCallersOnly] thunk body: calls the real method, string return via
  //    Marshal.StringToCoTaskMemUTF8, and — the "let it crash" decision — no try/catch.
  // ------------------------------------------------------------------

  @Test
  fun `generated thunk has UnmanagedCallersOnly attribute with CallConvCdecl`() {
    val shim: GeneratedFile = jsonConvertShim()
    assertContains(
      shim.content,
      "[UnmanagedCallersOnly(CallConvs = new[] { typeof(CallConvCdecl) })]",
    )
  }

  @Test
  fun `generated thunk signature uses IntPtr return and int parameter`() {
    val shim: GeneratedFile = jsonConvertShim()
    assertContains(
      shim.content,
      "private static IntPtr SerializeObject_Thunk(int value)",
    )
  }

  @Test
  fun `generated thunk body calls JsonConvert SerializeObject directly`() {
    val shim: GeneratedFile = jsonConvertShim()
    assertContains(shim.content, "JsonConvert.SerializeObject(value)")
  }

  @Test
  fun `generated thunk body returns Marshal StringToCoTaskMemUTF8 of the result`() {
    val shim: GeneratedFile = jsonConvertShim()
    assertContains(shim.content, "Marshal.StringToCoTaskMemUTF8(result)")
  }

  @Test
  fun `generated thunk body does not contain try or catch`() {
    // Word-boundary matching, not plain `.contains`: the file legitimately contains "EntryPoint"
    // (required by the [DllImport] attribute), which itself contains the substring "try" — a
    // naive `.contains("try")` would always false-positive on any valid generated shim.
    val shim: GeneratedFile = jsonConvertShim()
    assertFalse(
      Regex("\\btry\\b").containsMatchIn(shim.content),
      "thunk body must not contain the try keyword (ADR-049 let it crash)",
    )
    assertFalse(
      Regex("\\bcatch\\b").containsMatchIn(shim.content),
      "thunk body must not contain the catch keyword (ADR-049 let it crash)",
    )
  }

  // ------------------------------------------------------------------
  // 6. Type-table correction: bool ↔ byte at the ABI, converted in-body
  // ------------------------------------------------------------------

  private val validatorRir: RirFile = RirFile(
    assemblies = listOf(
      RirAssembly(
        packageId = "Acme.Lib",
        assemblyName = "Acme.Lib",
        namespaces = listOf(
          RirNamespace(
            name = "Acme.Lib",
            types = listOf(
              RirClass(
                name = "Validator",
                methods = listOf(
                  RirMethod(
                    name = "Negate",
                    isStatic = true,
                    returnType = RirPrimitiveType("bool"),
                    parameters = listOf(
                      RirParameter(name = "value", type = RirPrimitiveType("bool")),
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

  @Test
  fun `bool parameter and return type use byte at the thunk ABI, not bool`() {
    val files: List<GeneratedFile> = generateCSharpShims(validatorRir, "sample")
    val shim: GeneratedFile = files.single { it.relativePath.endsWith("ValidatorRegistration.cs") }

    assertContains(shim.content, "private static byte Negate_Thunk(byte value)")
    assertFalse(
      shim.content.contains("Negate_Thunk(bool"),
      "thunk parameter must be byte, not bool (ADR-049: bool is not blittable)",
    )
  }

  @Test
  fun `bool thunk body converts byte to bool on the way in and bool to byte on the way out`() {
    val files: List<GeneratedFile> = generateCSharpShims(validatorRir, "sample")
    val shim: GeneratedFile = files.single { it.relativePath.endsWith("ValidatorRegistration.cs") }

    assertContains(shim.content, "value != 0")
    assertContains(shim.content, "(byte)1")
    assertContains(shim.content, "(byte)0")
  }

  // ------------------------------------------------------------------
  // 7. Type-table correction: char ↔ ushort at the ABI, converted in-body
  // ------------------------------------------------------------------

  private val charHelperRir: RirFile = RirFile(
    assemblies = listOf(
      RirAssembly(
        packageId = "Acme.Lib",
        assemblyName = "Acme.Lib",
        namespaces = listOf(
          RirNamespace(
            name = "Acme.Lib",
            types = listOf(
              RirClass(
                name = "CharHelper",
                methods = listOf(
                  RirMethod(
                    name = "ToUpperChar",
                    isStatic = true,
                    returnType = RirPrimitiveType("char"),
                    parameters = listOf(
                      RirParameter(name = "value", type = RirPrimitiveType("char")),
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

  @Test
  fun `char parameter and return type use ushort at the thunk ABI, not char`() {
    val files: List<GeneratedFile> = generateCSharpShims(charHelperRir, "sample")
    val shim: GeneratedFile = files.single { it.relativePath.endsWith("CharHelperRegistration.cs") }

    assertContains(shim.content, "private static ushort ToUpperChar_Thunk(ushort value)")
    assertFalse(
      shim.content.contains("ToUpperChar_Thunk(char"),
      "thunk parameter must be ushort, not char (ADR-049: char is not blittable)",
    )
  }

  @Test
  fun `char thunk body converts ushort to char on the way in and char to ushort on the way out`() {
    val files: List<GeneratedFile> = generateCSharpShims(charHelperRir, "sample")
    val shim: GeneratedFile = files.single { it.relativePath.endsWith("CharHelperRegistration.cs") }

    assertContains(shim.content, "(char)value")
    assertContains(shim.content, "(ushort)result")
  }

  // ------------------------------------------------------------------
  // 8. void return type maps to void
  // ------------------------------------------------------------------

  private val fooRir: RirFile = RirFile(
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

  @Test
  fun `void return type maps to void thunk signature`() {
    val files: List<GeneratedFile> = generateCSharpShims(fooRir, "sample")
    val shim: GeneratedFile = files.single { it.relativePath.endsWith("FooRegistration.cs") }

    assertContains(shim.content, "private static void DoIt_Thunk()")
  }

  // ------------------------------------------------------------------
  // 9. Multi-method class: registers pointers in declaration order (order-drift guard, ADR-049
  //    Alternative 10 — must not silently re-sort e.g. alphabetically).
  // ------------------------------------------------------------------

  private val multiOpsRir: RirFile = RirFile(
    assemblies = listOf(
      RirAssembly(
        packageId = "Acme.Lib",
        assemblyName = "Acme.Lib",
        namespaces = listOf(
          RirNamespace(
            name = "Acme.Lib",
            types = listOf(
              RirClass(
                name = "MultiOps",
                methods = listOf(
                  RirMethod(
                    name = "Zulu",
                    isStatic = true,
                    returnType = RirPrimitiveType("int"),
                    parameters = listOf(
                      RirParameter(name = "value", type = RirPrimitiveType("int")),
                    ),
                  ),
                  RirMethod(
                    name = "Alpha",
                    isStatic = true,
                    returnType = RirPrimitiveType("int"),
                    parameters = listOf(
                      RirParameter(name = "value", type = RirPrimitiveType("int")),
                    ),
                  ),
                  RirMethod(
                    name = "Mike",
                    isStatic = true,
                    returnType = RirPrimitiveType("int"),
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

  @Test
  fun `multi-method class registers thunk pointers in reverse-ir declaration order, not alphabetical`() {
    val files: List<GeneratedFile> = generateCSharpShims(multiOpsRir, "sample")
    val shim: GeneratedFile = files.single { it.relativePath.endsWith("MultiOpsRegistration.cs") }

    val zuluIndex: Int = shim.content.indexOf("&Zulu_Thunk")
    val alphaIndex: Int = shim.content.indexOf("&Alpha_Thunk")
    val mikeIndex: Int = shim.content.indexOf("&Mike_Thunk")

    assertTrue(
      zuluIndex >= 0 && alphaIndex >= 0 && mikeIndex >= 0,
      "all three thunks must be registered",
    )
    assertTrue(
      alphaIndex in zuluIndex + 1 until mikeIndex,
      "thunk pointers must be registered in declaration order Zulu, Alpha, Mike — got indices " +
        "$zuluIndex, $alphaIndex, $mikeIndex",
    )
  }

  @Test
  fun `multi-method class DllImport declares one IntPtr parameter per bridgeable method in order`() {
    val files: List<GeneratedFile> = generateCSharpShims(multiOpsRir, "sample")
    val shim: GeneratedFile = files.single { it.relativePath.endsWith("MultiOpsRegistration.cs") }

    val externLine: String = shim.content.lines().first { it.contains("_register(IntPtr") }
    val zuluIndex: Int = externLine.indexOf("zuluPtr")
    val alphaIndex: Int = externLine.indexOf("alphaPtr")
    val mikeIndex: Int = externLine.indexOf("mikePtr")

    assertTrue(
      zuluIndex >= 0 && alphaIndex >= 0 && mikeIndex >= 0,
      "all three pointer params must be declared",
    )
    assertTrue(alphaIndex in zuluIndex + 1 until mikeIndex)
  }

  // ------------------------------------------------------------------
  // 10. A class with no bridgeable static methods emits no file.
  // ------------------------------------------------------------------

  @Test
  fun `class with no methods emits no file`() {
    val rir: RirFile = RirFile(
      assemblies = listOf(
        RirAssembly(
          packageId = "Acme.Lib",
          assemblyName = "Acme.Lib",
          namespaces = listOf(
            RirNamespace(
              name = "Acme.Lib",
              types = listOf(RirClass(name = "Empty", methods = emptyList())),
            ),
          ),
        ),
      ),
    )

    val files: List<GeneratedFile> = generateCSharpShims(rir, "sample")
    assertTrue(
      files.isEmpty(),
      "no file should be generated for a class with no bridgeable methods",
    )
  }

  // Phase 9 (ROADMAP line 151) supersedes the pre-Phase-9 v1 boundary this test used to encode
  // ("instance methods are not bridgeable at all"): a class with only instance methods now DOES
  // emit a registration file, with an instance-method thunk (leading IntPtr selfHandle receiver).
  @Test
  fun `class with only instance (non-static) methods emits a file with a receiver-first thunk`() {
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
                  name = "InstanceOnly",
                  methods = listOf(
                    RirMethod(
                      name = "DoIt",
                      isStatic = false,
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

    val files: List<GeneratedFile> = generateCSharpShims(rir, "sample")
    val shim: GeneratedFile = files.single { it.relativePath.endsWith("InstanceOnlyRegistration.cs") }
    assertContains(shim.content, "private static void DoIt_Thunk(IntPtr selfHandle)")
    assertContains(
      shim.content,
      "(InstanceOnly)GCHandle.FromIntPtr(selfHandle).Target!",
    )
  }

  // ------------------------------------------------------------------
  // ADR-051: C# objects as opaque handles — C# shim generation
  //
  // Canonical fixture: Sample.Text.Template — public non-static class with two static methods:
  //   Parse(source: string): Template  and  Render(template: Template, name: string): string
  //
  // NOTE: this fixture references RirObjectHandleType (not yet in RirModel.kt) and the isStatic
  // field on RirClass (not yet added). Both produce compile errors until the model is extended.
  // That compile error is the expected "red state" for this test section (ADR-051 Step 3).
  // ------------------------------------------------------------------

  // ADR-051 canonical fixture shared across all handle-type shim tests.
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

  private fun templateShim(): GeneratedFile {
    val files: List<GeneratedFile> = generateCSharpShims(templateRir, "sample")
    return files.single { it.relativePath.endsWith("TemplateRegistration.cs") }
  }

  // ------------------------------------------------------------------
  // 11. Parse_Thunk: IntPtr return, GCHandle.Alloc, IntPtr.Zero for null
  // ------------------------------------------------------------------

  @Test
  fun `Parse thunk signature returns IntPtr and takes IntPtr source parameter`() {
    val shim: GeneratedFile = templateShim()
    assertContains(shim.content, "private static IntPtr Parse_Thunk(IntPtr sourcePtr)")
  }

  @Test
  fun `Parse thunk body allocates GCHandle for a non-null result`() {
    val shim: GeneratedFile = templateShim()
    assertContains(
      shim.content,
      "GCHandle.Alloc(result)",
      message = "Parse_Thunk must wrap the returned Template in a GCHandle (ADR-051 §thunk table)",
    )
  }

  @Test
  fun `Parse thunk body converts GCHandle to IntPtr`() {
    val shim: GeneratedFile = templateShim()
    assertContains(shim.content, "GCHandle.ToIntPtr(GCHandle.Alloc(result))")
  }

  @Test
  fun `Parse thunk body returns IntPtr Zero for null result`() {
    val shim: GeneratedFile = templateShim()
    assertContains(
      shim.content,
      "IntPtr.Zero",
      message = "Parse_Thunk must return IntPtr.Zero when the C# method returns null (ADR-051 §Nullability)",
    )
  }

  // ------------------------------------------------------------------
  // 12. Render_Thunk: IntPtr templateHandle parameter, GCHandle.FromIntPtr cast
  // ------------------------------------------------------------------

  @Test
  fun `Render thunk signature takes IntPtr templateHandle and IntPtr namePtr and returns IntPtr`() {
    val shim: GeneratedFile = templateShim()
    assertContains(
      shim.content,
      "private static IntPtr Render_Thunk(IntPtr templateHandle, IntPtr namePtr)",
    )
  }

  @Test
  fun `Render thunk body unpacks template from GCHandle`() {
    val shim: GeneratedFile = templateShim()
    assertContains(
      shim.content,
      "(Template)GCHandle.FromIntPtr(templateHandle).Target!",
      message = "Render_Thunk must cast the handle back to Template via GCHandle.FromIntPtr (ADR-051)",
    )
  }

  // ------------------------------------------------------------------
  // 13. Per-type register contract unchanged: two IntPtr params (parse, render), no extra free slot
  // ------------------------------------------------------------------

  @Test
  fun `TemplateRegistration DllImport has exactly parsePtr and renderPtr with no extra free slot`() {
    val shim: GeneratedFile = templateShim()

    val externLine: String = shim.content.lines().first { it.contains("_register(IntPtr") }
    assertContains(externLine, "parsePtr")
    assertContains(externLine, "renderPtr")
    assertFalse(
      externLine.contains("freePtr"),
      "Per-type register must NOT have a freePtr slot (ADR-051 §3d: free is in shared runtime export)",
    )
  }

  @Test
  fun `TemplateRegistration ModuleInitializer passes thunks in Parse then Render order`() {
    val shim: GeneratedFile = templateShim()

    val parseIndex: Int = shim.content.indexOf("&Parse_Thunk")
    val renderIndex: Int = shim.content.indexOf("&Render_Thunk")

    assertTrue(parseIndex >= 0, "&Parse_Thunk must appear in the shim")
    assertTrue(renderIndex >= 0, "&Render_Thunk must appear in the shim")
    assertTrue(
      parseIndex < renderIndex,
      "Parse_Thunk must be registered before Render_Thunk (reverse-ir declaration order)",
    )
  }

  // ------------------------------------------------------------------
  // 14. NugetRuntimeRegistration.cs is emitted when handle types are present
  // ------------------------------------------------------------------

  @Test
  fun `NugetRuntimeRegistration cs is emitted when handle types are present`() {
    val files: List<GeneratedFile> = generateCSharpShims(templateRir, "sample")

    assertNotNull(
      files.find { it.relativePath.endsWith("NugetRuntimeRegistration.cs") },
      "NugetRuntimeRegistration.cs must be generated when handle types appear in the IR",
    )
  }

  @Test
  fun `NugetRuntimeRegistration cs contains FreeGcHandle_Thunk`() {
    val files: List<GeneratedFile> = generateCSharpShims(templateRir, "sample")

    val runtimeShim: GeneratedFile = requireNotNull(
      files.find { it.relativePath.endsWith("NugetRuntimeRegistration.cs") }
    ) { "NugetRuntimeRegistration.cs must be generated" }

    assertContains(runtimeShim.content, "FreeGcHandle_Thunk")
  }

  @Test
  fun `NugetRuntimeRegistration cs ModuleInitializer calls nuget_runtime_register`() {
    val files: List<GeneratedFile> = generateCSharpShims(templateRir, "sample")

    val runtimeShim: GeneratedFile = requireNotNull(
      files.find { it.relativePath.endsWith("NugetRuntimeRegistration.cs") }
    ) { "NugetRuntimeRegistration.cs must be generated" }

    assertContains(runtimeShim.content, "[ModuleInitializer]")
    assertContains(runtimeShim.content, "nuget_runtime_register")
  }

  @Test
  fun `NugetRuntimeRegistration cs FreeGcHandle_Thunk frees via GCHandle FromIntPtr`() {
    val files: List<GeneratedFile> = generateCSharpShims(templateRir, "sample")

    val runtimeShim: GeneratedFile = requireNotNull(
      files.find { it.relativePath.endsWith("NugetRuntimeRegistration.cs") }
    ) { "NugetRuntimeRegistration.cs must be generated" }

    assertContains(runtimeShim.content, "GCHandle.FromIntPtr(handle).Free()")
  }

  // Guard: NugetRuntimeRegistration.cs must NOT be emitted for statics-only IR (uses the
  // existing fixture, compiles today, must stay green before and after implementation).
  @Test
  fun `NugetRuntimeRegistration cs is not emitted for statics-only IR`() {
    val files: List<GeneratedFile> = generateCSharpShims(jsonConvertRir, "sample")

    assertTrue(
      files.none { it.relativePath.endsWith("NugetRuntimeRegistration.cs") },
      "NugetRuntimeRegistration.cs must not be emitted when no handle types are present in the IR",
    )
  }

  // ------------------------------------------------------------------
  // ADR-052: C# instance constructors in Kotlin — `new Foo(...)` gains a `Ctor_Thunk`, registered
  // first in Initialize(), mirroring ADR-051's Parse_Thunk but unconditionally non-null.
  //
  // Canonical fixture: Sample.Text.Template — public non-static class with:
  //   - one public instance constructor: Template(string source)
  //   - the existing ADR-051 statics: Parse(source: string): Template, Render(template, name): string
  //
  // NOTE: RirClass.constructors / RirConstructor do not yet drive any generation logic in
  // NugetGenerateShimsTask.kt (ADR-052 Step 3 "red" state) — the fixture compiles (RirModel.kt was
  // extended additively) but the assertions below fail because the generator does not yet emit
  // Ctor_Thunk or register it first.
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

  private fun templateWithCtorShim(): GeneratedFile {
    val files: List<GeneratedFile> = generateCSharpShims(templateWithCtorRir, "sample")
    return files.single { it.relativePath.endsWith("TemplateRegistration.cs") }
  }

  // ------------------------------------------------------------------
  // 15. Ctor_Thunk: signature, `new` call, unconditionally non-null GCHandle
  // ------------------------------------------------------------------

  @Test
  fun `Ctor_Thunk signature returns IntPtr and takes IntPtr source parameter`() {
    val shim: GeneratedFile = templateWithCtorShim()
    assertContains(shim.content, "private static IntPtr Ctor_Thunk(IntPtr sourcePtr)")
  }

  @Test
  fun `Ctor_Thunk body calls new Template with the marshalled source string`() {
    val shim: GeneratedFile = templateWithCtorShim()
    assertContains(
      shim.content,
      "new Template(Marshal.PtrToStringUTF8(sourcePtr)!)",
      message = "ADR-052: Ctor_Thunk must construct the C# object via `new Template(...)`",
    )
  }

  @Test
  fun `Ctor_Thunk body returns GCHandle ToIntPtr of GCHandle Alloc unconditionally, never IntPtr Zero`() {
    val shim: GeneratedFile = templateWithCtorShim()
    val thunkBody: String = shim.content
      .substringAfter(
        delimiter = "private static IntPtr Ctor_Thunk(IntPtr sourcePtr)",
        missingDelimiterValue = "",
      )
      .substringBefore("[UnmanagedCallersOnly")

    assertContains(
      thunkBody,
      "GCHandle.ToIntPtr(GCHandle.Alloc(",
      message = "ADR-052: Ctor_Thunk must always allocate and return a GCHandle — `new` never " +
        "returns null, unlike ADR-051's nullable factory thunks",
    )
    assertFalse(
      thunkBody.contains("IntPtr.Zero"),
      "ADR-052: Ctor_Thunk must never return IntPtr.Zero — a C# constructor never returns null",
    )
  }

  // ------------------------------------------------------------------
  // 16. Ctor_Thunk is registered FIRST in Initialize(), before the static-method thunks
  // ------------------------------------------------------------------

  @Test
  fun `TemplateRegistration ModuleInitializer passes Ctor_Thunk before Parse_Thunk and Render_Thunk`() {
    val shim: GeneratedFile = templateWithCtorShim()

    val ctorIndex: Int = shim.content.indexOf("&Ctor_Thunk")
    val parseIndex: Int = shim.content.indexOf("&Parse_Thunk")
    val renderIndex: Int = shim.content.indexOf("&Render_Thunk")

    assertTrue(
      ctorIndex >= 0 && parseIndex >= 0 && renderIndex >= 0,
      "all three thunks (Ctor_Thunk, Parse_Thunk, Render_Thunk) must be registered, got indices " +
        "$ctorIndex, $parseIndex, $renderIndex",
    )
    assertTrue(
      ctorIndex < parseIndex && ctorIndex < renderIndex,
      "ADR-052 shared bridgeable ordering: Ctor_Thunk must be registered first, before the " +
        "static-method thunks — got indices ctor=$ctorIndex, parse=$parseIndex, render=$renderIndex",
    )
  }

  @Test
  fun `TemplateRegistration DllImport declares ctorPtr as its first parameter`() {
    val shim: GeneratedFile = templateWithCtorShim()

    val externLine: String = shim.content.lines().first { it.contains("_register(IntPtr") }
    val ctorIndex: Int = externLine.indexOf("ctorPtr")
    val parseIndex: Int = externLine.indexOf("parsePtr")
    val renderIndex: Int = externLine.indexOf("renderPtr")

    assertTrue(
      ctorIndex >= 0 && parseIndex >= 0 && renderIndex >= 0,
      "ctorPtr, parsePtr, and renderPtr must all be declared, got: '$externLine'",
    )
    assertTrue(
      ctorIndex < parseIndex && ctorIndex < renderIndex,
      "ADR-052: ctorPtr must be the first DllImport parameter — got '$externLine'",
    )
  }

  // ------------------------------------------------------------------
  // Phase 9 (ROADMAP line 151): C# instance methods and instance properties — confirmed "mirror"
  // item, no new ADR. "An instance thunk is a static thunk whose first parameter is the receiver
  // handle" (ADR-051 Deferred section). Reuses ADR-051's GCHandle.FromIntPtr(...).Target! pattern
  // (already used for handle-typed *parameters*, see Render_Thunk) for the receiver too.
  //
  // Thunk-naming decisions made while writing these tests (not previously settled by an ADR —
  // flagged for confirmation before the follow-up implementation round):
  //   - an instance method thunk keeps the existing `{MethodName}_Thunk` naming (methods are
  //     already named this way regardless of static/instance; only the parameter list changes)
  //   - a property getter thunk is named `{PropertyName}_Get_Thunk`
  //   - a property setter thunk is named `{PropertyName}_Set_Thunk`
  //
  // Canonical fixture: Sample.Text.Template — public non-static class with:
  //   - a public instance constructor Template(string source)                (ADR-052, unchanged)
  //   - static Parse(source: string): Template                               (ADR-051, unchanged)
  //   - instance method Rename(newName: string): void
  //   - instance property Name: string { get; }                              (read-only)
  //   - instance property Length: int { get; set; }                         (settable, primitive)
  //
  // NOTE: RirRegistrable.PropertyGetter/PropertySetter and bridgeableProperties() exist only as
  // stubs in RirBridging.kt at this step (Phase 9 line 151 Step 3 "red" state) — the generator
  // does not yet emit any instance-method receiver parameter or any property thunk at all, so the
  // assertions in this section are expected to fail until the follow-up round.
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

  private fun templateInstanceShim(): GeneratedFile {
    val files: List<GeneratedFile> = generateCSharpShims(templateInstanceRir, "sample")
    return files.single { it.relativePath.endsWith("TemplateRegistration.cs") }
  }

  // ------------------------------------------------------------------
  // 17. Instance-method thunk: leading IntPtr selfHandle, resolves the receiver via
  //     GCHandle.FromIntPtr(selfHandle).Target!, calls the method on the resolved instance
  //     (not statically on the type).
  // ------------------------------------------------------------------

  @Test
  fun `Rename_Thunk signature gains a leading IntPtr selfHandle parameter`() {
    val shim: GeneratedFile = templateInstanceShim()
    assertContains(
      shim.content,
      "private static void Rename_Thunk(IntPtr selfHandle, IntPtr newNamePtr)",
      message = "Phase 9 line 151: an instance method thunk gains a leading IntPtr selfHandle " +
        "parameter (an instance thunk is a static thunk whose first parameter is the receiver " +
        "handle, ADR-051)",
    )
  }

  @Test
  fun `Rename_Thunk body resolves the receiver via GCHandle FromIntPtr selfHandle Target`() {
    val shim: GeneratedFile = templateInstanceShim()
    assertContains(
      shim.content,
      "(Template)GCHandle.FromIntPtr(selfHandle).Target!",
      message = "the receiver must be resolved via the exact same GCHandle.FromIntPtr(...).Target! " +
        "pattern ADR-051 already uses for handle-typed parameters (see Render_Thunk)",
    )
  }

  @Test
  fun `Rename_Thunk body calls Rename on the resolved instance, not statically on Template`() {
    val shim: GeneratedFile = templateInstanceShim()
    assertFalse(
      shim.content.contains("Template.Rename("),
      "an instance method must be called on the resolved receiver instance, not statically " +
        "via Template.Rename(...)",
    )
  }

  // ------------------------------------------------------------------
  // 18. Property getter thunk: leading IntPtr selfHandle, returns the marshalled value
  //     (Marshal.StringToCoTaskMemUTF8 for a string-typed property, per ADR-048/049).
  // ------------------------------------------------------------------

  @Test
  fun `Name_Get_Thunk signature takes IntPtr selfHandle and returns IntPtr`() {
    val shim: GeneratedFile = templateInstanceShim()
    assertContains(shim.content, "private static IntPtr Name_Get_Thunk(IntPtr selfHandle)")
  }

  @Test
  fun `Name_Get_Thunk body resolves the receiver and returns Marshal StringToCoTaskMemUTF8 of the property value`() {
    val shim: GeneratedFile = templateInstanceShim()
    assertContains(shim.content, "(Template)GCHandle.FromIntPtr(selfHandle).Target!")
    assertContains(shim.content, "Marshal.StringToCoTaskMemUTF8(")
  }

  // ------------------------------------------------------------------
  // 19. Property setter thunk: leading IntPtr selfHandle + value param, void return.
  // ------------------------------------------------------------------

  @Test
  fun `Length_Set_Thunk signature takes IntPtr selfHandle and int value and returns void`() {
    val shim: GeneratedFile = templateInstanceShim()
    assertContains(
      shim.content,
      "private static void Length_Set_Thunk(IntPtr selfHandle, int value)",
    )
  }

  // ------------------------------------------------------------------
  // 19b. Static property thunks do not receive a selfHandle. They access the C# property through
  // the type, and their slots append after every instance-property accessor slot.
  // ------------------------------------------------------------------

  @Test
  fun `static property getter and setter thunks have no selfHandle and access Template statically`() {
    val shim: GeneratedFile = templateInstanceShim()

    assertContains(shim.content, "private static IntPtr DefaultName_Get_Thunk()")
    assertContains(shim.content, "private static void DefaultName_Set_Thunk(IntPtr valuePtr)")
    assertContains(shim.content, "Template.DefaultName")
    assertContains(shim.content, "private static int RenderCount_Get_Thunk()")
    assertContains(shim.content, "Template.RenderCount")
  }

  // ------------------------------------------------------------------
  // 20. [ModuleInitializer] passes the pointers in the exact order from the shared
  //     bridgeableRegistrables() contract: ctor, static methods, instance methods, then
  //     per-property getter/[setter] pairs.
  // ------------------------------------------------------------------

  @Test
  fun `TemplateRegistration ModuleInitializer appends static property accessors after instance property accessors`() {
    val shim: GeneratedFile = templateInstanceShim()

    val ctorIndex: Int = shim.content.indexOf("&Ctor_Thunk")
    val parseIndex: Int = shim.content.indexOf("&Parse_Thunk")
    val renameIndex: Int = shim.content.indexOf("&Rename_Thunk")
    val nameGetIndex: Int = shim.content.indexOf("&Name_Get_Thunk")
    val lengthGetIndex: Int = shim.content.indexOf("&Length_Get_Thunk")
    val lengthSetIndex: Int = shim.content.indexOf("&Length_Set_Thunk")
    val defaultNameGetIndex: Int = shim.content.indexOf("&DefaultName_Get_Thunk")
    val defaultNameSetIndex: Int = shim.content.indexOf("&DefaultName_Set_Thunk")
    val renderCountGetIndex: Int = shim.content.indexOf("&RenderCount_Get_Thunk")

    assertTrue(
      listOf(
        ctorIndex, parseIndex, renameIndex, nameGetIndex, lengthGetIndex, lengthSetIndex,
        defaultNameGetIndex, defaultNameSetIndex, renderCountGetIndex,
      )
        .all { it >= 0 },
      "all static and instance property thunks must be registered, got indices: ctor=$ctorIndex, parse=$parseIndex, " +
        "rename=$renameIndex, nameGet=$nameGetIndex, lengthGet=$lengthGetIndex, " +
        "lengthSet=$lengthSetIndex, defaultNameGet=$defaultNameGetIndex, " +
        "defaultNameSet=$defaultNameSetIndex, renderCountGet=$renderCountGetIndex",
    )
    assertTrue(
      ctorIndex < parseIndex &&
        parseIndex < renameIndex &&
        renameIndex < nameGetIndex &&
        nameGetIndex < lengthGetIndex &&
        lengthGetIndex < lengthSetIndex &&
        lengthSetIndex < defaultNameGetIndex &&
        defaultNameGetIndex < defaultNameSetIndex &&
        defaultNameSetIndex < renderCountGetIndex,
      "Phase 9 static-property ordering: ctor, static methods, instance methods, instance " +
        "property accessors, then static property accessors — got " +
        "ctor=$ctorIndex, parse=$parseIndex, rename=$renameIndex, nameGet=$nameGetIndex, " +
        "lengthGet=$lengthGetIndex, lengthSet=$lengthSetIndex, defaultNameGet=$defaultNameGetIndex, " +
        "defaultNameSet=$defaultNameSetIndex, renderCountGet=$renderCountGetIndex",
    )
  }

  @Test
  fun `TemplateRegistration DllImport declares one IntPtr parameter per registrable in the shared order`() {
    val shim: GeneratedFile = templateInstanceShim()

    val externLine: String = shim.content.lines().first { it.contains("_register(IntPtr") }
    val ctorIndex: Int = externLine.indexOf("ctorPtr")
    val parseIndex: Int = externLine.indexOf("parsePtr")
    val renameIndex: Int = externLine.indexOf("renamePtr")
    val nameGetIndex: Int = externLine.indexOf("nameGetterPtr")
    val lengthGetIndex: Int = externLine.indexOf("lengthGetterPtr")
    val lengthSetIndex: Int = externLine.indexOf("lengthSetterPtr")
    val defaultNameGetIndex: Int = externLine.indexOf("defaultNameGetterPtr")
    val defaultNameSetIndex: Int = externLine.indexOf("defaultNameSetterPtr")
    val renderCountGetIndex: Int = externLine.indexOf("renderCountGetterPtr")

    assertTrue(
      listOf(
        ctorIndex, parseIndex, renameIndex, nameGetIndex, lengthGetIndex, lengthSetIndex,
        defaultNameGetIndex, defaultNameSetIndex, renderCountGetIndex,
      )
        .all { it >= 0 },
      "all six pointer params must be declared, got: '$externLine'",
    )
    assertTrue(
      ctorIndex < parseIndex &&
        parseIndex < renameIndex &&
        renameIndex < nameGetIndex &&
        nameGetIndex < lengthGetIndex &&
        lengthGetIndex < lengthSetIndex &&
        lengthSetIndex < defaultNameGetIndex &&
        defaultNameGetIndex < defaultNameSetIndex &&
        defaultNameSetIndex < renderCountGetIndex,
    )
  }

  // ------------------------------------------------------------------
  // Phase 9: enum ABI conversion. C# enum values cross the unmanaged boundary as `int`, while
  // the thunk casts at the managed call boundary in both directions.
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
  fun `enum method thunk receives and returns int while casting for MoodService Next`() {
    val shim: GeneratedFile = generateCSharpShims(moodRir, "sample")
      .single { it.relativePath.endsWith("MoodServiceRegistration.cs") }

    assertContains(shim.content, "private static int Next_Thunk(int mood)")
    assertContains(shim.content, "Mood result = MoodService.Next((Mood)mood);")
    assertContains(shim.content, "return (int)result;")
  }

  @Test
  fun `enum property thunks use int ABI and cast getter and setter values`() {
    val shim: GeneratedFile = generateCSharpShims(moodRir, "sample")
      .single { it.relativePath.endsWith("MoodServiceRegistration.cs") }

    assertContains(shim.content, "private static int DefaultMood_Get_Thunk()")
    assertContains(shim.content, "Mood result = MoodService.DefaultMood;")
    assertContains(shim.content, "return (int)result;")
    assertContains(shim.content, "private static void DefaultMood_Set_Thunk(int value)")
    assertContains(shim.content, "MoodService.DefaultMood = (Mood)value;")
  }

  @Test
  fun `enum in the shims own namespace needs no extra using`() {
    val shim: GeneratedFile = generateCSharpShims(moodRir, "sample")
      .single { it.relativePath.endsWith("MoodServiceRegistration.cs") }

    assertContains(shim.content, "namespace Sample.Enums")
    assertFalse(
      shim.content.contains("using Sample.Enums;"),
      "the shim already renders inside namespace Sample.Enums; importing it would be redundant",
    )
  }

  // ------------------------------------------------------------------
  // Cross-namespace enum: Mood is declared in Sample.Enums but consumed by Sample.Text.MoodService,
  // so the shim (which renders inside `namespace Sample.Text`) must import Sample.Enums or the
  // `(Mood)mood` casts in its thunk bodies do not compile.
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

  @Test
  fun `shim imports the namespace of an enum declared outside its own namespace`() {
    val shim: GeneratedFile = generateCSharpShims(crossNamespaceMoodRir, "sample")
      .single { it.relativePath.endsWith("MoodServiceRegistration.cs") }

    assertContains(shim.content, "namespace Sample.Text")
    assertContains(shim.content, "    using Sample.Enums;")
    assertContains(shim.content, "Mood result = MoodService.Next((Mood)mood);")
  }

  @Test
  fun `shim keeps the standard system usings alongside an imported enum namespace`() {
    val shim: GeneratedFile = generateCSharpShims(crossNamespaceMoodRir, "sample")
      .single { it.relativePath.endsWith("MoodServiceRegistration.cs") }

    assertContains(shim.content, "    using System;")
    assertContains(shim.content, "    using System.Runtime.CompilerServices;")
    assertContains(shim.content, "    using System.Runtime.InteropServices;")
  }
}
