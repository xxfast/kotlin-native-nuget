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
  val entries: List<DocLinkEntry> = docLinkEntries()
  if (entries.isEmpty()) return this
  val fileWide: Map<String, String> = entries.uniqueBy { entry -> entry.key }
  val byNamespace: Map<String, Map<String, String>> = entries
    .groupBy { entry -> entry.namespace }
    .mapValues { (_, inNamespace) -> inNamespace.filter { it.isPath }.uniqueBy { it.key } }
  return copy(
    namespaces = namespaces.map { namespace ->
      val index = DocLinkIndex(byNamespace[namespace.name].orEmpty(), fileWide)
      namespace.copy(declarations = namespace.declarations.map { it.resolveDocLinks(index) })
    },
  )
}

/**
 * The two lookups a `[link]` in one namespace can take. [local] is that namespace's own types, and
 * answers a link KSP scoped to a type of the documented declaration's own package
 * ([CirDocInline.Link.scopedPath]): that type or nothing, never a same-named one elsewhere.
 * [fileWide] answers an unscoped link, and only for a name exactly one type in the file claims.
 */
private class DocLinkIndex(val local: Map<String, String>, val fileWide: Map<String, String>)

/** One key a declared type can be linked by, with the namespace that declares it. */
private data class DocLinkEntry(
  val namespace: String,
  val key: String,
  val cref: String,
  /** The whole path from the namespace, rather than a nested type's bare simple name. */
  val isPath: Boolean,
)

/** Key to `global::`-qualified cref. A key two declarations claim is dropped, not guessed at. */
private fun List<DocLinkEntry>.uniqueBy(key: (DocLinkEntry) -> String): Map<String, String> =
  groupBy(key) { entry -> entry.cref }
    .filterValues { crefs -> crefs.distinct().size == 1 }
    .mapValues { (_, crefs) -> crefs.first() }

/**
 * Simple name, dotted nested path and its Kotlin spelling, for every type this file declares.
 */
private fun CirFile.docLinkEntries(): List<DocLinkEntry> {
  val entries: MutableList<DocLinkEntry> = mutableListOf()
  for (namespace in namespaces) {
    val keys: MutableList<DocLinkEntry> = mutableListOf()
    for (declaration in namespace.declarations) {
      declaration.indexInto(keys, namespace.name, emptyList(), emptyList())
    }
    entries += keys
  }
  return entries
}

/**
 * [prefix] is the enclosing C# path, [kotlinPrefix] the same path as the Kotlin author spells it:
 * they differ only across an ADR-040 interface (`IOuter.Inner` against `Outer.Inner`), and a
 * KSP-scoped link carries the Kotlin spelling.
 */
