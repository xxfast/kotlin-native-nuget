package io.github.xxfast.kotlin.native.nuget.processor.forward

import io.github.xxfast.kotlin.native.nuget.processor.asCSymbol
import io.github.xxfast.kotlin.native.nuget.processor.CSHARP_RESERVED
import io.github.xxfast.kotlin.native.nuget.processor.ForwardSymbolTable
import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeParameter
import com.google.devtools.ksp.symbol.Visibility
import io.github.xxfast.kotlin.native.nuget.processor.cir.expandAliases
import com.google.devtools.ksp.symbol.KSFunction
import io.github.xxfast.kotlin.native.nuget.processor.cir.nativePrefix
import io.github.xxfast.kotlin.native.nuget.processor.cir.nestedCsName
import io.github.xxfast.kotlin.native.nuget.processor.cir.nestedInterfaceCsName
import io.github.xxfast.kotlin.native.nuget.processor.exports.isCompilerOwnedMember

/**
 * ADR-084 stage 1: the single ordered slot list for one Kotlin interface a C# class may implement.
 *
 * Both halves of the bridge factory (`pet_bridge_create`) are projected from *this* plan: the
 * Kotlin `@CName` export (`exports/InterfaceBridgeFactoryExports.kt`) and the C# bridge state class
 * (`cir/CirBridgeRenderer.kt`). The ADR-055 contract hash covers the export name and parameter
 * count but not per-slot *meaning*, so slot order drift between the two sides would be a silent ABI
 * bug: deriving the order from one site here is the actual defense.
 *
 * Order is the interface's declared-member order: properties first, then functions, each in KSP
 * declaration order.
 */
internal enum class ForwardBridgeWire {
  UNIT,

  /** A `StableRef` handle (String today; object slots are deferred by the ADR). */
  OBJECT,
  BOOLEAN,
  ENUM,
  INT,
  LONG,
  FLOAT,
  DOUBLE,
}

internal data class ForwardBridgeType(
  val wire: ForwardBridgeWire,
  /** Kotlin source text for the `override` declaration, e.g. `String?`. */
  val kotlin: String,
  /** C# source text for the implementing member, e.g. `string?`. */
  val csharp: String,
  val nullable: Boolean = false,
  /**
   * ADR-201 amendment: set when the slot type is a `Throwable` (an [ForwardBridgeWire.OBJECT]
   * slot): a parameter crosses as the ADR-107 envelope, a result as the managed-exception text.
   */
  val throwable: BridgeType.Throwable? = null,
  /**
   * ADR-222: the interface's own `T` (or `T?`), boxed on the [ForwardBridgeWire.OBJECT] wire as
   * ADR-215 lane A boxes a lambda's `T`: `NugetHandles.retain` out, `WrapOwned<T>` back.
   */
  val typeParameter: Boolean = false,
  /**
   * ADR-232: a `StateFlow`, `SharedFlow` or mutable form, crossing as the Kotlin flow object's own
   * handle on the OBJECT wire: retained by C# (`NugetHotFlowNative.Retain`), read and released here.
   */
  val hotFlow: Boolean = false,
  /**
   * ADR-233: every other classifier-admitted type (a class or generic class handle, an interface,
   * a collection, a value class, a nullable primitive or enum, `Char`, `Instant`, `Duration`,
   * `Uuid`, `ByteArray`), crossing on the OBJECT wire as one owned box: Kotlin retains the value it
   * hands C# (C# reads and releases it with the plan's own read), and C# hands back an owned box
   * that Kotlin reads with the plan's own lowering and releases.
   */
  val boxed: BridgeType? = null,
  /** ADR-233: a star-projected `Flow<*>` element: `kotlin.Any?` here, `object?` in C#. */
  val star: Boolean = false,
)

internal data class ForwardBridgeParameter(
  val name: String,
  val type: ForwardBridgeType,
)

/**
 * What one slot forwards to: a member function, or one accessor of a property. ADR-084 amendment
 * (2026-10-22): a `var` gets a [SETTER] slot, `(value, ctx) -> Unit`, directly after its [GETTER].
 */
internal enum class ForwardBridgeSlotKind {
  FUNCTION,
  GETTER,
  SETTER,
}

/**
 * ADR-232: how a slot's member crosses. [SYNC] is the ADR-084 call; [SUSPEND] is an ADR-210 Begin
 * and End pair over the implementation's `Task`, the Begin delegate registered once per bridge; a
 * [FLOW] slot returns the key of a registered enumeration factory over the implementation's
 * `KotlinFlow<T>`, which Kotlin reads through the runtime's `flowForKotlin`.
 */
internal enum class ForwardBridgeAsync {
  SYNC,
  SUSPEND,
  FLOW,
}

internal data class ForwardBridgeSlot(
  /** Kotlin member name (`speak`), also the slot's variable prefix. */
  val name: String,
  /** C# member name (`Speak`). */
  val csName: String,
  val kind: ForwardBridgeSlotKind,
  val result: ForwardBridgeType,
  val parameters: List<ForwardBridgeParameter>,
  /**
   * ADR-090 amendment (2026-09-26): `""` for the first function slot of a name, `_$n` for the
   * n-th (`speak_2`). Internal ABI naming only; [name] stays the Kotlin `override fun` name.
   * Counted over the whole slot walk, inherited members included, so it need not match the
   * export suffix.
   */
  val overloadSuffix: String = "",
  /**
   * `_$n` when another slot, or a name the route's own generated code declares, already holds this
   * slot's prefix ([withUniqueSlotPrefixes]); `""` otherwise.
   */
  val uniqueSuffix: String = "",
  /** ADR-232: a `suspend` member or a `Flow` result; [SYNC] for every other slot. */
  val async: ForwardBridgeAsync = ForwardBridgeAsync.SYNC,
  /** ADR-232: a [ForwardBridgeAsync.FLOW] slot's element type, null for every other slot. */
  val element: ForwardBridgeType? = null,
  /**
   * ADR-237: the member's own type parameters as its Kotlin `override` restates them (`R`,
   * `R : Pet`), erased in C#. Empty for every member that declares none.
   */
  val typeParameters: List<String> = emptyList(),
) {
  /** A getter or a setter slot: forwards to a property, not a member function. */
  val isProperty: Boolean = kind != ForwardBridgeSlotKind.FUNCTION

  /**
   * `nameGetPtr` / `nameSetPtr` / `speakPtr` / `speak_2Ptr`: the ABI parameter prefix, shared by
   * both projections.
   */
  val slotPrefix: String =
    // `asCSymbol`: a `@CSharpName`d backticked member (`tug hard`) slots as `tug_hardPtr`.
    when (kind) {
      ForwardBridgeSlotKind.GETTER -> "${name.asCSymbol()}Get"
      ForwardBridgeSlotKind.SETTER -> "${name.asCSymbol()}Set"
      ForwardBridgeSlotKind.FUNCTION -> "${name.asCSymbol()}$overloadSuffix"
    } + uniqueSuffix
}

/**
 * The names the ADR-084 bridge factory's generated code declares beside its slot-derived ones: the
 * release pair (`releasePtr`, `releaseCtx`, `releaseFn`) and the C# `Create` method's parameter and
 * locals. The C# half declares each slot's delegate under the bare prefix, so a C# keyword is
 * reserved too. A slot lambda's own locals (`result`, `value0`) may shadow a delegate, so they are
 * not.
 */
