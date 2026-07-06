package io.github.xxfast.kotlin.native.nuget

import io.github.xxfast.kotlin.native.nuget.rir.RirClass
import io.github.xxfast.kotlin.native.nuget.rir.RirFile
import io.github.xxfast.kotlin.native.nuget.rir.RirMethod
import io.github.xxfast.kotlin.native.nuget.rir.RirObjectHandleType
import io.github.xxfast.kotlin.native.nuget.rir.RirParameter
import io.github.xxfast.kotlin.native.nuget.rir.RirPrimitiveType
import io.github.xxfast.kotlin.native.nuget.rir.RirStringType
import io.github.xxfast.kotlin.native.nuget.rir.RirTypeKey
import io.github.xxfast.kotlin.native.nuget.rir.RirTypeRef
import io.github.xxfast.kotlin.native.nuget.rir.RirVoidType
import io.github.xxfast.kotlin.native.nuget.rir.boundHandleTypes
import io.github.xxfast.kotlin.native.nuget.rir.bridgeableStaticMethods
import io.github.xxfast.kotlin.native.nuget.rir.parseReverseIr
import io.github.xxfast.kotlin.native.nuget.rir.registrationExportName
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.File

private const val INTERNAL_PKG = "io.github.xxfast.kotlin.native.nuget.internal"
private const val INTERNAL_DIR = "io/github/xxfast/kotlin/native/nuget/internal"

data class GeneratedFile(
  val relativePath: String,
  val content: String,
)

fun generateKotlinStubs(
  file: RirFile,
  packageNameOverrides: Map<String, String> = emptyMap(),
  namespaceAliases: Map<String, Map<String, String>> = emptyMap(),
): List<GeneratedFile> {
  val result: MutableList<GeneratedFile> = mutableListOf()
  var needsInterop = false
  var needsRuntime = false

  // ADR-051: derive once for the whole file — both generators must use the same helper
  // (anti-drift contract, ADR-049 Alternative 10 extended by ADR-051).
  val boundTypes: Set<RirTypeKey> = boundHandleTypes(file)

  file.assemblies.forEach { assembly ->
    val aliases: Map<String, String> = namespaceAliases[assembly.packageId] ?: emptyMap()
    val packageOverride: String? = packageNameOverrides[assembly.packageId]

    assembly.namespaces.forEach { namespace ->
      val kotlinPkg: String = aliases[namespace.name]
        ?: packageOverride
        ?: assembly.packageId.lowercase().replace('-', '_')
      val pkgPath: String = kotlinPkg.replace('.', '/')

      namespace.types.filterIsInstance<RirClass>().forEach { cls ->
        val bridgeable: List<RirMethod> = bridgeableStaticMethods(cls, boundTypes)

        if (bridgeable.isEmpty()) return@forEach

        val hasString: Boolean = bridgeable.any { method ->
          method.returnType is RirStringType ||
            method.parameters.any { p -> p.type is RirStringType }
        }
        if (hasString) needsInterop = true

        // ADR-051: NugetRuntime.kt is needed whenever any bridgeable signature contains a
        // handle type — the wrapper class uses NugetObjectHandle and the Cleaner path needs
        // freeGcHandleFn. Emitted once (below), regardless of how many classes trigger it.
        val hasHandle: Boolean = bridgeable.any { method ->
          method.returnType is RirObjectHandleType ||
            method.parameters.any { p -> p.type is RirObjectHandleType }
        }
        if (hasHandle) needsRuntime = true

        val exportName: String = registrationExportName(namespace.name, cls.name)

        result.add(GeneratedFile(
          relativePath = "nativeMain/$pkgPath/${cls.name}Bindings.kt",
          content = bindingsFileContent(kotlinPkg, cls, bridgeable, exportName, assembly.packageId),
        ))
        result.add(GeneratedFile(
          relativePath = "nativeMain/$pkgPath/${cls.name}.kt",
          content = stubFileContent(kotlinPkg, cls, bridgeable, assembly.packageId),
        ))
      }
    }
  }

  if (needsInterop) {
    result.add(GeneratedFile(
      relativePath = "nativeMain/$INTERNAL_DIR/NugetInterop.kt",
      content = nugetInteropExpect(),
    ))
    result.add(GeneratedFile(
      relativePath = "mingwMain/$INTERNAL_DIR/NugetInterop.kt",
      content = nugetInteropMingw(),
    ))
    result.add(GeneratedFile(
      relativePath = "posixMain/$INTERNAL_DIR/NugetInterop.kt",
      content = nugetInteropPosix(),
    ))
  }

  if (needsRuntime) {
    result.add(GeneratedFile(
      relativePath = "nativeMain/$INTERNAL_DIR/NugetRuntime.kt",
      content = nugetRuntimeContent(),
    ))
  }

  return result
}

