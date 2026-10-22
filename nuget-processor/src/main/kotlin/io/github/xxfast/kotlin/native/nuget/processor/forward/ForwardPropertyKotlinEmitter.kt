package io.github.xxfast.kotlin.native.nuget.processor.forward

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.TypeName
import io.github.xxfast.kotlin.native.nuget.processor.exports.cNameAnnotation
import io.github.xxfast.kotlin.native.nuget.processor.exports.cOpaquePointer
import io.github.xxfast.kotlin.native.nuget.processor.exports.cOpaquePointerVar
import io.github.xxfast.kotlin.native.nuget.processor.exports.nugetHandles

/** Kotlin projection for the complete planned property path. */
internal fun FileSpec.Builder.addForwardPropertyPlanExports(plan: ForwardPropertyPlan) {
  plan.validate()
  when (val getter: ForwardPropertyGetter = plan.getter) {
    is ForwardPropertyGetter.Direct -> addGetter(plan, getter.call)
    is ForwardPropertyGetter.LegacyTwoCall -> {
      addNullablePresenceGetter(plan, getter.presence)
      addNullableValueGetter(plan, getter.value)
    }
  }
  when (val setter: ForwardPropertySetter? = plan.setter) {
    null -> Unit
    is ForwardPropertySetter.Direct -> addSetter(plan, setter.call, null)
    is ForwardPropertySetter.NullableDispatch -> {
      addSetter(plan, setter.value, false)
      addSetter(plan, setter.nullValue, true)
    }
  }
}

