package io.github.xxfast.kotlin.native.nuget.processor.exports

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import io.github.xxfast.kotlin.native.nuget.processor.cir.FLOW_TYPES
import io.github.xxfast.kotlin.native.nuget.processor.cir.STATE_FLOW_TYPES
import io.github.xxfast.kotlin.native.nuget.processor.cir.expandAliases
import io.github.xxfast.kotlin.native.nuget.processor.toCName

/**
 * ADR-064 amendment (2026-09-13): this route's own gate, hoisted so the Kotlin half
 * ([addFunctionExports]), the C# half (`translateSpecializedFunction`) and the planner's
 * unrouted-position reclassification all read one function.
 *
 * The Flow/StateFlow refusal is the amendment's route fix (research H cell 4): `Flow<T>` is a
 * generic declaration, so it used to pass the type-parameter test below and both halves emitted —
 * the C# half rendering `public static Flow<int> F()` against a type that exists nowhere in
 * `Interop.cs` (the class route spells the same thing `KotlinFlow<int>`), i.e. a guaranteed
 * `CS0246` in the consumer. There is no top-level Flow route, so the member is refused here and
 * named by the planner instead.
 */
internal fun KSFunctionDeclaration.hasLegacyGenericReturnRoute(): Boolean {
  val returnType: KSType = returnType?.resolve()?.expandAliases() ?: return false
  val returnDecl: KSClassDeclaration = returnType.declaration as? KSClassDeclaration ?: return false
  val qualified: String? = returnDecl.qualifiedName?.asString()
  if (qualified in FLOW_TYPES || qualified in STATE_FLOW_TYPES) return false
  return returnDecl.typeParameters.isNotEmpty() && returnType.arguments.isNotEmpty()
}

/**
 * Named legacy adapter for top-level functions that remain outside the ordinary plan path:
 * generic-declaration returns. Ordinary synchronous top-level callables (including ADR-002
 * nullable-primitive two-call) are planned and emitted via [addForwardKotlinPlanExport].
 *
 * ADR-105 (issue #54): a *sealed* return is planned at every origin now, and this adapter runs
 * only for a function with no plan, so its sealed arm was dead. The export it used to build was
 * byte-identical to the planned one (`toCName(name)`, one `StableRef.create(...)`), so removing it
 * moves no ABI.
 *
 * @see <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/007-top-level-function-class-naming.md">ADR-007: Top-level function naming</a>
 * @see <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/010-generics-mapping.md">ADR-010: Generics mapping</a>
 */
internal fun FileSpec.Builder.addFunctionExports(func: KSFunctionDeclaration) {
  val cname: String = toCName(func.simpleName.asString())
  val funcName: String = func.simpleName.asString()

  if (!func.hasLegacyGenericReturnRoute()) {
    // Ordinary types without a plan are unsupported for emission — never fall through to
    // IntPtr / defaultValueFor("0") garbage (Phase 10 / MIGRATION invariants).
    return
  }

  // ADR-064: the import belongs to the route that actually emits. The caller no longer adds it
  // ahead of the plan gate, so this legacy generic-return route imports what it calls.
  addImport(func.packageName.asString(), funcName)

  val paramCall: String = func.parameters.joinToString(", ") { param ->
    val resolved: KSType = param.type.resolve().expandAliases()
    val name: String = param.name?.asString() ?: "_"
    val isEnum: Boolean = (resolved.declaration as? KSClassDeclaration)
      ?.classKind == com.google.devtools.ksp.symbol.ClassKind.ENUM_CLASS

    if (!isEnum) return@joinToString name

    val enumName: String = resolved.declaration.qualifiedName?.asString()
      ?: resolved.declaration.simpleName.asString()

    "($enumName.entries.getOrNull($name) ?: throw IllegalArgumentException(" +
        "\"Ordinal \" + $name + \" is out of bounds for enum $enumName\"))"
  }

  addFunction(
    FunSpec.builder("export_$cname")
      .addAnnotation(cNameAnnotation(cname))
      .addEnumAwareParameters(func)
      .addParameter("errorOut", cOpaquePointer.copy(nullable = true))
      .returns(cOpaquePointer.copy(nullable = true))
      .addCode(buildString {
        appendLine("return try {")
        appendLine("  %T.retain(%L(%L))")
        appendLine("} catch (e: Throwable) {")
        appendLine("  if (errorOut != null) {")
        appendLine("    errorOut.reinterpret<%T>().pointed.value = %T.retain(")
        appendLine("      buildError(e)")
        appendLine("    )")
        appendLine("  }")
        appendLine("  null")
        append("}")
      }, nugetHandles, funcName, paramCall, cOpaquePointerVar, nugetHandles)
      .build()
  )
}
