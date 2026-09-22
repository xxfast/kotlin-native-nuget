package io.github.xxfast.kotlin.native.nuget.processor

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration

/**
 * ADR-163: the single source of every forward C entry point's leading qualification.
 *
 * Every generated `@CName` is `<lib>_<package>__<owner chain>_<member>[_<n>]`, always:
 *  - `<lib>` is the sanitised `nuget.libraryName` ([sanitizeLibrarySegment]). It is never empty, so
 *    no generated symbol is ever a bare user identifier. That is what makes a top-level
 *    `fun signal(dbm: Int)` bind: `ld.lld`'s MinGW auto-exporter silently drops any exported name
 *    that an import library on the link line already exports (`signal`, `read`, `qsort`, `Beep`),
 *    and the match is exact, so `<lib>_signal` is safe by mechanism rather than by luck.
 *  - `<package>` is the declaring Kotlin package RELATIVE to `rootPackage` when it is under it
 *    (the same segment-bounded [isUnderPackage] rule admission and `mapPackageToNamespace` use),
 *    else the whole package; lowercased with `.` replaced by `_`. It is omitted together with its
 *    separator when empty, so a root-package declaration reads `<lib>_kitten_create`.
 *  - `__` (a double underscore) separates the package part from the owner chain, so `a.b` + `C`
 *    (`a_b__c`) and `a` + `B.C` (`a__b_c`) differ. Kotlin identifiers may themselves contain `_`,
 *    so this is best-effort rather than a JNI-grade escape: ADR-117's collision diagnostic stays
 *    as the loud backstop for the residue.
 *
 * Built once in [NugetProcessor] and handed to the planners, the CIR translators and the legacy
 * `exports/` builders, because the routes that mint a symbol do not share a code path: three
 * families (the raw sealed prefixes, the bare top-level names, the non-class extension receiver
 * fallback) bypassed `nativePrefix()` entirely. A table every route asks means they cannot drift
 * into disagreeing about one symbol, which would fail `ForwardAbiContract`'s generator-bug check.
 *
 * Per-declaration and memoized on the package part rather than recomputed: the qualifier is a pure
 * function of the declaring package, and one library has few packages but many declarations.
 */
internal class ForwardSymbolTable(
  /** The sanitised library segment, already checked against [RESERVED_LIBRARY_SEGMENT]. */
  val librarySegment: String,
  private val rootPackage: String,
) {
  private val qualifiers: MutableMap<String, String> = mutableMapOf()

  /**
   * `<lib>_` for the root package, `<lib>_<relative package>__` for anything under or outside it.
   */
  fun qualifier(packageName: String): String = qualifiers.getOrPut(packageName) {
    val underRoot: Boolean = rootPackage.isNotEmpty() && isUnderPackage(packageName, rootPackage)
    val relative: String = if (underRoot) {
      packageName.removePrefix(rootPackage).removePrefix(".")
    } else {
      packageName
    }
    val segment: String = relative.lowercase().replace('.', '_')
    if (segment.isEmpty()) "${librarySegment}_" else "${librarySegment}_${segment}__"
  }

  /** The qualifier of whatever file [declaration] was declared in. */
  fun qualifier(declaration: KSDeclaration): String =
    qualifier(declaration.packageName.asString())

  /**
   * The fully qualified entry-point prefix of a class-like owner: its own package's qualifier plus
   * the ADR-133 enclosing chain of lowercased simple names. Every member, role and nested arm
   * composes on top of this.
   */
  fun owner(declaration: KSClassDeclaration): String =
    qualifier(declaration) + ownerChain(declaration)

  /**
   * A top-level callable's entry point: its own package's qualifier plus its C-escaped name. The
   * `toCName` escape lives here so no route can mint the symbol from the raw Kotlin name (two
   * `exports/GenericFunctionExports.kt` sites did, and agreed with their C# twin only while the
   * name was not a C keyword).
   */
  fun topLevel(
    declaration: KSDeclaration,
    name: String = declaration.simpleName.asString(),
  ): String = qualifier(declaration) + toCName(name)

  /**
   * An extension callable's entry point. The package part is the EXTENSION's own package, not the
   * receiver's, matching ADR-095's `(package, name)` overload-counter scope: two extensions of the
   * same name on one receiver, declared in two packages, must not converge. The owner chain is the
   * receiver's, unqualified, so `a.Mood.pounce` reads `<lib>_a__mood_get_pounce`.
   */
  fun extension(declaration: KSDeclaration, receiverPrefix: String, member: String): String =
    qualifier(declaration) + receiverPrefix + "_" + member

  /**
   * The C#-identifier form of the same package qualification, for the few generated helper TYPE
   * names that live in the ROOT namespace and therefore cannot be separated by a namespace:
   * `{Iface}BridgeState` (ADR-088). Relative package segments, each capitalised and concatenated,
   * empty for the root package, so a root-package `Pet` keeps its shipped `PetBridgeState`.
   */
  fun csharpQualifier(declaration: KSDeclaration): String {
    val qualified: String = qualifier(declaration)
    val packagePart: String = qualified
      .removePrefix("${librarySegment}_")
      .removeSuffix("__")
    if (packagePart.isEmpty()) return ""
    return packagePart.split("_")
      .filter { segment -> segment.isNotEmpty() }
      .joinToString("") { segment -> segment.replaceFirstChar { it.uppercase() } }
  }

  /**
   * ADR-163: [exportName] with THIS table's own qualification removed, and returned unchanged when
   * it does not carry it.
   *
   * For the few C# identifiers that are (historically) folded out of the C symbol rather than out
   * of the declaration: the property projection's `Native_ChartrefGetPatientName` extern names.
   * Leaving them to read the qualified symbol is the defect memo finding 11 found on the legacy
   * top-level route, where a public C# method silently renamed itself to `Testlib_gen_b__Make`
   * because its name was derived from the entry point.
   *
   * Exact rather than heuristic: it strips only a leading `<librarySegment>_` it actually finds,
   * so a hand-built plan (every unit test that constructs a `ForwardNativeCall` itself) is a fixed
   * point instead of silently losing its first segment.
   */
  fun stem(exportName: String): String {
    val head = "${librarySegment}_"
    if (!exportName.startsWith(head)) return exportName
    val rest: String = exportName.removePrefix(head)
    val cut: Int = rest.indexOf("__")
    return if (cut >= 0) rest.substring(cut + 2) else rest
  }

  companion object {

    /**
     * ADR-133's chain, UNQUALIFIED: the whole enclosing chain of simple names, each lowercased and
     * `_`-joined (`owner_nested`, `owner_middle_inner`). Only the table composes with it, so no
     * route can accidentally emit a chain with no library segment in front of it.
     */
    fun ownerChain(declaration: KSClassDeclaration): String =
      generateSequence<KSDeclaration>(declaration) { it.parentDeclaration }
        .takeWhile { it is KSClassDeclaration }
        .map { it.simpleName.asString().lowercase() }
        .toList()
        .asReversed()
        .joinToString("_")
  }
}
