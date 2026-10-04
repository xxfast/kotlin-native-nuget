package io.github.xxfast.kotlin.native.nuget.processor

import com.google.devtools.ksp.symbol.KSNode
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.TypeName
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirDllImport
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirClass
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirDeclaration
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirFile
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirObject
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirSealedClass
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirStaticClass
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirEnum
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirValueClass
import io.github.xxfast.kotlin.native.nuget.processor.cir.backing
import io.github.xxfast.kotlin.native.nuget.processor.cir.inheritedNativeImports
import io.github.xxfast.kotlin.native.nuget.processor.cir.nativeImports
import io.github.xxfast.kotlin.native.nuget.processor.cir.ordinaryNativeImports
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardAbiDirection as PlanAbiDirection
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardAbiWireType
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardCallablePlan
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardCallablePlanCatalog
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardExportOwner
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardExportOwners
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardNativeCall
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardPropertyPlan

internal enum class ForwardAbiType {
  VOID,
  BOOL,
  BYTE,
  SHORT,
  INT,
  LONG,
  FLOAT,
  DOUBLE,
  POINTER,
  STRING,
}

internal enum class ForwardAbiDirection { IN, OUT }

internal data class ForwardAbiSignatureParameter(
  val type: ForwardAbiType,
  val direction: ForwardAbiDirection = ForwardAbiDirection.IN,
)

internal data class ForwardAbiSignature(
  val exportName: String,
  val result: ForwardAbiType,
  val parameters: List<ForwardAbiSignatureParameter>,
) {
  override fun toString(): String {
    val params: String = parameters.joinToString(", ") { parameter ->
      "${parameter.direction.name.lowercase()} ${parameter.type.name.lowercase()}"
    }
    return "$exportName(${params}) -> ${result.name.lowercase()}"
  }
}

/** ADR-117: which of the three duplicate-entry-point guards produced a [ForwardAbiCollision]. */
internal enum class ForwardAbiGuard(val phrase: String) {
  DUPLICATE_CSHARP_IMPORT("duplicate C# import for"),
  DUPLICATE_KOTLIN_EXPORT("duplicate Kotlin export for"),
  CONFLICTING_LEGACY_IMPORTS("conflicting C# legacy imports for"),
}

/**
 * ADR-117: one C entry point claimed by more than one Kotlin declaration. Returned rather than
 * thrown, so `NugetProcessor` can raise it as `ERROR_C_ENTRY_POINT_COLLISION` pointing at the
 * author's own source. The remaining contract guards (missing, mismatch, and every
 * `assertMatchesPlan` check) stay generator-bug `require`s: those are never user-reachable.
 */
internal data class ForwardAbiCollision(
  val exportName: String,
  val guard: ForwardAbiGuard,
  val owners: List<ForwardExportOwner>,
  val signatures: List<ForwardAbiSignature>,
) {
  /** The first owner, which the diagnostic renders its location and heading from. */
  val declaration: String get() = owners.firstOrNull()?.text ?: exportName

  val symbol: KSNode? get() = owners.firstOrNull()?.node

  val reason: String
    get() = "Forward ABI ${guard.phrase} $exportName; ${owners.size} Kotlin declarations export " +
        "the same C entry point:\n" +
        owners.joinToString("\n") { owner -> "  - ${owner.render()}" }

  val hint: String
    get() = "The C entry point is the library name, the declaration's package relative to " +
        "`nuget.rootPackage`, and its enclosing chain of simple names (ADR-163), so two " +
        "same-named declarations in different packages no longer collide. What remains is a " +
        "collision inside ONE package and owner: a member whose name matches a generated role " +
        "(`fun dispose()` against the generated `Dispose`), a Kotlin `_` that reads as this " +
        "scheme's own separator, or two routes claiming one member; rename one declaration. " +
        "[${signatures.joinToString(", ")}]"

  fun message(): String = "$reason\n$hint"
}

/**
 * ADR-117: [ForwardAbiContract.csharpLegacy]'s result. Still a `List<ForwardAbiSignature>` to every
 * existing caller, plus the collisions its own duplicate guard found.
 */
internal class ForwardAbiLegacyContracts(
  val signatures: List<ForwardAbiSignature>,
  val collisions: List<ForwardAbiCollision>,
) : List<ForwardAbiSignature> by signatures

internal fun List<ForwardAbiSignature>.canonicalText(): String =
  sortedWith(
    compareBy(
      { signature -> signature.exportName },
      { signature -> signature.toString() },
    ),
  ).joinToString("\n")

