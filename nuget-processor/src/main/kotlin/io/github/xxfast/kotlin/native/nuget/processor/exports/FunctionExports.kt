package io.github.xxfast.kotlin.native.nuget.processor.exports

import io.github.xxfast.kotlin.native.nuget.processor.ForwardSymbolTable
import io.github.xxfast.kotlin.native.nuget.processor.forward.importIfDefaultPackage
import io.github.xxfast.kotlin.native.nuget.processor.forward.kotlinPackageReference
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import io.github.xxfast.kotlin.native.nuget.processor.cir.FLOW_TYPES
import io.github.xxfast.kotlin.native.nuget.processor.cir.KOTLIN_TO_CSHARP_PARAM
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
  // ROADMAP Phase 4 (2026-09-20), the general form of the ADR-064 amendment above: a *collection*
  // return belongs to the ADR-062 plan route and to nothing else. It is a generic declaration with
  // arguments, so it passed the test below whenever the plan SKIPPED it -- and `translateFunction`
  // then rendered the pre-ADR-062 `isListReturnType`/`isMapReturnType`/`isSetReturnType` branches,
  // which spell a component by its Kotlin SIMPLE NAME and drop its own type arguments. Measured
  // 2026-09-20: `fun f(): List<ByteArray>` emitted `IReadOnlyList<ByteArray>` reading
  // `FromHandle<ByteArray>`, and `fun g(): List<List<ByteArray>>` emitted `IReadOnlyList<List>` --
  // both CS0246 in the consumer, both carrying the `SKIPPED_UNSUPPORTED_TYPE` warning that says
  // they were dropped. Not ByteArray-specific: any component the plan refuses (`List<Instant>`,
  // `List<Uuid>`, `List<Sequence<Int>>`) took the same fall-through. Skip means absent.
  if (qualified in LEGACY_UNROUTED_COLLECTIONS) return false
  // The same hole one position over: this route's C# half spells every parameter through
  // `mapParamType`, whose fall-through is a public `IntPtr` (issue #126's class, which ADR-122
  // fixed on the async routes only). A plan skip caused by a PARAMETER left the return route open,
  // so `fun f(p: List<ByteArray?>): List<Int>` rendered `IReadOnlyList<int> MissingSignals(IntPtr
  // signals)`: a member no consumer can call, for a declaration the build said it dropped.
  if (!parameters.all { it.type.resolve().expandAliases().isLegacyGenericRouteParameter() }) {
    return false
  }
  return returnDecl.typeParameters.isNotEmpty() && returnType.arguments.isNotEmpty()
}

/**
 * The collection declarations the ADR-062 plan route owns outright. Spelled as qualified names
 * rather than reached through `BridgeType` because this predicate runs on both halves before any
 * classifier is in scope.
 */
private val LEGACY_UNROUTED_COLLECTIONS: Set<String> = setOf(
  "kotlin.collections.List", "kotlin.collections.MutableList",
  "kotlin.collections.Map", "kotlin.collections.MutableMap",
  "kotlin.collections.Set", "kotlin.collections.MutableSet",
)

/**
 * Whether `translateFunction` can actually spell this parameter: one of the 13 `mapParamType`
 * entries, or an enum (which it renders as the public enum over an `int` native slot). Everything
 * else is `mapParamType`'s `IntPtr` fall-through.
 */
private fun KSType.isLegacyGenericRouteParameter(): Boolean {
  if (declaration.simpleName.asString() in KOTLIN_TO_CSHARP_PARAM) return true
  return (declaration as? KSClassDeclaration)?.classKind == ClassKind.ENUM_CLASS
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
internal fun FileSpec.Builder.addFunctionExports(
  func: KSFunctionDeclaration,
  /** ADR-163: the one symbol table, the same string `translateFunction` pins as its entryPoint. */
  symbols: ForwardSymbolTable,
) {
  val cname: String = symbols.topLevel(func)
  // ADR-163: the call is fully qualified, so two same-named top-level functions in two packages
  // do not import to one ambiguous simple name in the generated file.
  val funcName: String =
    kotlinPackageReference(func.packageName.asString()) + func.simpleName.asString()

  if (!func.hasLegacyGenericReturnRoute()) {
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

  // ADR-163: the default package has no qualifier to spell, so the bare call needs this import.
  importIfDefaultPackage(func)
  addFunction(
    FunSpec.builder("export_$cname")
      .addAnnotation(cNameAnnotation(cname, ownedBy(func)))
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
