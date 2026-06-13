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
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Visibility
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.ksp.writeTo
import io.github.xxfast.nuget.processor.cir.CirFile
import io.github.xxfast.nuget.processor.cir.CirRenderer
import io.github.xxfast.nuget.processor.cir.CirTranslator
import io.github.xxfast.nuget.processor.exports.addClassExports
import io.github.xxfast.nuget.processor.exports.addEnumExports
import io.github.xxfast.nuget.processor.exports.addFunctionExports
import io.github.xxfast.nuget.processor.exports.addGenericClassExports
import io.github.xxfast.nuget.processor.exports.addNugetHelperExports
import io.github.xxfast.nuget.processor.exports.addObjectExports
import io.github.xxfast.nuget.processor.exports.addSealedClassExports

class NugetProcessor(
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

    val allClasses: List<KSClassDeclaration> = allDeclarations
      .filterIsInstance<KSClassDeclaration>()
      .filter { it.getVisibility() == Visibility.PUBLIC }
      .filter { it.classKind == ClassKind.CLASS }
      .filter { it.parentDeclaration == null }
      .filter { !it.modifiers.contains(Modifier.SEALED) }

    val classes: List<KSClassDeclaration> = allClasses.filter { it.typeParameters.isEmpty() }
    val genericClasses: List<KSClassDeclaration> = allClasses.filter { it.typeParameters.isNotEmpty() }

    val sealedClasses: List<KSClassDeclaration> = allDeclarations
      .filterIsInstance<KSClassDeclaration>()
      .filter { it.getVisibility() == Visibility.PUBLIC }
      .filter { it.classKind == ClassKind.CLASS }
      .filter { it.parentDeclaration == null }
      .filter { it.modifiers.contains(Modifier.SEALED) }

    val objects: List<KSClassDeclaration> = allDeclarations
      .filterIsInstance<KSClassDeclaration>()
      .filter { it.getVisibility() == Visibility.PUBLIC }
      .filter { it.classKind == ClassKind.OBJECT }
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

    if (functions.isEmpty() && classes.isEmpty() && genericClasses.isEmpty() && enums.isEmpty() && interfaces.isEmpty() && sealedClasses.isEmpty() && objects.isEmpty()) return emptyList()

    val sources: Array<KSFile> = (functions.mapNotNull { it.containingFile } +
      classes.mapNotNull { it.containingFile } +
      genericClasses.mapNotNull { it.containingFile } +
      enums.mapNotNull { it.containingFile } +
      interfaces.mapNotNull { it.containingFile } +
      sealedClasses.mapNotNull { it.containingFile } +
      objects.mapNotNull { it.containingFile }).toTypedArray()

    val deps = Dependencies(aggregating = true, *sources)

    generateCNameWrappers(functions, classes, genericClasses, enums, sealedClasses, objects, deps)
    generateCSharpBindings(functions, allClasses, enums, interfaces, sealedClasses, objects, deps)

    logger.info(
      "Generated bindings for ${functions.size} functions" +
        ", ${classes.size} classes" +
        ", ${genericClasses.size} generic classes" +
        ", ${enums.size} enums" +
        ", ${interfaces.size} interfaces" +
        ", ${sealedClasses.size} sealed classes" +
        ", and ${objects.size} objects"
    )

    return emptyList()
  }

  private fun generateCSharpBindings(
    functions: List<KSFunctionDeclaration>,
    classes: List<KSClassDeclaration>,
    enums: List<KSClassDeclaration>,
    interfaces: List<KSClassDeclaration>,
    sealedClasses: List<KSClassDeclaration>,
    objects: List<KSClassDeclaration>,
    deps: Dependencies,
  ) {
    val cirFile: CirFile = translator.translate(functions, classes, enums, interfaces, sealedClasses, objects)
    val csharp: String = renderer.render(cirFile)

    val file = codeGenerator.createNewFile(
      dependencies = deps,
      packageName = "",
      fileName = "Interop",
      extensionName = "cs",
    )

    file.writer().use { writer -> writer.write(csharp) }
  }

  private fun generateCNameWrappers(
    functions: List<KSFunctionDeclaration>,
    classes: List<KSClassDeclaration>,
    genericClasses: List<KSClassDeclaration>,
    enums: List<KSClassDeclaration>,
    sealedClasses: List<KSClassDeclaration>,
    objects: List<KSClassDeclaration>,
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
        functions.forEach { func ->
          addImport(func.packageName.asString(), func.simpleName.asString())
          addFunctionExports(func)
        }

        classes.forEach { addClassExports(it) }
        genericClasses.forEach { addGenericClassExports(it) }
        enums.forEach { addEnumExports(it) }
        sealedClasses.forEach { addSealedClassExports(it) }
        objects.forEach { addObjectExports(it) }

        if (genericClasses.isNotEmpty()) {
          addNugetHelperExports()
        }
      }
      .build()

    fileSpec.writeTo(codeGenerator, Dependencies(aggregating = true))
  }

}
