package io.github.xxfast.kotlin.native.nuget.processor.forward

import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Visibility
import io.github.xxfast.kotlin.native.nuget.processor.cir.FLOW_TYPES
import io.github.xxfast.kotlin.native.nuget.processor.cir.LAMBDA_TYPES
import io.github.xxfast.kotlin.native.nuget.processor.cir.STATE_FLOW_TYPES
import io.github.xxfast.kotlin.native.nuget.processor.cir.SUSPEND_LAMBDA_TYPES
import io.github.xxfast.kotlin.native.nuget.processor.NESTED_DECLARATION_KINDS
import io.github.xxfast.kotlin.native.nuget.processor.cir.expandAliases
import io.github.xxfast.kotlin.native.nuget.processor.exports.isCompilerOwnedDeclaration
import io.github.xxfast.kotlin.native.nuget.processor.exports.isCompilerOwnedMember
import io.github.xxfast.kotlin.native.nuget.processor.nestedDeclarationDeferral

/**
 * Which root-declaration bucket a discovered dependency-module declaration lands in — mirroring
 * the module-local partition `NugetProcessor` already computes (`allClasses`/`valueClasses`/
 * `sealedClasses`/`objects`/`enums`/`interfaces`), so an admitted type flows through the exact
 * same downstream planning/rendering path as an in-module one, with no special-casing beyond
 * bucketing.
 */
internal enum class ForwardReachabilityBucket {
  CLASS, VALUE_CLASS, SEALED_CLASS, OBJECT, ENUM, INTERFACE,

  /**
   * Issue #54: a subclass of a sealed base, which no root list picks up. It is admitted (so the
   * classifier still spells it as an exported handle) but reaches C# only through its base's
   * `getSealedSubclasses()` walk in the sealed route. Bucketing it as [CLASS] declared it a second
   * time as a plain class, the klib-origin half of the same duplicate a module-local sibling
   * subclass produced.
   */
  SEALED_SUBCLASS,
}

/**
 * Why the closure refused to admit a discovered dependency-module declaration. Recorded because a
 * bare refusal loses the one thing the diagnostic needs: `include(...)` is the fix for exactly one
 * of these, is actively wrong for [EXCLUDED_BY_CONFIG] (`PackageScope.covers` tests `exclude`
 * first, so no include can override one) and for [EXPECT_IN_DEPENDENCY] (no scope reaches another
 * module's actualization), and is incomplete for [CROSS_MODULE_ADMISSION_DISABLED] (an include
 * alone replaces the "everything" default and drops the module's own files).
 */
internal enum class ForwardAdmissionRefusal {
  EXCLUDED_BY_CONFIG,
  NOT_INCLUDED,
  CROSS_MODULE_ADMISSION_DISABLED,
  EXPECT_IN_DEPENDENCY,

  /** A declaration nested inside another dependency declaration: not admitted by the closure
   *  itself, because ADR-133's owner walk is the sole declarer of a nested type and the owner is
   *  the admission record. Since the ADR-066 amendment the closure first climbs to the owner, so
   *  this refusal means "declared, if at all, by that owner's walk", and it is recorded only when
   *  the owner carries no refusal of its own; when the owner IS refused (an `exclude(...)` naming
   *  it, or a package outside the scope), the owner's refusal is propagated onto the nested name
   *  instead, so the classifier can state the remedy that actually applies. */
  NESTED_DECLARATION,
}

internal data class ForwardReachabilityResult(
  /** Admitted cross-module declarations, keyed by qualified name. */
  val admitted: Map<String, KSClassDeclaration>,
  val bucketOf: Map<String, ForwardReachabilityBucket>,
  /** Refused cross-module declarations, keyed by qualified name: the reason the classifier's
   *  "not in the exported handle set" view cannot reconstruct on its own. */
  val refused: Map<String, ForwardAdmissionRefusal> = emptyMap(),
)

/**
 * ADR-066: the forward export set as a reachability closure from module-local roots, admitted by
 * the ADR-063 package predicate. `Resolver.getDeclarationsFromPackage` returns empty for a klib
 * dependency (verified against a real Kotlin/Native klib), so a dependency-module declaration can
 * only enter the export set by being *discovered* — reachable by walking return types, parameter
 * types, property types, type arguments of an admitted carrier (`Flow<T>`, `List<T>`, ...), sealed
 * subclasses, and primary-constructor parameter types — never by enumerating its package directly.
 *
 * Termination is a visited-set keyed on qualified name, so a cyclic cross-module type graph
 * (`A.b: B`, `B.a: A`) terminates on the second visit.
 */