private fun FileSpec.Builder.addGetter(plan: ForwardPropertyPlan, call: ForwardNativeCall) {
  val builder: FunSpec.Builder = exportBuilder(call, plan.receiver, plan.ownerTag())
  val access: String = plan.readExpression()
  when (val type: BridgeType = plan.type) {
    BridgeType.Unit -> builder.addCode(
      unitBody(access, "errorOut"), cOpaquePointerVar, nugetHandles,
    )
    is BridgeType.Primitive -> {
      builder.returns(kotlinType(type))
      builder.addCode(
        valueBody(access, "errorOut", primitiveDefault(type)),
        cOpaquePointerVar,
        nugetHandles,
      )
    }

    BridgeType.Char -> {
      builder.returns(kotlinType("Char"))
      builder.addCode(valueBody(access, "errorOut", "'\\u0000'"), cOpaquePointerVar, nugetHandles)
    }

    BridgeType.String -> {
      builder.returns(kotlinType(type))
      builder.addCode(valueBody(access, "errorOut", "\"\""), cOpaquePointerVar, nugetHandles)
    }

    // ADR-106: the String getter with `toString()` composed in -- the RFC 9562 lowercase hex-dash
    // text `Guid.Parse` reads verbatim.
    BridgeType.Uuid -> {
      builder.returns(kotlinType("String"))
      builder.addCode(
        valueBody("$access.toString()", "errorOut", "\"\""),
        cOpaquePointerVar,
        nugetHandles,
      )
    }

    is BridgeType.Nullable -> when (val inner: BridgeType = type.type) {
      BridgeType.String -> {
        builder.returns(kotlinType(type))
        builder.addCode(valueBody(access, "errorOut", "null"), cOpaquePointerVar, nugetHandles)
      }

      // ADR-106: `Uuid?` ships the null pointer for null -- the String? shape with a safe-called
      // `toString()`, never Instant's has-value pair.
      BridgeType.Uuid -> {
        builder.returns(kotlinType("String").copy(nullable = true))
        builder.addCode(
          valueBody("$access?.toString()", "errorOut", "null"),
          cOpaquePointerVar,
          nugetHandles,
        )
      }

      // ADR-075: a nullable collection has no element-type restriction on the read side --
      // `nullableHandleBody` already returns Kotlin `null` for a null result before ever building
      // a `StableRef`, the same route a nullable `ObjectHandle`/`Interface` getter takes.
      // ADR-083/147: `T?` ships the null pointer for null, the retained box otherwise.
      // ADR-151: `ByteArray?` too -- `NugetMarshal.ReadBytes` never sees a zero handle, the
      // generated C# returns null before it.
      is BridgeType.ObjectHandle, is BridgeType.Interface, is BridgeType.Collection,
      BridgeType.ByteArray, is BridgeType.TypeParameter -> {
        // ADR-081: a value-class component is projected to its underlying before boxing, with the
        // whole chain `?.`-guarded so a null property value still ships a null pointer.
        val boxed: String = if (inner is BridgeType.Collection) {
          collectionResultProjection(access, inner, nullable = true)
        } else {
          access
        }
        builder.returns(cOpaquePointer.copy(nullable = true))
        builder.addCode(
          nullableHandleBody(boxed, "errorOut"),
          nugetHandles,
          cOpaquePointerVar,
          nugetHandles,
        )
      }

      // ADR-077 sub-items 3/4: safe-call unboxing; a Kotlin null ships the null pointer, over the
      // nullable-String wire or as a null StableRef pointer for an ObjectHandle underlying.
      is BridgeType.ValueClass -> {
        val unboxed = "$access?.${inner.underlyingPropertyName}"
        if (inner.underlying is BridgeType.ObjectHandle) {
          builder.returns(cOpaquePointer.copy(nullable = true))
          builder.addCode(
            nullableHandleBody(unboxed, "errorOut"),
            nugetHandles,
            cOpaquePointerVar,
            nugetHandles,
          )
        } else {
          builder.returns(kotlinType("String").copy(nullable = true))
          builder.addCode(valueBody(unboxed, "errorOut", "null"), cOpaquePointerVar, nugetHandles)
        }
      }

      // ADR-107: the nullable Throwable getter ships the same envelope a thrown exception writes
      // into errorOut, in the result slot; a null property value ships the null pointer, exactly
      // as a nullable ObjectHandle getter does.
      is BridgeType.Throwable -> {
        builder.returns(cOpaquePointer.copy(nullable = true))
        builder.addCode(
          nullableHandleBody("$access?.let { buildError(it, ::nugetMappedType) }", "errorOut"),
          nugetHandles,
          cOpaquePointerVar,
          nugetHandles,
        )
      }

      BridgeType.Unit,
      is BridgeType.Primitive,
      BridgeType.Char,
      BridgeType.Instant,
      BridgeType.Duration,
      is BridgeType.Enum,
      is BridgeType.BoundInterface,
      is BridgeType.Nullable,
      is BridgeType.Callback,
      is BridgeType.AsyncCallback,
      is BridgeType.FlowSource,
      is BridgeType.ReturnedLambda,
      is BridgeType.SpecializedProtocol,
      is BridgeType.RawKSType,
      is BridgeType.Unsupported,
      is BridgeType.RawCollection,
        -> error("Forward property direct nullable getter is invalid for ${plan.symbol}: $inner")
    }

    is BridgeType.Enum -> {
      builder.returns(kotlinType("Int"))
      builder.addCode(
        valueBody("$access.ordinal", "errorOut", "0"),
        cOpaquePointerVar,
        nugetHandles,
      )
    }

    // ADR-103: Duration takes the identical branch (its own `toDotNetTicks()` receiver).
    BridgeType.Instant, BridgeType.Duration -> {
      builder.returns(kotlinType("Long"))
      builder.addCode(
        valueBody("$access.toDotNetTicks()", "errorOut", "0L"),
        cOpaquePointerVar,
        nugetHandles,
      )
    }

    // ADR-147: a `T` getter mints the same `NugetHandles.retain` box.
    // ADR-151: a ByteArray getter mints one too, read back by `NugetMarshal.ReadBytes`.
    is BridgeType.ObjectHandle, is BridgeType.Interface, is BridgeType.Collection,
    BridgeType.ByteArray, is BridgeType.TypeParameter -> {
      // ADR-081: read side of a collection property whose component is a value class -- box a copy
      // projected to the underlying, the shape a C# per-element re-wrap can read.
      val boxed: String =
        if (type is BridgeType.Collection) collectionResultProjection(access, type) else access
      builder.returns(cOpaquePointer.copy(nullable = true))
      builder.addCode(handleBody(boxed, "errorOut"), nugetHandles, cOpaquePointerVar, nugetHandles)
    }

    // ADR-107: non-null Throwable. Same envelope, unconditionally built.
    is BridgeType.Throwable -> {
      builder.returns(cOpaquePointer.copy(nullable = true))
      builder.addCode(
        handleBody("buildError($access, ::nugetMappedType)", "errorOut"),
        nugetHandles,
        cOpaquePointerVar,
        nugetHandles,
      )
    }

    // ADR-077 sub-items 2/4: unbox to the underlying property, so the export ships the
    // underlying's wire representation (String, primitive, int ordinal, StableRef pointer); C#
    // reconstructs the record struct.
    is BridgeType.ValueClass -> {
      val unboxed = "$access.${type.underlyingPropertyName}"
      when (val underlying: BridgeType = type.underlying) {
        BridgeType.String -> {
          builder.returns(kotlinType("String"))
          builder.addCode(valueBody(unboxed, "errorOut", "\"\""), cOpaquePointerVar, nugetHandles)
        }

        is BridgeType.Primitive -> {
          builder.returns(kotlinType(underlying))
          builder.addCode(
            valueBody(unboxed, "errorOut", primitiveDefault(underlying)),
            cOpaquePointerVar,
            nugetHandles,
          )
        }

        is BridgeType.Enum -> {
          builder.returns(kotlinType("Int"))
          builder.addCode(
            valueBody("$unboxed.ordinal", "errorOut", "0"),
            cOpaquePointerVar,
            nugetHandles,
          )
        }

        is BridgeType.ObjectHandle -> {
          builder.returns(cOpaquePointer.copy(nullable = true))
          builder.addCode(
            handleBody(unboxed, "errorOut"), nugetHandles, cOpaquePointerVar, nugetHandles,
          )
        }

        BridgeType.Unit,
        BridgeType.Char,
        BridgeType.Instant,
        BridgeType.Duration,
        is BridgeType.Throwable,
        BridgeType.Uuid,
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
          -> error(
          "Forward property emitter has no value-class getter for underlying $underlying",
        )
      }
    }

    is BridgeType.BoundInterface,
    is BridgeType.Callback,
    is BridgeType.AsyncCallback,
    is BridgeType.FlowSource,
    is BridgeType.ReturnedLambda,
    is BridgeType.SpecializedProtocol,
    is BridgeType.RawKSType,
    is BridgeType.Unsupported,
    is BridgeType.RawCollection,
      -> error("Forward property emitter has no getter route for $type")
  }
  addFunction(builder.build())
}

