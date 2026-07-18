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
import io.github.xxfast.kotlin.native.nuget.processor.cir.expandAliases
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
import io.github.xxfast.kotlin.native.nuget.processor.exports.addNugetSuspendFunc0HelperExports
import io.github.xxfast.kotlin.native.nuget.processor.exports.addNugetSuspendFunc1HelperExports
import io.github.xxfast.kotlin.native.nuget.processor.exports.addNugetScopeHelperExports
import io.github.xxfast.kotlin.native.nuget.processor.exports.addNugetScopeDrainExport
import io.github.xxfast.kotlin.native.nuget.processor.exports.addNugetJobHelperExports
import io.github.xxfast.kotlin.native.nuget.processor.exports.addNugetErrorHelperExports
import io.github.xxfast.kotlin.native.nuget.processor.exports.addStoredCallbackExports
import io.github.xxfast.kotlin.native.nuget.processor.exports.findStoredCallbackPairs
import io.github.xxfast.kotlin.native.nuget.processor.exports.findInterfaceBridgePairs
import io.github.xxfast.kotlin.native.nuget.processor.exports.addExtensionFunctionExports
import io.github.xxfast.kotlin.native.nuget.processor.exports.addExtensionPropertyExports
import io.github.xxfast.kotlin.native.nuget.processor.exports.addObjectExports
import io.github.xxfast.kotlin.native.nuget.processor.exports.addPropertyExports
import io.github.xxfast.kotlin.native.nuget.processor.exports.addSealedClassExports
import io.github.xxfast.kotlin.native.nuget.processor.exports.addSuspendClassMethodExports
import io.github.xxfast.kotlin.native.nuget.processor.exports.addSuspendFunctionExports
import io.github.xxfast.kotlin.native.nuget.processor.exports.addValueClassExports
import io.github.xxfast.kotlin.native.nuget.processor.forward.BridgeType
import io.github.xxfast.kotlin.native.nuget.processor.forward.CollectionKind
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardBridgeTypeContext
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardBridgeTypeClassifier
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardCallablePlan
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardCallablePlanCatalog
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardCallablePlanner
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardHelperRequirement
import io.github.xxfast.kotlin.native.nuget.processor.forward.addForwardKotlinPlanExport
import io.github.xxfast.kotlin.native.nuget.processor.forward.calls
import io.github.xxfast.kotlin.native.nuget.processor.forward.planFor