// PascalCase method name → camelCase: lowercase the first character only.
// e.g. SerializeObject → serializeObject
private fun String.toMethodCamelCase(): String = replaceFirstChar { it.lowercaseChar() }

private fun kotlinType(type: RirTypeRef): String = when (type) {
  is RirVoidType -> "Unit"
  is RirStringType -> "String"
  // ADR-051: the Kotlin type name for a handle is simply the C# simple type name (e.g. Template).
  is RirObjectHandleType -> type.name
  is RirPrimitiveType -> when (type.name) {
    "bool" -> "Boolean"
    "byte" -> "UByte"
    "short" -> "Short"
    "int" -> "Int"
    "long" -> "Long"
    "float" -> "Float"
    "double" -> "Double"
    "char" -> "Char"
    else -> error(
      "[nuget] Unknown primitive type name '${type.name}' — " +
        "update the v1 type-mapping table in NugetGenerateBindingsTask.kt"
    )
  }
}

private fun cfnType(type: RirTypeRef): String = when (type) {
  is RirVoidType -> "Unit"
  is RirStringType -> "COpaquePointer?"
  // ADR-051: handles cross the ABI as IntPtr ↔ COpaquePointer? (same slot as strings).
  is RirObjectHandleType -> "COpaquePointer?"
  is RirPrimitiveType -> when (type.name) {
    "bool" -> "Boolean"
    "byte" -> "UByte"
    "short" -> "Short"
    "int" -> "Int"
    "long" -> "Long"
    "float" -> "Float"
    "double" -> "Double"
    "char" -> "UShort"
    else -> error(
      "[nuget] Unknown primitive type name '${type.name}' — " +
        "update the v1 type-mapping table in NugetGenerateBindingsTask.kt"
    )
  }
}

private fun bindingsFileContent(
  kotlinPkg: String,
  cls: RirClass,
  bridgeable: List<RirMethod>,
  exportName: String,
  packageId: String,
): String {
  val hasString: Boolean = bridgeable.any { m ->
    m.returnType is RirStringType || m.parameters.any { p -> p.type is RirStringType }
  }

  val imports: List<String> = buildList {
    if (hasString) {
      add("import $INTERNAL_PKG.freeManagedString")
      add("import kotlinx.cinterop.ByteVar")
    }
    add("import kotlinx.cinterop.CFunction")
    add("import kotlinx.cinterop.COpaquePointer")
    add("import kotlinx.cinterop.CPointer")
    add("import kotlinx.cinterop.reinterpret")
    add("import kotlin.experimental.ExperimentalNativeApi")
  }

  val fnVars: String = bridgeable.joinToString("\n\n") { method ->
    val paramCfnTypes: String = method.parameters.joinToString(", ") { cfnType(it.type) }
    val retCfnType: String = cfnType(method.returnType)
    "@Suppress(\"NOTHING_TO_INLINE\")\n" +
      "internal var ${method.name.toMethodCamelCase()}Fn: " +
        "CPointer<CFunction<($paramCfnTypes) -> $retCfnType>>? = null"
  }

  val regParams: String = bridgeable.joinToString(",\n  ") { method ->
    "${method.name.toMethodCamelCase()}Ptr: COpaquePointer"
  }

  val regBody: String = bridgeable.joinToString("\n  ") { method ->
    "${method.name.toMethodCamelCase()}Fn = ${method.name.toMethodCamelCase()}Ptr.reinterpret()"
  }

  return """
    |@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
    |
    |package $kotlinPkg
    |
    |${imports.joinToString("\n")}
    |
    |// Generated: registration machinery for $packageId.${cls.name}
    |// Do not call these functions from Kotlin code directly.
    |
    |$fnVars
    |
    |@OptIn(ExperimentalNativeApi::class)
    |// Must stay public (not internal): @CName is what makes Kotlin/Native emit this as a native
    |// C export, and internal visibility suppresses that native export entirely. The
    |// forward-direction (KSP) exporter is the one that must not re-wrap this function into
    |// another C-ABI export — it does so by skipping every @CName-annotated top-level function
    |// (see `hasCNameAnnotation()` in nuget-processor's NugetProcessor.kt), not by hiding this
    |// function from Kotlin visibility.
    |@CName("$exportName")
    |fun $exportName(
    |  $regParams,
    |) {
    |  $regBody
    |}
  """.trimMargin().trim()
}

