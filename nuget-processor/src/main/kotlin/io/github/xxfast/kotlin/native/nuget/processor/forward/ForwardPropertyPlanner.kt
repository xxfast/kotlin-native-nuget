package io.github.xxfast.kotlin.native.nuget.processor.forward

import io.github.xxfast.kotlin.native.nuget.processor.ForwardSymbolTable
import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Visibility
import io.github.xxfast.kotlin.native.nuget.processor.ExpectIndex
import io.github.xxfast.kotlin.native.nuget.processor.cir.expandAliases
import io.github.xxfast.kotlin.native.nuget.processor.cir.nativePrefix
import io.github.xxfast.kotlin.native.nuget.processor.cir.nestedCsName
import io.github.xxfast.kotlin.native.nuget.processor.exports.isCompilerOwnedMember
import io.github.xxfast.kotlin.native.nuget.processor.toCName
import io.github.xxfast.kotlin.native.nuget.processor.asCSymbol

/**
 * ADR-075: a mutable collection property whose declared type is a `Collection` (optionally
 * `Nullable`) but whose element (or map key/value) fails [isWrappableComponent] — the property
 * still plans, with `setter = null`, so the getter survives; this is what feeds the diagnostic
 * naming the offending component and stating that the C# property is read-only.
 */
internal data class ForwardDroppedPropertySetter(
  val symbol: String,
  val node: KSNode?,
  val publicName: String,
  val componentDescription: String,
  /**
   * ADR-107: the sentence explaining the refusal, when the ADR-075 collection wording ("cannot be
   * written into a Kotlin collection") does not apply. A `var error: IllegalStateException?` is
   * refused because C# can only hand Kotlin a `NugetManagedException` (ADR-201), not because a
   * component failed to box; the property still survives read-only, which is what this record
   * means.
   */
  val reason: String? = null,
  /**
   * ADR-064 amendment (issue #249): the declaration the surviving C# property is rendered on, so
   * the remark can be attached to the PROPERTY (`Property(owner, publicName)`) rather than to its
   * type -- the member still exists, get-only.
   */
  val owner: ForwardDiagnosticOwner? = null,
) {
  val memberName: String? get() = (node as? KSDeclaration)?.simpleName?.asString()
}

/**
 * A property whose declared type [ForwardPropertyPlanner.isPlannable] rejects outright, so the
 * whole property (getter and setter alike) is absent from the generated C#. Unlike
 * [ForwardDroppedPropertySetter], which is a partial skip, nothing of this property survives.
 */
internal data class ForwardDroppedProperty(
  val symbol: String,
  val node: KSNode?,
  val typeDescription: String,
  /** ADR-088: the declared type is a bound C# interface, which v1 marshals at ordinary
   *  parameter/return positions but not at a property. Routes the diagnostic to the named
   *  `SKIPPED_BOUND_TYPE_POSITION` kind instead of the generic unsupported-property one, whose
   *  "expose a property whose type is not IFeedable" hint would be actively misleading. */
  val boundInterface: Boolean = false,
  /** ADR-115: the fully-qualified name of the `@RequiresOptIn` marker on the property or its
   *  setter. Routes the diagnostic to `SKIPPED_OPT_IN_MARKER`, whose message names the marker
   *  rather than blaming the property's (perfectly bridgeable) type. */
  val optInMarker: String? = null,
  /** ADR-064's 2026-09-11 amendment: the same [ForwardPlanSkipReason] classification a dropped
   *  *callable* carries, so a scope, nesting, sealed or opt-in drop reads the reason's own
   *  sentence and remedy instead of the generic "no property getter or setter shape" pair, which
   *  named the property's type when the type was not what failed. `null` only when the classifier
   *  produced no reason at all; a reason that does not own a sentence (a legacy-route deferral
   *  like [ForwardPlanSkipReason.GENERIC]) keeps the shipped wording too. */
  val reason: ForwardPlanSkipReason? = null,
  /** The detail slot [reason]'s sentence and hint read: the undeclared type's name, the
   *  dependency type to `include(...)`, the opt-in marker, the sealed base. See
   *  [BridgeType.skipDetail]. */
  val detail: String? = null,
  /** ADR-064 amendment (issue #249): the declaration this whole-property drop leaves a hole in. */
  val owner: ForwardDiagnosticOwner? = null,
  /** The non-null spelling (`(Int) -> Unit`) of a nullable function type this owner binds only
   *  non-null. Routes the diagnostic to a sentence naming the nullability, where the generic
   *  "expose a property whose type is not `(Int) -> Unit?`" read as if no lambda could bind. */
  val nullableFunctionType: String? = null,
) {
  val memberName: String? get() = (node as? KSDeclaration)?.simpleName?.asString()
}

/**
 * An extension property whose *receiver* type has no supported wire shape, so the whole property is
 * absent from the generated C#. Separate from [ForwardDroppedProperty] because the property's own
 * type is usually fine here: reusing that record would name the property's type in a message about
 * its receiver, which is worse than no message.
 */
internal data class ForwardDroppedExtensionReceiver(
  val symbol: String,
  val node: KSNode?,
  val receiverDescription: String,
  /**
   * ADR-064 amendment (2026-09-20): the planner's own classification of *why* the receiver was
   * refused, when it has a name the diagnostic can read a sentence and a hint off (a shadowing
   * member or extension function, a nullable-receiver twin). Null for a receiver with no wire at
   * all, which keeps the shipped "not a supported extension-property receiver" pair.
   */
  val reason: ForwardPlanSkipReason? = null,
  /** The detail [reason]'s sentence and hint read: the rendered receiver type (`Int?`). Same slot
   *  `ForwardCallableCatalogEntry.Skipped.detail` carries on the callable route, so the two routes
   *  render one string. */
  val detail: String? = null,
)

