package io.github.xxfast.kotlin.native.nuget.processor.forward

import io.github.xxfast.kotlin.native.nuget.processor.cir.CirProperty
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ADR-040: the property-getter/setter half of [BridgeType.Interface]'s CIR projection
 * ([ForwardCirPropertyProjection]), the sibling of [ForwardInterfaceCirProjectionTest]'s
 * method-return coverage. A property getter shares the same "construct the backing wrapper, keep
 * the interface as the public type" rule as a method return, and its setter is the property-plan
 * route for sub-decision B's `NugetMarshal.HandleOf` lowering (methods/constructors go through
 * [ForwardCirPlanProjection] instead).
 */
class ForwardInterfacePropertyProjectionTest {
  private val petType = BridgeType.Interface("sample.Pet", csharpType = "IPet", backingType = "Pet")

  private val handleTransfer = ForwardTransfer(
    "handle", BridgeType.ObjectHandle("sample.Cat"), ForwardFlow.INTO_KOTLIN, ForwardPassing.VALUE,
    ForwardOwnership.BORROWED, ForwardConversion.HANDLE_TO_STABLE_REF,
  )
  private val handleParameter =
    ForwardAbiParameter("handle", ForwardAbiWireType.POINTER, ForwardAbiDirection.IN, handleTransfer)
  private val errorParameter = ForwardAbiParameter(
    "errorOut", ForwardAbiWireType.POINTER, ForwardAbiDirection.OUT,
    ForwardTransfer(
      "error", BridgeType.ObjectHandle("kotlin.Throwable"), ForwardFlow.OUT_OF_KOTLIN, ForwardPassing.OUT,
      ForwardOwnership.BORROWED, ForwardConversion.STABLE_REF_TO_HANDLE,
    ),
  )

  private fun valueParameter(type: BridgeType): ForwardAbiParameter = ForwardAbiParameter(
    "value", ForwardAbiWireType.POINTER, ForwardAbiDirection.IN,
    ForwardTransfer(
      "value",
      type,
      ForwardFlow.INTO_KOTLIN,
      ForwardPassing.VALUE,
      ForwardOwnership.BORROWED,
      ForwardConversion.HANDLE_TO_STABLE_REF
    ),
  )

  @Test
  fun `non-null interface property getter constructs the backing class but reads as the interface type`() {
    val plan = ForwardPropertyPlan(
      symbol = "sample.Cat.self",
      position = ForwardPropertyPosition.CLASS,
      receiver = ForwardPropertyReceiver.Handle("sample.Cat"),
      kotlinName = "self",
      publicName = "Self",
      type = petType,
      getter = ForwardPropertyGetter.Direct(
        ForwardNativeCall("cat_get_self", ForwardAbiWireType.POINTER, listOf(handleParameter, errorParameter)),
      ),
    ).validate()

    val property: CirProperty = ForwardCirPropertyProjection.classProperty(plan)

    assertEquals("IPet", property.type)
    // ADR-084 facet 5: the wrapper construction is now the identity probe's fallback.
    assertTrue(
      property.getter.contains(
        "return (NugetMarshal.TryResolveCSharp(nativeResult, out IPet csharpOriginal) " +
            "? csharpOriginal : new Pet(nativeResult));",
      ),
    )
  }

  @Test
  fun `nullable interface property getter and setter null-guard through the backing class and HandleOf`() {
    val nullablePet = BridgeType.Nullable(petType)
    val plan = ForwardPropertyPlan(
      symbol = "sample.Cat.friend",
      position = ForwardPropertyPosition.CLASS,
      receiver = ForwardPropertyReceiver.Handle("sample.Cat"),
      kotlinName = "friend",
      publicName = "Friend",
      type = nullablePet,
      getter = ForwardPropertyGetter.Direct(
        ForwardNativeCall("cat_get_friend", ForwardAbiWireType.POINTER, listOf(handleParameter, errorParameter)),
      ),
      setter = ForwardPropertySetter.Direct(
        ForwardNativeCall(
          "cat_set_friend",
          ForwardAbiWireType.VOID,
          listOf(handleParameter, valueParameter(nullablePet), errorParameter),
        ),
      ),
    ).validate()

    val property: CirProperty = ForwardCirPropertyProjection.classProperty(plan)

    assertEquals("IPet?", property.type)
    assertTrue(
      property.getter.contains(
        "return nativeResult == IntPtr.Zero ? null : " +
            "(NugetMarshal.TryResolveCSharp(nativeResult, out IPet csharpOriginal) " +
            "? csharpOriginal : new Pet(nativeResult));",
      ),
      "expected the nullable getter to null-guard then construct the backing class; got: ${property.getter}",
    )
    val setter: String = requireNotNull(property.setter) { "expected a setter body" }
    assertTrue(
      // ADR-084 stage 3: the nullable lowering is HandleOfOrZero (null ships IntPtr.Zero and mints
      // nothing), extracted before the call so a minted transfer handle can be disposed after it.
      setter.contains("IntPtr valueHandle = NugetMarshal.HandleOfOrZero(value, out bool valueOwned);") &&
          setter.contains("if (valueOwned) { NugetMarshal.Dispose(valueHandle); }"),
      "expected the nullable setter to lower through the shared HandleOf reflective helper with " +
          "a null guard, not a direct ._handle read; got: $setter",
    )
  }

  @Test
  fun `non-null interface property setter lowers via HandleOf without a null guard`() {
    val plan = ForwardPropertyPlan(
      symbol = "sample.Cat.mentor",
      position = ForwardPropertyPosition.CLASS,
      receiver = ForwardPropertyReceiver.Handle("sample.Cat"),
      kotlinName = "mentor",
      publicName = "Mentor",
      type = petType,
      getter = ForwardPropertyGetter.Direct(
        ForwardNativeCall("cat_get_mentor", ForwardAbiWireType.POINTER, listOf(handleParameter, errorParameter)),
      ),
      setter = ForwardPropertySetter.Direct(
        ForwardNativeCall(
          "cat_set_mentor",
          ForwardAbiWireType.VOID,
          listOf(handleParameter, valueParameter(petType), errorParameter),
        ),
      ),
    ).validate()

    val property: CirProperty = ForwardCirPropertyProjection.classProperty(plan)

    val setter: String = requireNotNull(property.setter) { "expected a setter body" }
    assertTrue(
      setter.contains("IntPtr valueHandle = NugetMarshal.HandleOf(value, out bool valueOwned);") &&
          setter.contains("if (valueOwned) { NugetMarshal.Dispose(valueHandle); }") &&
          !setter.contains("value != null"),
      "expected a plain HandleOf lowering with no null guard for a non-nullable interface " +
          "property; got: $setter",
    )
    assertEquals("IntPtr", property.nativeSetterType)
  }
}
