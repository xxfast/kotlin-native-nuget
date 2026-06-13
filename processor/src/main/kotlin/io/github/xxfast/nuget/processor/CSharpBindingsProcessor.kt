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
import com.google.devtools.ksp.symbol.Visibility
import io.github.xxfast.nuget.processor.cir.CirFile
import io.github.xxfast.nuget.processor.cir.CirRenderer
import io.github.xxfast.nuget.processor.cir.CirTranslator

private val C_RESERVED = setOf(
  "auto", "break", "case", "char", "const", "continue", "default", "do",
  "double", "else", "enum", "extern", "float", "for", "goto", "if",
  "int", "long", "register", "return", "short", "signed", "sizeof",
  "static", "struct", "switch", "typedef", "union", "unsigned", "void",
  "volatile", "while",
)

class CSharpBindingsProcessor(
  private val codeGenerator: CodeGenerator,
  private val logger: KSPLogger,
  private val libraryName: String,
  private val rootNamespace: String,
  private val rootPackage: String,
  private val className: String,
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

    if (functions.isEmpty() && classes.isEmpty()) return emptyList()

    val sources: Array<KSFile> = (functions.mapNotNull { it.containingFile } +
      classes.mapNotNull { it.containingFile }).toTypedArray()

    val deps = Dependencies(aggregating = true, *sources)

    generateCNameWrappers(functions, classes, deps)
    generateCSharpBindings(functions, classes, deps)

    logger.info(
      "Generated bindings for ${functions.size} functions" +
        " and ${classes.size} classes"
    )

    return emptyList()
  }

  private fun generateCSharpBindings(
    functions: List<KSFunctionDeclaration>,
    classes: List<KSClassDeclaration>,
    deps: Dependencies,
  ) {
    val cirFile: CirFile = translator.translate(functions, classes)
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
    deps: Dependencies,
  ) {
    val file = codeGenerator.createNewFile(
      dependencies = deps,
      packageName = "io.github.xxfast.nuget.generated",
      fileName = "CNameExports",
    )

    file.writer().use { writer ->
      writer.write(
        "@file:OptIn(ExperimentalNativeApi::class, ExperimentalForeignApi::class)\n"
      )
      writer.write("package io.github.xxfast.nuget.generated\n\n")
      writer.write("import kotlin.experimental.ExperimentalNativeApi\n")
      writer.write("import kotlinx.cinterop.ExperimentalForeignApi\n")
      writer.write("import kotlinx.cinterop.*\n")

      val imports: MutableSet<String> = mutableSetOf()

      for (func in functions) {
        imports.add("${func.packageName.asString()}.${func.simpleName.asString()}")
      }

      for (import in imports) {
        writer.write("import $import\n")
      }

      writer.write("\n")

      for (func in functions) {
        writeFunctionExport(writer, func)
      }

      for (cls in classes) {
        writeClassExports(writer, cls)
      }
    }
  }

  private fun writeFunctionExport(
    writer: java.io.Writer,
    func: KSFunctionDeclaration,
  ) {
    val cname: String = toCName(func)
    val funcName: String = func.simpleName.asString()
    val returnType: KSType? = func.returnType?.resolve()
    val isNullable: Boolean = returnType?.isMarkedNullable == true

    val params: List<KSValueParameter> = func.parameters
    val paramDecl: String = toParamDecl(params)
    val paramCall: String = toParamCall(params)

    val qualifiedReturn: String = returnType
      ?.declaration?.qualifiedName?.asString() ?: "Unit"

    if (isNullable) {
      writer.write("@CName(\"${cname}_has_value\")\n")
      writer.write(
        "fun `export_${cname}_has_value`($paramDecl): Boolean" +
          " = $funcName($paramCall) != null\n\n"
      )

      val valueReturn: String =
        if (qualifiedReturn == "kotlin.String") "String"
        else qualifiedReturn

      writer.write("@CName(\"${cname}_value\")\n")
      writer.write(
        "fun `export_${cname}_value`($paramDecl): $valueReturn" +
          " = $funcName($paramCall)!!\n\n"
      )
      return
    }

    if (qualifiedReturn == "kotlin.Unit") {
      writer.write("@CName(\"$cname\")\n")
      writer.write("fun `export_$cname`($paramDecl) { $funcName($paramCall) }\n\n")
      return
    }

    writer.write("@CName(\"$cname\")\n")
    writer.write(
      "fun `export_$cname`($paramDecl): $qualifiedReturn" +
        " = $funcName($paramCall)\n\n"
    )
  }

  private fun writeClassExports(
    writer: java.io.Writer,
    cls: KSClassDeclaration,
  ) {
    val name: String = cls.simpleName.asString()
    val qualifiedName: String = cls.qualifiedName?.asString() ?: return
    val prefix: String = name.lowercase()

    val constructor: KSFunctionDeclaration = cls.primaryConstructor ?: return
    val ctorParams: List<KSValueParameter> = constructor.parameters
    val ctorParamDecl: String = toParamDecl(ctorParams)
    val ctorParamCall: String = toParamCall(ctorParams)

    writer.write("@CName(\"${prefix}_create\")\n")
    writer.write(
      "fun `export_${prefix}_create`($ctorParamDecl): COpaquePointer =\n" +
        "    StableRef.create($qualifiedName($ctorParamCall)).asCPointer()\n\n"
    )

    writer.write("@CName(\"${prefix}_dispose\")\n")
    writer.write(
      "fun `export_${prefix}_dispose`(handle: COpaquePointer) {\n" +
        "    handle.asStableRef<$qualifiedName>().dispose()\n}\n\n"
    )

    val properties: List<KSPropertyDeclaration> = cls.getAllProperties()
      .filter { it.getVisibility() == Visibility.PUBLIC }
      .toList()

    for (prop in properties) {
      val propName: String = prop.simpleName.asString()
      val propType: String =
        prop.type.resolve().declaration.qualifiedName?.asString() ?: "Any"

      writer.write("@CName(\"${prefix}_get_$propName\")\n")
      writer.write(
        "fun `export_${prefix}_get_$propName`" +
          "(handle: COpaquePointer): $propType =\n" +
          "    handle.asStableRef<$qualifiedName>().get().$propName\n\n"
      )
    }

    val methods: List<KSFunctionDeclaration> = cls.getAllFunctions()
      .filter { it.getVisibility() == Visibility.PUBLIC }
      .filter {
        it.simpleName.asString() !in
          listOf("equals", "hashCode", "toString", "<init>")
      }
      .toList()

    for (method in methods) {
      val methodName: String = method.simpleName.asString()
      val methodReturn: String = method.returnType?.resolve()
        ?.declaration?.qualifiedName?.asString() ?: "Unit"

      val methodParams: List<KSValueParameter> = method.parameters

      val methodParamDecl: String = (listOf("handle: COpaquePointer") +
        methodParams.map { param ->
          val type: String =
            param.type.resolve().declaration.qualifiedName?.asString()
              ?: param.type.resolve().declaration.simpleName.asString()

          "${param.name?.asString()}: $type"
        }).joinToString(", ")

      val methodParamCall: String = methodParams.joinToString(", ") { param ->
        param.name?.asString() ?: "_"
      }

      if (methodReturn == "kotlin.Unit") {
        writer.write("@CName(\"${prefix}_$methodName\")\n")
        writer.write(
          "fun `export_${prefix}_$methodName`($methodParamDecl) {\n" +
            "    handle.asStableRef<$qualifiedName>()" +
            ".get().$methodName($methodParamCall)\n}\n\n"
        )
      } else {
        writer.write("@CName(\"${prefix}_$methodName\")\n")
        writer.write(
          "fun `export_${prefix}_$methodName`" +
            "($methodParamDecl): $methodReturn =\n" +
            "    handle.asStableRef<$qualifiedName>()" +
            ".get().$methodName($methodParamCall)\n\n"
        )
      }
    }
  }

  private fun toParamDecl(params: List<KSValueParameter>): String =
    params.joinToString(", ") { param ->
      val type: String = param.type.resolve().declaration.qualifiedName?.asString()
        ?: param.type.resolve().declaration.simpleName.asString()

      "${param.name?.asString()}: $type"
    }

  private fun toParamCall(params: List<KSValueParameter>): String =
    params.joinToString(", ") { param -> param.name?.asString() ?: "_" }

  private fun toCName(func: KSFunctionDeclaration): String {
    val name: String = func.simpleName.asString()
    if (name in C_RESERVED) return "${name}_"
    return name
  }
}
