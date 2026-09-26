package io.github.xxfast.kotlin.native.nuget.processor.cir

import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Visibility
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnostic
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticSink
import io.github.xxfast.kotlin.native.nuget.processor.kotlinConstantToPascalCase

/**
 * ADR-110's CS0102 rule and its CS0108 extension, shared by every generated C# type that holds
 * both properties and methods (ROADMAP line 32).
 *
 * Kotlin keeps properties and functions in separate namespaces; C# gives a type one member
 * namespace, where only methods may share a name (overloads). So `val count` beside `fun count()`
 * renders `Count { get; }` beside `Count()` and is CS0102, and a declared member taking the C#
 * name of an INHERITED member of the other kind is CS0108 hiding, which `nugetCompileInterop`
 * (ADR-138) and every consumer building with warnings as errors treat as a failure. Worse, when a
 * derived method hides an inherited property, the property stops being readable through the
 * derived type at all (`n.Value` is CS0428).
 *
 * The guard runs over the PROJECTED member lists, never over declarations: issue #112's shipped
 * `val collarTag` beside an unbridgeable `fun collarTag(code: Int)` only ever renders one
 * `CollarTag` and must keep building (ADR-113 Decision E).
 *
 * Fatal, never a rename or a skip, for ADR-110's reasons: a rename is a silently different API, and
 * a planned callable cannot be exported from Kotlin and dropped from the C# (ADR-055).
 */
internal enum class CsMemberKind { VALUE, METHOD }

/** One public C# member name a type declares, and whether it is a property/const or invocable. */
internal data class CsMemberName(val name: String, val kind: CsMemberKind)

/**
 * The public names a projected member list renders. `CirDllImport`s are all `Native_*` (never an
 * authored spelling) and are left out; a callback route renders exactly one public method, its
 * `csMethodName` (the remove half is a private extern).
 */
internal fun List<CirMember>.csMemberNames(): List<CsMemberName> = mapNotNull { member ->
  when (member) {
    is CirProperty -> CsMemberName(member.name, CsMemberKind.VALUE)
    is CirConst -> CsMemberName(member.name, CsMemberKind.VALUE)
    is CirMethod -> CsMemberName(member.name, CsMemberKind.METHOD)
    is CirCallbackMethod -> CsMemberName(member.csMethodName, CsMemberKind.METHOD)
    is CirStoredCallbackMethod -> CsMemberName(member.csMethodName, CsMemberKind.METHOD)
    is CirInterfaceBridgeMethod -> CsMemberName(member.csMethodName, CsMemberKind.METHOD)
    is CirDllImport -> null
  }
}

/** A Kotlin declaration as the author wrote it, so a message names something they can search. */
internal data class KotlinSpelling(
  val keyword: String,
  val name: String,
  val onCompanion: Boolean = false,
) {
  val isFunction: Boolean get() = keyword == "fun"

  fun describe(): String {
    val code: String = if (isFunction) "`fun $name()`" else "`$keyword $name`"
    return if (onCompanion) "$code on the companion" else code
  }
}

/**
 * The Kotlin declarations behind each rendered C# name. The CIR model carries only C# names, so a
 * route records spellings beside its projection (or indexes its declaration, [ofClass]).
 */
internal class KotlinSpellings {
  private val byCsName: MutableMap<String, MutableList<KotlinSpelling>> = linkedMapOf()

  fun record(csName: String, spelling: KotlinSpelling) {
    val spellings: MutableList<KotlinSpelling> = byCsName.getOrPut(csName) { mutableListOf() }
    if (spelling !in spellings) spellings.add(spelling)
  }

  operator fun get(csName: String): List<KotlinSpelling> = byCsName[csName].orEmpty()

  fun recordProperty(prop: KSPropertyDeclaration, onCompanion: Boolean = false) {
    val name: String = prop.simpleName.asString()
    if (prop.modifiers.contains(Modifier.CONST)) {
      record(name.kotlinConstantToPascalCase(), KotlinSpelling("const val", name, onCompanion))
      return
    }
    val keyword: String = if (prop.isMutable) "var" else "val"
    record(name.replaceFirstChar { it.uppercase() }, KotlinSpelling(keyword, name, onCompanion))
  }