internal val BRIDGE_FACTORY_FIXED_NAMES: Set<String> =
  setOf("release", "token", "state", "impl", "error") + CSHARP_RESERVED

/**
 * [this] with every [ForwardBridgeSlot.slotPrefix] unique among the slots and absent from [fixed].
 *
 * `val name` and `fun nameGet()` both had the prefix `nameGet`, so the export declared `nameGetPtr`
 * twice (a Kotlin `Conflicting declarations` error) and the C# import did the same. Function slots
 * claim first, in order, then property slots, so on both routes it is the getter slot that moves
 * (the ADR-039 pair's function slots are named by [fixed]). A later claimant takes the smallest
 * `_2`, `_3`, ... that no slot and no fixed name holds. A shape with no clash keeps every prefix.
 */
internal fun List<ForwardBridgeSlot>.withUniqueSlotPrefixes(
  fixed: Set<String>,
): List<ForwardBridgeSlot> {
  val reserved: Set<String> = fixed + map { slot -> slot.slotPrefix }
  val claimed: MutableSet<String> = fixed.toMutableSet()
  val unique: MutableList<ForwardBridgeSlot> = toMutableList()
  indices.sortedBy { index -> if (this[index].isProperty) 1 else 0 }.forEach { index ->
    val slot: ForwardBridgeSlot = this[index]
    if (claimed.add(slot.slotPrefix)) return@forEach
    val ordinal: Int = generateSequence(2) { n -> n + 1 }.first { n ->
      val candidate: String = "${slot.slotPrefix}_$n"
      candidate !in reserved && candidate !in claimed
    }
    val renamed: ForwardBridgeSlot = slot.copy(uniqueSuffix = "_$ordinal")
    claimed.add(renamed.slotPrefix)
    unique[index] = renamed
  }
  return unique
}

internal data class ForwardBridgeInterfacePlan(
  /** ADR-117 amendment: the interface itself, so the bridge factory export names its owner. */
  val declaration: KSClassDeclaration,
  val qualifiedName: String,
  val simpleName: String,
  /** The projected C# interface name (`IPet`). */
  val csName: String,
  /** `pet_bridge_create`. */
  val exportName: String,
  /** `PetBridgeState`, or `AviaryKeeperBridgeState` for a nested interface (ADR-133). */
  val stateClassName: String,
  val slots: List<ForwardBridgeSlot>,
)

/**
 * The `is` pattern variable `NugetBridge.HandleFor` binds this interface's implementation to
 * (`petImpl`, `aviaryKeeperImpl`). Derived from [ForwardBridgeInterfacePlan.stateClassName] rather
 * than from the simple name so the two cannot drift: every arm of `HandleFor` lives in ONE C#
 * block, so two owners' same-simple-name nested interfaces declared `keeperImpl` twice (CS0128).
 */
internal fun ForwardBridgeInterfacePlan.bridgeImplVariable(): String =
  stateClassName.removeSuffix("BridgeState").lowercase() + "Impl"

/**
 * ADR-084: one member (or the interface itself) that keeps an interface from planning a bridge,
 * named by [unimplementableInterfaceDiagnostic] so a null plan is never silent.
 */
internal data class ForwardBridgeRefusal(
  /** The member as declared (`var mood: String`, `suspend fun fetch`), quoted in the message. */
  val member: String,
  /** Why, as a predicate of [member]: "is a `var`, and ...". */
  val reason: String,
  /** The remedy for this member alone. */
  val hint: String,
  /** The member's simple name, for ADR-201's `<interface>.<member>` declaration. */
  val name: String = "",
  /**
   * ADR-201 amendment: the declared spelling (`IllegalStateException?`) when the refusal is a
   * result narrower than `RuntimeException`, which keeps its shipped `SKIPPED_UNSUPPORTED_RETURN`;
   * null for every other refusal.
   */
  val narrowThrowable: String? = null,
)

/** What the one planner walk concludes for an interface: a plan, or every reason there is none. */
internal sealed interface ForwardBridgePlanOutcome {
  data class Planned(val plan: ForwardBridgeInterfacePlan) : ForwardBridgePlanOutcome

  data class Refused(val refusals: List<ForwardBridgeRefusal>) : ForwardBridgePlanOutcome
}

/** One member's half of the walk. */
private sealed interface ForwardBridgeSlotOutcome {
  /** [setter] is a `var`'s second slot, ordered directly after [slot], its getter. */
  data class Slot(
    val slot: ForwardBridgeSlot,
    val setter: ForwardBridgeSlot? = null,
  ) : ForwardBridgeSlotOutcome

  data class Refused(val refusal: ForwardBridgeRefusal) : ForwardBridgeSlotOutcome
}

/**
 * The vocabulary a slot carries, for every "cannot carry" remedy. ADR-233: every type an ordinary
 * class member takes or returns.
 */
private const val CARRIED_TYPES: String = "the types an ordinary class member takes and returns"

/**
 * ADR-084 / ADR-064: the named skip for a reachable interface whose walk refused, naming every
 * disqualifying member with its reason and remedy. The interface is the declaration, not a member:
 * `I<Name>` still declares every member (ADR-040), and what is skipped is the bridge a C#
 * implementation would cross through, so `owner` is null and no `<remarks>` is attached.
 */
internal fun unimplementableInterfaceDiagnostic(
  symbol: KSNode?,
  qualifiedName: String,
  csName: String,
  refusals: List<ForwardBridgeRefusal>,
): ForwardDiagnostic = ForwardDiagnostic(
  kind = ForwardDiagnosticKind.SKIPPED_UNIMPLEMENTABLE_INTERFACE,
  symbol = symbol,
  declaration = qualifiedName,
  reason = "a C# class implementing `$csName` cannot be passed to Kotlin, because " +
    refusals.joinToString("; ") { refusal -> "`${refusal.member}` ${refusal.reason}" },
  hint = refusals.joinToString("; ", postfix = ". ") { refusal ->
    "for `${refusal.member}`, ${refusal.hint}"
  }.replaceFirstChar { it.uppercase() } + "Kotlin-backed `$csName` values are unaffected",
  owner = null,
)

internal object ForwardInterfaceBridgePlanner {
  /**
   * Returns the bridge plan for [iface], or `null` when any member falls outside the stage-1 slot
   * vocabulary (object/collection slots, hot flows, member type parameters). A `null`
   * plan means no factory export and no C# bridge state: `NugetMarshal.HandleOf` keeps throwing
   * for that interface rather than emitting a half-supported ABI. [planOrRefuse] says why.
   */
  fun plan(
    iface: KSClassDeclaration,
    classifier: ForwardBridgeTypeClassifier,
    /** ADR-163: the one symbol table. */
    symbols: ForwardSymbolTable,
  ): ForwardBridgeInterfacePlan? =
    (planOrRefuse(iface, classifier, symbols) as? ForwardBridgePlanOutcome.Planned)?.plan