private fun FileSpec.Builder.addNullablePresenceGetter(
  plan: ForwardPropertyPlan,
  call: ForwardNativeCall,
) {
  val builder: FunSpec.Builder =
    exportBuilder(call, plan.receiver, plan.ownerTag()).returns(kotlinType("Boolean"))
  builder.addCode(
    valueBody("${plan.readExpression()} != null", "errorOut", "false"),
    cOpaquePointerVar,
    nugetHandles,
  )
  addFunction(builder.build())
}

private fun FileSpec.Builder.addNullableValueGetter(
  plan: ForwardPropertyPlan,
  call: ForwardNativeCall,
) {
  val inner: BridgeType = (plan.type as BridgeType.Nullable).type
  val builder: FunSpec.Builder = when (inner) {
    is BridgeType.Primitive -> {
      val getterBuilder: FunSpec.Builder = exportBuilder(call, plan.receiver, plan.ownerTag())
        .returns(kotlinType(inner))
      getterBuilder.addCode(
        valueBody("${plan.readExpression()}!!", "errorOut", primitiveDefault(inner)),
        cOpaquePointerVar,
        nugetHandles,
      )
      getterBuilder
    }

    // ADR-098 amendment (boundary nullability part C): `Char?` rides the same LegacyTwoCall
    // `_value` call, returning a by-value `Char` over the CHAR16 wire. The by-value slot is the one
    // a non-null `Char` property already uses, so the U2 marshalling ADR-098 minted covers it
    // unchanged; only the shape selection is new.
    BridgeType.Char -> exportBuilder(call, plan.receiver, plan.ownerTag())
      .returns(kotlinType("Char"))
      .addCode(
        valueBody("${plan.readExpression()}!!", "errorOut", "'\\u0000'"),
        cOpaquePointerVar,
        nugetHandles,
      )

    // ADR-076: same LegacyTwoCall value shape as the nullable Primitive case above, converted to
    // ticks before it crosses the wire.
    // ADR-103: same again for Duration.
    BridgeType.Instant, BridgeType.Duration -> {
      val getterBuilder: FunSpec.Builder = exportBuilder(call, plan.receiver, plan.ownerTag())
        .returns(kotlinType("Long"))
      getterBuilder.addCode(
        valueBody("${plan.readExpression()}!!.toDotNetTicks()", "errorOut", "0L"),
        cOpaquePointerVar,
        nugetHandles,
      )
      getterBuilder
    }

    // ADR-080: a bare nullable enum rides the same LegacyTwoCall `_value` call as its ordinal.
    is BridgeType.Enum -> exportBuilder(call, plan.receiver, plan.ownerTag())
      .returns(kotlinType("Int"))
      .addCode(
        valueBody("${plan.readExpression()}!!.ordinal", "errorOut", "0"),
        cOpaquePointerVar,
        nugetHandles,
      )

    // ADR-079: a Primitive/Enum-underlying value class rides the same LegacyTwoCall `_value` call,
    // unboxed to the underlying (the ordinal for an enum underlying).
    is BridgeType.ValueClass -> {
      val unboxed = "${plan.readExpression()}!!.${inner.underlyingPropertyName}"
      val getterBuilder: FunSpec.Builder = exportBuilder(call, plan.receiver, plan.ownerTag())
      when (val underlying: BridgeType = inner.underlying) {
        is BridgeType.Primitive -> getterBuilder
          .returns(kotlinType(underlying))
          .addCode(
            valueBody(unboxed, "errorOut", primitiveDefault(underlying)),
            cOpaquePointerVar,
            nugetHandles,
          )

        is BridgeType.Enum -> getterBuilder
          .returns(kotlinType("Int"))
          .addCode(valueBody("$unboxed.ordinal", "errorOut", "0"), cOpaquePointerVar, nugetHandles)

        BridgeType.Unit,
        BridgeType.Char,
        BridgeType.String,
        BridgeType.Instant,
        BridgeType.Duration,
        is BridgeType.Throwable,
        BridgeType.Uuid,
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
          -> error(
          "Forward property nullable value getter has no value-class underlying route for " +
              "${plan.symbol}: $underlying",
        )
      }
      getterBuilder
    }

    BridgeType.Unit,
    BridgeType.String,
    is BridgeType.Throwable,
    BridgeType.Uuid,
    is BridgeType.ObjectHandle,
    is BridgeType.Interface,
    is BridgeType.BoundInterface,
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
      -> error("Forward property nullable value getter is invalid for ${plan.symbol}: $inner")
  }
  addFunction(builder.build())
}

