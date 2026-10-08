package io.github.xxfast.kotlin.native.nuget.processor.forward

import io.github.xxfast.kotlin.native.nuget.processor.asCSymbol
import io.github.xxfast.kotlin.native.nuget.processor.CSHARP_RESERVED
import io.github.xxfast.kotlin.native.nuget.processor.ForwardSymbolTable
import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Visibility
import io.github.xxfast.kotlin.native.nuget.processor.cir.expandAliases
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
)

internal data class ForwardBridgeParameter(
  val name: String,
  val type: ForwardBridgeType,
)

internal data class ForwardBridgeSlot(
  /** Kotlin member name (`speak`), also the slot's variable prefix. */
  val name: String,
  /** C# member name (`Speak`). */
  val csName: String,
  val isProperty: Boolean,
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
) {
  /**
   * `nameGetPtr` / `speakPtr` / `speak_2Ptr`: the ABI parameter prefix, shared by both
   * projections.
   */
  val slotPrefix: String =
    // `asCSymbol`: a `@CSharpName`d backticked member (`tug hard`) slots as `tug_hardPtr`.
    (if (isProperty) "${name.asCSymbol()}Get" else "${name.asCSymbol()}$overloadSuffix") +
      uniqueSuffix
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
  data class Slot(val slot: ForwardBridgeSlot) : ForwardBridgeSlotOutcome

  data class Refused(val refusal: ForwardBridgeRefusal) : ForwardBridgeSlotOutcome
}

/** The vocabulary a slot carries, for every "cannot carry" remedy. */
private const val CARRIED_TYPES: String = "String, String?, a non-null Boolean, Int, Long, " +
  "Float or Double, a non-null enum, or a Throwable"

