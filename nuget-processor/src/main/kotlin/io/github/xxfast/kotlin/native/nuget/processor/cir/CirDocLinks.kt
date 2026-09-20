package io.github.xxfast.kotlin.native.nuget.processor.cir

/**
 * ADR-150 amendment: turns a KDoc `[Link]` into a real `<see cref>` when, and only when, the
 * finished file declares exactly one non-generic type under that name.
 *
 * One post-pass over the assembled [CirFile], run where `CirRenderer` is handed the file. The
 * authority is the CIR itself, not KSP: a Kotlin class can exist and be skipped (ADR-043), and a
 * cref naming a type nothing declares is CS1574 -- fatal in a *consumer's* build of a source file
 * they cannot edit. Resolving against the file makes that unrepresentable, and anything that does
 * not resolve keeps rendering as `<c>` with the author's Kotlin spelling.
 *
 * Deliberately conservative, three ways:
 * - a generic type is not indexed at all: spike 1 (2026-09-20) confirmed a bare cref to `Crate<T>`
 *   is CS1574, and the `Crate{T}` spelling is deferred scope.
 * - an ambiguous simple name (two namespaces, or an ADR-040 interface whose backing wrapper class
 *   carries the same Kotlin spelling) resolves to nothing and falls back.
 * - a member or parameter link never resolves here: member crefs need overload-qualified spelling
 *   (CS0419 on ambiguity), which is deferred scope.
 */
internal fun CirFile.resolveDocLinks(): CirFile {
  val index: Map<String, String> = docLinkIndex()
  if (index.isEmpty()) return this
  return copy(
    namespaces = namespaces.map { namespace ->
      namespace.copy(declarations = namespace.declarations.map { it.resolveDocLinks(index) })
    },
  )
}

/**
 * Simple name (and dotted nested path) to `global::`-qualified cref, for every type this file
 * declares. A key two declarations claim is dropped rather than guessed at.
 */
private fun CirFile.docLinkIndex(): Map<String, String> {
  val entries: MutableList<Pair<String, String>> = mutableListOf()
  for (namespace in namespaces) {
    for (declaration in namespace.declarations) {
      declaration.indexInto(entries, namespace.name, emptyList())
    }
  }
  return entries
    .groupBy({ it.first }, { it.second })
    .filterValues { crefs -> crefs.distinct().size == 1 }
    .mapValues { (_, crefs) -> crefs.first() }
}

private fun CirDeclaration.indexInto(
  entries: MutableList<Pair<String, String>>,
  namespace: String,
  prefix: List<String>,
) {
  val name: String = docLinkName() ?: return
  val path: List<String> = prefix + name
  if (!isGenericDeclaration()) {
    val cref: String = "global::$namespace.${path.joinToString(".")}"
    entries += name to cref
    if (path.size > 1) entries += path.joinToString(".") to cref
    // ADR-040: the C# interface is `IPerchable` while the author writes `[Perchable]`. Both keys
    // are indexed; if a backing wrapper class also claims `Perchable`, the key is ambiguous and
    // the link falls back, which is the safe half of the trade.
    if (this is CirInterface) kotlinInterfaceName()?.let { entries += it to cref }
  }
  for (nested in docLinkChildren()) nested.indexInto(entries, namespace, path)
  if (this is CirSealedClass) {
    for (arm in subclasses) {
      // Issue #54: a nested arm is declared inside the base (`Snooze.Catnap`), a sibling arm at
      // namespace level, and the cref has to follow the C# scope either way.
      val armPrefix: List<String> = if (arm.isNested) path else prefix
      val armPath: List<String> = armPrefix + arm.name
      val cref: String = "global::$namespace.${armPath.joinToString(".")}"
      entries += arm.name to cref
      if (armPath.size > 1) entries += armPath.joinToString(".") to cref
      for (nested in arm.nestedDeclarations) nested.indexInto(entries, namespace, armPath)
    }
  }
}

/**
 * The C# simple name this declaration is spelled with inside its enclosing scope, or null for the
 * generated marshalling helpers -- they are `CirDeclaration`s too, they carry no `doc` slot and no
 * Kotlin author can name one in a `[link]`.
 */
private fun CirDeclaration.docLinkName(): String? = when (this) {
  is CirStaticClass -> name
  is CirInterface -> name
  is CirClass -> name
  is CirValueClass -> name
  is CirEnum -> name
  is CirSealedClass -> name
  is CirObject -> name
  else -> null
}

private fun CirDeclaration.docLinkChildren(): List<CirDeclaration> = when (this) {
  is CirInterface -> nestedDeclarations
  is CirClass -> nestedDeclarations
  is CirSealedClass -> nestedDeclarations
  is CirObject -> nestedDeclarations
  else -> emptyList()
}

/** ADR-147: a generic type has no bare cref spelling, so it is left to the `<c>` fallback. */
private fun CirDeclaration.isGenericDeclaration(): Boolean = when (this) {
  is CirInterface -> typeParameters.isNotEmpty()
  is CirClass -> typeParameters.isNotEmpty()
  else -> false
}

/**
 * `IPerchable` back to the `Perchable` the Kotlin author writes in a `[link]`. Always a simple
 * name: `CirInterface.name` is `"I" + iface.simpleName` at its one construction site
 * (`translateInterface`), and the enclosing scope is carried by the index's path, not by the name.
 */
