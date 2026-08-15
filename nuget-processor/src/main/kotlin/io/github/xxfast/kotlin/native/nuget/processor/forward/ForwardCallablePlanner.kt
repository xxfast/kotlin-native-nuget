package io.github.xxfast.kotlin.native.nuget.processor.forward

import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.getConstructors
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeParameter
import com.google.devtools.ksp.symbol.KSValueParameter
import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Visibility
import io.github.xxfast.kotlin.native.nuget.processor.cir.expandAliases
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

  // ADR-076: same defensive classification as CHAR/STRING/ENUM/HANDLE/OBJECT above -- Instant is a
  // supported ordinary type with no legacy route, so a skip carrying it can only mean a genuine
  // drop, even though the planner does not currently reach it (shapeOrNull's Instant branch
  // always succeeds).
  INSTANT(droppedFromCSharp = true),
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

  /** ADR-088: a bound C# interface at a position v1 does not marshal (nullable, property,
   *  collection component, receiver). Named rather than folded into the generic UNSUPPORTED
   *  bucket: the type IS bridgeable, just not here, and the hint differs accordingly. */
  BOUND_INTERFACE_POSITION(droppedFromCSharp = true),

  /** ADR-088: a bound C# interface at a RETURN position that the manifest flags as not
   *  Kotlin-implementable (no `mint{Iface}Bridge`), so a plain Kotlin implementation of it cannot
   *  be lowered to a C#-side bridge. Parameter positions of the same interface stay admissible:
   *  they only need `nuget{Iface}Value`. */
  UNIMPLEMENTABLE_BOUND_INTERFACE(droppedFromCSharp = true),
}

internal sealed interface ForwardCallableCatalogEntry {
  val symbol: String

  /**
   * ADR-064 for [Skipped], ADR-095 for [Planned]: the originating declaration. On the top-level and
   * extension routes the emitters keep their declaration walks (their C# grouping needs the
   * declaration itself), so a *planned* entry must be findable by node identity — with overload
   * numbering the symbol is no longer derivable from a declaration.
   */
  val node: KSNode?

  data class Planned(
    val plan: ForwardCallablePlan,
    override val node: KSNode? = null,
    /**
     * ADR-096: this entry is a planner-synthesized omitting overload of a declared one, so it
     * shares [node] with the entry it was synthesized from. Planner-internal: the plan model,
     * `validate()` and the ABI contract never see it.
     */
    val synthesized: Boolean = false,
  ) : ForwardCallableCatalogEntry {
    override val symbol: String = plan.invocation.symbol
  }

  data class Skipped(
    override val symbol: String,
    val reason: ForwardPlanSkipReason,
    // ADR-064: the originating declaration, so the diagnostic sink can point KSP/Gradle at the
    // author's own Kotlin source rather than at generated code. Null only where no single KSNode
    // cleanly represents the skip.
    override val node: KSNode? = null,
    // ADR-066: the unexported dependency type's qualified name, when `reason ==
    // UNEXPORTED_DEPENDENCY_TYPE`. Carries enough for the diagnostic sink to build the
    // `include("<package>")` hint without re-deriving it from the generic reason enum.
    val detail: String? = null,
  ) : ForwardCallableCatalogEntry
}

/**
 * ADR-082's 2026-08-08 amendment: the declared-vs-inherited signal for value-class members.
 *
 * Every member a supertype declares, indexed for signature comparison. A member is inherited when
 * a supertype declares one of the same *kind* with the same simple name, the same arity, and
 * per-position matching parameter types (resolved qualified name plus nullability). A
 * supertype-side type *parameter* matches any argument type, deliberately conservatively: it
 * over-drops (loud diagnostic, non-colliding name as workaround) rather than exporting something
 * that may be a delegation forwarder.
 *
 * The simple-name rule this replaces also dropped author-declared members whose name merely
 * collided with a supertype's (`fun get(key: String)` next to `CharSequence.get(index: Int)`).
 * Properties keep the name-only comparison, and only against supertype *properties*: Kotlin
 * properties cannot overload.
 */
internal class ForwardSupertypeMembers private constructor(
  private val propertyNames: Set<String>,
  private val functions: List<Signature>,
) {
  /** [parameters] holds one key per position; `null` is a supertype type parameter (wildcard). */
  private data class Signature(val name: String, val parameters: List<String?>)

  fun declares(property: KSPropertyDeclaration): Boolean =
    property.simpleName.asString() in propertyNames

  fun declares(function: KSFunctionDeclaration): Boolean {
    val name: String = function.simpleName.asString()
    val parameters: List<String?> = function.parameters.map { parameter ->
      typeKey(parameter.type.resolve())
    }
    return functions.any { signature ->
      signature.name == name &&
          signature.parameters.size == parameters.size &&
          signature.parameters.zip(parameters).all { (inherited, declared) ->
            inherited == null || inherited == declared
          }
    }
  }

  companion object {
    fun of(cls: KSClassDeclaration): ForwardSupertypeMembers {
      val superTypes: List<KSClassDeclaration> = cls.getAllSuperTypes()
        .mapNotNull { superType -> superType.declaration as? KSClassDeclaration }
        .toList()
      return ForwardSupertypeMembers(
        propertyNames = superTypes
          .flatMap { superType ->
            superType.getAllProperties().map { property -> property.simpleName.asString() }
          }
          .toSet(),
        functions = superTypes.flatMap { superType ->
          superType.getAllFunctions().map { function ->
            Signature(
              name = function.simpleName.asString(),
              parameters = function.parameters.map { parameter ->
                typeKey(parameter.type.resolve())
              },
            )
          }
        },
      )
    }

    /** Null for a type-parameter position, which the comparison treats as a wildcard. */
    private fun typeKey(type: KSType): String? {
      val expanded: KSType = type.expandAliases()
      val declaration: KSDeclaration = expanded.declaration
      if (declaration is KSTypeParameter) return null
      val name: String = declaration.qualifiedName?.asString()
        ?: declaration.simpleName.asString()
      val nullable: Boolean = type.isMarkedNullable || expanded.isMarkedNullable
      return if (nullable) "$name?" else name
    }
  }
}

/**
 * Complete planning result for the first migration slice. Every callable inspected by this
 * planner is either [ForwardCallableCatalogEntry.Planned] or explicitly [Skipped]; no raw KSP
 * type or implicit fallback reaches the emission phase.
 */