private fun FileSpec.Builder.addSetter(
  plan: ForwardPropertyPlan,
  call: ForwardNativeCall,
  assignsNull: Boolean?,
) {
  val builder: FunSpec.Builder =
    exportBuilder(call, plan.receiver, plan.ownerTag(), includeError = false)
  if (assignsNull != true) {
    val valueType: BridgeType = requireNotNull(
      call.parameters.firstOrNull { it.role == ForwardAbiRole.SETTER_VALUE }?.transfer?.type,
    ) {
      "Forward property setter ${call.exportName} has no value transfer"
    }
    builder.addParameter("value", kotlinInputType(valueType))
  }
  builder.addParameter("errorOut", cOpaquePointer.copy(nullable = true))
  // ADR-218: a `T` value with no single erased argument assigns inside the ADR-198 trampoline.
  val trampoline: ForwardGenericOwnerParameter? = plan.setterTrampoline()
  val expression: String = plan.accessExpression(trampoline)
  val assignment: String = when {
    assignsNull == true -> "$expression = null"
    trampoline != null && plan.type.unwrapNullable() is BridgeType.TypeParameter ->
      "$expression = ${plan.trampolinedValueExpression(trampoline)}"
    else -> "$expression = ${plan.valueExpression()}"
  }
  builder.addCode(unitBody(assignment, "errorOut"), cOpaquePointerVar, nugetHandles)
  val value: BridgeType.TypeParameter? = plan.type.unwrapNullable() as? BridgeType.TypeParameter
  // ADR-147: a checked read through a generic bound's erased spelling warns on its arguments.
  if (value != null && !value.trampolined && value.bounds().castsToGenericBound()) {
    builder.addAnnotation(forwardUncheckedCastSuppression)
  }
  val typeVariables: List<Pair<String, List<String>>> =
    listOfNotNull(trampoline).map { parameter -> parameter.kotlinName to parameter.bounds }
  addFunction(builder.build().trampolined(typeVariables))
}

/**
 * ADR-117 owner of a property export. ADR-006 amendment: an enum member property shares its plan
 * symbol with a (shadowed) extension property of the same name on the enum, and both claim one C
 * entry point, so the member is tagged by its role or the collision would list one name twice.
 */
private fun ForwardPropertyPlan.ownerTag(): ForwardExportOwnerTag = ForwardExportOwnerTag(
  symbol = symbol,
  role = if (position == ForwardPropertyPosition.ENUM_MEMBER) "enum member property" else null,
)