  fun recordFunction(function: KSFunctionDeclaration, onCompanion: Boolean = false) {
    val name: String = function.simpleName.asString()
    val csName: String = name.replaceFirstChar { it.uppercase() }
    val spelling = KotlinSpelling("fun", name, onCompanion)
    record(csName, spelling)
    // The suspend route renders `{Name}Async`.
    if (function.modifiers.contains(Modifier.SUSPEND)) record("${csName}Async", spelling)
  }

  companion object {
    /** Every public property and function of [cls] and of its companion, by rendered C# name. */
    fun ofClass(cls: KSClassDeclaration): KotlinSpellings {
      val spellings = KotlinSpellings()
      fun index(owner: KSClassDeclaration, onCompanion: Boolean) {
        owner.getAllProperties()
          .filter { it.getVisibility() == Visibility.PUBLIC }
          .forEach { spellings.recordProperty(it, onCompanion) }
        owner.getAllFunctions()
          .filter { it.getVisibility() == Visibility.PUBLIC }
          .filter { it.simpleName.asString() != "<init>" }
          .forEach { spellings.recordFunction(it, onCompanion) }
      }
      index(cls, onCompanion = false)
      cls.declarations
        .filterIsInstance<KSClassDeclaration>()
        .firstOrNull { it.isCompanionObject }
        ?.let { companion -> index(companion, onCompanion = true) }
      return spellings
    }
  }
}

/** One C# name held by a property/const and at least one other member of the same type. */
internal data class CsNameCollision(
  val name: String,
  val values: List<KotlinSpelling>,
  val methods: List<KotlinSpelling>,
) {
  val spellings: List<KotlinSpelling> get() = values + methods

  /** `both` for a pair, `all` for three or more, so the reason reads as English. */
  val quantifier: String get() = if (spellings.size > 2) "all" else "both"

  /** The declarations, or the C# name when no spelling was recorded for it. */
  fun declarations(): String = spellings
    .takeIf { it.isNotEmpty() }
    ?.joinToString(" and ") { it.describe() }
    ?: "two members named '$name'"
}

/**
 * Rule 1 (CS0102): a C# name held by a VALUE member and by any other member of the same type.
 * Method against method stays ADR-034's signature guard ([emitCsharpSignatureCollisions]).
 *
 * @param ownerPhrase how the reason names the owner (`class Counter`, `sealed class Shape`).
 */
internal fun emitMemberNameCollisions(
  container: String,
  ownerPhrase: String,
  symbol: KSNode?,
  members: List<CsMemberName>,
  spellings: KotlinSpellings,
  logger: KSPLogger,
  reason: (CsNameCollision) -> String = { collision -> defaultReason(ownerPhrase, collision) },
  hint: (CsNameCollision) -> String = { defaultHint(ownerPhrase) },
  // A merged type (the top-level file class) has a different declaration behind each name.
  symbolFor: (String) -> KSNode? = { symbol },
) {
  val collisions: List<CsNameCollision> = members
    .groupBy { member -> member.name }
    .filter { (_, group) ->
      group.size > 1 && group.any { it.kind == CsMemberKind.VALUE }
    }
    .map { (name, _) ->
      val (methods, values) = spellings[name].partition { it.isFunction }
      CsNameCollision(name, values, methods)
    }

  collisions.forEach { collision ->
    ForwardDiagnosticSink.emit(
      listOf(
        ForwardDiagnostic(
          kind = ForwardDiagnosticKind.ERROR_CSHARP_NAME_COLLISION,
          symbol = symbolFor(collision.name),
          declaration = "$container.${collision.name}",
          reason = reason(collision),
          hint = hint(collision),
          // ERROR_*: the build fails before anything generated is read.
          owner = null,
        ),
      ),
      logger,
    )
  }
}

