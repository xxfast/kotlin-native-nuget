package io.github.xxfast.kotlin.native.nuget.processor.cir

import io.github.xxfast.kotlin.native.nuget.processor.forward.BridgeType
import io.github.xxfast.kotlin.native.nuget.processor.forward.CollectionKind
import io.github.xxfast.kotlin.native.nuget.processor.forward.forwardPublicCsharpType
import io.github.xxfast.kotlin.native.nuget.processor.forward.unwrapNullable

/**
 * ADR-240: the `Equals(V)` and `GetHashCode()` members of a record struct over a `ByteArray` or a
 * collection, which compare the payload's CONTENT rather than its reference: `byte[]` by
 * sequence, a list element-wise in order, a set order-insensitively, a map by the same keys with
 * equal values. A nested component recurses; anything else (a scalar, a string, another struct,
 * an interface) compares by its own `EqualityComparer<T>.Default`, so a nested struct compares
 * through its own members. `default(V)` carries a null payload, which equals another null and
 * hashes to 0. Empty for every other underlying, which keeps the record's own equality.
 */
internal fun contentEqualityMembers(
  struct: String,
  property: String,
  underlying: BridgeType,
): List<String> {
  val inner: BridgeType = underlying.unwrapNullable()
  if (inner != BridgeType.ByteArray && inner !is BridgeType.Collection) return emptyList()
  // A lambda inside a struct cannot read `this`, so each body copies the payload to locals first.
  val payload: String = underlying.forwardPublicCsharpType()
  return listOf(
    "public bool Equals($struct other) { " +
      "$payload self = $property; $payload that = other.$property; " +
      "return ${contentEquals(inner, "self", "that", 1)}; }",
    "public override int GetHashCode() { $payload self = $property; " +
      "return ${contentHash(inner, "self", 1)}; }",
  )
}

private const val LINQ: String = "global::System.Linq.Enumerable"

private fun contentEquals(type: BridgeType, a: String, b: String, depth: Int): String {
  val inner: BridgeType = type.unwrapNullable()
  val guard = "ReferenceEquals($a, $b) || ($a is not null && $b is not null && "
  if (inner == BridgeType.ByteArray) {
    return "($guard global::System.MemoryExtensions.SequenceEqual<byte>($a, $b)))"
  }
  val collection: BridgeType.Collection = inner as? BridgeType.Collection
    ?: return "global::System.Collections.Generic.EqualityComparer<" +
      "${type.forwardPublicCsharpType()}>.Default.Equals($a, $b)"
  val index = "i$depth"
  val element = "e$depth"
  val entry = "kv$depth"
  val found = "v$depth"
  val body: String = when (collection.kind) {
    CollectionKind.LIST, CollectionKind.MUTABLE_LIST -> {
      val component: BridgeType = requireNotNull(collection.element)
      "$LINQ.All($LINQ.Range(0, $a.Count), $index => " +
        "${contentEquals(component, "$a[$index]", "$b[$index]", depth + 1)})"
    }
    CollectionKind.SET, CollectionKind.MUTABLE_SET ->
      "$LINQ.All($a, $element => $b.Contains($element))"
    CollectionKind.MAP, CollectionKind.MUTABLE_MAP -> {
      val value: BridgeType = requireNotNull(collection.value)
      "$LINQ.All($a, $entry => $b.TryGetValue($entry.Key, out var $found) && " +
        "${contentEquals(value, "$entry.Value", found, depth + 1)})"
    }
  }
  return "($guard$a.Count == $b.Count && $body))"
}

private fun contentHash(type: BridgeType, a: String, depth: Int): String {
  val inner: BridgeType = type.unwrapNullable()
  val hash = "h$depth"
  val element = "e$depth"
  val entry = "kv$depth"
  if (inner == BridgeType.ByteArray) {
    return "($a is null ? 0 : " +
      "$LINQ.Aggregate($a, 17, ($hash, $element) => unchecked($hash * 31 + $element)))"
  }
  val collection: BridgeType.Collection = inner as? BridgeType.Collection
    ?: return "global::System.Collections.Generic.EqualityComparer<" +
      "${type.forwardPublicCsharpType()}>.Default.GetHashCode($a!)"
  return when (collection.kind) {
    CollectionKind.LIST, CollectionKind.MUTABLE_LIST -> {
      val component: String = contentHash(requireNotNull(collection.element), element, depth + 1)
      "($a is null ? 0 : " +
        "$LINQ.Aggregate($a, 17, ($hash, $element) => unchecked($hash * 31 + $component)))"
    }
    // Order-insensitive: a sum of element hashes, so two equal sets hash alike in any order.
    CollectionKind.SET, CollectionKind.MUTABLE_SET -> {
      val component: String = contentHash(requireNotNull(collection.element), element, depth + 1)
      "($a is null ? 0 : " +
        "$LINQ.Aggregate($a, 0, ($hash, $element) => unchecked($hash + $component)))"
    }
    CollectionKind.MAP, CollectionKind.MUTABLE_MAP -> {
      val key: String = contentHash(requireNotNull(collection.key), "$entry.Key", depth + 1)
      val value: String = contentHash(requireNotNull(collection.value), "$entry.Value", depth + 1)
      "($a is null ? 0 : $LINQ.Aggregate($a, 0, ($hash, $entry) => " +
        "unchecked($hash + $key * 31 + $value)))"
    }
  }
}
