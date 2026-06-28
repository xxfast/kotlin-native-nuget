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
import io.github.xxfast.kotlin.native.nuget.processor.cir.expandAliases
import io.github.xxfast.kotlin.native.nuget.processor.toCName

/**
 * Generates @CName bridge exports for classes: constructor, properties (getters/setters),
 * methods, and data class operations (equals, hashCode, toString, copy).
 *
 * @see <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/003-memory-management-across-bridge.md">ADR-003: Memory management</a>
 * @see <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/005-object-return-semantics.md">ADR-005: Object return semantics</a>
 * @see <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/008-data-class-mapping.md">ADR-008: Data class mapping</a>
 */
internal fun FileSpec.Builder.addClassExports(cls: KSClassDeclaration) {
  val name: String = cls.simpleName.asString()
  val qualifiedName: String = cls.qualifiedName?.asString() ?: return
  val prefix: String = name.lowercase()
  val isAbstract: Boolean = cls.modifiers.contains(Modifier.ABSTRACT)

  val hasSuperClass: Boolean = cls.superTypes
    .map { it.resolve().declaration }
    .filterIsInstance<KSClassDeclaration>()
    .any { it.classKind == com.google.devtools.ksp.symbol.ClassKind.CLASS &&
      it.qualifiedName?.asString() != "kotlin.Any" }

  val constructor: KSFunctionDeclaration? = cls.primaryConstructor

  if (constructor != null && !isAbstract) {
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

  if (!isAbstract) {
    val secondaryConstructors: List<KSFunctionDeclaration> = cls.getConstructors()
      .filter { it != cls.primaryConstructor }
      .filter { it.getVisibility() == Visibility.PUBLIC }
      .toList()

    secondaryConstructors.forEachIndexed { index, ctor ->
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
            .addCode(buildString {
              appendLine("return try {")
              appendLine("  val obj: %L? = handle.asStableRef<%L>().get().%L")
              appendLine("  if (obj == null) null else %T.create(obj).asCPointer()")
              appendLine("} catch (e: Throwable) {")
              appendLine("  if (errorOut != null) {")
              appendLine("    errorOut.reinterpret<%T>().pointed.value = %T.create(")
              appendLine("      buildError(e)")
              appendLine("    ).asCPointer()")
              appendLine("  }")
              appendLine("  null")
              append("}")
            }, propType, qualifiedName, propName, stableRef, cOpaquePointerVar, stableRef)
            .build()
        )

        if (isMutable) {
          addFunction(
            FunSpec.builder("export_${prefix}_set_$propName")
              .addAnnotation(cNameAnnotation("${prefix}_set_$propName"))
              .addParameter("handle", cOpaquePointer)
              .addParameter("value", cOpaquePointer.copy(nullable = true))
              .addParameter("errorOut", cOpaquePointer.copy(nullable = true))
              .addCode(buildString {
                appendLine("try {")
                appendLine("  handle.asStableRef<%L>().get().%L = value?.asStableRef<%L>()?.get()")
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

  val methods: List<KSFunctionDeclaration> = allRegularMethods.filter { method ->
    val returnQualified: String? = method.returnType?.resolve()
      ?.expandAliases()?.declaration?.qualifiedName?.asString()
    returnQualified != "kotlinx.coroutines.flow.Flow"
  }

  for (method in methods) {
    val methodName: String = method.simpleName.asString()
    val methodReturn: String = method.returnType?.resolve()?.expandAliases()
      ?.declaration?.qualifiedName?.asString() ?: "Unit"

    val methodParamCall: String = method.parameters.joinToString(", ") {
      it.name?.asString() ?: "_"
    }

    val builder: FunSpec.Builder = FunSpec
      .builder("export_${prefix}_$methodName")
      .addAnnotation(cNameAnnotation("${prefix}_$methodName"))
      .addParameter("handle", cOpaquePointer)

    for (param in method.parameters) {
      val resolved = param.type.resolve().expandAliases()
      val type: String =
        resolved.declaration.qualifiedName?.asString()
          ?: resolved.declaration.simpleName.asString()

      builder.addParameter(
        param.name?.asString() ?: "_",
        ClassName.bestGuess(type),
      )
    }

    builder.addParameter("errorOut", cOpaquePointer.copy(nullable = true))

    if (methodReturn == "kotlin.Unit") {
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
    } else {
      builder.returns(ClassName.bestGuess(methodReturn))
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
      .addCode(buildFlowMethodCollectBody(
        qualifiedName, methodName, paramCall, flowElementQualified,
      ))

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
      val copyBuilder: FunSpec.Builder = FunSpec
        .builder("export_${prefix}_copy")
        .addAnnotation(cNameAnnotation("${prefix}_copy"))
        .addParameter("handle", cOpaquePointer)

      for (param in constructor.parameters) {
        val resolved = param.type.resolve().expandAliases()
        val type: String =
          resolved.declaration.qualifiedName?.asString()
            ?: resolved.declaration.simpleName.asString()

        copyBuilder.addParameter(
          param.name?.asString() ?: "_",
          ClassName.bestGuess(type),
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

internal fun FileSpec.Builder.addCompanionExports(cls: KSClassDeclaration) {
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
    val cname: String = toCName(methodName)
    val entryPoint: String = "${prefix}_companion_${cname}"
    val methodReturn: String = method.returnType?.resolve()?.expandAliases()
      ?.declaration?.qualifiedName?.asString() ?: "Unit"

    val methodParamCall: String = method.parameters.joinToString(", ") {
      it.name?.asString() ?: "_"
    }

    val builder: FunSpec.Builder = FunSpec
      .builder("export_$entryPoint")
      .addAnnotation(cNameAnnotation(entryPoint))

    for (param in method.parameters) {
      val resolved = param.type.resolve().expandAliases()
      val type: String =
        resolved.declaration.qualifiedName?.asString()
          ?: resolved.declaration.simpleName.asString()

      builder.addParameter(
        param.name?.asString() ?: "_",
        ClassName.bestGuess(type),
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
      builder.returns(ClassName.bestGuess(methodReturn))
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
  appendLine("val onNext = onNextPtr.reinterpret<CFunction<" +
    "(COpaquePointer?, Byte, COpaquePointer) -> Unit>>()")
  appendLine("val onComplete = onCompletePtr.reinterpret<CFunction<" +
    "(COpaquePointer) -> Unit>>()")
  appendLine("val onError = onErrorPtr.reinterpret<CFunction<" +
    "(COpaquePointer?, COpaquePointer) -> Unit>>()")
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
  appendLine("val onNext = onNextPtr.reinterpret<CFunction<" +
    "(COpaquePointer?, Byte, COpaquePointer) -> Unit>>()")
  appendLine("val onComplete = onCompletePtr.reinterpret<CFunction<" +
    "(COpaquePointer) -> Unit>>()")
  appendLine("val onError = onErrorPtr.reinterpret<CFunction<" +
    "(COpaquePointer?, COpaquePointer) -> Unit>>()")
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
