package io.github.xxfast.kotlin.native.nuget.processor.forward

import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeArgument
import com.google.devtools.ksp.symbol.KSTypeParameter
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Variance
import io.github.xxfast.kotlin.native.nuget.processor.cir.FLOW_TYPES
import io.github.xxfast.kotlin.native.nuget.processor.cir.LAMBDA_TYPES
import io.github.xxfast.kotlin.native.nuget.processor.cir.STATE_FLOW_TYPES
import io.github.xxfast.kotlin.native.nuget.processor.cir.SUSPEND_LAMBDA_TYPES
import io.github.xxfast.kotlin.native.nuget.processor.cir.expandAliases
import io.github.xxfast.kotlin.native.nuget.processor.cir.extensionNamespace
import io.github.xxfast.kotlin.native.nuget.processor.cir.mapPackageToNamespace
import io.github.xxfast.kotlin.native.nuget.processor.cir.nestedCsName
import io.github.xxfast.kotlin.native.nuget.processor.cir.nestedInterfaceCsName

/** The declarations whose StableRef handles are part of this forward export set. */
internal data class ForwardBridgeTypeContext(
  val exportedObjectHandles: Set<String>,
  /** The value classes the renderer declares as a C# `readonly record struct`, which since ADR-134
   *  includes the nested ones under an admitted owner. Its own set rather than a widening of
   *  [exportedObjectHandles]: that set answers "does this type have a StableRef handle", and it is
   *  read by `forwardSuperClass` and the legacy `csTypeArguments` route, neither of which may start
   *  seeing a struct. */
  val exportedValueClasses: Set<String> = emptySet(),
  val rootPackage: String = "",
  val rootNamespace: String = "",
  /** ADR-074 Decision 2: `actual typealias` targets, keyed by the `expect` class's qualified name.
   *  A type reference to such a name resolves to the `expect` class declaration itself, never to
   *  the alias (spike finding 8), so the classifier must redirect by name before it ever reaches
   *  the [exportedObjectHandles] membership check. */
  val actualTypeAliasTargets: Map<String, KSClassDeclaration> = emptyMap(),
  /** ADR-088: the plugin's `bound-types.json`, keyed by the generated stub's Kotlin FQCN. The
   *  forward pipeline cannot derive a bound interface's original C# spelling from anything else
   *  it holds: `nuget.boundPackages` is a flat package list, and the namespace-alias map that
   *  produced the Kotlin package is not invertible. */
  val boundInterfaces: Map<String, ForwardBoundInterface> = emptyMap(),
  /** ADR-066's reachability closure keeps why it refused each discovered dependency declaration;
   *  the classifier only sees "not in [exportedObjectHandles], and no containing file", which
   *  cannot tell an `exclude(...)` apart from a missing `include(...)`. */
  val refusedDependencyTypes: Map<String, ForwardAdmissionRefusal> = emptyMap(),
  /** ADR-115 amendment: the `@RequiresOptIn` marker FQNs `publish { exportMarkers(...) }` waives.
   *  Carried here rather than read from a process-global set so two publishers in one Gradle
   *  daemon cannot see each other's list (the ADR-054 lesson). */
  val exportMarkers: Set<String> = emptySet(),
)

/**
 * Classifies resolved, alias-expanded KSP types once, before planning decides how they move over
 * the ABI. The classifier is intentionally strict: an ordinary class is a handle only when its
 * declaration is in the export set, and every collection must retain all of its type arguments.
 */
