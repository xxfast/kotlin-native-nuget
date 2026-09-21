package io.github.xxfast.kotlin.native.nuget.rir

import io.github.xxfast.kotlin.native.nuget.allDiagnostics

/**
 * The reverse diagnostics census: what a real published NuGet package actually does when the
 * whole reverse pipeline is pointed at it. One [Census] per package, rendered to a stable JSON
 * string and committed as a golden, so a bridge change that starts (or stops) binding real
 * members shows up as a reviewable diff instead of as nothing.
 *
 * Deliberately NOT a quality metric. [PublicSurfaceCensus] is the honest denominator, counted by
 * the reader off the metadata; [MemberCensus.seen] is only what the RIR plus the reader's own
 * diagnostics can see, and the gap between the two is the members this project drops with no
 * diagnostic at all.
 */
data class Census(
  val packageName: String,
  val version: String,
  val asset: String,
  val includes: List<String>,
  val reader: String,
  val readerError: String? = null,
  val generation: String,
  val generationError: String? = null,
  val nullability: NullabilityCensus = NullabilityCensus(),
  val publicSurface: PublicSurfaceCensus? = null,
  val types: TypeCensus = TypeCensus(),
  val members: Map<String, MemberCensus> = emptyMap(),
  val diagnostics: Map<String, Int> = emptyMap(),
  val unboundTypeReferences: Map<String, Int> = emptyMap(),
  val collapsedOverloads: CollapsedOverloadCensus = CollapsedOverloadCensus(),
  val errors: List<String> = emptyList(),
)

data class NullabilityCensus(
  val assemblyOblivious: Boolean = false,
  val obliviousMembers: Int = 0,
)

data class PublicSurfaceCensus(
  val types: Int,
  val nestedTypes: Int,
  val structs: Int,
  val methods: Int,
  val constructors: Int,
  val properties: Int,
  val operators: Int,
  val events: Int,
  val genericMethods: Int,
) {
  // The denominator the bound counts are compared against: every member-shaped thing, never the
  // types. Operators and events are counted in, because today they are dropped silently and
  // the whole point of the denominator is that a silent drop costs the ratio.
  val members: Int get() = methods + constructors + properties + operators + events
}

data class TypeCensus(
  val cls: Int = 0,
  val staticClass: Int = 0,
  val interface_: Int = 0,
  val enum: Int = 0,
  val struct: Int = 0,
  val genericDefinition: Int = 0,
)

data class MemberCensus(val seen: Int, val bound: Int)

data class CollapsedOverloadCensus(
  val sets: Int = 0,
  val membersDropped: Int = 0,
  val bridgeableMethods: Int = 0,
  val examples: List<String> = emptyList(),
)

/**
 * Builds the census for one package from its parsed RIR. Pure: no process launch, no file
 * access, so the shape is unit-testable against a hand-built [RirFile] in the ordinary `test`
 * task, and the dogfood task only supplies the real input.
 */
