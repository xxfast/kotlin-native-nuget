package io.github.xxfast.kotlin.native.nuget.processor.exports

import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSValueParameter
import com.google.devtools.ksp.symbol.Visibility
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import io.github.xxfast.kotlin.native.nuget.processor.cir.expandAliases
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardBridgeTypeClassifier
import io.github.xxfast.kotlin.native.nuget.processor.forward.InterfaceBridgeWire
import io.github.xxfast.kotlin.native.nuget.processor.forward.interfaceBridgeWire

/**
 * ADR-039 amendment (2026-09-26): how [this] listener parameter crosses; see [interfaceBridgeWire].
 */
private fun KSValueParameter.wire(classifier: ForwardBridgeTypeClassifier): InterfaceBridgeWire =
  classifier.classify(type.resolve()).interfaceBridgeWire()

/**
 * The `override` parameter's Kotlin spelling: a `kotlin.*` type by simple name, anything else
 * qualified, with type arguments and `?` kept (the bare declaration name this used to print
 * dropped both, so a `List<Int>` or an `Int?` member "overrode nothing").
 */
private fun KSType.overrideSpelling(): String {
  val qualified: String = declaration.qualifiedName?.asString() ?: ""
  val name: String =
    if (qualified.startsWith("kotlin.")) declaration.simpleName.asString() else qualified
  val arguments: String =
    if (this.arguments.isEmpty()) ""
    else this.arguments.joinToString(", ", "<", ">") { argument ->
      argument.type?.resolve()?.expandAliases()?.overrideSpelling() ?: "*"
    }
  return "$name$arguments${if (isMarkedNullable) "?" else ""}"
}

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
  // ADR-039 amendment (2026-09-26): one classification per listener parameter, the same one the
  // pair gate (`legacyRefusedInterfaceBridgePair`) admitted it by.
  classifier: ForwardBridgeTypeClassifier,
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
    .filter { method -> !method.isCompilerOwnedMember(ifaceDecl) }
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
          val pSimple: String =
            param.type.resolve().expandAliases().declaration.simpleName.asString()
          when (param.wire(classifier)) {
            InterfaceBridgeWire.BOOL -> append("Byte, ")
            InterfaceBridgeWire.ORDINAL -> append("Int, ")
            InterfaceBridgeWire.BY_VALUE -> append("$pSimple, ")
            InterfaceBridgeWire.HANDLE -> append("COpaquePointer?, ")
          }
        }
        // ADR-161: ctx, then the trailing error slot.
        append("COpaquePointer, COpaquePointer?")
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
        "$pName: ${param.type.resolve().expandAliases().overrideSpelling()}"
      }

      appendLine("    override fun $mName($paramDecl) {")

      // Marshal each param before invoking the function pointer
      params.forEachIndexed { i, param ->
        val pName: String = param.name?.asString() ?: "arg$i"
        when (param.wire(classifier)) {
          InterfaceBridgeWire.BOOL ->
            appendLine("      val arg${i}Val: Byte = if ($pName) 1.toByte() else 0.toByte()")
          InterfaceBridgeWire.ORDINAL -> appendLine("      val arg${i}Val: Int = $pName.ordinal")
          InterfaceBridgeWire.BY_VALUE -> {
            /* primitives passed by value, no extra binding needed */
          }
          InterfaceBridgeWire.HANDLE ->
            appendLine("      val arg${i}Ref = NugetHandles.retain($pName as Any)")
        }
      }

      val invokeArgs: String = buildString {
        params.forEachIndexed { i, param ->
          val pName: String = param.name?.asString() ?: "arg$i"
          when (param.wire(classifier)) {
            InterfaceBridgeWire.BOOL, InterfaceBridgeWire.ORDINAL -> append("arg${i}Val, ")
            InterfaceBridgeWire.BY_VALUE -> append("$pName, ")
            InterfaceBridgeWire.HANDLE -> append("arg${i}Ref, ")
          }
        }
        append("${mName}Ctx, nugetErr")
      }
      // ADR-161: the ADR-039 listener bridge's members go through the same error channel.
      appendLine("      nugetCallbackCall { nugetErr -> ${mName}Fn.invoke($invokeArgs) }")

      // ADR-036 amendment (2026-09-11): a handle-passed argument belongs to the C# side once it
      // crosses. `NugetMarshal.FromHandle<string>` disposes as it reads, and an exported object
      // is handed to the wrapper's constructor, whose `Dispose()` is the free. Releasing here as
      // well freed a handle the C# side had already freed (measured at -2 per `onMeow` crossing,
      // three releases against one retain, with the thunk's own explicit `Dispose` making the
      // third).

      appendLine("    }")
    }
    appendLine("  }")

    appendLine("  obj.$addMethodName(bridge)")
    appendLine("  val unregister: () -> Unit = { obj.$removeMethodName(bridge) }")
    appendLine("  NugetHandles.retain(unregister)")
    appendLine("} catch (e: Throwable) {")
    appendLine(
      "  if (errorOut != null) errorOut.reinterpret<COpaquePointerVar>().pointed.value = " +
        "NugetHandles.retain(buildError(e))"
    )
    appendLine("  null")
    append("}")
  }

  val subscribeBuilder: FunSpec.Builder = FunSpec.builder("export_${classPrefix}_$addMethodName")
    .addAnnotation(cNameAnnotation("${classPrefix}_$addMethodName", ownedBy(addMethod)))
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
    append("NugetHandles.release(ref.asCPointer())")
  }

  addFunction(
    FunSpec.builder("export_${classPrefix}_$removeMethodName")
      .addAnnotation(cNameAnnotation("${classPrefix}_$removeMethodName", ownedBy(removeMethod)))
      .addParameter("handle", cOpaquePointer)
      .addParameter("subscriptionHandle", cOpaquePointer)
      .addCode(unsubscribeBody)
      .build()
  )
}
