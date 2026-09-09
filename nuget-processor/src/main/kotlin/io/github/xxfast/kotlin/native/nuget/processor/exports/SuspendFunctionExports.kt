package io.github.xxfast.kotlin.native.nuget.processor.exports

import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Visibility
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import io.github.xxfast.kotlin.native.nuget.processor.cir.expandAliases
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardBridgeTypeClassifier
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardCallablePlanCatalog
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardExportOwnerTag
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardLegacyParameterShape
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardLegacyReturnShape
import io.github.xxfast.kotlin.native.nuget.processor.forward.collectionResultProjection
import io.github.xxfast.kotlin.native.nuget.processor.forward.legacyLoweredName
import io.github.xxfast.kotlin.native.nuget.processor.forward.legacyLoweringStatement
import io.github.xxfast.kotlin.native.nuget.processor.forward.legacyParameterShapes
import io.github.xxfast.kotlin.native.nuget.processor.forward.legacyRefusedParameter
import io.github.xxfast.kotlin.native.nuget.processor.forward.legacyRefusedReturn
import io.github.xxfast.kotlin.native.nuget.processor.forward.legacyReturnShape
import io.github.xxfast.kotlin.native.nuget.processor.toCName

/**
 * Generates @CName bridge exports for suspend functions using a callback-based async pattern.
 *
 * Each suspend function gets a non-suspend wrapper that:
 * 1. Accepts the original parameters plus a callback pointer and userData pointer
 * 2. Launches a coroutine on Dispatchers.Default
 * 3. Calls the original suspend function
 * 4. Invokes the callback with (resultPtr, null, userData) on success
 *    or (null, errorPtr, userData) on error
 *
 * @see <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/019-suspend-function-mapping.md">ADR-019: Suspend function mapping</a>
 */
internal fun FileSpec.Builder.addSuspendFunctionExports(
  func: KSFunctionDeclaration,
  // ADR-114: same classification the class-method route below uses. This route compiles today
  // (`addParameters` keeps the type arguments), but it hands C# an IntPtr no caller can produce.
  classifier: ForwardBridgeTypeClassifier,
) {
  if (classifier.legacyRefusedParameter(func.parameters) != null) return
  // ADR-119: a generic return that is not a marshallable collection skips on both halves too.
  if (classifier.legacyRefusedReturn(func) != null) return
  val cname: String = toCName(func.simpleName.asString())
  val funcName: String = func.simpleName.asString()
  val returnType = func.returnType?.resolve()?.expandAliases()
  val qualifiedReturn: String = returnType?.declaration?.qualifiedName?.asString() ?: "kotlin.Unit"
  val isUnit: Boolean = qualifiedReturn == "kotlin.Unit"
  // Issue #108: a nullable return cannot go straight into `StableRef.create`, whose T is `Any`.
  val isNullable: Boolean = returnType?.isMarkedNullable == true

  val paramShapes: List<ForwardLegacyParameterShape> =
    classifier.legacyParameterShapes(func.parameters)
  val paramCall: String = legacyParamCall(func, paramShapes)
  val paramPrelude: String = legacyParamPrelude(func, paramShapes)
  val boxed: String = legacyBoxedResult(classifier.legacyReturnShape(returnType))

  val body: String =
    buildSuspendFunctionBody(funcName, paramCall, paramPrelude, isUnit, isNullable, boxed)

  val builder: FunSpec.Builder = FunSpec.builder("export_${cname}_async")
    .addAnnotation(cNameAnnotation("${cname}_async"))
    // ADR-117: the one live legacy route that can collide *within* a class (two suspend
    // overloads share `${cname}_async`), so it names the method, not the class.
    .tag(ForwardExportOwnerTag::class, ForwardExportOwnerTag(declaration = func))
    .addLegacySuspendParameters(func, paramShapes)
    .addParameter("callbackPtr", cOpaquePointer)
    .addParameter("userData", cOpaquePointer)
    .returns(cOpaquePointer)
    .addCode(body)

  addFunction(builder.build())
}

/**
 * ADR-118: the same builder now serves an ordinary class and a sealed subclass. A sealed arm passes
 * its own export [prefix] (`job_running`) and [declaredOnly], because the sealed route -- planner,
 * C# translator and this builder alike -- binds exactly what the arm declares itself.
 */
