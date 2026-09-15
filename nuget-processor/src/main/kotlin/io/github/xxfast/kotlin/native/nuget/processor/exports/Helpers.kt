package io.github.xxfast.kotlin.native.nuget.processor.exports

import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Origin
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.ksp.toTypeName
import io.github.xxfast.kotlin.native.nuget.processor.cir.expandAliases
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardExportOwnerTag

internal val cNameAnnotation = ClassName("kotlin.native", "CName")
internal val cOpaquePointer = ClassName("kotlinx.cinterop", "COpaquePointer")
internal val cOpaquePointerVar = ClassName("kotlinx.cinterop", "COpaquePointerVar")
internal val stableRef = ClassName("kotlinx.cinterop", "StableRef")
internal val atomicLong = ClassName("kotlin.concurrent", "AtomicLong")

/**
 * ADR-120: the generated live-handle chokepoint. Every emitted mint goes through
 * `NugetHandles.retain` and every emitted release through `NugetHandles.release`, so the
 * `nuget_live_handles` export can report how many `StableRef` handles the forward bridge holds.
 * ADR-127: the object lives in the `nuget-runtime` klib now, so the name resolves through the
 * import `NugetProcessor` adds unconditionally rather than through a regenerated declaration.
 */
internal val nugetHandles =
  ClassName(NUGET_RUNTIME_PACKAGE, "NugetHandles")

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

/**
 * The one `@CName` minter of the whole forward bridge (ADR-117 amendment, 2026-09-13). [owner] is
 * **required**: it hangs the owning Kotlin declaration on the `AnnotationSpec` itself, so
 * `ForwardExportOwners` can name the exact declaration behind a duplicate entry point on every
 * route, and so a future export site that forgets its owner does not compile. A KotlinPoet tag is
 * builder metadata and never renders, so `CNameExports.kt` is unchanged by it.
 */
internal fun cNameAnnotation(value: String, owner: ForwardExportOwnerTag): AnnotationSpec =
  AnnotationSpec.builder(cNameAnnotation)
    .addMember("%S", value)
    .tag(ForwardExportOwnerTag::class, owner)
    .build()

/**
 * The owner tag for a site that holds its declaration. [role] names *which generated member* of
 * [declaration] the export is (`generated Dispose`, `sealed discriminator`,
 * `generic create variant: string`); a member the user wrote needs none.
 */
internal fun ownedBy(declaration: KSDeclaration, role: String? = null): ForwardExportOwnerTag =
  ForwardExportOwnerTag(declaration = declaration, role = role)

/**
 * ADR-127: the package of the `nuget-runtime` klib, which owns the fixed 67-name `nuget_*` ABI
 * and the Kotlin surface the generated file calls into. Every name here is behind the runtime's
 * `NugetRuntimeApi` opt-in marker, which the generated file's `@file:OptIn` carries.
 */
internal const val NUGET_RUNTIME_PACKAGE: String = "io.github.xxfast.kotlin.native.nuget.runtime"

/**
 * The runtime members the generated file calls by *name* rather than through a KotlinPoet
 * `ClassName` (they are emitted as literal statement text), so KotlinPoet cannot import them on
 * its own. Imported unconditionally: the runtime exports all 67 names unconditionally too, and
 * the gating these used to carry is exactly the defect class ADR-127 removes.
 */
internal val NUGET_RUNTIME_MEMBERS: List<String> = listOf(
  "NugetHandles",
  "NugetError",
  "buildError",
  "NugetCSharpBridge",
  "toDotNetTicks",
  "instantFromDotNetTicks",
  "durationFromDotNetTicks",
)

/**
 * Whether this member belongs to the compiler rather than to the person who wrote [owner], so no
 * forward route may declare it, plan it, or walk the types it is spelled with (issue #235).
 *
 * Four rules, in the order they are cheapest to answer:
 *
 * 1. **The language's own members.** `Any`'s three, the constructor, and a data class's `copy` and
 *    `componentN` operators. `copy` is projected separately as C#'s `Copy`, and `componentN` has no
 *    C# meaning at all (issue #230).
 * 2. **[Origin.SYNTHETIC].** What KSP says about a member it synthesized itself, in-module.
 * 3. **A hidden deprecation.** `@Deprecated(level = DeprecationLevel.HIDDEN)` means the compiler
 *    removed the member from the source language: nothing can call it by name, so nothing should
 *    bridge it. General and plugin-agnostic. The default level is `WARNING`, so an absent `level`
 *    argument is deliberately NOT hidden.
 * 4. **A signature spelled in a compiler plugin's runtime**, on an owner the plugin marked. A
 *    member of a `@kotlinx.serialization.Serializable` type (or of that type's companion) whose
 *    return type or any parameter type lives under `kotlinx.serialization.` was written by the
 *    serialization plugin, not by a person.
 *
 * Rule 4 is honestly narrow, and deliberately not a name check: `Companion.serializer()` carries no
 * annotation, no [Origin] of its own and no location once it crosses a klib boundary (verified: KSP
 * reports it `KOTLIN_LIB` at `NonExistLocation`, byte-identical to a hand-written member), so its
 * signature plus the owner's `@Serializable` marker is the only handle KSP offers. There is no
 * plugin-agnostic signal to generalize it with. A second plugin needs a second clause here.
 *
 * One named predicate for every route that selects members off [owner], because the inline copies
 * of rule 1 did not agree: the arm flow selector had no copy, so a `Flow`-typed data class
 * parameter's `componentN` reached both halves as a worse-named duplicate of its property.
 */
