package io.github.xxfast.kotlin.native.nuget.processor.forward

import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The one public C# speller, [forwardPublicCsharpType]. It used to have a private twin on the
 * property route; each ended in `else -> error(...)`, so neither said which variant the other
 * spelled. Every [BridgeType] variant is visited here: the spellable ones render a non-empty
 * type, and the four that never reach a public signature fail with a named error.
 */
class ForwardPublicCsharpTypeTest {

  /** One sample per variant. [variantOf] keeps this list honest at compile time. */
  private val samples: List<BridgeType> = listOf(
    BridgeType.Unit,
    BridgeType.Primitive(PrimitiveKind.INT),
    BridgeType.Char,
    BridgeType.String,
    BridgeType.Instant,
    BridgeType.Duration,
    BridgeType.Throwable("kotlin.Throwable"),
    BridgeType.Uuid,
    BridgeType.Enum("pkg.Mood"),
    BridgeType.ObjectHandle("pkg.Cat"),
    BridgeType.Interface("pkg.Pet"),
    BridgeType.BoundInterface("dotnet.IClock", "global::Contoso.IClock"),
    BridgeType.ValueClass("pkg.ChartId", BridgeType.String),
    BridgeType.ByteArray,
    BridgeType.Collection(CollectionKind.LIST, element = BridgeType.String),
    BridgeType.Nullable(BridgeType.String),
    BridgeType.Callback(listOf(BridgeType.String), BridgeType.Unit),
    BridgeType.ReturnedLambda(listOf(BridgeType.Unit), listOf("Unit")),
    BridgeType.SpecializedProtocol("Flow"),
    BridgeType.RawKSType("Foo"),
    BridgeType.Unsupported("Foo", "no route"),
    BridgeType.RawCollection(CollectionKind.LIST),
    BridgeType.TypeParameter("T"),
  )

  /**
   * Exhaustive with no `else`, like the speller: a new [BridgeType] variant stops this test file
   * compiling until it is named here, and then [samples] has to grow to keep the count equal.
   */
  private fun variantOf(type: BridgeType): KClass<out BridgeType> = when (type) {
    BridgeType.Unit -> BridgeType.Unit::class
    is BridgeType.Primitive -> BridgeType.Primitive::class
    BridgeType.Char -> BridgeType.Char::class
    BridgeType.String -> BridgeType.String::class
    BridgeType.Instant -> BridgeType.Instant::class
    BridgeType.Duration -> BridgeType.Duration::class
    is BridgeType.Throwable -> BridgeType.Throwable::class
    BridgeType.Uuid -> BridgeType.Uuid::class
    is BridgeType.Enum -> BridgeType.Enum::class
    is BridgeType.ObjectHandle -> BridgeType.ObjectHandle::class
    is BridgeType.Interface -> BridgeType.Interface::class
    is BridgeType.BoundInterface -> BridgeType.BoundInterface::class
    is BridgeType.ValueClass -> BridgeType.ValueClass::class
    BridgeType.ByteArray -> BridgeType.ByteArray::class
    is BridgeType.Collection -> BridgeType.Collection::class
    is BridgeType.Nullable -> BridgeType.Nullable::class
    is BridgeType.Callback -> BridgeType.Callback::class
    is BridgeType.ReturnedLambda -> BridgeType.ReturnedLambda::class
    is BridgeType.SpecializedProtocol -> BridgeType.SpecializedProtocol::class
    is BridgeType.RawKSType -> BridgeType.RawKSType::class
    is BridgeType.Unsupported -> BridgeType.Unsupported::class
    is BridgeType.RawCollection -> BridgeType.RawCollection::class
    is BridgeType.TypeParameter -> BridgeType.TypeParameter::class
  }

  /** The variants no public signature ever carries: planning skips them by name first. */
  private val unspellable: Set<KClass<out BridgeType>> = setOf(
    BridgeType.SpecializedProtocol::class,
    BridgeType.RawKSType::class,
    BridgeType.Unsupported::class,
    BridgeType.RawCollection::class,
  )

  @Test
  fun `every variant is sampled exactly once`() {
    val variants: List<KClass<out BridgeType>> = samples.map(::variantOf)
    assertEquals(variants.size, variants.toSet().size, "a variant is sampled twice: $variants")
    assertEquals(23, variants.size, "a BridgeType variant was added; sample it here")
  }

  @Test
  fun `every spellable variant renders a non-empty public type`() {
    samples.filter { variantOf(it) !in unspellable }.forEach { type ->
      val spelled: String = type.forwardPublicCsharpType()
      assertTrue(spelled.isNotBlank(), "$type spelled blank")
    }
  }

  @Test
  fun `an unspellable variant fails naming itself rather than guessing`() {
    samples.filter { variantOf(it) in unspellable }.forEach { type ->
      val failure = assertFailsWith<IllegalStateException> { type.forwardPublicCsharpType() }
      assertTrue("$type" in failure.message.orEmpty(), failure.message.orEmpty())
    }
  }

  /** The two arms each former copy alone had now render through the one speller. */
  @Test
  fun `the former blind spots render`() {
    val throwable = BridgeType.Throwable("kotlin.Throwable")
    val bound = BridgeType.BoundInterface("dotnet.IClock", "global::Contoso.IClock")
    assertEquals("global::System.Exception", throwable.forwardPublicCsharpType())
    assertEquals("global::Contoso.IClock", bound.forwardPublicCsharpType())
    assertEquals("void", BridgeType.Unit.forwardPublicCsharpType())
    assertEquals(
      "global::System.Exception?",
      BridgeType.Nullable(throwable).forwardPublicCsharpType(),
    )
  }
}