private fun exportBuilder(
  call: ForwardNativeCall,
  receiver: ForwardPropertyReceiver,
  // ADR-117: a property plan is its own owner symbol (properties cannot overload).
  owner: ForwardExportOwnerTag,
  includeError: Boolean = true,
): FunSpec.Builder {
  val builder: FunSpec.Builder = FunSpec.builder("export_${call.exportName}")
    .addAnnotation(cNameAnnotation(call.exportName, owner))
  when (receiver) {
    is ForwardPropertyReceiver.Handle -> builder.addParameter("handle", cOpaquePointer)
    // ADR-132 amendment (2026-10-04): a fan-out receiver is the flag, then the value typed off the
    // INNER type. `kotlinInputType` of the nullable type would be `Int?`, which Kotlin/Native
    // exports as a boxed pointer rather than the `int` the C# import passes.
    is ForwardPropertyReceiver.Value -> when (val inner: BridgeType? = receiver.fanOutInner()) {
      null -> builder.addParameter("receiver", kotlinInputType(receiver.type))
      else -> builder
        .addParameter(RECEIVER_HAS_VALUE, kotlinType("Boolean"))
        .addParameter("receiver", kotlinInputType(inner))
    }

    is ForwardPropertyReceiver.Static -> Unit
    // ADR-157: the box reads through the base handle, the same single slot a Handle receiver has.
    is ForwardPropertyReceiver.EnumArm -> builder.addParameter("handle", cOpaquePointer)
  }
  if (includeError) builder.addParameter("errorOut", cOpaquePointer.copy(nullable = true))
  return builder
}

private fun ForwardPropertyPlan.accessExpression(
  // ADR-218: inside the trampoline the owner is applied to the trampoline's own type variable,
  // through a cast (`asStableRef` is reified, and a type variable is not).
  trampoline: ForwardGenericOwnerParameter? = null,
): String {
  // Backticked where the declared name needs it: a hard keyword, or a space or symbol.
  val name: String = kotlinName.kotlinIdentifier()
  return when (val receiver: ForwardPropertyReceiver = receiver) {
    is ForwardPropertyReceiver.Handle -> {
      val generic: ForwardGenericOwner? = receiver.generic
      if (trampoline != null && generic != null) {
        val applied: String = generic.applied(
          generic.typeParameters.joinToString(", ") { parameter ->
            if (parameter.kotlinName == trampoline.kotlinName) parameter.kotlinName
            else parameter.erased ?: "*"
          },
        )
        "(handle.asStableRef<Any>().get() as $applied).$name"
      } else {
        "handle.asStableRef<${receiver.owner}>().get().$name"
      }
    }

    // ADR-157: no member access after the cast. The box's `Value` IS the receiver -- an enum entry
    // has no property that answers with itself -- and the `Enum` result branch appends `.ordinal`.
    is ForwardPropertyReceiver.EnumArm ->
      "(handle.asStableRef<${receiver.base}>().get() as ${receiver.enum})"

    // ADR-132 at the property position: the receiver is lowered by the *same* wire-to-Kotlin
    // function the setter value uses, so an interface receiver reads its borrowed StableRef and a
    // value-class receiver is reconstructed from its underlying (ADR-014/075) before the property
    // access. The nullable handle receiver's `?.` chain is parenthesised, because `a?.b().c` binds
    // `.c` to the *safe-called* result, which is not the nullable extension's receiver.
    // ADR-132 amendment (2026-10-04): a fan-out receiver reads its flag. `inputLowering`'s nullable
    // arms for these shapes lower the value unconditionally (they were written for the
    // `NullableDispatch` setter value, non-null by construction), so the flag picks between that
    // and a real `null`. Without it the null twin compiles clean and reads the value slot's `0`.
    is ForwardPropertyReceiver.Value -> {
      val lowered: String = inputLowering(receiver.type, "receiver")
      when {
        receiver.fanOutInner() != null ->
          "(if ($RECEIVER_HAS_VALUE) $lowered else null).$name"

        receiver.type is BridgeType.Nullable -> "($lowered).$name"
        else -> "$lowered.$name"
      }
    }

    is ForwardPropertyReceiver.Static ->
      receiver.owner?.let { "$it.$name" } ?: name
  }
}

/** ADR-235: the setter value, re-wrapped in its WIRE value class so `init` runs on the write. */
private fun ForwardPropertyPlan.valueExpression(): String {
  val lowered: String = inputLowering(type, "value")
  return view?.kotlinWrap(lowered) ?: lowered
}

/** ADR-235: the getter read, unwrapped to the underlying of a WIRE value class. */
private fun ForwardPropertyPlan.readExpression(): String {
  val access: String = accessExpression()
  return view?.kotlinUnwrap(access) ?: access
}

/**
 * ADR-218: the owner parameter a `T` setter value names when that parameter has no single erased
 * argument (ADR-198 trampolined, or multi-bound), so the erased receiver cannot take the value.
 * Null for every other setter, which keeps its plain assignment.
 */
private fun ForwardPropertyPlan.setterTrampoline(): ForwardGenericOwnerParameter? {
  val generic: ForwardGenericOwner = (receiver as? ForwardPropertyReceiver.Handle)?.generic
    ?: return null
  // ADR-217: a trampolined `T` inside a collection (`var a: List<A>`) needs the type variable too.
  val names: Set<String> = type.typeParameterNames()
  return generic.typeParameters.firstOrNull { parameter ->
    parameter.kotlinName in names && parameter.erased == null
  }
}

