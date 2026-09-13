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
import io.github.xxfast.kotlin.native.nuget.processor.cir.KOTLIN_TO_CSHARP_PARAM
import io.github.xxfast.kotlin.native.nuget.processor.cir.LAMBDA_TYPES
import io.github.xxfast.kotlin.native.nuget.processor.cir.expandAliases
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardBridgeTypeClassifier
import io.github.xxfast.kotlin.native.nuget.processor.forward.legacyRefusedReturn
import io.github.xxfast.kotlin.native.nuget.processor.forward.optInMarker

/**
 * Generates @CName bridge exports for class methods that accept lambda parameters (reverse interop).
 * The Kotlin export receives COpaquePointer function pointers from C# and calls them.
 *
 * @see <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/036-reverse-interop-mechanism.md">ADR-036: Reverse interop mechanism</a>
 */
internal fun FileSpec.Builder.addLambdaParamMethodExport(
  method: KSFunctionDeclaration,
  qualifiedClassName: String,
  classPrefix: String,
) {
  val methodName: String = method.simpleName.asString()

  val lambdaParam = method.parameters.firstOrNull { param ->
    param.type.resolve().expandAliases().declaration.qualifiedName?.asString() in LAMBDA_TYPES
  } ?: return

  val lambdaParamName: String = lambdaParam.name?.asString() ?: "callback"
  val lambdaType: KSType = lambdaParam.type.resolve().expandAliases()
  val lambdaArgTypes: List<KSType> = lambdaType.arguments.dropLast(1)
    .mapNotNull { it.type?.resolve()?.expandAliases() }
  val lambdaRetType: KSType? = lambdaType.arguments.lastOrNull()?.type?.resolve()?.expandAliases()
  val lambdaRetKotlin: String = lambdaRetType?.declaration?.simpleName?.asString() ?: "Unit"
  val lambdaArity: Int = lambdaArgTypes.size

  val outerRetType: KSType? = method.returnType?.resolve()?.expandAliases()
  val outerRetQualified: String = outerRetType?.declaration?.qualifiedName?.asString() ?: "kotlin.Unit"
  val isOuterRetUnit: Boolean = outerRetQualified == "kotlin.Unit"
  val isOuterRetString: Boolean = outerRetQualified == "kotlin.String"
  val isOuterRetList: Boolean = outerRetQualified in setOf(
    "kotlin.collections.List", "kotlin.collections.MutableList",
  )

  // ADR-036's marshalling table: a primitive payload crosses BY VALUE, in both directions. The
  // interface-bridge route has always done this; the per-call route boxed every payload into a
  // StableRef instead, which cost a handle per invocation and rendered a C# thunk that could not
  // compile. `String` stays on the handle path (it is not a C ABI scalar) and so does `Char`,
  // which has no crossing convention on any route yet.
  val byValueArgs: List<Boolean> = lambdaArgTypes.map { argType ->
    val simple: String = argType.declaration.simpleName.asString()
    val qualified: String = argType.declaration.qualifiedName?.asString() ?: ""
    qualified.startsWith("kotlin.") && simple in KOTLIN_TO_CSHARP_PARAM &&
        simple != "String" && simple != "Char"
  }

  // CFunction signature for the Kotlin side
  // (arg0: Int, ..., userData: COpaquePointer) -> ReturnType
  val cfuncArgTypes: String = buildString {
    lambdaArgTypes.forEachIndexed { i, argType ->
      val argKotlin: String = argType.declaration.simpleName.asString()
      when {
        // `Boolean` is the one by-value payload that is not its own wire type: it crosses as the
        // `Byte` the C# side widens back to `bool`.
        argKotlin == "Boolean" -> append("Byte, ")
        byValueArgs[i] -> append("$argKotlin, ")
        else -> append("COpaquePointer?, ")
      }
    }
    append("COpaquePointer")  // userData
  }

  val cfuncReturnType: String = when {
    lambdaRetKotlin == "Unit" -> "Unit"
    lambdaRetKotlin == "Boolean" -> "Byte"
    else -> "COpaquePointer?"
  }

  val cfuncSignature: String = "($cfuncArgTypes) -> $cfuncReturnType"

  // Shared helper: body of the Kotlin wrapper lambda that calls the CFunction.
  // Returns a snippet that produces the required Kotlin type.
  fun buildCallbackWrapperBody(indent: String): String = buildString {
    val fnVar = "${lambdaParamName}Fn"

    // Marshal each lambda arg from Kotlin to its wire form. A by-value primitive needs no
    // binding at all: it is already the wire type.
    lambdaArgTypes.forEachIndexed { i, argType ->
      val argKotlin: String = argType.declaration.simpleName.asString()
      when {
        argKotlin == "Boolean" ->
          appendLine("${indent}val arg${i}Ref: Byte = if (it$i) 1.toByte() else 0.toByte()")
        byValueArgs[i] -> Unit
        argKotlin == "String" ->
          appendLine("${indent}val arg${i}Ref = NugetHandles.retain(it$i as Any)")
        else -> appendLine("${indent}val arg${i}Ref = NugetHandles.retain(it$i)")
      }
    }

    val fnCallArgs: String = buildString {
      lambdaArgTypes.indices.forEach { i ->
        val argKotlin: String = lambdaArgTypes[i].declaration.simpleName.asString()
        if (byValueArgs[i] && argKotlin != "Boolean") append("it$i, ") else append("arg${i}Ref, ")
      }
      append("${lambdaParamName}UserData")
    }

    // ADR-036 amendment (2026-09-11): a handle-passed payload is the C# side's to free, so
    // nothing is released here. `NugetMarshal.FromHandle<string>` disposes the handle as it reads
    // it, and an exported object goes through `Materialize<T>`, which hands the raw handle to the
    // wrapper's constructor: the wrapper's `Dispose()` is the free. Releasing here as well took
    // the count one *below* baseline per crossing (LeakTests rows 8g/8h/8i) and freed a handle a
    // live wrapper was still holding. The callback's *return* box is the other way round: no C#
    // owner ever frees it, so its release below stays.
    when {
      lambdaRetKotlin == "Unit" -> appendLine("${indent}$fnVar.invoke($fnCallArgs)")
      lambdaRetKotlin == "Boolean" -> {
        appendLine("${indent}val cbResult = $fnVar.invoke($fnCallArgs) != 0.toByte()")
        append("${indent}cbResult")
      }
      else -> {
        // String or object return from C# callback — backed by nuget_wrap_string StableRef
        appendLine("${indent}val resultRef = $fnVar.invoke($fnCallArgs)!!")
        appendLine("${indent}val cbResult = resultRef.asStableRef<String>().get()")
        appendLine("${indent}NugetHandles.release(resultRef)")
        append("${indent}cbResult")
      }
    }
  }

  val lambdaArgDecl: String = if (lambdaArity == 0) "" else {
    val args = lambdaArgTypes.indices.joinToString(", ") { "it$it" }
    " $args ->"
  }

  val fnVar = "${lambdaParamName}Fn"

  val exportedBody: String = buildString {
    appendLine("val $fnVar = ${lambdaParamName}Ptr.reinterpret<CFunction<$cfuncSignature>>()")
    val callbackBody: String = buildCallbackWrapperBody("    ")

    when {
      isOuterRetUnit -> {
        appendLine("try {")
        appendLine("  handle.asStableRef<$qualifiedClassName>().get().$methodName {$lambdaArgDecl")
        append(callbackBody)
        appendLine()
        appendLine("  }")
        appendLine("} catch (e: Throwable) {")
        appendLine("  if (errorOut != null) {")
        appendLine(
          "    errorOut.reinterpret<COpaquePointerVar>().pointed.value = NugetHandles.retain(",
        )
        appendLine("      buildError(e)")
        appendLine("    )")
        appendLine("  }")
        append("}")
      }
      isOuterRetList -> {
        appendLine("return try {")
        appendLine("  val list = handle.asStableRef<$qualifiedClassName>().get().$methodName {$lambdaArgDecl")
        append(callbackBody)
        appendLine()
        appendLine("  }")
        appendLine("  NugetHandles.retain(list)")
        appendLine("} catch (e: Throwable) {")
        appendLine("  if (errorOut != null) {")
        appendLine(
          "    errorOut.reinterpret<COpaquePointerVar>().pointed.value = NugetHandles.retain(",
        )
        appendLine("      buildError(e)")
        appendLine("    )")
        appendLine("  }")
        appendLine("  null")
        append("}")
      }
      else -> {
        // String or other object return
        appendLine("return try {")
        appendLine("  handle.asStableRef<$qualifiedClassName>().get().$methodName {$lambdaArgDecl")
        append(callbackBody)
        appendLine()
        appendLine("  }")
        appendLine("} catch (e: Throwable) {")
        appendLine("  if (errorOut != null) {")
        appendLine(
          "    errorOut.reinterpret<COpaquePointerVar>().pointed.value = NugetHandles.retain(",
        )
        appendLine("      buildError(e)")
        appendLine("    )")
        appendLine("  }")
        appendLine("  ${defaultValueFor(outerRetQualified)}")
        append("}")
      }
    }
  }

  val builder: FunSpec.Builder = FunSpec
    .builder("export_${classPrefix}_$methodName")
    .addAnnotation(cNameAnnotation("${classPrefix}_$methodName", ownedBy(method)))
    .addParameter("handle", cOpaquePointer)
    .addParameter("${lambdaParamName}Ptr", cOpaquePointer)
    .addParameter("${lambdaParamName}UserData", cOpaquePointer)
    .addParameter("errorOut", cOpaquePointer.copy(nullable = true))

  when {
    isOuterRetUnit -> Unit  // no .returns() → implicit Unit, block body
    isOuterRetList -> builder.returns(cOpaquePointer.copy(nullable = true))
    isOuterRetString -> builder.returns(String::class)
    else -> builder.returns(ClassName.bestGuess(outerRetQualified))
  }

  builder.addCode(exportedBody)

  addFunction(builder.build())
}

