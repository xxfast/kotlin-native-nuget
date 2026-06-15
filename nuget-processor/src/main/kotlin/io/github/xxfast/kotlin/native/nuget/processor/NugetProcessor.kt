package io.github.xxfast.kotlin.native.nuget.processor

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
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Visibility
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.ksp.writeTo
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirFile
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirRenderer
import io.github.xxfast.kotlin.native.nuget.processor.cir.NugetContext
import io.github.xxfast.kotlin.native.nuget.processor.cir.translate
import io.github.xxfast.kotlin.native.nuget.processor.exports.addClassExports
import io.github.xxfast.kotlin.native.nuget.processor.exports.addCompanionExports
import io.github.xxfast.kotlin.native.nuget.processor.exports.addEnumExports
import io.github.xxfast.kotlin.native.nuget.processor.exports.addFunctionExports
import io.github.xxfast.kotlin.native.nuget.processor.exports.addGenericClassExports
import io.github.xxfast.kotlin.native.nuget.processor.exports.addGenericFunctionExports
import io.github.xxfast.kotlin.native.nuget.processor.exports.addNugetHelperExports
import io.github.xxfast.kotlin.native.nuget.processor.exports.addNugetListHelperExports
import io.github.xxfast.kotlin.native.nuget.processor.exports.addNugetMapHelperExports
import io.github.xxfast.kotlin.native.nuget.processor.exports.addNugetSetHelperExports
import io.github.xxfast.kotlin.native.nuget.processor.exports.addNugetWrapHelperExports
import io.github.xxfast.kotlin.native.nuget.processor.exports.addNugetFunc0HelperExports
import io.github.xxfast.kotlin.native.nuget.processor.exports.addNugetFunc1HelperExports
import io.github.xxfast.kotlin.native.nuget.processor.exports.addNugetFunc2HelperExports
import io.github.xxfast.kotlin.native.nuget.processor.exports.addNugetFunc3HelperExports
import io.github.xxfast.kotlin.native.nuget.processor.exports.addObjectExports
import io.github.xxfast.kotlin.native.nuget.processor.exports.addPropertyExports
import io.github.xxfast.kotlin.native.nuget.processor.exports.addSealedClassExports

