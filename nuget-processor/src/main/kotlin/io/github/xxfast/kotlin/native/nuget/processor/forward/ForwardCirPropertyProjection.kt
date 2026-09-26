package io.github.xxfast.kotlin.native.nuget.processor.forward

import io.github.xxfast.kotlin.native.nuget.processor.cir.CirDllImport
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirExtraNative
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirMember
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirMethod
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirParameter
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirProperty
import io.github.xxfast.kotlin.native.nuget.processor.cir.CirVisibility
import io.github.xxfast.kotlin.native.nuget.processor.cir.indentNestedBody

/** C# projection for the planned property path. */
internal object ForwardCirPropertyProjection {
  fun classProperty(
    plan: ForwardPropertyPlan,
    isOverride: Boolean = false,
    isVirtual: Boolean = false,
    isAbstract: Boolean = false,
  ): CirProperty {
    require(plan.position == ForwardPropertyPosition.CLASS) { "Expected class property plan" }
    return property(
      plan,
      receiver = "_handle",
      isStatic = false,
      isOverride = isOverride,
      isVirtual = isVirtual,
      isAbstract = isAbstract,
    )
  }

  /**
   * ADR-113: the public C# spelling this projection gives [plan], exposed so the generated `IFoo`
   * declaration can be projected from the *same* call the implementing class's property goes
   * through. A second spelling of the same plan is what CS0738 is made of.
   */
  fun publicType(plan: ForwardPropertyPlan): String = plan.type.csharpType()

  fun staticProperty(plan: ForwardPropertyPlan, libraryName: String): List<CirMember> {
    // ROADMAP Phase 4: an `object`'s own property is the third static position, projected by this
    // same function -- the wire has no receiver slot, so the C# member is a static property whose
    // accessors call the extern with nothing but the error slot.
    require(
      plan.position == ForwardPropertyPosition.TOP_LEVEL ||
          plan.position == ForwardPropertyPosition.COMPANION ||
          plan.position == ForwardPropertyPosition.OBJECT
    ) {
      "Expected static property plan"
    }
    val imports: List<CirDllImport> = plan.calls().map { call -> nativeImport(call, libraryName, emptyList(), plan) }
    return imports + property(plan, receiver = "", isStatic = true)
  }

