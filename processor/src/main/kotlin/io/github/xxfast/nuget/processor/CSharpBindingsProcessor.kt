package io.github.xxfast.nuget.processor

import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSValueParameter
import com.google.devtools.ksp.symbol.Visibility

private val KOTLIN_TO_CSHARP = mapOf(
  "String" to "string",
  "Byte" to "sbyte",
  "UByte" to "byte",
  "Short" to "short",
  "UShort" to "ushort",
  "Int" to "int",
  "UInt" to "uint",
  "Long" to "long",
  "ULong" to "ulong",
  "Float" to "float",
  "Double" to "double",
  "Boolean" to "bool",
  "Unit" to "void",
)

class CSharpBindingsProcessor(
  private val codeGenerator: CodeGenerator,
  private val logger: KSPLogger,
  private val libraryName: String,
  private val namespace: String,
  private val className: String,
) : SymbolProcessor {

  private var processed = false

  override fun process(resolver: Resolver): List<KSAnnotated> {
    if (processed) return emptyList()
    processed = true

    val functions: List<KSFunctionDeclaration> = resolver.getAllFiles()
      .flatMap { it.declarations }
      .filterIsInstance<KSFunctionDeclaration>()
      .filter { it.getVisibility() == Visibility.PUBLIC }
      .filter { it.parentDeclaration == null }
      .filter { it.packageName.asString() != "io.github.xxfast.nuget.generated" }
      .toList()

    if (functions.isEmpty()) return emptyList()

    val sources = functions.mapNotNull { it.containingFile }.toTypedArray()
    val deps = Dependencies(aggregating = true, *sources)

    generateCNameWrappers(functions, deps)
    generateCSharpBindings(functions, deps)

    logger.info("Generated bindings for ${functions.size} public functions")
    return emptyList()
  }

  private fun generateCNameWrappers(
    functions: List<KSFunctionDeclaration>,
    deps: Dependencies,
  ) {
    val file = codeGenerator.createNewFile(
      dependencies = deps,
      packageName = "io.github.xxfast.nuget.generated",
      fileName = "CNameExports",
    )

    file.writer().use { writer ->
      writer.write("@file:OptIn(ExperimentalNativeApi::class)\n")
      writer.write("package io.github.xxfast.nuget.generated\n\n")
      writer.write("import kotlin.experimental.ExperimentalNativeApi\n")

      val imports: MutableSet<String> = mutableSetOf()

      for (func in functions) {
        val packageName: String = func.packageName.asString()
        val funcName: String = func.simpleName.asString()
        imports.add("$packageName.$funcName")
      }

      for (import in imports) {
        writer.write("import $import\n")
      }

      writer.write("\n")

      for (func in functions) {
        val cname: String = toCName(func)
        val funcName: String = func.simpleName.asString()

        val params: List<KSValueParameter> = func.parameters
        val paramDecl: String = params.joinToString(", ") { param ->
          val type: String = param.type.resolve().declaration.qualifiedName?.asString()
            ?: param.type.resolve().declaration.simpleName.asString()

          "${param.name?.asString()}: $type"
        }

        val paramCall: String = params.joinToString(", ") { param ->
          param.name?.asString() ?: "_"
        }

        val returnType: String = func.returnType?.resolve()
          ?.declaration?.qualifiedName?.asString() ?: "Unit"

        if (returnType == "kotlin.Unit") {
          writer.write("@CName(\"$cname\")\n")
          writer.write("fun `export_$cname`($paramDecl) { $funcName($paramCall) }\n\n")
        } else {
          writer.write("@CName(\"$cname\")\n")
          writer.write("fun `export_$cname`($paramDecl): $returnType = $funcName($paramCall)\n\n")
        }
      }
    }
  }

  private fun generateCSharpBindings(
    functions: List<KSFunctionDeclaration>,
    deps: Dependencies,
  ) {
    val file = codeGenerator.createNewFile(
      dependencies = deps,
      packageName = "",
      fileName = "Interop",
      extensionName = "cs",
    )

    file.writer().use { writer ->
      writer.write("using System.Runtime.InteropServices;\n\n")
      writer.write("namespace $namespace\n{\n")
      writer.write("    public static partial class $className\n    {\n")

      for (func in functions) {
        val cname: String = toCName(func)

        val kotlinReturnType: String = func.returnType?.resolve()
          ?.declaration?.simpleName?.asString() ?: "Unit"

        val csharpReturnType: String = mapType(kotlinReturnType)
        val isStringReturn = kotlinReturnType == "String"

        val params: List<String> = func.parameters.map { param ->
          val kotlinType: String = param.type.resolve()
            .declaration.simpleName.asString()

          val csharpType: String = mapType(kotlinType)
          "$csharpType ${param.name?.asString()}"
        }

        val csName: String = toCSharpName(cname)
        val entryPoint: String = if (csName != cname) ", EntryPoint = \"$cname\"" else ""

        writer.write("        [DllImport(\"$libraryName\", CallingConvention = CallingConvention.Cdecl$entryPoint)]\n")

        if (isStringReturn) {
          writer.write("        [return: MarshalAs(UnmanagedType.LPUTF8Str)]\n")
          writer.write("        public static extern string $csName(${params.joinToString(", ")});\n")
        } else {
          writer.write("        public static extern $csharpReturnType $csName(${params.joinToString(", ")});\n")
        }

        writer.write("\n")
      }

      writer.write("    }\n}\n")
    }
  }

  private val C_RESERVED = setOf(
    "auto", "break", "case", "char", "const", "continue", "default", "do",
    "double", "else", "enum", "extern", "float", "for", "goto", "if",
    "int", "long", "register", "return", "short", "signed", "sizeof",
    "static", "struct", "switch", "typedef", "union", "unsigned", "void",
    "volatile", "while",
  )

  private val CSHARP_RESERVED = setOf(
    "abstract", "as", "base", "bool", "break", "byte", "case", "catch",
    "char", "checked", "class", "const", "continue", "decimal", "default",
    "delegate", "do", "double", "else", "enum", "event", "explicit",
    "extern", "false", "finally", "fixed", "float", "for", "foreach",
    "goto", "if", "implicit", "in", "int", "interface", "internal", "is",
    "lock", "long", "namespace", "new", "null", "object", "operator",
    "out", "override", "params", "private", "protected", "public",
    "readonly", "ref", "return", "sbyte", "sealed", "short", "sizeof",
    "stackalloc", "static", "string", "struct", "switch", "this", "throw",
    "true", "try", "typeof", "uint", "ulong", "unchecked", "unsafe",
    "ushort", "using", "virtual", "void", "volatile", "while",
  )

  private fun toCName(func: KSFunctionDeclaration): String {
    val name: String = func.simpleName.asString()
    if (name in C_RESERVED) return "${name}_"
    return name
  }

  private fun toCSharpName(cname: String): String {
    if (cname.trimEnd('_') in CSHARP_RESERVED) return "@$cname"
    return cname
  }

  private fun mapType(kotlinType: String): String =
    KOTLIN_TO_CSHARP[kotlinType] ?: "IntPtr"
}
