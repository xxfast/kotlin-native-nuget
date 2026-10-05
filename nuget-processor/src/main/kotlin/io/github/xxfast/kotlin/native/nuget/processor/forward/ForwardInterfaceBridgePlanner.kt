package io.github.xxfast.kotlin.native.nuget.processor.forward

import io.github.xxfast.kotlin.native.nuget.processor.asCSymbol
import io.github.xxfast.kotlin.native.nuget.processor.CSHARP_RESERVED
import io.github.xxfast.kotlin.native.nuget.processor.ForwardSymbolTable
import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
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

internal object ForwardInterfaceBridgePlanner {
  /**
   * Returns the bridge plan for [iface], or `null` when any member falls outside the stage-1 slot
   * vocabulary (`var` properties, object/collection slots, suspend members, generics). A `null`
   * plan means no factory export and no C# bridge state: `NugetMarshal.HandleOf` keeps throwing
   * for that interface rather than emitting a half-supported ABI.
   */
  fun plan(
    iface: KSClassDeclaration,
    classifier: ForwardBridgeTypeClassifier,
    /** ADR-163: the one symbol table. */
    symbols: ForwardSymbolTable,
    // ADR-201 amendment: plan as if a narrower Throwable result could hold what C# sends, which is
    // how [throwableRefusal] tells that refusal apart from every other reason a plan is null.
    allowNarrowThrowable: Boolean = false,
  ): ForwardBridgeInterfacePlan? {
    if (iface.classKind != ClassKind.INTERFACE) return null
    if (iface.typeParameters.isNotEmpty()) return null
    val qualifiedName: String = iface.qualifiedName?.asString() ?: return null
    val simpleName: String = iface.simpleName.asString()

    val slots: MutableList<ForwardBridgeSlot> = mutableListOf()
    iface.getAllProperties()
      .filter { property -> property.getVisibility() == Visibility.PUBLIC }
      .filter { property -> !property.isCompilerOwnedMember(iface) }
      .forEach { property ->
        slots.add(slotOf(property, classifier, allowNarrowThrowable) ?: return null)
      }
    // ADR-090 amendment (2026-09-26): two same-name function slots declared `speakPtr` twice in
    // the factory signature (a Kotlin `Conflicting declarations` compile error), whether both
    // overloads are declared here or one is inherited from a super-interface.
    val occurrences: MutableMap<String, Int> = mutableMapOf()
    iface.getAllFunctions()
      .filter { function -> function.getVisibility() == Visibility.PUBLIC }
      .filter { function -> !function.isCompilerOwnedMember(iface) }
      .forEach { function ->
        val slot: ForwardBridgeSlot =
          slotOf(function, classifier, allowNarrowThrowable) ?: return null
        val occurrence: Int = occurrences.merge(slot.name, 1, Int::plus)!!
        slots.add(if (occurrence == 1) slot else slot.copy(overloadSuffix = "_$occurrence"))
      }
    // A member-less (marker) interface plans with no slots: its factory carries only the release
    // pair and the token, and the Kotlin bridge is an empty `object : Marker`. Reading "no slots"
    // as out of scope left `HandleOf` throwing for a C# implementation at a plain parameter, while
    // the ADR-039 add/remove pair accepts the same implementation.

    return ForwardBridgeInterfacePlan(
      declaration = iface,
      qualifiedName = qualifiedName,
      simpleName = simpleName,
      // ADR-133: `Aviary.IKeeper`, and the bridge export carries the chain like every other.
      csName = iface.nestedInterfaceCsName(),
      exportName = "${iface.nativePrefix(symbols)}_bridge_create",
      // ADR-133: the enclosing chain, flattened. Every state class is rendered into the ROOT
      // namespace's one `CirBridgeHelper`, so two owners' same-simple-name nested interfaces
      // (`Aviary.Keeper` and `Registry.Keeper`) both emitted `KeeperBridgeState`: CS0101, plus two
      // `keeperImpl` pattern variables in one `HandleFor` block (CS0128). A top-level interface
      // has no chain and keeps its shipped `PetBridgeState` byte for byte.
      // ADR-163: package-qualified too. Every state class is rendered into the ROOT namespace, so
      // two same-simple-name interfaces in two packages were CS0101 there the moment their entry
      // points stopped colliding and the build got far enough to emit both.
      stateClassName =
        "${symbols.csharpQualifier(iface)}${iface.nestedCsName().replace(".", "")}BridgeState",
      slots = slots.withUniqueSlotPrefixes(BRIDGE_FACTORY_FIXED_NAMES),
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
    allowNarrowThrowable: Boolean = false,
  ): ForwardBridgeSlot? {
    // `var` properties would need a second (setter) slot each: deferred by the ADR's scope.
    if (property.isMutable) return null
    // A backticked `slack line` with no `@CSharpName` has no C# member to implement.
    if (!property.hasBridgeableName()) return null
    val result: ForwardBridgeType =
      bridgeType(property.type.resolve().expandAliases(), classifier) ?: return null
    if (!allowNarrowThrowable && result.refusesManagedException()) return null
    if (result.wire == ForwardBridgeWire.UNIT) return null
    val name: String = property.simpleName.asString()
    return ForwardBridgeSlot(
      name = name,
      csName = property.csharpMemberName(),
      isProperty = true,
      result = result,
      parameters = emptyList(),
    )
  }

  private fun slotOf(
    function: KSFunctionDeclaration,
    classifier: ForwardBridgeTypeClassifier,
    allowNarrowThrowable: Boolean,
  ): ForwardBridgeSlot? {
    if (function.modifiers.any { modifier -> modifier.name == "SUSPEND" }) return null
    if (!function.hasBridgeableName()) return null
    if (function.typeParameters.isNotEmpty()) return null
    if (function.parameters.size > 2) return null
    val returnType: KSType = function.returnType?.resolve()?.expandAliases() ?: return null
    val result: ForwardBridgeType = bridgeType(returnType, classifier) ?: return null
    if (!allowNarrowThrowable && result.refusesManagedException()) return null
    val parameters: List<ForwardBridgeParameter> = function.parameters.mapIndexed { index, parameter ->
      val type: ForwardBridgeType =
        bridgeType(parameter.type.resolve().expandAliases(), classifier) ?: return null
      if (type.wire == ForwardBridgeWire.UNIT) return null
      ForwardBridgeParameter(parameter.name?.asString() ?: "arg$index", type)
    }
    val name: String = function.simpleName.asString()
    return ForwardBridgeSlot(
      name = name,
      csName = function.csharpMemberName(),
      isProperty = false,
      result = result,
      parameters = parameters,
    )
  }

  /**
   * ADR-201 amendment: a slot RESULT is a value C# hands Kotlin, which arrives as a
   * `NugetManagedException`; a result declared narrower than `RuntimeException` cannot hold one.
   */
  private fun ForwardBridgeType.refusesManagedException(): Boolean =
    throwable?.acceptsManagedException == false

  /**
   * ADR-201 amendment: the member whose narrower `Throwable` result alone keeps [iface] from
   * planning a bridge, as `member` to its declared spelling (`last` to `IllegalStateException?`),
   * or null. Read where a null [plan] is otherwise silent, so this refusal is named.
   */
  internal fun throwableRefusal(
    iface: KSClassDeclaration,
    classifier: ForwardBridgeTypeClassifier,
    symbols: ForwardSymbolTable,
  ): Pair<String, String>? {
    // Only when the Throwable result is the whole reason: a `var`, a suspend member or any other
    // out-of-vocabulary slot is a different (pre-existing) refusal this does not name.
    if (plan(iface, classifier, symbols, allowNarrowThrowable = true) == null) return null
    val results: List<Pair<String, KSType?>> =
      iface.getAllProperties()
        .filter { property -> property.getVisibility() == Visibility.PUBLIC }
        .map { property -> property.simpleName.asString() to property.type.resolve() }.toList() +
        iface.getAllFunctions()
          .filter { function -> function.getVisibility() == Visibility.PUBLIC }
          .filter { function -> !function.isCompilerOwnedMember(iface) }
          .map { function -> function.simpleName.asString() to function.returnType?.resolve() }
          .toList()
    return results.firstNotNullOfOrNull { (member, type) ->
      val bridged: ForwardBridgeType = type?.expandAliases()?.let { resolved ->
        bridgeType(resolved, classifier)
      } ?: return@firstNotNullOfOrNull null
      val throwable: BridgeType.Throwable =
        bridged.throwable?.takeIf { bridged.refusesManagedException() }
          ?: return@firstNotNullOfOrNull null
      member to throwable.kotlinType.substringAfterLast('.') + if (bridged.nullable) "?" else ""
    }
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
