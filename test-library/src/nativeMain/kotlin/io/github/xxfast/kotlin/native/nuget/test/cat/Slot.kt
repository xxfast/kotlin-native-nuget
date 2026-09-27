package io.github.xxfast.kotlin.native.nuget.test.cat

// Nullable properties on a generic class. `previous` is always null (the lifted getter used to
// force `!!`), `current` proves a nullable-typed getter still passes a real value through, and
// since `T` is unconstrained (upper bound `Any?`) the constructor argument itself may be null:
// `Slot<string?>(null)` reads null back through every getter. Rides ADR-083's null-pointer
// decision.
class Slot<T>(val value: T) {
  val previous: T? = null
  val current: T? = value
}
