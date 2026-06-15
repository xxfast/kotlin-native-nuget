package io.github.xxfast.kotlin.native.nuget.processor.exports

import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import io.github.xxfast.kotlin.native.nuget.processor.toCName

/**
 * Generates @CName bridge exports for top-level properties.
 * Handles nullable types using the two-call pattern (ADR-002) for non-String nullables,
 * and direct get/set for primitives and String.
 *
 * @see <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/002-nullable-two-call-pattern.md">ADR-002: Nullable two-call pattern</a>
 */
internal fun FileSpec.Builder.addPropertyExports(prop: KSPropertyDeclaration) {
  val propName: String = prop.simpleName.asString()
  val cname: String = toCName(propName)
  val propTypeResolved: KSType = prop.type.resolve()
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

    addFunction(
      FunSpec.builder("export_get_$cname")
        .addAnnotation(cNameAnnotation("get_$cname"))
        .returns(Boolean::class)
        .addStatement("return %L != null", propName)
        .build()
    )

    addFunction(
      FunSpec.builder("export_get_${cname}_value")
        .addAnnotation(cNameAnnotation("get_${cname}_value"))
        .returns(nonNullType)
        .addStatement("return %L!!", propName)
        .build()
    )

    if (isMutable) {
      addFunction(
        FunSpec.builder("export_set_$cname")
          .addAnnotation(cNameAnnotation("set_$cname"))
          .addParameter("value", nonNullType)
          .addStatement("%L = value", propName)
          .build()
      )

      addFunction(
        FunSpec.builder("export_set_${cname}_null")
          .addAnnotation(cNameAnnotation("set_${cname}_null"))
          .addStatement("%L = null", propName)
          .build()
      )
    }

    return
  }

  val primitiveTypeName = ClassName.bestGuess(propType).copy(nullable = isNullable)

  addFunction(
    FunSpec.builder("export_get_$cname")
      .addAnnotation(cNameAnnotation("get_$cname"))
      .returns(primitiveTypeName)
      .addStatement("return %L", propName)
      .build()
  )

  if (isMutable) {
    addFunction(
      FunSpec.builder("export_set_$cname")
        .addAnnotation(cNameAnnotation("set_$cname"))
        .addParameter("value", primitiveTypeName)
        .addStatement("%L = value", propName)
        .build()
    )
  }
}
