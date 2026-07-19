package io.github.xxfast.kotlin.native.nuget.processor.forward

import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeArgument
import com.google.devtools.ksp.symbol.KSTypeParameter
import com.google.devtools.ksp.symbol.Modifier
import io.github.xxfast.kotlin.native.nuget.processor.cir.FLOW_TYPES
import io.github.xxfast.kotlin.native.nuget.processor.cir.LAMBDA_TYPES
import io.github.xxfast.kotlin.native.nuget.processor.cir.STATE_FLOW_TYPES
import io.github.xxfast.kotlin.native.nuget.processor.cir.SUSPEND_LAMBDA_TYPES
import io.github.xxfast.kotlin.native.nuget.processor.cir.expandAliases
import io.github.xxfast.kotlin.native.nuget.processor.cir.mapPackageToNamespace

/** The declarations whose StableRef handles are part of this forward export set. */
internal data class ForwardBridgeTypeContext(
  val exportedObjectHandles: Set<String>,
  val rootPackage: String = "",
  val rootNamespace: String = "",
)

/**
 * Classifies resolved, alias-expanded KSP types once, before planning decides how they move over
 * the ABI. The classifier is intentionally strict: an ordinary class is a handle only when its
 * declaration is in the export set, and every collection must retain all of its type arguments.
 */
