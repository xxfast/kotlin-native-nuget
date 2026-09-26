package io.github.xxfast.kotlin.native.nuget.processor.forward

import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.symbol.Visibility
import io.github.xxfast.kotlin.native.nuget.processor.exports.isCompilerOwnedMember
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.FileLocation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeParameter

/**
 * Where an interface member lands once `interface Derived : Base` renders `IDerived : IBase`
 * instead of flattening.
 *
 * - [DECLARED]: declared on `IDerived` itself: lexically declared in the interface, or in an
 *   unexported super-interface whose members are re-homed onto it (the ADR-101 mirror).
 * - [INHERITED]: reached through a kept (exported) super-interface; C# inherits it, so `IDerived`
 *   must not redeclare it.
 * - [IDENTICAL_OVERRIDE]: declared here, but it overrides a kept super's member with the same
 *   signature; a redeclaration would be CS0108 (a build break under TreatWarningsAsErrors).
 * - [COVARIANT_OVERRIDE]: declared here, overriding a kept super's member with a narrower type.
 *   Named skip: C# reaches it through the super at the super's type.
 *
 * Every placement is still planned for the ADR-040 backing class, which implements them all.
 */
internal enum class ForwardInterfaceMemberPlacement {
  DECLARED,
  INHERITED,
  IDENTICAL_OVERRIDE,
  COVARIANT_OVERRIDE,

  // Two or more kept supers each declare this member on their own C# interface (`D : A, B`, both
  // declaring `x()`): inheriting it leaves `d.X()` ambiguous (CS0121) and the ADR-084 bridge
  // reading `impl.X()` off `ID` fails to compile, so `ID` redeclares it with `new`.
  DIAMOND_OVERRIDE,
}

/**
 * The super-interface shape of one exported interface, against the export set.
 *
 * [keptSupers] are the direct super-interface types the C# base list names; an unexported direct
 * super is flattened through: its members are re-homed ([rehomed]) and its own exported supers
 * join [keptSupers] (the ADR-101 chain amendment, mirrored for an interface owner).
 */
