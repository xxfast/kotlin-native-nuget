package io.github.xxfast.kotlin.native.nuget.processor

import io.github.xxfast.kotlin.native.nuget.processor.cir.CirClass
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirConstructor
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirDllImport
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirFile
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirMethod
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirNamespace
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirObject
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirParameter
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirProperty
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirStaticClass
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirValueClass
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirValueClassConstructor
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * MIGRATION.md test gate: ABI tests detect missing, duplicated, reordered, wrongly directed,
 * and width-mismatched parameters for every ordinary declaration form.
 */
class ForwardAbiDeclarationFormTest {

  private data class Form(
    val name: String,
    val matched: ForwardAbiSignature,
  )

  /** Canonical ABI pairs for each ordinary declaration form (C# and Kotlin halves identical). */
  private fun forms(): List<Form> = listOf(
    Form(
      "top-level function",
      signature(
        "static_echo",
        ForwardAbiParameter(ForwardAbiType.STRING),
        ForwardAbiParameter(ForwardAbiType.POINTER, ForwardAbiDirection.OUT),
        result = ForwardAbiType.POINTER,
      ),
    ),
    Form(
      "object method",
      signature(
        "singleton_ping",
        ForwardAbiParameter(ForwardAbiType.INT),
        ForwardAbiParameter(ForwardAbiType.POINTER, ForwardAbiDirection.OUT),
        result = ForwardAbiType.BOOL,
      ),
    ),
    Form(
      "class constructor",
      signature(
        "sample_create",
        ForwardAbiParameter(ForwardAbiType.POINTER, ForwardAbiDirection.OUT),
        result = ForwardAbiType.POINTER,
      ),
    ),
    Form(
      "class property",
      signature(
        "sample_get_count",
        ForwardAbiParameter(ForwardAbiType.POINTER),
        ForwardAbiParameter(ForwardAbiType.POINTER, ForwardAbiDirection.OUT),
        result = ForwardAbiType.INT,
      ),
    ),
    Form(
      "class method",
      signature(
        "sample_try_read",
        ForwardAbiParameter(ForwardAbiType.POINTER),
        ForwardAbiParameter(ForwardAbiType.STRING),
        ForwardAbiParameter(ForwardAbiType.POINTER, ForwardAbiDirection.OUT),
        ForwardAbiParameter(ForwardAbiType.POINTER, ForwardAbiDirection.OUT),
        result = ForwardAbiType.BOOL,
      ),
    ),
    Form(
      "companion method",
      signature(
        "class_companion",
        ForwardAbiParameter(ForwardAbiType.INT),
        result = ForwardAbiType.INT,
      ),
    ),
    Form(
      "value-class constructor",
      signature(
        "value_create",
        ForwardAbiParameter(ForwardAbiType.INT),
        ForwardAbiParameter(ForwardAbiType.POINTER, ForwardAbiDirection.OUT),
        result = ForwardAbiType.INT,
      ),
    ),
    Form(
      "value-class method",
      signature(
        "value_is_positive",
        ForwardAbiParameter(ForwardAbiType.INT),
        result = ForwardAbiType.BOOL,
      ),
    ),
    Form(
      "extension function",
      signature(
        "int_route",
        ForwardAbiParameter(ForwardAbiType.INT),
        ForwardAbiParameter(ForwardAbiType.POINTER, ForwardAbiDirection.OUT),
        result = ForwardAbiType.INT,
      ),
    ),
  )

  @Test
  fun `sample file projects every declaration form export`() {
    val csharp: List<ForwardAbiSignature> = ForwardAbiContract.csharp(sampleFile())
    val names: Set<String> = csharp.map { it.exportName }.toSet()
    listOf(
      "static_echo",
      "singleton_ping",
      "sample_create",
      "sample_get_count",
      "sample_try_read",
      "class_companion",
      "value_create",
      "value_is_positive",
      "int_route",
    ).forEach { export ->
      assertTrue(export in names, "missing $export in $names")
    }
  }

  @Test
  fun `matched halves pass for every declaration form`() {
    forms().forEach { form ->
      ForwardAbiContract.assertMatches(
        csharp = listOf(form.matched),
        kotlin = listOf(form.matched),
      )
    }
  }

  @Test
  fun `missing parameter is reported for every declaration form`() {
    forms().forEach { form ->
      val full: ForwardAbiSignature = form.matched
      if (full.parameters.isEmpty()) return@forEach
      val missing: ForwardAbiSignature = full.copy(parameters = full.parameters.dropLast(1))
      val error: IllegalArgumentException = assertFailsWith {
        ForwardAbiContract.assertMatches(csharp = listOf(full), kotlin = listOf(missing))
      }
      assertTrue(
        error.message!!.contains(full.exportName),
        "${form.name}: message should name export, got ${error.message}",
      )
    }
  }

  @Test
  fun `duplicated export name is reported`() {
    val sig: ForwardAbiSignature = forms().first().matched
    val error: IllegalArgumentException = assertFailsWith {
      ForwardAbiContract.assertMatches(
        csharp = listOf(sig, sig),
        kotlin = listOf(sig),
      )
    }
    assertTrue(
      error.message!!.contains("duplicate", ignoreCase = true) ||
          error.message!!.contains(sig.exportName),
    )
  }