/**
 * ADR-116 amendment (2026-09-11): a sealed arm's **per-call** lambda-parameter methods, the third
 * legacy route re-keyed onto the arm after ADR-118's suspend and ADR-124's flow. One selector for
 * the three halves that must agree on the member set (the Kotlin export loop, the C# translator
 * and the import gate), shaped after `forwardArmFlowMethods`.
 *
 * Declared-only (`parentDeclaration == this`), which is ADR-116's rule for the arm's method
 * surface: a base method the arm does not override belongs to no arm.
 *
 * An add/remove **pair** is excluded here and stays a named `SEALED_SUBCLASS_UNROUTED` drop: the
 * stored-callback route (ADR-037) is not re-keyed by this change, and emitting the add half as a
 * per-call callback would silently change what the member means.
 */
internal fun KSClassDeclaration.forwardArmLambdaMethods(
  classifier: ForwardBridgeTypeClassifier,
): List<KSFunctionDeclaration> {
  val candidates: List<KSFunctionDeclaration> = getAllFunctions()
    .filter { it.getVisibility() == Visibility.PUBLIC }
    .filter { it.parentDeclaration == this }
    .filter { !it.modifiers.contains(Modifier.SUSPEND) }
    .filter { !it.returnsForwardFlow() }
    // A data class's generated `copy` can carry a lambda-typed parameter; the ordinary route
    // excludes those members upstream and so does the plan, so this route must not claim them.
    .filter { method ->
      val name: String = method.simpleName.asString()
      val isDataClassMethod: Boolean = modifiers.contains(Modifier.DATA) &&
          (name == "copy" || name.startsWith("component"))
      name !in setOf("equals", "hashCode", "toString", "<init>") && !isDataClassMethod
    }
    .filter { method ->
      method.parameters.any { parameter ->
        parameter.type.resolve().expandAliases().declaration.qualifiedName?.asString() in
            LAMBDA_TYPES
      }
    }
    .toList()

  val paired: Set<KSFunctionDeclaration> = findStoredCallbackPairs(candidates)
    .flatMap { (add, remove) -> listOf(add, remove) }
    .toSet()

  return candidates
    .filter { it !in paired }
    // Issue #121: a marked declaration reaches neither artifact, legacy route or not.
    .filter { it.optInMarker(classifier.exportMarkers) == null }
    // ADR-123: a return this route cannot marshal drops the member on both halves, the C# rule
    // `translateClass` applies upstream of its own projection.
    .filter { classifier.legacyRefusedReturn(it) == null }
}