internal class ForwardInterfaceHierarchy(
  val iface: KSClassDeclaration,
  private val exportedTypes: Set<String>,
) {
  val keptSupers: List<KSType>
  val rehomed: List<KSClassDeclaration>

  /** Qualified names of every kept super and all of its ancestors: the members C# inherits. */
  private val keptClosure: Set<String>

  /** Every kept super and its ancestors, as declarations, for the diamond scan. */
  private val keptClosureDeclarations: List<KSClassDeclaration>

  init {
    val kept: MutableList<KSType> = mutableListOf()
    val dropped: MutableList<KSClassDeclaration> = mutableListOf()
    fun walk(owner: KSClassDeclaration) {
      owner.superTypes.forEach { reference ->
        val type: KSType = reference.resolve()
        val declaration: KSClassDeclaration =
          type.declaration as? KSClassDeclaration ?: return@forEach
        if (declaration.classKind != ClassKind.INTERFACE) return@forEach
        val qualified: String = declaration.qualifiedName?.asString() ?: return@forEach
        if (qualified in exportedTypes) {
          if (kept.none { it.declaration.qualifiedName?.asString() == qualified }) kept += type
        } else if (dropped.none { it.qualifiedName?.asString() == qualified }) {
          dropped += declaration
          walk(declaration)
        }
      }
    }
    walk(iface)
    keptSupers = kept
    rehomed = dropped
    keptClosure = buildSet {
      kept.forEach { type ->
        val declaration: KSClassDeclaration = type.declaration as KSClassDeclaration
        declaration.qualifiedName?.asString()?.let(::add)
        declaration.getAllSuperTypes().forEach { ancestor ->
          ancestor.declaration.qualifiedName?.asString()?.let(::add)
        }
      }
    }
    keptClosureDeclarations = kept.flatMap { type ->
      val declaration: KSClassDeclaration = type.declaration as KSClassDeclaration
      listOf(declaration) + declaration.getAllSuperTypes()
        .mapNotNull { it.declaration as? KSClassDeclaration }
        .filter { it.qualifiedName?.asString() in exportedTypes }
    }.distinctBy { it.qualifiedName?.asString() }
  }

  fun placement(member: KSFunctionDeclaration): ForwardInterfaceMemberPlacement {
    val diamond: Boolean = declaringKeptSupers(member) >= 2
    if (!isDeclaredHere(member)) {
      return if (diamond) ForwardInterfaceMemberPlacement.DIAMOND_OVERRIDE
      else ForwardInterfaceMemberPlacement.INHERITED
    }
    val overridden: KSFunctionDeclaration =
      keptOverridee(member) { it.findOverridee() as? KSFunctionDeclaration }
        ?: return ForwardInterfaceMemberPlacement.DECLARED
    val inherited: KSType? = try {
      overridden.asMemberOf(iface.asStarProjectedType()).returnType
    } catch (_: IllegalArgumentException) {
      overridden.returnType?.resolve()
    }
    val own: KSType? = member.returnType?.resolve()
    return if (sameType(own, inherited)) {
      if (diamond) ForwardInterfaceMemberPlacement.DIAMOND_OVERRIDE
      else ForwardInterfaceMemberPlacement.IDENTICAL_OVERRIDE
    }
    else ForwardInterfaceMemberPlacement.COVARIANT_OVERRIDE
  }

  fun placement(member: KSPropertyDeclaration): ForwardInterfaceMemberPlacement {
    val diamond: Boolean = declaringKeptSupers(member) >= 2
    if (!isDeclaredHere(member)) {
      return if (diamond) ForwardInterfaceMemberPlacement.DIAMOND_OVERRIDE
      else ForwardInterfaceMemberPlacement.INHERITED
    }
    val overridden: KSPropertyDeclaration = keptOverridee(member) { it.findOverridee() }
      ?: return ForwardInterfaceMemberPlacement.DECLARED
    val inherited: KSType = try {
      overridden.asMemberOf(iface.asStarProjectedType())
    } catch (_: IllegalArgumentException) {
      overridden.type.resolve()
    }
    return if (sameType(member.type.resolve(), inherited)) {
      if (diamond) ForwardInterfaceMemberPlacement.DIAMOND_OVERRIDE
      else ForwardInterfaceMemberPlacement.IDENTICAL_OVERRIDE
    } else {
      ForwardInterfaceMemberPlacement.COVARIANT_OVERRIDE
    }
  }

  /** The kept super's member [member] overrides, for a covariant override's planned signature. */
  fun keptOverriddenFunction(member: KSFunctionDeclaration): KSFunctionDeclaration? =
    keptOverridee(member) { it.findOverridee() as? KSFunctionDeclaration }

  /** The substituted return type of the kept member a covariant override narrows. */
  fun keptReturnType(member: KSFunctionDeclaration): KSType? {
    val overridden: KSFunctionDeclaration = keptOverriddenFunction(member) ?: return null
    return try {
      overridden.asMemberOf(iface.asStarProjectedType()).returnType
    } catch (_: IllegalArgumentException) {
      overridden.returnType?.resolve()
    }
  }

  /** The property half of [keptReturnType]. */
  fun keptPropertyType(member: KSPropertyDeclaration): KSType? {
    val overridden: KSPropertyDeclaration =
      keptOverridee(member) { it.findOverridee() } ?: return null
    return try {
      overridden.asMemberOf(iface.asStarProjectedType())
    } catch (_: IllegalArgumentException) {
      overridden.type.resolve()
    }
  }

  /** Lexical, not `parentDeclaration`: KSP reports a substituted fake override (the `peek(): Int`
   *  of `IntHolder : Holder<Int>`) as owned by the subtype, which it never declared. */
  /**
   * How many kept supers (transitively) declare a member of this signature on their OWN generated
   * C# interface, i.e. where that super's own hierarchy places it DECLARED (or re-declares it for
   * its own diamond). A chain `B : A` with `B` restating `x` counts once (`IB` omits it); two
   * unrelated `A` and `B` count twice, which is the CS0121 ambiguity `new` resolves.
   */
  private fun declaringKeptSupers(member: KSFunctionDeclaration): Int {
    val name: String = member.simpleName.asString()
    val arity: Int = member.parameters.size
    val ownParameters: List<KSType?> = substitutedParameters(member)
    return mostDerived(keptClosureDeclarations.filter { candidate ->
      val hierarchy by lazy { ForwardInterfaceHierarchy(candidate, exportedTypes) }
      candidate.getAllFunctions().any { function ->
        val sameSignature: Boolean = function.simpleName.asString() == name &&
            function.parameters.size == arity &&
            substitutedParameters(function).zip(ownParameters).all { (a, b) -> sameType(a, b) }
        val declaresOwn: Boolean = hierarchy.placement(function).let { placed ->
          placed == ForwardInterfaceMemberPlacement.DECLARED ||
              placed == ForwardInterfaceMemberPlacement.DIAMOND_OVERRIDE
        }
        sameSignature && declaresOwn
      }
    })
  }

  /** The property half of [declaringKeptSupers]. */
  private fun declaringKeptSupers(member: KSPropertyDeclaration): Int {
    val name: String = member.simpleName.asString()
    return mostDerived(keptClosureDeclarations.filter { candidate ->
      val hierarchy by lazy { ForwardInterfaceHierarchy(candidate, exportedTypes) }
      candidate.getAllProperties().any { property ->
        val sameName: Boolean = property.simpleName.asString() == name
        val declaresOwn: Boolean = hierarchy.placement(property).let { placed ->
          placed == ForwardInterfaceMemberPlacement.DECLARED ||
              placed == ForwardInterfaceMemberPlacement.DIAMOND_OVERRIDE
        }
        sameName && declaresOwn
      }
    })
  }

  /** C# hiding: a declarer another declarer derives from is hidden by it, so only the most-derived
   *  declarers are candidates for the ambiguity. */
  private fun mostDerived(declarers: List<KSClassDeclaration>): Int = declarers.count { declarer ->
    declarers.none { other ->
      other !== declarer && other.getAllSuperTypes().any { ancestor ->
        ancestor.declaration.qualifiedName?.asString() == declarer.qualifiedName?.asString()
      }
    }
  }

  private fun substitutedParameters(function: KSFunctionDeclaration): List<KSType?> = try {
    function.asMemberOf(iface.asStarProjectedType()).parameterTypes
  } catch (_: IllegalArgumentException) {
    function.parameters.map { it.type.resolve() }
  }

  private fun isDeclaredHere(member: KSDeclaration): Boolean =
    (listOf(iface) + rehomed).any { home -> home.declaresLexically(member) }

  private fun <T : KSDeclaration> keptOverridee(member: T, up: (T) -> T?): T? {
    var current: T? = up(member)
    val seen: MutableSet<T> = mutableSetOf()
    while (current != null && seen.add(current)) {
      val owner: String? = (current.parentDeclaration as? KSClassDeclaration)
        ?.qualifiedName?.asString()
      if (owner != null && owner in keptClosure) return current
      current = up(current)
    }
    return null
  }
}

