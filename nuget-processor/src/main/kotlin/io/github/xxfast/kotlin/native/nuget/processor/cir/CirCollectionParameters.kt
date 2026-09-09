package io.github.xxfast.kotlin.native.nuget.processor.cir

import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSValueParameter
import io.github.xxfast.kotlin.native.nuget.processor.csharpParameterName
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardBridgeTypeClassifier
import io.github.xxfast.kotlin.native.nuget.processor.forward.ForwardLegacyParameterShape
import io.github.xxfast.kotlin.native.nuget.processor.forward.forwardPublicCsharpType
import io.github.xxfast.kotlin.native.nuget.processor.forward.legacyCollectionCreate
import io.github.xxfast.kotlin.native.nuget.processor.forward.legacyParameterShape

/**
 * ADR-114: the C# half of a collection parameter on a legacy Flow/StateFlow or suspend route.
 *
 * The ownership model is the ordinary synchronous route's, unchanged, moved one lexical level
 * inwards: the wire container is built immediately before the native call and disposed in a
 * `finally` immediately after it returns. That is safe on these routes only because the Kotlin
 * export copies the container out *before* `scope.launch` (see `legacyLoweringStatement`), so no
 * coroutine, callback or cancellation path can ever observe the handle.
 *
 * The native call itself lives inside a closure on every one of these routes (the collect
 * delegate, the `.Value` read lambda, the `MutableStateFlow` write lambda) or inside the async
 * method body, so the create/dispose pair is emitted around whichever of those makes the call.
 */
internal val CirParameter.nativeArgument: String
  get() = when {
    // ADR-122: a borrowed handle (`observation._handle`), passed straight through with no
    // call-scoped allocation, so it deliberately does not reach the create/dispose block below.
    nativeArgumentExpression != null -> nativeArgumentExpression
    collectionCreate != null -> "${name}Handle"
    else -> name
  }

/** Whether any parameter needs a wire handle built before the native call. */
internal fun List<CirParameter>.hasCollectionHandles(): Boolean =
  any { it.collectionCreate != null }

/**
 * The native call [call] wrapped in create-then-`finally`-dispose for every collection parameter,
 * as a block-bodied lambda or statement block at [indent]. Returns null when nothing needs a
 * handle, so every existing call site keeps its exact previous single-expression rendering.
 */
internal fun List<CirParameter>.collectionScopedCall(
  indent: String,
  call: String,
  returns: Boolean = true,
): List<String>? {
  val handles: List<CirParameter> = filter { it.collectionCreate != null }
  if (handles.isEmpty()) return null
  val body: String = if (returns) "return $call;" else "$call;"
  return buildList {
    add("$indent{")
    handles.forEach { add("$indent    IntPtr ${it.nativeArgument} = ${it.collectionCreate};") }
    add("$indent    try")
    add("$indent    {")
    add("$indent        $body")
    add("$indent    }")
    add("$indent    finally")
    add("$indent    {")
    handles.forEach { add("$indent        NugetMarshal.Dispose(${it.nativeArgument});") }
    add("$indent    }")
    add("$indent}")
  }
}

/**
 * The C# parameters of a legacy Flow/StateFlow or suspend member: the public signature takes the
 * collection, the `DllImport` takes the `IntPtr` handle it is projected to. `CirParameter` already
 * carried that split for enum parameters (`Mood` public, `int` native), so the two halves need no
 * model change beyond the create expression.
 *
 * Registering the kind with [tracker] is not optional: it is what emits the `NugetListNative` /
 * `NugetSetNative` / `NugetMapNative` classes the create call binds to. These members carry no
 * `ForwardCallablePlan`, so `trackPlan` never sees them.
 */
internal fun legacyRouteParameters(
  parameters: List<KSValueParameter>,
  classifier: ForwardBridgeTypeClassifier,
  tracker: CollectionHelperTracker,
): List<CirParameter> = parameters.map { param ->
  val name: String = (param.name?.asString() ?: "_").csharpParameterName()
  when (val shape: ForwardLegacyParameterShape =
    classifier.legacyParameterShape(param.type.resolve())) {
    is ForwardLegacyParameterShape.Marshalled -> {
      tracker.trackCollection(shape.type)
      CirParameter(
        name,
        type = shape.type.forwardPublicCsharpType(),
        nativeType = "IntPtr",
        collectionCreate = legacyCollectionCreate(name, shape.type),
      )
    }

    // ADR-122: the public type is the mapped C# spelling the return and property routes on this
    // same class already use, and the native argument is the `internal IntPtr _handle` the wrapper
    // (or, for a sealed arm, the base it inherits from) already declares. Nothing is allocated
    // here, so nothing is disposed here either.
    is ForwardLegacyParameterShape.Handle -> CirParameter(
      name,
      type = shape.type.forwardPublicCsharpType(),
      nativeType = "IntPtr",
      nativeArgumentExpression = "$name._handle",
    )

    // A scalar keeps the shipped spelling. A refused parameter never reaches here: both halves
    // filter its member out first, and `warnRefusedLegacyRouteMembers` names it once.
    ForwardLegacyParameterShape.Plain, is ForwardLegacyParameterShape.Refused -> {
      val resolved: KSType = param.type.resolve().expandAliases()
      CirParameter(name, mapParamType(resolved.declaration.simpleName.asString()))
    }
  }
}
