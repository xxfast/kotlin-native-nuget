package io.github.xxfast.kotlin.native.nuget.processor.exports

import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import io.github.xxfast.kotlin.native.nuget.processor.cir.LAMBDA_TYPES
import io.github.xxfast.kotlin.native.nuget.processor.cir.expandAliases

/**
 * Detects `add{X}`/`remove{X}` (or `subscribe{X}`/`unsubscribe{X}`) method pairs that both
 * accept the same single lambda parameter. Returns the (add, remove) pairs.
 *
 * @see <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/037-stored-callbacks.md">ADR-037: Stored callbacks</a>
 */
internal fun findStoredCallbackPairs(
  methods: List<KSFunctionDeclaration>,
): List<Pair<KSFunctionDeclaration, KSFunctionDeclaration>> {
  val addPrefixes = listOf("add", "subscribe")
  val removePrefixes = listOf("remove", "unsubscribe")

  val byName: Map<String, KSFunctionDeclaration> = methods.associateBy { it.simpleName.asString() }

  return methods.mapNotNull { addMethod ->
    val addName: String = addMethod.simpleName.asString()
    val addPrefix: String = addPrefixes.find { prefix ->
      addName.startsWith(prefix) && addName.length > prefix.length
    } ?: return@mapNotNull null

    val suffix: String = addName.removePrefix(addPrefix)

    val removeMethod: KSFunctionDeclaration = removePrefixes.mapNotNull { removePrefix ->
      byName["$removePrefix$suffix"]
    }.firstOrNull() ?: return@mapNotNull null

    // Both must have a single lambda parameter of the same type
    val addLambdaParam = addMethod.parameters.singleOrNull { param ->
      param.type.resolve().expandAliases().declaration.qualifiedName?.asString() in LAMBDA_TYPES
    } ?: return@mapNotNull null

    val removeLambdaParam = removeMethod.parameters.singleOrNull { param ->
      param.type.resolve().expandAliases().declaration.qualifiedName?.asString() in LAMBDA_TYPES
    } ?: return@mapNotNull null

    // Confirm the lambda type argument lists match
    val addArgs: List<String?> = addLambdaParam.type.resolve().expandAliases().arguments
      .map { it.type?.resolve()?.expandAliases()?.declaration?.qualifiedName?.asString() }
    val removeArgs: List<String?> = removeLambdaParam.type.resolve().expandAliases().arguments
      .map { it.type?.resolve()?.expandAliases()?.declaration?.qualifiedName?.asString() }
    if (addArgs != removeArgs) return@mapNotNull null

    Pair(addMethod, removeMethod)
  }
}

/**
 * Generates two `@CName` exports for a stored-callback pair:
 * - A subscribe export that creates a bridge lambda, registers it with the Kotlin object,
 *   and returns an opaque unregister-closure handle.
 * - An unsubscribe export that invokes and disposes the closure handle.
 *
 * Uses the closure-StableRef approach: no global registry map; the unregister closure
 * captures both the object reference and the bridge lambda by reference.
 *
 * Enum lambda arg types are passed as their ordinal `Int` (not as StableRef handles).
 */
