package io.github.xxfast.kotlin.native.nuget.processor.exports

import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.Visibility
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec

/**
 * Generates @CName bridge exports for generic classes using type erasure.
 * For Box<T>, generates box_get_value that returns Any, box_dispose, and helper unwrap functions.
 */
internal fun FileSpec.Builder.addGenericClassExports(cls: KSClassDeclaration) {
  val name: String = cls.simpleName.asString()
  val qualifiedName: String = cls.qualifiedName?.asString() ?: return
  val prefix: String = name.lowercase()

  val hasNonTrivialBound: Boolean = cls.typeParameters.firstOrNull()
    ?.bounds?.toList()?.any { bound ->
      val resolved = bound.resolve()
      resolved.declaration.qualifiedName?.asString() != "kotlin.Any"
    } ?: false

  if (!hasNonTrivialBound) {
    val primitiveVariants: List<Pair<String, ParameterSpec>> = listOf(
      "string" to ParameterSpec.builder("value", String::class).build(),
      "byte" to ParameterSpec.builder("value", Byte::class).build(),
      "ubyte" to ParameterSpec.builder("value", UByte::class).build(),
      "short" to ParameterSpec.builder("value", Short::class).build(),
      "ushort" to ParameterSpec.builder("value", UShort::class).build(),
      "int" to ParameterSpec.builder("value", Int::class).build(),
      "uint" to ParameterSpec.builder("value", UInt::class).build(),
      "long" to ParameterSpec.builder("value", Long::class).build(),
      "ulong" to ParameterSpec.builder("value", ULong::class).build(),
      "float" to ParameterSpec.builder("value", Float::class).build(),
      "double" to ParameterSpec.builder("value", Double::class).build(),
      "bool" to ParameterSpec.builder("value", Boolean::class).build(),
    )

    for ((suffix, param) in primitiveVariants) {
      addGenericCreateExport(prefix, suffix, param, qualifiedName, "value")
    }
  }

  val boundQualified: String? = cls.typeParameters.firstOrNull()
    ?.bounds?.toList()?.firstOrNull()?.let { bound ->
      val resolved = bound.resolve()
      val qn: String? = resolved.declaration.qualifiedName?.asString()
      if (qn != null && qn != "kotlin.Any") qn else null
    }

  val castExpr: String = if (boundQualified != null) {
    "value.asStableRef<$boundQualified>().get()"
  } else {
    "value.asStableRef<Any>().get()"
  }

  addGenericCreateExport(
    prefix,
    "object",
    ParameterSpec.builder("value", cOpaquePointer).build(),
    qualifiedName,
    castExpr,
  )

  addFunction(
    FunSpec.builder("export_${prefix}_dispose")
      .addAnnotation(cNameAnnotation("${prefix}_dispose"))
      .addParameter("handle", cOpaquePointer)
      .addStatement("handle.asStableRef<%L<*>>().dispose()", qualifiedName)
      .build()
  )

  val properties: List<KSPropertyDeclaration> = cls.getAllProperties()
    .filter { it.getVisibility() == Visibility.PUBLIC }
    .toList()

  for (prop in properties) {
    val propName: String = prop.simpleName.asString()

    addFunction(
      FunSpec.builder("export_${prefix}_get_$propName")
        .addAnnotation(cNameAnnotation("${prefix}_get_$propName"))
        .addParameter("handle", cOpaquePointer)
        .returns(cOpaquePointer)
        .addStatement(
          "return %T.create(handle.asStableRef<%L<*>>().get().%L!!).asCPointer()",
          stableRef, qualifiedName, propName,
        )
        .build()
    )
  }
}

/**
 * Emits a generic-class construction export with synchronous exception propagation
 * (ADR-032): a trailing nullable [errorOut] receives a StableRef<NugetError> when
 * the Kotlin constructor throws, and the export returns null instead of a handle.
 */
