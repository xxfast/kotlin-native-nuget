package io.github.xxfast.kotlin.native.nuget.processor.cir

import io.github.xxfast.kotlin.native.nuget.processor.asCSymbol
import com.google.devtools.ksp.processing.KSPLogger
import io.github.xxfast.kotlin.native.nuget.processor.ExpectIndex
import io.github.xxfast.kotlin.native.nuget.processor.ForwardSymbolTable
import io.github.xxfast.kotlin.native.nuget.processor.forward.isIntermediateGenericSealedArm
import io.github.xxfast.kotlin.native.nuget.processor.forward.csharpMemberName
import io.github.xxfast.kotlin.native.nuget.processor.forward.declaredCSharpName
import io.github.xxfast.kotlin.native.nuget.processor.sanitizeLibrarySegment
import io.github.xxfast.kotlin.native.nuget.processor.csharpIdentifier
import io.github.xxfast.kotlin.native.nuget.processor.csharpParameterName
import io.github.xxfast.kotlin.native.nuget.processor.kotlinConstantToPascalCase
import io.github.xxfast.kotlin.native.nuget.processor.forward.BridgeType
import io.github.xxfast.kotlin.native.nuget.processor.forward.forwardGuardName
import io.github.xxfast.kotlin.native.nuget.processor.forward.guarded
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardBoundInterface
import io.github.xxfast.kotlin.native.nuget.processor.forward.forwardCallbackDelegate
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardBridgeTypeClassifier
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardBridgeTypeContext
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Modifier
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardCallablePlan
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardCallablePlanCatalog
import io.github.xxfast.kotlin.native.nuget.processor.forward.enumArmName
import io.github.xxfast.kotlin.native.nuget.processor.forward.isEnumArm
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardCirPlanProjection
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardCirPropertyProjection
import io.github.xxfast.kotlin.native.nuget.processor.forward.enumMembersOf
import io.github.xxfast.kotlin.native.nuget.processor.forward.enumReceiverName
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardBridgeInterfacePlan
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnostic
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticSink
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticOwner
import io.github.xxfast.kotlin.native.nuget.processor.forward.forwardDiagnosticOwner
import io.github.xxfast.kotlin.native.nuget.processor.forward.forwardFileClassOwner
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardInterfaceBridgePlanner
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardPropertyPlan
import io.github.xxfast.kotlin.native.nuget.processor.forward.PublishedScope
import io.github.xxfast.kotlin.native.nuget.processor.forward.planFor
import io.github.xxfast.kotlin.native.nuget.processor.toCName

private fun syncErrorArguments(parameters: String): String = if (parameters.isEmpty()) {
  "out IntPtr error"
} else {
  "$parameters, out IntPtr error"
}

internal data class NugetContext(
  val libraryName: String,
  val rootNamespace: String,
  val rootPackage: String,
  val className: String,
  val includePackages: List<String> = emptyList(),
  val excludePackages: List<String> = emptyList(),
  /** ADR-154: the additive `admit(...)` entries — qualified type names and/or package prefixes
   *  — which admit a DEPENDENCY-module declaration into ADR-066's closure and do nothing else.
   *  They never pick roots, never replace the `rootPackage` default, and are matched with the
   *  exact `isUnderPackage` rule `exclude` uses (issue #53). Empty is the shipped default. */
  val admit: List<String> = emptyList(),
  /** ADR-154 §6: opt-in. Escalates a dependency-scope skip the author can act on (refusal
   *  `NOT_INCLUDED` or `CROSS_MODULE_ADMISSION_DISABLED`) from a warning to an error, on both the
   *  callable and the property route. An `exclude(...)`-caused skip stays a warning: the author
   *  already declared that omission deliberate. */
  val strictDependencyTypes: Boolean = false,
  val boundPackages: List<String> = emptyList(),
  /** ADR-088: the bound C# interfaces the plugin's `bound-types.json` manifest declares, keyed by
   *  the generated Kotlin stub's qualified name. Empty when nothing is bound, or when the option
   *  is absent (an older plugin against a newer processor), in which case every bound interface at
   *  a forward position keeps the pre-ADR-088 behaviour. */
  val boundInterfaces: Map<String, ForwardBoundInterface> = emptyMap(),
  /** ADR-109: the export scope of every OTHER forward publisher in this Gradle build, delivered by
   *  the plugin as the `nuget.publishedScopes` option (this module's own entry is dropped at parse
   *  time). Empty when this module is the only publisher, when the option is absent (an older
   *  plugin against a newer processor), or when the project does not publish at all — in every
   *  case no duplicate-type warning can fire, which is the pre-ADR-109 behaviour. */
  val publishedScopes: List<PublishedScope> = emptyList(),
  /** ADR-115 amendment: the fully-qualified `@RequiresOptIn` marker names `publish {
   *  exportMarkers(...) }` waives, so a declaration carrying one exports as if it carried no
   *  marker at all. Empty is the shipped default (every marked declaration skips). */
  val exportMarkers: Set<String> = emptySet(),
) {
  /**
   * ADR-163: the one forward symbol table, derived from [libraryName] and [rootPackage] and carried
   * here so every `cir/` site that already holds a context holds the table too. [NugetProcessor]
   * reads this same instance to hand to the planners and the legacy `exports/` builders, so there
   * is exactly one per processing round (`by lazy`, not a `get()`, so memoized qualifiers survive).
   */
  internal val symbols: ForwardSymbolTable by lazy {
    ForwardSymbolTable(sanitizeLibrarySegment(libraryName), rootPackage)
  }
}

