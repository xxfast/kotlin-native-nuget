package io.github.xxfast.kotlin.native.nuget.processor.forward

import io.github.xxfast.kotlin.native.nuget.processor.RESULT_FAILED_SLOT
import io.github.xxfast.kotlin.native.nuget.processor.csharpParameterName
import io.github.xxfast.kotlin.native.nuget.processor.freshName
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirDoc
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirDllImport
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirConstructor
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirInterfaceMethod
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirMember
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirMethod
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirParameter
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirProperty
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirTypeParameter
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirValueClassBoxing
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirValueClassConstructor
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirVisibility
import io.github.xxfast.kotlin.native.nuget.processor.cir.KOTLIN_HANDLE

/** Projects the direct-value migration slice of a callable plan into CIR. */
internal object ForwardCirPlanProjection {
  /**
   * Value-class constructor: public parameters map 1:1, CreateChecked body unwraps String via
   * PtrToStringUTF8 when the underlying type is String (ADR-014 / ADR-035).
   */
  fun valueClassConstructor(
    plan: ForwardCallablePlan,
    nativeSuffix: String,
    underlyingIsString: Boolean,
  ): CirValueClassConstructor {
    require(plan.invocation.origin == ForwardCallableOrigin.VALUE_CLASS) {
      "Forward CIR value-class constructor projection received ${plan.invocation.origin}"
    }
    val publicParams: List<CirParameter> = plan.publicParameters()
    val paramNames: String = publicParams.joinToString(", ") { it.name }
    val result: BridgeType = plan.publicSignature.result
    val body: String = when {
      underlyingIsString -> "Marshal.PtrToStringUTF8(CreateChecked$nativeSuffix($paramNames))!"
      // ADR-077: an enum underlying crosses as its int ordinal; cast it back to the enum for the
      // struct member assignment.
      result is BridgeType.Enum -> "(${result.csharpType})CreateChecked$nativeSuffix($paramNames)"
      // ADR-035's 2026-09-11 amendment: a reference underlying comes back as a fresh handle, so
      // the secondary rebuilds it (ADR-105's `new T(...)` / `T.FromHandle(...)`) and hands it to
      // the positional record constructor it delegates to.
      result is BridgeType.ObjectHandle ->
        result.handleReconstruction("CreateChecked$nativeSuffix($paramNames)")
      else -> "CreateChecked$nativeSuffix($paramNames)"
    }
    val nativeCall: ForwardNativeCall = plan.nativeExports.single()
    return CirValueClassConstructor(
      parameters = publicParams,
      nativeName = nativeCall.exportName,
      body = body,
      nativeSuffix = nativeSuffix,
      // ADR-077: the DllImport and its call site follow the plan's wire shape, so an enum
      // parameter imports as `int` and is passed as `(int)name`, matching the Kotlin export.
      nativeParameters = plan.nativeInCirParameters(nativeCall.parameters),
      nativeArguments = plan.publicSignature.parameters.flatMap { plan.callArgument(it) },
      // ADR-150 amendment: the same `cirDoc()` every other planned callable projects, so a value
      // class's constructor documents itself exactly as an ordinary class's does.
      doc = plan.publicSignature.cirDoc(),
    )
  }

  /**
   * ADR-171: the box/unbox externs and helper bodies, both off the plans so the DllImports carry
   * exactly the wire the Kotlin exports declare. The box lowers the struct to its underlying wire
   * the way an ordinary value-class parameter does ([callArgument]); the unbox rebuilds it the way
   * an ordinary value-class result does ([valueClassReconstructionCs], so a sealed-base underlying
   * goes through its `FromHandle` discriminator).
   */
  fun valueClassBoxing(
    box: ForwardCallablePlan,
    unbox: ForwardCallablePlan,
    libraryName: String,
  ): CirValueClassBoxing {
    require(
      box.invocation.origin == ForwardCallableOrigin.VALUE_CLASS_BOX &&
          unbox.invocation.origin == ForwardCallableOrigin.VALUE_CLASS_BOX
    ) { "Forward CIR value-class boxing projection received ${box.invocation.origin}" }
    val type: BridgeType.ValueClass = unbox.publicSignature.result as BridgeType.ValueClass
    val boxCall: ForwardNativeCall = box.singleNativeImport()
    val unboxCall: ForwardNativeCall = unbox.singleNativeImport()
    val unboxWire: String = valueClassUnderlyingWireCs(type.underlying)
    val boxParameter: ForwardPublicParameter = box.publicSignature.parameters.single()
    return CirValueClassBoxing(
      boxImport = CirDllImport(
        libraryName = libraryName,
        entryPoint = boxCall.exportName,
        returnType = "IntPtr",
        name = "Native_NugetBox",
        parameters = box.nativeInCirParameters(boxCall.parameters),
        visibility = CirVisibility.PRIVATE,
        hasSyncErrorOut = box.errorSlot != null,
      ),
      unboxImport = CirDllImport(
        libraryName = libraryName,
        entryPoint = unboxCall.exportName,
        returnType = unboxWire,
        name = "Native_NugetUnbox",
        parameters = unbox.nativeInCirParameters(unboxCall.parameters),
        visibility = CirVisibility.PRIVATE,
        hasSyncErrorOut = unbox.errorSlot != null,
      ),
      boxParameter = boxParameter.csharpName,
      boxArguments = box.callArgument(boxParameter),
      unboxResult = valueClassReconstructionCs(type, "nativeResult"),
    )
  }

  /** Value-class computed property getter — no errorOut (shipped ABI). */
  fun valueClassProperty(plan: ForwardCallablePlan, nativeReceiverArg: String): CirProperty {
    require(plan.invocation.origin == ForwardCallableOrigin.VALUE_CLASS) {
      "Forward CIR value-class property projection received ${plan.invocation.origin}"
    }
    val nativeName: String = "Native_Get${plan.publicSignature.name}"
    val propName: String = plan.invocation.symbol.substringAfterLast('.')
    val (returnType, nativeReturnType, expression) = valueClassMemberExpression(
      plan = plan,
      nativeName = nativeName,
      callArguments = nativeReceiverArg,
    )
    return CirProperty(
      name = plan.publicSignature.name,
      type = returnType,
      nativeReturnType = nativeReturnType,
      nativeName = propName,
      getter = expression,
      doc = plan.publicSignature.cirDoc(),
    )
  }

  /** Value-class method — parameters included; no errorOut (shipped ABI). */
  fun valueClassMethod(plan: ForwardCallablePlan, nativeReceiverArg: String): CirMethod {
    require(plan.invocation.origin == ForwardCallableOrigin.VALUE_CLASS) {
      "Forward CIR value-class method projection received ${plan.invocation.origin}"
    }
    val publicParams: List<CirParameter> = plan.publicParameters()
    val argumentList: List<String> = listOf(nativeReceiverArg) +
        plan.publicSignature.parameters.flatMap { parameter -> plan.callArgument(parameter) }
    // ADR-082 overload numbering: the symbol's last segment carries the `_2` suffix, the public
    // name does not (the C# surface is one natural overload set). The `DllImport` name must follow
    // the *numbered* name, or two overloads that happen to share a wire shape would declare the
    // same extern twice (CS0111). Unsuffixed members render exactly as before.
    val methodName: String = plan.invocation.symbol.substringAfterLast('.')
    val nativeName: String = "Native_${methodName.replaceFirstChar { it.uppercase() }}"
    val (returnType, nativeReturnType, expression) = valueClassMemberExpression(
      plan = plan,
      nativeName = nativeName,
      callArguments = argumentList.joinToString(", "),
    )
    val needsCustomParams: Boolean =
      plan.publicSignature.parameters.any { parameter -> !parameter.type.isTrivialInput() }
    val nativeParams: List<CirParameter>? = if (needsCustomParams) {
      plan.nativeInCirParameters(
        plan.nativeExports.single().parameters.filter { parameter ->
          parameter.role == ForwardAbiRole.USER &&
              parameter.direction == ForwardAbiDirection.IN
        },
      )
    } else {
      null
    }
    return CirMethod(
      name = plan.publicSignature.name,
      returnType = returnType,
      nativeReturnType = nativeReturnType,
      nativeName = methodName,
      parameters = publicParams,
      body = expression,
      isSyncErrorCheckEnabled = plan.errorSlot != null,
      nativeParameters = nativeParams,
      doc = plan.publicSignature.cirDoc(),
    )
  }

  private fun valueClassMemberExpression(
    plan: ForwardCallablePlan,
    nativeName: String,
    callArguments: String,
  ): Triple<String, String, String> {
    val result: BridgeType = plan.publicSignature.result
    val wireType: ForwardAbiWireType = plan.result.wireType
    val call = "$nativeName($callArguments)"
    return when (result) {
      BridgeType.Unit -> Triple("void", "void", call)
      BridgeType.String -> Triple(
        "string",
        "IntPtr",
        "Marshal.PtrToStringUTF8($call)!",
      )

      is BridgeType.Enum -> Triple(
        result.csharpType(),
        "int",
        "($call)", // ordinal returned; public type cast applied at call site if needed
      ).let { (ret, native, _) ->
        Triple(ret, native, "(${result.csharpType()})$call")
      }

      else -> Triple(result.csharpType(), wireType.csharpType(), call)
    }
  }

  fun constructor(plan: ForwardCallablePlan, nativeSuffix: String = ""): CirConstructor {
    // ADR-157: a boxed enum arm's constructor is a constructor here in every respect -- one
    // public parameter, an owned handle result, `_handle = ...` -- and differs only in the Kotlin
    // invocation, which this half never sees.
    require(
      plan.invocation.origin == ForwardCallableOrigin.CONSTRUCTOR ||
          plan.invocation.origin == ForwardCallableOrigin.ENUM_ARM_BOX,
    ) {
      "Forward CIR constructor projection received ${plan.invocation.origin}"
    }
    val nativeCall: ForwardNativeCall = plan.singleNativeImport()
    // ADR-141: an inner class's constructor is receiver-carrying, exactly like [extension]'s. The
    // receiver becomes public parameter zero (`Hearth outer`) AND rides at the head of the input
    // list every projection step below walks. Both, never only the first: with the receiver in
    // `publicParams` alone, a primitive-only inner constructor stays on the trivial path and
    // `renderConstructor` hands `Native_Create` the `Hearth` itself where an `IntPtr` slot is
    // (CS1503 at the consumer's compile). Sharing the list is also what gives the receiver its
    // `outer._handle` lowering through `callArgument`.
    val receiver: ForwardAbiParameter? = nativeCall.parameters.firstOrNull()
      ?.takeIf { parameter -> parameter.role == ForwardAbiRole.RECEIVER }
    val receiverInput: List<ForwardPublicParameter> = listOfNotNull(receiver)
      .map { parameter -> ForwardPublicParameter(parameter.name, parameter.transfer.type) }
    val inputs: List<ForwardPublicParameter> = receiverInput + plan.publicSignature.parameters
    val publicParams: List<CirParameter> = listOfNotNull(receiver).map { parameter ->
      CirParameter(
        parameter.csharpName,
        parameter.transfer.type.csharpType(),
        parameter.wireType.csharpType(),
      )
    } + plan.publicParameters()
    val needsCustomParams: Boolean =
      inputs.any { parameter -> !parameter.isTrivialInput() }
    if (!needsCustomParams) {
      return CirConstructor(
        parameters = publicParams,
        body = "",
        hasErrorCheck = true,
        nativeSuffix = nativeSuffix,
        doc = plan.publicSignature.cirDoc(),
      )
    }
    val prelude: List<ForwardCirHandleStep> =
      inputs.mapNotNull { parameter -> parameter.optionalPrelude() } +
          inputs.unwrapped().mapNotNull { parameter ->
            plan.bytesPrelude(parameter)
              ?: plan.collectionPrelude(parameter)
              ?: plan.interfacePrelude(parameter)
              ?: plan.boundInterfacePrelude(parameter)
              ?: plan.typeParameterPrelude(parameter)
          }
    val cleanup: List<String> =
      inputs.unwrapped().mapNotNull {
        plan.bytesCleanup(it) ?: plan.collectionCleanup(it) ?: plan.interfaceCleanup(it)
          ?: plan.typeParameterCleanup(it)
      }
    val argumentList: List<String> = inputs.flatMap { plan.inputArguments(it) }
    val callArgs: String = (argumentList + "out IntPtr error").joinToString(", ")
    val body: String = forwardCirHandleScope(
      prelude,
      cleanup,
      core = buildString {
        appendLine("            IntPtr handle = Native_Create$nativeSuffix($callArgs);")
        appendErrorCheck()
        append("            _handle = new NugetKotlinHandle(handle);")
      },
    )
    return CirConstructor(
      parameters = publicParams,
      body = body,
      hasErrorCheck = false,
      nativeSuffix = nativeSuffix,
      nativeParameters = plan.nativeInCirParameters(nativeCall.parameters),
      doc = plan.publicSignature.cirDoc(),
    )
  }