fun census(
  rir: RirFile,
  packageName: String,
  version: String,
  asset: String,
  includes: List<String> = emptyList(),
  generation: String = "ok",
  generationError: String? = null,
): Census {
  val boundTypes: Set<RirTypeKey> = boundHandleTypes(rir)
  val boundInterfaces: Map<RirTypeKey, RirInterface> = boundInterfaceTypes(rir)
  val genericDefs: Map<RirTypeKey, RirClass> = boundGenericClassDefinitions(rir)

  val classes: List<RirClass> = rir.allTypes().filterIsInstance<RirClass>()
  val interfaces: List<RirInterface> = rir.allTypes().filterIsInstance<RirInterface>()
  val structs: List<RirStruct> = rir.allTypes().filterIsInstance<RirStruct>()
  val enums: List<RirEnum> = rir.allTypes().filterIsInstance<RirEnum>()

  // `bound` is counted with the four bridgeable* filters, NEVER bridgeableRegistrables: a
  // registrable list splits a property into getter and setter, which makes "bound" exceed
  // "seen".
  val boundConstructors: Int = classes.sumOf {
    bridgeableConstructors(it, boundTypes, boundInterfaces, genericDefs).size
  } + structs.sumOf { bridgeableStructConstructors(it, boundTypes).size }
  val boundStaticMethods: Int = classes.sumOf {
    bridgeableStaticMethods(it, boundTypes, boundInterfaces, genericDefs).size
  }
  val boundInstanceMethods: Int = classes.sumOf {
    bridgeableInstanceMethods(it, boundTypes, boundInterfaces, genericDefs).size
  } + interfaces.sumOf { it.methods.size } + structs.sumOf { it.methods.size }
  val boundProperties: Int = classes.sumOf {
    bridgeableProperties(it, boundTypes, boundInterfaces, genericDefs).size
  } + interfaces.sumOf { it.properties.size } + structs.sumOf { it.properties.size }

  // `seen` is what the RIR carries plus what a reader diagnostic named, which is the widest
  // total this side of the bridge can observe. The reader's diagnostics do not say which member
  // SHAPE they refused, so they all land on one bucket rather than being guessed into four.
  val readerDiagnostics: List<RirDiagnostic> = rir.assemblies.flatMap { it.diagnostics }
  // Only the `skipped_*` kinds: an `info_*` entry (an oblivious member, a deferred async
  // shape's note) names a member that may well be BOUND, so counting those here would inflate
  // the skip bucket by 84 members on Humanizer.Core alone.
  val namedSkips: Int = readerDiagnostics.count {
    it.memberName.isNotEmpty() && it.kind.wireName().startsWith("skipped_")
  }

  val rirConstructors: Int =
    classes.sumOf { it.constructors.size } + structs.sumOf { it.constructors.size }
  val rirStaticMethods: Int = classes.sumOf { cls -> cls.methods.count { it.isStatic } }
  val rirInstanceMethods: Int = classes.sumOf { cls -> cls.methods.count { !it.isStatic } } +
      interfaces.sumOf { it.methods.size } + structs.sumOf { it.methods.size }
  val rirProperties: Int = classes.sumOf { it.properties.size } +
      interfaces.sumOf { it.properties.size } + structs.sumOf { it.properties.size }

  val collapsed: List<List<RirMethod>> = classes.flatMap {
    collapsedOverloadSets(it, boundTypes, boundInterfaces, genericDefs)
  }
  val bridgeableMethods: Int = boundStaticMethods + classes.sumOf {
    bridgeableInstanceMethods(it, boundTypes, boundInterfaces, genericDefs).size
  }

  // Every diagnostic that will ever reach a consumer's log, reader-emitted and plugin-derived
  // alike, bucketed by kind. validateDiagnostics is NOT called: an `error_*` kind is a census
  // row, not an abort.
  val all: List<RirDiagnostic> = allDiagnostics(rir).map { it.second }

  return Census(
    packageName = packageName,
    version = version,
    asset = asset,
    includes = includes,
    reader = "ok",
    readerError = null,
    generation = generation,
    generationError = generationError,
    nullability = NullabilityCensus(
      // ADR-053 collapses the whole-assembly case to ONE entry naming no member; an oblivious
      // island inside an annotated assembly keeps its per-member entries. The two are told
      // apart by exactly that, so a scalar "oblivious" would be wrong on both.
      assemblyOblivious = readerDiagnostics.any {
        it.kind == RirDiagnosticKind.INFO_OBLIVIOUS_NULLABILITY && it.memberName.isEmpty()
      },
      obliviousMembers = readerDiagnostics.count {
        it.kind == RirDiagnosticKind.INFO_OBLIVIOUS_NULLABILITY && it.memberName.isNotEmpty()
      },
    ),
    publicSurface = rir.assemblies.firstNotNullOfOrNull { it.publicSurface }?.let {
      PublicSurfaceCensus(
        types = it.types,
        nestedTypes = it.nestedTypes,
        structs = it.structs,
        methods = it.methods,
        constructors = it.constructors,
        properties = it.properties,
        operators = it.operators,
        events = it.events,
        genericMethods = it.genericMethods,
      )
    },
    types = TypeCensus(
      cls = classes.count { !it.isStatic && it.typeParameters.isEmpty() },
      staticClass = classes.count { it.isStatic },
      interface_ = interfaces.size,
      enum = enums.size,
      struct = structs.size,
      genericDefinition = genericDefs.size,
    ),
    members = mapOf(
      "constructor" to MemberCensus(seen = rirConstructors, bound = boundConstructors),
      "staticMethod" to MemberCensus(seen = rirStaticMethods, bound = boundStaticMethods),
      "instanceMethod" to
        MemberCensus(seen = rirInstanceMethods, bound = boundInstanceMethods),
      "property" to MemberCensus(seen = rirProperties, bound = boundProperties),
      "readerSkippedMember" to MemberCensus(seen = namedSkips, bound = 0),
    ),
    diagnostics = all.groupingBy { it.kind.wireName() }.eachCount().toSortedMap(),
    unboundTypeReferences = all
      .filter { it.kind == RirDiagnosticKind.SKIPPED_UNBOUND_TYPE_REFERENCE }
      .mapNotNull { backtickedSubject(it.reason) }
      .groupingBy { it }
      .eachCount()
      .toSortedMap(),
    collapsedOverloads = CollapsedOverloadCensus(
      sets = collapsed.size,
      // ADR-155 drops the WHOLE set, so every member of every collapsed set is lost.
      membersDropped = collapsed.sumOf { it.size },
      bridgeableMethods = bridgeableMethods,
      examples = collapsed
        .take(5)
        .map { set -> set.joinToString(" | ") { "${it.name}${it.managedSignature}" } }
        .sorted(),
    ),
    errors = all
      .filter { it.kind.wireName().startsWith("error_") }
      .map { "${it.kind.wireName()}: ${it.typeName}.${it.memberName}" }
      .sorted(),
  )
}