internal fun translate(
  context: NugetContext,
  logger: KSPLogger,
  functions: List<KSFunctionDeclaration>,
  genericFunctions: List<KSFunctionDeclaration>,
  classes: List<KSClassDeclaration>,
  enums: List<KSClassDeclaration> = emptyList(),
  interfaces: List<KSClassDeclaration> = emptyList(),
  sealedClasses: List<KSClassDeclaration> = emptyList(),
  objects: List<KSClassDeclaration> = emptyList(),
  properties: List<KSPropertyDeclaration> = emptyList(),
  constProperties: List<KSPropertyDeclaration> = emptyList(),
  extensionFunctions: List<KSFunctionDeclaration> = emptyList(),
  extensionProperties: List<KSPropertyDeclaration> = emptyList(),
  valueClasses: List<KSClassDeclaration> = emptyList(),
  suspendFunctions: List<KSFunctionDeclaration> = emptyList(),
  callableCatalog: ForwardCallablePlanCatalog = ForwardCallablePlanCatalog(emptyList()),
  // ADR-040: the reachability-driven subset of [interfaces] that appears in a planned return
  // position, and therefore gets a concrete `sealed class Foo : IFoo` backing wrapper plus
  // `foo_*` dispatch exports alongside the unconditional `IFoo` declaration.
  interfaceBackingClasses: List<KSClassDeclaration> = emptyList(),
  // ADR-074 Decision 3: the index of every filtered `expect` declaration, so a top-level
  // `actual fun`/`val` can take its C# static class name from the *expect's* file instead of its
  // own (per-target) file.
  expects: ExpectIndex = ExpectIndex(),
  // ADR-114: supplied by `NugetProcessor` so both halves classify legacy-route parameters with
  // the identical instance. Defaulted to null for the translator-level tests, which build one
  // from the export set below rather than threading a classifier through every fixture.
  forwardClassifier: ForwardBridgeTypeClassifier? = null,
  // ADR-113: the DECLARATION catalog, planned over every exported interface, not just the
  // reachable ones. `callableCatalog` above stays reachability-driven (it drives the exports, the
  // backing classes and the ADR-055 contract); `IFoo` is unconditional under ADR-040, so its member
  // list has to come from a catalog that is unconditional too.
  interfaceDeclarationCatalog: ForwardCallablePlanCatalog = ForwardCallablePlanCatalog(emptyList()),
): CirFile {
  // ADR-147: no split any more. A generic class is an ordinary class with type parameters, and
  // goes through `translateClass` and the ADR-062 plan like every other class.
  val regularClasses: List<KSClassDeclaration> = classes

  val exportedTypes: Set<String> = buildSet {
    classes.forEach { add(it.qualifiedName?.asString() ?: "") }
    enums.forEach { add(it.qualifiedName?.asString() ?: "") }
    interfaces.forEach { add(it.qualifiedName?.asString() ?: "") }
    sealedClasses.forEach { cls ->
      add(cls.qualifiedName?.asString() ?: "")
      cls.getSealedSubclasses().forEach { add(it.qualifiedName?.asString() ?: "") }
    }
    objects.forEach { add(it.qualifiedName?.asString() ?: "") }
    remove("")
  }

  // ADR-114: the processor threads its own instance so both halves classify identically. The
  // fallback keeps the translator-level tests (which never build one) on the same code path.
  val classifier: ForwardBridgeTypeClassifier = forwardClassifier ?: ForwardBridgeTypeClassifier(
    ForwardBridgeTypeContext(
      exportedObjectHandles = exportedTypes,
      // ADR-134: the record structs this same call declares, so the fallback classifier refuses a
      // nested value class on exactly the membership the processor's instance would.
      exportedValueClasses = valueClasses.mapNotNullTo(mutableSetOf()) {
        it.qualifiedName?.asString()
      },
      rootPackage = context.rootPackage,
      rootNamespace = context.rootNamespace,
      exportMarkers = context.exportMarkers,
    ),
  )

  fun namespaceOf(pkg: String): String =
    mapPackageToNamespace(pkg, context.rootPackage, context.rootNamespace)

  // ADR-074 Decision 3: without this, `expect fun platformName()` in `nativeMain/Platform.kt` with
  // actuals in `macosArm64Main/PlatformMacos.kt` and `mingwX64Main/PlatformMingw.kt` produces
  // `public static class PlatformMacos` in one target's `Interop.cs` and `PlatformMingw` in the
  // other's, while `packNuget` packages exactly one target's output and ships every target's
  // binary. Kotlin requires an `expect` and its `actual` to live in the same module, so within one
  // compilation this lookup always hits for a genuine `actual`; the fallback is defensive only.
  fun expectFileNameOrNull(declaration: KSDeclaration): String? =
    expects.fileNameOrNull(declaration)

  // Issue #233: the resolved stem is sanitised here, at the grouping *key*, not at the render
  // site. Both halves of ADR-007's holder (functions and properties) key off the same sanitised
  // name, so a file's members stay in one holder, and `resolveStaticClassName` only ever compares
  // legal identifiers. The expect-derived name (ADR-074 Decision 3) goes through the same call,
  // since an `expect` may live in a dotted file too.
  fun groupByNamespaceAndFile(
    funcs: List<KSFunctionDeclaration>,
  ): Map<Pair<String, String>, List<KSFunctionDeclaration>> =
    funcs.groupBy { func ->
      val namespace: String = namespaceOf(func.packageName.asString())
      val stem: String = expectFileNameOrNull(func)
        ?: func.containingFile?.fileName?.removeSuffix(".kt")
        ?: context.className
      namespace to stem.csharpIdentifier()
    }

  fun groupPropertiesByNamespaceAndFile(
    props: List<KSPropertyDeclaration>,
  ): Map<Pair<String, String>, List<KSPropertyDeclaration>> =
    props.groupBy { prop ->
      val namespace: String = namespaceOf(prop.packageName.asString())
      val stem: String = expectFileNameOrNull(prop)
        ?: prop.containingFile?.fileName?.removeSuffix(".kt")
        ?: context.className
      namespace to stem.csharpIdentifier()
    }

  // ADR-110: a top-level function renders PascalCase, so `fun beam()` in `Beam.kt` wants the member
  // name `Beam` on a static class already called `Beam`, which C# forbids (CS0542). ADR-007 already
  // owns the remedy for a file class whose name is taken, so this reuses it: suffix the class `Kt`.
  // Suspend functions are excluded (their `Async` suffix keeps them clear) and so are extensions
  // (they live on `{Receiver}Extensions`).
  val fileClassFunctions: Map<Pair<String, String>, List<KSFunctionDeclaration>> = buildMap {
    listOf(functions, genericFunctions).forEach { list ->
      groupByNamespaceAndFile(list).forEach { (key, funcs) ->
        put(key, getOrElse(key) { emptyList() } + funcs)
      }
    }
  }

  fun csharpMemberName(function: KSFunctionDeclaration): String =
    function.csharpMemberName()

  fun resolveStaticClassName(fileClassName: String, namespace: String): String {
    val conflictsWithClass: Boolean = classes.any {
      it.simpleName.asString() == fileClassName && namespaceOf(it.packageName.asString()) == namespace
    }

    val conflictsWithSealed: Boolean = sealedClasses.any {
      it.simpleName.asString() == fileClassName && namespaceOf(it.packageName.asString()) == namespace
    }

    // ADR-040: a reachable interface's generated backing class (`Pet`) is exactly as real a
    // static-class-name conflict as an ordinary class/sealed-class — e.g. `Pet.kt`'s top-level
    // `strayPet()` would otherwise collide with the backing wrapper for `interface Pet`, both
    // named "Pet" in the same namespace.
    val conflictsWithInterfaceBackingClass: Boolean = interfaceBackingClasses.any {
      it.simpleName.asString() == fileClassName && namespaceOf(it.packageName.asString()) == namespace
    }

    val named: String =
      if (conflictsWithClass || conflictsWithSealed || conflictsWithInterfaceBackingClass) {
        "${fileClassName}Kt"
      } else {
        fileClassName
      }

    val claimedByFunction: Boolean = fileClassFunctions[namespace to fileClassName]
      ?.any { function -> csharpMemberName(function) == named } == true

    return if (claimedByFunction) "${named}Kt" else named
  }

  // Once per renamed class, rather than once per loop that asks [resolveStaticClassName] for the
  // name (functions, generic functions, suspend functions, properties and consts all ask).
  fileClassFunctions.forEach { (key, funcs) ->
    val (namespace, fileClassName) = key
    val resolved: String = resolveStaticClassName(fileClassName, namespace)
    val claimant: KSFunctionDeclaration = funcs
      .firstOrNull { function -> csharpMemberName(function) == resolved.removeSuffix("Kt") }
      ?: return@forEach
    ForwardDiagnosticSink.emit(
      listOf(
        ForwardDiagnostic(
          kind = ForwardDiagnosticKind.INFO_FILE_CLASS_RENAMED,
          symbol = claimant,
          declaration = resolved,
          reason = "the top-level function '${claimant.simpleName.asString()}' renders the C# " +
              "name '${csharpMemberName(claimant)}', which is also what its file class would be " +
              "called, and C# cannot declare a member named like its enclosing type (CS0542)",
          hint = "call it as $resolved.${csharpMemberName(claimant)}(...); the native export " +
              "name is unchanged (ADR-007, ADR-110)",
          // Nothing is skipped: the function binds, on a renamed holder. The remark channel is
          // for absences, and this note deliberately does NOT feed the file-holder name the
          // post-pass resolves (the two rules are independent).
          owner = null,
        ),
      ),
      logger,
    )
  }

  val namespaces: MutableList<CirNamespace> = mutableListOf()
  val tracker = CollectionHelperTracker()
  // ADR-110 amendment (ROADMAP line 32): every class-shaped route records its rendered names and
  // kept base here; the inherited CS0108 check runs once, after all of them translated.
  val memberRegistry = CsMemberRegistry()

  // ADR-110: top-level functions render PascalCase, so a function can now claim a C# name that a
  // top-level property of the same file class already holds (`val name` + `fun name()`, CS0102).
  // The file-class shape (CS0542) is renamed away above; this one has no rename to fall back on.
  // The file class is `partial` and merged from several loops (functions, generic and suspend
  // functions, properties, consts, same-named extension classes), so the check runs once over the
  // MERGED class before returning, which is also what catches two consts meeting after casing
  // (`MAX_RETRIES` + `maxRetries`). Each loop records the Kotlin declaration behind every name.
  val staticSpellings: MutableMap<Pair<String, String>, KotlinSpellings> = mutableMapOf()
  val staticSymbols: MutableMap<Pair<String, String>, MutableMap<String, KSDeclaration>> =
    mutableMapOf()
  fun recordStatic(
    namespace: String,
    className: String,
    emitted: List<CirMember>,
    declaration: KSDeclaration,
    spelling: KotlinSpelling,
  ) {
    val key: Pair<String, String> = namespace to className
    val spellings: KotlinSpellings = staticSpellings.getOrPut(key) { KotlinSpellings() }
    val symbols: MutableMap<String, KSDeclaration> = staticSymbols.getOrPut(key) { mutableMapOf() }
    emitted.csMemberNames().forEach { member ->
      spellings.record(member.name, spelling)
      // The function is the declaration the pinned ADR-110 message asks the author to rename.
      val existing: KSDeclaration? = symbols[member.name]
      val functionOutranksNonFunction: Boolean =
        spelling.isFunction && existing !is KSFunctionDeclaration
      if (existing == null || functionOutranksNonFunction) {
        symbols[member.name] = declaration
      }
    }
  }
  fun KSDeclaration.spelling(keyword: String): KotlinSpelling =
    KotlinSpelling(keyword, simpleName.asString(), declared = declaredCSharpName())
  fun KSPropertyDeclaration.topLevelSpelling(): KotlinSpelling = when {
    modifiers.contains(Modifier.CONST) -> spelling("const val")
    isMutable -> spelling("var")
    else -> spelling("val")
  }

  groupByNamespaceAndFile(functions).forEach { (key, funcs) ->
    val (namespace, fileClassName) = key
    val finalClassName: String = resolveStaticClassName(fileClassName, namespace)
    val members: List<CirMember> = funcs.flatMap { function ->
      // ADR-095: node identity — the walk stays (this grouping needs the declaration), but the
      // plan of an overload is keyed `..._$n` and is no longer derivable from the name.
      val planned: ForwardCallablePlan? = callableCatalog.planFor(function)
      val emitted: List<CirMember> = if (planned != null) {
        tracker.trackPlan(planned)
        ForwardCirPlanProjection.static(planned, context.libraryName)
      } else {
        // Named specialized adapters only (sealed / generic-declaration returns).
        // ADR-171: its lambda-return and generic-return type arguments may name a value class
        // that has a box/unbox pair.
        translateSpecializedFunction(
          function,
          context.libraryName,
          context,
          tracker,
          exportedTypes + callableCatalog.boxedValueClasses,
          logger,
          classifier,
          callableCatalog,
        )
      }
      recordStatic(
        namespace, finalClassName, emitted, function,
        function.spelling("fun"),
      )
      emitted
    }
    // ADR-095: top-level overloads land on one static class per (namespace, file class).
    emitCsharpSignatureCollisions(
      methods = members.filterIsInstance<CirMethod>(),
      container = "$namespace.$finalClassName",
      symbol = funcs.first(),
      logger = logger,
    )
    namespaces.addDeclaration(namespace, CirStaticClass(finalClassName, members))
  }

  groupByNamespaceAndFile(genericFunctions).forEach { (key, funcs) ->
    val (namespace, fileClassName) = key
    val finalClassName: String = resolveStaticClassName(fileClassName, namespace)
    val members: List<CirMember> =
      funcs.flatMap { function ->
        translateGenericFunction(function, context.libraryName, context, logger).also { emitted ->
          recordStatic(
            namespace, finalClassName, emitted, function,
            function.spelling("fun"),
          )
        }
      }
    namespaces.mergeStaticClass(namespace, finalClassName, members)
  }

  groupByNamespaceAndFile(suspendFunctions).forEach { (key, funcs) ->
    val (namespace, fileClassName) = key
    val finalClassName: String = resolveStaticClassName(fileClassName, namespace)
    val members: List<CirMember> = funcs.flatMap { function ->
      translateSuspendFunction(
        function, context.libraryName, context.symbols, tracker, exportedTypes, logger, classifier,
        callableCatalog, context,
      ).also { emitted ->
        recordStatic(
          namespace, finalClassName, emitted, function,
          function.spelling("fun"),
        )
      }
    }
    namespaces.mergeStaticClass(namespace, finalClassName, members)
  }

  groupPropertiesByNamespaceAndFile(properties).forEach { (key, props) ->
    val (namespace, fileClassName) = key
    val finalClassName: String = resolveStaticClassName(fileClassName, namespace)
    val members: List<CirMember> = props.flatMap { prop ->
      val plan: ForwardPropertyPlan? =
        callableCatalog.propertyFor("${prop.packageName.asString()}.${prop.simpleName.asString()}")
      if (plan != null) {
        tracker.trackProperty(plan)
        ForwardCirPropertyProjection.staticProperty(plan, context.libraryName)
          .also { emitted ->
            recordStatic(namespace, finalClassName, emitted, prop, prop.topLevelSpelling())
          }
      } else {
        emptyList()
      }
    }
    namespaces.mergeStaticClass(namespace, finalClassName, members)
  }

  groupPropertiesByNamespaceAndFile(constProperties).forEach { (key, props) ->
    val (namespace, fileClassName) = key
    val finalClassName: String = resolveStaticClassName(fileClassName, namespace)
    val members: List<CirMember> = props.mapNotNull { prop ->
      translateConstProperty(prop, logger)?.also { emitted ->
        recordStatic(namespace, finalClassName, listOf(emitted), prop, prop.topLevelSpelling())
      }
    }
    namespaces.mergeStaticClass(namespace, finalClassName, members)
  }

  // ADR-133: the owner walk is the sole declarer of a nested type, so a declaration whose parent
  // is a class or object is routed into its owner's `nestedDeclarations` slot instead of the
  // namespace. Every kind list has to be partitioned: one missed partition emits a namespace-level
  // twin, which is CS0426 against every `Outer.Inner` reference (the pre-2026-09-07 flattening) or
  // CS0101 outright (the issue #54/#110 lesson). A companion never reaches these lists (ADR-013
  // folds it into its owner's statics) and neither does a sealed arm (ADR-009 declares it).
  fun KSClassDeclaration.isNestedDeclaration(): Boolean = parentDeclaration is KSClassDeclaration

  fun isOwnedBy(owner: KSClassDeclaration, declaration: KSClassDeclaration): Boolean =
    (declaration.parentDeclaration as? KSClassDeclaration)?.qualifiedName?.asString() ==
        owner.qualifiedName?.asString()

  // ADR-196: a class and the nested declarations it owns, as the C# declarations they become. A
  // non-generic class carries them in its own block. A generic `Tin<T>` cannot (CS7042: no extern
  // anywhere inside a generic type), and Kotlin's own scope for them is the bare `Tin.`, so they go
  // on a non-generic `public static class Tin` beside it, emitted only when there is a child.
  fun withNestedDeclarations(
    translated: CirClass,
    nested: List<CirDeclaration>,
  ): List<CirDeclaration> {
    if (translated.typeParameters.isEmpty()) {
      return listOf(translated.copy(nestedDeclarations = nested))
    }
    // A generic abstract class's backing wrapper is generic too, so it sits on the holder as well.
    if (nested.isEmpty() && translated.backingName == null) return listOf(translated)
    val holder = CirObject(
      name = translated.name,
      libraryName = context.libraryName,
      nativePrefix = translated.nativePrefix,
      methods = emptyList(),
      nestedDeclarations = nested,
      isNestedTypeHolder = true,
    )
    return listOf(holder, translated)
  }

  fun translateNestedOf(owner: KSClassDeclaration): List<CirDeclaration> = buildList {
    regularClasses.filter { isOwnedBy(owner, it) }.forEach { cls ->
      val translated: CirClass = translateClass(
        cls, context.libraryName, tracker, exportedTypes, logger, callableCatalog, context,
        classifier, interfaceDeclarationCatalog, expects, memberRegistry,
      )
      addAll(withNestedDeclarations(translated, translateNestedOf(cls)))
    }
    enums.filter { isOwnedBy(owner, it) }.forEach { enum ->
      add(translateEnum(
        enum, context.libraryName, logger, context.symbols, expects, context, callableCatalog,
        tracker,
      ))
    }
    // ADR-134: a nested `value class` is declared as a nested `readonly record struct`. Its
    // members already export under the whole chain (`nativePrefix()`) and every type position
    // already spelled `Owner.Tag`; only the declaration was missing (CS0426 until now).
    valueClasses.filter { isOwnedBy(owner, it) }.forEach { cls ->
      add(translateValueClass(
        cls, context.libraryName, logger, context, callableCatalog, expects, tracker,
      ))
    }
    interfaces.filter { isOwnedBy(owner, it) }.forEach { iface ->
      add(
        translateInterface(
          iface, interfaceDeclarationCatalog, logger, tracker, expects, exportedTypes, classifier,
          context = context,
        )
          // ADR-134: an interface owner carries children at any depth, exactly as a class does.
          .copy(nestedDeclarations = translateNestedOf(iface)),
      )
      // ADR-040's backing wrapper nests BESIDE its interface (`Owner.Listener : IListener`)
      // rather than at namespace root, which is the caveat ADR-133 closes.
      interfaceBackingClasses
        .filter { it.qualifiedName?.asString() == iface.qualifiedName?.asString() }
        .forEach { backing ->
          add(
            translateInterfaceBackingClass(
              backing, context.libraryName, context.symbols, callableCatalog, tracker, logger,
              classifier = classifier, context = context, expects = expects,
            ),
          )
        }
    }
    objects.filter { isOwnedBy(owner, it) }.forEach { obj ->
      add(
        translateObject(
          obj, context.libraryName, context.symbols, callableCatalog, tracker, logger, expects,
        )
          .copy(nestedDeclarations = translateNestedOf(obj)),
      )
    }
  }

  // ADR-162: guarded per declaration. A generator invariant that a legal class shape reaches used
  // to abort the whole round here with one unlocated `IllegalStateException`, hiding every other
  // offending declaration; now each one is reported against its own source location and the loop
  // keeps going, so the author gets the whole list in one build. The round still fails at the
  // fatal-diagnostic gate, so nothing half-translated ships.
  regularClasses.filter { !it.isNestedDeclaration() }.forEach { cls ->
    val declarations: List<CirDeclaration> = guarded(cls.forwardGuardName(), cls, logger) {
      val translated: CirClass = translateClass(
        cls, context.libraryName, tracker, exportedTypes, logger, callableCatalog, context,
        classifier, interfaceDeclarationCatalog, expects, memberRegistry,
      )
      withNestedDeclarations(translated, translateNestedOf(cls))
    } ?: return@forEach
    declarations.forEach { declaration ->
      namespaces.addDeclaration(namespaceOf(cls.packageName.asString()), declaration)
    }
  }

  // ADR-134: a nested value class is declared by the owner walk above and nowhere else; a
  // namespace-level twin would be CS0101 against it (the issue #54/#110 lesson).
  valueClasses.filter { !it.isNestedDeclaration() }.forEach { cls ->
    val declaration: CirDeclaration = guarded(cls.forwardGuardName(), cls, logger) {
      translateValueClass(
        cls, context.libraryName, logger, context, callableCatalog, expects, tracker,
      )
    } ?: return@forEach
    namespaces.addDeclaration(namespaceOf(cls.packageName.asString()), declaration)
  }

  enums.filter { !it.isNestedDeclaration() }.forEach { enum ->
    val declaration: CirDeclaration = guarded(enum.forwardGuardName(), enum, logger) {
      translateEnum(
        enum, context.libraryName, logger, context.symbols, expects, context, callableCatalog,
        tracker,
      )
    } ?: return@forEach
    namespaces.addDeclaration(namespaceOf(enum.packageName.asString()), declaration)
  }

  interfaces.filter { !it.isNestedDeclaration() }.forEach { iface ->
    val declaration: CirDeclaration = guarded(iface.forwardGuardName(), iface, logger) {
      translateInterface(
        iface, interfaceDeclarationCatalog, logger, tracker, expects, exportedTypes, classifier,
        context = context,
      )
        // ADR-134: the interface block owns its nested declarations (`ICage.Bar`).
        .copy(nestedDeclarations = translateNestedOf(iface))
    } ?: return@forEach
    namespaces.addDeclaration(namespaceOf(iface.packageName.asString()), declaration)
  }

  // ADR-040: name collision, "not currently handled" per the ADR's Breaking-changes section — a
  // Kotlin interface `Pet` whose generated backing class `Pet` clashes with an existing C# type
  // in the same namespace (a class/object/enum/value-class/sealed-class also named `Pet`) must
  // fail fast rather than emit ambiguous code, mirroring the existing
  // ERROR_CSHARP_SIGNATURE_COLLISION style (`CirClassTranslator.kt`'s duplicate-constructor check).
  val existingTypeNamesByNamespace: MutableMap<String, MutableSet<String>> = mutableMapOf()
  // ADR-133: keyed by the ENCLOSING-SCOPE name (`Shape.Circle`, `Owner.Nested`), not the bare
  // simple name. A nested type only collides with something in the same scope, so the bare key
  // also reported a sealed arm `Shape.Circle` as colliding with an unrelated top-level `Circle`.
  fun recordExistingTypeName(declaration: KSClassDeclaration) {
    existingTypeNamesByNamespace
      .getOrPut(namespaceOf(declaration.packageName.asString())) { mutableSetOf() }
      .add(declaration.nestedCsName())
  }
  (regularClasses + valueClasses + enums + objects).forEach { decl ->
    recordExistingTypeName(decl)
  }
  sealedClasses.forEach { sealed ->
    recordExistingTypeName(sealed)
    sealed.getSealedSubclasses().forEach { sub ->
      // ADR-157: an enum arm's C# declaration is `{Enum}Arm`. The enum's own name is already
      // recorded above, by the `enums` walk, and recording it a second time here would report the
      // enum as colliding with itself.
      if (sub.isEnumArm()) {
        existingTypeNamesByNamespace
          .getOrPut(namespaceOf(sub.packageName.asString())) { mutableSetOf() }
          .add(sub.enumArmName())
        return@forEach
      }
      recordExistingTypeName(sub)
    }
  }

  // Two exported top-level types in different Kotlin packages that map to one C# namespace (always
  // the case with `rootPackage` unset) and one C# name are CS0101 in the consumer's build. Keyed by
  // qualified name so one type reached twice (a sealed arm that is also an exported class) is not a
  // collision, and by arity because C# declares `Box` and `Box<T>` side by side.
  val topLevelTypesByCsName:
    MutableMap<Pair<String, String>, MutableMap<String, KSClassDeclaration>> = linkedMapOf()
  fun recordTopLevelType(
    declaration: KSClassDeclaration,
    csName: String,
    arity: Int = declaration.typeParameters.size,
  ) {
    if (declaration.isNestedDeclaration()) return
    val qualifiedName: String = declaration.qualifiedName?.asString() ?: return
    val namespace: String = namespaceOf(declaration.packageName.asString())
    val key: Pair<String, String> = namespace to if (arity == 0) csName else "$csName`$arity"
    topLevelTypesByCsName.getOrPut(key) { sortedMapOf() }.putIfAbsent(qualifiedName, declaration)
  }
  (regularClasses + valueClasses + enums + objects + sealedClasses).forEach { decl ->
    recordTopLevelType(decl, decl.nestedCsName())
  }
  // ADR-196: a generic class with a declared nested type also declares the non-generic holder
  // `Tin`, which is a `Tin` of arity 0 for CS0101 purposes.
  val declaredNested: List<KSClassDeclaration> =
    regularClasses + valueClasses + enums + objects + interfaces
  regularClasses
    .filter { cls -> cls.typeParameters.isNotEmpty() }
    .filter { cls -> declaredNested.any { nested -> isOwnedBy(cls, nested) } }
    .forEach { cls -> recordTopLevelType(cls, cls.nestedCsName(), arity = 0) }
  sealedClasses
    .flatMap { it.getSealedSubclasses().toList() }
    .filter { !it.isEnumArm() }
    .forEach { sub -> recordTopLevelType(sub, sub.nestedCsName()) }
  // ADR-199: a generic sealed type also declares its non-generic holder `Outcome`.
  sealedClasses
    .filter { sealed -> sealed.typeParameters.isNotEmpty() }
    .forEach { sealed -> recordTopLevelType(sealed, sealed.nestedCsName(), arity = 0) }
  interfaces.forEach { iface -> recordTopLevelType(iface, iface.nestedInterfaceCsName()) }
  topLevelTypesByCsName.forEach { (key, declarations) ->
    if (declarations.size < 2) return@forEach
    val (namespace: String, arityKey: String) = key
    val csName: String = arityKey.substringBefore('`')
    val names: String = declarations.keys.joinToString(" and ") { "'$it'" }
    val hint: String = if (context.rootPackage.isEmpty()) {
      "set the NuGet publish rootPackage so each Kotlin package gets its own C# namespace, " +
          "or rename one of the declarations"
    } else {
      "rename one of the declarations, or move it so its package maps to a different C# " +
          "namespace under rootPackage '${context.rootPackage}'"
    }
    ForwardDiagnosticSink.emit(
      listOf(
        ForwardDiagnostic(
          kind = ForwardDiagnosticKind.ERROR_CSHARP_SIGNATURE_COLLISION,
          symbol = declarations.values.last(),
          declaration = csName,
          reason = "$names are both declared in C# as '$csName' in namespace '$namespace', " +
              "which the consumer's build rejects as CS0101",
          hint = hint,
          // ERROR_*: the build fails before anything generated is read.
          owner = null,
        ),
      ),
      logger,
    )
  }

  interfaceBackingClasses.filter { !it.isNestedDeclaration() }.forEach { iface ->
    val namespace: String = namespaceOf(iface.packageName.asString())
    val backingName: String = iface.nestedCsName()
    val collides: Boolean = existingTypeNamesByNamespace[namespace]?.contains(backingName) == true
    if (collides) {
      ForwardDiagnosticSink.emit(
        listOf(
          ForwardDiagnostic(
            kind = ForwardDiagnosticKind.ERROR_CSHARP_SIGNATURE_COLLISION,
            symbol = iface,
            declaration = backingName,
            reason = "the generated interface backing class '$backingName' collides with an " +
                "existing C# type of the same name in namespace '$namespace' (ADR-040)",
            hint = "rename the Kotlin interface, or the colliding declaration, so the generated " +
                "backing class name is unique",
            // ERROR_*: the build fails before anything generated is read.
            owner = null,
          ),
        ),
        logger,
      )
      return@forEach
    }
    namespaces.addDeclaration(
      namespace,
      translateInterfaceBackingClass(
        iface, context.libraryName, context.symbols, callableCatalog, tracker, logger,
        classifier = classifier, context = context, expects = expects,
      ),
    )
  }

  // ADR-199: an intermediate sealed arm is translated by its parent, onto the parent's holder.
  sealedClasses.filterNot { it.isIntermediateGenericSealedArm() }.forEach { sealed ->
    namespaces.addDeclaration(
      namespaceOf(sealed.packageName.asString()),
      translateSealedClass(
        sealed, context, tracker, callableCatalog, classifier, exportedTypes, logger,
        // ADR-134: the base and each arm are owners. The walk is passed in rather than rebuilt
        // there, so a nested declaration under a sealed owner goes through exactly the same
        // translation an ordinary owner's does.
        nestedOf = ::translateNestedOf,
        expects = expects,
        memberRegistry = memberRegistry,
      ),
    )
  }

  objects.filter { !it.isNestedDeclaration() }.forEach { obj ->
    val declaration: CirDeclaration = guarded(obj.forwardGuardName(), obj, logger) {
      translateObject(
        obj, context.libraryName, context.symbols, callableCatalog, tracker, logger, expects,
      )
        .copy(nestedDeclarations = translateNestedOf(obj))
    } ?: return@forEach
    namespaces.addDeclaration(namespaceOf(obj.packageName.asString()), declaration)
  }

  // ADR-126. An exported receiver homes its `{Receiver}Extensions` class on the receiver's own
  // package, which is deterministic already. An unexported receiver (`String`, primitives, any
  // stdlib type) has no such home, so the class belongs to the package that *declares* the
  // extension, the rule every other declaration follows. Keying the group on it, rather than
  // reading `first()` after the fact, is what makes it independent of KSP visit order: before
  // ADR-126 whichever extension KSP saw first dragged every same-receiver extension in the library
  // into its package's namespace.
  fun extensionNamespace(receiver: KSDeclaration, declaring: KSDeclaration): String =
    extensionNamespace(receiver, declaring, exportedTypes, ::namespaceOf)

  // ADR-133 amendment: an extension receiver keys on its whole enclosing chain (`Aviary.Perch`),
  // which is both the class-name stem and -- spelled identically by `ForwardPropertyPlanner` --
  // the middle of an extension property's plan symbol. A top-level receiver keys on its own simple
  // name exactly as before.
  fun KSDeclaration.extensionReceiverKey(): String =
    (this as? KSClassDeclaration)?.nestedCsName() ?: simpleName.asString()

  // ADR-006 amendment: every C# method the `{Receiver}Extensions` partial class gains, per class,
  // with the Kotlin declaration behind it, so an enum member property and an extension of the
  // same C# signature are named as ADR-034's collision instead of reaching the consumer as CS0111.
  val extensionClassMethods: MutableMap<Pair<String, String>, MutableList<SpelledMethod>> =
    mutableMapOf()

  // The function and property loops below MUST key identically, or one package's extension
  // functions and its extension properties on the same receiver land in two different classes.
  val extensionsByReceiver: Map<Pair<String, String>, List<KSFunctionDeclaration>> =
    extensionFunctions.groupBy { func ->
      val receiver: KSDeclaration = func.extensionReceiver!!.resolve().expandAliases().declaration
      extensionNamespace(receiver, func) to receiver.extensionReceiverKey()
    }

  extensionsByReceiver.forEach { (key, funcs) ->
    val (namespace, receiverName) = key
    // ADR-133 amendment: CS1109 forbids nesting an extension class, so a nested receiver's
    // chain travels into the *name* instead, exactly as a nested enum's `AviaryKindExtensions`
    // already does (`CirEnumRenderer`). Unchanged for a top-level receiver, whose key has no dot.
    val className: String = "${receiverName.replace(".", "")}Extensions"

    val members: List<CirMember> = funcs.flatMap { func ->
      // ADR-095: node identity, same reason as the top-level walk above. Extension plan symbols are
      // receiver-agnostic, so two same-name extensions on different receivers in one package share
      // the counter and only the declaration itself tells them apart.
      // ordinary unplanned extensions: no fallthrough
      val planned: ForwardCallablePlan = callableCatalog.planFor(func) ?: return@flatMap emptyList()
      tracker.trackPlan(planned)
      ForwardCirPlanProjection.extension(planned, context.libraryName)
        .also { emitted ->
          val receiverText: String = func.extensionReceiver?.resolve()?.declaration?.simpleName
            ?.asString().orEmpty()
          extensionClassMethods.getOrPut(key) { mutableListOf() } += emitted
            .filterIsInstance<CirMethod>()
            .map { method ->
              SpelledMethod(method, "`fun $receiverText.${func.simpleName.asString()}()`", func)
            }
          recordStatic(
            namespace, className, emitted, func, func.spelling("fun"),
          )
        }
    }

    // A group whose members are all unplanned would otherwise emit an empty
    // `{Receiver}Extensions` class. Extension *properties* below merge into the same class, so a
    // receiver that only keeps a property still gets one.
    // ADR-095: one `{Receiver}Extensions` class holds every extension on that receiver; the
    // receiver is the first parameter of each, which is how C# tells extension overloads apart.
    emitCsharpSignatureCollisions(
      methods = members.filterIsInstance<CirMethod>(),
      container = "$namespace.$className",
      symbol = funcs.first(),
      logger = logger,
    )

    // Merge rather than add: the property loop below feeds the same `(namespace, receiver)` key,
    // and a same-named static class can also already exist in this namespace from the per-file
    // walk (a `StringExtensions.kt` holding a plain top-level function). Both cases render as C#
    // `partial` classes either way; merging just keeps one block per class.
    if (members.isNotEmpty()) namespaces.mergeStaticClass(namespace, className, members)
  }

  val extensionPropsByReceiver: Map<Pair<String, String>, List<KSPropertyDeclaration>> =
    extensionProperties.groupBy { prop ->
      val receiver: KSDeclaration = prop.extensionReceiver!!.resolve().expandAliases().declaration
      extensionNamespace(receiver, prop) to receiver.extensionReceiverKey()
    }

  extensionPropsByReceiver.forEach { (key, props) ->
    val (namespace, receiverName) = key
    // ADR-133 amendment: CS1109 forbids nesting an extension class, so a nested receiver's
    // chain travels into the *name* instead, exactly as a nested enum's `AviaryKindExtensions`
    // already does (`CirEnumRenderer`). Unchanged for a top-level receiver, whose key has no dot.
    val className: String = "${receiverName.replace(".", "")}Extensions"

    val members: List<CirMember> = props.flatMap { prop ->
      val plan: ForwardPropertyPlan? = callableCatalog.extensionPropertyFor(prop)
      if (plan != null) {
        tracker.trackProperty(plan)
        ForwardCirPropertyProjection.extension(plan, context.libraryName)
          // ADR-188: the projection emits a C# 14 extension-block property, not a `GetX`/`SetX`
          // method pair, so nothing here feeds the ADR-095 extension-method signature check. Its
          // one clash, a same-named extension function on the same receiver, is refused by the
          // planner (`SHADOWED_BY_EXTENSION_FUNCTION`).
          .also { emitted ->
            // The property's C# signature, spelled as the `Name(Receiver)` it lowers next to, so
            // the ADR-095 / ADR-006 signature checks still see it: two properties of one C# name
            // on one receiver, or an enum member method `Name(this Mood)` beside one, stay fatal
            // rather than reaching the consumer's compiler.
            val keyword: String = if (prop.isMutable) "var" else "val"
            val receiverText: String = prop.extensionReceiver?.resolve()?.declaration?.simpleName
              ?.asString().orEmpty()
            extensionClassMethods.getOrPut(key) { mutableListOf() } += emitted
              .filterIsInstance<CirExtensionProperty>()
              .map { property ->
                SpelledMethod(
                  CirMethod(
                    name = property.name,
                    returnType = property.type,
                    parameters = listOf(CirParameter("receiver", property.receiverType)),
                    body = "",
                    isStatic = true,
                    isExtension = true,
                  ),
                  "`$keyword $receiverText.${prop.simpleName.asString()}`", prop,
                )
              }
            recordStatic(namespace, className, emitted, prop, prop.topLevelSpelling())
          }
      } else {
        emptyList()
      }
    }

    // ADR-188: two extension properties of one C# name on one receiver type (a `@CSharpName`
    // landing on a sibling's name) are two `public T X` members of one extension block: fatal
    // here, as the two `GetX(this R)` methods they replaced were. Properties only: the function
    // group above already checked the functions, and the planner refused function/property pairs.
    // Keyed on the receiver DECLARATION, not its rendered C# spelling: two same-named enums in two
    // packages may render one spelling here and are still two receivers.
    // ADR-132 amendment (2026-10-04): plus the receiver's nullability. `extension(int)` and
    // `extension(int?)` are two receivers to C#; the only nullable/non-null pair that reaches here
    // is a value-type one, because the planner refuses a reference twin (`NULLABLE_RECEIVER_TWIN`).
    val propertySignatures: List<CirMethod> = extensionClassMethods[key].orEmpty()
      .filter { spelled -> spelled.node is KSPropertyDeclaration }
      .map { spelled ->
        val receiver: KSType? =
          (spelled.node as KSPropertyDeclaration).extensionReceiver?.resolve()
        val declaration: String = receiver?.declaration?.qualifiedName?.asString().orEmpty() +
          if (receiver?.expandAliases()?.isMarkedNullable == true) "?" else ""
        // Not a reference type: the collision check strips a reference receiver's `?`, which is
        // exactly the distinction this key carries.
        spelled.method.copy(
          parameters = listOf(CirParameter("receiver", declaration, isReferenceType = false)),
        )
      }
    if (propertySignatures.isNotEmpty()) {
      emitCsharpSignatureCollisions(
        methods = propertySignatures,
        container = "$namespace.$className",
        symbol = props.first(),
        logger = logger,
      )
    }

    // Same rule as the extension-function groups above: no surviving member, no class. Merging an
    // empty member list into an existing class is a no-op, but creating one from nothing is the
    // empty-class bug.
    if (members.isNotEmpty()) namespaces.mergeStaticClass(namespace, className, members)
  }

  // ADR-006 amendment: an enum's own member properties render into the same `{Enum}Extensions`
  // partial class the extension loops above merge into (the enum carries its half itself, see
  // `CirEnumRenderer`). A member `val grooming` and an extension `fun Coat.grooming()` are both
  // `Grooming(this Coat …)` there: named here, both declarations, as ADR-034's collision.
  enums.forEach { enum ->
    val qualifiedName: String = enum.qualifiedName?.asString() ?: return@forEach
    val key: Pair<String, String> =
      namespaceOf(enum.packageName.asString()) to enum.extensionReceiverKey()
    val receiverName: String = enum.enumReceiverName()
    val propertyMethods: List<SpelledMethod> = callableCatalog.propertyPlans
      .enumMembersOf(qualifiedName)
      .flatMap { plan ->
        val keyword: String = if (plan.setter != null) "var" else "val"
        ForwardCirPropertyProjection.enumMember(plan, context.libraryName, receiverName)
          .filterIsInstance<CirMethod>()
          .map { method -> SpelledMethod(method, "`$keyword ${plan.kotlinName}`", enum) }
      }
    // ADR-006 amendment: the member functions and the companion functions render into the same
    // class, so a member `fun description()` beside `val description`, or a companion
    // `fun describe(mood: Mood)` beside a member `fun describe()`, is the same collision.
    fun ForwardCallablePlan.spelled(where: String, members: List<CirMember>): List<SpelledMethod> =
      members.filterIsInstance<CirMethod>().map { method ->
        SpelledMethod(method, "$where`fun ${invocation.member ?: publicSignature.name}`", enum)
      }
    val functionMethods: List<SpelledMethod> = callableCatalog.enumMethods(qualifiedName)
      .flatMap { plan ->
        plan.spelled(
          "",
          ForwardCirPlanProjection.extension(plan, context.libraryName, receiverName),
        )
      }
    val companionMethods: List<SpelledMethod> = callableCatalog.companionMethods(qualifiedName)
      .flatMap { plan ->
        plan.spelled("companion ", ForwardCirPlanProjection.static(plan, context.libraryName))
      }
    val memberMethods: List<SpelledMethod> = propertyMethods + functionMethods + companionMethods
    // A companion `val`/`var` is a static PROPERTY there, and C# forbids a property sharing its
    // name with any other member of the class, method or property (CS0102), whatever the
    // parameters.
    val staticProperties: List<SpelledProperty> = callableCatalog
      .enumCompanionProperties(qualifiedName)
      .flatMap { plan ->
        val keyword: String = if (plan.setter != null) "var" else "val"
        ForwardCirPropertyProjection.staticProperty(plan, context.libraryName)
          .filterIsInstance<CirProperty>()
          .map { property ->
            SpelledProperty(property.name, "companion `$keyword ${plan.kotlinName}`", enum)
          }
      }
    if (memberMethods.isEmpty() && staticProperties.isEmpty()) return@forEach
    emitEnumExtensionSignatureCollisions(
      enumName = enum.simpleName.asString(),
      container = "${key.first}.${key.second.replace(".", "")}Extensions",
      members = memberMethods,
      extensions = extensionClassMethods[key].orEmpty(),
      logger = logger,
      properties = staticProperties,
    )
  }

  // ADR-160: every per-call callback parameter the plan owns declares its delegate here, off the
  // plan catalog rather than per translated member, so the `Interop.cs` helper block and each call
  // site are minted from the same [BridgeType.Callback] and one delegate covers every member that
  // shares its wire. Deduplicated by name, exactly as the bridge delegates below are.
  val plannedCallbackDelegates: List<CirCallbackDelegate> = callableCatalog.plans
    .flatMap { plan -> plan.publicSignature.parameters }
    .mapNotNull { parameter -> parameter.type as? BridgeType.Callback }
    .map { callback -> callback.forwardCallbackDelegate() }
  tracker.callbackDelegates.addAll(
    plannedCallbackDelegates.filter { delegate ->
      tracker.callbackDelegates.none { existing -> existing.name == delegate.name }
    }.distinctBy { delegate -> delegate.name },
  )

  if (tracker.suspendLambdaArities.isNotEmpty()) tracker.needsAsync = true

  if (tracker.needsFlow) tracker.needsAsync = true

  // ADR-084 stage 1: every interface with a C# backing wrapper (i.e. reachable at a return or
  // parameter position) that plans cleanly gets a bridge factory, so a C# class implementing it can
  // be passed to Kotlin. An interface with an out-of-scope member plans to null and simply gets no
  // factory: `HandleOf` keeps throwing for it rather than emitting a half-supported ABI.
  val bridgePlans: List<CirBridgeInterface> = interfaceBackingClasses.mapNotNull { iface ->
    val plan: ForwardBridgeInterfacePlan =
      ForwardInterfaceBridgePlanner.plan(iface, classifier, context.symbols)
        ?: return@mapNotNull null
    CirBridgeInterface(namespaceOf(iface.packageName.asString()), plan)
  }
  if (bridgePlans.isNotEmpty()) {
    tracker.callbackDelegates.addAll(
      bridgeDelegates(bridgePlans).filter { delegate ->
        tracker.callbackDelegates.none { existing -> existing.name == delegate.name }
      },
    )
  }

  // ADR-129 (2026-09-20 amendment): the core helpers are emitted for every module that emits
  // `Interop.cs` at all. They used to sit behind `needsMarshalHelper`, an allow-list of four
  // declaration kinds (top-level function, class, object, sealed class) out of the thirteen
  // `translate` receives. Every module built only out of the other nine -- an interface plus
  // an extension over it, a top-level property, a generic function, a value class -- still
  // rendered `NugetMarshal.HandleOf` and `NugetErrorNative.BuildException` calls, and then
  // declared neither: CS0103 for the consumer, invisible to the forward ABI contract (which
  // only reports a C# import with no Kotlin export). The gate's original reason (ADR-078: do not
  // import a `nuget_*` name with no Kotlin export behind it) died with ADR-127, which moved those
  // exports into the `nuget-runtime` klib that every consumer links and that `ForwardAbiContract`
  // filters out of the comparison. An enum-only or const-only module now gains `NugetMarshal`
  // and the public `KotlinException` surface too; that is accepted, and is what keeps this a
  // deletion rather than a longer list to forget. The per-feature flags below (`includesList`,
  // `needsAsync`, `bridgePlans`, ...) are untouched.
  val helpers: MutableList<CirDeclaration> = mutableListOf(
    CirMarshalHelper(
      context.libraryName,
      includesMap = tracker.needsMap,
      includesSet = tracker.needsSet,
      includesList = tracker.needsList,
      includesBytes = tracker.needsBytes,
      includesBridge = bridgePlans.isNotEmpty(),
      // ADR-094: the walk happens here, before the helpers are prepended, because `namespaces`
      // already pairs every wrapper declaration with the namespace that names it.
      factories = factoryEntries(namespaces) +
          classifier.closedSealedInstantiations.map { (type, construct) ->
            CirFactoryEntry(type, constructExpression = construct)
          },
      includesFactorySlot = namespaces.any { namespace ->
        namespace.declarations.any { it is CirSealedClass && it.typeParameters.isNotEmpty() }
      },
      boxers = valueClassNames(namespaces),
      enumBoxers = enumBoxers(namespaces),
    ),
  )
  if (bridgePlans.isNotEmpty()) helpers.add(CirBridgeHelper(context.libraryName, bridgePlans))
  if (tracker.needsList) helpers.add(CirListHelper(context.libraryName))
  if (tracker.needsBytes) helpers.add(CirBytesHelper(context.libraryName))
  if (tracker.needsMap) helpers.add(CirMapHelper(context.libraryName))
  if (tracker.needsSet) helpers.add(CirSetHelper(context.libraryName))
  if (tracker.lambdaArities.isNotEmpty()) helpers.add(CirFuncNativeHelper(context.libraryName, tracker.lambdaArities))
  if (tracker.suspendLambdaArities.isNotEmpty()) helpers.add(
    CirSuspendFuncNativeHelper(
      context.libraryName,
      tracker.suspendLambdaArities
    )
  )
  if (tracker.needsAsync) helpers.add(CirAsyncHelper(context.libraryName))
  if (tracker.needsAsync) helpers.add(CirScopeHelper(context.libraryName))
  if (tracker.needsAsync) helpers.add(CirJobHelper(context.libraryName))
  helpers.add(CirErrorHelper(context.libraryName))
  if (tracker.needsFlow) {
    helpers.add(
      CirFlowHelper(
        context.libraryName,
        includesStateFlow = tracker.needsStateFlow,
        includesMutableStateFlow = tracker.needsMutableStateFlow,
      ),
    )
  }
  if (tracker.needsSuspendStateFlow) {
    helpers.add(CirStateFlowHandleHelper(context.libraryName))
  }
  if (tracker.callbackDelegates.isNotEmpty()) {
    helpers.add(CirCallbackDelegateHelper(tracker.callbackDelegates.distinctBy { it.name }))
  }
  if (tracker.needsSubscription) {
    helpers.add(CirSubscriptionHelper(context.libraryName))
  }

  val rootIdx: Int = namespaces.indexOfFirst { it.name == context.rootNamespace }

  if (rootIdx >= 0) {
    val root: CirNamespace = namespaces[rootIdx]
    namespaces[rootIdx] = root.copy(declarations = helpers + root.declarations)
  } else {
    namespaces.add(0, CirNamespace(context.rootNamespace, helpers))
  }

  if (tracker.lambdaArities.isNotEmpty()) {
    val helperNs: String = context.rootNamespace
    val funcHelper = CirFuncHelper(context.libraryName, tracker.lambdaArities, helperNs)
    val funcRootIdx: Int = namespaces.indexOfFirst { it.name == context.rootNamespace }

    if (funcRootIdx >= 0) {
      val root: CirNamespace = namespaces[funcRootIdx]
      namespaces[funcRootIdx] = root.copy(declarations = listOf(funcHelper) + root.declarations)
    } else {
      namespaces.add(CirNamespace(context.rootNamespace, listOf(funcHelper)))
    }
  }

  if (tracker.suspendLambdaArities.isNotEmpty()) {
    val helperNs: String = context.rootNamespace
    val suspendFuncHelper = CirSuspendFuncHelper(context.libraryName, tracker.suspendLambdaArities, helperNs)
    val suspendRootIdx: Int = namespaces.indexOfFirst { it.name == context.rootNamespace }

    if (suspendRootIdx >= 0) {
      val root: CirNamespace = namespaces[suspendRootIdx]
      namespaces[suspendRootIdx] =
        root.copy(declarations = listOf(suspendFuncHelper) + root.declarations)
    } else {
      namespaces.add(CirNamespace(context.rootNamespace, listOf(suspendFuncHelper)))
    }
  }

  // ADR-129: `nuget_runtime_version` must be importable by every library, including a scalar-only
  // one that marshals nothing, so that "which runtime is in this binary" has an answer for every
  // consumer rather than only for the ones that happen to pass a string. This used to be the one
  // helper emitted outside the `needsMarshalHelper` gate; that gate is gone (ADR-129's 2026-09-20
  // amendment), so the core helpers above are unconditional too and this block stays separate only
  // because it prepends last (i.e. `NugetRuntime` renders first in the root namespace).
  val runtimeHelper = CirRuntimeHelper(context.libraryName)
  val runtimeRootIdx: Int = namespaces.indexOfFirst { it.name == context.rootNamespace }
  if (runtimeRootIdx >= 0) {
    val root: CirNamespace = namespaces[runtimeRootIdx]
    namespaces[runtimeRootIdx] = root.copy(declarations = listOf(runtimeHelper) + root.declarations)
  } else {
    namespaces.add(0, CirNamespace(context.rootNamespace, listOf(runtimeHelper)))
  }

  // ADR-159: `System.Threading` is unconditional. Every class's `Dispose()` calls
  // `Interlocked.Exchange(ref _handle, ...)`, so a coroutine-free module -- one ordinary class,
  // nothing async -- did not compile under the ADR-138 gate's csproj, which has no implicit
  // usings: `error CS0103: The name 'Interlocked' does not exist in the current context`. It
  // never surfaced because `test-library` always has async members and every consumer project
  // here enables implicit usings.
  val usings: MutableList<String> =
    mutableListOf("System", "System.Runtime.InteropServices", "System.Threading")
  if (tracker.needsList || tracker.needsMap || tracker.needsSet) {
    usings.add("System.Collections.Generic")
  }
  if (tracker.needsAsync) {
    usings.add("System.Runtime.CompilerServices")
    usings.add("System.Threading.Tasks")
  }
  if (tracker.needsFlow) {
    usings.add("System.Threading.Channels")
    if ("System.Collections.Generic" !in usings) usings.add("System.Collections.Generic")
  }

  // ADR-110 (and its 2026-09-26 amendment, ROADMAP line 32): the merged-file-class check. A
  // top-level function beside a top-level property of its C# name keeps ADR-110's original
  // wording (the pinned "rename the Kotlin function" hint); two values meeting after casing get
  // the generic one.
  namespaces.forEach { namespace ->
    namespace.declarations.filterIsInstance<CirStaticClass>().forEach { staticClass ->
      val key: Pair<String, String> = namespace.name to staticClass.name
      val symbols: Map<String, KSDeclaration> = staticSymbols[key].orEmpty()
      emitMemberNameCollisions(
        container = staticClass.name,
        ownerPhrase = "file class ${staticClass.name}",
        symbol = null,
        members = staticClass.members.csMemberNames(),
        spellings = staticSpellings[key] ?: KotlinSpellings(),
        logger = logger,
        symbolFor = { name -> symbols[name] },
        reason = { collision ->
          if (collision.methods.isEmpty()) {
            "file class ${staticClass.name} declares ${collision.declarations()}, which " +
                "${collision.quantifier} render the C# name '${collision.name}', and C# cannot " +
                "declare two members with one name (CS0102)"
          } else {
            "the top-level property '${collision.name}' on the same file class already claims " +
                "that C# name, and C# cannot declare a property and a method with one name (CS0102)"
          }
        },
        // Top-level declarations are always this module's own: the closure admits only types.
        hint = { collision ->
          val function: String? = collision.methods.firstOrNull()?.name
          if (function == null) {
            csharpNameRemedy(
              rename = "rename one of them",
              spellings = collision.spellings,
              target = "one",
              detail = "a top-level property and a `const val` both render PascalCase in C# " +
                  "(ADR-110)",
            )
          } else {
            csharpNameRemedy(
              rename = "rename the Kotlin function '$function'",
              spellings = collision.spellings,
              detail = "a top-level function renders PascalCase in C# (ADR-110)",
            )
          }
        },
      )
    }
  }

  // ADR-110 amendment (ROADMAP line 32): the inherited CS0108 half, once every class, sealed base
  // and arm has registered, since a base can translate after its subclass.
  memberRegistry.emitInheritedCollisions(logger)

  // ADR-064 amendment (issue #249): the husk sweep moved OUT of here, to the processor, so it runs
  // after `withSkipRemarks` and can spare a holder that carries a remark. Translation returns what
  // the declarations produced, husks included.
  return CirFile(usings = usings, namespaces = namespaces, rootNamespace = context.rootNamespace)
}

