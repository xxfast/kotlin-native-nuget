package io.github.xxfast.kotlin.native.nuget.processor.cir

import com.google.devtools.ksp.getDeclaredProperties
import com.google.devtools.ksp.symbol.KSClassDeclaration
import io.github.xxfast.kotlin.native.nuget.processor.forward.BridgeType
import io.github.xxfast.kotlin.native.nuget.processor.forward.CollectionKind
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardBridgeTypeClassifier
import io.github.xxfast.kotlin.native.nuget.processor.forward.csharpMemberName
import io.github.xxfast.kotlin.native.nuget.processor.forward.forwardPublicCsharpType

/**
 * ADR-242: the value shape of [cls], or `null` when the forward classifier keeps it a handle. The
 * one admission verdict is the classifier's, so the class renderer and every lowering that mints or
 * reads a `BridgeType.DataValue` agree on which classes are values.
 */
internal fun cirDataValue(
  cls: KSClassDeclaration,
  classifier: ForwardBridgeTypeClassifier,
): CirDataValue? {
  if (!classifier.isDataValue(cls)) return null
  val value = classifier.classify(cls.asStarProjectedType()) as BridgeType.DataValue
  val properties = cls.getDeclaredProperties().associateBy { it.simpleName.asString() }
  return CirDataValue(
    value.components.map { component ->
      CirDataValueComponent(
        propertyName = properties.getValue(component.name).csharpMemberName(),
        defensiveCopy = component.type.defensiveCopy(),
      )
    },
  )
}

/**
 * The public constructor's read of a component argument, `{0}` standing for it. A collection is
 * copied, so a caller mutating the array or list it passed cannot change the value; a nullable
 * collection keeps its null.
 */
private fun BridgeType.defensiveCopy(): String = when (this) {
  is BridgeType.Nullable -> {
    val inner: String = type.defensiveCopy()
    if (inner == "{0}") "{0}" else "{0} == null ? null : ${inner.replace("{0}", "{0}!")}"
  }
  is BridgeType.Collection -> when (kind) {
    CollectionKind.LIST, CollectionKind.MUTABLE_LIST ->
      "global::System.Array.AsReadOnly(global::System.Linq.Enumerable.ToArray({0}))"
    CollectionKind.SET, CollectionKind.MUTABLE_SET ->
      "new global::System.Collections.Generic.HashSet<${element.spelled()}>({0})"
    CollectionKind.MAP, CollectionKind.MUTABLE_MAP ->
      "new global::System.Collections.ObjectModel.ReadOnlyDictionary<${key.spelled()}, " +
          "${value.spelled()}>(new global::System.Collections.Generic.Dictionary<" +
          "${key.spelled()}, ${value.spelled()}>({0}))"
  }
  else -> "{0}"
}

private fun BridgeType?.spelled(): String =
  checkNotNull(this) { "ADR-242: a data value collection component without its type argument" }
    .forwardPublicCsharpType()
