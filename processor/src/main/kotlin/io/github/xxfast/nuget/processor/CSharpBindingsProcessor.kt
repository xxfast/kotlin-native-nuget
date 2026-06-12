package io.github.xxfast.nuget.processor

import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSValueParameter
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.Visibility

private val KOTLIN_TO_CSHARP_RETURN = mapOf(
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

private val KOTLIN_TO_CSHARP_PARAM = mapOf(
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

    val allDeclarations = resolver.getAllFiles()
      .flatMap { it.declarations }
      .filter { it.packageName.asString() != "io.github.xxfast.nuget.generated" }
      .toList()

    val functions: List<KSFunctionDeclaration> = allDeclarations
      .filterIsInstance<KSFunctionDeclaration>()
      .filter { it.getVisibility() == Visibility.PUBLIC }
      .filter { it.parentDeclaration == null }
      .toList()

    val classes: List<KSClassDeclaration> = allDeclarations
      .filterIsInstance<KSClassDeclaration>()
      .filter { it.getVisibility() == Visibility.PUBLIC }
      .filter { it.classKind == ClassKind.CLASS }
      .filter { it.parentDeclaration == null }
      .toList()

    if (functions.isEmpty() && classes.isEmpty()) return emptyList()

    val sources = (functions.mapNotNull { it.containingFile } +
      classes.mapNotNull { it.containingFile }).toTypedArray()

    val deps = Dependencies(aggregating = true, *sources)

    generateCNameWrappers(functions, classes, deps)
    generateCSharpBindings(functions, classes, deps)

    logger.info("Generated bindings for ${functions.size} functions and ${classes.size} classes")
    return emptyList()
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
      writer.write("@file:OptIn(ExperimentalNativeApi::class, ExperimentalForeignApi::class)\n")
      writer.write("package io.github.xxfast.nuget.generated\n\n")
      writer.write("import kotlin.experimental.ExperimentalNativeApi\n")
      writer.write("import kotlinx.cinterop.ExperimentalForeignApi\n")
      writer.write("import kotlinx.cinterop.*\n")

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

      for (cls in classes) {
        val name: String = cls.simpleName.asString()
        val qualifiedName: String = cls.qualifiedName?.asString() ?: continue
        val prefix: String = name.lowercase()

        val constructor = cls.primaryConstructor ?: continue
        val ctorParams: List<KSValueParameter> = constructor.parameters

        val ctorParamDecl: String = ctorParams.joinToString(", ") { param ->
          val type: String = param.type.resolve().declaration.qualifiedName?.asString()
            ?: param.type.resolve().declaration.simpleName.asString()

          "${param.name?.asString()}: $type"
        }

        val ctorParamCall: String = ctorParams.joinToString(", ") { param ->
          param.name?.asString() ?: "_"
        }

        writer.write("@CName(\"${prefix}_create\")\n")
        writer.write("fun `export_${prefix}_create`($ctorParamDecl): COpaquePointer =\n")
        writer.write("    StableRef.create($qualifiedName($ctorParamCall)).asCPointer()\n\n")

        writer.write("@CName(\"${prefix}_dispose\")\n")
        writer.write("fun `export_${prefix}_dispose`(handle: COpaquePointer) {\n")
        writer.write("    handle.asStableRef<$qualifiedName>().dispose()\n")
        writer.write("}\n\n")

        val properties: List<KSPropertyDeclaration> = cls.getAllProperties()
          .filter { it.getVisibility() == Visibility.PUBLIC }
          .toList()

        for (prop in properties) {
          val propName: String = prop.simpleName.asString()
          val propType: String = prop.type.resolve()
            .declaration.qualifiedName?.asString() ?: "Any"

          writer.write("@CName(\"${prefix}_get_$propName\")\n")
          writer.write("fun `export_${prefix}_get_$propName`(handle: COpaquePointer): $propType =\n")
          writer.write("    handle.asStableRef<$qualifiedName>().get().$propName\n\n")
        }

        val methods: List<KSFunctionDeclaration> = cls.getAllFunctions()
          .filter { it.getVisibility() == Visibility.PUBLIC }
          .filter { it.simpleName.asString() !in listOf("equals", "hashCode", "toString", "<init>") }
          .toList()

        for (method in methods) {
          val methodName: String = method.simpleName.asString()
          val methodReturn: String = method.returnType?.resolve()
            ?.declaration?.qualifiedName?.asString() ?: "Unit"

          val methodParams: List<KSValueParameter> = method.parameters
          val methodParamDecl: String = (listOf("handle: COpaquePointer") +
            methodParams.map { param ->
              val type: String = param.type.resolve().declaration.qualifiedName?.asString()
                ?: param.type.resolve().declaration.simpleName.asString()

              "${param.name?.asString()}: $type"
            }).joinToString(", ")

          val methodParamCall: String = methodParams.joinToString(", ") { param ->
            param.name?.asString() ?: "_"
          }

          if (methodReturn == "kotlin.Unit") {
            writer.write("@CName(\"${prefix}_$methodName\")\n")
            writer.write("fun `export_${prefix}_$methodName`($methodParamDecl) {\n")
            writer.write("    handle.asStableRef<$qualifiedName>().get().$methodName($methodParamCall)\n")
            writer.write("}\n\n")
          } else {
            writer.write("@CName(\"${prefix}_$methodName\")\n")
            writer.write("fun `export_${prefix}_$methodName`($methodParamDecl): $methodReturn =\n")
            writer.write("    handle.asStableRef<$qualifiedName>().get().$methodName($methodParamCall)\n\n")
          }
        }
      }
    }
  }

  private fun generateCSharpBindings(
    functions: List<KSFunctionDeclaration>,
    classes: List<KSClassDeclaration>,
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

      for (cls in classes) {
        val namespace: String = mapPackageToNamespace(cls.packageName.asString())
        val name: String = cls.simpleName.asString()
        val prefix: String = name.lowercase()

        writer.write("namespace $namespace\n{\n")

        writer.write("    public class $name : IDisposable\n    {\n")
        writer.write("        private IntPtr _handle;\n\n")

        val constructor = cls.primaryConstructor
        if (constructor != null) {
          val ctorParams: List<String> = constructor.parameters.map { param ->
            val kotlinType: String = param.type.resolve().declaration.simpleName.asString()
            val csharpType: String = mapParamType(kotlinType)
            "$csharpType ${param.name?.asString()}"
          }

          val ctorParamStr: String = ctorParams.joinToString(", ")
          val ctorParamNames: String = constructor.parameters.joinToString(", ") { param ->
            param.name?.asString() ?: "_"
          }

          writer.write("        [DllImport(\"$libraryName\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"${prefix}_create\")]\n")
          writer.write("        private static extern IntPtr Native_Create($ctorParamStr);\n\n")

          writer.write("        public $name($ctorParamStr)\n")
          writer.write("        {\n")
          writer.write("            _handle = Native_Create($ctorParamNames);\n")
          writer.write("        }\n\n")
        }

        val properties: List<KSPropertyDeclaration> = cls.getAllProperties()
          .filter { it.getVisibility() == Visibility.PUBLIC }
          .toList()

        for (prop in properties) {
          val propName: String = prop.simpleName.asString()
          val propType: String = prop.type.resolve().declaration.simpleName.asString()
          val csharpType: String = mapReturnType(propType)
          val csPropName: String = propName.replaceFirstChar { it.uppercase() }

          writer.write("        [DllImport(\"$libraryName\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"${prefix}_get_$propName\")]\n")
          writer.write("        private static extern $csharpType Native_Get_$propName(IntPtr handle);\n\n")

          if (propType == "String") {
            writer.write("        public string $csPropName => Marshal.PtrToStringUTF8(Native_Get_$propName(_handle))!;\n\n")
          } else {
            writer.write("        public $csharpType $csPropName => Native_Get_$propName(_handle);\n\n")
          }
        }

        val methods: List<KSFunctionDeclaration> = cls.getAllFunctions()
          .filter { it.getVisibility() == Visibility.PUBLIC }
          .filter { it.simpleName.asString() !in listOf("equals", "hashCode", "toString", "<init>") }
          .toList()

        for (method in methods) {
          val methodName: String = method.simpleName.asString()
          val methodReturn: String = method.returnType?.resolve()
            ?.declaration?.simpleName?.asString() ?: "Unit"

          val csharpReturn: String = mapReturnType(methodReturn)
          val csMethodName: String = methodName.replaceFirstChar { it.uppercase() }

          val methodParams: List<String> = method.parameters.map { param ->
            val kotlinType: String = param.type.resolve().declaration.simpleName.asString()
            val csharpType: String = mapParamType(kotlinType)
            "$csharpType ${param.name?.asString()}"
          }

          val nativeParams: String = (listOf("IntPtr handle") + methodParams).joinToString(", ")
          val paramStr: String = methodParams.joinToString(", ")
          val paramNames: String = method.parameters.joinToString(", ") { it.name?.asString() ?: "_" }
          val nativeCallArgs: String = (listOf("_handle") +
            method.parameters.map { it.name?.asString() ?: "_" }).joinToString(", ")

          writer.write("        [DllImport(\"$libraryName\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"${prefix}_$methodName\")]\n")
          writer.write("        private static extern $csharpReturn Native_$csMethodName($nativeParams);\n\n")

          if (methodReturn == "String") {
            writer.write("        public string $csMethodName($paramStr)\n")
            writer.write("            => Marshal.PtrToStringUTF8(Native_$csMethodName($nativeCallArgs))!;\n\n")
          } else if (methodReturn == "Unit") {
            writer.write("        public void $csMethodName($paramStr)\n")
            writer.write("            => Native_$csMethodName($nativeCallArgs);\n\n")
          } else {
            writer.write("        public $csharpReturn $csMethodName($paramStr)\n")
            writer.write("            => Native_$csMethodName($nativeCallArgs);\n\n")
          }
        }

        writer.write("        [DllImport(\"$libraryName\", CallingConvention = CallingConvention.Cdecl, EntryPoint = \"${prefix}_dispose\")]\n")
        writer.write("        private static extern void Native_Dispose(IntPtr handle);\n\n")

        writer.write("        public void Dispose()\n")
        writer.write("        {\n")
        writer.write("            if (_handle != IntPtr.Zero)\n")
        writer.write("            {\n")
        writer.write("                Native_Dispose(_handle);\n")
        writer.write("                _handle = IntPtr.Zero;\n")
        writer.write("            }\n")
        writer.write("        }\n")

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

    val csharpReturnType: String = mapReturnType(kotlinReturnType)

    val params: List<String> = func.parameters.map { param ->
      val kotlinType: String = param.type.resolve()
        .declaration.simpleName.asString()

      val csharpType: String = mapParamType(kotlinType)
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

  private fun mapReturnType(kotlinType: String): String =
    KOTLIN_TO_CSHARP_RETURN[kotlinType] ?: "IntPtr"

  private fun mapParamType(kotlinType: String): String =
    KOTLIN_TO_CSHARP_PARAM[kotlinType] ?: "IntPtr"

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
