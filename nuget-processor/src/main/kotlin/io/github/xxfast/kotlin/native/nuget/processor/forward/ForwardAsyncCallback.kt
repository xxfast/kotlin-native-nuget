package io.github.xxfast.kotlin.native.nuget.processor.forward

import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import io.github.xxfast.kotlin.native.nuget.processor.freshName

/** ADR-210: which kind of asynchronous parameter a set of [ForwardAsyncSlots] belongs to. */
internal enum class ForwardAsyncKind {
  /** A `suspend (T) -> R`: its own slots are the Begin thunk, the End thunk and the key. */
  LAMBDA,

  /** A `Flow<T>`: its own slots are the Enumerate thunk, the Current thunk and the key. */
  FLOW,
}

/**
 * ADR-210: the thunks that do not depend on the parameter, so they cross ONCE per export. Declared
 * in this order by whichever parameter is first to need each: release task and release context by
 * the export's first asynchronous parameter, release cancellation by its first suspend lambda, the
 * three enumeration thunks by its first `Flow`.
 */
internal enum class ForwardAsyncShared {
  RELEASE_TASK,
  RELEASE_CANCELLATION,
  MOVE_NEXT_BEGIN,
  MOVE_NEXT_END,
  DISPOSE_ENUMERATION,
  RELEASE_CONTEXT,
}

/**
 * ADR-210: the native slots one suspend lambda or `Flow` parameter is read from, already minted
 * against the export's other names. [begin], [end] and [key] are the parameter's own (for a
 * `Flow`, the Enumerate and Current thunks). [shared] names every parameter-independent thunk this
 * parameter reads; [carried] are the ones it is first to need and therefore declares, right after
 * its own three. So one suspend lambda is 6 slots, one `Flow` 8, one of each 12.
 */
internal data class ForwardAsyncSlots(
  val kind: ForwardAsyncKind,
  val begin: String,
  val end: String,
  val key: String,
  val shared: Map<ForwardAsyncShared, String>,
  val carried: List<ForwardAsyncShared>,
  /** Kotlin: the local the key-owning holder is bound to, built before anything can throw. */
  val holder: String,
  /**
   * C#: the local the key is registered into. Any exit between the registration and the native
   * call returning unregisters it; once the call has returned, Kotlin owns the key.
   */
  val csharpKey: String,
) {
  val releaseTask: String get() = shared.getValue(ForwardAsyncShared.RELEASE_TASK)
  val releaseContext: String get() = shared.getValue(ForwardAsyncShared.RELEASE_CONTEXT)
  val releaseCancellation: String get() = shared.getValue(ForwardAsyncShared.RELEASE_CANCELLATION)
  val moveNextBegin: String get() = shared.getValue(ForwardAsyncShared.MOVE_NEXT_BEGIN)
  val moveNextEnd: String get() = shared.getValue(ForwardAsyncShared.MOVE_NEXT_END)
  val disposeEnumeration: String get() = shared.getValue(ForwardAsyncShared.DISPOSE_ENUMERATION)

  /** The slots this parameter itself declares, in ABI order. */
  val declared: List<String>
    get() = listOf(begin, end, key) + carried.map { slot -> shared.getValue(slot) }
}

/** The shared thunks a parameter of [kind] reads, in declaration order. */
private fun ForwardAsyncKind.sharedSlots(): List<ForwardAsyncShared> = when (this) {
  ForwardAsyncKind.LAMBDA -> listOf(
    ForwardAsyncShared.RELEASE_TASK,
    ForwardAsyncShared.RELEASE_CANCELLATION,
    ForwardAsyncShared.RELEASE_CONTEXT,
  )

  ForwardAsyncKind.FLOW -> listOf(
    ForwardAsyncShared.RELEASE_TASK,
    ForwardAsyncShared.MOVE_NEXT_BEGIN,
    ForwardAsyncShared.MOVE_NEXT_END,
    ForwardAsyncShared.DISPOSE_ENUMERATION,
    ForwardAsyncShared.RELEASE_CONTEXT,
  )
}

private fun ForwardAsyncShared.stem(): String = when (this) {
  ForwardAsyncShared.RELEASE_TASK -> "ReleaseTask"
  ForwardAsyncShared.RELEASE_CANCELLATION -> "ReleaseCancellation"
  ForwardAsyncShared.MOVE_NEXT_BEGIN -> "MoveNextBegin"
  ForwardAsyncShared.MOVE_NEXT_END -> "MoveNextEnd"
  ForwardAsyncShared.DISPOSE_ENUMERATION -> "DisposeEnumeration"
  ForwardAsyncShared.RELEASE_CONTEXT -> "ReleaseContext"
}

