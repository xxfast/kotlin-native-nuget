package io.github.xxfast.kotlin.native.nuget

import io.github.xxfast.kotlin.native.nuget.rir.NUGET_RUNTIME_CONTRACT_HASH
import io.github.xxfast.kotlin.native.nuget.rir.RirClass
import io.github.xxfast.kotlin.native.nuget.rir.RirConstructor
import io.github.xxfast.kotlin.native.nuget.rir.RirEnumType
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
import io.github.xxfast.kotlin.native.nuget.rir.contractHash
import io.github.xxfast.kotlin.native.nuget.rir.isNullable
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

            is RirRegistrable.PropertyGetter -> !r.property.isStatic
            is RirRegistrable.PropertySetter -> !r.property.isStatic
          }
        }
        if (hasHandle) needsRuntime = true

        val exportName: String = registrationExportName(namespace.name, cls.name)

        result.add(
          GeneratedFile(
            relativePath = "${cls.name}Registration.cs",
            content = registrationFileContent(
              namespaceName = namespace.name,
              cls = cls,
              registrables = registrables,
              exportName = exportName,
              nativeLibraryName = nativeLibraryName,
            ),
          )
        )
      }
    }
  }

  if (needsRuntime) {
    result.add(
      GeneratedFile(
        relativePath = "NugetRuntimeRegistration.cs",
        content = nugetRuntimeRegistrationContent(nativeLibraryName),
      )
    )
  }

  // ADR-054: NugetTrace.cs is needed exactly whenever anything else here is — every
  // [ModuleInitializer] this file emits (per-type and the shared runtime one) calls
  // NugetTrace.Write/WriteAlways around its registration P/Invoke.
  if (result.isNotEmpty()) {
    result.add(
      GeneratedFile(
        relativePath = "NugetTrace.cs",
        content = nugetTraceCsContent(),
      )
    )
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
  is RirEnumType -> "int"
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
  is RirEnumType -> type.name
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
// ADR-053: a nullable-annotated string parameter drops the null-forgiving `!` — the parameter may
// legitimately be null, and Marshal.PtrToStringUTF8 already returns `string?`.
private fun paramConversion(p: RirParameter): String = when (p.type) {
  is RirStringType ->
    if (p.type.nullable) "Marshal.PtrToStringUTF8(${thunkParamName(p)})"
    else "Marshal.PtrToStringUTF8(${thunkParamName(p)})!"
  // ADR-051: unpack the handle back to the managed type via GCHandle.FromIntPtr.
  is RirObjectHandleType -> "(${p.type.name})GCHandle.FromIntPtr(${thunkParamName(p)}).Target!"
  is RirEnumType -> "(${p.type.name})${thunkParamName(p)}"
  is RirVoidType -> thunkParamName(p)
  is RirPrimitiveType -> when (p.type.name) {
    "bool" -> "${thunkParamName(p)} != 0"
    "char" -> "(char)${thunkParamName(p)}"
    else -> thunkParamName(p)
  }
}

// ADR-053: a nullable-annotated handle parameter cannot be unpacked inline via the plain
// GCHandle.FromIntPtr(...).Target! expression paramConversion above uses — IntPtr.Zero (the null
// sentinel every handle-typed slot already uses, ADR-051) throws there. It is guarded instead with
// an IntPtr.Zero check bound to its own local variable, which the call expression then simply
// references by name; every other parameter shape keeps the existing inline paramConversion(p)
// expression untouched — this only special-cases the one shape that needs a statement of its own.
private data class ParamBinding(val declarationLines: List<String>, val expression: String)

private fun paramBinding(p: RirParameter): ParamBinding {
  val type: RirTypeRef = p.type
  if (type is RirObjectHandleType && type.nullable) {
    val handleName: String = thunkParamName(p)
    return ParamBinding(
      declarationLines = listOf(
        "${type.name}? ${p.name} = $handleName == IntPtr.Zero",
        "    ? null",
        "    : (${type.name})GCHandle.FromIntPtr($handleName).Target!;",
      ),
      expression = p.name,
    )
  }
  return ParamBinding(declarationLines = emptyList(), expression = paramConversion(p))
}

// Every enum type referenced by a class's registrable members, deduplicated. Unlike the Kotlin
// side, no lookup table is needed to resolve one: RirEnumType.namespace already IS the C#
// namespace the enum is declared in.
private fun referencedEnumTypes(registrables: List<RirRegistrable>): List<RirEnumType> =
  registrables.flatMap { r ->
    when (r) {
      is RirRegistrable.Ctor -> r.ctor.parameters.mapNotNull { it.type as? RirEnumType }
      is RirRegistrable.Method -> listOfNotNull(r.method.returnType as? RirEnumType) +
          r.method.parameters.mapNotNull { it.type as? RirEnumType }

      is RirRegistrable.PropertyGetter -> listOfNotNull(r.property.type as? RirEnumType)
      is RirRegistrable.PropertySetter -> listOfNotNull(r.property.type as? RirEnumType)
    }
  }.distinct()

private fun registrationFileContent(
  namespaceName: String,
  cls: RirClass,
  registrables: List<RirRegistrable>,
  exportName: String,
  nativeLibraryName: String,
): String {
  // A shim renders inside `namespace $namespaceName`, so an enum declared in any other namespace
  // (a C# enum in `Sample.Enums` referenced by a class in `Sample.Text`, say) is out of scope for
  // the `(Mood)mood` casts in the thunk bodies below unless its namespace is imported here.
  val enumNamespaces: List<String> = referencedEnumTypes(registrables)
    .map { it.namespace }
    .distinct()
    .filter { it != namespaceName }
    .sorted()

  // ADR-054: IoGithubXxfast.KotlinNativeNuget carries NugetTrace, referenced by the
  // [ModuleInitializer] below in every generated {Type}Registration.cs.
  val usings: String = (
      listOf(
        "System", "System.Runtime.CompilerServices", "System.Runtime.InteropServices",
        "IoGithubXxfast.KotlinNativeNuget",
      ) + enumNamespaces
      ).joinToString("\n") { "    using $it;" }

  // ADR-054: the register export's contract — both baked identically from the same shared
  // contractHash() function NugetGenerateBindingsTask calls, so within one build the two
  // generated sides can never disagree on either value.
  val slotCount: Int = registrables.size
  val hash: Long = contractHash(cls, registrables)
  val qualifiedType: String = "$namespaceName.${cls.name}"
  val slotWord: String = if (slotCount == 1) "slot" else "slots"

  // ADR-052: rendered directly off the shared bridgeableRegistrables() ordering — ctorPtr first
  // (if any), then method pointers — matching NugetGenerateBindingsTask's register signature.
  // ADR-054: slotCount/contractHash precede the pointer parameters (both int/long — see the
  // amended ADR-048 contract). registrables is never empty here (the caller returns early when
  // it is), so prepending the two leading params as a plain string is safe — no dangling comma.
  val registrableParams: String = registrables.joinToString(", ") { r ->
    when (r) {
      is RirRegistrable.Ctor -> "IntPtr ctorPtr"
      is RirRegistrable.Method -> "IntPtr ${r.method.name.toMethodCamelCase()}Ptr"
      is RirRegistrable.PropertyGetter -> "IntPtr ${r.property.name.toMethodCamelCase()}GetterPtr"
      is RirRegistrable.PropertySetter -> "IntPtr ${r.property.name.toMethodCamelCase()}SetterPtr"
    }
  }
  val dllImportParams: String = "int slotCount, long contractHash, $registrableParams"

  val moduleInitArgs: String = (listOf("$slotCount", "${hash}L") + registrables.map { r ->
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
        val receiverType: String = if (r.property.isStatic) "" else "IntPtr, "
        "(IntPtr)(delegate* unmanaged[Cdecl]<$receiverType$retType>)(&${r.property.name}_Get_Thunk)"
      }

      is RirRegistrable.PropertySetter -> {
        val valueType: String = csAbiType(r.property.type)
        val receiverType: String = if (r.property.isStatic) "" else "IntPtr, "
        "(IntPtr)(delegate* unmanaged[Cdecl]<$receiverType$valueType, void>)(&${r.property.name}_Set_Thunk)"
      }
    }
  }).joinToString(",\n                    ")

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
    |$usings
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
    |            NugetTrace.Write(
    |                "register enter $qualifiedType -> $exportName($slotCount $slotWord) dll=$nativeLibraryName");
    |            try
    |            {
    |                $exportName(
    |                    $moduleInitArgs);
    |            }
    |            catch (DllNotFoundException e)
    |            {
    |                NugetTrace.WriteAlways(${'$'}"FATAL: native library '$nativeLibraryName' not found: {e.Message}");
    |                throw;
    |            }
    |            catch (EntryPointNotFoundException e)
    |            {
    |                NugetTrace.WriteAlways(${'$'}"FATAL: export '$exportName' missing from " +
    |                    ${'$'}"'$nativeLibraryName'. The native library predates this shim (stale build state). {e.Message}");
    |                throw;
    |            }
    |            NugetTrace.Write("register ok    $qualifiedType");
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
  // ADR-053: a nullable-annotated handle parameter needs its own guarded local declaration (see
  // paramBinding) instead of being converted inline — gathered here so those declarations can be
  // emitted as statements ahead of the call, while every other parameter still converts inline.
  val paramBindings: List<ParamBinding> = method.parameters.map { paramBinding(it) }
  val paramDeclarationLines: List<String> = paramBindings.flatMap { it.declarationLines }
  val callArgs: String = paramBindings.joinToString(", ") { it.expression }
  val receiverLine: String? = if (!method.isStatic) {
    "${cls.name} receiver = (${cls.name})GCHandle.FromIntPtr(selfHandle).Target!;"
  } else {
    null
  }
  val callExpr: String =
    if (method.isStatic) "${cls.name}.${method.name}($callArgs)" else "receiver.${method.name}($callArgs)"

  val callBodyLines: List<String> = when (method.returnType) {
    is RirVoidType -> listOf("$callExpr;")

    // ADR-053: a nullable-annotated string return declares its local as `string?` — the shim's
    // `#nullable enable` would otherwise warn CS8600 on assigning a possibly-null string to a
    // non-null local. A non-null-annotated return (including an oblivious one) is unaffected.
    is RirStringType -> listOf(
      "${if (method.returnType.isNullable) "string?" else "string"} result = $callExpr;",
      "return Marshal.StringToCoTaskMemUTF8(result);",
    )

    // ADR-051/ADR-053: wrap the returned object in a Normal GCHandle, return its IntPtr. If the
    // method returns null, return IntPtr.Zero. This body is deliberately IDENTICAL for both a
    // nullable- and a non-null-annotated handle return: GCHandle.Alloc(null) is legal and would
    // otherwise leak a non-zero handle whose Target is null, so the null check stays even when the
    // C# API claims the return is never null — a lying API becomes a clear Kotlin-side
    // IllegalStateException instead of a crash deep in some later thunk.
    is RirObjectHandleType -> listOf(
      "${csNativeType(method.returnType)}? result = $callExpr;",
      "return result is null ? IntPtr.Zero : GCHandle.ToIntPtr(GCHandle.Alloc(result));",
    )

    is RirEnumType -> listOf(
      "${csNativeType(method.returnType)} result = $callExpr;",
      "return (int)result;",
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

  val bodyLines: List<String> = listOfNotNull(receiverLine) + paramDeclarationLines + callBodyLines
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
  val receiverLine: String? = if (property.isStatic) null
  else "${cls.name} receiver = (${cls.name})GCHandle.FromIntPtr(selfHandle).Target!;"
  val getExpr: String = if (property.isStatic) "${cls.name}.${property.name}"
  else "receiver.${property.name}"

  val bodyLines: List<String> = when (property.type) {
    is RirVoidType -> error("[nuget] a property cannot have void type")

    // ADR-053: same nullable-local treatment as buildThunkMethod's return handling above.
    is RirStringType -> listOf(
      "${if (property.type.isNullable) "string?" else "string"} result = $getExpr;",
      "return Marshal.StringToCoTaskMemUTF8(result);",
    )

    // ADR-051/ADR-053: wrap the returned object in a Normal GCHandle, return its IntPtr. If the
    // property returns null, return IntPtr.Zero — identical for both a nullable- and a non-null-
    // annotated property, same rationale as buildThunkMethod's handle-return branch above.
    is RirObjectHandleType -> listOf(
      "${csNativeType(property.type)}? result = $getExpr;",
      "return result is null ? IntPtr.Zero : GCHandle.ToIntPtr(GCHandle.Alloc(result));",
    )

    is RirEnumType -> listOf(
      "${csNativeType(property.type)} result = $getExpr;",
      "return (int)result;",
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

  val body: String = (listOfNotNull(receiverLine) + bodyLines).joinToString("\n") { "            $it" }
  val params: String = if (property.isStatic) "" else "IntPtr selfHandle"

  // ADR-049 "let it crash" (unchanged, Phase 9 line 151): no try/catch.
  return """
    |        [UnmanagedCallersOnly(CallConvs = new[] { typeof(CallConvCdecl) })]
    |        private static $retAbiType $thunkName($params)
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
  val receiverLine: String? = if (property.isStatic) null
  else "${cls.name} receiver = (${cls.name})GCHandle.FromIntPtr(selfHandle).Target!;"
  val assignTarget: String = if (property.isStatic) "${cls.name}.${property.name}"
  else "receiver.${property.name}"
  val valueBinding: ParamBinding = paramBinding(valueParam)
  val assignLine = "$assignTarget = ${valueBinding.expression};"
  val body: String = (listOfNotNull(receiverLine) + valueBinding.declarationLines + assignLine)
    .joinToString("\n") { "            $it" }
  val receiverParam: String = if (property.isStatic) "" else "IntPtr selfHandle, "

  // ADR-049 "let it crash" (unchanged, Phase 9 line 151): no try/catch.
  return """
    |        [UnmanagedCallersOnly(CallConvs = new[] { typeof(CallConvCdecl) })]
    |        private static void $thunkName($receiverParam$valueAbiType $valueParamName)
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
  val paramBindings: List<ParamBinding> = ctor.parameters.map { paramBinding(it) }
  val paramDeclarationLines: List<String> = paramBindings.flatMap { it.declarationLines }
  val callArgs: String = paramBindings.joinToString(", ") { it.expression }

  val bodyLines: List<String> = paramDeclarationLines + listOf(
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
  |        private static extern void nuget_runtime_register(int slotCount, long contractHash, IntPtr freeGcHandlePtr);
  |
  |        [ModuleInitializer]
  |        internal static unsafe void Initialize()
  |        {
  |            NugetTrace.Write(
  |                "register enter <runtime> -> nuget_runtime_register(1 slot) dll=$nativeLibraryName");
  |            try
  |            {
  |                nuget_runtime_register(
  |                    1,
  |                    ${NUGET_RUNTIME_CONTRACT_HASH}L,
  |                    (IntPtr)(delegate* unmanaged[Cdecl]<IntPtr, void>)(&FreeGcHandle_Thunk));
  |            }
  |            catch (DllNotFoundException e)
  |            {
  |                NugetTrace.WriteAlways(${'$'}"FATAL: native library '$nativeLibraryName' not found: {e.Message}");
  |                throw;
  |            }
  |            catch (EntryPointNotFoundException e)
  |            {
  |                NugetTrace.WriteAlways(${'$'}"FATAL: export 'nuget_runtime_register' missing from " +
  |                    ${'$'}"'$nativeLibraryName'. The native library predates this shim (stale build state). {e.Message}");
  |                throw;
  |            }
  |            NugetTrace.Write("register ok    <runtime>");
  |        }
  |
  |        // Called from Kotlin's Cleaner thread or close(); GCHandle.Free is thread-safe and the
  |        // CLR attaches unknown native threads automatically on entry.
  |        [UnmanagedCallersOnly(CallConvs = new[] { typeof(CallConvCdecl) })]
  |        private static void FreeGcHandle_Thunk(IntPtr handle) => GCHandle.FromIntPtr(handle).Free();
  |    }
  |}
""".trimMargin().trim()

// ADR-054: NugetTrace.cs — the opt-in registration trace sink, C# side. Emitted once per
// generateCSharpShims run, whenever anything else is (every generated [ModuleInitializer]
// references it). xunit 2.9.3 (this repo's harness) removed Console capture entirely, so the
// default sink is stderr, not Console.Out — a Console.WriteLine here would be invisible at
// exactly the moment it matters (mid-test-run registration failure).
private fun nugetTraceCsContent(): String = """
  |// <auto-generated>
  |// Generated by nugetGenerateShims (ADR-054). Do not edit by hand.
  |// Opt-in registration trace: NUGET_INTEROP_TRACE=1 (also "true"/"all") enables it;
  |// NUGET_INTEROP_TRACEFILE=<path> redirects from stderr to a file (appended).
  |// </auto-generated>
  |#nullable enable
  |
  |namespace IoGithubXxfast.KotlinNativeNuget
  |{
  |    using System;
  |    using System.IO;
  |
  |    internal static class NugetTrace
  |    {
  |        private static readonly bool s_enabled = IsEnabled();
  |        private static readonly string? s_file =
  |            Environment.GetEnvironmentVariable("NUGET_INTEROP_TRACEFILE");
  |
  |        private static bool IsEnabled() =>
  |            Environment.GetEnvironmentVariable("NUGET_INTEROP_TRACE") is "1" or "true" or "all";
  |
  |        // The only two call sites are [ModuleInitializer]s, once per registration at process
  |        // start — never on the hot bridge-call path (there is no such path on the C# side; the
  |        // thunks ARE the hot path and none of them call this).
  |        internal static void Write(string message)
  |        {
  |            if (!s_enabled) return;
  |            WriteAlways(message);
  |        }
  |
  |        // Bypasses the env-var gate: a fatal condition (native library not found, export
  |        // missing) is not opt-in.
  |        internal static void WriteAlways(string message)
  |        {
  |            string line = ${'$'}"[nuget:shim] {message}";
  |            if (s_file is null) Console.Error.WriteLine(line);
  |            else File.AppendAllText(s_file, line + Environment.NewLine);
  |        }
  |    }
  |}
""".trimMargin().trim()

abstract class NugetGenerateShimsTask : DefaultTask() {
  @get:InputFile
  abstract val reverseIrFile: RegularFileProperty

  @get:Input
  abstract val nativeLibraryName: Property<String>

  @get:OutputDirectory
  abstract val csharpOutputDir: DirectoryProperty

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