/**
 * ADR-007 names a static class after its Kotlin file, and every contribution loop above merges into
 * that one class by name. When every declaration in a file is skipped, the loops still create the
 * class and it renders as `public static partial class HuskOnly { }`: the residue of a skip, not an
 * API. A namespace whose only occupant was such a class then renders as an empty `namespace X { }`
 * block.
 *
 * The sweep runs once, here, after every loop has merged and after the suspend/lambda helpers have
 * been folded into the root namespace. It cannot be a per-loop guard: a file with a skipped sync
 * function and a surviving `suspend fun` contributes an empty member list in one loop and the
 * survivor in another, and the class has to stay. Only the fully merged set can say "empty".
 *
 * [CirStaticClass] carries nothing but its members (helpers such as `CirMarshalHelper` and
 * `CirFuncHelper` are their own declaration types), so an empty member list means an empty class
 * with no content to lose.
 *
 * ADR-064 amendment (issue #249) narrows that last sentence and moves the call site: a holder with
 * [CirStaticClass.remarks] DOES have content -- the reason it is empty -- and issue #249's headline
 * case (a file whose every top-level declaration was dropped) is exactly the shape that needs to
 * say so. The sweep therefore runs in `NugetProcessor`, after `withSkipRemarks`, and spares a
 * holder that carries a remark. A file with nothing declared and nothing dropped is unchanged: no
 * members, no remarks, no holder, and no namespace if it held nothing else.
 */
