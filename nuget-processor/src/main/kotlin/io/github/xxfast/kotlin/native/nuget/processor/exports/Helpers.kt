package io.github.xxfast.kotlin.native.nuget.processor.exports

import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.ksp.toTypeName
import io.github.xxfast.kotlin.native.nuget.processor.cir.expandAliases

internal val cNameAnnotation = ClassName("kotlin.native", "CName")
internal val cOpaquePointer = ClassName("kotlinx.cinterop", "COpaquePointer")
internal val cOpaquePointerVar = ClassName("kotlinx.cinterop", "COpaquePointerVar")
internal val stableRef = ClassName("kotlinx.cinterop", "StableRef")

/**
 * Reconstructs the full [TypeName] for a resolved+alias-expanded [KSType], preserving generic
 * type arguments that `ClassName.bestGuess(qualifiedName)` silently drops — e.g. rendering
 * `List<String>` as bare `List`, which KotlinPoet then emits as invalid Kotlin ("One type
 * argument expected for class List<out E>"). This is the forward P0 fix (BUG-005); see
 * `nuget-processor`'s ADR-060 cells 17/18/20.
 *
 * [nullable] defaults to this type's own [KSType.isMarkedNullable]. Callers that previously built
 * a non-null [ClassName] via `bestGuess` — which never carried nullability at all — pass
 * `nullable = false` to keep that part of the prior behavior unchanged, isolating this fix to the
 * dropped type arguments.
 *
 * Guarded against an unresolved generic type parameter anywhere in this type — directly (a bare
 * `T`, e.g. a method-level `fun <T> pick(x: T): T`) or nested (`Result<T>`) — which has no
 * `TypeParameterResolver` context here, so KotlinPoet's `toTypeName()` throws rather than dropping
 * a type argument (ADR-060 cell 23, `suspend inline fun <reified T> ...`, and the equivalent
 * class-method/companion-method shape: none of these declaration kinds are filtered out upstream
 * the way generic top-level functions and generic classes are). Falling back to the pre-existing
 * `bestGuess` behavior for exactly that case keeps the KSP run from crashing outright — those
 * shapes still fail at JVM-compile time exactly as before this fix (an unbound type reference),
 * just without taking the whole processor down with them. Fixing generic/reified declarations
 * properly is a separate, out-of-scope item (MVP.md P1, "Forward unsupported-declaration
 * diagnostics").
 *
 * The fallback deliberately reads only [KSType.declaration]'s simple name, never its qualified
 * name: a `KSTypeParameter`'s `qualifiedName` is a synthetic dotted path through its enclosing
 * declarations (e.g. `Repo.pick.T`) that includes the *method* name as a path segment —
 * `ClassName.bestGuess` expects every segment after the (lowercase) package to start uppercase, so
 * a lowercase method-name segment in the middle makes it throw `IllegalArgumentException`
 * ("couldn't make a guess"). The simple name (`T`) is always a single, bestGuess-able segment.
 */
internal fun KSType.toBridgeTypeName(nullable: Boolean = isMarkedNullable): TypeName =
  try {
    toTypeName().copy(nullable = nullable)
  } catch (_: NoSuchElementException) {
    ClassName.bestGuess(declaration.simpleName.asString()).copy(nullable = nullable)
  }

/**
 * The `kotlinx.cinterop` `*Var` type whose `.value` accessor round-trips a given blittable
 * numeric primitive through `reinterpret<...>().pointed.value = ...` — the mechanism the
 * nullable-primitive method/extension-function return out-parameter uses (ADR-061 §5), mirroring
 * the identical `errorOut.reinterpret<COpaquePointerVar>()` write already shipped for every sync
 * export (ADR-024). `Boolean`/`Char` are deliberately not covered here (ADR-061 defers them: out-
 * param width for `bool`/`char` marshalling is unconfirmed, same fragility ADR-056 found).
 */
internal fun cVarTypeFor(qualifiedType: String): ClassName = ClassName(
  "kotlinx.cinterop",
  when (qualifiedType) {
    "kotlin.Byte" -> "ByteVar"
    "kotlin.UByte" -> "UByteVar"
    "kotlin.Short" -> "ShortVar"
    "kotlin.UShort" -> "UShortVar"
    "kotlin.Int" -> "IntVar"
    "kotlin.UInt" -> "UIntVar"
    "kotlin.Long" -> "LongVar"
    "kotlin.ULong" -> "ULongVar"
    "kotlin.Float" -> "FloatVar"
    "kotlin.Double" -> "DoubleVar"
    else -> "IntVar"
  },
)

internal fun defaultValueFor(qualifiedReturn: String): String = when (qualifiedReturn) {
  "kotlin.Boolean" -> "false"
  "kotlin.String" -> "\"\""
  "kotlin.Float" -> "0.0f"
  "kotlin.Double" -> "0.0"
  "kotlin.UByte" -> "0.toUByte()"
  "kotlin.UShort" -> "0.toUShort()"
  "kotlin.UInt" -> "0u"
  "kotlin.ULong" -> "0uL"
  "kotlin.Unit" -> ""
  else -> if (qualifiedReturn.startsWith("kotlin.")) "0" else "null"
}

internal fun FunSpec.Builder.addParameters(
  func: KSFunctionDeclaration,
): FunSpec.Builder {
  for (param in func.parameters) {
    val resolved = param.type.resolve().expandAliases()
    addParameter(param.name?.asString() ?: "_", resolved.toBridgeTypeName(nullable = false))
  }
  return this
}

/**
 * Same as [addParameters], except an enum param is declared as the ordinal [Int] it crosses the
 * C ABI as (ADR-006). Only for exports whose C# half maps enum params to `int`: top-level
 * functions today. [addParameters] stays as-is for the callers that do not.
 */
internal fun FunSpec.Builder.addEnumAwareParameters(
  func: KSFunctionDeclaration,
): FunSpec.Builder {
  for (param in func.parameters) {
    val resolved: KSType = param.type.resolve().expandAliases()
    val name: String = param.name?.asString() ?: "_"
    val isEnum: Boolean = (resolved.declaration as? KSClassDeclaration)
      ?.classKind == ClassKind.ENUM_CLASS

    if (isEnum) {
      addParameter(name, Int::class)
      continue
    }

    // A nullable String parameter keeps its nullability (ADR-060 cell 9): Kotlin/Native's @CName
    // boundary already marshals a nullable C string to Kotlin `String?` transparently.
    val isNullableString: Boolean =
      resolved.declaration.qualifiedName?.asString() == "kotlin.String" && resolved.isMarkedNullable
    addParameter(name, resolved.toBridgeTypeName(nullable = isNullableString))
  }
  return this
}

internal fun cNameAnnotation(value: String): AnnotationSpec =
  AnnotationSpec.builder(cNameAnnotation)
    .addMember("%S", value)
    .build()