internal class ForwardBridgeTypeClassifier(
  private val context: ForwardBridgeTypeContext,
) {
  /**
   * The export set, exposed so the planners can answer `forwardSuperClass(exportedTypes)` (ADR-101
   * amendment) with the same membership test this classifier uses for a handle. Identical, bucket
   * for bucket, to `CirClassTranslator`'s `exportedTypes`, which is how the translator and both
   * planners cannot disagree about whether a class has a forward base class.
   */
  internal val exportedObjectHandles: Set<String> get() = context.exportedObjectHandles

  /** ADR-115 amendment: exposed for the same reason [exportedObjectHandles] is. The planners and
   *  the legacy export arms hold this classifier, not the [ForwardBridgeTypeContext], and every
   *  opt-in marker read has to consult the identical waiver list this classifier does. */
  internal val exportMarkers: Set<String> get() = context.exportMarkers

  /**
   * The C# namespace [declaration] is rendered into, by the same mapping [interfaceType] qualifies
   * with; null when there is no root namespace to qualify against. Exposed for the base-list
   * spellings, which name a type bare only when it shares the referencing type's namespace.
   */
  internal fun csharpNamespaceOf(declaration: KSDeclaration): String? {
    if (context.rootNamespace.isEmpty()) return null
    return mapPackageToNamespace(
      declaration.packageName.asString(),
      context.rootPackage,
      context.rootNamespace,
    )
  }

  /**
   * ADR-188 amendment: the namespace an extension on [receiver] declared by [declaring] lands in,
   * by the translator's own ADR-126 rule ([extensionNamespace]) over the identical export set.
   */
  internal fun extensionNamespaceOf(receiver: KSDeclaration, declaring: KSDeclaration): String =
    extensionNamespace(receiver, declaring, context.exportedObjectHandles) { pkg ->
      mapPackageToNamespace(pkg, context.rootPackage, context.rootNamespace)
    }

  fun classify(type: KSType): BridgeType {
    val expanded: KSType = type.expandAliases()
    val classified: BridgeType = classifyNonNullable(expanded)
    // Since the ADR-018 amendment `expandAliases()` carries an alias use site's `?` itself, so the
    // expanded side alone is sufficient; the unexpanded read is kept as a belt-and-braces OR.
    val isNullable: Boolean = type.isMarkedNullable || expanded.isMarkedNullable
    if (!isNullable) return classified
    // ADR-147 amendment: a nullable-bounded `T` is already wrapped; a `T?` use site keeps the
    // wrap and drops the bare-`T` C# spelling rather than wrapping twice.
    val boundNullable: BridgeType.TypeParameter? =
      ((classified as? BridgeType.Nullable)?.type as? BridgeType.TypeParameter)
        ?.takeIf { parameter -> parameter.nullableFromBound }
    return if (boundNullable != null) {
      BridgeType.Nullable(boundNullable.copy(nullableFromBound = false))
    } else {
      BridgeType.Nullable(classified)
    }
  }

  private fun classifyNonNullable(type: KSType): BridgeType {
    val declaration = type.declaration
    // ADR-147: a *class's* type parameter is a first-class kind now, carrying its declared bound
    // so both halves can spell the boxed wire. Scoped to a class deliberately: an interface's own
    // type parameter (issue #112) and a function's own keep the named legacy refusal, because
    // neither route spells an applied receiver and both have their own open questions.
    if (declaration is KSTypeParameter) {
      val owner: KSDeclaration? = declaration.parentDeclaration
      val onGenericClass: Boolean =
        owner is KSClassDeclaration && owner.classKind == ClassKind.CLASS
      return if (onGenericClass) {
        val bounds: List<String> = declaration.forwardBoundSpellings()
        val parameter = BridgeType.TypeParameter(
          name = (owner as KSClassDeclaration).forwardCsharpTypeParameterName(declaration),
          boundQualifiedName = bounds.firstOrNull(),
          kotlinName = declaration.simpleName.asString(),
          additionalBounds = bounds.drop(1),
        )
        // ADR-147 amendment: an unconstrained `T` has upper bound `Any?`, so a bare `T` is as
        // nullable as `T?` on the Kotlin half and crosses on the same null-pointer wire (ADR-083).
        if (declaration.hasNullableBound()) {
          BridgeType.Nullable(parameter.copy(nullableFromBound = true))
        } else {
          parameter
        }
      } else {
        BridgeType.Unsupported(
          declaration.simpleName.asString(),
          "type parameters require the named generic legacy route",
        )
      }
    }

    val classDeclaration: KSClassDeclaration = declaration as? KSClassDeclaration
      ?: return BridgeType.RawKSType(
        declaration.qualifiedName?.asString() ?: declaration.simpleName.asString(),
      )
    val qualifiedName: String = classDeclaration.qualifiedName?.asString()
      ?: return BridgeType.Unsupported(
        classDeclaration.simpleName.asString(),
        "local and anonymous declarations are not bridgeable",
      )

    // ADR-074 Decision 2: a reference to an `actual typealias`-actualized `expect class` resolves
    // to the `expect` declaration itself, from every source set, never to the alias (spike finding
    // 8) -- `expandAliases()` (ADR-018) structurally cannot see through it, since `KSType
    // .declaration` here is a class, not a `KSTypeAlias`. Applied before every other branch below,
    // including the `exportedObjectHandles` membership check, so the C# type this produces is
    // always the target's.
    if (classDeclaration.isExpect) {
      context.actualTypeAliasTargets[qualifiedName]?.let { target ->
        return classifyActualTypeAliasTarget(qualifiedName, target)
      }
    }

    // ADR-088: checked before the exportedObjectHandles membership test (and before every other
    // shape branch) — a bound stub is deliberately kept OUT of the forward root buckets so it is
    // never re-projected as a duplicate `IIFeedable`, which means it would otherwise fall straight
    // through to `SKIPPED_UNSUPPORTED_TYPE`.
    context.boundInterfaces[qualifiedName]?.let { bound ->
      return BridgeType.BoundInterface(
        qualifiedName = qualifiedName,
        csharpType = "global::${bound.csharpName}",
        implementable = bound.implementable,
      )
    }

    knownScalarType(qualifiedName)?.let { return it }
    if (qualifiedName == "kotlin.Char") return BridgeType.Char
    if (qualifiedName == "kotlin.String") return BridgeType.String
    // ADR-076: kotlin.time.Instant is a known stdlib type, in the same category as Char/String --
    // recognized here, before the exportedObjectHandles membership check below, so it never falls
    // through to SKIPPED_UNEXPORTED_DEPENDENCY_TYPE with an unactionable include(...) hint.
    if (qualifiedName == "kotlin.time.Instant") return BridgeType.Instant
    // ADR-103: kotlin.time.Duration, same known-stdlib category as Instant. This line MUST stay
    // ahead of the isValueClass() branch below: Duration *is* a value class over a private
    // `rawValue` Long with an internal constructor, so the shape branch would otherwise win and
    // bind an encoding the generated Kotlin cannot even compile against.
    if (qualifiedName == "kotlin.time.Duration") return BridgeType.Duration
    // ADR-106: kotlin.uuid.Uuid, the third known stdlib type. A plain class (not a value class),
    // so ordering against isValueClass() is irrelevant; it stays in this block by convention.
    if (qualifiedName == "kotlin.uuid.Uuid") return BridgeType.Uuid
    // ADR-151: kotlin.ByteArray, the fourth known stdlib type, mapped to C# `byte[]` over the
    // collection handle wire. A plain final class (UByteArray is the value class, and is out of
    // scope), so ordering against isValueClass() below is irrelevant.
    if (qualifiedName == "kotlin.ByteArray") return BridgeType.ByteArray
    // ADR-064 (2026-09-11): kotlin.sequences.Sequence is the first known stdlib type recognized
    // here to be *refused* rather than bound. It is an interface with one type parameter, so
    // without this line the generic-interface arm below claims it as
    // SpecializedProtocol("generic declaration ..."), which the planner maps to the
    // droppedFromCSharp = false GENERIC deferral -- but no legacy route is keyed on a parameter's
    // or return's type, so the member vanished from C# with no diagnostic at all. Named here so
    // every position skips loudly. Sequence only: Iterable/Iterator/Collection are supertypes of
    // List and a line for them would mask the collection route.
    if (qualifiedName == "kotlin.sequences.Sequence") {
      return BridgeType.Unsupported(
        rendered = qualifiedName,
        reason = "a lazy Sequence has no bridge shape; expose a List instead",
      )
    }
    // ADR-107: kotlin.Throwable and every stdlib subtype of it (Exception, IllegalStateException,
    // ...). Supertype-aware, because the declared property type is usually a subtype; ahead of the
    // exportedObjectHandles membership test below, so a stdlib throwable stops being an
    // "unexported dependency". A user exception class that IS in the export set keeps its
    // ObjectHandle binding (decision 3), which is why the membership test guards this line.
    if (qualifiedName !in context.exportedObjectHandles && classDeclaration.isStdlibThrowable()) {
      return BridgeType.Throwable
    }
    // ADR-160: a plain Kotlin function type whose payload and result are both shapes the plan's
    // two callback lowerings implement becomes a first-class [BridgeType.Callback]. Ahead of
    // `specializedProtocol`, which is what every other (and every non-parameter) function-type
    // spelling still falls through to, so the legacy per-call route keeps exactly the shapes the
    // plan does not carry.
    if (qualifiedName in LAMBDA_TYPES) {
      callbackType(type)?.let { return it }
    }
    specializedProtocol(qualifiedName)?.let { return it }
    collectionType(qualifiedName, type.arguments)?.let { return it }

    // ADR-115: a class/object/interface/enum/value class carrying a `@RequiresOptIn` marker is
    // never declared in C#, so every member typed with it skips -- ahead of every membership test
    // below, whose `include(...)`/move-to-top-level hints cannot repair a marked type.
    val marker: String? = classDeclaration.optInMarker(context.exportMarkers)
    if (marker != null) {
      return BridgeType.Unsupported(
        qualifiedName,
        "marked with the opt-in marker `$marker`, so no C# type is declared for it",
        optInMarker = marker,
      )
    }

    if (classDeclaration.classKind == ClassKind.ENUM_CLASS) {
      // The membership gate the enum branch never had. `exportedObjectHandles` holds exactly the
      // enums the renderer declares (NugetProcessor's `enums` list feeds both), so an enum outside
      // it has no C# declaration and spelling it — which [csharpTypeNameFor] happily does, nesting
      // and all — leaves a dangling reference the consumer cannot compile (CS0426/CS0234). Skip
      // named instead, exactly as the class branch below does for an unexported handle.
      if (qualifiedName !in context.exportedObjectHandles) {
        // A nested enum ADR-133 cannot declare is not out of scope but undeclarable, and the
        // `include(...)` hint would be actively wrong for it, so the nested test runs ahead of the
        // scope-widening route — but only for a nested enum the closure did NOT refuse on scope
        // grounds (see [scopeRefusal]): when the enum, or its owner, is outside the export scope,
        // widening the scope is exactly the fix and the nested wording would hide it.
        val scopeRefusal: ForwardAdmissionRefusal? = scopeRefusal(qualifiedName)
        val isNested: Boolean = classDeclaration.parentDeclaration != null && scopeRefusal == null
        if (!isNested && classDeclaration.containingFile == null) {
          // ADR-151: a stdlib type that reaches this gate is merely unmapped, not out of
          // scope: no include(...) repairs it, so it is refused as plainly unsupported and the
          // skip reads SKIPPED_UNSUPPORTED_TYPE with the stdlib hint.
          if (qualifiedName.isStdlibPackage()) return unmappedStdlibType(qualifiedName)
          return BridgeType.Unsupported(
            qualifiedName,
            "declared in a dependency module whose package is outside the export scope",
            isUnexportedDependency = true,
            // The class and interface branches always passed the closure's refusal here; the enum
            // branch dropped it, so an excluded or cross-module-disabled enum was told to widen
            // the scope, the one remedy that cannot work for it.
            unexportedDependencyRefusal = scopeRefusal,
          )
        }
        return BridgeType.Unsupported(
          qualifiedName,
          if (isNested) {
            "a nested enum class with no C# nested enum declared for it"
          } else {
            "enum class is not in the exported object-handle set"
          },
          isUndeclaredEnum = true,
        )
      }
      // Identical spelling rule to [csharpTypeNameFor], so it goes through the same helper rather
      // than repeating it — the two must never drift.
      return BridgeType.Enum(qualifiedName, csharpTypeNameFor(classDeclaration))
    }
    if (classDeclaration.isValueClass()) {
      return valueClass(classDeclaration, qualifiedName, type.arguments)
    }
    if (classDeclaration.modifiers.contains(Modifier.SEALED)) {
      // ADR-105: the protocol still names the legacy route (every position that had one keeps it,
      // unchanged), but it now also carries the ObjectHandle this type WOULD be, so a position
      // that can bridge a sealed base -- today a property -- unwraps it without the classifier
      // becoming position-aware.
      //
      // Kind-aware it must be, though, which ADR-105 does not say: only a sealed type
      // `rootSealedClasses` admits reaches the ADR-009 renderer and gets a `FromHandle`
      // discriminator to reconstruct through, which since ADR-112 is a sealed class or an
      // ELIGIBLE sealed interface; and this branch fires ahead of `objectHandle()`'s
      // `exportedObjectHandles` membership test (:150), so an out-of-scope sealed type would
      // otherwise be handed a C# spelling nothing generates. Both keep the bare protocol and skip
      // exactly as before.
      val discriminated: Boolean = classDeclaration.isEligibleSealedType() &&
          qualifiedName in context.exportedObjectHandles
      return BridgeType.SpecializedProtocol(
        "$SEALED_HELPER_PREFIX$qualifiedName",
        sealedHandle = if (discriminated) {
          BridgeType.ObjectHandle(
            qualifiedName,
            csharpType = csharpTypeNameFor(classDeclaration),
            viaDiscriminator = true,
          )
        } else {
          null
        },
      )
    }
    // ADR-040: an interface with type parameters stays on the pre-existing "generic declaration"
    // legacy route (unchanged from before this ADR) rather than becoming a plannable
    // BridgeType.Interface — chosen ahead of the ADR-039 add*/remove* pair exclusion below, since
    // the classifier is deliberately position-agnostic (ForwardCallablePlanner.classEntries
    // already excludes add*/remove* pair methods from planOrSkip before classification's result
    // would matter for them).
    if (classDeclaration.classKind == ClassKind.INTERFACE && classDeclaration.typeParameters.isNotEmpty()) {
      return BridgeType.SpecializedProtocol("generic declaration $qualifiedName")
    }
    if (classDeclaration.classKind == ClassKind.INTERFACE) {
      return interfaceType(classDeclaration, qualifiedName)
    }
    // ADR-196: an inner class that captures a generic owner's `T` is the generic `Tin.Latch<T>` in
    // C#, so a reference to it is a generic reference too, though it declares no parameter itself.
    if (classDeclaration.forwardTypeParametersInScope().isNotEmpty()) {
      return BridgeType.SpecializedProtocol("generic declaration $qualifiedName")
    }
    val isClassOrObject: Boolean =
      classDeclaration.classKind == ClassKind.CLASS ||
          classDeclaration.classKind == ClassKind.OBJECT
    if (!isClassOrObject) {
      return BridgeType.Unsupported(
        qualifiedName,
        "${classDeclaration.classKind} declarations are not bridgeable",
      )
    }
    if (qualifiedName !in context.exportedObjectHandles) {
      // The enum/interface branches' rule verbatim: since ADR-133/134 the owner walk is the sole
      // declarer of a nested type, so a nested name missing from `exportedObjectHandles` is one
      // that walk deferred (an `inner`, generic or sealed shape, or an owner that cannot carry a
      // nested type) or one whose C# name collided, and the `include(...)` hint would be actively
      // wrong for either. The nested test therefore runs FIRST, and only a top-level cross-module
      // declaration takes the scope-widening route below.
      //
      // A sealed subclass is not nested in this sense (ADR-009 declares it under its base, which
      // is exactly how every reference spells it) and neither is a companion object (ADR-013 folds
      // it into its owner's statics), so both are left to the membership test.
      //
      // ADR-066 amendment: "FIRST" now means first among the UNDECLARABLE cases only. A nested
      // declaration the closure refused on SCOPE grounds — its own package outside the scope, or
      // its owner excluded by name — takes the dependency route below, because `include(...)` is
      // what repairs that build and "move it to the top level of its file" repairs nothing in a
      // module the author does not own.
      val isUndeclaredNested: Boolean = classDeclaration.parentDeclaration != null &&
          !classDeclaration.isCompanionObject &&
          !classDeclaration.isSealedSubclass() &&
          scopeRefusal(qualifiedName) == null
      if (isUndeclaredNested) {
        return BridgeType.Unsupported(
          qualifiedName,
          "a nested ${if (classDeclaration.classKind == ClassKind.OBJECT) "object" else "class"} " +
              "with no C# nested type declared for it",
          isUndeclaredClass = true,
        )
      }
      // ADR-066: a declaration read straight off a klib dependency (never seen by
      // `resolver.getAllFiles()`) carries no containing file — verified in the ADR's spike. A
      // module-local declaration that simply fell outside the ADR-063 package filter still keeps
      // the old, generic message; only the cross-module case gets the closure's own diagnostic.
      val isUnexportedDependency: Boolean = classDeclaration.containingFile == null
      // ADR-151: same gate as the enum branch above, for the class/object fall-through this
      // issue's `ByteArray` actually landed in.
      if (isUnexportedDependency && qualifiedName.isStdlibPackage()) {
        return unmappedStdlibType(qualifiedName)
      }
      return BridgeType.Unsupported(
        qualifiedName,
        if (isUnexportedDependency) {
          "declared in a dependency module whose package is outside the export scope"
        } else {
          "declaration is not in the exported object-handle set"
        },
        isUnexportedDependency = isUnexportedDependency,
        unexportedDependencyRefusal = context.refusedDependencyTypes[qualifiedName],
      )
    }
    // ADR-133: a Kotlin `object` renders as a C# STATIC class, which cannot be a member type
    // (CS0722: "cannot convert to/from a static type", and a static type is illegal as a
    // parameter or return type at all). Before this ADR every nested object was refused one line
    // up by the nested gate and every top-level object return emitted uncompilable C#. Skipped
    // named at the position instead, so `fun single(): Marker` says why it vanished.
    if (classDeclaration.classKind == ClassKind.OBJECT && !classDeclaration.isSealedSubclass()) {
      return BridgeType.Unsupported(
        qualifiedName,
        "`${classDeclaration.simpleName.asString()}` is a Kotlin `object`, declared in C# as a " +
            "static class, which cannot appear at a parameter or return position (CS0722)",
        isObjectPosition = true,
      )
    }
    return BridgeType.ObjectHandle(qualifiedName, csharpType = csharpTypeNameFor(classDeclaration))
  }

  /**
   * ADR-074 Decision 2 (2a): erase an `actual typealias` to its target and classify that instead
   * — the C# type is always the target's, never the `expect`'s, exactly as an ordinary
   * `typealias` is erased under ADR-018. v1 admits only a redirect to a plain, non-generic class
   * (Consequences, "deferred" list); anything else, including a target the forward direction
   * cannot otherwise export, takes the `SKIPPED_ACTUAL_TYPEALIAS_TARGET` path via
   * [BridgeType.Unsupported.isActualTypeAliasTarget] rather than the generic
   * `SKIPPED_UNEXPORTED_DEPENDENCY_TYPE`/`SKIPPED_UNSUPPORTED_TYPE` messages, since a platform
   * library or stdlib type can never be brought into scope with `include(...)`.
   */
  private fun classifyActualTypeAliasTarget(
    expectQualifiedName: String,
    target: KSClassDeclaration,
  ): BridgeType {
    val targetQualifiedName: String? = target.qualifiedName?.asString()
    if (targetQualifiedName == null || target.typeParameters.isNotEmpty()) {
      return BridgeType.Unsupported(
        targetQualifiedName ?: target.simpleName.asString(),
        "actual typealias target is not a plain, non-generic class",
        isActualTypeAliasTarget = true,
        actualTypeAliasExpectName = expectQualifiedName,
      )
    }
    val classified: BridgeType = classifyNonNullable(target.asStarProjectedType())
    // ADR-107 does not extend to an `expect class` actualized onto a stdlib throwable: the
    // envelope binds a Throwable-typed *property*, not a whole type, so this stays ADR-074's
    // named actual-typealias-target skip rather than silently becoming a System.Exception.
    if (classified == BridgeType.Throwable) {
      return BridgeType.Unsupported(
        targetQualifiedName,
        "actual typealias target is a Kotlin throwable, which binds only at a property position",
        isActualTypeAliasTarget = true,
        actualTypeAliasExpectName = expectQualifiedName,
      )
    }
    if (classified is BridgeType.Unsupported) {
      return classified.copy(
        isActualTypeAliasTarget = true,
        actualTypeAliasExpectName = expectQualifiedName,
      )
    }
    return classified
  }

  /**
   * ADR-066 amendment: the closure's refusal for this name when it is a refusal an author can act
   * on by changing the export SCOPE, rather than the "declared by its owner's walk, if at all"
   * marker. The class, enum and interface membership gates consult this ahead of their nested
   * test, so a nested dependency declaration outside the scope reports the `include(...)` remedy
   * that repairs the build instead of a move-to-the-top-level one that cannot.
   *
   * [ForwardAdmissionRefusal.NESTED_DECLARATION] is deliberately the only value filtered out: it
   * is the closure saying "not admitted here, ask ADR-133's owner walk", which is precisely what
   * the nested gates already report.
   */
  private fun scopeRefusal(qualifiedName: String): ForwardAdmissionRefusal? =
    context.refusedDependencyTypes[qualifiedName]
      ?.takeIf { refusal -> refusal != ForwardAdmissionRefusal.NESTED_DECLARATION }

  /**
   * ADR-040: an interface at an ordinary (non ADR-039 add/remove-pair) position. Mirrors the
   * [ObjectHandle] membership check exactly, but the public spelling is `I$simpleName` (the
   * projected interface) and the construction spelling is `$simpleName` (the generated backing
   * wrapper class) — both qualified together under the same unconditional rule
   * [csharpTypeNameFor] applies to a class/object handle (issue #41).
   */
  private fun interfaceType(declaration: KSClassDeclaration, qualifiedName: String): BridgeType {
    if (qualifiedName !in context.exportedObjectHandles) {
      // Issue #54, the enum branch's rule verbatim: ADR-133/134's owner walk is the sole declarer
      // of a nested interface, so a nested name missing here is one that walk deferred or one
      // whose C# name collided, and the `include(...)` hint would be actively wrong for it. The
      // nested test therefore runs FIRST, and only a top-level cross-module interface takes the
      // scope-widening route, with the ADR-066 amendment's one exception, shared with the class
      // and enum branches: a nested interface the closure refused on SCOPE grounds wants the scope
      // remedy, not this one.
      if (declaration.parentDeclaration != null && scopeRefusal(qualifiedName) == null) {
        return BridgeType.Unsupported(
          qualifiedName,
          "a nested interface with no C# nested interface declared for it",
          isUndeclaredInterface = true,
        )
      }
      val isUnexportedDependency: Boolean = declaration.containingFile == null
      // ADR-151: the interface twin of the class fall-through's stdlib gate.
      if (isUnexportedDependency && qualifiedName.isStdlibPackage()) {
        return unmappedStdlibType(qualifiedName)
      }
      return BridgeType.Unsupported(
        qualifiedName,
        if (isUnexportedDependency) {
          "declared in a dependency module whose package is outside the export scope"
        } else {
          "declaration is not in the exported object-handle set"
        },
        isUnexportedDependency = isUnexportedDependency,
        unexportedDependencyRefusal = context.refusedDependencyTypes[qualifiedName],
      )
    }
    // ADR-133: the enclosing scope, with the `I` on the LAST segment only (`Owner.IListener`).
    // `I` + nestedCsName() would give the nonexistent `IOwner.Listener`, and the bare simple name
    // would give a namespace-root `IListener` nothing declares (CS0246).
    val simpleName: String = declaration.nestedCsName()
    val interfaceName: String = declaration.nestedInterfaceCsName()
    if (context.rootNamespace.isEmpty()) {
      return BridgeType.Interface(
        qualifiedName,
        csharpType = interfaceName,
        backingType = simpleName,
      )
    }
    val namespace: String = mapPackageToNamespace(
      declaration.packageName.asString(),
      context.rootPackage,
      context.rootNamespace,
    )
    return BridgeType.Interface(
      qualifiedName,
      csharpType = "global::$namespace.$interfaceName",
      backingType = "global::$namespace.$simpleName",
    )
  }

  /**
   * The public C# spelling of a class/object handle or a value class, always fully qualified as
   * `global::{namespace}.{Name}` — the enum branch's shape, verbatim.
   *
   * ADR-066 originally kept a *module-local* declaration bare and qualified only an admitted
   * dependency-module type, on the reasoning that a module-local type always shares its
   * referencing class's namespace. Issue
   * [#41](https://github.com/xxfast/kotlin-native-nuget/issues/41) disproved that: a module's own
   * sub-package maps to a sub-namespace (`TestLibrary.Issue41`), so a root-namespace class
   * referencing it emitted a bare `Issue41Thing` in its property, constructor-parameter,
   * `new List<T>(count)`, `FromHandle<T>`, `new T(handle)` and `Copy` positions and `Interop.cs`
   * failed `CS0246` — reported in the field against `joreilly/PeopleInSpace#503`. The classifier
   * is deliberately position-agnostic (it never sees which namespace the *referencing*
   * declaration lands in), so "same namespace stays bare" is not a decision it can make
   * correctly; qualifying unconditionally is. `global::` is legal everywhere a [BridgeType]'s
   * `csharpType` is rendered — every one of those positions is a type reference (parameter and
   * return types, casts, `new T(...)`, `out T x`, generic arguments), never an identifier.
   *
   * The one bare case left is an empty [ForwardBridgeTypeContext.rootNamespace], where there is
   * no namespace to qualify against at all.
   */
  private fun csharpTypeNameFor(classDeclaration: KSClassDeclaration): String {
    // The name carries its enclosing scope ([nestedCsName]): a sealed subclass is *declared* as a
    // nested C# class under ADR-009 (`Shape.Circle`), so spelling it `Circle` at a member type
    // position names a type that does not exist and fails Interop.cs with CS0234.
    val nestedName: String = classDeclaration.nestedCsName()
    if (context.rootNamespace.isEmpty()) return nestedName
    val namespace: String = mapPackageToNamespace(
      classDeclaration.packageName.asString(),
      context.rootPackage,
      context.rootNamespace,
    )
    return "global::$namespace.$nestedName"
  }

  /**
   * ADR-107: whether this declaration is `kotlin.Throwable` or inherits from it. `getAllSuperTypes`
   * resolves for klib-origin stdlib declarations; a failure to resolve leaves the old behaviour
   * (the type falls through to the exportedObjectHandles test) rather than mis-binding.
   */
  private fun KSClassDeclaration.isStdlibThrowable(): Boolean {
    if (qualifiedName?.asString() == "kotlin.Throwable") return true
    // The supertype walk is restricted to klib-origin declarations (`containingFile == null`, the
    // same cross-module signal ADR-066 uses), which is what "stdlib subtype" means here:
    // `Exception`, `IllegalStateException`, ... A module-local exception class keeps its existing
    // classification, and an exported one keeps its ObjectHandle binding (decision 3). It also
    // keeps the classifier from asking every ordinary class for its supertypes.
    if (containingFile != null) return false
    return getAllSuperTypes().any { supertype ->
      supertype.declaration.qualifiedName?.asString() == "kotlin.Throwable"
    }
  }

  private fun valueClass(
    declaration: KSClassDeclaration,
    qualifiedName: String,
    arguments: List<KSTypeArgument>,
  ): BridgeType {
    // The membership gate the value-class branch never had, ahead of the underlying checks: since
    // ADR-134 a nested value class IS declared as a `readonly record struct` under an admitted
    // owner, so a nested one missing from `exportedValueClasses` is one the owner walk deferred (a
    // generic or `enum class` owner) or one whose C# name collided -- and [csharpTypeNameFor]
    // spells it `Owner.Name` regardless, leaving `Interop.cs` referring to a struct nothing
    // declares (CS0426/CS0234). Skip named instead, exactly as the enum, interface and class
    // branches do.
    //
    // Deliberately NOT the full membership test those three use: `kotlin.Result` is a top-level
    // value class that is in no export set and must keep classifying as a `ValueClass` for
    // ADR-108's return-position rewrite, so only a nested value class is gated here. A top-level
    // one outside the set is spelled exactly as it was before.
    val isUndeclaredNestedValueClass: Boolean = declaration.parentDeclaration != null &&
        qualifiedName !in context.exportedValueClasses
    if (isUndeclaredNestedValueClass) {
      // ADR-066 amendment, the class branch's rule verbatim: a nested declaration the closure
      // refused on SCOPE grounds wants `include(...)`, not "move it to the top level".
      val scopeRefusal: ForwardAdmissionRefusal? = scopeRefusal(qualifiedName)
      if (scopeRefusal != null) {
        return BridgeType.Unsupported(
          qualifiedName,
          "declared in a dependency module whose package is outside the export scope",
          isUnexportedDependency = true,
          unexportedDependencyRefusal = scopeRefusal,
        )
      }
      return BridgeType.Unsupported(
        qualifiedName,
        "a nested value class with no C# nested record struct declared for it",
        isUndeclaredValueClass = true,
      )
    }

    // ROADMAP line 38, folded into ADR-154 §4: the TOP-LEVEL dependency value class. Until this
    // gate, a value class the closure refused on scope grounds was still spelled
    // `global::Ns.Eartag` at every position (and `new global::Ns.Eartag(...)` at a return), with no
    // diagnostic at all, against a struct nothing ever declared: CS0246 in the consumer's build,
    // from a member the author was never told about. Per-type `admit(...)` makes it far easier to
    // reach, since a dependency class's members routinely mention sibling value classes.
    //
    // Keyed on the closure's SCOPE refusal, never on "absent from `exportedValueClasses`": that
    // wider test would refuse `kotlin.Result`, which is a top-level value class in no export set
    // and must keep classifying as a `ValueClass` for ADR-108's payload rewrite. `Result` is
    // recorded refused `NOT_INCLUDED` (it is not an INTRINSIC_TERMINAL of the closure), so the
    // carve-out is explicit rather than incidental. `Duration` and `Uuid` ARE terminals and never
    // reach here; if another stdlib value class ever does, it lands on this named skip rather than
    // on an undeclared spelling, which is the safe direction.
    val topLevelScopeRefusal: ForwardAdmissionRefusal? = scopeRefusal(qualifiedName)
      ?.takeIf { declaration.parentDeclaration == null && qualifiedName != RESULT_QUALIFIED_NAME }
    if (topLevelScopeRefusal != null) {
      return BridgeType.Unsupported(
        qualifiedName,
        "declared in a dependency module whose package is outside the export scope",
        isUnexportedDependency = true,
        unexportedDependencyRefusal = topLevelScopeRefusal,
      )
    }
    val underlyingParam = declaration.primaryConstructor?.parameters?.singleOrNull()
      ?: return BridgeType.Unsupported(
        qualifiedName,
        "value class must have exactly one underlying property",
      )
    val underlyingPropertyName: String = underlyingParam.name?.asString()
      ?: return BridgeType.Unsupported(
        qualifiedName,
        "value class underlying parameter must be named",
      )
    // ADR-108: carry the classified type arguments so the planner can see `Result<T>`'s payload.
    // A star projection (`type == null`) contributes nothing, which is what keeps `Result<*>` on
    // its named skip.
    val typeArguments: List<BridgeType> = arguments.mapNotNull { argument ->
      argument.type?.let { reference -> classify(reference.resolve()) }
    }
    return BridgeType.ValueClass(
      qualifiedName,
      classify(underlyingParam.type.resolve()),
      underlyingPropertyName,
      csharpType = csharpTypeNameFor(declaration),
      typeArguments = typeArguments,
    )
  }

  private fun collectionType(
    qualifiedName: String,
    arguments: List<KSTypeArgument>,
  ): BridgeType? {
    val kind: CollectionKind = when (qualifiedName) {
      "kotlin.collections.List" -> CollectionKind.LIST
      "kotlin.collections.MutableList" -> CollectionKind.MUTABLE_LIST
      "kotlin.collections.Map" -> CollectionKind.MAP
      "kotlin.collections.MutableMap" -> CollectionKind.MUTABLE_MAP
      "kotlin.collections.Set" -> CollectionKind.SET
      "kotlin.collections.MutableSet" -> CollectionKind.MUTABLE_SET
      else -> return null
    }
    val isMap: Boolean = kind == CollectionKind.MAP || kind == CollectionKind.MUTABLE_MAP
    if (arguments.any { argument -> argument.type == null }) return BridgeType.RawCollection(kind)
    if (isMap) {
      if (arguments.size != 2) return BridgeType.RawCollection(kind)
      val key: KSType = arguments[0].type?.resolve() ?: return BridgeType.RawCollection(kind)
      val value: KSType = arguments[1].type?.resolve() ?: return BridgeType.RawCollection(kind)
      return BridgeType.Collection(kind, key = classify(key), value = classify(value))
    }
    if (arguments.size != 1) return BridgeType.RawCollection(kind)
    val element: KSType = arguments.single().type?.resolve()
      ?: return BridgeType.RawCollection(kind)
    return BridgeType.Collection(kind, element = classify(element))
  }

  private fun knownScalarType(qualifiedName: String): BridgeType? = when (qualifiedName) {
    "kotlin.Boolean" -> BridgeType.Primitive(PrimitiveKind.BOOLEAN)
    "kotlin.Byte" -> BridgeType.Primitive(PrimitiveKind.BYTE)
    "kotlin.UByte" -> BridgeType.Primitive(PrimitiveKind.UBYTE)
    "kotlin.Short" -> BridgeType.Primitive(PrimitiveKind.SHORT)
    "kotlin.UShort" -> BridgeType.Primitive(PrimitiveKind.USHORT)
    "kotlin.Int" -> BridgeType.Primitive(PrimitiveKind.INT)
    "kotlin.UInt" -> BridgeType.Primitive(PrimitiveKind.UINT)
    "kotlin.Long" -> BridgeType.Primitive(PrimitiveKind.LONG)
    "kotlin.ULong" -> BridgeType.Primitive(PrimitiveKind.ULONG)
    "kotlin.Float" -> BridgeType.Primitive(PrimitiveKind.FLOAT)
    "kotlin.Double" -> BridgeType.Primitive(PrimitiveKind.DOUBLE)
    "kotlin.Unit" -> BridgeType.Unit
    else -> null
  }

  /**
   * ADR-160: the [BridgeType.Callback] for a `kotlin.FunctionN` type, or null when either half of
   * the plan's callback lowering cannot carry one of its components, in which case the caller falls
   * through to the `lambda <fqn>` [BridgeType.SpecializedProtocol] and the member takes the named
   * `CALLBACK_PROTOCOL` skip (or keeps its legacy route where one exists).
   *
   * The admitted payload set is exactly ADR-036's table minus what has no crossing convention on
   * any callback route: a primitive by value (`Boolean` over its `Byte` wire), a `String` or an
   * exported object/interface over a handle, an enum over its ordinal. The admitted result set is
   * narrower still, because a value coming OUT of the C# lambda has an ownership question ADR-160
   * left to a sibling item: `Unit`, a primitive by value, or a `String` over the box C# mints and
   * Kotlin releases.
   */
  private fun callbackType(type: KSType): BridgeType.Callback? {
    val arguments: List<KSTypeArgument> = type.arguments
    if (arguments.isEmpty()) return null
    val components: List<BridgeType> = arguments.map { argument ->
      val resolved: KSType = argument.type?.resolve() ?: return null
      classify(resolved)
    }
    val parameters: List<BridgeType> = components.dropLast(1)
    val result: BridgeType = components.last()
    if (!parameters.all { parameter -> parameter.isCallbackPayload() }) return null
    if (!result.isCallbackResult()) return null
    return BridgeType.Callback(parameters, result)
  }

  /**
   * ADR-160 amendment: the [BridgeType.ReturnedLambda] for a non-nullable `kotlin.FunctionN`
   * return, or null for any other type. Position-specific, so [classify] never calls it: the
   * planner asks only at a top-level function's RESULT.
   *
   * Every argument is classified, never refused here: whether C# can spell it is the plan's
   * question ([BridgeType.ReturnedLambda.unnameableTypeArgument]), so an unspellable one is a
   * named skip rather than a fall-through to a protocol with no route. An ADR-009 sealed class
   * argument is carried as its [BridgeType.ObjectHandle], the same unwrap a sealed result takes.
   */
  fun returnedLambdaOrNull(type: KSType): BridgeType.ReturnedLambda? {
    val expanded: KSType = type.expandAliases()
    if (type.isMarkedNullable || expanded.isMarkedNullable) return null
    val qualifiedName: String = expanded.declaration.qualifiedName?.asString() ?: return null
    if (qualifiedName !in LAMBDA_TYPES) return null
    val resolved: List<KSType?> = expanded.arguments.map { argument -> argument.type?.resolve() }
    if (resolved.isEmpty()) return null
    val typeArguments: List<BridgeType> = resolved.map { argument ->
      if (argument == null) {
        BridgeType.Unsupported("an unresolved type argument", "unresolved")
      } else {
        when (val classified: BridgeType = classify(argument)) {
          is BridgeType.SpecializedProtocol -> classified.sealedHandle ?: classified
          is BridgeType.Nullable -> (classified.type as? BridgeType.SpecializedProtocol)
            ?.sealedHandle?.let(BridgeType::Nullable) ?: classified

          else -> classified
        }
      }
    }
    val kotlinTypeArguments: List<String> = resolved.map { argument ->
      val declaration = argument?.expandAliases()?.declaration
      declaration?.qualifiedName?.asString() ?: declaration?.simpleName?.asString()
      ?: "an unresolved type argument"
    }
    return BridgeType.ReturnedLambda(typeArguments, kotlinTypeArguments)
  }

  /** The payload shapes both callback halves lower, in. See [callbackType]. */
  internal fun BridgeType.isCallbackPayload(): Boolean = when (this) {
    is BridgeType.Primitive, BridgeType.String, is BridgeType.Enum -> true
    is BridgeType.ObjectHandle -> !viaDiscriminator
    is BridgeType.Interface -> true
    else -> false
  }

  /** The result shapes both callback halves lower, out. See [callbackType]. */
  private fun BridgeType.isCallbackResult(): Boolean = when (this) {
    BridgeType.Unit, is BridgeType.Primitive, BridgeType.String -> true
    else -> false
  }

  private fun specializedProtocol(qualifiedName: String): BridgeType.SpecializedProtocol? = when {
    // ADR-065: StateFlow is checked before plain Flow -- it is-a Flow, and this is an exact
    // qualifiedName match (not isAssignableFrom), so there is no risk of a StateFlow falling
    // through to the plain-flow branch below and losing its `.Value` legacy-route handling.
    qualifiedName in STATE_FLOW_TYPES -> BridgeType.SpecializedProtocol("state flow $qualifiedName")
    qualifiedName in FLOW_TYPES -> BridgeType.SpecializedProtocol("flow $qualifiedName")
    qualifiedName in LAMBDA_TYPES -> BridgeType.SpecializedProtocol("lambda $qualifiedName")
    qualifiedName in SUSPEND_LAMBDA_TYPES ->
      BridgeType.SpecializedProtocol("suspend lambda $qualifiedName")

    else -> null
  }
}