internal fun CirFile.withoutEmptyStaticClasses(): CirFile =
  copy(namespaces = namespaces.withoutEmptyStaticClasses())

/**
 * ADR-126: the C# namespace an extension's `{Receiver}Extensions` class lands in. An exported
 * receiver ([exportedTypes] holds its qualified name) homes it on the receiver's own package; an
 * unexported one (`String`, primitives, any stdlib type) on the package [declaring] the extension.
 * The one rule both the translator's grouping and ADR-188's clash refusal in
 * `ForwardPropertyPlanner` read, so the planner refuses exactly the pairs that share a class.
 */
internal fun extensionNamespace(
  receiver: KSDeclaration,
  declaring: KSDeclaration,
  exportedTypes: Set<String>,
  namespaceOf: (String) -> String,
): String =
  if ((receiver.qualifiedName?.asString() ?: "") in exportedTypes) {
    namespaceOf(receiver.packageName.asString())
  } else {
    namespaceOf(declaring.packageName.asString())
  }

private fun List<CirNamespace>.withoutEmptyStaticClasses(): List<CirNamespace> = this
  .map { namespace ->
    namespace.copy(
      declarations = namespace.declarations
        .filterNot { it is CirStaticClass && it.members.isEmpty() && it.remarks.isEmpty() },
    )
  }
  .filter { it.declarations.isNotEmpty() }

