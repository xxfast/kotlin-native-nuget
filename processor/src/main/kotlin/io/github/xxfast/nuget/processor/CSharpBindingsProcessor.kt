package io.github.xxfast.nuget.processor

import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSValueParameter
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Visibility
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.ksp.writeTo
import io.github.xxfast.nuget.processor.cir.CirFile
import io.github.xxfast.nuget.processor.cir.CirRenderer
import io.github.xxfast.nuget.processor.cir.CirTranslator

class CSharpBindingsProcessor(
  private val codeGenerator: CodeGenerator,
  private val logger: KSPLogger,
  libraryName: String,
  rootNamespace: String,
  rootPackage: String,
  className: String,
) : SymbolProcessor {

  private var processed = false

  private val translator = CirTranslator(
    libraryName, rootNamespace, rootPackage, className,
  )

  private val renderer = CirRenderer()

  override fun process(resolver: Resolver): List<KSAnnotated> {
    if (processed) return emptyList()
    processed = true

    val allDeclarations: List<KSDeclaration> = resolver.getAllFiles()
      .flatMap { it.declarations }
      .filter { it.packageName.asString() != "io.github.xxfast.nuget.generated" }
      .toList()

    val functions: List<KSFunctionDeclaration> = allDeclarations
      .filterIsInstance<KSFunctionDeclaration>()
      .filter { it.getVisibility() == Visibility.PUBLIC }
      .filter { it.parentDeclaration == null }

    val classes: List<KSClassDeclaration> = allDeclarations
      .filterIsInstance<KSClassDeclaration>()
      .filter { it.getVisibility() == Visibility.PUBLIC }
      .filter { it.classKind == ClassKind.CLASS }
      .filter { it.parentDeclaration == null }

    val enums: List<KSClassDeclaration> = allDeclarations
      .filterIsInstance<KSClassDeclaration>()
      .filter { it.getVisibility() == Visibility.PUBLIC }
      .filter { it.classKind == ClassKind.ENUM_CLASS }
      .filter { it.parentDeclaration == null }

    val interfaces: List<KSClassDeclaration> = allDeclarations
      .filterIsInstance<KSClassDeclaration>()
      .filter { it.getVisibility() == Visibility.PUBLIC }
      .filter { it.classKind == ClassKind.INTERFACE }
      .filter { it.parentDeclaration == null }

    if (functions.isEmpty() && classes.isEmpty() && enums.isEmpty() && interfaces.isEmpty()) return emptyList()

    val sources: Array<KSFile> = (functions.mapNotNull { it.containingFile } +
      classes.mapNotNull { it.containingFile } +
      enums.mapNotNull { it.containingFile } +
      interfaces.mapNotNull { it.containingFile }).toTypedArray()

    val deps = Dependencies(aggregating = true, *sources)

    generateCNameWrappers(functions, classes, enums, deps)
    generateCSharpBindings(functions, classes, enums, interfaces, deps)

    logger.info(
      "Generated bindings for ${functions.size} functions" +
        ", ${classes.size} classes" +
        ", ${enums.size} enums" +
        ", and ${interfaces.size} interfaces"
    )

    return emptyList()
  }

  private fun generateCSharpBindings(
    functions: List<KSFunctionDeclaration>,
    classes: List<KSClassDeclaration>,
    enums: List<KSClassDeclaration>,
    interfaces: List<KSClassDeclaration>,
    deps: Dependencies,
  ) {
    val cirFile: CirFile = translator.translate(functions, classes, enums, interfaces)
    val csharp: String = renderer.render(cirFile)

    val file = codeGenerator.createNewFile(
      dependencies = deps,
      packageName = "",
      fileName = "Interop",
      extensionName = "cs",
    )

    file.writer().use { writer -> writer.write(csharp) }
  }

  private val cNameAnnotation = ClassName("kotlin.native", "CName")
  private val cOpaquePointer = ClassName("kotlinx.cinterop", "COpaquePointer")
  private val stableRef = ClassName("kotlinx.cinterop", "StableRef")

  private fun generateCNameWrappers(
    functions: List<KSFunctionDeclaration>,
    classes: List<KSClassDeclaration>,
    enums: List<KSClassDeclaration>,
    deps: Dependencies,
  ) {
    val fileSpec: FileSpec = FileSpec
      .builder("io.github.xxfast.nuget.generated", "CNameExports")
      .addAnnotation(
        AnnotationSpec.builder(ClassName("kotlin", "OptIn"))
          .addMember(
            "%T::class, %T::class",
            ClassName("kotlin.experimental", "ExperimentalNativeApi"),
            ClassName("kotlinx.cinterop", "ExperimentalForeignApi"),
          )
          .build()
      )
      .addImport("kotlinx.cinterop", "asStableRef")
      .apply {
        for (func in functions) {
          addImport(
            func.packageName.asString(),
            func.simpleName.asString(),
          )

          addFunctionExports(func)
        }

        for (cls in classes) {
          addClassExports(cls)
        }

        for (enum in enums) {
          addEnumExports(enum)
        }
      }
      .build()

    fileSpec.writeTo(codeGenerator, Dependencies(aggregating = true))
  }

  private fun FileSpec.Builder.addFunctionExports(func: KSFunctionDeclaration) {
    val cname: String = toCName(func.simpleName.asString())
    val funcName: String = func.simpleName.asString()
    val returnType: KSType? = func.returnType?.resolve()
    val isNullable: Boolean = returnType?.isMarkedNullable == true
    val qualifiedReturn: String = returnType
      ?.declaration?.qualifiedName?.asString() ?: "Unit"

    val paramCall: String = func.parameters.joinToString(", ") {
      it.name?.asString() ?: "_"
    }

    if (isNullable) {
      addFunction(
        FunSpec.builder("export_${cname}_has_value")
          .addAnnotation(cNameAnnotation(cname + "_has_value"))
          .addParameters(func)
          .returns(Boolean::class)
          .addStatement("return %L(%L) != null", funcName, paramCall)
          .build()
      )

      val valueReturn: String =
        if (qualifiedReturn == "kotlin.String") "kotlin.String"
        else qualifiedReturn

      addFunction(
        FunSpec.builder("export_${cname}_value")
          .addAnnotation(cNameAnnotation(cname + "_value"))
          .addParameters(func)
          .returns(ClassName.bestGuess(valueReturn))
          .addStatement("return %L(%L)!!", funcName, paramCall)
          .build()
      )
      return
    }

    if (qualifiedReturn == "kotlin.Unit") {
      addFunction(
        FunSpec.builder("export_$cname")
          .addAnnotation(cNameAnnotation(cname))
          .addParameters(func)
          .addStatement("%L(%L)", funcName, paramCall)
          .build()
      )
      return
    }

    addFunction(
      FunSpec.builder("export_$cname")
        .addAnnotation(cNameAnnotation(cname))
        .addParameters(func)
        .returns(ClassName.bestGuess(qualifiedReturn))
        .addStatement("return %L(%L)", funcName, paramCall)
        .build()
    )
  }

  private fun FileSpec.Builder.addClassExports(cls: KSClassDeclaration) {
    val name: String = cls.simpleName.asString()
    val qualifiedName: String = cls.qualifiedName?.asString() ?: return
    val prefix: String = name.lowercase()

    val constructor: KSFunctionDeclaration = cls.primaryConstructor ?: return

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

    addFunction(
      FunSpec.builder("export_${prefix}_dispose")
        .addAnnotation(cNameAnnotation("${prefix}_dispose"))
        .addParameter("handle", cOpaquePointer)
        .addStatement("handle.asStableRef<%L>().dispose()", qualifiedName)
        .build()
    )

    val properties: List<KSPropertyDeclaration> = cls.getAllProperties()
      .filter { it.getVisibility() == Visibility.PUBLIC }
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
      } else if (isPrimitiveType) {
        addFunction(
          FunSpec.builder("export_${prefix}_get_$propName")
            .addAnnotation(cNameAnnotation("${prefix}_get_$propName"))
            .addParameter("handle", cOpaquePointer)
            .returns(ClassName.bestGuess(propType))
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
              .addParameter("value", ClassName.bestGuess(propType))
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

  private fun FunSpec.Builder.addParameters(
    func: KSFunctionDeclaration,
  ): FunSpec.Builder {
    for (param in func.parameters) {
      val type: String =
        param.type.resolve().declaration.qualifiedName?.asString()
          ?: param.type.resolve().declaration.simpleName.asString()

      addParameter(param.name?.asString() ?: "_", ClassName.bestGuess(type))
    }
    return this
  }

  private fun FileSpec.Builder.addEnumExports(enum: KSClassDeclaration) {
    val name: String = enum.simpleName.asString()
    val qualifiedName: String = enum.qualifiedName?.asString() ?: return
    val prefix: String = name.lowercase()

    val properties: List<KSPropertyDeclaration> = enum.getAllProperties()
      .filter { it.getVisibility() == Visibility.PUBLIC }
      .filter { it.simpleName.asString() !in setOf("name", "ordinal", "declaringJavaClass") }
      .toList()

    for (prop in properties) {
      val propName: String = prop.simpleName.asString()
      val propType: String = prop.type.resolve().declaration.qualifiedName?.asString() ?: "Any"

      addFunction(
        FunSpec.builder("export_${prefix}_get_$propName")
          .addAnnotation(cNameAnnotation("${prefix}_get_$propName"))
          .addParameter("ordinal", Int::class)
          .returns(ClassName.bestGuess(propType))
          .addStatement("val ${prefix}: %L = %L.entries[ordinal]", qualifiedName, qualifiedName)
          .addStatement("return ${prefix}.$propName")
          .build()
      )
    }
  }

  private fun cNameAnnotation(value: String): AnnotationSpec =
    AnnotationSpec.builder(cNameAnnotation)
      .addMember("%S", value)
      .build()

}
