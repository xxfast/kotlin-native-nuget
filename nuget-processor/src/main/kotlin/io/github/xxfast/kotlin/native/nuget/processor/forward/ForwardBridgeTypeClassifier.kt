package io.github.xxfast.kotlin.native.nuget.processor.forward

import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeArgument
import com.google.devtools.ksp.symbol.KSTypeParameter
import com.google.devtools.ksp.symbol.Modifier
import io.github.xxfast.kotlin.native.nuget.processor.cir.FLOW_TYPES
import io.github.xxfast.kotlin.native.nuget.processor.cir.LAMBDA_TYPES
import io.github.xxfast.kotlin.native.nuget.processor.cir.STATE_FLOW_TYPES
import io.github.xxfast.kotlin.native.nuget.processor.cir.SUSPEND_LAMBDA_TYPES
import io.github.xxfast.kotlin.native.nuget.processor.cir.expandAliases
import io.github.xxfast.kotlin.native.nuget.processor.cir.mapPackageToNamespace
import io.github.xxfast.kotlin.native.nuget.processor.cir.nestedCsName

/** The declarations whose StableRef handles are part of this forward export set. */
internal data class ForwardBridgeTypeContext(
  val exportedObjectHandles: Set<String>,
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

  fun classify(type: KSType): BridgeType {
    val expanded: KSType = type.expandAliases()
    val classified: BridgeType = classifyNonNullable(expanded)
    // `KSTypeAlias.type.resolve()` describes the alias target and can lose a `?` applied at the
    // alias use site, so retain nullability from both the original use and the expanded target.
    val isNullable: Boolean = type.isMarkedNullable || expanded.isMarkedNullable
    return if (isNullable) BridgeType.Nullable(classified) else classified
  }

  private fun classifyNonNullable(type: KSType): BridgeType {
    val declaration = type.declaration
    if (declaration is KSTypeParameter) {
      return BridgeType.Unsupported(
        declaration.simpleName.asString(),
        "type parameters require the named generic legacy route",
      )
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
    // ADR-107: kotlin.Throwable and every stdlib subtype of it (Exception, IllegalStateException,
    // ...). Supertype-aware, because the declared property type is usually a subtype; ahead of the
    // exportedObjectHandles membership test below, so a stdlib throwable stops being an
    // "unexported dependency". A user exception class that IS in the export set keeps its
    // ObjectHandle binding (decision 3), which is why the membership test guards this line.
    if (qualifiedName !in context.exportedObjectHandles && classDeclaration.isStdlibThrowable()) {
      return BridgeType.Throwable
    }
    specializedProtocol(qualifiedName)?.let { return it }
    collectionType(qualifiedName, type.arguments)?.let { return it }

    if (classDeclaration.classKind == ClassKind.ENUM_CLASS) {
      // The membership gate the enum branch never had. `exportedObjectHandles` holds exactly the
      // enums the renderer declares (NugetProcessor's `enums` list feeds both), so an enum outside
      // it has no C# declaration and spelling it — which [csharpTypeNameFor] happily does, nesting
      // and all — leaves a dangling reference the consumer cannot compile (CS0426/CS0234). Skip
      // named instead, exactly as the class branch below does for an unexported handle.
      if (qualifiedName !in context.exportedObjectHandles) {
        // A nested enum is never declarable, in either module: `rootEnums` filters
        // `parentDeclaration == null` and the reachability closure refuses to admit a nested
        // dependency enum for the same reason. The `include(...)` hint would therefore be actively
        // wrong for it, so the nested test runs FIRST and only a *top-level* cross-module enum
        // (ADR-066's `containingFile == null` signal, as used by the class branch) takes the
        // scope-widening route.
        val isNested: Boolean = classDeclaration.parentDeclaration != null
        if (!isNested && classDeclaration.containingFile == null) {
          return BridgeType.Unsupported(
            qualifiedName,
            "declared in a dependency module whose package is outside the export scope",
            isUnexportedDependency = true,
          )
        }
        return BridgeType.Unsupported(
          qualifiedName,
          if (isNested) {
            "a nested enum class is never declared as a C# enum"
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
      // Kind-aware it must be, though, which ADR-105 does not say: `rootSealedClasses`
      // (NugetProcessor.kt:409) filters `classKind == CLASS`, so a sealed *interface* never
      // reaches the ADR-009 renderer and has no `FromHandle` discriminator to reconstruct
      // through; and this branch fires ahead of `objectHandle()`'s `exportedObjectHandles`
      // membership test (:150), so an out-of-scope sealed class would otherwise be handed a C#
      // spelling nothing generates. Both keep the bare protocol and skip exactly as before.
      val discriminated: Boolean = classDeclaration.classKind == ClassKind.CLASS &&
          qualifiedName in context.exportedObjectHandles
      return BridgeType.SpecializedProtocol(
        "sealed helper $qualifiedName",
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
    if (classDeclaration.typeParameters.isNotEmpty()) {
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
      // ADR-066: a declaration read straight off a klib dependency (never seen by
      // `resolver.getAllFiles()`) carries no containing file — verified in the ADR's spike. A
      // module-local declaration that simply fell outside the ADR-063 package filter still keeps
      // the old, generic message; only the cross-module case gets the closure's own diagnostic.
      val isUnexportedDependency: Boolean = classDeclaration.containingFile == null
      return BridgeType.Unsupported(
        qualifiedName,
        if (isUnexportedDependency) {
          "declared in a dependency module whose package is outside the export scope"
        } else {
          "declaration is not in the exported object-handle set"
        },
        isUnexportedDependency = isUnexportedDependency,
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
   * ADR-040: an interface at an ordinary (non ADR-039 add/remove-pair) position. Mirrors the
   * [ObjectHandle] membership check exactly, but the public spelling is `I$simpleName` (the
   * projected interface) and the construction spelling is `$simpleName` (the generated backing
   * wrapper class) — both qualified together under the same unconditional rule
   * [csharpTypeNameFor] applies to a class/object handle (issue #41).
   */
  private fun interfaceType(declaration: KSClassDeclaration, qualifiedName: String): BridgeType {
    if (qualifiedName !in context.exportedObjectHandles) {
      val isUnexportedDependency: Boolean = declaration.containingFile == null
      return BridgeType.Unsupported(
        qualifiedName,
        if (isUnexportedDependency) {
          "declared in a dependency module whose package is outside the export scope"
        } else {
          "declaration is not in the exported object-handle set"
        },
        isUnexportedDependency = isUnexportedDependency,
      )
    }
    val simpleName: String = declaration.simpleName.asString()
    if (context.rootNamespace.isEmpty()) {
      return BridgeType.Interface(qualifiedName, csharpType = "I$simpleName", backingType = simpleName)
    }
    val namespace: String = mapPackageToNamespace(
      declaration.packageName.asString(),
      context.rootPackage,
      context.rootNamespace,
    )
    return BridgeType.Interface(
      qualifiedName,
      csharpType = "global::$namespace.I$simpleName",
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