/**
 * ADR-094: one `NugetMarshal.Factories` line per wrapper that an erased generic path could be asked
 * to materialise. A plain class only qualifies when it actually carries the
 * `internal X(IntPtr, out NugetHandleTag)` constructor the lambda calls, and an abstract one is
 * not constructible at all (neither was it under `Activator`). Sealed subclasses register under
 * their nested `Base.Sub` name.
 *
 * Issue #40: the sealed BASE registers too, via [CirFactoryEntry.viaFromHandle]. It is not
 * constructible, but it is materialisable -- its generated `FromHandle(IntPtr)` reads the Kotlin
 * discriminator and news up the right subclass -- and it is the type an erased `StateFlow<Sealed>`
 * or `Flow<Sealed>` instantiates `T` as, so without it those two paths throw.
 *
 * ADR-171: a value class with a box/unbox pair registers too, via [CirFactoryEntry.viaNugetUnbox].
 * A generic slot holds a boxed Kotlin value class, so `Box<ChartId>.Value` hands back a handle to
 * one; nested value classes register under their `Outer.Inner` name ([valueClassNames]).
 *
 * ADR-173: an exported interface with a backing wrapper registers under `typeof(IFoo)`,
 * constructing the backing class ([CirFactoryEntry.constructTypeName]). `Materialize<T>` probes the
 * C# token BEFORE this lookup for an interface key, so a C#-implemented `IFoo` is never re-wrapped.
 *
 * ADR-094 amendment: an enum registers too, via [CirFactoryEntry.viaEnumOrdinal]. An enum element
 * at an erased position (`Flow<E>`, `StateFlow<E?>`, `Box<E>.Value`) is a handle to the Kotlin
 * enum object, so the entry reads its ordinal back; nested enums ride the same walk.
 *
 * Objects, interfaces without a backing wrapper and open generic wrappers register nothing:
 * none of them is a closed type reachable from a handle.
 */
