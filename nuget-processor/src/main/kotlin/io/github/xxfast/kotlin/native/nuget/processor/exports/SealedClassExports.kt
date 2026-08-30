package io.github.xxfast.kotlin.native.nuget.processor.exports

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
import io.github.xxfast.kotlin.native.nuget.processor.cir.expandAliases
import io.github.xxfast.kotlin.native.nuget.processor.forward.handleBody
import io.github.xxfast.kotlin.native.nuget.processor.forward.nullableHandleBody
import io.github.xxfast.kotlin.native.nuget.processor.forward.valueBody

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
      val propTypeResolved: KSType = prop.type.resolve().expandAliases()
      val propType: String = propTypeResolved.declaration.qualifiedName?.asString() ?: "Any"
      val isNullable: Boolean = propTypeResolved.isMarkedNullable
      val access = "handle.asStableRef<$subQualifiedName>().get().$propName"

      val isEnumType: Boolean = (propTypeResolved.declaration as? KSClassDeclaration)
        ?.classKind == ClassKind.ENUM_CLASS

      val isPrimitiveType: Boolean = propType in setOf(
        "kotlin.String", "kotlin.Byte", "kotlin.UByte", "kotlin.Short",
        "kotlin.UShort", "kotlin.Int", "kotlin.UInt", "kotlin.Long",
        "kotlin.ULong", "kotlin.Float", "kotlin.Double", "kotlin.Boolean",
        "kotlin.Unit",
      )

      // Issue #38: a nullable non-String primitive has no spare wire value to spell "absent", so
      // it takes the same ADR-002 two-call pair (`_has_value` + `_value`) the top-level property
      // path takes (ForwardPropertyKotlinEmitter's LegacyTwoCall getter). A nullable `String`
      // needs no pair: the null pointer is its own presence bit.
      val isNullablePrimitiveTwoCall: Boolean =
        isPrimitiveType && isNullable && propType != "kotlin.String" && !isEnumType

      if (isEnumType) {
        addFunction(
          sealedPropertyGetter(subPrefix, propName)
            .returns(Int::class)
            .addCode(
              valueBody("$access.ordinal", "errorOut", "0"),
              cOpaquePointerVar, stableRef,
            )
            .build()
        )
      } else if (isNullablePrimitiveTwoCall) {
        addFunction(
          sealedPropertyGetter(subPrefix, "${propName}_has_value")
            .returns(Boolean::class)
            .addCode(
              valueBody("$access != null", "errorOut", "false"),
              cOpaquePointerVar, stableRef,
            )
            .build()
        )
        addFunction(
          sealedPropertyGetter(subPrefix, "${propName}_value")
            .returns(ClassName.bestGuess(propType))
            .addCode(
              valueBody("$access!!", "errorOut", defaultValueFor(propType)),
              cOpaquePointerVar, stableRef,
            )
            .build()
        )
      } else if (isPrimitiveType) {
        // Issue #38: the `?` was dropped here, so a `String?` property generated a `String`-typed
        // export whose body returned `String?` and the generated file did not compile at all.
        addFunction(
          sealedPropertyGetter(subPrefix, propName)
            .returns(ClassName.bestGuess(propType).copy(nullable = isNullable))
            .addCode(
              valueBody(access, "errorOut", if (isNullable) "null" else defaultValueFor(propType)),
              cOpaquePointerVar, stableRef,
            )
            .build()
        )
      } else {
        // The catch branch of both handle bodies ships a null pointer, so even the non-null
        // reference getter returns `COpaquePointer?` — the same widening the top-level property
        // emitter applies to an ObjectHandle getter.
        addFunction(
          sealedPropertyGetter(subPrefix, propName)
            .returns(cOpaquePointer.copy(nullable = true))
            .addCode(
              if (isNullable) nullableHandleBody(access, "errorOut") else handleBody(access, "errorOut"),
              stableRef, cOpaquePointerVar, stableRef,
            )
            .build()
        )
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

/**
 * Issue #38: every sealed-subclass property getter now carries the `errorOut` slot the top-level
 * property path carries (ADR-024/ADR-062), so a throwing getter reports through the same
 * convention instead of crossing the boundary as an unhandled Kotlin exception. [exportSuffix] is
 * the property name for a single-call getter, or the `_has_value` / `_value` suffixed name for the
 * nullable-primitive pair.
 */
private fun sealedPropertyGetter(subPrefix: String, exportSuffix: String): FunSpec.Builder =
  FunSpec.builder("export_${subPrefix}_get_$exportSuffix")
    .addAnnotation(cNameAnnotation("${subPrefix}_get_$exportSuffix"))
    .addParameter("handle", cOpaquePointer)
    .addParameter("errorOut", cOpaquePointer.copy(nullable = true))
