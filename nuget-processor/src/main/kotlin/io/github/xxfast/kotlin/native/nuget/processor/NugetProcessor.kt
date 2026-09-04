package io.github.xxfast.kotlin.native.nuget.processor

import com.google.devtools.ksp.findActualType
import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeAlias
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Visibility
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.ksp.writeTo
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirFile
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirRenderer
import io.github.xxfast.kotlin.native.nuget.processor.cir.NugetContext
import io.github.xxfast.kotlin.native.nuget.processor.cir.STATE_FLOW_TYPES
import io.github.xxfast.kotlin.native.nuget.processor.cir.expandAliases
import io.github.xxfast.kotlin.native.nuget.processor.cir.translate
import io.github.xxfast.kotlin.native.nuget.processor.exports.addClassExports
import io.github.xxfast.kotlin.native.nuget.processor.exports.addCompanionExports
import io.github.xxfast.kotlin.native.nuget.processor.exports.addEnumExports
import io.github.xxfast.kotlin.native.nuget.processor.exports.addFunctionExports
import io.github.xxfast.kotlin.native.nuget.processor.exports.addGenericClassExports
import io.github.xxfast.kotlin.native.nuget.processor.exports.addGenericFunctionExports
import io.github.xxfast.kotlin.native.nuget.processor.exports.addCSharpBridgeMarker
import io.github.xxfast.kotlin.native.nuget.processor.exports.addGcCollectExport
import io.github.xxfast.kotlin.native.nuget.processor.exports.addInterfaceBridgeFactoryExport
import io.github.xxfast.kotlin.native.nuget.processor.exports.addInterfaceExports
import io.github.xxfast.kotlin.native.nuget.processor.exports.addNugetHelperExports
import io.github.xxfast.kotlin.native.nuget.processor.exports.addNugetListHelperExports
import io.github.xxfast.kotlin.native.nuget.processor.exports.addNugetMapHelperExports
import io.github.xxfast.kotlin.native.nuget.processor.exports.addNugetSetHelperExports
import io.github.xxfast.kotlin.native.nuget.processor.exports.addNugetWrapHelperExports
import io.github.xxfast.kotlin.native.nuget.processor.exports.addNugetFunc0HelperExports
import io.github.xxfast.kotlin.native.nuget.processor.exports.addNugetFunc1HelperExports
import io.github.xxfast.kotlin.native.nuget.processor.exports.addNugetFunc2HelperExports
import io.github.xxfast.kotlin.native.nuget.processor.exports.addNugetFunc3HelperExports
import io.github.xxfast.kotlin.native.nuget.processor.exports.addNugetSuspendFunc0HelperExports
import io.github.xxfast.kotlin.native.nuget.processor.exports.addNugetSuspendFunc1HelperExports
import io.github.xxfast.kotlin.native.nuget.processor.exports.addNugetScopeHelperExports
import io.github.xxfast.kotlin.native.nuget.processor.exports.addNugetScopeDrainExport
import io.github.xxfast.kotlin.native.nuget.processor.exports.addNugetJobHelperExports
import io.github.xxfast.kotlin.native.nuget.processor.exports.addNugetErrorHelperExports
import io.github.xxfast.kotlin.native.nuget.processor.exports.addNugetDurationHelperExports
import io.github.xxfast.kotlin.native.nuget.processor.exports.addNugetInstantHelperExports
import io.github.xxfast.kotlin.native.nuget.processor.exports.addStoredCallbackExports
import io.github.xxfast.kotlin.native.nuget.processor.exports.findStoredCallbackPairs
import io.github.xxfast.kotlin.native.nuget.processor.exports.findInterfaceBridgePairs
import io.github.xxfast.kotlin.native.nuget.processor.exports.addExtensionFunctionExports
import io.github.xxfast.kotlin.native.nuget.processor.exports.addExtensionPropertyExports
import io.github.xxfast.kotlin.native.nuget.processor.exports.addObjectExports
import io.github.xxfast.kotlin.native.nuget.processor.exports.addPropertyExports
import io.github.xxfast.kotlin.native.nuget.processor.exports.addSealedClassExports
import io.github.xxfast.kotlin.native.nuget.processor.exports.addStateFlowHandleExports
import io.github.xxfast.kotlin.native.nuget.processor.exports.addSuspendClassMethodExports
import io.github.xxfast.kotlin.native.nuget.processor.exports.addSuspendFunctionExports
import io.github.xxfast.kotlin.native.nuget.processor.exports.addValueClassExports
import io.github.xxfast.kotlin.native.nuget.processor.forward.BridgeType
import io.github.xxfast.kotlin.native.nuget.processor.forward.CollectionKind
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardBridgeTypeContext
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardBridgeTypeClassifier
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardCallableCatalogEntry
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardCallablePlan
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardCallablePlanCatalog
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardCallablePlanner
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnostic
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardPlanSkipReason
import io.github.xxfast.kotlin.native.nuget.processor.forward.diagnosticHint
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticSink
import io.github.xxfast.kotlin.native.nuget.processor.forward.renderForwardDiagnosticsJson
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticTrackingLogger
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardHelperRequirement
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardPropertyPlan
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardBridgeInterfacePlan
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardInterfaceBridgePlanner
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardPropertyPlanner
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardReachabilityBucket
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardReachabilityClosure
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardReachabilityResult
import io.github.xxfast.kotlin.native.nuget.processor.forward.addForwardKotlinPlanExport
import io.github.xxfast.kotlin.native.nuget.processor.forward.calls
import io.github.xxfast.kotlin.native.nuget.processor.forward.diagnosticHint
import io.github.xxfast.kotlin.native.nuget.processor.forward.isValueClass
import io.github.xxfast.kotlin.native.nuget.processor.forward.planFor
import io.github.xxfast.kotlin.native.nuget.processor.forward.toDiagnosticKind

// A `@kotlin.native.CName`-annotated function is already a C-ABI export by definition (its native
// export name is fixed by the annotation itself). It must never be picked up by the forward
// exporter's own top-level-function scan and re-wrapped in another `export_*` C-ABI wrapper: the
// reverse-direction generator (`NugetGenerateBindingsTask`, ADR-048) emits exactly this shape for
// its registration functions — `@CName("nuget_..._register") public fun nuget_..._register(...)`
// — and those raw `COpaquePointer` parameters do not round-trip through this translator, which
// expects ordinary Kotlin-authored public API. Excluding any `@CName` function here is the general,
// robust fix: it protects against this exact class of bug for any future generated C export, not
// just this one registration function.
// ADR-062/ADR-064: ordinary synchronous callables the planner cannot plan and no named legacy
// route re-emits simply disappear from the generated C# API (never as an `IntPtr` / `"0"`
// fallback). Warn once per dropped callable through the named ForwardDiagnostic sink, naming the
// symbol and the specific kind, so a consumer whose `fun f(m: Map<K, V>)` vanishes from C# learns
// why. Every one of these is `SKIPPED_*` (warning), never `ERROR_*`: existing fixtures
// intentionally contain such declarations and generation must keep succeeding.
internal fun warnDroppedForwardCallables(
  catalog: ForwardCallablePlanCatalog,
  logger: KSPLogger,
  // Issue #55: the effective include set, so an `include(...)` hint can name the whole line.
  scope: List<String> = emptyList(),
) {
  val diagnostics: List<ForwardDiagnostic> = catalog.droppedCallables.map { dropped ->
    ForwardDiagnostic(
      kind = dropped.reason.toDiagnosticKind(),
      symbol = dropped.node,
      declaration = dropped.symbol,
      reason = "its ${dropped.reason} type combination is not supported",
      hint = dropped.reason.diagnosticHint(dropped.detail, scope),
    )
  }
  ForwardDiagnosticSink.emit(diagnostics, logger)
}

