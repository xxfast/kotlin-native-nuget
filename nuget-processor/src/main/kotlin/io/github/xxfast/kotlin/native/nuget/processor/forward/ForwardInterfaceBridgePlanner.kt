package io.github.xxfast.kotlin.native.nuget.processor.forward

import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Visibility
import io.github.xxfast.kotlin.native.nuget.processor.cir.expandAliases

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
) {
  /** `nameGetPtr` / `speakPtr`: the ABI parameter prefix, shared by both projections. */
  val slotPrefix: String = if (isProperty) "${name}Get" else name
}

internal data class ForwardBridgeInterfacePlan(
  val qualifiedName: String,
  val simpleName: String,
  /** The projected C# interface name (`IPet`). */
  val csName: String,
  /** `pet_bridge_create`. */
  val exportName: String,
  /** `PetBridgeState`. */
  val stateClassName: String,
  val slots: List<ForwardBridgeSlot>,
)

internal object ForwardInterfaceBridgePlanner {
  private val IGNORED_FUNCTIONS: Set<String> = setOf("equals", "hashCode", "toString", "<init>")

  /**
   * Returns the bridge plan for [iface], or `null` when any member falls outside the stage-1 slot
   * vocabulary (`var` properties, object/collection slots, suspend members, generics). A `null`
   * plan means no factory export and no C# bridge state: `NugetMarshal.HandleOf` keeps throwing
   * for that interface rather than emitting a half-supported ABI.
   */
  fun plan(iface: KSClassDeclaration): ForwardBridgeInterfacePlan? {
    if (iface.classKind != ClassKind.INTERFACE) return null
    if (iface.typeParameters.isNotEmpty()) return null
    val qualifiedName: String = iface.qualifiedName?.asString() ?: return null
    val simpleName: String = iface.simpleName.asString()

    val slots: MutableList<ForwardBridgeSlot> = mutableListOf()
    iface.getAllProperties()
      .filter { property -> property.getVisibility() == Visibility.PUBLIC }
      .forEach { property -> slots.add(slotOf(property) ?: return null) }
    iface.getAllFunctions()
      .filter { function -> function.getVisibility() == Visibility.PUBLIC }
      .filter { function -> function.simpleName.asString() !in IGNORED_FUNCTIONS }
      .forEach { function -> slots.add(slotOf(function) ?: return null) }
    if (slots.isEmpty()) return null

    return ForwardBridgeInterfacePlan(
      qualifiedName = qualifiedName,
      simpleName = simpleName,
      csName = "I$simpleName",
      exportName = "${simpleName.lowercase()}_bridge_create",
      stateClassName = "${simpleName}BridgeState",
      slots = slots,
    )
  }

  private fun slotOf(property: KSPropertyDeclaration): ForwardBridgeSlot? {
    // `var` properties would need a second (setter) slot each: deferred by the ADR's scope.
    if (property.isMutable) return null
    val result: ForwardBridgeType = bridgeType(property.type.resolve().expandAliases()) ?: return null
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

  private fun slotOf(function: KSFunctionDeclaration): ForwardBridgeSlot? {
    if (function.modifiers.any { modifier -> modifier.name == "SUSPEND" }) return null
    if (function.typeParameters.isNotEmpty()) return null
    if (function.parameters.size > 2) return null
    val returnType: KSType = function.returnType?.resolve()?.expandAliases() ?: return null
    val result: ForwardBridgeType = bridgeType(returnType) ?: return null
    val parameters: List<ForwardBridgeParameter> = function.parameters.mapIndexed { index, parameter ->
      val type: ForwardBridgeType = bridgeType(parameter.type.resolve().expandAliases()) ?: return null
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

  private fun bridgeType(type: KSType): ForwardBridgeType? {
    val qualifiedName: String = type.declaration.qualifiedName?.asString() ?: return null
    val nullable: Boolean = type.isMarkedNullable
    val isEnum: Boolean = (type.declaration as? KSClassDeclaration)?.classKind == ClassKind.ENUM_CLASS
    if (isEnum) {
      // A nullable enum has no sentinel on an `int` wire; only the non-null shape is in scope.
      if (nullable) return null
      val simpleName: String = type.declaration.simpleName.asString()
      return ForwardBridgeType(ForwardBridgeWire.ENUM, qualifiedName, simpleName)
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