private fun stubFileContent(
  kotlinPkg: String,
  cls: RirClass,
  bridgeable: List<RirMethod>,
  packageId: String,
): String {
  val hasStringReturn: Boolean = bridgeable.any { it.returnType is RirStringType }
  val hasStringParam: Boolean = bridgeable
    .any { m -> m.parameters.any { p -> p.type is RirStringType } }
  val hasHandle: Boolean = bridgeable.any { method ->
    method.returnType is RirObjectHandleType ||
      method.parameters.any { p -> p.type is RirObjectHandleType }
  }

  // ADR-051: a non-static class that appears as a handle type in its own bridgeable methods
  // renders as a wrapper class. Classes without handle involvement keep the ADR-048 object shape.
  val isClassWrapper: Boolean = !cls.isStatic && hasHandle
  if (isClassWrapper) {
    return classWrapperContent(kotlinPkg, cls, bridgeable, hasStringReturn, hasStringParam, packageId)
  }

  // Always required: every stub method body calls `fn.invoke(...)` on a
  // `CPointer<CFunction<...>>?` — the `invoke` operator extension is declared in kotlinx.cinterop
  // and, being an extension function, is not resolved without an explicit import (unqualified
  // calls otherwise resolve to an unrelated same-named `invoke`, e.g. kotlin.DeepRecursiveFunction,
  // producing confusing "cannot infer type parameter" errors instead of a missing-import error).
  val imports: MutableList<String> = mutableListOf("import kotlinx.cinterop.invoke")
  if (hasStringReturn) {
    imports.add("import $INTERNAL_PKG.freeManagedString")
    imports.add("import kotlinx.cinterop.ByteVar")
    imports.add("import kotlinx.cinterop.reinterpret")
    imports.add("import kotlinx.cinterop.toKString")
  }
  if (hasStringParam) {
    imports.add("import kotlinx.cinterop.cstr")
    imports.add("import kotlinx.cinterop.memScoped")
    imports.add("import kotlinx.cinterop.ptr")
  }

  val methods: String = bridgeable.joinToString("\n\n  ") { buildStubMethod(cls, it, packageId) }

  return buildString {
    appendLine("@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)")
    appendLine()
    appendLine("package $kotlinPkg")
    appendLine()
    if (imports.isNotEmpty()) {
      imports.forEach { appendLine(it) }
      appendLine()
    }
    appendLine("// Generated: Kotlin-idiomatic stubs for $packageId.${cls.name}")
    appendLine()
    // internal (not public): consumable from anywhere else in this same Gradle module (e.g. the
    // hand-authored sample-library sources that call it), but invisible to the forward-direction
    // (KSP) exporter's public-API scan — this reverse-bound API must not be re-exported forward
    // into the packed nupkg's own Interop.cs (see the matching note on the Bindings.kt file).
    appendLine("internal object ${cls.name} {")
    appendLine()
    appendLine("  $methods")
    append("}")
  }
}

// ADR-051: renders a non-static class as a full wrapper class with:
//   - internal constructor taking COpaquePointer
//   - internal NugetObjectHandle field
//   - createCleaner for automatic GCHandle release on GC
//   - AutoCloseable / close() for deterministic release
//   - companion object containing the bridged static methods
private fun classWrapperContent(
  kotlinPkg: String,
  cls: RirClass,
  bridgeable: List<RirMethod>,
  hasStringReturn: Boolean,
  hasStringParam: Boolean,
  packageId: String,
): String {
  val imports: MutableList<String> = mutableListOf(
    "import $INTERNAL_PKG.NugetObjectHandle",
    "import kotlin.experimental.ExperimentalNativeApi",
    "import kotlin.native.ref.createCleaner",
    "import kotlinx.cinterop.COpaquePointer",
    "import kotlinx.cinterop.invoke",
  )
  if (hasStringReturn) {
    imports.add("import $INTERNAL_PKG.freeManagedString")
    imports.add("import kotlinx.cinterop.ByteVar")
    imports.add("import kotlinx.cinterop.reinterpret")
    imports.add("import kotlinx.cinterop.toKString")
  }
  if (hasStringParam) {
    imports.add("import kotlinx.cinterop.cstr")
    imports.add("import kotlinx.cinterop.memScoped")
    imports.add("import kotlinx.cinterop.ptr")
  }

  val methods: String = bridgeable.joinToString("\n\n    ") { buildStubMethod(cls, it, packageId) }

  return buildString {
    appendLine("@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)")
    appendLine()
    appendLine("package $kotlinPkg")
    appendLine()
    imports.forEach { appendLine(it) }
    appendLine()
    appendLine("// Generated: Kotlin-idiomatic wrapper for $packageId.${cls.name}")
    appendLine()
    appendLine("/**")
    appendLine(" * Kotlin wrapper for the C# type `$packageId.${cls.name}`.")
    appendLine(" *")
    appendLine(" * Equality is wrapper identity: two wrappers around the same C# object are not equal.")
    appendLine(" * The underlying C# object is released automatically when this wrapper is garbage-collected;")
    appendLine(" * call [close] (or use `use { }`) for deterministic release. [close] is optional and idempotent.")
    appendLine(" */")
    appendLine("@OptIn(ExperimentalNativeApi::class)")
    appendLine("internal class ${cls.name} internal constructor(handle: COpaquePointer) : AutoCloseable {")
    appendLine("  internal val handle: NugetObjectHandle = NugetObjectHandle(handle)")
    appendLine()
    appendLine("  @Suppress(\"unused\")")
    appendLine("  private val cleaner = createCleaner(this.handle) { it.free() }")
    appendLine()
    appendLine("  override fun close(): Unit = handle.free()")
    appendLine()
    appendLine("  companion object {")
    appendLine()
    appendLine("    $methods")
    appendLine("  }")
    append("}")
  }
}

