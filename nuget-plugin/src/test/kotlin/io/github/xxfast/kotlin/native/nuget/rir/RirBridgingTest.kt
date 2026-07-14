package io.github.xxfast.kotlin.native.nuget.rir

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for the shared, order-sensitive filtering functions in RirBridging.kt.
 *
 * Phase 9 (ROADMAP line 151, instance methods/properties) — confirmed "mirror" item, no new ADR:
 * "an instance thunk is a static thunk whose first parameter is the receiver handle" (ADR-051
 * Deferred section). This test class extends the existing ADR-052 [bridgeableRegistrables]
 * ordering contract with instance methods and instance properties.
 *
 * [bridgeableProperties] accepts static and instance v1-typed properties. ADR-053 deletes "rule 4"
 * (handle-typed settable properties used to get a getter slot only): a settable handle-typed
 * property now always gets both a getter and a setter slot, the same as any other settable
 * property. Rule 5 (member-name collisions with the ADR-051 wrapper's own `handle`/`close`/
 * `cleaner` members are skipped + diagnosed via [collisionDiagnostics], reusing the existing
 * [RirDiagnostic] model) is unaffected.
 */
class RirBridgingTest {

  // ------------------------------------------------------------------
  // 1. Total registration ordering: Ctor, static methods, instance methods, instance-property
  //    getter/setter pairs, then static-property getter/setter pairs — groups appended, never
  //    interleaved (the load-bearing contract both generators derive their output from).
  // ------------------------------------------------------------------

  private val orderingCls: RirClass = RirClass(
    name = "Template",
    isStatic = false,
    constructors = listOf(
      RirConstructor(parameters = listOf(RirParameter(name = "source", type = RirStringType()))),
    ),
    methods = listOf(
      RirMethod(
        name = "StaticA", isStatic = true, returnType = RirVoidType, parameters = emptyList(),
      ),
      RirMethod(
        name = "StaticB", isStatic = true, returnType = RirVoidType, parameters = emptyList(),
      ),
      RirMethod(
        name = "InstanceA", isStatic = false, returnType = RirVoidType, parameters = emptyList(),
      ),
      RirMethod(
        name = "InstanceB", isStatic = false, returnType = RirVoidType, parameters = emptyList(),
      ),
    ),
    properties = listOf(
      RirProperty(name = "Name", type = RirStringType(), isReadOnly = true, isStatic = false),
      RirProperty(
        name = "Count", type = RirPrimitiveType("int"), isReadOnly = false, isStatic = false,
      ),
      RirProperty(
        name = "DefaultName", type = RirStringType(), isReadOnly = false, isStatic = true,
      ),
      RirProperty(
        name = "RenderCount", type = RirPrimitiveType("int"), isReadOnly = true, isStatic = true,
      ),
    ),
  )

  private val orderingBoundTypes: Set<RirTypeKey> = setOf(RirTypeKey("Sample.Text", "Template"))

  @Test
  fun `bridgeableRegistrables appends static property getter-setter pairs after instance property pairs`() {
    val expected: List<RirRegistrable> = listOf(
      RirRegistrable.Ctor(orderingCls.constructors[0]),
      RirRegistrable.Method(orderingCls.methods[0]), // StaticA
      RirRegistrable.Method(orderingCls.methods[1]), // StaticB
      RirRegistrable.Method(orderingCls.methods[2]), // InstanceA
      RirRegistrable.Method(orderingCls.methods[3]), // InstanceB
      RirRegistrable.PropertyGetter(orderingCls.properties[0]), // Name (read-only) getter
      RirRegistrable.PropertyGetter(orderingCls.properties[1]), // Count (settable) getter
      RirRegistrable.PropertySetter(orderingCls.properties[1]), // Count (settable) setter
      RirRegistrable.PropertyGetter(orderingCls.properties[2]), // DefaultName (settable) getter
      RirRegistrable.PropertySetter(orderingCls.properties[2]), // DefaultName (settable) setter
      RirRegistrable.PropertyGetter(orderingCls.properties[3]), // RenderCount (read-only) getter
    )

    val actual: List<RirRegistrable> = bridgeableRegistrables(orderingCls, orderingBoundTypes)

    assertEquals(
      expected,
      actual,
      "Phase 9 static properties: ctor, static methods, instance methods, instance-property " +
          "getter/[setter] pairs, then static-property getter/[setter] pairs — got $actual",
    )
  }

