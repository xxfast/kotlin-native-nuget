package io.github.xxfast.nuget.processor.exports

import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Visibility
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec

/**
 * Generates @CName bridge exports for sealed classes: type discriminator,
 * subclass property getters, and data class methods for data class subclasses.
 *
 * @see <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/009-sealed-class-mapping.md">ADR-009: Sealed class mapping</a>
 */
internal fun FileSpec.Builder.addSealedClassExports(sealed: KSClassDeclaration) {
  val name: String = sealed.simpleName.asString()
  val qualifiedName: String = sealed.qualifiedName?.asString() ?: return
  val prefix: String = name.lowercase()

  val subclasses: List<KSClassDeclaration> = sealed.getSealedSubclasses().toList()

  addFunction(
    FunSpec.builder("export_${prefix}_get_type")
      .addAnnotation(cNameAnnotation("${prefix}_get_type"))
      .addParameter("handle", cOpaquePointer)
      .returns(Int::class)
      .addStatement("val obj: %L = handle.asStableRef<%L>().get()", qualifiedName, qualifiedName)
      .addStatement("return when (obj) {")
      .apply {
        for ((index, subclass) in subclasses.withIndex()) {
          val subQualifiedName: String = subclass.qualifiedName?.asString() ?: continue
          addStatement("    is %L -> %L", subQualifiedName, index)
        }
      }
      .addStatement("}")
      .build()
  )

  for (subclass in subclasses) {
    val subName: String = subclass.simpleName.asString()
    val subQualifiedName: String = subclass.qualifiedName?.asString() ?: continue
    val subPrefix: String = "${prefix}_${subName.lowercase()}"
    val isDataClass: Boolean = subclass.modifiers.contains(Modifier.DATA)

    addFunction(
      FunSpec.builder("export_${subPrefix}_dispose")
        .addAnnotation(cNameAnnotation("${subPrefix}_dispose"))
        .addParameter("handle", cOpaquePointer)
        .addStatement("handle.asStableRef<%L>().dispose()", subQualifiedName)
        .build()
    )

    val properties: List<KSPropertyDeclaration> = subclass.getAllProperties()
      .filter { it.getVisibility() == Visibility.PUBLIC }
      .toList()

    for (prop in properties) {
      val propName: String = prop.simpleName.asString()
      val propTypeResolved: KSType = prop.type.resolve()
      val propType: String = propTypeResolved.declaration.qualifiedName?.asString() ?: "Any"
      val isNullable: Boolean = propTypeResolved.isMarkedNullable

      val isEnumType: Boolean = (propTypeResolved.declaration as? KSClassDeclaration)
        ?.classKind == ClassKind.ENUM_CLASS

      val isPrimitiveType: Boolean = propType in setOf(
        "kotlin.String", "kotlin.Byte", "kotlin.UByte", "kotlin.Short",
        "kotlin.UShort", "kotlin.Int", "kotlin.UInt", "kotlin.Long",
        "kotlin.ULong", "kotlin.Float", "kotlin.Double", "kotlin.Boolean",
        "kotlin.Unit",
      )

      if (isEnumType) {
        addFunction(
          FunSpec.builder("export_${subPrefix}_get_$propName")
            .addAnnotation(cNameAnnotation("${subPrefix}_get_$propName"))
            .addParameter("handle", cOpaquePointer)
            .returns(Int::class)
            .addStatement(
              "return handle.asStableRef<%L>().get().%L.ordinal",
              subQualifiedName, propName,
            )
            .build()
        )
      } else if (isPrimitiveType) {
        addFunction(
          FunSpec.builder("export_${subPrefix}_get_$propName")
            .addAnnotation(cNameAnnotation("${subPrefix}_get_$propName"))
            .addParameter("handle", cOpaquePointer)
            .returns(ClassName.bestGuess(propType))
            .addStatement(
              "return handle.asStableRef<%L>().get().%L",
              subQualifiedName, propName,
            )
            .build()
        )
      } else {
        if (isNullable) {
          addFunction(
            FunSpec.builder("export_${subPrefix}_get_$propName")
              .addAnnotation(cNameAnnotation("${subPrefix}_get_$propName"))
              .addParameter("handle", cOpaquePointer)
              .returns(cOpaquePointer.copy(nullable = true))
              .addStatement(
                "val obj: %L? = handle.asStableRef<%L>().get().%L",
                propType, subQualifiedName, propName,
              )
              .addStatement(
                "return if (obj == null) null else %T.create(obj).asCPointer()",
                stableRef,
              )
              .build()
          )
        } else {
          addFunction(
            FunSpec.builder("export_${subPrefix}_get_$propName")
              .addAnnotation(cNameAnnotation("${subPrefix}_get_$propName"))
              .addParameter("handle", cOpaquePointer)
              .returns(cOpaquePointer)
              .addStatement(
                "return %T.create(handle.asStableRef<%L>().get().%L).asCPointer()",
                stableRef, subQualifiedName, propName,
              )
              .build()
          )
        }
      }
    }

    if (isDataClass) {
      addFunction(
        FunSpec.builder("export_${subPrefix}_equals")
          .addAnnotation(cNameAnnotation("${subPrefix}_equals"))
          .addParameter("handle", cOpaquePointer)
          .addParameter("other", cOpaquePointer)
          .returns(Boolean::class)
          .addStatement(
            "return handle.asStableRef<%L>().get() == other.asStableRef<%L>().get()",
            subQualifiedName, subQualifiedName,
          )
          .build()
      )

      addFunction(
        FunSpec.builder("export_${subPrefix}_hashcode")
          .addAnnotation(cNameAnnotation("${subPrefix}_hashcode"))
          .addParameter("handle", cOpaquePointer)
          .returns(Int::class)
          .addStatement(
            "return handle.asStableRef<%L>().get().hashCode()",
            subQualifiedName,
          )
          .build()
      )

      addFunction(
        FunSpec.builder("export_${subPrefix}_tostring")
          .addAnnotation(cNameAnnotation("${subPrefix}_tostring"))
          .addParameter("handle", cOpaquePointer)
          .returns(String::class)
          .addStatement(
            "return handle.asStableRef<%L>().get().toString()",
            subQualifiedName,
          )
          .build()
      )
    }
  }
}
