package io.github.xxfast.kotlin.native.nuget.processor.forward

import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.getConstructors
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Visibility
import io.github.xxfast.kotlin.native.nuget.processor.exports.findInterfaceBridgePairs
import io.github.xxfast.kotlin.native.nuget.processor.exports.findStoredCallbackPairs
import io.github.xxfast.kotlin.native.nuget.processor.toCName

/**
 * Why the planner declined to build an ordinary synchronous plan for a callable.
 *
 * [droppedFromCSharp] is the load-bearing distinction: a reason is a *drop* only when no named
 * legacy route re-emits the callable, so the declaration genuinely disappears from the generated
 * C# API (never as an `IntPtr` / `"0"` fallback, just absent). Those reasons are surfaced as a KSP
 * warning. Reasons that defer the callable to a named legacy export builder / CIR translator
 * (`ForwardAbiLegacyRoutes`, ADR-062's legacy-route table) are *not* drops: the callable is still
 * emitted, only not through the plan, so warning on them would be a false alarm.
 */
internal enum class ForwardPlanSkipReason(val droppedFromCSharp: Boolean) {
  // Deferred to a named legacy route (still emitted, just not via the plan): silent.
  ABSTRACT(droppedFromCSharp = false),
  CALLBACK_PROTOCOL(droppedFromCSharp = false),
  FLOW_PROTOCOL(droppedFromCSharp = false),
  GENERIC(droppedFromCSharp = false),
  SEALED_PROTOCOL(droppedFromCSharp = false),
  SUSPEND(droppedFromCSharp = false),
  SUSPEND_CALLBACK_PROTOCOL(droppedFromCSharp = false),
  TYPE_PARAMETER(droppedFromCSharp = false),

  // No legacy home: the callable is dropped from the C# API and must warn. CHAR/STRING/ENUM/HANDLE/
  // OBJECT are supported ordinary types with no legacy route, so a skip carrying them can only mean
  // a genuine drop; they are defensively classified as drops even though the planner does not
  // currently reach them.
  CHAR(droppedFromCSharp = true),
  COLLECTION(droppedFromCSharp = true),
  ENUM(droppedFromCSharp = true),
  HANDLE(droppedFromCSharp = true),
  NULLABLE(droppedFromCSharp = true),
  OBJECT(droppedFromCSharp = true),
  STRING(droppedFromCSharp = true),
  UNSUPPORTED(droppedFromCSharp = true),
  VALUE_CLASS(droppedFromCSharp = true),

  // ADR-064: genuine drops with their own named diagnostic kind, not the generic "type
  // combination is not supported" bucket the reasons above still render through.
  /** Cell 23 / BUG-010: a generic + suspend + inline + reified extension returning `Result<T>` —
   *  the combination has no working legacy route, even though suspend and generic each do
   *  individually. */
  UNSUPPORTED_COMBINATION(droppedFromCSharp = true),

  /** ROADMAP line 77: a value-class member inherited via interface delegation (e.g.
   *  `CharSequence by value`), not declared by the value class itself. */
  INHERITED_MEMBER(droppedFromCSharp = true),

  /** ADR-066: a reachable, structurally bridgeable declaration in a dependency module whose
   *  package the reachability closure did not admit (out of scope, not unsupported). Carries its
   *  own diagnostic kind (`SKIPPED_UNEXPORTED_DEPENDENCY_TYPE`) naming the `include(...)` fix,
   *  rather than the generic "declaration is not in the exported object-handle set" message. */
  UNEXPORTED_DEPENDENCY_TYPE(droppedFromCSharp = true),

  /** ADR-074: an `expect class` actualized by an `actual typealias` whose erased target the
   *  forward direction does not export (a platform-library type, a stdlib type, an out-of-scope
   *  package, or a parameterized target — v1 admits only a redirect to a plain class). Distinct
   *  from [UNEXPORTED_DEPENDENCY_TYPE], whose `include(...)` hint is wrong here: a platform
   *  library can never be brought into scope. */
  ACTUAL_TYPEALIAS_TARGET(droppedFromCSharp = true),
}

internal sealed interface ForwardCallableCatalogEntry {
  val symbol: String

  data class Planned(
    val plan: ForwardCallablePlan,
  ) : ForwardCallableCatalogEntry {
    override val symbol: String = plan.invocation.symbol
  }

  data class Skipped(
    override val symbol: String,
    val reason: ForwardPlanSkipReason,
    // ADR-064: the originating declaration, so the diagnostic sink can point KSP/Gradle at the
    // author's own Kotlin source rather than at generated code. Null only where no single KSNode
    // cleanly represents the skip.
    val node: KSNode? = null,
    // ADR-066: the unexported dependency type's qualified name, when `reason ==
    // UNEXPORTED_DEPENDENCY_TYPE`. Carries enough for the diagnostic sink to build the
    // `include("<package>")` hint without re-deriving it from the generic reason enum.
    val detail: String? = null,
  ) : ForwardCallableCatalogEntry
}

/**
 * Complete planning result for the first migration slice. Every callable inspected by this
 * planner is either [ForwardCallableCatalogEntry.Planned] or explicitly [Skipped]; no raw KSP
 * type or implicit fallback reaches the emission phase.
 */
internal data class ForwardCallablePlanCatalog(
  val entries: List<ForwardCallableCatalogEntry>,
  val propertyPlans: List<ForwardPropertyPlan> = emptyList(),
) {
  val plans: List<ForwardCallablePlan> = entries.mapNotNull { entry ->
    (entry as? ForwardCallableCatalogEntry.Planned)?.plan
  }

  fun propertyFor(symbol: String): ForwardPropertyPlan? {
    val matches: List<ForwardPropertyPlan> = propertyPlans.filter { plan -> plan.symbol == symbol }
    // ADR-074: this invariant must be unreachable once the `allDeclarations` funnel filters
    // `isExpect` (an unfiltered expect/actual pair is what used to trip it). A fresh firing means
    // a *new* source of duplicate qualified names, not this one.
    require(matches.size <= 1) {
      "Forward property catalog has duplicate plans for $symbol; two declarations share one " +
          "qualified name (an unfiltered expect/actual pair is the usual cause)"
    }
    return matches.singleOrNull()
  }

  /** The skipped callables that no legacy route re-emits, so they vanish from the C# API. */
  val droppedCallables: List<ForwardCallableCatalogEntry.Skipped> = entries
    .filterIsInstance<ForwardCallableCatalogEntry.Skipped>()
    .filter { entry -> entry.reason.droppedFromCSharp }
}

/**
 * Builds the shadow plan for ordinary synchronous class methods and primitive-receiver extension
 * functions. This phase intentionally does not hand its plans to either renderer.
 */