  fun static(plan: ForwardCallablePlan, libraryName: String): List<CirMember> {
    require(
      plan.invocation.origin in setOf(
        ForwardCallableOrigin.TOP_LEVEL,
        ForwardCallableOrigin.OBJECT,
        ForwardCallableOrigin.COMPANION,
      )
    ) { "Forward CIR static projection received ${plan.invocation.origin}" }
    val nativeCall: ForwardNativeCall = plan.singleNativeImport()
    val publicParams: List<CirParameter> = plan.publicParameters()
    val identifier: String = plan.publicSignature.name + plan.overloadSuffix()
    val nativeName: String = if (plan.invocation.origin == ForwardCallableOrigin.COMPANION) {
      "Native_Companion_$identifier"
    } else {
      "Native_$identifier"
    }
    val result: CirResultProjection = plan.resultProjection(
      nativeName = nativeName,
      parameters = plan.publicSignature.parameters,
    )
    val method = CirMethod(
      name = plan.publicSignature.name,
      returnType = result.returnType,
      nativeReturnType = result.nativeReturnType,
      nativeName = nativeName,
      parameters = publicParams.map { parameter -> parameter.copy(nativeType = parameter.type) },
      body = result.body,
      isStatic = true,
      typeParameters = plan.cirTypeParameters(isOverride = false),
      isSyncErrorCheckEnabled = !result.hasCustomBody && plan.errorSlot != null,
      hasCustomBody = result.hasCustomBody,
      doc = plan.publicSignature.cirDoc(),
    )
    return listOf(
      CirDllImport(
        libraryName = libraryName,
        entryPoint = nativeCall.exportName,
        returnType = result.nativeReturnType,
        name = nativeName,
        parameters = plan.nativeInCirParameters(nativeCall.parameters) + plan.nativeOutCirParameters(nativeCall),
        visibility = CirVisibility.PRIVATE,
        hasSyncErrorOut = plan.errorSlot != null,
      ),
      method.copy(
        tryOverload = plan.resultTry(method) { names ->
          plan.resultProjection(nativeName, plan.publicSignature.parameters, tryNames = names)
        },
      ),
    )
  }

  fun classMethod(
    plan: ForwardCallablePlan,
    nativePrefix: String,
    isOverride: Boolean,
    isVirtual: Boolean = false,
  ): CirMethod {
    val nativeCall: ForwardNativeCall = plan.singleNativeImport()
    val receiver: ForwardAbiParameter = nativeCall.parameters.firstOrNull()
      ?: error("Forward CIR plan ${plan.invocation.symbol} has no receiver")
    val isHandleReceiver: Boolean =
      receiver.role == ForwardAbiRole.RECEIVER && receiver.wireType == ForwardAbiWireType.POINTER
    require(isHandleReceiver) {
      "Forward CIR class plan ${plan.invocation.symbol} must begin with a handle receiver"
    }

    val publicParams: List<CirParameter> = plan.publicParameters()
    val nativeName: String = nativeCall.exportName.removePrefix("${nativePrefix}_")
    require(nativeName != nativeCall.exportName) {
      "Forward CIR class plan ${plan.invocation.symbol} export ${nativeCall.exportName} " +
          "does not begin with $nativePrefix"
    }

    // ADR-090 overload numbering: the symbol's last segment carries the `_2` suffix, the public
    // name does not (the C# surface is one natural overload set). The `DllImport` name must follow
    // the *numbered* name, or two overloads that happen to share a wire shape (an `Int` and an
    // enum parameter both cross as `int`) would declare the same extern twice (CS0111). Unsuffixed
    // members render exactly as before.
    val externName: String =
      "Native_" + plan.invocation.symbol.substringAfterLast('.').replaceFirstChar { it.uppercase() }
    val result: CirResultProjection = plan.resultProjection(
      nativeName = externName,
      parameters = plan.publicSignature.parameters,
      receiverArgument = "_handle",
    )
    val needsCustomParams: Boolean =
      plan.publicSignature.parameters.any { parameter -> !parameter.isTrivialInput() }
    val nativeParams: List<CirParameter>? = if (needsCustomParams) {
      plan.nativeInCirParameters(nativeCall.parameters.drop(1))
    } else {
      null
    }
    val method = CirMethod(
      name = plan.publicSignature.name,
      returnType = result.returnType,
      nativeReturnType = result.nativeReturnType,
      nativeName = nativeName,
      externName = externName,
      parameters = publicParams,
      body = result.body,
      isOverride = isOverride,
      isVirtual = isVirtual,
      typeParameters = plan.cirTypeParameters(isOverride),
      // Unlike static/extension (which hand-build their own CirDllImport), the DllImport here is
      // derived generically by CirClass.methodNativeImport from this CirMethod, and its trailing
      // `out IntPtr error` must be present whether or not the *body* is hand-written, so this is
      // deliberately not gated by `!result.hasCustomBody` the way static/extension are.
      isSyncErrorCheckEnabled = plan.errorSlot != null,
      extraNativeParams = plan.nativeOutDeclarationParameters(nativeCall),
      hasCustomBody = result.hasCustomBody,
      nativeParameters = nativeParams,
      doc = plan.publicSignature.cirDoc(),
    )
    // The twin rides on the method rather than beside it in the member list, so the one extern
    // this method's import is derived from stays the only one (a second CirMethod would mint a
    // second `[DllImport]` through `methodNativeImport`).
    return method.copy(
      tryOverload = plan.resultTry(method) { names ->
        plan.resultProjection(
          externName, plan.publicSignature.parameters, receiverArgument = "_handle",
          tryNames = names,
        )
      },
    )
  }

  fun extension(
    plan: ForwardCallablePlan,
    libraryName: String,
    // ADR-006 amendment: the C#-only spelling of the `this` parameter (`mood` on an enum member
    // function, matching the enum's member properties). The ABI keeps the plan-owned `receiver`,
    // so only the C# half renames it, and a declared parameter already spelled that way keeps the
    // plan's name instead of a duplicate.
    receiverName: String? = null,
  ): List<CirMember> {
    val nativeCall: ForwardNativeCall = plan.singleNativeImport()
    val receiver: ForwardAbiParameter =
      nativeCall.parameters.firstOrNull { parameter -> parameter.role == ForwardAbiRole.RECEIVER }
        ?: error("Forward CIR extension plan ${plan.invocation.symbol} has no receiver")
    val declared: List<CirParameter> = plan.publicParameters()
    val publicReceiverName: String = receiverName
      ?.takeIf { name -> declared.none { parameter -> parameter.name == name } }
      ?: receiver.name
    // ADR-132 amendment: the public receiver comes off the plan, which keeps its declared
    // (`int?`, `Mood?`, ...) type and minted flag; the value slot carries only the inner type.
    val publicReceiver: ForwardPublicParameter = plan.publicSignature.receiver
      ?: ForwardPublicParameter(receiver.name, receiver.transfer.type)
    val receiverType: String = publicReceiver.type.csharpType()
    val receiverParam = CirParameter(
      publicReceiverName.csharpParameterName(),
      receiverType,
      receiver.wireType.csharpType(),
      // ADR-034: `int?` is `Nullable<int>`, a distinct C# signature from `int`, so the ADR-095
      // `Int` / `Int?` receiver twin must not be normalized into a collision.
      isReferenceType = publicReceiver.type.isCSharpReferenceType(),
    )
    val publicParams: List<CirParameter> = listOf(receiverParam) + declared
    val nativeName: String = "Native_${plan.publicSignature.name}${plan.overloadSuffix()}"
    // ADR-132: the receiver is parameter zero. Instead of a hand-rolled `receiverArgument` string
    // (which had an `else -> "receiver"` arm that handed the extern an `IPet`/`CatId?` where it
    // wanted an `IntPtr`/`string`, CS1503) and a hand-listed `needsCustomParams` gate, it enters
    // [resultProjection] as a [ForwardPublicParameter] at the head of the input list. That makes it
    // share `callArgument` (including two-slot fan-outs), all three preludes and both cleanups with
    // every declared parameter — so an interface receiver finally gets its ADR-084 stage-3
    // `HandleOf` / `Dispose` lifecycle, and a collection receiver its `CreateList` handle. The
    // custom-body gate now follows from the same list: `parameters.any { !isTrivialInput() }`
    // inside [resultProjection] covers the receiver too, so no `forceCustomBody` is needed here.
    // The three shapes the old `when` handled render byte-identically: `callArgument` emits the
    // same `receiver._handle`, value-class unwrap, and `receiver?._handle ?? IntPtr.Zero`.
    val receiverInput = ForwardPublicParameter(
      publicReceiverName, publicReceiver.type, hasValueSlot = publicReceiver.hasValueSlot,
    )
    val result: CirResultProjection = plan.resultProjection(
      nativeName = nativeName,
      parameters = listOf(receiverInput) + plan.publicSignature.parameters,
    )

    val nativeImport = CirDllImport(
      libraryName = libraryName,
      entryPoint = nativeCall.exportName,
      returnType = result.nativeReturnType,
      name = nativeName,
      parameters = plan.nativeInCirParameters(nativeCall.parameters) + plan.nativeOutCirParameters(nativeCall),
      visibility = CirVisibility.PRIVATE,
      hasSyncErrorOut = plan.errorSlot != null,
    )
    val wrapper = CirMethod(
      name = plan.publicSignature.name,
      returnType = result.returnType,
      nativeReturnType = result.nativeReturnType,
      nativeName = nativeName,
      parameters = publicParams.map { parameter -> parameter.copy(nativeType = parameter.type) },
      body = result.body,
      isStatic = true,
      isExtension = true,
      isSyncErrorCheckEnabled = !result.hasCustomBody && plan.errorSlot != null,
      hasCustomBody = result.hasCustomBody,
      // ADR-150: the `this` receiver is a C# parameter of this member but not of the plan, so it
      // has to be named here or a documented parameter beside it is CS1573.
      doc = plan.publicSignature.cirDoc(listOf(receiverParam.name)),
    )
    val tryOverload: CirMethod? = plan.resultTry(wrapper) { names ->
      plan.resultProjection(
        nativeName, listOf(receiverInput) + plan.publicSignature.parameters, tryNames = names,
      )
    }
    return listOf(nativeImport, wrapper.copy(tryOverload = tryOverload))
  }