  // ------------------------------------------------------------------
  // 2. bridgeableProperties: static and instance properties are both bridgeable
  // ------------------------------------------------------------------

  private val staticVsInstancePropCls: RirClass = RirClass(
    name = "Foo",
    isStatic = false,
    properties = listOf(
      RirProperty(name = "StaticProp", type = RirStringType(), isReadOnly = true, isStatic = true),
      RirProperty(
        name = "InstanceProp", type = RirStringType(), isReadOnly = true, isStatic = false,
      ),
    ),
  )

  @Test
  fun `bridgeableProperties includes static and instance properties in declaration order`() {
    val result: List<RirProperty> = bridgeableProperties(staticVsInstancePropCls, emptySet())

    assertEquals(
      staticVsInstancePropCls.properties,
      result,
      "static properties now share the same v1 type filter as instance properties and must " +
          "retain reverse-ir declaration order",
    )
  }

  // ------------------------------------------------------------------
  // 3. bridgeableProperties: non-v1-typed properties excluded (unbound handle type)
  // ------------------------------------------------------------------

  private val unboundPropCls: RirClass = RirClass(
    name = "Foo",
    isStatic = false,
    properties = listOf(
      RirProperty(name = "Bound", type = RirStringType(), isReadOnly = true, isStatic = false),
      RirProperty(
        name = "Unbound",
        type = RirObjectHandleType(namespace = "Acme.Other", name = "NotBound"),
        isReadOnly = true,
        isStatic = false,
      ),
    ),
  )

  @Test
  fun `bridgeableProperties excludes properties whose type is not v1-bridgeable`() {
    // "NotBound" is deliberately absent from boundHandleTypes.
    val result: List<RirProperty> = bridgeableProperties(unboundPropCls, emptySet())

    assertEquals(
      listOf(unboundPropCls.properties[0]),
      result,
      "a property referencing an unbound handle type must be excluded — only Bound must survive",
    )
  }

  // ------------------------------------------------------------------
  // 4. ADR-053 (ROADMAP line 157 unblock): "rule 4" — a handle-typed settable property used to
  // emit a PropertyGetter slot only, NEVER a PropertySetter slot, even when isReadOnly=false. That
  // rule is now deleted: a settable handle-typed property ALWAYS gets a PropertySetter slot,
  // whatever its (non-)nullable annotation, because a C# property carries exactly one
  // NullableAttribute and a Kotlin `var`'s getter/setter can now share that single type (`Foo?`
  // or `Foo`).
  // ------------------------------------------------------------------

  private val handleSettablePropCls: RirClass = RirClass(
    name = "Template",
    isStatic = false,
    properties = listOf(
      RirProperty(
        name = "Parent",
        type = RirObjectHandleType(namespace = "Sample.Text", name = "Template"),
        isReadOnly = false,
        isStatic = false,
      ),
    ),
  )

  private val handleSettableBoundTypes: Set<RirTypeKey> =
    setOf(RirTypeKey("Sample.Text", "Template"))

  @Test
  fun `handle-typed settable property emits both a getter slot and a setter slot`() {
    val registrables: List<RirRegistrable> =
      bridgeableRegistrables(handleSettablePropCls, handleSettableBoundTypes)

    assertTrue(
      registrables.any { it is RirRegistrable.PropertyGetter && it.property.name == "Parent" },
      "expected a PropertyGetter for the handle-typed Parent property — got $registrables",
    )
    assertTrue(
      registrables.any { it is RirRegistrable.PropertySetter && it.property.name == "Parent" },
      "ADR-053: rule 4 is deleted — a handle-typed settable property must get a PropertySetter " +
          "slot too — got $registrables",
    )
  }