  @Test
  fun `reordered parameters are reported for every multi-parameter form`() {
    forms().forEach { form ->
      val full: ForwardAbiSignature = form.matched
      if (full.parameters.size < 2) return@forEach
      val reordered: ForwardAbiSignature = full.copy(parameters = full.parameters.reversed())
      val error: IllegalArgumentException = assertFailsWith {
        ForwardAbiContract.assertMatches(csharp = listOf(full), kotlin = listOf(reordered))
      }
      assertTrue(error.message!!.contains(full.exportName), "${form.name}: ${error.message}")
    }
  }

  @Test
  fun `wrongly directed parameters are reported for every form with an out slot`() {
    forms().forEach { form ->
      val full: ForwardAbiSignature = form.matched
      val outParam: ForwardAbiParameter = full.parameters.firstOrNull { parameter ->
        parameter.direction == ForwardAbiDirection.OUT
      } ?: return@forEach
      val flipped: ForwardAbiSignature = full.copy(
        parameters = full.parameters.map { parameter ->
          if (parameter == outParam) parameter.copy(direction = ForwardAbiDirection.IN) else parameter
        },
      )
      val error: IllegalArgumentException = assertFailsWith {
        ForwardAbiContract.assertMatches(csharp = listOf(full), kotlin = listOf(flipped))
      }
      assertTrue(error.message!!.contains(full.exportName), "${form.name}: ${error.message}")
    }
  }

  @Test
  fun `width-mismatched result types are reported for every form`() {
    forms().forEach { form ->
      val full: ForwardAbiSignature = form.matched
      val wider: ForwardAbiType = when (full.result) {
        ForwardAbiType.INT -> ForwardAbiType.LONG
        ForwardAbiType.BOOL -> ForwardAbiType.INT
        ForwardAbiType.POINTER -> ForwardAbiType.LONG
        ForwardAbiType.STRING -> ForwardAbiType.POINTER
        else -> ForwardAbiType.LONG
      }
      if (wider == full.result) return@forEach
      val error: IllegalArgumentException = assertFailsWith {
        ForwardAbiContract.assertMatches(
          csharp = listOf(full),
          kotlin = listOf(full.copy(result = wider)),
        )
      }
      assertTrue(error.message!!.contains(full.exportName), "${form.name}: ${error.message}")
    }
  }

  private fun signature(
    name: String,
    vararg parameters: ForwardAbiParameter,
    result: ForwardAbiType,
  ): ForwardAbiSignature = ForwardAbiSignature(name, result, parameters.toList())

  private fun sampleFile(): CirFile = CirFile(
    namespaces = listOf(
      CirNamespace(
        name = "Sample",
        declarations = listOf(
          CirStaticClass(
            name = "Functions",
            members = listOf(
              CirDllImport(
                libraryName = "sample",
                entryPoint = "static_echo",
                returnType = "IntPtr",
                name = "Native_Echo",
                parameters = listOf(CirParameter("value", "string")),
                hasSyncErrorOut = true,
              ),
              CirDllImport(
                libraryName = "sample",
                entryPoint = "int_route",
                returnType = "int",
                name = "Native_Route",
                parameters = listOf(CirParameter("receiver", "int")),
                hasSyncErrorOut = true,
              ),
            ),
          ),
          CirObject(
            name = "Singleton",
            libraryName = "sample",
            nativePrefix = "singleton",
            methods = listOf(
              CirDllImport(
                libraryName = "sample",
                entryPoint = "singleton_ping",
                returnType = "bool",
                name = "Native_Ping",
                parameters = listOf(CirParameter("value", "int")),
                hasSyncErrorOut = true,
              ),
            ),
          ),
          CirClass(
            name = "SampleClass",
            libraryName = "sample",
            nativePrefix = "sample",
            constructor = CirConstructor(emptyList(), "", hasErrorCheck = true),
            secondaryConstructors = emptyList(),
            properties = listOf(
              CirProperty(
                name = "Count",
                type = "int",
                nativeReturnType = "int",
                nativeName = "count",
                getter = "0",
                hasSyncErrorOut = true,
              ),
            ),
            methods = listOf(
              CirMethod(
                name = "TryRead",
                returnType = "bool",
                nativeName = "try_read",
                parameters = listOf(CirParameter("key", "string")),
                body = "false",
                isSyncErrorCheckEnabled = true,
                extraNativeParams = listOf("out int value"),
              ),
            ),
            companionMembers = listOf(
              CirDllImport(
                libraryName = "sample",
                entryPoint = "class_companion",
                returnType = "int",
                name = "Native_Companion",
                parameters = listOf(CirParameter("value", "int")),
              ),
            ),
          ),
          CirValueClass(
            name = "SampleValue",
            libraryName = "sample",
            nativePrefix = "value",
            underlyingType = "int",
            underlyingName = "Value",
            underlyingNativeType = "int",
            constructors = listOf(
              CirValueClassConstructor(
                parameters = listOf(CirParameter("value", "int")),
                nativeName = "value_create",
                body = "value",
                hasErrorCheck = true,
              ),
            ),
            properties = emptyList(),
            methods = listOf(
              CirMethod(
                name = "IsPositive",
                returnType = "bool",
                nativeName = "is_positive",
                parameters = emptyList(),
                body = "false",
              ),
            ),
          ),
        ),
      ),
    ),
  )
}