  /**
   * The one walk behind [plan]: every member is visited, so a refusal names each disqualifying
   * member rather than the first. `null` only for a declaration that is not a named interface at
   * all, which nothing reachable is.
   */
  fun planOrRefuse(
    iface: KSClassDeclaration,
    classifier: ForwardBridgeTypeClassifier,
    symbols: ForwardSymbolTable,
  ): ForwardBridgePlanOutcome? {
    if (iface.classKind != ClassKind.INTERFACE) return null
    val qualifiedName: String = iface.qualifiedName?.asString() ?: return null
    val simpleName: String = iface.simpleName.asString()

    val slots: MutableList<ForwardBridgeSlot> = mutableListOf()
    val refusals: MutableList<ForwardBridgeRefusal> = mutableListOf()
    fun collect(outcome: ForwardBridgeSlotOutcome, add: (ForwardBridgeSlot) -> Unit) {
      when (outcome) {
        is ForwardBridgeSlotOutcome.Slot -> {
          add(outcome.slot)
          outcome.setter?.let(add)
        }
        is ForwardBridgeSlotOutcome.Refused -> refusals += outcome.refusal
      }
    }
    iface.getAllProperties()
      .filter { property -> property.getVisibility() == Visibility.PUBLIC }
      .filter { property -> !property.isCompilerOwnedMember(iface) }
      .forEach { property -> collect(slotOrRefusal(property, classifier, iface)) { slots += it } }
    // ADR-090 amendment (2026-09-26): two same-name function slots declared `speakPtr` twice in
    // the factory signature (a Kotlin `Conflicting declarations` compile error), whether both
    // overloads are declared here or one is inherited from a super-interface.
    val occurrences: MutableMap<String, Int> = mutableMapOf()
    iface.getAllFunctions()
      .filter { function -> function.getVisibility() == Visibility.PUBLIC }
      .filter { function -> !function.isCompilerOwnedMember(iface) }
      .forEach { function ->
        collect(slotOrRefusal(function, classifier, iface)) { slot ->
          val occurrence: Int = occurrences.merge(slot.name, 1, Int::plus)!!
          slots.add(if (occurrence == 1) slot else slot.copy(overloadSuffix = "_$occurrence"))
        }
      }
    if (refusals.isNotEmpty()) return ForwardBridgePlanOutcome.Refused(refusals)
    // A member-less (marker) interface plans with no slots: its factory carries only the release
    // pair and the token, and the Kotlin bridge is an empty `object : Marker`. Reading "no slots"
    // as out of scope left `HandleOf` throwing for a C# implementation at a plain parameter, while
    // the ADR-039 add/remove pair accepts the same implementation.

    return ForwardBridgePlanOutcome.Planned(
      ForwardBridgeInterfacePlan(
        declaration = iface,
        qualifiedName = qualifiedName,
        simpleName = simpleName,
        // ADR-133: `Aviary.IKeeper`, and the bridge export carries the chain like every other.
        csName = iface.nestedInterfaceCsName(),
        exportName = "${iface.nativePrefix(symbols)}_bridge_create",
        // ADR-133: the enclosing chain, flattened. Every state class is rendered into the ROOT
        // namespace's one `CirBridgeHelper`, so two owners' same-simple-name nested interfaces
        // (`Aviary.Keeper` and `Registry.Keeper`) both emitted `KeeperBridgeState`: CS0101, plus
        // two `keeperImpl` pattern variables in one `HandleFor` block (CS0128). A top-level
        // interface has no chain and keeps its shipped `PetBridgeState` byte for byte.
        // ADR-163: package-qualified too. Every state class is rendered into the ROOT namespace,
        // so two same-simple-name interfaces in two packages were CS0101 there the moment their
        // entry points stopped colliding and the build got far enough to emit both.
        stateClassName = bridgeStateName(iface, symbols),
        slots = slots.withUniqueSlotPrefixes(BRIDGE_FACTORY_FIXED_NAMES),
      ),
    )
  }

  /**
   * The slots for [property], its getter then (for a `var`) its setter, or null when it falls
   * outside the slot vocabulary. Also read by the ADR-039 `add*`/`remove*` subscription route
   * ([listenerPropertySlots]), so a listener property crosses there exactly as it does on this
   * factory.
   */
  internal fun slotsOf(
    property: KSPropertyDeclaration,
    classifier: ForwardBridgeTypeClassifier,
  ): List<ForwardBridgeSlot>? {
    val outcome = slotOrRefusal(property, classifier) as? ForwardBridgeSlotOutcome.Slot
      ?: return null
    // ADR-232: a Flow getter needs the factory's shared async thunks, which the ADR-039
    // subscription route does not cross.
    if (outcome.slot.async != ForwardBridgeAsync.SYNC) return null
    return listOfNotNull(outcome.slot, outcome.setter)
  }

  private fun slotOrRefusal(
    property: KSPropertyDeclaration,
    classifier: ForwardBridgeTypeClassifier,
    owner: KSClassDeclaration? = null,
  ): ForwardBridgeSlotOutcome {
    val name: String = property.simpleName.asString()
    val type: KSType = inherited(owner, property)
      ?.let { owned -> property.asMemberOf(owned) }?.expandAliases()
      ?: property.type.resolve().expandAliases()
    // Spelled, never the raw `KSType`: that prints a function type by its class name
    // (`SuspendFunction1<Int, String>`), which is not what the author wrote.
    val spelled: String = type.kotlinSpelling(keepProjections = true)
    val member: String = "${if (property.isMutable) "var" else "val"} $name: $spelled"
    fun refuse(reason: String, hint: String): ForwardBridgeSlotOutcome =
      ForwardBridgeSlotOutcome.Refused(ForwardBridgeRefusal(member, reason, hint, name))
    // A backticked `slack line` with no `@CSharpName` has no C# member to implement.
    if (!property.hasBridgeableName()) {
      return refuse("has no C# member name to implement", "give it a `@CSharpName`")
    }
    // ADR-232: a `val` of `Flow<T>` is a FLOW getter slot, read exactly as a `Flow` function.
    if (type.isFlowType()) {
      val outcome: FlowSlotOutcome = flowResult(type, classifier, owner)
      if (outcome is FlowSlotOutcome.Refused) return refuse(outcome.reason, outcome.hint)
      val getter = ForwardBridgeSlot(
        name = name,
        csName = property.csharpMemberName(),
        kind = ForwardBridgeSlotKind.GETTER,
        result = ForwardBridgeType(ForwardBridgeWire.UNIT, "Unit", "void"),
        parameters = emptyList(),
      )
      val read: ForwardBridgeSlot = when (outcome) {
        is FlowSlotOutcome.Hot -> getter.copy(result = outcome.result)
        is FlowSlotOutcome.Element -> getter.copy(
          result = flowSlotResult(outcome.result, outcome.nullable),
          async = ForwardBridgeAsync.FLOW,
          element = outcome.result,
        )
        is FlowSlotOutcome.Refused -> error("refused above")
      }
      // ADR-233: a `var` of a flow takes the Kotlin flow as its handle, which C# reads as the
      // `KotlinFlow` a class member returning that flow hands out.
      val setter: ForwardBridgeSlot? = if (property.isMutable) {
        // ADR-233 amendment: C# materialises that handle through the closed flow argument's
        // factory entry, which no other position may have sighted (`Flow<Paw>` only here).
        val declaring: KSClassDeclaration? = property.parentDeclaration as? KSClassDeclaration
        if (outcome is FlowSlotOutcome.Element && declaring != null) {
          val reading: ForwardFlowArgumentReading =
            classifier.flowArgument(type.makeNotNullable(), owner ?: declaring)
          if (reading is ForwardFlowArgumentReading.Bound) {
            classifier.closedFlowArguments.getOrPut(reading.argument.csharpType) { reading.argument }
          }
        }
        val value = ForwardBridgeType(
          ForwardBridgeWire.OBJECT, read.result.kotlin, read.result.csharp, read.result.nullable,
        )
        getter.copy(
          kind = ForwardBridgeSlotKind.SETTER,
          parameters = listOf(ForwardBridgeParameter("value", value)),
        )
      } else {
        null
      }
      return ForwardBridgeSlotOutcome.Slot(read, setter)
    }
    val result: ForwardBridgeType = bridgeType(type, classifier, owner)
      ?: return refuse("has a type the bridge cannot carry", "use one of $CARRIED_TYPES")
    narrowThrowableOf(result)?.let { declared -> return refuseNarrow(member, name, declared) }
    if (result.wire == ForwardBridgeWire.UNIT) {
      return refuse("is `Unit`, which carries no value", "give it a value type or remove it")
    }
    val getter = ForwardBridgeSlot(
      name = name,
      csName = property.csharpMemberName(),
      kind = ForwardBridgeSlotKind.GETTER,
      result = result,
      parameters = emptyList(),
    )
    // ADR-084 amendment (2026-10-22): the setter carries the value exactly as a one-parameter
    // function slot of the getter's type does, so both halves reuse that parameter marshalling.
    val setter: ForwardBridgeSlot? = if (property.isMutable) {
      getter.copy(
        kind = ForwardBridgeSlotKind.SETTER,
        result = ForwardBridgeType(ForwardBridgeWire.UNIT, "Unit", "void"),
        parameters = listOf(ForwardBridgeParameter("value", result)),
      )
    } else {
      null
    }
    return ForwardBridgeSlotOutcome.Slot(getter, setter)
  }