private fun defaultReason(ownerPhrase: String, collision: CsNameCollision): String {
  val what: String = if (collision.methods.isEmpty()) "two members" else "a property and a method"
  return "$ownerPhrase declares ${collision.declarations()}, which ${collision.quantifier} " +
      "render the C# name '${collision.name}', and C# cannot declare $what with one name on " +
      "one type (CS0102)"
}

private fun defaultHint(ownerPhrase: String): String =
  "rename one of them on $ownerPhrase; a property, a `const val` and a function all render " +
      "PascalCase in C#, and a companion's members land on the same C# type (ADR-110)"

/**
 * Rule 2 (CS0108) runs after every class has translated, because a base can translate after its
 * subclass. Each class-shaped route registers the names it rendered and the base it kept; the
 * post-pass walks each kept-base chain.
 */
internal class CsMemberRegistry {
  data class Entry(
    val qualifiedName: String,
    val csName: String,
    val ownerPhrase: String,
    val symbol: KSNode,
    val keptBase: String?,
    val members: List<CsMemberName>,
    val spellings: KotlinSpellings,
  )

  private val entries: MutableMap<String, Entry> = linkedMapOf()

  fun register(entry: Entry) {
    entries[entry.qualifiedName] = entry
  }

  /** A declared member whose C# name an ancestor gives to a member of the other kind. */
  fun emitInheritedCollisions(logger: KSPLogger) {
    entries.values.forEach { entry ->
      // A name the type already holds both ways is rule 1's, reported at the type.
      val ownMixed: Set<String> = entry.members
        .groupBy { it.name }
        .filterValues { group -> group.map { it.kind }.toSet().size > 1 }
        .keys
      val reported: MutableSet<String> = mutableSetOf()
      val visited: MutableSet<String> = mutableSetOf(entry.qualifiedName)
      var ancestor: Entry? = entry.keptBase?.let { entries[it] }
      while (ancestor != null && visited.add(ancestor.qualifiedName)) {
        val base: Entry = ancestor
        entry.members
          .filter { it.name !in ownMixed && it.name !in reported }
          .forEach { declared ->
            val inherited: CsMemberName = base.members.firstOrNull { candidate ->
              candidate.name == declared.name && candidate.kind != declared.kind
            } ?: return@forEach
            reported.add(declared.name)
            emitInherited(entry, base, declared, inherited, logger)
          }
        ancestor = base.keptBase?.let { entries[it] }
      }
    }
  }

  private fun emitInherited(
    entry: Entry,
    base: Entry,
    declared: CsMemberName,
    inherited: CsMemberName,
    logger: KSPLogger,
  ) {
    val name: String = declared.name
    fun spelled(owner: Entry, kind: CsMemberKind): String = owner.spellings[name]
      .filter { it.isFunction == (kind == CsMemberKind.METHOD) }
      .takeIf { it.isNotEmpty() }
      ?.joinToString(" and ") { it.describe() }
      ?: "'$name'"
    val consequence: String = if (declared.kind == CsMemberKind.METHOD) {
      "the inherited property is no longer readable through ${entry.csName} (CS0428 at the " +
          "consumer) and C# reports the hiding as CS0108"
    } else {
      "C# reports the hiding as CS0108"
    }
    ForwardDiagnosticSink.emit(
      listOf(
        ForwardDiagnostic(
          kind = ForwardDiagnosticKind.ERROR_CSHARP_NAME_COLLISION,
          symbol = entry.symbol,
          declaration = "${entry.csName}.$name",
          reason = "${entry.ownerPhrase} declares ${spelled(entry, declared.kind)}, which " +
              "renders the C# name '$name' that its base ${base.ownerPhrase} already gives to " +
              "${spelled(base, inherited.kind)}; $consequence, which fails `nugetCompileInterop` " +
              "and every consumer build that treats warnings as errors",
          hint = "rename the member on ${entry.csName} or on ${base.csName}; a C# type shares " +
              "one member namespace with its base, where only methods may reuse a name " +
              "(ADR-110)",
          owner = null,
        ),
      ),
      logger,
    )
  }
}