private fun buildStubMethod(cls: RirClass, method: RirMethod, packageId: String): String {
  val name: String = method.name.toMethodCamelCase()
  val fnVar: String = "${name}Fn"
  val hasStringParam: Boolean = method.parameters.any { it.type is RirStringType }

  val params: String = method.parameters.joinToString(", ") { p ->
    "${p.name}: ${kotlinType(p.type)}"
  }

  val retSuffix: String = when {
    method.returnType is RirVoidType -> ""
    // ADR-051: handle returns are nullable — IntPtr.Zero maps to null (Try/factory patterns).
    method.returnType is RirObjectHandleType -> ": ${kotlinType(method.returnType)}?"
    else -> ": ${kotlinType(method.returnType)}"
  }

  val invokeArgs: String = method.parameters.joinToString(", ") { p ->
    val type: RirTypeRef = p.type
    when {
      type is RirStringType -> "${p.name}.cstr.ptr"
      // ADR-051: unwrap the opaque pointer via handle.require() which also guards against
      // use-after-close (throws IllegalStateException if the handle was already freed).
      type is RirObjectHandleType -> "${p.name}.handle.require(\"${type.name}\")"
      type is RirPrimitiveType && type.name == "char" -> "${p.name}.code.toUShort()"
      else -> p.name
    }
  }

  val failMsg =
    "\"${cls.name} bindings are not registered. \" +\n" +
      "      \"Ensure the generated C# shims for $packageId are referenced \" +\n" +
      "      \"in the consuming application before making Kotlin → C# bridge calls.\""

  val invokeCall: String =
    if (hasStringParam) "memScoped { fn.invoke($invokeArgs) }" else "fn.invoke($invokeArgs)"

  val nullMsg: String = "${cls.name}.${method.name} returned null" +
    " — expected a non-null string pointer"

  return when (val retType = method.returnType) {
    is RirVoidType -> """
      |fun $name($params)$retSuffix {
      |    val fn = requireNotNull($fnVar) {
      |      $failMsg
      |    }
      |    $invokeCall
      |  }
    """.trimMargin()

    is RirStringType -> """
      |fun $name($params)$retSuffix {
      |    val fn = requireNotNull($fnVar) {
      |      $failMsg
      |    }
      |    val resultPtr = $invokeCall
      |      ?: error("$nullMsg")
      |    val result = resultPtr.reinterpret<ByteVar>().toKString()
      |    freeManagedString(resultPtr)
      |    return result
      |  }
    """.trimMargin()

    // ADR-051: a handle return wraps the raw COpaquePointer in a new Kotlin wrapper instance.
    // IntPtr.Zero from C# maps to null ptr → Kotlin null (no wrapper allocated).
    is RirObjectHandleType -> """
      |fun $name($params)$retSuffix {
      |    val fn = requireNotNull($fnVar) {
      |      $failMsg
      |    }
      |    val ptr: COpaquePointer? = $invokeCall
      |    return ptr?.let { ${retType.name}(it) }
      |  }
    """.trimMargin()

    is RirPrimitiveType -> {
      val isChar: Boolean = retType.name == "char"
      val returnExpr: String = if (isChar) "$invokeCall.toInt().toChar()" else invokeCall
      """
        |fun $name($params)$retSuffix {
        |    val fn = requireNotNull($fnVar) {
        |      $failMsg
        |    }
        |    return $returnExpr
        |  }
      """.trimMargin()
    }
  }
}