internal fun KSType.toBridgeType(context: ForwardBridgeTypeContext): BridgeType =
  ForwardBridgeTypeClassifier(context).classify(this)

/**
 * ADR-066 verified spike finding: a cross-module (klib) `value class` reports `Modifier.INLINE`,
 * never `Modifier.VALUE` — only an in-module one reports `VALUE`. Every classification site that
 * tested `Modifier.VALUE` alone would misclassify an admitted dependency value class as an
 * ordinary class and export it as an opaque `IDisposable` handle instead of an unwrapped value:
 * valid-compiling, silently wrong. This is the single shared helper the ADR asks for so a fourth
 * `Modifier.VALUE`-only site can never be added later against the in-module-only test.
 */
internal fun KSClassDeclaration.isValueClass(): Boolean =
  modifiers.contains(Modifier.VALUE) || modifiers.contains(Modifier.INLINE)

/** ADR-108's carrier, carved out of ADR-154 §4's top-level value-class scope gate: it is a
 *  top-level stdlib value class the closure records refused (`NOT_INCLUDED`, since it is not an
 *  intrinsic terminal), yet it must keep classifying as a `ValueClass` so the planner can rewrite a
 *  `Result<T>` return to its payload. It has no C# type of its own, so the gate would amputate the
 *  whole Result route rather than skip one undeclared spelling. */
