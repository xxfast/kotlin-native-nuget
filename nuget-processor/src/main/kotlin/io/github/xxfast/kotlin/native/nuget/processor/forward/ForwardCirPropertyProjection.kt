package io.github.xxfast.kotlin.native.nuget.processor.forward

import io.github.xxfast.kotlin.native.nuget.processor.cir.CirDllImport
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirExtraNative
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirMember
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirMethod
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirParameter
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirProperty
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirVisibility

/** C# projection for the planned property path. */
internal object ForwardCirPropertyProjection {
  fun classProperty(
    plan: ForwardPropertyPlan,
    isOverride: Boolean = false,
    isVirtual: Boolean = false,
  ): CirProperty {
    require(plan.position == ForwardPropertyPosition.CLASS) { "Expected class property plan" }
    return property(plan, receiver = "_handle", isStatic = false, isOverride = isOverride, isVirtual = isVirtual)
  }

  fun staticProperty(plan: ForwardPropertyPlan, libraryName: String): List<CirMember> {
    require(plan.position == ForwardPropertyPosition.TOP_LEVEL || plan.position == ForwardPropertyPosition.COMPANION) {
      "Expected static property plan"
    }
    val imports: List<CirDllImport> = plan.calls().map { call -> nativeImport(call, libraryName, emptyList(), plan) }
    return imports + property(plan, receiver = "", isStatic = true)
  }

  fun extension(plan: ForwardPropertyPlan, libraryName: String): List<CirMember> {
    require(plan.position == ForwardPropertyPosition.EXTENSION) { "Expected extension property plan" }
    val receiver = plan.receiver as ForwardPropertyReceiver.Value
    val publicReceiver: String = receiver.type.csharpType()
    val nativeReceiver: String = plan.calls().first().parameters
      .first { parameter -> parameter.name == "receiver" }
      .wireType.csharpWireType()
    // ADR-075: an extension receiver that is a value class passes its underlying value to the
    // native call, exactly like the value class's own generated members
    // (`renderValueClassMembers`'s `underlyingName` -- the Kotlin `value` property capitalized).
    val receiverArgument: String = when (val receiverType = receiver.type) {
      is BridgeType.ObjectHandle -> "receiver._handle"
      is BridgeType.ValueClass ->
        "receiver.${receiverType.underlyingPropertyName.replaceFirstChar { it.uppercase() }}"

      else -> "receiver"
    }
    val imports: List<CirMember> = plan.calls().map { call ->
      nativeImport(call, libraryName, listOf(CirParameter("receiver", nativeReceiver)), plan)
    }
    val getter = CirMethod(
      name = "Get${plan.publicName}",
      returnType = plan.type.csharpType(),
      nativeReturnType = plan.getter.calls().first().result.csharpWireType(),
      parameters = listOf(CirParameter("receiver", publicReceiver)),
      body = getterBody(plan, receiverArgument),
      isStatic = true,
      isExtension = true,
      hasCustomBody = true,
    )
    val setter: CirMethod? = plan.setter?.let {
      CirMethod(
        name = "Set${plan.publicName}",
        returnType = "void",
        parameters = listOf(CirParameter("receiver", publicReceiver), CirParameter("value", plan.type.csharpType())),
        body = setterBody(plan, receiverArgument),
        isStatic = true,
        isExtension = true,
        hasCustomBody = true,
      )
    }
    return imports + listOfNotNull(getter, setter)
  }

  private fun property(
    plan: ForwardPropertyPlan,
    receiver: String,
    isStatic: Boolean,
    isOverride: Boolean = false,
    isVirtual: Boolean = false,
  ): CirProperty {
    val directGetter: ForwardNativeCall = plan.getter.calls().first()
    return CirProperty(
      name = plan.publicName,
      type = plan.type.csharpType(),
      nativeReturnType = directGetter.result.csharpWireType(),
      nativeSetterType = if (plan.setter != null) setterNativeType(plan.type) else directGetter.result.csharpWireType(),
      nativeName = plan.kotlinName,
      getter = getterBody(plan, receiver),
      setter = plan.setter?.let { setterBody(plan, receiver) },
      extraNatives = classExtraNatives(plan),
      isStatic = isStatic,
      isOverride = isOverride,
      isVirtual = isVirtual,
      hasSyncErrorOut = true,
    )
  }

