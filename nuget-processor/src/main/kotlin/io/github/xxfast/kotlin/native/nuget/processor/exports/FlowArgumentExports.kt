package io.github.xxfast.kotlin.native.nuget.processor.exports

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.TypeName
import io.github.xxfast.kotlin.native.nuget.processor.ForwardSymbolTable
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardExportOwnerTag
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardFlowArgument
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardFlowArgumentKind

/**
 * ADR-208 part E: the Kotlin half of every closed flow type argument (`Box<Flow<Mood>>`). One
 * `<lib>_flowarg_<kind>_<element>_collect` per instantiation, keyed on the flow's own handle and
 * built by the builder the acquired `Flow` route uses ([handleKeyedFlowCollectExport]), so each
 * element is projected by the same [itemBoxExpr]; a state flow gains the `_value` sibling. Module
 * local and regenerated with its C# half: no `nuget_*` runtime name is involved.
 *
 * Called last, once every route has classified its types.
 */
internal fun FileSpec.Builder.addFlowArgumentExports(
  arguments: Collection<ForwardFlowArgument>,
  symbols: ForwardSymbolTable,
) {
  if (arguments.isEmpty()) return
  // The coroutine surface the bodies name. Added here, not with the suspend block's imports,
  // because a module can reach a flow only through a box.
  addImport(NUGET_RUNTIME_PACKAGE, "collectForCSharp")
  addImport("kotlinx.coroutines", "CoroutineScope")
  addImport("kotlinx.coroutines", "Dispatchers")
  addImport("kotlinx.coroutines.flow", "collect")
  arguments.forEach { argument ->
    val stem: String = argument.exportStem(symbols)
    val flowType: TypeName = ClassName("kotlinx.coroutines.flow", argument.kind.kotlinName)
      .parameterizedBy(argument.element.toBridgeTypeName())
    val boxed: String =
      itemBoxExpr(argument.element.isMarkedNullable, argument.collection, argument.envelope)
    val owner: ForwardExportOwnerTag =
      ownedBy(argument.owner, "flow type argument: ${argument.elementKotlinSpelling}")
    if (argument.kind == ForwardFlowArgumentKind.STATE_FLOW) {
      addFunction(
        handleKeyedStateFlowValueExport(
          stem, owner, flowType, boxed, nullable = argument.element.isMarkedNullable,
        ),
      )
    }
    addFunction(handleKeyedFlowCollectExport(stem, owner, flowType, boxed))
  }
}