  private fun slotOrRefusal(
    function: KSFunctionDeclaration,
    classifier: ForwardBridgeTypeClassifier,
    owner: KSClassDeclaration? = null,
  ): ForwardBridgeSlotOutcome {
    val name: String = function.simpleName.asString()
    val suspend: Boolean = function.modifiers.any { modifier -> modifier.name == "SUSPEND" }
    val member: String = "${if (suspend) "suspend " else ""}fun $name"
    fun refuse(reason: String, hint: String): ForwardBridgeSlotOutcome =
      ForwardBridgeSlotOutcome.Refused(ForwardBridgeRefusal(member, reason, hint, name))
    if (!function.hasBridgeableName()) {
      return refuse("has no C# member name to implement", "give it a `@CSharpName`")
    }
    // ADR-237: a member's own type parameters are erased, `object?` (or the one bound) in C#.
    val erasedRefusal: String? = function.forwardErasedMemberRefusal()
    if (function.typeParameters.isNotEmpty() && erasedRefusal != null) {
      return refuse(erasedRefusal, "give it one bound or none")
    }
    // ADR-233: any arity: each parameter is one more slot argument, on its own wire.
    val substituted: KSFunction? =
      inherited(owner, function)?.let { owned -> function.asMemberOf(owned) }
    val returnType: KSType =
      (substituted?.returnType ?: function.returnType?.resolve())?.expandAliases()
      ?: return refuse("has no resolvable result type", "declare its result type")
    // ADR-232: a `Flow` result is a FLOW slot; a suspend member returning one awaits the C# task,
    // whose End thunk registers the flow it completed with.
    var hotResult: ForwardBridgeType? = null
    var flowNullable = false
    val flow: ForwardBridgeType? = if (returnType.isFlowType()) {
      when (val outcome: FlowSlotOutcome = flowResult(returnType, classifier, owner)) {
        is FlowSlotOutcome.Element -> {
          flowNullable = outcome.nullable
          outcome.result
        }
        is FlowSlotOutcome.Hot -> {
          hotResult = outcome.result
          null
        }
        is FlowSlotOutcome.Refused -> return refuse(outcome.reason, outcome.hint)
      }
    } else {
      null
    }
    // ADR-233 amendment: a suspend member returning a Flow of the interface's own `T` starts
    // through `BeginFlow`, whose task the non-generic boxed End thunk registers without naming `T`.
    val result: ForwardBridgeType = hotResult ?: flow?.let { flowSlotResult(it, flowNullable) }
      ?: bridgeType(returnType, classifier, owner)
      ?: return refuse(
        "returns `${returnType.kotlinSpelling(keepProjections = true)}`, a type the bridge " +
            "cannot carry",
        "return one of $CARRIED_TYPES",
      )
    val parameters: MutableList<ForwardBridgeParameter> = mutableListOf()
    function.parameters.forEachIndexed { index, parameter ->
      val parameterName: String = parameter.name?.asString() ?: "arg$index"
      val declared: KSType =
        (substituted?.parameterTypes?.getOrNull(index) ?: parameter.type.resolve()).expandAliases()
      val type: ForwardBridgeType = bridgeType(declared, classifier, owner, parameter = true)
        ?.takeIf { it.wire != ForwardBridgeWire.UNIT }
        ?: return refuse(
          "takes `$parameterName: ${declared.kotlinSpelling(keepProjections = true)}`, a type " +
              "the bridge cannot carry",
          "take one of $CARRIED_TYPES",
        )
      parameters += ForwardBridgeParameter(parameterName, type)
    }
    // Checked after the parameters, so a member with a second, unrelated refusal is named for
    // that one: ADR-201's code covers only the shape where the Throwable result is the whole
    // reason.
    narrowThrowableOf(result)?.let { declared -> return refuseNarrow(member, name, declared) }
    return ForwardBridgeSlotOutcome.Slot(
      ForwardBridgeSlot(
        name = name,
        csName = if (suspend) function.csharpAsyncMemberName() else function.csharpMemberName(),
        kind = ForwardBridgeSlotKind.FUNCTION,
        result = result,
        parameters = parameters,
        async = when {
          suspend -> ForwardBridgeAsync.SUSPEND
          flow != null -> ForwardBridgeAsync.FLOW
          else -> ForwardBridgeAsync.SYNC
        },
        element = flow,
        typeParameters = function.typeParameters.map { parameter ->
          val bound: String? = parameter.forwardBoundSpellings().firstOrNull()
          parameter.name.asString() + (bound?.let { ": $it" } ?: "")
        },
      ),
    )
  }

  private sealed interface FlowSlotOutcome {
    data class Element(
      val result: ForwardBridgeType,
      val nullable: Boolean = false,
    ) : FlowSlotOutcome


    data class Refused(val reason: String, val hint: String) : FlowSlotOutcome

    /** ADR-232: a hot flow, the whole slot result. */
    data class Hot(val result: ForwardBridgeType) : FlowSlotOutcome
  }