/**
 * A generation-time assertion for C# native declarations represented by [CirDllImport], including
 * ordinary class and value-class imports normalized through their shared computed factories.
 * Specialized helper protocols remain on explicit legacy routes until their migration phase.
 */
internal object ForwardAbiContract {
  private const val ENTRY_POINT_MARKER: String = "EntryPoint = \""
  private const val EXTERN_MARKER: String = "static extern "
  private const val BOOL_RETURN_MARSHAL: String = "[return: MarshalAs(UnmanagedType.I1)]"
  private const val BOOL_PARAMETER_MARSHAL: String = "[MarshalAs(UnmanagedType.I1)] "

  /**
   * ADR-117: returns the duplicate-entry-point collisions (a user-reachable authoring mistake) for
   * the caller to raise as a named diagnostic; every other disagreement stays a `require`, because
   * it can only be a generator bug. [owners] names the Kotlin declarations behind an entry point
   * and is deliberately a separate parameter: [ForwardAbiSignature] compares with `==` below, so
   * an owner field on it would break the mismatch check.
   *
   * The [kotlin] side of the multiplicity check below is also **the** detector for two
   * byte-identical legacy imports of one entry point, the shape a cross-package namesake on a
   * legacy route mints. [csharpLegacy] collapses those with `.distinct()`, so they never reach
   * `CONFLICTING_LEGACY_IMPORTS`; the Kotlin half does not collapse, because one `@CName` is minted
   * per declaration, so `actual.size > 1` fires `DUPLICATE_KOTLIN_EXPORT` naming both owners. Run,
   * not inferred: the sealed `loadstate_get_type` cell in `Tier1EntryPointCollisionTest` reaches
   * exactly that guard, and `ForwardAbiContractTest` chains the two halves in one unit test.
   */
  fun assertMatches(
    csharp: List<ForwardAbiSignature>,
    kotlin: List<ForwardAbiSignature>,
    owners: ForwardExportOwners = ForwardExportOwners.EMPTY,
  ): List<ForwardAbiCollision> {
    val csharpByName: Map<String, List<ForwardAbiSignature>> = csharp.groupBy { it.exportName }
    val kotlinByName: Map<String, List<ForwardAbiSignature>> = kotlin.groupBy { it.exportName }
    // ADR-127 (+ADR-129): the 67 fixed names are exported by the `nuget-runtime` klib, which the
    // plugin adds as `api` and `export()`s, so the C# side imports them and the generated Kotlin
    // does not declare them. Their presence in the linked binary is checked by
    // `scripts/verify-runtime-exports.sh`, against the runtime source, not here. What this check
    // keeps is the inverse: a regenerated copy of one would collide with the runtime's at link
    // time, so a generated export under a runtime name is a hard failure.
    NUGET_RUNTIME_EXPORTS.forEach { name ->
      require(name !in kotlinByName) {
        "Forward ABI regenerated the runtime export $name; it belongs to nuget-runtime only"
      }
    }

    val names: List<String> = (csharpByName.keys + kotlinByName.keys)
      .filterNot { it in NUGET_RUNTIME_EXPORTS }
      .sorted()
    val collisions: MutableList<ForwardAbiCollision> = mutableListOf()

    names.forEach { name ->
      val expected: List<ForwardAbiSignature> = csharpByName[name].orEmpty()
      val actual: List<ForwardAbiSignature> = kotlinByName[name].orEmpty()
      // One collision per entry point: a cross-package namesake duplicates both halves, and the
      // `single()` calls below would throw before the caller ever sees the diagnostic.
      if (expected.size > 1 || actual.size > 1) {
        val duplicatedCsharp: Boolean = expected.size > 1
        collisions += ForwardAbiCollision(
          exportName = name,
          guard = if (duplicatedCsharp) ForwardAbiGuard.DUPLICATE_CSHARP_IMPORT
          else ForwardAbiGuard.DUPLICATE_KOTLIN_EXPORT,
          owners = owners.owners(name),
          signatures = if (duplicatedCsharp) expected else actual,
        )
        return@forEach
      }
      require(expected.isNotEmpty()) {
        "Forward ABI missing C# import for $name; actual ${actual.single()}"
      }
      require(actual.isNotEmpty()) {
        "Forward ABI missing Kotlin export for $name; expected ${expected.single()}"
      }
      require(expected.single() == actual.single()) {
        "Forward ABI mismatch for $name; expected ${expected.single()}, actual ${actual.single()}"
      }
    }

    return collisions
  }