  private val nullableHandleSettablePropCls: RirClass = RirClass(
    name = "NicknameBook",
    isStatic = false,
    properties = listOf(
      RirProperty(
        name = "Favourite",
        type = RirObjectHandleType(
          namespace = "Sample.Nullability", name = "Nickname", nullable = true,
        ),
        isReadOnly = false,
        isStatic = false,
      ),
      RirProperty(
        name = "Primary",
        type = RirObjectHandleType(
          namespace = "Sample.Nullability", name = "Nickname", nullable = false,
        ),
        isReadOnly = false,
        isStatic = false,
      ),
    ),
  )

  private val nicknameBoundTypes: Set<RirTypeKey> =
    setOf(RirTypeKey("Sample.Nullability", "Nickname"))

  @Test
  fun `ADR-053 a settable handle-typed property always gets a PropertySetter slot, nullable or not`() {
    val registrables: List<RirRegistrable> =
      bridgeableRegistrables(nullableHandleSettablePropCls, nicknameBoundTypes)

    assertTrue(
      registrables.any { it is RirRegistrable.PropertySetter && it.property.name == "Favourite" },
      "ADR-053: a nullable-handle-typed settable property must get a PropertySetter slot " +
          "now that rule 4 is deleted — got $registrables",
    )
    assertTrue(
      registrables.any { it is RirRegistrable.PropertySetter && it.property.name == "Primary" },
      "ADR-053: a non-null-handle-typed settable property must get a PropertySetter slot " +
          "now that rule 4 is deleted — got $registrables",
    )
  }

  // ------------------------------------------------------------------
  // 5. Rule 5 (human-approved v1 scope call): member-name collisions with the ADR-051 wrapper's
  //    own members (handle, close, cleaner) are skipped + diagnosed. Statics are unaffected —
  //    they live in the companion object, a separate namespace.
  // ------------------------------------------------------------------

  private val collisionCls: RirClass = RirClass(
    name = "Foo",
    isStatic = false,
    constructors = listOf(RirConstructor(parameters = emptyList())),
    methods = listOf(
      // control: non-colliding instance method, must survive the collision filter untouched.
      RirMethod(
        name = "Reset", isStatic = false, returnType = RirVoidType, parameters = emptyList(),
      ),
      // colliding: instance Close() shadows the ADR-051 wrapper's own close().
      RirMethod(
        name = "Close", isStatic = false, returnType = RirVoidType, parameters = emptyList(),
      ),
      // safe: a *static* Close overload lives in the companion object, a separate namespace.
      RirMethod(
        name = "Close",
        isStatic = true,
        returnType = RirVoidType,
        parameters = listOf(RirParameter(name = "reason", type = RirStringType())),
      ),
    ),
    properties = listOf(
      // colliding: instance property Handle shadows the ADR-051 wrapper's own `handle` field.
      RirProperty(name = "Handle", type = RirStringType(), isReadOnly = true, isStatic = false),
    ),
  )

  @Test
  fun `bridgeableRegistrables excludes colliding instance members but keeps the non-colliding instance method and the static Close overload`() {
    val registrables: List<RirRegistrable> = bridgeableRegistrables(collisionCls, emptySet())

    assertTrue(
      registrables.any { it is RirRegistrable.Method && it.method.name == "Reset" },
      "non-colliding instance method Reset must still be registered — got $registrables",
    )
    assertFalse(
      registrables.any {
        it is RirRegistrable.Method && it.method.name == "Close" && !it.method.isStatic
      },
      "the colliding instance Close() must be skipped — got $registrables",
    )
    assertFalse(
      registrables.any { it is RirRegistrable.PropertyGetter && it.property.name == "Handle" },
      "the colliding instance property Handle must be skipped — got $registrables",
    )
    assertTrue(
      registrables.any {
        it is RirRegistrable.Method && it.method.name == "Close" && it.method.isStatic
      },
      "the static Close(string) overload lives in the companion object and must NOT be skipped " +
          "— got $registrables",
    )
  }

