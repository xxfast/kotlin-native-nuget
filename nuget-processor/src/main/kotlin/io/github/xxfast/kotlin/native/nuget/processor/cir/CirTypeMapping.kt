package io.github.xxfast.kotlin.native.nuget.processor.cir

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeAlias
import io.github.xxfast.kotlin.native.nuget.processor.forward.BridgeType
import io.github.xxfast.kotlin.native.nuget.processor.forward.CollectionKind
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardCallablePlan
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
internal val STATE_FLOW_TYPES = setOf(
  "kotlinx.coroutines.flow.StateFlow",
  "kotlinx.coroutines.flow.MutableStateFlow",
)

internal class CollectionHelperTracker {
  var needsList: Boolean = false
  var needsMap: Boolean = false
  var needsSet: Boolean = false
  var needsAsync: Boolean = false
  var needsFlow: Boolean = false
  var needsStateFlow: Boolean = false

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
  }

  fun trackPlan(plan: ForwardCallablePlan) {
    trackCollection(plan.publicSignature.result)
    plan.publicSignature.parameters.forEach { parameter -> trackCollection(parameter.type) }
  }

  fun trackProperty(plan: ForwardPropertyPlan) {
    trackCollection(plan.type)
  }
}

internal fun mapReturnType(kotlinType: String): String =
  KOTLIN_TO_CSHARP_RETURN[kotlinType] ?: "IntPtr"

internal fun mapParamType(kotlinType: String): String =
  KOTLIN_TO_CSHARP_PARAM[kotlinType] ?: "IntPtr"

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
 * (`declaration.simpleName.asString()`), so `Flow<TopStory>` emitted the unqualified `KotlinFlow
 * <TopStory>` — a type that only resolves inside `Interop.cs` when the element's namespace
 * happens to coincide with the enclosing class's own namespace, which an admitted dependency-
 * module type is never guaranteed to do. Mirrors [ForwardBridgeTypeClassifier]'s enum branch
 * exactly: a known scalar keeps its C# primitive spelling, otherwise a same-namespace reference
 * stays bare and everything else renders `global::Namespace.Name`.
 */
internal fun qualifiedElementCsType(type: KSType?, context: NugetContext): String {
  val declaration: KSDeclaration = type?.expandAliases()?.declaration ?: return "Any"
  val simpleName: String = declaration.simpleName.asString()
  KOTLIN_TO_CSHARP_PARAM[simpleName]?.let { return it }
  val classDeclaration: KSClassDeclaration = declaration as? KSClassDeclaration ?: return simpleName
  if (context.rootNamespace.isEmpty()) return simpleName
  val namespace: String = mapPackageToNamespace(
    classDeclaration.packageName.asString(), context.rootPackage, context.rootNamespace,
  )
  return "global::$namespace.$simpleName"
}

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