  fun csharp(file: CirFile): List<ForwardAbiSignature> {
    val declarations: List<CirDeclaration> = file.namespaces.flatMap { it.declarations }
    val signatures: List<ForwardAbiSignature> = csharpImports(declarations)
    // A backing wrapper's override of a member an abstract base above its owner leaves open calls
    // that base's export again. It must import it exactly as the base's own wrapper does.
    declarations.filterIsInstance<CirClass>()
      .flatMap { cls -> cls.backing()?.inheritedNativeImports().orEmpty() }
      .mapNotNull { import -> import.toSignature() }
      .forEach { inherited ->
        require(inherited in signatures) {
          "Forward ABI inherited backing import $inherited matches no import of its base; " +
              "imports of ${inherited.exportName}: " +
              signatures.filter { it.exportName == inherited.exportName }
        }
      }
    return signatures
  }

  private fun csharpImports(declarations: List<CirDeclaration>): List<ForwardAbiSignature> =
    declarations.flatMap { declaration ->
      when (declaration) {
        is CirStaticClass -> declaration.members.filterIsInstance<CirDllImport>()
        is CirObject -> declaration.methods.filterIsInstance<CirDllImport>()
        is CirClass -> declaration.ordinaryNativeImports() +
            declaration.companionMembers.filterIsInstance<CirDllImport>() +
            declaration.backing()?.nativeImports().orEmpty()

        is CirValueClass -> declaration.ordinaryNativeImports()
        // ADR-006 amendment: a top-level enum's member-property imports are projected plan nodes.
        // A nested enum's render at namespace level (ADR-133) and are read by `csharpLegacy`.
        // ADR-094 (write side): its box import is a plan node too, rendered in `NugetMarshal`.
        is CirEnum -> declaration.extensionMembers.filterIsInstance<CirDllImport>() +
            listOfNotNull(declaration.boxImport)
        // ADR-078 amendment (2026-09-11): a sealed arm's plan-derived imports are nodes like any
        // ordinary class's, so they are read here rather than scraped by `csharpLegacy`.
        is CirSealedClass -> declaration.ordinaryNativeImports() +
            declaration.subclasses.flatMap { subclass ->
              subclass.ordinaryNativeImports(declaration.libraryName) +
                  subclass.backing(declaration)?.nativeImports().orEmpty()
            }

        else -> emptyList()
      }
    }
    .mapNotNull { import -> import.toSignature() }

