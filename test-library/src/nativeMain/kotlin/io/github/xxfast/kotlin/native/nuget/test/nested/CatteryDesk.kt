package io.github.xxfast.kotlin.native.nuget.test.nested

/**
 * ADR-135 fixture: interfaces that appear at a **parameter-only** position, so a C# class
 * implementing one is handed to Kotlin without that interface ever being returned anywhere.
 *
 * Every positioned interface shipped before this file is also returned somewhere ([Aviary.Keeper]
 * via `currentKeeper()`, [Registry.Keeper] via the return position added for exactly this reason,
 * `Pet` via `Cat.closestFriend`), which is why the gap survived: the set the ADR-084 bridge plan is
 * built from is the **return**-reachable one (`NugetProcessor.reachableInterfaceNames`), not the
 * "return or parameter" set ADR-084's Detection rule claims. An interface reached only through a
 * parameter gets no backing wrapper, no dispatch exports, no bridge state and no factory, so
 * `NugetBridge.HandleFor` falls to its `NotSupportedException` arm and the `finally` then disposes
 * `IntPtr.Zero`, which kills the host with an unlocated Kotlin `NullPointerException`.
 *
 * Four cells, deliberately spanning every position the walk has to learn, because a fixture with
 * only the nested one would go green on a change that special-cases nesting:
 *
 * - [Boarding.Clerk] is the **nested** twin of the ADR sketch's `Registry.Clerk`, passed to
 *   [Boarding.fileVia] and returned by nothing,
 * - [Doorman] is the **top-level** twin. Nothing in the reachability walk looks at nesting, so a
 *   top-level parameter-only interface is in exactly the same state, and an implementation that
 *   fixes only the nested path has to fail here,
 * - [Sitter] is reached only as an ADR-132 **extension receiver** ([Sitter.checkIn]), never as a
 *   declared parameter. ADR-135 leaves open whether a receiver's type shows up in
 *   `publicSignature.parameters` or only on the native call's RECEIVER slot; this cell is what
 *   settles it rather than arguing it,
 * - [ScratchLog] has a `var` member, which is out of ADR-084's v1 slot vocabulary, so its bridge
 *   **plans to null** even once the walk is widened. Its contract is a managed
 *   `NotSupportedException` naming the C# type, not a dead host process.
 *
 * None of the four is returned from anything, at any position, on purpose. Adding a return for any
 * of them makes this whole file vacuous, the same way `Registry.currentKeeper()` made the ADR-133
 * collision cell real and this one invisible.
 *
 * Oreo checks into boarding under protest and audits the treat ledger on the way out. Mylo just
 * wants someone to hold the door.
 */
object Boarding {

  /**
   * Nested interface at a parameter position only. The bridge state has to be named from the
   * enclosing chain (`BoardingClerkBridgeState`) exactly as ADR-133 taught the return-reachable
   * ones.
   */
  interface Clerk {
    fun stamp(): String
  }

  /**
   * The only position [Clerk] appears in. There is no `currentClerk()` and there must never be
   * one.
   */
  fun fileVia(clerk: Clerk): String = "${clerk.stamp()} filed at boarding"

  /** Control: the owner keeps binding whatever happens to the parameter-only interface. */
  fun label(): String = "boarding"
}

/**
 * Top-level parameter-only interface. Nesting is irrelevant to the reachability walk, so this one
 * is unbridged for the same reason [Boarding.Clerk] is.
 */
interface Doorman {
  fun buzz(): String
}

/** The only position [Doorman] appears in. */
fun buzzIn(doorman: Doorman): String = "${doorman.buzz()}, come in"

/**
 * Reached **only** as an ADR-132 extension receiver, never as a declared parameter and never as a
 * return. The receiver slot reuses the parameter lowerings on the C# side, so it crosses the same
 * `HandleOf(receiver, out owned)` prelude and the same `finally` dispose.
 */
interface Sitter {
  fun house(): String
}

/** The only position [Sitter] appears in: a receiver, not a parameter. */
fun Sitter.checkIn(): String = "checked in at ${house()}"

/**
 * The same receiver, one slot to the right: an ADR-132 interface receiver at the extension
 * **property** position. `checkIn()` reaches [Sitter] through the callable route's RECEIVER slot;
 * this one is the only cell in the fixture that reaches it through the **property** plan's arm of
 * `NugetProcessor.reachableInterfaceNames` (ADR-135), so a widening that only walks callable plans
 * leaves `Sitter` unbridged here while `checkIn()` stays green. The body calls back into the
 * implementation rather than echoing the receiver, so a C#-implemented sitter has to dispatch for
 * the string to come out right.
 *
 * Oreo and Mylo need someone at number 9 while the humans are away.
 */
val Sitter.address: String get() = "at ${house()}"

/**
 * The residual, **plans-to-null** case: a `var` member is out of ADR-084's v1 slot vocabulary, so
 * `ForwardInterfaceBridgePlanner.plan` returns null for this interface however reachable it is.
 * The contract is a managed `NotSupportedException` naming the C# implementation, which is what
 * the throw-safety half of ADR-135 buys; today the throw is overwritten by a
 * `NugetMarshal.Dispose(IntPtr.Zero)` in the `finally`.
 */
interface ScratchLog {
  var scratches: Int
}

/** The only position [ScratchLog] appears in. */
fun countScratches(log: ScratchLog): Int = log.scratches
