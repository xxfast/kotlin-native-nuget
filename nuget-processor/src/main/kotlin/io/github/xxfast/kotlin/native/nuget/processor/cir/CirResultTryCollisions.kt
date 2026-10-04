package io.github.xxfast.kotlin.native.nuget.processor.cir

import com.google.devtools.ksp.processing.KSPLogger
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnostic
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticSink

/**
 * Drops a `Result<T>` member's `TryX` twin that C# would not accept, with a named warning. The
 * throwing member it rides on stays, over the same export, so unlike ADR-110's fatal rule for two
 * authored members nothing is lost but the convenience, and a library that built before still
 * builds.
 *
 * A twin is dropped when its name is already taken on the C# type that would declare it:
 * - by a property, constant or nested type of that type (CS0102), or by the type itself (CS0542);
 * - by a same-named extension property in the same `{Receiver}Extensions` class, which compiles
 *   but makes every read of that property ambiguous at the call site (CS9339, measured);
 * - by a property, constant, nested type or same-signature method the type INHERITS, which the
 *   twin would hide (CS0108).
 *
 * And a twin is dropped with its whole override chain, decided per chain: a derived `override`
 * twin whose base twin is gone has nothing to override (CS0115), and an abstract base twin whose
 * override is gone leaves the concrete class unimplemented (CS0534). One warning per dropped twin.
 *
 * One post-pass over the assembled file, like `withSkipRemarks` beside it: the twin is the only
 * member the generator adds under a name derived from an authored one, and the file is the one
 * place every member a C# type ends up holding (merged file classes, companions, bases) is
 * visible. A base the file does not declare (a dependency type) cannot be read, so a twin over it
 * is kept rather than guessed at.
 */
internal fun CirFile.withoutCollidingResultTries(logger: KSPLogger): CirFile {
  val types: Map<String, TryType> = namespaces
    .flatMap { namespace ->
      namespace.declarations.flatMap { it.tryTypes(namespace.name, namespace.name) }
    }
    .groupBy { type -> type.path }
    .mapValues { (_, partials) -> partials.reduce(TryType::plus) }
  val dropped: Set<TwinNode> = TryDecisions(types).decide(logger)
  if (dropped.isEmpty()) return this
  return copy(
    namespaces = namespaces.map { namespace ->
      namespace.copy(
        declarations = namespace.declarations.map {
          it.withoutTries(namespace.name, namespace.name, dropped)
        },
      )
    },
  )
}

/** A twin by the C# type that declares it and its own signature. */
private data class TwinNode(val path: String, val name: String, val parameters: List<String>)

private data class TwinRef(
  val name: String,
  val parameters: List<String>,
  /** The throwing member's C# name, for the message. */
  val member: String,
  /** `override` on a class, `new` on an interface: either way it needs the base's twin. */
  val linksToBase: Boolean,
)

/** One C# type's surface as far as a twin's name is concerned. Partial classes are merged. */
private data class TryType(
  val path: String,
  val display: String,
  val simpleName: String,
  val bases: List<String>,
  val values: Set<String>,
  val nestedTypes: Set<String>,
  val extensionProperties: Set<String>,
  val signatures: Set<Pair<String, List<String>>>,
  val twins: List<TwinRef>,
) {
  operator fun plus(other: TryType): TryType = copy(
    bases = (bases + other.bases).distinct(),
    values = values + other.values,
    nestedTypes = nestedTypes + other.nestedTypes,
    extensionProperties = extensionProperties + other.extensionProperties,
    signatures = signatures + other.signatures,
    twins = twins + other.twins,
  )
}

private class TryDecisions(private val types: Map<String, TryType>) {
  private val parents: MutableMap<TwinNode, TwinNode> = mutableMapOf()

