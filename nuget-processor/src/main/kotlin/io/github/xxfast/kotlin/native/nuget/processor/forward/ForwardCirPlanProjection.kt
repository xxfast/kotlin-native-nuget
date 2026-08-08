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
    val result: BridgeType = plan.publicSignature.result
    val body: String = when {
      underlyingIsString -> "Marshal.PtrToStringUTF8(CreateChecked$nativeSuffix($paramNames))!"
      // ADR-077: an enum underlying crosses as its int ordinal; cast it back to the enum for the
      // struct member assignment.
      result is BridgeType.Enum -> "(${result.csharpType})CreateChecked$nativeSuffix($paramNames)"
      else -> "CreateChecked$nativeSuffix($paramNames)"
    }
    val nativeCall: ForwardNativeCall = plan.nativeExports.single()
    return CirValueClassConstructor(
      parameters = publicParams,
      nativeName = nativeCall.exportName,
      body = body,
      hasErrorCheck = plan.errorSlot != null,
      nativeSuffix = nativeSuffix,
      // ADR-077: the DllImport and its call site follow the plan's wire shape, so an enum
      // parameter imports as `int` and is passed as `(int)name`, matching the Kotlin export.
      nativeParameters = plan.nativeInCirParameters(nativeCall.parameters),
      nativeArguments = plan.publicSignature.parameters.flatMap { plan.callArgument(it) },
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
    val inner: BridgeType = nullable.type
    // ADR-079: a Primitive/Enum-underlying value class rides the same two-call shape, with the
    // `_value` DllImport declared at the underlying's wire.
    require(
      inner is BridgeType.Primitive || inner == BridgeType.Instant ||
          inner is BridgeType.ValueClass || inner is BridgeType.Enum
    ) {
      "Legacy two-call plan ${plan.invocation.symbol} requires a nullable primitive, Instant, " +
          "enum or value class"
    }
    val presence: ForwardNativeCall = plan.nativeImports[0]
    val value: ForwardNativeCall = plan.nativeImports[1]
    val publicParams: List<CirParameter> = plan.publicParameters()
    val csName: String = plan.publicSignature.name.removePrefix("@")
    // ADR-076: the DllImport/local-variable wire type is always the raw representation (`long`
    // for Instant); the public return type is the semantic one (`DateTimeOffset?`).
    val dllImportReturnType: String = when (inner) {
      is BridgeType.Primitive -> inner.csharpType()
      is BridgeType.ValueClass -> valueClassUnderlyingWireCs(inner.underlying)
      // ADR-080: the `_value` call returns the bare `int` ordinal.
      is BridgeType.Enum -> "int"
      else -> "long"
    }
    val publicReturnType: String = "${inner.csharpType()}?"
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
    val returnExpression: String = when {
      inner == BridgeType.Instant ->
        "new global::System.DateTimeOffset(__nuget_value, global::System.TimeSpan.Zero)"

      // ADR-079: rebuild the record struct from the underlying wire value the `_value` call
      // returned; the null case already returned above on `!__nuget_hasValue`.
      inner is BridgeType.ValueClass -> valueClassReconstructionCs(inner, "__nuget_value")
      // ADR-080: lift the ordinal back into the enum.
      inner is BridgeType.Enum -> "(${inner.csharpType()})__nuget_value"
      else -> "__nuget_value"
    }
    val body: String = buildString {
      appendLine()
      appendLine("            bool __nuget_hasValue = $presenceName($hasValueCallArgs);")
      appendLine("            if (__nuget_hasValueError != IntPtr.Zero)")
      appendLine("            {")
      appendLine("                throw NugetErrorNative.BuildException(__nuget_hasValueError);")
      appendLine("            }")
      appendLine("            if (!__nuget_hasValue) return null;")
      appendLine("            $dllImportReturnType __nuget_value = $valueName($valueCallArgs);")
      appendLine("            if (__nuget_valueError != IntPtr.Zero)")
      appendLine("            {")
      appendLine("                throw NugetErrorNative.BuildException(__nuget_valueError);")
      appendLine("            }")
      append("            return $returnExpression;")
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
        returnType = dllImportReturnType,
        name = valueName,
        parameters = inParams,
        visibility = CirVisibility.PRIVATE,
        hasSyncErrorOut = true,
        marshalBooleanReturn = dllImportReturnType == "bool",
      ),
      CirMethod(
        name = plan.publicSignature.name,
        returnType = publicReturnType,
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
    isVirtual: Boolean = false,
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
      isVirtual = isVirtual,
      // Unlike static/extension (which hand-build their own CirDllImport), the DllImport here is
      // derived generically by CirClass.methodNativeImport from this CirMethod, and its trailing
      // `out IntPtr error` must be present whether or not the *body* is hand-written, so this is
      // deliberately not gated by `!result.hasCustomBody` the way static/extension are.
      isSyncErrorCheckEnabled = plan.errorSlot != null,
      extraNativeParams = plan.nativeOutDeclarationParameters(nativeCall),
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
    publicSignature.parameters.map { parameter ->
      CirParameter(
        name = parameter.name,
        type = parameter.type.csharpType(),
        isReferenceType = parameter.type.isCSharpReferenceType(),
      )
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
    .map { native -> CirParameter(native.name, native.nativeCsharpType()) }

  private fun ForwardAbiParameter.nativeCsharpType(): String {
    val type: BridgeType = transfer.type
    // ADR-077 sub-items 3/4: a nullable value class with a *String* underlying rides the nullable
    // string wire, so the DllImport parameter must be `string?` for the same CS8604 reason as a
    // nullable String. An ObjectHandle underlying stays on the plain IntPtr wire.
    val isNullableStringWire: Boolean = type is BridgeType.Nullable &&
        (
            type.type == BridgeType.String ||
                (type.type as? BridgeType.ValueClass)?.underlying == BridgeType.String
            )
    return if (isNullableStringWire) "string?" else wireType.csharpType()
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
      // ADR-076: UtcTicks is load-bearing (verified) -- a consumer holding a non-UTC
      // DateTimeOffset must not send its wall-clock ticks.
      BridgeType.Instant -> listOf("${parameter.name}.UtcTicks")
      is BridgeType.ObjectHandle -> listOf("${parameter.name}._handle")
      // ADR-040 sub-decision B: an interface-typed parameter's public static type is `IFoo`, which
      // does not carry `._handle` (that is only true of the generated `Foo` backing class). The
      // one shared reflective helper extracts it regardless of which concrete type implements
      // `IFoo`, and throws NotSupportedException for a C#-implemented (non-Kotlin-backed) one.
      is BridgeType.Interface -> listOf("NugetMarshal.HandleOf(${parameter.name})")
      is BridgeType.Collection -> listOf("${parameter.name}Handle")
      // ADR-077: the generated `readonly record struct` capitalizes the Kotlin underlying
      // property (`value` -> `Value`, CirClassTranslator); the unwrapped value is lowered to its
      // wire form per underlying (sub-item 4), and Kotlin re-wraps it on the other side.
      is BridgeType.ValueClass -> {
        val prop: String = type.underlyingPropertyName.replaceFirstChar { it.uppercase() }
        val unwrapped = "${parameter.name}.$prop"
        listOf(
          when (type.underlying) {
            is BridgeType.Enum -> "(int)$unwrapped"
            is BridgeType.ObjectHandle -> "$unwrapped._handle"
            else -> unwrapped
          }
        )
      }

      is BridgeType.Nullable -> when (val inner = type.type) {
        BridgeType.String -> listOf(parameter.name)
        is BridgeType.ObjectHandle -> listOf("${parameter.name}?._handle ?? IntPtr.Zero")
        is BridgeType.Interface -> listOf(
          "${parameter.name} != null ? NugetMarshal.HandleOf(${parameter.name}) : IntPtr.Zero",
        )

        is BridgeType.Primitive -> listOf("${parameter.name}.HasValue", "${parameter.name}.GetValueOrDefault()")
        // ADR-080: same HasValue/GetValueOrDefault pair, the value half lowered to the ordinal.
        is BridgeType.Enum -> listOf(
          "${parameter.name}.HasValue", "(int)${parameter.name}.GetValueOrDefault()",
        )

        // ADR-076: same HasValue/GetValueOrDefault pair as the nullable Primitive case above,
        // with the ticks conversion applied to the value half.
        BridgeType.Instant -> listOf(
          "${parameter.name}.HasValue", "${parameter.name}.GetValueOrDefault().UtcTicks",
        )

        // ADR-075: a nullable collection *parameter* (e.g. a data class's `notes: List<String>?`
        // constructor parameter) shares [ForwardPropertyPlan]'s setter route exactly: the local
        // handle variable [collectionPrelude] built already folds the null check in, so the call
        // argument itself is unconditional either way.
        is BridgeType.Collection -> listOf("${parameter.name}Handle")
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
            val unwrapped = "${parameter.name}.GetValueOrDefault().$prop"
            listOf(
              "${parameter.name}.HasValue",
              if (inner.underlying is BridgeType.Enum) "(int)$unwrapped" else unwrapped,
            )
          } else {
            val unwrapped = "${parameter.name}?.$prop"
            listOf(
              if (inner.underlying is BridgeType.ObjectHandle) {
                "$unwrapped._handle ?? IntPtr.Zero"
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

  private fun ForwardCallablePlan.collectionPrelude(parameter: ForwardPublicParameter): String? {
    val (type, nullable) = parameter.type.asNullableAwareCollection() ?: return null
    val factory: String = when (type.kind) {
      CollectionKind.LIST, CollectionKind.MUTABLE_LIST -> "CreateList"
      CollectionKind.MAP, CollectionKind.MUTABLE_MAP -> "CreateMap"
      CollectionKind.SET, CollectionKind.MUTABLE_SET -> "CreateSet"
    }
    // ADR-081: a value-class component is projected to its underlying per element before boxing.
    val source: String = collectionCreateArgument(parameter.name, type) { it.csharpType() }
    val value: String = if (nullable) {
      "${parameter.name} != null ? NugetMarshal.$factory($source) : IntPtr.Zero"
    } else {
      "NugetMarshal.$factory($source)"
    }
    return "IntPtr ${parameter.name}Handle = $value;"
  }

  // ADR-073: load-bearing, not cosmetic. All three Dispose members bind to the same
  // `nuget_dispose` entry point, so emitting NugetListNative.Dispose for a map/set handle would be
  // *runtime*-correct, but a callable whose only collection is a Map/Set never causes
  // NugetMapNative/NugetSetNative to be tracked as needed for List, so NugetListNative is never
  // emitted at all, and the mismatched call is a CS0103 compile error, not a runtime bug.
  private fun ForwardCallablePlan.collectionCleanup(parameter: ForwardPublicParameter): String? {
    val (type, nullable) = parameter.type.asNullableAwareCollection() ?: return null
    val native: String = when (type.kind) {
      CollectionKind.LIST, CollectionKind.MUTABLE_LIST -> "NugetListNative"
      CollectionKind.MAP, CollectionKind.MUTABLE_MAP -> "NugetMapNative"
      CollectionKind.SET, CollectionKind.MUTABLE_SET -> "NugetSetNative"
    }
    // ADR-075: a null source value never built a handle above (it stayed IntPtr.Zero), and
    // `nuget_dispose`'s `handle.asStableRef<Any>().dispose()` is not null-safe.
    return if (nullable) {
      "if (${parameter.name}Handle != IntPtr.Zero) { $native.Dispose(${parameter.name}Handle); }"
    } else {
      "$native.Dispose(${parameter.name}Handle);"
    }
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
      .map { parameter -> "out ${parameter.transfer.type.csharpType()} ${parameter.name}" }

  private fun ForwardCallablePlan.nativeOutDeclarationParameters(
    nativeCall: ForwardNativeCall,
  ): List<String> = nativeCall.parameters
    .filter { parameter ->
      parameter != errorSlot && parameter.direction == ForwardAbiDirection.OUT
    }
    .map { parameter ->
      val marshal: String = outParameterMarshalPrefix(parameter.transfer.type)
      "${marshal}out ${parameter.transfer.type.csharpType()} ${parameter.name}"
    }

  private fun ForwardCallablePlan.nativeOutCirParameters(
    nativeCall: ForwardNativeCall,
  ): List<CirParameter> = nativeCall.parameters
    .filter { parameter -> parameter != errorSlot && parameter.direction == ForwardAbiDirection.OUT }
    .map { parameter ->
      val type: String = parameter.transfer.type.csharpType()
      val marshal: String = outParameterMarshalPrefix(parameter.transfer.type)
      CirParameter(parameter.name, type, "${marshal}out $type")
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

      // ADR-040: the public return type is the projected interface (`IPet`); construction uses
      // the generated backing wrapper class (`Pet`) instead.
      is BridgeType.Interface -> CirResultProjection(
        returnType = result.csharpType(),
        nativeReturnType = "IntPtr",
        body = checkedPointerBody(
          nativeName, callArguments, "return new ${result.backingType}(nativeResult);", prelude, cleanup,
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
            "return ${valueClassReconstructionCs(result, "nativeResult")};",
            prelude,
            cleanup,
          ),
        )
      }

      // ADR-076: always a custom body, regardless of `needsCustomParams` -- same reasoning as
      // ValueClass above: a raw `long -> DateTimeOffset` C# cast is illegal, so this can never go
      // through directOrCustomResultProjection's no-custom-body cast route.
      BridgeType.Instant -> CirResultProjection(
        returnType = result.csharpType(),
        nativeReturnType = "long",
        body = checkedInstantBody(nativeName, callArguments, prelude, cleanup),
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

        is BridgeType.Interface -> CirResultProjection(
          returnType = "${type.csharpType()}?",
          nativeReturnType = "IntPtr",
          body = checkedPointerBody(
            nativeName,
            callArguments,
            "return nativeResult == IntPtr.Zero ? null : new ${type.backingType}(nativeResult);",
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
              ),
            )
          } else {
            CirResultProjection(
              returnType = "${type.csharpType}?",
              nativeReturnType = "IntPtr",
              body = checkedPointerBody(
                nativeName,
                callArguments,
                "return nativeResult == IntPtr.Zero ? null : " +
                    "${valueClassReconstructionCs(type, "nativeResult")};",
                prelude,
                cleanup,
              ),
            )
          }

        is BridgeType.Primitive -> {
          val valueType: String = type.csharpType()
          CirResultProjection(
            returnType = "$valueType?",
            nativeReturnType = "bool",
            body = checkedNullableValueBody(nativeName, callArguments, prelude, cleanup),
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
          ),
        )

        // ADR-076: same BOOLEAN + valueOut shape as the nullable Primitive case above; valueOut
        // itself is declared as a raw `long` (its transfer type is Primitive(LONG), not Instant --
        // see ForwardCallablePlanner.nullableResultShape), converted to DateTimeOffset here.
        BridgeType.Instant -> CirResultProjection(
          returnType = "${type.csharpType()}?",
          nativeReturnType = "bool",
          body = checkedNullableInstantValueBody(nativeName, callArguments, prelude, cleanup),
        )

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
   */
  private fun valueClassReconstructionCs(type: BridgeType.ValueClass, wireValue: String): String {
    val inner: String = when (val underlying: BridgeType = type.underlying) {
      BridgeType.String -> "Marshal.PtrToStringUTF8($wireValue)!"
      is BridgeType.Enum -> "(${underlying.csharpType})$wireValue"
      is BridgeType.ObjectHandle -> "new ${underlying.csharpType()}($wireValue)"
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
    prelude: List<String> = emptyList(),
    cleanup: List<String> = emptyList(),
  ): String = buildString {
    appendLine()
    prelude.forEach { line -> appendLine("            $line") }
    appendLine("            $wireCs nativeResult = $nativeName($arguments);")
    appendLine("            if (error != IntPtr.Zero)")
    appendLine("            {")
    appendLine("                throw NugetErrorNative.BuildException(error);")
    appendLine("            }")
    cleanup.forEach { line -> appendLine("            $line") }
    append("            $result")
  }

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

  /** [returnExpression] defaults to the bare `valueOut` a nullable primitive returns; ADR-079's
   *  value-class results pass the reconstruction (`hasValue ? new Dosage(valueOut) : ...`). */
  private fun checkedNullableValueBody(
    nativeName: String,
    arguments: String,
    prelude: List<String> = emptyList(),
    cleanup: List<String> = emptyList(),
    returnExpression: String = "hasValue ? valueOut : null",
  ): String = buildString {
    appendLine()
    prelude.forEach { line -> appendLine("            $line") }
    appendLine("            bool hasValue = $nativeName($arguments);")
    appendLine("            if (error != IntPtr.Zero)")
    appendLine("            {")
    appendLine("                throw NugetErrorNative.BuildException(error);")
    appendLine("            }")
    cleanup.forEach { line -> appendLine("            $line") }
    append("            return $returnExpression;")
  }

  /** ADR-076: same shape as [checkedNullableValueBody], except `valueOut` is a raw `long` of
   *  ticks (its own transfer stays `Primitive(LONG)`, never `Instant`) and must be lifted into a
   *  `DateTimeOffset` in the return expression. */
  private fun checkedNullableInstantValueBody(
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
    append(
      "            return hasValue ? " +
          "new global::System.DateTimeOffset(valueOut, global::System.TimeSpan.Zero) : " +
          "(global::System.DateTimeOffset?)null;",
    )
  }

  /** ADR-076: non-nullable Instant result -- the native call returns a raw `long` of ticks,
   *  which must be lifted into a `DateTimeOffset` in the return expression. Always a custom body
   *  (see the `BridgeType.Instant` branch in [resultProjection]). */
  private fun checkedInstantBody(
    nativeName: String,
    arguments: String,
    prelude: List<String> = emptyList(),
    cleanup: List<String> = emptyList(),
  ): String = buildString {
    appendLine()
    prelude.forEach { line -> appendLine("            $line") }
    appendLine("            long nativeResult = $nativeName($arguments);")
    appendLine("            if (error != IntPtr.Zero)")
    appendLine("            {")
    appendLine("                throw NugetErrorNative.BuildException(error);")
    appendLine("            }")
    cleanup.forEach { line -> appendLine("            $line") }
    append(
      "            return new global::System.DateTimeOffset(nativeResult, global::System.TimeSpan.Zero);",
    )
  }

  private fun checkedCollectionBody(
    nativeName: String,
    arguments: String,
    type: BridgeType.Collection,
    prelude: List<String> = emptyList(),
    cleanup: List<String> = emptyList(),
  ): String = when (type.kind) {
    CollectionKind.LIST, CollectionKind.MUTABLE_LIST -> {
      val element: BridgeType = requireNotNull(type.element) {
        "Forward CIR List result has no element type"
      }
      val elementType: String = element.csharpType()
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
        val read: String =
          collectionComponentRead("NugetListNative.Get(listHandle, i)", element) { it.csharpType() }
        appendLine("                result.Add($read);")
        appendLine("            }")
        appendLine("            NugetListNative.Dispose(listHandle);")
        append("            return " + if (mutable) "result;" else "result.AsReadOnly();")
      }
    }

    CollectionKind.MAP, CollectionKind.MUTABLE_MAP -> {
      val key: BridgeType = requireNotNull(type.key) { "Forward CIR Map result has no key type" }
      val value: BridgeType = requireNotNull(type.value) { "Forward CIR Map result has no value type" }
      val keyType: String = key.csharpType()
      val valueType: String = value.csharpType()
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
        val readKey: String =
          collectionComponentRead("NugetMapNative.KeyAt(mapHandle, i)", key) { it.csharpType() }
        val readValue: String =
          collectionComponentRead("NugetMapNative.ValueAt(mapHandle, i)", value) { it.csharpType() }
        appendLine("                var key = $readKey;")
        appendLine("                var value = $readValue;")
        appendLine("                result[key] = value;")
        appendLine("            }")
        appendLine("            NugetMapNative.Dispose(mapHandle);")
        append("            return result;")
      }
    }

    CollectionKind.SET, CollectionKind.MUTABLE_SET -> {
      val element: BridgeType = requireNotNull(type.element) {
        "Forward CIR Set result has no element type"
      }
      val elementType: String = element.csharpType()
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
        val read: String = collectionComponentRead(
          "NugetSetNative.ElementAt(setHandle, i)",
          element,
        ) { it.csharpType() }
        appendLine("                result.Add($read);")
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
    // ADR-076: the public C# type is always System.DateTimeOffset, fully qualified so no "using
    // System;" is required in the generated file.
    BridgeType.Instant -> "global::System.DateTimeOffset"
    // ADR-066: the classifier already computed the correctly-qualified public spelling (bare
    // simple name in this class's own namespace, `global::Namespace.Name` otherwise) — mirrors
    // `BridgeType.Enum.csharpType`'s existing shape exactly.
    is BridgeType.ObjectHandle -> csharpType
    // ADR-040: the public C# spelling is the projected interface (`IPet`), never the backing
    // wrapper class — the wrapper is a construction-only implementation detail.
    is BridgeType.Interface -> csharpType
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
    BridgeType.String, is BridgeType.ObjectHandle, is BridgeType.Interface, is BridgeType.Collection -> true
    // ADR-076: DateTimeOffset is a C# value type, same as Enum/ValueClass.
    BridgeType.Unit, is BridgeType.Primitive, BridgeType.Char, BridgeType.Instant,
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
