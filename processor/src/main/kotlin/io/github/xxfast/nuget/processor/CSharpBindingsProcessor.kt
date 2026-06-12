package io.github.xxfast.nuget.processor

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSFunctionDeclaration

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

  override fun process(resolver: Resolver): List<KSAnnotated> {
    val functions: List<KSFunctionDeclaration> = resolver
      .getSymbolsWithAnnotation("kotlin.native.CName")
      .filterIsInstance<KSFunctionDeclaration>()
      .toList()

    if (functions.isEmpty()) return emptyList()

    val file = codeGenerator.createNewFile(
      dependencies = Dependencies(
        aggregating = true,
        *functions.mapNotNull { it.containingFile }.toTypedArray(),
      ),
      packageName = "",
      fileName = "Interop",
      extensionName = "cs",
    )

    file.writer().use { writer ->
      writer.write("using System.Runtime.InteropServices;\n\n")
      writer.write("namespace $namespace\n{\n")
      writer.write("    public static partial class $className\n    {\n")

      for (func in functions) {
        val cname: String = func.annotations
          .first { it.shortName.asString() == "CName" }
          .arguments.first().value as String

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

        writer.write("        [DllImport(\"$libraryName\", CallingConvention = CallingConvention.Cdecl)]\n")

        if (isStringReturn) {
          writer.write("        [return: MarshalAs(UnmanagedType.LPUTF8Str)]\n")
          writer.write("        public static extern string $cname(${params.joinToString(", ")});\n")
        } else {
          writer.write("        public static extern $csharpReturnType $cname(${params.joinToString(", ")});\n")
        }

        writer.write("\n")
      }

      writer.write("    }\n}\n")
    }

    logger.info("Generated C# bindings for ${functions.size} functions")
    return emptyList()
  }

  private fun mapType(kotlinType: String): String =
    KOTLIN_TO_CSHARP[kotlinType] ?: "IntPtr"
}
