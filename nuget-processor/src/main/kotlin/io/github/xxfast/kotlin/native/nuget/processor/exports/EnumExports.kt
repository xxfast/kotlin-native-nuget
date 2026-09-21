package io.github.xxfast.kotlin.native.nuget.processor.exports

import io.github.xxfast.kotlin.native.nuget.processor.ForwardSymbolTable

import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Visibility
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.INT
import io.github.xxfast.kotlin.native.nuget.processor.cir.expandAliases
import io.github.xxfast.kotlin.native.nuget.processor.cir.nativePrefix

/**
 * Generates @CName bridge exports for enum properties.
 * Takes ordinal as Int, converts to enum entry, and accesses the property.
 *
 * @see <a href="https://github.com/xxfast/kotlin-native-nuget/blob/main/docs/adr/006-enum-mapping.md">ADR-006: Enum mapping</a>
 */
internal fun FileSpec.Builder.addEnumExports(
  enum: KSClassDeclaration,
  /** ADR-163: the one symbol table. */
  symbols: ForwardSymbolTable,
) {
  val name: String = enum.simpleName.asString()
  val qualifiedName: String = enum.qualifiedName?.asString() ?: return
  val prefix: String = enum.nativePrefix(symbols)

  // ADR-163 qualifies the C entry point, not the generated Kotlin body's local names: the receiver
  // local keeps the unqualified owner chain, so the body still reads `swirl.patch` instead of
  // `library_tier1_enumtypedenummember__swirl.patch`.
  val local: String = ForwardSymbolTable.ownerChain(enum)

  val properties: List<KSPropertyDeclaration> = enum.getAllProperties()
    .filter { it.getVisibility() == Visibility.PUBLIC }
    .filter { it.simpleName.asString() !in setOf("name", "ordinal", "declaringJavaClass") }
    .toList()

  for (prop in properties) {
    val propName: String = prop.simpleName.asString()
    val propResolved: KSType = prop.type.resolve().expandAliases()
    val propType: String = propResolved.declaration.qualifiedName
      ?.asString() ?: "Any"

    // An enum-typed member of an enum (`enum class Swirl(val patch: Patch)`) lowers to the ordinal
    // like every other ADR-006 enum position. Returning the Kotlin enum object itself gave C# an
    // `IntPtr` no consumer could turn back into a `Patch` (fixed alongside ADR-157).
    val isEnumTyped: Boolean =
      (propResolved.declaration as? KSClassDeclaration)?.classKind == ClassKind.ENUM_CLASS

    addFunction(
      FunSpec.builder("export_${prefix}_get_$propName")
        .addAnnotation(cNameAnnotation("${prefix}_get_$propName", ownedBy(prop)))
        .addParameter("ordinal", Int::class)
        .returns(if (isEnumTyped) INT else ClassName.bestGuess(propType))
        .addStatement("val ${local}: %L = %L.entries[ordinal]", qualifiedName, qualifiedName)
        .addStatement("return ${local}.$propName${if (isEnumTyped) ".ordinal" else ""}")
        .build()
    )
  }
}