  private fun classExtraNatives(plan: ForwardPropertyPlan): List<CirExtraNative> = buildList {
    if (plan.getter is ForwardPropertyGetter.LegacyTwoCall) {
      val value = plan.getter.value
      add(
        CirExtraNative(
          "get_${plan.kotlinName}_value",
          value.result.csharpWireType(),
          "Native_Get_${plan.kotlinName}_value",
          hasSyncErrorOut = true
        )
      )
    }
    if (plan.setter is ForwardPropertySetter.NullableDispatch) {
      add(
        CirExtraNative(
          "set_${plan.kotlinName}_null",
          "void",
          "Native_Set_${plan.kotlinName}_null",
          hasSyncErrorOut = true
        )
      )
    }
  }

  private fun nativeImport(
    call: ForwardNativeCall,
    libraryName: String,
    receiver: List<CirParameter>,
    plan: ForwardPropertyPlan,
  ): CirDllImport {
    val values: List<CirParameter> = call.parameters
      .filter { parameter -> parameter.name != "handle" && parameter.name != "receiver" && parameter.name != "errorOut" }
      .map { parameter ->
        val type: String = if (parameter.name == "value") setterNativeType(plan.type)
        else parameter.wireType.csharpWireType()
        CirParameter(parameter.name, type)
      }
    val nativeName: String = call.exportName
      .split('_')
      .joinToString("") { part -> part.replaceFirstChar { it.uppercase() } }
    return CirDllImport(
      libraryName = libraryName,
      entryPoint = call.exportName,
      returnType = call.result.csharpWireType(),
      name = "Native_$nativeName",
      parameters = receiver + values,
      visibility = CirVisibility.PRIVATE,
      hasSyncErrorOut = true,
      marshalBooleanReturn = call.result == ForwardAbiWireType.BOOLEAN,
    )
  }

  private fun getterBody(plan: ForwardPropertyPlan, receiver: String): String {
    val args: String = listOf(receiver).filter { it.isNotBlank() }.joinToString(", ")
    return when (val getter = plan.getter) {
      is ForwardPropertyGetter.Direct -> checkedGetter(
        nativeName(plan, getter.call), args,
        plan.type,
      )

      is ForwardPropertyGetter.LegacyTwoCall -> legacyGetter(
        nativeName(plan, getter.presence), nativeName(plan, getter.value), args, plan.type,
      )
    }
  }

  private fun setterBody(plan: ForwardPropertyPlan, receiver: String): String {
    val prefix: String = listOf(receiver).filter { it.isNotBlank() }.joinToString(", ")
    fun args(extra: String = ""): String = listOf(prefix, extra).filter { it.isNotBlank() }.joinToString(", ")
    return when (val setter = plan.setter) {
      is ForwardPropertySetter.Direct -> checkedVoidBody(nativeName(plan, setter.call), args(plan.valueArgument()))
      is ForwardPropertySetter.NullableDispatch -> buildString {
        appendLine(); appendLine("            if (value.HasValue)"); appendLine("            {")
        append(
          checkedVoidBody(
            nativeName(plan, setter.value),
            args(plan.valueArgument("value.Value")),
            indent = "                "
          )
        )
        appendLine(); appendLine("            }"); appendLine("            else"); appendLine("            {")
        append(checkedVoidBody(nativeName(plan, setter.nullValue), args(), indent = "                "))
        appendLine(); append("            }")
      }

      null -> error("Forward property ${plan.symbol} has no setter")
    }
  }