  /**
   * ADR-232: the element of a `Flow<T>` member, in the vocabulary ADR-210's `flowForKotlin`
   * lowering reads (a non-null `String`, `Boolean`, `Int`, `Long`, `Float`, `Double`, or the
   * interface's own `T`). `StateFlow`, `SharedFlow` and their mutable forms keep ADR-210's named
   * refusal: a C# implementation has no Kotlin flow handle to hand back for one.
   */
  private fun flowResult(
    type: KSType,
    classifier: ForwardBridgeTypeClassifier,
    owner: KSClassDeclaration?,
  ): FlowSlotOutcome {
    val qualifiedName: String? = type.declaration.qualifiedName?.asString()
    val hot: String? = HOT_FLOWS[qualifiedName]
    // ADR-233: a nullable flow is the null pointer on the same wire.
    val mark: String = if (type.isMarkedNullable) "?" else ""
    if (qualifiedName != "kotlinx.coroutines.flow.Flow" && hot == null) {
      return FlowSlotOutcome.Refused(
        "is a `${type.kotlinSpelling(keepProjections = true)}`, " +
          "a flow type the bridge cannot carry",
        "declare it a `Flow<T>`, `StateFlow<T>`, `SharedFlow<T>` or a mutable form",
      )
    }
    val projection = type.arguments.singleOrNull()
    val argument: KSType? = projection?.type?.resolve()?.expandAliases()
      ?.takeIf { projection.variance != com.google.devtools.ksp.symbol.Variance.STAR }
    // ADR-233: a star-projected element is whatever the C# value is at run time, boxed.
    val element: ForwardBridgeType = if (argument == null) {
      ForwardBridgeType(
        ForwardBridgeWire.OBJECT, "kotlin.Any?", "object?", nullable = true, star = true,
      )
    } else {
      bridgeType(argument, classifier, owner)
        ?.takeIf { element -> element.typeParameter || !element.nullable }
        // ADR-233 amendment: a non-null exception element rides the managed-exception text.
        ?.takeIf { element -> element.boxed == null }
        ?.takeIf { element ->
          element.wire != ForwardBridgeWire.UNIT && element.wire != ForwardBridgeWire.ENUM
        }
        ?: boxedElement(argument, classifier)
        ?: return FlowSlotOutcome.Refused(
          "is a Flow of `${argument.kotlinSpelling(keepProjections = true)}`, an element the " +
            "bridge cannot carry",
          "use a Flow of a type a class member can return",
        )
    }
    if (hot != null) {
      // ADR-233: a mutable hot flow whose element has no ADR-071 write arm is spelled read-only,
      // as the interface member itself is.
      val spelled: String =
        if (element.boxed?.hasHotWriteArm() == false) hot.replace("Mutable", "") else hot
      return FlowSlotOutcome.Hot(
        ForwardBridgeType(
          ForwardBridgeWire.OBJECT,
          "$qualifiedName<${element.kotlin}>$mark",
          "$spelled<${element.csharp}>$mark",
          nullable = type.isMarkedNullable,
          hotFlow = true,
        ),
      )
    }
    return FlowSlotOutcome.Element(element, type.isMarkedNullable)
  }

  /**
   * ADR-233: a Flow element outside the unboxed `String` / primitive / own `T` set, read per
   * element as the owned box `CurrentBoxed` mints from its run-time value, then cast to its
   * declared type.
   */
  private fun boxedElement(
    argument: KSType,
    classifier: ForwardBridgeTypeClassifier,
  ): ForwardBridgeType? {
    val classified: BridgeType = classifier.classify(argument)
    if (!classified.isSlotBoxable()) return null
    return ForwardBridgeType(
      ForwardBridgeWire.OBJECT,
      argument.forwardKotlinDeclaredSpelling(),
      classified.forwardPublicCsharpType(),
      nullable = argument.isMarkedNullable,
      boxed = classified,
    )
  }

  /** ADR-232: each hot flow type, to the C# wrapper whose handle its slot hands back. */
  private val HOT_FLOWS: Map<String?, String> = mapOf(
    "kotlinx.coroutines.flow.StateFlow" to "KotlinStateFlow",
    "kotlinx.coroutines.flow.MutableStateFlow" to "KotlinMutableStateFlow",
    "kotlinx.coroutines.flow.SharedFlow" to "KotlinSharedFlow",
    "kotlinx.coroutines.flow.MutableSharedFlow" to "KotlinMutableSharedFlow",
  )

  /** ADR-232: a FLOW slot crosses its enumeration factory's key on the OBJECT wire. */
  private fun flowSlotResult(
    element: ForwardBridgeType,
    nullable: Boolean = false,
  ): ForwardBridgeType =
    ForwardBridgeType(
      ForwardBridgeWire.OBJECT,
      "kotlinx.coroutines.flow.Flow<${element.kotlin}>${if (nullable) "?" else ""}",
      "KotlinFlow<${element.csharp}>${if (nullable) "?" else ""}",
      nullable = nullable,
    )

  /**
   * ADR-233: the planned interface as a type when [member] is inherited from a super-interface, so
   * its slot types are the substituted ones (`Hamper : Basket<String>` reads `item: String`). Null
   * for a member declared on the interface itself, and for a generic interface, whose own type
   * parameters ride the ADR-222 box.
   */
  private fun inherited(owner: KSClassDeclaration?, member: KSDeclaration): KSType? {
    if (owner == null || owner.typeParameters.isNotEmpty()) return null
    val declared: String? = member.parentDeclaration?.qualifiedName?.asString()
    if (declared == owner.qualifiedName?.asString()) return null
    return owner.asType(emptyList())
  }

  private fun refuseNarrow(
    member: String,
    name: String,
    declared: String,
  ): ForwardBridgeSlotOutcome =
    ForwardBridgeSlotOutcome.Refused(
      ForwardBridgeRefusal(
        member = member,
        reason = "is declared `$declared`, which cannot hold the `NugetManagedException` a C# " +
          "exception arrives as",
        hint = "declare it Throwable, Exception or RuntimeException",
        name = name,
        narrowThrowable = declared,
      ),
    )

  /**
   * ADR-201 amendment: a slot RESULT is a value C# hands Kotlin, which arrives as a
   * `NugetManagedException`; a result declared narrower than `RuntimeException` cannot hold one.
   * The declared spelling (`IllegalStateException?`) when so, else null.
   */
  private fun narrowThrowableOf(result: ForwardBridgeType): String? {
    val throwable: BridgeType.Throwable = result.throwable
      ?.takeIf { it.acceptsManagedException == false }
      ?: return null
    return throwable.kotlinType.substringAfterLast('.') + if (result.nullable) "?" else ""
  }

  /** ADR-174: a `Flow`/`StateFlow` member is async, named as such rather than as a bad type. */
  private fun KSType.isFlowType(): Boolean =
    declaration.qualifiedName?.asString()?.startsWith("kotlinx.coroutines.flow.") == true

  /**
   * ADR-201 amendment: the member whose narrower `Throwable` result alone keeps an interface from
   * planning a bridge, as `member` to its declared spelling (`last` to `IllegalStateException?`),
   * or null. Only when every refusal is that one: a `var`, a suspend member or any other
   * out-of-vocabulary slot is named by `SKIPPED_UNIMPLEMENTABLE_INTERFACE` instead.
   */
  internal fun throwableRefusal(refused: ForwardBridgePlanOutcome.Refused): Pair<String, String>? {
    if (refused.refusals.any { refusal -> refusal.narrowThrowable == null }) return null
    val first: ForwardBridgeRefusal = refused.refusals.first()
    return first.name to requireNotNull(first.narrowThrowable)
  }