// A `@kotlin.native.CName`-annotated function is already a C-ABI export by definition (its native
// export name is fixed by the annotation itself). It must never be picked up by the forward
// exporter's own top-level-function scan and re-wrapped in another `export_*` C-ABI wrapper: the
// reverse-direction generator (`NugetGenerateBindingsTask`, ADR-048) emits exactly this shape for
// its registration functions — `@CName("nuget_..._register") public fun nuget_..._register(...)`
// — and those raw `COpaquePointer` parameters do not round-trip through this translator, which
// expects ordinary Kotlin-authored public API. Excluding any `@CName` function here is the general,
// robust fix: it protects against this exact class of bug for any future generated C export, not
// just this one registration function.
private fun KSAnnotated.hasCNameAnnotation(): Boolean =
  annotations.any { annotation ->
    val name: String? = annotation.annotationType.resolve().declaration.qualifiedName?.asString()
    name == "kotlin.native.CName"
  }

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
      .filter { it.extensionReceiver == null }
      .filter { !it.hasCNameAnnotation() }

    val extensionFunctions: List<KSFunctionDeclaration> = allDeclarations
      .filterIsInstance<KSFunctionDeclaration>()
      .filter { it.getVisibility() == Visibility.PUBLIC }
      .filter { it.parentDeclaration == null }
      .filter { it.extensionReceiver != null }
      .filter { !it.hasCNameAnnotation() }

    val (suspendFunctions, regularFunctions) = allFunctions
      .filter { it.typeParameters.isEmpty() }
      .partition { it.modifiers.contains(Modifier.SUSPEND) }

    val functions: List<KSFunctionDeclaration> = regularFunctions
    val genericFunctions: List<KSFunctionDeclaration> = allFunctions
      .filter { it.typeParameters.isNotEmpty() }

    val allProperties: List<KSPropertyDeclaration> = allDeclarations
      .filterIsInstance<KSPropertyDeclaration>()
      .filter { it.getVisibility() == Visibility.PUBLIC }
      .filter { it.parentDeclaration == null }
      .filter { it.extensionReceiver == null }

    val extensionProperties: List<KSPropertyDeclaration> = allDeclarations
      .filterIsInstance<KSPropertyDeclaration>()
      .filter { it.getVisibility() == Visibility.PUBLIC }
      .filter { it.parentDeclaration == null }
      .filter { it.extensionReceiver != null }

    val properties: List<KSPropertyDeclaration> = allProperties
      .filter { !it.modifiers.contains(Modifier.CONST) }
    val constProperties: List<KSPropertyDeclaration> = allProperties
      .filter { it.modifiers.contains(Modifier.CONST) }

    val allClasses: List<KSClassDeclaration> = allDeclarations
      .filterIsInstance<KSClassDeclaration>()
      .filter { it.getVisibility() == Visibility.PUBLIC }
      .filter { it.classKind == ClassKind.CLASS }
      .filter { it.parentDeclaration == null }
      .filter { !it.modifiers.contains(Modifier.SEALED) }
      .filter { !it.modifiers.contains(Modifier.VALUE) }

    val valueClasses: List<KSClassDeclaration> = allDeclarations
      .filterIsInstance<KSClassDeclaration>()
      .filter { it.getVisibility() == Visibility.PUBLIC }
      .filter { it.classKind == ClassKind.CLASS }
      .filter { it.parentDeclaration == null }
      .filter { it.modifiers.contains(Modifier.VALUE) }

    val classes: List<KSClassDeclaration> = allClasses.filter { it.typeParameters.isEmpty() }
    val genericClasses: List<KSClassDeclaration> = allClasses
      .filter { it.typeParameters.isNotEmpty() }

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

    val hasNothingToProcess: Boolean = functions.isEmpty() && genericFunctions.isEmpty() &&
        extensionFunctions.isEmpty() && extensionProperties.isEmpty() &&
        classes.isEmpty() && genericClasses.isEmpty() && enums.isEmpty() &&
        interfaces.isEmpty() && sealedClasses.isEmpty() && objects.isEmpty() &&
        properties.isEmpty() && constProperties.isEmpty() && valueClasses.isEmpty()
    if (hasNothingToProcess) return emptyList()

    val sources: Array<KSFile> = (functions.mapNotNull { it.containingFile } +
        genericFunctions.mapNotNull { it.containingFile } +
        extensionFunctions.mapNotNull { it.containingFile } +
        extensionProperties.mapNotNull { it.containingFile } +
        classes.mapNotNull { it.containingFile } +
        genericClasses.mapNotNull { it.containingFile } +
        enums.mapNotNull { it.containingFile } +
        interfaces.mapNotNull { it.containingFile } +
        sealedClasses.mapNotNull { it.containingFile } +
        objects.mapNotNull { it.containingFile } +
        properties.mapNotNull { it.containingFile } +
        constProperties.mapNotNull { it.containingFile } +
        valueClasses.mapNotNull { it.containingFile }).toTypedArray()

    val deps = Dependencies(aggregating = true, *sources)

    // Phase 2 shadow migration: construct the source-neutral catalog once while KSP symbols are
    // still available, before either legacy emitter runs. It remains observational for now.
    val exportedObjectHandles: Set<String> = buildSet {
      allClasses.forEach { cls -> cls.qualifiedName?.asString()?.let(::add) }
      enums.forEach { enum -> enum.qualifiedName?.asString()?.let(::add) }
      interfaces.forEach { iface -> iface.qualifiedName?.asString()?.let(::add) }
      sealedClasses.forEach { sealed ->
        sealed.qualifiedName?.asString()?.let(::add)
        sealed.getSealedSubclasses().forEach { subclass ->
          subclass.qualifiedName?.asString()?.let(::add)
        }
      }
      objects.forEach { obj -> obj.qualifiedName?.asString()?.let(::add) }
    }
    val callableCatalog: ForwardCallablePlanCatalog = ForwardCallablePlanner(
      ForwardBridgeTypeClassifier(
        ForwardBridgeTypeContext(
          exportedObjectHandles = exportedObjectHandles,
          rootPackage = context.rootPackage,
          rootNamespace = context.rootNamespace,
        ),
      ),
    ).catalog(
      classes, functions, extensionFunctions, objects, properties, extensionProperties, valueClasses,
    )

    val cNameExports: FileSpec = generateCNameWrappers(
      functions, genericFunctions, extensionFunctions, extensionProperties,
      classes, genericClasses, enums, sealedClasses, objects, properties,
      valueClasses, suspendFunctions, callableCatalog, deps,
    )
    val cirFile: CirFile = generateCSharpBindings(
      functions, genericFunctions, extensionFunctions, extensionProperties,
      allClasses, enums, interfaces, sealedClasses, objects, properties,
      constProperties, valueClasses, suspendFunctions, callableCatalog, deps,
    )

    val csharpContracts: List<ForwardAbiSignature> = ForwardAbiContract.csharp(cirFile)
    ForwardAbiContract.assertMatches(
      csharp = csharpContracts,
      kotlin = ForwardAbiContract.kotlin(cNameExports, csharpContracts.map { it.exportName }.toSet()),
    )
    ForwardAbiContract.assertMatchesPlan(
      catalog = callableCatalog,
      csharp = csharpContracts,
      kotlin = ForwardAbiContract.kotlin(
        cNameExports,
        (
            callableCatalog.plans.flatMap { plan -> plan.nativeExports.map { call -> call.exportName } } +
                callableCatalog.propertyPlans.flatMap { plan -> plan.calls().map { call -> call.exportName } }
            ).toSet(),
      ),
    )
    cNameExports.writeTo(codeGenerator, Dependencies(aggregating = true))

    logger.info(
      "Generated bindings for ${functions.size} functions" +
          ", ${genericFunctions.size} generic functions" +
          ", ${extensionFunctions.size} extension functions" +
          ", ${extensionProperties.size} extension properties" +
          ", ${classes.size} classes" +
          ", ${genericClasses.size} generic classes" +
          ", ${enums.size} enums" +
          ", ${interfaces.size} interfaces" +
          ", ${sealedClasses.size} sealed classes" +
          ", ${objects.size} objects" +
          ", ${properties.size} properties" +
          ", ${constProperties.size} const properties" +
          ", and ${valueClasses.size} value classes"
    )

    return emptyList()
  }

  private fun generateCSharpBindings(
    functions: List<KSFunctionDeclaration>,
    genericFunctions: List<KSFunctionDeclaration>,
    extensionFunctions: List<KSFunctionDeclaration>,
    extensionProperties: List<KSPropertyDeclaration>,
    classes: List<KSClassDeclaration>,
    enums: List<KSClassDeclaration>,
    interfaces: List<KSClassDeclaration>,
    sealedClasses: List<KSClassDeclaration>,
    objects: List<KSClassDeclaration>,
    properties: List<KSPropertyDeclaration>,
    constProperties: List<KSPropertyDeclaration>,
    valueClasses: List<KSClassDeclaration>,
    suspendFunctions: List<KSFunctionDeclaration>,
    callableCatalog: ForwardCallablePlanCatalog,
    deps: Dependencies,
  ): CirFile {
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
      extensionFunctions,
      extensionProperties,
      valueClasses,
      suspendFunctions,
      callableCatalog,
    )

    val csharp: String = renderer.render(cirFile)

    val file = codeGenerator.createNewFile(
      dependencies = deps,
      packageName = "",
      fileName = "Interop",
      extensionName = "cs",
    )

    file.writer().use { writer -> writer.write(csharp) }
    return cirFile
  }

  private fun generateCNameWrappers(
    functions: List<KSFunctionDeclaration>,
    genericFunctions: List<KSFunctionDeclaration>,
    extensionFunctions: List<KSFunctionDeclaration>,
    extensionProperties: List<KSPropertyDeclaration>,
    classes: List<KSClassDeclaration>,
    genericClasses: List<KSClassDeclaration>,
    enums: List<KSClassDeclaration>,
    sealedClasses: List<KSClassDeclaration>,
    objects: List<KSClassDeclaration>,
    properties: List<KSPropertyDeclaration>,
    valueClasses: List<KSClassDeclaration>,
    suspendFunctions: List<KSFunctionDeclaration>,
    callableCatalog: ForwardCallablePlanCatalog,
    deps: Dependencies,
  ): FileSpec {
    val builder: FileSpec.Builder = FileSpec
      .builder("io.github.xxfast.kotlin.native.nuget.generated", "CNameExports")
      .addAnnotation(
        AnnotationSpec.builder(ClassName("kotlin", "OptIn"))
          .addMember(
            "%T::class, %T::class, %T::class",
            ClassName("kotlin.experimental", "ExperimentalNativeApi"),
            ClassName("kotlinx.cinterop", "ExperimentalForeignApi"),
            ClassName("kotlinx.coroutines", "ExperimentalCoroutinesApi"),
          )
          .build()
      )
      .addImport("kotlinx.cinterop", "asStableRef")
      .addImport("kotlinx.cinterop", "COpaquePointerVar")
      .addImport("kotlinx.cinterop", "reinterpret")
      .addImport("kotlinx.cinterop", "pointed")
      .addImport("kotlinx.cinterop", "value")
      .addImport("kotlinx.cinterop", "StableRef")

    functions.forEach { func ->
      builder.addImport(func.packageName.asString(), func.simpleName.asString())
      val symbol: String = "${func.packageName.asString()}.${func.simpleName.asString()}"
      val planned = callableCatalog.planFor(symbol)
      if (planned != null) builder.addForwardKotlinPlanExport(planned)
      else builder.addFunctionExports(func)
    }

    genericFunctions.forEach { func ->
      builder.addImport(func.packageName.asString(), func.simpleName.asString())
      builder.addGenericFunctionExports(func)
    }

    classes.forEach { builder.addClassExports(it, callableCatalog) }
    classes.forEach { builder.addCompanionExports(it, callableCatalog) }
    genericClasses.forEach { builder.addGenericClassExports(it) }
    enums.forEach { builder.addEnumExports(it) }
    sealedClasses.forEach { builder.addSealedClassExports(it) }
    objects.forEach { builder.addObjectExports(it, callableCatalog) }
    valueClasses.forEach { builder.addValueClassExports(it, callableCatalog) }

    val suspendLambdaTypes: Set<String> = setOf(
      "kotlin.coroutines.SuspendFunction0",
      "kotlin.coroutines.SuspendFunction1",
      "kotlin.coroutines.SuspendFunction2",
      "kotlin.coroutines.SuspendFunction3",
    )

    fun KSType.isSuspendLambdaType(): Boolean =
      expandAliases().declaration.qualifiedName?.asString() in suspendLambdaTypes

    fun KSType.suspendLambdaArity(): Int =
      expandAliases().arguments.size - 1

    val suspendLambdaArities: MutableSet<Int> = mutableSetOf()

    (classes + genericClasses).forEach { cls ->
      cls.getAllProperties().forEach { prop ->
        val propType: KSType = prop.type.resolve()
        if (propType.isSuspendLambdaType()) suspendLambdaArities.add(propType.suspendLambdaArity())
      }
    }

    val needsSuspendLambdaSupport: Boolean = suspendLambdaArities.isNotEmpty()

    val hasSuspendFunctions: Boolean = suspendFunctions.isNotEmpty() ||
        needsSuspendLambdaSupport ||
        classes.any { cls -> cls.getAllFunctions().any { it.modifiers.contains(Modifier.SUSPEND) } }

    val classesHaveFlowPropertiesForImports: Boolean = classes.any { cls ->
      cls.getAllProperties().any { prop ->
        prop.type.resolve().expandAliases().declaration.qualifiedName?.asString() ==
            "kotlinx.coroutines.flow.Flow"
      }
    }

    val classesHaveFlowMethodsForImports: Boolean = classes.any { cls ->
      cls.getAllFunctions().any { method ->
        method.returnType?.resolve()?.expandAliases()?.declaration?.qualifiedName?.asString() ==
            "kotlinx.coroutines.flow.Flow"
      }
    }

    val needsFlowImports: Boolean = classesHaveFlowPropertiesForImports ||
        classesHaveFlowMethodsForImports

    val lambdaTypeSet: Set<String> = setOf(
      "kotlin.Function0", "kotlin.Function1", "kotlin.Function2", "kotlin.Function3",
    )

    val hasLambdaParamMethods: Boolean = classes.any { cls ->
      cls.getAllFunctions().any { method ->
        method.parameters.any { param ->
          param.type.resolve().expandAliases().declaration.qualifiedName?.asString() in lambdaTypeSet
        }
      }
    }

    // Stored-callback pairs also need invoke/CFunction/COpaquePointer (the bridge lambda calls fn.invoke).
    val hasStoredCallbackMethods: Boolean = classes.any { cls ->
      val lambdaParamMethods: List<KSFunctionDeclaration> = cls.getAllFunctions()
        .filter { method ->
          method.parameters.any { param ->
            param.type.resolve().expandAliases().declaration.qualifiedName?.asString() in lambdaTypeSet
          }
        }.toList()
      findStoredCallbackPairs(lambdaParamMethods).isNotEmpty()
    }

    // Interface-bridge pairs also need invoke/CFunction/COpaquePointer (each method's fn.invoke).
    val hasInterfaceBridgeMethods: Boolean = classes.any { cls ->
      val allMethods: List<KSFunctionDeclaration> = cls.getAllFunctions().toList()
      findInterfaceBridgePairs(allMethods).isNotEmpty()
    }

    val needsCallbackImports: Boolean =
      hasLambdaParamMethods || hasStoredCallbackMethods || hasInterfaceBridgeMethods
    if (needsCallbackImports && !hasSuspendFunctions && !needsFlowImports) {
      builder.addImport("kotlinx.cinterop", "invoke")
      builder.addImport("kotlinx.cinterop", "CFunction")
      builder.addImport("kotlinx.cinterop", "COpaquePointer")
    }

    if (hasSuspendFunctions || needsFlowImports) {
      builder.addImport("kotlinx.cinterop", "reinterpret")
      builder.addImport("kotlinx.cinterop", "invoke")
      builder.addImport("kotlinx.cinterop", "CFunction")
      builder.addImport("kotlinx.cinterop", "COpaquePointer")
      builder.addImport("kotlinx.cinterop", "StableRef")
      builder.addImport("kotlinx.coroutines", "CoroutineScope")
      builder.addImport("kotlinx.coroutines", "CoroutineStart")
      builder.addImport("kotlinx.coroutines", "Dispatchers")
      builder.addImport("kotlinx.coroutines", "launch")
      builder.addImport("kotlinx.coroutines", "SupervisorJob")
      builder.addImport("kotlinx.coroutines", "cancel")
      builder.addImport("kotlinx.coroutines", "CancellationException")
      builder.addImport("kotlinx.coroutines", "ExperimentalCoroutinesApi")
    }

    if (needsSuspendLambdaSupport) {
      builder.addImport("kotlin.coroutines", "SuspendFunction0")
      builder.addImport("kotlin.coroutines", "SuspendFunction1")
    }

    suspendFunctions.forEach { func ->
      builder.addImport(func.packageName.asString(), func.simpleName.asString())
      builder.addSuspendFunctionExports(func)
    }

    classes.forEach { cls ->
      val hasSuspendMethods: Boolean = cls.getAllFunctions()
        .any { it.modifiers.contains(Modifier.SUSPEND) }
      if (hasSuspendMethods) builder.addSuspendClassMethodExports(cls)
    }

    properties.forEach { prop ->
      builder.addImport(prop.packageName.asString(), prop.simpleName.asString())
      builder.addPropertyExports(prop, callableCatalog)
    }

    extensionFunctions.forEach { func ->
      builder.addImport(func.packageName.asString(), func.simpleName.asString())
      builder.addExtensionFunctionExports(func, callableCatalog)
    }

    extensionProperties.forEach { prop ->
      builder.addImport(prop.packageName.asString(), prop.simpleName.asString())
      builder.addExtensionPropertyExports(prop, callableCatalog)
    }

    val listTypes: Set<String> = setOf("kotlin.collections.List", "kotlin.collections.MutableList")

    fun KSType.isListType(): Boolean =
      expandAliases().declaration.qualifiedName?.asString() in listTypes

    val classesHaveLists: Boolean = (classes + genericClasses)
      .any { cls -> cls.getAllProperties().any { prop -> prop.type.resolve().isListType() } }

    val functionsReturnLists: Boolean = (functions + genericFunctions)
      .any { func -> func.returnType?.resolve()?.isListType() == true }

    val sealedClassesHaveLists: Boolean = sealedClasses
      .any { sealed ->
        sealed.getSealedSubclasses().any { sub ->
          sub.getAllProperties().any { prop -> prop.type.resolve().isListType() }
        }
      }

    // ADR-061: class-method and extension-function returns are a distinct position the property/
    // top-level-function scans above never covered — widen the gate or the shared NugetListNative
    // helper/exports a List-returning method boxes its handle for are never emitted.
    val classMethodsReturnLists: Boolean = classes
      .any { cls ->
        val methodsReturnLists: Boolean = cls.getAllFunctions()
          .any { method ->
            val symbol: String = "${cls.qualifiedName?.asString()}.${method.simpleName.asString()}"
            callableCatalog.planFor(symbol) == null && method.returnType?.resolve()?.isListType() == true
          }
        val companionMethodsReturnLists: Boolean = cls.declarations
          .filterIsInstance<KSClassDeclaration>()
          .filter { declaration -> declaration.isCompanionObject }
          .flatMap { companion -> companion.getAllFunctions() }
          .any { method -> method.returnType?.resolve()?.isListType() == true }
        methodsReturnLists || companionMethodsReturnLists
      }

    val extensionFunctionsReturnLists: Boolean = extensionFunctions
      .any { func ->
        val symbol: String = "${func.packageName.asString()}.${func.simpleName.asString()}"
        callableCatalog.planFor(symbol) == null && func.returnType?.resolve()?.isListType() == true
      }

    fun BridgeType.collectionKindOrNull(): CollectionKind? {
      val unwrapped: BridgeType = if (this is BridgeType.Nullable) type else this
      return (unwrapped as? BridgeType.Collection)?.kind
    }

    fun ForwardCallablePlan.collectionKinds(): Sequence<CollectionKind> = sequence {
      publicSignature.result.collectionKindOrNull()?.let { yield(it) }
      publicSignature.parameters.forEach { parameter ->
        parameter.type.collectionKindOrNull()?.let { yield(it) }
      }
    }

    fun plannedCollectionKinds(): Sequence<CollectionKind> = sequence {
      callableCatalog.plans.forEach { plan -> yieldAll(plan.collectionKinds()) }
      callableCatalog.propertyPlans.forEach { plan ->
        plan.type.collectionKindOrNull()?.let { yield(it) }
      }
    }

    val needsListSupport: Boolean = classesHaveLists || functionsReturnLists ||
        sealedClassesHaveLists || classMethodsReturnLists || extensionFunctionsReturnLists ||
        plannedCollectionKinds().any { kind ->
          kind == CollectionKind.LIST || kind == CollectionKind.MUTABLE_LIST
        }

    val mapTypes: Set<String> = setOf("kotlin.collections.Map", "kotlin.collections.MutableMap")

    fun KSType.isMapType(): Boolean =
      expandAliases().declaration.qualifiedName?.asString() in mapTypes

    val classesHaveMaps: Boolean = (classes + genericClasses)
      .any { cls -> cls.getAllProperties().any { prop -> prop.type.resolve().isMapType() } }

    val functionsReturnMaps: Boolean = (functions + genericFunctions)
      .any { func ->
        val symbol: String = "${func.packageName.asString()}.${func.simpleName.asString()}"
        callableCatalog.planFor(symbol) == null && func.returnType?.resolve()?.isMapType() == true
      }

    val sealedClassesHaveMaps: Boolean = sealedClasses
      .any { sealed ->
        sealed.getSealedSubclasses().any { sub ->
          sub.getAllProperties().any { prop -> prop.type.resolve().isMapType() }
        }
      }

    val needsMapSupport: Boolean = classesHaveMaps || functionsReturnMaps || sealedClassesHaveMaps ||
        plannedCollectionKinds().any { kind ->
          kind == CollectionKind.MAP || kind == CollectionKind.MUTABLE_MAP
        }

    val setTypes: Set<String> = setOf("kotlin.collections.Set", "kotlin.collections.MutableSet")

    fun KSType.isSetType(): Boolean =
      expandAliases().declaration.qualifiedName?.asString() in setTypes

    val classesHaveSets: Boolean = (classes + genericClasses)
      .any { cls -> cls.getAllProperties().any { prop -> prop.type.resolve().isSetType() } }

    val functionsReturnSets: Boolean = (functions + genericFunctions)
      .any { func ->
        val symbol: String = "${func.packageName.asString()}.${func.simpleName.asString()}"
        callableCatalog.planFor(symbol) == null && func.returnType?.resolve()?.isSetType() == true
      }

    val sealedClassesHaveSets: Boolean = sealedClasses
      .any { sealed ->
        sealed.getSealedSubclasses().any { sub ->
          sub.getAllProperties().any { prop -> prop.type.resolve().isSetType() }
        }
      }

    val needsSetSupport: Boolean = classesHaveSets || functionsReturnSets || sealedClassesHaveSets ||
        plannedCollectionKinds().any { kind ->
          kind == CollectionKind.SET || kind == CollectionKind.MUTABLE_SET
        }

    val lambdaTypes: Set<String> = setOf(
      "kotlin.Function0", "kotlin.Function1", "kotlin.Function2", "kotlin.Function3",
    )

    fun KSType.isLambdaType(): Boolean =
      expandAliases().declaration.qualifiedName?.asString() in lambdaTypes

    fun KSType.lambdaArity(): Int =
      expandAliases().arguments.size - 1

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

    val needsHelpers: Boolean = genericClasses.isNotEmpty() ||
        needsListSupport || needsMapSupport || needsSetSupport || needsLambdaSupport
    if (needsHelpers) builder.addNugetHelperExports()
    if (needsListSupport) builder.addNugetListHelperExports()
    if (needsMapSupport) builder.addNugetMapHelperExports()
    if (needsSetSupport) builder.addNugetSetHelperExports()

    // A collection *parameter* (Phase 7) needs `nuget_wrap_*` too: `NugetMarshal.CreateList<T>`
    // boxes each primitive/String element through the matching wrap export before handing it to
    // `nuget_list_add`. Computed once so lambda/suspend-lambda support sharing the same gate below
    // never emits the wrap exports twice.
    val needsCollectionParamWrap: Boolean = callableCatalog.plans.any { plan ->
      ForwardHelperRequirement.COLLECTION in plan.helperRequirements
    }
    var wrapHelpersEmitted = false
    fun addNugetWrapHelperExportsOnce() {
      if (wrapHelpersEmitted) return
      wrapHelpersEmitted = true
      builder.addNugetWrapHelperExports()
    }

    if ((needsLambdaSupport && lambdaArities.any { it > 0 }) || needsCollectionParamWrap) {
      addNugetWrapHelperExportsOnce()
    }
    if (0 in lambdaArities) builder.addNugetFunc0HelperExports()
    if (1 in lambdaArities) builder.addNugetFunc1HelperExports()
    if (2 in lambdaArities) builder.addNugetFunc2HelperExports()
    if (3 in lambdaArities) builder.addNugetFunc3HelperExports()

    val needsSuspendWrap: Boolean = needsSuspendLambdaSupport &&
        suspendLambdaArities.any { it > 0 } && !needsLambdaSupport
    if (needsSuspendWrap) addNugetWrapHelperExportsOnce()
    if (0 in suspendLambdaArities) builder.addNugetSuspendFunc0HelperExports()
    if (1 in suspendLambdaArities) builder.addNugetSuspendFunc1HelperExports()

    val classesHaveSuspendMethods: Boolean = classes.any { cls ->
      cls.getAllFunctions().any { it.modifiers.contains(Modifier.SUSPEND) }
    }

    val flowTypes: Set<String> = setOf("kotlinx.coroutines.flow.Flow")

    fun KSType.isFlowType(): Boolean =
      expandAliases().declaration.qualifiedName?.asString() in flowTypes

    val classesHaveFlowProperties: Boolean = classes.any { cls ->
      cls.getAllProperties().any { prop -> prop.type.resolve().isFlowType() }
    }

    val classesHaveFlowMethods: Boolean = classes.any { cls ->
      cls.getAllFunctions().any { method ->
        method.returnType?.resolve()?.isFlowType() == true
      }
    }

    val needsFlowSupport: Boolean = classesHaveFlowProperties || classesHaveFlowMethods

    if (needsFlowSupport) builder.addImport("kotlinx.coroutines.flow", "collect")

    val needsScopeHelpers: Boolean = suspendFunctions.isNotEmpty() ||
        needsSuspendLambdaSupport || classesHaveSuspendMethods || needsFlowSupport
    if (needsScopeHelpers) builder.addNugetScopeHelperExports()
    if (needsScopeHelpers) builder.addNugetScopeDrainExport()
    if (needsScopeHelpers) builder.addNugetJobHelperExports()
    builder.addNugetErrorHelperExports()

    return builder.build()
  }

}