internal data class ForwardCallablePlanCatalog(
  val entries: List<ForwardCallableCatalogEntry>,
  val propertyPlans: List<ForwardPropertyPlan> = emptyList(),
  // ADR-075: the property planner's own diagnostic channel — a mutable collection property
  // planned with `setter = null` because a component failed `isWrappableComponent()`, so the
  // consumer learns the C# property survives read-only rather than silently losing its setter.
  val droppedPropertySetters: List<ForwardDroppedPropertySetter> = emptyList(),
  // The property planner's whole-property channel: a property whose type it cannot plan at all,
  // minus the ones a legacy route still re-emits. Separate from droppedPropertySetters above,
  // which is a partial skip (the getter survives).
  val droppedProperties: List<ForwardDroppedProperty> = emptyList(),
  // The receiver-side counterpart of droppedProperties: an extension property dropped for its
  // receiver type rather than its own type. Same diagnostic kind, different wording.
  val droppedExtensionReceivers: List<ForwardDroppedExtensionReceiver> = emptyList(),
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

  /**
   * ADR-082: the planned value-class property getters of [owner], in planning order.
   *
   * Both emitters read their member plans off the catalog rather than re-deriving a plan key per
   * `getAllProperties()` / `getAllFunctions()` entry. Symbol-per-declaration re-derivation breaks
   * as soon as two declared members share a simple name (the overload numbering lives in the
   * planner, and a second `getAllFunctions()` entry re-emitted the *first* one's plan).
   * Constructors are excluded: their reference-underlying branch still has a legacy route that
   * needs the declaration itself.
   */
  fun valueClassProperties(owner: String): List<ForwardCallablePlan> = valueClassMembers(owner)
    .filter { plan -> plan.invocation.target?.endsWith("#property") == true }

  /** ADR-082: the planned value-class methods of [owner], in planning order. See above. */
  fun valueClassMethods(owner: String): List<ForwardCallablePlan> = valueClassMembers(owner)
    .filter { plan -> plan.invocation.target?.endsWith("#property") != true }

  /**
   * ADR-090: the planned ordinary-class methods of [owner], in planning order.
   *
   * Same reason as [valueClassMethods]: with overload numbering in the planner, a symbol is no
   * longer derivable from a `getAllFunctions()` entry, so both emitters read the member plans off
   * the catalog instead. The owner filter is *exact*, not a prefix: `interfaceEntries` also emits
   * CLASS-origin plans, keyed by the interface's own qualified name. Constructors are excluded
   * (their own origin aside, `<init>` never belongs to the method surface).
   */
  fun classMethods(owner: String): List<ForwardCallablePlan> = plans.filter { plan ->
    plan.invocation.origin == ForwardCallableOrigin.CLASS &&
        plan.invocation.symbol.substringBeforeLast('.') == owner &&
        !plan.invocation.symbol.substringAfterLast('.').startsWith("<init>")
  }

  /**
   * ADR-091: the planned constructors of [owner], in planning order (primary, secondaries, then
   * the synthesized omitting overloads).
   *
   * Same reason as [classMethods]: the ADR-034 `_$n` sequence now also carries planner-synthesized
   * entries that no `getConstructors()` walk can see, so both emitters read constructors off the
   * catalog instead of re-deriving a plan key per declaration. Owner-exact matching.
   */
  fun constructors(owner: String): List<ForwardCallablePlan> = plans.filter { plan ->
    plan.invocation.origin == ForwardCallableOrigin.CONSTRUCTOR &&
        plan.invocation.symbol.substringBeforeLast('.') == owner
  }

  /**
   * ADR-095: the planned members of object [owner], in planning order.
   *
   * Same reason as [classMethods]: with per-object overload numbering the symbol of the n-th
   * namesake is `$owner.${name}_$n`, which no `getAllFunctions()` walk can re-derive, so both
   * emitters read the object's members off the catalog. Owner-exact, not prefix.
   */
  fun objectMethods(owner: String): List<ForwardCallablePlan> = plans.filter { plan ->
    plan.invocation.origin == ForwardCallableOrigin.OBJECT &&
        plan.invocation.symbol.substringBeforeLast('.') == owner
  }

  /** ADR-095: the planned companion members of class [owner], in planning order. See above. */
  fun companionMethods(owner: String): List<ForwardCallablePlan> = plans.filter { plan ->
    plan.invocation.origin == ForwardCallableOrigin.COMPANION &&
        plan.invocation.symbol.substringBeforeLast('.') == "$owner.Companion"
  }

  /**
   * ADR-095/ADR-096: the plans for [declaration], matched by node identity rather than by symbol,
   * in planning order (the declared plan first, then its synthesized omitting overloads).
   *
   * The top-level and extension emitters keep their declaration walks — the C# halves group by
   * (namespace, file class) and by receiver simple name, and the Kotlin top-level loop has a
   * per-declaration legacy fallback — so an owner-keyed accessor cannot replace them. Identity
   * matching is sound because `NugetProcessor` collects `functions` / `extensionFunctions` once and
   * hands the *same list instances* to the planner and to both emitters (verified).
   *
   * Returns an empty list only for a declaration this planner explicitly skipped; a declaration the
   * catalog never saw is a wiring bug and fails loudly rather than silently binding to a namesake's
   * plan.
   */
  fun plansFor(declaration: KSFunctionDeclaration): List<ForwardCallablePlan> {
    val matches: List<ForwardCallableCatalogEntry> = entries
      .filter { entry -> entry.node === declaration }
    require(matches.isNotEmpty()) {
      "Forward callable catalog has no entry for " +
          "${declaration.qualifiedName?.asString() ?: declaration.simpleName.asString()}; the " +
          "emitter is walking a declaration list the planner never saw"
    }
    val planned: List<ForwardCallableCatalogEntry.Planned> = matches
      .filterIsInstance<ForwardCallableCatalogEntry.Planned>()
    // ADR-074, restated by ADR-096 in terms of *declared* plans: synthesized omitting overloads
    // legitimately share their declaration's node, a route planning one declaration twice does not.
    require(planned.count { entry -> !entry.synthesized } <= 1) {
      "Forward callable catalog has ${planned.count { entry -> !entry.synthesized }} declared " +
          "plans for one declaration of ${declaration.simpleName.asString()}; a route planned it " +
          "more than once"
    }
    return planned.map { entry -> entry.plan }
  }

  private fun valueClassMembers(owner: String): List<ForwardCallablePlan> = plans.filter { plan ->
    plan.invocation.origin == ForwardCallableOrigin.VALUE_CLASS &&
        plan.invocation.symbol.substringBeforeLast('.') == owner &&
        !plan.invocation.symbol.substringAfterLast('.').startsWith("<init>")
  }
}

/**
 * Builds the shadow plan for ordinary synchronous class methods and primitive-receiver extension
 * functions. This phase intentionally does not hand its plans to either renderer.
 */