  /**
   * The non-throwing `TryX` twin of [method], or null when the plan does not unwrap a `Result`
   * (ADR-108). It shares [method]'s extern and its preludes, cleanups and call; only the error
   * check and the exits differ ([CirTryNames]): a set error slot with the failure flag set returns
   * `false` with the exception, one with the flag clear (the Kotlin body threw) still throws.
   *
   * Leading parameters lose their C# defaults: an `out` parameter after an optional one is
   * CS1737. Inheritance modifiers are read off the parent at render time, because translators
   * adjust those after projection.
   */
  private fun ForwardCallablePlan.resultTry(
    method: CirMethod,
    body: (CirTryNames) -> CirResultProjection,
  ): CirMethod? {
    if (!invocation.unwrapsKotlinResult || errorSlot == null) return null
    val result: BridgeType = publicSignature.result
    val leading: List<CirParameter> = method.parameters.map { it.copy(defaultValue = null) }
    val names: CirTryNames =
      CirTryNames.of(leading.map { it.name }, hasValue = result != BridgeType.Unit)
    val projection: CirResultProjection = body(names)
    return method.copy(
      name = "Try${method.name}",
      returnType = "bool",
      parameters = leading + resultTryOutParameters(names, method.returnType, result),
      body = projection.body,
      isSyncErrorCheckEnabled = false,
      hasCustomBody = true,
      doc = null,
      tryOverload = null,
    )
  }

  /**
   * The interface declaration's `TryX` twin, as a default interface method over the throwing
   * member: an implementing Kotlin-backed class declares its own (which reads the failure flag),
   * and a C#-implemented interface keeps compiling without one. There, nothing models a
   * `Result.failure`, so any exception the C# implementation throws propagates, as on a thrown
   * Kotlin exception.
   */
  fun interfaceResultTry(
    plan: ForwardCallablePlan,
    method: CirInterfaceMethod,
  ): CirInterfaceMethod? {
    if (!plan.invocation.unwrapsKotlinResult || plan.errorSlot == null) return null
    val result: BridgeType = plan.publicSignature.result
    val leading: List<CirParameter> = method.parameters.map { it.copy(defaultValue = null) }
    val names: CirTryNames =
      CirTryNames.of(leading.map { it.name }, hasValue = result != BridgeType.Unit)
    val call = "${method.identifier}(${leading.joinToString(", ") { it.name }})"
    val exit = CirBodyExit(names)
    val statements: String =
      if (names.value == null) "$call;\n            ${exit.completing()}" else exit.returning(call)
    val body: String = "\n            $statements"
    return method.copy(
      name = "Try${method.name}",
      returnType = "bool",
      parameters = leading + resultTryOutParameters(names, method.returnType, result),
      doc = null,
      body = body,
      tryOverload = null,
    )
  }

  /**
   * The abstract `TryX` twin of an abstract `Result<T>` member, which has no plan (the planner
   * skips abstract members): [payload] is the unwrapped `T`, [method] the abstract declaration.
   */
  fun abstractResultTry(method: CirMethod, payload: BridgeType): CirMethod {
    val names: CirTryNames =
      CirTryNames.of(method.parameters.map { it.name }, hasValue = payload != BridgeType.Unit)
    return method.copy(
      name = "Try${method.name}",
      returnType = "bool",
      parameters = method.parameters + resultTryOutParameters(names, method.returnType, payload),
      tryOverload = null,
    )
  }

  /**
   * The `out T value` (absent for `Unit`) and `out Exception? failure` a `TryX` twin ends with.
   * `value` takes `[MaybeNullWhen(false)]` when [valueType] is a non-nullable reference (or an
   * unconstrained `T`), so a caller's true arm reads it without a null check.
   */
  fun resultTryOutParameters(
    names: CirTryNames,
    valueType: String,
    result: BridgeType,
  ): List<CirParameter> = buildList {
    val value: String? = names.value
    if (value != null) {
      // A nullable-bounded bare `T` is spelled `T` but may hold null like `T?` (ADR-147
      // amendment), so its failure path's `default` needs the attribute too (CS8601 otherwise).
      val nullableInner: BridgeType? = (result as? BridgeType.Nullable)?.type
      val bareNullableBound: Boolean =
        nullableInner is BridgeType.TypeParameter && nullableInner.nullableFromBound
      val maybeNull: Boolean = bareNullableBound || result !is BridgeType.Nullable &&
          (result.isCSharpReferenceType() || result is BridgeType.TypeParameter)
      val attribute: String = if (maybeNull) "$MAYBE_NULL_WHEN_FALSE " else ""
      add(CirParameter(value, "${attribute}out $valueType"))
    }
    add(CirParameter(names.failure, "$NOT_NULL_WHEN_FALSE out global::System.Exception?"))
  }

  /**
   * ADR-197: the member's own type parameters as C# declares them. A declaring member carries
   * ADR-015's constraints. An `override` restates none (CS0460: they are inherited), except that
   * a type parameter it spells `T?` says `where T : default`, without which C# reads `T?` in an
   * override as `Nullable<T>` (CS0115, CS0453).
   */
  private fun ForwardCallablePlan.cirTypeParameters(isOverride: Boolean): List<CirTypeParameter> =
    publicSignature.typeParameters.map { parameter ->
      val constraints: List<String> = when {
        !isOverride -> parameter.constraints
        spellsNullable(parameter.name) -> listOf("default")
        else -> emptyList()
      }
      CirTypeParameter(parameter.name, constraints)
    }

  /** Whether any public position of this plan spells the type parameter [name] as `T?`. */
  private fun ForwardCallablePlan.spellsNullable(name: String): Boolean {
    fun BridgeType.spells(): Boolean = when (this) {
      is BridgeType.Nullable -> {
        val inner: BridgeType = type
        (inner is BridgeType.TypeParameter && inner.name == name && !inner.nullableFromBound) ||
            inner.spells()
      }
      else -> false
    }
    return publicSignature.parameters.any { parameter -> parameter.type.spells() } ||
        publicSignature.result.spells()
  }

  /**
   * ADR-095: the `_$n` an overload's plan symbol carries, or "" for the first (or only) namesake.
   *
   * The private extern name must carry it as well as the DllImport EntryPoint: two overloads can
   * share one wire shape (an `Int` and an enum parameter both cross as `int`), and one extern name
   * declared twice is CS0111. Derived from the symbol tail minus the bare declared name, so an
   * unnumbered callable renders byte-identically to before.
   */
  private fun ForwardCallablePlan.overloadSuffix(): String {
    val member: String = invocation.member ?: return ""
    val tail: String = invocation.symbol.substringAfterLast('.')
    return if (tail.startsWith(member)) tail.substring(member.length) else ""
  }

  private fun ForwardCallablePlan.singleNativeImport(): ForwardNativeCall {
    require(evaluation == ForwardEvaluation.EXACTLY_ONCE && nativeImports.size == 1) {
      "Forward CIR projection only supports exactly-once plans: ${invocation.symbol}"
    }
    return nativeImports.single()
  }

  /** The public C# parameter list, one entry per declared [ForwardPublicParameter] — independent
   * of the native ABI's shape (which may fan a single public parameter into several native ones,
   * or dispose of a materialized handle the public type never mentions).
   */
  /** ADR-164: the public parameters, for the interface declaration, which renders no body. */
  fun interfaceParameters(plan: ForwardCallablePlan): List<CirParameter> = plan.publicParameters()

  private fun ForwardCallablePlan.publicParameters(): List<CirParameter> =
    publicSignature.parameters.map { parameter ->
      // ADR-164: a widened parameter's type is already its nullable form; an already-nullable one
      // is wrapped in the generated `Optional<T>` struct, a value type.
      val type: String = parameter.type.csharpType()
      CirParameter(
        name = parameter.csharpName,
        type = when (parameter.default?.encoding) {
          ForwardDefaultEncoding.OPTIONAL -> "global::Kotlin.Native.Interop.KotlinOptional<$type>"
          ForwardDefaultEncoding.PRESENCE -> "$type?"
          else -> type
        },
        isReferenceType = !parameter.isOptional && parameter.type.isCSharpReferenceType(),
        defaultValue = when {
          parameter.default?.omittable != true -> null
          parameter.isOptional -> "default"
          else -> "null"
        },
      )
    }

  /**
   * ADR-164: an `Optional<T>` parameter's value, read once into a local named after the parameter
   * so every existing prelude, argument and cleanup works on it unchanged, and its `IsSet` slot
   * argument. The local is declared before any `try`, since reading `.Value` cannot fail. Its name
   * is the planner's [ForwardPublicParameter.optionalLocal], so a sibling user parameter spelled
   * `limitValue` keeps its name and the local moves.
   */
  private fun ForwardPublicParameter.optionalValue(): ForwardPublicParameter =
    copy(name = optionalLocal, default = null)

  private fun ForwardPublicParameter.optionalPrelude(): ForwardCirHandleStep? {
    if (!isOptional) return null
    val local = "var ${optionalValue().csharpName} = $csharpName.Value;"
    return ForwardCirHandleStep(flat = local, declarations = listOf(local), statement = "")
  }

  /** ADR-164: [parameters] as the preludes and cleanups see them, each `Optional<T>` unwrapped. */
  private fun List<ForwardPublicParameter>.unwrapped(): List<ForwardPublicParameter> =
    map { parameter -> if (parameter.isOptional) parameter.optionalValue() else parameter }

  /** ADR-164: an `Optional<T>` never passes straight through, whatever its inner type. */
  private fun ForwardPublicParameter.isTrivialInput(): Boolean = !isOptional && type.isTrivialInput()

  /** ADR-164: the call arguments of [parameter], led by the `IsSet` slot when it is optional. */
  private fun ForwardCallablePlan.inputArguments(parameter: ForwardPublicParameter): List<String> =
    when (parameter.default?.encoding) {
      ForwardDefaultEncoding.OPTIONAL ->
        listOf("${parameter.csharpName}.HasValue") + callArgument(parameter.optionalValue())
      // Unset, the callback's own slots still carry the static thunk and a zero ctx.
      ForwardDefaultEncoding.PRESENCE ->
        listOf("${parameter.csharpName} is not null") + callArgument(parameter)

      else -> callArgument(parameter)
    }

  /** The DllImport-only native parameter list: every native ABI IN parameter in [nativeParameters]
   * (already positioned correctly by the planner, including any nullable-primitive fan-out),
   * rendered at its wire type for both `type` and `nativeType` since DllImport declarations never
   * need a public/native distinction of their own — except a nullable String, whose wire type
   * (`STRING`) carries no nullability of its own: the DllImport parameter must be annotated
   * `string?` too, or passing the (correctly nullable) public value into it is a CS8604 under
   * nullable-reference analysis, even though the underlying marshaling is identical either way.
   */
  private fun ForwardCallablePlan.nativeInCirParameters(
    nativeParameters: List<ForwardAbiParameter>,
  ): List<CirParameter> = nativeParameters
    .filter { parameter -> parameter.direction == ForwardAbiDirection.IN }
    .map { native -> CirParameter(native.csharpName, native.nativeCsharpType()) }

  // ADR-077 sub-items 3/4 / ADR-106: the nullable-string-wire rule lives in `isNullableStringWire`,
  // shared with the property route's receiver import (ADR-132 2026-09-20) rather than copied.
  // ADR-187: a wrapper-handle slot is typed as the owned handle, so the call keeps it alive.
  private fun ForwardAbiParameter.nativeCsharpType(): String = when {
    transfer.type.isNullableStringWire() -> "string?"
    transfer.type.isKotlinHandleWire() -> KOTLIN_HANDLE
    else -> wireType.csharpType()
  }

  /** A parameter shape whose native ABI representation is identical to its public C# type — no
   * cast, fan-out, or prelude/cleanup statement required at the call site, so it can still flow
   * through the pre-existing generic (non-custom-body) rendering paths.
   */
  private fun BridgeType.isTrivialInput(): Boolean = when (this) {
    is BridgeType.Primitive, BridgeType.Char, BridgeType.String -> true
    is BridgeType.Nullable -> type == BridgeType.String
    else -> false
  }

