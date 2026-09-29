package io.github.xxfast.kotlin.native.nuget.processor.exports

import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.STRING
import com.squareup.kotlinpoet.THROWABLE
import io.github.xxfast.kotlin.native.nuget.processor.cir.KotlinExceptionMatch
import io.github.xxfast.kotlin.native.nuget.processor.cir.KotlinExceptionRow

/**
 * ADR-177: the per-module classifier every generated `buildError` call and every
 * `launchForCSharp`/`collectForCSharp` call passes. The optional rows ([present], the ones whose
 * class KSP resolved on this module's classpath) are tested first, then the runtime's stdlib rows.
 * Optional rows are disjoint from every stdlib row (`kotlinx.io.IOException : kotlin.Exception`),
 * so putting them first cannot shadow a more specific stdlib row. The class is spelled fully
 * qualified: no import that exists in only one cell.
 */
internal fun nugetMappedTypeFunction(present: List<KotlinExceptionRow>): FunSpec {
  val body: CodeBlock = if (present.isEmpty()) {
    CodeBlock.of("return nugetStdlibMappedType(t)\n")
  } else {
    CodeBlock.builder()
      .add("return when {\n")
      .indent()
      .apply {
        present.forEach { row ->
          when (row.match) {
            KotlinExceptionMatch.IS -> add("t is %L -> %S\n", row.kotlinType, row.kotlinType)
            KotlinExceptionMatch.NAME ->
              add("t::class.qualifiedName == %S -> %S\n", row.kotlinType, row.kotlinType)
          }
        }
      }
      .add("else -> nugetStdlibMappedType(t)\n")
      .unindent()
      .add("}\n")
      .build()
  }
  return FunSpec.builder("nugetMappedType")
    .addModifiers(KModifier.INTERNAL)
    .addParameter("t", THROWABLE)
    .returns(STRING.copy(nullable = true))
    .addCode(body)
    .build()
}