  @Test
  fun `collisionDiagnostics reports the colliding instance property Handle and instance method Close`() {
    val diagnostics: List<RirDiagnostic> = collisionDiagnostics(collisionCls)
    val names: Set<String> = diagnostics.map { it.memberName }.toSet()

    assertTrue(
      "Handle" in names,
      "expected a skip diagnostic naming the colliding instance property Handle — " +
          "got $diagnostics",
    )
    assertTrue(
      "Close" in names,
      "expected a skip diagnostic naming the colliding instance method Close — got $diagnostics",
    )
    assertTrue(
      diagnostics.all { it.kind == RirDiagnosticKind.SKIPPED_MEMBER_NAME_COLLISION },
      "every collision diagnostic must use RirDiagnosticKind.SKIPPED_MEMBER_NAME_COLLISION " +
          "— got $diagnostics",
    )
  }

  // ------------------------------------------------------------------
  // ADR-054: contractHash() — a pure, deterministic function of the ordered registrable list,
  // shared by both generators. Not itself a fixed value (only "same input -> same output" and
  // "different input -> [overwhelmingly likely] different output" matter here).
  // ------------------------------------------------------------------

  private val templateCls: RirClass = RirClass(
    name = "Template",
    isStatic = false,
    constructors = listOf(
      RirConstructor(parameters = listOf(RirParameter(name = "source", type = RirStringType()))),
    ),
    methods = listOf(
      RirMethod(
        name = "Parse",
        isStatic = true,
        returnType = RirObjectHandleType(namespace = "Sample.Text", name = "Template"),
        parameters = listOf(RirParameter(name = "source", type = RirStringType())),
      ),
    ),
  )

  @Test
  fun `contractHash is a pure function of cls and registrables — same input, same output`() {
    val registrables: List<RirRegistrable> = bridgeableRegistrables(templateCls, emptySet())
    val boundTypes: Set<RirTypeKey> = setOf(RirTypeKey("Sample.Text", "Template"))
    val registrablesBound: List<RirRegistrable> = bridgeableRegistrables(templateCls, boundTypes)

    assertEquals(
      contractHash(templateCls, registrablesBound, emptyMap()),
      contractHash(templateCls, registrablesBound, emptyMap()),
      "contractHash must be deterministic for the exact same (cls, registrables) input",
    )
    // Sanity: with an empty boundTypes set, Parse's handle return is not v1-bridgeable, so this
    // registrables list differs (ctor-only) from registrablesBound (ctor + Parse) — asserted only
    // to document that the two fixtures below really do differ, not a contractHash property.
    assertTrue(registrables.size < registrablesBound.size)
  }

  @Test
  fun `contractHash changes when a parameter's nullability changes`() {
    val boundTypes: Set<RirTypeKey> = setOf(RirTypeKey("Sample.Text", "Template"))
    val registrables: List<RirRegistrable> = bridgeableRegistrables(templateCls, boundTypes)

    val nullableCls: RirClass = templateCls.copy(
      constructors = listOf(
        RirConstructor(
          parameters = listOf(RirParameter(name = "source", type = RirStringType(nullable = true))),
        ),
      ),
    )
    val nullableRegistrables: List<RirRegistrable> = bridgeableRegistrables(nullableCls, boundTypes)

    assertTrue(
      contractHash(templateCls, registrables, emptyMap()) != contractHash(
        nullableCls,
        nullableRegistrables,
        emptyMap()
      ),
      "ADR-054: a same-arity nullability-only change must change the hash — a plain slotCount " +
          "comparison cannot see this class of drift, which is the whole point of the hash",
    )
  }