  /**
   * ADR-078: the specialized legacy protocols print their `DllImport` declarations as raw renderer
   * text, so there is no [CirDllImport] node to normalize. Collect them from the rendered C# that
   * actually ships instead, which makes a renderer edit visible to the contract check by
   * construction. [ordinaryNames] drops the entry points the structural [csharp] collector already
   * covers, so a route migrating to a plan moves between the two universes automatically.
   *
   * The `.distinct()` below stays, and is not a gap. A runtime helper import renders twice by
   * design: `cir/CirFunctionRenderer.kt` prints `nuget_dispose` and the `nuget_wrap_*` family once
   * for the `Func` helper and again for the `SuspendFunc` helper, so any file with both would trip
   * `CONFLICTING_LEGACY_IMPORTS` on imports that do not conflict. Collapsing them costs nothing,
   * because the shape it hides (two byte-identical legacy imports of one entry point, from a
   * cross-package namesake) is caught by the Kotlin-side multiplicity check in [assertMatches] as
   * `DUPLICATE_KOTLIN_EXPORT`. What survives `.distinct()` here, and is reported, is the case that
   * check cannot see: two legacy imports of one entry point whose *signatures differ*.
   */
  fun csharpLegacy(
    renderedCsharp: String,
    ordinaryNames: Set<String>,
    owners: ForwardExportOwners = ForwardExportOwners.EMPTY,
  ): ForwardAbiLegacyContracts {
    val lines: List<String> = renderedCsharp.lines()
    val unmarshalledBools: MutableList<String> = mutableListOf()
    val collected: List<ForwardAbiSignature> = lines.mapIndexedNotNull { index, line ->
      val name: String = line.entryPointName() ?: return@mapIndexedNotNull null
      // The attribute and its declaration are adjacent apart from further attribute lines a
      // marshalled return adds (`[return: MarshalAs(UnmanagedType.I1)]`). Anything else means the
      // renderer's format moved, which must fail loudly rather than silently shrink coverage.
      val following: List<String> = lines.drop(index + 1)
        .map { candidate -> candidate.trim() }
        .filter { candidate -> candidate.isNotEmpty() }
      val attributes: List<String> = following.takeWhile { candidate -> candidate.startsWith("[") }
      val declaration: String = following.getOrNull(attributes.size).orEmpty()
      check(declaration.contains(EXTERN_MARKER)) {
        "Forward ABI legacy import for $name has no extern declaration; found \"$declaration\""
      }
      // Read off the shipped text before the ordinary filter, so every renderer is covered.
      unmarshalledBools += unmarshalledBoolSlots(name, attributes, declaration)
      if (name in ordinaryNames) null else externSignature(name, declaration)
    }
    require(unmarshalledBools.isEmpty()) {
      "Forward ABI bool without [MarshalAs(UnmanagedType.I1)] on a C# import (ADR-055): a " +
          "DllImport `bool` defaults to the 4-byte Win32 BOOL, while every Kotlin export speaks " +
          "a 1-byte C bool. Unmarshalled slots:\n" +
          unmarshalledBools.joinToString("\n") { slot -> "  - $slot" }
    }

    val distinct: List<ForwardAbiSignature> = collected.distinct()
    // ADR-117: two legacy imports of one entry point whose signatures differ is the same
    // user-reachable collision the two guards above catch, so it is reported the same way.
    val collisions: List<ForwardAbiCollision> = distinct
      .groupBy { signature -> signature.exportName }
      .filterValues { signatures -> signatures.size > 1 }
      .map { (name, signatures) ->
        ForwardAbiCollision(
          exportName = name,
          guard = ForwardAbiGuard.CONFLICTING_LEGACY_IMPORTS,
          owners = owners.owners(name),
          signatures = signatures,
        )
      }
    return ForwardAbiLegacyContracts(distinct, collisions)
  }

  fun kotlin(file: FileSpec, expectedNames: Set<String>): List<ForwardAbiSignature> = file.members
    .filterIsInstance<FunSpec>()
    .mapNotNull { function -> function.toSignature() }
    .filter { signature -> signature.exportName in expectedNames }

  /**
   * The first shadow-planning slice is intentionally checked against the existing independent
   * KotlinPoet and CIR projections. Keeping all three projections independent makes a stale
   * legacy emitter fail during KSP rather than silently drifting while renderers migrate later.
   */
  fun assertMatchesPlan(
    catalog: ForwardCallablePlanCatalog,
    csharp: List<ForwardAbiSignature>,
    kotlin: List<ForwardAbiSignature>,
  ) {
    val planned: List<ForwardAbiSignature> =
      catalog.plans.flatMap { plan -> plan.toSignatures() } +
          catalog.propertyPlans.flatMap { plan -> plan.toSignatures() }
    val names: Set<String> = planned.map { signature -> signature.exportName }.toSet()
    assertProjectionMatches("plan", planned, "C#", csharp.filter { it.exportName in names })
    assertProjectionMatches("plan", planned, "Kotlin", kotlin.filter { it.exportName in names })
  }

  private fun assertProjectionMatches(
    expectedLabel: String,
    expected: List<ForwardAbiSignature>,
    actualLabel: String,
    actual: List<ForwardAbiSignature>,
  ) {
    val expectedByName: Map<String, List<ForwardAbiSignature>> = expected.groupBy { it.exportName }
    val actualByName: Map<String, List<ForwardAbiSignature>> = actual.groupBy { it.exportName }
    val names: List<String> = (expectedByName.keys + actualByName.keys).sorted()
    names.forEach { name ->
      val expectedSignatures: List<ForwardAbiSignature> = expectedByName[name].orEmpty()
      val actualSignatures: List<ForwardAbiSignature> = actualByName[name].orEmpty()
      require(expectedSignatures.size <= 1) {
        "Forward ABI duplicate $expectedLabel signature for $name: $expectedSignatures"
      }
      require(actualSignatures.size <= 1) {
        "Forward ABI duplicate $actualLabel signature for $name: $actualSignatures"
      }
      require(expectedSignatures.isNotEmpty()) {
        "Forward ABI unexpected $actualLabel projection for $name: ${actualSignatures.single()}"
      }
      require(actualSignatures.isNotEmpty()) {
        "Forward ABI missing $actualLabel projection for $name; " +
            "expected ${expectedSignatures.single()}"
      }
      require(expectedSignatures.single() == actualSignatures.single()) {
        "Forward ABI plan mismatch for $name against $actualLabel; expected " +
            "${expectedSignatures.single()}, actual ${actualSignatures.single()}"
      }
    }
  }