  private fun bridgeType(
    type: KSType,
    classifier: ForwardBridgeTypeClassifier,
    owner: KSClassDeclaration?,
    // ADR-233: a function slot's parameter, a value Kotlin hands C#; a lambda there is a Kotlin
    // lambda, read by C# as the plan reads a returned lambda.
    parameter: Boolean = false,
  ): ForwardBridgeType? {
    if (parameter) lambdaParameterOrNull(type, classifier)?.let { lambda -> return lambda }
    else lambdaResultOrNull(type, classifier)?.let { lambda -> return lambda }
    // ADR-222: the planned interface's OWN type parameter rides lane A's box. An inherited
    // super-interface `T` is not substituted, so it stays out of the vocabulary as before.
    // ADR-237: a member's own erased `R` boxes as `object?`, or as its one bound.
    if ((type.declaration as? KSTypeParameter)?.parentDeclaration is KSFunctionDeclaration) {
      return boxedOrNull(type, classifier)
    }
    val typeParameter: KSTypeParameter? = type.declaration as? KSTypeParameter
    if (typeParameter != null) {
      val declaredBy: String? = typeParameter.parentDeclaration?.qualifiedName?.asString()
      if (owner == null || declaredBy != owner.qualifiedName?.asString()) return null
      val mark: String = if (type.isMarkedNullable) "?" else ""
      return ForwardBridgeType(
        ForwardBridgeWire.OBJECT,
        typeParameter.name.asString() + mark,
        owner.forwardCsharpTypeParameterName(typeParameter) + mark,
        nullable = type.isMarkedNullable || typeParameter.hasNullableBound(),
        typeParameter = true,
      )
    }
    val qualifiedName: String = type.declaration.qualifiedName?.asString() ?: return null
    val nullable: Boolean = type.isMarkedNullable
    // ADR-201 amendment: a `Throwable` rides the OBJECT wire: the envelope out of Kotlin, the
    // managed-exception text into it. Asked of the classifier, so an exported exception class keeps
    // its handle classification (and stays out of this vocabulary).
    val throwable: BridgeType.Throwable? =
      classifier.classify(type).let { if (it is BridgeType.Nullable) it.type else it } as?
        BridgeType.Throwable
    if (throwable != null) {
      return ForwardBridgeType(
        ForwardBridgeWire.OBJECT,
        if (nullable) "$qualifiedName?" else qualifiedName,
        if (nullable) "global::System.Exception?" else "global::System.Exception",
        nullable,
        throwable,
      )
    }
    val isEnum: Boolean = (type.declaration as? KSClassDeclaration)?.classKind == ClassKind.ENUM_CLASS
    if (isEnum) {
      // ADR-233: a nullable enum has no sentinel on an `int` wire, so it rides the box.
      if (nullable) return boxedOrNull(type, classifier)
      // The enum's C# spelling is the classifier's to make, never this planner's: a bare simple
      // name names nothing for a nested enum (declared under its owner, `IKettle.Whistle`) and
      // resolves only by luck for one outside the file's `using` list. An undeclared enum (nested
      // under an owner ADR-133 still defers, dropped by its CS0102 collision gate, or out of scope)
      // plans no factory, exactly the ADR-084 posture for every other out-of-scope member.
      val classified: BridgeType = classifier.classify(type)
      if (classified !is BridgeType.Enum) return null
      // Kotlin reads the qualified name: valid at every `override` position, and the generated
      // file imports nothing for it.
      return ForwardBridgeType(ForwardBridgeWire.ENUM, qualifiedName, classified.csharpType)
    }
    return when (qualifiedName) {
      "kotlin.Unit" ->
        if (nullable) null
        else ForwardBridgeType(ForwardBridgeWire.UNIT, "Unit", "void")
      "kotlin.String" -> ForwardBridgeType(
        ForwardBridgeWire.OBJECT,
        if (nullable) "String?" else "String",
        if (nullable) "string?" else "string",
        nullable,
      )

      "kotlin.Boolean" ->
        if (nullable) boxedOrNull(type, classifier)
        else ForwardBridgeType(ForwardBridgeWire.BOOLEAN, "Boolean", "bool")
      "kotlin.Int" ->
        if (nullable) boxedOrNull(type, classifier)
        else ForwardBridgeType(ForwardBridgeWire.INT, "Int", "int")
      "kotlin.Long" ->
        if (nullable) boxedOrNull(type, classifier)
        else ForwardBridgeType(ForwardBridgeWire.LONG, "Long", "long")
      "kotlin.Float" ->
        if (nullable) boxedOrNull(type, classifier)
        else ForwardBridgeType(ForwardBridgeWire.FLOAT, "Float", "float")
      "kotlin.Double" ->
        if (nullable) boxedOrNull(type, classifier)
        else ForwardBridgeType(ForwardBridgeWire.DOUBLE, "Double", "double")
      else -> boxedOrNull(type, classifier)
    }
  }

  /**
   * ADR-233: a Kotlin lambda (plain or `suspend`) handed to a C# slot, boxed as itself. C# wraps
   * the box in the plan's own `KotlinFunc` / `KotlinSuspendFunc`, whose `Invoke` / `InvokeAsync`
   * is the delegate the implementation receives.
   */
  private fun lambdaParameterOrNull(
    type: KSType,
    classifier: ForwardBridgeTypeClassifier,
  ): ForwardBridgeType? {
    val lambda: BridgeType = classifier.asyncCallbackOrNull(type)
      ?: classifier.returnedLambdaOrNull(type)?.takeIf { it.unnameableTypeArgument() == null }
      ?: return null
    val arguments: List<KSType> = type.arguments.map { argument ->
      argument.type?.resolve()?.expandAliases() ?: return null
    }
    val spelled: List<String> = arguments.map { it.forwardKotlinDeclaredSpelling() }
    val prefix: String = if (lambda is BridgeType.AsyncCallback) "suspend " else ""
    val kotlin = "$prefix(${spelled.dropLast(1).joinToString(", ")}) -> ${spelled.last()}"
    val csharp: String = if (lambda is BridgeType.ReturnedLambda) {
      BridgeType.Callback(lambda.typeArguments.dropLast(1), lambda.typeArguments.last())
        .forwardPublicCsharpType()
    } else {
      lambda.forwardPublicCsharpType()
    }
    return ForwardBridgeType(ForwardBridgeWire.OBJECT, kotlin, csharp, boxed = lambda)
  }

  /**
   * ADR-233: a C# lambda a slot hands back, crossing as the ADR-160 callback a class member's
   * lambda parameter crosses as: C# registers its delegate and returns the key, and Kotlin calls it
   * through the delegate's thunk ([ForwardBridgeSlot.lambdaThunkParameter]) for as long as the
   * Kotlin lambda lives; a holder's cleaner hands the key back.
   */
  private fun lambdaResultOrNull(
    type: KSType,
    classifier: ForwardBridgeTypeClassifier,
  ): ForwardBridgeType? {
    val callback: BridgeType.Callback =
      classifier.classify(type) as? BridgeType.Callback ?: return null
    val spelled: List<String> = type.arguments.map { argument ->
      argument.type?.resolve()?.expandAliases()?.forwardKotlinDeclaredSpelling() ?: return null
    }
    val kotlin = "(${spelled.dropLast(1).joinToString(", ")}) -> ${spelled.last()}"
    return ForwardBridgeType(
      ForwardBridgeWire.OBJECT, kotlin, callback.forwardPublicCsharpType(), boxed = callback,
    )
  }

