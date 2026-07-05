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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

  @Test
  fun `class with only instance (non-static) methods emits no file`() {
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
    assertTrue(
      files.isEmpty(),
      "no file should be generated for a class with only instance methods",
    )
  }
}
