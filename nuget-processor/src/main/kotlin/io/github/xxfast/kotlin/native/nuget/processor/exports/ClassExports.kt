package io.github.xxfast.kotlin.native.nuget.processor.exports

import com.google.devtools.ksp.getConstructors
import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Visibility
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.TypeName
import io.github.xxfast.kotlin.native.nuget.processor.cir.LAMBDA_TYPES
import io.github.xxfast.kotlin.native.nuget.processor.cir.expandAliases
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardCallablePlanCatalog
import io.github.xxfast.kotlin.native.nuget.processor.forward.addForwardKotlinPlanExport
import io.github.xxfast.kotlin.native.nuget.processor.forward.planFor
import io.github.xxfast.kotlin.native.nuget.processor.forward.addForwardPropertyPlanExports
import io.github.xxfast.kotlin.native.nuget.processor.toCName

/**
 * Generates @CName bridge exports for classes: constructor, properties (getters/setters),
 * methods, and data class operations (equals, hashCode, toString, copy).
 *
 * @see <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/003-memory-management-across-bridge.md">ADR-003: Memory management</a>
 * @see <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/005-object-return-semantics.md">ADR-005: Object return semantics</a>
 * @see <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/008-data-class-mapping.md">ADR-008: Data class mapping</a>
 */
