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
 * [bridgeableProperties] excludes static/non-v1-typed properties (ROADMAP line 152), rule 4
 * (handle-typed settable properties get a getter slot only), and rule 5 (member-name collisions
 * with the ADR-051 wrapper's own `handle`/`close`/`cleaner` members are skipped + diagnosed via
 * [collisionDiagnostics], reusing the existing [RirDiagnostic] model).
 */
class RirBridgingTest {

  // ------------------------------------------------------------------
  // 1. Total registration ordering: Ctor, static methods, instance methods, then
  //    PropertyGetter/[PropertySetter] pairs per bridgeable instance property — groups appended,
  //    never interleaved (the load-bearing contract both generators derive their output from).
  // ------------------------------------------------------------------

  private val orderingCls: RirClass = RirClass(
    name = "Template",
    isStatic = false,
    constructors = listOf(
      RirConstructor(parameters = listOf(RirParameter(name = "source", type = RirStringType))),
    ),
    methods = listOf(
      RirMethod(name = "StaticA", isStatic = true, returnType = RirVoidType, parameters = emptyList()),
      RirMethod(name = "StaticB", isStatic = true, returnType = RirVoidType, parameters = emptyList()),
      RirMethod(name = "InstanceA", isStatic = false, returnType = RirVoidType, parameters = emptyList()),
      RirMethod(name = "InstanceB", isStatic = false, returnType = RirVoidType, parameters = emptyList()),
    ),
    properties = listOf(
      RirProperty(name = "Name", type = RirStringType, isReadOnly = true, isStatic = false),
      RirProperty(name = "Count", type = RirPrimitiveType("int"), isReadOnly = false, isStatic = false),
    ),
  )

  private val orderingBoundTypes: Set<RirTypeKey> = setOf(RirTypeKey("Sample.Text", "Template"))

  @Test
  fun `bridgeableRegistrables places ctor, static methods, instance methods, then property getter-setter pairs, in that order`() {
    val expected: List<RirRegistrable> = listOf(
      RirRegistrable.Ctor(orderingCls.constructors[0]),
      RirRegistrable.Method(orderingCls.methods[0]), // StaticA
      RirRegistrable.Method(orderingCls.methods[1]), // StaticB
      RirRegistrable.Method(orderingCls.methods[2]), // InstanceA
      RirRegistrable.Method(orderingCls.methods[3]), // InstanceB
      RirRegistrable.PropertyGetter(orderingCls.properties[0]), // Name (read-only) getter
      RirRegistrable.PropertyGetter(orderingCls.properties[1]), // Count (settable) getter
      RirRegistrable.PropertySetter(orderingCls.properties[1]), // Count (settable) setter
    )

    val actual: List<RirRegistrable> = bridgeableRegistrables(orderingCls, orderingBoundTypes)

    assertEquals(
      expected,
      actual,
      "Phase 9 line 151 total ordering: ctor, statics, instance methods, then per-property " +
        "getter/[setter] pairs, groups appended (never interleaved) — got $actual",
    )
  }

  // ------------------------------------------------------------------
  // 2. bridgeableProperties: static properties excluded (out of scope, ROADMAP line 152)
  // ------------------------------------------------------------------

  private val staticVsInstancePropCls: RirClass = RirClass(
    name = "Foo",
    isStatic = false,
    properties = listOf(
      RirProperty(name = "StaticProp", type = RirStringType, isReadOnly = true, isStatic = true),
      RirProperty(name = "InstanceProp", type = RirStringType, isReadOnly = true, isStatic = false),
    ),
  )

  @Test
  fun `bridgeableProperties excludes static properties`() {
    val result: List<RirProperty> = bridgeableProperties(staticVsInstancePropCls, emptySet())

    assertEquals(
      listOf(staticVsInstancePropCls.properties[1]),
      result,
      "static properties are out of scope (ROADMAP line 152) — only InstanceProp must survive",
    )
  }

  // ------------------------------------------------------------------
  // 3. bridgeableProperties: non-v1-typed properties excluded (unbound handle type)
  // ------------------------------------------------------------------

  private val unboundPropCls: RirClass = RirClass(
    name = "Foo",
    isStatic = false,
    properties = listOf(
      RirProperty(name = "Bound", type = RirStringType, isReadOnly = true, isStatic = false),
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
  // 4. Rule 4 (human-approved v1 scope call): a handle-typed settable property emits a
  //    PropertyGetter slot but NEVER a PropertySetter slot, even though isReadOnly=false.
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

  private val handleSettableBoundTypes: Set<RirTypeKey> = setOf(RirTypeKey("Sample.Text", "Template"))

  @Test
  fun `handle-typed settable property emits a getter slot but no setter slot`() {
    val registrables: List<RirRegistrable> =
      bridgeableRegistrables(handleSettablePropCls, handleSettableBoundTypes)

    assertTrue(
      registrables.any { it is RirRegistrable.PropertyGetter && it.property.name == "Parent" },
      "expected a PropertyGetter for the handle-typed Parent property even though isReadOnly=false " +
        "— got $registrables",
    )
    assertFalse(
      registrables.any { it is RirRegistrable.PropertySetter && it.property.name == "Parent" },
      "rule 4: a handle-typed settable property must NOT get a PropertySetter slot — got $registrables",
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
      RirMethod(name = "Reset", isStatic = false, returnType = RirVoidType, parameters = emptyList()),
      // colliding: instance Close() shadows the ADR-051 wrapper's own close().
      RirMethod(name = "Close", isStatic = false, returnType = RirVoidType, parameters = emptyList()),
      // safe: a *static* Close overload lives in the companion object, a separate namespace.
      RirMethod(
        name = "Close",
        isStatic = true,
        returnType = RirVoidType,
        parameters = listOf(RirParameter(name = "reason", type = RirStringType)),
      ),
    ),
    properties = listOf(
      // colliding: instance property Handle shadows the ADR-051 wrapper's own `handle` field.
      RirProperty(name = "Handle", type = RirStringType, isReadOnly = true, isStatic = false),
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
      registrables.any { it is RirRegistrable.Method && it.method.name == "Close" && !it.method.isStatic },
      "the colliding instance Close() must be skipped — got $registrables",
    )
    assertFalse(
      registrables.any { it is RirRegistrable.PropertyGetter && it.property.name == "Handle" },
      "the colliding instance property Handle must be skipped — got $registrables",
    )
    assertTrue(
      registrables.any { it is RirRegistrable.Method && it.method.name == "Close" && it.method.isStatic },
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
      "expected a skip diagnostic naming the colliding instance property Handle — got $diagnostics",
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
}
