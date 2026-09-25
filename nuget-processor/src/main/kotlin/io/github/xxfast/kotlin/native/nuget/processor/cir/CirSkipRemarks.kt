package io.github.xxfast.kotlin.native.nuget.processor.cir

import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticOwner
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticRecord
import io.github.xxfast.kotlin.native.nuget.processor.csharpIdentifier

/**
 * ADR-064 amendment (issue #249): name every member-level skip on the generated declaration it left
 * a hole in, as a `<remarks>` paragraph, so a C# consumer meets the absence in `Interop.cs` itself
 * rather than at the call site with `NugetDiagnostics.json` nowhere in reach.
 *
 * A PURE post-pass: it takes the finished [CirFile] and the same recorded diagnostics
 * `NugetDiagnostics.json` is written from, and returns a new file. The translator never reads the
 * diagnostic sink, so a Tier 1 test that translates directly sees no hidden state, and the file and
 * the JSON cannot name different members.
 *
 * The gate is `owner != null`, never the `SKIPPED_` name prefix: ADR-075's partial setter skip is a
 * `SKIPPED_UNSUPPORTED_INPUT` for a property that still EXISTS, and a prefix filter would report it
 * as unavailable -- exactly the lie ADR-064's honesty rule exists to stop. That record carries a
 * [ForwardDiagnosticOwner.Property] owner instead, and lands on the C# property.
 */
internal fun CirFile.withSkipRemarks(
  records: List<ForwardDiagnosticRecord>,
  namespaceOf: (String) -> String,
): CirFile {
  val skips: List<ForwardSkipRemark> = records
    .mapNotNull { record -> record.toSkipRemark(namespaceOf) }
    // Spike 4 (2026-09-20, measured): one dropped member can record the same diagnostic more than
    // once (ADR-149's omitting overloads did, before ADR-164 replaced them). Deduped on
    // (owner, member, kind) rather than on the
    // rendered text, so two genuinely different drops of one member (a refused parameter AND an
    // unsupported return) still read as two paragraphs.
    .distinctBy { skip -> Triple(skip.owner, skip.member, skip.kind) }
  if (skips.isEmpty()) return this
  return copy(
    namespaces = namespaces.map { namespace ->
      namespace.withSkipRemarks(skips.filter { skip -> skip.namespace == namespace.name })
    },
  )
}

/** One skip, resolved from Kotlin spelling to the C# namespace it will be attached in. */
internal data class ForwardSkipRemark(
  val namespace: String,
  val owner: ForwardDiagnosticOwner,
  val member: String?,
  val kind: ForwardDiagnosticKind,
  val paragraph: String,
)

private fun ForwardDiagnosticRecord.toSkipRemark(
  namespaceOf: (String) -> String,
): ForwardSkipRemark? {
  val owner: ForwardDiagnosticOwner = owner ?: return null
  val member: String = member ?: return null
  return ForwardSkipRemark(
    namespace = namespaceOf(owner.packageName),
    owner = owner,
    member = member,
    kind = kind,
    paragraph = remarkParagraph(owner, member, kind, reason),
  )
}

/**
 * The paragraph itself: the diagnostic's KIND, its raw reason sentence, and the KOTLIN name of the
 * member.
 *
 * Never the formatted `message` (it embeds `at <absolute path>:<line>`, which would ship every
 * producer's source tree to every consumer's tooltip) and never the `hint` (author-facing: a
 * consumer cannot edit the Kotlin). Never the C# name either -- the member was never generated
 * under one, so naming it would send a reader looking for a spelling that never existed.
 */
private fun remarkParagraph(
  owner: ForwardDiagnosticOwner,
  member: String,
  kind: ForwardDiagnosticKind,
  reason: String,
): String = when (owner) {
  // ADR-075's partial skip: the member is here, one accessor short, so the paragraph says what is
  // missing from it rather than claiming the member is gone.
  is ForwardDiagnosticOwner.Property -> "Kotlin `$member`: $reason ($kind)."
  else -> "Not generated from Kotlin `$member`: $reason ($kind)."
}

