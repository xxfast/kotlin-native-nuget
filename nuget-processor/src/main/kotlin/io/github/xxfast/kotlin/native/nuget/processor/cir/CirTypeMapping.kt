package io.github.xxfast.kotlin.native.nuget.processor.cir

import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeAlias
import com.google.devtools.ksp.symbol.KSTypeArgument
import com.google.devtools.ksp.symbol.KSTypeParameter
import io.github.xxfast.kotlin.native.nuget.processor.forward.BridgeType
import io.github.xxfast.kotlin.native.nuget.processor.forward.CollectionKind
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardCallablePlan
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnostic
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardDiagnosticKind
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardPropertyPlan

internal fun KSType.expandAliases(): KSType {
  val decl = declaration
  return if (decl is KSTypeAlias) decl.type.resolve().expandAliases()
  else this
}

internal val KOTLIN_TO_CSHARP_RETURN = mapOf(
  "String" to "IntPtr",
  "Byte" to "sbyte",
  "UByte" to "byte",
  "Short" to "short",
  "UShort" to "ushort",
  "Int" to "int",
  "UInt" to "uint",
  "Long" to "long",
  "ULong" to "ulong",
  "Float" to "float",
  "Double" to "double",
  "Boolean" to "bool",
  "Unit" to "void",
)

internal val KOTLIN_TO_CSHARP_PARAM = mapOf(
  "String" to "string",
  "Char" to "char",
  "Byte" to "sbyte",
  "UByte" to "byte",
  "Short" to "short",
  "UShort" to "ushort",
  "Int" to "int",
  "UInt" to "uint",
  "Long" to "long",
  "ULong" to "ulong",
  "Float" to "float",
  "Double" to "double",
  "Boolean" to "bool",
)

internal val LAMBDA_TYPES = setOf(
  "kotlin.Function0", "kotlin.Function1", "kotlin.Function2", "kotlin.Function3",
)

internal val SUSPEND_LAMBDA_TYPES = setOf(
  "kotlin.coroutines.SuspendFunction0",
  "kotlin.coroutines.SuspendFunction1",
  "kotlin.coroutines.SuspendFunction2",
  "kotlin.coroutines.SuspendFunction3",
)

internal val FLOW_TYPES = setOf(
  "kotlinx.coroutines.flow.Flow",
)

// ADR-065: StateFlow<T> (and, as a read-only view, MutableStateFlow<T>) is a hot,
// always-current-value stream. It is-a Flow, so detection is on the DECLARED type's exact
// qualifiedName and is checked BEFORE FLOW_TYPES everywhere FLOW_TYPES is consulted -- never via
// isAssignableFrom, which would make a StateFlow match the plain-Flow branch and silently lose
// `.Value`. SharedFlow/MutableSharedFlow remain unlisted and deferred (ROADMAP line 108).
//
// ADR-071: split into MUTABLE_STATE_FLOW_TYPES / READ_ONLY_STATE_FLOW_TYPES so a genuinely
// DECLARED `MutableStateFlow<T>` (not narrowed through `.asStateFlow()`) can additionally gain a
// settable `.Value`. STATE_FLOW_TYPES stays their union so every existing call site (which only
// needs "is this a StateFlow-shaped member at all") is unchanged.
internal val MUTABLE_STATE_FLOW_TYPES = setOf("kotlinx.coroutines.flow.MutableStateFlow")
internal val READ_ONLY_STATE_FLOW_TYPES = setOf("kotlinx.coroutines.flow.StateFlow")
internal val STATE_FLOW_TYPES = READ_ONLY_STATE_FLOW_TYPES + MUTABLE_STATE_FLOW_TYPES

internal class CollectionHelperTracker {
  var needsList: Boolean = false
  var needsMap: Boolean = false
  var needsSet: Boolean = false
  var needsAsync: Boolean = false
  var needsFlow: Boolean = false
  var needsStateFlow: Boolean = false

  // ADR-071: at least one publicly-DECLARED MutableStateFlow<T> member/return needs the settable
  // `KotlinMutableStateFlow<T>` subclass emitted (implies needsStateFlow, which implies needsFlow).
  var needsMutableStateFlow: Boolean = false

