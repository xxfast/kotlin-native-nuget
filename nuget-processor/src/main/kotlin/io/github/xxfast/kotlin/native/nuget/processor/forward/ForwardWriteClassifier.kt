package io.github.xxfast.kotlin.native.nuget.processor.forward

/**
 * ADR-240: the module's forward classifier, for the `MutableStateFlow`/`MutableSharedFlow` write
 * seam, whose classification (`classifyMutableStateFlowElement`) is read from a dozen sites with
 * no classifier in reach. Installed once per processing round by `NugetProcessor`, per thread, so
 * two processor runs on two threads never read each other's.
 */
internal object ForwardWriteClassifier {
  private val holder: ThreadLocal<ForwardBridgeTypeClassifier?> = ThreadLocal()

  var current: ForwardBridgeTypeClassifier?
    get() = holder.get()
    set(value) = holder.set(value)
}