internal fun FileSpec.Builder.addClassExports(
  cls: KSClassDeclaration,
  callableCatalog: ForwardCallablePlanCatalog,
) {
  val name: String = cls.simpleName.asString()
  val qualifiedName: String = cls.qualifiedName?.asString() ?: return
  val prefix: String = name.lowercase()
  val isAbstract: Boolean = cls.modifiers.contains(Modifier.ABSTRACT)

  val hasSuperClass: Boolean = cls.superTypes
    .map { it.resolve().declaration }
    .filterIsInstance<KSClassDeclaration>()
    .any { it.classKind == ClassKind.CLASS && it.qualifiedName?.asString() != "kotlin.Any" }

  val constructor: KSFunctionDeclaration? = cls.primaryConstructor

  if (constructor != null && !isAbstract) {
    val planned = callableCatalog.planFor("$qualifiedName.<init>")
    if (planned != null) {
      addForwardKotlinPlanExport(planned)
    } else {
    val ctorParamCall: String = constructor.parameters.joinToString(", ") {
      it.name?.asString() ?: "_"
    }

    addFunction(
      FunSpec.builder("export_${prefix}_create")
        .addAnnotation(cNameAnnotation("${prefix}_create"))
        .addParameters(constructor)
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
        }, stableRef, qualifiedName, ctorParamCall, cOpaquePointerVar, stableRef)
        .build()
    )
    }
  }

  if (!isAbstract) {
    val secondaryConstructors: List<KSFunctionDeclaration> = cls.getConstructors()
      .filter { it != cls.primaryConstructor }
      .filter { it.getVisibility() == Visibility.PUBLIC }
      .toList()

    secondaryConstructors.forEachIndexed { index, ctor ->
      val planned = callableCatalog.planFor("$qualifiedName.<init>_${index + 2}")
      if (planned != null) {
        addForwardKotlinPlanExport(planned)
        return@forEachIndexed
      }
      val cname: String = "${prefix}_create_${index + 2}"
      val ctorParamCall: String = ctor.parameters.joinToString(", ") {
        it.name?.asString() ?: "_"
      }

      addFunction(
        FunSpec.builder("export_$cname")
          .addAnnotation(cNameAnnotation(cname))
          .addParameters(ctor)
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
          }, stableRef, qualifiedName, ctorParamCall, cOpaquePointerVar, stableRef)
          .build()
      )
    }
  }

  addFunction(
    FunSpec.builder("export_${prefix}_dispose")
      .addAnnotation(cNameAnnotation("${prefix}_dispose"))
      .addParameter("handle", cOpaquePointer)
      .addStatement("handle.asStableRef<%L>().dispose()", qualifiedName)
      .build()
  )

  val properties: List<KSPropertyDeclaration> = cls.getAllProperties()
    .filter { it.getVisibility() == Visibility.PUBLIC }
    .filter { prop ->
      if (!hasSuperClass) return@filter true
      prop.parentDeclaration == cls
    }
    .toList()

  for (prop in properties) {
    val propName: String = prop.simpleName.asString()
    val planned = callableCatalog.propertyFor("$qualifiedName.$propName")
    if (planned != null) {
      addForwardPropertyPlanExports(planned)
      continue
    }
    val propTypeResolved: KSType = prop.type.resolve().expandAliases()
    val propType: String = propTypeResolved.declaration.qualifiedName?.asString() ?: "Any"
    val isNullable: Boolean = propTypeResolved.isMarkedNullable
    val isMutable: Boolean = prop.isMutable

    val isEnumType: Boolean = (propTypeResolved.declaration as? KSClassDeclaration)
      ?.classKind == ClassKind.ENUM_CLASS

    val isFlowType: Boolean = propType == "kotlinx.coroutines.flow.Flow"

    val isPrimitiveType: Boolean = propType in setOf(
      "kotlin.String", "kotlin.Byte", "kotlin.UByte", "kotlin.Short",
      "kotlin.UShort", "kotlin.Int", "kotlin.UInt", "kotlin.Long",
      "kotlin.ULong", "kotlin.Float", "kotlin.Double", "kotlin.Boolean",
      "kotlin.Unit",
    )

    if (isFlowType) {
      val flowElementType = propTypeResolved.arguments.firstOrNull()?.type?.resolve()
      val flowElementQualified: String =
        flowElementType?.declaration?.qualifiedName?.asString() ?: "kotlin.Any"

      addFunction(
        FunSpec.builder("export_${prefix}_get_${propName}_collect")
          .addAnnotation(cNameAnnotation("${prefix}_get_${propName}_collect"))
          .addParameter("handle", cOpaquePointer)
          .addParameter("scopeHandle", cOpaquePointer)
          .addParameter("onNextPtr", cOpaquePointer)
          .addParameter("onCompletePtr", cOpaquePointer)
          .addParameter("onErrorPtr", cOpaquePointer)
          .addParameter("userData", cOpaquePointer)
          .returns(cOpaquePointer)
          .addCode(buildFlowCollectBody(qualifiedName, propName, flowElementQualified))
          .build()
      )
    } else if (isEnumType) {
      addFunction(
        FunSpec.builder("export_${prefix}_get_$propName")
          .addAnnotation(cNameAnnotation("${prefix}_get_$propName"))
          .addParameter("handle", cOpaquePointer)
          .addParameter("errorOut", cOpaquePointer.copy(nullable = true))
          .returns(Int::class)
          .addCode(buildString {
            appendLine("return try {")
            appendLine("  handle.asStableRef<%L>().get().%L.ordinal")
            appendLine("} catch (e: Throwable) {")
            appendLine("  if (errorOut != null) {")
            appendLine("    errorOut.reinterpret<%T>().pointed.value = %T.create(")
            appendLine("      buildError(e)")
            appendLine("    ).asCPointer()")
            appendLine("  }")
            appendLine("  0")
            append("}")
          }, qualifiedName, propName, cOpaquePointerVar, stableRef)
          .build()
      )

      if (isMutable) {
        addFunction(
          FunSpec.builder("export_${prefix}_set_$propName")
            .addAnnotation(cNameAnnotation("${prefix}_set_$propName"))
            .addParameter("handle", cOpaquePointer)
            .addParameter("value", Int::class)
            .addParameter("errorOut", cOpaquePointer.copy(nullable = true))
            .addCode(buildString {
              appendLine("try {")
              appendLine("  handle.asStableRef<%L>().get().%L = %L.entries[value]")
              appendLine("} catch (e: Throwable) {")
              appendLine("  if (errorOut != null) {")
              appendLine("    errorOut.reinterpret<%T>().pointed.value = %T.create(")
              appendLine("      buildError(e)")
              appendLine("    ).asCPointer()")
              appendLine("  }")
              append("}")
            }, qualifiedName, propName, propType, cOpaquePointerVar, stableRef)
            .build()
        )
      }
    } else if (isPrimitiveType && isNullable && propType != "kotlin.String") {
      val nonNullType = ClassName.bestGuess(propType)
      val dummy: String = defaultValueFor(propType)

      addFunction(
        FunSpec.builder("export_${prefix}_get_$propName")
          .addAnnotation(cNameAnnotation("${prefix}_get_$propName"))
          .addParameter("handle", cOpaquePointer)
          .addParameter("errorOut", cOpaquePointer.copy(nullable = true))
          .returns(Boolean::class)
          .addCode(buildString {
            appendLine("return try {")
            appendLine("  handle.asStableRef<%L>().get().%L != null")
            appendLine("} catch (e: Throwable) {")
            appendLine("  if (errorOut != null) {")
            appendLine("    errorOut.reinterpret<%T>().pointed.value = %T.create(")
            appendLine("      buildError(e)")
            appendLine("    ).asCPointer()")
            appendLine("  }")
            appendLine("  false")
            append("}")
          }, qualifiedName, propName, cOpaquePointerVar, stableRef)
          .build()
      )

      addFunction(
        FunSpec.builder("export_${prefix}_get_${propName}_value")
          .addAnnotation(cNameAnnotation("${prefix}_get_${propName}_value"))
          .addParameter("handle", cOpaquePointer)
          .addParameter("errorOut", cOpaquePointer.copy(nullable = true))
          .returns(nonNullType)
          .addCode(buildString {
            appendLine("return try {")
            appendLine("  handle.asStableRef<%L>().get().%L!!")
            appendLine("} catch (e: Throwable) {")
            appendLine("  if (errorOut != null) {")
            appendLine("    errorOut.reinterpret<%T>().pointed.value = %T.create(")
            appendLine("      buildError(e)")
            appendLine("    ).asCPointer()")
            appendLine("  }")
            appendLine("  $dummy")
            append("}")
          }, qualifiedName, propName, cOpaquePointerVar, stableRef)
          .build()
      )

      if (isMutable) {
        addFunction(
          FunSpec.builder("export_${prefix}_set_$propName")
            .addAnnotation(cNameAnnotation("${prefix}_set_$propName"))
            .addParameter("handle", cOpaquePointer)
            .addParameter("value", nonNullType)
            .addParameter("errorOut", cOpaquePointer.copy(nullable = true))
            .addCode(buildString {
              appendLine("try {")
              appendLine("  handle.asStableRef<%L>().get().%L = value")
              appendLine("} catch (e: Throwable) {")
              appendLine("  if (errorOut != null) {")
              appendLine("    errorOut.reinterpret<%T>().pointed.value = %T.create(")
              appendLine("      buildError(e)")
              appendLine("    ).asCPointer()")
              appendLine("  }")
              append("}")
            }, qualifiedName, propName, cOpaquePointerVar, stableRef)
            .build()
        )

        addFunction(
          FunSpec.builder("export_${prefix}_set_${propName}_null")
            .addAnnotation(cNameAnnotation("${prefix}_set_${propName}_null"))
            .addParameter("handle", cOpaquePointer)
            .addParameter("errorOut", cOpaquePointer.copy(nullable = true))
            .addCode(buildString {
              appendLine("try {")
              appendLine("  handle.asStableRef<%L>().get().%L = null")
              appendLine("} catch (e: Throwable) {")
              appendLine("  if (errorOut != null) {")
              appendLine("    errorOut.reinterpret<%T>().pointed.value = %T.create(")
              appendLine("      buildError(e)")
              appendLine("    ).asCPointer()")
              appendLine("  }")
              append("}")
            }, qualifiedName, propName, cOpaquePointerVar, stableRef)
            .build()
        )
      }
    } else if (isPrimitiveType) {
      val primitiveTypeName = ClassName.bestGuess(propType).copy(nullable = isNullable)

      if (propType == "kotlin.Unit") {
        addFunction(
          FunSpec.builder("export_${prefix}_get_$propName")
            .addAnnotation(cNameAnnotation("${prefix}_get_$propName"))
            .addParameter("handle", cOpaquePointer)
            .addParameter("errorOut", cOpaquePointer.copy(nullable = true))
            .addCode(buildString {
              appendLine("try {")
              appendLine("  handle.asStableRef<%L>().get().%L")
              appendLine("} catch (e: Throwable) {")
              appendLine("  if (errorOut != null) {")
              appendLine("    errorOut.reinterpret<%T>().pointed.value = %T.create(")
              appendLine("      buildError(e)")
              appendLine("    ).asCPointer()")
              appendLine("  }")
              append("}")
            }, qualifiedName, propName, cOpaquePointerVar, stableRef)
            .build()
        )
      } else {
        addFunction(
          FunSpec.builder("export_${prefix}_get_$propName")
            .addAnnotation(cNameAnnotation("${prefix}_get_$propName"))
            .addParameter("handle", cOpaquePointer)
            .addParameter("errorOut", cOpaquePointer.copy(nullable = true))
            .returns(primitiveTypeName)
            .addCode(buildString {
              appendLine("return try {")
              appendLine("  handle.asStableRef<%L>().get().%L")
              appendLine("} catch (e: Throwable) {")
              appendLine("  if (errorOut != null) {")
              appendLine("    errorOut.reinterpret<%T>().pointed.value = %T.create(")
              appendLine("      buildError(e)")
              appendLine("    ).asCPointer()")
              appendLine("  }")
              appendLine("  ${defaultValueFor(propType)}")
              append("}")
            }, qualifiedName, propName, cOpaquePointerVar, stableRef)
            .build()
        )
      }

      if (isMutable) {
        addFunction(
          FunSpec.builder("export_${prefix}_set_$propName")
            .addAnnotation(cNameAnnotation("${prefix}_set_$propName"))
            .addParameter("handle", cOpaquePointer)
            .addParameter("value", primitiveTypeName)
            .addParameter("errorOut", cOpaquePointer.copy(nullable = true))
            .addCode(buildString {
              appendLine("try {")
              appendLine("  handle.asStableRef<%L>().get().%L = value")
              appendLine("} catch (e: Throwable) {")
              appendLine("  if (errorOut != null) {")
              appendLine("    errorOut.reinterpret<%T>().pointed.value = %T.create(")
              appendLine("      buildError(e)")
              appendLine("    ).asCPointer()")
              appendLine("  }")
              append("}")
            }, qualifiedName, propName, cOpaquePointerVar, stableRef)
            .build()
        )
      }
    } else {
      if (isNullable) {
        addFunction(
          FunSpec.builder("export_${prefix}_get_$propName")
            .addAnnotation(cNameAnnotation("${prefix}_get_$propName"))
            .addParameter("handle", cOpaquePointer)
            .addParameter("errorOut", cOpaquePointer.copy(nullable = true))
            .returns(cOpaquePointer.copy(nullable = true))
            .addCode(
              buildString {
                appendLine("return try {")
                appendLine("  val obj: %T = handle.asStableRef<%L>().get().%L")
                appendLine("  if (obj == null) null else %T.create(obj).asCPointer()")
                appendLine("} catch (e: Throwable) {")
                appendLine("  if (errorOut != null) {")
                appendLine("    errorOut.reinterpret<%T>().pointed.value = %T.create(")
                appendLine("      buildError(e)")
                appendLine("    ).asCPointer()")
                appendLine("  }")
                appendLine("  null")
                append("}")
              },
              propTypeResolved.toBridgeTypeName(),
              qualifiedName,
              propName,
              stableRef,
              cOpaquePointerVar,
              stableRef,
            )
            .build()
        )

        if (isMutable) {
          addFunction(
            FunSpec.builder("export_${prefix}_set_$propName")
              .addAnnotation(cNameAnnotation("${prefix}_set_$propName"))
              .addParameter("handle", cOpaquePointer)
              .addParameter("value", cOpaquePointer.copy(nullable = true))
              .addParameter("errorOut", cOpaquePointer.copy(nullable = true))
              .addCode(
                buildString {
                  appendLine("try {")
                  appendLine(
                    "  handle.asStableRef<%L>().get().%L = " +
                        "value?.asStableRef<%T>()?.get()"
                  )
                  appendLine("} catch (e: Throwable) {")
                  appendLine("  if (errorOut != null) {")
                  appendLine("    errorOut.reinterpret<%T>().pointed.value = %T.create(")
                  appendLine("      buildError(e)")
                  appendLine("    ).asCPointer()")
                  appendLine("  }")
                  append("}")
                },
                qualifiedName,
                propName,
                propTypeResolved.toBridgeTypeName(nullable = false),
                cOpaquePointerVar,
                stableRef
              )
              .build()
          )
        }
      } else {
        addFunction(
          FunSpec.builder("export_${prefix}_get_$propName")
            .addAnnotation(cNameAnnotation("${prefix}_get_$propName"))
            .addParameter("handle", cOpaquePointer)
            .addParameter("errorOut", cOpaquePointer.copy(nullable = true))
            .returns(cOpaquePointer.copy(nullable = true))
            .addCode(buildString {
              appendLine("return try {")
              appendLine("  %T.create(handle.asStableRef<%L>().get().%L).asCPointer()")
              appendLine("} catch (e: Throwable) {")
              appendLine("  if (errorOut != null) {")
              appendLine("    errorOut.reinterpret<%T>().pointed.value = %T.create(")
              appendLine("      buildError(e)")
              appendLine("    ).asCPointer()")
              appendLine("  }")
              appendLine("  null")
              append("}")
            }, stableRef, qualifiedName, propName, cOpaquePointerVar, stableRef)
            .build()
        )

        if (isMutable) {
          addFunction(
            FunSpec.builder("export_${prefix}_set_$propName")
              .addAnnotation(cNameAnnotation("${prefix}_set_$propName"))
              .addParameter("handle", cOpaquePointer)
              .addParameter("value", cOpaquePointer)
              .addParameter("errorOut", cOpaquePointer.copy(nullable = true))
              .addCode(buildString {
                appendLine("try {")
                appendLine("  handle.asStableRef<%L>().get().%L = value.asStableRef<%L>().get()")
                appendLine("} catch (e: Throwable) {")
                appendLine("  if (errorOut != null) {")
                appendLine("    errorOut.reinterpret<%T>().pointed.value = %T.create(")
                appendLine("      buildError(e)")
                appendLine("    ).asCPointer()")
                appendLine("  }")
                append("}")
              }, qualifiedName, propName, propType, cOpaquePointerVar, stableRef)
              .build()
          )
        }
      }
    }
  }

  val allRegularMethods: List<KSFunctionDeclaration> = cls.getAllFunctions()
    .filter { it.getVisibility() == Visibility.PUBLIC }
    .filter {
      val name: String = it.simpleName.asString()
      val isDataClassMethod: Boolean = cls.modifiers.contains(Modifier.DATA) &&
          (name == "copy" || name.startsWith("component"))
      name !in listOf("equals", "hashCode", "toString", "<init>") && !isDataClassMethod
    }
    .filter { !it.modifiers.contains(Modifier.SUSPEND) }
    .filter { method ->
      if (hasSuperClass) {
        method.parentDeclaration == cls && !method.modifiers.contains(Modifier.ABSTRACT)
      } else {
        val declaredInThisClass: Boolean = method.parentDeclaration == cls
        declaredInThisClass && !method.modifiers.contains(Modifier.ABSTRACT)
      }
    }
    .toList()

  val flowMethods: List<KSFunctionDeclaration> = allRegularMethods.filter { method ->
    val returnQualified: String? = method.returnType?.resolve()
      ?.expandAliases()?.declaration?.qualifiedName?.asString()
    returnQualified == "kotlinx.coroutines.flow.Flow"
  }

  val allNonFlowMethods: List<KSFunctionDeclaration> = allRegularMethods.filter { method ->
    val returnQualified: String? = method.returnType?.resolve()
      ?.expandAliases()?.declaration?.qualifiedName?.asString()
    returnQualified != "kotlinx.coroutines.flow.Flow"
  }

  val (lambdaParamMethods, methods) = allNonFlowMethods.partition { method ->
    method.parameters.any { param ->
      param.type.resolve().expandAliases().declaration.qualifiedName?.asString() in LAMBDA_TYPES
    }
  }

  // Detect stored-callback pairs: add*/subscribe* + matching remove*/unsubscribe*.
  // Paired add* methods get stored-callback exports; paired remove* methods are excluded entirely.
  val storedCallbackPairs: List<Pair<KSFunctionDeclaration, KSFunctionDeclaration>> =
    findStoredCallbackPairs(lambdaParamMethods)
  val storedCallbackAddMethods: Set<KSFunctionDeclaration> = storedCallbackPairs
    .map { it.first }.toSet()
  val storedCallbackRemoveMethods: Set<KSFunctionDeclaration> = storedCallbackPairs
    .map { it.second }.toSet()

  storedCallbackPairs.forEach { (addMethod, removeMethod) ->
    addStoredCallbackExports(addMethod, removeMethod, qualifiedName, prefix)
  }

  lambdaParamMethods.forEach { method ->
    if (method in storedCallbackAddMethods || method in storedCallbackRemoveMethods) return@forEach
    addLambdaParamMethodExport(method, qualifiedName, prefix)
  }

  // Detect interface-bridge pairs: add*/subscribe* + matching remove*/unsubscribe* where the
  // parameter is a Kotlin interface type (not a lambda). Both halves are excluded from the
  // regular method loop; the add half gets interface-bridge exports.
  val interfaceBridgePairs: List<Pair<KSFunctionDeclaration, KSFunctionDeclaration>> =
    findInterfaceBridgePairs(methods)
  val interfaceBridgeExcluded: Set<KSFunctionDeclaration> =
    (interfaceBridgePairs.map { it.first } + interfaceBridgePairs.map { it.second }).toSet()

  interfaceBridgePairs.forEach { (addMethod, removeMethod) ->
    addInterfaceBridgeExports(addMethod, removeMethod, qualifiedName, prefix)
  }

  methods.forEach { method ->
    if (method in interfaceBridgeExcluded) return@forEach
    val methodName: String = method.simpleName.asString()
    val planned = callableCatalog.planFor("$qualifiedName.$methodName")
    if (planned != null) {
      addForwardKotlinPlanExport(planned)
      return@forEach
    }
    val methodReturnType: KSType? = method.returnType?.resolve()?.expandAliases()
    val methodReturn: String = methodReturnType?.declaration?.qualifiedName?.asString() ?: "Unit"
    val isNullableReturn: Boolean = methodReturnType?.isMarkedNullable == true

    // ADR-061: route the return through the same marshalling cascade the property getter
    // already uses (object / nullable object / collection / nullable String / nullable
    // primitive), instead of the old single `else` branch that declared a by-value return no
    // body could satisfy (BUG-005 follow-up / ADR-060 cells 4/5/6).
    val isEnumReturn: Boolean = (methodReturnType?.declaration as? KSClassDeclaration)
      ?.classKind == ClassKind.ENUM_CLASS
    // "kotlin.Char" is deliberately included here even though it is not one of ADR-061's five
    // return shapes: it is excluded so it keeps falling to the `else` bucket below (the *same*
    // pre-existing, separately-tracked bug as before this ADR — ADR-060 cell 13's
    // `defaultValueFor` literal-vs-declared-type mismatch), instead of being accidentally
    // "fixed" by the new StableRef object-boxing branch, which would box a `Char` nonsensically.
    val isPrimitiveReturn: Boolean = methodReturn in setOf(
      "kotlin.String", "kotlin.Byte", "kotlin.UByte", "kotlin.Short",
      "kotlin.UShort", "kotlin.Int", "kotlin.UInt", "kotlin.Long",
      "kotlin.ULong", "kotlin.Float", "kotlin.Double", "kotlin.Boolean",
      "kotlin.Char", "kotlin.Unit",
    )
    val isListReturn: Boolean = !isEnumReturn &&
        (methodReturn == "kotlin.collections.List" ||
            methodReturn == "kotlin.collections.MutableList")
    val isObjectReturn: Boolean = !isEnumReturn && !isPrimitiveReturn && !isListReturn

    // For enum parameters, the C boundary uses Int; convert with EnumClass.entries[value].
    val methodParamCall: String = method.parameters.joinToString(", ") { param ->
      val resolved = param.type.resolve().expandAliases()
      val paramName: String = param.name?.asString() ?: "_"
      val isEnum: Boolean = (resolved.declaration as? KSClassDeclaration)
        ?.classKind == ClassKind.ENUM_CLASS
      if (isEnum) {
        val enumQualifiedName: String = resolved.declaration.qualifiedName?.asString() ?: paramName
        "$enumQualifiedName.entries[$paramName]"
      } else {
        paramName
      }
    }

    val builder: FunSpec.Builder = FunSpec
      .builder("export_${prefix}_$methodName")
      .addAnnotation(cNameAnnotation("${prefix}_$methodName"))
      .addParameter("handle", cOpaquePointer)

    for (param in method.parameters) {
      val resolved = param.type.resolve().expandAliases()
      val paramName: String = param.name?.asString() ?: "_"
      val isEnum: Boolean = (resolved.declaration as? KSClassDeclaration)
        ?.classKind == ClassKind.ENUM_CLASS
      if (isEnum) {
        builder.addParameter(paramName, Int::class)
      } else {
        builder.addParameter(paramName, resolved.toBridgeTypeName(nullable = false))
      }
    }

    when {
      methodReturn == "kotlin.Unit" -> {
        builder.addParameter("errorOut", cOpaquePointer.copy(nullable = true))
        builder.addCode(buildString {
          appendLine("try {")
          appendLine("  handle.asStableRef<%L>().get().%L(%L)")
          appendLine("} catch (e: Throwable) {")
          appendLine("  if (errorOut != null) {")
          appendLine("    errorOut.reinterpret<%T>().pointed.value = %T.create(")
          appendLine("      buildError(e)")
          appendLine("    ).asCPointer()")
          appendLine("  }")
          append("}")
        }, qualifiedName, methodName, methodParamCall, cOpaquePointerVar, stableRef)
      }

      isListReturn || (isObjectReturn && !isNullableReturn) -> {
        // Object return (ADR-061 §1) / collection return (§3): both are, on the Kotlin side,
        // just the object carrier — box the result via StableRef, mirroring the companion-method
        // loop's `isObjectReturn` branch (ClassExports.kt's addCompanionExports, below).
        builder.addParameter("errorOut", cOpaquePointer.copy(nullable = true))
        builder.returns(cOpaquePointer.copy(nullable = true))
        builder.addCode(buildString {
          appendLine("return try {")
          appendLine("  %T.create(handle.asStableRef<%L>().get().%L(%L)).asCPointer()")
          appendLine("} catch (e: Throwable) {")
          appendLine("  if (errorOut != null) {")
          appendLine("    errorOut.reinterpret<%T>().pointed.value = %T.create(")
          appendLine("      buildError(e)")
          appendLine("    ).asCPointer()")
          appendLine("  }")
          appendLine("  null")
          append("}")
        }, stableRef, qualifiedName, methodName, methodParamCall, cOpaquePointerVar, stableRef)
      }

      isObjectReturn && isNullableReturn -> {
        // Nullable object return (ADR-061 §2).
        builder.addParameter("errorOut", cOpaquePointer.copy(nullable = true))
        builder.returns(cOpaquePointer.copy(nullable = true))
        builder.addCode(
          buildString {
            appendLine("return try {")
            appendLine("  val obj: %T = handle.asStableRef<%L>().get().%L(%L)")
            appendLine("  if (obj == null) null else %T.create(obj).asCPointer()")
            appendLine("} catch (e: Throwable) {")
            appendLine("  if (errorOut != null) {")
            appendLine("    errorOut.reinterpret<%T>().pointed.value = %T.create(")
            appendLine("      buildError(e)")
            appendLine("    ).asCPointer()")
            appendLine("  }")
            appendLine("  null")
            append("}")
          },
          methodReturnType!!.toBridgeTypeName(),
          qualifiedName,
          methodName,
          methodParamCall,
          stableRef,
          cOpaquePointerVar,
          stableRef
        )
      }

      isPrimitiveReturn && isNullableReturn && methodReturn != "kotlin.String" -> {
        // Nullable primitive return (ADR-061 §5): single call. The method is invoked exactly
        // once; the has-value `Boolean` return and the `valueOut` out-write mirror the already-
        // shipped `errorOut` out-write (ADR-024), differing only in the `CVariable` type arg.
        val nonNullReturnType: TypeName = methodReturnType!!.toBridgeTypeName(nullable = false)
        builder.addParameter("valueOut", cOpaquePointer.copy(nullable = true))
        builder.addParameter("errorOut", cOpaquePointer.copy(nullable = true))
        builder.returns(Boolean::class)
        builder.addCode(
          buildString {
            appendLine("return try {")
            appendLine("  val result: %T? = handle.asStableRef<%L>().get().%L(%L)")
            appendLine("  if (result != null && valueOut != null) {")
            appendLine("    valueOut.reinterpret<%T>().pointed.value = result")
            appendLine("  }")
            appendLine("  result != null")
            appendLine("} catch (e: Throwable) {")
            appendLine("  if (errorOut != null) {")
            appendLine("    errorOut.reinterpret<%T>().pointed.value = %T.create(")
            appendLine("      buildError(e)")
            appendLine("    ).asCPointer()")
            appendLine("  }")
            appendLine("  false")
            append("}")
          },
          nonNullReturnType,
          qualifiedName,
          methodName,
          methodParamCall,
          cVarTypeFor(methodReturn),
          cOpaquePointerVar,
          stableRef
        )
      }

      else -> {
        // Non-null primitives (unchanged) and nullable String (ADR-061 §4): a nullable Kotlin
        // `String` crosses the ABI as a plain pointer (null for `null`), so this needs no new
        // shape — just declaring the real nullability instead of the old hardcoded non-null.
        builder.addParameter("errorOut", cOpaquePointer.copy(nullable = true))
        builder.returns(methodReturnType?.toBridgeTypeName() ?: ClassName.bestGuess(methodReturn))
        builder.addCode(buildString {
          appendLine("return try {")
          appendLine("  handle.asStableRef<%L>().get().%L(%L)")
          appendLine("} catch (e: Throwable) {")
          appendLine("  if (errorOut != null) {")
          appendLine("    errorOut.reinterpret<%T>().pointed.value = %T.create(")
          appendLine("      buildError(e)")
          appendLine("    ).asCPointer()")
          appendLine("  }")
          appendLine("  ${defaultValueFor(methodReturn)}")
          append("}")
        }, qualifiedName, methodName, methodParamCall, cOpaquePointerVar, stableRef)
      }
    }

    addFunction(builder.build())
  }

  flowMethods.forEach { method ->
    val methodName: String = method.simpleName.asString()
    val cname: String = toCName(methodName)
    val returnType = method.returnType?.resolve()?.expandAliases()
    val flowElementType = returnType?.arguments?.firstOrNull()?.type?.resolve()
    val flowElementQualified: String =
      flowElementType?.declaration?.qualifiedName?.asString() ?: "kotlin.Any"

    val paramCall: String = method.parameters
      .joinToString(", ") { it.name?.asString() ?: "_" }

    val builder: FunSpec.Builder = FunSpec
      .builder("export_${prefix}_${cname}_collect")
      .addAnnotation(cNameAnnotation("${prefix}_${cname}_collect"))
      .addParameter("handle", cOpaquePointer)
      .addParameter("scopeHandle", cOpaquePointer)

    method.parameters.forEach { param ->
      val resolved = param.type.resolve().expandAliases()
      val type: String = resolved.declaration.qualifiedName?.asString()
        ?: resolved.declaration.simpleName.asString()
      builder.addParameter(
        param.name?.asString() ?: "_",
        ClassName.bestGuess(type),
      )
    }

    builder
      .addParameter("onNextPtr", cOpaquePointer)
      .addParameter("onCompletePtr", cOpaquePointer)
      .addParameter("onErrorPtr", cOpaquePointer)
      .addParameter("userData", cOpaquePointer)
      .returns(cOpaquePointer)
      .addCode(
        buildFlowMethodCollectBody(
          qualifiedName, methodName, paramCall, flowElementQualified,
        )
      )

    addFunction(builder.build())
  }

  if (cls.modifiers.contains(Modifier.DATA)) {
    addFunction(
      FunSpec.builder("export_${prefix}_equals")
        .addAnnotation(cNameAnnotation("${prefix}_equals"))
        .addParameter("handle", cOpaquePointer)
        .addParameter("other", cOpaquePointer)
        .returns(Boolean::class)
        .addStatement(
          "return handle.asStableRef<%L>().get() == other.asStableRef<%L>().get()",
          qualifiedName, qualifiedName,
        )
        .build()
    )

    addFunction(
      FunSpec.builder("export_${prefix}_hashcode")
        .addAnnotation(cNameAnnotation("${prefix}_hashcode"))
        .addParameter("handle", cOpaquePointer)
        .returns(Int::class)
        .addStatement(
          "return handle.asStableRef<%L>().get().hashCode()",
          qualifiedName,
        )
        .build()
    )

    addFunction(
      FunSpec.builder("export_${prefix}_tostring")
        .addAnnotation(cNameAnnotation("${prefix}_tostring"))
        .addParameter("handle", cOpaquePointer)
        .returns(String::class)
        .addStatement(
          "return handle.asStableRef<%L>().get().toString()",
          qualifiedName,
        )
        .build()
    )

    if (constructor != null) {
      val planned = callableCatalog.planFor("$qualifiedName.copy")
      if (planned != null) {
        addForwardKotlinPlanExport(planned)
        return@addClassExports
      }
      val copyBuilder: FunSpec.Builder = FunSpec
        .builder("export_${prefix}_copy")
        .addAnnotation(cNameAnnotation("${prefix}_copy"))
        .addParameter("handle", cOpaquePointer)

      for (param in constructor.parameters) {
        val resolved = param.type.resolve().expandAliases()
        copyBuilder.addParameter(
          param.name?.asString() ?: "_",
          resolved.toBridgeTypeName(nullable = false),
        )
      }

      copyBuilder.addParameter("errorOut", cOpaquePointer.copy(nullable = true))

      val copyParamCall: String = constructor.parameters.joinToString(", ") {
        val paramName: String = it.name?.asString() ?: "_"
        "$paramName = $paramName"
      }

      copyBuilder.returns(cOpaquePointer.copy(nullable = true))
      copyBuilder.addCode(buildString {
        appendLine("return try {")
        appendLine("  %T.create(handle.asStableRef<%L>().get().copy(%L)).asCPointer()")
        appendLine("} catch (e: Throwable) {")
        appendLine("  if (errorOut != null) {")
        appendLine("    errorOut.reinterpret<%T>().pointed.value = %T.create(")
        appendLine("      buildError(e)")
        appendLine("    ).asCPointer()")
        appendLine("  }")
        appendLine("  null")
        append("}")
      }, stableRef, qualifiedName, copyParamCall, cOpaquePointerVar, stableRef)

      addFunction(copyBuilder.build())
    }
  }
}

