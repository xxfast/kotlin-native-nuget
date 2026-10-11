package io.github.xxfast.kotlin.native.nuget.processor.cir

import io.github.xxfast.kotlin.native.nuget.processor.ForwardSymbolTable
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardFlowArgument
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardFlowArgumentKind

/**
 * ADR-208 part E: the C# half of one closed flow type argument, the externs it reads through and
 * the `NugetMarshal.Factories` line that builds `box.Value` from the flow's handle.
 */
internal class CirFlowArgument(
  val imports: List<CirDllImport>,
  val factory: CirFactoryEntry,
)

/**
 * The materialiser of [this] argument. `Materialize<T>` runs inside `Box<T>.Value`, with no
 * producing owner in hand, so the holder cannot borrow an owner's scope the way an acquired
 * `Flow` does (ADR-194). It creates its own instead and owns it: `Dispose` cancels the scope
 * (and with it every collection started from this holder) and then releases the flow handle.
 * Nothing here is a child of any class's scope, so no owner's `DisposeAsync` drain waits on it.
 *
 * A reference-typed element spells one runtime `Type` for `Flow<E>` and `Flow<E?>`; the entries
 * are rendered in spelling order, so the nullable one, whose export and read cover both, wins.
 */
internal fun ForwardFlowArgument.toCir(
  libraryName: String,
  symbols: ForwardSymbolTable,
): CirFlowArgument {
  val stem: String = exportStem(symbols)
  val collect: String = "${nativeStem}_Collect"
  val value: String = "${nativeStem}_Value"
  val isState: Boolean = kind == ForwardFlowArgumentKind.STATE_FLOW
  val imports: List<CirDllImport> = if (isState) {
    awaitedStateFlowCollectionImports(libraryName, stem, collect, value)
  } else {
    listOf(acquiredFlowCollectImport(libraryName, stem, collect))
  }
  val construct: String = buildString {
    appendLine("{")
    appendLine("                var scope = new NugetScopeHandle(NugetScopeNative.Create());")
    appendLine("                return new $csharpType(")
    appendLine("                    (onNext, onComplete, onError, userData) =>")
    appendLine("                    {")
    appendLine("                        if (handle.IsClosed || scope.IsClosed)")
    appendLine(
      "                            throw new ObjectDisposedException(\"${kind.csharpName}\");",
    )
    appendLine(
      "                        return $collect(handle, scope, onNext, onComplete, onError, " +
        "userData);",
    )
    appendLine("                    },")
    if (isState) appendLine("                    () => $value(handle),")
    // Named, as at the acquired-flow site: a collection element's `read:` carries a `release:`.
    if (read != null) appendLine("                    $read,")
    appendLine("                    ownedHandle: handle, ownedScope: scope);")
    append("            }")
  }
  return CirFlowArgument(
    imports = imports,
    factory = CirFactoryEntry(
      qualifiedTypeName = csharpType.removePrefix("global::"),
      constructExpression = construct,
    ),
  )
}