  /**
   * ADR-233: the slot wire is the plan's wire. Every type the classifier admits at a member
   * position and the nine-entry vocabulary above does not carry rides the OBJECT wire as one box,
   * read and written with the same projections a class member of that type uses.
   */
  private fun boxedOrNull(
    type: KSType,
    classifier: ForwardBridgeTypeClassifier,
  ): ForwardBridgeType? {
    val classified: BridgeType = classifier.classify(type)
    if (!classified.isSlotBoxable()) return null
    return ForwardBridgeType(
      ForwardBridgeWire.OBJECT,
      type.forwardKotlinDeclaredSpelling(),
      classified.forwardPublicCsharpType(),
      nullable = type.isMarkedNullable || classified is BridgeType.Nullable,
      boxed = classified,
    )
  }
}

/** The Kotlin `CFunction` type text this wire crosses on. */
internal fun ForwardBridgeWire.kotlinWire(): String = when (this) {
  ForwardBridgeWire.UNIT -> "Unit"
  ForwardBridgeWire.OBJECT -> "COpaquePointer?"
  ForwardBridgeWire.BOOLEAN -> "Byte"
  ForwardBridgeWire.ENUM, ForwardBridgeWire.INT -> "Int"
  ForwardBridgeWire.LONG -> "Long"
  ForwardBridgeWire.FLOAT -> "Float"
  ForwardBridgeWire.DOUBLE -> "Double"
}

/** The C# delegate type text this wire crosses on. */
internal fun ForwardBridgeWire.csharpWire(): String = when (this) {
  ForwardBridgeWire.UNIT -> "void"
  ForwardBridgeWire.OBJECT -> "IntPtr"
  ForwardBridgeWire.BOOLEAN -> "byte"
  ForwardBridgeWire.ENUM, ForwardBridgeWire.INT -> "int"
  ForwardBridgeWire.LONG -> "long"
  ForwardBridgeWire.FLOAT -> "float"
  ForwardBridgeWire.DOUBLE -> "double"
}

/** The delegate-name fragment this wire contributes, mirroring the ADR-039 suffix convention. */
internal fun ForwardBridgeWire.nameFragment(): String = when (this) {
  ForwardBridgeWire.UNIT -> "Void"
  ForwardBridgeWire.OBJECT -> "Object"
  ForwardBridgeWire.BOOLEAN -> "Byte"
  ForwardBridgeWire.ENUM, ForwardBridgeWire.INT -> "Int"
  ForwardBridgeWire.LONG -> "Long"
  ForwardBridgeWire.FLOAT -> "Float"
  ForwardBridgeWire.DOUBLE -> "Double"
}

/** `NugetBridgeObjectObjectCallback`: parameter wires then the result wire. */
internal fun ForwardBridgeSlot.delegateName(): String =
  "NugetBridge" + parameters.joinToString("") { it.type.wire.nameFragment() } +
      result.wire.nameFragment() + "Callback"

internal fun ForwardBridgeSlot.delegateParamList(): String {
  val args: List<String> = parameters.mapIndexed { index, parameter ->
    "${parameter.type.wire.csharpWire()} arg$index"
  }
  return "(${(args + "IntPtr ctx").joinToString(", ")})"
}

/**
 * `PetBridgeState`, or `AviaryKeeperBridgeState` for a nested interface (ADR-133), package-qualified
 * per ADR-163. Every state class is rendered into the ROOT namespace's one `CirBridgeHelper`.
 */
internal fun bridgeStateName(iface: KSClassDeclaration, symbols: ForwardSymbolTable?): String =
  "${symbols?.csharpQualifier(iface).orEmpty()}${iface.nestedCsName().replace(".", "")}BridgeState"

/**
 * ADR-222: the `NugetBridge` entry a generic interface's call sites name (`KeepsakeHandleOf`). Named,
 * never an overload of `HandleOf`: an overload loses to the erased `HandleOf<T>(T, out bool)` when
 * the argument's static type is the implementing class (verified in the ADR-222 spike).
 */
internal fun genericBridgeEntryName(
  iface: KSClassDeclaration,
  symbols: ForwardSymbolTable?,
): String =
  bridgeStateName(iface, symbols).removeSuffix("BridgeState") + "HandleOf"

/** ADR-222: the interface declares type parameters, so its bridge is generic on both halves. */
internal val ForwardBridgeInterfacePlan.isGeneric: Boolean
  get() = declaration.typeParameters.isNotEmpty()

/**
 * ADR-232: a parameter-independent ADR-210 thunk a bridge factory crosses once, after its slot
 * pairs, as [parameter] (Kotlin) and [pointer] (C#). `releasePtr_` names cannot collide with a
 * slot's: every slot parameter ends in `Ptr` or `Ctx`, and `release` is a fixed name.
 */
internal enum class ForwardBridgeAsyncThunk(val parameter: String, val pointer: String) {
  RELEASE_TASK("releasePtr_task", "NugetThunks.NugetAsyncReleaseTaskPtr"),
  RELEASE_CANCELLATION("releasePtr_cancellation", "NugetThunks.NugetAsyncReleaseCancellationPtr"),
  ENUMERATE("releasePtr_enumerate", "NugetThunks.NugetAsyncEnumeratePtr"),
  MOVE_NEXT_BEGIN("releasePtr_moveNextBegin", "NugetThunks.NugetAsyncMoveNextBeginPtr"),
  MOVE_NEXT_END("releasePtr_moveNextEnd", "NugetThunks.NugetAsyncMoveNextEndPtr"),
  DISPOSE_ENUMERATION("releasePtr_dispose", "NugetThunks.NugetAsyncDisposeEnumerationPtr"),
  RELEASE_CONTEXT("releasePtr_context", "NugetThunks.NugetAsyncReleaseContextPtr"),
}

/** ADR-232: the shared thunks [this] plan's async slots read, in ABI order; empty when none. */
internal fun ForwardBridgeInterfacePlan.asyncThunks(): List<ForwardBridgeAsyncThunk> {
  val suspends: Boolean = slots.any { slot -> slot.async == ForwardBridgeAsync.SUSPEND }
  val flows: Boolean = slots.any { slot -> slot.element != null }
  val lambdas: Boolean = slots.any { slot -> slot.returnedCallback != null }
  return ForwardBridgeAsyncThunk.entries.filter { thunk ->
    when (thunk) {
      ForwardBridgeAsyncThunk.RELEASE_TASK -> suspends || flows
      ForwardBridgeAsyncThunk.RELEASE_CANCELLATION -> suspends
      ForwardBridgeAsyncThunk.ENUMERATE,
      ForwardBridgeAsyncThunk.MOVE_NEXT_BEGIN,
      ForwardBridgeAsyncThunk.MOVE_NEXT_END,
      ForwardBridgeAsyncThunk.DISPOSE_ENUMERATION,
        -> flows
      ForwardBridgeAsyncThunk.RELEASE_CONTEXT -> flows || lambdas
    }
  }
}