internal fun FileSpec.Builder.addSuspendClassMethodExports(
  cls: KSClassDeclaration,
  classifier: ForwardBridgeTypeClassifier,
  callableCatalog: ForwardCallablePlanCatalog,
  prefix: String = cls.simpleName.asString().lowercase(),
  declaredOnly: Boolean = false,
) {
  val qualifiedName: String = cls.qualifiedName?.asString() ?: return

  val suspendMethods: List<KSFunctionDeclaration> = cls.getAllFunctions()
    .filter { it.getVisibility() == Visibility.PUBLIC }
    .filter { it.modifiers.contains(Modifier.SUSPEND) }
    .filter { it.simpleName.asString() !in listOf("equals", "hashCode", "toString", "<init>") }
    // ADR-118: declared-only for a sealed arm. Without it a base `open suspend fun` no arm
    // overrides exports once per arm under the arm's prefix, with `overloadSuffix` answering ""
    // on its lenient path; `ForwardAbiContract.kotlin` filters Kotlin exports down to the C#
    // import set, so that stray export would never be flagged.
    .filter { !declaredOnly || it.parentDeclaration == cls }
    // ADR-114: a generic parameter this route cannot marshal skips the member named, rather than
    // emitting `ids: Set` and breaking the whole generated file's compile.
    .filter { method -> classifier.legacyRefusedParameter(method.parameters) == null }
    // ADR-119: same for a generic return that is not a marshallable collection.
    .filter { method -> classifier.legacyRefusedReturn(method) == null }
    .toList()

  suspendMethods.forEach { method ->
    val methodName: String = method.simpleName.asString()
    // ADR-118: the planner's overload number, so two `suspend` overloads take two C symbols. The
    // Kotlin call site below stays bare -- the suffix names the export, not the method.
    val cname: String = toCName(methodName) + callableCatalog.overloadSuffix(method)
    val returnType = method.returnType?.resolve()?.expandAliases()
    val qualifiedReturn: String = returnType?.declaration?.qualifiedName?.asString() ?: "kotlin.Unit"
    val isUnit: Boolean = qualifiedReturn == "kotlin.Unit"
    // Issue #108: same nullable-return guard as the top-level builder.
    val isNullable: Boolean = returnType?.isMarkedNullable == true

    val paramShapes: List<ForwardLegacyParameterShape> =
      classifier.legacyParameterShapes(method.parameters)
    val paramCall: String = legacyParamCall(method, paramShapes)
    val paramPrelude: String = legacyParamPrelude(method, paramShapes)
    val boxed: String = legacyBoxedResult(classifier.legacyReturnShape(returnType))

    val body: String = buildSuspendMethodBody(
      qualifiedName, methodName, paramCall, paramPrelude, isUnit, isNullable, boxed,
    )

    val builder: FunSpec.Builder = FunSpec.builder("export_${prefix}_${cname}_async")
      .addAnnotation(cNameAnnotation("${prefix}_${cname}_async"))
      .tag(ForwardExportOwnerTag::class, ForwardExportOwnerTag(declaration = method))
      .addParameter("handle", cOpaquePointer)
      .addParameter("scopeHandle", cOpaquePointer)

    method.parameters.forEachIndexed { index, param ->
      val paramName: String = param.name?.asString() ?: "_"
      // ADR-114: a collection crosses as a handle to its boxed wire container.
      if (paramShapes[index] is ForwardLegacyParameterShape.Marshalled) {
        builder.addParameter(paramName, cOpaquePointer)
        return@forEachIndexed
      }
      val resolved: KSType = param.type.resolve().expandAliases()
      val type: String = resolved.declaration.qualifiedName?.asString()
        ?: resolved.declaration.simpleName.asString()
      builder.addParameter(paramName, ClassName.bestGuess(type))
    }

    builder
      .addParameter("callbackPtr", cOpaquePointer)
      .addParameter("userData", cOpaquePointer)
      .returns(cOpaquePointer)
      .addCode(body)

    addFunction(builder.build())
  }
}

private fun buildSuspendFunctionBody(
  funcName: String,
  paramCall: String,
  paramPrelude: String,
  isUnit: Boolean,
  isNullable: Boolean,
  boxed: String,
): String = buildString {
  appendLine(
    "val fn = callbackPtr.reinterpret<CFunction<" +
        "(COpaquePointer?, COpaquePointer?, Byte, COpaquePointer) -> Unit>>()"
  )
  append(paramPrelude)
  val resultRefCode: String = resultRefExpression(isNullable, boxed)
  appendLine("val job = CoroutineScope(Dispatchers.Default).launch(start = CoroutineStart.ATOMIC) {")
  appendLine("  try {")
  if (isUnit) {
    appendLine("    $funcName($paramCall)")
    appendLine("    fn.invoke(null, null, 0.toByte(), userData)")
  } else {
    appendLine("    val result = $funcName($paramCall)")
    appendLine("    val resultRef = $resultRefCode")
    appendLine("    fn.invoke(resultRef, null, 0.toByte(), userData)")
  }
  appendLine("  } catch (e: CancellationException) {")
  appendLine("    fn.invoke(null, null, 1.toByte(), userData)")
  appendLine("    throw e")
  appendLine("  } catch (e: Throwable) {")
  appendLine("    val errRef = NugetHandles.retain(buildError(e))")
  appendLine("    fn.invoke(null, errRef, 0.toByte(), userData)")
  appendLine("  }")
  appendLine("}")
  append("return NugetHandles.retain(job)")
}

