package io.github.xxfast.kotlin.native.nuget

import io.github.xxfast.kotlin.native.nuget.rir.RirClass
import io.github.xxfast.kotlin.native.nuget.rir.RirFile
import io.github.xxfast.kotlin.native.nuget.rir.RirMethod
import io.github.xxfast.kotlin.native.nuget.rir.RirParameter
import io.github.xxfast.kotlin.native.nuget.rir.RirPrimitiveType
import io.github.xxfast.kotlin.native.nuget.rir.RirStringType
import io.github.xxfast.kotlin.native.nuget.rir.RirTypeRef
import io.github.xxfast.kotlin.native.nuget.rir.RirVoidType
import io.github.xxfast.kotlin.native.nuget.rir.bridgeableStaticMethods
import io.github.xxfast.kotlin.native.nuget.rir.parseReverseIr
import io.github.xxfast.kotlin.native.nuget.rir.registrationExportName
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.File

// ADR-049: C#-side registration shim generator — the managed mirror of generateKotlinStubs
// (ADR-048). Emits, per bound RirClass with at least one v1-bridgeable static method, one
// `{TypeName}Registration.cs` file containing:
//   - a [DllImport] extern declaration for the Kotlin registration export (ADR-048's contract),
//   - a [ModuleInitializer] that hands the export one thunk-pointer per bridgeable method, in the
//     exact order bridgeableStaticMethods() returns (ADR-049 Alternative 10 order-drift guard),
//   - one [UnmanagedCallersOnly] thunk per bridgeable method, calling the real C# method directly.
//
// Exception behaviour (ADR-049 "let it crash"): thunk bodies never catch. A thrown C# exception
// escapes the [UnmanagedCallersOnly] method and fast-fails the host process — this is deliberate,
// not an oversight; do not "harden" a thunk with try/catch (see ADR-049 Consequences).
fun generateCSharpShims(file: RirFile, nativeLibraryName: String): List<GeneratedFile> {
  val result: MutableList<GeneratedFile> = mutableListOf()

  file.assemblies.forEach { assembly ->
    assembly.namespaces.forEach { namespace ->
      namespace.types.filterIsInstance<RirClass>().forEach { cls ->
        val bridgeable: List<RirMethod> = bridgeableStaticMethods(cls)

        if (bridgeable.isEmpty()) return@forEach

        val exportName: String = registrationExportName(namespace.name, cls.name)

        result.add(GeneratedFile(
          relativePath = "${cls.name}Registration.cs",
          content = registrationFileContent(
            namespaceName = namespace.name,
            cls = cls,
            bridgeable = bridgeable,
            exportName = exportName,
            nativeLibraryName = nativeLibraryName,
          ),
        ))
      }
    }
  }

  return result
}

// ADR-049 "Inverse type-mapping table": the C# type that crosses the [UnmanagedCallersOnly]
// boundary itself. IntPtr for strings (avoids `unsafe` everywhere but the [ModuleInitializer]'s
// function-pointer address-of expression); byte/ushort narrowing for the two non-blittable
// primitives `bool`/`char` (System.Boolean and System.Char are not blittable — see ADR-049's
// "two non-obvious blittability corrections"); every other primitive crosses directly.
private fun csAbiType(type: RirTypeRef): String = when (type) {
  is RirVoidType -> "void"
  is RirStringType -> "IntPtr"
  is RirPrimitiveType -> when (type.name) {
    "bool" -> "byte"
    "byte" -> "byte"
    "short" -> "short"
    "int" -> "int"
    "long" -> "long"
    "float" -> "float"
    "double" -> "double"
    "char" -> "ushort"
    else -> error(
      "[nuget] Unknown primitive type name '${type.name}' — " +
        "update the v1 inverse type-mapping table in NugetGenerateShimsTask.kt"
    )
  }
}

// The real, natural C# type for the actual method call/return (as opposed to the ABI-level type
// that crosses [UnmanagedCallersOnly] — see csAbiType above).
private fun csNativeType(type: RirTypeRef): String = when (type) {
  is RirVoidType -> "void"
  is RirStringType -> "string"
  is RirPrimitiveType -> when (type.name) {
    "bool" -> "bool"
    "byte" -> "byte"
    "short" -> "short"
    "int" -> "int"
    "long" -> "long"
    "float" -> "float"
    "double" -> "double"
    "char" -> "char"
    else -> error(
      "[nuget] Unknown primitive type name '${type.name}' — " +
        "update the v1 inverse type-mapping table in NugetGenerateShimsTask.kt"
    )
  }
}

// PascalCase method name → camelCase: lowercase the first character only.
// e.g. SerializeObject → serializeObject
private fun String.toMethodCamelCase(): String = replaceFirstChar { it.lowercaseChar() }