private fun factoryEntries(namespaces: List<CirNamespace>): List<CirFactoryEntry> {
  // ADR-176: nested declarations register too, spelled through their enclosing declarations (the
  // name a consumer's `typeof(...)` produces, as [valueClassNames] does). Without it a nested
  // class read per element (`List<Aviary.Perch>`, `FromHandle<Aviary.Perch>`) or a nested
  // interface's backing wrapper (`List<Aviary.Keeper>`, `Materialize<Aviary.IKeeper>`) found no
  // `Factories` key and threw `NotSupportedException` at the first element. A sealed arm's scope
  // follows its `isNested` placement.
  fun CirDeclaration.own(path: String): List<CirFactoryEntry> = when (this) {
    // ADR-147: an open generic wrapper registers nothing -- `typeof(Crate<>)` is not the
    // closed type an erased path asks for, and the entry would never match.
    is CirClass ->
      // An abstract class with a backing wrapper registers under its own name and constructs the
      // wrapper; without one it is still not materialisable. A generic one is an open type, like
      // any generic wrapper below.
      if (isAbstract && backingName != null && typeParameters.isEmpty()) {
        listOf(CirFactoryEntry("$path.$name", constructTypeName = "$path.$name.$backingName"))
      } else if (hasInternalHandleConstructor && !isAbstract && typeParameters.isEmpty()) {
        listOf(CirFactoryEntry("$path.$name")) +
            // ADR-173: an ADR-040 backing wrapper also registers under its interface, so an
            // erased read at `T = IPet` (a Kotlin-backed pet the token probe missed) constructs
            // the backing class. ADR-176: the backing wrapper of a nested interface is declared
            // beside it, so both names share the enclosing path.
            listOfNotNull(
              backsInterface?.let { iface ->
                CirFactoryEntry("$path.$iface", constructTypeName = "$path.$name")
              },
            )
      } else {
        emptyList()
      }

    // ADR-199: a generic base and a generic arm are open types; only a closed arm registers here,
    // and every closed instantiation a position names registers from the classifier.
    is CirSealedClass -> if (typeParameters.isNotEmpty()) {
      (subclasses.filter { it.typeParameters.isEmpty() && !it.isIntermediate }
        .map { arm ->
          val name: String = if (arm.isNested) "$name.${arm.name}" else arm.name
          CirFactoryEntry("$path.$name")
        })
    } else {
      listOf(CirFactoryEntry("$path.$name", viaFromHandle = true)) +
          subclasses.map { subclass ->
            // Issue #54: a sibling subclass is declared beside its base, so its wrapper name is
            // `Namespace.Label`, not `Namespace.Shape.Label` -- the key has to be the name a
            // consumer's `typeof(...)` produces or the erased generic path misses it.
            val name: String = if (subclass.isNested) "$name.${subclass.name}" else subclass.name
            // An abstract arm registers under its own name and constructs its backing wrapper.
            val backing: String? = subclass.backingName
            CirFactoryEntry(
              "$path.$name",
              constructTypeName = if (backing != null) "$path.$name.$backing" else "$path.$name",
            )
          }
    }

    is CirEnum -> listOf(CirFactoryEntry("$path.$name", viaEnumOrdinal = true))

    // ADR-204: a sealed interface over its declared arms reconstructs through its own `FromHandle`.
    is CirInterface -> listOfNotNull(
      discriminator?.let { CirFactoryEntry("$path.$name", viaFromHandle = true) },
    )

    else -> emptyList()
  }

  fun CirDeclaration.walk(path: String): List<CirFactoryEntry> = own(path) + when (this) {
    is CirClass -> nestedDeclarations.flatMap { it.walk("$path.$name") }
    is CirInterface -> nestedDeclarations.flatMap { it.walk("$path.$name") }
    is CirObject -> nestedDeclarations.flatMap { it.walk("$path.$name") }
    is CirSealedClass -> nestedDeclarations.flatMap { it.walk("$path.$name") } +
        subclasses.flatMap { arm ->
          val armPath: String = if (arm.isNested) "$path.$name.${arm.name}" else "$path.${arm.name}"
          arm.nestedDeclarations.flatMap { it.walk(armPath) }
        } +
        // ADR-199: an intermediate arm's own hierarchy sits on this holder.
        intermediates.flatMap { it.walk("$path.$name") }

    else -> emptyList()
  }

  return namespaces
    .flatMap { namespace -> namespace.declarations.flatMap { it.walk(namespace.name) } }
    .plus(valueClassNames(namespaces).map { name -> CirFactoryEntry(name, viaNugetUnbox = true) })
    // A duplicate key is an ArgumentException at type-initialization time, i.e. the first marshal
    // call of the consumer's process, so collapse rather than trust the walk to be unique.
    .distinctBy { it.qualifiedTypeName }
}

/**
 * ADR-171: the `global::`-free qualified C# name of every value class with a box/unbox pair, root
 * or nested (ADR-134), the key both `NugetMarshal.Boxers` and `Factories` register it under. A
 * nested one is spelled through its enclosing declarations, the name a consumer's `typeof(...)`
 * produces; a sealed arm's scope follows its `isNested` placement, as in [factoryEntries].
 */
private fun valueClassNames(namespaces: List<CirNamespace>): List<String> {
  fun CirDeclaration.walk(path: String): List<String> = when (this) {
    is CirValueClass -> if (boxing != null) listOf("$path.$name") else emptyList()
    is CirClass -> nestedDeclarations.flatMap { it.walk("$path.$name") }
    is CirInterface -> nestedDeclarations.flatMap { it.walk("$path.$name") }
    is CirObject -> nestedDeclarations.flatMap { it.walk("$path.$name") }
    is CirSealedClass -> nestedDeclarations.flatMap { it.walk("$path.$name") } +
        subclasses.flatMap { arm ->
          val armPath: String = if (arm.isNested) "$path.$name.${arm.name}" else "$path.${arm.name}"
          arm.nestedDeclarations.flatMap { it.walk(armPath) }
        }

    else -> emptyList()
  }
  return namespaces
    .flatMap { namespace -> namespace.declarations.flatMap { it.walk(namespace.name) } }
    .distinct()
}