private const val RESULT_QUALIFIED_NAME: String = "kotlin.Result"

/**
 * ADR-147: every upper bound's Kotlin spelling as a concrete type argument
 * ([forwardKotlinBoundSpelling]), empty when the parameter is unconstrained. `kotlin.Any` is not a
 * bound for this purpose: it is what an unconstrained parameter's implicit `Any?` resolves to, and
 * `asStableRef<Any>()` is already the unconstrained decode.
 *
 * The first entry is the one a `T` is read back as (`asStableRef<First>()`); a multi-bound
 * parameter's value is then smart-cast to each of the rest. A generic bound goes first: the
 * `asStableRef` read is the one place its arguments cost no unchecked cast.
 */
internal fun KSTypeParameter.forwardBoundSpellings(): List<String> = bounds.toList()
  .map { bound -> bound.resolve() }
  .filter { resolved ->
    val name: String? = resolved.declaration.qualifiedName?.asString()
    name != null && name != "kotlin.Any"
  }
  .sortedBy { resolved -> resolved.arguments.isEmpty() }
  .map { resolved -> resolved.forwardKotlinBoundSpelling() }

/**
 * A bound as its declaration wrote it, for a declaration that re-states it: type parameters keep
 * their names (`kotlin.Comparable<T>`), projections and nullability are kept as written. Unlike
 * [forwardKotlinBoundSpelling], which erases them to spell a concrete type argument.
 */
