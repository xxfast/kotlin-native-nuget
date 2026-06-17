package io.github.xxfast.kotlin.native.nuget.processor.exports

import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.Visibility
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec

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
    addFunction(
      FunSpec.builder("export_${prefix}_create_string")
        .addAnnotation(cNameAnnotation("${prefix}_create_string"))
        .addParameter("value", String::class)
        .returns(cOpaquePointer)
        .addStatement("return %T.create(%L(value)).asCPointer()", stableRef, qualifiedName)
        .build()
    )

    addFunction(
      FunSpec.builder("export_${prefix}_create_byte")
        .addAnnotation(cNameAnnotation("${prefix}_create_byte"))
        .addParameter("value", Byte::class)
        .returns(cOpaquePointer)
        .addStatement("return %T.create(%L(value)).asCPointer()", stableRef, qualifiedName)
        .build()
    )

    addFunction(
      FunSpec.builder("export_${prefix}_create_ubyte")
        .addAnnotation(cNameAnnotation("${prefix}_create_ubyte"))
        .addParameter("value", UByte::class)
        .returns(cOpaquePointer)
        .addStatement("return %T.create(%L(value)).asCPointer()", stableRef, qualifiedName)
        .build()
    )

    addFunction(
      FunSpec.builder("export_${prefix}_create_short")
        .addAnnotation(cNameAnnotation("${prefix}_create_short"))
        .addParameter("value", Short::class)
        .returns(cOpaquePointer)
        .addStatement("return %T.create(%L(value)).asCPointer()", stableRef, qualifiedName)
        .build()
    )

    addFunction(
      FunSpec.builder("export_${prefix}_create_ushort")
        .addAnnotation(cNameAnnotation("${prefix}_create_ushort"))
        .addParameter("value", UShort::class)
        .returns(cOpaquePointer)
        .addStatement("return %T.create(%L(value)).asCPointer()", stableRef, qualifiedName)
        .build()
    )

    addFunction(
      FunSpec.builder("export_${prefix}_create_int")
        .addAnnotation(cNameAnnotation("${prefix}_create_int"))
        .addParameter("value", Int::class)
        .returns(cOpaquePointer)
        .addStatement("return %T.create(%L(value)).asCPointer()", stableRef, qualifiedName)
        .build()
    )

    addFunction(
      FunSpec.builder("export_${prefix}_create_uint")
        .addAnnotation(cNameAnnotation("${prefix}_create_uint"))
        .addParameter("value", UInt::class)
        .returns(cOpaquePointer)
        .addStatement("return %T.create(%L(value)).asCPointer()", stableRef, qualifiedName)
        .build()
    )

    addFunction(
      FunSpec.builder("export_${prefix}_create_long")
        .addAnnotation(cNameAnnotation("${prefix}_create_long"))
        .addParameter("value", Long::class)
        .returns(cOpaquePointer)
        .addStatement("return %T.create(%L(value)).asCPointer()", stableRef, qualifiedName)
        .build()
    )

    addFunction(
      FunSpec.builder("export_${prefix}_create_ulong")
        .addAnnotation(cNameAnnotation("${prefix}_create_ulong"))
        .addParameter("value", ULong::class)
        .returns(cOpaquePointer)
        .addStatement("return %T.create(%L(value)).asCPointer()", stableRef, qualifiedName)
        .build()
    )

    addFunction(
      FunSpec.builder("export_${prefix}_create_float")
        .addAnnotation(cNameAnnotation("${prefix}_create_float"))
        .addParameter("value", Float::class)
        .returns(cOpaquePointer)
        .addStatement("return %T.create(%L(value)).asCPointer()", stableRef, qualifiedName)
        .build()
    )

    addFunction(
      FunSpec.builder("export_${prefix}_create_double")
        .addAnnotation(cNameAnnotation("${prefix}_create_double"))
        .addParameter("value", Double::class)
        .returns(cOpaquePointer)
        .addStatement("return %T.create(%L(value)).asCPointer()", stableRef, qualifiedName)
        .build()
    )

    addFunction(
      FunSpec.builder("export_${prefix}_create_bool")
        .addAnnotation(cNameAnnotation("${prefix}_create_bool"))
        .addParameter("value", Boolean::class)
        .returns(cOpaquePointer)
        .addStatement("return %T.create(%L(value)).asCPointer()", stableRef, qualifiedName)
        .build()
    )
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

  addFunction(
    FunSpec.builder("export_${prefix}_create_object")
      .addAnnotation(cNameAnnotation("${prefix}_create_object"))
      .addParameter("value", cOpaquePointer)
      .returns(cOpaquePointer)
      .addStatement(
        "return %T.create(%L($castExpr)).asCPointer()",
        stableRef, qualifiedName,
      )
      .build()
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
