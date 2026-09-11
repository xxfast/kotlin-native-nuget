package io.github.xxfast.kotlin.native.nuget.processor.exports

import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.Visibility
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec

/**
 * Generates @CName bridge exports for generic classes using type erasure.
 * For Box<T>, generates box_get_value that returns Any, box_dispose, and helper unwrap functions.
 */
internal fun FileSpec.Builder.addGenericClassExports(cls: KSClassDeclaration) {
  val name: String = cls.simpleName.asString()
  val qualifiedName: String = cls.qualifiedName?.asString() ?: return
  val prefix: String = name.lowercase()

  val hasNonTrivialBound: Boolean = cls.typeParameters.firstOrNull()
    ?.bounds?.toList()?.any { bound ->
      val resolved = bound.resolve()
      resolved.declaration.qualifiedName?.asString() != "kotlin.Any"
    } ?: false

  if (!hasNonTrivialBound) {
    val primitiveVariants: List<Pair<String, ParameterSpec>> = listOf(
      "string" to ParameterSpec.builder("value", String::class).build(),
      "byte" to ParameterSpec.builder("value", Byte::class).build(),
      "ubyte" to ParameterSpec.builder("value", UByte::class).build(),
      "short" to ParameterSpec.builder("value", Short::class).build(),
      "ushort" to ParameterSpec.builder("value", UShort::class).build(),
      "int" to ParameterSpec.builder("value", Int::class).build(),
      "uint" to ParameterSpec.builder("value", UInt::class).build(),
      "long" to ParameterSpec.builder("value", Long::class).build(),
      "ulong" to ParameterSpec.builder("value", ULong::class).build(),
      "float" to ParameterSpec.builder("value", Float::class).build(),
      "double" to ParameterSpec.builder("value", Double::class).build(),
      "bool" to ParameterSpec.builder("value", Boolean::class).build(),
    )

    for ((suffix, param) in primitiveVariants) {
      addGenericCreateExport(prefix, suffix, param, qualifiedName, "value")
    }
  }

  val boundQualified: String? = cls.typeParameters.firstOrNull()
    ?.bounds?.toList()?.firstOrNull()?.let { bound ->
      val resolved = bound.resolve()
      val qn: String? = resolved.declaration.qualifiedName?.asString()
      if (qn != null && qn != "kotlin.Any") qn else null
    }

  val castExpr: String = if (boundQualified != null) {
    "value.asStableRef<$boundQualified>().get()"
  } else {
    "value.asStableRef<Any>().get()"
  }

  addGenericCreateExport(
    prefix,
    "object",
    ParameterSpec.builder("value", cOpaquePointer).build(),
    qualifiedName,
    castExpr,
  )

  addFunction(
    FunSpec.builder("export_${prefix}_dispose")
      .addAnnotation(cNameAnnotation("${prefix}_dispose"))
      .addParameter("handle", cOpaquePointer)
      .addStatement("%T.release(handle)", nugetHandles)
      .build()
  )

  val properties: List<KSPropertyDeclaration> = cls.getAllProperties()
    .filter { it.getVisibility() == Visibility.PUBLIC }
    .toList()

  for (prop in properties) {
    val propName: String = prop.simpleName.asString()

    addFunction(
      FunSpec.builder("export_${prefix}_get_$propName")
        .addAnnotation(cNameAnnotation("${prefix}_get_$propName"))
        .addParameter("handle", cOpaquePointer)
        // ADR-083: a null property value rides the null pointer out, closing the `.prop!!` NPE that
        // any nullable property on a generic class used to hit at its first read.
        .returns(cOpaquePointer.copy(nullable = true))
        .addStatement(
          "return handle.asStableRef<%L<*>>().get().%L?.let { %T.retain(it) }",
          qualifiedName, propName, nugetHandles,
        )
        .build()
    )
  }
}

/**
 * Emits a generic-class construction export with synchronous exception propagation
 * (ADR-032): a trailing nullable [errorOut] receives a StableRef<NugetError> when
 * the Kotlin constructor throws, and the export returns null instead of a handle.
 */
private fun FileSpec.Builder.addGenericCreateExport(
  prefix: String,
  suffix: String,
  valueParam: ParameterSpec,
  qualifiedName: String,
  argExpr: String,
) {
  addFunction(
    FunSpec.builder("export_${prefix}_create_$suffix")
      .addAnnotation(cNameAnnotation("${prefix}_create_$suffix"))
      .addParameter(valueParam)
      .addParameter("errorOut", cOpaquePointer.copy(nullable = true))
      .returns(cOpaquePointer.copy(nullable = true))
      .addCode(buildString {
        appendLine("return try {")
        appendLine("  %T.retain(%L($argExpr))")
        appendLine("} catch (e: Throwable) {")
        appendLine("  if (errorOut != null) {")
        appendLine("    errorOut.reinterpret<%T>().pointed.value = %T.retain(")
        appendLine("      buildError(e)")
        appendLine("    )")
        appendLine("  }")
        appendLine("  null")
        append("}")
      }, nugetHandles, qualifiedName, cOpaquePointerVar, nugetHandles)
      .build()
  )
}