/** The [ForwardAsyncKind] of a parameter of [this] type, or null for every other type. */
internal fun BridgeType.asyncKind(): ForwardAsyncKind? = when (this) {
  is BridgeType.AsyncCallback -> ForwardAsyncKind.LAMBDA
  is BridgeType.FlowSource -> ForwardAsyncKind.FLOW
  BridgeType.Unit,
  is BridgeType.Primitive,
  BridgeType.Char,
  BridgeType.String,
  BridgeType.Instant,
  BridgeType.Duration,
  is BridgeType.Throwable,
  BridgeType.Uuid,
  is BridgeType.Enum,
  is BridgeType.ObjectHandle,
  is BridgeType.Interface,
  is BridgeType.BoundInterface,
  is BridgeType.ValueClass,
  BridgeType.ByteArray,
  is BridgeType.Collection,
  is BridgeType.Nullable,
  is BridgeType.Callback,
  is BridgeType.ReturnedLambda,
  is BridgeType.SpecializedProtocol,
  is BridgeType.RawKSType,
  is BridgeType.Unsupported,
  is BridgeType.RawCollection,
  is BridgeType.TypeParameter,
    -> null
}

/**
 * Mints the [ForwardAsyncSlots] of every asynchronous parameter in [parameters] (name to kind, null
 * for every other parameter), through [mint], in declaration order. A shared thunk is named after
 * the parameter that declares it.
 */
internal fun mintAsyncSlots(
  parameters: List<Pair<String, ForwardAsyncKind?>>,
  mint: (String) -> String,
): List<ForwardAsyncSlots?> {
  val shared: MutableMap<ForwardAsyncShared, String> = mutableMapOf()
  return parameters.map { (name, kind) ->
    if (kind == null) return@map null
    val begin: String = mint(if (kind == ForwardAsyncKind.FLOW) "${name}Enumerate" else "${name}Begin")
    val end: String = mint(if (kind == ForwardAsyncKind.FLOW) "${name}Current" else "${name}End")
    val key: String = mint("${name}Key")
    val carried: List<ForwardAsyncShared> = kind.sharedSlots().filter { slot -> slot !in shared }
    carried.forEach { slot -> shared[slot] = mint("$name${slot.stem()}") }
    ForwardAsyncSlots(
      kind, begin, end, key,
      shared = kind.sharedSlots().associateWith { slot -> shared.getValue(slot) },
      carried = carried,
      holder = mint("${name}Holder"),
      csharpKey = mint("${name}AsyncKey"),
    )
  }
}

/** The name of the generated holder class, emitted once into `CNameExports.kt`. */
internal const val ASYNC_CALLBACK_HOLDER: String = "NugetAsyncContext"

private const val RUNTIME: String = "io.github.xxfast.kotlin.native.nuget.runtime"
private const val CFUNCTION: String = "kotlinx.cinterop.CFunction"
private const val POINTER: String = "kotlinx.cinterop.COpaquePointer"

/**
 * ADR-210: the holder a generated `suspend` lambda captures. It owns the cleaner that hands the C#
 * context key back through the release-context thunk, the shape the ADR-084 bridge factory emits
 * (`InterfaceBridgeFactoryExports`). The cleaner's block captures nothing but the pair, so the
 * holder is collectable, and `null` in the error slot selects the thunk's FailFast branch: there
 * is nowhere to throw to from a GC worker.
 */
internal fun asyncCallbackHolderType(): TypeSpec {
  val pointer = ClassName("kotlinx.cinterop", "COpaquePointer")
  return TypeSpec.classBuilder(ASYNC_CALLBACK_HOLDER)
    .addModifiers(KModifier.PRIVATE)
    .addAnnotation(
      AnnotationSpec.builder(ClassName("kotlin", "OptIn"))
        .addMember("%T::class", ClassName("kotlin.experimental", "ExperimentalNativeApi"))
        .build()
    )
    .primaryConstructor(
      FunSpec.constructorBuilder()
        .addParameter("releaseContext", pointer)
        .addParameter("key", pointer)
        .build()
    )
    .addProperty(PropertySpec.builder("key", pointer).initializer("key").build())
    .addProperty(
      PropertySpec.builder("cleaner", ClassName("kotlin.native.ref", "Cleaner"), KModifier.PRIVATE)
        .addAnnotation(
          AnnotationSpec.builder(ClassName("kotlin", "Suppress")).addMember("%S", "unused").build()
        )
        .initializer(
          CodeBlock.of(
            "kotlin.native.ref.createCleaner(\n" +
                "  releaseContext.reinterpret<$CFUNCTION<($POINTER,·$POINTER?)·->·Unit>>()" +
                " to key\n" +
                ") { (fn, ctx) -> fn.invoke(ctx, null) }"
          )
        )
        .build()
    )
    .build()
}

