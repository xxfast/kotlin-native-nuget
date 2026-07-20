package io.github.xxfast.kotlin.native.nuget.processor.forward

import io.github.xxfast.kotlin.native.nuget.processor.cir.CirDllImport
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirConstructor
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirMember
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirMethod
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirParameter
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirProperty
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirValueClassConstructor
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirVisibility

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
    val body: String = if (underlyingIsString) {
      "Marshal.PtrToStringUTF8(CreateChecked$nativeSuffix($paramNames))!"
    } else {
      "CreateChecked$nativeSuffix($paramNames)"
    }
    return CirValueClassConstructor(
      parameters = publicParams,
      nativeName = plan.nativeExports.single().exportName,
      body = body,
      hasErrorCheck = plan.errorSlot != null,
      nativeSuffix = nativeSuffix,
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
    val nativeName: String = "Native_${plan.publicSignature.name}"
    val (returnType, nativeReturnType, expression) = valueClassMemberExpression(
      plan = plan,
      nativeName = nativeName,
      callArguments = argumentList.joinToString(", "),
    )
    val methodName: String = plan.invocation.symbol.substringAfterLast('.')
    val needsCustomParams: Boolean =
      plan.publicSignature.parameters.any { parameter -> !parameter.type.isTrivialInput() }
    val nativeParams: List<CirParameter>? = if (needsCustomParams) {
      plan.nativeInCirParameters(
        plan.nativeExports.single().parameters.filter { parameter ->
          parameter.name !in setOf("handle", "value", "receiver") &&
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
    require(plan.invocation.origin == ForwardCallableOrigin.CONSTRUCTOR) {
      "Forward CIR constructor projection received ${plan.invocation.origin}"
    }
    val nativeCall: ForwardNativeCall = plan.singleNativeImport()
    val publicParams: List<CirParameter> = plan.publicParameters()
    val needsCustomParams: Boolean =
      plan.publicSignature.parameters.any { parameter -> !parameter.type.isTrivialInput() }
    if (!needsCustomParams) {
      return CirConstructor(parameters = publicParams, body = "", hasErrorCheck = true, nativeSuffix = nativeSuffix)
    }
    val prelude: List<String> = plan.publicSignature.parameters.mapNotNull { plan.collectionPrelude(it) }
    val cleanup: List<String> = plan.publicSignature.parameters.mapNotNull { plan.collectionCleanup(it) }
    val argumentList: List<String> = plan.publicSignature.parameters.flatMap { plan.callArgument(it) }
    val callArgs: String = (argumentList + "out IntPtr error").joinToString(", ")
    val body: String = buildString {
      prelude.forEach { line -> appendLine("            $line") }
      appendLine("            IntPtr handle = Native_Create$nativeSuffix($callArgs);")
      appendLine("            if (error != IntPtr.Zero)")
      appendLine("            {")
      appendLine("                throw NugetErrorNative.BuildException(error);")
      appendLine("            }")
      cleanup.forEach { line -> appendLine("            $line") }
      append("            _handle = handle;")
    }
    return CirConstructor(
      parameters = publicParams,
      body = body,
      hasErrorCheck = false,
      nativeSuffix = nativeSuffix,
      nativeParameters = plan.nativeInCirParameters(nativeCall.parameters),
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
    if (plan.evaluation == ForwardEvaluation.LEGACY_TWO_CALL) {
      return staticLegacyTwoCall(plan, libraryName)
    }
    val nativeCall: ForwardNativeCall = plan.singleNativeImport()
    val publicParams: List<CirParameter> = plan.publicParameters()
    val identifier: String = plan.publicSignature.name.removePrefix("@")
    val nativeName: String = if (plan.invocation.origin == ForwardCallableOrigin.COMPANION) {
      "Native_Companion_$identifier"
    } else {
      "Native_$identifier"
    }
    val result: CirResultProjection = plan.resultProjection(
      nativeName = nativeName,
      parameters = plan.publicSignature.parameters,
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
        marshalBooleanReturn = result.nativeReturnType == "bool",
      ),
      CirMethod(
        name = plan.publicSignature.name,
        returnType = result.returnType,
        nativeReturnType = result.nativeReturnType,
        nativeName = nativeName,
        parameters = publicParams.map { parameter -> parameter.copy(nativeType = parameter.type) },
        body = result.body,
        isStatic = true,
        isSyncErrorCheckEnabled = !result.hasCustomBody && plan.errorSlot != null,
        hasCustomBody = result.hasCustomBody,
      ),
    )
  }

  /**
   * ADR-002 top-level nullable-primitive two-call CIR projection. Mirrors the historical
   * `translateNullableFunction` shape so the public `T?` wrapper and both DllImports keep
   * their shipped error-out slots and names.
   */
  private fun staticLegacyTwoCall(plan: ForwardCallablePlan, libraryName: String): List<CirMember> {
    require(plan.invocation.origin == ForwardCallableOrigin.TOP_LEVEL) {
      "Legacy two-call CIR projection only supports top-level: ${plan.invocation.symbol}"
    }
    require(plan.nativeImports.size == 2) {
      "Legacy two-call plan ${plan.invocation.symbol} must have two native imports"
    }
    val nullable: BridgeType.Nullable = plan.publicSignature.result as? BridgeType.Nullable
      ?: error("Legacy two-call plan ${plan.invocation.symbol} requires a nullable result")
    val primitive: BridgeType.Primitive = nullable.type as? BridgeType.Primitive
      ?: error("Legacy two-call plan ${plan.invocation.symbol} requires a nullable primitive")
    val presence: ForwardNativeCall = plan.nativeImports[0]
    val value: ForwardNativeCall = plan.nativeImports[1]
    val publicParams: List<CirParameter> = plan.publicParameters()
    val csName: String = plan.publicSignature.name.removePrefix("@")
    val csharpReturnType: String = primitive.csharpType()
    val callArgs: String = plan.publicSignature.parameters
      .flatMap { parameter -> plan.callArgument(parameter) }
      .joinToString(", ")
    val hasValueCallArgs: String = if (callArgs.isEmpty()) {
      "out IntPtr __nuget_hasValueError"
    } else {
      "$callArgs, out IntPtr __nuget_hasValueError"
    }
    val valueCallArgs: String = if (callArgs.isEmpty()) {
      "out IntPtr __nuget_valueError"
    } else {
      "$callArgs, out IntPtr __nuget_valueError"
    }
    val presenceName: String = "${csName}_has_value"
    val valueName: String = "${csName}_value"
    val body: String = buildString {
      appendLine()
      appendLine("            bool __nuget_hasValue = $presenceName($hasValueCallArgs);")
      appendLine("            if (__nuget_hasValueError != IntPtr.Zero)")
      appendLine("            {")
      appendLine("                throw NugetErrorNative.BuildException(__nuget_hasValueError);")
      appendLine("            }")
      appendLine("            if (!__nuget_hasValue) return null;")
      appendLine("            $csharpReturnType __nuget_value = $valueName($valueCallArgs);")
      appendLine("            if (__nuget_valueError != IntPtr.Zero)")
      appendLine("            {")
      appendLine("                throw NugetErrorNative.BuildException(__nuget_valueError);")
      appendLine("            }")
      append("            return __nuget_value;")
    }
    val inParams: List<CirParameter> = plan.nativeInCirParameters(presence.parameters)
    return listOf(
      CirDllImport(
        libraryName = libraryName,
        entryPoint = presence.exportName,
        returnType = "bool",
        name = presenceName,
        parameters = inParams,
        visibility = CirVisibility.PRIVATE,
        hasSyncErrorOut = true,
        marshalBooleanReturn = true,
      ),
      CirDllImport(
        libraryName = libraryName,
        entryPoint = value.exportName,
        returnType = csharpReturnType,
        name = valueName,
        parameters = inParams,
        visibility = CirVisibility.PRIVATE,
        hasSyncErrorOut = true,
      ),
      CirMethod(
        name = plan.publicSignature.name,
        returnType = "$csharpReturnType?",
        parameters = publicParams,
        body = body,
        isStatic = true,
      ),
    )
  }

  fun classMethod(
    plan: ForwardCallablePlan,
    nativePrefix: String,
    isOverride: Boolean,
  ): CirMethod {
    val nativeCall: ForwardNativeCall = plan.singleNativeImport()
    val receiver: ForwardAbiParameter = nativeCall.parameters.firstOrNull()
      ?: error("Forward CIR plan ${plan.invocation.symbol} has no receiver")
    require(receiver.name == "handle" && receiver.wireType == ForwardAbiWireType.POINTER) {
      "Forward CIR class plan ${plan.invocation.symbol} must begin with a handle receiver"
    }

    val publicParams: List<CirParameter> = plan.publicParameters()
    val nativeName: String = nativeCall.exportName.removePrefix("${nativePrefix}_")
    require(nativeName != nativeCall.exportName) {
      "Forward CIR class plan ${plan.invocation.symbol} export ${nativeCall.exportName} " +
          "does not begin with $nativePrefix"
    }

    val result: CirResultProjection = plan.resultProjection(
      nativeName = "Native_${plan.publicSignature.name}",
      parameters = plan.publicSignature.parameters,
      receiverArgument = "_handle",
    )
    val needsCustomParams: Boolean =
      plan.publicSignature.parameters.any { parameter -> !parameter.type.isTrivialInput() }
    val nativeParams: List<CirParameter>? = if (needsCustomParams) {
      plan.nativeInCirParameters(nativeCall.parameters.drop(1))
    } else {
      null
    }
    return CirMethod(
      name = plan.publicSignature.name,
      returnType = result.returnType,
      nativeReturnType = result.nativeReturnType,
      nativeName = nativeName,
      parameters = publicParams,
      body = result.body,
      isOverride = isOverride,
      // Unlike static/extension (which hand-build their own CirDllImport), the DllImport here is
      // derived generically by CirClass.methodNativeImport from this CirMethod, and its trailing
      // `out IntPtr error` must be present whether or not the *body* is hand-written, so this is
      // deliberately not gated by `!result.hasCustomBody` the way static/extension are.
      isSyncErrorCheckEnabled = plan.errorSlot != null,
      extraNativeParams = plan.nativeOutParameters(nativeCall),
      hasCustomBody = result.hasCustomBody,
      nativeParameters = nativeParams,
    )
  }

  fun extension(
    plan: ForwardCallablePlan,
    libraryName: String,
  ): List<CirMember> {
    val nativeCall: ForwardNativeCall = plan.singleNativeImport()
    val receiver: ForwardAbiParameter = nativeCall.parameters.firstOrNull()
      ?: error("Forward CIR plan ${plan.invocation.symbol} has no receiver")
    require(receiver.name == "receiver") {
      "Forward CIR extension plan ${plan.invocation.symbol} must begin with a receiver"
    }
    val receiverType: String = receiver.transfer.type.csharpType()
    val receiverParam = CirParameter(receiver.name, receiverType, receiver.wireType.csharpType())
    val publicParams: List<CirParameter> = listOf(receiverParam) + plan.publicParameters()
    val receiverArgument: String = if (receiver.transfer.type is BridgeType.ObjectHandle) {
      "receiver._handle"
    } else {
      "receiver"
    }
    val nativeName: String = "Native_${plan.publicSignature.name}"
    val needsCustomParams: Boolean = receiver.transfer.type is BridgeType.ObjectHandle ||
        plan.publicSignature.parameters.any { parameter -> !parameter.type.isTrivialInput() }
    val result: CirResultProjection = plan.resultProjection(
      nativeName = nativeName,
      parameters = plan.publicSignature.parameters,
      receiverArgument = receiverArgument,
      forceCustomBody = needsCustomParams,
    )

    val nativeImport = CirDllImport(
      libraryName = libraryName,
      entryPoint = nativeCall.exportName,
      returnType = result.nativeReturnType,
      name = nativeName,
      parameters = plan.nativeInCirParameters(nativeCall.parameters) + plan.nativeOutCirParameters(nativeCall),
      visibility = CirVisibility.PRIVATE,
      hasSyncErrorOut = plan.errorSlot != null,
      marshalBooleanReturn = result.nativeReturnType == "bool",
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
    )
    return listOf(nativeImport, wrapper)
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
  private fun ForwardCallablePlan.publicParameters(): List<CirParameter> =
    publicSignature.parameters.map { parameter -> CirParameter(parameter.name, parameter.type.csharpType()) }

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
    .map { native -> CirParameter(native.name, native.nativeCsharpType()) }

  private fun ForwardAbiParameter.nativeCsharpType(): String {
    val type: BridgeType = transfer.type
    return if (type is BridgeType.Nullable && type.type == BridgeType.String) "string?" else wireType.csharpType()
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
      is BridgeType.Primitive, BridgeType.Char, BridgeType.String -> listOf(parameter.name)
      is BridgeType.Enum -> listOf("(int)${parameter.name}")
      is BridgeType.ObjectHandle -> listOf("${parameter.name}._handle")
      is BridgeType.Collection -> listOf("${parameter.name}Handle")
      is BridgeType.Nullable -> when (val inner = type.type) {
        BridgeType.String -> listOf(parameter.name)
        is BridgeType.ObjectHandle -> listOf("${parameter.name}?._handle ?? IntPtr.Zero")
        is BridgeType.Primitive -> listOf("${parameter.name}.HasValue", "${parameter.name}.GetValueOrDefault()")
        else -> error("Forward CIR plan projection has no call argument for nullable $inner")
      }

      else -> error("Forward CIR plan projection has no call argument for $type")
    }

  private fun ForwardCallablePlan.collectionPrelude(parameter: ForwardPublicParameter): String? =
    if (parameter.type is BridgeType.Collection) {
      "IntPtr ${parameter.name}Handle = NugetMarshal.CreateList(${parameter.name});"
    } else {
      null
    }

  private fun ForwardCallablePlan.collectionCleanup(parameter: ForwardPublicParameter): String? =
    if (parameter.type is BridgeType.Collection) {
      "NugetListNative.Dispose(${parameter.name}Handle);"
    } else {
      null
    }

  private fun ForwardCallablePlan.nativeOutParameters(nativeCall: ForwardNativeCall): List<String> =
    nativeCall.parameters
      .filter { parameter -> parameter != errorSlot && parameter.direction == ForwardAbiDirection.OUT }
      .map { parameter -> "out ${parameter.transfer.type.csharpType()} ${parameter.name}" }

  private fun ForwardCallablePlan.nativeOutCirParameters(
    nativeCall: ForwardNativeCall,
  ): List<CirParameter> = nativeCall.parameters
    .filter { parameter -> parameter != errorSlot && parameter.direction == ForwardAbiDirection.OUT }
    .map { parameter ->
      val type: String = parameter.transfer.type.csharpType()
      CirParameter(parameter.name, type, "out $type")
    }

  private fun ForwardCallablePlan.resultProjection(
    nativeName: String,
    parameters: List<ForwardPublicParameter>,
    receiverArgument: String? = null,
    forceCustomBody: Boolean = false,
  ): CirResultProjection {
    val nativeCall: ForwardNativeCall = singleNativeImport()
    val prelude: List<String> = parameters.mapNotNull { parameter -> collectionPrelude(parameter) }
    val cleanup: List<String> = parameters.mapNotNull { parameter -> collectionCleanup(parameter) }
    val argumentList: List<String> =
      listOfNotNull(receiverArgument) + parameters.flatMap { parameter -> callArgument(parameter) }
    val callArguments: String = (argumentList + nativeOutParameters(nativeCall) + "out IntPtr error").joinToString(", ")
    val needsCustomParams: Boolean = forceCustomBody || parameters.any { parameter -> !parameter.type.isTrivialInput() }
    val result: BridgeType = publicSignature.result
    return when (result) {
      is BridgeType.ObjectHandle -> CirResultProjection(
        returnType = result.csharpType(),
        nativeReturnType = "IntPtr",
        body = checkedPointerBody(
          nativeName, callArguments, "return new ${result.csharpType()}(nativeResult);", prelude, cleanup,
        ),
      )

      is BridgeType.Collection -> CirResultProjection(
        returnType = result.csharpType(),
        nativeReturnType = "IntPtr",
        body = checkedCollectionBody(nativeName, callArguments, result, prelude, cleanup),
      )

      // ADR-014 (ordinary position, ADR-066's fixture gap): always a custom body, regardless of
      // `needsCustomParams` — a value class's zero-parameter own getter (`Newsroom.Code()`) would
      // otherwise fall through to the generic pass-through renderer, which has no wrap-in-struct
      // case. Scoped to a String underlying, matching the planner's own scoping.
      is BridgeType.ValueClass -> CirResultProjection(
        returnType = result.csharpType,
        nativeReturnType = "IntPtr",
        body = checkedPointerBody(
          nativeName,
          callArguments,
          "return new ${result.csharpType}(Marshal.PtrToStringUTF8(nativeResult)!);",
          prelude,
          cleanup,
        ),
      )

      is BridgeType.Nullable -> when (val type: BridgeType = result.type) {
        is BridgeType.ObjectHandle -> CirResultProjection(
          returnType = "${type.csharpType()}?",
          nativeReturnType = "IntPtr",
          body = checkedPointerBody(
            nativeName,
            callArguments,
            "return nativeResult == IntPtr.Zero ? null : new ${type.csharpType()}(nativeResult);",
            prelude,
            cleanup,
          ),
        )

        BridgeType.String -> CirResultProjection(
          returnType = "string?",
          nativeReturnType = "IntPtr",
          body = checkedPointerBody(
            nativeName, callArguments, "return Marshal.PtrToStringUTF8(nativeResult);", prelude, cleanup,
          ),
        )

        is BridgeType.Primitive -> {
          val valueType: String = type.csharpType()
          CirResultProjection(
            returnType = "$valueType?",
            nativeReturnType = "bool",
            body = checkedNullableValueBody(nativeName, callArguments, prelude, cleanup),
          )
        }

        else -> directOrCustomResultProjection(
          result, nativeCall.result, needsCustomParams, nativeName, callArguments, prelude, cleanup,
        )
      }

      else -> directOrCustomResultProjection(
        result, nativeCall.result, needsCustomParams, nativeName, callArguments, prelude, cleanup,
      )
    }
  }

  private fun directOrCustomResultProjection(
    result: BridgeType,
    wireType: ForwardAbiWireType,
    needsCustomParams: Boolean,
    nativeName: String,
    callArguments: String,
    prelude: List<String>,
    cleanup: List<String>,
  ): CirResultProjection = if (!needsCustomParams) {
    directResultProjection(result, wireType)
  } else {
    CirResultProjection(
      returnType = result.csharpType(),
      nativeReturnType = wireType.csharpType(),
      body = directCustomBody(nativeName, callArguments, result, wireType, prelude, cleanup),
    )
  }

  private fun directResultProjection(result: BridgeType, wireType: ForwardAbiWireType): CirResultProjection =
    CirResultProjection(
      returnType = result.csharpType(),
      nativeReturnType = wireType.csharpType(),
      body = "",
      hasCustomBody = false,
    )

  private fun checkedPointerBody(
    nativeName: String,
    arguments: String,
    result: String,
    prelude: List<String> = emptyList(),
    cleanup: List<String> = emptyList(),
  ): String = buildString {
    appendLine()
    prelude.forEach { line -> appendLine("            $line") }
    appendLine("            IntPtr nativeResult = $nativeName($arguments);")
    appendLine("            if (error != IntPtr.Zero)")
    appendLine("            {")
    appendLine("                throw NugetErrorNative.BuildException(error);")
    appendLine("            }")
    cleanup.forEach { line -> appendLine("            $line") }
    append("            $result")
  }

  private fun checkedNullableValueBody(
    nativeName: String,
    arguments: String,
    prelude: List<String> = emptyList(),
    cleanup: List<String> = emptyList(),
  ): String = buildString {
    appendLine()
    prelude.forEach { line -> appendLine("            $line") }
    appendLine("            bool hasValue = $nativeName($arguments);")
    appendLine("            if (error != IntPtr.Zero)")
    appendLine("            {")
    appendLine("                throw NugetErrorNative.BuildException(error);")
    appendLine("            }")
    cleanup.forEach { line -> appendLine("            $line") }
    append("            return hasValue ? valueOut : null;")
  }

  private fun checkedCollectionBody(
    nativeName: String,
    arguments: String,
    type: BridgeType.Collection,
    prelude: List<String> = emptyList(),
    cleanup: List<String> = emptyList(),
  ): String = when (type.kind) {
    CollectionKind.LIST, CollectionKind.MUTABLE_LIST -> {
      val elementType: String = requireNotNull(type.element) {
        "Forward CIR List result has no element type"
      }.csharpType()
      val mutable: Boolean = type.kind == CollectionKind.MUTABLE_LIST
      buildString {
        appendLine()
        prelude.forEach { line -> appendLine("            $line") }
        appendLine("            IntPtr listHandle = $nativeName($arguments);")
        appendLine("            if (error != IntPtr.Zero)")
        appendLine("            {")
        appendLine("                throw NugetErrorNative.BuildException(error);")
        appendLine("            }")
        cleanup.forEach { line -> appendLine("            $line") }
        appendLine("            int count = NugetListNative.Count(listHandle);")
        appendLine("            var result = new List<$elementType>(count);")
        appendLine("            for (int i = 0; i < count; i++)")
        appendLine("            {")
        appendLine("                result.Add(NugetMarshal.FromHandle<$elementType>(NugetListNative.Get(listHandle, i)));")
        appendLine("            }")
        appendLine("            NugetListNative.Dispose(listHandle);")
        append("            return " + if (mutable) "result;" else "result.AsReadOnly();")
      }
    }

    CollectionKind.MAP, CollectionKind.MUTABLE_MAP -> {
      val keyType: String = requireNotNull(type.key) { "Forward CIR Map result has no key type" }.csharpType()
      val valueType: String = requireNotNull(type.value) { "Forward CIR Map result has no value type" }.csharpType()
      buildString {
        appendLine()
        prelude.forEach { line -> appendLine("            $line") }
        appendLine("            IntPtr mapHandle = $nativeName($arguments);")
        appendLine("            if (error != IntPtr.Zero)")
        appendLine("            {")
        appendLine("                throw NugetErrorNative.BuildException(error);")
        appendLine("            }")
        cleanup.forEach { line -> appendLine("            $line") }
        appendLine("            int count = NugetMapNative.Count(mapHandle);")
        appendLine("            var result = new Dictionary<$keyType, $valueType>(count);")
        appendLine("            for (int i = 0; i < count; i++)")
        appendLine("            {")
        appendLine("                var key = NugetMarshal.FromHandle<$keyType>(NugetMapNative.KeyAt(mapHandle, i));")
        appendLine("                var value = NugetMarshal.FromHandle<$valueType>(NugetMapNative.ValueAt(mapHandle, i));")
        appendLine("                result[key] = value;")
        appendLine("            }")
        appendLine("            NugetMapNative.Dispose(mapHandle);")
        append("            return result;")
      }
    }

    CollectionKind.SET, CollectionKind.MUTABLE_SET -> {
      val elementType: String = requireNotNull(type.element) {
        "Forward CIR Set result has no element type"
      }.csharpType()
      buildString {
        appendLine()
        prelude.forEach { line -> appendLine("            $line") }
        appendLine("            IntPtr setHandle = $nativeName($arguments);")
        appendLine("            if (error != IntPtr.Zero)")
        appendLine("            {")
        appendLine("                throw NugetErrorNative.BuildException(error);")
        appendLine("            }")
        cleanup.forEach { line -> appendLine("            $line") }
        appendLine("            int count = NugetSetNative.Count(setHandle);")
        appendLine("            var result = new HashSet<$elementType>(count);")
        appendLine("            for (int i = 0; i < count; i++)")
        appendLine("            {")
        appendLine("                result.Add(NugetMarshal.FromHandle<$elementType>(NugetSetNative.ElementAt(setHandle, i)));")
        appendLine("            }")
        appendLine("            NugetSetNative.Dispose(setHandle);")
        append("            return result;")
      }
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
    prelude: List<String>,
    cleanup: List<String>,
  ): String = buildString {
    appendLine()
    prelude.forEach { line -> appendLine("            $line") }
    if (result == BridgeType.Unit) {
      appendLine("            $nativeName($arguments);")
    } else {
      appendLine("            ${wireType.csharpType()} nativeResult = $nativeName($arguments);")
    }
    appendLine("            if (error != IntPtr.Zero)")
    appendLine("            {")
    appendLine("                throw NugetErrorNative.BuildException(error);")
    appendLine("            }")
    cleanup.forEach { line -> appendLine("            $line") }
    if (result is BridgeType.Enum) {
      append("            return (${result.csharpType()})nativeResult;")
    } else if (result == BridgeType.String) {
      append("            return Marshal.PtrToStringUTF8(nativeResult)!;")
    } else if (result != BridgeType.Unit) {
      append("            return nativeResult;")
    }
  }

  private data class CirResultProjection(
    val returnType: String,
    val nativeReturnType: String,
    val body: String,
    val hasCustomBody: Boolean = true,
  )

  private fun BridgeType.csharpType(): String = when (this) {
    BridgeType.Unit -> "void"
    is BridgeType.Primitive -> kind.csharpType()
    BridgeType.Char -> "char"
    BridgeType.String -> "string"
    // ADR-066: the classifier already computed the correctly-qualified public spelling (bare
    // simple name in this class's own namespace, `global::Namespace.Name` otherwise) — mirrors
    // `BridgeType.Enum.csharpType`'s existing shape exactly.
    is BridgeType.ObjectHandle -> csharpType
    is BridgeType.ValueClass -> csharpType
    is BridgeType.Enum -> this.csharpType
    is BridgeType.Collection -> when (kind) {
      CollectionKind.LIST -> "IReadOnlyList<${requireNotNull(element).csharpType()}>"
      CollectionKind.MUTABLE_LIST -> "IList<${requireNotNull(element).csharpType()}>"
      CollectionKind.MAP ->
        "IReadOnlyDictionary<${requireNotNull(key).csharpType()}, ${requireNotNull(value).csharpType()}>"

      CollectionKind.MUTABLE_MAP ->
        "IDictionary<${requireNotNull(key).csharpType()}, ${requireNotNull(value).csharpType()}>"

      CollectionKind.SET -> "IReadOnlySet<${requireNotNull(element).csharpType()}>"
      CollectionKind.MUTABLE_SET -> "ISet<${requireNotNull(element).csharpType()}>"
    }

    is BridgeType.Nullable -> "${type.csharpType()}?"
    else -> error("Forward CIR direct-value projection cannot render public type $this")
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
