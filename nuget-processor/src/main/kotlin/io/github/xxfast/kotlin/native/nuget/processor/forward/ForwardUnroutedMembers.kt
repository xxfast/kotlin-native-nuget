package io.github.xxfast.kotlin.native.nuget.processor.forward

import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.Visibility

/**
 * The members each owner's catalog dropped from C#, by owner qualified name: the callables and
 * properties no route carries, each of which is named by its own `SKIPPED_*` warning on that
 * owner (`warnDroppedForwardCallables` / `warnDroppedForwardProperties` read the same entries).
 *
 * Read by `SKIPPED_UNEXPORTED_SUPERTYPE`, whose "its public members are bound on X directly" was
 * false for exactly these members. Filled once per KSP round by `NugetProcessor` from the planned
 * catalogs, before any class is translated, as [ForwardDeclaredTypeNames] is; empty means "nothing
 * known", which keeps the shipped all-bound sentence.
 */
internal object ForwardUnroutedMembers {
  private val byOwner: MutableMap<String, MutableSet<String>> = mutableMapOf()

  fun reset(catalogs: List<ForwardCallablePlanCatalog>) {
    synchronized(byOwner) {
      byOwner.clear()
      catalogs.forEach { catalog ->
        catalog.droppedCallables.forEach { dropped ->
          // The node's own name, not the symbol's tail, which carries an overload suffix.
          val member: String = (dropped.node as? KSDeclaration)?.simpleName?.asString()
            ?: dropped.symbol.substringAfterLast('.')
          record(dropped.symbol.substringBeforeLast('.'), member)
        }
        catalog.droppedProperties.forEach { dropped ->
          record(dropped.symbol.substringBeforeLast('.'), dropped.symbol.substringAfterLast('.'))
        }
      }
    }
  }

  /** The Kotlin names of [owner]'s members that no route carries. */
  fun of(owner: String): Set<String> = synchronized(byOwner) { byOwner[owner].orEmpty().toSet() }

  private fun record(owner: String, member: String) {
    byOwner.getOrPut(owner) { mutableSetOf() }.add(member)
  }
}

/**
 * What became of a dropped supertype's public members on the class that re-homes them: [bound]
 * bind on it, [unrouted] are each named by their own `SKIPPED_*` warning on it. Kotlin names, in
 * declaration order, functions before properties.
 */
internal data class ForwardInheritedOutcome(val bound: List<String>, val unrouted: List<String>)

/** `` `a` ``, `` `a` and `b` ``, `` `a`, `b` and `c` ``: member names inline in a sentence. */
internal fun List<String>.inlineList(): String {
  val quoted: List<String> = map { name -> "`$name`" }
  return if (quoted.size <= 1) {
    quoted.joinToString("")
  } else {
    quoted.dropLast(1).joinToString(", ") + " and " + quoted.last()
  }
}

/**
 * The [ForwardInheritedOutcome] of this dropped supertype's public members on [owner]. A member
 * [keptBase] (or a supertype of it) declares stays on that kept C# base and is not counted, nor
 * is `Any`'s.
 */
internal fun KSClassDeclaration.inheritedOutcomeOn(
  owner: KSClassDeclaration,
  keptBase: KSClassDeclaration? = null,
): ForwardInheritedOutcome {
  val keptOwners: Set<String> =
    if (keptBase == null) emptySet()
    else (sequenceOf(keptBase) + keptBase.getAllSuperTypes().map { it.declaration })
      .mapNotNull { it.qualifiedName?.asString() }
      .toSet()
  val kept: Set<String> = keptOwners + "kotlin.Any"
  val members: List<KSDeclaration> = (getAllFunctions() + getAllProperties())
    .filter { member -> member.getVisibility() == Visibility.PUBLIC }
    .filter { member ->
      (member.parentDeclaration as? KSClassDeclaration)?.qualifiedName?.asString() !in kept
    }
    .toList()
  val names: List<String> = members.map { it.simpleName.asString() }.distinct()
  val dropped: Set<String> = ForwardUnroutedMembers.of(owner.qualifiedName?.asString().orEmpty())
  return ForwardInheritedOutcome(
    bound = names.filterNot { it in dropped },
    unrouted = names.filter { it in dropped },
  )
}