/**
 * The row for a package the reader could not read at all. There is no RIR, so every RIR-derived
 * cell is absent rather than zero: a committed `reader: "failed"` golden on a real, popular
 * package is a status line, and it turns green in a diff the day the reader stops crashing.
 */
fun readerFailureCensus(
  packageName: String,
  version: String,
  asset: String,
  includes: List<String> = emptyList(),
  readerError: String,
): Census = Census(
  packageName = packageName,
  version = version,
  asset = asset,
  includes = includes,
  reader = "failed",
  readerError = readerError,
  generation = "not_reached",
)

// Every RirDiagnosticKind's @SerialName is its own name, lowercased; derived rather than looked
// up so the census never carries a second copy of the vocabulary that could drift from RirModel.
internal fun RirDiagnosticKind.wireName(): String = name.lowercase()

// The unbound type's name lives only inside the diagnostic's prose today (there is no `subject`
// field on RirDiagnostic), and the reader always backticks it first. Nothing depends on this
// being exhaustive: a reason with no backticked token simply does not contribute to the histogram.
internal fun backtickedSubject(reason: String): String? {
  val open: Int = reason.indexOf('`')
  if (open < 0) return null
  val close: Int = reason.indexOf('`', open + 1)
  if (close < 0) return null
  val subject: String = reason.substring(open + 1, close)
  return subject.ifEmpty { null }
}

internal fun RirFile.allTypes(): List<RirType> =
  assemblies.flatMap { assembly -> assembly.namespaces.flatMap { it.types } }

/**
 * Renders a [Census] as JSON with a fixed key order, sorted maps, no timestamps and no
 * absolute paths, so a golden diff shows only what actually moved. Hand-written rather than
 * kotlinx.serialization because the key order and the two-space layout ARE the contract here.
 */