// ADR-075 Decision 2/Question C: a mutable collection property whose element (or map key/value)
// fails `isWrappableComponent()` still plans -- get-only -- so this is a *partial* skip, not the
// callable-style "this member vanished entirely" one above. Wording says so explicitly: the C#
// property survives read-only, it was not dropped.
internal fun warnDroppedForwardPropertySetters(
  catalog: ForwardCallablePlanCatalog,
  logger: KSPLogger,
) {
  val diagnostics: List<ForwardDiagnostic> = catalog.droppedPropertySetters.map { dropped ->
    ForwardDiagnostic(
      kind = ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_INPUT,
      symbol = dropped.node,
      declaration = dropped.symbol,
      reason = "its setter is not generated because the ${dropped.componentDescription} cannot " +
          "be written into a Kotlin collection",
      hint = "the C# property ${dropped.publicName} is read-only",
    )
  }
  ForwardDiagnosticSink.emit(diagnostics, logger)
}

// The whole-property counterpart to the partial setter skip above: the property planner had no
// getter or setter shape for the declared type at all, so nothing of the member reaches C#. The
// hint names the property's own type rather than a fixed example, so it stays accurate for every
// type that lands here.
internal fun warnDroppedForwardProperties(
  catalog: ForwardCallablePlanCatalog,
  logger: KSPLogger,
) {
  val diagnostics: List<ForwardDiagnostic> = catalog.droppedProperties.map { dropped ->
    // ADR-088: a bound C# interface is bridgeable, just not at a property; its message says so
    // rather than telling the author to stop using the type.
    if (dropped.boundInterface) {
      ForwardDiagnostic(
        kind = ForwardDiagnosticKind.SKIPPED_BOUND_TYPE_POSITION,
        symbol = dropped.node,
        declaration = dropped.symbol,
        reason = "the bound C# interface ${dropped.typeDescription} is not marshalled at a " +
            "property position",
        hint = ForwardPlanSkipReason.BOUND_INTERFACE_POSITION.diagnosticHint(),
      )
    } else {
      ForwardDiagnostic(
        kind = ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_PROPERTY,
        symbol = dropped.node,
        declaration = dropped.symbol,
        reason = "its type ${dropped.typeDescription} has no property getter or setter shape",
        hint = "expose a bridgeable property (or a getter function) whose type is not " +
            "${dropped.typeDescription}, and export that instead",
      )
    }
  }
  ForwardDiagnosticSink.emit(diagnostics, logger)
}

// Second producer for the same kind (the established `SKIPPED_UNSUPPORTED_INPUT` pattern, fed by
// both setter drops and input skips): an extension property dropped for its *receiver* type rather
// than its own. The wording says receiver explicitly, because the property's declared type is
// typically supported and naming it would send the author after the wrong declaration.
internal fun warnDroppedForwardExtensionReceivers(
  catalog: ForwardCallablePlanCatalog,
  logger: KSPLogger,
) {
  val diagnostics: List<ForwardDiagnostic> = catalog.droppedExtensionReceivers.map { dropped ->
    ForwardDiagnostic(
      kind = ForwardDiagnosticKind.SKIPPED_UNSUPPORTED_PROPERTY,
      symbol = dropped.node,
      declaration = dropped.symbol,
      reason = "its extension receiver type ${dropped.receiverDescription} is not a supported " +
          "extension-property receiver",
      hint = "declare the property on a class, String, primitive, or value class receiver, or " +
          "expose a top-level getter function instead",
    )
  }
  ForwardDiagnosticSink.emit(diagnostics, logger)
}

private fun KSAnnotated.hasCNameAnnotation(): Boolean =
  annotations.any { annotation ->
    val name: String? = annotation.annotationType.resolve().declaration.qualifiedName?.asString()
    name == "kotlin.native.CName"
  }

/**
 * The generated C# in both the forms the ABI contract check needs: the CIR nodes for the ordinary
 * universe, and the rendered text that was written to Interop.cs for the legacy one (ADR-078).
 * Rendered once, so what is checked is what ships.
 */
private data class CsharpBindings(val cir: CirFile, val rendered: String)

