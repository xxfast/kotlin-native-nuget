package io.github.xxfast.kotlin.native.nuget.processor.forward

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** ADR-216 (second amendment): the one collector every generated C# body fills its slots from. */
class ForwardOpenSlotsTest {
  private val inner = BridgeType.ObjectHandle(
    "pkg.Basket",
    csharpType = "global::Ns.Basket<T>",
    open = true,
  )
  private val outer = BridgeType.ObjectHandle(
    "pkg.Basket",
    csharpType = "global::Ns.Basket<global::Ns.Basket<T>>",
    open = true,
    openArguments = listOf(inner),
  )
  private val closed = BridgeType.ObjectHandle("pkg.Basket", csharpType = "global::Ns.Basket<int>")
  private val pickup = BridgeType.ObjectHandle(
    "pkg.Pickup",
    csharpType = "global::Ns.Pickup<T>",
    viaDiscriminator = true,
    open = true,
  )
  private val cabinet = BridgeType.ObjectHandle(
    "pkg.Cabinet",
    csharpType = "global::Ns.Cabinet<T>",
    constructType = "global::Ns.Cabinet.Backing<T>",
    open = true,
  )

  private fun BridgeType.types(): List<String> = openSlots().map { it.csharpType }

  @Test
  fun `an open instantiation fills itself and every nested one`() {
    assertEquals(listOf(outer.csharpType, inner.csharpType), outer.types())
  }

  @Test
  fun `a closed instantiation fills nothing`() {
    assertEquals(emptyList(), closed.types())
  }

  @Test
  fun `every composite is walked`() {
    val positions: List<BridgeType> = listOf(
      BridgeType.Nullable(outer),
      BridgeType.Collection(CollectionKind.LIST, element = outer),
      BridgeType.Collection(CollectionKind.MAP, key = BridgeType.String, value = outer),
      BridgeType.Callback(listOf(outer), BridgeType.Unit),
      BridgeType.Callback(emptyList(), outer),
      BridgeType.AsyncCallback(listOf(outer), BridgeType.Unit),
      BridgeType.FlowSource(outer),
      BridgeType.ReturnedLambda(listOf(outer), listOf("pkg.Basket<pkg.Basket<T>>")),
    )
    positions.forEach { position ->
      assertEquals(listOf(outer.csharpType, inner.csharpType), position.types(), "$position")
    }
  }

  @Test
  fun `a root the body reconstructs fills only what it nests`() {
    assertEquals(listOf(inner.csharpType), outer.openSlotsBeneath().map { it.csharpType })
    val nullable: BridgeType = BridgeType.Nullable(outer)
    assertEquals(listOf(inner.csharpType), nullable.openSlotsBeneath().map { it.csharpType })
    assertEquals(emptyList(), inner.openSlotsBeneath())
    val list = BridgeType.Collection(CollectionKind.LIST, element = inner)
    assertEquals(listOf(inner.csharpType), list.openSlotsBeneath().map { it.csharpType })
  }

  @Test
  fun `a sealed handle reads through its discriminator`() {
    val helper = BridgeType.SpecializedProtocol("sealed pkg.Pickup", sealedHandle = pickup)
    assertEquals(
      listOf(OpenSlot("global::Ns.Pickup<T>", "global::Ns.Pickup<T>.FromHandle(handle)")),
      helper.openSlots(),
    )
  }

  @Test
  fun `an abstract class constructs its backing`() {
    assertEquals(
      "new global::Ns.Cabinet.Backing<T>(handle, out _)",
      cabinet.openSlots().single().factory,
    )
  }

  @Test
  fun `fills are deduplicated across a signature and lead the body`() {
    val fills: String = listOf(outer, BridgeType.Collection(CollectionKind.LIST, element = inner))
      .openSlotFills()
    assertEquals(2, fills.lines().count { it.isNotBlank() })
    assertTrue(
      fills.startsWith("            NugetFactory<global::Ns.Basket<global::Ns.Basket<T>>>"),
    )
    val body = "\n            return x;"
    assertEquals("\n${fills}            return x;", body.withOpenSlotFills(fills))
    assertEquals("\n            return x;", "\n            return x;".withOpenSlotFills(""))
  }
}
