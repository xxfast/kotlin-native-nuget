package io.github.xxfast.kotlin.native.nuget.processor.exports

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
        .returns(cOpaquePointer)
        .addStatement(
          "return %T.create(%L(%L)).asCPointer()",
          stableRef, qualifiedName, ctorParamCall,
        )
        .build()
    )
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
    val propTypeResolved: KSType = prop.type.resolve()
    val propType: String = propTypeResolved.declaration.qualifiedName?.asString() ?: "Any"
    val isNullable: Boolean = propTypeResolved.isMarkedNullable
    val isMutable: Boolean = prop.isMutable

    val isEnumType: Boolean = (propTypeResolved.declaration as? KSClassDeclaration)
      ?.classKind == ClassKind.ENUM_CLASS

    val isPrimitiveType: Boolean = propType in setOf(
      "kotlin.String", "kotlin.Byte", "kotlin.UByte", "kotlin.Short",
      "kotlin.UShort", "kotlin.Int", "kotlin.UInt", "kotlin.Long",
      "kotlin.ULong", "kotlin.Float", "kotlin.Double", "kotlin.Boolean",
      "kotlin.Unit",
    )

    if (isEnumType) {
      addFunction(
        FunSpec.builder("export_${prefix}_get_$propName")
          .addAnnotation(cNameAnnotation("${prefix}_get_$propName"))
          .addParameter("handle", cOpaquePointer)
          .returns(Int::class)
          .addStatement(
            "return handle.asStableRef<%L>().get().%L.ordinal",
            qualifiedName, propName,
          )
          .build()
      )

      if (isMutable) {
        addFunction(
          FunSpec.builder("export_${prefix}_set_$propName")
            .addAnnotation(cNameAnnotation("${prefix}_set_$propName"))
            .addParameter("handle", cOpaquePointer)
            .addParameter("value", Int::class)
            .addStatement(
              "handle.asStableRef<%L>().get().%L = %L.entries[value]",
              qualifiedName, propName, propType,
            )
            .build()
        )
      }
    } else if (isPrimitiveType && isNullable && propType != "kotlin.String") {
      val nonNullType = ClassName.bestGuess(propType)

      addFunction(
        FunSpec.builder("export_${prefix}_get_$propName")
          .addAnnotation(cNameAnnotation("${prefix}_get_$propName"))
          .addParameter("handle", cOpaquePointer)
          .returns(Boolean::class)
          .addStatement(
            "return handle.asStableRef<%L>().get().%L != null",
            qualifiedName, propName,
          )
          .build()
      )

      addFunction(
        FunSpec.builder("export_${prefix}_get_${propName}_value")
          .addAnnotation(cNameAnnotation("${prefix}_get_${propName}_value"))
          .addParameter("handle", cOpaquePointer)
          .returns(nonNullType)
          .addStatement(
            "return handle.asStableRef<%L>().get().%L!!",
            qualifiedName, propName,
          )
          .build()
      )

      if (isMutable) {
        addFunction(
          FunSpec.builder("export_${prefix}_set_$propName")
            .addAnnotation(cNameAnnotation("${prefix}_set_$propName"))
            .addParameter("handle", cOpaquePointer)
            .addParameter("value", nonNullType)
            .addStatement(
              "handle.asStableRef<%L>().get().%L = value",
              qualifiedName, propName,
            )
            .build()
        )

        addFunction(
          FunSpec.builder("export_${prefix}_set_${propName}_null")
            .addAnnotation(cNameAnnotation("${prefix}_set_${propName}_null"))
            .addParameter("handle", cOpaquePointer)
            .addStatement(
              "handle.asStableRef<%L>().get().%L = null",
              qualifiedName, propName,
            )
            .build()
        )
      }
    } else if (isPrimitiveType) {
      val primitiveTypeName = ClassName.bestGuess(propType).copy(nullable = isNullable)

      addFunction(
        FunSpec.builder("export_${prefix}_get_$propName")
          .addAnnotation(cNameAnnotation("${prefix}_get_$propName"))
          .addParameter("handle", cOpaquePointer)
          .returns(primitiveTypeName)
          .addStatement(
            "return handle.asStableRef<%L>().get().%L",
            qualifiedName, propName,
          )
          .build()
      )

      if (isMutable) {
        addFunction(
          FunSpec.builder("export_${prefix}_set_$propName")
            .addAnnotation(cNameAnnotation("${prefix}_set_$propName"))
            .addParameter("handle", cOpaquePointer)
            .addParameter("value", primitiveTypeName)
            .addStatement(
              "handle.asStableRef<%L>().get().%L = value",
              qualifiedName, propName,
            )
            .build()
        )
      }
    } else {
      val returnType: String = if (isNullable) "$cOpaquePointer?" else cOpaquePointer.toString()

      if (isNullable) {
        addFunction(
          FunSpec.builder("export_${prefix}_get_$propName")
            .addAnnotation(cNameAnnotation("${prefix}_get_$propName"))
            .addParameter("handle", cOpaquePointer)
            .returns(cOpaquePointer.copy(nullable = true))
            .addStatement(
              "val obj: %L? = handle.asStableRef<%L>().get().%L",
              propType, qualifiedName, propName,
            )
            .addStatement(
              "return if (obj == null) null else %T.create(obj).asCPointer()",
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
              .addStatement(
                "handle.asStableRef<%L>().get().%L = value?.asStableRef<%L>()?.get()",
                qualifiedName, propName, propType,
              )
              .build()
          )
        }
      } else {
        addFunction(
          FunSpec.builder("export_${prefix}_get_$propName")
            .addAnnotation(cNameAnnotation("${prefix}_get_$propName"))
            .addParameter("handle", cOpaquePointer)
            .returns(cOpaquePointer)
            .addStatement(
              "return %T.create(handle.asStableRef<%L>().get().%L).asCPointer()",
              stableRef, qualifiedName, propName,
            )
            .build()
        )

        if (isMutable) {
          addFunction(
            FunSpec.builder("export_${prefix}_set_$propName")
              .addAnnotation(cNameAnnotation("${prefix}_set_$propName"))
              .addParameter("handle", cOpaquePointer)
              .addParameter("value", cOpaquePointer)
              .addStatement(
                "handle.asStableRef<%L>().get().%L = value.asStableRef<%L>().get()",
                qualifiedName, propName, propType,
              )
              .build()
          )
        }
      }
    }
  }

  val methods: List<KSFunctionDeclaration> = cls.getAllFunctions()
    .filter { it.getVisibility() == Visibility.PUBLIC }
    .filter {
      val name: String = it.simpleName.asString()
      val isDataClassMethod: Boolean = cls.modifiers.contains(Modifier.DATA) &&
        (name == "copy" || name.startsWith("component"))
      name !in listOf("equals", "hashCode", "toString", "<init>") && !isDataClassMethod
    }
    .filter { method ->
      if (hasSuperClass) {
        method.parentDeclaration == cls && !method.modifiers.contains(Modifier.ABSTRACT)
      } else {
        val declaredInThisClass: Boolean = method.parentDeclaration == cls
        declaredInThisClass && !method.modifiers.contains(Modifier.ABSTRACT)
      }
    }
    .toList()

  for (method in methods) {
    val methodName: String = method.simpleName.asString()
    val methodReturn: String = method.returnType?.resolve()
      ?.declaration?.qualifiedName?.asString() ?: "Unit"

    val methodParamCall: String = method.parameters.joinToString(", ") {
      it.name?.asString() ?: "_"
    }

    val builder: FunSpec.Builder = FunSpec
      .builder("export_${prefix}_$methodName")
      .addAnnotation(cNameAnnotation("${prefix}_$methodName"))
      .addParameter("handle", cOpaquePointer)

    for (param in method.parameters) {
      val type: String =
        param.type.resolve().declaration.qualifiedName?.asString()
          ?: param.type.resolve().declaration.simpleName.asString()

      builder.addParameter(
        param.name?.asString() ?: "_",
        ClassName.bestGuess(type),
      )
    }

    if (methodReturn == "kotlin.Unit") {
      builder.addStatement(
        "handle.asStableRef<%L>().get().%L(%L)",
        qualifiedName, methodName, methodParamCall,
      )
    } else {
      builder.returns(ClassName.bestGuess(methodReturn))
      builder.addStatement(
        "return handle.asStableRef<%L>().get().%L(%L)",
        qualifiedName, methodName, methodParamCall,
      )
    }

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
        .returns(cOpaquePointer)

      for (param in constructor.parameters) {
        val type: String =
          param.type.resolve().declaration.qualifiedName?.asString()
            ?: param.type.resolve().declaration.simpleName.asString()

        copyBuilder.addParameter(
          param.name?.asString() ?: "_",
          ClassName.bestGuess(type),
        )
      }

      val copyParamCall: String = constructor.parameters.joinToString(", ") {
        val paramName: String = it.name?.asString() ?: "_"
        "$paramName = $paramName"
      }

      copyBuilder.addStatement(
        "return %T.create(handle.asStableRef<%L>().get().copy(%L)).asCPointer()",
        stableRef, qualifiedName, copyParamCall,
      )

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
    val methodReturn: String = method.returnType?.resolve()
      ?.declaration?.qualifiedName?.asString() ?: "Unit"

    val methodParamCall: String = method.parameters.joinToString(", ") {
      it.name?.asString() ?: "_"
    }

    val builder: FunSpec.Builder = FunSpec
      .builder("export_$entryPoint")
      .addAnnotation(cNameAnnotation(entryPoint))

    for (param in method.parameters) {
      val type: String =
        param.type.resolve().declaration.qualifiedName?.asString()
          ?: param.type.resolve().declaration.simpleName.asString()

      builder.addParameter(
        param.name?.asString() ?: "_",
        ClassName.bestGuess(type),
      )
    }

    val returnsEnclosingClass: Boolean = methodReturn == qualifiedName
    val returnDecl: KSClassDeclaration? = method.returnType?.resolve()?.declaration as? KSClassDeclaration
    val isObjectReturn: Boolean = returnsEnclosingClass ||
      (returnDecl != null && methodReturn !in setOf(
        "kotlin.String", "kotlin.Byte", "kotlin.UByte", "kotlin.Short", "kotlin.UShort",
        "kotlin.Int", "kotlin.UInt", "kotlin.Long", "kotlin.ULong",
        "kotlin.Float", "kotlin.Double", "kotlin.Boolean", "kotlin.Unit",
      ))

    if (isObjectReturn) {
      builder.returns(cOpaquePointer)
      builder.addStatement(
        "return %T.create(%L.%L(%L)).asCPointer()",
        stableRef, qualifiedName, methodName, methodParamCall,
      )
    } else if (methodReturn == "kotlin.Unit") {
      builder.addStatement(
        "%L.%L(%L)",
        qualifiedName, methodName, methodParamCall,
      )
    } else {
      builder.returns(ClassName.bestGuess(methodReturn))
      builder.addStatement(
        "return %L.%L(%L)",
        qualifiedName, methodName, methodParamCall,
      )
    }

    addFunction(builder.build())
  }

  val properties: List<KSPropertyDeclaration> = companion.getAllProperties()
    .filter { it.getVisibility() == Visibility.PUBLIC }
    .filter { !it.modifiers.contains(Modifier.CONST) }
    .toList()

  for (prop in properties) {
    val propName: String = prop.simpleName.asString()
    val propTypeResolved: KSType = prop.type.resolve()
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
