package io.github.xxfast.kotlin.native.nuget.processor

import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSNode
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardCallablePlanCatalog
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDroppedProperty
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardPlanSkipReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-064's 2026-09-11 amendment, property half: a dropped property carries the same
 * [ForwardPlanSkipReason] a dropped callable does, so a scope, nesting, sealed or opt-in drop
 * reads the reason's own sentence and remedy instead of the generic "its type X has no property
 * getter or setter shape ... expose a property whose type is not X", which named the wrong
 * declaration and suggested a fix that could not work.
 *
 * The kind stays [ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_PROPERTY] for every routed record:
 * it names the *position*, which is still what happened, and `toDiagnosticKind()` `error()`s on
 * the legacy-route reasons a property can genuinely hold ([ForwardPlanSkipReason.GENERIC]).
 */
class ForwardSkippedPropertyWarningTest {
  private class RecordingLogger : KSPLogger {
    val warnings: MutableList<String> = mutableListOf()
    override fun logging(message: String, symbol: KSNode?) = Unit
    override fun info(message: String, symbol: KSNode?) = Unit
    override fun warn(message: String, symbol: KSNode?) {
      warnings.add(message)
    }

    override fun error(message: String, symbol: KSNode?) = Unit
    override fun exception(e: Throwable) = Unit
  }

  private fun warn(
    dropped: ForwardDroppedProperty,
    scope: List<String> = emptyList(),
  ): String {
    val logger = RecordingLogger()
    warnDroppedForwardProperties(
      ForwardCallablePlanCatalog(entries = emptyList(), droppedProperties = listOf(dropped)),
      logger,
      scope,
    )
    return logger.warnings.single()
  }

  @Test
  fun `a nested-enum property reads the enum's own sentence and move-to-top-level hint`() {
    val warning: String = warn(
      ForwardDroppedProperty(
        symbol = "com.example.Owner.mode",
        node = null,
        typeDescription = "Unsupported",
        reason = ForwardPlanSkipReason.UNDECLARED_ENUM,
        detail = "com.example.Owner.Mode",
      ),
    )

    assertTrue(
      warning.contains(ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_PROPERTY.name),
      "the position kind is unchanged: $warning",
    )
    assertTrue(
      warning.contains("its enum type `com.example.Owner.Mode` is never declared as a C# enum"),
      "the reason's sentence names the enum: $warning",
    )
    assertTrue(
      warning.contains("move it to the top level"),
      "the reason's hint names the move-to-top-level fix: $warning",
    )
    assertFalse(
      warning.contains("has no property getter"),
      "the generic property sentence is gone: $warning",
    )
  }

  @Test
  fun `an out-of-scope dependency property names the whole include line`() {
    val warning: String = warn(
      ForwardDroppedProperty(
        symbol = "app.Newsroom.sponsor",
        node = null,
        typeDescription = "Unsupported",
        reason = ForwardPlanSkipReason.UNEXPORTED_DEPENDENCY_TYPE,
        detail = "dep.Advert",
      ),
      scope = listOf("app"),
    )

    assertTrue(
      warning.contains("is declared in a dependency module outside the export scope"),
      "the reason's sentence says out of scope, not unsupported: $warning",
    )
    assertTrue(
      warning.contains("include(\"app\", \"dep\")"),
      "the hint names the author's own package beside the missing one: $warning",
    )
  }

  // The two records that must keep the shipped text verbatim: one with no reason at all (nothing
  // classified it), and one whose reason is a legacy-route deferral, where the reason's own
  // sentence would say "its GENERIC type combination is not supported" and `toDiagnosticKind()`
  // would `error()`.
  @Test
  fun `a property with no reason, or a legacy-route one, keeps the shipped wording`() {
    val expected: List<ForwardDroppedProperty> = listOf(
      ForwardDroppedProperty(
        symbol = "com.example.Patient.box",
        node = null,
        typeDescription = "Box<Int>",
      ),
      ForwardDroppedProperty(
        symbol = "com.example.Cat.unsupported",
        node = null,
        typeDescription = "Sequence<Int>",
        reason = ForwardPlanSkipReason.GENERIC,
      ),
    )

    expected.forEach { dropped ->
      val warning: String = warn(dropped)
      assertTrue(
        warning.contains(
          "its type ${dropped.typeDescription} has no property getter or setter shape",
        ),
        "${dropped.symbol} keeps the shipped sentence: $warning",
      )
      assertTrue(
        warning.contains(
          "expose a bridgeable property (or a getter function) whose type is not " +
              "${dropped.typeDescription}, and export that instead",
        ),
        "${dropped.symbol} keeps the shipped hint: $warning",
      )
      assertFalse(
        warning.contains("type combination is not supported"),
        "${dropped.symbol} does not pick up the generic reason sentence: $warning",
      )
    }
  }

  @Test
  fun `one warning per dropped property`() {
    val logger = RecordingLogger()
    warnDroppedForwardProperties(
      ForwardCallablePlanCatalog(
        entries = emptyList(),
        droppedProperties = listOf(
          ForwardDroppedProperty("a.B.one", null, "Sequence<Int>"),
          ForwardDroppedProperty(
            "a.B.two", null, "Unsupported",
            reason = ForwardPlanSkipReason.SEALED_POSITION, detail = "a.Filter",
          ),
        ),
      ),
      logger,
    )

    assertEquals(2, logger.warnings.size, "one warning per record: ${logger.warnings}")
    assertTrue(
      logger.warnings[1].contains(
        "its sealed type `a.Filter` has no generated C# discriminator",
      ),
      "a sealed-position property reads the sealed sentence: ${logger.warnings[1]}",
    )
  }
}