class NugetProcessor(
  private val codeGenerator: CodeGenerator,
  logger: KSPLogger,
  private val context: NugetContext,
) : SymbolProcessor {

  private var processed = false

  private val renderer = CirRenderer()

  // ADR-064: every `logger.error(...)` this class makes (directly, or transitively through the
  // CIR translators it passes this to) routes through this wrapper, so `process()` can tell
  // whether an ERROR_* diagnostic (e.g. ERROR_CSHARP_SIGNATURE_COLLISION) fired during
  // generation, in time to skip writing `cNameExports.kt` for a construct that must never
  // compile — `logger.error` itself returns nothing, and KSP's own round-failure detection only
  // surfaces after `process()` returns.
  private val logger: ForwardDiagnosticTrackingLogger = ForwardDiagnosticTrackingLogger(logger)

  override fun process(resolver: Resolver): List<KSAnnotated> {
    if (processed) return emptyList()
    processed = true

    // ADR-100: the sink is a singleton inside a long-lived Gradle daemon; start this round empty so
    // NugetDiagnostics.json describes this compilation and no earlier one.
    ForwardDiagnosticSink.reset()

    // ADR-063: the effective include set is the explicit `include` when non-empty, else
    // `[rootPackage]` when `rootPackage` is set, else empty (= all). Mirrors the reverse side's
    // `IsNamespaceIncluded` predicate exactly (exclude wins; empty include = all; prefix match).
    val effectiveInclude: List<String> = when {
      context.includePackages.isNotEmpty() -> context.includePackages
      context.rootPackage.isNotBlank() -> listOf(context.rootPackage)
      else -> emptyList()
    }

    fun isPackageExported(pkg: String): Boolean {
      fun matches(p: String) = pkg == p || pkg.startsWith("$p.")
      // ADR-063 "Reverse-bound packages are always in scope": checked first, before exclude and
      // before include. A module that both publishes forward and consumes via `bind {}` returns
      // reverse-bound types from its own forward code; dropping the bound stub's declaration
      // while the forward-generated C# still references it is a dangling-reference build break,
      // not a scoping choice the user asked for.
      if (context.boundPackages.any(::matches)) return true
      if (context.excludePackages.any(::matches)) return false
      if (effectiveInclude.isEmpty()) return true
      return effectiveInclude.any(::matches)
    }

    // ADR-074: for a native compilation `getAllFiles()` returns both halves of every
    // `expect`/`actual` pair as two files of one compilation (Verified, spike finding 1), so this
    // raw list is the shared input for the `isExpect` filter below, the by-name expect index, and
    // Decision 2's `actual typealias` target map.
    val allFilesDeclarations: List<KSDeclaration> = resolver.getAllFiles()
      .flatMap { it.declarations }
      .toList()

    val candidateDeclarations: List<KSDeclaration> = allFilesDeclarations
      .filter { it.packageName.asString() != "io.github.xxfast.kotlin.native.nuget.generated" }
      // ADR-074: the `actual` is the export root. Without this every pair is planned twice under
      // one qualified name and trips a catalog duplicate guard. Same rule, same three declaration
      // kinds, as Kotlin/Native's own C export (`CAdapterGenerator`) and ObjC export. No
      // diagnostic: filtering an `expect` is normal and every Kotlin backend does it silently.
      .filter { !it.isExpect }
      .toList()

    val allDeclarations: List<KSDeclaration> = candidateDeclarations
      .filter { isPackageExported(it.packageName.asString()) }

    // Issue #55: scoping that admits nothing used to be indistinguishable from a module with no
    // public API at all: `packNuget` stayed green with no `Interop.cs` in the package. Say so
    // once, naming the scope that did it, before the `hasNothingToProcess` early return below.
    if (allDeclarations.isEmpty() && candidateDeclarations.isNotEmpty()) {
      val scope: String = buildList {
        if (context.includePackages.isNotEmpty()) {
          add("include(${context.includePackages.joinToString { "\"$it\"" }})")
        }
        if (context.excludePackages.isNotEmpty()) {
          add("exclude(${context.excludePackages.joinToString { "\"$it\"" }})")
        }
        if (context.rootPackage.isNotBlank()) add("rootPackage = \"${context.rootPackage}\"")
      }.joinToString()
      val packages: String = candidateDeclarations
        .map { it.packageName.asString() }
        .distinct()
        .sorted()
        .joinToString { "\"$it\"" }
      ForwardDiagnosticSink.emit(
        listOf(
          ForwardDiagnostic(
            kind = ForwardDiagnosticKind.SKIPPED_ALL_DECLARATIONS,
            symbol = null,
            declaration = context.libraryName,
            reason = "the export scope ($scope) admits none of the module's " +
                "${candidateDeclarations.size} public declaration(s) in package(s) $packages, " +
                "so no Interop.cs is generated",
            hint = "an explicit include replaces the rootPackage default rather than adding to " +
                "it: list your own package(s) in include(...) as well, or drop include(...) to " +
                "fall back to rootPackage",
          ),
        ),
        logger,
      )
    }

    // ADR-074: kept because the `actual` is structurally complete but metadata-poor (no KDoc, no
    // annotations, no parameter defaults). `findExpects()` is Verified empty on KSP 2.3.10, so the
    // qualified name is the only available link. Consumed by Decision 2 (actual typealias target
    // redirect) and Decision 3 (per-file C# static class naming).
    val expectsByName: Map<String, KSDeclaration> = allFilesDeclarations
      .filter { it.isExpect }
      .mapNotNull { declaration ->
        declaration.qualifiedName?.asString()?.let { name -> name to declaration }
      }
      .toMap()

    // ADR-074 Decision 2: collected from the same funnel input, before the `isExpect` filter drops
    // the paired expect class. `findActualType()` resolves the alias to its target
    // KSClassDeclaration (Verified, spike finding 8); keyed by the alias's own qualified name,
    // which is always the *expect*'s qualified name (Kotlin requires an `actual typealias` to
    // share the expect's package and simple name), since a type reference to that name is exactly
    // what the classifier/reachability-closure redirect needs to intercept.
    val actualTypeAliasTargets: Map<String, KSClassDeclaration> = allFilesDeclarations
      .filterIsInstance<KSTypeAlias>()
      .filter { it.isActual }
      .mapNotNull { alias -> alias.qualifiedName?.asString()?.let { it to alias.findActualType() } }
      .toMap()

    val allFunctions: List<KSFunctionDeclaration> = allDeclarations
      .filterIsInstance<KSFunctionDeclaration>()
      .filter { it.getVisibility() == Visibility.PUBLIC }
      .filter { it.parentDeclaration == null }
      .filter { it.extensionReceiver == null }
      .filter { !it.hasCNameAnnotation() }

    val extensionFunctions: List<KSFunctionDeclaration> = allDeclarations
      .filterIsInstance<KSFunctionDeclaration>()
      .filter { it.getVisibility() == Visibility.PUBLIC }
      .filter { it.parentDeclaration == null }
      .filter { it.extensionReceiver != null }
      .filter { !it.hasCNameAnnotation() }

    val (suspendFunctions, regularFunctions) = allFunctions
      .filter { it.typeParameters.isEmpty() }
      .partition { it.modifiers.contains(Modifier.SUSPEND) }

    val functions: List<KSFunctionDeclaration> = regularFunctions
    val genericFunctions: List<KSFunctionDeclaration> = allFunctions
      .filter { it.typeParameters.isNotEmpty() }

    val allProperties: List<KSPropertyDeclaration> = allDeclarations
      .filterIsInstance<KSPropertyDeclaration>()
      .filter { it.getVisibility() == Visibility.PUBLIC }
      .filter { it.parentDeclaration == null }
      .filter { it.extensionReceiver == null }

    val extensionProperties: List<KSPropertyDeclaration> = allDeclarations
      .filterIsInstance<KSPropertyDeclaration>()
      .filter { it.getVisibility() == Visibility.PUBLIC }
      .filter { it.parentDeclaration == null }
      .filter { it.extensionReceiver != null }

    val properties: List<KSPropertyDeclaration> = allProperties
      .filter { !it.modifiers.contains(Modifier.CONST) }
    val constProperties: List<KSPropertyDeclaration> = allProperties
      .filter { it.modifiers.contains(Modifier.CONST) }

    val rootClasses: List<KSClassDeclaration> = allDeclarations
      .filterIsInstance<KSClassDeclaration>()
      .filter { it.getVisibility() == Visibility.PUBLIC }
      .filter { it.classKind == ClassKind.CLASS }
      .filter { it.parentDeclaration == null }
      .filter { !it.modifiers.contains(Modifier.SEALED) }
      .filter { !it.isValueClass() }

    val rootValueClasses: List<KSClassDeclaration> = allDeclarations
      .filterIsInstance<KSClassDeclaration>()
      .filter { it.getVisibility() == Visibility.PUBLIC }
      .filter { it.classKind == ClassKind.CLASS }
      .filter { it.parentDeclaration == null }
      .filter { it.isValueClass() }

    val rootSealedClasses: List<KSClassDeclaration> = allDeclarations
      .filterIsInstance<KSClassDeclaration>()
      .filter { it.getVisibility() == Visibility.PUBLIC }
      .filter { it.classKind == ClassKind.CLASS }
      .filter { it.parentDeclaration == null }
      .filter { it.modifiers.contains(Modifier.SEALED) }

    val rootObjects: List<KSClassDeclaration> = allDeclarations
      .filterIsInstance<KSClassDeclaration>()
      .filter { it.getVisibility() == Visibility.PUBLIC }
      .filter { it.classKind == ClassKind.OBJECT }
      .filter { it.parentDeclaration == null }

    val rootEnums: List<KSClassDeclaration> = allDeclarations
      .filterIsInstance<KSClassDeclaration>()
      .filter { it.getVisibility() == Visibility.PUBLIC }
      .filter { it.classKind == ClassKind.ENUM_CLASS }
      .filter { it.parentDeclaration == null }

    val rootInterfaces: List<KSClassDeclaration> = allDeclarations
      .filterIsInstance<KSClassDeclaration>()
      .filter { it.getVisibility() == Visibility.PUBLIC }
      .filter { it.classKind == ClassKind.INTERFACE }
      .filter { it.parentDeclaration == null }
      // ADR-088: a bound interface's ADR-070 stub is now `public`, so without this it would enter
      // the forward export scan and be re-projected as a DUPLICATE `IIFeedable` + backing wrapper
      // (the shipped enum-duplication precedent, deliberately not extended). The classifier maps
      // it to the ORIGINAL `Test.Menagerie.IFeedable` instead, which is only coherent if this
      // pipeline never mints a second managed type for the same concept.
      .filter { it.qualifiedName?.asString() !in context.boundInterfaces }

    // ADR-066: the reachability closure discovers dependency-module (klib) declarations reachable
    // from these module-local roots — the only way in, since `getDeclarationsFromPackage` returns
    // empty for a klib dependency (verified). A discovered declaration is admitted iff it passes
    // the same `isPackageExported` predicate the roots already did, and only when the module
    // crosses into `include`/`rootPackage` scope at all (admission rule 4).
    val reachability: ForwardReachabilityResult = ForwardReachabilityClosure(
      isPackageExported = ::isPackageExported,
      crossModuleAdmissionAllowed = effectiveInclude.isNotEmpty(),
      actualTypeAliasTargets = actualTypeAliasTargets,
    ).walk(
      classes = rootClasses,
      valueClasses = rootValueClasses,
      sealedClasses = rootSealedClasses,
      objects = rootObjects,
      enums = rootEnums,
      interfaces = rootInterfaces,
      functions = functions + genericFunctions,
      extensionFunctions = extensionFunctions,
      properties = properties + constProperties,
      extensionProperties = extensionProperties,
    )
    if (reachability.admitted.isNotEmpty()) {
      ForwardDiagnosticSink.emit(
        listOf(
          ForwardDiagnostic(
            kind = ForwardDiagnosticKind.INFO_EXPORTED_FROM_DEPENDENCY,
            symbol = null,
            declaration = context.className,
            reason = "the export closure admitted ${reachability.admitted.size} type(s) from " +
                "dependency modules: " +
                reachability.admitted.keys.sorted().joinToString(", "),
            hint = "these are generated exactly like module-local types; " +
                "narrow with exclude(...) if any of them should not be part of the public API",
          ),
        ),
        logger,
      )
    }

    val dependencyByBucket: Map<ForwardReachabilityBucket, List<KSClassDeclaration>> =
      reachability.admitted.values.groupBy { cls ->
        reachability.bucketOf.getValue(requireNotNull(cls.qualifiedName).asString())
      }

    fun dependenciesIn(bucket: ForwardReachabilityBucket): List<KSClassDeclaration> =
      dependencyByBucket[bucket] ?: emptyList()

    val allClasses: List<KSClassDeclaration> =
      rootClasses + dependenciesIn(ForwardReachabilityBucket.CLASS)
    val valueClasses: List<KSClassDeclaration> =
      rootValueClasses + dependenciesIn(ForwardReachabilityBucket.VALUE_CLASS)
    val sealedClasses: List<KSClassDeclaration> =
      rootSealedClasses + dependenciesIn(ForwardReachabilityBucket.SEALED_CLASS)
    val objects: List<KSClassDeclaration> =
      rootObjects + dependenciesIn(ForwardReachabilityBucket.OBJECT)
    val enums: List<KSClassDeclaration> = rootEnums + dependenciesIn(ForwardReachabilityBucket.ENUM)
    val interfaces: List<KSClassDeclaration> =
      rootInterfaces + dependenciesIn(ForwardReachabilityBucket.INTERFACE)

    val classes: List<KSClassDeclaration> = allClasses.filter { it.typeParameters.isEmpty() }
    val genericClasses: List<KSClassDeclaration> = allClasses
      .filter { it.typeParameters.isNotEmpty() }

    val hasNothingToProcess: Boolean = functions.isEmpty() && genericFunctions.isEmpty() &&
        extensionFunctions.isEmpty() && extensionProperties.isEmpty() &&
        classes.isEmpty() && genericClasses.isEmpty() && enums.isEmpty() &&
        interfaces.isEmpty() && sealedClasses.isEmpty() && objects.isEmpty() &&
        properties.isEmpty() && constProperties.isEmpty() && valueClasses.isEmpty() &&
        suspendFunctions.isEmpty()
    if (hasNothingToProcess) {
      // Issue #55: the diagnostics file is what `nugetReportDiagnostics` re-emits, so the
      // SKIPPED_ALL_DECLARATIONS warning above has to reach it even though nothing else is written.
      writeForwardDiagnostics(Dependencies.ALL_FILES)
      return emptyList()
    }

    // This processor is whole-module by construction: every round rescans
    // resolver.getAllFiles() and rewrites one Interop.cs and one CNameExports.kt from
    // everything. A narrower dependency set (e.g. only the files backing the declaration
    // buckets above) has never been correct here, since it silently drops any bucket an
    // agent forgets to list (see ROADMAP.md Tier 1 suspendFunctions). ALL_FILES gives every
    // incremental round the same semantics as a clean build, the only configuration known
    // to be correct.
    val deps = Dependencies.ALL_FILES

    // Phase 2 shadow migration: construct the source-neutral catalog once while KSP symbols are
    // still available, before either legacy emitter runs. It remains observational for now.
    val exportedObjectHandles: Set<String> = buildSet {
      allClasses.forEach { cls -> cls.qualifiedName?.asString()?.let(::add) }
      enums.forEach { enum -> enum.qualifiedName?.asString()?.let(::add) }
      interfaces.forEach { iface -> iface.qualifiedName?.asString()?.let(::add) }
      sealedClasses.forEach { sealed ->
        sealed.qualifiedName?.asString()?.let(::add)
        sealed.getSealedSubclasses().forEach { subclass ->
          subclass.qualifiedName?.asString()?.let(::add)
        }
      }
      objects.forEach { obj -> obj.qualifiedName?.asString()?.let(::add) }
    }
    val forwardClassifier = ForwardBridgeTypeClassifier(
      ForwardBridgeTypeContext(
        exportedObjectHandles = exportedObjectHandles,
        rootPackage = context.rootPackage,
        rootNamespace = context.rootNamespace,
        actualTypeAliasTargets = actualTypeAliasTargets,
        boundInterfaces = context.boundInterfaces,
      ),
    )
    val forwardPlanner = ForwardCallablePlanner(forwardClassifier, expectsByName)
    val forwardPropertyPlanner = ForwardPropertyPlanner(forwardClassifier)
    val ordinaryCatalog: ForwardCallablePlanCatalog = forwardPlanner.catalog(
      classes, functions, extensionFunctions, objects, properties, extensionProperties, valueClasses,
    )

    // ADR-040 sub-decision C.1 (reachability-driven): a Kotlin interface gets a concrete backing
    // class + `foo_*` dispatch exports only when it actually appears in a planned return position
    // (method result, property type — including nullable) among the *ordinary* plans built above.
    // Computed before the interface's own members are planned, so there is no ordering cycle: an
    // interface's own members never mention that same interface as their receiver's result type.
    fun BridgeType.interfaceQualifiedNameOrNull(): String? {
      val unwrapped: BridgeType = if (this is BridgeType.Nullable) type else this
      return (unwrapped as? BridgeType.Interface)?.qualifiedName
    }

    val reachableInterfaceNames: Set<String> = buildSet {
      ordinaryCatalog.plans.forEach { plan ->
        plan.publicSignature.result.interfaceQualifiedNameOrNull()?.let(::add)
      }
      ordinaryCatalog.propertyPlans.forEach { plan ->
        plan.type.interfaceQualifiedNameOrNull()?.let(::add)
      }
    }
    val reachableInterfaces: List<KSClassDeclaration> = interfaces
      .filter { iface -> iface.qualifiedName?.asString() in reachableInterfaceNames }

    val interfaceEntries: List<ForwardCallableCatalogEntry> = reachableInterfaces.flatMap { iface ->
      forwardPlanner.interfaceEntries(iface)
    }
    val interfacePropertyPlans: List<ForwardPropertyPlan> = reachableInterfaces.flatMap { iface ->
      forwardPropertyPlanner.interfaceProperties(iface)
    }
    val callableCatalog: ForwardCallablePlanCatalog = ForwardCallablePlanCatalog(
      entries = ordinaryCatalog.entries + interfaceEntries,
      propertyPlans = ordinaryCatalog.propertyPlans + interfacePropertyPlans,
      // ADR-075: `ordinaryCatalog`'s own planner already folded the class/top-level/extension
      // property setter drops in; `forwardPropertyPlanner` here is the second, separate instance
      // (interface dispatch properties only), so its drops need adding explicitly.
      droppedPropertySetters = ordinaryCatalog.droppedPropertySetters +
          forwardPropertyPlanner.droppedPropertySetters,
      // Same two-instance merge as the setter drops above, for the whole-property channel.
      droppedProperties = ordinaryCatalog.droppedProperties +
          forwardPropertyPlanner.droppedProperties,
      // The interface-dispatch instance has no extension properties of its own, so this merge is
      // only ever the ordinary planner's list today; it is written as a merge anyway so a future
      // second producer does not silently lose its drops.
      droppedExtensionReceivers = ordinaryCatalog.droppedExtensionReceivers +
          forwardPropertyPlanner.droppedExtensionReceivers,
    )

    warnDroppedForwardCallables(callableCatalog, logger, effectiveInclude)
    warnDroppedForwardPropertySetters(callableCatalog, logger)
    warnDroppedForwardProperties(callableCatalog, logger)
    warnDroppedForwardExtensionReceivers(callableCatalog, logger)

    val cNameExports: FileSpec = generateCNameWrappers(
      functions, genericFunctions, extensionFunctions, extensionProperties,
      classes, genericClasses, enums, sealedClasses, objects, properties,
      valueClasses, suspendFunctions, callableCatalog, deps, reachableInterfaces,
    )
    val bindings: CsharpBindings = generateCSharpBindings(
      functions, genericFunctions, extensionFunctions, extensionProperties,
      allClasses, enums, interfaces, sealedClasses, objects, properties,
      constProperties, valueClasses, suspendFunctions, callableCatalog, deps, reachableInterfaces,
      expectsByName,
    )

    // ADR-064: an ERROR_* diagnostic (e.g. ERROR_CSHARP_SIGNATURE_COLLISION, ADR-034) already
    // failed this KSP round via logger.error above. Stop here rather than also running the ABI
    // contract checks and writing cNameExports.kt for a construct that must never compile.
    if (logger.hasFatalDiagnostic) return emptyList()

    // ADR-078: the ordinary universe comes off the CIR nodes, the specialized legacy protocols off
    // the same rendered text that was just written to Interop.cs, so both halves of every route are
    // compared rather than only the planned ones.
    val ordinaryContracts: List<ForwardAbiSignature> = ForwardAbiContract.csharp(bindings.cir)
    val csharpContracts: List<ForwardAbiSignature> = ordinaryContracts +
        ForwardAbiContract.csharpLegacy(
          bindings.rendered,
          ordinaryContracts.map { signature -> signature.exportName }.toSet(),
        )
    ForwardAbiContract.assertMatches(
      csharp = csharpContracts,
      kotlin = ForwardAbiContract.kotlin(cNameExports, csharpContracts.map { it.exportName }.toSet()),
    )
    ForwardAbiContract.assertMatchesPlan(
      catalog = callableCatalog,
      csharp = csharpContracts,
      kotlin = ForwardAbiContract.kotlin(
        cNameExports,
        (
            callableCatalog.plans.flatMap { plan -> plan.nativeExports.map { call -> call.exportName } } +
                callableCatalog.propertyPlans.flatMap { plan -> plan.calls().map { call -> call.exportName } }
            ).toSet(),
      ),
    )
    cNameExports.writeTo(codeGenerator, deps)
    writeForwardDiagnostics(deps)

    logger.info(
      "Generated bindings for ${functions.size} functions" +
          ", ${genericFunctions.size} generic functions" +
          ", ${extensionFunctions.size} extension functions" +
          ", ${extensionProperties.size} extension properties" +
          ", ${classes.size} classes" +
          ", ${genericClasses.size} generic classes" +
          ", ${enums.size} enums" +
          ", ${interfaces.size} interfaces" +
          ", ${sealedClasses.size} sealed classes" +
          ", ${objects.size} objects" +
          ", ${properties.size} properties" +
          ", ${constProperties.size} const properties" +
          ", and ${valueClasses.size} value classes"
    )

    return emptyList()
  }

  /**
   * ADR-100: the delivery channel for forward diagnostics. `KSPLogger.warn` output never reaches
   * the console (KSP runs the processor on a Worker API thread and its stdout is dropped), and a
   * normal `packNuget` does not even run the KSP task (`FROM-CACHE`, then `UP-TO-DATE`), so a
   * transport that only speaks during the task action is silent on most builds. A *declared KSP
   * output file* survives both: it is restored on a cache hit and present on an up-to-date run, and
   * `NugetReportDiagnosticsTask` re-emits it through Gradle's own `Task.logger`.
   *
   * `json` lands in the KSP resources dir (`CodeGeneratorImpl.extensionToDirectory` routes every
   * extension but `class`/`java`/`kt` there), beside `Interop.cs`, which is exactly where the
   * plugin already looks. Written unconditionally, empty array included, so "no file" unambiguously
   * means "KSP never ran for this target" rather than "no skips".
   */
  private fun writeForwardDiagnostics(deps: Dependencies) {
    val json: String = renderForwardDiagnosticsJson(ForwardDiagnosticSink.recorded())
    codeGenerator
      .createNewFile(
        dependencies = deps,
        packageName = "",
        fileName = "NugetDiagnostics",
        extensionName = "json",
      )
      .writer()
      .use { writer -> writer.write(json) }
  }

  private fun generateCSharpBindings(
    functions: List<KSFunctionDeclaration>,
    genericFunctions: List<KSFunctionDeclaration>,
    extensionFunctions: List<KSFunctionDeclaration>,
    extensionProperties: List<KSPropertyDeclaration>,
    classes: List<KSClassDeclaration>,
    enums: List<KSClassDeclaration>,
    interfaces: List<KSClassDeclaration>,
    sealedClasses: List<KSClassDeclaration>,
    objects: List<KSClassDeclaration>,
    properties: List<KSPropertyDeclaration>,
    constProperties: List<KSPropertyDeclaration>,
    valueClasses: List<KSClassDeclaration>,
    suspendFunctions: List<KSFunctionDeclaration>,
    callableCatalog: ForwardCallablePlanCatalog,
    deps: Dependencies,
    reachableInterfaces: List<KSClassDeclaration>,
    expectsByName: Map<String, KSDeclaration>,
  ): CsharpBindings {
    val cirFile: CirFile = translate(
      context,
      logger,
      functions,
      genericFunctions,
      classes,
      enums,
      interfaces,
      sealedClasses,
      objects,
      properties,
      constProperties,
      extensionFunctions,
      extensionProperties,
      valueClasses,
      suspendFunctions,
      callableCatalog,
      reachableInterfaces,
      expectsByName,
    )

    val csharp: String = renderer.render(cirFile)

    val file = codeGenerator.createNewFile(
      dependencies = deps,
      packageName = "",
      fileName = "Interop",
      extensionName = "cs",
    )

    file.writer().use { writer -> writer.write(csharp) }
    return CsharpBindings(cirFile, csharp)
  }

  private fun generateCNameWrappers(
    functions: List<KSFunctionDeclaration>,
    genericFunctions: List<KSFunctionDeclaration>,
    extensionFunctions: List<KSFunctionDeclaration>,
    extensionProperties: List<KSPropertyDeclaration>,
    classes: List<KSClassDeclaration>,
    genericClasses: List<KSClassDeclaration>,
    enums: List<KSClassDeclaration>,
    sealedClasses: List<KSClassDeclaration>,
    objects: List<KSClassDeclaration>,
    properties: List<KSPropertyDeclaration>,
    valueClasses: List<KSClassDeclaration>,
    suspendFunctions: List<KSFunctionDeclaration>,
    callableCatalog: ForwardCallablePlanCatalog,
    deps: Dependencies,
    reachableInterfaces: List<KSClassDeclaration>,
  ): FileSpec {
    val builder: FileSpec.Builder = FileSpec
      .builder("io.github.xxfast.kotlin.native.nuget.generated", "CNameExports")
      .addImport("kotlinx.cinterop", "asStableRef")
      .addImport("kotlinx.cinterop", "COpaquePointerVar")
      .addImport("kotlinx.cinterop", "reinterpret")
      .addImport("kotlinx.cinterop", "pointed")
      .addImport("kotlinx.cinterop", "value")
      .addImport("kotlinx.cinterop", "StableRef")

    functions.forEach { func ->
      builder.addImport(func.packageName.asString(), func.simpleName.asString())
      // ADR-095: node identity, not a name-derived symbol — top-level overloads number per
      // (package, name), so the n-th namesake's plan is keyed `..._$n`.
      // ADR-096: plural — a defaulted top-level function also carries its synthesized omitting
      // overloads on the same node.
      val planned: List<ForwardCallablePlan> = callableCatalog.plansFor(func)
      if (planned.isNotEmpty()) planned.forEach { builder.addForwardKotlinPlanExport(it) }
      else builder.addFunctionExports(func)
    }

    genericFunctions.forEach { func ->
      builder.addImport(func.packageName.asString(), func.simpleName.asString())
      builder.addGenericFunctionExports(func)
    }

    classes.forEach { builder.addClassExports(it, callableCatalog) }
    classes.forEach { builder.addCompanionExports(it, callableCatalog) }
    genericClasses.forEach { builder.addGenericClassExports(it) }
    enums.forEach { builder.addEnumExports(it) }
    sealedClasses.forEach { builder.addSealedClassExports(it) }
    objects.forEach { builder.addObjectExports(it, callableCatalog) }
    valueClasses.forEach { builder.addValueClassExports(it, callableCatalog) }
    reachableInterfaces.forEach { builder.addInterfaceExports(it, callableCatalog) }
    // ADR-084 stage 1: the per-interface bridge factory, projected from the same slot plan the C#
    // `{Iface}BridgeState` is projected from (see `ForwardInterfaceBridgePlanner`).
    val bridgePlans: List<ForwardBridgeInterfacePlan> =
      reachableInterfaces.mapNotNull { iface -> ForwardInterfaceBridgePlanner.plan(iface) }
    bridgePlans.forEach { plan -> builder.addInterfaceBridgeFactoryExport(plan) }
    // ADR-084 stage 2: the release path is only observable with a forced GC round, so the support
    // export ships with the factories it exists to exercise.
    if (bridgePlans.isNotEmpty()) builder.addGcCollectExport()
    // ADR-084 facet 5: the marker and its probe pair with `NugetMarshal.TryResolveCSharp`, which
    // every module carrying an interface return emits, so both halves are unconditional.
    builder.addCSharpBridgeMarker()

    val suspendLambdaTypes: Set<String> = setOf(
      "kotlin.coroutines.SuspendFunction0",
      "kotlin.coroutines.SuspendFunction1",
      "kotlin.coroutines.SuspendFunction2",
      "kotlin.coroutines.SuspendFunction3",
    )

    fun KSType.isSuspendLambdaType(): Boolean =
      expandAliases().declaration.qualifiedName?.asString() in suspendLambdaTypes

    fun KSType.suspendLambdaArity(): Int =
      expandAliases().arguments.size - 1

    val suspendLambdaArities: MutableSet<Int> = mutableSetOf()

    (classes + genericClasses).forEach { cls ->
      cls.getAllProperties().forEach { prop ->
        val propType: KSType = prop.type.resolve()
        if (propType.isSuspendLambdaType()) suspendLambdaArities.add(propType.suspendLambdaArity())
      }
    }

    val needsSuspendLambdaSupport: Boolean = suspendLambdaArities.isNotEmpty()

    val hasSuspendFunctions: Boolean = suspendFunctions.isNotEmpty() ||
        needsSuspendLambdaSupport ||
        classes.any { cls -> cls.getAllFunctions().any { it.modifiers.contains(Modifier.SUSPEND) } }

    val classesHaveFlowPropertiesForImports: Boolean = classes.any { cls ->
      cls.getAllProperties().any { prop ->
        val qualified: String? =
          prop.type.resolve().expandAliases().declaration.qualifiedName?.asString()
        qualified == "kotlinx.coroutines.flow.Flow" || qualified in STATE_FLOW_TYPES
      }
    }

    val classesHaveFlowMethodsForImports: Boolean = classes.any { cls ->
      cls.getAllFunctions().any { method ->
        val qualified: String? =
          method.returnType?.resolve()?.expandAliases()?.declaration?.qualifiedName?.asString()
        qualified == "kotlinx.coroutines.flow.Flow" || qualified in STATE_FLOW_TYPES
      }
    }

    val needsFlowImports: Boolean = classesHaveFlowPropertiesForImports ||
        classesHaveFlowMethodsForImports

    // The coroutines opt-in is gated on the SAME condition as the coroutines imports below: every
    // emission that names anything from `kotlinx.coroutines` (suspend functions and suspend
    // lambdas, `Flow`/`StateFlow`, the scope helpers) sits under it. Naming the marker
    // unconditionally forced a library with no suspend/`Flow` surface to depend on
    // `kotlinx-coroutines-core` just to resolve an annotation it never otherwise needed.
    val optIns: List<ClassName> = buildList {
      add(ClassName("kotlin.experimental", "ExperimentalNativeApi"))
      add(ClassName("kotlinx.cinterop", "ExperimentalForeignApi"))
      if (hasSuspendFunctions || needsFlowImports) {
        add(ClassName("kotlinx.coroutines", "ExperimentalCoroutinesApi"))
      }
    }

    builder.addAnnotation(
      AnnotationSpec.builder(ClassName("kotlin", "OptIn"))
        .addMember(
          optIns.joinToString(", ") { "%T::class" },
          *optIns.toTypedArray(),
        )
        .build()
    )

    val lambdaTypeSet: Set<String> = setOf(
      "kotlin.Function0", "kotlin.Function1", "kotlin.Function2", "kotlin.Function3",
    )

    val hasLambdaParamMethods: Boolean = classes.any { cls ->
      cls.getAllFunctions().any { method ->
        method.parameters.any { param ->
          param.type.resolve().expandAliases().declaration.qualifiedName?.asString() in lambdaTypeSet
        }
      }
    }

    // Stored-callback pairs also need invoke/CFunction/COpaquePointer (the bridge lambda calls fn.invoke).
    val hasStoredCallbackMethods: Boolean = classes.any { cls ->
      val lambdaParamMethods: List<KSFunctionDeclaration> = cls.getAllFunctions()
        .filter { method ->
          method.parameters.any { param ->
            param.type.resolve().expandAliases().declaration.qualifiedName?.asString() in lambdaTypeSet
          }
        }.toList()
      findStoredCallbackPairs(lambdaParamMethods).isNotEmpty()
    }

    // Interface-bridge pairs also need invoke/CFunction/COpaquePointer (each method's fn.invoke).
    val hasInterfaceBridgeMethods: Boolean = classes.any { cls ->
      val allMethods: List<KSFunctionDeclaration> = cls.getAllFunctions().toList()
      findInterfaceBridgePairs(allMethods).isNotEmpty()
    }

    // ADR-084: every bridge factory slot is a `fn.invoke(...)` on a reinterpreted CFunction too.
    val hasBridgeFactories: Boolean = bridgePlans.isNotEmpty()

    // ADR-084 stage 2: the bridge object's cleaner.
    if (hasBridgeFactories) builder.addImport("kotlin.native.ref", "createCleaner")

    val needsCallbackImports: Boolean = hasLambdaParamMethods || hasStoredCallbackMethods ||
        hasInterfaceBridgeMethods || hasBridgeFactories
    if (needsCallbackImports && !hasSuspendFunctions && !needsFlowImports) {
      builder.addImport("kotlinx.cinterop", "invoke")
      builder.addImport("kotlinx.cinterop", "CFunction")
      builder.addImport("kotlinx.cinterop", "COpaquePointer")
    }

    if (hasSuspendFunctions || needsFlowImports) {
      builder.addImport("kotlinx.cinterop", "reinterpret")
      builder.addImport("kotlinx.cinterop", "invoke")
      builder.addImport("kotlinx.cinterop", "CFunction")
      builder.addImport("kotlinx.cinterop", "COpaquePointer")
      builder.addImport("kotlinx.cinterop", "StableRef")
      builder.addImport("kotlinx.coroutines", "CoroutineScope")
      builder.addImport("kotlinx.coroutines", "CoroutineStart")
      builder.addImport("kotlinx.coroutines", "Dispatchers")
      builder.addImport("kotlinx.coroutines", "launch")
      builder.addImport("kotlinx.coroutines", "SupervisorJob")
      builder.addImport("kotlinx.coroutines", "cancel")
      builder.addImport("kotlinx.coroutines", "CancellationException")
      builder.addImport("kotlinx.coroutines", "ExperimentalCoroutinesApi")
    }

    if (needsSuspendLambdaSupport) {
      builder.addImport("kotlin.coroutines", "SuspendFunction0")
      builder.addImport("kotlin.coroutines", "SuspendFunction1")
    }

    suspendFunctions.forEach { func ->
      builder.addImport(func.packageName.asString(), func.simpleName.asString())
      builder.addSuspendFunctionExports(func)
    }

    classes.forEach { cls ->
      val hasSuspendMethods: Boolean = cls.getAllFunctions()
        .any { it.modifiers.contains(Modifier.SUSPEND) }
      if (hasSuspendMethods) builder.addSuspendClassMethodExports(cls)
    }

    properties.forEach { prop ->
      builder.addImport(prop.packageName.asString(), prop.simpleName.asString())
      builder.addPropertyExports(prop, callableCatalog)
    }

    extensionFunctions.forEach { func ->
      builder.addImport(func.packageName.asString(), func.simpleName.asString())
      builder.addExtensionFunctionExports(func, callableCatalog)
    }

    // The import lives inside addExtensionPropertyExports, behind the plan gate: adding it here
    // left a dead import for every dropped extension property.
    extensionProperties.forEach { prop ->
      builder.addExtensionPropertyExports(prop, callableCatalog)
    }

    val listTypes: Set<String> = setOf("kotlin.collections.List", "kotlin.collections.MutableList")

    fun KSType.isListType(): Boolean =
      expandAliases().declaration.qualifiedName?.asString() in listTypes

    val classesHaveLists: Boolean = (classes + genericClasses)
      .any { cls -> cls.getAllProperties().any { prop -> prop.type.resolve().isListType() } }

    val functionsReturnLists: Boolean = (functions + genericFunctions)
      .any { func -> func.returnType?.resolve()?.isListType() == true }

    val sealedClassesHaveLists: Boolean = sealedClasses
      .any { sealed ->
        sealed.getSealedSubclasses().any { sub ->
          sub.getAllProperties().any { prop -> prop.type.resolve().isListType() }
        }
      }

    // ADR-061: class-method and extension-function returns are a distinct position the property/
    // top-level-function scans above never covered — widen the gate or the shared NugetListNative
    // helper/exports a List-returning method boxes its handle for are never emitted.
    val classMethodsReturnLists: Boolean = classes
      .any { cls ->
        val methodsReturnLists: Boolean = cls.getAllFunctions()
          .any { method ->
            val symbol: String = "${cls.qualifiedName?.asString()}.${method.simpleName.asString()}"
            callableCatalog.planFor(symbol) == null && method.returnType?.resolve()?.isListType() == true
          }
        val companionMethodsReturnLists: Boolean = cls.declarations
          .filterIsInstance<KSClassDeclaration>()
          .filter { declaration -> declaration.isCompanionObject }
          .flatMap { companion -> companion.getAllFunctions() }
          .any { method -> method.returnType?.resolve()?.isListType() == true }
        methodsReturnLists || companionMethodsReturnLists
      }

    val extensionFunctionsReturnLists: Boolean = extensionFunctions
      .any { func ->
        val symbol: String = "${func.packageName.asString()}.${func.simpleName.asString()}"
        callableCatalog.planFor(symbol) == null && func.returnType?.resolve()?.isListType() == true
      }

    // ADR-099: recursive, because a nested component needs its own kind's Kotlin exports. A
    // `Set<List<String>>` parameter calls `nuget_list_create`/`nuget_list_add` one level down, and
    // reading only the outer kind would leave those exports unemitted.
    fun BridgeType.componentCollectionKinds(): Sequence<CollectionKind> = sequence {
      val unwrapped: BridgeType = if (this@componentCollectionKinds is BridgeType.Nullable) type
      else this@componentCollectionKinds
      val collection: BridgeType.Collection = unwrapped as? BridgeType.Collection ?: return@sequence
      yield(collection.kind)
      collection.element?.let { yieldAll(it.componentCollectionKinds()) }
      collection.key?.let { yieldAll(it.componentCollectionKinds()) }
      collection.value?.let { yieldAll(it.componentCollectionKinds()) }
    }

    fun ForwardCallablePlan.collectionKinds(): Sequence<CollectionKind> = sequence {
      yieldAll(publicSignature.result.componentCollectionKinds())
      publicSignature.parameters.forEach { parameter ->
        yieldAll(parameter.type.componentCollectionKinds())
      }
    }

    fun plannedCollectionKinds(): Sequence<CollectionKind> = sequence {
      callableCatalog.plans.forEach { plan -> yieldAll(plan.collectionKinds()) }
      callableCatalog.propertyPlans.forEach { plan ->
        yieldAll(plan.type.componentCollectionKinds())
      }
    }

    val needsListSupport: Boolean = classesHaveLists || functionsReturnLists ||
        sealedClassesHaveLists || classMethodsReturnLists || extensionFunctionsReturnLists ||
        plannedCollectionKinds().any { kind ->
          kind == CollectionKind.LIST || kind == CollectionKind.MUTABLE_LIST
        }

    val mapTypes: Set<String> = setOf("kotlin.collections.Map", "kotlin.collections.MutableMap")

    fun KSType.isMapType(): Boolean =
      expandAliases().declaration.qualifiedName?.asString() in mapTypes

    val classesHaveMaps: Boolean = (classes + genericClasses)
      .any { cls -> cls.getAllProperties().any { prop -> prop.type.resolve().isMapType() } }

    val functionsReturnMaps: Boolean = (functions + genericFunctions)
      .any { func ->
        val symbol: String = "${func.packageName.asString()}.${func.simpleName.asString()}"
        callableCatalog.planFor(symbol) == null && func.returnType?.resolve()?.isMapType() == true
      }

    val sealedClassesHaveMaps: Boolean = sealedClasses
      .any { sealed ->
        sealed.getSealedSubclasses().any { sub ->
          sub.getAllProperties().any { prop -> prop.type.resolve().isMapType() }
        }
      }

    val needsMapSupport: Boolean = classesHaveMaps || functionsReturnMaps || sealedClassesHaveMaps ||
        plannedCollectionKinds().any { kind ->
          kind == CollectionKind.MAP || kind == CollectionKind.MUTABLE_MAP
        }

    val setTypes: Set<String> = setOf("kotlin.collections.Set", "kotlin.collections.MutableSet")

    fun KSType.isSetType(): Boolean =
      expandAliases().declaration.qualifiedName?.asString() in setTypes

    val classesHaveSets: Boolean = (classes + genericClasses)
      .any { cls -> cls.getAllProperties().any { prop -> prop.type.resolve().isSetType() } }

    val functionsReturnSets: Boolean = (functions + genericFunctions)
      .any { func ->
        val symbol: String = "${func.packageName.asString()}.${func.simpleName.asString()}"
        callableCatalog.planFor(symbol) == null && func.returnType?.resolve()?.isSetType() == true
      }

    val sealedClassesHaveSets: Boolean = sealedClasses
      .any { sealed ->
        sealed.getSealedSubclasses().any { sub ->
          sub.getAllProperties().any { prop -> prop.type.resolve().isSetType() }
        }
      }

    val needsSetSupport: Boolean = classesHaveSets || functionsReturnSets || sealedClassesHaveSets ||
        plannedCollectionKinds().any { kind ->
          kind == CollectionKind.SET || kind == CollectionKind.MUTABLE_SET
        }

    val lambdaTypes: Set<String> = setOf(
      "kotlin.Function0", "kotlin.Function1", "kotlin.Function2", "kotlin.Function3",
    )

    fun KSType.isLambdaType(): Boolean =
      expandAliases().declaration.qualifiedName?.asString() in lambdaTypes

    fun KSType.lambdaArity(): Int =
      expandAliases().arguments.size - 1

    val lambdaArities: MutableSet<Int> = mutableSetOf()

    (classes + genericClasses).forEach { cls ->
      cls.getAllProperties().forEach { prop ->
        val propType: KSType = prop.type.resolve()
        if (propType.isLambdaType()) lambdaArities.add(propType.lambdaArity())
      }
    }

    (functions + genericFunctions).forEach { func ->
      val returnType: KSType? = func.returnType?.resolve()
      if (returnType?.isLambdaType() == true) lambdaArities.add(returnType.lambdaArity())
    }

    sealedClasses.forEach { sealed ->
      sealed.getSealedSubclasses().forEach { sub ->
        sub.getAllProperties().forEach { prop ->
          val propType: KSType = prop.type.resolve()
          if (propType.isLambdaType()) lambdaArities.add(propType.lambdaArity())
        }
      }
    }

    val needsLambdaSupport: Boolean = lambdaArities.isNotEmpty()

    // The C# `NugetMarshal` helper class is emitted for any module that declares a function, class,
    // object or sealed class (`CirTranslator`'s core-marshal condition), and it declares the
    // `nuget_unwrap_*` / `nuget_dispose` / `nuget_wrap_*` imports unconditionally in its body. The
    // Kotlin exports have to ship under at least as broad a condition, or the C# side imports
    // symbols the native library never exported.
    val needsCoreMarshal: Boolean = functions.isNotEmpty() || classes.isNotEmpty() ||
        objects.isNotEmpty() || sealedClasses.isNotEmpty() || suspendFunctions.isNotEmpty() ||
        properties.isNotEmpty()

    val needsHelpers: Boolean = genericClasses.isNotEmpty() || needsCoreMarshal ||
        needsListSupport || needsMapSupport || needsSetSupport || needsLambdaSupport
    if (needsHelpers) builder.addNugetHelperExports()
    if (needsListSupport) builder.addNugetListHelperExports()
    if (needsMapSupport) builder.addNugetMapHelperExports()
    if (needsSetSupport) builder.addNugetSetHelperExports()

    // A collection *parameter* (Phase 7) needs `nuget_wrap_*` too: `NugetMarshal.CreateList<T>`
    // boxes each primitive/String element through the matching wrap export before handing it to
    // `nuget_list_add`. Computed once so lambda/suspend-lambda support sharing the same gate below
    // never emits the wrap exports twice.
    //
    // ADR-075: a property *setter* is the same `CreateList`/`CreateMap`/`CreateSet` write side, so
    // it needs the same gate -- a class whose only collection input is a property setter (no
    // method/constructor collection parameter anywhere) would otherwise generate C# calling
    // `nuget_wrap_*` against a native library that never exported it. `helperRequirements` also
    // carries `COLLECTION` for a getter-only collection property (the *read* side never needs
    // wrapping), so this is gated on `setter != null`, not on the marker alone.
    val needsCollectionParamWrap: Boolean = callableCatalog.plans.any { plan ->
      ForwardHelperRequirement.COLLECTION in plan.helperRequirements
    } || callableCatalog.propertyPlans.any { plan ->
      plan.setter != null && ForwardHelperRequirement.COLLECTION in plan.helperRequirements
    }
    var wrapHelpersEmitted = false
    fun addNugetWrapHelperExportsOnce() {
      if (wrapHelpersEmitted) return
      wrapHelpersEmitted = true
      builder.addNugetWrapHelperExports()
    }

    if (
      (needsLambdaSupport && lambdaArities.any { it > 0 }) ||
      needsCollectionParamWrap ||
      needsCoreMarshal
    ) {
      addNugetWrapHelperExportsOnce()
    }
    if (0 in lambdaArities) builder.addNugetFunc0HelperExports()
    if (1 in lambdaArities) builder.addNugetFunc1HelperExports()
    if (2 in lambdaArities) builder.addNugetFunc2HelperExports()
    if (3 in lambdaArities) builder.addNugetFunc3HelperExports()

    val needsSuspendWrap: Boolean = needsSuspendLambdaSupport &&
        suspendLambdaArities.any { it > 0 } && !needsLambdaSupport
    if (needsSuspendWrap) addNugetWrapHelperExportsOnce()
    if (0 in suspendLambdaArities) builder.addNugetSuspendFunc0HelperExports()
    if (1 in suspendLambdaArities) builder.addNugetSuspendFunc1HelperExports()

    val classesHaveSuspendMethods: Boolean = classes.any { cls ->
      cls.getAllFunctions().any { it.modifiers.contains(Modifier.SUSPEND) }
    }

    val flowTypes: Set<String> = setOf("kotlinx.coroutines.flow.Flow") + STATE_FLOW_TYPES

    fun KSType.isFlowType(): Boolean =
      expandAliases().declaration.qualifiedName?.asString() in flowTypes

    val classesHaveFlowProperties: Boolean = classes.any { cls ->
      cls.getAllProperties().any { prop -> prop.type.resolve().isFlowType() }
    }

    val classesHaveFlowMethods: Boolean = classes.any { cls ->
      cls.getAllFunctions().any { method ->
        method.returnType?.resolve()?.isFlowType() == true
      }
    }

    val needsFlowSupport: Boolean = classesHaveFlowProperties || classesHaveFlowMethods

    if (needsFlowSupport) builder.addImport("kotlinx.coroutines.flow", "collect")

    val needsScopeHelpers: Boolean = suspendFunctions.isNotEmpty() ||
        needsSuspendLambdaSupport || classesHaveSuspendMethods || needsFlowSupport
    if (needsScopeHelpers) builder.addNugetScopeHelperExports()
    if (needsScopeHelpers) builder.addNugetScopeDrainExport()
    if (needsScopeHelpers) builder.addNugetJobHelperExports()
    builder.addNugetErrorHelperExports()

    // ADR-076: the toDotNetTicks()/instantFromDotNetTicks() conversion pair, generated only when
    // some plan actually crosses an Instant.
    val needsInstantSupport: Boolean = callableCatalog.plans.any { plan ->
      ForwardHelperRequirement.INSTANT in plan.helperRequirements
    } || callableCatalog.propertyPlans.any { plan ->
      ForwardHelperRequirement.INSTANT in plan.helperRequirements
    }
    if (needsInstantSupport) builder.addNugetInstantHelperExports()

    // ADR-103: likewise the toDotNetTicks()/durationFromDotNetTicks() pair, gated on a plan
    // actually crossing a Duration.
    val needsDurationSupport: Boolean = callableCatalog.plans.any { plan ->
      ForwardHelperRequirement.DURATION in plan.helperRequirements
    } || callableCatalog.propertyPlans.any { plan ->
      ForwardHelperRequirement.DURATION in plan.helperRequirements
    }
    if (needsDurationSupport) builder.addNugetDurationHelperExports()

    // ADR-068: `suspend fun` returning StateFlow<T>/MutableStateFlow<T> needs the two shared
    // generic exports keyed on the awaited flow's own handle -- generated once per module,
    // regardless of how many suspend-StateFlow members exist across every class.
    val classesHaveSuspendStateFlowMethods: Boolean = classes.any { cls ->
      cls.getAllFunctions().any { method ->
        method.modifiers.contains(Modifier.SUSPEND) &&
            method.returnType?.resolve()?.expandAliases()
              ?.declaration?.qualifiedName?.asString() in STATE_FLOW_TYPES
      }
    }
    if (classesHaveSuspendStateFlowMethods) builder.addStateFlowHandleExports()

    return builder.build()
  }

}