  fun decide(logger: KSPLogger): Set<TwinNode> {
    val direct: MutableMap<TwinNode, String> = linkedMapOf()
    val owners: MutableMap<TwinNode, Pair<TryType, TwinRef>> = linkedMapOf()
    types.values.forEach { type ->
      type.twins.forEach { twin ->
        val node = TwinNode(type.path, twin.name, twin.parameters)
        owners[node] = type to twin
        parents.getOrPut(node) { node }
        val clash: String? = ownClash(type, twin) ?: inheritedClash(type, twin)
        if (clash != null) direct[node] = clash
        if (twin.linksToBase) {
          when (val base: TwinNode? = baseTwin(type, twin)) {
            null -> if (basesResolved(type)) {
              direct.putIfAbsent(
                node,
                "no base of ${type.display} declares the twin it would override (CS0115)",
              )
            }

            else -> union(node, base)
          }
        }
      }
    }
    val droppedRoots: Map<TwinNode, TwinNode> = direct.keys.associateBy { node -> find(node) }
    val dropped: List<TwinNode> = owners.keys.filter { node -> find(node) in droppedRoots }
    dropped.forEach { node ->
      val (type, twin) = owners.getValue(node)
      val directReason: String? = direct[node]
      val reason: String = if (directReason != null) {
        directReason
      } else {
        val cause: TwinNode = droppedRoots.getValue(find(node))
        val (causeType, _) = owners.getValue(cause)
        "it shares an override chain with ${causeType.display}.${cause.name}, which is not " +
            "generated because ${direct.getValue(cause)}"
      }
      emit(type, twin, reason, logger)
    }
    return dropped.toSet()
  }

  private fun ownClash(type: TryType, twin: TwinRef): String? = when (twin.name) {
    in type.values -> "${type.display} already declares a property or constant named " +
        "'${twin.name}', and C# cannot declare a property and a method with one name on one type " +
        "(CS0102)"
    in type.nestedTypes -> "${type.display} already declares a nested type named " +
        "'${twin.name}' (CS0102)"
    in type.extensionProperties -> "${type.display} already declares an extension property " +
        "'${twin.name}', and a method of that name beside it makes every read of the property " +
        "ambiguous (CS9339)"
    type.simpleName -> "it would be named like its enclosing type ${type.display}, which C# " +
        "forbids (CS0542)"
    else -> null
  }

  private fun inheritedClash(type: TryType, twin: TwinRef): String? {
    val signature: Pair<String, List<String>> = twin.name to twin.parameters
    return ancestors(type).firstNotNullOfOrNull { base ->
      val twinsAlike: Boolean = !twin.linksToBase &&
          base.twins.any { it.name == twin.name && it.parameters == twin.parameters }
      val hides: Boolean = twin.name in base.values || twin.name in base.nestedTypes ||
          signature in base.signatures || twinsAlike
      if (hides) {
        "its base ${base.display} already declares a member named '${twin.name}', which the " +
            "twin would hide (CS0108)"
      } else {
        null
      }
    }
  }

  private fun baseTwin(type: TryType, twin: TwinRef): TwinNode? = ancestors(type)
    .firstOrNull { base ->
      base.twins.any { it.name == twin.name && it.parameters == twin.parameters }
    }
    ?.let { base -> TwinNode(base.path, twin.name, twin.parameters) }

  /** True when every base in [type]'s chain is declared in this file, so an absence is real. */
  private fun basesResolved(type: TryType): Boolean {
    val seen: MutableSet<String> = mutableSetOf(type.path)
    val pending: ArrayDeque<TryType> = ArrayDeque(listOf(type))
    while (pending.isNotEmpty()) {
      val current: TryType = pending.removeFirst()
      current.bases.forEach { spelling ->
        val base: TryType = resolve(spelling) ?: return false
        if (seen.add(base.path)) pending.addLast(base)
      }
    }
    return true
  }

  private fun ancestors(type: TryType): Sequence<TryType> = sequence {
    val seen: MutableSet<String> = mutableSetOf(type.path)
    val pending: ArrayDeque<TryType> = ArrayDeque(listOf(type))
    while (pending.isNotEmpty()) {
      val current: TryType = pending.removeFirst()
      current.bases.mapNotNull { resolve(it) }.forEach { base ->
        if (seen.add(base.path)) {
          yield(base)
          pending.addLast(base)
        }
      }
    }
  }

  /** A base list spelling (`global::Ns.Outer.Base<int>`) back to the type this file declares. */
  private fun resolve(spelling: String): TryType? {
    val bare: String = spelling.removePrefix("global::").substringBefore('<').trim()
    types[bare]?.let { return it }
    return types.values.filter { it.path.endsWith(".$bare") }.singleOrNull()
  }

