package io.github.xxfast.kotlin.native.nuget

import io.github.xxfast.kotlin.native.nuget.rir.RirClass
import io.github.xxfast.kotlin.native.nuget.rir.RirConstructor
import io.github.xxfast.kotlin.native.nuget.rir.RirFile
import io.github.xxfast.kotlin.native.nuget.rir.RirMethod
import io.github.xxfast.kotlin.native.nuget.rir.RirObjectHandleType
import io.github.xxfast.kotlin.native.nuget.rir.RirParameter
import io.github.xxfast.kotlin.native.nuget.rir.RirPrimitiveType
import io.github.xxfast.kotlin.native.nuget.rir.RirProperty
import io.github.xxfast.kotlin.native.nuget.rir.RirRegistrable
import io.github.xxfast.kotlin.native.nuget.rir.RirStringType
import io.github.xxfast.kotlin.native.nuget.rir.RirTypeKey
import io.github.xxfast.kotlin.native.nuget.rir.RirTypeRef
import io.github.xxfast.kotlin.native.nuget.rir.RirVoidType
import io.github.xxfast.kotlin.native.nuget.rir.boundHandleTypes
import io.github.xxfast.kotlin.native.nuget.rir.bridgeableRegistrables
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
// ADR-051 extension: handle-typed parameters use GCHandle.FromIntPtr; handle-typed returns use
// GCHandle.Alloc + GCHandle.ToIntPtr (non-null) or IntPtr.Zero (null). Additionally emits one
// NugetRuntimeRegistration.cs when any handle type appears in the IR.
//
// Exception behaviour (ADR-049 "let it crash"): thunk bodies never catch. A thrown C# exception
// escapes the [UnmanagedCallersOnly] method and fast-fails the host process — this is deliberate,
// not an oversight; do not "harden" a thunk with try/catch (see ADR-049 Consequences).
fun generateCSharpShims(file: RirFile, nativeLibraryName: String): List<GeneratedFile> {
  val result: MutableList<GeneratedFile> = mutableListOf()
  var needsRuntime = false

  // ADR-051: derive once for the whole file — same helper as generateKotlinStubs (anti-drift).
  val boundTypes: Set<RirTypeKey> = boundHandleTypes(file)

  file.assemblies.forEach { assembly ->
    assembly.namespaces.forEach { namespace ->
      namespace.types.filterIsInstance<RirClass>().forEach { cls ->
        // ADR-052 "shared bridgeable ordering": the exact same ordered list (constructor first,
        // then bridgeable static methods) that NugetGenerateBindingsTask derives its register
        // signature/body from — anti-drift for the DllImport params / ModuleInitializer args
        // below.
        val registrables: List<RirRegistrable> = bridgeableRegistrables(cls, boundTypes)

        if (registrables.isEmpty()) return@forEach

        // ADR-051/052/Phase 9 line 151: track whether any method has a handle type, the class has
        // a public instance constructor (a constructor's return is implicitly a handle —
        // GCHandle.Alloc), or the class has any instance method/property at all — every one of
        // these forces the Kotlin side into the ADR-051 wrapper shape (NugetObjectHandle +
        // Cleaner), which always calls the shared free-handle thunk this file registers,
        // regardless of whether any individual signature happens to use a handle TYPE — for
        // NugetRuntimeRegistration.cs.
        val hasHandle: Boolean = registrables.any { r ->
          when (r) {
            is RirRegistrable.Ctor -> true
            is RirRegistrable.Method -> r.method.returnType is RirObjectHandleType ||
              r.method.parameters.any { p -> p.type is RirObjectHandleType } || !r.method.isStatic
            is RirRegistrable.PropertyGetter -> true
            is RirRegistrable.PropertySetter -> true
          }
        }
        if (hasHandle) needsRuntime = true

        val exportName: String = registrationExportName(namespace.name, cls.name)

        result.add(GeneratedFile(
          relativePath = "${cls.name}Registration.cs",
          content = registrationFileContent(
            namespaceName = namespace.name,
            cls = cls,
            registrables = registrables,
            exportName = exportName,
            nativeLibraryName = nativeLibraryName,
          ),
        ))
      }
    }
  }

  if (needsRuntime) {
    result.add(GeneratedFile(
      relativePath = "NugetRuntimeRegistration.cs",
      content = nugetRuntimeRegistrationContent(nativeLibraryName),
    ))
  }

  return result
}

