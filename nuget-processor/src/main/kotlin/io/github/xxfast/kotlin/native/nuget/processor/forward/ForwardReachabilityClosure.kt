package io.github.xxfast.kotlin.native.nuget.processor.forward

import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Visibility
import io.github.xxfast.kotlin.native.nuget.processor.cir.FLOW_TYPES
import io.github.xxfast.kotlin.native.nuget.processor.cir.LAMBDA_TYPES
import io.github.xxfast.kotlin.native.nuget.processor.cir.STATE_FLOW_TYPES
import io.github.xxfast.kotlin.native.nuget.processor.cir.SUSPEND_LAMBDA_TYPES
import io.github.xxfast.kotlin.native.nuget.processor.cir.expandAliases

/**
 * Which root-declaration bucket a discovered dependency-module declaration lands in — mirroring
 * the module-local partition `NugetProcessor` already computes (`allClasses`/`valueClasses`/
 * `sealedClasses`/`objects`/`enums`/`interfaces`), so an admitted type flows through the exact
 * same downstream planning/rendering path as an in-module one, with no special-casing beyond
 * bucketing.
 */
internal enum class ForwardReachabilityBucket {
  CLASS, VALUE_CLASS, SEALED_CLASS, OBJECT, ENUM, INTERFACE,
}

internal data class ForwardReachabilityResult(
  /** Admitted cross-module declarations, keyed by qualified name. */
  val admitted: Map<String, KSClassDeclaration>,
  val bucketOf: Map<String, ForwardReachabilityBucket>,
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
  private val isPackageExported: (String) -> Boolean,
  /** ADR-066 admission rule 4: with neither `rootPackage` nor `include` set, the closure must not
   *  cross the module boundary at all (or it would walk straight into `kotlinx-coroutines`). An
   *  empty effective include set means "admit everything" for the module's own files (ADR-063),
   *  but must mean "no cross-module admission" here — the deliberate asymmetry ADR-066 documents. */
  private val crossModuleAdmissionAllowed: Boolean,
) {
  private val visited: MutableSet<String> = mutableSetOf()
  private val admitted: MutableMap<String, KSClassDeclaration> = mutableMapOf()
  private val bucketOf: MutableMap<String, ForwardReachabilityBucket> = mutableMapOf()

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

    return ForwardReachabilityResult(admitted, bucketOf)
  }

  private fun walkFunction(function: KSFunctionDeclaration) {
    function.returnType?.resolve()?.let(::visitType)
    function.parameters.forEach { parameter -> visitType(parameter.type.resolve()) }
  }

  private fun walkProperty(prop: KSPropertyDeclaration) {
    visitType(prop.type.resolve())
  }

  /** Primary-constructor parameters, declared public properties, declared public methods, and a
   *  companion object's own members — every position ADR-066's edge table names. */
  private fun walkClassMembers(cls: KSClassDeclaration) {
    cls.primaryConstructor?.parameters?.forEach { parameter -> visitType(parameter.type.resolve()) }
    cls.declarations.filterIsInstance<KSPropertyDeclaration>()
      .filter { property -> property.getVisibility() == Visibility.PUBLIC }
      .forEach(::walkProperty)
    cls.getAllFunctions()
      .filter { function -> function.getVisibility() == Visibility.PUBLIC }
      .filter { function -> function.parentDeclaration == cls }
      .forEach(::walkFunction)
    cls.declarations.filterIsInstance<KSClassDeclaration>()
      .firstOrNull { declaration -> declaration.isCompanionObject }
      ?.let(::walkClassMembers)
  }

  /** An edge target: unwraps alias expansion, terminates at an intrinsic (walking the type
   *  arguments of an admitted carrier — `List<T>`, `Flow<T>`, ...), otherwise visits the
   *  declaration. Nullability needs no special handling: `KSType.declaration` already resolves
   *  through a `?` to the same class declaration the walk needs to reach. */
  private fun visitType(type: KSType) {
    val expanded: KSType = type.expandAliases()
    val classDeclaration: KSClassDeclaration = expanded.declaration as? KSClassDeclaration ?: return
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

    // `containingFile == null` is the verified cross-module signal (ADR-066 spike): a klib
    // declaration carries no containing file, a module-local one always does.
    if (classDeclaration.containingFile != null) {
      // Reached transitively rather than enumerated as a root — e.g. a module-local type an
      // `exclude(...)` package filtered out of the root scan. Only recurse when it independently
      // passes the same predicate roots already did; otherwise leave it exactly as unadmitted as
      // it already was (the classifier's existing "not in the exported object-handle set" path).
      if (isPackageExported(classDeclaration.packageName.asString())) {
        walkClassMembers(classDeclaration)
      }
      return
    }

    // Cross-module (klib) declaration: ADR-066's admission predicate.
    if (classDeclaration.getVisibility() != Visibility.PUBLIC) return
    if (!crossModuleAdmissionAllowed) return
    if (!isPackageExported(classDeclaration.packageName.asString())) return

    admitted[qualifiedName] = classDeclaration
    bucketOf[qualifiedName] = classDeclaration.reachabilityBucket()
    walkClassMembers(classDeclaration)
    if (classDeclaration.modifiers.contains(Modifier.SEALED)) {
      classDeclaration.getSealedSubclasses().forEach(::visitDeclaration)
    }
  }

  private fun KSClassDeclaration.reachabilityBucket(): ForwardReachabilityBucket = when {
    classKind == ClassKind.ENUM_CLASS -> ForwardReachabilityBucket.ENUM
    classKind == ClassKind.OBJECT -> ForwardReachabilityBucket.OBJECT
    classKind == ClassKind.INTERFACE -> ForwardReachabilityBucket.INTERFACE
    modifiers.contains(Modifier.SEALED) -> ForwardReachabilityBucket.SEALED_CLASS
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
