package io.github.xxfast.kotlin.native.nuget.processor.exports

import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import io.github.xxfast.kotlin.native.nuget.processor.cir.expandAliases
import io.github.xxfast.kotlin.native.nuget.processor.toCName
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardCallablePlanCatalog
import io.github.xxfast.kotlin.native.nuget.processor.forward.addForwardPropertyPlanExports

/**
 * Generates @CName bridge exports for top-level properties.
 * Handles nullable types using the two-call pattern (ADR-002) for non-String nullables,
 * and direct get/set for primitives and String.
 *
 * @see <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/002-nullable-two-call-pattern.md">ADR-002: Nullable two-call pattern</a>
 */
internal fun FileSpec.Builder.addPropertyExports(
  prop: KSPropertyDeclaration,
  callableCatalog: ForwardCallablePlanCatalog,
) {
  val propName: String = prop.simpleName.asString()
  val planned = callableCatalog.propertyFor("${prop.packageName.asString()}.$propName")
  if (planned != null) {
    addForwardPropertyPlanExports(planned)
    return
  }
  val cname: String = toCName(propName)
  val propTypeResolved: KSType = prop.type.resolve().expandAliases()
  val propType: String = propTypeResolved.declaration.qualifiedName?.asString() ?: "Any"
  val isNullable: Boolean = propTypeResolved.isMarkedNullable
  val isMutable: Boolean = prop.isMutable

  val isPrimitiveType: Boolean = propType in setOf(
    "kotlin.String", "kotlin.Byte", "kotlin.UByte", "kotlin.Short",
    "kotlin.UShort", "kotlin.Int", "kotlin.UInt", "kotlin.Long",
    "kotlin.ULong", "kotlin.Float", "kotlin.Double", "kotlin.Boolean",
    "kotlin.Unit",
  )

  if (!isPrimitiveType) return

  if (isPrimitiveType && isNullable && propType != "kotlin.String") {
    val nonNullType = ClassName.bestGuess(propType)
    val dummy: String = defaultValueFor(propType)

    addFunction(
      FunSpec.builder("export_get_$cname")
        .addAnnotation(cNameAnnotation("get_$cname"))
        .addParameter("errorOut", cOpaquePointer.copy(nullable = true))
        .returns(Boolean::class)
        .addCode(buildString {
          appendLine("return try {")
          appendLine("  %L != null")
          appendLine("} catch (e: Throwable) {")
          appendLine("  if (errorOut != null) {")
          appendLine("    errorOut.reinterpret<%T>().pointed.value = %T.create(")
          appendLine("      buildError(e)")
          appendLine("    ).asCPointer()")
          appendLine("  }")
          appendLine("  false")
          append("}")
        }, propName, cOpaquePointerVar, stableRef)
        .build()
    )

    addFunction(
      FunSpec.builder("export_get_${cname}_value")
        .addAnnotation(cNameAnnotation("get_${cname}_value"))
        .addParameter("errorOut", cOpaquePointer.copy(nullable = true))
        .returns(nonNullType)
        .addCode(buildString {
          appendLine("return try {")
          appendLine("  %L!!")
          appendLine("} catch (e: Throwable) {")
          appendLine("  if (errorOut != null) {")
          appendLine("    errorOut.reinterpret<%T>().pointed.value = %T.create(")
          appendLine("      buildError(e)")
          appendLine("    ).asCPointer()")
          appendLine("  }")
          appendLine("  $dummy")
          append("}")
        }, propName, cOpaquePointerVar, stableRef)
        .build()
    )

    if (isMutable) {
      addFunction(
        FunSpec.builder("export_set_$cname")
          .addAnnotation(cNameAnnotation("set_$cname"))
          .addParameter("value", nonNullType)
          .addParameter("errorOut", cOpaquePointer.copy(nullable = true))
          .addCode(buildString {
            appendLine("try {")
            appendLine("  %L = value")
            appendLine("} catch (e: Throwable) {")
            appendLine("  if (errorOut != null) {")
            appendLine("    errorOut.reinterpret<%T>().pointed.value = %T.create(")
            appendLine("      buildError(e)")
            appendLine("    ).asCPointer()")
            appendLine("  }")
            append("}")
          }, propName, cOpaquePointerVar, stableRef)
          .build()
      )

      addFunction(
        FunSpec.builder("export_set_${cname}_null")
          .addAnnotation(cNameAnnotation("set_${cname}_null"))
          .addParameter("errorOut", cOpaquePointer.copy(nullable = true))
          .addCode(buildString {
            appendLine("try {")
            appendLine("  %L = null")
            appendLine("} catch (e: Throwable) {")
            appendLine("  if (errorOut != null) {")
            appendLine("    errorOut.reinterpret<%T>().pointed.value = %T.create(")
            appendLine("      buildError(e)")
            appendLine("    ).asCPointer()")
            appendLine("  }")
            append("}")
          }, propName, cOpaquePointerVar, stableRef)
          .build()
      )
    }

    return
  }

  val primitiveTypeName = ClassName.bestGuess(propType).copy(nullable = isNullable)

  if (propType == "kotlin.Unit") {
    addFunction(
      FunSpec.builder("export_get_$cname")
        .addAnnotation(cNameAnnotation("get_$cname"))
        .addParameter("errorOut", cOpaquePointer.copy(nullable = true))
        .addCode(buildString {
          appendLine("try {")
          appendLine("  %L")
          appendLine("} catch (e: Throwable) {")
          appendLine("  if (errorOut != null) {")
          appendLine("    errorOut.reinterpret<%T>().pointed.value = %T.create(")
          appendLine("      buildError(e)")
          appendLine("    ).asCPointer()")
          appendLine("  }")
          append("}")
        }, propName, cOpaquePointerVar, stableRef)
        .build()
    )
  } else {
    addFunction(
      FunSpec.builder("export_get_$cname")
        .addAnnotation(cNameAnnotation("get_$cname"))
        .addParameter("errorOut", cOpaquePointer.copy(nullable = true))
        .returns(primitiveTypeName)
        .addCode(buildString {
          appendLine("return try {")
          appendLine("  %L")
          appendLine("} catch (e: Throwable) {")
          appendLine("  if (errorOut != null) {")
          appendLine("    errorOut.reinterpret<%T>().pointed.value = %T.create(")
          appendLine("      buildError(e)")
          appendLine("    ).asCPointer()")
          appendLine("  }")
          appendLine("  ${defaultValueFor(propType)}")
          append("}")
        }, propName, cOpaquePointerVar, stableRef)
        .build()
    )
  }

  if (isMutable) {
    addFunction(
      FunSpec.builder("export_set_$cname")
        .addAnnotation(cNameAnnotation("set_$cname"))
        .addParameter("value", primitiveTypeName)
        .addParameter("errorOut", cOpaquePointer.copy(nullable = true))
        .addCode(buildString {
          appendLine("try {")
          appendLine("  %L = value")
          appendLine("} catch (e: Throwable) {")
          appendLine("  if (errorOut != null) {")
          appendLine("    errorOut.reinterpret<%T>().pointed.value = %T.create(")
          appendLine("      buildError(e)")
          appendLine("    ).asCPointer()")
          appendLine("  }")
          append("}")
        }, propName, cOpaquePointerVar, stableRef)
        .build()
    )
  }
}
