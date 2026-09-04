package io.github.xxfast.kotlin.native.nuget.test.infirmary

import io.github.xxfast.kotlin.native.nuget.internal.NugetManagedException
import test.infirmary.Infirmary
import test.infirmary.Quarantine

// ADR-104: the reverse thunk error channel (C# throws -> Kotlin catches).
//
//   C# IntegrationTests
//     -> (forward bridge, Interop.cs)   InfirmarySample.*
//       -> Kotlin test-library          InfirmarySample.kt (this file)
//         -> (reverse bridge, ADR-104)  test.infirmary.{Infirmary, Quarantine, Patient}
//           -> real C# TestDependency   Test.Infirmary.{Infirmary, Quarantine, Patient}
//
// Every `*Throws` function below calls a bound C# member that always throws. TODAY the managed
// exception has nowhere to go: it escapes the [UnmanagedCallersOnly] thunk and .NET TERMINATES
// THE HOST rather than unwinding through the Kotlin/Native frame below it, so these functions do
// not return at all and the whole xunit run aborts. That is the intended TDD red state.
//
// Once the channel lands, each one catches a `NugetManagedException(managedType, message)` at the
// Kotlin call site and surfaces `simpleName|managedType|message` forward to C#.
//
// The fixture is organised by RETURN SHAPE, because ADR-104's ledger flags the error path's
// `return default` (leaving out-pointers unwritten) as INFERRED, not verified, and asks for a
// test per shape: void, pass-through scalar, string, bound-class handle, property getter, struct
// out-pointers, and constructor. Mixed on purpose: a fixture built only from `Int` would go
// green while the string and struct paths were still wrong.
//
// Plus one member that is here for its EMITTER rather than its return shape: the property SETTER
// (`myloWardSignSetterThrows`). Fork A puts setters in scope, and it is the only seam where a
// missed error-slot check swallows the exception silently instead of surfacing something visibly
// wrong, so leaving it out would mean no coverage signal at all for that path.

private const val NO_THROW = "no throw"

/** Field separator inside one [describe] triple. */
private const val FIELD = "|"

/** Separator between a throwing probe and its non-throwing sibling's result. */
private const val PART = "~"

/** The .NET type name the reverse error channel carried across (ADR-104 Fork B). */
private fun managedTypeOf(e: Throwable): String = (e as NugetManagedException).managedType

/**
 * `simpleName|managedType|message` for a caught exception. The Kotlin simple name pins ADR-104
 * Fork D (ONE exception type, `NugetManagedException`); `managedType` pins Fork B (the .NET type
 * name); `message` must survive verbatim.
 */
private fun describe(e: Throwable): String =
  listOf(e::class.simpleName ?: "<unknown>", managedTypeOf(e), e.message ?: "<null>")
    .joinToString(FIELD)

private inline fun probe(block: () -> Any?): String =
  try {
    block()
    NO_THROW
  } catch (e: Throwable) {
    describe(e)
  }

// ---------------------------------------------------------------------------------------------
// One throwing member per return shape.
// ---------------------------------------------------------------------------------------------

/** VOID return shape: nothing to return, so only the error slot carries anything back. */
fun oreoDischargeThrows(): String =
  Infirmary().use { infirmary -> probe { infirmary.discharge("Oreo") } }

/** PASS-THROUGH SCALAR return shape: an `Int` that needs no marshalling conversion at all. */
fun oreoTemperatureThrows(): String =
  Infirmary().use { infirmary -> probe { infirmary.temperature("Oreo") } }

/**
 * STRING return shape: the happy path allocates with `StringToCoTaskMemUTF8` and Kotlin frees it
 * with `freeManagedString`. On the error path there is nothing to free, and a stub that reads the
 * null pointer before the error slot reports the wrong failure.
 */
fun oreoChartThrows(): String =
  Infirmary().use { infirmary -> probe { infirmary.chart("Oreo") } }

/**
 * BOUND-CLASS HANDLE return shape. The generated stub wraps the returned pointer in
 * `requireNotNull(ptr) { "... annotates it non-null." }`, so check-the-error-slot-first is
 * observable here: get the order wrong and this surfaces an `IllegalStateException` about a null
 * handle instead of the managed exception.
 */
fun oreoAdmitThrows(): String =
  Infirmary().use { infirmary -> probe { infirmary.admit("Oreo") } }

/** PROPERTY GETTER return shape: a distinct thunk emission site from a method. */
fun oreoOccupancyThrows(): String =
  Infirmary().use { infirmary -> probe { infirmary.occupancy } }

/**
 * STRUCT OUT-POINTER return shape (ADR-056/058 Shape A, `Profile`). This is the shape ADR-104
 * names as unverified: the thunk returns with four out-pointers unwritten, one of them the string
 * component, and the Kotlin stub must check the error slot BEFORE reading any of them. Reading
 * them after a throw is uninitialised `memScoped` memory.
 */
fun oreoExamineThrows(): String =
  Infirmary().use { infirmary -> probe { infirmary.examine("Oreo") } }

/**
 * PROPERTY SETTER emission site. The one seam where a missed error-slot check is SILENT: a setter
 * has no result to guard, so a call site that forgets to read the slot swallows the managed
 * exception and the write looks like it succeeded. Its own thunk in `NugetGenerateShimsTask` and
 * its own call site in `NugetGenerateBindingsTask`, both distinct from [oreoDischargeThrows]'s
 * despite sharing the `void` return.
 */
fun myloWardSignSetterThrows(): String =
  Infirmary().use { infirmary -> probe { infirmary.wardSign = "Mylo" } }

/**
 * CONSTRUCTOR return shape. The generated constructor helper ends in
 * `requireNotNull(ptr) { "... a C# constructor never returns null." }`, which the error slot
 * check has to beat.
 */
fun myloQuarantineCtorThrows(): String = probe { Quarantine("Mylo already has the good pen") }

// ---------------------------------------------------------------------------------------------
// Non-throwing siblings: the channel must not tax the happy path (the reason
// `KotlinNoVacancy_LegsOnly_NonThrowingSiblingSlotStillWorks` exists), and calling one on the
// SAME instance right after a throw proves the host survived AND the receiver handle is intact.
// ---------------------------------------------------------------------------------------------

/** STRING shape, happy path only. Must pass today and keep passing after. */
fun myloChartFor(): String = Infirmary().use { infirmary -> infirmary.chartFor("Mylo") }

/** STRUCT shape, happy path only. Must pass today and keep passing after. */
fun myloExamineCalm(): String =
  Infirmary().use { infirmary -> render(infirmary.examineCalm("Mylo")) }

/** Throw on the string shape, then the non-throwing string sibling on the SAME receiver. */
fun oreoChartThrowsThenMyloChartFor(): String =
  Infirmary().use { infirmary ->
    val thrown = probe { infirmary.chart("Oreo") }
    thrown + PART + infirmary.chartFor("Mylo")
  }

/** Throw on the struct shape, then the non-throwing struct sibling on the SAME receiver. */
fun oreoExamineThrowsThenMyloExamineCalm(): String =
  Infirmary().use { infirmary ->
    val thrown = probe { infirmary.examine("Oreo") }
    thrown + PART + render(infirmary.examineCalm("Mylo"))
  }

private fun render(profile: test.structs.Profile): String =
  "${profile.tag}:${profile.active}:${profile.grade}:${profile.mood}"