  @Test
  fun `contractHash changes when a registrable is added`() {
    val boundTypes: Set<RirTypeKey> = setOf(RirTypeKey("Sample.Text", "Template"))
    val ctorOnly: RirClass = templateCls.copy(methods = emptyList())
    val ctorOnlyRegistrables: List<RirRegistrable> = bridgeableRegistrables(ctorOnly, boundTypes)
    val fullRegistrables: List<RirRegistrable> = bridgeableRegistrables(templateCls, boundTypes)

    assertTrue(
      contractHash(ctorOnly, ctorOnlyRegistrables, emptyMap()) != contractHash(
        templateCls,
        fullRegistrables,
        emptyMap()
      ),
      "contractHash must change when the registrable list's arity changes",
    )
  }

  // ------------------------------------------------------------------
  // 5. ADR-056: abiArgs/abiOutArgs/abiReturnType — the shared struct-expansion functions both
  //    generators MUST derive their ABI argument lists from.
  // ------------------------------------------------------------------

  private val pointStruct = RirStruct(
    name = "Point",
    components = listOf(
      RirStructComponent(name = "x", readName = "X", type = RirPrimitiveType("int")),
      RirStructComponent(name = "y", readName = "Y", type = RirPrimitiveType("int")),
    ),
  )
  private val pointType = RirStructType(namespace = "Sample.Structs", name = "Point")
  private val structs: Map<RirTypeKey, RirStruct> =
    mapOf(RirTypeKey("Sample.Structs", "Point") to pointStruct)

  @Test
  fun `abiArgs expands a struct-typed parameter into one component argument per field, ABI-named param_Component`() {
    val params = listOf(
      RirParameter(name = "p", type = pointType),
      RirParameter(name = "dx", type = RirPrimitiveType("int")),
    )

    val args: List<AbiArg> = abiArgs(params, structs)

    assertEquals(
      listOf(
        AbiArg("p_X", RirPrimitiveType("int"), isOutPointer = false),
        AbiArg("p_Y", RirPrimitiveType("int"), isOutPointer = false),
        AbiArg("dx", RirPrimitiveType("int"), isOutPointer = false),
      ),
      args,
    )
  }

  @Test
  fun `abiArgs passes a non-struct parameter through unchanged`() {
    val params = listOf(RirParameter(name = "name", type = RirStringType()))
    assertEquals(
      listOf(AbiArg("name", RirStringType(), isOutPointer = false)),
      abiArgs(params, structs),
    )
  }

  @Test
  fun `abiOutArgs is empty for a non-struct return`() {
    assertEquals(emptyList(), abiOutArgs(RirPrimitiveType("int"), structs))
    assertEquals(emptyList(), abiOutArgs(RirVoidType, structs))
  }

  @Test
  fun `abiOutArgs expands a struct-typed return into one out-pointer argument per field, ABI-named outComponent`() {
    val outArgs: List<AbiArg> = abiOutArgs(pointType, structs)

    assertEquals(
      listOf(
        AbiArg("outX", RirPrimitiveType("int"), isOutPointer = true),
        AbiArg("outY", RirPrimitiveType("int"), isOutPointer = true),
      ),
      outArgs,
    )
  }

  @Test
  fun `abiReturnType maps a struct return to void and leaves every other return unchanged`() {
    assertEquals(RirVoidType, abiReturnType(pointType, structs))
    assertEquals(RirPrimitiveType("int"), abiReturnType(RirPrimitiveType("int"), structs))
    assertEquals(RirVoidType, abiReturnType(RirVoidType, structs))
  }