// Converts a thunk-level (ABI) parameter into the expression passed to the real C# method call.
private fun paramConversion(p: RirParameter): String = when (p.type) {
  is RirStringType -> "Marshal.PtrToStringUTF8(${p.name})!"
  is RirVoidType -> p.name
  is RirPrimitiveType -> when (p.type.name) {
    "bool" -> "${p.name} != 0"
    "char" -> "(char)${p.name}"
    else -> p.name
  }
}

private fun registrationFileContent(
  namespaceName: String,
  cls: RirClass,
  bridgeable: List<RirMethod>,
  exportName: String,
  nativeLibraryName: String,
): String {
  val dllImportParams: String = bridgeable.joinToString(", ") { m ->
    "IntPtr ${m.name.toMethodCamelCase()}Ptr"
  }

  val moduleInitArgs: String = bridgeable.joinToString(",\n                ") { m ->
    val paramTypes: String = m.parameters.joinToString(", ") { p -> csAbiType(p.type) }
    val retType: String = csAbiType(m.returnType)
    val fnTypeParams: String = if (paramTypes.isEmpty()) retType else "$paramTypes, $retType"
    "(IntPtr)(delegate* unmanaged[Cdecl]<$fnTypeParams>)(&${m.name}_Thunk)"
  }

  val thunks: String = bridgeable.joinToString("\n\n") { buildThunkMethod(cls, it) }

  return """
    |// <auto-generated>
    |// Generated by nugetGenerateShims from reverse-ir.json (ADR-049). Do not edit by hand.
    |// C# registration shim for $namespaceName.${cls.name}.
    |// </auto-generated>
    |namespace $namespaceName
    |{
    |    using System;
    |    using System.Runtime.CompilerServices;
    |    using System.Runtime.InteropServices;
    |
    |    internal static class ${cls.name}Registration
    |    {
    |        [DllImport("$nativeLibraryName", CallingConvention = CallingConvention.Cdecl,
    |            EntryPoint = "$exportName")]
    |        private static extern void $exportName($dllImportParams);
    |
    |        [ModuleInitializer]
    |        internal static unsafe void Initialize()
    |        {
    |            $exportName(
    |                $moduleInitArgs);
    |        }
    |
    |$thunks
    |    }
    |}
  """.trimMargin()
}

private fun buildThunkMethod(cls: RirClass, method: RirMethod): String {
  val thunkName: String = "${method.name}_Thunk"
  val paramList: String = method.parameters
    .joinToString(", ") { p -> "${csAbiType(p.type)} ${p.name}" }
  val retAbiType: String = csAbiType(method.returnType)
  val callArgs: String = method.parameters.joinToString(", ") { p -> paramConversion(p) }
  val callExpr: String = "${cls.name}.${method.name}($callArgs)"

  val bodyLines: List<String> = when (method.returnType) {
    is RirVoidType -> listOf("$callExpr;")

    is RirStringType -> listOf(
      "string result = $callExpr;",
      "return Marshal.StringToCoTaskMemUTF8(result);",
    )

    is RirPrimitiveType -> when (method.returnType.name) {
      "bool" -> listOf(
        "bool result = $callExpr;",
        "return result ? (byte)1 : (byte)0;",
      )
      "char" -> listOf(
        "char result = $callExpr;",
        "return (ushort)result;",
      )
      else -> listOf(
        "${csNativeType(method.returnType)} result = $callExpr;",
        "return result;",
      )
    }
  }

  val body: String = bodyLines.joinToString("\n") { "            $it" }

  // ADR-049 "let it crash": deliberately no try/catch. A thrown C# exception escapes this thunk
  // and fast-fails the host process (loud failure is preferred over a silently-wrong sentinel;
  // graceful propagation is deferred to Phase 11). Do not add exception handling here.
  return """
    |        [UnmanagedCallersOnly(CallConvs = new[] { typeof(CallConvCdecl) })]
    |        private static $retAbiType $thunkName($paramList)
    |        {
    |$body
    |        }
  """.trimMargin()
}

abstract class NugetGenerateShimsTask : DefaultTask() {
  @get:InputFile abstract val reverseIrFile: RegularFileProperty
  @get:Input abstract val nativeLibraryName: Property<String>
  @get:OutputDirectory abstract val csharpOutputDir: DirectoryProperty

  @TaskAction
  fun generate() {
    val rir: RirFile = parseReverseIr(reverseIrFile.get().asFile.readText())

    val files: List<GeneratedFile> = generateCSharpShims(
      file = rir,
      nativeLibraryName = nativeLibraryName.get(),
    )

    val outputDir: File = csharpOutputDir.get().asFile
    files.forEach { generated ->
      val out: File = outputDir.resolve(generated.relativePath)
      out.parentFile.mkdirs()
      out.writeText(generated.content)
    }
  }
}