  /** The call-site argument(s) for one public parameter. Every shape contributes exactly one
   * argument except a nullable primitive, which contributes two (`x.HasValue`,
   * `x.GetValueOrDefault()`) matching the planner's adjacent native fan-out. A collection
   * parameter's argument is the local handle variable built by [collectionPrelude], not the
   * parameter itself.
   */
  private fun ForwardCallablePlan.callArgument(parameter: ForwardPublicParameter): List<String> =
    when (val type = parameter.type) {
      is BridgeType.Primitive, BridgeType.Char, BridgeType.String -> listOf(parameter.csharpName)
      // ADR-106: the default "D" format is the lowercase hex-dash text `Uuid.parse` reads. Never
      // pass a format string here: "N" would still parse, "B"/"P"/"X" would not.
      BridgeType.Uuid -> listOf("${parameter.csharpName}.ToString()")
      is BridgeType.Enum -> listOf("(int)${parameter.csharpName}")
      // ADR-076: UtcTicks is load-bearing (verified) -- a consumer holding a non-UTC
      // DateTimeOffset must not send its wall-clock ticks.
      BridgeType.Instant -> listOf("${parameter.csharpName}.UtcTicks")
      // ADR-103: TimeSpan has one tick domain, so the plain `.Ticks` is unambiguous.
      BridgeType.Duration -> listOf("${parameter.csharpName}.Ticks")
      is BridgeType.ObjectHandle -> listOf("${parameter.csharpName}._handle")
      // ADR-147: the box [typeParameterPrelude] minted; the `finally` disposes it when owned.
      is BridgeType.TypeParameter -> listOf("${parameter.csharpLocal}Box")
      // ADR-040 sub-decision B: an interface-typed parameter's public static type is `IFoo`, which
      // does not carry `._handle` (that is only true of the generated `Foo` backing class). The
      // one shared reflective helper extracts it regardless of which concrete type implements
      // `IFoo`, and throws NotSupportedException for a C#-implemented (non-Kotlin-backed) one.
      // ADR-084 stage 3: the extraction moved into the prelude, because a C#-implemented value
      // mints a transfer handle that [interfaceCleanup] disposes once the crossing is done.
      is BridgeType.Interface -> listOf("${parameter.csharpLocal}Handle")
      // ADR-088: the transfer GCHandle allocated by [boundInterfacePrelude]. No `HandleOf`
      // reflection here: a bound interface's implementations are ordinary managed objects on this
      // side, Kotlin-backed or not, so the handle is simply an alloc over whatever came in.
      is BridgeType.BoundInterface -> listOf("${parameter.csharpLocal}Handle")
      is BridgeType.Collection -> listOf("${parameter.csharpLocal}Handle")
      // ADR-151: the handle [bytesPrelude] minted with `NugetMarshal.CreateBytes`.
      BridgeType.ByteArray -> listOf("${parameter.csharpLocal}Handle")
      // ADR-160: the two-slot ADR-102 pair -- the link-time thunk address, then the ADR-161 table
      // key [forwardCallbackPrelude] registered the managed delegate under.
      is BridgeType.Callback -> forwardCallbackArguments(parameter.csharpLocal, type)
      // ADR-077: the generated `readonly record struct` capitalizes the Kotlin underlying
      // property (`value` -> `Value`, CirClassTranslator); the unwrapped value is lowered to its
      // wire form per underlying (sub-item 4), and Kotlin re-wraps it on the other side.
      is BridgeType.ValueClass -> {
        val prop: String = type.underlyingPropertyName.replaceFirstChar { it.uppercase() }
        val unwrapped = "${parameter.csharpName}.$prop"
        listOf(
          when (type.underlying) {
            is BridgeType.Enum -> "(int)$unwrapped"
            is BridgeType.ObjectHandle -> "$unwrapped._handle"
            else -> unwrapped
          }
        )
      }

      is BridgeType.Nullable -> when (val inner = type.type) {
        BridgeType.String -> listOf(parameter.csharpName)
        // ADR-106: `Guid?` -- a null stays a null string, so the wire's null pointer is the null.
        BridgeType.Uuid -> listOf("${parameter.csharpName}?.ToString()")
        is BridgeType.ObjectHandle ->
          listOf("${parameter.csharpName}?._handle ?? NugetKotlinHandle.Null")
        is BridgeType.Interface -> listOf("${parameter.csharpLocal}Handle")
        // ADR-083/147: `Wrap<T>` already maps a null to `IntPtr.Zero`, so `T?` needs no guard.
        is BridgeType.TypeParameter -> listOf("${parameter.csharpLocal}Box")

        // ADR-098 amendment (boundary nullability part C): `char?` contributes the identical pair;
        // the value half stays a `char`, so the by-value slot keeps ADR-098's U2 marshalling.
        is BridgeType.Primitive, BridgeType.Char -> listOf(
          "${parameter.csharpName}.HasValue", "${parameter.csharpName}.GetValueOrDefault()",
        )
        // ADR-080: same HasValue/GetValueOrDefault pair, the value half lowered to the ordinal.
        is BridgeType.Enum -> listOf(
          "${parameter.csharpName}.HasValue", "(int)${parameter.csharpName}.GetValueOrDefault()",
        )

        // ADR-076: same HasValue/GetValueOrDefault pair as the nullable Primitive case above,
        // with the ticks conversion applied to the value half.
        BridgeType.Instant -> listOf(
          "${parameter.csharpName}.HasValue",
          "${parameter.csharpName}.GetValueOrDefault().UtcTicks",
        )

        // ADR-103: the same pair, the value half lowered to TimeSpan ticks.
        BridgeType.Duration -> listOf(
          "${parameter.csharpName}.HasValue", "${parameter.csharpName}.GetValueOrDefault().Ticks",
        )

        // ADR-075: a nullable collection *parameter* (e.g. a data class's `notes: List<String>?`
        // constructor parameter) shares [ForwardPropertyPlan]'s setter route exactly: the local
        // handle variable [collectionPrelude] built already folds the null check in, so the call
        // argument itself is unconditional either way.
        is BridgeType.Collection -> listOf("${parameter.csharpLocal}Handle")
        // ADR-151: the prelude folds the null into `IntPtr.Zero`, same as the collection arm.
        BridgeType.ByteArray -> listOf("${parameter.csharpLocal}Handle")
        // ADR-077 sub-items 3/4: null propagation into the pointer-shaped marshalling; a C# null
        // ships the null pointer (null string reference, or IntPtr.Zero for a handle underlying).
        // ADR-079: a Primitive/Enum underlying has no null pointer, so it contributes the same
        // HasValue/GetValueOrDefault pair as the nullable Primitive case above, with the value
        // half unwrapped to the underlying. `GetValueOrDefault()` on an empty Nullable<Dosage>
        // yields `default(Dosage)` (the validating constructor is bypassed), and Kotlin never
        // reads that dead value because HasValue is false.
        is BridgeType.ValueClass -> {
          val prop: String = inner.underlyingPropertyName.replaceFirstChar { it.uppercase() }
          if (inner.underlying is BridgeType.Primitive || inner.underlying is BridgeType.Enum) {
            val unwrapped = "${parameter.csharpName}.GetValueOrDefault().$prop"
            listOf(
              "${parameter.csharpName}.HasValue",
              if (inner.underlying is BridgeType.Enum) "(int)$unwrapped" else unwrapped,
            )
          } else {
            val unwrapped = "${parameter.csharpName}?.$prop"
            listOf(
              if (inner.underlying is BridgeType.ObjectHandle) {
                "$unwrapped._handle ?? NugetKotlinHandle.Null"
              } else {
                unwrapped
              }
            )
          }
        }

        else -> error("Forward CIR plan projection has no call argument for nullable $inner")
      }

      else -> error("Forward CIR plan projection has no call argument for $type")
    }

  /** [type] with one `Nullable` layer removed only when it wraps a `Collection`, alongside
   *  whether that layer was present — the same collection factory/dispose call applies either
   *  way, but a nullable source value needs the `!= null ? ... : IntPtr.Zero` guard around it. */
  private fun BridgeType.asNullableAwareCollection(): Pair<BridgeType.Collection, Boolean>? =
    when (this) {
      is BridgeType.Collection -> this to false
      is BridgeType.Nullable -> (type as? BridgeType.Collection)?.let { it to true }
      else -> null
    }

  /**
   * ADR-151: a `byte[]` argument crosses as one Kotlin-side handle, minted before the call by
   * `NugetMarshal.CreateBytes` (one `nuget_bytes_create` P/Invoke that copies the pinned managed
   * array) and disposed by [bytesCleanup] after it. `byte[]?` folds the null into `IntPtr.Zero`,
   * exactly as [collectionPrelude] does.
   */
  /**
   * ADR-160: the managed delegate plus its ADR-161 table key, declared before the `try` so
   * [callbackCleanup]'s `finally` can remove the key on every exit path (including a Kotlin
   * exception rethrown by the error check, which is exactly the leak the ADR-099 handle scope was
   * built for). Removing it on exit is also what makes a call that outlives this frame a lookup
   * MISS.
   */
  private fun ForwardCallablePlan.callbackPrelude(
    parameter: ForwardPublicParameter,
  ): ForwardCirHandleStep? {
    val type: BridgeType.Callback = parameter.type as? BridgeType.Callback ?: return null
    return forwardCallbackPrelude(
      parameter.csharpName, type,
      omittable = parameter.default?.encoding == ForwardDefaultEncoding.PRESENCE,
      local = parameter.csharpLocal,
    )
  }

  /**
   * A non-omittable callback is required: C# `null` has no Kotlin meaning, and left unchecked it
   * registers and surfaces as a `NullReferenceException` rethrown from inside the callback. The
   * guard leads the whole prelude (a declaration, so it lands before the `try` and before any
   * other parameter's handle is minted); an exception there leaves nothing to clean up. A
   * defaulted (ADR-164) lambda keeps `null` as "unset" under every encoding and takes no guard.
   */
  private fun callbackNullGuard(parameter: ForwardPublicParameter): ForwardCirHandleStep? {
    if (parameter.type !is BridgeType.Callback) return null
    if (parameter.default != null) return null
    val guard = "ArgumentNullException.ThrowIfNull(${parameter.csharpName});"
    return ForwardCirHandleStep(flat = guard, declarations = listOf(guard), statement = "")
  }

  private fun ForwardCallablePlan.callbackCleanup(parameter: ForwardPublicParameter): String? {
    if (parameter.type !is BridgeType.Callback) return null
    return forwardCallbackCleanup(parameter.csharpLocal)
  }

  private fun ForwardCallablePlan.bytesPrelude(
    parameter: ForwardPublicParameter,
  ): ForwardCirHandleStep? {
    if (parameter.type.unwrapNullable() != BridgeType.ByteArray) return null
    val nullable: Boolean = parameter.type is BridgeType.Nullable
    val name: String = parameter.csharpName
    val local: String = parameter.csharpLocal
    val value: String = if (nullable) {
      "$name != null ? NugetMarshal.CreateBytes($name) : IntPtr.Zero"
    } else {
      "NugetMarshal.CreateBytes($name)"
    }
    return ForwardCirHandleStep(
      flat = "IntPtr ${local}Handle = $value;",
      declarations = listOf("IntPtr ${local}Handle = IntPtr.Zero;"),
      statement = "${local}Handle = $value;",
    )
  }

  /** ADR-151: the mirror of [collectionCleanup], with the same unconditional zero guard (the
   *  `finally` is reached by a throw from the mint itself, and `nuget_dispose` is not
   *  null-safe). */
  private fun ForwardCallablePlan.bytesCleanup(parameter: ForwardPublicParameter): String? {
    if (parameter.type.unwrapNullable() != BridgeType.ByteArray) return null
    return "if (${parameter.csharpLocal}Handle != IntPtr.Zero) { " +
        "NugetBytesNative.Dispose(${parameter.csharpLocal}Handle); }"
  }