internal fun KSClassDeclaration.declaresLexically(member: KSDeclaration): Boolean =
  declarations.any { declared ->
    declared == member ||
        (member.location is FileLocation && declared.location == member.location &&
            declared.simpleName.asString() == member.simpleName.asString())
  }

private fun sameType(a: KSType?, b: KSType?): Boolean {
  if (a == null || b == null) return a == b
  if (a == b) return true
  if (a.isMarkedNullable != b.isMarkedNullable) return false
  val aDeclaration: KSDeclaration = a.declaration
  val bDeclaration: KSDeclaration = b.declaration
  if (aDeclaration is KSTypeParameter || bDeclaration is KSTypeParameter) {
    return aDeclaration.simpleName.asString() == bDeclaration.simpleName.asString()
  }
  if (aDeclaration.qualifiedName?.asString() != bDeclaration.qualifiedName?.asString()) return false
  if (a.arguments.size != b.arguments.size) return false
  return a.arguments.zip(b.arguments).all { (left, right) ->
    left.variance == right.variance && sameType(left.type?.resolve(), right.type?.resolve())
  }
}

/**
 * The interface route's member list with its plan symbols, in the exact order and numbering
 * `ForwardCallablePlanner.interfaceEntries` assigns (ADR-090 amendment: a per-interface counter in
 * `getAllFunctions()` order), so a declaration route can map a plan back to its member.
 */
internal fun KSClassDeclaration.interfaceMethodSymbols():
    List<Pair<String, KSFunctionDeclaration>> {
  val owner: String = qualifiedName?.asString() ?: return emptyList()
  val occurrences: MutableMap<String, Int> = mutableMapOf()
  return getAllFunctions()
    .filter { method -> method.getVisibility() == Visibility.PUBLIC }
    .filter { method -> !method.isCompilerOwnedMember(this) }
    .map { method ->
      val name: String = method.simpleName.asString()
      val occurrence: Int = occurrences.merge(name, 1, Int::plus)!!
      val suffix: String = if (occurrence == 1) "" else "_$occurrence"
      "$owner.$name$suffix" to method
    }
    .toList()
}

/** Null (no placement known) keeps the member, the pre-hierarchy behaviour. */
internal fun declared(placement: ForwardInterfaceMemberPlacement?): Boolean =
  placement == null || placement == ForwardInterfaceMemberPlacement.DECLARED ||
      placement == ForwardInterfaceMemberPlacement.DIAMOND_OVERRIDE

/**
 * Whether ADR-113's type-parameter carve-out (`typeParameterMethods` in `CirClassTranslator`)
 * restores this member on `IFoo<T>`: declared on [iface] itself, with a signature naming one of
 * its own class type parameters. The planner has no entry for it, but it is not dropped, so a
 * `SKIPPED_UNSUPPORTED_TYPE ... T` warning for it is a false positive.
 */
internal fun KSFunctionDeclaration.restoredByTypeParameterCarveOut(
  iface: KSClassDeclaration,
): Boolean {
  val types: List<KSType> =
    listOfNotNull(returnType?.resolve()) + parameters.map { it.type.resolve() }
  return mentionsOwnTypeParameter(iface, types)
}

/** The property half of [restoredByTypeParameterCarveOut] (`typeParameterProperties`). */
internal fun KSPropertyDeclaration.restoredByTypeParameterCarveOut(
  iface: KSClassDeclaration,
): Boolean = mentionsOwnTypeParameter(iface, listOf(type.resolve()))

private fun KSDeclaration.mentionsOwnTypeParameter(
  iface: KSClassDeclaration,
  types: List<KSType>,
): Boolean {
  if (iface.typeParameters.isEmpty() || !iface.declaresLexically(this)) return false
  val names: Set<String> = iface.typeParameters.map { it.name.asString() }.toSet()
  return types.any { type ->
    type.declaration is KSTypeParameter && type.declaration.simpleName.asString() in names
  }
}