private fun FileSpec.Builder.addGenericCreateExport(
  prefix: String,
  suffix: String,
  valueParam: ParameterSpec,
  qualifiedName: String,
  argExpr: String,
) {
  addFunction(
    FunSpec.builder("export_${prefix}_create_$suffix")
      .addAnnotation(cNameAnnotation("${prefix}_create_$suffix"))
      .addParameter(valueParam)
      .addParameter("errorOut", cOpaquePointer.copy(nullable = true))
      .returns(cOpaquePointer.copy(nullable = true))
      .addCode(buildString {
        appendLine("return try {")
        appendLine("  %T.create(%L($argExpr)).asCPointer()")
        appendLine("} catch (e: Throwable) {")
        appendLine("  if (errorOut != null) {")
        appendLine("    errorOut.reinterpret<%T>().pointed.value = %T.create(")
        appendLine("      buildError(e)")
        appendLine("    ).asCPointer()")
        appendLine("  }")
        appendLine("  null")
        append("}")
      }, stableRef, qualifiedName, cOpaquePointerVar, stableRef)
      .build()
  )
}

internal fun FileSpec.Builder.addNugetListHelperExports() {
  addFunction(
    FunSpec.builder("export_nuget_list_count")
      .addAnnotation(cNameAnnotation("nuget_list_count"))
      .addParameter("handle", cOpaquePointer)
      .returns(Int::class)
      .addStatement("return handle.asStableRef<List<*>>().get().size")
      .build()
  )

  addFunction(
    FunSpec.builder("export_nuget_list_get")
      .addAnnotation(cNameAnnotation("nuget_list_get"))
      .addParameter("handle", cOpaquePointer)
      .addParameter("index", Int::class)
      .returns(cOpaquePointer)
      .addStatement(
        "return %T.create(handle.asStableRef<List<*>>().get()[index]!!).asCPointer()",
        stableRef,
      )
      .build()
  )
}

internal fun FileSpec.Builder.addNugetSetHelperExports() {
  addFunction(
    FunSpec.builder("export_nuget_set_count")
      .addAnnotation(cNameAnnotation("nuget_set_count"))
      .addParameter("handle", cOpaquePointer)
      .returns(Int::class)
      .addStatement("return handle.asStableRef<Set<*>>().get().size")
      .build()
  )

  addFunction(
    FunSpec.builder("export_nuget_set_element_at")
      .addAnnotation(cNameAnnotation("nuget_set_element_at"))
      .addParameter("handle", cOpaquePointer)
      .addParameter("index", Int::class)
      .returns(cOpaquePointer)
      .addStatement(
        "return %T.create(handle.asStableRef<Set<*>>().get().toList()[index]!!).asCPointer()",
        stableRef,
      )
      .build()
  )
}

internal fun FileSpec.Builder.addNugetMapHelperExports() {
  addFunction(
    FunSpec.builder("export_nuget_map_count")
      .addAnnotation(cNameAnnotation("nuget_map_count"))
      .addParameter("handle", cOpaquePointer)
      .returns(Int::class)
      .addStatement("return handle.asStableRef<Map<*, *>>().get().size")
      .build()
  )

  addFunction(
    FunSpec.builder("export_nuget_map_key_at")
      .addAnnotation(cNameAnnotation("nuget_map_key_at"))
      .addParameter("handle", cOpaquePointer)
      .addParameter("index", Int::class)
      .returns(cOpaquePointer)
      .addStatement(
        "return %T.create(handle.asStableRef<Map<*, *>>().get().keys.toList()[index]!!).asCPointer()",
        stableRef,
      )
      .build()
  )

  addFunction(
    FunSpec.builder("export_nuget_map_value_at")
      .addAnnotation(cNameAnnotation("nuget_map_value_at"))
      .addParameter("handle", cOpaquePointer)
      .addParameter("index", Int::class)
      .returns(cOpaquePointer)
      .addStatement(
        "return %T.create(handle.asStableRef<Map<*, *>>().get().values.toList()[index]!!).asCPointer()",
        stableRef,
      )
      .build()
  )
}