  private fun ForwardCallablePlan.toSignatures(): List<ForwardAbiSignature> =
    nativeExports.toSignatures()

  private fun ForwardPropertyPlan.toSignatures(): List<ForwardAbiSignature> =
    calls().toSignatures()

  private fun List<ForwardNativeCall>.toSignatures(): List<ForwardAbiSignature> = map { call ->
    ForwardAbiSignature(
      exportName = call.exportName,
      result = call.result.toAbiType(),
      parameters = call.parameters.map { parameter ->
        ForwardAbiSignatureParameter(parameter.wireType.toAbiType(), parameter.direction.toAbiDirection())
      },
    )
  }

  private fun ForwardAbiWireType.toAbiType(): ForwardAbiType = when (this) {
    ForwardAbiWireType.VOID -> ForwardAbiType.VOID
    ForwardAbiWireType.BOOLEAN -> ForwardAbiType.BOOL
    ForwardAbiWireType.INT8, ForwardAbiWireType.UINT8 -> ForwardAbiType.BYTE
    ForwardAbiWireType.INT16,
    ForwardAbiWireType.UINT16,
    ForwardAbiWireType.CHAR16,
      -> ForwardAbiType.SHORT

    ForwardAbiWireType.INT32, ForwardAbiWireType.UINT32 -> ForwardAbiType.INT
    ForwardAbiWireType.INT64, ForwardAbiWireType.UINT64 -> ForwardAbiType.LONG
    ForwardAbiWireType.FLOAT32 -> ForwardAbiType.FLOAT
    ForwardAbiWireType.FLOAT64 -> ForwardAbiType.DOUBLE
    ForwardAbiWireType.STRING -> ForwardAbiType.STRING
    ForwardAbiWireType.POINTER -> ForwardAbiType.POINTER
    ForwardAbiWireType.UNKNOWN -> error("Forward plan contains an unknown ABI wire type")
  }

  private fun PlanAbiDirection.toAbiDirection(): ForwardAbiDirection = when (this) {
    PlanAbiDirection.IN -> ForwardAbiDirection.IN
    PlanAbiDirection.OUT -> ForwardAbiDirection.OUT
    PlanAbiDirection.IN_OUT ->
      error("Forward plan IN_OUT ABI direction has no legacy ForwardAbiContract projection")
  }

  private fun CirDllImport.toSignature(): ForwardAbiSignature? {
    // Cleaned as the rendered `EntryPoint` is (`NugetProcessor`), the identity otherwise.
    val name: String = entryPoint?.asCSymbol() ?: return null
    val parameters: MutableList<ForwardAbiSignatureParameter> = parameters.map { parameter ->
      // ADR-061's nullable-primitive out-parameter (`out int value`, etc.) is, at the C ABI
      // level, exactly the same shape as `out IntPtr error` below: a pointer to a memory slot the
      // callee writes through. Recognize any `out `-prefixed native type uniformly as (POINTER,
      // OUT) rather than trying to resolve its pointee width — mirroring the errorOut special
      // case's own philosophy — so it matches the Kotlin side's `COpaquePointer?` (also POINTER).
      // ADR-069: a Boolean out-parameter's DllImport declaration carries a leading
      // `[MarshalAs(UnmanagedType.I1)] ` attribute (the C# marshaller's default `out bool` read is
      // 4 bytes against Kotlin's 1-byte `BooleanVar` write); strip it before the `out `-prefix
      // check below, which recognizes the pointer shape by native-type text.
      val spelling: String = parameter.nativeType.substringAfterLast("] ").trim()
      requireMarshalable(name, spelling)
      if (spelling.startsWith("out ")) {
        ForwardAbiSignatureParameter(ForwardAbiType.POINTER, ForwardAbiDirection.OUT)
      } else {
        ForwardAbiSignatureParameter(csharpType(parameter.nativeType))
      }
    }.toMutableList()
    if (hasSyncErrorOut) {
      parameters.add(ForwardAbiSignatureParameter(ForwardAbiType.POINTER, ForwardAbiDirection.OUT))
    }
    requireMarshalable(name, returnType.trim())
    return ForwardAbiSignature(name, csharpReturnType(returnType), parameters)
  }