private fun CirDeclaration.indexInto(
  entries: MutableList<DocLinkEntry>,
  namespace: String,
  prefix: List<String>,
  kotlinPrefix: List<String>,
) {
  val name: String = docLinkName() ?: return
  val path: List<String> = prefix + name
  val kotlinName: String = (this as? CirInterface)?.kotlinInterfaceName() ?: name
  val kotlinPath: List<String> = kotlinPrefix + kotlinName
  if (!isGenericDeclaration()) {
    val cref: String = "global::$namespace.${path.joinToString(".")}"
    entries += DocLinkEntry(namespace, name, cref, isPath = path.size == 1)
    if (path.size > 1) entries += DocLinkEntry(namespace, path.joinToString("."), cref, true)
    // ADR-040: the C# interface is `IPerchable` while the author writes `[Perchable]`. Both keys
    // are indexed; if a backing wrapper class also claims `Perchable`, the key is ambiguous and
    // the link falls back, which is the safe half of the trade.
    if (this is CirInterface) {
      kotlinInterfaceName()?.let { entries += DocLinkEntry(namespace, it, cref, path.size == 1) }
    }
    if (kotlinPath.size > 1 && kotlinPath != path) {
      entries += DocLinkEntry(namespace, kotlinPath.joinToString("."), cref, isPath = true)
    }
  }
  for (nested in docLinkChildren()) nested.indexInto(entries, namespace, path, kotlinPath)
  if (this is CirSealedClass) {
    for (arm in subclasses) {
      // Issue #54: a nested arm is declared inside the base (`Snooze.Catnap`), a sibling arm at
      // namespace level, and the cref has to follow the C# scope either way.
      val armPrefix: List<String> = if (arm.isNested) path else prefix
      val armKotlinPrefix: List<String> = if (arm.isNested) kotlinPath else kotlinPrefix
      val armPath: List<String> = armPrefix + arm.name
      val armKotlinPath: List<String> = armKotlinPrefix + arm.name
      val cref: String = "global::$namespace.${armPath.joinToString(".")}"
      // ADR-199: a generic arm has no bare cref spelling either, but its holder's children do.
      if (arm.typeParameters.isEmpty()) {
        entries += DocLinkEntry(namespace, arm.name, cref, isPath = armPath.size == 1)
        if (armPath.size > 1) {
          entries += DocLinkEntry(namespace, armPath.joinToString("."), cref, isPath = true)
          if (armKotlinPath != armPath) {
            entries += DocLinkEntry(namespace, armKotlinPath.joinToString("."), cref, isPath = true)
          }
        }
      }
      for (nested in arm.nestedDeclarations) {
        nested.indexInto(entries, namespace, armPath, armKotlinPath)
      }
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
  // ADR-199: an intermediate arm's hierarchy sits on the generic base's holder.
  is CirSealedClass -> nestedDeclarations + intermediates
  is CirObject -> nestedDeclarations
  else -> emptyList()
}

/** ADR-147: a generic type has no bare cref spelling, so it is left to the `<c>` fallback. */
private fun CirDeclaration.isGenericDeclaration(): Boolean = when (this) {
  is CirInterface -> typeParameters.isNotEmpty()
  is CirClass -> typeParameters.isNotEmpty()
  is CirSealedClass -> typeParameters.isNotEmpty()
  else -> false
}

/**
 * `IPerchable` back to the `Perchable` the Kotlin author writes in a `[link]`. Always a simple
 * name: `CirInterface.name` is `"I" + iface.simpleName` at its one construction site
 * (`translateInterface`), and the enclosing scope is carried by the index's path, not by the name.
 */
private fun CirInterface.kotlinInterfaceName(): String? =
  name.takeIf { it.length > 1 && it[0] == 'I' && it[1].isUpperCase() }?.drop(1)

private fun CirDeclaration.resolveDocLinks(index: DocLinkIndex): CirDeclaration =
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
      asyncMembers = asyncMembers.map { it.resolveDocLinks(index) },
      flowMembers = flowMembers.map { it.resolveDocLinks(index) },
      nestedDeclarations = nestedDeclarations.map { it.resolveDocLinks(index) },
      intermediates = intermediates.map { it.resolveDocLinks(index) as CirSealedClass },
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

private fun CirSealedSubclass.resolveDocLinks(index: DocLinkIndex): CirSealedSubclass = copy(
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
private fun CirMember.resolveDocLinks(index: DocLinkIndex): CirMember = when (this) {
  is CirMethod -> copy(doc = doc.resolve(index))
  is CirProperty -> copy(doc = doc.resolve(index))
  is CirDllImport -> this
  is CirInterfaceBridgeMethod -> this
  is CirStoredCallbackMethod -> this
  is CirCallbackMethod -> this
  is CirConst -> this
  is CirExtensionProperty -> copy(doc = doc.resolve(index))
}

private fun CirDoc?.resolve(index: DocLinkIndex): CirDoc? {
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

private fun CirDocInline.resolve(index: DocLinkIndex): CirDocInline = when (this) {
  is CirDocInline.Link -> (
    if (scopedPath != null) index.local[scopedPath] else index.fileWide[target]
  )
    ?.let { cref -> CirDocInline.TypeRef(cref, label) }
    ?: this

  is CirDocInline.Text -> this
  is CirDocInline.Code -> this
  is CirDocInline.TypeRef -> this
}