  private fun checkedGetter(native: String, args: String, type: BridgeType): String = buildString {
    val callArgs: String = listOf(args, "out IntPtr error").filter { it.isNotBlank() }.joinToString(", ")
    when (val value = type.unwrapNullable()) {
      BridgeType.String -> appendLine("            IntPtr nativeResult = $native($callArgs);")
      // ADR-077 sub-item 2: a String-underlying value class rides the same pointer wire as a
      // plain String getter.
      is BridgeType.ObjectHandle, is BridgeType.Interface, is BridgeType.Collection, is BridgeType.ValueClass ->
        appendLine("            IntPtr nativeResult = $native($callArgs);")

      else -> appendLine("            ${type.wireType().csharpWireType()} nativeResult = $native($callArgs);")
    }
    appendErrorCheck(this)
    when (val value = type) {
      BridgeType.String -> append("            return Marshal.PtrToStringUTF8(nativeResult)!;")
      // ADR-077 sub-item 2: reconstruct the record struct from the raw underlying string. Without
      // this branch the `else` below returns the raw IntPtr (CS0029 in generated code).
      is BridgeType.ValueClass -> append(
        "            return new ${value.csharpType()}(Marshal.PtrToStringUTF8(nativeResult)!);",
      )
      is BridgeType.Nullable -> when (val inner = value.type) {
        BridgeType.String -> append("            return Marshal.PtrToStringUTF8(nativeResult);")
        is BridgeType.ObjectHandle -> append("            return nativeResult == IntPtr.Zero ? null : new ${inner.csharpType()}(nativeResult);")
        // ADR-040: construct via the backing wrapper class, not the interface spelling.
        is BridgeType.Interface -> append(
          "            return nativeResult == IntPtr.Zero ? null : new ${inner.backingType}(nativeResult);",
        )

        // ADR-075: a null handle means Kotlin `null`, guarded before the ordinary
        // `collectionMaterialize` -- reads impose no element-type restriction, unlike a setter.
        is BridgeType.Collection -> append(
          "            if (nativeResult == IntPtr.Zero) return null;\n" +
              collectionMaterialize(inner),
        )

        else -> append("            return nativeResult;")
      }

      is BridgeType.Enum -> append("            return (${value.csharpType()})nativeResult;")
      // ADR-076: nativeResult is a raw `long` of ticks (see the first `when` above); lift it into
      // a DateTimeOffset rather than casting (a `long -> DateTimeOffset` C# cast is illegal).
      BridgeType.Instant -> append(
        "            return new global::System.DateTimeOffset(nativeResult, global::System.TimeSpan.Zero);",
      )

      is BridgeType.ObjectHandle -> append("            return new ${value.csharpType()}(nativeResult);")
      is BridgeType.Interface ->
        append("            return new ${value.backingType}(nativeResult);")

      is BridgeType.Collection -> append(collectionMaterialize(value))
      else -> append("            return nativeResult;")
    }
  }

  private fun legacyGetter(presence: String, value: String, args: String, type: BridgeType): String {
    val inner: BridgeType = (type as BridgeType.Nullable).type
    require(inner is BridgeType.Primitive || inner == BridgeType.Instant) {
      "Forward property legacy getter requires a nullable primitive or Instant, got $type"
    }
    val presenceArgs: String = listOf(args, "out IntPtr error").filter { it.isNotBlank() }.joinToString(", ")
    val valueArgs: String = listOf(args, "out IntPtr error2").filter { it.isNotBlank() }.joinToString(", ")
    // ADR-076: the "value" wire read is always the raw representation (`long` ticks for Instant);
    // the return expression lifts it into the semantic type.
    val returnExpression: String = if (inner == BridgeType.Instant) {
      "new global::System.DateTimeOffset(value, global::System.TimeSpan.Zero)"
    } else {
      "value"
    }
    return buildString {
      appendLine(); appendLine("            bool hasValue = $presence($presenceArgs);"); appendErrorCheck(this)
      appendLine("            if (!hasValue) return null;")
      appendLine("            ${inner.wireType().csharpWireType()} value = $value($valueArgs);")
      appendLine("            if (error2 != IntPtr.Zero)"); appendLine("            {")
      appendLine("                throw NugetErrorNative.BuildException(error2);"); appendLine("            }")
      append("            return $returnExpression;")
    }
  }

