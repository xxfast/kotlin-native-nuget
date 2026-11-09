package io.github.xxfast.kotlin.native.nuget.processor.cir

import com.google.devtools.ksp.symbol.KSClassDeclaration
import io.github.xxfast.kotlin.native.nuget.processor.forward.forwardSuperClass

/**
 * ADR-116 amendment (2026-11-09, issue #613): a C# extern's identity for hiding, its name and its
 * parameter types. C# hides by signature, not by name: `Native_Create(int, out IntPtr)` beside a
 * base's `Native_Create(string?, out IntPtr)` is an overload, and `new` on it is CS0109. The return
 * type and any `[MarshalAs]` are not part of it.
 */
internal data class CirExternSignature(val name: String, val parameterTypes: List<String>)

/**
 * The externs one class body declares, and the enclosing-base signatures they must hide.
 *
 * A class nested in an exported base it also derives from (`Fruit.Apple : Fruit`) sees that base's
 * `private static extern`s, so redeclaring one with the same signature is CS0108 under warnings as
 * errors. Every extern-emitting site of the ordinary class renderer goes through [declare], which
 * records the signature (so the class's own nested subclasses can hide it in turn) and answers the
 * `new ` modifier.
 */
internal class CirExternScope(private val hidden: Set<CirExternSignature>) {
  private val declared: MutableSet<CirExternSignature> = mutableSetOf()

  val externs: Set<CirExternSignature> get() = declared.toSet()

  /** Records the extern [name] over the rendered [parameters], and returns `"new "` or `""`. */
  fun declare(name: String, parameters: List<String>): String {
    val signature = CirExternSignature(name, parameters.map(::externParameterType))
    declared += signature
    return if (signature in hidden) "new " else ""
  }
}

/** An enclosing class on the render stack, keyed by its Kotlin name, with the externs it declared. */
internal class CirEnclosingExterns(val kotlinName: String?, val externs: Set<CirExternSignature>)

/** The signature set a class keyed by [hidesExternsOf] must hide, out of the [enclosing] stack. */
internal fun List<CirEnclosingExterns>.hiddenBy(
  hidesExternsOf: Set<String>,
): Set<CirExternSignature> =
  filter { enclosing -> enclosing.kotlinName in hidesExternsOf }
    .flatMap { enclosing -> enclosing.externs }
    .toSet()

private val PARAMETER_ATTRIBUTE = Regex("""^\[[^\]]*]\s*""")

/** `[MarshalAs(UnmanagedType.I1)] out bool flag` -> `out bool`: the type of one declared slot. */
internal fun externParameterType(declaration: String): String =
  declaration.trim().replace(PARAMETER_ATTRIBUTE, "").substringBeforeLast(' ')

/**
 * One `private static extern` line at the class-member depth, `new` when [externs] says it hides an
 * enclosing base's extern.
 */
internal fun StringBuilder.appendExtern(
  externs: CirExternScope?,
  returnType: String,
  name: String,
  parameters: List<String>,
  visibility: String = "private",
) {
  val hides: String = externs?.declare(name, parameters).orEmpty()
  appendLine(
    "        $visibility ${hides}static extern $returnType $name(${parameters.joinToString(", ")});",
  )
}

/**
 * The Kotlin names of the exported bases of [this] whose externs it must hide: each kept base in
 * its chain that is also a lexical parent, so its privates are accessible here. Non-generic only,
 * mirroring the sealed route's `!generic`: a generic class hoists its externs into a carrier, so
 * neither a generic base's nor a generic subclass's collide in the class body. A top-level
 * subclass has no lexical parent and hides nothing (a `new` there is CS0109).
 */
internal fun KSClassDeclaration.enclosingKeptBases(
  superClass: KSClassDeclaration?,
  exportedTypes: Set<String>,
): Set<String> {
  if (typeParameters.isNotEmpty()) return emptySet()
  val parents: Set<String> = generateSequence(parentDeclaration) { it.parentDeclaration }
    .mapNotNull { parent -> parent.qualifiedName?.asString() }
    .toSet()
  if (parents.isEmpty()) return emptySet()
  return generateSequence(superClass) { base -> base.forwardSuperClass(exportedTypes) }
    .filter { base -> base.typeParameters.isEmpty() }
    .mapNotNull { base -> base.qualifiedName?.asString() }
    .filter { base -> base in parents }
    .toSet()
}