internal fun FileSpec.Builder.addNugetHelperExports() {
  addFunction(
    FunSpec.builder("export_nuget_unwrap_string")
      .addAnnotation(cNameAnnotation("nuget_unwrap_string"))
      .addParameter("handle", cOpaquePointer)
      .returns(String::class)
      .addStatement("return handle.asStableRef<Any>().get() as String")
      .build()
  )

  addFunction(
    FunSpec.builder("export_nuget_unwrap_byte")
      .addAnnotation(cNameAnnotation("nuget_unwrap_byte"))
      .addParameter("handle", cOpaquePointer)
      .returns(Byte::class)
      .addStatement("return handle.asStableRef<Any>().get() as Byte")
      .build()
  )

  addFunction(
    FunSpec.builder("export_nuget_unwrap_ubyte")
      .addAnnotation(cNameAnnotation("nuget_unwrap_ubyte"))
      .addParameter("handle", cOpaquePointer)
      .returns(UByte::class)
      .addStatement("return handle.asStableRef<Any>().get() as UByte")
      .build()
  )

  addFunction(
    FunSpec.builder("export_nuget_unwrap_short")
      .addAnnotation(cNameAnnotation("nuget_unwrap_short"))
      .addParameter("handle", cOpaquePointer)
      .returns(Short::class)
      .addStatement("return handle.asStableRef<Any>().get() as Short")
      .build()
  )

  addFunction(
    FunSpec.builder("export_nuget_unwrap_ushort")
      .addAnnotation(cNameAnnotation("nuget_unwrap_ushort"))
      .addParameter("handle", cOpaquePointer)
      .returns(UShort::class)
      .addStatement("return handle.asStableRef<Any>().get() as UShort")
      .build()
  )

  addFunction(
    FunSpec.builder("export_nuget_unwrap_int")
      .addAnnotation(cNameAnnotation("nuget_unwrap_int"))
      .addParameter("handle", cOpaquePointer)
      .returns(Int::class)
      .addStatement("return handle.asStableRef<Any>().get() as Int")
      .build()
  )

  addFunction(
    FunSpec.builder("export_nuget_unwrap_uint")
      .addAnnotation(cNameAnnotation("nuget_unwrap_uint"))
      .addParameter("handle", cOpaquePointer)
      .returns(UInt::class)
      .addStatement("return handle.asStableRef<Any>().get() as UInt")
      .build()
  )

  addFunction(
    FunSpec.builder("export_nuget_unwrap_long")
      .addAnnotation(cNameAnnotation("nuget_unwrap_long"))
      .addParameter("handle", cOpaquePointer)
      .returns(Long::class)
      .addStatement("return handle.asStableRef<Any>().get() as Long")
      .build()
  )

  addFunction(
    FunSpec.builder("export_nuget_unwrap_ulong")
      .addAnnotation(cNameAnnotation("nuget_unwrap_ulong"))
      .addParameter("handle", cOpaquePointer)
      .returns(ULong::class)
      .addStatement("return handle.asStableRef<Any>().get() as ULong")
      .build()
  )

  addFunction(
    FunSpec.builder("export_nuget_unwrap_float")
      .addAnnotation(cNameAnnotation("nuget_unwrap_float"))
      .addParameter("handle", cOpaquePointer)
      .returns(Float::class)
      .addStatement("return handle.asStableRef<Any>().get() as Float")
      .build()
  )

  addFunction(
    FunSpec.builder("export_nuget_unwrap_double")
      .addAnnotation(cNameAnnotation("nuget_unwrap_double"))
      .addParameter("handle", cOpaquePointer)
      .returns(Double::class)
      .addStatement("return handle.asStableRef<Any>().get() as Double")
      .build()
  )

  addFunction(
    FunSpec.builder("export_nuget_unwrap_bool")
      .addAnnotation(cNameAnnotation("nuget_unwrap_bool"))
      .addParameter("handle", cOpaquePointer)
      .returns(Boolean::class)
      .addStatement("return handle.asStableRef<Any>().get() as Boolean")
      .build()
  )

  addFunction(
    FunSpec.builder("export_nuget_dispose")
      .addAnnotation(cNameAnnotation("nuget_dispose"))
      .addParameter("handle", cOpaquePointer)
      .addStatement("handle.asStableRef<Any>().dispose()")
      .build()
  )
}

