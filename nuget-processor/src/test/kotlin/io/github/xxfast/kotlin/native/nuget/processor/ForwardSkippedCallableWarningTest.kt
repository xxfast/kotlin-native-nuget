package io.github.xxfast.kotlin.native.nuget.processor

import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSNode
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardCallableCatalogEntry
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardCallablePlanCatalog
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardPlanSkipReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ForwardSkippedCallableWarningTest {
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

  @Test
  fun `a dropped skip warns with the symbol and reason`() {
    val logger = RecordingLogger()
    val catalog = ForwardCallablePlanCatalog(
      entries = listOf(
        ForwardCallableCatalogEntry.Skipped(
          symbol = "com.example.Api.consume",
          reason = ForwardPlanSkipReason.COLLECTION,
        ),
      ),
    )

    warnDroppedForwardCallables(catalog, logger)

    assertEquals(1, logger.warnings.size)
    val warning: String = logger.warnings.single()
    assertTrue(warning.contains("com.example.Api.consume"), "warning names the symbol: $warning")
    assertTrue(warning.contains("COLLECTION"), "warning names the reason: $warning")
  }

  @Test
  fun `a legacy-routed skip produces no warning`() {
    val logger = RecordingLogger()
    val catalog = ForwardCallablePlanCatalog(
      entries = listOf(
        ForwardCallableCatalogEntry.Skipped(
          symbol = "com.example.Api.observe",
          reason = ForwardPlanSkipReason.SUSPEND,
        ),
        ForwardCallableCatalogEntry.Skipped(
          symbol = "com.example.Api.stream",
          reason = ForwardPlanSkipReason.FLOW_PROTOCOL,
        ),
        ForwardCallableCatalogEntry.Skipped(
          symbol = "com.example.Api.onEach",
          reason = ForwardPlanSkipReason.CALLBACK_PROTOCOL,
        ),
      ),
    )

    warnDroppedForwardCallables(catalog, logger)

    assertTrue(logger.warnings.isEmpty(), "legacy-routed skips stay silent: ${logger.warnings}")
  }

  @Test
  fun `only dropped reasons are classified as dropped from C#`() {
    val dropped: Set<ForwardPlanSkipReason> = ForwardPlanSkipReason.entries
      .filter { it.droppedFromCSharp }
      .toSet()

    assertEquals(
      setOf(
        ForwardPlanSkipReason.CHAR,
        ForwardPlanSkipReason.COLLECTION,
        ForwardPlanSkipReason.ENUM,
        ForwardPlanSkipReason.HANDLE,
        ForwardPlanSkipReason.NULLABLE,
        ForwardPlanSkipReason.OBJECT,
        ForwardPlanSkipReason.STRING,
        ForwardPlanSkipReason.UNSUPPORTED,
        ForwardPlanSkipReason.VALUE_CLASS,
        // ADR-064: genuine drops with their own named diagnostic kind (cell 23's combination,
        // and a value-class member inherited via interface delegation).
        ForwardPlanSkipReason.UNSUPPORTED_COMBINATION,
        ForwardPlanSkipReason.INHERITED_MEMBER,
      ),
      dropped,
    )
  }
}