internal class ForwardBridgeTypeClassifier(
  private val context: ForwardBridgeTypeContext,
) {
  fun classify(type: KSType): BridgeType {
    val expanded: KSType = type.expandAliases()
    val classified: BridgeType = classifyNonNullable(expanded)
    // `KSTypeAlias.type.resolve()` describes the alias target and can lose a `?` applied at the
    // alias use site, so retain nullability from both the original use and the expanded target.
    val isNullable: Boolean = type.isMarkedNullable || expanded.isMarkedNullable
    return if (isNullable) BridgeType.Nullable(classified) else classified
  }

  private fun classifyNonNullable(type: KSType): BridgeType {
    val declaration = type.declaration
    if (declaration is KSTypeParameter) {
      return BridgeType.Unsupported(
        declaration.simpleName.asString(),
        "type parameters require the named generic legacy route",
      )
    }

    val classDeclaration: KSClassDeclaration = declaration as? KSClassDeclaration
      ?: return BridgeType.RawKSType(
        declaration.qualifiedName?.asString() ?: declaration.simpleName.asString(),
      )
    val qualifiedName: String = classDeclaration.qualifiedName?.asString()
      ?: return BridgeType.Unsupported(
        classDeclaration.simpleName.asString(),
        "local and anonymous declarations are not bridgeable",
      )

    knownScalarType(qualifiedName)?.let { return it }
    if (qualifiedName == "kotlin.Char") return BridgeType.Char
    if (qualifiedName == "kotlin.String") return BridgeType.String
    specializedProtocol(qualifiedName)?.let { return it }
    collectionType(qualifiedName, type.arguments)?.let { return it }

    if (classDeclaration.classKind == ClassKind.ENUM_CLASS) {
      val simpleName: String = classDeclaration.simpleName.asString()
      val csharpType: String = if (context.rootNamespace.isEmpty()) {
        simpleName
      } else {
        val namespace: String = mapPackageToNamespace(
          classDeclaration.packageName.asString(),
          context.rootPackage,
          context.rootNamespace,
        )
        "global::$namespace.$simpleName"
      }
      return BridgeType.Enum(qualifiedName, csharpType)
    }
    if (classDeclaration.modifiers.contains(Modifier.VALUE)) return valueClass(classDeclaration, qualifiedName)
    if (classDeclaration.modifiers.contains(Modifier.SEALED)) {
      return BridgeType.SpecializedProtocol("sealed helper $qualifiedName")
    }
    if (classDeclaration.classKind == ClassKind.INTERFACE) {
      return BridgeType.SpecializedProtocol("interface bridge $qualifiedName")
    }
    if (classDeclaration.typeParameters.isNotEmpty()) {
      return BridgeType.SpecializedProtocol("generic declaration $qualifiedName")
    }
    val isClassOrObject: Boolean =
      classDeclaration.classKind == ClassKind.CLASS ||
          classDeclaration.classKind == ClassKind.OBJECT
    if (!isClassOrObject) {
      return BridgeType.Unsupported(
        qualifiedName,
        "${classDeclaration.classKind} declarations are not bridgeable",
      )
    }
    if (qualifiedName !in context.exportedObjectHandles) {
      return BridgeType.Unsupported(
        qualifiedName,
        "declaration is not in the exported object-handle set",
      )
    }
    return BridgeType.ObjectHandle(qualifiedName)
  }

  private fun valueClass(declaration: KSClassDeclaration, qualifiedName: String): BridgeType {
    val underlying: KSType = declaration.primaryConstructor
      ?.parameters
      ?.singleOrNull()
      ?.type
      ?.resolve()
      ?: return BridgeType.Unsupported(
        qualifiedName,
        "value class must have exactly one underlying property",
      )
    return BridgeType.ValueClass(qualifiedName, classify(underlying))
  }

  private fun collectionType(
    qualifiedName: String,
    arguments: List<KSTypeArgument>,
  ): BridgeType? {
    val kind: CollectionKind = when (qualifiedName) {
      "kotlin.collections.List" -> CollectionKind.LIST
      "kotlin.collections.MutableList" -> CollectionKind.MUTABLE_LIST
      "kotlin.collections.Map" -> CollectionKind.MAP
      "kotlin.collections.MutableMap" -> CollectionKind.MUTABLE_MAP
      "kotlin.collections.Set" -> CollectionKind.SET
      "kotlin.collections.MutableSet" -> CollectionKind.MUTABLE_SET
      else -> return null
    }
    val isMap: Boolean = kind == CollectionKind.MAP || kind == CollectionKind.MUTABLE_MAP
    if (arguments.any { argument -> argument.type == null }) return BridgeType.RawCollection(kind)
    if (isMap) {
      if (arguments.size != 2) return BridgeType.RawCollection(kind)
      val key: KSType = arguments[0].type?.resolve() ?: return BridgeType.RawCollection(kind)
      val value: KSType = arguments[1].type?.resolve() ?: return BridgeType.RawCollection(kind)
      return BridgeType.Collection(kind, key = classify(key), value = classify(value))
    }
    if (arguments.size != 1) return BridgeType.RawCollection(kind)
    val element: KSType = arguments.single().type?.resolve()
      ?: return BridgeType.RawCollection(kind)
    return BridgeType.Collection(kind, element = classify(element))
  }

  private fun knownScalarType(qualifiedName: String): BridgeType? = when (qualifiedName) {
    "kotlin.Boolean" -> BridgeType.Primitive(PrimitiveKind.BOOLEAN)
    "kotlin.Byte" -> BridgeType.Primitive(PrimitiveKind.BYTE)
    "kotlin.UByte" -> BridgeType.Primitive(PrimitiveKind.UBYTE)
    "kotlin.Short" -> BridgeType.Primitive(PrimitiveKind.SHORT)
    "kotlin.UShort" -> BridgeType.Primitive(PrimitiveKind.USHORT)
    "kotlin.Int" -> BridgeType.Primitive(PrimitiveKind.INT)
    "kotlin.UInt" -> BridgeType.Primitive(PrimitiveKind.UINT)
    "kotlin.Long" -> BridgeType.Primitive(PrimitiveKind.LONG)
    "kotlin.ULong" -> BridgeType.Primitive(PrimitiveKind.ULONG)
    "kotlin.Float" -> BridgeType.Primitive(PrimitiveKind.FLOAT)
    "kotlin.Double" -> BridgeType.Primitive(PrimitiveKind.DOUBLE)
    "kotlin.Unit" -> BridgeType.Unit
    else -> null
  }

  private fun specializedProtocol(qualifiedName: String): BridgeType.SpecializedProtocol? = when {
    // ADR-065: StateFlow is checked before plain Flow -- it is-a Flow, and this is an exact
    // qualifiedName match (not isAssignableFrom), so there is no risk of a StateFlow falling
    // through to the plain-flow branch below and losing its `.Value` legacy-route handling.
    qualifiedName in STATE_FLOW_TYPES -> BridgeType.SpecializedProtocol("state flow $qualifiedName")
    qualifiedName in FLOW_TYPES -> BridgeType.SpecializedProtocol("flow $qualifiedName")
    qualifiedName in LAMBDA_TYPES -> BridgeType.SpecializedProtocol("lambda $qualifiedName")
    qualifiedName in SUSPEND_LAMBDA_TYPES ->
      BridgeType.SpecializedProtocol("suspend lambda $qualifiedName")

    else -> null
  }
}

internal fun KSType.toBridgeType(context: ForwardBridgeTypeContext): BridgeType =
  ForwardBridgeTypeClassifier(context).classify(this)