internal fun FileSpec.Builder.addNugetWrapHelperExports() {
  val types = listOf(
    "string" to String::class,
    "int" to Int::class,
    "long" to Long::class,
    "float" to Float::class,
    "double" to Double::class,
    "bool" to Boolean::class,
  )

  for ((suffix, type) in types) {
    addFunction(
      FunSpec.builder("export_nuget_wrap_$suffix")
        .addAnnotation(cNameAnnotation("nuget_wrap_$suffix"))
        .addParameter("value", type)
        .returns(cOpaquePointer)
        .addStatement(
          "return %T.create(value as Any).asCPointer()",
          stableRef,
        )
        .build()
    )
  }
}

internal fun FileSpec.Builder.addNugetFunc0HelperExports() {
  addFunction(
    FunSpec.builder("export_nuget_func0_invoke")
      .addAnnotation(cNameAnnotation("nuget_func0_invoke"))
      .addParameter("handle", cOpaquePointer)
      .returns(cOpaquePointer)
      .addStatement(
        "val fn = handle.asStableRef<Function0<*>>().get()",
      )
      .addStatement(
        "return %T.create(fn.invoke() as Any).asCPointer()",
        stableRef,
      )
      .build()
  )
}

internal fun FileSpec.Builder.addNugetFunc1HelperExports() {
  addFunction(
    FunSpec.builder("export_nuget_func1_invoke")
      .addAnnotation(cNameAnnotation("nuget_func1_invoke"))
      .addParameter("handle", cOpaquePointer)
      .addParameter("arg0", cOpaquePointer)
      .returns(cOpaquePointer)
      .addStatement(
        "val fn = handle.asStableRef<Function1<Any?, Any?>>().get()",
      )
      .addStatement(
        "val param0 = arg0.asStableRef<Any>().get()",
      )
      .addStatement(
        "return %T.create(fn.invoke(param0) as Any).asCPointer()",
        stableRef,
      )
      .build()
  )
}

internal fun FileSpec.Builder.addNugetFunc2HelperExports() {
  addFunction(
    FunSpec.builder("export_nuget_func2_invoke")
      .addAnnotation(cNameAnnotation("nuget_func2_invoke"))
      .addParameter("handle", cOpaquePointer)
      .addParameter("arg0", cOpaquePointer)
      .addParameter("arg1", cOpaquePointer)
      .returns(cOpaquePointer)
      .addStatement(
        "val fn = handle.asStableRef<Function2<Any?, Any?, Any?>>().get()",
      )
      .addStatement(
        "val param0 = arg0.asStableRef<Any>().get()",
      )
      .addStatement(
        "val param1 = arg1.asStableRef<Any>().get()",
      )
      .addStatement(
        "return %T.create(fn.invoke(param0, param1) as Any).asCPointer()",
        stableRef,
      )
      .build()
  )
}

internal fun FileSpec.Builder.addNugetSuspendFunc0HelperExports() {
  addFunction(
    FunSpec.builder("export_nuget_suspend_func0_invoke")
      .addAnnotation(cNameAnnotation("nuget_suspend_func0_invoke"))
      .addParameter("handle", cOpaquePointer)
      .addParameter("callbackPtr", cOpaquePointer)
      .addParameter("userData", cOpaquePointer)
      .returns(cOpaquePointer)
      .addCode(buildString {
        appendLine("val fn = handle.asStableRef<SuspendFunction0<*>>().get()")
        appendLine("val callback = callbackPtr.reinterpret<CFunction<")
        appendLine("  (COpaquePointer?, COpaquePointer?, Byte, COpaquePointer) -> Unit>>()")
        appendLine("val job = CoroutineScope(Dispatchers.Default).launch(start = CoroutineStart.ATOMIC) {")
        appendLine("  try {")
        appendLine("    val result = fn.invoke()")
        appendLine("    if (result == Unit) {")
        appendLine("      callback.invoke(null, null, 0.toByte(), userData)")
        appendLine("    } else {")
        appendLine("      val resultRef = StableRef.create(result as Any).asCPointer()")
        appendLine("      callback.invoke(resultRef, null, 0.toByte(), userData)")
        appendLine("    }")
        appendLine("  } catch (e: CancellationException) {")
        appendLine("    callback.invoke(null, null, 1.toByte(), userData)")
        appendLine("    throw e")
        appendLine("  } catch (e: Throwable) {")
        appendLine("    val errRef = StableRef.create(buildError(e)).asCPointer()")
        appendLine("    callback.invoke(null, errRef, 0.toByte(), userData)")
        appendLine("  }")
        appendLine("}")
        append("return StableRef.create(job).asCPointer()")
      })
      .build()
  )
}