  // ADR-068: at least one `suspend fun` returns StateFlow<T>/MutableStateFlow<T> -- gates the two
  // shared generic `nuget_stateflow_collect`/`nuget_stateflow_value` handle-keyed exports.
  var needsSuspendStateFlow: Boolean = false
  var needsSubscription: Boolean = false
  val lambdaArities: MutableSet<Int> = mutableSetOf()
  val suspendLambdaArities: MutableSet<Int> = mutableSetOf()
  val callbackDelegates: MutableList<CirCallbackDelegate> = mutableListOf()

  /** Marks List/Map/Set helper needs from a planned [BridgeType] (result, parameter, or property). */
  fun trackCollection(type: BridgeType) {
    val unwrapped: BridgeType = if (type is BridgeType.Nullable) type.type else type
    val collection = unwrapped as? BridgeType.Collection ?: return
    when (collection.kind) {
      CollectionKind.LIST, CollectionKind.MUTABLE_LIST -> needsList = true
      CollectionKind.MAP, CollectionKind.MUTABLE_MAP -> needsMap = true
      CollectionKind.SET, CollectionKind.MUTABLE_SET -> needsSet = true
    }
    // ADR-099: a nested component needs ITS kind's native class too -- `Set<List<String>>` is the
    // first declaration to need NugetSetNative and NugetListNative in the same file.
    collection.element?.let { trackCollection(it) }
    collection.key?.let { trackCollection(it) }
    collection.value?.let { trackCollection(it) }
  }

  fun trackPlan(plan: ForwardCallablePlan) {
    trackCollection(plan.publicSignature.result)
    plan.publicSignature.parameters.forEach { parameter -> trackCollection(parameter.type) }
  }

  fun trackProperty(plan: ForwardPropertyPlan) {
    trackCollection(plan.type)
  }
}

/**
 * ADR-071 v1 scope: a `MutableStateFlow<T>` element the settable-`.Value` write seam supports --
 * a primitive/`Char`/`String` (crosses by value, no conversion or one conversion) or an ordinary
 * class/object element (crosses as a handle). Enum elements are explicitly out of scope in both
 * directions (a pre-existing gap: `NugetMarshal.FromHandle<T>` has no enum branch) and everything
 * else (collections, lambdas, nullable) is deferred, so this deliberately returns `false` for them
 * -- the declared `MutableStateFlow<T>` keeps ADR-065/067's read-only `KotlinStateFlow<T>` mapping.
 */
internal fun isMutableStateFlowElementSupported(elementType: KSType?): Boolean {
  val declaration = elementType?.expandAliases()?.declaration ?: return false
  val simpleName: String = declaration.simpleName.asString()
  if (KOTLIN_TO_CSHARP_PARAM.containsKey(simpleName)) return true
  val classDeclaration: KSClassDeclaration = declaration as? KSClassDeclaration ?: return false
  if (classDeclaration.classKind == ClassKind.ENUM_CLASS) return false
  return classDeclaration.classKind == ClassKind.CLASS ||
      classDeclaration.classKind == ClassKind.OBJECT
}

/**
 * True when a (already-[isMutableStateFlowElementSupported]) element crosses the write seam as an
 * object handle rather than by value -- i.e. it is not one of the [KOTLIN_TO_CSHARP_PARAM]
 * primitive/`Char`/`String` scalars.
 */
internal fun isMutableStateFlowElementObject(elementType: KSType?): Boolean {
  val declaration = elementType?.expandAliases()?.declaration ?: return false
  val simpleName: String = declaration.simpleName.asString()
  return !KOTLIN_TO_CSHARP_PARAM.containsKey(simpleName)
}

internal fun mapReturnType(kotlinType: String): String =
  KOTLIN_TO_CSHARP_RETURN[kotlinType] ?: "IntPtr"

internal fun mapParamType(kotlinType: String): String =
  KOTLIN_TO_CSHARP_PARAM[kotlinType] ?: "IntPtr"

/**
 * The C# spelling of a declaration's *name within its namespace*, enclosing scope included:
 * `Circle` for a top-level class, `Shape.Circle` for a subclass declared inside its sealed base.
 *
 * ADR-009 declares a sealed subclass as a **nested** C# class (`CirSealedRenderer` emits
 * `public sealed class Circle : Shape` *inside* `public abstract class Shape`), so the
 * simple-name-only spelling `global::Namespace.Circle` names a type that does not exist and fails
 * the whole `Interop.cs` with CS0234/CS0246. Every member **type position** — property, method
 * return and its `new T(handle)` construction, parameter — must therefore walk the enclosing
 * declarations and join their simple names outermost-first.
 *
 * The walk stops at the first non-class parent, so a file-level declaration is unchanged and a
 * class local to a function contributes only its own name (it is never exported anyway).
 */