  private fun checkedVoidBody(native: String, args: String, indent: String = "            "): String = buildString {
    val callArgs: String = listOf(args, "out IntPtr error").filter { it.isNotBlank() }.joinToString(", ")
    appendLine("$indent$native($callArgs);"); appendLine("${indent}if (error != IntPtr.Zero)")
    appendLine("$indent{"); appendLine("${indent}    throw NugetErrorNative.BuildException(error);"); append("$indent}")
  }

  private fun appendErrorCheck(builder: StringBuilder) {
    builder.appendLine("            if (error != IntPtr.Zero)")
    builder.appendLine("            {")
    builder.appendLine("                throw NugetErrorNative.BuildException(error);")
    builder.appendLine("            }")
  }

  private fun collectionMaterialize(type: BridgeType.Collection): String = when (type.kind) {
    CollectionKind.LIST, CollectionKind.MUTABLE_LIST -> {
      val element: String = requireNotNull(type.element).csharpType()
      val readOnly: Boolean = type.kind == CollectionKind.LIST
      buildString {
        appendLine("            int count = NugetListNative.Count(nativeResult);")
        appendLine("            var result = new List<$element>(count);")
        appendLine("            for (int i = 0; i < count; i++)")
        appendLine("            {")
        appendLine("                result.Add(NugetMarshal.FromHandle<$element>(NugetListNative.Get(nativeResult, i)));")
        appendLine("            }")
        appendLine("            NugetListNative.Dispose(nativeResult);")
        append("            return " + if (readOnly) "result.AsReadOnly();" else "result;")
      }
    }

    CollectionKind.MAP, CollectionKind.MUTABLE_MAP -> {
      val key: String = requireNotNull(type.key).csharpType()
      val value: String = requireNotNull(type.value).csharpType()
      buildString {
        appendLine("            int count = NugetMapNative.Count(nativeResult);")
        appendLine("            var result = new Dictionary<$key, $value>(count);")
        appendLine("            for (int i = 0; i < count; i++)")
        appendLine("            {")
        appendLine("                var mapKey = NugetMarshal.FromHandle<$key>(NugetMapNative.KeyAt(nativeResult, i));")
        appendLine("                var mapValue = NugetMarshal.FromHandle<$value>(NugetMapNative.ValueAt(nativeResult, i));")
        appendLine("                result[mapKey] = mapValue;")
        appendLine("            }")
        appendLine("            NugetMapNative.Dispose(nativeResult);")
        append("            return result;")
      }
    }

    CollectionKind.SET, CollectionKind.MUTABLE_SET -> {
      val element: String = requireNotNull(type.element).csharpType()
      buildString {
        appendLine("            int count = NugetSetNative.Count(nativeResult);")
        appendLine("            var result = new HashSet<$element>(count);")
        appendLine("            for (int i = 0; i < count; i++)")
        appendLine("            {")
        appendLine("                result.Add(NugetMarshal.FromHandle<$element>(NugetSetNative.ElementAt(nativeResult, i)));")
        appendLine("            }")
        appendLine("            NugetSetNative.Dispose(nativeResult);")
        append("            return result;")
      }
    }
  }

  private fun nativeName(plan: ForwardPropertyPlan, call: ForwardNativeCall): String {
    if (plan.position == ForwardPropertyPosition.CLASS) {
      return when {
        call.exportName.contains("_get_${plan.kotlinName}_value") -> "Native_Get_${plan.kotlinName}_value"
        call.exportName.contains("_get_${plan.kotlinName}") -> "Native_Get_${plan.kotlinName}"
        call.exportName.contains("_set_${plan.kotlinName}_null") -> "Native_Set_${plan.kotlinName}_null"
        else -> "Native_Set_${plan.kotlinName}"
      }
    }
    return "Native_" + call.exportName.split('_').joinToString("") {
      it.replaceFirstChar { char -> char.uppercase() }
    }
  }