internal fun FileSpec.Builder.addNugetSuspendFunc1HelperExports() {
  addFunction(
    FunSpec.builder("export_nuget_suspend_func1_invoke")
      .addAnnotation(cNameAnnotation("nuget_suspend_func1_invoke"))
      .addParameter("handle", cOpaquePointer)
      .addParameter("arg0", cOpaquePointer)
      .addParameter("callbackPtr", cOpaquePointer)
      .addParameter("userData", cOpaquePointer)
      .returns(cOpaquePointer)
      .addCode(buildString {
        appendLine("val fn = handle.asStableRef<SuspendFunction1<Any?, Any?>>().get()")
        appendLine("val param0 = arg0.asStableRef<Any>().get()")
        appendLine("val callback = callbackPtr.reinterpret<CFunction<")
        appendLine("  (COpaquePointer?, COpaquePointer?, Byte, COpaquePointer) -> Unit>>()")
        appendLine("val job = CoroutineScope(Dispatchers.Default).launch(start = CoroutineStart.ATOMIC) {")
        appendLine("  try {")
        appendLine("    val result = fn.invoke(param0)")
        appendLine("    if (result == Unit) {")
        appendLine("      callback.invoke(null, null, 0.toByte(), userData)")
        appendLine("    } else {")
        appendLine("      val resultRef = StableRef.create(result as Any).asCPointer()")
        appendLine("      callback.invoke(resultRef, null, 0.toByte(), userData)")
        appendLine("    }")
        appendLine("  } catch (e: CancellationException) {")
        appendLine("    callback.invoke(null, null, 1.toByte(), userData)")
        appendLine("    throw e")
        appendLine("  } catch (e: Throwable) {")
        appendLine("    val errRef = StableRef.create(buildError(e)).asCPointer()")
        appendLine("    callback.invoke(null, errRef, 0.toByte(), userData)")
        appendLine("  }")
        appendLine("}")
        append("return StableRef.create(job).asCPointer()")
      })
      .build()
  )
}

internal fun FileSpec.Builder.addNugetScopeHelperExports() {
  addFunction(
    FunSpec.builder("export_nuget_scope_create")
      .addAnnotation(cNameAnnotation("nuget_scope_create"))
      .returns(cOpaquePointer)
      .addStatement(
        "return %T.create(%T(%T() + %T.Default)).asCPointer()",
        stableRef,
        ClassName("kotlinx.coroutines", "CoroutineScope"),
        ClassName("kotlinx.coroutines", "SupervisorJob"),
        ClassName("kotlinx.coroutines", "Dispatchers"),
      )
      .build()
  )

  addFunction(
    FunSpec.builder("export_nuget_scope_cancel")
      .addAnnotation(cNameAnnotation("nuget_scope_cancel"))
      .addParameter("handle", cOpaquePointer.copy(nullable = true))
      .beginControlFlow("if (handle == null)")
      .addStatement("return")
      .endControlFlow()
      .addStatement(
        "handle.asStableRef<%T>().get().cancel()",
        ClassName("kotlinx.coroutines", "CoroutineScope"),
      )
      .build()
  )

  addFunction(
    FunSpec.builder("export_nuget_scope_dispose")
      .addAnnotation(cNameAnnotation("nuget_scope_dispose"))
      .addParameter("handle", cOpaquePointer.copy(nullable = true))
      .beginControlFlow("if (handle == null)")
      .addStatement("return")
      .endControlFlow()
      .addStatement(
        "handle.asStableRef<%T>().dispose()",
        ClassName("kotlinx.coroutines", "CoroutineScope"),
      )
      .build()
  )
}

