package io.github.xxfast.kotlin.native.nuget.processor.exports

import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Visibility
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import io.github.xxfast.kotlin.native.nuget.processor.cir.LAMBDA_TYPES
import io.github.xxfast.kotlin.native.nuget.processor.cir.expandAliases
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardCallablePlanCatalog
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardPropertyPlan
import io.github.xxfast.kotlin.native.nuget.processor.forward.addForwardKotlinPlanExport
import io.github.xxfast.kotlin.native.nuget.processor.forward.addForwardPropertyPlanExports
import io.github.xxfast.kotlin.native.nuget.processor.forward.isOptInRefused
import io.github.xxfast.kotlin.native.nuget.processor.forward.handleBody
import io.github.xxfast.kotlin.native.nuget.processor.forward.nullableHandleBody

/**
 * Generates @CName bridge exports for sealed classes: type discriminator,
 * subclass property getters, and data class methods for data class subclasses.
 *
 * @see <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/009-sealed-class-mapping.md">ADR-009: Sealed class mapping</a>
 */
internal fun FileSpec.Builder.addSealedClassExports(
  sealed: KSClassDeclaration,
  callableCatalog: ForwardCallablePlanCatalog,
) {
  val name: String = sealed.simpleName.asString()
  val qualifiedName: String = sealed.qualifiedName?.asString() ?: return
  val prefix: String = name.lowercase()

  val subclasses: List<KSClassDeclaration> = sealed.getSealedSubclasses().toList()

  addFunction(
    FunSpec.builder("export_${prefix}_get_type")
      .addAnnotation(cNameAnnotation("${prefix}_get_type"))
      .addParameter("handle", cOpaquePointer)
      .returns(Int::class)
      .addStatement("val obj: %L = handle.asStableRef<%L>().get()", qualifiedName, qualifiedName)
      .addStatement("return when (obj) {")
      .apply {
        for ((index, subclass) in subclasses.withIndex()) {
          val subQualifiedName: String = subclass.qualifiedName?.asString() ?: continue
          addStatement("    is %L -> %L", subQualifiedName, index)
        }
      }
      .addStatement("}")
      .build()
  )

  for (subclass in subclasses) {
    val subName: String = subclass.simpleName.asString()
    val subQualifiedName: String = subclass.qualifiedName?.asString() ?: continue
    val subPrefix: String = "${prefix}_${subName.lowercase()}"
    val isDataClass: Boolean = subclass.modifiers.contains(Modifier.DATA)

    addFunction(
      FunSpec.builder("export_${subPrefix}_dispose")
        .addAnnotation(cNameAnnotation("${subPrefix}_dispose"))
        .addParameter("handle", cOpaquePointer)
        .addStatement("handle.asStableRef<%L>().dispose()", subQualifiedName)
        .build()
    )

    val properties: List<KSPropertyDeclaration> = subclass.getAllProperties()
      .filter { it.getVisibility() == Visibility.PUBLIC }
      .toList()

    for (prop in properties) {
      val propName: String = prop.simpleName.asString()
      // ADR-111: every ordinary property type is planned once and projected by the shared emitter,
      // so the getter's error slot, its nullable fan-out and its `bool` wire agree with the C#
      // half by construction rather than by a fourth hand-written copy.
      val planned: ForwardPropertyPlan? =
        callableCatalog.propertyFor("$subQualifiedName.$propName")
      if (planned != null) {
        addForwardPropertyPlanExports(planned)
        continue
      }

      // Issue #121: the planner declined, but a decline is not always an invitation. A marked
      // declaration must reach neither artifact, so the legacy arm below never runs for one.
      if (prop.isOptInRefused()) continue

      // Residual legacy route: a lambda-typed property, which has no plan shape yet (the C# half
      // still spells its own `KotlinFunc<...>` arm in `translateSealedClass`). Everything else the
      // planner declined is skipped, with a `SKIPPED_UNSUPPORTED_PROPERTY` diagnostic behind it.
      val propTypeResolved: KSType = prop.type.resolve().expandAliases()
      val qualifiedTypeName: String? = propTypeResolved.declaration.qualifiedName?.asString()
      if (qualifiedTypeName !in LAMBDA_TYPES) continue
      val access: String = "handle.asStableRef<$subQualifiedName>().get().$propName"
      val body: String = if (propTypeResolved.isMarkedNullable) {
        nullableHandleBody(access, "errorOut")
      } else {
        handleBody(access, "errorOut")
      }
      addFunction(
        sealedPropertyGetter(subPrefix, propName)
          .returns(cOpaquePointer.copy(nullable = true))
          .addCode(body, stableRef, cOpaquePointerVar, stableRef)
          .build()
      )
    }

    // ADR-116: the arm's declared member functions, off the same catalog and the same emitter an
    // ordinary class uses (`ClassExports`). Overload numbering lives in the planner, so the plans
    // are read from the catalog rather than re-derived per `getAllFunctions()` entry.
    callableCatalog.classMethods(subQualifiedName).forEach { plan ->
      addForwardKotlinPlanExport(plan)
    }

    if (isDataClass) {
      addFunction(
        FunSpec.builder("export_${subPrefix}_equals")
          .addAnnotation(cNameAnnotation("${subPrefix}_equals"))
          .addParameter("handle", cOpaquePointer)
          .addParameter("other", cOpaquePointer)
          .returns(Boolean::class)
          .addStatement(
            "return handle.asStableRef<%L>().get() == other.asStableRef<%L>().get()",
            subQualifiedName, subQualifiedName,
          )
          .build()
      )

      addFunction(
        FunSpec.builder("export_${subPrefix}_hashcode")
          .addAnnotation(cNameAnnotation("${subPrefix}_hashcode"))
          .addParameter("handle", cOpaquePointer)
          .returns(Int::class)
          .addStatement(
            "return handle.asStableRef<%L>().get().hashCode()",
            subQualifiedName,
          )
          .build()
      )

      addFunction(
        FunSpec.builder("export_${subPrefix}_tostring")
          .addAnnotation(cNameAnnotation("${subPrefix}_tostring"))
          .addParameter("handle", cOpaquePointer)
          .returns(String::class)
          .addStatement(
            "return handle.asStableRef<%L>().get().toString()",
            subQualifiedName,
          )
          .build()
      )
    }
  }
}

/**
 * Issue #38: every sealed-subclass property getter now carries the `errorOut` slot the top-level
 * property path carries (ADR-024/ADR-062), so a throwing getter reports through the same
 * convention instead of crossing the boundary as an unhandled Kotlin exception. [exportSuffix] is
 * the property name for a single-call getter, or the `_has_value` / `_value` suffixed name for the
 * nullable-primitive pair.
 */
private fun sealedPropertyGetter(subPrefix: String, exportSuffix: String): FunSpec.Builder =
  FunSpec.builder("export_${subPrefix}_get_$exportSuffix")
    .addAnnotation(cNameAnnotation("${subPrefix}_get_$exportSuffix"))
    .addParameter("handle", cOpaquePointer)
    .addParameter("errorOut", cOpaquePointer.copy(nullable = true))