private fun CirNamespace.withSkipRemarks(skips: List<ForwardSkipRemark>): CirNamespace {
  if (skips.isEmpty()) return this
  val fileClassSkips: List<ForwardSkipRemark> = skips
    .filter { skip -> skip.ownerRoot() is ForwardDiagnosticOwner.FileClass }
  val typeSkips: List<ForwardSkipRemark> = skips - fileClassSkips.toSet()
  val staticClassNames: Set<String> = declarations
    .filterIsInstance<CirStaticClass>()
    .mapTo(mutableSetOf()) { it.name }
  val typeNames: Set<String> = declarations.mapNotNullTo(mutableSetOf()) { it.declaredName() } -
      staticClassNames
  val attached: List<CirDeclaration> = declarations.map { declaration ->
    declaration.withSkipRemarks(typeSkips, path = emptyList()) { name ->
      fileClassSkips.filter { skip -> skip.fileClassName(typeNames) == name }
    }
  }
  // Issue #249's headline case -- a file whose every top-level declaration was dropped -- is NOT
  // handled here. It is handled by moving the ADR-064 husk sweep out of `translate` and running it
  // after this pass (`CirFile.withoutEmptyStaticClasses`), so the holder `translate` built with an
  // empty member set is still standing when the loop above attaches to it, under the exact name
  // `resolveStaticClassName` gave it. That is the answer to "synthesise, or let the elision consult
  // the records": neither -- the elision simply runs later, and this pass stays pure.
  //
  // What remains below is the fallback for an owner no translate loop grouped at all, because the
  // declaration never reached `translate`: an ADR-115 opt-in-marked top-level function is filtered
  // out of the export set before collection, so a file holding only one leaves no husk to attach
  // to. Minting the holder is what lets that skip reach a consumer too.
  val missing: List<CirStaticClass> = fileClassSkips
    .groupBy { skip -> skip.fileClassName(typeNames) }
    .filterKeys { name -> name !in staticClassNames }
    .map { (name, skips) -> CirStaticClass(name, emptyList(), skips.map { it.paragraph }) }
  return copy(declarations = attached + missing)
}

/** A [ForwardDiagnosticOwner.Property] hangs off a container, which is what decides the route. */
private fun ForwardSkipRemark.ownerRoot(): ForwardDiagnosticOwner = when (val owner = owner) {
  is ForwardDiagnosticOwner.Property -> owner.container
  else -> owner
}

/**
 * ADR-007's holder name for this skip's file, resolved the one way the CIR allows: the file stem,
 * suffixed `Kt` when a TYPE of that name is declared in the same namespace -- which is the rule
 * `CirTranslator.resolveStaticClassName` applies to its own inputs. `ClawStrip.kt` beside
 * `class ClawStrip` therefore resolves `ClawStripKt`, with no dependence on the
 * `INFO_FILE_CLASS_RENAMED` note (which does not fire for that shape at all).
 */
private fun ForwardSkipRemark.fileClassName(typeNames: Set<String>): String {
  val fileClass: ForwardDiagnosticOwner.FileClass =
    ownerRoot() as? ForwardDiagnosticOwner.FileClass ?: return ""
  val stem: String = fileClass.fileStem.csharpIdentifier()
  return if (stem in typeNames) "${stem}Kt" else stem
}

/** The C# name a declaration is found under, for the file-holder conflict rule above. */
private fun CirDeclaration.declaredName(): String? = when (this) {
  is CirStaticClass -> name
  is CirClass -> name
  is CirSealedClass -> name
  is CirObject -> name
  is CirValueClass -> name
  is CirEnum -> name
  // An `IFoo` never claims the `Foo` a file holder wants; its ADR-040 backing wrapper is a
  // `CirClass` and is counted above.
  else -> null
}

/**
 * Attaches every skip whose owner path matches this declaration, and recurses into nested types and
 * sealed arms with the path extended.
 *
 * [path] is the chain of KOTLIN simple names walked so far, so `Gantry.Rung` and `Crate.Rung` can
 * never cross-attach: the first is matched at `["Gantry", "Rung"]` and the second at
 * `["Crate", "Rung"]`. A sealed arm declared BESIDE its base (`isNested = false`) has a Kotlin path
 * of `["Label"]` while C# renders it inside the base's block, which is why the sealed arms are
 * matched against both the extended path and the namespace-level one.
 */