  /**
   * ADR-055 amendment: a `?` on a `DllImport` spelling is admitted only on a by-value or return
   * `string`. Any other one is a `Nullable<T>` (or an `out` pointee that is never ADR-061's pointer
   * slot) that C# compiles and the runtime refuses at call time, while the `?`-stripping
   * normalization below would read it as the plain scalar and let it match a non-nullable export.
   * Textually a `Color?` (a `Nullable<T>`) and a `Cat?` (a reference annotation) look alike, and
   * only `string?` is emitted, so the rule is a whitelist. No user-authored Kotlin reaches this,
   * so it is a generator-bug `require` (ADR-117), not a diagnostic.
   */
  private fun requireMarshalable(entryPoint: String, spelling: String) {
    require(!spelling.endsWith("?") || spelling == "string?") {
      "Forward ABI Nullable<T> on C# import for $entryPoint: \"$spelling\" is not " +
          "P/Invoke-marshalable; only a by-value or return `string?` may carry `?` on a " +
          "DllImport (ADR-055), any other `?` spelling compiles and then throws " +
          "MarshalDirectiveException at call time"
    }
  }

  /**
   * The Kotlin mirror of [requireMarshalable]: a nullable Kotlin scalar on a `@CName` export has no
   * C# spelling but a `Nullable<T>`, and the `?`-stripping in [kotlinType] would read it as the
   * plain scalar. `String?` and every pointer type stay admitted.
   */
  private fun requireKotlinScalar(exportName: String, type: TypeName) {
    val spelling: String = type.toString()
    require(!(type.isNullable && spelling.removeSuffix("?") in KOTLIN_SCALARS)) {
      "Forward ABI nullable scalar on Kotlin export for $exportName: \"$spelling\" has no C# " +
          "DllImport spelling but a Nullable<T>, which compiles and then throws " +
          "MarshalDirectiveException at call time (ADR-055)"
    }
  }

  private val KOTLIN_SCALARS: Set<String> = setOf(
    "kotlin.Unit",
    "kotlin.Boolean",
    "kotlin.Byte",
    "kotlin.UByte",
    "kotlin.Char",
    "kotlin.Short",
    "kotlin.UShort",
    "kotlin.Int",
    "kotlin.UInt",
    "kotlin.Long",
    "kotlin.ULong",
    "kotlin.Float",
    "kotlin.Double",
  )

  private fun FunSpec.toSignature(): ForwardAbiSignature? {
    val name: String = annotations.cNameValue() ?: return null
    requireKotlinScalar(name, returnType)
    parameters.forEach { parameter -> requireKotlinScalar(name, parameter.type) }
    return ForwardAbiSignature(
      exportName = name,
      result = kotlinReturnType(returnType),
      parameters = parameters.map { parameter ->
        // "valueOut" is ADR-061's nullable-primitive return out-parameter, the Kotlin-side
        // counterpart of the C# `out <T> value` recognized above.
        //
        // Reading a direction off a *name* is sound because both names are reserved to the
        // generator: `bridgeParameterName` in Reserved.kt shifts any user parameter spelled
        // `errorOut` / `valueOut` (or either followed by underscores) as it enters the plan, so an
        // `errorOut` reaching here is always the exception slot and a `valueOut` is always the
        // has-value out-slot. Without that shift this branch reads a user's `in int` as `out` and
        // the two projections of a structurally identical signature stop agreeing.
        //
        // ADR-062: the *plan* now carries a structural `ForwardAbiRole` instead of name-matching,
        // but this projection deliberately stays name-based. It reads a rendered KotlinPoet
        // `FunSpec`, where the plan's parameter (and its role) is already gone, and it also covers
        // the legacy `exports/*` FunSpecs that never had a plan and spell `errorOut` by hand.
        // The `Result<T>` failure flag (`RESULT_FAILED_SLOT`) is reserved the same way.
        val direction: ForwardAbiDirection = if (
          parameter.name == "errorOut" || parameter.name == "valueOut" ||
          parameter.name == RESULT_FAILED_SLOT
        ) {
          ForwardAbiDirection.OUT
        } else {
          ForwardAbiDirection.IN
        }
        ForwardAbiSignatureParameter(kotlinParameterType(parameter.type), direction)
      },
    )
  }

  private fun String.entryPointName(): String? {
    if (!contains(ENTRY_POINT_MARKER)) return null
    return substringAfter(ENTRY_POINT_MARKER).substringBefore("\"")
  }