internal class ForwardCallablePlanner(
  private val classifier: ForwardBridgeTypeClassifier,
) {
  fun catalog(
    classes: List<KSClassDeclaration>,
    functions: List<KSFunctionDeclaration>,
    extensionFunctions: List<KSFunctionDeclaration>,
    objects: List<KSClassDeclaration>,
    properties: List<KSPropertyDeclaration>,
    extensionProperties: List<KSPropertyDeclaration>,
    valueClasses: List<KSClassDeclaration> = emptyList(),
  ): ForwardCallablePlanCatalog {
    val entries: List<ForwardCallableCatalogEntry> = buildList {
      classes.forEach { cls -> addAll(classEntries(cls)) }
      classes.forEach { cls -> addAll(constructorEntries(cls)) }
      functions.forEach { function -> add(topLevelEntry(function)) }
      extensionFunctions.forEach { function -> add(extensionEntry(function)) }
      objects.forEach { obj -> addAll(objectEntries(obj)) }
      classes.forEach { cls -> addAll(companionEntries(cls)) }
      valueClasses.forEach { cls -> addAll(valueClassEntries(cls)) }
    }
    val propertyPlans: List<ForwardPropertyPlan> = ForwardPropertyPlanner(classifier).catalog(
      classes, properties, extensionProperties,
    )
    return ForwardCallablePlanCatalog(entries, propertyPlans)
  }

  /**
   * Value-class constructors (ADR-035 primary/secondary numbering), non-underlying public
   * properties, and public methods. Members keep the shipped no-errorOut ABI; only constructors
   * carry an error slot. The receiver is always the *underlying* wire value (primitive/String as
   * a Value named `value`, reference as a Handle named `handle`).
   */
  private fun valueClassEntries(cls: KSClassDeclaration): List<ForwardCallableCatalogEntry> {
    val owner: String = cls.qualifiedName?.asString() ?: return emptyList()
    val prefix: String = cls.simpleName.asString().lowercase()
    val underlyingParam = cls.primaryConstructor?.parameters?.firstOrNull() ?: return emptyList()
    val underlyingPropName: String = underlyingParam.name?.asString() ?: return emptyList()
    val classifiedUnderlying: BridgeType = classifier.classify(underlyingParam.type.resolve())
    // Sealed (and other handle-shaped specialized) underlyings still cross as StableRef handles
    // on the shipped ABI — same as ObjectHandle. Map them to a Handle receiver so methods like
    // ObservationResult.describe keep working after ordinary legacy deletion.
    val underlyingType: BridgeType = if (
      classifiedUnderlying is BridgeType.SpecializedProtocol &&
      classifiedUnderlying.name.startsWith("sealed helper ")
    ) {
      BridgeType.ObjectHandle(classifiedUnderlying.name.removePrefix("sealed helper "))
    } else {
      classifiedUnderlying
    }
    val isReferenceUnderlying: Boolean = underlyingType is BridgeType.ObjectHandle

    val receiver: ForwardReceiver = if (isReferenceUnderlying) {
      ForwardReceiver.Handle(underlyingType, name = "handle")
    } else {
      ForwardReceiver.Value(underlyingType, name = "value")
    }

    // ADR-066 amendment to ADR-064's SKIPPED_INHERITED_MEMBER filter: `origin != Origin.KOTLIN`
    // is wrong once a value class can live in a dependency module — every member of a KOTLIN_LIB
    // declaration (author-declared or not) reports `origin == KOTLIN_LIB`, so the old rule would
    // drop the entire cross-module value class, not just its delegated members. The only
    // origin-independent signal available, verified against a real klib, is "a supertype declares
    // a member with this simple name" — this also correctly catches interface delegation
    // (`CharSequence by value`), which forwards members with `parentDeclaration == cls` and is
    // otherwise indistinguishable from a hand-written member. Computed once per class: cheap
    // relative to walking every member, and `getAllSuperTypes()` is documented as expensive.
    val inheritedNames: Set<String> = cls.getAllSuperTypes()
      .mapNotNull { superType -> superType.declaration as? KSClassDeclaration }
      .flatMap { superType ->
        superType.getAllFunctions().map { function -> function.simpleName.asString() } +
            superType.getAllProperties().map { property -> property.simpleName.asString() }
      }
      .toSet()

    return buildList {
      addAll(
        valueClassConstructorEntries(
          cls,
          owner,
          prefix,
          underlyingPropName,
          underlyingType,
          isReferenceUnderlying
        )
      )
      addAll(
        valueClassPropertyEntries(cls, owner, prefix, underlyingPropName, receiver, inheritedNames),
      )
      addAll(valueClassMethodEntries(cls, owner, prefix, receiver, inheritedNames))
    }
  }

  private fun valueClassConstructorEntries(
    cls: KSClassDeclaration,
    owner: String,
    prefix: String,
    underlyingPropName: String,
    underlyingType: BridgeType,
    isReferenceUnderlying: Boolean,
  ): List<ForwardCallableCatalogEntry> {
    // Constructor exports return the *underlying* value (ADR-014 unwrapped bridge), not a
    // StableRef of the value class. Reference-underlying primaries are deferred (ADR-035).
    val secondaryConstructors: List<KSFunctionDeclaration> = cls.declarations
      .filterIsInstance<KSFunctionDeclaration>()
      .filter { it.simpleName.asString() == "<init>" }
      .filter { it != cls.primaryConstructor }
      .filter { it.getVisibility() == Visibility.PUBLIC }
      .toList()

    // Reference-underlying constructors stay on the legacy path: ADR-035 defers the primary, and
    // any secondary still uses the historical export numbering. Planning them would force the
    // ObjectHandle/StableRef result shape, which is not the shipped (rare) secondary ABI.
    if (isReferenceUnderlying) return emptyList()

    val exports: List<Pair<KSFunctionDeclaration, Pair<String, String>>> = buildList {
      val primary = cls.primaryConstructor
      if (primary != null && primary.getVisibility() == Visibility.PUBLIC) {
        add(primary to ("${prefix}_create" to ""))
      }
      secondaryConstructors.forEachIndexed { index, ctor ->
        val number: Int = index + 2
        add(ctor to ("${prefix}_create_$number" to "_$number"))
      }
    }

    return exports.map { (ctor, names) ->
      val (export, suffix) = names
      planOrSkip(
        symbol = "$owner.<init>$suffix",
        publicName = "Create$suffix",
        exportName = export,
        receiver = ForwardReceiver.Static,
        parameters = ctor.parameters.map { parameter ->
          (parameter.name?.asString() ?: "_") to classifier.classify(parameter.type.resolve())
        },
        result = underlyingType,
        origin = ForwardCallableOrigin.VALUE_CLASS,
        target = owner,
        // Underlying property name used by the Kotlin emitter to unbox: `Owner(args).prop`.
        invocationReceiver = underlyingPropName,
        includeError = true,
      )
    }
  }

  private fun valueClassPropertyEntries(
    cls: KSClassDeclaration,
    owner: String,
    prefix: String,
    underlyingPropName: String,
    receiver: ForwardReceiver,
    inheritedNames: Set<String>,
  ): List<ForwardCallableCatalogEntry> = cls.getAllProperties()
    .filter { it.getVisibility() == Visibility.PUBLIC }
    .filter { it.simpleName.asString() != underlyingPropName }
    .map { prop ->
      val name: String = prop.simpleName.asString()
      // ADR-064 (ROADMAP line 77), amended by ADR-066: a property whose declaration site is not
      // the value class itself, or that a supertype (including an interface delegate, e.g.
      // `CharSequence by value`'s `length`) also declares by this simple name, is a v1
      // product-scope skip, not a silently-bridged member. See `valueClassEntries` for why the
      // supertype-name check (not `Origin.KOTLIN`) is the origin-independent signal this needs.
      if (prop.parentDeclaration != cls || name in inheritedNames) {
        return@map ForwardCallableCatalogEntry.Skipped(
          "$owner.$name", ForwardPlanSkipReason.INHERITED_MEMBER, node = prop,
        )
      }
      planOrSkip(
        symbol = "$owner.$name",
        publicName = name.replaceFirstChar { it.uppercase() },
        exportName = "${prefix}_get_$name",
        receiver = receiver,
        parameters = emptyList(),
        result = classifier.classify(prop.type.resolve()),
        origin = ForwardCallableOrigin.VALUE_CLASS,
        target = owner,
        includeError = false,
        // Property getter: export name contains `_get_`; emitter uses bare member access.
        valueClassProperty = true,
        node = prop,
      )
    }
    .toList()

  private fun valueClassMethodEntries(
    cls: KSClassDeclaration,
    owner: String,
    prefix: String,
    receiver: ForwardReceiver,
    inheritedNames: Set<String>,
  ): List<ForwardCallableCatalogEntry> {
    val excluded: Set<String> = setOf(
      "equals", "hashCode", "toString", "<init>",
      "box-impl", "unbox-impl", "constructor-impl",
      "hashCode-impl", "equals-impl", "equals-impl0", "toString-impl",
    )
    return cls.getAllFunctions()
      .filter { it.getVisibility() == Visibility.PUBLIC }
      .filter { it.simpleName.asString() !in excluded }
      .map { method ->
        val name: String = method.simpleName.asString()
        // ADR-064 (ROADMAP line 77), amended by ADR-066: see `valueClassPropertyEntries` above —
        // the same supertype-simple-name signal (not `Origin.KOTLIN`) catches both genuine
        // supertype inheritance and interface delegation (`CharSequence by value`'s `get` /
        // `subSequence`), the two constructs `parentDeclaration` alone cannot tell apart
        // cross-module.
        if (method.parentDeclaration != cls || name in inheritedNames) {
          return@map ForwardCallableCatalogEntry.Skipped(
            "$owner.$name", ForwardPlanSkipReason.INHERITED_MEMBER, node = method,
          )
        }
        val structuralReason: ForwardPlanSkipReason? = when {
          method.modifiers.contains(Modifier.SUSPEND) -> ForwardPlanSkipReason.SUSPEND
          method.typeParameters.isNotEmpty() -> ForwardPlanSkipReason.GENERIC
          else -> null
        }
        if (structuralReason != null) {
          ForwardCallableCatalogEntry.Skipped("$owner.$name", structuralReason, node = method)
        } else {
          planOrSkip(
            symbol = "$owner.$name",
            publicName = name.replaceFirstChar { it.uppercase() },
            exportName = "${prefix}_$name",
            receiver = receiver,
            parameters = method.parameters.map { parameter ->
              (parameter.name?.asString() ?: "_") to classifier.classify(parameter.type.resolve())
            },
            result = method.returnType?.resolve()?.let(classifier::classify) ?: BridgeType.Unit,
            origin = ForwardCallableOrigin.VALUE_CLASS,
            target = owner,
            includeError = false,
            node = method,
          )
        }
      }
      .toList()
  }

  /**
   * ADR-040: dispatch-export plans for an interface's own declared members (reachability-driven —
   * only called for interfaces the caller already determined appear in a planned return
   * position). The receiver classifies as [BridgeType.ObjectHandle] rather than
   * [BridgeType.Interface] even though the receiver *is* an interface — a receiver is only ever
   * lowered via `asStableRef`, never constructed, so the extra construction spelling
   * [BridgeType.Interface] carries would be unused (sub-decision A.1's Consequences #3).
   *
   * Unlike [classEntries], an interface member with no body still needs an export (the concrete
   * object behind the handle always implements it), so the ABSTRACT structural skip does not
   * apply here.
   */
  fun interfaceEntries(iface: KSClassDeclaration): List<ForwardCallableCatalogEntry> {
    val ifaceName: String = iface.qualifiedName?.asString() ?: return emptyList()
    val prefix: String = iface.simpleName.asString().lowercase()
    val receiverType: BridgeType = BridgeType.ObjectHandle(ifaceName)
    val methods: List<KSFunctionDeclaration> = iface.getAllFunctions()
      .filter { method -> method.getVisibility() == Visibility.PUBLIC }
      .filter { method -> method.simpleName.asString() !in setOf("equals", "hashCode", "toString", "<init>") }
      .filter { method -> method.parentDeclaration == iface }
      .toList()

    return methods.map { method ->
      val symbol: String = "$ifaceName.${method.simpleName.asString()}"
      val structuralReason: ForwardPlanSkipReason? = when {
        method.modifiers.contains(Modifier.SUSPEND) -> ForwardPlanSkipReason.SUSPEND
        method.typeParameters.isNotEmpty() -> ForwardPlanSkipReason.GENERIC
        else -> null
      }
      if (structuralReason != null) {
        ForwardCallableCatalogEntry.Skipped(symbol, structuralReason, node = method)
      } else {
        planOrSkip(
          symbol = symbol,
          publicName = method.simpleName.asString().replaceFirstChar { it.uppercase() },
          exportName = "${prefix}_${method.simpleName.asString()}",
          receiver = ForwardReceiver.Handle(receiverType),
          parameters = method.parameters.map { parameter ->
            (parameter.name?.asString() ?: "_") to classifier.classify(parameter.type.resolve())
          },
          result = method.returnType?.resolve()?.let(classifier::classify) ?: BridgeType.Unit,
          origin = ForwardCallableOrigin.CLASS,
          node = method,
        )
      }
    }
  }

  private fun classEntries(cls: KSClassDeclaration): List<ForwardCallableCatalogEntry> {
    val className: String = cls.simpleName.asString()
    val prefix: String = className.lowercase()
    val receiverType: BridgeType = BridgeType.ObjectHandle(
      requireNotNull(cls.qualifiedName?.asString()) {
        "Forward class planner cannot create a handle for local ${className}"
      }
    )
    val methods: List<KSFunctionDeclaration> = cls.getAllFunctions()
      .filter { method -> method.getVisibility() == Visibility.PUBLIC }
      .filter { method ->
        val name: String = method.simpleName.asString()
        val isDataClassMethod: Boolean = cls.modifiers.contains(Modifier.DATA) &&
            (name == "copy" || name.startsWith("component"))
        name !in setOf("equals", "hashCode", "toString", "<init>") && !isDataClassMethod
      }
      .filter { method -> method.parentDeclaration == cls }
      .toList()
    val interfaceBridgeMethods: Set<KSFunctionDeclaration> = findInterfaceBridgePairs(methods)
      .flatMap { pair -> listOf(pair.first, pair.second) }
      .toSet()
    val storedCallbackMethods: Set<KSFunctionDeclaration> = findStoredCallbackPairs(methods)
      .flatMap { pair -> listOf(pair.first, pair.second) }
      .toSet()

    return methods.map { method ->
      val symbol: String = "${cls.qualifiedName?.asString() ?: className}.${method.simpleName.asString()}"
      val structuralReason: ForwardPlanSkipReason? = when {
        method.modifiers.contains(Modifier.ABSTRACT) -> ForwardPlanSkipReason.ABSTRACT
        method.modifiers.contains(Modifier.SUSPEND) -> ForwardPlanSkipReason.SUSPEND
        method.typeParameters.isNotEmpty() -> ForwardPlanSkipReason.GENERIC
        method in interfaceBridgeMethods || method in storedCallbackMethods -> ForwardPlanSkipReason.CALLBACK_PROTOCOL
        else -> null
      }
      if (structuralReason != null) {
        ForwardCallableCatalogEntry.Skipped(symbol, structuralReason, node = method)
      } else {
        planOrSkip(
          symbol = symbol,
          publicName = method.simpleName.asString().replaceFirstChar { it.uppercase() },
          exportName = "${prefix}_${method.simpleName.asString()}",
          receiver = ForwardReceiver.Handle(receiverType),
          parameters = method.parameters.map { parameter ->
            (parameter.name?.asString() ?: "_") to classifier.classify(parameter.type.resolve())
          },
          result = method.returnType?.resolve()?.let(classifier::classify) ?: BridgeType.Unit,
          origin = ForwardCallableOrigin.CLASS,
          node = method,
        )
      }
    }
  }

  private fun constructorEntries(cls: KSClassDeclaration): List<ForwardCallableCatalogEntry> {
    if (cls.modifiers.contains(Modifier.ABSTRACT)) return emptyList()
    val owner: String = cls.qualifiedName?.asString() ?: return emptyList()
    val prefix: String = cls.simpleName.asString().lowercase()
    val result = BridgeType.ObjectHandle(owner)
    val constructors: List<KSFunctionDeclaration> = cls.getConstructors()
      .filter { it.getVisibility() == Visibility.PUBLIC }
      .toList()
    val primary = cls.primaryConstructor
    return buildList {
      if (primary != null) add(constructorEntry(primary, owner, "${prefix}_create", "Create", result, ""))
      constructors
        .filter { constructor -> constructor != primary }
        .forEachIndexed { index, constructor ->
          add(
            constructorEntry(
              constructor,
              owner,
              "${prefix}_create_${index + 2}",
              "Create",
              result,
              "_${index + 2}",
            )
          )
        }
      if (cls.modifiers.contains(Modifier.DATA) && primary != null) {
        val receiver = ForwardReceiver.Handle(result)
        add(
          planOrSkip(
            symbol = "$owner.copy",
            publicName = "Copy",
            exportName = "${prefix}_copy",
            receiver = receiver,
            parameters = primary.parameters.map { parameter ->
              (parameter.name?.asString() ?: "_") to classifier.classify(parameter.type.resolve())
            },
            result = result,
            origin = ForwardCallableOrigin.COPY,
            node = primary,
          )
        )
      }
    }
  }

  private fun constructorEntry(
    constructor: KSFunctionDeclaration,
    owner: String,
    export: String,
    publicName: String,
    result: BridgeType.ObjectHandle,
    suffix: String,
  ): ForwardCallableCatalogEntry = planOrSkip(
    symbol = "$owner.<init>$suffix",
    publicName = publicName,
    exportName = export,
    receiver = ForwardReceiver.Static,
    parameters = constructor.parameters.map { parameter ->
      (parameter.name?.asString() ?: "_") to classifier.classify(parameter.type.resolve())
    },
    result = result,
    origin = ForwardCallableOrigin.CONSTRUCTOR,
    target = owner,
    node = constructor,
  )

  private fun topLevelEntry(function: KSFunctionDeclaration): ForwardCallableCatalogEntry = staticEntry(
    function = function,
    symbol = "${function.packageName.asString()}.${function.simpleName.asString()}",
    publicName = toCName(function.simpleName.asString()).csharpIdentifier(),
    exportName = toCName(function.simpleName.asString()),
    origin = ForwardCallableOrigin.TOP_LEVEL,
    target = null,
  )

  private fun objectEntries(obj: KSClassDeclaration): List<ForwardCallableCatalogEntry> {
    val owner: String = obj.qualifiedName?.asString() ?: return emptyList()
    val prefix: String = obj.simpleName.asString().lowercase()
    return obj.getAllFunctions()
      .filter { it.getVisibility() == Visibility.PUBLIC }
      .filter { it.parentDeclaration == obj }
      .filter { it.simpleName.asString() !in setOf("equals", "hashCode", "toString", "<init>") }
      .map { function ->
        val name: String = function.simpleName.asString()
        staticEntry(
          function = function,
          symbol = "$owner.$name",
          publicName = name.replaceFirstChar { it.uppercase() },
          exportName = "${prefix}_${toCName(name)}",
          origin = ForwardCallableOrigin.OBJECT,
          target = owner,
        )
      }.toList()
  }

  private fun companionEntries(cls: KSClassDeclaration): List<ForwardCallableCatalogEntry> {
    val owner: String = cls.qualifiedName?.asString() ?: return emptyList()
    val companion: KSClassDeclaration = cls.declarations.filterIsInstance<KSClassDeclaration>()
      .firstOrNull { it.isCompanionObject } ?: return emptyList()
    val prefix: String = cls.simpleName.asString().lowercase()
    return companion.getAllFunctions()
      .filter { it.getVisibility() == Visibility.PUBLIC }
      .filter { it.simpleName.asString() !in setOf("equals", "hashCode", "toString", "<init>") }
      .map { function ->
        val name: String = function.simpleName.asString()
        staticEntry(
          function = function,
          symbol = "$owner.Companion.$name",
          publicName = name.replaceFirstChar { it.uppercase() },
          exportName = "${prefix}_companion_${toCName(name)}",
          origin = ForwardCallableOrigin.COMPANION,
          target = owner,
        )
      }.toList()
  }

  private fun staticEntry(
    function: KSFunctionDeclaration,
    symbol: String,
    publicName: String,
    exportName: String,
    origin: ForwardCallableOrigin,
    target: String?,
  ): ForwardCallableCatalogEntry {
    val structuralReason: ForwardPlanSkipReason? = when {
      function.modifiers.contains(Modifier.SUSPEND) -> ForwardPlanSkipReason.SUSPEND
      function.typeParameters.isNotEmpty() -> ForwardPlanSkipReason.GENERIC
      else -> null
    }
    if (structuralReason != null) {
      return ForwardCallableCatalogEntry.Skipped(symbol, structuralReason, node = function)
    }
    val result: BridgeType = function.returnType?.resolve()?.let(classifier::classify) ?: BridgeType.Unit
    val parameters: List<Pair<String, BridgeType>> = function.parameters.map { parameter ->
      (parameter.name?.asString() ?: "_") to classifier.classify(parameter.type.resolve())
    }
    // ADR-002 / MIGRATION: top-level nullable primitives keep the shipped two-call ABI.
    if (origin == ForwardCallableOrigin.TOP_LEVEL &&
      result is BridgeType.Nullable &&
      result.type is BridgeType.Primitive
    ) {
      return topLevelNullablePrimitivePlan(
        symbol = symbol,
        publicName = publicName,
        exportName = exportName,
        parameters = parameters,
        result = result,
        node = function,
      )
    }
    return planOrSkip(
      symbol = symbol,
      publicName = publicName,
      exportName = exportName,
      receiver = ForwardReceiver.Static,
      parameters = parameters,
      result = result,
      origin = origin,
      target = target,
      node = function,
    )
  }

  /**
   * Plans a top-level `fun f(...): Primitive?` as [ForwardEvaluation.LEGACY_TWO_CALL]:
   * `${export}_has_value` (BOOLEAN) + `${export}_value` (primitive wire), matching ADR-002.
   * Method/extension nullable primitives stay on the ADR-061 single-call `valueOut` shape.
   */
  private fun topLevelNullablePrimitivePlan(
    symbol: String,
    publicName: String,
    exportName: String,
    parameters: List<Pair<String, BridgeType>>,
    result: BridgeType.Nullable,
    node: KSNode? = null,
  ): ForwardCallableCatalogEntry {
    val primitive: BridgeType.Primitive = result.type as BridgeType.Primitive
    val ineligible: BridgeType? = parameters.map { it.second }.firstOrNull { type ->
      type.inputSkipReason() != null
    }
    if (ineligible != null) {
      return ForwardCallableCatalogEntry.Skipped(
        symbol, requireNotNull(ineligible.inputSkipReason()), node = node,
        detail = ineligible.actualTypeAliasTargetDetail()
          ?: ineligible.unexportedDependencyDetail(),
      )
    }

    val error: ForwardAbiParameter = errorParameter()
    val nativeInputs: List<ForwardAbiParameter> = parameters.flatMap { (name, type) ->
      nativeInputParameters(name, type)
    }
    val presence = ForwardNativeCall(
      exportName = "${exportName}_has_value",
      result = ForwardAbiWireType.BOOLEAN,
      parameters = nativeInputs + error,
    )
    val value = ForwardNativeCall(
      exportName = "${exportName}_value",
      result = primitive.wireType(),
      parameters = nativeInputs + error,
    )
    val helpers: Set<ForwardHelperRequirement> = buildSet {
      add(ForwardHelperRequirement.STABLE_REF)
      if (parameters.any { (_, type) -> type.unwrapNullable() == BridgeType.String }) {
        add(ForwardHelperRequirement.UTF8)
      }
      if (parameters.any { (_, type) -> type.unwrapNullable() is BridgeType.Enum }) {
        add(ForwardHelperRequirement.ENUM_ORDINAL)
      }
      if (parameters.any { (_, type) -> type.unwrapNullable() is BridgeType.Collection }) {
        add(ForwardHelperRequirement.COLLECTION)
      }
    }
    val plan = ForwardCallablePlan(
      invocation = ForwardInvocation(
        symbol = symbol,
        origin = ForwardCallableOrigin.TOP_LEVEL,
        target = null,
      ),
      publicSignature = ForwardPublicSignature(
        name = publicName,
        parameters = parameters.map { (name, type) -> ForwardPublicParameter(name, type) },
        result = result,
      ),
      evaluation = ForwardEvaluation.LEGACY_TWO_CALL,
      nativeExports = listOf(presence, value),
      nativeImports = listOf(presence, value),
      result = ForwardResultConvention(
        wireType = ForwardAbiWireType.BOOLEAN,
        transfer = transfer("result", result, ForwardFlow.OUT_OF_KOTLIN),
      ),
      errorSlot = error,
      helperRequirements = helpers,
    ).validate()
    return ForwardCallableCatalogEntry.Planned(plan)
  }

  private fun extensionEntry(function: KSFunctionDeclaration): ForwardCallableCatalogEntry {
    val receiver: KSType = requireNotNull(function.extensionReceiver) {
      "Forward extension planner received a non-extension function ${function.simpleName.asString()}"
    }.resolve()
    val receiverType: BridgeType = classifier.classify(receiver)
    val functionName: String = function.simpleName.asString()
    val symbol: String = "${function.packageName.asString()}.$functionName"

    // ADR-064 cell 23 / BUG-010: a generic + suspend + inline + reified extension returning
    // Result<T> has no legacy route at all — inline+reified erases at the C ABI and suspend
    // needs a concrete continuation type — so it must be recognized as a genuine drop *before*
    // the general SUSPEND/GENERIC structural checks below classify it as a silent legacy-route
    // deferral. That classification is correct for an *ordinary* suspend or generic extension
    // (each has its own working legacy route individually); it is wrong for this specific
    // combination, since nothing re-emits it. Gated narrowly (suspend + inline + a reified type
    // parameter + a `kotlin.Result` return) so ordinary suspend/generic extensions are unaffected.
    if (function.isUnsupportedSuspendGenericResultExtension()) {
      return ForwardCallableCatalogEntry.Skipped(
        symbol, ForwardPlanSkipReason.UNSUPPORTED_COMBINATION, node = function,
      )
    }

    val structuralReason: ForwardPlanSkipReason? = when {
      function.modifiers.contains(Modifier.SUSPEND) -> ForwardPlanSkipReason.SUSPEND
      function.typeParameters.isNotEmpty() -> ForwardPlanSkipReason.GENERIC
      else -> null
    }
    if (structuralReason != null) {
      return ForwardCallableCatalogEntry.Skipped(symbol, structuralReason, node = function)
    }

    return planOrSkip(
      symbol = symbol,
      publicName = toCName(functionName).replaceFirstChar { it.uppercase() },
      exportName = "${receiver.declaration.simpleName.asString().lowercase()}_${toCName(functionName)}",
      receiver = ForwardReceiver.Value(receiverType),
      parameters = function.parameters.map { parameter ->
        (parameter.name?.asString() ?: "_") to classifier.classify(parameter.type.resolve())
      },
      result = function.returnType?.resolve()?.let(classifier::classify) ?: BridgeType.Unit,
      origin = ForwardCallableOrigin.EXTENSION,
      node = function,
    )
  }

  /**
   * ADR-064 cell 23 / BUG-010: the one suspend+generic extension shape with no working legacy
   * route (`suspend inline fun <reified T> Receiver.get(...): Result<T>`, NYTimes-KMP BUG-010).
   */
  private fun KSFunctionDeclaration.isUnsupportedSuspendGenericResultExtension(): Boolean {
    if (!modifiers.contains(Modifier.SUSPEND)) return false
    if (!modifiers.contains(Modifier.INLINE)) return false
    if (typeParameters.none { it.isReified }) return false
    val returnDeclaration: String? = returnType?.resolve()?.declaration?.qualifiedName?.asString()
    return returnDeclaration == "kotlin.Result"
  }

  private fun planOrSkip(
    symbol: String,
    publicName: String,
    exportName: String,
    receiver: ForwardReceiver,
    parameters: List<Pair<String, BridgeType>>,
    result: BridgeType,
    origin: ForwardCallableOrigin,
    target: String? = null,
    invocationReceiver: String? = null,
    includeError: Boolean = true,
    valueClassProperty: Boolean = false,
    node: KSNode? = null,
  ): ForwardCallableCatalogEntry {
    val inputTypes: List<BridgeType> = buildList {
      when (receiver) {
        is ForwardReceiver.Value -> add(receiver.type)
        is ForwardReceiver.Handle -> add(receiver.type)
        ForwardReceiver.Static -> Unit
      }
      addAll(parameters.map { it.second })
    }
    val ineligible: BridgeType? = inputTypes
      .firstOrNull { type -> type.inputSkipReason() != null }
    if (ineligible != null) {
      return ForwardCallableCatalogEntry.Skipped(
        symbol, requireNotNull(ineligible.inputSkipReason()), node = node,
        detail = ineligible.actualTypeAliasTargetDetail()
          ?: ineligible.unexportedDependencyDetail(),
      )
    }

    val resultShape: ForwardResultShape? = result.shapeOrNull()
    if (resultShape == null) {
      return ForwardCallableCatalogEntry.Skipped(
        symbol, requireNotNull(result.skipReason()), node = node,
        detail = result.actualTypeAliasTargetDetail() ?: result.unexportedDependencyDetail(),
      )
    }

    val error: ForwardAbiParameter? = if (includeError) errorParameter() else null
    val nativeParameters: List<ForwardAbiParameter> = receiverParameter(receiver) +
        parameters.flatMap { (name, type) -> nativeInputParameters(name, type) } +
        resultShape.extraParameters + listOfNotNull(error)
    val nativeCall = ForwardNativeCall(
      exportName = exportName,
      result = resultShape.wireType,
      parameters = nativeParameters,
    )
    val helpers: Set<ForwardHelperRequirement> = buildSet {
      add(ForwardHelperRequirement.STABLE_REF)
      if (origin == ForwardCallableOrigin.VALUE_CLASS) add(ForwardHelperRequirement.VALUE_CLASS)
      addAll(resultShape.helperRequirements)
      if (inputTypes.any { type -> type.unwrapNullable() == BridgeType.String }) {
        add(ForwardHelperRequirement.UTF8)
      }
      if (inputTypes.any { type -> type.unwrapNullable() is BridgeType.Enum }) {
        add(ForwardHelperRequirement.ENUM_ORDINAL)
      }
      if (inputTypes.any { type -> type.unwrapNullable() is BridgeType.Collection }) {
        add(ForwardHelperRequirement.COLLECTION)
      }
    }
    val plan = ForwardCallablePlan(
      invocation = ForwardInvocation(
        symbol = symbol,
        receiver = invocationReceiver,
        origin = origin,
        target = if (valueClassProperty) "$target#property" else target,
      ),
      publicSignature = ForwardPublicSignature(
        name = publicName,
        parameters = parameters.map { (name, type) -> ForwardPublicParameter(name, type) },
        result = result,
      ),
      evaluation = ForwardEvaluation.EXACTLY_ONCE,
      nativeExports = listOf(nativeCall),
      nativeImports = listOf(nativeCall),
      result = ForwardResultConvention(
        wireType = resultShape.wireType,
        transfer = resultShape.transfer,
      ),
      errorSlot = error,
      cleanup = resultShape.cleanup,
      helperRequirements = helpers,
    ).validate()
    return ForwardCallableCatalogEntry.Planned(plan)
  }

  private fun errorParameter(): ForwardAbiParameter = ForwardAbiParameter(
    name = "errorOut",
    wireType = ForwardAbiWireType.POINTER,
    direction = ForwardAbiDirection.OUT,
    transfer = ForwardTransfer(
      subject = "error",
      type = BridgeType.ObjectHandle("kotlin.Throwable"),
      flow = ForwardFlow.OUT_OF_KOTLIN,
      passing = ForwardPassing.OUT,
      ownership = ForwardOwnership.BORROWED,
      conversion = ForwardConversion.STABLE_REF_TO_HANDLE,
    ),
  )

  private fun valueParameter(
    name: String,
    type: BridgeType,
    flow: ForwardFlow,
  ): ForwardAbiParameter = ForwardAbiParameter(
    name = name,
    wireType = type.wireType(),
    direction = ForwardAbiDirection.IN,
    transfer = transfer(name, type, flow),
  )

  /**
   * The native ABI shape for one declared input parameter. Almost every [BridgeType] fans out to
   * exactly one native parameter; a nullable primitive is the sole exception, fanning out to two
   * *adjacent* native parameters (`${name}HasValue` then `name`) in place of the single public
   * parameter, so callers must `flatMap` over the declared parameter list rather than `map`.
   */
  private fun nativeInputParameters(name: String, type: BridgeType): List<ForwardAbiParameter> = when (type) {
    is BridgeType.Primitive, BridgeType.Char, BridgeType.String -> listOf(
      valueParameter(name, type, ForwardFlow.INTO_KOTLIN),
    )

    is BridgeType.Enum -> listOf(
      ForwardAbiParameter(
        name = name,
        wireType = ForwardAbiWireType.INT32,
        direction = ForwardAbiDirection.IN,
        transfer = ForwardTransfer(
          name, type, ForwardFlow.INTO_KOTLIN, ForwardPassing.VALUE,
          ForwardOwnership.BORROWED, ForwardConversion.ORDINAL_TO_ENUM,
        ),
      )
    )

    is BridgeType.ObjectHandle, is BridgeType.Interface -> listOf(
      ForwardAbiParameter(
        name = name,
        wireType = ForwardAbiWireType.POINTER,
        direction = ForwardAbiDirection.IN,
        transfer = ForwardTransfer(
          name, type, ForwardFlow.INTO_KOTLIN, ForwardPassing.VALUE,
          ForwardOwnership.BORROWED, ForwardConversion.HANDLE_TO_STABLE_REF,
        ),
      )
    )

    // ADR-073: the POINTER / IN / HANDLE_TO_COLLECTION shape is the same for all six collection
    // kinds; only the C# prelude/cleanup factory and the Kotlin lowering expression are kind-aware.
    is BridgeType.Collection -> listOf(
      ForwardAbiParameter(
        name = name,
        wireType = ForwardAbiWireType.POINTER,
        direction = ForwardAbiDirection.IN,
        transfer = ForwardTransfer(
          name, type, ForwardFlow.INTO_KOTLIN, ForwardPassing.VALUE,
          ForwardOwnership.BORROWED, ForwardConversion.HANDLE_TO_COLLECTION,
        ),
      )
    )

    is BridgeType.Nullable -> when (val inner = type.type) {
      BridgeType.String -> listOf(
        ForwardAbiParameter(
          name = name,
          wireType = ForwardAbiWireType.STRING,
          direction = ForwardAbiDirection.IN,
          transfer = ForwardTransfer(
            name, type, ForwardFlow.INTO_KOTLIN, ForwardPassing.VALUE,
            ForwardOwnership.BORROWED, ForwardConversion.STRING_TO_UTF8,
          ),
        )
      )

      is BridgeType.ObjectHandle, is BridgeType.Interface -> listOf(
        ForwardAbiParameter(
          name = name,
          wireType = ForwardAbiWireType.POINTER,
          direction = ForwardAbiDirection.IN,
          transfer = ForwardTransfer(
            name, type, ForwardFlow.INTO_KOTLIN, ForwardPassing.VALUE,
            ForwardOwnership.BORROWED, ForwardConversion.HANDLE_TO_STABLE_REF,
          ),
        )
      )

      is BridgeType.Primitive -> listOf(
        ForwardAbiParameter(
          name = "${name}HasValue",
          wireType = ForwardAbiWireType.BOOLEAN,
          direction = ForwardAbiDirection.IN,
          transfer = ForwardTransfer(
            "${name}HasValue", BridgeType.Primitive(PrimitiveKind.BOOLEAN), ForwardFlow.INTO_KOTLIN,
            ForwardPassing.VALUE, ForwardOwnership.BORROWED, ForwardConversion.DIRECT,
          ),
        ),
        ForwardAbiParameter(
          name = name,
          wireType = inner.wireType(),
          direction = ForwardAbiDirection.IN,
          transfer = ForwardTransfer(
            name, inner, ForwardFlow.INTO_KOTLIN, ForwardPassing.VALUE,
            ForwardOwnership.BORROWED, ForwardConversion.DIRECT,
          ),
        ),
      )

      else -> error("Forward planner cannot build an input parameter for nullable $inner")
    }

    else -> error("Forward planner cannot build an input parameter for $type")
  }

  private fun transfer(subject: String, type: BridgeType, flow: ForwardFlow): ForwardTransfer = ForwardTransfer(
    subject = subject,
    type = type,
    flow = flow,
    passing = ForwardPassing.VALUE,
    ownership = ForwardOwnership.BORROWED,
    conversion = if (type == BridgeType.String && flow == ForwardFlow.INTO_KOTLIN) {
      ForwardConversion.STRING_TO_UTF8
    } else {
      ForwardConversion.DIRECT
    },
  )

  private fun BridgeType.shapeOrNull(): ForwardResultShape? = when (this) {
    BridgeType.Unit, is BridgeType.Primitive, BridgeType.Char -> ForwardResultShape(
      wireType = wireType(),
      transfer = transfer("result", this, ForwardFlow.OUT_OF_KOTLIN),
    )

    // String results cross as a native pointer (Kotlin String / C# IntPtr + PtrToStringUTF8),
    // matching property getters and the shipped value-class method ABI.
    BridgeType.String -> ForwardResultShape(
      wireType = ForwardAbiWireType.POINTER,
      transfer = ForwardTransfer(
        subject = "result",
        type = this,
        flow = ForwardFlow.OUT_OF_KOTLIN,
        passing = ForwardPassing.VALUE,
        ownership = ForwardOwnership.MATERIALIZED,
        conversion = ForwardConversion.UTF8_TO_STRING,
      ),
      helperRequirements = setOf(ForwardHelperRequirement.UTF8),
    )

    is BridgeType.Enum -> ForwardResultShape(
      wireType = ForwardAbiWireType.INT32,
      transfer = ForwardTransfer(
        subject = "result",
        type = this,
        flow = ForwardFlow.OUT_OF_KOTLIN,
        passing = ForwardPassing.VALUE,
        ownership = ForwardOwnership.BORROWED,
        conversion = ForwardConversion.ENUM_TO_ORDINAL,
      ),
      helperRequirements = setOf(ForwardHelperRequirement.ENUM_ORDINAL),
    )

    is BridgeType.ObjectHandle, is BridgeType.Interface -> handleResultShape(this)
    // ADR-014 gap this feature's fixture flushed out: a value class returned by an *ordinary*
    // (non-value-class-own) callable never had a planner-side result shape, despite the model/
    // validator already carrying BOX_VALUE_CLASS/UNBOX_VALUE_CLASS conversions for exactly this
    // position. Scoped to a String underlying only (what the fixture needs); every other
    // underlying keeps its existing VALUE_CLASS skip rather than risk a wire shape this change
    // was not verified against.
    is BridgeType.ValueClass -> valueClassResultShape(this)
    // ADR-066: an unsupported (or reachable-but-out-of-scope) element/key/value must not reach
    // ForwardCallablePlanValidator as a built Collection shape — that error()s the whole plan
    // rather than skipping just this one callable (the archive(): List<TopStory> crash this
    // feature's fixture flushed out, predating ADR-066 but only reachable once it exists).
    is BridgeType.Collection -> if (isBridgeableComponent()) {
      handleResultShape(this, ForwardHelperRequirement.COLLECTION)
    } else {
      null
    }

    is BridgeType.Nullable -> nullableResultShape(type)
    else -> null
  }

  private fun nullableResultShape(type: BridgeType): ForwardResultShape? = when (type) {
    BridgeType.String -> ForwardResultShape(
      wireType = ForwardAbiWireType.POINTER,
      transfer = ForwardTransfer(
        subject = "result",
        type = BridgeType.Nullable(type),
        flow = ForwardFlow.OUT_OF_KOTLIN,
        passing = ForwardPassing.VALUE,
        ownership = ForwardOwnership.MATERIALIZED,
        conversion = ForwardConversion.UTF8_TO_STRING,
      ),
      helperRequirements = setOf(ForwardHelperRequirement.UTF8),
    )

    is BridgeType.ObjectHandle, is BridgeType.Interface -> handleResultShape(BridgeType.Nullable(type))
    is BridgeType.Primitive -> {
      val nullable: BridgeType = BridgeType.Nullable(type)
      ForwardResultShape(
        wireType = ForwardAbiWireType.BOOLEAN,
        transfer = transfer("result", nullable, ForwardFlow.OUT_OF_KOTLIN),
        extraParameters = listOf(
          ForwardAbiParameter(
            name = "valueOut",
            wireType = ForwardAbiWireType.POINTER,
            direction = ForwardAbiDirection.OUT,
            transfer = ForwardTransfer(
              subject = "valueOut",
              type = type,
              flow = ForwardFlow.OUT_OF_KOTLIN,
              passing = ForwardPassing.OUT,
              ownership = ForwardOwnership.BORROWED,
              conversion = ForwardConversion.DIRECT,
            ),
          )
        ),
      )
    }

    else -> null
  }

  private fun handleResultShape(
    type: BridgeType,
    helper: ForwardHelperRequirement? = null,
  ): ForwardResultShape = ForwardResultShape(
    wireType = ForwardAbiWireType.POINTER,
    transfer = ForwardTransfer(
      subject = "result",
      type = type,
      flow = ForwardFlow.OUT_OF_KOTLIN,
      passing = ForwardPassing.VALUE,
      ownership = ForwardOwnership.OWNED_HANDLE,
      conversion = when (type.unwrapNullable()) {
        is BridgeType.Collection -> ForwardConversion.COLLECTION_TO_HANDLE
        else -> ForwardConversion.STABLE_REF_TO_HANDLE
      },
    ),
    cleanup = listOf(ForwardCleanup("result", ForwardCleanupKind.DISPOSE_STABLE_REF)),
    helperRequirements = setOfNotNull(helper),
  )

  /**
   * ADR-014 (ordinary position): the value class's underlying wire value crosses the boundary
   * unchanged; the Kotlin export unboxes it (`result.${underlyingPropertyName}`, wired by
   * [ForwardCallableOrigin] alone — the Kotlin emitter reads `type.underlyingPropertyName`
   * directly) and the C# wrapper reconstructs `new StructType(rawValue)`. Reuses the underlying's
   * own shape verbatim except for the outer transfer, which must record `type` (not the
   * underlying) with [ForwardConversion.UNBOX_VALUE_CLASS] — the tag [ForwardCallablePlanValidator
   * .requiredConversion] demands for a `BridgeType.ValueClass` result.
   */
  private fun valueClassResultShape(type: BridgeType.ValueClass): ForwardResultShape? {
    if (type.underlying != BridgeType.String) return null
    val underlyingShape: ForwardResultShape = type.underlying.shapeOrNull() ?: return null
    return underlyingShape.copy(
      transfer = underlyingShape.transfer.copy(
        type = type,
        conversion = ForwardConversion.UNBOX_VALUE_CLASS,
      ),
      helperRequirements = underlyingShape.helperRequirements +
          ForwardHelperRequirement.VALUE_CLASS,
    )
  }

  private data class ForwardResultShape(
    val wireType: ForwardAbiWireType,
    val transfer: ForwardTransfer,
    val extraParameters: List<ForwardAbiParameter> = emptyList(),
    val cleanup: List<ForwardCleanup> = emptyList(),
    val helperRequirements: Set<ForwardHelperRequirement> = emptySet(),
  )

  private sealed interface ForwardReceiver {
    val type: BridgeType?

    data class Handle(
      override val type: BridgeType,
      val name: String = "handle",
    ) : ForwardReceiver

    data class Value(
      override val type: BridgeType,
      val name: String = "receiver",
    ) : ForwardReceiver

    data object Static : ForwardReceiver {
      override val type: BridgeType? = null
    }
  }

  private fun receiverParameter(receiver: ForwardReceiver): List<ForwardAbiParameter> = when (receiver) {
    is ForwardReceiver.Handle -> listOf(
      ForwardAbiParameter(
        name = receiver.name,
        wireType = ForwardAbiWireType.POINTER,
        direction = ForwardAbiDirection.IN,
        transfer = ForwardTransfer(
          subject = receiver.name,
          type = receiver.type,
          flow = ForwardFlow.INTO_KOTLIN,
          passing = ForwardPassing.VALUE,
          ownership = ForwardOwnership.BORROWED,
          conversion = ForwardConversion.HANDLE_TO_STABLE_REF,
        ),
      )
    )

    is ForwardReceiver.Value -> nativeInputParameters(receiver.name, receiver.type)
    ForwardReceiver.Static -> emptyList()
  }

  /** ADR-066: the qualified name to feed the `SKIPPED_UNEXPORTED_DEPENDENCY_TYPE` hint, when this
   *  (possibly nullable-wrapped) type is the direct reason a callable was dropped because it is a
   *  reachable-but-out-of-scope dependency type. `null` for every other skip reason. */
  private fun BridgeType.unexportedDependencyDetail(): String? =
    (unwrapNullable() as? BridgeType.Unsupported)
      ?.takeIf { unsupported -> unsupported.isUnexportedDependency }
      ?.rendered

  /** ADR-074: the `expect` name and its erased-to target, when this (possibly nullable-wrapped)
   *  type is the direct reason a callable was dropped because its `actual typealias` target is
   *  not exportable. Encoded as `"<expect qualified name>-><target rendered name>"` so
   *  [ForwardDiagnosticKind.SKIPPED_ACTUAL_TYPEALIAS_TARGET]'s hint can name both without a
   *  second detail slot on [ForwardCallableCatalogEntry.Skipped]. `null` for every other reason. */
  private fun BridgeType.actualTypeAliasTargetDetail(): String? =
    (unwrapNullable() as? BridgeType.Unsupported)
      ?.takeIf { unsupported -> unsupported.isActualTypeAliasTarget }
      ?.let { unsupported -> "${unsupported.actualTypeAliasExpectName}->${unsupported.rendered}" }

  /**
   * ADR-066: a collection whose element (or map key/value) type is itself unsupported must skip
   * the whole callable through the normal named-diagnostic path, not reach the plan validator —
   * `handleResultShape`/`inputSkipReason` used to build a Collection shape unconditionally, so an
   * unsupported element only surfaced as a hard `IllegalStateException` out of
   * `ForwardCallablePlanValidator.validateType`, crashing the entire `packNuget` rather than
   * skipping the one member. Mirrors [ForwardCallablePlanValidator.validateType]'s error branches
   * exactly, so anything that would `error(...)` there returns `false` here instead.
   */
  private fun BridgeType.isBridgeableComponent(): Boolean = when (this) {
    BridgeType.Unit, BridgeType.Char, BridgeType.String, is BridgeType.Primitive,
    is BridgeType.Enum, is BridgeType.ObjectHandle,
      -> true

    is BridgeType.ValueClass -> underlying.isBridgeableComponent()
    is BridgeType.Nullable -> type !is BridgeType.Nullable && type != BridgeType.Unit &&
        type.isBridgeableComponent()

    is BridgeType.Collection -> {
      val isMap: Boolean = kind == CollectionKind.MAP || kind == CollectionKind.MUTABLE_MAP
      if (isMap) {
        key?.isBridgeableComponent() == true && value?.isBridgeableComponent() == true
      } else {
        element?.isBridgeableComponent() == true
      }
    }

    // ADR-040: an interface element inside a collection is deferred v1 scope ("collections of
    // interfaces" — Scope section); routed through the ordinary COLLECTION skip rather than
    // silently building an untested shape.
    is BridgeType.Interface,
    is BridgeType.RawCollection, is BridgeType.RawKSType, is BridgeType.SpecializedProtocol,
    is BridgeType.Unsupported,
      -> false
  }

  /**
   * ADR-073: the component types the C# write side can actually box, for an input-position
   * `Map`/`Set` (and their mutable variants): the six `nuget_wrap_*` primitives plus an object
   * handle (via `CreateMap`/`CreateSet`'s reflective `_handle` fallback). Narrower than
   * [isBridgeableComponent], which also admits `Nullable`, `ValueClass`, `Char`, nested
   * `Collection`, `Enum` and the narrow-primitive kinds (none of which the write side can box),
   * because those overshoots would otherwise either crash `packNuget`
   * (`ValueClass`/`Nullable`/nested `Collection`, no `elementKotlinTypeName` branch) or throw at
   * runtime (`NotSupportedException`, no matching `nuget_wrap_*`). Deliberately *not* applied to
   * `List`; narrowing the list-element predicate is a separate, deferred decision (ADR-073 Scope
   * item 1).
   */
  private fun BridgeType.isWrappableComponent(): Boolean = when (this) {
    BridgeType.String -> true
    is BridgeType.Primitive -> kind in setOf(
      PrimitiveKind.INT, PrimitiveKind.LONG, PrimitiveKind.FLOAT,
      PrimitiveKind.DOUBLE, PrimitiveKind.BOOLEAN,
    )

    is BridgeType.ObjectHandle -> true
    else -> false
  }

  private fun BridgeType.skipReason(): ForwardPlanSkipReason? = when (this) {
    BridgeType.Unit, is BridgeType.Primitive -> null
    BridgeType.Char -> ForwardPlanSkipReason.CHAR
    BridgeType.String -> ForwardPlanSkipReason.STRING
    is BridgeType.Nullable -> ForwardPlanSkipReason.NULLABLE
    // ADR-066: a bridgeable-shaped Collection (List/MutableList result, Map/Set) that still
    // reaches here failed for its own reason (nothing else calls skipReason() on a bridgeable
    // Collection); an unsupported element/key/value attributes to that component's own reason
    // (e.g. UNEXPORTED_DEPENDENCY_TYPE) instead of the generic COLLECTION bucket, which
    // `toDiagnosticKind()` reserves for the genuinely input-position case.
    is BridgeType.Collection -> if (isBridgeableComponent()) {
      ForwardPlanSkipReason.COLLECTION
    } else {
      (element ?: key ?: value)?.skipReason() ?: ForwardPlanSkipReason.UNSUPPORTED
    }

    is BridgeType.RawCollection -> ForwardPlanSkipReason.COLLECTION
    is BridgeType.Enum -> ForwardPlanSkipReason.ENUM
    is BridgeType.ObjectHandle -> ForwardPlanSkipReason.HANDLE
    // Never actually reached by an ordinary interface result (shapeOrNull's Interface branch
    // always succeeds); only reachable defensively via a Collection-of-Interface element skip.
    is BridgeType.Interface -> ForwardPlanSkipReason.HANDLE
    is BridgeType.ValueClass -> ForwardPlanSkipReason.VALUE_CLASS
    is BridgeType.SpecializedProtocol -> when {
      // ADR-065: StateFlow shares the plain-Flow legacy route (both are named legacy exports in
      // exports/ClassExports.kt + cir/CirFlowRenderer.kt); it is a distinct SpecializedProtocol
      // name only so the classifier never confuses it with plain Flow (ADR-065 detection order).
      name.startsWith("state flow ") -> ForwardPlanSkipReason.FLOW_PROTOCOL
      name.startsWith("flow ") -> ForwardPlanSkipReason.FLOW_PROTOCOL
      name.startsWith("suspend lambda ") -> ForwardPlanSkipReason.SUSPEND_CALLBACK_PROTOCOL
      name.startsWith("lambda ") || name.startsWith("interface bridge ") -> ForwardPlanSkipReason.CALLBACK_PROTOCOL
      name.startsWith("sealed helper ") -> ForwardPlanSkipReason.SEALED_PROTOCOL
      name.startsWith("generic declaration ") -> ForwardPlanSkipReason.GENERIC
      else -> error("Forward planner has no explicit legacy route for specialized protocol $name")
    }

    is BridgeType.RawKSType -> error("Forward planner received raw KSP type $rendered")
    // ADR-074: checked ahead of isUnexportedDependency -- an actual-typealias-target redirect can
    // land on either an out-of-scope module-local type or a cross-module one, and both must carry
    // this ADR's own diagnostic, not the generic UNEXPORTED_DEPENDENCY_TYPE include(...) hint.
    is BridgeType.Unsupported -> when {
      isActualTypeAliasTarget -> ForwardPlanSkipReason.ACTUAL_TYPEALIAS_TARGET
      isUnexportedDependency -> ForwardPlanSkipReason.UNEXPORTED_DEPENDENCY_TYPE
      else -> ForwardPlanSkipReason.UNSUPPORTED
    }
  }

  private fun BridgeType.inputSkipReason(): ForwardPlanSkipReason? = when (this) {
    BridgeType.String, BridgeType.Char -> null
    is BridgeType.Enum -> null
    // ADR-040 sub-decision B: an interface-typed parameter is plannable — the C# lowering routes
    // through NugetMarshal.HandleOf (ForwardCirPlanProjection.callArgument), which throws
    // NotSupportedException at runtime for a C#-implemented (non-Kotlin-backed) IFoo.
    is BridgeType.ObjectHandle, is BridgeType.Interface -> null
    // ADR-066: an unsupported element must not silently produce a Collection shape that later
    // crashes plan validation — route it through the same skip path as any other unsupported
    // input, preferring the (element ?: key ?: value)'s own reason (e.g.
    // UNEXPORTED_DEPENDENCY_TYPE) when known.
    is BridgeType.Collection -> when {
      !isBridgeableComponent() ->
        (element ?: key ?: value)?.skipReason() ?: ForwardPlanSkipReason.UNSUPPORTED

      kind == CollectionKind.LIST || kind == CollectionKind.MUTABLE_LIST -> null
      // ADR-073: map/set inputs are admitted only for components the write side can box
      // (isWrappableComponent); List is deliberately left on the wider isBridgeableComponent
      // check above (ADR-073 Scope item 1, out of scope for this change).
      kind == CollectionKind.MAP || kind == CollectionKind.MUTABLE_MAP ->
        if (key?.isWrappableComponent() == true && value?.isWrappableComponent() == true) null
        else ForwardPlanSkipReason.COLLECTION

      else ->
        if (element?.isWrappableComponent() == true) null else ForwardPlanSkipReason.COLLECTION
    }

    is BridgeType.Nullable -> when (type) {
      BridgeType.String, is BridgeType.ObjectHandle, is BridgeType.Primitive -> null
      else -> ForwardPlanSkipReason.NULLABLE
    }

    else -> skipReason()
  }

  private fun BridgeType.wireType(): ForwardAbiWireType = when (this) {
    BridgeType.Unit -> ForwardAbiWireType.VOID
    is BridgeType.Primitive -> when (kind) {
      PrimitiveKind.BOOLEAN -> ForwardAbiWireType.BOOLEAN
      PrimitiveKind.BYTE -> ForwardAbiWireType.INT8
      PrimitiveKind.UBYTE -> ForwardAbiWireType.UINT8
      PrimitiveKind.SHORT -> ForwardAbiWireType.INT16
      PrimitiveKind.USHORT -> ForwardAbiWireType.UINT16
      PrimitiveKind.INT -> ForwardAbiWireType.INT32
      PrimitiveKind.UINT -> ForwardAbiWireType.UINT32
      PrimitiveKind.LONG -> ForwardAbiWireType.INT64
      PrimitiveKind.ULONG -> ForwardAbiWireType.UINT64
      PrimitiveKind.FLOAT -> ForwardAbiWireType.FLOAT32
      PrimitiveKind.DOUBLE -> ForwardAbiWireType.FLOAT64
    }

    BridgeType.String -> ForwardAbiWireType.STRING
    BridgeType.Char -> ForwardAbiWireType.CHAR16
    is BridgeType.Nullable,
    is BridgeType.Collection,
    is BridgeType.RawCollection,
    is BridgeType.Enum,
    is BridgeType.ObjectHandle,
    is BridgeType.Interface,
    is BridgeType.ValueClass,
    is BridgeType.SpecializedProtocol,
    is BridgeType.RawKSType,
    is BridgeType.Unsupported,
      -> error("Forward planner requested a wire type for ineligible $this")
  }

  private fun BridgeType.unwrapNullable(): BridgeType = if (this is BridgeType.Nullable) type else this

  private fun String.csharpIdentifier(): String = if (this in CSHARP_KEYWORDS) "@$this" else this

  private companion object {
    val CSHARP_KEYWORDS: Set<String> = setOf(
      "abstract", "as", "base", "bool", "break", "byte", "case", "catch", "char", "checked",
      "class", "const", "continue", "decimal", "default", "delegate", "do", "double", "else",
      "enum", "event", "explicit", "extern", "false", "finally", "fixed", "float", "for",
      "foreach", "goto", "if", "implicit", "in", "int", "interface", "internal", "is", "lock",
      "long", "namespace", "new", "null", "object", "operator", "out", "override", "params",
      "private", "protected", "public", "readonly", "ref", "return", "sbyte", "sealed", "short",
      "sizeof", "stackalloc", "static", "string", "struct", "switch", "this", "throw", "true",
      "try", "typeof", "uint", "ulong", "unchecked", "unsafe", "ushort", "using", "virtual",
      "void", "volatile", "while",
    )
  }
}
