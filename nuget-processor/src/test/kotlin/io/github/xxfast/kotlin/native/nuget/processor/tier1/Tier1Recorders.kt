package io.github.xxfast.kotlin.native.nuget.processor.tier1

import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSNode
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector

/**
 * ADR-060 Tier 1's `KSPLogger`: the harness supplies this instead of KSP's own console logger
 * so `logger.error`/`warn` calls the real `NugetProcessor` makes are captured directly — this
 * is the "diagnostic" assertion mode (cell 23 and the forward-diagnostics item, MVP.md P1),
 * free by-product of driving the real processor rather than a text fixture.
 */
internal class RecordingKSPLogger : KSPLogger {
  val errors: MutableList<String> = mutableListOf()
  val warnings: MutableList<String> = mutableListOf()
  val info: MutableList<String> = mutableListOf()

  override fun logging(message: String, symbol: KSNode?) {}
  override fun info(message: String, symbol: KSNode?) {
    info += message
  }

  override fun warn(message: String, symbol: KSNode?) {
    warnings += message
  }

  override fun error(message: String, symbol: KSNode?) {
    errors += message
  }

  override fun exception(e: Throwable) {
    errors += (e.message ?: e.toString())
  }
}

/**
 * ADR-060 Tier 1's `MessageCollector` for the [org.jetbrains.kotlin.cli.jvm.K2JVMCompiler]
 * compile step. The reported diagnostics *are* the assertion (ADR-060 "Tier 1 compiles; it
 * does not substring-match") — this just captures them instead of printing to stdout.
 */
internal class RecordingMessageCollector : MessageCollector {
  val errors: MutableList<String> = mutableListOf()
  val warnings: MutableList<String> = mutableListOf()
  private var sawError: Boolean = false

  override fun clear() {
    errors.clear()
    warnings.clear()
    sawError = false
  }

  override fun report(
    severity: CompilerMessageSeverity,
    message: String,
    location: CompilerMessageSourceLocation?,
  ) {
    val formatted: String = if (location != null) {
      "${location.path}:${location.line}:${location.column}: $message"
    } else {
      message
    }

    when {
      severity.isError -> {
        sawError = true
        errors += formatted
      }

      severity.isWarning -> warnings += formatted
    }
  }

  override fun hasErrors(): Boolean = sawError
}
