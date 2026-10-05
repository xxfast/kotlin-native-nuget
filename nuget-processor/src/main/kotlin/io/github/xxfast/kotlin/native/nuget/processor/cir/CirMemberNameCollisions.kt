package io.github.xxfast.kotlin.native.nuget.processor.cir

import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Visibility
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnostic
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticSink
import io.github.xxfast.kotlin.native.nuget.processor.forward.csharpAsyncMemberName
import io.github.xxfast.kotlin.native.nuget.processor.forward.csharpMemberName
import io.github.xxfast.kotlin.native.nuget.processor.forward.declaredCSharpName
import io.github.xxfast.kotlin.native.nuget.processor.NESTED_DECLARATION_KINDS
import io.github.xxfast.kotlin.native.nuget.processor.forward.isEligibleSealedInterface
import io.github.xxfast.kotlin.native.nuget.processor.forward.isSealedSubclass
import io.github.xxfast.kotlin.native.nuget.processor.kotlinConstantToPascalCase
import io.github.xxfast.kotlin.native.nuget.processor.nestedDeclarationDeferral
import io.github.xxfast.kotlin.native.nuget.processor.nestedOwnerScopeCollision

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
    // ADR-188: not a member name of the static class. Two extension properties are only a clash on
    // the SAME receiver (`extension(A.Mood)` and `extension(B.Mood)` may both declare `Pounce`), so
    // `CirTranslator`'s extension-property loop checks them per receiver instead.
    is CirExtensionProperty -> null
  }
}

