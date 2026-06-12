package io.github.xxfast.nuget.processor

import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSValueParameter
import com.google.devtools.ksp.symbol.Visibility

private val KOTLIN_TO_CSHARP = mapOf(
  "String" to "IntPtr",
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
  private val rootNamespace: String,
  private val rootPackage: String,
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
        val returnType: KSType? = func.returnType?.resolve()
        val isNullable: Boolean = returnType?.isMarkedNullable == true

        val params: List<KSValueParameter> = func.parameters
        val paramDecl: String = params.joinToString(", ") { param ->
          val type: String = param.type.resolve().declaration.qualifiedName?.asString()
            ?: param.type.resolve().declaration.simpleName.asString()

          "${param.name?.asString()}: $type"
        }

        val paramCall: String = params.joinToString(", ") { param ->
          param.name?.asString() ?: "_"
        }

        val qualifiedReturn: String = returnType
          ?.declaration?.qualifiedName?.asString() ?: "Unit"

        if (isNullable) {
          writer.write("@CName(\"${cname}_has_value\")\n")
          writer.write("fun `export_${cname}_has_value`($paramDecl): Boolean = $funcName($paramCall) != null\n\n")

          if (qualifiedReturn == "kotlin.String") {
            writer.write("@CName(\"${cname}_value\")\n")
            writer.write("fun `export_${cname}_value`($paramDecl): String = $funcName($paramCall)!!\n\n")
          } else {
            writer.write("@CName(\"${cname}_value\")\n")
            writer.write("fun `export_${cname}_value`($paramDecl): $qualifiedReturn = $funcName($paramCall)!!\n\n")
          }
        } else if (qualifiedReturn == "kotlin.Unit") {
          writer.write("@CName(\"$cname\")\n")
          writer.write("fun `export_$cname`($paramDecl) { $funcName($paramCall) }\n\n")
        } else {
          writer.write("@CName(\"$cname\")\n")
          writer.write("fun `export_$cname`($paramDecl): $qualifiedReturn = $funcName($paramCall)\n\n")
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

    val grouped: Map<String, List<KSFunctionDeclaration>> = functions
      .groupBy { mapPackageToNamespace(it.packageName.asString()) }

    file.writer().use { writer ->
      writer.write("using System;\n")
      writer.write("using System.Runtime.InteropServices;\n\n")

      for ((namespace, funcs) in grouped) {
        writer.write("namespace $namespace\n{\n")
        writer.write("    public static partial class $className\n    {\n")

        for (func in funcs) {
          writeFunction(writer, func)
        }

        writer.write("    }\n}\n\n")
      }
    }
  }

  private fun writeFunction(writer: java.io.Writer, func: KSFunctionDeclaration) {
    val cname: String = toCName(func)
    val returnType: KSType? = func.returnType?.resolve()
    val isNullable: Boolean = returnType?.isMarkedNullable == true
    val kotlinReturnType: String = returnType
      ?.declaration?.simpleName?.asString() ?: "Unit"

    val csharpReturnType: String = mapType(kotlinReturnType)

    val params: List<String> = func.parameters.map { param ->
      val kotlinType: String = param.type.resolve()
        .declaration.simpleName.asString()

      val csharpType: String = mapType(kotlinType)
      "$csharpType ${param.name?.asString()}"
    }

    val paramStr: String = params.joinToString(", ")

    if (isNullable) {
      val csName: String = toCSharpName(cname)
      val hasValueName = "${cname}_has_value"
      val valueName = "${cname}_value"

      writer.write("        [DllImport(\"$libraryName\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"$hasValueName\")]\n")
      writer.write("        private static extern bool ${csName}_has_value($paramStr);\n\n")

      writer.write("        [DllImport(\"$libraryName\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"$valueName\")]\n")
      writer.write("        private static extern $csharpReturnType ${csName}_value($paramStr);\n\n")

      val paramNames: String = func.parameters.joinToString(", ") { param ->
        param.name?.asString() ?: "_"
      }

      if (kotlinReturnType == "String") {
        writer.write("        public static string? $csName($paramStr)\n")
        writer.write("        {\n")
        writer.write("            if (!${csName}_has_value($paramNames)) return null;\n")
        writer.write("            return Marshal.PtrToStringUTF8(${csName}_value($paramNames));\n")
        writer.write("        }\n\n")
      } else {
        writer.write("        public static $csharpReturnType? $csName($paramStr)\n")
        writer.write("        {\n")
        writer.write("            if (!${csName}_has_value($paramNames)) return null;\n")
        writer.write("            return ${csName}_value($paramNames);\n")
        writer.write("        }\n\n")
      }
    } else if (kotlinReturnType == "String") {
      val csName: String = toCSharpName(cname)
      val entryPoint: String = if (csName != cname) ", EntryPoint = \"$cname\"" else ""

      writer.write("        [DllImport(\"$libraryName\", CallingConvention = CallingConvention.Cdecl$entryPoint)]\n")
      writer.write("        private static extern IntPtr ${csName}_native($paramStr);\n\n")

      val paramNames: String = func.parameters.joinToString(", ") { param ->
        param.name?.asString() ?: "_"
      }

      writer.write("        public static string $csName($paramStr)\n")
      writer.write("            => Marshal.PtrToStringUTF8(${csName}_native($paramNames))!;\n\n")
    } else {
      val csName: String = toCSharpName(cname)
      val entryPoint: String = if (csName != cname) ", EntryPoint = \"$cname\"" else ""

      writer.write("        [DllImport(\"$libraryName\", CallingConvention = CallingConvention.Cdecl$entryPoint)]\n")
      writer.write("        public static extern $csharpReturnType $csName($paramStr);\n\n")
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

  private fun mapPackageToNamespace(kotlinPackage: String): String {
    if (rootPackage.isEmpty()) return rootNamespace

    val relative: String = if (kotlinPackage.startsWith(rootPackage)) {
      kotlinPackage.removePrefix(rootPackage).removePrefix(".")
    } else {
      kotlinPackage
    }

    if (relative.isEmpty()) return rootNamespace

    val suffix: String = relative.split(".")
      .joinToString(".") { segment ->
        segment.replaceFirstChar { it.uppercase() }
      }

    return "$rootNamespace.$suffix"
  }
}