  private fun find(node: TwinNode): TwinNode {
    val parent: TwinNode = parents.getOrPut(node) { node }
    if (parent == node) return node
    return find(parent).also { root -> parents[node] = root }
  }

  private fun union(a: TwinNode, b: TwinNode) {
    val rootA: TwinNode = find(a)
    val rootB: TwinNode = find(b)
    if (rootA != rootB) parents[rootA] = rootB
  }

  private fun emit(type: TryType, twin: TwinRef, reason: String, logger: KSPLogger) {
    ForwardDiagnosticSink.emit(
      listOf(
        ForwardDiagnostic(
          kind = ForwardDiagnosticKind.SKIPPED_RESULT_TRY_COLLISION,
          symbol = null,
          declaration = "${type.display}.${twin.name}",
          reason = "the non-throwing twin of `${twin.member}`, which returns a Kotlin `Result`, " +
              "is not generated because $reason; `${twin.member}` itself still binds and " +
              "throws on a `Result.failure`",
          hint = "rename the colliding member, or give one a `@CSharpName`, to get " +
              "`${twin.name}` back",
          // The twin is a convenience over a member that still binds, so no C# declaration is
          // missing a member a remark would have to name.
          owner = null,
        ),
      ),
      logger,
    )
  }
}

private fun CirMethod.twinRef(): TwinRef? {
  val twin: CirMethod = tryOverload ?: return null
  // A `new` member's twin is never rendered (see `renderMethod`), so it claims nothing.
  if (isNew) return null
  return TwinRef(twin.name, twin.parameters.map { it.type }, name, linksToBase = isOverride)
}

private fun CirMethod.signature(): Pair<String, List<String>> = name to parameters.map { it.type }

private fun List<CirMember>.memberValues(): Set<String> = mapNotNull { member ->
  when (member) {
    is CirProperty -> member.name
    is CirConst -> member.name
    else -> null
  }
}.toSet()

private fun CirDeclaration.typeName(): String? = when (this) {
  is CirStaticClass -> name
  is CirInterface -> name
  is CirClass -> name
  is CirValueClass -> name
  is CirEnum -> csName
  is CirSealedClass -> name
  is CirObject -> name
  else -> null
}

private fun List<CirDeclaration>.typeNames(): Set<String> = mapNotNull { it.typeName() }.toSet()

/** Every C# type [this] declares, itself and nested, with the surface a twin name meets. */
private fun CirDeclaration.tryTypes(namespace: String, outer: String): List<TryType> {
  val name: String = typeName() ?: return emptyList()
  val path = "$outer.$name"
  fun type(
    bases: List<String> = emptyList(),
    values: Set<String> = emptySet(),
    nestedTypes: Set<String> = emptySet(),
    extensionProperties: Set<String> = emptySet(),
    methods: List<CirMethod> = emptyList(),
    twins: List<TwinRef> = methods.mapNotNull { it.twinRef() },
    at: String = path,
    simple: String = name,
  ): TryType = TryType(
    path = at,
    display = at.removePrefix("$namespace."),
    simpleName = simple,
    bases = bases,
    values = values,
    nestedTypes = nestedTypes,
    extensionProperties = extensionProperties,
    signatures = methods.map { it.signature() }.toSet(),
    twins = twins,
  )
  return when (this) {
    is CirStaticClass -> listOf(
      type(
        values = members.memberValues(),
        extensionProperties = members
          .filterIsInstance<CirExtensionProperty>()
          .map { it.name }
          .toSet(),
        methods = members.filterIsInstance<CirMethod>(),
      ),
    )

    is CirClass -> listOf(
      type(
        bases = listOfNotNull(superClass),
        values = properties.map { it.name }.toSet() + companionMembers.memberValues(),
        nestedTypes = nestedDeclarations.typeNames(),
        methods = methods +
          companionMembers.filterIsInstance<CirMethod>() +
          listOfNotNull(copyMethod),
      ),
    ) + nestedDeclarations.flatMap { it.tryTypes(namespace, path) }

    is CirSealedClass -> listOf(
      type(
        bases = listOfNotNull(superClass),
        values = properties.map { it.name }.toSet(),
        nestedTypes = nestedDeclarations.typeNames() +
            subclasses.filter { it.isNested }.map { it.name },
        methods = methods,
      ),
    ) + subclasses.flatMap { arm ->
      val armPath: String = if (arm.isNested) "$path.${arm.name}" else "$outer.${arm.name}"
      listOf(
        type(
          bases = listOf(path),
          values = arm.properties.map { it.name }.toSet(),
          nestedTypes = arm.nestedDeclarations.typeNames(),
          methods = arm.methods,
          at = armPath,
          simple = arm.name,
        ),
      ) + arm.nestedDeclarations.flatMap { it.tryTypes(namespace, armPath) }
    } + nestedDeclarations.flatMap { it.tryTypes(namespace, path) }

    is CirObject -> listOf(
      type(
        values = methods.memberValues(),
        nestedTypes = nestedDeclarations.typeNames(),
        methods = methods.filterIsInstance<CirMethod>(),
      ),
    ) + nestedDeclarations.flatMap { it.tryTypes(namespace, path) }

    // An enum's member functions render into the same `{Enum}Extensions` partial class its
    // extension functions do, merged with that static class by path.
    is CirEnum -> {
      val extensions = "${csName}Extensions"
      listOf(
        type(
          values = extensionMembers.memberValues(),
          methods = extensionMembers.filterIsInstance<CirMethod>(),
          at = "$namespace.$extensions",
          simple = extensions,
        ),
      )
    }

    is CirInterface -> listOf(
      type(
        bases = superInterfaces,
        values = properties.map { it.name }.toSet(),
        nestedTypes = nestedDeclarations.typeNames(),
        twins = methods.mapNotNull { method ->
          method.tryOverload?.let { twin ->
            TwinRef(twin.name, twin.parameters.map { it.type }, method.name, method.isNew)
          }
        },
      ).copy(signatures = methods.map { it.name to it.parameters.map { p -> p.type } }.toSet()),
    ) + nestedDeclarations.flatMap { it.tryTypes(namespace, path) }

    else -> emptyList()
  }
}