  /**
   * ADR-055 amendment (2026-09-27): every `bool` a `DllImport` spells, at the return, by value, or
   * behind `out`/`ref`, must be marshalled as one byte. Returns one `entryPoint: slot` line per
   * violation so a single generation round names all of them.
   */
  private fun unmarshalledBoolSlots(
    entryPoint: String,
    attributes: List<String>,
    declaration: String,
  ): List<String> {
    val violations: MutableList<String> = mutableListOf()
    val header: String = declaration.substringAfter(EXTERN_MARKER).substringBefore("(").trim()
    val returnType: String = header.substringBeforeLast(" ").trim()
    if (returnType == "bool" && BOOL_RETURN_MARSHAL !in attributes) {
      violations += "$entryPoint: return bool"
    }
    declaration
      .substringAfter("(")
      .substringBeforeLast(")")
      .split(",")
      .map { parameter -> parameter.trim() }
      .filter { parameter -> parameter.isNotEmpty() }
      .forEach { parameter ->
        val spelling: String = parameter.substringAfterLast("] ").trim()
        val type: String = spelling.removePrefix("out ").removePrefix("ref ").substringBefore(" ")
        if (type == "bool" && !parameter.startsWith(BOOL_PARAMETER_MARSHAL)) {
          violations += "$entryPoint: $spelling"
        }
      }
    return violations
  }

  private fun externSignature(name: String, declaration: String): ForwardAbiSignature {
    val header: String = declaration.substringAfter(EXTERN_MARKER).substringBefore("(").trim()
    val parameters: List<ForwardAbiSignatureParameter> = declaration
      .substringAfter("(")
      .substringBeforeLast(")")
      .split(",")
      .map { parameter -> parameter.trim() }
      .filter { parameter -> parameter.isNotEmpty() }
      .map { parameter -> parameter.toAbiParameter(name) }
    val returnType: String = header.substringBeforeLast(" ")
    requireMarshalable(name, returnType)
    return ForwardAbiSignature(name, csharpReturnType(returnType), parameters)
  }

  // The same normalization the CirDllImport path applies: strip a leading `[MarshalAs(...)] `, read
  // any `out `-prefixed parameter as the (POINTER, OUT) slot it is at the C ABI, and fall through
  // to csharpType otherwise (an unknown token, such as a marshalled delegate, is a pointer).
  private fun String.toAbiParameter(entryPoint: String): ForwardAbiSignatureParameter {
    val declaration: String = substringAfterLast("] ").trim()
    requireMarshalable(entryPoint, declaration.substringBeforeLast(" ").trim())
    if (declaration.startsWith("out ")) {
      return ForwardAbiSignatureParameter(ForwardAbiType.POINTER, ForwardAbiDirection.OUT)
    }
    return ForwardAbiSignatureParameter(csharpType(declaration.substringBeforeLast(" ").trim()))
  }

  private fun List<AnnotationSpec>.cNameValue(): String? = firstOrNull { annotation ->
    annotation.typeName.toString() == "kotlin.native.CName"
  }?.members?.singleOrNull()?.toString()?.removeSurrounding("\"")

  private fun csharpType(type: String): ForwardAbiType = when (type.removeSuffix("?")) {
    "void" -> ForwardAbiType.VOID
    "bool" -> ForwardAbiType.BOOL
    "byte", "sbyte" -> ForwardAbiType.BYTE
    "short", "ushort", "char" -> ForwardAbiType.SHORT
    "int", "uint" -> ForwardAbiType.INT
    "long", "ulong" -> ForwardAbiType.LONG
    "float" -> ForwardAbiType.FLOAT
    "double" -> ForwardAbiType.DOUBLE
    "string" -> ForwardAbiType.STRING
    "IntPtr", "nint" -> ForwardAbiType.POINTER
    else -> ForwardAbiType.POINTER
  }

  // Kotlin/Native returns a Kotlin String through a native pointer. P/Invoke's `string` spelling
  // asks the runtime to marshal that pointer for the managed caller, so it is a string at an input
  // position but a pointer at the ABI result position.
  private fun csharpReturnType(type: String): ForwardAbiType = if (
    type.removeSuffix("?") == "string"
  ) {
    ForwardAbiType.POINTER
  } else {
    csharpType(type)
  }