internal fun KSClassDeclaration.nestedCsName(): String =
  generateSequence<KSDeclaration>(this) { it.parentDeclaration }
    .takeWhile { it is KSClassDeclaration }
    .map { it.simpleName.asString() }
    .toList()
    .asReversed()
    .joinToString(".")

internal fun mapPackageToNamespace(
  kotlinPackage: String,
  rootPackage: String,
  rootNamespace: String,
): String {
  if (rootPackage.isEmpty()) return rootNamespace

  val relative: String = if (kotlinPackage.startsWith(rootPackage)) {
    kotlinPackage.removePrefix(rootPackage).removePrefix(".")
  } else {
    kotlinPackage
  }

  if (relative.isEmpty()) return rootNamespace

  val suffix: String = relative.split(".")
    .joinToString(".") { segment ->
      segment.replaceFirstChar { it.uppercase() }
    }

  return "$rootNamespace.$suffix"
}

/**
 * ADR-066: the `Flow<T>`/`StateFlow<T>` element-type route mapped its element by *simple* name
 * (`declaration.simpleName.asString()`), so `Flow<TopStory>` emitted the unqualified
 * `KotlinFlow<TopStory>`, a type that only resolves inside `Interop.cs` when the element's
 * namespace happens to coincide with the enclosing class's own namespace, which an admitted
 * dependency-module type is never guaranteed to do. Mirrors [ForwardBridgeTypeClassifier]'s enum
 * branch: a known scalar keeps its C# primitive spelling, and everything else is qualified.
 *
 * Qualification is unconditional whenever a root namespace exists: a reference already in the
 * enclosing namespace renders `global::Namespace.Name` too, rather than staying bare. That is
 * what shipped, and it is what a caller must expect; the earlier "a same-namespace reference
 * stays bare" wording described a same-namespace shortcut this function has never had. Only an
 * empty [NugetContext.rootNamespace] (nothing to qualify with) yields a bare name.
 */
internal fun qualifiedElementCsType(type: KSType?, context: NugetContext): String {
  val declaration: KSDeclaration = type?.expandAliases()?.declaration ?: return "Any"
  val simpleName: String = declaration.simpleName.asString()
  KOTLIN_TO_CSHARP_PARAM[simpleName]?.let { return it }
  val classDeclaration: KSClassDeclaration = declaration as? KSClassDeclaration ?: return simpleName
  // Enclosing scope included ([nestedCsName]): this route spells a *sealed subclass* property's
  // type (`CirClassTranslator`) and an ADR-067 flow element, either of which can be a class nested
  // in its sealed base and so declared as a nested C# class.
  val nestedName: String = classDeclaration.nestedCsName()
  if (context.rootNamespace.isEmpty()) return nestedName
  val kotlinPackage: String = classDeclaration.packageName.asString()
  // ADR-123: this is the *user-type* speller. A Kotlin builtin reaching it is a defect at every
  // call site, because [mapPackageToNamespace] has no notion of a builtin package: it capitalises
  // `kotlin.collections` into `Kotlin.Collections` and hangs it off the root namespace, naming a
  // namespace nothing declares (issue #127, CS0234), and it never reads the type's arguments
  // either. Callers gate first: a collection element goes through `forwardPublicCsharpType()`,
  // and anything else builtin is refused by name.
  check(!kotlinPackage.isKotlinBuiltinPackage()) {
    "Kotlin builtin $kotlinPackage.$nestedName reached the user-type C# speller; it would " +
        "render global::${context.rootNamespace}.Kotlin..., a namespace nothing declares. " +
        "Gate the call site on the type's own route first (ADR-123)."
  }
  val namespace: String = mapPackageToNamespace(
    kotlinPackage, context.rootPackage, context.rootNamespace,
  )
  return "global::$namespace.$nestedName"
}

/**
 * `kotlin`, `kotlinx` and everything under them: the packages [mapPackageToNamespace] must never
 * see, since a root namespace is only ever a *user* package's prefix.
 */
private fun String.isKotlinBuiltinPackage(): Boolean =
  this == "kotlin" || this == "kotlinx" ||
      startsWith("kotlin.") || startsWith("kotlinx.")