internal fun FileSpec.Builder.addCompanionExports(
  cls: KSClassDeclaration,
  callableCatalog: ForwardCallablePlanCatalog,
) {
  val name: String = cls.simpleName.asString()
  val qualifiedName: String = cls.qualifiedName?.asString() ?: return
  val prefix: String = name.lowercase()

  val companion: KSClassDeclaration = cls.declarations
    .filterIsInstance<KSClassDeclaration>()
    .firstOrNull { it.isCompanionObject } ?: return

  val methods: List<KSFunctionDeclaration> = companion.getAllFunctions()
    .filter { it.getVisibility() == Visibility.PUBLIC }
    .filter { it.simpleName.asString() !in listOf("equals", "hashCode", "toString", "<init>") }
    .toList()

  for (method in methods) {
    val methodName: String = method.simpleName.asString()
    val planned = callableCatalog.planFor("$qualifiedName.Companion.$methodName")
    if (planned != null) {
      addForwardKotlinPlanExport(planned)
      continue
    }
    val cname: String = toCName(methodName)
    val entryPoint: String = "${prefix}_companion_${cname}"
    val methodReturnType: KSType? = method.returnType?.resolve()?.expandAliases()
    val methodReturn: String = methodReturnType?.declaration?.qualifiedName?.asString() ?: "Unit"

    val methodParamCall: String = method.parameters.joinToString(", ") {
      it.name?.asString() ?: "_"
    }

    val builder: FunSpec.Builder = FunSpec
      .builder("export_$entryPoint")
      .addAnnotation(cNameAnnotation(entryPoint))

    for (param in method.parameters) {
      val resolved = param.type.resolve().expandAliases()

      builder.addParameter(
        param.name?.asString() ?: "_",
        resolved.toBridgeTypeName(nullable = false),
      )
    }

    val returnsEnclosingClass: Boolean = methodReturn == qualifiedName
    val returnDecl: KSClassDeclaration? = method.returnType
      ?.resolve()?.expandAliases()?.declaration as? KSClassDeclaration
    val isObjectReturn: Boolean = returnsEnclosingClass ||
        (returnDecl != null && methodReturn !in setOf(
          "kotlin.String", "kotlin.Byte", "kotlin.UByte", "kotlin.Short", "kotlin.UShort",
          "kotlin.Int", "kotlin.UInt", "kotlin.Long", "kotlin.ULong",
          "kotlin.Float", "kotlin.Double", "kotlin.Boolean", "kotlin.Unit",
        ))

    builder.addParameter("errorOut", cOpaquePointer.copy(nullable = true))

    if (isObjectReturn) {
      builder.returns(cOpaquePointer.copy(nullable = true))
      builder.addCode(buildString {
        appendLine("return try {")
        appendLine("  %T.create(%L.%L(%L)).asCPointer()")
        appendLine("} catch (e: Throwable) {")
        appendLine("  if (errorOut != null) {")
        appendLine("    errorOut.reinterpret<%T>().pointed.value = %T.create(")
        appendLine("      buildError(e)")
        appendLine("    ).asCPointer()")
        appendLine("  }")
        appendLine("  null")
        append("}")
      }, stableRef, qualifiedName, methodName, methodParamCall, cOpaquePointerVar, stableRef)
    } else if (methodReturn == "kotlin.Unit") {
      builder.addCode(buildString {
        appendLine("try {")
        appendLine("  %L.%L(%L)")
        appendLine("} catch (e: Throwable) {")
        appendLine("  if (errorOut != null) {")
        appendLine("    errorOut.reinterpret<%T>().pointed.value = %T.create(")
        appendLine("      buildError(e)")
        appendLine("    ).asCPointer()")
        appendLine("  }")
        append("}")
      }, qualifiedName, methodName, methodParamCall, cOpaquePointerVar, stableRef)
    } else {
      builder.returns(
        methodReturnType?.toBridgeTypeName(nullable = false) ?: ClassName.bestGuess(methodReturn),
      )
      builder.addCode(buildString {
        appendLine("return try {")
        appendLine("  %L.%L(%L)")
        appendLine("} catch (e: Throwable) {")
        appendLine("  if (errorOut != null) {")
        appendLine("    errorOut.reinterpret<%T>().pointed.value = %T.create(")
        appendLine("      buildError(e)")
        appendLine("    ).asCPointer()")
        appendLine("  }")
        appendLine("  ${defaultValueFor(methodReturn)}")
        append("}")
      }, qualifiedName, methodName, methodParamCall, cOpaquePointerVar, stableRef)
    }

    addFunction(builder.build())
  }

  val properties: List<KSPropertyDeclaration> = companion.getAllProperties()
    .filter { it.getVisibility() == Visibility.PUBLIC }
    .filter { !it.modifiers.contains(Modifier.CONST) }
    .toList()

  for (prop in properties) {
    val propName: String = prop.simpleName.asString()
    val planned = callableCatalog.propertyFor("$qualifiedName.Companion.$propName")
    if (planned != null) {
      addForwardPropertyPlanExports(planned)
      continue
    }
    val propTypeResolved: KSType = prop.type.resolve().expandAliases()
    val propType: String = propTypeResolved.declaration.qualifiedName?.asString() ?: "Any"
    val isMutable: Boolean = prop.isMutable

    val isPrimitiveType: Boolean = propType in setOf(
      "kotlin.String", "kotlin.Byte", "kotlin.UByte", "kotlin.Short",
      "kotlin.UShort", "kotlin.Int", "kotlin.UInt", "kotlin.Long",
      "kotlin.ULong", "kotlin.Float", "kotlin.Double", "kotlin.Boolean",
    )

    if (!isPrimitiveType) continue

    val primitiveTypeName = ClassName.bestGuess(propType)

    addFunction(
      FunSpec.builder("export_${prefix}_companion_get_$propName")
        .addAnnotation(cNameAnnotation("${prefix}_companion_get_$propName"))
        .returns(primitiveTypeName)
        .addStatement("return %L.%L", qualifiedName, propName)
        .build()
    )

    if (isMutable) {
      addFunction(
        FunSpec.builder("export_${prefix}_companion_set_$propName")
          .addAnnotation(cNameAnnotation("${prefix}_companion_set_$propName"))
          .addParameter("value", primitiveTypeName)
          .addStatement("%L.%L = value", qualifiedName, propName)
          .build()
      )
    }
  }
}