fun Census.toStableJson(): String {
  val out = StringBuilder()
  out.append("{\n")
  out.append("  \"package\": ${quote(packageName)},\n")
  out.append("  \"version\": ${quote(version)},\n")
  out.append("  \"asset\": ${quote(asset)},\n")
  out.append("  \"includes\": ${stringArray(includes)},\n")
  out.append("  \"reader\": ${quote(reader)},\n")
  out.append("  \"readerError\": ${readerError?.let { quote(it) } ?: "null"},\n")
  out.append("  \"generation\": ${quote(generation)},\n")
  out.append("  \"generationError\": ${generationError?.let { quote(it) } ?: "null"},\n")
  out.append("  \"nullability\": {\n")
  out.append("    \"assemblyOblivious\": ${nullability.assemblyOblivious},\n")
  out.append("    \"obliviousMembers\": ${nullability.obliviousMembers}\n")
  out.append("  },\n")
  if (publicSurface == null) {
    out.append("  \"publicSurface\": null,\n")
  } else {
    out.append("  \"publicSurface\": {\n")
    out.append(
      listOf(
        "types" to publicSurface.types,
        "nestedTypes" to publicSurface.nestedTypes,
        "structs" to publicSurface.structs,
        "methods" to publicSurface.methods,
        "constructors" to publicSurface.constructors,
        "properties" to publicSurface.properties,
        "operators" to publicSurface.operators,
        "events" to publicSurface.events,
        "genericMethods" to publicSurface.genericMethods,
      ).joinToString(",\n") { (key, value) -> "    ${quote(key)}: $value" },
    )
    out.append("\n  },\n")
  }
  out.append("  \"types\": {\n")
  out.append(
    listOf(
      "class" to types.cls,
      "staticClass" to types.staticClass,
      "interface" to types.interface_,
      "enum" to types.enum,
      "struct" to types.struct,
      "genericDefinition" to types.genericDefinition,
    ).joinToString(",\n") { (key, value) -> "    ${quote(key)}: $value" },
  )
  out.append("\n  },\n")
  if (members.isEmpty()) {
    out.append("  \"members\": {},\n")
  } else {
    out.append("  \"members\": {\n")
    out.append(
      members.toSortedMap().entries.joinToString(",\n") { (key, value) ->
        "    ${quote(key)}: { \"seen\": ${value.seen}, \"bound\": ${value.bound} }"
      },
    )
    out.append("\n  },\n")
  }
  out.append("  \"diagnostics\": ${countMap(diagnostics, "  ")},\n")
  out.append("  \"unboundTypeReferences\": ${countMap(unboundTypeReferences, "  ")},\n")
  out.append("  \"collapsedOverloads\": {\n")
  out.append("    \"sets\": ${collapsedOverloads.sets},\n")
  out.append("    \"membersDropped\": ${collapsedOverloads.membersDropped},\n")
  out.append("    \"bridgeableMethods\": ${collapsedOverloads.bridgeableMethods},\n")
  out.append("    \"examples\": ${stringArray(collapsedOverloads.examples)}\n")
  out.append("  },\n")
  out.append("  \"errors\": ${stringArray(errors)}\n")
  out.append("}\n")
  return out.toString()
}

private fun countMap(values: Map<String, Int>, indent: String): String {
  if (values.isEmpty()) return "{}"
  return values.entries.joinToString(
    separator = ",\n",
    prefix = "{\n",
    postfix = "\n$indent}",
  ) { (key, value) -> "$indent  ${quote(key)}: $value" }
}

private fun stringArray(values: List<String>): String =
  if (values.isEmpty()) "[]" else values.joinToString(", ", "[", "]") { quote(it) }

private fun quote(value: String): String {
  val out = StringBuilder("\"")
  value.forEach { ch ->
    when (ch) {
      '"' -> out.append("\\\"")
      '\\' -> out.append("\\\\")
      '\n' -> out.append("\\n")
      '\r' -> out.append("\\r")
      '\t' -> out.append("\\t")
      else -> if (ch < ' ') out.append("\\u%04x".format(ch.code)) else out.append(ch)
    }
  }
  return out.append('"').toString()
}
