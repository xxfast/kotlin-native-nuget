package io.github.xxfast.kotlin.native.nuget.processor.forward

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import io.github.xxfast.kotlin.native.nuget.processor.ForwardSymbolTable
import io.github.xxfast.kotlin.native.nuget.processor.cir.FLOW_TYPES
import io.github.xxfast.kotlin.native.nuget.processor.cir.STATE_FLOW_TYPES
import io.github.xxfast.kotlin.native.nuget.processor.cir.expandAliases

/**
 * ADR-208 part E: which holder a flow type argument materialises as, and the Kotlin type its
 * handle is read at. The one place a `kotlinx.coroutines.flow` declaration is mapped for the
 * type-argument position.
 */
internal enum class ForwardFlowArgumentKind(
  /** The C# holder's simple name, declared in the root namespace by `CirFlowHelper`. */
  val csharpName: String,
  /** The segment of the generated export's name. */
  val symbol: String,
  /** The simple name of the `kotlinx.coroutines.flow` type the export reads the handle at. */
  val kotlinName: String,
) {
  /**
   * `Flow`, and (ADR-205) `SharedFlow` / `MutableSharedFlow`, which spell `KotlinFlow<T>` and
   * collect through the plain `Flow` export like every other `FLOW_TYPES` position.
   */
  FLOW("KotlinFlow", "flow", "Flow"),

  /**
   * `StateFlow`, and `MutableStateFlow` as a read-only view: ADR-071's write seam is keyed on a
   * member, and a boxed flow has none.
   */
  STATE_FLOW("KotlinStateFlow", "stateflow", "StateFlow"),
  ;

  companion object {
    /** The kind of a flow declaration by qualified name, or null when it is not one. */
    fun of(qualifiedName: String?): ForwardFlowArgumentKind? = when (qualifiedName) {
      // ADR-065's order: a StateFlow is-a Flow, so it is tested first.
      in STATE_FLOW_TYPES -> STATE_FLOW
      in FLOW_TYPES -> FLOW
      else -> null
    }
  }
}

/**
 * ADR-208 part E: one closed `Flow<E>` / `StateFlow<E>` instantiation used as a type argument of
 * an exported generic class (`Box<Flow<Mood>>`). Both halves read this one record: the Kotlin half
 * emits its handle-keyed `_collect` (and, for a state flow, `_value`) export, and the C# half the
 * `[DllImport]`s and the `NugetMarshal.Factories` line that builds `box.Value` from the handle.
 */
internal class ForwardFlowArgument(
  val kind: ForwardFlowArgumentKind,
  /** `global::Root.KotlinFlow<global::Root.Boxes.Mood>`: the argument's spelling and its key. */
  val csharpType: String,
  /** The element type, aliases expanded, carrying its own nullability. */
  val element: KSType,
  /** The element as Kotlin spells it (`kotlin.Int`, `pkg.Mood?`), which names the export. */
  val elementKotlinSpelling: String,
  /** The `read:` (and `release:`) arguments of an element `FromHandle<T>` cannot read, or null. */
  val read: String?,
  /** A collection element, projected per component on the Kotlin half. */
  val collection: BridgeType.Collection?,
  /** A bare `ByteArray` element, which reads through `NugetMarshal.ReadBytes`. */
  val bytes: Boolean,
  /** A `Throwable` element, which crosses as its ADR-107 envelope. */
  val envelope: Boolean,
  /** The generic class first seen holding this argument: the export's ADR-117 owner. */
  val owner: KSClassDeclaration,
) {
  /**
   * The module-local entry-point stem, named by the element so it is stable across unrelated
   * edits: `<lib>_flowarg_flow_kotlin_Int`, `<lib>_flowarg_stateflow_pkg_Mood_nullable`.
   */
  fun exportStem(symbols: ForwardSymbolTable): String =
    "${symbols.librarySegment}_flowarg_${kind.symbol}_${elementKotlinSpelling.mangled()}"

  /** The C# extern name stem inside `NugetMarshal`. */
  val nativeStem: String
    get() = "Native_FlowArg_${kind.symbol}_${elementKotlinSpelling.mangled()}"
}

/** A Kotlin type spelling as C identifier characters: `kotlin.collections.List<pkg.Mood>?`. */
private fun String.mangled(): String = this
  .replace("?", "_nullable")
  .replace("<", "_of_")
  .replace(">", "")
  .replace(", ", "_and_")
  .replace(Regex("[^A-Za-z0-9_]"), "_")