  private fun ForwardCallablePlan.collectionPrelude(
    parameter: ForwardPublicParameter,
  ): ForwardCirHandleStep? {
    val (type, nullable) = parameter.type.asNullableAwareCollection() ?: return null
    val factory: String = when (type.kind) {
      CollectionKind.LIST, CollectionKind.MUTABLE_LIST -> "CreateList"
      CollectionKind.MAP, CollectionKind.MUTABLE_MAP -> "CreateMap"
      CollectionKind.SET, CollectionKind.MUTABLE_SET -> "CreateSet"
    }
    // ADR-081: a value-class component is projected to its underlying per element before boxing.
    val source: String = collectionCreateArgument(parameter.csharpName, type) { it.csharpType() }
    val value: String = if (nullable) {
      "${parameter.csharpName} != null ? NugetMarshal.$factory($source) : IntPtr.Zero"
    } else {
      "NugetMarshal.$factory($source)"
    }
    return ForwardCirHandleStep(
      flat = "IntPtr ${parameter.csharpLocal}Handle = $value;",
      declarations = listOf("IntPtr ${parameter.csharpLocal}Handle = IntPtr.Zero;"),
      statement = "${parameter.csharpLocal}Handle = $value;",
    )
  }

  /**
   * ADR-084 stage 3: an interface-typed argument is extracted before the call, reporting whether
   * `HandleOf` *minted* a bridge transfer handle (a C#-implemented object) or read a Kotlin-backed
   * wrapper's own `_handle`.
   */
  private fun ForwardCallablePlan.interfacePrelude(
    parameter: ForwardPublicParameter,
  ): ForwardCirHandleStep? {
    val nullable: Boolean = parameter.type is BridgeType.Nullable
    if (!parameter.type.isInterfaceInput()) return null
    val helper: String = if (nullable) "HandleOfOrZero" else "HandleOf"
    return ForwardCirHandleStep(
      flat = "IntPtr ${parameter.csharpLocal}Handle = " +
          "NugetMarshal.$helper(${parameter.csharpName}, out bool ${parameter.csharpLocal}Owned);",
      declarations = listOf(
        "IntPtr ${parameter.csharpLocal}Handle = IntPtr.Zero;",
        "bool ${parameter.csharpLocal}Owned = false;",
      ),
      statement = "${parameter.csharpLocal}Handle = " +
          "NugetMarshal.$helper(${parameter.csharpName}, out ${parameter.csharpLocal}Owned);",
    )
  }

  /**
   * The transfer handle was only ever a vehicle for the crossing: Kotlin has taken its own
   * reference to the bridge by now, and holding this StableRef would root the bridge forever
   * (which is what stops the ADR-084 facet 4 cleaner from ever running). A wrapper's `_handle` is
   * owned by that wrapper and is never disposed here.
   */
  private fun ForwardCallablePlan.interfaceCleanup(parameter: ForwardPublicParameter): String? {
    if (!parameter.type.isInterfaceInput()) return null
    // ADR-135: the same zero guard `collectionCleanup` carries, for the same reason. This
    // `finally` is also reached by a throw from the mint itself, where the handle is still Zero,
    // and `nuget_dispose` is not null-safe.
    // ADR-187: a borrowed handle is the wrapper's raw `Handle`, so the wrapper is kept alive past
    // the call that read it.
    return "if (${parameter.csharpLocal}Owned && ${parameter.csharpLocal}Handle != IntPtr.Zero) " +
        "{ NugetMarshal.Dispose(${parameter.csharpLocal}Handle); } GC.KeepAlive(${parameter.csharpName});"
  }

  /**
   * ADR-147: a `T` argument is boxed before the call. `Wrap<T>` reports whether it MINTED the box
   * (a primitive, a char, a string) or contributed a live wrapper's own `_handle`; only a minted
   * box is disposed, which is ADR-099's ownership rule verbatim.
   */
  private fun ForwardCallablePlan.typeParameterPrelude(
    parameter: ForwardPublicParameter,
  ): ForwardCirHandleStep? {
    val type: BridgeType.TypeParameter =
      parameter.type.unwrapNullable() as? BridgeType.TypeParameter ?: return null
    val name: String = parameter.csharpName
    val local: String = parameter.csharpLocal
    val wrap = "NugetMarshal.Wrap<${type.name}>($name!, out"
    return ForwardCirHandleStep(
      flat = "IntPtr ${local}Box = $wrap bool ${local}Owned);",
      declarations = listOf("IntPtr ${local}Box = IntPtr.Zero;", "bool ${local}Owned = false;"),
      statement = "${local}Box = $wrap ${local}Owned);",
    )
  }

  /** ADR-099/147: dispose only a box this call site minted, and only once it exists. */
  private fun ForwardCallablePlan.typeParameterCleanup(parameter: ForwardPublicParameter): String? {
    if (parameter.type.unwrapNullable() !is BridgeType.TypeParameter) return null
    // ADR-187: keep a borrowed wrapper alive past the call (only when borrowed: `T` may be a value).
    return "if (${parameter.csharpLocal}Owned && ${parameter.csharpLocal}Box != IntPtr.Zero) { " +
        "NugetMarshal.Dispose(${parameter.csharpLocal}Box); } " +
        "if (!${parameter.csharpLocal}Owned) GC.KeepAlive(${parameter.csharpName});"
  }

  /**
   * ADR-088: a bound C# interface argument crosses as a fresh transfer GCHandle. There is
   * deliberately no matching cleanup: the RECEIVING side owns it. Kotlin's `nuget{Iface}Value`
   * either frees the handle itself (token-probe hit, the value was a Kotlin object all along) or
   * hands it to the ADR-070 wrapper's cleaner. Freeing it here as well would double-free, and NOT
   * allocating a fresh one would let a Kotlin `Farm` store a handle C# then released.
   */
  private fun ForwardCallablePlan.boundInterfacePrelude(
    parameter: ForwardPublicParameter,
  ): ForwardCirHandleStep? {
    if (parameter.type !is BridgeType.BoundInterface) return null
    // No cleanup, so nothing to hoist: the local stays declared where it is used.
    return ForwardCirHandleStep(
      "IntPtr ${parameter.csharpLocal}Handle = " +
          "GCHandle.ToIntPtr(GCHandle.Alloc(${parameter.csharpName}));",
    )
  }

  // ADR-073: load-bearing, not cosmetic. All three Dispose members bind to the same
  // `nuget_dispose` entry point, so emitting NugetListNative.Dispose for a map/set handle would be
  // *runtime*-correct, but a callable whose only collection is a Map/Set never causes
  // NugetMapNative/NugetSetNative to be tracked as needed for List, so NugetListNative is never
  // emitted at all, and the mismatched call is a CS0103 compile error, not a runtime bug.
  private fun ForwardCallablePlan.collectionCleanup(parameter: ForwardPublicParameter): String? {
    val (type, _) = parameter.type.asNullableAwareCollection() ?: return null
    val native: String = when (type.kind) {
      CollectionKind.LIST, CollectionKind.MUTABLE_LIST -> "NugetListNative"
      CollectionKind.MAP, CollectionKind.MUTABLE_MAP -> "NugetMapNative"
      CollectionKind.SET, CollectionKind.MUTABLE_SET -> "NugetSetNative"
    }
    // ADR-075: a null source value never built a handle above (it stayed IntPtr.Zero), and
    // `nuget_dispose`'s `handle.asStableRef<Any>().dispose()` is not null-safe. ROADMAP:130: the
    // guard is now unconditional rather than keyed on [nullable], because this runs in a `finally`
    // that a throw *from the creation itself* also reaches, where any handle is still Zero.
    return "if (${parameter.csharpLocal}Handle != IntPtr.Zero) { " +
        "$native.Dispose(${parameter.csharpLocal}Handle); }"
  }

  // ADR-069: default P/Invoke `out bool` marshalling reads 4 bytes; Kotlin's `BooleanVar` writes 1
  // (`putByte`). Every `out`-parameter Boolean nullable-result slot needs this on the DllImport
  // *declaration*; the call-site local variable declaration ([nativeOutParameters]) needs no
  // attribute (MarshalAs is a declaration-only concern).
  private fun outParameterMarshalPrefix(type: BridgeType): String =
    if (type == BridgeType.Primitive(PrimitiveKind.BOOLEAN)) {
      "[MarshalAs(UnmanagedType.I1)] "
    } else {
      ""
    }

  private fun ForwardCallablePlan.nativeOutParameters(nativeCall: ForwardNativeCall): List<String> =
    nativeCall.parameters
      .filter { parameter -> parameter != errorSlot && parameter.direction == ForwardAbiDirection.OUT }
      .map { parameter -> "out ${parameter.transfer.type.csharpType()} ${parameter.csharpName}" }

  private fun ForwardCallablePlan.nativeOutDeclarationParameters(
    nativeCall: ForwardNativeCall,
  ): List<String> = nativeCall.parameters
    .filter { parameter ->
      parameter != errorSlot && parameter.direction == ForwardAbiDirection.OUT
    }
    .map { parameter ->
      val marshal: String = outParameterMarshalPrefix(parameter.transfer.type)
      "${marshal}out ${parameter.transfer.type.csharpType()} ${parameter.csharpName}"
    }

  private fun ForwardCallablePlan.nativeOutCirParameters(
    nativeCall: ForwardNativeCall,
  ): List<CirParameter> = nativeCall.parameters
    .filter { parameter -> parameter != errorSlot && parameter.direction == ForwardAbiDirection.OUT }
    .map { parameter ->
      val type: String = parameter.transfer.type.csharpType()
      val marshal: String = outParameterMarshalPrefix(parameter.transfer.type)
      CirParameter(parameter.csharpName, type, "${marshal}out $type")
    }

