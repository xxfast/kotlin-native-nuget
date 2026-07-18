package io.github.xxfast.kotlin.native.nuget.processor.exports

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Modifier
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import io.github.xxfast.kotlin.native.nuget.processor.cir.expandAliases
import io.github.xxfast.kotlin.native.nuget.processor.toCName

/**
 * Named legacy adapter for top-level functions that remain outside the ordinary plan path:
 * sealed and generic-declaration returns. Ordinary synchronous top-level callables (including
 * ADR-002 nullable-primitive two-call) are planned and emitted via [addForwardKotlinPlanExport].
 *
 * @see <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/007-top-level-function-class-naming.md">ADR-007: Top-level function naming</a>
 * @see <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/009-sealed-class-mapping.md">ADR-009: Sealed class mapping</a>
 * @see <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/010-generics-mapping.md">ADR-010: Generics mapping</a>
 */
internal fun FileSpec.Builder.addFunctionExports(func: KSFunctionDeclaration) {
  val cname: String = toCName(func.simpleName.asString())
  val funcName: String = func.simpleName.asString()
  val returnType: KSType? = func.returnType?.resolve()?.expandAliases()
  val returnDecl: KSClassDeclaration? = returnType?.declaration as? KSClassDeclaration
  val isSealedReturnType: Boolean = returnDecl?.modifiers?.contains(Modifier.SEALED) == true
  val isGenericReturnType: Boolean = returnDecl?.typeParameters?.isNotEmpty() == true &&
      returnType.arguments.isNotEmpty()

  if (!isGenericReturnType && !isSealedReturnType) {
    // Ordinary types without a plan are unsupported for emission — never fall through to
    // IntPtr / defaultValueFor("0") garbage (Phase 10 / MIGRATION invariants).
    return
  }

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
        appendLine("  %T.create(%L(%L)).asCPointer()")
        appendLine("} catch (e: Throwable) {")
        appendLine("  if (errorOut != null) {")
        appendLine("    errorOut.reinterpret<%T>().pointed.value = %T.create(")
        appendLine("      buildError(e)")
        appendLine("    ).asCPointer()")
        appendLine("  }")
        appendLine("  null")
        append("}")
      }, stableRef, funcName, paramCall, cOpaquePointerVar, stableRef)
      .build()
  )
}