private const val ASYNC_HINT: String =
  "move it to a class; only a Kotlin-backed implementation can cross this parameter"

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
   * vocabulary (`var` properties, object/collection slots, suspend members, generics). A `null`
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
    if (iface.typeParameters.isNotEmpty()) {
      val parameters: String = iface.typeParameters.joinToString { it.name.asString() }
      return ForwardBridgePlanOutcome.Refused(
        listOf(
          ForwardBridgeRefusal(
            member = "interface $simpleName<$parameters>",
            reason = "declares type parameters, and a bridge slot carries no type argument",
            hint = "a C# implementation needs a non-generic interface",
          ),
        ),
      )
    }

    val slots: MutableList<ForwardBridgeSlot> = mutableListOf()
    val refusals: MutableList<ForwardBridgeRefusal> = mutableListOf()
    fun collect(outcome: ForwardBridgeSlotOutcome, add: (ForwardBridgeSlot) -> Unit) {
      when (outcome) {
        is ForwardBridgeSlotOutcome.Slot -> add(outcome.slot)
        is ForwardBridgeSlotOutcome.Refused -> refusals += outcome.refusal
      }
    }
    iface.getAllProperties()
      .filter { property -> property.getVisibility() == Visibility.PUBLIC }
      .filter { property -> !property.isCompilerOwnedMember(iface) }
      .forEach { property -> collect(slotOrRefusal(property, classifier)) { slots += it } }
    // ADR-090 amendment (2026-09-26): two same-name function slots declared `speakPtr` twice in
    // the factory signature (a Kotlin `Conflicting declarations` compile error), whether both
    // overloads are declared here or one is inherited from a super-interface.
    val occurrences: MutableMap<String, Int> = mutableMapOf()
    iface.getAllFunctions()
      .filter { function -> function.getVisibility() == Visibility.PUBLIC }
      .filter { function -> !function.isCompilerOwnedMember(iface) }
      .forEach { function ->
        collect(slotOrRefusal(function, classifier)) { slot ->
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
        stateClassName =
          "${symbols.csharpQualifier(iface)}${iface.nestedCsName().replace(".", "")}BridgeState",
        slots = slots.withUniqueSlotPrefixes(BRIDGE_FACTORY_FIXED_NAMES),
      ),
    )
  }

  /**
   * The getter slot for [property], or null when it falls outside the slot vocabulary. Also read by
   * the ADR-039 `add*`/`remove*` subscription route ([listenerPropertySlots]), so a listener `val`
   * crosses there exactly as it does on this factory.
   */
  internal fun slotOf(
    property: KSPropertyDeclaration,
    classifier: ForwardBridgeTypeClassifier,
  ): ForwardBridgeSlot? =
    (slotOrRefusal(property, classifier) as? ForwardBridgeSlotOutcome.Slot)?.slot

  private fun slotOrRefusal(
    property: KSPropertyDeclaration,
    classifier: ForwardBridgeTypeClassifier,
  ): ForwardBridgeSlotOutcome {
    val name: String = property.simpleName.asString()
    val type: KSType = property.type.resolve().expandAliases()
    val member: String = "${if (property.isMutable) "var" else "val"} $name: $type"
    fun refuse(reason: String, hint: String): ForwardBridgeSlotOutcome =
      ForwardBridgeSlotOutcome.Refused(ForwardBridgeRefusal(member, reason, hint, name))
    // `var` properties would need a second (setter) slot each: deferred by the ADR's scope.
    if (property.isMutable) {
      return refuse(
        "is a `var`, and the bridge carries a property through a getter slot only",
        "declare it `val`, or hand the value over through a member function",
      )
    }
    // A backticked `slack line` with no `@CSharpName` has no C# member to implement.
    if (!property.hasBridgeableName()) {
      return refuse("has no C# member name to implement", "give it a `@CSharpName`")
    }
    if (type.isFlowType()) {
      return refuse("is a Flow member, which a C# implementation cannot supply", ASYNC_HINT)
    }
    val result: ForwardBridgeType = bridgeType(type, classifier)
      ?: return refuse("has a type the bridge cannot carry", "use one of $CARRIED_TYPES")
    narrowThrowableOf(result)?.let { declared -> return refuseNarrow(member, name, declared) }
    if (result.wire == ForwardBridgeWire.UNIT) {
      return refuse("is `Unit`, which carries no value", "give it a value type or remove it")
    }
    return ForwardBridgeSlotOutcome.Slot(
      ForwardBridgeSlot(
        name = name,
        csName = property.csharpMemberName(),
        isProperty = true,
        result = result,
        parameters = emptyList(),
      ),
    )
  }

  private fun slotOrRefusal(
    function: KSFunctionDeclaration,
    classifier: ForwardBridgeTypeClassifier,
  ): ForwardBridgeSlotOutcome {
    val name: String = function.simpleName.asString()
    val suspend: Boolean = function.modifiers.any { modifier -> modifier.name == "SUSPEND" }
    val member: String = "${if (suspend) "suspend " else ""}fun $name"
    fun refuse(reason: String, hint: String): ForwardBridgeSlotOutcome =
      ForwardBridgeSlotOutcome.Refused(ForwardBridgeRefusal(member, reason, hint, name))
    if (suspend) {
      return refuse("is a suspend member, which a C# implementation cannot supply", ASYNC_HINT)
    }
    if (!function.hasBridgeableName()) {
      return refuse("has no C# member name to implement", "give it a `@CSharpName`")
    }
    if (function.typeParameters.isNotEmpty()) {
      return refuse(
        "declares type parameters, and a bridge slot carries no type argument",
        "give it concrete parameter and result types",
      )
    }
    if (function.parameters.size > 2) {
      return refuse(
        "takes ${function.parameters.size} parameters, and a bridge slot carries at most two",
        "take at most two parameters, for example by grouping them in a value the bridge carries",
      )
    }
    val returnType: KSType = function.returnType?.resolve()?.expandAliases()
      ?: return refuse("has no resolvable result type", "declare its result type")
    if (returnType.isFlowType()) {
      return refuse("returns a Flow, which a C# implementation cannot supply", ASYNC_HINT)
    }
    val result: ForwardBridgeType = bridgeType(returnType, classifier)
      ?: return refuse(
        "returns `$returnType`, a type the bridge cannot carry",
        "return one of $CARRIED_TYPES",
      )
    val parameters: MutableList<ForwardBridgeParameter> = mutableListOf()
    function.parameters.forEachIndexed { index, parameter ->
      val parameterName: String = parameter.name?.asString() ?: "arg$index"
      val declared: KSType = parameter.type.resolve().expandAliases()
      val type: ForwardBridgeType = bridgeType(declared, classifier)
        ?.takeIf { it.wire != ForwardBridgeWire.UNIT }
        ?: return refuse(
          "takes `$parameterName: $declared`, a type the bridge cannot carry",
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
        csName = function.csharpMemberName(),
        isProperty = false,
        result = result,
        parameters = parameters,
      ),
    )
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
  ): ForwardBridgeType? {
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
      // A nullable enum has no sentinel on an `int` wire; only the non-null shape is in scope.
      if (nullable) return null
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
      "kotlin.Unit" -> if (nullable) null else ForwardBridgeType(ForwardBridgeWire.UNIT, "Unit", "void")
      "kotlin.String" -> ForwardBridgeType(
        ForwardBridgeWire.OBJECT,
        if (nullable) "String?" else "String",
        if (nullable) "string?" else "string",
        nullable,
      )

      "kotlin.Boolean" -> if (nullable) null else ForwardBridgeType(ForwardBridgeWire.BOOLEAN, "Boolean", "bool")
      "kotlin.Int" -> if (nullable) null else ForwardBridgeType(ForwardBridgeWire.INT, "Int", "int")
      "kotlin.Long" -> if (nullable) null else ForwardBridgeType(ForwardBridgeWire.LONG, "Long", "long")
      "kotlin.Float" -> if (nullable) null else ForwardBridgeType(ForwardBridgeWire.FLOAT, "Float", "float")
      "kotlin.Double" -> if (nullable) null else ForwardBridgeType(ForwardBridgeWire.DOUBLE, "Double", "double")
      else -> null
    }
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