/**
 * ADR-232: the slot's own extra thunk parameters after its pair: a SUSPEND slot's End thunk, then
 * the Current thunk of the flow a FLOW slot (or a SUSPEND slot returning a Flow) reads.
 */
internal val ForwardBridgeSlot.asyncThunkParameters: List<String>
  get() = listOfNotNull(
    endThunkParameter.takeIf { async == ForwardBridgeAsync.SUSPEND },
    currentThunkParameter.takeIf { element != null },
    lambdaThunkParameter.takeIf { returnedCallback != null },
  )

/** ADR-233: the C# lambda a slot returns, null for every other slot. */
internal val ForwardBridgeSlot.returnedCallback: BridgeType.Callback?
  get() = result.boxed as? BridgeType.Callback

/** ADR-233: the thunk of the delegate a lambda-returning slot's key dispatches through. */
internal val ForwardBridgeSlot.lambdaThunkParameter: String get() = "${slotPrefix}Ptr_lambda"

/** ADR-232: a SUSPEND slot's End thunk parameter. */
internal val ForwardBridgeSlot.endThunkParameter: String get() = "${slotPrefix}Ptr_end"

/** ADR-232: the Current thunk parameter of the flow a slot returns. */
internal val ForwardBridgeSlot.currentThunkParameter: String get() = "${slotPrefix}Ptr_current"

/** ADR-232: a SUSPEND slot's Begin delegate and thunk, minted off its payload wires. */
internal fun ForwardBridgeSlot.asyncBeginName(): String =
  "NugetBridgeAsync" + parameters.joinToString("") { it.type.wire.nameFragment() } + "Begin"

/**
 * ADR-232: a SUSPEND slot's End thunk, one per result shape. The End read applies the ADR-084
 * slot's own result conversion to the completed task's result, so Kotlin reads it exactly as it
 * reads a synchronous slot of the same type.
 */
internal fun ForwardBridgeSlot.asyncEndName(): String {
  val type: ForwardBridgeType = result
  val shape: String = when {
    element?.typeParameter == true -> "Boxed"
    element != null -> "Flow" + flowSource().forwardAsyncCurrentName().removePrefix("NugetAsync")
      .removeSuffix("Current")
    type.typeParameter -> "Boxed"
    // ADR-233: one End per hot flow type and per boxed result type, named off its C# spelling.
    type.hotFlow || type.boxed != null ->
      "Box" + type.csharp.filter { char -> char.isLetterOrDigit() } +
        if (type.nullable) "OrNull" else ""
    type.throwable != null -> if (type.nullable) "NullableException" else "Exception"
    type.wire == ForwardBridgeWire.OBJECT -> if (type.nullable) "NullableString" else "String"
    type.wire == ForwardBridgeWire.ENUM ->
      type.kotlin.split('.').joinToString("") { it.replaceFirstChar(Char::uppercase) }
    else -> type.wire.nameFragment()
  }
  return "NugetBridgeAsync${shape}End"
}

/** ADR-232: a FLOW slot's element as the ADR-210 `Flow` parameter element it is read as. */
internal fun ForwardBridgeSlot.flowSource(): BridgeType.FlowSource {
  val element: ForwardBridgeType = requireNotNull(element) { "ADR-232: `$name` is not a FLOW slot" }
  // ADR-233: a boxed or star element reads as the ADR-215 box, cast to its declared type.
  if (element.boxed != null || element.star) {
    val parameter = BridgeType.TypeParameter(
      "T", boundQualifiedName = element.kotlin.removeSuffix("?"),
    )
    return BridgeType.FlowSource(
      if (element.nullable) BridgeType.Nullable(parameter) else parameter,
    )
  }
  // ADR-233 amendment: an exception element reads as its managed-exception text.
  element.throwable?.let { throwable -> return BridgeType.FlowSource(throwable) }
  val type: BridgeType = if (element.typeParameter) {
    val parameter = BridgeType.TypeParameter(element.kotlin.removeSuffix("?"))
    if (element.nullable) BridgeType.Nullable(parameter) else parameter
  } else {
    when (element.wire) {
      ForwardBridgeWire.OBJECT -> BridgeType.String
      ForwardBridgeWire.BOOLEAN -> BridgeType.Primitive(PrimitiveKind.BOOLEAN)
      ForwardBridgeWire.INT -> BridgeType.Primitive(PrimitiveKind.INT)
      ForwardBridgeWire.LONG -> BridgeType.Primitive(PrimitiveKind.LONG)
      ForwardBridgeWire.FLOAT -> BridgeType.Primitive(PrimitiveKind.FLOAT)
      ForwardBridgeWire.DOUBLE -> BridgeType.Primitive(PrimitiveKind.DOUBLE)
      ForwardBridgeWire.UNIT, ForwardBridgeWire.ENUM ->
        error("ADR-232: the planner admits no `${element.kotlin}` Flow element")
    }
  }
  return BridgeType.FlowSource(type)
}

/**
 * ADR-233: whether [this] crosses a bridge slot as one box, read and written with the plan's own
 * projections ([ForwardBridgeType.boxed]). A type parameter rides the ADR-222 box instead, and a
 * lambda or a flow its own wire.
 */
internal fun BridgeType.isSlotBoxable(): Boolean = when (this) {
  is BridgeType.Primitive,
  BridgeType.Char,
  BridgeType.String,
  BridgeType.Instant,
  BridgeType.Duration,
  BridgeType.Uuid,
  is BridgeType.Enum,
  is BridgeType.ObjectHandle,
  is BridgeType.Interface,
  is BridgeType.BoundInterface,
  is BridgeType.ValueClass,
  BridgeType.ByteArray,
    -> true

  // A nullable map key is refused at every position (the plan names it), so no slot carries one.
  is BridgeType.Collection -> key !is BridgeType.Nullable &&
    listOfNotNull(element, key, value).all { component ->
    component.isSlotBoxable() || component.unwrapNullable() is BridgeType.TypeParameter
  }
  // ADR-237: `kotlin.Any` boxes as itself; a class's own `T` has its own lane.
  is BridgeType.TypeParameter -> isAny
  is BridgeType.Nullable -> type.isSlotBoxable()
  BridgeType.Unit,
  is BridgeType.Throwable,
  is BridgeType.Callback,
  is BridgeType.AsyncCallback,
  is BridgeType.FlowSource,
  is BridgeType.ReturnedLambda,
  is BridgeType.SpecializedProtocol,
  is BridgeType.RawKSType,
  is BridgeType.Unsupported,
  is BridgeType.RawCollection,
    -> false
}

/** ADR-071: whether a mutable hot flow of [this] element keeps its write surface in C#. */
private fun BridgeType.hasHotWriteArm(): Boolean {
  val inner: BridgeType = unwrapNullable()
  val nullable: Boolean = this is BridgeType.Nullable
  if (inner is BridgeType.Collection || inner == BridgeType.ByteArray) return false
  if (inner == BridgeType.Instant || inner == BridgeType.Duration) return false
  if (inner == BridgeType.Uuid) return false
  if (!nullable) return true
  return inner !is BridgeType.Enum && inner != BridgeType.Char &&
    !(inner is BridgeType.Primitive && inner.kind == PrimitiveKind.BOOLEAN)
}
