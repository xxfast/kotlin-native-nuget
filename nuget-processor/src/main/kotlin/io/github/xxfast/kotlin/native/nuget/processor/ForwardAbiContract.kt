package io.github.xxfast.kotlin.native.nuget.processor

import com.google.devtools.ksp.symbol.KSNode
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.TypeName
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirDllImport
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirClass
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirFile
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirObject
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirSealedClass
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirStaticClass
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirValueClass
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
    get() = "The C entry point is derived from the unqualified simple name; rename one " +
        "declaration. [${signatures.joinToString(", ")}]"

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

  /**
   * ADR-117: returns the duplicate-entry-point collisions (a user-reachable authoring mistake) for
   * the caller to raise as a named diagnostic; every other disagreement stays a `require`, because
   * it can only be a generator bug. [owners] names the Kotlin declarations behind an entry point
   * and is deliberately a separate parameter: [ForwardAbiSignature] compares with `==` below, so
   * an owner field on it would break the mismatch check.
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

  fun csharp(file: CirFile): List<ForwardAbiSignature> = file.namespaces
    .flatMap { namespace -> namespace.declarations }
    .flatMap { declaration ->
      when (declaration) {
        is CirStaticClass -> declaration.members.filterIsInstance<CirDllImport>()
        is CirObject -> declaration.methods.filterIsInstance<CirDllImport>()
        is CirClass -> declaration.ordinaryNativeImports() +
            declaration.companionMembers.filterIsInstance<CirDllImport>()

        is CirValueClass -> declaration.ordinaryNativeImports()
        // ADR-078 amendment (2026-09-11): a sealed arm's plan-derived imports are nodes like any
        // ordinary class's, so they are read here rather than scraped by `csharpLegacy`.
        is CirSealedClass -> declaration.ordinaryNativeImports() +
            declaration.subclasses
              .flatMap { subclass -> subclass.ordinaryNativeImports(declaration.libraryName) }

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
   */
  fun csharpLegacy(
    renderedCsharp: String,
    ordinaryNames: Set<String>,
    owners: ForwardExportOwners = ForwardExportOwners.EMPTY,
  ): ForwardAbiLegacyContracts {
    val lines: List<String> = renderedCsharp.lines()
    val collected: List<ForwardAbiSignature> = lines.mapIndexedNotNull { index, line ->
      val name: String = line.entryPointName() ?: return@mapIndexedNotNull null
      // The attribute and its declaration are adjacent apart from further attribute lines a
      // marshalled return adds (`[return: MarshalAs(UnmanagedType.I1)]`). Anything else means the
      // renderer's format moved, which must fail loudly rather than silently shrink coverage.
      val declaration: String = lines.drop(index + 1)
        .map { candidate -> candidate.trim() }
        .firstOrNull { candidate -> candidate.isNotEmpty() && !candidate.startsWith("[") }
        .orEmpty()
      check(declaration.contains(EXTERN_MARKER)) {
        "Forward ABI legacy import for $name has no extern declaration; found \"$declaration\""
      }
      if (name in ordinaryNames) null else externSignature(name, declaration)
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
    val name: String = entryPoint ?: return null
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
      if (parameter.nativeType.substringAfterLast("] ").startsWith("out ")) {
        ForwardAbiSignatureParameter(ForwardAbiType.POINTER, ForwardAbiDirection.OUT)
      } else {
        ForwardAbiSignatureParameter(csharpType(parameter.nativeType))
      }
    }.toMutableList()
    if (hasSyncErrorOut) {
      parameters.add(ForwardAbiSignatureParameter(ForwardAbiType.POINTER, ForwardAbiDirection.OUT))
    }
    return ForwardAbiSignature(name, csharpReturnType(returnType), parameters)
  }

  private fun FunSpec.toSignature(): ForwardAbiSignature? {
    val name: String = annotations.cNameValue() ?: return null
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
        val direction: ForwardAbiDirection = if (
          parameter.name == "errorOut" || parameter.name == "valueOut"
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

  private fun externSignature(name: String, declaration: String): ForwardAbiSignature {
    val header: String = declaration.substringAfter(EXTERN_MARKER).substringBefore("(").trim()
    val parameters: List<ForwardAbiSignatureParameter> = declaration
      .substringAfter("(")
      .substringBeforeLast(")")
      .split(",")
      .map { parameter -> parameter.trim() }
      .filter { parameter -> parameter.isNotEmpty() }
      .map { parameter -> parameter.toAbiParameter() }
    return ForwardAbiSignature(name, csharpReturnType(header.substringBeforeLast(" ")), parameters)
  }

  // The same normalization the CirDllImport path applies: strip a leading `[MarshalAs(...)] `, read
  // any `out `-prefixed parameter as the (POINTER, OUT) slot it is at the C ABI, and fall through
  // to csharpType otherwise (an unknown token, such as a marshalled delegate, is a pointer).
  private fun String.toAbiParameter(): ForwardAbiSignatureParameter {
    val declaration: String = substringAfterLast("] ").trim()
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
  "nuget_csharp_token",
  "nuget_dispose",
  "nuget_error_cause_count",
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