/** `NugetAsyncContext(releaseContext, key)`: the holder taking ownership of the C# key. */
internal fun asyncCallbackHolderConstruction(slots: ForwardAsyncSlots): String =
  "$ASYNC_CALLBACK_HOLDER(${slots.releaseContext}, ${slots.key})"

/** The Kotlin spelling of one component of the function type, as the member declares it. */
private fun BridgeType.asyncKotlinType(): String = when (this) {
  BridgeType.Unit -> "kotlin.Unit"
  is BridgeType.Primitive -> "kotlin.${kind.simpleKotlinName()}"
  BridgeType.String -> "kotlin.String"
  is BridgeType.Enum -> qualifiedName
  is BridgeType.ObjectHandle -> kotlinReadType ?: qualifiedName
  is BridgeType.Interface -> qualifiedName
  // A function type is contravariant in its parameters, so the widest spelling is assignable to
  // whichever of `Throwable` / `Exception` / `RuntimeException` the member declared.
  is BridgeType.Throwable -> "kotlin.Throwable"
  BridgeType.Char,
  BridgeType.Instant,
  BridgeType.Duration,
  BridgeType.Uuid,
  is BridgeType.BoundInterface,
  is BridgeType.ValueClass,
  BridgeType.ByteArray,
  is BridgeType.Collection,
  is BridgeType.Nullable,
  is BridgeType.Callback,
  is BridgeType.AsyncCallback,
  is BridgeType.FlowSource,
  is BridgeType.ReturnedLambda,
  is BridgeType.SpecializedProtocol,
  is BridgeType.RawKSType,
  is BridgeType.Unsupported,
  is BridgeType.RawCollection,
  is BridgeType.TypeParameter,
    -> error("ADR-210 admits no suspend lambda component $this")
}

/** The Kotlin `CFunction` spelling of one component on the wire. */
private fun BridgeType.asyncKotlinWire(): String = when (this) {
  BridgeType.Unit -> "Unit"
  is BridgeType.Primitive ->
    if (kind == PrimitiveKind.BOOLEAN) "Byte" else kind.simpleKotlinName()

  is BridgeType.Enum -> "Int"
  BridgeType.String, is BridgeType.ObjectHandle, is BridgeType.Interface,
  is BridgeType.Throwable -> "$POINTER?"
  BridgeType.Char,
  BridgeType.Instant,
  BridgeType.Duration,
  BridgeType.Uuid,
  is BridgeType.BoundInterface,
  is BridgeType.ValueClass,
  BridgeType.ByteArray,
  is BridgeType.Collection,
  is BridgeType.Nullable,
  is BridgeType.Callback,
  is BridgeType.AsyncCallback,
  is BridgeType.FlowSource,
  is BridgeType.ReturnedLambda,
  is BridgeType.SpecializedProtocol,
  is BridgeType.RawKSType,
  is BridgeType.Unsupported,
  is BridgeType.RawCollection,
  is BridgeType.TypeParameter,
    -> error("ADR-210 admits no suspend lambda wire for $this")
}