internal fun KSType.forwardKotlinDeclaredSpelling(): String {
  val nullable: String = if (isMarkedNullable) "?" else ""
  val declaration: KSDeclaration = declaration
  if (declaration is KSTypeParameter) return declaration.name.asString() + nullable
  val name: String = declaration.qualifiedName?.asString() ?: declaration.simpleName.asString()
  if (arguments.isEmpty()) return name + nullable
  val spelled: String = arguments.joinToString(", ") { argument ->
    val argumentType: KSType? = argument.type?.resolve()
    when {
      argumentType == null || argument.variance == Variance.STAR -> "*"
      argument.variance == Variance.CONTRAVARIANT ->
        "in ${argumentType.forwardKotlinDeclaredSpelling()}"

      argument.variance == Variance.COVARIANT ->
        "out ${argumentType.forwardKotlinDeclaredSpelling()}"

      else -> argumentType.forwardKotlinDeclaredSpelling()
    }
  }
  return "$name<$spelled>$nullable"
}

/**
 * The Kotlin spelling of a type-parameter bound as a concrete type argument: its qualified name,
 * then its arguments with every type-parameter reference erased to `Any?`. The bare
 * `kotlin.Comparable` of `T : Comparable<T>` is not a type, so `Sorted<kotlin.Comparable>` and
 * `asStableRef<kotlin.Comparable>()` did not compile. `kotlin.Comparable<Any?>` satisfies that
 * F-bound, nullable or not, because `Comparable` is contravariant (`Comparable<Any?>` is a
 * `Comparable<Comparable<Any?>?>`). An invariant F-bound (`T : Enum<T>`) has no such spelling; it
 * still renders, and still fails to compile, as a known limit.
 */
