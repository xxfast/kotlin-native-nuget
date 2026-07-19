package io.github.xxfast.kotlin.native.nuget.processor.forward

import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSNode

/**
 * ADR-064: wraps the real [KSPLogger] so
 * [io.github.xxfast.kotlin.native.nuget.processor.NugetProcessor] can tell, immediately after
 * generation, whether any `ERROR_*` [ForwardDiagnostic] fired — `logger.error` itself returns
 * nothing, and KSP's own round-failure detection only surfaces after `process()` returns, which
 * is too late to skip writing `cNameExports.kt` for a construct that must never compile
 * (ADR-034's C# constructor-signature collision).
 */
internal class ForwardDiagnosticTrackingLogger(
  private val delegate: KSPLogger,
) : KSPLogger by delegate {
  var hasFatalDiagnostic: Boolean = false
    private set

  override fun error(message: String, symbol: KSNode?) {
    hasFatalDiagnostic = true
    delegate.error(message, symbol)
  }
}