/** One payload, lowered to its wire. ADR-036 ownership: C# frees a handle-passed payload. */
private fun BridgeType.asyncPayloadArgument(argument: String): String = when (this) {
  is BridgeType.Primitive ->
    if (kind == PrimitiveKind.BOOLEAN) "if ($argument) 1.toByte() else 0.toByte()" else argument

  is BridgeType.Enum -> "$argument.ordinal"
  BridgeType.String -> "NugetHandles.retain($argument as Any)"
  is BridgeType.Throwable -> "NugetHandles.retain(buildError($argument, ::nugetMappedType))"
  is BridgeType.ObjectHandle, is BridgeType.Interface -> "NugetHandles.retain($argument)"
  BridgeType.Unit,
  BridgeType.Char,
  BridgeType.Instant,
  BridgeType.Duration,
  BridgeType.Uuid,
  is BridgeType.BoundInterface,
  is BridgeType.ValueClass,
  BridgeType.ByteArray,
  is BridgeType.Collection,
  is BridgeType.Nullable,
  is BridgeType.Callback,
  is BridgeType.AsyncCallback,
  is BridgeType.FlowSource,
  is BridgeType.ReturnedLambda,
  is BridgeType.SpecializedProtocol,
  is BridgeType.RawKSType,
  is BridgeType.Unsupported,
  is BridgeType.RawCollection,
  is BridgeType.TypeParameter,
    -> error("ADR-210 admits no suspend lambda payload $this")
}

/** `suspend (kotlin.Int) -> kotlin.String`. */
internal fun BridgeType.AsyncCallback.kotlinFunctionType(): String =
  parameters.joinToString(", ", "suspend (", ") -> ${result.asyncKotlinType()}") { parameter ->
    parameter.asyncKotlinType()
  }

/**
 * ADR-210: the Kotlin `suspend` lambda that forwards one invocation out to the C# delegate, as one
 * self-contained expression of type [kotlinFunctionType].
 *
 * The composition is the ADR's sketch (spike 2): `awaitForKotlin` owns the pending ctx and the
 * cancellation source handle, Begin and End are user-code crossings so each goes through
 * `nugetCallbackCall`, and the lambda captures a [ASYNC_CALLBACK_HOLDER] whose cleaner releases the
 * C# key. The holder is built as the expression is evaluated, on the calling thread, so Kotlin owns
 * the key before any coroutine launches. Every Kotlin-side handle is minted by `NugetHandles`.
 */
internal fun asyncCallbackExpression(
  name: String,
  type: BridgeType.AsyncCallback,
  slots: ForwardAsyncSlots,
  // True when the export already bound [ForwardAsyncSlots.holder] as its first statement (the
  // plan route); false when this expression builds it itself (the legacy prelude, which runs
  // these lowerings ahead of every other).
  holderBuilt: Boolean = false,
): String {
  val taken: MutableSet<String> = mutableSetOf(
    name, slots.begin, slots.end, slots.key, slots.releaseTask, slots.releaseCancellation,
    slots.releaseContext, slots.holder,
  )
  fun mint(base: String): String = freshName(base, taken).also { taken += it }
  val holder: String = slots.holder
  val lambda: String = mint("${name}Lambda")
  val task: String = mint("${name}Task")
  val box: String = mint("${name}Box")
  val value: String = mint("${name}Value")
  val arguments: List<String> = type.parameters.indices.map { index -> mint("${name}Arg$index") }
  val payloadWire: List<String> = type.parameters.map { parameter -> parameter.asyncKotlinWire() }
  // Payloads, the completion callback, awaitForKotlin's pending ctx, the key, then ADR-161's slot.
  val beginSignature: String =
    (payloadWire + listOf(POINTER, POINTER, POINTER, "$POINTER?")).joinToString(", ")
  val beginArguments: String = (
      type.parameters.mapIndexed { index, parameter ->
        parameter.asyncPayloadArgument(arguments[index])
      } + listOf("nugetCallback", "nugetCtx", "$holder.key", "nugetErr")
      ).joinToString(", ")
  val resultWire: String = type.result.asyncKotlinWire()
  val end: String = "nugetCallbackCall { nugetErr -> " +
      "${slots.end}.reinterpret<$CFUNCTION<($POINTER, $POINTER?) -> $resultWire>>()" +
      ".invoke($task, nugetErr) }"
  val header: String = if (arguments.isEmpty()) "{" else "{ ${arguments.joinToString(", ")} ->"
  return buildString {
    appendLine("run {")
    if (!holderBuilt) appendLine("  val $holder = ${asyncCallbackHolderConstruction(slots)}")
    appendLine("  val $lambda: ${type.kotlinFunctionType()} = $header")
    appendLine("    val $task: $POINTER = $RUNTIME.awaitForKotlin(")
    appendLine(
      "      release = { nugetTask -> ${slots.releaseTask}" +
          ".reinterpret<$CFUNCTION<($POINTER) -> Unit>>().invoke(nugetTask) },"
    )
    appendLine(
      "      cancel = { nugetSource, nugetCancelled -> ${slots.releaseCancellation}" +
          ".reinterpret<$CFUNCTION<($POINTER, Int) -> Unit>>()" +
          ".invoke(nugetSource, if (nugetCancelled) 1 else 0) },"
    )
    appendLine("      begin = { nugetCallback, nugetCtx ->")
    appendLine(
      "        nugetCallbackCall { nugetErr -> ${slots.begin}" +
          ".reinterpret<$CFUNCTION<($beginSignature) -> $POINTER?>>()" +
          ".invoke($beginArguments) }"
    )
    appendLine("      },")
    appendLine("    )")
    when (val result: BridgeType = type.result) {
      BridgeType.Unit -> appendLine("    $end")
      is BridgeType.Primitive ->
        if (result.kind == PrimitiveKind.BOOLEAN) appendLine("    $end != 0.toByte()")
        else appendLine("    $end")

      BridgeType.String -> {
        // The End thunk boxes the string with `WrapString`; no C# owner frees it, so this side
        // releases the box once it has read it (ADR-036's return-box rule).
        appendLine("    val $box = $end!!")
        appendLine("    val $value = $box.asStableRef<String>().get()")
        appendLine("    NugetHandles.release($box)")
        appendLine("    $value")
      }

      BridgeType.Char,
      BridgeType.Instant,
      BridgeType.Duration,
      is BridgeType.Throwable,
      BridgeType.Uuid,
      is BridgeType.Enum,
      is BridgeType.ObjectHandle,
      is BridgeType.Interface,
      is BridgeType.BoundInterface,
      is BridgeType.ValueClass,
      BridgeType.ByteArray,
      is BridgeType.Collection,
      is BridgeType.Nullable,
      is BridgeType.Callback,
      is BridgeType.AsyncCallback,
      is BridgeType.FlowSource,
      is BridgeType.ReturnedLambda,
      is BridgeType.SpecializedProtocol,
      is BridgeType.RawKSType,
      is BridgeType.Unsupported,
      is BridgeType.RawCollection,
      is BridgeType.TypeParameter,
        -> error("ADR-210 admits no suspend lambda result $result")
    }
    appendLine("  }")
    appendLine("  $lambda")
    append("}")
  }
}

