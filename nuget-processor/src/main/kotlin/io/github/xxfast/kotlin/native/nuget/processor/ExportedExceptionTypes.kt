package io.github.xxfast.kotlin.native.nuget.processor

import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.isPublic
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import io.github.xxfast.kotlin.native.nuget.processor.cir.KOTLIN_EXCEPTION_TYPES
import io.github.xxfast.kotlin.native.nuget.processor.cir.KotlinExceptionMatch

/**
 * ADR-241: one exported `Throwable` class of this module. [kotlinType] is both the `is` target of
 * the generated `nugetMappedType` arm and the key the C# `MapExported` switch dispatches on;
 * [csharpBase] is the shared-contract exception type a ROOT of the hierarchy derives from (the
 * matched mapped `Kotlin*` row type, else `KotlinException`), constraint 6: chosen statically.
 */
internal data class ExportedExceptionType(
  val declaration: KSClassDeclaration,
  val kotlinType: String,
  val depth: Int,
  val csharpBase: String,
)

private const val CONTRACT_NAMESPACE: String = "global::Kotlin.Native.Interop"

/**
 * The exported `Throwable` classes among [classes] and the [sealedClasses] hierarchies (bases and
 * their public sealed arms), deepest first, so the first matching `is` is the nearest exported
 * ancestor (single class inheritance, ADR-241 constraint 6).
 */
internal fun exportedExceptionTypes(
  classes: List<KSClassDeclaration>,
  sealedClasses: List<KSClassDeclaration>,
): List<ExportedExceptionType> {
  val candidates: List<KSClassDeclaration> =
    classes + sealedClasses + sealedClasses.flatMap { sealed -> sealed.getSealedSubclasses() }
  return candidates
    .filter { declaration -> declaration.classKind == ClassKind.CLASS && declaration.isPublic() }
    .filter { declaration -> declaration.typeParameters.isEmpty() }
    .mapNotNull { declaration ->
      val qualifiedName: String = declaration.qualifiedName?.asString() ?: return@mapNotNull null
      val base: String = declaration.exceptionCsharpBase() ?: return@mapNotNull null
      val depth: Int = declaration.getAllSuperTypes()
        .count { supertype -> (supertype.declaration as? KSClassDeclaration)?.classKind == ClassKind.CLASS }
      ExportedExceptionType(declaration, qualifiedName, depth, base)
    }
    .distinctBy { type -> type.kotlinType }
    .sortedByDescending { type -> type.depth }
}

/**
 * ADR-241: the shared-contract exception type this class's C# wrapper derives from when it is the
 * ROOT of its exported hierarchy (the matched mapped `Kotlin*` row type, in `KOTLIN_EXCEPTION_TYPES`
 * order, else `KotlinException`), or null when the class is not a `Throwable`.
 */
internal fun KSClassDeclaration.exceptionCsharpBase(): String? {
  val supertypes: Set<String> = getAllSuperTypes()
    .mapNotNull { supertype -> supertype.declaration.qualifiedName?.asString() }
    .toSet()
  if ("kotlin.Throwable" !in supertypes) return null
  val row: String = KOTLIN_EXCEPTION_TYPES
    .firstOrNull { row -> row.match == KotlinExceptionMatch.IS && row.kotlinType in supertypes }
    ?.csharpType
    ?: "KotlinException"
  return "$CONTRACT_NAMESPACE.$row"
}

/**
 * ADR-241: the C# members an exported exception class does not render: the `Throwable` members
 * `Exception` already carries (`Message`, `InnerException`), and the names that would hide an
 * `Exception` member (a named skip in v1; renaming is deferred).
 */
internal val EXCEPTION_MEMBER_COLLISIONS: Set<String> = setOf(
  "Message", "Cause", "GetLocalizedMessage", "Data", "Source", "HelpLink", "HResult",
  "StackTrace", "TargetSite", "InnerException", "GetBaseException", "GetType",
)