internal fun KSType.forwardKotlinBoundSpelling(): String {
  val name: String = declaration.qualifiedName?.asString() ?: declaration.simpleName.asString()
  if (arguments.isEmpty()) return name
  val spelled: String = arguments.joinToString(", ") { argument ->
    val argumentType: KSType? = argument.type?.resolve()
    when {
      argumentType == null -> "*"
      argumentType.declaration is KSTypeParameter -> "Any?"
      else -> argumentType.forwardKotlinBoundSpelling() +
          if (argumentType.isMarkedNullable) "?" else ""
    }
  }
  return "$name<$spelled>"
}

/**
 * ADR-147: a generic owner as the Kotlin half spells it, or null for an ordinary class. Each
 * parameter erases to its bound (`io.pkg.Crate<Any?>`, `io.pkg.Kennel<io.pkg.Pet>`): `asStableRef`
 * takes a type argument, so the bare qualified name does not compile for a generic owner, and a
 * star projection would type every `T` parameter as `Nothing`.
 *
 * A multi-bound parameter (`where T : Comparable<T>, T : Pet`) has no erased argument: its first
 * bound is not within the others. It keeps its declared bounds instead, so the emitter can type a
 * receiver through a declaration that re-states them, and is star-projected everywhere else.
 */
internal fun KSClassDeclaration.forwardGenericOwner(): ForwardGenericOwner? {
  val captured: List<KSClassDeclaration> = capturedTypeParameterOwners()
  if (typeParameters.isEmpty() && captured.isEmpty()) return null
  val owner: String = qualifiedName?.asString() ?: return null
  return ForwardGenericOwner(
    qualifiedName = owner,
    typeParameters = forwardGenericOwnerParameters(),
    capturedEnclosing = if (captured.isEmpty()) null else capturedEnclosingSpelling(captured),
  )
}