/** `kotlinx.coroutines.flow.Flow<kotlin.Int>`. */
internal fun BridgeType.FlowSource.kotlinFlowType(): String =
  "kotlinx.coroutines.flow.Flow<${element.asyncKotlinType()}>"

/**
 * ADR-210: the Kotlin `Flow<T>` over the C# caller's `IAsyncEnumerable<T>`, as one self-contained
 * expression of type [kotlinFlowType].
 *
 * The whole shape is the runtime's `flowForKotlin` (ADR-156): cold, so the Enumerate thunk (and with
 * it `GetAsyncEnumerator`) runs once per `collect`; one `awaitForKotlin` per element; the
 * enumeration disposed exactly once from its `finally`. Enumerate, both halves of a step and
 * Current run user C#, so each goes through `nugetCallbackCall`; the dispose and the task release
 * are plumbing and carry no error slot. `enumerate` reads the key off the holder, which is what
 * keeps the holder (and so the C# source) alive for as long as the flow is reachable.
 */
internal fun flowSourceExpression(
  name: String,
  type: BridgeType.FlowSource,
  slots: ForwardAsyncSlots,
  // See [asyncCallbackExpression].
  holderBuilt: Boolean = false,
): String {
  val taken: MutableSet<String> = (slots.declared + slots.shared.values + name + slots.holder).toMutableSet()
  fun mint(base: String): String = freshName(base, taken).also { taken += it }
  val holder: String = slots.holder
  val flow: String = mint("${name}Flow")
  val box: String = mint("${name}Box")
  val value: String = mint("${name}Value")
  val wire: String = type.element.asyncKotlinWire()
  val read: String = "nugetCallbackCall { nugetErr -> " +
      "${slots.end}.reinterpret<$CFUNCTION<($POINTER, $POINTER?) -> $wire>>()" +
      ".invoke(nugetEnumeration, nugetErr) }"
  return buildString {
    appendLine("run {")
    if (!holderBuilt) appendLine("  val $holder = ${asyncCallbackHolderConstruction(slots)}")
    appendLine("  val $flow: ${type.kotlinFlowType()} = $RUNTIME.flowForKotlin(")
    appendLine(
      "    release = { nugetTask -> ${slots.releaseTask}" +
          ".reinterpret<$CFUNCTION<($POINTER) -> Unit>>().invoke(nugetTask) },"
    )
    appendLine(
      "    enumerate = { nugetCallbackCall { nugetErr -> ${slots.begin}" +
          ".reinterpret<$CFUNCTION<($POINTER, $POINTER?) -> $POINTER?>>()" +
          ".invoke($holder.key, nugetErr) }!! },"
    )
    appendLine(
      "    moveNextBegin = { nugetEnumeration, nugetCallback, nugetCtx -> " +
          "nugetCallbackCall { nugetErr -> ${slots.moveNextBegin}" +
          ".reinterpret<$CFUNCTION<($POINTER, $POINTER, $POINTER, $POINTER?) -> Unit>>()" +
          ".invoke(nugetEnumeration, nugetCallback, nugetCtx, nugetErr) } },"
    )
    appendLine(
      "    moveNextEnd = { nugetTask -> nugetCallbackCall { nugetErr -> ${slots.moveNextEnd}" +
          ".reinterpret<$CFUNCTION<($POINTER, $POINTER?) -> Int>>()" +
          ".invoke(nugetTask, nugetErr) } != 0 },"
    )
    when (val element: BridgeType = type.element) {
      is BridgeType.Primitive ->
        if (element.kind == PrimitiveKind.BOOLEAN) {
          appendLine("    current = { nugetEnumeration -> $read != 0.toByte() },")
        } else {
          appendLine("    current = { nugetEnumeration -> $read },")
        }

      BridgeType.String -> {
        // The Current thunk boxes the string with `WrapString`; this side reads it, then releases
        // the box through the counted registry (ADR-036's return-box rule).
        appendLine("    current = { nugetEnumeration ->")
        appendLine("      val $box = $read!!")
        appendLine("      val $value = $box.asStableRef<String>().get()")
        appendLine("      NugetHandles.release($box)")
        appendLine("      $value")
        appendLine("    },")
      }

      BridgeType.Unit,
      BridgeType.Char,
      BridgeType.Instant,
      BridgeType.Duration,
      is BridgeType.Throwable,
      BridgeType.Uuid,
      is BridgeType.Enum,
      is BridgeType.ObjectHandle,
      is BridgeType.Interface,
      is BridgeType.BoundInterface,
      is BridgeType.ValueClass,
      BridgeType.ByteArray,
      is BridgeType.Collection,
      is BridgeType.Nullable,
      is BridgeType.Callback,
      is BridgeType.AsyncCallback,
      is BridgeType.FlowSource,
      is BridgeType.ReturnedLambda,
      is BridgeType.SpecializedProtocol,
      is BridgeType.RawKSType,
      is BridgeType.Unsupported,
      is BridgeType.RawCollection,
      is BridgeType.TypeParameter,
        -> error("ADR-210 admits no Flow parameter element $element")
    }
    appendLine(
      "    dispose = { nugetEnumeration, nugetCancelled -> ${slots.disposeEnumeration}" +
          ".reinterpret<$CFUNCTION<($POINTER, Int) -> Unit>>()" +
          ".invoke(nugetEnumeration, if (nugetCancelled) 1 else 0) },"
    )
    appendLine("  )")
    appendLine("  $flow")
    append("}")
  }
}

/** The Kotlin expression for either asynchronous parameter kind. */
internal fun asyncParameterExpression(
  name: String,
  type: BridgeType,
  slots: ForwardAsyncSlots,
  holderBuilt: Boolean = false,
): String = when (type.asyncKind()) {
  ForwardAsyncKind.LAMBDA ->
    asyncCallbackExpression(name, type as BridgeType.AsyncCallback, slots, holderBuilt)

  ForwardAsyncKind.FLOW -> flowSourceExpression(name, type as BridgeType.FlowSource, slots, holderBuilt)
  null -> error("ADR-210 has no asynchronous parameter lowering for $type")
}

/** The Kotlin type of either asynchronous parameter kind, as the member declares it. */
internal fun asyncParameterKotlinType(type: BridgeType): String = when (type.asyncKind()) {
  ForwardAsyncKind.LAMBDA -> (type as BridgeType.AsyncCallback).kotlinFunctionType()
  ForwardAsyncKind.FLOW -> (type as BridgeType.FlowSource).kotlinFlowType()
  null -> error("ADR-210 has no asynchronous parameter type for $type")
}