private fun CirInterface.kotlinInterfaceName(): String? =
  name.takeIf { it.length > 1 && it[0] == 'I' && it[1].isUpperCase() }?.drop(1)

private fun CirDeclaration.resolveDocLinks(index: Map<String, String>): CirDeclaration =
  when (this) {
    is CirStaticClass -> copy(members = members.map { it.resolveDocLinks(index) })
    is CirInterface -> copy(
      properties = properties.map { it.copy(doc = it.doc.resolve(index)) },
      methods = methods.map { it.copy(doc = it.doc.resolve(index)) },
      nestedDeclarations = nestedDeclarations.map { it.resolveDocLinks(index) },
      doc = doc.resolve(index),
    )

    is CirClass -> copy(
      constructor = constructor?.let { it.copy(doc = it.doc.resolve(index)) },
      secondaryConstructors = secondaryConstructors.map { it.copy(doc = it.doc.resolve(index)) },
      properties = properties.map { it.copy(doc = it.doc.resolve(index)) },
      methods = methods.map { it.copy(doc = it.doc.resolve(index)) },
      copyMethod = copyMethod?.let { it.copy(doc = it.doc.resolve(index)) },
      companionMembers = companionMembers.map { it.resolveDocLinks(index) },
      nestedDeclarations = nestedDeclarations.map { it.resolveDocLinks(index) },
      doc = doc.resolve(index),
    )

    is CirValueClass -> copy(
      constructors = constructors.map { it.copy(doc = it.doc.resolve(index)) },
      properties = properties.map { it.copy(doc = it.doc.resolve(index)) },
      methods = methods.map { it.copy(doc = it.doc.resolve(index)) },
      doc = doc.resolve(index),
      underlyingDoc = underlyingDoc.resolve(index),
    )

    is CirEnum -> copy(
      entries = entries.map { it.copy(doc = it.doc.resolve(index)) },
      doc = doc.resolve(index),
    )

    is CirSealedClass -> copy(
      subclasses = subclasses.map { arm -> arm.resolveDocLinks(index) },
      properties = properties.map { it.copy(doc = it.doc.resolve(index)) },
      methods = methods.map { it.copy(doc = it.doc.resolve(index)) },
      nestedDeclarations = nestedDeclarations.map { it.resolveDocLinks(index) },
      doc = doc.resolve(index),
    )

    is CirObject -> copy(
      methods = methods.map { it.resolveDocLinks(index) },
      nestedDeclarations = nestedDeclarations.map { it.resolveDocLinks(index) },
      doc = doc.resolve(index),
    )

    // The generated marshalling helpers, which carry no `doc` slot at all. A declaration kind that
    // grows one and is not added above keeps its links as `<c>`: a degraded rendering, never a
    // cref naming something undeclared.
    else -> this
  }

private fun CirSealedSubclass.resolveDocLinks(index: Map<String, String>): CirSealedSubclass = copy(
  properties = properties.map { it.copy(doc = it.doc.resolve(index)) },
  constructors = constructors.map { it.copy(doc = it.doc.resolve(index)) },
  methods = methods.map { it.copy(doc = it.doc.resolve(index)) },
  asyncMembers = asyncMembers.map { it.resolveDocLinks(index) },
  flowMembers = flowMembers.map { it.resolveDocLinks(index) },
  callbackMembers = callbackMembers.map { it.resolveDocLinks(index) },
  nestedDeclarations = nestedDeclarations.map { it.resolveDocLinks(index) },
  doc = doc.resolve(index),
)

/** Exhaustive on purpose: a member kind that grows a `doc` slot must be handled here too. */
private fun CirMember.resolveDocLinks(index: Map<String, String>): CirMember = when (this) {
  is CirMethod -> copy(doc = doc.resolve(index))
  is CirProperty -> copy(doc = doc.resolve(index))
  is CirDllImport -> this
  is CirInterfaceBridgeMethod -> this
  is CirStoredCallbackMethod -> this
  is CirCallbackMethod -> this
  is CirConst -> this
}

private fun CirDoc?.resolve(index: Map<String, String>): CirDoc? {
  val doc: CirDoc = this ?: return null
  return doc.copy(
    summary = doc.summary?.map { it.resolve(index) },
    remarks = doc.remarks.map { block ->
      when (block) {
        is CirDocBlock.Para -> CirDocBlock.Para(block.text.map { it.resolve(index) })
        is CirDocBlock.Code -> block
      }
    },
    params = doc.params.map { param -> param.copy(text = param.text.map { it.resolve(index) }) },
    returns = doc.returns?.map { it.resolve(index) },
    throws = doc.throws.map { thrown -> thrown.copy(text = thrown.text.map { it.resolve(index) }) },
    seeAlso = doc.seeAlso.map { it.resolve(index) },
  )
}

private fun CirDocInline.resolve(index: Map<String, String>): CirDocInline = when (this) {
  is CirDocInline.Link -> index[target]
    ?.let { cref -> CirDocInline.TypeRef(cref, label) }
    ?: this

  is CirDocInline.Text -> this
  is CirDocInline.Code -> this
  is CirDocInline.TypeRef -> this
}