/** ADR-217: every type parameter a setter value carries, bare or as a collection component. */
private fun BridgeType.typeParameterNames(): Set<String> {
  val unwrapped: BridgeType = unwrapNullable()
  if (unwrapped is BridgeType.TypeParameter) return setOf(unwrapped.kotlinName)
  if (unwrapped !is BridgeType.Collection) return emptySet()
  return listOfNotNull(unwrapped.element, unwrapped.key, unwrapped.value)
    .flatMapTo(mutableSetOf()) { component -> component.typeParameterNames() }
}

/** ADR-218: the setter value read inside the trampoline, cast last to its type variable. */
private fun ForwardPropertyPlan.trampolinedValueExpression(
  trampoline: ForwardGenericOwnerParameter,
): String {
  val value: BridgeType.TypeParameter = type.unwrapNullable() as BridgeType.TypeParameter
  return forwardBoundedRead(
    "value", value.bounds(), nullable = type is BridgeType.Nullable, trampoline.kotlinName,
  )
}

/** ADR-218: a `T` setter value, the callable route's bounded read of its box. */
private fun typeParameterLowering(
  name: String,
  type: BridgeType.TypeParameter,
  nullable: Boolean,
): String = forwardBoundedRead(
  name, type.bounds(), nullable, type.kotlinName.takeIf { type.trampolined },
)

/** ADR-147: every bound a `T` box is cast to, first one first; empty when unconstrained. */
private fun BridgeType.TypeParameter.bounds(): List<String> =
  listOfNotNull(boundQualifiedName) + additionalBounds

/**
 * The wire value named [name], lowered to the Kotlin type [type] spells. Shared by the setter's
 * value and (ADR-132) by an extension property's receiver: both are inputs on the same wire, so
 * one `when` covers both and a shape neither has an arm for is a build failure rather than a
 * `receiver.foo` on a `COpaquePointer`.
 */
