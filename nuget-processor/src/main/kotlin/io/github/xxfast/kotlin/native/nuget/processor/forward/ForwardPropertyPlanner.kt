package io.github.xxfast.kotlin.native.nuget.processor.forward

import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Visibility
import io.github.xxfast.kotlin.native.nuget.processor.cir.expandAliases
import io.github.xxfast.kotlin.native.nuget.processor.toCName

/** Builds the property slice while leaving unsupported/specialized properties on their named legacy paths. */
internal class ForwardPropertyPlanner(
  private val classifier: ForwardBridgeTypeClassifier,
) {
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
    val supportedReceiver: Boolean =
      receiverType is BridgeType.ObjectHandle ||
          receiverType is BridgeType.Primitive ||
          receiverType == BridgeType.String
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
    if (!isPlannable(type) || (prop.isMutable && type.unwrapNullable() is BridgeType.Collection)) return null
    val name: String = prop.simpleName.asString()
    val getter: ForwardPropertyGetter = if (type is BridgeType.Nullable && type.type is BridgeType.Primitive) {
      ForwardPropertyGetter.LegacyTwoCall(
        presence = nativeCall(getExport, ForwardAbiWireType.BOOLEAN, receiver, emptyList()),
        value = nativeCall("${getExport}_value", type.type.wireType(), receiver, emptyList()),
      )
    } else {
      ForwardPropertyGetter.Direct(nativeCall(getExport, type.wireType(), receiver, emptyList()))
    }
    val setter: ForwardPropertySetter? = if (!prop.isMutable) null else if (
      type is BridgeType.Nullable && type.type is BridgeType.Primitive
    ) {
      ForwardPropertySetter.NullableDispatch(
        value = nativeCall(setExport, ForwardAbiWireType.VOID, receiver, listOf(valueParameter(type.type))),
        nullValue = nativeCall("${setExport}_null", ForwardAbiWireType.VOID, receiver, emptyList()),
      )
    } else {
      ForwardPropertySetter.Direct(
        nativeCall(setExport, ForwardAbiWireType.VOID, receiver, listOf(valueParameter(type))),
      )
    }
    return ForwardPropertyPlan(
      symbol = symbol,
      position = position,
      receiver = receiver,
      kotlinName = name,
      publicName = name.replaceFirstChar { it.uppercase() },
      type = type,
      getter = getter,
      setter = setter,
      helperRequirements = if (type.unwrapNullable() is BridgeType.Collection) {
        setOf(ForwardHelperRequirement.COLLECTION)
      } else emptySet(),
    ).validate()
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
    BridgeType.Unit, BridgeType.Char, BridgeType.String, is BridgeType.Primitive, is BridgeType.Enum,
    is BridgeType.ObjectHandle, is BridgeType.Collection -> true

    is BridgeType.Nullable -> isPlannable(type.type)
    else -> false
  }

  private fun BridgeType.unwrapNullable(): BridgeType = if (this is BridgeType.Nullable) type else this

  private fun BridgeType.wireType(): ForwardAbiWireType = when (val type = unwrapNullable()) {
    BridgeType.Unit -> ForwardAbiWireType.VOID
    BridgeType.Char -> ForwardAbiWireType.CHAR16
    BridgeType.String, is BridgeType.ObjectHandle, is BridgeType.Collection -> ForwardAbiWireType.POINTER
    is BridgeType.Enum -> ForwardAbiWireType.INT32
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

    else -> error("Forward property planner cannot choose a wire type for $type")
  }

  private fun BridgeType.inputWireType(): ForwardAbiWireType = when (unwrapNullable()) {
    BridgeType.String -> ForwardAbiWireType.STRING
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

    is BridgeType.ObjectHandle -> if (flow == ForwardFlow.INTO_KOTLIN) {
      ForwardConversion.HANDLE_TO_STABLE_REF
    } else {
      ForwardConversion.STABLE_REF_TO_HANDLE
    }

    is BridgeType.Collection -> if (flow == ForwardFlow.INTO_KOTLIN) {
      ForwardConversion.HANDLE_TO_COLLECTION
    } else {
      ForwardConversion.COLLECTION_TO_HANDLE
    }

    else -> ForwardConversion.DIRECT
  }
}
