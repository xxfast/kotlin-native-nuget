package io.github.xxfast.kotlin.native.nuget.processor

import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.TypeName
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirDllImport
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirFile
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirObject
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirStaticClass

internal enum class ForwardAbiType {
  VOID,
  BOOL,
  BYTE,
  SHORT,
  INT,
  LONG,
  FLOAT,
  DOUBLE,
  POINTER,
  STRING,
}

internal enum class ForwardAbiDirection { IN, OUT }

internal data class ForwardAbiParameter(
  val type: ForwardAbiType,
  val direction: ForwardAbiDirection = ForwardAbiDirection.IN,
)

internal data class ForwardAbiSignature(
  val exportName: String,
  val result: ForwardAbiType,
  val parameters: List<ForwardAbiParameter>,
) {
  override fun toString(): String {
    val params: String = parameters.joinToString(", ") { parameter ->
      "${parameter.direction.name.lowercase()} ${parameter.type.name.lowercase()}"
    }
    return "$exportName(${params}) -> ${result.name.lowercase()}"
  }
}

/**
 * A generation-time assertion for the subset of C# native declarations represented directly by
 * [CirDllImport]. Other C# helper declarations are deliberately not collected: they are rendered
 * from helper CIR nodes and do not have a corresponding generated @CName wrapper.
 */
internal object ForwardAbiContract {
  fun assertMatches(csharp: List<ForwardAbiSignature>, kotlin: List<ForwardAbiSignature>) {
    val csharpByName: Map<String, List<ForwardAbiSignature>> = csharp.groupBy { it.exportName }
    val kotlinByName: Map<String, List<ForwardAbiSignature>> = kotlin.groupBy { it.exportName }
    val names: List<String> = (csharpByName.keys + kotlinByName.keys).sorted()

    names.forEach { name ->
      val expected: List<ForwardAbiSignature> = csharpByName[name].orEmpty()
      val actual: List<ForwardAbiSignature> = kotlinByName[name].orEmpty()
      require(expected.size <= 1) { "Forward ABI duplicate C# import for $name: $expected" }
      require(actual.size <= 1) { "Forward ABI duplicate Kotlin export for $name: $actual" }
      require(expected.isNotEmpty()) {
        "Forward ABI missing C# import for $name; actual ${actual.single()}"
      }
      require(actual.isNotEmpty()) {
        "Forward ABI missing Kotlin export for $name; expected ${expected.single()}"
      }
      require(expected.single() == actual.single()) {
        "Forward ABI mismatch for $name; expected ${expected.single()}, actual ${actual.single()}"
      }
    }
  }

  fun csharp(file: CirFile): List<ForwardAbiSignature> = file.namespaces
    .flatMap { namespace -> namespace.declarations }
    .flatMap { declaration ->
      when (declaration) {
        is CirStaticClass -> declaration.members.filterIsInstance<CirDllImport>()
        is CirObject -> declaration.methods.filterIsInstance<CirDllImport>()
        else -> emptyList()
      }
    }
    .mapNotNull { import -> import.toSignature() }

  fun kotlin(file: FileSpec, expectedNames: Set<String>): List<ForwardAbiSignature> = file.members
    .filterIsInstance<FunSpec>()
    .mapNotNull { function -> function.toSignature() }
    .filter { signature -> signature.exportName in expectedNames }

  private fun CirDllImport.toSignature(): ForwardAbiSignature? {
    val name: String = entryPoint ?: return null
    val parameters: MutableList<ForwardAbiParameter> = parameters.map { parameter ->
      ForwardAbiParameter(csharpType(parameter.nativeType))
    }.toMutableList()
    if (hasSyncErrorOut) parameters.add(ForwardAbiParameter(ForwardAbiType.POINTER, ForwardAbiDirection.OUT))
    return ForwardAbiSignature(name, csharpReturnType(returnType), parameters)
  }

  private fun FunSpec.toSignature(): ForwardAbiSignature? {
    val name: String = annotations.cNameValue() ?: return null
    return ForwardAbiSignature(
      exportName = name,
      result = kotlinReturnType(returnType),
      parameters = parameters.map { parameter ->
        val direction: ForwardAbiDirection = if (parameter.name == "errorOut") {
          ForwardAbiDirection.OUT
        } else {
          ForwardAbiDirection.IN
        }
        ForwardAbiParameter(kotlinType(parameter.type), direction)
      },
    )
  }

  private fun List<AnnotationSpec>.cNameValue(): String? = firstOrNull { annotation ->
    annotation.typeName.toString() == "kotlin.native.CName"
  }?.members?.singleOrNull()?.toString()?.removeSurrounding("\"")

  private fun csharpType(type: String): ForwardAbiType = when (type.removeSuffix("?")) {
    "void" -> ForwardAbiType.VOID
    "bool" -> ForwardAbiType.BOOL
    "byte", "sbyte" -> ForwardAbiType.BYTE
    "short", "ushort" -> ForwardAbiType.SHORT
    "int", "uint" -> ForwardAbiType.INT
    "long", "ulong" -> ForwardAbiType.LONG
    "float" -> ForwardAbiType.FLOAT
    "double" -> ForwardAbiType.DOUBLE
    "string" -> ForwardAbiType.STRING
    "IntPtr", "nint" -> ForwardAbiType.POINTER
    else -> ForwardAbiType.POINTER
  }

  // Kotlin/Native returns a Kotlin String through a native pointer. P/Invoke's `string` spelling
  // asks the runtime to marshal that pointer for the managed caller, so it is a string at an input
  // position but a pointer at the ABI result position.
  private fun csharpReturnType(type: String): ForwardAbiType = if (
    type.removeSuffix("?") == "string"
  ) {
    ForwardAbiType.POINTER
  } else {
    csharpType(type)
  }

  private fun kotlinType(type: TypeName): ForwardAbiType {
    val name: String = type.toString().removeSuffix("?")
    return when (name) {
      "kotlin.Unit" -> ForwardAbiType.VOID
      "kotlin.Boolean" -> ForwardAbiType.BOOL
      "kotlin.Byte", "kotlin.UByte" -> ForwardAbiType.BYTE
      "kotlin.Short", "kotlin.UShort" -> ForwardAbiType.SHORT
      "kotlin.Int", "kotlin.UInt" -> ForwardAbiType.INT
      "kotlin.Long", "kotlin.ULong" -> ForwardAbiType.LONG
      "kotlin.Float" -> ForwardAbiType.FLOAT
      "kotlin.Double" -> ForwardAbiType.DOUBLE
      "kotlin.String" -> ForwardAbiType.STRING
      else -> ForwardAbiType.POINTER
    }
  }

  private fun kotlinReturnType(type: TypeName): ForwardAbiType = if (
    type.toString().removeSuffix("?") == "kotlin.String"
  ) {
    ForwardAbiType.POINTER
  } else {
    kotlinType(type)
  }
}