  @Test
  fun `contractHash expands a struct parameter's components, so adding a field to the struct changes the hash`() {
    val translate = RirMethod(
      name = "Translate",
      isStatic = true,
      returnType = pointType,
      parameters = listOf(RirParameter(name = "p", type = pointType)),
    )
    val cls = RirClass(name = "Geometry", isStatic = true, methods = listOf(translate))
    val registrables: List<RirRegistrable> = listOf(RirRegistrable.Method(translate))

    val hashBefore: Long = contractHash(cls, registrables, structs)

    // Simulates the C# struct gaining a third field: the METHOD signature is textually
    // unchanged (still "Translate(Point): Point"), but the thunk's real ABI arity would grow by
    // one — exactly the same-arity drift ADR-054 exists to catch, and slotCount cannot see it
    // (the method count is unchanged either).
    val widerStruct = pointStruct.copy(
      components = pointStruct.components + RirStructComponent(
        name = "z", readName = "Z", type = RirPrimitiveType("int"),
      ),
    )
    val widerStructs: Map<RirTypeKey, RirStruct> =
      mapOf(RirTypeKey("Sample.Structs", "Point") to widerStruct)
    val hashAfter: Long = contractHash(cls, registrables, widerStructs)

    assertTrue(
      hashBefore != hashAfter,
      "contractHash must change when a struct referenced by a method signature gains a " +
          "component, even though the method's own signature text is unchanged",
    )
  }

  // ADR-058 Decision 5: `structContractHash` (the struct's OWN register export) already expands
  // its components as "name:type", but `signaturePart()` — used when some OTHER method merely
  // REFERENCES the struct — expands components as types only. A reorder of two same-typed
  // components is therefore invisible to contractHash today for every method that mentions the
  // struct, even though the struct's OWN slot ordering already depends on it. This is exactly the
  // "stale shim writes each component into the other's ABI slot" corruption class ADR-054 exists
  // to catch, and Shape B makes a same-typed-component reorder source-compatible in C# (a field
  // reorder), where for Shape A it was already a breaking constructor-parameter reorder.
  @Test
  fun `signaturePart must expand a struct reference by component NAMES, not just types, so swapping two same-typed components changes the contractHash of a method that merely mentions the struct`() {
    // Sample.Structs.Extent { public int Width; public int Height; } — both components are `int`,
    // so a name-only swap leaves every type-only signature identical.
    val extentWidthHeight = RirStruct(
      name = "Extent",
      components = listOf(
        RirStructComponent(name = "width", readName = "Width", type = RirPrimitiveType("int")),
        RirStructComponent(name = "height", readName = "Height", type = RirPrimitiveType("int")),
      ),
    )
    val extentHeightWidth = extentWidthHeight.copy(
      components = listOf(
        RirStructComponent(name = "height", readName = "Height", type = RirPrimitiveType("int")),
        RirStructComponent(name = "width", readName = "Width", type = RirPrimitiveType("int")),
      ),
    )
    val extentType = RirStructType(namespace = "Sample.Structs", name = "Extent")

    // `Extents.Grow` only MENTIONS Extent as a parameter/return type — it does not own Extent's
    // register export, so this goes through signaturePart(), not structContractHash().
    val grow = RirMethod(
      name = "Grow",
      isStatic = true,
      returnType = extentType,
      parameters = listOf(RirParameter(name = "e", type = extentType)),
    )
    val cls = RirClass(name = "Extents", isStatic = true, methods = listOf(grow))
    val registrables: List<RirRegistrable> = listOf(RirRegistrable.Method(grow))

    val structsBefore: Map<RirTypeKey, RirStruct> =
      mapOf(RirTypeKey("Sample.Structs", "Extent") to extentWidthHeight)
    val structsAfter: Map<RirTypeKey, RirStruct> =
      mapOf(RirTypeKey("Sample.Structs", "Extent") to extentHeightWidth)

    val hashBefore: Long = contractHash(cls, registrables, structsBefore)
    val hashAfter: Long = contractHash(cls, registrables, structsAfter)

    assertTrue(
      hashBefore != hashAfter,
      "signaturePart() must expand a referenced struct's component NAMES (not just types), so " +
          "reordering Extent's Width/Height fields changes the contractHash of every method that " +
          "mentions Extent — this currently passes silently (ADR-058 Decision 5) because " +
          "signaturePart() only expands component TYPES for a struct REFERENCE, unlike " +
          "structContractHash which already hashes the struct's OWN components as name:type",
    )
  }
}