private fun buildFlowCollectBody(
  qualifiedName: String,
  propName: String,
  flowElementQualified: String,
): String = buildString {
  appendLine("val obj = handle.asStableRef<$qualifiedName>().get()")
  appendLine("val scope = scopeHandle.asStableRef<CoroutineScope>().get()")
  appendLine(
    "val onNext = onNextPtr.reinterpret<CFunction<" +
        "(COpaquePointer?, Byte, COpaquePointer) -> Unit>>()"
  )
  appendLine(
    "val onComplete = onCompletePtr.reinterpret<CFunction<" +
        "(COpaquePointer) -> Unit>>()"
  )
  appendLine(
    "val onError = onErrorPtr.reinterpret<CFunction<" +
        "(COpaquePointer?, COpaquePointer) -> Unit>>()"
  )
  appendLine("val job = scope.launch(start = CoroutineStart.ATOMIC) {")
  appendLine("  try {")
  appendLine("    obj.$propName.collect { value ->")
  appendLine("      val itemRef = StableRef.create(value as Any).asCPointer()")
  appendLine("      onNext.invoke(itemRef, 0.toByte(), userData)")
  appendLine("    }")
  appendLine("    onComplete.invoke(userData)")
  appendLine("  } catch (e: CancellationException) {")
  appendLine("    onNext.invoke(null, 1.toByte(), userData)")
  appendLine("    throw e")
  appendLine("  } catch (e: Throwable) {")
  appendLine("    val errRef = StableRef.create(buildError(e)).asCPointer()")
  appendLine("    onError.invoke(errRef, userData)")
  appendLine("  }")
  appendLine("}")
  append("return StableRef.create(job).asCPointer()")
}