internal class ForwardReachabilityClosure(
  /** ADR-063's scope predicate over the declaration itself (issue #53: `exclude` may name a
   *  qualified declaration, not only a package), shared with the root scan. */
  private val isExported: (KSDeclaration) -> Boolean,
  /** The `exclude` half of that same predicate, so a refusal can name the author's own
   *  `exclude(...)` instead of telling them to add an `include(...)` that cannot override it. */
  private val isExcluded: (KSDeclaration) -> Boolean = { false },
  /** ADR-066 admission rule 4: with neither `rootPackage` nor `include` set, the closure must not
   *  cross the module boundary at all (or it would walk straight into `kotlinx-coroutines`). An
   *  empty effective include set means "admit everything" for the module's own files (ADR-063),
   *  but must mean "no cross-module admission" here — the deliberate asymmetry ADR-066 documents. */
  private val crossModuleAdmissionAllowed: Boolean,
  /** ADR-074 Decision 2: `actual typealias` targets, keyed by the `expect` class's qualified name.
   *  A type reference to such a name resolves to the `expect` class declaration from every source
   *  set (spike finding 8), never to the alias, so this closure must redirect by name exactly as
   *  the classifier does. */
  private val actualTypeAliasTargets: Map<String, KSClassDeclaration> = emptyMap(),
) {
  private val visited: MutableSet<String> = mutableSetOf()
  private val admitted: MutableMap<String, KSClassDeclaration> = mutableMapOf()
  private val bucketOf: MutableMap<String, ForwardReachabilityBucket> = mutableMapOf()
  private val refused: MutableMap<String, ForwardAdmissionRefusal> = mutableMapOf()

  fun walk(
    classes: List<KSClassDeclaration>,
    valueClasses: List<KSClassDeclaration>,
    sealedClasses: List<KSClassDeclaration>,
    objects: List<KSClassDeclaration>,
    enums: List<KSClassDeclaration>,
    interfaces: List<KSClassDeclaration>,
    functions: List<KSFunctionDeclaration>,
    extensionFunctions: List<KSFunctionDeclaration>,
    properties: List<KSPropertyDeclaration>,
    extensionProperties: List<KSPropertyDeclaration>,
  ): ForwardReachabilityResult {
    // Roots are already admitted by file provenance (ADR-063); mark them visited up front so the
    // walk never re-discovers or re-buckets one of the module's own declarations.
    (classes + valueClasses + sealedClasses + objects + enums + interfaces).forEach { cls ->
      cls.qualifiedName?.asString()?.let(visited::add)
    }

    classes.forEach(::walkClassMembers)
    valueClasses.forEach(::walkClassMembers)
    sealedClasses.forEach { sealed ->
      walkClassMembers(sealed)
      sealed.getSealedSubclasses().forEach(::visitDeclaration)
    }
    objects.forEach(::walkClassMembers)
    interfaces.forEach(::walkClassMembers)
    // Enum entries are intrinsic to the enum (ADR-066 §2), not an edge to walk.

    functions.forEach(::walkFunction)
    extensionFunctions.forEach(::walkFunction)
    properties.forEach(::walkProperty)
    extensionProperties.forEach(::walkProperty)

    return ForwardReachabilityResult(admitted, bucketOf, refused)
  }

  private fun walkFunction(function: KSFunctionDeclaration) {
    function.returnType?.resolve()?.let(::visitType)
    function.parameters.forEach { parameter -> visitType(parameter.type.resolve()) }
  }

  private fun walkProperty(prop: KSPropertyDeclaration) {
    visitType(prop.type.resolve())
  }

  /** Primary-constructor parameters, declared public properties, declared public methods, a
   *  companion object's own members, and — since the ADR-066 amendment — the members of every
   *  nested declaration ADR-133 declares under this one: every position ADR-066's edge table
   *  names. */
  private fun walkClassMembers(cls: KSClassDeclaration) {
    cls.primaryConstructor?.parameters?.forEach { parameter -> visitType(parameter.type.resolve()) }
    // Issue #235: a compiler-owned member is filtered BEFORE its types are visited, so the closure
    // never admits a type on behalf of a declaration no route will ever export. Without this the
    // surface stays reachable even once every route refuses it, which is how `$serializer` came
    // back through the return position after #223 guarded the nested route.
    cls.declarations.filterIsInstance<KSPropertyDeclaration>()
      .filter { property -> property.getVisibility() == Visibility.PUBLIC }
      .filter { property -> !property.isCompilerOwnedMember(cls) }
      .forEach(::walkProperty)
    cls.getAllFunctions()
      .filter { function -> function.getVisibility() == Visibility.PUBLIC }
      .filter { function -> function.parentDeclaration == cls }
      .filter { function -> !function.isCompilerOwnedMember(cls) }
      .forEach(::walkFunction)
    cls.declarations.filterIsInstance<KSClassDeclaration>()
      .firstOrNull { declaration -> declaration.isCompanionObject }
      ?.let(::walkClassMembers)
    // ADR-066 amendment, edge B: a nested declaration ADR-133 declares under this one is part of
    // the exported surface, so the types ITS members are spelled with have to be admitted too.
    // Without this, `Broadcast.Schedule.timetable(): Timetable` leaves `Timetable` neither
    // admitted nor refused, and the member skips advising an `include(...)` for a package that is
    // already in scope. One level only: this function recurses, so depth is covered once each.
    //
    // Gated on `nestedDeclarationDeferral() == null` so the closure never admits a dependency type
    // on behalf of a nested declaration ADR-133 will not declare (that would put dead entries in
    // the INFO_EXPORTED_FROM_DEPENDENCY manifest and dead types in the generated C#).
    //
    // Kind before visibility, verified (NugetProcessor.kt:150-153): `getVisibility()` on an enum
    // entry read from a dependency klib/jar throws `Internal KSP Error`, and this walk now reaches
    // the `declarations` of an admitted cross-module `enum class`.
    cls.declarations.filterIsInstance<KSClassDeclaration>()
      .filter { declaration -> !declaration.isCompilerOwnedDeclaration() }
      .filter { declaration -> declaration.classKind in NESTED_DECLARATION_KINDS }
      .filter { declaration -> declaration.getVisibility() == Visibility.PUBLIC }
      .filter { declaration -> !declaration.isCompanionObject }
      .filter { declaration -> declaration.nestedDeclarationDeferral() == null }
      .forEach(::walkClassMembers)
  }

  /** An edge target: unwraps alias expansion, terminates at an intrinsic (walking the type
   *  arguments of an admitted carrier — `List<T>`, `Flow<T>`, ...), otherwise visits the
   *  declaration. Nullability needs no special handling: `KSType.declaration` already resolves
   *  through a `?` to the same class declaration the walk needs to reach. */
  private fun visitType(type: KSType) {
    val expanded: KSType = type.expandAliases()
    val rawDeclaration: KSClassDeclaration = expanded.declaration as? KSClassDeclaration ?: return
    val rawQualifiedName: String = rawDeclaration.qualifiedName?.asString() ?: return

    // ADR-074 Decision 2: a reference to an `actual typealias`-actualized `expect class` resolves
    // to the `expect` declaration itself (spike finding 8), so `expandAliases()` above structurally
    // cannot see through it. Redirect by name to the alias's erased target before this closure
    // applies any of its own rules to it.
    val classDeclaration: KSClassDeclaration =
      if (rawDeclaration.isExpect) actualTypeAliasTargets[rawQualifiedName] ?: rawDeclaration
      else rawDeclaration
    val qualifiedName: String = classDeclaration.qualifiedName?.asString() ?: return

    if (qualifiedName in INTRINSIC_TERMINALS) {
      if (qualifiedName in CARRIER_TYPES) {
        expanded.arguments.forEach { argument -> argument.type?.resolve()?.let(::visitType) }
      }
      return
    }

    visitDeclaration(classDeclaration)
  }

  private fun visitDeclaration(classDeclaration: KSClassDeclaration) {
    val qualifiedName: String = classDeclaration.qualifiedName?.asString() ?: return
    if (qualifiedName in visited) return
    visited += qualifiedName

    // ADR-074 Decision 1 (defensive): a cross-module (klib) declaration reporting `isExpect` must
    // not be admitted. Not spiked for a cross-module expect/actual pair; costs one condition and
    // removes the question. A module-local `expect` is a different thing entirely (its `actual` is
    // the export root, filtered out at the root scan), so only the cross-module one is recorded.
    if (classDeclaration.isExpect) {
      if (classDeclaration.containingFile == null) {
        refused[qualifiedName] = ForwardAdmissionRefusal.EXPECT_IN_DEPENDENCY
      }
      return
    }

    // `containingFile == null` is the verified cross-module signal (ADR-066 spike): a klib
    // declaration carries no containing file, a module-local one always does.
    if (classDeclaration.containingFile != null) {
      // Reached transitively rather than enumerated as a root — e.g. a module-local type an
      // `exclude(...)` package filtered out of the root scan. Only recurse when it independently
      // passes the same predicate roots already did; otherwise leave it exactly as unadmitted as
      // it already was (the classifier's existing "not in the exported object-handle set" path).
      if (isExported(classDeclaration)) {
        walkClassMembers(classDeclaration)
      }
      return
    }

    // Cross-module (klib) declaration: ADR-066's admission predicate.
    if (classDeclaration.getVisibility() != Visibility.PUBLIC) return
    // Exclude is tested ahead of the admission rules for the same reason `PackageScope.covers`
    // tests it first: an explicitly excluded type is excluded whatever else the scope says, and
    // that is the refusal the author can act on.
    if (isExcluded(classDeclaration)) {
      refused[qualifiedName] = ForwardAdmissionRefusal.EXCLUDED_BY_CONFIG
      return
    }
    if (!crossModuleAdmissionAllowed) {
      refused[qualifiedName] = ForwardAdmissionRefusal.CROSS_MODULE_ADMISSION_DISABLED
      return
    }
    if (!isExported(classDeclaration)) {
      refused[qualifiedName] = ForwardAdmissionRefusal.NOT_INCLUDED
      return
    }

    // A *nested* dependency declaration must never be admitted, whatever its bucket: every
    // translator declares an admitted type at the namespace root under its simple name
    // (`translateClass`, `translateEnum`), while every reference to it is spelled `Outer.Inner`
    // (`nestedCsName`), so admitting one emits a `public class Inner` / `public enum Inner` that no
    // reference resolves against (CS0426). Declining admission hands it to ADR-133's owner walk
    // instead, the sole declarer of a nested type, which declares this one as `Owner.Nested`
    // unless it defers it; only a deferred one falls through to the classifier's membership gate,
    // which skips each member named (`UNDECLARED_CLASS`, `UNDECLARED_ENUM`,
    // `UNDECLARED_INTERFACE`).
    //
    // The carve-out is a sealed subclass: ADR-009 declares it nested under its base, which is
    // exactly how `nestedCsName` spells it, so the `getSealedSubclasses()` walk below must keep
    // admitting one. A companion object is likewise declared, as its owner's statics (ADR-013).
    //
    // ADR-066 amendment, edge A: before any of that, climb to the enclosing declaration. A nested
    // type is only ever spelled `Owner.Nested`, so a member returning one is a reference to the
    // owner as much as to the nested type, and until this edge existed an owner NOTHING returned
    // was never admitted at all — `Newsroom.page(): Almanac.Page` left `Almanac` undeclared and
    // `page()` skipped. The visited-set above makes the recursion safe (the owner's own
    // `getSealedSubclasses()`/nested walk comes straight back here and returns), and it reaches
    // the outermost owner at any depth. A sealed arm nested in its base reaches the base by this
    // same edge and is then admitted by the SEALED_SUBCLASS carve-out below, so an arm-only
    // reference no longer depends on some other member returning the base.
    //
    // Unconditional on purpose, including when ADR-133/134 defers the owner (`enum class`,
    // generic, `inner class`): the owner's admission is what puts the nested type on
    // `NugetProcessor`'s `nestedCandidates` walk, so it is the sole carrier of that type's named
    // `SKIPPED_NESTED_DECLARATION`, and the admitted owner is a real usable C# type either way.
    val owner: KSClassDeclaration? = classDeclaration.parentDeclaration as? KSClassDeclaration
    owner?.let(::visitDeclaration)

    val isUndeclaredNested: Boolean = classDeclaration.parentDeclaration != null &&
        !classDeclaration.isCompanionObject &&
        !classDeclaration.isSealedSubclass()
    if (isUndeclaredNested) {
      // The owner's own refusal, when it has one, is the refusal the author can act on: an
      // `exclude("pkg.Owner")` naming the owner by qualified name refuses only the owner, and
      // `NESTED_DECLARATION`'s "undeclarable" reading would hide that with a remedy (move it to
      // the top level) that repairs nothing. Falls back to NESTED_DECLARATION when the owner was
      // admitted, or is module-local, or carries no record.
      val ownerQualifiedName: String? = owner?.qualifiedName?.asString()
      refused[qualifiedName] = ownerQualifiedName?.let(refused::get)
        ?: ForwardAdmissionRefusal.NESTED_DECLARATION
      return
    }

    admitted[qualifiedName] = classDeclaration
    bucketOf[qualifiedName] = classDeclaration.reachabilityBucket()
    walkClassMembers(classDeclaration)
    if (classDeclaration.modifiers.contains(Modifier.SEALED)) {
      classDeclaration.getSealedSubclasses().forEach(::visitDeclaration)
    }
  }

  private fun KSClassDeclaration.reachabilityBucket(): ForwardReachabilityBucket = when {
    classKind == ClassKind.ENUM_CLASS -> ForwardReachabilityBucket.ENUM
    // Issue #110: the sealed-subclass test is part of the OBJECT condition, not a later branch,
    // because a cross-module `data object` arm of a sealed base is admitted here (the nested
    // refusal carves out `isSealedSubclass`) and would otherwise land in the OBJECT bucket and be
    // declared a second time as an empty namespace-level `public static class`. SEALED_SUBCLASS is
    // fed to no root list on purpose: the base's `getSealedSubclasses()` walk declares the arm.
    // Only the object kind is qualified, so an *intermediate* sealed class (sealed and itself a
    // sealed subclass) keeps its SEALED_CLASS bucket below.
    classKind == ClassKind.OBJECT && !isSealedSubclass() -> ForwardReachabilityBucket.OBJECT
    // ADR-112: eligibility is tested BEFORE the interface kind, so a cross-module eligible sealed
    // interface reaches the ADR-009 renderer instead of being declared as a bare `I<Name>`. An
    // ineligible one falls through to INTERFACE, exactly as every sealed interface used to.
    isEligibleSealedType() -> ForwardReachabilityBucket.SEALED_CLASS
    classKind == ClassKind.INTERFACE -> ForwardReachabilityBucket.INTERFACE
    isSealedSubclass() -> ForwardReachabilityBucket.SEALED_SUBCLASS
    isValueClass() -> ForwardReachabilityBucket.VALUE_CLASS
    else -> ForwardReachabilityBucket.CLASS
  }

  private companion object {
    /** Primitives, `Char`, `String`, `Unit` — the classifier's known scalars, which terminate the
     *  walk without becoming an object-handle edge. */
    val SCALAR_TERMINALS: Set<String> = setOf(
      "kotlin.Boolean", "kotlin.Byte", "kotlin.UByte", "kotlin.Short", "kotlin.UShort",
      "kotlin.Int", "kotlin.UInt", "kotlin.Long", "kotlin.ULong", "kotlin.Float", "kotlin.Double",
      "kotlin.Unit", "kotlin.Char", "kotlin.String",
      // ADR-076: a known stdlib scalar, same as String/Char above -- the closure must stop
      // walking it as a class edge.
      "kotlin.time.Instant",
      // ADR-103: likewise, and doubly so -- Duration is a value class, so without this entry the
      // closure would walk it as a VALUE_CLASS edge.
      "kotlin.time.Duration",
      // ADR-106: the third known stdlib scalar; the closure must stop at it rather than walking
      // Uuid's own members as class edges.
      "kotlin.uuid.Uuid",
      // ADR-151: the fourth known stdlib scalar. Without it the closure walks ByteArray's own
      // members as class edges.
      "kotlin.ByteArray",
    )
    val COLLECTION_TYPES: Set<String> = setOf(
      "kotlin.collections.List", "kotlin.collections.MutableList",
      "kotlin.collections.Map", "kotlin.collections.MutableMap",
      "kotlin.collections.Set", "kotlin.collections.MutableSet",
    )

    /** Carriers whose *type arguments* are still edges even though the carrier itself terminates
     *  the walk (ADR-066 edge table: "type arguments of an admitted carrier"). */
    val CARRIER_TYPES: Set<String> = COLLECTION_TYPES + FLOW_TYPES + STATE_FLOW_TYPES

    val INTRINSIC_TERMINALS: Set<String> =
      SCALAR_TERMINALS + CARRIER_TYPES + LAMBDA_TYPES + SUSPEND_LAMBDA_TYPES
  }
}