/**
 * ADR-067: threads a nullable *element* (`StateFlow<T?>`) through to the C# type argument. Both
 * value types (`int?` → real `Nullable<int>`) and reference types (`string?`/`Cat?` — a compile-
 * time-only annotation) accept the trailing `?`; C# does not reject it on either kind.
 */
internal fun qualifiedElementCsType(
  type: KSType?,
  context: NugetContext,
  nullable: Boolean,
): String {
  val base: String = qualifiedElementCsType(type, context)
  return if (nullable) "$base?" else base
}

/**
 * Issue #111: how one type argument of a `KotlinFunc<...>` / `KotlinSuspendFunc<...>` / generic
 * return is spelled in C#, or why it cannot be spelled at all.
 *
 * The legacy lambda routes spelled every argument
 * `arg.type?.resolve()?.declaration?.simpleName?.asString() ?: "object"`, which drops both the
 * argument's namespace and its own type arguments, so `(CamId) -> Flow<Snapshot>` rendered
 * `KotlinFunc<CamId, Flow>`: two separate CS0246s in one line.
 *
 * Qualifying alone does not fix it. `Flow<Snapshot>` spelled `global::Ns.Kotlinx.Coroutines.Flow
 * .Flow` names a type nothing declares, which is the same CS0246 in a longer coat. So there are
 * two outcomes, never one: an argument C# can genuinely name is [Named] and fully qualified, and
 * one it cannot is [Unnameable] and the whole member is skipped by its caller with a diagnostic
 * naming the offending argument.
 */
internal sealed interface CsTypeArgument {
  /**
   * The C# spelling: a primitive (`int`), a type parameter in scope (`T`), or `global::Ns.Name`.
   */
  data class Named(val csType: String) : CsTypeArgument

  /** The qualified name of the argument C# has no spelling for, for the caller's diagnostic. */
  data class Unnameable(val typeArgument: String) : CsTypeArgument
}

/**
 * Issue #111: the one place a lambda's (or a generic return's) type argument is turned into C#.
 *
 * Admits, in order:
 *  - a known scalar, by its C# primitive name (`Int` -> `int`), and `Unit` as `void` (the suspend
 *    routes narrow a `void` result to `KotlinSuspendAction`);
 *  - a *type parameter* (`class Crate<T>`), kept bare: `T` is in scope at the declaration, not a
 *    type in a namespace, so `global::Ns.T` would be nonsense;
 *  - a class, object or enum that is both **exported and declared**, fully qualified.
 *
 * Everything else is [CsTypeArgument.Unnameable]: a type carrying its own type arguments
 * (`Flow<T>`, `List<T>`, a nested lambda, a generic class), and any type outside [exportedTypes]
 * (an unexported dependency type, a nested class, a stdlib type). The export set is the test on
 * purpose rather than "is it a class": [ForwardReachabilityClosure] does not walk lambda type
 * arguments (`LAMBDA_TYPES` is an intrinsic terminal, not a carrier), so a class reachable only
 * through one is never admitted, and qualifying it would emit a `global::` reference to a type
 * nothing declares.
 *
 * A *declared* enum is deliberately still admitted here even though `NugetMarshal.FromHandle` has
 * no enum branch (`docs/backlog/fromhandle-no-enum-branch.md`): it compiles, and narrowing that
 * gap is a separate change.
 */
internal fun csTypeArgument(
  type: KSType?,
  exportedTypes: Set<String>,
  context: NugetContext,
): CsTypeArgument {
  val resolved: KSType = type?.expandAliases()
    ?: return CsTypeArgument.Unnameable("an unresolved type argument")
  val declaration: KSDeclaration = resolved.declaration
  val simpleName: String = declaration.simpleName.asString()
  val qualifiedName: String = declaration.qualifiedName?.asString() ?: simpleName

  KOTLIN_TO_CSHARP_PARAM[simpleName]?.let { return CsTypeArgument.Named(it) }
  if (qualifiedName == "kotlin.Unit") return CsTypeArgument.Named("void")
  if (declaration is KSTypeParameter) return CsTypeArgument.Named(simpleName)

  if (declaration !is KSClassDeclaration) return CsTypeArgument.Unnameable(qualifiedName)
  if (resolved.arguments.isNotEmpty()) return CsTypeArgument.Unnameable(qualifiedName)
  if (qualifiedName !in exportedTypes) return CsTypeArgument.Unnameable(qualifiedName)

  return CsTypeArgument.Named(qualifiedElementCsType(resolved, context))
}