/**
 * ADR-094 (write side): every enum with a planned box, root or nested, keyed on the name a
 * consumer's `typeof(...)` produces, the same walk as [valueClassNames]. Its `Factories` twin is
 * the enum's `viaEnumOrdinal` entry in [factoryEntries].
 */
private fun enumBoxers(namespaces: List<CirNamespace>): List<CirEnumBoxer> {
  fun CirDeclaration.walk(path: String): List<CirEnumBoxer> = when (this) {
    is CirEnum -> listOfNotNull(boxImport?.let { import -> CirEnumBoxer("$path.$name", import) })
    is CirClass -> nestedDeclarations.flatMap { it.walk("$path.$name") }
    is CirInterface -> nestedDeclarations.flatMap { it.walk("$path.$name") }
    is CirObject -> nestedDeclarations.flatMap { it.walk("$path.$name") }
    is CirSealedClass -> nestedDeclarations.flatMap { it.walk("$path.$name") } +
        subclasses.flatMap { arm ->
          val armPath: String = if (arm.isNested) "$path.$name.${arm.name}" else "$path.${arm.name}"
          arm.nestedDeclarations.flatMap { it.walk(armPath) }
        }

    else -> emptyList()
  }
  return namespaces
    .flatMap { namespace -> namespace.declarations.flatMap { it.walk(namespace.name) } }
    .distinctBy { boxer -> boxer.qualifiedTypeName }
}

private fun MutableList<CirNamespace>.addDeclaration(namespace: String, declaration: CirDeclaration) {
  val existing = find { it.name == namespace }
  if (existing != null) {
    val index: Int = indexOf(existing)
    this[index] = existing.copy(declarations = existing.declarations + declaration)
  } else {
    add(CirNamespace(namespace, listOf(declaration)))
  }
}

private fun MutableList<CirNamespace>.mergeStaticClass(namespace: String, className: String, members: List<CirMember>) {
  val existing = find { it.name == namespace }

  if (existing == null) {
    add(CirNamespace(namespace, listOf(CirStaticClass(className, members))))
    return
  }

  val index: Int = indexOf(existing)
  val existingClass = existing.declarations.find { it is CirStaticClass && it.name == className } as? CirStaticClass

  if (existingClass != null) {
    val updatedDecls = existing.declarations.map { decl ->
      if (decl is CirStaticClass && decl.name == className) decl.copy(members = decl.members + members)
      else decl
    }
    this[index] = existing.copy(declarations = updatedDecls)
  } else {
    this[index] = existing.copy(declarations = existing.declarations + CirStaticClass(className, members))
  }
}

internal fun translateExtensionFunction(
  func: KSFunctionDeclaration,
  receiverName: String,
  receiverQualified: String,
  libraryName: String,
  exportedTypes: Set<String>,
  tracker: CollectionHelperTracker,
): List<CirMember> {
  val funcName: String = func.simpleName.asString()
  val csName: String = func.declaredCSharpName()
    ?: toCName(funcName).replaceFirstChar { it.uppercase() }
  val receiverPrefix: String = receiverName.lowercase()
  val cname: String = "${receiverPrefix}_${toCName(funcName)}"

  val returnType = func.returnType?.resolve()?.expandAliases()
  val kotlinReturnType: String = returnType?.declaration?.simpleName?.asString() ?: "Unit"
  val kotlinReturnQualified: String? = returnType?.declaration?.qualifiedName?.asString()
  val isNullableReturn: Boolean = returnType?.isMarkedNullable == true

  val isExportedReceiver: Boolean = receiverQualified in exportedTypes

  val nativeReceiverType: String = if (isExportedReceiver) "IntPtr" else mapParamType(receiverName)
  val receiverParamName: String = if (isExportedReceiver) "handle" else "receiver"

  val extraParams: List<CirParameter> = func.parameters.map { param ->
    val kotlinType: String = param.type.resolve().expandAliases().declaration.simpleName.asString()
    CirParameter(param.name?.asString() ?: "_", mapParamType(kotlinType))
  }

  val csReceiverType: String = if (isExportedReceiver) receiverName else mapParamType(receiverName)
  val csReceiverParamName: String = if (isExportedReceiver) receiverName.lowercase() else "receiver"

  val wrapperParams: List<CirParameter> = listOf(
    CirParameter(csReceiverParamName, csReceiverType, nativeReceiverType),
  ) + extraParams

  val nativeCallReceiver: String = if (isExportedReceiver) "${csReceiverParamName}._handle" else "receiver"
  val nativeCallArgs: String = (listOf(nativeCallReceiver) + extraParams.map { it.name }).joinToString(", ")

  // ADR-061: the same return-marshalling cascade as the class-method position (mirrored 1:1;
  // see ClassExports.kt's `methods.forEach` loop / CirClassTranslator's `translateClass` "methods"
  // block for the shared design rationale). Enum returns are left exactly as before.
  val isEnumReturn: Boolean = (returnType?.declaration as? KSClassDeclaration)
    ?.classKind == ClassKind.ENUM_CLASS
  val isListReturn: Boolean = !isEnumReturn &&
      (kotlinReturnQualified == "kotlin.collections.List" ||
          kotlinReturnQualified == "kotlin.collections.MutableList")
  val isMutableListReturn: Boolean = kotlinReturnQualified == "kotlin.collections.MutableList"
  val listElementType: String? = if (isListReturn) {
    val elementType = returnType?.arguments?.firstOrNull()?.type?.resolve()
    val elementTypeName: String = elementType?.declaration?.simpleName?.asString() ?: "Any"
    KOTLIN_TO_CSHARP_PARAM[elementTypeName] ?: elementTypeName
  } else null
  if (isListReturn) tracker.needsList = true

  // "Char" is deliberately treated as primitive-ish here — see CirClassTranslator.kt's identical
  // exclusion on the mirrored class-method cascade.
  val isPrimitiveReturn: Boolean =
    kotlinReturnType in KOTLIN_TO_CSHARP_RETURN || kotlinReturnType == "Char"
  val isObjectReturn: Boolean = !isEnumReturn && !isPrimitiveReturn && !isListReturn
  val isNullableStringReturn: Boolean =
    isPrimitiveReturn && isNullableReturn && kotlinReturnType == "String"
  val isNullablePrimitiveReturn: Boolean =
    isPrimitiveReturn && isNullableReturn && kotlinReturnType != "String"

  val nativeReturnType: String
  val nativeExtraParams: List<CirParameter>
  val csReturnType: String
  val body: String

  when {
    kotlinReturnType == "Unit" -> {
      nativeReturnType = "void"
      nativeExtraParams = emptyList()
      csReturnType = "void"
      body = checkedExtensionBody(null, "", csName, nativeCallArgs)
    }

    isListReturn -> {
      nativeReturnType = "IntPtr"
      nativeExtraParams = emptyList()
      csReturnType =
        if (isMutableListReturn) "IList<$listElementType>" else "IReadOnlyList<$listElementType>"
      body = buildString {
        appendLine()
        appendLine("            IntPtr listHandle = Native_$csName(${syncErrorArguments(nativeCallArgs)});")
        appendLine("            if (error != IntPtr.Zero)")
        appendLine("            {")
        appendLine("                throw NugetErrorNative.BuildException(error);")
        appendLine("            }")
        appendLine("            int count = NugetListNative.Count(listHandle);")
        appendLine("            var result = new List<$listElementType>(count);")
        appendLine("            for (int i = 0; i < count; i++)")
        appendLine("            {")
        appendLine("                result.Add(NugetMarshal.FromHandle<$listElementType>(NugetListNative.Get(listHandle, i)));")
        appendLine("            }")
        appendLine("            NugetListNative.Dispose(listHandle);")
        append(if (isMutableListReturn) "            return result;" else "            return result.AsReadOnly();")
      }
    }

    isObjectReturn && isNullableReturn -> {
      nativeReturnType = "IntPtr"
      nativeExtraParams = emptyList()
      csReturnType = "$kotlinReturnType?"
      body = buildString {
        appendLine()
        appendLine("            IntPtr nativeResult = Native_$csName(${syncErrorArguments(nativeCallArgs)});")
        appendLine("            if (error != IntPtr.Zero)")
        appendLine("            {")
        appendLine("                throw NugetErrorNative.BuildException(error);")
        appendLine("            }")
        append(
          "            return nativeResult == IntPtr.Zero ? null : " +
              "new $kotlinReturnType(nativeResult, out _);"
        )
      }
    }

    isObjectReturn -> {
      nativeReturnType = "IntPtr"
      nativeExtraParams = emptyList()
      csReturnType = kotlinReturnType
      body = buildString {
        appendLine()
        appendLine("            IntPtr nativeResult = Native_$csName(${syncErrorArguments(nativeCallArgs)});")
        appendLine("            if (error != IntPtr.Zero)")
        appendLine("            {")
        appendLine("                throw NugetErrorNative.BuildException(error);")
        appendLine("            }")
        append("            return new $kotlinReturnType(nativeResult, out _);")
      }
    }

    isNullableStringReturn -> {
      nativeReturnType = "IntPtr"
      nativeExtraParams = emptyList()
      csReturnType = "string?"
      body = buildString {
        appendLine()
        appendLine("            IntPtr nativeResult = Native_$csName(${syncErrorArguments(nativeCallArgs)});")
        appendLine("            if (error != IntPtr.Zero)")
        appendLine("            {")
        appendLine("                throw NugetErrorNative.BuildException(error);")
        appendLine("            }")
        append("            return Marshal.PtrToStringUTF8(nativeResult);")
      }
    }

    isNullablePrimitiveReturn -> {
      val csValueType: String = mapParamType(kotlinReturnType)
      nativeReturnType = "bool"
      nativeExtraParams = listOf(CirParameter("value", csValueType, "out $csValueType"))
      csReturnType = "$csValueType?"
      body = buildString {
        appendLine()
        appendLine("            bool hasValue = Native_$csName(${syncErrorArguments("$nativeCallArgs, out $csValueType value")});")
        appendLine("            if (error != IntPtr.Zero)")
        appendLine("            {")
        appendLine("                throw NugetErrorNative.BuildException(error);")
        appendLine("            }")
        append("            return hasValue ? value : null;")
      }
    }

    kotlinReturnType == "String" -> {
      nativeReturnType = "IntPtr"
      nativeExtraParams = emptyList()
      csReturnType = "string"
      body =
        checkedExtensionBody("IntPtr", "Marshal.PtrToStringUTF8(result)!", csName, nativeCallArgs)
    }

    else -> {
      nativeReturnType = mapReturnType(kotlinReturnType)
      nativeExtraParams = emptyList()
      csReturnType = KOTLIN_TO_CSHARP_PARAM[kotlinReturnType] ?: kotlinReturnType
      body = checkedExtensionBody(csReturnType, "result", csName, nativeCallArgs)
    }
  }

  val allNativeParams: List<CirParameter> = listOf(
    CirParameter(receiverParamName, nativeReceiverType),
  ) + extraParams + nativeExtraParams

  val dllImport = CirDllImport(
    libraryName = libraryName,
    entryPoint = cname,
    returnType = nativeReturnType,
    name = "Native_$csName",
    parameters = allNativeParams,
    visibility = CirVisibility.PRIVATE,
    hasSyncErrorOut = true,
  )

  val wrapper = CirMethod(
    name = csName,
    returnType = csReturnType,
    nativeReturnType = nativeReturnType,
    nativeName = "Native_$csName",
    parameters = wrapperParams,
    body = body,
    isStatic = true,
    isExtension = true,
  )

  return listOf(dllImport, wrapper)
}