private fun inputLowering(type: BridgeType, name: String): String = when (type) {
  is BridgeType.Nullable -> when (val inner: BridgeType = type.type) {
    is BridgeType.Primitive, BridgeType.Char, BridgeType.String -> name
    // ADR-106: a null wire value stays null; only real text is parsed.
    BridgeType.Uuid -> "$name?.let(kotlin.uuid.Uuid::parse)"
    // ADR-201: the callable parameter's lowering, so the two input routes cannot drift.
    is BridgeType.Throwable -> managedExceptionLowering(name, nullable = true)
    is BridgeType.ObjectHandle ->
      "$name?.asStableRef<${inner.kotlinReadType ?: inner.qualifiedName}>()?.get()"
    is BridgeType.Interface -> "$name?.asStableRef<${inner.qualifiedName}>()?.get()"
    // ADR-076: the wire value is a raw INT64 of ticks; convert it back to an Instant.
    BridgeType.Instant -> "instantFromDotNetTicks($name)"
    // ADR-103: the same, into a Duration.
    BridgeType.Duration -> "durationFromDotNetTicks($name)"
    // ADR-080: the NullableDispatch `set` export carries the bare ordinal (`set_null` is the
    // other export), so the entry lookup is unconditional.
    is BridgeType.Enum -> "${inner.qualifiedName}.entries[$name]"
    // ADR-075 Question D: a nullable collection setter is an ordinary `Direct` route with a
    // nullable `COpaquePointer` value -- `?.` short-circuits before `asStableRef` is ever reached
    // for a null wire value, so the property's static type stays the property's own `List<T>?`.
    is BridgeType.Collection -> loweredCollectionExpression(name, inner, nullable = true)
    // ADR-151: `?.` short-circuits on the null wire pointer, exactly as the collection arm does.
    BridgeType.ByteArray -> "$name?.asStableRef<kotlin.ByteArray>()?.get()"
    // ADR-218: a null `T?` (or a nullable-bounded `T`) arrives as the null pointer.
    is BridgeType.TypeParameter -> typeParameterLowering(name, inner, nullable = true)
    // ADR-077 sub-items 3/4: `?.let` re-wraps only a non-null wire value, matching the callable
    // parameter lowering in ForwardKotlinPlanEmitter.
    // ADR-079: a Primitive/Enum underlying takes the NullableDispatch route instead, whose `set`
    // export receives the bare underlying wire (non-null by construction; `set_null` is the other
    // export), so the value is re-wrapped unconditionally.
    is BridgeType.ValueClass ->
      if (inner.underlying is BridgeType.Primitive || inner.underlying is BridgeType.Enum) {
        "${inner.qualifiedName}(${valueClassUnderlyingLowering(name, inner.underlying)})"
      } else {
        val lowered: String = valueClassUnderlyingLowering("it", inner.underlying)
        "$name?.let { ${inner.qualifiedName}($lowered) }"
      }

    BridgeType.Unit,
    is BridgeType.BoundInterface,
    is BridgeType.Nullable,
    is BridgeType.Callback,
    is BridgeType.AsyncCallback,
    is BridgeType.FlowSource,
    is BridgeType.ReturnedLambda,
    is BridgeType.SpecializedProtocol,
    is BridgeType.RawKSType,
    is BridgeType.Unsupported,
    is BridgeType.RawCollection,
      -> error("Forward property emitter has no nullable input route for $type")
  }

  is BridgeType.Primitive, BridgeType.Char, BridgeType.String -> name
  // ADR-106: parse the canonical text back; spelled fully qualified so no import is needed.
  BridgeType.Uuid -> "kotlin.uuid.Uuid.parse($name)"
  // ADR-201: the managed exception text, as a `NugetManagedException`.
  is BridgeType.Throwable -> managedExceptionLowering(name, nullable = false)
  is BridgeType.Enum -> "${type.qualifiedName}.entries[$name]"
  BridgeType.Instant -> "instantFromDotNetTicks($name)"
  BridgeType.Duration -> "durationFromDotNetTicks($name)"
  is BridgeType.ObjectHandle ->
    "$name.asStableRef<${type.kotlinReadType ?: type.qualifiedName}>().get()"
  is BridgeType.Interface -> "$name.asStableRef<${type.qualifiedName}>().get()"
  // ADR-088 / ADR-132 (2026-09-20): a bound C# interface receiver is read back through the reverse
  // pipeline's own `nuget{Iface}Value`, the SAME helper the callable route's bound-interface
  // parameter uses -- it decides between a token-probe hit (a Kotlin object all along, GCHandle
  // freed there) and wrapping the handle in an ADR-070 wrapper whose cleaner frees it. Kotlin takes
  // ownership, so there is deliberately nothing for the C# side to dispose.
  is BridgeType.BoundInterface -> "${type.valueHelper()}($name)"
  is BridgeType.Collection -> loweredCollectionExpression(name, type)
  // ADR-151: the setter value is the handle C# minted with `NugetMarshal.CreateBytes`.
  BridgeType.ByteArray -> "$name.asStableRef<kotlin.ByteArray>().get()"
  // ADR-218: the box C# minted with `Wrap<T>` (or a borrowed wrapper's handle).
  is BridgeType.TypeParameter -> typeParameterLowering(name, type, nullable = false)
  // ADR-077 sub-items 2/4: re-wrap the raw underlying wire value, re-running the value class's
  // own `init` validation, exactly like the callable parameter lowering in
  // ForwardKotlinPlanEmitter.
  is BridgeType.ValueClass ->
    "${type.qualifiedName}(${valueClassUnderlyingLowering(name, type.underlying)})"

  BridgeType.Unit,
  is BridgeType.Callback,
  is BridgeType.AsyncCallback,
  is BridgeType.FlowSource,
  is BridgeType.ReturnedLambda,
  is BridgeType.SpecializedProtocol,
  is BridgeType.RawKSType,
  is BridgeType.Unsupported,
  is BridgeType.RawCollection,
    -> error("Forward property emitter has no input route for $type")
}

private fun kotlinInputType(type: BridgeType): TypeName = when (type) {
  is BridgeType.Nullable -> kotlinInputType(type.type).copy(nullable = true)
  is BridgeType.Primitive -> kotlinType(type)
  BridgeType.Char -> kotlinType("Char")
  // ADR-106: a Uuid setter's wire value is its text form.
  // ADR-201: and so is a Throwable setter's, `"{FullName}: {Message}"`.
  BridgeType.String, BridgeType.Uuid, is BridgeType.Throwable -> kotlinType("String")
  is BridgeType.Enum -> kotlinType("Int")
  BridgeType.Instant, BridgeType.Duration -> kotlinType("Long")
  // ADR-014: the underlying is what actually crosses the wire, both for an extension property's
  // value-class receiver (ADR-075) and for an ordinary value-class property's setter value
  // (ADR-077 sub-item 2).
  is BridgeType.ValueClass -> kotlinInputType(type.underlying)
  // ADR-088: the bound-interface transfer GCHandle is the same opaque pointer slot.
  is BridgeType.ObjectHandle, is BridgeType.Interface, is BridgeType.Collection,
  is BridgeType.BoundInterface, BridgeType.ByteArray, is BridgeType.TypeParameter ->
    cOpaquePointer.copy(nullable = type is BridgeType.Nullable)

  BridgeType.Unit,
  is BridgeType.Callback,
  is BridgeType.AsyncCallback,
  is BridgeType.FlowSource,
  is BridgeType.ReturnedLambda,
  is BridgeType.SpecializedProtocol,
  is BridgeType.RawKSType,
  is BridgeType.Unsupported,
  is BridgeType.RawCollection,
    -> error("Forward property emitter has no input type for $type")
}

