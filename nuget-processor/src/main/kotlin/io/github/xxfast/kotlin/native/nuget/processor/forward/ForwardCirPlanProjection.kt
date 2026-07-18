package io.github.xxfast.kotlin.native.nuget.processor.forward

import io.github.xxfast.kotlin.native.nuget.processor.cir.CirDllImport
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirConstructor
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirMember
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirMethod
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirParameter
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirVisibility

/** Projects the direct-value migration slice of a callable plan into CIR. */
internal object ForwardCirPlanProjection {
  fun constructor(plan: ForwardCallablePlan, nativeSuffix: String = ""): CirConstructor {
    require(plan.invocation.origin == ForwardCallableOrigin.CONSTRUCTOR) {
      "Forward CIR constructor projection received ${plan.invocation.origin}"
    }
    val nativeCall: ForwardNativeCall = plan.singleNativeImport()
    return CirConstructor(
      parameters = plan.publicParameters(nativeCall.parameters),
      body = "",
      hasErrorCheck = plan.errorSlot != null,
      nativeSuffix = nativeSuffix,
    )
  }

  fun static(plan: ForwardCallablePlan, libraryName: String): List<CirMember> {
    val nativeCall: ForwardNativeCall = plan.singleNativeImport()
    require(plan.invocation.origin in setOf(
      ForwardCallableOrigin.TOP_LEVEL,
      ForwardCallableOrigin.OBJECT,
      ForwardCallableOrigin.COMPANION,
    )) { "Forward CIR static projection received ${plan.invocation.origin}" }
    val parameters: List<CirParameter> = plan.publicParameters(nativeCall.parameters)
    val identifier: String = plan.publicSignature.name.removePrefix("@")
    val nativeName: String = if (plan.invocation.origin == ForwardCallableOrigin.COMPANION) {
      "Native_Companion_$identifier"
    } else {
      "Native_$identifier"
    }
    val result: CirResultProjection = plan.resultProjection(
      nativeName = nativeName,
      arguments = parameters.map { parameter -> parameter.name },
    )
    return listOf(
      CirDllImport(
        libraryName = libraryName,
        entryPoint = nativeCall.exportName,
        returnType = result.nativeReturnType,
        name = nativeName,
        parameters = parameters + plan.nativeOutCirParameters(nativeCall),
        visibility = CirVisibility.PRIVATE,
        hasSyncErrorOut = plan.errorSlot != null,
        marshalBooleanReturn = result.nativeReturnType == "bool",
      ),
      CirMethod(
        name = plan.publicSignature.name,
        returnType = result.returnType,
        nativeReturnType = result.nativeReturnType,
        nativeName = nativeName,
        parameters = parameters.map { parameter -> parameter.copy(nativeType = parameter.type) },
        body = result.body,
        isStatic = true,
        isSyncErrorCheckEnabled = !result.hasCustomBody && plan.errorSlot != null,
        hasCustomBody = result.hasCustomBody,
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

    val parameters: List<CirParameter> = plan.publicParameters(nativeCall.parameters.drop(1))
    val nativeName: String = nativeCall.exportName.removePrefix("${nativePrefix}_")
    require(nativeName != nativeCall.exportName) {
      "Forward CIR class plan ${plan.invocation.symbol} export ${nativeCall.exportName} " +
          "does not begin with $nativePrefix"
    }

    val result: CirResultProjection = plan.resultProjection(
      nativeName = "Native_${plan.publicSignature.name}",
      arguments = listOf("_handle") + parameters.map { parameter -> parameter.name },
    )
    return CirMethod(
      name = plan.publicSignature.name,
      returnType = result.returnType,
      nativeReturnType = result.nativeReturnType,
      nativeName = nativeName,
      parameters = parameters,
      body = result.body,
      isOverride = isOverride,
      isSyncErrorCheckEnabled = plan.errorSlot != null,
      extraNativeParams = plan.nativeOutParameters(nativeCall),
      hasCustomBody = result.hasCustomBody,
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
    val parameters: List<CirParameter> = listOf(
      CirParameter(receiver.name, receiverType, receiver.wireType.csharpType()),
    ) + plan.publicParameters(nativeCall.parameters.drop(1))
    val nativeName: String = "Native_${plan.publicSignature.name}"
    val result: CirResultProjection = plan.resultProjection(
      nativeName = nativeName,
      arguments = parameters.map { parameter -> parameter.name },
    )

    val nativeImport = CirDllImport(
      libraryName = libraryName,
      entryPoint = nativeCall.exportName,
      returnType = result.nativeReturnType,
      name = nativeName,
      parameters = parameters + plan.nativeOutCirParameters(nativeCall),
      visibility = CirVisibility.PRIVATE,
      hasSyncErrorOut = plan.errorSlot != null,
      marshalBooleanReturn = result.nativeReturnType == "bool",
    )
    val wrapper = CirMethod(
      name = plan.publicSignature.name,
      returnType = result.returnType,
      nativeReturnType = result.nativeReturnType,
      nativeName = nativeName,
      parameters = parameters.map { parameter -> parameter.copy(nativeType = parameter.type) },
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

  private fun ForwardCallablePlan.publicParameters(
    nativeParameters: List<ForwardAbiParameter>,
  ): List<CirParameter> {
    val error: ForwardAbiParameter? = errorSlot
    if (error != null) {
      require(nativeParameters.lastOrNull() == error) {
        "Forward CIR plan ${invocation.symbol} must end with its error slot"
      }
    }
    val valueParameters: List<ForwardAbiParameter> = nativeParameters
      .filter { parameter -> parameter.direction == ForwardAbiDirection.IN }
    require(valueParameters.size == publicSignature.parameters.size) {
      "Forward CIR plan ${invocation.symbol} has ${valueParameters.size} native values but " +
          "${publicSignature.parameters.size} public parameters"
    }
    return publicSignature.parameters.zip(valueParameters).map { (public, native) ->
      require(public.name == native.name && native.direction == ForwardAbiDirection.IN) {
        "Forward CIR plan ${invocation.symbol} parameter ${public.name} is not a direct input"
      }
      CirParameter(public.name, public.type.csharpType(), native.wireType.csharpType())
    }
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
    arguments: List<String>,
  ): CirResultProjection {
    val callArguments: String = (arguments + nativeOutParameters(nativeImports.single()) +
        "out IntPtr error").joinToString(", ")
    val result: BridgeType = publicSignature.result
    return when (result) {
      is BridgeType.ObjectHandle -> CirResultProjection(
        returnType = result.csharpType(),
        nativeReturnType = "IntPtr",
        body = checkedPointerBody(nativeName, callArguments, "return new ${result.csharpType()}(nativeResult);"),
      )

      is BridgeType.Collection -> CirResultProjection(
        returnType = result.csharpType(),
        nativeReturnType = "IntPtr",
        body = checkedListBody(nativeName, callArguments, result),
      )

      is BridgeType.Nullable -> when (val type: BridgeType = result.type) {
        is BridgeType.ObjectHandle -> CirResultProjection(
          returnType = "${type.csharpType()}?",
          nativeReturnType = "IntPtr",
          body = checkedPointerBody(
            nativeName,
            callArguments,
            "return nativeResult == IntPtr.Zero ? null : new ${type.csharpType()}(nativeResult);",
          ),
        )

        BridgeType.String -> CirResultProjection(
          returnType = "string?",
          nativeReturnType = "IntPtr",
          body = checkedPointerBody(nativeName, callArguments, "return Marshal.PtrToStringUTF8(nativeResult);"),
        )

        is BridgeType.Primitive -> {
          val valueType: String = type.csharpType()
          CirResultProjection(
            returnType = "$valueType?",
            nativeReturnType = "bool",
            body = checkedNullableValueBody(nativeName, callArguments),
          )
        }

        else -> directResultProjection(result, nativeImports.single().result)
      }

      else -> directResultProjection(result, nativeImports.single().result)
    }
  }

  private fun directResultProjection(result: BridgeType, wireType: ForwardAbiWireType): CirResultProjection =
    CirResultProjection(
      returnType = result.csharpType(),
      nativeReturnType = wireType.csharpType(),
      body = "",
      hasCustomBody = false,
    )

  private fun checkedPointerBody(nativeName: String, arguments: String, result: String): String = buildString {
    appendLine()
    appendLine("            IntPtr nativeResult = $nativeName($arguments);")
    appendLine("            if (error != IntPtr.Zero)")
    appendLine("            {")
    appendLine("                throw NugetErrorNative.BuildException(error);")
    appendLine("            }")
    append("            $result")
  }

  private fun checkedNullableValueBody(nativeName: String, arguments: String): String = buildString {
    appendLine()
    appendLine("            bool hasValue = $nativeName($arguments);")
    appendLine("            if (error != IntPtr.Zero)")
    appendLine("            {")
    appendLine("                throw NugetErrorNative.BuildException(error);")
    appendLine("            }")
    append("            return hasValue ? valueOut : null;")
  }

  private fun checkedListBody(nativeName: String, arguments: String, type: BridgeType.Collection): String {
    val elementType: String = requireNotNull(type.element) { "Forward CIR List result has no element type" }.csharpType()
    val mutable: Boolean = type.kind == CollectionKind.MUTABLE_LIST
    return buildString {
      appendLine()
      appendLine("            IntPtr listHandle = $nativeName($arguments);")
      appendLine("            if (error != IntPtr.Zero)")
      appendLine("            {")
      appendLine("                throw NugetErrorNative.BuildException(error);")
      appendLine("            }")
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

  private data class CirResultProjection(
    val returnType: String,
    val nativeReturnType: String,
    val body: String,
    val hasCustomBody: Boolean = true,
  )

  private fun BridgeType.csharpType(): String = when (this) {
    BridgeType.Unit -> "void"
    is BridgeType.Primitive -> kind.csharpType()
    BridgeType.String -> "string"
    is BridgeType.ObjectHandle -> qualifiedName.substringAfterLast('.')
    is BridgeType.Enum -> qualifiedName.substringAfterLast('.')
    is BridgeType.Collection -> when (kind) {
      CollectionKind.LIST -> "IReadOnlyList<${requireNotNull(element).csharpType()}>"
      CollectionKind.MUTABLE_LIST -> "IList<${requireNotNull(element).csharpType()}>"
      else -> error("Forward CIR Phase 4 cannot render collection $kind")
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
    ForwardAbiWireType.UINT16, ForwardAbiWireType.CHAR16 -> "ushort"
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
