package io.github.xxfast.kotlin.native.nuget.test.workshop

import io.github.xxfast.kotlin.native.nuget.internal.NugetManagedException
import test.workshop.Workshop

// The reverse delegate-parameter feature: a C# method that declares a `Func<>`, `Action<>`,
// `Predicate<T>` or package-declared delegate parameter, called from Kotlin with an ORDINARY
// Kotlin lambda.
//
//   C# IntegrationTests
//     -> (forward bridge, Interop.cs)        WorkshopSample.*
//       -> Kotlin test-library               WorkshopSample.kt (this file)
//         -> (reverse bridge, this feature)  test.workshop.{Workshop, Transform}
//           -> real C# TestDependency        Test.Workshop.{Workshop, Transform}
//
// EXPECTED NOT TO COMPILE TODAY. Verified against the shipped reader on 2026-09-22: every
// delegate-taking member of `Test.Workshop.Workshop` is dropped with
// `skipped_unbound_generic_instantiation` (or `skipped_unbound_type_reference` for the
// non-generic `System.Action`), and `Transform` is extracted as an ordinary CLASS with an
// `Invoke` method and no constructor, so `applyNamed` today wants a handle no Kotlin code can
// build. The only members the generator emits are `applyNamed`, `runKept`, `runKeptOnPool` and
// `forget`. So this file is the failing half of the feature, by design.
//
// Organised by the SEAM each function crosses, because the crossing is a one-slot Kotlin bridge
// and a driver built only from `{ it * 2 }` over `Func<int,int>` would go green while the string
// conversion, the nullability, the stored lifetime, the off-thread invoke and the throw channel
// were all still wrong:
//
//   static route | instance route | void slot return | converting payload | non-converting
//   payload | custom delegate | Predicate<T> | arity 4 | nullable delegate ARGUMENT | nullable
//   DELEGATE | method-context nullability encoding | stored, invoked later | stored, invoked on a
//   POOL thread | released on drop | released on owner dispose | overload set differing only by
//   delegate shape | a Kotlin throw inside the lambda.

private const val NO_THROW = "no throw"

/** `managedType|message` for a caught reverse throw: ADR-104's channel, as in `KennelSample`. */
private fun describe(e: NugetManagedException): String = "${e.managedType}|${e.message}"

// ---------------------------------------------------------------------------------------------
// Synchronous, per-call delegates.
// ---------------------------------------------------------------------------------------------

/**
 * STATIC route (no receiver handle in the thunk), and REENTRANT: C# invokes the lambda twice,
 * nested, on the calling thread inside the same crossing.
 */
fun workshopTwice(seed: Int): Int = Workshop.twice(seed) { it * 2 }

/** The INSTANCE route, same shape. `Int` in and out needs no conversion anywhere. */
fun workshopDoubleTwice(seed: Int): Int = Workshop().use { it.apply(seed) { value -> value * 2 } }

/**
 * `Action<String>`: a `Unit` slot return and a CONVERTING payload, and C# invokes it more than
 * once, so a slot that works exactly once fails here.
 */
fun workshopNames(): String = Workshop().use { workshop ->
  buildList { workshop.forEachName { name -> add(name) } }.joinToString(",")
}

/**
 * The package-declared `Transform`, which binds as `(Int) -> Int` plus a `typealias Transform`
 * carrying the C# name. The typealias is spelled out here on purpose: it is the surface promise,
 * and a lambda literal alone would not notice if it were missing.
 */
// DISABLED 2026-09-22 (ADR-158, custom delegates are step 4 of the item and did not land in this
// pass): a package-declared `delegate` is still a named reader skip, so `applyNamed` is not
// generated and no `typealias Transform` exists. The xunit row that drives this is skipped with the
// same reason. Kept verbatim, commented, so re-enabling it is one uncomment plus removing the Skip.
// fun workshopApplyNamed(seed: Int): Int {
//   val triple: test.workshop.Transform = { it * 3 }
//   return Workshop().use { it.applyNamed(seed, triple) }
// }
// The forward export has to keep EXISTING (the skipped xunit row still references it and the
// IntegrationTests assembly must compile), so it throws rather than returning a plausible number:
// a stub that answered 63 would make the row pass the day someone deletes the Skip, with no
// delegate crossing anywhere in it.
fun workshopApplyNamed(seed: Int): Int =
  error("ADR-158 step 4: a package-declared C# delegate does not bind yet (seed=$seed)")

/**
 * `Predicate<String>`: a `Boolean` slot return, and short-circuiting on the C# side, so the
 * lambda is invoked once for a short name and twice for a long one.
 */
fun workshopAnyLong(minLength: Int): Boolean =
  Workshop().use { it.anyLong { name -> name.length >= minLength } }

/**
 * Arity FOUR: the lifted slot ceiling, six C parameters behind the Kotlin lambda. C# passes
 * 1, 2, 3, 4 and the lambda weights them by place value, so a slot that shuffles its arguments
 * gives a different number rather than the same sum.
 */
fun workshopSum4(): Int = Workshop.sum4 { a, b, c, d -> a * 1000 + b * 100 + c * 10 + d }

/**
 * `Action<String?>`: the delegate's own ARGUMENT is a nullable reference, and C# invokes it once
 * with a value and once with `null`. A binding that lost the second `NullableAttribute` byte and
 * bound this `(String) -> Unit` fails on the second invoke, not silently.
 */
