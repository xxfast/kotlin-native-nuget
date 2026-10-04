package io.github.xxfast.kotlin.native.nuget.processor.exports

import io.github.xxfast.kotlin.native.nuget.processor.forward.kotlinIdentifier
import io.github.xxfast.kotlin.native.nuget.processor.ForwardSymbolTable
import io.github.xxfast.kotlin.native.nuget.processor.abiSlotParameterName
import io.github.xxfast.kotlin.native.nuget.processor.forward.forwardBoundSpellings
import io.github.xxfast.kotlin.native.nuget.processor.forward.forwardKotlinDeclaredSpelling
import io.github.xxfast.kotlin.native.nuget.processor.forward.forwardStarBoundSpellings
import io.github.xxfast.kotlin.native.nuget.processor.forward.isTrampolined
import io.github.xxfast.kotlin.native.nuget.processor.forward.trampolined
import io.github.xxfast.kotlin.native.nuget.processor.forward.castsToGenericBound
import io.github.xxfast.kotlin.native.nuget.processor.forward.forwardUncheckedCastSuppression
import io.github.xxfast.kotlin.native.nuget.processor.forward.forwardBoundedRead
import io.github.xxfast.kotlin.native.nuget.processor.forward.hasNullableBound
import io.github.xxfast.kotlin.native.nuget.processor.forward.importIfDefaultPackage
import io.github.xxfast.kotlin.native.nuget.processor.forward.kotlinPackageReference
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeParameter
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import io.github.xxfast.kotlin.native.nuget.processor.cir.expandAliases
import io.github.xxfast.kotlin.native.nuget.processor.cir.isKotlinBuiltinPackage
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
  // ADR-198: refused on both halves, and named by `warnUnroutedGenericFunctions`.
  if (isReifiedWithUnspellableBound()) return -1
  val typeParamName: String = typeParameters.firstOrNull()?.name?.asString() ?: "T"
  // The two returns both halves spell: `T` itself, or a generated generic class over it
  // (`Box<T>`). Anything else (`String`, `Unit`, a builtin `List<T>`) was read as a generic-class
  // handle by the Kotlin half and as a `T` by the C# half, which the ABI contract stopped as an
  // internal generator failure; it is refused here, on both halves, and named as its return.
  val returned: KSDeclaration? = returnType?.resolve()?.expandAliases()?.declaration
  val routedReturn: Boolean = when (returned) {
    is KSTypeParameter -> returned.name.asString() == typeParamName
    is KSClassDeclaration -> returned.typeParameters.isNotEmpty() &&
        !returned.packageName.asString().isKotlinBuiltinPackage()
    else -> false
  }
  if (!routedReturn) return -1
  return parameters.indexOfFirst { param ->
    param.type.resolve().expandAliases().declaration.simpleName.asString() == typeParamName
  }
}

/**
 * ADR-198: a `reified` type parameter whose bound has no closed spelling (`inline fun <reified T :
 * Enum<T>>`). The trampoline's `T` is not reified and neither is `Nothing`, so nothing the export
 * can name is a legal reified argument; the language has no way to call it here.
 */
internal fun KSFunctionDeclaration.isReifiedWithUnspellableBound(): Boolean =
  typeParameters
    .firstOrNull()
    ?.let { parameter -> parameter.isReified && parameter.isTrampolined() }
    ?: false

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
    kotlinPackageReference(func.packageName.asString()) +
      func.simpleName.asString().kotlinIdentifier()
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
  // The body's reference to it: KotlinPoet backticks the declaration of a keyword name itself.
  val paramRef: String = paramName.kotlinIdentifier()

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
          }, nugetHandles, funcName, paramRef, cOpaquePointerVar, nugetHandles)
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
          }, funcName, paramRef, cOpaquePointerVar, nugetHandles)
          .build()
      )
    }
  }

  val cname = "${symbolStem}_object"

  // The class route's bound spellings and reader: a generic bound keeps its arguments
  // (`kotlin.Comparable` names no type), the box is cast, checked, to every bound (ADR-015
  // amendment: `Weigh<uint>` fails at the read, not in the body), and a multi-bound `T` is typed
  // as the intersection no single type argument names.
  val typeParameter: KSTypeParameter? = func.typeParameters.firstOrNull()
  // ADR-198: a bound with no closed spelling is read inside the trampoline, checked against each
  // bound's star-projected class and cast to the function's own `T`, which then infers the call.
  val trampolined: Boolean = typeParameter?.isTrampolined() == true
  val bounds: List<String> = when {
    typeParameter == null -> emptyList()
    trampolined -> typeParameter.forwardStarBoundSpellings()
    else -> typeParameter.forwardBoundSpellings()
  }
  val trampoline: List<Pair<String, List<String>>> = listOfNotNull(
    typeParameter?.takeIf { trampolined }?.let { parameter ->
      parameter.name.asString() to
          parameter.bounds.map { bound -> bound.resolve().forwardKotlinDeclaredSpelling() }.toList()
    },
  )

  // ADR-147 amendment, applied to this route: an unconstrained `T` (upper bound `Any?`) may be
  // null, so the object variant takes the null pointer for a null argument (ADR-083) and returns
  // it for a null result, instead of dereferencing it.
  val nullableBound: Boolean = typeParameter?.hasNullableBound() ?: true
  val argument: String = forwardBoundedRead(
    paramRef, bounds, nullableBound, typeVariable = typeParamName.takeIf { trampolined },
  )
  // The checked read of a generic bound (`as kotlin.Comparable<Any?>`) warns; the trampoline
  // suppresses its own casts on its local function.
  val suppressUnchecked: Boolean = !trampolined && bounds.castsToGenericBound()

  if (returnsGenericClass) {
    addFunction(
      FunSpec.builder("export_$cname")
        .addAnnotation(cNameAnnotation(cname, ownedBy(func, "generic variant: object")))
        .apply { if (suppressUnchecked) addAnnotation(forwardUncheckedCastSuppression) }
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
        .trampolined(trampoline)
    )
  } else if (returnDecl == typeParamName) {
    val callArguments: Array<Any> =
      if (nullableBound) arrayOf(funcName, nugetHandles) else arrayOf(nugetHandles, funcName)
    addFunction(
      FunSpec.builder("export_$cname")
        .addAnnotation(cNameAnnotation(cname, ownedBy(func, "generic variant: object")))
        .apply { if (suppressUnchecked) addAnnotation(forwardUncheckedCastSuppression) }
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
        .trampolined(trampoline)
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