private fun checkedExtensionBody(
  resultType: String?,
  returnValue: String,
  csName: String,
  arguments: String,
): String = buildString {
  appendLine()
  val call: String = "Native_$csName(${syncErrorArguments(arguments)})"
  if (resultType == null) appendLine("            $call;")
  else appendLine("            $resultType result = $call;")
  appendLine("            if (error != IntPtr.Zero)")
  appendLine("            {")
  appendLine("                throw NugetErrorNative.BuildException(error);")
  appendLine("            }")
  if (resultType != null) append("            return $returnValue;")
}

internal fun translateProperty(
  prop: KSPropertyDeclaration,
  libraryName: String,
): List<CirMember> {
  // Extern names are spelled from the ABI segment, so a `@CSharpName`d backticked name compiles.
  val propName: String = prop.simpleName.asString().asCSymbol()
  val cname: String = io.github.xxfast.kotlin.native.nuget.processor.toCName(propName)
  val propTypeResolved: KSType = prop.type.resolve().expandAliases()
  val propType: String = propTypeResolved.declaration.simpleName.asString()
  val isNullable: Boolean = propTypeResolved.isMarkedNullable
  val isMutable: Boolean = prop.isMutable
  val csPropName: String = prop.csharpMemberName()

  if (propType !in KOTLIN_TO_CSHARP_RETURN) return emptyList()

  val isNullablePrimitive: Boolean = isNullable && propType != "String"

  val members: MutableList<CirMember> = mutableListOf()

  if (isNullablePrimitive) {
    val csValueType: String = mapParamType(propType)

    members.add(
      CirDllImport(
        libraryName = libraryName,
        entryPoint = "get_$cname",
        returnType = "bool",
        name = "Native_Get_$propName",
        parameters = emptyList(),
        visibility = CirVisibility.PRIVATE,
        hasSyncErrorOut = true,
      )
    )

    members.add(
      CirDllImport(
        libraryName = libraryName,
        entryPoint = "get_${cname}_value",
        returnType = csValueType,
        name = "Native_Get_${propName}_value",
        parameters = emptyList(),
        visibility = CirVisibility.PRIVATE,
        hasSyncErrorOut = true,
      )
    )

    if (isMutable) {
      members.add(
        CirDllImport(
          libraryName = libraryName,
          entryPoint = "set_$cname",
          returnType = "void",
          name = "Native_Set_$propName",
          parameters = listOf(CirParameter("value", csValueType)),
          visibility = CirVisibility.PRIVATE,
          hasSyncErrorOut = true,
        )
      )

      members.add(
        CirDllImport(
          libraryName = libraryName,
          entryPoint = "set_${cname}_null",
          returnType = "void",
          name = "Native_Set_${propName}_null",
          parameters = emptyList(),
          visibility = CirVisibility.PRIVATE,
          hasSyncErrorOut = true,
        )
      )
    }

    val getter: String = buildString {
      appendLine()
      appendLine("                bool hasValue = Native_Get_$propName(out IntPtr error);")
      appendLine("                if (error != IntPtr.Zero)")
      appendLine("                {")
      appendLine("                    throw NugetErrorNative.BuildException(error);")
      appendLine("                }")
      appendLine("                if (!hasValue) return null;")
      appendLine("                $csValueType value = Native_Get_${propName}_value(out IntPtr error2);")
      appendLine("                if (error2 != IntPtr.Zero)")
      appendLine("                {")
      appendLine("                    throw NugetErrorNative.BuildException(error2);")
      appendLine("                }")
      append("                return value;")
    }

    val setter: String? = if (isMutable) buildString {
      appendLine()
      appendLine("                if (value.HasValue)")
      appendLine("                {")
      appendLine("                    Native_Set_$propName(value.Value, out IntPtr error);")
      appendLine("                    if (error != IntPtr.Zero)")
      appendLine("                    {")
      appendLine("                        throw NugetErrorNative.BuildException(error);")
      appendLine("                    }")
      appendLine("                }")
      appendLine("                else")
      appendLine("                {")
      appendLine("                    Native_Set_${propName}_null(out IntPtr error);")
      appendLine("                    if (error != IntPtr.Zero)")
      appendLine("                    {")
      appendLine("                        throw NugetErrorNative.BuildException(error);")
      appendLine("                    }")
      append("                }")
    } else null

    members.add(
      CirProperty(
        name = csPropName,
        type = "$csValueType?",
        nativeReturnType = csValueType,
        nativeName = propName,
        getter = getter,
        setter = setter,
        isStatic = true,
        hasSyncErrorOut = true,
      )
    )

    return members
  }

  val csNativeReturnType: String = mapReturnType(propType)

  members.add(
    CirDllImport(
      libraryName = libraryName,
      entryPoint = "get_$cname",
      returnType = csNativeReturnType,
      name = "Native_Get_$propName",
      parameters = emptyList(),
      visibility = CirVisibility.PRIVATE,
      hasSyncErrorOut = true,
    )
  )

  val csType: String
  val getter: String
  val setter: String?

  if (propType == "String" && isNullable) {
    csType = "string?"
    getter = buildString {
      appendLine()
      appendLine("                IntPtr nativeResult = Native_Get_$propName(out IntPtr error);")
      appendLine("                if (error != IntPtr.Zero)")
      appendLine("                {")
      appendLine("                    throw NugetErrorNative.BuildException(error);")
      appendLine("                }")
      append("                return Marshal.PtrToStringUTF8(nativeResult);")
    }
    setter = if (isMutable) buildString {
      appendLine()
      appendLine("                Native_Set_$propName(value, out IntPtr error);")
      appendLine("                if (error != IntPtr.Zero)")
      appendLine("                {")
      appendLine("                    throw NugetErrorNative.BuildException(error);")
      append("                }")
    } else null

    if (isMutable) {
      members.add(
        CirDllImport(
          libraryName = libraryName,
          entryPoint = "set_$cname",
          returnType = "void",
          name = "Native_Set_$propName",
          parameters = listOf(CirParameter("value", "string?")),
          visibility = CirVisibility.PRIVATE,
          hasSyncErrorOut = true,
        )
      )
    }
  } else if (propType == "String") {
    csType = "string"
    getter = buildString {
      appendLine()
      appendLine("                IntPtr nativeResult = Native_Get_$propName(out IntPtr error);")
      appendLine("                if (error != IntPtr.Zero)")
      appendLine("                {")
      appendLine("                    throw NugetErrorNative.BuildException(error);")
      appendLine("                }")
      append("                return Marshal.PtrToStringUTF8(nativeResult)!;")
    }
    setter = if (isMutable) buildString {
      appendLine()
      appendLine("                Native_Set_$propName(value, out IntPtr error);")
      appendLine("                if (error != IntPtr.Zero)")
      appendLine("                {")
      appendLine("                    throw NugetErrorNative.BuildException(error);")
      append("                }")
    } else null

    if (isMutable) {
      members.add(
        CirDllImport(
          libraryName = libraryName,
          entryPoint = "set_$cname",
          returnType = "void",
          name = "Native_Set_$propName",
          parameters = listOf(CirParameter("value", "string")),
          visibility = CirVisibility.PRIVATE,
          hasSyncErrorOut = true,
        )
      )
    }
  } else {
    csType = csNativeReturnType
    getter = buildString {
      appendLine()
      appendLine("                $csNativeReturnType result = Native_Get_$propName(out IntPtr error);")
      appendLine("                if (error != IntPtr.Zero)")
      appendLine("                {")
      appendLine("                    throw NugetErrorNative.BuildException(error);")
      appendLine("                }")
      append("                return result;")
    }
    setter = if (isMutable) buildString {
      appendLine()
      appendLine("                Native_Set_$propName(value, out IntPtr error);")
      appendLine("                if (error != IntPtr.Zero)")
      appendLine("                {")
      appendLine("                    throw NugetErrorNative.BuildException(error);")
      append("                }")
    } else null

    if (isMutable) {
      members.add(
        CirDllImport(
          libraryName = libraryName,
          entryPoint = "set_$cname",
          returnType = "void",
          name = "Native_Set_$propName",
          parameters = listOf(CirParameter("value", csNativeReturnType)),
          visibility = CirVisibility.PRIVATE,
          hasSyncErrorOut = true,
        )
      )
    }
  }

  members.add(
    CirProperty(
      name = csPropName,
      type = csType,
      nativeReturnType = csNativeReturnType,
      nativeName = propName,
      getter = getter,
      setter = setter,
      isStatic = true,
      hasSyncErrorOut = true,
    )
  )

  return members
}

/**
 * A `const val` as a C# `const`, its value the compiler's EVALUATED constant ([KotlinConstValue]),
 * rendered from that value and the declared type: never from source text (ROADMAP line 25).
 *
 * When the value cannot be read (a KSP version without the reflective accessor, or no constant
 * initializer), the const is skipped and, when [logger] is given, named with
 * `SKIPPED_UNREADABLE_CONST_VALUE`. [logger] is null only for a caller that asks for the name alone
 * (the top-level static-name collision set), so each skip is reported once, by the translation.
 */
internal fun translateConstProperty(
  prop: KSPropertyDeclaration,
  logger: KSPLogger?,
): CirConst? {
  val propName: String = prop.simpleName.asString()
  val propTypeResolved: KSType = prop.type.resolve().expandAliases()
  val propType: String = propTypeResolved.declaration.simpleName.asString()
  // Issue #285, the twin of the enum-entry defect: the identical expression lived here, so
  // `const val MaxRetries` was only reachable as `Maxretries`. Same helper, same rule, one place.
  val csPropName: String = propName.kotlinConstantToPascalCase()
  val csType: String = KOTLIN_TO_CSHARP_PARAM[propType] ?: return null
  val csValue: String = when (val read = KotlinConstValue.read(prop)) {
    is KotlinConstValue.Read.Evaluated -> KotlinConstValue.csharpLiteral(read.value, propType)
      ?: return null.also {
        reportUnreadableConst(
          prop,
          logger,
          "the compiler's evaluated constant `${read.value}` " +
              "(${read.value.javaClass.name}) is not a $propType value",
        )
      }
    is KotlinConstValue.Read.Unreadable ->
      return null.also { reportUnreadableConst(prop, logger, read.reason) }
  }

  return CirConst(name = csPropName, type = csType, value = csValue)
}

private fun reportUnreadableConst(prop: KSPropertyDeclaration, logger: KSPLogger?, reason: String) {
  logger ?: return
  val name: String = prop.simpleName.asString()
  // A companion's const is a static on its owning class (ADR-013); `forwardDiagnosticOwner` folds
  // the companion away. A dependency-klib top-level const has no file holder: owner null.
  val owner: ForwardDiagnosticOwner? = when (val parent = prop.parentDeclaration) {
    is KSClassDeclaration -> parent.forwardDiagnosticOwner()
    else -> prop.forwardFileClassOwner()
  }
  ForwardDiagnosticSink.emit(
    listOf(
      ForwardDiagnostic(
        kind = ForwardDiagnosticKind.SKIPPED_UNREADABLE_CONST_VALUE,
        symbol = prop,
        declaration = prop.qualifiedName?.asString() ?: name,
        reason = "the value of `const val $name` is not readable: $reason",
        hint = "a const's C# value is only ever the compiler's evaluated constant, never its " +
            "source text; build with the KSP version this plugin pins, or declare it as a plain " +
            "`val` to bind it as a get-only property instead",
        owner = owner,
        member = name,
      ),
    ),
    logger,
  )
}