private fun CirMethod.withoutTry(path: String, dropped: Set<TwinNode>): CirMethod {
  val twin: CirMethod = tryOverload ?: return this
  val node = TwinNode(path, twin.name, twin.parameters.map { it.type })
  return if (node in dropped) copy(tryOverload = null) else this
}

private fun List<CirMember>.withoutMemberTries(
  path: String,
  dropped: Set<TwinNode>,
): List<CirMember> =
  map { member -> if (member is CirMethod) member.withoutTry(path, dropped) else member }

private fun CirDeclaration.withoutTries(
  namespace: String,
  outer: String,
  dropped: Set<TwinNode>,
): CirDeclaration {
  val name: String = typeName() ?: return this
  val path = "$outer.$name"
  fun List<CirDeclaration>.nested(at: String): List<CirDeclaration> =
    map { it.withoutTries(namespace, at, dropped) }
  return when (this) {
    is CirStaticClass -> copy(members = members.withoutMemberTries(path, dropped))

    is CirClass -> copy(
      methods = methods.map { it.withoutTry(path, dropped) },
      companionMembers = companionMembers.withoutMemberTries(path, dropped),
      nestedDeclarations = nestedDeclarations.nested(path),
    )

    is CirSealedClass -> copy(
      methods = methods.map { it.withoutTry(path, dropped) },
      subclasses = subclasses.map { arm ->
        val armPath: String = if (arm.isNested) "$path.${arm.name}" else "$outer.${arm.name}"
        arm.copy(
          methods = arm.methods.map { it.withoutTry(armPath, dropped) },
          nestedDeclarations = arm.nestedDeclarations.nested(armPath),
        )
      },
      nestedDeclarations = nestedDeclarations.nested(path),
    )

    is CirObject -> copy(
      methods = methods.withoutMemberTries(path, dropped),
      nestedDeclarations = nestedDeclarations.nested(path),
    )

    is CirEnum -> copy(
      extensionMembers =
        extensionMembers.withoutMemberTries("$namespace.${csName}Extensions", dropped),
    )

    is CirInterface -> copy(
      methods = methods.map { method ->
        val twin: CirInterfaceMethod = method.tryOverload ?: return@map method
        val node = TwinNode(path, twin.name, twin.parameters.map { it.type })
        if (node in dropped) method.copy(tryOverload = null) else method
      },
      nestedDeclarations = nestedDeclarations.nested(path),
    )

    else -> this
  }
}