  private fun setterNativeType(type: BridgeType): String = when (val value = type.unwrapNullable()) {
    BridgeType.String -> if (type is BridgeType.Nullable) "string?" else "string"
    is BridgeType.Enum -> "int"
    is BridgeType.ObjectHandle, is BridgeType.Interface -> "IntPtr"
    // ADR-077 sub-item 2: the underlying string crosses the setter wire (non-null only in this
    // slice; the nullable spelling arrives with sub-item 3).
    is BridgeType.ValueClass -> "string"
    else -> value.wireType().csharpWireType()
  }

  private fun ForwardPropertyPlan.valueArgument(name: String = "value"): String =
    when (val value = type.unwrapNullable()) {
      is BridgeType.Enum -> "(int)$name"
      // ADR-076: UtcTicks is load-bearing (verified) -- a consumer holding a non-UTC
      // DateTimeOffset must not send its wall-clock ticks.
      BridgeType.Instant -> "$name.UtcTicks"
      is BridgeType.ObjectHandle -> if (type is BridgeType.Nullable) "$name?._handle ?? IntPtr.Zero" else "$name._handle"
      // ADR-040 sub-decision B: the setter's static parameter type is `IFoo`, so extraction goes
      // through the shared reflective helper rather than a direct `._handle` field read.
      is BridgeType.Interface -> if (type is BridgeType.Nullable) {
        "$name != null ? NugetMarshal.HandleOf($name) : IntPtr.Zero"
      } else {
        "NugetMarshal.HandleOf($name)"
      }

      // ADR-075 Decision 3: a nullable collection is a call-site conditional, not a
      // nullable-returning `CreateList`/`CreateMap`/`CreateSet` -- character-for-character the
      // same shape as the nullable-`Interface` arm above.
      is BridgeType.Collection -> {
        val factory: String = when (value.kind) {
          CollectionKind.LIST, CollectionKind.MUTABLE_LIST -> "CreateList"
          CollectionKind.MAP, CollectionKind.MUTABLE_MAP -> "CreateMap"
          CollectionKind.SET, CollectionKind.MUTABLE_SET -> "CreateSet"
        }
        if (type is BridgeType.Nullable) {
          "$name != null ? NugetMarshal.$factory($name) : IntPtr.Zero"
        } else {
          "NugetMarshal.$factory($name)"
        }
      }

      // ADR-077 sub-item 2: unwrap the record struct to its capitalized underlying property.
      // The old `else -> name` fell through here and passed the struct where the native import
      // expects `string` (CS1503 in generated code).
      is BridgeType.ValueClass ->
        "$name.${value.underlyingPropertyName.replaceFirstChar { it.uppercase() }}"

      else -> name
    }

  private fun BridgeType.unwrapNullable(): BridgeType = if (this is BridgeType.Nullable) type else this
  private fun BridgeType.wireType(): ForwardAbiWireType = when (val type = unwrapNullable()) {
    BridgeType.Unit -> ForwardAbiWireType.VOID
    BridgeType.Char -> ForwardAbiWireType.CHAR16
    BridgeType.String, is BridgeType.ObjectHandle, is BridgeType.Interface, is BridgeType.Collection ->
      ForwardAbiWireType.POINTER

    is BridgeType.Enum -> ForwardAbiWireType.INT32
    // ADR-076: wires as its own INT64 tick representation, same as a Primitive(LONG).
    BridgeType.Instant -> ForwardAbiWireType.INT64
    is BridgeType.Primitive -> type.kind.wireType()
    // ADR-077 sub-item 2: the underlying's wire, matching ForwardPropertyPlanner.wireType().
    is BridgeType.ValueClass -> type.underlying.wireType()
    else -> error("No property wire type for $type")
  }

