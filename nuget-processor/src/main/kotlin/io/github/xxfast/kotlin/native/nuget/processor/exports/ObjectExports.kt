package io.github.xxfast.kotlin.native.nuget.processor.exports

import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.Visibility
import com.squareup.kotlinpoet.FileSpec
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardCallablePlan
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardCallablePlanCatalog
import io.github.xxfast.kotlin.native.nuget.processor.forward.addForwardKotlinPlanExport
import io.github.xxfast.kotlin.native.nuget.processor.forward.planFor

/**
 * Generates @CName bridge exports for Kotlin object singletons via the forward plan catalog.
 * Ordinary object methods without a plan are skipped (no pointer/numeric fallthrough).
 */
internal fun FileSpec.Builder.addObjectExports(
  obj: KSClassDeclaration,
  callableCatalog: ForwardCallablePlanCatalog,
) {
  val qualifiedName: String = obj.qualifiedName?.asString() ?: return

  obj.getAllFunctions()
    .filter { it.getVisibility() == Visibility.PUBLIC }
    .filter { it.simpleName.asString() !in listOf("equals", "hashCode", "toString", "<init>") }
    .forEach { method ->
      val planned: ForwardCallablePlan? =
        callableCatalog.planFor("$qualifiedName.${method.simpleName.asString()}")
      if (planned != null) addForwardKotlinPlanExport(planned)
    }
}
