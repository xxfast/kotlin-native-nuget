package io.github.xxfast.kotlin.native.nuget.processor

import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.isPublic
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnostic
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import io.github.xxfast.kotlin.native.nuget.processor.forward.csharpMemberName
import io.github.xxfast.kotlin.native.nuget.processor.forward.forwardDiagnosticOwner
import io.github.xxfast.kotlin.native.nuget.processor.cir.KOTLIN_EXCEPTION_TYPES
import io.github.xxfast.kotlin.native.nuget.processor.cir.KotlinExceptionMatch
import io.github.xxfast.kotlin.native.nuget.processor.forward.abstractBackingName
import io.github.xxfast.kotlin.native.nuget.processor.forward.sealedExceptionBackingName

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
 * their public sealed arms, `object` arms included: the sealed route renders each as a handle class
 * that wraps the thrown singleton), deepest first, so the first matching `is` is the nearest
 * exported ancestor (single class inheritance, ADR-241 constraint 6).
 *
 * The ONE list both halves key off: the Kotlin `nugetMappedType` arms and the C# `MapExported`
 * arms. Every entry must therefore be constructible in C#, which [exceptionBackingName] makes true
 * of an abstract class and a sealed root.
 */
internal fun exportedExceptionTypes(
  classes: List<KSClassDeclaration>,
  sealedClasses: List<KSClassDeclaration>,
): List<ExportedExceptionType> {
  val candidates: List<KSClassDeclaration> =
    classes + sealedClasses + sealedClasses.flatMap { sealed -> sealed.getSealedSubclasses() }
  return candidates
    .filter { declaration -> declaration.classKind in setOf(ClassKind.CLASS, ClassKind.OBJECT) }
    .filter { declaration -> declaration.isPublic() }
    .filter { declaration -> declaration.typeParameters.isEmpty() }
    .mapNotNull { declaration ->
      val qualifiedName: String = declaration.qualifiedName?.asString() ?: return@mapNotNull null
      val base: String = declaration.exceptionCsharpBase() ?: return@mapNotNull null
      val depth: Int = declaration.getAllSuperTypes()
        .count { type -> (type.declaration as? KSClassDeclaration)?.classKind == ClassKind.CLASS }
      ExportedExceptionType(declaration, qualifiedName, depth, base)
    }
    .distinctBy { type -> type.kotlinType }
    .sortedByDescending { type -> type.depth }
}

/**
 * ADR-241: the nested C# wrapper a thrown instance of this class is constructed as, when the class
 * itself cannot be instantiated: an abstract class's ADR-040 backing, or a sealed root's backing.
 * Null for a concrete class or `object` arm, which C# constructs directly.
 */
internal fun KSClassDeclaration.exceptionBackingName(): String? =
  abstractBackingName() ?: sealedExceptionBackingName()

/**
 * ADR-241: the shared-contract exception type this class's C# wrapper derives from when it is the
 * ROOT of its exported hierarchy (the matched mapped `Kotlin*` row type, in
 * `KOTLIN_EXCEPTION_TYPES` order, else `KotlinException`), or null when the class is not a `Throwable`.
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
 * ADR-241: the `Throwable` members `System.Exception` already carries, by C# name: `message` is
 * `Exception.Message`, `cause` is `InnerException`, and the JVM's `getLocalizedMessage()` is the
 * message again. Not rendered and not reported: nothing is missing.
 */
internal val EXCEPTION_CARRIED_MEMBERS: Set<String> =
  setOf("Message", "Cause", "GetLocalizedMessage")

/**
 * ADR-241: the C# names of `System.Exception`'s public instance members a bound member would hide
 * (CS0108, an error under a consumer's warnings-as-errors build). A named skip in v1
 * (`SKIPPED_EXCEPTION_MEMBER_COLLISION`); renaming is deferred. `ToString`, `Equals` and
 * `GetHashCode` are not here: the data-class route owns them (`toString` keeps
 * `KotlinException.ToString`, ADR-241 Q5).
 */
internal val EXCEPTION_MEMBER_COLLISIONS: Set<String> = setOf(
  "Data", "Source", "HelpLink", "HResult", "StackTrace", "TargetSite", "InnerException",
  "GetBaseException", "GetObjectData", "GetType",
)

/** Whether an exception class leaves the member of this C# name unrendered. */
internal fun String.isExceptionMemberName(): Boolean =
  this in EXCEPTION_CARRIED_MEMBERS || this in EXCEPTION_MEMBER_COLLISIONS

/**
 * ADR-241: one `SKIPPED_EXCEPTION_MEMBER_COLLISION` per member an exported exception class
 * DECLARES whose C# name would hide an `Exception` member. The membership rule
 * (`isForwardMemberOf` / `isForwardPlannableMemberOf`) is what drops it, on every route at once;
 * this names the drop, once, on the class that declares it. An inherited `Throwable` member is a
 * stdlib member, not the author's, and is not reported.
 */
internal fun exceptionMemberCollisions(
  types: List<ExportedExceptionType>,
): List<ForwardDiagnostic> =
  types.flatMap { type ->
    val cls: KSClassDeclaration = type.declaration
    val members: List<KSDeclaration> = cls.declarations
      .filter { member -> member is KSFunctionDeclaration || member is KSPropertyDeclaration }
      .filter { member -> member.isPublic() }
      .filter { member -> member.csharpMemberName() in EXCEPTION_MEMBER_COLLISIONS }
      .toList()
    members.map { member ->
      val name: String = member.simpleName.asString()
      val csharp: String = member.csharpMemberName()
      ForwardDiagnostic(
        kind = ForwardDiagnosticKind.SKIPPED_EXCEPTION_MEMBER_COLLISION,
        symbol = member,
        declaration = "${type.kotlinType}.$name",
        reason = "`${cls.simpleName.asString()}` binds as a C# exception type, and its member " +
          "`$csharp` would hide `System.Exception.$csharp`",
        hint = "rename it, or give it a `@CSharpName`, to bind it",
        owner = cls.forwardDiagnosticOwner(),
        member = name,
      )
    }
  }