internal fun FileSpec.Builder.addNugetScopeDrainExport() {
  addFunction(
    FunSpec.builder("export_nuget_scope_drain")
      .addAnnotation(cNameAnnotation("nuget_scope_drain"))
      .addParameter("scopeHandle", cOpaquePointer)
      .addParameter("callbackPtr", cOpaquePointer)
      .addParameter("userData", cOpaquePointer)
      .returns(cOpaquePointer)
      .addCode(buildString {
        appendLine("val scope = scopeHandle.asStableRef<CoroutineScope>().get()")
        appendLine("val callback = callbackPtr.reinterpret<CFunction<")
        appendLine("  (COpaquePointer?, COpaquePointer?, Byte, COpaquePointer) -> Unit>>()")
        appendLine("val drainJob = scope.launch(start = CoroutineStart.ATOMIC) {")
        appendLine("  val self = coroutineContext[Job]")
        appendLine("  scope.coroutineContext[Job]")
        appendLine("    ?.children")
        appendLine("    ?.filter { it != self }")
        appendLine("    ?.forEach { it.join() }")
        appendLine("  callback.invoke(null, null, 0.toByte(), userData)")
        appendLine("}")
        append("return StableRef.create(drainJob).asCPointer()")
      })
      .build()
  )
}

internal fun FileSpec.Builder.addNugetJobHelperExports() {
  addFunction(
    FunSpec.builder("export_nuget_job_cancel")
      .addAnnotation(cNameAnnotation("nuget_job_cancel"))
      .addParameter("handle", cOpaquePointer.copy(nullable = true))
      .beginControlFlow("if (handle == null)")
      .addStatement("return")
      .endControlFlow()
      .addStatement(
        "handle.asStableRef<%T>().get().cancel()",
        ClassName("kotlinx.coroutines", "Job"),
      )
      .build()
  )

  addFunction(
    FunSpec.builder("export_nuget_job_dispose")
      .addAnnotation(cNameAnnotation("nuget_job_dispose"))
      .addParameter("handle", cOpaquePointer.copy(nullable = true))
      .beginControlFlow("if (handle == null)")
      .addStatement("return")
      .endControlFlow()
      .addStatement(
        "handle.asStableRef<%T>().dispose()",
        ClassName("kotlinx.coroutines", "Job"),
      )
      .build()
  )
}

