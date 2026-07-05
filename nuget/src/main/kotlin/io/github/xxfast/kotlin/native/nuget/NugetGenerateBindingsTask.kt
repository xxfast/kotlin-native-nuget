package io.github.xxfast.kotlin.native.nuget

import io.github.xxfast.kotlin.native.nuget.rir.RirClass
import io.github.xxfast.kotlin.native.nuget.rir.RirFile
import io.github.xxfast.kotlin.native.nuget.rir.RirMethod
import io.github.xxfast.kotlin.native.nuget.rir.RirParameter
import io.github.xxfast.kotlin.native.nuget.rir.RirPrimitiveType
import io.github.xxfast.kotlin.native.nuget.rir.RirStringType
import io.github.xxfast.kotlin.native.nuget.rir.RirTypeRef
import io.github.xxfast.kotlin.native.nuget.rir.RirVoidType
import io.github.xxfast.kotlin.native.nuget.rir.parseReverseIr
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

  file.assemblies.forEach { assembly ->
    val aliases: Map<String, String> = namespaceAliases[assembly.packageId] ?: emptyMap()
    val packageOverride: String? = packageNameOverrides[assembly.packageId]

    assembly.namespaces.forEach { namespace ->
      val kotlinPkg: String = aliases[namespace.name]
        ?: packageOverride
        ?: assembly.packageId.lowercase().replace('-', '_')
      val pkgPath: String = kotlinPkg.replace('.', '/')
      val nsSnake: String = namespace.name.replace('.', '_').lowercase()

      namespace.types.filterIsInstance<RirClass>().forEach { cls ->
        val bridgeable: List<RirMethod> = cls.methods.filter { it.isStatic && isV1Bridgeable(it) }

        if (bridgeable.isEmpty()) return@forEach

        val hasString: Boolean = bridgeable.any { method ->
          method.returnType is RirStringType ||
            method.parameters.any { p -> p.type is RirStringType }
        }
        if (hasString) needsInterop = true

        val typeSnake: String = cls.name.toTypeSnake()
        val exportName: String = if (nsSnake.isEmpty()) "nuget_${typeSnake}_register"
          else "nuget_${nsSnake}_${typeSnake}_register"

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

  return result
}

private fun isV1Bridgeable(method: RirMethod): Boolean {
  if (!isV1Type(method.returnType)) return false
  return method.parameters.all { isV1Type(it.type) }
}

private fun isV1Type(type: RirTypeRef): Boolean = when (type) {
  is RirVoidType -> true
  is RirStringType -> true
  is RirPrimitiveType -> type.name in setOf(
    "bool", "byte", "short", "int", "long", "float", "double", "char",
  )
}

// PascalCase type name → lower_snake_case: insert '_' before each uppercase letter
// after the first, then lowercase. e.g. JsonConvert → json_convert, MathHelper → math_helper
private fun String.toTypeSnake(): String = buildString {
  this@toTypeSnake.forEachIndexed { i, c ->
    if (i > 0 && c.isUpperCase()) append('_')
    append(c.lowercaseChar())
  }
}

// PascalCase method name → camelCase: lowercase the first character only.
// e.g. SerializeObject → serializeObject
private fun String.toMethodCamelCase(): String = replaceFirstChar { it.lowercaseChar() }

private fun kotlinType(type: RirTypeRef): String = when (type) {
  is RirVoidType -> "Unit"
  is RirStringType -> "String"
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

  val imports: MutableList<String> = mutableListOf()
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
    appendLine("package $kotlinPkg")
    appendLine()
    if (imports.isNotEmpty()) {
      imports.forEach { appendLine(it) }
      appendLine()
    }
    appendLine("// Generated: Kotlin-idiomatic stubs for $packageId.${cls.name}")
    appendLine()
    appendLine("object ${cls.name} {")
    appendLine()
    appendLine("  $methods")
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

  val retSuffix: String =
    if (method.returnType is RirVoidType) "" else ": ${kotlinType(method.returnType)}"

  val invokeArgs: String = method.parameters.joinToString(", ") { p ->
    when {
      p.type is RirStringType -> "${p.name}.cstr.ptr"
      p.type is RirPrimitiveType && p.type.name == "char" -> "${p.name}.code.toUShort()"
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

  return when (method.returnType) {
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

    is RirPrimitiveType -> {
      val isChar: Boolean = method.returnType.name == "char"
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
  |package $INTERNAL_PKG
  |
  |import kotlinx.cinterop.COpaquePointer
  |
  |internal expect fun freeManagedString(ptr: COpaquePointer?)
""".trimMargin().trim()

private fun nugetInteropMingw(): String = """
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
  |package $INTERNAL_PKG
  |
  |import kotlinx.cinterop.COpaquePointer
  |import platform.posix.free
  |
  |internal actual fun freeManagedString(ptr: COpaquePointer?) {
  |  ptr?.let { free(it) }
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