private fun nugetInteropExpect(): String = """
  |@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
  |
  |package $INTERNAL_PKG
  |
  |import kotlinx.cinterop.COpaquePointer
  |
  |internal expect fun freeManagedString(ptr: COpaquePointer?)
""".trimMargin().trim()

private fun nugetInteropMingw(): String = """
  |@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
  |
  |package $INTERNAL_PKG
  |
  |import kotlinx.cinterop.COpaquePointer
  |import platform.windows.CoTaskMemFree
  |
  |internal actual fun freeManagedString(ptr: COpaquePointer?) {
  |  ptr?.let { CoTaskMemFree(it) }
  |}
""".trimMargin().trim()

private fun nugetInteropPosix(): String = """
  |@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
  |
  |package $INTERNAL_PKG
  |
  |import kotlinx.cinterop.COpaquePointer
  |import platform.posix.free
  |
  |internal actual fun freeManagedString(ptr: COpaquePointer?) {
  |  ptr?.let { free(it) }
  |}
""".trimMargin().trim()

// ADR-051: NugetRuntime.kt — shared runtime support emitted once into the internal package
// whenever any bound signature contains a RirObjectHandleType. Contains:
//   - freeGcHandleFn: the registered C# thunk for freeing a GCHandle
//   - nuget_runtime_register: the @CName export that C# calls at startup
//   - NugetObjectHandle: the Cleaner resource holder (separate from the wrapper to avoid
//     the createCleaner self-reference leak hazard)
private fun nugetRuntimeContent(): String = """
  |@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
  |
  |package $INTERNAL_PKG
  |
  |import kotlin.concurrent.AtomicInt
  |import kotlinx.cinterop.COpaquePointer
  |import kotlinx.cinterop.CFunction
  |import kotlinx.cinterop.CPointer
  |import kotlinx.cinterop.invoke
  |import kotlinx.cinterop.reinterpret
  |import kotlin.experimental.ExperimentalNativeApi
  |
  |internal var freeGcHandleFn: CPointer<CFunction<(COpaquePointer) -> Unit>>? = null
  |
  |@OptIn(ExperimentalNativeApi::class)
  |@CName("nuget_runtime_register")
  |fun nuget_runtime_register(freeGcHandlePtr: COpaquePointer) {
  |  freeGcHandleFn = freeGcHandlePtr.reinterpret()
  |}
  |
  |// Holder passed as the Cleaner resource. Deliberately a separate object from the wrapper so the
  |// cleanup lambda captures nothing (createCleaner self-reference leak hazard).
  |internal class NugetObjectHandle(private val raw: COpaquePointer) {
  |  private val freed = AtomicInt(0)
  |
  |  fun free() {
  |    if (freed.compareAndSet(0, 1)) {
  |      val fn = requireNotNull(freeGcHandleFn) {
  |        "NuGet interop runtime is not registered. Ensure the generated C# shims are " +
  |          "loaded in the host process before Kotlin ${'→'} C# bridge objects are released."
  |      }
  |      fn.invoke(raw)
  |    }
  |  }
  |
  |  fun require(typeName: String): COpaquePointer {
  |    check(freed.value == 0) { "${'$'}typeName is closed — the underlying C# object handle was already released." }
  |    return raw
  |  }
  |}
""".trimMargin().trim()

abstract class NugetGenerateBindingsTask : DefaultTask() {
  @get:InputFile abstract val reverseIrFile: RegularFileProperty
  @get:Input abstract val packageNameOverrides: MapProperty<String, String>
  @get:Input abstract val namespaceAliases: MapProperty<String, Map<String, String>>
  @get:OutputDirectory abstract val kotlinOutputDir: DirectoryProperty

  @TaskAction
  fun generate() {
    val rir: RirFile = parseReverseIr(reverseIrFile.get().asFile.readText())

    val files: List<GeneratedFile> = generateKotlinStubs(
      file = rir,
      packageNameOverrides = packageNameOverrides.get(),
      namespaceAliases = namespaceAliases.get(),
    )

    val outputDir: File = kotlinOutputDir.get().asFile
    files.forEach { generated ->
      val out: File = outputDir.resolve(generated.relativePath)
      out.parentFile.mkdirs()
      out.writeText(generated.content)
    }
  }
}
