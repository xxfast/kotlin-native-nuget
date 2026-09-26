package io.github.xxfast.kotlin.native.nuget.processor.forward

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
) {
  /**
   * `nameGetPtr` / `speakPtr` / `speak_2Ptr`: the ABI parameter prefix, shared by both
   * projections.
   */
  val slotPrefix: String = if (isProperty) "${name}Get" else "$name$overloadSuffix"
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
  ): ForwardBridgeInterfacePlan? {
    if (iface.classKind != ClassKind.INTERFACE) return null
    if (iface.typeParameters.isNotEmpty()) return null
    val qualifiedName: String = iface.qualifiedName?.asString() ?: return null
    val simpleName: String = iface.simpleName.asString()

    val slots: MutableList<ForwardBridgeSlot> = mutableListOf()
    iface.getAllProperties()
      .filter { property -> property.getVisibility() == Visibility.PUBLIC }
      .filter { property -> !property.isCompilerOwnedMember(iface) }
      .forEach { property -> slots.add(slotOf(property, classifier) ?: return null) }
    // ADR-090 amendment (2026-09-26): two same-name function slots declared `speakPtr` twice in
    // the factory signature (a Kotlin `Conflicting declarations` compile error), whether both
    // overloads are declared here or one is inherited from a super-interface.
    val occurrences: MutableMap<String, Int> = mutableMapOf()
    iface.getAllFunctions()
      .filter { function -> function.getVisibility() == Visibility.PUBLIC }
      .filter { function -> !function.isCompilerOwnedMember(iface) }
      .forEach { function ->
        val slot: ForwardBridgeSlot = slotOf(function, classifier) ?: return null
        val occurrence: Int = occurrences.merge(slot.name, 1, Int::plus)!!
        slots.add(if (occurrence == 1) slot else slot.copy(overloadSuffix = "_$occurrence"))
      }
    if (slots.isEmpty()) return null

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
      slots = slots,
    )
  }

  private fun slotOf(
    property: KSPropertyDeclaration,
    classifier: ForwardBridgeTypeClassifier,
  ): ForwardBridgeSlot? {
    // `var` properties would need a second (setter) slot each: deferred by the ADR's scope.
    if (property.isMutable) return null
    val result: ForwardBridgeType =
      bridgeType(property.type.resolve().expandAliases(), classifier) ?: return null
    if (result.wire == ForwardBridgeWire.UNIT) return null
    val name: String = property.simpleName.asString()
    return ForwardBridgeSlot(
      name = name,
      csName = name.replaceFirstChar { it.uppercase() },
      isProperty = true,
      result = result,
      parameters = emptyList(),
    )
  }

  private fun slotOf(
    function: KSFunctionDeclaration,
    classifier: ForwardBridgeTypeClassifier,
  ): ForwardBridgeSlot? {
    if (function.modifiers.any { modifier -> modifier.name == "SUSPEND" }) return null
    if (function.typeParameters.isNotEmpty()) return null
    if (function.parameters.size > 2) return null
    val returnType: KSType = function.returnType?.resolve()?.expandAliases() ?: return null
    val result: ForwardBridgeType = bridgeType(returnType, classifier) ?: return null
    val parameters: List<ForwardBridgeParameter> = function.parameters.mapIndexed { index, parameter ->
      val type: ForwardBridgeType =
        bridgeType(parameter.type.resolve().expandAliases(), classifier) ?: return null
      if (type.wire == ForwardBridgeWire.UNIT) return null
      ForwardBridgeParameter(parameter.name?.asString() ?: "arg$index", type)
    }
    val name: String = function.simpleName.asString()
    return ForwardBridgeSlot(
      name = name,
      csName = name.replaceFirstChar { it.uppercase() },
      isProperty = false,
      result = result,
      parameters = parameters,
    )
  }

  private fun bridgeType(
    type: KSType,
    classifier: ForwardBridgeTypeClassifier,
  ): ForwardBridgeType? {
    val qualifiedName: String = type.declaration.qualifiedName?.asString() ?: return null
    val nullable: Boolean = type.isMarkedNullable
    val isEnum: Boolean = (type.declaration as? KSClassDeclaration)?.classKind == ClassKind.ENUM_CLASS
    if (isEnum) {
      // A nullable enum has no sentinel on an `int` wire; only the non-null shape is in scope.
      if (nullable) return null
      // The enum's C# spelling is the classifier's to make, never this planner's: a bare simple
      // name names nothing for a nested enum (which is never declared as a C# enum at all) and
      // resolves only by luck for one outside the file's `using` list. An undeclared enum plans no
      // factory, exactly the ADR-084 posture for every other out-of-scope member.
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