private fun buildFlowMethodCollectBody(
  qualifiedName: String,
  methodName: String,
  paramCall: String,
  flowElementQualified: String,
): String = buildString {
  appendLine("val obj = handle.asStableRef<$qualifiedName>().get()")
  appendLine("val scope = scopeHandle.asStableRef<CoroutineScope>().get()")
  appendLine(
    "val onNext = onNextPtr.reinterpret<CFunction<" +
        "(COpaquePointer?, Byte, COpaquePointer) -> Unit>>()"
  )
  appendLine(
    "val onComplete = onCompletePtr.reinterpret<CFunction<" +
        "(COpaquePointer) -> Unit>>()"
  )
  appendLine(
    "val onError = onErrorPtr.reinterpret<CFunction<" +
        "(COpaquePointer?, COpaquePointer) -> Unit>>()"
  )
  appendLine("val job = scope.launch(start = CoroutineStart.ATOMIC) {")
  appendLine("  try {")
  appendLine("    obj.$methodName($paramCall).collect { value ->")
  appendLine("      val itemRef = StableRef.create(value as Any).asCPointer()")
  appendLine("      onNext.invoke(itemRef, 0.toByte(), userData)")
  appendLine("    }")
  appendLine("    onComplete.invoke(userData)")
  appendLine("  } catch (e: CancellationException) {")
  appendLine("    onNext.invoke(null, 1.toByte(), userData)")
  appendLine("    throw e")
  appendLine("  } catch (e: Throwable) {")
  appendLine("    val errRef = StableRef.create(buildError(e)).asCPointer()")
  appendLine("    onError.invoke(errRef, userData)")
  appendLine("  }")
  appendLine("}")
  append("return StableRef.create(job).asCPointer()")
}