/** A Kotlin declaration as the author wrote it, so a message names something they can search. */
internal data class KotlinSpelling(
  val keyword: String,
  val name: String,
  val onCompanion: Boolean = false,
  /** ADR-179: the author's `@CSharpName`, so a declared name that still collides names it. */
  val declared: String? = null,
  /** Issue #464: the type declaring this member, when it was read from a dependency's klib. */
  val dependency: String? = null,
) {
  val isFunction: Boolean get() = keyword == "fun"

  fun describe(): String {
    val code: String = if (isFunction) "`fun $name()`" else "`$keyword $name`"
    val annotated: String = if (declared == null) code else "$code (`@CSharpName(\"$declared\")`)"
    return if (onCompanion) "$annotated on the companion" else annotated
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
    record(
      prop.csharpMemberName(),
      KotlinSpelling(
        keyword,
        name,
        onCompanion,
        prop.declaredCSharpName(),
        prop.parentDeclaration?.dependencyName(),
      ),
    )
  }

  fun recordFunction(function: KSFunctionDeclaration, onCompanion: Boolean = false) {
    val name: String = function.simpleName.asString()
    val spelling = KotlinSpelling(
      "fun",
      name,
      onCompanion,
      function.declaredCSharpName(),
      function.parentDeclaration?.dependencyName(),
    )
    record(function.csharpMemberName(), spelling)
    // The suspend route renders `{Name}Async`, or the declared name verbatim (ADR-179).
    if (function.modifiers.contains(Modifier.SUSPEND)) {
      record(function.csharpAsyncMemberName(), spelling)
    }
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
  hint: (CsNameCollision) -> String = { collision -> defaultHint(ownerPhrase, collision) },
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

private fun defaultHint(ownerPhrase: String, collision: CsNameCollision): String =
  csharpNameRemedy(
    rename = "rename one of them on $ownerPhrase",
    spellings = collision.spellings,
    target = "one",
    detail = "a property, a `const val` and a function all render PascalCase in C#, and a " +
        "companion's members land on the same C# type (ADR-110)",
  )

/**
 * Issue #464: a collision hint's [rename], then ADR-179's `@CSharpName` as the alternative, then
 * [detail]. The alternative is left out when every colliding declaration is a `const val`, whose C#
 * name ignores the annotation. A declaration that could carry it but was read from a klib (the
 * ADR-066 cross-module signal, `containingFile == null`) lives in a dependency module, which needs
 * the annotations-only plugin, so its type is named (a klib carries no module name).
 */
internal fun csharpNameRemedy(
  rename: String,
  spellings: List<KotlinSpelling>,
  target: String = "it",
  detail: String? = null,
): String {
  val tail: String = if (detail == null) "" else "; $detail"
  val annotatable: List<KotlinSpelling> = spellings.filter { it.keyword != "const val" }
  // No recorded spelling means only a bare C# name, which is still a member the annotation names.
  if (spellings.isNotEmpty() && annotatable.isEmpty()) return "$rename$tail"
  val remedy: String = "$rename, or give $target a different `@CSharpName` (ADR-179)$tail"
  val owners: List<String> = annotatable.mapNotNull { it.dependency }.distinct()
  if (owners.isEmpty()) return remedy
  val declared: String = if (owners.size == 1) {
    "${owners.single()} is declared in a dependency module, which needs"
  } else {
    "${owners.joinToString(" and ")} are declared in dependency modules, which each need"
  }
  return "$remedy; $declared the `io.github.xxfast.kotlin.native.nuget.annotations` plugin to use " +
      "the annotation (the main plugin adds `nuget-annotations` only to the module that applies it)"
}

/** Issue #464: this declaration's qualified name when it was read from a klib, else null. */
internal fun KSDeclaration.dependencyName(): String? {
  if (containingFile != null) return null
  return qualifiedName?.asString() ?: simpleName.asString()
}

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
    /**
     * The C# names of the types declared inside this one ([csNestedTypeNames]). A derived type
     * inherits them, so a member it declares under one of these names hides the type (CS0108).
     */
    val nestedTypes: Set<String> = emptySet(),
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
      // CS0542: a member named like the type that declares it (`class OnTap(val onTap: ...)`).
      entry.members.firstOrNull { member -> member.name == entry.csName }?.let { member ->
        reported.add(member.name)
        emitOwnName(entry, member, logger)
      }
      val visited: MutableSet<String> = mutableSetOf(entry.qualifiedName)
      // Names a nearer ancestor already declares as a member: that ancestor hid the nested type
      // first and is named for it, so this type's override of the member is not a second hide.
      val nearerMembers: MutableSet<String> = mutableSetOf()
      var ancestor: Entry? = entry.keptBase?.let { entries[it] }
      while (ancestor != null && visited.add(ancestor.qualifiedName)) {
        val base: Entry = ancestor
        entry.members
          .filter { it.name !in ownMixed && it.name !in reported }
          .forEach { declared ->
            // A member named like the type itself (an arm `OnTap` nested in the base, with
            // `val onTap`) is C#'s CS0542, not a hide of an inherited type, and not this rule's.
            val hidesNested: Boolean = declared.name in base.nestedTypes &&
                declared.name !in nearerMembers && declared.name != entry.csName
            if (hidesNested) {
              reported.add(declared.name)
              emitInheritedType(entry, base, declared, logger)
              return@forEach
            }
            val inherited: CsMemberName = base.members.firstOrNull { candidate ->
              candidate.name == declared.name && candidate.kind != declared.kind
            } ?: return@forEach
            reported.add(declared.name)
            emitInherited(entry, base, declared, inherited, logger)
          }
        base.members.mapTo(nearerMembers) { it.name }
        ancestor = base.keptBase?.let { entries[it] }
      }
    }
  }

  /** The Kotlin spellings of [member]'s kind on [this] type. */
  private fun Entry.spellingsOf(member: CsMemberName): List<KotlinSpelling> =
    spellings[member.name].filter { it.isFunction == (member.kind == CsMemberKind.METHOD) }

  /** The Kotlin spellings of [declared]'s kind on [this] type, or its bare C# name. */
  private fun Entry.spelledAs(declared: CsMemberName): String = spellingsOf(declared)
    .takeIf { it.isNotEmpty() }
    ?.joinToString(" and ") { it.describe() }
    ?: "'${declared.name}'"

  /** A declared member named like its own declaring type, which C# forbids (CS0542). */
  private fun emitOwnName(entry: Entry, declared: CsMemberName, logger: KSPLogger) {
    val name: String = declared.name
    val spelled: String = entry.spelledAs(declared)
    ForwardDiagnosticSink.emit(
      listOf(
        ForwardDiagnostic(
          kind = ForwardDiagnosticKind.ERROR_CSHARP_NAME_COLLISION,
          symbol = entry.symbol,
          declaration = "${entry.csName}.$name",
          reason = "${entry.ownerPhrase} declares $spelled, which renders the C# name '$name', " +
              "the name of the type that declares it, and C# cannot declare a member named like " +
              "its enclosing type (CS0542)",
          hint = csharpNameRemedy(
            rename = "rename the member",
            spellings = entry.spellingsOf(declared),
            detail = "a property and a function both render PascalCase in C# (ADR-110)",
          ),
          owner = null,
        ),
      ),
      logger,
    )
  }

  /** A declared member named like a type nested in an ancestor, which it would hide (CS0108). */
  private fun emitInheritedType(
    entry: Entry,
    base: Entry,
    declared: CsMemberName,
    logger: KSPLogger,
  ) {
    val name: String = declared.name
    val spelled: String = entry.spelledAs(declared)
    ForwardDiagnosticSink.emit(
      listOf(
        ForwardDiagnostic(
          kind = ForwardDiagnosticKind.ERROR_CSHARP_NAME_COLLISION,
          symbol = entry.symbol,
          declaration = "${entry.csName}.$name",
          reason = "${entry.ownerPhrase} declares $spelled, which renders the C# name '$name' " +
              "of the type ${base.csName}.$name that it inherits from its base " +
              "${base.ownerPhrase}; the member would hide that type, which C# reports as " +
              "CS0108 and which fails `nugetCompileInterop` and every consumer build that " +
              "treats warnings as errors",
          hint = csharpNameRemedy(
            rename = "rename the member on ${entry.csName} or the nested type on ${base.csName}",
            // Only the member can carry the annotation; a nested type keeps its name (ADR-179).
            spellings = entry.spellingsOf(declared),
            target = "the member",
            detail = "a C# type shares one member namespace with its base, nested types " +
                "included (ADR-110)",
          ),
          owner = null,
        ),
      ),
      logger,
    )
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
          hint = csharpNameRemedy(
            rename = "rename the member on ${entry.csName} or on ${base.csName}",
            spellings = entry.spellingsOf(declared) + base.spellingsOf(inherited),
            detail = "a C# type shares one member namespace with its base, where only methods " +
                "may reuse a name (ADR-110)",
          ),
          owner = null,
        ),
      ),
      logger,
    )
  }
}

