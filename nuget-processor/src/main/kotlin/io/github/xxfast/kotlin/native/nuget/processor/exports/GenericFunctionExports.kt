package io.github.xxfast.kotlin.native.nuget.processor.exports

import io.github.xxfast.kotlin.native.nuget.processor.ForwardSymbolTable
import io.github.xxfast.kotlin.native.nuget.processor.abiSlotParameterName
import io.github.xxfast.kotlin.native.nuget.processor.forward.forwardBoundSpellings
import io.github.xxfast.kotlin.native.nuget.processor.forward.forwardBoundedRead
import io.github.xxfast.kotlin.native.nuget.processor.forward.hasNullableBound
import io.github.xxfast.kotlin.native.nuget.processor.forward.importIfDefaultPackage
import io.github.xxfast.kotlin.native.nuget.processor.forward.kotlinPackageReference
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import io.github.xxfast.kotlin.native.nuget.processor.cir.expandAliases
import kotlin.reflect.KClass

/**
 * ADR-064 amendment (2026-09-13): this route's own gate, hoisted so both halves and the planner's
 * diagnostic read one function. The route dispatches on a `T`-typed *direct parameter* (it emits
 * one export per primitive it can substitute), so `fun <T> f(): List<T>` — no such parameter —
 * is refused, and the refusal is total for the declaration: nothing is emitted on either half.
 * `NugetProcessor` names the refused ones (`SKIPPED_UNSUPPORTED_RETURN`) rather than leaving them
 * silent, which is the amendment.
 */
internal fun KSFunctionDeclaration.legacyGenericRouteParameterIndex(): Int {
  val typeParamName: String = typeParameters.firstOrNull()?.name?.asString() ?: "T"
  return parameters.indexOfFirst { param ->
    param.type.resolve().expandAliases().declaration.simpleName.asString() == typeParamName
  }
}

/**
 * True when the first type parameter has a bound other than `Any`, which suppresses the primitive
 * width variants on both halves. Read from the Kotlin bounds, never from the C# `where` clause: a
 * dropped builtin bound (`T : Number` -> `where T : notnull`) still suppresses them, and the C#
 * half deciding from its clause would import `_int` entry points the Kotlin half never exported.
 */
internal fun KSFunctionDeclaration.legacyGenericHasNonTrivialBound(): Boolean =
  typeParameters.firstOrNull()?.bounds?.toList()?.any { bound ->
    bound.resolve().declaration.qualifiedName?.asString() != "kotlin.Any"
  } ?: false

/** True when the legacy generic-function route emits for [this]; see the index above. */
internal fun KSFunctionDeclaration.hasLegacyGenericFunctionRoute(): Boolean =
  legacyGenericRouteParameterIndex() != -1

/**
 * Generates @CName bridge exports for generic functions using type-variant pattern.
 * For identity<T>(value: T): T, generates identity_string, identity_int, etc.
 */
