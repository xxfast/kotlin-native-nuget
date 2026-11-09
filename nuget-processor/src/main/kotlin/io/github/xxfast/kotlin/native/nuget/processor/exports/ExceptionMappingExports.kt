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
/**
 * ADR-202: the statement a generated export runs first when it hands C# a handle that a
 * runtime-owned route (`nuget_suspend_func{0..3}_invoke`, `nuget_stateflow_collect`) can later be
 * invoked on. Those routes live in the runtime klib, which cannot name this module's
 * [nugetMappedTypeFunction], so the module installs it into its own statically linked runtime
 * copy. Idempotent, synchronous on the calling thread, and always before C# holds the handle.
 *
 * The three mint sites: a class's suspend-lambda property getter, a suspend function returning
 * `StateFlow`, and a held `MutableStateFlow` acquire. They are the Kotlin halves of the only C#
 * sites that construct `KotlinSuspendFunc` / `KotlinSuspendAction` or call
 * `NugetStateFlowNative.Collect`.
 */
internal const val INSTALL_MODULE_MAPPED_TYPE: String = "nugetInstallMappedType(::nugetMappedType)"

/**
 * ADR-241: the forward classifier. This library's exported `Throwable` classes ([exported], FQNs
 * ordered deepest first so the first `is` that matches is the nearest exported ancestor) are
 * tested ahead of the rows; anything else falls through to [nugetRowTypeFunction]. The key the
 * C# `NugetErrorNative.MapExported` switches on is the FQN this same run emitted here.
 */
internal fun nugetMappedTypeFunction(exported: List<String>): FunSpec {
  val body: CodeBlock = if (exported.isEmpty()) {
    CodeBlock.of("return nugetRowType(t)\n")
  } else {
    val arms: CodeBlock.Builder = CodeBlock.builder()
      .add("return when {\n")
      .indent()
    exported.forEach { fqn -> arms.add("t is %L -> %S\n", fqn, fqn) }
    arms.add("else -> nugetRowType(t)\n")
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

/**
 * ADR-177/203: the row-only classifier, the body `nugetMappedType` had before ADR-241. The reverse
 * envelope classifies with it, because its C# half calls `CreateMapped` directly, which does not
 * know this library's exported keys.
 */
internal fun nugetRowTypeFunction(present: List<KotlinExceptionRow>): FunSpec {
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
  return FunSpec.builder("nugetRowType")
    .addModifiers(KModifier.INTERNAL)
    .addParameter("t", THROWABLE)
    .returns(STRING.copy(nullable = true))
    .addCode(body)
    .build()
}