internal fun FileSpec.Builder.addNugetErrorHelperExports() {
  val nugetError = ClassName("io.github.xxfast.kotlin.native.nuget.generated", "NugetError")

  addType(
    TypeSpec.classBuilder("NugetError")
      .addModifiers(KModifier.DATA)
      .primaryConstructor(
        FunSpec.constructorBuilder()
          .addParameter("type", String::class)
          .addParameter("message", String::class)
          .addParameter("stackTrace", String::class)
          .addParameter(
            ParameterSpec.builder("cause", nugetError.copy(nullable = true))
              .defaultValue("null")
              .build()
          )
          .build()
      )
      .addProperty(PropertySpec.builder("type", String::class).initializer("type").build())
      .addProperty(PropertySpec.builder("message", String::class).initializer("message").build())
      .addProperty(PropertySpec.builder("stackTrace", String::class).initializer("stackTrace").build())
      .addProperty(
        PropertySpec.builder("cause", nugetError.copy(nullable = true))
          .initializer("cause")
          .build()
      )
      .build()
  )

  addFunction(
    FunSpec.builder("buildError")
      .addModifiers(KModifier.PRIVATE)
      .addParameter("e", Throwable::class)
      .returns(nugetError)
      .addCode(buildString {
        appendLine("val seen = mutableSetOf<Throwable>()")
        appendLine("fun build(t: Throwable): NugetError? {")
        appendLine("  if (!seen.add(t)) return null")
        appendLine("  return NugetError(")
        appendLine("    type = t::class.qualifiedName ?: t::class.simpleName ?: \"UnknownException\",")
        appendLine("    message = t.message ?: \"Kotlin error\",")
        appendLine("    stackTrace = t.stackTraceToString(),")
        appendLine("    cause = t.cause?.let(::build),")
        appendLine("  )")
        appendLine("}")
        append("return build(e)!!")
      })
      .build()
  )

  addFunction(
    FunSpec.builder("at")
      .addModifiers(KModifier.PRIVATE, KModifier.TAILREC)
      .receiver(nugetError)
      .addParameter("index", Int::class)
      .returns(nugetError)
      .addStatement("return if (index == 0) this else cause!!.at(index - 1)")
      .build()
  )

  addFunction(
    FunSpec.builder("export_nuget_error_type")
      .addAnnotation(cNameAnnotation("nuget_error_type"))
      .addParameter("handle", cOpaquePointer)
      .returns(String::class)
      .addStatement("return handle.asStableRef<NugetError>().get().type")
      .build()
  )

  addFunction(
    FunSpec.builder("export_nuget_error_message")
      .addAnnotation(cNameAnnotation("nuget_error_message"))
      .addParameter("handle", cOpaquePointer)
      .returns(String::class)
      .addStatement("return handle.asStableRef<NugetError>().get().message")
      .build()
  )

  addFunction(
    FunSpec.builder("export_nuget_error_stacktrace")
      .addAnnotation(cNameAnnotation("nuget_error_stacktrace"))
      .addParameter("handle", cOpaquePointer)
      .returns(String::class)
      .addStatement("return handle.asStableRef<NugetError>().get().stackTrace")
      .build()
  )

  addFunction(
    FunSpec.builder("export_nuget_error_cause_count")
      .addAnnotation(cNameAnnotation("nuget_error_cause_count"))
      .addParameter("handle", cOpaquePointer)
      .returns(Int::class)
      .addCode(buildString {
        appendLine("var e: NugetError? = handle.asStableRef<NugetError>().get()")
        appendLine("var n = 0")
        appendLine("while (e != null) { n++; e = e.cause }")
        append("return n")
      })
      .build()
  )

  addFunction(
    FunSpec.builder("export_nuget_error_cause_type")
      .addAnnotation(cNameAnnotation("nuget_error_cause_type"))
      .addParameter("handle", cOpaquePointer)
      .addParameter("index", Int::class)
      .returns(String::class)
      .addStatement("return handle.asStableRef<NugetError>().get().at(index).type")
      .build()
  )

  addFunction(
    FunSpec.builder("export_nuget_error_cause_message")
      .addAnnotation(cNameAnnotation("nuget_error_cause_message"))
      .addParameter("handle", cOpaquePointer)
      .addParameter("index", Int::class)
      .returns(String::class)
      .addStatement("return handle.asStableRef<NugetError>().get().at(index).message")
      .build()
  )

  addFunction(
    FunSpec.builder("export_nuget_error_cause_stacktrace")
      .addAnnotation(cNameAnnotation("nuget_error_cause_stacktrace"))
      .addParameter("handle", cOpaquePointer)
      .addParameter("index", Int::class)
      .returns(String::class)
      .addStatement("return handle.asStableRef<NugetError>().get().at(index).stackTrace")
      .build()
  )
}

internal fun FileSpec.Builder.addNugetFunc3HelperExports() {
  addFunction(
    FunSpec.builder("export_nuget_func3_invoke")
      .addAnnotation(cNameAnnotation("nuget_func3_invoke"))
      .addParameter("handle", cOpaquePointer)
      .addParameter("arg0", cOpaquePointer)
      .addParameter("arg1", cOpaquePointer)
      .addParameter("arg2", cOpaquePointer)
      .returns(cOpaquePointer)
      .addStatement(
        "val fn = handle.asStableRef<Function3<Any?, Any?, Any?, Any?>>().get()",
      )
      .addStatement(
        "val param0 = arg0.asStableRef<Any>().get()",
      )
      .addStatement(
        "val param1 = arg1.asStableRef<Any>().get()",
      )
      .addStatement(
        "val param2 = arg2.asStableRef<Any>().get()",
      )
      .addStatement(
        "return %T.create(fn.invoke(param0, param1, param2) as Any).asCPointer()",
        stableRef,
      )
      .build()
  )
}