internal fun FileSpec.Builder.addStoredCallbackExports(
  addMethod: KSFunctionDeclaration,
  removeMethod: KSFunctionDeclaration,
  qualifiedClassName: String,
  classPrefix: String,
) {
  val addMethodName: String = addMethod.simpleName.asString()
  val removeMethodName: String = removeMethod.simpleName.asString()

  val lambdaParam = addMethod.parameters.firstOrNull { param ->
    param.type.resolve().expandAliases().declaration.qualifiedName?.asString() in LAMBDA_TYPES
  } ?: return

  val lambdaType: KSType = lambdaParam.type.resolve().expandAliases()
  val lambdaArgTypes: List<KSType> = lambdaType.arguments.dropLast(1)
    .mapNotNull { it.type?.resolve()?.expandAliases() }
  val lambdaArity: Int = lambdaArgTypes.size

  // Determine whether each arg is an enum (ordinal Int) or reference type
  data class ArgInfo(val qualifiedName: String, val isEnum: Boolean)
  val argInfos: List<ArgInfo> = lambdaArgTypes.map { argType ->
    val isEnum: Boolean = (argType.declaration as? KSClassDeclaration)?.classKind == ClassKind.ENUM_CLASS
    ArgInfo(
      qualifiedName = argType.declaration.qualifiedName?.asString() ?: argType.declaration.simpleName.asString(),
      isEnum = isEnum,
    )
  }

  // CFunction signature: enum args -> Int, reference args -> COpaquePointer?, arity-0 -> just userData
  val cfuncArgTypes: String = buildString {
    argInfos.forEach { info ->
      if (info.isEnum) append("Int, ")
      else append("COpaquePointer?, ")
    }
    append("COpaquePointer")  // userData is always last
  }
  val cfuncSignature: String = "($cfuncArgTypes) -> Unit"

  // Bridge lambda type annotation (fully qualified so no import needed)
  val bridgeLambdaType: String = if (lambdaArity == 0) {
    "() -> Unit"
  } else {
    val args: String = argInfos.joinToString(", ") { it.qualifiedName }
    "($args) -> Unit"
  }

  // Bridge lambda body
  val lambdaParamDecl: String = if (lambdaArity == 0) "" else {
    " " + argInfos.indices.joinToString(", ") { "arg$it" } + " ->"
  }

  val bridgeBodyLines: String = buildString {
    // Marshal each arg before the fn.invoke call
    argInfos.forEachIndexed { i, info ->
      if (!info.isEnum) {
        val argSimpleName: String = info.qualifiedName.substringAfterLast('.')
        when (argSimpleName) {
          "String" -> appendLine("    val arg${i}Ref = StableRef.create(arg$i as Any).asCPointer()")
          "Boolean" -> appendLine("    val arg${i}Val: Byte = if (arg$i) 1.toByte() else 0.toByte()")
          else -> appendLine("    val arg${i}Ref = StableRef.create(arg$i).asCPointer()")
        }
      }
    }

    // Build fn.invoke argument list
    val invokeArgs: String = buildString {
      argInfos.forEachIndexed { i, info ->
        if (info.isEnum) append("arg$i.ordinal, ")
        else {
          val argSimpleName: String = info.qualifiedName.substringAfterLast('.')
          if (argSimpleName == "Boolean") append("arg${i}Val, ") else append("arg${i}Ref, ")
        }
      }
      append("userData")
    }
    appendLine("    fn.invoke($invokeArgs)")

    // Dispose reference args after the call
    argInfos.forEachIndexed { i, info ->
      if (!info.isEnum) {
        val argSimpleName: String = info.qualifiedName.substringAfterLast('.')
        if (argSimpleName != "Boolean") {
          appendLine("    arg${i}Ref!!.asStableRef<Any>().dispose()")
        }
      }
    }
  }

  val subscribeBody: String = buildString {
    appendLine("return try {")
    appendLine("  val obj = handle.asStableRef<$qualifiedClassName>().get()")
    appendLine("  val fn = listenerPtr.reinterpret<CFunction<$cfuncSignature>>()")
    appendLine("  val bridge: $bridgeLambdaType = {$lambdaParamDecl")
    append(bridgeBodyLines)
    appendLine("  }")
    appendLine("  obj.$addMethodName(bridge)")
    appendLine("  val unregister: () -> Unit = { obj.$removeMethodName(bridge) }")
    appendLine("  StableRef.create(unregister).asCPointer()")
    appendLine("} catch (e: Throwable) {")
    appendLine("  if (errorOut != null) errorOut.reinterpret<COpaquePointerVar>().pointed.value = StableRef.create(buildError(e)).asCPointer()")
    appendLine("  null")
    append("}")
  }

  addFunction(
    FunSpec.builder("export_${classPrefix}_$addMethodName")
      .addAnnotation(cNameAnnotation("${classPrefix}_$addMethodName"))
      .addParameter("handle", cOpaquePointer)
      .addParameter("listenerPtr", cOpaquePointer)
      .addParameter("userData", cOpaquePointer)
      .addParameter("errorOut", cOpaquePointer.copy(nullable = true))
      .returns(cOpaquePointer.copy(nullable = true))
      .addCode(subscribeBody)
      .build()
  )

  val unsubscribeBody: String = buildString {
    appendLine("val ref = subscriptionHandle.asStableRef<() -> Unit>()")
    appendLine("ref.get().invoke()")
    append("ref.dispose()")
  }

  addFunction(
    FunSpec.builder("export_${classPrefix}_$removeMethodName")
      .addAnnotation(cNameAnnotation("${classPrefix}_$removeMethodName"))
      .addParameter("handle", cOpaquePointer)
      .addParameter("subscriptionHandle", cOpaquePointer)
      .addCode(unsubscribeBody)
      .build()
  )
}