class NugetProcessor(
  private val codeGenerator: CodeGenerator,
  private val logger: KSPLogger,
  private val context: NugetContext,
) : SymbolProcessor {

  private var processed = false

  private val renderer = CirRenderer()

  override fun process(resolver: Resolver): List<KSAnnotated> {
    if (processed) return emptyList()
    processed = true

    val allDeclarations: List<KSDeclaration> = resolver.getAllFiles()
      .flatMap { it.declarations }
      .filter { it.packageName.asString() != "io.github.xxfast.kotlin.native.nuget.generated" }
      .toList()

    val allFunctions: List<KSFunctionDeclaration> = allDeclarations
      .filterIsInstance<KSFunctionDeclaration>()
      .filter { it.getVisibility() == Visibility.PUBLIC }
      .filter { it.parentDeclaration == null }

    val functions: List<KSFunctionDeclaration> = allFunctions.filter { it.typeParameters.isEmpty() }
    val genericFunctions: List<KSFunctionDeclaration> = allFunctions.filter { it.typeParameters.isNotEmpty() }

    val allProperties: List<KSPropertyDeclaration> = allDeclarations
      .filterIsInstance<KSPropertyDeclaration>()
      .filter { it.getVisibility() == Visibility.PUBLIC }
      .filter { it.parentDeclaration == null }

    val properties: List<KSPropertyDeclaration> = allProperties.filter { !it.modifiers.contains(Modifier.CONST) }
    val constProperties: List<KSPropertyDeclaration> = allProperties.filter { it.modifiers.contains(Modifier.CONST) }

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

    if (functions.isEmpty() && genericFunctions.isEmpty() && classes.isEmpty() && genericClasses.isEmpty() && enums.isEmpty() && interfaces.isEmpty() && sealedClasses.isEmpty() && objects.isEmpty() && properties.isEmpty() && constProperties.isEmpty()) return emptyList()

    val sources: Array<KSFile> = (functions.mapNotNull { it.containingFile } +
      genericFunctions.mapNotNull { it.containingFile } +
      classes.mapNotNull { it.containingFile } +
      genericClasses.mapNotNull { it.containingFile } +
      enums.mapNotNull { it.containingFile } +
      interfaces.mapNotNull { it.containingFile } +
      sealedClasses.mapNotNull { it.containingFile } +
      objects.mapNotNull { it.containingFile } +
      properties.mapNotNull { it.containingFile } +
      constProperties.mapNotNull { it.containingFile }).toTypedArray()

    val deps = Dependencies(aggregating = true, *sources)

    generateCNameWrappers(functions, genericFunctions, classes, genericClasses, enums, sealedClasses, objects, properties, deps)
    generateCSharpBindings(functions, genericFunctions, allClasses, enums, interfaces, sealedClasses, objects, properties, constProperties, deps)

    logger.info(
      "Generated bindings for ${functions.size} functions" +
        ", ${genericFunctions.size} generic functions" +
        ", ${classes.size} classes" +
        ", ${genericClasses.size} generic classes" +
        ", ${enums.size} enums" +
        ", ${interfaces.size} interfaces" +
        ", ${sealedClasses.size} sealed classes" +
        ", ${objects.size} objects" +
        ", ${properties.size} properties" +
        ", and ${constProperties.size} const properties"
    )

    return emptyList()
  }

  private fun generateCSharpBindings(
    functions: List<KSFunctionDeclaration>,
    genericFunctions: List<KSFunctionDeclaration>,
    classes: List<KSClassDeclaration>,
    enums: List<KSClassDeclaration>,
    interfaces: List<KSClassDeclaration>,
    sealedClasses: List<KSClassDeclaration>,
    objects: List<KSClassDeclaration>,
    properties: List<KSPropertyDeclaration>,
    constProperties: List<KSPropertyDeclaration>,
    deps: Dependencies,
  ) {
    val cirFile: CirFile = translate(
      context,
      logger,
      functions,
      genericFunctions,
      classes,
      enums,
      interfaces,
      sealedClasses,
      objects,
      properties,
      constProperties,
    )

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
    genericFunctions: List<KSFunctionDeclaration>,
    classes: List<KSClassDeclaration>,
    genericClasses: List<KSClassDeclaration>,
    enums: List<KSClassDeclaration>,
    sealedClasses: List<KSClassDeclaration>,
    objects: List<KSClassDeclaration>,
    properties: List<KSPropertyDeclaration>,
    deps: Dependencies,
  ) {
    val fileSpec: FileSpec = FileSpec
      .builder("io.github.xxfast.kotlin.native.nuget.generated", "CNameExports")
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

        genericFunctions.forEach { func ->
          addImport(func.packageName.asString(), func.simpleName.asString())
          addGenericFunctionExports(func)
        }

        classes.forEach { addClassExports(it) }
        classes.forEach { addCompanionExports(it) }
        genericClasses.forEach { addGenericClassExports(it) }
        enums.forEach { addEnumExports(it) }
        sealedClasses.forEach { addSealedClassExports(it) }
        objects.forEach { addObjectExports(it) }

        properties.forEach { prop ->
          addImport(prop.packageName.asString(), prop.simpleName.asString())
          addPropertyExports(prop)
        }

        val listTypes: Set<String> = setOf("kotlin.collections.List", "kotlin.collections.MutableList")

        fun KSType.isListType(): Boolean =
          declaration.qualifiedName?.asString() in listTypes

        val classesHaveLists: Boolean = (classes + genericClasses)
          .any { cls -> cls.getAllProperties().any { prop -> prop.type.resolve().isListType() } }

        val functionsReturnLists: Boolean = (functions + genericFunctions)
          .any { func -> func.returnType?.resolve()?.isListType() == true }

        val sealedClassesHaveLists: Boolean = sealedClasses
          .any { sealed -> sealed.getSealedSubclasses().any { sub ->
            sub.getAllProperties().any { prop -> prop.type.resolve().isListType() }
          } }

        val needsListSupport: Boolean = classesHaveLists || functionsReturnLists || sealedClassesHaveLists

        val mapTypes: Set<String> = setOf("kotlin.collections.Map", "kotlin.collections.MutableMap")

        fun KSType.isMapType(): Boolean =
          declaration.qualifiedName?.asString() in mapTypes

        val classesHaveMaps: Boolean = (classes + genericClasses)
          .any { cls -> cls.getAllProperties().any { prop -> prop.type.resolve().isMapType() } }

        val functionsReturnMaps: Boolean = (functions + genericFunctions)
          .any { func -> func.returnType?.resolve()?.isMapType() == true }

        val sealedClassesHaveMaps: Boolean = sealedClasses
          .any { sealed -> sealed.getSealedSubclasses().any { sub ->
            sub.getAllProperties().any { prop -> prop.type.resolve().isMapType() }
          } }

        val needsMapSupport: Boolean = classesHaveMaps || functionsReturnMaps || sealedClassesHaveMaps

        val setTypes: Set<String> = setOf("kotlin.collections.Set", "kotlin.collections.MutableSet")

        fun KSType.isSetType(): Boolean =
          declaration.qualifiedName?.asString() in setTypes

        val classesHaveSets: Boolean = (classes + genericClasses)
          .any { cls -> cls.getAllProperties().any { prop -> prop.type.resolve().isSetType() } }

        val functionsReturnSets: Boolean = (functions + genericFunctions)
          .any { func -> func.returnType?.resolve()?.isSetType() == true }

        val sealedClassesHaveSets: Boolean = sealedClasses
          .any { sealed -> sealed.getSealedSubclasses().any { sub ->
            sub.getAllProperties().any { prop -> prop.type.resolve().isSetType() }
          } }

        val needsSetSupport: Boolean = classesHaveSets || functionsReturnSets || sealedClassesHaveSets

        val lambdaTypes: Set<String> = setOf(
          "kotlin.Function0", "kotlin.Function1", "kotlin.Function2", "kotlin.Function3",
        )

        fun KSType.isLambdaType(): Boolean =
          declaration.qualifiedName?.asString() in lambdaTypes

        fun KSType.lambdaArity(): Int =
          arguments.size - 1

        val lambdaArities: MutableSet<Int> = mutableSetOf()

        (classes + genericClasses).forEach { cls ->
          cls.getAllProperties().forEach { prop ->
            val propType: KSType = prop.type.resolve()
            if (propType.isLambdaType()) lambdaArities.add(propType.lambdaArity())
          }
        }

        (functions + genericFunctions).forEach { func ->
          val returnType: KSType? = func.returnType?.resolve()
          if (returnType?.isLambdaType() == true) lambdaArities.add(returnType.lambdaArity())
        }

        sealedClasses.forEach { sealed ->
          sealed.getSealedSubclasses().forEach { sub ->
            sub.getAllProperties().forEach { prop ->
              val propType: KSType = prop.type.resolve()
              if (propType.isLambdaType()) lambdaArities.add(propType.lambdaArity())
            }
          }
        }

        val needsLambdaSupport: Boolean = lambdaArities.isNotEmpty()

        if (genericClasses.isNotEmpty() || needsListSupport || needsMapSupport || needsSetSupport || needsLambdaSupport) {
          addNugetHelperExports()
        }

        if (needsListSupport) {
          addNugetListHelperExports()
        }

        if (needsMapSupport) {
          addNugetMapHelperExports()
        }

        if (needsSetSupport) {
          addNugetSetHelperExports()
        }

        if (needsLambdaSupport && lambdaArities.any { it > 0 }) addNugetWrapHelperExports()
        if (0 in lambdaArities) addNugetFunc0HelperExports()
        if (1 in lambdaArities) addNugetFunc1HelperExports()
        if (2 in lambdaArities) addNugetFunc2HelperExports()
        if (3 in lambdaArities) addNugetFunc3HelperExports()
      }
      .build()

    fileSpec.writeTo(codeGenerator, Dependencies(aggregating = true))
  }

}