private fun kotlinType(type: BridgeType): TypeName = when (type) {
  is BridgeType.Nullable -> kotlinType(type.type).copy(nullable = true)
  is BridgeType.Primitive -> kotlinType(
    when (type.kind) {
      PrimitiveKind.BOOLEAN -> "Boolean"
      PrimitiveKind.BYTE -> "Byte"
      PrimitiveKind.UBYTE -> "UByte"
      PrimitiveKind.SHORT -> "Short"
      PrimitiveKind.USHORT -> "UShort"
      PrimitiveKind.INT -> "Int"
      PrimitiveKind.UINT -> "UInt"
      PrimitiveKind.LONG -> "Long"
      PrimitiveKind.ULONG -> "ULong"
      PrimitiveKind.FLOAT -> "Float"
      PrimitiveKind.DOUBLE -> "Double"
    },
  )

  BridgeType.Char -> kotlinType("Char")
  BridgeType.String -> kotlinType("String")
  BridgeType.Unit,
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
  is BridgeType.Callback,
  is BridgeType.AsyncCallback,
  is BridgeType.FlowSource,
  is BridgeType.ReturnedLambda,
  is BridgeType.SpecializedProtocol,
  is BridgeType.RawKSType,
  is BridgeType.Unsupported,
  is BridgeType.RawCollection,
  is BridgeType.TypeParameter,
    -> error("Forward property emitter has no Kotlin result type for $type")
}

private fun kotlinType(name: String): ClassName = ClassName("kotlin", name)

private fun primitiveDefault(type: BridgeType.Primitive): String = when (type.kind) {
  PrimitiveKind.BOOLEAN -> "false"
  PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT, PrimitiveKind.LONG -> "0"
  PrimitiveKind.UBYTE -> "0.toUByte()"
  PrimitiveKind.USHORT -> "0.toUShort()"
  PrimitiveKind.UINT -> "0u"
  PrimitiveKind.ULONG -> "0uL"
  PrimitiveKind.FLOAT -> "0.0f"
  PrimitiveKind.DOUBLE -> "0.0"
}

private fun unitBody(invocation: String, error: String): String = buildString {
  appendLine("try {")
  appendLine("  $invocation")
  appendLine("} catch (e: Throwable) {")
  appendLine("  if ($error != null) {")
  appendLine("    $error.reinterpret<%T>().pointed.value = %T.retain(")
  appendLine("      buildError(e, ::nugetMappedType)")
  appendLine("    )")
  appendLine("  }")
  append("}")
}

internal fun valueBody(invocation: String, error: String, fallback: String): String = buildString {
  appendLine("return try {")
  appendLine("  $invocation")
  appendLine("} catch (e: Throwable) {")
  appendLine("  if ($error != null) {")
  appendLine("    $error.reinterpret<%T>().pointed.value = %T.retain(")
  appendLine("      buildError(e, ::nugetMappedType)")
  appendLine("    )")
  appendLine("  }")
  appendLine("  $fallback")
  append("}")
}

internal fun handleBody(invocation: String, error: String): String = buildString {
  appendLine("return try {")
  appendLine("  %T.retain($invocation)")
  appendLine("} catch (e: Throwable) {")
  appendLine("  if ($error != null) {")
  appendLine("    $error.reinterpret<%T>().pointed.value = %T.retain(")
  appendLine("      buildError(e, ::nugetMappedType)")
  appendLine("    )")
  appendLine("  }")
  appendLine("  null")
  append("}")
}

internal fun nullableHandleBody(invocation: String, error: String): String = buildString {
  appendLine("return try {")
  appendLine("  val result = $invocation")
  appendLine("  if (result == null) null else %T.retain(result)")
  appendLine("} catch (e: Throwable) {")
  appendLine("  if ($error != null) {")
  appendLine("    $error.reinterpret<%T>().pointed.value = %T.retain(")
  appendLine("      buildError(e, ::nugetMappedType)")
  appendLine("    )")
  appendLine("  }")
  appendLine("  null")
  append("}")
}
