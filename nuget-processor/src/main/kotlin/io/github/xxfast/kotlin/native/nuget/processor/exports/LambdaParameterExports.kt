package io.github.xxfast.kotlin.native.nuget.processor.exports

import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import io.github.xxfast.kotlin.native.nuget.processor.cir.LAMBDA_TYPES
import io.github.xxfast.kotlin.native.nuget.processor.cir.expandAliases

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

  // CFunction signature for the Kotlin side
  // (arg0: COpaquePointer?, ..., userData: COpaquePointer) -> ReturnType
  val cfuncArgTypes: String = buildString {
    repeat(lambdaArity) {
      append("COpaquePointer?, ")
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

    // Marshal each lambda arg from Kotlin to COpaquePointer
    lambdaArgTypes.forEachIndexed { i, argType ->
      val argKotlin: String = argType.declaration.simpleName.asString()
      when (argKotlin) {
        "Boolean" -> appendLine("${indent}val arg${i}Ref: Byte = if (it$i) 1.toByte() else 0.toByte()")
        "String" -> appendLine("${indent}val arg${i}Ref = StableRef.create(it$i as Any).asCPointer()")
        else -> appendLine("${indent}val arg${i}Ref = StableRef.create(it$i).asCPointer()")
      }
    }

    val fnCallArgs: String = buildString {
      lambdaArgTypes.indices.forEach { i ->
        append("arg${i}Ref, ")
      }
      append("${lambdaParamName}UserData")
    }

    when {
      lambdaRetKotlin == "Unit" -> {
        appendLine("${indent}$fnVar.invoke($fnCallArgs)")
        lambdaArgTypes.forEachIndexed { i, argType ->
          val argKotlin: String = argType.declaration.simpleName.asString()
          if (argKotlin != "Boolean") {
            appendLine("${indent}arg${i}Ref!!.asStableRef<Any>().dispose()")
          }
        }
      }
      lambdaRetKotlin == "Boolean" -> {
        appendLine("${indent}val cbResult = $fnVar.invoke($fnCallArgs) != 0.toByte()")
        lambdaArgTypes.forEachIndexed { i, argType ->
          val argKotlin: String = argType.declaration.simpleName.asString()
          if (argKotlin != "Boolean") {
            appendLine("${indent}arg${i}Ref!!.asStableRef<Any>().dispose()")
          }
        }
        append("${indent}cbResult")
      }
      else -> {
        // String or object return from C# callback — backed by nuget_wrap_string StableRef
        appendLine("${indent}val resultRef = $fnVar.invoke($fnCallArgs)!!")
        lambdaArgTypes.forEachIndexed { i, argType ->
          val argKotlin: String = argType.declaration.simpleName.asString()
          if (argKotlin != "Boolean") {
            appendLine("${indent}arg${i}Ref!!.asStableRef<Any>().dispose()")
          }
        }
        appendLine("${indent}val cbResult = resultRef.asStableRef<String>().get()")
        appendLine("${indent}resultRef.asStableRef<Any>().dispose()")
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
        appendLine("    errorOut.reinterpret<COpaquePointerVar>().pointed.value = StableRef.create(")
        appendLine("      buildError(e)")
        appendLine("    ).asCPointer()")
        appendLine("  }")
        append("}")
      }
      isOuterRetList -> {
        appendLine("return try {")
        appendLine("  val list = handle.asStableRef<$qualifiedClassName>().get().$methodName {$lambdaArgDecl")
        append(callbackBody)
        appendLine()
        appendLine("  }")
        appendLine("  StableRef.create(list).asCPointer()")
        appendLine("} catch (e: Throwable) {")
        appendLine("  if (errorOut != null) {")
        appendLine("    errorOut.reinterpret<COpaquePointerVar>().pointed.value = StableRef.create(")
        appendLine("      buildError(e)")
        appendLine("    ).asCPointer()")
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
        appendLine("    errorOut.reinterpret<COpaquePointerVar>().pointed.value = StableRef.create(")
        appendLine("      buildError(e)")
        appendLine("    ).asCPointer()")
        appendLine("  }")
        appendLine("  ${defaultValueFor(outerRetQualified)}")
        append("}")
      }
    }
  }

  val builder: FunSpec.Builder = FunSpec
    .builder("export_${classPrefix}_$methodName")
    .addAnnotation(cNameAnnotation("${classPrefix}_$methodName"))
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
