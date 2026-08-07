package io.github.xxfast.kotlin.native.nuget.processor.forward

import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Visibility
import io.github.xxfast.kotlin.native.nuget.processor.cir.expandAliases
import io.github.xxfast.kotlin.native.nuget.processor.toCName

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
)

/** Builds the property slice while leaving unsupported/specialized properties on their named legacy paths. */
internal class ForwardPropertyPlanner(
  private val classifier: ForwardBridgeTypeClassifier,
) {
  private val droppedSetters: MutableList<ForwardDroppedPropertySetter> = mutableListOf()

  /** ADR-075: every collection property setter this planner declined to build because a
   *  component failed [isWrappableComponent] — the property itself is still planned, get-only. */
  val droppedPropertySetters: List<ForwardDroppedPropertySetter> get() = droppedSetters

  fun catalog(
    classes: List<KSClassDeclaration>,
    topLevel: List<KSPropertyDeclaration>,
    extensions: List<KSPropertyDeclaration>,
  ): List<ForwardPropertyPlan> = buildList {
    classes.forEach { cls ->
      addAll(classProperties(cls))
      addAll(companionProperties(cls))
    }
    topLevel.forEach { prop -> topLevelProperty(prop)?.let(::add) }
    extensions.forEach { prop -> extensionProperty(prop)?.let(::add) }
  }

  private fun classProperties(cls: KSClassDeclaration): List<ForwardPropertyPlan> {
    val owner: String = cls.qualifiedName?.asString() ?: return emptyList()
    val hasSuperClass: Boolean = cls.superTypes.any { type ->
      type.resolve().declaration.qualifiedName?.asString() != "kotlin.Any"
    }
    return cls.getAllProperties()
      .filter { it.getVisibility() == Visibility.PUBLIC }
      .filter { prop -> !hasSuperClass || prop.parentDeclaration == cls }
      .mapNotNull { prop ->
        propertyPlan(
          symbol = "$owner.${prop.simpleName.asString()}",
          position = ForwardPropertyPosition.CLASS,
          receiver = ForwardPropertyReceiver.Handle(owner),
          prop = prop,
          getExport = "${cls.simpleName.asString().lowercase()}_get_${prop.simpleName.asString()}",
          setExport = "${cls.simpleName.asString().lowercase()}_set_${prop.simpleName.asString()}",
        )
      }
      .toList()
  }

  /**
   * ADR-040: dispatch-export property plans for an interface's own declared properties
   * (reachability-driven — only called for interfaces already known to appear in a planned return
   * position). Shaped exactly like [classProperties], with the interface's own qualified name as
   * both the symbol owner and the `asStableRef` receiver type.
   */
  fun interfaceProperties(iface: KSClassDeclaration): List<ForwardPropertyPlan> {
    val owner: String = iface.qualifiedName?.asString() ?: return emptyList()
    val prefix: String = iface.simpleName.asString().lowercase()
    return iface.getAllProperties()
      .filter { it.getVisibility() == Visibility.PUBLIC }
      .filter { prop -> prop.parentDeclaration == iface }
      .mapNotNull { prop ->
        propertyPlan(
          symbol = "$owner.${prop.simpleName.asString()}",
          position = ForwardPropertyPosition.CLASS,
          receiver = ForwardPropertyReceiver.Handle(owner),
          prop = prop,
          getExport = "${prefix}_get_${prop.simpleName.asString()}",
          setExport = "${prefix}_set_${prop.simpleName.asString()}",
        )
      }
      .toList()
  }

  private fun companionProperties(cls: KSClassDeclaration): List<ForwardPropertyPlan> {
    val companion: KSClassDeclaration = cls.declarations.filterIsInstance<KSClassDeclaration>()
      .firstOrNull { it.isCompanionObject } ?: return emptyList()
    val owner: String = cls.qualifiedName?.asString() ?: return emptyList()
    val prefix: String = cls.simpleName.asString().lowercase()
    return companion.getAllProperties()
      .filter { it.getVisibility() == Visibility.PUBLIC }
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

  private fun topLevelProperty(prop: KSPropertyDeclaration): ForwardPropertyPlan? {
    val name: String = prop.simpleName.asString()
    val cname: String = toCName(name)
    return propertyPlan(
      symbol = "${prop.packageName.asString()}.$name",
      position = ForwardPropertyPosition.TOP_LEVEL,
      receiver = ForwardPropertyReceiver.Static(null),
      prop = prop,
      getExport = "get_$cname",
      setExport = "set_$cname",
    )
  }

  private fun extensionProperty(prop: KSPropertyDeclaration): ForwardPropertyPlan? {
    val receiver: KSType = prop.extensionReceiver?.resolve()?.expandAliases() ?: return null
    val receiverType: BridgeType = classifier.classify(receiver)
    // ADR-075: a value class crosses the bridge as its own underlying primitive/String value
    // (ADR-014), the same wire shape its own declared members already use
    // (`ForwardCallablePlanner.valueClassEntries`) — a reference-underlying value class is not
    // admitted here, matching that same route's ADR-035 deferral.
    val isSupportedValueClass: Boolean = receiverType is BridgeType.ValueClass &&
        (receiverType.underlying is BridgeType.Primitive || receiverType.underlying == BridgeType.String)
    val supportedReceiver: Boolean =
      receiverType is BridgeType.ObjectHandle ||
          receiverType is BridgeType.Primitive ||
          receiverType == BridgeType.String ||
          isSupportedValueClass
    if (!supportedReceiver) return null
    val receiverName: String = receiver.declaration.simpleName.asString()
    val name: String = prop.simpleName.asString()
    return propertyPlan(
      symbol = "${prop.packageName.asString()}.$receiverName.$name",
      position = ForwardPropertyPosition.EXTENSION,
      receiver = ForwardPropertyReceiver.Value(receiverType),
      prop = prop,
      getExport = "${receiverName.lowercase()}_get_${toCName(name)}",
      setExport = "${receiverName.lowercase()}_set_${toCName(name)}",
    )
  }

  private fun propertyPlan(
    symbol: String,
    position: ForwardPropertyPosition,
    receiver: ForwardPropertyReceiver,
    prop: KSPropertyDeclaration,
    getExport: String,
    setExport: String,
  ): ForwardPropertyPlan? {
    val type: BridgeType = classifier.classify(prop.type.resolve())
    // ADR-075: getter eligibility never depended on mutability or on the collection facet — a
    // `Collection` (nullable or not) is unconditionally plannable (`isPlannable` already recurses
    // through `Nullable`). Whether a *setter* can also be built is a wholly separate question,
    // decided below, independent of the getter.
    if (!isPlannable(type)) return null
    val name: String = prop.simpleName.asString()
    val publicName: String = name.replaceFirstChar { it.uppercase() }
    // ADR-076: Instant shares the nullable-primitive LegacyTwoCall shape exactly.
    val isNullableLegacyPrimitive: Boolean = type is BridgeType.Nullable &&
        (type.type is BridgeType.Primitive || type.type == BridgeType.Instant)
    val getter: ForwardPropertyGetter = if (isNullableLegacyPrimitive) {
      ForwardPropertyGetter.LegacyTwoCall(
        presence = nativeCall(getExport, ForwardAbiWireType.BOOLEAN, receiver, emptyList()),
        value = nativeCall("${getExport}_value", type.type.wireType(), receiver, emptyList()),
      )
    } else {
      ForwardPropertyGetter.Direct(nativeCall(getExport, type.wireType(), receiver, emptyList()))
    }
    val setter: ForwardPropertySetter? = collectionSetterOrNull(
      symbol, publicName, prop, type, setExport, receiver,
    )
    return ForwardPropertyPlan(
      symbol = symbol,
      position = position,
      receiver = receiver,
      kotlinName = name,
      publicName = publicName,
      type = type,
      getter = getter,
      setter = setter,
      helperRequirements = when (type.unwrapNullable()) {
        is BridgeType.Collection -> setOf(ForwardHelperRequirement.COLLECTION)
        BridgeType.Instant -> setOf(ForwardHelperRequirement.INSTANT)
        // ADR-077: same pairing as the callable side (the value-class step plus the underlying's
        // own helper, keyed per kind in sub-item 4).
        is BridgeType.ValueClass -> buildSet {
          add(ForwardHelperRequirement.VALUE_CLASS)
          val underlying: BridgeType = (type.unwrapNullable() as BridgeType.ValueClass).underlying
          if (underlying == BridgeType.String) add(ForwardHelperRequirement.UTF8)
          if (underlying is BridgeType.Enum) add(ForwardHelperRequirement.ENUM_ORDINAL)
        }

        else -> emptySet()
      },
    ).validate()
  }

  /**
   * ADR-075 Decision 2. `null` when [prop] is not `var` (an ordinary, expected get-only shape —
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
  ): ForwardPropertySetter? {
    if (!prop.isMutable) return null
    // ADR-076: Instant shares the nullable-primitive NullableDispatch setter shape exactly.
    val isNullableLegacyPrimitive: Boolean = type is BridgeType.Nullable &&
        (type.type is BridgeType.Primitive || type.type == BridgeType.Instant)
    if (isNullableLegacyPrimitive) {
      return ForwardPropertySetter.NullableDispatch(
        value = nativeCall(
          setExport, ForwardAbiWireType.VOID, receiver, listOf(valueParameter(type.type)),
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
          componentDescription = collection.ineligibleComponentDescription(),
        ),
      )
      return null
    }
    return ForwardPropertySetter.Direct(
      nativeCall(setExport, ForwardAbiWireType.VOID, receiver, listOf(valueParameter(type))),
    )
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

  /** The offending component(s), for the [ForwardDroppedPropertySetter] diagnostic wording. */
  private fun BridgeType.Collection.ineligibleComponentDescription(): String {
    val isMap: Boolean = kind == CollectionKind.MAP || kind == CollectionKind.MUTABLE_MAP
    if (!isMap) return "element type ${element?.diagnosticTypeName() ?: "unknown"}"
    val keyOk: Boolean = key?.isWrappableComponent() == true
    val valueOk: Boolean = value?.isWrappableComponent() == true
    return when {
      !keyOk && !valueOk ->
        "key type ${key?.diagnosticTypeName() ?: "unknown"} and value type " +
            "${value?.diagnosticTypeName() ?: "unknown"}"

      !keyOk -> "key type ${key?.diagnosticTypeName() ?: "unknown"}"
      else -> "value type ${value?.diagnosticTypeName() ?: "unknown"}"
    }
  }

  /** A short, human-readable name for a diagnostic message — never used to drive marshalling. */
  private fun BridgeType.diagnosticTypeName(): String = when (this) {
    BridgeType.Unit -> "Unit"
    BridgeType.Char -> "Char"
    BridgeType.String -> "String"
    BridgeType.Instant -> "Instant"
    is BridgeType.Primitive -> kind.name.lowercase().replaceFirstChar { it.uppercase() }
    is BridgeType.Enum -> qualifiedName.substringAfterLast('.')
    is BridgeType.ObjectHandle -> qualifiedName.substringAfterLast('.')
    is BridgeType.Interface -> qualifiedName.substringAfterLast('.')
    is BridgeType.ValueClass -> qualifiedName.substringAfterLast('.')
    is BridgeType.Collection -> "Collection"
    is BridgeType.Nullable -> "${type.diagnosticTypeName()}?"
    is BridgeType.SpecializedProtocol -> name
    is BridgeType.RawCollection -> "Collection"
    is BridgeType.RawKSType -> rendered
    is BridgeType.Unsupported -> rendered
  }

  private fun nativeCall(
    exportName: String,
    result: ForwardAbiWireType,
    receiver: ForwardPropertyReceiver,
    values: List<ForwardAbiParameter>,
  ): ForwardNativeCall = ForwardNativeCall(
    exportName = exportName,
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
      ),
    )

    is ForwardPropertyReceiver.Value -> listOf(valueParameter(type, "receiver"))
    is ForwardPropertyReceiver.Static -> emptyList()
  }

  private fun valueParameter(type: BridgeType, name: String = "value"): ForwardAbiParameter = ForwardAbiParameter(
    name, type.inputWireType(), ForwardAbiDirection.IN,
    ForwardTransfer(
      name, type, ForwardFlow.INTO_KOTLIN, ForwardPassing.VALUE,
      ForwardOwnership.BORROWED, type.conversion(ForwardFlow.INTO_KOTLIN)
    ),
  )

  private fun errorParameter(): ForwardAbiParameter = ForwardAbiParameter(
    "errorOut", ForwardAbiWireType.POINTER, ForwardAbiDirection.OUT,
    ForwardTransfer(
      "error", BridgeType.ObjectHandle("kotlin.Throwable"), ForwardFlow.OUT_OF_KOTLIN,
      ForwardPassing.OUT, ForwardOwnership.BORROWED, ForwardConversion.STABLE_REF_TO_HANDLE
    ),
  )

  private fun isPlannable(type: BridgeType): Boolean = when (type) {
    BridgeType.Unit, BridgeType.Char, BridgeType.String, BridgeType.Instant,
    is BridgeType.Primitive, is BridgeType.Enum, is BridgeType.ObjectHandle,
    is BridgeType.Interface, is BridgeType.Collection -> true

    // ADR-077 sub-items 2/4: a value-class property plans when its underlying does
    // (String/primitive/enum/ObjectHandle).
    is BridgeType.ValueClass -> type.underlying == BridgeType.String ||
        type.underlying is BridgeType.Primitive ||
        type.underlying is BridgeType.Enum ||
        type.underlying is BridgeType.ObjectHandle

    // ADR-077 sub-item 4: a *nullable* value-class property needs a pointer-shaped underlying
    // (String, ObjectHandle) to carry null in-band; the primitive/enum-underlying nullable
    // spelling stays deferred, so it must not ride the plain recursion.
    is BridgeType.Nullable -> {
      val inner: BridgeType = type.type
      if (inner is BridgeType.ValueClass) {
        inner.underlying == BridgeType.String || inner.underlying is BridgeType.ObjectHandle
      } else {
        isPlannable(inner)
      }
    }

    else -> false
  }

  private fun BridgeType.unwrapNullable(): BridgeType = if (this is BridgeType.Nullable) type else this

  private fun BridgeType.wireType(): ForwardAbiWireType = when (val type = unwrapNullable()) {
    BridgeType.Unit -> ForwardAbiWireType.VOID
    BridgeType.Char -> ForwardAbiWireType.CHAR16
    BridgeType.String, is BridgeType.ObjectHandle, is BridgeType.Interface, is BridgeType.Collection ->
      ForwardAbiWireType.POINTER

    is BridgeType.Enum -> ForwardAbiWireType.INT32
    // ADR-076: wires as its own INT64 tick representation, same as a Primitive(LONG).
    BridgeType.Instant -> ForwardAbiWireType.INT64
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

    else -> error("Forward property planner cannot choose a wire type for $type")
  }

  private fun BridgeType.inputWireType(): ForwardAbiWireType = when (val type = unwrapNullable()) {
    BridgeType.String -> ForwardAbiWireType.STRING
    is BridgeType.ValueClass -> type.underlying.inputWireType()
    else -> wireType()
  }

  private fun BridgeType.conversion(flow: ForwardFlow): ForwardConversion? = when (unwrapNullable()) {
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

    is BridgeType.Collection -> if (flow == ForwardFlow.INTO_KOTLIN) {
      ForwardConversion.HANDLE_TO_COLLECTION
    } else {
      ForwardConversion.COLLECTION_TO_HANDLE
    }

    BridgeType.Instant -> if (flow == ForwardFlow.INTO_KOTLIN) {
      ForwardConversion.TICKS_TO_INSTANT
    } else {
      ForwardConversion.INSTANT_TO_TICKS
    }

    // ADR-077 sub-item 2: without this branch the `else` silently tags the transfer DIRECT, and
    // ForwardPropertyPlan.validate() never checks conversions, so nothing would catch it.
    is BridgeType.ValueClass -> if (flow == ForwardFlow.INTO_KOTLIN) {
      ForwardConversion.BOX_VALUE_CLASS
    } else {
      ForwardConversion.UNBOX_VALUE_CLASS
    }

    else -> ForwardConversion.DIRECT
  }
}