internal class ForwardCallablePlanner(
  private val classifier: ForwardBridgeTypeClassifier,
  /**
   * ADR-091: the ADR-074 expect index, keyed by qualified name. Only source of parameter defaults
   * for an `expect`/`actual` class, whose `actual` (the export root) always reports
   * `hasDefault = false`.
   */
  private val expectsByName: Map<String, KSDeclaration> = emptyMap(),
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
      // ADR-095: top-level and extension overloads number per (package, name), the extension one
      // deliberately receiver-agnostic because its plan symbol is (`fun Cat.pat()` then
      // `fun Dog.pat()` in one package are one counter). Both counters live here rather than in the
      // per-declaration entry builders, because the scope spans the whole collected list.
      val topLevelOccurrences: MutableMap<String, Int> = mutableMapOf()
      val topLevel: List<ForwardCallableCatalogEntry> = functions.map { function ->
        topLevelEntry(function, overloadSuffix(topLevelOccurrences, function))
      }
      addAll(topLevel)
      // ADR-096: the omitting overloads, appended after *every* declared entry of this counter
      // scope so declared exports keep their numbers. The declared namesake count is snapshotted
      // first, because the synthesized pass advances the same counter.
      val declaredTopLevel: Map<String, Int> = topLevelOccurrences.toMap()
      functions.forEachIndexed { index, function ->
        if (topLevel[index] !is ForwardCallableCatalogEntry.Planned) return@forEachIndexed
        val defaults: List<Boolean> = topLevelDefaultFlags(function, declaredTopLevel)
        repeat(defaults.trailingCount()) { omitted ->
          add(
            topLevelEntry(
              function, overloadSuffix(topLevelOccurrences, function), omitted = omitted + 1,
            ).synthesized()
          )
        }
      }
      val extensionOccurrences: MutableMap<String, Int> = mutableMapOf()
      val extensions: List<ForwardCallableCatalogEntry> = extensionFunctions.map { function ->
        extensionEntry(function, overloadSuffix(extensionOccurrences, function))
      }
      addAll(extensions)
      extensionFunctions.forEachIndexed { index, function ->
        if (extensions[index] !is ForwardCallableCatalogEntry.Planned) return@forEachIndexed
        repeat(function.parameters.map { it.hasDefault }.trailingCount()) { omitted ->
          add(
            extensionEntry(
              function, overloadSuffix(extensionOccurrences, function), omitted = omitted + 1,
            ).synthesized()
          )
        }
      }
      objects.forEach { obj -> addAll(objectEntries(obj)) }
      classes.forEach { cls -> addAll(companionEntries(cls)) }
      valueClasses.forEach { cls -> addAll(valueClassEntries(cls)) }
    }
    val planner = ForwardPropertyPlanner(classifier)
    val propertyPlans: List<ForwardPropertyPlan> = planner.catalog(
      classes, properties, extensionProperties,
    )
    return ForwardCallablePlanCatalog(
      entries, propertyPlans, planner.droppedPropertySetters, planner.droppedProperties,
      planner.droppedExtensionReceivers,
    )
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
    // this member" — this also correctly catches interface delegation (`CharSequence by value`),
    // which forwards members with `parentDeclaration == cls` and is otherwise indistinguishable
    // from a hand-written member. ADR-082's 2026-08-08 amendment narrows the comparison from
    // simple names to signatures; see [ForwardSupertypeMembers]. Computed once per class: cheap
    // relative to walking every member, and `getAllSuperTypes()` is documented as expensive.
    val inherited: ForwardSupertypeMembers = ForwardSupertypeMembers.of(cls)

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
        valueClassPropertyEntries(cls, owner, prefix, underlyingPropName, receiver, inherited),
      )
      addAll(valueClassMethodEntries(cls, owner, prefix, receiver, inherited))
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
    inherited: ForwardSupertypeMembers,
  ): List<ForwardCallableCatalogEntry> = cls.getAllProperties()
    .filter { it.getVisibility() == Visibility.PUBLIC }
    .filter { it.simpleName.asString() != underlyingPropName }
    .map { prop ->
      val name: String = prop.simpleName.asString()
      // ADR-064 (ROADMAP line 77), amended by ADR-066: a property whose declaration site is not
      // the value class itself, or that a supertype (including an interface delegate, e.g.
      // `CharSequence by value`'s `length`) also declares by this simple name, is a v1
      // product-scope skip, not a silently-bridged member. See `valueClassEntries` for why the
      // supertype check (not `Origin.KOTLIN`) is the origin-independent signal this needs.
      // Properties compare by name alone (Kotlin properties cannot overload) and only against
      // supertype properties — ADR-082's amendment.
      if (prop.parentDeclaration != cls || inherited.declares(prop)) {
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
    inherited: ForwardSupertypeMembers,
  ): List<ForwardCallableCatalogEntry> {
    val excluded: Set<String> = setOf(
      "equals", "hashCode", "toString", "<init>",
      "box-impl", "unbox-impl", "constructor-impl",
      "hashCode-impl", "equals-impl", "equals-impl0", "toString-impl",
    )
    // ADR-082 amendment fix B: overload numbering, mirroring the secondary-constructor scheme.
    // Counted over *declared* same-name members in `getAllFunctions()` order, so a skipped
    // (inherited) namesake never consumes a number and the first declared overload keeps the
    // shipped unsuffixed export name.
    val occurrences: MutableMap<String, Int> = mutableMapOf()
    return cls.getAllFunctions()
      .filter { it.getVisibility() == Visibility.PUBLIC }
      .filter { it.simpleName.asString() !in excluded }
      .map { method ->
        val name: String = method.simpleName.asString()
        // ADR-064 (ROADMAP line 77), amended by ADR-066 and narrowed by ADR-082: the supertype
        // *signature* signal (not `Origin.KOTLIN`, and no longer the simple name) catches both
        // genuine supertype inheritance and interface delegation (`CharSequence by value`'s `get`
        // / `subSequence`), the two constructs `parentDeclaration` alone cannot tell apart
        // cross-module, while letting an unrelated same-name overload (`get(key: String)`)
        // through.
        if (method.parentDeclaration != cls || inherited.declares(method)) {
          return@map ForwardCallableCatalogEntry.Skipped(
            "$owner.$name", ForwardPlanSkipReason.INHERITED_MEMBER, node = method,
          )
        }
        val occurrence: Int = occurrences.merge(name, 1, Int::plus)!!
        val suffix: String = if (occurrence == 1) "" else "_$occurrence"
        val symbol: String = "$owner.$name$suffix"
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
            publicName = name.replaceFirstChar { it.uppercase() },
            exportName = "${prefix}_$name$suffix",
            receiver = receiver,
            parameters = method.parameters.map { parameter ->
              (parameter.name?.asString() ?: "_") to classifier.classify(parameter.type.resolve())
            },
            result = method.returnType?.resolve()?.let(classifier::classify) ?: BridgeType.Unit,
            origin = ForwardCallableOrigin.VALUE_CLASS,
            target = owner,
            includeError = false,
            // The symbol carries the overload suffix; the Kotlin call site must not.
            member = name,
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
    val superClass: KSClassDeclaration? = cls.forwardSuperClass()
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
      // Shared with `CirClassTranslator` and `ForwardPropertyPlanner`: a defaulted interface
      // member the class does not override still binds here, because the C# class declares that
      // interface and must carry the member (`ForwardClassMembership.kt`).
      .filter { method -> method.isForwardPlannableMemberOf(cls, superClass) }
      .toList()
    val interfaceBridgeMethods: Set<KSFunctionDeclaration> = findInterfaceBridgePairs(methods)
      .flatMap { pair -> listOf(pair.first, pair.second) }
      .toSet()
    val storedCallbackMethods: Set<KSFunctionDeclaration> = findStoredCallbackPairs(methods)
      .flatMap { pair -> listOf(pair.first, pair.second) }
      .toSet()

    val owner: String = cls.qualifiedName?.asString() ?: className
    // ADR-090: overload numbering, the scheme `valueClassMethodEntries` uses (itself ADR-034's
    // secondary-constructor scheme). Counted over the *declared plannable* members in
    // `getAllFunctions()` order — the counter increments before the structural check, so a
    // skipped namesake still consumes its number and numbering stays declaration-order stable.
    val occurrences: MutableMap<String, Int> = mutableMapOf()
    fun entryFor(method: KSFunctionDeclaration, omitted: Int): ForwardCallableCatalogEntry {
      val name: String = method.simpleName.asString()
      val occurrence: Int = occurrences.merge(name, 1, Int::plus)!!
      val suffix: String = if (occurrence == 1) "" else "_$occurrence"
      val symbol: String = "$owner.$name$suffix"
      // ADR-090: the C# modifiers, computed here because a planned entry keeps no declaration.
      // ADR-096: a synthesized entry is never `override`/`virtual` (the base has no such
      // signature, so `override` would be CS0115); overrides synthesize nothing anyway.
      val isOverride: Boolean = omitted == 0 &&
          superClass != null && method.modifiers.contains(Modifier.OVERRIDE)
      val isVirtual: Boolean = omitted == 0 && superClass == null &&
          method.modifiers.contains(Modifier.OVERRIDE) &&
          !method.modifiers.contains(Modifier.FINAL)
      val structuralReason: ForwardPlanSkipReason? = when {
        method.modifiers.contains(Modifier.ABSTRACT) -> ForwardPlanSkipReason.ABSTRACT
        method.modifiers.contains(Modifier.SUSPEND) -> ForwardPlanSkipReason.SUSPEND
        method.typeParameters.isNotEmpty() -> ForwardPlanSkipReason.GENERIC
        method in interfaceBridgeMethods || method in storedCallbackMethods -> ForwardPlanSkipReason.CALLBACK_PROTOCOL
        else -> null
      }
      return if (structuralReason != null) {
        ForwardCallableCatalogEntry.Skipped(symbol, structuralReason, node = method)
      } else {
        planOrSkip(
          symbol = symbol,
          publicName = name.replaceFirstChar { it.uppercase() },
          exportName = "${prefix}_$name$suffix",
          receiver = ForwardReceiver.Handle(receiverType),
          parameters = method.parameters.dropLast(omitted).map { parameter ->
            (parameter.name?.asString() ?: "_") to classifier.classify(parameter.type.resolve())
          },
          result = method.returnType?.resolve()?.let(classifier::classify) ?: BridgeType.Unit,
          origin = ForwardCallableOrigin.CLASS,
          // The symbol carries the overload suffix; the Kotlin call site must not.
          member = name,
          isOverride = isOverride,
          isVirtual = isVirtual,
          node = method,
        )
      }
    }
    return buildList {
      val declared: List<ForwardCallableCatalogEntry> =
        methods.map { method -> entryFor(method, 0) }
      addAll(declared)
      // ADR-096: the omitting overloads, appended after every declared entry of this
      // per-(class, name) counter scope so declared exports keep their numbers.
      methods.forEachIndexed { index, method ->
        if (declared[index] !is ForwardCallableCatalogEntry.Planned) return@forEachIndexed
        // Kotlin forbids an override from restating defaults; the base class's own synthesized
        // overload is inherited by the generated C# subclass, so this route synthesizes nothing.
        if (method.modifiers.contains(Modifier.OVERRIDE)) return@forEachIndexed
        repeat(method.parameters.map { it.hasDefault }.trailingCount()) { omitted ->
          add(entryFor(method, omitted + 1).synthesized())
        }
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
    val secondaries: List<KSFunctionDeclaration> = constructors.filter { it != primary }
    return buildList {
      if (primary != null) add(constructorEntry(primary, owner, "${prefix}_create", "Create", result, ""))
      secondaries.forEachIndexed { index, constructor ->
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
      // ADR-091: one omitting overload per trailing-defaulted suffix (the `@JvmOverloads` rule).
      // The truncated parameter list is the *only* change: the Kotlin wrapper builds its call from
      // the plan, so `Cat(name)` compiles and Kotlin computes `lives = 9` at the call site.
      // Numbers continue ADR-034's `_$n` sequence, primary's suffixes first then each secondary's
      // in declaration order, so unsuffixed/secondary exports render byte-identically to before.
      var next: Int = secondaries.size + 2
      (listOfNotNull(primary) + secondaries).forEach { constructor ->
        val defaults: List<Boolean> =
          defaultFlags(cls, constructor, isPrimary = constructor == primary)
        val trailing: Int = defaults.reversed().takeWhile { it }.count()
        repeat(trailing) { index ->
          val number: Int = next++
          add(
            constructorEntry(
              constructor,
              owner,
              "${prefix}_create_$number",
              "Create",
              result,
              "_$number",
              omitted = index + 1,
            )
          )
        }
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

  /**
   * ADR-091: per-parameter "has a default", positionally.
   *
   * KSP exposes exactly one bit ([KSValueParameter.hasDefault]) and never the default expression,
   * which is why the feature is overload synthesis rather than C# optional parameters. For an
   * `expect`/`actual` class the bit is erased on the exported root (ADR-074 exports the `actual`,
   * and Kotlin forbids an `actual` from restating a default), so the expect's primary constructor
   * is consulted positionally through [expectsByName], and only for the actual's own primary
   * constructor, since matching secondaries across the pair needs a signature rule no spike has
   * verified.
   */
  private fun defaultFlags(
    cls: KSClassDeclaration,
    constructor: KSFunctionDeclaration,
    isPrimary: Boolean,
  ): List<Boolean> {
    val expectParameters: List<KSValueParameter> =
      if (!isPrimary) emptyList()
      else (expectsByName[cls.qualifiedName?.asString()] as? KSClassDeclaration)
        ?.primaryConstructor
        ?.parameters
        .orEmpty()
    return constructor.parameters.mapIndexed { index, parameter ->
      parameter.hasDefault || expectParameters.getOrNull(index)?.hasDefault == true
    }
  }

  /**
   * ADR-096: the number of *trailing* parameters that all have a default, i.e. how many omitting
   * overloads to synthesize. A default followed anywhere by a required parameter contributes
   * nothing, because the generated wrapper is a positional Kotlin call.
   */
  private fun List<Boolean>.trailingCount(): Int = reversed().takeWhile { it }.count()

  /** ADR-096: marks a planned entry as a synthesized omitting overload; skips pass through. */
  private fun ForwardCallableCatalogEntry.synthesized(): ForwardCallableCatalogEntry =
    if (this is ForwardCallableCatalogEntry.Planned) copy(synthesized = true) else this

  /**
   * ADR-096: per-parameter "has a default" for a **top-level** function, positionally.
   *
   * The one route that consults the ADR-074 expect index, because Kotlin forbids an `actual` from
   * restating a default so every parameter of the exported root reports `hasDefault = false`. The
   * lookup is guarded three ways: only when that `(package, name)` has exactly one declared
   * namesake (the index is a `.toMap()`, so two `expect` overloads of one name collapse to the last
   * one and would attribute one declaration's defaults to another), only when the resolved expect
   * is not an extension, and only when the parameter counts match. No other route consults it in
   * v1; class/object/companion/extension read the exported declaration's own bit only.
   */
  private fun topLevelDefaultFlags(
    function: KSFunctionDeclaration,
    declaredNamesakes: Map<String, Int>,
  ): List<Boolean> {
    val key: String = "${function.packageName.asString()}.${function.simpleName.asString()}"
    val expect: KSFunctionDeclaration? =
      if (declaredNamesakes[key] != 1) null
      else (expectsByName[key] as? KSFunctionDeclaration)?.takeIf { declaration ->
        declaration.extensionReceiver == null &&
            declaration.parameters.size == function.parameters.size
      }
    return function.parameters.mapIndexed { index, parameter ->
      parameter.hasDefault || expect?.parameters?.get(index)?.hasDefault == true
    }
  }

  private fun constructorEntry(
    constructor: KSFunctionDeclaration,
    owner: String,
    export: String,
    publicName: String,
    result: BridgeType.ObjectHandle,
    suffix: String,
    omitted: Int = 0,
  ): ForwardCallableCatalogEntry = planOrSkip(
    symbol = "$owner.<init>$suffix",
    publicName = publicName,
    exportName = export,
    receiver = ForwardReceiver.Static,
    parameters = constructor.parameters.dropLast(omitted).map { parameter ->
      (parameter.name?.asString() ?: "_") to classifier.classify(parameter.type.resolve())
    },
    result = result,
    origin = ForwardCallableOrigin.CONSTRUCTOR,
    target = owner,
    node = constructor,
  )

  /**
   * ADR-095/ADR-090 numbering: the first declared namesake keeps the bare name, the n-th further
   * one is `_$n` (n from 2). The counter increments before any structural check, so a skipped
   * namesake still consumes its number and numbering stays declaration-order stable. The suffix
   * composes *after* `toCName` on the export name: `name_2` is never a C reserved word.
   */
  private fun overloadSuffix(
    occurrences: MutableMap<String, Int>,
    function: KSFunctionDeclaration,
  ): String {
    val key: String = "${function.packageName.asString()}.${function.simpleName.asString()}"
    val occurrence: Int = occurrences.merge(key, 1, Int::plus)!!
    return if (occurrence == 1) "" else "_$occurrence"
  }

  private fun topLevelEntry(
    function: KSFunctionDeclaration,
    suffix: String,
    omitted: Int = 0,
  ): ForwardCallableCatalogEntry = staticEntry(
    function = function,
    symbol = "${function.packageName.asString()}.${function.simpleName.asString()}$suffix",
    publicName = toCName(function.simpleName.asString()).csharpIdentifier(),
    exportName = "${toCName(function.simpleName.asString())}$suffix",
    origin = ForwardCallableOrigin.TOP_LEVEL,
    target = null,
    member = function.simpleName.asString(),
    omitted = omitted,
  )

  private fun objectEntries(obj: KSClassDeclaration): List<ForwardCallableCatalogEntry> {
    val owner: String = obj.qualifiedName?.asString() ?: return emptyList()
    val prefix: String = obj.simpleName.asString().lowercase()
    val occurrences: MutableMap<String, Int> = mutableMapOf()
    val members: List<KSFunctionDeclaration> = obj.getAllFunctions()
      .filter { it.getVisibility() == Visibility.PUBLIC }
      .filter { it.parentDeclaration == obj }
      .filter { it.simpleName.asString() !in setOf("equals", "hashCode", "toString", "<init>") }
      .toList()

    fun entryFor(function: KSFunctionDeclaration, omitted: Int): ForwardCallableCatalogEntry {
      val name: String = function.simpleName.asString()
      val occurrence: Int = occurrences.merge(name, 1, Int::plus)!!
      val suffix: String = if (occurrence == 1) "" else "_$occurrence"
      return staticEntry(
        function = function,
        symbol = "$owner.$name$suffix",
        publicName = name.replaceFirstChar { it.uppercase() },
        exportName = "${prefix}_${toCName(name)}$suffix",
        origin = ForwardCallableOrigin.OBJECT,
        target = owner,
        member = name,
        omitted = omitted,
      )
    }
    return buildList {
      val declared: List<ForwardCallableCatalogEntry> =
        members.map { member -> entryFor(member, 0) }
      addAll(declared)
      // ADR-096: omitting overloads, appended after the declared pass of this per-object counter.
      members.forEachIndexed { index, member ->
        if (declared[index] !is ForwardCallableCatalogEntry.Planned) return@forEachIndexed
        repeat(member.parameters.map { it.hasDefault }.trailingCount()) { omitted ->
          add(entryFor(member, omitted + 1).synthesized())
        }
      }
    }
  }

  private fun companionEntries(cls: KSClassDeclaration): List<ForwardCallableCatalogEntry> {
    val owner: String = cls.qualifiedName?.asString() ?: return emptyList()
    val companion: KSClassDeclaration = cls.declarations.filterIsInstance<KSClassDeclaration>()
      .firstOrNull { it.isCompanionObject } ?: return emptyList()
    val prefix: String = cls.simpleName.asString().lowercase()
    val occurrences: MutableMap<String, Int> = mutableMapOf()
    val members: List<KSFunctionDeclaration> = companion.getAllFunctions()
      .filter { it.getVisibility() == Visibility.PUBLIC }
      .filter { it.simpleName.asString() !in setOf("equals", "hashCode", "toString", "<init>") }
      .toList()

    fun entryFor(function: KSFunctionDeclaration, omitted: Int): ForwardCallableCatalogEntry {
      val name: String = function.simpleName.asString()
      val occurrence: Int = occurrences.merge(name, 1, Int::plus)!!
      val suffix: String = if (occurrence == 1) "" else "_$occurrence"
      return staticEntry(
        function = function,
        symbol = "$owner.Companion.$name$suffix",
        publicName = name.replaceFirstChar { it.uppercase() },
        exportName = "${prefix}_companion_${toCName(name)}$suffix",
        origin = ForwardCallableOrigin.COMPANION,
        target = owner,
        member = name,
        omitted = omitted,
      )
    }
    return buildList {
      val declared: List<ForwardCallableCatalogEntry> = members.map { member -> entryFor(member, 0) }
      addAll(declared)
      // ADR-096: omitting overloads, appended after the declared pass of this per-companion counter.
      members.forEachIndexed { index, member ->
        if (declared[index] !is ForwardCallableCatalogEntry.Planned) return@forEachIndexed
        repeat(member.parameters.map { it.hasDefault }.trailingCount()) { omitted ->
          add(entryFor(member, omitted + 1).synthesized())
        }
      }
    }
  }

  private fun staticEntry(
    function: KSFunctionDeclaration,
    symbol: String,
    publicName: String,
    exportName: String,
    origin: ForwardCallableOrigin,
    target: String?,
    // ADR-095: the bare declared name for the Kotlin call site, since the symbol may carry `_$n`.
    member: String? = null,
    // ADR-096: how many trailing defaulted parameters this omitting overload drops (0 = declared).
    omitted: Int = 0,
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
    val parameters: List<Pair<String, BridgeType>> = function.parameters.dropLast(omitted)
      .map { parameter ->
        (parameter.name?.asString() ?: "_") to classifier.classify(parameter.type.resolve())
      }
    // ADR-002 / MIGRATION: top-level nullable primitives keep the shipped two-call ABI.
    // ADR-076: a top-level nullable Instant shares the same two-call shape (ADR-069 recorded that
    // this path crashes packNuget for a shape it does not handle, rather than skipping).
    // ADR-079: a top-level `Dosage?` / `Temperament?` return shares the same two-call shape, with
    // the `_value` call returning the underlying's own wire.
    if (origin == ForwardCallableOrigin.TOP_LEVEL &&
      result is BridgeType.Nullable &&
      (result.type is BridgeType.Primitive || result.type == BridgeType.Instant ||
          result.type is BridgeType.Enum ||
          (result.type as? BridgeType.ValueClass)?.underlying?.isHasValueFanOutUnderlying() == true)
    ) {
      return topLevelNullablePrimitivePlan(
        symbol = symbol,
        publicName = publicName,
        exportName = exportName,
        parameters = parameters,
        result = result,
        member = member,
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
      member = member,
      node = function,
    )
  }

  /**
   * Plans a top-level `fun f(...): Primitive?` (or, per ADR-076, `Instant?`) as
   * [ForwardEvaluation.LEGACY_TWO_CALL]: `${export}_has_value` (BOOLEAN) + `${export}_value`
   * (primitive/ticks wire), matching ADR-002. Method/extension nullable primitives stay on the
   * ADR-061 single-call `valueOut` shape.
   */
  private fun topLevelNullablePrimitivePlan(
    symbol: String,
    publicName: String,
    exportName: String,
    parameters: List<Pair<String, BridgeType>>,
    result: BridgeType.Nullable,
    // ADR-095: the two-call route numbers like every other, so its plan carries the bare name too.
    member: String? = null,
    node: KSNode? = null,
  ): ForwardCallableCatalogEntry {
    val inner: BridgeType = result.type
    require(
      inner is BridgeType.Primitive || inner == BridgeType.Instant ||
          inner is BridgeType.Enum ||
          (inner as? BridgeType.ValueClass)?.underlying?.isHasValueFanOutUnderlying() == true
    ) {
      "Forward planner topLevelNullablePrimitivePlan received unsupported inner type $inner"
    }
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
    val valueWireType: ForwardAbiWireType = when (inner) {
      is BridgeType.Primitive -> inner.wireType()
      // ADR-079: the `_value` call returns the underlying's wire (the primitive's own, INT32 for
      // an enum ordinal); the box step composes in the emitted expressions at both ends.
      is BridgeType.ValueClass -> inner.underlying.underlyingWireType()
      // ADR-080: a bare nullable enum returns its `int` ordinal on the same `_value` call.
      is BridgeType.Enum -> ForwardAbiWireType.INT32
      else -> ForwardAbiWireType.INT64
    }
    val value = ForwardNativeCall(
      exportName = "${exportName}_value",
      result = valueWireType,
      parameters = nativeInputs + error,
    )
    val helpers: Set<ForwardHelperRequirement> = buildSet {
      add(ForwardHelperRequirement.STABLE_REF)
      // ADR-077: same value-class input helper as `planOrSkip`, so a top-level
      // `fun f(id: ChartId): Int?` on this two-call route validates too.
      parameters
        .mapNotNull { (_, type) -> (type.unwrapNullable() as? BridgeType.ValueClass)?.underlying }
        .forEach { underlying ->
          add(ForwardHelperRequirement.VALUE_CLASS)
          if (underlying == BridgeType.String) add(ForwardHelperRequirement.UTF8)
          if (underlying is BridgeType.Enum) add(ForwardHelperRequirement.ENUM_ORDINAL)
        }
      if (parameters.any { (_, type) -> type.unwrapNullable() == BridgeType.String }) {
        add(ForwardHelperRequirement.UTF8)
      }
      if (parameters.any { (_, type) -> type.unwrapNullable() is BridgeType.Enum }) {
        add(ForwardHelperRequirement.ENUM_ORDINAL)
      }
      if (parameters.any { (_, type) -> type.unwrapNullable() is BridgeType.Collection }) {
        add(ForwardHelperRequirement.COLLECTION)
      }
      val resultIsInstant: Boolean = inner == BridgeType.Instant
      val hasInstantParameter: Boolean =
        parameters.any { (_, type) -> type.unwrapNullable() == BridgeType.Instant }
      if (resultIsInstant || hasInstantParameter) {
        add(ForwardHelperRequirement.INSTANT)
      }
      // ADR-079: the value-class *result* on this two-call route carries its own helpers, the
      // same way `nullableResultShape`'s new branch does for the single-call route.
      if (inner is BridgeType.ValueClass) {
        add(ForwardHelperRequirement.VALUE_CLASS)
        if (inner.underlying is BridgeType.Enum) add(ForwardHelperRequirement.ENUM_ORDINAL)
      }
      // ADR-080: a bare nullable enum result needs the ordinal helper too.
      if (inner is BridgeType.Enum) add(ForwardHelperRequirement.ENUM_ORDINAL)
    }
    // ADR-076: the generic `transfer()` helper only special-cases String; an Instant result needs
    // its own INSTANT_TO_TICKS conversion tagged explicitly, or plan validation rejects it.
    // ADR-079: likewise a value-class result must carry UNBOX_VALUE_CLASS explicitly.
    val resultConversion: ForwardConversion? = when {
      inner == BridgeType.Instant -> ForwardConversion.INSTANT_TO_TICKS
      inner is BridgeType.ValueClass -> ForwardConversion.UNBOX_VALUE_CLASS
      // ADR-080: the ordinal lowering is explicit for the same reason.
      inner is BridgeType.Enum -> ForwardConversion.ENUM_TO_ORDINAL
      else -> null
    }
    val resultTransfer: ForwardTransfer = if (resultConversion != null) {
      ForwardTransfer(
        subject = "result",
        type = result,
        flow = ForwardFlow.OUT_OF_KOTLIN,
        passing = ForwardPassing.VALUE,
        ownership = ForwardOwnership.BORROWED,
        conversion = resultConversion,
      )
    } else {
      transfer("result", result, ForwardFlow.OUT_OF_KOTLIN)
    }
    val plan = ForwardCallablePlan(
      invocation = ForwardInvocation(
        symbol = symbol,
        origin = ForwardCallableOrigin.TOP_LEVEL,
        target = null,
        member = member,
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
        transfer = resultTransfer,
      ),
      errorSlot = error,
      helperRequirements = helpers,
    ).validate()
    return ForwardCallableCatalogEntry.Planned(plan, node = node)
  }

  private fun extensionEntry(
    function: KSFunctionDeclaration,
    suffix: String,
    // ADR-096: how many trailing defaulted parameters this omitting overload drops (0 = declared).
    // The receiver is a `ForwardReceiver.Value`, not a plan parameter, so truncation never
    // reaches it: an extension whose parameters are all defaulted still has its receiver.
    omitted: Int = 0,
  ): ForwardCallableCatalogEntry {
    val receiver: KSType = requireNotNull(function.extensionReceiver) {
      "Forward extension planner received a non-extension function ${function.simpleName.asString()}"
    }.resolve()
    val receiverType: BridgeType = classifier.classify(receiver)
    val functionName: String = function.simpleName.asString()
    val symbol: String = "${function.packageName.asString()}.$functionName$suffix"

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
      exportName =
        "${receiver.declaration.simpleName.asString().lowercase()}_${toCName(functionName)}$suffix",
      receiver = ForwardReceiver.Value(receiverType),
      parameters = function.parameters.dropLast(omitted).map { parameter ->
        (parameter.name?.asString() ?: "_") to classifier.classify(parameter.type.resolve())
      },
      result = function.returnType?.resolve()?.let(classifier::classify) ?: BridgeType.Unit,
      origin = ForwardCallableOrigin.EXTENSION,
      member = functionName,
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
    member: String? = null,
    isOverride: Boolean = false,
    isVirtual: Boolean = false,
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
      // ADR-077: a value-class *input* also needs the helper, otherwise the validator's
      // `requiredConversion.helper() in helperRequirements` check rejects its BOX_VALUE_CLASS
      // transfer. The underlying carries its own helper in (UTF-8 / enum ordinal per kind).
      inputTypes
        .mapNotNull { type -> (type.unwrapNullable() as? BridgeType.ValueClass)?.underlying }
        .forEach { underlying ->
          add(ForwardHelperRequirement.VALUE_CLASS)
          if (underlying == BridgeType.String) add(ForwardHelperRequirement.UTF8)
          if (underlying is BridgeType.Enum) add(ForwardHelperRequirement.ENUM_ORDINAL)
        }
      if (inputTypes.any { type -> type.unwrapNullable() == BridgeType.String }) {
        add(ForwardHelperRequirement.UTF8)
      }
      if (inputTypes.any { type -> type.unwrapNullable() is BridgeType.Enum }) {
        add(ForwardHelperRequirement.ENUM_ORDINAL)
      }
      if (inputTypes.any { type -> type.unwrapNullable() is BridgeType.Collection }) {
        add(ForwardHelperRequirement.COLLECTION)
      }
      if (inputTypes.any { type -> type.unwrapNullable() == BridgeType.Instant }) {
        add(ForwardHelperRequirement.INSTANT)
      }
      // ADR-088: the reverse pipeline already generated these helpers into the same compilation;
      // the requirement is recorded only so the validator's conversion/helper pairing check holds.
      if (inputTypes.any { type -> type.unwrapNullable() is BridgeType.BoundInterface }) {
        add(ForwardHelperRequirement.BOUND_INTERFACE)
      }
    }
    val plan = ForwardCallablePlan(
      invocation = ForwardInvocation(
        symbol = symbol,
        receiver = invocationReceiver,
        origin = origin,
        target = if (valueClassProperty) "$target#property" else target,
        member = member,
      ),
      publicSignature = ForwardPublicSignature(
        name = publicName,
        parameters = parameters.map { (name, type) -> ForwardPublicParameter(name, type) },
        result = result,
        isOverride = isOverride,
        isVirtual = isVirtual,
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
    return ForwardCallableCatalogEntry.Planned(plan, node = node)
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

    // ADR-076: the wire value is a raw INT64 of ticks; the Kotlin export converts it back to an
    // Instant via the TICKS_TO_INSTANT helper before use.
    BridgeType.Instant -> listOf(
      ForwardAbiParameter(
        name = name,
        wireType = ForwardAbiWireType.INT64,
        direction = ForwardAbiDirection.IN,
        transfer = ForwardTransfer(
          name, type, ForwardFlow.INTO_KOTLIN, ForwardPassing.VALUE,
          ForwardOwnership.BORROWED, ForwardConversion.TICKS_TO_INSTANT,
        ),
      )
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

    // ADR-088: same POINTER/IN wire as the two above, but the pointer is a transfer GCHandle the
    // C# wrapper allocated. Kotlin RECEIVES ownership (`nuget{Iface}Value` either frees it on a
    // token-probe hit or hands it to the wrapper's cleaner), which is why the transfer is not
    // BORROWED: nothing on the C# side frees it after the call.
    is BridgeType.BoundInterface -> listOf(
      ForwardAbiParameter(
        name = name,
        wireType = ForwardAbiWireType.POINTER,
        direction = ForwardAbiDirection.IN,
        transfer = ForwardTransfer(
          name, type, ForwardFlow.INTO_KOTLIN, ForwardPassing.VALUE,
          ForwardOwnership.MATERIALIZED, ForwardConversion.GC_HANDLE_TO_BOUND_VALUE,
        ),
      )
    )

    // ADR-077: one native parameter carrying the underlying's wire value (String/primitive
    // directly, enum as its int ordinal, ObjectHandle as a StableRef pointer). The transfer keeps
    // the *value class* as its type and tags BOX_VALUE_CLASS, which is what
    // `ForwardCallablePlanValidator.requiredConversion` demands; the underlying's own step
    // composes inside the emitted expression rather than stacking a conversion.
    is BridgeType.ValueClass -> listOf(
      ForwardAbiParameter(
        name = name,
        wireType = type.underlying.underlyingWireType(),
        direction = ForwardAbiDirection.IN,
        transfer = ForwardTransfer(
          name, type, ForwardFlow.INTO_KOTLIN, ForwardPassing.VALUE,
          ForwardOwnership.BORROWED, ForwardConversion.BOX_VALUE_CLASS,
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

      // ADR-075: the wire value's own nullability (`IntPtr.Zero` for a null collection reference)
      // is completely independent of the collection's element eligibility, already checked by
      // `inputSkipReason()` before this ever runs — same POINTER / HANDLE_TO_COLLECTION shape as
      // the non-null case three cases above, the C# side's `CreateList(...) : IntPtr.Zero`
      // ternary is the only difference (ForwardCirPlanProjection.collectionPrelude).
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

      // ADR-077 sub-items 3/4: one pointer-shaped parameter like the non-null value-class input
      // above (STRING wire for a String underlying, POINTER for an ObjectHandle one); the
      // transfer records the *nullable* type so both emitters lower with null propagation.
      // ADR-079: a Primitive/Enum underlying has no in-band null on its wire, so it fans out to
      // the same adjacent HasValue pair the nullable-primitive and nullable-Instant cases below
      // use, with the value slot carrying the (non-null) value class + BOX_VALUE_CLASS -- exactly
      // how Instant's pair carries Instant + TICKS_TO_INSTANT.
      is BridgeType.ValueClass -> if (inner.underlying.isHasValueFanOutUnderlying()) {
        listOf(
          ForwardAbiParameter(
            name = "${name}HasValue",
            wireType = ForwardAbiWireType.BOOLEAN,
            direction = ForwardAbiDirection.IN,
            transfer = ForwardTransfer(
              "${name}HasValue", BridgeType.Primitive(PrimitiveKind.BOOLEAN),
              ForwardFlow.INTO_KOTLIN, ForwardPassing.VALUE, ForwardOwnership.BORROWED,
              ForwardConversion.DIRECT,
            ),
          ),
          ForwardAbiParameter(
            name = name,
            wireType = inner.underlying.underlyingWireType(),
            direction = ForwardAbiDirection.IN,
            transfer = ForwardTransfer(
              name, inner, ForwardFlow.INTO_KOTLIN, ForwardPassing.VALUE,
              ForwardOwnership.BORROWED, ForwardConversion.BOX_VALUE_CLASS,
            ),
          ),
        )
      } else {
        listOf(
          ForwardAbiParameter(
            name = name,
            wireType = inner.underlying.underlyingWireType(),
            direction = ForwardAbiDirection.IN,
            transfer = ForwardTransfer(
              name, type, ForwardFlow.INTO_KOTLIN, ForwardPassing.VALUE,
              ForwardOwnership.BORROWED, ForwardConversion.BOX_VALUE_CLASS,
            ),
          )
        )
      }

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

      // ADR-080: same adjacent-pair shape, the value slot carrying the `int` ordinal with the
      // ORDINAL_TO_ENUM conversion (ADR-079's enum-underlying value class minus the box).
      is BridgeType.Enum -> listOf(
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
          wireType = ForwardAbiWireType.INT32,
          direction = ForwardAbiDirection.IN,
          transfer = ForwardTransfer(
            name, inner, ForwardFlow.INTO_KOTLIN, ForwardPassing.VALUE,
            ForwardOwnership.BORROWED, ForwardConversion.ORDINAL_TO_ENUM,
          ),
        ),
      )

      // ADR-076 §4.1: same adjacent-pair shape as the nullable-primitive case above, except the
      // value slot carries the TICKS_TO_INSTANT conversion (the wire value is still a raw INT64).
      BridgeType.Instant -> listOf(
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
          wireType = ForwardAbiWireType.INT64,
          direction = ForwardAbiDirection.IN,
          transfer = ForwardTransfer(
            name, inner, ForwardFlow.INTO_KOTLIN, ForwardPassing.VALUE,
            ForwardOwnership.BORROWED, ForwardConversion.TICKS_TO_INSTANT,
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

    // ADR-076: same shape as Enum above -- a semantic result with a required conversion, wired
    // as its own primitive-like representation (INT64 ticks).
    BridgeType.Instant -> ForwardResultShape(
      wireType = ForwardAbiWireType.INT64,
      transfer = ForwardTransfer(
        subject = "result",
        type = this,
        flow = ForwardFlow.OUT_OF_KOTLIN,
        passing = ForwardPassing.VALUE,
        ownership = ForwardOwnership.BORROWED,
        conversion = ForwardConversion.INSTANT_TO_TICKS,
      ),
      helperRequirements = setOf(ForwardHelperRequirement.INSTANT),
    )

    is BridgeType.ObjectHandle, is BridgeType.Interface -> handleResultShape(this)
    // ADR-088: gated on the manifest's Kotlin-implementability flag. Without a
    // `mint{Iface}Bridge`, a plain Kotlin implementation returned here has nothing to become on
    // the C# side, and v1 refuses to emit a route that works for one origin and traps for the
    // other (the skip is named UNIMPLEMENTABLE_BOUND_INTERFACE).
    is BridgeType.BoundInterface -> if (implementable) boundInterfaceResultShape(this) else null
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
    // ADR-077 sub-items 3/4: reuses the corresponding nullable pointer shape verbatim (null rides
    // the null pointer; a value class's underlying is non-nullable by construction, so there is
    // no third state), with only the transfer's type and conversion tag changed. Pointer-shaped
    // underlyings only (String, ObjectHandle); nullable x primitive/enum stays deferred.
    is BridgeType.ValueClass -> when (type.underlying) {
      BridgeType.String -> ForwardResultShape(
        wireType = ForwardAbiWireType.POINTER,
        transfer = ForwardTransfer(
          subject = "result",
          type = BridgeType.Nullable(type),
          flow = ForwardFlow.OUT_OF_KOTLIN,
          passing = ForwardPassing.VALUE,
          ownership = ForwardOwnership.MATERIALIZED,
          conversion = ForwardConversion.UNBOX_VALUE_CLASS,
        ),
        helperRequirements = setOf(
          ForwardHelperRequirement.UTF8,
          ForwardHelperRequirement.VALUE_CLASS,
        ),
      )

      is BridgeType.ObjectHandle -> handleResultShape(BridgeType.Nullable(type)).let { shape ->
        shape.copy(
          transfer = shape.transfer.copy(conversion = ForwardConversion.UNBOX_VALUE_CLASS),
          helperRequirements = shape.helperRequirements + ForwardHelperRequirement.VALUE_CLASS,
        )
      }

      // ADR-079: the ADR-061 single-call shape (BOOLEAN has-value result + `valueOut` OUT
      // pointer), same as the nullable-primitive and nullable-Instant cases below. The outer
      // transfer carries the Nullable value class + UNBOX_VALUE_CLASS; `valueOut` carries the
      // *bare* underlying primitive (INT for an enum ordinal) so it renders as `double`/`int` on
      // the C# side and inherits ADR-069's [MarshalAs(UnmanagedType.I1)] for a Boolean underlying.
      is BridgeType.Primitive, is BridgeType.Enum -> ForwardResultShape(
        wireType = ForwardAbiWireType.BOOLEAN,
        transfer = ForwardTransfer(
          subject = "result",
          type = BridgeType.Nullable(type),
          flow = ForwardFlow.OUT_OF_KOTLIN,
          passing = ForwardPassing.VALUE,
          ownership = ForwardOwnership.BORROWED,
          conversion = ForwardConversion.UNBOX_VALUE_CLASS,
        ),
        extraParameters = listOf(
          ForwardAbiParameter(
            name = "valueOut",
            wireType = ForwardAbiWireType.POINTER,
            direction = ForwardAbiDirection.OUT,
            transfer = ForwardTransfer(
              subject = "valueOut",
              type = type.underlying.valueOutTransferType(),
              flow = ForwardFlow.OUT_OF_KOTLIN,
              passing = ForwardPassing.OUT,
              ownership = ForwardOwnership.BORROWED,
              conversion = ForwardConversion.DIRECT,
            ),
          )
        ),
        helperRequirements = buildSet {
          add(ForwardHelperRequirement.VALUE_CLASS)
          if (type.underlying is BridgeType.Enum) add(ForwardHelperRequirement.ENUM_ORDINAL)
        },
      )

      else -> null
    }

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

    // ADR-076 §4.2: same BOOLEAN-result + valueOut OUT-pointer shape as the nullable-primitive
    // case above -- the whole nullable story reduces to the already-shipped nullable-primitive
    // INT64 machinery. The outer "result" transfer carries the semantic INSTANT_TO_TICKS
    // conversion; valueOut itself carries the already-converted raw ticks (a plain Long), so its
    // own transfer stays DIRECT and the DllImport/local-variable declarations it drives (which
    // read `transfer.type.csharpType()`) render "long", not "DateTimeOffset".
    BridgeType.Instant -> ForwardResultShape(
      wireType = ForwardAbiWireType.BOOLEAN,
      transfer = ForwardTransfer(
        subject = "result",
        type = BridgeType.Nullable(type),
        flow = ForwardFlow.OUT_OF_KOTLIN,
        passing = ForwardPassing.VALUE,
        ownership = ForwardOwnership.BORROWED,
        conversion = ForwardConversion.INSTANT_TO_TICKS,
      ),
      extraParameters = listOf(
        ForwardAbiParameter(
          name = "valueOut",
          wireType = ForwardAbiWireType.POINTER,
          direction = ForwardAbiDirection.OUT,
          transfer = ForwardTransfer(
            subject = "valueOut",
            type = BridgeType.Primitive(PrimitiveKind.LONG),
            flow = ForwardFlow.OUT_OF_KOTLIN,
            passing = ForwardPassing.OUT,
            ownership = ForwardOwnership.BORROWED,
            conversion = ForwardConversion.DIRECT,
          ),
        )
      ),
      helperRequirements = setOf(ForwardHelperRequirement.INSTANT),
    )

    // ADR-080: a bare nullable enum is ADR-079's value-class-over-enum shape with the box step
    // deleted -- BOOLEAN has-value result plus a `valueOut` carrying the plain `int` ordinal.
    is BridgeType.Enum -> ForwardResultShape(
      wireType = ForwardAbiWireType.BOOLEAN,
      transfer = ForwardTransfer(
        subject = "result",
        type = BridgeType.Nullable(type),
        flow = ForwardFlow.OUT_OF_KOTLIN,
        passing = ForwardPassing.VALUE,
        ownership = ForwardOwnership.BORROWED,
        conversion = ForwardConversion.ENUM_TO_ORDINAL,
      ),
      extraParameters = listOf(
        ForwardAbiParameter(
          name = "valueOut",
          wireType = ForwardAbiWireType.POINTER,
          direction = ForwardAbiDirection.OUT,
          transfer = ForwardTransfer(
            subject = "valueOut",
            type = type.valueOutTransferType(),
            flow = ForwardFlow.OUT_OF_KOTLIN,
            passing = ForwardPassing.OUT,
            ownership = ForwardOwnership.BORROWED,
            conversion = ForwardConversion.DIRECT,
          ),
        )
      ),
      helperRequirements = setOf(ForwardHelperRequirement.ENUM_ORDINAL),
    )

    else -> null
  }

  /**
   * ADR-088: a bound C# interface returned OUT of Kotlin. Wire-shaped like [handleResultShape] (a
   * POINTER), but deliberately NOT it: the pointer is a GCHandle, not a StableRef, so the
   * ownership and cleanup are the other way round. C# owns the fresh transfer handle and frees it
   * the moment it resolves `.Target`, which is why this is MATERIALIZED with no Kotlin-side
   * cleanup rather than OWNED_HANDLE + DISPOSE_STABLE_REF.
   */
  private fun boundInterfaceResultShape(type: BridgeType.BoundInterface): ForwardResultShape =
    ForwardResultShape(
      wireType = ForwardAbiWireType.POINTER,
      transfer = ForwardTransfer(
        subject = "result",
        type = type,
        flow = ForwardFlow.OUT_OF_KOTLIN,
        passing = ForwardPassing.VALUE,
        ownership = ForwardOwnership.MATERIALIZED,
        conversion = ForwardConversion.BOUND_VALUE_TO_GC_HANDLE,
      ),
      helperRequirements = setOf(ForwardHelperRequirement.BOUND_INTERFACE),
    )

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
    // ADR-077 sub-item 4: the underlying's own shape verbatim (wire, ownership, cleanup: an
    // ObjectHandle underlying keeps OWNED_HANDLE + DISPOSE_STABLE_REF from handleResultShape),
    // with only the transfer re-typed to the value class and re-tagged UNBOX_VALUE_CLASS. The
    // underlying's own step (ordinal, StableRef, UTF-8) composes inside the emitted expressions.
    if (!type.underlying.isOrdinaryValueClassUnderlying()) return null
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

  /**
   * ADR-077 sub-item 4: the underlyings a value class may carry across an ordinary position.
   * Everything else (nested value classes, collections, nullables) keeps the VALUE_CLASS skip.
   */
  private fun BridgeType.isOrdinaryValueClassUnderlying(): Boolean =
    this == BridgeType.String || this is BridgeType.Primitive ||
        this is BridgeType.Enum || this is BridgeType.ObjectHandle

  /**
   * ADR-079: the value-class underlyings whose wire has no in-band null, so a
   * `Nullable(ValueClass)` over them needs the out-of-band has-value channel (an adjacent BOOLEAN
   * parameter at an input position, a BOOLEAN result + `valueOut` at a return one). String and
   * ObjectHandle underlyings ride their own null pointer instead (ADR-077 sub-items 3/4).
   */
  private fun BridgeType.isHasValueFanOutUnderlying(): Boolean =
    this is BridgeType.Primitive || this is BridgeType.Enum

  /**
   * ADR-079: the type an ADR-061 `valueOut` slot carries for a has-value fan-out value class. It is
   * the *bare* underlying primitive (an enum's ordinal is a plain INT), never the value class
   * itself, so the slot renders as `double`/`int` in the DllImport and inherits ADR-069's
   * `[MarshalAs(UnmanagedType.I1)]` for a Boolean underlying. Mirrors Instant's `Primitive(LONG)`
   * valueOut.
   */
  private fun BridgeType.valueOutTransferType(): BridgeType =
    if (this is BridgeType.Enum) BridgeType.Primitive(PrimitiveKind.INT) else this

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

  private fun BridgeType.skipReason(): ForwardPlanSkipReason? = when (this) {
    BridgeType.Unit, is BridgeType.Primitive -> null
    BridgeType.Char -> ForwardPlanSkipReason.CHAR
    BridgeType.String -> ForwardPlanSkipReason.STRING
    // ADR-076: defensive only -- shapeOrNull's Instant branch always succeeds, same as CHAR/
    // STRING above.
    BridgeType.Instant -> ForwardPlanSkipReason.INSTANT
    // ADR-088: same deferred nullable position as the input side, named the same way instead of
    // reaching the generic NULLABLE bucket.
    is BridgeType.Nullable ->
      if (type is BridgeType.BoundInterface) ForwardPlanSkipReason.BOUND_INTERFACE_POSITION
      else ForwardPlanSkipReason.NULLABLE
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
    // ADR-088: shapeOrNull's BoundInterface branch succeeds only for a manifest-flagged
    // Kotlin-implementable interface, so reaching here at a return position means exactly the
    // "no mint{Iface}Bridge" case. A collection element reaches here too, and takes the position
    // skip instead (collections of bound interfaces are deferred).
    is BridgeType.BoundInterface ->
      if (implementable) ForwardPlanSkipReason.BOUND_INTERFACE_POSITION
      else ForwardPlanSkipReason.UNIMPLEMENTABLE_BOUND_INTERFACE

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
    BridgeType.String, BridgeType.Char, BridgeType.Instant -> null
    is BridgeType.Enum -> null
    // ADR-040 sub-decision B: an interface-typed parameter is plannable — the C# lowering routes
    // through NugetMarshal.HandleOf (ForwardCirPlanProjection.callArgument), which throws
    // NotSupportedException at runtime for a C#-implemented (non-Kotlin-backed) IFoo.
    is BridgeType.ObjectHandle, is BridgeType.Interface -> null
    // ADR-088: admissible at a parameter position regardless of `implementable` — the incoming
    // GCHandle only needs `nuget{Iface}Value`, which every manifest-listed interface has. Only a
    // RETURN of a plain Kotlin implementation needs the mint.
    is BridgeType.BoundInterface -> null
    // ADR-066: an unsupported element must not silently produce a Collection shape that later
    // crashes plan validation — route it through the same skip path as any other unsupported
    // input, preferring the (element ?: key ?: value)'s own reason (e.g.
    // UNEXPORTED_DEPENDENCY_TYPE) when known.
    is BridgeType.Collection -> collectionInputSkipReason()

    // ADR-077: a value class crosses as its underlying wire value, so an ordinary parameter is
    // plannable exactly when that underlying is (String/primitive/enum/ObjectHandle, sub-item 4).
    is BridgeType.ValueClass ->
      if (underlying.isOrdinaryValueClassUnderlying()) null else ForwardPlanSkipReason.VALUE_CLASS

    // ADR-075: a nullable collection input (e.g. a data class's `notes: List<String>?` primary
    // constructor parameter, mirroring `Visit.notes` as a *property*) shares exactly the same
    // per-kind eligibility as a non-null collection input — the collection reference's own
    // nullability is orthogonal to its components' marshallability, same as the property setter
    // side of this same ADR.
    is BridgeType.Nullable -> when (val inner = type) {
      BridgeType.String, is BridgeType.ObjectHandle, is BridgeType.Primitive,
      BridgeType.Instant -> null

      // ADR-080: a bare nullable enum fans out to the has-value pair with the ordinal in the
      // value slot, exactly like ADR-079's enum-underlying value class minus the box.
      is BridgeType.Enum -> null

      is BridgeType.Collection -> inner.collectionInputSkipReason()
      // ADR-077 sub-items 3/4: null rides the null pointer for the pointer-wired underlyings
      // (String, ObjectHandle). ADR-079: a Primitive/Enum underlying has no in-band null, so it
      // fans out to the has-value pair instead; either way the nullable spelling is plannable
      // exactly when the underlying is (`isOrdinaryValueClassUnderlying`, the non-null rule).
      is BridgeType.ValueClass ->
        if (inner.underlying.isOrdinaryValueClassUnderlying()) null
        else ForwardPlanSkipReason.VALUE_CLASS

      // ADR-088: `IFeedable?` is on this ADR's deferred list. The null-pointer ride is natural,
      // but it needs its own lowering in four emitter positions; until then the skip names the
      // position rather than falling through to the generic NULLABLE bucket below, whose
      // diagnostic kind is SKIPPED_UNSUPPORTED_RETURN and whose hint talks about Booleans.
      is BridgeType.BoundInterface -> ForwardPlanSkipReason.BOUND_INTERFACE_POSITION

      else -> ForwardPlanSkipReason.NULLABLE
    }

    else -> skipReason()
  }

  private fun BridgeType.Collection.collectionInputSkipReason(): ForwardPlanSkipReason? = when {
    !isBridgeableComponent() ->
      (element ?: key ?: value)?.skipReason() ?: ForwardPlanSkipReason.UNSUPPORTED

    // ADR-073: map/set inputs are admitted only for components the write side can box
    // (isWrappableComponent). ADR-083: the *key* additionally has to be non-nullable -- a C#
    // Dictionary cannot hold a null key, so a nullable-key map has no idiomatic projection and
    // skips named, even though its value slot would be fine.
    kind == CollectionKind.MAP || kind == CollectionKind.MUTABLE_MAP -> {
      val keyAdmitted: Boolean =
        key?.let { it !is BridgeType.Nullable && it.isWrappableComponent() } == true
      val valueAdmitted: Boolean = value?.isWrappableComponent() == true
      if (keyAdmitted && valueAdmitted) null else ForwardPlanSkipReason.COLLECTION
    }

    else ->
      if (element?.isWrappableComponent() == true) null else ForwardPlanSkipReason.COLLECTION
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
    // ADR-076: like Enum, Instant always needs an explicit conversion tag (INSTANT_TO_TICKS/
    // TICKS_TO_INSTANT) at its own call site rather than this untagged pass-through -- every
    // caller builds its own ForwardTransfer for it instead of reaching this generic helper.
    BridgeType.Instant,
    is BridgeType.Nullable,
    is BridgeType.Collection,
    is BridgeType.RawCollection,
    is BridgeType.Enum,
    is BridgeType.ObjectHandle,
    is BridgeType.Interface,
      // ADR-088: like ObjectHandle/Interface, a bound interface always builds its own tagged
      // ForwardTransfer at its call site rather than reaching this untagged pass-through.
    is BridgeType.BoundInterface,
    is BridgeType.ValueClass,
    is BridgeType.SpecializedProtocol,
    is BridgeType.RawKSType,
    is BridgeType.Unsupported,
      -> error("Forward planner requested a wire type for ineligible $this")
  }

  private fun BridgeType.unwrapNullable(): BridgeType = if (this is BridgeType.Nullable) type else this

  /**
   * ADR-077 sub-item 4: the wire a value-class *underlying* rides. Unlike [wireType], which
   * rejects Enum/ObjectHandle at a declared position (they have their own transfer machinery
   * there), an underlying crosses as its ordinal / StableRef pointer inside a single-slot
   * value-class transfer.
   */
  private fun BridgeType.underlyingWireType(): ForwardAbiWireType = when (this) {
    is BridgeType.Enum -> ForwardAbiWireType.INT32
    is BridgeType.ObjectHandle -> ForwardAbiWireType.POINTER
    else -> wireType()
  }

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

/**
 * ADR-066: a collection whose element (or map key/value) type is itself unsupported must skip
 * the whole callable through the normal named-diagnostic path, not reach the plan validator —
 * `handleResultShape`/`inputSkipReason` used to build a Collection shape unconditionally, so an
 * unsupported element only surfaced as a hard `IllegalStateException` out of
 * `ForwardCallablePlanValidator.validateType`, crashing the entire `packNuget` rather than
 * skipping the one member. Mirrors [ForwardCallablePlanValidator.validateType]'s error branches
 * exactly, so anything that would `error(...)` there returns `false` here instead.
 *
 * ADR-075: lifted from a `ForwardCallablePlanner` private member to file-level `internal` — the
 * body touches no planner state — so [ForwardPropertyPlanner] can reuse it unchanged for a
 * collection property's setter eligibility.
 */
internal fun BridgeType.isBridgeableComponent(): Boolean = when (this) {
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
  // ADR-076: "Instant as a collection element" is explicitly deferred (its own boxing question,
  // ADR-073/075's isWrappableComponent allow-list territory) -- same route.
  // ADR-088: "bound interfaces as collection components" is on this ADR's own deferred list, for
  // the same reason -- the wrap/box helpers have no route for a GCHandle element.
  is BridgeType.Interface, is BridgeType.BoundInterface, BridgeType.Instant,
  is BridgeType.RawCollection, is BridgeType.RawKSType, is BridgeType.SpecializedProtocol,
  is BridgeType.Unsupported,
    -> false
}

/**
 * ADR-073: the component types the C# write side can actually box, for an input-position
 * `Map`/`Set` (and their mutable variants): the six `nuget_wrap_*` primitives plus an object
 * handle (via `CreateMap`/`CreateSet`'s reflective `_handle` fallback), plus (ADR-081) a value
 * class over any of those underlyings, projected to the underlying per element, plus (ADR-097) a
 * bare `Enum`, which rides that same per-element projection as its int ordinal, plus (ADR-098) the
 * six narrow primitive kinds and `Char`, each with a `nuget_wrap_*` export of its own. Still
 * narrower than [isBridgeableComponent], which also admits nested `Collection` and `Unit` (neither
 * of which the write side can box), because those overshoots would otherwise either crash
 * `packNuget` (nested `Collection`, no `elementKotlinTypeName` branch) or throw at runtime
 * (`NotSupportedException`, no matching `nuget_wrap_*`).
 *
 * ADR-097: this is now the gate for *every* input position, `List` included. ADR-075 already
 * reused it for a collection *property setter*.
 *
 * ADR-075: lifted from a `ForwardCallablePlanner` private member to file-level `internal` — the
 * body touches no planner state.
 */
internal fun BridgeType.isWrappableComponent(): Boolean = when (this) {
  BridgeType.String -> true
  // ADR-098: every PrimitiveKind is wrappable now that the six narrow kinds have a
  // `nuget_wrap_*` export each. Kept as an explicit set rather than `true` so a future kind has
  // to be admitted deliberately, with its export minted alongside.
  is BridgeType.Primitive -> kind in setOf(
    PrimitiveKind.BYTE, PrimitiveKind.UBYTE, PrimitiveKind.SHORT, PrimitiveKind.USHORT,
    PrimitiveKind.INT, PrimitiveKind.UINT, PrimitiveKind.LONG, PrimitiveKind.ULONG,
    PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE, PrimitiveKind.BOOLEAN,
  )

  // ADR-098 part B: `Char` is its own BridgeType, not a PrimitiveKind, so it needs its own arm.
  // It crosses as the UTF-16 code unit Kotlin already emits (`KChar` = `unsigned short`), with
  // the C# side pinned to that width by `[MarshalAs(UnmanagedType.U2)]`.
  BridgeType.Char -> true

  is BridgeType.ObjectHandle -> true

  // ADR-097: a *bare* enum component rides the same int-ordinal wire ADR-081 minted for a value
  // class over an enum, projected per element at the C# call site (`(int)x`) and re-wrapped as
  // `Mood.entries[it as Int]` on the Kotlin side, so `Wrap<T>` is only ever instantiated at
  // `T = int`. One branch here admits it at every position the predicate guards: `List`/`Set`/`Map`
  // callable inputs and collection property setters.
  is BridgeType.Enum -> true

  // ADR-081: a value-class component crosses as its *underlying*, projected per element at the C#
  // call site (`x.Value`, `(int)x.Mood`, `x.Patient`) before `Wrap<T>` is ever instantiated, so the
  // write side only ever boxes a type it already handles.
  is BridgeType.ValueClass ->
    underlying is BridgeType.Enum || underlying.isWrappableComponent()

  // ADR-083: a component slot is already pointer-shaped (every element crosses as a boxed
  // StableRef handle), so the null pointer is an in-band null for every wrappable component kind
  // -- including Int?, which at an *ordinary* position needs the ADR-079 has-value pair. Nesting
  // is excluded, matching isBridgeableComponent's own no-nested-nullable rule.
  is BridgeType.Nullable -> type !is BridgeType.Nullable && type.isWrappableComponent()

  else -> false
}