  private fun ForwardCallablePlan.resultProjection(
    nativeName: String,
    parameters: List<ForwardPublicParameter>,
    receiverArgument: String? = null,
    forceCustomBody: Boolean = false,
    // Set for the `TryX` twin's body: the error check and every exit take the Try spelling.
    tryNames: CirTryNames? = null,
  ): CirResultProjection {
    val nativeCall: ForwardNativeCall = singleNativeImport()
    val exit = CirBodyExit(tryNames)
    val prelude: List<ForwardCirHandleStep> =
      parameters.mapNotNull { parameter -> callbackNullGuard(parameter) } +
          parameters.mapNotNull { parameter -> parameter.optionalPrelude() } +
          parameters.unwrapped().mapNotNull { parameter ->
            bytesPrelude(parameter)
              ?: collectionPrelude(parameter)
              ?: interfacePrelude(parameter)
              ?: boundInterfacePrelude(parameter)
              ?: typeParameterPrelude(parameter)
              ?: callbackPrelude(parameter)
          }
    val cleanup: List<String> =
      parameters.unwrapped().mapNotNull { parameter ->
        bytesCleanup(parameter)
          ?: collectionCleanup(parameter)
          ?: interfaceCleanup(parameter)
          ?: typeParameterCleanup(parameter)
          ?: callbackCleanup(parameter)
      }
    val argumentList: List<String> =
      listOfNotNull(receiverArgument) + parameters.flatMap { parameter -> inputArguments(parameter) }
    val callArguments: String = (argumentList + nativeOutParameters(nativeCall) + "out IntPtr error").joinToString(", ")
    // A `Result`-unwrapping export carries the failure flag, an out slot the generic pass-through
    // renderer would not pass, so both of its C# members take a hand-built body.
    val needsCustomParams: Boolean = forceCustomBody || invocation.unwrapsKotlinResult ||
        parameters.any { parameter -> !parameter.isTrivialInput() }
    val result: BridgeType = publicSignature.result
    return when (result) {
      is BridgeType.ObjectHandle -> CirResultProjection(
        returnType = result.csharpType(),
        nativeReturnType = "IntPtr",
        body = checkedPointerBody(
          nativeName, callArguments, exit.returning(result.handleReconstruction()), prelude,
          cleanup, exit,
        ),
      )

      // ADR-147: `FromHandle<T>` reads the box back and disposes it, the same decode the generic
      // property getter has always used.
      is BridgeType.TypeParameter -> CirResultProjection(
        returnType = result.name,
        nativeReturnType = "IntPtr",
        body = checkedPointerBody(
          nativeName,
          callArguments,
          exit.returning("NugetMarshal.FromHandle<${result.name}>(nativeResult)"),
          prelude,
          cleanup,
          exit,
        ),
      )

      // ADR-160 amendment: the OWNED lambda handle becomes ADR-012's `KotlinFunc<...>` (or
      // `KotlinAction<...>`), which owns it from here and releases it on `Dispose`. `Invoke` goes
      // through the shared arity-generic `NugetFunctionNative` exports the tracker declares.
      is BridgeType.ReturnedLambda -> {
        val lambdaType: String = result.forwardPublicCsharpType()
        CirResultProjection(
          returnType = lambdaType,
          nativeReturnType = "IntPtr",
          body = checkedPointerBody(
            nativeName, callArguments, exit.returning("new $lambdaType(nativeResult)"), prelude,
            cleanup, exit,
          ),
        )
      }

      // ADR-040: the public return type is the projected interface (`IPet`); construction uses
      // the generated backing wrapper class (`Pet`) instead.
      is BridgeType.Interface -> CirResultProjection(
        returnType = result.csharpType(),
        nativeReturnType = "IntPtr",
        body = checkedPointerBody(
          nativeName,
          callArguments,
          exit.returning(interfaceReturnExpression(result.csharpType(), result.backingType)),
          prelude,
          cleanup,
          exit,
        ),
      )

      // ADR-088: the returned pointer is a FRESH transfer GCHandle (Kotlin either duplicated a
      // wrapper's handle or minted a bridge), so this side resolves the target and frees the
      // duplicate. Freeing is not optional: the handle is ours, and the object itself stays rooted
      // by whatever managed reference already held it.
      is BridgeType.BoundInterface -> CirResultProjection(
        returnType = result.csharpType(),
        nativeReturnType = "IntPtr",
        body = checkedPointerBody(
          nativeName,
          callArguments,
          boundInterfaceReturnStatements(result.csharpType(), exit),
          prelude,
          cleanup,
          exit,
        ),
      )

      is BridgeType.Collection -> CirResultProjection(
        returnType = result.csharpType(),
        nativeReturnType = "IntPtr",
        body = checkedCollectionBody(
          nativeName, callArguments, result, prelude, cleanup, exit = exit,
        ),
      )

      // ADR-151: one handle out, materialized (and disposed) by `NugetMarshal.ReadBytes`.
      BridgeType.ByteArray -> CirResultProjection(
        returnType = result.csharpType(),
        nativeReturnType = "IntPtr",
        body = checkedBytesBody(nativeName, callArguments, prelude, cleanup, exit = exit),
      )

      // ADR-014 (ordinary position, ADR-066's fixture gap): always a custom body, regardless of
      // `needsCustomParams` — a value class's zero-parameter own getter (`Newsroom.Code()`) would
      // otherwise fall through to the generic pass-through renderer, which has no wrap-in-struct
      // case. ADR-077 sub-item 4 dispatches per underlying: the wire carries the underlying's
      // representation and the reconstruction composes the underlying's own step.
      is BridgeType.ValueClass -> {
        val wire: String = valueClassUnderlyingWireCs(result.underlying)
        CirResultProjection(
          returnType = result.csharpType,
          nativeReturnType = wire,
          body = checkedTypedBody(
            nativeName,
            callArguments,
            wire,
            exit.returning(valueClassReconstructionCs(result, "nativeResult")),
            prelude,
            cleanup,
            exit,
          ),
        )
      }

      // ADR-076: always a custom body, regardless of `needsCustomParams` -- same reasoning as
      // ValueClass above: a raw `long -> DateTimeOffset` C# cast is illegal, so this can never go
      // through directOrCustomResultProjection's no-custom-body cast route.
      BridgeType.Instant -> CirResultProjection(
        returnType = result.csharpType(),
        nativeReturnType = "long",
        body = checkedTicksBody(nativeName, callArguments, ::instantLiftCs, prelude, cleanup, exit),
      )

      // ADR-103: the same custom-body route, lifting the ticks into a TimeSpan.
      BridgeType.Duration -> CirResultProjection(
        returnType = result.csharpType(),
        nativeReturnType = "long",
        body = checkedTicksBody(
          nativeName, callArguments, ::durationLiftCs, prelude, cleanup, exit,
        ),
      )

      // ADR-106: the String result body with `Guid.Parse` composed onto the decoded text. Always a
      // custom body for the same reason as Instant/Duration: `IntPtr -> Guid` is not a C# cast.
      BridgeType.Uuid -> CirResultProjection(
        returnType = result.csharpType(),
        nativeReturnType = "IntPtr",
        body = checkedPointerBody(
          nativeName,
          callArguments,
          exit.returning("global::System.Guid.Parse(Marshal.PtrToStringUTF8(nativeResult)!)"),
          prelude,
          cleanup,
          exit,
        ),
      )

      is BridgeType.Nullable -> when (val type: BridgeType = result.type) {
        is BridgeType.ObjectHandle -> CirResultProjection(
          returnType = "${type.csharpType()}?",
          nativeReturnType = "IntPtr",
          body = checkedPointerBody(
            nativeName,
            callArguments,
            exit.returning("nativeResult == IntPtr.Zero ? null : ${type.handleReconstruction()}"),
            prelude,
            cleanup,
            exit,
          ),
        )

        // ADR-083/147: `FromHandle<T>` already answers `default!` for the null pointer, which is
        // the null of whatever `T` was instantiated to.
        is BridgeType.TypeParameter -> CirResultProjection(
          // ADR-147 amendment: bare `T` when only the bound made it nullable.
          returnType = if (type.nullableFromBound) type.name else "${type.name}?",
          nativeReturnType = "IntPtr",
          body = checkedPointerBody(
            nativeName,
            callArguments,
            exit.returning("NugetMarshal.FromHandle<${type.name}>(nativeResult)"),
            prelude,
            cleanup,
            exit,
          ),
        )

        is BridgeType.Interface -> CirResultProjection(
          returnType = "${type.csharpType()}?",
          nativeReturnType = "IntPtr",
          body = checkedPointerBody(
            nativeName,
            callArguments,
            exit.returning(
              "nativeResult == IntPtr.Zero ? null : " +
                  interfaceReturnExpression(type.csharpType(), type.backingType),
            ),
            prelude,
            cleanup,
            exit,
          ),
        )

        BridgeType.String -> CirResultProjection(
          returnType = "string?",
          nativeReturnType = "IntPtr",
          body = checkedPointerBody(
            nativeName, callArguments, exit.returning("Marshal.PtrToStringUTF8(nativeResult)"),
            prelude, cleanup, exit,
          ),
        )

        // ADR-106: `Guid?` over the same single pointer slot -- a null pointer is the null, and
        // `Guid` is a value type, so the public spelling is `Nullable<Guid>`.
        BridgeType.Uuid -> CirResultProjection(
          returnType = "${type.csharpType()}?",
          nativeReturnType = "IntPtr",
          body = checkedPointerBody(
            nativeName,
            callArguments,
            exit.returning(
              "nativeResult == IntPtr.Zero ? null : " +
                  "global::System.Guid.Parse(Marshal.PtrToStringUTF8(nativeResult)!)",
            ),
            prelude,
            cleanup,
            exit,
          ),
        )

        // ADR-077 sub-items 3/4: `ChartId?` is Nullable<ChartId> automatically (the record struct
        // is a value type); reconstruct only for a non-zero pointer, the ADR-075 null-handle
        // guard. Both admissible underlyings (String, ObjectHandle) ride an IntPtr wire.
        // ADR-079: a Primitive/Enum underlying takes the ADR-061 BOOLEAN + valueOut shape
        // instead; valueOut is declared at the *bare* underlying wire (`double`, `int` ordinal,
        // and `bool` with ADR-069's [MarshalAs(UnmanagedType.I1)]), reconstructed here.
        is BridgeType.ValueClass ->
          if (type.underlying is BridgeType.Primitive || type.underlying is BridgeType.Enum) {
            CirResultProjection(
              returnType = "${type.csharpType}?",
              nativeReturnType = "bool",
              body = checkedNullableValueBody(
                nativeName,
                callArguments,
                prelude,
                cleanup,
                "hasValue ? ${valueClassReconstructionCs(type, "valueOut")} : " +
                    "(${type.csharpType}?)null",
                exit,
              ),
            )
          } else {
            CirResultProjection(
              returnType = "${type.csharpType}?",
              nativeReturnType = "IntPtr",
              body = checkedPointerBody(
                nativeName,
                callArguments,
                exit.returning(
                  "nativeResult == IntPtr.Zero ? null : " +
                      valueClassReconstructionCs(type, "nativeResult"),
                ),
                prelude,
                cleanup,
                exit,
              ),
            )
          }

        is BridgeType.Primitive -> {
          val valueType: String = type.csharpType()
          CirResultProjection(
            returnType = "$valueType?",
            nativeReturnType = "bool",
            body = checkedNullableValueBody(
              nativeName, callArguments, prelude, cleanup, exit = exit,
            ),
          )
        }

        // ADR-080: same BOOLEAN + valueOut shape; valueOut is a plain `int` ordinal (its transfer
        // type is Primitive(INT), see ForwardCallablePlanner.valueOutTransferType), cast back to
        // the enum here. The `(Mood?)null` cast keeps the ternary's two arms unifiable.
        is BridgeType.Enum -> CirResultProjection(
          returnType = "${type.csharpType()}?",
          nativeReturnType = "bool",
          body = checkedNullableValueBody(
            nativeName, callArguments, prelude, cleanup,
            "hasValue ? (${type.csharpType()})valueOut : (${type.csharpType()}?)null",
            exit,
          ),
        )

        // ADR-098 amendment (boundary nullability part C): the Enum arm's shape with `.code` in
        // place of the ordinal. `valueOut` is declared `out ushort` (its transfer type is
        // Primitive(USHORT), blittable under NativeAOT by construction) and cast back to `char`
        // here; an `out char` slot is never minted, bare or U2-decorated.
        BridgeType.Char -> CirResultProjection(
          returnType = "char?",
          nativeReturnType = "bool",
          body = checkedNullableValueBody(
            nativeName, callArguments, prelude, cleanup,
            "hasValue ? (char)valueOut : (char?)null",
            exit,
          ),
        )

        // ADR-076: same BOOLEAN + valueOut shape as the nullable Primitive case above; valueOut
        // itself is declared as a raw `long` (its transfer type is Primitive(LONG), not Instant --
        // see ForwardCallablePlanner.nullableResultShape), converted to DateTimeOffset here.
        BridgeType.Instant -> CirResultProjection(
          returnType = "${type.csharpType()}?",
          nativeReturnType = "bool",
          body = checkedNullableTicksValueBody(
            nativeName, callArguments, type.csharpType(), ::instantLiftCs, prelude, cleanup, exit,
          ),
        )

        // ADR-103: the same, lifting the `valueOut` ticks into a TimeSpan.
        BridgeType.Duration -> CirResultProjection(
          returnType = "${type.csharpType()}?",
          nativeReturnType = "bool",
          body = checkedNullableTicksValueBody(
            nativeName, callArguments, type.csharpType(), ::durationLiftCs, prelude, cleanup, exit,
          ),
        )

        // ADR-061 (2026-09-16 amendment): the non-nullable collection body with the ADR-075
        // null-handle guard in front of it, the same guard the nullable ObjectHandle arm above
        // applies. A null handle is Kotlin `null`; anything else materializes unchanged.
        is BridgeType.Collection -> CirResultProjection(
          returnType = "${type.csharpType()}?",
          nativeReturnType = "IntPtr",
          body = checkedCollectionBody(
            nativeName, callArguments, type, prelude, cleanup, nullable = true, exit = exit,
          ),
        )

        // ADR-151: the same body with the null-handle guard in front, after the error check.
        BridgeType.ByteArray -> CirResultProjection(
          returnType = "${type.csharpType()}?",
          nativeReturnType = "IntPtr",
          body = checkedBytesBody(
            nativeName, callArguments, prelude, cleanup, nullable = true, exit = exit,
          ),
        )

        else -> directOrCustomResultProjection(
          result, nativeCall.result, needsCustomParams, nativeName, callArguments, prelude, cleanup,
          exit,
        )
      }

      else -> directOrCustomResultProjection(
        result, nativeCall.result, needsCustomParams, nativeName, callArguments, prelude, cleanup,
        exit,
      )
    }
  }