/** The outcome of reading one type argument as a flow. */
internal sealed interface ForwardFlowArgumentReading {
  data class Bound(val argument: ForwardFlowArgument) : ForwardFlowArgumentReading

  data class Refused(val why: String) : ForwardFlowArgumentReading
}

/** Whether this type argument is a `Flow` / `StateFlow` (or `SharedFlow`) at all. */
internal fun KSType.isFlowTypeArgument(): Boolean =
  ForwardFlowArgumentKind.of(expandAliases().declaration.qualifiedName?.asString()) != null

/**
 * ADR-208 part E: reads [argument] (a type argument of [owner] for which [isFlowTypeArgument]
 * holds) as a materialisable flow, or says why nothing can materialise it. The element vocabulary
 * is the one a `Flow` member's own element has ([legacyFlowElementShape]); an element with no
 * type arguments is further held to what `NugetMarshal.FromHandle<T>` reads.
 */
internal fun ForwardBridgeTypeClassifier.flowArgument(
  argument: KSType,
  owner: KSClassDeclaration,
): ForwardFlowArgumentReading {
  val expanded: KSType = argument.expandAliases()
  val qualifiedName: String? = expanded.declaration.qualifiedName?.asString()
  val kind: ForwardFlowArgumentKind = requireNotNull(ForwardFlowArgumentKind.of(qualifiedName)) {
    "flowArgument was handed $argument, which is not a flow"
  }
  if (expanded.mentionsAnyTypeParameter()) {
    return ForwardFlowArgumentReading.Refused(
      "its type argument `$argument` is an open flow, and a flow argument is materialised by an " +
          "export generated for one closed element type",
    )
  }
  val element: KSType = expanded.arguments.firstOrNull()?.type?.resolve()?.expandAliases()
    ?: return ForwardFlowArgumentReading.Refused(
      "its type argument `$argument` is a star-projected flow, which has no element to read",
    )
  val nullable: Boolean = element.isMarkedNullable
  val refused = ForwardFlowArgumentReading.Refused(
    "the erased wire cannot read its type argument `$argument`: the flow's element has no " +
        "bridge shape",
  )
  val shape: ForwardLegacyFlowElementShape = legacyFlowElementShape(element)
  val collection: BridgeType.Collection? =
    (shape as? ForwardLegacyFlowElementShape.Marshalled)?.type
  val bytes: Boolean = shape is ForwardLegacyFlowElementShape.Bytes
  val envelope: Boolean = shape is ForwardLegacyFlowElementShape.Envelope
  val elementInterface: BridgeType.Interface? = legacyFlowElementInterface(element)
  val elementCsharpType: String = when (shape) {
    is ForwardLegacyFlowElementShape.Refused -> return refused
    is ForwardLegacyFlowElementShape.Marshalled -> shape.type.forwardPublicCsharpType()
    is ForwardLegacyFlowElementShape.Bytes -> legacyBytesCsharpType(nullable)
    is ForwardLegacyFlowElementShape.Envelope -> legacyEnvelopeCsharpType(nullable)
    ForwardLegacyFlowElementShape.Plain -> {
      val classified: BridgeType = classify(element).sealedAsHandle()
      val readable: BridgeType = classified.unwrapNullable()
      if (readable is BridgeType.TypeParameter || !readable.isErasedTypeArgument()) return refused
      classified.forwardPublicCsharpType()
    }
  }
  val read: String? = when {
    collection != null -> legacyFlowElementReadArgument(collection)
    bytes -> legacyBytesElementReadArgument(nullable)
    envelope -> legacyEnvelopeElementReadArgument(nullable)
    elementInterface != null -> legacyInterfaceElementReadArgument(elementInterface, nullable)
    else -> null
  }
  val holder: String =
    if (rootNamespace.isEmpty()) kind.csharpName else "global::$rootNamespace.${kind.csharpName}"
  return ForwardFlowArgumentReading.Bound(
    ForwardFlowArgument(
      kind = kind,
      csharpType = "$holder<$elementCsharpType>",
      element = element,
      elementKotlinSpelling = element.forwardKotlinArgumentSpelling(),
      read = read,
      collection = collection,
      bytes = bytes,
      envelope = envelope,
      owner = owner,
    ),
  )
}