  fun extension(plan: ForwardPropertyPlan, libraryName: String): List<CirMember> {
    require(plan.position == ForwardPropertyPosition.EXTENSION) { "Expected extension property plan" }
    val receiver = plan.receiver as ForwardPropertyReceiver.Value
    val publicReceiver: String = receiver.type.csharpType()
    // ADR-132 (2026-09-20): spelled through the SHARED nullable-string-wire rule, not off the bare
    // wire type. A `String?` / `Uuid?` / `ValueClass(String)?` receiver hands the import a nullable
    // expression, and `STRING -> "string"` made that a CS8604 under the generated file's
    // `<Nullable>enable</Nullable>` + `<TreatWarningsAsErrors>`. The callable route already had the
    // rule; `isNullableStringWire` is now the one copy both read.
    val nativeReceiver: String = plan.calls().first().parameters
      .first { parameter -> parameter.role == ForwardAbiRole.RECEIVER }
      .let { parameter ->
        if (parameter.transfer.type.isNullableStringWire()) "string?"
        else parameter.wireType.csharpWireType()
      }
    // ADR-075: an extension receiver that is a value class passes its underlying value to the
    // native call, exactly like the value class's own generated members
    // (`renderValueClassMembers`'s `underlyingName` -- the Kotlin `value` property capitalized).
    // The underlying itself then unwraps to its own wire, mirroring the parameter lowering in
    // `ForwardCirPlanProjection.callArguments`: an enum casts to its `int` ordinal, an object
    // handle hands over its `_handle`.
    // ADR-132 at the property position: the receiver is an input on the same wire the setter's
    // *value* rides, so it goes through the same three functions. An interface receiver mints an
    // ADR-084 transfer handle here and has to dispose it afterwards, which is why both accessors
    // get a handle scope rather than just an argument string.
    val receiverArgument: String = receiver.type.inputArgument("receiver")
    val receiverStep: ForwardCirHandleStep? = receiver.type.handleStep("receiver")
    val receiverCleanup: String? = receiver.type.handleCleanup("receiver")
    val imports: List<CirMember> = plan.calls().map { call ->
      nativeImport(call, libraryName, listOf(CirParameter("receiver", nativeReceiver)), plan)
    }
    val getter = CirMethod(
      doc = plan.doc?.toCirDoc(),
      name = "Get${plan.publicName}",
      returnType = plan.type.csharpType(),
      nativeReturnType = plan.getter.calls().first().result.csharpWireType(),
      parameters = listOf(CirParameter("receiver", publicReceiver)),
      body = getterBody(plan, receiverArgument, receiverStep, receiverCleanup),
      isStatic = true,
      isExtension = true,
      hasCustomBody = true,
    )
    val setter: CirMethod? = plan.setter?.let {
      CirMethod(
        name = "Set${plan.publicName}",
        returnType = "void",
        parameters = listOf(CirParameter("receiver", publicReceiver), CirParameter("value", plan.type.csharpType())),
        body = setterBody(plan, receiverArgument, receiverStep, receiverCleanup),
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
    isAbstract: Boolean = false,
  ): CirProperty {
    val directGetter: ForwardNativeCall = plan.getter.calls().first()
    return CirProperty(
      name = plan.publicName,
      type = plan.type.csharpType(),
      nativeReturnType = directGetter.result.csharpWireType(),
      nativeSetterType = if (plan.setter != null) setterNativeType(plan.type) else directGetter.result.csharpWireType(),
      nativeName = plan.kotlinName,
      // ROADMAP:29: the bodies are baked at the method position's depth (the brace at column 8,
      // statements at 12), which is what the extension `CirMethod` pair renders at. A property
      // accessor's brace sits one level deeper (column 12, and 16 inside a sealed arm, which
      // `CirSealedRenderer` reaches by composing this same shift again), so the shared body is
      // moved in one level here rather than threaded as an indent through every helper below.
      getter = getterBody(plan, receiver).indentNestedBody(),
      setter = plan.setter?.let { setterBody(plan, receiver).indentNestedBody() },
      extraNatives = classExtraNatives(plan),
      isStatic = isStatic,
      isOverride = isOverride,
      isVirtual = isVirtual,
      isAbstract = isAbstract,
      hasSyncErrorOut = true,
      // ADR-150: ADR-075's getter/setter pair is one C# property, so it carries one doc block.
      doc = plan.doc?.toCirDoc(),
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
      .filter { parameter ->
        parameter.role == ForwardAbiRole.USER || parameter.role == ForwardAbiRole.SETTER_VALUE
      }
      .map { parameter ->
        val type: String =
          if (parameter.role == ForwardAbiRole.SETTER_VALUE) setterNativeType(plan.type)
          else parameter.wireType.csharpWireType()
        CirParameter(parameter.name, type)
      }
    val nativeName: String = call.csharpStem
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

  // ADR-132 / ADR-120: the getter used to be flat, which was correct only while no receiver ever
  // minted a handle. An interface receiver mints one per read, so the core goes inside a scope
  // whose `finally` disposes it even when the error check throws. With no receiver handle
  // [forwardCirHandleScope] keeps the flat shape byte for byte.
  private fun getterBody(
    plan: ForwardPropertyPlan,
    receiver: String,
    receiverStep: ForwardCirHandleStep? = null,
    receiverCleanup: String? = null,
  ): String {
    val args: String = listOf(receiver).filter { it.isNotBlank() }.joinToString(", ")
    val core: String = when (val getter = plan.getter) {
      is ForwardPropertyGetter.Direct -> checkedGetter(
        nativeName(plan, getter.call), args,
        plan.type,
      )

      is ForwardPropertyGetter.LegacyTwoCall -> legacyGetter(
        nativeName(plan, getter.presence), nativeName(plan, getter.value), args, plan.type,
      )
    }
    return forwardCirHandleScope(
      prelude = listOfNotNull(receiverStep),
      cleanup = listOfNotNull(receiverCleanup),
      core = core,
    )
  }

  private fun setterBody(
    plan: ForwardPropertyPlan,
    receiver: String,
    receiverStep: ForwardCirHandleStep? = null,
    receiverCleanup: String? = null,
  ): String {
    val prefix: String = listOf(receiver).filter { it.isNotBlank() }.joinToString(", ")
    fun args(extra: String = ""): String = listOf(prefix, extra).filter { it.isNotBlank() }.joinToString(", ")
    return when (val setter = plan.setter) {
      // ROADMAP:130: the setter's temporary handle is released in a `finally`, so the error
      // check's throw cannot skip it. With no handle to release the body keeps its flat shape.
      // The receiver's own step comes first: it is an argument to the same native call, and its
      // dispose belongs in the same `finally` as the value's.
      is ForwardPropertySetter.Direct -> forwardCirHandleScope(
        prelude = listOfNotNull(receiverStep, plan.type.handleStep("value")),
        cleanup = listOfNotNull(receiverCleanup, plan.type.handleCleanup("value")),
        core = checkedVoidBody(
          nativeName(plan, setter.call),
          args(plan.type.inputArgument("value")),
        ),
      )

      // ADR-132 (2026-09-20): the fan-out arm goes through the SAME handle scope as `Direct` above.
      // It used to build its body directly, so an `Interface`/`Nullable(Interface)` receiver --
      // whose `inputArgument` is the local `receiverHandle` -- named a local this arm never
      // declared, minted or disposed: CS0103 in the generated C#, and a leaked ADR-084 transfer
      // handle per set if it had compiled. The value itself never mints a handle here (this arm
      // exists only for the has-value fan-out shapes: primitive, enum, Instant, Duration and a
      // Primitive/Enum-underlying value class), so the receiver's step is the only prelude.
      is ForwardPropertySetter.NullableDispatch -> forwardCirHandleScope(
        prelude = listOfNotNull(receiverStep),
        cleanup = listOfNotNull(receiverCleanup),
        core = buildString {
          appendLine("            if (value.HasValue)"); appendLine("            {")
          append(
            checkedVoidBody(
              nativeName(plan, setter.value),
              args(plan.type.inputArgument("value.Value", nonNull = true)),
              indent = "                "
            )
          )
          appendLine()
          appendLine("            }"); appendLine("            else"); appendLine("            {")
          append(
            checkedVoidBody(
              nativeName(plan, setter.nullValue),
              args(),
              indent = "                "
            )
          )
          appendLine(); append("            }")
        },
      )

      null -> error("Forward property ${plan.symbol} has no setter")
    }
  }

  private fun checkedGetter(native: String, args: String, type: BridgeType): String = buildString {
    val callArgs: String = listOf(args, "out IntPtr error").filter { it.isNotBlank() }.joinToString(", ")
    when (val value = type.unwrapNullable()) {
      BridgeType.String -> appendLine("            IntPtr nativeResult = $native($callArgs);")
      // ADR-147: a `T` getter reads the same boxed-handle wire.
      // ADR-151: and so does a ByteArray getter.
      is BridgeType.ObjectHandle, is BridgeType.Interface, is BridgeType.Collection,
      BridgeType.ByteArray, is BridgeType.TypeParameter ->
        appendLine("            IntPtr nativeResult = $native($callArgs);")

      // ADR-107: the error envelope's pointer. ONE call, deliberately: the export mints a
      // StableRef per invocation, so a second call would build a second envelope and leak it.
      BridgeType.Throwable -> appendLine("            IntPtr nativeResult = $native($callArgs);")

      // ADR-106: the hex-dash text pointer, exactly the String getter's wire.
      BridgeType.Uuid -> appendLine("            IntPtr nativeResult = $native($callArgs);")

      // ADR-077 sub-items 2/4: a value class rides its underlying's wire (IntPtr for
      // String/ObjectHandle, int ordinal for enum, the primitive's own wire otherwise), which is
      // exactly what `wireType()`'s ValueClass branch resolves to in the fallthrough below.
      else -> appendLine("            ${type.wireType().csharpWireType()} nativeResult = $native($callArgs);")
    }
    appendErrorCheck(this)
    when (val value = type) {
      BridgeType.String -> append("            return Marshal.PtrToStringUTF8(nativeResult)!;")
      // ADR-106: decode the text, then parse it into a Guid.
      BridgeType.Uuid -> append(
        "            return global::System.Guid.Parse(Marshal.PtrToStringUTF8(nativeResult)!);",
      )
      // ADR-077 sub-items 2/4: reconstruct the record struct, composing the underlying's own step.
      // Without this branch the `else` below returns the raw wire value (CS0029 in generated code).
      is BridgeType.ValueClass ->
        append("            return ${valueClassGetterReconstruction(value)};")

      is BridgeType.Nullable -> when (val inner = value.type) {
        BridgeType.String -> append("            return Marshal.PtrToStringUTF8(nativeResult);")
        // ADR-106: a null pointer is the null; anything else is canonical text.
        BridgeType.Uuid -> append(
          "            return nativeResult == IntPtr.Zero ? null : " +
              "global::System.Guid.Parse(Marshal.PtrToStringUTF8(nativeResult)!);",
        )
        // ADR-107: a null Throwable property ships the null pointer, as every pointer-wired
        // nullable getter does; a non-null one rebuilds the exception from the envelope.
        BridgeType.Throwable -> append(
          "            return nativeResult == IntPtr.Zero ? null : " +
              "NugetErrorNative.BuildException(nativeResult);",
        )

        is BridgeType.ObjectHandle -> append(
          "            return nativeResult == IntPtr.Zero ? null : " +
              "${inner.handleReconstruction()};",
        )
        // ADR-083/147: `FromHandle<T>` answers `default!` for the null pointer itself.
        is BridgeType.TypeParameter ->
          append("            return NugetMarshal.FromHandle<${inner.name}>(nativeResult);")
        // ADR-040: construct via the backing wrapper class, not the interface spelling.
        is BridgeType.Interface -> append(
          "            return nativeResult == IntPtr.Zero ? null : " +
              "${interfaceReturnExpression(inner.csharpType(), inner.backingType)};",
        )

        // ADR-075: a null handle means Kotlin `null`, guarded before the ordinary
        // `collectionMaterialize` -- reads impose no element-type restriction, unlike a setter.
        is BridgeType.Collection -> append(
          "            if (nativeResult == IntPtr.Zero) return null;\n" +
              collectionMaterialize(inner),
        )

        // ADR-151: the same null-handle guard, then the count-plus-memcpy read. `ReadBytes`
        // disposes the handle in its own `finally`, so a null must never reach it.
        BridgeType.ByteArray -> append(
          "            if (nativeResult == IntPtr.Zero) return null;\n" +
              "            return NugetMarshal.ReadBytes(nativeResult);",
        )

        // ADR-077 sub-items 3/4: the ADR-075 null-handle guard, then the per-underlying
        // reconstruction (String and ObjectHandle underlyings both ride the IntPtr wire).
        is BridgeType.ValueClass -> append(
          "            return nativeResult == IntPtr.Zero ? null : " +
              "${valueClassGetterReconstruction(inner)};",
        )

        else -> append("            return nativeResult;")
      }

      is BridgeType.Enum -> append("            return (${value.csharpType()})nativeResult;")
      // ADR-076: nativeResult is a raw `long` of ticks (see the first `when` above); lift it into
      // a DateTimeOffset rather than casting (a `long -> DateTimeOffset` C# cast is illegal).
      BridgeType.Instant -> append("            return ${instantLiftCs("nativeResult")};")
      // ADR-103: the same lift into a TimeSpan.
      BridgeType.Duration -> append("            return ${durationLiftCs("nativeResult")};")

      // ADR-107: BuildException reads the envelope through the nuget_error_* exports, disposes it,
      // and RETURNS the exception rather than throwing it -- the same object a catch would see.
      BridgeType.Throwable ->
        append("            return NugetErrorNative.BuildException(nativeResult);")

      is BridgeType.ObjectHandle -> append("            return ${value.handleReconstruction()};")
      // ADR-147: read the box back and dispose it, whatever `T` was instantiated to.
      is BridgeType.TypeParameter ->
        append("            return NugetMarshal.FromHandle<${value.name}>(nativeResult);")
      is BridgeType.Interface ->
        append("            return ${interfaceReturnExpression(value.csharpType(), value.backingType)};")

      is BridgeType.Collection -> append(collectionMaterialize(value))
      // ADR-151: materialize and dispose the handle the getter minted.
      BridgeType.ByteArray -> append("            return NugetMarshal.ReadBytes(nativeResult);")
      else -> append("            return nativeResult;")
    }
  }

  /**
   * ADR-105 (issue #54): the C# expression that turns a handle back into its declared type. An
   * ordinary handle-backed class takes its `internal T(IntPtr, out NugetHandleTag)` constructor;
   * an ADR-009 sealed *base* is `abstract`, so `new` is CS0144 and the reconstruction goes
   * through the generated `internal static T FromHandle(IntPtr)` discriminator instead — the
   * same spelling a top-level sealed *return* already renders, so a consumer sees one idiom
   * either way.
   */
  private fun BridgeType.ObjectHandle.handleReconstruction(
    wireValue: String = "nativeResult",
  ): String = if (viaDiscriminator) {
    "${csharpType()}.FromHandle($wireValue)"
  } else {
    "new ${csharpType()}($wireValue, out _)"
  }

  /**
   * ADR-077 sub-item 4: rebuild the record struct from `nativeResult`, composing the underlying's
   * own step (UTF-8, enum cast, handle wrapper) inside the constructor call.
   *
   * ADR-105: the handle step is [handleReconstruction], not a bare `new`, so a value class over a
   * sealed base takes the `FromHandle` discriminator (the base is `abstract`, `new` is CS0144).
   */
  private fun valueClassGetterReconstruction(
    type: BridgeType.ValueClass,
    wireValue: String = "nativeResult",
  ): String {
    val inner: String = when (val underlying: BridgeType = type.underlying) {
      BridgeType.String -> "Marshal.PtrToStringUTF8($wireValue)!"
      is BridgeType.Enum -> "(${underlying.csharpType})$wireValue"
      is BridgeType.ObjectHandle -> underlying.handleReconstruction(wireValue)
      is BridgeType.Primitive -> wireValue
      else -> error(
        "Forward CIR property projection has no value-class reconstruction for $underlying",
      )
    }
    return "new ${type.csharpType()}($inner)"
  }

  private fun legacyGetter(presence: String, value: String, args: String, type: BridgeType): String {
    val inner: BridgeType = (type as BridgeType.Nullable).type
    // ADR-079: a Primitive/Enum-underlying value class rides the same two-call getter; the
    // `_value` read's local type comes from the underlying's wire (`wireType()` delegates).
    // ADR-098 amendment (boundary nullability part C): and so does `Char?`, its `_value` read a
    // by-value `char` (CHAR16 through `csharpWireType()`, U2-decorated by native-type text).
    require(
      inner is BridgeType.Primitive || inner == BridgeType.Char ||
          inner == BridgeType.Instant ||
          inner == BridgeType.Duration ||
          inner is BridgeType.ValueClass || inner is BridgeType.Enum
    ) {
      "Forward property legacy getter requires a nullable primitive, Char, Instant, Duration, " +
          "enum or value class, got $type"
    }
    val presenceArgs: String = listOf(args, "out IntPtr error").filter { it.isNotBlank() }.joinToString(", ")
    val valueArgs: String = listOf(args, "out IntPtr error2").filter { it.isNotBlank() }.joinToString(", ")
    // ADR-076: the "value" wire read is always the raw representation (`long` ticks for Instant);
    // the return expression lifts it into the semantic type.
    val returnExpression: String = when {
      inner == BridgeType.Instant -> instantLiftCs("value")
      // ADR-103: the same lift into a TimeSpan.
      inner == BridgeType.Duration -> durationLiftCs("value")

      // ADR-079: rebuild the record struct from the underlying wire value the `_value` call read.
      inner is BridgeType.ValueClass -> valueClassGetterReconstruction(inner, "value")
      // ADR-080: lift the `int` ordinal back into the enum.
      inner is BridgeType.Enum -> "(${inner.csharpType()})value"
      else -> "value"
    }
    // ROADMAP:29: no leading `appendLine()` here. [forwardCirHandleScope] owns the newline that
    // keeps the opening brace alone on its line; emitting a second one put a blank line after the
    // brace of every nullable-primitive getter.
    return buildString {
      appendLine("            bool hasValue = $presence($presenceArgs);"); appendErrorCheck(this)
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

  /** ADR-120: same routing as the callable half. The read runs through ADR-099's
   *  `finally`-guarded helpers, so the result handle goes even when an element read throws. */
  private fun collectionMaterialize(type: BridgeType.Collection): String {
    val read: String =
      componentCollectionRead("nativeResult", type, csharpType = { it.csharpType() })
    return "            return $read;"
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
    return "Native_" + call.csharpStem.split('_').joinToString("") {
      it.replaceFirstChar { char -> char.uppercase() }
    }
  }

  private fun setterNativeType(type: BridgeType): String = when (val value = type.unwrapNullable()) {
    // ADR-106: a Uuid setter crosses as text, so the DllImport parameter is `string` -- NOT the
    // getter's IntPtr wire, and `string?` when nullable for the same CS8604 reason as String.
    BridgeType.String, BridgeType.Uuid -> if (type is BridgeType.Nullable) "string?" else "string"
    is BridgeType.Enum -> "int"
    is BridgeType.ObjectHandle, is BridgeType.Interface -> "IntPtr"
    // ADR-077: the underlying's wire crosses the setter (sub-item 4 keys it per kind); the outer
    // nullability decides the String spelling, exactly like the plain-String arm above.
    is BridgeType.ValueClass -> when (value.underlying) {
      BridgeType.String -> if (type is BridgeType.Nullable) "string?" else "string"
      is BridgeType.Enum -> "int"
      is BridgeType.ObjectHandle -> "IntPtr"
      else -> value.underlying.wireType().csharpWireType()
    }

    else -> value.wireType().csharpWireType()
  }

  /**
   * ADR-084 stage 3: the two pointer-shaped setter values need a statement *after* the native call,
   * which a single argument expression cannot carry. An interface value mints a bridge transfer
   * handle whose disposal is what lets the bridge ever be collected; a collection value builds a
   * Kotlin list/map/set handle that was likewise never freed. One mechanism closes both.
   */
  private fun BridgeType.handleStep(name: String): ForwardCirHandleStep? {
    val nullable: Boolean = this is BridgeType.Nullable
    return when (val value = unwrapNullable()) {
      is BridgeType.Interface -> {
        val helper: String = if (nullable) "HandleOfOrZero" else "HandleOf"
        ForwardCirHandleStep(
          flat = "IntPtr ${name}Handle = NugetMarshal.$helper($name, out bool ${name}Owned);",
          declarations = listOf(
            "IntPtr ${name}Handle = IntPtr.Zero;",
            "bool ${name}Owned = false;",
          ),
          statement = "${name}Handle = NugetMarshal.$helper($name, out ${name}Owned);",
        )
      }

      // ADR-151: the setter mints the Kotlin array handle before the call, the same shape the
      // collection arm below uses, with `IntPtr.Zero` for a null value.
      BridgeType.ByteArray -> {
        val built: String = if (nullable) {
          "$name != null ? NugetMarshal.CreateBytes($name) : IntPtr.Zero"
        } else {
          "NugetMarshal.CreateBytes($name)"
        }
        ForwardCirHandleStep(
          flat = "IntPtr ${name}Handle = $built;",
          declarations = listOf("IntPtr ${name}Handle = IntPtr.Zero;"),
          statement = "${name}Handle = $built;",
        )
      }

      // ADR-088 / ADR-132 (2026-09-20): a bound C# interface receiver crosses as a FRESH transfer
      // GCHandle, byte for byte the callable route's `boundInterfacePrelude`. Deliberately no
      // cleanup below: the receiving side owns it (Kotlin's `nuget{Iface}Value` either frees it on
      // a token-probe hit or hands it to the ADR-070 wrapper's cleaner), so freeing it here too
      // would double-free, and not allocating a fresh one would let Kotlin store a handle C# then
      // released. Nothing to hoist either, so the flat spelling is the only one.
      is BridgeType.BoundInterface -> ForwardCirHandleStep(
        flat = "IntPtr ${name}Handle = GCHandle.ToIntPtr(GCHandle.Alloc($name));",
      )

      is BridgeType.Collection -> {
        val factory: String = when (value.kind) {
          CollectionKind.LIST, CollectionKind.MUTABLE_LIST -> "CreateList"
          CollectionKind.MAP, CollectionKind.MUTABLE_MAP -> "CreateMap"
          CollectionKind.SET, CollectionKind.MUTABLE_SET -> "CreateSet"
        }
        // ADR-081: the setter's elements are projected to their underlying before boxing, the same
        // per-element projection a collection *parameter* uses.
        val source: String = collectionCreateArgument(name, value) { it.csharpType() }
        // ADR-075 Decision 3: a null source ships the null pointer rather than an empty collection.
        val built: String = if (nullable) {
          "$name != null ? NugetMarshal.$factory($source) : IntPtr.Zero"
        } else {
          "NugetMarshal.$factory($source)"
        }
        ForwardCirHandleStep(
          flat = "IntPtr ${name}Handle = $built;",
          declarations = listOf("IntPtr ${name}Handle = IntPtr.Zero;"),
          statement = "${name}Handle = $built;",
        )
      }

      else -> null
    }
  }

  private fun BridgeType.handleCleanup(name: String): String? =
    when (val value = unwrapNullable()) {
      // Only a minted bridge handle is disposed: a Kotlin-backed wrapper's `_handle` belongs to
      // that wrapper, and `owned` is how HandleOf reports which of the two it returned.
      // ADR-135: the same zero guard the collection arm below carries. A throw from the mint in
      // `handleStep` reaches this `finally` with the handle still Zero, and `nuget_dispose` is
      // not null-safe.
      is BridgeType.Interface ->
        "if (${name}Owned && ${name}Handle != IntPtr.Zero) { NugetMarshal.Dispose(${name}Handle); }"
      // ADR-151: the same unconditional zero guard the collection arm carries.
      BridgeType.ByteArray ->
        "if (${name}Handle != IntPtr.Zero) { NugetBytesNative.Dispose(${name}Handle); }"

      is BridgeType.Collection -> {
        val native: String = when (value.kind) {
          CollectionKind.LIST, CollectionKind.MUTABLE_LIST -> "NugetListNative"
          CollectionKind.MAP, CollectionKind.MUTABLE_MAP -> "NugetMapNative"
          CollectionKind.SET, CollectionKind.MUTABLE_SET -> "NugetSetNative"
        }
        // ADR-075: a null value never built a handle, and `nuget_dispose` is not null-safe.
        // ROADMAP:130: unconditional now, because the `finally` this lands in is also reached by a
        // throw from the creation itself, where the handle is still Zero.
        "if (${name}Handle != IntPtr.Zero) { $native.Dispose(${name}Handle); }"
      }

      else -> null
    }

  /** [nonNull] marks a call site that has already unwrapped the `Nullable<T>` (the
   *  `NullableDispatch` setter's `value.Value`), so no `?.` propagation may be emitted: `?.` on a
   *  non-nullable struct is a C# error. */
  private fun BridgeType.inputArgument(
    name: String,
    nonNull: Boolean = false,
  ): String =
    when (val value = unwrapNullable()) {
      is BridgeType.Enum -> "(int)$name"
      // ADR-076: UtcTicks is load-bearing (verified) -- a consumer holding a non-UTC
      // DateTimeOffset must not send its wall-clock ticks.
      BridgeType.Instant -> "$name.UtcTicks"
      // ADR-103: TimeSpan has one tick domain, so the plain `.Ticks` is unambiguous.
      BridgeType.Duration -> "$name.Ticks"
      // ADR-106: the default "D" format only -- never a format string (see ADR-106 Decision 2).
      // A `Guid?` setter rides the null-pointer wire, so the safe call is the whole null handling.
      BridgeType.Uuid -> if (this is BridgeType.Nullable && !nonNull) {
        "$name?.ToString()"
      } else {
        "$name.ToString()"
      }

      is BridgeType.ObjectHandle ->
        if (this is BridgeType.Nullable) "$name?._handle ?? IntPtr.Zero" else "$name._handle"
      // ADR-040 sub-decision B: the setter's static parameter type is `IFoo`, so extraction goes
      // through the shared reflective helper rather than a direct `._handle` field read.
      // ADR-084 stage 3: extraction moved into [handleStep], because a C#-implemented value
      // mints a transfer handle that has to be disposed *after* the native call.
      is BridgeType.Interface -> "${name}Handle"

      // ADR-075 Decision 3: a nullable collection is a call-site conditional, not a
      // nullable-returning `CreateList`/`CreateMap`/`CreateSet` -- character-for-character the
      // same shape as the nullable-`Interface` arm above.
      is BridgeType.Collection -> "${name}Handle"

      // ADR-151: the handle [handleStep] minted with `NugetMarshal.CreateBytes`.
      BridgeType.ByteArray -> "${name}Handle"

      // ADR-088: the transfer GCHandle [handleStep] allocated just above.
      is BridgeType.BoundInterface -> "${name}Handle"

      // ADR-077 sub-items 2/3/4: unwrap the record struct to its capitalized underlying property
      // and lower it to the wire per underlying, with null propagation for the nullable spelling
      // (a C# null ships the null pointer). The old `else -> name` fell through here and passed
      // the struct where the native import expects the wire type (CS1503 in generated code).
      is BridgeType.ValueClass -> {
        val prop: String = value.underlyingPropertyName.replaceFirstChar { it.uppercase() }
        val nullable: Boolean = this is BridgeType.Nullable && !nonNull
        val unwrapped: String = if (nullable) "$name?.$prop" else "$name.$prop"
        when (value.underlying) {
          is BridgeType.Enum -> "(int)$unwrapped"
          is BridgeType.ObjectHandle ->
            if (nullable) "$unwrapped._handle ?? IntPtr.Zero" else "$unwrapped._handle"

          else -> unwrapped
        }
      }

      else -> name
    }

  private fun BridgeType.unwrapNullable(): BridgeType = if (this is BridgeType.Nullable) type else this
  private fun BridgeType.wireType(): ForwardAbiWireType = when (val type = unwrapNullable()) {
    BridgeType.Unit -> ForwardAbiWireType.VOID
    BridgeType.Char -> ForwardAbiWireType.CHAR16
    BridgeType.String, is BridgeType.ObjectHandle, is BridgeType.Interface,
    is BridgeType.Collection, BridgeType.ByteArray,
    is BridgeType.TypeParameter -> ForwardAbiWireType.POINTER

    is BridgeType.Enum -> ForwardAbiWireType.INT32
    // ADR-076: wires as its own INT64 tick representation, same as a Primitive(LONG).
    // ADR-103: the same, an INT64 of TimeSpan ticks.
    BridgeType.Instant, BridgeType.Duration -> ForwardAbiWireType.INT64
    // ADR-107: the error-envelope pointer, matching ForwardPropertyPlanner.wireType().
    BridgeType.Throwable -> ForwardAbiWireType.POINTER
    // ADR-088: the transfer GCHandle pointer, matching ForwardPropertyPlanner.wireType().
    is BridgeType.BoundInterface -> ForwardAbiWireType.POINTER
    // ADR-106: the getter's pointer-to-text wire (the setter's STRING slot is declared from the
    // plan's own parameter, not from here).
    BridgeType.Uuid -> ForwardAbiWireType.POINTER
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
    // ADR-103: likewise System.TimeSpan.
    BridgeType.Duration -> "global::System.TimeSpan"
    // ADR-107: the public C# type is System.Exception; the value is always an IKotlinException
    // (KotlinException or one of the ADR-029 mapped subclasses), never a bare Exception.
    BridgeType.Throwable -> "global::System.Exception"
    // ADR-106: System.Guid, a value type, so `Uuid?` renders `Guid?` (Nullable<Guid>).
    BridgeType.Uuid -> "global::System.Guid"
    is BridgeType.Enum -> this.csharpType
    // ADR-066: mirrors `BridgeType.Enum.csharpType` — the classifier already qualified this.
    is BridgeType.ObjectHandle -> csharpType
    // ADR-147: the type parameter's own name, on the generic carrier.
    is BridgeType.TypeParameter -> name
    // ADR-040: the public C# spelling is the projected interface, never the backing class.
    is BridgeType.Interface -> csharpType
    // ADR-088: the ORIGINAL bound C# interface, as the plugin's manifest spells it -- the same
    // spelling `forwardPublicCsharpType` gives it on the callable route.
    is BridgeType.BoundInterface -> csharpType
    // The public C# spelling is the value class itself (e.g. `ChartId`), never its underlying
    // wire value: true for an extension property's receiver (ADR-075) and for an ordinary
    // value-class-typed property (ADR-077 sub-item 2).
    is BridgeType.ValueClass -> csharpType
    // ADR-151: the public spelling is `byte[]`; `ByteArray?` renders `byte[]?` through the
    // nullable arm above, because a C# array is a reference type.
    BridgeType.ByteArray -> "byte[]"
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