/**
 * Issue #111: [csTypeArgument] over a whole argument list, so a caller can decide once between
 * "spell the member" and "skip it named". The first unnameable argument wins: a member with two
 * of them is skipped for the first, and the author fixes them one at a time either way.
 */
internal fun csTypeArguments(
  arguments: List<KSTypeArgument>,
  exportedTypes: Set<String>,
  context: NugetContext,
): CsTypeArgument.Unnameable? = arguments
  .asSequence()
  .map { argument -> csTypeArgument(argument.type?.resolve(), exportedTypes, context) }
  .filterIsInstance<CsTypeArgument.Unnameable>()
  .firstOrNull()

/** Issue #111: the C# spellings, valid only when [csTypeArguments] found nothing unnameable. */
internal fun csTypeArgumentNames(
  arguments: List<KSTypeArgument>,
  exportedTypes: Set<String>,
  context: NugetContext,
): List<String> = arguments.map { argument ->
  when (val spelling = csTypeArgument(argument.type?.resolve(), exportedTypes, context)) {
    is CsTypeArgument.Named -> spelling.csType
    // The generic-return route (`CirFunctionTranslator`) is qualify-only by decision: it keeps the
    // pre-issue-#111 simple name for an argument with no C# spelling rather than skipping the
    // function, so gating that route stays a separate question.
    is CsTypeArgument.Unnameable -> spelling.typeArgument.substringAfterLast('.')
  }
}

/**
 * Issue #114: the C# spelling of a lambda whose type arguments are already resolved, narrowing a
 * Unit return to `KotlinAction` instead of spelling it `void`.
 *
 * `void` is a legal C# return type and never a legal type argument, so `() -> Unit` rendered
 * `KotlinFunc<void>` is `CS1547: Keyword 'void' cannot be used in this context`, twice per
 * property (once for the declared type, once for the `new`). The suspend arm has always narrowed
 * this way; this is its plain-lambda twin.
 *
 * The Unit return is *dropped* rather than replaced, so the arity is carried by the parameters
 * alone: `() -> Unit` is the non-generic `KotlinAction` and `(Int) -> Unit` is
 * `KotlinAction<int>`, the same shape as `KotlinSuspendAction` and the shape `renderFuncHelper`
 * declares.
 *
 * [typeArgs] is a lambda's full argument list, return type last, as [csTypeArgumentNames] spells
 * it. Only `kotlin.Unit` maps to `"void"` there, so the string test is exact.
 *
 * Shared rather than inlined because the three routes that spell a lambda (ordinary class
 * property, sealed subclass property, top-level function return) were three hand-written copies
 * of one expression, and issue #111 had already been fixed in each of them separately.
 */
internal fun csLambdaType(typeArgs: List<String>): String {
  if (typeArgs.lastOrNull() != "void") return "KotlinFunc<${typeArgs.joinToString(", ")}>"
  val parameters: List<String> = typeArgs.dropLast(1)
  if (parameters.isEmpty()) return "KotlinAction"
  return "KotlinAction<${parameters.joinToString(", ")}>"
}

/**
 * Issue #111: the one diagnostic every lambda route raises for a type argument with no C#
 * spelling, so a property arm and a return arm say the same thing under different kinds.
 *
 * It names the offending argument rather than the member's whole type: `(CamId) -> Flow<Snapshot>`
 * is skipped because of `Flow`, and an author told only "this property was skipped" has three
 * candidates to guess between.
 */
internal fun lambdaTypeArgumentDiagnostic(
  kind: ForwardDiagnosticKind,
  symbol: KSNode?,
  declaration: String,
  typeArgument: String,
): ForwardDiagnostic = ForwardDiagnostic(
  kind = kind,
  symbol = symbol,
  declaration = declaration,
  reason = "its lambda type argument `$typeArgument` has no C# spelling: a lambda argument must " +
      "be a primitive, String, or an exported class, object or enum that is declared in C#, and " +
      "a type carrying its own type arguments (Flow<T>, a collection, another lambda, a generic " +
      "class) has no spelling on this route at all",
  hint = "expose a lambda over bridgeable types instead: replace `$typeArgument` with a " +
      "primitive, a String, or a top-level exported class in the export scope (a Flow or a " +
      "generic type argument needs its own bridgeable wrapper type)",
)