/**
 * ADR-196: the enclosing chain of an `inner class` that captures [captured], with the erased
 * arguments on every captured segment (`pkg.Tin<Any?>`, so the class reads `pkg.Tin<Any?>.Latch`).
 * A non-inner segment above the captured run takes none: Kotlin rejects them there ("type
 * arguments for outer class are redundant"). A multi-bound captured parameter would star-project
 * here, but `NugetProcessor` refuses that shape before it is planned.
 */
private fun KSClassDeclaration.capturedEnclosingSpelling(
  captured: List<KSClassDeclaration>,
): String {
  val chain: List<KSClassDeclaration> =
    generateSequence(parentDeclaration as? KSClassDeclaration) {
      it.parentDeclaration as? KSClassDeclaration
    }.toList().asReversed()
  val packagePrefix: String = packageName.asString().let { if (it.isEmpty()) "" else "$it." }
  return packagePrefix + chain.joinToString(".") { segment ->
    val name: String = segment.simpleName.asString()
    if (segment in captured) {
      val arguments: String = segment.forwardGenericOwnerParameters()
        .joinToString(", ") { it.erased ?: "*" }
      "$name<$arguments>"
    } else {
      name
    }
  }
}

/**
 * ADR-196: the captured owner and the name of the first multi-bound parameter (`where T :
 * Comparable<T>, T : Pet`) this `inner class` captures, or null. Such a parameter has no erased
 * argument, so the inner class could only be read back star-projected (`Arena<*>.Lane`), where
 * Kotlin forbids every member that takes a `T` ("star projection prohibits the use of").
 */
