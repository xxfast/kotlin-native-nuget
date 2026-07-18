package io.github.xxfast.kotlin.native.nuget.processor

import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.TypeName
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirDllImport
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirClass
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirFile
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirObject
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirStaticClass
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirValueClass
import io.github.xxfast.kotlin.native.nuget.processor.cir.ordinaryNativeImports
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardAbiWireType
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardCallablePlan
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardCallablePlanCatalog
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardNativeCall
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardPropertyPlan

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

internal fun List<ForwardAbiSignature>.canonicalText(): String =
  sortedWith(compareBy({ signature -> signature.exportName }, { signature -> signature.toString() }))
    .joinToString("\n")

/**
 * A generation-time assertion for C# native declarations represented by [CirDllImport], including
 * ordinary class and value-class imports normalized through their shared computed factories.
 * Specialized helper protocols remain on explicit legacy routes until their migration phase.
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
        is CirClass -> declaration.ordinaryNativeImports() +
            declaration.companionMembers.filterIsInstance<CirDllImport>()
        is CirValueClass -> declaration.ordinaryNativeImports()
        else -> emptyList()
      }
    }
    .mapNotNull { import -> import.toSignature() }

  fun kotlin(file: FileSpec, expectedNames: Set<String>): List<ForwardAbiSignature> = file.members
    .filterIsInstance<FunSpec>()
    .mapNotNull { function -> function.toSignature() }
    .filter { signature -> signature.exportName in expectedNames }

  /**
   * The first shadow-planning slice is intentionally checked against the existing independent
   * KotlinPoet and CIR projections. Keeping all three projections independent makes a stale
   * legacy emitter fail during KSP rather than silently drifting while renderers migrate later.
   */
  fun assertMatchesPlan(
    catalog: ForwardCallablePlanCatalog,
    csharp: List<ForwardAbiSignature>,
    kotlin: List<ForwardAbiSignature>,
  ) {
    val planned: List<ForwardAbiSignature> =
      catalog.plans.flatMap { plan -> plan.toSignatures() } +
          catalog.propertyPlans.flatMap { plan -> plan.toSignatures() }
    val names: Set<String> = planned.map { signature -> signature.exportName }.toSet()
    assertProjectionMatches("plan", planned, "C#", csharp.filter { it.exportName in names })
    assertProjectionMatches("plan", planned, "Kotlin", kotlin.filter { it.exportName in names })
  }

  private fun assertProjectionMatches(
    expectedLabel: String,
    expected: List<ForwardAbiSignature>,
    actualLabel: String,
    actual: List<ForwardAbiSignature>,
  ) {
    val expectedByName: Map<String, List<ForwardAbiSignature>> = expected.groupBy { it.exportName }
    val actualByName: Map<String, List<ForwardAbiSignature>> = actual.groupBy { it.exportName }
    val names: List<String> = (expectedByName.keys + actualByName.keys).sorted()
    names.forEach { name ->
      val expectedSignatures: List<ForwardAbiSignature> = expectedByName[name].orEmpty()
      val actualSignatures: List<ForwardAbiSignature> = actualByName[name].orEmpty()
      require(expectedSignatures.size <= 1) {
        "Forward ABI duplicate $expectedLabel signature for $name: $expectedSignatures"
      }
      require(actualSignatures.size <= 1) {
        "Forward ABI duplicate $actualLabel signature for $name: $actualSignatures"
      }
      require(expectedSignatures.isNotEmpty()) {
        "Forward ABI unexpected $actualLabel projection for $name: ${actualSignatures.single()}"
      }
      require(actualSignatures.isNotEmpty()) {
        "Forward ABI missing $actualLabel projection for $name; expected ${expectedSignatures.single()}"
      }
      require(expectedSignatures.single() == actualSignatures.single()) {
        "Forward ABI plan mismatch for $name against $actualLabel; expected " +
            "${expectedSignatures.single()}, actual ${actualSignatures.single()}"
      }
    }
  }

  private fun ForwardCallablePlan.toSignatures(): List<ForwardAbiSignature> = nativeExports.toSignatures()

  private fun ForwardPropertyPlan.toSignatures(): List<ForwardAbiSignature> = calls().toSignatures()

  private fun List<ForwardNativeCall>.toSignatures(): List<ForwardAbiSignature> = map { call ->
    ForwardAbiSignature(
      exportName = call.exportName,
      result = call.result.toAbiType(),
      parameters = call.parameters.map { parameter ->
        ForwardAbiParameter(parameter.wireType.toAbiType(), parameter.direction.toAbiDirection())
      },
    )
  }

  private fun ForwardAbiWireType.toAbiType(): ForwardAbiType = when (this) {
    ForwardAbiWireType.VOID -> ForwardAbiType.VOID
    ForwardAbiWireType.BOOLEAN -> ForwardAbiType.BOOL
    ForwardAbiWireType.INT8, ForwardAbiWireType.UINT8 -> ForwardAbiType.BYTE
    ForwardAbiWireType.INT16, ForwardAbiWireType.UINT16, ForwardAbiWireType.CHAR16 -> ForwardAbiType.SHORT
    ForwardAbiWireType.INT32, ForwardAbiWireType.UINT32 -> ForwardAbiType.INT
    ForwardAbiWireType.INT64, ForwardAbiWireType.UINT64 -> ForwardAbiType.LONG
    ForwardAbiWireType.FLOAT32 -> ForwardAbiType.FLOAT
    ForwardAbiWireType.FLOAT64 -> ForwardAbiType.DOUBLE
    ForwardAbiWireType.STRING -> ForwardAbiType.STRING
    ForwardAbiWireType.POINTER -> ForwardAbiType.POINTER
    ForwardAbiWireType.UNKNOWN -> error("Forward plan contains an unknown ABI wire type")
  }

  private fun io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardAbiDirection.toAbiDirection():
      ForwardAbiDirection = when (this) {
    io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardAbiDirection.IN -> ForwardAbiDirection.IN
    io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardAbiDirection.OUT -> ForwardAbiDirection.OUT
    io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardAbiDirection.IN_OUT ->
      error("Forward plan IN_OUT ABI direction has no legacy ForwardAbiContract projection")
  }

  private fun CirDllImport.toSignature(): ForwardAbiSignature? {
    val name: String = entryPoint ?: return null
    val parameters: MutableList<ForwardAbiParameter> = parameters.map { parameter ->
      // ADR-061's nullable-primitive out-parameter (`out int value`, etc.) is, at the C ABI
      // level, exactly the same shape as `out IntPtr error` below: a pointer to a memory slot the
      // callee writes through. Recognize any `out `-prefixed native type uniformly as (POINTER,
      // OUT) rather than trying to resolve its pointee width — mirroring the errorOut special
      // case's own philosophy — so it matches the Kotlin side's `COpaquePointer?` (also POINTER).
      if (parameter.nativeType.startsWith("out ")) {
        ForwardAbiParameter(ForwardAbiType.POINTER, ForwardAbiDirection.OUT)
      } else {
        ForwardAbiParameter(csharpType(parameter.nativeType))
      }
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
        // "valueOut" is ADR-061's nullable-primitive return out-parameter, the Kotlin-side
        // counterpart of the C# `out <T> value` recognized above.
        val direction: ForwardAbiDirection = if (
          parameter.name == "errorOut" || parameter.name == "valueOut"
        ) {
          ForwardAbiDirection.OUT
        } else {
          ForwardAbiDirection.IN
        }
        ForwardAbiParameter(kotlinParameterType(parameter.type), direction)
      },
    )
  }

  // Distinct from [kotlinType]/[kotlinReturnType] deliberately: `Char` is only a plannable
  // *parameter* shape so far (MIGRATION.md Phase 7). Its legacy *result* route still declares an
  // "IntPtr" C# return for a Kotlin `Char` return (ADR-060 cell 13, Phase 8, out of scope here),
  // so widening the shared [kotlinType] to recognize `kotlin.Char` would make this checker correctly
  // flag that pre-existing legacy mismatch too — a real defect, but not this slice's to fix.
  private fun kotlinParameterType(type: TypeName): ForwardAbiType {
    val name: String = type.toString().removeSuffix("?")
    return if (name == "kotlin.Char") ForwardAbiType.SHORT else kotlinType(type)
  }

  private fun List<AnnotationSpec>.cNameValue(): String? = firstOrNull { annotation ->
    annotation.typeName.toString() == "kotlin.native.CName"
  }?.members?.singleOrNull()?.toString()?.removeSurrounding("\"")

  private fun csharpType(type: String): ForwardAbiType = when (type.removeSuffix("?")) {
    "void" -> ForwardAbiType.VOID
    "bool" -> ForwardAbiType.BOOL
    "byte", "sbyte" -> ForwardAbiType.BYTE
    "short", "ushort", "char" -> ForwardAbiType.SHORT
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
