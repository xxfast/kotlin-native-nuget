package io.github.xxfast.kotlin.native.nuget.processor.forward

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A property plan's `helperRequirements` has to cover its **receiver**, not just its declared
 * type. An extension property on a value class puts the receiver's conversion on the ABI transfer
 * (`receiver`, BOX_VALUE_CLASS), and the underlying's own helper (an enum ordinal, a UTF-8 string)
 * is needed to reconstruct it before the property access -- the same pairing
 * `ForwardCallablePlanner` applies to a value-class input.
 *
 * [helperRequirements] is asserted directly rather than through a Tier 1 run: nothing in the
 * generated text reads the set back, so a Tier 1 cell could only prove the plan validated, which
 * is the weaker half of the claim.
 */
class ForwardPropertyHelperRequirementsTest {

  private val mood: BridgeType.Enum = BridgeType.Enum("sample.Mood")
  private val temperament: BridgeType.ValueClass = BridgeType.ValueClass("sample.Temperament", mood)
  private val chartId: BridgeType.ValueClass =
    BridgeType.ValueClass("sample.ChartId", BridgeType.String)

  @Test
  fun `an enum-underlying value-class receiver contributes both its own helper and the ordinal one`() {
    val helpers: Set<ForwardHelperRequirement> = extensionPropertyHelpers(temperament)

    assertTrue(ForwardHelperRequirement.VALUE_CLASS in helpers, "got: $helpers")
    assertTrue(ForwardHelperRequirement.ENUM_ORDINAL in helpers, "got: $helpers")
  }

  @Test
  fun `a String-underlying value-class receiver contributes the utf8 helper`() {
    val helpers: Set<ForwardHelperRequirement> = extensionPropertyHelpers(chartId)

    assertTrue(ForwardHelperRequirement.VALUE_CLASS in helpers, "got: $helpers")
    assertTrue(ForwardHelperRequirement.UTF8 in helpers, "got: $helpers")
  }

  @Test
  fun `an ObjectHandle receiver needs only the StableRef helper its transfer names`() {
    val helpers: Set<ForwardHelperRequirement> =
      extensionPropertyHelpers(BridgeType.ObjectHandle("sample.Patient"))

    assertEquals(setOf(ForwardHelperRequirement.STABLE_REF, ForwardHelperRequirement.UTF8), helpers)
  }

  @Test
  fun `a plan whose helpers omit the receiver's own conversion fails validation`() {
    val plan: ForwardPropertyPlan = extensionPropertyPlan(
      temperament,
      helpers = setOf(ForwardHelperRequirement.STABLE_REF, ForwardHelperRequirement.UTF8),
    )

    val error: IllegalArgumentException = assertFailsWith { plan.validate() }

    assertTrue(
      error.message!!.contains("transfer receiver is missing helper VALUE_CLASS"),
      error.message!!,
    )
  }

  @Test
  fun `the computed helpers validate`() {
    val plan: ForwardPropertyPlan =
      extensionPropertyPlan(temperament, extensionPropertyHelpers(temperament))

    assertEquals(plan, plan.validate())
  }

  /** `var <receiver>.note: String`, the ADR-075 extension-property shape the planner builds. */
  private fun extensionPropertyHelpers(receiver: BridgeType): Set<ForwardHelperRequirement> {
    val plan: ForwardPropertyPlan = extensionPropertyPlan(receiver, emptySet())
    return helperRequirements(plan.type, plan.receiver, plan.getter, plan.setter)
  }

  private fun extensionPropertyPlan(
    receiver: BridgeType,
    helpers: Set<ForwardHelperRequirement>,
  ): ForwardPropertyPlan {
    val receiverParameter = ForwardAbiParameter(
      "receiver",
      ForwardAbiWireType.INT32,
      ForwardAbiDirection.IN,
      ForwardTransfer(
        "receiver", receiver, ForwardFlow.INTO_KOTLIN, ForwardPassing.VALUE,
        ForwardOwnership.BORROWED, receiver.conversion(ForwardFlow.INTO_KOTLIN),
      ),
      ForwardAbiRole.RECEIVER,
    )
    val value = ForwardAbiParameter(
      "value",
      ForwardAbiWireType.STRING,
      ForwardAbiDirection.IN,
      ForwardTransfer(
        "value", BridgeType.String, ForwardFlow.INTO_KOTLIN, ForwardPassing.VALUE,
        ForwardOwnership.BORROWED, ForwardConversion.STRING_TO_UTF8,
      ),
      ForwardAbiRole.SETTER_VALUE,
    )
    val error = ForwardAbiParameter(
      "errorOut",
      ForwardAbiWireType.POINTER,
      ForwardAbiDirection.OUT,
      ForwardTransfer(
        "error", BridgeType.ObjectHandle("kotlin.Throwable"), ForwardFlow.OUT_OF_KOTLIN,
        ForwardPassing.OUT, ForwardOwnership.BORROWED, ForwardConversion.STABLE_REF_TO_HANDLE,
      ),
      ForwardAbiRole.ERROR,
    )
    return ForwardPropertyPlan(
      symbol = "sample.ext.note",
      position = ForwardPropertyPosition.EXTENSION,
      receiver = ForwardPropertyReceiver.Value(receiver),
      kotlinName = "note",
      publicName = "Note",
      type = BridgeType.String,
      getter = ForwardPropertyGetter.Direct(
        ForwardNativeCall(
          "ext_get_note", ForwardAbiWireType.POINTER, listOf(receiverParameter, error),
        ),
      ),
      setter = ForwardPropertySetter.Direct(
        ForwardNativeCall(
          "ext_set_note", ForwardAbiWireType.VOID, listOf(receiverParameter, value, error),
        ),
      ),
      helperRequirements = helpers,
    )
  }
}
