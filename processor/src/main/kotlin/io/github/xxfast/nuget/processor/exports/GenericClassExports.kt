package io.github.xxfast.nuget.processor.exports

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

  addFunction(
    FunSpec.builder("export_${prefix}_create_string")
      .addAnnotation(cNameAnnotation("${prefix}_create_string"))
      .addParameter("value", String::class)
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
    FunSpec.builder("export_${prefix}_create_long")
      .addAnnotation(cNameAnnotation("${prefix}_create_long"))
      .addParameter("value", Long::class)
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

  addFunction(
    FunSpec.builder("export_${prefix}_create_object")
      .addAnnotation(cNameAnnotation("${prefix}_create_object"))
      .addParameter("value", cOpaquePointer)
      .returns(cOpaquePointer)
      .addStatement(
        "return %T.create(%L(value.asStableRef<Any>().get())).asCPointer()",
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
    FunSpec.builder("export_nuget_unwrap_int")
      .addAnnotation(cNameAnnotation("nuget_unwrap_int"))
      .addParameter("handle", cOpaquePointer)
      .returns(Int::class)
      .addStatement("return handle.asStableRef<Any>().get() as Int")
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