private fun CirDeclaration.withSkipRemarks(
  skips: List<ForwardSkipRemark>,
  path: List<String>,
  holderSkips: (String) -> List<ForwardSkipRemark>,
): CirDeclaration = when (this) {
  is CirStaticClass -> {
    val mine: List<ForwardSkipRemark> = holderSkips(name)
    // ADR-075's partial skip reaches a TOP-LEVEL property too, and its surviving `CirProperty` is a
    // member of this holder rather than of a type, so the property route is applied here as well.
    // The `container` match is the holder itself, which `holderSkips` has already resolved by name.
    val propertySkips: List<ForwardSkipRemark> =
      mine.filter { skip -> skip.owner is ForwardDiagnosticOwner.Property }
    val ownerSkips: List<ForwardSkipRemark> = mine - propertySkips.toSet()
    copy(
      remarks = remarks + ownerSkips.map { it.paragraph },
      members = members.withMemberPropertyRemarks(propertySkips),
    )
  }

  is CirClass -> {
    // ADR-040's backing wrapper shares its Kotlin name with the interface it wraps, and `IFoo` is
    // the declaration the author's member was refused from, so the wrapper is never given a
    // paragraph: it would say the same thing twice, on two C# types, for one Kotlin declaration.
    val own: List<ForwardSkipRemark> =
      if (isSealed) emptyList() else skips.matching(path + name, forInterface = false)
    copy(
      remarks = remarks + own.map { it.paragraph },
      properties = properties.withPropertyRemarks(skips, path + name),
      nestedDeclarations = nestedDeclarations.map { nested ->
        nested.withSkipRemarks(skips, path + name, holderSkips)
      },
    )
  }

  is CirInterface -> copy(
    remarks = remarks + skips.matching(path + name, forInterface = true).map { it.paragraph },
    nestedDeclarations = nestedDeclarations.map { nested ->
      // A nested declaration's Kotlin owner is the interface's own Kotlin name, not the
      // `I`-prefixed C# one.
      nested.withSkipRemarks(skips, path + name.removeInterfacePrefix(), holderSkips)
    },
  )

  // ROADMAP Phase 4 x issue #249: an object's statics live in `methods` rather than in a
  // `properties` slot, so ADR-075's partial skip needs the member-list spelling of the property
  // route here. Without it an `object { var lastError: Throwable? }` -- whose setter alone is
  // refused -- recorded a `Property`-owned skip that matched no branch and attached to nothing.
  is CirObject -> copy(
    remarks = remarks + skips.matching(path + name, forInterface = false).map { it.paragraph },
    methods = methods.withMemberPropertyRemarks(skips.propertySkipsFor(path + name)),
    nestedDeclarations = nestedDeclarations.map { nested ->
      nested.withSkipRemarks(skips, path + name, holderSkips)
    },
  )

  is CirValueClass -> copy(
    remarks = remarks + skips.matching(path + name, forInterface = false).map { it.paragraph },
    properties = properties.withPropertyRemarks(skips, path + name),
  )

  is CirSealedClass -> copy(
    remarks = remarks + skips.matching(path + name, forInterface = false).map { it.paragraph },
    properties = properties.withPropertyRemarks(skips, path + name),
    subclasses = subclasses.map { subclass ->
      val armPaths: List<List<String>> =
        listOf(path + name + subclass.name, path + subclass.name)
      subclass.copy(
        remarks = subclass.remarks +
            armPaths.flatMap { armPath -> skips.matching(armPath, forInterface = false) }
              .distinct()
              .map { it.paragraph },
        properties = armPaths.fold(subclass.properties) { properties, armPath ->
          properties.withPropertyRemarks(skips, armPath)
        },
      )
    },
    nestedDeclarations = nestedDeclarations.map { nested ->
      nested.withSkipRemarks(skips, path + name, holderSkips)
    },
  )

  else -> this
}

/** ADR-075's partial skip, matched on the generated C# property name its owner carries. */
private fun List<CirProperty>.withPropertyRemarks(
  skips: List<ForwardSkipRemark>,
  path: List<String>,
): List<CirProperty> {
  val propertySkips: List<ForwardSkipRemark> = skips.propertySkipsFor(path)
  if (propertySkips.isEmpty()) return this
  return map { property -> property.withPropertyRemarks(propertySkips) }
}

/**
 * The same attachment over a MEMBER list, for the two declarations whose properties are not held in
 * a `properties` slot: the ADR-007 file holder and (ROADMAP Phase 4) the static class an `object`
 * becomes. One step, shared with [withPropertyRemarks], so an object's partial skip can never read
 * differently from a class's.
 */
private fun List<CirMember>.withMemberPropertyRemarks(
  propertySkips: List<ForwardSkipRemark>,
): List<CirMember> {
  if (propertySkips.isEmpty()) return this
  return map { member ->
    if (member is CirProperty) member.withPropertyRemarks(propertySkips) else member
  }
}

/** The per-property step both shapes share: every skip naming THIS generated C# property. */
private fun CirProperty.withPropertyRemarks(
  propertySkips: List<ForwardSkipRemark>,
): CirProperty {
  val mine: List<ForwardSkipRemark> = propertySkips.filter { skip ->
    (skip.owner as ForwardDiagnosticOwner.Property).publicName == name
  }
  return if (mine.isEmpty()) this else copy(remarks = remarks + mine.map { it.paragraph })
}

/** Every [ForwardDiagnosticOwner.Property] skip whose CONTAINER is the declaration at [path]. */
private fun List<ForwardSkipRemark>.propertySkipsFor(
  path: List<String>,
): List<ForwardSkipRemark> = filter { skip ->
  val owner: ForwardDiagnosticOwner = skip.owner
  owner is ForwardDiagnosticOwner.Property && owner.container.matchesPath(path, false)
}

private fun List<ForwardSkipRemark>.matching(
  path: List<String>,
  forInterface: Boolean,
): List<ForwardSkipRemark> = filter { skip ->
  skip.owner !is ForwardDiagnosticOwner.Property && skip.owner.matchesPath(path, forInterface)
}

private fun ForwardDiagnosticOwner.matchesPath(path: List<String>, forInterface: Boolean): Boolean {
  if (this !is ForwardDiagnosticOwner.Type) return false
  val candidate: List<String> =
    if (forInterface) path.dropLast(1) + path.last().removeInterfacePrefix() else path
  return this.path == candidate
}

/**
 * `IPounceable` back to the Kotlin `Pounceable` the producer named. An interface whose Kotlin name
 * genuinely starts with a capital `I` (`IOStream`) is unaffected: the second character decides.
 */
private fun String.removeInterfacePrefix(): String =
  if (length >= 2 && this[0] == 'I' && this[1].isUpperCase()) substring(1) else this