  private fun PrimitiveKind.wireType(): ForwardAbiWireType = when (this) {
    PrimitiveKind.BOOLEAN -> ForwardAbiWireType.BOOLEAN; PrimitiveKind.BYTE -> ForwardAbiWireType.INT8; PrimitiveKind.UBYTE -> ForwardAbiWireType.UINT8
    PrimitiveKind.SHORT -> ForwardAbiWireType.INT16; PrimitiveKind.USHORT -> ForwardAbiWireType.UINT16; PrimitiveKind.INT -> ForwardAbiWireType.INT32
    PrimitiveKind.UINT -> ForwardAbiWireType.UINT32; PrimitiveKind.LONG -> ForwardAbiWireType.INT64; PrimitiveKind.ULONG -> ForwardAbiWireType.UINT64
    PrimitiveKind.FLOAT -> ForwardAbiWireType.FLOAT32; PrimitiveKind.DOUBLE -> ForwardAbiWireType.FLOAT64
  }

  private fun BridgeType.csharpType(): String = when (this) {
    is BridgeType.Nullable -> "${type.csharpType()}?"
    is BridgeType.Primitive -> kind.csharpType()
    BridgeType.Char -> "char"
    BridgeType.String -> "string"
    // ADR-076: the public C# type is always System.DateTimeOffset, fully qualified so no "using
    // System;" is required in the generated file.
    BridgeType.Instant -> "global::System.DateTimeOffset"
    is BridgeType.Enum -> this.csharpType
    // ADR-066: mirrors `BridgeType.Enum.csharpType` — the classifier already qualified this.
    is BridgeType.ObjectHandle -> csharpType
    // ADR-040: the public C# spelling is the projected interface, never the backing class.
    is BridgeType.Interface -> csharpType
    // The public C# spelling is the value class itself (e.g. `ChartId`), never its underlying
    // wire value: true for an extension property's receiver (ADR-075) and for an ordinary
    // value-class-typed property (ADR-077 sub-item 2).
    is BridgeType.ValueClass -> csharpType
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

    else -> error("No C# property type for $this")
  }

  private fun PrimitiveKind.csharpType(): String = when (this) {
    PrimitiveKind.BOOLEAN -> "bool"; PrimitiveKind.BYTE -> "sbyte"; PrimitiveKind.UBYTE -> "byte"; PrimitiveKind.SHORT -> "short"; PrimitiveKind.USHORT -> "ushort"
    PrimitiveKind.INT -> "int"; PrimitiveKind.UINT -> "uint"; PrimitiveKind.LONG -> "long"; PrimitiveKind.ULONG -> "ulong"; PrimitiveKind.FLOAT -> "float"; PrimitiveKind.DOUBLE -> "double"
  }

  private fun ForwardAbiWireType.csharpWireType(): String = when (this) {
    ForwardAbiWireType.VOID -> "void"; ForwardAbiWireType.BOOLEAN -> "bool"; ForwardAbiWireType.INT8 -> "sbyte"; ForwardAbiWireType.UINT8 -> "byte"
    ForwardAbiWireType.INT16 -> "short"; ForwardAbiWireType.UINT16 -> "ushort"; ForwardAbiWireType.CHAR16 -> "char"
    ForwardAbiWireType.INT32 -> "int"; ForwardAbiWireType.UINT32 -> "uint"
    ForwardAbiWireType.INT64 -> "long"; ForwardAbiWireType.UINT64 -> "ulong"; ForwardAbiWireType.FLOAT32 -> "float"; ForwardAbiWireType.FLOAT64 -> "double"
    ForwardAbiWireType.STRING -> "string"; ForwardAbiWireType.POINTER -> "IntPtr"
    ForwardAbiWireType.UNKNOWN -> error("Unknown property wire type")
  }
}