private fun buildSuspendMethodBody(
  qualifiedName: String,
  methodName: String,
  paramCall: String,
  paramPrelude: String,
  isUnit: Boolean,
  isNullable: Boolean,
  boxed: String,
): String = buildString {
  appendLine("val obj = handle.asStableRef<$qualifiedName>().get()")
  appendLine("val scope = scopeHandle.asStableRef<CoroutineScope>().get()")
  appendLine(
    "val fn = callbackPtr.reinterpret<CFunction<" +
        "(COpaquePointer?, COpaquePointer?, Byte, COpaquePointer) -> Unit>>()"
  )
  append(paramPrelude)
  val resultRefCode: String = resultRefExpression(isNullable, boxed)
  appendLine("val job = scope.launch(start = CoroutineStart.ATOMIC) {")
  appendLine("  try {")
  if (isUnit) {
    appendLine("    obj.$methodName($paramCall)")
    appendLine("    fn.invoke(null, null, 0.toByte(), userData)")
  } else {
    appendLine("    val result = obj.$methodName($paramCall)")
    appendLine("    val resultRef = $resultRefCode")
    appendLine("    fn.invoke(resultRef, null, 0.toByte(), userData)")
  }
  appendLine("  } catch (e: CancellationException) {")
  appendLine("    fn.invoke(null, null, 1.toByte(), userData)")
  appendLine("    throw e")
  appendLine("  } catch (e: Throwable) {")
  appendLine("    val errRef = NugetHandles.retain(buildError(e))")
  appendLine("    fn.invoke(null, errRef, 0.toByte(), userData)")
  appendLine("  }")
  appendLine("}")
  append("return NugetHandles.retain(job)")
}

/**
 * Issue #108: `StableRef.create` takes `T : Any`, so a nullable suspend result has to be tested
 * before it is pinned. Null travels as a null result pointer, which is the same wire shape the
 * property route already uses, and the C# side reads it back as `null` rather than as a wrapper
 * over `IntPtr.Zero`.
 */
private fun resultRefExpression(isNullable: Boolean, boxed: String): String =
  if (isNullable) "if (result == null) null else NugetHandles.retain($boxed)"
  else "NugetHandles.retain($boxed)"

/**
 * ADR-119: what the suspend export pins for the C# side to read. A collection result is projected
 * per element exactly as the ordinary route's `List` return is (`collectionResultProjection`: a
 * value class or enum component leaves as its wire value, everything else is boxed as-is), so the
 * `nuget_list_get` / `nuget_set_element_at` / `nuget_map_*_at` helpers see the same container
 * shape on both routes. Any other result stays the bare `result` the shipped route pins.
 */
private fun legacyBoxedResult(shape: ForwardLegacyReturnShape): String =
  if (shape is ForwardLegacyReturnShape.Marshalled) collectionResultProjection("result", shape.type)
  else "result"

/**
 * ADR-114: the argument list the suspend member is called with. A marshalled collection is read
 * from its eagerly-copied local, everything else keeps its parameter name.
 */
private fun legacyParamCall(
  func: KSFunctionDeclaration,
  shapes: List<ForwardLegacyParameterShape>,
): String = func.parameters
  .mapIndexed { index, param ->
    val name: String = param.name?.asString() ?: "_"
    if (shapes[index] is ForwardLegacyParameterShape.Marshalled) legacyLoweredName(name) else name
  }
  .joinToString(", ")

/**
 * ADR-114: the eager copy, emitted before `launch`. The C# side disposes the wire handle the
 * moment this export returns, so the coroutine must never dereference it.
 */
private fun legacyParamPrelude(
  func: KSFunctionDeclaration,
  shapes: List<ForwardLegacyParameterShape>,
): String = buildString {
  func.parameters.forEachIndexed { index, param ->
    val shape: ForwardLegacyParameterShape = shapes[index]
    if (shape is ForwardLegacyParameterShape.Marshalled) {
      appendLine(legacyLoweringStatement(param.name?.asString() ?: "_", shape.type))
    }
  }
}

/**
 * [addParameters] with ADR-114's collection arm: the top-level suspend route spelled a collection
 * with its real Kotlin type (`toBridgeTypeName` keeps the type arguments), which compiles and then
 * hands C# an `IntPtr` no caller can produce. A handle is the shape both halves agree on.
 */
private fun FunSpec.Builder.addLegacySuspendParameters(
  func: KSFunctionDeclaration,
  shapes: List<ForwardLegacyParameterShape>,
): FunSpec.Builder {
  func.parameters.forEachIndexed { index, param ->
    val name: String = param.name?.asString() ?: "_"
    if (shapes[index] is ForwardLegacyParameterShape.Marshalled) {
      addParameter(name, cOpaquePointer)
      return@forEachIndexed
    }
    addParameter(name, param.type.resolve().expandAliases().toBridgeTypeName(nullable = false))
  }
  return this
}