/** Builds the property slice while leaving unsupported/specialized properties on their named legacy paths. */
internal class ForwardPropertyPlanner(
  private val classifier: ForwardBridgeTypeClassifier,
  /** ADR-163: the one forward symbol table, the same instance the callable planner holds. */
  private val symbols: ForwardSymbolTable,
  /**
   * ADR-150: the same index the callable planner holds. Without it a documented top-level
   * `expect val`, and a documented property of an `expect class`, rendered undocumented — the
   * `actual` this planner sees normally carries no KDoc of its own.
   */
  private val expects: ExpectIndex = ExpectIndex(),
) {
  /**
   * Issue #249: the declaration whose properties are being planned right now, stamped onto every
   * drop this walk records. A field rather than a parameter threaded through `propertyPlan` and
   * `collectionSetterOrNull`: planning is strictly sequential, and the alternative is an owner
   * argument on six private functions that only the diagnostics read.
   */
  private var ownerScope: ForwardDiagnosticOwner? = null

  private fun <T> inOwner(owner: ForwardDiagnosticOwner?, body: () -> T): T {
    val previous: ForwardDiagnosticOwner? = ownerScope
    ownerScope = owner
    try {
      return body()
    } finally {
      ownerScope = previous
    }
  }

  private val droppedSetters: MutableList<ForwardDroppedPropertySetter> = mutableListOf()
  private val dropped: MutableList<ForwardDroppedProperty> = mutableListOf()
  private val droppedReceivers: MutableList<ForwardDroppedExtensionReceiver> = mutableListOf()

  // ADR-188: (extension namespace, receiver declaration, C# name) -> the diagnostic spelling
  // (`extension function `name``) of every exported extension function, set by [catalog] before
  // its extension-property walk.
  private var extensionFunctionNames: Map<Triple<String, String, String>, String> = emptyMap()

  /** ADR-075: every collection property setter this planner declined to build because a
   *  component failed [isWrappableComponent] — the property itself is still planned, get-only. */
  val droppedPropertySetters: List<ForwardDroppedPropertySetter> get() = droppedSetters

  /** Every property this planner declined to plan at all, minus the ones a legacy route still
   *  re-emits (see [recordDropped]). */
  val droppedProperties: List<ForwardDroppedProperty> get() = dropped

  /** Every extension property this planner declined to plan because of its receiver type. */
  val droppedExtensionReceivers: List<ForwardDroppedExtensionReceiver> get() = droppedReceivers

  fun catalog(
    classes: List<KSClassDeclaration>,
    topLevel: List<KSPropertyDeclaration>,
    extensions: List<KSPropertyDeclaration>,
    sealed: List<KSClassDeclaration> = emptyList(),
    // ROADMAP Phase 4: the object singletons, whose own properties had no route at all before --
    // neither planned nor dropped, so `object Jar { val count }` was silent on both halves.
    objects: List<KSClassDeclaration> = emptyList(),
    // ADR-006 amendment: every exported enum, top-level, nested (ADR-133) or the enum arm of a
    // sealed interface (ADR-157) alike; its own member properties plan as [ENUM_MEMBER].
    enums: List<KSClassDeclaration> = emptyList(),
    // ADR-188: the exported extension functions, read only to refuse an extension property that
    // shares its C# name and receiver with one of them (C# 14 member lookup is ambiguous, CS9339).
    extensionFunctions: List<KSFunctionDeclaration> = emptyList(),
    // Every C entry point the callable catalog minted (its plans and the hand-written callback
    // routes), which an extension property accessor must not spell again.
    callableExports: Set<String> = emptySet(),
  ): List<ForwardPropertyPlan> = buildList {
    extensionFunctionNames = extensionFunctions.mapNotNull { function ->
      val receiverType: KSType = function.extensionReceiver?.resolve()?.expandAliases()
        ?: return@mapNotNull null
      val receiverDeclaration: KSDeclaration = receiverType.declaration
      // ADR-132 amendment (2026-10-04): a nullable VALUE-type receiver keys apart from its non-null
      // spelling, because C# member lookup tells `int` from `int?` (see [receiverKey]).
      val receiver: String = receiverKey(
        receiverDeclaration.qualifiedName?.asString() ?: return@mapNotNull null,
        classifier.classify(receiverType).sealedAsHandle(),
      )
      val namespace: String = classifier.extensionNamespaceOf(receiverDeclaration, function)
      val name: String =
        if (Modifier.SUSPEND in function.modifiers) {
          function.csharpAsyncMemberName()
        } else {
          function.csharpMemberName()
        }
      Triple(namespace, receiver, name) to "extension function `${function.simpleName.asString()}`"
    }.toMap()
    enums.forEach { enum ->
      inOwner(enum.forwardDiagnosticOwner()) {
        addAll(enumMemberProperties(enum))
        // ADR-006 amendment: a companion `val`/`var` is a static property of `{Enum}Extensions`,
        // planned as a class companion's is (`const val` stays excluded, and named elsewhere).
        addAll(companionProperties(enum))
      }
    }
    classes.forEach { cls ->
      // ADR-013: a companion's properties render as the class's statics, so both walks share the
      // class as their C# owner.
      inOwner(cls.forwardDiagnosticOwner()) {
        addAll(classProperties(cls))
        addAll(companionProperties(cls))
      }
    }
    // Issue #249 + ROADMAP Phase 4: the object walk needs its own owner scope for the same reason
    // the class walk has one. Its drops are real since the Phase 4 item made a Flow, StateFlow or
    // lambda property on a static owner a NAMED skip, and an ownerless record reaches
    // `NugetDiagnostics.json` but never `CirFile.withSkipRemarks`, so the generated
    // `public static class` said nothing about the member it lost. The owner is the OBJECT, never
    // the member's `parentDeclaration`: this walk flattens inherited members (`superClass = null`),
    // and the C# hole for an inherited `val` is on the object's static class, not on the supertype
    // that declared it.
    objects.forEach { obj ->
      inOwner(obj.forwardDiagnosticOwner()) { addAll(objectProperties(obj)) }
    }
    // ADR-111: a sealed subclass is reached only through its base (`classes` excludes it by
    // `isSealedSubclass`), so nothing double-plans.
    sealed.forEach { base ->
      // ADR-111 amendment (2026-09-11): the base's own declared properties first, so an arm can
      // ask the catalog whether the base already carries the member it is about to project.
      inOwner(base.forwardDiagnosticOwner()) { addAll(sealedBaseProperties(base)) }
      base.getSealedSubclasses().forEach { subclass ->
        inOwner(subclass.forwardDiagnosticOwner()) {
          addAll(sealedSubclassProperties(base, subclass))
        }
      }
    }
    topLevel.forEach { prop ->
      inOwner(prop.forwardFileClassOwner()) { topLevelProperty(prop)?.let(::add) }
    }
    // An extension property's holder is `{Receiver}Extensions`, which a dropped one may have been
    // the only member of, so it stays ownerless (see `warnDroppedForwardExtensionReceivers`).
    // Every accessor a member, top-level or static route minted above, so an extension accessor
    // that would spell one of them takes the `ext` role word instead (`ForwardSymbolTable`): an
    // unshadowed `val Leash?.x` beside a member `Leash.x` in ONE package both derive `leash_get_x`.
    // Plus every entry point the callable catalog minted ([callableExports]: a member function
    // spelled `get_x` derives the same `leash_get_x`) and the hand-written getter of every member
    // lambda property (`leash_get_onTap`), which no plan owns.
    val taken: Set<String> = flatMap { plan -> plan.calls().map { call -> call.exportName } }
      .toSet() + callableExports + legacyLambdaPropertyGetters(classes, sealed)
    // ADR-132 amendment (2026-10-04): a nullable VALUE-type receiver (`val Int?.x`,
    // `val Uuid?.x`) plans after every other extension property, against their accessors too, so
    // beside a non-null twin it is the one that takes the `ext` role word, whichever of the two the
    // author declared first.
    val (nullableValues: List<KSPropertyDeclaration>, others: List<KSPropertyDeclaration>) =
      extensions.partition { prop -> prop.hasNullableValueReceiver() }
    val plannedOthers: Map<KSPropertyDeclaration, ForwardPropertyPlan> = others
      .mapNotNull { prop ->
        inOwner(null) { extensionProperty(prop, taken) }?.let { plan -> prop to plan }
      }
      .toMap()
    val othersTaken: Set<String> = taken + plannedOthers.values
      .flatMap { plan -> plan.calls().map { call -> call.exportName } }
    val plannedNullableValues: Map<KSPropertyDeclaration, ForwardPropertyPlan> = nullableValues
      .mapNotNull { prop ->
        inOwner(null) { extensionProperty(prop, othersTaken) }?.let { plan -> prop to plan }
      }
      .toMap()
    val planned: List<Pair<KSPropertyDeclaration, ForwardPropertyPlan>> =
      extensions.mapNotNull { prop ->
        (plannedOthers[prop] ?: plannedNullableValues[prop])?.let { plan -> prop to plan }
      }
    // ADR-188 amendment: `val Cat.x` beside `val Cat?.x` is legal Kotlin, but the plan symbol and
    // the export are built from the receiver DECLARATION, so both plan as one symbol (and one C
    // entry point), and for a reference receiver C# cannot declare the pair either (CS0102).
    // Refused after planning, so a twin that already dropped for its own reason (a shadowed `Cat`)
    // leaves the survivor binding as before. Neither twin is a safe survivor, so both go, as one
    // fatal record. A value-type
    // twin (`val Int.x` beside `val Int?.x`, `val Uuid.x` beside `val Uuid?.x`) never groups here:
    // the nullable one keys `pkg.Int?.x` ([extensionPropertySymbol]), and C# declares
    // `extension(int)` beside `extension(int?)`.
    planned.groupBy { (_, plan) -> plan.symbol }.values.forEach { twins ->
      if (twins.size == 1) {
        add(twins.single().second)
        return@forEach
      }
      val first: KSPropertyDeclaration = twins.first().first
      droppedReceivers.add(
        ForwardDroppedExtensionReceiver(
          symbol = twins.first().second.symbol,
          node = first,
          receiverDescription = "",
          reason = ForwardPlanSkipReason.NULLABLE_RECEIVER_TWIN,
          detail = twins.joinToString(" and ") { (prop, _) -> "`${prop.kotlinSpelling()}`" },
        ),
      )
    }
  }

  /**
   * The receiver half of the ADR-188 property/function clash key: the receiver declaration, with a
   * `?` for a nullable C# VALUE type ([isNullableValueTypeReceiver]) and nullability-blind for a
   * reference type, matching what C# member lookup can and cannot tell apart.
   */
  private fun receiverKey(declaration: String, receiverType: BridgeType): String =
    if (receiverType.isNullableValueTypeReceiver()) "$declaration?" else declaration

  /** Whether this extension property's receiver is a nullable C# value type (`Int?`, `Uuid?`). */
  private fun KSPropertyDeclaration.hasNullableValueReceiver(): Boolean {
    val receiver: KSType = extensionReceiver?.resolve()?.expandAliases() ?: return false
    return classifier.classify(receiver).sealedAsHandle().isNullableValueTypeReceiver()
  }

  /**
   * The `<owner>_get_<name>` entry point the hand-written legacy getter of every member lambda
   * property exports, on a class (`addClassExports`) and on a sealed arm (`addSealedClassExports`).
   * No plan owns it, so neither planner's name set held it. Inherited properties are walked too,
   * as the emitters walk them.
   */
  private fun legacyLambdaPropertyGetters(
    classes: List<KSClassDeclaration>,
    sealed: List<KSClassDeclaration>,
  ): Set<String> = buildSet {
    // The emitters' own rule (`carriesLegacyLambdaProperty`), so this set names exactly the getters
    // `ClassExports` and `SealedClassExports` emit and no parallel predicate can drift from them.
    fun addFrom(owner: KSClassDeclaration, prefix: String, carrier: ForwardLambdaPropertyCarrier) {
      owner.getAllProperties()
        .filter { prop -> prop.getVisibility() == Visibility.PUBLIC }
        .filter { prop -> prop.carriesLegacyLambdaProperty(carrier) }
        .forEach { prop -> add("${prefix}_get_${prop.simpleName.asString()}") }
    }
    classes.forEach { cls ->
      addFrom(cls, cls.nativePrefix(symbols), cls.classLambdaPropertyCarrier())
    }
    sealed.forEach { base ->
      base.getSealedSubclasses().forEach { arm ->
        val prefix: String =
          "${base.nativePrefix(symbols)}_${arm.simpleName.asString().lowercase()}"
        addFrom(arm, prefix, ForwardLambdaPropertyCarrier.SEALED_ARM)
      }
    }
  }

  /** `val Cat?.x`, as the author wrote the declaration's head. */
  private fun KSPropertyDeclaration.kotlinSpelling(): String {
    val keyword: String = if (isMutable) "var" else "val"
    val receiver: KSType? = extensionReceiver?.resolve()
    val receiverName: String = (receiver?.declaration as? KSClassDeclaration)?.nestedCsName()
      ?: receiver?.declaration?.simpleName?.asString().orEmpty()
    val nullable: String = if (receiver?.isMarkedNullable == true) "?" else ""
    return "$keyword $receiverName$nullable.${simpleName.asString()}"
  }

  /**
   * ADR-111 amendment (2026-09-11): the sealed **base**'s own declared properties, planned like
   * [classProperties] under the base's own `${sealed}_get_x` export prefix.
   *
   * ADR-101 amendment (2026-09-27): declared properties plus every inherited one no rendered C#
   * supertype carries (an unexported base's, and any interface's), re-homed onto the base, the
   * only C# carrier they have; a kept exported base's own properties stay on it. It used to be
   * declared-only, which silently lost them all. `isForwardMemberOf`, not the plannable variant:
   * an inherited abstract property the base does not implement plans like a declared
   * `abstract val`. The plan is only an ABI, and the generated export reads
   * `handle.asStableRef<Base>().get().sides`, which is Kotlin's own virtual dispatch and therefore
   * answers with the arm's value.
   */
  private fun sealedBaseProperties(sealed: KSClassDeclaration): List<ForwardPropertyPlan> {
    val owner: String = sealed.qualifiedName?.asString() ?: return emptyList()
    val prefix: String = sealed.nativePrefix(symbols)
    val keptBase: KSClassDeclaration? = sealed.forwardSuperClass(classifier.exportedObjectHandles)
    return sealed.getAllProperties()
      .filter { it.getVisibility() == Visibility.PUBLIC }
      .filter { prop -> !prop.isCompilerOwnedMember(sealed) }
      .filter { prop -> prop.isForwardMemberOf(sealed, keptBase) }
      .mapNotNull { prop ->
        propertyPlan(
          symbol = "$owner.${prop.simpleName.asString()}",
          position = ForwardPropertyPosition.CLASS,
          receiver = ForwardPropertyReceiver.Handle(sealed.forwardOwnerTypeName() ?: owner),
          prop = prop,
          getExport = "${prefix}_get_${prop.simpleName.asString()}",
          setExport = "${prefix}_set_${prop.simpleName.asString()}",
          superClass = keptBase,
          lambdaCarrier = ForwardLambdaPropertyCarrier.SEALED_BASE,
        )
      }
      .toList()
  }

  /**
   * ADR-111: a sealed subclass's properties, planned exactly like [classProperties] but keeping
   * the ADR-009 `${sealed}_${sub}_get_x` export prefix the discriminator, dispose and data-class
   * exports still use.
   *
   * `superClass = sealed` since the 2026-09-11 amendment: the generated C# base now carries the
   * base's own members ([sealedBaseProperties]), so an arm binds only its own surface like an
   * ordinary subclass (declared, plus what it inherits from an interface the sealed type does not
   * carry, which the arm now lists: ADR-101 amendment 2026-09-27). Before that it was `null`,
   * which flattened every implemented base property onto every arm; keeping it would be CS0108
   * against the base's new member.
   */
  private fun sealedSubclassProperties(
    sealed: KSClassDeclaration,
    subclass: KSClassDeclaration,
  ): List<ForwardPropertyPlan> {
    val owner: String = subclass.qualifiedName?.asString() ?: return emptyList()
    val prefix: String =
      "${sealed.nativePrefix(symbols)}_${subclass.simpleName.asString().lowercase()}"
    // ADR-157: an enum arm carries exactly one property, the box's `Value`, and none of the enum's
    // own. Those belong to `{Enum}Extensions` (ADR-006) and are already planned there; planning
    // them again under the arm's prefix would export each of them twice, plus `name` and `ordinal`.
    if (subclass.isEnumArm()) return enumArmValueProperty(sealed, subclass, prefix, owner)
    return subclass.getAllProperties()
      .filter { it.getVisibility() == Visibility.PUBLIC }
      .filter { prop -> !prop.isCompilerOwnedMember(subclass) }
      .filter { prop -> prop.isForwardPlannableMemberOf(subclass, superClass = sealed) }
      .mapNotNull { prop ->
        propertyPlan(
          symbol = "$owner.${prop.simpleName.asString()}",
          position = ForwardPropertyPosition.CLASS,
          receiver = ForwardPropertyReceiver.Handle(subclass.forwardOwnerTypeName() ?: owner),
          prop = prop,
          getExport = "${prefix}_get_${prop.simpleName.asString()}",
          setExport = "${prefix}_set_${prop.simpleName.asString()}",
          // ADR-168 (measured 2026-09-26): the ADR-075 read-only-base guard needs the
          // sealed base too. Without it an arm's `override var count` over the base's
          // `open val count` rendered `public override int Count { get; set; }`, CS0546 against
          // the base's get-only `Count`.
          superClass = sealed,
          // ADR-168 on an arm (2026-09-27): the arm now lists its own interfaces, so the setter
          // the guard refuses in public is reachable through an explicit `ITally.Count`. Only an
          // interface the arm itself lists: one the sealed type already is (the sealed interface
          // parent included) is not in the arm's base list, and an explicit member naming it
          // would be CS0540.
          implementer = subclass,
          carriedInterfaces = sealed.forwardSupertypeNames(),
          lambdaCarrier = ForwardLambdaPropertyCarrier.SEALED_ARM,
        )
      }
      .toList()
  }

  /**
   * ADR-157: the boxed enum arm's one property, `Value`, planned rather than hand-written so its
   * error slot, its `int` ordinal wire and its C# getter come off the same emitter and projection
   * every other arm property uses, and so the ADR-055 contract check sees both halves.
   *
   * There is no [KSPropertyDeclaration] behind it: `Value` is the box, not a Kotlin member, which
   * is what [ForwardPropertyReceiver.EnumArm] exists to say.
   */
  private fun enumArmValueProperty(
    sealed: KSClassDeclaration,
    subclass: KSClassDeclaration,
    prefix: String,
    owner: String,
  ): List<ForwardPropertyPlan> {
    if (sealed.qualifiedName == null) return emptyList()
    // ADR-199: a generic base reads star-projected (`Reply<*>`).
    val base: String = sealed.forwardStarSpelling()
    val type: BridgeType = classifier.classify(subclass.asStarProjectedType())
    if (type !is BridgeType.Enum) return emptyList()
    val receiver = ForwardPropertyReceiver.EnumArm(base, owner)
    val getter = ForwardPropertyGetter.Direct(
      nativeCall("${prefix}_get_value", type.wireType(), receiver, emptyList()),
    )
    return listOf(
      ForwardPropertyPlan(
        // Keyed on the BOX, not on the enum: the enum's own qualified name is the catalog key of
        // nothing else, but a key that reads as a member of the arm is what every reader expects.
        symbol = "$owner.${ENUM_ARM_VALUE_MEMBER}",
        position = ForwardPropertyPosition.CLASS,
        receiver = receiver,
        kotlinName = ENUM_ARM_VALUE_MEMBER,
        publicName = "Value",
        type = type,
        getter = getter,
        helperRequirements = helperRequirements(type, receiver, getter, setter = null),
      ).validate(),
    )
  }

  private fun classProperties(cls: KSClassDeclaration): List<ForwardPropertyPlan> {
    val owner: String = cls.qualifiedName?.asString() ?: return emptyList()
    // One shared predicate with `CirClassTranslator` (see `ForwardClassMembership.kt`): this used
    // to count any non-`Any` supertype, so an interface-only class planned none of its inherited
    // members while the translator still rendered them into `: IGreeter`.
    val superClass: KSClassDeclaration? = cls.forwardSuperClass(classifier.exportedObjectHandles)
    return cls.getAllProperties()
      .filter { it.getVisibility() == Visibility.PUBLIC }
      // Issue #235: a compiler plugin's property (`descriptor` and friends) never plans.
      .filter { prop -> !prop.isCompilerOwnedMember(cls) }
      .filter { prop -> prop.isForwardPlannableMemberOf(cls, superClass) }
      .mapNotNull { prop ->
        propertyPlan(
          symbol = "$owner.${prop.simpleName.asString()}",
          position = ForwardPropertyPosition.CLASS,
          // ADR-147: a generic owner is read back fully applied (`Crate<Any?>`); the bare
          // qualified name is not a legal type argument to `asStableRef`.
          receiver = ForwardPropertyReceiver.Handle(cls.forwardOwnerTypeName() ?: owner),
          prop = prop,
          getExport = "${cls.nativePrefix(symbols)}_get_${prop.simpleName.asString()}",
          setExport = "${cls.nativePrefix(symbols)}_set_${prop.simpleName.asString()}",
          superClass = superClass,
          implementer = cls,
          lambdaCarrier = cls.classLambdaPropertyCarrier(),
        )
      }
      .toList()
  }

  /**
   * ADR-168: the exported interfaces whose C# declaration carries a settable [prop]
   * (`ITally.Count { get; set; }`) and that [this] class implements through its own base list.
   * When the read-only-base guard refuses the class's public setter (CS0546), these are the
   * interfaces the setter is still reachable through, as an explicit `ITally.Count` member.
   *
   * Only a DECLARED (or diamond-redeclared) placement counts: an inherited member lives on the
   * super-interface that declares it, and the explicit member has to name that one. A member typed
   * by the interface's own type parameter is excluded: the ADR-113 carve-out renders it get-only,
   * so an explicit `set` against it would be CS0550.
   */
  private fun KSClassDeclaration.explicitSetterInterfaces(
    prop: KSPropertyDeclaration,
    carried: Set<String> = emptySet(),
  ): List<String> {
    val exported: Set<String> = classifier.exportedObjectHandles
    val name: String = prop.simpleName.asString()
    // [carried]: the interfaces a sealed arm's base already is, which the arm's base list leaves
    // out (ADR-101 amendment 2026-09-27). Empty for an ordinary class.
    fun KSClassDeclaration.isExportedInterface(): Boolean =
      classKind == com.google.devtools.ksp.symbol.ClassKind.INTERFACE &&
          qualifiedName?.asString() in exported &&
          qualifiedName?.asString() !in carried
    val direct: List<KSClassDeclaration> = superTypes
      .mapNotNull { it.resolve().declaration as? KSClassDeclaration }
      .filter { it.isExportedInterface() }
      .toList()
    val implemented: List<KSClassDeclaration> = (
        direct + direct.flatMap { iface ->
          iface.getAllSuperTypes()
            .mapNotNull { it.declaration as? KSClassDeclaration }
            .filter { it.isExportedInterface() }
            .toList()
        }
        ).distinctBy { it.qualifiedName?.asString() }
    return implemented
      .filter { iface ->
        val member: KSPropertyDeclaration = iface.getAllProperties()
          .firstOrNull { it.simpleName.asString() == name } ?: return@filter false
        val placement: ForwardInterfaceMemberPlacement =
          ForwardInterfaceHierarchy(iface, exported).placement(member)
        val isNotTypeParameter: Boolean =
          member.type.resolve().declaration !is com.google.devtools.ksp.symbol.KSTypeParameter
        member.isMutable &&
            member.getVisibility() == Visibility.PUBLIC &&
            isNotTypeParameter &&
            (placement == ForwardInterfaceMemberPlacement.DECLARED ||
                placement == ForwardInterfaceMemberPlacement.DIAMOND_OVERRIDE)
      }
      .mapNotNull { it.qualifiedName?.asString() }
  }

  /**
   * ADR-040: dispatch-export property plans for an interface's own declared properties
   * (reachability-driven — only called for interfaces already known to appear in a planned return
   * position). Shaped exactly like [classProperties], with the interface's own qualified name as
   * both the symbol owner and the `asStableRef` receiver type.
   */
  fun interfaceProperties(
    iface: KSClassDeclaration,
    // Interface super-interfaces: the ADR-040 backing class implements every inherited member, so
    // an interface plans them all. The unexported-supertype lookup (ADR-075) keeps own-only.
    inherited: Boolean = true,
  ): List<ForwardPropertyPlan> {
    val owner: String = iface.qualifiedName?.asString() ?: return emptyList()
    val prefix: String = iface.nativePrefix(symbols)
    val hierarchy = ForwardInterfaceHierarchy(iface, classifier.exportedObjectHandles)
    return inOwner(iface.forwardDiagnosticOwner()) {
      iface.getAllProperties()
        .filter { it.getVisibility() == Visibility.PUBLIC }
        .filter { prop -> !prop.isCompilerOwnedMember(iface) }
        .filter { prop -> if (inherited) true else prop.parentDeclaration == iface }
        .mapNotNull { prop ->
          val placement: ForwardInterfaceMemberPlacement = hierarchy.placement(prop)
          // A kept super's unplannable property is that super's drop, named once on it.
          val droppedBefore: Int = dropped.size
          val droppedSettersBefore: Int = droppedSetters.size
          val plan: ForwardPropertyPlan? = propertyPlan(
            symbol = "$owner.${prop.simpleName.asString()}",
            position = ForwardPropertyPosition.CLASS,
            receiver = ForwardPropertyReceiver.Handle(owner),
            prop = prop,
            getExport = "${prefix}_get_${prop.simpleName.asString()}",
            setExport = "${prefix}_set_${prop.simpleName.asString()}",
            typeOverride = if (placement == ForwardInterfaceMemberPlacement.COVARIANT_OVERRIDE) {
              hierarchy.keptPropertyType(prop)
            } else {
              null
            },
          )
          val restored: Boolean = prop.restoredByTypeParameterCarveOut(iface)
          val inherited: Boolean = placement == ForwardInterfaceMemberPlacement.INHERITED
          if (plan == null && (inherited || restored)) {
            while (dropped.size > droppedBefore) dropped.removeAt(dropped.lastIndex)
          }
          // ADR-168: the same rule for a refused SETTER. `IBase.X` is where C# declares
          // the member (and where the remark lands), so `Derived.x` naming it again is noise.
          if (inherited) {
            while (droppedSetters.size > droppedSettersBefore) {
              droppedSetters.removeAt(droppedSetters.lastIndex)
            }
          }
          plan
        }
        .toList()
    }
  }

  private fun companionProperties(cls: KSClassDeclaration): List<ForwardPropertyPlan> {
    val companion: KSClassDeclaration = cls.declarations.filterIsInstance<KSClassDeclaration>()
      .firstOrNull { it.isCompanionObject } ?: return emptyList()
    val owner: String = cls.qualifiedName?.asString() ?: return emptyList()
    val prefix: String = cls.nativePrefix(symbols)
    return companion.getAllProperties()
      .filter { it.getVisibility() == Visibility.PUBLIC }
      .filter { prop -> !prop.isCompilerOwnedMember(companion) }
      .filter { !it.modifiers.contains(Modifier.CONST) }
      .mapNotNull { prop ->
        val name: String = prop.simpleName.asString()
        propertyPlan(
          symbol = "$owner.Companion.$name",
          position = ForwardPropertyPosition.COMPANION,
          receiver = ForwardPropertyReceiver.Static(owner),
          prop = prop,
          getExport = "${prefix}_companion_get_$name",
          setExport = "${prefix}_companion_set_$name",
        )
      }
      .toList()
  }

  /**
   * ROADMAP Phase 4: an `object`'s own properties, planned as the fourth static position.
   *
   * The receiver is [ForwardPropertyReceiver.Static] with the object's qualified name, exactly the
   * companion arm's: there is no singleton receiver on the wire at all, and the emitter spells the
   * access `pkg.TreatPantry.count`, which is how a Kotlin object property is read. `const val` is
   * excluded here and rendered as a C# `const` by `translateConstProperty`, the way a companion's
   * is.
   *
   * Inherited members FLATTEN (`superClass = null`, the class route's own predicate): a C# static
   * class cannot extend anything, so an inherited `val` has no other carrier and would otherwise be
   * unreachable. Object *methods* flatten on the same predicate
   * (`ForwardCallablePlanner.objectEntries`), so the two walks are symmetric; the asymmetry this
   * comment used to describe was lifted with them.
   */
  private fun objectProperties(obj: KSClassDeclaration): List<ForwardPropertyPlan> {
    val owner: String = obj.qualifiedName?.asString() ?: return emptyList()
    val prefix: String = obj.nativePrefix(symbols)
    return obj.getAllProperties()
      .filter { it.getVisibility() == Visibility.PUBLIC }
      .filter { prop -> !prop.isCompilerOwnedMember(obj) }
      .filter { !it.modifiers.contains(Modifier.CONST) }
      .filter { prop -> prop.isForwardPlannableMemberOf(obj, superClass = null) }
      .mapNotNull { prop ->
        val name: String = prop.simpleName.asString()
        propertyPlan(
          symbol = "$owner.$name",
          position = ForwardPropertyPosition.OBJECT,
          receiver = ForwardPropertyReceiver.Static(owner),
          prop = prop,
          getExport = "${prefix}_get_${toCName(name)}",
          setExport = "${prefix}_set_${toCName(name)}",
        )
      }
      .toList()
  }

  /**
   * ADR-006 amendment: an enum's own member properties, planned on the ordinal receiver ADR-132
   * already lowers (`Mood.entries[receiver]` / `(int)mood`), under the entry point ADR-006 always
   * used (`{prefix}_get_{name}`, and `_set_{name}` for a `var`). Being on the plan is what gives
   * the getter its error slot and containment, binds a `var`'s setter, gates the type (an
   * unplannable one is a named drop through [recordDropped], never a raw `IntPtr`), and carries
   * the KDoc.
   *
   * `name`, `ordinal` and `declaringJavaClass` are `Enum<E>`'s own members, which ADR-006 never
   * bridged (the entry itself IS the C# enum value); this is the one place that filter lives.
   */
  private fun enumMemberProperties(enum: KSClassDeclaration): List<ForwardPropertyPlan> {
    val owner: String = enum.qualifiedName?.asString() ?: return emptyList()
    val type: BridgeType = classifier.classify(enum.asStarProjectedType())
    if (type !is BridgeType.Enum) return emptyList()
    val prefix: String = enum.nativePrefix(symbols)
    return enum.getAllProperties()
      .filter { it.getVisibility() == Visibility.PUBLIC }
      .filter { prop -> prop.simpleName.asString() !in ENUM_OWN_MEMBERS }
      .filter { prop -> !prop.isCompilerOwnedMember(enum) }
      .mapNotNull { prop ->
        val name: String = prop.simpleName.asString()
        propertyPlan(
          symbol = "$owner.$name",
          position = ForwardPropertyPosition.ENUM_MEMBER,
          receiver = ForwardPropertyReceiver.Value(type),
          prop = prop,
          getExport = "${prefix}_get_$name",
          setExport = "${prefix}_set_$name",
        )
      }
      .toList()
  }

  private fun topLevelProperty(prop: KSPropertyDeclaration): ForwardPropertyPlan? {
    val name: String = prop.simpleName.asString()
    val cname: String = toCName(name)
    return propertyPlan(
      symbol = "${prop.packageName.asString()}.$name",
      position = ForwardPropertyPosition.TOP_LEVEL,
      // ADR-163: the Kotlin access is package-qualified, not a simple-name import, for the same
      // reason the top-level function call is: two `val`s of one name in two packages both export
      // now, and two simple-name imports of them are ambiguous in the generated file.
      receiver = ForwardPropertyReceiver.Static(
        kotlinPackageReference(prop.packageName.asString()).removeSuffix(".").ifEmpty { null },
      ),
      prop = prop,
      // ADR-163: library- and package-qualified like every other route, so two top-level `val`s of
      // one name in two packages no longer derive one getter symbol.
      getExport = "${symbols.qualifier(prop)}get_$cname",
      setExport = "${symbols.qualifier(prop)}set_$cname",
    )
  }

  /**
   * The `Owner.name` of a member property (declared or inherited) that shadows an extension
   * property [name] on [receiver], or null. A nullable receiver is never shadowed: the emitter's
   * `(receiver).name` has a nullable static type, so no member is a candidate and the extension
   * resolves. A private or protected member is not visible from the generated file, and a member
   * *extension* property is not a candidate for plain `receiver.name`, so neither shadows.
   */
  private fun shadowingMember(receiver: KSType, name: String): String? {
    if (receiver.isMarkedNullable) return null
    val declaration: KSClassDeclaration = receiver.declaration as? KSClassDeclaration ?: return null
    val member: KSPropertyDeclaration = declaration.getAllProperties().firstOrNull { candidate ->
      candidate.simpleName.asString() == name &&
          candidate.extensionReceiver == null &&
          candidate.getVisibility() != Visibility.PRIVATE &&
          candidate.getVisibility() != Visibility.PROTECTED
    } ?: return null
    val owner: String = (member.parentDeclaration as? KSClassDeclaration)?.nestedCsName()
      ?: declaration.nestedCsName()
    return "$owner.$name"
  }

  /**
   * ADR-188 amendment: the diagnostic spelling of a member FUNCTION of [receiver]'s own
   * declaration whose C# name is [csharpName], or null. Unlike [shadowingMember] this is C#'s
   * resolution, not Kotlin's (Kotlin keeps properties and functions apart), so a nullable receiver
   * is no exemption: C# member lookup on `Cat?` still finds the method group.
   *
   * - An enum's member functions render as `Name(this Mood …)` in the same `{Enum}Extensions` class
   *   as the extension property, whatever their arity (CS9339 with none, CS1061 with any). Selected
   *   as the enum route selects them (`ForwardCallablePlanner.enumEntries`); a suspend or generic
   *   one is a named drop there and never renders, so it is no clash.
   * - A class, interface or value class member function is an instance method, and `cat.Name` is
   *   then a method group (CS0428). A suspend one renders its `Async` spelling on the class
   *   suspend route.
   */
  private fun shadowingMemberFunction(
    receiver: KSType,
    receiverType: BridgeType,
    csharpName: String,
  ): String? {
    // Only a receiver whose C# type this module generates: a collection or a bound C# interface
    // receiver renders as a .NET type whose members are not the Kotlin declaration's.
    val generated: BridgeType = (receiverType as? BridgeType.Nullable)?.type ?: receiverType
    if (generated !is BridgeType.ObjectHandle && generated !is BridgeType.Interface &&
      generated !is BridgeType.Enum && generated !is BridgeType.ValueClass
    ) {
      return null
    }
    val declaration: KSClassDeclaration = receiver.declaration as? KSClassDeclaration
      ?: return null
    val owner: String = declaration.nestedCsName()
    return when (declaration.classKind) {
      ClassKind.ENUM_CLASS -> declaration.declarations
        .filterIsInstance<KSFunctionDeclaration>()
        .filter { function -> function.getVisibility() == Visibility.PUBLIC }
        .filter { function -> !function.isCompilerOwnedMember(declaration) }
        .filter { function -> function.simpleName.asString() !in ENUM_SYNTHESIZED_FUNCTIONS }
        .filter { function -> Modifier.SUSPEND !in function.modifiers }
        .filter { function -> function.typeParameters.isEmpty() }
        .firstOrNull { function -> function.csharpMemberName() == csharpName }
        ?.let { function -> "enum member function `$owner.${function.simpleName.asString()}`" }

      ClassKind.CLASS, ClassKind.INTERFACE -> declaration.getAllFunctions()
        .filter { function -> function.getVisibility() == Visibility.PUBLIC }
        .filter { function -> function.extensionReceiver == null }
        .filter { function -> !function.isCompilerOwnedMember(declaration) }
        .firstOrNull { function ->
          val name: String =
            if (Modifier.SUSPEND in function.modifiers) {
              function.csharpAsyncMemberName().removePrefix("@")
            } else {
              function.csharpMemberName()
            }
          name == csharpName
        }
        ?.let { function -> "member function `$owner.${function.simpleName.asString()}`" }

      else -> null
    }
  }

  private fun extensionProperty(
    prop: KSPropertyDeclaration,
    taken: Set<String> = emptySet(),
  ): ForwardPropertyPlan? {
    val receiver: KSType = prop.extensionReceiver?.resolve()?.expandAliases() ?: return null
    // ADR-105 amendment: the extension *property* receiver gets the same sealed rewrite the
    // extension *function* receiver already gets (`ForwardCallablePlanner.extensionEntry`). An
    // eligible sealed base becomes a bare `ObjectHandle` before `supportedReceiver` looks, so it
    // rides the existing handle arms of the emitter and the projection; an ineligible one stays a
    // protocol and drops below exactly as before.
    val receiverType: BridgeType = classifier.classify(receiver).sealedAsHandle()
    val supportedReceiver: Boolean = receiverType.isSupportedReceiver()
    // ADR-133 amendment: the receiver spelled with its enclosing chain (`Aviary.Perch`), in the
    // plan symbol and -- lowercased and `_`-joined by `nativePrefix()` -- in the entry point. The
    // symbol is spelled once, by [extensionPropertySymbol]; both renderers look the plan back up
    // through `ForwardCallablePlanCatalog.extensionPropertyFor(prop)`, which reads the same
    // function, so the spellings cannot drift (a mismatch used to make the property vanish from
    // `Interop.cs` with no diagnostic at all).
    // ADR-132 amendment (2026-10-04): a nullable VALUE-type receiver keys `pkg.Int?.x`, so
    // `val Int.x` beside `val Int?.x` (or `Uuid` beside `Uuid?`) plans as two symbols and both
    // bind, as the function route's `fun Int.f()` / `fun Int?.f()` pair does. A reference twin
    // (`Cat` / `Cat?`) still shares one symbol and stays the fatal `NULLABLE_RECEIVER_TWIN` (C#
    // cannot declare it, CS0102).
    val nullableValueReceiver: Boolean =
      supportedReceiver && receiverType.isNullableValueTypeReceiver()
    val symbol: String = extensionPropertySymbol(prop, nullableValueReceiver)
    // ADR-163: the receiver chain UNQUALIFIED. The package part of an extension symbol is the
    // extension's own package, supplied by `symbols.extension` below.
    val receiverPrefix: String = (receiver.declaration as? KSClassDeclaration)
      ?.let { declaration -> ForwardSymbolTable.ownerChain(declaration) }
      ?: receiver.declaration.simpleName.asString().lowercase()
    val name: String = prop.simpleName.asString()
    // ADR-064's position coverage: the receiver is the last position that used to vanish silently.
    // Nothing legacy-routes an extension property by receiver, so unlike `recordDropped` there is
    // no re-emission to exclude here.
    // ADR-132 amendment (2026-10-04): no has-value fan-out receiver reaches this refusal any more
    // (`isSupportedReceiver` admits every one), so every refused receiver reads this route's own
    // receiver sentence.
    if (!supportedReceiver) {
      droppedReceivers.add(
        ForwardDroppedExtensionReceiver(
          symbol = symbol,
          node = prop,
          receiverDescription = receiverType.diagnosticTypeName(),
        ),
      )
      return null
    }
    // Kotlin's own resolution: `receiver.name` picks a visible member property over any extension,
    // so the export body the emitter writes would read the MEMBER and the C# extension would
    // silently return the wrong value. Kotlin call syntax cannot reach this extension either, so
    // it is a named skip rather than a rewrite.
    shadowingMember(receiver, name)?.let { member ->
      droppedReceivers.add(
        ForwardDroppedExtensionReceiver(
          symbol = symbol,
          node = prop,
          receiverDescription = receiverType.diagnosticTypeName(),
          reason = ForwardPlanSkipReason.SHADOWED_BY_MEMBER,
          detail = member,
        ),
      )
      return null
    }
    // ADR-188: a C# 14 extension property and a classic extension method of one name on one
    // receiver both declare fine, but every `receiver.Name` access is then ambiguous (CS9339), so
    // the consumer could call neither spelling it expects. The function keeps the name: it is the
    // shape C# has always had, and dropping it would be the larger loss. Matched on the receiver
    // DECLARATION, so `fun Cat?.x()` and `val Cat.x` meet, and on the C# name, so a `@CSharpName`
    // on either side (ADR-179) is what separates them. Never a rename (ADR-110). Keyed on the
    // ADR-126 namespace too: an unexported receiver's pair in two packages renders two classes in
    // two namespaces, and a consumer importing either one sees no ambiguity, so both bind.
    // ADR-132 amendment (2026-10-04): and on the receiver's nullability for a VALUE type only, so
    // `fun Int.x()` beside `val Int?.x` binds both (`7.X()` and `none.X` each find one member: no
    // implicit nullable conversion applies to an extension receiver), while `fun Cat?.x()` beside
    // `val Cat.x` still meets, `Cat?` being `Cat` to C#.
    val receiverDeclaration: String = receiverKey(
      receiver.declaration.qualifiedName?.asString().orEmpty(), receiverType,
    )
    val csharpName: String = prop.csharpMemberName()
    val namespace: String = classifier.extensionNamespaceOf(receiver.declaration, prop)
    val key: Triple<String, String, String> =
      Triple(namespace, receiverDeclaration, csharpName)
    val shadowingFunction: String? =
      extensionFunctionNames[key] ?: shadowingMemberFunction(receiver, receiverType, csharpName)
    shadowingFunction?.let { function ->
      droppedReceivers.add(
        ForwardDroppedExtensionReceiver(
          symbol = symbol,
          node = prop,
          receiverDescription = receiverType.diagnosticTypeName(),
          reason = ForwardPlanSkipReason.SHADOWED_BY_EXTENSION_FUNCTION,
          detail = function,
        ),
      )
      return null
    }
    return propertyPlan(
      symbol = symbol,
      position = ForwardPropertyPosition.EXTENSION,
      receiver = ForwardPropertyReceiver.Value(receiverType),
      prop = prop,
      getExport = symbols.extension(prop, receiverPrefix, "get_${toCName(name)}", taken),
      setExport = symbols.extension(prop, receiverPrefix, "set_${toCName(name)}", taken),
    )
  }

  /**
   * Which receiver shapes the extension-property route lowers, as one exhaustive `when`: a new
   * [BridgeType] variant is a compile error here rather than a silent admission or a silent skip.
   *
   * ADR-132 at the property position: `Interface`, `Nullable(Interface)` and
   * `Nullable(ObjectHandle)` ride the same single POINTER / `HANDLE_TO_STABLE_REF` slot the bare
   * handle receiver already uses, so both renderers lower them through the arms the setter
   * *value* has always owned.
   *
   * ADR-132 amendment (2026-09-20): receiver parity with the extension **function** route. `Enum`
   * (INT32 ordinal), `Uuid` (hex-dash text), `Instant`/`Duration` (INT64 ticks), `String?`/`Uuid?`
   * and a nullable value class over a `String` or object-handle underlying (all three riding their
   * null in-band), plus the two handle-MINTING receivers `Collection` (a Kotlin list/map/set
   * StableRef built for the crossing) and `BoundInterface` (ADR-088's transfer GCHandle) all bind
   * here now, through the same lowering pair the setter value uses.
   *
   * ADR-132 amendment (2026-10-04): every has-value fan-out shape (`Nullable(Primitive)`,
   * `Nullable(Char)`, `Nullable(Enum)`, `Nullable(Instant)`, `Nullable(Duration)`, and a nullable
   * value class over a `Primitive`/`Enum` underlying) binds too, on the extension-FUNCTION route's
   * two-slot wire: `ForwardPropertyReceiver.parameters()` mints the `receiverHasValue` flag in
   * front of the value, and both renderers read it. They used to be a named skip, because a
   * receiver was exactly one slot and admitting them here alone silently lost the null.
   */
  private fun BridgeType.isSupportedReceiver(): Boolean = when (this) {
    // ADR-160: a callback binds at a parameter position; an extension ON a function type is not a
    // shape either half spells.
    is BridgeType.Callback, is BridgeType.ReturnedLambda -> false

    is BridgeType.ObjectHandle, is BridgeType.Interface, is BridgeType.Primitive,
    BridgeType.String -> true

    // ADR-132 amendment: the converting by-value receivers. Each crosses as exactly one wire slot
    // (`int` ordinal, hex-dash text, `long` ticks) and the shared `inputLowering` /
    // `inputArgument` pair already owns both halves of the conversion.
    BridgeType.Instant, BridgeType.Duration, BridgeType.Uuid, is BridgeType.Enum -> true

    // ADR-088: a bound C# interface receiver crosses as a fresh transfer GCHandle that KOTLIN
    // takes ownership of (`nuget{Iface}Value` frees it on a token-probe hit or hands it to the
    // ADR-070 wrapper's cleaner), exactly as it does at an ordinary parameter position.
    is BridgeType.BoundInterface -> true

    // ADR-075: the C# side builds a Kotlin collection for the crossing and disposes it in the
    // getter's/setter's own `finally`. Gated on the same INTO_KOTLIN component predicate the
    // collection *setter value* uses, not on the read-side `isReadable()`: a receiver is an input.
    is BridgeType.Collection -> isSetterEligible()

    // ADR-132 amendment: the nullable spellings whose wire has a spare null to ride -- a null
    // string pointer for `String?`/`Uuid?`/`ValueClass(String)?`, `IntPtr.Zero` for a handle.
    // ADR-132 amendment (2026-10-04): and every has-value fan-out shape, whose receiver is the
    // two-slot `receiverHasValue` + value pair (`parameters()` below). Both emitters lower it off
    // that flag; admitting it here alone would read the value slot only and lose the null.
    is BridgeType.Nullable -> when (val inner: BridgeType = type) {
      is BridgeType.ObjectHandle, is BridgeType.Interface, BridgeType.String,
      BridgeType.Uuid -> true

      is BridgeType.ValueClass ->
        inner.underlying is BridgeType.String || inner.underlying is BridgeType.ObjectHandle ||
            hasValueFanOutInner() != null

      BridgeType.Unit,
      is BridgeType.Primitive,
      BridgeType.Char,
      BridgeType.Instant,
      BridgeType.Duration,
      is BridgeType.Throwable,
      is BridgeType.Enum,
      is BridgeType.BoundInterface,
      BridgeType.ByteArray,
      is BridgeType.Collection,
      is BridgeType.Nullable,
      is BridgeType.Callback,
      is BridgeType.ReturnedLambda,
      is BridgeType.SpecializedProtocol,
      is BridgeType.RawKSType,
      is BridgeType.Unsupported,
      is BridgeType.RawCollection,
      is BridgeType.TypeParameter,
        -> hasValueFanOutInner() != null
    }

    // ADR-075: a value class crosses the bridge as its own underlying value (ADR-014), the same
    // wire shape its own declared members already use (`ForwardCallablePlanner.valueClassEntries`).
    // The receiver admits every underlying `isPlannable` admits at an ordinary position (ADR-077's
    // String/Primitive/Enum/ObjectHandle set): the receiver is reconstructed from that wire before
    // the property access, so an enum ordinal or a StableRef pointer is no harder here than it is
    // in a parameter slot.
    is BridgeType.ValueClass ->
      underlying is BridgeType.String || underlying is BridgeType.Primitive ||
          underlying is BridgeType.Enum || underlying is BridgeType.ObjectHandle

    // ADR-132 amendment (2026-10-04): a `Char` receiver rides the by-value CHAR16 slot a `Char`
    // setter value already uses, as the function route's `Char` receiver does.
    BridgeType.Char -> true

    // ADR-147: an extension property over a bare `T` receiver is not a generic-class member and
    // has no carrier to hang off; refused as it is today.
    // ADR-151: a `ByteArray` receiver is still refused -- it is not in the ADR-132 function-route
    // receiver set either, so admitting it here would be a new position, not parity.
    BridgeType.ByteArray, BridgeType.Unit, is BridgeType.Throwable,
    is BridgeType.SpecializedProtocol, is BridgeType.RawKSType, is BridgeType.Unsupported,
    is BridgeType.TypeParameter, is BridgeType.RawCollection -> false
  }

  private fun propertyPlan(
    symbol: String,
    position: ForwardPropertyPosition,
    receiver: ForwardPropertyReceiver,
    prop: KSPropertyDeclaration,
    getExport: String,
    setExport: String,
    // ADR-101's base-class gate, as seen by this property: the generated C# base class whose
    // accessor set an `override` here has to match. Null for every position without one
    // (top-level, extension, companion, interface dispatch).
    superClass: KSClassDeclaration? = null,
    // Interface super-interfaces: a covariant override plans at the kept super's type.
    typeOverride: KSType? = null,
    // ADR-168: the exported class this property is rendered on, when an explicit
    // interface implementation may carry a setter the public property cannot. Class route only.
    implementer: KSClassDeclaration? = null,
    // ADR-168 on an arm: the interfaces [implementer]'s C# base already carries, never explicit.
    carriedInterfaces: Set<String> = emptySet(),
    // Which legacy lambda-property route re-emits this owner's lambda property, if any. NONE for
    // every owner without one (a sealed base, an interface, every static owner).
    lambdaCarrier: ForwardLambdaPropertyCarrier = ForwardLambdaPropertyCarrier.NONE,
  ): ForwardPropertyPlan? {
    // ADR-115: the author's own signal, ahead of any type question -- nothing about the property
    // is unsupported. `@set:Marker` on a `var` skips the whole property rather than exporting it
    // get-only: an accessor-level partial projection does not exist in the forward plan.
    val optInMarker: String? = prop.optInMarker(classifier.exportMarkers)
    if (optInMarker != null) {
      dropped.add(
        ForwardDroppedProperty(
          symbol, prop, typeDescription = "", optInMarker = optInMarker, owner = ownerScope,
        ),
      )
      return null
    }
    // The callable route's gate (`planOrSkipUnguarded`): a backticked name that is no identifier
    // has no C# spelling unless the author declares one with `@CSharpName`.
    val declaredName: String = prop.simpleName.asString()
    if (!declaredName.isPlainKotlinIdentifier() && prop.declaredCSharpName() == null) {
      dropped.add(
        ForwardDroppedProperty(
          symbol, prop, typeDescription = "", reason = ForwardPlanSkipReason.NON_IDENTIFIER_NAME,
          detail = declaredName, owner = ownerScope,
        ),
      )
      return null
    }
    val type: BridgeType = classifier.classify(typeOverride ?: prop.type.resolve()).sealedAsHandle()
    // ADR-075: getter eligibility never depended on mutability or on the collection facet — a
    // `Collection` (nullable or not) plans whenever the C# read can spell every component
    // (`isReadable`; `isPlannable` already recurses through `Nullable`). Whether a *setter* can
    // also be built is a wholly separate question, decided below, independent of the getter.
    if (!isPlannable(type)) {
      recordDropped(symbol, position, prop, type, lambdaCarrier)
      return null
    }
    val name: String = prop.simpleName.asString()
    val publicName: String = prop.csharpMemberName()
    // ADR-076: Instant shares the nullable-primitive LegacyTwoCall shape exactly.
    // ADR-079: so does a Primitive/Enum-underlying value class, with the `_value` call returning
    // the underlying's wire (`wireType()` already delegates through the value class).
    val fanOutInner: BridgeType? = type.hasValueFanOutInner()
    val getter: ForwardPropertyGetter = if (fanOutInner != null) {
      ForwardPropertyGetter.LegacyTwoCall(
        presence = nativeCall(getExport, ForwardAbiWireType.BOOLEAN, receiver, emptyList()),
        value = nativeCall("${getExport}_value", fanOutInner.wireType(), receiver, emptyList()),
      )
    } else {
      ForwardPropertyGetter.Direct(nativeCall(getExport, type.wireType(), receiver, emptyList()))
    }
    // ADR-168: only the class and sealed-arm routes pass an [implementer], and only
    // a `var` the read-only-base guard is about to refuse needs the interface walk at all.
    val explicitInterfaces: List<String> =
      if (
        implementer != null &&
        prop.isMutable &&
        prop.readOnlyOverrideeOwner(superClass) != null
      ) {
        implementer.explicitSetterInterfaces(prop, carriedInterfaces)
      } else {
        emptyList()
      }
    val setter: ForwardPropertySetter? = collectionSetterOrNull(
      symbol, publicName, prop, type, setExport, receiver, superClass, explicitInterfaces,
    )
    return ForwardPropertyPlan(
      symbol = symbol,
      doc = prop.forwardKdoc(expects),
      position = position,
      receiver = receiver,
      kotlinName = name,
      publicName = publicName,
      type = type,
      getter = getter,
      setter = setter,
      explicitSetterInterfaces = if (setter != null) explicitInterfaces else emptyList(),
      helperRequirements = helperRequirements(type, receiver, getter, setter),
    ).validate()
  }

  /**
   * ADR-075 Decision 2. `null` when [prop] is not `var`, or is a `var` whose setter is narrower
   * than public ([hasPublicSetter], ADR-075 amendment) (both ordinary, expected get-only shapes —
   * no diagnostic). For a `var`, every non-`Collection` type keeps its pre-existing setter shape
   * unchanged; a `Collection` (or `Nullable` of one) is eligible only when every component
   * (element for list/set, key **and** value for map) satisfies [isWrappableComponent] — the
   * collection reference's own nullability is orthogonal to that check (Question D). An
   * ineligible collection setter records one [ForwardDroppedPropertySetter] and returns `null`,
   * so the property still plans, get-only.
   */
  private fun collectionSetterOrNull(
    symbol: String,
    publicName: String,
    prop: KSPropertyDeclaration,
    type: BridgeType,
    setExport: String,
    receiver: ForwardPropertyReceiver,
    superClass: KSClassDeclaration?,
    explicitInterfaces: List<String> = emptyList(),
  ): ForwardPropertySetter? {
    if (!prop.isMutable || !prop.hasPublicSetter()) return null
    val readOnlyBase: KSClassDeclaration? = prop.readOnlyOverrideeOwner(superClass)
    if (readOnlyBase != null) {
      val cs0546: String = "it overrides a property with no public setter on the exported base " +
          "class ${readOnlyBase.simpleName.asString()}; C# cannot add a set accessor to an " +
          "override (CS0546)"
      // ADR-168: the same `override var` also implements an exported interface
      // `var`. The setter is still built (and exported), but rendered only as an explicit
      // `IFoo.X` member beside the get-only override. Every OTHER refusal below still applies:
      // decided first, so a `Throwable?` gets its own one diagnostic and no explicit member.
      if (explicitInterfaces.isNotEmpty()) {
        val built: ForwardPropertySetter? = collectionSetterOrNull(
          symbol, publicName, prop, type, setExport, receiver, superClass = null,
        )
        if (built != null) {
          val through: String = explicitInterfaces.joinToString(" and ") { qualified ->
            "I${qualified.substringAfterLast('.')}.$publicName"
          }
          droppedSetters.add(
            ForwardDroppedPropertySetter(
              symbol = symbol,
              node = prop,
              publicName = publicName,
              owner = ownerScope,
              componentDescription = type.diagnosticTypeName(),
              reason = "$cs0546; it is reachable only through the explicit $through " +
                  "implementation",
            ),
          )
        }
        return built
      }
      droppedSetters.add(
        ForwardDroppedPropertySetter(
          symbol = symbol,
          node = prop,
          publicName = publicName,
          owner = ownerScope,
          componentDescription = type.diagnosticTypeName(),
          reason = cs0546,
        ),
      )
      return null
    }
    // ADR-107 / ADR-201: `var error: Throwable?` takes a C# exception as a `NugetManagedException`
    // (a `RuntimeException`), so a narrower declared type cannot hold it and binds get-only,
    // named here before `valueParameter` would build a setter that does not compile.
    if ((type.unwrapNullable() as? BridgeType.Throwable)?.acceptsManagedException == false) {
      droppedSetters.add(
        ForwardDroppedPropertySetter(
          symbol = symbol,
          node = prop,
          publicName = publicName,
          owner = ownerScope,
          componentDescription = type.diagnosticTypeName(),
          reason = "C# hands Kotlin a NugetManagedException (a RuntimeException), which a " +
              "property declared narrower than RuntimeException cannot hold; declare it " +
              "Throwable, Exception or RuntimeException to make it writable",
        ),
      )
      return null
    }
    // ADR-147 v1: `var item: T` binds get-only. The write side would have to mint a box per
    // assignment and dispose it after the native call, which no property setter shape carries
    // today; refused here, named, rather than emitting a setter that cannot marshal.
    if (type.unwrapNullable() is BridgeType.TypeParameter) {
      droppedSetters.add(
        ForwardDroppedPropertySetter(
          symbol = symbol,
          node = prop,
          publicName = publicName,
          owner = ownerScope,
          componentDescription = type.diagnosticTypeName(),
          reason = "a type-parameter property binds read-only in v1; the write side has no " +
              "boxing step (ADR-147)",
        ),
      )
      return null
    }
    // ADR-076: Instant shares the nullable-primitive NullableDispatch setter shape exactly.
    // ADR-079: and so does a Primitive/Enum-underlying value class -- `valueParameter` already
    // resolves the wire through the underlying and tags BOX_VALUE_CLASS.
    val fanOutInner: BridgeType? = type.hasValueFanOutInner()
    if (fanOutInner != null) {
      return ForwardPropertySetter.NullableDispatch(
        value = nativeCall(
          setExport, ForwardAbiWireType.VOID, receiver, listOf(valueParameter(fanOutInner)),
        ),
        nullValue = nativeCall("${setExport}_null", ForwardAbiWireType.VOID, receiver, emptyList()),
      )
    }
    val collection: BridgeType.Collection? = type.unwrapNullable() as? BridgeType.Collection
    if (collection != null && !collection.isSetterEligible()) {
      droppedSetters.add(
        ForwardDroppedPropertySetter(
          symbol = symbol,
          node = prop,
          publicName = publicName,
          owner = ownerScope,
          componentDescription = collection.componentDescription { isWrappableComponent() },
        ),
      )
      return null
    }
    return ForwardPropertySetter.Direct(
      nativeCall(setExport, ForwardAbiWireType.VOID, receiver, listOf(valueParameter(type))),
    )
  }

  /**
   * The exported base *class* declaring the read-only property this `var` overrides (a `val`, or a
   * `var` whose setter is narrower than public, which also binds get-only), or `null`
   * when the setter is free to be built.
   *
   * Kotlin lets an override widen `val` to `var`; C# does not. The base class renders whatever
   * accessors *it* has, so a get-only base property plus a derived `{ get; set; }` override is
   * `CS0546`. Only a base *class* member counts: a class implementing an interface member renders
   * `virtual`, not `override` (`isOpenForOverride`), and a `virtual` declaration is
   * free to carry a setter the interface never asked for.
   *
   * The class-chain lookup itself is [keptBaseOverridee] (`ForwardClassMembership.kt`), shared
   * with the `override` / `virtual` pair since the ADR-101 amendment of 2026-09-11: the two used
   * to answer differently for the same member, and a setter rule keyed on a different overridee
   * from the modifier it renders is how CS0546 gets back in. Since the 2026-10-10 amendment that
   * lookup reads through a dropped base to the kept one, so a `val` only the dropped base declares
   * refuses nothing, and a `val` the kept base declares still does whatever a dropped hop widened.
   */
  private fun KSPropertyDeclaration.readOnlyOverrideeOwner(
    superClass: KSClassDeclaration?,
  ): KSClassDeclaration? {
    val overridee: KSPropertyDeclaration =
      keptBaseOverridee(superClass) as? KSPropertyDeclaration ?: return null
    return if (overridee.isMutable && overridee.hasPublicSetter()) null else superClass
  }

  /** ADR-075 Question A alternative A1: every component must satisfy [isWrappableComponent],
   *  for every collection kind including `LIST` — a property setter starts on the strict
   *  predicate rather than inheriting the callable parameter side's known-broken `List` shapes. */
  private fun BridgeType.Collection.isSetterEligible(): Boolean {
    val isMap: Boolean = kind == CollectionKind.MAP || kind == CollectionKind.MUTABLE_MAP
    return if (isMap) {
      key?.isWrappableComponent() == true && value?.isWrappableComponent() == true
    } else {
      element?.isWrappableComponent() == true
    }
  }

  /** The component(s) failing [admitted], in the shared "element type X" / "key type X" wording
   *  of the [ForwardDroppedPropertySetter] and [ForwardDroppedProperty] diagnostics. */
  private fun BridgeType.Collection.componentDescription(
    admitted: BridgeType.() -> Boolean,
  ): String {
    val isMap: Boolean = kind == CollectionKind.MAP || kind == CollectionKind.MUTABLE_MAP
    if (!isMap) return "element type ${element?.diagnosticTypeName() ?: "unknown"}"
    val keyOk: Boolean = key?.admitted() == true
    val valueOk: Boolean = value?.admitted() == true
    return when {
      !keyOk && !valueOk ->
        "key type ${key?.diagnosticTypeName() ?: "unknown"} and value type " +
            "${value?.diagnosticTypeName() ?: "unknown"}"

      !keyOk -> "key type ${key?.diagnosticTypeName() ?: "unknown"}"
      else -> "value type ${value?.diagnosticTypeName() ?: "unknown"}"
    }
  }

  /**
   * Records one unplannable property for the `SKIPPED_UNSUPPORTED_PROPERTY` diagnostic, unless a
   * legacy route still re-emits it. The specialized callback/flow protocols are unplannable *by
   * design*: `CirClassTranslator`'s lambda and flow adapters bind them, so warning that they were
   * skipped would tell a consumer a working property had vanished. `Nullable` is unwrapped first,
   * so a `StateFlow<T>?` is excluded on the same grounds as a bare `StateFlow<T>`.
   *
   * The exclusion is **position-aware**: those adapters live in `CirClassTranslator`'s
   * `getAllProperties()` loop, so they only ever re-emit a property declared *on* a class. An
   * extension property has no adapter anywhere, so excluding it turned a real drop into silence
   * (`val Patient.status: StateFlow<Int>` vanished from the generated C# with no diagnostic).
   *
   * ROADMAP Phase 4 (2026-09-20): the predicate is now the positive `position == CLASS` rather
   * than `!= EXTENSION`. Measured by Tier 1 probe before the change: a `StateFlow` property on a
   * COMPANION, at TOP LEVEL, and (the new position) on an OBJECT produced **no** diagnostic at all
   * and **no** C# member either -- the companion loop (`CirClassTranslator`) and the top-level one
   * (`CirTranslator`) project planned properties only, and neither has a flow adapter. Only the
   * class route (which the sealed base and arm positions share) genuinely re-emits them.
   *
   * ROADMAP Phase 4 (2026-10-04): a lambda property is no longer decided by its [BridgeType]
   * here. Since ADR-160 an admissible `(Int) -> Unit` classifies as [BridgeType.Callback], not as
   * a `lambda` protocol, so the class and sealed-arm routes emitted it while this warned it was
   * skipped; and the shared CLASS position kept a sealed base's or an interface's lambda silent
   * though neither has a route. The answer is now the emitters' own predicate,
   * [carriesLegacyLambdaProperty], for the owner's [lambdaCarrier].
   *
   * A carried lambda binds get-only, so a public `var` names its setter once, the ADR-075 /
   * ADR-107 partial skip. A nullable lambda whose non-null form the owner carries is named for
   * its nullability ([ForwardDroppedProperty.nullableFunctionType]), not for its type.
   */
  private fun recordDropped(
    symbol: String,
    position: ForwardPropertyPosition,
    prop: KSPropertyDeclaration,
    type: BridgeType,
    lambdaCarrier: ForwardLambdaPropertyCarrier,
  ) {
    if (prop.carriesLegacyLambdaProperty(lambdaCarrier)) {
      if (prop.isMutable && prop.hasPublicSetter()) {
        droppedSetters.add(
          ForwardDroppedPropertySetter(
            symbol = symbol,
            node = prop,
            publicName = prop.csharpMemberName(),
            owner = ownerScope,
            componentDescription = type.diagnosticTypeName(),
            reason = "a function-type property binds read-only: its getter hands out the Kotlin " +
                "lambda, and no setter route turns a C# value back into one",
          ),
        )
      }
      return
    }
    val nullableFunctionType: String? = prop.refusesNullableLambdaProperty(lambdaCarrier)
    if (nullableFunctionType != null) {
      dropped.add(
        ForwardDroppedProperty(
          symbol, prop, type.diagnosticTypeName(),
          nullableFunctionType = nullableFunctionType,
          owner = ownerScope,
        ),
      )
      return
    }
    val protocol: BridgeType.SpecializedProtocol? =
      type.unwrapNullable() as? BridgeType.SpecializedProtocol
    val isLegacyRouted: Boolean = position == ForwardPropertyPosition.CLASS &&
        protocol != null && LEGACY_ROUTED_PROTOCOLS.any { prefix ->
      protocol.name.startsWith(prefix)
    }
    if (isLegacyRouted) return
    // Issue #52: a collection only lands here because a component failed `isReadable`, and
    // "Collection" alone would send the author after the wrong declaration, so name the slot.
    val collection: BridgeType.Collection? = type.unwrapNullable() as? BridgeType.Collection
    val description: String = if (collection == null) {
      type.diagnosticTypeName()
    } else {
      val component: String = collection.componentDescription { isReadableComponent() }
      "${type.diagnosticTypeName()} ($component)"
    }
    dropped.add(
      ForwardDroppedProperty(
        symbol, prop, description,
        boundInterface = type.unwrapNullable() is BridgeType.BoundInterface,
        // The classification is the callable planner's, unchanged: this is the same BridgeType a
        // parameter or return position would have been skipped on, so it takes the same reason.
        reason = type.skipReason(),
        detail = type.skipDetail(),
        owner = ownerScope,
      )
    )
  }

  private fun nativeCall(
    exportName: String,
    result: ForwardAbiWireType,
    receiver: ForwardPropertyReceiver,
    values: List<ForwardAbiParameter>,
  ): ForwardNativeCall = ForwardNativeCall(
    // Identity for an identifier-named property; a `@CSharpName`d backticked one cannot carry its
    // space into the entry point.
    exportName = exportName.asCSymbol(),
    csharpStem = symbols.stem(exportName.asCSymbol()),
    result = result,
    parameters = receiver.parameters() + values + errorParameter(),
  )

  private fun ForwardPropertyReceiver.parameters(): List<ForwardAbiParameter> = when (this) {
    is ForwardPropertyReceiver.Handle -> listOf(
      ForwardAbiParameter(
        "handle", ForwardAbiWireType.POINTER, ForwardAbiDirection.IN,
        ForwardTransfer(
          "handle", BridgeType.ObjectHandle(owner), ForwardFlow.INTO_KOTLIN,
          ForwardPassing.VALUE, ForwardOwnership.BORROWED, ForwardConversion.HANDLE_TO_STABLE_REF
        ),
        ForwardAbiRole.RECEIVER,
      ),
    )

    // ADR-132 amendment (2026-10-04): a has-value fan-out receiver is the extension-FUNCTION
    // route's adjacent pair, the flag first. The value slot carries the INNER type, so the export
    // takes a by-value `Int` rather than a boxed `Int?`. The fixed flag name is safe here: a
    // property export has no user parameter it could meet, only `receiver`, `value`, `errorOut`.
    is ForwardPropertyReceiver.Value -> when (val inner: BridgeType? = type.hasValueFanOutInner()) {
      null -> listOf(valueParameter(type, "receiver", ForwardAbiRole.RECEIVER))
      else -> listOf(
        ForwardAbiParameter(
          RECEIVER_HAS_VALUE, ForwardAbiWireType.BOOLEAN, ForwardAbiDirection.IN,
          ForwardTransfer(
            RECEIVER_HAS_VALUE, BridgeType.Primitive(PrimitiveKind.BOOLEAN),
            ForwardFlow.INTO_KOTLIN, ForwardPassing.VALUE, ForwardOwnership.BORROWED,
            ForwardConversion.DIRECT,
          ),
        ),
        valueParameter(inner, "receiver", ForwardAbiRole.RECEIVER),
      )
    }

    is ForwardPropertyReceiver.Static -> emptyList()

    // ADR-157: the same borrowed handle slot a [Handle] receiver carries, typed as the sealed
    // base -- which is what the box's StableRef was minted through and what the discriminator
    // reads it back as.
    is ForwardPropertyReceiver.EnumArm -> listOf(
      ForwardAbiParameter(
        "handle", ForwardAbiWireType.POINTER, ForwardAbiDirection.IN,
        ForwardTransfer(
          "handle", BridgeType.ObjectHandle(base), ForwardFlow.INTO_KOTLIN,
          ForwardPassing.VALUE, ForwardOwnership.BORROWED, ForwardConversion.HANDLE_TO_STABLE_REF,
        ),
        ForwardAbiRole.RECEIVER,
      ),
    )
  }

  private fun valueParameter(
    type: BridgeType,
    name: String = "value",
    role: ForwardAbiRole = ForwardAbiRole.SETTER_VALUE,
  ): ForwardAbiParameter = ForwardAbiParameter(
    name, type.inputWireType(), ForwardAbiDirection.IN,
    ForwardTransfer(
      name, type, ForwardFlow.INTO_KOTLIN, ForwardPassing.VALUE,
      type.inputOwnership(), type.conversion(ForwardFlow.INTO_KOTLIN)
    ),
    role,
  )

  /**
   * ADR-088: a bound C# interface arrives as a transfer GCHandle that **Kotlin** owns -- nothing on
   * the C# side frees it after the call, which is why `boundInterfacePrelude` emits no cleanup --
   * so the transfer is MATERIALIZED, matching the callable route's own bound-interface parameter
   * (`ForwardCallablePlanner`'s `BoundInterface` input arm). Every other input slot the property
   * route owns is BORROWED: the caller keeps whatever it passed.
   */
  private fun BridgeType.inputOwnership(): ForwardOwnership =
    if (unwrapNullable() is BridgeType.BoundInterface) ForwardOwnership.MATERIALIZED
    else ForwardOwnership.BORROWED

  private fun errorParameter(): ForwardAbiParameter = ForwardAbiParameter(
    "errorOut", ForwardAbiWireType.POINTER, ForwardAbiDirection.OUT,
    ForwardTransfer(
      "error", BridgeType.ObjectHandle("kotlin.Throwable"), ForwardFlow.OUT_OF_KOTLIN,
      ForwardPassing.OUT, ForwardOwnership.BORROWED, ForwardConversion.STABLE_REF_TO_HANDLE
    ),
    ForwardAbiRole.ERROR,
  )

  private fun isPlannable(type: BridgeType): Boolean = when (type) {
    BridgeType.Unit, BridgeType.Char, BridgeType.String, BridgeType.Instant, BridgeType.Duration,
    is BridgeType.Primitive, is BridgeType.Enum, is BridgeType.ObjectHandle,
    is BridgeType.Interface -> true

    // ADR-147: a `T` getter reads the boxed handle back through `NugetMarshal.FromHandle<T>`, the
    // decode the generic-class getter has always used. The setter is refused just below.
    is BridgeType.TypeParameter -> true

    // ADR-107: a Throwable property reads as the same error envelope a throw writes; the getter is
    // the only shape (the setter is refused in `collectionSetterOrNull`).
    is BridgeType.Throwable -> true

    // ADR-106: a Uuid property rides the String property shapes verbatim (getter, setter, and the
    // `Uuid?` null-pointer spelling), with the text conversion composed on each side.
    BridgeType.Uuid -> true

    // ADR-151: a ByteArray property rides the collection property shapes (single-call getter,
    // Direct setter, the null pointer for `ByteArray?`) with no component gate to apply.
    BridgeType.ByteArray -> true

    // Issue #52: the read side imposes no *marshalling* restriction on a component (unlike a
    // setter), but it still has to spell one in C#; a sealed helper inside a `List` used to sail
    // through here and crash the projection, where the bare `Shape?` spelling skips named.
    is BridgeType.Collection -> type.isReadable()

    // ADR-077 sub-items 2/4: a value-class property plans when its underlying does
    // (String/primitive/enum/ObjectHandle).
    is BridgeType.ValueClass -> when (type.underlying) {
      BridgeType.String, is BridgeType.Primitive, is BridgeType.Enum,
      is BridgeType.ObjectHandle -> true

      BridgeType.Unit,
      BridgeType.Char,
      BridgeType.Instant,
      BridgeType.Duration,
      is BridgeType.Throwable,
      BridgeType.Uuid,
      is BridgeType.Interface,
      is BridgeType.BoundInterface,
      is BridgeType.ValueClass,
      BridgeType.ByteArray,
      is BridgeType.Collection,
      is BridgeType.Nullable,
      is BridgeType.Callback,
      is BridgeType.ReturnedLambda,
      is BridgeType.SpecializedProtocol,
      is BridgeType.RawKSType,
      is BridgeType.Unsupported,
      is BridgeType.RawCollection,
      is BridgeType.TypeParameter,
        -> false
    }

    // ADR-077 sub-item 4 carried the pointer-shaped underlyings (String, ObjectHandle), which
    // carry null in-band; ADR-079 adds the Primitive/Enum ones on the LegacyTwoCall /
    // NullableDispatch has-value shapes, so the nullable spelling is now plannable exactly when
    // the non-null one is and the plain recursion is right again.
    is BridgeType.Nullable -> isPlannable(type.type)

    is BridgeType.BoundInterface,
    is BridgeType.Callback,
    is BridgeType.ReturnedLambda,
    is BridgeType.SpecializedProtocol,
    is BridgeType.RawKSType,
    is BridgeType.Unsupported,
    is BridgeType.RawCollection,
      -> false
  }

  /**
   * Issue #52: whether every component of this collection (element, or map key and value) is one
   * the property projection's `csharpType()` can spell, recursively through nested collections
   * (ADR-099) and the nullable spelling (ADR-083). This is the read-side counterpart of the
   * stricter [isWrappableComponent] setter gate: anything `false` here has no C# name at all, so
   * planning it would only defer the failure to the renderer.
   */
  private fun BridgeType.Collection.isReadable(): Boolean {
    // ROADMAP Phase 4: the `Set` element and `Map` KEY slots a `ByteArray` is declined at, the same
    // rule `isBridgeableComponent` applies -- identity equality against a copied array.
    if (declinesByteArrayComponent()) return false
    // ADR-201: and the same two slots a `Throwable` is declined at.
    if (declinesThrowableComponent()) return false
    // ADR-083 amendment (boundary nullability part B): the read side is the position ADR-083 left
    // open. `val tallies: Map<Int?, String>` rendered `IReadOnlyDictionary<int?, string>` over
    // `NugetMarshal.ReadMap<int?, string>`, whose `where TKey : notnull` made the generated file
    // fail to compile (CS8714), so the property dropped the whole module's build rather than
    // binding.
    if (declinesNullableMapKey()) return false
    val isMap: Boolean = kind == CollectionKind.MAP || kind == CollectionKind.MUTABLE_MAP
    return if (isMap) {
      key?.isReadableComponent() == true && value?.isReadableComponent() == true
    } else {
      element?.isReadableComponent() == true
    }
  }

  /** The component types `ForwardCirPropertyProjection.csharpType()` has a spelling for; keep the
   *  two in step. A value class reads through its underlying (`FromHandle<underlying>`), so the
   *  underlying is what has to be spellable. Exhaustive on purpose: a new [BridgeType] variant
   *  must decide here rather than fall into an `else`. */
  private fun BridgeType.isReadableComponent(): Boolean = when (this) {
    // ADR-160: not readable at a property position; a function-typed property keeps its own skip.
    is BridgeType.Callback, is BridgeType.ReturnedLambda -> false

    // ADR-176: `Interface` was admitted here before the method gates lifted it, so a collection
    // property getter bound with no reachable backing wrapper and threw at the first element; the
    // reachability walk in `NugetProcessor` now visits every collection component. The setter side
    // is `isWrappableComponent`, which admits `Interface` too.
    BridgeType.Char, BridgeType.String, BridgeType.Instant, BridgeType.Duration,
    is BridgeType.Primitive, is BridgeType.Enum, is BridgeType.ObjectHandle,
    is BridgeType.Interface -> true

    // ADR-201: a value class over `Throwable` stays deferred, as `isBridgeableComponent` says.
    is BridgeType.ValueClass ->
      underlying !is BridgeType.Throwable && underlying.isReadableComponent()
    is BridgeType.Nullable -> type.isReadableComponent()
    is BridgeType.Collection -> isReadable()
    // ADR-201: a Throwable component reads back as its own envelope through
    // `NugetErrorNative.BuildException`, the same per-element box `isBridgeableComponent` admits.
    is BridgeType.Throwable -> true
    // ADR-106: `List<Uuid>` is deferred for the same reason, and skips named here.
    BridgeType.Uuid -> false
    // ROADMAP Phase 4 (ADR-151 amendment): a `ByteArray` component reads back through
    // `NugetMarshal.ReadBytes(h)` -- the per-element box IS the bytes handle -- so the property
    // projection can spell it (`byte[]`), matching `isBridgeableComponent`.
    BridgeType.ByteArray -> true
    // ADR-147 v1: `List<T>` is deferred, the same nesting rule `isBridgeableComponent` applies.
    is BridgeType.TypeParameter -> false
    BridgeType.Unit, is BridgeType.BoundInterface, is BridgeType.SpecializedProtocol,
    is BridgeType.RawCollection, is BridgeType.RawKSType, is BridgeType.Unsupported -> false
  }

  private fun BridgeType.unwrapNullable(): BridgeType = if (this is BridgeType.Nullable) type else this

  private fun BridgeType.wireType(): ForwardAbiWireType = when (val type = unwrapNullable()) {
    BridgeType.Unit -> ForwardAbiWireType.VOID
    BridgeType.Char -> ForwardAbiWireType.CHAR16
    // ADR-147: the boxed handle a `T` getter mints.
    // ADR-151: the handle to the Kotlin array, on both the getter and the setter side.
    BridgeType.String, is BridgeType.ObjectHandle, is BridgeType.Interface,
    is BridgeType.Collection, BridgeType.ByteArray,
    is BridgeType.TypeParameter -> ForwardAbiWireType.POINTER

    // ADR-107: the pointer to the `StableRef<NugetError>` envelope `buildError` produced, exactly
    // the value an `errorOut` slot carries.
    is BridgeType.Throwable -> ForwardAbiWireType.POINTER

    // ADR-088 / ADR-132 (2026-09-20): a bound C# interface receiver crosses as the transfer
    // GCHandle pointer, the same POINTER slot the callable route's bound-interface parameter uses.
    // Only reachable from an extension-property RECEIVER: a property *typed* as a bound interface
    // is refused by `isPlannable` before a wire type is ever asked for.
    is BridgeType.BoundInterface -> ForwardAbiWireType.POINTER

    // ADR-106: the getter ships the hex-dash text over the same runtime-owned pointer a String
    // getter uses; `inputWireType()` below overrides it to STRING on the setter side.
    BridgeType.Uuid -> ForwardAbiWireType.POINTER

    is BridgeType.Enum -> ForwardAbiWireType.INT32
    // ADR-076: wires as its own INT64 tick representation, same as a Primitive(LONG).
    // ADR-103: likewise, an INT64 of TimeSpan ticks.
    BridgeType.Instant, BridgeType.Duration -> ForwardAbiWireType.INT64
    is BridgeType.Primitive -> when (type.kind) {
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

    // ADR-014 unwraps at the boundary: the underlying's wire is used both for an extension
    // property's value-class *receiver* (ADR-075) and, since ADR-077 sub-item 2, for an ordinary
    // property declared `: SomeValueClass` (getter result and setter value alike).
    is BridgeType.ValueClass -> type.underlying.wireType()

    is BridgeType.Nullable,
    is BridgeType.Callback,
    is BridgeType.ReturnedLambda,
    is BridgeType.SpecializedProtocol,
    is BridgeType.RawKSType,
    is BridgeType.Unsupported,
    is BridgeType.RawCollection,
      -> error("Forward property planner cannot choose a wire type for $type")
  }

  private fun BridgeType.inputWireType(): ForwardAbiWireType = when (val type = unwrapNullable()) {
    // ADR-106: a Uuid setter takes the same STRING slot a String setter does.
    // ADR-201: and so does a Throwable setter, over the `"{FullName}: {Message}"` text.
    BridgeType.String, BridgeType.Uuid, is BridgeType.Throwable -> ForwardAbiWireType.STRING
    is BridgeType.ValueClass -> type.underlying.inputWireType()
    BridgeType.Unit,
    is BridgeType.Primitive,
    BridgeType.Char,
    BridgeType.Instant,
    BridgeType.Duration,
    is BridgeType.Enum,
    is BridgeType.ObjectHandle,
    is BridgeType.Interface,
    is BridgeType.BoundInterface,
    BridgeType.ByteArray,
    is BridgeType.Collection,
    is BridgeType.Nullable,
    is BridgeType.Callback,
    is BridgeType.ReturnedLambda,
    is BridgeType.SpecializedProtocol,
    is BridgeType.RawKSType,
    is BridgeType.Unsupported,
    is BridgeType.RawCollection,
    is BridgeType.TypeParameter,
      -> wireType()
  }

  private companion object {
    /** The [BridgeType.SpecializedProtocol] name prefixes whose *class* properties a legacy route
     *  still re-emits, so [recordDropped] must stay silent about them there. Matches the prefixes
     *  `ForwardBridgeTypeClassifier` mints and `ForwardCallablePlanner.skipReason` routes. The
     *  lambda prefixes are not here: [carriesLegacyLambdaProperty] answers for those. */
    val LEGACY_ROUTED_PROTOCOLS: List<String> = listOf("flow ", "state flow ")
  }
}

/**
 * Every helper this plan's own ABI needs, unioned from the conversions already carried on its
 * transfers (the receiver slot, the setter value, the error slot) so the set cannot drift from
 * the pairing [ForwardPropertyPlan.validate] checks. Two contributions are not representable as
 * a transfer conversion, so they are read off the types:
 *
 * - the getter *result*, which the ABI carries as a bare wire with no transfer of its own, so
 *   its OUT_OF_KOTLIN conversion is asked for directly, from the same [conversion] the
 *   transfers use.
 * - a value class's underlying, since a value-class transfer only ever tags
 *   BOX/UNBOX_VALUE_CLASS and says nothing about what is inside the box. This is the
 *   receiver-side gap the plan used to have: `var Temperament.note: String` on an
 *   enum-underlying receiver needs ENUM_ORDINAL, and only the receiver's type knows that
 *   (`ForwardCallablePlanner` unions the same way over its inputs).
 */
internal fun helperRequirements(
  type: BridgeType,
  receiver: ForwardPropertyReceiver,
  getter: ForwardPropertyGetter,
  setter: ForwardPropertySetter?,
): Set<ForwardHelperRequirement> = buildSet {
  val transferred: List<ForwardConversion> = (getter.calls() + (setter?.calls() ?: emptyList()))
    .flatMap { call -> call.parameters }
    .mapNotNull { parameter -> parameter.transfer.conversion }
    .filter { conversion -> conversion != ForwardConversion.DIRECT }
  transferred.forEach { conversion -> add(conversion.helper()) }
  val result: ForwardConversion? = type.conversion(ForwardFlow.OUT_OF_KOTLIN)
  if (result != null && result != ForwardConversion.DIRECT) add(result.helper())
  addAll(type.valueClassHelpers())
  if (receiver is ForwardPropertyReceiver.Value) addAll(receiver.type.valueClassHelpers())
}

/**
 * ADR-077 sub-item 4: the value-class step plus the underlying's own helper, keyed per kind, the
 * same pairing the callable planner applies to a value-class input. Empty for everything else.
 */
internal fun BridgeType.valueClassHelpers(): Set<ForwardHelperRequirement> {
  val valueClass: BridgeType.ValueClass = unwrapNullable() as? BridgeType.ValueClass
    ?: return emptySet()
  return buildSet {
    add(ForwardHelperRequirement.VALUE_CLASS)
    if (valueClass.underlying == BridgeType.String) add(ForwardHelperRequirement.UTF8)
    if (valueClass.underlying is BridgeType.Enum) add(ForwardHelperRequirement.ENUM_ORDINAL)
  }
}

/**
 * The conversion a property's [BridgeType] needs to cross its wire in [flow]. Shared by the
 * transfers the planner builds and by [helperRequirements], so a slot's conversion and the
 * helper claimed for it can never disagree.
 */
internal fun BridgeType.conversion(flow: ForwardFlow): ForwardConversion? = when (unwrapNullable()) {
  BridgeType.String -> if (flow == ForwardFlow.INTO_KOTLIN) {
    ForwardConversion.STRING_TO_UTF8
  } else {
    ForwardConversion.UTF8_TO_STRING
  }

  is BridgeType.Enum -> if (flow == ForwardFlow.INTO_KOTLIN) {
    ForwardConversion.ORDINAL_TO_ENUM
  } else {
    ForwardConversion.ENUM_TO_ORDINAL
  }

  is BridgeType.ObjectHandle, is BridgeType.Interface -> if (flow == ForwardFlow.INTO_KOTLIN) {
    ForwardConversion.HANDLE_TO_STABLE_REF
  } else {
    ForwardConversion.STABLE_REF_TO_HANDLE
  }

  // ADR-107 / ADR-201: the envelope out; in, the managed-exception text, which only a setter
  // whose declared type can hold a `NugetManagedException` reaches (`collectionSetterOrNull`).
  is BridgeType.Throwable -> if (flow == ForwardFlow.INTO_KOTLIN) {
    ForwardConversion.STRING_TO_MANAGED_EXCEPTION
  } else {
    ForwardConversion.STABLE_REF_TO_HANDLE
  }

  is BridgeType.Collection -> if (flow == ForwardFlow.INTO_KOTLIN) {
    ForwardConversion.HANDLE_TO_COLLECTION
  } else {
    ForwardConversion.COLLECTION_TO_HANDLE
  }

  // ADR-088 / ADR-132 (2026-09-20): in only. A bound C# interface reaches this route at an
  // extension-property RECEIVER and nowhere else (`isPlannable` refuses the type itself), so the
  // OUT_OF_KOTLIN direction is unreachable rather than unimplemented.
  is BridgeType.BoundInterface -> if (flow == ForwardFlow.INTO_KOTLIN) {
    ForwardConversion.GC_HANDLE_TO_BOUND_VALUE
  } else {
    error("Forward property planner cannot marshal a bound interface out of Kotlin")
  }

  // ADR-151: the same handle wire, with the bytes helpers instead of the list ones.
  BridgeType.ByteArray -> if (flow == ForwardFlow.INTO_KOTLIN) {
    ForwardConversion.HANDLE_TO_BYTES
  } else {
    ForwardConversion.BYTES_TO_HANDLE
  }

  BridgeType.Instant -> if (flow == ForwardFlow.INTO_KOTLIN) {
    ForwardConversion.TICKS_TO_INSTANT
  } else {
    ForwardConversion.INSTANT_TO_TICKS
  }

  // ADR-106: the RFC 9562 text conversion, both directions.
  BridgeType.Uuid -> if (flow == ForwardFlow.INTO_KOTLIN) {
    ForwardConversion.STRING_TO_UUID
  } else {
    ForwardConversion.UUID_TO_STRING
  }

  BridgeType.Duration -> if (flow == ForwardFlow.INTO_KOTLIN) {
    ForwardConversion.TICKS_TO_DURATION
  } else {
    ForwardConversion.DURATION_TO_TICKS
  }

  // ADR-077 sub-item 2: without this branch the `else` silently tags the transfer DIRECT, which
  // ForwardPropertyPlan.validate() skips (a DIRECT slot needs no helper), so nothing would catch
  // it.
  is BridgeType.ValueClass -> if (flow == ForwardFlow.INTO_KOTLIN) {
    ForwardConversion.BOX_VALUE_CLASS
  } else {
    ForwardConversion.UNBOX_VALUE_CLASS
  }

  BridgeType.Unit,
  is BridgeType.Primitive,
  BridgeType.Char,
  is BridgeType.Nullable,
  is BridgeType.Callback,
  is BridgeType.ReturnedLambda,
  is BridgeType.SpecializedProtocol,
  is BridgeType.RawKSType,
  is BridgeType.Unsupported,
  is BridgeType.RawCollection,
  is BridgeType.TypeParameter,
    -> ForwardConversion.DIRECT
}

/**
 * ADR-075 amendment (2026-09-26): a setter narrower than public (`private set`, `protected set`,
 * `internal set`) is not public Kotlin API, so it is not C# API either and the property binds
 * get-only, exactly like a `val`. Written as an absence check so an explicit `set(v) { ... }` with
 * no modifier counts as public whether or not KSP reports `PUBLIC` for it. `internal` is folded in
 * even for a module-local owner: the C# consumer sits outside the Kotlin module (ObjC export does
 * the same), and for a dependency-module owner the call would not compile at all.
 */
private fun KSPropertyDeclaration.hasPublicSetter(): Boolean {
  val modifiers: Set<Modifier> = setter?.modifiers ?: return false
  return Modifier.PRIVATE !in modifiers &&
      Modifier.PROTECTED !in modifiers &&
      Modifier.INTERNAL !in modifiers
}

/**
 * The plan symbol of the extension property [prop], `pkg.Receiver.name`: the extension's own
 * package, then the receiver with its enclosing chain (ADR-133 amendment, `pkg.Aviary.Perch.x`),
 * falling back to the bare simple name for a receiver whose declaration is not a class.
 *
 * ADR-132 amendment (2026-10-04): a nullable VALUE-type receiver ([nullableValueReceiver],
 * [isNullableValueTypeReceiver]) spells its nullability, `pkg.Int?.x`, so it never shares a symbol
 * with a `val Int.x` twin. Every other receiver stays nullability-blind, which is what keeps
 * `val Cat.x` beside `val Cat?.x` one symbol and therefore the fatal `NULLABLE_RECEIVER_TWIN`.
 *
 * The ONE spelling: the planner keys the plan with it, and both renderers find the plan again
 * through [ForwardCallablePlanCatalog.extensionPropertyFor], which reads this function too.
 */
internal fun extensionPropertySymbol(
  prop: KSPropertyDeclaration,
  nullableValueReceiver: Boolean,
): String {
  val receiver: KSType = requireNotNull(prop.extensionReceiver) {
    "Forward extension property symbol requested for a non-extension ${prop.simpleName.asString()}"
  }.resolve().expandAliases()
  val receiverName: String = (receiver.declaration as? KSClassDeclaration)?.nestedCsName()
    ?: receiver.declaration.simpleName.asString()
  val nullable: String = if (nullableValueReceiver) "?" else ""
  return "${prop.packageName.asString()}.$receiverName$nullable.${prop.simpleName.asString()}"
}