internal fun KSFunctionDeclaration.isCompilerOwnedMember(owner: KSClassDeclaration): Boolean {
  val name: String = simpleName.asString()
  if (name in LANGUAGE_OWNED_MEMBERS) return true
  if (owner.modifiers.contains(Modifier.DATA) && (name == "copy" || name.startsWith("component"))) {
    return true
  }
  if (origin == Origin.SYNTHETIC) return true
  if (isHiddenByDeprecation()) return true
  if (!owner.isCompilerPluginMarkedOwner()) return false
  val signatureTypes: List<KSType?> =
    listOf(returnType?.resolve()) + parameters.map { parameter -> parameter.type.resolve() }
  return signatureTypes.any { type -> type.isPluginRuntimeType() }
}

/**
 * The property half of [isCompilerOwnedMember]. Same four rules, minus the ones that cannot apply:
 * a property has no `<init>`, and a data class's `componentN` is a function. Rule 4 is what keeps
 * `KSerializer.descriptor` off the property routes when a serializer-ish owner is walked.
 */
internal fun KSPropertyDeclaration.isCompilerOwnedMember(owner: KSClassDeclaration): Boolean {
  if (origin == Origin.SYNTHETIC) return true
  if (isHiddenByDeprecation()) return true
  if (!owner.isCompilerPluginMarkedOwner()) return false
  return type.resolve().isPluginRuntimeType()
}

/**
 * The declaration half of [isCompilerOwnedMember]: whether a nested declaration was written by a
 * compiler plugin rather than by a person.
 *
 * Keeps issue #223's `$` rule as the backstop (a `$` cannot appear in a Kotlin *simple* name that
 * anyone wrote, and C# cannot spell one either, which is the CS1056 in that issue), and adds the
 * same hidden-deprecation and plugin-runtime rules the member predicate uses, so
 * kotlinx.serialization's `Carton.$serializer` would still be refused if it were ever renamed:
 * verified, it carries `@Deprecated(level = DeprecationLevel.HIDDEN)` AND extends
 * `kotlinx.serialization.internal.GeneratedSerializer`.
 */
internal fun KSClassDeclaration.isCompilerOwnedDeclaration(): Boolean {
  if ('$' in simpleName.asString()) return true
  if (origin == Origin.SYNTHETIC) return true
  if (isHiddenByDeprecation()) return true
  val owner: KSClassDeclaration = parentDeclaration as? KSClassDeclaration ?: return false
  if (!owner.isCompilerPluginMarkedOwner()) return false
  return superTypes.any { superType -> superType.resolve().isPluginRuntimeType() }
}

/**
 * `@Deprecated(level = DeprecationLevel.HIDDEN)`: the compiler kept the member on the ABI but took
 * it out of the source language. Read off `arguments`, so an omitted `level` (default `WARNING`)
 * answers false. The argument's value comes back differently depending on where the annotation was
 * read from (a `KSType`, the enum entry's own `KSClassDeclaration`, or a rendered string), so all
 * three shapes reduce to the entry's simple name.
 */
private fun KSAnnotated.isHiddenByDeprecation(): Boolean = annotations
  .filter { annotation ->
    val name: String? = annotation.annotationType.resolve().declaration.qualifiedName?.asString()
    name == DEPRECATED_ANNOTATION
  }
  .any { annotation ->
    val level: Any? = annotation.arguments
      .firstOrNull { argument -> argument.name?.asString() == "level" }
      ?.value
    val name: String? = when (level) {
      null -> null
      is KSType -> level.declaration.simpleName.asString()
      is KSClassDeclaration -> level.simpleName.asString()
      else -> level.toString().substringAfterLast('.')
    }
    name == "HIDDEN"
  }

/**
 * Whether this declaration, or the class it is the companion of, carries a compiler plugin's
 * marker.
 */
private fun KSClassDeclaration.isCompilerPluginMarkedOwner(): Boolean {
  if (carriesPluginMarker()) return true
  if (!isCompanionObject) return false
  return (parentDeclaration as? KSClassDeclaration)?.carriesPluginMarker() == true
}

private fun KSClassDeclaration.carriesPluginMarker(): Boolean = annotations.any { annotation ->
  val name: String? = annotation.annotationType.resolve().declaration.qualifiedName?.asString()
  name in PLUGIN_MARKER_ANNOTATIONS
}

private fun KSType?.isPluginRuntimeType(): Boolean {
  val qualifiedName: String = this?.declaration?.qualifiedName?.asString() ?: return false
  return PLUGIN_RUNTIME_PACKAGES.any { prefix -> qualifiedName.startsWith(prefix) }
}

private val LANGUAGE_OWNED_MEMBERS: Set<String> =
  setOf("equals", "hashCode", "toString", "<init>")

private const val DEPRECATED_ANNOTATION: String = "kotlin.Deprecated"

/** The annotations a compiler plugin puts on a type to claim it. One entry per supported plugin. */
private val PLUGIN_MARKER_ANNOTATIONS: Set<String> = setOf("kotlinx.serialization.Serializable")

/** The runtime packages those plugins spell their generated signatures with. */
private val PLUGIN_RUNTIME_PACKAGES: List<String> = listOf("kotlinx.serialization.")
