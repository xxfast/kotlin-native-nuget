package io.github.xxfast.kotlin.native.nuget.processor.exports

import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.Visibility
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import io.github.xxfast.kotlin.native.nuget.processor.cir.expandAliases

/**
 * Generates two `@CName` exports for an interface-bridge pair:
 * - A subscribe export that creates an anonymous bridge object implementing the Kotlin interface,
 *   with one function pointer pair per interface method, registers the bridge with the Kotlin object,
 *   and returns an opaque unregister-closure handle.
 * - An unsubscribe export that invokes and disposes the closure handle.
 *
 * Uses the closure-StableRef approach: the unregister closure captures the bridge object and
 * the Kotlin object by reference, preserving referential identity for list removal.
 *
 * @see <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/039-interface-bridging.md">ADR-039: Interface bridging</a>
 */
internal fun FileSpec.Builder.addInterfaceBridgeExports(
  addMethod: KSFunctionDeclaration,
  removeMethod: KSFunctionDeclaration,
  qualifiedClassName: String,
  classPrefix: String,
) {
  val addMethodName: String = addMethod.simpleName.asString()
  val removeMethodName: String = removeMethod.simpleName.asString()

  val ifaceParam = addMethod.parameters.firstOrNull { param ->
    (param.type.resolve().expandAliases().declaration as? KSClassDeclaration)
      ?.classKind == ClassKind.INTERFACE
  } ?: return

  val ifaceDecl: KSClassDeclaration = ifaceParam.type.resolve().expandAliases()
    .declaration as? KSClassDeclaration ?: return
  val ifaceQualifiedName: String = ifaceDecl.qualifiedName?.asString() ?: return

  val ifaceMethods: List<KSFunctionDeclaration> = ifaceDecl.getAllFunctions()
    .filter { it.getVisibility() == Visibility.PUBLIC }
    .filter { it.simpleName.asString() !in listOf("equals", "hashCode", "toString", "<init>") }
    .toList()

  if (ifaceMethods.isEmpty()) return

  val subscribeBody: String = buildString {
    appendLine("return try {")
    appendLine("  val obj = handle.asStableRef<$qualifiedClassName>().get()")

    // Reinterpret each method's function pointer
    ifaceMethods.forEach { method ->
      val mName: String = method.simpleName.asString()
      val params = method.parameters.toList()

      val cfuncArgs: String = buildString {
        params.forEach { param ->
          val pType = param.type.resolve().expandAliases()
          val pSimple: String = pType.declaration.simpleName.asString()
          val pQualified: String = pType.declaration.qualifiedName?.asString() ?: ""
          val isEnum: Boolean = (pType.declaration as? KSClassDeclaration)
            ?.classKind == ClassKind.ENUM_CLASS
          val isPrimitive: Boolean = pQualified.startsWith("kotlin.") && pSimple != "String"
          when {
            pSimple == "Boolean" -> append("Byte, ")
            isEnum -> append("Int, ")
            isPrimitive -> append("$pSimple, ")
            else -> append("COpaquePointer?, ")
          }
        }
        append("COpaquePointer")
      }
      appendLine("  val ${mName}Fn = ${mName}Ptr.reinterpret<CFunction<($cfuncArgs) -> Unit>>()")
    }

    // Build anonymous bridge object
    appendLine("  val bridge = object : $ifaceQualifiedName {")
    ifaceMethods.forEach { method ->
      val mName: String = method.simpleName.asString()
      val params = method.parameters.toList()

      val paramDecl: String = params.joinToString(", ") { param ->
        val pName: String = param.name?.asString() ?: "_"
        val pType = param.type.resolve().expandAliases()
        val pQualified: String = pType.declaration.qualifiedName?.asString() ?: ""
        val pSimple: String = pType.declaration.simpleName.asString()
        val typeStr: String = if (pQualified.startsWith("kotlin.")) pSimple else pQualified
        "$pName: $typeStr"
      }

      appendLine("    override fun $mName($paramDecl) {")

      // Marshal each param before invoking the function pointer
      params.forEachIndexed { i, param ->
        val pName: String = param.name?.asString() ?: "arg$i"
        val pType = param.type.resolve().expandAliases()
        val pSimple: String = pType.declaration.simpleName.asString()
        val pQualified: String = pType.declaration.qualifiedName?.asString() ?: ""
        val isEnum: Boolean = (pType.declaration as? KSClassDeclaration)
          ?.classKind == ClassKind.ENUM_CLASS
        val isPrimitive: Boolean = pQualified.startsWith("kotlin.") && pSimple != "String"
        when {
          pSimple == "Boolean" ->
            appendLine("      val arg${i}Val: Byte = if ($pName) 1.toByte() else 0.toByte()")
          isEnum -> appendLine("      val arg${i}Val: Int = $pName.ordinal")
          isPrimitive -> { /* primitives passed by value, no extra binding needed */ }
          else -> appendLine("      val arg${i}Ref = StableRef.create($pName as Any).asCPointer()")
        }
      }

      val invokeArgs: String = buildString {
        params.forEachIndexed { i, param ->
          val pName: String = param.name?.asString() ?: "arg$i"
          val pType = param.type.resolve().expandAliases()
          val pSimple: String = pType.declaration.simpleName.asString()
          val pQualified: String = pType.declaration.qualifiedName?.asString() ?: ""
          val isEnum: Boolean = (pType.declaration as? KSClassDeclaration)
            ?.classKind == ClassKind.ENUM_CLASS
          val isPrimitive: Boolean = pQualified.startsWith("kotlin.") && pSimple != "String"
          when {
            pSimple == "Boolean" -> append("arg${i}Val, ")
            isEnum -> append("arg${i}Val, ")
            isPrimitive -> append("$pName, ")
            else -> append("arg${i}Ref, ")
          }
        }
        append("${mName}Ctx")
      }
      appendLine("      ${mName}Fn.invoke($invokeArgs)")

      // Dispose reference args after the call
      params.forEachIndexed { i, param ->
        val pType = param.type.resolve().expandAliases()
        val pSimple: String = pType.declaration.simpleName.asString()
        val pQualified: String = pType.declaration.qualifiedName?.asString() ?: ""
        val isEnum: Boolean = (pType.declaration as? KSClassDeclaration)
          ?.classKind == ClassKind.ENUM_CLASS
        val isPrimitive: Boolean = pQualified.startsWith("kotlin.") && pSimple != "String"
        if (!isEnum && !isPrimitive && pSimple != "Boolean") {
          appendLine("      arg${i}Ref!!.asStableRef<Any>().dispose()")
        }
      }

      appendLine("    }")
    }
    appendLine("  }")

    appendLine("  obj.$addMethodName(bridge)")
    appendLine("  val unregister: () -> Unit = { obj.$removeMethodName(bridge) }")
    appendLine("  StableRef.create(unregister).asCPointer()")
    appendLine("} catch (e: Throwable) {")
    appendLine(
      "  if (errorOut != null) errorOut.reinterpret<COpaquePointerVar>().pointed.value = " +
        "StableRef.create(buildError(e)).asCPointer()"
    )
    appendLine("  null")
    append("}")
  }

  val subscribeBuilder: FunSpec.Builder = FunSpec.builder("export_${classPrefix}_$addMethodName")
    .addAnnotation(cNameAnnotation("${classPrefix}_$addMethodName"))
    .addParameter("handle", cOpaquePointer)

  ifaceMethods.forEach { method ->
    val mName: String = method.simpleName.asString()
    subscribeBuilder.addParameter("${mName}Ptr", cOpaquePointer)
    subscribeBuilder.addParameter("${mName}Ctx", cOpaquePointer)
  }

  subscribeBuilder
    .addParameter("errorOut", cOpaquePointer.copy(nullable = true))
    .returns(cOpaquePointer.copy(nullable = true))
    .addCode(subscribeBody)

  addFunction(subscribeBuilder.build())

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
