package io.github.xxfast.kotlin.native.nuget

import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A type renamed or removed from a bound namespace must not leave its `.kt`/`.cs` file from the
 * previous run in `build/nuget-interop/`. Runs each task action twice against one output dir: once
 * with two bound types, once with one of them gone.
 *
 * The reverse-ir.json shapes are trimmed from real `NugetMetadataReader` output: `MimeUtility` from
 * test-library's MimeMapping import, `Template` from test-companion's TestDependency import.
 */
class NugetInteropOutputCleanupTest {
  private val mimeUtility = """
    {
      "packageId": "MimeMapping",
      "assemblyName": "MimeMapping",
      "namespaces": [
        {
          "name": "MimeMapping",
          "types": [
            {
              "kind": "class",
              "name": "MimeUtility",
              "isAbstract": true,
              "isStatic": true,
              "methods": [
                {
                  "name": "GetMimeMapping",
                  "returnType": { "kind": "string", "nullable": false },
                  "parameters": [
                    { "name": "file", "type": { "kind": "string", "nullable": false } }
                  ],
                  "isStatic": true,
                  "managedSignature": "method|static|MimeMapping.MimeUtility|GetMimeMapping|(System.String)|System.String",
                  "asyncKind": null,
                  "cancellationToken": null
                }
              ],
              "properties": [],
              "constructors": [],
              "interfaces": [],
              "typeParameters": [],
              "instantiations": []
            }
          ]
        }
      ],
      "diagnostics": []
    }
  """.trimIndent()

  private val template = """
    {
      "packageId": "TestDependency",
      "assemblyName": "TestDependency",
      "namespaces": [
        {
          "name": "Test.Text",
          "types": [
            {
              "kind": "class",
              "name": "Template",
              "isAbstract": false,
              "isStatic": false,
              "methods": [
                {
                  "name": "Apply",
                  "returnType": { "kind": "string", "nullable": false },
                  "parameters": [
                    { "name": "name", "type": { "kind": "string", "nullable": false } }
                  ],
                  "isStatic": false,
                  "managedSignature": "method|instance|Test.Text.Template|Apply|(System.String)|System.String",
                  "asyncKind": null,
                  "cancellationToken": null
                }
              ],
              "properties": [],
              "constructors": [
                {
                  "managedSignature": "ctor|instance|Test.Text.Template|.ctor|(System.String)|System.Void",
                  "parameters": [
                    { "name": "source", "type": { "kind": "string", "nullable": false } }
                  ],
                  "isState": false
                }
              ],
              "interfaces": [],
              "typeParameters": [],
              "instantiations": []
            }
          ]
        }
      ],
      "diagnostics": []
    }
  """.trimIndent()

  private fun reverseIr(vararg assemblies: String): String =
    """{ "schemaVersion": 1, "assemblies": [ ${assemblies.joinToString(",\n")} ] }"""

  private fun tempDir(name: String): File = Files.createTempDirectory(name).toFile()

  private fun project(): Project = ProjectBuilder.builder().build()

  private fun File.names(): Set<String> =
    walkTopDown().filter { it.isFile }.map { it.name }.toSet()

  @Test
  fun `regenerating bindings removes the kotlin file of a type no longer in reverse-ir`() {
    val work: File = tempDir("bindings-cleanup")
    val ir = File(work, "reverse-ir.json")
    val out = File(work, "kotlin")
    val task: NugetGenerateBindingsTask = project().tasks
      .register("nugetGenerateBindings", NugetGenerateBindingsTask::class.java)
      .get()
    task.reverseIrFile.set(ir)
    task.packageNameOverrides.set(emptyMap())
    task.namespaceAliases.set(emptyMap())
    task.kotlinOutputDir.set(out)
    task.boundTypesManifestFile.set(File(work, "bound-types.json"))
    task.diagnosticsFile.set(File(work, "NugetDiagnostics.json"))

    ir.writeText(reverseIr(mimeUtility, template))
    task.generate()
    val first: Set<String> = out.names()
    assertTrue("Template.kt" in first, "first run must generate Template.kt, was $first")
    assertTrue("MimeUtility.kt" in first, "first run must generate MimeUtility.kt, was $first")

    ir.writeText(reverseIr(mimeUtility))
    task.generate()
    val second: Set<String> = out.names()
    assertFalse("Template.kt" in second, "Template.kt survived its type's removal: $second")
    assertTrue("MimeUtility.kt" in second, "MimeUtility.kt must remain, was $second")
  }

  @Test
  fun `regenerating shims removes the csharp file of a type no longer in reverse-ir`() {
    val work: File = tempDir("shims-cleanup")
    val ir = File(work, "reverse-ir.json")
    val out = File(work, "csharp")
    val task: NugetGenerateShimsTask = project().tasks
      .register("nugetGenerateShims", NugetGenerateShimsTask::class.java)
      .get()
    task.reverseIrFile.set(ir)
    task.nativeLibraryName.set("sample")
    task.forwardNamespace.set("")
    task.csharpOutputDir.set(out)

    ir.writeText(reverseIr(mimeUtility, template))
    task.generate()
    val first: Set<String> = out.names()
    assertTrue("TemplateRegistration.cs" in first, "first run must shim Template, was $first")
    assertTrue("MimeUtilityRegistration.cs" in first, "first run must shim MimeUtility, was $first")

    ir.writeText(reverseIr(mimeUtility))
    task.generate()
    val second: Set<String> = out.names()
    assertFalse(
      "TemplateRegistration.cs" in second,
      "TemplateRegistration.cs survived its type's removal: $second",
    )
    assertTrue("MimeUtilityRegistration.cs" in second, "MimeUtility shim must remain, was $second")
  }
}