fun workshopShout(): String {
  val seen = mutableListOf<String>()
  val result = Workshop().use { workshop ->
    workshop.shout { name -> seen += (name ?: "<null>") }
  }
  return "$result:${seen.joinToString(",")}"
}

/** A NULLABLE delegate, present: the lambda's own return is a nullable reference too. */
fun workshopDescribeWithLabel(): String =
  Workshop().use { it.describeOrDefault { "Oreo and Mylo" } }

/** The same nullable delegate, ABSENT: `null` crosses the wire as a zero pointer. */
fun workshopDescribeWithoutLabel(): String = Workshop().use { it.describeOrDefault(null) }

/**
 * The method-level `NullableContextAttribute(2)` encoding (no per-parameter attribute at all).
 * Both spellings run, because the whole risk here is that the parameter binds non-null.
 */
fun workshopMaybe(): String = Workshop().use { workshop ->
  val present: String? = workshop.maybe { it + 1 }
  val absent: String? = workshop.maybe(null)
  "${present ?: "<null>"}/${absent ?: "<null>"}"
}

/**
 * The overload set differing ONLY by delegate shape. A bare lambda is ALWAYS an overload
 * resolution ambiguity against this pair in Kotlin (verified by spike, 2026-09-21), so both call
 * sites use an anonymous function, which is exactly the workaround the feature's info diagnostic
 * has to name.
 */
fun workshopRunBoth(): String {
  val action = Workshop.run(fun() { /* a `Unit` anonymous function picks the `Action` overload */ })
  val func = Workshop.run(fun(): Int = 7)
  return "$action/$func"
}

// ---------------------------------------------------------------------------------------------
// The throw channel: a Kotlin throw INSIDE the lambda must reach the Kotlin caller as a
// catchable exception rather than killing the host. `error(...)` throws
// kotlin.IllegalStateException, which the ADR-029 table maps to
// TestLibrary.KotlinInvalidOperationException on the C# side, and ADR-104 carries that type name
// home, so this string is the whole four-hop assertion.
// ---------------------------------------------------------------------------------------------

/** The lambda throws on its FIRST invoke, inside the reentrant `apply`. */
fun workshopThrowing(): String = Workshop().use { workshop ->
  try {
    workshop.apply(1) { error("boom from Kotlin") }
    NO_THROW
  } catch (e: NugetManagedException) {
    describe(e)
  }
}

/** The non-throwing sibling on the SAME receiver: the throw must not poison the crossing. */
fun workshopThrowingThenFine(): String = Workshop().use { workshop ->
  val thrown = try {
    workshop.apply(1) { error("boom from Kotlin") }
    NO_THROW
  } catch (e: NugetManagedException) {
    e.managedType
  }
  "$thrown/${workshop.apply(3) { it * 2 }}"
}

// ---------------------------------------------------------------------------------------------
// The STORED lifetime. Metadata cannot say whether a callee stores its delegate, so one rule has
// to be safe for the stored case: the C# delegate owns the Kotlin lambda and the .NET GC releases
// it. `held` is minted lazily, never at file initialisation, because a top-level reverse handle
// would be constructed before the C# registration `[ModuleInitializer]` has necessarily fired.
// ---------------------------------------------------------------------------------------------

private var held: Workshop? = null

private fun heldWorkshop(): Workshop = held ?: Workshop().also { held = it }

/**
 * Stores a CAPTURING lambda in C# and then makes it unreachable from Kotlin: after this returns,
 * nothing on the Kotlin side roots the lambda, so a per-call borrow would free it here and the
 * later invokes would be use-after-free.
 */
fun workshopKeep(factor: Int) {
  heldWorkshop().keep { it * factor }
}

/** Invokes the stored lambda LATER, long after the call that passed it returned. */
fun workshopRunKept(seed: Int): Int = heldWorkshop().runKept(seed)

/** Invokes the stored lambda on a .NET POOL thread, not the one that passed it. */
suspend fun workshopRunKeptOnPool(seed: Int): Int = heldWorkshop().runKeptOnPool(seed)

/** Drops the stored delegate on the C# side, which is what makes the Kotlin lambda releasable. */
fun workshopForget() {
  heldWorkshop().forget()
}

/**
 * Disposes the OWNER without forgetting first: the stored delegate dies with the `Workshop`, so
 * this is the "released on owner dispose" leak row rather than a second copy of `forget`.
 */
fun workshopDisposeHeld() {
  held?.close()
  held = null
}

// ---------------------------------------------------------------------------------------------
// Leak-harness drivers. A fresh CAPTURING lambda per crossing is the point: a non-capturing
// lambda is a singleton and the ADR-089 reuse table would hand back the same bridge every time,
// so a per-crossing leak would never appear.
// ---------------------------------------------------------------------------------------------

/** One crossing, one freshly captured lambda, one value back. */
fun workshopScale(seed: Int, factor: Int): Int =
  Workshop().use { workshop -> workshop.apply(seed) { it * factor } }

/** The same crossing, ending in a throw: the fault path must free what the happy path frees. */
fun workshopScaleThrowing(factor: Int): String = Workshop().use { workshop ->
  try {
    workshop.apply(1) { error("boom x$factor") }
    NO_THROW
  } catch (e: NugetManagedException) {
    e.message ?: "<no message>"
  }
}