internal fun KSClassDeclaration.capturedMultiBoundTypeParameter(): Pair<KSClassDeclaration, String>? =
  capturedTypeParameterOwners().firstNotNullOfOrNull { owner ->
    owner.forwardGenericOwnerParameters()
      .firstOrNull { parameter -> parameter.erased == null }
      ?.let { parameter -> owner to parameter.kotlinName }
  }

private fun KSClassDeclaration.forwardGenericOwnerParameters(): List<ForwardGenericOwnerParameter> =
  typeParameters.map { parameter ->
    val bounds: List<String> = parameter.forwardBoundSpellings()
    val nullable: Boolean = parameter.hasNullableBound()
    val multiBound: Boolean = bounds.size > 1
    ForwardGenericOwnerParameter(
      kotlinName = parameter.name.asString(),
      // ADR-147 amendment: the erased argument carries the bound's nullability. An unconstrained
      // parameter erases to `Any?`, so a bare `T` member substitutes to `Any?` and takes the
      // nullable lowering and result body the classifier gives it; a `T : Any` erases to `Any`
      // and keeps the non-null `retain`.
      erased = if (multiBound) {
        null
      } else {
        (bounds.firstOrNull() ?: "Any") + if (nullable) "?" else ""
      },
      bounds = if (multiBound) {
        parameter.bounds.map { bound -> bound.resolve().forwardKotlinDeclaredSpelling() }.toList()
      } else {
        emptyList()
      },
      nullableBound = nullable,
    )
  }

/**
 * ADR-196: the generic enclosing classes whose type parameters this `inner class` captures,
 * outermost first. The walk climbs while the current declaration is `inner`, so a non-inner link
 * ends it (`Tin<T> { class Shelf { inner class Hinge } }` captures nothing). Empty for every
 * non-inner class and for an inner class of non-generic owners only.
 */
internal fun KSClassDeclaration.capturedTypeParameterOwners(): List<KSClassDeclaration> {
  val owners: MutableList<KSClassDeclaration> = mutableListOf()
  var current: KSClassDeclaration = this
  while (Modifier.INNER in current.modifiers) {
    val outer: KSClassDeclaration = current.parentDeclaration as? KSClassDeclaration ?: break
    if (outer.typeParameters.isNotEmpty()) owners += outer
    current = outer
  }
  return owners.asReversed()
}

/**
 * ADR-196: every type parameter the C# declaration of this class declares, with the Kotlin class
 * that declares each one: the captured ones first, outermost owner first, then its own. Equal to
 * its own `typeParameters` for every class that captures nothing.
 */
internal fun KSClassDeclaration.forwardTypeParametersInScope():
  List<Pair<KSClassDeclaration, KSTypeParameter>> =
  capturedTypeParameterOwners().flatMap { owner -> owner.typeParameters.map { owner to it } } +
      typeParameters.map { this to it }

/**
 * ADR-196: the captured owner and the name of the first of this class's own type parameters that
 * reuses a captured one's name, or null. Kotlin allows the shadowing (`inner class Echo<T>` in
 * `Tin<T>`); the flattened C# `Tin.Echo<T, T>` is CS0692.
 */
internal fun KSClassDeclaration.shadowedCapturedTypeParameter(): Pair<KSClassDeclaration, String>? {
  val captured: List<KSClassDeclaration> = capturedTypeParameterOwners()
  typeParameters.forEach { own ->
    val name: String = own.simpleName.asString()
    captured.firstOrNull { owner -> owner.typeParameters.any { it.simpleName.asString() == name } }
      ?.let { owner -> return owner to name }
  }
  return null
}

/** The receiver spelling of [forwardGenericOwner], or null for an ordinary class. */
internal fun KSClassDeclaration.forwardOwnerTypeName(): String? = forwardGenericOwner()?.spelling

/**
 * ADR-147 amendment: Kotlin's rule for whether a bare `T` may hold null -- every upper bound is
 * nullable, an unconstrained parameter (implicit `Any?`, or no reported bound at all) included.
 * `T : Any` and `T : Pet` are non-null; `T : Pet?` is nullable.
 */
internal fun KSTypeParameter.hasNullableBound(): Boolean =
  bounds.all { bound -> bound.resolve().isMarkedNullable }

/**
 * The C# spelling of one of this class's type parameters. Its own name, unless a member of the
 * class renders the same PascalCase identifier (`class Duo<A, B>(val a: A)` renders property `A`),
 * which C# refuses (CS0102). The member names are the consumer-facing API, so the type parameter
 * gives way: `A` becomes `TA`, prefixed again until it clashes with nothing. Over-inclusive on
 * purpose (every property and function, inherited ones too): a spurious rename is harmless, a
 * missed one breaks the build.
 */
internal fun KSClassDeclaration.forwardCsharpTypeParameterName(parameter: KSTypeParameter): String {
  val name: String = parameter.simpleName.asString()
  return forwardCsharpTypeParameterNames()[name] ?: name
}

/** Every type parameter's C# spelling, keyed by its Kotlin name, assigned in declaration order so
 *  two renamed parameters can never land on the same spelling. */
private fun KSClassDeclaration.forwardCsharpTypeParameterNames(): Map<String, String> {
  val names: List<String> = typeParameters.map { it.simpleName.asString() }
  val members: Set<String> = buildSet {
    getAllProperties().forEach { property ->
      add(property.csharpMemberName())
    }
    getAllFunctions().forEach { function ->
      add(function.csharpMemberName())
    }
    declarations.filterIsInstance<KSClassDeclaration>().forEach { nested ->
      add(nested.simpleName.asString())
    }
    add(simpleName.asString())
  }
  if (names.none { name -> name in members }) return names.associateWith { it }
  val taken: MutableSet<String> = (members + names).toMutableSet()
  return names.associateWith { name ->
    if (name !in members) return@associateWith name
    var candidate: String = "T$name"
    while (candidate in taken) candidate = "T$candidate"
    taken.add(candidate)
    candidate
  }
}

/**
 * ADR-151: a `kotlin.*`/`kotlinx.*` type with no first-class mapping, refused as plainly
 * unsupported rather than as an out-of-scope dependency. Nothing third-party is in the signature
 * and no `include(...)` can repair it, so `SKIPPED_UNEXPORTED_DEPENDENCY_TYPE` named the wrong
 * defect; the stdlib sentence it used to carry now rides
 * [io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardPlanSkipReason.UNSUPPORTED]'s
 * hint instead.
 */
private fun unmappedStdlibType(qualifiedName: String): BridgeType.Unsupported =
  BridgeType.Unsupported(
    rendered = qualifiedName,
    reason = "a Kotlin stdlib type with no first-class C# mapping yet",
  )