  private fun kotlinType(type: TypeName): ForwardAbiType {
    val name: String = type.toString().removeSuffix("?")
    return when (name) {
      "kotlin.Unit" -> ForwardAbiType.VOID
      "kotlin.Boolean" -> ForwardAbiType.BOOL
      "kotlin.Byte", "kotlin.UByte" -> ForwardAbiType.BYTE
      // Char is CHAR16 on the wire (same ABI width as Short). Phase 8 admits Char results.
      "kotlin.Char", "kotlin.Short", "kotlin.UShort" -> ForwardAbiType.SHORT
      "kotlin.Int", "kotlin.UInt" -> ForwardAbiType.INT
      "kotlin.Long", "kotlin.ULong" -> ForwardAbiType.LONG
      "kotlin.Float" -> ForwardAbiType.FLOAT
      "kotlin.Double" -> ForwardAbiType.DOUBLE
      "kotlin.String" -> ForwardAbiType.STRING
      else -> ForwardAbiType.POINTER
    }
  }

  private fun kotlinReturnType(type: TypeName): ForwardAbiType = if (
    type.toString().removeSuffix("?") == "kotlin.String"
  ) {
    ForwardAbiType.POINTER
  } else {
    kotlinType(type)
  }

  private fun kotlinParameterType(type: TypeName): ForwardAbiType = kotlinType(type)
}

/**
 * ADR-127: the fixed `nuget_*` ABI, exported by the `nuget-runtime` klib rather than regenerated
 * into every consumer. Pinned here because the processor cannot read the runtime's source at
 * generation time; `scripts/verify-runtime-exports.sh` derives the same list from that source and
 * asserts every name is present in the linked binary, which is where the two are kept honest.
 */
internal val NUGET_RUNTIME_EXPORTS: Set<String> = setOf(
  // ADR-151: the byte-array wire, three names plus the shared nuget_dispose below.
  "nuget_bytes_copy",
  "nuget_bytes_count",
  "nuget_bytes_create",
  "nuget_csharp_token",
  "nuget_dispose",
  "nuget_error_cause_count",
  // ADR-177: the matched mapping row per error node.
  "nuget_error_cause_mapped_type",
  "nuget_error_cause_message",
  "nuget_error_cause_stacktrace",
  "nuget_error_cause_type",
  "nuget_error_message",
  "nuget_error_stacktrace",
  "nuget_error_type",
  "nuget_func0_invoke",
  "nuget_func1_invoke",
  "nuget_func2_invoke",
  "nuget_func3_invoke",
  "nuget_gc_collect",
  "nuget_job_cancel",
  "nuget_job_dispose",
  "nuget_list_add",
  "nuget_list_count",
  "nuget_list_create",
  "nuget_list_get",
  "nuget_live_handles",
  "nuget_map_count",
  "nuget_map_create",
  "nuget_map_key_at",
  "nuget_map_put",
  // ADR-161: the forward callback error channel's only new export. A C# thunk calls it from inside
  // its catch to mint the Kotlin-owned holder it stores in the trailing `IntPtr* errOut` slot.
  "nuget_managed_error_create",
  "nuget_map_value_at",
  "nuget_runtime_version",
  "nuget_scope_cancel",
  "nuget_scope_create",
  "nuget_scope_dispose",
  "nuget_scope_drain",
  "nuget_set_add",
  "nuget_set_count",
  "nuget_set_create",
  "nuget_set_element_at",
  "nuget_stateflow_collect",
  "nuget_stateflow_value",
  "nuget_suspend_func0_invoke",
  "nuget_suspend_func1_invoke",
  "nuget_suspend_func2_invoke",
  "nuget_suspend_func3_invoke",
  "nuget_unwrap_bool",
  "nuget_unwrap_byte",
  "nuget_unwrap_char",
  "nuget_unwrap_double",
  "nuget_unwrap_enum_ordinal",
  "nuget_unwrap_float",
  "nuget_unwrap_int",
  "nuget_unwrap_long",
  "nuget_unwrap_short",
  "nuget_unwrap_string",
  "nuget_unwrap_ubyte",
  "nuget_unwrap_uint",
  "nuget_unwrap_ulong",
  "nuget_unwrap_ushort",
  "nuget_wrap_bool",
  "nuget_wrap_byte",
  "nuget_wrap_char",
  "nuget_wrap_double",
  "nuget_wrap_float",
  "nuget_wrap_int",
  "nuget_wrap_long",
  "nuget_wrap_short",
  "nuget_wrap_string",
  "nuget_wrap_ubyte",
  "nuget_wrap_uint",
  "nuget_wrap_ulong",
  "nuget_wrap_ushort",
)