  private fun directOrCustomResultProjection(
    result: BridgeType,
    wireType: ForwardAbiWireType,
    needsCustomParams: Boolean,
    nativeName: String,
    callArguments: String,
    prelude: List<ForwardCirHandleStep>,
    cleanup: List<String>,
    exit: CirBodyExit,
  ): CirResultProjection = if (!needsCustomParams) {
    directResultProjection(result, wireType)
  } else {
    CirResultProjection(
      returnType = result.csharpType(),
      nativeReturnType = wireType.csharpType(),
      body = directCustomBody(nativeName, callArguments, result, wireType, prelude, cleanup, exit),
    )
  }

  private fun directResultProjection(result: BridgeType, wireType: ForwardAbiWireType): CirResultProjection =
    CirResultProjection(
      returnType = result.csharpType(),
      nativeReturnType = wireType.csharpType(),
      body = "",
      hasCustomBody = false,
    )

  /**
   * ADR-077 sub-item 4: the C# spelling of a value-class underlying's wire ("double", "int"
   * ordinal, "IntPtr" for String/ObjectHandle).
   */
  private fun valueClassUnderlyingWireCs(underlying: BridgeType): String = when (underlying) {
    BridgeType.String, is BridgeType.ObjectHandle -> "IntPtr"
    is BridgeType.Enum -> "int"
    is BridgeType.Primitive -> underlying.kind.csharpType()
    else -> error("Forward CIR plan projection has no value-class underlying wire for $underlying")
  }

  /**
   * ADR-077 sub-item 4: rebuild the record struct from its wire value, composing the underlying's
   * own step (UTF-8, enum cast, handle wrapper) inside the constructor call.
   *
   * ADR-105: the handle step is [handleReconstruction], not a bare `new`, so a value class over a
   * sealed base takes the `FromHandle` discriminator (the base is `abstract`, `new` is CS0144).
   */
  private fun valueClassReconstructionCs(type: BridgeType.ValueClass, wireValue: String): String {
    val inner: String = when (val underlying: BridgeType = type.underlying) {
      BridgeType.String -> "Marshal.PtrToStringUTF8($wireValue)!"
      is BridgeType.Enum -> "(${underlying.csharpType})$wireValue"
      is BridgeType.ObjectHandle -> underlying.handleReconstruction(wireValue)
      is BridgeType.Primitive -> wireValue
      else -> error("Forward CIR plan projection has no value-class reconstruction for $underlying")
    }
    return "new ${type.csharpType}($inner)"
  }

  private fun checkedTypedBody(
    nativeName: String,
    arguments: String,
    wireCs: String,
    result: String,
    prelude: List<ForwardCirHandleStep> = emptyList(),
    cleanup: List<String> = emptyList(),
    exit: CirBodyExit = CirBodyExit.THROWING,
  ): String = forwardCirHandleScope(
    prelude,
    cleanup,
    buildString {
      appendLine("            $wireCs nativeResult = $nativeName($arguments);")
      appendErrorCheck(exit)
      append("            $result")
    },
  )

  private fun checkedPointerBody(
    nativeName: String,
    arguments: String,
    result: String,
    prelude: List<ForwardCirHandleStep> = emptyList(),
    cleanup: List<String> = emptyList(),
    exit: CirBodyExit = CirBodyExit.THROWING,
  ): String = forwardCirHandleScope(
    prelude,
    cleanup,
    buildString {
      appendLine("            IntPtr nativeResult = $nativeName($arguments);")
      appendErrorCheck(exit)
      append("            $result")
    },
  )

  private fun StringBuilder.appendErrorCheck(exit: CirBodyExit = CirBodyExit.THROWING) {
    appendLine("            if (error != IntPtr.Zero)")
    appendLine("            {")
    val names: CirTryNames? = exit.names
    if (names == null) {
      appendLine("                throw NugetErrorNative.BuildException(error);")
    } else {
      // The flag tells a modelled `Result.failure` from an exception the Kotlin body threw; the
      // export zeroes it on entry, so the thrown path reads `false` and still throws.
      val exception: String = names.exception
      appendLine(
        "                global::System.Exception $exception = " +
            "NugetErrorNative.BuildException(error);",
      )
      appendLine("                if (!$RESULT_FAILED_SLOT) throw $exception;")
      names.value?.let { value -> appendLine("                $value = default;") }
      appendLine("                ${names.failure} = $exception;")
      appendLine("                return false;")
    }
    appendLine("            }")
  }

  /** [returnExpression] defaults to the bare `valueOut` a nullable primitive returns; ADR-079's
   *  value-class results pass the reconstruction (`hasValue ? new Dosage(valueOut) : ...`). */
  private fun checkedNullableValueBody(
    nativeName: String,
    arguments: String,
    prelude: List<ForwardCirHandleStep> = emptyList(),
    cleanup: List<String> = emptyList(),
    returnExpression: String = "hasValue ? valueOut : null",
    exit: CirBodyExit = CirBodyExit.THROWING,
  ): String = forwardCirHandleScope(
    prelude,
    cleanup,
    buildString {
      appendLine("            bool hasValue = $nativeName($arguments);")
      appendErrorCheck(exit)
      append("            ${exit.returning(returnExpression)}")
    },
  )

  /** ADR-076 / ADR-103: same shape as [checkedNullableValueBody], except `valueOut` is a raw
   *  `long` of ticks (its own transfer stays `Primitive(LONG)`, never `Instant`/`Duration`) and
   *  must be lifted into the semantic C# type in the return expression. [lift] renders that lift
   *  for the given wire expression; [csharpType] is the type the `null` arm is cast to so the
   *  ternary's two arms unify. */
  private fun checkedNullableTicksValueBody(
    nativeName: String,
    arguments: String,
    csharpType: String,
    lift: (String) -> String,
    prelude: List<ForwardCirHandleStep> = emptyList(),
    cleanup: List<String> = emptyList(),
    exit: CirBodyExit = CirBodyExit.THROWING,
  ): String = forwardCirHandleScope(
    prelude,
    cleanup,
    buildString {
      appendLine("            bool hasValue = $nativeName($arguments);")
      appendErrorCheck(exit)
      append("            ${exit.returning("hasValue ? ${lift("valueOut")} : ($csharpType?)null")}")
    },
  )

  /** ADR-076 / ADR-103: non-nullable Instant/Duration result -- the native call returns a raw
   *  `long` of ticks, which must be lifted into the semantic C# type in the return expression.
   *  Always a custom body (see the `BridgeType.Instant`/`BridgeType.Duration` branches in
   *  [resultProjection]): a raw `long ->` value-type cast is illegal C#. */
  private fun checkedTicksBody(
    nativeName: String,
    arguments: String,
    lift: (String) -> String,
    prelude: List<ForwardCirHandleStep> = emptyList(),
    cleanup: List<String> = emptyList(),
    exit: CirBodyExit = CirBodyExit.THROWING,
  ): String = forwardCirHandleScope(
    prelude,
    cleanup,
    buildString {
      appendLine("            long nativeResult = $nativeName($arguments);")
      appendErrorCheck(exit)
      append("            ${exit.returning(lift("nativeResult"))}")
    },
  )

  /** ADR-151: the result-side read of a byte-array handle. `NugetMarshal.ReadBytes` counts,
   *  allocates, copies and disposes the handle in its own `finally`, so this body is the call,
   *  the error check, the optional null-handle guard, and the read. */
  private fun checkedBytesBody(
    nativeName: String,
    arguments: String,
    prelude: List<ForwardCirHandleStep> = emptyList(),
    cleanup: List<String> = emptyList(),
    nullable: Boolean = false,
    exit: CirBodyExit = CirBodyExit.THROWING,
  ): String = forwardCirHandleScope(
    prelude,
    cleanup,
    buildString {
      appendLine("            IntPtr bytesHandle = $nativeName($arguments);")
      appendErrorCheck(exit)
      if (nullable) appendNullHandleExit("bytesHandle", exit)
      append("            ${exit.returning("NugetMarshal.ReadBytes(bytesHandle)")}")
    },
  )

  /** The ADR-075 null-handle guard, after the error check: a null handle is Kotlin `null`. */
  private fun StringBuilder.appendNullHandleExit(handle: String, exit: CirBodyExit) {
    if (exit.names == null) {
      appendLine("            if ($handle == IntPtr.Zero) return null;")
      return
    }
    appendLine("            if ($handle == IntPtr.Zero)")
    appendLine("            {")
    appendLine("                ${exit.returning("null").replace("\n    ", "\n        ")}")
    appendLine("            }")
  }

  private fun checkedCollectionBody(
    nativeName: String,
    arguments: String,
    type: BridgeType.Collection,
    prelude: List<ForwardCirHandleStep> = emptyList(),
    cleanup: List<String> = emptyList(),
    nullable: Boolean = false,
    exit: CirBodyExit = CirBodyExit.THROWING,
  ): String = forwardCirHandleScope(
    prelude,
    cleanup,
    collectionMaterializingCore(nativeName, arguments, type, nullable, exit),
  )

  /** The result-side read of a collection handle: the call, the error check, and the
   *  materialization, which runs through ADR-099's `finally`-guarded `ReadList`/`ReadSet`/`ReadMap`
   *  helpers. The *parameter* handles it may have been given are released by the
   *  [forwardCirHandleScope] around it; the result handle is released by the helper, on the
   *  throwing path as well as the happy one (ADR-120: the shipped inlined loop put that `Dispose`
   *  after the loop, so an element read that threw mid-loop leaked the handle). */
  private fun collectionMaterializingCore(
    nativeName: String,
    arguments: String,
    type: BridgeType.Collection,
    nullable: Boolean,
    exit: CirBodyExit,
  ): String {
    val handle: String = when (type.kind) {
      CollectionKind.LIST, CollectionKind.MUTABLE_LIST -> "listHandle"
      CollectionKind.MAP, CollectionKind.MUTABLE_MAP -> "mapHandle"
      CollectionKind.SET, CollectionKind.MUTABLE_SET -> "setHandle"
    }
    return buildString {
      appendLine("            IntPtr $handle = $nativeName($arguments);")
      appendErrorCheck(exit)
      // ADR-061 (2026-09-16 amendment): after the error check, so a throw is still reported as a
      // throw rather than silently read as a null result.
      if (nullable) appendNullHandleExit(handle, exit)
      val read: String = componentCollectionRead(handle, type, csharpType = { it.csharpType() })
      append("            ${exit.returning(read)}")
    }
  }

  /** A direct (no object/list/nullable materialization) result whose call site still needs to be
   * hand-built because one of its *parameters* — not its result — requires a raising expression a
   * plain nativeType-diff cast cannot express (an object handle's `._handle`, a collection's
   * prelude-built local, or a nullable primitive's two-argument fan-out).
   */
  private fun directCustomBody(
    nativeName: String,
    arguments: String,
    result: BridgeType,
    wireType: ForwardAbiWireType,
    prelude: List<ForwardCirHandleStep>,
    cleanup: List<String>,
    exit: CirBodyExit = CirBodyExit.THROWING,
  ): String = forwardCirHandleScope(
    prelude,
    cleanup,
    buildString {
      if (result == BridgeType.Unit) {
        appendLine("            $nativeName($arguments);")
      } else {
        appendLine("            ${wireType.csharpType()} nativeResult = $nativeName($arguments);")
      }
      appendErrorCheck(exit)
      val returned: String? = when {
        result is BridgeType.Enum -> "(${result.csharpType()})nativeResult"
        result == BridgeType.String -> "Marshal.PtrToStringUTF8(nativeResult)!"
        result != BridgeType.Unit -> "nativeResult"
        else -> null
      }
      val exitStatement: String? =
        if (returned != null) exit.returning(returned) else exit.completing()
      if (exitStatement != null) append("            $exitStatement")
    },
  )