// ADR-049 "Inverse type-mapping table": the C# type that crosses the [UnmanagedCallersOnly]
// boundary itself. IntPtr for strings (avoids `unsafe` everywhere but the [ModuleInitializer]'s
// function-pointer address-of expression); byte/ushort narrowing for the two non-blittable
// primitives `bool`/`char` (System.Boolean and System.Char are not blittable — see ADR-049's
// "two non-obvious blittability corrections"); every other primitive crosses directly.
// ADR-051: handles also cross as IntPtr (GCHandle.ToIntPtr).
private fun csAbiType(type: RirTypeRef): String = when (type) {
  is RirVoidType -> "void"
  is RirStringType -> "IntPtr"
  is RirObjectHandleType -> "IntPtr"
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
  // ADR-051: the natural C# type for a handle is the simple type name (e.g. Template).
  is RirObjectHandleType -> type.name
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

// ADR-051: thunk-level parameter name includes a type-specific suffix so the C# variable name
// distinguishes the raw ABI value from the converted managed value in the body.
//   string "source" → "sourcePtr"  (matches the existing string-param convention)
//   handle "template" → "templateHandle"
//   other "value" → "value" (no suffix)
private fun thunkParamName(p: RirParameter): String = when (p.type) {
  is RirStringType -> "${p.name}Ptr"
  is RirObjectHandleType -> "${p.name}Handle"
  else -> p.name
}

// Converts a thunk-level (ABI) parameter into the expression passed to the real C# method call.
private fun paramConversion(p: RirParameter): String = when (p.type) {
  is RirStringType -> "Marshal.PtrToStringUTF8(${thunkParamName(p)})!"
  // ADR-051: unpack the handle back to the managed type via GCHandle.FromIntPtr.
  is RirObjectHandleType -> "(${p.type.name})GCHandle.FromIntPtr(${thunkParamName(p)}).Target!"
  is RirVoidType -> thunkParamName(p)
  is RirPrimitiveType -> when (p.type.name) {
    "bool" -> "${thunkParamName(p)} != 0"
    "char" -> "(char)${thunkParamName(p)}"
    else -> thunkParamName(p)
  }
}

private fun registrationFileContent(
  namespaceName: String,
  cls: RirClass,
  registrables: List<RirRegistrable>,
  exportName: String,
  nativeLibraryName: String,
): String {
  // ADR-052: rendered directly off the shared bridgeableRegistrables() ordering — ctorPtr first
  // (if any), then method pointers — matching NugetGenerateBindingsTask's register signature.
  val dllImportParams: String = registrables.joinToString(", ") { r ->
    when (r) {
      is RirRegistrable.Ctor -> "IntPtr ctorPtr"
      is RirRegistrable.Method -> "IntPtr ${r.method.name.toMethodCamelCase()}Ptr"
      is RirRegistrable.PropertyGetter -> "IntPtr ${r.property.name.toMethodCamelCase()}GetterPtr"
      is RirRegistrable.PropertySetter -> "IntPtr ${r.property.name.toMethodCamelCase()}SetterPtr"
    }
  }

  val moduleInitArgs: String = registrables.joinToString(",\n                ") { r ->
    when (r) {
      is RirRegistrable.Ctor -> {
        val paramTypes: String = r.ctor.parameters.joinToString(", ") { p -> csAbiType(p.type) }
        // ADR-052: Ctor_Thunk always returns IntPtr (a constructor's return is the handle itself).
        val fnTypeParams: String = if (paramTypes.isEmpty()) "IntPtr" else "$paramTypes, IntPtr"
        "(IntPtr)(delegate* unmanaged[Cdecl]<$fnTypeParams>)(&Ctor_Thunk)"
      }
      is RirRegistrable.Method -> {
        val paramTypes: String = r.method.parameters.joinToString(", ") { p -> csAbiType(p.type) }
        val retType: String = csAbiType(r.method.returnType)
        // Phase 9 (ROADMAP line 151): an instance method thunk keeps the existing bare
        // `{MethodName}_Thunk` name (methods are already named this way regardless of
        // static/instance) — only its parameter list gains a leading IntPtr selfHandle, already
        // reflected in buildThunkMethod's paramList; the delegate* type here must match.
        val selfParamType: String? = if (!r.method.isStatic) "IntPtr" else null
        val allParamTypes: String = listOfNotNull(selfParamType, paramTypes.ifEmpty { null })
          .joinToString(", ")
        val fnTypeParams: String = if (allParamTypes.isEmpty()) retType else "$allParamTypes, $retType"
        "(IntPtr)(delegate* unmanaged[Cdecl]<$fnTypeParams>)(&${r.method.name}_Thunk)"
      }
      is RirRegistrable.PropertyGetter -> {
        val retType: String = csAbiType(r.property.type)
        "(IntPtr)(delegate* unmanaged[Cdecl]<IntPtr, $retType>)(&${r.property.name}_Get_Thunk)"
      }
      is RirRegistrable.PropertySetter -> {
        val valueType: String = csAbiType(r.property.type)
        "(IntPtr)(delegate* unmanaged[Cdecl]<IntPtr, $valueType, void>)(&${r.property.name}_Set_Thunk)"
      }
    }
  }

  val thunks: String = registrables.joinToString("\n\n") { r ->
    when (r) {
      is RirRegistrable.Ctor -> buildCtorThunkMethod(cls, r.ctor)
      is RirRegistrable.Method -> buildThunkMethod(cls, r.method)
      is RirRegistrable.PropertyGetter -> buildPropertyGetterThunkMethod(cls, r.property)
      is RirRegistrable.PropertySetter -> buildPropertySetterThunkMethod(cls, r.property)
    }
  }

  return """
    |// <auto-generated>
    |// Generated by nugetGenerateShims from reverse-ir.json (ADR-049). Do not edit by hand.
    |// C# registration shim for $namespaceName.${cls.name}.
    |// </auto-generated>
    |#nullable enable
    |
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

  // Phase 9 (ROADMAP line 151): "an instance thunk is a static thunk whose first parameter is the
  // receiver handle" (ADR-051 Deferred section). Resolve the receiver via the exact same
  // GCHandle.FromIntPtr(...).Target! pattern paramConversion already uses for handle-typed
  // parameters, then call the method on the resolved receiver instance rather than statically on
  // the type. Static methods are unaffected.
  val selfParam: String? = if (!method.isStatic) "IntPtr selfHandle" else null
  val paramList: String = (listOfNotNull(selfParam) +
    method.parameters.map { p -> "${csAbiType(p.type)} ${thunkParamName(p)}" }).joinToString(", ")
  val retAbiType: String = csAbiType(method.returnType)
  val callArgs: String = method.parameters.joinToString(", ") { p -> paramConversion(p) }
  val receiverLine: String? = if (!method.isStatic) {
    "${cls.name} receiver = (${cls.name})GCHandle.FromIntPtr(selfHandle).Target!;"
  } else {
    null
  }
  val callExpr: String =
    if (method.isStatic) "${cls.name}.${method.name}($callArgs)" else "receiver.${method.name}($callArgs)"

  val callBodyLines: List<String> = when (method.returnType) {
    is RirVoidType -> listOf("$callExpr;")

    is RirStringType -> listOf(
      "string result = $callExpr;",
      "return Marshal.StringToCoTaskMemUTF8(result);",
    )

    // ADR-051: wrap the returned object in a Normal GCHandle, return its IntPtr. If the
    // method returns null, return IntPtr.Zero (ADR-051 §Nullability).
    is RirObjectHandleType -> listOf(
      "${csNativeType(method.returnType)}? result = $callExpr;",
      "return result is null ? IntPtr.Zero : GCHandle.ToIntPtr(GCHandle.Alloc(result));",
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

  val bodyLines: List<String> = listOfNotNull(receiverLine) + callBodyLines
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

// Phase 9 (ROADMAP line 151): a property getter thunk — always takes IntPtr selfHandle (the
// receiver), resolves it via GCHandle.FromIntPtr(...).Target!, and returns the marshalled value
// (Marshal.StringToCoTaskMemUTF8 for a string-typed property, per ADR-048/049).
private fun buildPropertyGetterThunkMethod(cls: RirClass, property: RirProperty): String {
  val thunkName = "${property.name}_Get_Thunk"
  val retAbiType: String = csAbiType(property.type)
  val receiverLine = "${cls.name} receiver = (${cls.name})GCHandle.FromIntPtr(selfHandle).Target!;"
  val getExpr = "receiver.${property.name}"

  val bodyLines: List<String> = when (property.type) {
    is RirVoidType -> error("[nuget] a property cannot have void type")

    is RirStringType -> listOf(
      "string result = $getExpr;",
      "return Marshal.StringToCoTaskMemUTF8(result);",
    )

    // ADR-051: wrap the returned object in a Normal GCHandle, return its IntPtr. If the
    // property returns null, return IntPtr.Zero (ADR-051 §Nullability).
    is RirObjectHandleType -> listOf(
      "${csNativeType(property.type)}? result = $getExpr;",
      "return result is null ? IntPtr.Zero : GCHandle.ToIntPtr(GCHandle.Alloc(result));",
    )

    is RirPrimitiveType -> when (property.type.name) {
      "bool" -> listOf(
        "bool result = $getExpr;",
        "return result ? (byte)1 : (byte)0;",
      )
      "char" -> listOf(
        "char result = $getExpr;",
        "return (ushort)result;",
      )
      else -> listOf(
        "${csNativeType(property.type)} result = $getExpr;",
        "return result;",
      )
    }
  }

  val body: String = (listOf(receiverLine) + bodyLines).joinToString("\n") { "            $it" }

  // ADR-049 "let it crash" (unchanged, Phase 9 line 151): no try/catch.
  return """
    |        [UnmanagedCallersOnly(CallConvs = new[] { typeof(CallConvCdecl) })]
    |        private static $retAbiType $thunkName(IntPtr selfHandle)
    |        {
    |$body
    |        }
  """.trimMargin()
}

// Phase 9 (ROADMAP line 151): a property setter thunk — takes IntPtr selfHandle (the receiver)
// plus the new value, returns void. Reuses thunkParamName/paramConversion via a synthetic
// RirParameter("value", property.type) so the marshalling rules never drift from method
// parameters (e.g. a string value thunk-parameter is named "valuePtr", a handle value
// "valueHandle").
private fun buildPropertySetterThunkMethod(cls: RirClass, property: RirProperty): String {
  val thunkName = "${property.name}_Set_Thunk"
  val valueParam = RirParameter(name = "value", type = property.type)
  val valueParamName: String = thunkParamName(valueParam)
  val valueAbiType: String = csAbiType(property.type)
  val receiverLine = "${cls.name} receiver = (${cls.name})GCHandle.FromIntPtr(selfHandle).Target!;"
  val assignLine = "receiver.${property.name} = ${paramConversion(valueParam)};"
  val body: String = listOf(receiverLine, assignLine).joinToString("\n") { "            $it" }

  // ADR-049 "let it crash" (unchanged, Phase 9 line 151): no try/catch.
  return """
    |        [UnmanagedCallersOnly(CallConvs = new[] { typeof(CallConvCdecl) })]
    |        private static void $thunkName(IntPtr selfHandle, $valueAbiType $valueParamName)
    |        {
    |$body
    |        }
  """.trimMargin()
}

// ADR-052: the constructor thunk. Mirrors ADR-051's factory thunks (e.g. Parse_Thunk) but calls
// `new` and is unconditionally non-null — a C# constructor either succeeds or throws, it never
// yields IntPtr.Zero (contrast ADR-051's nullable-factory `result is null ? IntPtr.Zero : …`).
private fun buildCtorThunkMethod(cls: RirClass, ctor: RirConstructor): String {
  val paramList: String = ctor.parameters
    .joinToString(", ") { p -> "${csAbiType(p.type)} ${thunkParamName(p)}" }
  val callArgs: String = ctor.parameters.joinToString(", ") { p -> paramConversion(p) }

  val bodyLines: List<String> = listOf(
    "var obj = new ${cls.name}($callArgs);",
    "return GCHandle.ToIntPtr(GCHandle.Alloc(obj));",
  )
  val body: String = bodyLines.joinToString("\n") { "            $it" }

  // ADR-049 "let it crash" (unchanged for constructors, ADR-052): no try/catch — a throwing C#
  // constructor escapes this thunk and fast-fails the host process.
  return """
    |        [UnmanagedCallersOnly(CallConvs = new[] { typeof(CallConvCdecl) })]
    |        private static IntPtr Ctor_Thunk($paramList)
    |        {
    |$body
    |        }
  """.trimMargin()
}

// ADR-051: NugetRuntimeRegistration.cs — emitted once per generateCSharpShims run when any
// handle type appears in the IR. Registers the shared GCHandle.Free thunk so Kotlin's Cleaner
// and close() can release handles without a static P/Invoke (ADR-041 registration table).
private fun nugetRuntimeRegistrationContent(nativeLibraryName: String): String = """
  |// <auto-generated>
  |// Generated by nugetGenerateShims (ADR-051). Do not edit by hand.
  |// Shared runtime registration for GCHandle freeing.
  |// </auto-generated>
  |#nullable enable
  |
  |namespace IoGithubXxfast.KotlinNativeNuget
  |{
  |    using System;
  |    using System.Runtime.CompilerServices;
  |    using System.Runtime.InteropServices;
  |
  |    internal static class NugetRuntimeRegistration
  |    {
  |        [DllImport("$nativeLibraryName", CallingConvention = CallingConvention.Cdecl,
  |            EntryPoint = "nuget_runtime_register")]
  |        private static extern void nuget_runtime_register(IntPtr freeGcHandlePtr);
  |
  |        [ModuleInitializer]
  |        internal static unsafe void Initialize() =>
  |            nuget_runtime_register((IntPtr)(delegate* unmanaged[Cdecl]<IntPtr, void>)(&FreeGcHandle_Thunk));
  |
  |        // Called from Kotlin's Cleaner thread or close(); GCHandle.Free is thread-safe and the
  |        // CLR attaches unknown native threads automatically on entry.
  |        [UnmanagedCallersOnly(CallConvs = new[] { typeof(CallConvCdecl) })]
  |        private static void FreeGcHandle_Thunk(IntPtr handle) => GCHandle.FromIntPtr(handle).Free();
  |    }
  |}
""".trimMargin().trim()

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
