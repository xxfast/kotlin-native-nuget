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
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardExportOwnerTag
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardLegacyParameterShape
import io.github.xxfast.kotlin.native.nuget.processor.forward.legacyLoweredName
import io.github.xxfast.kotlin.native.nuget.processor.forward.legacyLoweringStatement
import io.github.xxfast.kotlin.native.nuget.processor.forward.legacyParameterShapes
import io.github.xxfast.kotlin.native.nuget.processor.forward.legacyRefusedParameter
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

  val body: String =
    buildSuspendFunctionBody(funcName, paramCall, paramPrelude, isUnit, isNullable)

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

internal fun FileSpec.Builder.addSuspendClassMethodExports(
  cls: KSClassDeclaration,
  classifier: ForwardBridgeTypeClassifier,
) {
  val name: String = cls.simpleName.asString()
  val qualifiedName: String = cls.qualifiedName?.asString() ?: return
  val prefix: String = name.lowercase()

  val suspendMethods: List<KSFunctionDeclaration> = cls.getAllFunctions()
    .filter { it.getVisibility() == Visibility.PUBLIC }
    .filter { it.modifiers.contains(Modifier.SUSPEND) }
    .filter { it.simpleName.asString() !in listOf("equals", "hashCode", "toString", "<init>") }
    // ADR-114: a generic parameter this route cannot marshal skips the member named, rather than
    // emitting `ids: Set` and breaking the whole generated file's compile.
    .filter { method -> classifier.legacyRefusedParameter(method.parameters) == null }
    .toList()

  suspendMethods.forEach { method ->
    val methodName: String = method.simpleName.asString()
    val cname: String = toCName(methodName)
    val returnType = method.returnType?.resolve()?.expandAliases()
    val qualifiedReturn: String = returnType?.declaration?.qualifiedName?.asString() ?: "kotlin.Unit"
    val isUnit: Boolean = qualifiedReturn == "kotlin.Unit"
    // Issue #108: same nullable-return guard as the top-level builder.
    val isNullable: Boolean = returnType?.isMarkedNullable == true

    val paramShapes: List<ForwardLegacyParameterShape> =
      classifier.legacyParameterShapes(method.parameters)
    val paramCall: String = legacyParamCall(method, paramShapes)
    val paramPrelude: String = legacyParamPrelude(method, paramShapes)

    val body: String =
      buildSuspendMethodBody(qualifiedName, methodName, paramCall, paramPrelude, isUnit, isNullable)

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
): String = buildString {
  appendLine(
    "val fn = callbackPtr.reinterpret<CFunction<" +
        "(COpaquePointer?, COpaquePointer?, Byte, COpaquePointer) -> Unit>>()"
  )
  append(paramPrelude)
  val resultRefCode: String = resultRefExpression(isNullable)
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
  appendLine("    val errRef = StableRef.create(buildError(e)).asCPointer()")
  appendLine("    fn.invoke(null, errRef, 0.toByte(), userData)")
  appendLine("  }")
  appendLine("}")
  append("return StableRef.create(job).asCPointer()")
}

private fun buildSuspendMethodBody(
  qualifiedName: String,
  methodName: String,
  paramCall: String,
  paramPrelude: String,
  isUnit: Boolean,
  isNullable: Boolean,
): String = buildString {
  appendLine("val obj = handle.asStableRef<$qualifiedName>().get()")
  appendLine("val scope = scopeHandle.asStableRef<CoroutineScope>().get()")
  appendLine(
    "val fn = callbackPtr.reinterpret<CFunction<" +
        "(COpaquePointer?, COpaquePointer?, Byte, COpaquePointer) -> Unit>>()"
  )
  append(paramPrelude)
  val resultRefCode: String = resultRefExpression(isNullable)
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
  appendLine("    val errRef = StableRef.create(buildError(e)).asCPointer()")
  appendLine("    fn.invoke(null, errRef, 0.toByte(), userData)")
  appendLine("  }")
  appendLine("}")
  append("return StableRef.create(job).asCPointer()")
}

/**
 * Issue #108: `StableRef.create` takes `T : Any`, so a nullable suspend result has to be tested
 * before it is pinned. Null travels as a null result pointer, which is the same wire shape the
 * property route already uses, and the C# side reads it back as `null` rather than as a wrapper
 * over `IntPtr.Zero`.
 */
private fun resultRefExpression(isNullable: Boolean): String =
  if (isNullable) "if (result == null) null else StableRef.create(result).asCPointer()"
  else "StableRef.create(result).asCPointer()"

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