  private data class CirResultProjection(
    val returnType: String,
    val nativeReturnType: String,
    val body: String,
    val hasCustomBody: Boolean = true,
  )

  /**
   * ADR-105 (issue #54): the C# expression that turns a returned handle back into its declared
   * type. An ordinary handle-backed class takes its `internal T(IntPtr, out NugetHandleTag)`
   * constructor; an ADR-009 sealed *base* is `abstract`, so `new` is CS0144 and the reconstruction
   * goes through the generated `internal static T FromHandle(IntPtr)` discriminator instead.
   * Mirrors [ForwardCirPropertyProjection]'s reconstruction of the same name, so a property
   * getter and a method returning the same sealed base render one idiom.
   */
  private fun BridgeType.ObjectHandle.handleReconstruction(
    wireValue: String = "nativeResult",
  ): String = if (viaDiscriminator) {
    "${csharpType()}.FromHandle($wireValue)"
  } else {
    "new ${constructType ?: csharpType()}($wireValue, out _)"
  }

  // ADR-114: the shared spelling, so the legacy Flow/suspend routes render a collection
  // parameter exactly as this projection does.
  private fun BridgeType.csharpType(): String = forwardPublicCsharpType()

  /**
   * Whether this type's rendered [csharpType] spelling is a C# reference type, i.e. whether a
   * nullable annotation on it is erased from the method signature (ADR-034's duplicate-
   * constructor check relies on this to know when it may strip a trailing "?" before comparing
   * rendered signatures). [BridgeType.Enum] and [BridgeType.ValueClass] are C# value types
   * (`readonly record struct`, `enum`), so `T` and `T?` really are distinct overloads for them —
   * unlike [BridgeType.String], [BridgeType.ObjectHandle], and [BridgeType.Collection], which
   * render as classes/interfaces.
   */
  private fun BridgeType.isCSharpReferenceType(): Boolean = when (this) {
    is BridgeType.Nullable -> type.isCSharpReferenceType()
    // ADR-151: `byte[]` is a C# array, a reference type, so `ByteArray?` renders `byte[]?`.
    BridgeType.String, is BridgeType.ObjectHandle, is BridgeType.Interface,
    is BridgeType.BoundInterface, is BridgeType.Collection, BridgeType.ByteArray,
      // ADR-160: `Action<>`/`Func<>` are delegate types, which are C# reference types.
    is BridgeType.Callback -> true
    // ADR-076: DateTimeOffset is a C# value type, same as Enum/ValueClass.
    // ADR-103: so is TimeSpan.
    // ADR-106: Guid is a C# value type too, so `Uuid?` renders Nullable<Guid> ("Guid?").
    // ADR-147: an unconstrained `T` can be instantiated at a value type, so `T` and `T?` are
    // distinct overloads and the trailing "?" must not be stripped before comparing signatures.
    BridgeType.Unit, is BridgeType.Primitive, BridgeType.Char, BridgeType.Instant,
    BridgeType.Duration, BridgeType.Uuid, is BridgeType.TypeParameter,
    is BridgeType.Enum, is BridgeType.ValueClass -> false

    else -> error("Forward CIR direct-value projection cannot classify public type $this")
  }

  private fun PrimitiveKind.csharpType(): String = when (this) {
    PrimitiveKind.BOOLEAN -> "bool"
    PrimitiveKind.BYTE -> "sbyte"
    PrimitiveKind.UBYTE -> "byte"
    PrimitiveKind.SHORT -> "short"
    PrimitiveKind.USHORT -> "ushort"
    PrimitiveKind.INT -> "int"
    PrimitiveKind.UINT -> "uint"
    PrimitiveKind.LONG -> "long"
    PrimitiveKind.ULONG -> "ulong"
    PrimitiveKind.FLOAT -> "float"
    PrimitiveKind.DOUBLE -> "double"
  }

  private fun ForwardAbiWireType.csharpType(): String = when (this) {
    ForwardAbiWireType.VOID -> "void"
    ForwardAbiWireType.BOOLEAN -> "bool"
    ForwardAbiWireType.INT8 -> "sbyte"
    ForwardAbiWireType.UINT8 -> "byte"
    ForwardAbiWireType.INT16 -> "short"
    ForwardAbiWireType.UINT16 -> "ushort"
    ForwardAbiWireType.CHAR16 -> "char"
    ForwardAbiWireType.INT32 -> "int"
    ForwardAbiWireType.UINT32 -> "uint"
    ForwardAbiWireType.INT64 -> "long"
    ForwardAbiWireType.UINT64 -> "ulong"
    ForwardAbiWireType.FLOAT32 -> "float"
    ForwardAbiWireType.FLOAT64 -> "double"
    ForwardAbiWireType.STRING -> "string"
    ForwardAbiWireType.POINTER -> "IntPtr"
    ForwardAbiWireType.UNKNOWN -> error("Forward CIR projection cannot render an unknown wire type")
  }
}

/**
 * ADR-084 facet 5: ask the returned handle whether a C# object is already behind it before wrapping
 * it in the generated backing class. A Kotlin-backed handle carries no token, so the wrapper
 * construction is unchanged; a bridge handle resolves to the original C#-implemented instance, so
 * `Assert.Same(dog, oreo.ClosestFriend())` holds and no read round-trips through two bridges.
 *
 * ADR-136: [handle] names the local the read is spelled against, so the suspend completion
 * (`resultPtr`) and the `Flow` element delegate (`h`) share this one expression instead of spelling
 * a bare wrapper construction. `TryResolveCSharp` disposes the resolved transfer handle itself, so
 * every route frees what Kotlin minted for the crossing.
 */
internal fun interfaceReturnExpression(
  csharpType: String,
  backingType: String,
  handle: String = "nativeResult",
): String =
  "(NugetMarshal.TryResolveCSharp($handle, out $csharpType csharpOriginal) " +
      "? csharpOriginal : new $backingType($handle, out _))"

/**
 * ADR-088: resolve the fresh transfer GCHandle Kotlin returned, then free it. `Target!` is safe by
 * construction: Kotlin's `nuget{Iface}HandleOut` never yields a handle over null (it `require`s a
 * non-null result), and the null pointer case is already excluded because v1 does not marshal a
 * nullable bound interface.
 *
 * The indentation matches [checkedPointerBody]'s own 12-space statement column; only the first
 * line is placed by the caller.
 */
private fun boundInterfaceReturnStatements(csharpType: String, exit: CirBodyExit): String =
  "GCHandle resultGcHandle = GCHandle.FromIntPtr(nativeResult);\n" +
      "            $csharpType resultValue = ($csharpType)resultGcHandle.Target!;\n" +
      "            resultGcHandle.Free();\n" +
      "            ${exit.returning("resultValue")}"

/**
 * The C# names a `Result<T>` `TryX` twin adds: its `out T value` (null for `Result<Unit>`), its
 * `out Exception? failure`, and the local holding the built exception. `value` and `failure` move
 * off a user parameter of the same spelling with a trailing underscore, the rule `error` already
 * takes; the local moves off all of them.
 */
internal data class CirTryNames(val value: String?, val failure: String, val exception: String) {
  companion object {
    fun of(parameters: List<String>, hasValue: Boolean): CirTryNames {
      val taken: Set<String> = parameters.toSet()
      val value: String? = if (hasValue) freshName("value", taken) else null
      val failure: String = freshName("failure", taken + listOfNotNull(value))
      val exception: String = freshName("exception", taken + listOfNotNull(value, failure))
      return CirTryNames(value, failure, exception)
    }
  }
}

/**
 * How a checked body leaves: the throwing member's `throw` / `return`, or, with [names], the
 * `TryX` twin's `value = ...; failure = null; return true;`. Each statement after the first starts
 * at the bodies' own 12-space column, so a caller places only the first.
 */
internal class CirBodyExit(val names: CirTryNames?) {
  fun returning(expression: String): String {
    val names: CirTryNames = names ?: return "return $expression;"
    val value: String = requireNotNull(names.value) { "A Result<Unit> Try has no value to return" }
    return "$value = $expression;\n            ${names.failure} = null;\n            return true;"
  }

  /** The exit of a `void` body: nothing for the throwing member, `failure = null; return true;`. */
  fun completing(): String? {
    val names: CirTryNames = names ?: return null
    return "${names.failure} = null;\n            return true;"
  }

  companion object {
    val THROWING: CirBodyExit = CirBodyExit(null)
  }
}

/** The nullable-flow attributes of a `TryX` twin's out parameters, spelled without a `using`. */
internal const val MAYBE_NULL_WHEN_FALSE: String =
  "[global::System.Diagnostics.CodeAnalysis.MaybeNullWhen(false)]"
internal const val NOT_NULL_WHEN_FALSE: String =
  "[global::System.Diagnostics.CodeAnalysis.NotNullWhen(false)]"

/**
 * ADR-076: lift a wire expression of .NET ticks into the public `DateTimeOffset`. Always UTC, so
 * the offset is `TimeSpan.Zero`.
 */
internal fun instantLiftCs(ticks: String): String =
  "new global::System.DateTimeOffset($ticks, global::System.TimeSpan.Zero)"

/**
 * ADR-103: lift a wire expression of `TimeSpan` ticks into the public `TimeSpan`. Unlike
 * [instantLiftCs] this needs no guard: `new TimeSpan(long)` never throws, for any tick value.
 */
internal fun durationLiftCs(ticks: String): String = "new global::System.TimeSpan($ticks)"

/** True for `IFoo` and `IFoo?` alike: both cross as one handle argument. */
private fun BridgeType.isInterfaceInput(): Boolean =
  this is BridgeType.Interface || (this is BridgeType.Nullable && type is BridgeType.Interface)

/** The C# spelling of a public parameter, at both its declaration and every use site. */
internal val ForwardPublicParameter.csharpName: String get() = name.csharpParameterName()

/**
 * The C# stem of a parameter's wrapper locals (`${csharpLocal}Handle`, `...Owned`, `...Box`,
 * `...Ctx`, `...Native`), from the planner's [ForwardPublicParameter.localStem]: the same as
 * [csharpName] unless one of those locals is spelled like a sibling user parameter.
 */
internal val ForwardPublicParameter.csharpLocal: String get() = localStem.csharpParameterName()

/**
 * ADR-150: this signature's KDoc as the C# tags of the member being rendered: `@param` entries
 * re-keyed to the C# parameter spellings the renderer prints, `<returns>` dropped on a `void`
 * member, everything else as parsed.
 */
internal fun ForwardPublicSignature.cirDoc(
  // ADR-150: C# parameters of the rendered member that the *plan* does not carry, in the order
  // they are declared — today the extension route's generated `this` receiver. `toCirDoc` is
  // all-or-none (once one `@param` matches, every parameter needs a tag), so leaving the receiver
  // out of this list made a documented `fun Foo.bar(x: Int)` render `<param name="x">` and nothing
  // for `receiver`: CS1573, fatal under `GeneratedBindingsCheck`. The suspend `Async` route already
  // solved the same problem for its `cancellationToken` slot the same way.
  leadingParameters: List<String> = emptyList(),
): CirDoc? {
  val kdoc: ForwardKdoc = doc ?: return null
  val named: Map<String, String> = parameters
    .mapNotNull { parameter -> kdoc.params[parameter.name]?.let { parameter.csharpName to it } }
    .toMap()
  return kdoc.copy(params = named).toCirDoc(
    leadingParameters + parameters.map { it.csharpName },
    hasResult = result != BridgeType.Unit,
  )
}

/**
 * The same escape for an ABI slot's name. Generator-minted slots (`handle`, `value`, `receiver`,
 * `errorOut`, `valueOut`, `hasValue`) are never keywords, so this is the identity for them and the
 * hand-written body text that references those locals raw stays in agreement; it matters only for
 * the slots whose names are copied from the Kotlin parameter.
 */
internal val ForwardAbiParameter.csharpName: String get() = name.csharpParameterName()