/**
 * The C# names of the types declared inside this class-like declaration, for
 * [CsMemberRegistry.Entry.nestedTypes]: the sealed arms nested in it and every public nested
 * declaration ADR-133/134 declares (a deferred one, or one skipped for an owner-scope collision,
 * declares nothing). A nested interface is `I<Name>`, except an ADR-112 eligible sealed interface,
 * which renders as an abstract class under its own name. The generated backing wrapper is left
 * out on purpose: a member hiding an internal type the author never wrote is not theirs to rename.
 */
internal fun KSClassDeclaration.csNestedTypeNames(): Set<String> = declarations
  .filterIsInstance<KSClassDeclaration>()
  .filter { nested -> nested.getVisibility() == Visibility.PUBLIC }
  .filter { nested -> !nested.isCompanionObject && nested.classKind in NESTED_DECLARATION_KINDS }
  .filter { nested ->
    nested.isSealedSubclass() ||
        (nested.nestedDeclarationDeferral() == null && nested.nestedOwnerScopeCollision() == null)
  }
  .map { nested ->
    val name: String = nested.simpleName.asString()
    val isInterface: Boolean =
      nested.classKind == ClassKind.INTERFACE && !nested.isEligibleSealedInterface()
    if (isInterface) "I$name" else name
  }
  .toSet()