internal fun FileSpec.Builder.addGenericFunctionExports(
  func: KSFunctionDeclaration,
  /** ADR-163: the one symbol table; `translateGenericFunction` pins the same strings. */
  symbols: ForwardSymbolTable,
) {
  // ADR-163: the qualified symbol stem, and the fully qualified Kotlin call beside it. The stem
  // also carries the `toCName` escape, which this route used to skip while its C# twin applied it.
  val symbolStem: String = symbols.topLevel(func)
  val funcName: String =
    kotlinPackageReference(func.packageName.asString()) + func.simpleName.asString()
  val returnType: KSType? = func.returnType?.resolve()?.expandAliases()
  val returnDecl: String = returnType?.declaration?.simpleName?.asString() ?: "Unit"

  val typeParamName: String = func.typeParameters.firstOrNull()?.name?.asString() ?: "T"

  val paramIndex: Int = func.legacyGenericRouteParameterIndex()

  if (paramIndex == -1) return

  // ADR-163: the default package has no qualifier to spell, so the bare call needs this import.
  importIfDefaultPackage(func)

  // A parameter spelled like an ABI slot (`errorOut`) shifts on both halves, see
  // abiSlotParameterName.
  val paramName: String =
    (func.parameters[paramIndex].name?.asString() ?: "value").abiSlotParameterName()

  val hasNonTrivialBound: Boolean = func.legacyGenericHasNonTrivialBound()

  val returnsGenericClass: Boolean = returnDecl != typeParamName && returnDecl != "Unit"

  val primitiveTypes = listOf(
    "string" to "String",
    "byte" to "Byte",
    "ubyte" to "UByte",
    "short" to "Short",
    "ushort" to "UShort",
    "int" to "Int",
    "uint" to "UInt",
    "long" to "Long",
    "ulong" to "ULong",
    "float" to "Float",
    "double" to "Double",
    "bool" to "Boolean",
  )

  if (!hasNonTrivialBound) primitiveTypes.forEach { (suffix, kotlinType) ->
    val cname = "${symbolStem}_$suffix"

    if (returnsGenericClass) {
      addFunction(
        FunSpec.builder("export_$cname")
          .addAnnotation(cNameAnnotation(cname, ownedBy(func, "generic variant: $suffix")))
          .addParameter(paramName, kotlinTypeClass(kotlinType))
          .addParameter("errorOut", cOpaquePointer.copy(nullable = true))
          .returns(cOpaquePointer.copy(nullable = true))
          .addCode(buildString {
            appendLine("return try {")
            appendLine("  %T.retain(%L(%L))")
            appendLine("} catch (e: Throwable) {")
            appendLine("  if (errorOut != null) {")
            appendLine("    errorOut.reinterpret<%T>().pointed.value = %T.retain(")
            appendLine("      buildError(e, ::nugetMappedType)")
            appendLine("    )")
            appendLine("  }")
            appendLine("  null")
            append("}")
          }, nugetHandles, funcName, paramName, cOpaquePointerVar, nugetHandles)
          .build()
      )
    } else if (returnDecl == typeParamName) {
      val qualifiedKotlinType: String = "kotlin.$kotlinType"
      addFunction(
        FunSpec.builder("export_$cname")
          .addAnnotation(cNameAnnotation(cname, ownedBy(func, "generic variant: $suffix")))
          .addParameter(paramName, kotlinTypeClass(kotlinType))
          .addParameter("errorOut", cOpaquePointer.copy(nullable = true))
          .returns(kotlinTypeClass(kotlinType))
          .addCode(buildString {
            appendLine("return try {")
            appendLine("  %L(%L)")
            appendLine("} catch (e: Throwable) {")
            appendLine("  if (errorOut != null) {")
            appendLine("    errorOut.reinterpret<%T>().pointed.value = %T.retain(")
            appendLine("      buildError(e, ::nugetMappedType)")
            appendLine("    )")
            appendLine("  }")
            appendLine("  ${defaultValueFor(qualifiedKotlinType)}")
            append("}")
          }, funcName, paramName, cOpaquePointerVar, nugetHandles)
          .build()
      )
    }
  }

  val cname = "${symbolStem}_object"

  // The class route's bound spellings and reader: a generic bound keeps its arguments
  // (`kotlin.Comparable` names no type), the box is cast, checked, to every bound (ADR-015
  // amendment: `Weigh<uint>` fails at the read, not in the body), and a multi-bound `T` is typed
  // as the intersection no single type argument names.
  val bounds: List<String> = func.typeParameters.firstOrNull()?.forwardBoundSpellings().orEmpty()

  // ADR-147 amendment, applied to this route: an unconstrained `T` (upper bound `Any?`) may be
  // null, so the object variant takes the null pointer for a null argument (ADR-083) and returns
  // it for a null result, instead of dereferencing it.
  val nullableBound: Boolean = func.typeParameters.firstOrNull()?.hasNullableBound() ?: true
  val argument: String = forwardBoundedRead(paramName, bounds, nullableBound)

  if (returnsGenericClass) {
    addFunction(
      FunSpec.builder("export_$cname")
        .addAnnotation(cNameAnnotation(cname, ownedBy(func, "generic variant: object")))
        .addParameter(paramName, cOpaquePointer.copy(nullable = nullableBound))
        .addParameter("errorOut", cOpaquePointer.copy(nullable = true))
        .returns(cOpaquePointer.copy(nullable = true))
        .addCode(buildString {
          appendLine("return try {")
          appendLine("  %T.retain(%L($argument))")
          appendLine("} catch (e: Throwable) {")
          appendLine("  if (errorOut != null) {")
          appendLine("    errorOut.reinterpret<%T>().pointed.value = %T.retain(")
          appendLine("      buildError(e, ::nugetMappedType)")
          appendLine("    )")
          appendLine("  }")
          appendLine("  null")
          append("}")
        }, nugetHandles, funcName, cOpaquePointerVar, nugetHandles)
        .build()
    )
  } else if (returnDecl == typeParamName) {
    val callArguments: Array<Any> =
      if (nullableBound) arrayOf(funcName, nugetHandles) else arrayOf(nugetHandles, funcName)
    addFunction(
      FunSpec.builder("export_$cname")
        .addAnnotation(cNameAnnotation(cname, ownedBy(func, "generic variant: object")))
        .addParameter(paramName, cOpaquePointer.copy(nullable = nullableBound))
        .addParameter("errorOut", cOpaquePointer.copy(nullable = true))
        .returns(cOpaquePointer.copy(nullable = true))
        .addCode(buildString {
          appendLine("return try {")
          if (nullableBound) {
            appendLine("  %L($argument)?.let { result -> %T.retain(result) }")
          } else {
            appendLine("  %T.retain(%L($argument))")
          }
          appendLine("} catch (e: Throwable) {")
          appendLine("  if (errorOut != null) {")
          appendLine("    errorOut.reinterpret<%T>().pointed.value = %T.retain(")
          appendLine("      buildError(e, ::nugetMappedType)")
          appendLine("    )")
          appendLine("  }")
          appendLine("  null")
          append("}")
        }, *callArguments, cOpaquePointerVar, nugetHandles)
        .build()
    )
  }
}

private fun kotlinTypeClass(kotlinType: String): KClass<*> = when (kotlinType) {
  "String" -> String::class
  "Byte" -> Byte::class
  "UByte" -> UByte::class
  "Short" -> Short::class
  "UShort" -> UShort::class
  "Int" -> Int::class
  "UInt" -> UInt::class
  "Long" -> Long::class
  "ULong" -> ULong::class
  "Float" -> Float::class
  "Double" -> Double::class
  "Boolean" -> Boolean::class
  else -> String::class
}
